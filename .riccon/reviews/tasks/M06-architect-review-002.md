---
review_id: REV-M06-ARCH-002
module_id: M06
task_id: TASK-0217
previous_review: REV-M06-ARCH-001
reviewer:
  agent: riccon/verifier
  session: ses_df15e35ebffeS0MGTOVvRQxChv
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - "751d384"
  - "90ff68e"
  - "a694979"
  - "01b5c57"
  - "9d30ac1"
  - "31121fb"
  - "4e575a0"
head_commit: "4e575a00dc3fc838049b53f7880c5c45069919fb"
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "WorkflowEdge.branch 已改为字符串枚举，YAML 解析与契约测试落点成立"
    summary: >-
      基线 4e575a0 的 `contracts/openapi.yaml` 中 `branch` 为
      `type: [string, "null"]`、`enum: ["TRUE", "FALSE", null]`；Ruby/Psych 解析结果
      `["TRUE","FALSE",nil]`，不再出现布尔值。ADR-0024 §7.5.1 要求 `WorkflowContractM06Test`
      覆盖每个 operationId 的 schema/枚举，§13.1 T01 把契约测试文件列入 write_scope。原 P1 已修复。
    evidence: 'contracts/openapi.yaml:8228-8233；Ruby YAML 解析输出 ["TRUE","FALSE",nil]；ADR-0024 §7.5.1、§13.1 T01'
  - id: F-002
    severity: P1
    status: fixed
    title: "审批节点 config 已补 actionKind/targetNodeKey 且 additionalProperties:false 下可表达"
    summary: >-
      `WorkflowNodeConfig` 现包含 `assigneeType`、`assigneeRole`、`assigneeUserIds`、`actionKind`、
      `targetNodeKey`；ADR §4.4 的 APPROVAL 行与契约字段一致。`actionKind` 允许 null 并引用
      `WorkflowApprovalActionKind`，`targetNodeKey` 为受限 key；服务端校验直接后继/目标工具节点。
      含审批节点的合法图现在可通过 schema 构造，T03 校验与 T01 契约测试落点明确。
    evidence: "contracts/openapi.yaml:8160-8182；ADR-0024 §4.4（APPROVAL 行）、§13.1 T01/T03"
  - id: F-003
    severity: P1
    status: fixed
    title: "AC-16 的共享审计已由 Product 修订归 M06b，M06 可验收剩余动作"
    summary: >-
      `docs/PRODUCT.md:833` 已删除“共享”动作，并在末尾明确模板共享/取消共享审计归 M06b
      AC-11/25；AC-16 的 Given/Then 现在只要求发布、运行、取消、审批、停用/重新启用、
      权限授予/撤销/移交。ADR §8.1 的动作清单覆盖这些动作（`workflow.*` 与既有
      `permission.changed`），并明确 M06b 动作不在 M06 白名单。F-003 本身已修复。
    evidence: "docs/PRODUCT.md:833；docs/adr/ADR-0024-m06-workflow-orchestration.md:573-596；git 9d30ac1"
  - id: F-004
    severity: P1
    status: fixed
    title: "WorkflowApproval 已有 dataScopeSummary，且只含范围/部门/引用计数"
    summary: >-
      新增 `WorkflowDataScopeSummary`（required: `runScope`、`documentReferenceCount`，可选
      `departmentId`），`WorkflowApproval` 将其加入 required，`WorkflowApprovalDetail` 通过
      allOf 继承；ADR §3.3.5/§5.3 明确不返回标题、片段、正文，审批人按 M05 ACL 自行访问。
      AC-05 的待办/详情数据范围字段已有契约落点，E2E-M06-04/T09 覆盖。
    evidence: "contracts/openapi.yaml:8562-8630；ADR-0024 §3.3.5、§5.3、§13.1 T09；docs/PRODUCT.md:822"
  - id: F-005
    severity: P1
    status: fixed
    title: "平台管理员 404 机制已明确为认证后的 servlet filter"
    summary: >-
      ADR §6.1 增加 `WorkflowPlatformTenantFilter`：位于认证之后、`PermissionInterceptor`/RBAC
      之前，对 `/workflows/**`、`/workflow-runs/**`、`/workflow-approvals/**`、
      `/workflow-templates/**` 的 platform 租户直接返回 404 `RICCON-TENANT-404`，不进入
      `@RequirePermission`；未认证仍 401。现有 `PermissionInterceptor` 是 MVC HandlerInterceptor，
      servlet filter 必然先于它执行；T01 的 `workflow/web/**` 可放 filter，T05/`TEN-M06-01`
      负责端点级断言。机制可落地；测试 owner 的缺口见 F-007。
    evidence: "ADR-0024 §6.1（482-483）、§13.1 T01/T05；apps/api/src/main/java/com/riccon/api/auth/controller/PermissionInterceptor.java:46-64；apps/api/src/main/java/com/riccon/api/config/WebMvcConfig.java:36-38"
  - id: F-006
    severity: P1
    status: fixed
    title: "McpCallExecutor 已定义 READ_ONLY/APPROVAL_BOUND_WRITE 双策略"
    summary: >-
      ADR §4.7 为共享执行器定义显式 `McpExecutionPolicy`：J2 固定 `READ_ONLY`，仍只允许
      `risk_level=READ`，`WRITE` 直接拒绝；M06 使用 `APPROVAL_BOUND_WRITE`，写工具仅在
      `workflow_approval` APPROVED、assignee 非发起人、`plan_sha256`/参数哈希匹配时执行，
      重试重新审批。现有 `McpGateway.validatePolicy` 的 READ-only 拒绝被保留；
      `McpGatewayIntegrationTest.invoke_writeRisk_denied` 已覆盖 WRITE 拒绝，T07b 的
      `apps/worker/src/test/java/com/riccon/worker/mcp/**` 写范围可扩展该测试；
      `IT-HITL-001`、`MCP-001..008`、`SEC-WF-004` 已列入证据。
    evidence: "ADR-0024 §4.7（380-390）、§9.2 TM-77；apps/worker/src/main/java/com/riccon/worker/mcp/service/McpGateway.java:178-192；apps/worker/src/test/java/com/riccon/worker/mcp/McpGatewayIntegrationTest.java:74-89；apps/worker/src/test/java/com/riccon/worker/research/HitlIntegrationTest.java"
  - id: F-007
    severity: P1
    status: open
    title: "测试写范围仍缺安全/审计测试 owner，TEN-M06-01/DEPT-M06-01 无法产出 surefire"
    summary: >-
      本轮已给 T02–T09、T14、T15 增加 `src/test` 路径，`IT-WF-101..112` 可按功能目录落位；
      但跨切面安全/审计测试仍没有可写的任务范围：THREAT-MODEL §8 明确要求
      `TEN-M06-01`/`DEPT-M06-01` 必须出现在
      `apps/api/target/surefire-reports/*.xml`（`grep -q "TEN-M06-01" ...`），现有
      tenant/department 套件位于 `apps/api/src/test/java/com/riccon/api/security/**`；
      §13.1 没有任何任务包含该路径。T16a 的 write_scope 只有 `e2e/**`、
      `scripts/real-stack/**`、`e2e/perf/**`，不能产出 Java surefire；T17 只有
      `.riccon/reviews/**`。`SEC-WF-002/003/006`、`IT-AUDIT-M06-01`、`IT-WF-111`
      也没有明确的任务/证据落点（T16a 证据只列 `SEC-WF-*`，不含审计/租户/部门命名用例）。
      因此 AC-15/16/24 的越权与审计证据仍无法按合同产出，F-007 未完全修复。
    owner: "riccon/lead"
    due: "M06 实现任务创建前"
    evidence: "ADR-0024 §13.1（811-835）；docs/security/THREAT-MODEL.md:826-832,785；apps/api/src/test/java/com/riccon/api/security/TenantIsolationSecurityTest.java、DepartmentIsolationTest.java 的套件清单"
  - id: F-008
    severity: P2
    status: fixed
    title: "AC-01 已由 Product 改为所有者/租户管理员并发，同侪不在 v1"
    summary: >-
      `docs/PRODUCT.md:818` 的 Given 已改为“所有者与租户管理员（或同一管理员的两个会话）
      并发修改”，并写明“同侪协同编辑不在 v1”；Product 补充约定明确 E2E-M06-01 不得作为
      同侪并发证据。与 ADR §6.2 草稿 ACL、§14 AC-01 备注一致。U-03 状态表仍滞后，见 F-022。
    evidence: "docs/PRODUCT.md:818,848；ADR-0024 §6.2、§14；git 9d30ac1"
  - id: F-009
    severity: P2
    status: fixed
    title: "无审批人已统一用 errorCode=WF_NO_APPROVER，不再需要 blockedReason 列"
    summary: >-
      ADR §5.2 明确不新增 `blocked_reason`，`WorkflowRun.errorCode=WF_NO_APPROVER` + 固定中文
      `errorMessage`（“需先配置非发起人审批人”），并创建无可用 assignee 的审批记录用于
      时间线/审计；`WorkflowErrorCode` 枚举已包含 `WF_NO_APPROVER`。SEC-WF-001 已列入 T09。
    evidence: "ADR-0024 §5.2（445）；contracts/openapi.yaml WorkflowErrorCode；§13.1 T09"
  - id: F-010
    severity: P2
    status: fixed
    title: "WorkflowRun 已补 required updatedAt 并定义轮询终止"
    summary: >-
      契约 `WorkflowRun.required` 现包含 `updatedAt`，属性为 date-time；ADR §7.4 明确
      “`WorkflowRun.updatedAt` 变化才重绘、终态停止轮询、result 只在终态读取一次”，
      ETag/includeResult 记为后续优化。OPS-M06-010 与 F-010 同一修改已落地。
    evidence: "contracts/openapi.yaml:8386-8450；ADR-0024 §7.4（553）；OPS-M06-010"
  - id: F-011
    severity: P2
    status: fixed
    title: "WorkflowNodeRun 已补 inputSummary，并明确 API 不返回完整 prompt/参数"
    summary: >-
      契约 `WorkflowNodeRun.inputSummary`（可空、maxLength 2000）与 ADR §3.3.4
      `workflow_node_run.input_summary`（VARCHAR(2000)）一致；§4.4 明确 API 返回
      `inputSummary`/`outputSummary`、不返回完整提示词或工具参数。T08/IT-WF-106 可断言。
    evidence: "contracts/openapi.yaml:8493-8498；ADR-0024 §3.3.4、§4.4（349）；§13.1 T08"
  - id: F-012
    severity: P2
    status: fixed
    title: "多审批节点已定义 approvalStatus 聚合规则与 counts 字段"
    summary: >-
      契约 `approvalStatus` 描述定义“任一 PENDING→PENDING；否则任一 REJECTED→REJECTED；
      否则任一 APPROVED→APPROVED；否则 null”，并新增 `pendingApprovalCount`/
      `approvedApprovalCount`/`rejectedApprovalCount`；ADR §3.3.3 说明是派生聚合、不新增列。
      AC-12/SEC-WF-003 可用聚合状态与 counts 断言。
    evidence: "contracts/openapi.yaml:8424-8462；ADR-0024 §3.3.3、§5.4；§13.1 T08"
  - id: F-013
    severity: P2
    status: accepted
    owner: "安全评审/riccon/product"
    due: "M06 gate 前"
    accepted_reason: >-
      U-05 仍是 M06 gate 前的安全/Product 二选一决策：当前临时默认“理由原文≤500 字进入审计
      并在审批 UI 提示”；替代方案是审计只存 `commentSha256`、审批记录保留理由。ADR §8.3/§16
      已明确未解决、owner 与 due，且不阻塞本轮设计与实现；本评审不声称安全评审已通过。
      M06 gate 前必须二选一并补测试、必要时回改 AC-16 文案。
    title: "U-05 审批理由入审计仍为 P2 residual，不是已接受的安全结论"
    summary: >-
      ADR §8.3 与 §16 U-05 明确“未解决”；本次不把 U-05 当已解决，也不伪造安全评审通过。
      作为 P2 residual 接受的是“在 M06 gate 前由安全评审/Product 决定”的流程安排，不是
      “理由原文入审计”这一隐私结果本身。
    evidence: "ADR-0024 §8.3（606）、§16 U-05（952）；docs/PRODUCT.md:833"
  - id: F-014
    severity: P2
    status: accepted
    owner: "riccon/verifier（独立安全评审）"
    due: "T06a 完成前"
    accepted_reason: >-
      F-014 的机制设计已补齐：claim SELECT 恰好 4 列并有静态列集合断言、非法/空 tenant
      fail-closed、`claim_failures_total`/denied 审计、`IT-WF-103` 与 `TEN-M06-01`
      覆盖负向场景。仍缺的是独立安全评审本身；本评审不声称 TM-85 例外已通过安全评审。
      该评审必须在 T06a 完成前完成并留下证据，否则不得把 T06a 标 done。
    title: "U-13 跨租户领取机制已补齐，独立安全评审仍是 accepted residual"
    summary: >-
      ADR §4.3/§9.2 TM-85 已明确只读列集合、fail-closed、指标与负向测试；但没有可伪造的
      安全评审结论。作为 P2 residual 接受，owner=独立安全评审、due=T06a 完成前。
    evidence: "ADR-0024 §4.3（330）、§9.2 TM-85（637）、§13.1 T06a；OPS-M06-015"
  - id: F-015
    severity: P2
    status: fixed
    title: "T06/T07/T12/T16 已拆分，总 M06 120h 与 M26 48h 成立"
    summary: >-
      ADR §13.1 现列出 21 个任务（T01..T17 加 T06a/b、T07a/b、T12a/b、T16a/b），工时合计
      120h：T01 9、T02 6、T03 6、T04 6、T05 6、T06a 6、T06b 8、T07a 5、T07b 5、T08 6、
      T09 5、T10 10、T11 2、T12a 5、T12b 5、T13 4、T14 4、T15 4、T16a 6、T16b 4、T17 8。
      关键路径 75h、前端链 41h；M26 §13.2 仍为 48h。F-015 原 P2 已修复；剩余尺寸风险见 F-023。
    evidence: "ADR-0024 §13.1（807-835）、§13.2（838-849）、§13.1 合计行 832"
  - id: F-017
    severity: P3
    status: fixed
    title: "拒绝理由条件必填已写入请求 schema"
    summary: >-
      `WorkflowApprovalDecisionRequest` 增加 `allOf`：`decision=REJECT` 时 `comment` required
      且 `minLength: 1`、`maxLength: 500`；ADR §5.4 明确缺失/空返回 400 RICCON-WF-400，
      SEC-WF-001 断言。契约与 ADR 一致。
    evidence: "contracts/openapi.yaml:8897-8917；ADR-0024 §5.4（460）；§13.1 T01/T09"
  - id: F-018
    severity: P3
    status: fixed
    title: "内置模板 visibility 已有 BUILT_IN 语义"
    summary: >-
      `WorkflowTemplateVisibility` 增加 `BUILT_IN`，`WorkflowTemplate.visibility` 对内置模板
      固定 `BUILT_IN`，用户模板 `PRIVATE`/`TENANT`（M06b）；ADR §3.3.7/§12 同步。
      `source` 枚举仍为 `BUILT_IN/TENANT`，语义一致。T14 可断言。
    evidence: "contracts/openapi.yaml:8018-8021、8627-8660；ADR-0024 §3.3.7、§12；§13.1 T14"
  - id: F-019
    severity: P3
    status: fixed
    title: "威胁模型 R-06-1 已改为 M06 不执行用户代码"
    summary: >-
      THREAT-MODEL R-06-1 现写“M06 仍不执行用户代码；代码执行开放前另立模块并重开
      TB-06/E-06-1/R-06-1”，验证改为 `SEC-WF-005`、`IT-WF-111`（无执行入口）；
      E-06-1 同步改为“代码执行开放前必须重开本威胁模型”。不再暗示 M06 有沙箱执行面。
    evidence: "docs/security/THREAT-MODEL.md:177-178；ADR-0024 §9.3/AC-24"
  - id: F-020
    severity: P3
    status: fixed
    title: "高风险任务清单已统一为 T01–T13、T16、T17"
    summary: >-
      ADR §13.1 高风险任务统一为 T01–T13、T16、T17（含拆分后的 T06a/b、T07a/b、T12a/b、
      T16a/b）；§15.5 的表覆盖 T01、T02/T03/T04、T05、T06a/b/T07a/b、T08/T09、T10–T13、
      T14/T15、T16a/b/T17，与任务表一致。T10 前端也已纳入独立评审范围。
    evidence: "ADR-0024 §13.1（835）、§15.5（927-936）"
  - id: F-021
    severity: P1
    status: open
    title: "AC-15 仍含 M06b 的共享/私有模板子句，M06 gate 无法完整验收"
    summary: >-
      Product 9d30ac1 只修订了 AC-16 和 AC-01；`docs/PRODUCT.md:832` 的 M06-AC-15 仍写
      “只有授权角色可编排/发布/共享”，并在 404 子句要求“私有模板”不可见，测试线索包含
      `SEC-WF-002`。但 M06 明确不实现模板共享、`workflow_user_template` 在 M06 无读写路径，
      `SEC-WF-002` 是 M06b AC-11 的私有模板用例（Product:828；ADR §8.1:596；
      §12:794）。Product 自己的 AC-15 优先级理由（:961）也只列编排/运行/查看/审批/取消/停用/
      授权/移交，未列共享或私有模板。ADR §14 AC-15（:871）仍把 `SEC-WF-002` 列为 M06 gate
      证据，§15.3（:906-916）只声明 AC-11/25 不进 M06 gate。结果是 M06 gate 判 AC-15 时仍有
      无法由 M06 产生的共享/私有模板 Then 子句。需要 Product 像 AC-16 一样把共享/私有模板
      子句明确移到 M06b，或在 AC-15 写清 M06 只验证的负向子集并移除 `SEC-WF-002` 的 M06 归属。
    owner: "riccon/product"
    due: "M06 gate 前"
    evidence: "docs/PRODUCT.md:832,828,961；ADR-0024 §8.1（596）、§12（794）、§14（871）、§15.3（906-916）；docs/security/THREAT-MODEL.md:537"
  - id: F-022
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "M06 gate 前"
    accepted_reason: >-
      AC-01 文本、E2E 约束与 ADR §6.2/§14 已一致；仅 §16 的 U-03 状态行和 Product §12.4
      的“审计覆盖…共享”指标尚未同步。该滞后不改变本轮 AC-01/AC-16 的可验收文本，作为文档
      清理项接受，M06 gate 前由 Architect/Product 一并修正。
    title: "U-03 状态表与 Product 指标仍滞后于 9d30ac1 的 AC-01/AC-16 修订"
    summary: >-
      ADR §16 顶部仍写“U-03..U-13 仍未决”（:944），U-03 行（:950）仍以“是否要求同侪协同”
      为未决问题；Product §12.4 的审计覆盖指标（:874）仍列“共享”，与 AC-16 的 M06b 归属
      不一致。属于文档一致性 P3，不影响本轮设计可行性。
    evidence: "ADR-0024 §16（944,950）；docs/PRODUCT.md:874；git 9d30ac1"
  - id: F-023
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M06 实现任务创建时"
    accepted_reason: >-
      T06/T16 已拆分且总预算仍为 120h；T06b 8h、T16a 6h 仍处于高风险任务上限，但关键路径
      75h、总工时 120h 留约 45h 修复/评审余量。建卡时若实际估算仍超，应按 DEC-0046 的
      20% 触发条件回用户，不再静默扩预算或削减 AC-15/16/17/18/21/23/24。
    title: "拆分后 T06b（8h）/T16a（6h）仍偏大，剩余为预算风险"
    summary: >-
      T06b 覆盖开始/结束/检索/模型/条件、超时/取消/结果/引用；T16a 覆盖 E2E/axe/键盘/
      50 并发 perf/越权/注入/重试 harness。两者仍是较大任务，但已从原 T06 14h、T16 8h
      拆分，且总额与关键路径成立；作为 P3 residual 接受，由 Lead 建卡时复核。
    evidence: "ADR-0024 §13.1 T06b/T16a（818,829）、合计行（832）、OPS-M06-013（833）"
