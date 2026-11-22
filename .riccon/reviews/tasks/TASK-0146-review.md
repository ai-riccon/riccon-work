---
review_id: REV-TASK-0146
module_id: M19
task_id: TASK-0146
reviewer:
  agent: riccon/verifier
  session: ses_e00ea9535ffeyS6ETdknSycltj
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
reviewed_commits:
  - 6014cbe0446010aa1a5df5d9ad507783676d63fb
  - 18fe8a034870b786eea0520b81c7546c1ccf51b1
head_commit: 18fe8a034870b786eea0520b81c7546c1ccf51b1
previous_review: REV-TASK-0145
dod:
  acceptance: true
  tests: true
  review: true
  evidence: true
dod_note: "任务级评审；模块 build/coverage/E2E/security/performance 属 M19 模块 gate，不在本任务 DoD。"
findings:
  - id: F-001
    severity: P2
    title: "（承接 REV-TASK-0145 F-001）安全测试基座类契约与 seed 边界不一致"
    summary: "SecurityTestSupport 类级注释已改为：基座归 verifier 所有、fixture 性能改造由 builder 在 verifier 评审下共同维护；租户/部门/空间/业务对象仍走公开 HTTP；fixture 账号可由 seedAndLoginLocalUser 直接写账号/凭据/角色表，但登录、CSRF、会话与权限判定仍走真实 HTTP/生产代码；真实 createUser 用例覆盖账号创建成功路径与创建审计；明确账号创建路径无短密码/策略拒绝断言（既有缺口），种子未取代任何策略测试。ApiIntegrationTestSupport 的同类过强表述同步校正。安全断言零增删，全量 642/0/0/0。"
    status: fixed
    evidence: "apps/api/src/test/java/com/riccon/api/security/SecurityTestSupport.java:23-31; apps/api/src/test/java/com/riccon/api/support/ApiIntegrationTestSupport.java:84-86,271-275,356,373; EV-0707"
  - id: F-002
    severity: P2
    title: "（承接 REV-TASK-0145 F-002）ModelCatalog 假平台 DEK 与同 namespace 成本残留"
    summary: "ModelCatalogApiTest 新增 @AfterEach cleanupAfterTest() 复用本类 cleanup()，只删 provider code=m04-catalog 的凭据/模型/provider 与 platform 下 key_id LIKE 'm04-key-%' 的占位 DEK；CostAdminApiTest 新增 @AfterEach cleanupCostInvocations()，按 createTrackedPricedModel 登记的本次模型 ID 精确删除 model_invocation。独立在 fresh namespace m19p3vfix 验证：反转全量 642/0/0/0（API suite 427.800s），同 namespace 不 reset 第二次默认顺序全量 642/0/0/0（411.141s）；断言、用例数与失败口径均未改变。"
    status: fixed
    evidence: "apps/api/src/test/java/com/riccon/api/model/catalog/ModelCatalogApiTest.java:104-114,319-349; apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:27-46,241-254; EV-0707"
  - id: F-003
    severity: P3
    title: "（承接 REV-TASK-0145 F-003）计时/覆盖表述不精确"
    summary: "CACHE-DESIGN 已把 EV-0700/EV-0703 的 worker 84.809s 残留与同轮计时拆开，P3 总改用 EV-0696 worker 93.267/91.623s + EV-0704 API 423.582s + platform ≈1s = 517.137s，对 1236.717s 为 58.2%；createUser 表述改为基线 33 个调用、fixture helper 移除 7 个、剩余 26 个调用点；ORDER-DEPENDENCE 改为 9 个 createCredential 500（7 ModelCredential + 2 M04Verifier）+ 1 CostAdmin = 10；密码策略明确为既有缺口（AuthFlowTest 只断言 bcrypt 前缀/强度 12，OIDC 短密码断言针对解绑）。逐项核对 git 计数与 EV-0701/EV-0696/EV-0704 日志，均一致。"
    status: fixed
    evidence: ".riccon/evidence/TASK-0145/CACHE-DESIGN.md:69-82; .riccon/evidence/TASK-0145/ORDER-DEPENDENCE.md:41-46; EV-0696; EV-0701; EV-0704"
reviewed_evidence:
  - EV-0696
  - EV-0701
  - EV-0704
  - EV-0706
  - EV-0707
