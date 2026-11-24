---
review_id: REV-TASK-0222-001
module_id: M06
task_id: TASK-0222
reviewer:
  agent: riccon/verifier
  session: ses_df04c5fd5ffemuyeb1krzhqb7x
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 6b33cd97e5f51b919b61349469fa14c3a5df9f14
  - 6a9a27a92160f3fdca9061f39a22043d647b4118
  - e3158f8efe20e7d4e919d9d6785d8afd17fd9a6c
  - edd0b310f5b17827a85758d45a30d673a6f7df92
  - ca651583e2075a8e6fc11d2fa9093d9e8ea75765
head_commit: ca651583e2075a8e6fc11d2fa9093d9e8ea75765
code_commit: 6b33cd97e5f51b919b61349469fa14c3a5df9f14
evidence_head: edd0b310f5b17827a85758d45a30d673a6f7df92
baseline_commit: fe32e80
related_reviews:
  - REV-TASK-0220-001
  - REV-TASK-0221-001
  - REV-TASK-0221-002
review_scope: >-
  M06 T04 任务级验收：发布不可变版本（完整 PUBLISH 校验、NO_APPROVER、runScope/部门限制、
  graph_sha256、发布指针、审计、资源级幂等、并发与 V15 触发器）、版本列表、停用/启用、所有权移交，
  以及 AC-03/15/16 的 T04 段、403/404 矩阵、审计 fail-closed、范围与证据复核。不重审 T05 运行准入/
  运行绑定、T06 执行器、T10 UI、T16a E2E/axe/perf/security 与 M06 模块 gate；不替代 Product 预验收
  或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
