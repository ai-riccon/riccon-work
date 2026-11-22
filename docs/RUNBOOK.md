# 运行手册

## 前置条件

| 工具   | 版本                       | 检查命令                 |
| ------ | -------------------------- | ------------------------ |
| JDK    | 21.0.x                     | `java -version`          |
| Maven  | 3.10.x（由 wrapper 使用）  | `./mvnw -version`        |
| Docker | 29.x + Compose v2          | `docker compose version` |
| k6     | 2.4.x（性能门禁）          | `k6 version`             |
| Trivy  | 0.76.x（安全门禁）         | `trivy --version`        |
| pnpm   | 10.x（前端，TASK-0003 起） | `pnpm --version`         |

M01 尚未建立 `apps/web`，因此合同里带 `pnpm -w ...` 的命令只能执行后端部分；前端部分在 TASK-0003 完成后补齐。

## 安装

```bash
# 后端依赖（首次会下载 Maven 发行版与依赖）
./mvnw -q -T1C -DskipTests dependency:go-offline

# 前端（TASK-0003 建立 package.json 后）
# pnpm install --frozen-lockfile
```

## 测试栈

```bash
# 启动 MySQL 8.4.13、OpenSearch 3.9.0、RustFS（S3 兼容）
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait

# 查看健康状态
docker compose -f deploy/compose/docker-compose.test.yml ps

# 停止并删除测试数据卷
docker compose -f deploy/compose/docker-compose.test.yml down -v
```

端口（仅绑定 `127.0.0.1`）：

| 服务           | 端口                             |
| -------------- | -------------------------------- |
| MySQL          | 13306                            |
| OpenSearch     | 19200（HTTP）、19600（性能分析） |
| RustFS S3      | 19000                            |
| RustFS Console | 19001                            |

测试凭据通过环境变量覆盖：`RICCON_DB_PASSWORD`、`RICCON_DB_ROOT_PASSWORD`、`RICCON_S3_ACCESS_KEY`、`RICCON_S3_SECRET_KEY`。默认值只用于本地测试，禁止用于任何共享环境。

### 热测试栈与受影响测试（M12，可选）

M12 起本地内层循环可显式复用同一套 compose 服务；默认与模块 gate 不启用，CI 后端分片自 M19 P1/P2 起显式启用（见下文「CI 热栈与测试分片」）。启动/停止/重置统一通过脚本操作，不另造服务：

```bash
# 启动、状态、停止（默认保留数据卷）
node scripts/test-services.mjs start
node scripts/test-services.mjs status
node scripts/test-services.mjs stop

# 停止并删除数据卷（会清空 MySQL / OpenSearch / RustFS 的全部测试数据）
node scripts/test-services.mjs stop --volumes

# 为当前 agent 创建独立 namespace：只建空库并授权，首次测试由 Flyway/TestSchema 初始化
node scripts/test-services.mjs prepare --namespace builder-0054

# 显式运行 external 测试
export RICCON_TEST_SERVICES=external
export RICCON_TEST_SERVICES_NAMESPACE=builder-0054
./mvnw -q -T1C verify

# 重建 namespace（丢弃数据库并清理该前缀的 OpenSearch 索引）
node scripts/test-services.mjs reset --namespace builder-0054
```

约束：

- 每个 agent 使用自己的 namespace；数据库、S3 桶和 OpenSearch 索引前缀都按 namespace 派生。同一 namespace 只允许一个测试 JVM，第二个会被 MySQL 命名锁拒绝（`already in use by another test JVM`）；禁止多个 agent 并行共用。
- 启动 compose 与运行测试必须使用同一组凭据环境变量，否则会出现认证失败。
- 迁移、回滚与 schema 版本测试始终使用一次性容器（`V9MigrationTest`/`V10MigrationTest`；`SchemaVersionCheckerTest` 的 external 分支也会自建容器），不得连接热栈。
- 热栈用于本地 L1 内层循环与 CI 后端分片（M19 P1/P2），不能作为任务完成或模块 gate 证据；L2/L3 仍按 `docs/CODING-STANDARDS.md` §10.1 运行合同命令并由 verifier 独立取证。
- 细节与 baseline 见 M12-A：`RICCON_TEST_SERVICES_NAMESPACE` 必填、`scripts/test-services.mjs benchmark` 可用于本机对比，但基准结果同样不是 gate 证据。

受影响测试映射只用于内层循环，输出建议命令而不自动执行：

```bash
pnpm test:affected -- --dry-run              # 工作树相对 HEAD + 未跟踪文件
pnpm test:affected -- --staged --dry-run     # 暂存区变更 + 未跟踪文件
pnpm test:affected -- --base HEAD~1 --dry-run
```

无法从变更文件唯一定位测试时，脚本会打印原因并回退到 API/Worker 模块全量或前端全量；无法识别的路径以非零退出，不会静默返回空建议。该命令不录证据，也不能替代合同 `test`/`coverage`/E2E。

常见故障：

| 现象                                               | 处理                                                                                                  |
| -------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `status` 报连接失败或 compose 未启动               | `node scripts/test-services.mjs start`；确认 Docker Desktop 已运行，端口 13306/19200/19000 未被占用。 |
| 端口 13306、19200、19000 被占用                    | 停止占用进程；这些端口由测试 compose 固定，不要为热栈另改端口或复制一套服务。                         |
| 测试启动报 `external test schema version mismatch` | `node scripts/test-services.mjs reset --namespace <ns>` 后重跑；禁止用 `flyway repair` 掩盖。         |
| 报 `already in use by another test JVM`            | 同一 namespace 已有测试 JVM；结束该进程或改用唯一 namespace，禁止并行共用。                           |
| MySQL 认证失败                                     | 启动 compose 与运行测试使用同一组 `RICCON_DB_PASSWORD`/`RICCON_S3_*` 环境变量。                       |
| 外部库残留数据导致断言异常                         | `reset --namespace <ns>` 重建数据库；S3 对象 key 含 UUID，彻底清空用 `stop --volumes`。               |
| OpenSearch 旧索引干扰                              | `reset --namespace <ns>` 清理 `riccon-kb-<ns>-` 前缀索引；测试基类启动时也会清理当前 namespace 前缀。 |
| 迁移/回滚测试误连热栈                              | 检查是否误设 `RICCON_TEST_SERVICES`；迁移测试必须使用一次性容器。                                     |

### CI 热栈与测试分片（external + namespace + 4-shard + coverage，M19 P1/P2、M22 F-001）

CI 后端由四个 job 组成，替代 M19 P1 之前的单个 `backend` job：

| job                | 作用                                                                                | 失败时的含义                                     |
| ------------------ | ----------------------------------------------------------------------------------- | ------------------------------------------------ |
| `backend-shards`   | 4 片矩阵并行跑 surefire `test`（快速反馈）                                          | 某片用例失败/编译失败；分片 namespace 或热栈异常 |
| `backend-merge`    | 校验 4 片合集 = 基线（每类恰好一次、0 failure/error/skip）                          | 有漏跑、重复跑、用例变少或报告缺失               |
| `backend-smoke`    | 迁移冒烟（默认 `riccon` 库）+ `spotless:check`                                      | 迁移/触发器/格式回归                             |
| `backend-coverage` | canonical `verify` + 全限定 `org.jacoco:jacoco-maven-plugin:report`（含覆盖率门槛） | 全量测试失败，或覆盖率跌破 line 80 / branch 70   |

`commands.test`/`commands.coverage` 文本不变：模块 gate 仍跑 canonical `./mvnw -q -T1C verify jacoco:report`，由 verifier 取证；CI 的 `backend-coverage` 用同一条后端命令做自动兜底。4 个分片只用于快速反馈（单片墙钟仍按 ≤5 分钟预算），不采集覆盖率，也不替代 gate；coverage job 是 canonical 兜底，因此这里**不承诺**总 CI 墙钟 ≤5 分钟。

`backend-coverage` 的细节（M22/F-001）：

