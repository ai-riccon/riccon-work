---
review_id: REV-M06-ARCH-001
module_id: M06
task_id: TASK-0217
reviewer:
  agent: riccon/verifier
  session: ses_df17bdd58ffeMFJ17CfD4mqM1a
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - "751d384"
  - "69bf886"
  - "01b5c57"
  - "a694979"
head_commit: "90ff68e5186708acffad52ead61b224e21c9d82b"
findings:
  - id: F-001
    severity: P1
    status: open
    title: "契约 WorkflowEdge.branch 的 enum 被 YAML 解析为布尔值，条件边 schema 不可满足"
    summary: >-
      `contracts/openapi.yaml:8219-8222` 将 `branch` 声明为 `type: [string, "null"]`，但
      `enum: [TRUE, FALSE, null]` 未加引号；Ruby/Psych 解析结果为 `[true, false, nil]`。因此客户端
      无法提交字符串 `"TRUE"`/`"FALSE"`，与 ADR-0024 §4.1/AC-09 的 `branch=TRUE/FALSE` 冲突，条件分支
      在契约测试和真实客户端中不可用。期望改为 `["TRUE", "FALSE", null]` 或等价字符串枚举，并补契约测试。
    owner: "riccon/architect"
    due: "T-M06-01 实现前"
    evidence: "contracts/openapi.yaml:8219-8222；ruby YAML 解析输出 `[true,false,nil]`"
  - id: F-002
    severity: P1
    status: open
    title: "审批节点 config 缺少 actionKind/targetNodeKey，审批动作无法在契约中表达"
    summary: >-
      `WorkflowNodeConfig` 关闭了 `additionalProperties`，但未定义 `actionKind` 和 `targetNodeKey`
      （`contracts/openapi.yaml:8126-8181`）；ADR-0024 §4.4 明确审批节点配置包含
      `assigneeType`、`assigneeRole`/`assigneeUserIds`、`actionKind`、`targetNodeKey`，§3.3.5 的
      `workflow_approval` 也持久化 `action_kind`/`target_node_key`。按当前契约，包含审批节点的流程
      无法通过 schema 校验，AC-05/06/08/18 的核心审批绑定不可实现。需补字段，或明确改由服务端从后继节点
      推导并同步修订 ADR；两者都必须有契约测试。
    owner: "riccon/architect"
    due: "T-M06-01/T-M06-03 实现前"
    evidence: "contracts/openapi.yaml:8126-8181；ADR-0024:327,215-225"
  - id: F-003
    severity: P1
    status: open
    title: "AC-16 包含模板共享审计，但 M06 不实现共享，M06 gate 无法完整验收"
    summary: >-
      `docs/PRODUCT.md:833` 的 M06-AC-16 是 M06 P0，要求“共享与权限授予/撤销/移交各发生一次”并
      有审计；但 ADR-0024 §8.1 明确 `workflow.template.saved/shared/unshared` 仅在 M06b 白名单中新增，
      M06 不写共享动作（ADR-0024:564），AC-11/25 也已归 M06b。结果是 M06 gate 的 AC-16 无法完整通过。
      期望将 AC-16 的“共享”子句明确移到 M06b，或为 M06 定义可验收的共享前身动作并在 AC 中写明。
    owner: "riccon/product"
    due: "M06 gate 前"
    evidence: "docs/PRODUCT.md:833；ADR-0024:564,822；contracts/README.md:150"
  - id: F-004
    severity: P1
    status: open
    title: "AC-05 审批待办/详情的“数据范围摘要”在契约中无字段"
    summary: >-
      `WorkflowApproval` 只有 `workflowName/nodeId/nodeName/requestedBy/createdAt/actionKind/
      targetToolName/paramsPreview` 等字段（`contracts/openapi.yaml:8533-8616`），没有 `runScope`、
      部门或 `dataScopeSummary`。AC-05 要求待办每项显示“数据范围摘要”、详情显示“数据范围”，
      审批人无法从 API 得到该信息；`paramsPreview` 是脱敏参数，不能替代数据范围。期望增加
      `runScope/departmentId` 或服务端计算的 `dataScopeSummary`，并由 E2E-M06-04 断言。
    owner: "riccon/architect"
    due: "T-M06-01/T-M06-09 实现前"
    evidence: "contracts/openapi.yaml:8533-8616；docs/PRODUCT.md:822；ADR-0024:422"
  - id: F-005
    severity: P1
    status: open
    title: "平台管理员统一 404 只有目标、没有实现机制；当前路由权限会返回 403"
    summary: >-
      所有 M06 路径声明 `workflow.read/edit/...` 权限，平台管理员按 ADR-0024 §6.1 不持有任何
      `workflow.*`；现有 `PermissionInterceptor` 在 controller 前调用 `AuthService.requirePermission`，
      无权限直接 `ForbiddenException`（403）。ADR-0024 §6.1 却要求 `tenantId=platform` 对
      `/workflows/**`、`/workflow-runs/**`、`/workflow-approvals/**`、`/workflow-templates/**` 统一 404。
      设计没有说明如何把 403 转成 404（M05 的 `KnowledgeController.actor()` 是先过服务层再 404 的窄例外）。
      期望明确平台租户 404 的过滤器/服务层路径，并由 TEN-M06-01 断言 404 而非 403。
    owner: "riccon/architect"
    due: "T-M06-01/T-M06-05 实现前"
    evidence: "contracts/openapi.yaml M06 路径 x-riccon-permission；PermissionInterceptor.java:54-69；AuthService.java:362-365；KnowledgeController.java:281-294；ADR-0024:452"
  - id: F-006
    severity: P1
    status: open
    title: "McpCallExecutor 共享策略未定义：J2 只允许 READ，M06 AC-18 要求 WRITE 工具"
    summary: >-
      现有 `McpGateway.validatePolicy` 明确拒绝 `riskLevel != READ`（`McpGateway.java:178-192`），
      并要求 approvalId；M02 的 J2 路径因此只允许只读工具。ADR-0024 §4.7 要求抽取共享
      `McpCallExecutor`，但未说明 J2 如何继续只允许 READ、工作流如何在审批后允许 WRITE。若共享实现
      直接复用现有策略，AC-18 的写工具重审批无法通过；若放宽策略，J2 可能被写工具穿透，属于 T4 安全回归。
      期望在 ADR 中定义执行器策略参数/模式，并同时用 IT-HITL-001/MCP-001..008 与 SEC-WF-004 验证。
    owner: "riccon/architect"
    due: "T-M06-07 实现前"
    evidence: "apps/worker/.../McpGateway.java:95-106,178-192；ADR-0024:360-362；docs/PRODUCT.md:835"
  - id: F-007
    severity: P1
    status: open
    title: "任务 write_scope 只列生产代码，未列 src/test，IT-WF-* 证据没有可用写范围"
    summary: >-
      ADR-0024 §13.1 的 T-M06-02..09、T15 等 write_scope 只包含 `src/main/java`、页面或
      `schema`/`application.yml`，没有对应 `src/test/java`；但证据要求 `IT-WF-101..112`、部分
      `SEC-WF-*` 是集成测试。按 Riccon 的 write_scope 规则，builder 不能写范围外测试，结果是这些
      AC 证据无法产出。T16 只覆盖 `e2e/**`、`scripts/**`、`e2e/perf/**`，也不能承接 Java 集成测试。
      期望在创建任务时给每个后端任务补 `apps/api/src/test/.../workflow/**` 或
      `apps/worker/src/test/.../workflow/**`，并保持互斥；或明确把哪些 IT 用例交给哪个任务。
    owner: "riccon/lead"
    due: "M06 实现任务创建前"
    evidence: "ADR-0024:728-744；TASK-0217 write_scope 规则"
  - id: F-008
    severity: P2
    status: open
    title: "AC-01 的“另一编辑者”与草稿 ACL 不一致（U-03）"
    summary: >-
      ADR-0024 §6.2 规定草稿仅所有者与 TENANT_ADMIN 可读/改，其他 `WORKFLOW_EDITOR` 不可见（404）；
      AC-01 写“同一草稿被另一编辑者并发修改”。设计用“所有者 + 租户管理员”覆盖冲突，未证明同侪
      编辑者场景。若 Product 不要求同侪协同，应把 AC-01 的 Given 改为所有者/管理员并记录 U-03；若要求，
      需新增草稿共享模型。当前不能把 E2E-M06-01 当作同侪并发证据。
    owner: "riccon/product"
    due: "M06 gate 前"
    evidence: "docs/PRODUCT.md:818；ADR-0024:456-459,861"
  - id: F-009
    severity: P2
    status: open
    title: "AC-06 无审批人阻断的 blockedReason 没有持久化/契约字段"
    summary: >-
      ADR-0024 §5.2 说无审批人时运行保持 `WAITING_APPROVAL`、`blockedReason=NO_APPROVER`；但
      `workflow_run` 表定义没有 `blocked_reason`，`WorkflowRun` 契约也只有 `errorCode/errorMessage`，
      没有 blockedReason。实现若用 `errorCode=WF_NO_APPROVER` 可以替代，但 ADR 未说明；AC-06 要求
      发起人与租户管理员看到配置提示。期望明确用 `errorCode` 或新增字段，并让 SEC-WF-001 断言。
    owner: "riccon/architect"
    due: "T-M06-09 实现前"
    evidence: "ADR-0024:415,165-178；contracts/openapi.yaml:8417-8423；docs/PRODUCT.md:823"
  - id: F-010
    severity: P2
    status: open
    title: "轮询契约引用 updatedAt，但 WorkflowRun 没有 updatedAt"
    summary: >-
      ADR-0024 §7.4 与 `contracts/README.md:149` 要求运行详情/历史按 `updatedAt` 变化轮询；
      `WorkflowRun`（`contracts/openapi.yaml:8377-8440`）和 `WorkflowRunDetail` 都没有 `updatedAt`。
      客户端无法实现契约所说的轮询判据，只能比较状态/节点数组，增加重复重绘或漏更新风险。
      期望补 `updatedAt` 或改为明确的状态/版本字段。
    owner: "riccon/architect"
    due: "T-M06-01 契约定稿前"
    evidence: "ADR-0024:525；contracts/README.md:149；contracts/openapi.yaml:8377-8440"
  - id: F-011
    severity: P2
    status: open
    title: "AC-13 要求节点输入/输出摘要，WorkflowNodeRun 只有输出摘要"
    summary: >-
      `WorkflowNodeRun` 提供 `outputSummary`、`errorCode`、时间线，但没有节点输入摘要字段
      （`contracts/openapi.yaml:8441-8532`）；AC-13 要求“每个节点的输入/输出摘要”。可由上游输出摘要
      与运行输入组合展示，但 ADR 未定义该展示口径。期望补 `inputSummary` 或在 ADR/UI 中明确组合规则，
      并由 IT-WF-106/E2E-M06-08 断言。
    owner: "riccon/architect"
    due: "T-M06-08/T-M06-12 实现前"
    evidence: "docs/PRODUCT.md:830；contracts/openapi.yaml:8441-8532；ADR-0024:193-194"
  - id: F-012
    severity: P2
    status: open
    title: "WorkflowRun.approvalStatus 单值无法表达多个审批节点"
    summary: >-
      `WorkflowRun` 只有一个 `approvalStatus`（`contracts/openapi.yaml:8413-8416`），而串行流程可有多个
      审批节点（每次运行多个 `workflow_approval`）。AC-12 运行历史要求显示“审批状态”，单值会丢掉已批/待批
      的组合语义。期望定义聚合规则（如最近/待决优先）或返回审批状态列表/计数，并由 SEC-WF-003 断言。
    owner: "riccon/architect"
    due: "T-M06-08 实现前"
    evidence: "docs/PRODUCT.md:829；contracts/openapi.yaml:8413-8416；ADR-0024:429-433"
  - id: F-013
    severity: P2
    status: open
    title: "U-05：审批理由进入审计的 PII 口径未闭环"
    summary: >-
      AC-16 要求审批含理由，ADR-0024 §8.3 默认把 ≤500 字符 `decisionComment` 写入审计并提示用户；
      PII/合规结论尚未由安全评审确认。若安全评审不接受，需改为审计只存 `commentSha256`、审批记录保留
      理由，并回改 AC-16 文案。当前不应把 U-05 当作已解决；M06 gate 前必须有明确结论和测试。
    owner: "riccon/architect"
    due: "M06 gate 前"
    evidence: "ADR-0024:575,863；docs/PRODUCT.md:833"
  - id: F-014
    severity: P2
    status: open
    title: "U-13：跨租户领取例外缺少可执行的 claim 证据设计"
    summary: >-
      ADR-0024 §4.3/TM-85 把 `workflow_run` 领取登记为 ADR-0009 新例外，但只写了“只读
      id/tenant_id/status/lease_until、领取后打开租户上下文”。设计没有给出 claim SQL 的边界
      （raw JDBC 不得读业务列）、租户 ID 非法时的 fail-closed、以及跨租户负向测试的断言。
      这类例外是 T4 隔离边界，必须有独立安全评审和 IT-WF-103/TEN-M06-01 证据，不能只靠代码评审。
    owner: "riccon/architect"
    due: "T-M06-06 实现前 / M06 gate 前"
    evidence: "ADR-0024:311-314,602；THREAT-MODEL.md:637；docs/PRODUCT.md:832"
  - id: F-015
    severity: P2
    status: open
    title: "任务规模与 120h 预算风险：T06/T07/T12/T16 过大"
    summary: >-
      T-M06-06 14h 覆盖运行队列、租约心跳、串行调度、5 类节点、超时/取消/结果；T-M06-07 10h 覆盖
      审批、参数加密、工具调用、plan、重审批、未知副作用；T-M06-12 10h 覆盖发布/运行详情/历史/重试/取消；
      T16 8h 覆盖 E2E/axe/perf/越权/注入。关键路径约 73h，120h 只剩 20% 缓冲，且高风险集中在少数任务。
      建议在创建任务前拆 T06（队列/引擎 vs 节点）、T07（审批 vs 工具）和 T16（perf vs 安全/E2E），
      或由 Lead 书面接受超时回到用户的触发条件。
    owner: "riccon/lead"
    due: "M06 实现任务创建前"
    evidence: "ADR-0024:728-749；DEC-0046/0048 预算"
  - id: F-017
    severity: P3
    status: open
    title: "审批拒绝理由的“拒绝时必填”未在请求 schema 表达"
    summary: >-
      `WorkflowApprovalDecisionRequest` 只有 `comment: maxLength 500`（`contracts/openapi.yaml:8842` 起），
      无法表达 `decision=REJECT` 时必填；AC-05 要求拒绝理由必填。服务层校验可以覆盖，但契约测试和生成客户端
      看不到约束。建议在契约描述/测试中明确，并由 SEC-WF-001 断言 400。
    owner: "riccon/architect"
    due: "T-M06-01 契约定稿前"
    evidence: "contracts/openapi.yaml:8842；docs/PRODUCT.md:822"
  - id: F-018
    severity: P3
    status: open
    title: "内置模板的 visibility 语义不清"
    summary: >-
      `WorkflowTemplate` 要求 `source` 与 `visibility`（`PRIVATE/TENANT`），但 M06 只返回全局内置模板；
      内置模板并非用户模板，`PRIVATE` 会误导 UI，`TENANT` 又像用户共享。期望为内置模板定义明确取值
      （或允许 `visibility=null`/新增 `BUILT_IN` 语义），避免 AC-10/AC-25 文案错误。
    owner: "riccon/architect"
    due: "T-M06-01/T-M06-14 实现前"
    evidence: "contracts/openapi.yaml:8018-8021,8627-8660；ADR-0024:245-249"
  - id: F-019
    severity: P3
    status: open
    title: "威胁模型 R-06-1 仍暗示 M06 执行代码"
    summary: >-
      `docs/security/THREAT-MODEL.md:178` 的 R-06-1 写“M02 不执行用户代码；M06 起记录 runId 与命令摘要”，
      但 M06 明确不开放代码执行。该残留会让安全评审误以为 M06 有沙箱执行面；应改为“M06 仍不执行用户代码；
      代码执行开放前另立模块并重开 TB-06/E-06-1/R-06-1”。
    owner: "riccon/architect"
    due: "M06 gate 前"
    evidence: "docs/security/THREAT-MODEL.md:177-178,677"
  - id: F-020
    severity: P3
    status: open
    title: "高风险任务清单与任务表不一致（T10）"
    summary: >-
      ADR-0024 §13.1 的“高风险任务”列表为 T01–T09、T12、T16、T17，但任务表中 T-M06-10 风险为“高”，
      §15.5 又把 T10–T13 列入 Verifier 评审。建议统一为 T01–T13、T16、T17（或明确 T10 为何不需独立评审），
      避免高风险前端任务在 closeout 时漏评审。
    owner: "riccon/architect"
    due: "M06 实现任务创建前"
    evidence: "ADR-0024:737,748,849"
