---
review_id: REV-TASK-0033
module_id: M03
task_id: TASK-0033
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0034_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: [6b41244, d468a8d]
head_commit: a2d7ebc
created_at: 2026-11-18T15:34:37+08:00
evidence:
  - EV-0304
  - EV-0305
findings:
  - id: F-001
    severity: P1
    status: open
    summary: "B1 首任务同时收紧 V9 与代码顺序冲突：M02 WorkspaceJdbc 尚未写 visibility，B1 改 NOT NULL 会使构建/测试失败"
  - id: F-002
    severity: P1
    status: open
    summary: "Builder 写范围漏掉 AuditDetailsPolicy 与 ErrorCodes：部门审计 details 会被 fail-closed 拒绝，RICCON-DEPT-409 无承载"
  - id: F-003
    severity: P2
    status: open
    summary: "PRODUCT 仍把审计查询/OIDC 写在 M03，M03-AC-07 仍写负责人移出/移交；与 DEC-0011/0015 不一致，需评审包显式演员与分段映射"
  - id: F-004
    severity: P2
    status: open
    summary: "历史 TENANT 空间仍全租户可见，使 M03-AC-03 '只显示个人+部门空间' 在含 M02 数据的租户中字面不成立；需清单与干净 E2E 租户"
  - id: F-005
    severity: P2
    status: open
    summary: "跨租户部门审计写 platform 租户，目标租户 M10 审计可查询性缺口只在 ADR 一句带过，未进入 PRODUCT M10 AC；DEC-0015 后果文字与 ADR/M02 口径不一致"
  - id: F-006
    severity: P2
    status: open
    summary: "OpenAPI 将 workspace-archive 标为 M04 deferred，却仍保留 DELETE /api/v1/workspaces/{workspaceId} 可用路径，且 M02/M03 均未实现"
  - id: F-007
    severity: P2
    status: open
    summary: "单一空间谓词目标正确，但 B3/B4 验收未明确要求移除 FileJdbc.WORKSPACE_VISIBLE、ConversationJdbc owner-only、RunDetailService CHAT owner-only 等旧路径"
  - id: F-008
    severity: P2
    status: open
    summary: "V9 迁移测试只验证空库应用与表数量，未验证 M02 数据回填、权限/角色回填、FK/CHECK 与回滚；存在假绿"
  - id: F-009
    severity: P2
    status: accepted
    summary: "V9 未发布前直接修订可接受，但未写非生产库重置、旧 API 排空与 checksum 处理；B1 必须以运行前提记录"
  - id: F-010
    severity: P2
    status: open
    summary: "B2 移出成员清理 CUSTOM 成员行需要 B3 的 workspace 能力，B3 requireManageable 又需要 B2 部门关系；ADR 未给任务依赖顺序"
  - id: F-011
    severity: P3
    status: open
    summary: "威胁模型仍有 M03 OIDC/MFA/导出陈旧引用；R-15 写 '租户管理员可随时移交' 与 DEC-0015 的仅平台管理员移交矛盾"
  - id: F-012
    severity: P3
    status: accepted
    summary: "ADR-0007 正文第 2/6/7 节仍标 M03 审计查询/导出/哈希链，变更日志只澄清第 6 节"
  - id: F-013
    severity: P3
    status: accepted
    summary: "M03-AC-21 测试线索 E2E-M03-07 未映射到 ADR-0018 §8 的 M03 验证计划（M03 只覆盖部门管理页）"
  - id: F-014
    severity: P3
    status: accepted
    summary: "F-017 文档收口只改了 ADR-0009；ADR-0014 仍只描述预签名 URL，未记录同源 content 路径"
  - id: F-015
    severity: P3
    status: accepted
    summary: "PermissionCodes 注释仍写 M03 不改变码值，但 M03 实际新增 department.read/platform.department.manage；B1 需同步"
---

# TASK-0033 独立评审：M03 部门/数据范围架构与 V9

## 结论

**verdict: fail**（2 个开放 P1）。架构方向本身成立：单级部门/单主部门、`DEPT`/`CUSTOM` 可见性、历史 `TENANT` 只读、平台管理员跨租户管理组织元数据、统一空间谓词与统一 404，和 DEC-0010..0015 及 PRODUCT §8 的 M03 段基本对齐；V9 作为 expand 迁移在干净 MySQL 8.4 上可应用，OpenAPI lint 通过。

但当前交付的 **Builder 任务边界不可直接执行**：

