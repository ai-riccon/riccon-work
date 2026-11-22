---
review_id: REV-TASK-0101-002
previous_review: REV-TASK-0101-001
module_id: M04
task_id: TASK-0101
reviewer:
  agent: riccon/verifier
  session: ses_e062e7325ffe4RRAjaGqce0x1L
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: ["272e690", "ef2b0f2", "b09aca2"]
head_commit: b09aca222ed667e6764b495e60843b1cf6cc5789
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-crypto"
    title: "已知测试密钥拒绝不完整且可被无填充 Base64 绕过"
    summary: >-
      已按解码后 32 字节十六进制比较；清单覆盖仓库 API/Worker 实际测试主密钥与上一把密钥，
      并覆盖历史测试密钥与 URL-safe 哨兵。无填充、URL-safe 变体均被拒；额外 padding 由 JDK
      两种解码器拒绝为非法 Base64（fail-closed）。API 非 test profile 关闭拒绝会启动失败；
      Worker Spring 装配强制 true。EV-0543 98 tests 与本次独立复跑均通过。
    evidence: >-
      platform/platform-common/src/main/java/com/riccon/platform/common/crypto/MasterKeyRing.java:65-82（KNOWN_TEST_KEY_HEX）、:387-420（decode，:402 十六进制比较）、:425-438（标准/URL-safe 解码）；
      platform/platform-common/src/test/java/com/riccon/platform/common/crypto/MasterKeyRingTest.java:36-42、143-181；
      apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java:84-94；
      apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java:47、94、105、117；
      apps/worker/src/main/java/com/riccon/worker/crypto/service/MasterKeyProvider.java:40-69；
      apps/worker/src/test/java/com/riccon/worker/crypto/MasterKeyProviderTest.java:143-160。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/lead（B10/ADR 澄清）"
    title: "Worker 不校验 fingerprint pepper（Lead 决策：pepper 仅 API）"
    summary: >-
      Lead 已决定 Worker 只校验主密钥/canary，pepper 仅 API；Worker `MasterKeyProvider` javadoc
      明确记录该口径，代码不注入、不校验 pepper。Worker 解密不需要 pepper，安全边界可接受。
      遗留：ADR-0021 §6.5 与 docs/ARCHITECTURE.md 第 358 行仍写“API 与 Worker 都必须注入 pepper”，
      需在 B10/RUNBOOK 或 ADR 澄清中同步，避免文档与实现冲突。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/crypto/service/MasterKeyProvider.java:23-27、40-69；
      apps/worker/src/main/java/com/riccon/worker/config/CryptoProperties.java:14-30（无 pepper 字段）；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:472；docs/ARCHITECTURE.md:358。
  - id: F-003
    severity: P2
    status: fixed
    owner: "riccon/builder-crypto"
    title: "CryptoProperties record 默认 toString 输出主密钥/pepper"
    summary: >-
      API 与 Worker `CryptoProperties` 均已覆写 `toString()`：`masterKey`、`previousMasterKey`、
      `fingerprintPepper` 只输出 `[redacted]`/`[not set]`；`masterKeyId` 与 `rejectTestKeys` 保留可诊断。
      API `CryptoPropertiesTest`、Worker `WorkerCryptoPropertiesTest` 均断言原值不出现，EV-0543 通过。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/config/CryptoProperties.java:48-64；
      apps/worker/src/main/java/com/riccon/worker/config/CryptoProperties.java:41-57；
      apps/api/src/test/java/com/riccon/api/crypto/CryptoPropertiesTest.java:19-36；
      apps/worker/src/test/java/com/riccon/worker/config/WorkerCryptoPropertiesTest.java:17-33。
  - id: F-004
    severity: P2
    status: fixed
    owner: "riccon/builder-crypto（提交任务元数据）/ riccon/lead"
    title: "EV-0533 绑定早期提交，缺少最终提交证据"
    summary: >-
      EV-0543 绑定最终提交 `b09aca2`、dirty=false、exit 0，覆盖 98 个 crypto/配置测试；
      任务卡工作区 evidence 为 `[EV-0533, EV-0543]`，无 dirty EV；EV-0542 的 exit 1 失败尝试未进入任务证据。
      注意：任务卡的 evidence/end_commit/新增 write_scope 目前仍是工作区未提交变更，HEAD 的任务卡仍指向
      EV-0533/end `617fa4b`；task done 前必须提交并复核。
    evidence: >-
      .riccon/evidence/TASK-0101/EV-0543.json（commit b09aca2、dirty false、exit 0）；
      .riccon/evidence/TASK-0101/EV-0542.json（commit 46a6ceb、dirty false、exit 1，失败尝试）；
      .riccon/tasks/TASK-0101.json（工作区 evidence 两项、end b09aca2；HEAD 仍是 EV-0533/end 617fa4b）。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-crypto；到期 B5 或 M04 gate 前（2026-11-22）"
    title: "masterKeyProvider 仍为 public，且 Worker 直接构造 public 保留 false 入口"
    summary: >-
      `CryptoConfig.masterKeyProvider` 仍为 public，只为 `com.riccon.api.crypto.CryptoStartupValidationTest`
      跨包直接调用；建议测试改用 ApplicationContextRunner 或移到 `crypto.config` 同包，保持包私有。
      Worker `new MasterKeyProvider(CryptoProperties)` 仍按 `properties.rejectTestKeys()` 工作；Spring 装配
      的 2 参构造强制非 test profile true，生产注入不受影响，但直接构造仍是测试便利入口。作为 P3 接受，
      不阻塞本任务；B5/M04 gate 前收窄或补注释。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java:60-61；
      apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java:61-162；
      apps/worker/src/main/java/com/riccon/worker/crypto/service/MasterKeyProvider.java:40-51。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder（B5/B9a 安全加固）；到期 M04 安全评审/B10 前（2026-11-25）"
    title: "Worker 解密与 API 加密路径仍有临时明文/DEK 数组未清零"
    summary: >-
      原 F-006 未在本轮修复：`EnvelopeDecryptor.decrypt` 的 `dekBytes`/`plaintext`、API
      `CredentialCipher.encrypt` 的 UTF-8 临时明文字节仍未 `Arrays.fill(..., 0)`；`MasterKeyRing`/
      `CredentialEnvelope` 自身已清零，且无字段缓存，AC“无明文缓存”仍通过。接受为 P3 纵深防御债，
      不阻塞任务；B5/B9a 安全加固时收敛。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/crypto/service/EnvelopeDecryptor.java:60、73-74；
      apps/api/src/main/java/com/riccon/api/crypto/CredentialCipher.java:28-33；
      对照 platform/platform-common/src/main/java/com/riccon/platform/common/crypto/CredentialEnvelope.java:108-110、140-142、183。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/lead；到期 TASK-0101 done 前（M04 gate）"
    title: "baseline_commit 3df8dc0 覆盖区间把 B2 提交算入 TASK-0101"
    summary: >-
      三个修复提交未触碰 B2 源码、SecurityConfig、contracts 或其他任务文件；272e690 的 crypto 文件在
      HEAD 任务卡 scope 内，ef2b0f2 的 Worker CryptoProperties/测试依赖任务卡工作区新增的 write_scope
      （HEAD 旧卡不含），该 scope 变更需提交并由 Lead 确认。问题在任务元数据：baseline 仍是 3df8dc0，
      当前 HEAD 的 `gate check --module M04` 已把 4752c4f..46a6ceb 的 B2 文件报为 TASK-0101
      out-of-scope。人工逐提交核对代码范围通过；baseline/end 或任务拆分需由 Lead 在 done 前修正。
      接受 P3 仅针对代码范围，不是 gate 豁免。
    evidence: >-
      .riccon/tasks/TASK-0101.json:40-41（baseline 3df8dc0、end b09aca2）；
      272e690/ef2b0f2/b09aca2 的 `git show --name-status`；
      `node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" gate check --module M04`（2026-11-21T01:53:26+08:00）
      输出 TASK-0101 changed files outside write_scope 含 B2 目录。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/lead（B10/RUNBOOK）；到期 M04 发布前"
    title: "test profile 未注入主密钥时允许 API 启动"
    summary: >-
      `CryptoConfig` 对显式 test profile 缺少主密钥时返回 `MasterKeyRing.unconfigured`，API 可启动，
      但任何 wrap/unwrap/fingerprint 抛 `NOT_CONFIGURED`；默认与非 test profile 仍 fail-closed。
      接受为 P3；建议 B10/RUNBOOK 明确禁止生产使用 test profile，并加启动守卫或 warning 门禁。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/crypto/config/CryptoConfig.java:68-77；
      platform/platform-common/src/main/java/com/riccon/platform/common/crypto/MasterKeyRing.java:157-159、452-460；
      apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java:171-191。
