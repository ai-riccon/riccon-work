---
review_id: REV-TASK-0220-001
module_id: M06
task_id: TASK-0220
reviewer:
  agent: riccon/verifier
  session: ses_df0d0649fffefZeyJcUkreVN0a
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 50c6e89bedf2360f9a0aafc4180a9d613f4c48f9
head_commit: 50c6e89bedf2360f9a0aafc4180a9d613f4c48f9
baseline_commit: c1dc211
review_scope: >-
  M06 T02（TASK-0220）定义/草稿领域与仓储、草稿 API、draft_version 乐观锁、另存副本与模板复制、
  审计写入的独立任务评审。核对 ADR-0024 §3.3.1/§4.1/§4.5/§6.2/§8.1、PRODUCT §12 AC-01/02/03
  与契约 1.6.0-m06；不替代 M06 模块 gate、Product 预验收或用户产品验收。
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
  acceptance 指 T02 后端范围（AC-01 的草稿保存/重开/未发布不可运行/并发冲突与副本、模板复制）。
  AC-01 的“我的流程”列表/冲突提示 UI 与 E2E-M06-01 需 T10/T16a 产出，本任务未验证；coverage、
  security、perf、a11y 属模块 gate/T16a，本任务未运行。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-b1（T04/TASK-0222；或 Lead 决策后修订契约）
    due: "M06 gate 前（T04 开工时定）"
    title: "POST /workflows 与 /duplicate 的 Idempotency-Key 只校验长度，未实现“同键返回既有草稿”"
    summary: >-
      `WorkflowDefinitionController.java:109-123`（create）与 `:172-188`（duplicate）接收并校验
      Idempotency-Key 的 8..128 长度，但随后直接调用 `WorkflowDefinitionService.create/duplicate`，
      每次请求都生成新的 UUIDv7 并插入。观察：同一主体用同一 Idempotency-Key 连续 POST 两次会创建两条
      草稿；契约 `contracts/openapi.yaml:2568` 声明“同一 Idempotency-Key 返回既有草稿”，通用参数
      `:4509` 声明“同一主体、同一资源重复请求返回既有结果”，ADR-0024 §7.4 也要求同键返回既有结果。
      期望：重复键返回既有草稿（或按契约 409 处理冲突载荷）；若不实现，必须由 Lead 明确修订契约，
      不能保留已声明但未实现的行为。
    accepted_reason: >-
      AC-01 不依赖幂等键，header 为可选（`contracts/openapi.yaml:4508 required:false`），T02 只创建
      草稿且无越权/数据泄露；重复草稿是可恢复的用户可见重复，不是安全边界问题。故不阻塞 T02 done，
      但 M06 gate 前必须实现或经 Lead 决定修订契约；T04/T05 的发布/运行/重试/审批决定端点仍按 ADR
      要求幂等键必填，不能沿用当前只校验不生效的做法。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/definition/controller/WorkflowDefinitionController.java:109-123,172-188,230-239；
      contracts/openapi.yaml:2568,4505-4513；docs/adr/ADR-0024-m06-workflow-orchestration.md:556
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "T03（TASK-0221）开工前"
    title: "targetNodeKey 未按契约 pattern 校验，保存可写入永远无法解析的审批目标"
    summary: >-
      `WorkflowGraphJson.java:261` 只用 `optionalText(..., 64)` 校验长度，
      `WorkflowGraphNodeConfig.java:124-127` 只拒绝空白与超长，没有校验契约
      `contracts/openapi.yaml:8176-8178` 的 `pattern: ^[A-Za-z0-9_-]{1,64}$`。观察：保存/解析
      `{"targetNodeKey":"bad key"}` 与 `{"targetNodeKey":"a!b"}` 均成功；而节点 ID 受同一 pattern
      约束，因此这类值永远无法匹配任何节点。期望：保存阶段按契约 pattern 返回 400 `RICCON-WF-400`，
      或由 T03 校验器在 validate/publish 明确拒绝未知/非法目标并给出节点级问题。
    accepted_reason: >-
      T02 保存允许未完成草稿，targetNodeKey 不参与执行且不会被拼接为 SQL/路径；当前影响是草稿可存一个
      不可解析的审批目标，T03 的审批可达性/目标节点校验是最终守卫。风险有界，但 T03 不能假设形状已校验
      pattern，必须逐项校验 targetNodeKey 指向直接后继的工具节点。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphJson.java:261；
      apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphNodeConfig.java:124-127；
      contracts/openapi.yaml:8176-8178
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "T10（TASK-0230）前端接入前"
    title: "保存解析比契约 schema 更严格：position 强制 x/y、空字符串与纯空白被拒绝"
    summary: >-
      `WorkflowGraphJson.java:229-237` 对 `position` 同时 `requireNumber("x")` 与 `requireNumber("y")`，
      但契约 `contracts/openapi.yaml:8206-8214` 的 position 没有 `required`（只允许 x/y 属性）；
      同一文件 `:263-264` 的 `resultFrom`/`resultTitle` 使用 `optionalText` 的 minLength=1，
      而契约 `:8184-8189` 只声明 `maxLength`。观察：schema 合法的 `"position":{"x":1}` 和
      `"resultFrom":""` 被 400 拒绝；`labels`/`name` 的纯空白同样被拒绝。期望：与 schema 对齐
      （补充 required/minLength），或明确记录“服务端更严格”的兼容口径。T10 表单当前会同时提交 x/y，
      暂不影响主路径。
    accepted_reason: >-
      拒绝的是不完整/空语义的草稿字段，不会造成越权或数据损坏；T10 生成的请求同时带 x/y 且名称/结果标题
      非空。契约 schema 与服务端双向偏差需在 T10 接入前统一，避免按 OpenAPI 生成的客户端/测试样例失败。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphJson.java:229-237,263-264；
      contracts/openapi.yaml:8184-8189,8206-8214
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b1（T05/TASK-0223）
    due: "T05 完成前"
    title: "DEPARTMENT 发布范围的列表/详情可见性在 T02 无集成测试"
    summary: >-
      `WorkflowDefinitionJdbc.java:289-298` 的 scope 谓词与 `WorkflowDefinitionService.java:433-443`
      的 `withinScope` 已实现 DEPARTMENT，但 `WorkflowDefinitionApiTest` 只覆盖 TENANT 与 ONLY_SELF
      的已发布版本夹具（`:261-302`）。缺少“本部门成员可见可运行 / 他部门成员 404 / 无部门成员 404 /
      部门停用后下一次请求失效”的 API 级断言。期望：T05 运行准入复用同一范围判定时补齐这些用例，
      避免 T02 的 SQL 分支只在静态检查下成立。
    accepted_reason: >-
      ADR-0024 §6.2 与任务拆分明确 DEPARTMENT 判定由 T05 复用并验收；T02 的 DEPARTMENT 分支不影响
      AC-01（草稿私有性）且无当前 UI 入口。风险有界，但 T05 不得只测 TENANT/ONLY_SELF。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/persistence/WorkflowDefinitionJdbc.java:289-298；
      apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionService.java:433-443；
      apps/api/src/test/java/com/riccon/api/workflow/definition/WorkflowDefinitionApiTest.java:261-302
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b1（T05/TASK-0223）
    due: "T05 完成前"
    title: "所有者移出 DEPARTMENT 后，详情 canRun=true 但 RUNNABLE 列表不包含该流程"
    summary: >-
      `WorkflowDefinitionService.java:481-484` 的 canRun 计算包含 `owner`，而
      `WorkflowDefinitionJdbc.java:264-272` 的 RUNNABLE 视图对非管理员只应用 `appendScopePredicate`
      （不含 owner 例外）。观察：一个 DEPARTMENT 范围流程的所有者被移出该部门后，
      `GET /workflows/{id}` 返回 `canRun=true`，但 `GET /workflows?view=RUNNABLE` 不返回该流程；
      ADR-0024 §6.2 要求“部门成员移出/部门停用：下一次请求按新范围判定”。期望：详情标志与列表/
      运行准入使用同一范围判定（或明确“所有者始终可运行自己的流程”并同步列表）。该不一致不应被
      T05 当作运行授权依据，运行准入必须服务端重新判定。
    accepted_reason: >-
      这是部门迁移后的边界状态，不是越权：canRun 只是 UI 提示，T05 必须服务端重新判定；当前无前端入口
      （T10 之后才消费该标志）。风险有界，但 T05/T10 接入前必须统一，避免“按钮可点但运行 403/404”。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionService.java:481-484；
      apps/api/src/main/java/com/riccon/api/workflow/persistence/WorkflowDefinitionJdbc.java:264-272,289-298；
      docs/adr/ADR-0024-m06-workflow-orchestration.md（§6.2 部门移出规则）
