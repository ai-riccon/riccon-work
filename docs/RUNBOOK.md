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

## 本地运行

```bash
# 1. 启动测试栈（MySQL 已在 compose 中固定审计触发器所需参数，无需手工 SET GLOBAL）
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait

# 2. 启动 API（默认端口 8080，连接 13306 的 MySQL；启动时执行 Flyway V1–V7）
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

- 迁移只由 `apps/api` 执行；Worker 不包含迁移文件。
- V6 创建 `audit_event` 触发器；若 MySQL 返回 `ERROR 1419`，说明迁移账号缺少 `TRIGGER` 或 `log_bin_trust_function_creators` 未开启。不得删除触发器绕过。
- 已应用的迁移文件禁止修改；新增迁移同步 `SchemaVersion.EXPECTED`（ADR-0010）。
- Spring Session 表由 V7 创建，必须保持 `spring.session.jdbc.initialize-schema=never`，禁止应用自动建表。

#### 测试栈迁移演练（V1–V7，可复制）

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

# 4. 按文件顺序执行 V1..V7；任一步失败立即中断，不吞错误
set -euo pipefail
for migration in apps/api/src/main/resources/db/migration/V*.sql; do
  echo "== ${migration}"
  docker compose -f deploy/compose/docker-compose.test.yml exec -T \
    -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
    mysql mysql --user=riccon --database=riccon --batch --show-warnings < "${migration}"
done

# 5. 校验结构：36 张表、2 个触发器
docker compose -f deploy/compose/docker-compose.test.yml exec -T \
  -e MYSQL_PWD="${RICCON_DB_PASSWORD:-riccon_test_only}" \
  mysql mysql --user=riccon --database=riccon --batch -e "
    SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='riccon';
    SELECT COUNT(*) AS trigger_count FROM information_schema.triggers WHERE trigger_schema='riccon';
    SELECT trigger_name, event_manipulation FROM information_schema.triggers WHERE trigger_schema='riccon';"

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

| 检查项                            | 期望                                                                            |
| --------------------------------- | ------------------------------------------------------------------------------- |
| `log_bin_trust_function_creators` | `ON`                                                                            |
| 迁移输出                          | V1–V7 逐条无错误；不得出现 `ERROR 1419`                                         |
| `table_count`                     | 36（手工重放不含 `flyway_schema_history`；API 启动后由 Flyway 建表，因此为 37） |
| `trigger_count`                   | 2（`trg_audit_event_no_update`、`trg_audit_event_no_delete`）                   |
| UPDATE / DELETE                   | 均 exit 1，`ERROR 1644 (45000): audit_event is append-only`，行仍存在           |

手工重放只用于演练与 CI 冒烟，不会写入 `flyway_schema_history`。**不要在同一数据卷上随即启动 API**：Flyway 会因“非空 schema 但无历史表”拒绝迁移；需要继续用 API 验证时先 `down -v` 再 `up -d --wait`。

CI 的 `backend` job 使用同一个 compose 文件和同一文件顺序做冒烟（`.github/workflows/ci.yml` 的“迁移冒烟”步骤），并在跑 `./mvnw -q -T1C verify` 之前执行。

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

## 生产

M09 交付。发布前必须满足 T4 要求：

- 威胁模型、k6 负载测试、回滚演练、Trivy 扫描均有通过证据；
- 生产发布、破坏性操作和花钱由用户明确批准；
- 密钥来自密钥管理服务，不写入 Helm values 或镜像。

## 回滚

1. **应用回滚**：回滚到上一个 `-exec.jar` 或 Helm revision；API 与 Worker 可独立回滚。
2. **数据库**：Flyway 只向前迁移，不提供 `undo`。破坏性变更必须预先准备反向 SQL 与备份；回滚前先停止写入。
3. **对象存储**：产物按版本保留；回滚不删除已写入对象，避免破坏引用。
4. **验证**：回滚后执行健康检查、冒烟测试与关键旅程 E2E，并记录证据。

## 恢复

- **API 无法启动**：检查 `RICCON_DB_*`、Flyway 迁移状态、端口占用；先恢复依赖再重启。
- **Worker 无法启动**：检查 `RICCON_WORKER_PORT`、模型/MCP 出网允许列表与凭据；Worker 不健康不应阻塞 API 登录。
- **Worker 无法领取任务**：检查 MySQL 连接、`agent_run_outbox` 索引与租约时间、服务令牌密钥；不要让 API 直接执行 Agent。
- **Spring Session 表缺失**：确认 Flyway V7 已应用且 `spring.session.jdbc.initialize-schema=never`；恢复数据库备份时不要遗漏 `SPRING_SESSION*`。
- **OpenSearch 不可用**：M02 前不影响 API 启动；M05 起检索降级策略另行定义。
- **对象存储不可用**：产物上传失败必须显式报错，不允许静默丢弃。
- **审计写入失败**：业务操作失败并告警；禁止降级为只写日志。

## 决策

生产发布、破坏性操作、花钱和风险例外需要用户明确批准。架构变更（技术栈、数据模型、公开契约、认证/安全、部署拓扑）走决策包与 ADR。
