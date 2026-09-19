package com.railway.sandbox.service;

import com.railway.sandbox.domain.TopologyAnalysis;
import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;
import com.railway.sandbox.repo.ApprovalEntity;
import com.railway.sandbox.repo.ApprovalRepository;
import com.railway.sandbox.repo.ReportEntity;
import com.railway.sandbox.repo.ReportRepository;
import com.railway.sandbox.repo.RuleSetEntity;
import com.railway.sandbox.repo.RuleSetRepository;
import com.railway.sandbox.repo.ScenarioEntity;
import com.railway.sandbox.repo.ScenarioRepository;
import com.railway.sandbox.repo.TopologyEntity;
import com.railway.sandbox.repo.TopologyRepository;
import com.railway.sandbox.verify.JsonIO;
import com.railway.sandbox.verify.Verifier;
import com.railway.sandbox.verify.VerifyReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Application service: revision storage, approval freeze, verification,
 * candidate comparison and three-way rule merging.
 */
@Service
public class SandboxService {

    private final TopologyRepository topologies;
    private final RuleSetRepository ruleSets;
    private final ScenarioRepository scenarios;
    private final ApprovalRepository approvals;
    private final ReportRepository reports;

    public SandboxService(TopologyRepository topologies, RuleSetRepository ruleSets,
                          ScenarioRepository scenarios, ApprovalRepository approvals,
                          ReportRepository reports) {
        this.topologies = topologies;
        this.ruleSets = ruleSets;
        this.scenarios = scenarios;
        this.approvals = approvals;
        this.reports = reports;
    }

    // ---------------- topology: raw evidence is append-only ----------------

    @Transactional
    public TopologyEntity saveTopology(String name, Topology topology) {
        String canonical = new Verifier(topology, new RuleSet(), emptyScenario()).canonicalTopology();
        String fp = Verifier.Fingerprints.sha256(canonical);
        long rev = topologies.findTopByOrderByRevisionDesc().map(TopologyEntity::getRevision).orElse(0L) + 1;
        String id = "top-" + rev + "-" + fp.substring(0, 8);
        TopologyEntity e = new TopologyEntity(id, rev, name,
                JsonIO.write(topology), fp, Instant.now());
        return topologies.save(e);
    }