evidence:
  - EV-0706
  - EV-0707
created_at: "2026-11-22T01:32:39+08:00"
---

# REV-TASK-0146：API 顺序依赖与 P3b 评审残余的独立评审

## 结论

**pass**。TASK-0146 用两个 `@AfterEach` 精确清理解决了 TASK-0145 记录的 P2 测试隔离残余：`ModelCatalogApiTest` 不再把假平台 DEK 留给后续类，`CostAdminApiTest` 不再把上次运行的成本行带进平台汇总。独立在干净 HEAD `18fe8a0`（测试代码与 `6014cbe` 相同）上用 fresh namespace `m19p3vfix` 复跑：**反转全量 120 类 / 642 用例 / 0 failure / 0 error / 0 skipped，API suite 427.800s**；同 namespace 不 reset 的**第二次默认顺序全量 642/0/0/0，API suite 411.141s**（EV-0707）。diff 中零断言增删、零用例删除/跳过、零生产/迁移/pom/CI 改动；F-001/F-003 文档修正与源码、证据逐项一致。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；任务状态不由此文件推进，由 Lead 决定。

## 独立复跑

环境：macOS 本地 compose 热栈（MySQL 8.4.13 / OpenSearch / RustFS），`RICCON_TEST_SERVICES=external`，命令 `./mvnw -q -T1C -pl apps/api -am test`，每次 Maven 外层硬超时 1800s；两轮前均清理 `apps/api`、`apps/worker`、`platform/*` 的 surefire 报告。全量期间未启动 Serena/JDT LS（先终止了残留 JDT LS 进程，避免重演 EV-0699 的 `target/test-classes` 污染）。

| 运行                                  | 条件                                                                  | classes | tests | fail/error/skip  |                      API suite | 证据              |
| ------------------------------------- | --------------------------------------------------------------------- | ------: | ----: | ---------------- | -----------------------------: | ----------------- |
| Verifier 反转全量                     | fresh namespace `m19p3vfix` 先 reset，`reversealphabetical`           |     120 |   642 | 0/0/0            |                       427.800s | EV-0707           |
| Verifier 第二次同 namespace 全量      | 不 reset，默认顺序                                                    |     120 |   642 | 0/0/0            |                       411.141s | EV-0707           |
| Builder 反转/正向/同 namespace 第二次 | fresh `m19p3rev`，三轮分别清报告                                      |     120 |   642 | 0/0/0 每轮       | 426.582s / 417.175s / 410.734s | EV-0706           |
| TASK-0145 Verifier 参照               | fresh `m19p3vvapi` 默认顺序；对照 EV-0701 同 namespace 第二次 10 失败 |     120 |   642 | 0/0/0（EV-0704） |                       423.582s | EV-0704 / EV-0701 |

EV-0707 总时长 937.609s，包含两轮全量、两次 `test-timings`、`spotless:check` 与 `git diff --check`；最终 `verifier_task_0146_ok` 打印且 exit 0。测试计时按两轮分别写入 `$TMPDIR/m19p3vfix/timings-reverse` 与 `timings-second`，均显示 `apps/api: tests=642 failures=0 errors=0 skipped=0`。

两轮日志各出现一次 `[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`。该现象在 TASK-0146 之前的 EV-0700/EV-0704 日志中同样存在（每轮一次，约多 30s），不是本次 `@AfterEach` 引入；不影响 surefire 汇总与 Maven 退出码，记为剩余风险。

## Diff 审计

