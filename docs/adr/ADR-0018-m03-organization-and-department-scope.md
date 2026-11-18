# ADR-0018：M03 组织模型、空间可见性与部门数据范围

Status: proposed
Date: 2026-11-18
Reviewers: `riccon/verifier`（数据范围与越权可测试性）、`riccon/lead`（范围与门禁）、`riccon/ops`（迁移与回滚可运维性）
Approved-by: （待评审；M03 gate 前由 Lead 确认，不自行标 accepted）
Review-date: M03 gate 前（预计 2026-11-25）
Scope: M03「权限纵深与组织模型」；M10（OIDC、审计查询/导出）不在本 ADR 范围
关联: DEC-0010、DEC-0011、DEC-0012、DEC-0013、DEC-0014、DEC-0015、`docs/PRODUCT.md` §8/§9、ADR-0007（审计）、ADR-0009（租户隔离）、ADR-0012（数据模型）、ADR-0013（会话认证）、ADR-0015（模块边界）、ADR-0016（outbox 例外）、TASK-0033

## Context

M02 只有租户级 RBAC：`MEMBER` 与 `TENANT_ADMIN` 两个系统角色，`workspace.kind` 只有 `PERSONAL|TEAM`；个人空间仅本人可见，团队空间对本租户全部启用员工可见（`WorkspaceJdbc.FIND_ACCESSIBLE` 中的 `kind='TEAM'`）。会话、文件、报告任务和产物都通过 `workspace_id` 归属空间，访问判定统一经过 `WorkspaceService.requireAccessible`（11 个调用点）。这为 M03 提供了单一改造点，但团队空间“全租户可见”的语义必须换成部门可见范围。

M03 的产品验收（`docs/PRODUCT.md` M03-AC-01..07、19 部门段、20 回归、21 部门页）要求：

- 租户内单级部门、账号单主部门、负责人管理本部门空间；
- 空间可见范围为“本部门成员”或“指定成员”，个人空间保持仅本人；
- 会话、文件、报告/产物按空间归属隔离，跨部门与跨租户统一 404；
- 成员被移出部门或部门被停用后，下一次请求即失去访问；
- 部门维度越权套件零 skip、零 xfail；
- 知识库在 M03 仍是租户级共享，界面必须明示。

用户已拍板（DEC-0014）：单级部门、单主部门、每租户 1 个 OIDC IdP、知识库 M03 租户级共享并明示、OIDC 账号管理员显式绑定、审计导出由同租户另一管理员审批。DEC-0015 进一步确定：**部门管理主体是平台管理员**，跨租户管理租户部门元数据，复用 `/api/v1/admin/tenants/{tenantId}` 路径族；租户管理员不新增部门管理权限；部门负责人只管理本部门空间。DEC-0013 明确 MFA、审计哈希链、平台管理员跨租户审计明细不在 M03。DEC-0012 把 B-06 例外延至 2027-03-31 或 M07 gate（取早），与本 ADR 的数据范围决定相互独立。

本 ADR 只定 M03 的组织模型、空间可见性、数据范围执行点、权限与审计契约；OIDC 登录与审计查询/导出留给 M10。

## Decision

### 1. 组织模型：单级部门 + 单主部门

#### 1.1 表与字段

新增 `department`（租户内部门）：

| 列                                          | 类型                    | 约束                             | 说明                                                                 |
| ------------------------------------------- | ----------------------- | -------------------------------- | -------------------------------------------------------------------- |
| `tenant_id`                                 | `VARCHAR(64) ascii_bin` | 非空、`CHECK (tenant_id <> '')`  | 所属租户                                                             |
| `id`                                        | `BINARY(16)`            | 与 `tenant_id` 组成主键          | 应用生成 UUIDv7                                                      |
| `name`                                      | `VARCHAR(100)`          | 非空，`UNIQUE (tenant_id, name)` | 部门名；库排序规则大小写/重音不敏感                                  |
| `manager_user_id`                           | `BINARY(16)`            | 可空，逻辑引用 `app_user`        | 负责人；可空且会移交，使用逻辑引用 + 索引，由服务层校验同租户/同部门 |
| `status`                                    | `VARCHAR(16)`           | `ACTIVE` / `DISABLED`            | 停用语义                                                             |
| `created_by`                                | `BINARY(16)`            | 非空，逻辑引用 `app_user`        | 创建人；跨租户创建时是平台管理员 UUID，不建复合外键                  |
| `created_at` / `updated_at` / `disabled_at` | `DATETIME(6)`           | UTC                              | 时间口径与 ADR-0012 一致                                             |

`app_user` 增加 `department_id BINARY(16) NULL`，复合外键 `(tenant_id, department_id) → department (tenant_id, id)`，索引 `(tenant_id, department_id, status)`。

`department.created_by` 不建外键：平台管理员属于 `platform` 保留租户，跨租户创建部门时无法满足 `(tenant_id, created_by) → app_user` 的复合外键；该列只作审计辅助，权威操作者仍是 `audit_event.actor_id`。

#### 1.2 单主部门：直接列 vs 关联表

| 方案                                      | 强制单主部门                                                                          | 查询成本              | 历史/多部门扩展                   | 结论                       |
| ----------------------------------------- | ------------------------------------------------------------------------------------- | --------------------- | --------------------------------- | -------------------------- |
| `app_user.department_id` 直接列           | 结构上只能有一个值；复合外键阻止跨租户                                                | 一次 join 或直接读取  | 无成员历史；多部门需新表          | **采用**                   |
| `department_member` 关联表 + `is_primary` | MySQL 无部分唯一索引，需要生成列/触发器保证“至多一个 primary”，且“至少一个”仍要靠应用 | 每次访问多一次 exists | 可记录加入/移出历史、可扩展多部门 | 拒绝                       |
| 关联表 + 每账号一行（`user_id` 唯一）     | 可强制单主                                                                            | 多一次 join           | 未来多部门要改主键                | 拒绝：与直接列等价但更啰嗦 |

