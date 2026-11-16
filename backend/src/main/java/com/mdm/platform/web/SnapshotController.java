package com.mdm.platform.web;

import com.mdm.platform.service.CompatibilityService;
import com.mdm.platform.service.ReleaseGateService;
import com.mdm.platform.service.SnapshotService;
import com.mdm.platform.service.QueryService;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class SnapshotController {

    private final SnapshotService snapshots;
    private final CompatibilityService compatibility;
    private final ReleaseGateService releaseGate;
    private final QueryService query;

    public SnapshotController(SnapshotService snapshots, CompatibilityService compatibility,
                              ReleaseGateService releaseGate, QueryService query) {
        this.snapshots = snapshots;
        this.compatibility = compatibility;
        this.releaseGate = releaseGate;
        this.query = query;
    }

    // ---------- 快照编译与版本化查询 ----------
    @GetMapping("/snapshots")
    public ApiResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(query.listSnapshots(equipmentId, status));
    }

    @GetMapping("/snapshots/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResult.ok(query.snapshotDetail(id));
    }

    @PostMapping("/snapshots/compile")
    public ApiResult<Map<String, Object>> compile(@RequestBody Map<String, Object> body) {
        long equipmentId = Long.parseLong(String.valueOf(body.get("equipmentId")));
        OffsetDateTime pit = OffsetDateTime.parse(String.valueOf(body.get("pointInTime")));
        OffsetDateTime asOf = body.get("asOfReceived") == null || String.valueOf(body.get("asOfReceived")).isBlank()
                ? null : OffsetDateTime.parse(String.valueOf(body.get("asOfReceived")));
        return ApiResult.ok(snapshots.compile(
                new SnapshotService.CompileRequest(equipmentId, pit, asOf,
                        com.mdm.platform.security.CurrentUser.username())));
    }

    @GetMapping("/equipment/{equipmentId}/as-of")
    public ApiResult<Map<String, Object>> asOf(@PathVariable long equipmentId,
                                               @RequestParam String pointInTime) {
        return ApiResult.ok(query.asOfQuery(equipmentId, OffsetDateTime.parse(pointInTime)));
    }

    // ---------- 兼容事实求值 ----------
    @PostMapping("/compatibility/evaluate")
    public ApiResult<Map<String, Object>> evaluate(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(compatibility.evaluate(new CompatibilityService.EvaluationRequest(
                Long.parseLong(String.valueOf(body.get("snapshotId"))),
                Long.parseLong(String.valueOf(body.get("mountPositionId"))),
                Long.parseLong(String.valueOf(body.get("candidateModelId"))))));
    }

    @PostMapping("/snapshots/{id}/evaluate-all")
    public ApiResult<List<Map<String, Object>>> evaluateAll(
            @PathVariable long id, @RequestBody Map<String, List<Long>> body) {
        List<Long> candidates = body.getOrDefault("candidateModelIds", List.of());
        return ApiResult.ok(compatibility.evaluateSnapshot(id, candidates));
    }

    @GetMapping("/snapshots/{id}/facts")
    public ApiResult<List<Map<String, Object>>> facts(@PathVariable long id) {
        return ApiResult.ok(compatibility.listBySnapshot(id));
    }

    @GetMapping("/compatibility/facts-all")
    public ApiResult<List<Map<String, Object>>> allFacts() {
        return ApiResult.ok(compatibility.listAll());
    }

    // ---------- 发布闸门 ----------
    @PostMapping("/facts/{id}/gate")
    public ApiResult<Map<String, Object>> gate(@PathVariable long id,
                                               @RequestBody Map<String, String> body) {
        return ApiResult.ok(releaseGate.transition(id,
                body.getOrDefault("action", "publish"),
                body.getOrDefault("reason", "")));
    }

    @GetMapping("/facts/public")
    public ApiResult<List<Map<String, Object>>> publicFacts() {
        return ApiResult.ok(releaseGate.publicFacts());
    }

    @GetMapping("/release/audit-logs")
    public ApiResult<List<Map<String, Object>>> auditLogs() {
        return ApiResult.ok(releaseGate.auditLogs());
    }
}
