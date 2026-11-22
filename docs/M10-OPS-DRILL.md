---
title: M10 OPS-004..007 测试环境演练记录
module_id: M10
task_id: TASK-0083
author: riccon/verifier
created_at: "2026-11-19T16:05:00+08:00"
environment: "测试环境（Testcontainers MySQL 8.4 + Compose 测试栈）；不是生产"
---

# M10 OPS-004..007 测试环境演练记录

本文件是 OPS 演练记录，不属于 `.riccon/reviews/` 模块评审。本记录只覆盖**非生产**演练。V10 生产执行仍需要 OPS 运行手册、备份、维护窗口与用户按合同
`decision_owners.production` 的 T4 批准；本文件不构成生产发布批准。

## 1. 环境与边界

- 迁移演练使用 `V10MigrationTest` 的 Testcontainers MySQL 8.4 一次性容器；容器销毁即清理，
  不连接 Compose 热栈，更不连接生产。
- 真实栈（`scripts/real-stack/run.mjs`）只在本地进程与 Compose 测试栈运行，worker 在
  schema 10 上启动并通过 `/actuator/health`。
- 未在共享热栈执行回滚或删 `flyway_schema_history`；回滚只在一次性容器内验证。

## 2. OPS-004 协调切换

**结论：schema 前置已由测试验证；完整停写/起停编排未在测试环境复制。**

- 代码事实：`SchemaVersionChecker` 严格比较 `MAX(success version)=10`；DB=10 时旧 worker
  （expected=9）重启会以 `RICCON-WORKER-001` 失败，因此生产必须按 RUNBOOK 的
  “停旧 worker → 停 API 写 → 迁移 → 起新 API → 起新 worker”协调切换，不能纯滚动。
- 测试证据：`SchemaVersionCheckerTest` 覆盖匹配通过、历史表缺失、版本落后/超前、无成功迁移；
  真实栈 worker 在 V10 库上健康。
- 未执行：真实 Kubernetes/Compose 多实例的滚动抑制与维护窗口切换；该编排属于 OPS/M09，
  需要生产批准，本演练不冒充已验证。

## 3. OPS-005 索引 DDL

**结论：测试通过。**

- `V10MigrationTest.migration_indexDdlIsSingleInplaceStatement` 读取真实 V10 文件，去注释后断言
  只有一条 `ALTER TABLE audit_event`，同一语句包含两个索引与 `ALGORITHM = INPLACE`、
  `LOCK = NONE`。
- `V10MigrationTest.migration_createsAuditQueryIndexesForPartitionedTable` 在分区表上断言索引
  列序与可被 `EXPLAIN` 使用，验证 MySQL 8 原子 DDL 后两条索引同时存在。
- 生产仍按 RUNBOOK 维护窗口停写执行；online DDL 只作为演练确认后的加速手段。

## 4. OPS-006 失败恢复矩阵

**结论：测试通过（索引失败注入 + repair 重跑）。**

- `V10MigrationTest` 用重名索引制造索引 ALTER 失败（`failureInjection_recoversWithRepairAndRerun`），验证权限/表前缀残留可识别；清理冲突后
  `flyway repair` 重跑成功，最终表、两条索引与权限回填完整。
- V10 文件头的失败矩阵与 RUNBOOK 一致：CREATE TABLE / 权限 INSERT / 角色回填 / 索引 ALTER
  四类失败分别给出残留与恢复路径；禁止修改已应用 V10 的 checksum。

## 5. OPS-007 回滚

**结论：一次性容器回滚通过。**

- `V10MigrationTest.rollback_returnsToV9AndKeepsDataReadable` 在 V1–V9 seed + V10 后执行 ADR-0020 §5.4 回滚：
  `DROP TABLE audit_export_request`、删除两个索引、删除角色映射与两条权限，并由维护账号删除
  `flyway_schema_history` 的 V10 行；断言 `MAX(version)=9`、V10 重新 pending、M02/M03 数据与
  审计仍可读。
- 未执行：生产结构回滚与 OIDC 账号恢复演练（OPS-010）。回滚前必须先解绑/写回本地密码并列出
  每个租户 `auth_source=OIDC` 的 `TENANT_ADMIN`；该清单属于 OPS/M09 发布流程，当前为残余风险。

## 6. 残余与未验证

1. 生产协调切换、维护窗口、备份恢复、K8s Job 化迁移：未执行（OPS/M09，需 T4 批准）。
2. OIDC 代码回滚导致已绑定账号无本地密码：未演练，OPS-010 仍 open。
3. 旧 worker 在 DB=10 上启动失败的实机演练：未执行；由 `SchemaVersionCheckerTest` 与代码评审
   证明该行为，避免在共享测试栈制造不可用窗口。
4. `audit_export_request` 清理索引（OPS-008）仍由 M09 承接。

**演练结论：OPS-005/006/007 的测试环境可复现证据通过；OPS-004 的 schema 前置通过，完整编排与
OPS-010 仍是生产发布前置，不由本记录关闭。**
