---
review_id: REV-TASK-0242-001
module_id: M06
task_id: TASK-0242
reviewer:
  agent: riccon/verifier
  session: ses_deffe8f27ffeS3CsWHPrnehNZZ
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 4e302e73d1ff38de6626f61a4b777c5b5d58706e
  - 4448778a27f200fed10630163b78fa39ef0c76b5
  - e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
head_commit: 90d8d406b4d9b243f5678ac338e50016d9994ea9
code_commit: 4e302e73d1ff38de6626f61a4b777c5b5d58706e
test_commit: 4448778a27f200fed10630163b78fa39ef0c76b5
evidence_head: e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
recorded_baseline_commit: d324e11f06fa189569175cdc5e82ddb16a1f3426
recommended_baseline_commit: a35d1f572bf10c1bc75130cbc91c469a518e9769
review_scope: >-
  M06 T10b 前端路由与导航独立评审：核对 /workflows 路由注册、工作空间导航权限过滤、
  aria-current/面包屑/375px、web 全量测试/typecheck/lint/build、EV-1048/EV-1049 的
  dirty/commit/hash，以及实际 diff 与 write_scope。页面功能与视觉验收属 T10（TASK-0230）
  另开评审；本评审不运行 M06 模块 gate check，不替代模块 review、Product 预验收或用户验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  e2e: true
  evidence: true
  scope: true
dod_not_applicable:
  - coverage
  - security
  - performance
  - migration
  - workflow_page_a11y
  - workflow_page_e2e
dod_note: >-
  acceptance/build/tests/lint/typecheck/e2e/evidence/scope 为本任务范围结论；coverage、security、
  performance、migration 属 M06 模块 gate。375px 单测残余见 F-001；工作流页面专属浏览器 E2E
  与 axe 属 T16a/模块 gate，当前以共享布局的 J2 E2E 和 responsive 回归覆盖。
findings:
  - id: F-001
    severity: P3
    status: accepted
    title: "375px 单测只验证可见与聚焦，未断言换行或无横向滚动"
    summary: >-
      apps/web/src/layouts/workspace/index.test.tsx:147-162 设置 window.innerWidth=375，但 jsdom
      不计算 CSS 布局；断言只覆盖主导航/面包屑可见、Studio 链接可聚焦，无法证明 flex-wrap 与
      768px 媒体查询在 375px 下生效。
    owner: riccon/builder-b3
    due: "M06 T16a/模块 gate 前"
    accepted_reason: >-
      本次评审已在真实 Chromium 用 e2e/tests/responsive.spec.ts 复跑共享 workspace 布局（含新增
      面包屑）375px，J1/J2 均无横向滚动；J2 关键旅程 axe serious/critical=0。工作流页面专属
      375px E2E 属 T16a/模块 gate；当前 CSS 为静态 flex-wrap + 768px 媒体查询，风险有界。
    evidence: "EV-1048；本评审 responsive.spec.ts + j2.spec.ts 复跑"
  - id: F-002
    severity: P3
    status: fixed
    title: "4e302e7 临时修改 apps/web/config/routes.test.ts（不在 write_scope）"
    summary: >-
      T10b 首个提交把约 96 行工作空间导航/面包屑断言写进 config/routes.test.ts；该文件不在
      TASK-0242 write_scope（仅 config/routes.ts）。4448778 将断言迁到
      layouts/workspace/navigation.test.ts 并恢复 routes.test.ts，最终净 diff 为零。
    owner: riccon/builder-b3
    evidence: "4e302e7；4448778；git diff d324e11..4448778 -- apps/web/config/routes.test.ts 为空"
  - id: F-003
    severity: P3
    status: accepted
    title: "任务 baseline d324e11 跨越并行 T05 提交 a35d1f5，CLI scope 误报后端文件"
    summary: >-
      .riccon/tasks/TASK-0242.json:31 的 baseline_commit=d324e11；该区间包含并行 TASK-0223 在
      a35d1f5 的 spotless 后端格式化，.riccon/state.json:28 因此把 10 个 apps/api 文件归到
      TASK-0242。T10b 两个提交实际只改 apps/web。
    owner: riccon/lead
    due: "TASK-0242 关闭前 / M06 gate 前"
    accepted_reason: >-
      代码风险为零：本评审逐提交核对 4e302e7/4448778 均为 apps/web，最终净 diff 5 个文件全部
      命中 write_scope；backend 文件属并行 TASK-0223。建议 Lead 将 baseline 设为 a35d1f5、
      end 设为 4448778，消除状态机误报；不影响本任务代码验收。
    evidence: ".riccon/state.json:28；a35d1f5；4e302e7；4448778"