evidence: []
created_at: "2026-11-25T01:44:47+08:00"
---

# REV-M06-ARCH-002：M06 架构与 AC 可验证性第二轮独立评审

## 结论

**verdict: fail**。新基线 `4e575a0` 已修复上一轮的大部分 P1/P2：`branch` 字符串枚举、
审批节点 `actionKind/targetNodeKey`、`dataScopeSummary`、平台租户 404 filter、共享工具
执行器双策略、`updatedAt`/`inputSummary`/审批聚合/拒绝理由条件必填、AC-16/AC-01 的
Product 修订、任务拆分与 120h 预算都已落到契约/ADR/THREAT。**但仍有 2 个 P1 open**：

1. **F-007**：T02–T09/T14/T15 已有 `src/test` 范围，但跨切面安全/审计测试没有任务写范围。
   `TEN-M06-01`/`DEPT-M06-01` 按 THREAT-MODEL 必须进入 surefire 报告，现有 suite 在
   `apps/api/src/test/java/com/riccon/api/security/**`，而 §13.1 没有任务包含该路径；
   `SEC-WF-002/003/006`、`IT-AUDIT-M06-01`、`IT-WF-111` 也未明确落点。AC-15/16/24 的
   越权与审计证据仍无法按合同产出。
2. **F-021**：Product 只修订了 AC-16，`M06-AC-15` 仍包含“编排/发布/共享”和“私有模板”
   404，测试线索仍列 M06b 的 `SEC-WF-002`。M06 不实现共享，M06 gate 无法完整验收 AC-15。

