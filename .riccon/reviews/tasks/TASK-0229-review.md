---
review_id: REV-TASK-0229-001
module_id: M06
task_id: TASK-0229
reviewer:
  agent: riccon/verifier
  session: ses_dedd16494ffeIzIzpYWceUNjgB
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 9fb7d8bb33e1d289ec4ac7fbd5116ec25cd47b61
  - cdd525aab66953b7285589739794b447fd0b55be
  - 2d89c70dd046ed04b7d058f83bed6d2bbdce0014
  - 6b2a35a66b77d33cdfd7d6f64ca27b80043258c3
head_commit: b8ff470f6130245848be678929cd8a179dc71bd8
baseline_commit: 8f077fa56324ad505cf3f4a0ef09ac9f64a8df8a
task_card_baseline_commit: "578df556"
task_card_end_commit: "6b2a35a"
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
dod_note: >-
  pass (with residual)。T09 API 的待办/详情/决定、自批/范围/停用拒绝、并发 409、批准恢复、
  拒绝失败/下游 SKIPPED/result_status=NONE、决定审计 201–500 写入与读取均有真实 MySQL/HTTP
  测试与独立复跑支撑；无 open P0/P1。保留 2 个 accepted P2（F-001 审批端点未应用运行保留期、
  F-002 U-05 未解决）与 2 个 accepted P3（F-003 待办 N+1、F-004 RUN_STATE_CONFLICT 测试缺口）、
  1 个 accepted P3（F-005 幂等文案漂移）和 1 个 fixed P3（F-006 openapi assignee-only 漂移）。
  AC-05/06/15/18 的 UI/E2E/最终结果产出不属 T09，AC 表标 partial，模块 gate 必须绑定 T13/T16a。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead（范围/任务拆分）；riccon/builder-b1（实现）"
    due: "M06 gate 前"
    title: "审批待办/详情/决定未应用运行保留期到期不可见"
    summary: >-
      `WorkflowApprovalJdbc` 的 `APPROVAL_FROM`/`findById`/`listInbox`、`RUN_STATE_SQL`、
      `RUN_GRAPH_SQL` 只按 `tenant_id` 过滤，没有 `r.retention_expires_at > NOW(6)`。观察：超过
      180 天仍 PENDING 的审批仍会出现在 assignee 待办，详情可返回 `paramsPreview`，决定可把过期
      运行恢复为 PENDING。T08（TASK-0228）验收包含“180 天到期不可见/审批聚合”，但 write_scope
      仅 `apps/api/src/main/java/com/riccon/api/workflow/history/**`，不能修改 approval 包。
      期望：T09 查询加入保留期条件，或抽共享可见性服务；Lead 扩展 T08 scope 或新增修复任务。
    accepted_reason: >-
      T09 任务卡验收未包含 retention；正常审批在小时级完成，只有长期 PENDING 审批才会暴露；
      无跨租户/跨部门越权，影响限于本租户 assignee 的过期数据可见与恢复，属数据保留/可见性
      合规 residual。T08 在 M06 gate 前实施，Lead 必须明确由谁覆盖审批端点；不阻塞 T09 功能验收。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/persistence/WorkflowApprovalJdbc.java:59-72,130-146,332-335；
      apps/api/src/main/resources/db/migration/V15__workflow_studio.sql:127；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:178,642；.riccon/tasks/TASK-0228.json（write_scope）。
  - id: F-002
    severity: P2
    status: accepted
    owner: "安全评审/Product（Lead 跟踪）"
    due: "M06 gate 前"
    title: "U-05 仍未解决：完整审批理由按临时默认进入审计"
    summary: >-
      `WorkflowApprovalAuditEvents.decided` 把 201–500 字符 `decisionComment` 原样写入
      `workflow.approval.decided`；API 写入/读取 500 上限已一致，但 ADR-0024 §8.3/F-013 的 U-05
      （理由可能含 PII）仍是未解决安全项。T09 未改 `commentSha256`，也未伪造安全接受。
    accepted_reason: >-
      ADR §8.3 明确授权临时默认并在 M06 gate 前二选一；T09 任务卡只要求关闭“201–500 写不进/
      读不出”，不负责替安全评审做结论。风险有界（用户输入、≤500、只进审计/审批记录、不写
      prompt/文档正文），但 M06 gate 必须由安全评审/Product 二选一并补测试；不得声称 U-05 已接受。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalAuditEvents.java:42-91；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:491-501,735；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:620,974,1050；docs/PRODUCT.md:11,26,28。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "M06 gate 前（perf/50 并发复测）"
    title: "待办列表 documentReferenceCount 为逐项查询（N+1）"
    summary: >-
      `WorkflowApprovalService.list` 对每个待办调用 `documentReferenceCount`，后者各执行一次
      `findSucceededCitations`；size 最大 100 时一次列表最多 1 次 count + 1 次列表 + 100 次引用
      查询。当前走 `(tenant_id, workflow_run_id, started_at)` 索引，默认 size=20，功能正确；但
      模块 API p95 ≤500ms/50 并发预算尚未测量。
    accepted_reason: >-
      查询有界且走运行索引，默认 20 条风险有限；不阻塞 T09 功能验收。M06 gate 的 k6/50 并发
      验证必须复测该端点；若 p95 超预算，改为按 run 批量查询/一次 JOIN 聚合，不改变响应语义。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalService.java:138-161,606-625；
      apps/api/src/main/java/com/riccon/api/workflow/approval/persistence/WorkflowApprovalJdbc.java:434-441；
      apps/api/src/main/java/com/riccon/api/identity/dto/PageQuery.java（size ≤100）。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "M06 gate 前 / T16a 取消-审批竞态"
    title: "RUN_STATE_CONFLICT（运行已取消/非等待审批）分支无 API 测试"
    summary: >-
      `WorkflowApprovalService.decide` 在审批 PENDING 后检查 `run.status == WAITING_APPROVAL`，
      否则 409 `RICCON-WF-409`；T09 测试覆盖重复/并发 409，但没有构造“审批仍 PENDING、运行已
      CANCELLED/FAILED”的 API 用例。代码路径与事务回滚清晰，缺少取消/审批竞态的端到端证据。
    accepted_reason: >-
      分支代码存在且与 T07a 语义一致，取消会条件更新审批为 CANCELLED/运行 CANCELLED；T16a 的
      取消/审批安全旅程可覆盖该竞态。作为测试覆盖 residual 接受，不阻塞 T09 功能验收。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalService.java:229-231；
      apps/api/src/test/java/com/riccon/api/workflow/approval/WorkflowApprovalApiTest.java:183-254,430-476（仅重复/并发）。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "M06 gate 前"
    title: "审批决定 Idempotency-Key 文案漂移：README 说键级重放，契约/实现是 409"
    summary: >-
      `contracts/README.md:99,145` 把审批决定列为键级 `Idempotency-Key` 重放，ADR-0024 §7.4
      （:558）同一 bullet 同时写“同一主体+资源+键返回既有结果”和“重试/审批由状态机与既有唯一键
      兜底”，自相矛盾；openapi decision 描述（:3150-3157）、ADR §5.4（:462）和 T09 实现则是
      重复/并发 409、不持久化键、不重放。客户端按 README 重试会得到 409 而非既有 200，容易误判
      决定失败。
    accepted_reason: >-
      T09 行为与 openapi/§5.4 及测试一致（重复 409、只一条成功审计），漂移在文档；契约文档是
      客户端契约事实源，M06 gate 前 Architect 应收敛 README/§7.4 到 409/状态机口径，或另立决策
      实现真正键级重放；不阻塞 T09 代码通过。
    evidence: >-
      contracts/README.md:99,145；contracts/openapi.yaml:3150-3157；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:462,558；
      apps/api/src/main/java/com/riccon/api/workflow/approval/controller/WorkflowApprovalController.java:41-42；
      apps/api/src/test/java/com/riccon/api/workflow/approval/WorkflowApprovalApiTest.java:183-254。
  - id: F-006
    severity: P3
    status: fixed
    owner: "riccon/architect"
    title: "openapi getWorkflowApproval 曾写“或租户管理员”，与 ADR §5.3/assignee-only 不一致"
    summary: >-
      T09 结束提交 6b2a35a 的 `contracts/openapi.yaml` getWorkflowApproval 描述为“只显示分配给
      当前用户或租户管理员的审批”，与 ADR §5.3（未分配 404、租户管理员不因身份获得参数摘要）
      和 T09 实现不一致。T09 代码按 ADR 实现；后续 contract-only 提交 11793f51 已改为
      assignee-only，当前 HEAD b8ff470f 已生效，无代码影响。
    evidence: >-
      git show 6b2a35a:contracts/openapi.yaml（旧描述）；contracts/openapi.yaml:3126（当前）；
      git show 11793f51 -- contracts/openapi.yaml。