evidence: []
created_at: "2026-11-25T01:22:00+08:00"
---

# REV-M06-ARCH-001：M06 架构与 AC 可验证性独立评审

## 结论

**verdict: fail**。ADR-0024、威胁模型、契约和任务拆分总体方向正确，V15 的聚合拆分、`workflow_run`
独立运行队列、审批与 J2 隔离、审计 fail-closed、TM-85 例外登记都成立；但在 M06 实现任务创建前仍有
7 个 P1 需要修复或书面处理，主要集中在契约 schema、审批可观察字段、平台管理员 404、共享工具执行器
和任务 write_scope。AC-23/AC-17 已由 DEC-0047=A/DEC-0048=A 解决，不再是 P1 open；ADR-0024/ARCHITECTURE
已在 `a694979` 同步上述口径，`docs/PRODUCT.md` 已在 `01b5c57` 同步 AC-23/OQ-M06-09。

本次评审只读：未修改生产源码、测试、迁移、`docs/PRODUCT.md`、`AGENTS.md`、ADR、威胁模型或契约。
评审对象为设计基线 `751d384`；评审期间用户答复 DEC-0047/0048（`69bf886`）并由 Architect 同步
（`01b5c57`、`a694979`），本文按当前设计文本评估。

`REV-M06-OPS-001` 是 TASK-0218 的 Ops 独立评审；本评审的 F-007/F-010/F-014/F-015 与
OPS-M06-004/010/015/013 有部分重叠（测试写范围、轮询 updatedAt、跨租户领取、预算/任务大小），
但本评审是从 AC/契约/任务拆分角度独立记录。Lead 应合并处理，不要为同一问题开两套互不一致的修法。