evidence: [EV-0533, EV-0543]
reviewed_evidence: [EV-0533, EV-0537, EV-0541, EV-0543]
notes: >-
  本次 verifier 在 b09aca2（工作区仅 .riccon 变更）独立复跑 EV-0543 命令：98 tests、0 failures、exit 0；
  EV-0543 clean/exit 0/commit b09aca2；EV-0541 在 46a6ceb 覆盖 ArchUnit 与 platform-common verify；
  EV-0537 是 617fa4b 的后端全量 verify（修复前）。
created_at: "2026-11-21T01:57:02+08:00"
---

# REV-TASK-0101-002：TASK-0101 修复复评

本文件是 `REV-TASK-0101-001` 的复评，只做任务级独立技术验收；不参与模块 gate，也不代表
Product 预验收或用户产品验收。不修改代码/测试、不提交、不标 done。

## 结论

**pass**：F-001 已关闭；F-002 按 Lead 决策接受；F-003/F-004 已修复；F-005/F-006/F-007/F-008
按 P3 accepted 跟踪。没有 open P0/P1。模块 gate 仍因任务状态、M04 模块评审和 TASK-0101 baseline
区间（F-007）失败，需 Lead 另行收敛元数据，不代表代码整改失败。

## F-001 逐用例验证

1. **标准 padded 历史测试密钥**：`AAECAwQ...` 与 `cmljY29uLXRlc3Q...` 均在解码后被
   `KNOWN_TEST_KEY_HEX` 命中，抛 `TEST_KEY_REJECTED`。测试：`MasterKeyRingTest.load_knownTestKey_rejectedByDefault`、
   `CryptoStartupValidationTest.knownTestKey_defaultProfile_failsStartup`。