选择直接列的理由：M03 的产品口径是“单主部门”，用一列就能让“不可能有两个部门”成为 schema 事实，而不是应用约定；成员变更历史由审计事件承担（`department.member.added/removed`），不需要在 M03 建第二套历史表。多部门归属是明确后移项（DEC-0014），将来另立 ADR 和迁移，不在本条上叠加。

#### 1.3 负责人、停用与唯一性

- 负责人必须是本部门成员且账号 `ACTIVE`；由服务层校验（负责人列是逻辑引用，没有复合外键）。负责人不是 RBAC 角色，而是 `department.manager_user_id` 关系，随部门读取、下一次请求生效，不需要重新登录或刷新会话。
- 停用部门只把 `status` 改为 `DISABLED` 并写 `disabled_at`，**不**批量清空 `app_user.department_id`，也**不**归档其空间：
  - 成员归属仍保留，便于审计与重新启用；
  - 访问判定必须同时检查 `department.status='ACTIVE'`，所以停用后成员立即失去该部门空间访问（M03-AC-07）；
  - 重新启用即恢复原成员关系，避免“停用后成员全丢”的不可逆操作。
- 部门名在租户内全局唯一（含停用部门）。停用部门不参与新空间创建，但名称仍占用；管理员应优先重新启用，而不是新建同名部门。这样 `UNIQUE (tenant_id, name)` 不需要生成列或部分索引，行为可预测。
- 只允许重命名 `ACTIVE` 部门；停用部门视为冻结，重新启用后再改。
- 只有当前负责人可以被移出部门：移出负责人必须先移交（409），避免出现“负责人不在本部门”的悬挂状态。
- 成员数（列表/详情）只统计 `status='ACTIVE'` 的账号；停用账号保留 `department_id` 但不计入成员数。

#### 1.4 管理主体：平台管理员跨租户管理元数据

- 部门是**平台级组织元数据**，由平台管理员跨租户管理；复用 M02 的 `/api/v1/admin/tenants/{tenantId}` 路径族，权限码 `platform.department.manage`（仅 `PLATFORM_ADMIN`）。
- 平台管理员只能操作目标租户的部门、成员归属、负责人和组织元数据：不能读取该租户的会话、文件、报告、产物、知识库正文或审计明细；部门响应只含组织字段与账号展示名，不含业务内容。
- 目标租户必须存在且 `ACTIVE`；不存在统一 404 `RICCON-TENANT-404`，已暂停返回 409 `RICCON-CONFLICT-409`。目标租户代码只从路径参数读取，不能由请求体覆盖。
- 租户管理员**不获得**部门管理权限；M02 的 `tenant.user.manage`/`tenant.role.manage` 语义不变。租户管理员可通过个人资料/`GET /api/v1/departments` 读取本租户部门与成员列表（只读，用于空间管理），但没有创建/改名/停用/成员/负责人权限。
- 部门负责人仍只管理本部门空间（创建/改名/可见性/CUSTOM 成员），不管理其他部门空间、不创建/停用部门、不调整部门成员归属。
- **AC 口径说明**：M03-AC-01 保持“平台管理员在租户 A 的部门管理页”。M03-AC-07 中“负责人把她移出部门/移交负责人”的部门成员变更步骤按 DEC-0015 由平台管理员执行；负责人失去/获得的是**空间管理能力**，该 AC 的最终断言（移出后下一次请求 404、移交后旧负责人失去管理入口）不变。此差异需在 M03 评审包向 verifier 明示，不改 PRODUCT。

### 2. 空间模型

#### 2.1 `workspace` 扩展

新增两列：

| 列                              | 说明                                                                                                      |
| ------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `department_id BINARY(16) NULL` | 所属部门；`PERSONAL`、历史 `TENANT` 空间为空                                                              |
| `visibility VARCHAR(16) NULL`   | `PRIVATE` / `TENANT` / `DEPT` / `CUSTOM`；M02 旧代码写入期间允许 `NULL`，Builder 在发布前直接修正 V9 收紧 |

可见性语义：

| 值        | 适用                                   | 谁可访问                                               | 可新建               |
| --------- | -------------------------------------- | ------------------------------------------------------ | -------------------- |
| `PRIVATE` | `kind=PERSONAL`                        | 仅 `owner_user_id`                                     | 系统自动创建个人空间 |
| `TENANT`  | `kind=TEAM` 且 `department_id IS NULL` | 本租户全部启用员工（M02 遗留语义）                     | **否**，仅迁移回填   |
| `DEPT`    | `kind=TEAM` 且 `department_id` 非空    | 该部门当前启用成员                                     | 是（默认）           |
| `CUSTOM`  | `kind=TEAM` 且 `department_id` 非空    | 该部门当前启用成员中，显式加入 `workspace_member` 的人 | 是                   |

- **不保留“TEAM 无部门可新建”**：M03 之后，创建团队空间必须提供 `departmentId`，可见性只能是 `DEPT` 或 `CUSTOM`；`TENANT` 只用于迁移 M02 已有的团队空间，服务层拒绝创建/更新为 `TENANT`。
- **为什么保留 `TENANT`**：M02 团队空间本来是全租户可见。若迁移时强行改成 `DEPT`，必须替管理员猜一个部门，可能把内容暴露给错误人群；若改成 `CUSTOM` 且只保留所有者，会让现有成员突然失去访问。保留 `TENANT` 是唯一不改变既有可见面的选择，并把“转为部门空间”变成显式的数据迁移动作。它是**只读历史状态**，不是产品能力。
- **M03 不提供 `TENANT` 转换端点**：转换需要把业务空间搬迁到部门，涉及内容可见面变化与批量成员清理，超出“平台管理员只管理组织元数据”的边界。历史空间清单在 M03 评审记录中列明，转换在 M04/M09 设计专用迁移；在转换前它们保持 M02 可见面（R-11）。
- 部门一旦创建不可变更。跨部门搬移空间会把内容暴露给新部门，M03 不做；需要时新建空间并在后续模块设计搬迁。

