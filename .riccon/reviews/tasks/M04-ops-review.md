---
review_id: REV-M04-OPS-001
module_id: M04
task_id: TASK-0089
reviewer:
  agent: riccon/ops
  session: ses_e09ab05e8ffeRPNhYcbH4zKvzm
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
scope: "M04 设计阶段独立运维评审（ADR-0021/0022 `proposed`、ARCHITECTURE/THREAT-MODEL/契约 M04 增量）：V11 迁移、发布顺序与回滚、主密钥与端点注入、出网、可观测性、性能环境、运行手册与拆分；只读，不改任何生产源码/契约/ADR/文档正文"
created_at: "2026-11-20T08:31:04+08:00"
findings:
  - id: OPS-M04-001
    severity: P0
    status: open
    owner: riccon/architect
    title: V11 的 CHECK 收紧顺序自相矛盾，按 ADR 顺序执行迁移必失败；回滚演练 SQL 反向同样失败
    summary: "ADR-0021 §3.2 规定顺序为“加列 → 回填 RETIRED→DISABLED → 建唯一键/CHECK → 删除旧唯一键/旧 CHECK”。旧 CHECK 为 `status IN ('ACTIVE','RETIRED')`，在它仍存在时把 RETIRED 改成 DISABLED 会立即报 ERROR 3819。反向的 §10.3 演练 SQL 先 `UPDATE status='RETIRED' WHERE status IN ('DISABLED','INVALID')`，此时新 CHECK 已不允许 RETIRED，同样报 3819。唯一正确顺序是：DROP 旧 CHECK → 数据归一 → ADD 新 CHECK。该缺陷不会被 CI 冒烟发现（空库上该 UPDATE 影响 0 行，不触发校验；CI 只覆盖 fresh install，不覆盖 V10→V11 升级）。"
    evidence: "ADR-0021:182（顺序说明）、ADR-0021 §10.3 回滚 SQL；V5:99（旧 CHECK）；本地 mysql:8.4.13 实测：`ERROR 3819 Check constraint 'chk_provider_credential_status' is violated`（见正文“评审方法”探针 A/C），修正顺序后 B 步骤成功"
    fix: "改写 §3.2 的四步为：①ADD COLUMN（INSTANT）②DROP CHECK chk_provider_credential_status ③UPDATE RETIRED→DISABLED + fingerprint/masked_hint 回填 ④ADD UNIQUE KEY/INDEX/新 CHECK + DROP 旧唯一键；§10.3 回滚 SQL 同样先 DROP 新 CHECK 再改状态。V11MigrationTest 必须含一条 RETIRED 历史行与多 key 行"
  - id: OPS-M04-002
    severity: P0
    status: open
    owner: riccon/lead + riccon/architect
    title: 可观测性无落点也无任务 owner，AC-17“运维可观察降级/错误率/配额拒绝/并发 run”不可验收
    summary: "ADR-0021 §10.6 列了 9 个指标与告警，但现状：两个应用只依赖 `spring-boot-starter-actuator`，无 `micrometer-registry-prometheus`，`management.endpoints.web.exposure.include: health,info`（API 与 Worker 均如此），全仓库无一处 MeterRegistry/Counter/Timer，无 logback/结构化日志配置，无 dashboard/rule 文件。M04 任务图中 B4/B6 只写业务代码，B10 的 write_scope 是 `docs/RUNBOOK.md`、`deploy/**`、`scripts/ops/**`——**没有任何任务能改 pom.xml 或 actuator 暴露配置**。按现计划落地后 AC-17 的运维半句只能靠人工读日志，T4 gate 无法取证。"
    evidence: "apps/api/pom.xml:42、apps/worker/pom.xml:92（仅 starter-actuator）；apps/api|worker/src/main/resources/application.yml `include: health,info`；`grep -rn 'MeterRegistry|Counter|Timer' apps platform` 仅命中测试里的变量名；ADR-0021 §10.6"
    fix: "Lead 必须给一个任务（建议 B1a 或新增 B1c）加 pom 依赖 `micrometer-registry-prometheus` + 两处 application.yml 暴露 `prometheus`，并给 B10 增加“指标清单→endpoint 路径→告警阈值”的落点表与 `scripts/ops/metrics-check`（curl 断言 9 个指标名存在）；否则缩小 AC-17 需用户批准"
  - id: OPS-M04-003
    severity: P1
    status: open
    owner: riccon/architect
    title: "`model_invocation` 的 ADD CHECK 在 MySQL 8.4 只能 COPY（阻塞写 + 全表重建），不是 online DDL"
    summary: "ADR-0021 §10.1 称“加列/建索引按 MySQL 8 online DDL 执行”。实测：ADD COLUMN 可 INSTANT；ADD KEY 可 INPLACE,LOCK=NONE；但 `ADD CONSTRAINT ... CHECK` 显式 `ALGORITHM=INPLACE` 直接报 ERROR 1845，只能 COPY——即重建整表且**不允许并发 DML**。V11 要给 `model_invocation` 加 3 个 CHECK（cost_status/cost_amount/attempt_count），大表下等于一次写阻塞窗口，磁盘需约“表+索引”两倍空间。ADR 只写“由 Ops 评估锁、磁盘与维护窗口”，没有给出可执行判据。"
    evidence: "本地 mysql:8.4.13 探针 I：`ERROR 1845 (0A000): ALGORITHM=INPLACE is not supported for this operation. Try ALGORITHM=COPY.`；探针 G（ADD COLUMN INSTANT）成功；探针 H/E 显示列+索引混写时 INSTANT 不被接受"
    fix: "迁移前必须测量 `SELECT table_rows/ data_length/ index_length FROM information_schema.tables WHERE table_name='model_invocation'` 并写入 RUNBOOK 判据（例如 >200 万行或 >2 GiB 必须单独维护窗口 + 停写）；给 V11 显式写 `ALGORITHM=INSTANT`（加列）/`ALGORITHM=INPLACE, LOCK=NONE`（索引）/不写算法但单独语句（CHECK，并注明 COPY+停写）；若不愿承担 COPY，改为应用层校验 + 只保留非空/默认值"
  - id: OPS-M04-004
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops
    title: 回滚 SQL 恢复“单 key 唯一键”在多 key 真实数据上必然失败
    summary: "§10.3 演练 SQL 最后一步 `ADD UNIQUE KEY uk_provider_credential (tenant_id, provider_id, credential_type)`。M04 上线后同一 provider 正常就有 ≥2 个 key（AC-06 要求），恢复旧唯一键必然 1062 冲突。ADR 声称该 SQL“必须在建表/迁移测试中验证可执行”，但现有 V10MigrationTest 的种子只有单 key，测试通过不代表真实数据可回滚。"
    evidence: "ADR-0021 §10.3；本地 mysql:8.4.13 探针 D：两行同 (tenant,provider,type) → `ERROR 1062 Duplicate entry 'platform-\\xAA-API_KEY' for key 'pc2.uk_provider_credential'`"
    fix: "回滚 SQL 先显式收敛到一把 key（保留规则写死：priority 最小、created_at 最新，或保留 `id` 列表由运维填写），把其余置 RETIRED 后再改唯一键；回滚演练种子必须包含 2 个 ACTIVE key + 1 个 INVALID key"
  - id: OPS-M04-005
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops
    title: "`spring.flyway.ignore-future-migrations` 在 Flyway 12 已不存在，回滚章节给的是无效开关"
    summary: 'ADR-0021 §10.3 要求回滚窗口“设 `spring.flyway.ignore-future-migrations=true`”。本项目 Flyway 12.4.0（Boot 4.1.2 管理）移除了该参数（Boot 元数据标注 `Removed in Flyway 9.0`，replacement=`ignore-migration-patterns`），设了也不会生效；真正生效的是 `spring.flyway.ignore-migration-patterns`，而 Boot 4.1.2 的默认值已是 `["*:future"]`。结论：默认行为支持旧 API 在 V11 库上启动，但文档给出的应急开关是假的；若某客户把 `ignore-migration-patterns` 显式置空，旧 API 会以 `Detected applied migration not resolved locally: 11` 启动失败，而运维按文档改属性不会有任何效果。'
    evidence: '`~/.m2/.../flyway-core-12.4.0-sources.jar` 仅存在 `ignoreMigrationPatterns`（ConfigUtils/ClassicConfiguration）；`spring-boot-flyway-4.1.2-sources.jar` FlywayProperties:323 默认 `singletonList("*:future")`，additional-spring-configuration-metadata.json 中 `ignore-future-migrations` deprecation.level=error'
    fix: "ADR §10.3 与 B10 RUNBOOK 改为 `spring.flyway.ignore-migration-patterns=*:future`（并说明默认已生效），回滚演练加一条：用 M04 前 API 镜像连 V11 库启动并断言 `/actuator/health` UP + 历史调用可读"
  - id: OPS-M04-006
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops
    title: 发布顺序缺一次性迁移 Job 与配置预检，与既有 RUNBOOK OPS-004 冲突；“必须停旧 Worker”的理由不成立
    summary: "ADR-0021 §10.2 第 3 步写“部署新 API（执行 V11）”，即由常驻 API 启动触发迁移；而 RUNBOOK“V10 生产迁移”OPS-004 第 3 步明确要求用**一次性迁移 Job**、不要经常驻 API 触发。M04 若沿用启动迁移，任何配置错误（缺 `RICCON_MASTER_KEY`、`RICCON_MODEL_ENDPOINTS` 非法）都可能发生在 DDL 之后——DB 已到 11、服务起不来、旧 Worker 又不能重启，回滚成本陡增。另：§10.2 第 2 步称停旧 Worker 是为“避免旧代码在 V11 上写不兼容数据”，但旧 Worker 的 `INSERT INTO model_invocation` 使用显式列清单（ModelInvocationJdbc:53-58），V11 新列全可空或带默认，旧 Worker 在 V11 上读写是兼容的；真正约束是**迁移后不得重启旧 Worker**（严格等值校验），不是必须提前停。"
    evidence: "ADR-0021 §10.2；docs/RUNBOOK.md“OPS-004 协调切换顺序”第 3 步；apps/worker/.../ModelInvocationJdbc.java:53-58；apps/api/src/main/resources/application.yml `flyway.validate-on-migrate: true`"
    fix: "改为与 OPS-004 一致的五段式：preflight（校验 Secret/端点 JSON/模式/DB 连通与表体积）→ 备份 → 迁移 Job（一次性，失败即停，未动业务）→ 新 API → 新 Worker → 开关；把“停旧 Worker”写成“先 drain 旧 Worker 再起新 Worker；停旧 Worker 是缩短混合窗口的可选步骤，不是迁移前置”"
  - id: OPS-M04-007
    severity: P1
    status: open
    owner: riccon/lead + riccon/builder (B1b) + riccon/ops
    title: API 新增主密钥启动依赖的本地/测试/真实栈/CI 落点全部缺失；主密钥轮换的 rewrap 无工具无 owner
    summary: "API 从 M04 起需要 `RICCON_MASTER_KEY`，但：①`apps/api/src/test/resources/application-test.yml` 没有该键，API 集成测试与 `ApiApplicationTests` 会在启动阶段失败；②`scripts/real-stack/run.mjs` 只给 worker（及 seed）注入 masterKey，API 段未注入 → 真实栈起不来；③RUNBOOK“本地运行”没有 `export RICCON_MASTER_KEY=...`；④API 也需要 `RICCON_MASTER_KEY_PREVIOUS` 才能解历史 DEK（worker 已有该配置），ADR 只提 `RICCON_MASTER_KEY`；⑤ADR §9/§10 声称“双密钥窗口 rewrap、轮换后校验、`KEY-005`”，但现有 `MasterKeyProvider` 只有解密回退，没有 rewrap 工具/端点/脚本，B1b 与 B10 的验收都不含它。"
    evidence: "apps/api/src/test/resources/application-test.yml（无 master-key）；scripts/real-stack/run.mjs:222-244（API env 无 RICCON_MASTER_KEY）与 :266-277（worker 有）；apps/worker/.../MasterKeyProvider.java:44,119-120（仅 previous 解密回退）；ADR-0021 §6.5/§9/§10.6"
    fix: "B1b 明确：`application-test.yml` 加测试主密钥占位、API 支持 current+previous+master-key-id 并做启动 canary；B8 在 run.mjs 给 API 注入同一 masterKey；B10 增加 `scripts/ops/rewrap-dek`（列出 master_key_id 分布 → 逐条 unwrap/rewrap → 校验）+ RUNBOOK 轮换四步（新密钥并存 → 滚动重启 → rewrap → 移除旧密钥）；否则把“主密钥轮换”从 M04 交付说明中移除并登记为 M09 项"
  - id: OPS-M04-008
    severity: P1
    status: open
    owner: riccon/lead + riccon/builder (B2/B8) + riccon/ops
    title: DEMO/PRODUCTION 端点配置在本地/测试/真实栈无落点；API 与 Worker 配置漂移可绕过“生产禁用演示端点”
    summary: "ADR-0021 §2.1 规定默认 `PRODUCTION` 且 `RICCON_MODEL_ENDPOINTS` 缺失时启动失败，测试 profile 必须显式 `DEMO`。现状：`application-test.yml` 无相关键、WorkerIntegrationTestBase 只设 `allow-insecure-http=true`、真实栈的模型端点就是 `http://127.0.0.1:18990/v1`（回环 + HTTP，生产规则直接拒绝）、`scripts/real-stack/run.mjs` 未设 `RICCON_MODEL_DEPLOYMENT_MODE` 与 `RICCON_MODEL_ENDPOINTS`。若只靠“测试里设 DEMO、默认 PRODUCTION”，一旦构建者为了通过测试把仓库默认改成 DEMO，生产就永久失去该护栏；且 API 与 Worker 由两份 env 注入（同一 `RICCON_MODEL_ALLOW_HTTP` 映射到不同属性），任一侧配错即可让 Worker 在 PRODUCTION 下调用 DEMO_ONLY 端点——这正是 AC-15 要防的越权出网。"
    evidence: "apps/api/src/test/resources/application-test.yml；apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:202；scripts/real-stack/run.mjs:222-277；apps/worker/src/main/resources/application.yml `allow-insecure-http: ${RICCON_MODEL_ALLOW_HTTP:false}`；ADR-0021 §2.1/§2.2"
    fix: "①仓库默认保持 PRODUCTION 并加一条测试：无 env 时上下文启动失败（负向）；②`application-test.yml` 与 WorkerIntegrationTestBase 显式设 DEMO + 允许列表；③run.mjs 设 `RICCON_MODEL_DEPLOYMENT_MODE=DEMO`、`RICCON_MODEL_ENDPOINTS`、`RICCON_MODEL_ALLOW_HTTP=true`，API/Worker 注入**同一份** env（建议 run.mjs 用共享常量对象）；④RUNBOOK 写明“API 与 Worker 的端点/模式 env 必须逐字节一致”，并在 `scripts/ops/preflight` 里比对两侧 `/actuator/info` 或启动日志指纹"
  - id: OPS-M04-009
    severity: P1
    status: open
    owner: riccon/lead + riccon/builder (B8) + riccon/ops
    title: 性能门禁不可执行：脚本缺失、CI 无 job、ci.yml 无写范围，且 50 并发与现有默认限流冲突
    summary: "合同 `commands.perf` 指向 `e2e/perf/mvc-smoke.js`，该文件不存在（F-007），CI 也没有 perf/security job；`.github/workflows/ci.yml` 不在任何 M04 任务 write_scope（B8 只写 `e2e/perf/**`、`scripts/real-stack/**`、`e2e/support/**`）。即使脚本写好，AC-17 的“50 并发 run”在当前默认配置下不可达：Worker `chat.max-concurrent-streams` 默认 16（硬上限 128，线程池同值），Hikari 默认 10，`outbox.batch-size` 10。ADR 未定义 perf profile 的 env（并发、池大小、超时、假 provider 场景），也未定义成本查询 30/180 天 p95 的场景与数据集规模。"
    evidence: "e2e/ 下无 perf 目录；.github/workflows/ci.yml 仅 backend/frontend 两个 job；apps/worker/src/main/resources/application.yml `max-concurrent-streams: ${RICCON_CHAT_MAX_STREAMS:16}`、`maximum-pool-size: ${RICCON_DB_POOL_SIZE:10}`；ChatStreamProperties.java:20,27 上限 128；ADR-0021 §11"
    fix: "Lead 给 B8 或 B10 增补 `.github/workflows/ci.yml`（perf job 需 50 并发 host 或明确 nightly/manual、artifact 上传 k6 summary + JSON、失败退出码）并写进 write_scope；B8 交付 `e2e/perf/env.mjs`（或 compose override）声明 `RICCON_CHAT_MAX_STREAMS=50`、`RICCON_DB_POOL_SIZE>=25`、`RICCON_FAKE_MODEL=1`、`RICCON_MODEL_DEPLOYMENT_MODE=DEMO`；AC-17 报告必须记录这套 env 与主机规格"
  - id: OPS-M04-010
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops
    title: 生产“加端点/加模型/维护价格”没有可用工具，唯一种子脚本是测试资产且会删表
    summary: "ADR 规定端点来自部署 env、“DB 行由部署种子创建”、价格“由部署种子/运维维护、M04 只读”，但仓库内唯一的模型种子是 `scripts/real-stack/seed.mjs`——它以 `DELETE FROM model / provider_credential / model_provider` 开头，只适用于一次性测试栈，绝不能用于生产；B10 的 write_scope 允许 `scripts/ops/**`，但验收未包含“幂等生产种子/价格更新”。结果：M04 上线后新增一个端点需要“改 env + 手写 SQL 插 `model_provider`/`model` + 重启 API/Worker”三步，没有幂等脚本、没有变更记录、没有价格更新流程。"
    evidence: "scripts/real-stack/seed.mjs:237-259（DELETE 后 INSERT 单 provider/单 model/单 credential）；ADR-0021 §2.1/§2.4、ADR-0022 §2.1；ADR-0021 §8.3 B10 验收"
    fix: "B10 增加 `scripts/ops/seed-model-catalog.mjs`（按 code/provider+model_key 幂等 upsert，不删除、不含密钥，端点/价格由参数或 JSON 文件给出，输出 diff 摘要）与 `scripts/ops/update-model-price.mjs`（只改 `model.input/output_price_per_1m`，打印 before/after，禁止改历史快照）；RUNBOOK 写“加端点/加模型/改价格/换币种不可行”四条流程"
  - id: OPS-M04-011
    severity: P1
    status: open
    owner: riccon/ops + riccon/architect
    title: 备份/恢复前置不完整：DB 备份必须与主密钥同生命周期，否则凭据不可解；无恢复演练与 RPO/RTO
    summary: "ADR §10.2 第 1 步只说“备份 MySQL（含 flyway_schema_history、tenant_data_key、provider_credential）”。但 `provider_credential.ciphertext` 与 `tenant_data_key.wrapped_dek` 只有配合**当时的** `RICCON_MASTER_KEY` 才能解密：备份保留 180 天而密钥已轮换/销毁时，备份里的凭据全部报废。ADR 又声明“生产回滚优先备份恢复”，却没有恢复脚本、校验点（行数/触发器/审计可读）与 RPO/RTO。"
    evidence: "ADR-0021 §10.3“生产回滚优先备份恢复”；V5:58-71（tenant_data_key 包裹 DEK）、V5:79-100（ciphertext）；ADR-0006；docs/RUNBOOK.md“恢复”章节（无 DB 备份/恢复步骤）"
    fix: "B10 在 RUNBOOK 增加：备份必须同时记录 `master_key_id` 与密钥版本（密钥本体留在 Secret store，禁止入备份包）、密钥保留期 ≥ 最长备份保留期、恢复演练脚本 `scripts/ops/restore-drill`（恢复到一次性容器 → 校验表数/触发器/一行 credential 可解密/j1 冒烟）与 RPO/RTO 目标；M04 gate 的回滚演练必须包含“备份恢复”路径而不只是应用回滚"
  - id: OPS-M04-012
    severity: P1
    status: open
    owner: riccon/architect
    title: V11 历史回填是全表 UPDATE（含一次多余的全表重写），大表上产生长事务/undo 膨胀/复制延迟
    summary: "ADR-0022 §5 要求“把既有 model_invocation 的 cost_status 置为 USAGE_UNKNOWN、cost_day 按 started_at 回填”。`cost_status` 是 `NOT NULL DEFAULT 'USAGE_UNKNOWN'`，ADD COLUMN 已在元数据层给出该值，再全表 `UPDATE cost_status=...` 只会把每一行真实重写一遍（undo + redo + binlog 全量），毫无收益。`cost_day` 回填 `DATE(CONVERT_TZ(started_at,'+00:00','+08:00'))` 也是单条全表 UPDATE；MySQL DDL 非事务，Flyway 逐语句执行，这条语句会以一个长事务持锁，期间写放大、复制延迟、失败后需重跑（且部分已更新）。ADR 只说“Builder 必须在 V10 种子数据上断言回填结果”，没有规模与分批要求。"
    evidence: "ADR-0022 §5 最后一条；ADR-0021 §3.3 `cost_status VARCHAR(16) NOT NULL DEFAULT 'USAGE_UNKNOWN'`；ADR-0020 OPS-006（MySQL DDL 非事务）"
    fix: "删掉 `UPDATE ... SET cost_status` 这类冗余更新；`cost_day` 回填改为分批（例如 `WHERE cost_day IS NULL LIMIT 50000` 循环，每批单独提交并在 RUNBOOK 记录进度），或明确写“单语句 + 维护窗口 + 先测量行数/体积”；验收加一条 `cost_day` 回填前后 `COUNT(*) WHERE cost_day IS NULL = 0` 且金额未受影响"
  - id: OPS-M04-013
    severity: P2
    status: open
    owner: riccon/architect + riccon/ops
    title: 成本报告未定义 `cost_day IS NULL` 的语义，回滚窗口的调用会从金额与计数中静默消失
    summary: "成本查询按 `cost_day` 范围过滤，而 `cost_day` 只有 M04 的 Worker 才会写。应用回滚到 M04 前版本后，旧 Worker 继续插入 `model_invocation`（显式列清单，`cost_day` 为 NULL），这些调用既不计入金额，也可能连 `usageUnknownCalls` 都不计——`CostTotals` 里没有“无日桶/未归类调用”字段，`CostBucket.day` 虽可空但契约没有约束必须返回。结果是回滚窗口的成本页“调用量”与 DB 实际行数不一致，且没人能看出来。"
    evidence: "contracts/openapi.yaml:5818-5850（CostTotals 无 unbucketed 字段）、:5851-5890（day 可空）；ADR-0022 §2.4；ModelInvocationJdbc.java:53-58"
    fix: "二选一并写进 ADR/RUNBOOK：①查询用 `started_at` 兜底并把 NULL 归入 `day=null` 桶 + 在 totals 增加 `unbucketedCalls`（需契约增量）；②明确“回滚窗口中新调用不计入成本报告”，并在 RUNBOOK 与评审包登记该窗口的用量缺口。至少要在 RUNBOOK 写明成本页在混合版本窗口不可用于对账"
  - id: OPS-M04-014
    severity: P2
    status: open
    owner: riccon/architect + riccon/builder (B6)
    title: 配额准入的 `INSERT IGNORE + 条件 UPDATE` 缺死锁/锁等待与“不得自动重试”的运维定义
    summary: "ADR-0022 §1.3 用 `INSERT IGNORE` 补行 + `UPDATE ... WHERE used_count < limit` 保证零超卖，方向正确。但并发首次插入同一 PK 时，`INSERT IGNORE` 在 InnoDB 下会加锁并可能出现死锁/锁等待（比 `ON DUPLICATE KEY UPDATE` 更易触发）；同一租户的日窗口是**单行热点**，50 并发（甚至 500）都串行在这行上。ADR 没写 `innodb_lock_wait_timeout`、连接池/事务超时，也没写“准入失败不得自动重试”（自动重试可能重复计数或重复建 run）。"
    evidence: "ADR-0022 §1.3；apps/worker/src/main/resources/application.yml `connection-timeout: 3000`；Hikari 默认 `innodb_lock_wait_timeout=50s`"
    fix: "B6 明确：补行改用 `INSERT ... ON DUPLICATE KEY UPDATE used_count=used_count`（或迁移时预置窗口行）；准入事务不加自动重试，失败一律返回 503/429 并计数；RUNBOOK/指标记录锁等待与死锁次数；PERF-M04-002 断言 50 并发下无锁超时、p95 不超过 API 预算"
  - id: OPS-M04-015
    severity: P2
    status: open
    owner: riccon/architect + riccon/ops
    title: 迁移新增索引/隐式 FK 索引/历史表保留与清理归属未量化
    summary: "`model_invocation` 一次性新增 4 个二级索引，其中 `idx_model_invocation_time (started_at, tenant_id, model_id)` 与既有 `idx_model_invocation_model (tenant_id, model_id, started_at)` 高度重叠；`ADD FOREIGN KEY (provider_id)` 未显式建索引，InnoDB 会自动建一个（实测索引名=约束名），等于又多一次重建。`model_invocation_attempt`、`quota_usage`、`model_invocation` 的 180 天清理全部推给 M09，但 M04 上线即开始增长，且 attempt 有 FK 指向 model_invocation（清理顺序受限）。"
    evidence: "ADR-0021 §3.3；V5:126-133（既有索引）；本地探针 F：ADD FK 后自动生成 `fk_mi_provider(provider_id)` 索引；ADR-0022 §2.5"
    fix: "删掉与既有索引重叠的那条（或改为 `(started_at, tenant_id)`）；`provider_id` 显式建索引并写进迁移；RUNBOOK 给出容量估算（调用数 × 平均 attempt 数 × 行宽）与清理顺序（先 attempt 再 invocation）、清理 SQL 的 `LIMIT` 批量约束"
  - id: OPS-M04-016
    severity: P2
    status: open
    owner: riccon/architect + riccon/ops
    title: 回滚窗口的可用性副作用未登记：旧 Worker 选 key 规则、schema 校验开关无任何日志、唯一键删除使旧种子失去幂等
    summary: "①回滚后旧 Worker `findActiveCredential` 取 `status='ACTIVE' ORDER BY version DESC LIMIT 1`，多 key 下可能选中正在冷却或即将失效的那把，且没有 failover——回滚即可能降级为“单 key 可用性”；②`SchemaVersionChecker` 用 `@ConditionalOnProperty` 关闭后**完全不创建 bean**，没有任何启动日志说明“校验已关闭”，风险接受既不留日志也不进审计；③删除单 key 唯一键后，M02 的凭据注入方式（同 provider 重复 INSERT）不再幂等，重复执行会生成多个 ACTIVE key。"
    evidence: "apps/worker/.../CredentialJdbc.java:27-33；SchemaVersionChecker.java:42-45（ConditionalOnProperty）、:51/80-83；ADR-0021 §10.3"
    fix: "回滚 RUNBOOK 写明“只保留一把 ACTIVE key 再回滚”或“回滚后手工核对 key 状态”；schema 校验关闭时输出 WARN（含 `RICCON_WORKER-003` 之类稳定码）并由 B10 记录运维工单号；种子脚本改为按 fingerprint/`(provider,credential_type)` 先查后插"
  - id: OPS-M04-017
    severity: P2
    status: open
    owner: riccon/lead + riccon/ops
    title: 合同命令面有未落地的 target，M04 gate 不能依赖它们
    summary: "`commands.run` 指向 `deploy/compose/docker-compose.dev.yml`、`commands.deploy_preview` 指向 `deploy/compose/docker-compose.preview.yml`、`commands.deploy_prod` 指向 `deploy/helm/riccon`，三者当前都不存在（deploy 下只有 test compose）。这意味着 M04 没有“verifier 可操作的预览环境”，回滚演练只能在一次性容器/真实栈脚本上做，且 gate 若调用 `deploy_preview` 必然失败。M09 才交付 Helm/预览，属既定计划，但必须显式声明 M04 不以此取证。"
    evidence: "`find deploy -type f` → 仅 docker-compose.test.yml；AGENTS.md commands.run/deploy_preview/deploy_prod"
    fix: "在 M04 评审包与 gate 说明中标注 `deploy_preview`/`deploy_prod` 对 M04 为 not-applicable（M09 交付）；回滚/迁移证据统一走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`；若 Lead 要求 M04 必须有预览环境，需要单独排任务并算进预算"
  - id: OPS-M04-018
    severity: P3
    status: open
    owner: riccon/architect
    title: 开关与重试配置的运维语义有歧义
    summary: "①环境变量覆盖开关时“启动时写一条 source=DEPLOYMENT 审计”，多副本滚动重启会产生成倍审计噪音，且若 DB 未就绪会拖慢启动；②既有 `riccon.worker.model.max-attempts: 3` 与新增 `riccon.worker.routing.max-attempts: 5` 并存，两套“max-attempts/retry-delays”语义相近，运维止血时极易改错；③`RICCON_BILLING_CURRENCY` 变更无模型级币种列可校验，只能靠报告里的 `currencyMismatchCalls` 事后发现。"
    evidence: "ADR-0021 §3.4/§10.5；apps/worker/src/main/resources/application.yml `model.max-attempts: 3`、`retry-delays: 200ms,1s`；ADR-0022 §2.1"
    fix: "启动审计只在值实际变化时写（或改为启动日志 + `/actuator/info` 暴露生效值）；新属性统一命名到 `riccon.worker.routing.*` 并在 RUNBOOK 的“止血开关表”里只列一套；RUNBOOK 声明币种部署后不可变，变更=新部署 + 历史报告隔离"
