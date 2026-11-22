---
review_id: REV-TASK-0145
module_id: M19
task_id: TASK-0145
reviewer:
  agent: riccon/verifier
  session: ses_e0136bbc5ffeqG0wROxo87bDqm
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
reviewed_commits:
  - 09ae5320af79d944fed7f5aa9570e399c46c003e
  - 69b3a0cd81042648f83166564ae2ab400e2e107a
  - c555b7d7c51784aba81db4e968e32f3805379dc4
  - d4047458f142a20ec493e79232c391feacf9c70b
  - b9abb856689ab1069d37ed6a6cf4154043a159e4
  - e80431a6e54049f7b1150f77bbd8e129b3daeb59
  - 0af22002c8ef32f958171b99a2c0247defe2fc41
head_commit: 0af22002c8ef32f958171b99a2c0247defe2fc41
dod:
  acceptance: true
  tests: true
  review: true
  evidence: true
dod_note: "任务级评审；build/coverage/E2E/security/performance 属 M19 模块 gate，不在本任务 DoD。"
findings:
  - id: F-001
    severity: P2
    title: "Verifier 安全测试基座改为直接种子账号，类级契约仍声称全部账号经公开 HTTP 创建"
    summary: "SecurityTestSupport 类注释声明“由 verifier 维护”“所有租户、部门、空间、账号均通过公开 HTTP 接口在真实 MySQL 上创建”，但本次把 manager 与 createMember 改为 seedAndLoginLocalUser/直接写 app_user/user_credential/user_role；行内注释承认种子，类级契约未更新。安全断言目标未被种子绕过（登录、CSRF、会话与权限判定仍走生产代码；用户创建、创建审计仍由真实 createUser/HTTP helper 覆盖），风险集中在 verifier 自有测试基座被 builder 修改、注释误导和后续维护者误判测试真实性。"
    status: accepted
    owner: riccon/builder-m19
    due: M19 gate 前
    accepted_reason: "逐类核对 TenantIsolationSecurityTest、DepartmentIsolationTest、DepartmentAdminApiTest、M04Verifier*、OidcIdentityBindingApiTest、AuthFlowTest：种子仅用于 fixture 账号，断言目标分别是部门/租户边界、模型边界、身份绑定、禁用登录等，不是账号创建端点、权限分配、创建审计或密码策略；TEN-019 与 AuditTestSupport 仍用真实 createUser。独立全量 642/0/0/0、关键安全类反转 135/0/0/0 通过。接受为 P2 文档/基座所有权残余，要求 M19 gate 前更新类注释与 CACHE-DESIGN §2.1，或经 Verifier 复核后恢复该套件账号的 HTTP 创建。"
    evidence: "apps/api/src/test/java/com/riccon/api/security/SecurityTestSupport.java:22-28; 同文件:58-65,135-141; .riccon/evidence/TASK-0145/CACHE-DESIGN.md:25-33"
  - id: F-002
    severity: P2
    title: "既有 API 全局状态/顺序依赖：ModelCatalog 假平台 DEK 与同 namespace 残留"
    summary: "EV-0702 全量反转 642/7 失败；独立在 HEAD 用 fresh namespace m19p3vcat 复现同一 7 个失败（ModelCredentialSecurityTest 2 + ModelCredentialLifecycleApiTest 5，7 次 EnvelopeException: data key cannot be unwrapped by any master key），与 builder 在基线 bc75c04 的反转日志完全一致；相关测试类在 ee4f26c..HEAD 零改动。EV-0701 同 namespace 不 reset 的第二次全量 10 失败（9 个 createCredential 500 + CostAdmin 平台汇总 0.018 vs 0.009），同属既有全局模型/成本残留，不是 P3b 缓存回归。默认 fresh namespace + 默认顺序的全量与关键安全类反转顺序均通过。"
    status: accepted
    owner: riccon/builder-m19
    due: M19 gate 前
    accepted_reason: "既有缺陷，TASK-0145 只改 fixture 会话/账号创建路径，未修改 ModelCatalogApiTest、ModelCredential*、CostAdminApiTest 或生产代码；CI/默认路径使用每 job 唯一 namespace + 默认顺序，已通过。接受为 P2 测试隔离残余，建议 M19 gate 前或紧随其后单独任务为 ModelCatalogApiTest 增加 @AfterEach 清平台 DEK、为全局成本汇总增加按测试隔离/清理，或在测试规范中显式声明“全量反转不在支持范围”。"
    evidence: ".riccon/evidence/TASK-0145/EV-0702.json; .riccon/evidence/TASK-0145/BASELINE-REVERSE-ORDER.log (sha256 c3b840e8439c121e67c255326c9704c35ad4be729b1619ca777b2b814baceb79); .riccon/evidence/TASK-0145/VERIFIER-MODELCATALOG-ORDER-DIAG.log (sha256 04f0459ae3b5add815a4ef104d9f5a6fbce9b6e4a13ed642e72a5e13e368d850); apps/api/src/test/java/com/riccon/api/model/catalog/ModelCatalogApiTest.java:84-96,307-330; apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java:558-559"
  - id: F-003
    severity: P3
    title: "性能/诊断文档的测量与覆盖表述不精确"
    summary: "1) EV-0700/EV-0703 的计时命令只删 apps/api 与 platform 报告，未删 apps/worker，test-timings 中的 worker 84.809s 来自 TASK-0144 EV-0698 的残留报告，因此“EV-0700 同一次运行的 worker 84.809s / 合计 553.081s”不是同一轮运行；用 EV-0696 worker ~92s 与 EV-0704 API 423.582s 计算 P3 总为 517.137s，对 1236.717s 降幅 58.2%，结论反而更强。2) CACHE-DESIGN “26 处 createUser 不变”应表述为“剩余 26 个调用点不变”：基线有 33 个真实 HTTP createUser 调用，本次从 fixture helper 移除 7 个，账号创建端点成功路径仍由剩余 26 个覆盖。3) ORDER-DEPENDENCE §3 称 8 个 createCredential 500，EV-0701 实际为 9 个（7 ModelCredential + 2 M04Verifier）+ 1 CostAdmin = 10。4) CACHE-DESIGN 称密码策略由真实 HTTP 创建路径覆盖，但账号创建路径没有短密码/策略拒绝断言（AuthFlowTest 只断言 bcrypt 前缀与强度 12；OidcIdentityBindingApiTest 的短密码断言针对 OIDC 解绑，不是创建），这是既有覆盖缺口，种子路径未取代任何策略测试。"
    status: accepted
    owner: riccon/builder-m19
    due: M19 gate 前
    accepted_reason: "均为文档/测量口径问题，不影响功能与验收结论：独立全量 642/0/0/0、API suite 423.582s 对本地基线 902.821s 降 53.1%，对 P2 规范基线 828.171s 降 48.9%，P3 总对 P2 总降 58.2%（≥30%）；断言未放宽。接受为 P3 文档精度残余，要求 M19 gate 前校正 CACHE-DESIGN/ORDER-DEPENDENCE 的上述表述。"
    evidence: ".riccon/evidence/TASK-0145/EV-0700.json; .riccon/evidence/TASK-0145/EV-0703.json; .riccon/evidence/TASK-0145/EV-0704.json; .riccon/evidence/TASK-0145/EV-0701.json; .riccon/evidence/TASK-0145/CACHE-DESIGN.md:62-74; .riccon/evidence/TASK-0145/ORDER-DEPENDENCE.md:38-41"
