---
review_id: REV-TASK-0103-001
module_id: M04
task_id: TASK-0103
reviewer:
  agent: riccon/verifier
  session: ses_e05eb9fecffeE1MdJRsf8d5JT2
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 4752c4f34d258fa649a74d072c992be8a7077c1a
  - 9b5d336471985b6cb6d51be8fdb3108b3a719a59
  - 76bfb225268f2982efdf30f94777385dd9d50daf
  - 46a6cebcd1d0fbba59c93aca3e615d6e5aa786ad
head_commit: 46a6cebcd1d0fbba59c93aca3e615d6e5aa786ad
head_at_review: 10fbba2c27b1de7b4c4f8d99ad244dfc91804ce9
task_end_commit: 46a6cebcd1d0fbba59c93aca3e615d6e5aa786ad
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/lead + riccon/builder-route（TASK-0103 收口）"
    title: "高风险任务最终提交不是全量绿：EV-0541 仅定向，46a6ceb 上既有 CryptoStartupValidationTest 失败"
    summary: >-
      TASK-0103 是 T4 安全/跨模块契约任务。按 `docs/CODING-STANDARDS.md` §10.1，高风险任务应在最终提交上
      跑一次受影响模块全量；EV-0541 只跑定向 62 测试与 platform-common verify，EV-0537 全量在 617fa4b
      （B2 之前），没有覆盖 B2。实际在任务 end_commit 46a6ceb 上，TASK-0101 的 EV-0542（clean、exit 1）
      显示 `CryptoStartupValidationTest.startupFailure_neverLogsMasterKey:229` 失败：B2 的
      `ModelEndpointProperties` 在 PRODUCTION 默认下强制要求 `RICCON_MODEL_ENDPOINTS`，该既有启动用例未提供
      端点，根因不再是 `MasterKeyConfigException`。修复提交 b09aca2（属于 TASK-0101）位于 46a6ceb 之后，
      TASK-0103 的 end_commit/evidence 未更新；`gate check --module M04` 因此把
      `apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java` 报为 TASK-0103
      out-of-scope。结论：任务证据不能证明最终提交全绿，任务不能 done。
    evidence: >-
      `.riccon/evidence/TASK-0103/EV-0541.json`（command 为定向 `-Dtest=...` + platform-common verify；
      commit 46a6ceb、dirty=false、exit 0）；
      `.riccon/evidence/TASK-0101/EV-0542-*.log:204,215`（`CryptoStartupValidationTest.startupFailure_neverLogsMasterKey`
      FAILURE，exit 1，commit 46a6ceb）；
      `apps/api/src/main/java/com/riccon/api/model/catalog/ModelEndpointProperties.java:111-128`（PRODUCTION 空端点启动失败）；
      `apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java:215-232`（46a6ceb 上未提供端点）；
      `.riccon/evidence/TASK-0101/EV-0543.json`（b09aca2、clean、exit 0，修复后 crypto 定向通过）；
      `git show --stat b09aca2`、`git log --oneline -4`；
      `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04`（2026-11-21T02:03+08:00）
      输出 `task TASK-0103 changed files outside write_scope: apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java`。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/lead + riccon/architect（审计动作/目标类型决策）；riccon/builder-route"
    title: "provider 改名（platform.model.manage 写操作）没有审计"
    summary: >-
      `PUT /api/v1/admin/model-providers/{providerId}` 只允许改 name，但 `ModelProviderService.renameProvider`
      在事务中只做 find/update/find，没有写任何审计。`AuditActions` 没有 provider 改名动作，`AuditTargetType`
      没有 provider；ADR-0021 §7.4 的动作目录也未定义该动作。结果是平台管理员执行的特权模型目录写操作完全无痕，
      与 AGENTS 合同 `constraints.security`“全链路审计”、`docs/CODING-STANDARDS.md` §12“模型、工具、MCP、权限、
      租户变更必须审计”存在张力。PRODUCT §10.4 的 M04 审计最小集未点名 provider 改名，ADR §7.4 又是当前批准
      的审计白名单，因此本条按 P2（设计缺口）记录；若 Lead 按合同硬规则从严解释，应升为 P1 并在 M04 gate 前
      补齐动作/目标类型、AuditDetailsPolicy 与测试，或形成明确的书面范围排除决定。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/admin/model/provider/service/ModelProviderService.java:71-78`（无 auditService 调用）；
      `apps/api/src/main/java/com/riccon/api/admin/model/provider/controller/ModelProviderAdminController.java:74-105`；
      `platform/platform-domain/src/main/java/com/riccon/platform/domain/audit/AuditActions.java`（无 provider 动作）；
      `platform/platform-domain/src/main/java/com/riccon/platform/domain/audit/AuditTargetType.java`（无 MODEL_PROVIDER）；
      `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md` §7.4；`docs/PRODUCT.md` §10.4；`AGENTS.md` constraints.security。
  - id: F-003
    severity: P2
    status: open
    owner: "riccon/builder-route + riccon/lead"
    title: "API 匿名非允许来源访问 /actuator/prometheus 返回 401，未满足 ADR 的 403/404"
    summary: >-
      API 用 `AuthorizationManager` 做来源过滤，匿名请求被拒时 Spring Security 走
      `JsonAuthenticationEntryPoint`，返回 401 `RICCON-AUTH-401`；ADR-0021 §2.2 明确要求非允许来源返回 404/403。
      `PrometheusExposureDenyTest.anonymousScrapeIsDenied` 断言 `in(401, 403, 404)`，把该偏差固化进测试；已认证
      平台管理员仍是 403。风险：状态码与 ADR 不一致，401 会暴露“该路径存在且需要认证”；响应体不含指标样本，
      不是访问绕过，也不是指标泄露。修复建议：为 `/actuator/prometheus` 提供统一的来源拒绝入口（例如在安全链
      前返回 404/403），或由 Lead/Architect 更新 ADR 明确 401 可接受并同步测试。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:149-151`（`.access(prometheusAccess(...))`）；
      `apps/api/src/main/java/com/riccon/api/web/JsonAuthenticationEntryPoint.java:19-54`（401）；
      `apps/api/src/test/java/com/riccon/api/actuator/PrometheusExposureDenyTest.java:50-62`（允许 401/403/404）；
      ADR-0021 §2.2“非允许来源返回 404/403”。
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder-route + riccon/ops（B10/RUNBOOK）"
    title: "缺少 ADR 要求的启动告警：PRODUCTION 空 RICCON_OPS_ALLOWED_CIDRS、端点配置与 DB 目录不一致"
    summary: >-
      ADR-0021 §2.2 要求 `PRODUCTION` 下 `RICCON_OPS_ALLOWED_CIDRS` 为空时启动 WARN；§2.1 要求“配置有、DB 无”
      与 baseUrl 不一致时启动告警。B2 只实现来源过滤和 `allowed` 计算，`SecurityConfig`/`ManagedEndpointRegistry`/
      `ModelProviderService` 均未输出这些告警。安全行为不受影响，但 Ops 无法在启动日志发现“生产只允许 loopback”
      或目录漂移。建议 B2 补 WARN（不输出 URL），或在 B10 启动检查中覆盖。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:64-81`（OpsProperties/prometheusAccess，无日志）；
      `apps/worker/src/main/java/com/riccon/worker/config/SecurityConfig.java:35-82`；
      `platform/platform-common/src/main/java/com/riccon/platform/common/model/ManagedEndpointRegistry.java:41-43`（仅注释）；
      `apps/api/src/main/java/com/riccon/api/admin/model/provider/service/ModelProviderService.java:86-106`（只计算 allowed，无告警）；
      ADR-0021 §2.1/§2.2。
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/builder-route"
    title: "model.updated 审计失败回滚没有负向测试"
    summary: >-
      `PlatformModelService.updateStatus` 与 `AuditService.append` 在同一事务（append 为 REQUIRED），实现路径
      是 fail-closed；但 `ModelCatalogApiTest.patchModelTogglesStatusAndAudits` 只覆盖成功审计，没有像
      `AuditFailClosedTest` 那样注入 AuditRepository 异常并断言 status 更新回滚。该任务唯一的模型写操作缺少
      fail-closed 回归保护，后续改动可能把审计移出事务而不被发现。建议补 `@MockitoBean AuditRepository`
      的 PATCH 失败用例，断言 500 且 DB 状态保持原值。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/admin/model/catalog/service/PlatformModelService.java:108-115`；
      `apps/api/src/main/java/com/riccon/api/audit/service/AuditService.java:35-37`；
      `apps/api/src/test/java/com/riccon/api/model/catalog/ModelCatalogApiTest.java:174-204`（仅成功路径）；
      参考 `apps/api/src/test/java/com/riccon/api/AuditFailClosedTest.java:31-50`。
  - id: F-006
    severity: P3
    status: open
    owner: "riccon/builder-route + riccon/ops"
    title: "Worker 空 allowed-cidrs 的 loopback 正例没有安全链集成测试；API/Worker CIDR 实现重复"
    summary: >-
      API 有 `PrometheusLoopbackAccessTest` 证明空配置下 127.0.0.1 可抓取；Worker 只有 `OpsAllowedCidrsTest`
      的纯单元测试和 `PrometheusExposureDenyTest` 的非允许来源负例，没有“空配置 + loopback 200”的 MockMvc 集成
      正例。ADR §2.2 的空=loopback 语义在 Worker 安全链上未被集成证明。另 API/Worker 各自复制了一份
      `AllowedCidrs`/`Cidr` 实现（约 150 行），后续只改一侧会造成语义漂移（B1c F-002 类问题）。建议补 Worker
      正例，并考虑抽到 platform-common 或加交叉测试。
    evidence: >-
      `apps/api/src/test/java/com/riccon/api/config/PrometheusLoopbackAccessTest.java:22-30`（API 正例）；
      `apps/worker/src/test/java/com/riccon/worker/config/OpsAllowedCidrsTest.java:16-35`（Worker 仅单元）；
      `apps/worker/src/test/java/com/riccon/worker/actuator/PrometheusExposureDenyTest.java:54-68`（Worker 仅负例）；
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:258-416` 与
      `apps/worker/src/main/java/com/riccon/worker/config/SecurityConfig.java:84-240`（重复实现）。
  - id: F-007
    severity: P3
    status: open
    owner: "riccon/builder-route（补测试）"
    title: "安全关键类 ManagedEndpointPolicy 类级分支覆盖率 66.8%；未覆盖 0.0.0.0/:: 等边界"
    summary: >-
      EV-0541 生成的 platform-common jacoco 显示 bundle line 90.69% / branch 75.41%，满足合同 80/70；
      但 `ManagedEndpointPolicy` 类级 line 80.8% / branch 66.8%（187/280），低于类级 70%。该类是 SSRF 允许列表
      的唯一校验点，未覆盖分支集中在配置错误/边界路径。另 `0.0.0.0`、`::` 等 unspecified 地址未被拒绝
      （ADR 未点名，但 Linux 上连接 0.0.0.0 可能命中本机服务，属于纵深防御缺口）。建议补：
      无效端口/空 host/配置 null、unspecified 地址、DNS 解析空数组、IPv6 link-local 字面量等用例。
    evidence: >-
      `platform/platform-common/target/site/jacoco/jacoco.csv`（ManagedEndpointPolicy line 206/255、branch 187/280；
      bundle 90.69/75.41，2026-11-21T01:45:53）；
      `platform/platform-common/src/main/java/com/riccon/platform/common/model/ManagedEndpointPolicy.java:390-443`
      （parseIpLiteral 未处理 0.0.0.0/::）、`:366-379`（rejectLiteralAddress）。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/lead（closeout 说明）"
    title: "B2 修改了 B1a/B1c 拥有的测试文件，ADR §8.3 原始 scope 不含；scope 补录已在 10fbba2 提交"
    summary: >-
      4752c4f 修改了 `WorkerIntegrationTestBase`（ADR 归 B1a）、worker `PrometheusExposureDenyTest`（B1c）
      和下沉到 platform-common 的 `ManagedEndpointPolicyTest`；ADR §8.3 的 B2 原始 write_scope 不含这些路径。
      任务 JSON 随 10fbba2 提交补录了 scope，且 TASK-0102 评审 F-001 明确把 CIDR 测试修复指派给 B2/TASK-0103，
      因此按“已授权的 F-001 修复 + 已提交 scope 补录”接受为 P3 流程债，不作为越界失败；但 ADR §8.3 与任务卡
      仍不一致，Lead 应在 closeout 说明该例外，避免后续任务照此扩大 scope。
    evidence: >-
      `git show 4752c4f -- apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java
      apps/worker/src/test/java/com/riccon/worker/actuator/PrometheusExposureDenyTest.java`；
      `.riccon/tasks/TASK-0103.json:24-28`（10fbba2 提交的补充 scope）；
      `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md` §8.3 B2 行；
      `.riccon/reviews/tasks/TASK-0102-review.md` F-001。
evidence:
  - EV-0537
  - EV-0538
  - EV-0539
  - EV-0540
  - EV-0541
  - EV-0542
  - EV-0543
reviewed_evidence:
  - EV-0537
  - EV-0541
  - EV-0542
  - EV-0543
notes: >-
  本评审只针对 TASK-0103 的 4 个提交与任务证据；HEAD 10fbba2 是 TASK-0101 的 b09aca2 修复与 .riccon 元数据提交，
  不属于本任务 reviewed_commits。任务级评审不替代 M04 模块评审、Product 预验收或用户验收；M04 gate 当前仍应失败。
created_at: "2026-11-21T02:06:00+08:00"
---

# REV-TASK-0103-001：TASK-0103 / M04-B2 独立评审

本评审是任务级独立技术验收，不修改代码/契约，不提交，不标 done；不替代 M04 模块评审、Product 预验收或用户产品验收。

## 结论

**fail（任务级）**。6 条验收的代码与定向测试基本成立，但存在 1 个 P1：高风险任务的 `end_commit=46a6ceb` 未跑受影响模块全量，且该提交上既有 `CryptoStartupValidationTest.startupFailure_neverLogsMasterKey` 明确失败（TASK-0101 EV-0542 exit 1）；修复 b09aca2 在任务范围之后，TASK-0103 的 end_commit/evidence 未更新，gate 还把该文件报为 TASK-0103 out-of-scope。P1 未关，任务不能 done。另有 2 个 P2（provider 改名无审计、API 匿名来源 401 偏离 ADR 403/404）和 5 个 P3。

## 逐条验收（6 条）

| #   | 验收原文                                                                                                | 结论                                     | 依据                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| --- | ------------------------------------------------------------------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `IT-MODEL-108a`（三个模型端点 + 路由/凭据/开关契约测试）                                                | pass                                     | `ModelContract108aTest` 单方法覆盖：`1.3.0-m04`；`GET /models`/`model.read`/`ModelPage`；`EmployeeModel` required 且不含价格/provider/baseUrl；`GET /admin/model-providers`/`platform.model.read`/`ModelProvider`；PUT 只允许 name、三个 readOnly 字段；`GET /admin/models`/`platform.model.read`/`AdminModel` 含价格与 `pricingStatus`；PATCH `platform.model.manage` + CsrfToken；路由 1..20/unique、凭据 rotate/disable/enable、开关 GET/PUT；MCP `x-riccon-deferred: M07`；ProblemDetail 含 field/reason；M02/M03/M10 代表路径存在。EV-0541 报 1 run / 0 fail / 0 skip；本次独立复跑 1 run / 0 fail。                                                                                                                                                                                                                                                                       |
| 2   | `IT-MODEL-108b`（配额/成本端点契约测试，同一 `1.3.0-m04` 全量、无 deferred 分段）                       | pass                                     | `ModelContract108bTest` 单方法覆盖 8 个配额/成本路径的 operation 级无 deferred；配额/成本权限、CSRF、`QuotaWindowState`/`QuotaUpdateRequest`/`PlatformQuotaRow`/`QuotaExceededProblem`/`RateLimitedProblem`；成本 `CostTotals` 6 位小数、三类 pricingStatus、租户/平台 bucket 可见性、`PlatformCostReport` items 只引用 PlatformCostBucket。EV-0541 报 1 run / 0 fail / 0 skip；本次独立复跑 1 run / 0 fail。B6/B7 业务实现不在本任务，未调用配额/成本运行时端点（见“未验证项”）。                                                                                                                                                                                                                                                                                                                                                                                              |
| 3   | 端点标签/允许状态、只读字段 400、生产模式拒绝演示端点、URL 负向矩阵（含元数据/IP 归一化）、405 无写路由 | pass                                     | `ProductionEndpointPolicyApiTest`：PRODUCTION 下员工目录排除 DEMO_ONLY、平台目录 `allowed=false`；`ModelCatalogApiTest`：员工目录无 provider/baseUrl/价格、平台目录含标签/价格/定价状态、PATCH 启停 + `model.updated`、非法状态 400 `field=status`/`reason=INVALID_STATUS`、PUT 只改名 + `baseUrl` 非 HTTPS/合法不可写/`environmentLabel`/`protocol` 均 400 且 field 指向、POST provider 405 + `Allow` + `RICCON-METHOD-405`、租户管理员 403；`ManagedEndpointPolicyTest` 14 用例覆盖 HTTPS/DEMO HTTP、userinfo/query/fragment、127.0.0.0/8/::1/localhost、169.254.169.254/169.254.170.2/100.100.100.200/192.0.0.192/0x/n 段/八进制/单整数/0xa9fea9fe/`::ffff:a9fe:a9fe`/`fd00:ec2::254`/`metadata.google.internal.`、DNS fixed map 到元数据/回环、私网允许/无法解析拒绝、重复 code/baseUrl、非法 code/标签、空允许列表遗留、规范化。EV-0541 与本次独立复跑均 0 fail / 0 skip。 |
| 4   | 内层 `model.max-attempts=1`                                                                             | pass                                     | `InnerModelRetryConfigTest` 直接读 `apps/worker/src/main/resources/application.yml`，断言 `riccon.worker.model.max-attempts=1` 且 `routing.max-attempts=${RICCON_MODEL_ROUTING_MAX_ATTEMPTS:3}`；EV-0541 1 run / 0 fail；本次独立复跑 1 run / 0 fail。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| 5   | Actuator prometheus 按 `RICCON_OPS_ALLOWED_CIDRS` 控制（空=loopback）且不放宽默认拒绝                   | pass（核心；P2/P3 见 F-003/F-004/F-006） | API `PrometheusLoopbackAccessTest` 空配置 127.0.0.1 200 且业务路径仍 401；API/Worker `PrometheusExposureDenyTest` 显式 10.99.0.0/16 拒绝非允许来源、响应无 `# HELP`/`# TYPE`/`riccon_*` 样本；两侧 `anyRequest().denyAll()` 未改；`OpsAllowedCidrsTest` 空/显式/IPv6/非法 CIDR；B1c F-001 已真实修复：`WorkerIntegrationTestBase` 不再注册 `riccon.ops.allowed-cidrs` 动态属性，Worker 负向测试由 401/403/404 收紧为精确 403 并通过。剩余偏差：API 匿名返回 401（F-003）、缺少 PRODUCTION 空值 WARN（F-004）、Worker 无 loopback 正例（F-006）。                                                                                                                                                                                                                                                                                                                                |
| 6   | M04 契约唯一 writer（含 `contracts/README.md`），M02/M03/M10 契约零回归                                 | pass                                     | `git log -- contracts/openapi.yaml contracts/README.md` 在 M04 实现期只有 4752c4f；`git show 4752c4f -- contracts/openapi.yaml` 只新增 `ProblemDetail.field`/`reason` 两个可选 nullable 属性，没有删除/改语义；README 增加 108a/108b/运行时测试位置与 B2 独占 writer 说明。108a 断言版本 `1.3.0-m04` 与 M02/M03/M10 代表路径；MCP 两个 operation 仍 `x-riccon-deferred: M07`；根 deferred 含 byo-model-credentials/model-price-editing，不含 model-routing-quota。未发现其他 M04 任务写契约。                                                                                                                                                                                                                                                                                                                                                                                   |

