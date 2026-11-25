---
review_id: REV-TASK-0235-001
module_id: M06
task_id: TASK-0235
reviewer:
  agent: riccon/verifier
  session: ses_def793c08ffe7c2Yr5mhgk9CVH
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 26c4d578f246257924364d03e213f0fb8922510f
  - 1d106efedcedfd3f6d5cee3ef11982f2a7aedecd
  - 1d7df96baf90fdd1595de6626a1f6cad8ca2faed
  - 145dc7c50865f8d5a1ed9f655208d30742b78044
head_commit: 5a951e47902b7ce254f83422d1639b72eb3e4691
code_commit: 1d106efedcedfd3f6d5cee3ef11982f2a7aedecd
evidence_commit: 1d7df96baf90fdd1595de6626a1f6cad8ca2faed
baseline_commit: 26c4d578f246257924364d03e213f0fb8922510f
task_card_baseline_commit: 26c4d578f246257924364d03e213f0fb8922510f
out_of_scope_commits:
  - 580849392aaad5d690b6ed074138dcd7b8877b1d
  - 5a951e47902b7ce254f83422d1639b72eb3e4691
related_reviews:
  - REV-TASK-0220-001
  - REV-TASK-0221-002
  - REV-TASK-0230-001
  - REV-TASK-0241-002
  - REV-TASK-0243-001
review_scope: >-
  M06 T14（TASK-0235）独立任务评审：内置模板列表 `GET /api/v1/workflow-templates`、复制
  `POST /api/v1/workflow-templates/{templateId}/copy`、BUILT_IN/ACTIVE 过滤、名称/场景通配符转义、
  分页、+08:00 时间、契约字段、复制个人草稿且不改源模板、403/404/400/Idempotency-Key 边界、T02
  `createFromTemplate` 复用、V15 market-research 种子、前端模板库页字段/复制/空/错误/403/无编辑权限/
  375px，以及 EV-1058/EV-1060 的 commit/dirty/hash 与独立复跑。不评审 T14b（TASK-0244）路由/导航、
  不评审 T16a 的 E2E-M06-07/axe、不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  review: true
  evidence: false
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
  - migration
