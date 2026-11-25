---
review_id: REV-TASK-0228-001
module_id: M06
task_id: TASK-0228
reviewer:
  agent: riccon/verifier
  session: ses_ded85bc67ffe1EocTlpm6whABL
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 0511afd31791f227a402ff262bf3c58cafb01909
head_commit: b11e557d12f33b13d0fbdee4a7ad56be784b2a11
baseline_commit: b514fe6729569860be4e085482053377dc2a2425
task_card_baseline_commit: "b514fe67"
task_card_end_commit: "0511afd3"
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  review: true
  evidence: true
  scope: true
  security: true
dod_not_applicable:
  - coverage
  - e2e
  - performance
  - accessibility
  - migration
  - docs
  - ui
dod_note: >-
  pass (with residual)。T08 在真实 MySQL/Testcontainers、真实会话/CSRF/权限链和真实审计写入下
  通过历史列表、详情、过滤/分页/倒序、审批聚合/counts、引用 sourceStatus/NO_ACCESS/DELETED 遮蔽、
  180 天到期不可见、重试/取消权限与 409/幂等、管理性读取审计，以及 T09 F-001/F-003/F-004 的
  审批保留期、批量引用计数与取消/审批竞态用例；独立重跑 101 tests、0 failure/0 error/0 skip，
  spotless 通过，无 open P0/P1。保留 1 个 accepted P2（契约操作
  `GET /api/v1/workflows/{workflowId}/runs` 无实现）和 4 个 accepted P3（过滤跨度未限 180 天、
  运行详情审批引用计数与 T09 口径不完全一致、竞态测试对 cancel 恒 202 的假设可能 flake、
  管理性读取审计无独立 fail-closed 负向用例）。AC-12/13/14/15/16 的 UI/E2E/审计查询面归
  T12a/T12b/T16a，本评审只判 T08 API 子集。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead（范围/任务拆分）；riccon/architect（契约口径）"
    due: "M06 gate 前（T17 或补任务）"
    title: "契约操作 GET /api/v1/workflows/{workflowId}/runs 未实现"
    summary: >-
      `contracts/openapi.yaml:2892-2915` 定义了 `listWorkflowRunsForWorkflow`
      （GET `/api/v1/workflows/{workflowId}/runs`，200 返回 `WorkflowRunPage`），
      `contracts/README.md:135,191` 也把它列为 M06 契约测试覆盖项；但代码中只有
      `WorkflowRunController.java:76` 的 POST 创建运行，`WorkflowHistoryController.java:80,113`
      只映射 `/api/v1/workflow-runs` 与 `/{workflowRunId}`，全仓库没有该 GET 映射。
      观察：生成客户端或 T12b 若按契约调用该路径，会得到 405/404 而不是 200；
      期望：实现该 operation（复用历史列表的可见性/保留/分页逻辑），或在契约中标记
      `x-riccon-deferred` 并记录决策。全局 `/api/v1/workflow-runs?workflowId=...` 已覆盖
      AC-12 的用户可见能力，因此不影响当前 UI 主路径，也不构成越权或内容泄露。
    accepted_reason: >-
      当前 T12b 前端尚未实现，仓库中没有调用该路径的 UI；全局运行历史端点使用同一组
      可见性/保留/排序规则，AC-12 的“流程所有者元数据/管理员全部/过滤分页”已通过。
      这是契约完整性与客户端兼容性 residual，不是安全边界或数据丢失问题；由 Lead 在
      M06 gate 前决定补实现或修改契约为 deferred，不能静默保留 405/404。
    evidence: >-
      contracts/openapi.yaml:2892-2915；contracts/README.md:135,191；
      apps/api/src/main/java/com/riccon/api/workflow/run/controller/WorkflowRunController.java:76；
      apps/api/src/main/java/com/riccon/api/workflow/history/controller/WorkflowRunHistoryController.java:80,113；
      apps/api/src/test/java/com/riccon/api/contract/WorkflowContractM06Test.java:62（仅校验 YAML operation，不校验 controller 映射）。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "M06 gate 前（T17/T16a 复测）"
    title: "运行历史过滤 from/to 未限制 180 天最大跨度"
    summary: >-
      ADR-0024 §7.4（`docs/adr/ADR-0024-m06-workflow-orchestration.md:570`）要求运行历史
      过滤时间范围带偏移、最长 180 天。`WorkflowHistoryRequestParser.parseFilter`
      （`WorkflowHistoryRequestParser.java:32-56`）只校验 from≤to、格式与 versionNo，
      未校验跨度；`WorkflowHistoryFilter.hasTimeRange()`（`WorkflowHistoryFilter.java:37-40`）
      没有任何调用方。观察：`from=2020-01-01T00:00:00+08:00&to=2026-11-25T...` 被接受；
      期望：跨度超过 180 天返回 400 `RICCON-WF-400`，或在契约为该参数明确上限。
      由于 SQL 同时带 `retention_expires_at > now`，实际可扫描数据仍被 180 天保留期约束，
      不产生跨保留期的数据放大；属输入校验/契约一致性 residual。
    accepted_reason: >-
      不影响可见性、租户隔离或真实数据量；缺失上限只让调用方传一个更宽但被保留期裁剪的
      时间窗。contract openapi 当前未写最大跨度，修改契约需 Architect 决策；建议随 T17
      契约收口或 T16a 性能用例一起处理，不阻塞 T08 功能验收。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:570；
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryRequestParser.java:32-56；
      apps/api/src/main/java/com/riccon/api/workflow/history/domain/WorkflowHistoryFilter.java:37-40；
      apps/api/src/main/java/com/riccon/api/workflow/history/persistence/WorkflowHistoryJdbc.java:303-337。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "M06 gate 前（T17 口径核对）"
    title: "运行详情审批 dataScopeSummary 的 documentReferenceCount 含非 SUCCEEDED 节点引用"
    summary: >-
      运行详情把全部节点尝试的 citations_json 解析后，按 `finishedAt <= approval.createdAt`
      计数（`WorkflowHistoryService.java:187,195,526-540`），未过滤节点状态；
      T09 审批端点的同名计数只统计 `status='SUCCEEDED'` 的上游节点
      （`WorkflowApprovalJdbc.java:483-508`）。观察：同一审批若上游有 FAILED/SKIPPED 节点
      且这些节点写了 citations_json，运行详情显示的 `documentReferenceCount` 可能大于
      审批详情接口；期望：两个入口对同一审批使用一致口径。属计数展示不一致，不泄露
      标题/片段/正文，也不改变审批/权限判定。
    accepted_reason: >-
      计数是辅助信息，审批详情接口是审批人的权威数据范围摘要；差异只在异常/重试节点留下
      引用时出现，当前测试路径（成功检索节点）下一致。修复可复用同一 SUCCEEDED 过滤或
      抽共享计数服务，属 M06 gate 前口径统一项。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryService.java:186-199,399-416,526-540；
      apps/api/src/main/java/com/riccon/api/workflow/approval/persistence/WorkflowApprovalJdbc.java:475-508；
      apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryApiTest.java:299-317。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "T16a 取消/审批竞态硬化时"
    title: "并发取消/审批测试断言 cancel 恒为 202，存在合法交错导致 flake"
    summary: >-
      `WorkflowRunHistoryConcurrencyApiTest.concurrentCancelAndDecide_consistentOutcome`
      （`WorkflowRunHistoryConcurrencyApiTest.java:86-143`）断言 cancel 响应恒为 202。
      按 T05 的 `WorkflowRunService.cancel` 与 `WorkflowRunJdbc.markCancelled`
      （`WorkflowRunService.java:220-250`、`WorkflowRunJdbc.java:236-254`），若 decide
      先提交（审批 APPROVED、运行恢复 PENDING），而 cancel 已读到旧状态 WAITING_APPROVAL，
      `markCancelled` 条件更新为 0 行，cancel 会按“运行状态变化”返回 409；这是合法的竞态
      结果，却会让本测试失败。观察：本次 3 轮全部通过，但该交错可复现性取决于数据库提交
      顺序；期望：测试接受 cancel ∈ {202,409}，并继续断言“不复活已取消运行/不重复成功审计”。
      生产语义本身（取消或决定一方 409）可接受，不是功能缺陷。
    accepted_reason: >-
      这是测试稳健性 residual，不会造成假绿：当前测试没有 retry，失败会显式暴露；且最终
      状态断言仍覆盖“取消不被审批复活”。T16a 的取消/审批安全旅程应改为接受 409 或使用
      可控锁顺序，避免 CI flake。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryConcurrencyApiTest.java:86-143；
      apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java:220-250；
      apps/api/src/main/java/com/riccon/api/workflow/run/persistence/WorkflowRunJdbc.java:236-274；
      apps/api/src/main/java/com/riccon/api/workflow/approval/persistence/WorkflowApprovalJdbc.java:222-232,616-625。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-b1 / riccon/verifier（T16a）"
    due: "M06 gate 前（IT-AUDIT-M06-01）"
    title: "workflow.run.admin_read 无独立审计 fail-closed 负向用例"
    summary: >-
      `WorkflowHistoryService.list/detail`（`WorkflowHistoryService.java:127-157,172-206`）
      在 `@Transactional` 内调用 `AuditService.append`，后者加入同一事务且不吞异常，
      代码路径满足 ADR-0024 §8.2 的“审计失败→业务失败”。但 T08 测试只正向断言 LIST/DETAIL
      审计条数与 details（`WorkflowRunHistoryApiTest.java:434-475`），没有像
      `WorkflowRunAuditFailClosedTest` 那样让 `workflow.run.admin_read` 写入抛错并断言
      列表/详情返回 500、不返回业务内容。T16a `IT-AUDIT-M06-01` 可补该负向用例。
    accepted_reason: >-
      事务结构与既有 `WorkflowRunAuditFailClosedTest` 相同（`AuditService.append` 无
      catch，`@Transactional` 默认 REQUIRED），行为可静态确认；缺少的是自动化负向证据，
      不是已知功能缺陷。由 T16a 审计套件覆盖后可关闭。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryService.java:127-157,172-206；
      apps/api/src/main/java/com/riccon/api/audit/service/AuditService.java:30-38；
      apps/api/src/test/java/com/riccon/api/workflow/run/WorkflowRunAuditFailClosedTest.java:29-80；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:637-644。
