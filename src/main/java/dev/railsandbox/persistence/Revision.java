package dev.railsandbox.persistence;

import java.time.Instant;

public record Revision(
        String revisionId,
        Long evidenceId,
        String baseRevisionId,
        int versionNumber,
        Instant createdAt,
        String entityId,
        String payload,
        String sourceFingerprint,
        String topologyFingerprint,
        String ruleFingerprint,
        String faultOfRevisionId
) {
}
