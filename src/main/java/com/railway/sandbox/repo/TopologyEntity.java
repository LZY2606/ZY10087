package com.railway.sandbox.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Immutable raw evidence: once a topology revision is received its payload
 * never changes. A new import creates a new revision id. derived entities
 * point at the exact revision fingerprint they were built from.
 */
@Entity
public class TopologyEntity {

    @Id
    private String id;
    /** Monotonic per-topology sequence, used for ordering revisions. */
    private long revision;
    private String name;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String payloadJson;
    private String fingerprint;
    private Instant receivedAt;

    /** JPA optimistic-lock column; concurrent stale edits surface 409. */
    @Version
    private long lockVersion;

    protected TopologyEntity() {}

    public TopologyEntity(String id, long revision, String name,
                          String payloadJson, String fingerprint, Instant receivedAt) {
        this.id = id;
        this.revision = revision;
        this.name = name;
        this.payloadJson = payloadJson;
        this.fingerprint = fingerprint;
        this.receivedAt = receivedAt;
    }

    public String getId() { return id; }
    public long getRevision() { return revision; }
    public String getName() { return name; }
    public String getPayloadJson() { return payloadJson; }
    public String getFingerprint() { return fingerprint; }
    public Instant getReceivedAt() { return receivedAt; }
    public long getLockVersion() { return lockVersion; }
}