reviewed_evidence:
  - EV-1082
evidence:
  - EV-1082
created_at: "2026-11-25T18:30:00+08:00"
---

# REV-TASK-0229-001：M06 T09 审批待办/详情/决定 API 独立评审

## 结论

**Pass（带 residual）**。TASK-0229 的审批待办、详情、决定 API 在真实 MySQL、真实
会话/CSRF/权限链和真实审计写入下通过验证：待办仅 assignee PENDING、`created_at ASC`、分页与
空页；详情仅 assignee、发起人/管理员/跨租户/平台 404、`dataScopeSummary` 与脱敏
`paramsPreview`；决定覆盖批准恢复、拒绝失败/下游 SKIPPED/`result_status=NONE`、拒绝理由
必填且 ≤500、自批/范围/停用 403 + denied 审计、重复/并发 409、编排关闭 503；API 与 T07a
的条件更新/自批/范围/幂等语义一致，且 T09 测试是 API 证据而非 worker 测试复用。
`workflow.approval.decided.decisionComment` 的 201–500 写入/读取已关闭 T01 F-001 与 T07a F-002。

无 open P0/P1。保留 2 个 accepted P2：F-001 审批端点未应用运行保留期（跨 T08 范围缺口）、
F-002 U-05 仍未解决（T09 按 ADR §8.3 临时默认完整写入，不能声称已接受）；2 个 accepted P3：
F-003 待办列表 N+1、F-004 `RUN_STATE_CONFLICT` 无 API 测试；1 个 accepted P3：F-005
审批决定幂等文案漂移；1 个 fixed P3：F-006 openapi assignee-only 漂移（11793f51 已修）。
AC-05/06/15/18 的 UI/E2E/最终结果产出归 T13/T16a，任务级只判 API 子集，模块 gate 必须绑定
UI/E2E 后才能判完整 AC pass。