#### 2.2 `workspace_member`

仅 `CUSTOM` 空间使用；`DEPT` 的成员关系由 `app_user.department_id` 派生，不冗余写行。

| 列                                     | 说明                                           |
| -------------------------------------- | ---------------------------------------------- |
| `tenant_id`、`workspace_id`、`user_id` | 复合主键；复合外键到 `workspace` 与 `app_user` |
| `granted_by`                           | 复合外键到 `app_user`；记录操作人              |
| `created_at`                           | UTC                                            |

约束与规则：

- 显式成员必须是该空间所属部门的当前成员；加人时校验，跨部门成员一律拒绝（不泄露对方部门信息）。
- 访问判定同时要求“当前部门成员”与“显式成员行”存在，所以成员被移出部门后，残留的 `workspace_member` 行不会授予访问；移出部门时仍删除该用户在本部门 `CUSTOM` 空间的成员行（一次事务内），避免重新加入部门时恢复旧共享。
- `DEPT → CUSTOM`：清空旧成员行，空间对新 `CUSTOM` 为空（只有负责人/管理员能管理）；UI 必须提示“请添加成员”。
- `CUSTOM → DEPT`：删除全部成员行，可见范围回到全部门。
- 个人空间不使用 `workspace_member`；任何写入个人空间成员行的请求返回 400/404。

#### 2.3 空间管理能力

| 操作                      | 平台管理员（`platform.department.manage`） | `TENANT_ADMIN`     | 部门负责人             | 普通成员       |
| ------------------------- | ------------------------------------------ | ------------------ | ---------------------- | -------------- |
| 创建/重命名/停用/启用部门 | 任意租户（目标租户须启用）                 | 否                 | 否                     | 否             |
| 加入/移出部门成员         | 任意租户                                   | 否                 | 否                     | 否             |
| 任命/移交负责人           | 任意租户                                   | 否                 | 否                     | 否             |
| 创建团队空间              | 否（不管理业务空间）                       | 任意启用部门       | 仅本部门（且部门启用） | 否             |
| 重命名/改描述             | 否                                         | 任意团队空间       | 仅本部门空间           | 否             |
| 改可见性/成员             | 否                                         | 任意团队空间       | 仅本部门空间           | 否             |
| 历史 `TENANT` 转部门      | 否（M03 不提供）                           | 否                 | 否                     | 否             |
| 读取部门/成员列表         | 否（用 admin 端点）                        | 本租户全部（只读） | 本部门（只读）         | 本部门（只读） |
| 读取空间内容              | 否（不读取业务内容）                       | 仅可访问空间       | 仅可访问空间           | 仅可访问空间   |

平台管理员**不因管理员身份自动获得业务内容读取权**；其部门端点只返回组织元数据与账号展示名。租户管理员不获得部门管理权限，但保留 M02 的 `workspace.write` 空间管理能力；部门负责人只管理本部门空间；内容访问对所有角色使用同一空间谓词。该选择比 M02 更严格，是 M03 的有意收紧。

### 3. 数据范围执行点

#### 3.1 唯一访问判定

`WorkspaceService.requireAccessible(tenantId, workspaceId, userId)` 继续作为**唯一**内容访问入口；所有列表、详情、下载、报告、产物、运行详情都必须先经过它。实现下沉到 `WorkspaceJdbc` 的固定 SQL 片段，禁止在 controller 或各业务仓储里复制可见性规则。

判定谓词（示意，参数绑定由 `TenantJdbc` 强制 `tenant_id = :tenantId`）：

```sql
JOIN app_user actor
  ON actor.tenant_id = w.tenant_id
 AND actor.id = :userId
 AND actor.status = 'ACTIVE'
LEFT JOIN department d
  ON d.tenant_id = actor.tenant_id
 AND d.id = actor.department_id
WHERE w.tenant_id = :tenantId
  AND w.id = :workspaceId
  AND w.deleted_at IS NULL
  AND (
       (w.kind = 'PERSONAL' AND w.owner_user_id = actor.id)
    OR (w.kind = 'TEAM' AND w.visibility = 'TENANT')
    OR (w.kind = 'TEAM' AND w.visibility = 'DEPT'
        AND actor.department_id IS NOT NULL
        AND w.department_id = actor.department_id
        AND d.status = 'ACTIVE')
    OR (w.kind = 'TEAM' AND w.visibility = 'CUSTOM'
        AND actor.department_id IS NOT NULL
        AND w.department_id = actor.department_id
        AND d.status = 'ACTIVE'
        AND EXISTS (SELECT 1 FROM workspace_member m
                     WHERE m.tenant_id = w.tenant_id
                       AND m.workspace_id = w.id
                       AND m.user_id = actor.id))
  )
```

- 列表复用同一谓词（`countAccessible` / `listAccessible`），不允许“列表过滤一套、详情另一套”。
- 对象级服务（会话、文件、报告、产物、运行）先按 `(tenant_id, objectId)` 取对象，再调用 `requireAccessible(object.workspaceId)`；对象不存在、跨租户、跨部门、个人空间他人，全部统一 `RICCON-TENANT-404`，响应体不含对象类型之外的元数据。
- 平台管理员的部门管理操作走 `/api/v1/admin/tenants/{tenantId}/...`，只操作组织元数据；部门负责人的空间管理使用 `requireManageable`：先判定管理能力，再判定可见性；对不可见空间返回 404，对可见但无管理能力返回 403，避免用 403 泄露其他部门空间存在。
- 成员移出部门、部门停用、负责人移交都在下一次请求生效，因为判定读取的是数据库当前关系，不读取会话中的部门/角色快照。

