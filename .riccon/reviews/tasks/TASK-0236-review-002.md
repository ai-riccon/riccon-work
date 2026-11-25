---
review_id: REV-TASK-0236-002
module_id: M06
task_id: TASK-0236
reviewer:
  agent: riccon/verifier
  session: ses_deed19d4fffegPEwsjti1V5wSE
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-TASK-0236-001
reviewed_commits:
  - 859a2c4a4988632ccaa401f2ef0286990c93fe11
  - 5e9de1c0accbe29f03c4050e9f4b60e4bdf00191
head_commit: 5e9de1c0accbe29f03c4050e9f4b60e4bdf00191
code_commit: 859a2c4a4988632ccaa401f2ef0286990c93fe11
evidence_commit: 5e9de1c0accbe29f03c4050e9f4b60e4bdf00191
baseline_commit: b62fea55457542c9d8beb2032fcef9ab3287108e
task_card_baseline_commit: b62fea55457542c9d8beb2032fcef9ab3287108e
review_scope: >-
  M06 T15（TASK-0236）第二轮独立任务评审：只审 REV-TASK-0236-001 的修复与残余。核对任务卡
  evidence 是否仅为 EV-1073/EV-1074/EV-1075/EV-1076、四条 EV 的 commit/dirty/exit/cwd/hash/bytes
  与日志；核对 859a2c4 是否仅格式化 6 个 T15 worker 文件并修正 RUNBOOK 指标名与 schema 口径；
  在 `git archive 859a2c4` 的干净导出独立复跑 52 个 T15 定向测试 + ArchUnit 12 + 默认 context 1，
  并复验 spotless（API 全量、worker T15 scoped、全仓残余归因）。不重审 T15 全量实现（代码仍为
  0a57d54，本轮仅格式/文档修订），不复审 T06b 串行引擎/节点执行（TASK-0225）、T16a E2E/axe/perf、
  T16b 回滚演练，不替代 M06 模块 review/Product 预验收/用户最终验收，不运行 gate check。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  review: true
  docs: true
  security: true
  evidence: true
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - performance
  - accessibility
  - migration
