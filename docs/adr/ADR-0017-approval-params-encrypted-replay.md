# ADR-0017：审批参数加密重放与脱敏预览

Status: accepted
Date: 2026-11-18
关联：ADR-0006（密钥生命周期与信封加密）、ADR-0007（审计事件模型）、ADR-0012（M02 数据模型）、ADR-0014（API 与事件契约）、ADR-0016（租户隔离例外）

## Context

J2 的 MCP 工具调用是全量 HITL：员工批准后，Worker 必须用**与审批时完全一致的参数**执行外部工具。若用展示给员工的脱敏摘要重放，实际执行内容会与审批内容不一致；若只存 SHA-256，无法还原参数。当前实现（`ApprovalService`）把规范化完整参数 JSON（≤8000 字符）写入 `approval.params_preview`，但 V4 迁移注释与 ADR-0007 明确该列是“脱敏后的入参摘要；禁止完整文件/会话/凭据”。这是契约与安全冲突：

- `params_preview` 会返回给审批 UI 与运行时间线，等于把工具入参原文展示给所有有权查看任务的人；
- 该列进入数据库备份、只读副本与运维直连，违反 T4 的最小化与凭据保护要求；
- 仅把预览脱敏又会让审批后的重放失去精确性，不能接受“审批 A、执行 B”。

需要同时满足：审批展示可读、执行参数精确、静态数据不落明文、密钥可轮换、审计可核对、跨租户不可解密。M02 已具备 ADR-0006 的主密钥与租户数据密钥（DEK）基础设施，不需要引入新加密库或外部 KMS。

## Decision

### 1. 两种表示，职责分离

`approval` 表保存同一组参数的两种表示：

| 列                                       | 语义                                                                             | 使用方                               |
| ---------------------------------------- | -------------------------------------------------------------------------------- | ------------------------------------ |
| `params_preview JSON NOT NULL`           | **脱敏摘要**，只含白名单键与截断值；≤4096 字节、≤16 个顶层键、单字符串 ≤200 字符 | 审批 UI、SSE、运行时间线             |
| `params_ciphertext VARBINARY(8192) NULL` | 规范化完整参数的 AES-256-GCM 密文                                                | 仅 Worker 审批通过后解密重放         |
| `params_key_id VARCHAR(64) NULL`         | 加密所用 DEK 的 key id（`tenant_data_key.key_id`）                               | 解密选钥、轮换与重加密               |
| `params_sha256 CHAR(64) NOT NULL`        | 规范化完整参数的 SHA-256 小写 hex                                                | 审计核对、审批绑定、解密后完整性校验 |

- **不新增** `params_sha256 BINARY(32)`：既有 `CHAR(64)` 已被审计、审批卡与 Worker 绑定逻辑使用，再加一列会产生双写与漂移；V8 继续沿用该列并用 `CHECK` 约束为 64 位小写 hex。未来若改二进制表示，必须走 expand → contract 两阶段迁移并更新本 ADR。
- `params_preview` **不得**保存完整参数、文件/会话/知识库原文、提示词、凭据或完整 PII。它是展示数据，不是重放数据。
- 任何 API、SSE、审计或日志都不得返回/记录 `params_ciphertext`、`params_key_id` 之外的密钥材料；`params_key_id` 只允许出现在运维日志与内部错误上下文，不进入公开响应。

### 2. 密文格式

- 算法：AES-256-GCM，256 位密钥，96 位随机 nonce，128 位认证标签。
- 载荷：`version(1) || nonce(12) || ciphertext || tag(16)`，当前 `version = 0x01`。空明文也至少 29 字节。
- AAD：`UTF-8(tenantId + "|" + approvalId)`，即审批行的租户与审批 ID；tenantId/approvalId 均不得包含 `|`。AAD 不包含 key id：key id 通过选钥绑定，换 key id 或换密钥都会导致 GCM 认证失败。
- 明文上限：`params_ciphertext` 为 `VARBINARY(8192)`，扣除 29 字节头部后明文最多 8163 字节；应用层把规范化 JSON 限制为 **8000 UTF-8 字节**（不是字符数），超限拒绝创建审批，禁止截断。
- 每次加密必须重新生成 nonce；禁止复用、禁止从数据库读取 nonce 后重加密同一明文产生确定性密文。
- 解密失败统一抛 `EnvelopeException`，不区分密钥错误、AAD 不匹配或密文篡改；日志只记录 `keyId`、`approvalId`、`runId` 与错误码，不记录 nonce、密文、明文或底层异常消息。

