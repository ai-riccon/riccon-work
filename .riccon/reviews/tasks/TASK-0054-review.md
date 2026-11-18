---
review_id: REV-TASK-0054-001
module_id: M12
task_id: TASK-0054
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M12_TASK-0056_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [746cf13]
head_commit: e51504a
evidence: [EV-0363, EV-0364, EV-0365, EV-0366, EV-0367]
reviewed_evidence: [EV-0363, EV-0364, EV-0365, EV-0366, EV-0367]
findings:
  - id: F-001
    title: "EV-0363/EV-0366 使用 -q，日志本身不含 surefire 汇总；579/0 skipped 依赖 target 报告事后汇总，默认模式计数无法从 EV 日志直接复核"
    severity: P3
    status: accepted
    evidence: ".riccon/evidence/TASK-0054/EV-0363-…log 无 Tests run 行；apps/api/target/surefire-reports 与 apps/worker/target/surefire-reports 的 316+263=579/0 skipped 来自 EV-0366 窗口"
  - id: F-002
    title: "基准记录只有“本机，串行”，没有机器规格/并发负载；external 全量 475s 对默认 484s 仅快 1.9%，余量接近噪声"
    severity: P3
    status: accepted
    evidence: ".riccon/evidence/TASK-0054/EV-0365-…log；EV-0363 duration_ms=484167，EV-0366 duration_ms=475116"
  - id: F-003
    title: "热栈 namespace 生命周期不完整：stop 默认保留卷、reset 会重建空库、stop 不检查 namespace 是否仍被别的 agent 使用"
    severity: P3
    status: accepted
    evidence: "scripts/test-services.mjs commandStop/commandReset；status 仍显示 builder-0054 与空的 verifier-0056 namespace 数据库"
  - id: F-004
    title: "worker external 的 schema 校验只比较最大成功版本，不校验迁移 checksum；修改已应用迁移时热栈不会像 API 的 Flyway 那样报错"
    severity: P3
    status: accepted
    evidence: "apps/worker/src/test/java/com/riccon/worker/support/TestSchema.java ensureSchema/currentVersion；项目规范禁止修改已应用迁移，风险受此约束"
created_at: "2026-11-19T03:16:30+08:00"
---

# TASK-0054 独立评审：M12-A 本地热测试栈与基准

## 结论

**verdict: pass**（P0/P1 为 0）。代码提交 `746cf13` 的写范围只有测试、测试 support、`scripts/test-services.mjs` 和 `package.json` scripts；默认与 CI 仍走 Testcontainers，external 必须显式 `RICCON_TEST_SERVICES=external` + namespace，非法值/缺 namespace 立即失败；compose 复用、namespace 隔离、同 namespace 锁和迁移一次性容器都在独立验证中成立；579 后端测试与 212 前端测试与任务声明一致，没有跳过或删断言；基准原始值、中位数和 startup 定义可复核，Hitl 42.89→7.13s（83.4%）、Auth 20.99→7.77s（63.0%），external 全量 475s 不慢于默认 484s。四个 P3 为证据/运维余量，written reason 接受，不阻塞。

**M12-A（TASK-0054）可由 Lead 标记 done**（本评审不改任务状态）。**M12-B（TASK-0055）可以启动**。M12 模块 gate 仍必须在最终提交上独立跑一次合同 `commands.test` + `commands.coverage` + E2E/a11y/security，不能拿本任务的 EV-0363/0366 代替模块级全量证据。

## 独立验证记录

评审时工作区干净，HEAD `e51504a`（`746cf13` 是 HEAD 祖先，`e51504a` 只记录 EV/状态）。

