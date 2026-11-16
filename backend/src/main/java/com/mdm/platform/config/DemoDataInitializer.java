package com.mdm.platform.config;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.service.CompatibilityService;
import com.mdm.platform.service.ImpactAnalysisService;
import com.mdm.platform.service.IngestionService;
import com.mdm.platform.service.ReliabilityService;
import com.mdm.platform.service.ReleaseGateService;
import com.mdm.platform.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 启动初始化：
 *  1. 执行 schema.sql / seed.sql（可通过 RESET_ON_START 重建）
 *  2. 按真实服务逻辑重放样例：编译各时点快照、求值兼容事实、发布、撤回规则、做影响分析
 * 所有派生数据都经服务产生，保证任一兼容结论可在接口上回放。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final DataSource dataSource;
    private final JdbcSupport db;
    private final SnapshotService snapshotService;
    private final CompatibilityService compatibilityService;
    private final ReleaseGateService releaseGateService;
    private final IngestionService ingestionService;
    private final ImpactAnalysisService impactAnalysisService;
    private final ReliabilityService reliabilityService;

    @Value("${app.demo.auto-init:true}")
    private boolean autoInit;

    @Value("${app.demo.reset-on-start:false}")
    private boolean resetOnStart;

    public DemoDataInitializer(DataSource dataSource, JdbcSupport db,
                               SnapshotService snapshotService,
                               CompatibilityService compatibilityService,
                               ReleaseGateService releaseGateService,
                               IngestionService ingestionService,
                               ImpactAnalysisService impactAnalysisService,
                               ReliabilityService reliabilityService) {
        this.dataSource = dataSource;
        this.db = db;
        this.snapshotService = snapshotService;
        this.compatibilityService = compatibilityService;
        this.releaseGateService = releaseGateService;
        this.ingestionService = ingestionService;
        this.impactAnalysisService = impactAnalysisService;
        this.reliabilityService = reliabilityService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!autoInit) {
            log.info("自动初始化已关闭(app.demo.auto-init=false)，跳过");
            return;
        }
        long userCount = db.count("SELECT count(*) FROM information_schema.tables WHERE table_name='app_user'");
        if (userCount == 0 || resetOnStart) {
            log.info("执行 schema.sql 与 seed.sql ...");
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
                if (resetOnStart) {
                    // 清空派生与基础数据后重新灌种子
                    truncateAll(conn);
                }
                if (db.count("SELECT count(*) FROM app_user") == 0) {
                    ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed.sql"));
                }
            }
        } else {
            // 老库升级：schema.sql 全部为 IF NOT EXISTS，可在每个启动周期幂等补齐新表
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            }
        }
        // 可靠性场景种子单独成文：全新库与老库升级都在“全空”时应用一次
        if (db.count("SELECT count(*) FROM reliability_program") == 0) {
            log.info("应用可靠性对比场景种子 seed_reliability.sql ...");
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed_reliability.sql"));
            }
        }
        if (db.count("SELECT count(*) FROM config_snapshot") > 0) {
            log.info("派生样例已存在，跳过场景重放");
        } else {
            replayScenarios();
        }
        if (db.count("SELECT count(*) FROM reliability_report") == 0) {
            replayReliabilityScenarios();
        }
    }

    private void truncateAll(Connection conn) throws Exception {
        String[] tables = {"release_audit_log","impact_analysis","quality_event","compatibility_fact",
                "conflict_fact","snapshot_position","config_snapshot","rule_constraint","substitution_rule",
                "inspection_record","factory_record","install_event","drawing_revision","drawing",
                "entity_alias_candidate","master_entity","ingestion_contract_version","ingestion_contract",
                "reliability_report_event","reliability_risk_comparison","reliability_report_stratum",
                "reliability_report","reliability_segment","failure_ticket","reliability_program",
                "app_user"};
        try (var st = conn.createStatement()) {
            for (String t : tables) {
                st.execute("TRUNCATE TABLE " + t + " RESTART IDENTITY CASCADE");
            }
        }
    }

    private void replayScenarios() {
        log.info("==== 开始重放五大核验场景 ====");

        // 设备/位置/型号 ID（与 seed.sql 固定主键一致）
        final long EQ7 = 10, EQ8 = 11, EQ18 = 12;
        final long POS_SERVO_X = 21, POS_POWER_BOX = 22, POS_COOLANT = 23;
        final long MODEL_SDU_C3 = 34, MODEL_PWR_501 = 36, MODEL_PWR_502 = 37;
        final long RULE1 = 1, RULE2 = 2, RULE3 = 3;

        // ---------- 场景 B：迟到拆装凭证 ----------
        // 2024-12-01 时点编译：此时冷却泵迟到凭证(2025-03-01 才接收)尚不可见 -> 冷却位为空
        OffsetDateTime beforeLate = OffsetDateTime.parse("2024-12-01T00:00:00Z");
        var snapBeforeLate = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, beforeLate, beforeLate, "system-replay"));
        long snapBeforeLateId = ((Number) snapBeforeLate.get("id")).longValue();
        log.info("场景B-1: 迟到凭证接收前快照 {} 编译完成", snapBeforeLate.get("snapshot_no"));

        // 2025-03-02 时点编译：迟到凭证已纳入 -> 冷却位出现冷却泵；旧快照保留不改写
        OffsetDateTime afterLate = OffsetDateTime.parse("2025-03-02T00:00:00Z");
        var snapAfterLate = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, OffsetDateTime.parse("2024-12-01T00:00:00Z"), afterLate, "system-replay"));
        long snapAfterLateId = ((Number) snapAfterLate.get("id")).longValue();
        log.info("场景B-2: 迟到凭证纳入后新版本快照 {} 编译完成，旧快照保留", snapAfterLate.get("snapshot_no"));

        // ---------- 场景 E + D：伺服替代（两约束联动 PASS）并发布，随后规则撤回 ----------
        // 2024-08-01 维修时点：伺服位仍在装 A1（9月才换装 C3），在此评估 A1 -> C3 的替代兼容性
        OffsetDateTime beforeReplacement = OffsetDateTime.parse("2024-08-01T00:00:00Z");
        var snapAug = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, beforeReplacement, beforeReplacement, "system-replay"));
        long snapAugId = ((Number) snapAug.get("id")).longValue();
        var servoFact = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapAugId, POS_SERVO_X, MODEL_SDU_C3));
        long servoFactId = ((Number) servoFact.get("id")).longValue();
        log.info("场景E: 伺服 A1->C3 求值结论 {}", servoFact.get("verdict"));
        if ("PASS".equals(servoFact.get("verdict"))) {
            releaseGateService.transition(servoFactId, "publish", "两约束(电源+协议)联动通过，签发对外");
            log.info("场景D-1: 伺服兼容事实已发布为 VERIFIED");
        }

        // ---------- 场景 E：电源 500->501 PASS；500->502 因尺寸 3U≠2U 联动 FAIL ----------
        // 12 月快照电源仓仍在装 500，规则 2/3 已于 2024-08-15 生效
        var powerPass = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapBeforeLateId, POS_POWER_BOX, MODEL_PWR_501));
        long powerPassId = ((Number) powerPass.get("id")).longValue();
        if ("PASS".equals(powerPass.get("verdict"))) {
            releaseGateService.transition(powerPassId, "publish", "电源+尺寸联动通过");
        }
        var powerFail = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapBeforeLateId, POS_POWER_BOX, MODEL_PWR_502));
        log.info("场景E: 电源 500->501={} / 500->502={}（尺寸约束拦截）",
                powerPass.get("verdict"), powerFail.get("verdict"));

        // ---------- 场景 D：供应商撤回规则1 ----------
        ingestionService.retireRule(RULE1, "供应商通知：C3 批次 E2411 协议栈缺陷，收回替代授权");
        var ruleImpact = impactAnalysisService.analyzeRuleChange(RULE1, "RETIRE",
                "供应商收回 SUB-RULE-001，历史结论保留锁定版本并另发更正");
        log.info("场景D-2: 规则撤回影响分析完成，受影响事实 {}，更正链已生成",
                ruleImpact.get("affected_fact_ids"));

        // 撤回后对同一历史时点重新求值：历史规则在该时点仍有效 -> PASS 但标注已撤回
        var reEval = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapAugId, POS_SERVO_X, MODEL_SDU_C3));
        log.info("场景D-3: 撤回后历史时点重评结论 {}（锁定规则版本 {}）",
                reEval.get("verdict"), reEval.get("rule_version_locked"));

        // ---------- 场景 A：跨修订序列号区间重叠 -> 冲突事实阻止发布 ----------
        var conflictSnap = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ18, OffsetDateTime.parse("2024-07-15T00:00:00Z"), null, "system-replay"));
        long conflictSnapId = ((Number) conflictSnap.get("id")).longValue();
        log.info("场景A: 设备0018 快照 has_conflict={} status={}",
                conflictSnap.get("has_conflict"), conflictSnap.get("status"));
        // 试图在冲突快照上发布结论 -> 闸门必须拦截（捕获异常留痕，不中断初始化）
        try {
            var blocked = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                    conflictSnapId, 20, MODEL_SDU_C3));
            releaseGateService.transition(((Number) blocked.get("id")).longValue(), "publish",
                    "尝试在冲突快照发布");
            log.warn("场景A: 闸门未拦截，不符合预期");
        } catch (IllegalStateException ex) {
            log.info("场景A: 发布闸门正确拦截 -> {}", ex.getMessage());
        }

        // ---------- 场景：同一实物重叠时间出现在两台设备 -> 冻结 ----------
        // EQ8 的编译会触发 PP-PWR-0001 双重安装检测
        var eq8Snap = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ8, OffsetDateTime.parse("2024-06-01T00:00:00Z"), null, "system-replay"));
        log.info("实体冻结: 设备0008 快照 status={}，双重安装冲突已登记", eq8Snap.get("status"));

        // 迟到凭证事件影响分析（场景B 配套）
        impactAnalysisService.analyzeInstallEventChange(4L);
        // 图号修订影响分析（场景A 配套）
        impactAnalysisService.analyzeDrawingChange(2L);

        replayReliabilityScenarios();

        log.info("==== 五大场景重放完成，共生成快照 {} 个，兼容事实 {} 条，质量事件 {} 条，可靠性报告 {} 份 ====",
                db.count("SELECT count(*) FROM config_snapshot"),
                db.count("SELECT count(*) FROM compatibility_fact"),
                db.count("SELECT count(*) FROM quality_event"),
                db.count("SELECT count(*) FROM reliability_report"));
    }

    /**
     * 替代件可靠性对比重放（与 seed.sql 第 13 节数据配套）：
     *  - v1：截止点 2025-09-30、接收边界 2025-08-01 -> 2025-06-20 的迟到工单尚未可见
     *  - v2：同一截止点、纳入迟到工单 -> 旧报告保留，另发新版本
     * 两版都覆盖：集中安装、观察不足、转装删失、重复报修、原因不明拆除、某工况缺原件（不作比较）。
     */
    private void replayReliabilityScenarios() {
        log.info("==== 重放替代件可靠性对比 ====");
        final long PROGRAM = 1;
        OffsetDateTime cutoff = OffsetDateTime.parse("2025-09-30T00:00:00Z");
        var v1 = reliabilityService.generateReport(new ReliabilityService.GenerateRequest(
                PROGRAM, cutoff, OffsetDateTime.parse("2025-08-01T00:00:00Z"), "system-replay"));
        log.info("可靠性-1: 旧接收边界报告 {} 生成（迟到工单尚未可见）",
                ((Map<?, ?>) v1.get("report")).get("report_no"));
        var v2 = reliabilityService.generateReport(new ReliabilityService.GenerateRequest(
                PROGRAM, cutoff, null, "system-replay"));
        log.info("可靠性-2: 纳入迟到工单的新版本报告 {} 生成，旧版本保留",
                ((Map<?, ?>) v2.get("report")).get("report_no"));
        var comparisons = (List<?>) v2.get("comparisons");
        for (Object c : comparisons) {
            Map<?, ?> row = (Map<?, ?>) c;
            Object censoring = row.get("censoring_check");
            Object verdict = censoring == null ? null : db.toMap(censoring).get("verdict");
            log.info("可靠性-3: 工况 {} 可比={} 风险差(固定窗口)={} 删失检验={}",
                    row.get("environment"), row.get("comparable"), row.get("risk_diff_fixed"), verdict);
        }
    }
}
