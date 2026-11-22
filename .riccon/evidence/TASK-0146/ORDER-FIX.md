# TASK-0146：API 顺序依赖收口与 P3b 评审残余处理

日期：2026-11-22
Owner：riccon/builder-m19
写范围：`apps/api/src/test/**`、`.riccon/**`（生产代码、迁移、pom、CI、Worker 测试零改动）
权威证据：`EV-0706`（commit `6014cbe`，dirty=false，exit 0）
基线：`1d1b367`（TASK-0145 评审后）
关联评审：`.riccon/reviews/tasks/TASK-0145-review.md`（F-001 / F-002 / F-003，均 accepted，due M19 gate 前）

## 1. 处理结果

| Finding | 级别 | 处理                                                                  |
| ------- | ---- | --------------------------------------------------------------------- |
| F-001   | P2   | 已修文档：`SecurityTestSupport` 类级契约明确所有权、seed fixture 边界 |
| F-002   | P2   | 已修代码：平台占位 DEK 与成本调用残留均有 `@AfterEach` 精确清理       |
| F-003   | P3   | 已修文档：计时口径、createUser 计数、9+1 失败数、密码策略覆盖表述     |

没有放宽、跳过或删除任何测试断言；没有修改 `ModelCredential*` 的断言语义。

## 2. F-002：顺序依赖与同 namespace 残留

### 2.1 复现（修复前）

fresh namespace `m19p3cor` 先 reset，只跑 3 个类并反转顺序：

```
RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=m19p3cor \
  ./mvnw -q -T1C -pl apps/api -am test \
  -Dtest='ModelCatalogApiTest,ModelCredentialLifecycleApiTest,ModelCredentialSecurityTest' \
  -Dsurefire.runOrder=reversealphabetical -Dsurefire.failIfNoSpecifiedTests=false
```

结果：**16 用例 / 7 failures / 0 errors / 0 skipped**，与 TASK-0145 评审及
`VERIFIER-MODELCATALOG-ORDER-DIAG.log` 完全一致（`ModelCredentialLifecycleApiTest` 5 +
`ModelCredentialSecurityTest` 2，均为 `EnvelopeException: data key cannot be unwrapped by any master key`）。

### 2.2 修复

1. `ModelCatalogApiTest`：新增 `@AfterEach cleanupAfterTest()` 调用本类已有的 `cleanup()`。
   `cleanup()` 只按本类 `PROVIDER_CODE = m04-catalog` 删除其凭据/模型/provider，并删除
   `tenant_id='platform' AND key_id LIKE 'm04-key-%'` 的占位 DEK；不执行全表 `DELETE`/`TRUNCATE`，
   不触碰其他测试数据。效果：类最后一个测试结束后不再遗留假平台 DEK。
2. `CostAdminApiTest`：新增 `@AfterEach cleanupCostInvocations()`，按本测试新建的模型 ID
   （`createTrackedPricedModel` 登记，模型 ID 全局唯一）删除 `model_invocation` 行。平台成本报告
   仍按原固定日期窗口与全量断言校验，断言口径未变；同 namespace 第二次全量不再把上次运行的成本行
   重复计入 `totals` 与租户桶。

### 2.3 定向验证（修复后）

- 同一 3 类反转定向：**16/0/0/0**（namespace `m19p3cor`）。
- 同 namespace 连续两次正向（`CostAdminApiTest` + `ModelCatalogApiTest` +
  `ModelCredentialLifecycleApiTest` + `ModelCredentialSecurityTest`，共 19 用例）：
  **19/0/0/0** 与第二次 **19/0/0/0**（不 reset）。

定向日志（namespace `m19p3cor`）：

| 文件                                     | 内容                                   | sha256（前 16 位）                      |
| ---------------------------------------- | -------------------------------------- | --------------------------------------- |
| `PRE-FIX-3CLASS-REVERSE.log`             | 修复前 16 用例 / 7 failures（2 + 5）   | `02abfcd82068d701`                      |
| `POST-FIX-3CLASS-REVERSE.log`            | 修复后 16/0/0/0                        | `68ec0639a0979151`                      |
| `POST-FIX-SAME-NS-RUN1.log` / `RUN2.log` | 同 namespace 两次 19/0/0/0（不 reset） | `f37db923bba426a0` / `e6f5fe926d489dcb` |

### 2.4 全量验证（EV-0706）

命令（单行）：fresh namespace `m19p3rev` → 反转全量 → reset → 正向全量 → 不 reset 再跑正向全量；
每轮先清 `apps/api`、`apps/worker`、`platform/*` 的 surefire 报告，并各跑一次 `test-timings`；
末尾 `spotless:check` 与 `git diff --check`。

