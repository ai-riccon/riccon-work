---
review_id: REV-TASK-0033-002
module_id: M03
task_id: TASK-0033
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0034_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [6b41244, d468a8d, 1a16ee4]
head_commit: 59d8f4c
previous_review: REV-TASK-0033
created_at: 2026-11-18T15:51:13+08:00
evidence:
  - EV-0304
  - EV-0306
findings:
  - id: F-001
    severity: P1
    status: fixed
    summary: "B1a/B1b 拆分：B1a 不碰 V9；B1b 依赖 B4，在 B3/B4 写 visibility 后收紧；运行前提与旧 V9→V10 例外已写入"
  - id: F-002
    severity: P1
    status: fixed
    summary: "AuditDetailsPolicy/ErrorCodes 进入 B1a 写范围；B2 断言 409 code=RICCON-DEPT-409；无 B2 字面量路径"
  - id: F-003
    severity: P2
    status: accepted
    summary: "PRODUCT 仍未同步；ADR-0018 §8 已登记 AC-07 平台管理员演员、AC-21 仅部门管理页段、AC-08..18 归 M10；文档漂移仍在"
  - id: F-004
    severity: P2
    status: accepted
    summary: "历史 TENANT 风险仍在；ADR-0018 §8 约定 E2E 用无历史 TEAM 空间租户、遗留租户登记 R-11；AC-03 不声称历史数据字面成立"
  - id: F-005
    severity: P2
    status: accepted
    summary: "目标租户 M10 审计可见性已写入 ADR-0018 §8/ADR-0007/威胁模型 R-14，但 PRODUCT M10 AC 未更新；M10 任务卡待 Lead 承接"
  - id: F-006
    severity: P2
    status: fixed
    summary: "OpenAPI 删除未实现的 DELETE /workspaces/{id}，保留 workspace-archive deferred；lint exit 0"
  - id: F-007
    severity: P2
    status: fixed
    summary: "B3/B4 验收明确删除 FileJdbc.WORKSPACE_VISIBLE、ConversationJdbc owner-only、RunDetailService CHAT owner-only，并由 DEPT-002/003/004 覆盖"
  - id: F-008
    severity: P2
    status: fixed
    summary: "B1b 明确 V1–V8 seed→V9、权限/FK/CHECK 断言与回滚演练，写范围含 migration 测试；执行待 B1b"
  - id: F-009
    severity: P2
    status: accepted
    summary: "V9 未发布前直接修订可接受；已写非生产库、排空旧 API、旧 V9→V10、禁 flyway repair 前提"
  - id: F-010
    severity: P2
    status: fixed
    summary: "依赖图 B1a→B2→B3→B4→B1b，B5 在 B2/B3 后，V 最后；B2 自清理 CUSTOM 行 + OrganizationDirectory 消除 B2/B3 循环"
  - id: F-011
    severity: P3
    status: fixed
    summary: "威胁模型 A-01/S-07-1/I-08-1 改 M10；R-15 改为平台管理员可移交；R-14/M10 行承接平台来源部门事件可见性"
  - id: F-012
    severity: P3
    status: fixed
    summary: "ADR-0007 第 2/6/7 节改 M10；平台来源部门事件可见模型留给 M10"
  - id: F-013
    severity: P3
    status: fixed
    summary: "ADR-0018 §8 明确 E2E-M03-07 在 M03 只验部门管理页段；PRODUCT 原文漂移归入 F-003"
  - id: F-014
    severity: P3
    status: fixed
    summary: "ADR-0014 补充同源 content 读取例外与 2 MiB/413/no-store 约束"
  - id: F-015
    severity: P3
    status: fixed
    summary: "B1a 明确同步 PermissionCodes 注释（V2 + V9 新增码）；实际代码注释待 B1a 执行"
  - id: F-016
    severity: P2
    status: open
    summary: "B2 验收按分号计 8 项、M03-V 7 项，超过 acceptance ≤6；Lead 建任务卡前需合并或拆分"
  - id: F-017
    severity: P2
    status: open
    summary: "旧 V9→V10 例外未同步 SchemaVersionChecker/§6.4：若触发 V10，worker 仍期望版本 9；需条件任务与版本常量"
  - id: F-018
    severity: P3
    status: open
    summary: "B4 验收写“消息”但未点名 ChatSubmissionService.findOwnedForUpdate；共享会话发送仍可能 owner-only，建议任务卡补一条"
---

# TASK-0033 复审（REV-TASK-0033-002）：F-001/F-002 修正与文档收口

## 结论

**verdict: pass**（无开放 P0/P1）。上一轮的两个 P1 已按可执行的方式关闭：