reviewed_evidence:
  - EV-1021
evidence:
  - EV-1021
notes: >-
  评审期间仓库 HEAD 因外部 TASK-0240（T01 ArchUnit 修复）前进到 8d0252f；该提交只改
  config/AppConfig.java、config/SecurityConfig.java 与新增 common/service/WorkflowFeatureAuditWriterAdapter.java，
  未触碰任何 T02 文件（git diff --name-status 50c6e89..8d0252f 已核对）。T02 的独立复跑在 50c6e89 上完成，
  并在 8d0252f 上复跑同一组测试确认无回归。
created_at: "2026-11-25T04:40:00+08:00"
---

# REV-TASK-0220-001：M06 T02 定义/草稿领域、仓储、草稿 API 与并发冲突独立评审

## 结论

**Pass（带 accepted residual）**。T02 在 `50c6e89` 上完成了契约 1.6.0-m06 要求的草稿领域/仓储/API：
`WorkflowGraph*` 严格白名单与规范化 SHA-256、`TenantJdbc` 租户隔离、UUIDv7、`draft_version` 条件更新
返回 409、草稿仅所有者/租户管理员可见、未发布不进入 RUNNABLE、另存副本与内置模板复制、发布版本只读摘要，
以及 `workflow.created`/`workflow.draft.saved` 同事务审计，details 不含 prompt/图/工具参数。
独立复跑 5 个测试类共 28 个用例，0 failures/0 errors/0 skipped；`spotless:check` 通过；T02 新增类不在
ArchUnit 违规列表。保留 1 个 accepted P2（幂等键未实现）和 4 个 accepted P3（targetNodeKey pattern、
schema 严格性、DEPARTMENT 测试缺口、canRun/列表范围不一致），均有 owner/due。

