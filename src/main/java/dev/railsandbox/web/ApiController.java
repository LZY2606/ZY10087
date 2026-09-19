package dev.railsandbox.web;

import dev.railsandbox.domain.Models.*;
import dev.railsandbox.persistence.*;
import dev.railsandbox.service.*;
import dev.railsandbox.verify.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SandboxService service;
    private final PackageService packages;

    public ApiController(SandboxService service, PackageService packages) {
        this.service = service;
        this.packages = packages;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return Map.of(
                "topologies", service.list("topology"),
                "rules", service.list("rule"),
                "scenarios", service.list("scenario"),
                "approval", Objects.requireNonNullElse(service.approval(), Map.of("approvalId", "NONE"))
        );
    }

    @GetMapping("/revisions/{kind}/{revisionId}")
    public Revision revision(@PathVariable String kind, @PathVariable String revisionId) {
        Revision revision = service.get(kind, revisionId);
        if (revision == null) {
            throw new NoSuchElementException("版本不存在");
        }
        return revision;
    }

    @PostMapping("/topology/validate")
    public Map<String, Object> validateTopology(@RequestBody Topology topology) {
        List<Diagnostic> issues = new TopologyValidator(topology).validate();
        return Map.of("valid", issues.stream().noneMatch(Diagnostic::error), "issues", issues);
    }

    @PostMapping("/revisions/topology")
    public Revision submitTopology(@RequestBody SubmitRevisionRequest request) {
        return service.submitTopology(request.payload(), request.source(), request.baseRevisionId());
    }

    @PostMapping("/revisions/rule")
    public Revision submitRule(@RequestBody SubmitRevisionRequest request) {
        return service.submitRule(request.payload(), request.source(), request.baseRevisionId());
    }

    @PostMapping("/revisions/scenario")
    public Revision submitScenario(@RequestBody SubmitScenarioRequest request) {
        return service.submitScenario(request.payload(), request.source(), request.topologyRevisionId(),
                request.topologyFingerprint(), request.baseRevisionId(), null);
    }

    @PostMapping("/approvals")
    public ApprovalSnapshot approve(@RequestBody ApproveRequest request) {
        return service.approve(request.topologyRevisionId(), request.ruleRevisionId(), request.scenarioRevisionId());
    }

    @PostMapping("/verifications/compare")
    public Map<String, Object> compare(@RequestBody CompareRequest request) {
        int maxDepth = request.maxDepth() == 0 ? 64 : request.maxDepth();
        int maxStates = request.maxStates() == 0 ? 50_000 : request.maxStates();
        return service.compareWithCandidate(null, request.candidateRuleRevisionId(),
                request.topologyRevisionId(), request.ruleRevisionId(), request.scenarioRevisionId(),
                maxDepth, maxStates);
    }

    @PostMapping("/scenarios/{revisionId}/fault")
    public Revision insertFault(@PathVariable String revisionId, @RequestBody FaultRequest request) {
        return service.insertFault(revisionId, request.deviceId(),
                request.afterActionIndex() == null ? 0 : request.afterActionIndex(),
                request.source() == null ? "manual-ui" : request.source());
    }

    @GetMapping("/exports/validation-package")
    public ResponseEntity<byte[]> exportPackage(@RequestParam String topologyId,
                                                @RequestParam String candidateRuleId) throws Exception {
        byte[] archive = packages.exportPackage(topologyId, candidateRuleId, 64, 50_000);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=rail-validation-package.zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(archive);
    }

    @PostMapping("/imports/validation-package")
    public Map<String, Object> importPackage(@RequestParam("file") MultipartFile file) throws Exception {
        return packages.importPackage(file.getBytes());
    }

    @ExceptionHandler(RevisionConflictException.class)
    public ResponseEntity<Object> conflict(RevisionConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.body());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException exception) {
        String message = exception.getMessage();
        Object parsed = message != null && message.trim().startsWith("{") ? Json.read(message, Object.class) : Map.of("message", message);
        return ResponseEntity.badRequest().body(parsed);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    public record SubmitRevisionRequest(String payload, String baseRevisionId, String source) {
    }

    public record SubmitScenarioRequest(String payload, String baseRevisionId, String topologyRevisionId,
                                        String topologyFingerprint, String source) {
    }

    public record ApproveRequest(String topologyRevisionId, String ruleRevisionId, String scenarioRevisionId) {
    }

    public record CompareRequest(String topologyRevisionId, String ruleRevisionId, String scenarioRevisionId,
                                 String candidateRuleRevisionId, int maxDepth, int maxStates) {
    }

    public record FaultRequest(String deviceId, Integer afterActionIndex, String source) {
    }
}
