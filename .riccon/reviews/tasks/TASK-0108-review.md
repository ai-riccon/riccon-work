---
review_id: REV-TASK-0108-001
module_id: M04
task_id: TASK-0108
reviewer:
  agent: riccon/verifier
  session: ses_e04cc589affekHFoeOWfre7303
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 096f585f6aadf4345434a0b83a97b4a5456e414f
head_commit: 096f585f6aadf4345434a0b83a97b4a5456e414f
evidence:
  - EV-0561
  - EV-0562
  - EV-0563
  - EV-0564
reviewed_evidence:
  - EV-0561
  - EV-0562
  - EV-0564
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/builder-cost + riccon/architect（ADR/列注释）"
    due: "M04 gate 前确认；M05 价格编辑前必须修正"
    title: "价格快照读取时点是成功终态，而 ADR-0022 §2.1/架构要求最终成功尝试开始"
    summary: >-
      ModelInvocationService 在成功写入前调用 findPricing（ModelInvocationService.java:162、296-298），
      读到的是调用结束时的 model 价格；ADR-0022:191、docs/ARCHITECTURE.md:427 与 V11 列注释
      （V11__model_gateway_depth.sql:151-152）均写“最终成功尝试开始时”。若调用期间运维修改价格，
      在途调用使用新价格，与“开始时快照”不符。实现只读一次、不会混合两个价格，且 M04 无价格编辑
      端点，影响有界，故判 P2 而非 P1；应在 M05 价格维护前修正，或由 architect 修订 ADR/列注释并在
      B7 closeout 记录。IT-COST-101 只断言“价格更新后历史不变”，不覆盖调用期间改价。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:136、162、296-298；
      docs/adr/ADR-0022-m04-quota-and-cost.md:191；docs/ARCHITECTURE.md:427；
      apps/api/src/main/resources/db/migration/V11__model_gateway_depth.sql:151-152；
      apps/worker/src/test/java/com/riccon/worker/model/ModelCostSnapshotIntegrationTest.java:50-81。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/builder-cost（B7）+ riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "3 秒查询保护只有 mock 端口单测，未在真实 MySQL 证明 3s 中止 → 503"
    summary: >-
      CostUnavailableMappingTest 用手写 CostQueryRepository/PlatformCostQueryRepository 抛
      QueryTimeoutException 验证映射（CostUnavailableMappingTest.java:38-158），没有真实慢查询；
      生产实现只在 SQL 文本中放 /*+ MAX_EXECUTION_TIME(3000) */（CostJdbc.java:37、
      PlatformCostJdbc.java:30）。现有证据不能证明 MySQL 8.4 实际在 3s 中止并把错误翻译为
      DataAccessException 后返回 RICCON-COST-503。180 天/page/size 边界有 HTTP 测试；3s 部分
      建议 B7/V1 用一次性 MySQL 增加真实超时用例或提供服务端证据，否则 AC“3s 超时 → 503”未闭环。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/cost/CostUnavailableMappingTest.java:38-158；
      apps/api/src/main/java/com/riccon/api/cost/persistence/CostJdbc.java:37；
      apps/api/src/main/java/com/riccon/api/admin/cost/persistence/PlatformCostJdbc.java:30；
      apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java:198-213。
  - id: F-003
    severity: P2
    status: open
    owner: "riccon/builder-cost（B7）+ riccon/builder-e2e（B11b）"
    due: "M04 gate / E2E-M04-06 前"
    title: "报表生成路径的 usage→PRICED 成本没有断言；报表调用可能全部停在 USAGE_UNKNOWN"
    summary: >-
      AgentRunResumeHandler 新增从 AgentResultEvent.getResult().getChatUsage() 取 usage
      （AgentRunResumeHandler.java:434-443），但 ReportArtifactIntegrationTest（10 tests）不检查
      model_invocation 的 prompt/completion/cost_status/cost_amount；现有成本测试只直接调用
      ModelInvocationService。若 AgentScope 在该路径不填 usage，报表调用会静默写 USAGE_UNKNOWN，
      AC-11 的报表成本无法证明。需在 worker IT 或 E2E-M04-06 断言生成阶段调用为 PRICED 且
      金额与固定样例一致；聊天路径同理缺少端到端成本断言。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:434-443；
      apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java；
      apps/worker/src/test/java/com/riccon/worker/model/ModelCostSnapshotIntegrationTest.java:50-173。
  - id: F-004
    severity: P2
    status: open
    owner: "riccon/builder-failover（B4）+ riccon/builder-cost（B7）+ riccon/lead"
    due: "M04 gate 前（B4 REV-TASK-0105-003 F-006 的 B7 closeout）"
    title: "B4 F-006 未在 B7 收口：报表 TOOL/GENERATION 调用仍不写 first_token_ms"
    summary: >-
      REV-TASK-0105-003 将“报表 TOOL/GENERATION model_invocation 不记录 first_token_ms”列为
      accepted P2，owner B4+B7，due B7 closeout。096f585 仍以 null 作为 firstTokenMs 调用
      succeeded（AgentRunRequestedHandler.java:348-357、AgentRunResumeHandler.java:434-443），
      没有新增 attempt 首字回填或报表路径断言。B7 closeout 需要修复，或由 Lead 重新 accepted
      并给新到期；否则该 P2 不应随 B7 关闭。
    evidence: >-
      .riccon/reviews/tasks/TASK-0105-review-003.md:76-84、226；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:348-357；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:434-443；
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:150-160。
  - id: F-005
    severity: P2
    status: open
    owner: "riccon/builder-cost（B7）+ riccon/verifier（V2/TEN-033）"
    due: "M04 gate 前"
    title: "平台成本合计的五类互斥计数没有测试；平台 SQL 是租户 SQL 的独立副本"
    summary: >-
      CostAdminApiTest 只插入两条 PRICED 调用并断言 TENANT 金额（CostAdminApiTest.java:28-84），
      没有 UNPRICED/USAGE_UNKNOWN/currencyMismatch/unbucketed，也没有断言五项之和等于范围内
      终态调用；PlatformCostJdbc.totals（:68-95）与 CostJdbc.totals（:53-79）是两份独立 SQL，
      租户侧 IT-COST-102 的五项断言不能覆盖平台副本。建议补平台侧五项计数与调用量一致性用例，
      再由 V2 的 TEN-033 独立复验。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:28-84；
      apps/api/src/main/java/com/riccon/api/admin/cost/persistence/PlatformCostJdbc.java:68-95；
      apps/api/src/main/java/com/riccon/api/cost/persistence/CostJdbc.java:53-79；
      apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java:119-136。
  - id: F-006
    severity: P2
    status: open
    owner: "riccon/architect + riccon/builder-cost（B7）"
    due: "M04 gate 前"
    title: "TenantContextFilter 例外按方法/URI/平台租户放行但不校验角色，ADR-0009 未修订，负向测试不完整"
    summary: >-
      isPlatformCostFilter 只检查 GET + /api/v1/admin/costs + principal.tenantId()=="platform"
      （TenantContextFilter.java:139-143），没有在过滤器内要求平台成本权限；角色仍由
      @RequirePermission(platform.cost.read) 在 PermissionInterceptor 强制，当前无越权读取，
      但安全过滤器在角色校验前放行伪造 tenantId，属 defense-in-depth 缺口。ADR-0009:17 仍写
      查询参数 tenantId 一律忽略/拒绝并审计，未记录本例外，代码注释只引用 ADR-0022 §2.4。
      CostAdminApiTest 只覆盖租户管理员 →403 与平台管理员 →200（:148-169），缺平台非管理员、
      X-Tenant-Id 头、其他路径/方法的负向用例。建议过滤器同时校验权限（或由 architect 记录
      ADR-0009 例外），并补负向测试。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:112-143；
      apps/api/src/main/java/com/riccon/api/auth/controller/PermissionInterceptor.java；
      apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:148-169；
      docs/adr/ADR-0009-tenant-isolation-enforcement.md:17；docs/adr/ADR-0022-m04-quota-and-cost.md:252-259。
  - id: F-007
    severity: P2
    status: open
    owner: "riccon/architect（B2 契约）"
    due: "M04 gate 前"
    title: "OpenAPI 通用 Page 无最大 200，实现按 ADR 拒绝 page=201"
    summary: >-
      ADR-0022 §2.4 要求 page 1..200，CostPage.MAX_PAGE=200（CostPage.java:22），
      CostApiTest 断言 page=201 →400（:202-207）；但 contracts/openapi.yaml:3361-3368 的 Page
      只声明 minimum 1，没有 maximum。按契约生成的客户端会认为 201 合法。需要 B2 为成本端点
      增加带 maximum 200 的参数（或在合同记录该限制），否则契约与实现不一致。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/cost/domain/CostPage.java:16-41；
      contracts/openapi.yaml:3361-3368；apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java:202-213；
      docs/adr/ADR-0022-m04-quota-and-cost.md:234（分页 1..200）。
  - id: F-008
    severity: P2
    status: open
    owner: "riccon/product + riccon/architect + riccon/builder-e2e（B11b）"
    due: "M04-AC-12 / E2E-M04-06"
    title: "AC-12“统一 404”与成本端点伪造 tenantId 的 403 行为不一致"
    summary: >-
      docs/PRODUCT.md:356 AC-12 要求“直接用租户 B 标识请求成本/配额数据；Then 统一 404”；
      CostAdminApiTest:165-168 对 /api/v1/costs?tenantId=other 断言 403 RICCON-AUTH-403
      （TenantContextFilter 的 ADR-0009 行为），而 /costs/invocations?userId=他租户 为 404。
      403 不泄露存在性，安全上可接受；但验收文案需 Product/architect 明确“tenantId 伪造走
      ADR-0009 403、资源 ID 越权走 404”，否则 E2E-M04-06 无法按 AC 原文判定。建议记录解释
      或调整实现/AC。
    evidence: >-
      docs/PRODUCT.md:356；apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:135-140、165-168。
  - id: F-009
    severity: P3
    status: open
    owner: "riccon/builder-cost（B7）+ riccon/lead"
    due: "B7 closeout"
    title: "EV-0561/0562 使用 -q，日志无 Tests run 汇总；EV-0563 为静默日志且已被 EV-0564 取代"
    summary: >-
      EV-0561（57 KB）与 EV-0562（67 KB）exit 0、commit/clean 正确，但日志没有 surefire 汇总；
      EV-0563 输出仅 332 B、无任何测试输出，EV-0564 对同一 ModelContract108bTest 非 -q 运行
      显示 Tests run:1、exit 0，note 也写明 0563 已被取代。本次以 target/surefire-reports 计数
      （CostApi 4、CostAdmin 3、Unavailable 3、ModelCostSnapshot 5、CostDay 2、Billing 2、
      Chat 17、Report 10、Failover 17、Budget 1）和独立重跑 CostAdminApiTest 3/3 通过确认。
      建议后续 EV 保留测试汇总，task.evidence 移除或标注 EV-0563 superseded。
    evidence: >-
      .riccon/evidence/TASK-0108/EV-0561.json、EV-0562.json、EV-0563-*.log、EV-0564.json；
      apps/api/target/surefire-reports/；apps/worker/target/surefire-reports/。
  - id: F-010
    severity: P3
    status: open
    owner: "riccon/builder-cost"
    due: "M04 gate 前"
    title: "tenantId 空串与 userId 非法 UUID 的契约边界未对齐/未测试"
    summary: >-
      OpenAPI tenantId minLength 1（contracts/openapi.yaml:3314-3318），
      PlatformCostService.normalizeTenantFilter 将空串归一为 null → 返回全租户（:122-131）；
      /costs/invocations?userId=not-a-uuid 由 Spring 类型错误映射为 RICCON-REQUEST-400
      （GlobalExceptionHandler.java:101-112），而不是成本错误码。无当前泄露，但契约边界与错误码
      未覆盖，建议补测试或调整参数校验。
    evidence: >-
      contracts/openapi.yaml:3314-3318；apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java:122-131；
      apps/api/src/main/java/com/riccon/api/web/GlobalExceptionHandler.java:101-112。
  - id: F-011
    severity: P3
    status: open
    owner: "riccon/builder-cost"
    due: "B7 closeout / 下一任务"
    title: "高风险代码任务的 tools_used 未记录 Serena 使用或降级理由"
    summary: >-
      TASK-0108.tools_used 仅为 codegraph、git；项目补充规则要求代码任务默认使用 Serena，
      MCP 不可用时记录降级。本次 45 文件/4000+ 行、含安全过滤器与金额逻辑，未记录 Serena 使用
      或降级原因，无法核对是否按团队规则完成符号级修改。非代码缺陷，按 P3 记录。
    evidence: ".riccon/tasks/TASK-0108.json（tools_used）；AGENTS.md「工具使用（Serena / CodeGraph）」；docs/COLLABORATION.md。"
  - id: F-012
    severity: P3
    status: open
    owner: "riccon/builder-cost"
    due: "M05 前或 B7 closeout"
    title: "provider 返回负 token 时会生成负金额，违反 CostTotals.amount 的契约 pattern"
    summary: >-
      costSnapshot 直接 BigDecimal.valueOf(promptTokens) 计算（ModelInvocationService.java:310-312），
      未校验非负；若上游返回负 usage，cost_amount 为负数，而 OpenAPI amount pattern
      ^\d{1,14}(\.\d{1,6})?$ 不含负号。正常 provider 不会返回负数，按 P3 记录；建议成功写库前
      拒绝负 token 或归一为 USAGE_UNKNOWN。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:296-314；
      contracts/openapi.yaml:5884-5887（CostTotals.amount）。
