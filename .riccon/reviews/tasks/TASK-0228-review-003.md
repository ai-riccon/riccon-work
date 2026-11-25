---
review_id: REV-TASK-0228-003
module_id: M06
task_id: TASK-0228
previous_review: REV-TASK-0228-002
reviewer:
  agent: riccon/verifier
  session: ses_ded85bc67ffe1EocTlpm6whABL
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - cebcc418b95123b953d19ae21bc5ac9e8ec603ca
head_commit: 8237a69adb995999988ab981fdd4c8cdb6bddd5b
baseline_commit: e00081bb7cb95b21f7dbe096e7cd603973495c07
task_card_baseline_commit: "b514fe67"
task_card_end_commit: "cebcc418"
dod:
  acceptance: true
  build: true
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
  pass (with P3 evidence residual)。F-006 已修复：新增 domain
  `WorkflowApprovalCitationSnapshot`，`WorkflowApprovalRepository.findSucceededCitationsByRuns`
  返回按运行分组的领域投影，`WorkflowApprovalService` 不再 import/使用
  `approval/persistence/WorkflowApprovalCitationRow`；底层 `WorkflowApprovalJdbc` 批量查询与
  按审批 createdAt 的去重计数行为未变，仍为一次批量查询（无 N+1）。独立复跑
  `ModuleBoundaryTest` 12/12、T08 受影响 103 tests 0 failure/0 skip、`spotless:check` exit 0；
  EV-1094 clean（dirty=false、主工作树、103 tests、hash 一致），EV-1088/1093/1094 均 clean，
  EV-1090 dirty/EV-1092 temp 未引用。F-001 路径端点仍 11/11 通过。保留 1 个 accepted P3：
  EV-1094 的 command/log 只覆盖 apps/api test，note 声称的 ModuleBoundaryTest 12/12 与 spotless
  不在该 EV 的输出中；独立复跑已证明，但 builder 应在 M06 gate 前补一条 clean ArchUnit+spotless
  EV 或合并命令，使任务证据自证 F-006 验收。无新 P1/P2。
