---
review_id: REV-TASK-0108-002
previous_review: REV-TASK-0108-001
module_id: M04
task_id: TASK-0108
reviewer:
  agent: riccon/verifier
  session: ses_e028b1e0cffegSYGgmhSpG7xDC
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
review_kind: residual-closure
reviewed_commits:
  - 096f585f6aadf4345434a0b83a97b4a5456e414f
head_commit: e2867be528cd50f1b8b3d5e4e0e66fff4846a2a8
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/builder-cost（TASK-0140）"
    due: "TASK-0140 / M20 gate 前"
    title: "价格快照读取时点是成功终态，而 ADR-0022 §2.1/架构要求最终成功尝试开始"
    summary: >-
      ModelInvocationService 在成功写入前调用 findPricing（ModelInvocationService.java:162、296-298），
      读到的是调用结束时的 model 价格；ADR-0022:191、docs/ARCHITECTURE.md:427 与 V11 列注释
      （V11__model_gateway_depth.sql:151-152）均写“最终成功尝试开始时”。若调用期间运维修改价格，
      在途调用使用新价格，与“开始时快照”不符。实现只读一次、不会混合两个价格，且 M04 无价格编辑
      端点，影响有界。IT-COST-101 只断言“价格更新后历史不变”，不覆盖调用期间改价。
    accepted_reason: >-
      原 due（M04 gate 前）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：M04 无价格编辑
      端点，当前实现只读一次价格、不会混合两个价格，偏差只出现在调用期间运维改价的窗口；M04 已由
      用户最终验收（DEC-0027），该残余不改变已验收 AC-11 的结论；修复任务 TASK-0140 已建，负责按
      “最终成功尝试开始”读取价格，或由 Architect 修订 ADR-0022/架构/列注释；承诺在 M20 gate 前关闭。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:162、296-298；
      docs/adr/ADR-0022-m04-quota-and-cost.md:191；docs/ARCHITECTURE.md:427；
      apps/api/src/main/resources/db/migration/V11__model_gateway_depth.sql:151-152。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-cost（TASK-0141）"
    due: "TASK-0141 / M20 gate 前"
    title: "3 秒查询保护只有 mock 端口单测，未在真实 MySQL 证明 3s 中止 → 503"
    summary: >-
      CostUnavailableMappingTest 用手写 CostQueryRepository/PlatformCostQueryRepository 抛
      QueryTimeoutException 验证映射（CostUnavailableMappingTest.java:38-158），没有真实慢查询；
      生产实现只在 SQL 文本中放 /*+ MAX_EXECUTION_TIME(3000) */（CostJdbc.java:37、
      PlatformCostJdbc.java:30）。现有证据不能证明 MySQL 8.4 实际在 3s 中止并把错误翻译为
      DataAccessException 后返回 RICCON-COST-503。180 天/page/size 边界有 HTTP 测试；3s 部分
      尚未闭环。
    accepted_reason: >-
      原 due（M04 gate 前）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：
      MAX_EXECUTION_TIME(3000) 优化器提示已下发，超时映射有 mock 单测且失败态不会返回部分结果，
      缺口是“缺少真实慢查询证据”而非功能失效；M04 已由用户最终验收（DEC-0027）；修复任务
      TASK-0141 已建，补真实 MySQL 3s 中止并返回 RICCON-COST-503 的证据；承诺在 M20 gate 前关闭。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/cost/CostUnavailableMappingTest.java:38-158；
      apps/api/src/main/java/com/riccon/api/cost/persistence/CostJdbc.java:37；
      apps/api/src/main/java/com/riccon/api/admin/cost/persistence/PlatformCostJdbc.java:30；
      apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java:198-213。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/builder-cost（TASK-0140）"
    due: "TASK-0140 / M20 gate 前"
    title: "报表生成路径的 usage→PRICED 成本没有断言；报表调用可能全部停在 USAGE_UNKNOWN"
    summary: >-
      AgentRunResumeHandler 从 AgentResultEvent.getResult().getChatUsage() 取 usage
      （AgentRunResumeHandler.java:441-442），但 ReportArtifactIntegrationTest 不检查
      model_invocation 的 prompt/completion/cost_status/cost_amount；现有成本测试只直接调用
      ModelInvocationService。若 AgentScope 在该路径不填 usage，报表调用会静默写 USAGE_UNKNOWN，
      AC-11 的报表成本无法证明。需在 worker IT 或 E2E-M04-06 断言生成阶段调用为 PRICED 且
      金额与固定样例一致；聊天路径同理缺少端到端成本断言。
    accepted_reason: >-
      原 due（M04 gate 前）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：usage 缺失时
      写 USAGE_UNKNOWN，只会少计成本、不会伪造金额或泄露数据；M04 已由用户最终验收（DEC-0027）；
      修复任务 TASK-0140 已建，补报表/聊天路径 PRICED 与金额断言；承诺在 M20 gate 前关闭。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:434-443；
      apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java；
      apps/worker/src/test/java/com/riccon/worker/model/ModelCostSnapshotIntegrationTest.java:50-173。
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/builder-cost（TASK-0140）"
    due: "TASK-0140 / M20 gate 前"
    title: "B4 F-006 未在 B7 收口：报表 TOOL/GENERATION 调用仍不写 first_token_ms"
    summary: >-
      REV-TASK-0105-003 将“报表 TOOL/GENERATION model_invocation 不记录 first_token_ms”列为
      accepted P2，owner B4+B7，due B7 closeout。096f585 与当前 HEAD 仍以 null 作为 firstTokenMs
      调用 succeeded（AgentRunRequestedHandler.java:348-357、AgentRunResumeHandler.java:434-443），
      没有新增 attempt 首字回填或报表路径断言。
    accepted_reason: >-
      原 due（M04 gate 前 / B7 closeout）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：
      first_token_ms 缺失只影响报表 TOOL/GENERATION 的首字延迟归因，不影响金额、路由或审计；M04 已由
      用户最终验收（DEC-0027）；修复任务 TASK-0140 已建，补 attempt 首字回填或书面接受 AgentScope
      限制；承诺在 M20 gate 前关闭。
    evidence: >-
      .riccon/reviews/tasks/TASK-0105-review-003.md:76-84；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:348-357；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:434-443。
  - id: F-005
    severity: P2
    status: accepted
    owner: "riccon/builder-cost（TASK-0141）；V2 TEN-033 复验"
    due: "TASK-0141 / M20 gate 前"
    title: "平台成本合计的五类互斥计数没有测试；平台 SQL 是租户 SQL 的独立副本"
    summary: >-
      CostAdminApiTest 只插入两条 PRICED 调用并断言 TENANT 金额（CostAdminApiTest.java:28-84），
      没有 UNPRICED/USAGE_UNKNOWN/currencyMismatch/unbucketed，也没有断言五项之和等于范围内
      终态调用；PlatformCostJdbc.totals（:68-95）与 CostJdbc.totals（:53-79）是两份独立 SQL，
      租户侧 IT-COST-102 的五项断言不能覆盖平台副本。
    accepted_reason: >-
      原 due（M04 gate 前）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：平台合计当前
      只由两条 PRICED 样例覆盖，未见实际漂移或错账，租户侧五项计数测试已存在；M04 已由用户最终验收
      （DEC-0027）；修复任务 TASK-0141 已建，补平台五类互斥计数与调用量一致性测试，并由 V2 在
      TEN-033 独立复验；承诺在 M20 gate 前关闭。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:28-84；
      apps/api/src/main/java/com/riccon/api/admin/cost/persistence/PlatformCostJdbc.java:68-95；
      apps/api/src/main/java/com/riccon/api/cost/persistence/CostJdbc.java:53-79；
      apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java:119-136。
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-core（TASK-0142）；ADR-0009 由 Architect 确认"
    due: "TASK-0142 / M20 gate 前"
    title: "TenantContextFilter 例外按方法/URI/平台租户放行但不校验角色，ADR-0009 未修订，负向测试不完整"
    summary: >-
      isPlatformCostFilter 只检查 GET + /api/v1/admin/costs + principal.tenantId()=="platform"
      （TenantContextFilter.java:139-143），没有在过滤器内要求平台成本权限；角色仍由
      @RequirePermission(platform.cost.read) 在 PermissionInterceptor 强制，当前无越权读取，
      但安全过滤器在角色校验前放行伪造 tenantId，属 defense-in-depth 缺口。ADR-0009:17 仍写
      查询参数 tenantId 一律忽略/拒绝并审计，未记录本例外。CostAdminApiTest 只覆盖租户管理员
      →403 与平台管理员 →200（:148-169），缺平台非管理员、X-Tenant-Id 头、其他路径/方法的负向用例。
    accepted_reason: >-
      原 due（M04 gate 前）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：过滤器例外只
      放行 GET /api/v1/admin/costs + platform 租户，controller 仍由 @RequirePermission
      (platform.cost.read) 强制，当前无越权读取；M04 已由用户最终验收（DEC-0027）；修复任务
      TASK-0142 已建，补过滤器角色校验或 ADR-0009 例外记录与负向测试，并做独立安全评审；承诺在
      M20 gate 前关闭。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:112-143；
      apps/api/src/main/java/com/riccon/api/auth/controller/PermissionInterceptor.java；
      apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:148-169；
      docs/adr/ADR-0009-tenant-isolation-enforcement.md:17。
  - id: F-007
    severity: P2
    status: accepted
    owner: "riccon/architect（TASK-0143）"
    due: "TASK-0143 / M20 gate 前"
    title: "OpenAPI 通用 Page 无最大 200，实现按 ADR 拒绝 page=201"
    summary: >-
      ADR-0022 §2.4 要求 page 1..200，CostPage.MAX_PAGE=200（CostPage.java:22），
      CostApiTest 断言 page=201 →400（:202-207）；但 contracts/openapi.yaml:3361-3368 的 Page
      只声明 minimum 1，没有 maximum。按契约生成的客户端会认为 201 合法。需要为成本端点增加带
      maximum 200 的参数（或在合同记录该限制），否则契约与实现不一致。
    accepted_reason: >-
      原 due（M04 gate 前）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：服务端仍拒绝
      page=201（400 RICCON-COST-400），只是契约未声明 maximum 导致生成客户端认知偏差，无数据泄露；
      M04 已由用户最终验收（DEC-0027）；修复任务 TASK-0143 已建，为成本端点 Page 增加 maximum 200
      并跑契约校验；承诺在 M20 gate 前关闭。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/cost/domain/CostPage.java:16-41；
      contracts/openapi.yaml:3361-3368；apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java:202-213；
      docs/adr/ADR-0022-m04-quota-and-cost.md:234。
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/architect（TASK-0143）"
    due: "TASK-0143 / M20 gate 前"
    title: "AC-12“统一 404”与成本端点伪造 tenantId 的 403 行为不一致"
    summary: >-
      docs/PRODUCT.md:356 AC-12 要求“直接用租户 B 标识请求成本/配额数据；Then 统一 404”；
      CostAdminApiTest:165-168 对 /api/v1/costs?tenantId=other 断言 403 RICCON-AUTH-403
      （TenantContextFilter 的 ADR-0009 行为），而 /costs/invocations?userId=他租户 为 404。
      403 不泄露存在性，安全上可接受；但验收文案需明确“tenantId 伪造走 ADR-0009 403、资源 ID
      越权走 404”，否则 E2E-M04-06 无法按 AC 原文判定。
    accepted_reason: >-
      原 due（M04-AC-12 / E2E-M04-06）未闭环，Lead 已新建 fix 模块 M20 承接并重绑 due。风险有界：
      伪造 tenantId 返回 403 不泄露资源存在性，资源 ID 越权返回 404，两种行为都安全，只是 AC 文案
      需要定稿；M04 已由用户最终验收（DEC-0027，AC-12 按现有证据通过）；修复任务 TASK-0143 已建，
      统一 AC/契约/E2E 口径；承诺在 M20 gate 前关闭。
    evidence: >-
      docs/PRODUCT.md:356；docs/ACCEPTANCE.md:305；
      apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:135-140、165-168。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M20 closeout"
    title: "EV-0561/0562 使用 -q，日志无 Tests run 汇总；EV-0563 为静默日志且已被 EV-0564 取代"
    summary: >-
      EV-0561（57 KB）与 EV-0562（67 KB）exit 0、commit/clean 正确，但日志没有 surefire 汇总；
      EV-0563 输出仅 332 B、无任何测试输出，EV-0564 对同一 ModelContract108bTest 非 -q 运行
      显示 Tests run:1、exit 0。EV-0563 已从 TASK-0108.evidence 移除，但历史 EV 文件与
      EV-0561/0562 的日志形态仍保持原样。
    accepted_reason: >-
      历史 EV 与日志不追溯改写，避免破坏证据哈希与历史可追溯性；EV-0563 已从任务 evidence 移除，
      后续 M20 EV 必须保留 surefire“Tests run”汇总，并在 note 中标注被取代关系；由 Lead 在
      M20 closeout 检查。
    evidence: >-
      .riccon/evidence/TASK-0108/EV-0561.json、EV-0562.json、EV-0563-*.log、EV-0564.json；
      .riccon/tasks/TASK-0108.json（evidence 已不含 EV-0563）。
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "TASK-0143"
    title: "tenantId 空串与 userId 非法 UUID 的契约边界未对齐/未测试"
    summary: >-
      OpenAPI 成本端点查询参数 tenantId minLength 1（contracts/openapi.yaml:3311-3318），
      PlatformCostService.normalizeTenantFilter 将空串/空白归一为 null → 返回全租户（:122-131）；
      /costs/invocations?userId=not-a-uuid 由 Spring 类型错误映射为 RICCON-REQUEST-400
      （GlobalExceptionHandler.java:101-112），而不是成本错误码。
    accepted_reason: >-
      无当前泄露；tenantId 空串被归一为全租户、userId 非法 UUID 返回 RICCON-REQUEST-400 而非
      成本错误码，属契约边界未对齐；并入 TASK-0143 的契约与测试收口，由 Architect 负责。
    evidence: >-
      contracts/openapi.yaml:3311-3318；
      apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java:122-131；
      apps/api/src/main/java/com/riccon/api/web/GlobalExceptionHandler.java:101-112。
  - id: F-011
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M20 启动前"
    title: "高风险代码任务的 tools_used 未记录 Serena 使用或降级理由"
    summary: >-
      TASK-0108.tools_used 仅为 codegraph、git；项目补充规则要求代码任务默认使用 Serena，
      MCP 不可用时记录降级。本次 45 文件/4000+ 行、含安全过滤器与金额逻辑，未记录 Serena 使用
      或降级原因，无法核对是否按团队规则完成符号级修改。非代码缺陷。
    accepted_reason: >-
      历史任务卡不改写；M20 起所有高风险代码任务必须在 tools_used 记录 Serena/CodeGraph 使用
      或明确的降级理由，由 Lead 在 M20 启动前落实并在任务模板/派单中检查。
    evidence: ".riccon/tasks/TASK-0108.json（tools_used）；AGENTS.md「工具使用（Serena / CodeGraph）」。"
  - id: F-012
    severity: P3
    status: accepted
    owner: "riccon/builder-cost（TASK-0140）"
    due: "TASK-0140 / M20 gate 前"
    title: "provider 返回负 token 时会生成负金额，违反 CostTotals.amount 的契约 pattern"
    summary: >-
      costSnapshot 直接 BigDecimal.valueOf(promptTokens) 计算（ModelInvocationService.java:310-313），
      未校验非负；若上游返回负 usage，cost_amount 为负数，而 OpenAPI amount pattern
      ^\d{1,14}(\.\d{1,6})?$ 不含负号（contracts/openapi.yaml:5884-5887）。
    accepted_reason: >-
      正常 provider 不返回负 token，负金额只在异常上游数据下出现，风险有界；TASK-0140 在写库前
      拒绝负 token 或归一为 USAGE_UNKNOWN，owner builder-cost；承诺在 M20 gate 前关闭。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:296-314；
      contracts/openapi.yaml:5884-5887。