created_at: "2026-11-21T07:17:08+08:00"
---

# REV-TASK-0108-001：TASK-0108 / M04 B7 独立评审

本文件是任务级独立评审，不参与模块 gate；模块级 AC/E2E/覆盖率仍由 M04 模块 review 与 V1/V2 承担。

## 结论

**pass（任务级）**。提交 `096f585` 在写范围内实现了成本快照写入、租户/平台成本聚合与明细 API、查询保护、指标和边界测试；未发现 P0/P1。存在 8 个 P2（价格快照时点、3s 保护证据、报表成本断言、B4 F-006 收口、平台五类计数、过滤器角色/ADR、Page 契约、AC-12 403/404 口径）与 4 个 P3，需在模块 gate 前登记/接受或修复。

## 评审对象与范围

- 评审提交：`096f585f6aadf4345434a0b83a97b4a5456e414f`，parent `98fbcc3`。
- `git diff --name-only 98fbcc3..096f585` 与 `git show --name-only 096f585` 均为 45 个文件，集合一致；逐条匹配 `write_scope` 全部通过，无越界文件。
- 未改 `contracts/`、`apps/api/src/main/resources/db/migration/`、`apps/web/`；改动了 B4 的 `ModelInvocationService`/调用链（在 scope 内）和 `TenantContextFilter`（TASK-0108 blocker 记录由 Lead 加入 scope）。
- 无生产密钥；diff 中仅测试夹具密码 `member-password-12`。`git show --check` 无空白错误；`./mvnw -q spotless:check` exit 0。

