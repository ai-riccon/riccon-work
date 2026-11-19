---
review_id: REV-TASK-0071-001
module_id: M10
task_id: TASK-0071
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0074_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [f60435d]
head_commit: 3b66d35
reviewed_evidence: [EV-0413, EV-0414, EV-0415]
findings:
  - id: F-001
    severity: P2
    status: open
    owner: riccon/lead + riccon/builder（单列 web 修复任务）
    title: "审计只读路径的未支持方法被兜底为 500，而非 ADR 要求的 404/405"
    summary: "`GlobalExceptionHandler` 没有 `HttpRequestMethodNotSupportedException` 处理器，`POST/DELETE /api/v1/audit-events`（以及审计导出只读路径上的错误方法）落入 `Exception` 兜底，返回 500 `RICCON-INTERNAL` 并写 ERROR 级堆栈。没有写路由、没有审计写入、没有内容泄露；但违反 ADR-0020 §1 的‘无路由 + 404/405’验证口径，且产生可被审计权限持有者放大的错误日志噪声。B3b 测试以 `status >= 400` 接受该行为，弱于 AC-16 的验证要求。"
    evidence: "apps/api/src/main/java/com/riccon/api/web/GlobalExceptionHandler.java:236-249（仅 NoResourceFoundException 有 404，方法不支持无 handler）；apps/api/src/test/java/com/riccon/api/m10/audit/AuditQueryApiTest.java:185-204（注释自述 500 为 web 层缺口，断言 >=400）；ADR-0020 §1（45）；独立定向测试日志 2026-11-19T11:30:06 两条 `unexpected error ... path=/api/v1/audit-events`"
  - id: F-002
    severity: P3
    status: open
    owner: riccon/verifier（V1）/ B3b 后续小任务
    title: "IT-AUDIT-101 的 actorDisplayName 批量解析只测了 100 行结果，未断言查询次数或耗时"
    summary: "ADR-0020 §1（F-009）要求 100 行断言查询次数/耗时，禁止 N+1。实现按代码结构每页一次 `SELECT ... WHERE tenant_id = :tenantId AND id IN (:ids)` 批量解析（非逐行），风险低；但 `AuditQueryApiTest.listAudit_modelTokenMetricsAndDisplayNames` 只断言 100 行数据正确，未加 SQL 次数/耗时断言，无法在回归中抓住未来有人把 `findDisplayNames` 移进逐行映射。"
    evidence: "apps/api/src/test/java/com/riccon/api/m10/audit/AuditQueryApiTest.java:238-271；apps/api/src/main/java/com/riccon/api/audit/repository/AuditQueryRepository.java:56-65；apps/api/src/main/java/com/riccon/api/audit/persistence/AuditQueryJdbc.java:97-111；ADR-0020 §1（42）"
  - id: F-003
    severity: P3
    status: open
    owner: riccon/verifier（V1）/ B3b 后续小任务
    title: "CSV 在中途批次失败时可能已发送 200 与部分内容，且 data.exported 已记成功审计"
    summary: "`data.exported` 在流开始前写入（符合任务简报‘流开始前写并 fail-closed’），但 `writeCsv` 在写出元数据/表头后才按 500 行分批执行 details 白名单。若后续批次命中历史/运维直写的非法 details（OPS-014），客户端会先收到 200 和部分 CSV，再被截断；此时成功审计已落库，失败语义只体现在连接中断。查询路径同一数据会直接 500 且不写审计，两者行为不完全一致。当前正常写入数据由写侧策略保证，风险主要来自历史数据，故列 P3；建议 V1 覆盖一条 ‘第二批次 details 非法’ 或连接中断用例，并决定是否把 data.exported 改名为下载尝试/在元数据中标记流完成状态。"
    evidence: "apps/api/src/main/java/com/riccon/api/audit/service/AuditExportService.java:247-284（prepareDownload 写 data.exported）、294-317（分批流式，toEventView 可抛 AuditDetailsInvalidException）；apps/api/src/main/java/com/riccon/api/audit/service/AuditCsvWriter.java:52-68（先写元数据/表头）；ADR-0020 §1（43）、§2（86-88）；OPS-014"
  - id: F-004
    severity: P3
    status: open
    owner: riccon/verifier（V1）/ B3b 后续小任务
    title: "下载前计数与流式查询之间没有行数硬上限，occurredTo 允许 now+5m 时会话窗口内新增事件可越过 rowLimit"
    summary: "`prepareDownload` 用一次 `count` 判定 `rowCount <= rowLimit`，随后 `writeCsv` 只按固定 filter 分页输出，不再限制总行数。`occurredTo` 允许服务器时间 +5 分钟；若在计数后、流式查询前有事件落入该未来窗口，CSV 实际行数可超过 rowLimit（元数据 rowCount 也会小于实际输出）。正常下载路径窗口极短，属低概率竞态；但‘超过上限不输出截断文件’的承诺只在计数时点成立。建议 V1 加一条固定未来 happened-at 的竞态用例，或在流中累计行数并在超限时中止（明确失败而不是继续输出）。"
    evidence: "apps/api/src/main/java/com/riccon/api/audit/service/AuditExportService.java:264-268、300-314；apps/api/src/main/java/com/riccon/api/audit/service/AuditQueryFilters.java:72-77；ADR-0020 §1（33）、§2（79）"
  - id: F-005
    severity: P3
    status: open
    owner: riccon/architect（ADR 口径）+ riccon/verifier（V1）
    title: "导出申请/下载只校验 audit.read，未显式校验 TENANT_ADMIN 角色"
    summary: "ADR-0020 §2 的权限写法是 `audit.read`（仅租户管理员）；实现中 `create`/`list`/`get`/`download` 只要求 `audit.read`，平台租户由业务层 403 排除。当前 V10 与 `RoleDefinitions` 只把 `audit.read` 授予 TENANT_ADMIN/PLATFORM_ADMIN，因此现状安全；但若未来给自定义角色或部门负责人授予 `audit.read`，会自动获得创建/下载导出的能力（审批仍受 `audit.export.approve` 保护）。建议架构确认是否需要显式角色断言，或把‘仅租户管理员’写成权限模型约束并在 V1 加负向用例。"
    evidence: "apps/api/src/main/java/com/riccon/api/audit/controller/AuditExportController.java:68-77、140-156；apps/api/src/main/java/com/riccon/api/audit/service/AuditExportService.java:415-419；apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:62-78；V10__audit_query_export.sql 权限回填；ADR-0020 §2（52）"