本评审不修改源码/测试/契约/PRODUCT/AGENTS/.riccon 状态，不运行 `gate check`，不替代 M06
模块 review、Product 预验收或用户最终验收，也不把任务标 done。

## 评审范围与提交

| 项       | 值                                                                                                                                                                                                                                                       |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 真实基线 | `8f077fa56324ad505cf3f4a0ef09ac9f64a8df8a`（T07a EV-1079 记录，`9fb7d8bb` 的父提交）                                                                                                                                                                     |
| T09 提交 | `9fb7d8bb`（API 与理由审计上限）→ `cdd525aa`（4 个契约测试 Javadoc 对齐）→ `2d89c70d`（USER 详情/无效决定/skipPendingNode）→ `6b2a35a6`（关闭态 503）                                                                                                    |
| 独立复跑 | `b8ff470f6130245848be678929cd8a179dc71bd8`（当前 main；`git diff 6b2a35a..HEAD -- apps/api platform` 为空，仅 contracts README/openapi 由 11793f51 修改）                                                                                                |
| 任务卡   | `.riccon/tasks/TASK-0229.json`：status=review，evidence=[EV-1082]，`baseline_commit=578df556`、`end_commit=6b2a35a`                                                                                                                                      |
| 设计依据 | ADR-0024 §5.2/§5.3/§5.4/§6.2/§7.2/§7.3/§8.1/§8.3、PRODUCT M06-AC-05/06/15/18、`contracts/openapi.yaml` 1.6.0-m06 审批 schema                                                                                                                             |
| 范围审计 | T09 四个提交的文件全部在任务卡 write_scope：`workflow/approval/**`、`AuditDetailsPolicy.java`、4 个契约测试 Javadoc；无 contracts/PRODUCT/AGENTS/V15/J2/T07a/T07b 源码改动，无 M06b/M26 模板/共享代码改动；`git diff --check 8f077fa5..6b2a35a` exit 0。 |

