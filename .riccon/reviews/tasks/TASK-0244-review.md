---
review_id: REV-TASK-0244-001
module_id: M06
task_id: TASK-0244
reviewer:
  agent: riccon/verifier
  session: ses_def6b13a1ffefqvOriwHauqhkY
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 580849392aaad5d690b6ed074138dcd7b8877b1d
  - 5a951e47902b7ce254f83422d1639b72eb3e4691
  - 3b4bfbb3cb745a0a95c3cee4262a8654543bcb1e
head_commit: 3b4bfbb3cb745a0a95c3cee4262a8654543bcb1e
code_commit: 580849392aaad5d690b6ed074138dcd7b8877b1d
evidence_commit: 5a951e47902b7ce254f83422d1639b72eb3e4691
baseline_commit: 1d7df96baf90fdd1595de6626a1f6cad8ca2faed
task_card_baseline_commit: 1d7df96baf90fdd1595de6626a1f6cad8ca2faed
related_reviews:
  - REV-TASK-0235-001
  - REV-TASK-0242-001
  - REV-TASK-0243-001
review_scope: >-
  M06 T14b（TASK-0244）独立任务评审：`/workflows/templates` 路由接入、列表/编辑器/模板库 Studio
  子导航、`workflow.read` 隐藏、选中态、面包屑、375px 单测、既有 `/workflows` 重定向与列表/编辑器、
  `/chat`、`/research`、`/admin`、登录与 `*` 404 回归、生产构建 async chunk、EV-1061/EV-1062 的
  commit/dirty/hash，以及实际 diff 与 write_scope。独立复跑使用 `5808493` 的干净导出，避开 T06a
  未提交文件；不运行 T16a 的真实浏览器 E2E/axe/键盘/实际 375px，不评审 T14 模板页功能
  （另见 REV-TASK-0235-001），不替代 M06 模块 review、Product 预验收或用户最终验收。
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
  acceptance 记 true 指 T14b 任务范围的路由/导航/构建/范围实现验收，按 **pass (with residual)**
  理解：真实浏览器 375px/axe/键盘与 M06 stub 账号由 T16a 承接（F-002 accepted）。build/tests/lint/
  typecheck 由 EV-1062 日志与本评审的干净导出独立复跑支撑。evidence 记 false：EV-1061/EV-1062
  均 dirty=true，strict 证据绑定下不能作为 done 依据（F-001 P1 open）；独立复跑只用于技术复核，
  不冒充任务证据。coverage/e2e/security/performance/migration 属 T16a/M06 模块 gate。
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-b3
    due: "T14b done 前（TASK-0224/T06a 提交、主工作树干净后）"
    title: "EV-1061/EV-1062 均为 dirty=true，strict 下不能建立 done；clean EV 尚未重录"
    summary: >-
      EV-1061（`pnpm --filter @riccon/web test`）与 EV-1062（typecheck && lint && build）的
      commit 均为 `5808493`，`dirty=true`、`exit_code=0`、`manual=false`，cwd 为主工作树
      `/Users/ricky/Documents/projects/riccon-work`；日志字节数与 SHA-256 均与 JSON 记录一致
      （EV-1061 883 B / `e6d483b7…`；EV-1062 5152 B / `cd0c8a48…`），日志分别显示 87 files /
      774 tests passed 与生产构建成功。问题不在代码或日志内容，而在取证时工作树含 TASK-0224/T06a
      的 33 个未跟踪 `apps/worker/**/workflow/**` 文件。`quality.evidence_binding=strict` 下，
      `gate.mjs` 的 `evidence_attestation` 遇到任何任务引用的 dirty 证据即 fail（`:392-409`），
      `docs/COLLABORATION.md:75` 也明确“strict 下 dirty: true 的证据会让 gate 失败”。因此
      EV-1061/EV-1062 不能建立 T14b done，也不能被模块 review 当作 clean 证据。修复：等 T06a
      提交、确认主工作树 `git status --porcelain` 为空后，由 builder-b3 在主工作树用
      `riccon evidence run` 重录同一组 web 全量 test 与 typecheck/lint/build 命令，更新
      `TASK-0244.evidence` 与 `end_commit`；旧 dirty EV 可从任务卡移除或归档，不复用为 done 依据。
    evidence: >-
      .riccon/evidence/TASK-0244/EV-1061.json:6-14；
      .riccon/evidence/TASK-0244/EV-1062.json:6-14；
      .riccon/evidence/TASK-0244/EV-1061-2026-11-25T10-38-37-773+08-00-pnpm-filter-riccon-web-test.log:1-27；
      .riccon/evidence/TASK-0244/EV-1062-2026-11-25T10-39-26-978+08-00-pnpm-filter-riccon-web-typecheck-pnpm-filter-ric.log:1-125；
      ~/.config/opencode/riccon/scripts/lib/gate.mjs:392-409；
      docs/COLLABORATION.md:75；docs/CODING-STANDARDS.md:201；
      .riccon/reviews/tasks/TASK-0235-review.md:60-83,280-289
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "M06 T16a / 模块 gate 前"
    title: "375px 换行/无横向滚动、axe 与实际路由跳转只有 jsdom/静态 CSS 覆盖"
    summary: >-
      `studio-nav.test.tsx:96-106` 把 `window.innerWidth` 设为 375 后只断言导航可见、
      “模板库”链接可聚焦；jsdom 不执行布局，因此不能证明 `flex-wrap` 实际换行，也不能证明页面
      无横向滚动。`routes.test.ts:99-160` 验证的是配置对象与 helper，而不是 Umi 运行时的实际跳转；
      生产构建已验证 wrappers 与 async chunk（见下文），但真实浏览器从列表/编辑器进入模板库、无权限
      403、刷新深链、axe/键盘与 M06 stub 账号均未在本任务执行。`templates.css:9-39` 使用
      `flex-wrap: wrap`、`max-width: 100%` 与 `min-width: 0`，静态风险有界。
    accepted_reason: >-
      T14b 只负责路由/子导航接入；任务卡与 T14/T16a 计划明确把真实浏览器 375px、axe、键盘旅程和
      M06 stub 账号归 T16a。T10b 对同类 jsdom 局限已按 P3 accepted（REV-TASK-0242-001 F-001），
      本任务沿用该口径并交由 T16a 做最终浏览器验证；当前 CSS 无固定宽度，风险有界，不阻塞 T14b
      代码验收，但本评审不把 jsdom 结果当作真实 375px/axe 通过。
    evidence: >-
      apps/web/src/pages/workflow/templates/studio-nav.test.tsx:96-106；
      apps/web/src/pages/workflow/templates/templates.css:9-39；
      apps/web/config/routes.test.ts:99-160；
      .riccon/reviews/tasks/TASK-0242-review.md:47-56,247