evidence:
  - EV-1088
reviewed_evidence:
  - EV-1088
notes: >-
  独立复跑命令与 EV-1088 相同：`./mvnw -pl apps/api test
  -Dtest='WorkflowRunHistory*Test,WorkflowApproval*Test,WorkflowRun*Test,WorkflowContractM06Test'
  -Dsurefire.failIfNoSpecifiedTests=false`，结果 101 tests / 0 failures / 0 errors / 0 skipped，
  exit 0；`./mvnw -q spotless:check` exit 0。临时日志在
  `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify/`，不写入
  `.riccon/evidence`。EV-1088 日志 SHA-256 与 EV JSON 的 `c3106129...` 一致，commit
  0511afd3、dirty=false。EV-1087 记录在未合入 main 的 amend 提交 fa20d3c 上，仅作历史快照；
  本评审以 EV-1088 为权威证据。
created_at: "2026-11-25T19:51:01+08:00"
---

# REV-TASK-0228-001：M06 T08 运行历史/详情/重试/取消/管理性读取/保留/审批聚合独立评审

## 结论

**Pass（带 residual）**。TASK-0228 在真实 MySQL/Testcontainers、真实会话/CSRF/权限链和真实
审计写入下通过运行历史、详情、过滤/分页/倒序、审批聚合/counts、引用 `sourceStatus` 与
`NO_ACCESS`/`DELETED` 遮蔽、180 天到期不可见、重试/取消权限与幂等/409、管理性读取审计，
以及 T09 F-001/F-003/F-004 的审批保留期、待办批量引用计数和取消/审批竞态用例。
`EV-1088` 记录在干净提交 `0511afd3`，101 tests 全过、0 skip；独立复跑 101 tests 与
`spotless:check` 均为 exit 0。无 open P0/P1。