---

# REV-M04-OPS-001：M04 模型网关纵深 — 独立运维评审

## 1. 结论

**verdict: fail（设计阶段，`ADR-0021`/`ADR-0022` 不能进入 `accepted`）**

不是“整体方向错”。路由/降级状态机、平台托管 key 池、fail-closed 配额、价格快照这些选择在运维上都是可执行、可回滚的；问题集中在**三处会让 T4 gate 变成假绿或让故障恢复变成纸面计划**的地方：

1. V11 迁移的 CHECK 收紧顺序按原文执行会失败，且 CI 的迁移冒烟只覆盖 fresh install，覆盖不到 V10→V11 升级路径（OPS-M04-001）。
2. 可观测性只存在于 ADR 的指标清单里：没有 registry、没有暴露端点、没有任何任务拥有 pom/actuator 改动权限（OPS-M04-002）。
3. 回滚路径有三处不可执行/无效：多 key 数据下恢复单 key 唯一键必冲突、`ignore-future-migrations` 是 Flyway 12 已删除的开关、回滚 SQL 的状态归一违反新 CHECK（OPS-M04-001/004/005）。

**是否建议在单模块 24h 内实施：不建议。** 即便补齐上述修复，24h 内还要同时完成 V11 大表 DDL 评估、迁移/回滚演练、性能门禁、主密钥与端点注入、RUNBOOK 重写——这些恰好是 Ops 侧最不能压缩的部分。支持 Architect 的 M04/M04b 拆分（见 §9），但拆分**不降低** V11 的一次性复杂度，只是把配额/成本的验证压力挪到第二个窗口。