evidence: [EV-0414, EV-0415]
created_at: "2026-11-19T11:45:00+08:00"
---

# REV-TASK-0071-001：TASK-0071（M10-B3b 审计查询/导出 API）独立评审

## 1. 结论

**verdict: pass（任务级）。** B3b 的查询、导出、CSV、平台审批与权限边界可独立复验；P0/P1 = 0。开放 **P2 1 条**（方法不支持被兜底 500，属 web 层缺口，需单列任务承接）与 **P3 4 条**（测试/竞态/权限口径）。本结论不代表 M10 模块 gate，也不代表 ADR-0020 已接受（ADR 仍为 `proposed`）；B3b 在 Lead 记录本评审并接受 P2 承接后可以 done，但不得把 P2 记为 fixed。

三点边界：

1. **EV-0413 是后端全量 `./mvnw -q -T1C verify`**，覆盖 API 420 + worker 263、0 skip，不覆盖合同 `pnpm -w test`、E2E、a11y、security、完整 coverage 与 V10 生产迁移；M10 模块 gate 由 V1 在最终提交上执行。本次独立定向测试只重复 `*Audit*`，未重复 13 分钟全量。
2. **方法不支持问题不是 B3b 写范围内的缺陷**：`GlobalExceptionHandler` 属 `apps/api/.../web/**`，B3b 未修改它；B3b 测试以 `>=400` 接受现状并注明残余风险。按影响判 P2 而非 P1：无写路由、无写入、无内容泄露，只有错误状态码/日志噪声；但 M10 gate 前需修复或书面接受。
3. **B3b 可 done ≠ M10 可 gate**：OPS-001/002/003/004/005/006/007/014 等 must-fix、B2/B4/B5、V1 套件与 ADR 复审仍未完成；M10 gate 必须继续阻塞。

