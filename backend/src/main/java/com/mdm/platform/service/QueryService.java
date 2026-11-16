package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 版本化查询接口：
 * 查询必须显式选择时点；历史快照、证据链与图号修订均可按版本回放，当前 BOM 不覆盖历史。
 */
@Service
public class QueryService {

    private final JdbcSupport db;

    public QueryService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> listSnapshots(Long equipmentId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.*, e.entity_code AS equipment_code FROM config_snapshot s " +
                "JOIN master_entity e ON e.id=s.equipment_id WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (equipmentId != null) {
            sql.append(" AND s.equipment_id=?");
            args.add(equipmentId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND s.status=?");
            args.add(status);
        }
        sql.append(" ORDER BY s.point_in_time DESC, s.id DESC");
        return db.query(sql.toString(), args.toArray());
    }

    public Map<String, Object> snapshotDetail(long snapshotId) {
        Map<String, Object> snapshot = db.queryOne(
                "SELECT s.*, e.entity_code AS equipment_code, e.display_name AS equipment_name " +
                "FROM config_snapshot s JOIN master_entity e ON e.id=s.equipment_id WHERE s.id=?", snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + snapshotId);
        }
        List<Map<String, Object>> positions = db.query(
                "SELECT sp.*, pos.entity_code AS position_code, pos.display_name AS position_name, " +
                "pm.entity_code AS model_code, pm.display_name AS model_name, " +
                "pp.entity_code AS physical_code, pp.display_name AS physical_name " +
                "FROM snapshot_position sp " +
                "JOIN master_entity pos ON pos.id=sp.mount_position_id " +
                "LEFT JOIN master_entity pm ON pm.id=sp.part_model_id " +
                "LEFT JOIN master_entity pp ON pp.id=sp.physical_part_id " +
                "WHERE sp.snapshot_id=? ORDER BY pos.id", snapshotId);
        List<Map<String, Object>> conflicts = db.query(
                "SELECT * FROM conflict_fact WHERE snapshot_id=? ORDER BY id", snapshotId);
        List<Map<String, Object>> facts = db.query(
                "SELECT * FROM compatibility_fact WHERE snapshot_id=? ORDER BY id", snapshotId);
        return Map.of("snapshot", snapshot, "positions", positions,
                "conflicts", conflicts, "facts", facts);
    }

    /**
     * 时点查询：返回该设备在指定时点最近一次编译的快照版本（不重新编译，只选版本）。
     */
    public Map<String, Object> asOfQuery(long equipmentId, OffsetDateTime pointInTime) {
        Map<String, Object> snapshot = db.queryOne(
                "SELECT s.*, e.entity_code AS equipment_code FROM config_snapshot s " +
                "JOIN master_entity e ON e.id=s.equipment_id " +
                "WHERE s.equipment_id=? AND s.point_in_time<=? " +
                "ORDER BY s.point_in_time DESC, s.id DESC LIMIT 1", equipmentId, pointInTime);
        if (snapshot == null) {
            return Map.of("equipmentId", equipmentId, "pointInTime", pointInTime,
                    "available", false, "message", "该时点之前没有已编译快照，请先选择时点编译");
        }
        return Map.of("available", true, "snapshot", snapshot,
                "detail", snapshotDetail(((Number) snapshot.get("id")).longValue()));
    }

    public List<Map<String, Object>> listDrawings() {
        return db.query("SELECT d.*, pm.entity_code AS part_model_code FROM drawing d " +
                "LEFT JOIN master_entity pm ON pm.id=d.part_model_id ORDER BY d.id");
    }

    public List<Map<String, Object>> drawingRevisions(Long drawingId) {
        String sql = "SELECT dr.*, d.drawing_no FROM drawing_revision dr " +
                "JOIN drawing d ON d.id=dr.drawing_id";
        if (drawingId == null) {
            return db.query(sql + " ORDER BY dr.drawing_id, dr.revision");
        }
        return db.query(sql + " WHERE dr.drawing_id=? ORDER BY dr.revision", drawingId);
    }

    public List<Map<String, Object>> listInspections() {
        return db.query("SELECT * FROM inspection_record ORDER BY inspected_at DESC, id DESC");
    }

    public List<Map<String, Object>> listFactoryRecords(Long equipmentId) {
        if (equipmentId == null) {
            return db.query("SELECT fr.*, e.entity_code AS equipment_code FROM factory_record fr " +
                    "JOIN master_entity e ON e.id=fr.equipment_id ORDER BY fr.id");
        }
        return db.query("SELECT fr.*, e.entity_code AS equipment_code FROM factory_record fr " +
                "JOIN master_entity e ON e.id=fr.equipment_id WHERE fr.equipment_id=? ORDER BY fr.id", equipmentId);
    }

    public List<Map<String, Object>> lineageOfRule(long ruleId) {
        // 规则 -> 约束 -> 图号修订 -> 测试报告 -> 引用它的兼容事实
        Map<String, Object> rule = db.queryOne("SELECT * FROM substitution_rule WHERE id=?", ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        List<Map<String, Object>> constraints = db.query(
                "SELECT * FROM rule_constraint WHERE rule_id=? ORDER BY id", ruleId);
        Map<String, Object> drawing = rule.get("drawing_revision_id") == null ? null : db.queryOne(
                "SELECT dr.*, d.drawing_no FROM drawing_revision dr JOIN drawing d ON d.id=dr.drawing_id " +
                "WHERE dr.id=?", rule.get("drawing_revision_id"));
        Map<String, Object> report = rule.get("test_report_id") == null ? null : db.queryOne(
                "SELECT * FROM inspection_record WHERE id=?", rule.get("test_report_id"));
        List<Map<String, Object>> facts = db.query(
                "SELECT id, fact_no, verdict, status, rule_version_locked, snapshot_id " +
                "FROM compatibility_fact WHERE rule_id=? ORDER BY id", ruleId);
        return List.of(Map.of(
                "rule", rule, "constraints", constraints, "drawingRevision", drawing == null ? Map.of() : drawing,
                "testReport", report == null ? Map.of() : report, "referencingFacts", facts));
    }
}
