package com.mdm.platform.web;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.service.IngestionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/contracts")
public class ContractController {

    private final IngestionService ingestion;
    private final JdbcSupport db;

    public ContractController(IngestionService ingestion, JdbcSupport db) {
        this.ingestion = ingestion;
        this.db = db;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(ingestion.listContracts());
    }

    @PostMapping
    public ApiResult<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(ingestion.registerContract(body));
    }

    @GetMapping("/versions")
    public ApiResult<List<Map<String, Object>>> versions() {
        return ApiResult.ok(db.query("SELECT * FROM ingestion_contract_version ORDER BY id"));
    }
}