reviewed_evidence:
  - EV-0694
  - EV-0695
  - EV-0696
  - EV-0700
  - EV-0703
  - EV-0704
  - EV-0705
evidence:
  - EV-0704
  - EV-0705
created_at: "2026-11-22T00:24:00+08:00"
---

# REV-TASK-0145：API fixture 缓存与 setup 摊销的独立评审

## 结论

**pass**。TASK-0145 对 API 集成测试的昂贵 setup 做了可证伪的缓存/摊销：平台管理员会话在真实登录后复用，每次复用前用真实 `GET /auth/session` 探针确认；fixture 账号直接种子但登录、CSRF、会话与权限判定仍走生产 HTTP。独立在干净提交 `0af2200` 上用 fresh namespace `m19p3vvapi` 复跑全量：**642 tests / 0 failures / 0 errors / 0 skipped，API suite 423.582s，墙钟 479s**（EV-0704）；关键认证/安全类反转顺序 **135/0/0/0**（EV-0705）。相对改造前 902.821s 的 API suite 降 **479.239s（-53.1%）**；P3 总体按 API 423.582s + Worker ~92s + platform 1.555s = 517.137s，对 P2 全量 1236.717s 降 **58.2%**，≥30% 稳健成立。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；任务状态不由此文件推进，由 Lead 决定。