## 验证动作

| 项                       | 命令/证据                                                                                             | 结果                                                                                                                                           |
| ------------------------ | ----------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 范围/基线                | `git show --stat 096f585`、`git diff --name-only 98fbcc3..096f585`                                    | 45 文件，等于提交，全部在 write_scope                                                                                                          |
| 证据完整性               | EV-0561/0562/0564 JSON + 日志                                                                         | exit 0、manual=false、dirty=false、commit=096f585；EV-0563 superseded                                                                          |
| 独立定向测试             | `./mvnw -T1C -pl apps/api -am test -Dtest='CostAdminApiTest' -Dsurefire.failIfNoSpecifiedTests=false` | Tests run: 3, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS                                                                                |
| 格式                     | `./mvnw -q spotless:check`                                                                            | exit 0                                                                                                                                         |
| 契约                     | `contracts/openapi.yaml` 成本 schema + `ModelContract108bTest`                                        | 端点/权限/字段与实现基本一致；Page 上限见 F-007                                                                                                |
| 测试计数（builder 运行） | `apps/*/target/surefire-reports`                                                                      | CostApi 4、CostAdmin 3、Unavailable 3、ModelCostSnapshot 5、CostDay 2、Billing 2、Chat 17、Report 10、Failover 17、Budget 1，均 0 failure/skip |