evidence:
  - EV-0561
  - EV-0562
  - EV-0564
created_at: "2026-11-21T17:37:49+08:00"
---

# REV-TASK-0108-002：TASK-0108 残余闭环补充评审

本文件是**补充闭环评审**，不替代 `REV-TASK-0108-001`。`REV-TASK-0108-001` 仍是 TASK-0108 的正式任务级评审，原文不修改；本文件只回答“001 的 P2/P3 残余后来是否闭环、由谁承接”。

## 结论

**pass（补充闭环）**。在 M04 gate 时点，001 的 8 条 P2（F-001..F-008）与 4 条 P3（F-009..F-012）都**没有**被后续提交修复；本评审逐条复核当前 HEAD 后，全部转为 `accepted`，并绑定 Lead 已建的 fix 模块 M20（TASK-0140..TASK-0143）。**本评审不声称任何 finding 已 fixed。**

- M04 已于 `2026-11-21T16:34:46+08:00` 完成用户最终验收（DEC-0027），本评审不重开 M04，也不降低已验收 AC 的结论。
- M20 当前为 `planned`，TASK-0140..0143 均为 `planned`；修复尚未开始，因此“accepted + 承接任务”是唯一诚实的闭环状态。
- 原 001 中 F-001..F-007 的 due 均包含“M04 gate 前”（F-001 为“M04 gate 前确认；M05 价格编辑前必须修正”），F-008 的 due 为“M04-AC-12 / E2E-M04-06”；这些时点均已过。Lead 以 M20 重新绑定到期，本评审如实记录“原 due 未闭环”，不把过期 due 改写成按时完成。

