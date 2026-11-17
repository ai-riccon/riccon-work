# ADR-0003：租户上下文基线使用 ThreadLocal + 显式作用域

Status: accepted
Date: 2026-11-17

## Context

M02 起所有业务数据必须按租户隔离。同步 HTTP + JDBC 场景需要一个简单、可测试的租户上下文；M01 不希望引入 Reactor 上下文传播、TransmittableThreadLocal 或 AOP 切面，因为这些机制会扩大基线复杂度，且在异步边界容易产生隐式行为。

## Decision

1. `platform-common` 提供 `TenantId` 值对象与 `TenantContext`：
   - 基于 `ThreadLocal`；
   - `open(TenantId)` 返回 `AutoCloseable` 作用域，关闭时恢复进入前的租户，支持嵌套；
   - 未设置租户时 `require()` 立即抛出 `IllegalStateException`，禁止降级为全租户查询；
   - 提供 `callWithTenant` / `runWithTenant` 两个显式包装。
2. 请求入口由 Filter/Interceptor 打开作用域；跨线程提交任务前必须捕获租户并在目标线程重新打开。
3. M01 不接入 Reactor、不引入线程继承、不做隐式传播；M02 在异步/流式链路中按实际需要扩展，扩展前先写 ADR。

## Consequences

- 正面：实现小、可单测、失败语义明确；线程池复用导致的串租户风险由 `finally`/`close` 显式清除。
- 负面：异步与响应式链路需要手动传递；忘记打开作用域会导致快速失败（这是有意的）。
- 约束：禁止把 `TenantContext` 当作全局缓存；禁止在 `TenantContext` 中存放用户、权限或请求对象。

## Alternatives

- **Reactor Context**：适合响应式链路，但 M01/M02 主要是同步 MVC，引入后需要在每个 `publishOn`/`flatMap` 边界处理，复杂度不匹配。
- **TransmittableThreadLocal**：能自动传播到线程池，但隐式行为难以审计，且增加第三方依赖。
- **方法参数显式传递 TenantId**：最安全但侵入所有签名；保留为跨进程契约的传递方式，进程内用 `TenantContext` 降低噪音。

## Evidence

- `platform/platform-common/src/main/java/com/riccon/platform/common/tenant/TenantContext.java`
- `platform/platform-common/src/test/java/com/riccon/platform/common/tenant/TenantContextTest.java`（嵌套恢复、重复关闭、线程清理、缺失失败）
- `./mvnw -q -T1C verify` 覆盖率门槛
