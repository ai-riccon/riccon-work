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

| ADR      | 标题                                                       | 状态     |
| -------- | ---------------------------------------------------------- | -------- |
| ADR-0001 | 模块化单体 + api/worker 双部署物，agentscope 隔离在 worker | accepted |
| ADR-0002 | 测试栈对象存储使用 RustFS 替代 MinIO 镜像                  | accepted |
| ADR-0003 | 租户上下文基线使用 ThreadLocal + 显式作用域                | accepted |
| ADR-0004 | Worker 使用 agentscope-core，不引入 starter                | accepted |
