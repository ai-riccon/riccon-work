---
review_id: REV-TASK-0240-001
module_id: M06
task_id: TASK-0240
reviewer:
  agent: riccon/verifier
  session: ses_df0b376fdffew7JS9lJUeNLuSf
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 8d0252f159e3e7211c6af20f32f73b9d43ed8c09
  - bd59bca83947f22880d522574cd0de5fefafe740
  - c1f5d6e4791f7c09a8ae72321658a8ed1ec3b4e3
head_commit: 8d0252f159e3e7211c6af20f32f73b9d43ed8c09
baseline_commit: 50c6e89bedf2360f9a0aafc4180a9d613f4c48f9
review_scope: >-
  M06 T01 ArchUnit 分层修复（TASK-0240）的独立任务评审：核对 AppConfig config→service、
  SecurityConfig config→controller 两个 P0 违例是否真实消除，ArchUnit 规则是否原样通过，安全过滤器
  顺序（tenant→workflow→origin）、平台租户 404、未认证 401、J1/J2 安全链/CSRF 是否保持，审计写入
  适配器的委托/事务/启动失败语义，以及范围、证据和回归。不替代 M06 模块 gate、Product 预验收或
  用户产品验收。
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
dod_note: >-
  acceptance 指任务卡 TASK-0240 的三条验收：两个 ArchUnit P0 修复、指定测试通过、范围/契约/独立评审
  约束。coverage、E2E、security、perf、a11y 不属于本次分层修复的验收面，留给 M06 模块 gate 与 T16a；
  本任务未运行全量 E2E/perf/coverage，也未运行 gate check。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "M06 gate 前（T16a 安全矩阵）"
    title: "未认证 `/api/v1/workflows` 的 401 只有过滤器 pass-through 单测，没有直接集成断言"
    summary: >-
      `WorkflowPlatformTenantFilterTest#unauthenticatedRequestsPassThroughFor401`
      （`apps/api/src/test/java/com/riccon/api/workflow/web/WorkflowPlatformTenantFilterTest.java:66-70`）
      只断言未认证请求继续进入 FilterChain，并未断言最终 HTTP 状态为 401；`assertPassesThrough`
      用 MockHttpServletRequest 直接调用过滤器，不经过安全链。`WorkflowFeatureDisabledStartupTest`
      （`:76-84`）只覆盖平台管理员 404，没有匿名 `/api/v1/workflows` 用例。观察：任务验收要求
      “未认证 401”，当前仅由 `SecurityConfig:167` 的 `requestMatchers("/api/v1/**").authenticated()`
      不变、过滤器显式 pass-through，以及 `AuthFlowTest` 15 例 / `WebErrorMethodNotAllowedTest` 2 例
      对通用匿名 401/CSRF 的回归间接支撑；没有直接断言匿名工作流端点返回 401。期望：T16a 安全矩阵
      补一条匿名 `GET /api/v1/workflows`（以及无 CSRF 的 POST）401 断言，防止后续过滤器顺序调整把
      401 变成 404/403。
    accepted_reason: >-
      行为本身未回归：`WorkflowPlatformTenantFilter` 对未认证请求显式 `filterChain.doFilter`，
      安全链 `requestMatchers("/api/v1/**").authenticated()` 与 `JsonAuthenticationEntryPoint`
      未改；`AuthFlowTest`/`WebErrorMethodNotAllowedTest` 独立复跑 17/17 通过。缺的是针对新工作流
      端点的直接断言，属于测试覆盖缺口而非运行缺陷，不阻塞本次 P0 修复；T16a 安全矩阵补测成本低，
      故 accepted。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:167,198-204；
      apps/api/src/main/java/com/riccon/api/workflow/web/WorkflowPlatformTenantFilter.java:70-90；
      apps/api/src/test/java/com/riccon/api/workflow/web/WorkflowPlatformTenantFilterTest.java:66-70；
      apps/api/src/test/java/com/riccon/api/config/WorkflowFeatureDisabledStartupTest.java:76-84
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "M06 gate 前（架构评审）"
    title: "WorkflowFeatureAuditWriterAdapter 位于 common.service 且依赖 config 端口，属反向包依赖；当前最小修复可接受"
    summary: >-
      `apps/api/src/main/java/com/riccon/api/common/service/WorkflowFeatureAuditWriterAdapter.java:5`
      导入 `com.riccon.api.config.WorkflowFeatureAuditWriter`，即 service 层依赖 config 层；同时
      `common.service` 是本次新增的技术层包，而项目编码规范优先按 feature 纵向分包
      （`docs/CODING-STANDARDS.md:55-58`）。观察：本处是 service→config，不是 config→service；
      `mvcLayeringIsOneWay` 只约束 controller/service/repository/persistence 四层之间的访问方向，
      因此规则 12/12 通过，运行时也无循环依赖。期望：M06 gate 或架构评审明确该端口/适配器的归属——保留为跨切面启动观测
      适配器，或后续收敛到 `workflow.service`；避免 `common.service` 成为新的技术层大包。
    accepted_reason: >-
      本次目标是消除 T01 引入的 config→service/config→controller P0，最小改动必须把委托
      `AuditService` 的 Bean 移出 config；若把端口移到 `workflow.service` 而启动观测仍留在 config，
      会重新引入 config→service 违例；把启动观测一并搬走则超出任务范围。当前实现无规则违例、无行为
      风险，Javadoc 已记录放置理由（`:9-18`），故作为有界设计债 accepted，M06 gate 前由架构评审决定
      是否收敛。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/common/service/WorkflowFeatureAuditWriterAdapter.java:5,9-20；
      apps/api/src/main/java/com/riccon/api/config/WorkflowFeatureStartupAuditor.java:82；
      platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java:179-200；
      docs/CODING-STANDARDS.md:55-58
