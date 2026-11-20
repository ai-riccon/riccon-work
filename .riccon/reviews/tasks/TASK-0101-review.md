---
review_id: REV-TASK-0101-001
module_id: M04
task_id: TASK-0101
reviewer:
  agent: riccon/verifier
  session: ses_e062e7325ffe4RRAjaGqce0x1L
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 4d0d7770360a072b80cb48c25d7ddf623f88c015
  - fcc9779ea6744531b6d55226e6b751aae210064d
  - c35f1f0b8f59dc1135b3775413ce95d19f64792b
  - 617fa4b07542d515f7615b3219129c4bf898ab31
head_commit: 617fa4b07542d515f7615b3219129c4bf898ab31
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/builder-crypto（B1b 修复；Lead 确认是否需扩 scope）"
    title: "已知测试密钥拒绝不完整，且可被无填充 Base64 绕过"
    summary: >-
      `MasterKeyRing.KNOWN_TEST_KEYS` 只列了两把与仓库实际测试夹具不同的密钥；仓库当前测试主密钥/上一把密钥
      （`apps/api/src/test/resources/application-test.yml`、`TestCredentials`）不在清单内。`decode()` 先按原始
      Base64 字符串精确比较再解码，而 JDK `Base64.getDecoder()` 接受无填充输入，去掉末尾一个 `=` 的同一把已知
      密钥会通过检查。生产还可通过 `RICCON_CRYPTO_REJECT_TEST_KEYS=false` 关闭拒绝。结果：ADR-0006 §1/§3、
      ADR-0021 §6.5 要求的“生产拒绝已知测试密钥”控制在默认配置下也不覆盖仓库自己的测试密钥。
    evidence: >-
      platform/platform-common/src/main/java/com/riccon/platform/common/crypto/MasterKeyRing.java:56-58（清单）、:366-401（`decode`，:374 为字符串精确匹配）、:389-395（长度校验）；
      platform/platform-common/src/test/java/com/riccon/platform/common/crypto/MasterKeyRingTest.java:24、27、60（用仓库测试密钥 + `rejectTestKeys=true` 断言成功）；
      apps/api/src/test/resources/application-test.yml:15-16；apps/worker/src/test/java/com/riccon/worker/support/TestCredentials.java:11-14（注释明确说“不在生产已知测试密钥清单中”）、19、22；
      apps/api/src/main/java/com/riccon/api/config/CryptoProperties.java:28；apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java:85。
      复现观察：JDK 中 `Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")` 返回 32 字节且与带填充值相等，`MasterKeyRing.load` 因此不会命中测试密钥拒绝。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/lead（明确 B1b/B5/B8 归属；worker CryptoProperties 不在 B1b 当前 write_scope）"
    title: "Worker 未注入/校验 fingerprint pepper，ADR-0021 §6.5 的“API 与 Worker 统一语义”未实现"
    summary: >-
      Worker `CryptoProperties` 没有 `fingerprintPepper` 字段，`MasterKeyProvider` 构造时向 `MasterKeyRing.load`
      传 `null` pepper；生产缺失 pepper 时 Worker 仍可启动。ADR-0021 §6.5 与 `docs/ARCHITECTURE.md` 第 358 行
      明确要求 API 与 Worker 在 `PRODUCTION` 缺失 pepper 即启动失败。Worker yml 已声明该键但被绑定器忽略；
      B1b 的 write_scope 又不含 worker `CryptoProperties.java`，因此需要 Lead 明确由哪个任务补齐，或记录延期决定。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/config/CryptoProperties.java:17-25（无 pepper 字段）；
      apps/worker/src/main/java/com/riccon/worker/crypto/service/MasterKeyProvider.java:35-40（传给 `load` 的 pepper 为 `null`）；
      apps/worker/src/main/resources/application.yml:129-133（声明但无消费）。
  - id: F-003
    severity: P2
    status: open
    owner: "riccon/builder-crypto（API 记录）；riccon/lead（worker 记录同模式）"
    title: "CryptoProperties 记录默认 toString 会输出 masterKey/previousMasterKey/fingerprintPepper"
    summary: >-
      API `CryptoProperties`（本任务新增）与 Worker `CryptoProperties` 都是 Java record，未覆写 `toString()`；
      默认生成的 toString 会把 `masterKey`、`previousMasterKey`、`fingerprintPepper` 原值拼进字符串。当前未发现
      直接日志调用，且 actuator 未暴露 configprops，因此是潜在泄漏；一旦后续直接日志/调试打印或异常上下文输出该
      对象即泄露主密钥，`/actuator/configprops` 的脱敏也依赖 Spring Boot sanitizer，不应作为唯一防线。
      ADR-0006 §5 要求 `toString` 脱敏。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/config/CryptoProperties.java:24-38；
      apps/worker/src/main/java/com/riccon/worker/config/CryptoProperties.java:19-30；
      对照已脱敏的 MasterKeyProvider.toString：apps/api/src/main/java/com/riccon/api/crypto/MasterKeyProvider.java:109-113。
  - id: F-004
    severity: P2
    status: open
    owner: "riccon/builder-crypto（录最终 EV）；riccon/lead（提交任务元数据）"
    title: "EV-0533 绑定 9d07224，不覆盖 end_commit 617fa4b；没有任务级最终提交证据"
    summary: >-
      EV-0533 clean、exit 0、74 个定向测试，但 `commit` 是 `9d07224`，早于 `c35f1f0`（新增分支测试）和
      `617fa4b`（CryptoConfig 包移动 + public）。HEAD 中的 `TASK-0101.json` 仍指向 dirty 的 EV-0530 和旧
      `end_commit fcc9779`；工作区未提交地改成 EV-0533/`617fa4b`。按 `docs/CODING-STANDARDS.md` §10.1，
      高风险加密任务应在最终提交上跑一次受影响模块全量并录 EV。EV-0537（TASK-0100）在 617fa4b 的
      `./mvnw -q -T1C verify` 通过，只能作为集成覆盖，不能替代本任务证据。
    evidence: >-
      .riccon/evidence/TASK-0101/EV-0533.json（commit 9d07224、dirty false、exit 0）；
      .riccon/evidence/TASK-0101/EV-0530.json（dirty true，仍被 HEAD 的任务卡引用）；
      .riccon/evidence/TASK-0100/EV-0537.json（commit 617fa4b、clean、exit 0，task_id=TASK-0100）；
      `git show -s --format='%h %ad %s' 9d07224 c35f1f0 617fa4b`。
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/builder-crypto"
    title: "masterKeyProvider 改为 public 只是跨包测试可见性"
    summary: >-
      617fa4b 将 `CryptoConfig` 从 `com.riccon.api.crypto` 移到 `com.riccon.api.crypto.config` 后，为让
      同部署物测试 `com.riccon.api.crypto.CryptoStartupValidationTest` 继续直接调用 bean 方法，将
      `masterKeyProvider` 改为 public。生产代码不需要该可见性；Spring 支持包私有 `@Bean` 方法。
      建议测试改用 `ApplicationContextRunner`，或把测试放到 `com.riccon.api.crypto.config` 同包，保持方法包私有。
    evidence: >-
      `git show 617fa4b -- apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java`；
      apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java:60-61；
      apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java:61、73、84、93、110-112、125-126、137、152。
  - id: F-006
    severity: P3
    status: open
    owner: "riccon/builder（B5/B9a 安全加固或单独任务）"
    title: "Worker 解密路径与 API 加密路径仍有临时明文/DEK 数组未清零"
    summary: >-
      `EnvelopeDecryptor.decrypt` 对 `masterKeyProvider.unwrap` 返回的 `dekBytes` 和 `Cipher.doFinal` 返回的
      `plaintext` 没有 `Arrays.fill(..., 0)`；API `CredentialCipher.encrypt` 创建的 `plaintext.getBytes(UTF_8)`
      临时数组也未清零。`MasterKeyRing`/`CredentialEnvelope` 自身已清零 nonce/payload/临时密钥，且没有字段缓存，
      因此 AC“无明文缓存”仍通过；这是纵深防御缺口，不是缓存泄漏。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/crypto/service/EnvelopeDecryptor.java:60、73-74；
      apps/api/src/main/java/com/riccon/api/crypto/CredentialCipher.java:28-33；
      对照已清零实现：platform/platform-common/src/main/java/com/riccon/platform/common/crypto/CredentialEnvelope.java:108-110、140-142、183；
      platform/platform-common/src/main/java/com/riccon/platform/common/crypto/MasterKeyRing.java:396-401。
  - id: F-007
    severity: P3
    status: open
    owner: "riccon/lead（任务元数据）"
    title: "TASK-0101 baseline_commit 3df8dc0 只覆盖 617fa4b，主实现提交未被自动 write_scope 校验"
    summary: >-
      4d0d777 的真实父提交是 `62a9e0c`；工作区把 `TASK-0101.json` 的 baseline_commit 改成 `3df8dc0`（晚于
      4d0d777/c35f1f0），因此 `git diff 3df8dc0..HEAD` 只包含 3 个文件，自动 write_scope 校验覆盖不到
      4d0d777/fcc9779/c35f1f0。人工核对 4 个提交的全部文件均在 9 项 write_scope 内，未修改
      contracts/、application.yml 或 SecurityConfig；但元数据应恢复为真实基线或记录人工审计理由。
    evidence: >-
      .riccon/tasks/TASK-0101.json:40-41（工作区 baseline 3df8dc0、end 617fa4b）；
      `git rev-parse 4d0d777^` = 62a9e0c；`git diff --name-only 3df8dc0..HEAD` 仅 3 个文件；
      `git diff --name-only 4d0d777^..617fa4b` 及逐个 commit 的 `git show --name-status` 人工核对。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/lead（B10/RUNBOOK）"
    title: "test profile 未注入主密钥时允许 API 启动（操作级失败关闭）"
    summary: >-
      `CryptoConfig` 对显式 `test` profile 在缺少 `RICCON_MASTER_KEY` 时返回 `MasterKeyRing.unconfigured`，
      API 可启动但任何 wrap/unwrap/fingerprint 都抛 `NOT_CONFIGURED`；默认与非 test profile 仍 fail-closed。
      作为测试便利可接受；剩余风险是生产误设 `SPRING_PROFILES_ACTIVE=test`。建议 B10/RUNBOOK 明确禁止
      生产使用 test profile，并考虑加启动守卫或显式 warning 门禁。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java:68-77；
      platform/platform-common/src/main/java/com/riccon/platform/common/crypto/MasterKeyRing.java:140-142、416-423；
      apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java:131-144。