2. **仓库实际测试主密钥/上一把密钥**：`cmljY29uLXdvcmtlci10ZXN0LW1hc3Rlci1rZXktMzI=` 与
   `MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=` 的十六进制值已加入清单；API 启动与
   `MasterKeyRing.load(..., true)` 均拒绝。测试：`MasterKeyRingTest.load_repositoryTestKeys_rejectedByDefault`、
   `CryptoStartupValidationTest.repositoryTestKey_defaultProfile_failsStartup`。
3. **无填充变体**：`REPOSITORY_TEST_KEY_UNPADDED` 先被标准 Base64 解码为同一 32 字节，再按 hex 命中拒绝；
   测试：`MasterKeyRingTest.load_unpaddedRepositoryTestKey_rejected`。本次独立 JDK 验证也确认无填充
   解码结果与 padded 相同。
4. **URL-safe 变体**：标准字母表哨兵 `+/v7...` 与 URL-safe 哨兵 `-_v7...` 解码后 hex 相同，均命中拒绝；
   测试：`MasterKeyRingTest.load_urlSafeVariantOfKnownKey_rejected`。关闭拒绝时 URL-safe 变体可正常装配，
   证明拒绝来自字节比较而非解码失败（`load_urlSafeVariant_allowedWhenRejectionDisabled`）。
5. **不同 padding**：额外 `=` 被 JDK 标准与 URL-safe 解码器同时判为非法 Base64，`decodeBase64` 抛
   `MasterKeyConfigException(MASTER_KEY_INVALID)`，启动 fail-closed；仓库没有专门用例，但本次用 JDK
   Base64 独立验证了 `base+"="`/`base+"=="` 两种变体均不被接受。该路径不退化为明文。
6. **生产关闭拒绝**：API `CryptoConfig` 在非 test profile 发现 `rejectTestKeys=false` 时抛
   `TEST_KEY_REJECTION_DISABLED`；测试 `CryptoStartupValidationTest.rejectTestKeysDisabled_defaultProfile_failsStartup`。
   Worker Spring 装配使用 `@Autowired` 2 参构造，非 test profile 强制 `!environment.matchesProfiles("test")`；
   测试 `MasterKeyProviderTest.constructor_nonTestProfile_rejectsRepositoryTestKeyEvenIfDisabled`。