dod_note: >-
  acceptance 记 true 按 **pass (with residual)** 理解：T15 任务范围的 API/Worker/文档面在上一轮已
  逐项评审，本轮只关闭证据、格式与文档残余；AC-17 的界面可见（E2E-M06-09）与 AC-23 的
  OPS-M06-01 回滚演练仍归 T16a/T16b，不因 T15 pass 而视为模块 AC 通过。build 由 859a2c4 干净导出
  中 Maven test 编译支撑；tests 由 52 个 T15 定向用例（7 类）+ ArchUnit 12 + 默认 context 1 在
  干净导出 exit 0 支撑；lint 由 API 模块全量 spotless exit 0 + worker T15 scoped spotless exit 0
  支撑，全仓 spotless 残余仅 T06b `ModelNodeExecutorTest`（TASK-0225，不属 T15）；docs 由 RUNBOOK
  指标全名与 schema 15 口径修复支撑；security 沿用上一轮对新端点 authz/租户隔离/无密钥的结论
  （859a2c4 只做格式化与文档，未改行为）；evidence 由 EV-1073..EV-1076 四条 dirty=false、exit=0、
  主工作树取证的 clean EV 支撑；scope 由 859a2c4 的 7 个文件全部落在 T15 write_scope 支撑。
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "REV-TASK-0236-001 F-001 已关闭：T15 evidence 收敛为 EV-1073..EV-1076 且全部 clean"
    summary: >-
      `.riccon/tasks/TASK-0236.json:22-27` 的 evidence 现为 `[EV-1073, EV-1074, EV-1075, EV-1076]`，
      不再引用 EV-1068/EV-1069/EV-1070；`.riccon/modules/M06.json`、`.riccon/state.json` 与任务卡
      均无旧 EV 引用（旧 JSON/日志仍保留在 `.riccon/evidence/TASK-0236/` 作为历史，符合保留
      superseded 的规则）。四条 EV 均由 `riccon/builder-b1` 在 commit
      `859a2c4a4988632ccaa401f2ef0286990c93fe11` 的主工作树
      `/Users/ricky/Documents/projects/riccon-work` 录制，`manual=false`、`dirty=false`、
      `exit_code=0`，`output_bytes`/`output_sha256` 与日志逐字节一致：EV-1073 98,142 bytes /
      `1963dcd60399f0cb7a6159cf283a436023a4024d8386c5f63721ebf2863f64d8`、EV-1074 447 /
      `c849053971b64c00b7230fc50dc5aff98579e1a4beababbfc3db2a632e504661`、EV-1075 6,806 /
      `fe5df2b142611636e2e8d4ae5f0691c86677bdd9ef9b4f45db90c5cb49adf3bf`、EV-1076 400 /
      `ba422e1cf84ac3904bc55b17b259ed90bdb6762705b302c4cef3df9f17f5a2ee`；四份日志第 1-6 行的
      command/cwd/started_at/finished_at/exit_code 与 JSON 一致。859a2c4 是 HEAD `5e9de1c` 的祖先；
      `5e9de1c` 只含 `.riccon` 证据/状态与任务卡更新，不含源码。F-001 关闭。
    evidence: >-
      .riccon/tasks/TASK-0236.json:22-27；.riccon/evidence/TASK-0236/EV-1073.json:7-15；
      EV-1074.json:7-15；EV-1075.json:7-15；EV-1076.json:7-15；四份 EV 日志第 1-6 行；
      `.riccon/evidence/TASK-0236/EV-1073-*.log` 98,142 bytes / `1963dcd6…`；
      EV-1074-*.log 447 / `c8490539…`；EV-1075-*.log 6,806 / `fe5df2b1…`；
      EV-1076-*.log 400 / `ba422e1c…`；`git show --stat 5e9de1c`；
      `git merge-base --is-ancestor 859a2c4 HEAD`。
  - id: F-002
    severity: P2
    status: fixed
    title: "REV-TASK-0236-001 F-002 已关闭：T15 worker 文件 spotless 通过，全仓残余仅 T06b ModelNodeExecutorTest"
    summary: >-
      859a2c4 对 6 个 T15 worker 文件做 Palantir 格式化（`WorkflowDatabasePoolGuard`、
      `WorkflowRuntimeConfigValidator`、`WorkflowApprovalWaitJdbc` 及 3 个对应测试），diff 只有
      换行/缩进，无语义变更。独立复核在 `git archive 859a2c4` 干净导出中：
      `./mvnw -q -pl apps/api spotless:check` exit 0；`./mvnw -q -pl apps/worker spotless:check
      -DspotlessFiles='.*src/(main|test)/java/com/riccon/worker/workflow/(config|metrics)/.*\.java'`
      （覆盖 T15 的 11 个 config/metrics 文件，非仅 6 个）exit 0；`./mvnw -q -fae spotless:check`
      全仓 exit 1，唯一违规文件为
      `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java`
      （T06b a99a2ff 新增，TASK-0225 / riccon/builder-b2，不属于 T15 write_scope）。用同一
      `-DspotlessFiles` 只匹配该 T06b 文件做阳性对照 exit 1，证明过滤参数真实生效而非被 spotless
      忽略。EV-1076 记录的 API 全量 + worker scoped 命令与上述一致且 exit 0。F-002 关闭；全仓
      `commands.lint` 仍需 TASK-0225 修复该文件后才能变绿，记录为 T06b 残余，不作为 T15 fail。
    evidence: >-
      git show 859a2c4 -- '*.java'；`git archive 859a2c4` 导出
      `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t15-rev2-859a2c4`；
      API spotless exit 0；worker scoped spotless exit 0；root `-fae` spotless exit 1 且仅
      `ModelNodeExecutorTest.java`；阳性对照 exit 1；EV-1076；TASK-0225.json owner/status；
      AGENTS.md `commands.lint`。
  - id: F-003
    severity: P3
    status: fixed
    title: "REV-TASK-0236-001 F-003 已关闭：RUNBOOK 告警表指标名统一为 riccon_workflow_ 全名"
    summary: >-
      `docs/RUNBOOK.md:1493`、`:1503`、`:1630`、`:1645-1648` 现统一使用 ADR-0024 §10.2
      （`docs/adr/ADR-0024-m06-workflow-orchestration.md:727-733`）的完整指标名：
      `riccon_workflow_run_duration_seconds`、`riccon_workflow_node_duration_seconds{node_type,status}`、
      `riccon_workflow_approvals_pending` / `riccon_workflow_approval_oldest_wait_seconds`、
      `riccon_workflow_result_oversize_total` / `riccon_workflow_result_size_bytes`；M06-4 表中的
      `lease_lost_total`、`heartbeat_renew_failures_total` 也补全为 `riccon_workflow_*` 前缀
      （`:1485-1497`）。用带前缀排除的正则扫描 M06 章节，仅剩 `workflow_run.result_size_bytes`
      这一 DB 列名（非指标），未发现其他无前缀指标。F-003 关闭。
    evidence: >-
      docs/RUNBOOK.md:1485-1497,1493,1503,1630,1645-1648；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:727-733；git show 859a2c4 -- docs/RUNBOOK.md。
  - id: F-004
    severity: P3
    status: fixed
    title: "REV-TASK-0236-001 F-004 已关闭：RUNBOOK 恢复章节 schema 口径统一为 M06 起为 15"
    summary: >-
      `docs/RUNBOOK.md:1694` 已从“M04 起为 11”改为“M06 起为 15”，与 `:255`、
      `scripts/ops/restore-drill.mjs:48,51`（`EXPECTED_SCHEMA_VERSION=15`、`EXPECTED_TRIGGERS=4`）
      和 `apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:61`
      的 `EXPECTED_SCHEMA_VERSION=15` 一致；M06-7/M06-8/M06-9 的 V15、restore 15/4 口径未受影响。
      其余“起为 11”只出现在 M04 历史章节（`:589`、`:647`、`:674`、`:852`），不属于 T15 的 M06
      章节，保留历史上下文。F-004 关闭。
    evidence: >-
      docs/RUNBOOK.md:255,1694；git show 859a2c4 -- docs/RUNBOOK.md；
      scripts/ops/restore-drill.mjs:48,51；
      apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:61。
