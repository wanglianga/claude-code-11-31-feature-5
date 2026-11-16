package com.mdm.platform.web;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.service.IngestionService;
import com.mdm.platform.service.QueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class MasterDataController {

    private final QueryService query;
    private final IngestionService ingestion;
    private final JdbcSupport db;

    public MasterDataController(QueryService query, IngestionService ingestion, JdbcSupport db) {
        this.query = query;
        this.ingestion = ingestion;
        this.db = db;
    }

    // ---------- 图号 ----------
    @GetMapping("/drawings")
    public ApiResult<List<Map<String, Object>>> drawings() {
        return ApiResult.ok(query.listDrawings());
    }

    @GetMapping("/drawing-revisions")
    public ApiResult<List<Map<String, Object>>> drawingRevisions(
            @RequestParam(required = false) Long drawingId) {
        return ApiResult.ok(query.drawingRevisions(drawingId));
    }

    // ---------- 检验记录 ----------
    @GetMapping("/inspections")
    public ApiResult<List<Map<String, Object>>> inspections() {
        return ApiResult.ok(query.listInspections());
    }

    // ---------- 出厂记录 ----------
    @GetMapping("/factory-records")
    public ApiResult<List<Map<String, Object>>> factoryRecords(
            @RequestParam(required = false) Long equipmentId) {
        return ApiResult.ok(query.listFactoryRecords(equipmentId));
    }

    // ---------- 追加式实装事件 ----------
    @GetMapping("/install-events")
    public ApiResult<List<Map<String, Object>>> events() {
        return ApiResult.ok(ingestion.listEvents());
    }

    @PostMapping("/install-events")
    public ApiResult<Map<String, Object>> appendEvent(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(ingestion.appendInstallEvent(body));
    }

    // ---------- 替代规则与约束 ----------
    @GetMapping("/rules")
    public ApiResult<List<Map<String, Object>>> rules() {
        return ApiResult.ok(ingestion.listRules());
    }

    @GetMapping("/rules/{id}/lineage")
    public ApiResult<List<Map<String, Object>>> ruleLineage(@PathVariable long id) {
        return ApiResult.ok(query.lineageOfRule(id));
    }

    @PostMapping("/rules/{id}/retire")
    public ApiResult<Map<String, Object>> retireRule(@PathVariable long id,
                                                     @RequestBody Map<String, String> body) {
        return ApiResult.ok(ingestion.retireRule(id,
                body.getOrDefault("reason", "供应商收回替代规则")));
    }

    @GetMapping("/constraints")
    public ApiResult<List<Map<String, Object>>> constraints(
            @RequestParam(required = false) Long ruleId) {
        return ApiResult.ok(ingestion.listConstraints(ruleId));
    }
}
