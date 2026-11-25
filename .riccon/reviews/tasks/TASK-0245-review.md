---
review_id: REV-TASK-0245-001
module_id: M06
task_id: TASK-0245
reviewer:
  agent: riccon/verifier
  session: ses_ded5d6b4effecEwUCyyfDOTNnZ
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 77c0b3043f34293655f37befa7cce715b5617128
  - 6c4af42c6c8cefb6340ef8885bf91abe98d903bc
head_commit: 6c4af42c6c8cefb6340ef8885bf91abe98d903bc
code_commit: 77c0b3043f34293655f37befa7cce715b5617128
ev_commit: 77c0b3043f34293655f37befa7cce715b5617128
end_commit: 6c4af42c6c8cefb6340ef8885bf91abe98d903bc
baseline_commit: cc014f79fd3017eb9578081cb064345c85e93de6
review_scope: >-
  M06 T13b（TASK-0245）独立任务评审：核对 `/workflows/approvals` 与
  `/workflows/approvals/:workflowApprovalId` 路由、workspace layout、Studio 子导航“审批待办”的
  `workflow.approve` 过滤与选中态/面包屑/375px、详情 `initialApprovalId` 注入、退出导航、
  T13 待办/详情/决定不回归、EV-1091 clean 证据与提交范围。独立复跑 routes/studio-nav/approvals
  定向 37 条测试、web typecheck、lint、生产构建与 chunk 检查；未运行真实浏览器
  E2E/axe/375px（T16a/TASK-0237）、未运行后端全量、未运行 gate check，不替代 M06 模块 review、
  Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  lint: true
  typecheck: true
  scope: true
  review: true
  evidence: true
dod_not_applicable:
  - e2e
  - accessibility
  - performance
  - migration
  - docs
dod_note: >-
  acceptance 记 true 按 **pass (with residual F-001)** 理解：路由注册、生产 chunk、Studio 导航
  权限/选中态/面包屑、详情深链与退出导航在配置、组件和单测层通过；真实 Umi 参数读取、浏览器
  深链刷新、E2E-M06-04、@a11y 与真实 375px 布局归 T16a/TASK-0237，本任务未执行。
  build/tests/lint/typecheck/coverage 由 EV-1091 与本评审独立复跑支撑；scope 由
  `git diff --name-only cc014f79 77c0b304` 的 7 个文件逐条支撑。docs 无影响：本任务只增加
  路由/导航与测试，未改合同、PRODUCT、ADR 或运行手册。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: "M06 T16a/模块 gate 前"
    title: "真实 Umi 路由参数读取、浏览器深链刷新与 375px 三链接布局仅有 jsdom/静态 CSS 证据"
    summary: >-
      详情组件测试通过注入 `workflowApprovalId` 绕过 `useParams`（测试替身
      `src/test/umijs-max.tsx:41-44` 返回空对象），因此没有在单元层直接验证
      `/workflows/approvals/:workflowApprovalId` 动态段到 `params.workflowApprovalId` 的生产读取；
      375px 用例只断言链接可见/可聚焦，jsdom 不计算布局；axe 与真实浏览器刷新未运行。路由表
      路径、组件和参数名已通过源码与 Umi 生成路由逐项核对，未发现拼写不一致。
    accepted_reason: >-
      T13b 任务卡与 T16a/TASK-0237 明确把真实浏览器深链刷新、E2E-M06-04、@a11y、375px 与键盘
      旅程归 T16a；T13b 的验收要求是路由/导航接入与定向单测，jsdom 本就不能证明真实布局和刷新。
      生成路由（`apps/web/src/.umi/core/route.tsx` 16-20 号节点）和源码参数名一致，风险有界；
      模块 gate 前必须由 T16a 用真实浏览器补证，不能把本单测当作深链刷新通过。
    evidence: >-
      apps/web/src/pages/workflow/approvals/detail.tsx:33-45；
      apps/web/src/test/umijs-max.tsx:41-44；
      apps/web/src/pages/workflow/approvals/detail.test.tsx:69-122；
      apps/web/src/pages/workflow/templates/studio-nav.test.tsx:140-153；
      apps/web/src/.umi/core/route.tsx:7（16-20 号节点）；TASK-0237 验收第 1 条
