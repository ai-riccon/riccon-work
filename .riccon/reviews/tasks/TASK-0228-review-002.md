---
review_id: REV-TASK-0228-002
module_id: M06
task_id: TASK-0228
previous_review: REV-TASK-0228-001
reviewer:
  agent: riccon/verifier
  session: ses_ded85bc67ffe1EocTlpm6whABL
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - cc014f79fd3017eb9578081cb064345c85e93de6
head_commit: e00081bb7cb95b21f7dbe096e7cd603973495c07
baseline_commit: 0511afd31791f227a402ff262bf3c58cafb01909
task_card_baseline_commit: "b514fe67"
task_card_end_commit: "cc014f79"
dod:
  acceptance: true
  build: false
  tests: true
  lint: true
  review: true
  evidence: true
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - performance
  - accessibility
  - migration
  - docs
  - ui
dod_note: >-
  F-001 本身已修复（acceptance/tests/evidence true）：`GET /api/v1/workflows/{workflowId}/runs`
  已实现，F-001 定向用例 11/11 通过，EV-1092 clean 103/103；独立复跑的同组 F-001 用例 11/11
  仍通过。build=false 是独立架构测试 `ModuleBoundaryTest.mvcLayeringIsOneWay` 失败：12 tests
  中 1 failure、5 个 service→persistence 违例（`WorkflowApprovalService` 直接使用
  `WorkflowApprovalCitationRow`）。该 P1 由 T08 的 F-003 批量引用修复引入，不是 cc014f79 的
  F-001 delta 新增，但它仍使 T08 无法通过 build/ArchUnit gate，必须修复后才能 pass。
  独立 T08 全量复跑 102/103，唯一失败是 REV-001 已接受 F-004 竞态测试（cancel 409 vs 期望
  202），按“P3 残余不重开”记录、不新增 finding；EV-1090 为 dirty=true 旧快照，当前任务卡已由
  e00081bb 改为 EV-1092（dirty=false，103 tests），本评审以 EV-1092 为权威证据。
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: riccon/builder-b1
    title: "契约操作 GET /api/v1/workflows/{workflowId}/runs 未实现"
    summary: >-
      cc014f79 在 `WorkflowRunHistoryController` 新增
      `GET /api/v1/workflows/{workflowId}/runs`（`@RequirePermission(WORKFLOW_READ)`），
      `WorkflowHistoryService.listForWorkflow` 先复用 `WorkflowDefinitionService.get` 的流程可见性
      （owner/admin/发布范围，否则 404），再以 `WorkflowHistoryFilter(workflowId,...)` 委托既有
      `list`。分页、倒序、保留期、审批聚合与管理员 LIST 审计均复用全局 `/workflow-runs`，
      未新增 history SQL。新增两个集成用例覆盖路径/全局同结果、分页、到期不可见、管理员 LIST
      审计、owner/其他编辑者/member 可见性、ONLY_SELF/不存在/跨租户/平台 404、非法 UUID/分页
      400；`WorkflowRunHistoryApiTest` 11/11、EV-1092 103/103 通过。F-001 已关闭。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/history/controller/WorkflowRunHistoryController.java:115-129；
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryService.java:183-200；
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryRequestParser.java:58-72；
      apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryApiTest.java:534-629；
      EV-1092。
  - id: F-006
    severity: P1
    status: open
    owner: riccon/builder-b1
    due: "M06 gate 前（T17 架构回归）"
    title: "T08 审批批量引用修复让 Service 直接使用 persistence 类型，ArchUnit mvcLayeringIsOneWay 失败"
    summary: >-
      独立运行 `./mvnw -q -pl platform/platform-architecture-tests -am test
      -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` 得到
      `Tests run: 12, Failures: 1, Errors: 0, Skipped: 0`；`mvcLayeringIsOneWay` 报 5 个违例，
      全部是 `WorkflowApprovalService` 直接调用/声明
      `WorkflowApprovalCitationRow`（`approval/persistence`）：
      `citationsByRun` 调用 `workflowRunId()`（WorkflowApprovalService.java:640）、
      `documentReferenceCount` 调用 `finishedAt()/citationsJson()`（:658,661），以及
      `Map<UUID,List<WorkflowApprovalCitationRow>>` / `List<WorkflowApprovalCitationRow>` 泛型
      依赖。规则要求 Persistence 层只能被 Repository 访问；该行类型是 T08 F-003 批量引用修复
      新增（EV-1088/1090），不是 cc014f79 的 F-001 delta 新增，但 T08 的 build/ArchUnit gate
      目前失败。期望：把批量引用结果改为 `approval/domain` 快照（如
      `WorkflowApprovalCitationSnapshot`），或让 Repository 返回领域类型，Service 不接触
      persistence record；修复后重跑架构测试与 T08 用例。P1 不能 accepted，必须在 gate 前 fixed。
    evidence: >-
      platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java:179-201；
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalService.java:616-663；
      apps/api/src/main/java/com/riccon/api/workflow/approval/persistence/WorkflowApprovalCitationRow.java:1-25；
      platform/platform-architecture-tests/target/surefire-reports/com.riccon.architecture.ModuleBoundaryTest.txt；
      独立日志 /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify-002/arch3.log。