未跑模块全量（build/lint/typecheck/coverage/E2E/a11y/security/perf），按任务级验证节奏只跑受影响定向测试；这些属于 M04 模块 gate，不由本 review 代替。

## 验收逐条

### 1. 价格快照 + 6 位小数 + 未定价/用量未知/unbucketed 分桶

- **通过（含 F-001 时点偏差）**。`ModelCostSnapshotIntegrationTest` 断言成功调用写入输入/输出价格、币种 `CNY`、`pricing_version=v1`、6 位 HALF_UP 金额（1000×3+500×6=0.006000；0.0000025→0.000003）、`UNPRICED` 与 `USAGE_UNKNOWN` 不写金额、失败调用只写 `USAGE_UNKNOWN` + `cost_day`、同一调用仅 1 行（ModelCostSnapshotIntegrationTest.java:50-173）。API 侧 `CostApiTest` 断言 `PRICED/UNPRICED/USAGE_UNKNOWN/currencyMismatch/unbucketed` 五项各 1 且合计 5 条明细（CostApiTest.java:96-148）。
- 公式实现与 ADR-0022 §2.2 一致：先求分子和、6 位 HALF_UP 一次（ModelInvocationService.java:310-313）；金额/价格/币种以字符串输出（CostFormat.java:40-55），合计对已存 6 位值求和，不按展示 2 位先舍入。
- 偏差：价格读取在成功终态而非“最终成功尝试开始”（F-001）；`ModelInvocationService` 注释也明确写“成功终态读取一次”。

