# 维修配置与备件兼容主数据血缘核验平台

面向设备制造企业的**配置主数据 / 血缘 / 兼容性事实 / 质量事件 / 版本化查询**平台。
解决"系统直接覆盖最新版 BOM，导致同一序列号在不同报表被解释成两种配置"的问题：
平台采用**版本化快照 + 追加式实装事件**，长期保留来源文件、版本索引与重放计算，
查询必须显式选择时点，已被引用的历史报告不会被静默改写。

技术栈：Vue 3 + Vite + Element Plus + Pinia ｜ Java 25 + Spring Boot 3.5 + Spring JDBC ｜ PostgreSQL 18。

---

## 一、平台能力与边界

### 交付的能力（本平台职责）
| 模块 | 职责 |
| --- | --- |
| 资料接入契约库 | 登记图号、供应商规则、实装凭证、检验数据的字段、来源、版本号、生效边界与来源可信度，并保留来源文件指纹与版本索引 |
| 实体消歧器 | 部件型号 / 实物序列号 / 装配位置 / 设备序列号 / 供应商编码**五类分开建模**；名称相似只产生消歧候选，**绝不自动合并** |
| 配置快照编译器 | 按指定维修时点重放出厂记录 + 追加式拆装凭证，汇集来源可信度，生成**位置级不可变快照**与重放指纹 |
| 规则血缘解析器 | 把替代方向、联换条件、协议/尺寸/电源约束连回图号修订、测试报告与生效版本 |
| 兼容事实求值器 | 对给定快照输出 **通过 / 不通过 / 证据不足** 及**最小证据链** |
| 影响分析器 | 规则、图号、实装记录变更后定位受影响快照、历史结论与下游数据集 |
| 发布闸门 | 草稿 / 已验证 / 待复核 / 已废止分通道对外，冲突未裁定或非 PASS 结论禁止发布 |
| 替代件可靠性观察 | 批准替代件后按**型号 × 工况**建立可比观察段：同一故障定义、同一随访窗口，并列输出固定窗口比例与 Kaplan-Meier 生存风险、风险差、删失机制检验；迟到工单另起报告版本 |

### 明确不做的事（外部业务系统职责）
不生成**领料单、维修工序、库存调拨、采购决策、工位排程**。平台只产出可回放的事实与证据。

### 四条关键业务纪律（已在代码中固化）
1. **序列号区间重叠**：两张图号都声称对同一序列号生效时，编译器产生冲突事实并冻结快照、阻止兼容发布，**不默认采用最新上传文件**。
2. **同一实物重叠时间出现在两台设备**：实体消歧器冻结相关实物/位置并要求凭证裁定，**不按接收顺序覆盖**。
3. **供应商收回替代规则**：影响分析器仅让引用该规则的快照/事实进入待复核；**已签发结论保留当时锁定版本，另发更正事实**，历史报告不改写。
4. **替代件可靠性对比**：观察段只取首发故障、重复报修不重复计数；**“尚未报修”不等于低风险**——期末在役、转装、无故障拆除按删失处理，原因不明拆除既不当作故障也不当作完成观察；某层缺一类部件时只描述、不作比较。

### 可靠性观察的统计口径（本平台如何落地）

| 问题 | 本平台的处理 |
| --- | --- |
| 只收集完成固定随访窗口的段 | 给出**固定窗口比例**（完成病例口径，分母仅含满窗口或窗口内故障的段）；直观，但会漏掉新近安装件，界面同时标注 `INSUFFICIENT_FOLLOWUP` |
| 想尽早使用新资料 | 并列出**Kaplan-Meier**：期末在役、转装、无故障拆除作为删失，风险 = 1 − S(随访窗口)，附 Greenwood/log-log 区间 |
| 删失机制是否可忽略 | 在**删失尺度**再做一次对数秩检验：p<0.05 标 `CENSORING_MAY_BE_INFORMATIVE`，提示 KM 结论需谨慎 |
| 小样本 | 固定窗口比例用 Wilson 区间、风险差用 Newcombe 混合区间，样本不足标 `SMALL_SAMPLE_WIDE_INTERVAL`，区间会很宽并如实呈现 |
| 同一观察段多次报修 | **只取首发故障**，后续报修记为 `RECURRENCE` 留档但不计数 |
| 原因不明的拆除 | 记为 `EXCLUDED_UNKNOWN_REMOVAL`：不当作故障、也不当作完成观察，单独计数并从主分析排除 |
| 迟到工单 | 旧报告**保留原截止点与接收边界**，重算生成新版本（supersedes 链），旧版本不改写 |
| 集中安装 | 同批安装占比 ≥ 50% 标 `CLUSTERED_INSTALL`，提醒批次效应与日历时间混淆 |
| 某层缺一类部件 | 该工况只输出单层描述，`comparable=false` 并写明原因，**不计算风险差** |