evidence:
  - EV-1061
  - EV-1062
reviewed_evidence:
  - EV-1061
  - EV-1062
notes: >-
  任务卡 status=review、owner=riccon/builder-b3、baseline=1d7df96、end=5808493、evidence=[EV-1061,
  EV-1062]；评审时 HEAD=3b4bfbb（只比 5808493 多 5a951e4 的证据/状态提交与 3b4bfbb 的 T14 评审），
  `git diff 5808493..HEAD -- apps` 为空。T14 代码 1d106ef 已在 baseline 1d7df96 中，T14b 的 diff
  只有 5 个 web 文件。T06a（TASK-0224）仍 in_progress，主工作树有 33 个未跟踪 worker 文件，这是
  EV-1061/1062 dirty 的唯一已知原因；评审期间未修改源码/测试/合同/PRODUCT/AGENTS，未运行 gate check，
  只写本评审文件。独立复跑在 `git archive 5808493` 的临时干净导出中完成（先 `max setup` 生成
  gitignore 的 `.umi`，再跑 vitest/typecheck/lint/build），不冒充 EV。
created_at: "2026-11-25T10:54:08+08:00"
---

# REV-TASK-0244-001：M06 T14b 模板库路由接入独立评审

## 结论

**不通过（fail）**。T14b 的代码层面通过独立复验：`/workflows/templates` 已挂在 workspace layout
下，列表/编辑器/模板库共享“流程列表 / 模板库”Studio 子导航，既有 `/workflows` 重定向、列表、
编辑器与 `/chat`、`/research`、`/admin`、登录、`*` 404 均保留；生产构建包含模板库 async chunk；
干净导出的 web 全量测试 87 files / 774 tests、typecheck、lint、build 均通过。

**失败原因只有一条：EV-1061 与 EV-1062 都是 `dirty=true`，在 strict 证据绑定下不能建立 done
（F-001，P1 open）**。这不是代码缺陷，而是取证时主工作树含 T06a 的 33 个未跟踪 worker 文件；
必须在 T06a 提交、主工作树干净后重录 clean EV。375px/axe 的真实浏览器验证按计划归 T16a
（F-002，P3 accepted）；本评审不把它当作已通过。

## 评审范围与提交

