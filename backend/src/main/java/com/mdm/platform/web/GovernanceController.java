package com.mdm.platform.web;

import com.mdm.platform.service.ImpactAnalysisService;
import com.mdm.platform.service.QualityEventService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class GovernanceController {

    private final ImpactAnalysisService impact;
    private final QualityEventService qualityEvents;

    public GovernanceController(ImpactAnalysisService impact, QualityEventService qualityEvents) {
        this.impact = impact;
        this.qualityEvents = qualityEvents;
    }

    // ---------- 影响分析 ----------
    @PostMapping("/impact/rule/{ruleId}")
    public ApiResult<Map<String, Object>> ruleImpact(
            @PathVariable long ruleId, @RequestBody Map<String, String> body) {
        return ApiResult.ok(impact.analyzeRuleChange(ruleId,
                body.getOrDefault("changeKind", "UPDATE"),
                body.getOrDefault("note", "")));
    }

    @PostMapping("/impact/drawing-revision/{revisionId}")
    public ApiResult<Map<String, Object>> drawingImpact(@PathVariable long revisionId) {
        return ApiResult.ok(impact.analyzeDrawingChange(revisionId));
    }

    @PostMapping("/impact/install-event/{eventId}")
    public ApiResult<Map<String, Object>> eventImpact(@PathVariable long eventId) {
        return ApiResult.ok(impact.analyzeInstallEventChange(eventId));
    }

    @GetMapping("/impact")
    public ApiResult<List<Map<String, Object>>> listImpact() {
        return ApiResult.ok(impact.list());
    }

    // ---------- 质量事件 ----------
    @GetMapping("/quality-events")
    public ApiResult<List<Map<String, Object>>> qualityEvents(
            @RequestParam(required = false) String handlingStatus) {
        return ApiResult.ok(qualityEvents.list(handlingStatus));
    }

    @PostMapping("/quality-events/{id}/close")
    public ApiResult<Object> closeQualityEvent(@PathVariable long id,
                                               @RequestBody Map<String, String> body) {
        qualityEvents.close(id, body.getOrDefault("note", ""));
        return ApiResult.ok(Map.of("closed", id));
    }
}
