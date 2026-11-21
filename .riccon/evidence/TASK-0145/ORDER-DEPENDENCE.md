# TASK-0145 诊断：API 套件的既有顺序/残留依赖与 P3b 的顺序验证

日期：2026-11-21（2026-11-22 由 TASK-0146 校正计数并补充修复说明）
Owner：riccon/builder-m19
范围：只读诊断；未修改 `ModelCatalogApiTest`、`ModelCredential*` 或任何生产代码。

> TASK-0146 修复进展：`ModelCatalogApiTest` 已增加 `@AfterEach` 清理平台占位 DEK/凭据/目录行；
> `CostAdminApiTest` 已增加 `@AfterEach` 按本测试模型 ID 删除 `model_invocation` 行。详见
> `.riccon/evidence/TASK-0146/`。

## 1. 结论

TASK-0145 的平台管理员会话缓存 + fixture 账号种子**没有引入新的类顺序依赖**：

- 改造后正向全量：`EV-0700`，namespace `m19p3b4`，642/0/0/0，API suite 467.249s。
- 改造后关键认证/安全类反转顺序：`EV-0703`，namespace `m19p3b6`，
  `-Dsurefire.runOrder=reversealphabetical`，114/0/0/0。
- 全量反转顺序在**改造前基线** `bc75c04` 上同样失败：642 用例 / 7 failures，失败类与数量完全一致。
  因此 `EV-0702` 的 7 个失败属于既有测试的全局状态依赖，不是 P3b 回归。

## 2. 既有依赖 1：`ModelCatalogApiTest` 遗留假平台 DEK

- `apps/api/src/test/java/com/riccon/api/model/catalog/ModelCatalogApiTest.java:50-57,84-92,307-330`
  在 `@BeforeEach` 插入 `tenant_data_key(tenant_id='platform', key_id='m04-key-*', wrapped_dek=X'00')`
  与占位 `provider_credential`，但**没有 `@AfterEach`**，只在下一个测试的 `@BeforeEach` 里清理。
  该类最后一个测试执行完后，假 DEK 留在共享数据库。
- `ModelCredentialService.activeDataKey()`（`apps/api/src/main/java/.../ModelCredentialService.java:561-576`）
  会选取 `findActiveDataKey()` 返回的任意 ACTIVE 平台 DEK 并尝试 unwrap。
- 默认字母序下 `admin.model.credential` 先于 `model.catalog` 执行，假 DEK 尚未产生；
  `reversealphabetical` 下 `model.catalog` 先执行，`ModelCredentialLifecycleApiTest` /
  `ModelCredentialSecurityTest` 的 `createCredential` 命中假 DEK，抛
  `EnvelopeException: data key cannot be unwrapped by any master key`，返回 500。
- 基线证明：在 worktree `bc75c04`（P3b 改造前）用同一命令/新 namespace `m19p3bx` 复跑，
  642 用例仍为 7 failures（`ModelCredentialLifecycleApiTest` 5、`ModelCredentialSecurityTest` 2）。
  诊断日志：`.riccon/evidence/TASK-0145/BASELINE-REVERSE-ORDER.log`
  （sha256 `c3b840e8439c121e67c255326c9704c35ad4be729b1619ca777b2b814baceb79`）。

## 3. 既有依赖 2：同 namespace 不 reset 的第二次全量

`EV-0701`（namespace `m19p3b4` 不 reset + 反转顺序）出现 10 个失败：

- `CostAdminApiTest.platformReport_hasNoUserFieldsAndFiltersByTenant`：平台成本汇总读到上次运行留下的
  `model_invocation` 成本行，期望 0.009000 实得 0.018000。
- `ModelCredential*` / `M04VerifierGatewaySecurityTest` 的 9 个 createCredential 500（7 个
  ModelCredential + 2 个 M04Verifier）：同上文平台 DEK 冲突，残留 DEK 与当前 master key ring 不匹配。
  合计 9 + 1（CostAdmin）= 10 个失败。

这些类均未被 TASK-0145 修改；API 套件本来只保证“每个 namespace 首次全量”，已有测试没有为
“同 namespace 第二次全量”设计清理。因此：

- P3b 的顺序验证采用 **fresh namespace + 反转顺序**（`EV-0703`），并额外用基线复跑排除回归。
- 同 namespace 不 reset 的全量失败保留为既有残余风险，建议后续单独任务为全局模型/成本测试增加
  显式 reset（不属于 TASK-0145 范围）。

## 4. 无效运行：EV-0699

`EV-0699` 运行期间调用了 Serena `initial_instructions`，启动 Eclipse JDT LS；JDT LS 在运行中重建
`target/test-classes`，导致 354 个 `Failed to load ApplicationContext` / `NoClassDefFound`。
该运行判为无效并由 `EV-0700` 替代；两轮正式全量期间已停止 JDT LS。此事件也验证了项目规则
“全量 Maven 测试期间不得激活/引导 Serena Java LSP”的必要性。