**外部 P0 不计入 T02**：T01 引入的 `ModuleBoundaryTest.mvcLayeringIsOneWay` config→service/controller
违例已建 TASK-0240；T02 未新增违例，模块 gate 以 TASK-0240 的评审/修复为准（详见文末）。

按任务要求未运行 `gate check --module M06`，未运行全量 E2E/perf/coverage/security/axe，未修改生产源码、
测试、迁移、契约、PRODUCT、AGENTS 或 `.riccon` 状态，也未把任务标 done。

## 评审范围与提交

| 项         | 值                                                                                                                |
| ---------- | ----------------------------------------------------------------------------------------------------------------- |
| baseline   | `c1dc211`（T01 评审残余关闭）                                                                                     |
| 代码       | `50c6e89`（T02 feat，38 文件，+5044 行；全部为新增文件）                                                          |
| 证据       | `EV-1021`（builder-b1，dirty=false，exit 0）；`EV-1020` 为同一命令/提交的旧快照（agent=unknown），以 EV-1021 为准 |
| 设计依据   | ADR-0024 §3.3.1/§4.1/§4.5/§6.2/§8.1、PRODUCT §12 AC-01/02/03、contracts 1.6.0-m06、docs/CODING-STANDARDS.md       |
| 评审时HEAD | 8d0252f（外部 TASK-0240 修复）；T02 文件在 50c6e89..8d0252f 之间无改动                                            |