1. B1 首任务同时被要求收紧 `visibility NOT NULL`，而 M02 的 `WorkspaceJdbc.insert` 尚未写 `visibility`，B3/B4 又不在 B1 写范围内；B1 完成后现有工作空间测试会失败，B1 无法按当前边界独立通过。
2. 新增 `department.*`/`workspace.*` 审计动作需要更新 `AuditDetailsPolicy`，但该文件不在任何 Builder 写范围内；不更新则第一条带 details 的部门审计会因 fail-closed 直接失败。`RICCON-DEPT-409` 所需 `ErrorCodes` 同样未被任何任务覆盖。

在 F-001/F-002 修复（拆 B1 或调整顺序/写范围）前，不应进入 Builder 阶段。其余 P2/P3 可在建任务卡与 M03 评审包中处理。

## 评审范围与方法

- 基线：`git status --short` 为空；HEAD `a2d7ebc`；评审提交 `6b41244`、`d468a8d`。
- 独立验证：
  - `npx --yes @redocly/cli@1.34.3 lint contracts/openapi.yaml` → exit 0（EV-0305）。
  - `./mvnw -q -T1C -pl apps/api -am -Dtest=ApiApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test` → exit 0；Flyway 在 Testcontainers MySQL 8.4.13 上应用 V1–V9，测试 8/0/0/0（EV-0304）。
  - `node ... riccon.mjs contract validate --strict --project .` → exit 0。
  - 独立核对 Maven Central：`agentscope-core-2.1.0-BETA1.pom` 仍依赖 `io.modelcontextprotocol.sdk:mcp:0.17.2`；`maven-metadata.xml` 无 2.1.0 正式版。
- 已核对但未作为本轮独立证据依赖：EV-0302、EV-0303（两者均为 architect 的 full verify，非 manual、exit 0、dirty=false；本轮已自行跑迁移级测试）。
- 未执行：完整 `./mvnw verify`、`commands.security`、E2E、axe、回滚演练、M03 gate。本任务只评审架构与 V9，不重复全量验证。

## AC 对账（M03 段）

| AC                       | 设计承载                                                                                                                           | 结论   | 说明                                                                                                                                              |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| M03-AC-01                | ADR-0018 §1.3/§1.4/§2.3/§7.1；V9 `department`/`app_user.department_id`；OpenAPI admin 部门端点；B2/B5；`E2E-M03-01`、`IT-DEPT-001` | 通过   | 平台管理员页、成员数/负责人、单主部门（列 + 复合外键）、重复加入 409、跨租户账号 404；演员与 AC 原文一致                                          |
| M03-AC-02                | ADR §2.3/§3.1；B3 `requireManageable`；OpenAPI PATCH/members；`IT-DEPT-002`                                                        | 通过   | 负责人创建/改名/可见性、非负责人 403/404、不可见空间 404；B5 隐藏管理入口                                                                         |
| M03-AC-03                | ADR §2.1/§3.1；B3 列表谓词；`DEPT-001`/`DEPT-009`                                                                                  | 部分   | 设计可满足干净租户；历史 `TENANT` 空间例外见 F-004                                                                                                |
| M03-AC-04                | ADR §3.1/§3.2；B4 内容贯通；`DEPT-002..003`、`E2E-M03-02`                                                                          | 部分   | 设计有承载；但旧 owner-only/重复谓词必须在 B4 验收明确移除，见 F-007                                                                              |
| M03-AC-05                | ADR §3.1/§3.4；`DEPT-004`、`IT-DEPT-003`                                                                                           | 通过   | 个人空间仅 owner；未显式加入知识库的个人文件不进入租户检索                                                                                        |
| M03-AC-06                | ADR §3.4；B5 固定文案；`E2E-M03-02`                                                                                                | 通过   | 知识库租户级共享、源文件打开/下载仍走空间 404；不出现部门隔离声明                                                                                 |
| M03-AC-07                | ADR §1.3/§1.4/§3.1/§3.3/§5；B2/B3；`IT-DEPT-004`、`DEPT-006..008`                                                                  | 部分   | 最终断言（移出/停用后下一次请求 404、旧负责人失去管理入口）可验证；演员按 DEC-0015 改为平台管理员，PRODUCT 未同步，见 F-003；审计可查询性见 F-005 |
| M03-AC-19（部门/空间段） | ADR §8；`DEPT-001..010`；TM-23..TM-28                                                                                              | 通过   | 10 条部门用例覆盖列表、详情、下载、检索、成员移出、负责人越权；导出段归 M10                                                                       |
| M03-AC-20                | ADR-0009 澄清；TEN-021..027；已有 TEN-001..020 回归                                                                                | 通过   | 编号连续、不重排；TEN-027 为平台部门端点                                                                                                          |
| M03-AC-21（部门管理页）  | ADR §8 B5/V；B5 375px/错误空无权限态；`@a11y`                                                                                      | 部分   | M03 只要求部门管理页；AC-21 测试线索 `E2E-M03-07` 未在 ADR §8 映射，见 F-013                                                                      |
| M03-AC-08..18            | 归 M10（PRODUCT §8.5）                                                                                                             | 不适用 | 本任务只确认 M03 契约未提供可用 OIDC/审计路径；`x-riccon-deferred` 已列 M10/M10+                                                                  |