7. **decode 顺序**：`decodeBase64` 先标准、失败再 URL-safe；随后校验 32 字节长度，再用
   `HexFormat.of().formatHex(keyBytes)` 查 `KNOWN_TEST_KEY_HEX`。不再存在原始字符串精确匹配分支。

独立复跑：在 HEAD `b09aca2`（工作区仅 `.riccon` 变更）执行 EV-0543 同一命令 →
**98 tests、0 failures、0 errors、0 skipped、exit 0**（MasterKeyRing 28、CredentialEnvelope 12、
CryptoStartupValidation 12、CredentialCipher 6、CryptoProperties 2、CrossService 7、
CryptoDomainValue 5、EnvelopeDecryptor 12、WorkerCryptoProperties 2、MasterKeyProvider 12）。

## F-002 决策接受

Worker 只校验主密钥与 canary，不注入/校验 fingerprint pepper；pepper 仅 API 使用。`MasterKeyProvider`
javadoc 已明确写清“M04 口径（Lead 决策）”及“ADR 原文未改，归属见 B5/B10”。Worker 解密确实不需要
pepper，接受为 **P2 accepted**。遗留文档问题：ADR-0021 §6.5 与 `docs/ARCHITECTURE.md:358` 仍写
API 与 Worker 都必须注入 pepper；B10/RUNBOOK 或 ADR 澄清前保持风险记录，避免运维误判。

## F-003 修复

API `CryptoProperties.toString()`（:48-64）与 Worker `CryptoProperties.toString()`（:41-57）均只输出
`[redacted]`/`[not set]`；`masterKeyId`、`rejectTestKeys` 保留可诊断。`CryptoPropertiesTest` 与
`WorkerCryptoPropertiesTest` 断言 `master-value`/`previous-value`/`pepper-value` 不出现，EV-0543
通过。F-003 **fixed**。

## F-004 证据

EV-0543 绑定 `b09aca2`、`dirty=false`、`exit 0`，命令为 98 个 crypto/配置测试；任务卡工作区
`evidence: [EV-0533, EV-0543]`，没有 dirty EV。EV-0542 是 `46a6ceb` 的失败尝试（exit 1），
未进入任务证据。F-004 **fixed**（证据文件层面）。

注意：`.riccon/tasks/TASK-0101.json` 的 evidence/end_commit/新增 write_scope 仍是未提交工作区变更；
HEAD 版本仍只有 `EV-0533` 和 `end_commit=617fa4b`。task done 前必须提交任务卡并复核，否则严格证据
绑定可能仍读旧卡。

## F-005/F-006/F-007/F-008

- **F-005（P3 accepted）**：`CryptoConfig.masterKeyProvider` 仍 public，只为跨包测试直接调用；建议
  ApplicationContextRunner 或同包测试。Worker `new MasterKeyProvider(CryptoProperties)` 仍按
  `properties.rejectTestKeys()`，是测试便利入口；Spring 装配不受影响。Owner builder-crypto，到期
  B5 或 M04 gate 前（2026-11-22）。
- **F-006（P3 accepted）**：Worker `EnvelopeDecryptor` 的 `dekBytes`/`plaintext`、API
  `CredentialCipher.encrypt` 的 UTF-8 临时明文字节仍未清零；无字段缓存，AC 仍通过。Owner builder
  B5/B9a，到期 M04 安全评审/B10 前（2026-11-25）。
- **F-007（P3 accepted）**：三个修复提交没有改 B2 源码、SecurityConfig、contracts 或其他任务文件；
  但 `ef2b0f2` 的 Worker CryptoProperties/测试依赖任务卡工作区新增的 write_scope（HEAD 旧卡不含），
  该 scope 变更尚未提交。人工 `git show --name-status` 通过；baseline 仍是 3df8dc0，当前 gate 已把
  B2 提交的文件报为 TASK-0101 out-of-scope，需 Lead 提交 scope 并修正 baseline/end 或任务拆分；
  接受 P3 不等于 gate 豁免。Owner Lead，到期 TASK-0101 done 前。
- **F-008（P3 accepted）**：test profile 缺主密钥可启动但操作级 fail-closed；默认/prod 仍 fail-closed。
  Owner Lead B10/RUNBOOK，到期 M04 发布前。

## ArchUnit 与覆盖率

