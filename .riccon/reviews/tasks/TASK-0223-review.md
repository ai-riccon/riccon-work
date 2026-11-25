---
review_id: REV-TASK-0223-001
module_id: M06
task_id: TASK-0223
reviewer:
  agent: riccon/verifier
  session: ses_df01934feffe0yK6n0KK1tQwO5
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 70bcccb682d16dab7dd82f0a000e82af15a115c2
  - ebc3c13bd7bf4af3b757a39871d1dbc5f4d61868
  - 9fdfcff09976082ff8d9655ca6296a42727f8811
head_commit: 98c88e8b0c0eb6572f2454deb3db667ed22f79c2
code_commit: 70bcccb682d16dab7dd82f0a000e82af15a115c2
evidence_head: ebc3c13bd7bf4af3b757a39871d1dbc5f4d61868
baseline_commit: d130da6c49149c32163d5942420ad18c0ddf9f26
review_scope: >-
  M06 T05 的任务级独立验收：运行创建/幂等/版本范围准入、M04 配额按运行 1 单位、租户 10/单流程 5
  并发计数、kill switch、取消与失败节点重试状态机、同事务审计 fail-closed、范围/回归与证据复核。
  不重审 T04 发布、T06/T07 执行器与审批、T08 历史/详情、T10 UI、T16a E2E/axe/perf/security 或
  M06 模块 gate；不替代 Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
  lint: false
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
dod_note: >-
  acceptance/build/tests/evidence=true 指 T05 功能范围：代码提交 70bcccb 与证据 EV-1037 的
  commit/dirty/exit/hash 复核一致，独立复跑 5 个 T05 测试类 24 用例与 WorkflowContractM06Test 6 用例
  全部 0 failures/0 errors/0 skipped，ModuleBoundaryTest 12/12 通过。lint=false 是本次 fail 的唯一原因：
  在 70bcccb 干净导出上 `./mvnw -q spotless:check` exit 1，10 个 T05 变更文件未格式化，CI 的
  `spotless:check` 会红。coverage/e2e/security/performance/accessibility 属 T16a 与模块 gate，本次
  按任务要求未运行；J1/J2 回归未单独运行，但 diff 未触碰 chat/research/worker。
findings:
  - id: F-001
    severity: P2
    status: open
    owner: riccon/builder-b1
    due: "T05 复审前"
    title: "T05 变更文件未通过 spotless:check，CI lint 门禁失败"
    summary: >-
      在 `git archive 70bcccb` 的干净导出上运行合同 lint 的 Java 部分 `./mvnw -q spotless:check`，
      退出码 1。Spotless 报告 10 个 T05 文件存在 Palantir Java Format 差异：
      WorkflowRunTestSupport.java、WorkflowRunLifecycleApiTest.java、WorkflowRunAuditFailClosedTest.java、
      WorkflowRunAdmissionApiTest.java、WorkflowRunScopeApiTest.java、WorkflowRunFeatureDisabledApiTest.java、
      WorkflowRunRepository.java、WorkflowRunController.java、WorkflowRunJdbc.java、WorkflowRunService.java。
      `.github/workflows/ci.yml:200` 直接运行同一命令，因此 70bcccb 在 CI 会失败；这不是环境差异或
      基线问题（同一命令下非 T05 文件未被列出）。评审时在主工作树对当前 HEAD 运行同一命令也 exit 1，
      后续 d324e11/98c88e8 未触及 run 包，问题未被自动修复。修复方式：在提交内运行
      `./mvnw -q spotless:apply`，确认 `spotless:check` exit 0 后重新取证。
    evidence: >-
      复现：`git archive 70bcccb | tar -x -C <tmp> && cd <tmp> && ./mvnw -q spotless:check`，
      exit 1；日志列出上述 10 个文件与换行/缩进差异；`.github/workflows/ci.yml:200`。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T04b/TASK-0241 收口）"
    title: "F-005：detail.canRun 的 owner 例外在 T05 代码提交中仍未收敛"
    summary: >-
      TASK-0223 验收第 4 条要求关闭 REV-TASK-0220-001 F-005（所有者移出 DEPARTMENT 后
      detail.canRun 与 RUNNABLE 列表语义统一）。代码提交 70bcccb 未修改
      `WorkflowDefinitionService.java`（不在 T05 write_scope 内），其 `detail(...)` 仍为
      `&& (admin || owner || scopeVisible)`：所有者移出 DEPARTMENT 后详情 canRun 仍为 true，而
      RUNNABLE 列表与 T05 运行准入已按新范围返回 false/404。T05 的 `WorkflowRunScopeApiTest`
      只断言 RUNNABLE 与运行端点，未断言 detail.canRun；因此 F-004 已由 DEPARTMENT 用例关闭，
      F-005 的 UI 旗标部分在本任务提交中仍开放。
    accepted_reason: >-
      该残余不构成越权：T05 运行准入不读取 detail.canRun，服务端独立按所选版本的 run_scope +
      department_id 判定，偏离范围返回 404；误导仅限详情页“可运行”按钮提示（T10 消费该字段），
      且已被 Lead 明确转 T04b/TASK-0241。评审期间 HEAD 已出现 d324e11（T04b 提交，移除 owner 例外
      并新增 canRun 集成断言），但本评审目标为 T05 代码提交 70bcccb，未把未独立复验的后续提交
      算作 T05 证据；该修复需由 T04b 的任务评审确认，且 TASK-0241 当前 acceptance 未显式列入
      F-005，Lead 需在 M06 gate 前补入或另立任务。
    evidence: >-
      `git show 70bcccb:apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionService.java`
      第 534-537 行；`WorkflowRunScopeApiTest.java:149-170` 仅覆盖 RUNNABLE/运行端点；
      `d324e11`（T04b）已提交 canRun 收敛与 `WorkflowPublishApiTest` F-005 用例。