## 重点结论

### 端点允许列表与 URL 校验

- `ManagedEndpointPolicy` 是纯 JDK、API/Worker 共用实现；`load` 校验 code、标签、URL、DNS 与重复项，`validateUrl` 只做结构校验（管理 PUT 不发起 DNS），`validateConfiguredUrl`/`verifyResolvedHost` 做解析后拒绝。`HostResolver` seam 让测试完全用固定 map/字面量，不依赖外部 DNS；测试覆盖 inet_aton 的 1–4 段、hex/octal、IPv4-mapped IPv6、末尾点 FQDN、阿里云/Oracle/AWS IPv6 元数据。未发现可绕过 allowlist 将用户输入变成出网的写路径：没有端点创建 API，PUT 只改 name，`baseUrl` 即使结构合法也返回 `ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED`。
- 生产/演示标签由部署配置派生，员工目录按 code + baseUrl + 标签 + 模型/provider ACTIVE + 协议过滤；平台目录展示 `allowed`/`environmentLabel`；DB 行不被自动改写。`legacyCatalog` 只在空允许列表（DEMO 或显式风险接受）返回 true，符合 ADR §2.1。
- 残余：`0.0.0.0`/`::` unspecified 未拒绝（F-007，P3）；DNS rebinding 仍依赖 TLS/禁重定向/出网策略（ADR R-23 已接受）；私网 IP 按 ADR 允许。`ManagedEndpointPolicy` 类级分支覆盖 66.8%，bundle 75.41% 过线（F-007）。