evidence:
  - EV-1073
  - EV-1074
  - EV-1075
  - EV-1076
reviewed_evidence:
  - EV-1073
  - EV-1074
  - EV-1075
  - EV-1076
notes: >-
  代码/文档提交为 `859a2c4`（T15 修复，7 个文件全部在 T15 write_scope：6 个 worker Java + RUNBOOK）；
  证据与任务卡提交为 `5e9de1c`（仅 `.riccon` 与任务卡，无源码）。评审时 HEAD=`5e9de1c`，主工作树
  `git status --porcelain` 为空。独立复跑不新增 EV，只用于复核 EV-1073..EV-1076 的命令与计数；
  复跑目录为 `git archive 859a2c4` 的临时干净导出。T06b 的 engine/node、T06a 队列实现、
  T16a/T16b 残余均不在本评审范围。
created_at: "2026-11-25T13:39:37+08:00"
---

# REV-TASK-0236-002：M06 T15 第二轮独立评审

## 结论

**通过（pass）**。上一轮 4 个 findings 全部关闭：`F-001`（EV-1068/1069/1070 dirty）由任务卡
evidence 收敛为 EV-1073..EV-1076 四条 clean EV 关闭；`F-002`（worker T15 文件 spotless）由
859a2c4 的格式化和独立 spotless 复核关闭；`F-003`（RUNBOOK 指标全名）与 `F-004`（schema 15
口径）由 RUNBOOK 修订关闭。独立在 `git archive 859a2c4` 干净导出复跑：**52 个 T15 定向测试 +
ArchUnit 12 + 默认 context 1，共 65 tests，0 failures / 0 errors / 0 skipped**；spotless
API 全量 exit 0、worker T15 scoped exit 0，全仓唯一残余是 T06b 的 `ModelNodeExecutorTest`
（TASK-0225，不属 T15）。未发现新 P1/P2；T16a/T16b 残余不阻塞 T15。

## 上一轮 findings 关闭

### F-001（P1, fixed）：clean EV 收敛

- 任务卡 evidence 仅 `[EV-1073, EV-1074, EV-1075, EV-1076]`，不引用 EV-1068/1069/1070；
  `.riccon/modules/M06.json` 与 `.riccon/state.json` 也无旧 EV 引用。旧 EV 文件仍留在
  `.riccon/evidence/TASK-0236/`，仅作历史，不参与 done。
- 四条 EV 均为 `manual=false`、`dirty=false`、`exit_code=0`，cwd 为主工作树
  `/Users/ricky/Documents/projects/riccon-work`；日志第 1-6 行与 JSON 的
  command/cwd/started_at/finished_at/exit_code 一致；bytes/SHA-256 与日志逐字节一致（见下表）。
- EV commit `859a2c4` 是 HEAD `5e9de1c` 的祖先；`5e9de1c` 只提交 `.riccon` 证据/状态与任务卡，
  不含源码。

### F-002（P2, fixed）：spotless

- 859a2c4 只格式化 6 个 T15 worker 文件，`git show 859a2c4 -- '*.java'` 显示改动均为换行/
  缩进，无语义变化。
- 独立复核（干净导出）：