保留 1 个 accepted P2：契约操作 `GET /api/v1/workflows/{workflowId}/runs` 没有 GET 实现
（全局 `/workflow-runs` 已覆盖 AC-12 主路径，契约完整性需 Lead/Architect 在 M06 gate 前收口）。
4 个 accepted P3：过滤跨度未限 180 天；运行详情审批引用计数与 T09 的 SUCCEEDED-only 口径
不完全一致；竞态测试对 cancel 恒 202 的假设存在合法交错 flake；管理性读取审计缺独立
fail-closed 负向用例。

本评审只判 T08 的 API 子集。AC-12 的空态/清除筛选、AC-13 的“结果再次打开”、AC-14 的
“界面显示保留值”、AC-16 的审计查询面归 T12a/T12b/T13/T16a；U-05（审批理由 PII）仍是
T09 F-002 的 accepted residual，不作为 T08 fail。本评审不修改源码/测试/契约/PRODUCT/AGENTS/
`.riccon` 状态，不运行 `gate check`，不替代模块 review、Product 预验收或用户最终验收。

## 评审范围与提交

| 项        | 值                                                                                                                                                                                                                                                                                                                                 |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 真实基线  | `b514fe6729569860be4e085482053377dc2a2425`（T08 feature commit 的父提交）                                                                                                                                                                                                                                                          |
| T08 提交  | `0511afd31791f227a402ff262bf3c58cafb01909`                                                                                                                                                                                                                                                                                         |
| 当前 HEAD | `b11e557d12f33b13d0fbdee4a7ad56be784b2a11`；`git diff 0511afd3..HEAD -- apps platform contracts docs` 为空，后续 `e692aa99`/`b11e557d` 只改 `.riccon` 证据/状态                                                                                                                                                                    |
| 任务卡    | `.riccon/tasks/TASK-0228.json`：status=review，baseline=`b514fe67`、end=`0511afd3`，evidence=[EV-1087,EV-1088]                                                                                                                                                                                                                     |
| 设计依据  | ADR-0024 §3.3.3/§6.2/§7.4/§8.1/§8.2；PRODUCT M06-AC-12/13/14/15/16；`contracts/openapi.yaml` 1.6.0-m06 的 run/detail/cancel/retry 与 approval schema                                                                                                                                                                               |
| 范围审计  | T08 diff 32 个文件、4269 insertions/61 deletions，全部在 `apps/api/src/{main,test}/java/com/riccon/api/workflow/history/**` 与 Lead 显式扩展的 `.../approval/**`（4 个文件，仅保留期/批量计数）；无 contracts/PRODUCT/AGENTS/V15/J2/T05/T07/T13 源码改动，无 T09 其他包/任务文件改动；`git diff --check b514fe67..0511afd3` exit 0 |
| 交错提交  | `ea5af615`（T13 前端）等交错提交位于基线 `b514fe67` 之前，不在 T08 diff；T13/T12b/T13b 前端未纳入本任务                                                                                                                                                                                                                            |