## 2. 评审范围与独立复验

评审起点：`git status --short` 空；HEAD `3b66d35`；被评审提交 `f60435d`（B3b 代码），EV-0413 在同一提交上录制且 `dirty=false`。独立复验证据：**EV-0414**（`-Dtest='*Audit*'`，commit `c40e5e5`，dirty=false，exit 0）与 **EV-0415**（spotless/contract/state/diff 校验，exit 0）。

| 检查/命令                                                                                                  | 退出码 | 结果                                                                                                                                             |
| ---------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `git status --short`；`git rev-parse HEAD`                                                                 | 0      | 空；`3b66d35be591fdb7022cada809703da9d70e8ee8`                                                                                                   |
| `git show --stat f60435d`                                                                                  | 0      | 49 文件、5548 行新增、0 删除；全部落在 6 个授权目录（audit/{controller,service,repository,persistence}、admin/audit/**、test/m10/audit/**）      |
| `./mvnw -q -T1C -pl apps/api -am -Dtest='*Audit*' -Dsurefire.failIfNoSpecifiedTests=false test`（EV-0414） | 0      | m10/audit 9 个套件 **47 tests、0 fail/0 error/0 skip**；同 pattern 另匹配既有 `*Audit*` 15 个用例；总计 62 个，全部通过                          |
| `./mvnw -q spotless:check`（EV-0415）                                                                      | 0      | 通过                                                                                                                                             |
| `riccon contract validate --strict`；`riccon state validate`（EV-0415）                                    | 0      | contract 0 errors/0 warnings；state 0 errors/1 条与 B3b 无关的 M11 历史警告                                                                      |
| `git diff --check`（EV-0415）                                                                              | 0      | 无空白错误                                                                                                                                       |
| EV-0413 审计                                                                                               | —      | `commit=f60435d`、`dirty=false`、`exit=0`、819,554 ms；日志 SHA-256 与 EV JSON 一致；API 67 套件 420 tests/0/0/0，worker 48 套件 263 tests/0/0/0 |
| EV-0413 覆盖率（JaCoCo XML/CSV）                                                                           | —      | API line 91.25%、branch 73.61%；worker line 87.87%、branch 70.77%；均高于合同 line 80 / branch 70                                                |

未重复/未执行：全量 `verify`（按要求只核对 EV-0413）、`pnpm -w test`、E2E、a11y、security、k6、V10 生产回滚演练、真实浏览器 UI。这些属 M10-V1 与模块 gate。

## 3. 查询（AC-15/16）

**通过项：**

- 租户只来自主体：`AuditQueryController.list` 使用 `TenantContext.require()`，无 `tenantId` 参数；`TenantContextFilter` 对伪造参数返回 403 并写 `tenant.access.denied`（`IT-AUDIT-103`）。
- 时间：`occurredFrom`/`occurredTo` 必须带偏移；缺省 `to=now`、`from=now-24h`；跨度 ≤180 天；`to ≤ now+5m`；无偏移 `LocalDateTime` 拒绝（`AuditQueryFilters.resolve`；`AuditQueryFiltersTest`、`IT-AUDIT-101`）。
- 分页：`page` 1–200、`size` 默认 20、最大 100；超限 `RICCON-AUDIT-400`；`offset` 上限 19,900（`AuditPagination`）。
- 排序固定 `occurred_at DESC, event_id DESC`，不接受客户端排序（`AuditQueryJdbc.PAGE_SUFFIX`）。
- 字段：`AuditQueryJdbc.COLUMNS` 不含 `source_ip`/`request_id`/`schema_version`；`AuditEventView` 无对应字段；测试断言不出现。
- `actorDisplayName`：每页一次 `SELECT id, display_name FROM app_user WHERE tenant_id = :tenantId AND id IN (:ids)` 批量解析，非逐行；100 行测试通过（但见 F-002）。
- `details`：读取复用 B1 的 `AuditDetailsPolicy`（action-aware 白名单，白名单优先），未知动作非空 details/敏感键 fail-closed `RICCON-AUDIT-500`，响应不含原文；`promptTokens`/`completionTokens` 等模型指标通过（`AuditDetailsReaderTest`、`IT-AUDIT-103`）。
- 查询自身审计：读取完成后在同一事务写 `audit.queried`（`filtersHash` + `rowCount`），写失败使查询失败；本次结果不含该事件（`IT-AUDIT-103`）。
- 权限：普通员工 403 `RICCON-AUTH-403`、匿名 401；平台管理员查询只看到 `tenant_id=platform` 事件，租户管理员只看到本租户（`IT-AUDIT-103`）。
- 策略端点：`retentionDays ≥180`、`maxRangeDays=180`、`pageSizeMax=100`、`maxPage=200`、动作含 API/Worker/M10 动作、`exportLimits` 来自配置（`IT-AUDIT-101`、`AuditCsvWriterTest`）。

## 4. 导出（AC-17/18）

**通过项：**

- 申请：服务端用同一查询条件计数 `estimatedRowCount`；超过 `rowLimit`（默认 10,000）400 `RICCON-AUDIT-400`，不接受客户端自报行数；写入 `PENDING_APPROVAL` 与 `audit.export.requested`（`IT-AUDIT-104`、`AuditExportClockTest`）。
- 自批 403 `RICCON-AUDIT-403`；同租户另一管理员审批 200；条件更新 `WHERE status='PENDING_APPROVAL'`，并发 200/409 各一（`IT-AUDIT-104`）。
- 非申请人下载 403 `RICCON-AUDIT-403`；跨租户/不存在统一 404 `RICCON-TENANT-404`；平台管理员下载租户导出 404；未批准 409；过期 410（不改写状态，派生 `EXPIRED`）；下载前重新计数超限 409（`IT-AUDIT-104/106`、`AuditExportClockTest`）。
- TTL 与 Clock：`decidedAt/expiresAt` 使用同一 `Clock`；`PT2S` 短 TTL + `MutableClock` 前进验证 410，不用 `Thread.sleep`；默认 24h、上限 7 天、`rowLimit` 上限 50,000 在 `AuditProperties` 启动校验（`AuditExportClockTest`、`AuditPropertiesTest`）。
- 审批审计：`audit.export.approved/rejected` 含 exportId/decision/estimatedRowCount/filtersHash/非空过滤字段；`decisionComment` 不进入审计；拒绝不设过期；平台审批写 `platform` + `details.targetTenantId`，目标租户不写重复事件（`IT-AUDIT-104/105`）。
- 平台边界：平台租户自身 create/list/detail 403 `RICCON-AUDIT-403`，download 404；平台端点仅 `platform.audit.export.approve`；单管理员租户可列表/审批，多管理员租户列表与决策均 409；平台管理员不是申请人（平台租户导出不支持）；admin 路径不提供下载（`IT-AUDIT-105`）。
- `data.exported`：在流开始前写入并 fail-closed（写失败使下载失败），重复下载逐次审计；过期/未批准不写成功事件（`AuditCsvExportTest`、`AuditExportClockTest`）。

## 5. CSV（AC-18）

**通过项：**

- 流式生成，不落盘、不写对象存储；按 500 行分批查询，避免一次性加载 10,000 行（`AuditExportService.STREAM_BATCH_SIZE`）。
- UTF-8 BOM；4 行 `#` 元数据（exportId/generatedAt/filters/rowCount，offset +08:00）；空行；固定表头；RFC 4180 CRLF；`details` 为 JSON 字符串（`AuditCsvWriter`、`IT-AUDIT-106`）。
- 公式注入：首字符 `=`/`+`/`-`/`@`/Tab/CR 前置 `'`；`details` 以 `{` 开头不受影响（`AuditCsvWriter.escape`、`AuditCsvWriterTest`、`IT-AUDIT-106`）。
- 字段白名单固定，不含 `source_ip`/`request_id`/`schema_version`/密码/令牌/凭据/原文/邮箱；`details` 读取执行与查询相同的 action-aware 白名单（`IT-AUDIT-106` 断言 `203.0.113.9`、`source_ip`、`requestId`、`password`、`email` 不出现）。
- 响应头：`text/csv; charset=utf-8`、`Content-Disposition: attachment; filename="audit-export-{exportId}.csv"`、`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`（`IT-AUDIT-104`）。

残余风险见 F-003/F-004：中途批次失败/连接中断的语义、计数与流之间的行数硬上限。

## 6. 权限、边界与依赖

- 权限消费 B3a：`PermissionCodes.AUDIT_READ`/`AUDIT_EXPORT_APPROVE`/`PLATFORM_AUDIT_EXPORT_APPROVE` 已由 B1/B3a 提供；B3b 未修改 `V10`、`RoleDefinitions`、`SchemaVersionChecker`、`contracts/**`、`apps/api/.../audit/domain/**` 或 `apps/web/**`（`git show --stat f60435d` 与路径过滤均通过）。
- 平台查询边界：平台管理员只能查 `platform` 租户事件；M03 F-009 的“目标租户看不到平台来源事件”在 M10 仍为 residual，B3b 未引入 `UNION`/`targetTenantId` 查询参数/事件复制。
- 平台审批只接触请求元数据（申请人、filter、预计行数、时间、状态），不返回审计明细、不提供下载；`data.exported` 仅租户申请人路径。
- 错误码：`RICCON-AUDIT-400/403/409/410/500` 与 ADR-0020 §6.2 一致；无权限仍用 `RICCON-AUTH-403`，跨租户用 `RICCON-TENANT-404`（`ErrorCodes`、`IT-AUDIT-104/105`）。
- CSRF：POST 端点走既有 CSRF 过滤；测试带 `X-CSRF-TOKEN`；GET 下载无 CSRF，符合 ADR。
- **F-001**：只读路径错误方法 500 的映射缺口，见下节。

## 7. 方法不支持（F-001 详述）

- 复现：以租户管理员会话 + CSRF 发 `POST /api/v1/audit-events`（body `{}`）或 `DELETE /api/v1/audit-events`；实际 500 `RICCON-INTERNAL`，日志两条 `unexpected error ... path=/api/v1/audit-events`（独立定向测试 2026-11-19T11:30:06；同一行为在 `AuditQueryApiTest` 注释中被 builder 指出）。
- 期望：无写路由时 405 Method Not Allowed（或至少 404），不是 500；ADR-0020 §1（45）明确 AC-16 以“无路由 + 404/405”验证。
- 根因：`GlobalExceptionHandler` 仅处理 `NoResourceFoundException`（未知路径 404），未处理 Spring MVC 的 `HttpRequestMethodNotSupportedException`，被 `Exception` 兜底成 500（`GlobalExceptionHandler.java:223-249`）。
- 影响：无写入/无内容泄露；但 500 会污染错误率/告警，且错误码语义违反契约；B3b 的测试只断言 `>=400`，无法防止回归到 2xx（当前也不可能是 2xx）。
- 级别与承接：**P2（open）**，owner Lead 建立单列小任务（建议只改 `apps/api/src/main/java/com/riccon/api/web/GlobalExceptionHandler.java` + `api/src/test/java/.../web/` 测试），在 M10 gate 前修复或由 Lead 书面接受。不要放进 B3b 原任务（写范围不含 web 层），也不建议塞给 B4/B5（它们是 Web 前端写范围）；V1 也不应修改生产 `GlobalExceptionHandler`。

## 8. 测试与假绿审计

- 无删测/跳测：B3b 提交只新增测试文件（9 个 m10/audit 套件），无删除；EV-0413 API 420/worker 263 均 `skipped=0`；本次定向 47 个 m10/audit 用例 `skipped=0`。
- 断言质量：查询/导出的核心负向路径（403/404/409/410/500、自批、非申请人、并发、过期、多管理员、平台租户、跨租户、公式注入、字段白名单、模型 token 指标、审计写入）都有具体状态码/错误码/数据库行断言；不是只做快照。
- 未覆盖/弱覆盖（已列 F-002/F-003/F-004）：100 行查询次数/耗时未断言；CSV 中途批次失败/连接中断未覆盖；计数与流之间的行数上限竞态未覆盖；`status=REJECTED` 下载 409、平台租户 decision 403 仅由代码路径保证，未单列用例。
- 性能：`AuditQueryJdbc` 使用固定排序 + 时间范围；`AuditExportService` 500 行分批，避免 10k 行一次性加载；未做 k6 压测（V1/gate）。

## 9. B2/B4/B5 启动条件

- **B2（TASK-0069，账号开通/绑定）**：可以立即启动。B1 与 OAuth2 Client starter（TASK-0066）已 done；B2 不依赖 B3b/本评审，也不写 V10/审计 API。
- **B4（Web OIDC/绑定）**：依赖 B1/B2 的 OpenAPI 字段稳定；B2 未完成前不启动。它不依赖 B3b，可在 B3b 评审后并行推进（与 B5 按 ADR“B4/B5 可并行”）。
- **B5（Web 审计/导出）**：依赖 B3b 的 OpenAPI 稳定；本评审 pass 后，Lead 将 B3b 置 done 并记录 P2 承接即可启动。B5 不得自行改后端契约字段；若需要改，回 architect。
- **V1（M10 独立验证）**：B4+B5 完成后启动；必须跑合同 test/coverage/E2E/a11y/security、OPS 演练和模块评审，不能引用本任务级 EV 作为模块 gate 证据。

## 10. 未验证与剩余风险

- 未重复全量 verify（按分层要求）；EV-0413 是 builder 在 `f60435d` 干净工作区录制，hash 已核对；本次未重跑 worker/前端。
- 未做真实浏览器 UI（B5 尚无页面）、E2E、a11y、security、k6、V10 生产迁移/回滚演练；这些属 V1/模块 gate。
- ADR-0020 仍 `proposed`（`docs/ARCHITECTURE.md` 评审状态表仍为 changes-requested）；B3b 实现不改变 ADR 状态，M10 gate 前需 architect/Lead 复审并记录。
- M03 F-009（平台来源事件对目标租户不可见）仍为 residual/deferred，B3b 未修复也不能声称已修复。
- OPS-008（audit_export_request 清理索引）、OPS-009（流式导出容量）、OPS-013（平台租户导出兜底）、OPS-014（历史/运维直写 details 清点）继续 open；OPS-009 已由 500 行分批缓解占用，但未物化全量。

## 11. 下一步

1. Lead：记录本评审；接受 F-001 P2 并建立 web 层小任务（gate 前关闭或书面接受），把 TASK-0071 置 done（本评审不代改）。
2. 启动 B2；B4 待 B2 API 稳定；B5 待 B3b done 后启动。
3. V1 在 B4+B5 后跑模块 gate；补齐 F-002/F-003/F-004 的验证或在模块评审中承接。
