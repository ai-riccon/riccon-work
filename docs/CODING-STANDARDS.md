# 编码规范

- **所有者**：`riccon/architect`
- **适用对象**：`apps/api`、`apps/worker`、`platform/**` 的 Java 21 / Spring Boot 4.1 代码；前端规范在 TASK-0003 建立 `apps/web` 时补充。
- **强制力**：本文件中的“必须/禁止”是评审阻断项。Builder 不得擅自修改构建配置、覆盖率门槛、格式化器、模块边界规则；需要变更时走决策包。
- **复现命令**：

```bash
./mvnw -q -T1C verify          # 编译 + 单元/切片测试 + 覆盖率门槛
./mvnw -q spotless:check       # 格式与导入检查
./mvnw -q spotless:apply       # 本地自动修复格式（提交前运行）
./mvnw -q -T1C verify jacoco:report   # 合同 commands.coverage 的后端部分
```

## 1. 技术栈与版本

| 项              | 版本                                  | 约束                                             |
| --------------- | ------------------------------------- | ------------------------------------------------ |
| JDK             | 21                                    | Maven Enforcer 锁定 `[21,22)`，不得使用 22+ 语法 |
| Maven           | 3.10.x                                | 通过 `./mvnw` 执行，不依赖本机 Maven             |
| Spring Boot     | 4.1.2                                 | 由 `spring-boot-starter-parent` 管理             |
| Spring Security | 7.1.2                                 | 由 Boot BOM 管理                                 |
| Spring AI       | 2.0.2                                 | M01 只导入 BOM，不引入具体 starter               |
| agentscope-java | 2.0.5                                 | 只能出现在 `apps/worker`                         |
| MySQL           | 8.4.13                                | 驱动 `com.mysql:mysql-connector-j`               |
| Flyway          | 12.4.0                                | 由 Boot BOM 管理                                 |
| JUnit           | 6.x                                   | Jupiter API，不用 JUnit 4                        |
| ArchUnit        | 1.5.1                                 | 架构规则                                         |
| Spotless        | 3.11.0 + Palantir Java Format 2.103.0 | 格式唯一来源                                     |

禁止自行升级版本、替换框架或引入第二套 JSON/HTTP/ORM/测试框架。版本变更必须同步 `AGENTS.md` 合同与 ADR。

## 2. 目录与包结构

```text
apps/api/src/main/java/com/riccon/api/
  ApiApplication.java
  config/            # Spring 配置、SecurityFilterChain
  <feature>/         # 按业务能力纵向分包：controller/service/repository/persistence/domain
apps/worker/src/main/java/com/riccon/worker/
  WorkerApplication.java
  config/
  agent/             # AgentScope 适配层（M02 起），不得被 api/platform 引用
  run/persistence/   # 运行、outbox 领取（唯一跨租户例外，见 ADR-0016）
platform/platform-common/src/main/java/com/riccon/platform/common/
  tenant/            # TenantId、TenantContext
platform/platform-domain/src/main/java/com/riccon/platform/domain/
  identity|workspace|file|chat|knowledge|research|model|mcp|audit|contract/
platform/platform-architecture-tests/src/test/java/com/riccon/architecture/
```

规则：

- 包名全小写，根包固定为 `com.riccon.api`、`com.riccon.worker`、`com.riccon.platform.common`、`com.riccon.platform.domain`。
- 业务能力按 feature 分包，不按技术层建全局大包；同一 feature 内再分 `controller/service/repository/persistence/domain`。
- `controller` 只做协议转换、校验、鉴权注解；`service` 承载事务与业务规则；`repository` 只做持久化；`persistence` 放 `TenantJdbc` 与行映射，`domain` 放该 feature 的局部值对象。
- `config` 包只放配置类；`@Configuration` 类必须位于 `..config..`（ArchUnit 强制）。
- 公共能力先放 `platform-common`；只被一个部署物使用的代码不要下沉；跨部署物共享的状态/权限/事件常量放 `platform-domain`。
- `platform-common` 不得依赖 Spring Web、`jakarta.servlet`、`apps/*`、`io.agentscope`；`platform-domain` 不得依赖 Spring、JPA、`apps/*`、`io.agentscope`。