EV-1088：`commit=0511afd3`、`dirty=false`、`exit_code=0`、`manual=false`、101 tests；
日志文件 `.riccon/evidence/TASK-0228/EV-1088-2026-11-25T19-27-49-852+08-00-mvnw-pl-apps-api-test-dtest-workflowrunhistory-t.log`
SHA-256 `c3106129f20377bb983b985cfc55baae875a1585b5affb979f55162c8b125969`，与 EV JSON 一致。
EV-1087 记录在 amend 提交 `fa20d3c`（未合入 main），只作旧快照，不作为本评审依据。

## 历史列表（GET /api/v1/workflow-runs）

- **端点与权限**：`WorkflowRunHistoryController.list`（`WorkflowRunHistoryController.java:80-100`）
  `@RequirePermission(WORKFLOW_READ)`；`WorkflowHistoryActor` 用 `workflow.manage` 判定租户管理员
  （`WorkflowHistoryActor.java:1-30`，`RoleDefinitions.java:94-99` 仅 TENANT_ADMIN 持有该权限）。
  平台租户由 `WorkflowPlatformTenantFilter` 在权限拦截器前统一 404（`WorkflowPlatformTenantFilter.java:40-84`）。
- **可见性**：SQL `where` 固定 `r.tenant_id=:tenantId AND r.retention_expires_at > :now`；非管理员追加
  `(r.requester_user_id=:actorId OR d.owner_user_id=:actorId)`（`WorkflowHistoryJdbc.java:303-311`）。
  即发起人本人全部、流程所有者只看自己流程的运行元数据、租户管理员全部；与 ADR §6.2 一致。
  `WorkflowRunHistoryApiTest.listRuns_visibilityMatrix_andDescendingOrder` 断言 owner/editor/member/admin
  四种视图互不串数据、owner 看不到他人流程的 memberRun、admin 看全部。
