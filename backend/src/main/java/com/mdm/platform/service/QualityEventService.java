package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class QualityEventService {

    private final JdbcSupport db;

    public QualityEventService(JdbcSupport db) {
        this.db = db;
    }

    public void record(String eventType, String severity, String entityType, Long entityId,
                       Long snapshotId, Long factId, String description, Object detail) {
        long count = db.count("SELECT count(*) FROM quality_event");
        String eventNo = String.format("QE-%s-%04d", eventType.toLowerCase().replace('_', '-'), count + 1);
        db.update("INSERT INTO quality_event(event_no,event_type,severity,related_entity_type,related_entity_id," +
                        "related_snapshot_id,related_fact_id,description,detail) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                eventNo, eventType, severity, entityType, entityId, snapshotId, factId,
                description, db.toJson(detail == null ? Map.of() : detail));
    }

    public List<Map<String, Object>> list(String handlingStatus) {
        if (handlingStatus == null || handlingStatus.isBlank()) {
            return db.query("SELECT * FROM quality_event ORDER BY id DESC");
        }
        return db.query("SELECT * FROM quality_event WHERE handling_status=? ORDER BY id DESC", handlingStatus);
    }

    public void close(long id, String note) {
        int n = db.update("UPDATE quality_event SET handling_status='CLOSED', closed_at=now(), " +
                "detail=detail||?::jsonb WHERE id=?", db.toJson(Map.of("closeNote", note == null ? "" : note)), id);
        if (n == 0) {
            throw new IllegalArgumentException("质量事件不存在: " + id);
        }
    }
}