evidence:
  - EV-0533
  - EV-0537
reviewed_evidence:
  - EV-0533
  - EV-0537
notes: >-
  定向测试由 verifier 在 617fa4b（工作区仅 .riccon 变更）独立复跑：84 tests、0 failures、exit 0；
  EV-0533 是 builder 的 74-test clean 定向证据，EV-0537 是 Lead 在 617fa4b 的后端全量 verify。
created_at: "2026-11-21T00:49:42+08:00"
---

# REV-TASK-0101-001：TASK-0101 独立评审

本文件只做任务级独立技术验收，不参与模块 gate，也不代表 Product 预验收或用户产品验收。

## 结论

**fail**：6 条 AC 在功能层面全部可复现通过，但 F-001 使“生产拒绝已知测试密钥”的安全控制失效（P1），
且 F-002/F-003/F-004 三个 P2 需在任务 done 前处理或书面接受。当前不能把 TASK-0101 标为 done。

## 逐条验收（6 条）

| #   | 任务 AC 原文                              | 结论            | 证据与说明                                                                                                                                                                                                                                                                                                                                 |
| --- | ----------------------------------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | API 加密→Worker 解密往返                  | pass            | `CrossServiceEnvelopeRoundTripTest` 7 个用例；API 侧 `CredentialCipherTest` 6 个用例；Worker `EnvelopeDecryptor` 真实生产实现解密成功。密文格式 `enc:v1:<keyId>:<Base64(nonce + ciphertext + tag)>` 与 Worker parser 一致；`CredentialEnvelope.aad` 与 `EnvelopeDecryptor.aad` 逐字节相等（schemaVersion 都是 1）。                        |
| 2   | AAD/篡改/跨租户失败                       | pass            | `CredentialEnvelopeTest` 的跨租户/跨用途/篡改/key-id 不匹配；`CrossServiceEnvelopeRoundTripTest` 的跨租户密文/跨租户 DEK/篡改/wrong key-id；`EnvelopeDecryptorTest` 的租户 mismatch、purpose/algorithm、嵌 key-id、AAD mismatch，以及未知 key-id、null/缺段/非 Base64/截断等格式非法失败。错误码统一为 `RICCON-CRYPTO-500`，断言不含明文。 |
| 3   | 日志 canary 不含 key                      | pass（有保留）  | `CryptoStartupValidationTest.startupFailure_neverLogsMasterKey` 启动真实 Spring 应用并扫描 root logger 消息+异常链，断言不含测试主密钥；`MasterKeyRingTest.toString_neverExposesKeyMaterial` 覆盖 ring toString。保留：`CryptoProperties` 默认 record toString 未脱敏（F-003），当前无日志调用点。                                         |
| 4   | API current+previous+master-key-id+canary | pass            | `CryptoStartupValidationTest.previousMasterKey_isWiredIntoProvider` 验证 previous 解包；`validConfiguration_createsConfiguredProvider` 验证 masterKeyId；`MasterKeyRingTest.unwrap_previousKey_succeeds`；canary 由 `MasterKeyRing.selfCheck` 固定明文+AAD 完成。注意 `RICCON_MASTER_KEY_CANARY` 在 yml 中未被消费（见剩余风险）。         |
| 5   | 测试主密钥占位                            | pass（AC 字面） | `apps/api/src/test/resources/application-test.yml:15-16`、`apps/api/src/test/resources/application-test.yml:19`、worker 测试资源与 `TestCredentials` 都有 32 字节占位值；不是真实密钥。但仓库测试密钥本身未被 known-test-key 拒绝覆盖（F-001）。                                                                                           |
| 6   | 无明文缓存                                | pass（有保留）  | `MasterKeyRing.unwrap` 每次返回新数组；`MasterKeyRingTest.unwrap_returnsFreshArrayEachCall`、`wrappedDek_isDefensiveCopy`；`MasterKeyRing`/`CredentialEnvelope` 字段只有 `SecretKey`/不可变配置，无 DEK/明文缓存。保留：worker 解密与 API 加密的临时数组未清零（F-006），但无字段缓存。                                                    |