- **过滤/分页/倒序**：`workflowId/versionNo/status/requesterUserId/from/to` 全部绑定参数
  （`WorkflowHistoryJdbc.java:312-335`）；`from>to`、无偏移时间、`versionNo<1`、非法 UUID、`page=0`、
  `size=101` 均 400（`WorkflowHistoryRequestParser.java:32-56`、`PageQuery.java:23-44`）；
  `ORDER BY COALESCE(started_at,created_at) DESC, id DESC`，PENDING 未开始运行按创建时间排序并稳定分页
  （`WorkflowHistoryJdbc.java:127-153`）。`listRuns_filtersAndPagination` 覆盖流程/状态/发起人/版本/
  时间过滤、page=2&size=1、total/totalPages 与 400 矩阵。
- **审批聚合/counts**：一次 `GROUP BY workflow_run_id` 批量查询 `SUM(status='PENDING'/'APPROVED'/'REJECTED')`
  （`WorkflowHistoryJdbc.java:221-243`）；`approvalStatus` 规则为 PENDING > REJECTED > APPROVED > null，
  CANCELLED 不计入（`WorkflowHistoryService.java:305-316`）。`listAndDetail_approvalAggregationAndCounts`
  覆盖 pending+approved+rejected+cancelled 混合、单 APPROVED/REJECTED/CANCELLED 与 counts。
- **空结果与到期**：`total==0` 返回 `items=[]`、`totalPages=0`；到期运行在 SQL 层过滤，管理员同样不可见
  （`expiredRun_isInvisibleEverywhere`：owner/admin 列表均空、详情均 404，且 admin 只写 1 条 LIST 审计）。
- **不泄露**：列表 SQL 不选 `inputs_json/result_json`；条目不含 result；`IT-WF-108` 断言 `has("result")==false`、
  不含 B 租户/他人流程名称或数量。

## 详情（GET /api/v1/workflow-runs/{workflowRunId}）

- **端点与 404 矩阵**：`WorkflowRunHistoryController.get`（`WorkflowRunHistoryController.java:113-122`）
  同权限；`findRunDetail` 带 `retention_expires_at > :now`（`WorkflowHistoryJdbc.java:166-173`）；
  `requireDetailVisible` 仅发起人或租户管理员，流程所有者、同租户他人、跨租户、平台、到期统一 404
  （`WorkflowHistoryService.java:215-219`）。`detail_visibilityMatrix` 覆盖 owner/member/platform/跨租户
  404 与 requester/admin 200；`expiredRun_isInvisibleEverywhere` 覆盖到期 404。
- **节点时间线**：`findNodeRuns` 读取全部 attempt，按 `COALESCE(started_at,created_at) ASC, attempt ASC,
node_key ASC`（`WorkflowHistoryJdbc.java:184-192`），响应含 nodeId/type/name/status/attempt/时间/
  duration/inputSummary/outputSummary/errorCode/errorMessage/approvalId/invocationId；
  `detail_failedRun_showsFailedNodeAndAttempts` 断言两次尝试、失败码与当前节点。
- **审批记录与 dataScopeSummary**：`findApprovals` 按 createdAt 升序，投影只取展示字段与 params_preview，
  不取 `params_ciphertext`/key（`WorkflowHistoryJdbc.java:73-95,201-209`）；`dataScopeSummary` 含
  runScope/departmentId/documentReferenceCount；`paramsPreview` 仅在“当前账号是 assignee 且非发起人”时返回
  （`WorkflowHistoryService.java:189-199,352-391`），测试断言 requester 无 preview、admin assignee 有 preview、
  响应不含 ciphertext/SECRET-OUTPUT-MARKER。