evidence:
  - EV-1092
reviewed_evidence:
  - EV-1088
  - EV-1092
notes: >-
  任务提示的 HEAD `6c4af42c`、EV-1090 clean/dirty=false、101 tests 与当前仓库不一致：评审时
  HEAD 为 `e00081bb`（其后仅 T13b/EV-1092 记录），任务卡证据为 EV-1087/EV-1088/EV-1092；
  `git diff cc014f79..HEAD -- apps platform contracts docs` 为空。EV-1090 实际
  `dirty: true`、103 tests（不是 clean/101）；它已被 EV-1092 取代：EV-1092
  commit=cc014f79、dirty=false、cwd 为隔离的 `/private/var/.../t08-evidence`、103 tests、
  日志 SHA-256 `65b0e072c202cbe4f2ac7cfdf334ad2698ff07693076a0a1eb717b98886adbce` 与 EV JSON
  一致，任务卡在 `e00081bb` 中已改为 EV-1092。独立 T08 全量复跑
  `TEST_EXIT=1`，103 tests 中唯一失败是 REV-001 F-004 的已知竞态 flake
  （`concurrentCancelAndDecide_consistentOutcome`：cancel 实际 409
  `RICCON-WF-409 run status changed during cancel`，测试期望 202）；F-001 的
  `WorkflowRunHistoryApiTest` 11/11 通过；`spotless:check` exit 0。按用户指示 P3 残余不重开，
  未把 F-004 或 REV-001 F-002/F-003/F-005 重新列为 finding。
created_at: "2026-11-25T20:32:22+08:00"
---

# REV-TASK-0228-002：M06 T08 F-001 delta 复审

## 结论

**Fail（F-001 已修复，但 T08 未通过架构 build gate）**。

- **F-001 fixed**：`GET /api/v1/workflows/{workflowId}/runs` 已实现为薄委托，复用全局
  `/workflow-runs?workflowId=` 的可见性、分页、倒序、保留、审批聚合与管理员审计；新增的
  F-001 定向用例 11/11 通过，EV-1092 clean 103/103。F-001 本身不再有 open 问题。
- **新增 P1 F-006 open**：独立架构测试 `ModuleBoundaryTest.mvcLayeringIsOneWay` 失败
  （12 tests / 1 failure / 5 violations）。`WorkflowApprovalService` 直接使用
  `WorkflowApprovalCitationRow`（`approval/persistence`），违反 service→repository→persistence
  分层。该类型来自 T08 的 F-003 批量引用修复（EV-1088/1090），不是 cc014f79 的 F-001 delta
  新增，但 T08 任务仍不能通过 build/ArchUnit gate；P1 不能 accepted，必须 fixed。