dod_note: >-
  acceptance 记 true 仅指 T14 任务范围（API + 模板库页面组件）的实现验收，按 **pass (with residual)**
  理解：AC-10 的“用户从模板创建草稿/填写参数/校验/发布/运行/产出报告”中，T14 覆盖列表、复制、草稿字段、
  个人草稿与不改源模板；真实路由入口由 T14b、E2E-M06-07 与 axe/键盘/375px 真实浏览器由 T16a 承接，
  本评审未执行，因此不是 M06-AC-10 的模块级 E2E pass。build 由 Maven test 编译与独立 `pnpm --filter
  @riccon/web build` 支撑；evidence 记 false，因为 EV-1058/EV-1060 的取证位置不合规（见 F-001）。
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-b1
    due: T14 done 前
    title: "EV-1058/EV-1060 在临时 detached worktree 录制且 cwd 已删除，违反主工作树取证规则"
    summary: >-
      两条 EV 的内部字段一致：commit=1d106ef（HEAD 祖先）、dirty=false、exit_code=0、manual=false，
      日志 output_bytes 与 SHA-256 均与记录一致。但 EV-1058.json:14 与 EV-1060.json:14 的
      `cwd=/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/riccon-t14-evidence`
      在评审时已不存在（实测 `os.path.exists=false`），两份日志第 2 行的 `# cwd` 也指向该临时目录。
      `docs/CODING-STANDARDS.md:201` 明确要求“先提交，再在主工作树用 riccon evidence run 取证，
      不先跑一遍再取证，不为取证新建临时 worktree”；`docs/COLLABORATION.md:75` 同口径。独立复跑证明
      代码测试通过，但证据本身不合规，不能作为 T14 done 的依据。评审时主工作树还有 T06a（TASK-0224）
      未提交 worker 文件；T14b（TASK-0244）已在 5808493 提交路由/导航/CSS，但其 EV-1061/EV-1062
      记录 dirty=true，也需单独重录 clean EV。clean EV 必须等主工作树干净后重录，并明确 T14 与
      T14b 的代码边界。
    evidence: >-
      .riccon/evidence/TASK-0235/EV-1058.json:7-15；
      .riccon/evidence/TASK-0235/EV-1060.json:7-15；
      .riccon/evidence/TASK-0235/EV-1058-2026-11-25T10-20-02-123+08-00-mvnw-q-pl-apps-api-am-test-dtest-workflowtemplat.log:1-6；
      .riccon/evidence/TASK-0235/EV-1060-2026-11-25T10-21-05-110+08-00-pnpm-filter-riccon-web-exec-vitest-run-src-pages.log:1-6；
      docs/CODING-STANDARDS.md:201；docs/COLLABORATION.md:75；
      .riccon/evidence/TASK-0244/EV-1061.json:8（dirty=true）；.riccon/evidence/TASK-0244/EV-1062.json:8（dirty=true）
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M06 gate 前
    title: "复制端点的通用 Idempotency-Key 描述仍承诺同键重放，与 ADR §7.4/M06 实现不一致"
    summary: >-
      OpenAPI `copyWorkflowTemplate` 仍引用通用 `IdempotencyKey`（contracts/openapi.yaml:3223），
      该组件参数描述为“可选幂等键；同一主体、同一资源重复请求返回既有结果”
      （contracts/openapi.yaml:4522），客户端会据此认为复制可按键重放；但 ADR-0024 §7.4 与端点表
      明确 copy “可选键但 M06 不承诺键级重放”，`WorkflowTemplateController.java:143-158` 只校验
      8..128，`WorkflowTemplateApiTest.java:182-193` 断言第二次复制产生新的草稿 ID。观察与期望的差异
      在契约文案而非 T14 实现：实现符合 ADR，通用参数描述容易误导。期望契约 owner 在 copy 的
      description 或该参数的 copy 引用处标注“M06 不重放（M06b 再评估）”，或明确说明该描述只适用于
      run/retry/approval。
    evidence: >-
      contracts/openapi.yaml:3213-3223,4518-4526；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:541-542,554-564；
      apps/api/src/main/java/com/riccon/api/workflow/template/controller/WorkflowTemplateController.java:143-158；
      apps/api/src/test/java/com/riccon/api/workflow/template/WorkflowTemplateApiTest.java:182-193
    accepted_reason: >-
      复制是低风险创建操作，重复请求只产生可恢复的个人草稿，不涉及越权、数据泄露或外部副作用；
      Idempotency-Key 为可选，T14 按 write_scope 不改 contracts（契约由 T01 独占），ADR §7.4 已给出
      M06 口径且实现与测试一致。该文案需在 M06 gate 前由契约 owner 澄清，不阻塞 T14 代码验收。
evidence:
  - EV-1058
  - EV-1060
reviewed_evidence:
  - EV-1058
  - EV-1060
notes: >-
  任务卡 status=review、baseline=26c4d57、end=1d106ef、evidence=[EV-1058, EV-1060]。代码评审以
  26c4d57..1d106ef 为准，diff 只新增 15 个文件，全部位于 T14 write_scope；未修改 contracts、PRODUCT、
  AGENTS、V15、J2 或既有生产源码。评审时 HEAD=5a951e4，1d106ef..HEAD 只包含 .riccon 状态/任务/证据
  与 T14b 5808493 的 routes/studio-nav/templates.css；T14b 另行评审。独立复跑使用 `git archive 145dc7c`
  的干净导出，避免主工作树 T06a 未提交文件污染。干净导出的 apps/web 缺少 gitignore 的 `src/.umi`，
  先由 `pnpm --filter @riccon/web typecheck`（内含 `max setup`）生成后再跑 vitest；主工作树重录 clean EV
  时 `.umi` 已存在，可直接执行。
created_at: "2026-11-25T10:47:00+08:00"
---

# REV-TASK-0235-001：M06 T14 内置模板/复制/模板列表独立评审

## 结论

**不通过（fail）**。代码层面的 T14 实现通过独立复验：后端 24 个定向测试、前端 13 个模板库测试 +
typecheck/lint、web 生产构建、T03 与 T10 回归均通过；契约字段、403/404/400、复制个人草稿且不改源模板、
V15 种子和 write_scope 均符合要求。**失败原因只有一条：EV-1058/EV-1060 在临时 detached worktree 录制，
cwd 已被删除，违反项目“主工作树取证”规则（F-001，P1 open）**。在该 P1 关闭、T14 在主工作树干净提交上
重录 clean EV 之前，任务不能 done；代码本身不需要返工。

## 评审范围与提交

