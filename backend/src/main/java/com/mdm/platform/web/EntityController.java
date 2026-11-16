package com.mdm.platform.web;

import com.mdm.platform.service.EntityDisambiguationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/entities")
public class EntityController {

    private final EntityDisambiguationService entities;

    public EntityController(EntityDisambiguationService entities) {
        this.entities = entities;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String keyword) {
        return ApiResult.ok(entities.listEntities(entityType, keyword));
    }

    @GetMapping("/alias-candidates")
    public ApiResult<List<Map<String, Object>>> candidates(
            @RequestParam(required = false) String decision) {
        return ApiResult.ok(entities.aliasCandidates(decision));
    }

    @PostMapping("/alias-candidates/{id}/decide")
    public ApiResult<Map<String, Object>> decide(@PathVariable long id,
                                                 @RequestBody Map<String, String> body) {
        return ApiResult.ok(entities.decideAlias(id,
                body.getOrDefault("decision", "KEEP_DISTINCT"),
                body.getOrDefault("note", "人工裁定保持分立")));
    }

    @GetMapping("/conflicts")
    public ApiResult<List<Map<String, Object>>> conflicts() {
        return ApiResult.ok(entities.openConflicts());
    }

    @PostMapping("/conflicts/{id}/adjudicate")
    public ApiResult<Map<String, Object>> adjudicate(@PathVariable long id,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResult.ok(entities.adjudicateConflict(id,
                String.valueOf(body.getOrDefault("resolutionNote", "凭证裁定完成")),
                (Boolean) body.get("keepFirstVoucher")));
    }
}