reviewed_evidence:
  - EV-1023
  - EV-1024
  - EV-1025
evidence:
  - EV-1022
  - EV-1023
  - EV-1024
  - EV-1025
created_at: "2026-11-25T04:56:00+08:00"
---

# REV-TASK-0240-001：M06 T01 ArchUnit 分层修复独立评审

## 结论

**Pass（带 2 个 accepted P3 residual）**。T01 引入的两个 P0 已真实消除：`AppConfig` 不再构造委托
`AuditService` 的 Bean，`SecurityConfig` 不再 import controller 层 `TenantContextFilter`；ArchUnit
`ModuleBoundaryTest.mvcLayeringIsOneWay` 在未修改规则、无 suppression/排除的情况下独立复跑 12/12。
安全过滤器顺序仍为 tenant(701) → workflow(702) → origin(1099)，平台管理员 `/api/v1/workflows`
404（非 403）由真实安全链集成测试证明；J1/J2 的 401/CSRF 回归 17/17 通过。审计写入适配器直接委托
`AuditService`，保留 `@Transactional`/fail-closed 语义；启动审计失败只记录 ERROR，不阻断启动。
变更严格限于 3 个生产文件，未触碰 contracts/PRODUCT/AGENTS/V15/J2。

这不是 M06 模块评审、Product 预验收或用户产品验收；未运行 gate check、全量 E2E/perf/coverage。

## 评审范围与提交

| 项          | 值                                                                                                                  |
| ----------- | ------------------------------------------------------------------------------------------------------------------- |
| baseline    | `50c6e89bedf2360f9a0aafc4180a9d613f4c48f9`（T02 完成）                                                              |
| 代码提交    | `8d0252f159e3e7211c6af20f32f73b9d43ed8c09`（fix(m06) ArchUnit 分层修复）                                            |
| 证据/元数据 | `bd59bca`（EV-1023/1024/1025 录制）、`c1f5d6e`（证据文件与任务卡转 review）                                         |
| 评审时 HEAD | `5fd4b6d`；`git diff --name-only 8d0252f..HEAD` 仅 `.riccon/` 文档，源码等同 `8d0252f`                              |
| 设计依据    | ADR-0024 §4.8/§6.1、OPS-M06-019/024、`docs/CODING-STANDARDS.md` 分层规则、任务卡 TASK-0240                          |
| 变更面      | `config/AppConfig.java`、`config/SecurityConfig.java`、新增 `common/service/WorkflowFeatureAuditWriterAdapter.java` |

`git diff --name-only 50c6e89..8d0252f` 只有上述 3 个文件；`git diff --check 50c6e89..8d0252f`
exit 0。`platform/platform-architecture-tests`、`contracts/`、`docs/PRODUCT.md`、`AGENTS.md`、
`apps/api/src/main/resources/db/migration` 在该区间无 diff。

## 1. ArchUnit 分层修复

### 1.1 规则未被修改

- `ModuleBoundaryTest.mvcLayeringIsOneWay`（`ModuleBoundaryTest.java:179-200`）仍是
  `consideringAllDependencies + withOptionalLayers(true)`，Service 层
  `mayOnlyBeAccessedByLayers("Controller")`。
