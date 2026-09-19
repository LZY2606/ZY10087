package dev.railsandbox.persistence;

import dev.railsandbox.service.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class RevisionStore {
    private final JdbcTemplate jdbc;

    public RevisionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Revision submit(String kind, String entityId, String baseRevisionId, String payload,
                           String source, Map<String, String> linkage, String faultOfRevisionId) {
        String fingerprint = Json.sha256(normalized(payload));
        Revision head = head(kind, entityId);
        if (head != null && baseRevisionId != null && !head.revisionId().equals(baseRevisionId)) {
            throw new RevisionConflictException("版本基线冲突", Map.of(
                    "message", "你的编辑基于 " + baseRevisionId + "，当前最新版本已经是 " + head.revisionId(),
                    "entityKind", kind,
                    "entityId", entityId,
                    "baseRevisionId", baseRevisionId,
                    "currentRevisionId", head.revisionId(),
                    "currentFingerprint", head.sourceFingerprint(),
                    "incomingFingerprint", fingerprint,
                    "resolution", "请读取当前版本与冲突内容，重新执行三方合并后以 currentRevisionId 为 base 提交"
            ));
        }
        if (head != null && head.sourceFingerprint().equals(fingerprint)) {
            return head;
        }
        String effectiveBase = baseRevisionId != null ? baseRevisionId : (head == null ? null : head.revisionId());
        int version = head == null ? 1 : head.versionNumber() + 1;
        Instant now = Instant.now();
        long evidenceId = insertEvidence(kind, entityId, payload, fingerprint, source, now);
        String revisionId = kind + "-" + entityId.replaceAll("[^A-Za-z0-9_-]", "_") + "-v" + version;
        String sql = switch (kind) {
            case "topology" -> """
                INSERT INTO topology_revisions
                (revision_id, evidence_id, base_revision_id, version_number, created_at, topology_id, payload, source_fingerprint, rule_fingerprint)
                VALUES (?,?,?,?,?,?,?,?,?)
                """;
            case "rule" -> """
                INSERT INTO rule_revisions
                (revision_id, evidence_id, base_revision_id, version_number, created_at, rule_id, payload, source_fingerprint)
                VALUES (?,?,?,?,?,?,?,?)
                """;
            case "scenario" -> """
                INSERT INTO scenario_revisions
                (revision_id, evidence_id, base_revision_id, version_number, created_at, scenario_id, payload,
                 source_fingerprint, topology_fingerprint, rule_fingerprint, fault_of_revision_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """;
            default -> throw new IllegalArgumentException("未知版本类型：" + kind);
        };
        if (kind.equals("topology")) {
            jdbc.update(sql, revisionId, evidenceId, effectiveBase, version, now.toString(), entityId,
                    payload, fingerprint, linkage.get("ruleFingerprint"));
        } else if (kind.equals("rule")) {
            jdbc.update(sql, revisionId, evidenceId, effectiveBase, version, now.toString(), entityId, payload, fingerprint);
        } else {
            jdbc.update(sql, revisionId, evidenceId, effectiveBase, version, now.toString(), entityId, payload,
                    fingerprint, linkage.get("topologyFingerprint"), linkage.get("ruleFingerprint"), faultOfRevisionId);
        }
        return get(kind, revisionId);
    }

    public Revision get(String kind, String revisionId) {
        String sql = switch (kind) {
            case "topology" -> "SELECT * FROM topology_revisions WHERE revision_id = ?";
            case "rule" -> "SELECT * FROM rule_revisions WHERE revision_id = ?";
            case "scenario" -> "SELECT * FROM scenario_revisions WHERE revision_id = ?";
            default -> throw new IllegalArgumentException("未知版本类型：" + kind);
        };
        return jdbc.query(sql, this::mapRevision, revisionId).stream().findFirst().orElse(null);
    }

    public Revision head(String kind, String entityId) {
        String column = switch (kind) {
            case "topology" -> "topology_id";
            case "rule" -> "rule_id";
            case "scenario" -> "scenario_id";
            default -> throw new IllegalArgumentException("未知版本类型：" + kind);
        };
        String table = switch (kind) {
            case "topology" -> "topology_revisions";
            case "rule" -> "rule_revisions";
            case "scenario" -> "scenario_revisions";
            default -> throw new IllegalArgumentException("未知版本类型：" + kind);
        };
        List<Revision> result = jdbc.query("SELECT * FROM " + table + " WHERE " + column + " = ? ORDER BY version_number DESC LIMIT 1",
                this::mapRevision, entityId);
        return result.isEmpty() ? null : result.get(0);
    }

    public List<Revision> list(String kind) {
        String table = switch (kind) {
            case "topology" -> "topology_revisions";
            case "rule" -> "rule_revisions";
            case "scenario" -> "scenario_revisions";
            default -> throw new IllegalArgumentException("未知版本类型：" + kind);
        };
        return jdbc.query("SELECT * FROM " + table + " ORDER BY created_at DESC, version_number DESC", this::mapRevision);
    }

    public void saveApproval(ApprovalSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO approvals
                (approval_id, approved_at, topology_revision_id, rule_revision_id, scenario_revision_id,
                 topology_fingerprint, rule_fingerprint, scenario_fingerprint, package_fingerprint)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, snapshot.approvalId(), snapshot.approvedAt().toString(), snapshot.topologyRevisionId(),
                snapshot.ruleRevisionId(), snapshot.scenarioRevisionId(), snapshot.topologyFingerprint(),
                snapshot.ruleFingerprint(), snapshot.scenarioFingerprint(), snapshot.packageFingerprint());
    }

    public ApprovalSnapshot approval() {
        List<ApprovalSnapshot> result = jdbc.query("SELECT * FROM approvals ORDER BY approved_at DESC LIMIT 1",
                (rs, row) -> new ApprovalSnapshot(
                        rs.getString("approval_id"),
                        Instant.parse(rs.getString("approved_at")),
                        rs.getString("topology_revision_id"),
                        rs.getString("rule_revision_id"),
                        rs.getString("scenario_revision_id"),
                        rs.getString("topology_fingerprint"),
                        rs.getString("rule_fingerprint"),
                        rs.getString("scenario_fingerprint"),
                        rs.getString("package_fingerprint")
                ));
        return result.isEmpty() ? null : result.get(0);
    }

    public void saveRun(String runId, String approvalId, String topologyRevisionId, String ruleRevisionId,
                        String scenarioRevisionId, String candidateRuleRevisionId, int maxDepth, int maxStates,
                        String report, String fingerprint) {
        jdbc.update("""
                INSERT INTO verification_runs
                (run_id, created_at, approval_id, topology_revision_id, rule_revision_id, scenario_revision_id,
                 candidate_rule_revision_id, max_depth, max_states, report, report_fingerprint)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, runId, Instant.now().toString(), approvalId, topologyRevisionId, ruleRevisionId,
                scenarioRevisionId, candidateRuleRevisionId, maxDepth, maxStates, report, fingerprint);
    }

    private long insertEvidence(String kind, String entityId, String payload, String fingerprint,
                                String source, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO raw_evidence
                    (evidence_kind, evidence_key, received_at, received_from, payload, source_fingerprint)
                    VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, kind);
            statement.setString(2, entityId);
            statement.setString(3, now.toString());
            statement.setString(4, source);
            statement.setString(5, payload);
            statement.setString(6, fingerprint);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private String normalized(String payload) {
        return Json.write(Json.read(payload, Object.class));
    }

    private Revision mapRevision(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String table = rs.getMetaData().getTableName(1).toLowerCase();
        String entityColumn = table.contains("topology") ? "topology_id" : table.contains("scenario") ? "scenario_id" : "rule_id";
        String topologyFingerprint = contains(rs, "topology_fingerprint") ? rs.getString("topology_fingerprint") : null;
        String ruleFingerprint = contains(rs, "rule_fingerprint") ? rs.getString("rule_fingerprint") : null;
        String faultOf = contains(rs, "fault_of_revision_id") ? rs.getString("fault_of_revision_id") : null;
        return new Revision(
                rs.getString("revision_id"),
                rs.getLong("evidence_id"),
                rs.getString("base_revision_id"),
                rs.getInt("version_number"),
                Instant.parse(rs.getString("created_at")),
                rs.getString(entityColumn),
                rs.getString("payload"),
                rs.getString("source_fingerprint"),
                topologyFingerprint,
                ruleFingerprint,
                faultOf
        );
    }

    private boolean contains(java.sql.ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (java.sql.SQLException ignored) {
            return false;
        }
    }
}
