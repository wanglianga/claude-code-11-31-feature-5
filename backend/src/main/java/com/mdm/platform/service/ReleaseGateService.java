package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 发布闸门：草稿 / 已验证 / 待复核 / 已废止 分状态对外提供。
 * 存在未裁定冲突事实的快照，其兼容事实禁止发布。
 */
@Service
public class ReleaseGateService {

    private final JdbcSupport db;

    public ReleaseGateService(JdbcSupport db) {
        this.db = db;
    }

    private void assertNoOpenBlocker(long snapshotId, long mountPositionId) {
        // 1) 直接挂在快照上的未裁定冲突（如图号序列号区间重叠）
        long blockers = db.count(
                "SELECT count(*) FROM conflict_fact WHERE snapshot_id=? AND resolution_status='OPEN' " +
                "AND blocks_release=TRUE", snapshotId);
        if (blockers > 0) {
            throw new IllegalStateException("快照存在 " + blockers + " 条未裁定且阻止发布的冲突事实，兼容发布被闸门拦截");
        }
        // 2) 被评估位置本身必须是已解析状态（冻结/冲突/空位不得对外发布）
        Map<String, Object> position = db.queryOne(
                "SELECT position_state FROM snapshot_position WHERE snapshot_id=? AND mount_position_id=?",
                snapshotId, mountPositionId);
        if (position == null || !"RESOLVED".equals(position.get("position_state"))) {
            String state = position == null ? "UNKNOWN" : String.valueOf(position.get("position_state"));
            throw new IllegalStateException("发布闸门拦截：该装配位置状态为 " + state + "，需凭证裁定后才能发布");
        }
    }

    @Transactional
    public Map<String, Object> transition(long factId, String action, String reason) {
        Map<String, Object> fact = db.queryOne("SELECT * FROM compatibility_fact WHERE id=?", factId);
        if (fact == null) {
            throw new IllegalArgumentException("兼容事实不存在: " + factId);
        }
        String from = String.valueOf(fact.get("status"));
        String to = switch (action) {
            case "publish" -> {
                if (!"DRAFT".equals(from) && !"REVIEW".equals(from)) {
                    throw new IllegalStateException("仅草稿/待复核事实可发布，当前状态: " + from);
                }
                assertNoOpenBlocker(((Number) fact.get("snapshot_id")).longValue(),
                        ((Number) fact.get("mount_position_id")).longValue());
                String verdict = String.valueOf(fact.get("verdict"));
                if (!"PASS".equals(verdict)) {
                    throw new IllegalStateException("发布闸门拦截：仅结论为 PASS 的事实可对外发布，当前结论 "
                            + verdict + "（不通过/证据不足只能留档复核）");
                }
                yield "VERIFIED";
            }
            case "review" -> "REVIEW";
            case "retire" -> {
                if (!"VERIFIED".equals(from)) {
                    throw new IllegalStateException("仅已验证事实可废止，当前状态: " + from);
                }
                yield "RETIRED";
            }
            default -> throw new IllegalArgumentException("未知闸门动作: " + action);
        };
        db.update("UPDATE compatibility_fact SET status=? WHERE id=?", to, factId);
        if ("publish".equals(action)) {
            db.update("UPDATE compatibility_fact SET issued_at=COALESCE(issued_at, now()) WHERE id=?", factId);
        }
        db.update("INSERT INTO release_audit_log(fact_id,snapshot_id,action,from_status,to_status,operator,reason) " +
                "VALUES (?,?,?,?,?,?,?)", factId, fact.get("snapshot_id"), action.toUpperCase(),
                from, to, CurrentUser.username(), reason);
        return db.queryOne("SELECT * FROM compatibility_fact WHERE id=?", factId);
    }

    /** 对外发布视图：仅已验证事实（已废止/待复核/草稿不进入对外通道） */
    public List<Map<String, Object>> publicFacts() {
        return db.query("SELECT * FROM compatibility_fact WHERE status='VERIFIED' ORDER BY issued_at DESC, id DESC");
    }

    public List<Map<String, Object>> auditLogs() {
        return db.query("SELECT * FROM release_audit_log ORDER BY id DESC LIMIT 200");
    }
}