另有 2 个 P2 accepted（F-013 U-05、F-014 U-13 独立安全评审）、2 个 P3 accepted
（F-022 U-03/指标滞后、F-023 T06b/T16a 尺寸）；本评审没有伪造任何安全评审通过。

本轮评审只读：未修改生产源码、测试、迁移、`docs/PRODUCT.md`、`AGENTS.md`、ADR、威胁
模型或契约；未运行 `riccon gate check --module M06`（按任务要求，最终 gate 由 Lead 运行）。

> 评审期间（约 01:48）工作区出现另一会话对 `docs/adr/ADR-0024-m06-workflow-orchestration.md`
> 的未提交修改（5 insertions/5 deletions，涉及 RUNBOOK 同步、`V15MigrationTest`、
> `workflow.feature.changed` details、首次发布默认 false 等）。本评审以基线提交 `4e575a0`
> 的已提交文本为准，不把工作区未提交改动当作已修复证据；若 Architect 已提交，请在
> `REV-M06-ARCH-003` 中按新提交复核。

## 评审对象与验证范围

| 检查                         | 命令/方法                                           | 观察结果                                                                                                                                                                                                                                                          |
| ---------------------------- | --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 基线/提交                    | `git log -8 --oneline`、`git rev-parse HEAD`        | HEAD=`4e575a00dc3fc838049b53f7880c5c45069919fb`；设计修订提交 `4e575a0`，Product 修订 `9d30ac1`                                                                                                                                                                   |
| 工作区状态                   | `git status --porcelain`                            | 干净（评审开始时）                                                                                                                                                                                                                                                |
| 契约 YAML 语义               | Ruby/Psych `YAML.load_file`                         | `branch.enum=[\"TRUE\",\"FALSE\",nil]`；`WorkflowNodeConfig` 含 `actionKind/targetNodeKey`；`WorkflowApproval.dataScopeSummary` 在 required；`WorkflowRun.updatedAt` 在 required；`WorkflowNodeRun.inputSummary` 存在；`WorkflowTemplateVisibility` 含 `BUILT_IN` |
| 契约兼容性                   | Ruby 深比较 `b2ba0dd:contracts/openapi.yaml` 与当前 | paths 96→117（+21，0 删除）；schemas 183→232（+49，0 删除）；既有 path 0 改动；既有 schema 仅 `AuditTargetType`；components.parameters 30→34；headers/responses/securitySchemes 不变                                                                              |
| `$ref` 完整性                | Ruby 递归遍历                                       | 1301 个引用，唯一 287，缺失 0                                                                                                                                                                                                                                     |
| M06 operation 权限/CSRF/幂等 | Ruby 遍历 M06 paths                                 | 21 个 path、23 个 operation；全部有 `x-riccon-permission`；写操作有 `CsrfToken`；发布/创建运行/重试/审批决定 `Idempotency-Key` required；创建/复制/取消 optional                                                                                                  |
| 现有权限/拦截器顺序          | 只读源码                                            | `PermissionInterceptor` 是 MVC `HandlerInterceptor`（permission 在 preHandle）；`TenantContextFilter` 在 `SecurityContextHolderFilter` 之后；新 servlet filter 可在 dispatcher 前、权限拦截器前执行                                                               |
| 现有工具策略                 | 只读源码/测试                                       | `McpGateway.validatePolicy` 拒绝 `riskLevel != READ`；`McpGatewayIntegrationTest.invoke_writeRisk_denied` 已存在；`HitlIntegrationTest` 自动注入 gateway，不直接构造                                                                                              |
| 安全测试落点                 | `grep` ADR/THREAT/既有 test 路径                    | THREAT 要求 TEN/DEPT 命名用例进入 surefire；现有 suite 在 `apps/api/src/test/java/com/riccon/api/security/**`；§13.1 无该路径                                                                                                                                     |
| AC/Product 文本              | 阅读 `docs/PRODUCT.md` 818/828/832/833/961/874      | AC-01/AC-16 已修订；AC-15 仍含共享/私有模板子句；§12.4 指标仍列共享                                                                                                                                                                                               |