### 模块边界（机器强制）

| 规则                                                          | 执行者                                         |
| ------------------------------------------------------------- | ---------------------------------------------- |
| `apps/api` 与 `platform` 不得依赖 `io.agentscope..`           | ArchUnit + Maven Enforcer `bannedDependencies` |
| 只有 `apps/worker` 可以依赖 agentscope                        | ArchUnit + Enforcer                            |
| `api` 与 `worker` 不得互相编译依赖                            | ArchUnit                                       |
| `platform` 不得依赖 `apps/*`                                  | ArchUnit                                       |
| `platform-common` 不得依赖 Spring/Web                         | ArchUnit                                       |
| `platform-domain` 不得依赖 Spring/JPA/agentscope              | ArchUnit + Enforcer                            |
| 项目禁止 JPA/Hibernate（`jakarta.persistence`/`hibernate`）   | ArchUnit + Enforcer                            |
| `JdbcClient` 只能出现在 `..persistence..`                     | ArchUnit                                       |
| `TenantJdbc` 只能位于 `..persistence..`                       | ArchUnit                                       |
| `PlatformJdbc` 只能在 `..admin..` 使用                        | ArchUnit（M03 前允许空）                       |
| MVC 分层单向：controller → service → repository → persistence | ArchUnit `layeredArchitecture`                 |

规则文件：`platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java`。规则失败时修改依赖方向，禁止用 `@ArchIgnore` 或放宽规则绕过；确需例外时写 ADR 并在规则中显式标注。

## 3. 命名

| 对象         | 规则                         | 示例                             |
| ------------ | ---------------------------- | -------------------------------- |
| 包           | 小写，点分隔                 | `com.riccon.api.chat.controller` |
| 类/记录/枚举 | PascalCase                   | `TenantContext`、`ChatSession`   |
| 方法/变量    | camelCase                    | `requireTenant()`、`tenantId`    |
| 常量         | UPPER_SNAKE_CASE             | `MAX_PAGE_SIZE`                  |
| 测试类       | 被测类名 + `Test`            | `TenantContextTest`              |
| 测试方法     | `被测方法_场景_期望`         | `requireFailsWhenTenantMissing`  |
| 数据库表/列  | snake_case                   | `chat_session`、`tenant_id`      |
| REST 路径    | 小写复数、短横线             | `/api/v1/knowledge-bases`        |
| JSON 字段    | camelCase                    | `tenantId`、`createdAt`          |
| 环境变量     | `RICCON_` 前缀 + UPPER_SNAKE | `RICCON_DB_URL`                  |
| 日志键       | camelCase 或点分小写         | `tenantId`、`agent.run_id`       |
| 错误码       | `RICCON-<DOMAIN>-<NNN>`      | `RICCON-TENANT-001`              |

禁止：拼音命名、无意义缩写（`tmp`、`data1`、`util2`）、单字母变量（循环下标除外）、中文标识符。

## 4. 格式化与静态检查

- 唯一格式化器：Spotless + Palantir Java Format，4 空格缩进，行宽 120，导入按字母排序并删除未使用导入。
- 提交前运行 `./mvnw -q spotless:apply`；CI 运行 `./mvnw -q spotless:check`，不通过即失败。
- 不在代码中手工对齐或使用 IDE 自带格式化覆盖 Spotless 结果。
- 不允许 `@SuppressWarnings` 掩盖未检查泛型/废弃 API；确需抑制时在同一行注释原因和移除条件。
- 编译告警不阻断构建，但新增告警必须在 PR 描述中说明；禁止新增 `deprecation` 告警。
- 禁止提交 `System.out`、`System.err`、`printStackTrace`、`TODO` 占位实现。

## 5. Java 21 约定