dod_note: >-
  acceptance=true 仅指 T04 核心发布/版本/生命周期范围；任务卡第 4/5 条（F-001 create/duplicate 幂等、
  F-002 validate runScope）未在 T04 代码中实现，已由 Lead 在评审期间以 ca65158 修订契约并创建
  TASK-0241（T04b）承接，见 F-001/F-002，属 accepted residual。build/tests=true 指独立复跑编译与
  定向测试通过，未跑合同全量 build/test/coverage/E2E/security/perf/a11y；HEAD ca65158 的
  WorkflowContractM06Test 当前 1/6 失败（F-003），M06 gate 前必须由 TASK-0241 收敛。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（TASK-0241 T04b 完成）"
    title: "F-001 幂等收敛未在 T04 落地：publish 仍强制 Idempotency-Key，create/duplicate 仍只校验长度"
    summary: >-
      任务卡第 4 条要求关闭 REV-TASK-0220-001 F-001。T04 代码仍按旧契约要求 publish 必须携带
      Idempotency-Key（WorkflowPublishController.java:81-97,123-129），而 ca65158 已把 publish 改为
      资源级幂等并移除该 header；create/duplicate 仍只校验长度（WorkflowDefinitionController.java:245-254）。
      publish 自身的 graph_sha256+runScope+departmentId 资源级幂等已实现且经测试（WorkflowPublishService.java:136-144；
      WorkflowPublishApiTest.java:196-252），但键级语义与新契约仍差 T04b 的代码/测试同步。
    accepted_reason: >-
      这是评审前已登记的 P2 residual，且 Lead 已在 ca65158 明确选择“create/duplicate 不承诺键级重放、
      publish 资源级幂等”并新建 TASK-0241（T04b，owner=builder-b2，写范围覆盖 publish/definition/
      validation 与 WorkflowContractM06Test）。T04 代码/证据在 6b33cd9/edd0b31 对当时契约全绿；
      该 residual 不涉及跨租户、权限或审计数据丢失，资源级幂等已防止重复版本；但 M06 gate 前必须完成
      T04b，否则 HEAD 契约与实现不一致。
    evidence: >-
      WorkflowPublishController.java:81-97,123-129；WorkflowDefinitionController.java:245-254；
      WorkflowPublishService.java:42-51,136-144；contracts/openapi.yaml:2566-2570,2678-2682,2750-2755；
      adr/ADR-0024 §16.1；TASK-0241。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（TASK-0241 T04b 完成）"
    title: "F-002 VALIDATE 未支持 runScope/departmentId，与 PUBLISH 范围口径仍不一致"
    summary: >-
      任务卡第 5 条要求关闭 REV-TASK-0221-001 F-002。ca65158 已给 POST /validate 增加可选
      runScope/departmentId 并说明“带范围时复用 PUBLISH 校验，省略时不含范围”
      （contracts/openapi.yaml:2713-2731；ADR-0024 §16.2），但 T04 代码未改 validate 端点/服务，
      当前 validate 仍只按图/审批口径校验，不接受范围参数。T04 的 publish 端已独立执行范围校验
      （WorkflowPublishService.java:135,257-288），所以 T04 发布路径本身不受影响；T10 若把 validate
      的 valid=true 当作可发布信号，仍需 T04b 补齐。
    accepted_reason: >-
      该事项在 T03 评审中已作为 P2 accepted（REV-TASK-0221-001 F-002），T04 写范围不含
      validation/controller；Lead 已在 ca65158 完成契约/ADR 收敛，并由 TASK-0241 实现参数与测试。
      T04 发布路径的范围校验独立存在且经测试，故不阻塞 T04 核心；M06 gate 前必须由 T04b 完成，
      T10 必须发送当前选择范围，T05 运行准入必须按版本 run_scope 再校验（见剩余风险 1）。
    evidence: >-
      contracts/openapi.yaml:2713-2731；ADR-0024 §16.2；WorkflowPublishService.java:257-288；
      WorkflowValidationApiTest（T03 现有行为）；TASK-0241。
  - id: F-003
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（TASK-0241 T04b 完成）"
    title: "HEAD ca65158 后 WorkflowContractM06Test 1/6 失败：契约已移除 Idempotency-Key，测试/控制器仍是旧口径"
    summary: >-
      ca65158 从 create/duplicate/publish 移除 Idempotency-Key 并改为资源级语义，但
      WorkflowContractM06Test.java:99-112 仍断言 create/publish 声明该参数；独立复跑
      `./mvnw -q -pl apps/api -am test -Dtest=WorkflowContractM06Test ...` 在 HEAD ca65158 为
      6 tests / 1 failure（失败点 `[/api/v1/workflows]`）。同时 WorkflowPublishApiTest.java:262
      仍断言 publish 缺省 key 返回 400。TASK-0241 的写范围包含 WorkflowContractM06Test 与
      workflow/publish、definition、validation 测试，用于同步该口径。
    accepted_reason: >-
      该失败不是 T04 代码在 6b33cd9 上的回归：EV-1032 时点 WorkflowContractM06Test 为 6/0/0/0，
      且 ca65158 是评审期间 Lead 的契约变更提交（评审开始时 HEAD 为 edd0b31）。该失败是确定性的
      契约/测试漂移，已由 TASK-0241 明确承接，无产品/安全行为缺陷；但它是当前 HEAD 的红灯，
      M06 gate 前必须修复，不能以本 T04 pass 代替模块 gate。
    evidence: >-
      WorkflowContractM06Test.java:99-112；WorkflowPublishApiTest.java:254-281；
      contracts/openapi.yaml:2566-2575,2678-2687,2750-2765；TASK-0241。
evidence:
  - EV-1031
  - EV-1032
  - EV-1033
reviewed_evidence:
  - EV-1032
  - EV-1033
notes: >-
  评审开始时 HEAD 为 edd0b31，代码提交 6b33cd9；评审期间 Lead 在同一工作树提交 ca65158
  （契约/ADR 幂等与 validate 范围收敛）并创建 TASK-0241（未提交），因此当前 HEAD 前移且工作树含
  Lead 的 .riccon 变更。本评审只新增本文件，未修改源码、测试、迁移、契约、PRODUCT、AGENTS 或
  .riccon 状态；未运行 gate check。独立复跑在 edd0b31（代码与 6b33cd9 相同）完成；HEAD ca65158
  的契约测试红灯见 F-003。
created_at: "2026-11-25T06:47:00+08:00"
---

# REV-TASK-0222-001：M06 T04 发布/版本/停用启用/移交独立评审

## 结论

