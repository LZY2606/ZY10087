package dev.railsandbox.persistence;

import java.time.Instant;

public record ApprovalSnapshot(
        String approvalId,
        Instant approvedAt,
        String topologyRevisionId,
        String ruleRevisionId,
        String scenarioRevisionId,
        String topologyFingerprint,
        String ruleFingerprint,
        String scenarioFingerprint,
        String packageFingerprint
) {
}