- 数据传输对象优先使用 `record`；需要可变状态或 JPA 实体时用普通类。
- `Optional` 只用于返回值，不用于字段、方法参数或集合元素。
- 禁止返回 `null` 集合；返回空集合或 `Optional`。
- 字符串拼接使用 `StringBuilder` 或文本块；SQL 使用参数绑定。
- 时间统一使用 `java.time`（`Instant`/`OffsetDateTime`），禁止 `Date`/`Calendar`。
- 金额、比例等精确值使用 `BigDecimal`，禁止 `double`。
- 集合默认使用 `List`/`Map` 接口类型；需要不可变时用 `List.copyOf`/`Map.copyOf`。
- 不使用 `var` 隐藏关键类型；局部变量类型明显时可用。
- 不使用 Java 21 预览特性（如未定稿的 ScopedValue/虚拟线程 API 变更），避免构建依赖 `--enable-preview`。
- 显式关闭资源：`try-with-resources` 优先；`TenantContext` 必须用 `try (TenantScope ...)`。
- 业务 ID 统一 `java.util.UUID`（UUIDv7）；只在 `..persistence..` 转 `BINARY(16)`，controller/service 不暴露字节数组。
- 状态值使用 `platform-domain` 的枚举名；数据库存枚举名（大写），不得在业务代码写状态字符串字面量。

## 5.5 时间与时区

- 团队默认时区：`Asia/Shanghai`（UTC+08:00），由合同 `project.timezone` 声明。
- 数据库目标使用带时区语义的类型（`timestamptz` / `timestamp with time zone`）。当前 MySQL 8.4 没有 `timestamptz`，按 ADR-0008/0012 使用 `DATETIME(6)` 存储 UTC，连接初始化 `SET time_zone='+00:00'`；这是已记录的存储层例外，不得扩散为本地时区存储。
- API/日志/事件/UI 使用 ISO 8601 带偏移，例如 `2026-11-18T09:30:00+08:00`；禁止只发 `YYYY-MM-DD HH:mm:ss`。
- 仅存储层使用 UTC；读写边界和展示层必须转换为北京时间；测试注入 `Clock`/`ZoneId`，禁止依赖运行机器本地时区。
- 修改时间类型、存储口径或展示口径必须写 ADR，并给出迁移与回滚方案。

## 6. Spring Boot 4.1 约定

- **构造器注入**：禁止 `@Autowired` 字段注入；单构造器可省略注解。
- **配置类**：使用 `@Configuration(proxyBeanMethods = false)`；配置类集中在 `config` 包。
- **配置属性**：外部配置用 `@ConfigurationProperties` + 校验，不用散落的 `@Value`；前缀 `riccon.*`。
- **Web 层**：M01 使用 `spring-boot-starter-webmvc`（Boot 4 的规范 starter），不要同时引入 WebFlux。
- **异常**：REST 边界统一转换为 `ProblemDetail`；业务异常在 M02 建立基类，禁止把 `Exception` 直接抛出控制器。
- **事务**：`@Transactional` 只放在 service 的公共方法；只读查询标注 `readOnly = true`；禁止在 controller 开启事务。
- **安全**：新增端点默认被 `denyAll()` 覆盖；需要放行时必须显式列出路径并说明理由，禁止 `anyRequest().permitAll()`。
- **数据访问**：M02 使用 Spring JDBC（`JdbcClient`），不引入 JPA/Hibernate；`JdbcClient` 只能注入 `..persistence..`，业务代码只依赖 `TenantJdbc`。SQL 必须参数绑定，禁止拼接用户输入。
- **会话与 CSRF**：使用 Spring Session JDBC + `HttpSessionCsrfTokenRepository`；写操作必须校验 `X-CSRF-TOKEN`；登录后轮换会话，登出失效（ADR-0013）。
- **健康检查**：`/actuator/health` 保持无认证、无外部依赖；不要把模型/MCP 探活塞进 readiness。
- **日志**：使用 SLF4J，不直接依赖 Logback API。
- **启动类**：排除 `UserDetailsServiceAutoConfiguration` 的基线不得移除，除非认证方案已落地并评审。

## 7. 异常与错误码