- 与分片相同的 external 热栈 + 唯一 namespace `ci-<github.run_id>-cov`（与 `ci-<run_id>-b<shard>` 隔离）；迁移/回滚/schema 测试仍自建一次性容器。
- **CI 用全限定 goal**：`./mvnw -q -T1C verify org.jacoco:jacoco-maven-plugin:report`。fresh GitHub runner 上 `jacoco` prefix 的解析依赖 plugin group 元数据（`maven-metadata`），实测会报 `NoPluginFoundForPrefixException`（run 44974295661）；全限定坐标绕过 prefix 解析，只按插件坐标/版本解析。版本来自根 pom `pluginManagement` 的 `jacoco.version`（0.8.16），CI 不写死版本。
- **与合同命令等价**：同一份 `verify` 生命周期（compiler/test/jar/jacoco:check 全跑），只是把尾部的显式 report goal 从 `jacoco:report` 写成全限定坐标，产物、报告路径与门槛判定完全相同。本地/verifier 仍用合同文本 `./mvnw -q -T1C verify jacoco:report`；CI 不改变覆盖率门槛、测试集合或 DoD。
- `jacoco:check` 绑定在 `verify` 阶段，检查 `apps/api`、`apps/worker`、`platform-common`、`platform-domain` 的 line 80 / branch 70；覆盖率不足时该 job 直接红。
- 报告作为 artifact `backend-coverage-<run_id>` 上传：`apps/*/target/site/jacoco/**`、`platform/*/target/site/jacoco/**`（always，保留 14 天）。
- 本地复现/负向演练：先跑 canonical 命令，再用执行配置临时抬高阈值确认 `jacoco:check` 会失败，不要提交临时阈值：
  ```bash
  ./mvnw -q -T1C verify jacoco:report
  # CI 同款（不依赖 prefix）
  ./mvnw -q -T1C verify org.jacoco:jacoco-maven-plugin:report
  ./mvnw -q -pl apps/api jacoco:check@check-coverage \
    -Dcoverage.line.minimum=1.01   # 预期非 0 退出
  ```
- 前端 `pnpm -w test` 仍未带 `--coverage`，不在本 job 范围内（M22 观察项，未扩大改动）。
- M22/F-003 起 CI action 使用 Node24-backed 主版本：`actions/checkout@v7`、`actions/setup-java@v6`、`actions/setup-node@v7`、`actions/upload-artifact@v7`、`actions/download-artifact@v8`、`pnpm/action-setup@v6`（仍排在 setup-node 之前）；CI 日志不应再出现 Node.js 20 弃用告警。

分片 job 的环境变量与 P1 相同，只是 namespace 按分片区分（≤30 字符，满足 `[a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?`）：

| 变量                             | CI 值                         | 说明                       |
| -------------------------------- | ----------------------------- | -------------------------- |
| `RICCON_TEST_SERVICES`           | `external`                    | 显式启用热栈               |
| `RICCON_TEST_SERVICES_NAMESPACE` | `ci-<github.run_id>-b<shard>` | 每片独立数据库/桶/索引前缀 |

每片的执行顺序：`--check` 校验基线 → `compose config` → `up -d --wait` → `prepare --namespace` → `mvnw test`（带排除过滤器）→ 计时报告与墙钟 → 上传 surefire XML/分片清单/计时 → 失败时打印 external/namespace/compose 版本与容器日志 → `down -v`。

分片命令（本地复现同 CI，`N` 为 0..3）：

```bash
export RICCON_TEST_SERVICES=external
export RICCON_TEST_SERVICES_NAMESPACE=m19p2local0
node scripts/test-services.mjs prepare --namespace "$RICCON_TEST_SERVICES_NAMESPACE"
FILTER="$(node scripts/test-shards.mjs --shard 0 --print-test-filter)"
./mvnw -q -T1C test -Dtest="$FILTER" -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

- 分片只跑 `test`，不用 `verify`/`jacoco:report`：分片覆盖率不完整，覆盖率仍由 CI `backend-coverage` 与 verifier 的 canonical gate 采集。`-Djacoco.skip=true` 只关闭采集代理，不改变测试内容与断言。
- `-Dtest` 用 Surefire 3.5.6 的排除语法（`!FQCN`）：每片运行“除其他 3 片之外的所有类”。未登记到基线的新测试类不会被任何排除列表命中，会在 4 片全部执行，合并时以“重复类/额外类”失败，靠运行时集合校验防漏跑，不依赖源码命名扫描。
- 迁移、回滚与 schema 版本测试（`V9/V10/V11MigrationTest`、`SchemaVersionCheckerTest` 的 external 分支）仍使用一次性容器，不受热栈影响；迁移冒烟在 `backend-smoke` 用默认 `riccon` 库，不读写 namespace。
- 本地复用共享热栈时只 `stop`（保留数据卷），不要照抄 CI 的 `down -v`，否则会清空其他 agent 的数据。

### 分片基线与合并校验（M19 P2）

`scripts/test-shards-baseline.json` 是“哪些测试类必须各跑一次”的权威集合，来自一次全量、0 failure/error/skip 的 surefire 运行：

```bash
# 查看 4 片分布（类数/用例/基线时间）
node scripts/test-shards.mjs

# 校验基线聚合值、4 片互斥完整、源码测试类均已登记
node scripts/test-shards.mjs --check

# 单片的排除过滤器与分片清单
node scripts/test-shards.mjs --shard 2 --print-test-filter
node scripts/test-shards.mjs --shard 2 --emit --out-dir .ci/test-shards

# 本地演练：用现有全量报告模拟 4 片目录（仅演练，不参与 CI），再跑合并校验
node scripts/test-shards.mjs --simulate "$TMPDIR/shards"
node scripts/test-merge-shards.mjs --shards-root "$TMPDIR/shards" \
  --baseline scripts/test-shards-baseline.json --out-dir "$TMPDIR/merged"

# 合并校验（CI 下载的分片目录）
node scripts/test-merge-shards.mjs --shards-root .ci/shards \
  --baseline scripts/test-shards-baseline.json --out-dir .ci/test-shards-merged
