---
review_id: REV-M04-OPS-003
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-OPS-002
reviewer:
  agent: riccon/ops
  session: ses_e0932f591ffdQZepEDLXInKmWC
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
scope: "M04 架构第三轮修订最终 focused 运维复审：只核 OPS-M04-019/020/021/012/022/023/008 的关闭情况与是否引入新 P0/P1（V11 DDL/回滚/B13/写范围/关键路径/契约分段），以及 OPS-M04-017/031、F-019 是否阻塞；只读设计文档，只写本评审文件"
created_at: "2026-11-20T11:07:15+08:00"
findings:
  - id: OPS-M04-019
    severity: P0
    status: fixed
    owner: riccon/architect + riccon/builder (B1a)
    title: V11 的 ADD FOREIGN KEY + ALGORITHM=INPLACE（必失败）已改为逻辑引用 + 索引
    summary: "§3.3 第 3 步已改为“不建 provider FK”，`provider_id` 只保留 `idx_model_invocation_provider`（INPLACE/LOCK=NONE）并在应用层校验；ADR 全文仅剩 3 处 FOREIGN KEY，全部位于 CREATE TABLE 的新表（routing_entry / attempt / quota_*），不存在 ALTER ... ADD FK，也没有任何 FK 声明 ALGORITHM=INPLACE。§10.1 的 DDL 语义表不再把 FK 当 online DDL，§10.2 第 3 步结构校验含“model_invocation.provider_id 无 FK”。"
    evidence: "ADR-0021:246-248、:278、:726-732、:741；`grep -n 'FOREIGN KEY' docs/adr/ADR-0021*` 仅 154/155/267/269/270（均 CREATE TABLE）；`grep -n ALGORITHM` 仅 167/179/218/230（INSTANT）与 235-244（索引 INPLACE）"
  - id: OPS-M04-020
    severity: P1
    status: open
    owner: riccon/architect (验收断言) + riccon/ops (B10 演练)
    title: 回滚 SQL 已正确保留 ACTIVE，但演练断言行与规定的两段式种子不相容（本轮实测必失败）
    summary: "§10.3 第 3 步已改为 `UPDATE ... SET status='RETIRED' WHERE status IN ('DISABLED','INVALID')`，探针确认回滚后仍有 1 把 ACTIVE 且旧唯一键恢复成功。但同节断言行仍写“`provider_credential` 每个 `(tenant, provider, type)` 恰好 1 行、`COUNT(*)=1 AND status='ACTIVE'`”。按 §3.2 规定的种子（V10：1 ACTIVE + 1 RETIRED 跨 provider；V11 后同 provider 再造 2 ACTIVE + 1 INVALID，且写明“回滚演练从这里开始”），回滚后必然是 provider A 1 行 ACTIVE + provider B 1 行 RETIRED，全表 COUNT=2、ACTIVE=1；无论按“分组”还是“全表”解读该断言都失败。演练者只能改弱断言或删掉 RETIRED 种子（牺牲 V11 归一覆盖），得到假绿。"
    evidence: "ADR-0021:211-213（两段式种子）、:776（正确 WHERE）、:801（断言行）；探针 P1（mysql:8.4.13，按原文 §3.2/§10.3 执行）：回滚后 `cnt=2, actives=1`，分组为 A 组 ACTIVE、B 组 RETIRED；旧唯一键已存在，重复插入同 (tenant,provider,type) → `ERROR 1062`；archive=2 行"
  - id: OPS-M04-021
    severity: P1
    status: fixed
    owner: riccon/architect + riccon/builder (B1a)
    title: 迁移测试种子已拆为 V10 合法 + V11 后 helper，且实测可构造
    summary: "§3.2 明确 V10 阶段只能造 1 ACTIVE + 1 RETIRED（跨 provider/租户）与 1 万历史调用；V11 之后用 helper/直接 SQL 给同一 provider 再插 1 ACTIVE + 1 INVALID，并写明不得在 V10 schema 上插同 provider 第二把 ACTIVE 或 INVALID。B1a 验收同步为“V10 种子（1 ACTIVE + 1 RETIRED 跨 provider）后 V11 通过；V11 后 helper 造 2 ACTIVE + 1 INVALID 并完成多 key 回滚”。"
    evidence: "ADR-0021:210-213、:653；探针 P1：V10 阶段两条 INSERT 成功（旧唯一键+旧 CHECK 下合法）；V11 五步全部成功；V11 后插入 2 ACTIVE + 1 INVALID 成功（`rows=4, actives=2, invalids=1`）"
  - id: OPS-M04-012
    severity: P1
    status: fixed
    owner: riccon/architect
    title: V11 已不回填 cost_day；历史 NULL 计入 unbucketedCalls；结构校验只要求新调用非空；回填移交 B10 独立 Job
    summary: "ADR-0021 §3.3/§10.1 删除 `cost_status` 全表更新并写明“V11 不回溯历史 cost_day，历史行保持 NULL，成本报告计入 unbucketedCalls；需要回填时由 B10 `scripts/ops/backfill-cost-day`（CHECK 后的独立 Job）分批执行，M04 gate 不依赖全量回填”。ADR-0022 §5 与 §10.2 第 3 步结构校验一致：历史 NULL 允许、M04 后新调用抽样非空、`IT-COST-103` 断言同一口径；能力落在 B10 的 `scripts/ops/backfill-*` 写范围内。ARCHITECTURE 实现地图仍有一处旧表述，另立 OPS-M04-036。"
    evidence: "ADR-0021:250-251、:279、:732、:741、:814；ADR-0022:297；`contracts/openapi.yaml` CostTotals 含 `unbucketedCalls`（:5872/:5895）；B10 写范围含 `scripts/ops/backfill-*`（ADR-0021:666）"
  - id: OPS-M04-022
    severity: P1
    status: open
    owner: riccon/architect (发布步骤) + riccon/lead (B13 排期)
    title: M17-B13 已建立、V11 种子已 false，但发布步骤第 6 步仍写“默认 ROUTED/true/true”，与 false 种子和强制 false→true 直接冲突
    summary: "正向部分已关闭：新增 M17-B13（owner riccon/ops，写 M17 RUNBOOK 章节 + `scripts/ops/quota-*|cost-*` + 配置漂移检查），验收含“quota 开关 false→true 演练、API/Worker 端点/模式/币种漂移、成本对账窗口”；V11 种 `quotaEnforcementEnabled=false`；§8.4 写死“M17 首发保持 false，B13 验证后再 PUT true，该步骤必须写入 B13 RUNBOOK 与 M17 评审包”；B10/B13 的 RUNBOOK 分工与同 owner 串行已写明。但 §10.2 第 6 步（发布冒烟与开关）仍写“默认 `ROUTED/true/true` … 观测 30 分钟（降级、5xx、配额拒绝、链空、key 失效）”。B10 的“五段发布”验收照此落地，M04 发布就会断言/写入 quota=true（且把 M17 才有的“配额拒绝”列为观测项），M17 上线时开关已是 true，B13 的 false→true 演练退化为走过场——正是 022 要防的“上线即拒绝”。"
    evidence: "ADR-0021:306（种子 false）、:699（强制 false→true）、:836（默认 ROUTED/true/false）、:744（ROUTED/true/true，冲突）、:619/:670（B13 任务与验收）、:647（RUNBOOK 分工）；THREAT-MODEL:878（M17 才核对配额指标）"
  - id: OPS-M04-023
    severity: P1
    status: fixed
    owner: riccon/architect + riccon/ops (B1c)
    title: 9 个指标已统一 riccon_* 前缀，quota_usage_ratio 已去除 tenant 标签
    summary: "§10.6 的 9 个指标全部带 `riccon_` 前缀；`riccon_quota_usage_ratio` 只保留 `{scope,window}`；新增标签纪律段落引用 ADR-0008（禁止 tenant/userId/runId，按租户排查走日志/审计/DB）。全仓库文档/代码 grep 无任何未加前缀的旧名（`model_failover_total` 等 0 命中）；THREAT B-20a 更新为“9 个 `riccon_*` 指标存在且标签正确”。指标集合与 M04 gate 的可达性另有新问题，见 OPS-M04-035。"
    evidence: "ADR-0021:842-856；`grep -rn 'model_failover_total\\|model_requests_total\\|quota_rejections_total\\|quota_usage_ratio' docs contracts apps platform scripts | grep -v riccon_` → 0 行；ADR-0008:82-97（前缀 + 标签禁令）；THREAT-MODEL:627"
  - id: OPS-M04-008
    severity: P1
    status: fixed
    owner: riccon/lead + riccon/builder (B1a/B8)
    title: WorkerIntegrationTestBase 已进 B1a 写范围与验收；DEMO/端点/主密钥在 B1a 与 B8 都有落点
    summary: "B1a 的 `write_scope` 显式包含 `apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java`（M04 全部配置键/测试 profile 占位），验收含“Worker 集成测试上下文 DEMO+端点+主密钥可启动”；§2.1 要求 `scripts/real-stack/run.mjs` 用同一份 env 对象给 API 与 Worker 注入 `RICCON_MODEL_DEPLOYMENT_MODE=DEMO`、`RICCON_MODEL_ENDPOINTS`、`RICCON_MODEL_ALLOW_HTTP=true`，B8 验收同样逐字要求“API/Worker 同一份 DEMO/端点/主密钥 env”；preflight 比对两侧 `/actuator/info` 键（S-12 已定义键名）。"
    evidence: "ADR-0021:88-89、:653（B1a 写范围/验收）、:662（B8 验收）、:469（test profile 主密钥）；apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:176-205（现状确无 deployment-mode/endpoints，由 B1a 修）"
  - id: OPS-M04-035
    severity: P1
    status: open
    owner: riccon/architect + riccon/lead + riccon/ops (B1c)
    title: B-20a/B1c 要求 M04 gate 时“9 个 riccon_* 指标存在”，其中 2 个由 M17 的 B6 才注册，B1c 又没有主代码写范围 → M04 gate 按原文不可通过
    summary: "§10.6 的 9 个指标里有 `riccon_quota_rejections_total` 与 `riccon_quota_usage_ratio`，它们的生产者在 M17-B6（ADR-0022 配额准入），而 §10.6 括号明确“B1c 落地 registry/暴露；B4/B6/B7 注册”。B1c 的 write_scope 只有两个 `pom.xml`、`scripts/ops/metrics-check*` 与 `src/test/**/actuator/**`，**没有任何主代码路径**，不可能注册占位指标。THREAT-MODEL §8 又把 B-20a（“9 个 `riccon_*` 指标存在且标签正确”）列为 M04 gate 必关项。结论：M04 阶段最多只有 7 个（model_requests/failover/attempt_duration/credential_invalid/credential_cooldown/chain_empty/agent_runs_inflight），`metrics-check` 与 B-20a 必然失败，或被迫改脚本只查一部分而不改 gate 文本（假绿）；这与 review-002 之后已拆开 B-18a/B-20a 的方向不一致。"
    evidence: "ADR-0021:604/:655（B1c 交付与写范围，无 main java）、:842-852（9 指标与注册归属）、:666（B6 属 M17）、:619-621；THREAT-MODEL:603、:627、:871（M04 gate 要求 9 个）；PRODUCT AC-17 整条归 M17（PRODUCT:361/453）"
  - id: OPS-M04-036
    severity: P2
    status: open
    owner: riccon/architect
    title: ARCHITECTURE 的 V11 迁移顺序仍写“cost_day 分批回填”，与 ADR 的“V11 不回溯”矛盾
    summary: "`docs/ARCHITECTURE.md:315` 的“迁移与回滚”仍把 `cost_day` 分批回填写进 V11 迁移顺序；ADR-0021 §3.3/§10.1、ADR-0022 §5 已明确 V11 不回溯、回填是 B10 的独立可选 Job。实现者按“最小地图”落地会把回填重新塞回迁移（单条 LIMIT 或无法在 SQL 内循环），正是 OPS-M04-012 已关闭的缺陷形态。"
    evidence: "docs/ARCHITECTURE.md:315；ADR-0021:250-251/:732；ADR-0022:297"
  - id: OPS-M04-037
    severity: P2
    status: open
    owner: riccon/architect + riccon/lead
    title: “provider_id 无 FK”的迁移测试断言只在响应矩阵里，未进 B1a/V1 验收
    summary: "S-01 的验证列写“V11 迁移测试：`information_schema` 无该 FK”，但 B1a 的 6 条验收与 V1 的验收都没有这句话；B10 的“五段发布/结构校验”虽含该检查，但 B10 验收只写“五段发布”这一概括。删 FK 是 P0 修复的核心，若迁移测试没断言，未来有人“补 FK”不会被 CI 抓住。"
    evidence: "ADR-0021:1024（S-01）、:653（B1a 验收）、:669（V1 验收）、:741（结构校验）、:666（B10 验收）"
  - id: OPS-M04-038
    severity: P2
    status: open
    owner: riccon/ops (B10) + riccon/architect
    title: backfill-cost-day 有写范围但无验收项；ADR-0022 的 quota_over_limit_observed_total 不在任何指标清单里
    summary: "B10 写范围含 `scripts/ops/backfill-*`，ADR 也把它作为独立 Job 描述（每批 50,000、记录进度），但 B10 的验收 6 条没有回填工具/干跑/进度输出，交付与否无人判定。另：ADR-0022 §1.3 使用 `riccon_quota_over_limit_observed_total`（quotaEnforcement=false 时的观察指标），§10.6 的 9 指标与 B-20b 的“配额拒绝/成本查询指标存在”都没有它，M17 可能漏注册也不被 gate 发现。"
    evidence: "ADR-0021:666（B10 写范围/验收）、:251（独立 Job）；ADR-0022:128；ADR-0021:842-852；THREAT-MODEL:628"
  - id: OPS-M04-039
    severity: P2
    status: open
    owner: riccon/lead
    title: M17-B13 无工时估算，M17 的 17h 预算无法覆盖“发布章节 + false→true 演练 + 对账窗口”
    summary: "§8.4 把 M17 记为 ≈17 人时、关键路径 14–16h，但逐任务只给了 B6 4 / B12 1 / B9b 3 / B11b 2 / V2 4（合计 14），B7 与 B13 共只剩约 3h；B13 的交付物（M17 发布/回滚 RUNBOOK 章节、开关 false→true 演练、API/Worker 漂移检查、成本对账窗口）明显不止 1–2h。B13 又是 V2 的前置（`M17-B12/B6/B7 → B13 → V2`），预算低估会直接挤压 M17 的演练证据。"
    evidence: "ADR-0021:619、:636、:670、:678-680"
  - id: OPS-M04-040
    severity: P3
    status: open
    owner: riccon/ops
    title: B10 与 B13 都写 deploy/compose/docker-compose.test.yml；§10.6 又把“暴露”写给 B1c
    summary: "B10 写整份 compose，B13 写“仅配置漂移检查”，同一文件两个 writer；因同 owner 且 M04/M17 串行，实际可接受，但应在任务卡写明“B13 仅追加 M17 漂移检查变量、不改 B10 段”。另 §10.6 写“B1c 落地 registry/暴露”，而 §8.3 把 Actuator 暴露的 SecurityConfig 归 B2，措辞应统一为“registry 归 B1c、暴露归 B2”。"
    evidence: "ADR-0021:655、:666、:670、:842"
  - id: OPS-M04-041
    severity: P3
    status: open
    owner: riccon/architect
    title: ADR-0008 的 riccon_model_requests_total 标签集 {outcome} 与 ADR-0021 的 {provider,model,outcome} 不同名同义冲突
    summary: "ADR-0008 §6（已生效的最小指标集）定义 `riccon_model_requests_total{outcome}`；ADR-0021 §10.6 复用同名指标但增加 `provider`、`model` 标签。Micrometer 的 Prometheus registry 对同名 meter 要求一致的标签键集，两套并存会在第二处注册时抛异常（应用启动失败）。当前 `apps/`、`platform/` 无任何 MeterRegistry 注册（grep 0 命中），因此 M04 只有一个注册点（B4），不阻塞 gate；但必须在 B1c 的指标约定里写死唯一注册点，并给 ADR-0008 加一行 superseded 注记，否则 M09 补 ADR-0008 指标时会踩坑。"
    evidence: "ADR-0008:82-97；ADR-0021:843、:853；`grep -rn 'MeterRegistry|Counter.builder' apps platform` → 0 行"