- 业务失败用异常表达，不用返回 `null` 或布尔值表示多种失败原因。
- 异常层次（M02 建立）：`RicconException`（受检业务失败）→ 领域子类；技术异常包装为 `InfrastructureException`。
- 每个对外错误必须有稳定错误码，格式 `RICCON-<DOMAIN>-<NNN>`；同一错误的码不得复用。
- HTTP 映射：参数错误 400，未认证 401，无权限 403，不存在 404，冲突 409，限流 429，内部错误 500。
- 响应使用 RFC 9457 `ProblemDetail`，至少包含 `type`、`title`、`status`、`detail`、`code`、`traceId`。
- 禁止把堆栈、SQL、内部主机名、密钥、完整提示词返回给客户端。
- 捕获异常时必须处理或添加上下文再抛出；禁止空 `catch`、禁止捕获 `Throwable` 后继续执行。
- 批量任务必须区分“可重试”和“不可重试”错误，并在日志中带 `retryable=true/false`。

## 8. 日志

- 使用参数化日志：`log.info("agent.run.completed runId={} tenantId={} durationMs={}", ...)`。
- 日志消息用英文短句，键值对便于检索；禁止拼接字符串生成日志。
- 必须带上下文：`tenantId`、`traceId`、`requestId`、`runId`（按场景）。
- 级别：`ERROR` 仅用于需要人工介入的失败；`WARN` 用于可恢复异常；`INFO` 记录状态变化；`DEBUG` 只用于本地排障。
- 禁止记录：密码、token、Cookie、密钥、完整身份证/手机号/邮箱、完整提示词、知识库原文、MCP 凭据。
- 需要记录用户内容时先脱敏（保留前后各 4 字符或哈希），并在代码注释说明脱敏规则。
- 审计日志与业务日志分离：审计必须落库，不能只写日志文件。
- 日志中禁止使用中文错误消息作为唯一检索键；错误码与英文键必须存在。

## 9. 注释（中文）

- 所有 `public`/`protected` 类和方法必须有中文 Javadoc，说明职责、参数、返回值和抛出的异常。
- 复杂业务流程必须有行级或段落注释，解释“为什么”而不是“做了什么”；纯 getter/setter 不写重复注释。
- 安全相关代码必须注释威胁与边界，例如“此处不放行任何业务路径，认证在 M02 接入”。
- 数据库迁移、外部协议适配、重试/幂等、租户传播必须有中文说明。
- TODO 格式：`// TODO(owner, M0x): 具体动作与移除条件`；禁止无 owner、无期限的 TODO。
- 禁止注释掉的代码；删除而不是注释。
- 标识符、日志键、错误码、命令保持英文；注释、文档、commit message 使用中文。

## 10. 测试

- 框架：JUnit 6 Jupiter + AssertJ；Spring 切片测试用 `@SpringBootTest`/`@AutoConfigureMockMvc`；禁止 JUnit 4。
- 单元测试不启动 Spring；只有需要验证装配、安全链、序列化时才用切片测试。
- 测试方法命名：`被测方法_场景_期望`，例如 `requireFailsWhenTenantMissing`。
- 每个测试只验证一个行为；断言必须具体（状态码、错误码、字段值），禁止只断言 `not null`。
- 禁止 `Thread.sleep`、依赖执行顺序、依赖真实时间或随机数；需要时钟时注入 `Clock`。
- 数据库/外部服务测试使用 `deploy/compose/docker-compose.test.yml` 或 Testcontainers；测试数据必须可重复清理。
- 安全测试必须覆盖：未认证访问、越权访问、跨租户访问、默认拒绝路径。
- 覆盖率门槛（合同 `constraints.coverage`）：
  - 行覆盖率 ≥ **80%**，分支覆盖率 ≥ **70%**；
  - 由 `jacoco:check` 在 `verify` 阶段强制执行；
  - 排除项仅允许启动类（`**/*Application.class`）；新增排除必须在 PR 说明理由并由 verifier 确认；
  - 报告路径：`apps/*/target/site/jacoco/index.html`、`platform/*/target/site/jacoco/index.html`；
  - 复现命令：`./mvnw -q -T1C verify jacoco:report`。