findings:
  - id: F-006
    severity: P1
    status: fixed
    owner: riccon/builder-b1
    title: "T08 审批批量引用修复让 Service 直接使用 persistence 类型，ArchUnit mvcLayeringIsOneWay 失败"
    summary: >-
      cebcc418 新增 `approval/domain/WorkflowApprovalCitationSnapshot`，由
      `WorkflowApprovalRepository.findSucceededCitationsByRuns` 把
      `WorkflowApprovalCitationRow`（persistence）映射为领域快照并按运行分组返回；
      `WorkflowApprovalService` 删除 persistence import，`citationsByRun` 直接使用 repository
      返回的领域映射，`documentReferenceCount(List<WorkflowApprovalCitationSnapshot>,...)
      仅读 domain 字段。`WorkflowApprovalJdbc.findSucceededCitationsByRuns` 的 SQL 与
      `status='SUCCEEDED'`/`citations_json IS NOT NULL`/`finished_at IS NOT NULL` 条件未变，
      仍是一次批量查询，服务层按审批 createdAt 过滤 finishedAt 的行为与去重计数不变。
      独立复跑 `ModuleBoundaryTest` 12/12（0 failure/0 skip），此前 5 个
      service→persistence 违例全部消失；T08 103 tests 与 F-001 路径用例 11/11 通过。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/domain/WorkflowApprovalCitationSnapshot.java:1-29；
      apps/api/src/main/java/com/riccon/api/workflow/approval/repository/WorkflowApprovalRepository.java:154-177；
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalService.java:621-656；
      apps/api/src/main/java/com/riccon/api/workflow/approval/persistence/WorkflowApprovalJdbc.java:475-508；
      platform/platform-architecture-tests/target/surefire-reports/TEST-com.riccon.architecture.ModuleBoundaryTest.xml（tests=12, failures=0）；
      EV-1094。
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "M06 gate 前（T17 架构回归）"
    title: "EV-1094 的 command/log 未包含 ModuleBoundaryTest 与 spotless，note 的 ArchUnit 12/12 不能由该 EV 自证"
    summary: >-
      EV-1094 记录的 command 是
      `./mvnw -pl apps/api test -Dtest='WorkflowRunHistory*Test,WorkflowApproval*Test,WorkflowRun*Test,WorkflowContractM06Test' ...`，
      日志只含 103 tests；EV note 写“ModuleBoundaryTest 12/12 + 103 affected tests”，但 EV 输出
      中没有 `ModuleBoundaryTest` 或 `spotless` 的 Maven 输出。独立评审另行运行
      `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest
      -Dsurefire.failIfNoSpecifiedTests=false` 得到 12 tests / 0 failures，并运行
      `./mvnw -q spotless:check` exit 0，代码行为已独立确认；缺的是任务证据文件的自证命令。
      期望：builder 在主工作树补一条 clean EV（ArchUnit+spotless 合并命令或两条独立 EV），
      避免后续 gate 只看到 EV-1094 的 apps/api 日志而无法直接核对 F-006 验收。
    accepted_reason: >-
      F-006 的分层修复与批量查询行为已通过独立 ArchUnit 12/12、103 tests、spotless 与代码
      diff 验证；EV-1094 本身 clean、commit/dirty/cwd/hash 与 103 tests 均正确，缺的只是
      架构/格式命令的 EV 覆盖，属证据记录完整性 residual，不是代码、安全或行为缺陷。
      T17 模块 gate 会再次运行完整 build/架构测试，届时可自然补齐；不阻塞本次 F-006 代码验收。
    evidence: >-
      .riccon/evidence/TASK-0228/EV-1094.json（command/note）；
      .riccon/evidence/TASK-0228/EV-1094-*.log（无 ModuleBoundaryTest/spotless）；
      独立日志 /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify-003/{tests.log,arch.log}；
      platform/platform-architecture-tests/target/surefire-reports/TEST-com.riccon.architecture.ModuleBoundaryTest.xml。
evidence:
  - EV-1094
reviewed_evidence:
  - EV-1088
  - EV-1093
  - EV-1094
notes: >-
  当前 HEAD `8237a69a`（仅 EV-1094/状态 chore）；任务卡 end_commit `cebcc418`，evidence
  [EV-1088, EV-1093, EV-1094]；`git diff cebcc418..HEAD -- apps platform contracts docs` 为空。
  EV-1094：commit cebcc418、dirty=false、cwd 主工作树 `/Users/ricky/Documents/projects/riccon-work`、
  103 tests、exit 0，日志 SHA-256 `0fe6a5259c616d52b9cbc635d380285e63fdd8c7d658553bc9b129a79b66cd8d`
  与 EV JSON 一致。EV-1088/EV-1093/EV-1094 均 dirty=false；EV-1090 dirty=true 与 EV-1092 temp
  worktree 均未被任务卡引用。独立复跑：
  T08 命令 103 tests 0 failure/0 skip exit 0；`spotless:check` exit 0；`ModuleBoundaryTest`
  12 tests 0 failure/0 skip exit 0；本次未命中 REV-001 F-004 的并发 flake（其仍为 accepted P3，
  未重开）。F-001 的 `WorkflowRunHistoryApiTest` 11/11 通过，路径端点无回归。未运行 gate check。
created_at: "2026-11-25T20:48:28+08:00"
---

# REV-TASK-0228-003：M06 T08 F-006 最终 delta 复审

## 结论

**Pass（带 1 个 accepted P3 证据 residual）**。

- **F-006 fixed**：`WorkflowApprovalService` 不再 import/使用
  `approval/persistence/WorkflowApprovalCitationRow`；仓储新增 domain
  `WorkflowApprovalCitationSnapshot`，`findSucceededCitationsByRuns` 返回按运行分组的领域投影；
  底层 JDBC 批量查询、按审批 `createdAt` 过滤 `finishedAt` 与去重计数语义未变，仍为一次批量
  查询（无 N+1）。
- **ArchUnit 通过**：独立复跑 `ModuleBoundaryTest` 12/12（0 failure/0 skip），此前
  service→persistence 的 5 个违例全部消失。
- **测试/格式**：T08 受影响 103 tests 0 failure/0 skip；`spotless:check` exit 0；F-001 路径
  端点 `WorkflowRunHistoryApiTest` 11/11 仍通过。
- **证据**：EV-1094 clean（commit `cebcc418`、dirty=false、主工作树、103 tests、hash 一致）；
  EV-1088/EV-1093/EV-1094 均 clean；EV-1090 dirty/EV-1092 temp 未引用。
- **保留 P3（F-007）**：EV-1094 的 command/log 只覆盖 apps/api test，note 声称的
  `ModuleBoundaryTest 12/12` 与 `spotless` 不在该 EV 输出中；独立复跑已确认，但 builder 应在
  M06 gate 前补一条 clean ArchUnit+spotless EV（或合并命令），使任务证据自证 F-006 验收。

本评审只审 F-006 与 F-001 回归；不修改源码/测试/契约/PRODUCT/AGENTS/`.riccon` 状态，不运行
gate check，不替代模块 review、Product 预验收或用户最终验收。

## F-006：分层修复

- **domain 投影**：新增
  `apps/api/src/main/java/com/riccon/api/workflow/approval/domain/WorkflowApprovalCitationSnapshot.java`
  （`workflowRunId`/`finishedAt`/`citationsJson`，非空校验，无 output/参数/正文）。
- **repository 映射**：`WorkflowApprovalRepository.findSucceededCitationsByRuns`
  （`WorkflowApprovalRepository.java:154-177`）内部读取
  `WorkflowApprovalCitationRow`（persistence，仓储层允许），映射为
  `WorkflowApprovalCitationSnapshot` 并用 `LinkedHashMap` 按运行分组返回。
- **service 去除 persistence**：`WorkflowApprovalService` 已无
  `approval.persistence` import；`citationsByRun` 直接返回 repository 的领域映射，
  `documentReferenceCount(List<WorkflowApprovalCitationSnapshot>,...)` 只读 domain 字段
  （`WorkflowApprovalService.java:621-656`）。
- **批量/N+1 不变**：`WorkflowApprovalJdbc.findSucceededCitationsByRuns`
  （`WorkflowApprovalJdbc.java:475-508`）仍以一次 `IN (:runIds)` 查询覆盖一页待办涉及的
  运行，SQL 条件 `status='SUCCEEDED' AND citations_json IS NOT NULL AND finished_at IS NOT NULL`
  未变；repository 只做内存映射/分组，不新增 SQL；待办列表仍是一次 count + 一次 list +
  一次批量引用查询。
- **测试**：`WorkflowApprovalApiTest`/`WorkflowRunHistoryRetentionApiTest` 的待办计数与
  运行详情用例均通过（103 tests 全覆盖），没有行为回归。

## ArchUnit

- 独立命令：
  `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest
