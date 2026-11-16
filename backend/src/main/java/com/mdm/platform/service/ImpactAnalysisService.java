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
 * 影响分析器：规则 / 图号 / 实装记录变更后，
 * 找出受影响的快照、历史兼容结论与下游数据集。
 *
 * 关键纪律（规则撤回场景）：
 *  - 仅让引用该规则的快照/事实进入"待复核"
 *  - 已签发（VERIFIED）结论保留当时锁定版本，不静默改写，另发更正链
 */
@Service
public class ImpactAnalysisService {

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public ImpactAnalysisService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    @Transactional
    public Map<String, Object> analyzeRuleChange(long ruleId, String changeKind, String note) {
        Map<String, Object> rule = db.queryOne("SELECT * FROM substitution_rule WHERE id=?", ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        // 找到所有引用该规则的兼容事实及其快照
        List<Map<String, Object>> facts = db.query(
                "SELECT * FROM compatibility_fact WHERE rule_id=? ORDER BY id", ruleId);
        List<Long> factIds = facts.stream().map(f -> ((Number) f.get("id")).longValue()).distinct().toList();
        List<Long> snapshotIds = facts.stream().map(f -> ((Number) f.get("snapshot_id")).longValue())
                .distinct().toList();

        List<Map<String, Object>> corrections = new ArrayList<>();
        boolean retirement = "RETIRE".equals(changeKind);

        for (Map<String, Object> fact : facts) {
            long factId = ((Number) fact.get("id")).longValue();
            String status = String.valueOf(fact.get("status"));
            if (retirement && "VERIFIED".equals(status)) {
                // 已签发：保留原结论与锁定版本，另发更正事实
                corrections.add(issueCorrection(fact, rule, note));
                qualityEvents.record("FACT_CORRECTION", "HIGH", "RULE", ruleId,
                        ((Number) fact.get("snapshot_id")).longValue(), factId,
                        "规则 " + rule.get("rule_code") + " 撤回，已签发事实 " + fact.get("fact_no")
                                + " 保留锁定版本 " + fact.get("rule_version_locked") + "，另发更正",
                        Map.of("ruleCode", rule.get("rule_code"), "factNo", fact.get("fact_no")));
            } else {
                // 草稿/待复核等：转入待复核
                if (!"RETIRED".equals(status)) {
                    db.update("UPDATE compatibility_fact SET status='REVIEW' WHERE id=?", factId);
                    qualityEvents.record("SNAPSHOT_REVIEW", "MEDIUM", "RULE", ruleId,
                            ((Number) fact.get("snapshot_id")).longValue(), factId,
                            "事实 " + fact.get("fact_no") + " 因规则变更进入待复核",
                            Map.of("changeKind", changeKind));
                }
            }
        }
        // 引用快照进入待复核（不删除、不改写）
        for (Long snapshotId : snapshotIds) {
            Map<String, Object> snap = db.queryOne("SELECT status FROM config_snapshot WHERE id=?", snapshotId);
            if (snap != null && !"FROZEN".equals(snap.get("status"))) {
                db.update("UPDATE config_snapshot SET status='REVIEW' WHERE id=? AND status<>'FROZEN'", snapshotId);
            }
        }

        List<Map<String, Object>> downstream = List.of(
                Map.of("dataset", "售后配置查询报表", "ref", "BI/after-sales-config", "impacted", !snapshotIds.isEmpty()),
                Map.of("dataset", "质量追溯看板", "ref", "QMS/traceability", "impacted", !factIds.isEmpty()),
                Map.of("dataset", "采购替代目录（只读引用）", "ref", "SRM/substitution-catalog",
                        "impacted", retirement));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleCode", rule.get("rule_code"));
        detail.put("ruleVersion", rule.get("rule_version"));
        detail.put("changeKind", changeKind);
        detail.put("affectedFacts", factIds);
        detail.put("corrections", corrections);
        detail.put("downstreamDatasets", downstream);

        return persistAnalysis("RULE_CHANGE", rule.get("rule_code") + ":" + rule.get("rule_version"),
                snapshotIds, factIds, downstream,
                "规则变更影响 " + snapshotIds.size() + " 个快照、" + factIds.size() + " 条兼容事实"
                        + (retirement ? "；已签发结论保留版本并另发更正 " + corrections.size() + " 条" : ""),
                detail);
    }

    private Map<String, Object> issueCorrection(Map<String, Object> originalFact, Map<String, Object> rule,
                                                String note) {
        long count = db.count("SELECT count(*) FROM compatibility_fact");
        String newFactNo = String.format("CMP-FACT-%05d", count + 1);
        List<Object> chain = db.toList(originalFact.get("evidence_chain"));
        List<Map<String, Object>> newChain = new ArrayList<>();
        Map<String, Object> correctionHeader = Map.of(
                "step", "CORRECTION_NOTICE", "result", "RULE_RETIRED",
                "detail", Map.of(
                        "originalFactNo", originalFact.get("fact_no"),
                        "lockedRuleVersion", originalFact.get("rule_version_locked"),
                        "retireReason", rule.get("retire_reason"),
                        "note", note == null ? "供应商收回规则，原结论按当时版本保留" : note));
        newChain.add(correctionHeader);
        newChain.add(Map.of("step", "ORIGINAL_CHAIN_REF", "result", "PRESERVED", "detail", chain));

        long newId = db.insertReturningId(
                "INSERT INTO compatibility_fact(fact_no,snapshot_id,mount_position_id,candidate_model_id," +
                "installed_model_id,verdict,rule_id,rule_version_locked,evidence_chain,failed_constraints," +
                "status,supersedes_fact_id,correction_note,issued_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?) RETURNING id",
                newFactNo, originalFact.get("snapshot_id"), originalFact.get("mount_position_id"),
                originalFact.get("candidate_model_id"), originalFact.get("installed_model_id"),
                originalFact.get("verdict"), originalFact.get("rule_id"),
                originalFact.get("rule_version_locked"), db.toJson(newChain),
                originalFact.get("failed_constraints"), "REVIEW",
                ((Number) originalFact.get("id")).longValue(),
                "规则撤回更正：原事实 " + originalFact.get("fact_no") + " 保留，本条目提示重新核验",
                OffsetDateTime.now());
        return Map.of("newFactId", newId, "newFactNo", newFactNo,
                "supersedes", originalFact.get("fact_no"));
    }

    @Transactional
    public Map<String, Object> analyzeDrawingChange(long drawingRevisionId) {
        Map<String, Object> rev = db.queryOne(
                "SELECT dr.*, d.drawing_no FROM drawing_revision dr JOIN drawing d ON d.id=dr.drawing_id " +
                "WHERE dr.id=?", drawingRevisionId);
        if (rev == null) {
            throw new IllegalArgumentException("图号修订不存在: " + drawingRevisionId);
        }
        // 规则血缘引用该图号修订
        List<Map<String, Object>> rules = db.query(
                "SELECT id FROM substitution_rule WHERE drawing_revision_id=?", drawingRevisionId);
        List<Long> ruleIds = rules.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        List<Long> factIds = new ArrayList<>();
        List<Long> snapshotIds = new ArrayList<>();
        for (Long ruleId : ruleIds) {
            List<Map<String, Object>> facts = db.query(
                    "SELECT id, snapshot_id FROM compatibility_fact WHERE rule_id=?", ruleId);
            facts.forEach(f -> {
                factIds.add(((Number) f.get("id")).longValue());
                snapshotIds.add(((Number) f.get("snapshot_id")).longValue());
            });
        }
        List<Long> distinctFacts = factIds.stream().distinct().toList();
        List<Long> distinctSnapshots = snapshotIds.stream().distinct().toList();
        Map<String, Object> detail = Map.of("drawingNo", rev.get("drawing_no"),
                "revision", rev.get("revision"), "viaRules", ruleIds);
        return persistAnalysis("DRAWING_CHANGE",
                rev.get("drawing_no") + ":" + rev.get("revision"),
                distinctSnapshots, distinctFacts, List.of(),
                "图号修订变更经规则血缘影响 " + distinctSnapshots.size() + " 个快照", detail);
    }

    @Transactional
    public Map<String, Object> analyzeInstallEventChange(long eventId) {
        Map<String, Object> event = db.queryOne("SELECT * FROM install_event WHERE id=?", eventId);
        if (event == null) {
            throw new IllegalArgumentException("实装事件不存在: " + eventId);
        }
        long equipmentId = ((Number) event.get("equipment_id")).longValue();
        // 所有包含该事件的快照
        List<Map<String, Object>> snapshots = db.query(
                "SELECT * FROM config_snapshot WHERE equipment_id=? AND ? = ANY(source_event_ids)",
                equipmentId, eventId);
        List<Long> snapshotIds = snapshots.stream().map(s -> ((Number) s.get("id")).longValue()).toList();
        List<Map<String, Object>> factRows = snapshotIds.isEmpty() ? List.of()
                : db.query("SELECT id FROM compatibility_fact WHERE snapshot_id IN (" +
                buildPlaceholders(snapshotIds.size()) + ")", snapshotIds.toArray());
        List<Long> factIds = factRows.stream().map(f -> ((Number) f.get("id")).longValue()).toList();

        for (Long snapshotId : snapshotIds) {
            db.update("UPDATE config_snapshot SET status='REVIEW' WHERE id=? AND status NOT IN ('FROZEN')",
                    snapshotId);
            qualityEvents.record("LATE_VOUCHER", "MEDIUM", "INSTALL_EVENT", eventId, snapshotId, null,
                    "实装凭证 " + event.get("event_code") + " 变更/迟到，快照进入待复核，可按新时点重放生成新版本",
                    Map.of("eventCode", event.get("event_code"),
                            "receivedAt", event.get("received_at"), "eventTime", event.get("event_time")));
        }
        Map<String, Object> detail = Map.of("eventCode", event.get("event_code"),
                "eventType", event.get("event_type"), "equipmentId", equipmentId);
        return persistAnalysis("INSTALL_EVENT_CHANGE", String.valueOf(event.get("event_code")),
                snapshotIds, factIds, List.of(),
                "实装事件变更影响 " + snapshotIds.size() + " 个历史快照（仅追加新版本，不改写旧快照）", detail);
    }

    private Map<String, Object> persistAnalysis(String triggerType, String triggerRef,
                                                List<Long> snapshotIds, List<Long> factIds,
                                                List<Map<String, Object>> downstream, String summary,
                                                Map<String, Object> detail) {
        long count = db.count("SELECT count(*) FROM impact_analysis");
        String analysisNo = String.format("IMP-%s-%04d", triggerType.toLowerCase().replace('_', '-'), count + 1);
        long id = db.insertReturningId(
                "INSERT INTO impact_analysis(analysis_no,trigger_type,trigger_ref,affected_snapshot_ids," +
                "affected_fact_ids,affected_dataset_refs,summary,detail,created_by) " +
                "VALUES (?,?,?,?::bigint[],?::bigint[],?::jsonb,?,?::jsonb,?) RETURNING id",
                analysisNo, triggerType, triggerRef, db.pgLongArrayLiteral(snapshotIds),
                db.pgLongArrayLiteral(factIds), db.toJson(downstream), summary, db.toJson(detail),
                CurrentUser.username());
        return db.queryOne("SELECT * FROM impact_analysis WHERE id=?", id);
    }

    public List<Map<String, Object>> list() {
        return db.query("SELECT * FROM impact_analysis ORDER BY id DESC");
    }

    private String buildPlaceholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