- **引用 sourceStatus 与遮蔽**：节点 citations_json 只解析一次；批量 `CitationStatusRepository.findStates`
  按当前账号 ACL/软删除/checksum 重算；聚合去重并按时间线重新编号；`NO_ACCESS`/`DELETED` 时 title/snippet
  置空、locatorType/pageNo/sheetName/cellRange/paragraphNo 置 null，保留 ID 与 score
  （`WorkflowHistoryService.java:399-517`）。`detail_successRun_citationsAndResult_noLeak` 断言
  AVAILABLE/UPDATED/DELETED 与遮蔽；`WorkflowRunHistoryCitationAclApiTest` 在
  `riccon.kb.acl.enforcement-enabled=true` 下断言私有文档 NO_ACCESS 且标题/片段/定位不泄露。
- **结果与不泄露**：`result` 只在终态且 `resultStatus=SAVED` 时解析，超限/NONE 返回 null；不返回
  `output_json`/inputs/prompt/完整参数/密文（`WorkflowHistoryService.java:548-571`、
  `WorkflowHistoryJdbc.java:65-70`）。测试断言 `result.text/format/sizeBytes/generatedAt`，且 body 不含
  `SECRET-INPUT-MARKER`/`SECRET-OUTPUT-MARKER`/`ciphertext`/`prompt`。
- **管理性读取**：详情仅当管理员且发起人不是本人时写 `workflow.run.admin_read scope=DETAIL`；管理员看自己的
  运行不写（`WorkflowHistoryService.java:201-204`）；`adminReadAudit_listAndDetail` 断言 details 为
  `workflowRunId/scope/reason`、targetId=runId、own run 不增审计、requester 读取不写。

## 重试/取消（复用 T05 端点，T08 验证约束）

- **权限**：取消/重试仅发起人或租户管理员；同租户非发起人 403 `RICCON-AUTH-403`，跨租户/平台 404
  `RICCON-TENANT-404`（`WorkflowRunHistoryLifecycleApiTest.cancelRun_permissionsAndIdempotency`、
  `retryRun_permissionsAndReplay`），与 ADR §6.2 一致；部门负责人不默认可取消他人运行。
- **取消幂等与终态**：已取消再取消返回 202 且不重复写 `workflow.run.cancelled`；SUCCEEDED 终态取消 409
  `RICCON-WF-409`；取消保留已完成节点（start 节点仍 SUCCEEDED）与错误码，`cancelledRun_detailRemainsReadable`
  断言详情可读、status=CANCELLED、errorCode=WF_CANCELLED、节点摘要保留。
- **重试**：只新增失败节点的 attempt 2，旧 FAILED 行保留；同一 Idempotency-Key 重放返回 202 且不重复新增
  attempt/审计；管理员可重试本租户他人运行。T08 没有修改 T05 源码，这些用例是对 AC-15 的重验。

## 保留与审批（关闭 REV-TASK-0229-001 F-001/F-003/F-004）

- **F-001 保留期**：`WorkflowApprovalJdbc.INBOX_FILTER`、`countInbox/listInbox`、`findById`、`RUN_STATE_SQL`、
  `RUN_GRAPH_SQL` 全部加入 `r.retention_expires_at > :now`
  （`WorkflowApprovalJdbc.java:74-79,127-146,305-357`）；`WorkflowApprovalService.list/get/decide/loadGraph`
  使用请求时刻（`WorkflowApprovalService.java:139-190,210-265,491-500`）。
  `WorkflowRunHistoryRetentionApiTest.approvalEndpoints_hideExpiredRun` 验证到期审批从待办消失、详情 404、
  决定 404 且不写 `workflow.approval.decided`，运行保持 WAITING_APPROVAL。
- **F-003 N+1**：待办列表改为一次 `findSucceededCitationsByRuns` 批量读取本页全部运行引用，再按每个审批
  createdAt 过滤计数（`WorkflowApprovalService.java:144-166,636-680`、
  `WorkflowApprovalJdbc.java:475-508`）；`inbox_documentReferenceCount_isBatchCorrect` 用 3 个运行/1/2/3 条引用
  断言计数正确且待办 createdAt 升序。详情仍单审批查询，属单条无 N+1。