**通过（pass，带 accepted residual）**。T04 在代码提交 `6b33cd9` 上实现了发布不可变版本、
`graph_sha256`/`run_scope`/`department_id`/`node_count`、发布指针与同事务审计、资源级重复发布幂等
（该口径随后由 `ca65158` 契约化）、版本列表、停用/启用、所有权移交与审计。独立复跑 5 个受影响 API 测试类（32 个用例）与
`ModuleBoundaryTest`（12 个用例），**0 failures / 0 errors / 0 skipped，两个命令 exit 0**；
`spotless:check` 独立复跑 exit 0；EV-1032/EV-1033 的 commit、dirty、exit code 与输出 hash 复核一致。

但本 verdict 有三个边界，必须同时阅读：

1. 任务卡第 4/5 条（F-001 create/duplicate 幂等、F-002 validate runScope）**未在 T04 代码中落地**。
   Lead 在评审期间以 `ca65158` 修订契约/ADR，并创建 `TASK-0241`（T04b，owner=builder-b2）承接。
   本评审将其记入 F-001/F-002 的 **P2 accepted residual**，不据此判 T04 fail；但 M06 gate 前必须完成。
2. `ca65158` 后 **HEAD 当前是红灯**：`WorkflowContractM06Test` 独立复跑 6 个用例中 1 个失败
   （`/api/v1/workflows` 不再声明 Idempotency-Key，但测试仍断言声明）。这不在 T04 代码的写范围，
   由 TASK-0241 同步；见 F-003。**本 pass 不代表 HEAD 可直接过模块 gate。**
3. T05 运行准入必须按所选发布版本的 `run_scope` 再校验；T04 的版本列表/发布范围判定不能替代运行准入，
   见剩余风险 1。

## 评审对象与范围

- 任务卡：`.riccon/tasks/TASK-0222.json`，baseline `fe32e80`，状态 `review`，evidence
  `EV-1031/EV-1032/EV-1033`。
- 代码提交：`6b33cd97e5f51b919b61349469fa14c3a5df9f14`（29 个文件，+3670/-9；生产/测试均落在
  `apps/api/src/**/workflow/{publish,version}/**`）。
- 证据提交：`6a9a27a92160f3fdca9061f39a22043d647b4118`（EV-1032）、
  `e3158f8efe20e7d4e919d9d6785d8afd17fd9a6c`（EV-1033）；任务状态提交 `edd0b31`。
- 评审开始时 HEAD `edd0b31`；评审期间 Lead 在前一提交之上做了 `ca65158`（仅 contracts/README、
  contracts/openapi.yaml、ADR-0024，不改源码/测试/V15），并创建 `TASK-0241`（工作树未提交）。
  本评审的代码判断以 `6b33cd9` 为准，契约判断同时参考 `ca65158` 后的 HEAD。

任务卡 write_scope 为 version/publish 生产与测试路径；`6b33cd9` 未触碰 `contracts/**`、
`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql` 或 J1/J2 生产代码（`git diff --stat
fe32e80..6b33cd9 -- contracts docs/PRODUCT.md AGENTS.md apps/api/src/main/resources/db/migration`
为空；`git diff --check fe32e80..6b33cd9 -- apps/api/src/main apps/api/src/test` 干净）。

## 发布路径

### 完整校验与失败原子性

- 控制器：`POST /api/v1/workflows/{workflowId}/publish` 要求 `workflow.publish`，校验
  `Idempotency-Key` 8..128 长度，并从真实 `CurrentUser` 重新计算权限后交给服务
  （`WorkflowPublishController.java:81-97,123-129`）。请求体用 `JsonNode` 白名单解析，未知字段、
  非法 `runScope`、`DEPARTMENT` 缺 `departmentId`、非 `DEPARTMENT` 带 `departmentId` 均返回 400
  `RICCON-WF-400`（`WorkflowPublishRequestParser.java:31-49`）。
- 服务顺序为：`requirePublisher` → 读取/可见性 → `SELECT ... FOR UPDATE` 行锁 → 锁内重读快照并重查
  可见性 → 解析范围/部门 → 计算 `graph_sha256` → 资源级幂等短路 → PUBLISH 校验 → 取版本号 →
  插入 `workflow_version` → 更新 `workflow_definition` 指针 → 同事务写 `workflow.published`
  （`WorkflowPublishService.java:116-198`）。