## 重点结论

1. **格式与 AAD 一致**：API 侧 `CredentialEnvelope.aad()` 生成 `tenantId|purpose|keyId|1`；Worker 侧
   `EnvelopeDecryptor.aad()` 生成同样字节串。`CrossServiceEnvelopeRoundTripTest.aad_matchesWorkerDecryptorExactly`
   直接比较两侧字节；`decrypt` 的 parser 与前缀、nonce/tag 长度一致。跨服务往返在共享实现 + Worker 生产解密器
   层面成立。
2. **主密钥环语义**：current/previous/masterKeyId/canary/pepper 均已进入 `MasterKeyRing`；previous 解包、
   canary 自检、pepper HMAC、脱敏 `toString` 有测试。API 生产缺 master key/pepper 失败，test profile 缺失时
   fail-closed 占位可接受（F-008 accepted）。
3. **F-001 是真实安全缺口**：known-test-key 清单未覆盖仓库测试密钥，且以原始 Base64 字符串精确比较；JDK
   Base64 解码接受无填充变体。去掉一个 `=` 的 `AAECAwQ...` 仍能通过 `rejectTestKeys=true`。这不是“未测到”，
   而是控制实现本身可绕过。
4. **CryptoConfig 移动**：617fa4b 只改 package/import 和 bean 方法可见性，bean 名 `masterKeyProvider`、
   `credentialCipher` 与 Bean 类型/创建逻辑不变；ArchUnit `configurationClassesLiveInConfigPackage` 在
   EV-0537 全量 verify 中通过。`masterKeyProvider` public 仅测试便利（P3，F-005）。