#### 3.2 对象级读写矩阵

| 对象     | 列表/详情/下载             | 创建               | 修改/删除                                         | 说明                                         |
| -------- | -------------------------- | ------------------ | ------------------------------------------------- | -------------------------------------------- |
| 会话     | 空间成员                   | 空间成员           | 标题修改：创建人；停止生成：创建人或该 run 发起人 | 消息追加对空间成员开放，满足 AC-04“继续使用” |
| 文件     | 空间成员                   | 空间成员           | 删除：上传者                                      | 下载仍需空间访问                             |
| 报告任务 | 空间成员                   | 空间成员           | 取消/重试：创建人                                 | 审批仍由 `report.approve` + 租户内可见性约束 |
| 产物     | 空间成员                   | 仅运行产生         | M03 无删除 API                                    | 下载地址签发前再次校验空间                   |
| 知识库   | 全租户成员（M03 明确口径） | `knowledge.manage` | `knowledge.manage`                                | 见 3.4                                       |

`created_by` 不再作为共享空间会话的读取条件；个人空间仍只允许所有者（由空间谓词保证）。M02 中“团队空间按创建人列会话”的测试断言由 M03 更新，数据不删除。

#### 3.3 缓存与失效

M03 **不引入**跨请求共享缓存，访问判定每请求查库。这样成员移出、部门停用、负责人移交无需失效逻辑，满足 AC-07“下一次请求即无法访问”。

若后续模块引入缓存（M04+），必须：

- 键以 `tenantId` 开头，并包含 `userId`、`workspaceId`、关系版本；
- 在部门成员变更、部门停用/启用、负责人变更、空间可见性/成员变更时显式失效；
- 纳入 `TEN-016` 与 `DEPT-*` 用例，未通过不得启用缓存。

#### 3.4 知识库仍为租户级

M03 不新增文档级/部门级 ACL（M05）。`knowledge_document`、`knowledge_chunk` 与 OpenSearch 索引继续按租户隔离，同租户员工可检索全部租户知识库。界面必须在知识库页与检索结果处显示固定文案：

> 知识库为全租户共享，文档级权限在后续版本提供。

引用与源文件的边界：

- 知识库引用快照（标题、片段、定位）可跨部门展示，因为知识库本身是租户级；
- “打开源文件/下载原文”仍走空间访问判定：源文件属于其他部门空间时返回 404，界面不得显示可点击的打开入口，只显示来源文件名与“源文件不可访问”；
- 任何位置不得出现“按部门隔离知识库”或“文档级权限已生效”的声明（M03-AC-06）。
- 个人空间文件只有被显式“加入知识库”后才进入租户级检索；该动作是发布行为，UI 必须在提交前明示“加入后全租户可检索”。未加入知识库的个人空间文件不会出现在任何部门/租户检索结果中（M03-AC-05）。
- 发布权限仍为 `knowledge.manage`（仅 `TENANT_ADMIN`，不改变 M02 角色语义）；发布者必须先能访问源文件，因此租户管理员若要发布部门文件，必须同时是该部门成员/空间成员。M03 不把知识库管理权限授予部门负责人或普通成员。

### 4. 角色与权限

新增权限码（全局 `permission` 字典，迁移写入）：

| 权限码                       | 含义                                                   | `PLATFORM_ADMIN` | `TENANT_ADMIN` | `MEMBER` / 部门负责人 |
| ---------------------------- | ------------------------------------------------------ | ---------------- | -------------- | --------------------- |
| `platform.department.manage` | 跨租户管理租户部门元数据（不含业务内容）               | 是               | 否             | 否                    |
| `department.read`            | 读取本人所属部门；租户管理员可读本租户全部部门（只读） | 否               | 是             | 是                    |

- 不新增 `department.manage` 租户权限：部门管理主体是平台管理员（DEC-0015），租户管理员不获得任何部门管理能力；部门负责人只管理本部门空间。
- `department.read`：普通员工只能读**自己所属部门**与成员列表；租户管理员可读本租户全部部门与成员列表（只读，用于空间管理选部门/选成员），不获得任何部门写入能力。平台管理员的部门列表走 `/admin/tenants/{tenantId}/departments`。
- 平台管理员即使拥有 `platform.department.manage`，也不能读取目标租户的会话、文件、报告、产物、知识库正文或审计明细；部门端点只返回组织字段与账号展示名。

既有权限码不改变语义：

- `workspace.read`：读取可访问空间与其内容；M03 收紧为部门数据范围，不改变“读”的含义。
- `workspace.write`：创建/修改团队空间；仅 `TENANT_ADMIN` 持有。部门负责人不通过角色获得该权限，而由服务层按 `department.manager_user_id` 做资源级授权。
- `tenant.user.manage`、`tenant.role.manage`、`knowledge.*`、`report.*` 保持不变。
- `platform.tenant.manage` 仍是平台租户/账号管理入口权限；部门元数据使用独立的 `platform.department.manage`，便于后续把组织管理从租户生命周期管理中拆分。

不新增 `DEPARTMENT_MANAGER` 角色码。原因：

- 负责人是部门上的可移交关系，不是租户级角色；用角色表达会出现“负责人角色”和 `manager_user_id` 两份事实，移交时容易漂移；
- 每次请求都从数据库读取负责人关系，移交下一次请求生效，符合 AC-07；
- 空间管理端点仍要求已认证 + 基础权限（`workspace.read`），具体资源能力在服务层判定，安全套件覆盖“普通成员直接调用管理端点被 403/404”。

