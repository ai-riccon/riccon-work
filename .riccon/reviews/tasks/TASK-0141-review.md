---
review_id: REV-TASK-0141-001
module_id: M20
task_id: TASK-0141
reviewer:
  agent: riccon/verifier
  session: ses_dfe2e1751ffeQTKiiB4pAJZTKJ
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 5f09a6bb3e310b7cb457628ed870e3fbbd2426d6
  - 76ef7669aedff0a5071217639576eac14773d550
head_commit: 76ef7669aedff0a5071217639576eac14773d550
evidence:
  - EV-0755
  - EV-0756
reviewed_evidence:
  - EV-0755
  - EV-0756
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: "riccon/verifier（M20 模块 gate 复核）"
    due: "M20 模块 gate 前"
    title: "真实 3s 证据使用同提示受控慢查询，未让生产聚合 SQL 本身超时"
    summary: >-
      CostQueryTimeoutIntegrationTest 在 Testcontainers MySQL 8.4.13 上用
      `SELECT /*+ MAX_EXECUTION_TIME(3000) */ SLEEP(1) ...` 证明 MySQL 以 3024 中止、
      JDBC 翻译为 DataAccessException，并经真实 CostQueryService/PlatformCostService 与 HTTP
      返回 503；但测试只替换 totals，buckets/bucketCount 未分别制造超时，生产聚合 SQL 也没有
      在生产数据量下实际超时。测试 Javadoc 已明确写出该覆盖边界。核心 AC 的可观察行为
      “真实 MySQL 3 秒中止 → 503、不返回部分结果”已满足，剩余部分是对查询形状与数据量的推演。
    accepted_reason: >-
      生产 SQL 的三个查询入口都带同一 `MAX_EXECUTION_TIME(3000)` 提示
      （CostJdbc.TOTALS_SQL/桶 SQL、PlatformCostJdbc.totals/bucket SQL），MySQL 8.4 对提示的
      实际中止已由真实实例证明，服务层 catch 同时包住 totals/bucketCount/buckets 三个仓储调用，
      异常到 503 的映射也由真实 HTTP 证明。在测试数据量下强行让生产聚合超过 3s 需要生产级数据，
      属 M09/性能观测而非单测可承担；风险有界且已被注释披露，故接受为 P3 残余，由 M20 模块
      gate 复核，不阻塞本任务。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/cost/CostQueryTimeoutIntegrationTest.java:35-79、84-132、166-195；
      apps/api/src/main/java/com/riccon/api/cost/persistence/CostJdbc.java:37、53-79；
      apps/api/src/main/java/com/riccon/api/admin/cost/persistence/PlatformCostJdbc.java:30、68-95；
      .riccon/evidence/TASK-0141/EV-0755-2026-11-22T13-44-26-920+08-00-mvnw-t1c-pl-apps-api-am-test-dtest-costapitest-c.log:268-270、439、618、787。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-core（TASK-0142）+ riccon/architect"
    due: "TASK-0142 / M20 gate 前"
    title: "ADR-0022 §2.4 仍写 normalizeTenantFilter 把空白归一为未提供"
    summary: >-
      docs/adr/ADR-0022-m04-quota-and-cost.md:248 仍写“当前 PlatformCostService.normalizeTenantFilter
      仍把空白归一为‘未提供’，实现修正与边界测试归 M20/TASK-0141”，而 5f09a6b 已改为只有
      `null` 才返回未提供，空串/纯空白抛 400。TASK-0155 只修订 §2.1/§2.6，未覆盖该句；文档与
      实现不一致，但实现与 OpenAPI minLength 1 及 ADR 目标一致，无用户可见行为或安全影响。
    accepted_reason: >-
      TASK-0142 的 write_scope 已包含 docs/adr/ADR-0022-m04-quota-and-cost.md，且其验收要求修订
      §2.4 的平台成本例外描述；可在同一次编辑中删除该过时句并写明 TASK-0141 已落地。不改变
      AC、错误码或用户可见结果，故接受为 P3，due 到 TASK-0142/M20 gate。
    evidence: >-
      docs/adr/ADR-0022-m04-quota-and-cost.md:248；
      apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java:127-139；
      .riccon/tasks/TASK-0142.json:8-20；
      git show e1f9137 -- docs/adr/ADR-0022-m04-quota-and-cost.md（仅 §2.1/§2.6）。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-cost"
    due: "M20 gate 前 / 后续证据整理"
    title: "EV-0755 日志只有类级耗时，未逐用例打印 3 个超时用例耗时"
    summary: >-
      EV-0755 日志含 `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` 和类级 11.75s，
      但 Surefire 控制台没有逐用例耗时；CostQueryTimeoutIntegrationTest 三个用例的
      3.755s/3.700s/3.012s 只能从独立重跑的 surefire XML 看到（该 XML 未随 EV 提交；EV-0756
      全量运行也会产生同类 XML，但本次无法回溯其历史文件）。这是证据粒度问题，不是假绿：
      类级耗时、3024 异常堆栈和断言区间共同证明超时行为。
    accepted_reason: >-
      任务验收只要求受影响测试通过并绑定证据；17/0/0/0、503/3024 与 3 秒级耗时已由本次独立
      重跑复现。缺逐用例控制台耗时不影响结论；建议后续 EV 附 surefire XML 或保留逐用例输出，
      由 builder 在 M20 gate 前的证据整理中处理。
    evidence: >-
      .riccon/evidence/TASK-0141/EV-0755-2026-11-22T13-44-26-920+08-00-mvnw-t1c-pl-apps-api-am-test-dtest-costapitest-c.log:222、962、1243、1263、1267、1277；
      独立重跑 apps/api/target/surefire-reports/TEST-com.riccon.api.cost.CostQueryTimeoutIntegrationTest.xml。