修复 OPS-M04-001..012 后可以复评转 `pass`；其中 001/002 必须先修，否则后续所有迁移与性能证据都建立在错误前提上。

## 2. 评审方法与证据

只读评审，未修改任何 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/源码/测试/迁移，未提交 git。运行过的命令：

| 命令                                                                           | 结果                                                               |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| `git rev-parse HEAD`                                                           | `d800410a7f0f413c12740eadfd3daa1ebe82ae67`（M04 设计改动均未提交） |
| `docker compose -f deploy/compose/docker-compose.test.yml config`              | exit 0                                                             |
| `ruby -ryaml -e 'YAML.load_file("contracts/openapi.yaml")'`                    | exit 0；`openapi 3.1.0`、`info.version=1.3.0-m04`、`paths=87`      |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict` | exit 0（0 errors, 0 warnings）                                     |
| `node .../riccon.mjs status`                                                   | M04 `planned`、0 tasks、gate 仍为 M10                              |
| 一次性 `mysql:8.4.13` 容器探针 A–J（见下）                                     | 关键错误已复现；容器 `--rm` 销毁                                   |

MySQL 探针（仅验证 DDL 语义，**不是** V11 迁移本身）：

- **A**：旧 CHECK（`ACTIVE|RETIRED`）在表上时 `UPDATE ... SET status='DISABLED'` → `ERROR 3819 Check constraint 'chk_provider_credential_status' is violated`。
- **B**：先 `DROP CHECK` 再更新再 `ADD CONSTRAINT` → 成功。
- **C**：新 CHECK（`ACTIVE|DISABLED|INVALID`）在表上时 `UPDATE ... SET status='RETIRED'` → `ERROR 3819`（即 §10.3 回滚 SQL 的方向）。
- **D**：两把 ACTIVE key 的表恢复 `UNIQUE (tenant_id, provider_id, credential_type)` → `ERROR 1062 Duplicate entry`。
- **E**：`ADD COLUMN + ADD KEY` 同时写在一条 ALTER 并 `ALGORITHM=INSTANT` → `ERROR 1845 ALGORITHM=INSTANT is not supported for this operation`。
- **F**：`ADD FOREIGN KEY (provider_id)` 未显式建索引 → InnoDB 自动生成同名索引 `fk_mi_provider`。
- **G**：`ADD COLUMN ... ALGORITHM=INSTANT` 单独执行 → 成功。
- **I**：`ADD CONSTRAINT ... CHECK` 显式 `ALGORITHM=INPLACE, LOCK=NONE` → `ERROR 1845 ... Try ALGORITHM=COPY`（即 CHECK 只能 COPY，阻塞 DML）。

版本事实核对（静态，未启动服务）：

- Flyway `12.4.0`（`~/.m2/repository/org/flywaydb/flyway-core/12.4.0`）：源码中**只有** `ignoreMigrationPatterns`，无 `ignoreFutureMigrations`。
- Spring Boot `4.1.2` `FlywayProperties:323`：`ignoreMigrationPatterns` 默认 `["*:future"]`；`spring.flyway.ignore-future-migrations` 在 `additional-spring-configuration-metadata.json` 中 `deprecation.level=error`，`reason="Removed in Flyway 9.0"`。

**未运行**（按任务约束）：`./mvnw verify`、k6、V11 迁移（尚不存在）、任何 API/Worker 进程、真实栈 E2E。因此本文不对覆盖率、p95、E2E 作任何通过性判断。

## 3. 迁移/回滚清单（V11）

### 3.1 可执行顺序（必须写进 V11 注释，B1a 照此实现）

```text
0. 预检（迁移 Job 内，DDL 之前）：DB 连通、表体积、磁盘余量、
   RICCON_MASTER_KEY/端点 JSON/模式/币种 env 校验；失败即退出，不动 DB