- 校验在 `publishRepository.insertVersion` 与 `markPublished` 之前执行
  （`WorkflowPublishService.java:146-171`），`WorkflowValidationException` 由全局映射为 400
  `RICCON-WF-400`；因此失败不会写版本、不会改指针。`NO_APPROVER` 由 PUBLISH profile 的
  `WorkflowValidationRequest.publish(tenantId, actor.userId())` 触发
  （`WorkflowPublishService.java:146-151`），测试用“审批人=发布者本人”的图证明 SAVE 可通过但
  PUBLISH 返回 400，并断言版本数、指针、审计均不变（`WorkflowPublishApiTest.java:99-128`）。
- `runScope`/部门规则：`TENANT_ADMIN` 仅 `ONLY_SELF`/`TENANT`；`WORKFLOW_EDITOR` 仅
  `ONLY_SELF`/`DEPARTMENT`，且 `DEPARTMENT` 必须是本人当前负责的启用部门
  （`WorkflowPublishService.java:257-288`）。测试覆盖 editor 发布 TENANT 拒绝、发布他人管理部门
  拒绝、admin 发布 DEPARTMENT 拒绝、范围变更发布新版本，且失败路径版本数保持 0/既有值
  （`WorkflowPublishApiTest.java:130-194,254-281`）。

### 成功写入与不可变性

- 版本行写入 `version_no`、规范化 `graph_json`、`graph_sha256`、`node_count`、`run_scope`、
  `department_id`、`published_by`、`published_at`（`WorkflowPublishJdbc.java:94-115`；
  `WorkflowVersionInsert.java` 构造校验），并更新 `published_version_id/published_version_no`
  （`WorkflowPublishJdbc.java:128-144`）。`graph_sha256` 用 `WorkflowGraphJson` 规范化后计算；
  测试从 MySQL JSON 列重新解析计算并断言与请求响应、数据库列一致
  （`WorkflowPublishApiTest.java:42-65`），覆盖“JSON 列重排键/空白”这一实际风险。
- V15 的 `UNIQUE (tenant_id, workflow_id, version_no)`、`chk_workflow_version_department` 与
  `BEFORE UPDATE/DELETE` 触发器构成数据库层保证
  （`V15__workflow_studio.sql:84-97,503-513`）；测试直接 `UPDATE`/`DELETE` 版本行并断言
  `DataAccessException`，随后版本数与快照不变（`WorkflowPublishApiTest.java:237-251`）。

### 重复发布与并发

- 资源级幂等：同一草稿的 `graph_sha256 + runScope + departmentId` 与当前发布版本一致时，直接返回
  既有版本，不写第二条版本、不写第二条发布审计（`WorkflowPublishService.java:136-144`）。测试用
  不同 Idempotency-Key 连续发布两次，断言返回同一版本 ID、版本数 1、发布审计 1
  （`WorkflowPublishApiTest.java:196-216`）。这与 `ca65158` 后的新契约“publish 资源级幂等、不承诺
  键级重放”一致；但控制器仍要求 header，见 F-001。
- 并发：方法是 `@Transactional(isolation = READ_COMMITTED)`，先对 `workflow_definition` 行
  `SELECT ... FOR UPDATE`，锁内用普通读重新加载完整快照，再读取发布指针和现有版本
  （`WorkflowPublishService.java:116-144`；`WorkflowPublishJdbc.java:53-86`）。这个边界是正确的：
  MySQL 默认 REPEATABLE READ 下，加锁前的普通读会建立快照，后到事务在锁内重读仍可能看到旧指针；
  READ_COMMITTED 让每条语句取最新提交视图，行锁则阻止并发写。测试用两个线程同时发布同一草稿，
  两个请求都 201 且返回同一版本 ID，版本数和发布审计均为 1（`WorkflowPublishApiTest.java:283-324`）。