`git diff --name-status c1dc211..50c6e89` 共 38 个新增文件，逐条命中任务卡 4 个 write_scope：

- `apps/api/src/main/java/com/riccon/api/workflow/definition/**`（controller/domain/dto/repository/service）；
- `apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraph*.java`（11 个图领域文件）；
- `apps/api/src/main/java/com/riccon/api/workflow/persistence/WorkflowDefinition*.java`（6 个持久化文件）；
- `apps/api/src/test/java/com/riccon/api/workflow/definition/**`（3 个测试类）。

未发现 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`、J1/J2 生产代码或 M06b
改动；`git diff --check` 干净。

## 任务验收对账

| 任务卡验收                                                                                                                                     | 结论                                                                                                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| 实现 workflow_definition 草稿读写、draftVersion 乐观锁、另存副本；AC-01 的保存/重开/未发布不可运行/并发冲突；证据 IT-WF-101、E2E-M06-01        | **后端 pass（E2E residual）**：IT-WF-101 全部覆盖且通过；E2E-M06-01 需要 UI，T02 无前端，归 T10/T16a，未验证（见“未验证”）。 |
| 按契约 1.6.0-m06 与 ADR §3.3.1/§4.1；不改 J2；受影响测试通过并记录命令/退出码/日志                                                             | pass：EV-1021 exit 0；独立复跑 28/28 通过；契约测试 6/6；J2 文件无改动。                                                     |
| 高风险任务：done 前必须有独立 Verifier 任务级评审；只改 write_scope；不改 contracts/PRODUCT/AGENTS；不启动 M06b；提交后取证，任务只移到 review | pass：本评审即独立评审；write_scope 38/38 命中；未启动 M06b；EV-1021 在 50c6e89 上取证；任务 status=review。                 |

## AC-01/02/03 对账

- **AC-01（P0，T02 主责）**：Given 租户管理员/流程编辑者；When 新建空白流程、保存 7 节点 6 边图、重开；
  Then `view=MINE` 列表包含且 `draftVersion=2`、重开 `draftGraph` 与保存响应逐字段一致、`view=RUNNABLE`
  不包含未发布草稿；When 所有者与租户管理员基于同一版本先后保存；Then 后保存者得到 409 `RICCON-WF-409`，
  源流程保留管理员修改，所有者可另存副本（新 ID/当前用户/版本 1/图一致）。**API + 集成 pass**。
  AC-01 的“我的流程”状态“草稿”与冲突提示 UI 未验证（T10/T16a）；API 用 `publishedVersionId=null` +
  `hasUnpublishedChanges=true` 表达草稿态，契约 `WorkflowDefinitionStatus` 只有 ACTIVE/DISABLED，无 DRAFT。
- **AC-02（P0，T03 主责）**：T02 只做图形状校验并允许未完成草稿（ADR §3.3.1/§4.1），不实现结构/必填/
  模型工具可用性/审批可达性校验；AC-02 的逐项问题、发布阻断由 T03/T04 验收。本任务不将 AC-02 标 pass。
- **AC-03（P0，T04 主责）**：T02 只读发布版本摘要（run_scope/department_id/graph_sha256），按范围计算
  列表/详情可见性与 `hasUnpublishedChanges`，无发布写入路径；发布版本不可变、版本号/旧运行展示由 T04
  验收。T02 的已发布夹具证明：TENANT 对员工可见可运行且不返回草稿图、ONLY_SELF 仅所有者可运行、
  草稿只改名不改图时 `hasUnpublishedChanges=false`、改图后为 true。

## 图领域 WorkflowGraph*

- **封闭字段与枚举**：`WorkflowGraphJson` 对 graph/node/position/edge/config/condition/left/right 逐层
  白名单（`GRAPH_FIELDS`/`NODE_FIELDS`/`EDGE_FIELDS`/`CONFIG_FIELDS` 等），`additionalProperties:false`
  不依赖全局 Jackson；`CONFIG_FIELDS` 刻意不含 script/code/http/cron/parallel/loop/subflow。
  节点类型/边 branch/条件算子/条件来源/值类型/审批动作/审批人类型全部来自 platform-domain 封闭枚举，
  与契约一致；branch 只接受字符串 TRUE/FALSE，布尔 `true` 在 `optionalEnum` 的 `isTextual` 检查处拒绝。
- **上限与唯一性**：节点 ≤50、边 ≤100 在解析器与 `WorkflowGraph` 紧凑构造器双重校验（测试 51/101）；
  节点 ID 与边 ID 分别唯一（构造器 `requireUnique`），重复立即拒绝；节点/边/条件字段的 ID pattern、
  名称长度、prompt 8000、query 2000、topK 1..20、labels ≤10×64、argsTemplate ≤16 键、assigneeUserIds ≤50、
  values ≤20、resultTitle ≤200 均落地。
- **规范化 JSON/SHA-256**：`toJson` 序列化后递归按键名排序、无空白输出；`sha256` 对该 JSON 计算小写
  64 位 hex。独立用 target/classes 复验：同一图 parse→toJson→parse→toJson 字节稳定、`equals` 稳定、
  hash 稳定（示例 `91962bc15d40352f867a8bd78be862a72073dd60acf7b4d1f4eb68b7a9513c61`）；键顺序不同的
  等价图 hash 相同（`WorkflowGraphJsonTest.sha256_isCanonical` 通过）。发布（T04）必须复用同一函数，
  T02 已在 `WorkflowDefinitionService.hasUnpublishedChanges` 使用它。
- **发现**：targetNodeKey 未校验 pattern（F-002）；position 强制 x/y、空字符串/空白更严格（F-003）。
  未发现未知字段/未知节点/布尔 branch/超限/重复 ID 被放行的路径。

## 草稿仓储与租户隔离

- `WorkflowDefinitionJdbc` 全部业务 SQL 经 `TenantJdbc`，条件为 `d.tenant_id = :tenantId`；`updateDraft`
  的 WHERE 同时含 tenant/id/`draft_version`。`WorkflowDefinitionTemplateJdbc` 只读全局 `workflow_template`
  （visibility=BUILT_IN、status=ACTIVE），是 ADR-0012/0024 的全局目录例外，不使用租户守卫；M06 无用户模板
  读写路径。
- 新建 ID 使用 `UuidV7.generate(clock.instant())`；`draft_version` 从 1 开始；`status=ACTIVE`；
  `published_version_id/version_no` 为空，发布字段无写入入口。
- 列表视图：MINE=本人草稿 + 按范围可见的已发布（管理员返回本租户全部）；RUNNABLE=已发布 + ACTIVE +
  范围谓词，未发布绝不出现；ALL 仅 `workflow.manage`（TENANT_ADMIN）。scope 谓词覆盖 TENANT/
  ONLY_SELF(owner)/DEPARTMENT(当前启用部门)，无部门时不匹配 DEPARTMENT。
- 乐观锁：`UPDATE ... SET draft_version=draft_version+1 ... WHERE draft_version=:expectedVersion`，
  影响行数 0 → 409 `RICCON-WF-409`，不写审计；测试验证管理员保存后所有者用旧版本保存被拒且不覆盖。
- 审计与业务同事务：service 方法 `@Transactional`，`AuditService.append` 也是 `@Transactional`
  （默认 REQUIRED，加入调用方事务）；append 抛错会回滚 insert/update，符合 ADR-0007/§8.2 的 fail-closed。
  没有独立的“审计失败回滚”集成测试，见“未验证/剩余风险”。

## 草稿 API、可见性与副本

- 端点严格为 T02 范围：`GET /workflows`、`POST /workflows`、`GET /workflows/{id}`、
  `PUT /workflows/{id}/draft`、`POST /workflows/{id}/duplicate`；`@RequirePermission` 为
  workflow.read/edit，控制器 + 服务双重检查；平台租户由 T01 过滤器在权限前 404。
- 可见性：`get` 对 owner/admin 返回 draftGraph，对其他范围内用户不返回 draftGraph；非 owner/
  非 admin/不在发布范围一律 404 `RICCON-TENANT-404`；跨租户 404；view=ALL 非管理员 403
  `RICCON-AUTH-403`；平台管理员 404（测试断言）。
- 请求解析：`WorkflowDefinitionRequestParser` 对 create/draft/duplicate 做顶层白名单、version≥1、
  name/description 长度、graph 必填与形状校验，错误统一 400 `RICCON-WF-400`；未知字段（含 config.script）
  与大小写/类型错误均拒绝；失败请求不推进版本、不写审计（测试）。
- 另存副本：源流程仅 owner/admin 可见（其他编辑者 404）；新流程归当前用户、版本 1、无发布版本、
  图与说明复制、默认名“原名 + 副本”并在 200 字符内截断；`workflow.created` 审计一条。
- 模板复制：`POST /workflows {templateId}` 读取全局 BUILT_IN 模板，默认使用模板名/说明，复制 7 节点图并
  记录 sourceTemplateId/Type/Version；未知/停用模板 400（契约未声明 404）。模板工具节点 `toolId=null`
  是已知限制，由 T03 校验器/T14 模板任务处理，不阻塞 T02。
- 已发布版本只读：T02 只有 SELECT `workflow_version`（摘要 + `graph_sha256`），无版本写入/更新/删除路径，
  版本不可变由 V15 触发器兜底（T01 已验证）；测试用直接插入夹具模拟 T04 发布，注释明确。

## 审计

- `workflow.created` details 只写 `workflowId`、可空 `sourceTemplateId/sourceTemplateType`；
  `workflow.draft.saved` 只写 `workflowId`、`version`、`nodeCount`。测试断言 details 不含
  `SECRET-PROMPT-MARKER`（prompt），也不含图 JSON/工具参数；T01 的 `AuditDetailsPolicy` 写入/读取白名单
  与 nullable 规则接受这些字段（端点测试实际写入成功即证明）。
- `WorkflowDefinitionAuditEvents` 目标类型 `WORKFLOW`、目标 ID 为 workflowId，actor 为当前用户，
  traceId/requestId 透传；创建/保存/副本/模板复制各写一条 `workflow.created` 或
  `workflow.draft.saved`，冲突/400 不写。
- 同事务 fail-closed 由结构保证（见上），但没有专门的“审计写失败→业务回滚”集成测试；M06-AC-22 的
  `IT-AUDIT-M06-01` 归 T16a（ADR §13.1），本任务不以此判失败。

## 范围、回归与 ArchUnit

- 38 个文件全部命中 4 个 write_scope；无 contracts/PRODUCT/AGENTS/V15/J2/M06b 改动；V15 仍为 T01 版本。
- T02 代码分层：controller→service（含 parser）→repository→persistence；domain 无反向依赖；
  `WorkflowDefinitionTemplateJdbc`/`WorkflowDefinitionJdbc` 在 persistence 使用 JdbcClient；
  `WorkflowDefinitionController` 为 @RestController 且在 controller 包。T02 新增类不在 ArchUnit
  违规列表。
- **外部 P0（T01 引入，不属于 T02）**：`ModuleBoundaryTest.mvcLayeringIsOneWay` 在 T02 提交
  `50c6e89` 上仍会因 T01 的 `AppConfig.workflowFeatureAuditWriter(AuditService)`（config→service）与
  `SecurityConfig` 引用 `TenantContextFilter`（config→controller）失败。TASK-0240 已建（owner b1），
  commit `8d0252f` 将 writer 适配器移到 `common/service` 并调整 AppConfig/SecurityConfig；该提交不触碰
  任何 T02 文件。独立在 8d0252f 上运行
  `./mvnw -q -pl platform/platform-architecture-tests -am -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`：
  12/12 通过（0 failures/0 errors/0 skipped）。TASK-0240 仍为
  in_progress，模块 gate 必须等其自身评审/关闭，不能因本 T02 评审通过而视为已关闭。
- J1/J2：T02 无 J2 生产代码改动，未运行 J1 全量 E2E（按任务要求）；J2 回归由 T16a/模块 gate 承担。

## 证据复核与独立重跑

- **EV-1021**：命令
  `./mvnw -q -pl apps/api -am -Dtest='WorkflowDefinitionApiTest,WorkflowGraphJsonTest,WorkflowGraphDomainTest,WorkflowContractM06Test,WorkflowPlatformTenantFilterTest' -Dsurefire.failIfNoSpecifiedTests=false test`；
  commit `50c6e89bedf2360f9a0aafc4180a9d613f4c48f9`；`manual=false`、`dirty=false`、exit 0；
  output 19,346 bytes，sha256 `3b78b4d1…2cc2e34`，与证据 JSON 一致（`shasum -a 256` 复核）。
  EV-1020 是同命令/同 commit 的旧快照（agent=unknown），sha256 `e1de1f32…b883c123` 与文件一致；
  本评审以 EV-1021 为权威，不把 EV-1020 当第二份“额外通过”。
- EV-1021 日志为 `-q` 模式，未打印 `Tests run` 汇总；独立复跑的计数来自
  `apps/api/target/surefire-reports` 的 XML（文件时间戳在本次运行内），不是从日志自报。
- **独立重跑（50c6e89，与 EV-1021 同命令）**：exit 0；surefire 报告
  `WorkflowDefinitionApiTest` 7、`WorkflowGraphJsonTest` 6、`WorkflowGraphDomainTest` 4、
  `WorkflowContractM06Test` 6、`WorkflowPlatformTenantFilterTest` 5，共 28 个用例，
  0 failures / 0 errors / 0 skipped，无 @Disabled/assumeTrue。
- **复核 HEAD 8d0252f（外部修复后）**：同一组 5 个类再次 exit 0，28/28 通过，证明 TASK-0240 的
  config 调整未破坏 T02 上下文/端点。
- **静态与构建面**：`./mvnw -q spotless:check -pl apps/api -am` exit 0；
  `git diff --check c1dc211..50c6e89` 干净；独立 Java 复验规范化 hash 与 round-trip 稳定。
- 未运行：coverage、全量 E2E、axe/a11y、k6/perf、Trivy/security、`gate check --module M06`
  （按任务要求）。这些由模块 gate/T16a 承担。

## 未验证

- **E2E-M06-01**：T02 无前端，“我的流程”列表状态“草稿”、节点/连线/参数重开、冲突提示与“另存副本/
  放弃”交互只能在 T10/T16a 用真实浏览器验证；本评审不把 API 证据当 UI 证据。
- **审计 fail-closed**：只做代码级确认（同事务、append 抛错回滚），未构造审计表写入失败用例；
  `IT-AUDIT-M06-01` 归 T16a。
- **DEPARTMENT 范围**：T02 无集成测试；T05 必须覆盖并在运行准入复用（F-004/F-005）。
- **覆盖率**：未跑 jacoco；T02 的 `WorkflowDefinitionJdbc` 难以覆盖的空行/异常分支由模块 gate/T16a 检查。
- **未认证 401**：控制器级未断言；T01 过滤器单测 + T05/T16a 端点套件补。
- **全量运行时/安全**：未跑 50 并发、5MiB 结果、回滚演练、Trivy；属 T16a/T16b/模块 gate。

## 残余评估（任务说明第 6 条）

| 残余                                                                 | 是否阻塞 T02 done                | 评估                                                                                                                                                                                                             |
| -------------------------------------------------------------------- | -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST/duplicate 的 Idempotency-Key 未实现                             | 否（accepted P2）                | 可选 header，AC-01 不依赖；重复草稿可恢复。但契约/ADR 已声明同键返回既有草稿，M06 gate 前必须实现或由 Lead 修订契约（F-001）。                                                                                   |
| V15 `BUILT_IN/USER` 与契约 `sourceTemplateType` 的 `BUILT_IN/TENANT` | 否                               | T02 把差异集中在 `WorkflowDefinitionSourceType`：业务/响应用 BUILT_IN/TENANT，DB/审计用 BUILT_IN/USER；当前只写 BUILT_IN，无可见偏差。M06b 启用 TENANT 用户模板时必须补映射/审计用例，不能假设 DB CHECK 已支持。 |
| 模板 toolId=null                                                     | 否                               | T02 允许草稿存 null（模板复制成功）；T03 校验器/T14 模板任务负责工具可用性与模板修正；不把模板原样当可发布流程。                                                                                                 |
| DEPARTMENT 范围判定 T05 复用                                         | 否                               | 代码已实现，但 T02 无集成测试且 canRun 与列表存在边界不一致；T05 必须复用范围判定并补测试（F-004/F-005）。                                                                                                       |
| E2E-M06-01 未产出                                                    | 否（但模块不能据此宣称 UI 验收） | 无前端，T10/T16a 负责；本任务 AC-01 只对后端 API pass。                                                                                                                                                          |

## Findings

- **F-001（P2，accepted）** 幂等键未生效；owner=builder-b1，due=M06 gate 前（T04 开工时定）。
- **F-002（P3，accepted）** targetNodeKey 未校验契约 pattern；owner=builder-b1，due=T03 开工前。
- **F-003（P3，accepted）** 解析器比 schema 更严格（position/空字符串）；owner=builder-b1，due=T10 前。
- **F-004（P3，accepted）** T02 缺 DEPARTMENT 可见性集成测试；owner=builder-b1(T05)，due=T05 完成前。
- **F-005（P3，accepted）** 所有者移出部门后 detail.canRun 与 RUNNABLE 列表不一致；owner=builder-b1(T05)，due=T05 完成前。

无 open P0/P1；P2/P3 均 accepted 且有 owner/due/accepted_reason。

## 剩余风险

1. **草稿图无字节上限**：契约只限制字段长度/节点数/边数，`argsTemplate` 值可任意嵌套；`list` 的
   `ROW_COLUMNS` 会加载每行 `draft_graph`，并对已发布行算 SHA-256。极端情况下（多个接近 16MiB 的
   草稿）一次列表请求会造成明显堆/CPU 压力。T16a perf/安全用例应评估是否加草稿字节上限或避免列表
   加载完整图；本任务无性能预算，未作为 finding 计。
2. **审计 fail-closed 无直接测试**：结构正确，但 T16a 的 `IT-AUDIT-M06-01` 必须覆盖“审计写失败→
   创建/保存回滚且无 workflow_definition 变更”，不能只测成功路径。
3. **规范化哈希无固定向量**：当前只有键顺序/内容变化断言；发布版本一旦落库，规范化算法变更会使旧
   `graph_sha256` 与新草案比较失真。T04 发布实现应加一条固定 JSON→hash 向量（防止无意改变算法）。
4. **副本来源元数据**：`duplicate` 复制源流程的 `source_template_id/type/version`，但
   `workflow.created` 审计写 null（因为不是直接从模板创建）。若 M06b 依赖来源审计做升级提示，需对齐
   审计与 DB 的来源口径；当前不影响用户可见行为。
5. **列表加载草稿图**：`view=RUNNABLE` 不需要草稿图，但 SQL 仍选择 `draft_graph`；后续可在 T04/T16a
   评估拆分查询（不影响本任务正确性）。
6. **UI 未验证**：冲突提示、草稿状态标签、可运行列表、canRun 按钮均需 T10/T16a 真实浏览器验证。

## 是否可 done

可 done（**pass**，带 accepted residual）。T02 后端验收、领域/仓储/API/审计、范围与证据均通过；
无 open P0/P1；外部 T01 ArchUnit P0 已由 TASK-0240 在 8d0252f 修复并独立复跑 12/12，但必须等
TASK-0240 自身评审/关闭后模块 gate 才能通过。F-001 必须在 M06 gate 前实现或由 Lead 修订契约；
F-002/F-003 在 T03/T10 前闭环；F-004/F-005 在 T05 前闭环。本评审不标 done、不运行模块 gate，也不替代
M06 模块 review、Product 预验收或用户验收。