1. CREATE TABLE：model_routing, model_routing_entry, model_gateway_switch,
   model_invocation_attempt, quota_limit, quota_usage（全部新增，可重跑前先 DROP 空的半成品）
2. provider_credential：
   2.1 ADD COLUMN（label/masked_hint/priority/consecutive_failures/cost 相关）ALGORITHM=INSTANT
   2.2 DROP CHECK chk_provider_credential_status          ← 必须在数据归一之前（OPS-M04-001）
   2.3 UPDATE RETIRED→DISABLED；回填 label/masked_hint/fingerprint（合成值）
   2.4 ADD UNIQUE KEY uk_provider_credential_fingerprint / ADD KEY idx_provider_credential_pool
   2.5 ADD CONSTRAINT 新 CHECK（status/priority/failures）
   2.6 DROP INDEX uk_provider_credential（旧唯一键）—— 放在最后
3. model_invocation：
   3.1 ADD COLUMN 全部可空/带默认 ALGORITHM=INSTANT（去掉冗余的 cost_status 全表 UPDATE）
   3.2 ADD KEY 逐条 ALGORITHM=INPLACE, LOCK=NONE
   3.3 回填 cost_day（分批；见 OPS-M04-012）
   3.4 ADD CONSTRAINT CHECK（COPY，停写窗口；见 OPS-M04-003）