- 死锁/重复键：同一流程的发布、保存草稿、停用/启用、移交都以 `workflow_definition` 行为串行化点；
  发布先锁定义行，再插子表版本，再更新同一父行，事务内不反向获取其他流程的锁，未发现锁序反转；
  `UNIQUE` 是最后兜底。不同草稿并发发布、并发停用/移交没有专门测试，见“未验证”。

## 版本与生命周期

### 版本列表

- `GET /api/v1/workflows/{workflowId}/versions` 要求 `workflow.read`，用 `PageQuery.of` 限制
  `page>=1`、`1<=size<=100`（`WorkflowVersionController.java:83-91`）。服务先做可见性判定，再
  `COUNT` + 按 `version_no DESC, id DESC` 分页（`WorkflowVersionService.java:108-119`；
  `WorkflowVersionJdbc.java:51-79`）。SQL 不选 `graph_json`，响应 DTO 也只有版本摘要
  （`WorkflowVersionJdbc.java:57-63`；`WorkflowVersionSummaryResponse`）。
- 测试覆盖：owner 首版、ONLY_SELF 对普通员工 404、范围改为 TENANT 后员工可见全部版本元数据并按
  版本号倒序、`page=2&size=1` 分页、响应不含 `graphJson` 与 `SECRET-PROMPT-MARKER`、跨租户 404、
  平台管理员 404（`WorkflowVersionLifecycleApiTest.java:24-81`）。
- 注意：可见性按“最近发布版本的 runScope”判定，而不是逐版本判定；v1=ONLY_SELF、v2=TENANT 时
  员工会看到 v1 的元数据（无图）。这是当前 T02/T04 的一致实现，但与 ADR §6.2 “按版本 run_scope”
  存在解释空间；T05 必须按所选版本再校验运行准入，见剩余风险 1/2。

### 停用/启用

- 只有所有者或租户管理员可执行；先检查 `workflow.publish`，再按“所有者/管理员直接允许；同租户
  可见但非所有者 403；私有草稿/不可见 404”判定（`WorkflowVersionService.java:310-324`）。
- `changeStatus` 先用快照比较目标状态，重复状态返回 409；再用
  `WHERE status=:expectedStatus AND owner_user_id=:expectedOwnerUserId` 条件更新，0 行即 409，
  避免并发覆盖（`WorkflowVersionService.java:238-265`；`WorkflowVersionJdbc.java:96-120`）。
  成功后同事务写 `workflow.disabled`/`workflow.enabled`，details 为 `workflowId/previousStatus/status`
  （`WorkflowVersionAuditEvents.java:40-55`）。
- 测试覆盖：owner 停用后 `status=DISABLED`、`canRun=false`、版本仍可读、管理员也可停用；重复停用/
  启用 409 且不重复审计；owner/管理员交替启用后状态与审计计数正确
  （`WorkflowVersionLifecycleApiTest.java:83-163`）。权限矩阵覆盖私有草稿 404、TENANT 可见非所有者
  403、普通员工 403、跨租户 404、平台管理员 404（`:165-199`）。

### 所有权移交

- 仅租户管理员（`workflow.manage`）可执行；目标必须是本租户 ACTIVE 且具备 `workflow.edit` 的成员；
  目标不存在 404，目标与当前 owner 相同 409，目标停用/无编辑权 400（`WorkflowVersionService.java:185-225`）。
  更新使用 `WHERE owner_user_id=:expectedOwner` 条件更新，0 行 409；同事务写
  `workflow.owner.transferred`，details 为 `workflowId/fromUserId/toUserId`
  （`WorkflowVersionJdbc.java:133-154`；`WorkflowVersionAuditEvents.java:70-92`）。
- 测试覆盖：owner/其他编辑者非管理员 403；admin 移交成功、响应与 DB owner 更新、精确 from/to 审计；
  原所有者随后读取 404（失去编辑权/可见性），新所有者可保存并发布
  （`WorkflowVersionLifecycleApiTest.java:201-245`）；随机不存在目标 404、MEMBER 目标 400、停用目标
  400、重复 owner 409、空/未知字段 400，且不产生审计（`:247-294`）。跨租户目标用户未单独造数，但
  目标查询按当前租户 `findById(tenantId, targetUserId)` 限定（`WorkflowVersionService.java:201-203`），
  见“未验证”。