evidence:
  - EV-1037
reviewed_evidence:
  - EV-1037
notes: >-
  评审期间工作树与 HEAD 被并行任务持续推进（评审开始 HEAD 9fdfcff，结束前已到 98c88e8，并出现
  T04b 的 d324e11 与前端/任务卡未提交变更）。为保证独立性，本次代码/测试复核使用
  `git archive 70bcccb` 的干净导出，不依赖工作树；工作树中的 WorkflowDefinitionService /
  WorkflowPublishApiTest 未提交改动未被计入 T05 证据。EV-1037 录制于 ebc3c13（70bcccb 的后续
  前端提交），`git diff 70bcccb ebc3c13 -- apps/api platform contracts docs AGENTS.md` 为空，
  故 EV 对应的后端代码与 70bcccb 相同。按任务要求未运行 gate check、全量 E2E/perf/security/coverage。
created_at: "2026-11-25T07:43:21+08:00"
---

# REV-TASK-0223-001：M06 T05 运行准入/创建/取消/重试独立评审

## 结论

**不通过（fail）**。功能、安全边界、事务/审计与隔离验证均通过：幂等键、选中版本范围、
M04 按运行 1 单位配额、租户 10/单流程 5 并发上限、kill switch、取消/重试状态机与同事务
审计 fail-closed 都符合 ADR-0024 §4.8/§6.2/§7.4/§8.1；独立复跑 24 个 T05 用例、
6 个 `WorkflowContractM06Test` 用例与 12 个 `ModuleBoundaryTest` 用例全部通过。

唯一阻断是确定性的质量门禁失败：`spotless:check` 在 10 个 T05 变更文件上 exit 1，CI
（`.github/workflows/ci.yml:200`）会红。修复是机械的 `spotless:apply` + 重新取证，不涉及
行为变更；F-005 的 detail.canRun 残余按 T04b/TASK-0241 转移记录为 accepted（见 F-002）。

## 评审对象与范围

- 任务卡：`.riccon/tasks/TASK-0223.json`，baseline `d130da6`，代码提交 `70bcccb`，`end_commit`
  `70bcccb`，状态 `review`，证据 `EV-1037`。
- 代码提交 `70bcccb`：29 个文件、+3874 行，全部位于
  `apps/api/src/main/java/com/riccon/api/workflow/run/**` 与
  `apps/api/src/test/java/com/riccon/api/workflow/run/**`；未改 contracts/PRODUCT/AGENTS/V15/
  worker/web/chat/research。
- 证据提交 `ebc3c13`（T10 前端提交，包含 70bcccb）与 `9fdfcff`（记录 EV-1037、移交评审）。
  评审结束前 HEAD 已前进到 `98c88e8`（T04b 证据提交），并出现 `d324e11`（T04b 关闭 F-005）；
  本评审仍以 T05 的 `70bcccb` 为代码判断基准。
- 契约 `1.6.0-m06`：`contracts/openapi.yaml:2892-3103`（创建/取消/重试）、`:8882-8897`
  （`WorkflowRunCreateRequest`/`WorkflowRetryRequest`）。

## 幂等与准入

### 请求边界

