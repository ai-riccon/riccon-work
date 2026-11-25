---
review_id: REV-TASK-0227-001
module_id: M06
task_id: TASK-0227
reviewer:
  agent: riccon/verifier
  session: ses_dedc8df67ffevy3pn2fWTtKfPu
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 578df556262780e3cf1f83f9ec276cf9c74e72fa
  - 3839a086ae72660674585c5d5d3333ad5c3118d5
  - 5596c97bd2ec4481487a9ac0767c7315f3eb6171
head_commit: b8ff470f6130245848be678929cd8a179dc71bd8
code_commit: 5596c97bd2ec4481487a9ac0767c7315f3eb6171
evidence_commit: b8ff470f6130245848be678929cd8a179dc71bd8
baseline_commit: 2d89c70dd046ed04b7d058f83bed6d2bbdce0014
task_card_baseline_commit: 2d89c70dd046ed04b7d058f83bed6d2bbdce0014
review_scope: >-
  M06 T07b（TASK-0227）独立任务评审：只审 `workflow/tool/**`、`mcp/service/McpCallExecutor.java`、
  `McpGateway.java` 及其测试，重点覆盖 `McpCallExecutor` 双策略、`WorkflowToolNodeExecutor`
  审批/plan/参数/重试/未知副作用、`workflow_tool_invocation` 唯一键与审计、J2 `mcp_invocation`
  回归、EV-1084 的 commit/dirty/hash。独立重跑 9 个测试类 77 例、ArchUnit `ModuleBoundaryTest`
  12 例与全仓 `spotless:check`。不运行 M06 模块全量 build/coverage、真实浏览器 E2E/axe、perf/k6、
  Trivy/semgrep、迁移演练，不评审 T07a/T08/T09/T10/T16a 的实现，不把 worker 测试当作 AC-07/08/18
  的完整 E2E/UI 证据，不替代模块 review、Product 预验收或用户最终验收，也不运行 gate check。
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
  pass (with residual)。acceptance/build/tests/evidence 指任务卡的后端验收范围：双策略、写工具
  重审批、一个审批最多一次、STARTED 未知副作用、plan/params 漂移、J2 WRITE 仍拒绝均有代码与真实
  MySQL + stdio MCP mock 集成测试支撑；独立复跑 77/0/0/0，EV-1084 commit=5596c97b、dirty=false、
  exit=0、sha256 一致。lint 指独立复跑 `./mvnw -q spotless:check` exit 0（未绑定 EV）；
  security 指本任务代码面（租户过滤、审批/plan/工具授权、审计白名单、错误脱敏）未发现越权路径，
  未运行 Trivy/semgrep。AC-07/08/18 的 E2E/UI、审批摘要 ACL、提示注入/canary 不在本任务，表内标
  partial，由 T16a/T09/T10/T12 承接；coverage/E2E/perf/a11y/migration/docs 属模块 gate 或后续任务。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/architect（T03/T05/T07b 归属决定）；Lead 跟踪
    due: "M06 gate 前决定归属；若升为 AC-18 硬控制则 T07b/T03 修复"
    title: "工具参数未按 mcp_tool.input_schema 校验，ADR §4.7 的执行前 schema 检查缺失"
    summary: >-
      ADR-0024 §4.7（`docs/adr/ADR-0024-m06-workflow-orchestration.md:380`）要求工具节点执行前
      “参数通过 schema/长度/敏感键校验；否则不发起调用”，架构评审也明确要求保存/发布/执行三处按
      工具 input schema 校验（`.riccon/reviews/tasks/M06-architect-review-001.md:431`）。T07b 的
      `WorkflowToolApprovalVerifier` 只做 AES-GCM 解密、`params_sha256` 与敏感键/长度复核
      （`WorkflowToolApprovalVerifier.java:108-111`；`ApprovalParamsCrypto.java:118-151`），
      `WorkflowToolNodeExecutor` 直接把 `verified.arguments()` 交给执行器
      （`WorkflowToolNodeExecutor.java:224-228`），`McpCallExecutor.call` 也未读取
      `McpToolHandle.tool().inputSchema()`（`McpCallExecutor.java:146-189`）。全仓工作流生产代码中
      没有 `inputSchema` 引用（`grep -rn "inputSchema" apps/worker/src/main/java/com/riccon/worker/workflow`
      无结果）。观察：参数模板/解密结果只受“对象、≤8000 字节、≤16 顶层键、敏感键”约束，未对照工具
      声明的必填字段/类型；期望：不满足 schema 时不发起外部调用并失败关闭（建议 WF_TOOL_NOT_AUTHORIZED
      或独立稳定码）。当前 M02 目录全部 `requires_approval=TRUE`、MCP server 自身通常也会校验 schema，
      且审批预览已展示参数，故未发现由此直接绕过审批/授权的路径；这是纵深防御与设计一致性缺口，不是
      已证明的越权。
    accepted_reason: >-
      未构成已证明的权限/副作用绕过：工具仍须租户授权、平台白名单、APPROVED 审批、plan/参数哈希
      一致；参数经 AES-GCM 认证且审批人可见脱敏预览；MCP server 是平台允许列表内的受信组件，通常
      自行校验 input schema。当前目录工具 schema 简单、审批预览可见参数，且 MCP server 通常自行校验，缺口的影响面有界。纳入 P3
      accepted，避免在 T07b 单任务内引入跨 T03/T05 的 schema 校验实现与契约变更；但 M06 gate 前必须
      由架构明确归属（T03/T05 保存/发布 + T07b 执行，或记录为 AC-18 之外的残余），不能无限期悬空。
    evidence: >-
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:380`；
      `.riccon/reviews/tasks/M06-architect-review-001.md:431`；
      `WorkflowToolApprovalVerifier.java:108-111`；`WorkflowToolNodeExecutor.java:224-228`；
      `McpCallExecutor.java:146-189`；`McpMockServer.java:103-111`（mock schema 要求 text，但测试只发
      合法参数）；`grep -rn "inputSchema" apps/worker/src/main/java/com/riccon/worker/workflow` 空结果。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/architect（T03 校验器 vs T07b 执行器口径）；Lead 跟踪
    due: "M06 gate 前统一；最迟 M07 引入 requires_approval=false 工具前"
    title: "READ + requires_approval=false 的图可发布，但 T07b 一律要求审批，保存校验与运行行为不一致"
    summary: >-
      T03 校验器只在 `requiresApproval || risk=WRITE` 时要求工具节点有直接 APPROVAL 前驱
      （`apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowToolAvailability.java:65-67`；
      `WorkflowValidator.java:634-636` 直接 return），因此 `START→MCP_TOOL(READ, requires_approval=false)→END`
      可通过 SAVE/VALIDATE/PUBLISH。T07b 的 `WorkflowToolApprovalVerifier.verify` 无条件调用
      `findLatestToolApproval`，缺少审批即 `WF_APPROVAL_REQUIRED`
      （`WorkflowToolApprovalVerifier.java:35,92-95`）。观察：同一张被 T03 判为合法的图在运行时会
      失败；期望：二者统一——要么 M06 所有工具节点强制审批（T03/T05 改为所有 MCP_TOOL 必须有
      APPROVAL 前驱），要么 T07b 为目录明确免审批的 READ 工具提供无审批执行路径。当前 V5/M02
      目录全部 `requires_approval=TRUE`（`V5__model_gateway_and_mcp.sql:173-181`），且 T07b 更严格，
      不存在“缺审批却执行”的安全漏洞；该缺口是未来 M07/M07b 工具目录扩展时的功能一致性问题。
    accepted_reason: >-
      当前可达目录没有 `requires_approval=false` 工具，T07b 的严格行为只会拒绝、不会放行；无越权或
      副作用风险。T03/T07b 分属不同任务，统一口径应走架构/任务卡而不是在本任务单侧修改；记 P3
      accepted，M06 gate 前由架构定夺，M07 引入免审批工具前必须闭环。
    evidence: >-
      `WorkflowToolAvailability.java:65-67`；`WorkflowValidator.java:634-636`；
      `WorkflowToolApprovalVerifier.java:35,92-95`；`WorkflowToolTestSupport.java:103-128`
      （测试工具固定 requires_approval=TRUE，未覆盖该分支）；`V5__model_gateway_and_mcp.sql:173-181`。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-b2（T07b）；T16a 补安全/容量回归
    due: "M06 gate 前补关键分支；容量/并发由 T16a 覆盖"
    title: "若干高风险分支缺直接测试：预算耗尽、输出上限、并发唯一键、PENDING/自批/目标绑定负向"
    summary: >-
      主路径已有 9 个真实 MySQL + stdio MCP 集成测试（READ/WRITE、plan/params 漂移、缺审批、
      未授权、STARTED 未知、审批消费、重试重审批）与 9 个策略单测、3 个错误码映射；但以下分支没有
      直接回归：
      （1）`5596c97b` 把 `callTimeout(execution)` 前移到 `insertStarted` 之前
      （`WorkflowToolNodeExecutor.java:187-188`），代码顺序正确，但测试类没有任何 deadline/
      `mcpTimeoutSeconds` 用例（`WorkflowToolNodeExecutorIntegrationTest` 9 个 DisplayName 无超时/预算），
      “预算耗尽不写 STARTED”只能靠代码审阅；
      （2）节点输出 256 KiB 上限分支 `WorkflowToolNodeExecutor.java:302-316` 无集成用例，
      `WorkflowToolNodeExecutorTest` 只测错误码映射；
      （3）`insertStarted` 并发 `DuplicateKeyException`（`:205-210`）无并发用例；
      （4）`requireApproved` 的 PENDING/自批/actionKind 分支（`WorkflowToolApprovalVerifier.java:119-138`）
      与 `requireToolBinding` 目标节点/工具不匹配（`:147-158`）无直接负向用例（plan 漂移用例会先命中
      plan 哈希，遮蔽这些分支）。这些是测试覆盖缺口，不是已观察到的失败；主路径与安全关键路径已被
      现有用例锁定。
    accepted_reason: >-
      核心风险（J2 WRITE 拒绝、写工具审批、plan/params 漂移、STARTED 未知、审批消费、重试重审批）
      均有真实 MySQL 集成测试，未发现假绿（77 例 0 skipped、0 failures/errors）；缺口集中在超时/容量/
      并发/负向分支，失败时均 fail-closed，不构成当前验收阻断。T07b 已移 review，补测试可在 M06
      gate 前由 T16a 或后续 T07b 修复任务完成；记 P3 accepted 并保留 owner/due。
    evidence: >-
      `WorkflowToolNodeExecutor.java:187-188,205-210,302-316`；
      `WorkflowToolApprovalVerifier.java:119-158`；
      `WorkflowToolNodeExecutorIntegrationTest.java:124-462`（9 个用例清单）；
      `WorkflowToolNodeExecutorTest.java:19-50`（仅错误码映射）；
      `grep -n "mcpTimeout\|deadline\|callTimeout" WorkflowToolNodeExecutorIntegrationTest.java`
      无超时用例。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b2（T07b 证据）；Lead closeout 核对
    due: "TASK-0227 done 前或 M06 gate 前"
    title: "EV-1084 日志只含 77 tests，未包含 ArchUnit/spotless；EV-1081 为修复前旧提交"
    summary: >-
      EV-1084 的 JSON 记录为 commit `5596c97bd2ec4481487a9ac0767c7315f3eb6171`、`dirty=false`、
      `exit_code=0`、`output_sha256=6cfc4c85…`，日志实际内容为 9 个类 77 tests + `BUILD SUCCESS`；
      但日志中没有 `spotless:check` 或 ArchUnit/`platform-architecture-tests` 的输出
      （`grep -cE "spotless|ArchUnit|architecture-tests"` 结果为 0）。任务 evidence 同时绑定
      EV-1081（commit `6b2a35a6`，在 `5596c97b` 修复之前）与 EV-1084；EV-1081 不是最终代码证据，
      应视为历史。EV-1083 `dirty=true`，未列入任务 evidence，处理正确。观察：任务策略通常把
      “测试”与“lint/ArchUnit”分开取证，本任务没有独立 lint EV；期望：要么补一条
      `spotless:check`+ArchUnit 的干净 EV，要么在 closeout 明确 lint 仅为评审方独立重跑、不进入
      任务证据。独立重跑已覆盖（77 例、ArchUnit 12/12、spotless exit 0），无功能影响。
    accepted_reason: >-
      EV-1084 的 77 例与 hash/commit/dirty 均复核一致，最终代码的证据链成立；ArchUnit/spotless
      已由本评审在 HEAD `b8ff470f`（代码等同 `5596c97b`，仅 .riccon 收尾）独立复跑通过，不构成
      假绿或功能阻断。EV-1081 是修复前旧提交，保留为历史不会改变最终结论，但容易让模块 gate
      误把旧提交当最终证据；记 P3 accepted，由 builder/Lead 在 done 前补 lint EV 或注明历史归属。
    evidence: >-
      `.riccon/evidence/TASK-0227/EV-1084.json`；`EV-1084-*.log`（77 tests、BUILD SUCCESS）；
      `.riccon/evidence/TASK-0227/EV-1081.json`（commit 6b2a35a6）；`EV-1083.json`（dirty=true）；
      `grep -cE "spotless|ArchUnit|architecture-tests" EV-1084-*.log` = 0；
      本评审独立复跑：77/0/0/0、ArchUnit 12/12、spotless exit 0。
evidence:
  - EV-1084
reviewed_evidence:
  - EV-1084
  - EV-1081
notes: >-
  评审时 HEAD `b8ff470f` 是 T07b 证据/任务收尾提交，代码与 `5596c97b` 相同；本评审只新增本 review
  文件，未修改源码/测试/迁移/契约/PRODUCT/AGENTS/.riccon 状态（工作树另有他人正在修改的
  `.riccon/tasks/TASK-0234.json`，与本评审无关），未运行 gate check。T07b 三个
  代码提交（578df556/3839a086/5596c97b）的文件均在 TASK-0227 write_scope 内；`2d89c70d..5596c97b`
  范围中的 `WorkflowApprovalFeatureDisabledApiTest.java`（6b2a35a6）与 `contracts/README.md`/
  `contracts/openapi.yaml`（11793f51）是 TASK-0229/T09 的交错提交，不属于 T07b，state.gate 的
  “TASK-0227 changed files outside write_scope”是基线范围归因噪声，不是 T07b 越界。
created_at: "2026-11-25T18:45:00+08:00"
---

# REV-TASK-0227-001：TASK-0227 M06 T07b 独立评审

## 结论

**通过（pass，with residual）**。`McpCallExecutor` 双策略、`WorkflowToolNodeExecutor` 的审批/
plan/参数绑定、写工具重审批、一个审批最多一次、STARTED 未知副作用 fail-closed、结果/输出上限、
`mcp.tool.invoked` 审计与 J2 回归均有代码和真实 MySQL + stdio MCP mock 测试支撑。独立复跑
77 例（0 failures / 0 errors / 0 skipped）、ArchUnit 12/12、全仓 `spotless:check` exit 0；
EV-1084 的 commit/dirty/exit/hash 与记录一致。未发现新的 P0/P1/P2；4 个 P3 accepted（schema
执行校验缺失、READ 免审批口径不一致、少数分支缺直接测试、证据描述偏差），均有 owner/due/
accepted_reason，不阻断 T07b done，但需在 M06 gate 前由对应 owner 收敛。

## 任务与 AC 对账

### 任务卡验收

| 任务卡验收                                                                                                                                                                                             | 结果                                           | 依据                                                                                                                                                                                                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `McpCallExecutor` 双策略 READ_ONLY（J2）/APPROVAL_BOUND_WRITE（M06）、plan 绑定、写工具重审批、未知副作用 `WF_SIDE_EFFECT_UNKNOWN`；AC-07/08/18；证据 IT-WF-104、SEC-WF-004、IT-HITL-001、MCP-001..008 | **pass（后端范围）**                           | 双策略 `McpCallExecutor.java:50-136`；执行器 `WorkflowToolNodeExecutor.java:142-337`；plan/审批校验 `WorkflowToolApprovalVerifier.java:84-222`；集成测试 9 例；J2 策略单测 9 例；EV-1084                          |
| J2 WRITE 继续拒绝，不得放宽；工具白名单与审批摘要 ACL；参照 ADR §4.7；受影响测试通过                                                                                                                   | **pass（J2/白名单）；partial（审批摘要 ACL）** | `McpGateway` 仍以 `READ_ONLY` 委托（`McpGateway.java:95-110`），`McpCallExecutorPolicyTest.readOnly_rejectsWriteTool`、`McpGatewayIntegrationTest.invoke_writeRisk_denied`；审批摘要 ACL 属 T09 待评审，不在 T07b |
| 高风险任务 done 前独立 Verifier 评审；只改 write_scope；不改 contracts/PRODUCT/AGENTS；不启动 M06b；提交后取证，任务只移 review                                                                        | **pass**                                       | 本评审；三个代码提交文件均在 scope；范围归因见下节；任务状态仍为 review                                                                                                                                           |

### M06 AC 对账（用户可见结果）

| AC | 原文用户可见结果（引用） | 本任务证据 | 结论 |
| --- | --- | --- | --- |
| M06-AC-07 | “Given 流程包含 A、B、C 三个顺序节点，B 配置为会失败或超时；When 运行；Then A 标记成功并保留输出，B 标记失败并显示可读原因与超时标记，C 标记跳过，整体运行标记失败；已完成节点记录与结果保留；When 重试失败节点；Then 从 B 继续，A 不重新执行，B 成功后 C 继续；When B 重试仍失败；Then 保留失败原因，可再次重试，不自动无限重试。” | T07b 覆盖工具节点失败码与“重试需重新审批”；序列推进/超时标记/部分完成由 T06b；`E2E-M06-05` 未运行 | **partial**（T06b/T16a 承接） |
| M06-AC-08 | “Given 一次运行中某节点已成功调用外部工具或已生成结果；When 用户重试该运行或重复点击运行/重试；Then 已成功节点不重复执行，已生成结果不重复追加，产物不重复创建，重复请求只产生一个运行或一次重试；When 失败节点是写操作 MCP 工具；Then 重试前必须重新审批，不复用旧批准；When 系统无法保证外部副作用幂等；Then 重试前明确提示风险并默认不自动重试，由审批人决定是否继续。” | `WorkflowToolNodeExecutor` 一个审批最多一次（`findInvocationStatus` + V15 唯一键），STARTED 未知 fail-closed，写工具重试 `WF_APPROVAL_REQUIRED`；集成测试 `startedInvocation_failsUnknown`/`terminalInvocation_requiresNewApproval`/`retry_requiresFreshApproval`；E2E 与 UI 风险提示未运行 | **partial**（后端 pass；E2E/UI 由 T16a/T09/T10/T12 承接） |
| M06-AC-18 | “Given 流程使用已授权 MCP 工具与知识库检索内容；When 节点引用未授权工具、已停用工具或越权文档；Then 保存/发布被拒绝或运行在该节点失败，提示原因，不发起工具调用；When 工具为写操作或高风险；Then 节点必须经过审批才能执行，写工具重试重新审批；When 检索内容或工具返回内容包含提示注入样本；Then 不改变流程指令、不触发 DAG 中未定义的工具、不泄露 canary 凭据或内网地址；审批摘要只显示审批人有权访问的内容。” | 工具授权/状态复查（`findAuthorizedTool` + `validatePolicy`）、写工具审批与重试、`mcp.tool.invoked` 审计已有真实 DB 证据；提示注入/canary、审批摘要 ACL 未在本任务运行 | **partial**（后端授权/审批 pass；注入/canary 与 ACL 由 T16a/T09 承接） |

AC-07/08/18 都含 E2E/UI 子句，worker 集成测试不能单独判完整 pass；本评审只确认 T07b 负责的后端
行为，不把 partial 写成无残余 pass。

## 策略与共享执行器

- `McpCallExecutor` 用显式 `Policy` 区分调用方；两个策略共享 server/tool ACTIVE、平台白名单、
  风险等级非空 fail-closed、审批 ID 必需（`McpCallExecutor.java:101-136`）。READ_ONLY 额外要求
  `risk=READ` 且 `requires_approval=TRUE`，WRITE 一律拒绝；APPROVAL_BOUND_WRITE 只放宽风险等级，
  不放开审批 ID 这一底线（`:127-133`）。策略单测覆盖 READ/WRITE、缺审批、白名单、停用工具、
  结果超限（`McpCallExecutorPolicyTest.java:48-142`）。
- 结果上限与 J2 错误码保持：超过 `maxResultBytes` 抛 `ResultLimitException`，仍继承
  `McpException(INVALID)` 即 `RICCON-MCP-422`（`McpCallExecutor.java:176-181,226-236`）；
  `McpCallExecutorPolicyTest.call_resultOverBudget_throwsResultLimit` 断言该错误码。
- `McpGateway` 的 diff 是抽取而非行为改写：授权查询/策略/端点/超时/文本提取/关闭逻辑移入共享
  执行器（`McpGateway.java:96-130`），`mcp_invocation` 生命周期、`mcp.tool.invoked` 审计、
  DENIED/FAILED 路径保持原样（`:180-295`）。J2 `McpGatewayIntegrationTest` 4 例与
  `HitlIntegrationTest` 10 例在 EV-1084 与独立复跑中均通过；WRITE 仍被拒
  （`McpGatewayIntegrationTest.java:74-88`）。
- 未发现 J2 行为放宽、工作流写入 `mcp_invocation`、或共享执行器猜测调用方策略的路径。F-001
  （schema）与 F-002（READ 免审批口径）是策略/设计一致性残余，不改变当前调用方边界。

## 执行器、审批与 plan

### 审批与参数

- `WorkflowToolApprovalVerifier.verify` 的顺序符合 ADR：工具配置 → 最新 `MCP_TOOL` 审批 →
  `APPROVED`/`decided_by != requested_by`/`actionKind=MCP_TOOL`（`:84-138`）→ 目标节点/工具快照
  （`:147-158`）→ 运行绑定版本重算 `plan_sha256`（`:168-193`）→ 写工具重试新鲜度
  （`:206-222`）→ 密文/哈希/敏感键/长度解密复核（`:108-111`；
  `ApprovalParamsCrypto.java:118-151`）。失败均发生在任何外部调用之前，并写 DENIED 审计
  （`WorkflowToolNodeExecutor.java:150-156`）。
- 禁自批在 T07b 执行前复查（`WorkflowToolApprovalVerifier.java:128-134`），不依赖 T09 的请求
  校验；目标节点/工具/actionKind 不一致都失败关闭。T09 的审批摘要 ACL 不在 T07b，已按任务说明
  记录为跨任务残余。
- plan 重算排除审批节点自身（`:251-259`），与 T07a 创建口径一致：T07a 创建时审批节点是最新
  RUNNING 尝试、不会进入 `WorkflowExecutionState.outputs()`（`WorkflowExecutionState.java:60-78`）；
  决定批准后审批节点才写 `SUCCEEDED`（`WorkflowApprovalDecisionService.java:172-189`），T07b
  执行时必须排除它，否则每次都会误判 `WF_PLAN_DRIFT`。首次审批、原地失败后重审批两条路径都由
  `readTool_approved_executesOnce` 与 `retry_requiresFreshApproval` 锁定，未发现“排除过度”的
  漂移盲区（版本/审批节点配置/上游摘要/目标工具仍在哈希内）。

### 一个审批最多一次与未知副作用

- 执行前查 `workflow_tool_invocation`（`:161-172`）：`STARTED` → `WF_SIDE_EFFECT_UNKNOWN`；
  终态 → `WF_APPROVAL_REQUIRED`，不重放。`insertStarted` 先于外部调用（`:187-210`），
  V15 唯一键 `(tenant_id, workflow_approval_id)` 兜底并发（`V15__workflow_studio.sql:302`）；
  `DuplicateKeyException` → `WF_SIDE_EFFECT_UNKNOWN`（`:205-210`）。
- 外部调用异常（端点/协议/超时/未知）统一 `WF_SIDE_EFFECT_UNKNOWN`（`:230-275`，
  `errorCodeOf` `:502-510`）；调用记录写 `FAILED`，重试看到终态后必须重新审批。调用成功但结果
  超出 `maxResultBytes` 映射 `WF_TOOL_RESULT_LIMIT`；节点输出超 256 KiB 也在写成功记录后失败
  关闭（`:302-316`），不静默截断。`5596c97b` 把 `callTimeout` 前移到 `insertStarted` 之前
  （`:187-188`），预算耗尽时不再留下 STARTED，代码顺序正确；该分支缺直接测试，见 F-003。
- 审计：成功/失败/拒绝均写 `mcp.tool.invoked`，details 带 `workflowRunId`、`workflowNodeId`、
  `approvalId`，不含参数/结果原文（`WorkflowToolAuditor.java:70-113`）；
  `readTool_approved_executesOnce` 断言三者（`WorkflowToolNodeExecutorIntegrationTest.java:162-175`）。
  `AuditDetailsPolicy` 已允许这三个键（`AuditDetailsPolicy.java:40-48`）。

### 重试与跨任务残余

- 写工具原地重试（同审批、新 attempt）会 `WF_APPROVAL_REQUIRED`（`:206-222`；
  `retry_requiresFreshApproval` 断言）；T09 的 retry API 必须把运行回退到前置 APPROVAL 节点生成
  新审批，不能复用旧批准。该行为是 ADR §4.6/§4.7 的设计要求，不属 T07b 缺陷；记录为 T09 已知
  残余。
- 未知副作用默认不自动重试；`WorkflowEngine` 对 `WorkflowNodeFailureException` 只标记节点/运行
  FAILED，不做自动重试（`WorkflowEngine.java:479-503`），符合 AC-08 的“由审批人决定是否继续”。

## J2 回归

- `McpGateway` 只把 `validatePolicy`/`call`/`textOf`/`close` 移入 `McpCallExecutor`，J2 的
  `mcp_invocation` STARTED/终态、`mcp.tool.invoked` 审计、DENIED/FAILED/ALREADY_INVOKED 分支与
  原实现逐行等价；新增的 `riskLevel()==null` 检查是 fail-closed 收紧，不是放宽。
- J2 WRITE 仍拒绝：`McpCallExecutorPolicyTest.readOnly_rejectsWriteTool` 与
  `McpGatewayIntegrationTest.invoke_writeRisk_denied`；`HitlIntegrationTest` 10 例覆盖 J2 HITL。
- 工作流只写 `workflow_tool_invocation` 与同名审计动作，不写 `mcp_invocation`；J2 的
  `mcp_invocation.approval_id` FK 语义未被触碰。EV-1084 的 4 个 `McpGatewayIntegrationTest` + 10 个
  `HitlIntegrationTest` 通过。

## 测试与证据

| 证据/复跑 | 记录与复核                                                                                                                                                                                                                                                                                                                               |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-1084   | `commit=5596c97bd2ec4481487a9ac0767c7315f3eb6171`、`dirty=false`、`exit=0`、`output_sha256=6cfc4c852d0d1db13e3ba06726b216b63e415c5c69857ce7314571585713601b`；日志 9 类 77 tests、0 failures/errors/skipped、BUILD SUCCESS；sha256 与日志文件一致                                                                                        |
| EV-1081   | `commit=6b2a35a66b77d33cdfd7d6f64ca27b80043258c3`、`dirty=false`、`exit=0`；在 `5596c97b` 修复前，属历史证据，最终结论以 EV-1084 为准                                                                                                                                                                                                    |
| EV-1083   | `commit=3839a086…`、`dirty=true`、`exit=0`；未列入 TASK-0227 evidence，未绑定，处理正确                                                                                                                                                                                                                                                  |
| 独立复跑  | 在 HEAD `b8ff470f`（代码等同 `5596c97b`）执行与 EV-1084 相同的 9 类命令：77 tests、0 failures/errors/skipped、BUILD SUCCESS                                                                                                                                                                                                              |
| ArchUnit  | `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest`：12 tests、0 failures/errors/skipped                                                                                                                                                                                                             |
| Spotless  | `./mvnw -q spotless:check`（无 `-DspotlessFiles`）：exit 0                                                                                                                                                                                                                                                                               |
| 测试清单  | `McpCallExecutorPolicyTest` 9、`WorkflowToolNodeExecutorTest` 3、`McpGatewayIntegrationTest` 4、`HitlIntegrationTest` 10、`WorkflowApprovalExecutorIntegrationTest` 6、`WorkflowApprovalDecisionServiceIntegrationTest` 8、`WorkflowEngineIntegrationTest` 27、`WorkflowEngineWiringTest` 1、`WorkflowToolNodeExecutorIntegrationTest` 9 |

EV-1084 的日志只有 77 tests，没有 `spotless`/ArchUnit 段；本评审的 lint/ArchUnit 是独立复跑，
不冒充 EV-1084 内容。真实 MySQL（Testcontainers）与真实 stdio `McpMockServer` 在日志中可看到
启动/停止记录，不是纯 mock 假绿。

## 范围与归因

- T07b 三个提交的文件：`578df556` 新增/修改 `mcp/service/McpCallExecutor.java`、
  `mcp/service/McpGateway.java`、`workflow/tool/**` 与对应测试；`3839a086` 只删
  `WorkflowToolTestSupport` 未使用夹具；`5596c97b` 只改 `WorkflowToolNodeExecutor` 的
  `callTimeout` 位置。全部在 TASK-0227 write_scope 内。
- `2d89c70d..5596c97b` 范围里的 `WorkflowApprovalFeatureDisabledApiTest.java`（`6b2a35a6`，T09）
  与 `contracts/README.md`/`contracts/openapi.yaml`（`11793f51`，T09 契约收敛）不属于 T07b；
  `state.gate` 的 “TASK-0227 changed files outside write_scope” 是同一范围交错提交造成的归因噪声。
  T07b 没有修改 contracts/PRODUCT/AGENTS/V15/API/T07a/T09；“J2”指 `McpGateway` 的抽取，
  行为由回归测试守住，不是 J2 合同/语义变更。
- 未修改 V15；`workflow_tool_invocation` 表与唯一键为 T01 既有迁移，本任务只按既有 schema 写入。
  未启动 M06b，未动模板/前端。

## 未验证 / 残余风险

1. **AC-07/08/18 的 E2E/UI**：`E2E-M06-05`、运行详情失败/超时标记、重试风险提示、审批详情
   “重试”标注、审批摘要 ACL 未在本任务运行；由 T16a/T09/T10/T12 承接。本任务只证明 worker
   后端行为。
2. **提示注入/canary**：T07b 把工具结果当不可信文本写入节点输出，但没有运行 `SEC-WF-004` 的
   注入样本/canary 测试；由 T16a 补。F-001 的 schema 校验缺失也属纵深防御缺口。
3. **T09 原地重试**：T07b 会按设计返回 `WF_APPROVAL_REQUIRED`；T09 retry API 必须回退前置
   APPROVAL 重新审批，否则用户会看到“重试即失败”。该行为已有集成测试锁定，不属 T07b 缺陷。
4. **T07a F-001 WARN**：正常审批停车仍会打印 `workflow.engine.aborted_lease_lost` WARN
   （EV-1084/独立复跑日志可见）；`REV-TASK-0226-001` 已 accepted，T06b/T15 在 M06 gate 前收敛，
   本任务不重开。
5. **覆盖率**：未运行模块全量 coverage；新增 `workflow/tool/**` 的异常/并发分支可能拉低模块
   覆盖率，module gate 必须按合同门槛复核。
6. **安全扫描/性能**：未运行 Trivy/semgrep、k6/perf、axe；属 release/module gate 与 T16a。
7. **F-001/F-002/F-003/F-004**：均为 P3 accepted，见 findings；不影响本任务 pass，但 M06 gate
   前需按 owner/due 收敛。

## Findings

| ID    | 级别 | 状态     | 标题                                                                   | Owner / Due                                           |
| ----- | ---- | -------- | ---------------------------------------------------------------------- | ----------------------------------------------------- |
| F-001 | P3   | accepted | 工具参数未按 `input_schema` 校验，ADR §4.7 执行前 schema 检查缺失      | architect（T03/T05/T07b 归属）；M06 gate 前           |
| F-002 | P3   | accepted | READ + `requires_approval=false` 可通过 T03 校验，但 T07b 一律要求审批 | architect（T03 vs T07b）；M06 gate 前，M07 前必须闭环 |
| F-003 | P3   | accepted | 预算耗尽/输出上限/并发唯一键/PENDING·自批·目标绑定缺直接测试           | builder-b2 + T16a；M06 gate 前                        |
| F-004 | P3   | accepted | EV-1084 日志不含 ArchUnit/spotless，EV-1081 为修复前旧提交             | builder-b2/Lead；TASK-0227 done 前或 M06 gate 前      |

P0/P1/P2：0。P3 accepted 均有 owner/due/accepted_reason；没有 open finding。

## 是否可 done

**可结束 review 进入 done（pass，with residual）**。任务卡要求的独立 Verifier 评审已完成；
T07b 后端验收、J2 回归、真实 MySQL/MCP 集成测试、ArchUnit 与 spotless 均通过；EV-1084 为干净
最终证据，EV-1083 正确未绑定；无 open P0/P1/P2。M06 模块 done 前仍须完成模块 gate（build/
coverage/E2E/a11y/security/perf）、T16a 的 `E2E-M06-04/05` 与注入/canary、T09 的审批 ACL/重试
回退，以及 F-001..F-004 的 owner 收敛。本评审不修改源码/测试/迁移/契约/PRODUCT/AGENTS/.riccon
状态，未运行 `gate check --module M06`，也不替代 Product 预验收或用户最终验收。
