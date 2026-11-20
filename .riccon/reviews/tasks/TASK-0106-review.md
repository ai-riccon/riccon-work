---
review_id: REV-TASK-0106-001
module_id: M04
task_id: TASK-0106
reviewer:
  agent: riccon/verifier
  session: ses_e05aaad97ffeVFvD3EwF67YT0c
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - "0638626"
  - "5f9b37c"
head_commit: 84e78c9a535bcac2602e4a4be061b7d006e4c5b6
task_end_commit: 06386261820ca2ae75605e060e3ec4ec4c56e0d5
evidence_commit: f9bff57b5b682ab7a41f87224775eb7edd269ee3
evidence: [EV-0545]
reviewed_evidence: [EV-0545]
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/builder-failover（TASK-0105 B4）+ riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "Worker 侧 BYO 回退与 model.credential.invalidated（SYSTEM/platform）尚未实现，B5 测试用 SQL 模拟 INVALID"
    summary: >-
      B5 的 API/DB 路径已固定 tenant_id='platform'，租户级遗留凭据不可列出、不可按 ID 操作（IT-MODEL-105
      负向用例通过）。但 Worker 仍保留“先查当前租户、再回退 platform”的旧逻辑
      （ModelRepository.findActiveCredential），并且没有任何 401/403 → INVALID 写库与
      model.credential.invalidated 审计（CredentialJdbc 只读 ACTIVE；Worker 审计策略有白名单但无调用点）。
      B5 的 itModel105_multiKeyLifecycle 用直连 SQL 把状态改成 INVALID，再验证 API 的 enable→recovered；
      API 恢复路径是真实的，但“401 自动失效 + SYSTEM/platform 审计”与“租户级凭据被忽略”的 Worker
      端到端语义要由 TASK-0105（当前 planned）收口。B5 任务级按 API 范围可以 pass；模块 gate/V1 前不得
      把 B5 测试当作 SEC-M04-006 / IT-MODEL-105 的完整证据。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/admin/model/credential/ModelCredentialLifecycleApiTest.java:130-138
      （SQL 直接置 INVALID）；apps/worker/src/main/java/com/riccon/worker/model/repository/ModelRepository.java:58-66
      （租户优先回退 platform）；apps/worker/src/main/java/com/riccon/worker/model/persistence/CredentialJdbc.java:24-34
      （只读 ACTIVE）；apps/worker/src/main/java/com/riccon/worker/audit/domain/AuditDetailsPolicy.java:40-43
      （invalidated 白名单）；.riccon/tasks/TASK-0105.json（status=planned，含 BYO 回退移除）；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:435、454、559。
  - id: F-002
    severity: P3
    status: open
    owner: "riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "SEC-M04-005 的 B5 子集只扫描响应/列表/审计/DB/错误，未扫描日志、trace 与证据文件"
    summary: >-
      secM04_005_canaryKeyNeverLeavesProcess 对创建响应、列表、重复 409 错误体、审计 details 与数据库
      做了 canary 断言，覆盖是真实的；但 ADR §11.1 的 SEC-M04-005 还要求扫描日志与 trace（以及证据）。
      B5 未做日志/trace 扫描，V1 必须补完整扫描报告。测试类注释已限定为“安全子集”，因此不把该测试
      冒充完整 SEC-M04-005；作为任务级证据可接受。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/admin/model/credential/ModelCredentialSecurityTest.java:77-126；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:925。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder（B9a/B10 安全加固）"
    due: "2026-11-25（M04 安全评审/B10 前）"
    title: "CredentialCipher.encrypt 的 UTF-8 临时明文字节仍未清零（TASK-0101 review-002 F-006 遗留）"
    summary: >-
      B5 的 Service.encrypt 在 finally 中清零 DEK，符合“每轮清零”；但 CredentialCipher.encrypt 把
      apiKey.getBytes(UTF_8) 直接作为实参传入 CredentialEnvelope.encrypt，调用前后没有 Arrays.fill，
      CredentialEnvelope.encrypt 的 finally 也只清零 nonce。该文件在 B5 写范围（admin/model/credential/**）
      之外，B5 无法顺手修复；沿用 TASK-0101 review-002 F-006 的 P3 accepted 跟踪，B9a/B10 安全加固时收敛。
      无字段缓存、无响应/审计/日志泄露，AC“不缓存明文”仍通过。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/crypto/CredentialCipher.java:28-33；
      platform/platform-common/src/main/java/com/riccon/platform/common/crypto/CredentialEnvelope.java:90-110；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java:538-547；
      .riccon/reviews/tasks/TASK-0101-review-002.md F-006。
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder-key + riccon/lead（契约收口）"
    due: "M04 gate 前"
    title: "OpenAPI rotate 描述承诺响应包含 changed，实现省略 changed"
    summary: >-
      contracts/openapi.yaml 的 rotateModelCredential 描述写“响应返回新 key 掩码与 changed”，但
      ModelCredentialService.rotate 调用 toResponse(..., null)，ModelCredentialResponse 又使用
      @JsonInclude(NON_NULL)，实际 200 响应没有 changed。ModelCredential.changed 在 schema 中是可选字段，
      因此不是破坏性契约错误；但描述与实现不一致，客户端无法依赖文档。建议 B5 返回 changed=true 或由
      B2 契约 writer/Lead 把描述改为“仅 disable/enable 返回 changed”。
    evidence: >-
      contracts/openapi.yaml:2959；apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java:323；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/dto/ModelCredentialResponse.java:27、38。
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/builder-key + riccon/verifier"
    due: "M04 gate 前"
    title: "IT-MODEL-105 与 IT-MODEL-106 各在两个测试方法中重复定义，违反 ADR §11.1 单点定义"
    summary: >-
      ADR §11.1 明确“每个 ID 只能在一个测试方法/用例中定义”。B5 在同
      ModelCredentialLifecycleApiTest 中用 @DisplayName 定义了两次 IT-MODEL-105
      （多 key 生命周期、租户级凭据忽略）和两次 IT-MODEL-106（轮换在途、并发轮换），并发轮换也不是
      IT-MODEL-106 定义中的场景。后续 B4 还会实现 IT-MODEL-105 的 Worker 半段，评审包按 AC → ID →
      报告路径登记时会产生歧义。建议把拆分方法登记为 IT-M04-* 补充用例，或由 Lead 明确 ID 分组规则。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/admin/model/credential/ModelCredentialLifecycleApiTest.java:81、179、233、309；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:951。
  - id: F-006
    severity: P3
    status: open
    owner: "riccon/lead（closeout 元数据）"
    due: "TASK-0106 done 前"
    title: "task end_commit=0638626 不含 5f9b37c，EV-0545 实际录制在 f9bff57；任务卡/证据未提交"
    summary: >-
      reviewed 的两个 B5 提交为 0638626（feat）与 5f9b37c（test）；任务卡 end_commit 仍是 0638626，
      5f9b37c 在区间外。EV-0545 的 commit 是 f9bff57（晚于 end，且包含 B7 配额提交），dirty=false、
      exit 0，凭证代码在 5f9b37c..f9bff57 未再变化，因此测试对象与 reviewed 代码一致；但 done 前 Lead
      应把 task 的 end_commit 绑定到包含两个 B5 提交的提交（或写明确 reviewed_commits），并提交
      .riccon/tasks/TASK-0106.json、state 与 EV-0545 文件。当前 gate 中 TASK-0106 的 out-of-scope 列表是
      status=review 时按 baseline..HEAD 计算导致的（scope.mjs 只在 done 时使用 end_commit），不是 B5 真实
      越界；52fc36b..0638626 只含 B5 的 credential 文件。
    evidence: >-
      .riccon/tasks/TASK-0106.json:35-36（baseline 52fc36b、end 0638626）；.riccon/evidence/TASK-0106/EV-0545.json
      （commit f9bff57、dirty false、exit 0）；git show --stat 0638626（16 个 credential 文件）；
      git show --stat 5f9b37c（仅 ModelCredentialLifecycleApiTest）；git log --oneline 52fc36b..0638626（仅 0638626）；
      node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04（2026-11-21T03:05:19+08:00，FAIL）。
  - id: F-007
    severity: P3
    status: open
    owner: "riccon/builder-key"
    due: "M04 gate / V1 前"
    title: "审计失败回滚只测 created/disabled；rotate/enable 未测，create 回滚未断言 DEK 与审计行"
    summary: >-
      secM04_006 用 MySQL trigger 注入 audit_event 写入失败，验证 create 不留凭据行、disable 不改变状态，
      这是真实的 fail-closed 证据。但 rotate（同时插入新 key + 停用旧 key）与 enable 的审计失败回滚没有
      用例；create 回滚只断言 credentialCountByLabel=0，没有断言本次新建的 tenant_data_key 行回滚、也没有
      断言 audit_event 没有残留。B5 的审计写入与业务同事务（Service.appendAudit → AuditService.append），
      机制上正确，但高风险在途状态机建议由 V1 或 B5 补两个失败路径用例。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/admin/model/credential/ModelCredentialSecurityTest.java:162-192；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java:233-246、312-322、353-364、393-407。
  - id: F-008
    severity: P3
    status: open
    owner: "riccon/builder-key"
    due: "M04 gate / B4 加固时"
    title: "enable 的 recovered/enabled 审计动作由更新前状态决定，极小并发窗口可能写错动作"
    summary: >-
      enable 先读 current.status()，再执行不带版本的 ENABLE_CREDENTIAL（WHERE status IN ('DISABLED','INVALID')），
      然后按读取时的状态选择 enabled/recovered。若读取时为 DISABLED、并发 enable 后又发生状态变化，或
      读取后被并发改成 INVALID 再被本次更新覆盖，审计动作可能与实际来源状态不一致。窗口很小、只影响
      审计动作名，不影响密文或状态机最终值；建议后续用条件更新返回旧状态或行锁收紧，或在 B4 审计加固时
      评估接受。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java:384-407；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/persistence/ModelCredentialJdbc.java:137-152。
  - id: F-009
    severity: P3
    status: open
    owner: "riccon/lead + riccon/verifier（模块 gate）"
    due: "M04 gate 前"
    title: "任务级未取覆盖率/ArchUnit 证据；B5 新增约 1.7k 行加密与权限代码，模块 gate 必须补全量 coverage"
    summary: >-
      EV-0545 只执行三组定向测试（-Dtest=...），没有 jacoco:report，也没有跑
      platform-architecture-tests（-pl apps/api -am 不包含该模块）。新增 Service/Controller/Jdbc/DTO 约
      1.7k 行，部分分支（占位 key、apiKey 首尾空白、label/priority 上界、rotate label 覆盖、INVALID→DISABLED、
      DEK 失败路径、list provider 404）没有专门用例。按合同 coverage 80/70，模块 review 必须在最终提交
      上用 coverage 合同命令取证；若低于门槛，按 P2/P1 处理。本任务级 pass 不代表覆盖率通过。
    evidence: >-
      .riccon/evidence/TASK-0106/EV-0545.json（命令无 jacoco、无 ArchUnit）；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java（704 行）；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/persistence/ModelCredentialJdbc.java（418 行）；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/controller/ModelCredentialAdminController.java（217 行）；
      platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java。
created_at: "2026-11-21T03:10:30+08:00"
---

# REV-TASK-0106-001：TASK-0106（M04-B5）独立评审

本文件是任务级独立技术验收，不是模块 gate 评审，也不代表 Product 预验收或用户产品验收。
评审期间未修改生产源码、测试、迁移或合同，未提交，未把任务标为 done。

## 结论

**pass（任务级）**：TASK-0106 的 6 条验收在 B5 的后端 API/持久化/审计范围内通过；没有 open P0/P1。
唯一 P2（F-001）是 Worker 侧 B4 交付项（BYO 回退移除、401 自动失效与 SYSTEM/platform 审计）尚未实现，
B5 的 API 恢复路径本身是真实的；另有 P3 记录（SEC 扫描子集、明文临时字节清零遗留、rotate changed
契约文案、测试 ID 重复、end 证据元数据、审计失败用例缺口、enable 审计动作竞态、覆盖率待模块 gate）。
模块 gate 仍失败（M04 多个任务 planned/review、无模块 review），属于模块未完成状态，不是本任务代码
验收失败。

## 验证范围与方法

- 读任务卡、ADR-0021 §6/§7.1–7.4/§11.1、B1b 复评 F-006、`docs/CODING-STANDARDS.md §12`。
- 逐提交核对：`0638626`（feat，16 个文件全部在 `admin/model/credential/**` 与对应测试）；
  `5f9b37c`（test，只改 `ModelCredentialLifecycleApiTest`）。
- 写范围：`git diff --name-only 52fc36b 0638626` 只含 B5 credential 文件；两个提交均未触碰
  Worker、contracts、B3 routing、B6 quota。5f9b37c 在 end_commit 之外，已人工覆盖（见 F-006）。
- 定向复跑（独立于 builder）：在 HEAD `84e78c9`（工作区仅 `.riccon` 变更；credential 代码在 5f9b37c 后
  未变）执行与 EV-0545 相同命令
  `./mvnw -q -T1C -pl apps/api -am test -Dtest='ModelCredentialRequestTest,ModelCredentialLifecycleApiTest,ModelCredentialSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false`
  → **10 tests、0 failures、0 errors、0 skipped、exit 0**（Lifecycle 5、Security 3、Request 2，
  surefire-reports 03:04）。
- 证据：EV-0545 `commit=f9bff57`、`dirty=false`、`exit_code=0`、output sha256 与记录一致；
  日志无 `BUILD FAILURE`、无真实 key；两条 ERROR 是 SEC-M04-006 的注入审计失败，预期内。
- `gate check --module M04`（2026-11-21T03:05:19+08:00）仍 FAIL：16 errors（TASK-0104/0106/0107
  review、多个 planned、无模块 review）；EV-0545 通过 hash/归属校验。TASK-0106 的 out-of-scope 行是
  status=review 时按 baseline..HEAD 检查的 gate 现象，不是 B5 越界。

## 验收逐条对账

| #   | 任务卡验收原文                            | 结果               | 证据                                                                                                                                                                                                                                                                                                        |
| --- | ----------------------------------------- | ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 掩码列表                                  | **pass**           | `ModelCredentialJdbc.SELECT_COLUMNS` 不含 ciphertext/key_id；`ModelCredentialResponse` 只含 id/providerId/label/maskedHint/status/priority/version/时间；`itModel105_multiKeyLifecycle` 断言列表字符串不含两把明文、无 ciphertext/keyId/masterKeyId 字段，maskedHint 为末 4 位。                            |
| 2   | 新增/轮换/停用/恢复                       | **pass**           | 新增 201、轮换 200、停用 200（changed=true）、恢复 200；轮换继承 priority/label、旧 key DISABLED、新 key ACTIVE；DISABLED→enabled、INVALID→recovered 两条恢复动作均断言。`ModelCredentialService.create/rotate/disable/enable`。                                                                            |
| 3   | HMAC fingerprint 409、最后一把 key 可停用 | **pass**           | 新增重复与轮换回旧明文均返回 409 `DUPLICATE_CREDENTIAL`；DB fingerprint 等于 `MasterKeyRing.fingerprint(providerId, apiKey)` 且为 64 位 hex；`activeCredentialCount()==0` 断言最后一把 ACTIVE 可停用（ADR §6.3 允许）。                                                                                     |
| 4   | 审计无明文、DTO toString/错误不回显 key   | **pass（子集）**   | 审计 `model.credential.created/rotated/disabled/enabled/recovered` 均 platform + USER + MODEL_CREDENTIAL，details 只含白名单键，断言不含 canary；两个请求 DTO 覆写 toString 为 `apiKey=[redacted]`；重复 409 错误体不含 canary；字段校验错误消息均为固定文案。日志/trace/证据 canary 扫描留给 V1（F-002）。 |
| 5   | 在途影响说明                              | **pass（服务端）** | 停用/轮换只改 status/时间列，不删行、不改旧 ciphertext/key_id；Service/Controller Javadoc 明确“新请求不再使用、在途连接不取消、可重试不重复”；`itModel106` 用轮换前的密文+包裹 DEK 快照在轮换后仍能解密，证明在途材料未被破坏。UI 确认框文案属 B9a，本任务未验证。                                          |
| 6   | 权限                                      | **pass**           | `@RequirePermission(PLATFORM_MODEL_READ/MANAGE)`；租户管理员/员工读或写均 403，匿名 401，403 响应不含 baseUrl/maskedHint；跨 provider credential 与租户级遗留 credential 统一 404 `RICCON-TENANT-404`。                                                                                                     |

## 加密与 AAD

- **DEK 懒生成**：`activeDataKey()` 先查 `tenant_data_key` 最新未退役的 `platform|model-credential` DEK；
  不存在时 32 字节 SecureRandom 生成、`MasterKeyRing.wrap` 后写库；并发首次创建允许多个 DEK，凭据各自
  引用自己的 keyId（ADR §6.5 允许）。
- **AAD 与 Worker 一致**：API 使用 `CredentialEnvelope.aad("platform","model-credential",keyId)`（含 schema
  version 1）；`CrossServiceEnvelopeRoundTripTest` 断言该 AAD 与 Worker `EnvelopeDecryptor.aad` 逐字节
  相同，并用 Worker 生产解密器还原 API 加密的密文；跨租户 AAD/DEK 负向用例通过。
- **成对写入**：`Sealed(ciphertext,keyId,masterKeyId)` 三值由同一次 encrypt 返回，`INSERT_CREDENTIAL`
  一次写入；`key_id` 同时满足 `tenant_data_key` 外键，未出现密文与 keyId 分离。
- **每轮清零**：Service.encrypt 在 finally 中 `Arrays.fill(key.dek(), 0)`；懒生成失败路径也清零；
  `PlatformDataKeyRecord` 对 wrappedDek/wrapNonce 防御性复制；无字段缓存明文。F-003 记录
  `CredentialCipher.encrypt` 的 UTF-8 临时明文字节遗留（B1b F-006，B5 scope 外）。
- 明文不进入响应/审计/DB/日志：SEC-M04-005 子集已验证响应、列表、409 错误、审计、数据库；
  EV-0545 日志 grep 无测试 canary。

## 审计

- 已实现：`model.credential.created`（created）、`rotated`（含 previousCredentialId）、`disabled`、
  `enabled`（来自 DISABLED）、`recovered`（来自 INVALID）；details 与 ADR §7.4 白名单逐键一致
  （`AuditDetailsPolicy` API 侧 :147-164 已登记，含 invalidated/recovered 的键）。
- 归属：`TenantContext.runWithTenant(PlatformTenant.TENANT_ID, ...)` + `AuditEvent.create` 的
  `tenant_id=platform`、`actor_type=USER`、`target_type=MODEL_CREDENTIAL`；测试断言 tenant/actor/target。
- 同事务：`@Transactional` 的 Service 调用 `AuditService.append`（加入同一事务）；trigger 注入
  audit_event 失败时 create/disable 返回 500 且业务回滚（SEC-M04-006 子集）。
- 缺口：Worker `model.credential.invalidated`（SYSTEM/platform）与 BYO 回退移除属 TASK-0105（planned），
  见 F-001；rotate/enable 审计失败回滚未测，见 F-007。

## 契约与 API

- 5 个端点与 OpenAPI §7.1 对齐：GET/POST `/admin/model-providers/{providerId}/credentials`、
  `/{credentialId}/rotate|disable|enable`；权限 read/manage 一致；201/200/400/404/409 与契约一致。
- `ModelCredential` 响应字段与 schema 一致；`changed` 只在 disable/enable 出现，create/rotate 省略
  （schema 可选字段，但 rotate 描述文案不一致，见 F-004）。
- `ModelCredentialCreateRequest` 16..512、label 1..64、priority 1..1000 与 OpenAPI 一致；服务端二次校验
  并拒绝首尾空白、空值和已知占位值；未知 provider 404、provider 不允许 400。
- 跨租户：Provider 路径 + credentialId 必须同时匹配 `tenant_id='platform'` 与 provider_id；
  租户级遗留行不可见、不可操作；测试覆盖 list/disable 404 与跨 provider 404。
- 错误：ProblemDetail + `RICCON-MODEL-409`/`RICCON-TENANT-404`；`reason` 稳定原因码；
  `ModelCredentialConflictException`/`ModelCredentialInvalidRequestException` 消息固定、不含 key/fingerprint/
  ciphertext/provider 地址；请求 DTO toString 已脱敏。

## 写范围、密钥与迁移

- `0638626`：16 个文件，全部在 `apps/api/src/main/java/com/riccon/api/admin/model/credential/**` 与
  `apps/api/src/test/java/com/riccon/api/admin/model/credential/**`；`5f9b37c`：仅同测试类。
- 未改 Worker、contracts、B3 routing、B6 quota、SecurityConfig、构建配置；B5 没有新迁移。
- 未发现真实密钥：测试使用 `sk-m04b5-*`、`sk-canary-m04b5-*` 合成值；EV-0545 日志无
  `sk-m04b5`/`sk-canary`/`enc:v1` 明文；数据库断言 fingerprint 为 HMAC、ciphertext 为 `enc:v1:`。
- B1a 的 `uk_provider_credential_fingerprint(tenant_id,provider_id,credential_type,fingerprint)`、
  `provider_credential` 状态 CHECK、`tenant_data_key` 外键均满足 B5 的写入顺序（DEK 先于凭据，
  同事务）。

## 测试真实性

- `ApiIntegrationTestSupport` 默认 Testcontainers MySQL 8.4.13 + 随机端口 Tomcat，`@ActiveProfiles("test")`；
  两个集成测试类走真实 HTTP、真实 Flyway V1–V11、真实 JDBC，不是 mock。
- IT-MODEL-105：多 key 停用、重复停用幂等不重复审计、SQL 模拟 INVALID 后 enable→recovered、
  DISABLED→enabled、最后一把 key 停用、全失效 active count 0、platform 审计、租户级凭据忽略、
  跨 provider 404。
- IT-MODEL-106：轮换前快照、轮换后旧密文仍可解密、旧 key 掩码保持、label/priority 继承、
  fingerprint HMAC 去重（create 与 rotate 均 409）、并发轮换单胜者。
- SEC-M04-005：canary 响应/列表/重复 409/审计/DB；SEC-M04-006：五类生命周期审计白名单 +
  created/disabled 注入失败回滚 + 权限边界。
- 无 skipped/零测试；独立复跑 10/0/0/0。F-005 记录 IT-MODEL-105/106 DisplayName 重复的 ID 纪律问题；
  F-007 记录 rotate/enable 审计失败与 DEK 回滚未测。

## 证据

- EV-0545：`.riccon/evidence/TASK-0106/EV-0545.json`（commit f9bff57、dirty=false、exit 0、
  output sha256 `5b3786e7…` 与日志一致）；命令为三组定向测试，日志 79,082 bytes。
- 任务卡：`.riccon/tasks/TASK-0106.json`（status review、evidence EV-0545、baseline 52fc36b、
  end 0638626）；工作区仍有未提交的 task/state/evidence 变更（F-006）。
- gate：`gate check --module M04` 2026-11-21T03:05:19+08:00 仍 FAIL（模块未完成/无模块 review），
  EV-0545 hash/归属通过；TASK-0106 的 out-of-scope 是 status=review 的 gate 区间现象。

## 未验证

- 模块级全量 build/lint/typecheck/test/coverage/E2E/a11y/security/perf：未跑（本任务不跑全量；
  模块 review 必须用合同命令在最终提交取证）。
- 覆盖率与 ArchUnit：EV-0545 无 jacoco/ArchUnit；B5 新增 ~1.7k 行的 80/70 覆盖未取证（F-009）。
- Worker 侧真实 401/403 → INVALID → audit `model.credential.invalidated`（SYSTEM/platform）、BYO 回退
  移除、Worker 按 priority/cooldown 选 key：属 TASK-0105（planned），未实现（F-001）。
- 真实 API 写库 → Worker 读库解密的 DB/HTTP 往返：CrossServiceEnvelopeRoundTripTest 验证共享实现 +
  Worker 生产解密器，B5 的 `decryptSnapshot` 验证 API 侧密文；完整 DB/HTTP 往返未在 B5 跑。
- 日志/trace/证据 canary 完整扫描（SEC-M04-005 V1）、SEC-M04-006 的 invalidated/SYSTEM 完整矩阵。
- rotate/enable 审计失败回滚、create 回滚的 DEK/审计行断言（F-007）。
- UI/确认框文案（B9a）、a11y、性能预算、迁移回滚（B5 无迁移；B1a V11 归其任务）。

## 剩余风险

- Worker 仍会把当前租户凭据用于模型调用，且没有 401 自动失效；B4 完成前 M04 的“平台托管池 + 租户级
  凭据忽略 + SYSTEM 审计”端到端语义不完整（F-001）。
- `CredentialCipher.encrypt` 的明文临时字节未清零（F-003），纵深防御债。
- rotate `changed` 文档/实现不一致（F-004），客户端可能按文档期待字段。
- IT-MODEL-105/106 的 ID 重复会让模块证据映射与 B4 的补充用例歧义（F-005）。
- EV-0545 录制在包含 B7 的 f9bff57，end_commit 仍 0638626；不更新元数据会让 gate/审计链读到旧区间（F-006）。
- B5 新代码覆盖率未测；若低于 80/70，模块 gate 会失败（F-009）。
- `enable` 的审计动作在极小并发窗口下可能选错（F-008），只影响动作名，不影响密文/最终状态。

## 证据链接

- `.riccon/reviews/tasks/TASK-0101-review-002.md`（F-006 前置接受）
- `.riccon/evidence/TASK-0106/EV-0545.json` 与 `EV-0545-2026-11-21T02-57-19-721+08-00-*.log`
- `.riccon/tasks/TASK-0106.json`
- `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md` §6/§7.1–7.4/§11.1
- `contracts/openapi.yaml` credential 端点与 schema（:2894–3040、:5508–5620）
- 本次独立复跑：`./mvnw -q -T1C -pl apps/api -am test -Dtest='ModelCredentialRequestTest,ModelCredentialLifecycleApiTest,ModelCredentialSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false`
  → exit 0；surefire 报告 10 tests/0 failures/0 errors/0 skipped（2026-11-21T03:04）。