---

## 二、目录结构

```
31/
├── docker-compose.yml          # 一键编排：postgres + backend + frontend(nginx)
├── backend/                    # Spring Boot 工程
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   ├── application.yml
│       │   └── db/
│       │       ├── schema.sql           # 全部表结构（全部 IF NOT EXISTS，可幂等升级）
│       │       ├── seed.sql             # 五大场景基础主数据
│       │       └── seed_reliability.sql # 替代件可靠性对比场景数据
│       └── java/com/mdm/platform/
│           ├── config/         # 初始化器、Jackson/PG 序列化
│           ├── security/       # 令牌认证、角色拦截
│           ├── repository/     # JdbcSupport
│           ├── service/        # 核心模块（含 ReliabilityService / SurvivalMath）
│           └── web/            # REST 控制器
└── frontend/                   # Vue3 工程
    ├── Dockerfile
    ├── nginx.conf
    └── src/views/              # 11 个业务页面（含替代件可靠性）
```

---

## 三、本地启动

### 方式 A：Docker Compose 一键启动（推荐，需本机有 Docker）

```bash
cd /workspace/31
docker compose up --build
```

- 前端：http://localhost:8080
- 后端 API：http://localhost:18080/api
- PostgreSQL：localhost:5432（库/用户/密码 `ccdev`）
- 后端首次启动自动建表、灌入种子数据（含 `seed_reliability.sql`），并按真实服务逻辑重放五大场景与替代件可靠性对比生成派生数据。
- 对已有旧库重启时同样安全：新表以 `IF NOT EXISTS` 补齐，可靠性种子仅在 `reliability_program` 为空时应用一次。

> 重置演示数据：`RESET_ON_START=true docker compose up backend`（或在库中 drop schema 后重启）。

### 方式 B：本地运行时开发启动（本仓库实际验证方式）

前置：JDK 25、Maven 3.9、Node 22、PostgreSQL 18（库 `ccdev`，账号/密码 `ccdev`）。

```bash
# 1. 后端（默认连 127.0.0.1:5432/ccdev，账号密码 ccdev）
cd backend
mvn -DskipTests package
DB_PASSWORD=ccdev java -jar target/mdm-platform.jar
# 服务监听 http://127.0.0.1:8080/api ，启动即自动建表+种子+场景重放

# 2. 前端（新终端）
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev
# 打开 http://127.0.0.1:5173 ，vite 已配置 /api 代理到 8080
```

生产模式前端：`npm run build` 后用任意静态服务器托管 `dist/`（容器内用 nginx，配置见 `frontend/nginx.conf`）。

---

## 四、测试账号

| 角色 | 账号 | 密码 | 权限 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部读写、发布闸门、裁定 |
| 工程师 | `engineer` | `eng123` | 业务数据读写、编译、求值（无系统管理限制外的写权限） |
| 只读查询 | `viewer` | `view123` | 仅查询，写操作返回 403 |

密码以 `SHA-256("mdm-salt::" + 明文)` 存储于 `app_user.password_hash`。

---

## 五、样例场景与验证方式

样例叙事数据见 `backend/src/main/resources/db/seed.sql`（可靠性对比另见 `db/seed_reliability.sql`），派生快照/事实/冲突/影响/质量事件/可靠性报告由
`DemoDataInitializer` 启动时调用**真实服务逻辑**重放生成，因此每个结论都能在接口与页面回放。
场景 A–E 为配置/兼容主数据场景，场景 F 为替代件批准之后的现场可靠性对比。