未运行：全量 Maven/前端测试、真实浏览器 E2E、迁移正反演练、OpenAPI 运行时 validator、
`riccon gate check`。原因是本任务只做设计阶段独立评审，且任务明确禁止运行 M06 gate；
这些由实现任务（T01/T16/T17）和模块 gate 在实现提交上执行。`branch`/schema 结论由 YAML
解析直接证明，不依赖测试运行。

## 上一轮 finding 逐条状态

> REV-M06-ARCH-001 没有 F-016（原评审编号跳号），本轮沿用原编号；新发现从 F-021 起。

| Finding | 上轮严重度 | 本轮状态     | 一句结论                                                                 |
| ------- | ---------- | ------------ | ------------------------------------------------------------------------ |
| F-001   | P1         | fixed        | `branch` 枚举已是字符串 `"TRUE"/"FALSE"`；YAML 解析与契约测试落点成立    |
| F-002   | P1         | fixed        | `WorkflowNodeConfig` 已补 `actionKind/targetNodeKey`，审批节点可表达     |
| F-003   | P1         | fixed        | AC-16 共享审计已归 M06b；AC-15 仍有同类问题，见 F-021                    |
| F-004   | P1         | fixed        | `WorkflowApproval.dataScopeSummary` 只含范围/部门/计数，不含正文         |
| F-005   | P1         | fixed        | 平台租户 404 由认证后 servlet filter 在权限拦截器前完成                  |
| F-006   | P1         | fixed        | `READ_ONLY`（J2）与 `APPROVAL_BOUND_WRITE`（M06）双策略已定义            |
| F-007   | P1         | **open**     | T02–T09/T14/T15 已有 `src/test`，但安全/审计 surefire 测试仍无任务写范围 |
| F-008   | P2         | fixed        | AC-01 已改为所有者/租户管理员并发；同侪不在 v1                           |
| F-009   | P2         | fixed        | 无审批人使用 `WF_NO_APPROVER`，不再需要 blockedReason 列                 |
| F-010   | P2         | fixed        | `WorkflowRun.updatedAt` 已 required，轮询终止规则已定义                  |
| F-011   | P2         | fixed        | `WorkflowNodeRun.inputSummary` 已补，API 不返回完整 prompt/参数          |
| F-012   | P2         | fixed        | `approvalStatus` 聚合规则 + counts 已定义                                |
| F-013   | P2         | **accepted** | U-05 仍是 M06 gate 前的安全/Product 二选一；未声称安全评审通过           |
| F-014   | P2         | **accepted** | U-13 claim 机制已补齐；独立安全评审仍待完成，未声称通过                  |
| F-015   | P2         | fixed        | 21 任务/120h、M26 48h、关键路径 75h 成立                                 |
| F-017   | P3         | fixed        | `decision=REJECT` 时 `comment` 条件必填已进契约                          |
| F-018   | P3         | fixed        | 内置模板 `visibility=BUILT_IN` 已定义                                    |
| F-019   | P3         | fixed        | THREAT R-06-1 已改为 M06 不执行用户代码                                  |
| F-020   | P3         | fixed        | 高风险任务统一为 T01–T13、T16、T17                                       |