**交错提交说明**：`578df556` 是 T07b 的功能提交（`2d89c70d` 的后继、`6b2a35a6` 的前驱），不是
T09 首个提交的父提交；`git diff 578df556..6b2a35a` 只能看到最后一个 503 测试。本评审按真实
T09 基线 `8f077fa5` 审计四个提交，并按每个提交的文件清单排除 T07b 的 `workflow/tool/**`
文件。任务卡 `baseline_commit=578df556` 会让 closing diff 误导追溯，建议 Lead 后续任务卡使用
真实基线或在卡片中注明交错提交；不影响本次代码结论。

## 待办（GET /api/v1/workflow-approvals）

- **端点与权限**：`WorkflowApprovalController.list` 使用 `@RequirePermission(WORKFLOW_APPROVE)`，
  service 再用 `CurrentUser.hasPermission` 复核（`WorkflowApprovalController.java:81-93`、
  `WorkflowApprovalService.java:138-162`）。
- **只含 assignee PENDING**：`INBOX_FILTER` 固定 `a.status='PENDING'`、`a.requested_by <> :userId`，
  USER 审批走 `workflow_approval_assignee` EXISTS，ROLE 审批按 `user_role`/`role.code` 联接复查
  （`WorkflowApprovalJdbc.java:74-101`）。发起人自己的待办被排除，管理员未获指派时不额外返回。
- **排序/分页/空页**：`ORDER BY a.created_at ASC, a.id ASC LIMIT/OFFSET`
  （`WorkflowApprovalJdbc.java:316-322`）；`PageQuery` 强制 page≥1、size 1–100；`total==0` 时
  返回空 `items` 与 `totalPages=0`。显式传非 PENDING `status` 返回空页，不把已决定记录当待办
  （`WorkflowApprovalService.java:143-146`）。
- **测试证据**：`WorkflowApprovalApiTest.listInbox_onlyAssignedPending_sortedAscending`
  断言 editor 两条（oldest/newer 顺序）、member 仅 USER 指派一条、requester/admin 空、分页
  page=2/size=1、`status=APPROVED` 空、`size=0` 400（`WorkflowApprovalApiTest.java:32-99`）。
- **空态文案**：接口不返回固定文案，由 T13 前端展示；本任务只保证空页结构。
- **F-003**：列表对每条审批调用 `documentReferenceCount`，是逐项查询；功能正确但需模块 gate
  的 perf/并发复测，见 finding。

## 详情（GET /api/v1/workflow-approvals/{id}）

- **仅 assignee**：`requireAssignableVisibility` 拒绝发起人并要求 `inScope`（USER 查 assignee 表，
  ROLE 查 `user_role` + ACTIVE），否则统一 `WorkflowApprovalNotFoundException` 404
  （`WorkflowApprovalService.java:167-181,450-459`）。`findById` 带 `tenant_id` 过滤，跨租户 404；
  平台租户由 `WorkflowPlatformTenantFilter` 在权限拦截器前 404。
- **404 矩阵测试**：非 assignee member、tenantAdmin、requester、随机 ID、跨租户、平台均 404；
  assignee editor 200；USER 审批 member 200（`WorkflowApprovalApiTest.java:100-179`）。
- **dataScopeSummary**：由运行绑定版本 `run_scope`/`department_id` 与上游 citations 去重计数
  组成，只含范围/部门/计数（`WorkflowApprovalService.java:606-625`、
  `WorkflowApprovalJdbc.java:148-160,434-441`）。详情测试插入重复 documentId + fileId 三条引用，
  断言去重计数 2（`WorkflowApprovalApiTest.java:109-124`）。