### 三模型端点与权限

- `GET /api/v1/models` 使用独立 `EmployeeModelResponse`，只含 id/modelKey/displayName/contextWindow/maxOutputTokens/capabilities/status；`GET /admin/model-providers` 含 baseUrl/label/allowed/key 数量/model 数量；`GET /admin/models` 含 provider、价格 6 位小数字符串、币种、`pricingStatus`。`PATCH /admin/models/{id}` 只接受 ACTIVE/DISABLED，更新与 `model.updated` 审计同事务，审计 details 通过 `AuditDetailsPolicy` 白名单（modelId/status/providerId），平台租户、USER actor；停用后员工目录立即不返回，启用后恢复。
- 权限：员工/租户管理员无平台写权限（测试覆盖租户管理员 GET 403）；`PUT`/`PATCH` 都有 CSRF 与 `platform.model.manage`；405 无端点创建路由。跨租户统一 404 由平台全局目录 + `@RequirePermission` 承担；本任务没有租户级模型对象，未单独造跨租户 404 用例（V1 的 SEC-M04-003a/TEN-031 负责）。
- 审计失败回滚实现为同事务（`@Transactional` + `AuditService.append` REQUIRED），但本任务没有负向注入测试（F-005，P3）。

### 契约与 Actuator

- 契约 `1.3.0-m04` 全量：108a/108b 同版本、无 deferred 分段；MCP 两 operation 标 M07；ProblemDetail 仅新增可选 `field`/`reason`，`additionalProperties: false` 下属于向后兼容增量。`ProblemDetail` 新字段未被任何既有 schema 引用破坏。
- Actuator：API/Worker 都只对 `/actuator/prometheus` 加来源 `access`，不是 `permitAll`；健康检查保持公开；`anyRequest().denyAll()` 原样保留。B1c F-001 的修复真实：删除了基类动态属性，子类 `@TestPropertySource` 生效，Worker 负向测试从宽松 401/403/404 收紧为 403 并实际通过。API 匿名仍 401（F-003，P2）；PRODUCTION 空值 WARN 缺失（F-004，P3）。