- `git diff 50c6e89..8d0252f -- platform/platform-architecture-tests` 为 0 行；
  `platform-architecture-tests/src/test` 中未发现 `@ArchIgnore`、`@SuppressWarnings`、
  `@Disabled` 或新增排除。**未通过放宽规则/抑制告警绕过**。

### 1.2 两个 P0 的实际消除

| P0                                                                  | 修复                                                                                                                   | 证据                                                                                 |
| ------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `AppConfig.workflowFeatureAuditWriter(AuditService)` config→service | 删除该 `@Bean` 与 `AuditService` import；委托实现迁到 `common.service.WorkflowFeatureAuditWriterAdapter`（`@Service`） | `AppConfig.java` 当前无 `AuditService`/`WorkflowFeatureAuditWriter`；diff 删除 15 行 |
| `SecurityConfig` 引用 `TenantContextFilter.class` config→controller | 删除 controller import；锚点改为 `tenantContextFilter.getClass()`，复用上一行已注册的同一 Filter 运行时类型            | `SecurityConfig.java:1-40` 无 controller import；`:198-204`                          |

`SecurityConfig` 仍通过 `@Qualifier("tenantContextFilter") Filter` 注入过滤器实例，这是按接口注入，
不再产生 config→controller 的编译依赖；`getClass()` 只返回 `Class<? extends Filter>`，不引用
`TenantContextFilter` 符号。

### 1.3 独立复跑

```text
./mvnw -q -pl apps/api -am -DskipTests -Djacoco.skip=true install
&& ./mvnw -q -pl platform/platform-architecture-tests test
```

结果：exit 0；`ModuleBoundaryTest` surefire
`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`。与 EV-1023 命令一致；EV-1023 的 `-q` 日志只有
446 bytes（仅 PluginLoader 一行），不含 12/12 计数，本次独立复跑的 surefire 报告补齐了该自证缺口。

## 2. 安全过滤器链与 404/401

### 2.1 顺序仍为 tenant(701) → workflow(702) → origin(1099)

按 Spring Security 7.1.2 的 `FilterOrderRegistration` / `HttpSecurity#addFilterAtOffsetOf` 核对：

- `SecurityContextHolderFilter` 注册顺序 700；
- `addFilterAfter(tenantContextFilter, SecurityContextHolderFilter.class)` → tenant 701；
- `addFilterAfter(workflowPlatformTenantFilter, tenantContextFilter.getClass())` → workflow 702；
- `CsrfFilter` 注册顺序 1100，`addFilterBefore(originCheckFilter, CsrfFilter.class)` → origin 1099。

`HttpSecurity.addFilterAtOffsetOf` 先 `filterOrders.getOrder(afterFilter)` 再 `put(filter.getClass(), order)`；
上一行已把 `tenantContextFilter` 的运行时类注册进同一 `HttpSecurity` 的顺序表，因此
`tenantContextFilter.getClass()` 锚点能命中 701，workflow 落在 702。该写法在运行时等价于原来的
`TenantContextFilter.class` 锚点，但不再形成 config→controller 编译依赖。本次实现与 ADR-0024 §6.1
“认证之后、MVC 权限拦截器之前”一致。

### 2.2 平台管理员 404（非 403）

`WorkflowFeatureDisabledStartupTest#platformAdminGets404OnWorkflowEndpoint`
（`WorkflowFeatureDisabledStartupTest.java:76-84`）用真实安全链登录平台管理员后请求
`GET /api/v1/workflows`，断言 404 + `RICCON-TENANT-404`；独立复跑通过。说明 workflow 过滤器在
`PermissionInterceptor` 之前短路，没有先返回 403。

### 2.3 未认证 401 与 J1/J2 CSRF 回归

- `WorkflowPlatformTenantFilterTest#unauthenticatedRequestsPassThroughFor401`
  （`:66-70`）证明过滤器不会把未认证请求误判为 404，而是交给安全链；实际 401 由未改动的
  `SecurityConfig:167` `requestMatchers("/api/v1/**").authenticated()` 与
  `JsonAuthenticationEntryPoint` 提供。
- 独立复跑 `AuthFlowTest,WebErrorMethodNotAllowedTest`：exit 0，
  `AuthFlowTest` 15/15、`WebErrorMethodNotAllowedTest` 2/2，0 failures/0 errors/0 skipped。
  `AuthFlowTest` 覆盖登录 CSRF 缺失 403、登录后 token 轮换、跨源拒绝、401/403 优先级等；
  `WebErrorMethodNotAllowedTest` 覆盖匿名 401、匿名写请求 401、员工 403。
