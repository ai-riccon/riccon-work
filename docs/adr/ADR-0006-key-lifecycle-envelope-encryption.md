# ADR-0006：密钥生命周期与信封加密

Status: accepted
Date: 2026-11-17

## Context

T4 项目要求模型、MCP、OIDC 等凭据加密入库，且密钥不得出现在仓库、镜像、日志、证据或提示词中。私有化部署默认没有可假设的云 KMS：客户可能只有环境变量注入、Kubernetes Secret 或自建 KMS/HSM。M02 需要保存模型网关 key；M03 增加 OIDC client secret；M07 增加按租户的 MCP 凭据。若等到 M07 再设计，M02 会把凭据以明文写入数据库，后续迁移成本高。

项目只允许 JDK 21 与既有技术栈，不引入 Jasypt 等额外加密库；AES-GCM 由 JDK 提供。

## Decision

### 1. 主密钥来源

- 私有化默认：部署时通过环境变量注入 `RICCON_MASTER_KEY`（Base64 编码的 32 字节随机值）；可选 `RICCON_MASTER_KEY_ID`（默认 `mk-1`，仅用于标识，不参与加密强度）。
- 轮换期可同时注入 `RICCON_MASTER_KEY_PREVIOUS`（上一把主密钥）。
- `platform-common` 定义与框架无关的 `MasterKeyProvider` SPI；M02 只实现 `EnvMasterKeyProvider`。客户 KMS/HSM 适配器在 M09 实现，不改变上层信封格式。
- 生产 profile 启动时拒绝已知测试密钥与长度不足的密钥；测试密钥只允许出现在 `src/test`。

### 2. 信封加密

- 数据密钥（DEK）按 `tenantId + purpose` 生成，purpose 例如 `model-credential`、`mcp-credential`、`oidc-client-secret`。
- 算法：AES-256-GCM；每个 DEK 每次加密使用 12 字节随机 nonce，禁止复用。
- AAD（附加认证数据）固定为 `tenantId|purpose|keyId|schemaVersion`，防止把 A 租户的密文换到 B 租户或换用途后仍能解密。
- 被主密钥包裹的 DEK 存 MySQL `tenant_data_key`：`tenant_id`、`key_id`、`purpose`、`wrapped_dek`、`wrap_nonce`、`algorithm`、`master_key_id`、`created_at`、`retired_at`。
- 密文存储格式：`enc:v1:<keyId>:<Base64(nonce || ciphertext || tag)>`。字段长度按最坏情况预留；不得截断。
- 明确不加密的范围：用户文件与产物由对象存储的服务端加密与 TLS 保护，不做字段级加密；提示词与消息不逐字段加密（由数据库访问控制与磁盘加密保护）。字段级加密只用于“凭据类小数据”。

### 3. 启动校验（fail-closed）

应用启动时、Web 端口接受流量前：

1. 读取并 Base64 解码主密钥，校验恰为 32 字节；
2. 用固定 canary 做一次加密/解密自检；
3. 校验 `tenant_data_key` 中已有记录可被当前或上一把主密钥解开；
4. 若提供了 `RICCON_MASTER_KEY_PREVIOUS`，执行批量 rewrap；结束后仍存在旧 `master_key_id` 的记录则启动失败，提示运维完成轮换。

任一步失败 → 抛出启动异常，进程非零退出；禁止降级为明文、禁止生成临时密钥、禁止健康检查返回 UP。API 与 Worker 都执行该校验（Worker 需要解密模型/MCP 凭据）。

### 4. 轮换

- 主密钥：至少每年一次，或发生泄露/人员变动时立即轮换。双密钥窗口内只做 DEK rewrap，不重加密业务数据。
- DEK：至少每 90 天轮换一次；轮换后新写入使用新 DEK，旧密文在读取时重加密（lazy re-encrypt），`retired_at` 置位后在无引用时可清理。
- 租户注销：删除该租户 DEK 与密文，保留审计（审计不含凭据）。
- 所有轮换操作记录运维证据（不含密钥），并由 M09 运行手册覆盖。

### 5. 禁止事项

- 禁止把密钥写入仓库、`.env` 提交、镜像层、日志、异常消息、证据文件、任务卡、提示词或测试快照。
- 禁止 `printenv`、`set -x`、调试端点或 actuator 暴露密钥相关配置；`/actuator/env` 不开放。
- 日志只允许记录 `keyId`/`masterKeyId` 与操作结果，不记录密钥、DEK、nonce、密文。
- 禁止在客户端（浏览器）解密或持有任何平台凭据。

### 6. 检测

- 合同 `commands.security` 的 Trivy `secret` 扫描必须在 CI 执行（M02 补 CI job）。
- CI 增加仓库模式检查：禁止 `RICCON_MASTER_KEY=<16 位以上>` 形式的赋值进入版本库（示例文件使用 `<injected-at-deploy>` 占位）。
- 日志 canary 测试：测试中注入固定假密钥，断言日志与异常输出不包含该值。
- 评审检查清单：新增配置项是否含密钥、是否走 `MasterKeyProvider`、是否有启动失败用例。

## Consequences

- 正面：M02 起凭据不以明文入库；私有化环境无需额外基础设施即可运行；KMS 适配不改变数据格式。
- 负面：多一个启动依赖（主密钥），部署必须正确注入；轮换需要运维步骤；密钥丢失将导致凭据不可恢复（必须备份主密钥或接受重新录入）。
- 约束：`platform-common` 的加密实现只依赖 JDK；Spring 配置与数据访问放在各部署物。加密服务不得被前端或公开 API 直接调用。

## Alternatives

- **明文或仅磁盘加密**：实现最简单，但数据库备份、只读副本和运维直连都会暴露凭据，不满足 T4。
- **Jasypt**：生态成熟，但只提供字符串加解密，缺少按租户 DEK、AAD 绑定与轮换语义，还引入额外依赖。
- **仅云 KMS**：私有化客户未必有云；把 KMS 作为唯一路径会阻塞交付。当前方案把 KMS 作为可选适配器。
- **数据库透明加密（TDE）**：保护静态文件，但无法防止有 DB 权限的人读取明文，也不能按租户隔离密钥。
- **每次加密直接用主密钥**：轮换需要重加密所有数据，且缺少租户级密钥隔离；信封加密成本更低。

## Evidence

- `platform/platform-common` 的 `MasterKeyProvider`、`EnvelopeEncryption`（M02 建立，JDK-only）
- 测试（M02 建立）：`KEY-001` 往返、`KEY-002` AAD 跨租户失败、`KEY-003` 篡改失败、`KEY-004` 缺失/短密钥启动失败、`KEY-005` rewrap、`KEY-006` 日志不含密钥
- `apps/api/src/main/resources/application.yml` 与 `apps/worker/src/main/resources/application.yml` 的配置项（M02）
- `docs/security/THREAT-MODEL.md` TM-12、TM-16、B-05；合同 `constraints.security`、`constraints.compliance`
- 合同 `commands.security`（Trivy secret 扫描）