## 权限与租户边界

- 发布：私有草稿对其他 `WORKFLOW_EDITOR` 404；已发布 TENANT 对非 owner 编辑者 403；普通员工因无
  `workflow.publish` 被权限拦截器 403；跨租户编辑者 404；平台管理员经
  `WorkflowPlatformTenantFilter` 404（`WorkflowPublishApiTest.java:326-354`）。
- 版本/停用：版本列表对 ONLY_SELF 普通员工、跨租户、平台管理员均 404；停用矩阵同上
  （`WorkflowVersionLifecycleApiTest.java:24-81,165-199`）。
- 平台管理员 404 由 T01 的 filter 在权限拦截器前短路，覆盖
  `/workflows`、`/workflow-runs`、`/workflow-approvals`、`/workflow-templates` 前缀；T04 复用该
  filter，没有在 controller 内先做 403。
- CSRF：新写端点均走既有 Spring Security CSRF 链，测试通过 `csrfToken()` 发起；未发现新增绕过。
  `Idempotency-Key` 的键级语义缺口见 F-001。
- 跨部门：代码用 `activeDepartmentId` + `withinScope` 判定，逻辑与 T02 详情一致；但 T04 测试只显式
  覆盖 ONLY_SELF/TENANT 与“他人管理部门”发布拒绝，**没有**独立的“跨部门访问版本列表/停用 404”
  用例，见“未验证”。J1/J2 生产代码未被本次提交修改；T02/T03 的 Definition/Validation 测试在
  EV-1032 中通过。

## 审计

- 发布审计只写 `workflowId/workflowVersionId/versionNo/runScope/departmentId/nodeCount`
  （`WorkflowPublishAuditEvents.java:43-77`），与 ADR §8.1 白名单一致；非 DEPARTMENT 显式写
  `departmentId=null`，`AuditDetailsPolicy` 的 nullable 白名单允许（`AuditDetailsPolicy.java:297-301,359`）。
  测试断言 details 含版本/范围/节点数且不含 `SECRET-PROMPT-MARKER`（`WorkflowPublishApiTest.java:67-85`）。
- 停用/启用/移交 details 只含 ID、前后状态、from/to（`WorkflowVersionAuditEvents.java`），测试断言
  精确值（Lifecycle 测试的相关段）。
- fail-closed：`AuditService.append` 不吞异常并加入调用方事务；发布/停用/移交的业务写入与审计在同一
  事务（`WorkflowPublishService.java:176-187`；`WorkflowVersionService.java:238-265,216-224`）。
  `WorkflowVersionAuditFailClosedTest` 用 `@MockitoBean AuditRepository` 只让目标动作抛
  `DataAccessResourceFailureException`，断言 HTTP 500、版本数 0/指针 null/状态 ACTIVE/owner 不变
  （`:30-90`）。该测试确实让真实 MySQL 事务回滚，而不是只断言异常；mock 只替代审计仓储，业务写入
  仍走真实 JdbcClient。
- 未单独覆盖：`workflow.enabled` 的审计失败回滚（与 disabled 共用同一 `changeStatus` 路径）；
  运行/审批/取消类审计属 T05/T08/T09。`workflow.published` 的“变更摘要”在 ADR 白名单中实际是
  版本号/范围/节点数，不含 graph diff；若 Product 对 AC-16 的“变更摘要”要求图级 diff，需要另行
  确认，见剩余风险 5。

## 并发与事务边界复核

- 发布使用 `READ_COMMITTED` + 定义行 `FOR UPDATE` + 锁内重读，解决了“锁前快照看不到先到者发布指针”
  和“发布使用旧草稿”两个竞态；`nextVersionNo` 的 `MAX+1` 在行锁内执行，唯一键兜底。
- 生命周期更新使用条件更新而不是读后写；`updateStatus` 同时校验 status+owner，`updateOwner` 校验
  owner，能检测“读后被移交/停用”的窗口并返回 409。
