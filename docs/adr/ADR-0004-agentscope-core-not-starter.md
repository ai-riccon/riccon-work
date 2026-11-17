# ADR-0004：Worker 使用 agentscope-core，不引入 agentscope-spring-boot-starter

Status: accepted
Date: 2026-11-17

## Context

合同要求 agentscope-java 精确 2.0.5，且只能在 `apps/worker` 使用。AgentScope 提供 `agentscope-core` 与 `agentscope-spring-boot-starter` 两种接入方式。M01 只需要可启动骨架与健康检查；M02 起需要按租户、按任务管理 Agent 生命周期、模型客户端与 MCP 连接。

## Decision

只引入 `io.agentscope:agentscope-core:2.0.5`，不引入 starter。

理由：

1. **版本一致性**：starter 2.0.5 的 POM 依赖 Spring Boot 4.0.1 的 autoconfigure/configuration-processor；根 BOM 是 4.1.2。虽然 Maven 会仲裁到 4.1.2，但 starter 的自动配置假设与 Boot 4.1 的模块拆分并不完全一致，排查成本高。
2. **生命周期可控**：Worker 需要按租户/任务创建和销毁 Agent，不能接受自动装配产生的全局单例；M02 的适配层需要显式构造与关闭资源。
3. **安全边界**：starter 会扫描并注册 Agent 相关 bean，扩大攻击面；core 只提供库能力，由 Worker 自己决定暴露什么。
4. **能力足够**：core 已包含 Agent、模型客户端、MCP 客户端与工具调用能力；starter 只是自动配置糖。

## Consequences

- 正面：依赖树更可控，Boot 版本只有一个事实源；M02 可以在 `com.riccon.worker.agent` 下写显式适配层，并通过接口隔离 agentscope 类型。
- 负面：需要自己管理 Agent 的构造、模型客户端与关闭；M02 增加少量样板代码。
- 约束：`io.agentscope` 类型不得出现在 `platform-common` 的接口签名中；SPI 接口只使用 JDK 类型与项目自有 DTO。

## Alternatives

- **agentscope-spring-boot-starter**：接入快，但引入 Boot 4.0.1 依赖假设与自动 bean，违背“最小可控”的 T4 基线。
- **暂不引入 agentscope，M02 再加**：会导致 M01 无法验证依赖可用性与隔离规则；当前选择已通过编译与架构测试。

## Evidence

- `apps/worker/pom.xml` 仅声明 `agentscope-core`
- `./mvnw -q -T1C verify` 通过（worker 可启动、健康检查通过）
- ArchUnit 规则证明 api/platform 不依赖 `io.agentscope..`