- 创建/重试的 `Idempotency-Key` 在控制器层强制（`WorkflowRunController.java:86,135`）；
  解析器要求 8..128 位可打印 ASCII，缺失/过短/过长/非 ASCII 均 400 `RICCON-WF-400`
  （`WorkflowRunRequestParser.java:132-146`）。
- 创建体只允许 `versionId`、`inputs`（`:28,64-96`）：未知字段、非对象 inputs、17 键、非法键
  pattern、规范化后 >64 KiB 均 400；`versionId` 只接受 UUID/null。重试体只允许 `nodeId`，
  pattern 复用图节点 `^[A-Za-z0-9_-]{1,64}$`（`:31,105-121`）。

### 幂等顺序与语义

- 服务层顺序符合 ADR §4.8：`requireFeatureEnabled` → 权限 → `lockTenant` → 查创建唯一键 →
  读定义/版本 → 范围判定 → `admit` → 并发计数 → 插入 → 审计
  （`WorkflowRunService.java:140-204`）。
- 同主体同流程同键命中时直接返回既有运行（`:153-156`），不重复扣配额、不写
  `workflow.run.requested`；V15 唯一键 `(tenant_id, workflow_id, requester_user_id,
idempotency_key)` 与查询一致（`V15__workflow_studio.sql:124,133`；
  `WorkflowRunJdbc.java:99-114`）。租户锁在唯一键查询之前，避免并发重复插入。
- 幂等命中先于定义/范围读取，因此已创建运行的键重放不因流程后来停用/移出部门而变成新运行；
  这与“同键返回既有结果”一致，运行本身仍只对发起人/租户管理员可见。

### 版本与范围

- `resolveVersion` 优先使用用户显式 `versionId`，为空才取 `definition.publishedVersionId`；
  读取 `workflow_version` 的 `run_scope`、`department_id`、`graph_json`，并校验版本属于路径
  流程，否则统一 404（`WorkflowRunService.java:392-409`，`WorkflowRunJdbc.java:147-164`）。
- `requireRunnable` 与 RUNNABLE 列表同口径：租户管理员放行；`TENANT` 本租户成员；`ONLY_SELF`
  当前所有者；`DEPARTMENT` 当前启用主部门等于版本部门；范围外 404，停用/未发布 400
  （`:423-474`）。
- 测试覆盖 v1=ONLY_SELF/v2=TENANT 普通员工显式选 v1 404、默认 v2 可运行，以及反向组合，
  证明运行绑定所选版本而不是定义/最近版本（`WorkflowRunScopeApiTest.java:45-85`）。

## 配额与并发

- 配额在范围判定之后、插入之前调用 `QuotaAdmissionService.admit`，按一次运行 1 单位；重试
  不调用 `admit`，不重复扣配额（`WorkflowRunService.java:163-165,275-340`；DEC-0048=A）。
- 并发计数在租户行锁内执行：`countActiveTenant`/`countActiveWorkflow` 只统计
  `PENDING/RUNNING/WAITING_APPROVAL`；超限抛 429 `RICCON-WF-429`，detail 带 scope、limit、
  current 与等待建议（`:483-494`；`WorkflowRunJdbc.java:172-192`；
  `WorkflowRunRateLimitedException.java:19-29`）。配额超限或并发超限与运行插入、审计同一事务，
  异常整体回滚。
- 测试覆盖租户 10（PENDING+RUNNING 各 5）、单流程 5（PENDING/WAITING_APPROVAL 混合）、
  M04 配额 1 单位（第二次创建 429、运行数/配额保持 1）、重试恢复时 429 且运行保持 FAILED
  （`WorkflowRunAdmissionApiTest.java:89-162`；`WorkflowRunLifecycleApiTest.java:193-215`）。

## 取消与重试

### 取消

- 仅发起人或租户管理员；跨租户/平台 404，非发起人 403；已 CANCELLED 按状态幂等返回 202 且
  不重复写审计；SUCCEEDED/FAILED 终态 409（`WorkflowRunService.java:221-251`；
  `WorkflowRunLifecycleApiTest.java:28-104`）。
- `WAITING_APPROVAL` 先取消该运行全部 PENDING 审批，再条件更新运行；条件更新 0 行时重新读取
  区分“并发已取消”与冲突，冲突整体回滚（`WorkflowRunJdbc.java:236-274`）。
- 取消不检查 kill switch，符合“关闭后取消仍可用”；已产生节点结果不动，仅运行置 CANCELLED
  并清空 lease（`WorkflowRunJdbc.java:239-248`）。