notes: "第三轮修订把 019（删 FK）、021（种子两阶段）、012（不回填）、023（riccon_*）、008（WorkerIntegrationTestBase）在设计与探针层面关闭；020 的 WHERE 修复有效但演练断言与种子不相容，022 的 B13/种子已补但发布第 6 步仍写 true/true，且新增一处 M04 gate 无法达成的 9 指标要求。只读复审，未修改任何 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/源码/测试/迁移，未创建任务/决策/模块，未提交 git；ADR-0021/0022 仍 proposed，DEC-0024 未创建，M04.json 仍 18 AC/24h，M17 模块不存在。"
---

# REV-M04-OPS-003：M04 架构修订 — 第三轮独立运维复审（focused）

## 1. 结论

**verdict: fail。** 上一轮 1×P0 与 6 条 P1 中，5 条已在设计与实测层面关闭（019/021/012/023/008）；**OPS-M04-020 与 022 仍以“文本/验收”形式开着**，并且新发现 **1 条新的 P1**（M04 gate 要求的 9 个指标含 2 个 M17 才注册的配额指标，B1c 无法注册）。

三个必改点都很小（1 条发布步骤、1 条演练断言、1 处指标集合/gate 归属），但每一个都会让 T4 gate 变成假绿或让 M17 上线即生效：