notes: "任务级独立评审；TASK-0108 F-002/F-005/F-010 已在本任务闭环，剩余 3 条 P3 accepted 均不改变用户可见行为。不参与 M20 模块 gate。"
created_at: "2026-11-22T14:00:37+08:00"
---

# REV-TASK-0141-001：TASK-0141 成本查询 3s 保护、平台五项计数与参数边界独立评审

本文件是任务级独立评审，不参与 M20 模块 gate；模块级 AC、全量合同命令、E2E、覆盖率与安全/性能仍由 M20 模块 review 承担。

## 结论

**pass（任务级）**。提交 `5f09a6b` 在 `TASK-0141.write_scope` 内完成了：

- 平台 `tenantId` 空串/纯空白/超长 → 400 `RICCON-COST-400`，不再归一为未提供；
- Testcontainers MySQL 8.4.13 上的真实 3 秒中止、`3024`/`DataAccessException` 与真实 HTTP 503；
- 平台五类互斥计数、金额币种与租户过滤测试；
- `userId`/`modelId` 非 UUID、平台 `page=201/200` 边界测试。

未发现 P0/P1/P2；存在 3 条 P3 accepted 残余（生产聚合 SQL 的数据量推演、ADR-0022 §2.4 过时句、EV-0755 逐用例耗时粒度），均不改变任务验收结论。原 REV-TASK-0108 的 F-002/F-005/F-010 已闭环。

## 评审对象与范围

- 基线 `7f06dd6`，代码提交 `5f09a6b`，证据/状态提交 `76ef766`；`end_commit=5f09a6b`，评审 HEAD `76ef766`。
- `git diff --name-status 7f06dd6..5f09a6b` 共 6 个文件：
  - `apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java`（M）
  - `apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java`（M）
  - `apps/api/src/test/java/com/riccon/api/cost/CostApiTest.java`（M）
  - `apps/api/src/test/java/com/riccon/api/cost/CostQueryTimeoutIntegrationTest.java`（A）
  - `.riccon/state.json`、`.riccon/tasks/TASK-0141.json`（M）
- 全部匹配 `write_scope`（`apps/api/src/test/java/com/riccon/api/cost/**`、`apps/api/src/test/java/com/riccon/api/admin/cost/**`、`apps/api/src/main/java/com/riccon/api/admin/cost/**`、`.riccon/**`）。
- **未改** `docs/adr/`、`docs/PRODUCT.md`、`docs/ACCEPTANCE.md`、`TenantContextFilter`、`apps/api/src/main/resources/db/migration/`、`contracts/`、历史 evidence/review；符合任务边界。
- `git diff --name-status 5f09a6b..76ef766` 仅 6 个文件：EV-0755 日志/JSON、EV-0756 日志/JSON、`.riccon/state.json`、`.riccon/tasks/TASK-0141.json`；未夹带其他 `.riccon` 或生产文件。
- `git show --check 5f09a6b` 与 `git diff --check 7f06dd6..5f09a6b` 无空白错误；`./mvnw -q spotless:check` exit 0。

## 证据核查

