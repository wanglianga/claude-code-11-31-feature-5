package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 实体消歧器：
 * 部件型号 / 实物序列号 / 装配位置 / 设备序列号 / 供应商编码分开建模。
 * 名称相似只产生待裁定候选，绝不自动合并；同名异件由人工显式裁定。
 */
@Service
public class EntityDisambiguationService {

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public EntityDisambiguationService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    public List<Map<String, Object>> listEntities(String entityType, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM master_entity WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type=?");
            args.add(entityType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (entity_code ILIKE ? OR display_name ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY entity_type, id");
        return db.query(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> aliasCandidates(String decision) {
        String sql = "SELECT ac.*, e.display_name AS candidate_name, e.entity_code AS candidate_code " +
                "FROM entity_alias_candidate ac " +
                "JOIN master_entity e ON e.id=ac.candidate_entity_id WHERE 1=1";
        if (decision != null && !decision.isBlank()) {
            return db.query(sql + " AND ac.decision=? ORDER BY ac.id", decision);
        }
        return db.query(sql + " ORDER BY ac.id");
    }

    /**
     * 人工裁定同名候选。
     * KEEP_DISTINCT：写入 distinct_from，两个实体永久分立；
     * MERGED：仅允许人工显式合并（记录操作人与原因），系统从不自动触发。
     */
    @Transactional
    public Map<String, Object> decideAlias(long candidateId, String decision, String note) {
        Map<String, Object> candidate = db.queryOne("SELECT * FROM entity_alias_candidate WHERE id=?", candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("消歧候选不存在: " + candidateId);
        }
        if (!"KEEP_DISTINCT".equals(decision) && !"MERGED".equals(decision)) {
            throw new IllegalArgumentException("裁定动作只支持 KEEP_DISTINCT / MERGED");
        }
        long entityId = ((Number) candidate.get("candidate_entity_id")).longValue();
        Long targetId = candidate.get("target_entity_id") == null ? null
                : ((Number) candidate.get("target_entity_id")).longValue();

        if ("KEEP_DISTINCT".equals(decision) && targetId != null) {
            Map<String, Object> entity = db.queryOne("SELECT * FROM master_entity WHERE id=?", entityId);
            boolean already = db.toLongList(entity.get("distinct_from")).contains(targetId);
            if (!already) {
                db.update("UPDATE master_entity SET distinct_from = array_append(distinct_from, ?), " +
                        "updated_at=now() WHERE id=?", targetId, entityId);
            }
        }
        db.update("UPDATE entity_alias_candidate SET decision=?, decided_by=?, decided_at=now(), note=? WHERE id=?",
                decision, CurrentUser.username(), note, candidateId);
        return db.queryOne("SELECT * FROM entity_alias_candidate WHERE id=?", candidateId);
    }

    public List<Map<String, Object>> openConflicts() {
        return db.query("SELECT * FROM conflict_fact WHERE resolution_status='OPEN' ORDER BY id DESC");
    }

    /**
     * 凭证裁定：处理双重安装/图号重叠冲突。
     * 裁定后冲突关闭、相关事件解除冻结；已生成的冲突快照不删除，保留审计轨迹。
     */
    @Transactional
    public Map<String, Object> adjudicateConflict(long conflictId, String resolutionNote,
                                                  Boolean keepFirstVoucher) {
        Map<String, Object> conflict = db.queryOne("SELECT * FROM conflict_fact WHERE id=?", conflictId);
        if (conflict == null) {
            throw new IllegalArgumentException("冲突事实不存在: " + conflictId);
        }
        String type = String.valueOf(conflict.get("conflict_type"));
        List<Long> eventIds = db.toLongList(conflict.get("related_event_ids"));
        if ("PHYSICAL_PART_DOUBLE_MOUNT".equals(type) && !eventIds.isEmpty()) {
            for (Long eventId : eventIds) {
                db.update("UPDATE install_event SET adjudication='ADJUDICATED', adjudication_note=? WHERE id=?",
                        resolutionNote, eventId);
            }
            List<Long> entityIds = db.toLongList(conflict.get("related_entity_ids"));
            if (!entityIds.isEmpty()) {
                long physicalId = entityIds.get(0);
                db.update("UPDATE master_entity SET status='VERIFIED', updated_at=now() WHERE id=? AND status='FROZEN'",
                        physicalId);
            }
        }
        db.update("UPDATE conflict_fact SET resolution_status='ADJUDICATED', resolution_note=?, " +
                "resolved_by=?, resolved_at=now() WHERE id=?",
                resolutionNote, CurrentUser.username(), conflictId);
        qualityEvents.record("SNAPSHOT_REVIEW", "MEDIUM", "CONFLICT", conflictId,
                conflict.get("snapshot_id") == null ? null
                        : ((Number) conflict.get("snapshot_id")).longValue(), null,
                "冲突 " + conflict.get("conflict_code") + " 已凭证裁定：" + resolutionNote,
                Map.of("keepFirstVoucher", keepFirstVoucher == null ? false : keepFirstVoucher));
        return db.queryOne("SELECT * FROM conflict_fact WHERE id=?", conflictId);
    }
}