1. **OPS-M04-022（P1，残留）**：§10.2 第 6 步仍写“默认 `ROUTED/true/true`”，与 V11 的 `quotaEnforcementEnabled=false` 和 B13 的强制 false→true 冲突。
2. **OPS-M04-020（P1，残留）**：回滚 SQL 已正确，但断言行 `COUNT(*)=1 AND status='ACTIVE'` 在规定的两段式种子下实测为 `COUNT=2, ACTIVE=1`，演练无法通过。
3. **OPS-M04-035（P1，新）**：B-20a/B1c 要求 M04 gate 时 9 个 `riccon_*` 指标存在，其中 2 个由 M17-B6 注册；B1c 无主代码写范围，M04 最多 7 个。

**是否建议开工：不建议在修掉这 3 项前创建 M04 任务。** 其余 P2/P3 可与同一批修订一并处理。

## 2. 关闭表（只列本轮要求的 7 项）

| ID       | 上轮 | 本轮      | 结论与依据                                                                                                                                                                           |
| -------- | ---- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 019 (P0) | open | **fixed** | 删除 `fk_model_invocation_provider`；`provider_id` 逻辑引用 + 显式索引 `idx_model_invocation_provider`；全文无 ALTER ADD FK、无 FK 的 `ALGORITHM=INPLACE`；§10.2 结构校验含“无 FK”。 |
| 020 (P1) | open | **open**  | WHERE 已改 `IN ('DISABLED','INVALID')`，探针确认保留 1 把 ACTIVE、旧唯一键恢复、1062 生效；但演练断言 `COUNT(*)=1 AND status='ACTIVE'` 与两段式种子冲突（实测 2 行/1 ACTIVE）。      |
| 021 (P1) | open | **fixed** | V10 合法种子（1 ACTIVE + 1 RETIRED 跨 provider）→ V11 → helper 造 2 ACTIVE + 1 INVALID；探针全程成功，无 1062/3819。B1a 验收同步。                                                   |
| 012 (P1) | open | **fixed** | V11 不回溯 `cost_day`，历史 NULL 计入 `unbucketedCalls`；结构校验允许历史 NULL、只要求 M04 后新调用非空；回填为 B10 独立 Job。ARCHITECTURE 旧表述另立 036。                          |
| 022 (P1) | open | **open**  | B13 已建立（owner/验收/依赖清楚）、V11 种子已 false、§8.4 强制 false→true；但 §10.2 第 6 步仍写 `ROUTED/true/true` 并把“配额拒绝”列为 M04 观测项。                                   |
| 023 (P1) | open | **fixed** | 9 个指标全部 `riccon_*`；`riccon_quota_usage_ratio{scope,window}` 去 tenant；文档/代码零处旧名。M04 gate 的指标集合可达性另立 035。                                                  |
| 008 (P1) | open | **fixed** | `WorkerIntegrationTestBase` 进 B1a 写范围+验收（DEMO+端点+主密钥）；B8 用同一份 env 注入 API/Worker；preflight 有 `/actuator/info` 键。                                              |