授予规则：

- 平台管理员只能管理目标租户的部门、成员归属、负责人；目标租户必须存在且 `ACTIVE`，不能读取业务内容（见 2.3、1.4）。
- 部门负责人只能管理本部门空间（创建/改名/可见性/CUSTOM 成员）；对其他部门的空间统一 404，对本部门空间的内容可见但无管理能力时 403。
- 负责人不能调整部门成员归属、不能创建/停用部门、不能任命负责人；这些操作只由平台管理员执行。
- 不能自我提权：普通成员调用空间管理端点得到 403/404；租户管理员调用平台部门端点得到 403。

现有租户的角色权限由 V9 回填；新租户由 `RoleDefinitions` 在开通时创建（Builder 更新常量）。平台管理员角色在 `platform` 保留租户获得 `platform.department.manage`；V9 对既有角色回填，空库由 `BootstrapAdminRunner` 在启动时按更新后的 `RoleDefinitions` 创建。

### 5. 审计动作与 details 白名单

新增 `AuditTargetType.DEPARTMENT`。动作名（`platform-domain` 常量，与 ADR-0007 的命名一致）：

| 动作                           | 目标         | details 允许字段（白名单）                                                 |
| ------------------------------ | ------------ | -------------------------------------------------------------------------- |
| `department.created`           | `DEPARTMENT` | `targetTenantId`、`departmentId`、`name`、`status`                         |
| `department.renamed`           | `DEPARTMENT` | `targetTenantId`、`departmentId`、`name`、`previousName`                   |
| `department.disabled`          | `DEPARTMENT` | `targetTenantId`、`departmentId`、`status`、`memberCount`                  |
| `department.enabled`           | `DEPARTMENT` | `targetTenantId`、`departmentId`、`status`、`memberCount`                  |
| `department.member.added`      | `DEPARTMENT` | `targetTenantId`、`departmentId`、`userId`、`previousDepartmentId`         |
| `department.member.removed`    | `DEPARTMENT` | `targetTenantId`、`departmentId`、`userId`、`removedWorkspaceMembers`      |
| `department.manager.changed`   | `DEPARTMENT` | `targetTenantId`、`departmentId`、`managerUserId`、`previousManagerUserId` |
| `workspace.created`            | `WORKSPACE`  | `workspaceId`、`departmentId`、`visibility`                                |
| `workspace.updated`            | `WORKSPACE`  | `workspaceId`、`name`、`previousName`（仅变更时）                          |
| `workspace.visibility.changed` | `WORKSPACE`  | `workspaceId`、`visibility`、`previousVisibility`、`departmentId`          |
| `workspace.member.added`       | `WORKSPACE`  | `workspaceId`、`userId`                                                    |
| `workspace.member.removed`     | `WORKSPACE`  | `workspaceId`、`userId`                                                    |

规则：

- **跨租户部门管理的审计租户**：沿用 M02 `TenantUserAdminService` 的口径——事件 `tenant_id` 写操作者租户（平台管理员为 `platform`），目标租户只进入 `details.targetTenantId`，`target_id` 写部门 ID；`actor_id` 是平台管理员账号 UUID。实现上业务读写使用目标租户作用域，审计写入前切回 `platform` 作用域（`TenantContext.runWithTenant`），再恢复目标租户，避免审计事件落到目标租户。ADR-0007 的租户字段语义不变，不把事件写到目标租户，也不写两条重复事件。M10 审计查询若要让目标租户管理员看到平台来源事件，需另立查询模型，M03 不声称。
- **租户内空间管理的审计租户**：部门负责人/租户用户在租户内操作，事件 `tenant_id` 写其所属租户，`details` 不含 `targetTenantId`。
- 只记录 UUID、计数、枚举、部门名；禁止记录用户名、显示名、邮箱、会话内容、文件名、提示词、凭据或部门成员名单。
- 批量操作（停用部门、移出部门成员导致 `workspace_member` 清理）只记录计数，不逐人展开，避免审计膨胀和 PII 扩散。
- 审计写入与业务同事务（ADR-0007 fail-closed）；审计写失败则操作失败。
- 负责人移交写一条 `department.manager.changed`，不写两条“移除/添加负责人”。

### 6. 迁移策略（V9 单迁移，发布前收紧）

#### 6.1 V9（本任务交付，尚未发布）

1. 新建 `department`、`workspace_member`。
2. `app_user` 增加 `department_id` + 复合外键 + 索引。
3. `workspace` 增加 `department_id`、`visibility` + 外键 + 索引 + 宽松 CHECK（允许 `visibility IS NULL`，保证 M02 旧代码在滚动发布期间仍可插入）。
4. 回填：`PERSONAL → PRIVATE`，`TEAM → TENANT`（不猜部门、不改成员可见面）。
5. 权限字典写入 `department.read`、`platform.department.manage`：
   - `department.read` 回填给既有 `MEMBER`/`TENANT_ADMIN` 角色；
   - `platform.department.manage` 只回填给 `platform` 保留租户的 `PLATFORM_ADMIN` 角色；
   - **不**创建/授予 `department.manage`，租户管理员不获得部门管理权限。
6. `department.created_by` 是逻辑引用，不建复合外键：平台管理员属于 `platform` 租户，跨租户创建时无法满足目标租户的 `app_user` 复合外键；权威操作者在 `audit_event.actor_id`。
7. 创建/删除均不触碰 M02 既有行，除上述两列回填；不删除、不改类型、不重建表。

#### 6.2 发布前收紧（Builder 直接修正 V9，不新增 V10）

V9 尚未发布，Builder 在 API 代码已全部写 `visibility` 后**直接修正 V9**：

1. 把旧代码可能写入的 `visibility IS NULL` 行按 `kind` 回填为 `PRIVATE`/`TENANT`；
2. `visibility` 改 `NOT NULL`；
3. 收紧 CHECK 为：