4. 权限/审计种子（幂等 INSERT ... WHERE NOT EXISTS）
5. 路由种子：CHAT/REPORT 各 1 行 model_routing + 最早 ACTIVE 模型 position=1
6. 开关种子：routingMode=ROUTED / failoverEnabled=true / quotaEnforcementEnabled=true
```

注意：CI 的“迁移冒烟”是把 `V*.sql` 直接喂给空库（`.github/workflows/ci.yml:55`），**只能证明 fresh install**；V5 里没有任何 `RETIRED` 行，所以 A 步骤的错误在 CI 上不会暴露。V10→V11 升级路径必须由 `V11MigrationTest`（参照 `V10MigrationTest` 的四段式：forward / rollback / fresh / 失败注入）覆盖，种子至少包含：1 行 RETIRED 凭据、2 行 ACTIVE 凭据、1 万行历史 `model_invocation`。

### 3.2 表体积与锁

| 对象                            | 风险                                    | 处理                                                        |
| ------------------------------- | --------------------------------------- | ----------------------------------------------------------- |
| `model_invocation` ADD COLUMN   | INSTANT 可接受                          | 单独语句、显式 `ALGORITHM=INSTANT`                          |
| `model_invocation` ADD KEY ×4   | INPLACE，重建索引，磁盘 ≈ 索引体积      | 逐条 `ALGORITHM=INPLACE, LOCK=NONE`，记录耗时               |
| `model_invocation` ADD CHECK ×3 | **COPY：阻塞 DML + 全表重建**           | 迁移前测量行数/体积；超过阈值单独窗口停写；或改为应用层校验 |
| `cost_day` 回填                 | 单语句长事务、undo/binlog、复制延迟     | 分批提交 + 进度记录（OPS-M04-012）                          |
| `provider_credential`           | 表小；但 CHECK 替换与唯一键替换不可回退 | 维护窗口内完成，先备份该表                                  |

### 3.3 演练命令（V2/B1a 执行，M04 交付物）

```bash
# 1) 一次性容器（禁止连热栈；参照 RUNBOOK“测试栈迁移演练”口径）
docker run --rm --name riccon-v11-drill -e MYSQL_ROOT_PASSWORD=<tmp> -d mysql:8.4.13 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --log-bin-trust-function-creators=1