- **F-004 竞态**：`WorkflowRunHistoryConcurrencyApiTest` 覆盖取消后决定 409、审批 PENDING 但运行已取消的
  RUN_STATE_CONFLICT 409、取消/批准并发仅两种一致结果且不重复成功审计、已取消运行不复活
  （附 F-004 的 flake 风险，见 findings）。
- **保留期边界**：运行列表/详情 SQL 与审批端点 SQL 都以 `retention_expires_at > now` 判定；测试用
  `setRetention` 直接模拟到期，未真等 180 天（合理）。T05 的 cancel/retry 仍未应用 retention，见剩余风险。

## 审计（workflow.run.admin_read）

- **动作与 details**：`WorkflowHistoryAuditEvents` 固定 action `workflow.run.admin_read`、targetType
  `WORKFLOW_RUN`、outcome SUCCESS；LIST 不带 workflowRunId，DETAIL 带 runId；details 仅
  `workflowRunId?/scope/reason=MANAGEMENT_READ`（`WorkflowHistoryAuditEvents.java:42-96`）。
  `AuditDetailsPolicy` 白名单含该 action 且 scope 枚举 LIST/DETAIL、reason 固定 MANAGEMENT_READ
  （`AuditDetailsPolicy.java:312,464`）。
- **触发规则**：LIST 每次管理员请求一条（空结果也写，`WorkflowHistoryService.java:151-155`）；DETAIL 仅管理员
  且非本人发起时一条（`:201-204`）；管理员看自己的运行、发起人看自己的运行都不写。测试覆盖 1/2/3 条
  计数、targetId、details size 与 outcome。
- **fail-closed**：`list/detail` 为 `@Transactional`，`AuditService.append` 无 catch 且以 REQUIRED 加入同一事务，
  审计异常会抛到全局异常处理并回滚（`WorkflowHistoryService.java:127,172`、`AuditService.java:30-38`）；
  同 T05 的 `WorkflowRunAuditFailClosedTest` 机制。T08 没有独立的 admin_read 负向用例，作为 F-005
  accepted P3 交 T16a `IT-AUDIT-M06-01`。

## 证据与独立复验

- **EV-1088**：`./mvnw -pl apps/api test -Dtest='WorkflowRunHistory*Test,WorkflowApproval*Test,WorkflowRun*Test,WorkflowContractM06Test' -Dsurefire.failIfNoSpecifiedTests=false`，
  commit 0511afd3、dirty=false、exit 0、101 tests、0 skip；日志 SHA-256 与 EV JSON 一致。
- **独立复跑**：同一命令，`TEST_EXIT=0`，`Tests run: 101, Failures: 0, Errors: 0, Skipped: 0`，
  `BUILD SUCCESS`（2026-11-25T19:40+08）；`./mvnw -q spotless:check` `SPOTLESS_EXIT=0`。
  临时日志 `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify/{tests.log,spotless.log,summary.txt}`，
  不写入 `.riccon/evidence`。
- **测试真实性**：Surefire 日志显示 history 18 个用例（Api 9 + CitationAcl 1 + Lifecycle 3 + Retention 2 +
  Concurrency 3）与 approval/run/contract 全部实际执行，0 skipped；无 `@Disabled`/`assumeTrue`/retry 配置。
- **范围**：T08 diff 文件全部在任务卡 write_scope；无 contracts/PRODUCT/AGENTS/V15/J2/T05/T07/T09/T13
  源码改动；`git diff --check` clean。

## 未验证

- **UI/E2E**：AC-12 空态/清除筛选、AC-13 结果再次打开、AC-14 保留值展示、AC-16 审计查询界面与
  E2E-M06-08/09 归 T12a/T12b/T13/T16a；本任务只判 API。
- **全量 build/coverage/lint/typecheck/E2E/a11y/perf/security**：归 T17 模块 gate；本评审只跑 API 定向
  test + spotless。
- **性能预算**：API p95≤500ms、50 并发、列表/详情 SQL 计划未测；T09 F-003 的 N+1 已在代码层修复，
  但要求 T16a/T17 复测。