- 任务卡：`.riccon/tasks/TASK-0235.json`，状态 `review`，owner `riccon/builder-b1`，
  `baseline_commit=26c4d57`，`end_commit=1d106ef`，evidence `[EV-1058, EV-1060]`。
- 代码提交：`1d106ef`（`feat(m06): T14 内置模板列表与复制`）。`git diff --stat 26c4d57..1d106ef`
  为 15 个新增文件、2305 行，全部位于 T14 `write_scope`：
  `apps/api/src/main/java/com/riccon/api/workflow/template/**`、
  `apps/api/src/test/java/com/riccon/api/workflow/template/**`、
  `apps/web/src/pages/workflow/templates/**`。无 `contracts/`、`docs/PRODUCT.md`、`AGENTS.md`、V15、
  J2 或既有生产源码改动。
- 证据提交：`1d7df96` 只改 `.riccon/evidence/TASK-0235/**`、任务卡与 `state.json`。
- 评审时 HEAD：`5a951e4`。`1d106ef..HEAD` 中 `apps/` 的改动只有 T14b `5808493` 的
  `routes.ts/routes.test.ts/studio-nav.tsx/studio-nav.test.tsx/templates.css`；T14b 另行评审，本评审
  不把其路由/导航/E2E 计为 T14 通过。
- 独立复跑使用 `git archive 145dc7c` 的干净导出（不含 T06a 未提交文件，也不含 T14b 改动），
  commit 为 `145dc7c`；因此复跑结论对应 T14 代码本身。

## 后端

### 列表 `GET /api/v1/workflow-templates`

- 权限：控制器 `@RequirePermission(PermissionCodes.WORKFLOW_READ)`（`WorkflowTemplateController.java:85-92`），
  未认证 401、无 `workflow.read` 403、平台管理员 404 由 `WorkflowPlatformTenantFilter` 在权限前短路；
  `WorkflowTemplateApiTest.java:107-125` 覆盖三种结果与错误码 `RICCON-AUTH-403`/`RICCON-TENANT-404`。
- 数据范围：`WorkflowTemplateJdbc.java:34` 的 `BASE_FILTER` 固定 `visibility=BUILT_IN AND status=ACTIVE`，
  `findActive`（`:101-110`）同一口径；全局表不含租户条件，符合 ADR-0024 §3.3.7/§12 的只读内置目录。
- 过滤：`scenario`/`name` 为 `%value%` 包含匹配，`likePattern`（`:170-175`）先转义 `!` 再转义 `%` 与 `_`，
  SQL 使用 `ESCAPE '!'`（`:121-131`）；用户输入全部命名参数绑定，无拼接注入。测试 `%` 与 `_`
  按字面量返回 0，证明没有把通配符当“匹配全部”（`WorkflowTemplateApiTest.java:88-92`）。
- 分页：`PageQuery` 默认 `page=1/size=20`，`size<=100`、`page>=1`；超限返回
  `RICCON-REQUEST-400`（测试 `:100-103`）。`total` 与 `items` 分别查询，排序固定
  `updated_at DESC, id ASC`（`WorkflowTemplateJdbc.java:43,66-90`），无 N+1。
- 契约字段：`WorkflowTemplateResponse.java:54-67` 返回 `id/name/scenario/description/source=BUILT_IN/
visibility=BUILT_IN/builtInVersion/upgradeAvailable=false/updatedAt`；`ownerUserId` 与
  `latestBuiltInVersion` 为 null 并由 `@JsonInclude(NON_NULL)` 省略，契约均为可选（`WorkflowTemplate`
  schema）。不返回 `graph`、节点配置、`nodeCount` 或工具参数（测试 `:61-65`）。
- 时间：`WorkflowTemplateResponse.java:45,75-77` 将 UTC `Instant` 转到 `Asia/Shanghai` 并序列化为
  `OffsetDateTime`；测试断言 `updatedAt` 以 `+08:00` 结尾（`:60`）。
- 过滤长度：`WorkflowTemplateService.java:113-121` 对 `scenario/name` 去空白后限制 200，超长 400；
  与契约 `maxLength: 200` 一致。

### 复制 `POST /api/v1/workflow-templates/{templateId}/copy`