5. **边界**：4 个提交没有修改 contracts/、application.yml、SecurityConfig；`.riccon/**` 在 write_scope 内。
   人工逐 commit 核对 9 项 write_scope 未发现越界。F-007 仅指出 baseline 元数据使自动校验覆盖不完整。
6. **测试质量**：新增分支测试是真实断言，无反射调用私有 helper、无 `@Disabled`/`assumeTrue`、未新增
   Jacoco 排除（仍只有 `WorkerApplication`/`ApiApplication` 启动类）。`CryptoDomainValueTest` 补齐既有域对象
   分支，属于覆盖率补齐而非新功能假绿。
7. **覆盖率**：EV-0537 在 617fa4b 的 `./mvnw -q -T1C verify` exit 0；Jacoco XML（本次运行生成）：
   worker LINE 4263/(4263+615)=87.4%、BRANCH 1327/(1327+546)=70.85%；API LINE 91.5%、BRANCH 73.9%；
   platform-common LINE 94.5%、BRANCH 85.2%。合同门槛 80/70 通过；worker 分支只剩 0.85pp 余量，属剩余风险。

## 验证了什么

- **提交与范围**：`git rev-parse HEAD` = 617fa4b；逐个 `git show --stat/--name-status` 检查
  4d0d777（16 文件）、fcc9779（1 测试）、c35f1f0（3 测试）、617fa4b（3 文件）。全部落在 TASK-0101
  9 项 write_scope；未触碰 contracts/、应用 yml、SecurityConfig。