- 未直接对匿名 `/api/v1/workflows` 发集成请求，形成 F-001 的 P3 测试覆盖 residual；运行行为本身
  未发现回归。

## 3. 审计写入适配器与启动观测

### 3.1 委托与事务/fail-closed 语义

`WorkflowFeatureAuditWriterAdapter`（`common/service/WorkflowFeatureAuditWriterAdapter.java:19-42`）
实现 `WorkflowFeatureAuditWriter`，构造函数注入 `AuditService`，`append` 直接调用
`auditService.append(event)`，不捕获、不转换异常。`AuditService.append` 仍是
`@Transactional`（`audit/service/AuditService.java:35-38`），因此：

- 调用方已有事务时加入同一事务，否则新开事务；
- 持久化异常继续向上抛，保持 fail-closed；
- 与原 `AppConfig` 中 `auditService::append` 的 lambda Bean 语义等价。

`WorkflowFeatureStartupAuditor` 在 `ApplicationRunner.run` 中显式
`TenantContext.runWithTenant("platform", ...)`（`:117-131`），平台租户上下文正确；
`catch (RuntimeException)` 只记录 `RICCON-WORKFLOW-001` ERROR（`:133-139`），不向启动流程抛出，
满足 OPS-M06-024“关闭态审计失败不阻断启动”。

### 3.2 测试覆盖

- `WorkflowFeatureStartupAuditorTest` 6/6：关闭态写审计、开启态不写、审计失败不阻断、instanceId
  回退、details 白名单往返、instanceId/observedAt 缺失 fail-closed。
- `WorkflowFeatureDisabledStartupTest` 2/2：真实 MySQL 上启动写入 `workflow.feature.changed`
  （platform/SYSTEM、details 含 enabled/source/reason/instanceId/observedAt 带 +08:00），健康检查
  200；平台管理员 404。
- 上述 8 个用例包含在 EV-1024 的 22 个用例内，独立复跑通过。

### 3.3 放置评估

`common.service` 是本次新增的技术层包，适配器依赖 `config` 端口，属于 service→config 的反向包
依赖。当前选择可接受：本处方向是 service→config，不是规则禁止的 config→service；
`mvcLayeringIsOneWay` 只约束四层之间的访问方向，规则 12/12 通过；若把端口移到
`workflow.service` 而启动观测留在 config，会重新出现 config→service 违例；把启动观测一并搬走则
超出本次 P0 最小修复范围。Javadoc 已写明放置理由。为避免 `common.service` 成为后续技术层大包，
记 F-002 的 P3 accepted，M06 gate 前由架构评审决定是否收敛到 `workflow.service`。

## 4. 范围、回归与证据

### 4.1 范围

- 生产代码仅 3 个文件：`config/AppConfig.java`、`config/SecurityConfig.java`、
  `common/service/WorkflowFeatureAuditWriterAdapter.java`；均在任务 `write_scope` 内。
