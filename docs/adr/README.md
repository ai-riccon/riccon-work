# 架构决策记录

每个决策一个文件：`ADR-NNNN-short-title.md`。状态使用 `proposed | accepted | superseded`；被替代时保留原文并链接新 ADR。

```text
# ADR-NNNN: 标题
Status: proposed | accepted | superseded
Context:
Decision:
Consequences:
Alternatives:
Evidence:
```

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

说明：

- ADR-0005 的 `accepted` 仅表示“按 DEC-0003 临时使用”，不是对许可风险的最终接受；对外分发前必须由用户重开决策，见 ADR-0005 与 `docs/security/THREAT-MODEL.md` B-01。
- ADR-0011 的两项 MCP CVE 例外已于 2026-11-17 经 DEC-0005 接受；B-06 关闭，例外到期 2026-12-31 或 M03 gate（取早）。
- ADR-0010 没有并入 ADR-0009：租户强制点是“查询与数据归属”约束，Flyway 归属是“迁移与部署”约束，两者的失效模式和验证命令不同；分开记录便于 verifier 逐条核对。