- 测试必须真实执行；0 个测试的命令视为失败，不得用 `-DskipTests` 作为交付证据。
- 前端 E2E 与覆盖率在 TASK-0003 建立 `apps/web` 后补充，命令以合同 `commands.test_e2e` 为准。

### 10.1 测试执行与证据分层

- **L1 内层循环**：只跑受影响单元/集成测试或 `-DskipTests` 编译；可用后续的受影响测试脚本，但不录 EV，不能用于任务完成或模块 gate。
- **L2 任务收尾**：低/中风险任务默认只跑受影响模块一次全量；高风险（认证、权限、迁移、并发、安全、AI 工具调用）、跨模块契约/迁移、模块最后一个任务在最终提交上跑一次全量。录 EV 时以最终一次通过为准。
- **L3 模块 gate**：在最终提交上跑一次完整 `commands.test` + `commands.coverage` + E2E/a11y/security，由 verifier 独立执行并绑定 `dod_evidence`。任务级定向证据不能替代模块 gate 全量。
- `./mvnw -q -T1C verify` 已包含 API 与 worker 测试；同一任务不得再重复跑 worker 全量。共享层/契约/迁移变更必须全量一次。
- 同一份代码的全量只跑一次；先提交，再在**主工作树**用 `riccon evidence run` 取证，不先跑一遍再取证，不为取证新建临时 worktree。
- 长测试必须设置显式超时并保留日志；超时视为失败，不得重试掩盖。
- 并行 builder/verifier 同一时间只允许一个全量套件运行，避免 Docker/MySQL/CPU 争抢导致假失败；定向测试可并行。
- 本地热测试栈（若启用）只用于单 agent 内层循环，默认与 CI 仍使用隔离容器；迁移/回滚测试必须使用一次性容器。

### 10.2 热测试栈与受影响测试（操作补充）

- 本节只补充操作方式，不改变 §10.1 的分层、取证与 gate 结论；L1/L2/L3 仍以 §10.1 为准。
- 本地热栈必须显式 opt-in：`RICCON_TEST_SERVICES=external` + 每个 agent 唯一的 `RICCON_TEST_SERVICES_NAMESPACE`；同一 namespace 同时只允许一个测试 JVM，命名锁失败时不得强行复用。
- `node scripts/test-affected.mjs` / `pnpm test:affected` 只根据 git 变更给出 L1 定向命令，不录 EV，不能替代 L2/L3 的合同全量测试与 verifier 证据。
- 迁移、回滚与 schema 版本测试始终使用一次性容器，不得指向热栈。
- 默认与模块 gate 不设置 `RICCON_TEST_SERVICES`，仍使用 Testcontainers；CI backend job 是唯一例外（M19 P1）：显式 `RICCON_TEST_SERVICES=external` + 每个 job 唯一 namespace 复用 compose 热栈，命令与测试集合不变，迁移/回滚/schema 测试仍用一次性容器。启动/停止/重置、namespace 规则、CI 热栈流程与故障处理见 `docs/RUNBOOK.md` 的“热测试栈与受影响测试”和“CI 热栈（external + namespace，M19 P1）”。

### 10.3 测试时间预算（M19）

- 依据 DEC-0026（P0–P4 已批准）：先测量、后提速；本小节只定义预算与超预算处理，不改变 §10.1/§10.2 的分层、取证与 gate 结论。
- 预算口径：
  - **L2 ≤ 5 分钟**：专指任务级**定向受影响测试回路**，范围由 `node scripts/test-affected.mjs` 给出的建议命令界定。它不是 §10.1 的 L2（任务收尾受影响模块全量），后者仍按 §10.1 执行。
  - **后端全量 ≤ 10 分钟**：CI `构建与测试`（`./mvnw -q -T1C verify`）在 4-shard 分片（P2）落地前的墙钟上限。
  - **后端全量 ≤ 5 分钟**：4-shard 分片落地后的墙钟上限。
