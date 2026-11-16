package com.mdm.platform.web;

import com.mdm.platform.service.ReliabilityService;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 替代件可靠性观察 API：
 *   - 对比项目 / 观察段 / 故障工单（只追加）
 *   - 版本化风险报告（固定窗口比例 + Kaplan-Meier + 删失检验 + 不可比较层）
 */
@RestController
@RequestMapping("/reliability")
public class ReliabilityController {

    private final ReliabilityService reliability;

    public ReliabilityController(ReliabilityService reliability) {
        this.reliability = reliability;
    }

    @GetMapping("/programs")
    public ApiResult<List<Map<String, Object>>> programs() {
        return ApiResult.ok(reliability.listPrograms());
    }

    @GetMapping("/segments")
    public ApiResult<List<Map<String, Object>>> segments(@RequestParam(required = false) Long programId) {
        return ApiResult.ok(reliability.listSegments(programId));
    }

    @GetMapping("/tickets")
    public ApiResult<List<Map<String, Object>>> tickets(@RequestParam(required = false) Long programId) {
        return ApiResult.ok(reliability.listTickets(programId));
    }

    @PostMapping("/tickets")
    public ApiResult<Map<String, Object>> appendTicket(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(reliability.appendTicket(body));
    }

    @GetMapping("/reports")
    public ApiResult<List<Map<String, Object>>> reports(@RequestParam(required = false) Long programId) {
        return ApiResult.ok(reliability.listReports(programId));
    }

    @GetMapping("/reports/{id}")
    public ApiResult<Map<String, Object>> report(@PathVariable long id) {
        return ApiResult.ok(reliability.reportDetail(id));
    }

    @PostMapping("/reports/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        long programId = Long.parseLong(String.valueOf(body.get("programId")));
        OffsetDateTime cutoff = OffsetDateTime.parse(String.valueOf(body.get("cutoffAt")));
        OffsetDateTime asOf = body.get("asOfReceived") == null
                || String.valueOf(body.get("asOfReceived")).isBlank()
                ? null : OffsetDateTime.parse(String.valueOf(body.get("asOfReceived")));
        return ApiResult.ok(reliability.generateReport(new ReliabilityService.GenerateRequest(
                programId, cutoff, asOf, com.mdm.platform.security.CurrentUser.username())));
    }

    @PostMapping("/reports/{id}/refresh")
    public ApiResult<Map<String, Object>> refresh(@PathVariable long id) {
        return ApiResult.ok(reliability.refreshReport(id));
    }
}