## V9 审查

### 表结构与约束

- `department`：`(tenant_id, id)` 复合主键、`tenant_id` 外键到 `tenant`、`UNIQUE (tenant_id, name)`、`status`/`disabled_at` CHECK、manager/status 索引均以 `tenant_id` 开头；与 ADR §1.1 一致。`department.created_by` 用逻辑引用而非复合外键，符合平台管理员不在目标租户 `app_user` 的事实；权威操作者在 `audit_event.actor_id`，无不可接受的孤儿风险（`app_user` 只有禁用、无硬删除 API）。
- `app_user.department_id`：单列 + `(tenant_id, department_id) → department` 复合外键，结构上强制单主部门并阻止跨租户；索引 `(tenant_id, department_id, status)` 支撑成员计数。符合 ADR §1.2。
- `workspace`：新增 `department_id`/`visibility` 可空 + 复合外键 + `(tenant_id, department_id, visibility, status, updated_at)` 索引；宽松 CHECK 允许旧代码在滚动发布期写 `NULL`，PERSONAL→PRIVATE、TEAM→TENANT 回填不猜部门，符合 expand 策略。
- `workspace_member`：复合主键、到 workspace/app_user 的复合外键、`granted_by` 外键、`(tenant_id, user_id, workspace_id)` 索引；只服务 CUSTOM 的约束靠服务层，DDL 无法跨表强制，B3 需测试“DEPT/PERSONAL 不接受成员行”。
- 权限：V9 写入 `department.read`/`platform.department.manage`，`department.read` 回填 MEMBER/TENANT_ADMIN，`platform.department.manage` 只回填 `platform` 的 PLATFORM_ADMIN；未创建 `department.manage`。与 DEC-0015/ADR §4 一致。空库依赖 `BootstrapAdminRunner` + B1 更新后的 `RoleDefinitions`（`AdminRepository.createRole` 会按定义插入 role_permission）；该依赖已写入 ADR §4/§8 B1 验收，但 B1 写范围未覆盖其测试，见 F-002。

### 迁移策略与回滚

- 当前 V9 是 expand：不删表/列、不改类型、不重建 M02 表；`visibility` 保持 NULL 兼容旧代码。独立迁移测试通过。
- ADR §6.2 的“发布前直接修正 V9”在**未发布**前提下可接受；ADR-0010 的“已应用迁移不可修改”针对已发布环境，非生产测试库必须重置。未写重置/排空步骤，见 F-009。
- ADR §6.3 回滚 SQL 顺序正确（先 workspace_member，再 workspace FK/索引/CHECK/列，再 app_user FK/索引/列，再 department，最后 role_permission/permission）；MySQL 8.4 支持 `DROP CHECK`。但本轮未在副本执行回滚，B1/M03 gate 需演练，见 F-008。
- 只有 `apps/api` 执行 Flyway；`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION` 已从 8 升到 9，worker 不读部门/可见性。符合 ADR-0010。

### V9 语义测试缺口

`ApiApplicationTests` 只断言 9 条迁移、39 张表、2 个触发器；EV-0304 也是空库应用。没有 seed V1–V8 的 M02 数据再跑 V9 的测试，因此回填、权限/角色回填、FK/CHECK 目前都是“设计上正确、自动化未证明”。T4 迁移不能以空库通过代替数据迁移通过，见 F-008。

## 权限、审计与租户边界

