package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.DigestUtil;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置快照编译器（核心）：
 * 按指定维修时点重放出厂记录 + 追加式拆装事件，生成位置级不可变快照。
 *  - 迟到凭证：通过 asOfReceived 模拟"当时已知"，新编译产生新版本快照，旧快照永不改写
 *  - 图号序列号区间重叠 -> 冲突事实，阻止兼容发布
 *  - 同一实物重叠时间出现在两台设备 -> 冻结相关配置并登记凭证裁定队列
 */
@Service
public class SnapshotService {

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public SnapshotService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    public record CompileRequest(long equipmentId, OffsetDateTime pointInTime, OffsetDateTime asOfReceived,
                                 String operator) {}

    /**
     * 编译快照。每次调用都生成一个新版本，历史快照保留。
     */
    public Map<String, Object> compile(CompileRequest req) {
        Map<String, Object> equipment = db.queryOne(
                "SELECT * FROM master_entity WHERE id=? AND entity_type='EQUIPMENT'", req.equipmentId());
        if (equipment == null) {
            throw new IllegalArgumentException("设备不存在: " + req.equipmentId());
        }

        // 1. 全局扫描双重安装（仅纳入"接收时点边界"之前已知的凭证，模拟平台当时的认知）
        detectAndFreezeDoubleMounts(req.asOfReceived());

        // 2. 收集出厂基线
        List<Map<String, Object>> factories = db.query(
                "SELECT * FROM factory_record WHERE equipment_id=? AND factory_time<=? ORDER BY id",
                req.equipmentId(), req.pointInTime());

        // 3. 收集维修时点之前、且在"已知边界"之前接收到的事件（迟到凭证可被重放排除/纳入）
        String eventSql = "SELECT * FROM install_event WHERE equipment_id=? AND event_time<=?";
        List<Object> args = new ArrayList<>(List.of(req.equipmentId(), req.pointInTime()));
        if (req.asOfReceived() != null) {
            eventSql += " AND received_at<=?";
            args.add(req.asOfReceived());
        }
        eventSql += " ORDER BY event_time, id";
        List<Map<String, Object>> events = db.query(eventSql, args.toArray());

        // 4. 位置级重放
        Map<Long, Map<String, Object>> positions = new LinkedHashMap<>();
        for (Map<String, Object> f : factories) {
            long posId = ((Number) f.get("mount_position_id")).longValue();
            Map<String, Object> state = new HashMap<>();
            state.put("partModelId", f.get("part_model_id"));
            state.put("physicalPartId", f.get("physical_part_id"));
            state.put("basis", "FACTORY");
            state.put("basisEventId", null);
            state.put("trustScore", f.get("trust_score"));
            state.put("factoryId", f.get("id"));
            positions.put(posId, state);
        }
        for (Map<String, Object> e : events) {
            long posId = ((Number) e.get("mount_position_id")).longValue();
            Map<String, Object> state = positions.computeIfAbsent(posId, k -> new HashMap<>());
            if ("INSTALL".equals(e.get("event_type"))) {
                state.put("partModelId", e.get("part_model_id"));
                state.put("physicalPartId", e.get("physical_part_id"));
                state.put("basis", "INSTALL_EVENT");
                state.put("basisEventId", e.get("id"));
                state.put("trustScore", e.get("trust_score"));
            } else {
                state.put("partModelId", null);
                state.put("physicalPartId", null);
                state.put("basis", "REMOVE_EMPTY");
                state.put("basisEventId", e.get("id"));
                state.put("trustScore", e.get("trust_score"));
            }
            // 冻结状态同样受"接收时点边界"约束：边界之后才接收到的凭证，
            // 不应让历史时点快照呈现冻结（点-in-time 认知保真）
            boolean frozenNow = "FROZEN".equals(e.get("adjudication"));
            boolean knownAtBoundary = req.asOfReceived() == null
                    || !db.toOffsetDateTime(e.get("received_at")).isAfter(req.asOfReceived());
            state.put("frozen", frozenNow && knownAtBoundary);
        }

        // 5. 图号序列号区间重叠检查
        List<Map<String, Object>> drawingConflicts = detectDrawingRangeOverlaps(equipment, req.pointInTime());

        // 6. 组装持久化
        long version = db.count("SELECT count(*) FROM config_snapshot WHERE equipment_id=?", req.equipmentId()) + 1;
        String snapshotNo = String.format("SNP-%s-%s-v%d",
                String.valueOf(equipment.get("entity_code")).replace("EQ-CNC-2024-", ""),
                req.pointInTime().toLocalDate().toString().replace("-", ""), version);

        List<Long> eventIds = events.stream().map(e -> ((Number) e.get("id")).longValue()).toList();
        List<Long> factoryIds = factories.stream().map(f -> ((Number) f.get("id")).longValue()).toList();
        double minTrust = positions.values().stream()
                .filter(s -> s.get("trustScore") != null)
                .map(s -> ((Number) s.get("trustScore")).doubleValue())
                .min(Comparator.naturalOrder()).orElse(100d);

        boolean hasConflict = !drawingConflicts.isEmpty()
                || positions.values().stream().anyMatch(s -> Boolean.TRUE.equals(s.get("frozen")));

        String digestInput = "eq=" + req.equipmentId()
                + "|pit=" + req.pointInTime()
                + "|factories=" + factoryIds
                + "|events=" + eventIds.stream().map(id -> id + "@" +
                    events.stream().filter(e -> ((Number) e.get("id")).longValue() == id)
                            .findFirst().map(e -> e.get("event_type") + "@" + e.get("event_time")).orElse(""))
                .toList();
        String replayDigest = DigestUtil.sha256(digestInput);

        long snapshotId = db.insertReturningId(
                "INSERT INTO config_snapshot(snapshot_no,equipment_id,point_in_time,source_event_ids," +
                "source_factory_ids,min_trust_score,has_conflict,conflict_detail,replay_digest,status,created_by) " +
                "VALUES (?,?,?,?::bigint[],?::bigint[],?,?,?::jsonb,?,?,?) RETURNING id",
                snapshotNo, req.equipmentId(), req.pointInTime(),
                db.pgLongArrayLiteral(eventIds), db.pgLongArrayLiteral(factoryIds),
                minTrust, hasConflict, db.toJson(drawingConflicts), replayDigest,
                hasConflict ? "FROZEN" : "DRAFT", req.operator());

        for (Map.Entry<Long, Map<String, Object>> entry : positions.entrySet()) {
            long posId = entry.getKey();
            Map<String, Object> s = entry.getValue();
            boolean frozen = Boolean.TRUE.equals(s.get("frozen"));
            String positionState = frozen ? "FROZEN"
                    : "REMOVE_EMPTY".equals(s.get("basis")) ? "VACANT" : "RESOLVED";
            String basis = frozen ? "CONFLICT" : String.valueOf(s.get("basis"));
            Map<String, Object> detail = new HashMap<>();
            detail.put("lateVoucherConsidered", req.asOfReceived() == null);
            detail.put("replayOrder", eventIds);
            db.update("INSERT INTO snapshot_position(snapshot_id,mount_position_id,part_model_id," +
                            "physical_part_id,basis,basis_event_id,trust_score,position_state,detail) " +
                            "VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                    snapshotId, posId, s.get("partModelId"), s.get("physicalPartId"),
                    basis, s.get("basisEventId"), s.get("trustScore"), positionState,
                    db.toJson(detail));
        }

        // 7. 冲突事实落库 + 质量事件
        for (Map<String, Object> c : drawingConflicts) {
            String conflictCode = "CFL-DRAW-" + snapshotId + "-" + c.get("drawingId");
            Map<String, Object> existRow = db.queryOne(
                    "SELECT id FROM conflict_fact WHERE conflict_code=?", conflictCode);
            if (existRow == null) {
                db.update("INSERT INTO conflict_fact(conflict_code,conflict_type,equipment_id,snapshot_id," +
                                "related_entity_ids,description,blocks_release) VALUES (?,?,?,?,?::bigint[],?,TRUE)",
                        conflictCode, "DRAWING_RANGE_OVERLAP", req.equipmentId(), snapshotId,
                        db.pgLongArrayLiteral(List.of(((Number) c.get("drawingId")).longValue())),
                        "设备 " + equipment.get("entity_code") + " 在维修时点 " + pitText(req)
                                + " 同时命中图号 " + c.get("drawingNo") + " 的两个生效修订: "
                                + c.get("revisions") + "，阻止兼容发布，等待工程裁定");
                qualityEvents.record("CONFLICT_BLOCK", "HIGH", "DRAWING",
                        ((Number) c.get("drawingId")).longValue(), snapshotId, null,
                        "图号序列号区间重叠导致快照 " + snapshotNo + " 存在冲突事实", c);
            }
        }

        return db.queryOne("SELECT * FROM config_snapshot WHERE id=?", snapshotId);
    }