evidence:
  - EV-1048
  - EV-1049
reviewed_evidence:
  - EV-1048
  - EV-1049
created_at: "2026-11-25T08:10:23+08:00"
---

# REV-TASK-0242-001：TASK-0242（M06 T10b 前端路由与导航）独立评审

## 结论

**pass**。`/workflows`、`/workflows/list`、`/workflows/:workflowId/edit` 已正确挂到
workspace layout；工作空间导航按 `workflow.read` 过滤，选中态与面包屑由同一份路由元数据
推导；既有 `/chat`、`/research`、`/admin`、登录与 `*` 404 保留；生产构建包含 workflow
async chunks。EV-1048/EV-1049 为 clean 证据且哈希一致；独立复跑 web 全量测试 736/736、
typecheck/lint/build、共享布局 375px 与 J2 axe/E2E 均通过。无 P0/P1/P2；两条 P3 accepted
为测试强度与任务 baseline 行政项，一条 P3 fixed 为已回退的临时越界测试改动。

## AC 对账

| 验收项（原文拆解）                                                                      | 结论                       | 证据                                                                                                                           |
| --------------------------------------------------------------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| 接入 `/workflows`、`/workflows/list`、`/workflows/:workflowId/edit` 到 workspace layout | pass                       | `routes.ts:46-66`；生成路由 id 7–10；`navigation.test.ts` 路由注册用例                                                         |
| 导航增加“编排 Studio”（`workflow.read`），无权限隐藏/403                                | pass                       | `routes.ts:192-216`；布局 126-156；列表页本地 403 守卫；页面 403 测试                                                          |
| 平台/租户/404 状态正确                                                                  | pass                       | `/admin/*`、`/login`、`*` 404 路由未变；`navigation.test.ts` 断言 404 与既有入口；全量 web 测试                                |
| 生产构建包含这些页面                                                                    | pass                       | `dist/p__workflow__list__index.async.js`、`dist/p__workflow__edit__index.async.js`；EV-1049 构建日志                           |
| 补路由/导航测试（权限、路径、选中态、375px）                                            | pass (with residual F-001) | 6 条 `navigation.test.ts` + 布局权限/选中/面包屑用例；真实浏览器 375px 共享布局回归通过，单测本身的 375px 断言强度不足见 F-001 |
| `pnpm --filter @riccon/web test/typecheck/lint/build` 通过                              | pass                       | 本评审独立复跑 736/736、typecheck/lint/build exit 0；EV-1048/EV-1049                                                           |
| 不改后端/contracts/PRODUCT/AGENTS                                                       | pass                       | `4e302e7`、`4448778` 提交仅 `apps/web/**`                                                                                      |
| 提交后取证，任务只移 review                                                             | pass                       | EV-1048/EV-1049 clean；任务状态 `review`，未 done                                                                              |

## 评审对象与提交