- **F-001 已关闭**：ADR-0018 §6.2/§8 把 V9 收紧从首任务拆为 **B1b**，依赖 **B3/B4 完成**；B1a 明确不碰 V9；B1b 写范围只含 V9、migration 测试目录与 `ApiApplicationTests`；非生产库、排空旧 API、已应用旧 V9 时改用 V10、禁止 `flyway repair` 的运行前提均已写入。依赖图 `B1a→B2→B3→B4→B1b`、`B5` 在 B2/B3 后、`M03-V` 最后，自洽。
- **F-002 已关闭**：`AuditDetailsPolicy`、`ErrorCodes` 进入 **B1a** 写范围，`AuditDetailsPolicyTest`/`RoleDefinitionsTest` 同步；B2 `DepartmentAdminApiTest` 必须断言 `409` 的 `code=RICCON-DEPT-409`。B1a 提供常量后，B2 不需要新建异常类写字符串字面量。

`1a16ee4` 只改 `contracts/openapi.yaml`、`docs/ARCHITECTURE.md`、ADR-0007/0014/0018 与威胁模型；V9、PRODUCT、AGENTS 未变。OpenAPI 删除未实现的 workspace DELETE 后 lint 通过；M10 deferred 项仍保留。

仍有 **2 个开放 P2**（F-016 任务验收计数、F-017 V10 例外版本常量）与 **1 个 P3**（F-018 共享会话发送入口点名），均已写明承接位置，不阻断 B1a 启动；F-003/F-004/F-005 的 PRODUCT 文档漂移是 **accepted/deferred，不是 fixed**。

## 独立验证（干净树，HEAD 59d8f4c）

| 命令                                                                                                                    | 退出码 | 结果                                              |
| ----------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------- |
| `git status --short`                                                                                                    | 0      | 空；HEAD `59d8f4c`                                |
| `git show --stat 1a16ee4`                                                                                               | 0      | 仅 6 个 docs/contracts 文件；无 V9/PRODUCT/AGENTS |
| `git diff --stat d468a8d..1a16ee4 -- apps/api/src/main/resources/db/migration/V9__organization_and_workspace_scope.sql` | 0      | **空**；V9 未改，EV-0304 的空库迁移测试仍适用     |
| `git diff --stat d468a8d..1a16ee4 -- docs/PRODUCT.md AGENTS.md`                                                         | 0      | **空**                                            |
| `npx --yes @redocly/cli@1.34.3 lint contracts/openapi.yaml`                                                             | 0      | valid；本轮独立 EV 见回执/`EV-0307`               |
| `riccon contract validate --strict --project .`                                                                         | 0      | 0 errors, 0 warnings                              |

未跑 full verify、V9 迁移测试、E2E、security、axe：按提速要求，本轮只审计划与契约；V9 未变的 diff 是复用 EV-0304 的依据。

## F-001..F-018 对账