## 3. 无新 P0/P1 的核对

### 3.1 V11 其他 DDL（FK/索引/CHECK/半迁移）

- 大表 `model_invocation`：10 列一次性 `ALGORITHM=INSTANT`、4 条索引逐条 `INPLACE, LOCK=NONE`、**无新增 CHECK、无新增 FK**。不触发 1846/COPY。
- 其余 FK 全部在 `CREATE TABLE` 内（routing_entry → routing/model；attempt → invocation/model/model_provider；quota_limit/usage → tenant/app_user）。父表 PK/索引均满足（`app_user` PK `(tenant_id,id)`、`model_invocation` PK `(tenant_id,id)`），新表为空，不重建父表。
- `provider_credential` 第 4 步把 `ADD UNIQUE KEY + ADD KEY + MODIFY COLUMN + ADD CHECK` 放在一条 ALTER：MySQL 会选 COPY，但这是小表、单语句原子，且在一次性迁移 Job 内，可接受；建议 B1a 在 SQL 注释里写一句“此处为 COPY（小表）”，避免后人误以为全 V11 都是 online DDL（P3，不单列 finding）。
- 失败恢复：§10.4 已有“失败点 → 残留 → 恢复”矩阵，覆盖“加列/索引中途”“半迁移时旧应用仍可读写”；回滚脚本含 `provider_credential_rollback_archive` DDL 与 `DELETE FROM flyway_schema_history WHERE version='11'` + repair。**本条无新 P0/P1。**

