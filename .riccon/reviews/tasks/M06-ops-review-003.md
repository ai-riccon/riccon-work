---
review_id: REV-M06-OPS-003
module_id: M06
task_id: TASK-0218
previous_review: REV-M06-OPS-002
reviewer:
  agent: riccon/ops
  session: ses_df14e751dffeRKnipYowo22wnY
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 06b3db297975a4fe29d502d669584dd09dd87298
  - 48211e71ce1a7a44b1683bfb3737fdd4c1d9e085
baseline_commit: 48211e71ce1a7a44b1683bfb3737fdd4c1d9e085
head_commit: 06b3db297975a4fe29d502d669584dd09dd87298
review_scope: >-
  M06 运维第三轮 delta 复评，只审 REV-M06-OPS-002 的 OPS-M06-017..023 在 06b3db2 中的设计响应，
  以及与仓库现状的对账（T01/T15/T16a/T16b 任务表与写范围、§3.5/§4.3/§4.8/§8.1/§10.1–§10.5/§13.1/
  §14/§15.2/§17、ARCHITECTURE 评审状态表与交付预算）。未修改任何被评审文件、源码/测试/迁移、
  PRODUCT、AGENTS 或 .riccon 状态；未运行构建/测试/迁移/演练；按 Lead 要求未运行
  `riccon gate check --module M06`。
tools_used: >-
  只读 shell（git log/diff、grep、sed、date）。未使用 Serena/CodeGraph：delta 仅两份 Markdown 文档，
  符号工具不适用；代码事实（run.mjs env map、AuditDetailsPolicy、worker application.yml、
  ci.yml、restore-drill、迁移测试先例）用 grep/sed 只读核对，不构成降级风险。
carried_over_findings: >-
  OPS-M06-001..016 的状态沿用 REV-M06-OPS-002（14 fixed、2 accepted）：
  OPS-001/002/003/004/005/006/008/009/010/011/013/014/015/016 为 fixed，
  OPS-007/012 为 accepted（owner/due/accepted_reason 见 REV-M06-OPS-002），本轮未重新复核。
