---
review_id: REV-TASK-0246-001
module_id: M06
task_id: TASK-0246
reviewer:
  agent: riccon/verifier
  session: ses_dec97f134ffe12hGwmClgeDA47
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - c629783ed5482dbf4efcd5b9445d620fc58da847
  - 30e27c473b5a117315115e7d0cca2d4936a3050e
head_commit: 42f6225e8f9208176347d72dd105cfd2376a1ab7
code_commit: c629783ed5482dbf4efcd5b9445d620fc58da847
evidence_commit: 30e27c473b5a117315115e7d0cca2d4936a3050e
baseline_commit: 837e9b6e0f477cdfc07d087810c733aae157f5f5
task_card_baseline_commit: 837e9b6e
task_card_end_commit: c629783e
review_scope: >-
  M06 T12c（TASK-0246）运行历史/详情路由与导航的独立任务评审：核对
  `/workflows/runs` → 运行历史、`/workflows/runs/:workflowRunId` → 运行详情（读取
  `useParams().workflowRunId`）是否挂载 workspace layout，既有列表/编辑器/模板库/审批待办/
  问答/调研/管理端/登录/* 是否保持注册，Studio 子导航“运行历史”入口的 `workflow.read`
  可见性、选中态互斥、面包屑与 375px jsdom 断言，T12a/T12b 页面是否无回归，生产构建是否含
  history/run chunk 与 Umi 路由串，EV-1119 的 clean commit/dirty/hash，以及独立复跑定向测试
  与 web 全量。不评审 T16a 的真实浏览器 E2E/axe/375px 全旅程，不评审后端/契约改动，不替代
  M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  typecheck: true
  lint: true
  coverage: true
  review: true
  evidence: true
  scope: true
dod_not_applicable:
  - e2e
  - accessibility
  - performance
  - security
  - migration
  - docs
dod_note: >-
  acceptance 记 true 实际按 **pass (with residual F-001)** 理解：路由注册、生产 chunk、
  Studio 导航可见性/选中态/面包屑、详情参数读取在配置、组件与 jsdom 单测层通过；真实 Umi
  动态路由匹配的浏览器深链刷新、375px 无横向滚动与 axe 归 T16a/TASK-0237。build/tests/
  typecheck/lint/coverage 由 EV-1119（clean 30e27c47，apps 代码与 c629783e 一致）与本次在
  c629783e 之后的 HEAD（apps 源码未变）独立复跑支撑。security/migration/docs 不适用：本任务
  未改后端、迁移、合同、PRODUCT、ADR 或运行手册；e2e/accessibility/performance 按任务卡
  归 T16a。scope 已用 `git diff --name-only 837e9b6e..c629783e` 逐文件核对。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: "M06 T16a/模块 gate 前"
    title: "真实浏览器深链刷新、axe 与 375px 无横向滚动仍只有 jsdom/静态断言证据"
    summary: >-
      `run/detail.test.tsx` 已通过 `vi.mock('@umijs/max')` 让 `useParams` 返回
      `workflowRunId`，补上了 T12a 评审中“测试注入绕过 useParams”的缺口；但本任务仍没有在
      真实浏览器验证 `/workflows/runs/:workflowRunId` 刷新后保持同一运行、Studio 子导航在
      375px 下无横向滚动，也没有对运行历史/详情页跑 axe。`studio-nav.test.tsx` 的 375px 用例
      只断言链接可见/可聚焦，jsdom 不计算布局；`routes.test.ts` 是对路由表的静态断言，不执行
      Umi/react-router 的真实匹配。生成路由 `apps/web/src/.umi/core/route.tsx` 中 21–25 号节点、
      dist 中的 chunk 与路由串均已核对一致，风险有界。
    accepted_reason: >-
      TASK-0246 验收明确把真实浏览器深链刷新、375px 无横向滚动与 axe 归 T16a（TASK-0237）；
      本任务的交付边界是路由/导航接入与定向单测，jsdom 本就不能证明真实布局与浏览器刷新。
      T16a 在模块 gate 前必须用真实浏览器补证，不能把本评审或 jsdom 单测当作 E2E 通过。
    evidence: >-
      apps/web/config/routes.test.ts:229-346；
      apps/web/src/pages/workflow/run/detail.test.tsx:18-26,86-101；
      apps/web/src/pages/workflow/templates/studio-nav.test.tsx:200-217；
      apps/web/src/.umi/core/route.tsx:7（21–25 号节点）；
      .riccon/tasks/TASK-0237.json
evidence:
  - EV-1119
reviewed_evidence:
  - EV-1119
created_at: "2026-11-25T23:59:47+08:00"
---

# REV-TASK-0246-001：TASK-0246（M06 T12c）独立任务评审

## 结论

**pass**。运行历史/详情路由、Studio 子导航入口、生产 chunk 与现有路由回归在配置、组件和
定向测试层面成立；未发现 P0/P1/P2 新问题。真实浏览器深链刷新、axe 与 375px 全旅程仍按任务卡
归 T16a，作为 P3 accepted 记录，不阻塞本任务。

## 评审范围

- 任务卡 `.riccon/tasks/TASK-0246.json`（status=review，baseline 837e9b6e，end c629783e）。
- 代码提交 `c629783e`：`git show --stat` 仅 `apps/web/config/routes.ts`、
  `routes.test.ts`、`studio-nav.tsx`、`studio-nav.test.tsx`、`run/detail.tsx`（仅注释）、
  `run/detail.test.tsx` 与任务卡。
- 证据 `EV-1119` 及日志；设计依据 PRODUCT M06-AC-04/12/19、ADR-0024 §11、T12a/T12b 页面、
  T13b/T14b 路由模式。
- 不评审：T16a 真实浏览器 E2E/axe/375px、T12a/T12b/T12d 主体实现、后端/契约、M06 模块 gate、
  Product 预验收或用户最终验收。

## 验证了什么

### 1. 路由

- `config/routes.ts:112-133` 新增独立布局节点 `/workflows/runs`，子路由为
  `/workflows/runs` → `@/pages/workflow/history` 与 `/workflows/runs/:workflowRunId` →
  `@/pages/workflow/run/detail`，两者均挂 `@/layouts/workspace`，wrapper 均为
  `@/pages/workflow/templates/studio-nav`，标题分别为“运行历史”“运行详情”。
- 沿用 T13b/T14b 的“独立布局节点”写法，`/workflows` 的既有 `/workflows`、
  `/workflows/list`、`/workflows/:workflowId/edit` 三项未改变；`/workflows/templates`、
  `/workflows/approvals`、问答、调研、管理端、`/login` 与 `*` 404 的顺序和注册保持不变。
- 动态段名 `workflowRunId` 与 `run/detail.tsx:30-35` 的
  `params.workflowRunId` 一致；`detail.tsx` 继续优先使用注入值，生产环境回退到 Umi
  `useParams`。
- 独立复跑 `max setup` 后生成的 `apps/web/src/.umi/core/route.tsx` 含 21–25 号节点：
  `/workflows/runs` 布局、`/workflows/runs` 页面、`/workflows/runs/:workflowRunId` 页面，
  `originPath` 与 title 正确；`dist/umi.js` 含 `p__workflow__history__index`、
  `p__workflow__run__detail` 与 `"/workflows/runs/:workflowRunId"` 字符串。
- 生产构建产物存在 `p__workflow__history__index.async.js`（19.06 kB gzip）与
  `p__workflow__run__detail.async.js`（8.15 kB gzip）；`check-prod-bundle.mjs` 扫描 98 个
  文件通过，无 mock/stub 特征。

### 2. 导航

- `studio-nav.tsx:24-30` 在“模板库”与“审批待办”之间加入“运行历史”，路径
  `/workflows/runs`；外层 `canRead` 仍按 `workflow.read` 控制整个子导航，无权限时不渲染
  入口（`studio-nav.tsx:76-100`）。
- `isStudioNavActive` 改为“非流程列表入口只按自身路径及下级深链高亮；流程列表覆盖其余
  `/workflows/*` 并排除全部子页面”，行为对既有模板库/审批待办等价，并让
  `/workflows/runs/:id` 归属“运行历史”，保证同一页面最多一个 `aria-current="page"`。
- 顶层 `workspaceNavigation` 未新增项，运行历史通过 Studio 子导航承载；`/workflows/runs`
  仍由 `/workflows` 顶层项高亮，面包屑由 `buildWorkspaceBreadcrumb` 生成
  “工作空间 → 编排 Studio → 运行历史/运行详情”。
- 定向测试覆盖：运行历史页只高亮运行历史、详情深链仍归属运行历史且唯一
  `aria-current`、列表/模板库把运行历史作为普通入口、无 `workflow.read` 时入口与子导航
  一起隐藏、375px 下运行历史与审批待办可见/可聚焦。

### 3. 集成与回归

- T12a 运行详情：`run/detail.test.tsx` 新增 `useParams` 替身用例，验证未注入
  `workflowRunId` 时从动态段读取并调用 `getRunDetail('run-from-params')`；原“注入动态段”
  与“缺少运行 ID”用例保留。`run/detail.tsx` 仅更新注释，无行为变化。
- T12b 运行历史：`workflow/history/index.tsx:149` 继续导航到
  `/workflows/runs/${encodeURIComponent(runId)}`，与本任务注册的动态段匹配；历史页与
  发布面主体未被修改。
- 全量 web 套件 106 文件/992 用例通过（含 T12a/T12b/T12d、Studio 导航、审批、模板库与
  管理端既有测试），未发现回归。
- 变更面未触及后端、contracts、PRODUCT、AGENTS、T12a/T12b/T12d 主体；`git diff
c629783e..30e27c47 -- apps` 为空，`git diff --name-only 837e9b6e..c629783e` 的代码文件
  全部落在任务卡 5 个声明路径内。

### 4. 测试与证据

- EV-1119：command 覆盖 `pnpm --filter @riccon/web test:coverage`、typecheck、lint、build、
  `check-prod-bundle` 与 history/run chunk、`/workflows/runs/:workflowRunId` 字符串守卫；
  `commit=30e27c47`、`dirty=false`、`exit_code=0`、output 20,724 bytes、sha256
  `ead5d642…de026b` 与日志实算一致；日志内 106 files/992 tests、覆盖率
  Statements 87.57 / Branches 80.61 / Functions 85.37 / Lines 87.47，高于合同 80/70。
- 本次独立复跑（HEAD `42f6225e`，apps 源码与 c629783e 相同）：
  - `vitest run config/routes.test.ts src/pages/workflow/templates/studio-nav.test.tsx
src/pages/workflow/run/detail.test.tsx`：3 文件/40 用例通过，exit 0。
  - `pnpm --filter @riccon/web test:coverage`：106 文件/992 用例通过，覆盖率与 EV-1119
    一致（87.57/80.61/85.37/87.47），exit 0。
  - `typecheck && lint && build`：exit 0；build 12.54s 完成，chunk 与 bundle 守卫复核通过。
  - 三个变更测试文件无 `.skip`/`.only`，未发现零测试或吞错假绿。

## AC 对账（任务级）

| AC                                     | 本任务可判定的用户可见结果                                                                                  | 结论                       | 残余                                                                               |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------- |
| M06-AC-04（运行详情刷新/重开保持状态） | 详情页已有可刷新/可收藏 URL，动态段 `workflowRunId` 能传给页面并触发 `getRunDetail`；jsdom 用例验证参数读取 | pass (with residual F-001) | 真实浏览器刷新后的轮询/时间线保持由 T16a E2E-M06-03 验证                           |
| M06-AC-12（运行历史入口与可见性）      | Studio 子导航新增“运行历史”，`workflow.read` 才有入口；历史页与详情 URL 可达，历史页跳转路径与路由匹配      | pass (with residual F-001) | 列表过滤/空态/统一 404 属 T12b 页面与 T16a E2E-M06-08/SEC-WF-003，本任务不重新判定 |
| M06-AC-19（五界面状态/375px/键盘）     | 无权限时运行历史入口随子导航隐藏；375px jsdom 断言链接可见可聚焦，未新增真实布局证据                        | pass (with residual F-001) | axe、真实 375px 无横向滚动与键盘全旅程由 T16a/@a11y 验证                           |

## Findings

### F-001（P3，accepted）

真实浏览器深链刷新、axe 与 375px 无横向滚动仍只有 jsdom/静态断言证据。详见 front matter；
owner `riccon/verifier`，due “M06 T16a/模块 gate 前”。T16a 完成前不得把本任务视为
E2E/a11y 通过。

## 未验证

- 真实浏览器中的深链刷新、跨页跳转、通知链接打开详情与 375px 横向滚动：任务卡明确归
  T16a/TASK-0237，本任务未运行。
- axe serious/critical 与键盘全旅程：归 T16a `@a11y`。
- 后端/Maven、契约、迁移：本任务未改这些面，未重复运行。
- perf：本任务无性能预算变更，未运行 k6。

## 剩余风险

- F-001 的真实浏览器缺口由 T16a 收口；若 T16a 发现 Umi 静态/动态路由排序或 375px 布局
  问题，需回到路由/导航修复，而不是把 jsdom 证据当作通过。
- 沿用 T12b 既有 accepted P3（流程所有者对他人运行显示“查看详情”后 404、版本选择器只
  加载最近 100 个版本）不属于本任务变更，本评审不重开。

## 证据

- `EV-1119`：`.riccon/evidence/TASK-0246/EV-1119.json` 与
  `.riccon/evidence/TASK-0246/EV-1119-2026-11-25T23-44-39-132+08-00-pnpm-filter-riccon-web-test-coverage-pnpm-filter.log`
  （clean 30e27c47，apps 与 c629783e 一致，exit 0，覆盖率 87.57/80.61/85.37/87.47）。
- 本次独立复跑日志（未登记新 EV）：
  `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/task-0246-web-coverage.log`
  与 `.../task-0246-web-checks.log`；定向 40 用例、全量 992 用例、typecheck/lint/build 均
  exit 0。