### 3.2 回滚

- SQL 语义正确（探针正向复现：V11 → 4 行（2 ACTIVE/1 INVALID）→ 收敛归档 2 行 → 1 行 ACTIVE + provider B 的 RETIRED → 旧唯一键恢复 → 重复插入 1062）。
- 缺陷只在**断言行**（020）：详见 §5 探针 P1。

### 3.3 写范围与串行

- `scripts/ops` 已按前缀分工且无重叠：B1c `metrics-check*`；B10 `seed-*/update-*/rewrap-*/restore-*/backfill-*`；B13 `quota-*/cost-*`。
- `docs/RUNBOOK.md` 由 B10 先写 M04、B13 后追加 M17，同 owner 串行；B10/B13 都列 `docker-compose.test.yml`，同 owner + 跨模块串行，实际无并发写（P3，见 040）。
- `application.yml` 唯一 writer B1a；契约 writer B2（M04）/B12（M17）串行；测试目录已按模块收窄（S-16）。**无文件级并发写 P1。**

### 3.4 B13 与关键路径/预算

- B13 在图中位于 `B12/B6/B7 → B13 → V2`，依赖与验收清楚，M17 V2 已把 B13 列为前置。
- 但 §8.4 的 M17 工时只覆盖关键路径 14h，B7 与 B13 合计仅约 3h，B13 无单独估算；发布章节 + false→true 演练 + 漂移检查 + 对账窗口的工时明显被低估（P2，039）。不构成“无法执行”的 P1，但 Lead 排期前应补 B13 的估算。