本文的文件行号以设计基线 `751d384` 为准；`a694979` 只同步 U-01/U-02 段，ADR 行号有少量偏移，
定位时以章节号（如 §4.4/§8.1/§15.3）为准。

评审期间 Architect 正在修改 ADR 工作区（未提交，涉及队列参数、updatedAt、TM-85 等）；本评审以
`90ff68e` 的已提交文本为准，不把工作区未提交改动当作已修复证据。若 Architect 已完成这些修改，
请在 `REV-M06-ARCH-002` 中按新提交重新核对对应 finding。

## 验证范围与命令

| 检查                         | 命令/方法                                                                                        | 观察结果                                                                                                                                                                                                                                                     |
| ---------------------------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 设计基线/提交                | `git log -3 --oneline`                                                                           | `751d384` 设计草案；`e414d5b`/`69bf886` 为 DEC-0047/0048 决策提交                                                                                                                                                                                            |
| 契约语义对比                 | `git show b2ba0dd:contracts/openapi.yaml > /tmp/openapi-m05b.yaml`；Ruby `YAML.load_file` 深比较 | paths 96→117（+21，0 删除）；schemas 183→231（+48，0 删除）；既有 path 0 改动；既有 schema 仅 `AuditTargetType` 追加 3 个枚举；components.parameters +4；securitySchemes/headers/responses 不变                                                              |
| $ref 完整性                  | Ruby 遍历全部 `$ref`                                                                             | 1298 个引用，0 缺失                                                                                                                                                                                                                                          |
| M06 operation 权限/CSRF/幂等 | Ruby 遍历 M06 paths                                                                              | 21 个 path 均有 `x-riccon-permission`；写操作均有 `CsrfToken`；发布/创建运行/重试/审批决定 `Idempotency-Key` required；创建/复制/取消等 optional                                                                                                             |
| `branch` 枚举                | Ruby `YAML.load_file` 读取 `WorkflowEdge.properties.branch.enum`                                 | `[true, false, nil]`，与 `type: [string,"null"]` 冲突（F-001）                                                                                                                                                                                               |
| 审批 config                  | 阅读 `WorkflowNodeConfig` 全文                                                                   | 无 `actionKind`/`targetNodeKey`，且 `additionalProperties:false`（F-002）                                                                                                                                                                                    |
| M06 审批字段                 | 阅读 `WorkflowApproval`/`WorkflowApprovalDetail`                                                 | 无 runScope/department/dataScope 字段（F-004）                                                                                                                                                                                                               |
| 运行轮询字段                 | 阅读 `WorkflowRun`/README/ADR                                                                    | 契约无 `updatedAt`（F-010）                                                                                                                                                                                                                                  |
| 现有代码前提                 | codegraph + 只读 `grep`/`sed`                                                                    | `approval.research_task_id NOT NULL`、`mcp_invocation.run_id NOT NULL`、`agent_run.kind` 仅 4 类、`model_invocation.run_id` 可空、`agent_run_outbox.run_id NOT NULL`、worker expected schema 14、`RoleDefinitions` 仅 TENANT_ADMIN/MEMBER 等与 ADR §1.1 一致 |
| 工具策略                     | 阅读 `McpGateway.validatePolicy`                                                                 | 当前拒绝 `riskLevel != READ`（F-006）                                                                                                                                                                                                                        |
| 平台管理员 403               | 阅读 `PermissionInterceptor`/`AuthService`                                                       | 无权限抛 `ForbiddenException`（403）（F-005）                                                                                                                                                                                                                |
| 审计 fail-closed             | 阅读 `AuditDetailsPolicy`/`AuditDetailsReader`                                                   | 未知动作的非空 details 读取失败；旧 API 回滚约束成立（U-01 依据）                                                                                                                                                                                            |
| 配额                         | 阅读 `QuotaAdmissionService.admit`                                                               | 租户/用户 × 日/月各 +1，按运行 1 单位，与 DEC-0048=A 一致                                                                                                                                                                                                    |