## 独立复跑

环境：macOS 本地 compose 热栈（MySQL 8.4.13 / OpenSearch / RustFS），`RICCON_TEST_SERVICES=external`，命令 `./mvnw -q -T1C -pl apps/api -am test`，硬超时 1800s；每次先 `node scripts/test-services.mjs reset --namespace <NS>`，且清理 `apps/api`、`apps/worker`、`platform/*` 的 surefire 报告，避免残留污染计时。

| 运行             | 条件                                                            |      tests | fail/error/skip | API suite | 墙钟 | 证据                                   |
| ---------------- | --------------------------------------------------------------- | ---------: | --------------- | --------: | ---: | -------------------------------------- |
| Verifier 全量    | namespace `m19p3vvapi`，默认顺序                                |        642 | 0/0/0           |  423.582s | 479s | EV-0704                                |
| Verifier 反转    | namespace `m19p3vord`，reversealphabetical，10 类关键认证/安全  |        135 | 0/0/0           |  190.906s | 213s | EV-0705                                |
| Builder 全量     | namespace `m19p3b4`，默认顺序                                   |        642 | 0/0/0           |  467.249s | 512s | EV-0700                                |
| Builder 反转定向 | namespace `m19p3b6`，8 类 114 用例                              |        114 | 0/0/0           |  224.315s | 245s | EV-0703                                |
| 改造前基线       | HEAD `bc75c04`，namespace `m19p3b0`，本地测量产物               |        642 | 0/0/0           |  902.821s | 948s | `$TMPDIR/m19p3b0/timings`              |
| P2 规范基线      | EV-0694 全量，`scripts/test-shards-baseline.json` 去残留        | 642（API） | 0/0/0           |  828.171s |    — | EV-0694/EV-0695                        |
| 既有顺序缺陷     | HEAD，namespace `m19p3vcat`，ModelCatalog+ModelCredential* 反转 |         16 | 7/0/0           |         — |  56s | `VERIFIER-MODELCATALOG-ORDER-DIAG.log` |

说明：另外做过一次把 Maven 输出实时流到宿主 UI 的预跑，API suite 625.815s、墙钟 677s（宿主 load average ~6–7，OpenCode renderer 占用高核）；正式 EV 使用内核捕获输出，423.582s，说明测量对宿主负载敏感。性能结论以正式 EV 与 builder EV 两条为准，均远超阈值。`EV-0699`（Serena JDT LS 并发污染 `target/test-classes`，exit 1）不作为通过证据，已由 `EV-0700`/`EV-0704` 替代；本次正式复跑期间未启动 Java LSP。

## 测试完整性

- **用例集合**：`ee4f26c..09ae532` 只改 5 个测试文件；120 个 `*Test(s).java` 类集合与 `scripts/test-shards-baseline.json` 的 120 个 API 类逐项一致，无新增/删除/重命名。精确统计 `^\s*@Test$` 为 **642**（baseline 与 HEAD 相同）；`grep '@Test'` 得到的 660 是子串计数，等于 642 个 `@Test` + 17 个 `@TestPropertySource` + 1 个 `@TestConfiguration`，不是 18 个未执行用例。与 EV-0695 的 642 完全对齐。
- **跳过/禁用**：HEAD 与 baseline 的 `apps/api/src/test/**` 均无 `@Disabled`、`assumeTrue/assumeThat/Assumptions`、`@EnabledIf/@EnabledOn*`；EV-0704/EV-0705 的 skipped 均为 0。
- **断言**：diff 中没有任何 `assert` 行增删；被替换的只是 fixture 创建与登录方式。`AuthFlowTest` 的会话读取、登出、密码哈希/超时 3 个用例改为 `freshPlatformAdminLogin()`，属于加强真实性而非放宽。
- **真实 createUser 覆盖**：基线 33 个真实 HTTP `createUser` 调用，HEAD 为 26 个；减少的 7 个都在 fixture helper（`DepartmentTestSupport`、`SecurityTestSupport`、`createAndLoginTenantAdmin/Member`）内，不是断言。剩余 26 个覆盖账号创建成功路径、`user.created` 审计、角色变更、禁用登录、Quota/OIDC 创建等；`OidcIdentityBindingApiTest` 另用自有 HTTP helper（`createLocalUserByPlatform/TenantAdmin`、`createOidcUser`）覆盖创建端点，`AuditTestSupport` 仍用真实 `createUser`。
- **真实执行**：EV-0704 实际执行 642 个用例并产出 120 份 surefire 报告；没有零测试、没有跳过、没有重试或吞错。