- 任务卡：`.riccon/tasks/TASK-0244.json`，状态 `review`，owner `riccon/builder-b3`，
  `baseline_commit=1d7df96`，`end_commit=5808493`，evidence `[EV-1061, EV-1062]`。
- 代码提交：`5808493`（`feat(m06): T14b 接入模板库路由与 Studio 子导航`），只改 5 个文件：
  `apps/web/config/routes.ts`、`apps/web/config/routes.test.ts`、
  `apps/web/src/pages/workflow/templates/studio-nav.tsx`、
  `apps/web/src/pages/workflow/templates/studio-nav.test.tsx`、
  `apps/web/src/pages/workflow/templates/templates.css`。
- 基线 `1d7df96` 已包含 T14 的 `1d106ef`（模板页/API），因此 T14b 的评审不重审模板页功能；
  T14 评审见 `REV-TASK-0235-001`。`git diff 1d7df96..5808493 -- apps` 只有上述 5 个 web 文件，
  没有 `apps/api`、`apps/worker`、`contracts/`、`docs/PRODUCT.md`、`AGENTS.md`、`package.json`、
  pnpm lockfile，也没有列表/编辑器页面主体文件。
- 评审时 HEAD=`3b4bfbb`。`5808493..HEAD -- apps` 为空：`5a951e4` 只记录 EV/任务卡/state，
  `3b4bfbb` 只增加 T14 评审文件。T06a（TASK-0224）仍 `in_progress`，主工作树有 33 个未跟踪
  `apps/worker/**/workflow/**` 文件；这既解释了 dirty 证据，也意味着现在无法重录 clean EV。

## 路由

- `routes.ts:12-18` 新增 `wrappers?: string[]` 元数据；`:63-73` 给 `/workflows/list` 和
  `/workflows/:workflowId/edit` 挂 `@/pages/workflow/templates/studio-nav`；`:76-90` 新增
  `/workflows/templates` 顶层路由，使用 `@/layouts/workspace`，同路径子页面为
  `@/pages/workflow/templates`，同样挂 Studio 子导航，标题“模板库”。
- `/workflows` 既有父子结构未变：仍只有 `/workflows` 重定向、`/workflows/list`、
  `/workflows/:workflowId/edit` 三条子路由（`routes.test.ts:103-119` 与 T10b 的
  `layouts/workspace/navigation.test.ts` 均断言）。模板库是独立布局节点，不插入 `/workflows`
  子列表，避免破坏 T10b 已验收的深链/导航断言。
- `routes.test.ts:121-134` 验证模板库 layout→page 的 component、title、wrappers；`:136-159`
  验证 `workflow.read`、选中态和面包屑。`findRouteByPath('/workflows/templates')` 返回 layout
  节点，测试正确地下钻 `.routes[0]` 检查页面 wrappers，和配置结构一致。
- 生产构建（独立干净导出）生成 `p__workflow__templates__index.async.js`（18,297 B）与
  `p__workflow__templates__studio-nav.async.js`（14,145 B），`dist/umi.js` 可检索到
  `workflows/templates`、`workflows/list`、`workflows/:workflowId/edit`；EV-1062 日志也包含
  对应 chunk（6.74 kB / 4.34 kB gzip 摘要）。生成的 Umi 路由把三种页面都标记为 `isWrapper`
  子路由，`/workflows/templates` 是 `/workflows` 的兄弟节点；静态路径在 React Router 匹配中优先于
  父级的 list/edit 分支，未发现 `/workflows/:workflowId/edit` 抢占模板库路径的问题。
- 既有 `/chat`、`/research`、`/admin`、`/login`、`*` 404 未改；`routes.test.ts` 的既有断言和
  全量测试均通过。

## 导航

- `studio-nav.tsx:14-17` 定义“流程列表 `/workflows/list`、模板库 `/workflows/templates`”；
  `:29-37` 做互斥选中：模板库只高亮自身，其余 `/workflows/*` 归“流程列表”，编辑器深链仍高亮
  流程列表；`:52-78` 用 `hasWorkflowPermission(auth.session?.permissions, 'workflow.read')`
  决定是否渲染子导航，无权限时只渲染 `<Outlet />`，页面自身 403 守卫仍在。`hasWorkflowPermission`
  对 `undefined` 返回 false，会话未加载时不会误显示入口。
- 顶层 `workspaceNavigation` 仍是 `/chat`、`/research`、`/workflows` 三项，`/workflows` 要求
  `workflow.read`（`routes.ts:221-229`）；模板库不新增顶层菜单，避免与“编排 Studio”重复高亮。
  `isNavigationActive('/workflows/templates','/workflows')` 为 true，顶层 Studio 与子导航同时
  保持分区上下文；子导航内只有模板库一项 `aria-current="page"`。