- **证据修正**：任务提示称 EV-1090 clean、dirty=false、101 tests；实际 EV-1090 为
  **dirty=true、103 tests**。它已被 EV-1092（dirty=false、commit cc014f79、103 tests、隔离
  worktree）取代，任务卡在 `e00081bb` 中已改为 EV-1092；本评审以 EV-1092 为权威。
- **独立 T08 复跑**：103 tests 中 1 failure，失败为 REV-001 已接受 F-004 竞态测试
  （cancel 409 vs 期望 202），按“P3 残余不重开”不新增 finding；F-001 定向用例 11/11 通过；
  spotless exit 0。

因此：**F-001 关闭，但 TASK-0228 不能 pass；T08 需先修复 F-006（P1）并重跑架构测试**。
本评审不修改源码/测试/契约/PRODUCT/AGENTS/`.riccon` 状态，不运行 gate check，不替代模块
review、Product 预验收或用户最终验收。

## 范围与提交

| 项          | 值                                                                                                                                             |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 上轮 review | `REV-TASK-0228-001`（pass；唯一 P2 F-001，F-002..F-005 为 accepted P3/residual）                                                               |
| delta 基线  | `0511afd31791f227a402ff262bf3c58cafb01909`（REV-001 审查的 T08 feature commit）                                                                |
| F-001 修复  | `cc014f79fd3017eb9578081cb064345c85e93de6`                                                                                                     |
| 当前 HEAD   | `e00081bb7cb95b21f7dbe096e7cd603973495c07`；`git diff cc014f79..HEAD -- apps platform contracts docs` 为空（后续仅 T13b/EV-1092 记录/任务卡）  |
| 任务卡      | `.riccon/tasks/TASK-0228.json`：status=review；evidence=[EV-1087,EV-1088,EV-1092]；end_commit=cc014f79                                         |
| 范围        | cc014f79 只改 `history/controller`、`history/service`（含 parser）、`history` 测试 5 个文件；未改 contracts/PRODUCT/AGENTS/V15/T05/T07/T09/T13 |
| 设计依据    | ADR-0024 §6.2/§6.3/§7.2、PRODUCT AC-12/13/14/15/16、`contracts/openapi.yaml` 的 `listWorkflowRunsForWorkflow`                                  |

## F-001：端点实现

- **路由与权限**：`WorkflowRunHistoryController.listForWorkflow`
  （`WorkflowRunHistoryController.java:115-129`）映射
  `GET /api/v1/workflows/{workflowId}/runs`，`@RequirePermission(WORKFLOW_READ)`；路径参数经
  `WorkflowHistoryRequestParser.parseWorkflowId`（`:58-72`）转 UUID，非法返回 400
  `RICCON-WF-400`；`page/size` 经 `PageQuery` 校验（page≥1、size 1..100）。
- **可见性**：`WorkflowHistoryService.listForWorkflow`
  （`WorkflowHistoryService.java:183-200`）先调用
  `WorkflowDefinitionService.get(definitionActor(...), workflowId)`：
  - 私有草稿、他人 `ONLY_SELF`、跨部门/跨租户、不存在 → 404 `RICCON-TENANT-404`；
  - 流程所有者、租户管理员、发布范围可见用户 → 继续；
  - 平台租户仍由 `WorkflowPlatformTenantFilter` 在权限拦截器前 404。
- **薄委托**：随后调用既有 `list(actor, new WorkflowHistoryFilter(workflowId,...), page, ...)`。
  `WorkflowHistoryJdbc` 未改；分页/倒序（`COALESCE(started_at,created_at) DESC`）、
  `retention_expires_at > now`、审批 counts 批量聚合、管理员 `workflow.run.admin_read
scope=LIST` 审计全部走原实现，没有新增 history 查询。
- **语义**：
  - 流程所有者/租户管理员看该流程全部可见运行；
  - 其他能看到该流程的用户只返回自己发起的运行（可见但无自己运行时 200 空页）；
  - 不可见流程统一 404，不泄露名称/数量；
  - 到期运行与非可见工作流 404；管理员路径仍写 LIST 审计。