- 未发现死锁或重复版本的可复现证据；同一草稿并发发布用例通过。不同草稿并发发布、并发停用/启用/
  移交没有专门测试；这些路径的条件更新逻辑简单，但仍是 T16a 的补测项。

## AC 对账（T04 范围）

- **M06-AC-03（T04 部分，pass，运行绑定待 T05）**：发布生成不可变版本并显示版本号、发布人、
  发布时间、运行范围；修改草稿不发布不会改变已发布版本；范围变更必须发布新版本且旧版本保留原范围。
  T04 测试用直接插入的 `workflow_run` 证明发布 v2 后旧运行仍绑定 v1
  （`WorkflowPublishApiTest.java:218-235`）。真正的“运行默认绑定最近版本/运行详情显示版本号”属 T05，
  本评审未运行。
- **M06-AC-15（T04 部分，pass，跨部门/运行/审批矩阵待 T05/T16a）**：发布仅 owner/管理员且范围受
  角色/部门限制；停用/启用仅 owner/管理员并写审计；移交仅租户管理员，目标为本租户 ACTIVE 且有
  `workflow.edit`，原所有者失权并写审计；同租户可见无能力 403，私有/跨租户/平台管理员 404。
  跨部门 404、普通员工运行/取消、审批人范围、账号禁用等由 T05/T09/T16a 验证。
- **M06-AC-16（T04 部分，pass，运行/审批审计待 T05/T08/T09）**：发布、停用/启用、移交均有审计，
  发布含版本号/范围/节点数，停用/启用含前后状态，移交含 from/to；审计与业务同事务 fail-closed，
  不含 prompt/图/凭据/正文。运行的 requested/started/completed/failed、取消、审批决定、管理性读取
  由 T05/T08/T09 实现并验证。

## 证据复核

| 证据    | 命令（摘要）                         | commit    | dirty | exit | 输出/哈希            | 结论                                                    |
| ------- | ------------------------------------ | --------- | ----- | ---- | -------------------- | ------------------------------------------------------- |
| EV-1031 | 10 类 workflow 测试 + ModuleBoundary | `6b33cd9` | false | 0    | 133425 B / `cc8f17…` | 早期同命令取证，agent=unknown，已被 EV-1032 取代        |
| EV-1032 | 同 EV-1031                           | `6a9a27a` | false | 0    | 133425 B / `280ae2…` | agent=builder-b1；10 类实测 67 API + 12 ArchUnit，0/0/0 |
| EV-1033 | `./mvnw -q spotless:check`           | `e3158f8` | false | 0    | 249 B / `880a15…`    | 空 stdout，exit 0                                       |

- EV-1032 的 67 个 API 用例为：Publish 7、Lifecycle 5、AuditFailClosed 3、Definition 7、
  Validation 10、Validator 14、GraphDomain 4、GraphJson 6、PlatformTenantFilter 5、ContractM06 6；
  加 ArchUnit 12 共 79。**派单所述“73 tests + ArchUnit”与实测不一致**（更可能是计数口径/笔误，
  因为该 10 类命令在 EV-1032 时点的 surefire XML 均 0 failures/0 errors/0 skipped，exit 0）。
  本评审按实测报告记录，不按 73 复述。