未运行：全量 Maven 测试、真实浏览器 E2E、迁移实跑、OpenAPI 运行时 validator。原因是本任务只做设计阶段
只读评审；这些由后续 T16/T17 和模块 gate 在实现提交上执行。`branch` 的 schema 问题已用 YAML 解析证明，
不需要运行测试即可确认。

## AC 逐条结论

| AC        | 归属/优先级          | 可观察结果与测试线索                                                                             | 结论                                                                                                                           |
| --------- | -------------------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| M06-AC-01 | M06/P0               | 草稿保存/重开/未发布不可运行；`E2E-M06-01`                                                       | **pass (with residual)**：冲突检测可做，但“另一编辑者”仅由所有者+TENANT_ADMIN 覆盖；同侪编辑者 ACL 是 U-03（F-008）            |
| M06-AC-02 | M06/P0               | 非法图逐项校验，画布/表单一致；`E2E-M06-01`、`IT-WF-101`                                         | **pass**：T03 校验器和 T10/T11 等价视图有落点；需确保 `IT-WF-101` 的测试文件写范围（F-007）                                    |
| M06-AC-03 | M06/P0               | 发布不可变、运行绑定版本、旧版本/范围；`E2E-M06-02`、`IT-WF-102`                                 | **pass**：触发器 + `graph_sha256` + 版本快照设计成立                                                                           |
| M06-AC-04 | M06/P0               | 节点时间线、刷新一致、无敏感内容；`E2E-M06-03`                                                   | **pass (with residual)**：轮询判据 `updatedAt` 缺失（F-010）；E2E 可手工刷新验证一致                                           |
| M06-AC-05 | M06/P0               | 待办仅本人、排序、数据范围、批准/拒绝、ACL 遮蔽；`E2E-M06-04`、`SEC-WF-001`                      | **fail**：审批项/详情没有数据范围字段（F-004），Then 子句不可完整实现                                                          |
| M06-AC-06 | M06/P0               | 禁自批 403+审计、审批人范围、无审批人阻断；`SEC-WF-001`、`E2E-M06-04`                            | **pass (with residual)**：`blockedReason` 未建模，需用 `errorCode=WF_NO_APPROVER` 或补字段（F-009）                            |
| M06-AC-07 | M06/P0               | A 成功/B 失败/C 跳过、重试只从 B；`E2E-M06-05`、`IT-WF-103`                                      | **pass**：节点 attempt + 条件状态更新设计成立                                                                                  |
| M06-AC-08 | M06/P0               | 重复点击只一个运行、写工具重审批、未知副作用 fail-closed；`E2E-M06-05`、`IT-WF-104`              | **pass (with residual)**：重试幂等本身成立；写工具路径受 F-006 执行器策略影响                                                  |
| M06-AC-09 | M06/P0               | 条件只走 TRUE/FALSE，无法求值即失败；`E2E-M06-06`、`IT-WF-105`                                   | **fail**：契约 `branch` 枚举不可满足（F-001），条件边无法通过 schema                                                           |
| M06-AC-10 | M06/P0               | 1 个内置模板、复制不改源、结果；`E2E-M06-07`                                                     | **pass**：模板种子/复制/只读字段有落点；内置模板 visibility 语义见 F-018（P3）                                                 |
| M06-AC-11 | M06b/P0（M06b gate） | 用户模板共享、私有 404；`E2E-M06-11`、`SEC-WF-002`                                               | **not in M06 gate**：ADR §15.3、Product §12.6 一致；M06 仅 deferred 字段                                                       |
| M06-AC-12 | M06/P0               | 历史角色可见性、过滤、404；`E2E-M06-08`、`SEC-WF-003`                                            | **pass (with residual)**：单一 `approvalStatus` 对多审批节点语义需定义（F-012）                                                |
| M06-AC-13 | M06/P0               | 详情/时间线/引用 ACL/sourceStatus/无敏感内容；`E2E-M06-08`、`IT-WF-106`                          | **pass (with residual)**：节点输入摘要无独立字段（F-011）；可由上游输出摘要/输入组合展示                                       |
| M06-AC-14 | M06/P0               | 180 天/租户策略、到期不可见、无删除；`IT-WF-107`                                                 | **pass**：`retention_expires_at` + `retentionDays` + 404 设计成立；物理清理归 M09                                              |
| M06-AC-15 | M06/P0               | 角色/范围/403/404/平台管理员 404；`SEC-WF-002/003/006`、`TEN-M06-01`、`DEPT-M06-01`、`IT-WF-112` | **fail**：平台管理员 404 机制未落地，路由权限会先返回 403（F-005）                                                             |
| M06-AC-16 | M06/P0               | 发布/运行/取消/审批/停用/共享/授权/移交审计；`IT-WF-108/112`、`IT-AUDIT-M06-01`                  | **fail**：M06 不实现模板共享，AC-16 的“共享”子句无法在 M06 gate 验收（F-003）                                                  |
| M06-AC-17 | M06/P0               | 节点/步数/超时/并发/结果大小，限制只读；`IT-WF-109`、`E2E-M06-09`                                | **pass (with residual)**：DEC-0048=A 明确按运行 1 单位；R-36 接受；ADR/ARCH/PRODUCT 已同步（a694979/01b5c57）                  |
| M06-AC-18 | M06/P0               | 工具授权/写工具审批/注入/canary/审批摘要 ACL；`SEC-WF-004`、`E2E-M06-04`                         | **fail**：共享 `McpCallExecutor` 的 READ/WRITE 策略未定义，写工具路径不可安全验收（F-006）                                     |
| M06-AC-19 | M06/P1（gate 必查）  | 五界面加载/空/错/无权限/禁用/成功，375px；`E2E-M06-09`、`@a11y`                                  | **pass**：T10-T13/T16 + §11 有落点；空态固定文案在 Product 草案中                                                              |
| M06-AC-20 | M06/P1（gate 必查）  | 键盘全流程、画布等价列表/表单、axe；`E2E-M06-09`、`@a11y`                                        | **pass**：列表/表单为主路径、画布增强设计可测                                                                                  |
| M06-AC-21 | M06/P0               | 50 并发混合、首字/Studio p95、无重复/错配；`PERF-M06-001`                                        | **pass**：§10.2 记录假模型、并发、限制、脚本参数；需 T16 实现 `RICCON_PERF_SCENARIO=workflow`                                  |
| M06-AC-22 | M06/P0               | J1/J2/M03/M10/M04/M05/M25 回归、端点真实实现；`E2E-M06-10`、`CONTRACT-M06`                       | **pass**：契约语义对比显示既有 path 未改；T01 契约测试 + T16 回归有落点                                                        |
| M06-AC-23 | M06/P0               | 关开关、取消、受支持回滚；`IT-WF-110`、`OPS-M06-01`、THREAT §7                                   | **pass (with residual)**：DEC-0047=A 已解决 AC-23 原文冲突；ADR/ARCH/PRODUCT 已同步；回滚演练证据待 T17（与 OPS-M06-001 重叠） |
| M06-AC-24 | M06/P0               | 无代码/脚本/HTTP/并行/循环/定时入口、平台管理员 404、文案；`SEC-WF-005`、`IT-WF-111`             | **pass (with residual)**：负向节点/枚举设计成立；平台管理员 404 与 AC-15 共用 F-005                                            |
| M06-AC-25 | M06b/P0（M06b gate） | 模板库/2 个内置/升级提示；`E2E-M06-12`、`SEC-WF-002`、`@a11y`                                    | **not in M06 gate**：ADR §15.3、Product §12.6 一致                                                                             |

