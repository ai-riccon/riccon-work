---
review_id: REV-TASK-0180-001
module_id: M05
task_id: TASK-0180
reviewer:
  agent: riccon/verifier
  session: ses_dfc21aee9ffemyxNIlgT4Mjoga
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-TASK-0164-001
reviewed_commits:
  - 5118aaca4559d98ca9c30e8f6802249fd011128c
  - eb7285b125c74f49c694cc0e386547ef9b682794
  - 74b7c8681d9ad49fabc920cf0d889dda7b926d34
  - 795f939cfe061286dcbe669489f698dd43eee161
  - 2f5e41b9780ecd9bf89204ebb99d03cd9387d5e0
head_commit: 8f87e43a0aa49ed6ed9f19ff0975f11ab5b385f0
evidence: [EV-0778, EV-0781, EV-0782, EV-0788, EV-0790, EV-0791]
reviewed_evidence: [EV-0778, EV-0782, EV-0790, EV-0791]
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-core + riccon/architect"
    due: "M05b 连接器同步实现前"
    title: "legacy=true 回滚路径仍用 default 30m 看门狗处理 heavy 事件"
    summary: >-
      F-005 已修复新旧调度互斥，但旧 OutboxScheduler 仍调用无通道 pollOnce()；
      OutboxPoller.startLease 只判断 heartbeat/laneProperties 非空，生产 Spring 主路径两者都存在，
      因此 legacy 路径也会启动心跳，并用 laneProperties.maxProcessing(null)=30m 作为看门狗。
      legacy=true 且 RICCON_CONNECTOR_ENABLED=true 时，OutboxProcessingTimeoutValidator 只校验
      heavy>=60m，实际连接器同步仍会在 30m 被中断；FILE_PARSE/INDEX 当前远短于 30m，故 M05 内影响有限。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxScheduler.java:38-44；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxPoller.java:117-133、300-309；
      apps/worker/src/main/java/com/riccon/worker/run/config/OutboxLaneProperties.java:160-168；
      apps/worker/src/main/java/com/riccon/worker/run/config/OutboxProcessingTimeoutValidator.java:50-60；
      docs/adr/ADR-0023-m05-knowledge-depth.md:716-717。
    accepted_reason: >-
      默认 legacy=false，生产正常路径不触发；M05 内 heavy 事件（解析 180s/OCR 600s）低于 30m，
      连接器同步在 M05b 才实现。互斥修复本身已满足 F-005 的任务验收；剩余语义风险只在回滚窗口+
      M05b 连接器组合出现，必须在 M05b 开工前决定“legacy 不启动心跳/按事件类型选 65m/回滚禁用连接器”
      之一，并在回滚演练中验证，因此按 P2 accepted 而非 open。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/lead + riccon/builder-kb-core"
    due: "M05 gate 前"
    title: "F-012 的 application-test.yml 注释仍写 V1–V11（不在本任务 write_scope）"
    summary: >-
      TASK-0180 更新了 ApiApplicationTests.java:14 与 V11MigrationTest.java:46 的 V1–V12 注释，
      但 apps/api/src/test/resources/application-test.yml:2 仍写“Flyway 在容器内按 V1–V11 建库”。
      该路径不在 TASK-0180.write_scope（只含 apps/api/src/test/java/**），不是 builder 越界；
      它属于原 F-012 列出的三处之一，因此 F-012 只完成 2/3。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:14；
      apps/api/src/test/java/com/riccon/api/migration/V11MigrationTest.java:46；
      apps/api/src/test/resources/application-test.yml:2；
      .riccon/tasks/TASK-0180.json:8-16（write_scope 不含 test/resources）。
    accepted_reason: >-
      纯注释漂移，不影响 Flyway 实际执行（EV-0772/EV-0782 的迁移测试已断言 schema 12）；
      文件在写范围外，本任务无法修。Lead 需在 M05 gate 前把该路径补进后续任务或扩 scope，
      不能把 TASK-0180 的 P3 验收说成完整闭环。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前"
    title: "调度池校验对只启用 default 的实例按 1+heavy-concurrency 要求容量"
    summary: >-
      OutboxSchedulingPoolValidator 用 defaultLaneEnabled ? 1+heavyConcurrency : heavyConcurrency
      计算下限；但 OutboxLaneScheduler 只为启用的通道提交任务，lanes=default 时实际只提交 1 个任务。
      因此 RICCON_WORKER_OUTBOX_LANES=default 且 heavy-concurrency=4..8 时，即使没有 heavy 任务，
      默认 pool=4 也会被拒绝启动（required 5..9）。不属于默认配置失败，且 fail-fast 有可操作提示；
      测试只覆盖 default+heavy 和 heavy-only，没有 default-only 反向用例。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/config/OutboxSchedulingPoolValidator.java:66-75；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLaneScheduler.java:78-96；
      apps/worker/src/test/java/com/riccon/worker/run/config/OutboxSchedulingPoolValidatorTest.java:19-77。
    accepted_reason: >-
      默认 lanes=default,heavy、heavy=2，required=3<4，正常路径不受影响；只有显式关闭 heavy
      却保留高 heavy 并发的非典型配置会误拒绝。作为 P3 接受，M05 gate 前可改为按启用通道计算
      （default 存在加 1，heavy 存在加 N）并补 default-only 测试；不阻断本任务。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "TASK-0180 closeout / M05 gate 前"
    title: "gate 把 TASK-0180 之后 M24 的 apps/web 测试算作本任务越界"
    summary: >-
      .riccon/state.json 的 M05 gate 记录“task TASK-0180 changed files outside write_scope:
      apps/web/src/pages/chat/knowledge-and-sharing.test.tsx”。但 git diff e059162..5118aac 的
      16 个文件全部在 TASK-0180.write_scope；该 web 文件来自 end_commit 之后的 M24 提交
      8eefc20，是共享工作树并行任务的变更被 gate 按 baseline..HEAD 归到本任务。同一份 gate 记录
      还把 TASK-0180 的 16 个文件列进 TASK-0165 的越界列表，说明归因噪声是双向的。
    evidence: >-
      .riccon/state.json:gates.reasons（checked_at 2026-11-22T23:29:52+08:00）；
      git diff --name-status e059162..5118aac（16/16 全在 write_scope）；
      git show --stat 8eefc20（M24 knowledge-and-sharing 测试修复）；
      docs/COLLABORATION.md:22（仅禁止 Serena LSP 与全量 Maven 并发，未覆盖多 agent 提交窗口）。
    accepted_reason: >-
      本任务产物没有越界；这是 gate 的 baseline/并发任务归因噪声，不影响 TASK-0180 技术验收。
      Lead 在 closeout 时用 end_commit/reviewed_commits 或调整 gate 口径消除；同时应把
      “共享工作树并行 Maven/任务提交”窗口写进 RUNBOOK/协作流程（当前文档只禁止 Serena LSP
      与全量 Maven 并发，未覆盖多 agent 同时 Maven/提交）。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前（可由 B3/V1 失败注入覆盖）"
    title: "OutboxPoller 心跳路径的 RuntimeException 租约丢失/超时分支仍未直接覆盖"
    summary: >-
      EV-0782 JaCoCo 显示 OutboxPoller 的 205/213/219/222/230 行未覆盖：
      EventProcessingException 之后的 timeout 分支、以及 RuntimeException 分支的
      discardIfLeaseLost/timedOut 处理。现有 Watchdog 集成测试覆盖了正常返回的 timeout（253）
      和 EventProcessingException 的 lease-lost（202），但没有覆盖“handler 抛未预期异常后
      租约已丢失/已超时”的语义；静态路径正确（先 discard、后 timedOut、再按 500 重试）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxPoller.java:200-247；
      apps/worker/target/site/jacoco/com.riccon.worker.run.service/OutboxPoller.java.html:205/213/219/222/230；
      apps/worker/src/test/java/com/riccon/worker/outbox/OutboxLeaseWatchdogIntegrationTest.java:68-211。
    accepted_reason: >-
      核心验收语义（租约丢失不落结果、超时 504 可重试、按通道取 max-processing）已被真实 DB
      集成测试覆盖；缺口是未预期异常与心跳组合的边界，静态路径简单且不改变默认行为。
      M05 gate 前可补一个 handler 抛 RuntimeException 的 lease-lost/timeout 用例，或由 B3/V1
      的失败注入覆盖；作为 P3 接受，不影响本任务 pass。
notes: >-
  任务级独立评审，不是 M05 模块评审、Product 预验收或用户验收。verdict=pass：F-002/F-003/F-004/F-005
  的核心验收与 P3 F-010/F-013 已闭环；另留 4 条 P3 accepted（F-012 写范围外注释、调度池
  default-only 边界、gate 并发归因噪声、poller RuntimeException 心跳边界）与 1 条 P2 accepted
  （legacy+连接器 30m 看门狗，交 M05b）。独立 EV-0790（worker 22 tests）与 EV-0791（API 2 tests）
  均 exit 0、dirty=false；EV-0788 因并行任务未提交 docs 被记录为 dirty，保留日志但未绑定；
  EV-0781 为 builder 失败的 jacoco:check 运行，保留未删且未作为通过证据。
created_at: "2026-11-22T15:40:30Z"
---

# REV-TASK-0180-001：TASK-0180 / M05-B1 评审整改独立评审

本评审是任务级独立技术验收，不是 M05 模块评审、Product 预验收或用户产品验收。评审对象是
`REV-TASK-0164-001` 的 F-002/F-003/F-004/F-005 与 P3 F-010/F-012/F-013（以及 F-014 的口径确认），
代码提交 `5118aac`（16 文件），证据提交 `74b7c86`。

## 1. 结论

- **未发现 P0/P1。** F-002 的调度器/看门狗测试、F-003 的调度池校验、F-004 的按通道看门狗、
  F-005 的新旧调度互斥均有代码、测试和配置三元组；F-010/F-013 的可观察性断言也已补齐。
- **独立定向验证通过**：EV-0790（worker 22 tests：Scheduler 5、PoolValidator 4、
  TimeoutValidator 4、Watchdog 4、LaneProperties 5）与 EV-0791（API 2 tests：
  KnowledgeAclStartupCheckTest）均 exit 0、`dirty=false`、0 fail/0 error/0 skip。
- **verdict=`pass`**（任务级）。保留 1 条 P2 accepted（legacy 回滚路径仍用 30m 看门狗，交 M05b）
  与 4 条 P3 accepted（F-012 写范围外注释、调度池 default-only 边界、gate 并发归因噪声、
  poller RuntimeException 心跳边界）；
  没有 open finding。
- **F-012 不是完整闭环**：`application-test.yml:2` 仍写 V1–V11，但该文件不在本任务 write_scope，
  是 Lead 的任务切分缺口，不是 builder 越界；M05 gate 前必须补任务或扩 scope。

## 2. 评审范围与证据审计

### 2.1 提交与写范围

| 项目          | 值                                                                                                 |
| ------------- | -------------------------------------------------------------------------------------------------- |
| 任务 baseline | `e059162041bbbfc739c37281f6c882ba6710b7f2`                                                         |
| 代码提交      | `5118aaca4559d98ca9c30e8f6802249fd011128c`（16 文件，+1257/−32）                                   |
| 证据提交      | `74b7c8681d9ad49fabc920cf0d889dda7b926d34`                                                         |
| 全量证据提交  | `eb7285b125c74f49c694cc0e386547ef9b682794`（EV-0782；`5118aac..eb7285b` 只有 `.riccon/**`）        |
| 当前 HEAD     | `8f87e43a0aa49ed6ed9f19ff0975f11ab5b385f0`（`2f5e41b..8f87e43` 只有 `.riccon/TASK-0182` closeout） |
| 代码 diff     | `git diff --check e059162..5118aac` exit 0；16/16 文件全部匹配 `TASK-0180.write_scope`             |

逐项核对：`git diff --name-status e059162..5118aac` 的 16 个文件分别落在
`apps/worker/src/main/java/com/riccon/worker/run/**`、`apps/worker/src/main/resources/application.yml`、
`apps/worker/src/test/java/com/riccon/worker/outbox/**`、`apps/worker/src/test/java/com/riccon/worker/run/**`、
`apps/api/src/test/java/com/riccon/api/config/**`、`ApiApplicationTests.java`、
`V11MigrationTest.java`；没有 `contracts/**`、`apps/web/**`、迁移或其他模块生产代码夹带。
`state.json` 里“TASK-0180 changed files outside write_scope: apps/web/...knowledge-and-sharing.test.tsx”
来自 `8eefc20`（TASK-0180 end_commit 之后的 M24 提交），不是本任务产物（见 F-004）。

### 2.2 证据核对

| EV      | 命令                                                                         | commit    | dirty | exit | 结果/核对                                                                                                   |
| ------- | ---------------------------------------------------------------------------- | --------- | ----- | ---- | ----------------------------------------------------------------------------------------------------------- |
| EV-0778 | `./mvnw -q spotless:check`                                                   | `5118aac` | false | 0    | 全仓格式通过；日志 249 bytes，SHA-256 `738319c0…` 与 JSON 一致                                              |
| EV-0781 | `./mvnw -q -T1C verify jacoco:report`                                        | `eb7285b` | false | 1    | **失败保留**：`jacoco:check`（riccon-api coverage）未达标；日志 1.7 MB，SHA-256 `2c57c196…`；未作为通过证据 |
| EV-0782 | `./mvnw -q -T1C verify jacoco:report`                                        | `eb7285b` | false | 0    | builder 全量重跑；633.6 s；日志 SHA-256 `862f348d…` 与 JSON 一致                                            |
| EV-0788 | worker 定向（同 EV-0790）                                                    | `557e937` | true  | 0    | 测试本身 22/22 通过，但运行期间并行任务有未提交 docs，dirty=true；保留日志，未绑定 review/DoD               |
| EV-0790 | `./mvnw -q -T1C -pl apps/worker -am test -Dtest='OutboxLane*…'`              | `795f939` | false | 0    | verifier 独立重跑；22 tests，0 fail/error/skip；74.7 s；干净树                                              |
| EV-0791 | `./mvnw -q -T1C -pl apps/api -am test -Dtest='KnowledgeAclStartupCheckTest'` | `2f5e41b` | false | 0    | verifier 独立；2 tests，0 fail/error/skip；12.3 s；干净树                                                   |

- **EV-0781 诚实性**：失败原因是 `[ERROR] Failed to execute goal … jacoco-maven-plugin:0.8.16:check
… Coverage checks have not been met`，不是测试失败；EV-0782 的 note 写明“重跑：干净树+无并行 Maven”。
  任务卡仍列出 EV-0781，但评审只把它当失败记录，不当通过证据；EV-0782/EV-0790/EV-0791 的 exit 0 成立。
- **EV-0788 处理**：这是一次被并行任务污染 dirty 的运行（`recordEvidence` 自动追加到任务卡后已从
  `TASK-0180.evidence` 移除；文件保留在 `.riccon/evidence/TASK-0180/` 供追溯）。干净重跑为 EV-0790；
  两者测试集合与退出码一致，后者是唯一绑定的 worker 证据。
- **全量计数**：EV-0782 在同一棵树的 surefire 报告为 **1241 tests**（API 701、worker 419、
  platform-architecture 12、platform-common 87、platform-domain 22），0 fail/0 error/0 skip。
  builder 口述的 1229 少了 platform-architecture-tests 的 12 个 ArchUnit 测试；不是测试缺失，
  但应在回执中写清口径。builder 自报的类级分布（Scheduler 4 / LaneProperties 6）与 surefire
  实际（Scheduler 5 / LaneProperties 5）相反，总数 22 一致；本评审以 surefire 为准。
- **提交可追溯**：`5118aac..eb7285b` 只有 `.riccon/**`（EV-0778/0780/任务状态），B1 的 16 个文件
  在代码提交后未变；`git log 5118aac..HEAD -- <任务路径>` 为空，因此 EV-0782/EV-0790/EV-0791
  对 `5118aac` 的代码有效。

## 3. 任务验收对账

| 验收                                                                       | 结论                      | 依据                                                                                                                                                                                                                               |
| -------------------------------------------------------------------------- | ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-002：scheduler 启动/停止/通道并发/legacy 互斥 + poller 租约丢失/超时 504 | **pass**                  | `OutboxLaneSchedulerTest` 5 tests；`OutboxLeaseWatchdogIntegrationTest` 4 tests 真实 MySQL；EV-0790 22/22 通过；EV-0782 JaCoCo `OutboxLaneScheduler` line 39/43（90.7%）、branch 11/12（91.7%），不再是 0/42、0/12                 |
| F-003：启动期校验 pool >= 1+heavy；4–8 不再静默截断；有测试                | **pass（with residual）** | `OutboxSchedulingPoolValidator` + 4 tests；EV-0782 全量 verify 在默认 pool=4、heavy=2 下通过；EV-0790 通过。残余 F-003：default-only 高 heavy 配置会误拒绝（P3 accepted）                                                          |
| F-004：default 30m / heavy 65m；解决连接器 60m 冲突；有测试/配置说明       | **pass**                  | `OutboxLaneProperties` 默认值/按通道取值；`OutboxProcessingTimeoutValidator` 59m 失败、60m 通过；EV-0790 通过；ADR §7.3:712-717 与 §7.5:757 已由 TASK-0179/EV-0780 更新，键名与 `application.yml:107-114` 一致；504 只用于超时分支 |
| F-005：legacy=true 时 lane scheduler 不注册；新旧互斥；有测试              | **pass（with residual）** | 两个 `@ConditionalOnProperty` 条件 + 四组合 `ApplicationContextRunner` 测试；该测试对旧实现（缺 legacy 条件的 lane scheduler）会失败。残余 F-001：legacy 路径仍以 30m 看门狗处理 heavy 事件（P2 accepted，交 M05b）                |
| P3：409 可观察或删除；V1–V11 注释更新；ACL WARN/指标直接断言；500 文案不变 | **pass（with residual）** | 409 出现在 heartbeat/poller 日志并由集成测试断言；500 静态文案未改；`KnowledgeAclStartupCheckTest` 2 tests（gauge 1/0 + WARN 正反）；V1–V11 只改 2/3，`application-test.yml:2` 仍在写范围外（F-002 P3 accepted）                   |
| 最终提交 spotless + verify exit 0，EV dirty=false                          | **pass（builder 证据）**  | EV-0778（spotless，`5118aac`，dirty=false，exit 0）与 EV-0782（verify，`eb7285b`，dirty=false，exit 0）；两者 SHA-256 与 JSON 一致。verifier 未重跑 634 s 全量，按 L2 用定向 EV-0790/EV-0791 复验受影响行为                        |

## 4. 原 finding 逐条结论

| 原 finding | 级别 | 本任务结论         | 说明                                                                                                                                                                                                                                                     |
| ---------- | ---- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-002      | P2   | **fixed**          | Scheduler 测试覆盖 default=1 + heavy=N、排除 legacy、异常隔离、stop 取消、四组合互斥；Watchdog 测试用真实 MySQL 覆盖租约丢失成功/失败不落库、heavy 150ms/default 30s 与反向配置的 504 重试。EV-0782 JaCoCo 显示 scheduler line 39/43、branch 11/12       |
| F-002 残余 | P3   | **accepted**       | `OutboxPoller` 的 205/213/219/222/230 行（RuntimeException + 心跳的 timeout/discard 分支）仍未直接覆盖；核心 lease-lost/504 语义已覆盖，静态路径正确，见 F-005（P3 accepted）                                                                            |
| F-003      | P2   | **fixed**          | `OutboxSchedulingPoolValidator` 启动期比较 pool 与 1+N（heavy-only 为 N），失败信息给出两个配置键；4 tests 覆盖 1/2/4/8、不足、heavy-only、disabled/legacy 跳过。配置映射 `application.yml:7-9` 一致；残余 default-only 边界见 F-003                     |
| F-004      | P2   | **fixed**          | `max-processing`=30m、`heavy-max-processing`=65m，`maxProcessing(lane)` 按通道取值；`OutboxProcessingTimeoutValidator` 在连接器启用且 heavy 启用时要求 >=60m（59m 失败、60m 通过），关闭/仅 default 跳过；ADR/ARCH 已由 TASK-0179 更新；504 只在超时分支 |
| F-005      | P2   | **fixed**          | `OutboxLaneScheduler` 增加 `legacy=false` 条件；`OutboxScheduler` 只在 `legacy=true` 注册；四组合测试锁定互斥；旧实现会因 lane scheduler 仍注册而失败。回滚窗口的 30m 看门狗语义见 F-001（P2 accepted）                                                  |
| F-010      | P3   | **fixed**          | `RICCON-WORKER-409` 写入 `outbox.lease_lost` / `outbox.event.lease_lost_discarded` / retry/done/dead 失败日志；`OutboxLeaseWatchdogIntegrationTest` 断言日志含 409；`RICCON-WORKER-500` 文案未改；504 只用于超时                                         |
| F-012      | P3   | **partial**        | `ApiApplicationTests.java:14`、`V11MigrationTest.java:46` 已改 V1–V12；`application-test.yml:2` 仍 V1–V11，且不在 write_scope（见 F-002）                                                                                                                |
| F-013      | P3   | **fixed**          | `KnowledgeAclStartupCheckTest` 直接调用 `run`，用 `SimpleMeterRegistry` 断言 false→gauge=1 + WARN、true→gauge=0 + 无 WARN；EV-0791 独立 2/2 通过                                                                                                         |
| F-014      | P3   | **closed by plan** | ADR §5.6:390 明确 `admin_read.documentId/visibility` 仅 `scope=LIST` 可空；TASK-0166（B3）验收已写明在策略层落实该边界，因此 TASK-0180 不改 `AuditDetailsPolicy` 符合分工；EV-0780 已记录 ADR/ARCH 对齐                                                  |

## 5. 独立验证明细

### 5.1 EV-0790（worker，干净树重跑）

```bash
./mvnw -q -T1C -pl apps/worker -am test \
  -Dtest='OutboxLaneSchedulerTest,OutboxSchedulingPoolValidatorTest,OutboxProcessingTimeoutValidatorTest,OutboxLanePropertiesTest,OutboxLeaseWatchdogIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- commit `795f939cfe061286dcbe669489f698dd43eee161`；`dirty=false`；exit 0；74.7 s。
- surefire：**22 tests，0 fail/0 error/0 skip**：Scheduler 5、PoolValidator 4、TimeoutValidator 4、
  Watchdog 4、LaneProperties 5。
- 日志可见真实 Testcontainers MySQL 8.4.13（schema 12）；`outbox.processing_timeout` 带
  `RICCON-WORKER-504`，`outbox.lease_lost`/`outbox.event.lease_lost_discarded` 带
  `RICCON-WORKER-409`，`outbox.event.retry_scheduled` 为可重试分支。异常隔离测试的 ERROR 堆栈是
  预期负向输出，不是失败（surefire 0 failure）。
- EV-0788 是同命令的首次运行，22/22 通过但 `dirty=true`（并行任务未提交 docs）；未绑定 review，
  由 EV-0790 取代。

### 5.2 EV-0791（API，干净树）

```bash
./mvnw -q -T1C -pl apps/api -am test \
  -Dtest='KnowledgeAclStartupCheckTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- commit `2f5e41b9780ecd9bf89204ebb99d03cd9387d5e0`；`dirty=false`；exit 0；12.3 s。
- surefire：**2 tests，0 fail/0 error/0 skip**；日志只有预期 `RICCON-KB-001` WARN（关闭分支）。
- 该命令未改生产/测试/迁移；只生成 target/ 报告。

### 5.3 证据真实性

- EV-0778/EV-0781/EV-0782/EV-0788/EV-0790/EV-0791 的日志 SHA-256 均与 JSON 一致；EV-0778/0782
  的 commit/dirty/exit 与任务要求一致。
- EV-0781 保留 exit 1，未在任何 pass 结论中被引用；EV-0782 的 note 明确“干净树+无并行 Maven”。
- `reviewed_evidence` 只绑定 EV-0778/EV-0782/EV-0790/EV-0791；EV-0781（失败）与 EV-0788（dirty）
  仅作追溯记录。

## 6. 未验证 / 没有做什么

- **未重跑 634 s 全量 verify**：按 L2/任务级分层，使用 builder 的 EV-0782 + verifier 定向
  EV-0790/EV-0791；模块 gate 仍需 Verifier 在 M05 最终提交上重跑全量 test/coverage/E2E。
- **未在真实浏览器跑 E2E/a11y**：本任务无用户可见 UI/路由/文案，B1 不涉及；M05-V1/V2 覆盖。
- **未验证 M05b 连接器同步**：30m/60m 的 legacy 组合是设计残余（F-001），连接器尚未实现。
- **未验证生产锁等待/回滚演练**：不属于 TASK-0180 范围；ADR/恢复演练由 M09/TASK-0177 等承接。
- **未运行 `riccon gate check --module M05` 作为本评审结论**：M05 仍 `building`，有 18 个
  planned/review 任务；gate 此时必然失败，且 task review 不参与模块 gate。模块评审应在 B3–B11/
  O1a/O1b/V1/V2 完成后由 Verifier 执行。
- **EV-0782 的 Surefire 尾部警告**：日志末尾仍有 `[ERROR] Surefire is going to kill self fork JVM`，
  与 TASK-0164 评审记录的历史测试基础设施警告相同；它没有导致 skip/timeout/exit≠0，也不是本任务
  引入；作为 M05 gate 的测试基础设施残余保留观察。

## 7. 剩余风险与下一步

1. **M05b 前**：F-001 需要决定 legacy 回滚路径的看门狗语义（推荐：legacy 路径按事件类型选择
   heavy 65m，或显式禁用连接器同步）；否则 `RICCON_CONNECTOR_ENABLED=true` + legacy=true 时连接器
   同步仍会被 30m 中断。
2. **M05 gate 前**：F-002 把 `apps/api/src/test/resources/application-test.yml` 补进任务并改 V1–V12；
   F-003 按启用通道修正池公式并补 default-only 测试；F-004 在 Lead closeout 时消除 gate 并发归因
   噪声，并把共享工作树并行 Maven/提交窗口写进 RUNBOOK/流程；F-005 补 RuntimeException + 心跳的
   lease-lost/timeout 用例（或由 B3/V1 失败注入覆盖）。
3. **模块 gate**：本评审不能替代 M05 模块评审；B3 仍需按 TASK-0166 验收落实 admin_read
   必填/可空边界（F-009/F-014）和 403/404 顺序（REV-TASK-0165-001 F-001）。