| 项                            | 值                                                                                                 |
| ----------------------------- | -------------------------------------------------------------------------------------------------- |
| 任务卡 baseline               | `d324e11f06fa189569175cdc5e82ddb16a1f3426`（`.riccon/tasks/TASK-0242.json:31`）                    |
| 代码提交                      | `4e302e73d1ff38de6626f61a4b777c5b5d58706e`（路由/导航/布局/样式 + 临时测试）                       |
| 测试归位提交                  | `4448778a27f200fed10630163b78fa39ef0c76b5`（断言迁入 `navigation.test.ts`，恢复 `routes.test.ts`） |
| 证据提交                      | `e5f5586858a0d7c3cd42ac050a2f95e2a41a232e`（EV-1048/EV-1049 绑定）                                 |
| 当前 HEAD                     | `90d8d406b4d9b243f5678ac338e50016d9994ea9`                                                         |
| 建议 Lead 设置的 baseline/end | `a35d1f572bf10c1bc75130cbc91c469a518e9769` / `4448778a27f200fed10630163b78fa39ef0c76b5`            |

需要特别说明：`a35d1f5` 的父提交是 `4e302e7`，即 `a35d1f5` 树已包含 T10b 功能代码；它同时
是并行 TASK-0223 的 spotless 提交。因此按任务卡 `d324e11` 做区间归因会把 T05 的后端文件误算
给 TASK-0242（见 F-003）。`git diff 4448778..e5f5586 -- apps/web` 为空，证据提交与代码
终态的 web 树完全一致。

## 路由

- `apps/web/config/routes.ts:46-66` 新增 `/workflows` 父路由，挂 `@/layouts/workspace`：
  - `/workflows` → `redirect: '/workflows/list'`；
  - `/workflows/list` → `@/pages/workflow/list`，title `编排 Studio`；
  - `/workflows/:workflowId/edit` → `@/pages/workflow/edit`，title `流程编辑`。
- Umi 生成路由 `apps/web/src/.umi-production/core/route.tsx` 中对应 id 7–10，父子关系、
  redirect 与 lazy import 正确；`/workflows/:workflowId/edit` 由页面 `useParams` 读取，
  深链/刷新不依赖额外状态。
- 既有路由未变：`/`→`/chat`、`/chat` 独立页面（不加载 workspace layout）、`/research/*`、
  `/admin/*`、`/login`、`*`→`@/pages/404`。`navigation.test.ts` 对 `/chat`、`/research/tasks`、
  `*` 404 做了回归断言。
- 面包屑匹配支持 `:param`：`buildWorkspaceBreadcrumb('/workflows/workflow-1/edit')` 返回
  `工作空间 → 编排 Studio → 流程编辑`，列表页标题与分区同名时去重；`/research/:id` 同样
  返回 `工作空间 → 市场调研报告 → 报告任务详情`。

## 导航

- `workspaceNavigation` 与 `filterWorkspaceNavigation` 位于 `config/routes.ts:192-216`；
  `/chat`、`/research` 保持“始终渲染、服务端 403 兜底”的既有语义，`/workflows` 声明
  `permissions: ['workflow.read']`，无权限时隐藏入口。
- 布局 `apps/web/src/layouts/workspace/index.tsx:126-156` 使用会话权限过滤导航，
  以 `isNavigationActive(pathname, item.path)` 设置 `aria-current="page"`；列表与编辑器深链
  均命中 `/workflows` 分区，`/research-archive` 这类前缀路径不会误命中。
- 面包屑 `index.tsx:186-201` 使用 `aria-label="面包屑"`、`<ol>` 结构，当前项为
  `aria-current="page"`，非当前项为 `Link`；`/chat` 只有一级时不渲染，J1 独立布局不受影响。
- 无权限直达：列表页在 `apps/web/src/pages/workflow/list/index.tsx:137-140` 本地判定
  `!canRead` 进入 `forbidden`（403 Result），编辑页对 API 403/404 分别显示无权限/不可见
  （`apps/web/src/pages/workflow/edit/index.tsx:1007-1041`）；这部分页面守卫属 T10，页面测试
  已在全量 web 测试中通过，本任务路由接线没有绕过它。
