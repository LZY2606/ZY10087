package dev.railsandbox.service;

import dev.railsandbox.domain.Models.*;
import dev.railsandbox.persistence.*;
import dev.railsandbox.verify.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class SandboxService {
    private final RevisionStore revisions;

    public SandboxService(RevisionStore revisions) {
        this.revisions = revisions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (revisions.list("topology").isEmpty() && revisions.list("rule").isEmpty() && revisions.list("scenario").isEmpty()) {
            Revision topology = submitTopology(Json.write(SampleData.topology()), "bootstrap", null);
            submitRule(Json.write(SampleData.approvedRules()), "bootstrap", null);
            submitRule(Json.write(SampleData.weakFlankRules()), "bootstrap", null);
            submitRule(Json.write(SampleData.weakReleaseRules()), "bootstrap", null);
            submitScenario(Json.write(SampleData.scenario()), "bootstrap", topology.revisionId(), topology.sourceFingerprint(), null, null);
            Revision weak = revisions.head("rule", "weak-flank");
            Revision scenario = revisions.head("scenario", "crossing-trains");
            approve(topology.revisionId(), revisions.head("rule", "safe-interlocking").revisionId(), scenario.revisionId());
        }
    }

    public Revision submitTopology(String payload, String source, String baseRevisionId) {
        Topology topology = Json.read(payload, Topology.class);
        List<Diagnostic> issues = new TopologyValidator(topology).validate();
        if (issues.stream().anyMatch(Diagnostic::error)) {
            throw new IllegalArgumentException(Json.write(Map.of("message", "拓扑包含错误，拒绝接收候选", "issues", issues)));
        }
        return revisions.submit("topology", topology.id(), baseRevisionId, Json.write(topology), source, Map.of(), null);
    }

    public Revision submitRule(String payload, String source, String baseRevisionId) {
        RuleSet rule = Json.read(payload, RuleSet.class);
        return revisions.submit("rule", rule.id(), baseRevisionId, Json.write(rule), source, Map.of(), null);
    }

    public Revision submitScenario(String payload, String source, String topologyRevisionId,
                                   String topologyFingerprintOverride, String baseRevisionId, String faultOfRevisionId) {
        Scenario scenario = Json.read(payload, Scenario.class);
        Revision topology = revisionOrHead("topology", topologyRevisionId);
        if (topology == null) {
            throw new IllegalArgumentException("场景必须引用已存在拓扑版本");
        }
        String topologyFingerprint = topologyFingerprintOverride != null ? topologyFingerprintOverride : topology.sourceFingerprint();
        if (!topology.sourceFingerprint().equals(topologyFingerprint)) {
            throw new IllegalArgumentException("拓扑来源指纹不匹配");
        }
        return revisions.submit("scenario", scenario.id(), baseRevisionId, Json.write(scenario), source,
                Map.of("topologyFingerprint", topologyFingerprint, "ruleFingerprint", ""), faultOfRevisionId);
    }

    public ApprovalSnapshot approve(String topologyRevisionId, String ruleRevisionId, String scenarioRevisionId) {
        Revision topology = revisions.get("topology", topologyRevisionId);
        Revision rule = revisions.get("rule", ruleRevisionId);
        Revision scenario = revisions.get("scenario", scenarioRevisionId);
        if (topology == null || rule == null || scenario == null) {
            throw new IllegalArgumentException("批准的三个版本必须都存在");
        }
        if (!Objects.equals(scenario.topologyFingerprint(), topology.sourceFingerprint())) {
            throw new IllegalArgumentException("场景派生拓扑指纹与待批准拓扑不一致");
        }
        List<Diagnostic> issues = new TopologyValidator(Json.read(topology.payload(), Topology.class)).validate();
        if (issues.stream().anyMatch(Diagnostic::error)) {
            throw new IllegalArgumentException("只能批准通过拓扑检查的版本");
        }
        String packageInput = Json.write(Map.of(
                "topology", topology.sourceFingerprint(),
                "rule", rule.sourceFingerprint(),
                "scenario", scenario.sourceFingerprint()
        ));
        ApprovalSnapshot snapshot = new ApprovalSnapshot(
                "approval-" + Instant.now().toEpochMilli(),
                Instant.now(),
                topologyRevisionId,
                ruleRevisionId,
                scenarioRevisionId,
                topology.sourceFingerprint(),
                rule.sourceFingerprint(),
                scenario.sourceFingerprint(),
                Json.sha256(packageInput)
        );
        revisions.saveApproval(snapshot);
        return snapshot;
    }

    public Map<String, Object> compareWithCandidate(String approvalId, String candidateRuleRevisionId,
                                                    String topologyRevisionId, String ruleRevisionId,
                                                    String scenarioRevisionId, int maxDepth, int maxStates) {
        ApprovalSnapshot approval = revisions.approval();
        Revision baselineRule = revisions.get("rule", ruleRevisionId != null ? ruleRevisionId : approval.ruleRevisionId());
        Revision candidateRule = revisions.get("rule", candidateRuleRevisionId);
        Revision topology = revisions.get("topology", topologyRevisionId != null ? topologyRevisionId : approval.topologyRevisionId());
        Revision scenario = revisions.get("scenario", scenarioRevisionId != null ? scenarioRevisionId : approval.scenarioRevisionId());
        VerificationModels.VerificationReport baseline = verify(topology, baselineRule, scenario, maxDepth, maxStates);
        VerificationModels.VerificationReport candidate = verify(topology, candidateRule, scenario, maxDepth, maxStates);
        String runId = "run-" + Instant.now().toEpochMilli();
        Map<String, Object> record = new TreeMap<>();
        record.put("baselineRuleRevisionId", baselineRule.revisionId());
        record.put("candidateRuleRevisionId", candidateRule.revisionId());
        record.put("baseline", baseline);
        record.put("candidate", candidate);
        String report = Json.write(record);
        revisions.saveRun(runId, approval != null ? approval.approvalId() : null, topology.revisionId(),
                baselineRule.revisionId(), scenario.revisionId(), candidateRule.revisionId(),
                maxDepth, maxStates, report, Json.sha256(report));
        record.put("runId", runId);
        record.put("reportFingerprint", Json.sha256(report));
        return record;
    }

    public VerificationModels.VerificationReport verify(Revision topologyRevision, Revision ruleRevision,
                                                        Revision scenarioRevision, int maxDepth, int maxStates) {
        Topology topology = Json.read(topologyRevision.payload(), Topology.class);
        RuleSet rules = Json.read(ruleRevision.payload(), RuleSet.class);
        Scenario scenario = Json.read(scenarioRevision.payload(), Scenario.class);
        return new Verifier(topology, rules).verify(scenario, new VerificationModels.SearchBounds(
                Math.max(1, maxDepth), Math.max(1, maxStates)));
    }

    public Revision insertFault(String scenarioRevisionId, String deviceId, int afterActionIndex, String source) {
        Revision base = revisions.get("scenario", scenarioRevisionId);
        if (base == null) {
            throw new IllegalArgumentException("基础场景版本不存在");
        }
        Scenario scenario = Json.read(base.payload(), Scenario.class);
        ScenarioProcess firstProcess = scenario.processes().get(0);
        int insertionIndex = Math.max(0, Math.min(afterActionIndex, firstProcess.actions().size()));
        ScenarioAction faultAction = new ScenarioAction(
                "fault-" + deviceId + "-" + Instant.now().toEpochMilli(),
                ActionType.DEVICE_FAULT, null, null, deviceId);
        List<ScenarioAction> actions = new ArrayList<>(firstProcess.actions());
        actions.add(insertionIndex, faultAction);
        ScenarioProcess changedProcess = new ScenarioProcess(firstProcess.id(), List.copyOf(actions));
        List<ScenarioProcess> processes = new ArrayList<>(scenario.processes());
        processes.set(0, changedProcess);
        Scenario changed = new Scenario(scenario.id(), scenario.description() + "；手工插入 " + deviceId + " 故障", List.copyOf(processes));
        return submitScenario(Json.write(changed), source,
                latestTopologyFor(base).revisionId(), base.topologyFingerprint(), base.revisionId(), scenarioRevisionId);
    }

    public Revision latestTopologyFor(Revision scenario) {
        return revisions.list("topology").stream()
                .filter(revision -> revision.sourceFingerprint().equals(scenario.topologyFingerprint()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("场景来源拓扑指纹找不到原始证据"));
    }

    private Revision revisionOrHead(String kind, String revisionId) {
        return revisionId == null ? revisions.list(kind).stream().findFirst().orElse(null)
                : revisions.get(kind, revisionId);
    }

    public List<Revision> list(String kind) {
        return revisions.list(kind);
    }

    public Revision get(String kind, String revisionId) {
        return revisions.get(kind, revisionId);
    }
    public ApprovalSnapshot approval() {
        return revisions.approval();
    }
}