| 检查                                                                                               | 结果                                                      |
| -------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `./mvnw -q -pl apps/api spotless:check`                                                            | exit 0                                                    |
| `./mvnw -q -pl apps/worker spotless:check -DspotlessFiles='…/workflow/(config\|metrics)/.*\.java'` | exit 0（覆盖 11 个文件）                                  |
| `./mvnw -q -fae spotless:check`（全仓）                                                            | exit 1，仅 `ModelNodeExecutorTest.java`（T06b/TASK-0225） |
| 同一 `-DspotlessFiles` 只匹配 `ModelNodeExecutorTest.java` 的阳性对照                              | exit 1（证明过滤参数生效，不是被忽略）                    |

- EV-1076 记录的就是 API 全量 + worker T15 scoped 的组合，exit 0。
- 全仓 `commands.lint` 要等 TASK-0225（owner `riccon/builder-b2`）修复
  `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java`
  后才能变绿；这是 T06b 的格式债，不记 T15 fail，但模块 gate 前必须关闭。

### F-003（P3, fixed）：RUNBOOK 指标名

- `docs/RUNBOOK.md:1493,1503,1630,1645-1648` 已统一为 ADR-0024 §10.2 的完整名称：
  `riccon_workflow_run_duration_seconds`、`riccon_workflow_node_duration_seconds{node_type,status}`、
  `riccon_workflow_approvals_pending` / `riccon_workflow_approval_oldest_wait_seconds`、
  `riccon_workflow_result_oversize_total` / `riccon_workflow_result_size_bytes`；M06-4 表的
  `lease_lost_total`、`heartbeat_renew_failures_total` 也补了 `riccon_workflow_` 前缀。
- 正则扫描 M06 章节后，唯一无前缀的 `result_size_bytes` 是 `workflow_run.result_size_bytes`
  DB 列（`docs/RUNBOOK.md:1631`），不是指标名。

### F-004（P3, fixed）：schema 口径

- `docs/RUNBOOK.md:1694` 已改为“M06 起为 15”，与 `:255`、`scripts/ops/restore-drill.mjs:48,51`
  的 15/4、`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION=15` 一致。
- 其余 `起为 11` 均在 M04 历史章节（`:589,647,674,852`），不是 T15 的 M06 恢复章节，保留历史
  上下文即可。

## 证据复核

| EV      | 命令范围                                       | commit  | dirty | exit | manual | bytes / SHA-256      | 核对结果                                          |
| ------- | ---------------------------------------------- | ------- | ----- | ---- | ------ | -------------------- | ------------------------------------------------- |
| EV-1073 | 7 个 T15 定向测试类（52 tests）                | 859a2c4 | false | 0    | false  | 98,142 / `1963dcd6…` | 日志头/哈希一致；独立复跑 52 tests                |
| EV-1074 | ArchUnit `ModuleBoundaryTest`（12 tests）      | 859a2c4 | false | 0    | false  | 447 / `c8490539…`    | 日志头/哈希一致；独立复跑 12 tests                |
| EV-1075 | `WorkflowQueueDefaultContextTest`（1 test）    | 859a2c4 | false | 0    | false  | 6,806 / `fe5df2b1…`  | 日志头/哈希一致；schema=15、三池启动、Hikari WARN |
| EV-1076 | API 全量 spotless + worker T15 scoped spotless | 859a2c4 | false | 0    | false  | 400 / `ba422e1c…`    | 日志头/哈希一致；独立复跑两条命令均 exit 0        |

- 四条日志的 `# cwd` 均为主工作树；`output_bytes`/`output_sha256` 与 `shasum -a 256`、`stat`
  复核结果一致。
- EV-1073 日志中的 2 条 ERROR 是 `WorkflowRuntimeLimitsDisabledApiTest` 负向用例预期的
  `RICCON-WF-503` 业务日志，不是测试失败；独立复跑同样只出现这 2 条。

## 独立复跑

在 `git archive 859a2c4` 的干净导出
`/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t15-rev2-859a2c4`
（不含主工作树未提交文件）执行：

1. **52 个 T15 定向测试**：命令与 EV-1073 一致（7 个类，`-T1C -pl apps/api,apps/worker -am`），
   exit 0；surefire XML 合计 **52 tests，0 failures / 0 errors / 0 skipped**：
   `WorkflowRuntimeLimitsApiTest` 5、`WorkflowRuntimeLimitsDisabledApiTest` 4、
   `WorkflowRuntimeLimitsServiceTest` 5、`WorkflowRuntimeStartupValidatorTest` 25、
   `WorkflowOperationsMetricsTest` 3、`WorkflowRuntimeConfigGuardTest` 9、
   `WorkflowApprovalWaitJdbcIntegrationTest` 1。