- 权限与顺序：控制器 `@RequirePermission(PermissionCodes.WORKFLOW_EDIT)`
  （`WorkflowTemplateController.java:105-120`），服务层再次 `actor.canEdit()` → 403
  （`WorkflowTemplateService.java:94-99`）；未知/停用模板 `NotFoundException` → `RICCON-TENANT-404`
  （`:100-101`）。测试覆盖员工 403、未知模板 404、平台管理员 404、无角色 403
  （`WorkflowTemplateApiTest.java:197-254`）。
- 响应：201 + `WorkflowDefinitionDetail`；测试断言 `name` 使用模板名、`ownerUserId`=当前编辑者、
  `sourceTemplateId/sourceTemplateType=BUILT_IN/sourceTemplateVersion=1`、`draftVersion=1`、
  `status=ACTIVE`、`canEdit=true`（`:128-170`）。
- 源模板只读：复制前后比较 `workflow_template` 的 name/version/graph_json/updated_at 文本
  （`:161-162,336-350`）；命名复制仍不改模板（`:182-193`）。实现只调用 T02
  `WorkflowDefinitionService.createFromTemplate`（`WorkflowTemplateService.java:103`），写入草稿与
  `workflow.created` 审计，不提供模板写路径。
- 个人草稿：草稿 owner=当前用户，来源模板版本记录在 `workflow_definition`；个人可见性由 T02 的草稿
  访问规则保证（本任务未另写共享/私有模板端点，M06b 范围）。
- 图内容：测试断言 `draftGraph.nodes=7`、`edges=6`，存在 `MCP_TOOL` 节点且 `config.toolId` 为空
  （`:154-159`）。这是 V15 种子的预期：工具由用户在授权范围内补选，符合 T03 校验规则；**nodeCount
  不在契约/AC，不作为 fail**。
- 请求体：`WorkflowTemplateRequestParser.java:31-55` 白名单仅 `name`，拒绝非对象、未知字段、非字符串、
  去空白后长度不在 1..200；错误 `RICCON-WF-400`（测试 `:222-225`）。
- Idempotency-Key：`WorkflowTemplateController.java:143-158` 缺失直接通过，存在时只校验 8..128；
  测试覆盖缺失成功与过短 400（`:227-234`）。实现不持久化、不按键重放，符合 ADR-0024 §7.4
  “copy 可选键但 M06 不承诺键级重放”；第二次复制产生新 ID（`:182-193`）。契约文案问题见 F-002。
- 审计：测试断言 `workflow.created` 一条、details 含 `sourceTemplateId/sourceTemplateType` 且不含
  `argsTemplate`（`:171-180`）；不写图或工具参数。

### 契约与复用

- `WorkflowContractM06Test` 新增/覆盖 `listWorkflowTemplates`/`copyWorkflowTemplate` 的权限、
  `WorkflowTemplate` schema 字段与“M06b 模板写端点不存在”断言（`WorkflowContractM06Test.java:83-85,
295-318`）。
- 复制复用 T02 `createFromTemplate`，没有复制/重写草稿插入、审计或权限逻辑；T02 的
  `WorkflowDefinitionApiTest` 在同一 EV/独立复跑中通过。
- 未实现 M06b 用户模板另存/共享/升级提示；`WorkflowTemplateResponse` 恒 `source=BUILT_IN`、
  `upgradeAvailable=false`，符合 ADR §12。

## 前端

- 页面 `apps/web/src/pages/workflow/templates/index.tsx`：
  - 字段：名称、场景、说明、内置模板 Tag、内置版本、更新时间（`formatOffsetDateTime` +08:00），
    复制按钮（`:289-324`）。
  - 状态：加载 Skeleton、空态（说明 + 刷新/清空筛选）、错误态（中文说明 + 重试）、403 态（Result +
    重试）、无编辑权限时提示并禁用复制（`:247-287,234-241`）。
  - 复制：`handleCopy` 调 `copyTemplate`，成功 message 明确“模板本身不会被修改”并跳转
    `/workflows/{draft.id}/edit`；403 给出申请权限文案；失败给出可重试说明（`:171-187`）。
  - 过滤/分页：名称/场景输入去空白后查询，重置回第一页；`requestSeq` 丢弃过期响应
    （`:103-162`）；分页固定 size 20（`:326-335`）。
  - 375px：列表使用卡片而非表格，工具栏/操作区可换行，`@media (max-width: 575px)` 操作按钮占满
    一行（`templates.css:47-111`）。**真实浏览器 375px、无横向滚动与 axe 由 T16a 验证**，组件测试
    只证明结构可达，不冒充视觉验收。