- **脱敏 paramsPreview**：SQL 投影只取 `a.params_preview`，不取 `params_ciphertext`/key/prompt/
  节点 output；详情把 preview 解析为 JSON 对象并限制 ≤16 顶层键；列表 `detail=false` 时不返回
  `paramsPreview`、`selfApprovalBlocked`、`assigneeUserIds`
  （`WorkflowApprovalJdbc.java:48-56`、`WorkflowApprovalService.java:688-735`）。
  测试断言 preview 为 `{"query":"已脱敏参数","limit":5}`，响应字符串不含
  ciphertext/paramsCiphertext/prompt（`WorkflowApprovalApiTest.java:125-142`）。
- **ACL 最小可见**：不返回文档标题/片段/正文，只返回计数；M05 文档 ACL 不在此接口二次展开。
  T16a/IT-WF-106 仍需用真实无权文档证明审批摘要无正文/片段；本任务只证明响应结构不含这些字段。
- **保留期缺口**：查询未加 `r.retention_expires_at > NOW(6)`，见 F-001。
- **`selfApprovalBlocked` 说明**：发起人访问详情会先 404，因此该字段在本端点没有可达 true
  分支；T13 不应依赖它禁用入口，应按“发起人不在待办/详情 404”处理。这是契约字段语义残留，
  不构成本任务失败。

## 决定（POST /api/v1/workflow-approvals/{id}/decision）

- **请求解析**：`Idempotency-Key` 必填（8–128 可打印 ASCII），请求体用 `JsonNode` 白名单
  `decision/comment`，未知字段/非法枚举/非字符串 comment 均 400 `RICCON-WF-400`
  （`WorkflowApprovalController.java:110-124`、`WorkflowApprovalRequestParser.java:43-80`）。
- **理由校验**：`WorkflowApprovalDecisionCommand` 去首尾空白、空白视为未填、按 Unicode 码点计长；
  REJECT 必填 1–500，APPROVE 可选 ≤500；DB `chk_workflow_approval_comment` 对 REJECTED 再兜底
  （`WorkflowApprovalDecisionCommand.java`、`WorkflowApprovalService.java:350-362`、
  `V15__workflow_studio.sql:249-251`）。测试覆盖 null/空白/501 400、201 通过、500 通过、
  501 拒绝（`WorkflowApprovalApiTest.java:257-354`）。
- **审批人校验**：顺序为自批（`decided_by == run.requester_user_id`）→ 账号 ACTIVE → assignee/
  角色范围；命中即写 `outcome=DENIED` 审计（不带理由）并抛 403 `RICCON-AUTH-403`，审批/运行
  保持等待（`WorkflowApprovalService.java:373-398,419-448`）。测试覆盖 owner 自批、member/
  tenantAdmin 范围外、跨租户/平台/未知 ID 404、3 条 denied 审计（`WorkflowApprovalApiTest.java:355-405`）；
  停用账号的服务层竞态测试断言 403 + denied + 运行等待（`WorkflowApprovalInactiveApproverTest.java:35-84`）。
- **并发/重复**：审批 `PENDING -> APPROVED/REJECTED` 条件更新是唯一守卫；0 行即 409
  `RICCON-WF-409`；重复请求 409 不重放；并发两线程一个 200、一个 409，只写一条 SUCCESS 审计
  （`WorkflowApprovalJdbc.java:178-198`、`WorkflowApprovalService.java:266-283,311-328`、
  `WorkflowApprovalApiTest.java:183-254,430-476`）。
- **批准**：条件更新审批、审批节点 `WAITING_APPROVAL -> SUCCEEDED` 并写
  `{"decision":"APPROVED"}`、运行 `WAITING_APPROVAL -> PENDING`、`current_node_key=targetNodeKey`、
  清 error/租约、累加 `approval_wait_ms`；测试断言 run/node/output/audit
  （`WorkflowApprovalService.java:266-309`、`WorkflowApprovalJdbc.java:200-233`、
  `WorkflowApprovalApiTest.java:183-254`）。