### 3.5 契约分段

- 当前 `1.3.0-m04` 全量设计（88 paths / 154 schemas）；DEC-0024 后 B2 出 `1.3.0-m04a`、B12 出 `1.4.0-m04b`；B12 依赖含 `M04-B2`，V2 依赖含 B13/B11b；`IT-MODEL-108a/108b` 已拆。契约分段与 B2/B12 依赖一致，唯一外部前置是 DEC-0024（见 §4）。**无新 P1。**

### 3.6 新发现（见第 6 节）

- **035（P1）**：B-20a/B1c 的 9 指标要求 M04 不可达（2 个属 M17-B6；B1c 无主代码写范围）。
- 036/037/038/039（P2）、040/041（P3）：文档一致性、断言归属、预算与指标注册点，均不单独阻塞，但 036 会复活已关闭的 012，应与 035 同批修。

## 4. 仍 deferred 项是否阻塞

| 项                                 | 结论                                                                                                                                                                                                     | 是否阻塞 M04 gate                                 |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| OPS-M04-017（deploy_preview/prod） | ADR-0021 §10.2 已明确 M04 不以 `deploy_preview`/`deploy_prod` 取证，迁移/回滚走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`，评审包标 `not-applicable`（M09）。RUNBOOK 现无 M04 章节（B10 交付）。 | 否（有条件）                                      |
| OPS-M04-031（DEC-0024 时序）       | `.riccon/decisions/` 仍无 DEC-0024；`.riccon/modules/M04.json` 仍 18 AC/24h/0 task，M17 模块不存在。ADR 已写“Lead 必须在派 B2 前拿到 DEC-0024；未答复则 M04 gate 等待”。                                 | **是（gate 前置）**，但不阻塞本次设计复评         |
| F-019（文档同步）                  | ADR-0021 §Decision 首段已对 ADR-0012 §4 作覆盖声明，ARCHITECTURE:115 同步；`docs/ACCEPTANCE.md:55-58` 仍写 M13 F-001/R-11 由 M04 承接，与 M04 acceptance “不承接”不一致。                                | 否，但需在评审包登记并在 M04 gate 前或 M17 前更新 |

## 5. 实际验证命令与证据

| 命令 / 探针                                                                    | 退出码 | 结果                                                                                                                                                                                                                 |
| ------------------------------------------------------------------------------ | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                           | 0      | `d800410a7f0f413c12740eadfd3daa1ebe82ae67`（设计改动仍未提交）                                                                                                                                                       |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict` | 0      | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                                                                        |
| `ruby -ryaml -e 'YAML.load_file("contracts/openapi.yaml")'`                    | 0      | `openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`                                                                                                                                                               |
| `docker compose -f deploy/compose/docker-compose.test.yml config`              | 0      | 编排可解析                                                                                                                                                                                                           |
| `grep -n 'ALGORITHM' docs/adr/ADR-0021*`                                       | 0      | 仅 INSTANT（含列）与索引 INPLACE，无 FK 算法声明                                                                                                                                                                     |
| `grep -n 'FOREIGN KEY' docs/adr/ADR-0021* docs/adr/ADR-0022*`                  | 0      | 7 处，全部在 `CREATE TABLE` 内                                                                                                                                                                                       |
| 指标前缀 grep（docs/contracts/apps/platform/scripts）                          | 0      | 未加 `riccon_` 的旧名 0 命中；`MeterRegistry                                                                                                                                                                         | Counter.builder` 0 命中（当前无实现） |
| 探针 P1（一次性 `mysql:8.4.13` 容器，`--rm` 销毁；按原文 §3.2 → §10.3 执行）   | 0      | V10 种子 2 行/1 ACTIVE → V11 五步成功 → 同 provider 插 2 ACTIVE+1 INVALID（4/2/1）→ 回滚后 **2 行/1 ACTIVE/1 RETIRED**、archive=2、旧唯一键存在、重复插入 `ERROR 1062`；**ADR §10.3 的 `COUNT(*)=1` 断言实测不成立** |