**AC 统计**：M06 gate 应判 23 条（01..10、12..24）；其中 5 条 fail（AC-05、AC-09、AC-15、AC-16、AC-18），
其余 pass 或 pass with residual；AC-11/25 确认不在 M06 gate。这里的 fail 是设计阶段结论，不等同于实现失败；
修复 F-001/F-002/F-004/F-005/F-006 后可重开为 pass。

## 设计与一致性评审

### 数据模型与 V15

- **9 张表顺序正确**：`workflow_definition → workflow_version → workflow_run → workflow_node_run →
workflow_approval → workflow_approval_assignee → workflow_tool_invocation → workflow_template →
workflow_user_template` 满足 FK 依赖；`workflow_definition.published_version_id`、`node_run.approval_id`、
  `model_invocation.workflow_*` 使用逻辑引用，避免环。
- **`model_invocation` 可空工作流引用**与现有 `run_id` 可空、`purpose=REPORT` CHECK 兼容；M04 成本聚合不依赖
  `run_id`，设计前提成立。
- **`workflow_template` 全局表**需要扩展 ADR-0012 例外清单，ADR 已登记；`workflow_user_template` 在 M06 为空表，
  M06b 无迁移，符合 DEC-0046 拆分。
- **迁移可执行性**：`model_invocation` 末尾加可空列 + 索引、`workflow_version` 触发器、权限/角色回填、内置模板种子
  都是 additive；V6 已有 `CREATE TRIGGER` 先例。未实跑迁移，T01 必须在一次性 MySQL 8.4.13 上正反验证。
