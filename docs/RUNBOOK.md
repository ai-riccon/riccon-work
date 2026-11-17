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
# 1. 启动测试栈
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait

# 2. 授予审计触发器所需权限（MySQL 8.4 默认开启 binlog；测试容器重启后需重设）
#    生产等价要求：迁移账号具备 TRIGGER，且 log_bin_trust_function_creators=1 或等价权限。
docker exec riccon-test-mysql mysql -uroot -p"${RICCON_DB_ROOT_PASSWORD:-riccon_root_test_only}" \
  -e "SET GLOBAL log_bin_trust_function_creators=1"

# 3. 启动 API（默认端口 8080，连接 13306 的 MySQL；启动时执行 Flyway V1–V7）
./mvnw -q -pl apps/api spring-boot:run

# 4. 启动 Worker（默认端口 8081；M01 无数据库依赖，M02 持久化实现任务加入 JDBC 后需要 MySQL）
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