- **平台管理员**：部门端点只在 `/api/v1/admin/tenants/{tenantId}/departments...`，统一 `platform.department.manage`；不提供 `/workspaces`、会话、文件、报告、产物、知识库正文或审计明细路径。目标租户不存在/部门不匹配统一 404，暂停 409，租户管理员/普通用户 403；跨租户账号加入 404。与 DEC-0015/ADR §1.4/§7.3 一致。
- **租户管理员**：只保留 `department.read`（本租户全部部门只读）+ `workspace.read/write` 空间管理；没有部门写端点、没有 `department.manage`。开放 API 中 `/api/v1/departments` 与成员端点只有 GET；符合“租户管理员不获部门写权限”。
- **普通成员/负责人**：普通成员只读本人部门与成员；负责人只通过 `requireManageable` 管理本部门空间；平台管理员不因管理员身份获得业务内容读取权。设计边界清楚。
- **审计**：部门事件写 `tenant_id=platform` + `details.targetTenantId`，与 M02 `TenantUserAdminService` 口径一致；`AuditJdbc.append` 通过 `TenantContext` 按事件租户写入，ADR §5 要求写前切回 platform 是必要的。details 白名单只含 UUID/计数/枚举/部门名，不含姓名、邮箱、成员名单。
- **缺口**：目标租户管理员在 M10 能不能看到平台来源的部门事件，ADR §5 只说“需另立查询模型”，未进入 PRODUCT M10 AC；DEC-0015 的 consequences 还写“把审计写到目标租户”。见 F-005。MFA/审计哈希链/跨租户审计明细三项缺口已登记 R-12/R-13/R-14。
- **缓存**：设计明确不引入跨请求缓存，成员移出/部门停用/负责人移交每请求查库；`AuthService.requirePermission` 也是每请求重载权限，下一请求生效成立。

## 契约检查

- `info.version: 1.1.0-m03`；新增 Departments 标签、租户只读部门端点、平台部门端点、空间成员端点与 schema。
- `WorkspaceCreateRequest` 必填 `departmentId`，`visibility` 只允许 `DEPT|CUSTOM`；`WorkspaceUpdateRequest` 不允许 `TENANT`；`Workspace` 响应 `visibility` 必填、`departmentId/departmentName` 可空；`Department`/`DepartmentMember` 字段与 ADR §7.2 对齐，未复用 `UserSummary`，不泄露邮箱/角色/最近登录。
- `RICCON-TENANT-404` 用于跨租户/跨部门/对象不存在；`RICCON-DEPT-409` 用于部门/成员冲突；`RICCON-AUTH-403`、`RICCON-CONFLICT-409` 语义未变。OpenAPI 的 `ProblemDetail.code` 模式允许 `RICCON-DEPT-409`。
- M10 项（OIDC/SSO、审计查询、审计导出、平台跨租户审计）只在顶层 `x-riccon-deferred`，没有可用路径；M03 路径未逐个标 deferred。`contracts/internal-api.yaml` 未变。
- 问题：`workspace-archive` 标 M04 deferred，但同文件仍保留 `DELETE /api/v1/workspaces/{workspaceId}`，见 F-006。redocly lint exit 0（EV-0305），但不检查“deferred 与路径存在性”的业务一致性。

## Findings 详情

### F-001（P1，open）B1 首任务收紧 V9 与代码顺序冲突

- **位置**：`docs/adr/ADR-0018-m03-organization-and-department-scope.md` §6.2（第 298 行：“Builder 在 API 代码已全部写 `visibility` 后直接修正 V9”）；§8 B1（第 405 行：写范围只有 `platform-domain/**`、`RoleDefinitions.java`、`V9`，验收含“V9 回填 NULL 并收紧 CHECK”）；`apps/api/src/main/java/com/riccon/api/workspace/persistence/WorkspaceJdbc.java:46-54`（INSERT 不写 `visibility`）；`WorkspaceService.java:88-104`、`:144-167`（创建团队/个人空间均经该 INSERT）。
- **观察**：当前 V9 允许 `visibility NULL`，M02 代码可运行；若 B1 按 §8 先收紧为 `NOT NULL`，而 B3/B4 尚未把 `WorkspaceJdbc.insert` 改为写 `visibility`，任何创建工作空间的测试都会因 `visibility cannot be null` 失败。B1 写范围不含 `WorkspaceJdbc`，无法自行修复。
- **期望**：B1 拆为 B1a（`WorkspaceVisibility`、权限、审计常量、`RoleDefinitions`，V9 保持 expand）和 B1b（Tightening，放在 B3/B4 写完 visibility 之后），或把 V9 收紧移到最后一个 Builder 任务；更新 ADR §6.2/§8 的顺序与验收。
- **影响**：Builder 按当前任务卡无法得到绿色构建，M03 无法进入可验证增量。