### 3. 密钥与轮换

- 审批参数使用租户数据密钥，`purpose = approval-params`，存入既有 `tenant_data_key`（ADR-0006）；DEK 由主密钥包裹，主密钥轮换只 rewrap DEK，不重加密审批密文。
- `params_key_id` 记录 DEK 的 `key_id`，不记录主密钥 ID（主密钥轮换不影响该列）。
- 当前与上一把 DEK 同时加载到 `EnvelopeKeyRing`：新审批用当前 DEK；读取旧审批按 `params_key_id` 选钥。未知/已退役 key id 直接失败，禁止尝试其他密钥。
- DEK 轮换：新审批写新 key id；旧 PENDING/APPROVED 审批在下次恢复时用旧 DEK 解密，若仍要执行，在同一事务内用当前 DEK 重加密并更新密文与 key id，成功后才调用外部工具。重加密失败则任务失败，不执行工具。
- 退役 DEK 前必须确认没有引用该 key id 的未终结审批（`idx_approval_params_key` 支持按租户与 key id 扫描）；存在引用时禁止移除密钥。密钥材料只由部署环境注入，禁止写入仓库、镜像、日志、证据或提示词。

### 4. 写入路径

Worker 创建审批时：

1. 对工具参数做规范化 JSON（键排序、稳定序列化），拒绝含敏感键（`token`、`secret`、`password`、`authorization`、`cookie`、`apiKey`、`credential`、`privateKey`、`session` 等）或超过 8000 UTF-8 字节的调用；
2. 计算 `params_sha256`；
3. 从同一参数生成白名单脱敏摘要 `params_preview`（嵌套对象最多 3 层、数组最多 10 项、字符串截断到 200 字符；文件/会话/知识库内容只允许 ID 或哈希）；
4. 用当前 DEK 加密规范化 JSON，写入 `params_ciphertext` 与 `params_key_id`；
5. 以上字段与 `PENDING` 审批记录在同一事务内插入；任一步失败则不创建审批、不调用工具。

### 5. 恢复与重放路径

Worker 读取审批通过后的参数时必须依次校验，任一步失败即失败关闭：

1. 审批属于当前租户、run、research task 与阶段尝试；
2. `status = APPROVED`，且审批 ID 与恢复事件绑定；
3. `params_key_id` 可解析、密文长度合法、AAD 与当前行匹配；
4. 解密成功，得到规范化 JSON；
5. 重新计算 SHA-256，必须等于 `params_sha256`；
6. 再次检查敏感键与长度上限；
7. 同一审批最多执行一次工具调用；失败重试不得复用旧审批，必须重新走审批。

### 6. 审计与日志

- `mcp.tool.invoked` 只记录 `toolName`、`paramsSha256`、`resultSha256`、`durationMs`、`approvalId`，不记录参数、预览或密文。
- `approval.decided` 只记录 `approvalId`、`decision`、`approverUserId`、`toolName`。
- 允许记录 `keyId` 与 `keyId` 解析结果用于轮换排障；禁止记录 DEK、主密钥、nonce、密文与明文。
- 审批拒绝、跨租户访问、密文认证失败写 `outcome = denied` 审计；审计写入失败使业务失败（ADR-0007）。

### 7. 迁移、兼容与回滚