- **真实 180 天到期**：测试用 setRetention 模拟，未用可注入时钟跨 180 天；逻辑边界由 `>` 条件保证。
- **契约 operation 实现覆盖**：`WorkflowContractM06Test` 只校验 openapi YAML，不校验 controller 映射；
  F-001 的 `GET /workflows/{workflowId}/runs` 未实现，见 finding。
- **U-05**：审批 `decisionComment` 完整写入审计/审批记录的 PII 风险仍是 T09 F-002 accepted residual，
  M06 gate 前由安全评审/Product 二选一；本任务不声称已接受。

## Findings

见 front matter。摘要：

- **F-001（P2，accepted）**：`GET /api/v1/workflows/{workflowId}/runs` 契约 operation 无实现（405/404）；
  全局 `/workflow-runs?workflowId=` 已覆盖 AC-12 主路径，Lead/Architect 在 M06 gate 前补实现或 deferred。
- **F-002（P3，accepted）**：运行历史 from/to 未限制 180 天最大跨度；`hasTimeRange()` 未使用。
- **F-003（P3，accepted）**：运行详情审批 `documentReferenceCount` 含 FAILED/SKIPPED 节点引用，
  与 T09 SUCCEEDED-only 口径不一致。
- **F-004（P3，accepted）**：并发取消/审批测试断言 cancel 恒 202，存在合法交错 409 的 flake 风险；
  生产语义可接受。
- **F-005（P3，accepted）**：`workflow.run.admin_read` 无独立 fail-closed 负向用例；代码路径满足，
  T16a `IT-AUDIT-M06-01` 补测。

## 剩余风险

- **U-05 PII（T09 F-002）**：审批理由原样进入审计，未解决；M06 gate 前必须二选一并补测试。
- **T05 cancel/retry 未应用 retention**：到期运行仍可按 ID 取消/重试（列表/详情不可见）；AC-14 只要求
  不可见，不要求不可变更，建议 T17 明确是否拒绝到期运行的写操作。
- **引用快照 bbox/ocrConfidence**：Worker `WorkflowCitation` 未持久化 bbox/ocrConfidence，运行详情对
  OCR/图片引用只能定位到页/段落；属 M05b/M06 引用模型缺口，不在 T08 write_scope，建议 M06b/T17 评估。
- **性能**：列表 3 条 SQL、详情 5 条 SQL（无 N+1），但 `ORDER BY COALESCE(started_at,created_at)` 的
  索引使用与 50 并发 p95 未测；T16a/T17 必测。
- **测试 flake**：F-004 的并发测试在 CI 高负载下可能偶发 cancel 409；T16a 改为接受两种合法结果。
- **平台/跨租户列表用例**：T08 只测平台/跨租户详情 404，列表由 filter 与 T01/T16a `TEN-M06-01` 覆盖；
  本任务未新增列表平台/跨租户用例，模块 gate 需确认。

## 回执

```text
任务：TASK-0228（M06 T08 运行历史/详情/重试/取消/管理性读取/保留/审批聚合）
review_id：REV-TASK-0228-001
verdict：pass（带 residual；无 open P0/P1）
findings：P0=0，P1=0，P2=1 accepted（F-001），P3=4 accepted（F-002..F-005）
关键结论：历史/详情/过滤/倒序/审批聚合/保留/引用 ACL/重试取消/管理性读取审计均在真实
  MySQL+HTTP+权限链下通过；EV-1088 clean（commit 0511afd3，dirty=false，101 tests，日志 hash
  一致），独立复跑 101/0/0/0 + spotless exit 0。T09 F-001/F-003/F-004 已在审批待办/详情/决定
  与竞态用例中关闭。唯一 P2 是契约 `GET /workflows/{workflowId}/runs` 无实现，需 Lead 在
  M06 gate 前补实现或 deferred；UI/E2E/perf/全量 gate 归 T12a/T12b/T13/T16a/T17。
证据：EV-1088；独立复跑日志 /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t08-verify/
未测：全量 build/coverage/E2E/a11y/perf/security、UI 旅程、真实 180 天时钟、U-05 决策。
```
