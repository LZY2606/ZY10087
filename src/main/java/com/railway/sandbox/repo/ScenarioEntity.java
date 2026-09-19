package com.railway.sandbox.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Scenario versions. Inserting a device fault or editing the operation
 * program creates a new version (parentVersion links it); existing versions
 * are never rewritten.
 */
@Entity
public class ScenarioEntity {
    @Id
    private String id;
    private String lineage;
    private long versionNo;
    private String parentVersion;
    private String name;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String payloadJson;
    private String fingerprint;
    private String topologyFingerprint;
    private Instant createdAt;
    @Version
    private long lockVersion;

    protected ScenarioEntity() {}

    public ScenarioEntity(String id, String lineage, long versionNo, String parentVersion,
                          String name, String payloadJson, String fingerprint,
                          String topologyFingerprint, Instant createdAt) {
        this.id = id;
        this.lineage = lineage;
        this.versionNo = versionNo;
        this.parentVersion = parentVersion;
        this.name = name;
        this.payloadJson = payloadJson;
        this.fingerprint = fingerprint;
        this.topologyFingerprint = topologyFingerprint;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getLineage() { return lineage; }
    public long getVersionNo() { return versionNo; }
    public String getParentVersion() { return parentVersion; }
    public String getName() { return name; }
    public String getPayloadJson() { return payloadJson; }
    public String getFingerprint() { return fingerprint; }
    public String getTopologyFingerprint() { return topologyFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public long getLockVersion() { return lockVersion; }
}
