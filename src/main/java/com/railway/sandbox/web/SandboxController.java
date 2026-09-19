package com.railway.sandbox.web;

import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;
import com.railway.sandbox.repo.RuleSetEntity;
import com.railway.sandbox.repo.ScenarioEntity;
import com.railway.sandbox.repo.TopologyEntity;
import com.railway.sandbox.service.SandboxService;
import com.railway.sandbox.verify.Verifier;
import com.railway.sandbox.verify.VerifyReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SandboxController {

    private final SandboxService service;

    public SandboxController(SandboxService service) {
        this.service = service;
    }

    // ---- topology ----

    public record TopologyImportRequest(String name, Topology payload) {}
    public record ScenarioSaveRequest(String lineage, String name, Scenario payload) {}
    public record RuleUpdateRequest(long baseVersion, RuleSet payload) {}
    public record RuleCreateRequest(RuleSet payload, String note) {}
    public record RuleMergeRequest(RuleSet base, RuleSet incoming) {}
    public record FaultRequest(String kind, String deviceId, boolean clear) {}
    public record ApproveRequest(String topologyId, String ruleSetId, String scenarioId, String by) {}

    @PostMapping("/topologies")
    public Map<String, Object> importTopology(@RequestBody TopologyImportRequest req) {
        TopologyEntity e = service.saveTopology(req.name(), req.payload());
        return service.topologyDetail(e.getId());
    }

    @GetMapping("/topologies")
    public Object listTopologies() { return service.listTopologies(); }

    @GetMapping("/topologies/{id}")
    public Map<String, Object> topology(@PathVariable String id) { return service.topologyDetail(id); }

    // ---- rules ----

    @PostMapping("/rules")
    public Object createRule(@RequestBody RuleCreateRequest req) {
        RuleSet rs = req.payload() == null ? new RuleSet() : req.payload();
        RuleSetEntity e = service.createRuleCandidate(rs, req.note());
        return Map.of("id", e.getId(), "lockVersion", e.getLockVersion(),
                "fingerprint", e.getFingerprint(), "version", e.getVersion());
    }

    @PostMapping("/rules/{id}")
    public Object updateRule(@PathVariable String id, @RequestBody RuleUpdateRequest req) {
        RuleSetEntity e = service.updateRuleCandidate(id, req.baseVersion(), req.payload());
        return Map.of("id", e.getId(), "lockVersion", e.getLockVersion(),
                "fingerprint", e.getFingerprint(), "version", e.getVersion());
    }

    @PostMapping("/rules/{id}/merge")
    public Object mergeRule(@PathVariable String id, @RequestBody RuleMergeRequest req) {
        return service.mergeRuleCandidate(id, req.base(), req.incoming());
    }

    @GetMapping("/rules")
    public Object listRules() { return service.listRules(); }

    // ---- scenarios ----

    @PostMapping("/scenarios")
    public Object saveScenario(@RequestBody ScenarioSaveRequest req,
                               @RequestParam(required = false) String topologyId) {
        String tfp = topologyId == null ? ""
                : service.requireTopology(topologyId).getFingerprint();
        ScenarioEntity e = service.saveScenario(req.lineage(), req.name(), req.payload(), tfp);
        return service.scenarioDetail(e.getId());
    }

    @GetMapping("/scenarios")
    public Object listScenarios() { return service.listScenarios(); }

    @GetMapping("/scenarios/{id}")
    public Map<String, Object> scenario(@PathVariable String id) { return service.scenarioDetail(id); }

    @PostMapping("/scenarios/{id}/faults")
    public Object insertFault(@PathVariable String id, @RequestBody FaultRequest req) {
        ScenarioEntity e = service.insertFault(id, req.kind(), req.deviceId(), req.clear());
        return service.scenarioDetail(e.getId());
    }

    // ---- approval ----

    @GetMapping("/approval")
    public Object approval() { return service.currentApproval(); }

    @PostMapping("/approval")
    public Object approve(@RequestBody ApproveRequest req) {
        service.approve(req.topologyId(), req.ruleSetId(), req.scenarioId(), req.by());
        return service.currentApproval();
    }

    // ---- verification ----

    @PostMapping("/verify")
    public VerifyReport verify(@RequestBody Map<String, String> body,
                               @RequestParam(defaultValue = "5000") int bound) {
        return service.verify(body.get("topologyId"), body.get("ruleSetId"),
                body.get("scenarioId"), bound);
    }

    @PostMapping("/verify/approved")
    public VerifyReport verifyApproved(@RequestParam(defaultValue = "5000") int bound) {
        return service.verifyApproved(bound);
    }

    @GetMapping("/reports")
    public Object reports() { return service.listReports(); }

    @GetMapping("/reports/{id}")
    public Object report(@PathVariable String id) { return service.reportDetail(id); }

    @PostMapping("/compare")
    public Object compare(@RequestBody Map<String, String> body,
                          @RequestParam(defaultValue = "5000") int bound) {
        return service.compareWithApproved(body.get("candidateRuleId"),
                body.getOrDefault("scenarioId",
                        service.currentApproval().getOrDefault("scenarioId", "").toString()), bound);
    }

    // ---- export / import ----

    @PostMapping("/export")
    public Object exportPackage(@RequestBody Map<String, String> body,
                                @RequestParam(defaultValue = "5000") int bound) {
        return service.exportPackage(body.get("topologyId"), body.get("ruleSetId"),
                body.get("scenarioId"), bound);
    }

    @PostMapping("/import-replay")
    public Object importReplay(@RequestBody Map<String, Object> pkg) {
        return service.importAndReplayPackage(pkg);
    }
}

@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/api")
class StepController {

    private final com.railway.sandbox.service.StepService steps;

    StepController(com.railway.sandbox.service.StepService steps) {
        this.steps = steps;
    }

    @org.springframework.web.bind.annotation.PostMapping("/step/initial")
    Object initial(@RequestBody Map<String, String> body) {
        return steps.initial(body.get("topologyId"), body.get("ruleSetId"), body.get("scenarioId"));
    }

    @org.springframework.web.bind.annotation.PostMapping("/step/one")
    Object one(@RequestBody com.railway.sandbox.service.StepService.StepRequest req) {
        return steps.step(req);
    }
}