- **独立定向复跑**：在 HEAD（工作区仅 `.riccon` 变更）执行
  `./mvnw -q -T1C -pl platform/platform-common,apps/api,apps/worker -am test -Dtest='MasterKeyRingTest,CredentialEnvelopeTest,CredentialCipherTest,CryptoStartupValidationTest,CrossServiceEnvelopeRoundTripTest,MasterKeyProviderTest,EnvelopeDecryptorTest,CryptoDomainValueTest' -Dsurefire.failIfNoSpecifiedTests=false`
  → exit 0；surefire 报告合计 84 tests、0 failures、0 errors、0 skipped
  （MasterKeyRing 23、CredentialEnvelope 12、CryptoStartupValidation 9、CredentialCipher 6、
  CrossService 7、CryptoDomainValue 5、EnvelopeDecryptor 12、MasterKeyProvider 10）。
- **EV 审计**：EV-0533 clean/exit 0/commit 9d07224/74 tests；EV-0530 dirty 但仍在 HEAD 任务卡；
  EV-0537 clean/exit 0/commit 617fa4b/后端全量 verify（task_id=TASK-0100）。F-004 记录最终提交证据缺口。
- **密钥扫描**：对 4 个提交的 diff、测试源码和 `.riccon/evidence/` 扫描 `RICCON_MASTER_KEY=<值>`、
  常见 `sk-...`、真实私钥/API key 模式，未发现真实密钥；测试/证据中只有仓库占位值。
- **代码路径**：对照 `CredentialEnvelope`、`EnvelopeDecryptor`、`MasterKeyRing`、API/Worker `MasterKeyProvider`、
  `CryptoConfig`、`CryptoProperties`；错误消息/异常链不含密钥、DEK、nonce 或密文。
- **编码规范/注释**：新增类与 public 方法均有中文 Javadoc，安全边界有中文说明；测试命名符合
  `被测方法_场景_期望`，未发现无 owner 的 TODO、注释掉的代码或英文业务注释。
- **文档影响**：本任务未改 docs；实现与 ADR-0006、ADR-0021 §6.5 对齐，RUNBOOK/rewrap/四步轮换属于 B10，
  因此本任务 docs no-impact 成立。

## 未验证

- **模块级全量合同验证**：本次未跑 `commands.build/lint/typecheck/test/coverage/test_e2e/security/perf`
  的模块级组合；EV-0537 只覆盖 `./mvnw -q -T1C verify`（后端），不含前端/E2E/契约/安全扫描。任务级评审不替代
  模块 gate。
- **真实“API 服务写库 → Worker 读库解密”集成路径**：`CrossServiceEnvelopeRoundTripTest` 用
  `platform-common` 共享实现模拟 API 侧（与 API `CredentialCipher` 同一条委托路径），并直接调用 Worker
  生产 `EnvelopeDecryptor`；没有启动 API HTTP、写 MySQL、再由 Worker 轮询的端到端链路。该链路应在 B5/B8
  或模块 E2E 覆盖。