- `git diff --name-status 1d1b367..6014cbe`：仅 4 个 API 测试文件（`ModelCatalogApiTest`、`CostAdminApiTest`、`SecurityTestSupport`、`ApiIntegrationTestSupport`）+ `.riccon` 文档/任务/状态；`git diff --name-only | grep -v '^apps/api/src/test/\\|^\\.riccon/'` 为空，生产源码、迁移、pom.xml、CI、Worker 测试零改动。
- 测试完整性：`6014cbe` 精确 `@Test` 计数 **642**、测试类 **120**，与 baseline 相同；`@Disabled`/`assume*`/`Assumptions`/`@Enabled*` 计数为 **0**；diff 中没有任何 `@Test` 行或 `assert` 行增删。新增只有清理生命周期方法、按模型 ID 跟踪的 helper 与中文 Javadoc。
- `ModelCatalogApiTest.cleanupAfterTest()`：`@AfterEach` 调用既有 `cleanup()`。删除范围为 `provider code='m04-catalog'` 的 `provider_credential`/`model`/`model_provider`，以及 `tenant_id='platform' AND key_id LIKE 'm04-key-%'` 的占位 `tenant_data_key`。`m04-catalog` 与 `m04-key-` 在 `apps/api/src/test` 和 `apps/api/src/main` 中只由本类使用，生产迁移/种子无此 provider code 或 key 前缀，未发现误删其他测试或种子的路径；`cleanup()` 在 `@BeforeEach` 与 `@AfterEach` 各执行一次，测试失败时也会执行。
- `CostAdminApiTest.cleanupCostInvocations()`：只遍历 `createTrackedPricedModel()` 登记的、本测试通过 `UuidV7` 新建的 `modelId`，删除 `model_invocation WHERE model_id = :modelId`；模型 ID 每次生成且不跨测试共享，不会命中其他测试行。删除发生在 `@AfterEach`，测试内断言看到的仍是本轮两行（0.003000 + 0.006000 = 0.009000），未改变平台汇总口径；遗留的 model/provider 行不参与无调用的聚合，第二次全量已验证无影响。
- 异常语义：两个清理方法都没有 `try/catch`，清理失败会让测试报错，不会吞掉原失败；两个方法都不含断言，也未加入任何 retry/skip 逻辑。`@AfterEach` 在 JUnit 5 中即使测试失败也会执行（标准生命周期行为；本任务未改测试框架配置）。
- 文档与注释：`SecurityTestSupport`/`ApiIntegrationTestSupport` 的类级/方法级说明已准确区分“种子 fixture 账号”与“真实 HTTP 登录/CSRF/权限/创建审计”，并明确密码策略拒绝是既有缺口；新增方法均有中文 Javadoc，复杂清理原因有行内说明。

## F-001 / F-002 / F-003 处理核对

**F-001（P2，fixed）**：`SecurityTestSupport.java:23-31` 已说明所有权与 seed 边界；`ApiIntegrationTestSupport.java:84-86,271-275,356,373` 同步校正“密码策略由真实创建路径覆盖”的过强表述。代码 diff 无安全断言改动，642 全量通过。补充说明：`OidcIdentityBindingApiTest` 使用自有 HTTP helper 创建账号，而非共享 `createUser` helper；其账号创建仍走真实端点，因此类注释中“真实 createUser 用例”的边界结论不改变，只作为措辞层面的小注记。

**F-002（P2，fixed）**：如上节，清理范围、断言口径与两轮全量均符合 AC；反转从 TASK-0145 的 7 failures 变为 642/0/0/0，同 namespace 第二次从 10 failures 变为 642/0/0/0。

**F-003（P3，fixed）**：逐项复核：

- `createUser`：`git grep "createUser(" ee4f26c` 为 34 行（33 个真实调用 + 1 个方法声明），`6014cbe` 为 27 行（26 个调用 + 1 个声明），即 fixture helper 移除 7 个、剩余 26 个调用点，与 CACHE-DESIGN 一致；
- 失败计数：EV-0701 汇总为 642 tests / 10 failures，明细为 M04Verifier 2 + ModelCredentialSecurity 2 + ModelCredentialLifecycle 5 = 9 个 createCredential 500，加 CostAdmin 平台汇总 1 个，合计 10，与 ORDER-DEPENDENCE 修正一致；
- 计时口径：EV-0696 两轮 worker 为 93.267s / 91.623s，EV-0704 API 为 423.582s，`517.137s` 对 `1236.717s` 降 58.2%，与 CACHE-DESIGN 修正一致；
- 密码策略：`AuthFlowTest` 只断言 `{bcrypt}$2a$12$` 前缀/强度与会话超时（`AuthFlowTest.java:298-308`），`OidcIdentityBindingApiTest` 的短密码断言在解绑场景（`:346-359`），创建端点无策略拒绝断言，与修正后的“既有缺口”表述一致。

## AC 对账