- **拒绝**：审批 `->REJECTED`；审批节点 `FAILED` + `WF_APPROVAL_REJECTED`；运行 `FAILED`、
  `result_status='NONE'`、`result_size_bytes=0`、`current_node_key=审批节点`；下游可达节点
  已有尝试的 `skipPendingNode`、无尝试的直接插入 `SKIPPED`；测试断言审批节点 FAILED、run
  FAILED、下游 end/report/tool SKIPPED、target tool 仅一条 SKIPPED、`result_status=NONE`
  （`WorkflowApprovalService.java:311-348,505-560`、`WorkflowApprovalJdbc.java:235-298`、
  `WorkflowApprovalApiTest.java:257-321`）。
- **编排关闭**：`requireFeatureEnabled` 返回 503 `RICCON-WF-503`；待办/详情仍 200，决定不写审计
  （`WorkflowApprovalService.java:208-217,472-478`、`WorkflowApprovalFeatureDisabledApiTest.java:25-49`）。
- **与 T07a 一致性**：API 与 worker 的 `APPROVE_SQL`/`REJECT_SQL`/`FINISH_APPROVAL_NODE_SQL`/
  `RESUME_RUN_SQL`/`FAIL_RUN_REJECTED_SQL` 规范化空白后一致；`ASSIGNED_SQL` 仅 API 多 `LIMIT 1`；
  自批/ACTIVE/范围顺序、理由规范化、409 语义一致。T09 测试为 HTTP/API 服务测试，未复用 worker
  测试作为 API 证据。
- **F-004**：`RUN_STATE_CONFLICT` 分支存在但无取消/审批竞态 API 测试，见 finding。
- **F-005**：`Idempotency-Key` 不持久化、重复 409 与 openapi/§5.4 一致，但 README 仍写键级
  重放、ADR §7.4 同一 bullet 自相矛盾，见 finding。

## 审计

- **写入动作/字段**：成功与 denied 都写 `workflow.approval.decided`，target `WORKFLOW_RUN`，
  details 严格为 `workflowRunId/workflowApprovalId/nodeId/decision/approverUserId/targetToolName?/
decisionComment?`；denied 不带理由；成功测试断言 details 大小 7、target/actor/字段
  （`WorkflowApprovalAuditEvents.java:42-91`、`WorkflowApprovalApiTest.java:230-252`）。
- **201–500 可写可读**：`AuditDetailsPolicy` 新增动作级 `decisionComment=500`，并把长度判断改为
  Unicode 码点；读取端 `AuditDetailsReader` 复用同一策略。测试覆盖 201/500/501、500 代理对、
  500 写入后 read 回、501 读取 fail-closed
  （`AuditDetailsPolicy.java:491-501,735`、`WorkflowApprovalAuditCommentPolicyTest.java:30-77`、
  `WorkflowApprovalApiTest.java:257-354`）。这关闭了 REV-TASK-0219-001 F-001 与
  REV-TASK-0226-001 F-002 的“201–500 写不进/读不出”。
- **fail-closed**：决定与审计在同一 `@Transactional` 内；`AuditService.append` 加入当前事务，
  审计异常不被吞掉，决定/节点/运行更新整体回滚（`WorkflowApprovalService.java:195-207`、
  `AuditService.java:35-38`）。denied 路径用 `noRollbackFor=WorkflowApprovalForbiddenException`
  提交 denied 审计。未做 API 级审计仓储故障注入，见“未验证”。
- **内容最小化**：details 不含工具参数、密文、提示词、凭据、文档正文；响应/审计测试断言无
  ciphertext/prompt。拒绝理由进入审批记录与审计，符合 AC-16，但 U-05 PII 风险仍开放（F-002）。
- **读取白名单**：T01 的 `AuditDetailsPolicy` 动作白名单包含该动作；API 查询/导出通过
  `AuditDetailsReader` 读取，201–500 可读。未通过 `/audit-events` 端点做 201–500 端到端查询，
  见“未验证”。
- **全局长度语义**：动作级 500 覆盖是局部调整；默认 200 上限同时从 UTF-16 `length()` 改为
  Unicode 码点计数，是全局语义变化（BMP 文本不变，非 BMP 最多 200 码点，与 ADR-0020“单值
  ≤200 字符”一致）。JSON details 非业务正文，未发现超长绕过；既有 `AuditDetailsPolicyTest`
  12/12 在本任务 EV 中通过，模块 review 可保留观察。