### F-002（P1，open）审计 details 白名单与 409 错误码没有任务归属

- **位置**：`apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:16-40`（`ALLOWED_KEYS` 固定映射，未知动作默认空集合）；`AuditEvent.java:89-102`（写入前 `AuditDetailsPolicy.validate`）；ADR-0018 §5 第 257-270 行要求 `department.*`/`workspace.*` 带白名单 details；ADR-0018 §8 B1/B2/B3/B4 写范围均未包含 `apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java`；`apps/api/src/main/java/com/riccon/api/error/ErrorCodes.java:9-72` 无 `RICCON-DEPT-409`，而 ADR §7.3 第 387 行要求该稳定错误码，B2 写范围不含 `error/**`。
- **观察**：按当前计划，B2 第一次写 `department.created` 且携带 `targetTenantId/departmentId/name/status` 时，`AuditDetailsPolicy` 会抛 `IllegalArgumentException`，事务回滚（fail-closed）；若为了绕过而传空 details，则违反 ADR §5 的审计字段要求。`RICCON-DEPT-409` 只能靠 B2 新建异常类并在其中写字符串字面量，和 `ErrorCodes` 单一事实源/编码规范不一致。
- **期望**：把 `AuditDetailsPolicy` 与 `ErrorCodes` 明确加入 B1（共享词汇）或 B2 写范围，并加入 `AuditDetailsPolicyTest`/`RoleDefinitionsTest` 或 `DepartmentAdminApiTest` 的断言；B2 测试必须断言 `409` 的 `code=RICCON-DEPT-409`。
- **影响**：M03-AC-01/07 的审计与 409 语义无法按设计落地；这是验收阻断，不是文档问题。

### F-003（P2，open）PRODUCT 的 M03 范围/演员未同步 DEC-0011/0015

- **位置**：`docs/PRODUCT.md:15`（“审计查询在 M03”）；`:180`（M03-AC-07“负责人把她移出部门/移交给他人”）；`:194`（M03-AC-21 含 OIDC 登录页、审计查询/导出与键盘旅程）；`docs/PRODUCT.md:219-227`（§8.5 把 08–18 归 M10、21 按页面拆分）；`.riccon/decisions/DEC-0015.md:14/43`（部门成员/负责人变更由平台管理员执行）；`docs/adr/ADR-0018...§1.4` 第 79 行已注明 AC-07 演员差异“不改 PRODUCT”。
- **观察**：M03-AC-07 的 Given/When 写的是负责人执行部门成员变更，但 DEC-0015 后只有平台管理员能执行；M03-AC-21 的完整旅程含 M10 的 OIDC/审计/导出；§2 用户表还把审计查询写在 M03。严格按 PRODUCT 字面验收会失败或产生歧义。
- **期望**：不改 PRODUCT 的前提下，M03 评审包必须登记“AC-07 演员替换：平台管理员执行移出/移交，负责人只失去/获得空间管理能力，最终断言不变”；同时记录 M03 只验 AC-21 部门管理页段、AC-08..18 归 M10。建议 Lead 在 M03 后安排 PRODUCT 同步，避免长期漂移。
- **影响**：AC 可验收性依赖人工口径；不是设计阻断。

### F-004（P2，open）历史 TENANT 空间与 M03-AC-03 字面冲突

- **位置**：ADR-0018 §2.1 第 97、102-103 行（`TENANT` 仅迁移回填，本租户启用员工可见；历史空间清单在 M03 评审记录列明）；`docs/security/THREAT-MODEL.md` R-11 第 417 行；`docs/PRODUCT.md` M03-AC-03 第 176 行“只显示个人空间与已加入的部门空间”。
- **观察**：任何含 M02 团队空间的租户，M03 后这些空间回填为 `TENANT`，仍对全租户可见；AC-03 的“只显示个人+部门空间”在该租户不成立。ADR 已接受 R-11，但没有把“M03 E2E 必须用干净租户或显式列出遗留 TENANT 空间”写入验证计划。
- **期望**：M03 评审包列出遗留 `TENANT` 空间清单；E2E-M03-01/02 的 tenant fixture 只含 M03 创建的部门空间，或单独断言遗留空间按 R-11 可见并从 AC-03 范围排除。
- **影响**：AC-03 可能在有 M02 数据的演示环境被误判失败；不影响设计。

### F-005（P2，open）目标租户的部门审计可查询性缺口未进入 M10 范围

