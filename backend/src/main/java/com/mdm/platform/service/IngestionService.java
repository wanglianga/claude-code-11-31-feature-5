package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import com.mdm.platform.security.DigestUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 资料接入服务：契约库登记、追加式实装事件登记、规则撤回。
 * 所有写入保留来源标识与文件指纹；事件只追加，不提供更新/删除入口。
 */
@Service
public class IngestionService {

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public IngestionService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    public List<Map<String, Object>> listContracts() {
        return db.query("SELECT * FROM ingestion_contract ORDER BY id");
    }

    @Transactional
    public Map<String, Object> registerContract(Map<String, Object> body) {
        String code = String.valueOf(body.get("contractCode"));
        String version = String.valueOf(body.getOrDefault("schemaVersion", "v1"));
        long count = db.count("SELECT count(*) FROM ingestion_contract WHERE contract_code=?", code);
        long id;
        if (count == 0) {
            id = db.insertReturningId(
                    "INSERT INTO ingestion_contract(contract_code,contract_name,source_team,source_system," +
                    "schema_version,effective_from,status,field_spec,trust_score,created_by) " +
                    "VALUES (?,?,?,?,?,?::timestamptz,?,?,?::jsonb,?,?) RETURNING id",
                    code, body.get("contractName"), body.get("sourceTeam"), body.get("sourceSystem"),
                    version, body.getOrDefault("effectiveFrom", OffsetDateTime.now().toString()),
                    body.getOrDefault("status", "DRAFT"),
                    db.toJson(body.getOrDefault("fieldSpec", List.of())),
                    body.getOrDefault("trustScore", 50), CurrentUser.username());
        } else {
            // 新版本不覆盖旧版本：主表更新当前版本，版本索引保留全部历史
            id = ((Number) db.queryOne("SELECT id FROM ingestion_contract WHERE contract_code=?", code)
                    .get("id")).longValue();
            db.update("UPDATE ingestion_contract SET schema_version=?, field_spec=?::jsonb, trust_score=?, " +
                            "updated_at=now() WHERE id=?",
                    version, db.toJson(body.getOrDefault("fieldSpec", List.of())),
                    body.getOrDefault("trustScore", 50), id);
        }
        String sourceRef = String.valueOf(body.getOrDefault("sourceRef", "manual-registration"));
        String digest = DigestUtil.sha256(code + version + sourceRef);
        db.update("INSERT INTO ingestion_contract_version(contract_code,schema_version,source_team,source_ref," +
                        "payload_digest,field_spec,trust_score) VALUES (?,?,?,?,?,?,?::jsonb,?) " +
                        "ON CONFLICT (contract_code, schema_version) DO NOTHING",
                code, version, body.get("sourceTeam"), sourceRef, "sha256:" + digest,
                db.toJson(body.getOrDefault("fieldSpec", List.of())), body.getOrDefault("trustScore", 50));
        return db.queryOne("SELECT * FROM ingestion_contract WHERE id=?", id);
    }

    public List<Map<String, Object>> listEvents() {
        return db.query("SELECT e.*, eq.entity_code AS equipment_code, pos.entity_code AS position_code, " +
                "pp.entity_code AS physical_code, pm.entity_code AS model_code " +
                "FROM install_event e " +
                "JOIN master_entity eq ON eq.id=e.equipment_id " +
                "JOIN master_entity pos ON pos.id=e.mount_position_id " +
                "JOIN master_entity pp ON pp.id=e.physical_part_id " +
                "JOIN master_entity pm ON pm.id=e.part_model_id ORDER BY e.event_time, e.id");
    }

    /**
     * 登记拆装凭证（追加式）。接收时间晚于事件时间 24h 即标记为迟到凭证并产生质量事件。
     */
    @Transactional
    public Map<String, Object> appendInstallEvent(Map<String, Object> body) {
        String eventCode = String.valueOf(body.get("eventCode"));
        Long dup = db.queryOne("SELECT id FROM install_event WHERE event_code=?", eventCode) == null ? null : 1L;
        if (dup != null) {
            throw new IllegalStateException("凭证号已存在，事件只能追加不能覆盖: " + eventCode);
        }
        OffsetDateTime eventTime = OffsetDateTime.parse(String.valueOf(body.get("eventTime")));
        OffsetDateTime receivedAt = body.get("receivedAt") == null
                ? OffsetDateTime.now()
                : OffsetDateTime.parse(String.valueOf(body.get("receivedAt")));
        String sourceRef = String.valueOf(body.getOrDefault("sourceRef", eventCode));
        String digest = DigestUtil.sha256(eventCode + eventTime);
        long id = db.insertReturningId(
                "INSERT INTO install_event(event_code,equipment_id,mount_position_id,physical_part_id," +
                "part_model_id,event_type,event_time,received_at,source_contract_id,source_ref,payload_digest," +
                "trust_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                eventCode, body.get("equipmentId"), body.get("mountPositionId"),
                body.get("physicalPartId"), body.get("partModelId"), body.get("eventType"),
                eventTime, receivedAt, body.get("sourceContractId"), sourceRef,
                "sha256:" + digest, body.getOrDefault("trustScore", 70));

        long gapHours = ChronoUnit.HOURS.between(eventTime, receivedAt);
        if (gapHours > 24) {
            qualityEvents.record("LATE_VOUCHER", "MEDIUM", "INSTALL_EVENT", id, null, null,
                    "凭证 " + eventCode + " 迟到 " + gapHours + " 小时（事件时间 " + eventTime
                            + "，接收时间 " + receivedAt + "），历史快照需按新版本重放，旧结论不改写",
                    Map.of("eventCode", eventCode, "gapHours", gapHours));
        }
        return db.queryOne("SELECT * FROM install_event WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> retireRule(long ruleId, String reason) {
        Map<String, Object> rule = db.queryOne("SELECT * FROM substitution_rule WHERE id=?", ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        if ("RETIRED".equals(rule.get("status"))) {
            throw new IllegalStateException("规则已处于撤回状态");
        }
        db.update("UPDATE substitution_rule SET status='RETIRED', retired_at=now(), " +
                "effective_to=COALESCE(effective_to, now()), retire_reason=? WHERE id=?",
                reason, ruleId);
        qualityEvents.record("RULE_RETIRE", "HIGH", "RULE", ruleId, null, null,
                "供应商收回替代规则 " + rule.get("rule_code") + "：" + reason,
                Map.of("ruleCode", rule.get("rule_code"), "ruleVersion", rule.get("rule_version")));
        return db.queryOne("SELECT * FROM substitution_rule WHERE id=?", ruleId);
    }

    public List<Map<String, Object>> listRules() {
        return db.query("SELECT r.*, f.entity_code AS from_model, t.entity_code AS to_model, " +
                "s.display_name AS supplier_name " +
                "FROM substitution_rule r " +
                "JOIN master_entity f ON f.id=r.from_part_model_id " +
                "JOIN master_entity t ON t.id=r.to_part_model_id " +
                "LEFT JOIN master_entity s ON s.id=r.supplier_id ORDER BY r.id");
    }

    public List<Map<String, Object>> listConstraints(Long ruleId) {
        if (ruleId == null) {
            return db.query("SELECT * FROM rule_constraint ORDER BY rule_id, id");
        }
        return db.query("SELECT * FROM rule_constraint WHERE rule_id=? ORDER BY id", ruleId);
    }
}