- **U-05**：仍为 M06 gate 前安全项；T09 只实现临时默认，不声称已接受（F-002）。

## 契约

- **assignee-only 漂移**：T09 结束提交 `6b2a35a` 的 `getWorkflowApproval` 描述写“分配给当前用户
  或租户管理员”，与 ADR §5.3 和 T09 代码不一致；T09 按 ADR 实现。后续 contract-only 提交
  `11793f51` 已把描述改为 assignee-only（当前 `contracts/openapi.yaml:3126`），T09 代码无需改动。
  记录为 F-006（fixed）。
- **决定描述**：当前 openapi 明确“仅 assignee 可决定；非 assignee 按范围外 403；重复/并发 409；
  拒绝理由必填”，与代码/测试一致（`contracts/openapi.yaml:3148-3157`）。
- **幂等文案漂移**：README/ADR §7.4 与 openapi/§5.4/实现不一致，见 F-005。
- **T09 未改契约**：T09 四个提交无 `contracts/**` 改动；`WorkflowContractM06Test` 6/6 通过，
  覆盖三个审批 operationId、`WorkflowApproval`/`WorkflowApprovalDetail` schema、
  `x-riccon-permission`、拒绝理由条件必填。
- **Javadoc 对齐**：`ConnectorContractM05bTest`、`KnowledgeContractM05Test`、
  `ModelContract108aTest`、`ModelContract108bTest` 类注释与断言均为 `1.6.0-m06`，关闭 T01 F-002。

## AC 对账（task 级）

| AC        | 原文用户可见结果（摘要）                                                                                                                                                                                         | T09 支撑                                                                                 | 判定                                                                                                                                 |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| M06-AC-05 | 待办只显示分配给自己的 PENDING、`created_at ASC`、每项流程/节点/发起人/等待/范围摘要；详情动作/工具/范围/脱敏参数；批准继续产出；拒绝终止/下游跳过/无结果/记录审批人/时间/决定/理由；无 ACL 不显示正文/片段/定位 | 待办/详情/批准/拒绝 API、数据范围、脱敏预览、理由、审计均通过真实 MySQL/HTTP 测试        | **partial（API 通过）**：UI 待办/详情/空态/等待时长/入口与“批准后产出结果、拒绝后界面显示”归 T13/T16a，模块 gate 绑定后才能判完整 AC |
| M06-AC-06 | 自批入口禁用/直接调用 403+拒绝审计、运行等待；范围外/停用/他租户不能审批且不泄露；无审批人等待并提示配置；发布/运行前提示；平台不代批                                                                            | 自批/范围/停用 403+denied、跨租户/平台 404、无审批人保持等待均有 API/服务测试            | **partial（API 通过）**：发布/运行前提示与配置入口归 T03/T04/T13，E2E 归 T16a                                                        |
| M06-AC-15 | 角色矩阵、审批人必须在节点范围且非发起人、管理员/他租户/平台 404、403 与 404 语义、部门/租户范围、取消/停用/移交等                                                                                               | 审批子集：assignee-only 待办/详情、自批/范围/停用、平台/跨租户 404、管理员 404           | **partial（审批子集通过）**：创建/编辑/发布/运行/取消/停用/授权/移交的矩阵归 T05/T08/T10/T11/T12/T16a                                |
| M06-AC-18 | 未授权/停用工具不调用；写工具必须审批；审批摘要 ACL 最小可见；提示注入不改变流程                                                                                                                                 | 审批摘要只返回脱敏 preview/范围计数，不返回 ciphertext/prompt/正文；ACL 由 M05/T16a 证明 | **partial（摘要最小化通过）**：工具授权/重审批归 T07b，ACL 遮蔽与注入 E2E 归 T16a                                                    |

结论：TASK-0229 的任务验收在 API/领域层成立；AC-05/06/15/18 仍有 UI、E2E、最终结果产出与
完整权限矩阵的模块级残余，不能把本任务测试当作完整 AC pass。

## 证据复核与独立重跑