- **位置**：ADR-0018 §5 第 274 行（跨租户部门事件写 `platform` + `details.targetTenantId`；“M10 审计查询若要让目标租户管理员看到平台来源事件，需另立查询模型，M03 不声称”）；`docs/adr/ADR-0007-audit-event-model.md:140`（DEC-0015 口径）；`.riccon/decisions/DEC-0015.md:14/43`（consequences 写“把审计写到目标租户”，与 ADR/M02 代码相反）；`docs/PRODUCT.md` M10-AC-15（只写租户管理员查本租户，不包含平台来源事件）。
- **观察**：部门变更事件在 `platform` 租户；若 M10 租户审计查询只按调用者租户过滤，目标租户管理员看不到本租户部门/成员/负责人变更。M03-AC-07 的“两次变更均可在审计中查到”没有指明查询主体。DEC-0015 的后果文字与 ADR-0018/ADR-0007 的实现口径不一致。
- **期望**：M03 评审包明确“M03 用 SQL/平台审计验证事件落库；M10 需定义平台来源事件对目标租户的可见模型，或明确接受租户管理员不可见”。Lead 以追加式澄清记录 DEC-0015 的审计租户口径；M10 AC 或设计补充该场景。
- **影响**：M10 审计查询范围与 M03-AC-07 的可解释性；不阻断 M03 设计。

### F-006（P2，open）workspace-archive deferred 与可用路径矛盾

- **位置**：`contracts/openapi.yaml:93-95`（`x-riccon-deferred` `workspace-archive`，status M04，reason“M03 不实现归档端点”）；同文件第 306-325 行仍有 `DELETE /api/v1/workspaces/{workspaceId}`（`deleteWorkspace`，`workspace.write`）；`apps/api/src/main/java/com/riccon/api/workspace/controller/WorkspaceController.java` 只有 list/create/get，没有 delete 实现。
- **观察**：契约把一个未实现端点同时标为 deferred 并保留可用路径；M03 契约读者会认为归档可用，实际 405/404。M02 的 F-011 已接受“契约漂移”，但本次 M03 编辑新增 deferred 条目时没有消除路径，属于可避免的新增矛盾。
- **期望**：M04 前从 `openapi.yaml` 删除 `DELETE /workspaces/{workspaceId}`，或把归档实现明确纳入 B3 并移出 deferred；若保留，`x-riccon-deferred` 不应与可用路径并存，并在 `contracts/README` 说明。
- **影响**：契约与实现不一致；不阻断 M03 AC。

### F-007（P2，open）单一空间谓词的旧路径未在 B3/B4 验收中明确移除

- **位置**：ADR-0018 §3.1 第 144-145 行（唯一入口、禁止在业务仓储复制可见性规则）；`apps/api/src/main/java/com/riccon/api/file/persistence/FileJdbc.java:49-50`（`WORKSPACE_VISIBLE` 复制 `kind='TEAM'` 规则，第 242/281/368 行使用）；`apps/api/src/main/java/com/riccon/api/chat/persistence/ConversationJdbc.java:151/169/187/204`（`findOwned`/`listOwned`）；`ConversationService.java:117-121`（列表按 `listOwned`）、`:185`（详情 `requireOwned`）、`:288-292`；`RunDetailService.java:116`（CHAT run 走 `requireOwned`）；`ChatSubmissionService.java:138`（`findOwnedForUpdate`）。
- **观察**：设计目标正确，B4 写范围覆盖 `chat/**`、`file/**`、`run/**`，但 B4 验收只写“部门空间内会话/文件/报告可见可继续；个人空间他人 404；下载 404”，没有明确要求删除 `FileJdbc.WORKSPACE_VISIBLE`、替换 `findOwned/listOwned`、移除 `RunDetailService` 的 CHAT owner-only 分支。Builder 可能只改列表入口而留下详情/消息/运行详情的 owner-only 断言，AC-04 的“本部门成员打开并继续使用”仍会失败，或出现两套谓词漂移。
- **期望**：B3/B4 验收增加“共享空间的所有列表/详情/消息/下载/运行详情都走 `requireAccessible`；删除/替换重复 SQL 谓词；`ConversationJdbc.findOwned/listOwned` 在共享空间路径不再作为唯一判据；增加 grep/ArchUnit 或测试守卫”。Verifier 的 DEPT-002/003/004 应直接覆盖消息与 run detail。
- **影响**：AC-04/AC-05 的核心可见性；不阻断设计。

### F-008（P2，open）V9 迁移语义测试与回滚演练缺失