- 面包屑由 `buildWorkspaceBreadcrumb('/workflows/templates')` 得到
  “工作空间 → 编排 Studio → 模板库”，布局只在条目 >1 时渲染（`layouts/workspace/index.tsx:127,
187-201`）；路由测试与既有布局测试口径一致。
- `templates.css:9-39` 给子导航 `max-width:100%`、`min-width:0`、`flex-wrap:wrap`，375px 下
  两个链接可换行；选中态用 `aria-current` 加下划线/颜色，不只靠颜色。
- 定向复跑 `config/routes.test.ts`、`layouts/workspace/navigation.test.ts`、
  `pages/workflow/templates/studio-nav.test.tsx`：3 files / 30 tests 全部通过，其中 T10b 的
  6 条导航断言（含“3 条子路由”）和 5 条 Studio 子导航用例均通过；未发现 `.skip/.only/todo`。

## 测试与独立复跑

使用 `git archive 5808493` 的干净导出（临时目录，仅用于复核，不录 EV），避开 T06a 未提交文件：

| 命令                                                                                                                                                 | 结果                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `pnpm --filter @riccon/web run setup`（干净导出缺少 `.umi`，先生成）                                                                                 | exit 0                                       |
| `pnpm --filter @riccon/web test`                                                                                                                     | exit 0；87 files / 774 tests passed；140.17s |
| `pnpm --filter @riccon/web typecheck`                                                                                                                | exit 0                                       |
| `pnpm --filter @riccon/web lint`（eslint --max-warnings=0 + prettier --check）                                                                       | exit 0；Prettier 全部通过                    |
| `pnpm --filter @riccon/web build`（Umi/Webpack）                                                                                                     | exit 0；生成模板库/子导航 async chunk        |
| 定向 `vitest run config/routes.test.ts src/layouts/workspace/navigation.test.ts src/pages/workflow/templates/studio-nav.test.tsx --reporter=verbose` | exit 0；3 files / 30 tests passed            |

- 全量数量与 EV-1061 的 87/774 一致；独立复跑耗时 140s vs EV 113s 属环境差异，不影响结果。
- 未使用 `--no-file-parallelism`、retry、`--skip` 等为通过追加的参数；新测试中没有 `.skip`、
  `.only`、`todo` 或吞错写法；EV-1061/EV-1062 日志中也没有 FAIL/ERROR/skipped。
- `git diff --check 1d7df96..5808493 -- apps/web` 无空白错误。

## 证据复核

| EV      | 命令                                                       | commit    | dirty    | exit | 日志/哈希                                               | 结论                      |
| ------- | ---------------------------------------------------------- | --------- | -------- | ---- | ------------------------------------------------------- | ------------------------- |
| EV-1061 | `pnpm --filter @riccon/web test`                           | `5808493` | **true** | 0    | 883 B；SHA-256 `e6d483b7…`；87 files / 774 tests passed | 内容通过，dirty 不能 done |
| EV-1062 | `pnpm --filter @riccon/web typecheck && … lint && … build` | `5808493` | **true** | 0    | 5152 B；SHA-256 `cd0c8a48…`；build 成功，含模板库 chunk | 内容通过，dirty 不能 done |

- 两条 EV 的 `manual=false`、`cwd` 为主工作树且存在、`commit=5808493` 是 HEAD 祖先；日志头
  command/cwd/exit 与 JSON 一致，输出字节数和 SHA-256 逐字匹配。
- **dirty 原因**：取证时主工作树含 TASK-0224/T06a 的 33 个未跟踪 worker 文件；评审时该目录仍
  未提交、TASK-0224 仍 `in_progress`。这不是 T14b 的改动，但 strict 证据绑定不区分“哪个任务的
  未提交文件”：`gate.mjs:392-409` 会对任务引用的任何 dirty EV 置 `evidence_attestation=fail`。
- **clean EV 关闭条件**：T06a 提交后，确认 `git status --porcelain` 为空，再由 builder-b3 在主
  工作树重录 T14b 的 web 全量 test 与 typecheck/lint/build EV，更新任务卡 evidence/end_commit；
  随后 verifier 复审并关闭 F-001。禁止用 EV-1061/1062 或临时干净导出代替任务 EV。
- EV-1061/EV-1062 的证据记录仅存在于 TASK-0244 证据目录，任务卡与本评审引用；全仓无重复 ID，
  没有空命令、占位命令或 `|| true` 等掩盖写法。

## 范围与重复路径检查