- **ArchUnit**：EV-0541 在 `46a6ceb`（晚于 272e690/ef2b0f2）执行 `ModuleBoundaryTest` 并通过；
  `b09aca2` 只改 crypto 测试，不影响包规则。ArchUnit 仍有效。
- **platform-common 覆盖率**：EV-0541 包含 `./mvnw -q -pl platform/platform-common verify`，当前
  `platform-common/target/site/jacoco/jacoco.xml`（01:45）显示 LINE 614/677 = 90.69%、
  BRANCH 365/484 = 75.41%，合同 80/70 通过；新增 `MasterKeyRing` 代码已纳入。
- **API/Worker 覆盖率**：`apps/api`/`apps/worker` 的 jacoco.xml 仍是 00:30/00:35 的 EV-0537
  （617fa4b，修复前）产物；EV-0543 只跑 `test`，没有 `jacoco:check`。新增 `CryptoProperties`/
  `MasterKeyProvider` 构造分支已由 98 测试覆盖，但最终提交的 API/worker bundle 覆盖率未在本轮独立
  取证；模块 gate/V1 必须再跑一次完整 `verify`/coverage。

## Gate check

`node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" gate check --module M04` → **FAIL**
（2026-11-21T01:53:26+08:00，18 errors / 1 warning）：任务 TASK-0101/TASK-0103 未 done、
TASK-0101 baseline 区间把 B2 文件报为越界、缺少 M04 模块评审。TASK-0101 的证据 EV-0533/EV-0543
均通过 hash/归属校验。该 gate 失败是任务状态/模块评审/元数据问题，不是本次代码 verdict 的失败；
但 F-007 必须在 TASK-0101 done 前修正。

## 未验证

- 最终 `b09aca2` 上的 API/Worker 全量 `verify`、`jacoco:check` 与覆盖率；EV-0537 是修复前，
  EV-0541 只覆盖 platform-common verify，EV-0543 是定向 test。
- Worker Spring 上下文（`@Autowired` 2 参构造）在最终提交的集成测试；`MasterKeyProviderTest`
  直接调用构造器，未替代完整 Worker 应用启动。
- 真实 API 写库 → Worker 读库解密的 DB/HTTP 往返；仍为类级共享实现 + Worker 生产解密器。
- 全库 `tenant_data_key` 可解性、rewrap/四步轮换、canary 失败注入（B10）。
- 额外 padding 的仓库内专门用例；本次仅用 JDK 独立检查确认 fail-closed。
- UI/a11y/perf/迁移：本任务不适用。

## 剩余风险

- Worker 直接构造仍可用 `rejectTestKeys=false`；Spring 装配强制 true，生产调用点目前没有直接构造。
- ADR-0021/ARCHITECTURE 的 Worker pepper 文案与 Lead 决策不一致，B10 前需澄清。
- API/worker 覆盖率未在最终提交重新取证；worker bundle 分支历史余量仅约 0.85pp（EV-0537 数据）。
- `RICCON_MASTER_KEY_CANARY` 在 API/Worker yml 仍未被消费（B1a 配置债）；实际 canary 为固定常量。
- 任务卡 baseline/evidence/scope 未提交，HEAD 仍是旧元数据；F-007/F-004 的提交动作未完成。
- test profile 误用与 `RICCON_CRYPTO_REJECT_TEST_KEYS=false` 的残余配置风险已由 F-008/F-001 覆盖；
  生产守卫仍需 RUNBOOK 明确。

## 证据

- `.riccon/reviews/tasks/TASK-0101-review.md`（REV-TASK-0101-001）
- `.riccon/evidence/TASK-0101/EV-0533.json`（早期 clean 定向 74 tests）
- `.riccon/evidence/TASK-0101/EV-0542.json`（失败尝试，未用）
- `.riccon/evidence/TASK-0101/EV-0543.json`（最终 b09aca2、clean、exit 0、98 tests）
- `.riccon/evidence/TASK-0103/EV-0541.json`（46a6ceb，ArchUnit + platform-common verify）
- `.riccon/evidence/TASK-0100/EV-0537.json`（617fa4b 修复前后端全量 verify）
- 本次独立复跑：EV-0543 同一 Maven 命令，exit 0；surefire 报告在 `platform/platform-common/target/surefire-reports/`、
  `apps/api/target/surefire-reports/`、`apps/worker/target/surefire-reports/`（98 tests/0 failures）。