```

- 分片算法：按模块（`apps/*`、`platform/*`）分组，模块内按基线时间降序、类名升序做 LPT 分配；同一模块内每个分片的累计时间最小，因此 `-T1C` 并行构建 api/worker 时，分片墙钟约等于 max(api 子集, worker 子集) + 固定开销。
- **测试集合变更（新增/删除/重命名测试类）必须重建基线并随代码提交**：
  ```bash
  RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=<ns> ./mvnw -q -T1C verify
  node scripts/test-shards.mjs --update-baseline
  git add scripts/test-shards-baseline.json
  ```
  `--update-baseline` 拒绝非绿色运行（failure/error/skip 非 0），也拒绝报告里找不到测试源码的类。Surefire 不会清理 `target/surefire-reports`，类重命名/换模块后旧 XML 会残留；遇到这类报错先删对应 `target/surefire-reports` 再跑全量。
- 合并校验项：分片齐全且编号完整（0..3）、基线每个类恰好出现一次、无额外类、每类用例数不减少、总用例数不少于基线、failures/errors/skips 全为 0。任一不满足非 0 退出，并打印具体类名与计数；报告写入 `test-shards-merged.json`/`.md`。
- CI artifact：`backend-shard-<N>`（surefire XML、`.ci/test-shards`、`.ci/test-timings`）与 `test-shards-merged`，保留 14 天。`.ci` 是隐藏目录，workflow 里必须显式 `include-hidden-files: true`，否则 artifact 为空。
- 分片排查：合并报告里的 `基线测试类没有任何分片执行` 多为排除过滤器用了旧基线或某片 artifact 缺失；`执行了 N 次` 多为新增测试未登记到基线。两种都先看 `--check` 输出，再决定重建基线还是修 job。

### 测试计时与预算（M19）

测试时间预算的数字与超预算处理只写在 `docs/CODING-STANDARDS.md` §10.3，本节只给操作方式，避免两处数字漂移。

```bash
# 汇总本仓库已有的 surefire 报告（apps/* 与 platform/*），stdout 打印人读摘要
node scripts/test-timings.mjs

# 同时写出 Markdown/JSON，供 CI artifact 或对比使用
node scripts/test-timings.mjs --out-dir "$TMPDIR/riccon-test-timings"

# 负向自测：指向空目录或坏 XML 时必须非 0 退出
node scripts/test-timings.mjs --reports-root "$TMPDIR/empty"
```

- 报告字段：用例总数、失败/错误/跳过数、累计 suite 时间（各 suite 时间相加，不是墙钟）、按模块汇总、top-20 最慢测试类（时间降序，同时间按类名升序）。
- CI artifact：`backend-shard-<N>` 里包含各片 `.ci/test-timings/shard-<N>/test-timings.md` 与 `.json`，artifact `test-shards-merged` 里包含合并报告，均保留 14 天。
- 报告缺失、为空或 XML 解析失败时脚本非 0 退出并打印原因；这是预期行为，不要用 `|| true` 掩盖。
- 预算数字与超预算处理见 `docs/CODING-STANDARDS.md` §10.3。

## 本地运行

```bash
# 1. 启动测试栈（MySQL 已在 compose 中固定审计触发器所需参数，无需手工 SET GLOBAL）
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait

# 2. 启动 API（默认端口 8080，连接 13306 的 MySQL；启动时执行 Flyway V1–V11）
./mvnw -q -pl apps/api spring-boot:run

# 3. 启动 Worker（默认端口 8081；M01 无数据库依赖，M02 持久化实现任务加入 JDBC 后需要 MySQL）
./mvnw -q -pl apps/worker spring-boot:run
```

健康检查：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8081/actuator/health
```

M01 的安全过滤器链默认拒绝健康检查之外的路径；M02 落地后只放行认证、CSRF 与健康检查，其余仍需会话与权限。

### 引导管理员（M02）

首次启动前通过环境变量注入，迁移**不会**创建默认账号或默认密码：

```bash
export RICCON_BOOTSTRAP_ADMIN_USERNAME=platform-admin
export RICCON_BOOTSTRAP_ADMIN_PASSWORD='<至少 12 位，部署时注入，禁止入库>'
```

启动后确认日志只有 “created/skipped”，随后从环境中移除该变量。空库且未提供引导变量时，平台没有管理员，需要按运维流程补建。

### 迁移与审计触发器

- 迁移只由 `apps/api` 执行；Worker 不包含迁移文件，只校验 `SchemaVersionChecker.EXPECTED_SCHEMA_VERSION`（M10 起为 10）。
- V6 创建 `audit_event` 触发器；若 MySQL 返回 `ERROR 1419`，说明迁移账号缺少 `TRIGGER` 或 `log_bin_trust_function_creators` 未开启。不得删除触发器绕过。
- 已应用的迁移文件禁止修改；新增迁移同步 `SchemaVersion.EXPECTED`（ADR-0010）。
- Spring Session 表由 V7 创建，必须保持 `spring.session.jdbc.initialize-schema=never`，禁止应用自动建表。
- V10（M10，审计查询与导出）新增 `audit_export_request` 表、`audit_event` 两条查询索引（`idx_audit_tenant_action_time`、`idx_audit_tenant_actor_time`）、`audit.export.approve` / `platform.audit.export.approve` 两条权限并回填既有 `TENANT_ADMIN` / `PLATFORM_ADMIN` 角色映射；纯 additive，append-only 触发器不变。生产执行见下文“V10 生产迁移”。

#### 测试栈迁移演练（V1–V11，可复制）

> **仅测试栈。** 以下参数与凭据只属于 `deploy/compose/docker-compose.test.yml`。生产迁移账号是否具备 `TRIGGER`、是否开启 `log_bin_trust_function_creators`（或改用 DBA 预建触发器）由 M09 决定，禁止直接照搬本节配置。

MySQL 8.4 默认开启 binlog（`log_bin=ON` 只读），非 SUPER 账号创建触发器会报 `ERROR 1419`。测试栈把 `--log-bin-trust-function-creators=1` 写进 MySQL 服务的 `command`，容器每次启动即生效；不要在容器启动后用 `SET GLOBAL` 临时打开——重启即失效，CI 也不可复现。

在仓库根目录执行：

```bash
# 1. 全新卷，避免旧 schema/旧 binlog 状态干扰
docker compose -f deploy/compose/docker-compose.test.yml down -v

# 2. 启动三服务并等待 healthy（MySQL/OpenSearch/RustFS 都必须是 healthy）
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait
docker compose -f deploy/compose/docker-compose.test.yml ps

# 3. 确认参数生效；全程使用 riccon 用户，不用 root 绕过权限
docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
  mysql mysql --user=riccon -e "SHOW VARIABLES LIKE 'log_bin_trust_function_creators';"

# 4. 按 Flyway 版本号顺序执行 V1..V11（不能用 shell 字典序：V10 会排到 V2 之前，
#    导致外键引用尚未创建的 app_user）；任一步失败立即中断，不吞错误
set -euo pipefail
migrations="$(printf '%s\n' apps/api/src/main/resources/db/migration/V*.sql | sort -V)"
while IFS= read -r migration; do
  echo "== ${migration}"
  docker compose -f deploy/compose/docker-compose.test.yml exec -T \
    -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
    mysql mysql --user=riccon --database=riccon --batch --show-warnings < "${migration}"
done <<< "${migrations}"

# 5. 校验结构：45 张表（Flyway 口径 46）、2 个触发器、V10 对象
docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
  mysql mysql --user=riccon --database=riccon --batch -e "
    SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='riccon';
    SELECT COUNT(*) AS trigger_count FROM information_schema.triggers WHERE trigger_schema='riccon';
    SELECT trigger_name, event_manipulation FROM information_schema.triggers WHERE trigger_schema='riccon';
    SELECT COUNT(*) AS v10_tables FROM information_schema.tables
     WHERE table_schema='riccon' AND table_name='audit_export_request';
    SELECT COUNT(DISTINCT index_name) AS v10_indexes FROM information_schema.statistics
     WHERE table_schema='riccon' AND table_name='audit_event'
       AND index_name IN ('idx_audit_tenant_action_time','idx_audit_tenant_actor_time');
    SELECT COUNT(*) AS v10_permissions FROM permission
     WHERE code IN ('audit.export.approve','platform.audit.export.approve');"

# 6. 校验 append-only：先插入一行，再验证 UPDATE/DELETE 都被触发器拒绝（两条命令都必须 exit 1）
docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
  mysql mysql --user=riccon --database=riccon -e "
    INSERT INTO audit_event (event_id, occurred_at, tenant_id, actor_type, actor_id, action, outcome, trace_id)
    VALUES (UNHEX('0192f0a1b2c37d4e8f9012345678abcd'), UTC_TIMESTAMP(6), 'platform', 'SYSTEM', 'system', 'migration.smoke', 'SUCCESS', 'trace-smoke-001');"

docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
  mysql mysql --user=riccon --database=riccon \
  -e "UPDATE audit_event SET outcome='FAILURE' WHERE action='migration.smoke';" \
  ; echo "UPDATE 期望 exit 1，实际 exit $?"

docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
  mysql mysql --user=riccon --database=riccon \
  -e "DELETE FROM audit_event WHERE action='migration.smoke';" \
  ; echo "DELETE 期望 exit 1，实际 exit $?"

# 7. 清理：演练数据与容器一次性销毁
docker compose -f deploy/compose/docker-compose.test.yml down -v
```

预期结果：

| 检查项                            | 期望                                                                                                     |
| --------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `log_bin_trust_function_creators` | `ON`                                                                                                     |
| 迁移输出                          | V1–V11 逐条无错误；不得出现 `ERROR 1419`                                                                 |
| `table_count`                     | 45（手工重放不含 `flyway_schema_history`；API 经 Flyway 启动后为 46，与 `ApiApplicationTests` 口径一致） |
| `trigger_count`                   | 2（`trg_audit_event_no_update`、`trg_audit_event_no_delete`）                                            |
| `v10_tables` / `v10_indexes`      | 1（`audit_export_request`）/ 2（两条 `audit_event` 复合索引）                                            |
| `v10_permissions`                 | 2（`audit.export.approve`、`platform.audit.export.approve`）                                             |
| UPDATE / DELETE                   | 均 exit 1，`ERROR 1644 (45000): audit_event is append-only`，行仍存在                                    |

手工重放只用于演练与 CI 冒烟，不会写入 `flyway_schema_history`。**不要在同一数据卷上随即启动 API**：Flyway 会因“非空 schema 但无历史表”拒绝迁移；需要继续用 API 验证时先 `down -v` 再 `up -d --wait`。

CI 的 `backend-smoke` job 使用同一个 compose 文件做冒烟（`.github/workflows/ci.yml` 的“迁移冒烟”步骤），按 Flyway 版本号顺序（`sort -V`）重放全部 `V*.sql`；本 job 只跑迁移冒烟与 `spotless:check`，不跑构建与全量测试（canonical `./mvnw -q -T1C verify jacoco:report` 由 CI `backend-coverage` 与 verifier gate 跑）。表数不写死：期望值从 `apps/api/src/main/resources/db/migration/V*.sql` 的 `CREATE TABLE` 数量推导，并额外断言 V10 表、索引、权限与 append-only 触发器。

> 合同 `commands.run` 引用 `deploy/compose/docker-compose.dev.yml`，该文件由后续 ops 任务建立；M01 用测试栈替代。不要在 M01 中创建生产/预览编排。

## 验证

```bash
./mvnw -q -T1C verify          # 编译 + 测试 + 覆盖率门槛
./mvnw -q spotless:check       # 格式检查
./mvnw -q -T1C verify jacoco:report   # 覆盖率报告
docker compose -f deploy/compose/docker-compose.test.yml config
```

- 覆盖率门槛：行 80%、分支 70%，由 `jacoco:check` 在 `verify` 阶段执行。
- 报告：`apps/*/target/site/jacoco/index.html`、`platform/*/target/site/jacoco/index.html`。
- 架构测试：`platform/platform-architecture-tests`，6 条规则必须全部通过。
- 证据使用 `riccon evidence run --task TASK-XXXX --agent ... -- "命令"` 记录；没有证据不视为通过。

## 预览

M09 提供 Helm 与预览编排。M01 不部署预览环境；任何预览环境必须使用独立数据库与对象存储，禁止连接生产。

## V10 生产迁移（OPS-004/005/006/007，尚未执行）

> **状态：未执行。** 本节只是执行方案；V10 尚未在任何生产/共享环境运行。实际执行需用户按合同 `decision_owners.production` 完成 T4 批准，并具备 OPS-006/007 的非生产演练记录。

V10 与代码强耦合（新 API 需要新表/索引，worker 严格校验 schema 版本），**不能纯滚动**：DB 升到 10 后任何旧 worker（expected=9）重启都会以 `RICCON-WORKER-001` 启动失败；旧 API 虽能忽略 future 迁移启动，但滚动期重启仍是可用性风险。

**OPS-004 协调切换顺序（正向）**：

```text
0. 全量备份，并在副本上验证恢复；预检磁盘余量、分区体积、长事务/MDL
1. 停 worker（drain；在途运行按租约 90s 重试或先等其结束）
2. 停 API 写入（维护模式 503 或缩容到 0）
3. 用新 API 镜像的一次性迁移 Job 执行 Flyway（不要经由常驻 API 启动触发）
4. 按下方“结构校验”确认 V10 对象与 flyway_schema_history=10
5. 起新 API → 健康检查 → smoke（登录、审计查询、导出闭环、J1/J2）
6. 起新 worker（EXPECTED=10）→ 确认 outbox 恢复领取
7. 解除维护模式
```

**OPS-005 维护窗口与索引 DDL**：V10 的两条索引写在**同一条** `ALTER TABLE audit_event ... ALGORITHM=INPLACE, LOCK=NONE` 中，MySQL 8 原子 DDL 保证同成败，失败不会留下半成品索引。生产仍在维护窗口停写执行；online DDL 只是演练确认后的加速手段，不作为免窗口依据。

**结构校验**：

```sql
SHOW CREATE TABLE audit_export_request;
SELECT DISTINCT index_name FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'audit_event'
   AND index_name IN ('idx_audit_tenant_action_time','idx_audit_tenant_actor_time');  -- 2 行
SELECT COUNT(*) FROM information_schema.triggers
 WHERE trigger_schema = DATABASE() AND event_object_table = 'audit_event';            -- 必须仍为 2
SELECT COUNT(*) FROM permission
 WHERE code IN ('audit.export.approve','platform.audit.export.approve');              -- 2
SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema = DATABASE();                                                     -- 含 flyway_schema_history 为 40
SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;   -- 10
```

**OPS-006 失败恢复矩阵**（MySQL DDL 非事务，失败会保留已提交前缀；禁止反复重跑而不 repair，禁止修改已应用的 V10 文件）：

| 失败点                            | 残留                             | 恢复                                                                                                     |
| --------------------------------- | -------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `CREATE TABLE` 失败（FK/重复表）  | 无                               | 修因 → `flyway repair` → 重跑；若表已存在先确认是否半成品                                                |
| 权限字典 / 角色回填 INSERT 失败   | 表已建、索引未建                 | `DROP TABLE audit_export_request`（新建且为空）→ `flyway repair` → 重跑；INSERT 带 NOT EXISTS 守卫       |
| 索引 ALTER 失败（online log/MDL） | 表与权限已就绪、无新索引（原子） | 消除冲突对象（如重名索引）→ 按上一条 DROP TABLE → `flyway repair` → 窗口内重跑；**不要**手建其中一条索引 |
| 迁移 Job 中途被杀                 | 取决于最后提交的语句             | 按上表定位后 repair/重跑，并记录残留                                                                     |

**OPS-007 回滚（严格反向）**：

```text
1. 停新 worker → 停新 API，排空写入并做全量备份（回滚会丢失 audit_export_request 数据）
2. （若涉及 OIDC）先在新版本上解绑/写回本地密码
3. 执行 ADR-0020 §5.4 回滚 SQL：
   DROP TABLE audit_export_request;
   ALTER TABLE audit_event DROP INDEX idx_audit_tenant_action_time,
                              DROP INDEX idx_audit_tenant_actor_time;
   DELETE FROM role_permission
    WHERE permission_code IN ('audit.read','audit.export.approve','platform.audit.export.approve');
   DELETE FROM permission
    WHERE code IN ('audit.export.approve','platform.audit.export.approve');
4. 由维护账号删除 V10 历史行（应用账号不得删除 history）：
   DELETE FROM flyway_schema_history WHERE version = '10' AND success = 1;
   SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;  -- 必须为 9
5. 起旧 API（v9）→ 健康检查 → 起旧 worker（v9）→ 确认版本校验通过
6. 记录回滚证据：结构、行数、审计可读性、导出请求丢失数量
```

前提：`audit.read` 在 M10 前未授予任何角色；若发布后存在手工授予，回滚前必须先导出/记录这些映射。`audit_event` 与 M02/M03 数据不受回滚影响。

**演练要求（M10 gate）**：在一次性 MySQL 8.4.13 容器上用 **Flyway**（不是 mysql CLI 直放）从 V1–V9 + 种子数据升到 V10，覆盖结构校验、`SchemaVersionChecker` 对 V9 失败/对 V10 通过、回滚、以及预建同名索引的失败注入；记录写入 M10 评审包，容器一次性销毁，不连接热栈。

## M04 模型网关发布、回滚与运维（单模块）

> 依据：ADR-0021 §10（发布/回滚/开关/观测）、ADR-0022 §1.3/§2（配额与成本）。适用范围是客户 K8s
> （M09 交付 Helm）与单机预览/测试栈；**M04 gate 不使用 `deploy_preview`/`deploy_prod`**，迁移、
> 回滚、备份恢复与 perf 证据走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`，评审包标
> `deploy_preview not-applicable`（M09）。
>
> 所有命令在仓库根目录执行；脚本遇错一律非 0，禁止用 `|| true`、`| tail` 或重试掩盖失败。

### M04-0 preflight（零副作用，失败即停）

| 检查项              | 命令/来源                                                                                                                      | 通过判据                                                                                                                                                                                 |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 主密钥与 Secret     | 环境变量存在性 + base64 长度（见下）                                                                                           | `RICCON_MASTER_KEY`/`RICCON_CREDENTIAL_FINGERPRINT_PEPPER` 均为 32 字节；轮换窗口才注入 `RICCON_MASTER_KEY_PREVIOUS`                                                                     |
| service token / DB  | `RICCON_SERVICE_TOKEN_SECRET`、`RICCON_DB_*` 由密钥管理注入                                                                    | 非空；日志、工单、证据里不出现值                                                                                                                                                         |
| 端点与模式          | `RICCON_MODEL_DEPLOYMENT_MODE`、`RICCON_MODEL_ENDPOINTS`、`RICCON_MODEL_ALLOW_HTTP`、`RICCON_MODEL_ENDPOINT_SET_HASH`          | PRODUCTION 只允许 https、未配置端点即启动失败；DEMO 才允许 http                                                                                                                          |
| 币种                | `RICCON_BILLING_CURRENCY`                                                                                                      | ISO 4217；**部署后不可在线变更**（见“换币种不可行”）                                                                                                                                     |
| 默认配额 4 键       | `RICCON_QUOTA_DEFAULT_TENANT_DAILY/_TENANT_MONTHLY/_USER_DAILY/_USER_MONTHLY`                                                  | 与 API/Worker 两侧一致；默认 10000/200000/1000/20000；`0` 或空 = 不限                                                                                                                    |
| DB 连通与体积       | 下方 SQL                                                                                                                       | `model_invocation` ≤200 万行且 data+index ≤2 GiB；磁盘余量 ≥2× 表体积；无 >60s 事务/长 MDL                                                                                               |
| API/Worker 指纹漂移 | `node scripts/ops/seed-model-catalog.mjs --check --api-base <API> --worker-base <Worker> [--catalog <目录文件>]`               | exit 0 且 `status=passed`；**必须从 `RICCON_OPS_ALLOWED_CIDRS` 允许的来源发起**（与 prometheus 同一允许列表，空 = 仅 loopback），其他来源按设计 403；403 不是通过，也不得用 env 快照冒充 |
| 目录与部署端点一致  | `node scripts/ops/seed-model-catalog.mjs --check ... [--catalog <目录文件>] [--endpoints "$RICCON_MODEL_ENDPOINTS"]`（不写库） | `model_provider.base_url` 与 `RICCON_MODEL_ENDPOINTS` 的 code/baseUrl 逐条一致；目录文件与 DB 差异由同一命令报告                                                                         |

```bash
# Secret 非空与长度（只打印结果，不打印值）
set -euo pipefail
for name in RICCON_MASTER_KEY RICCON_CREDENTIAL_FINGERPRINT_PEPPER RICCON_SERVICE_TOKEN_SECRET; do
  [[ -n "${!name:-}" ]] || { echo "missing ${name}"; exit 1; }
done
node -e 'for (const name of ["RICCON_MASTER_KEY","RICCON_CREDENTIAL_FINGERPRINT_PEPPER"]) {
  const key = Buffer.from(process.env[name], "base64");
  if (key.length !== 32) throw new Error(`${name} must be 32 bytes, got ${key.length}`);
} console.log("secrets ok");'

# 表体积与行数（模型调用是大表判据；provider_credential/tenant_data_key 是小表）
docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" mysql \
  mysql --user=riccon --database=riccon --batch -e "
    SELECT table_name, table_rows, data_length, index_length
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name IN ('model_invocation','provider_credential','tenant_data_key','quota_usage','audit_event');
    SELECT COUNT(*) AS long_trx FROM information_schema.innodb_trx
     WHERE TIME_TO_SEC(TIMEDIFF(NOW(), trx_started)) > 60;
    SELECT COUNT(*) AS long_mdl FROM performance_schema.metadata_locks
     WHERE lock_status = 'PENDING' AND lock_type = 'METADATA';"
```

判据与动作：

- `model_invocation > 200 万行` 或 `data+index > 2 GiB`：V11 的 4 条索引必须**单独维护窗口停写**执行，
  磁盘预留 ≥2× 表体积（ADR-0021 §10.1）；否则按“preflight + 六步”正常窗口执行。
- `long_trx`/`long_mdl` 必须为 0；非 0 时先定位并结束来源（长查询/备份/DDL 残留），不要带病迁移。
- 生产 `--via=client`（`RICCON_DB_*` + PATH 上 `mysql`）；`--via=compose` 只用于测试栈。

> **来源要求（TASK-0128 起）**：`/actuator/info` 与 `/actuator/prometheus` 共用 `RICCON_OPS_ALLOWED_CIDRS`
> 允许列表（空 = 仅 loopback）。preflight 必须从允许来源发起；其他来源按设计返回 403，应先修正来源网段
> 或 ingress，而不是放宽安全链，也不得用 env 快照冒充“指纹一致”。

### M04-1…6 发布顺序（preflight + 六步，不能纯滚动）

DB 升到 11 后旧 Worker（期望 10）会以 `RICCON-WORKER-001` 启动失败，因此必须协调切换，不能纯滚动。

1. **备份**：全量 MySQL，含 `flyway_schema_history`、`tenant_data_key`、`provider_credential`、
   `SPRING_SESSION`；记录 `master_key_id`，密钥保留期 ≥ 备份保留期。在副本上校验一行 credential 可解密：

   ```bash
   RICCON_MASTER_KEY=... RICCON_CREDENTIAL_FINGERPRINT_PEPPER=... \
     node scripts/ops/rewrap-credentials.mjs --verify-only --via=client --database riccon_restore_drill
   ```

2. **一次性迁移 Job**：用**新 API 镜像**只执行 Flyway，不经常驻 API 触发迁移。

   ```bash
   java -jar riccon-api-exec.jar \
     --spring.main.web-application-type=none \
     --spring.flyway.enabled=true \
     --spring.flyway.validate-on-migrate=true
   # 迁移完成后进程自行退出；10 分钟未退出则终止并检查 flyway_schema_history，禁止重复跑未 repair 的失败迁移
   ```

   失败即停，不动业务表；按 ADR-0021 §10.4 失败矩阵 `flyway repair` 后重跑。

3. **结构校验**：

   ```sql
   SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;  -- 必须 11
   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
     AND table_name IN ('model_routing','model_routing_entry','model_gateway_switch',
                        'model_invocation_attempt','quota_limit','quota_usage');       -- 6
   SELECT COUNT(*) FROM provider_credential WHERE status = 'RETIRED';                  -- 0（已归一为 DISABLED）
   SELECT COUNT(*) FROM information_schema.key_column_usage
     WHERE table_schema = DATABASE() AND table_name = 'model_invocation'
       AND referenced_table_name = 'model_provider';                                   -- 0（快照不建 FK）
   SELECT index_name FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'model_invocation'
       AND index_name IN ('idx_model_invocation_tenant_time','idx_model_invocation_user_time',
                          'idx_model_invocation_cost_day','idx_model_invocation_provider'); -- 4 行
   SELECT COUNT(*) FROM model_invocation WHERE cost_day IS NULL;                       -- 允许非 0（计入 unbucketedCalls）
   ```

4. **新 API**：`/actuator/health` 为 UP；平台管理员读 `/api/v1/admin/model-providers` 返回
   `allowed=true` 且标签正确；`GET /api/v1/admin/model-gateway/switches` 可读。

5. **drain 旧 Worker → 新 Worker**（自停止领取起上限 **5 分钟**）
   1. 先记录在途 run 清单（`runId`），用于确认新 Worker 按租约恢复：

      ```sql
      SELECT tenant_id, HEX(id) AS run_hex, status, attempt, lease_until
        FROM agent_run
       WHERE status IN ('PENDING','RUNNING','RETRY_WAIT','WAITING_APPROVAL')
       ORDER BY created_at;
      ```

   2. 优雅停机旧 Worker（`server.shutdown=graceful`），停止领取新 run；等待在途 run 自然结束。
   3. 超时不得强杀：按失败/取消语义结束并核对审计。outbox 租约 90s、最大 3 次尝试，新 Worker
      按租约到期恢复；同一 run 不重复准入、attempt 不重复计数。
   4. 启动新 Worker（期望 11）→ `/actuator/health` UP → 确认 outbox 恢复领取。

6. **冒烟与开关**（30 分钟观测窗口）
   - J1 首字、J2 报告（含审批）、路由读、key 掩码、开关读；
   - 默认 `ROUTED/true/false`（`quotaEnforcementEnabled=false`，只计量不阻断）；
   - 先跑 `scripts/ops/quota-drill.mjs`（见 M04-9）验证超限语义，再显式 `PUT true`；
     M04 gate 以 `true` 状态取证 AC-08/09；
   - 观测覆盖路由/降级/key/链空/5xx 与配额拒绝（指标表见 M04-12）。

### M04-7 回滚（应用回滚，不做 down migration）

**生产回滚优先“应用回滚 + 备份恢复 + 前向修复”**；下列结构 SQL 只用于演练或经用户批准的灾难恢复。
回滚会丢失 M04 新表数据（路由/开关/尝试/配额），成本列与 `model_invocation` 历史保留。

```text
1. 停新 Worker → 停新 API（维护模式/缩容到 0），排空写入并做全量备份
2. 多 key 收敛（必须先做，否则恢复旧唯一键会 ERROR 1062）：
   a) 备份 provider_credential（含 id/ciphertext/fingerprint）
   b) 每个 (tenant_id, provider_id, credential_type) 只保留 1 行：有 ACTIVE 保留 ACTIVE；
      同状态按 priority ASC, created_at DESC, id DESC 取 1 行；其余归档后 DELETE
   c) 只把保留行的非 ACTIVE 状态改为 RETIRED（DISABLED/INVALID → RETIRED）；保留的 ACTIVE 不动
3. 先 DROP CHECK（顺序不可反）：chk_provider_credential_status/_priority/_failures
4. 恢复旧唯一键 uk_provider_credential + 旧 CHECK；删除 uk_provider_credential_fingerprint 与 idx_provider_credential_pool
5. 删除 M04 新增列（label/fingerprint/masked_hint/priority/consecutive_failures/cooldown_until/
   disabled_at/disabled_by/invalidated_at/last_used_at）
6. 删除 M04 新表：model_invocation_attempt、model_gateway_switch、model_routing_entry、model_routing、
   quota_usage、quota_limit；model_invocation 新增列保留（旧应用忽略），不删除成本快照
7. 由维护账号删除 flyway_schema_history 的 V11 行 → flyway repair → 断言 MAX(version)=10
8. 起旧 API（v10）：Flyway 默认 spring.flyway.ignore-migration-patterns=*:future 必须保留；
   若客户显式置空该属性，回滚窗口内必须设为 *:future，否则旧镜像在 V11 库上启动失败
9. 起旧 Worker（M04 前最后一个版本）：必须 RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false，
   并在工单登记；确认启动日志出现固定 WARN 码 RICCON-WORKER-002（文案 schema validation disabled）
10. 健康检查 + J1/J2 冒烟 + 历史调用在运行详情可读（AC-18）+ 成本页只读
```

分组断言（不要写死总行数，也不只断言行数）：

```sql
-- 每组恰好 1 行；组内若有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED
SELECT tenant_id, provider_id, credential_type, COUNT(*) AS rows_per_group,
       SUM(status = 'ACTIVE') AS active_rows, MIN(status) AS kept_status
  FROM provider_credential
 GROUP BY tenant_id, provider_id, credential_type
HAVING COUNT(*) <> 1 OR SUM(status = 'ACTIVE') > 1;
-- 期望：0 行
SELECT COUNT(*) FROM provider_credential WHERE status IN ('DISABLED','INVALID');  -- 期望 0
```

回滚工单必须登记：窗口起止、`MAX(version)` 人工核对结果、旧 Worker 版本、`RICCON-WORKER-002` 日志行、
key 收敛行数与归档位置、风险接受人。**混合版本窗口（旧 Worker 不写 `cost_day`）不可用于成本对账**，
这些行计入 `unbucketedCalls`，回滚结束后用 `scripts/ops/backfill-cost-day.mjs` 回填再对账。

> **实现状态**：TASK-0127（EV-0591）已实现固定 WARN。`RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false`
> 启动时 `SchemaVersionChecker` 输出恰好一次固定 WARN，格式为 “RICCON-WORKER-002 schema validation
> disabled; worker is running in rollback window, expected schema version=11”；校验启用时绝不输出。
> 回滚演练判据：该 WARN 逐字出现一次，缺失或重复均判失败，以代码与日志实际输出为准（不得改写文案）。

### M04-8 主密钥轮换（rewrap）与幂等种子/价格

轮换是**双密钥窗口**：新密钥 `RICCON_MASTER_KEY` + 旧密钥 `RICCON_MASTER_KEY_PREVIOUS`；pepper 不轮换。

```bash
# 1) 只读校验：确认当前密钥可解包全部 DEK、每个 provider 抽一行凭据可解密且 fingerprint 匹配
node scripts/ops/rewrap-credentials.mjs --verify-only --via=client

# 2) 正式 rewrap（默认 dry-run；--apply 才写 tenant_data_key）
node scripts/ops/rewrap-credentials.mjs --apply --via=client

# 3) 观察窗口后移除 RICCON_MASTER_KEY_PREVIOUS，再跑一次 --verify-only
```

判据：`status=passed`、`rewrapped` 覆盖所有 `master_key_id != RICCON_MASTER_KEY_ID` 的行、无 `failures`。
失败时停止并保留旧密钥（旧密钥仍可解包），不要重复 `--apply` 掩盖；回退只需把 `RICCON_MASTER_KEY`
切回旧值（未 rewrap 的行仍是旧 ID）。

目录/价格变更（新增端点、模型、价格、币种）只能按下表；路由/开关**必须走管理端 API**（乐观锁 + 审计），
运维不得 SQL 直改：

| 变更          | 步骤                                                                                                                                             | 禁止                                                        |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------- |
| 新增端点      | 更新 `RICCON_MODEL_ENDPOINTS`（含 code/baseUrl/https）→ `seed-model-catalog.mjs --catalog <目录文件> --apply` → 重启 API/Worker → `--check` 比对 | 只改 env 不种 DB；脚本会 `--check` 报 endpoint 漂移         |
| 新增模型/改价 | 目录文件加模型或改价格 → `seed-model-catalog.mjs --apply`（幂等 upsert）→ 需要时 `update-model-price.mjs --apply`                                | 改历史 `model_invocation` 快照；DELETE 旧模型               |
| 改单个价格    | `update-model-price.mjs --provider-code <c> --model-key <k> --input/--output <p> [--apply]`，打印 before/after                                   | 用 UPDATE 直改库；批量 SQL 无审计                           |
| 换币种        | **不可行**：新部署 + 历史报告按币种隔离；旧快照保持原币种                                                                                        | 在线改 `RICCON_BILLING_CURRENCY`（会产生 currencyMismatch） |

### M04-9 备份恢复演练（preview RPO ≤24h、RTO ≤4h）

预览/测试环境基线：RPO ≤24h、RTO ≤4h；客户生产可由 SOW 覆盖，覆盖值必须写进交付文档，
但不得放宽到无备份窗口。`restore-drill` 通过判据：表数一致、触发器 2 个、一行 credential 可解密、
J1 冒烟通过、`MAX(version)=11`、RTO ≤4h。

```bash
# 完整演练（推荐）：演练库 riccon_restore_drill 导入源库快照，API 指向演练库后跑 J1
RICCON_MASTER_KEY=... RICCON_CREDENTIAL_FINGERPRINT_PEPPER=... \
RICCON_DRILL_MEMBER_TENANT=tenant-alpha RICCON_DRILL_MEMBER_USERNAME=alpha-member \
RICCON_DRILL_MEMBER_PASSWORD=... \
  node scripts/ops/restore-drill.mjs --apply --api-base http://127.0.0.1:18089

# 仅结构演练（无主密钥/无 API）：记录 partial，不得当通过
node scripts/ops/restore-drill.mjs --apply --allow-partial
```

- 演练库只在同实例一次性使用，名字必须 `riccon_restore_*`；结束默认删除（`--keep` 保留）。
- 建库/导入使用 `RICCON_DB_ADMIN_USER/PASSWORD`（测试栈默认 root；生产必须是具备建库权限的最小账号），
  不得把 admin 密码写进命令历史或证据。该账号除建库/导入外**还需演练库的 GRANT 权限**：
  导入成功后脚本会用管理账号给运行账号（`--grant-user`，默认 compose=`riccon` / `--via=client` 取
  `RICCON_DB_USER`；主机默认从 `mysql.user` 只读发现，可用 `--grant-host` 指定）授予演练库上的最小
  DML（`SELECT/INSERT/UPDATE/DELETE`，仅 `riccon_restore_*.*`，不含 DDL/GRANT OPTION，不建账号）。
  缺该权限时演练在授权步骤失败，可改用有权限账号重跑。
- 演练库删除前脚本会先 `REVOKE ALL PRIVILEGES ON <演练库>.*` 再 `DROP DATABASE`（MySQL 不随
  DROP 清理 `mysql.db` 库级授权）；`--keep` 保留演练库时授权一并保留，下次清理前必须回收。
  `--no-grant` 只用于“运行账号本来就有演练库权限”的场景，否则 credential 校验/J1 会报 `ERROR 1044`。
- J1 冒烟必须指向“连接演练库”的 API（独立端口/独立 `RICCON_DB_URL`）；指向生产 API 的冒烟不算数。
- 报告默认写入 `.riccon/evidence/<REAL_EVIDENCE_TASK>/restore-drill.json`；`partial` 退出 2（除显式
  `--allow-partial`），`failed` 退出 1。

### M04-10 `backfill-cost-day`（历史成本日桶回填）

V11 不回溯 `cost_day`，历史行 `cost_day=NULL` 只计入 `unbucketedCalls`。需要归桶时：

```bash
# 干跑：待回填行数 + 表体积（默认就是 dry-run）
node scripts/ops/backfill-cost-day.mjs [--tenant tenant-alpha]

# 生产：维护窗口外分批，默认 5000 行/批，批次间不暂停；必要时 --pause-ms 节流
node scripts/ops/backfill-cost-day.mjs --apply --batch-size 5000 [--max-batches 100] [--pause-ms 200]
```

判据：每批打印 `remaining` 单调下降；最终 `remaining=0` 且 exit 0。脚本只写 `cost_day`，重复执行第二次
更新 0 行（幂等）；不重算金额、不改 `cost_status`。`--max-batches` 未跑完时 exit 1，必须继续跑完。

### M04-11 配额开关 false→true 真实超限演练

发版默认 `quotaEnforcementEnabled=false`（只计量不阻断）。B10 演练必须**真实超限**，不能只读检查：

```bash
export RICCON_DRILL_PLATFORM_USERNAME=...   # 平台管理员（开关 + 限额）
export RICCON_DRILL_PLATFORM_PASSWORD=...
export RICCON_DRILL_MEMBER_TENANT=tenant-alpha
export RICCON_DRILL_MEMBER_USERNAME=alpha-member
export RICCON_DRILL_MEMBER_PASSWORD=...
node scripts/ops/quota-drill.mjs --api-base http://127.0.0.1:18080 --tenant tenant-alpha
```

脚本步骤与判据（`finally` 中无论如何都恢复限额与开关）：

| 步  | 动作                             | 判据                                                                                                                      |
| --- | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 1   | 记录基线并打开执行（`PUT true`） | 返回 `quotaEnforcementEnabled=true`                                                                                       |
| 2   | 租户日限额 = 已用 + 1            | `PUT /api/v1/admin/tenants/{id}/quotas` 200，日限额等于 `used+1`                                                          |
| 3   | 提交 1 个 CHAT run               | 200，消耗最后 1 次                                                                                                        |
| 4   | 再提交 1 个 CHAT run             | **429**，body 含 `RICCON-QUOTA-429`；`audit_event.action='quota.exceeded'` 计数增加；`riccon_quota_rejections_total` 增加 |
| 5   | `PUT false` 后再提交             | 200（只计量不阻断）；`riccon_quota_over_limit_observed_total` 增加（缺失时记录为 absent，不判失败）                       |
| 6   | 恢复原限额 → `PUT true`          | 日/月限额与原值一致，开关为 `true`（M04 gate 取证状态）                                                                   |

- 审计核对需要 DB（`--via=compose|client`）；`--via=none` 必须配 `--allow-partial`，报告 `status=partial`。
- 演练租户必须是可丢弃的测试租户；演练会留下用量与审计（真实超限的必然结果），生产演练需用户批准。

### M04-12 漂移检查（`/actuator/info` 指纹）

```bash
node scripts/ops/seed-model-catalog.mjs --check \
  --api-base http://127.0.0.1:8080 --worker-base http://127.0.0.1:8081 \
  [--catalog <目录文件>] [--endpoints "$RICCON_MODEL_ENDPOINTS"]
```

发起来源：`--api-base`/`--worker-base` 必须落在 `RICCON_OPS_ALLOWED_CIDRS` 允许列表内（与
`/actuator/prometheus` 同一配置，空 = 仅 loopback）；不在列表内的来源按设计返回 403，属于来源配置问题，
不是“指纹一致”，也不是放行安全链的理由。

生产/预览用 `--via=client`（`RICCON_DB_*` 读 `model_provider`）；本地测试栈用 `--via=compose`；
DEMO 的 `http://` 端点需要显式 `--allow-http`，PRODUCTION 只接受 https。

比对键：`deploymentMode`、`endpointSetHash`、`routingMode`、`failoverEnabled`、
`quotaEnforcementEnabled`、`masterKeyId`、`currency`、`tenantDaily/tenantMonthly/userDaily/userMonthly`。
判据：API 与 Worker 逐键相等；与目录文件 `deployment` 段期望值一致；`RICCON_MODEL_ENDPOINTS` 的
code/baseUrl 与 DB `model_provider` 一致。任一 403/401（多半是发起来源不在 `RICCON_OPS_ALLOWED_CIDRS`）或缺失键 → exit 1（发布阻断，先修来源而不是绕过）。

### M04-13 成本对账窗口与容差

- 窗口：日对账 T+1（北京时间 09:00 前完成前一日）；月对账每月 1 日 09:00 前完成上月；
  回滚/混合版本窗口（旧 Worker 不写 `cost_day`）不可对账，先回填再对账。
- 容差：**金额差 0.00**（按币种最小单位，CNY 2 位）；**调用量差 ≤0.1%** 且差异必须解释。
  金额按已存储 6 位 `cost_amount` 求和，不按展示 2 位先舍入。

```bash
export RICCON_RECONCILE_USERNAME=<租户管理员> RICCON_RECONCILE_PASSWORD=...
node scripts/ops/cost-reconcile.mjs --api-base http://127.0.0.1:18080 --tenant tenant-alpha \
  [--from 2026-11-01] [--to 2026-11-21] [--currency CNY] [--explain "当日有 3 条 USAGE_UNKNOWN，未计金额"]
```

判据：`status=passed`。`amountMinor != 0.00`、`callRatio > 0.001` 或“有差异但无 `--explain`”都 exit 1。
`unbucketedCalls > 0` 必须在 `--explain` 中说明来源（历史行/回滚窗口）并给出回填计划。

### M04-14 指标与告警

指标清单唯一来源是 ADR-0021 §10.6；标签禁止 `tenant`/`userId`/`runId` 等高基数。按租户排查走日志/审计/DB。

| 指标                                                  | 类型      | 告警阈值                                  | 处置                                                                |
| ----------------------------------------------------- | --------- | ----------------------------------------- | ------------------------------------------------------------------- |
| `riccon_model_requests_total{provider,model,outcome}` | counter   | `outcome=error` 5 分钟占比 >5%            | 看 provider/model 维度；核对 key 与上游状态；必要时 `BASELINE` 开关 |
| `riccon_model_failover_total{from,to,reason}`         | counter   | 降级率（failover/requests）>20%/5min      | 定位首个失败 reason；key 冷却/上游 5xx；检查尝试上限与预算          |
| `riccon_model_attempt_duration_seconds`               | histogram | p95 > 首字预算（2.5s）                    | 上游慢或重试过多；核对 `RICCON_MODEL_CONNECT_TIMEOUT` 与尝试次数    |
| `riccon_model_credential_invalid_total{provider}`     | counter   | >0 增长                                   | 该 key 已 401/403 失效；管理端轮换/停用后恢复                       |
| `riccon_model_credential_cooldown_total{provider}`    | counter   | 短时激增                                  | 上游限流；检查配额/速率；避免降级风暴                               |
| `riccon_model_chain_empty_total{purpose}`             | counter   | **>0 即告警**                             | 链为空/全链模型停用；立即补链或 `BASELINE`                          |
| `riccon_agent_runs_inflight`                          | gauge     | 接近 `RICCON_CHAT_MAX_STREAMS`（基线 50） | 排队/限流；检查 Worker 是否积压                                     |
| `riccon_quota_rejections_total{scope,window}`         | counter   | 拒绝率 >5%/5min 或突增                    | 核对限额与误设；tenant/user 维度用审计定位                          |
| `riccon_quota_usage_ratio{scope,window}`              | gauge     | >0.9 持续 30 分钟                         | 提前通知租户/调高限额；denial-of-wallet 保护                        |
| `riccon_quota_store_failure_total{reason}`            | counter   | **>0 即告警**                             | 锁等待/死锁或 DB 故障；配额 fail-closed 会 503，先恢复 DB           |
| `riccon_quota_over_limit_observed_total`（如适用）    | counter   | `false` 期间持续增长                      | 说明只计量期间已超限；评估是否切 `true`                             |
| `riccon_cost_report_queries_total{group_by}`          | counter   | 成本查询 p95 >2s                          | 检查索引/范围（≤180 天）/并发；必要时加缓存                         |
| `http.server.requests`（框架）                        | histogram | API p95 >500ms                            | 见性能预算；先排除模型/外部调用                                     |

`scripts/ops/metrics-check --base-url <Worker>` 只断言 Worker 7 个指标；API 2 个主指标 + 3 个辅助指标
由 V2（`OPS-M04-02b`）核对。

### M04-15 故障与恢复

| 现象                                   | 可能原因                                           | 处置                                                                                                               |
| -------------------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| J1/J2 返回 503（链空）                 | 路由链为空/模型全停用/端点未放行                   | 管理端补链或 `RICCON_MODEL_ROUTING_MODE=BASELINE`；查 `riccon_model_chain_empty_total`                             |
| 请求持续 401/403 后切备用              | 主 key 失效/被上游拒绝                             | 管理端轮换或停用失效 key；`credential_invalid_total` 定位 provider                                                 |
| 429 但租户未超限                       | 用户维度限额更小/窗口边界                          | `GET /api/v1/quotas`、`/quotas/users` 核对；审计 `quota.exceeded` 的 `scopeType/window`                            |
| 503 `RICCON-QUOTA-503`                 | 配额存储失败/锁等待/死锁                           | 先恢复 DB；不建议自动重试（可能重复建 run）；核对 `innodb_lock_wait_timeout` 与事务超时                            |
| 开关 PUT 409 `ENV_OVERRIDE_ACTIVE`     | env 覆盖了 DB 开关                                 | 变更部署 env 并重启；或移除 env 后由 DB 接管（写审计 `source=DEPLOYMENT`）                                         |
| 成本页 503                             | 查询超时/DB 不可用                                 | 缩小范围（≤180 天）、核对索引；`cost_report_queries_total` 与日志                                                  |
| 成本金额与账单差异                     | 未定价/usage 缺失/混合版本窗口                     | 查 `cost_status` 分布、`unbucketedCalls`、`currencyMismatchCalls`；回填后按 M04-13 对账                            |
| Worker 启动失败 `RICCON-WORKER-001`    | schema ≠11（DB 已升未切/版本错）                   | 按 M04-1…6 顺序切换；**不要**用关闭校验绕过（仅回滚窗口，见 M04-7）                                                |
| 回滚后旧 Worker 无 `RICCON-WORKER-002` | WARN 已实现（TASK-0127/EV-0591），但开关或版本不对 | 确认 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 且为 M04 前最后一个 Worker 版本；该 WARN 必须逐字出现恰好一次 |

### M04-16 性能预算与 CI perf job

| 项           | 预算                                         | 说明                                         |
| ------------ | -------------------------------------------- | -------------------------------------------- |
| 首字延迟 p95 | ≤3s（假模型 `RICCON_FAKE_MODEL=1`，50 并发） | 取运行详情 `modelInvocations[].firstTokenMs` |
| API p95      | ≤500ms（不含模型/外部 MCP）                  | 框架 `http.server.requests`                  |
| 并发 run     | 50（单 Worker 实例基线）                     | `RICCON_CHAT_MAX_STREAMS=50`、DB 池 ≥25      |
| 降级成功率   | 100%（备用可用时）                           | 超时/5xx/429/key 失效矩阵                    |
| 配额超卖     | 0（限额边界 50 并发恰好 1 成功）             | `PERF-M04-002`                               |
| 成本查询 p95 | ≤2s                                          | `cost` 场景                                  |

- 运行入口固定 `node scripts/real-stack/run.mjs --mode=perf --perf-modes=server_error,rate_limited,unauthorized,timeout`；
  报告 `perf-m04-matrix.json` 记录主机 CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令与 OpenSearch 排除原因。
- 主机规格：2C4Gi 沙箱不足（perf 模式不启 OpenSearch/Web）；基线按 ≥4C8Gi 记录实际值，跨主机比较前必须
  对齐 `real-stack-env.json` 的 `host` 与 `perfProfile`。
- **CI perf job 是唯一发布门禁**：nightly 定期 + 发布前必跑；本地 `--mode=perf` 只算预检，不得替代 CI/nightly。
  job 定义见 `.github/workflows/ci.yml` 的 `perf-m04`，产物上传 `perf-m04-matrix.json` 与各场景日志。

## 生产

M09 交付。发布前必须满足 T4 要求：

- 威胁模型、k6 负载测试、回滚演练、Trivy 扫描均有通过证据；
- 生产发布、破坏性操作和花钱由用户明确批准；
- 密钥来自密钥管理服务，不写入 Helm values 或镜像。

## 回滚

1. **应用回滚**：回滚到上一个 `-exec.jar` 或 Helm revision；API 与 Worker 可独立回滚。M04 的 Worker 回滚必须先收敛多 key 并以 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动，见“M04-7 回滚”。
2. **数据库**：Flyway 只向前迁移，不提供 `undo`。破坏性变更必须预先准备反向 SQL 与备份；回滚前先停止写入。V10 回滚还必须由维护账号删除 `flyway_schema_history` 的 V10 行并校验 `MAX(version)=9`，详见“V10 生产迁移（OPS-007）”；V11 回滚见“M04-7 回滚”。
3. **对象存储**：产物按版本保留；回滚不删除已写入对象，避免破坏引用。
4. **验证**：回滚后执行健康检查、冒烟测试与关键旅程 E2E，并记录证据。

## 恢复

- **API 无法启动**：检查 `RICCON_DB_*`、Flyway 迁移状态、端口占用；先恢复依赖再重启。
- **Worker 无法启动**：检查 `RICCON_WORKER_PORT`、模型/MCP 出网允许列表与凭据；Worker 不健康不应阻塞 API 登录。
- **Worker 报 `RICCON-WORKER-001`（schema 版本不符）**：确认迁移已成功且 worker 镜像期望版本等于 DB（M04 起为 11）；**DB 版本高于 worker 期望时禁止启动旧 worker**，必须按“V10 生产迁移”/“M04-1…6 发布顺序”协调切换（或先回滚 API 与 DB 再回退 worker）。
- **M04 链空/降级/配额/成本故障**：按“M04-15 故障与恢复”定位；配额 fail-closed 会返回 503 `RICCON-QUOTA-503`，不要用自动重试掩盖。
- **Worker 无法领取任务**：检查 MySQL 连接、`agent_run_outbox` 索引与租约时间、服务令牌密钥；不要让 API 直接执行 Agent。
- **Spring Session 表缺失**：确认 Flyway V7 已应用且 `spring.session.jdbc.initialize-schema=never`；恢复数据库备份时不要遗漏 `SPRING_SESSION*`。
- **OpenSearch 不可用**：M02 前不影响 API 启动；M05 起检索降级策略另行定义。
- **对象存储不可用**：产物上传失败必须显式报错，不允许静默丢弃。
- **审计写入失败**：业务操作失败并告警；禁止降级为只写日志。

## 决策

生产发布、破坏性操作、花钱和风险例外需要用户明确批准。架构变更（技术栈、数据模型、公开契约、认证/安全、部署拓扑）走决策包与 ADR。