| 项                | 观察                                                                                                                                                                                                      |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-0755 JSON      | `exit_code=0`、`manual=false`、`dirty=false`、`commit=5f09a6b`、`task_id=TASK-0141`、`agent=riccon/builder-cost`、`duration_ms=63774`                                                                     |
| EV-0755 日志头    | 第 1 行命令与 JSON 完全一致：`./mvnw -T1C -pl apps/api -am test -Dtest='CostApiTest,CostAdminApiTest,CostUnavailableMappingTest,CostQueryTimeoutIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| EV-0755 结果      | `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`（CostApiTest 5、CostQueryTimeoutIntegrationTest 3、CostUnavailableMappingTest 3、CostAdminApiTest 6）；`BUILD SUCCESS`                                |
| EV-0755 哈希/大小 | SHA-256 `7ee15e7b...bff684` 与 JSON `output_sha256` 一致；`output_bytes=177453` 与 `wc -c` 一致                                                                                                           |
| EV-0756 JSON      | `exit_code=0`、`manual=false`、`dirty=false`、`commit=5f09a6b`、`task_id=TASK-0141`、`agent=riccon/builder-cost`、`duration_ms=461695`                                                                    |
| EV-0756 日志头    | 第 1 行命令与 JSON 完全一致：`./mvnw -T1C -pl apps/api -am test`                                                                                                                                          |
| EV-0756 结果      | `Tests run: 649, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；日志末尾有历史性 `[ERROR] Surefire is going to kill self fork JVM`，不影响 649/0/0/0 与构建结果                                    |
| EV-0756 哈希/大小 | SHA-256 `34df7c75...aac2e6e` 与 JSON `output_sha256` 一致；`output_bytes=1406128` 与 `wc -c` 一致                                                                                                         |

EV-0755 的控制台没有逐用例耗时（见 F-003）；本次独立重跑的 surefire XML 显示三个超时用例分别为 3.755s、3.700s、3.012s，与日志类级 11.75s 相符。

## 逐项核查

### 1. F-002：真实 MySQL 3 秒中止 → 503，不返回部分结果

- **真实数据库**：`CostQueryTimeoutIntegrationTest` 继承 `ApiIntegrationTestSupport`，默认启动 `MySQLContainer("mysql:8.4.13")`，与合同 `mysql: 8.4.13` 一致（`ApiIntegrationTestSupport.java:47-55`）；不是 H2 或 mock。
- **真实中止**：直接用例执行 `SELECT /*+ MAX_EXECUTION_TIME(3000) */ SLEEP(1) FROM (...4 行...) probe`，断言抛出 `DataAccessException` 且异常链含 MySQL 错误码 3024，耗时 2.5s–15s；EV-0755 日志第 270/618 行显示 `error code [3024]; Query execution was interrupted, maximum statement execution time exceeded`。
- **真实服务/HTTP**：租户与平台两个 HTTP 用例分别请求 `/api/v1/costs`、`/api/v1/admin/costs`，由真实 `CostQueryService`/`PlatformCostService` 捕获 `DataAccessException` 并抛 `CostUnavailableException`，断言 503、`RICCON-COST-503`、无 `items`/`totals`、响应不含 `MAX_EXECUTION_TIME`/`SLEEP`/`probe`，耗时 2.5s–9s。
- **测试装配隔离**：`@TestConfiguration` 通过 `@Import` 只挂在本测试类，`@Primary` 慢仓储只覆盖 `totals`，其余方法委托生产仓储；该上下文与 `CostApiTest` 等类不同，未污染全量套件（EV-0756 其他成本类均通过）。
- **生产 SQL 提示一致**：`CostJdbc.TIMEOUT_HINT` 与 `PlatformCostJdbc.TIMEOUT_HINT` 均为 `/*+ MAX_EXECUTION_TIME(3000) */`，位置在 `SELECT` 之后；测试使用同一提示文本。
- **诚实边界**：类 Javadoc 明确写“不冒充生产聚合 SQL 本身的执行耗时；生产 SQL 是否超时取决于真实数据量”。
- **判断**：AC“真实 MySQL 证明查询在 3 秒中止并返回 RICCON-COST-503，不返回部分结果”的**可观察行为已满足**。生产聚合 SQL 在真实数据量下的超时未被强制复现，属于有界残余，记为 F-001（P3 accepted），不是 P2/P1。

### 2. F-005：平台五类互斥计数与总量一致性