- **测试**：`WorkflowRunHistoryApiTest` 新增两个用例：
  - `listRunsForWorkflow_delegatesToGlobalList`：路径与全局端点 ids/total/totalPages/item
    完全一致；page=2&size=1 一致；设置 retention 后路径端点与全局同样隐藏；管理员路径调用
    只写 1 条 `workflow.run.admin_read scope=LIST`。
  - `listRunsForWorkflow_visibilityAndNotFound`：owner/其他编辑者/member 视图；ONLY_SELF
    私有流程对 member/owner 404、对 owner 本人与管理员 200；不存在 UUID、跨租户 admin、
    platform 均 404；非法 UUID/`page=0`/`size=101` 400。
- **结论**：F-001 的实现符合“委托 history list 的 workflowId 过滤、复用可见性/分页/倒序/
  保留/404/管理员审计、不新造查询”的要求，**fixed**。

## 测试与独立复验

- **EV-1092（权威）**：命令
  `./mvnw -pl apps/api test -Dtest='WorkflowRunHistory*Test,WorkflowApproval*Test,WorkflowRun*Test,WorkflowContractM06Test' -Dsurefire.failIfNoSpecifiedTests=false`，
  commit `cc014f79`、`dirty=false`、103 tests、0 failure/error/skip、BUILD SUCCESS；日志
  SHA-256 `65b0e072c202cbe4f2ac7cfdf334ad2698ff07693076a0a1eb717b98886adbce` 与 EV JSON 一致；
  cwd 为隔离 worktree，证明工作树干净。
- **EV-1090（旧快照）**：同 commit、同命令，但 EV JSON 实际 `dirty=true`、103 tests；与任务
  提示的“clean/dirty=false/101 tests”不符。它已被 EV-1092 取代，任务卡 e00081bb 只保留
  EV-1092；本评审不以 EV-1090 作为通过依据。
- **独立复跑**：同命令（当前 HEAD e00081bb，apps/api 与 cc014f79 相同）退出码 1：
  `Tests run: 103, Failures: 1, Errors: 0, Skipped: 0`。唯一失败为
  `WorkflowRunHistoryConcurrencyApiTest.concurrentCancelAndDecide_consistentOutcome`：
  cancel 实际 409 `RICCON-WF-409 run status changed during cancel`，测试断言 202。该失败与
  REV-001 F-004（取消/审批竞态测试对 cancel 恒 202 的假设）一致，属已接受 P3；按“P3 残余不
  重开”不新增 finding。F-001 定向的 `WorkflowRunHistoryApiTest` 11/11 通过。
- **Spotless**：`./mvnw -q spotless:check` exit 0。
- **架构测试（新增证据，导致 fail）**：
  `./mvnw -q -pl platform/platform-architecture-tests -am test
-Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` 退出码 1；
  `Tests run: 12, Failures: 1, Errors: 0, Skipped: 0`。`mvcLayeringIsOneWay` 报 5 个
  service→persistence 违例，全部集中在 `WorkflowApprovalService` 使用
  `WorkflowApprovalCitationRow`（详见 F-006）。

## 证据

- **EV-1092**：`commit=cc014f79`、`dirty=false`、`exit_code=0`、`manual=false`、103 tests。
- **EV-1090**：`commit=cc014f79`、`dirty=true`（任务提示写 clean，实际不是）、103 tests；
  已被 EV-1092 取代，任务卡 e00081bb 不再引用。
- **EV-1088**：REV-001 的 T08 feature 证据，保留用于 F-003/F-004 历史。
- 独立日志（不写入 `.riccon/evidence`）：
  `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify-002/`：
  `tests.log`（T08 103 tests，1 个已知 F-004 失败）、`spotless.log`、`arch3.log` +
  `platform/platform-architecture-tests/target/surefire-reports/`（F-006 违例）。