## 边界与不可变项

- 未修改生产源码、测试、迁移、构建配置、合同、任务卡、`.riccon/state.json`、历史评审或历史证据；只新增本文件与 `M04-review-supplement-001.md`。
- `REV-TASK-0108-001`、`REV-M04-005`、`TASK-0108.json`、`M20.json`、`TASK-0140..0143.json` 均保持原样。
- 任务级评审不参与模块 gate；M04 gate 已在最终提交 `795ed5b` 上通过，本文件不重跑、不替换模块级 DoD 证据。

## 复核方法

1. 读取 `REV-TASK-0108-001` 全文、`REV-M04-005`、`TASK-0108.json`、`M20.json`、`TASK-0140..0143.json`、`docs/ACCEPTANCE.md` 的 M04-AC-11/12、`docs/PRODUCT.md` 的 M04-AC-11/12、ADR-0022、ADR-0009 与相关代码/契约/测试。
2. 用 `git log --oneline 096f585..HEAD -- <001 证据涉及的 9 个路径>` 核对后续提交：输出为空，说明相关生产代码、契约与测试自 `096f585` 起未变化。当前 HEAD 为 `e2867be`，逐条缺口仍可复现。
3. 核对 EV-0561/0562/0564 的 `exit_code=0`、`manual=false`、`dirty=false`、`commit=096f585`、日志文件存在；本补充**没有重跑测试、没有新录 EV**，不把旧证据冒充新验证。
4. 核对 M20 的 `kind=fix`、`fixes_module=M04`、`status=planned`，四个任务的 write_scope/acceptance 与 001 的 finding 映射一致。
5. 运行 `riccon state validate --project .`：0 errors、42 warnings；写入前基线为 43 warnings，新增文件自身 0 warning，减少的 1 条来自 TASK-0108 最新 task review 由 001 切换为本文件。

