# 架构决策记录

每个决策一个文件：`ADR-NNNN-short-title.md`。状态使用 `proposed | accepted | superseded`；被替代时保留原文并链接新 ADR。

```text
# ADR-NNNN: 标题
Status: proposed | accepted | superseded
Reviewers: <角色/agent；独立评审者>
Approved-by: <用户/决策 ID；不可逆或高成本项必填>
Review-date: <ISO8601 +08:00>
Supersedes: <ADR-NNNN | none>
Context:
Decision:
Consequences:
Alternatives:
Evidence:
```

- ADR 默认 `proposed`；未完成 Lead/Verifier/Ops 评审或用户批准（不可逆/高成本项）前，不得改为 `accepted`。
- `Approved-by` 必须引用真实决策 ID（如 `DEC-0005`）；没有用户批准的高成本项保持 `pending` 或 `proposed`。
- 被替代的 ADR 保留原文并链接新 ADR；不得删除历史结论。

决策难以逆转时写 ADR：技术栈、数据模型、公开契约、认证/安全、部署架构，或对合同的刻意偏离。

## 索引

| ADR      | 标题                                                        | 状态     |
| -------- | ----------------------------------------------------------- | -------- |
| ADR-0001 | 模块化单体 + api/worker 双部署物，agentscope 隔离在 worker  | accepted |
| ADR-0002 | 测试栈对象存储使用 RustFS 替代 MinIO 镜像                   | accepted |
| ADR-0003 | 租户上下文基线使用 ThreadLocal + 显式作用域                 | accepted |
| ADR-0004 | Worker 使用 agentscope-core，不引入 starter                 | accepted |
| ADR-0005 | MySQL Connector/J 许可风险与发布门禁（DEC-0003=C 临时接受） | accepted |
| ADR-0006 | 密钥生命周期与 AES-256-GCM 信封加密                         | accepted |
| ADR-0007 | 审计事件模型与 append-only 存储                             | accepted |
| ADR-0008 | M02 异步承载（MySQL outbox）与可观测性                      | accepted |
| ADR-0009 | 租户隔离强制点与越权测试入口                                | accepted |
| ADR-0010 | Flyway 单一迁移者与 schema 版本校验                         | accepted |
| ADR-0011 | B-06 依赖安全处置与例外（MCP SDK 例外、Umi 构建链重分类）   | accepted |
| ADR-0012 | M02 数据模型、UUIDv7 标识、租户键与全局表例外               | accepted |
| ADR-0013 | M02 会话认证（Spring Session JDBC）、CSRF 与登录防护        | accepted |
| ADR-0014 | M02 API/事件契约、SSE 语义与版本规则                        | accepted |
| ADR-0015 | M02 领域模块边界、共享词汇与持久化归属                      | accepted |
| ADR-0016 | Worker outbox 跨租户领取例外与租户绑定                      | accepted |
| ADR-0017 | 审批参数加密重放与脱敏预览                                  | accepted |
| ADR-0018 | M03 组织模型、空间可见性与部门数据范围                      | proposed |
| ADR-0019 | M10 OIDC/SSO：每租户 1 个 IdP、授权码 + PKCE、显式绑定      | proposed |
| ADR-0020 | M10 审计查询、导出审批与平台跨租户审计边界                  | proposed |

说明：

- ADR-0005 的 `accepted` 仅表示“按 DEC-0003 临时使用”，不是对许可风险的最终接受；对外分发前必须由用户重开决策，见 ADR-0005 与 `docs/security/THREAT-MODEL.md` B-01。
- ADR-0011 的两项 MCP CVE 例外于 2026-11-17 经 DEC-0005 接受、M02 关闭；2026-11-18 经 DEC-0012 重开续期至 2027-03-31 或 M07 gate（取早）。2026-11-18 核查：agentscope 2.1.0-BETA1 仍依赖 MCP SDK 0.17.2，升级不能消除 CVE。
- ADR-0010 没有并入 ADR-0009：租户强制点是“查询与数据归属”约束，Flyway 归属是“迁移与部署”约束，两者的失效模式和验证命令不同；分开记录便于 verifier 逐条核对。
- ADR-0012 统一了 ADR-0007/0008 中“ULID”的表述：标识仍要求时间有序，但实际使用 UUIDv7 `BINARY(16)`；ADR-0007/0008 的审计与 outbox 语义不变。
- ADR-0016 是 ADR-0009 的显式例外：只允许 Worker 领取 outbox 时跨租户读取任务元数据，领取后必须回到 `TenantJdbc`。
- ADR-0018 为 M03 架构提案，状态 `proposed`，待 verifier/lead/ops 在 M03 gate 前评审；DEC-0014 已确认产品口径，但 ADR 本身不自行标 `accepted`。
- ADR-0019/0020 为 M10 架构提案，状态 `proposed`；DEC-0013/DEC-0014/DEC-0017 已确认产品口径，但 OIDC 配置来源、失败会话清理、联合登出、审计查询/导出实现与平台边界仍需 Lead/Verifier/Ops 复审（REV-M10-ARCH-001 fail 后已修订 F-001..F-023），未批准前不得进入实现。
