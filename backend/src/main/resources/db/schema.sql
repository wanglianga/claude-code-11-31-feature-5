-- =====================================================================
-- 维修配置与备件兼容主数据血缘核验平台 —— PostgreSQL DDL
-- 设计原则：
--   1. 版本化快照 + 追加式实装事件，绝不覆盖历史
--   2. 五类实体显式区分编码，同名不自动合并
--   3. 所有事实带状态闸门（草稿/已验证/待复核/已废止）
--   4. 冲突与冻结显式落库，需要凭证裁定
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 平台账号
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    display_name  VARCHAR(128) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    role          VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN','ENGINEER','VIEWER')),
    team          VARCHAR(64),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 1. 资料接入契约库：登记每类资料的字段、来源、版本与生效边界
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingestion_contract (
    id              BIGSERIAL PRIMARY KEY,
    contract_code   VARCHAR(64)  NOT NULL UNIQUE,             -- 契约编号
    contract_name   VARCHAR(200) NOT NULL,
    source_team     VARCHAR(128) NOT NULL,                     -- 维护团队
    source_system   VARCHAR(128),                             -- 来源系统
    schema_version  VARCHAR(32)  NOT NULL,                     -- 契约版本号
    effective_from  TIMESTAMPTZ  NOT NULL,
    effective_to    TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW')),
    field_spec      JSONB        NOT NULL DEFAULT '[]'::jsonb,-- 字段契约（名称/类型/必填/含义）
    trust_score     NUMERIC(5,2) NOT NULL DEFAULT 50
                    CHECK (trust_score >= 0 AND trust_score <= 100), -- 来源可信度
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 契约版本索引（同一契约编号的历次版本全部保留）
CREATE TABLE IF NOT EXISTS ingestion_contract_version (
    id              BIGSERIAL PRIMARY KEY,
    contract_code   VARCHAR(64)  NOT NULL,
    schema_version  VARCHAR(32)  NOT NULL,
    source_team     VARCHAR(128) NOT NULL,
    source_ref      VARCHAR(256),                             -- 来源文件/报文标识
    payload_digest  VARCHAR(128),                             -- 来源文件指纹（长期保留）
    field_spec      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    trust_score     NUMERIC(5,2) NOT NULL,
    registered_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (contract_code, schema_version)
);

-- ---------------------------------------------------------------------
-- 2. 实体消歧器：五类实体分开建模
--    PART_MODEL 部件型号 / PHYSICAL_PART 实物序列号 / MOUNT_POSITION 装配位置
--    EQUIPMENT 设备序列号 / SUPPLIER 供应商编码
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS master_entity (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(20) NOT NULL
                    CHECK (entity_type IN
                    ('PART_MODEL','PHYSICAL_PART','MOUNT_POSITION','EQUIPMENT','SUPPLIER')),
    entity_code     VARCHAR(96) NOT NULL,                    -- 业务编码（唯一业务身份）
    display_name    VARCHAR(256) NOT NULL,                   -- 名称仅作展示，不参与合并
    supplier_code   VARCHAR(96),                             -- 供应商编码（仅部件/实物引用）
    spec_payload    JSONB NOT NULL DEFAULT '{}'::jsonb,      -- 协议/尺寸/电源等规格
    status          VARCHAR(16) NOT NULL DEFAULT 'VERIFIED'
                    CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW','FROZEN')),
    -- 消歧标记：人工确认与某实体是两个不同对象（防止名称相似自动合并）
    distinct_from   BIGINT[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_type, entity_code)
);
CREATE INDEX IF NOT EXISTS idx_entity_name ON master_entity (entity_type, display_name);

-- 同名候选（实体消歧工作队列，绝不自动合并）
CREATE TABLE IF NOT EXISTS entity_alias_candidate (
    id                  BIGSERIAL PRIMARY KEY,
    entity_type         VARCHAR(20) NOT NULL,
    name_text           VARCHAR(256) NOT NULL,
    candidate_entity_id BIGINT NOT NULL REFERENCES master_entity(id),
    target_entity_id    BIGINT REFERENCES master_entity(id),
    decision            VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (decision IN ('PENDING','KEEP_DISTINCT','MERGED')),
    decided_by          VARCHAR(64),
    decided_at          TIMESTAMPTZ,
    note                TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 3. 图号（工程图号）主数据与修订
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS drawing (
    id              BIGSERIAL PRIMARY KEY,
    drawing_no      VARCHAR(96) NOT NULL UNIQUE,             -- 图号
    title           VARCHAR(256) NOT NULL,
    part_model_id   BIGINT REFERENCES master_entity(id),     -- 图号对应的部件型号
    current_revision VARCHAR(16),
    status          VARCHAR(16) NOT NULL DEFAULT 'VERIFIED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS drawing_revision (
    id              BIGSERIAL PRIMARY KEY,
    drawing_id      BIGINT NOT NULL REFERENCES drawing(id),
    revision        VARCHAR(16) NOT NULL,
    -- 序列号生效边界（跨修订序列号区间，可重叠 -> 编译器需产生冲突事实）
    serial_range_from VARCHAR(64),
    serial_range_to   VARCHAR(64),
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ,
    source_contract_id BIGINT REFERENCES ingestion_contract(id),
    source_ref      VARCHAR(256),
    payload_digest  VARCHAR(128),
    change_note     TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'VERIFIED'
                    CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (drawing_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_drawing_rev_boundary
    ON drawing_revision (drawing_id, serial_range_from, serial_range_to);

-- ---------------------------------------------------------------------
-- 4. 追加式实装事件（拆装凭证）——只追加，不更新不删除
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS install_event (
    id                  BIGSERIAL PRIMARY KEY,
    event_code          VARCHAR(64) NOT NULL UNIQUE,
    equipment_id        BIGINT NOT NULL REFERENCES master_entity(id), -- 设备序列号
    mount_position_id   BIGINT NOT NULL REFERENCES master_entity(id), -- 装配位置
    physical_part_id    BIGINT NOT NULL REFERENCES master_entity(id), -- 实物序列号
    part_model_id       BIGINT NOT NULL REFERENCES master_entity(id), -- 部件型号
    event_type          VARCHAR(16) NOT NULL CHECK (event_type IN ('INSTALL','REMOVE')),
    event_time          TIMESTAMPTZ NOT NULL,                    -- 实际拆装时间
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),      -- 平台接收时间（迟到凭证靠它识别）
    source_contract_id  BIGINT REFERENCES ingestion_contract(id),
    source_ref          VARCHAR(256),
    payload_digest      VARCHAR(128),
    trust_score         NUMERIC(5,2) NOT NULL DEFAULT 50,
    -- 裁定状态：正常 / 冻结待裁定 / 已裁定
    adjudication        VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
                        CHECK (adjudication IN ('NORMAL','FROZEN','ADJUDICATED')),
    adjudication_note   TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_equip_time ON install_event (equipment_id, event_time);
CREATE INDEX IF NOT EXISTS idx_event_phys_time ON install_event (physical_part_id, event_time);
CREATE INDEX IF NOT EXISTS idx_event_received ON install_event (received_at);

-- ---------------------------------------------------------------------
-- 5. 出厂配置记录
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS factory_record (
    id                  BIGSERIAL PRIMARY KEY,
    equipment_id        BIGINT NOT NULL REFERENCES master_entity(id),
    mount_position_id   BIGINT NOT NULL REFERENCES master_entity(id),
    part_model_id       BIGINT NOT NULL REFERENCES master_entity(id),
    physical_part_id    BIGINT REFERENCES master_entity(id),
    factory_time        TIMESTAMPTZ NOT NULL,
    source_contract_id  BIGINT REFERENCES ingestion_contract(id),
    source_ref          VARCHAR(256),
    payload_digest      VARCHAR(128),
    trust_score         NUMERIC(5,2) NOT NULL DEFAULT 80,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_factory_equip ON factory_record (equipment_id);

-- ---------------------------------------------------------------------
-- 6. 检验记录
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_record (
    id                  BIGSERIAL PRIMARY KEY,
    inspection_code     VARCHAR(64) NOT NULL UNIQUE,
    equipment_id        BIGINT REFERENCES master_entity(id),
    part_model_id       BIGINT REFERENCES master_entity(id),
    physical_part_id    BIGINT REFERENCES master_entity(id),
    test_item           VARCHAR(128) NOT NULL,
    result              VARCHAR(16) NOT NULL CHECK (result IN ('PASS','FAIL','CONDITIONAL')),
    inspected_at        TIMESTAMPTZ NOT NULL,
    source_contract_id  BIGINT REFERENCES ingestion_contract(id),
    source_ref          VARCHAR(256),
    payload_digest      VARCHAR(128),
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_inspection_part ON inspection_record (part_model_id, physical_part_id);

-- ---------------------------------------------------------------------
-- 7. 替代规则与约束（规则血缘解析器的输入）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS substitution_rule (
    id                  BIGSERIAL PRIMARY KEY,
    rule_code           VARCHAR(64) NOT NULL UNIQUE,
    rule_version        VARCHAR(16) NOT NULL,
    from_part_model_id  BIGINT NOT NULL REFERENCES master_entity(id), -- 被替代件
    to_part_model_id    BIGINT NOT NULL REFERENCES master_entity(id), -- 替代件（方向明确）
    direction_note      VARCHAR(256),                                 -- 替代方向说明
    co_substitution     JSONB NOT NULL DEFAULT '[]'::jsonb,           -- 联换条件（必须同时更换的型号）
    effective_from      TIMESTAMPTZ NOT NULL,
    effective_to        TIMESTAMPTZ,
    supplier_id         BIGINT REFERENCES master_entity(id),          -- 规则发布供应商
    drawing_revision_id BIGINT REFERENCES drawing_revision(id),       -- 连回图号生效版本
    test_report_id      BIGINT REFERENCES inspection_record(id),      -- 连回测试报告
    source_contract_id  BIGINT REFERENCES ingestion_contract(id),
    source_ref          VARCHAR(256),
    status              VARCHAR(16) NOT NULL DEFAULT 'VERIFIED'
                        CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW')),
    retired_at          TIMESTAMPTZ,                                  -- 供应商收回时间
    retire_reason       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rule_code, rule_version)
);
CREATE INDEX IF NOT EXISTS idx_rule_parts ON substitution_rule (from_part_model_id, to_part_model_id);

-- 协议 / 尺寸 / 电源等硬约束
CREATE TABLE IF NOT EXISTS rule_constraint (
    id                  BIGSERIAL PRIMARY KEY,
    rule_id             BIGINT NOT NULL REFERENCES substitution_rule(id),
    constraint_type     VARCHAR(24) NOT NULL
                        CHECK (constraint_type IN ('PROTOCOL','DIMENSION','POWER','INTERFACE','ENVIRONMENT')),
    attribute_name      VARCHAR(96) NOT NULL,
    expected_value      VARCHAR(256) NOT NULL,
    compare_op          VARCHAR(8) NOT NULL DEFAULT 'EQ'
                        CHECK (compare_op IN ('EQ','NE','GT','GE','LT','LE','IN','RANGE')),
    actual_source       VARCHAR(128),                                 -- 取值来源（型号规格字段路径）
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_constraint_rule ON rule_constraint (rule_id);

-- ---------------------------------------------------------------------
-- 8. 配置快照（不可变，版本化；位置级）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS config_snapshot (
    id                  BIGSERIAL PRIMARY KEY,
    snapshot_no         VARCHAR(64) NOT NULL UNIQUE,
    equipment_id        BIGINT NOT NULL REFERENCES master_entity(id),
    point_in_time       TIMESTAMPTZ NOT NULL,                        -- 维修时点
    compiled_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_event_ids    BIGINT[] NOT NULL DEFAULT '{}',              -- 重放所用事件（版本索引）
    source_factory_ids  BIGINT[] NOT NULL DEFAULT '{}',
    min_trust_score     NUMERIC(5,2),
    has_conflict        BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_detail     JSONB NOT NULL DEFAULT '[]'::jsonb,
    replay_digest       VARCHAR(128),                                -- 重放计算指纹
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW','FROZEN')),
    created_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_snapshot_equip_time ON config_snapshot (equipment_id, point_in_time);

-- 快照内每个装配位置的事实行（不可变）
CREATE TABLE IF NOT EXISTS snapshot_position (
    id                  BIGSERIAL PRIMARY KEY,
    snapshot_id         BIGINT NOT NULL REFERENCES config_snapshot(id),
    mount_position_id   BIGINT NOT NULL REFERENCES master_entity(id),
    part_model_id       BIGINT REFERENCES master_entity(id),
    physical_part_id    BIGINT REFERENCES master_entity(id),
    basis               VARCHAR(24) NOT NULL
                        CHECK (basis IN ('FACTORY','INSTALL_EVENT','REMOVE_EMPTY','CONFLICT')),
    basis_event_id      BIGINT REFERENCES install_event(id),
    trust_score         NUMERIC(5,2),
    position_state      VARCHAR(16) NOT NULL DEFAULT 'RESOLVED'
                        CHECK (position_state IN ('RESOLVED','CONFLICT','FROZEN','VACANT')),
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (snapshot_id, mount_position_id)
);

-- 冲突事实（序列号区间重叠 / 实物同时出现在两台设备）
CREATE TABLE IF NOT EXISTS conflict_fact (
    id                  BIGSERIAL PRIMARY KEY,
    conflict_code       VARCHAR(64) NOT NULL UNIQUE,
    conflict_type       VARCHAR(32) NOT NULL
                        CHECK (conflict_type IN
                        ('DRAWING_RANGE_OVERLAP','PHYSICAL_PART_DOUBLE_MOUNT','SOURCE_CLAIM_CONFLICT')),
    equipment_id        BIGINT REFERENCES master_entity(id),
    snapshot_id         BIGINT REFERENCES config_snapshot(id),
    related_entity_ids  BIGINT[] NOT NULL DEFAULT '{}',
    related_event_ids   BIGINT[] NOT NULL DEFAULT '{}',
    description         TEXT NOT NULL,
    resolution_status   VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (resolution_status IN ('OPEN','ADJUDICATED','SUPERSEDED')),
    resolution_note     TEXT,
    resolved_by         VARCHAR(64),
    resolved_at         TIMESTAMPTZ,
    blocks_release      BOOLEAN NOT NULL DEFAULT TRUE,              -- 是否阻止兼容发布
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 9. 兼容性事实（求值器输出，带最小证据链，只追加结论版本）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compatibility_fact (
    id                  BIGSERIAL PRIMARY KEY,
    fact_no             VARCHAR(64) NOT NULL UNIQUE,
    snapshot_id         BIGINT NOT NULL REFERENCES config_snapshot(id),
    mount_position_id   BIGINT NOT NULL REFERENCES master_entity(id),
    candidate_model_id  BIGINT NOT NULL REFERENCES master_entity(id), -- 被评估的候选备件型号
    installed_model_id  BIGINT REFERENCES master_entity(id),
    verdict             VARCHAR(32) NOT NULL
                        CHECK (verdict IN ('PASS','FAIL','INSUFFICIENT_EVIDENCE')),
    rule_id             BIGINT REFERENCES substitution_rule(id),     -- 引用的规则版本
    rule_version_locked VARCHAR(16),                                 -- 签发时锁定的规则版本
    evidence_chain      JSONB NOT NULL DEFAULT '[]'::jsonb,         -- 最小证据链
    failed_constraints  JSONB NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW')),
    supersedes_fact_id  BIGINT REFERENCES compatibility_fact(id),    -- 更正链
    correction_note     TEXT,
    issued_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fact_snapshot ON compatibility_fact (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_fact_rule ON compatibility_fact (rule_id);

-- ---------------------------------------------------------------------
-- 10. 质量事件（冲突、撤回、冻结、更正统一登记）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_event (
    id                  BIGSERIAL PRIMARY KEY,
    event_no            VARCHAR(64) NOT NULL UNIQUE,
    event_type          VARCHAR(32) NOT NULL
                        CHECK (event_type IN
                        ('CONFLICT_BLOCK','ENTITY_FREEZE','RULE_RETIRE','FACT_CORRECTION',
                         'LATE_VOUCHER','SNAPSHOT_REVIEW','RELEASE_GATE_BLOCK')),
    severity            VARCHAR(8) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    related_entity_type VARCHAR(32),
    related_entity_id   BIGINT,
    related_snapshot_id BIGINT REFERENCES config_snapshot(id),
    related_fact_id     BIGINT REFERENCES compatibility_fact(id),
    description         TEXT NOT NULL,
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    handling_status     VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (handling_status IN ('OPEN','IN_PROGRESS','CLOSED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_quality_status ON quality_event (handling_status);

-- ---------------------------------------------------------------------
-- 11. 影响分析结果
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS impact_analysis (
    id                  BIGSERIAL PRIMARY KEY,
    analysis_no         VARCHAR(64) NOT NULL UNIQUE,
    trigger_type        VARCHAR(32) NOT NULL
                        CHECK (trigger_type IN ('RULE_CHANGE','DRAWING_CHANGE','INSTALL_EVENT_CHANGE','ENTITY_CHANGE')),
    trigger_ref         VARCHAR(128) NOT NULL,
    affected_snapshot_ids  BIGINT[] NOT NULL DEFAULT '{}',
    affected_fact_ids      BIGINT[] NOT NULL DEFAULT '{}',
    affected_dataset_refs  JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 下游数据集
    summary             TEXT NOT NULL,
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 发布闸门审计日志
CREATE TABLE IF NOT EXISTS release_audit_log (
    id                  BIGSERIAL PRIMARY KEY,
    fact_id             BIGINT REFERENCES compatibility_fact(id),
    snapshot_id         BIGINT REFERENCES config_snapshot(id),
    action              VARCHAR(32) NOT NULL,
    from_status         VARCHAR(16),
    to_status           VARCHAR(16),
    operator            VARCHAR(64),
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- 12. 可靠性观察段 / 故障工单 / 版本化风险报告（替代件批准后的现场对比）
--     设计原则：
--       1. 观察段由追加式装拆事件派生，保留装拆、工况、首发故障与删失原因
--       2. 同一观察段只取首发故障；重复报修以 recurrence 留档但不重复计数
--       3. 报告按截止点 + 已知接收边界版本化：迟到工单不改写旧报告，只生成新版本
--       4. 固定随访窗口比例与 Kaplan-Meier 生存曲线并列输出，口径与删失规则显式留痕
-- =====================================================================

-- 拆卸原因：NO_FAULT_SPARE_RETURN 无故障返库 / UNKNOWN 原因不明 / TRANSFER 由后续安装推导
ALTER TABLE install_event ADD COLUMN IF NOT EXISTS remove_reason VARCHAR(32);

-- 对比项目：原件 vs 替代件 + 同一故障定义 + 指定随访期
CREATE TABLE IF NOT EXISTS reliability_program (
    id                     BIGSERIAL PRIMARY KEY,
    program_code           VARCHAR(64)  NOT NULL UNIQUE,
    program_name           VARCHAR(200) NOT NULL,
    original_model_id      BIGINT NOT NULL REFERENCES master_entity(id),
    replacement_model_id   BIGINT NOT NULL REFERENCES master_entity(id),
    failure_criteria       JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {"codes":[...],"definition":"..."}
    followup_days          INT NOT NULL DEFAULT 180 CHECK (followup_days > 0),
    approved_at            TIMESTAMPTZ,
    status                 VARCHAR(16) NOT NULL DEFAULT 'VERIFIED'
                           CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 观察段（派生数据：每次按截止点/接收边界重算，报告另行快照留痕）
CREATE TABLE IF NOT EXISTS reliability_segment (
    id                      BIGSERIAL PRIMARY KEY,
    segment_code            VARCHAR(96) NOT NULL,              -- 段编码（按截止点/接收边界重算，可重复出现）
    program_id              BIGINT NOT NULL REFERENCES reliability_program(id),
    part_model_id           BIGINT NOT NULL REFERENCES master_entity(id),
    part_role               VARCHAR(16) NOT NULL CHECK (part_role IN ('ORIGINAL','REPLACEMENT')),
    physical_part_id        BIGINT NOT NULL REFERENCES master_entity(id),
    equipment_id            BIGINT NOT NULL REFERENCES master_entity(id),
    mount_position_id       BIGINT NOT NULL REFERENCES master_entity(id),
    environment             VARCHAR(32) NOT NULL,              -- 工况：HIGH_TEMP/HUMID/STANDARD/...
    install_event_id        BIGINT REFERENCES install_event(id),
    installed_at            TIMESTAMPTZ NOT NULL,
    ended_at                TIMESTAMPTZ,
    end_kind                VARCHAR(16) NOT NULL CHECK (end_kind IN ('FAILURE','CENSORED','OPEN')),
    censor_reason           VARCHAR(32),                       -- END_OF_CUTOFF/TRANSFER/REMOVED_NO_FAULT/UNKNOWN_REMOVAL
    first_failure_ticket_id BIGINT,
    observed_days           DOUBLE PRECISION NOT NULL,
    cutoff_at               TIMESTAMPTZ NOT NULL,
    as_of_received          TIMESTAMPTZ,
    compiled_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail                  JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_rel_segment_group
    ON reliability_segment (program_id, environment, part_role);
CREATE INDEX IF NOT EXISTS idx_rel_segment_snapshot
    ON reliability_segment (program_id, cutoff_at, as_of_received);

-- 故障工单（只追加；事件时间与接收时间分离，迟到工单靠接收边界识别）
CREATE TABLE IF NOT EXISTS failure_ticket (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_no           VARCHAR(64) NOT NULL UNIQUE,
    program_id          BIGINT REFERENCES reliability_program(id),
    physical_part_id    BIGINT NOT NULL REFERENCES master_entity(id),
    part_model_id       BIGINT NOT NULL REFERENCES master_entity(id),
    equipment_id        BIGINT REFERENCES master_entity(id),
    occurred_at         TIMESTAMPTZ NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL,
    fault_code          VARCHAR(32) NOT NULL,
    fault_desc          VARCHAR(255),
    cause_category      VARCHAR(64),
    source_ref          VARCHAR(256),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_failure_ticket_phys ON failure_ticket (physical_part_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_failure_ticket_received ON failure_ticket (received_at);

-- 版本化报告头：cf. 迟到工单 -> 旧报告截止点保留，新版本另发
CREATE TABLE IF NOT EXISTS reliability_report (
    id                   BIGSERIAL PRIMARY KEY,
    report_no            VARCHAR(96) NOT NULL UNIQUE,
    program_id           BIGINT NOT NULL REFERENCES reliability_program(id),
    version              INT NOT NULL,
    cutoff_at            TIMESTAMPTZ NOT NULL,
    as_of_received       TIMESTAMPTZ,
    followup_days        INT NOT NULL,
    supersedes_report_id BIGINT REFERENCES reliability_report(id),
    status               VARCHAR(16) NOT NULL DEFAULT 'VERIFIED'
                         CHECK (status IN ('DRAFT','VERIFIED','RETIRED','REVIEW')),
    summary              JSONB NOT NULL DEFAULT '{}'::jsonb,
    generated_by         VARCHAR(64),
    generated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (program_id, version)
);

-- 模型 × 环境 分层结果：固定窗口比例 + KM 风险 + 观察时间 + 删失构成
CREATE TABLE IF NOT EXISTS reliability_report_stratum (
    id                      BIGSERIAL PRIMARY KEY,
    report_id               BIGINT NOT NULL REFERENCES reliability_report(id) ON DELETE CASCADE,
    part_role               VARCHAR(16) NOT NULL,
    part_model_id           BIGINT NOT NULL,
    model_code              VARCHAR(96) NOT NULL,
    environment             VARCHAR(32) NOT NULL,
    n_segments              INT NOT NULL,
    n_fixed_denominator     INT NOT NULL,
    n_fixed_events          INT NOT NULL,
    n_completed_window      INT NOT NULL,
    risk_fixed              DOUBLE PRECISION,
    risk_fixed_low          DOUBLE PRECISION,
    risk_fixed_high         DOUBLE PRECISION,
    person_days             DOUBLE PRECISION NOT NULL,
    median_followup_days    DOUBLE PRECISION,
    n_km_risk               INT NOT NULL,
    km_events               INT NOT NULL,
    km_censored             INT NOT NULL,
    km_survival             DOUBLE PRECISION,
    km_risk                 DOUBLE PRECISION,
    km_risk_low             DOUBLE PRECISION,
    km_risk_high            DOUBLE PRECISION,
    censor_end_of_cutoff    INT NOT NULL DEFAULT 0,
    censor_transfer         INT NOT NULL DEFAULT 0,
    censor_removed_no_fault INT NOT NULL DEFAULT 0,
    censor_unknown          INT NOT NULL DEFAULT 0,
    warnings                JSONB NOT NULL DEFAULT '[]'::jsonb,
    detail                  JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_rel_stratum_report ON reliability_report_stratum (report_id);

-- 环境内 原件 vs 替代件 风险差、log-rank 与删失机制检验
CREATE TABLE IF NOT EXISTS reliability_risk_comparison (
    id                      BIGSERIAL PRIMARY KEY,
    report_id               BIGINT NOT NULL REFERENCES reliability_report(id) ON DELETE CASCADE,
    environment             VARCHAR(32) NOT NULL,
    original_model_code     VARCHAR(96),
    replacement_model_code  VARCHAR(96),
    original_n              INT NOT NULL DEFAULT 0,
    replacement_n           INT NOT NULL DEFAULT 0,
    comparable              BOOLEAN NOT NULL DEFAULT FALSE,
    not_comparable_reason   VARCHAR(255),
    risk_diff_fixed         DOUBLE PRECISION,
    risk_diff_fixed_low     DOUBLE PRECISION,
    risk_diff_fixed_high    DOUBLE PRECISION,
    risk_diff_km            DOUBLE PRECISION,
    risk_diff_km_low        DOUBLE PRECISION,
    risk_diff_km_high       DOUBLE PRECISION,
    logrank_chi2            DOUBLE PRECISION,
    logrank_p               DOUBLE PRECISION,
    censoring_check         JSONB NOT NULL DEFAULT '{}'::jsonb,
    flags                   JSONB NOT NULL DEFAULT '[]'::jsonb,
    note                    TEXT
);
CREATE INDEX IF NOT EXISTS idx_rel_comparison_report ON reliability_risk_comparison (report_id);

-- 报告事件账（快照）：首发故障 / 删失 / 未完成 / 原因不明 / 重复报修 / 超定义范围
CREATE TABLE IF NOT EXISTS reliability_report_event (
    id              BIGSERIAL PRIMARY KEY,
    report_id       BIGINT NOT NULL REFERENCES reliability_report(id) ON DELETE CASCADE,
    segment_code    VARCHAR(96) NOT NULL,
    part_role       VARCHAR(16) NOT NULL,
    model_code      VARCHAR(96) NOT NULL,
    environment     VARCHAR(32) NOT NULL,
    physical_code   VARCHAR(96) NOT NULL,
    event_type      VARCHAR(32) NOT NULL,   -- FIRST_FAILURE/CENSORED/EXCLUDED_INCOMPLETE/EXCLUDED_UNKNOWN_REMOVAL/RECURRENCE/NOT_IN_DEFINITION/OUT_OF_SEGMENT
    event_time      TIMESTAMPTZ,
    time_days       DOUBLE PRECISION,
    ticket_no       VARCHAR(64),
    reason          VARCHAR(255),
    detail          JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_rel_event_report ON reliability_report_event (report_id);