# 2) Flyway（不是 mysql CLI）应用到 V10 → 灌 M02/M03/M10 种子 + 多 key 种子
#    断言：flyway_schema_history MAX=10；RETIRED 行存在；2 个 ACTIVE key
# 3) 应用 V11，断言：
#    - MAX(success version)=11
#    - provider_credential 无 RETIRED；旧唯一键不存在；新唯一键存在；CHECK 生效（写 RETIRED 必须失败）
#    - model_invocation.cost_day 无 NULL；cost_amount 全 NULL；attempt_count=1
#    - 6 张新表 + 4 个新索引 + FK 存在
#    - 历史 model_invocation 仍可被 M02 查询读取（列清单不变）
# 4) 回滚演练（修正后的 SQL）：
#    - 先 DROP 新 CHECK → 收敛到一把 ACTIVE key → 状态改 RETIRED → 恢复旧唯一键 → DROP 新增列
#    - 断言：MAX(version) 手工删除 11 行后 = 10；旧代码（V10 镜像）API 能启动
# 5) 失败注入：把 2.4 的唯一键前置到 2.3 之前，断言迁移失败并留下可 repair 的前缀
```

### 3.4 失败处理（沿用 V10 的 OPS-006 矩阵）

- MySQL DDL 非事务，失败会保留已提交前缀；**禁止**反复重跑而不 `flyway repair`，禁止修改已应用的 V11 文件（checksum）。
- 新增表为空 → `DROP TABLE` + `repair` + 重跑；`provider_credential` 已加列未加约束 → 只补缺失步骤（列已存在时用 `ADD COLUMN IF NOT EXISTS` 语义需自行判断，MySQL 8 不支持 IF NOT EXISTS，需在脚本里分支）。
- 迁移 Job 被杀在 CHECK 阶段（COPY）→ 表会回滚整条 ALTER，但已加列/索引保留；按残留清单补齐，不要整段重放 `ADD COLUMN`。
- 失败后**不要**直接起旧 Worker：V11 若已部分提交（例如 history 行 success=0），Worker 的 `MAX(version)` 可能仍是 10 但结构已变；恢复顺序是 repair → 结构校验 → 再起服务。

## 4. 发布顺序（API/Worker/DB/开关）

```text
0. 变更评审与批准（T4 生产需用户批准；本评审不等于批准）
1. preflight（脚本，无副作用）：
   - Secret 存在：RICCON_MASTER_KEY（+ PREVIOUS）、SERVICE_TOKEN、DB 凭据
   - RICCON_MODEL_DEPLOYMENT_MODE / RICCON_MODEL_ENDPOINTS / BILLING_CURRENCY 合法且 API 与 Worker 一致
   - DB 连通；model_invocation 行数与体积；磁盘余量 ≥ 2×表体积；无长事务/MDL
2. 全量备份（含 flyway_schema_history / tenant_data_key / provider_credential / SPRING_SESSION）
   + 记录 master_key_id；在副本上验证恢复（至少校验一行 credential 可解密）
3. 迁移 Job（一次性，新 API 镜像，仅执行 Flyway；不启 Web 端口；失败即停）
4. 结构校验：MAX(version)=11、6 张新表、4 索引、CHECK 生效、RETIRED 清零
5. 起新 API → /actuator/health UP → 读 /admin/model-providers 应返回 allowed=true 且标签正确
6. drain 旧 Worker → 起新 Worker（expected=11）→ /actuator/health + outbox 恢复领取
7. 冒烟：J1 首字、J2 报告（含审批）、路由读、key 列表掩码、开关读
8. 打开开关（V11 已种 ROUTED/true/true；若发版策略要求先基线，则显式 PUT 后再放开）
9. 观测 30 分钟：降级次数、5xx、配额拒绝、链空、key 失效计数
```

关键判断：

- **旧 Worker 不需要在迁移前停**（OPS-M04-006）：旧代码的写入是显式列清单、新列全可空/有默认，读写兼容；真正的硬约束是“V11 之后不得重启旧 Worker”，因为 `SchemaVersionChecker` 是严格等值比较（`actual != EXPECTED`）。
- **但旧 Worker 不能无限期留在 V11 上跑**：它没有 failover、不写 cost_day、不写 attempt，混合窗口越短越好。建议顺序：迁移 → 立刻切新 Worker，窗口内不接受“旧 Worker 扩容”。
- **停旧 Worker 是缩短混合窗口的选择**，写进 RUNBOOK 时不要再说“旧代码会写不兼容数据”，那会让未来的值班人员误判能否在线迁移。
- `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 只在**回滚窗口**用：它关闭的是整个版本校验（包括“比期望更旧”和“未来的 V12”），且 `@ConditionalOnProperty` 使 bean 根本不创建 → 没有任何启动日志。要求：启用该开关时 B10 在 RUNBOOK 写“工单号 + 人工核对 `MAX(version)` + 只允许启动 M04 前最后一个 worker 版本”，并让代码在关闭时打一条固定 WARN（`RICCON-WORKER-002 schema validation disabled`）。
- 回滚边界：**不回滚数据库**。旧 API 依赖 Boot 默认 `ignore-migration-patterns=*:future` 才启动；这一点必须演练，不能只写在 ADR 里（OPS-M04-005）。

## 5. Secret / 端点 / 出网

### 5.1 部署配置项（M04 新增，必须进 RUNBOOK 的“部署清单”）