| 运行                        | 条件                                  | 类数 | 用例 | fail/error/skip | API suite |
| --------------------------- | ------------------------------------- | ---: | ---: | --------------- | --------: |
| 反转全量                    | fresh `m19p3rev`，reversealphabetical |  120 |  642 | 0/0/0           |  426.582s |
| 正向全量                    | reset 后 fresh，默认顺序              |  120 |  642 | 0/0/0           |  417.175s |
| 同 namespace 第二次（正向） | 不 reset，默认顺序                    |  120 |  642 | 0/0/0           |  410.734s |

三轮合计 1926 次用例执行，零失败、零错误、零跳过；`spotless:check` 与 `git diff --check` 均通过。

## 3. F-001：SecurityTestSupport 类级契约

`apps/api/src/test/java/com/riccon/api/security/SecurityTestSupport.java` 类注释已更新：

- 基座归 verifier 所有，fixture 性能改造后由 builder 在 verifier 评审下共同维护；
- 租户、部门、空间与业务对象仍走公开 HTTP；fixture 账号可用 `seedAndLoginLocalUser` 种子写入
  账号/凭据/角色表，但登录、CSRF、会话与权限判定仍走真实 HTTP 与生产代码；
- 账号创建成功路径与创建审计由真实 `createUser` 用例覆盖；账号创建路径没有短密码/密码策略拒绝
  断言（既有缺口），种子路径未取代任何策略测试。

`ApiIntegrationTestSupport` 中相同的过强表述（密码策略由真实 HTTP 创建路径覆盖）同步校正为上述口径。

## 4. F-003：文档与测量口径

`CACHE-DESIGN.md`：

- EV-0700/EV-0703 的 test-timings 含 `apps/worker` 84.809s 残留报告（来自 TASK-0144 EV-0698），
  不是同轮；P3 总体改用 EV-0696 worker ~92s + EV-0704 API 423.582s = 517.137s，对 1236.717s
  降幅 **58.2%**（builder API 467.249s 口径为约 560s / 54.7%，两种均 ≥30%）。
- createUser 表述改为：基线 33 个真实 HTTP createUser，fixture helper 移除 7 个，剩余 26 个调用点不变。
- 密码策略改为：账号创建路径无短密码/策略拒绝断言（既有缺口），种子路径未取代任何策略测试。

`ORDER-DEPENDENCE.md`：

- §3 的 8 个 createCredential 500 改为 9 个（7 ModelCredential + 2 M04Verifier）+ 1 CostAdmin = 10；
- 顶部补充 TASK-0146 修复说明与证据目录。

## 5. 范围与合规

- `git show --stat 6014cbe`：仅 4 个 API 测试文件 + 2 份 `.riccon/evidence/TASK-0145` 文档 +
  TASK-0146/状态文件；生产源码、迁移、pom、CI、Worker 测试零改动。
- 测试断言零增删（仅新增清理生命周期方法、跟踪 helper 与注释）；无 `@Disabled`/assume/skip。
- 代码注释中文；每个新方法有方法级 Javadoc，复杂清理原因有行内说明。
- 先提交后取证：EV-0706 记录于 `6014cbe`，`dirty=false`，命令单行且与日志头一致。

## 6. 工具使用

- CodeGraph：定位 `PlatformCostService`/`PlatformCostJdbc`/成本汇总调用链与测试影响面。
- Serena：Java LSP 符号检查，确认 `cleanup` 仅由 `seedCatalog` 与 `cleanupAfterTest` 引用，
  `createTrackedPricedModel` 由两个成本测试引用；全量 Maven 期间未启动/引导 Java LSP。
- Maven + Docker Compose 热栈（`scripts/test-services.mjs`）执行定向与全量测试。
- `riccon` 记录 EV-0706。

## 7. 遗留风险

1. 清理只覆盖本任务发现的 `ModelCatalogApiTest` 假平台 DEK 与 `CostAdminApiTest` 平台成本行；
   其他未来新增的全局状态测试仍需遵循“按唯一 ID/前缀清理”的约定。
2. surefire 默认 `forkCount=1, reuseForks=true`；若未来改为多 fork/并行，需重新验证类顺序与清理时序。
3. 默认 Testcontainers 模式与 CI 4-shard 未在本任务重跑；EV-0706 使用 external 热栈（与 TASK-0145
   的本地性能证据同口径），模块 gate 仍以合同命令为准。