- **数据库既有 `tenant_data_key` 可解性校验与 rewrap**：ADR-0006 §3 第 3/4 步及 ADR-0021 §6.5 的
  `scripts/ops/rewrap-dek` 属于 B10；本任务只验证 canary，不验证全库 DEK 或“旧 master_key_id 残留则启动失败”。
- **canary 失败注入**：未模拟 AES-GCM 不可用/自检失败（JDK 21 固定提供，难以注入）；`CANARY_FAILED` 分支
  只有代码审查，无运行证据。
- **Worker pepper 启动失败**：当前实现不校验，无法验证；见 F-002。
- **生产真实密钥注入/轮换**：只用测试占位和 `MockEnvironment`/测试 yml，未用真实部署 env 或 KMS/Secret store。
- **UI/可访问性/性能/迁移**：本任务无 UI、无页面、无迁移；不适用，未跑 axe/k6/迁移演练。

## Gate check

`node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" gate check --module M04` → **FAIL**
（2026-11-21T00:51:59+08:00，20 errors / 1 warning）：17 个任务仍非 done、TASK-0100/TASK-0102 有 write_scope
越界报错、缺少 M04 模块评审。TASK-0101 本身没有 write_scope 报错（自动区间只覆盖 617fa4b，见 F-007），
但本评审 F-001 为 P1 open，因此 gate 不应通过；模块 gate 还需等待全部任务 done、模块评审和最终提交证据。

## Findings

| ID    | 严重级 | 状态     | 摘要                                                                                                               | Owner                 |
| ----- | ------ | -------- | ------------------------------------------------------------------------------------------------------------------ | --------------------- |
| F-001 | P1     | open     | known-test-key 拒绝不覆盖仓库测试密钥，且无填充 Base64 可绕过；`RICCON_CRYPTO_REJECT_TEST_KEYS=false` 可在生产关闭 | builder-crypto        |
| F-002 | P2     | open     | Worker 不注入/校验 fingerprint pepper，违反 ADR-0021 §6.5 统一语义                                                 | lead（明确归属）      |
| F-003 | P2     | open     | `CryptoProperties` record 默认 toString 输出 masterKey/previousMasterKey/pepper                                    | builder-crypto / lead |
| F-004 | P2     | open     | EV-0533 绑定 9d07224，不覆盖 617fa4b；任务卡仍引用 dirty EV-0530，缺最终提交 EV                                    | builder-crypto / lead |
| F-005 | P3     | open     | `masterKeyProvider` 为测试便利改为 public；建议 ApplicationContextRunner 或同包测试                                | builder-crypto        |
| F-006 | P3     | open     | Worker 解密 dekBytes/plaintext、API encrypt 临时明文未清零；无缓存但纵深防御不足                                   | builder（B5/B9a）     |
| F-007 | P3     | open     | baseline_commit 3df8dc0 只覆盖 617fa4b，主实现提交未被自动 write_scope 校验；人工审核通过                          | lead                  |
| F-008 | P3     | accepted | test profile 无主密钥可启动，但操作级 fail-closed；需 RUNBOOK 禁止生产 test profile                                | lead（B10）           |

### F-001 复现要点

- `MasterKeyRing.KNOWN_TEST_KEYS`（MasterKeyRing.java:56-58）只有 `AAEC...` 与 `cmlj...` 两把；
  仓库实际测试主/上一把是 `cmljY29uLXdvcmtlci10ZXN0LW1hc3Rlci1rZXktMzI=` 和
  `MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=`（application-test.yml:15-16、TestCredentials.java:19/22），
  都不在清单内。`TestCredentials.java:11-14` 的注释还明确写“且不在生产已知测试密钥清单中”，说明这是被
  认知的例外而非遗漏，但没有对应的决策/风险接受记录。
- `MasterKeyRing.decode()`（MasterKeyRing.java:366-401）在 Base64 解码前做 `KNOWN_TEST_KEYS.contains(value)`
  精确字符串比较；JDK `Base64.getDecoder()` 接受无填充输入。实际验证：不带末尾 `=` 的
  `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` 解码仍是 32 字节且与带填充值相等。