新发现：F-021（P1，AC-15 共享/私有模板）、F-022（P3，U-03/指标滞后）、F-023（P3，
T06b/T16a 尺寸 residual）。F-013/F-014/F-022/F-023 的 owner/due/accepted_reason 见 front
matter。

## Contract 事实核对

### 契约整体

- **既有 path 0 改动**：96 个既有 path 深比较全部相等；新增 21 个 M06 path，0 删除。
- **既有 schema 仅 `AuditTargetType`**：追加 `WORKFLOW`、`WORKFLOW_RUN`、`WORKFLOW_TEMPLATE`；
  其余 183 个既有 schema 未改。新增 49 个 schema（上一轮 48 个 + `WorkflowDataScopeSummary`）。
- **`$ref` 完整**：1301 个引用、287 个唯一引用、0 缺失。
- **M06 operation**：21 path/23 operation 全部有权限声明；写操作有 CSRF；发布、创建运行、
  重试、审批决定要求 `Idempotency-Key`；创建/复制/取消 optional，与 ADR §7.2/§7.4 一致。
- **F-001**：`WorkflowEdge.branch.enum` 解析为 `["TRUE","FALSE",nil]`，字符串 `"TRUE"`/
  `"FALSE"` 可提交；布尔值不再是合法枚举。