evidence:
  - EV-1091
reviewed_evidence:
  - EV-1091
notes: >-
  T13b 代码提交 77c0b304 只含 apps/web/config/routes.ts、routes.test.ts、
  templates/studio-nav.tsx、studio-nav.test.tsx 与 approvals/detail.tsx、detail.test.tsx、
  index.tsx 共 7 个文件，全部落在任务 write_scope；未改后端/contracts/PRODUCT/AGENTS。
  6c4af42c 为任务收口（记录 EV-1091 并移 review）；评审期间并行 T08 继续提交证据
  （49ab2c3e、e00081bb、5ada9580），均不属 T13b；工作区另见 T08 未提交证据文件，本评审未修改
  或回退。旧 EV 未引用；未运行 gate check。
created_at: "2026-11-25T20:28:00+08:00"
---

# REV-TASK-0245-001：M06 T13b 审批待办路由与导航独立评审

## 结论

**通过（pass，带残余 F-001/P3）**。`/workflows/approvals` 与
`/workflows/approvals/:workflowApprovalId` 已挂到 workspace layout，待办路由渲染
`@/pages/workflow/approvals`，详情路由通过 `initialApprovalId` 打开指定审批；Studio 子导航新增
“审批待办”，按 `workflow.approve` 过滤，列表/模板库/审批待办选中态互斥，面包屑与 375px 键
盘焦点有测试；详情返回与决定成功都会导航回待办路由。T13 的待办/详情/决定行为未回归。
EV-1091 clean、命令覆盖 test/coverage/typecheck/lint/build/chunk，哈希与字节数一致；独立复跑
定向 37 条测试、typecheck、lint、生产构建和 chunk 检查全部通过，未发现新的 P0/P1/P2 缺陷。

残余 **F-001（P3）** 不是 T13b 代码失败：真实 Umi 参数读取、浏览器深链刷新、E2E-M06-04、
@a11y 与真实 375px 布局归 T16a/TASK-0237，本任务按计划未执行，模块 gate 前需要 T16a 补证。

## AC 对账

| AC 原文用户可见结果（节选）                                                                                                                          | 结论                                  | 证据                                                                                                               |
| ---------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| AC-05：审批人在审批待办；When 打开待办，Then 只显示分配给自己的待审批项，按创建时间升序                                                              | pass（入口可达；过滤/排序为 T13/T09） | `apps/web/config/routes.ts:92-111`；`routes.test.ts:163-199`；T13 `REV-TASK-0234-001` 已验收页面内容               |
| AC-05：When 打开审批详情，Then 显示动作/工具/数据范围/脱敏参数，审批人可批准或拒绝                                                                   | pass（T13 已验收，不回归）            | `apps/web/src/pages/workflow/approvals/index.tsx:72-122`；定向 `approvals/index.test.tsx`；EV-1091 837 条全量      |
| AC-05：批准后运行继续；拒绝后终止且记录审批人/时间/决定/理由；无 ACL 时只显示脱敏摘要                                                                | pass（服务端 T09；前端 T13 不回归）   | T13 `REV-TASK-0234-001` 的 AC 对账；本任务未改详情/决定渲染逻辑                                                    |
| AC-19：审批待办加载/空/错误/无权限/禁用/成功态，空态固定文案，错误态重试/返回，无权限联系管理员                                                      | pass（T13 已验收，路由可达）          | `REV-TASK-0234-001`；本任务只接入路由与导航                                                                        |
| AC-19：375 px 无横向滚动，关键按钮键盘可聚焦且焦点可见                                                                                               | pass (with residual F-001)            | `templates.css:17-24` 使用 `flex-wrap`；`studio-nav.test.tsx:139-153` 仅 jsdom；真实 375px/@a11y 归 T16a           |
| 任务 AC-1：`/workflows/approvals` 待办、`:workflowApprovalId` 详情（`initialApprovalId`）、workspace layout、子导航、选中态/面包屑/375px、生产 chunk | pass (with residual F-001)            | `routes.ts:92-111`；`studio-nav.tsx:24-48,65-93`；`detail.tsx:18-45`；独立构建与 chunk 检查；真实浏览器部分归 T16a |
| 任务 AC-2：补路由/导航测试；web test/typecheck/lint/build 通过；不改后端/contracts/PRODUCT/AGENTS；提交取证，只移 review                             | pass                                  | EV-1091；独立定向 37 条 + typecheck/lint/build；`git diff --name-only cc014f79 77c0b304`；任务 status=review       |