## 未验证

- 全量模块 gate build/coverage/E2E/a11y/perf/security：T17；本 delta 只补了架构测试与 T08
  定向测试，未跑 E2E/perf。
- UI 旅程（T12a/T12b/T13/T16a）与 AC-12 空态、AC-13 结果再次打开、AC-14 保留值展示、
  AC-16 审计查询界面。
- F-004 已接受 P3 的再次复现只在一次独立全量运行中观察到，未以 retry 掩盖；T16a 应按
  REV-001 F-004 硬化测试（接受 cancel 409 或控制事务顺序）。
- U-05（审批理由 PII）仍是 T09 F-002 accepted residual，本任务不声称已接受。

## Findings

- **F-001（P2，fixed）**：`GET /api/v1/workflows/{workflowId}/runs` 已实现并委托全局 list；
  F-001 用例 11/11、EV-1092 103/103。**fixed**。
- **F-006（P1，open）**：`WorkflowApprovalService` 直接使用
  `WorkflowApprovalCitationRow`（persistence），`ModuleBoundaryTest.mvcLayeringIsOneWay`
  5 个违例；T08 build/ArchUnit gate 失败。必须由 builder 在 M06 gate 前把批量引用结果改为
  领域快照/Repository 返回领域类型，重跑架构测试与 T08 用例。**open，不能 accepted**。
- REV-001 的 F-002/F-003/F-004/F-005 按“P3 残余不重开”保留原 accepted 状态；本次未新增
  这些 P3 的 finding。独立复跑命中 F-004 一次，仅作为已接受 residual 记录。

## 剩余风险

- **F-006 架构违例**是当前唯一 P1；修复前 T08 不能 done/模块 gate 不能通过。
- **F-004 竞态 flake**：测试对 cancel 恒 202 的假设在合法交错下会失败；生产语义（409/202）
  可接受，T16a 应硬化，不阻塞 F-001。
- **EV-1090 旧快照**仍留在 `.riccon/evidence`，但任务卡已改用 EV-1092；后续 gate 不应引用
  EV-1090（dirty=true）。
- 全量 E2E/perf/coverage、UI 旅程与 U-05 仍归 T12a/T12b/T13/T16a/T17。

## 回执

```text
任务：TASK-0228（M06 T08）F-001 delta 复审
review_id：REV-TASK-0228-002
previous_review：REV-TASK-0228-001
verdict：fail
findings：P0=0；P1=1 open（F-006 ArchUnit service→persistence）；P2=0；P3=0 new；
  F-001 fixed；REV-001 F-002..F-005 按 P3 残余不重开
关键结论：
- F-001 已修复：GET /api/v1/workflows/{workflowId}/runs 薄委托全局 history list 的
  workflowId 过滤，复用可见性/分页/倒序/保留/404/管理员 LIST 审计；F-001 定向用例 11/11，
  EV-1092 clean 103/103。
- 但 T08 仍不能 pass：独立 ModuleBoundaryTest.mvcLayeringIsOneWay 12 tests / 1 failure /
  5 violations，WorkflowApprovalService 直接使用 WorkflowApprovalCitationRow（persistence）。
  该 P1 来自 T08 F-003 批量修复，不是 F-001 delta 新增，但必须在 M06 gate 前修复。
- 证据修正：EV-1090 实际 dirty=true（不是 clean）、103 tests；已被 EV-1092
  （dirty=false、cc014f79、103 tests）取代；任务卡 e00081bb 已改用 EV-1092。
- 独立 T08 全量复跑 102/103，唯一失败为 REV-001 已接受 F-004 竞态 flake，按指示不重开。
证据：EV-1092（cc014f79，dirty=false，hash 65b0...，103 tests）；EV-1090 dirty 旧快照；
  arch3.log / surefire ModuleBoundaryTest.txt；评审文件
  .riccon/reviews/tasks/TASK-0228-review-002.md
未测：全量 E2E/perf/coverage/UI、U-05。
```