    public List<Map<String, Object>> listTopologies() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TopologyEntity e : topologies.findAllByOrderByRevisionDesc()) {
            Map<String, Object> m = base(e);
            m.put("issues", new TopologyAnalysis(readTopology(e)).validate().size());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> base(TopologyEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("revision", e.getRevision());
        m.put("name", e.getName());
        m.put("fingerprint", e.getFingerprint());
        m.put("receivedAt", e.getReceivedAt());
        m.put("lockVersion", e.getLockVersion());
        return m;
    }

    public Topology readTopology(TopologyEntity e) {
        return JsonIO.read(e.getPayloadJson(), Topology.class);
    }

    public TopologyEntity requireTopology(String id) {
        return topologies.findById(id).orElseThrow(() -> new NotFoundException("拓扑版本不存在: " + id));
    }

    public Map<String, Object> topologyDetail(String id) {
        TopologyEntity e = requireTopology(id);
        Topology t = readTopology(e);
        Map<String, Object> m = base(e);
        m.put("payload", t);
        TopologyAnalysis a = new TopologyAnalysis(t);
        List<Map<String, String>> issueList = new ArrayList<>();
        a.validate().forEach(i -> {
            Map<String, String> im = new LinkedHashMap<>();
            im.put("code", i.code);
            im.put("ref", i.ref);
            im.put("message", i.message);
            issueList.add(im);
        });
        m.put("issues", issueList);
        m.put("summary", a.summary());
        return m;
    }

    // ---------------- rule candidates with optimistic conflict ----------------

    private static final List<String> RULE_FLAGS = List.of(
            "requireSectionsFree", "enforceRouteMutex", "requireSwitchPosition",
            "blockSwitchUnderMovement", "requireFlankProtection",
            "releaseOnlyAfterClear", "enforceReleaseOrder", "signalRequiresFullLock");
    private static final List<String> RULE_NUMBERS = List.of("maxShuntSpeed");

    @Transactional
    public RuleSetEntity createRuleCandidate(RuleSet rules, String note) {
        String id = "rule-" + UUID.randomUUID().toString().substring(0, 8);
        rules.note = note == null ? "" : note;
        RuleSetEntity e = new RuleSetEntity(id, rules.version, rules.note, true,
                JsonIO.write(rules), ruleFingerprint(rules), Instant.now());
        return ruleSets.save(e);
    }

    @Transactional
    public RuleSetEntity updateRuleCandidate(String id, long baseVersion, RuleSet incoming) {
        RuleSetEntity e = ruleSets.findById(id).orElseThrow(() -> new NotFoundException("规则不存在: " + id));
        if (e.getLockVersion() != baseVersion) {
            throw new ConflictException("ruleset", id, baseVersion, e.getLockVersion(),
                    flagMap(incoming), flagMap(JsonIO.read(e.getPayloadJson(), RuleSet.class)));
        }
        e.touch(incoming.version, incoming.note, e.isCandidate(),
                JsonIO.write(incoming), ruleFingerprint(incoming), Instant.now());
        return ruleSets.save(e);
    }

    public static Map<String, Object> flagMap(RuleSet r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("version", r.version);
        m.put("note", r.note);
        for (String f : RULE_FLAGS) {
            try {
                m.put(f, RuleSet.class.getField(f).getBoolean(r));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        for (String f : RULE_NUMBERS) {
            try {
                m.put(f, RuleSet.class.getField(f).getInt(r));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return m;
    }

    public static String ruleFingerprint(RuleSet r) {
        return Verifier.Fingerprints.sha256(JsonIO.write(flagMap(r)));
    }

    public RuleSet readRules(RuleSetEntity e) {
        return JsonIO.read(e.getPayloadJson(), RuleSet.class);
    }

    public List<Map<String, Object>> listRules() {
        ApprovalEntity ap = currentApprovalOrNull();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleSetEntity e : ruleSets.findAllByOrderByUpdatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("version", e.getVersion());
            m.put("note", e.getNote());
            m.put("candidate", e.isCandidate());
            m.put("fingerprint", e.getFingerprint());
            m.put("lockVersion", e.getLockVersion());
            m.put("updatedAt", e.getUpdatedAt());
            m.put("approved", ap != null && e.getId().equals(ap.getRuleSetId()));
            m.put("flags", flagMap(readRules(e)));
            out.add(m);
        }
        return out;
    }

    /**
     * Three-way merge of a candidate edit against the current server copy:
     *  - flag unchanged on both sides            -> keep
     *  - only one side changed                   -> take that side
     *  - both sides changed differently          -> recorded conflict, resolved
     *                                               by preferring the current
     *                                               version, exposed for the user
     */
    public Map<String, Object> mergeRuleCandidate(String id, RuleSet base, RuleSet incoming) {
        RuleSetEntity serverEntity = ruleSets.findById(id)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + id));
        RuleSet server = readRules(serverEntity);
        RuleSet merged = new RuleSet();
        merged.version = incoming.version + "-merged";
        merged.note = incoming.note;
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String f : RULE_FLAGS) {
            try {
                Object b = RuleSet.class.getField(f).getBoolean(base);
                Object i = RuleSet.class.getField(f).getBoolean(incoming);
                Object sv = RuleSet.class.getField(f).getBoolean(server);
                Object value = mergeValue(f, b, i, sv, conflicts);
                RuleSet.class.getField(f).setBoolean(merged, (Boolean) value);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        for (String f : RULE_NUMBERS) {
            try {
                Object b = RuleSet.class.getField(f).getInt(base);
                Object i = RuleSet.class.getField(f).getInt(incoming);
                Object sv = RuleSet.class.getField(f).getInt(server);
                Object value = mergeValue(f, b, i, sv, conflicts);
                RuleSet.class.getField(f).setInt(merged, (Integer) value);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        RuleSetEntity mergedEntity = createRuleCandidate(merged,
                "合并自 " + serverEntity.getVersion() + "（冲突 " + conflicts.size() + " 处）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mergedId", mergedEntity.getId());
        out.put("conflicts", conflicts);
        out.put("merged", flagMap(merged));
        return out;
    }

    /** Uniform three-way merge for boolean and numeric rule parameters. */
    private Object mergeValue(String f, Object b, Object mine, Object current,
                              List<Map<String, Object>> conflicts) {
        boolean mineChanged = !java.util.Objects.equals(mine, b);
        boolean currentChanged = !java.util.Objects.equals(current, b);
        if (!mineChanged && !currentChanged) return b;
        if (mineChanged && !currentChanged) return mine;
        if (!mineChanged) return current;
        if (java.util.Objects.equals(mine, current)) return mine;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("flag", f);
        c.put("base", b);
        c.put("yours", mine);
        c.put("current", current);
        c.put("merged", current);
        conflicts.add(c);
        return current;
    }

    // ---------------- scenario versions (fault insertion = new version) ----------------

    private Scenario emptyScenario() { return new Scenario(); }

    @Transactional
    public ScenarioEntity saveScenario(String lineage, String name, Scenario scenario,
                                       String topologyFingerprint) {
        Verifier v = new Verifier(new Topology(), new RuleSet(), scenario);
        String fp = Verifier.Fingerprints.sha256(v.canonicalScenario());
        String lin = lineage == null ? "lin-" + UUID.randomUUID().toString().substring(0, 8) : lineage;
        long no = scenarios.findTopByLineageOrderByVersionNoDesc(lin)
                .map(s -> s.getVersionNo() + 1).orElse(1L);
        String parent = scenarios.findTopByLineageOrderByVersionNoDesc(lin)
                .map(ScenarioEntity::getId).orElse(null);
        String id = lin + ":v" + no;
        ScenarioEntity e = new ScenarioEntity(id, lin, no, parent, name,
                JsonIO.write(scenario), fp, topologyFingerprint, Instant.now());
        return scenarios.save(e);
    }

    public Scenario readScenario(ScenarioEntity e) {
        return JsonIO.read(e.getPayloadJson(), Scenario.class);
    }

    public List<Map<String, Object>> listScenarios() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScenarioEntity e : scenarios.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("lineage", e.getLineage());
            m.put("versionNo", e.getVersionNo());
            m.put("parentVersion", e.getParentVersion());
            m.put("name", e.getName());
            m.put("fingerprint", e.getFingerprint());
            m.put("topologyFingerprint", e.getTopologyFingerprint());
            m.put("createdAt", e.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> scenarioDetail(String id) {
        ScenarioEntity e = scenarios.findById(id).orElseThrow(() -> new NotFoundException("场景版本不存在: " + id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("lineage", e.getLineage());
        m.put("versionNo", e.getVersionNo());
        m.put("parentVersion", e.getParentVersion());
        m.put("name", e.getName());
        m.put("fingerprint", e.getFingerprint());
        m.put("topologyFingerprint", e.getTopologyFingerprint());
        m.put("createdAt", e.getCreatedAt());
        m.put("payload", readScenario(e));
        return m;
    }

    /** Insert/clear a fault from an existing scenario version -> new child version. */
    @Transactional
    public ScenarioEntity insertFault(String scenarioId, String kind, String deviceId, boolean clear) {
        ScenarioEntity base = scenarios.findById(scenarioId)
                .orElseThrow(() -> new NotFoundException("场景版本不存在: " + scenarioId));
        Scenario sc = readScenario(base);
        switch (kind) {
            case "SECTION" -> {
                if (clear) sc.failedSections.remove(deviceId); else sc.failedSections.add(deviceId);
            }
            case "SWITCH" -> {
                if (clear) sc.failedSwitches.remove(deviceId); else sc.failedSwitches.add(deviceId);
            }
            default -> throw new BadRequestException("故障类型必须是 SECTION 或 SWITCH");
        }
        sc.failedSections = new ArrayList<>(new java.util.TreeSet<>(sc.failedSections));
        sc.failedSwitches = new ArrayList<>(new java.util.TreeSet<>(sc.failedSwitches));
        String action = clear ? "故障恢复" : "插入故障";
        return saveScenario(base.getLineage(), base.getName() + "（" + action + " " + deviceId + "）",
                sc, base.getTopologyFingerprint());
    }

    // ---------------- approval freeze ----------------

    public ApprovalEntity currentApprovalOrNull() {
        return approvals.findById(ApprovalEntity.SINGLETON_ID).orElse(null);
    }

    public Map<String, Object> currentApproval() {
        ApprovalEntity a = currentApprovalOrNull();
        if (a == null) return Map.of("approved", false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", true);
        m.put("topologyId", a.getTopologyId());
        m.put("topologyFingerprint", a.getTopologyFingerprint());
        m.put("ruleSetId", a.getRuleSetId());
        m.put("ruleVersion", a.getRuleVersion());
        m.put("ruleFingerprint", a.getRuleFingerprint());
        m.put("scenarioId", a.getScenarioId());
        m.put("scenarioFingerprint", a.getScenarioFingerprint());
        m.put("approvedAt", a.getApprovedAt());
        m.put("approvedBy", a.getApprovedBy());
        return m;
    }

    @Transactional
    public ApprovalEntity approve(String topologyId, String ruleSetId, String scenarioId, String by) {
        TopologyEntity t = requireTopology(topologyId);
        RuleSetEntity r = ruleSets.findById(ruleSetId).orElseThrow(() -> new NotFoundException("规则不存在: " + ruleSetId));
        ScenarioEntity s = scenarios.findById(scenarioId).orElseThrow(() -> new NotFoundException("场景不存在: " + scenarioId));
        Topology topology = readTopology(t);
        List<TopologyAnalysis.Issue> issues = new TopologyAnalysis(topology).validate();
        if (!issues.isEmpty())
            throw new BadRequestException("拓扑存在 " + issues.size() + " 个结构问题，不能批准");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("topology", JsonIO.read(t.getPayloadJson(), Topology.class));
        snapshot.put("rules", JsonIO.read(r.getPayloadJson(), RuleSet.class));
        snapshot.put("scenario", JsonIO.read(s.getPayloadJson(), Scenario.class));
        String snapshotJson = JsonIO.write(snapshot);
        Instant now = Instant.now();
        ApprovalEntity a = approvals.findById(ApprovalEntity.SINGLETON_ID).orElseGet(ApprovalEntity::blank);
        a.freeze(t.getId(), t.getFingerprint(), r.getId(), r.getVersion(), r.getFingerprint(),
                s.getId(), s.getFingerprint(), snapshotJson, now,
                by == null ? "engineer" : by);
        return approvals.save(a);
    }

    // ---------------- verification ----------------

    @Transactional
    public VerifyReport verify(String topologyId, String ruleSetId, String scenarioId, int bound) {
        TopologyEntity t = requireTopology(topologyId);
        RuleSetEntity r = ruleSets.findById(ruleSetId).orElseThrow(() -> new NotFoundException("规则不存在: " + ruleSetId));
        ScenarioEntity s = scenarios.findById(scenarioId).orElseThrow(() -> new NotFoundException("场景不存在: " + scenarioId));
        Topology topology = readTopology(t);
        RuleSet rules = readRules(r);
        Scenario scenario = readScenario(s);
        Verifier verifier = new Verifier(topology, rules, scenario);
        VerifyReport report = verifier.verify(bound);
        String reportId = "rep-" + UUID.randomUUID().toString().substring(0, 8);
        reports.save(new ReportEntity(reportId, report.conclusion, rules.version, r.getFingerprint(),
                t.getFingerprint(), s.getId(), s.getFingerprint(),
                report.trace.size(), bound, JsonIO.write(report), Instant.now()));
        return report;
    }

    public VerifyReport verifyApproved(int bound) {
        ApprovalEntity a = currentApprovalOrNull();
        if (a == null) throw new BadRequestException("尚无批准版本，请先批准拓扑/规则/场景");
        return verify(a.getTopologyId(), a.getRuleSetId(), a.getScenarioId(), bound);
    }

    public List<Map<String, Object>> listReports() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportEntity e : reports.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("conclusion", e.getConclusion());
            m.put("ruleVersion", e.getRuleVersion());
            m.put("ruleFingerprint", e.getRuleFingerprint());
            m.put("topologyFingerprint", e.getTopologyFingerprint());
            m.put("scenarioId", e.getScenarioId());
            m.put("scenarioFingerprint", e.getScenarioFingerprint());
            m.put("traceLength", e.getTraceLength());
            m.put("bound", e.getBound());
            m.put("createdAt", e.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> reportDetail(String id) {
        ReportEntity e = reports.findById(id).orElseThrow(() -> new NotFoundException("报告不存在: " + id));
        return JsonIO.read(e.getReportJson(), Map.class);
    }

    // ---------------- candidate vs approved comparison ----------------

    /**
     * Run the same scenario under the approved rule set and a candidate and
     * lay reachable state counts, rejection reasons and counterexample length
     * side by side so the effect of a rule change is auditable.
     */
    public Map<String, Object> compareWithApproved(String candidateRuleId, String scenarioId, int bound) {
        ApprovalEntity ap = currentApprovalOrNull();
        if (ap == null) throw new BadRequestException("尚无批准版本可比较");
        ScenarioEntity sc = scenarios.findById(scenarioId)
                .orElseThrow(() -> new NotFoundException("场景不存在: " + scenarioId));
        Topology topology = readTopology(requireTopology(ap.getTopologyId()));
        Scenario scenario = readScenario(sc);

        RuleSetEntity approved = ruleSets.findById(ap.getRuleSetId())
                .orElseThrow(() -> new NotFoundException("批准规则缺失: " + ap.getRuleSetId()));
        RuleSetEntity candidate = ruleSets.findById(candidateRuleId)
                .orElseThrow(() -> new NotFoundException("候选规则不存在: " + candidateRuleId));

        VerifyReport approvedReport = new Verifier(topology, readRules(approved), scenario).verify(bound);
        VerifyReport candidateReport = new Verifier(topology, readRules(candidate), scenario).verify(bound);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenarioId", scenarioId);
        out.put("scenarioFingerprint", sc.getFingerprint());
        out.put("bound", bound);
        out.put("approved", reportSummary(approved, approvedReport));
        out.put("candidate", reportSummary(candidate, candidateReport));

        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("reachableStates", candidateReport.reachableStates - approvedReport.reachableStates);
        deltas.put("exploredTransitions", candidateReport.exploredTransitions - approvedReport.exploredTransitions);
        deltas.put("counterexampleLengthDelta",
                ceLength(candidateReport) - ceLength(approvedReport));
        deltas.put("newRejections", keyDiff(candidateReport.rejectionReasons, approvedReport.rejectionReasons));
        deltas.put("removedRejections", keyDiff(approvedReport.rejectionReasons, candidateReport.rejectionReasons));
        out.put("deltas", deltas);
        out.put("sameCounterexamplePath", samePath(approvedReport, candidateReport));
        return out;
    }

    private int ceLength(VerifyReport r) {
        return VerifyReport.COUNTEREXAMPLE.equals(r.conclusion) ? r.trace.size() : Integer.MAX_VALUE;
    }

    private Map<String, Object> reportSummary(RuleSetEntity e, VerifyReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleSetId", e.getId());
        m.put("ruleVersion", r.ruleVersion);
        m.put("ruleFingerprint", e.getFingerprint());
        m.put("conclusion", r.conclusion);
        m.put("limitReached", r.limitReached);
        m.put("reachableStates", r.reachableStates);
        m.put("mergedStates", r.mergedStates);
        m.put("exploredTransitions", r.exploredTransitions);
        m.put("counterexampleLength",
                VerifyReport.COUNTEREXAMPLE.equals(r.conclusion) ? r.trace.size() : null);
        m.put("rejectionReasons", r.rejectionReasons);
        if (VerifyReport.COUNTEREXAMPLE.equals(r.conclusion)) {
            List<String> triggers = new ArrayList<>();
            r.trace.forEach(st -> triggers.add(st.trigger));
            m.put("counterexampleTriggers", triggers);
            List<String> finalV = r.trace.isEmpty() ? List.of() : r.trace.get(r.trace.size() - 1).violations;
            m.put("finalViolations", finalV);
        }
        return m;
    }

    private Map<String, Integer> keyDiff(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new TreeMap<>();
        a.forEach((k, v) -> {
            int d = v - b.getOrDefault(k, 0);
            if (d > 0) out.put(k, d);
        });
        return out;
    }

    private boolean samePath(VerifyReport a, VerifyReport b) {
        if (!VerifyReport.COUNTEREXAMPLE.equals(a.conclusion)
                || !VerifyReport.COUNTEREXAMPLE.equals(b.conclusion)) return false;
        if (a.trace.size() != b.trace.size()) return false;
        for (int i = 0; i < a.trace.size(); i++) {
            if (!a.trace.get(i).trigger.equals(b.trace.get(i).trigger)) return false;
        }
        return true;
    }

    // ---------------- deterministic export / import of a verification package ----------------

    /**
     * The package embeds all inputs and the report. On another machine the
     * importer re-runs verification and asserts the lexicographically minimal
     * counterexample is identical, rather than trusting the stored report.
     */
    public Map<String, Object> exportPackage(String topologyId, String ruleSetId,
                                            String scenarioId, int bound) {
        TopologyEntity t = requireTopology(topologyId);
        RuleSetEntity r = ruleSets.findById(ruleSetId).orElseThrow(() -> new NotFoundException("规则不存在"));
        ScenarioEntity s = scenarios.findById(scenarioId).orElseThrow(() -> new NotFoundException("场景不存在"));
        VerifyReport report = new Verifier(readTopology(t), readRules(r), readScenario(s)).verify(bound);
        Map<String, Object> pkg = new TreeMap<>();
        pkg.put("format", "railway-sandbox-verification/1");
        pkg.put("exportedAt", Instant.now().toString());
        Map<String, Object> inputs = new TreeMap<>();
        inputs.put("topology", JsonIO.read(t.getPayloadJson(), Topology.class));
        inputs.put("rules", JsonIO.read(r.getPayloadJson(), RuleSet.class));
        inputs.put("scenario", JsonIO.read(s.getPayloadJson(), Scenario.class));
        inputs.put("bound", bound);
        pkg.put("inputs", inputs);
        pkg.put("fingerprints", new TreeMap<>(Map.of(
                "topology", t.getFingerprint(),
                "rules", r.getFingerprint(),
                "scenario", s.getFingerprint())));
        pkg.put("expectedReport", JsonIO.read(JsonIO.write(report), Object.class));
        pkg.put("packageFingerprint", Verifier.Fingerprints.sha256(JsonIO.write(inputs)));
        return pkg;
    }

    public Map<String, Object> importAndReplayPackage(Map<String, Object> pkg) {
        Object inputsObj = pkg.get("inputs");
        if (!(inputsObj instanceof Map)) throw new BadRequestException("验证包缺少 inputs");
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) inputsObj;
        Topology topology = JsonIO.read(JsonIO.write(inputs.get("topology")), Topology.class);
        RuleSet rules = JsonIO.read(JsonIO.write(inputs.get("rules")), RuleSet.class);
        Scenario scenario = JsonIO.read(JsonIO.write(inputs.get("scenario")), Scenario.class);
        int bound = ((Number) inputs.getOrDefault("bound", Verifier.DEFAULT_BOUND)).intValue();
        VerifyReport rerun = new Verifier(topology, rules, scenario).verify(bound);

        Object expectedObj = pkg.get("expectedReport");
        VerifyReport expected = expectedObj == null ? null
                : JsonIO.read(JsonIO.write(expectedObj), VerifyReport.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rerunConclusion", rerun.conclusion);
        out.put("rerunTraceLength", rerun.trace.size());
        if (expected == null) {
            out.put("replayMatch", false);
            out.put("reason", "验证包没有携带期望报告");
            return out;
        }
        boolean conclusionMatch = expected.conclusion.equals(rerun.conclusion);
        boolean lengthMatch = expected.trace.size() == rerun.trace.size();
        boolean pathMatch = true;
        List<String> diffs = new ArrayList<>();
        if (!conclusionMatch) {
            pathMatch = false;
            diffs.add("结论不一致: 包内=" + expected.conclusion + " 重放=" + rerun.conclusion);
        }
        if (!lengthMatch) {
            pathMatch = false;
            diffs.add("反例长度不一致: 包内=" + expected.trace.size() + " 重放=" + rerun.trace.size());
        }
        for (int i = 0; i < Math.min(expected.trace.size(), rerun.trace.size()); i++) {
            String a = expected.trace.get(i).trigger;
            String b = rerun.trace.get(i).trigger;
            if (!a.equals(b)) {
                pathMatch = false;
                diffs.add("第 " + (i + 1) + " 步不一致: 包内=" + a + " 重放=" + b);
            }
        }
        out.put("replayMatch", conclusionMatch && lengthMatch && pathMatch);
        out.put("diffs", diffs);
        out.put("rerunTriggers", rerun.trace.stream().map(x -> x.trigger).toList());
        out.put("rerunViolations", rerun.trace.isEmpty() ? List.of()
                : rerun.trace.get(rerun.trace.size() - 1).violations);
        return out;
    }

    public com.railway.sandbox.repo.RuleSetRepository ruleSetStore() { return ruleSets; }
    public com.railway.sandbox.repo.ScenarioRepository scenarioStore() { return scenarios; }
}