### 2. 历史不改写（价格更新后金额不变）

- **通过**。API 测试更新 `model` 价格后重新查询，`totals.amount` 与桶金额仍为 `0.003000`（CostApiTest.java:69-78）；worker 测试断言更新价格后调用行整行不变、下一次调用使用新价格（ModelCostSnapshotIntegrationTest.java:74-80）。查询只读 `model_invocation` 快照列，`CostJdbc`/`PlatformCostJdbc` 不 join 当前价格（CostJdbc.java:43-50、145-160；PlatformCostJdbc.java:36-43）。
- V11 历史行保持 `cost_day=NULL`、`cost_status=USAGE_UNKNOWN`，报告计入 `unbucketedCalls`（CostJdbc.java:53-79；CostApiTest.java:117、137-151）。

### 3. 日桶 `Asia/Shanghai` 边界

- **通过**。`ModelInvocationService.costDay` 使用 `ZonedDateTime`（ModelInvocationService.java:320-325）；`ModelInvocationCostDayTest` 覆盖 23:59:59.999→当日、次日 00:00→次日、跨月/跨年（2 tests）。API 侧用 `2026-11-30T15:59:59.999Z` 与 `16:00:00Z` 断言 Nov 30/Dec 1 分桶（CostApiTest.java:159-196）。查询范围按北京时间自然日转 UTC 半开区间（CostRange.java:39-50、73-84）。

### 4. 平台无用户字段、租户明细、跨租户 404

- **通过（含 F-008 口径澄清）**。平台 `PlatformCostBucket`/`PlatformCostReport` 结构上无 `userId`/`userDisplayName`，测试对 TENANT/MODEL/DAY 三个维度断言不存在用户字段（CostAdminApiTest.java:57-83）；租户报告只含本租户用户，`userId` 过滤他租户返回 404 `RICCON-TENANT-404`（CostAdminApiTest.java:89-141）。员工 403、租户管理员不能访问平台端点、平台管理员不能访问租户端点、平台 `groupBy=USER` 返回 400（CostAdminApiTest.java:148-169）。
- 注意：`/api/v1/costs?tenantId=other` 由安全过滤器返回 403，而 AC-12 原文写“统一 404”；见 F-008。该差异不泄露存在性，但需在模块 AC/E2E 口径中定稿。

### 5. 注册 `riccon_cost_report_queries_total{group_by}` + 查询保护（180 天/page/size/3s 超时 → 503）

- **指标：通过**。`CostMetrics` 预注册 USER/MODEL/DAY/TENANT 固定标签（CostMetrics.java:31-42）；`CostApiTest` 断言 Micrometer 计数与 Prometheus 导出 `riccon_cost_report_queries_total{group_by="USER"/"DAY"}`（CostApiTest.java:244-252）。标签不含租户/用户/金额，无高基数泄露。
- **180 天/page/size：通过**。181 天、`from>to`、非法日期、非法维度、`page=201`、`size=101` 均返回 400 `RICCON-COST-400`（CostApiTest.java:198-213）。实现 `CostRange.MAX_DAYS=180`、`CostPage.MAX_PAGE=200`、`MAX_SIZE=100`。
- **3s 超时：部分通过（F-002）**。SQL 带 `MAX_EXECUTION_TIME(3000)` 提示，`QueryTimeoutException` 映射 `RICCON-COST-503` 有 mock 单测；未在真实 MySQL 证明 3s 中止与 HTTP 503。

## 与 M04 AC / 测试 ID 的对应