| ID        | 上一轮      | 本轮                         | 证据/承接位置                                                                                                                                                                                                 |
| --------- | ----------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001     | P1 open     | **fixed**                    | ADR-0018 §6.2 第 296–318 行；§8 第 409–432 行；B1b 写范围仅 V9 + migration 测试 + `ApiApplicationTests`                                                                                                       |
| F-002     | P1 open     | **fixed**                    | B1a 行第 426 行：`AuditDetailsPolicy.java`、`ErrorCodes.java`、两个测试；B2 行第 427 行要求 409 code；§8 第 436 行说明归属                                                                                    |
| F-003     | P2 open     | **accepted（未修 PRODUCT）** | ADR-0018 §8 第 437 行登记 AC-07 演员、AC-21 分段、AC-08..18 归 M10；`docs/PRODUCT.md` diff 为空，文档漂移仍在                                                                                                 |
| F-004     | P2 open     | **accepted（未修 PRODUCT）** | ADR-0018 §8 第 438 行：E2E 用无历史 TEAM 空间租户；遗留 TENANT 租户登记 R-11；AC-03 不声称历史数据字面成立                                                                                                    |
| F-005     | P2 open     | **accepted（M10 承接）**     | ADR-0018 §8 第 439 行；ADR-0007 §6 第 107 行；威胁模型 R-14/M10 行；PRODUCT M10 AC 仍未更新                                                                                                                   |
| F-006     | P2 open     | **fixed**                    | OpenAPI 删除 `deleteWorkspace`；`workspace-archive` 仅保留顶层 deferred；lint exit 0                                                                                                                          |
| F-007     | P2 open     | **fixed（计划）**            | B3 第 428 行“workspace 包内无第二份可见性 SQL”；B4 第 429 行明确删除 `FileJdbc.WORKSPACE_VISIBLE`、`ConversationJdbc.findOwned/listOwned` 唯一判据、`RunDetailService` CHAT owner-only；DEPT-002/003/004 覆盖 |
| F-008     | P2 open     | **fixed（计划）**            | B1b 第 430 行含 V1–V8 seed、权限/FK/CHECK、回滚；§6.2 第 312–313 行；执行证据待 B1b                                                                                                                           |
| F-009     | P2 accepted | **accepted**                 | §6.2 第 314–317 行：非生产库、停写/排空、旧 V9→V10、禁 `flyway repair`；第 318 行保留“未发布不新增 V10”默认路径                                                                                               |
| F-010     | P2 open     | **fixed（计划）**            | §8 依赖图第 413–422 行；B2 自清理 CUSTOM 行、`OrganizationDirectory` 由 B2 实现/B3 消费；B5/V 顺序                                                                                                            |
| F-011     | P3 open     | **fixed**                    | 威胁模型 diff：A-01/S-07-1/I-08-1 改 M10；R-15 改平台管理员；R-14/M10 行承接平台来源事件                                                                                                                      |
| F-012     | P3 accepted | **fixed**                    | ADR-0007 第 2/6/7 节改 M10；第 141 行变更说明                                                                                                                                                                 |
| F-013     | P3 accepted | **fixed（ADR 映射）**        | ADR-0018 §8 第 441 行：`E2E-M03-07` 在 M03 只验部门管理页；PRODUCT 原文漂移归 F-003                                                                                                                           |
| F-014     | P3 accepted | **fixed**                    | ADR-0014 第 35–36 行补同源 content 例外、2 MiB、413、no-store                                                                                                                                                 |
| F-015     | P3 accepted | **fixed（B1a 计划）**        | ADR-0018 §8 第 442 行；`PermissionCodes` 实际注释仍待 B1a 同步                                                                                                                                                |
| **F-016** | 新增        | **P2 open**                  | B2 验收第 427 行按分号计 8 项；V 第 432 行 7 项；均超过 acceptance ≤6（AGENTS 任务大小规则）。承接：Lead 建 B2/V 任务卡前合并/拆分                                                                            |
| **F-017** | 新增        | **P2 open**                  | §6.2 第 316 行要求旧 V9 环境新增 V10；但 §6.4 第 343 行仍写“不新增 V10，版本保持 9”，B1b 写范围不含 `SchemaVersionChecker`。承接：条件 V10 任务 + worker 版本常量，或明确旧 V9 环境不在 M03 范围              |
| **F-018** | 新增        | **P3 open**                  | B4 第 429 行写“所有…消息…”但未点名 `ChatSubmissionService.findOwnedForUpdate`；共享会话发送可能仍 owner-only。承接：B4 任务卡补一条                                                                           |

## AC 对账是否仍成立

| AC                      | 结论                 | 说明                                                                           |
| ----------------------- | -------------------- | ------------------------------------------------------------------------------ |
| M03-AC-01               | 通过                 | 平台管理员部门页、成员数/负责人、单主部门、409、跨租户账号 404 均在 B2/B5 承载 |
| M03-AC-02               | 通过                 | B3 `requireManageable` + B5 隐藏入口；非负责人 403/404                         |
| M03-AC-03               | 通过（限定干净租户） | 设计支持；历史 `TENANT` 例外按 F-004 accepted，E2E 用无历史 TEAM 空间租户      |
| M03-AC-04               | 通过（计划）         | B4 单一 `requireAccessible` + DEPT-002/003；旧 owner-only 删除已写入验收       |
| M03-AC-05               | 通过                 | 个人空间仅 owner；DEPT-004/IT-DEPT-003                                         |
| M03-AC-06               | 通过                 | ADR §3.4 固定文案 + 源文件 404；B5                                             |
| M03-AC-07               | 通过（演员替换）     | 平台管理员执行移出/移交；最终断言不变；F-003 记录 PRODUCT 未改                 |
| M03-AC-19（部门段）     | 通过                 | DEPT-001..010、TM-23..28                                                       |
| M03-AC-20               | 通过                 | TEN-001..027；M02 回归                                                         |
| M03-AC-21（部门管理页） | 通过（ADR 映射）     | M03 只验部门管理页；`E2E-M03-07` 分段；F-003 记录 PRODUCT 原文                 |
| M03-AC-08..18           | 不适用               | 归 M10；M03 契约未提前提供 OIDC/审计路径                                       |

## V9 未变证明与 OpenAPI 检查

- `git diff --stat d468a8d..1a16ee4 -- .../V9__organization_and_workspace_scope.sql` 为空；V9 仍为 expand（`visibility` 允许 NULL），EV-0304 的 Testcontainers MySQL 8.4 空库应用仍适用。
- `docs/PRODUCT.md`、`AGENTS.md` 在 `d468a8d..1a16ee4` 的 diff 为空；ADR-0018 仍 `Status: proposed`，Reviewers/Approved-by/Review-date 存在。
- `contracts/openapi.yaml`：`DELETE /api/v1/workspaces/{workspaceId}` 已删除；`grep -n "deleteWorkspace"` 无匹配；`x-riccon-deferred` 仍含 oidc-sso/audit-query/audit-export/platform-cross-tenant-audit 等 M10 项；redocly lint exit 0。
- `contracts/internal-api.yaml` 未变（`1a16ee4` 只改 openapi）。