## 残余复核表（以当前 HEAD 复现）

| Finding | 级别 | 当前观察（HEAD `e2867be`）                                                                                                      | 承接                 | 结论                     |
| ------- | ---- | ------------------------------------------------------------------------------------------------------------------------------- | -------------------- | ------------------------ |
| F-001   | P2   | `ModelInvocationService.java:162、296-298` 仍在成功写库前读取当时价格；ADR-0022:191/架构:427/V11 列注释仍写“最终成功尝试开始时” | TASK-0140            | accepted，未修复         |
| F-002   | P2   | `CostUnavailableMappingTest` 仍全部是 mock repository 抛 `QueryTimeoutException`；无真实 MySQL 慢查询用例                       | TASK-0141            | accepted，未修复         |
| F-003   | P2   | `ReportArtifactIntegrationTest` 仍无 `cost_status/cost_amount/model_invocation` 断言；报表 usage 可能落 `USAGE_UNKNOWN`         | TASK-0140            | accepted，未修复         |
| F-004   | P2   | `AgentRunRequestedHandler.java:348-357` 与 `AgentRunResumeHandler.java:434-443` 仍把 `firstTokenMs` 传 `null`                   | TASK-0140            | accepted，未修复         |
| F-005   | P2   | `CostAdminApiTest` 仍只有两条 PRICED 样例，无 UNPRICED/USAGE_UNKNOWN/currencyMismatch/unbucketed 与五项之和断言                 | TASK-0141            | accepted，未修复         |
| F-006   | P2   | `TenantContextFilter.isPlatformCostFilter`（:139-143）仍只校验方法/URI/platform 租户，不校验角色；ADR-0009 未记录例外           | TASK-0142            | accepted，未修复         |
| F-007   | P2   | `contracts/openapi.yaml:3361-3368` 的 Page 仍只有 `minimum: 1`，无 `maximum: 200`；实现仍拒绝 201                               | TASK-0143            | accepted，未修复         |
| F-008   | P2   | `docs/PRODUCT.md:356` 与 `docs/ACCEPTANCE.md:305` 仍写“统一 404”；测试对伪造 tenantId 仍断言 403                                | TASK-0143            | accepted，未修复         |
| F-009   | P3   | EV-0563 已从 `TASK-0108.evidence` 移除；EV-0561/0562 仍为 `-q` 无汇总日志，历史 EV 未改写                                       | Lead（M20 closeout） | accepted，未修复日志形态 |
| F-010   | P3   | `PlatformCostService.normalizeTenantFilter` 仍把空串归一为全租户；非法 UUID 仍走通用 400                                        | TASK-0143            | accepted，未修复         |
| F-011   | P3   | `TASK-0108.tools_used` 仍只有 `codegraph`、`git`                                                                                | Lead（M20 启动前）   | accepted，不追溯改写     |
| F-012   | P3   | `costSnapshot` 仍未拒绝负 token，负 usage 会算出负金额                                                                          | TASK-0140            | accepted，未修复         |