- V8 是 **expand** 迁移：新增可空密文列与 key id、收紧预览约束、更新列注释；不删除、不改写既有列，不回填历史数据。
- 兼容窗口：旧 Worker 仍可读取旧 `params_preview`；新 Worker 必须只把 `params_preview` 当展示数据，并在缺少 `params_ciphertext` 时拒绝执行。M02 gate 前必须完成 Worker 切换与数据清理，评审必须给出“预览不含完整参数”的查询/测试证据。
- 历史行没有密文时不可重放：应标记失败/取消并重新发起审批，不得退回用预览执行。
- 回滚：部署上一版本 Worker 即可，新增列保持未使用；不依赖 Flyway undo，不做破坏性 down migration。若未来需要 `params_ciphertext NOT NULL`，必须等历史行清理后单独迁移并保留回滚说明。
- 已知限制：SQL 只能约束预览大小与 JSON 对象类型，无法判断业务语义上的“脱敏”；键级白名单由应用、契约测试与评审保证。

### 8. 契约

- `contracts/openapi.yaml` 的 `Approval.paramsPreview` 明确为脱敏摘要（`maxProperties: 16`），不返回密文与 key id。
- `contracts/openapi.yaml` 的 `RunStageRecord.details` 使用 `RunStageDetails`，明确 `paramsPreview`、`resultSummary`、`invocationId` 为可选字段及长度/脱敏要求。
- `contracts/internal-api.yaml` 的跨租户运行统一 404，403 只用于同租户绑定冲突（ADR-0014 澄清）。
- `platform/platform-common` 的 `AesGcmEnvelope`、`EnvelopeKey`、`EnvelopeKeyRing`、`EnvelopeException` 是唯一加密实现；实现必须引用，不得在 `apps/api`、`apps/worker` 各写一份 AES 逻辑。

## Consequences

- 正面：审批展示与执行参数分离；完整参数不落明文；密文绑定租户与审批行；主密钥/DEK 轮换路径明确；审计只凭哈希即可核对；无新增运行时依赖。
- 负面：审批写入多一次加密与摘要生成；恢复路径增加解密与哈希校验；密钥丢失将导致未执行审批不可重放（必须重新审批，符合 fail-closed）；`params_preview` 与完整参数存在漂移风险，需要测试固定。
- 约束：新增工具必须定义预览白名单；不得把密文/密钥写进日志、审计或证据；AAD 与载荷格式一旦发布不得在同版本内改变；`params_ciphertext` 字段变更必须同步本 ADR 与 V8/V9 迁移。

## Alternatives

- **继续在 `params_preview` 存完整参数**：实现最简单，但泄露到 UI、备份与副本，违反 ADR-0007 与 T4；拒绝。
- **只存脱敏预览并据此重放**：不会泄露，但执行内容与审批内容不一致，属于审批绕过；拒绝。
- **只存 SHA-256**：可审计但无法重放；拒绝。
- **引入外部密钥管理/Secret Manager**：私有化客户未必具备；ADR-0006 已选择环境注入 + 信封加密，M09 再适配 KMS/HSM；当前复用既有 DEK。
- **用数据库 TDE 代替字段加密**：保护静态文件，但有 DB 权限的人、只读副本与备份仍可读明文；拒绝。
- **`enc:v1:<keyId>:<Base64>` 文本格式**：与 ADR-0006 的凭据格式一致，但审批列需要 JSON/二进制混合且长度更大；本场景使用 `VARBINARY` + 独立 key id 列，避免双重编码。凭据仍沿用 ADR-0006 的文本格式。

## Evidence

- `apps/api/src/main/resources/db/migration/V8__approval_params_envelope.sql`：列、注释、CHECK 与索引
- `platform/platform-common/src/main/java/com/riccon/platform/common/crypto/AesGcmEnvelope.java`、`EnvelopeKey.java`、`EnvelopeKeyRing.java`、`EnvelopeException.java`
- `platform/platform-common/src/test/java/com/riccon/platform/common/crypto/AesGcmEnvelopeTest.java`：往返、随机 nonce、篡改、错误 key/AAD、密钥环、防御性复制
- `contracts/openapi.yaml`、`contracts/internal-api.yaml`
- `docs/security/THREAT-MODEL.md` TM-12、TM-14、TM-16、TM-07；合同 `constraints.security`、`data.retention`
- 后续 Worker 任务：写入/恢复路径、`IT-HITL-001`、`MCP-007`、`E2E-AC-09`；M02 gate 必须验证 `params_preview` 不含完整参数