| 场景 | 数据抓手 | 预期可观察结果 |
| --- | --- | --- |
| **A 跨修订序列号重叠** | 图号 `DRW-MOTOR-100` revA(`SN-0001~0020`) 与 revB(`SN-0015~0030`) 重叠；设备 `EQ-CNC-2024-0018`(SN-0018) | 快照 `SNP-0018-*` `has_conflict=true/status=FROZEN`，产生 `DRAWING_RANGE_OVERLAP` 冲突事实；对其发布兼容事实被闸门拦截 |
| **B 迟到拆装凭证** | 事件 `EV-INST-003`：冷却泵安装事件时间 `2024-08-20`，接收时间 `2025-03-01` | 同一设备同一维修时点编译出 `SNP-0007-20241201-v1`（接收边界前，冷却位 VACANT）与 `v2`（纳入迟到凭证，冷却位已装）；旧快照保留不改写，并产生 `LATE_VOUCHER` 质量事件 |
| **C 同名异件** | 三个"伺服驱动单元" `PM-SDU-A1`(EtherCAT/380V)、`PM-SDU-B2`(CANopen/220V)、`PM-SDU-C3` | 实体消歧页有 PENDING 候选，仅能人工 `KEEP_DISTINCT`；系统无任何自动合并入口 |
| **D 规则撤回** | 规则 `SUB-RULE-001`（A1→C3）被撤回 | 已发布事实保留 `rule_version_locked=v1`，另生成 `supersedes` 更正事实；引用快照进入 REVIEW；产生 `RULE_RETIRE`/`FACT_CORRECTION` 事件；历史时点重评仍 PASS 且标注规则已撤回 |
| **E 两约束联动** | 电源规则 `SUB-RULE-002`(电压380V+尺寸2U) / `SUB-RULE-003`(电压380V+尺寸2U，候选 `PM-PWR-502` 实际尺寸3U) | `500→501` 判 **PASS**；`500→502` 因尺寸约束失败判 **FAIL**；证据链逐项列出两条约束的 expected/actual/passed |
| **F 替代件批准后的可比观察** | 对比项目 `RELPROG-COOLPUMP`：原件 `PM-PUMP-200`、替代件 `PM-PUMP-210`，29 个观察段，工况 `HIGH_TEMP`/`STANDARD`/`HUMID`；(1) 替代件 2025-01 集中安装；(2) `PP-PUMP-3011` 2025-06 才装 → 观察不足；(3) `PP-PUMP-3004` 无故障拆除后转装、`PP-PUMP-3015` 无故障返库；(4) `PP-PUMP-3002` 首发后再次报修；(5) `PP-PUMP-3009` 原因不明拆除；(6) `FT-REL-007` 2025-06-20 故障、2025-09-05 才接收（迟到）；(7) `HUMID` 只有替代件；(8) `FT-REL-011` 为定义外噪声 | 生成两版报告：v1（接收边界 2025-08-01，9 件首发）/ v2（含迟到工单，10 件首发），v2 `supersedes` v1；`HIGH_TEMP`、`STANDARD` 可比并给出风险差，`HUMID` 标为**不可比较**；重复报修记 `RECURRENCE`、原因不明拆除单列、噪声记 `NOT_IN_DEFINITION`；分层表同时列出固定窗口比例与 KM 风险及删失构成 |

附加：实物 `PP-PUMP-0001` 在重叠时间段分别安装于设备 0007/0008，触发 `PHYSICAL_PART_DOUBLE_MOUNT`，
实物与相关位置被冻结，需在"实体消歧"页做凭证裁定。

### 建议的页面核验路径
1. 用 `admin` 登录 → **核验总览**看统计与场景导航。
2. **快照与时点查询**：查看 `SNP-0018`（冲突冻结）、`SNP-0007` 的 v1/v2 版本差异，打开详情看重放指纹与位置事实。
3. **兼容与发布闸门**：选快照+位置+候选型号求值，展开最小证据链；尝试发布冲突快照上的事实，观察被拦截。
4. **规则与血缘**：打开 `SUB-RULE-001` 血缘抽屉（图号修订/测试报告/引用事实），点"撤回"观察影响。
5. **影响分析 / 质量事件 / 实体消歧**：复核待复核清单、更正链与凭证裁定。
6. **替代件可靠性**：选 `RELPROG-COOLPUMP`，对比 v1/v2 两版报告的“故障段/原因不明拆除”差异；看分层表的固定窗口 vs KM 风险、删失构成与提示标签；在“风险差与删失检验”表确认 `HUMID` 不作比较；在事件账里找到 `RECURRENCE`（不计数）与 `EXCLUDED_UNKNOWN_REMOVAL`（排除）。

### 主要 REST 接口（均需 `Authorization: Bearer <token>`，登录接口除外）
```
POST /api/auth/login
GET  /api/contracts | /api/contracts/versions
GET  /api/entities?entityType=&keyword= | /api/entities/alias-candidates | /api/entities/conflicts
POST /api/entities/alias-candidates/{id}/decide | /api/entities/conflicts/{id}/adjudicate
GET  /api/drawings | /api/drawing-revisions | /api/inspections | /api/factory-records
GET  /api/install-events | POST /api/install-events
GET  /api/rules | /api/rules/{id}/lineage | POST /api/rules/{id}/retire
GET  /api/constraints
POST /api/snapshots/compile | GET /api/snapshots | GET /api/snapshots/{id}
GET  /api/equipment/{id}/as-of?pointInTime=
POST /api/compatibility/evaluate | GET /api/compatibility/facts-all
POST /api/facts/{id}/gate | GET /api/facts/public | GET /api/release/audit-logs
POST /api/impact/rule/{id} | /api/impact/drawing-revision/{id} | /api/impact/install-event/{id}
GET  /api/impact | GET /api/quality-events | POST /api/quality-events/{id}/close

# 替代件可靠性观察
GET  /api/reliability/programs | /api/reliability/segments | /api/reliability/tickets
POST /api/reliability/tickets                     # 追加故障工单（重复工单号 409）
GET  /api/reliability/reports?programId=          # 版本列表（含 supersedes 关系）
GET  /api/reliability/reports/{id}                # 报告 + 分层 + 风险差/删失检验 + 事件账 + 观察段
POST /api/reliability/reports/generate            # {programId, cutoffAt, asOfReceived?}
POST /api/reliability/reports/{id}/refresh        # 同截止点、最新接收边界 -> 新版本
```