| 变量                                      | 归属              | 默认         | 缺失/非法行为                                     | 备注                                        |
| ----------------------------------------- | ----------------- | ------------ | ------------------------------------------------- | ------------------------------------------- |
| `RICCON_MASTER_KEY`                       | Secret            | 无           | **API 与 Worker 启动失败**（M04 起 API 新增依赖） | Base64 32B；与 DEK 备份同生命周期           |
| `RICCON_MASTER_KEY_PREVIOUS`              | Secret            | 空           | 空=仅当前密钥可解                                 | 轮换窗口；API 也必须支持                    |
| `RICCON_MASTER_KEY_ID`                    | ConfigMap         | `mk-1`       | 仅记录                                            | 与 `tenant_data_key.master_key_id` 对齐     |
| `RICCON_MODEL_DEPLOYMENT_MODE`            | ConfigMap         | `PRODUCTION` | 非法=启动失败                                     | DEMO 仅本地/CI/演示；API 与 Worker 必须一致 |
| `RICCON_MODEL_ENDPOINTS`                  | ConfigMap（JSON） | 空           | PRODUCTION + 空 = 启动失败                        | 非密钥；变更需重启                          |
| `RICCON_MODEL_ALLOW_HTTP`                 | ConfigMap         | `false`      | PRODUCTION+true = 启动失败                        | 仅 DEMO 可用                                |
| `RICCON_BILLING_CURRENCY`                 | ConfigMap         | `CNY`        | 非法=启动失败                                     | 部署后不可变；变更=新部署                   |
| `RICCON_CHAT_MAX_STREAMS`                 | ConfigMap         | 16           | 1..128                                            | 50 并发 perf 必须调高                       |
| `RICCON_DB_POOL_SIZE`                     | ConfigMap         | 10           | —                                                 | 50 并发 perf 需 ≥25                         |
| `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED` | 运维临时          | `true`       | 仅回滚窗口                                        | 关闭必须留日志与工单                        |

### 5.2 启动校验与 canary

- 启动必须失败（而不是降级）的情形：主密钥缺失/长度错/是已知测试密钥；PRODUCTION 下端点为 HTTP/回环/元数据/带 userinfo/query；PRODUCTION 下配置含 `DEMO_ONLY` 端点且被路由引用；端点 JSON 重复 code/baseUrl。
- canary 分两层：①进程内用固定测试向量做 wrap/unwrap（防密钥格式错）；②运维侧 `scripts/ops/check-master-key` 用**现网 DEK** 试解一行 `provider_credential`（防“密钥能加载但解不开历史数据”）。第②层现在不存在，且如果只在 Worker 做，API 新增凭据仍可能写入一把谁也解不开的 DEK——API 与 Worker 必须用同一份密钥校验。
- 轮换流程（当前**不可执行**，见 OPS-M04-007）：新密钥作为 current + 旧密钥作为 previous，滚动重启两侧 → 用 rewrap 工具把所有 `tenant_data_key` 重新包裹并更新 `master_key_id` → 校验 → 移除 previous。缺少 rewrap 工具时，M04 只能承诺“在 previous 密钥存在期间可轮换”，不能承诺完成轮换。

### 5.3 出网点与重启

- 端点新增 = `env 更新 + DB model_provider/model 种子 + API/Worker 重启`；DB 行由部署种子创建，应用**不自动建行**（ADR §2.1），所以漏种子只会得到 `allowed=false` + 启动告警，运行时 503。RUNBOOK 必须把“先种子、再 env、再重启”写成固定顺序，并在重启后核对 `/admin/model-providers` 的 `allowed`。
- 应用层校验（M04）与网络策略（M09）分工要写在 RUNBOOK 的“出网”一节：M04 只保证“不调用未授权端点”，**不保证** Worker 无法访问其他内网地址；在 M09 网络策略落地前，生产只能靠客户网络隔离 + DNS 解析检查兜底（残余 R-23）。
- 重定向：`Redirect.NEVER` 是硬要求，3xx 计入 `INVALID_RESPONSE`；这条必须在 E2E 假 provider 里有专门场景（当前 `fake-model.mjs` 只有 ok/slow/error 三态，B8 要扩 302/307、401、429、断流、缺 usage）。

## 6. 可观测性

目标 → 落点（当前全部缺失，见 OPS-M04-002）：

| 需求（AC-17/§10.6）   | 指标/日志                                         | 落点                          | 状态                                   |
| --------------------- | ------------------------------------------------- | ----------------------------- | -------------------------------------- |
| 降级次数              | `model_failover_total{from,to,reason}`            | Worker `/actuator/prometheus` | 缺 registry/暴露                       |
| provider/model 错误率 | `model_requests_total{provider,model,outcome}`    | Worker                        | 缺                                     |
| 配额拒绝              | `quota_rejections_total{scope,window}`            | API                           | 缺                                     |
| 当前并发 run          | `agent_runs_inflight`（gauge）                    | Worker                        | 缺                                     |
| key 冷却/失效         | `model_credential_invalid_total`/`cooldown_total` | Worker                        | 缺                                     |
| 成本查询 p95          | `cost_report_queries_total` + HTTP server timer   | API                           | 缺（且无 `http.server.requests` 暴露） |
| 配额存储失败          | `quota_store_failure_total`                       | API                           | 缺                                     |
| 审计与业务日志分离    | 结构化日志（traceId 字段）                        | 两服务                        | 无 logback 配置，无 JSON encoder       |
| traceId 贯穿          | MDC/日志 + `ProblemDetail.traceId`                | 两服务                        | 有 traceId 字段，但无日志格式约束      |

告警规则（M09 接入监控）建议先写成 RUNBOOK 里的阈值表，不要求 M04 交付 Alertmanager 配置：降级率 >20%/5min、`model_chain_empty_total > 0`、`model_credential_invalid_total` 增长、配额拒绝突增、`cost_report` p95 >2s、`quota_store_failure_total > 0`。

日志红线（B4/B6 实现时必须有用例，`SEC-M04-005`）：只允许 `providerCode/modelId/credentialId/errorCode/attempt/traceId`；禁止 `Authorization`、明文、密文、nonce、`baseUrl`、prompt、上游响应体。建议再加一条 CI 级 grep 守卫（`security` 任务里扫 `logger.*apiKey` 之类），但当前没有 trivy/SAST job。

## 7. 性能/负载

- **脚本**：`e2e/perf/mvc-smoke.js` 不存在（F-007）；`commands.perf` 现在必然失败。
- **场景**（AC-17 + PERF-M04-001/002）：
  1. 基线：50 并发 J1 run，假 provider 正常，测首字 p95 ≤3s、API p95 ≤500ms；
  2. 故障矩阵：首字前 timeout/500/429/401/断流，备用可用 → 成功率 100%，无重复帧；
  3. 配额争用：剩余 1 次 + 50 并发 → 恰好 1 成功、49 个 429；
  4. 成本查询：30 天（默认）与 180 天（上限）各一次，p95 ≤2s（ADR §10.6 告警阈值）/ DB 3s 超时；
  5. 链空与开关：`routingMode=BASELINE`、`failoverEnabled=false` 下 J1/J2 仍可用。
- **环境**：当前 compose 只有 MySQL/OpenSearch/RustFS，无 API/Worker 服务；50 并发需要 `RICCON_CHAT_MAX_STREAMS=50`（默认 16）、`RICCON_DB_POOL_SIZE>=25`、`RICCON_FAKE_MODEL=1`、`RICCON_MODEL_DEPLOYMENT_MODE=DEMO`。报告必须记录主机规格、镜像 tag、env 快照、k6 版本与脚本 sha。合同 `sandbox.limits`（2 CPU / 4Gi）不足以同时跑 OpenSearch + MySQL + 两个 JVM + 50 并发——J1 场景应允许不启 OpenSearch（或明确 perf 主机规格另计），否则 p95 数字没有意义。
- **门禁**：`PERF-M04-001/002` 只能由 k6 脚本 + 退出码判定；CI 当前无 job、`ci.yml` 无写范围，见 OPS-M04-009。