| 检查/命令                                                                                                             | 退出码 | 结果                                                                                                              |
| --------------------------------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------- |
| `git status --short` / `git rev-parse --short HEAD`                                                                   | 0      | 空输出；HEAD `e51504a`                                                                                            |
| `git show --stat 746cf13` / `name-status`                                                                             | 0      | 11 个文件，1454 insertions / 49 deletions；只有 `apps/*/src/test/**`、`scripts/test-services.mjs`、`package.json` |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=AuthFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`                    | 0      | 默认模式通过                                                                                                      |
| `./mvnw -q -T1C -pl apps/worker -am -Dtest=HitlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`          | 0      | 默认模式通过；日志出现 Testcontainers MySQL 8.4.13、OpenSearch 3.9.0、RustFS 1.0.6 创建/启动记录                  |
| `node scripts/test-services.mjs status`                                                                               | 0      | compose 三个服务 healthy；显示 namespace 数据库与索引                                                             |
| `node scripts/test-services.mjs prepare --namespace verifier-0056`                                                    | 0      | 创建 `riccon_test_verifier_0056_api                                                                               | worker` |
| `RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-0056 ./mvnw … -Dtest=AuthFlowTest test`        | 0      | 通过；`Creating container for image` 计数 0，未回落到 Testcontainers                                              |
| `RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-0056 ./mvnw … -Dtest=HitlIntegrationTest test` | 0      | 通过；Testcontainers 计数 0；worker 热库 Flyway 版本 9、39 表                                                     |
| 手持有 `GET_LOCK('riccon_test_verifier_0056_api',0)` 后重跑 external `AuthFlowTest`                                   | 1      | `IllegalStateException: … already in use by another test JVM`；KILL 连接后两个 namespace 锁均为 NULL              |
| `RICCON_TEST_SERVICES=bogus … -Dtest=AuthFlowTest test`                                                               | 1      | `unsupported RICCON_TEST_SERVICES=bogus`；未回落到容器                                                            |
| `RICCON_TEST_SERVICES=external`（无 namespace）`… test`                                                               | 1      | `RICCON_TEST_SERVICES_NAMESPACE is required …`                                                                    |
| `node scripts/test-services.mjs reset --namespace verifier-0056`                                                      | 0      | 清理并重建 namespace；锁保持 NULL                                                                                 |
| `./mvnw -q spotless:check`                                                                                            | 0      | 通过                                                                                                              |
| `riccon contract validate --strict --project .`                                                                       | 0      | 0 errors, 0 warnings                                                                                              |
| `riccon state validate --project .`                                                                                   | 0      | 0 errors, 1 warning（M11 planned 但已有 done task；brief 允许）                                                   |
| 外部 surefire XML 汇总（EV-0366 窗口）                                                                                | —      | API 316 + worker 263 = **579 tests / 0 failures / 0 errors / 0 skipped**                                          |
| 测试源码 `@Test`/`@ParameterizedTest` 计数                                                                            | —      | **579**；无 `@Disabled`/`assumeTrue`                                                                              |
| EV 日志 sha256（EV-0363/0365/0366）                                                                                   | —      | 与 EV JSON 的 `output_sha256` 完全一致                                                                            |
| EV-0364 原始日志                                                                                                      | 0      | 前端 29 files / **212 tests** passed                                                                              |

## 默认路径与 CI

- `ApiIntegrationTestSupport` 的 static block：`ExternalTestServices.enabled()` 为 false 时执行 `MYSQL.start()`；worker 基类同理走 `startContainers()` + `TestSchema.applyMigrations` + `createFlywayHistory`。未设置 `RICCON_TEST_SERVICES` 的默认行为与改动前一致。
- 默认定向测试实际启动了 Testcontainers 三个容器；不是靠环境“碰巧连上 compose”。
- 非法值（`bogus`）和缺 namespace 都在 static init 阶段抛 `IllegalStateException`（JUnit 报 initialization error），不会静默回落。`EXTERNAL` 大小写不敏感是显式设计，不算非法值。
- 提交未改 `.github/workflows/ci.yml`；CI 中 `./mvnw -q -T1C verify` 之前虽然启动 compose 测试栈用于迁移冒烟，但没有设置 `RICCON_TEST_SERVICES`，所以测试仍用 Testcontainers；两者端口不冲突，行为不变。

## external 复用与隔离

- **连接参数**：Java 侧默认值与 `deploy/compose/docker-compose.test.yml` 一致（MySQL 13306、OpenSearch 19200、RustFS 19000）；`RICCON_TEST_MYSQL_HOST/PORT`、`RICCON_TEST_OPENSEARCH_URL`、`RICCON_TEST_S3_ENDPOINT`、`RICCON_DB_PASSWORD`、`RICCON_S3_ACCESS_KEY`、`RICCON_S3_SECRET_KEY` 可覆盖。脚本侧的 `settings` 与 compose 插值同源。
- **隔离**：数据库为 `riccon_test_<ns>_api|worker`，S3 桶为 `riccon-test-<ns>-api|worker`，OpenSearch 索引前缀为 `riccon-kb-<ns>-worker`；api/worker 各自拥有独立 DB/桶，允许同 namespace 下 api+worker 并行，这是刻意设计而非静默共用。
- **锁**：`verifyAndLock()` 以 namespace+模块派生锁名，用 `SELECT GET_LOCK(name, 0)` 抢占并保持连接；独立验证中手工持锁后第二个 JVM 立即失败，说明不是“仅告警”。同一 namespace 的第二个同模块 JVM 不能并行；不同 namespace 使用不同 DB/桶/前缀，静态上可并行（未额外做双 namespace 并发压测）。
- **清理**：`reset` 会先检查 `IS_USED_LOCK`，有活跃 JVM 时拒绝；验证后已执行 reset，两个锁均为 NULL。`purgeSearchIndices` 只删除 `riccon-kb-<ns>-worker-` 前缀索引。

## 迁移与 schema

- `V9MigrationTest` 自身声明独立 `MySQLContainer`，不读取 `ExternalTestServices`，因此在 external 模式下仍启动一次性 MySQL；EV-0366 日志中 api/worker 各出现 1 个 `mysql:8.4.13` 和 `ryuk` 创建记录（共 4 个），与两个迁移/schema 测试对应，说明热栈没有承载迁移测试。
- `SchemaVersionCheckerTest` 默认模式复用 `WorkerIntegrationTestBase.sharedMysql()`（不增加启动次数）；external 模式在 `resolveMysql()` 中启动自己的 MySQLContainer，不连接 `riccon_test_<ns>_worker`。
- worker external 的 `TestSchema.ensureSchema`：无 `flyway_schema_history` 时执行权威迁移并写入版本；有历史时最大成功版本必须等于 `SchemaVersionChecker.EXPECTED_SCHEMA_VERSION`，否则失败并提示 reset。独立验证的 worker 热库为版本 9、39 表。
- API external 的 `verifySchemaNotAhead` 在静态初始化时检查 `flyway_schema_history` 最大版本是否高于代码内最新迁移；高于则失败，低于/缺失则交给 Flyway 前向迁移。生产 worker 的 `SchemaVersionChecker` 对版本缺失、落后、超前均 fail-fast（不是只告警），`SchemaVersionCheckerTest` 覆盖四种情况。
- 迁移/回滚语义仍由原有 `V9MigrationTest` 独立保护；本次改动没有修改迁移文件。

## 测试完整性

- 外部 surefire XML 汇总 579 / 0 skipped / 0 failures / 0 errors；测试源码注解计数同为 579；没有 `@Disabled`、`assumeTrue` 或条件跳过。
- 提交没有增删 `@Test`/`@ParameterizedTest`（`git show --unified=0` 的 +/- 行中无测试注解）；`SchemaVersionCheckerTest` 的五个测试和断言保留，只是默认模式改为复用共享容器、external 模式自建容器。
- `ApiApplicationTests` 把硬编码 `table_schema = 'riccon'` 改为 `DATABASE()`，在默认库（`riccon`）和 external 库（`riccon_test_<ns>_api`）语义等价，没有弱化 39 表/2 触发器/9 迁移断言；`S3FileContentReaderTest` 只是改用 `storageAccessKey()/storageSecretKey()`，断言未变。
- EV-0364 前端 212 tests passed；本次未改前端，未重跑前端全量。覆盖率门槛/配置未改（提交没有碰 pom、jacoco 或 vitest 配置）；本任务不是模块 gate，未用覆盖率 EV 替代。

## 基准

EV-0365 原始值与中位数复核：

| 用例        | 模式     | 各次 startup (s)      | 中位数 | test 中位数 | class 中位数 | wall 中位数 |
| ----------- | -------- | --------------------- | ------ | ----------- | ------------ | ----------- |
| worker-hitl | default  | 42.89 / 42.47 / 43.10 | 42.89  | 18.32       | 61.27        | 69.69       |
| worker-hitl | external | 7.71 / 7.13 / 7.00    | 7.13   | 17.49       | 24.63        | 31.79       |
| api-auth    | default  | 20.37 / 21.25 / 20.99 | 20.99  | 13.92       | 35.17        | 46.65       |
| api-auth    | external | 7.86 / 7.77 / 7.70    | 7.77   | 14.59       | 22.45        | 33.18       |

- 中位数计算正确（奇数值取中间；worker-hitl default 的 42.89 与 api-auth external 的 7.77 均为中间值）。
- startup 定义在两种模式一致：`surefire 类耗时 - XML 各 testcase 时间之和`，即容器/迁移/Spring 上下文/类级 setup；不是把业务测试时间混入启动时间。
- 降幅：worker-hitl `(42.89-7.13)/42.89 = 83.4%`，api-auth `(20.99-7.77)/20.99 = 63.0%`，均 ≥40%。
- 全量：EV-0363 484.167s，EV-0366 475.116s，external 不慢于默认（快 9.05s / 1.9%）。原始三次值相对稳定，未发现 retry 或吞错。
- 该结果只代表本机串行窗口；见 F-002。

## 边界与状态对账

- `git show --name-status 746cf13` 仅：`ApiApplicationTests`、`ApiIntegrationTestSupport`、`ApiStorageIntegrationTestSupport`、api `ExternalTestServices`（新增）、`WorkerIntegrationTestBase`、worker `S3FileContentReaderTest`、`SchemaVersionCheckerTest`、worker `ExternalTestServices`（新增）、worker `TestSchema`、`package.json`、`scripts/test-services.mjs`。
- 无生产源码、迁移、`contracts/`、`deploy/`、Helm、CI、`.github` 改动；`package.json` 只新增 6 个 test-services scripts。
- EV-0363..0367 均为 `dirty=false`、`exit_code=0`、`commit=746cf13…`；EV-0363/0365/0366 日志 hash 与 JSON 一致。
- 工作区在评审前后均为空；`state validate` 只有 M11 legacy warning，符合 brief。
- TASK-0054 当前 `status=review`，owner `riccon/builder`；本评审不修改任务状态。

## Findings

- **F-001（P3，accepted）**：`-q` 让 EV-0363/EV-0366 日志缺少 surefire 汇总；579/0 skipped 是从 `target/surefire-reports`（EV-0366 窗口）和源码注解计数交叉确认的，默认 579 无法仅从 EV-0363 日志复核。承接：M12 模块 gate 取证时显式汇总 surefire XML（或保留 `surefire-reports` 归档），避免重跑覆盖报告后只剩自报数字。
- **F-002（P3，accepted）**：benchmark 记录“本机，串行”，未记录机器规格/并发进程；external 全量仅快 1.9%，属于薄余量。承接：M12 模块评审若引用性能结论，在同一主机重新测量并记录环境；startup 降幅本身（63%/83%）足以支撑 AC。
- **F-003（P3，accepted）**：`stop` 默认保留卷、`reset` 重建空 namespace 库、`stop` 不检查是否有其他 agent 的 namespace 锁；验证后 `verifier-0056` 空库与 `builder-0054` 数据仍在。承接：M12-B 的 RUNBOOK 写明单 agent/停栈协调与 `stop --volumes` 清理；可选增加 `--purge`。
- **F-004（P3，accepted）**：worker 热库只有版本校验，没有 checksum；若有人违反规范修改已应用迁移，worker 外部模式不会像 API Flyway 那样校验 checksum。承接：M12 模块 gate 继续用迁移测试的一次性容器做权威校验；RUNBOOK 强调迁移不可改。

## 未验证 / 剩余风险

- 未重复 EV-0363 的后端全量 verify（按 brief 与合同“同一份代码全量只跑一次”）；默认/external 全量时间取 EV 记录，未在本会话重跑 738s 基准。
- 未重跑前端 212 测试（EV-0364 已在干净提交上通过，且本提交不碰前端）。
- 未做两个 namespace 的并发动态压测；不同 namespace 的隔离由数据库名/桶名/索引前缀和锁名推导，静态成立，已用单 namespace + 手工持锁验证互斥。
- 未在 CI 环境重跑；CI 文件未改，且 CI 不注入 `RICCON_TEST_SERVICES`。
- 未验证在其他机器上的基准数值；见 F-002。

## 下一步

1. Lead 可将 TASK-0054 标记 done；本评审不代替 Lead 的状态变更。
2. TASK-0055（M12-B：test-affected、RUNBOOK、CODING-STANDARDS）可以启动；建议在其 RUNBOOK 中承接 F-003，并在 M12 模块 gate 取证中承接 F-001/F-002。
3. M12 模块 gate 必须在最终提交上独立跑合同 test/coverage/E2E/a11y/security；EV-0363/0366 只是任务级证据，不能替代模块级全量。