```sql
(kind = 'PERSONAL' AND visibility = 'PRIVATE' AND department_id IS NULL)
OR (kind = 'TEAM' AND visibility = 'TENANT' AND department_id IS NULL)
OR (kind = 'TEAM' AND visibility IN ('DEPT', 'CUSTOM') AND department_id IS NOT NULL)
```

这是 ADR-0010 的 expand → contract 两阶段，但两个阶段都在 V9 文件内完成，不新增 V10；V9 在 M03 发布前可变，发布后按 Flyway 校验和冻结。

#### 6.3 回滚

Flyway 不做 undo；首选“前向修复 + 备份恢复”。若必须回到 V8（V9 已应用）：

```sql
DROP TABLE workspace_member;
ALTER TABLE workspace DROP FOREIGN KEY fk_workspace_department;
ALTER TABLE workspace DROP INDEX idx_workspace_department;
ALTER TABLE workspace DROP CHECK chk_workspace_scope;
ALTER TABLE workspace DROP COLUMN visibility;
ALTER TABLE workspace DROP COLUMN department_id;
ALTER TABLE app_user DROP FOREIGN KEY fk_app_user_department;
ALTER TABLE app_user DROP INDEX idx_app_user_department;
ALTER TABLE app_user DROP COLUMN department_id;
DROP TABLE department;
DELETE FROM role_permission WHERE permission_code IN ('department.read', 'platform.department.manage');
DELETE FROM permission WHERE code IN ('department.read', 'platform.department.manage');
```

执行前必须停 API/Worker 写入并做全量备份；`flyway_schema_history` 的 V9 行由维护账号按 ADR-0010 处理，禁止应用账号删除。生产回滚演练在 M09 完成（B-13）。

#### 6.4 迁移归属

只有 `apps/api` 执行 Flyway（ADR-0010）；`apps/worker` 保持 `flyway.enabled=false`，启动时校验 `flyway_schema_history` 最大版本与代码期望一致。V9 落地时把 `SchemaVersionChecker.EXPECTED_SCHEMA_VERSION` 从 8 升到 9（本任务已同步）；不新增 V10，版本保持 9。worker 不读取部门/可见性，不需要改查询。

### 7. API 与错误语义

所有路径在 `/api/v1`，分页 `page`/`size` 与现有约定一致，写操作要求 `X-CSRF-TOKEN`。

#### 7.1 端点

租户内只保留读取本部门与空间管理；部门管理全部在 `/api/v1/admin/tenants/{tenantId}`。

| 方法   | 路径                                                                    | 最小权限                     | 资源范围                     | 说明                                                         |
| ------ | ----------------------------------------------------------------------- | ---------------------------- | ---------------------------- | ------------------------------------------------------------ |
| GET    | `/departments`                                                          | `department.read`            | 本人部门；租户管理员全部     | 普通员工只返回本人部门；租户管理员可读本租户全部部门（只读） |
| GET    | `/departments/{departmentId}/members`                                   | `department.read`            | 本人部门；租户管理员任意部门 | 分页成员；普通员工访问其他部门 404                           |
| GET    | `/departments/{departmentId}/workspaces`                                | `workspace.read` + 服务判定  | 租户管理员或本部门负责人     | 管理用空间元数据列表                                         |
| POST   | `/departments/{departmentId}/workspaces`                                | `workspace.read` + 服务判定  | 租户管理员或本部门负责人     | 在部门下创建空间；部门来自路径                               |
| POST   | `/workspaces`                                                           | `workspace.write`            | 租户管理员                   | 创建任意启用部门的空间；必须给 `departmentId`                |
| PATCH  | `/workspaces/{workspaceId}`                                             | `workspace.read` + 服务判定  | 租户管理员或本部门负责人     | 改名/描述/可见性；`TENANT` 不可转换                          |
| GET    | `/workspaces/{workspaceId}/members`                                     | `workspace.read` + 服务判定  | 租户管理员或本部门负责人     | 列出 `CUSTOM` 显式成员                                       |
| POST   | `/workspaces/{workspaceId}/members`                                     | `workspace.read` + 服务判定  | 同上                         | 加入显式成员；必须为本部门成员；重复 409                     |
| DELETE | `/workspaces/{workspaceId}/members/{userId}`                            | `workspace.read` + 服务判定  | 同上                         | 移出显式成员；不存在 204                                     |
| GET    | `/workspaces`                                                           | `workspace.read`             | 可访问范围                   | 只返回可访问空间；不返回其他部门名称/数量                    |
| GET    | `/admin/tenants/{tenantId}/departments`                                 | `platform.department.manage` | 目标租户                     | 平台管理员列出目标租户部门                                   |
| POST   | `/admin/tenants/{tenantId}/departments`                                 | `platform.department.manage` | 目标租户                     | 创建部门；名称冲突 409                                       |
| PATCH  | `/admin/tenants/{tenantId}/departments/{departmentId}`                  | `platform.department.manage` | 目标租户                     | 重命名；停用部门只读                                         |
| POST   | `/admin/tenants/{tenantId}/departments/{departmentId}/disable`          | `platform.department.manage` | 目标租户                     | 幂等停用                                                     |
| POST   | `/admin/tenants/{tenantId}/departments/{departmentId}/enable`           | `platform.department.manage` | 目标租户                     | 幂等启用                                                     |
| GET    | `/admin/tenants/{tenantId}/departments/{departmentId}/members`          | `platform.department.manage` | 目标租户                     | 分页成员                                                     |
| POST   | `/admin/tenants/{tenantId}/departments/{departmentId}/members`          | `platform.department.manage` | 目标租户                     | 加入成员；已在其他/本部门 409                                |
| DELETE | `/admin/tenants/{tenantId}/departments/{departmentId}/members/{userId}` | `platform.department.manage` | 目标租户                     | 移出成员；不存在 204；移除负责人 409                         |
| PUT    | `/admin/tenants/{tenantId}/departments/{departmentId}/manager`          | `platform.department.manage` | 目标租户                     | 任命/移交负责人；同一人 200 幂等                             |

