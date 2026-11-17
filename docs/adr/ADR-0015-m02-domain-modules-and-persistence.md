# ADR-0015：M02 领域模块边界、共享词汇与持久化归属

Status: accepted
Date: 2026-11-17
关联：ADR-0001（模块化单体双部署物）、ADR-0009（Spring JDBC/TenantJdbc）、ADR-0012（数据模型）、`docs/CODING-STANDARDS.md`

## Context

M02 会同时扩展 `apps/api`（同步 REST、会话、文件、知识库、任务、审批、审计）与 `apps/worker`（Agent 运行、模型、MCP、解析、索引）。两者需要共享状态枚举、权限码、审计动作与事件名，但不能共享 Repository、JPA 实体或彼此的实现。M01 只有 `platform-common`（租户上下文）与 `platform-architecture-tests`。

如果让 api 与 worker 各自复制枚举，状态值会在迁移、OpenAPI、事件和代码之间漂移；如果把实体或 Repository 下沉到 `platform`，会绕开每个部署物唯一的 `TenantJdbc`，并让 `platform` 依赖 Spring/JDBC。需要明确“共享什么、不共享什么”。

## Decision

### 1. 新增 `platform/platform-domain`

- 新 Maven 模块 `com.riccon:riccon-platform-domain`，只依赖 JDK 与测试库，不依赖 Spring、JPA/Hibernate、agentscope 或 `apps/*`。
- 内容：
  - 状态枚举：用户、工作空间、文件/解析、消息、知识库、任务/运行/阶段/审批/产物、模型调用、MCP、审计；
  - 常量接口：`RoleCodes`、`PermissionCodes`、`AuditActions`、`EventTypes`、`HttpHeaders`；
  - 不包含 JPA 实体、Repository、Service、Controller、SQL 或业务规则。
- `platform-common` 继续只放租户上下文、主密钥 SPI、`SchemaVersion` 等无框架内核；不把领域状态塞进 common。

### 2. 不建 `platform-persistence`

- 不建立共享持久化模块，也不放 JPA 实体。原因：
  - ADR-0009 已选 Spring JDBC，没有 ORM 实体；
  - 每个部署物必须拥有自己的 `TenantJdbc` 强制点，共享 Repository 会让租户守卫失效；
  - api 与 worker 的查询模式不同（api 面向用户，worker 面向 run/outbox），共享 Repository 会制造耦合。
- 行映射、SQL、`TenantJdbc` 放在各部署物的 `..persistence..`；业务编排放在 `..service..`；HTTP 协议放 `..controller..`。

### 3. 依赖方向

```text
apps/api  ──> platform-domain
apps/api  ──> platform-common
apps/worker ─> platform-domain
apps/worker ─> platform-common
apps/api  ──X──> apps/worker（只能通过 contracts 通信）
apps/worker ─X──> apps/api
platform/* ─X──> apps/*
platform-domain ─X──> Spring / JPA / agentscope
```

- `apps/api`：`spring-boot-starter-jdbc`、`spring-session-jdbc`、Flyway、MySQL 驱动（runtime）；禁止 JPA/Hibernate 与 agentscope。
- `apps/worker`：已有 agentscope-core；M02 持久化实现任务再加入 `spring-boot-starter-jdbc` 与 MySQL 驱动（runtime），并关闭 Flyway；禁止 JPA/Hibernate。
- `apps/api` 是唯一迁移者；worker 只做 schema 版本校验（ADR-0010）。

### 4. 机器强制

在 `ModuleBoundaryTest` 增加并保持以下规则：

| 规则                                                  | 说明               |
| ----------------------------------------------------- | ------------------ |
| agentscope 只能在 worker                              | 既有规则           |
| api/worker 不互相依赖                                 | 既有规则           |
| platform 不依赖 apps                                  | 既有规则           |
| platform-common 不依赖 Spring/Web                     | 既有规则           |
| platform-domain 不依赖 Spring/Jakarta/agentscope/apps | 新增               |
| 项目禁止 `jakarta.persistence`/`org.hibernate`        | 新增               |
| `JdbcClient` 只能出现在 `..persistence..`             | 新增               |
| `TenantJdbc` 只能位于 `..persistence..`               | 新增               |
| `PlatformJdbc` 只能在 `..admin..` 使用                | 新增，M03 前允许空 |
| controller → service → repository → persistence 单向  | 扩展既有分层规则   |
| `@Configuration` 只能在 `config` 包                   | 既有规则           |

规则失败时修改依赖方向，禁止用 `@ArchIgnore` 放宽；确需例外必须写 ADR 并在规则中显式标注。

### 5. 包结构（M02 目标）

```text
apps/api/src/main/java/com/riccon/api/
  auth/{controller,service,persistence,domain}
  workspace/{controller,service,persistence,domain}
  file/{controller,service,persistence,domain}
  chat/{controller,service,persistence,domain}
  knowledge/{controller,service,persistence,domain}
  research/{controller,service,persistence,domain}
  model/{service,persistence,domain}
  mcp/{service,persistence,domain}
  audit/{service,persistence,domain}
  config/

apps/worker/src/main/java/com/riccon/worker/
  run/{service,persistence,domain}
  agent/            # AgentScope 适配，仅 worker
  model/            # OpenAI 兼容网关
  mcp/              # MCP 客户端与白名单
  parse/            # 文件解析
  index/            # OpenSearch 索引
  audit/{service,persistence}
  config/
```

`platform-domain` 只提供词汇，不规定 feature 包内的类名；Builder 按 CODING-STANDARDS 的 `被测方法_场景_期望`、中文注释与 MVC 规则实现。

## Consequences

- 正面：状态/权限/事件值有单一事实源；api 与 worker 不共享实现，租户守卫保持每部署物唯一；ArchUnit 能阻止 ORM 与跨部署物依赖。
- 负面：新增一个 Maven 模块；共享词汇变更需要跨模块评审；worker 的 JDBC 依赖要等到持久化任务才加入，M02 实现顺序需注意。
- 约束：任何新的共享类型必须先确认两边都使用；不得把 Spring 注解放进 domain；不得把 Repository 下沉到 platform；不得在 api/worker 复制状态字符串（用枚举名或常量）。

## Alternatives

- **每个部署物各自定义枚举**：实现快，但状态值必然漂移；拒绝。
- **共享 `platform-persistence` + JPA 实体**：违反 ADR-0009 与 TenantJdbc 强制点；拒绝。
- **共享 Repository 接口，各部署物实现**：看似解耦，但查询语义与事务边界不同，容易变成分布式单体；M02 不需要。
- **把 domain 放进 platform-common**：会让 common 承担领域知识，破坏“无框架内核”边界；拒绝。
- **引入 ArchUnit 之外的模块系统（JPMS）**：Maven/Spring 生态成本高；ArchUnit + Enforcer 已足够。

## Evidence

- `platform/platform-domain`（模块、枚举、常量、`DomainVocabularyTest`）
- `platform/platform-architecture-tests` 新增规则与 `projectClassesAreLoaded`
- `pom.xml`、`apps/api/pom.xml`、`apps/worker/pom.xml`
- `docs/CODING-STANDARDS.md` 第 2 节目录与边界表