- 客户端 `template-api.ts`：列表带 page/size 与可选 name/scenario（空值不发送）；复制路径
  `encodeURIComponent`、POST JSON、`X-CSRF-TOKEN`；403 `RICCON-AUTH-403` 刷新 CSRF 并重试一次；
  非 2xx 统一 `ApiError`（`:193-215,98-190`）。测试覆盖列表参数、复制路径/CSRF、重试与 404 归一化。
- 测试 `index.test.tsx` 9 个 + `template-api.test.ts` 4 个，共 13 个；覆盖字段、空/错误/403/无编辑
  权限、复制成功/失败、过滤/重置、375px 结构。**未覆盖**：真实浏览器复制→编辑器往返、E2E-M06-07、
  axe、键盘全流程，均归 T16a/T14b。

## 数据与 V15

- V15（`apps/api/src/main/resources/db/migration/V15__workflow_studio.sql:478-497`）已有
  `market-research` 种子：固定 ID `01990000-0000-7000-8000-000000000601`、`visibility=BUILT_IN`、
  `status=ACTIVE`、version=1、场景“市场调研”，7 节点/6 连线，`MCP_TOOL.config.toolId=null`；
  `WHERE NOT EXISTS` 保证迁移幂等。T14 未修改 V15（diff 为空）。
- `workflow_template` 表约束 `visibility IN ('BUILT_IN')`、`version>=1`、`status IN ('ACTIVE','DISABLED')`
  （`:323-340`），与只读内置目录一致。
- 复制后的草稿使用 V15 图，`toolId` 为空是预期状态；T03 校验器会在用户保存/发布时要求补选工具。
  **nodeCount 不在契约/AC，不作为 fail**。

## 范围与回归

- 代码 diff 仅 15 个新增文件，全部在 `write_scope`；未改 `contracts/`、`PRODUCT`、`AGENTS`、`V15`、
  J2、T10/T03/T02 生产源码。
- 独立回归（干净导出 `145dc7c`）：
  - 后端 T14：`WorkflowTemplateApiTest` 5 + `WorkflowContractM06Test` 6 +
    `WorkflowDefinitionApiTest` 8 + `WorkflowPlatformTenantFilterTest` 5 = **24 tests，0 failures/
    0 errors/0 skipped，exit 0**。
  - 后端 T03 回归：`WorkflowValidatorTest` 14 + `WorkflowValidationApiTest` 11 +
    `WorkflowGraphDomainTest` 4 + `WorkflowGraphJsonTest` 6 = **35 tests，0/0/0，exit 0**。
  - 前端 T14：`vitest run src/pages/workflow/templates` **2 files / 13 tests passed**；typecheck
    exit 0；eslint exit 0；`pnpm --filter @riccon/web build` exit 0。
  - 前端 T10 回归：`vitest run src/pages/workflow/list src/pages/workflow/edit` **7 files / 80 tests
    passed**。
  - `./mvnw -q -pl apps/api -am spotless:check` exit 0。
- 复跑说明：干净导出缺少 `.umi`，先执行 typecheck（含 `max setup`）再跑 vitest；这不是代码失败，
  主工作树重录 clean EV 时 `.umi` 已存在。未运行 E2E/axe/Trivy/k6/coverage；这些属于 T16a/M06 gate。
- T14b：`5808493` 已接入 `/workflows/templates` 与 Studio 子导航，`5a951e4` 记录 EV-1061/1062；
  但两条 T14b EV 的 `dirty=true`，T14b 也需 clean EV。T14 评审不把 T14b 的路由/导航/CSS 计入
  T14 验收，只记录其已存在，避免 clean EV 时误以为主工作树仍只有 T14 代码。
- T10 的固定模板 ID（`apps/web/src/pages/workflow/list/workflow-api.ts:256` 的
  `MARKET_RESEARCH_TEMPLATE_ID`）不在 T14 失败项；T14 页面通过 `GET /workflow-templates` 列表获取 ID，
  后续可由 T14b/T10 决定是否改为统一列表入口。

## 证据复核与 clean EV