## 缓存隔离与安全真实性

1. **平台管理员会话缓存**：`loginPlatformAdmin()` 首次走真实 `GET /auth/csrf` + `POST /auth/login`，之后每次复用前用 `GET /api/v1/auth/session` 只读探针确认 200；非 200 重新真实登录。登出会使会话返回 401（`AuthFlowTest.logoutInvalidatesSessionAndWritesAudit` 使用真实独立会话验证），探针逻辑据此会触发重登，缓存失败不会掩盖成成功。会话计数、登出、CSRF 轮换、登录审计相关用例已逐项核对：`AuthFlowTest` 的 3 个会话断言改为 fresh；`OidcLoginFlowTest` 的 sessionCount/logout 使用其自己的 OIDC 会话；`TenantIsolationSecurityTest`/`AuditQueryApiTest` 的审计断言按唯一租户或唯一目标过滤，不依赖平台管理员每次登录。未发现使用缓存平台会话做登录/登出/会话计数/CSRF/登录审计断言的用例。
2. **种子账号**：`seedLocalUser` 直接写 `app_user`、`user_credential`（复用两个 fixture 密码的 bcrypt 强度 12 哈希）、`user_role`（授予人为平台管理员），字段与生产 `TenantUserAdminService.createLocalUser` 对齐（display_name "User "+username、email NULL、ACTIVE/LOCAL、must_change_password=FALSE、failed_attempts=0）；`seedAndLoginLocalUser` 的登录仍走真实 HTTP。使用方为 `DepartmentTestSupport`（租户管理员/成员/额外成员）与 `SecurityTestSupport`（负责人/成员），以及大量 `createAndLoginTenantAdmin/Member` fixture。逐类核对：这些类的断言目标是部门/租户边界、空间范围、文件、报告、会话、运行、模型凭据/路由/网关、M04 边界、身份绑定、禁用登录与 405 等，不是账号创建端点、权限分配、创建审计或密码策略；这些语义仍由剩余 26 个真实 `createUser` 与 `AuditTestSupport`/OIDC HTTP helper 覆盖。未发现安全/审计断言目标被种子绕过。
3. **共享 HttpClient**：`SHARED_HTTP_CLIENT` 未配置 CookieHandler，Cookie 与 CSRF 由每次请求显式传入；仅复用连接池，不保存认证状态，不跨测试泄漏。
4. **全局可变状态**：缓存只包含平台会话、两个 bcrypt 哈希和 HttpClient，不缓存租户、业务行、模型/MCP/配额目录或开关；模型/MCP/outbox 的 reset helper 保持原样，未进入缓存。
5. **既有覆盖缺口（非本任务引入）**：账号创建路径没有短密码/密码策略拒绝断言；`AuthFlowTest.credentialsUseBcryptTwelveAndSessionTimeoutIsThirtyMinutes` 只断言 bcrypt 前缀/强度与会话超时，`OidcIdentityBindingApiTest` 的短密码断言针对 OIDC 解绑而非创建。种子路径没有取代任何策略测试，但 CACHE-DESIGN 的“密码策略由真实 HTTP 创建路径覆盖”表述过强（F-003）。

## 顺序依赖

- **关键认证/安全类反转通过**：EV-0705 在 fresh namespace `m19p3vord` 用 `-Dsurefire.runOrder=reversealphabetical` 跑 10 类 135 用例（AuthFlow、TenantIsolation、TenantIsolationSecurity、DepartmentIsolation、WorkspaceScope、AuditQuery、OidcLoginFlow、DepartmentAdmin、OidcIdentityBinding、QuotaAdmin），全部通过；覆盖范围比 builder 的 114 用例定向更广，包含用户点名的 OidcIdentityBinding 与 QuotaAdmin。
- **EV-0702 的 7 个失败是既有缺陷**：builder 在基线 `bc75c04` 上反转全量同样 7 failures，日志 sha256 与 ORDER-DEPENDENCE 声明一致；本次在 HEAD `0af2200` 用 fresh namespace `m19p3vcat` 独立复现同一 7 个方法（2 ModelCredentialSecurity + 5 ModelCredentialLifecycle），7 次 `EnvelopeException: data key cannot be unwrapped by any master key`。`ModelCatalogApiTest`、`ModelCredential*` 在 `ee4f26c..HEAD` 零改动。机制是 `ModelCatalogApiTest` 每个测试前插入假平台 `tenant_data_key`，类末没有 `@AfterEach` 清理；反转顺序下它先跑，后续凭据测试取到假 DEK 失败。默认顺序下凭据类先跑，因此不暴露。
- **EV-0701 的 10 个失败**：同 namespace 不 reset 的第二次全量，9 个 createCredential 500（7 ModelCredential + 2 M04Verifier）+ 1 CostAdmin 平台汇总 0.018 vs 0.009；均为既有全局模型/成本残留，未由本次缓存引入。默认 fresh namespace + 默认顺序通过，关键安全类反转通过。
- 结论：没有发现 P3b 缓存/种子引入的新顺序依赖；既有顺序缺陷按 P2 记录（F-002），不判本任务 fail。