- 未改 `contracts/`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`、J2 相关代码；
  `git diff --name-only 50c6e89..8d0252f` 已核对无越界。
- `./mvnw -q spotless:check` 独立复跑 exit 0；`git diff --check 50c6e89..8d0252f` exit 0。
- 未发现新增密钥、日志泄露或中文注释缺失；新增类的方法/Javadoc 为中文。

### 4.2 证据复核

| 证据    | 命令/提交                                                                                             | 结果                                                                                             | 评审结论                                          |
| ------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------- |
| EV-1022 | `./mvnw -q -pl platform/platform-architecture-tests -am test`，commit `8d0252f`                       | exit 1；864 tests，0 failures，23 errors；错误集中在 `Connector*Test` 的 ApplicationContext 加载 | 环境问题，非本任务代码缺陷，详见 4.3；不作为 fail |
| EV-1023 | `install -DskipTests -Djacoco.skip=true` + `-pl platform/platform-architecture-tests test`，`bd59bca` | exit 0；日志 446 bytes 无计数；独立复跑 12/12                                                    | 采信 exit 0；独立复跑补齐 12/12 自证              |
| EV-1024 | 4 个测试类定向 `test`，`bd59bca`                                                                      | exit 0；9+2+6+5 = 22/22，0 fail/error/skip                                                       | 通过；独立复跑 22/22                              |
| EV-1025 | `./mvnw -q spotless:check`，`bd59bca`                                                                 | exit 0                                                                                           | 通过；独立复跑 exit 0                             |

三项证据 JSON 的 `dirty=false`、`output_sha256` 与日志文件实际 sha256 一致（EV-1023
`e065b0f...`、EV-1024 `f1fe95c...`、EV-1025 `98e3e8a...`）。

### 4.3 EV-1022 环境失败确认

EV-1022 的失败不是 ArchUnit 修复引入的代码缺陷：

- 失败类为 `ConnectorCredentialSyncApiTest`、`ConnectorCrudApiTest`、`ConnectorDocumentReadApiTest`、
  `ConnectorDownloadApiTest`、`ConnectorTenantIsolationVerifyTest`，全部是 Connector 集成测试的
  ApplicationContext 加载失败；
- 根因是 `ConnectorProperties.afterPropertiesSet` 拒绝端点 `local-rustfs`：
  `invalid connector endpoint configuration: connector endpoint must not resolve to metadata, link-local or IPv6 loopback: local-rustfs`
  （EV-1022 日志 `:7827,9279`）；即本机/Docker 环境把该
  端点解析到 IPv6 loopback，触发 SSRF 防护，与本次只改 `config`/`common.service` 的三个文件无关；
- 同一工作区改用干净 `install -DskipTests` 预装依赖后，`platform-architecture-tests` 独立通过
  12/12；T02 评审也已在 `8d0252f` 上复跑其定向用例确认无回归。

因此 EV-1022 作为环境失败/负向证据保留，不用于通过结论；M06 模块 gate 需要在干净测试环境重跑
全量 `-am test`，避免同一端点解析问题污染 gate。

## 5. DoD 对账

| DoD 项        | 结论 | 依据                                                                                   |
| ------------- | ---- | -------------------------------------------------------------------------------------- |
| acceptance    | true | 两个 P0 消除；`mvcLayeringIsOneWay` 12/12；404/401/CSRF 与审计启动语义保持；范围未越界 |
| build         | true | `install -DskipTests` 独立 exit 0；ArchUnit 测试模块编译并运行                         |
| tests         | true | ArchUnit 12/12；目标 22/22；J1/J2 安全回归 17/17；均 0 fail/error/skip                 |
| review        | true | 本独立任务评审                                                                         |
| evidence      | true | EV-1023/1024/1025 + 独立复跑；EV-1022 环境失败已解释                                   |
| coverage      | N/A  | 本任务为分层修复，不新增业务分支；模块 gate/T16a 统一 coverage                         |
| e2e           | N/A  | 无 UI/端到端行为变更；模块 gate/T16a 统一 E2E                                          |
| security      | N/A  | 无新端点/权限/迁移；401/CSRF 回归已跑；模块 gate 统一 security                         |
| performance   | N/A  | 无热点路径变更；模块 gate 统一 perf                                                    |
| accessibility | N/A  | 无 UI 变更                                                                             |

## 6. Findings

- **F-001（P3，accepted）**：匿名 `/api/v1/workflows` 的 401 只有 pass-through 单测，缺直接集成断言；
  owner `riccon/builder-b1`，due “M06 gate 前（T16a 安全矩阵）”。
- **F-002（P3，accepted）**：`WorkflowFeatureAuditWriterAdapter` 在 `common.service` 且依赖 `config`
  端口，属反向包依赖；当前最小修复可接受，M06 gate 前架构评审决定是否收敛到 `workflow.service`；
  owner `riccon/builder-b1`，due “M06 gate 前（架构评审）”。

两项均不阻塞本次 P0 修复，均为 accepted 且有 owner/due/accepted_reason；没有 P0/P1 open。

## 7. 未验证与剩余风险

- **未跑全量 `-am test`**：本机仍会因 4.3 的 Connector 端点解析问题变红；本次用干净
  `install -DskipTests` + 独立 ArchUnit/定向测试替代，模块 gate 必须在干净环境重跑。
- **未直接验证匿名工作流端点 401**：见 F-001；当前由过滤器代码、未改安全链和 17 例通用安全回归
  支撑，风险低。
- **未跑模块 gate、全量 E2E、coverage、perf、axe**：不属于本次分层修复验收面；M06 模块 review/
  T16a 负责。
- **适配器放置**：F-002 为设计债，不影响规则/行为；若后续新增更多启动观测适配器，应统一归属，
  避免 `common.service` 膨胀。
- **EV-1023 证据自证力**：其 `-q` 日志不含 12/12 计数；本次独立复跑已补齐，建议后续 ArchUnit
  证据使用 surefire 报告或非 `-q` 摘要，减少复核成本。