## 8. Findings

P0：

- **OPS-M04-001** V11 CHECK 顺序自相矛盾（迁移必失败、回滚 SQL 反向必失败）。
- **OPS-M04-002** 可观测性无落点、无 owner（AC-17 不可验收）。

P1：

- **OPS-M04-003** `model_invocation` ADD CHECK = COPY（阻塞写 + 全表重建），不是 online DDL。
- **OPS-M04-004** 回滚恢复单 key 唯一键在多 key 数据上必然 1062。
- **OPS-M04-005** `spring.flyway.ignore-future-migrations` 在 Flyway 12 已移除，回滚开关无效。
- **OPS-M04-006** 发布顺序缺一次性迁移 Job 与 preflight；“必须停旧 Worker”的理由不成立。
- **OPS-M04-007** API 主密钥在测试/本地/真实栈无落点；轮换 rewrap 无工具无 owner。
- **OPS-M04-008** DEMO/PRODUCTION 端点配置无落点；API/Worker 漂移可绕过生产禁用演示端点。
- **OPS-M04-009** 性能门禁不可执行（脚本缺、CI 缺、50 并发与默认限流冲突、资源预算未定义）。
- **OPS-M04-010** 生产加端点/加模型/改价格无工具，唯一 seed 脚本会删表。
- **OPS-M04-011** 备份必须与主密钥同生命周期；无恢复演练与 RPO/RTO。
- **OPS-M04-012** `cost_day` 全表回填 + 冗余 `cost_status` 全表 UPDATE。

P2：

- **OPS-M04-013** 成本报告 `cost_day IS NULL` 语义未定义（回滚窗口调用静默消失）。
- **OPS-M04-014** 配额准入的锁等待/死锁与“不得自动重试”未定义，单租户热点行。
- **OPS-M04-015** 索引重叠、隐式 FK 索引、attempt/quota 容量与清理归属未量化。
- **OPS-M04-016** 回滚副作用：旧 Worker 选 key、schema 校验关闭无日志、种子失去幂等。
- **OPS-M04-017** `commands.run/deploy_preview/deploy_prod` 指向不存在的文件，M04 不能据此取证。

P3：

- **OPS-M04-018** 开关 env 覆盖审计噪音、两套 max-attempts 命名冲突、币种不可变未声明。

每条的影响/修复/验证方式见 front matter 的 `summary`/`fix`/`evidence`。

## 9. 拆分评估（M04a / M04b）

**支持拆分，但不是因为迁移。** 从运维角度：

| 维度     | 单模块（一次发布）                                        | 拆分（两次发布）                                                     |
| -------- | --------------------------------------------------------- | -------------------------------------------------------------------- |
| 迁移窗口 | 一次大窗口：V11 全部对象 + 大表 DDL                       | 同样一次大窗口（V11 在 M04a 全建），M04b 无 schema 变更 → 可滚动发布 |
| 回滚     | 应用回滚一次，但回滚 SQL 必须同时处理多 key + 配额/成本表 | M04a 回滚 = OPS-M04-001/004 的问题；M04b 回滚 = 纯应用回滚（最干净） |
| 验证     | 一次 gate 覆盖 18 AC，压力集中，容易压缩 E2E/perf         | AC-17/18 跨模块，必须合并取证；两次 Product/用户验收                 |
| 风险     | 24h 内很难同时做完 DDL 评估 + 回滚演练 + perf + RUNBOOK   | 第一窗口更小、可先止血；第二窗口不再动 schema                        |
| 暗数据   | 无                                                        | 配额/成本表在 M04a 上线后为空闲状态，直到 M04b 才被使用（可能数周）  |

结论与条件：

1. **拆**（按 ADR §8.4 边界），但 V11 必须在 M04a 一次写完并且**修好 OPS-M04-001/003/004/012** 之后再冻结；M04b “无 schema 变更”只有在 V11 完全正确时才成立，否则就是 V12 + 又一次迁移窗口。
2. M04a 的 gate 必须对配额/成本表做**结构和读写冒烟**（不要求业务完整）：保证 M04b 不会因为一张建错的表再用 V12 返工。
3. M04a 的 gate 只能声称 AC-17/18 的子集通过；两个评审包都要写“AC → 子集 → 证据”，否则用户会在 M04a 验收时误以为 18 条全过。
4. M04b 上线前必须补一次“M04a 生产配置漂移”检查：V11 种的开关（`quotaEnforcementEnabled=true`）在 M04a 期间无人读取，M04b 一上线即生效——**默认开启配额执行**意味着 M04b 发布当天就可能开始拒绝请求。必须在 M04b 发布前确认默认限额与租户实际用量，或 M04b 首发把开关置 false，验证后再打开（这需要 Architect 明确写进 M04b 的发布步骤）。

## 10. 交给 Architect / Lead 的最小修复清单

Architect（改 ADR-0021/0022 与 ARCHITECTURE 对应段）：

1. 重写 §3.2 迁移顺序（先 DROP 旧 CHECK），修正 §10.3 回滚 SQL（先 DROP 新 CHECK、先收敛 key、再恢复唯一键）。
2. 在 §10.1 写明 `model_invocation` 的 ADD CHECK = COPY（阻塞写）、索引=INPLACE、列=INSTANT，并给出“超过阈值单独窗口”的判据。
3. 把 `ignore-future-migrations` 换成 `ignore-migration-patterns=*:future`，并说明 Boot 默认已生效。
4. 发布顺序改为“preflight → 备份 → 一次性迁移 Job → 新 API → 新 Worker”，并修正“停旧 Worker”的理由。
5. 补“api 与 worker 端点/模式配置必须一致”的约束与校验方式。
6. 明确 cost_day NULL / 回滚窗口成本口径（选一个方案写死）。
7. 拆分时写明 M04b 发布当天配额开关的默认值与打开顺序。

Lead（改任务图与写范围）：

8. 给某个任务加 `micrometer-registry-prometheus` + actuator 暴露（pom.xml 与两处 application.yml），否则 AC-17 不可验收。
9. 给 B8/B10 增补 `.github/workflows/ci.yml` 的 perf/security job 写范围，或明确 M04 只用人工 k6 + 证据（后者需在评审包承认 CI 不覆盖）。
10. B10 验收增加：生产幂等种子/价格脚本、master-key rewrap 工具、备份恢复演练、指标落点表；B1b 验收增加 API 主密钥 + previous + canary 与 `application-test.yml`。
11. B8 验收增加 run.mjs 的 API 主密钥注入、DEMO/端点 env、假 provider 的 302/401/429/断流/缺 usage 场景。

## 11. 未验证与剩余风险

- 未运行 V11（不存在）、未跑 `mvnw verify`/k6/E2E/真实栈；因此不对功能正确性、覆盖率、延迟作判断。
- 未验证真实生产规模下 `model_invocation` 的行数与 COPY 窗口时长；OPS-M04-003 的严重度随表大小上升，升级前必须实测。
- 未验证客户 K8s 的出网策略与 Secret 注入方式（M09）；M04 只保证应用层校验。
- 未验证 `RICCON_MASTER_KEY` 在客户密钥库中的轮换能力；若客户不支持双密钥并存，主密钥轮换在 M04 不可行，需在 DEC-0024 中明确。
- 本评审不代替 Lead/用户/Verifier 签字，也不构成生产发布批准。