### 证据、范围与秘密

- EV-0541：manual=false、dirty=false、exit 0、commit 46a6ceb、sha256 匹配；命令是定向 `-Dtest=...` + `platform-common verify`。按 surefire 报告合计 62 个测试（API 21、Worker 15、platform-common 14、ArchUnit 12），任务 note 写“59 测试”有 3 个偏差，建议校正；未发现失败/跳过。platform-common 覆盖率 90.69% line / 75.41% branch（jacoco.csv 01:45:53），过 80/70。
- EV-0538/0539/0540 分别绑定 4752c4f/9b5d336/76bfb22，dirty=false、exit 0；EV-0537 是 617fa4b 的后端全量 verify（B2 之前），不能替代 B2 全量。EV-0542（TASK-0101）在 46a6ceb exit 1，是本次 P1 的直接证据；EV-0543（TASK-0101）在 b09aca2 exit 0，修复后 crypto 定向通过。
- 范围：`git diff --name-only ef2b0f2 46a6ceb` 共 54 个文件，全部落在 `.riccon/tasks/TASK-0103.json` 当前 write_scope 内；未改 pom/迁移/application.yml/其他模块生产代码；未发现真实密钥（diff grep 无 AKIA/BEGIN/PASSWORD 等）。B2 实际修改了 B1a/B1c 拥有的测试文件，scope 补录已在 10fbba2 提交，且 B1c F-001 明确指派 B2（F-008，P3 accepted）。
- HEAD 当前为 10fbba2（TASK-0101 done + .riccon 元数据），不是 reviewed_commits 的 46a6ceb；b09aca2 是 TASK-0101 的测试夹具修复，不改变 B2 生产代码。gate 的 TASK-0103 out-of-scope 是 baseline..HEAD 跨任务区间的噪声（B1c 评审 F-007 同类），但它同时暴露了 P1：TASK-0103 自己的 end_commit 证据未覆盖该修复。