## 性能

- **API suite**：EV-0704 **423.582s**，对 builder 本地基线 **902.821s** 降 **479.239s（-53.08%）**；对 P2 规范 API 累计 **828.171s**（EV-0694/EV-0695 去残留）降 **404.589s（-48.85%）**。builder 的 EV-0700 467.249s（-48.2%）也成立。
- **P3 总体**：API 423.582 + Worker EV-0696 ~92s + platform 1.555s ≈ **517.137s**，对 P2 全量 1236.717s 降 **58.18%**（对 baseline JSON 1231.995s 降 58.02%），远超 ≥30%。builder 报的约 55% 是偏保守的较优运行口径；独立运行结果更强。
- **测量口径**：EV-0700/EV-0703 的 `test-timings` 含 `apps/worker` 的 84.809s 残留（来自 TASK-0144 EV-0698），不能当作同一轮 worker 时间；P3 合计应使用 EV-0696 的 worker ~92s 或更保守的 4-shard 基线。该口径问题不影响 ≥30% 结论（F-003）。
- **敏感性**：宿主负载高时（实时流式输出 + UI 渲染）预跑 625.815s，正式 EV（内核捕获输出）423.582s；建议模块 gate 以 EV-0704/EV-0700 为准，并接受 ±20% 的宿主噪声。

## 范围与合规

- `git diff --name-only ee4f26c 09ae532`：仅 `apps/api/src/test/**` 5 个文件（AuthFlowTest、DepartmentTestSupport、SecurityTestSupport、ApiIntegrationTestSupport、ApiTestClient）与 `.riccon/**`；生产源码、迁移、pom.xml、CI workflow 零改动。
- 新增方法均有中文 Javadoc；`seedLocalUser`/缓存/探针/哈希缓存有方法级说明，复杂 fixture 语义有行内中文说明；`./mvnw -q spotless:check` 与 `git diff --check` 在 EV-0704/0705 中通过。
- 证据单行、先提交后取证：实现 `09ae532`；EV-0704/0705 记录于 `0af2200`，`dirty=false`、exit 0、命令单行。
- TASK-0144 F-001 文档修正：`ISOLATION-DESIGN.md` 已把 `provider_credential`/`tenant_data_key` 拆分为 platform 行与租户级行，并列出 `ModelRegistryIntegrationTest` 的全表 `DELETE`/`UPDATE` 例外；与 worker 源码（`ModelRegistryIntegrationTest:133,160`、`ModelFailoverIntegrationTest:230`、`ApprovalParamsCryptoTest:110`）一致，修正到位。

### 模块 gate 现状

`node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M19` 当前为 **FAIL**，与 TASK-0145 技术验收无关：M19 仍为 `building`、TASK-0145 仍为 `review`（Lead 尚未 closeout）、TASK-0119 仍为 `review` 且 gate 用其 baseline `87d7ee6..HEAD` 归因把 TASK-0144/0145 的测试变更算作其 write_scope 外文件、M19 尚无模块评审。任务级 review 已按 `task-review.schema.json` 解析通过（`verdict=pass`，3 条 accepted finding）。模块 gate 应在 TASK-0119/0145 closeout 并产出 `.riccon/reviews/M19-review.md` 后再运行；`riccon state validate` 的其余 warning 均为既有 M03/M04/M10/M12/M13/M15/M18 历史评审，与本次无关。

## AC 对账