- EV-1058（后端）：`commit=1d106ef`、`dirty=false`、`exit_code=0`、`manual=false`；日志 24144 bytes，
  SHA-256 `53dc69ff9131a1aa0c57cf8c0042b0ee66f7b88fd97883567fec2adaa6c001f5`，与记录一致；
  1d106ef 是 HEAD 祖先。**日志本身没有 `Tests run` 汇总（`-q` 抑制），24 个测试数由独立复跑的
  surefire XML 复核**；建议新 EV 保留可核对的测试数或报告摘要。
- EV-1060（前端）：`commit=1d106ef`、`dirty=false`、`exit_code=0`、`manual=false`；日志 1213 bytes，
  SHA-256 `12a568d6cf16ef9b16c3664a12e7c2db5bc409d807c295c17c8735962b7bda57`，与记录一致；
  日志明确 `2 passed / 13 passed`，typecheck 与 eslint 在命令链中 exit 0。
- **两条 EV 的 cwd 都是已删除的临时 detached worktree**（见 F-001），这是本项目禁止的取证方式；
  内部 hash/commit 正确不能改变“证据来源不合规”的结论。
- 重录要求（F-001 关闭条件）：
  1. 等 T06a（TASK-0224）提交，确认主工作树无未提交代码；当前仍有 33 个 worker 未跟踪文件。
  2. 在主工作树干净提交上，用 `riccon evidence run --task TASK-0235 --agent riccon/builder-b1 -- ...`
     重跑后端定向命令与前端定向命令（可加 build），更新任务卡 `evidence` 与 `end_commit`。
  3. 前端命令若继续跑 `src/pages/workflow/templates` 目录，会包含 T14b 已提交的
     `studio-nav.test.tsx`（测试数会大于 13）；要么在 EV note 中明确包含 T14b 测试，要么显式列出
     T14 的 `index.test.tsx`/`template-api.test.ts`，保持证据边界。
  4. 不得复用 EV-1058/EV-1060 作为 done 依据；它们可保留为历史记录，但新 EV 必须来自主工作树干净树。
  5. 重录后由 verifier 写新 review（预期 `REV-TASK-0235-002`）关闭 F-001；在此之前 T14 保持 review，
     不得 done。

## Findings

- **F-001（P1，open，owner=riccon/builder-b1，due=T14 done 前）**：EV-1058/EV-1060 在临时 detached
  worktree 录制且 cwd 已删除，违反主工作树取证规则；不能作为 done 证据。详见上文与 front matter。
- **F-002（P3，accepted，owner=riccon/lead，due=M06 gate 前）**：复制端点引用的通用
  `Idempotency-Key` 描述仍承诺同键重放，与 ADR §7.4 “copy 不承诺键级重放”不一致；实现与测试正确，
  属契约文案澄清项，不阻塞 T14 代码验收。

## 未验证与剩余风险

- **未验证**：T16a 的 E2E-M06-07（真实浏览器从模板库复制→编辑器→校验/发布/运行→报告）、axe/
  `@a11y`、真实键盘旅程、真实 375px 无横向滚动；T14b 的路由/导航/生产 chunk；M06 模块 gate 的
  coverage/security/perf/migration。
- AC-10 按 **pass (with residual)** 记录：T14 覆盖 API + 组件 + 草稿/源模板不变；E2E-M06-07 与
  用户可见入口由 T16a/T14b 承接，未在本评审执行。
- T14b 的 EV-1061/EV-1062 `dirty=true` 是 T14b 自身的证据问题，需 T14b 重录 clean EV；不要把
  T14b 的 dirty EV 当作 T14 证据，也不要在 T14 clean EV 中把 T14b 代码算作 T14 变更。
- 模板升级提示、`latestBuiltInVersion`、用户模板共享/私有模板 404 与审计属 M06b AC-11/25，
  T14 未实现，符合 ADR §12；不构成 T14 fail。
- EV-1058 日志无 surefire 汇总属证据可读性风险，独立复跑已确认 24 个测试；重录时建议保留测试数。

## 后续

- T14（TASK-0235）：按 F-001 重录 clean EV，完成后请求 verifier 复审；代码无需返工。
- T14b（TASK-0244）：路由/导航已提交，但 EV-1061/1062 dirty=true，需按同一规则重录；其路由/E2E
  仍归 T16a。
- T10：固定模板 ID 与模板库入口的后续统一由 T14b/T10 处理，不在 T14 失败项。
- M06 gate：在 T14/T14b 的 clean EV、T16a E2E/a11y/perf、模块全量 test/coverage 和 Product 预验收
  完成后另行评审；本 review 不代表模块级通过。