探针口径：为隔离 `provider_credential` 的状态/唯一键语义，简表省略了到 `tenant`/`model_provider`/`tenant_data_key` 的 FK（不影响本次断言）；`label VARCHAR(64) NOT NULL DEFAULT '默认'`、mock 的 provider UUID、legacy SHA2 回填均按 ADR 原文。容器的 `urandom` 无关；探针不涉及生产数据。

**未运行**（按任务约束）：`./mvnw verify`、`pnpm -w *`、k6、E2E、a11y、Prometheus 抓取、真实 V11 迁移（V11 文件尚不存在）。本文不对覆盖率、p95、E2E 作任何通过性判断。

## 6. 最小修复清单

P1（必须修完再复评）：

1. **OPS-M04-022**：`§10.2 第 6 步` 改为“开关保持 `ROUTED/true/`**`false`**；M04 观测项删除‘配额拒绝’（配额准入未上），改为‘降级/5xx/链空/key 失效/成本查询延迟’；quota 的 false→true 只在 M17-B13 执行”。
2. **OPS-M04-020**：把 `§10.3` 的演练断言改成可执行的形状，例如：“`(tenant,provider,type)` 每组恰好 1 行；V10 种子涉及的 provider 组 1 行且 `status='ACTIVE'`；全表 `ACTIVE=1`、`DISABLED=INVALID=0`；provider B 组 1 行 `RETIRED`；archive 行数 = 回滚前行数 − 保留行数；旧 Worker 取到该 ACTIVE key 并完成一次 J1”。同时删掉或限定“`COUNT(*)=1`”的全表读法。
3. **OPS-M04-035**：把 B-20a 的指标集合按模块切开——M04 gate 断言 7 个（`riccon_model_requests_total`、`riccon_model_failover_total`、`riccon_model_attempt_duration_seconds`、`riccon_model_credential_invalid_total`、`riccon_model_credential_cooldown_total`、`riccon_model_chain_empty_total`、`riccon_agent_runs_inflight`）；B-20b/M17 断言 9 个 + `riccon_quota_over_limit_observed_total`、`riccon_cost_report_queries_total`、`riccon_quota_store_failure_total`。`metrics-check` 加 `--scope=m04|m17`（或两个脚本），B1c 验收同步为“M04 7 个、约定 9 个名字与标签”。若坚持 M04 就有 9 个，必须给 B1c 一个主代码写范围来注册零值占位（不推荐：会制造无生产者的假指标）。