#### 7.2 请求/响应要点

- `WorkspaceCreateRequest`：`name`、可选 `description`、必填 `departmentId`（UUID）、可选 `visibility`（默认 `DEPT`，只允许 `DEPT|CUSTOM`）。
- `WorkspaceUpdateRequest`：可选 `name`、`description`、`visibility`（只允许 `DEPT|CUSTOM`）；`TENANT` 空间在 M03 不可转换。
- `Workspace` 响应新增 `departmentId`（可空）、`departmentName`（可空）、`visibility`（M03 起必填）；`kind` 保持 `PERSONAL|TEAM`。
- `Department`：`id`、`name`、`status`、`managerUserId`（可空）、`managerDisplayName`（可空，仅管理界面）、`memberCount`、`createdAt`、`updatedAt`。
- 平台管理员部门端点与租户部门端点使用同一 schema；`targetTenantId` 只在路径参数中出现，不从请求体读取。
- `DepartmentMember`：只含 `id`、`username`、`displayName`、`status`，不复用 `UserSummary`，避免把邮箱、角色与最近登录时间暴露给部门负责人。
- 列表排序：部门按 `name` 升序、`id` 升序；成员按 `displayName`（服务端固定，不接受客户端排序字段）。

#### 7.3 403 / 404 / 409

| 场景                                                                             | 状态    | 错误码                                     | 说明                                             |
| -------------------------------------------------------------------------------- | ------- | ------------------------------------------ | ------------------------------------------------ |
| 对象不存在、跨租户、跨部门、个人空间他人                                         | 404     | `RICCON-TENANT-404`                        | 与普通 404 同状态、同码、同结构；不泄露存在性    |
| 目标租户不存在 / 部门不属于目标租户                                              | 404     | `RICCON-TENANT-404`                        | 平台管理员部门端点统一 404，不区分不存在与不匹配 |
| 已认证但缺少管理能力（能看见空间但不是负责人/管理员）                            | 403     | `RICCON-AUTH-403`                          | 响应不含空间内容                                 |
| 其他部门空间的管理操作                                                           | 404     | `RICCON-TENANT-404`                        | 不让对方负责人探测空间存在                       |
| 租户管理员/普通用户调用平台部门端点                                              | 403     | `RICCON-AUTH-403`                          | 平台部门端点要求 `platform.department.manage`    |
| 目标租户已暂停                                                                   | 409     | `RICCON-CONFLICT-409`                      | 租户存在但不可写；响应不含租户业务内容           |
| 部门名重复、账号已在其他/本部门、重复空间成员                                    | 409     | `RICCON-DEPT-409`                          | `detail` 只给稳定原因码，不回显成员名单          |
| 非法组合（`TENANT` 新建/转换、个人空间加成员、跨租户账号加入、停用部门新建空间） | 400/404 | `RICCON-REQUEST-400` / `RICCON-TENANT-404` | 参数级拒绝；跨租户账号不泄露存在性               |
| 停用/启用已处于目标状态                                                          | 200     | —                                          | 幂等；仅在状态真正变化时写审计                   |
| 移出不存在成员                                                                   | 204     | —                                          | 幂等                                             |
| 移除当前负责人                                                                   | 409     | `RICCON-DEPT-409`                          | 先移交                                           |

`RICCON-DEPT-409` 是新增的稳定错误码，只用于部门/空间成员关系冲突；不改变既有错误码语义。

#### 7.4 OpenAPI 与 deferred

`contracts/openapi.yaml` 升 `info.version` 为 `1.1.0-m03`，新增 Departments 标签、部门/成员/可见性端点与 schema；M03 已实现的路径不得标 `x-riccon-deferred`。M10 项（OIDC/SSO、审计查询、审计导出、平台跨租户审计）标 `x-riccon-deferred`，不得在 M03 契约中给出可用路径。`contracts/internal-api.yaml` 无变更（M03 不改 API→Worker 契约）。

### 8. 交付计划与 Builder 边界

M03 拆成 5 个 Builder 任务 + 1 个 Verifier 独立验证任务。写范围不重叠；每个任务 `write_scope ≤ 8`、`acceptance ≤ 6`。Builder 写生产代码与代码级集成测试（`IT-DEPT-*`）；Verifier 独立写 `DEPT-001..010` 安全套件、`E2E-M03-01/02` 与 a11y，不改生产代码。