- 独立复跑（评审时 HEAD `edd0b31`，代码与 `6b33cd9` 相同）：
  `./mvnw -q -pl apps/api -am test -Dtest='WorkflowPublishApiTest,WorkflowVersionLifecycleApiTest,WorkflowVersionAuditFailClosedTest,WorkflowDefinitionApiTest,WorkflowValidationApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  → exit 0，32 tests / 0/0/0；
  `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest='ModuleBoundaryTest' ...`
  → exit 0，12 tests / 0/0/0；`./mvnw -q spotless:check` → exit 0。测试类无 `@Disabled`/
  `assumeTrue`/skip 标记，执行的是真实 MySQL/Testcontainers + V15 + 真实会话/CSRF/权限链。
- 评审期间 HEAD 前移 `ca65158` 后，独立复跑
  `./mvnw -q -pl apps/api -am test -Dtest=WorkflowContractM06Test ...` → **exit 1，6 tests /
  1 failure**，失败在 `/api/v1/workflows` 的 Idempotency-Key 断言（见 F-003）。该失败不是 T04 代码
  回归，但它意味着 **HEAD 当前不能直接作为 M06 gate 的绿色基线**。

## 未验证

- 合同全量 `build`/`test`/`coverage`/`E2E`/`a11y`/`security`/`perf`/`migration rollback`；本任务只跑
  了受影响定向测试与 ArchUnit。模块 gate 仍需在最终提交上重跑合同命令并录 EV。
- T05 运行准入、运行绑定、并发上限、取消/重试；T06 执行器；T08/T09 运行历史与审批；T10/T14 前端
  入口与文案；T16a 跨部门/平台/越权矩阵、axe、375px、PERF-M06-001、J1/J2 全量 E2E。
- 跨部门访问版本列表/停用的 404 场景、并发停用/启用/移交、并发不同草稿发布、发布时结构/工具/
  模型等非 NO_APPROVER 失败类别的端到端 400、`workflow.enabled` 的审计失败回滚，均未单独覆盖。
- 按任务要求未运行 `riccon gate check --module M06`；本评审不替代模块 review、Product 预验收或
  用户最终验收。

## 剩余风险

1. **T05 必须按版本 run_scope 再校验运行准入**：T04 的版本列表可见性按“最近发布版本范围”判定；
   若 T05 只按流程/最近版本可见性放行，v1=ONLY_SELF、v2=TENANT 时普通员工可能用 versionId 运行
   v1（越权），v1=TENANT、v2=ONLY_SELF 时旧版本又可能不可见但“仍可运行”。T05 必须在创建运行时
   读取所选 `workflow_version.run_scope + department_id` 并按当前成员/部门重判，不能复用 T04 的
   列表 gate。此项 owner=T05/builder，due=T05 done / M06 gate 前。
2. **版本列表的“逐版本范围”歧义**：当前实现/T04 测试以最近版本范围 gate 整个列表；ADR §6.2 的
   “按版本 run_scope”若按字面逐版本解释，会在范围收窄/放宽时产生差异。建议 T05/T10/T16a 明确产品
   口径并补跨范围用例，避免把 v1 ONLY_SELF 的元数据暴露给 v2 后的租户成员（当前只暴露元数据，不含图）。
3. **HEAD 契约漂移**：ca65158 已改契约/ADR，但 T04 控制器仍要求 publish Idempotency-Key、T04 测试
   仍断言缺失 key 为 400、ContractM06 仍断言旧参数；TASK-0241 必须同步，否则 HEAD 测试红、
   M06 gate 不能通过。
4. **测试数据卫生**：`WorkflowVersionTestSupport.createTool` 在每个 fixture 插入全局 `mcp_server`/
   `mcp_tool` 与租户 grant，没有统一 cleanup；当前用例未出现跨测试污染，若后续改为全局精确计数，
   T16a 需补清理。
5. **AC-16“变更摘要”口径**：T04 发布审计按 ADR 白名单只写版本号/范围/节点数，不含 graph diff；
   若 Product 要求图级变更摘要，需要新增 details 字段/契约并补测试，当前按 ADR 口径通过。
6. **重复发布跳过重新校验**：资源级幂等命中在 PUBLISH 校验之前，若工具/审批人/模型在首次发布后
   失效，重复发布仍返回既有版本（不产生新版本）。这是资源级幂等的预期行为，且不扩大现有版本的
   运行风险（T05 运行前仍会执行前校验），但应在 T05/T16a 的运行前校验中覆盖。

## 是否可 done

**T04 任务级可 done（带上述 accepted residual）**：核心发布/版本/生命周期实现、审计 fail-closed、
触发器、并发幂等与权限矩阵在其提交/证据上通过独立复核；F-001/F-002 是已登记的 P2 residual，
由 TASK-0241（T04b）承接，不阻塞 T04 核心 done。**但 M06 模块 gate 前必须满足**：
TASK-0241 完成并让 `WorkflowContractM06Test`/publish 相关测试恢复绿色；T05 按版本 run_scope
实现运行准入；T16a 补跨部门/并发/axe/perf/security 全量。本评审未运行 gate check。
