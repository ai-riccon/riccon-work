# 契约

本目录存放跨部署物、跨模块的公开契约：REST API、内部任务、事件与数据 schema。

## 现状（M01）

M01 只建立约定，不定义具体业务契约。业务契约从 M02 起随模块交付，每个契约必须有对应测试与版本记录。

## 命名与版本

| 类型      | 规则                               | 示例                            |
| --------- | ---------------------------------- | ------------------------------- |
| REST 路径 | `/api/v{n}/{资源复数}`，小写短横线 | `/api/v1/knowledge-bases`       |
| 事件      | `riccon.<domain>.<event>.v{n}`     | `riccon.agent.run.completed.v1` |
| 内部任务  | `riccon.<domain>.<action>.v{n}`    | `riccon.agent.run.requested.v1` |
| JSON 字段 | camelCase                          | `tenantId`、`createdAt`         |
| 错误码    | `RICCON-<DOMAIN>-<NNN>`            | `RICCON-TENANT-001`             |

- 同一版本内只做兼容变更：新增可选字段、增加枚举值需消费方容忍未知值；删除/重命名/改变语义必须升版本。
- 契约文件与实现必须同 PR 更新；破坏性变更需要 ADR 与迁移说明。
- 事件必须包含 `eventId`、`occurredAt`、`tenantId`、`version`；消费方必须幂等。

## 错误模型

REST 错误统一使用 RFC 9457 `ProblemDetail`：

```json
{
  "type": "https://riccon.example/errors/tenant-001",
  "title": "Tenant context missing",
  "status": 403,
  "detail": "请求缺少有效的租户上下文",
  "code": "RICCON-TENANT-001",
  "traceId": "..."
}
```

禁止在契约中暴露堆栈、SQL、内部主机名、密钥或完整提示词。

## 安全

- 契约中不得包含真实密钥、token、租户真实数据或 PII 示例。
- 示例值使用 `tenant-a`、`user-0001` 等占位符。
- 内部契约必须声明调用方身份、超时、重试与幂等语义。

## 所有者

- 公开 REST 与事件：`riccon/architect` 起草，`riccon/product` 确认语义。
- 生产变更：由用户批准。