- `MasterKeyRingTest.load_validConfiguration_isConfigured`（MasterKeyRingTest.java:60）用仓库测试密钥 +
  `rejectTestKeys=true` 断言成功，等于把该缺口固化进测试；修复时需同步调整 API/Worker test fixture（例如
  使用非仓库密钥，或在测试 profile/显式 `rejectTestKeys=false` 下使用）。
- 生产路径：`CryptoConfig` 的 `rejectTestKeys = properties.rejectTestKeys() && !testProfile`
  （CryptoConfig.java:85）；`CryptoProperties.rejectTestKeys` 默认 true，但 `RICCON_CRYPTO_REJECT_TEST_KEYS=false`
  可绑定关闭，无生产守卫。

## 证据

- 任务证据：`.riccon/evidence/TASK-0101/EV-0533.json`（clean、exit 0、commit 9d07224、74 定向测试；
  gate 输出确认其日志 hash 与记录一致）。
- 脏证据（HEAD 任务卡仍引用）：`.riccon/evidence/TASK-0101/EV-0530.json`（dirty true）。
- 集成全量：`.riccon/evidence/TASK-0100/EV-0537.json`（clean、exit 0、commit 617fa4b、
  `./mvnw -q -T1C verify`，task_id=TASK-0100）。
- 本次 verifier 定向复跑：上面的 Maven 命令，exit 0；报告在
  `platform/platform-common/target/surefire-reports/`、`apps/api/target/surefire-reports/`、
  `apps/worker/target/surefire-reports/`（84 tests/0 failures；未录 EV，仅作评审证据）。
- 覆盖率：`apps/worker/target/site/jacoco/jacoco.xml`、`apps/api/target/site/jacoco/jacoco.xml`、
  `platform/platform-common/target/site/jacoco/jacoco.xml`（EV-0537 运行生成；worker 87.4%/70.85%，
  API 91.5%/73.9%，platform-common 94.5%/85.2%）。
- 提交：4d0d777、fcc9779、c35f1f0、617fa4b；HEAD=617fa4b。

## 剩余风险

- worker 分支覆盖率 70.85% 仅比门槛高 0.85pp；后续 B4/B5 改动可能把 bundle 拉回 70% 以下。
- `apps/api/src/main/resources/application.yml:105` 与 `apps/worker/src/main/resources/application.yml:130`
  暴露 `RICCON_MASTER_KEY_CANARY`，但 `CryptoProperties` 没有该字段，实际 canary 是 `MasterKeyRing` 固定常量；
  这是 B1a 配置债，运维可能误以为可配置。建议 B10 清理或注明“不参与校验”。
- `CryptoProperties` 明文 String 在 Spring 配置 bean 中驻留整个进程生命周期；这是配置绑定的常规代价，不是
  DEK/凭据缓存，但配合 F-003 的 toString 风险需要脱敏。
- 生产误设 `SPRING_PROFILES_ACTIVE=test` 可绕过主密钥启动校验（操作级 fail-closed，F-008 accepted）；
  `RICCON_CRYPTO_REJECT_TEST_KEYS=false` 可在生产关闭 known-test-key 拒绝（F-001 的一部分）。
- 真实跨服务 DB/HTTP 链路、全库 DEK 可解性、rewrap 与 canary 失败注入仍未由本任务验证，属于 B5/B8/B10/模块 E2E。

## 下一步建议

1. 修复 F-001：`decode` 先 Base64 解码再按 32 字节比较 known-test-key；把 `application-test.yml` /
   `TestCredentials` 的主/上一把测试密钥加入清单；禁止生产关闭拒绝（或把 `rejectTestKeys=false` 限制在 test profile）。
2. 处理 F-002：在 worker `CryptoProperties` 增加 pepper 并在生产缺失时 fail-closed，或由 Lead 明确延期到
   B5/B8 并记录决定；同步更新 ADR/计划中的归属。
3. 处理 F-003：为 API/Worker `CryptoProperties` 覆写脱敏 `toString()`（以及必要的 `equals/hashCode` 审查）。
4. 在最终提交（修复后）重跑受影响模块全量并录 EV；把 EV-0533 与 EV-0530 的引用清理干净，任务卡 baseline
   恢复真实基线（或写明人工 scope 审计）。
5. F-005/F-006/F-007/F-008 为 P3：可按书面理由接受，但建议在 B5/B9a/B10 一并收敛。
