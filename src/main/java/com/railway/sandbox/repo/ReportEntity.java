package com.railway.sandbox.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.Instant;

/**
 * Derived artefact. Every verification report records the rule version and
 * source fingerprints it was derived from, so a report can always be traced
 * back to the exact frozen inputs and re-derived byte-identically elsewhere.
 */
@Entity
public class ReportEntity {
    @Id
    private String id;
    private String conclusion;
    private String ruleVersion;
    private String ruleFingerprint;
    private String topologyFingerprint;
    private String scenarioId;
    private String scenarioFingerprint;
    private int traceLength;
    private int bound;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String reportJson;
    private Instant createdAt;

    protected ReportEntity() {}

    public ReportEntity(String id, String conclusion, String ruleVersion, String ruleFingerprint,
                        String topologyFingerprint, String scenarioId, String scenarioFingerprint,
                        int traceLength, int bound, String reportJson, Instant createdAt) {
        this.id = id;
        this.conclusion = conclusion;
        this.ruleVersion = ruleVersion;
        this.ruleFingerprint = ruleFingerprint;
        this.topologyFingerprint = topologyFingerprint;
        this.scenarioId = scenarioId;
        this.scenarioFingerprint = scenarioFingerprint;
        this.traceLength = traceLength;
        this.bound = bound;
        this.reportJson = reportJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getConclusion() { return conclusion; }
    public String getRuleVersion() { return ruleVersion; }
    public String getRuleFingerprint() { return ruleFingerprint; }
    public String getTopologyFingerprint() { return topologyFingerprint; }
    public String getScenarioId() { return scenarioId; }
    public String getScenarioFingerprint() { return scenarioFingerprint; }
    public int getTraceLength() { return traceLength; }
    public int getBound() { return bound; }
    public String getReportJson() { return reportJson; }
    public Instant getCreatedAt() { return createdAt; }
}
