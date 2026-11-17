# ADR-0010：Flyway 单一迁移者与 schema 版本校验

Status: accepted
Date: 2026-11-17

## Context

`apps/api` 与 `apps/worker` 都要访问 MySQL：API 执行业务读写与审计，Worker 执行任务、写运行状态与审计。合同规定“仅 apps/api 执行迁移，worker 关闭 Flyway 并校验版本”。如果两个部署物都能迁移，会出现启动竞态、重复执行、锁等待和“旧版本 Worker 用新 schema 跑”的不可控状态。M02 将引入第一批业务表（会话、文件、任务、审计、outbox），必须先把迁移所有权和升级顺序固定下来。

## Decision

### 1. 唯一迁移者：`apps/api`

- 迁移文件只存在于 `apps/api/src/main/resources/db/migration`，命名 `V{n}__{snake_case_描述}.sql`，`n` 从 1 连续递增（当前只支持整数版本号）。
- `apps/api` 配置：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    clean-disabled: true
    out-of-order: false
    baseline-on-migrate: false
```

- 不自动执行 `repair`；`clean` 永久禁用。已应用的迁移文件禁止修改（checksum 会失败）。
- 迁移在 API 启动时执行；同一时间只允许一个 API 实例迁移（Flyway 使用数据库锁），滚动发布时先启动的新实例完成迁移后再接收流量。

### 2. Worker 只校验，不迁移

- Worker 设置 `spring.flyway.enabled=false`，不包含 `db/migration` 资源，不依赖 Flyway 执行迁移。
- Worker 启动时执行 `SchemaVersionCheck`（`ApplicationRunner` 或 `SmartInitializingSingleton`，在 Web 端口接受流量前）：
  1. 查询 `flyway_schema_history` 是否存在；
  2. 取成功记录的最大版本 `MAX(CAST(version AS UNSIGNED))`（`version` 为字符串列，禁止直接字典序比较）；
  3. 与 `platform-common` 中的 `SchemaVersion.EXPECTED` 比较；
  4. 表缺失、查询失败或版本不符 → 抛异常，进程非零退出。日志只输出 `expected`/`actual` 与错误码，不含连接串。
- Worker 不使用 `flyway-core` 做该校验，直接用 JDBC 查询，避免把迁移工具带进运行时。

### 3. 版本号单一事实源

- `platform-common` 提供 `SchemaVersion.EXPECTED`（整数常量）。
- `apps/api` 的测试 `MigrationVersionTest` 扫描 `db/migration` 文件名，断言最大版本 == `SchemaVersion.EXPECTED`；新增迁移必须同步修改常量，否则 `./mvnw verify` 失败。
- `apps/worker` 的测试 `SchemaVersionCheckTest` 断言：缺少表、版本落后、版本超前三种情况均使启动失败。
- CI 增加目录守卫：`apps/worker/src/main/resources/db/migration` 必须不存在；新增迁移文件必须只出现在 `apps/api`。

### 4. 迁移编写规则（向前兼容）

- 只做向前兼容变更：新增列允许 `NULL` 或带默认值；新增表/索引可随时添加。
- 重命名、删列、改类型、收紧约束必须分两次发布（expand → contract）：
  1. 第一次：新增结构并双写/回填，旧代码仍可运行；
  2. 第二次（至少一个 release 后）：移除旧结构。
- 破坏性变更（删表、删列、改类型、大表回填）必须有 ADR、回滚脚本和 sandbox 演练记录；回滚以“前向修复 + 备份恢复”为主，不依赖 Flyway undo。
- 禁止在迁移中执行 `DROP DATABASE`、跨库写入、依赖生产数据或调用外部服务。
- 审计表迁移不得包含 `DELETE`/`UPDATE` 数据；保留清理由 M09 维护账号按分区执行（ADR-0007）。
- 大表变更必须评估锁表时间并写入 `docs/RUNBOOK.md` 的操作窗口。

### 5. 部署顺序与滚动升级

1. 部署新 `apps/api`（执行迁移），健康检查通过；
2. 再滚动 `apps/worker`；
3. 新 schema 必须兼容上一版本 Worker（N/N-1）至少一个 release；
4. 破坏性收缩只能在旧 Worker 全部下线后执行。

Worker 启动版本校验保证“schema 未迁移时不启动”，避免新 Worker 撞旧表；API 的 `validate-on-migrate` 保证迁移文件与数据库一致。

### 6. 回滚策略

- 应用回滚不回滚数据库；旧版本应用必须能在新 schema 上运行（向前兼容规则保证）。
- 数据损坏或迁移失败：停止写入 → 从最近备份恢复或执行预写的补偿脚本 → 记录证据；禁止直接 `flyway clean`。
- 每次发布保留迁移脚本与 checksum 记录；回滚演练在 M09 完成（B-13）。

## Consequences

- 正面：只有一个迁移写入者，消除竞态；Worker 不会在错误 schema 上运行；版本号有测试守卫，不会被遗忘。
- 负面：API 启动承担迁移时间；Worker 启动多一次数据库查询；版本常量需要随迁移手工更新（由测试强制）。
- 约束：新增部署物若需要数据库，必须复用同一迁移链并实现版本校验，不得各自迁移；不得在 Worker 中放迁移文件。

## Alternatives

- **两个部署物都启用 Flyway**：Flyway 有锁，但仍会出现旧 Worker 启动时把 schema 降级/报错、以及谁负责迁移的职责不清；合同已明确禁止。
- **独立迁移 Job（K8s Job / 一次性容器）**：职责最清晰，适合 M09 生产；但 M02 单机 Compose 下增加部署物与编排复杂度，暂不采用。M09 可切换为 Job，迁移文件仍归 `apps/api`。
- **Liquibase**：功能相当，但合同已选 Flyway，且现有配置与文档均基于 Flyway。
- **启动时自动建表（Hibernate ddl-auto）**：不可审计、不可回滚，T4 禁止。
- **Worker 只读不校验版本**：会把 schema 不匹配变成运行期偶发错误，难以定位。

## Evidence

- `apps/api/src/main/resources/application.yml` 的 Flyway 配置（已有 `validate-on-migrate: true`，M02 补齐其余项）
- `apps/worker/src/main/resources/application.yml` 的 `spring.flyway.enabled=false`（M02）
- `platform/platform-common` 的 `SchemaVersion`（M02）
- 测试（M02）：`MigrationVersionTest`、`SchemaVersionCheckTest`
- CI 目录守卫（M02）
- `docs/security/THREAT-MODEL.md` B-13；`docs/CODING-STANDARDS.md` 第 11 节