## 与 M20 的映射

| M20 任务  | 覆盖 finding               | 验收要点                                                                                  |
| --------- | -------------------------- | ----------------------------------------------------------------------------------------- |
| TASK-0140 | F-001、F-003、F-004、F-012 | 价格快照时点或 ADR 修订；报表/聊天 PRICED 断言；`first_token_ms`；负 token 归一           |
| TASK-0141 | F-002、F-005               | 真实 MySQL 3s 中止 → `RICCON-COST-503`；平台五类互斥计数与调用量一致性                    |
| TASK-0142 | F-006                      | 过滤器角色校验或 ADR-0009 例外；平台非管理员/`X-Tenant-Id`/其他路径负向测试；独立安全评审 |
| TASK-0143 | F-007、F-008、F-010        | Page `maximum: 200`；AC-12 403/404 口径；tenantId 空串/userId 非法 UUID 契约边界          |

M20 的验收标准明确要求“P2 F-001..F-008 全部 fixed 或由 owner 书面 accepted”“不降低 M04 已验收的 AC-11/AC-12 与 gate 证据”“不修改历史评审与证据”，与本补充的处理一致。

## 证据

- EV-0561、EV-0562、EV-0564：TASK-0108 在 `096f585` 的 API 成本测试、Worker 成本测试与 B2 成本契约测试；`exit_code=0`、`dirty=false`，日志与 JSON 仍在 `.riccon/evidence/TASK-0108/`。
- 本补充只审阅这些 EV 的元数据与任务引用，没有重新执行它们；因此本文件不绑定新的 DoD 证据。

