package com.railway.sandbox.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The single frozen approval. Approval snapshots and freezes:
 *  - exact topology revision id + fingerprint
 *  - exact rule set id + version fingerprint
 *  - exact scenario version id + scenario fingerprint
 * Later edits create new revisions/versions and never rewrite this row's
 * frozen references (they only move the pointers to the new approved ones).
 */
@Entity
public class ApprovalEntity {
    public static final String SINGLETON_ID = "CURRENT";

    @Id
    private String id = SINGLETON_ID;
    private String topologyId;
    private String topologyFingerprint;
    private String ruleSetId;
    private String ruleVersion;
    private String ruleFingerprint;
    private String scenarioId;
    private String scenarioFingerprint;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String frozenSnapshotJson;
    private Instant approvedAt;
    private String approvedBy;
    @Version
    private long lockVersion;

    protected ApprovalEntity() {}

    /** Factory for the very first approval (JPA needs the protected ctor itself). */
    public static ApprovalEntity blank() { return new ApprovalEntity(); }

    public ApprovalEntity(String topologyId, String topologyFingerprint,
                          String ruleSetId, String ruleVersion, String ruleFingerprint,
                          String scenarioId, String scenarioFingerprint,
                          String frozenSnapshotJson, Instant approvedAt, String approvedBy) {
        this.topologyId = topologyId;
        this.topologyFingerprint = topologyFingerprint;
        this.ruleSetId = ruleSetId;
        this.ruleVersion = ruleVersion;
        this.ruleFingerprint = ruleFingerprint;
        this.scenarioId = scenarioId;
        this.scenarioFingerprint = scenarioFingerprint;
        this.frozenSnapshotJson = frozenSnapshotJson;
        this.approvedAt = approvedAt;
        this.approvedBy = approvedBy;
    }

    public String getId() { return id; }
    public String getTopologyId() { return topologyId; }
    public String getTopologyFingerprint() { return topologyFingerprint; }
    public String getRuleSetId() { return ruleSetId; }
    public String getRuleVersion() { return ruleVersion; }
    public String getRuleFingerprint() { return ruleFingerprint; }
    public String getScenarioId() { return scenarioId; }
    public String getScenarioFingerprint() { return scenarioFingerprint; }
    public String getFrozenSnapshotJson() { return frozenSnapshotJson; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getApprovedBy() { return approvedBy; }
    public long getLockVersion() { return lockVersion; }

    /** Re-approve in place: JPA @Version keeps concurrent approvals honest. */
    public void freeze(String topologyId, String topologyFingerprint,
                       String ruleSetId, String ruleVersion, String ruleFingerprint,
                       String scenarioId, String scenarioFingerprint,
                       String frozenSnapshotJson, Instant approvedAt, String approvedBy) {
        this.topologyId = topologyId;
        this.topologyFingerprint = topologyFingerprint;
        this.ruleSetId = ruleSetId;
        this.ruleVersion = ruleVersion;
        this.ruleFingerprint = ruleFingerprint;
        this.scenarioId = scenarioId;
        this.scenarioFingerprint = scenarioFingerprint;
        this.frozenSnapshotJson = frozenSnapshotJson;
        this.approvedAt = approvedAt;
        this.approvedBy = approvedBy;
    }
}