---

## 六、版本化与不可篡改是怎么落地的

- `install_event` **只追加**：无 update/delete 接口，凭证号唯一；事件时间与接收时间分离，迟到凭证靠接收边界识别。
- `config_snapshot` / `snapshot_position` **只插入不更新**：每次编译产生新版本号，保存 `source_event_ids`、`source_factory_ids` 与 `replay_digest`（重放指纹）。
- 兼容性事实带 `rule_version_locked`；规则撤回后原事实不被改写，更正通过新事实 + `supersedes_fact_id` 串联。
- 所有来源记录保存 `source_ref` + `payload_digest`（文件指纹），契约版本索引长期保留。

---

## 七、实际执行过的验证结论

以下为在本隔离环境**实际执行**并通过的验证（截至交付时）：

1. `mvn -DskipTests package` 成功，产出 `backend/target/mdm-platform.jar`（Spring Boot 3.5.16 / JDK 25）。
2. `npm run build` 成功，产出 `frontend/dist`（Vite + Vue3 生产包）。
3. 后端连接本地 PostgreSQL 18 成功，自动建表、灌种子并重放五大场景：启动日志确认生成 **5 个配置快照、6 条兼容事实、5 条质量事件**，且
   - 场景 A 冲突冻结 + 发布闸门拦截成功；
   - 场景 B v1/v2 版本差异正确（v1 无冷却位证据、v2 纳入迟到凭证后出现冷却泵）；
   - 场景 E 伺服/电源 PASS、502 尺寸 FAIL；
   - 场景 D 撤回后历史结论保留锁定版本并生成更正链。
4. REST 冒烟通过：登录鉴权（错误密码 HTTP 401）、VIEWER 写操作返回 403、版本化时点查询、规则血缘、证据链 jsonb 原生输出、`facts-all`/`facts/public` 数据正确。
5. 前端 `vite build` 编译无错误，11 个页面（新增「替代件可靠性」）与路由、角色按钮禁用均已接线；开发服务器下 `/reliability` 与 `/api` 代理均返回 200。
6. 端到端断言脚本 `backend/verify_e2e.py`（仅依赖 Python 标准库，对运行中的服务做 **43 项**断言，覆盖五大场景与替代件可靠性观察）全部通过，可重复执行（首跑与重跑均为 43/43）：
   ```bash
   # 先启动后端，再运行
   python3 backend/verify_e2e.py
   ```

7. 可靠性观察的场景已按真实服务逻辑重放并核对：`HIGH_TEMP` 原件 3/6（50%），替代件在 v2 纳入迟到工单后 3/6（50%）、KM 风险 46.4%（手算寿命表一致），该层同时含期末在役 4、转装 1、无故障返库 1 三类删失；`STANDARD` 原件 2/5、替代件 1/4（另有 1 段原因不明拆除被排除）；`HUMID` 只有替代件 → 不作比较。
8. 单元测试 `SurvivalMathTest`（KM / Wilson / Newcombe / log-rank / 中位随访共 9 项）与既有测试合计 **14 项**全部通过（`mvn clean package` 全绿）。
9. 三种数据状态下均实跑通过：全新库（自动建表+两套种子）、已有数据的库重启（跳过重放、幂等补表）、以及“旧版 schema/种子”的库升级（自动补齐可靠性表并仅在全空时灌入可靠性种子），三种情况 `verify_e2e.py` 均为 43/43。

**未在本环境执行的验证**：当前容器未安装 Docker，故 `docker compose up --build` 的容器化一键启动**未在本环境实跑**；Dockerfile/compose 已按多阶段构建与官方基础镜像编写，需在具备 Docker 的环境执行确认。

---

## 八、配置项（application.yml / 环境变量）

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` | 数据库连接 |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` | 数据库账号 |
| `AUTO_INIT` | `true` | 启动自动建表+种子+场景重放 |
| `RESET_ON_START` | `false` | 置 `true` 启动时清空重建演示数据 |

> 重置全部演示数据（含可靠性报告）：
> `psql postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"` 后重启后端。