findings:
  - id: OPS-M06-017
    severity: P1
    status: fixed
    title: "T01 已补 migration 测试目录；应急 drop SQL 单一落点为 V15MigrationTest，无第三处副本"
    summary: >-
      §13.1 T01 的 write_scope 已含 `apps/api/src/test/java/com/riccon/api/migration/**`，且在 T01 描述中
      点名 `V15MigrationTest（含应急 drop SQL 内联断言）`；§15.2 明确 drop SQL 内联在该测试、不新增
      `scripts/ops/*.sql`，T16b 只按 §10.4 同序做判定断言、不另存副本。可行性有先例：V9–V14MigrationTest
      均在该包，且用一次性 Testcontainers MySQL 8.4.13 + root 演练 schema（V10MigrationTest:896 注释、
      V11MigrationTest:1174），内联 drop 测试与既有模式一致，不需要新测试设施。T01 证据列“迁移测试”
      现在有合法落点，不再二选一越界或缺失。
    evidence: >-
      docs/adr/ADR-0024 §13.1 T01 L819、§15.2 L908-911、§10.4 L747；
      apps/api/src/test/java/com/riccon/api/migration/{V9..V14}MigrationTest.java、
      V10MigrationTest.java:896、V11MigrationTest.java:1174
  - id: OPS-M06-018
    severity: P2
    status: fixed
    title: "T16a 已点名 real-stack M06 env 注入与 real-stack-env.json 快照（键/默认值/来源），写范围覆盖"
    summary: >-
      §13.1 T16a 明确 `scripts/real-stack/run.mjs` 显式注入 M06 env，并把
      `RICCON_WORKFLOW_ENABLED`、`CLAIM_ENABLED`、`RESULT_MAX_BYTES`、心跳/租约/并发/轮询/连接池等键
      连同默认值与来源登记进 `real-stack-env.json` 快照；写范围仍含 `scripts/real-stack/**`，可改 run.mjs。
      机制已存在、属扩展而非新建：run.mjs 用 `sharedModelEnv()` 显式 env map（L900-940），
      `buildEnvSnapshot()`（L778-820）已用 `startupCommands` 按 API/Worker 列出 env 键，快照文件为
      `ENV_SNAPSHOT_FILE`（L117，写入 evidence 目录）。E2E-M06-10（关闭态）与 T16b 演练因此可以按场景
      切换开关并留可复现快照，不再被迫跳过或改手工步骤。
    evidence: >-
      docs/adr/ADR-0024 §13.1 T16a L837；scripts/real-stack/run.mjs:117、778-820、900-940；
      deploy/compose/docker-compose.test.yml（栈入口）
  - id: OPS-M06-019
    severity: P2
    status: fixed
    title: "§8.1 白名单已对齐 §4.8（instanceId/observedAt），T01 承接 AuditDetailsPolicy 与断言"
    summary: >-
      §8.1 `workflow.feature.changed` 的 details 已改为 `enabled`、`source`、`reason?`、`instanceId`、
      `observedAt`，与 §4.8 的启动观测一致；§13.1 T01 描述加入“审计白名单（含 feature.changed 的
      instanceId/observedAt + 关闭态启动审计断言）”，主代码 `AuditDetailsPolicy.java` 在 T01 写范围内。
      该修复是必要的：`AuditDetailsPolicy.validate` 对白名单外键直接抛
      IllegalArgumentException（L391-423），关闭态启动写未登记键会阻断 API 启动，失败模式最差。
      落点残余见 OPS-M06-024。
    evidence: >-
      docs/adr/ADR-0024 §4.8 L406、§8.1 L592、§13.1 T01 L819；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:391-423、
      AuditDetailsReader.java:42-46
  - id: OPS-M06-020
    severity: P2
    status: fixed
    title: "§3.5 已拆为“可执行断言同提交 / RUNBOOK 由 T15 在 gate 前更新”，T15 列出关键数字"
    summary: >-
      §3.5 item 7 改为：CI/ApiApplicationTests/SchemaVersionCheckerTest/restore-drill 与 V15 同提交，
      **RUNBOOK 不与 V15 同提交**，由 T15 在 M06 gate 前更新（V1–V15、57/58 表、4 触发器、
      restore-drill 15/4、MAX(version)=15），并明确 T01 不因 RUNBOOK 晚更新阻塞；T15 任务描述列出
      V1–V15、57/58、4 触发器、restore-drill 15/4（MAX=15 只在 §3.5，可接受）。仓库现实对账支持该拆分：
      CI 表数由 `CREATE TABLE` 动态推导、仅触发器硬编码 2（ci.yml:237-246）；ApiApplicationTests 仍为
      14/49/2（L42-45）；restore-drill self-test fixtures 为 49/48、triggers 2/1（L779-809）；
      RUNBOOK 226/268/300/351-352/692 仍是 V14/48-49/2/MAX=14。即：T01 同提交的四项必须改，RUNBOOK 可
      晚于 V15 但不晚于 gate，两处要求不再互斥。
    evidence: >-
      docs/adr/ADR-0024 §3.5 L274、§13.1 T15 L836；.github/workflows/ci.yml:237-246；
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:42-45；
      scripts/ops/restore-drill.mjs:779-809；docs/RUNBOOK.md:226,268,300,351-352,692
  - id: OPS-M06-021
    severity: P2
    status: open
    title: "回滚顺序已统一，但“关开关”不停 Worker，断言仍可被在途运行推翻"
    summary: >-
      顺序本身已按上轮建议统一：§10.4 受支持回滚 1..7 为“关开关（取消可用）→ 取消 WAITING_APPROVAL 并
      断言 0 → 处理/登记 PENDING/RUNNING → 回滚 Worker 前断言 → 回滚 Worker → 保留/前向修复 API”，
      §10.5 第 7 章与 §14 AC-23 映射、T16b“回滚顺序断言”一致。**但竞争根因未消除**：`RICCON_WORKFLOW_ENABLED=false`
      只让 API 拒绝新运行/重试/审批决定，§4.8 明确“已受理的在途运行继续到终态”，Worker 领取由
      `RICCON_WORKFLOW_CLAIM_ENABLED` 单独控制（§4.3、§10.1）；§10.4 第 1 步既没有关 CLAIM_ENABLED，
      也没有等 §4.3 的停机宽限。于是：
      (a) 第 2 步断言 WAITING=0 之后，仍为 PENDING 的运行会被当前 Worker 领取并执行，走到 APPROVAL 节点
      就产生新的 WAITING_APPROVAL；
      (b) 第 4 步允许“PENDING/RUNNING 为 0 **或已登记滞留清单**”，即允许带着在途运行进入回滚，断言与
      实际回滚之间的窗口仍可出现新的 WAITING_APPROVAL；
      (c) 回滚后旧 Worker 不认 `workflow_run`，这些新行无人接管，只能等下一次 M06 部署或靠保留的 API 取消。
      这正是 REV-M06-OPS-002 OPS-M06-021 描述的失败模式（“与仍在推进的运行竞争，可能又生成新的
      WAITING_APPROVAL”），只是窗口变窄。对 T16b 的直接后果：演练按此顺序断言会间歇失败，或被放宽成
      只对快照计数断言（假绿）。
      需要的修正（2–3 行量级）：第 1 步同时 `CLAIM_ENABLED=false` 并按 §4.3 等待在途终止/宽限；或把
      断言点后移到“回滚 Worker 之后且 M06 API 仍可取消”再断言并清空；第 4 步不得用“已登记滞留清单”放行
      PENDING（PENDING 必须先取消，或 claim 已关），RUNNING 必须终止或回滚后复断言 WAITING=0；
      T16b 断言“静默前提”（claim 已关、无 RUNNING）而不只是计数快照。
    owner: "riccon/architect（§10.4/§10.5）；T15（RUNBOOK）、T16b（演练断言）"
    due: "T15 完成前；T16b 开工前"
    evidence: >-
      docs/adr/ADR-0024 §10.4 L739-745、§10.5 L778、§13.1 T16b L838、§4.8 L406、§4.3 L335、
      §10.1 L691；apps/worker 现有 outbox 领取/优雅停机语义（application.yml:19、
      OutboxLeaseHeartbeat.java:50）
  - id: OPS-M06-022
    severity: P2
    status: fixed
    title: "Hikari 容量规则、claim 轮询/退避、hikaricp 指标已落到 §4.3/§10.1/§10.2/§10.5 与 T06a/T15"
    summary: >-
      §4.3 新增“连接池与 claim 节奏”：`RICCON_DB_POOL_SIZE >= WORKER_CONCURRENCY + 4`（并说明 4 为
      心跳/outbox/健康余量）、执行槽位不长期占连接、`CLAIM_POLL_INTERVAL_MS`（1000）与空队列指数退避
      `CLAIM_IDLE_BACKOFF_MAX_MS`（5000）；§10.1 增加两个新键、`RICCON_DB_POOL_SIZE`（worker 默认 16）与
      启动校验（含“退避上限 ≥ 轮询周期”）；§10.2 增加 `hikaricp_connections_pending`/
      `hikaricp_connections_timeout_total` 与“pending 持续/timeout 增长”告警；§10.5 第 1/3 章写入配置表
      与排障；T06a 描述含 claim 轮询/空队列退避，T15 描述含 Hikari 规则与 hikaricp 指标。
      对账注意：worker 现有 `maximum-pool-size: ${RICCON_DB_POOL_SIZE:10}`（application.yml:19），
      默认 10 < 10+4，故 T01 必须按 §10.1 把 worker 默认改为 16（T01 已写明一次性写入两个
      application.yml）；否则默认配置会 fail-closed 拒绝启动。real-stack 注入 25/50（run.mjs:904）
      满足下限，`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 同值，无冲突。该键改动会提高每 Worker
      实例连接数，属预期；容量换算（pool × 副本 + API 池 < MySQL max_connections）需在 T15 RUNBOOK 写明。
    evidence: >-
      docs/adr/ADR-0024 §4.3 L329、§10.1 L683-684/694-698、§10.2 L719、§10.5 L772/L774、
      §13.1 T06a L824 / T15 L836；apps/worker/src/main/resources/application.yml:19；
      scripts/real-stack/run.mjs:904
  - id: OPS-M06-023
    severity: P3
    status: accepted
    title: "文案/映射小项 a/b/c/e/f 已补；d（T15 4h）未新增论证，仍偏紧"
    summary: >-
      (a) §14 AC-23 映射已加 T16b（L887）；(b) §3.5 已写 fixtures tables 49/48→58/57、
      triggers 2/1→4/3（L274），与 restore-drill:779-809 现状对应；(c) §10.3 第 2 步改为“首次发布固定
      `RICCON_WORKFLOW_ENABLED=false`，smoke/只读检查后再显式打开”，不再留二选一问号（L730）；
      (e) 近 5 MiB 真实插入归 T16b 容量报告（L838），写范围仅 `scripts/ops/workflow-*.mjs`，需脚本
      自清理（实现细节，可接受）；(f) §10.5 第 1 章与 T15 描述已说明
      `RICCON_WORKFLOW_SCHEDULER_POOL_SIZE` 与 `RICCON_WORKER_SCHEDULER_POOL_SIZE` 是不同池。
      (d) T15 仍为 4h，§13.1 只记录 3→4h 调剂与“有模块余量”，未给出 4h 覆盖“Hikari 校验 + claim 节奏 +
      hikaricp 指标 + 11 章 RUNBOOK + 两个 pool 说明”的论证；本次 T15 范围又扩大，偏紧程度略升。
      因属文案/工时且不在关键路径、模块有 28h 余量，沿用接受；若 T15 开工后超出，按 §13.1 的“候选后移”
      （运行历史高级过滤 -2h / 模板列表合并 -1h）调剂，不得删 RUNBOOK 回滚/预检章节。
    owner: "riccon/lead（建卡/工时）；T15（RUNBOOK）；T16b（容量报告）"
    due: "M06 建卡时（2026-11-25）；T15/T16b 完成前复核"
    accepted_reason: >-
      均为文案、映射与验收细化，不改变队列/回滚/审计语义，不影响 CI 变绿；T15 不在 75h 关键路径，
      模块尚有工时余量；5 MiB 实插与 capacity 报告可在 T16b 内完成，不阻塞 T01 开工。
    evidence: >-
      docs/adr/ADR-0024 §3.5 L274、§10.3 L730、§10.5 L772、§13.1 L836-843、§14 L887、
      scripts/ops/restore-drill.mjs:779-809
  - id: OPS-M06-024
    severity: P3
    status: open
    title: "“关闭态启动审计断言”缺少主代码落点：观测组件在 T15 的 config 包，T01 无法独立变绿"
    summary: >-
      OPS-019 要求 T01 加“关闭态启动审计断言”，但 T01 write_scope 只有 `api/workflow/web/**`、
      `application.yml`、`AuditDetailsPolicy.java` 与既有测试位置；启动观测组件的自然落点
      （如 `apps/api/src/main/java/com/riccon/api/config/Workflow*.java`，与既有
      `KnowledgeAclStartupCheck` 同类）属于 T15，审计测试包 `apps/api/src/test/java/com/riccon/api/audit/**`
      属于 T16a。结果：T01 要么把观测组件塞进 `workflow/web/**`（语义错位），要么该断言要等 T15 才能通过，
      T01 自己的测试会红。需要 Lead/Architect 在建卡时二选一：
      (1) 把 `api/config/Workflow*.java` 的观测组件划给 T01（T15 保留配置/校验）；
      (2) T01 只做白名单 + policy 级 details 断言，把“关闭态启动写审计且不抛错”的集成断言移到 T15 或 T16a。
    owner: "riccon/lead（写范围）；riccon/architect（§13.1/§8.1 落点）"
    due: "T01 建卡前（与 OPS-017 同批处理）"
    evidence: >-
      docs/adr/ADR-0024 §8.1 L592、§13.1 T01 L819 / T15 L836 / T16a L837；
      apps/api/src/main/java/com/riccon/api/config/KnowledgeAclStartupCheck.java:22,45；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:391-423
  - id: OPS-M06-025
    severity: P3
    status: open
    title: "关闭开关即写 workflow 审计行 → “全量回滚判定全 0”在首次禁用启动后不可达；演练顺序需写明"
    summary: >-
      §10.4 判定 SQL 含 `audit_event action LIKE 'workflow.%'`，§4.8 规定开关关闭时 API 启动写
      `workflow.feature.changed`，§10.3 又固定首发 `ENABLED=false`。因此首次禁用启动成功后，“全 0”
      永不成立，全量 API 回滚/应急 drop 只能走 V15 前备份恢复。这与 DEC-0047=A 的“有审计行不回滚”
      一致，不是缺陷；但两个操作面未写清：
      (1) RUNBOOK 第 8 章需明示“试发布失败 → 恢复 V15 前备份”，否则运维会以为可做 drop；
      (2) T16b 必须把“无数据→允许”分支排在造数据/关闭动作之前（或换独立栈），否则同一库内先关闭再断言
      “无数据允许”必然失败，演练会自相矛盾。若业务确实需要“试发布失败可干净回退”，需新决策把
      SYSTEM/platform 的 `workflow.feature.changed` 排除出判定（用户所有，不在本轮范围）。
    owner: "T15（RUNBOOK 第 8 章）；T16b（演练顺序）"
    due: "T15/T16b 完成前"
    evidence: >-
      docs/adr/ADR-0024 §10.4 L757-765、§4.8 L406、§10.3 L730、§10.5 L779；
      docs/PRODUCT.md M06-AC-23 L840；.riccon/decisions/DEC-0047.md（A：审计行即拒绝）
notes: >-
  设计阶段 delta 只读复核，未产生 EV（manual 证据不能建立 done），也未要求产生 EV。所有结论以
  `git diff 48211e7..06b3db2`、文件行号与只读 grep/sed 为依据。delta 只改 `docs/ARCHITECTURE.md` 与
  `docs/adr/ADR-0024` 两份文档（43+/28-），无源码/契约/迁移/PRODUCT/状态变更。未运行构建、测试、
  迁移或演练；按 Lead 要求未运行 `riccon gate check --module M06`。本轮不标任务 done、不改状态。
created_at: "2026-11-25T02:00:41+08:00"
---

# REV-M06-OPS-003：M06 运维设计第三轮 delta 复评

## 结论

**不通过（fail）**：OPS-M06-017..023 中 5 条判 `fixed`、1 条 `accepted`，**OPS-M06-021（P2）仍 open**；
另有 2 条由本轮核对新发现的 P3（OPS-M06-024/025）open。无 open P1、无新 P1。

关键判断：上轮 5 个 P2 中的写范围、env 注入、白名单、RUNBOOK 时序、Hikari/claim 节奏都已实质落成，
且与仓库现状（CI 触发器硬编码、ApiApplicationTests 49/2、restore-drill fixtures、worker Hikari 10、
run.mjs 显式 env map、AuditDetailsPolicy fail-closed、V9–V14 Testcontainers 迁移测试）对得上。
**唯一未闭合的 P2 是回滚顺序的根因**：文档把顺序改对了，但 `RICCON_WORKFLOW_ENABLED=false` 并不停止
Worker 领取/执行（§4.8 明确在途继续，§4.3 的停领取是另一个开关），所以“取消→断言 0→回滚 Worker”
仍可能被仍在推进的运行在断言之后插入新的 `WAITING_APPROVAL`，T16b 演练会间歇失败或被放宽成快照断言。

## 评审范围与方法

- **只审 delta**：`git diff 48211e7..06b3db2` 只动 `docs/ARCHITECTURE.md`（评审状态表、交付与预算）与
  `docs/adr/ADR-0024-m06-workflow-orchestration.md`（§3.5、§4.3、§8.1、§10.1–§10.5、§13.1、§14、§15.2、§17）。
- **现状对账**（只读）：`.github/workflows/ci.yml`、`ApiApplicationTests`、`SchemaVersionCheckerTest`、
  `scripts/ops/restore-drill.mjs`、`apps/api/src/test/java/com/riccon/api/migration/**`、
  `apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java`、
  `apps/api/src/main/java/com/riccon/api/config/KnowledgeAclStartupCheck.java`、
  `apps/worker/src/main/resources/application.yml`、`scripts/real-stack/run.mjs`、`docs/RUNBOOK.md`。
- **决策**：DEC-0047=A（回滚口径）、DEC-0046=A（默认值/预算）。PRODUCT M06-AC-23 文本已按 DEC-0047 修订。
- 未修改任何被评审文件；未运行 `riccon gate check --module M06`；未产生 EV。

## OPS-017..025 逐条结论

| ID          | 上轮        | 本轮              | 一句话结论                                                                          |
| ----------- | ----------- | ----------------- | ----------------------------------------------------------------------------------- |
| OPS-M06-017 | P1 open     | **fixed**         | migration/** 入 T01 scope；drop SQL 单一落点 V15MigrationTest，T16b 不存副本        |
| OPS-M06-018 | P2 open     | **fixed**         | T16a 点名 M06 env 注入 + 快照登记（键/默认/来源）；scope 含 scripts/real-stack/**   |
| OPS-M06-019 | P2 open     | **fixed**         | §8.1 白名单对齐 §4.8；T01 承接 AuditDetailsPolicy（断言落点另见 OPS-024）           |
| OPS-M06-020 | P2 open     | **fixed**         | §3.5 拆“可执行断言同提交 / RUNBOOK 由 T15 gate 前”；T15 列出关键数字                |
| OPS-M06-021 | P2 open     | **open**          | 顺序已统一，但关开关不停 Worker，断言仍可被在途运行推翻                             |
| OPS-M06-022 | P2 open     | **fixed**         | Hikari ≥并发+4、claim 轮询/退避、hikaricp 指标入 §4.3/§10.1/§10.2/§10.5 与 T06a/T15 |
| OPS-M06-023 | P3 accepted | **accepted**      | a/b/c/e/f 已补；d（T15 4h）未新增论证，仍偏紧                                       |
| OPS-M06-024 | —           | **P3 open（新）** | “关闭态启动审计断言”无主代码落点，T01 无法独立变绿                                  |
| OPS-M06-025 | —           | **P3 open（新）** | 关闭即写 workflow 审计 → 全 0 判定首次禁用启动后不可达；RUNBOOK/演练顺序需写明      |

### 1) OPS-M06-017（P1）— fixed

- T01 write_scope 已含 `apps/api/src/test/java/com/riccon/api/migration/**`（ADR L819）。
- §15.2 明确：drop SQL 内联在 `.../migration/V15MigrationTest`，验证“无数据可 drop、有数据保留”，
  不新增 `scripts/ops/*.sql`；T16b 只按 §10.4 同序做判定断言，不另存副本（L908-911）。
- 可行性：V9–V14MigrationTest 已用“一次性 Testcontainers MySQL 8.4.13 + Flyway 只到前序版本 + root
  演练 schema”的模式，内联 drop 不需要新设施。T01 的“迁移测试”证据现在有合法写范围。

### 2) OPS-M06-018（P2）— fixed

- T16a（L837）明确 `run.mjs` 显式注入 M06 env，并把键、默认值、来源登记进 `real-stack-env.json`；
  写范围含 `scripts/real-stack/**`。
- 机制已存在：`sharedModelEnv()` 是显式 env map（L900-940），`buildEnvSnapshot()`（L778-820）
  用 `startupCommands` 逐服务列 env 键，快照文件 `ENV_SNAPSHOT_FILE`（L117）。新增 M06 键是扩展，
  不改变“API/Worker 收到同一份 env”的既有约束。

### 3) OPS-M06-019（P2）— fixed（落点残余见 OPS-024）

- §8.1 `workflow.feature.changed` details 已补 `instanceId`/`observedAt`，与 §4.8 启动观测一致（L406/L592）。
- T01 已加 `AuditDetailsPolicy.java` 与“关闭态启动审计断言”，避免白名单外键导致关闭态启动抛错
  （`AuditDetailsPolicy.validate` L391-423 对未知键直接抛 IllegalArgumentException）。
- 残余：观测组件与断言实际落在哪个包未指定（OPS-024）。

### 4) OPS-M06-020（P2）— fixed

- §3.5 item 7 已拆开：CI/ApiApplicationTests/SchemaVersionCheckerTest/restore-drill 与 V15 同提交；
  RUNBOOK 由 T15 在 gate 前更新（V1–V15、57/58、4 触发器、restore 15/4、MAX=15），T01 不阻塞。
- 与仓库对账一致：CI 表数动态推导、触发器硬编码 2（ci.yml:237-246）；ApiApplicationTests 14/49/2；
  restore-drill fixtures 49/48、triggers 2/1；RUNBOOK 仍 V14/48-49/2/MAX=14。T01 同提交后 CI 不会红，
  RUNBOOK 在 T15 前“数字过期但不影响 CI”的窗口被明确接受。

### 5) OPS-M06-021（P2）— open（未闭合）

顺序已统一（§10.4 1..7、§10.5 第 7 章、T16b“回滚顺序断言”），但：

1. `ENABLED=false` 只关 API 受理；§4.8 明确在途运行继续到终态；
2. Worker 领取由 `CLAIM_ENABLED` 单独控制（§4.3 L335、§10.1 L691）；§10.4 第 1 步未关它、未等宽限；
3. 第 2 步断言 `WAITING_APPROVAL=0` 之后，仍 PENDING 的运行会被领取并可能在 APPROVAL 节点生成新行；
4. 第 4 步允许“PENDING/RUNNING 为 0 **或已登记滞留清单**”，把断言与回滚之间的窗口留给在途运行；
5. 回滚后旧 Worker 不接管这些行 → 运行滞留在 `WAITING_APPROVAL`，只能等下次 M06 部署或保留 API 取消。

这正是上轮该 finding 描述的竞争，只是窗口变窄。必须补：停领取 + 宽限（或回滚后复断言），
并取消“滞留清单”对 PENDING 的放行；T16b 断言静默前提而非快照计数。

### 6) OPS-M06-022（P2）— fixed

- §4.3：`DB_POOL_SIZE >= WORKER_CONCURRENCY + 4`、执行槽位不长期占连接、`CLAIM_POLL_INTERVAL_MS=1000`、
  空队列退避至 `CLAIM_IDLE_BACKOFF_MAX_MS=5000`；§10.1：新键 + 启动校验（退避上限 ≥ 轮询周期）；
  §10.2：`hikaricp_connections_pending`/`timeout_total` 与告警；§10.5：配置表与排障；T06a/T15 均有落点。
- 落地点：worker 默认 `RICCON_DB_POOL_SIZE:10` 必须由 T01 改为 16，否则默认配置触发 fail-closed。
  real-stack 的 25/50 满足下限，无冲突。每实例连接数与副本数的容量换算应在 T15 RUNBOOK 写明。

### 7) OPS-M06-023（P3）— accepted（d 项残余）

- a/b/c/e/f 均已补（AC-23 映射加 T16b；fixtures 58/57、triggers 4/3；首发固定 false；5 MiB 实插归 T16b；
  两个 scheduler pool 不同）。
- d（T15 4h）未新增论证，而 T15 范围又扩大（Hikari 规则、claim 节奏、hikaricp 指标、11 章 RUNBOOK）。
  沿用 accepted：非关键路径、模块有余量；若超支按 §13.1 的候选后移调剂，不得删回滚/预检章节。

### 8) OPS-M06-024（P3，新）— open

T01 的“关闭态启动审计断言”没有主代码落点：观测组件的自然位置是 `api/config/Workflow*.java`（T15），
审计测试包 `api/test/.../audit/**`（T16a）。T01 若只加断言会红，若把组件塞进 `workflow/web/**` 则语义错位。
建议建卡时二选一：观测组件随 T01（补 scope），或断言移到 T15/T16a。

### 9) OPS-M06-025（P3，新）— open

判定 SQL 把 `workflow.*` 审计行计入，而关闭开关本身会在 API 启动时写 `workflow.feature.changed`
（§4.8），§10.3 又固定首发关闭。因此首次禁用启动后“全 0”永不成立，全量 API 回滚/应急 drop 只能
恢复 V15 前备份。这是 DEC-0047=A 的直接后果，不是缺陷；但 RUNBOOK 第 8 章应写明“试发布失败 → 备份恢复”，
T16b 必须把“无数据→允许”分支排在关闭动作之前（或换独立栈），否则同库演练自相矛盾。

## 任务/工时核对

| 项       | 声明         | 核算                                                    | 结论                   |
| -------- | ------------ | ------------------------------------------------------- | ---------------------- |
| 任务数   | 21           | T01..T17 拆分后 21 个（T06a/b、T07a/b、T12a/b、T16a/b） | 成立                   |
| M06 工时 | 120h         | 9+6+6+6+6+6+8+5+5+6+5+10+2+5+5+4+4+4+7+4+7 = 120        | 成立                   |
| 关键路径 | ≈75h         | 9+6+6+6+6+6+8+5+5+7+4+7 = 75                            | 成立                   |
| 前端链   | ≈41h         | 9+6+10+2+5+5+4 = 41                                     | 成立                   |
| T16a     | 7h           | 表内 7h（6→7，F-007 安全/审计套件）；T17 8→7 抵消       | 成立（仍偏紧，见风险） |
| T16b     | 4h           | 表内 4h；回滚演练 + 容量 + 包大小                       | 成立                   |
| M26      | 48h / 6 任务 | 4+8+10+8+10+8 = 48                                      | 成立                   |

无新 P1。工程与预算口径自洽；OPS-021 是设计语义缺口，不是工时/范围问题。

## 证据命令（只读）

```bash
git log -3 --format='%H %s'
git diff --stat 48211e71..06b3db29
git diff 48211e71..06b3db29
sed -n '255,300p;320,345p;580,600p;660,712p;728,790p;800,860p;895,915p;965,1015p' docs/adr/ADR-0024-m06-workflow-orchestration.md
grep -n "同一提交\|RUNBOOK\|CLAIM_ENABLED\|featureEnabled" docs/adr/ADR-0024-m06-workflow-orchestration.md
sed -n '224,248p' .github/workflows/ci.yml
sed -n '38,50p' apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java
sed -n '770,815p' scripts/ops/restore-drill.mjs
sed -n '1,30p' apps/worker/src/main/resources/application.yml
sed -n '778,820p;890,960p' scripts/real-stack/run.mjs
sed -n '385,430p' apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java
ls apps/api/src/test/java/com/riccon/api/migration/
grep -n "V1–V14\|48/49\|2 个触发器\|MAX(version)=14" docs/RUNBOOK.md
```

未执行构建/测试/迁移/演练；未运行 `riccon gate check --module M06`（按 Lead 要求避免污染 state）。

## 剩余风险

1. **回滚断言稳定性（P2，OPS-021）**：修复前 T16b 演练可能间歇失败；若为通过而放宽断言，会掩盖
   “回滚后滞留 WAITING_APPROVAL”的真实问题。
2. **T15 4h**：范围继续扩大（Hikari/claim/hikaricp/RUNBOOK），虽不在关键路径，仍可能在 gate 前挤压
   RUNBOOK 质量；建卡时建议 Lead 复核是否从 T10/T11 余量再调剂 1–2h。
3. **T16a 7h**：10 条 E2E + axe + 50 并发 perf + 5 个安全/审计套件 + env 快照 + 零 skip，属高密度任务；
   若 T16a 延期，T16b/T17 与 gate 顺延，需在 T16a 中途设检查点。
4. **演练“回滚 Worker”的物资前提**：T16b 需要一份 M06 前 Worker 镜像/构建（单机 preview 无私服 registry
   时需按提交构建旧 jar）。ADR 未写明演练如何取得旧版本；建议 T15 RUNBOOK 第 11 章与 T16b 脚本注明
   “旧 Worker 来源（tag/镜像 digest）”，否则演练只能模拟“停止领取”，不构成真实回滚证据。
5. **每实例连接数**：`RICCON_DB_POOL_SIZE` 默认 10→16 会放大每副本连接数；多副本 + API 池之和需
   在 RUNBOOK 给出与 MySQL `max_connections` 的换算，避免客户扩容后连接耗尽。
6. **全量回滚窗口（OPS-025）**：首次禁用启动后只能备份恢复；若客户要求试发布可干净回退，需用户决策
   （排除 SYSTEM/platform 的 `workflow.feature.changed`），不建议团队静默改 AC-23。

## 下一步（不阻塞其他事务）

1. Architect 改 §10.4 第 1/4 步与 §10.5 第 7 章：加入 `CLAIM_ENABLED=false` + 宽限/回滚后复断言，
   取消“滞留清单”对 PENDING 的放行；T16b 断言静默前提。
2. Lead 建卡时处理 OPS-024 的写范围二选一；T15/T16b 按 OPS-025 写明备份恢复与演练顺序。
3. 完成后开 REV-M06-OPS-004 只复验 OPS-021/024/025 三条即可（其余 7 条已 closed）。