- **F-002**：`WorkflowNodeConfig` 含 `assigneeType/assigneeRole/assigneeUserIds/actionKind/
targetNodeKey`，`additionalProperties:false` 下审批节点可表达；服务端校验目标节点。
- **F-004**：`WorkflowDataScopeSummary` 只含 `runScope/departmentId/documentReferenceCount`，
  `WorkflowApproval.required` 包含 `dataScopeSummary`；`WorkflowApprovalDetail` allOf 继承。
- **F-010/F-011/F-012/F-017/F-018**：`updatedAt`、`inputSummary`、审批 counts/聚合描述、
  `decision=REJECT` 条件必填、`visibility=BUILT_IN` 均在当前契约中可解析。
- **F-009**：`WorkflowErrorCode` 包含 `WF_NO_APPROVER`、`WF_INTERNAL` 等 20 个稳定码。

### 契约测试落点

ADR §7.5.1 要求 `WorkflowContractM06Test` 覆盖每个 operationId 的请求/响应 schema、错误码、
枚举、`x-riccon-permission`、M06b deferred 字段；T01 write_scope 明确包含
`apps/api/src/test/java/com/riccon/api/contract/WorkflowContractM06Test.java`。F-001/F-002/
F-004/F-017/F-018 的 schema 变化都能在该测试中构造正/负例；T03 的 validation 测试负责
“审批节点必须指向直接后继/目标工具”等跨字段规则。该落点成立。

## AC 逐条结论

M06 gate 应判 23 条（01..10、12..24）；AC-11/25 仍只在 M06b/M26 gate。下表是设计阶段
可验证性结论，不等于实现验收。

| AC        | 结论                     | 依据/残余                                                                                            |
| --------- | ------------------------ | ---------------------------------------------------------------------------------------------------- |
| M06-AC-01 | **pass**                 | Product 9d30ac1 改为所有者/租户管理员并发；同侪不在 v1；E2E 补充约定；U-03 状态表滞后见 F-022        |
| M06-AC-02 | **pass**                 | T03 校验器 + T10 等价视图；`IT-WF-101` 落 T02/T03/T01                                                |
| M06-AC-03 | **pass**                 | 发布触发器/版本快照/`graph_sha256`；T04/T05/T12b                                                     |
| M06-AC-04 | **pass**                 | `updatedAt` 轮询判据；T06b/T12a；终态停止轮询                                                        |
| M06-AC-05 | **pass**                 | `dataScopeSummary` 契约 + §5.3 最小可见；T07a/T09/T13；`E2E-M06-04`、`SEC-WF-001`                    |
| M06-AC-06 | **pass**                 | 禁自批/无审批人 `WF_NO_APPROVER`；T03/T07a/T09                                                       |
| M06-AC-07 | **pass**                 | 节点 attempt + 条件状态；T06b/T07b/T12a                                                              |
| M06-AC-08 | **pass**                 | 写工具前置审批、重试重新审批、未知副作用 fail-closed；T07b/T16a；F-006 策略                          |
| M06-AC-09 | **pass**                 | `branch` 字符串枚举；T03/T06b/T10；`E2E-M06-06`、`IT-WF-105`                                         |
| M06-AC-10 | **pass**                 | 1 个内置模板 + 复制；升级提示明确 M06b AC-25；T14/T10                                                |
| M06-AC-11 | **not in M06 gate**      | M06b/M26 gate；`E2E-M06-11`、`SEC-WF-002`                                                            |
| M06-AC-12 | **pass**                 | 聚合规则 + counts；T08/T12b；`SEC-WF-003`                                                            |
| M06-AC-13 | **pass**                 | `inputSummary`/`outputSummary`/引用 ACL；T06b/T08/T12a；`IT-WF-106`                                  |
| M06-AC-14 | **pass**                 | 180 天/租户策略/到期不可见；T08；`IT-WF-107`                                                         |
| M06-AC-15 | **fail**                 | 平台 404 filter 已补，但 AC 文本仍含 M06b 共享/私有模板子句；见 F-021                                |
| M06-AC-16 | **pass (with residual)** | Product 修订共享审计归 M06b；ADR §8.1 覆盖剩余动作；但 `IT-AUDIT-M06-01` 仍无明确任务 owner（F-007） |
| M06-AC-17 | **pass**                 | 运行 1 单位配额（DEC-0048=A）；T05/T06a/T15/T16a                                                     |
| M06-AC-18 | **pass**                 | `READ_ONLY`/`APPROVAL_BOUND_WRITE` 策略；T03/T07b/T16a；`SEC-WF-004`                                 |
| M06-AC-19 | **pass**                 | 五界面状态/375px；T10/T12a/T12b/T13/T16a；`E2E-M06-09`、`@a11y`                                      |
| M06-AC-20 | **pass**                 | 键盘全流程/列表表单等价路径；T10/T11/T13/T16a                                                        |
| M06-AC-21 | **pass**                 | 50 并发混合、p95 口径；T16a/T16b/T17；`PERF-M06-001`                                                 |
| M06-AC-22 | **pass**                 | 既有 path 0 改动；J1/J2/M03/M10/M04/M05/M25 回归；T16a/T17                                           |
| M06-AC-23 | **pass**                 | DEC-0047=A 回滚口径、判定 SQL/拒绝路径；T15/T17/T16b                                                 |
| M06-AC-24 | **pass (with residual)** | 负向节点/无执行入口、平台 404；但 `IT-WF-111`/`SEC-WF-005` 任务 owner 未明确（F-007）                |
| M06-AC-25 | **not in M06 gate**      | M06b/M26 gate；`E2E-M06-12`、`SEC-WF-002`、`@a11y`                                                   |