- **EV-1082**：`manual=false`、commit=`6b2a35a66b77d33cdfd7d6f64ca27b80043258c3`、`dirty=false`、
  exit=0、输出 69,388 bytes、SHA-256
  `4b48a39579fdaaf7610c7cdc1e108c68e7cd825759c1a3b49fc1228f7e3e626d`，与 JSON 及文件哈希一致。
  命令为
  `./mvnw -pl apps/api test -Dtest='WorkflowApproval*Test,AuditDetailsPolicyTest,AuditDetailsReaderTest,WorkflowContractM06Test,ConnectorContractM05bTest,KnowledgeContractM05Test,ModelContract108aTest,ModelContract108bTest,WorkflowRunLifecycleApiTest' -Dsurefire.failIfNoSpecifiedTests=false`；
  日志汇总 **Tests run: 67, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**。T09 测试类
  Javadoc 与 `@DisplayName` 标注 `IT-WF-108`/`SEC-WF-001`，实际断言覆盖待办、详情、批准/拒绝、
  自批/范围/停用、跨租户/平台、并发与审计回读，不是 worker 测试复用。
- **独立复跑**：HEAD=`b8ff470f6130245848be678929cd8a179dc71bd8`，同一命令，**67/0/0/0，
  BUILD SUCCESS**（01:19 min）。`git diff 6b2a35a..HEAD -- apps/api platform` 为空；HEAD 之后
  只有 T07b worker 提交、`.riccon` 记录与 `11793f51` 的 contracts 文档修改，不影响 T09 API 代码。
- **格式**：`./mvnw -q -pl apps/api spotless:check` exit 0。
- **EV-1080**：commit `cdd525aa`、`dirty=true`，已被 EV-1082 取代，未作为本任务通过证据。
- **未运行**：M06 模块全量 build/test/coverage、真实浏览器 E2E/axe、k6/perf、Trivy/semgrep、
  ArchUnit、迁移回滚。T09 无 UI、无迁移、无模块边界改动；这些属模块 gate/T16a/T16b 或 release
  DoD，不作为本任务失败。

## 未验证 / 残余风险

1. **保留期（F-001）**：审批端点未应用 `retention_expires_at`；T08 的 write_scope 不含 approval 包，
   需 Lead/Architect 明确覆盖方式。
2. **U-05（F-002）**：完整理由进审计的 PII 风险未解决；M06 gate 前安全评审/Product 必须二选一。
3. **待办 N+1（F-003）**：size 100 时逐项引用查询；模块 perf/50 并发必须复测或批量化。
4. **取消/审批竞态（F-004）**：`RUN_STATE_CONFLICT` 无 API 测试；T16a 需覆盖取消后决定 409。
5. **审计故障注入**：未有 API 级审计仓储失败用例；代码事务语义为 fail-closed，但未做故障注入证明。
6. **审计查询/导出端点**：201–500 只在 `AuditDetailsReader` 层验证，未通过 `/audit-events` 查询/
   导出端点做端到端回读；读取策略是共享组件，风险低，M06 gate/IT-AUDIT-M06-01 应补。
7. **Worker → API 端到端**：T09 测试用直接 SQL 构造“停在审批节点”的审批，不是 T07a worker 创建
   后由 API 读取/决定；T16a 的 E2E 必须覆盖真实链路。
8. **UI/E2E/a11y**：待办空态/等待时长/按钮禁用/键盘/375px 未在真实浏览器验证（T13/T16a）。
9. **完整权限矩阵**：AC-15 的创建/编辑/发布/运行/取消/停用/授权/移交不由 T09 证明。
10. **决定端点无专用限流**：denied 审计按请求写入且无端点级限流，依赖全局防护；T16a 可评估
    审计量/滥用风险，不构成本任务失败。

## 是否可 done

可进入 done（**pass**）。TASK-0229 的 API 验收、write_scope、证据与高风险独立评审条件成立；
无 open P0/P1；5 个 accepted P2/P3 均有 owner/due/accepted_reason，1 个 P3 已 fixed。P2 的
保留期与 U-05 必须在 M06 gate 前闭环；AC-05/06/15/18 的 UI/E2E 由 T13/T16a 与模块 review 绑定。
本评审不修改任务状态、不运行 `gate check --module M06`，也不替代模块 review、Product 预验收
或用户最终验收。