- **跨租户数据**：`fixture()` 生成唯一租户 A，`createOtherTenant()` 生成唯一租户 B；五条调用分别落在 A（PRICED、USAGE_UNKNOWN、unbucketed）与 B（UNPRICED、currencyMismatch），不是同一租户内的伪跨租户。
- **五类各 1**：`platformTotals_partitionFiveTerminalCategoriesAndFilterByTenant()` 断言 `pricedCalls/unpricedCalls/usageUnknownCalls/currencyMismatchCalls/unbucketedCalls` 均为 1。
- **总量一致性**：五项之和为 5，并与 `terminalInvocationCount(null)`（窗口内 `SUCCEEDED/FAILED/CANCELLED` 行数）相等；租户过滤后五项之和为 2，与 `terminalInvocationCount(other.tenant())` 相等。
- **金额币种**：总额 `0.003000` 只含 CNY PRICED；USD 币种不一致行不计金额，租户 B 桶金额 `0.000000`、`pricedCalls=0`、`unpricedCalls=1`。
- **互斥性**：currencyMismatch 行同时是 `PRICED`，但只计入 mismatch、不计入 priced；unbucketed 行 `cost_day IS NULL`，不计入 usageUnknown；测试数据和断言能暴露重复计数（若重复，五项之和会大于终态行数）。
- **清理与隔离**：`@BeforeEach clearReservedCostWindow()` 删除 2027-02-03 北京时间半开区间 `[2027-02-02T16:00Z, 2027-02-03T16:00Z)` 的全部行，`@AfterEach` 按本测试唯一 `modelId` 精确删除；`grep` 显示 2027-02-03 仅本文件使用，项目无 JUnit/Surefire 并行配置，API 测试串行执行，未发现跨测试污染。
- **残余**：`RICCON_TEST_SERVICES=external` 热栈模式下若并行跑同一套件，全局 DELETE 会竞争；团队规则已要求同一时间只跑一个全量套件，故不作为 finding，写入剩余风险。

### 3. F-010：查询参数边界

- **tenantId 归一**：`PlatformCostService.normalizeTenantFilter` 只有 `null` 返回 `null`（未提供）；`strip()` 后为空抛 `CostInvalidRequestException`；超过 64 抛同类异常；64 字符返回（`PlatformCostService.java:127-139`）。
- **边界测试**：`?tenantId=` 与 `?tenantId=%20%20` 均 400 `RICCON-COST-400`，`"t".repeat(65)` 400；`"t".repeat(64)` 200 且空聚合。Spring 实际绑定路径由通过的真实 HTTP 用例覆盖，不是只做字符串调用。
- **错误 detail**：`CostInvalidRequestException` 的 detail 是常量 `tenantId must not be blank` / `tenantId must not exceed 64 characters`，不回显输入；测试断言 detail 精确包含规则文案。
- **userId/modelId**：`/api/v1/costs/invocations?userId=not-a-uuid` 与 `?modelId=not-a-uuid` 均由 Spring `MethodArgumentTypeMismatchException` 映射为 400 `RICCON-REQUEST-400`；响应无 `tenantId`、无 `items`，body 不含 fixture 租户代码。
- **page**：平台 `page=201` → 400 `RICCON-COST-400`，detail `page must be between 1 and 200`；`page=200` → 200；租户 `/api/v1/costs?page=201` 的既有 `CostApiTest` 断言仍通过（17 tests 中包含该用例）。
- **契约**：`contracts/openapi.yaml` 的平台 `tenantId` 为 `minLength: 1 / maxLength: 64`，三处成本端点使用 `CostPage`（`maximum: 200`）；本次未改契约，与 TASK-0143 基线一致。

### 4. 回归与测试质量

- 新增/修改测试均有实际断言，无 `@Disabled`、`assumeTrue`、`TODO/FIXME` 或空断言；17 个定向用例与 649 个全量 API 用例均 0 skipped。
- 三个超时用例均有 `@Timeout(30)`；直接用例 2.5s–15s、HTTP 用例 2.5s–9s，本次实测 3.0–3.8s，慢 CI 上有约 5s 余量；若提示失效，查询 4 秒结束并抛 `IllegalStateException`，用例会失败而不是误通过。
- `@Primary` 测试配置只在本测试上下文生效，未污染同 context 的其他测试类；`CostAdminApiTest` 的 2027-02-03 窗口与其它测试日期无交集。
- `CostAdminApiTest` 清理按唯一 `modelId`；`@BeforeEach` 只清保留窗口，未删除本套件其他日期的数据。
- 全量 EV-0756 末尾的 `Surefire is going to kill self fork JVM` 在多个历史证据中出现，属既有测试进程/线程收尾问题，不是本任务引入；不影响 BUILD SUCCESS，作为剩余风险记录。

### 5. 文档