| 任务                        | 内容                                                                                                                  | 主要写范围                                                                                                                                                                              | 验收（≤6）                                                                                                     | 测试归属                                            |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| M03-B1 领域词汇与 V9 收紧   | `WorkspaceVisibility`、`department.read`/`platform.department.manage`、审计常量、`RoleDefinitions`；发布前直接收紧 V9 | `platform/platform-domain/src/main/java/com/riccon/platform/domain/**`、`apps/api/.../identity/domain/RoleDefinitions.java`、`apps/api/src/main/resources/db/migration/V9__*.sql`       | 权限码/枚举/审计动作存在；V9 回填 NULL 并收紧 CHECK；平台/租户角色含正确权限；ArchUnit 通过                    | Builder 单测 + 迁移测试                             |
| M03-B2 部门后端             | 平台管理员跨租户部门元数据端点、租户只读部门端点、成员/负责人、审计、错误语义                                         | `apps/api/src/main/java/com/riccon/api/admin/department/**`、`apps/api/src/main/java/com/riccon/api/department/**`、`apps/api/src/test/java/com/riccon/api/DepartmentAdminApiTest.java` | 平台管理员创建/改名/停用/启用；加/移成员单主部门；负责人移交；目标租户 404/停用 409；租户管理员 403；审计      | Builder `IT-DEPT-001/004`                           |
| M03-B3 空间可见性与访问判定 | `WorkspaceJdbc` 谓词、`WorkspaceService.requireAccessible/requireManageable`、空间成员端点、历史 TENANT 只读          | `apps/api/src/main/java/com/riccon/api/workspace/**`、`apps/api/src/test/java/com/riccon/api/WorkspaceApiTest.java`、`WorkspaceScopeTest.java`                                          | 列表只含可访问；跨部门 404；CUSTOM 成员；负责人只能管本部门；旧测试更新                                        | Builder `IT-DEPT-002/003`                           |
| M03-B4 内容贯通             | 会话/文件/报告/产物/运行改走空间访问；共享空间对象语义；知识库明示由 B5 做 UI                                         | `apps/api/src/main/java/com/riccon/api/chat/**`、`file/**`、`research/**`、`run/**`、`apps/api/src/test/java/com/riccon/api/*ScopeApiTest.java`                                         | 部门空间内会话/文件/报告可见可继续；个人空间他人 404；下载 404；审计不回归                                     | Builder 集成测试                                    |
| M03-B5 Web 与管理界面       | 平台管理员租户部门管理页、空间可见性/成员、知识库明示、路由/客户端类型；租户管理端不出现部门管理入口                  | `apps/web/config/routes.ts`、`apps/web/src/pages/admin/tenants/**`、`apps/web/src/pages/chat/**`、`apps/web/src/services/api/**`、`apps/web/src/services/workspaces/**`                 | 平台管理员部门 CRUD/成员/负责人 UI；空间可见性/成员；知识库固定文案；375 px 无横向滚动；错误/空/无权限态       | Builder 组件测试；Verifier `E2E-M03-01/02`、`@a11y` |
| M03-V 独立验证              | 部门维度越权套件、跨租户平台部门端点、M02 回归、E2E、a11y、评审                                                       | `apps/api/src/test/java/com/riccon/api/security/**`（或 verifier 约定目录）、`e2e/**`                                                                                                   | `DEPT-001..010` 零 skip；平台管理员只能碰元数据；`TEN-001..027` 回归；`E2E-M03-01/02`；axe 无 serious/critical | Verifier 独立写                                     |

验证命令（不改合同命令）：

```bash
./mvnw -q -T1C verify
./mvnw -q -T1C verify -Dgroups=tenant-isolation
./mvnw -q -T1C verify -Dgroups=dept-isolation
pnpm -w test
pnpm -w e2e
pnpm -w a11y
trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .
```

`DEPT-NNN` 使用 `@Tag("dept-isolation")`，与 `TEN-NNN` 同套件运行，不新增合同命令。M03 gate 只要求部门/空间段（`DEPT-001..010`）与 M02 回归通过；M10 扩展导出请求段。

## Consequences

- 正面：部门范围成为 schema + 单一 SQL 谓词的事实；成员移出/部门停用下一次请求生效；跨部门与跨租户统一 404；管理面与内容面分离；历史 M02 空间不被静默改写。
- 负面：`TENANT` 历史状态会在一段时间内保留全租户可见，M03 不提供转换，需在 M04/M09 设计迁移；单主部门不支持多部门兼职；部门/空间管理端点比 M02 多，测试面扩大；平台管理员只管理组织元数据，不能读取目标租户业务内容。
- 约束：所有内容访问必须经过 `WorkspaceService.requireAccessible`；新增共享缓存必须实现失效与用例；新增部门/空间表必须遵守复合键与 `tenant_id` 前缀；不得用 `TENANT` 新建空间；知识库不得声称部门隔离。

## Alternatives

- **多级部门树 / 多部门归属**：产品后移（DEC-0014），且会让数据范围取并集，越权歧义大；拒绝。
- **`department_member` 关联表**：历史与多部门扩展更好，但单主约束需要生成列/触发器，M03 成本高于收益；拒绝。
- **把 M02 团队空间猜一个部门回填**：可能把内容暴露给错误人群；拒绝。
- **把 M02 团队空间改成仅所有者可见的 `CUSTOM`**：会让现有成员突然失去访问，违反“不破坏 M02 数据”的可用性预期；拒绝。
- **租户管理员默认可读全部业务内容**：管理便利，但削弱部门数据范围且与最小权限冲突；拒绝。
- **为负责人新增 RBAC 角色**：角色与 `manager_user_id` 双写会漂移；拒绝。
- **知识库按部门隔离**：M03 无文档 ACL，声称隔离会造成虚假安全感；留到 M05；拒绝。
- **在 M03 引入缓存**：需要失效逻辑与更多用例，收益有限；拒绝。

## Evidence

- 迁移：`apps/api/src/main/resources/db/migration/V9__organization_and_workspace_scope.sql`（本任务；发布前由 Builder 在文件内收紧）。
- 现有访问入口：`apps/api/src/main/java/com/riccon/api/workspace/service/WorkspaceService.java`、`WorkspaceJdbc.java`；调用点见 codegraph（`ConversationService`、`FileService`、`ResearchTaskService`、`ArtifactService`、`RunDetailService`）。
- 隔离强制点：ADR-0009 第 3/4/5 节；`TenantJdbc` 的 `tenant_id = :tenantId` 守卫。
- 产品验收：`docs/PRODUCT.md` M03-AC-01..07、19 部门段、20、21。
- 决策：DEC-0010..0015。
- 威胁模型：`docs/security/THREAT-MODEL.md` TB-10、TM-23..TM-28、ABUSE-04、R-11..R-15。