-Dsurefire.failIfNoSpecifiedTests=false`。
- 结果：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`；surefire XML
  `tests="12" failures="0" errors="0"`。
- 此前 REV-002 的 5 个 `WorkflowApprovalService` → `WorkflowApprovalCitationRow`
  service→persistence 违例全部消失；`mvcLayeringIsOneWay` 通过，且未修改
  `platform-architecture-tests` 规则、无 `@SuppressWarnings`。

## 测试与独立复验

- **EV-1094（权威）**：commit `cebcc418`、dirty=false、cwd 主工作树、103 tests、
  exit 0；日志 SHA-256
  `0fe6a5259c616d52b9cbc635d380285e63fdd8c7d658553bc9b129a79b66cd8d` 与 EV JSON 一致。
- **独立 T08 复跑**：103 tests、0 failure/0 error/0 skip、BUILD SUCCESS；本次未命中
  REV-001 F-004 的并发 flake（其仍是 accepted P3，未重开）。
- **F-001 回归**：`WorkflowRunHistoryApiTest` 11/11 通过，路径端点
  `GET /api/v1/workflows/{workflowId}/runs` 仍委托全局 list，F-001 无回归。
- **Spotless**：`./mvnw -q spotless:check` exit 0。
- **证据卫生**：EV-1088/EV-1093/EV-1094 均 dirty=false；EV-1090 dirty=true 与 EV-1092
  temp worktree 均未被任务卡引用。EV-1094 的 command/log 仅 apps/api test（F-007）。

## 证据

- **EV-1094**：`commit=cebcc418b95123b953d19ae21bc5ac9e8ec603ca`、`dirty=false`、
  `cwd=/Users/ricky/Documents/projects/riccon-work`、103 tests、exit 0；hash 已核对。