| 项                                                                     | 本任务证据                                                       | 结论                                                      |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------- | --------------------------------------------------------- |
| M04-AC-11（计算公式、快照、6 位、未定价/未知、固定文案、单币种、日桶） | ModelCostSnapshotIntegrationTest、CostApiTest、CostFormat        | 后端通过；前端展示舍入/E2E 归 B9b/B11b；F-001             |
| M04-AC-12（平台无用户明细、租户明细、员工 403、跨租户 404）            | CostAdminApiTest、CostApiTest                                    | 后端通过；`tenantId` 403 vs AC 404 见 F-008；E2E 归 B11b  |
| IT-COST-101                                                            | ModelCostSnapshotIntegrationTest 两个用例 + CostApiTest 快照用例 | 通过（展示舍入/合计未按展示舍入未单独断言，属 P3 级缺口） |
| IT-COST-102                                                            | ModelCostSnapshotIntegrationTest 三个用例 + CostApiTest 五项分桶 | 通过；平台侧未覆盖（F-005）                               |
| IT-COST-103                                                            | ModelInvocationCostDayTest 两个用例 + CostApiTest 边界/未分桶    | 通过                                                      |
| IT-COST-104                                                            | CostAdminApiTest 三个用例 + CostApiTest USER/指标                | 通过；TEN-033 正式独立证据归 V2，不用本任务替代           |
| E2E-M04-06                                                             | 未运行                                                           | 归 B11b，本任务不声明                                     |

## Findings

### P0

无。

### P1

无。

### P2

见 front matter F-001..F-008。要点：

1. **F-001 价格快照时点**：成功终态读取，与 ADR/列注释的“最终成功尝试开始”不一致；M04 无价格编辑、只读一次、无混合价格，判 P2 而非 P1，M05 前必须修正或修订 ADR。
2. **F-002 3s 超时**：只有 mock 映射，无真实 MySQL 3s 中止证据。
3. **F-003 报表成本**：新增 usage 提取无成本断言，报表 PRICED 不可证。
4. **F-004 B4 F-006**：报表 first_token 仍未写，accepted P2 的 B7 closeout 未完成。
5. **F-005 平台五项计数**：平台 SQL 独立副本，缺五项/调用量一致性测试。
6. **F-006 过滤器例外**：路径/方法/平台租户限定正确、权限由 controller 强制，但过滤器不校验角色、ADR-0009 未修订、负向测试不完整。
7. **F-007 Page 契约**：OpenAPI 无 maximum 200，实现按 ADR 拒绝 201。
8. **F-008 AC-12 403/404**：伪造 `tenantId` 403 vs 资源 ID 404，需 Product/architect 定稿。

### P3

见 front matter F-009..F-012（EV 质量、tenantId 空串/userId 非法 UUID、Serena 降级未记录、负 token 金额）。

## 未验证

- M04 模块级全量 build/lint/typecheck/coverage/E2E/a11y/security/perf：未运行，按任务级节奏只做定向验证。
- E2E-M04-06（成本页平台/租户视图、未定价/unknown/unbucketed、明细展开）与 B9b 前端：不在本任务。
- 真实 MySQL 3s 超时中止（F-002）；平台非管理员/`X-Tenant-Id` 负例（F-006）；报表/聊天端到端 PRICED 断言（F-003）。
- V11 迁移 forward/rollback 与历史 NULL 行为：属 B1a/模块 gate，本提交未改迁移。
- TEN-033 的独立 V2 证据：本任务的 `CostAdminApiTest` 只是成本子集，不能替代 V2。

## 剩余风险

- 报表调用若 AgentScope 不填 usage，成本会静默停留在 `USAGE_UNKNOWN`；只有 F-003 的断言能发现。
- 调用期间改价时，在途调用按终态价格计价；M05 开放价格编辑后风险放大。
- 平台合计 SQL 与租户合计 SQL 两份实现，后续修改可能漂移；F-005 需平台侧测试。
- USER 维度不包含 `user_id IS NULL` 的历史/系统调用，items 合计可能小于 totals；属已接受的数据归属限制，UI 需显示 unbucketed/未知口径。
- `model_invocation.provider_id` 由 B4 负责，目前仍未写入；不影响成本金额，但影响后续按 provider 归因，模块 gate 前需确认是否另行登记。

## 独立性声明

本评审使用新会话，直接读取提交 diff、ADR-0022、PRODUCT AC、V11 与测试代码，未采信 builder 总结；独立重跑了 `CostAdminApiTest`（3/3 通过）并核对 surefire 报告，未修改生产源码、测试、迁移或合同，未提交。

## Gate

任务评审不参与模块 gate，M04 仍为 `building`，本文件不标记任务 done；M04 模块 gate 仍须由 V1/V2 在最终提交上跑全量并绑定 `dod_evidence`。F-001..F-008 为 P2，其中 F-002/F-003/F-004/F-005/F-006/F-007/F-008 建议在模块 gate 前由 Lead 明确 accepted 或修复。
