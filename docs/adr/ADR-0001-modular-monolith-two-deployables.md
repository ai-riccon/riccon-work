# ADR-0001：模块化单体 + api/worker 双部署物，agentscope 隔离在 worker

Status: accepted
Date: 2026-11-17

## Context

Riccon 是 T4 多租户 AI 协同办公系统，M02 要交付云工作空间与市场调研报告闭环。系统同时包含低延迟 CRUD、认证授权、Agent 运行、模型调用和 MCP 工具代理。全部放在一个进程会让 Agent 的运行风险（模型/工具出网、长任务、提示注入）直接暴露给认证与业务 API；立即拆微服务又会引入分布式事务、服务发现、链路追踪和多套部署编排，超出 M01 的交付能力。

## Decision

1. 源码保持一个 Maven reactor（模块化单体），按业务能力纵向切片；不为未来两年预拆服务。
2. 运行期拆为两个部署物：
   - `apps/api`：同步 HTTP、认证、RBAC、租户解析、业务读写、审计入口；
   - `apps/worker`：异步 Agent 运行、模型调用、MCP 代理、HITL 编排。
3. `platform/platform-common` 只放无框架共享内核；`platform/platform-architecture-tests` 用 ArchUnit 强制边界。
4. `io.agentscope` 只允许出现在 `apps/worker`；`apps/api` 与 `platform` 同时受 ArchUnit 与 Maven Enforcer `bannedDependencies` 约束。
5. 两个部署物只通过契约通信；M02 定稿任务契约（HTTP 或队列），不提前引入消息中间件。

## Consequences

- 正面：Agent 故障与出网风险被隔离；API 与 Worker 可独立扩缩容、限流和回滚；M02–M06 仍可在单仓库内快速迭代。
- 负面：需要维护两个启动类、两套配置与两份部署清单；跨进程调用需要幂等、重试和契约版本管理。
- 约束：任何“在 api 里直接调 Agent”的捷径都会被架构测试阻断；接口定义不得泄漏 agentscope 类型。

## Alternatives

- **单体单进程**：实现最快，但 Agent 运行风险与 API 混合，无法独立扩缩容和隔离网络，T4 风险不可接受。
- **立即拆微服务**：边界清晰，但 M01 阶段缺少团队规模与运维能力支撑，分布式复杂度会拖慢 MVC。
- **把 agentscope 放进 platform-common**：会让所有部署物被动携带 Agent 运行时，直接违反技术栈隔离目标。

## Evidence

- `platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java`（6 条规则通过）
- 根 `pom.xml` 中 `maven-enforcer-plugin` 的 `banned-agentscope` 执行
- `docs/ARCHITECTURE.md` 组件与数据流章节
- `./mvnw -q -T1C verify` 证据（TASK-0002）