- **数据完整性缺口**：`workflow_run.workflow_id` 与 `workflow_version_id` 没有复合约束保证版本属于同一流程；
  `workflow_approval.workflow_id` 与 `workflow_run.workflow_id` 也无复合 FK。服务层应校验并在迁移测试/集成测试中
  覆盖；否则错误绑定只能靠应用层。
- **`workflow_run` 作为持久队列**：不复用 `agent_run_outbox` 的理由成立（`run_id NOT NULL` + FK + `OutboxPoller`
  绑定 `agent_run.kind`）；TM-85 跨租户领取与现有 outbox claim 同类，但设计只写了边界，未给出 claim SQL/非法
  租户 ID 的 fail-closed 细节（F-014）。

### 审批模型

- 新建 `workflow_approval`/`workflow_approval_assignee` 而不是扩展 J2 `approval` 是正确的：V4 的
  `research_task_id NOT NULL`、`action_type CHECK=MCP_TOOL_CALL`、唯一键 `(tenant_id, run_id, stage, attempt_no)`
  决定扩展会破坏 J2。
- 禁自批、无审批人保持等待、一个审批最多执行一次、参数密文 + `params_sha256` + `plan_sha256`、写工具重试重新审批
  都有设计落点；`workflow_tool_invocation` 的 `(tenant_id, workflow_approval_id)` 唯一键能防重复执行。
- **缺口**：审批节点 `config` 无 `actionKind/targetNodeKey`（F-002）；审批待办无数据范围字段（F-004）；
  无审批人 `blockedReason` 未建模（F-009）；`plan_sha256` 的“上游输出摘要”具体规范化算法未定义，T07 必须在测试向量
  中固定，否则漂移检测不可复现。

### 权限/租户/部门

- 权限码 `workflow.read/edit/publish/run/approve/manage` 与 `WORKFLOW_EDITOR` 角色符合 Product §12.1/OQ-M06-04；
  `manager_user_id` 只限制 `DEPARTMENT` 发布范围、不授权，设计正确。
- 403/404 目标总体清楚：跨租户/跨部门/私有草稿/他人运行详情/平台管理员 → 404；同租户可见无能力 → 403；列表
  不泄露名称/数量。
- **平台管理员 404 的机制缺失**（F-005）：M06 所有路径都有 `workflow.read` 等 URL 权限，现有拦截器会先 403；
  M05 的 `KnowledgeController` 是先过服务层再 404 的窄例外，M06 需要同样的显式设计。
- **草稿 ACL 与 AC-01**：只有所有者/TENANT_ADMIN 可读写草稿，其他 `WORKFLOW_EDITOR` 404；同侪并发是 U-03
  （F-008），不能用“所有者 + 管理员”冒充同侪。
- **运行/详情分离**：所有者只看运行元数据、发起人/租户管理员看详情，设计可测；需确保 `GET /workflow-runs/{id}`
  对所有者返回 404，而列表返回元数据；TEN-M06-01/SEC-WF-003 要覆盖。

### 契约 1.6.0-m06

- **既有 path 未被改写**：深比较 96 个既有 path 全部相等；既有 183 个 schema 仅 `AuditTargetType` 追加
  `WORKFLOW/WORKFLOW_RUN/WORKFLOW_TEMPLATE`，属于兼容枚举扩展；`components.parameters` 新增 4 个；没有删除/重命名。
  这满足“additive”声明，也说明 Lead 的 Ruby 对比结论可复现。
- **新增 21 path/48 schema** 与 ADR §7.2 表一致；全部有 `x-riccon-permission`；写操作有 CSRF；发布/运行创建/重试/
  审批决定强制 `Idempotency-Key`；M06b 共享/升级字段和 `workflow-code-execution` 在 `x-riccon-deferred` 中登记。
- **契约缺陷**：`WorkflowEdge.branch` 枚举布尔化（F-001）；`WorkflowNodeConfig` 缺审批动作字段（F-002）；
  `WorkflowRun` 缺 `updatedAt`（F-010）；`WorkflowApproval` 缺数据范围（F-004）；`WorkflowNodeRun` 缺输入摘要
  （F-011）；`WorkflowRun.approvalStatus` 单值（F-012）；`WorkflowApprovalDecisionRequest.comment` 条件必填
  无法表达（F-017）；内置模板 `visibility` 语义（F-018）。