- **EV-1093**：F-001 主工作树 clean 证据（commit e00081bb，103 tests，dirty=false）。
- **EV-1088**：原始 T08 feature 证据（commit 0511afd3，dirty=false）。
- **不引用**：EV-1090（dirty=true）、EV-1092（temp worktree）。
- 独立日志（不写入 `.riccon/evidence`）：
  `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify-003/`：
  `tests.log`（103 tests）、`spotless.log`（exit 0）、`arch.log`（ArchUnit 12/12）。
- F-007 证据：EV-1094 的 command/note 与日志边界；独立 ArchUnit/spotless 日志已证明代码行为。

## 未验证

- 全量模块 gate build/coverage/E2E/a11y/perf/security：T17；本 delta 只补 ArchUnit 与 T08
  定向测试，未跑 E2E/perf。
- UI 旅程（T12a/T12b/T13/T16a）与 AC-12 空态、AC-13 结果再次打开、AC-14 保留值展示、
  AC-16 审计查询界面。
- U-05（审批理由 PII）仍是 T09 F-002 accepted residual，本任务不声称已接受。
- F-004 并发 flake：本次独立复跑未命中，其 accepted P3 状态不变，T16a 仍需硬化。

## Findings

- **F-006（P1，fixed）**：service 不再依赖 approval/persistence；domain 投影由 repository
  返回；批量查询/N+1 与计数语义不变；ArchUnit 12/12、103 tests、spotless 均通过。**fixed**。
- **F-001（P2，fixed）**：路径端点仍 11/11 通过，无回归；REV-002 的 F-001 保持 fixed。
- **F-007（P3，accepted）**：EV-1094 command/log 未覆盖 ArchUnit/spotless，note 的声明由独立
  复跑证实；builder 在 M06 gate 前补 clean ArchUnit+spotless EV。owner=riccon/builder-b1，
  due=M06 gate 前（T17），风险有界、无代码/行为缺陷。
- 无新 P1/P2；REV-001 F-002..F-005、F-004 的 accepted P3 不重开。

## 剩余风险

- **F-007 证据完整性**：当前 EV-1094 可自证 103 tests，但不能自证 ArchUnit/spotless；T17
  gate 前应补 EV，避免“note 声称、日志未覆盖”的证据漂移。
- **F-004 并发 flake**：测试对 cancel 恒 202 的假设在合法交错下仍可能失败；本次未命中，
  生产语义可接受，T16a 硬化。
- 全量 E2E/perf/coverage、UI 旅程、U-05 仍归 T12a/T12b/T13/T16a/T17。

## 回执

```text
任务：TASK-0228（M06 T08）F-006 最终 delta 复审
review_id：REV-TASK-0228-003
previous_review：REV-TASK-0228-002
verdict：pass
findings：P0=0；P1=0（F-006 fixed）；P2=0；P3=1 accepted（F-007 证据覆盖）；
  F-001 fixed（路径端点仍 11/11）
关键结论：
- F-006 已修复：service 不再 import approval/persistence；repository 返回 domain
  WorkflowApprovalCitationSnapshot 投影；JDBC 批量查询与按审批 createdAt 过滤/去重计数
  不变，仍一次批量查询（无 N+1）。
- 独立复跑：ModuleBoundaryTest 12/12（0 failure/0 skip）；T08 103 tests 0 failure/0 skip；
  spotless exit 0；F-001 路径端点 11/11 无回归。
- EV-1094 clean：cebcc418、dirty=false、主工作树、103 tests、hash 一致；EV-1088/1093/1094
  均 clean；EV-1090 dirty/EV-1092 temp 未引用。
- 保留 P3：EV-1094 的 command/log 只覆盖 apps/api，note 声称的 ArchUnit 12/12 与 spotless
  不在该 EV 输出；独立复跑已证明，builder 在 M06 gate 前补 clean EV 即可。
证据：EV-1094（hash 0fe6...）；独立日志
  /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify-003/；
  review 文件 .riccon/reviews/tasks/TASK-0228-review-003.md
未测：全量 E2E/perf/coverage/UI、U-05；未运行 gate check。
```