- **位置**：`apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:29-46`（只断言 9 条迁移、39 张表、2 个触发器）；EV-0304 复现；ADR-0018 §6.3 回滚 SQL；ADR-0018 §8 B1“迁移测试”。
- **观察**：没有 seed V1–V8 的 M02 数据后再跑 V9 的测试；`PERSONAL→PRIVATE`、`TEAM→TENANT` 回填、`department.read`/`platform.department.manage` 角色回填、`app_user`/`workspace`/`workspace_member` 复合外键、`chk_workspace_scope` 都只经静态 SQL 检查。空库通过不能证明数据迁移通过；`V9` 的 `chk_department_disabled` 等约束也没有测试。
- **期望**：B1/B2 增加 Testcontainers 迁移测试：先应用 V1–V8，插入 M02 租户/角色/个人/团队空间/非法组合，再应用 V9，断言回填与权限；在副本执行 ADR §6.3 回滚并验证可回到 V8。把测试路径加入 B1 写范围。
- **影响**：T4 迁移的假绿风险；M03 gate 前必须补齐。

### F-009（P2，accepted）V9 未发布前直接修订的运维前提

- **位置**：ADR-0018 §6.2 第 296-310 行；V9 文件头第 11-13 行；ADR-0010 第 28 行“已应用的迁移文件禁止修改”。
- **观察/接受理由**：V9 未发布，允许在文件内完成 expand→contract；ADR 提供了回滚 SQL，且 M03 不承诺生产。但任何已应用 6b41244 版 V9（或 pre-tightening 版）的测试/预览库都会因 checksum 校验失败；滚动发布期间旧 API 实例仍可能插入 `visibility NULL`。
- **执行前提**：B1 必须记录非生产库重置（drop/recreate 或维护账号处理 `flyway_schema_history`）、旧 API 排空后再收紧；若某持久环境已应用 V9，改用 V10 前向迁移。验收时以“干净重建或 V10”二选一，不使用 `flyway repair` 掩盖。

### F-010（P2，open）B2/B3 跨任务依赖顺序未定义

- **位置**：ADR-0018 §2.2 第 119 行（移出部门时删除该用户在本部门 `CUSTOM` 空间的成员行，一次事务）；§8 B2（平台部门成员端点、`DepartmentAdminApiTest`）与 B3（`WorkspaceJdbc`/`WorkspaceService`/空间成员端点）。
- **观察**：B2 的“移出成员”需要清理 `workspace_member`（B3 的 workspace 包），B3 的 `requireManageable` 需要读部门/负责人（B2 的 department 包）。ADR 没有写任务顺序或共享接口；并行 Builder 会阻塞或把 SQL 复制到错误包。
- **期望**：明确顺序 B1a → B2（部门领域/仓储）→ B3（空间/成员清理）→ B4（内容贯通）→ B5（Web），并指定 `WorkspaceService` 暴露 `removeDepartmentMemberCustomRows` 供 B2 调用；或把清理逻辑划给 B2 的部门仓储并保持单事务。把该依赖写入 ADR §8 任务表。

### F-011（P3，open）威胁模型陈旧引用与 R-15 错误缓解

- **位置**：`docs/security/THREAT-MODEL.md:23`（A-01“OIDC 绑定（M03）”）；`:168`（S-07-1“M03 OIDC/MFA”）；`:182`（I-08-1“导出仅 M03 且需审批”）；`:421`（R-15“租户管理员可随时移交”）。
- **观察**：DEC-0011 把 OIDC 移到 M10，DEC-0013 把 MFA/审计查询/导出后移；DEC-0015 明确只有平台管理员能任命/移交负责人。R-15 的缓解措施“租户管理员可随时移交”与 DEC-0015 相反。
- **期望**：把上述行更新为 M10/平台管理员；R-15 改为“平台管理员可随时移交；租户管理员无部门写权限”。P3 文档债务，不阻断 M03 设计，但应在 M03 gate 前修正。

### F-012（P3，accepted）ADR-0007 正文仍标 M03

- **位置**：`docs/adr/ADR-0007-audit-event-model.md:55-56`（`data.exported`/`audit.queried` 标 M03）、`:106`（M03 平台管理员查询全部租户）、`:112`（M03 评估哈希链）；`:140` 变更日志只澄清第 6 节。
- **观察**：DEC-0013 已把三项后移；正文与变更日志并存会让 M10 实现者误读。已接受为 P3 文档债务；建议在 M10 设计前补一条覆盖第 2/7 节的变更说明。

