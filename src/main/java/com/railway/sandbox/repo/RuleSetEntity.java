package com.railway.sandbox.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A rule candidate. Candidates are append/update drafts; the currently
 * approved rule set is recorded by ApprovalEntity. Updating a draft bumps
 * lockVersion so two browsers editing the same old version cannot clobber
 * each other silently.
 */
@Entity
public class RuleSetEntity {
    @Id
    private String id;
    private String version;
    private String note;
    private boolean candidate;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String payloadJson;
    private String fingerprint;
    private Instant updatedAt;
    @Version
    private long lockVersion;

    protected RuleSetEntity() {}

    public RuleSetEntity(String id, String version, String note, boolean candidate,
                         String payloadJson, String fingerprint, Instant updatedAt) {
        this.id = id;
        this.version = version;
        this.note = note;
        this.candidate = candidate;
        this.payloadJson = payloadJson;
        this.fingerprint = fingerprint;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getNote() { return note; }
    public boolean isCandidate() { return candidate; }
    public String getPayloadJson() { return payloadJson; }
    public String getFingerprint() { return fingerprint; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getLockVersion() { return lockVersion; }

    public void touch(String version, String note, boolean candidate,
                      String payloadJson, String fingerprint, Instant now) {
        this.version = version;
        this.note = note;
        this.candidate = candidate;
        this.payloadJson = payloadJson;
        this.fingerprint = fingerprint;
        this.updatedAt = now;
    }
}