### 重试

- 仅发起人或租户管理员；要求运行 FAILED 且指定节点最新 attempt 为 FAILED；非 FAILED 运行/
  非失败节点/未知节点 409，非法 nodeId/缺失键 400（`WorkflowRunService.java:275-340`；
  `WorkflowRunLifecycleApiTest.java:148-191`）。
- 条件更新 FAILED→PENDING，设置 `current_node_key=failedNodeKey`，清空 error/finished/lease/
  claim_attempts；插入 `attempt+1` 的 PENDING 节点行；只把失败节点可达下游的 SKIPPED 行恢复
  PENDING，条件未走分支的 SKIPPED 保持（`WorkflowRunJdbc.java:285-305,343-394`；
  `WorkflowRunService.java:521-540`）。测试断言 attempt 1 FAILED + attempt 2 PENDING、下游
  report 恢复、无关 unrelated 保持 SKIPPED。
- 重放按状态机判断：运行非终态、当前节点等于请求节点、最新 attempt>1 且 PENDING 时返回 202，
  不重复新增尝试/审计；与 ADR §7.4“重试由状态机与既有唯一键兜底”一致。未持久化重试键本身，
  这是设计选择（见剩余风险 2）。

## 审计

- `workflow.run.requested` details = workflowId/workflowVersionId/workflowRunId/versionNo/
  nodeCount；`cancelled` = workflowRunId/previousStatus；`retried` = workflowRunId/nodeId/attempt
  （`WorkflowRunAuditEvents.java:39-66,80-104,119-145`）。均在 `AuditDetailsPolicy` 写入白名单内
  （`AuditDetailsPolicy.java:302-312`），不含输入、prompt、工具参数、结果、凭据。
- 审计与业务同事务：`AuditService.append` 加入调用方事务，不捕获异常
  （`AuditService.java:35-38`）。`WorkflowRunAuditFailClosedTest.java:38-89` 分别让
  requested/cancelled/retried 审计抛异常，断言 500 `RICCON-COMMON-500`、运行/节点/配额回滚。
- 幂等命中与取消重放不重复写审计；测试断言 requested 1 条、cancelled 1 条、retried 1 条。

## 范围、回归与架构

- `git diff --name-only d130da6..70bcccb` 仅 `apps/api/src/**/workflow/run/**`；contracts、
  PRODUCT、AGENTS、V15、worker、web、chat/research 均无改动。J1/J2 行为面无改动。
- `ModuleBoundaryTest` 在干净导出上 12/12 通过，T04b 探索性运行看到的 T05 WIP
  Service→Persistence 违规已不存在：`WorkflowRunService` 只依赖 `WorkflowRunRepository`，
  `WorkflowRunJdbc` 仅在 repository/persistence 层被访问。
- `WorkflowContractM06Test` 6/6 通过（契约版本、operationId/权限、Idempotency-Key 声明、
  schema 枚举、deferred 字段）。
- **质量门禁**：`./mvnw -q spotless:check` exit 1（F-001），CI 会红；这是本次 fail 的唯一原因。

## 证据复核

### EV-1037

- 命令：`./mvnw -q -pl apps/api -am test
-Dtest=WorkflowRunAdmissionApiTest,WorkflowRunScopeApiTest,WorkflowRunLifecycleApiTest,
WorkflowRunFeatureDisabledApiTest,WorkflowRunAuditFailClosedTest
-Dsurefire.failIfNoSpecifiedTests=false`
- `commit=ebc3c13bd7bf4af3b757a39871d1dbc5f4d61868`、`dirty=false`、`exit_code=0`、
  `output_bytes=177592`；`sha256` 复核为
  `06e2006730538466dc19e827b134f1c627fa9ee1cf8ed91f5aadc0bf1b0332ce`，与记录一致。
- 24 个用例的 surefire 报告（07:28）与记录一致：Admission 8、AuditFailClosed 3、
  FeatureDisabled 3、Lifecycle 6、Scope 4，全部 `Failures: 0, Errors: 0, Skipped: 0`。
- `ebc3c13` 相对 `70bcccb` 只改 `apps/web/**`；`git diff 70bcccb ebc3c13 -- apps/api platform
contracts docs AGENTS.md` 为空，证据的后端代码等于 T05 代码提交。

### 独立复跑（干净导出 `70bcccb`）