- **契约测试**：`WorkflowContractM06Test` 覆盖 23 个 operationId 是好的；但 F-001/F-002 会让“合法审批/条件图”
  的 request schema 测试失败或无法构造，需要在 T01 前修正。

### 审计与回滚

- 动作清单/details 白名单覆盖发布、运行、取消、审批、停用/启用、移交、管理性读取、权限变更；`AuditDetailsPolicy`
  当前对未知动作的非空 details fail-closed（`AuditDetailsPolicy.java:393-422`），`AuditDetailsReader` 读取同样
  fail-closed（`AuditDetailsReader.java:29-47`）。因此“旧 API 无法读取含 workflow.* 的审计行”是事实，U-01 的
  设计判断成立。
- **DEC-0047=A** 已把 AC-23 改为受支持回滚；这是本轮评审后用户答复的决策，本文不再把 U-01 记 P1。
  ADR §10.4/§16、ARCHITECTURE 风险和 PRODUCT AC-23 已在 `a694979`/`01b5c57` 同步。
- **DEC-0048=A** 已把配额定为运行准入 1 单位，`QuotaAdmissionService.admit` 的现有实现（租户/用户 × 日/月各 +1）
  与之相符；R-36 残余被接受，ADR/ARCH/PRODUCT 已同步。AC-17 可验证性恢复；测试应明确断言“一次运行只扣一次
  租户/用户配额”，不要按模型节点数断言。

### a11y/性能

- AC-19/20 的列表/表单等价路径、键盘、375px、axe 五界面设计具体；`@a11y` 映射到合同 `commands.a11y`；T10-T13/T16
  分工可测。需注意“五个界面”与模板库（M06b）不要混入 M06 gate。
- AC-21 沿用 `commands.perf` 并新增 `RICCON_PERF_SCENARIO=workflow`，报告记录假模型/并发/限制/脚本参数，满足可复现性；
  50 并发是 J1/J2/M06 混合而非 50 个 M06，M06 租户 10/单流程 5 单列，口径清楚。T16 目前只有 8h，见 F-015。

## T4 安全边界评审

- **TM-72..TM-85 覆盖**：图/节点/算子封闭校验、版本不可变、双重 RBAC、审批绑定、最小可见、工具白名单、提示注入、
  资源上限、保留、幂等/租约、kill switch、审计 fail-closed、部门边界、跨租户领取均有条目和验证 ID；B-27..B-30
  也能逐条对应。整体覆盖度满足 T4 设计评审的最低要求。
- **需要补强的点**：
  1. **F-005 平台管理员 404**：B-28/TM-74 的目标明确，但设计没有实现机制；这是 T4 权限矩阵的直接阻断项。
  2. **F-006 共享工具执行器**：TM-77/AC-18 要求写工具在审批后执行；现有 `McpGateway` 只允许 READ。必须明确
     J2 与 workflow 的策略差异，否则要么 J2 回归（允许写工具），要么 AC-18 无法实现。
  3. **F-014/U-13 跨租户领取**：TM-85 是新增 ADR-0009 例外，必须在 claim SQL 层只读元数据、非法 tenant fail-closed，
     并补跨租户负向测试；仅靠“领取后打开租户上下文”的描述不足以证明。
  4. **U-05 审批理由 PII**：`decisionComment` 进审计是 AC-16 的要求，但安全评审未闭环；需要在 M06 gate 前决定
     保留原文还是仅存哈希，并同步 AC-16/白名单。
  5. **提示注入/canary**：TM-78 的 `SEC-WF-004` + `INJ-001..012` 扩展方向正确；建议把“模型不能改变图/边/工具/审批”
     做成负向断言，而不只检查输出文本。
- **第二模型/安全专项**：建议对 T07（审批参数加密、plan 绑定、写工具执行）和 TM-85 做一次独立安全/第二模型复核；
  ADR 的 Reviewer 列表已包含安全评审，但当前 ARCHITECTURE 评审状态仍为 pending，需在 M06 gate 前闭环。
- **明确负向控制**：`WorkflowNodeType` 只有 7 类，`WorkflowNodeConfig`/`WorkflowGraph` 关闭额外字段，`SEC-WF-005`/
  `IT-WF-111` 覆盖代码/脚本/HTTP/并行/循环/定时；这部分设计可验证。注意 `argsTemplate` 仍是开放对象（工具 schema
  决定），必须在保存/发布/执行三处按工具 input schema 校验。

## 任务拆分、write_scope 与预算

- **AC 覆盖**：23 条 M06 AC 在 ADR §14 均有任务/测试落点；AC-11/25 明确 M06b；AC-19/20 虽 P1 但 gate 必查，符合合同
  质量基线。**但 AC-16 的共享子句与 AC-11 冲突**（F-003），需要先拆清。
- **互斥性**：T01 独占 `platform-domain` 常量、V15、`AuditDetailsPolicy`、`RoleDefinitions`、`application.yml`、
  `SchemaVersionChecker` 和契约测试；T02-T09 按 package 分工；T10-T13 按页面目录；T16 e2e/scripts。未发现明显的
  同文件双写；T15 需要 `application.yml` 配置键时由 T01 独占写入，需在任务卡中写明依赖。
- **write_scope 缺口**：T02-T09/T15 没有 `src/test` 路径，`IT-WF-*` 集成测试无 owner（F-007）。这是本轮最容易被
  忽略的执行阻断项；必须在创建任务前补测试 glob，或把具体 IT 用例分配给 T16 并扩展其 scope。
- **任务大小**：T06 14h、T07 10h、T12 10h、T16 8h 都偏大；关键路径 73h，120h 只有 20% 缓冲。建议在任务创建前
  拆 T06（队列/租约 vs 节点执行器）、T07（审批 vs 工具调用）、T12（运行详情 vs 历史/管理动作）、T16（perf vs
  安全/E2E），或由 Lead 书面接受超时回到用户的触发条件（F-015）。