2. **ArchUnit 12**：`./mvnw -q -pl platform/platform-architecture-tests -am test
-Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false`，exit 0；
   **12 tests，0/0/0**。
3. **默认 context 1**：`./mvnw -q -pl apps/worker -am test
-Dtest=WorkflowQueueDefaultContextTest -Dsurefire.failIfNoSpecifiedTests=false`，exit 0；
   **1 test，0/0/0**；日志含 `outbox.lane.scheduler.started lanes=[DEFAULT, HEAVY]`、
   `schema.version.checked expected=15 actual=15`、`RICCON-WORKFLOW-022 ... actual=3 required=14`。
4. **spotless**：见 F-002 表；API 全量 0、worker T15 scoped 0、全仓 `-fae` 仅 T06b 一个文件，
   阳性对照证明 `-DspotlessFiles` 生效。

复跑只用于复核 EV 的命令、计数与行为，不新增 EV；EV-1073..EV-1076 四条 clean EV 仍是任务的
正式证据。

### 命令调整说明

EV-1076 的 worker 部分使用 `-DspotlessFiles` 限定 T15 的 `workflow/config|metrics` 范围；原因：
全仓 spotless 唯一残余 `ModelNodeExecutorTest` 属 T06b/TASK-0225（owner `riccon/builder-b2`），
不在 T15 write_scope，T15 无法在不越界的情况下修复它。影响范围：T15 自身文件格式门禁成立；
全仓 `commands.lint` 在 TASK-0225 修复前仍为红，M06 模块 gate 必须重跑一次全量 spotless。
这不是 flake，也没有掩盖 T15 违规——阳性对照已证明过滤参数真实生效。

## 范围与未验证

- `git show --name-status 859a2c4` 共 7 个文件：6 个 worker T15 Java（`workflow/config/**`、
  `workflow/metrics/**` 及其测试）+ `docs/RUNBOOK.md`，全部在 T15 write_scope 内；未改
  `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、V15 迁移、J2/outbox、T06b engine/node。
- 未运行：`riccon gate check`（按本轮指令不运行）、M06 模块全量 build/lint/typecheck/test/
  coverage/E2E/a11y/perf/security/Trivy/downloads；T16a 的 E2E-M06-09/axe、T16b 的
  OPS-M06-01 回滚演练/5 MiB 容量与包大小预检仍归后续任务。
- T06b（TASK-0225）的串行引擎/节点执行质量、证据与 spotless 收口不在本评审范围；本评审只
  确认 T15 测试在包含 T06b 的提交上没有回归。

## 剩余风险

1. **T06b 格式债**：`ModelNodeExecutorTest` 未格式化，全仓 `spotless:check` / `commands.lint`
   仍为红；owner `riccon/builder-b2`（TASK-0225），due “M06 gate 前”。不阻塞 T15，但阻塞模块
   gate 的 lint 项。
2. **AC 残余**：AC-17 的界面可见/限制提示（E2E-M06-09）归 T16a；AC-23 的 OPS-M06-01 回滚
   演练与 5 MiB/包大小预检归 T16b。T15 的 acceptance 为 task-scope `pass (with residual)`，
   不是模块 AC-17/23 的完整 pass。
3. **指标实现范围**：RUNBOOK 告警表中的 `run_duration_seconds`、`node_duration_seconds`、
   `result_size_bytes`、`result_oversize_total` 等是 ADR-0024 §10.2 定义的全名；T15 只做名称
   与口径统一，不证明这些指标已在代码中注册。模块 gate 或相应后续任务应核对实际注册情况。
4. **API 池键绑定**：上一轮记录的“API 实际 Hikari 池不由 `RICCON_DB_POOL_SIZE` 控制”风险不变，
   属 T01/RUNBOOK 部署侧事项，与本次修复无关。
5. 本轮未发现新的 P0/P1/P2；F-001/F-002/F-003/F-004 均为 fixed。

## 下一步

1. TASK-0236 可保持 `review` 并交由 Lead 收口；本评审 verdict 为 pass，无 open finding。
2. T06b/TASK-0225 需修复 `ModelNodeExecutorTest` 的 spotless，并在模块 gate 前重跑全量
   spotless；T16a/T16b 按计划承接 AC-17 UI 与 AC-23 回滚演练。
3. M06 模块 gate 由 T17 在 canonical 栈执行合同全量命令，不把本轮的定向复跑当作模块级
   test/coverage/E2E 证据。