### F-013（P3，accepted）M03-AC-21 的测试 ID 未映射

- **位置**：`docs/PRODUCT.md:194`（测试线索 `E2E-M03-07`、`@a11y`）；ADR-0018 §8 M03-V（`E2E-M03-01/02`、axe）；PRODUCT §8.5（M03 只验部门管理页）。
- **观察**：M03 只覆盖部门管理页，ADR 用 `E2E-M03-01/02` + `@a11y` 承载；`E2E-M03-07` 未在 ADR 的 M03 计划中出现。建议在 ADR §8 或 M03 评审包明确“AC-21 M03 段由部门管理页 a11y 覆盖，E2E-M03-07 留给 M10 全旅程”。P3。

### F-014（P3，accepted）F-017 只完成 ADR-0009，未完成 ADR-0014

- **位置**：`docs/PRODUCT.md:217`（F-017 在 M03 文档收口）；`docs/adr/ADR-0009...` 已加 M03 澄清；`docs/adr/ADR-0014-m02-api-event-contract-and-sse.md:35` 仍只写“下载只返回预签名 URL”，未记录同源 `content` 读取路径。
- **观察**：F-017 是 M02 已接受 P3，PRODUCT 指定 M03 文档收口；本轮只改 ADR-0009。建议 M03 文档任务补 ADR-0014 或在 M03 评审包记录 no-impact 理由。P3。

### F-015（P3，accepted）PermissionCodes 注释与 M03 新增权限码矛盾

- **位置**：`platform/platform-domain/src/main/java/com/riccon/platform/domain/identity/PermissionCodes.java:6-8`（“新增权限码必须同时更新 V2……M03 的部门数据范围通过额外 scope 过滤实现，不改变这里的码值”）。
- **观察**：M03 实际新增 `department.read`/`platform.department.manage`；B1 需新增常量并同步注释为“V2 + V9”，否则代码注释误导后续维护。P3。

## 残余风险与未验证项

- R-11 历史 `TENANT` 空间全租户可见，M03 不转换；F-004 要求清单与干净 E2E 租户。
- R-01 知识库租户级共享；ADR §3.4 已明示，M05 解决。
- R-12/R-13/R-14：MFA、审计哈希链、平台跨租户审计明细按 DEC-0013 后移，威胁模型已登记；M03 不声称解决。
- 未执行：V9 回滚副本演练、seed V1–V8 → V9 数据迁移、`commands.security`、M03 E2E/axe、完整 `./mvnw verify`。这些必须在 B1/M03 gate 前补证据，不能以本轮架构评审代替。
- 未评审：M10 的 OIDC/审计查询/导出设计（本任务范围外），只确认 M03 契约未提前提供可用路径。

## 变更边界与 ADR 状态

- `6b41244`/`d468a8d` 只改 `docs/adr/**`、`docs/ARCHITECTURE.md`、`docs/security/THREAT-MODEL.md`、`contracts/**`、`.trivyignore`、V9、`ApiApplicationTests`、`SchemaVersionChecker`；均在 TASK-0033 写范围内。未改 `docs/PRODUCT.md`、`AGENTS.md`、`docs/CODING-STANDARDS.md`，未写功能代码/业务测试。
- ADR-0018 `Status: proposed`，Reviewers/Approved-by/Review-date 均存在，未自标 accepted；ADR README 同步为 proposed。
- ADR-0011 续期事实、`.trivyignore` `exp:2027-03-31`、补偿控制 1–5、重开条件 1–4 与 DEC-0012 一致；独立核对 Maven Central 也支持 2.1.0-BETA1 → MCP SDK 0.17.2。
- 独立证据：EV-0304（迁移测试，exit 0，dirty=false）、EV-0305（OpenAPI lint，exit 0，dirty=false）；均绑定 HEAD `a2d7ebc`。

## 是否可进入 Builder 阶段

**否，先修 F-001/F-002**。两者都是计划可执行性问题，不需要推翻 ADR 的数据模型：

1. 拆分 B1a/B1b 或调整 V9 收紧顺序（F-001）。
2. 把 `AuditDetailsPolicy`、`ErrorCodes` 与相关测试纳入明确写范围（F-002）。
3. 在建任务卡时处理 F-003..F-010 的验收口径、依赖顺序与迁移测试；P3 文档债务在 M03 gate 前收口。

修复后重新跑定向验证（迁移测试 + lint + contract validate）并复审；在此之前 TASK-0033 不应标 done，M03 不应进入 Builder。