| 命令                                                                                                                            | 结果                                              |
| ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| 上述 5 个 T05 类 + `WorkflowContractM06Test`                                                                                    | exit 0；30 tests，0 failures，0 errors，0 skipped |
| `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` | exit 0；12 tests，0 failures，0 errors，0 skipped |
| `./mvnw -q spotless:check`                                                                                                      | **exit 1**；10 个 T05 文件格式违规（F-001）       |

复跑使用 `git archive 70bcccb` 的独立目录，避免评审期间工作树/HEAD 并行变化影响结果；
未运行全量 build/coverage/E2E/perf/security/a11y，未运行 gate check。

## AC 对账（T05 段）

| AC        | T05 段判定                        | 证据                                                                                                          |
| --------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| M06-AC-03 | pass（运行侧）                    | 默认最近发布版本、显式旧版本、停用/未发布拒绝、运行详情版本号；选择器/发布 UI 属 T04/T10                      |
| M06-AC-04 | 未在 T05 验证                     | T05 仅创建 PENDING/START 初始状态；节点时间线/刷新一致性属 T06/T08                                            |
| M06-AC-15 | pass（T05 段，含 F-002 residual） | 运行范围、取消/重试仅发起人/管理员、403/404 矩阵；详情/列表/审批/停用属 T07/T08/T10                           |
| M06-AC-17 | pass（T05 段）                    | 租户 10/单流程 5 含 WAITING_APPROVAL、429 限制值/当前值、输入 64 KiB/16 键；节点/步数/超时/结果大小属 T06/T15 |
| M06-AC-23 | pass（T05 段）                    | 关闭时创建/重试 503、取消仍可用；启动审计/J1J2/回滚演练属 T01/T16a/OPS                                        |

## Findings

- **F-001（P2，open）** T05 变更文件 `spotless:check` 失败，CI lint 门禁红；修复：
  `./mvnw -q spotless:apply`，确认 check exit 0 后重新取证。owner=builder-b1，due=T05 复审前。
- **F-002（P2，accepted）** F-005 detail.canRun owner 例外在 70bcccb 仍存在；T05 运行准入
  不依赖该旗标，F-004 已闭环，F-005 由 T04b/TASK-0241 承接（评审期间 d324e11 已提交修复）。
  owner=builder-b2，due=M06 gate 前；accepted_reason 见 front matter。

## 未验证与剩余风险

1. **幂等重放响应聚合**：对已进展/已进入 WAITING_APPROVAL 的运行重放创建键时，
   `WorkflowRunResponse.from` 仍返回 `approvalStatus=null`、counts=0、`durationMs=null`
   （`WorkflowRunResponse.java:80-103`）。创建端点契约不要求 counts，T08 详情聚合会覆盖；
   若 UI 消费创建重放响应，应在 T08 统一。
2. **重试键级重放**：同一 `Idempotency-Key` 在重试节点再次失败后不记忆，可能再产生 attempt；
   ADR §7.4 允许“状态机兜底”，但 T08/T16a 应测试“同键在 PENDING/RUNNING/再次 FAILED 三种
   时点”的语义。
3. **停用流程后的重试**：`retry` 只检查 kill switch，不重新检查 `workflow_definition.status`；
   FAILED 运行在流程停用后仍可重试。按 AC-15/§4.8“停用阻止新运行、在途继续”的边界，建议
   T08/T16a 明确“重试是否算新执行”；当前未作为 T05 finding。
4. **RUNNING 取消/部分结果**：取消 PENDING/WAITING_APPROVAL 有集成测试；RUNNING 下取消与
   Worker 节点边界/已产生结果保留由 T06/T16a E2E 验证。
5. **平台管理员取消/重试**：平台 404 由 T01 过滤器统一覆盖，T05 只对创建做了端点断言；
   T16a 补 cancel/retry 的平台/跨租户矩阵。
6. **到期运行**：`cancel`/`retry` 的 `findById` 不按 `retention_expires_at` 过滤；历史/详情
   的 180 天不可见属 T08，若 T08 不覆盖写操作，应补一条到期运行 404 用例。
7. **未运行**：合同全量 build/test/coverage、E2E-M06、axe、Trivy、k6、迁移回滚演练（任务要求
   外，属 T16a/模块 gate）。

## 是否可 done

否（当前 verdict=fail）。请 builder 先执行 `spotless:apply` 并重新录制受影响证据，使
`spotless:check` exit 0；F-002 已 accepted 且有 owner/due，不单独阻塞 T05。功能/安全/证据面
在修复格式后即可复审通过。本评审不修改源码/测试/迁移/契约/PRODUCT/AGENTS/.riccon 状态，
未运行 gate check。