- 测量来源：`node scripts/test-timings.mjs` 的 surefire 汇总（用例数、失败/错误/跳过、累计 suite 时间、top-20 慢类）与 CI backend job 的步骤墙钟；两者同时看，不以单次本地数字替代 CI 口径。
- 超预算处理：
  - 先看报告 top-20，确认是 setup 摊销（容器/迁移/夹具）还是单类真实耗时；
  - 建 P2 任务并写明 `owner` 与 `due`（例如归入 M19 的 P1/P3/P4 任务），在任务卡和评审中留痕；
  - **禁止**用删除用例、加 `@Disabled`/`skip`、放宽断言或 `-DskipTests` 来达标；用例集合与 `commands.test` 不变。
  - 分片（P2）落地后若要让分片命令成为 gate 证据，必须另提合同决策，不得默认替换 `commands.test`。

### 10.4 测试 tag 分层（M19 P4a）

- 目的与边界：JUnit 5/6 类级标签只用于**按层选择执行**与报告归类，不改变默认执行集合、用例数量、断言或执行顺序。`commands.test`/`commands.coverage` 与模块 gate 仍是全量命令（§10.1）；标签过滤结果不能冒充全量或 gate 证据。
- 标签定义：

| 标签           | 含义                                                                 | 声明位置                                                                                 |
| -------------- | -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `integration`  | 需要 Spring 上下文、真实 MySQL/RustFS/OpenSearch 或 HTTP 端口        | `ApiIntegrationTestSupport`、`FileApiTestSupport`、`WorkerIntegrationTestBase`；子类继承 |
| `security`     | 越权、租户/部门隔离、凭据泄露、安全审计边界                          | `SecurityTestSupport`；未继承它的独立套件逐类标注                                        |
| `migration`    | Flyway 迁移、回滚、schema 版本校验；始终使用一次性容器               | `V9MigrationTest`、`V10MigrationTest`、`V11MigrationTest`、`SchemaVersionCheckerTest`    |
| `unit`（约定） | 未带上述标签即 unit：纯 JUnit/AssertJ/ArchUnit，不启动 Spring 与容器 | 不逐类标注                                                                               |

- 当前映射（M19 P4a）：
  - `integration`：API 全部 `@SpringBootTest` 基类子类（含文件、研究、配额、成本、审计与安全套件），以及 Worker `WorkerIntegrationTestBase` 子类。
  - `security`：`DepartmentIsolationTest`、`TenantIsolationSecurityTest`（继承 `SecurityTestSupport`），`TenantIsolationTest`，`ChatScopeApiTest`、`ContentScopeApiTest`，`M04VerifierGatewaySecurityTest`、`M04VerifierProductionEndpointTest`、`M04VerifierSwitchDeltaTest`、`M04V2VerifierBoundaryTest`，`ModelCredentialSecurityTest`、`ModelRoutingAuditSecurityTest`；原有 `dept-isolation`/`tenant-isolation` 标签保留。认证/会话功能流（`AuthFlowTest`、`OidcLoginFlowTest`）仍归 `integration`，不强行并入 `security`。
  - `migration`：上表四个类；它们不继承集成基类，因此只带 `migration`。
  - 其余测试类（含 `platform/**` 的纯单测与 ArchUnit）视为 unit。
- 继承规则：
  - JUnit 5/6 的 `@Tag` 是可继承注解：类级标签沿抽象基类多级传递到子类；`@Nested` 继承外层类标签；接口上的标签不继承。新增测试优先继承现有基类，不复制标签。
  - `security` 是叠加标签：安全套件同时具备 `integration`；`-Dgroups=integration` 会包含安全套件。
- Surefire 选择示例（JUnit Platform provider；均为 L1 排障/定向命令）：