P2/P3（与上同批，可与 P1 一起改）：

4. **OPS-M04-036**：`docs/ARCHITECTURE.md:315` 删除“`cost_day` 分批回填”，改为“V11 不回填；B10 `backfill-cost-day` 在 CHECK 后按需分批”。
5. **OPS-M04-037**：B1a 验收加一条“`information_schema` 无 `fk_model_invocation_provider`，且 `idx_model_invocation_provider` 存在”；V1 结构冒烟同句引用。
6. **OPS-M04-038**：B10 验收加“`backfill-cost-day` 干跑 + 每批 50,000 + 进度输出（可选，不影响 gate）”；ADR-0022/§10.6/B-20b 补 `riccon_quota_over_limit_observed_total`。
7. **OPS-M04-039**：给 B13 一个工时估算并重算 M17 的 17h（至少把“发布章节/开关演练/对账窗口”计入）。
8. **OPS-M04-040/041**（P3）：任务卡写明 B13 只追加 compose 中 M17 漂移检查段；统一“registry 归 B1c、暴露归 B2”措辞；给 ADR-0008 的 `riccon_model_requests_total` 加一行“标签集由 ADR-0021 §10.6 扩展为 {provider,model,outcome}，唯一注册点 B4”。

## 7. 未验证与剩余风险

- V11 文件尚不存在，本文只验证了 ADR 的 SQL 语义与顺序，未验证真实 V10 数据量下的索引耗时、磁盘余量与复制延迟；>200 万行/2 GiB 的停写判据仍需 B1a/B10 在真实体积上实测并写入 RUNBOOK。
- 未验证客户 K8s 的 Secret 注入、`/actuator/prometheus` 在 ingress 下的可达性（RICCON_OPS_ALLOWED_CIDRS 只做应用层来源过滤，完整网络策略归 M09）。
- 未验证主密钥 current/previous 双密钥并存能力；若客户 KMS 不支持并存，rewrap 窗口只能缩短，需在 DEC-0024 写明。
- ADR-0021/0022 仍 `proposed`；DEC-0024 未创建；M04.json 仍 18 AC/24h；M17 模块与 M17-B13 的任务卡尚未创建。本评审不代替 Lead/用户/Verifier/Architect 签字，也不构成任何发布批准。