## 路由

- `apps/web/config/routes.ts:92-111` 新增独立布局节点：父节点 `path: '/workflows/approvals'`、
  `component: '@/layouts/workspace'`，子节点分别为待办页
  `@/pages/workflow/approvals`（title “审批待办”）与详情页
  `@/pages/workflow/approvals/detail`（title “审批详情”），两者均挂
  `@/pages/workflow/templates/studio-nav` wrapper。该写法与既有 `/workflows/templates` 深链模式
  一致，不改变 `/workflows` 的 list/edit 父子结构。
- Umi 生成路由表（`.umi/core/route.tsx` 16-20 号节点）确认父布局、待办、详情及 wrapper 均实际
  生成；`/workflows/approvals/:workflowApprovalId` 的 `originPath` 与源码一致，参数名
  `workflowApprovalId` 与 `detail.tsx:33-36` 的 `params.workflowApprovalId` 匹配。
- 既有路由保留：`routes.test.ts:191-207` 断言 `/workflows` 子路由仍为
  `/workflows`、`/workflows/list`、`/workflows/:workflowId/edit`，并回归 templates/chat/
  research/admin/login/`*` 404；`routes.test.ts:88-130` 的既有测试继续通过。
- 生产构建：独立复跑 `pnpm --filter @riccon/web build` 与
  `node apps/web/scripts/check-prod-bundle.mjs` 通过；`p__workflow__approvals__detail.async.js`
  存在，页面标记 `workflow-approvals-page` 位于共享 `3183.async.js`。生成
  `preload_helper.js` 显示 `/workflows/approvals` 预加载 3183（待办页 JS）与 approvals CSS，
  `/workflows/approvals/:workflowApprovalId` 额外预加载 detail chunk；待办页 JS 因详情页复用
  `./index` 被 Umi 提取为共享 chunk，不是缺失。

## 导航

- `studio-nav.tsx:24-28` 在既有“流程列表 / 模板库”之后追加“审批待办”，权限为
  `WORKFLOW_PERMISSIONS.approve`；`studio-nav.tsx:69-74` 对条目做权限过滤。外层仍按
  `workflow.read` 决定是否渲染整个 Studio 子导航，与既有 Studio 入口语义一致。
- 选中态：`isStudioNavActive`（`studio-nav.tsx:40-49`）让审批待办走精确前缀匹配，流程列表排除
  模板库与审批待办，模板库只匹配自身；`studio-nav.test.tsx:98-131` 验证列表页仍高亮流程列表、
  审批待办页只有审批待办高亮、详情深链同样归属审批待办。
- 权限：`studio-nav.test.tsx:52-66` 验证只有 `workflow.read`、没有 `workflow.approve` 时审批
  待办入口隐藏，避免死链；审批页自身仍保留无权限 403 与返回 Studio 的动作。
- 面包屑：`routes.test.ts:210-227` 验证 `/workflows/approvals` 为“工作空间 → 编排 Studio →
  审批待办”，详情为“工作空间 → 编排 Studio → 审批详情”；`buildWorkspaceBreadcrumb` 由
  workspace layout 实际消费（`apps/web/src/layouts/workspace/index.tsx:126-127`）。
- 375px：`templates.css:17-24` 的 `display:flex; flex-wrap:wrap` 允许三个条目换行；
  `studio-nav.test.tsx:140-153` 在 jsdom 下验证审批待办链接可见且可聚焦。真实布局与 axe 归
  T16a（F-001）。

## 页面集成

- 详情路由 `detail.tsx:18-45` 从 `useParams` 读取 `workflowApprovalId`，支持测试注入覆盖；将
  动态段转为 `initialApprovalId`，并注入 `onLeaveDetail={() => navigate('/workflows/approvals')}`。
