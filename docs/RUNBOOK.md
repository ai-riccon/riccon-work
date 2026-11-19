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

M12 起本地内层循环可显式复用同一套 compose 服务；默认、CI 与模块 gate 不启用。启动/停止/重置统一通过脚本操作，不另造服务：

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
- 热栈只用于 L1 内层循环，不能作为任务完成或模块 gate 证据；L2/L3 仍按 `docs/CODING-STANDARDS.md` §10.1 运行合同命令并由 verifier 独立取证。
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

## 本地运行

```bash
# 1. 启动测试栈（MySQL 已在 compose 中固定审计触发器所需参数，无需手工 SET GLOBAL）
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait

# 2. 启动 API（默认端口 8080，连接 13306 的 MySQL；启动时执行 Flyway V1–V10）
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

#### 测试栈迁移演练（V1–V10，可复制）

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

# 4. 按文件顺序执行 V1..V10；任一步失败立即中断，不吞错误
set -euo pipefail
for migration in apps/api/src/main/resources/db/migration/V*.sql; do
  echo "== ${migration}"
  docker compose -f deploy/compose/docker-compose.test.yml exec -T \
    -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
    mysql mysql --user=riccon --database=riccon --batch --show-warnings < "${migration}"
done

# 5. 校验结构：39 张表（Flyway 口径 40）、2 个触发器、V10 对象
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
| 迁移输出                          | V1–V10 逐条无错误；不得出现 `ERROR 1419`                                                                 |
| `table_count`                     | 39（手工重放不含 `flyway_schema_history`；API 经 Flyway 启动后为 40，与 `ApiApplicationTests` 口径一致） |
| `trigger_count`                   | 2（`trg_audit_event_no_update`、`trg_audit_event_no_delete`）                                            |
| `v10_tables` / `v10_indexes`      | 1（`audit_export_request`）/ 2（两条 `audit_event` 复合索引）                                            |
| `v10_permissions`                 | 2（`audit.export.approve`、`platform.audit.export.approve`）                                             |
| UPDATE / DELETE                   | 均 exit 1，`ERROR 1644 (45000): audit_event is append-only`，行仍存在                                    |

手工重放只用于演练与 CI 冒烟，不会写入 `flyway_schema_history`。**不要在同一数据卷上随即启动 API**：Flyway 会因“非空 schema 但无历史表”拒绝迁移；需要继续用 API 验证时先 `down -v` 再 `up -d --wait`。

CI 的 `backend` job 使用同一个 compose 文件和同一文件顺序做冒烟（`.github/workflows/ci.yml` 的“迁移冒烟”步骤），并在跑 `./mvnw -q -T1C verify` 之前执行。表数不写死：期望值从 `apps/api/src/main/resources/db/migration/V*.sql` 的 `CREATE TABLE` 数量推导，并额外断言 V10 表、索引、权限与 append-only 触发器。

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

## 生产

M09 交付。发布前必须满足 T4 要求：

- 威胁模型、k6 负载测试、回滚演练、Trivy 扫描均有通过证据；
- 生产发布、破坏性操作和花钱由用户明确批准；
- 密钥来自密钥管理服务，不写入 Helm values 或镜像。

## 回滚

1. **应用回滚**：回滚到上一个 `-exec.jar` 或 Helm revision；API 与 Worker 可独立回滚。
2. **数据库**：Flyway 只向前迁移，不提供 `undo`。破坏性变更必须预先准备反向 SQL 与备份；回滚前先停止写入。V10 回滚还必须由维护账号删除 `flyway_schema_history` 的 V10 行并校验 `MAX(version)=9`，详见“V10 生产迁移（OPS-007）”。
3. **对象存储**：产物按版本保留；回滚不删除已写入对象，避免破坏引用。
4. **验证**：回滚后执行健康检查、冒烟测试与关键旅程 E2E，并记录证据。

## 恢复

- **API 无法启动**：检查 `RICCON_DB_*`、Flyway 迁移状态、端口占用；先恢复依赖再重启。
- **Worker 无法启动**：检查 `RICCON_WORKER_PORT`、模型/MCP 出网允许列表与凭据；Worker 不健康不应阻塞 API 登录。
- **Worker 报 `RICCON-WORKER-001`（schema 版本不符）**：确认 V10 迁移 Job 已成功且 worker 镜像为期望 10 的版本；**DB 为 10 时禁止启动旧 worker**，必须按“V10 生产迁移”的协调顺序切换（或先回滚 API 与 DB 再回退 worker）。
- **Worker 无法领取任务**：检查 MySQL 连接、`agent_run_outbox` 索引与租约时间、服务令牌密钥；不要让 API 直接执行 Agent。
- **Spring Session 表缺失**：确认 Flyway V7 已应用且 `spring.session.jdbc.initialize-schema=never`；恢复数据库备份时不要遗漏 `SPRING_SESSION*`。
- **OpenSearch 不可用**：M02 前不影响 API 启动；M05 起检索降级策略另行定义。
- **对象存储不可用**：产物上传失败必须显式报错，不允许静默丢弃。
- **审计写入失败**：业务操作失败并告警；禁止降级为只写日志。

## 决策

生产发布、破坏性操作、花钱和风险例外需要用户明确批准。架构变更（技术栈、数据模型、公开契约、认证/安全、部署拓扑）走决策包与 ADR。