```bash
# API 集成层（含安全套件）
./mvnw -q -pl apps/api -am test -Dgroups=integration -Dsurefire.failIfNoSpecifiedTests=false

# 安全套件（继承的 security 标签与类上原有 dept/tenant 标签共同生效）
./mvnw -q -pl apps/api -am test -Dgroups=security -Dsurefire.failIfNoSpecifiedTests=false

# 迁移/回滚/schema 版本（一次性容器）
./mvnw -q -pl apps/api -am test -Dgroups=migration -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl apps/worker -am test -Dgroups=migration -Dsurefire.failIfNoSpecifiedTests=false

# 类名与标签同时过滤：冒烟确认继承标签生效
./mvnw -q -pl apps/api -am test -Dtest=DepartmentIsolationTest#deptSuiteIntegrityManifest \
  -Dgroups=security -Dsurefire.failIfNoSpecifiedTests=false

# unit（未打标签）：排除慢层；示例只跑 platform-common
./mvnw -q -pl platform/platform-common test -DexcludedGroups=integration,security,migration
```

- `-pl <module> -am` 会一并构建 platform 模块；这些模块没有匹配标签的测试，必须加 `-Dsurefire.failIfNoSpecifiedTests=false`，否则 Maven 以“没有执行测试”失败。该参数只避免无关模块阻断，不能把空选择当作通过：每次选择冒烟都必须看到预期的非零用例数。
- `-Dgroups=unit` **不会**选中未打标签的测试；untagged=unit 是分类约定，不是可过滤标签。只跑 unit 用 `-DexcludedGroups=integration,security,migration`，可再配合 `-Dtest` 或模块选择。
- 迁移/Schema 测试禁止指向热栈（§10.2）；external 模式下 `SchemaVersionCheckerTest` 也自建一次性容器。
- 与分片基线的关系：`scripts/test-shards.mjs` 以类名与 surefire 用例计数为基线，标签不参与分片；新增 tag 不改变基线类集合/用例数，仍以 `node scripts/test-shards.mjs --check` 校验。

## 11. 数据库与迁移

- 只使用 Flyway；迁移文件位于 `apps/api/src/main/resources/db/migration`。
- 命名：`V{n}__{snake_case_描述}.sql`，序号连续，不修改已应用的文件。
- 每个业务实体表必须包含：`id`、`tenant_id`、`created_at`、`updated_at`；需要软删除时加 `deleted_at`。关联表与密钥表可使用自然复合主键（如 `(tenant_id, role_id, permission_code)`），不额外生成 `id`。
- 主键 `(tenant_id, id)`，`id` 为应用生成的 UUIDv7，数据库 `BINARY(16)`；禁止自增主键与 `VARCHAR(36)` 存 UUID。
- 索引必须以 `tenant_id` 开头或包含 `tenant_id`，除非是全局字典表并写 ADR；全局表与特殊索引例外见 ADR-0012。
- 唯一键以 `tenant_id` 开头；结构性父子关系使用复合外键 `(tenant_id, parent_id)`。
- 时间列使用 `DATETIME(6)`（UTC），禁止 `TIMESTAMP`；连接初始化必须 `SET time_zone='+00:00'`。
- 状态列使用 `VARCHAR(32)` + `CHECK`，值必须与 `platform-domain` 枚举名一致；禁止 MySQL `ENUM`。
- 表、关键列、CHECK 与特殊索引必须有中文 `COMMENT` 或 `--` 注释；迁移头部说明状态机、幂等与例外。
- 迁移只向前兼容：新增列允许 `NULL` 或带默认值；重命名/删除必须分两步并保留回滚脚本。
- 破坏性变更（删表、删列、改类型、数据回填）必须有 ADR、回滚脚本和 sandbox 演练记录。
- 禁止在迁移中使用 `DROP DATABASE`、跨库写入或依赖生产数据。
- 审计触发器需要迁移账号具备 `TRIGGER`，binlog 开启时需 `log_bin_trust_function_creators=1`；不可用时迁移失败，不得降级为无触发器。
- 大表变更必须评估锁表时间，并在 RUNBOOK 记录操作窗口。

## 12. 安全基线