## 本次独立验证

- 命令：`./mvnw -q -T1C -pl apps/api,platform/platform-common -am test -Dtest='ModelContract108aTest,ModelContract108bTest,ModelCatalogApiTest,ManagedEndpointPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false`。
- 结果：surefire 1（108a）/1（108b）/8（ModelCatalogApi）/14（ManagedEndpointPolicy），0 fail / 0 error / 0 skip；报告时间 01:54:44–01:55:20。未录 EV，只作本次评审证据；命令输出未见错误。
- 未跑全量，未跑前端/E2E/a11y/perf/security/Trivy；不替代模块 gate。

## 未验证项

- **46a6ceb/HEAD 的全量 API/worker 测试**：EV-0541 是定向，EV-0537 是 B2 之前；P1 要求补一次最终 HEAD 全量并重录。
- **M02/M03/M10 完整回归**：108a 只检查 7 个代表路径，openapi diff 只增 ProblemDetail；没有跑 M03/M10 全部测试。
- **Actuator 真实来源**：测试用 loopback/MockMvc，不覆盖反向代理、`X-Forwarded-For`、K8s ingress、真实 Prometheus 抓取。
- **真实 DNS/出网/SSRF**：全部用固定 HostResolver/字面量，符合“测试不出网”，但未验证生产 DNS、私网网关、DNS rebinding、重定向（B4 HttpClient）。
- **Quota/cost 运行时**：108b 只做契约；B6/B7 实现与 E2E 未验证。
- **审计失败回滚、provider 改名审计、401 状态码**：见 F-002/F-003/F-005。
- **模块级 E2E/a11y/perf/security/migration**：本任务不跑；M04 gate 需要 Verifier 在最终提交执行合同 test/coverage/E2E/a11y/security，并修复 TASK-0101 之外的 M04 评审。

## 剩余风险

- P1 未关：TASK-0103 end_commit/evidence 与最终 HEAD 不一致；若 Lead 只改任务元数据而不重录全量，模块 gate 仍会带着“红提交”证据。
- P2 审计缺口：provider 改名无痕，若按 T4 硬规则解释会升级为 P1；需要 ADR/Lead 的明确决定。
- P2 401 偏差：ADR 与测试口径不一致；修复状态码或改 ADR，不能两者都保留。
- Worker 分支覆盖率在 EV-0537 为 70.85%（B2 前），B4/B5/B6/B7 新增代码后必须由模块 gate 重跑；platform-common bundle 75.41% 也需关注。
- 端点策略允许私网 IP（ADR 设计）、不做 IP pinning（R-23），生产依赖 TLS/禁重定向/出网策略；`0.0.0.0`/`::` 未拒绝是纵深防御残余。
- API/Worker 各自维护 `AllowedCidrs` 与 `ModelEndpointProperties` 两份同语义代码，存在漂移风险。
- b09aca2/10fbba2 的跨任务归属和 gate 的 baseline..HEAD 噪声需要 Lead 在 closeout 统一，否则 M04 gate 会持续把 TASK-0103 报为越界。
