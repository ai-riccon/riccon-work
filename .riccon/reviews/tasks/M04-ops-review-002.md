---
review_id: REV-M04-OPS-002
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-OPS-001
reviewer:
  agent: riccon/ops
  session: ses_e095e5ccdffeNNYMh1JrEpht13
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
scope: "M04 架构修订第二轮独立运维复审（focused）：OPS-M04-001..012 关闭情况、R-02/R-03 角色/CI/契约边界、M04/M17 拆分后的迁移-发布-回滚-开关、OPS-M04-017 deferred；只读设计文档，只写本评审文件"
created_at: "2026-11-20T10:12:00+08:00"
findings:
  - id: OPS-M04-001
    severity: P0
    status: fixed
    owner: riccon/architect
    title: V11 的 CHECK 收紧顺序自相矛盾（已修正）
    summary: "§3.2 已改为 ①加列 INSTANT ②DROP 旧 CHECK ③RETIRED→DISABLED + fingerprint 回填 ④新唯一键/索引/新 CHECK ⑤删除旧唯一键；§10.3 回滚先 DROP 三个新 CHECK。本地 mysql:8.4.13 按原文顺序复跑成功。"
    evidence: "ADR-0021:163-199；探针 P1：旧 CHECK 下 UPDATE RETIRED→DISABLED 报 ERROR 3819；DROP CHECK 后同一 UPDATE 成功；新 CHECK 下 UPDATE status='RETIRED' 报 3819（预期）"
  - id: OPS-M04-002
    severity: P0
    status: fixed
    owner: riccon/lead + riccon/architect
    title: 可观测性无落点也无 owner（已补 B1c/B2/B10）
    summary: "新增 M04-B1c（owner riccon/ops）：两应用 pom 加 micrometer-registry-prometheus、9 指标名与标签约定、scripts/ops/metrics-check、默认拒绝回归测试；Actuator 暴露归 B2，告警阈值归 B10；THREAT-MODEL 新增 B-20/TM-54 作为 gate 阻断项。落地细节仍有缺口，见 OPS-M04-023/024/029。"
    evidence: "ADR-0021 §8.1/§8.3 B1c、§10.6；§13 矩阵 OPS-M04-002；THREAT-MODEL:563,626"
  - id: OPS-M04-003
    severity: P1
    status: fixed
    owner: riccon/architect
    title: model_invocation 的 ADD CHECK 只能 COPY（已改为不加 CHECK + 应用层校验）
    summary: "§3.3/§10.1 明确 model_invocation 不加 CHECK，只保留可空/带默认列；新增小表继续用 CHECK；给出 >200 万行或 >2 GiB 的维护窗口判据。探针 P5 确认“有 FK + 已有 CHECK 的表”仍可 10 列一次性 ALGORITHM=INSTANT。"
    evidence: "ADR-0021:210-276、:718-724；探针 P5 成功（列数 15）"
  - id: OPS-M04-004
    severity: P1
    status: fixed
    owner: riccon/architect
    title: 回滚恢复单 key 唯一键在多 key 数据上 1062（已加收敛/归档）
    summary: "§10.3 第 2 步先按“优先 ACTIVE、priority ASC、created_at DESC、id DESC”收敛并归档多余行，再恢复旧唯一键。探针 P2 验证：收敛后恢复 uk_provider_credential 不再 1062。但收敛后的状态归一仍有新缺陷（见 OPS-M04-020）与说明缺口（OPS-M04-026）。"
    evidence: "ADR-0021:759-770；探针 P2：3 行→保留 3 行(按 tenant/provider 分组)、归档 1 行、旧唯一键创建成功"
  - id: OPS-M04-005
    severity: P1
    status: fixed
    owner: riccon/architect
    title: ignore-future-migrations 在 Flyway 12 不存在（已改 ignore-migration-patterns）
    summary: "§10.3 明确 Boot 4.1.2 默认 spring.flyway.ignore-migration-patterns=*:future 且旧开关无效，并要求旧 API 镜像在 V11 库上演练 health UP + 历史调用可读。"
    evidence: "ADR-0021:746；REV-M04-OPS-001 §2 的 Flyway 12.4.0/Boot 4.1.2 版本事实"
  - id: OPS-M04-006
    severity: P1
    status: fixed
    owner: riccon/architect
    title: 发布顺序缺一次性迁移 Job 与 preflight（已改五段式）
    summary: "§10.2 改为 preflight → 备份 → 一次性迁移 Job（只跑 Flyway）→ 新 API → drain/新 Worker → 冒烟与开关；明确旧 Worker 不必在迁移前停、但 V11 后不得重启。与 RUNBOOK OPS-004 的迁移 Job 口径一致。"
    evidence: "ADR-0021:728-737；docs/RUNBOOK.md:257-268；docs/ARCHITECTURE.md:316"
  - id: OPS-M04-007
    severity: P1
    status: fixed
    owner: riccon/lead + riccon/builder (B1b) + riccon/ops
    title: API 主密钥/previous/rewrap 无落点（已补 B1a/B1b/B8/B10）
    summary: "§6.5 规定 API 与 Worker 都注入 current/previous/master-key-id + 启动 canary；B1a 拥有 application-test.yml 与两应用 application.yml 配置键；B8 给 API/Worker 注入同一 masterKey；B10 交付 scripts/ops/rewrap-dek 与四步轮换 + restore-drill/RPO-RTO。残留：Worker 共享测试基座无 owner，见 OPS-M04-008。"
    evidence: "ADR-0021:462-470、§8.3 B1a/B1b/B8/B10；scripts/real-stack/run.mjs:214-236（API env 现状仍缺 master key，由 B8 修）"
  - id: OPS-M04-008
    severity: P1
    status: open
    owner: riccon/lead + riccon/builder (B1a/B1b)
    title: DEMO/端点配置在测试落点仍缺一处：Worker 共享测试基座无人拥有
    summary: "run.mjs（B8）、API application-test.yml（B1a）、RUNBOOK（B10）都已覆盖；但 M04 起 Worker 默认 PRODUCTION 且无 RICCON_MODEL_ENDPOINTS 时启动失败，而 apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java 的 @DynamicPropertySource 只设了 allow-insecure-http，不在任何 M04 任务的 write_scope（B1a 只写 apps/api/src/test/resources/application-test.yml）。落地后 Worker 全量测试会在上下文启动阶段失败，且修复必然发生在某个任务的范围外。"
    evidence: "ADR-0021 §2.1/§2.2、§8.3 B1a 写范围；WorkerIntegrationTestBase.java:176-205（无 deployment-mode/endpoints）；apps/worker/src/main/resources/application.yml:84-93"
  - id: OPS-M04-009
    severity: P1
    status: fixed
    owner: riccon/lead + riccon/builder (B8/B10)
    title: 性能门禁不可执行（已补脚本/CI/env）
    summary: "B8 交付 e2e/perf/** + k6 三场景 + 退出码 + env 记录（RICCON_CHAT_MAX_STREAMS=50、RICCON_DB_POOL_SIZE>=25、RICCON_FAKE_MODEL=1）；B10 独占 .github/workflows/ci.yml 并负责 perf job 或明确 manual gate。残留：主机规格/资源预算未写入验收（OPS-M04-030）。"
    evidence: "ADR-0021 §8.3 B8/B10、§11.1 PERF-M04-001/002；§8.2 配置唯一 writer 段"
  - id: OPS-M04-010
    severity: P1
    status: fixed
    owner: riccon/ops
    title: 生产加端点/加模型/改价格无工具（已补幂等脚本）
    summary: "§10.2 规定新增端点=更新 env + scripts/ops/seed-model-catalog.mjs（幂等 upsert、不 DELETE、不含密钥）+ 重启；update-model-price.mjs 只改目录价格、不改历史快照；B10 验收含幂等种子/价格流程。"
    evidence: "ADR-0021:739；§8.3 B10"
  - id: OPS-M04-011
    severity: P1
    status: fixed
    owner: riccon/architect + riccon/ops
    title: 备份必须与主密钥同生命周期（已补）
    summary: "§6.5/§10.2 规定备份记录 master_key_id、密钥保留期 ≥ 备份保留期、密钥本体不进备份；B10 交付 restore-drill（校验表数/触发器/一行 credential 可解密/J1 冒烟）与 RPO/RTO。"
    evidence: "ADR-0021:469、:731；§8.3 B10"
  - id: OPS-M04-012
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops
    title: cost_day 分批回填仍无可执行落点
    summary: "冗余的 UPDATE cost_status 已删除，也写了“每批 50,000 行、单独提交、记录进度”。但 V11 里仍是一条 UPDATE ... LIMIT 50000，而“循环由迁移 Job 在 Flyway 外执行”与 §10.2“迁移 Job 只执行 Flyway（不启 Web 端口）”互相矛盾：单条 LIMIT 语句只能回填 5 万行，超过即留下 NULL；随后结构校验（cost_day IS NULL=0）在 DDL 已提交后失败，回填脚本无 owner。"
    evidence: "ADR-0021:243-248、:732；探针 P3：3 行数据执行一次 LIMIT 2 后仍有 1 行 NULL；V11 迁移测试种子只有 1 万行 → 单批即“全绿”"
  - id: OPS-M04-019
    severity: P0
    status: open
    owner: riccon/architect + riccon/builder (B1a)
    title: V11 的 ADD FOREIGN KEY 显式 ALGORITHM=INPLACE 在 MySQL 8.4.13 必然失败
    summary: "§3.3 第 3 步 `ALTER TABLE model_invocation ADD CONSTRAINT fk_model_invocation_provider ... ALGORITHM = INPLACE, LOCK = NONE` 在 foreign_key_checks=ON（默认）下报 ERROR 1846：加外键必须 foreign_key_checks=OFF 或 ALGORITHM=COPY。V11 会在此语句中断，而前面的加列（INSTANT）与 4 条索引（INPLACE）已各自提交——MySQL DDL 非事务，留下“列/索引已加、无 FK、history 无 11”的半成品。ADR §10.1 的“索引 INPLACE、不阻塞写”结论对 FK 不成立。"
    evidence: "ADR-0021:238-241；探针 P4（mysql 8.4.13，有 1 行数据与空表各一次）：`ERROR 1846 (0A000): ALGORITHM=INPLACE is not supported. Reason: Adding foreign keys needs foreign_key_checks=OFF. Try ALGORITHM=COPY.`；同探针：SET foreign_key_checks=0 后同语句成功，或不写 ALGORITHM 时以 COPY 成功"
  - id: OPS-M04-020
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops
    title: 回滚 SQL 把所有 key 置 RETIRED，回滚后没有任何 ACTIVE key
    summary: "§10.3 第 3 步 `UPDATE provider_credential SET status = 'RETIRED';` 无 WHERE，把收敛后保留的那把 ACTIVE key 也改成 RETIRED。与同节叙述“回滚前必须收敛到一把 ACTIVE key”“旧 Worker 仍可服务”直接冲突；旧 Worker 的 findActiveCredential 只取 status='ACTIVE'，回滚后模型调用全线失败。回滚演练若只断言行数/旧唯一键，会得到假绿。"
    evidence: "ADR-0021:748、:759-764；探针 P2：执行第 3 步后 `SELECT COUNT(*) ... status='ACTIVE'` = 0，旧 Worker 可见凭据 0 行；改为 WHERE status IN ('DISABLED','INVALID') 后保留 1 把 ACTIVE"
  - id: OPS-M04-021
    severity: P1
    status: open
    owner: riccon/architect + riccon/builder (B1a)
    title: V11 迁移测试种子在 V10 schema 上不可构造（2 ACTIVE 同 provider / INVALID 行）
    summary: "§3.2/B1a 验收要求“V10 种子含 1 行 RETIRED、2 行 ACTIVE、1 行 INVALID key”。V10 的 uk_provider_credential 禁止同一 (tenant,provider,type) 出现第二把 ACTIVE，CHECK 也只允许 ACTIVE|RETIRED，因此 INVALID 行在 V11 之前无法插入；2 把 ACTIVE 只能跨 provider/租户构造。按原文写种子会得到 1062 或 3819，构建者很可能改为弱化种子（牺牲多 key 回滚覆盖）。"
    evidence: "ADR-0021:205、§8.3 B1a；V5:99（旧 CHECK）、V5:96（旧唯一键）；探针 P1 种子阶段：同 provider 两把 ACTIVE → `ERROR 1062 Duplicate entry ... uk_provider_credential`"
  - id: OPS-M04-022
    severity: P1
    status: open
    owner: riccon/lead + riccon/architect
    title: M17 第二次发布没有 ops 任务/运行手册 owner，且 V11 把配额开关种子为 true
    summary: "§8.4 要求“M17 首发先把 quotaEnforcementEnabled 置 false、写入 B10 RUNBOOK 与 M17 评审包”，但：①B10 是 M04 任务，验收清单不含 M17 发布章节、开关 false→true 演练、配置漂移检查、成本对账窗口；②M17 六个任务（B6/B7/B12/B9b/B11b/V2）没有 ops owner，RUNBOOK 无人可写；③V11 仍种 true，M04 期间无人读取，M17 一上线即生效，flip 步骤漏做即“上线即拒绝”。"
    evidence: "ADR-0021:691、§8.3 B10 验收、§8.1 任务表；ADR-0021:300-303（种子 true）；docs/RUNBOOK.md 现无 M04/M17 章节"
  - id: OPS-M04-023
    severity: P1
    status: open
    owner: riccon/architect + riccon/ops (B1c)
    title: 9 个指标名/标签违反已生效的 ADR-0008 约定（无 riccon_ 前缀、tenant 高基数标签）
    summary: "ADR-0008 §6 已定义 riccon_* 前缀，并明确“标签禁止包含 tenantId/userId/runId（高基数）；按租户排查用日志与审计”。ADR-0021 §10.6 给出的 9 个指标全部无前缀（model_requests_total 与既有 riccon_model_requests_total 同名不同义），且 quota_usage_ratio{tenant,scope,window} 直接引入租户标签：万元级租户 × 4 系列会打爆 Prometheus，并把租户代码暴露给任何能抓取该端点的人。B1c 的 metrics-check 会把这套名字固化成 gate 证据（B-20）。"
    evidence: "docs/adr/ADR-0008-mysql-outbox-async-and-observability.md:82-97（riccon_* 前缀 + “标签禁止包含 tenantId/userId/runId”）；ADR-0021:812-824；ADR-0022:98-128（quota_over_limit_observed_total 也未列入 9 指标）"
  - id: OPS-M04-024
    severity: P2
    status: open
    owner: riccon/builder (B2) + riccon/ops
    title: /actuator/prometheus 的“仅内网/受控”在 M04 无可执行控制
    summary: "现有两份 SecurityConfig 只放行 /actuator/health，其余默认拒绝；B2 为暴露 prometheus 必须新增放行规则，但 ADR 只写“仅在集群内/ops 网络”，没有应用层令牌、独立 management 端口或来源限制，也没有声明 ingress/网络策略归 M09。B2 验收“仅内网/受控”无法在应用内取证。"
    evidence: "apps/api/.../config/SecurityConfig.java:112-129；apps/worker/.../config/SecurityConfig.java:29-36；ADR-0021:823；TM-53"
  - id: OPS-M04-025
    severity: P2
    status: open
    owner: riccon/builder (B1a) + riccon/ops (B10)
    title: preflight 依赖的 /actuator/info 指纹没有键名与 owner
    summary: "§2.1 要求 preflight 比对两侧 /actuator/info 的 mode/endpoint code/baseUrl host/currency 指纹，§10.5 还要求 info 暴露生效开关。但没有任何任务写明 info contributor（可用 management.info.env.enabled + info.* 纯 YAML 实现，属 B1a 的 application.yml 范围）或键名，B10 的 preflight 无从实现，最终会退化为人工比对 env。"
    evidence: "ADR-0021:86、:807；§8.3 B1a/B10 验收均未提及 /actuator/info 键名"
  - id: OPS-M04-026
    severity: P2
    status: open
    owner: riccon/architect + riccon/ops
    title: 回滚 SQL 仍不完整：归档表无 DDL、flyway_schema_history 的 V11 行未处理
    summary: "§10.3 第 2 步引用 provider_credential_rollback_archive（同结构 + archived_at）但全仓库没有该表定义，演练前必须先建；与 V10 OPS-007 不同，V11 回滚/灾难恢复后没有“删除 history 的 11 行并校验 MAX(version)=10”的步骤，库会声称 V11 已应用而对象已删。"
    evidence: "ADR-0021:761、:749-786；docs/RUNBOOK.md:297-310（V10 OPS-007 有该步骤）"
  - id: OPS-M04-027
    severity: P2
    status: open
    owner: riccon/builder (B1a/B5)
    title: 历史 fingerprint 用 SHA2 回填，与 HMAC(pepper) 去重体系不互通
    summary: "§3.2 第 3 步给历史行写 SHA2('legacy:'||HEX(id))，而新建/轮换计算 HMAC-SHA-256(pepper, provider_id||key)。同一把历史 key 再次被添加时 fingerprint 永不相等，去重/409 失效，同一 key 可被重复录入多行。"
    evidence: "ADR-0021:185、:202-203；探针 P1 结果：回填后 fingerprint 无 NULL，但算法与 §6.5 的 HMAC 不同"
  - id: OPS-M04-028
    severity: P2
    status: open
    owner: riccon/lead + riccon/verifier
    title: M04 gate 的 quota/cost 冒烟未绑定 B6 的真实 SQL 形状
    summary: "模块 acceptance 只要求“quota_limit/quota_usage/model_invocation_attempt 空表 INSERT/ROLLBACK + FK/CHECK 断言”。M17 之所以能承诺“不改 schema”，前提是 V11 的主键顺序与 B6 的 INSERT ... ON DUPLICATE KEY UPDATE used_count=used_count / 条件 UPDATE 完全匹配；泛化 INSERT 抓不到 PK 顺序、默认值、affectedRows 语义不符。"
    evidence: "ADR-0021:690；ADR-0022:96-118；§8.4 M04 模块 acceptance"
  - id: OPS-M04-029
    severity: P2
    status: open
    owner: riccon/lead
    title: B1c/B2 的测试写范围用“对应 src/test/**”互相重叠，且 B2 的暴露验收早于 registry 依赖
    summary: "B1c 与 B2 都写“对应 src/test/**”，并行派单时会落到同一目录，违反“一个文件一个 writer”；B2 只依赖 B1a，却要验收 /actuator/prometheus 受控暴露，而 registry 依赖由并行的 B1c 提供——B2 先完成时该端点不存在，验收无法执行。"
    evidence: "ADR-0021 §8.2 依赖图、§8.3 B1c/B2 写范围与验收"
  - id: OPS-M04-030
    severity: P2
    status: open
    owner: riccon/builder (B8) + riccon/ops
    title: 性能证据未要求记录主机规格/镜像/启动方式，2C4Gi 沙箱与 50 并发冲突未解决
    summary: "B8 验收只写“k6 三场景+成本查询+退出码+env 记录”。AC-17 的 p95 判定必须绑定主机规格、镜像 tag、k6 版本、脚本 sha 与启动命令；合同 sandbox.limits（2 CPU/4Gi）无法同时承载 MySQL+OpenSearch+两个 JVM+50 并发，ADR 未声明 perf 场景的主机规格或“不启 OpenSearch”的口径。"
    evidence: "ADR-0021:658、§11.1 PERF-M04-001；AGENTS.md sandbox.limits；REV-M04-OPS-001 §7"
  - id: OPS-M04-031
    severity: P2
    status: open
    owner: riccon/lead
    title: B2 验收依赖尚未答复的 DEC-0024（契约分段），关键路径可能被决策阻塞
    summary: "B2 的验收含“DEC-0024 后、M04 gate 前完成 1.3.0-m04a 分段”，而 DEC-0024 仍未创建/答复。若决策晚于 B2 完成，B2 无法 done，M04 关键路径 B1a→B2→B3→B4→B11a→V1 直接停摆。Lead 应在派 B2 前拿到 DEC-0024。"
    evidence: "ADR-0021:581、§8.3 B2；DEC-0024 未出现在 .riccon/decisions（第一轮评审已记录）"
  - id: OPS-M04-032
    severity: P3
    status: open
    owner: riccon/lead
    title: B10 的 deploy/** 写范围在 M04 没有交付物
    summary: "deploy 下只有 docker-compose.test.yml，deploy_preview/deploy_prod 归 M09。B10 写范围含 deploy/** 但验收无对应项，容易产生“为了填满范围”的顺手改动；建议收窄或在任务卡注明 M04 不做部署物。"
    evidence: "ADR-0021 §8.3 B10；`ls deploy/compose` 仅 docker-compose.test.yml；AGENTS.md commands.deploy_preview/deploy_prod"
  - id: OPS-M04-033
    severity: P3
    status: open
    owner: riccon/architect
    title: pepper 的“缺失即失败”与“丢失只影响去重”语义未统一
    summary: "§6.5 同时写“API 启动校验必需（PRODUCTION 缺失即启动失败）”和“丢失 pepper 只影响新建 key 的去重”。DEMO/测试是否有开发默认 pepper 未定义，B1a/B1b/B8 三个任务对同一 Secret 的注入口径可能不一致。"
    evidence: "ADR-0021:202、:467；§8.3 B1a/B1b/B8"
  - id: OPS-M04-034
    severity: P3
    status: open
    owner: riccon/architect + riccon/ops
    title: V11 的失败恢复矩阵比 V10 OPS-006 薄
    summary: "§10.4 对 V11 只有一行“迁移 Job 中断 → repair + 按残留清单补齐”。V11 有 10+ 条非事务 DDL（加列/索引/FK/唯一键替换/数据归一），应按 V10 OPS-006 给出“失败点 → 残留 → 恢复”表，并写明 provider_credential 半迁移（旧 CHECK 已删、新 CHECK 未建）时旧应用能否继续服务。"
    evidence: "ADR-0021:789-798；docs/RUNBOOK.md:288-295（V10 OPS-006 矩阵）"
notes: "第一轮 P0 全部关闭；本轮新增 1×P0（V11 外键 DDL 必失败）、4×P1（回滚置空 ACTIVE、迁移测试种子不可构造、M17 发布无 ops owner、指标命名/标签），并有 2×P1 残留（008 Worker 测试基座、012 cost_day 回填落点）。复审为设计阶段只读，未运行 build/test/e2e/perf。"
---

# REV-M04-OPS-002：M04 架构修订 — 独立运维复审（第二轮，focused）

## 1. 结论

**verdict: fail（ADR-0021/0022 不能进入 `accepted`，M04 不能进入实施）**

第一轮的两个 P0 与 10 个 P1 中，9 个 P1 已实质关闭，修订方向正确；但复核发现**一个新的 P0** 与**四个新的/残留的 P1**：

1. **OPS-M04-019（P0）**：V11 的 `ADD FOREIGN KEY ... ALGORITHM=INPLACE, LOCK=NONE` 在 MySQL 8.4.13 默认配置下必然报 `ERROR 1846`。V11 会稳定在“列与索引已提交、无 FK、history 无 11”的半迁移状态。
2. **OPS-M04-020（P1）**：回滚 SQL 无 `WHERE` 地把所有 key 置 `RETIRED`，回滚后旧 Worker 找不到任何 ACTIVE key——与同节“收敛到一把 ACTIVE 再回滚”的叙述矛盾。
3. **OPS-M04-021（P1）**：V11 迁移测试种子（2 ACTIVE 同 provider + 1 INVALID）在 V10 schema 上不可构造，最该抓 001/004 的测试会被迫弱化。
4. **OPS-M04-012（P1，残留）**：`cost_day` 分批回填没有可执行落点，单条 `LIMIT 50000` 留在 V11，超过 5 万行即在结构校验阶段失败。
5. **OPS-M04-022（P1）**：M17 第二次发布没有 ops 任务/运行手册 owner，且 V11 把 `quotaEnforcementEnabled` 种成 `true`。
6. **OPS-M04-023（P1）**：9 个指标名/标签违反已生效的 ADR-0008（无 `riccon_` 前缀；`quota_usage_ratio{tenant}` 高基数且泄露租户代码）。

这些都能用很小的改动关闭（删/改 1 条 DDL、加 1 个 WHERE、拆一次种子、移一段脚本、改指标名、加 1 条任务验收），但必须在本轮修掉再复评。

**是否建议直接开工：不建议。** OPS-M04-019 决定 V11 能否跑完，OPS-M04-021 决定测试能否证明它跑对了。

## 2. 复审方法与本轮证据

只读设计文档，未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/源码/测试/迁移，未创建任务/决策/模块，未提交 git。撰写时工作区仍是第一轮同一批未提交设计改动（`head_commit=d800410a`）；评审结束前另一会话开始并发写入 `.riccon/state.json` 与 `M04-architect-review-002.md`，与本评审无关。

MySQL 探针（一次性 `mysql:8.4.13` 容器，`--rm` 销毁；只验证 DDL 语义，**不是** V11 本身）：

| 探针 | 内容                                                                                          | 结果                                                                                                                                                 |
| ---- | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| P1   | 按 §3.2 五步跑 `provider_credential`（旧唯一键 + 旧 CHECK + 1 ACTIVE×2 provider + 1 RETIRED） | 旧 CHECK 下归一 → `ERROR 3819`；DROP 后成功；新 CHECK 拒绝 `RETIRED`；故意插同 provider 第二把 ACTIVE → `ERROR 1062`                                 |
| P2   | 先插入 V11 后的多 key 数据，再按 §10.3 原文回滚                                               | 收敛/归档/旧唯一键恢复成功；第 3 步后 **ACTIVE key = 0**，旧 Worker 可见凭据 0 行                                                                    |
| P3   | `cost_day` 单条 `UPDATE ... LIMIT 2`（3 行数据）                                              | 回填 2 行，**仍有 1 行 NULL**                                                                                                                        |
| P4   | `ADD CONSTRAINT fk_model_invocation_provider ... ALGORITHM=INPLACE, LOCK=NONE`                | **`ERROR 1846 (0A000)`：Adding foreign keys needs foreign_key_checks=OFF**；`SET foreign_key_checks=0` 后同语句成功，或不写 ALGORITHM 时以 COPY 成功 |
| P5   | 有 FK + 已有 CHECK 的表，10 列 `ADD COLUMN ... ALGORITHM=INSTANT`                             | 成功（15 列），确认 §3.3 第 1 步可执行                                                                                                               |

## 3. OPS-M04-001..012 关闭表

| ID  | 严重度 | 状态      | 结论与依据                                                                                                                                     |
| --- | ------ | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 001 | P0     | **fixed** | §3.2 五步顺序正确；探针 P1 正向复现通过。回滚侧改为先 DROP 三个新 CHECK。                                                                      |
| 002 | P0     | **fixed** | 新增 B1c（registry/9 指标/metrics-check/默认拒绝回归），暴露归 B2，阈值归 B10，TM-54/B-20 作为 gate 阻断项。命名/暴露细节见 023/024。          |
| 003 | P1     | **fixed** | `model_invocation` 不加 CHECK；列 INSTANT、索引 INPLACE/LOCK=NONE、FK 单独语句、大表判据齐全。**但 FK 的算法声明错误（019）**。                |
| 004 | P1     | **fixed** | 收敛/归档规则写死，探针 P2 确认 1062 消失；状态归一的副作用另立 020。                                                                          |
| 005 | P1     | **fixed** | `ignore-migration-patterns=*:future`（Boot 默认）并列入旧 API 演练。                                                                           |
| 006 | P1     | **fixed** | 五段式与 RUNBOOK OPS-004 一致；旧 Worker 只要求“迁移后不得重启”。                                                                              |
| 007 | P1     | **fixed** | API current/previous/id/canary、test profile、run.mjs、rewrap、备份生命周期齐备；Worker 测试基座残留计入 008。                                 |
| 008 | P1     | **open**  | run.mjs / API test profile / RUNBOOK 已覆盖；`WorkerIntegrationTestBase` 无 owner，M04 起 Worker 测试上下文会因“PRODUCTION + 无端点”启动失败。 |
| 009 | P1     | **fixed** | B8 脚本 + 50 并发 env，B10 独占 ci.yml；主机规格/资源预算残留见 030。                                                                          |
| 010 | P1     | **fixed** | `seed-model-catalog` / `update-model-price` + 四条运维流程。                                                                                   |
| 011 | P1     | **fixed** | 备份记录 master_key_id、密钥保留期对齐、restore-drill + RPO/RTO。                                                                              |
| 012 | P1     | **open**  | 冗余 `UPDATE cost_status` 已删；分批回填仍无可执行落点（探针 P3）。                                                                            |

P2/P3 复核（不改变 verdict，详见 front matter）：013 已用 `unbucketedCalls` 关闭；014 改 `ON DUPLICATE KEY UPDATE` + 无自动重试 + 指标；015 删重叠索引/显式 provider 索引/清理顺序；016 收敛 + WARN 工单 + 种子幂等；017 仍 deferred（§7）；018 env 仅实际变化才审计 + `routing.*` 统一 + 币种不可变。

## 4. R-02 / R-03 角色与 CI/契约核对

| 检查点                              | 结论                                                                                                                                                                                                 |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B1c 不再写 SecurityConfig           | ✅ B1c 只有两应用 `pom.xml`、`scripts/ops/metrics-check*`、对应测试；验收明确“Actuator 暴露的 SecurityConfig 归 B2”。Ops 写 pom 属构建配置，边界可接受。                                             |
| B2 写两份 SecurityConfig 是否合理   | ✅ 合理且必要：端点允许列表、SSRF 策略、默认拒绝基线都在同一安全边界内；B2 验收含“仅内网/受控且不放宽默认拒绝”。但“仅内网”本身不可在应用内证明（024），且 B2 的暴露验收早于 B1c 的 registry（029）。 |
| B10 独占 `.github/workflows/ci.yml` | ✅ 与合同“CI 属 Ops”一致；B8 只保证脚本可本地/CI 运行。B10 允许“CI perf job 或明确 manual gate”，与 T3 DoD 不冲突（`commands.perf` 仍可由 verifier 手工取证）。                                      |
| B8 只写测试资产                     | ✅ `scripts/real-stack/**`、`e2e/perf/**`、`e2e/support/fake-model/**`、`e2e/stub/server.mjs`；不碰 ci.yml、不碰业务代码。                                                                           |
| M17-B12 与 B2 契约 writer           | ✅ 顺序明确：B2 独占 M04 `1.3.0-m04a`，B12 独占 M17 `1.4.0-m04b` 且依赖 B2 完成，不并行。风险在决策时序而非写冲突（031）。                                                                           |
| 配置唯一 writer                     | ✅ `application.yml` 归 B1a，B1b/B1c/B2 不再改 yml；B4→B7、B9a→B9b 串行已注明。                                                                                                                      |
| 测试范围重叠                        | ⚠️ B1c/B2/B4/B7 都用“对应 `src/test/**`”，并行派单时需 Lead 收窄到文件级（029）。                                                                                                                    |

## 5. 拆分后的迁移/回滚/发布/开关结论

### 5.1 V11 一次建全表（M04），M17 不改 schema

方向正确：一次 DDL 窗口比两次安全，M17 变成纯应用发布。前提是 V11 必须一次写对——当前 **019/012/021** 表明还不能冻结。

V11 修正后的可执行顺序（供 B1a 实现）：

```text
0. preflight（无副作用）：Secret/preflight env/DB/表体积/磁盘；失败即停
1. CREATE TABLE 6 张新表（routing/entry/switch/attempt/quota_limit/quota_usage）
2. provider_credential：加列 INSTANT → DROP 旧 CHECK → RETIRED→DISABLED + 回填
   → 新唯一键/索引/新 CHECK → 最后 DROP 旧唯一键
3. model_invocation：加列 INSTANT → 4 条索引逐条 INPLACE/LOCK=NONE
   → FK（去掉 INPLACE 声明：要么不建 FK 走逻辑引用，要么 foreign_key_checks=0，
      要么接受 COPY 并进维护窗口）
   → cost_day 回填（移出 V11，见 5.2）
4. 权限/审计/路由/开关种子（幂等）
```

### 5.2 `cost_day` 回填的落点（OPS-M04-012）

三选一，必须写死一个：

- **推荐**：V11 不写回填；迁移 Job 在 Flyway 成功后执行 B10 的 `scripts/ops/backfill-cost-day.mjs`（`WHERE cost_day IS NULL LIMIT 50000` 循环、逐批提交、打印进度），然后才做结构校验。代价是 §10.2 第 2 步要改成“Flyway + 幂等回填”，B10 验收加该项。
- 备选：V11 保留单条**无 LIMIT** 的 `UPDATE ... WHERE cost_day IS NULL`，并明确“只在测得 <200 万行时执行，否则走脚本”。
- 不接受现状：单条 `LIMIT 50000` 在 V11 里，等于把问题推到结构校验。

### 5.3 回滚

- 应用回滚路径（旧 API 依赖 `*:future` 默认、旧 Worker 关 schema 校验）正确，且必须演练。
- 应急 SQL 需要两处修正：`UPDATE ... SET status='RETIRED' WHERE status IN ('DISABLED','INVALID')`（020）；补 `provider_credential_rollback_archive` 的建表语句与删除 `flyway_schema_history` V11 行（026）。
- 演练断言行必须包含“回滚后旧 Worker 仍能取到 1 把 ACTIVE key 并完成一次 J1 调用”，不能只断言行数与唯一键。

### 5.4 两次发布与开关

- M04：V11 + 路由/降级/多 key；`quotaEnforcementEnabled` 无读取方。
- M17：纯应用；必须在 rollout 前把开关置 `false`（建议 V11 直接种 `false`，M17 验证后 PUT `true`），并把该步骤、配置漂移检查、回滚与“混合版本成本页不可对账”写进 RUNBOOK。当前无任务承接（022）。

### 5.5 M04 gate 的 quota/cost 取证

“结构 + 最小读写冒烟”方向对，但要按 B6 的真实语句形状执行（`INSERT ... ON DUPLICATE KEY UPDATE`、条件 `UPDATE ... WHERE used_count < limit` 的 affectedRows、`quota_usage` PK 顺序），否则 M17 才发现不符就只能 V12（028）。`model_invocation_attempt` 的 FK/CHECK 与级联删除顺序也要在同一冒烟里断言。

## 6. 新发现与最小修复清单

P0：

1. **OPS-M04-019** V11 外键 DDL 必失败。修：删除该语句的 `ALGORITHM = INPLACE, LOCK = NONE`，并三选一——(a) 不建 FK（`provider_id` 与 `user_id` 一样按逻辑引用，M04 不删 provider）；(b) `SET foreign_key_checks=0` 包住该语句（历史行 provider_id 全 NULL，不可能违反）后恢复为 1；(c) 接受 COPY 并纳入大表维护窗口。同时把 §10.1 的 DDL 语义表补一行“ADD FK = COPY（除非 foreign_key_checks=OFF）”。

P1：

2. **OPS-M04-020** 回滚 SQL 加 `WHERE status IN ('DISABLED','INVALID')`，保留 ACTIVE；演练断言行加“旧 Worker 取到 ACTIVE key + J1 成功”。
3. **OPS-M04-021** 把种子拆成两段：V10 种子 = 2 ACTIVE（跨两个 provider/租户）+ 1 RETIRED + 1 万历史调用；V11 之后的回滚种子 = 再加 1 把 ACTIVE + 1 把 INVALID（验证新枚举与多 key 收敛）。
4. **OPS-M04-012** 按 §5.2 选一个落点并写进 B1a（或 B10）验收；删除 V11 里的单批语句或改为无 LIMIT 的显式窗口方案。
5. **OPS-M04-022** V11 种 `quotaEnforcementEnabled=false`；B10 验收加“M17 发布章节（flip 顺序、漂移检查、回滚、对账窗口）”；V2 验收引用该章节。否则需 Lead 明确“M17 规划时新增 ops 任务”。
6. **OPS-M04-023** 9 指标统一 `riccon_` 前缀（或显式修订 ADR-0008），`quota_usage_ratio` 去掉 `tenant` 标签（只保留 `scope`/`window`；按租户排查走日志/审计），并说明与既有 `riccon_model_requests_total` 的关系。

P2/P3（可随后续任务消化）：024 暴露控制、025 info 指纹、026 回滚 SQL 完整性、027 fingerprint 去重、028 gate 冒烟形状、029 测试范围重叠、030 性能主机规格、031 DEC-0024 时序、032 deploy 范围、033 pepper 语义、034 V11 失败矩阵。

## 7. OPS-M04-017（deploy_preview / deploy_prod 不存在）是否阻塞 M04 gate

**不阻塞，但有条件。** 理由与条件：

- M04 的模块 DoD 不含部署；E2E 走 `commands.test_e2e`（compose 测试栈 + `pnpm -w e2e`），迁移/回滚演练走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`，都能在现有环境完成。
- ADR §10.2 已写明“M04 不以此取证，评审包标 `deploy_preview not-applicable`（M09）”，与我的建议一致。
- 条件：①M04 评审包与 gate 说明逐字记录 not-applicable，不调用 `deploy_preview`/`deploy_prod`；②回滚/迁移证据统一说明“一次性容器/真实栈，不是预览环境”；③Lead 不要在 M04 把 `commands.run`（指向不存在的 `docker-compose.dev.yml`）当作可执行门禁命令。若 Lead 要求 M04 必须有可操作的预览环境，需单独立任务并计入预算（属产品/交付决策，不由 Ops 自行扩范围）。

## 8. 实际验证命令与退出码

| 命令                                                                           | 退出码              | 结果                                                                                                                                                                                                         |
| ------------------------------------------------------------------------------ | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `git rev-parse HEAD`                                                           | 0                   | `d800410a7f0f413c12740eadfd3daa1ebe82ae67`                                                                                                                                                                   |
| `git status --short`                                                           | 0                   | 撰写时 6 项设计改动（ADR-0021/0022 未跟踪，openapi/ARCHITECTURE/THREAT-MODEL/PRODUCT/README 修改），均未提交；评审结束前另一会话并发写入 `.riccon/state.json` 与 `M04-architect-review-002.md`，非本会话产物 |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict` | 0                   | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                                                                |
| `ruby -ryaml -e 'YAML.load_file("contracts/openapi.yaml")'`                    | 0                   | `openapi=3.1.0 version=1.3.0-m04 paths=88`                                                                                                                                                                   |
| `docker compose -f deploy/compose/docker-compose.test.yml config`              | 0                   | 编排可解析                                                                                                                                                                                                   |
| 探针 P1（§3.2 顺序）                                                           | 0                   | 顺序可执行；旧 CHECK 反证 3819；同 provider 双 ACTIVE 反证 1062                                                                                                                                              |
| 探针 P2（§10.3 回滚）                                                          | 0                   | 收敛/归档/唯一键成功；**ACTIVE key = 0**                                                                                                                                                                     |
| 探针 P3（cost_day LIMIT）                                                      | 0                   | 单批后仍 1 行 NULL                                                                                                                                                                                           |
| 探针 P4（ADD FK INPLACE）                                                      | 0（`--force` 继续） | 有数据与空表各一次均 **ERROR 1846**；`foreign_key_checks=0` 或不写 ALGORITHM 可成功                                                                                                                          |
| 探针 P5（10 列 INSTANT，含 FK+CHECK）                                          | 0                   | 成功                                                                                                                                                                                                         |

**未运行**（按任务约束）：`./mvnw verify`、`pnpm -w *`、k6、E2E、真实栈、V11 迁移本身。本文不对覆盖率、p95、E2E 作任何通过性判断。

## 9. 未验证与剩余风险

- 未在真实 V10 数据量上跑 V11：OPS-M04-019 的失败点与 012 的回填时长都会随表体积放大，升级前必须实测行数/体积并写入 RUNBOOK。
- 未验证客户 K8s 的 Secret 注入与出网策略（M09）；M04 只保证应用层校验。
- 未验证 `/actuator/prometheus` 在客户 ingress 下是否可达（024）。
- 未验证主密钥双密钥并存能力；若客户 KMS 不支持 previous 并存，rewrap 窗口只能缩短，需在 DEC-0024 明确。
- ADR-0021/0022 仍为 `proposed`；DEC-0024 未创建/未答复。本评审不代替 Lead/用户/Verifier 签字，也不构成任何发布批准。