- 页面 `index.tsx` 以 `initialApprovalId` 初始化 `selectedId`，并在参数变化时同步
  （`:72,84-88`）；`handleBack`（`:95-103`）和 `handleDecided`（`:113-122`）在详情深链场景调用
  `onLeaveDetail`，待办路由场景保持原有“原地返回并刷新/聚焦”的分支。
- 行为符合“列表内打开详情不改变 URL、详情深链打开指定审批、退出详情回待办”的主从视图设计；
  详情深链回列表时页面组件重新挂载，待办列表会重新请求。T13 的详情/决定交互未改动。
- `detail.test.tsx:69-122` 覆盖“直接打开详情不先请求待办”“返回待办导航”“决定成功导航”三个
  场景；`approvals/index.test.tsx` 等 T13 定向测试在本任务提交上继续通过（EV-1091 全量 837 条，
  本评审定向复跑 37 条）。

## 测试与证据

- **EV-1091 审计**：`manual:false`、`commit:77c0b304`、`dirty:false`、`exit_code:0`；
  `output_sha256=178fb2e7e38702040a3d4915ec715feee4406b6de81e7027c4337b382432760b` 与日志实测
  一致，`output_bytes=19352` 一致；日志显示 `Test Files 93 passed`、`Tests 837 passed`，
  coverage 为 statements 87.28 / branches 79.75 / functions 85.54 / lines 87.16，均高于合同
  80/70 门槛；同日志包含 typecheck、lint、build 与 `check-prod-bundle` 通过记录。
- **本评审独立复跑**：
  - `pnpm --filter @riccon/web exec vitest run config/routes.test.ts
src/pages/workflow/templates/studio-nav.test.tsx
src/pages/workflow/approvals/detail.test.tsx src/pages/workflow/approvals/index.test.tsx`
    → 4 个测试文件、37 条用例全部通过；
  - `pnpm --filter @riccon/web typecheck` → `tsc --noEmit` 通过；
  - `pnpm --filter @riccon/web lint` → ESLint `--max-warnings=0` 与 Prettier 通过；
  - `pnpm --filter @riccon/web build` + `node apps/web/scripts/check-prod-bundle.mjs` → 构建与
    产物扫描通过；`p__workflow__approvals__detail.async.js` 存在，标记存在于
    `3183.async.js`；生成路由/preload 映射核对通过。
- **未独立重跑** web 全量 837 条（EV-1091 为同代码 clean 提交的全量证据，本评审做了定向复跑）。
- **范围**：`git diff --name-only cc014f79 77c0b304` 仅 7 个文件，全部在 write_scope；未改动
  backend/contracts/PRODUCT/AGENTS 或其他页面主体。6c4af42c 仅记录 EV-1091 并把任务移 review；
  后续 49ab2c3e/e00081bb/5ada9580 是并行 T08 的证据提交，不归 T13b。工作区未提交的 T08 证据
  文件与本任务无关，本评审未触碰。

## Findings

### F-001（P3，accepted）：真实 Umi 深链读取/刷新与 375px 三链接布局仅有 jsdom/静态 CSS 证据

详情组件测试通过注入 `workflowApprovalId` 绕过 `useParams`（测试替身返回空对象），单元层未
直接验证 Umi 动态段到 `params.workflowApprovalId` 的生产读取；375px 用例只做可见/可聚焦断言，
jsdom 不计算布局；axe 与真实浏览器刷新未执行。路由表与源码参数名已逐项核对一致，风险有界。
按任务计划，E2E-M06-04、@a11y、真实 375px 与深链刷新由 T16a/TASK-0237 承接；模块 gate 前需
补真实浏览器证据，不能把本任务单测视为深链刷新通过。

## 剩余风险

- 真实浏览器深链刷新、375px 无横向滚动、键盘全流程与 axe 归 T16a；在 T16a 完成前，AC-19 与
  AC-05 的 E2E 用户可见结论只能记为 pass (with residual)。
- T13 `REV-TASK-0234-001` 的 F-001 中“路由未接入、无 approvals chunk”部分已由本任务闭合；
  E2E-M06-04/@a11y 部分仍由 T16a 承接，模块 review 应保留对应残余，不能把 T13 F-001 视为全部
  消除。
- 本次未运行 M06 模块 gate、后端全量或真实浏览器 E2E；这些不属 T13b 任务级评审结论。