| AC 原文要点                                                                           | 结论 | 依据                                                                           |
| ------------------------------------------------------------------------------------- | ---- | ------------------------------------------------------------------------------ |
| F-002：ModelCatalog 每测试清平台 DEK/凭据，fresh namespace 反转 642/0/0/0，不放宽断言 | pass | EV-0707 反转 427.800s；diff 零断言增删；清理范围限定 provider/前缀             |
| 同 namespace 连续第二次全量，修复成本汇总残留                                         | pass | EV-0707 第二次不 reset 642/0/0/0，API 411.141s                                 |
| F-001：SecurityTestSupport 类级契约/所有权与 seed/真实 HTTP 边界                      | pass | 注释 diff 与源码一致；安全断言零改动；642 全量                                 |
| F-003：计时口径、createUser 计数、9+1 失败数、密码策略覆盖表述                        | pass | git 计数、EV-0701/EV-0696/EV-0704 逐项一致                                     |
| 不改生产/迁移/pom/CI；先提交后取证；证据单行；独立 Verifier 评审                      | pass | `6014cbe` 仅测试+.riccon；EV-0706/EV-0707 单行、commit/dirty/exit 合规；本评审 |

### 模块 gate 现状

`node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M19` 当前为 **FAIL**，与本任务技术验收无关：M19 仍为 `building`；TASK-0119 仍为 `review`，且 gate 用其 `87d7ee6..HEAD` 基线把 TASK-0144/0145/0146 的测试改动归因为 TASK-0119 的 write_scope 越界；TASK-0146 本身仍为 `review`（等待 Lead closeout）；M19 尚无模块评审。任务级 review 已按 `task-review.schema.json` 解析（`state validate` 0 errors），待 Lead 完成 TASK-0119/0146 closeout 并产出 `.riccon/reviews/M19-review.md` 后再运行模块 gate。

## 未验证

- **默认 Testcontainers 模式**未独立复跑：本任务 AC 与 CI 热栈路径使用 external 模式；一次性容器留给 M19 模块 gate。
- **失败路径下的 `@AfterEach` 实际执行**未用故意失败的测试验证（按约束不改测试源码）；依据 JUnit 5 标准生命周期和“无 catch、无吞异常”的代码审查。
- **删除行数**未逐行断言：两轮全量（反转 + 同 namespace 第二次）通过是行为级证明；未直接查询 `tenant_data_key`/`model_invocation` 的精确剩余行数。
- **模块 build/coverage/E2E/security/a11y/performance** 未在本任务重跑；属 M19 模块 gate。
- EV-0707 的 `agent` 元数据为 `unknown`（记录命令未传 `--agent`，不影响 commit/命令/output_sha256/dirty/exit 核对）；审查以本会话与 EV 文件为准。
- CI 4-shard 与 GitHub Actions 未运行（DEC-0028：P3 后单独申请 CI 取证）。

## 剩余风险

1. 清理约定目前只覆盖两个已发现类；未来新增写全局 DEK/成本/MCP/outbox 状态的测试仍需按唯一前缀/ID 清理，否则反转或同 namespace 复用会再次暴露。
2. `ModelCatalogApiTest` 的 `LIKE 'm04-key-%'` 依赖前缀在测试/生产种子中的唯一性；若未来其他测试复用该前缀，`@AfterEach` 可能误删其 DEK。
3. `CostAdminApiTest` 只删 `model_invocation`，不删遗留 model/provider 行；当前无全局目录计数断言，第二次全量通过，但未来新增全目录计数用例需重新评估。
4. Surefire 每轮约 30s 的 fork 退出等待（`kill self fork JVM`）是既有现象（EV-0700/EV-0704/EV-0706 均存在），会推高墙钟，未计入本轮修复。
5. 两轮保证依赖 surefire 默认 `forkCount=1, reuseForks=true`；若改为并行/多 fork，类顺序与 `@AfterEach` 清理时序需重新验证。

## 独立性声明

本次评审由 `riccon/verifier` 在独立会话 `ses_e00ea9535ffeyS6ETdknSycltj` 执行；未采信 builder 总结作为结论，先读任务/上一轮 review/diff/源码/文档，再在干净提交 `18fe8a0` 上独立复跑反转全量与同 namespace 第二次全量。未修改生产源码、迁移、pom、CI 或测试源码；只写评审、证据与 `.riccon` 状态。审查期间终止了残留 Java LSP，全量 Maven 期间未启动 Serena/JDT LS。