- **默认拒绝**：安全过滤器链默认 `denyAll()`；只放行健康检查。新增白名单路径必须评审。
- **最小权限**：数据库账号只授予所需库表权限；生产不使用 root。
- **租户隔离**：所有业务查询必须带 `tenant_id`；`TenantContext.require()` 失败时不得降级为全租户查询。
- **认证与会话**：M02 使用 Spring Session JDBC 服务端会话；Cookie `HttpOnly`、`SameSite=Lax`、非本地 `Secure`；登录后轮换会话 ID，登出失效；密码 bcrypt 12；统一失败提示；登录限流与锁定；所有写操作校验 CSRF 令牌（ADR-0013）。
- **CSRF**：`X-CSRF-TOKEN` 来自 `GET /api/v1/auth/csrf` 或登录响应；不使用 JS 可读的 CSRF Cookie；缺失/过期返回 403。
- **输入校验**：所有外部输入在 controller 边界校验；文件上传限制类型、大小和存储路径；禁止路径穿越。
- **SSRF**：模型网关与 MCP 端点使用允许列表；禁止用户可控 URL 直接出网。
- **提示注入**：检索内容与工具返回一律视为数据；不得拼接进系统指令；高风险工具必须 HITL。
- **密钥**：只从环境变量或密钥管理读取；日志、异常、证据、测试快照中不得出现真实密钥。
- **依赖安全**：新增依赖必须检查许可证（Apache-2.0/MIT/BSD/EPL）与已知漏洞；CI/发布前运行 Trivy。
- **审计**：模型、工具、MCP、权限、租户变更必须审计；审计失败时业务失败。

## 13. 性能

- API p95 ≤ 500 ms（不含模型与外部 MCP）；首字 p95 ≤ 3 s（假模型基准）。
- 所有列表接口必须分页，默认 20，最大 100；禁止无界查询。
- 禁止 N+1 查询；批量查询使用 `IN` 或 join，并在测试中验证查询次数。
- 外部调用（模型、MCP、对象存储）必须设置连接/读取超时和重试上限；重试只用于幂等操作。
- 大文件走流式处理，禁止一次性读入内存；上传大小上限在配置中显式声明。
- SSE 必须设置空闲超时（默认 60 秒）与总时长上限；客户端断开后停止上游模型调用。
- outbox 领取使用 `FOR UPDATE SKIP LOCKED`，批量有上限；禁止无界轮询或全表扫描。
- 性能敏感变更必须附 k6 或基准测试证据；未达到预算不得发布。

## 14. Git 与提交

- 分支从 `main` 创建；M01 只使用 `main`。
- Commit message：`type(scope): 中文摘要`，type ∈ `feat|fix|chore|docs|test|refactor|build`。
- 一个 commit 只做一件事；格式化与行为变更分开提交。
- 不提交 `.env`、密钥、构建产物、`target/`、`node_modules/`、日志、`.DS_Store`。
- PR 描述必须包含：变更范围、验收标准、测试命令与结果、风险与回滚方式。
- 不执行 `git push --force`、`rm -rf`、`sudo` 等破坏性命令，除非用户明确批准。

## 15. 评审检查清单

- [ ] 依赖方向符合模块边界，agentscope 未出现在 api/platform；未引入 JPA/Hibernate。
- [ ] 新增端点默认被拒绝，白名单有理由；写操作校验 CSRF。
- [ ] 所有外部输入已校验，错误使用 `ProblemDetail` + 稳定错误码。
- [ ] 日志无 PII/密钥，关键路径带 `tenantId`/`traceId`。
- [ ] 公共类/方法有中文 Javadoc，复杂流程有中文行级注释。
- [ ] 新增/修改行为有对应测试，覆盖率门槛通过。
- [ ] 数据库迁移可向前兼容，租户键/索引/复合外键符合 ADR-0012，破坏性变更附 ADR 与回滚脚本。
- [ ] 契约（OpenAPI/事件 schema）已同步，事件幂等键与 SSE 事件经过校验。
- [ ] 性能预算与超时/重试已评估。
- [ ] `./mvnw -q -T1C verify` 与 `./mvnw -q spotless:check` 通过。
- [ ] 文档已同步，或记录 no-impact 理由。