| 文件                                                        | write_scope                      | 结论 |
| ----------------------------------------------------------- | -------------------------------- | ---- |
| `apps/web/config/routes.ts`                                 | `apps/web/config/routes.ts`      | 命中 |
| `apps/web/config/routes.test.ts`                            | `apps/web/config/routes.test.ts` | 命中 |
| `apps/web/src/pages/workflow/templates/studio-nav.tsx`      | `templates/**`                   | 命中 |
| `apps/web/src/pages/workflow/templates/studio-nav.test.tsx` | `templates/**`                   | 命中 |
| `apps/web/src/pages/workflow/templates/templates.css`       | `templates/**`                   | 命中 |

- T14b 提交没有后端/worker/contracts/PRODUCT/AGENTS/构建配置改动；任务卡 status 仍为 `review`，
  没有自行 done。T14 的模板页代码在 baseline 中，不是 T14b 的 diff。
- `routes.test.ts:81-98` 把“页面路由不重复”检查收窄为 `component?.startsWith('@/pages/')`。
  **该收窄合理**：原检查会把 layout 节点也当作页面，而 `/workflows/templates` 需要
  “workspace layout + 同路径子页面”的合法组合；新谓词与 `findPageRouteByPathname`/面包屑对
  “真正渲染页面”的定义一致，仍能捕获两个 `@/pages/` 页面在同一路径的重复。唯一理论盲区是未来
  引入非 `@/pages/` 的页面组件，但仓库约定页面都在 `@/pages/`、布局都在 `@/layouts/`，当前无此
  风险，不记为 finding。

## Findings

### F-001（P1，open，owner `riccon/builder-b3`，due T14b done 前）

EV-1061/EV-1062 均 `dirty=true`，strict 下不能建立 done；clean EV 尚未重录。详见上文“证据复核”。
代码本身不需要返工；等 T06a 提交、主工作树干净后重录即可。该 P1 未关闭前，任务不得 done，
模块 review 也不能把这两条 EV 当作 T14b 的 clean 证据。

### F-002（P3，accepted，owner `riccon/builder-b3`，due M06 T16a/模块 gate 前）

375px 换行/无横向滚动与 axe 只有 jsdom/静态 CSS 覆盖，真实浏览器和实际路由跳转由 T16a 验证。
T14b 的 CSS 无固定宽度，且有 T10b 同类 P3 accepted 先例；风险有界，但不是本任务已完成的浏览器级
验收。

## 未验证与剩余风险

- **T16a 未跑**：真实浏览器 `/workflows/templates` 加载、列表/编辑器↔模板库跳转、无权限 403、
  刷新深链、375px 无横向滚动、axe serious/critical=0、键盘旅程和 M06 stub 账号，均归 T16a；
  本评审不据此判 T14b 失败，但也不把它们写成已通过。
- **T14 依赖**：`REV-TASK-0235-001` 对 T14 判 fail，原因是 EV-1058/EV-1060 在已删除的临时
  worktree 录制（P1 open），不是模板页代码缺陷；T14 与 T14b 都需要在 T06a 提交后重录 clean EV，
  M06 模块 gate 会同时受两者影响。
- **布局重挂载观察（非 finding）**：T14b 把模板库作为 `/workflows` 的兄弟 layout 路由；列表↔模板库
  切换时 workspace layout 可能重新挂载，`useWorkspaceManagement` 会再次请求部门列表。当前未在
  浏览器测量其用户可见影响，不改架构、不判 finding，T16a/性能观察可确认。
- **未验证**：coverage、安全扫描、k6 性能、迁移回滚属 M06 模块 gate；T14b 未产生这些证据。
- **测试环境说明**：干净导出缺少 gitignore 的 `.umi`，需先 `max setup`；主工作树重录 clean EV
  时 `.umi` 已存在，可直接执行。该差异不是代码失败，也不是 flake。

## 下一步

1. 等 T06a（TASK-0224）提交，确认主工作树无未提交/未跟踪的代码文件（包括评审文件也先提交）。
2. builder-b3 在主工作树重录 T14b 的 clean EV（web 全量 test + typecheck/lint/build），更新
   `TASK-0244.evidence` 与 `end_commit`；task 保持 `review`，不得 done。
3. verifier 复核新 EV 的 commit/dirty/hash 后写 `REV-TASK-0244-002`，关闭 F-001；在此之前
   T14b 不能 done，M06 gate 也不应通过。
4. T16a 承担真实浏览器 E2E/axe/375px/键盘与 M06 stub 账号；T14 clean EV 由 b1 按
   `REV-TASK-0235-001` 的 F-001 流程处理。