## F-001 关闭细节

- ADR-0018 §6.2 第 300 行：B1a 不改 V9，V9 保持 expand，M02 `WorkspaceJdbc.insert` 仍可运行。
- 第 301 行：B3 把 `WorkspaceJdbc.insert` 改为写 `visibility`；B4 统一内容入口；B1b 才修改 V9。
- 第 312–313 行：B1b 必须 V1–V8 seed → V9、权限/FK/CHECK、回滚副本演练。
- 第 314–317 行：非生产库、停写/排空、旧 V9 持久环境改 V10、禁 `flyway repair`。
- §8 第 430 行：B1b 写范围仅 V9 + `apps/api/src/test/java/com/riccon/api/migration/**` + `ApiApplicationTests.java`；不含 `WorkspaceJdbc`，依赖 B3。
- 依赖图第 413–422 行：`B1a→B2→B3→B4→B1b`；B5 在 B2/B3 后；`B4+B1b+B5→M03-V`。与 §6.2 一致。

## F-002 关闭细节

- ADR-0018 §8 第 426 行：B1a 写范围含 `apps/api/.../audit/domain/AuditDetailsPolicy.java`、`apps/api/.../error/ErrorCodes.java`、`AuditDetailsPolicyTest.java`、`RoleDefinitionsTest.java`。
- 第 427 行：B2 `DepartmentAdminApiTest` 断言 `409` 的 `code=RICCON-DEPT-409`。
- 第 436 行：F-002 归属说明明确放在 B1a，避免 B2/B3/B4 并发修改白名单；B2 不需要自建异常类写字符串字面量（可用 B1a 的 `ErrorCodes.RICCON_DEPT_409`）。
- 仍建议 B2 任务卡把“使用 `ErrorCodes` 常量”写成显式约束；这是 F-016 任务卡调整的一部分，不是新 P1。

## 剩余风险与承接

- **F-003/F-004/F-005：未修复 PRODUCT 文档漂移**。ADR-0018 §8 第 437–439 行已把 AC-07 演员、AC-21 分段、AC-03 历史数据口径、M10 审计可见性写成“验收口径补充（不改 PRODUCT）”。PRODUCT 原文仍未改；M03 评审包必须引用这些段落，不能把 F-003/F-004/F-005 说成“已解决”。
- **F-016（P2）**：B2 验收 8 项、V 验收 7 项，超过 `acceptance ≤6`。承接：Lead 建任务卡前把 B2 合并为 ≤6（例如 409 code + audit details 合一项）、V 合并 E2E 两项；不改设计。
- **F-017（P2）**：旧 V9 持久环境走 V10 时，`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION` 仍为 9；B1b 写范围不含 worker。承接：若该环境存在，新增 V10 条件任务并同步 worker 版本常量；否则在 M03 运行前提中明确“不覆盖已应用旧 V9 的持久环境”。
- **F-018（P3）**：B4 任务卡补充 `ChatSubmissionService.findOwnedForUpdate` 的共享空间发送路径，避免只改列表/详情而“继续使用”仍失败。
- **F-009/F-017 的 V10 例外**：默认路径仍不新增 V10；不要用 `flyway repair`。V9 未变是 EV-0304 继续有效的前提。
- **未验证**：B1b 的 seed/回滚执行、M03 E2E/axe、security、full verify 均待 Builder/后续 gate；本轮不能以计划闭合代替执行证据。
- **R-11/R-01/R-12/R-13/R-14**：历史 TENANT、知识库租户级、MFA、审计哈希链、平台跨租户审计明细均仍按既有风险登记，未被本次文档修复误称为解决。

## 是否可进入 Builder

**可以启动 B1a**；B1a 不碰 V9，F-001/F-002 的写范围与测试已闭合。B2/B3/B4/B1b/B5/V 按依赖图推进：

1. B1a（领域词汇、`AuditDetailsPolicy`、`ErrorCodes`、`OrganizationDirectory`、测试）→
2. B2（部门后端；建卡前把验收合并到 ≤6）→
3. B3（空间可见性）→
4. B4（内容贯通；补 `ChatSubmissionService` 点名）→
5. B5 可在 B2/B3 后并行；B1b 在 B4 后收紧 V9；V 最后。

F-016/F-017 是建卡与运行前提问题，不需要重开 ADR 架构；F-003/F-004/F-005 是已登记的 PRODUCT 文档漂移，不阻塞 M03 设计，但必须由 M03 评审包与 M10 任务卡承接。