    private String pitText(CompileRequest req) {
        return req.pointInTime().toLocalDate().toString();
    }

    /**
     * 图号区间重叠：取设备出厂序列号，找出在维修时点同时生效且序列号区间都覆盖它的修订。
     */
    private List<Map<String, Object>> detectDrawingRangeOverlaps(Map<String, Object> equipment,
                                                                  OffsetDateTime pit) {
        Map<String, Object> spec = db.toMap(equipment.get("spec_payload"));
        String serial = String.valueOf(spec.getOrDefault("factorySerialNo", ""));
        long serialNum = parseSerialNumber(serial);
        List<Map<String, Object>> revs = db.query(
                "SELECT dr.*, d.drawing_no FROM drawing_revision dr JOIN drawing d ON d.id=dr.drawing_id " +
                "WHERE dr.status='VERIFIED' AND dr.effective_from<=? " +
                "AND (dr.effective_to IS NULL OR dr.effective_to>?)", pit, pit);
        Map<Long, List<Map<String, Object>>> byDrawing = new LinkedHashMap<>();
        for (Map<String, Object> r : revs) {
            long from = parseSerialNumber(String.valueOf(r.get("serial_range_from")));
            long to = parseSerialNumber(String.valueOf(r.get("serial_range_to")));
            if (serialNum >= from && serialNum <= to) {
                byDrawing.computeIfAbsent(((Number) r.get("drawing_id")).longValue(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> e : byDrawing.entrySet()) {
            if (e.getValue().size() > 1) {
                Map<String, Object> c = new HashMap<>();
                c.put("drawingId", e.getKey());
                c.put("drawingNo", e.getValue().get(0).get("drawing_no"));
                c.put("equipmentSerial", serial);
                c.put("revisions", e.getValue().stream()
                        .map(r -> r.get("revision") + "(" + r.get("serial_range_from") + "~"
                                + r.get("serial_range_to") + ",digest=" + r.get("payload_digest") + ")")
                        .toList());
                conflicts.add(c);
            }
        }
        return conflicts;
    }

    /**
     * 同一实物重叠时间段出现在两台设备：冻结实物与相关事件，登记冲突事实。
     * asOfReceived 非空时，只扫描该边界之前接收到的凭证（迟到/后到凭证不参与当时的判定）。
     */
    private void detectAndFreezeDoubleMounts(OffsetDateTime asOfReceived) {
        String physicalSql = "SELECT DISTINCT physical_part_id FROM install_event " +
                "WHERE adjudication<>'ADJUDICATED'" + (asOfReceived == null ? "" : " AND received_at<=?");
        List<Map<String, Object>> physicals = asOfReceived == null
                ? db.query(physicalSql)
                : db.query(physicalSql, asOfReceived);
        for (Map<String, Object> row : physicals) {
            long physicalId = ((Number) row.get("physical_part_id")).longValue();
            String installSql = "SELECT * FROM install_event WHERE physical_part_id=? AND event_type='INSTALL'";
            List<Map<String, Object>> installs = asOfReceived == null
                    ? db.query(installSql + " ORDER BY event_time, id", physicalId)
                    : db.query(installSql + " AND received_at<=? ORDER BY event_time, id",
                            physicalId, asOfReceived);
            for (int i = 0; i < installs.size(); i++) {
                for (int j = i + 1; j < installs.size(); j++) {
                    Map<String, Object> a = installs.get(i);
                    Map<String, Object> b = installs.get(j);
                    long eqA = ((Number) a.get("equipment_id")).longValue();
                    long eqB = ((Number) b.get("equipment_id")).longValue();
                    if (eqA == eqB) {
                        continue;
                    }
                    OffsetDateTime endA = findRemoveTime(physicalId, eqA, a);
                    OffsetDateTime endB = findRemoveTime(physicalId, eqB, b);
                    OffsetDateTime startA = db.toOffsetDateTime(a.get("event_time"));
                    OffsetDateTime startB = db.toOffsetDateTime(b.get("event_time"));
                    boolean overlap = startA.isBefore(endB) && startB.isBefore(endA);
                    if (overlap) {
                        registerDoubleMountConflict(physicalId, a, b);
                    }
                }
            }
        }
    }

    private OffsetDateTime findRemoveTime(long physicalId, long equipmentId, Map<String, Object> install) {
        Map<String, Object> remove = db.queryOne(
                "SELECT * FROM install_event WHERE physical_part_id=? AND equipment_id=? AND event_type='REMOVE' " +
                "AND event_time>? ORDER BY event_time LIMIT 1",
                physicalId, equipmentId, install.get("event_time"));
        return remove == null ? OffsetDateTime.parse("2099-12-31T00:00:00Z")
                : db.toOffsetDateTime(remove.get("event_time"));
    }

    private void registerDoubleMountConflict(long physicalId, Map<String, Object> a, Map<String, Object> b) {
        String conflictCode = "CFL-DOUBLE-" + physicalId;
        Map<String, Object> existing = db.queryOne(
                "SELECT id FROM conflict_fact WHERE conflict_code=? AND resolution_status='OPEN'", conflictCode);
        if (existing != null) {
            return;
        }
        db.update("INSERT INTO conflict_fact(conflict_code,conflict_type,related_entity_ids,related_event_ids," +
                        "description,blocks_release) VALUES (?,?,?::bigint[],?::bigint[],?,TRUE)",
                conflictCode, "PHYSICAL_PART_DOUBLE_MOUNT",
                db.pgLongArrayLiteral(List.of(physicalId, ((Number) a.get("equipment_id")).longValue(),
                        ((Number) b.get("equipment_id")).longValue())),
                db.pgLongArrayLiteral(List.of(((Number) a.get("id")).longValue(),
                        ((Number) b.get("id")).longValue())),
                "实物 " + physicalId + " 在重叠时间段分别安装于设备 " + a.get("equipment_id") + " 与设备 "
                        + b.get("equipment_id") + "（凭证 " + a.get("event_code") + " / "
                        + b.get("event_code") + "），已冻结相关配置，等待凭证裁定");
        db.update("UPDATE master_entity SET status='FROZEN', updated_at=now() WHERE id=?", physicalId);
        db.update("UPDATE install_event SET adjudication='FROZEN' WHERE physical_part_id=? " +
                "AND adjudication='NORMAL'", physicalId);
        Map<String, Object> physical = db.queryOne("SELECT * FROM master_entity WHERE id=?", physicalId);
        qualityEvents.record("ENTITY_FREEZE", "CRITICAL", "PHYSICAL_PART", physicalId, null, null,
                "实物 " + (physical == null ? physicalId : physical.get("entity_code"))
                        + " 双重安装，配置冻结等待凭证裁定",
                Map.of("eventA", a.get("event_code"), "eventB", b.get("event_code")));
    }

    public static long parseSerialNumber(String serial) {
        String digits = serial.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }
}