- 375px：`workspace.css:43-131` 使用 flex-wrap 与 `max-width: 768px` 媒体查询将头部改列、
  导航占满宽度；本次评审用真实 Chromium 在 375px 复跑 J1/J2 页面，无横向滚动（F-001 仍
  记录单测本身的强度不足）。

## 测试与证据

### 独立复跑（当前 HEAD，web 树与 `4448778` 一致）

| 命令                                                                                                                                         | 结果                                                        |
| -------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| `pnpm --filter @riccon/web test`                                                                                                             | exit 0，82 files / 736 tests passed，79.11s                 |
| `pnpm --filter @riccon/web typecheck && pnpm --filter @riccon/web lint && pnpm --filter @riccon/web build`                                   | exit 0；Prettier/ESLint 通过；生产构建成功                  |
| `pnpm --filter @riccon/web exec vitest run src/layouts/workspace/navigation.test.ts src/layouts/workspace/index.test.tsx --reporter=verbose` | 2 files / 16 tests passed                                   |
| `pnpm exec playwright test --config e2e/playwright.config.ts e2e/tests/responsive.spec.ts`                                                   | 2 tests passed（J1 375px、J2 列表/详情 375px 均无横向滚动） |
| `pnpm exec playwright test --config e2e/playwright.config.ts e2e/tests/j2.spec.ts`                                                           | 3 tests passed（含 axe serious/critical=0、刷新后深链恢复） |

未使用 `--no-file-parallelism`、retry 或其他为通过而追加的参数；无零测试、无跳过、无 flake。

### EV-1048 / EV-1049 复核

| 证据    | commit    | dirty | exit | 命令                                                       | 哈希核对                                |
| ------- | --------- | ----- | ---- | ---------------------------------------------------------- | --------------------------------------- |
| EV-1048 | `e5f5586` | false | 0    | `pnpm --filter @riccon/web test`（82 files / 736 tests）   | 日志 sha256 `c592ea37…` 与 EV JSON 一致 |
| EV-1049 | `e5f5586` | false | 0    | `pnpm --filter @riccon/web typecheck && … lint && … build` | 日志 sha256 `f36946c9…` 与 EV JSON 一致 |

- `git diff 4448778..e5f5586 -- apps/web` 为空：证据虽绑定 docs 提交 `e5f5586`，但 web 树与
  代码终态 `4448778` 一致，SV 命令确实覆盖被评审代码。
- 被替代证据归档在 `.riccon/archive/evidence-superseded/TASK-0242/`：EV-1042/EV-1043
  `dirty=true`（并行 TASK-0223 未提交改动），EV-1046/EV-1047 clean 但录制后测试树发生迁移；
  最终任务 `evidence` 仅保留 EV-1048/EV-1049，未污染 strict gate。
- 独立复跑测试数量与 EV-1048 相同（736），说明测试归位后没有丢用例。

## 构建

- `pnpm --filter @riccon/web build` 成功；`apps/web/dist/` 生成
  `p__workflow__list__index.async.js`（本次 17,631 B，EV-1049 记录 6.46 kB gzip）与
  `p__workflow__edit__index.async.js`（本次 79,009 B，EV-1049 记录 21.65 kB gzip），
  以及两页的 chunk CSS。
- 生产生成路由含 `/workflows`、`/workflows/list`、`/workflows/:workflowId/edit`，
  `dist/umi.js` 可检索到 `workflows/list` 与 `workflows/:workflowId/edit`；满足“生产构建
  包含这些页面”。
- 未做 bundle 预算专项检查（不在本任务 AC；模块 gate 的 perf/构建约束另测）。

## 范围与任务行政

