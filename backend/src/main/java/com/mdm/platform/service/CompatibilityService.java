package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容事实求值器：
 * 对给定快照的某个装配位置 + 候选备件型号，输出 PASS / FAIL / INSUFFICIENT_EVIDENCE，
 * 并给出最小证据链（规则版本、图号修订、测试报告、约束逐项结果、快照位置事实）。
 * 本服务不生成领料单、维修工序或库存调拨。
 */
@Service
public class CompatibilityService {

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public CompatibilityService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    public record EvaluationRequest(long snapshotId, long mountPositionId, long candidateModelId) {}

    @Transactional
    public Map<String, Object> evaluate(EvaluationRequest req) {
        Map<String, Object> snapshot = db.queryOne("SELECT * FROM config_snapshot WHERE id=?", req.snapshotId());
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + req.snapshotId());
        }
        Map<String, Object> position = db.queryOne(
                "SELECT * FROM snapshot_position WHERE snapshot_id=? AND mount_position_id=?",
                req.snapshotId(), req.mountPositionId());
        if (position == null) {
            throw new IllegalArgumentException("快照中不存在该装配位置");
        }
        Map<String, Object> candidate = db.queryOne(
                "SELECT * FROM master_entity WHERE id=? AND entity_type='PART_MODEL'", req.candidateModelId());
        if (candidate == null) {
            throw new IllegalArgumentException("候选备件型号不存在");
        }

        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> failedConstraints = new ArrayList<>();
        Long installedModelId = position.get("part_model_id") == null ? null
                : ((Number) position.get("part_model_id")).longValue();

        // 仅当"被评估位置本身"冻结/冲突时才证据不足；其他位置的冲突不连坐
        if ("FROZEN".equals(position.get("position_state"))
                || "CONFLICT".equals(position.get("basis"))) {
            return persistFact(req, snapshot, position, candidate, installedModelId,
                    "INSUFFICIENT_EVIDENCE", null, evidence, failedConstraints,
                    "该装配位置处于冲突/冻结状态，需先完成凭证裁定");
        }
        if (installedModelId == null) {
            return persistFact(req, snapshot, position, candidate, installedModelId,
                    "INSUFFICIENT_EVIDENCE", null, evidence, failedConstraints,
                    "该位置在维修时点为空位，无在装型号可比对");
        }

        // 寻找在维修时点生效、方向为  installed -> candidate 的替代规则（取最新生效版本）
        // 草稿/待复核规则不参与求值；已撤回规则对历史时点仍可作为锁定版本依据
        OffsetDateTime pit = db.toOffsetDateTime(snapshot.get("point_in_time"));
        Map<String, Object> rule = db.queryOne(
                "SELECT * FROM substitution_rule WHERE from_part_model_id=? AND to_part_model_id=? " +
                "AND status IN ('VERIFIED','RETIRED') " +
                "AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) " +
                "ORDER BY effective_from DESC, id DESC LIMIT 1",
                installedModelId, req.candidateModelId(), pit, pit);

        if (rule == null) {
            evidence.add(Map.of("step", "RULE_LOOKUP", "result", "NOT_FOUND",
                    "detail", "维修时点不存在从在装型号到候选型号的生效替代规则（替代方向不成立）"));
            return persistFact(req, snapshot, position, candidate, installedModelId,
                    "INSUFFICIENT_EVIDENCE", null, evidence, failedConstraints,
                    "无生效替代规则，证据不足");
        }

        // 规则血缘：连回图号修订与测试报告
        Map<String, Object> lineage = buildRuleLineage(rule);
        evidence.add(Map.of("step", "RULE_LINEAGE", "result", "RESOLVED", "detail", lineage));

        // 已撤回规则在历史时点仍有效时：历史结论可保留，但要显式标注当前状态
        boolean ruleRetiredNow = "RETIRED".equals(rule.get("status"));

        // 约束逐项求值（协议/尺寸/电源… 必须全部通过 —— 两条约束联动）
        List<Map<String, Object>> constraints = db.query(
                "SELECT * FROM rule_constraint WHERE rule_id=? ORDER BY id", rule.get("id"));
        Map<String, Object> candidateSpec = db.toMap(candidate.get("spec_payload"));
        boolean allPassed = true;
        for (Map<String, Object> c : constraints) {
            String actual = String.valueOf(candidateSpec.getOrDefault(String.valueOf(c.get("attribute_name")), null));
            boolean passed = compare(actual, String.valueOf(c.get("expected_value")),
                    String.valueOf(c.get("compare_op")));
            Map<String, Object> constraintResult = new LinkedHashMap<>();
            constraintResult.put("constraintType", c.get("constraint_type"));
            constraintResult.put("attribute", c.get("attribute_name"));
            constraintResult.put("operator", c.get("compare_op"));
            constraintResult.put("expected", c.get("expected_value"));
            constraintResult.put("actual", actual);
            constraintResult.put("passed", passed);
            evidence.add(Map.of("step", "CONSTRAINT_CHECK", "result", passed ? "PASS" : "FAIL",
                    "detail", constraintResult));
            if (!passed) {
                allPassed = false;
                failedConstraints.add(constraintResult);
            }
        }

        // 联换条件：仅做提示性证据，不产生领料/调拨动作
        List<Object> coSubs = db.toList(rule.get("co_substitution"));
        if (!coSubs.isEmpty()) {
            evidence.add(Map.of("step", "CO_SUBSTITUTION_NOTICE", "result", "NOTICE",
                    "detail", Map.of("coSubstitution", coSubs,
                            "note", "存在联换条件，仅作为证据提示，平台不生成领料单或调拨指令")));
        }

        // 快照位置事实进入证据链（最小证据链闭环）
        evidence.add(Map.of("step", "SNAPSHOT_POSITION", "result", "RESOLVED",
                "detail", Map.of(
                        "snapshotNo", snapshot.get("snapshot_no"),
                        "basis", position.get("basis"),
                        "basisEventId", position.get("basis_event_id"),
                        "trustScore", position.get("trust_score"),
                        "replayDigest", snapshot.get("replay_digest"))));

        String verdict;
        String reason;
        if (!allPassed) {
            verdict = "FAIL";
            reason = "存在未通过的硬约束（共 " + failedConstraints.size() + " 项），联动判定不通过";
        } else if (ruleRetiredNow) {
            // 历史时点规则有效 -> 结论按当时版本成立，标注"规则现已撤回"
            verdict = "PASS";
            reason = "维修时点规则有效，按锁定版本通过；该规则事后已被供应商撤回（"
                    + rule.get("retire_reason") + "），历史结论保留，另发更正链";
        } else {
            verdict = "PASS";
            reason = "替代规则生效且全部约束（含联动约束）通过，证据链完整";
        }

        return persistFact(req, snapshot, position, candidate, installedModelId,
                verdict, rule, evidence, failedConstraints, reason);
    }

    private Map<String, Object> buildRuleLineage(Map<String, Object> rule) {
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("ruleCode", rule.get("rule_code"));
        lineage.put("ruleVersion", rule.get("rule_version"));
        lineage.put("direction", rule.get("direction_note"));
        Map<String, Object> effectiveWindow = new LinkedHashMap<>();
        effectiveWindow.put("from", rule.get("effective_from"));
        effectiveWindow.put("to", rule.get("effective_to"));
        lineage.put("effectiveWindow", effectiveWindow);
        lineage.put("ruleStatus", rule.get("status"));
        if (rule.get("drawing_revision_id") != null) {
            Map<String, Object> rev = db.queryOne(
                    "SELECT dr.*, d.drawing_no FROM drawing_revision dr JOIN drawing d ON d.id=dr.drawing_id " +
                    "WHERE dr.id=?", rule.get("drawing_revision_id"));
            if (rev != null) {
                Map<String, Object> serialRange = new LinkedHashMap<>();
                serialRange.put("from", rev.get("serial_range_from"));
                serialRange.put("to", rev.get("serial_range_to"));
                lineage.put("drawing", Map.of(
                        "drawingNo", rev.get("drawing_no"),
                        "revision", rev.get("revision"),
                        "serialRange", serialRange,
                        "sourceRef", rev.get("source_ref"),
                        "payloadDigest", rev.get("payload_digest")));
            }
        }
        if (rule.get("test_report_id") != null) {
            Map<String, Object> report = db.queryOne(
                    "SELECT * FROM inspection_record WHERE id=?", rule.get("test_report_id"));
            if (report != null) {
                lineage.put("testReport", Map.of(
                        "inspectionCode", report.get("inspection_code"),
                        "testItem", report.get("test_item"),
                        "result", report.get("result"),
                        "sourceRef", report.get("source_ref"),
                        "payloadDigest", report.get("payload_digest")));
            }
        }
        if (rule.get("supplier_id") != null) {
            Map<String, Object> supplier = db.queryOne(
                    "SELECT entity_code, display_name FROM master_entity WHERE id=?", rule.get("supplier_id"));
            lineage.put("supplier", supplier);
        }
        return lineage;
    }

    private boolean compare(String actual, String expected, String op) {
        if (actual == null || "null".equals(actual)) {
            return false;
        }
        return switch (op) {
            case "EQ" -> actual.equalsIgnoreCase(expected);
            case "NE" -> !actual.equalsIgnoreCase(expected);
            case "IN" -> List.of(expected.split(",")).stream().map(String::trim)
                    .anyMatch(v -> v.equalsIgnoreCase(actual));
            default -> actual.equalsIgnoreCase(expected);
        };
    }

    private Map<String, Object> persistFact(EvaluationRequest req, Map<String, Object> snapshot,
                                            Map<String, Object> position, Map<String, Object> candidate,
                                            Long installedModelId, String verdict, Map<String, Object> rule,
                                            List<Map<String, Object>> evidence,
                                            List<Map<String, Object>> failedConstraints, String reason) {
        long count = db.count("SELECT count(*) FROM compatibility_fact");
        String factNo = String.format("CMP-FACT-%05d", count + 1);
        Long ruleId = rule == null ? null : ((Number) rule.get("id")).longValue();
        String ruleVersion = rule == null ? null : String.valueOf(rule.get("rule_version"));
        Map<String, Object> reasonNode = new LinkedHashMap<>();
        reasonNode.put("reason", reason);
        List<Map<String, Object>> fullEvidence = new ArrayList<>(evidence);
        fullEvidence.add(0, Map.of("step", "EVALUATION_REASON", "result", verdict, "detail", reasonNode));

        long id = db.insertReturningId(
                "INSERT INTO compatibility_fact(fact_no,snapshot_id,mount_position_id,candidate_model_id," +
                "installed_model_id,verdict,rule_id,rule_version_locked,evidence_chain,failed_constraints," +
                "status,issued_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id",
                factNo, req.snapshotId(), req.mountPositionId(), req.candidateModelId(), installedModelId,
                verdict, ruleId, ruleVersion, db.toJson(fullEvidence), db.toJson(failedConstraints),
                "DRAFT", OffsetDateTime.now());

        db.update("INSERT INTO release_audit_log(fact_id,snapshot_id,action,from_status,to_status,operator,reason) " +
                "VALUES (?,?,?,NULL,?,?,?)", id, req.snapshotId(), "EVALUATE", "DRAFT",
                CurrentUser.username(), reason);
        return getFactDetail(id);
    }

    public Map<String, Object> getFactDetail(long factId) {
        Map<String, Object> fact = db.queryOne("SELECT * FROM compatibility_fact WHERE id=?", factId);
        if (fact == null) {
            throw new IllegalArgumentException("兼容事实不存在: " + factId);
        }
        return fact;
    }

    public List<Map<String, Object>> listBySnapshot(long snapshotId) {
        return db.query("SELECT * FROM compatibility_fact WHERE snapshot_id=? ORDER BY id DESC", snapshotId);
    }

    public List<Map<String, Object>> listAll() {
        return db.query("SELECT * FROM compatibility_fact ORDER BY id DESC");
    }

    /**
     * 对快照内所有"有在装件"的位置批量求值（演示可回放性）。
     */
    public List<Map<String, Object>> evaluateSnapshot(long snapshotId, List<Long> candidateModelIds) {
        List<Map<String, Object>> positions = db.query(
                "SELECT * FROM snapshot_position WHERE snapshot_id=? AND part_model_id IS NOT NULL", snapshotId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> p : positions) {
            for (Long candidateId : candidateModelIds) {
                long posId = ((Number) p.get("mount_position_id")).longValue();
                results.add(evaluate(new EvaluationRequest(snapshotId, posId, candidateId)));
            }
        }
        return results;
    }
}