- **高风险评审**：T01-T09、T12、T16、T17 均有独立评审安排；T10 在任务表风险为“高”但未列入 §13.1 的高风险清单，
  §15.5 又把 T10-T13 列入 Verifier 评审，存在表述不一致（F-020）。

## 未决项与建议

| ID                  | 状态                     | 本评审建议                                                                                                            |
| ------------------- | ------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| U-01 AC-23 回滚     | **已由 DEC-0047=A 解决** | 按“关开关 + 回滚 Worker + 保留/前向修复 API；全量 API 回滚仅限无数据”验收；ADR/ARCH/PRODUCT 已同步（a694979/01b5c57） |
| U-02 配额           | **已由 DEC-0048=A 解决** | 按运行 1 单位；测试只断言一次运行扣一次租户/用户配额；记录 R-36；ADR/ARCH/PRODUCT 已同步（a694979/01b5c57）           |
| U-03 AC-01 同侪编辑 | open                     | Product 决定是否要求同侪协同；不要求则改 AC-01 Given 并记录；要求则新增草稿共享模型（F-008）                          |
| U-05 审批理由 PII   | open                     | 安全评审在 M06 gate 前决定“原文 + 提示”或“hash + 审批记录原文”；拒绝则回改 AC-16（F-013）                             |
| U-13 跨租户领取     | open                     | 保留 TM-85，但补 claim SQL/非法 tenant fail-closed/跨租户负向测试；建议第二模型/安全复核（F-014）                     |

**建议的下一步（按顺序）**：

1. Architect 修 F-001/F-002/F-004/F-005/F-006；先把契约 `branch` 和审批 config 修正到可构造合法图。
2. Product 处理 F-003（AC-16 共享范围）和 F-008（U-03 AC-01 措辞）；Lead 在创建任务前补 F-007 的测试 write_scope，
   并决定是否按 F-015 拆任务。
3. 修复后提交 `REV-M06-ARCH-002` 重开本评审；P1 全 fixed 前不要创建 M06 实现任务。
4. 实现阶段按 T01→T02→T03→T04→T05→T06→T07→T16→T17 关键路径推进；T06/T07/T16 的测试与安全证据必须先于
   done；T17 在最终提交上跑模块 gate。

## 证据与命令摘要

```text
# 契约语义对比（Ruby）
paths: 96 -> 117; added=21 removed=0
schemas: 183 -> 231; added=48 removed=0
changed pre-existing paths: (none)
changed pre-existing schemas: AuditTargetType (added WORKFLOW, WORKFLOW_RUN, WORKFLOW_TEMPLATE)
components.parameters: +WorkflowId, +WorkflowRunId, +WorkflowApprovalId, +WorkflowTemplateId
info.version: 1.5.0-m05b -> 1.6.0-m06
x-riccon-deferred added: workflow-template-sharing (M06b), workflow-code-execution (not planned), workflow-result-artifact (P1)

# $ref 完整性
refs=1298 missing=0

# branch 枚举解析
WorkflowEdge.properties.branch.enum = [true, false, nil] (Ruby/Psych)
type = [string, "null"]  # 无合法字符串枚举值

# M06 operation 统计
21 paths；全部有 x-riccon-permission；写操作有 CsrfToken；
发布/创建运行/重试/审批决定 Idempotency-Key required。

# 只读代码核对
McpGateway.validatePolicy: riskLevel != READ -> 拒绝 (178-192)
PermissionInterceptor -> AuthService.requirePermission -> ForbiddenException (403)
AuditDetailsPolicy/AuditDetailsReader：未知动作非空 details fail-closed
QuotaAdmissionService.admit：租户/用户 × 日/月各 +1（运行 1 单位）
SchemaVersionChecker.EXPECTED_SCHEMA_VERSION = 14（T01 需升 15）
```

`riccon gate check --module M06` 在本评审落盘后失败（2026-11-25T01:18:19+08:00，退出非零），输出摘要：

```text
✗ task TASK-0217 is in_progress, not done
✗ task TASK-0217 changed files outside write_scope: docs/ARCHITECTURE.md, docs/PRODUCT.md, docs/adr/ADR-0024...
✗ task TASK-0218 is in_progress, not done
✗ task TASK-0218 changed files outside write_scope: docs/ARCHITECTURE.md, docs/PRODUCT.md, docs/adr/ADR-0024...
✗ no review found for module M06
✗ latest task review REV-M06-ARCH-001 verdict is fail
✗ latest task review REV-M06-OPS-001 verdict is fail
! evidence EV-0571 / EV-0908 are manual (cannot establish pass)
```

这是符合预期的门禁反馈：本评审 verdict=fail 且 P1 open，TASK-0218 设计评审也未完成，M06 实现任务和模块
review 尚未创建；不应为了通过而修改 review。另需 Lead 核对 `task-scope` 报错：本次评审只新增
`.riccon/reviews/tasks/M06-architect-review-001.md`，未改 `docs/ARCHITECTURE.md`/`docs/PRODUCT.md`/ADR；
该报错看起来是设计基线归因或 task baseline 设置问题，属于 Lead/工具状态处理，不是本评审写入。

## 未验证与剩余风险

- 未运行全量 Maven/前端测试、真实浏览器 E2E、迁移正反演练、OpenAPI 运行时 validator；这些属于实现阶段和模块 gate。
- 未审查 V15 具体 SQL（设计只给结构）；迁移账号 `TRIGGER` 权限、`model_invocation` INSTANT/INPLACE、模板 JSON 种子、
  角色回填幂等必须由 T01 在实际 MySQL 8.4.13 上验证。
- 残余风险（设计已承认，不重复计为 finding）：提示注入无法根治（R-31/R-02）；租户管理员可读本租户运行结果（R-32）；
  审批是控制而非串谋证明（R-33）；运行结果/节点输出 MySQL 明文（R-34）；180 天到期只做到期不可见、物理清理 M09
  （R-35）；单运行多模型节点的配额套利（R-36，DEC-0048=A 接受）；单入边树状图表达力（R-37）。
- 本次评审不替代 Product 预验收或用户产品验收；它只是设计阶段独立技术验收。