## 未验证

- M20 的修复尚未开始，TASK-0140..0143 没有任何实现或证据；本评审不能预判其通过。
- 未重跑 TASK-0108 定向测试或 M04 模块全量（build/lint/typecheck/test/coverage/E2E/a11y/security/perf）。
- 未在真实 MySQL 上复现 3s 超时（F-002），未运行报表/聊天端到端成本断言（F-003），未做平台非管理员/`X-Tenant-Id` 负向用例（F-006）。

## 剩余风险

- M05 开放价格编辑后，F-001 的在途改价风险会放大；M20 必须在 M05 前关闭或由 Architect 修订 ADR/列注释。
- F-003 若 AgentScope 不填 usage，报表成本会持续静默显示“用量未知”；只有 TASK-0140 的断言能发现。
- F-005 的平台 SQL 与租户 SQL 是两份实现，后续修改仍可能漂移。
- F-006 当前依赖 controller 的 `@RequirePermission` 兜底；若权限链被绕过，过滤器不会阻止伪造 `tenantId` 的平台账号读取。
- F-007/F-010 是契约与实现边界问题，生成客户端可能发送实现会拒绝的参数。
- F-012 只在异常上游数据下出现，但金额列契约不允许负号，写库后可能造成报表解析失败。

## 独立性声明

本评审使用独立会话 `ses_e028b1e0cffegSYGgmhSpG7xDC`，直接读取源码、契约、ADR、验收文档与历史评审，未采信 builder 的总结。未修改任何生产源码、测试、迁移、合同或历史文件，未提交。

## Gate

任务级评审不参与模块 gate。M04 gate 已于 `2026-11-21T16:33:27+08:00` 通过；M20 尚未启动，其 gate 必须由独立的 M20 模块评审在最终提交上重跑受影响测试并验证 F-001..F-008 的 fixed/accepted 状态。