| AC 原文要点                                                                   | 结论 | 依据                                                                                       |
| ----------------------------------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------ |
| 缓存/复用昂贵 fixture，但不得共享可变测试状态；每测试仍有唯一租户/数据        | pass | 平台会话+哈希+连接池缓存；租户/账号/业务对象唯一；EV-0704 全量                             |
| 所有 API 测试仍真实执行（≥642，零 skip/@Disabled），断言不放宽；全量通过      | pass | 642 `@Test`、120 类、无 skip/assume；EV-0704 642/0/0/0；diff 无 assert 变更                |
| 缓存失效/清理与全局目录、开关、配额等可变状态隔离有书面说明；需要时按测试重置 | pass | CACHE-DESIGN §2–§4、ORDER-DEPENDENCE；全局状态不进入缓存，reset helper 保留                |
| 记录 API suite 时间与 P2 基线对比；不改生产代码                               | pass | EV-0700/EV-0704；902.821→423.582（-53.1%），828.171→423.582（-48.9%）；仅测试/.riccon diff |
| 先提交再取证；证据单行；任务 review 前由独立 Verifier 评审缓存隔离            | pass | 09ae532 后 EV-0700；EV-0704/0705 单行、clean commit；本评审                                |

## 未验证

- **默认 Testcontainers 模式**未独立复跑：本任务 AC 与 CI 热栈路径使用 external 模式；默认一次性容器留给 M19 模块 gate。
- **全量 642 用例反转顺序**因既有 ModelCatalog 平台 DEK 缺陷无法通过；只独立跑了关键认证/安全 135 用例反转与 ModelCatalog+ModelCredential* 的定向反转复现，未把既有缺陷当作本任务回归。
- **缓存探针 401 重登分支**未被现有用例直接执行（没有测试主动登出缓存平台会话）；通过代码审查 + `AuthFlowTest` 的“登出后 `/auth/session` 401”行为间接验证，未写临时测试（按约束不改测试源码）。
- **账号创建路径的密码策略拒绝**没有 API 级断言（既有覆盖缺口；OIDC 解绑的短密码断言不属于创建），未在本任务补测。
- **模块 build/coverage/E2E/security/a11y** 未在本任务重跑；属 M19 模块 gate。
- GitHub Actions 未运行（DEC-0028：P3 后再单独申请 CI 取证）。

## Findings

见 front matter：F-001（P2，accepted）、F-002（P2，accepted）、F-003（P3，accepted）。无 P0/P1，无 open。

## 剩余风险

1. 静态缓存是 JVM 级；若 surefire 改为多 fork/并行执行，或测试类被拆到不同 JVM，缓存命中率和会话探针行为需要重新验证（当前 `forkCount=1, reuseForks=true` 默认）。
2. 既有全局模型/成本状态依赖仍在：默认 fresh namespace + 默认顺序安全，但同 namespace 第二次全量、反转全量或未来并行化会失败；F-002 需后续任务修复。
3. 种子账号绕过 `user.created` 审计、用户配额与创建时密码策略副作用；后续测试不得把种子用于这些断言目标（当前已核对未发生）。SecurityTestSupport 类注释仍与实现不一致，F-001 要求 gate 前修正。
4. API suite 时间对宿主负载敏感（423–626s 区间）；CI 4-shard 口径与本地单 JVM 不可直接比较，模块 gate 应以合同命令和 EV 为准。
5. 缓存平台会话依赖引导管理员账号不被密码/状态修改；当前测试没有修改平台管理员密码/状态/角色的用例，探针也会在失效时重登，但未来新增此类测试应显式使用 `freshPlatformAdminLogin()`。

## 独立性声明

本次评审由 `riccon/verifier` 在独立会话 `ses_e0136bbc5ffeqG0wROxo87bDqm` 执行；未采信 builder 的总结作为结论，先读任务/设计/diff/源码与全部相关 EV，再在干净提交 `0af2200` 上独立复跑全量与反转定向。未修改生产源码、迁移、pom、CI 或测试源码；只写评审、EV 与 `.riccon` 状态。

工具使用：用 CodeGraph 查 `TenantUserAdminService`/`ModelCredentialService`/测试基座与 `createUser` 的引用面；全量 Maven 期间未启动 Serena Java LSP（避免重演 EV-0699 的 `target/test-classes` 污染），符号与调用点用 grep/read 交叉核对。评审前确认 `git status` 无源代码改动，HEAD 为 `0af2200`。