**AC 统计**：23 条 M06 gate AC 中 22 条 pass（其中 AC-16/24 带残余），1 条 fail（AC-15）；
AC-16/24 的残余来自 F-007 的测试 owner 缺口，AC-15 的 fail 来自 F-021。AC-11/25 确认只在
M06b gate。上一轮的 AC-05/09/16/18 已从 fail 转为 pass；AC-15 仍 fail（F-021 新发现，
不是 F-005 未修）。

## 任务拆分、write_scope 与测试 owner

### 21 个任务与预算

ADR §13.1 任务表实际为 21 行：T01、T02、T03、T04、T05、T06a、T06b、T07a、T07b、T08、
T09、T10、T11、T12a、T12b、T13、T14、T15、T16a、T16b、T17。工时合计：

```text
9+6+6+6+6+6+8+5+5+6+5+10+2+5+5+4+4+4+6+4+8 = 120h
```

关键路径 T01→T02→T03→T04→T05→T06a→T06b→T07a→T07b→T16a→T16b→T17 = 75h；
前端链 T01→T02→T10→T11→T12a→T12b→T13 = 41h；M26 §13.2 六任务合计 48h。总预算成立。
T15 3→4h、T16 8→10h、T11 5→2h 的调剂总额仍 120h，和 OPS-M06-013 一致。

### AC 覆盖

§14 任务映射覆盖全部 23 条 M06 AC（01..10、12..24），AC-11/25 只映射到 M26-B1..B5；
§12.6/§15.3 明确 AC-11/25 不进 M06 gate。未发现遗漏的 M06 AC。

### write_scope 互斥性

按 §13.1 的路径检查，未发现同文件双写：T01 独占 platform-domain/身份与审计常量、V15、
`AuditDetailsPolicy`、`RoleDefinitions`、`application.yml`、`SchemaVersionChecker`、CI、
`restore-drill`；T02–T09 按 API/worker feature package 分工；T10–T13 按页面目录分工；
T14 template；T15 runtime/metrics/config/RUNBOOK；T16a e2e/scripts-real-stack/perf；
T16b `scripts/ops/workflow-*.mjs`；T17 `.riccon/reviews/**`。共享文件标注由 T01/T15 独占，
方向正确。

### 测试 owner 缺口（F-007，P1）

- T02–T09、T14、T15 已有对应 `src/test` 目录，`IT-WF-101..112` 基本可按功能目录落位。
- **但** THREAT-MODEL §8（`docs/security/THREAT-MODEL.md:826-832`）要求
  `TEN-M06-01`/`DEPT-M06-01` 出现在 `apps/api/target/surefire-reports/*.xml`；现有
  `TenantIsolationSecurityTest`/`DepartmentIsolationTest` 在
  `apps/api/src/test/java/com/riccon/api/security/**`。§13.1 没有任何任务包含该路径。
  T16a 只有 `e2e/**`、`scripts/real-stack/**`、`e2e/perf/**`，不能产出 Java surefire；
  T17 只有 `.riccon/reviews/**`。这是上一轮 F-007 的直接残留。
- `SEC-WF-002/003/006`、`IT-AUDIT-M06-01`、`IT-WF-111` 在 §13.1 也没有明确任务/证据落点
  （T16a 的“证据”列只有 `SEC-WF-*`，不含 `IT-AUDIT`/`TEN`/`DEPT`；`IT-WF-111` 只在 AC-24
  映射中出现）。
- 修复建议：在 T16a 或 T08/T09/T05 的 write_scope 增加
  `apps/api/src/test/java/com/riccon/api/security/**`（或新建安全测试任务），并在任务卡中
  逐个列出 `SEC-WF-001..006`、`TEN-M06-01`、`DEPT-M06-01`、`IT-AUDIT-M06-01`、
  `IT-WF-111` 的落点；否则 AC-15/16/24 和 B-28/B-30 无法按合同取证。

### 仍偏大的任务（F-023，P3）