- ADR-0022 §2.4:248 仍保留“当前 normalizeTenantFilter 仍把空白归一为未提供，修正归 TASK-0141”的过时句，与 5f09a6b 实现不一致；TASK-0155 只改 §2.1/§2.6，未覆盖。已记 F-002（P3 accepted），由 TASK-0142 在 ADR-0022 同文件编辑时清理。
- 新增生产代码与测试的类/方法 Javadoc 为中文，覆盖参数、返回、异常与边界；未发现方法级注释缺失。

## 独立命令结果

| 命令                                                                                                                                                                         | 结果                                                                                                                        |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `git diff --name-status 7f06dd6..5f09a6b`                                                                                                                                    | 6 个文件，全部在 `write_scope`                                                                                              |
| `git diff --name-status 5f09a6b..76ef766`                                                                                                                                    | 6 个文件：EV-0755/0756 日志/JSON + state/task                                                                               |
| `git show --check 5f09a6b`；`git diff --check 7f06dd6..5f09a6b`                                                                                                              | 无空白错误                                                                                                                  |
| `./mvnw -q spotless:check`                                                                                                                                                   | exit 0                                                                                                                      |
| `./mvnw -T1C -pl apps/api -am test -Dtest='CostApiTest,CostAdminApiTest,CostUnavailableMappingTest,CostQueryTimeoutIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | exit 0；`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；总耗时 1:00；类级 28.14s/11.77s/0.018s/5.489s |
| 上述重跑的 surefire XML                                                                                                                                                      | CostQueryTimeoutIntegrationTest 3 个用例：3.755s、3.700s、3.012s                                                            |
| `shasum -a 256` + `wc -c` 对 EV-0755/EV-0756 日志                                                                                                                            | 两个哈希与字节数均与 JSON 一致                                                                                              |

未按派单要求重跑全量 API（EV-0756 已录）、E2E、coverage 或 `gate check`；这些属于 M20 模块 gate，不用任务级评审替代。

## 未验证

- M20 模块级全量合同 build/lint/typecheck/test/coverage/E2E/a11y/security/perf：未运行；本次只做任务级定向验证。
- 生产聚合 SQL 在生产数据量下真实超过 3 秒的行为：未复现（测试用同提示受控慢查询，见 F-001）。
- EV-0756 的 649 个用例未在本会话重跑；只核对了 JSON、日志头、哈希、大小与类级/总汇总。
- 未在 external compose 热栈模式验证 2027-02-03 全局清理的并发行为；默认 Testcontainers 一次性容器下无并发。
- 未运行 UI/E2E：本任务只改 API 生产逻辑与测试，无前端入口变更。
- 未运行 `riccon gate check --module M20`：派单明确要求不跑；任务评审不参与模块 gate，M20 仍为 `building`。

## 剩余风险

- **生产查询形状**：同提示已在真实 MySQL 证明有效，但生产聚合/联表 SQL 在真实数据量下是否触发 3s 中止仍依赖数据规模；M09 性能观测或 M20 gate 可复核。
- **证据粒度**：EV-0755 未保留逐用例耗时；后续全量重跑会覆盖 surefire XML，历史 EV 只能靠独立重跑补证。
- **外部测试栈并发**：若 `RICCON_TEST_SERVICES=external` 且多个 agent 并行跑同一套件，2027-02-03 的全局 DELETE 会互相影响；团队串行规则是当前缓解。
- **全量收尾**：EV-0756 末尾的 Surefire kill fork 是历史性测试进程问题，建议后续单独治理，避免掩盖未来真正的测试泄漏。
- **文档一致性**：ADR-0022 §2.4 过时句在 TASK-0142 完成前仍会误导读者；不影响实现或 AC。
- **模块 gate**：M20 仍为 `building`，TASK-0140/0142 与模块 review 未完成；本任务 pass 不等于 M20 可关闭。

## 独立性声明

本评审使用独立会话 `ses_dfe2e1751ffeQTKiiB4pAJZTKJ`，直接读取任务卡/模块卡、基线到 end_commit 的 diff、`docs/adr/ADR-0022`、OpenAPI、`CostQueryService`/`PlatformCostService`/`CostJdbc`/`PlatformCostJdbc`、新增测试与 EV-0755/0756 JSON/日志；独立重跑了 17 个定向用例并核对 surefire XML、哈希与字节数。未采信 builder 总结，未修改生产源码、测试、合同、ADR、历史证据或其他 `.riccon` 状态；仅新增本评审文件。

## Gate

任务级评审不参与模块 gate。按派单要求未运行 `riccon gate check --module M20`；M20 仍为 `building`，模块 gate 仍需在 TASK-0140/0142 完成、本任务 done 后，由独立 Verifier 在最终提交上跑全量合同命令并绑定 `dod_evidence`。
