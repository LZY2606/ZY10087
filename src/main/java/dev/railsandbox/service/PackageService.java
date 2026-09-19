package dev.railsandbox.service;

import dev.railsandbox.domain.Models.*;
import dev.railsandbox.persistence.*;
import dev.railsandbox.verify.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class PackageService {
    private final RevisionStore revisions;

    public PackageService(RevisionStore revisions) {
        this.revisions = revisions;
    }

    public byte[] exportPackage(String topologyId, String candidateRuleId, int maxDepth, int maxStates) throws Exception {
        Revision topology = revisions.head("topology", topologyId);
        ApprovalSnapshot approval = revisions.approval();
        Revision baselineRule = revisions.get("rule", approval.ruleRevisionId());
        Revision candidateRule = revisions.head("rule", candidateRuleId);
        Revision scenario = revisions.get("scenario", approval.scenarioRevisionId());
        return exportPackageDirect(
                Json.read(topology.payload(), Topology.class),
                Json.read(baselineRule.payload(), RuleSet.class),
                Json.read(candidateRule.payload(), RuleSet.class),
                Json.read(scenario.payload(), Scenario.class),
                maxDepth, maxStates);
    }

    public byte[] exportPackageDirect(Topology topo, RuleSet baselineRuleSet, RuleSet candidateRuleSet,
                                      Scenario scene, int maxDepth, int maxStates) throws Exception {
        String topologyPayload = Json.write(topo);
        String baselineRulePayload = Json.write(baselineRuleSet);
        String candidateRulePayload = Json.write(candidateRuleSet);
        String scenarioPayload = Json.write(scene);
        VerificationModels.VerificationReport baselineReport = new Verifier(topo, baselineRuleSet)
                .verify(scene, new VerificationModels.SearchBounds(maxDepth, maxStates));
        VerificationModels.VerificationReport candidateReport = new Verifier(topo, candidateRuleSet)
                .verify(scene, new VerificationModels.SearchBounds(maxDepth, maxStates));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "rail-signal-sandbox-validation-package/v1");
        manifest.put("createdAt", "deterministic:omitted");
        manifest.put("maxDepth", maxDepth);
        manifest.put("maxStates", maxStates);
        manifest.put("determinism", "BFS process order train-a,train-b,...; action order follows each process program counter; rejected paths do not terminate search");
        manifest.put("topology", fingerprinted(topologyPayload));
        manifest.put("baselineRule", fingerprinted(baselineRulePayload));
        manifest.put("candidateRule", fingerprinted(candidateRulePayload));
        manifest.put("scenario", fingerprinted(scenarioPayload));
        manifest.put("baselineReportFingerprint", Json.fingerprint(baselineReport));
        manifest.put("candidateReportFingerprint", Json.fingerprint(candidateReport));

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "manifest.json", Json.write(manifest));
            writeEntry(zip, "topology.json", topologyPayload);
            writeEntry(zip, "baseline-rule.json", baselineRulePayload);
            writeEntry(zip, "candidate-rule.json", candidateRulePayload);
            writeEntry(zip, "scenario.json", scenarioPayload);
            writeEntry(zip, "baseline-report.json", Json.write(baselineReport));
            writeEntry(zip, "candidate-report.json", Json.write(candidateReport));
            writeEntry(zip, "README.txt", """
                    Rail Signal Sandbox validation package

                    Import this zip on an offline machine with the same application version.
                    The importer re-runs the bounded BFS verifier and compares report
                    fingerprints, then returns the deterministic shortest counterexample.
                    """);
        }
        return bytes.toByteArray();
    }

    public Map<String, Object> importPackage(byte[] archive) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Map<String, Object> manifest = Json.read(files.get("manifest.json"), LinkedHashMap.class);
        int maxDepth = ((Number) manifest.get("maxDepth")).intValue();
        int maxStates = ((Number) manifest.get("maxStates")).intValue();
        Topology topology = Json.read(files.get("topology.json"), Topology.class);
        RuleSet baselineRule = Json.read(files.get("baseline-rule.json"), RuleSet.class);
        RuleSet candidateRule = Json.read(files.get("candidate-rule.json"), RuleSet.class);
        Scenario scenario = Json.read(files.get("scenario.json"), Scenario.class);
        VerificationModels.VerificationReport baseline = new Verifier(topology, baselineRule)
                .verify(scenario, new VerificationModels.SearchBounds(maxDepth, maxStates));
        VerificationModels.VerificationReport candidate = new Verifier(topology, candidateRule)
                .verify(scenario, new VerificationModels.SearchBounds(maxDepth, maxStates));
        verifyFingerprint("baseline", manifest.get("baselineReportFingerprint"), baseline);
        verifyFingerprint("candidate", manifest.get("candidateReportFingerprint"), candidate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verified", true);
        result.put("manifest", manifest);
        result.put("baseline", baseline);
        result.put("candidate", candidate);
        return result;
    }

    private void verifyFingerprint(String name, Object expected, VerificationModels.VerificationReport report) {
        String actual = Json.fingerprint(report);
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(name + " 报告指纹不一致，导出包可能被改写");
        }
    }

    private Map<String, String> fingerprinted(String payload) {
        return Map.of("fingerprint", Json.sha256(Json.write(Json.read(payload, Object.class))));
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