T06b 8h 仍覆盖 5 类节点、超时/取消、结果/引用；T16a 6h 仍覆盖 E2E/axe/键盘/perf/越权/
注入/重试 harness。拆分已发生且总额成立，但建卡时应再核对；若实际超 120h 20%，按
DEC-0046 回用户，不得静默扩预算或砍 AC-15/16/17/18/21/23/24。

## T4 安全边界复核

- **平台管理员 404（F-005）**：filter 机制明确且与现有 MVC 拦截器顺序可落地；`TEN-M06-01`
  断言 404 而不是 403。但测试 owner 缺口见 F-007。
- **J2/工作流工具隔离（F-006）**：`READ_ONLY` 保持现有 `validatePolicy` 的 WRITE 拒绝；
  `APPROVAL_BOUND_WRITE` 只在工作流审批/plan 校验后允许 WRITE；`McpGatewayIntegrationTest`
  的 WRITE 拒绝用例仍在 T07b 可写范围。不能因 M06 需要 WRITE 放宽 J2。
- **跨租户领取（F-014）**：claim SELECT 4 列、静态断言、非法 tenant fail-closed、
  `claim_failures`/denied、`IT-WF-103`/`TEN-M06-01` 已定义；但独立安全评审仍是
  accepted residual，不能声称已通过。
- **U-05**：审批理由入审计仍是临时默认；安全评审/Product 必须在 M06 gate 前二选一。
  本评审不把 U-05 当作已解决。
- **负向控制**：无代码/脚本/任意 HTTP/并行/循环/定时入口；`SEC-WF-005`/`IT-WF-111`
  的设计目标成立。

## 未验证与剩余风险

- 未运行全量 Maven/前端测试、真实浏览器 E2E、axe、迁移正反演练、OpenAPI 运行时
  validator；这些属于 T01/T16/T17 与模块 gate。
- 未运行 `riccon gate check --module M06`（任务明确禁止，避免污染 state；最终 gate 由 Lead
  运行）。
- V15 SQL、触发器权限、`ApiApplicationTests`/`SchemaVersionCheckerTest`/`restore-drill`
  联动、CI 触发器计数仍是实现验证项；设计已写入 T01/T15/T16b write_scope。
- F-013/F-014 的 accepted 是流程性 residual，不是安全评审通过；M06 gate 前必须由独立
  安全评审给出结论并留证据。
- F-021 未修复前，M06 AC-15 的“共享/私有模板”Then 子句无法完整验收；F-007 未修复前，
  AC-15/16/24 的 surefire 证据无法产出。

## 证据命令摘要

```text
# 基线
git rev-parse HEAD
-> 4e575a00dc3fc838049b53f7880c5c45069919fb

# 契约关键字段（Ruby/Psych）
branch.enum = ["TRUE", "FALSE", nil]          # types [String, String, NilClass]
WorkflowNodeConfig.properties keys includes actionKind, targetNodeKey
WorkflowApproval.required includes dataScopeSummary
WorkflowRun.required includes updatedAt
WorkflowNodeRun.properties includes inputSummary
WorkflowTemplateVisibility.enum = ["BUILT_IN","PRIVATE","TENANT"]
WorkflowApprovalDecisionRequest.allOf if decision=REJECT -> comment required

# 契约兼容性（Ruby 深比较 b2ba0dd:contracts/openapi.yaml）
paths: 96 -> 117; added=21 removed=0
schemas: 183 -> 232; added=49 removed=0
changed pre-existing paths: (none)
changed pre-existing schemas: AuditTargetType
components.parameters: 30 -> 34 (+WorkflowId, WorkflowRunId, WorkflowApprovalId, WorkflowTemplateId)
headers: 1 -> 1; responses: 21 -> 21; securitySchemes: 1 -> 1

# $ref
refs=1301 uniq=287 missing=0

# M06 operation
21 paths / 23 operations; all have x-riccon-permission;
writes have CsrfToken; publish/createRun/retry/decision have required Idempotency-Key.

# 测试 owner 检查
grep "TEN-M06-01" docs/security/THREAT-MODEL.md
-> 必须出现在 apps/api/target/surefire-reports/*.xml
find apps/api/src/test/java/com/riccon/api/security -maxdepth 1 -type f
-> TenantIsolationSecurityTest.java / DepartmentIsolationTest.java / SecurityTestSupport.java
ADR §13.1 T16a write_scope = e2e/**、scripts/real-stack/**、e2e/perf/**（无 security/src/test）
```

## 结论与建议下一步

1. **Product**：修订 `docs/PRODUCT.md` 的 M06-AC-15，把“共享”和“私有模板”子句像 AC-16
   一样明确归 M06b；M06 的 AC-15 只保留流程/运行/审批/草稿/内置模板列表的 403/404 矩阵，
   并从 M06 测试线索中移除或标注 `SEC-WF-002` 的 M06b 归属（F-021）。
2. **Lead/Architect**：在建卡前为 `TEN-M06-01`/`DEPT-M06-01`/`SEC-WF-002/003/006`/
   `IT-AUDIT-M06-01`/`IT-WF-111` 明确任务与 write_scope；至少给 T16a 或 T08/T09/T05 增加
   `apps/api/src/test/java/com/riccon/api/security/**`，并保留 surefire 套件清单（F-007）。
3. **安全评审/Product**：M06 gate 前完成 U-05 二选一；**独立安全评审**完成 U-13/TM-85
   例外并以证据关闭 F-014；在 T06a 完成前不得把两项 residual 当作已通过。
4. **Lead**：建卡时复核 T06b/T16a 的实际工作量；如超过 120h 20% 按 DEC-0046 回用户。
5. 修复 F-021/F-007 后再开 `REV-M06-ARCH-003`；P1 全 fixed 前不要创建 M06 实现任务。

本评审不替代模块 review、Product 预验收或用户产品验收；它是设计阶段的独立技术验收。