| 文件                                                | write_scope                         | 结论                                                  |
| --------------------------------------------------- | ----------------------------------- | ----------------------------------------------------- |
| `apps/web/config/routes.ts`                         | `apps/web/config/routes.ts`         | 命中                                                  |
| `apps/web/src/layouts/workspace/index.tsx`          | `apps/web/src/layouts/workspace/**` | 命中                                                  |
| `apps/web/src/layouts/workspace/index.test.tsx`     | 同上                                | 命中                                                  |
| `apps/web/src/layouts/workspace/navigation.test.ts` | 同上                                | 命中（4448778 新增）                                  |
| `apps/web/src/layouts/workspace/workspace.css`      | 同上                                | 命中                                                  |
| `apps/web/config/routes.test.ts`                    | 不在范围（仅 `routes.ts`）          | 4e302e7 临时修改；4448778 恢复，净 diff 为零（F-002） |

- `4e302e7` 与 `4448778` 两个提交都只改 `apps/web/**`；未改 apps/api、apps/worker、
  contracts、docs/PRODUCT、AGENTS、迁移或构建配置。
- 最终净 diff（`git diff d324e11..4448778 -- apps/web`）只包含上述 5 个 web 文件；后端文件
  不在 T10b 提交中，CLI scope 误报源于 baseline（F-003）。
- 任务状态为 `review`，未被标记 done；本评审只运行只读的 `riccon state validate`（0 errors，45 条既有 warning），不运行 `gate check`，不改变任务状态。

## 未验证与剩余风险

1. **工作流页面专属真实浏览器 E2E 未跑**：`e2e/stub/server.mjs` 的账号权限不含
   `workflow.read`，本评审不改 stub，无法在现有 E2E 环境点开 Studio 入口并验证
   `/workflows/list`、`/workflows/:id/edit` 的深链/刷新与 375px。建议 T16a/模块 gate 增加
   M06 stub 账号与 workflow E2E（含 axe、375px、刷新深链）。
2. **模块级 coverage/security/performance/migration 未跑**：属 M06 模块 review 与 gate；
   本任务只跑 web 定向/全量 web 测试。
3. **T10 页面功能与视觉**：列表/编辑器的交互、文案、DAG 节点编辑等由 TASK-0230 独立评审；
   本评审只判路由、导航、构建可达。
4. 新增面包屑也会出现在 `/research/*`（共享布局）；本次 J2 E2E 与 axe 通过，视觉是否符合
   Product 预期由 Product/T10 页面评审确认。
5. 生产环境深链刷新依赖反向代理回退到 `index.html`；`apps/web/config/config.ts:19` 明确该
   配置属 M09 Helm，本任务只验证 Umi browser history、dev E2E 刷新与构建产物。
6. 证据绑定 `e5f5586` 而非代码终态 `4448778`，已用空 web diff 证明等价；若后续模块 gate
   要求证据与 end commit 严格同一提交，需 Lead 明确口径。

## Findings

| ID    | 级别 | 状态     | 摘要                                                                                           | owner             | due                            |
| ----- | ---- | -------- | ---------------------------------------------------------------------------------------------- | ----------------- | ------------------------------ |
| F-001 | P3   | accepted | 375px 单测未断言换行/无横向滚动；真实浏览器共享布局已通过                                      | riccon/builder-b3 | M06 T16a/模块 gate 前          |
| F-002 | P3   | fixed    | `routes.test.ts` 临时越界修改已迁移并恢复，净 diff 为零                                        | riccon/builder-b3 | —                              |
| F-003 | P3   | accepted | baseline `d324e11` 跨越并行 T05 提交导致 CLI scope 误报；建议 baseline `a35d1f5`/end `4448778` | riccon/lead       | TASK-0242 关闭前 / M06 gate 前 |

## 下一步

1. Lead 在 TASK-0242 关闭前把任务 baseline 更新为 `a35d1f5`、end 设为 `4448778`，消除
   `state.json` 中把并行 T05 后端文件归到本任务的误报（F-003）。
2. 本任务代码验收通过；TASK-0242 可转 done，但模块 M06 仍需等待其余任务、T16a workflow
   E2E 与模块 review。
3. T10（TASK-0230）页面评审另开；本评审不替代其结论。
