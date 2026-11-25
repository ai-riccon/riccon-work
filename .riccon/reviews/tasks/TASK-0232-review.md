---
review_id: REV-TASK-0232-001
module_id: M06
task_id: TASK-0232
reviewer:
  agent: riccon/verifier
  session: ses_ded0f5072ffeEi7SjqHiK1HOWX
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - a1ede823627f27b5122f77334de6e9b77831b43d
  - a8f2e02c8ecd4604a0fa0abcd1ea5fc6a1101a28
  - a184ca2118b3dfe9a37e19a73b7efa3d41ee8496
head_commit: a184ca2118b3dfe9a37e19a73b7efa3d41ee8496
code_commit: a1ede823627f27b5122f77334de6e9b77831b43d
evidence_commit: a184ca2118b3dfe9a37e19a73b7efa3d41ee8496
baseline_commit: 7e6f50d50a0349baa8e030d95ea7906f05b2a1e4
task_card_baseline_commit: 7e6f50d5
task_card_end_commit: a1ede823
review_scope: >-
  M06 T12a（TASK-0232）前端运行详情页的独立任务评审：节点时间线（全部 attempt、状态/当前节点、
  输入/输出摘要、失败原因）、引用 sourceStatus 遮蔽（NO_ACCESS/DELETED 强制遮蔽、UPDATED 快照提示）、
  结果三态、审批记录/状态/counts/dataScopeSummary/脱敏参数、重试入口只展示、3 秒轮询与 updatedAt
  重绘判据、终态停止、失败保留旧数据、404/403 清空、手动刷新、加载/空/错误/403/404/503/缺 ID/
  无权限，以及 375px 结构、键盘/焦点和敏感信息不渲染。不评审 T12b 的发布/运行历史/重试/取消动作、
  T12c 的路由/导航接入、T13 审批决定、T16a 的真实浏览器 E2E/axe/375px/perf/安全套件、T16b/T17，
  也不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: false
  build: true
  tests: true
  lint: true
  typecheck: true
  coverage: true
  review: true
  evidence: true
  scope: true
  security: true
  accessibility: false
dod_not_applicable:
  - e2e
  - performance
  - migration
  - docs
dod_note: >-
  acceptance 记 false：T12a 的功能主路径、轮询、引用遮蔽、结果与审批展示已实现，34 个定向用例和独立
  复跑的 web 全量 871 个用例通过，build/tests/coverage/typecheck/lint 由 EV-1095..EV-1099 与独立复跑
  支撑；但 AC-19 的组件级可访问性有 3 个确定的 axe serious 违规：13px 元信息文本对比度 3.36:1、
  “展开全文”按钮 aria-label 不包含可见文本、可滚动 `<pre>` 无键盘焦点入口。三项均为 open P2，
  因此本任务不能判 pass，需修复后重新提交复审。真实浏览器 E2E-M06-03/08/09、@a11y/axe、375px 实际
  无横向滚动与完整键盘旅程仍归 T16a；`/workflows/runs/:workflowRunId` 路由注册/导航/生产 chunk 归
  T12c（TASK-0246），不据此单独判 fail。F-004..F-008 为 accepted P3；无 open P0/P1。T08 的契约/
  实现交错不归 T12a；T12b 的 `apps/web/src/pages/workflow/history/**` 并发改动不纳入本评审。
findings:
  - id: F-001
    severity: P2
    status: open
    owner: riccon/builder-b3
    due: "T12a 修复轮次（重新提交后复审）"
    title: "节点元信息 13px 文本对比度 3.36:1，低于 WCAG 2.1 AA"
    summary: >-
      `apps/web/src/pages/workflow/run/run.css:138-145` 的 `.workflow-run-node-meta` 将开始/结束/耗时
      元信息设为 `color: rgb(0 0 0 / 45%)`，字号 13px、白底。按 sRGB 合成得到前景约 `#8c8c8c`，
      与白底对比度约 3.36:1，低于 WCAG 2.1 AA 普通文本 4.5:1；axe 的 `color-contrast` 影响级别为
      serious。合同 `constraints.accessibility` 与 AC-19 要求 axe 无 serious/critical，
      `e2e/tests/a11y.ts:14-23` 正是按 serious/critical 过滤。当前 jsdom 测试不计算 CSS 对比度，
      因此 34 个用例全绿不能证明该项。期望改用 antd 的 `colorTextSecondary`（antd 6 为 0.65 /
      约 `#595959`）或更深的专用色，并在 T16a 真实浏览器 axe 中断言 0 serious/critical。
    evidence: >-
      apps/web/src/pages/workflow/run/run.css:138-145（:143 `rgb(0 0 0 / 45%)`）；e2e/tests/a11y.ts:14-23；
      对比度计算：rgba(0,0,0,0.45) 合成到白底为 #8c8c8c，3.36:1；run-detail.test.tsx:354-370 只证明
      结构与焦点，不计算 CSS 对比度。
  - id: F-002
    severity: P2
    status: open
    owner: riccon/builder-b3
    due: "T12a 修复轮次（重新提交后复审）"
    title: "按钮 aria-label 不含完整可见文本，label-in-name 失败（展开全文/引用定位）"
    summary: >-
      `apps/web/src/pages/workflow/run/run-detail.tsx:576-583` 的按钮 `aria-label="展开完整结果"`，
      可见文本却是“展开全文”；可访问名称为“展开完整结果”，可见文本“展开全文”不是其连续子串。
      同一页 `:389-407` 的引用按钮 `aria-label` 只包含 `${citationListLabel}，${availability}`，
      但按钮内还可见定位文本如“第 3 页”（`:402-406`），定位文本不在可访问名称中。两处都可能触发
      axe `label-content-name-mismatch`（wcag21a，impact=serious）。当前测试
      `run-detail.test.tsx:276-280` 与 `:361-363` 分别用 `getByRole('button', { name: '展开完整结果' })`
      和 `[1] 新能源行业报告.pdf，可访问` 查询，反而固化了 aria-label 与可见文本不一致。期望让
      aria-label 包含完整可见文本，或移除 aria-label 让可见文本成为名称，并补 label-in-name 断言。
    evidence: >-
      apps/web/src/pages/workflow/run/run-detail.tsx:389-407,576-583；
      apps/web/src/pages/workflow/run/run-detail.test.tsx:276-280,361-363；
      e2e/tests/a11y.ts:14-23；axe-core 4.14.1 `label-content-name-mismatch` tags=wcag21a、impact=serious。
  - id: F-003
    severity: P2
    status: open
    owner: riccon/builder-b3
    due: "T12a 修复轮次（重新提交后复审）"
    title: "可滚动输入/输出摘要与结果 pre 不可键盘聚焦"
    summary: >-
      `apps/web/src/pages/workflow/run/run.css:167-175` 的 `.workflow-run-summary-block > pre`
      和 `:227-239` 的 `.workflow-run-result-text` 都是 `max-height` + `overflow: auto` 的滚动区域，
      但 `run-detail.tsx:329-341,566-568` 只渲染原生 `<pre>`，没有 `tabIndex`/`role`/`aria-label`，
      也没有可聚焦后代。结果预览上限 2000 字符、13px 字号，在 375px/常规宽度下会超过 240/360px
      高度，真实浏览器中 axe `scrollable-region-focusable`（wcag2a，impact=serious）会命中，
      键盘用户无法聚焦并滚动查看完整内容。期望给两个 `<pre>` 加 `tabIndex={0}`、`role="region"`
      和中文 `aria-label`（参考 TASK-0215 对表格滚动区的处理），或在 T12a 改用可访问的折叠组件。
    evidence: >-
      apps/web/src/pages/workflow/run/run.css:167-175,227-239；
      apps/web/src/pages/workflow/run/run-detail.tsx:329-341,566-568；
      e2e/tests/a11y.ts:14-23；axe-core 4.14.1 `scrollable-region-focusable` tags=wcag2a、impact=serious；
      run-detail.test.tsx:256-281 只断言文本/按钮，不做真实布局滚动与焦点。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T12b/T16a 前"
    title: "引用抽屉打开后，轮询把引用更新为 NO_ACCESS/DELETED 时抽屉仍显示旧快照"
    summary: >-
      `apps/web/src/pages/workflow/run/run-detail.tsx:117` 用 `activeCitation` 保存点击时的
      `CitationView`，`:393` 点击写入，`:424-428` 直接传给抽屉；`index.tsx:239-242` 轮询/手动刷新
      替换 `detail` 时 `WorkflowRunDetailView` 不卸载，`activeCitation` 不会被重新对应到
      `detail.citations`。复现：打开一条 AVAILABLE 引用抽屉 → 服务端把该文档 ACL 撤销或软删除 →
      下一次 3 秒轮询/手动刷新后列表已显示“该引用不可访问/源文档已删除”，但抽屉仍显示旧标题、片段、
      定位。期望按 `ordinal`/`knowledgeDocumentId` 将抽屉内容同步到最新 `sourceStatus`，或在状态
      变为 NO_ACCESS/DELETED 时关闭抽屉。
    accepted_reason: >-
      抽屉打开时内容仍是 AVAILABLE，用户已经获得并看到该内容；列表在更新后立即遮蔽，重新打开抽屉
      也走遮蔽分支，因此不产生新的未授权数据读取。残余是撤销后的旧快照仍留在已打开的抽屉里，
      风险有界；T16a 的 ACL/引用旅程应覆盖该时序，修复可在 T12b/T12c 或 T16a 前完成。
    evidence: >-
      apps/web/src/pages/workflow/run/run-detail.tsx:117,393,424-428；
      apps/web/src/pages/workflow/run/index.tsx:239-242；
      apps/web/src/pages/knowledge/components/citation-detail-drawer.tsx:73-80,126-144；
      run-detail.test.tsx:168-214 只测试首次渲染的遮蔽，未测试打开后状态变化。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T12b/T12c 前"
    title: "手动刷新与轮询并发时，刷新按钮可能永久 loading"
    summary: >-
      `apps/web/src/pages/workflow/run/index.tsx:134-173` 中手动刷新 `load(true)` 先递增
      `requestSeq` 并 `setRefreshing(true)`，finally 只在 `requestSeq.current === seq` 时
      `setRefreshing(false)`；`:225-263` 的轮询也会递增同一 `requestSeq`。若轮询在刷新期间启动
      并先返回，轮询响应会让刷新的 seq 过期，刷新的 finally 不再清理 `refreshing`，而轮询成功路径
      从不调用 `setRefreshing(false)`。复现：运行活跃时点击“刷新”，3 秒内轮询触发且先返回；
      “刷新”按钮保持 loading（antd loading 同时禁用点击），直到重新打开页面。期望刷新 loader
      与轮询序号解耦，或在刷新请求结束（无论是否过期）时清理自己的 loading 状态。
    accepted_reason: >-
      自动轮询仍继续，数据不会丢失；只影响手动刷新入口的可用性，重新进入页面即可恢复。修复只需
      独立 refresh token 或在 finally 中按刷新生命周期清理；T12b/T12c 可顺手修复，并在测试中
      覆盖“刷新被轮询接管”的交错。
    evidence: >-
      apps/web/src/pages/workflow/run/index.tsx:134-173,225-263；index.test.tsx 未覆盖刷新与轮询
      并发；现有轮询测试只覆盖单请求循环。
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T12b/M06 gate 前"
    title: "仅含 CANCELLED 审批的运行，摘要显示“无审批节点”而审批记录仍列出已取消项"
    summary: >-
      `apps/web/src/pages/workflow/run/format.ts:338-353` 的 `approvalCountsText` 只看
      `approvalStatus` 与 pending/approved/rejected 计数；`WorkflowApprovalCounts` 明确
      `CANCELLED` 不计入任何计数（apps/api/src/main/java/com/riccon/api/workflow/history/domain/WorkflowApprovalCounts.java:6-10），
      `WorkflowHistoryService.approvalStatus` 在计数为 0 时返回 null
      （apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryService.java:348-359），
      但详情仍返回 CANCELLED 审批（同文件 :224）。因此运行取消后若有审批被取消，摘要区会显示
      “无审批节点”，下方审批记录却列出“已取消”条目。期望用 `detail.approvals.length` 判断是否存在
      审批节点，或补 cancelled 计数。
    accepted_reason: >-
      只影响摘要文案，不丢审批记录或数据；取消审批仍可在审批记录区看到。修复可在 T12b 运行历史/
      详情复用逻辑时一并处理，M06 gate 前补一个 CANCELLED 审批用例即可。
    evidence: >-
      apps/web/src/pages/workflow/run/format.ts:338-353；apps/web/src/pages/workflow/run/run-detail.tsx:433-520；
      apps/api/src/main/java/com/riccon/api/workflow/history/domain/WorkflowApprovalCounts.java:6-10；
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryService.java:224,348-359；
      format.test.ts:85-96 未覆盖 CANCELLED-only。
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T12b/M06 gate 前"
    title: "“仅 updatedAt 变化才重绘”测试没有断言渲染次数"
    summary: >-
      `apps/web/src/pages/workflow/run/index.test.tsx:142-194` 标题声称验证 updatedAt 变化才重绘，
      但实际只断言 `getRunDetail` 调用次数、最终结果文本与轮询停止；没有 render spy、DOM 快照或
      对相同 updatedAt 响应后 DOM 不变的断言。代码 `index.tsx:238-242` 确实比较
      `next.updatedAt !== previous.updatedAt`，当前行为正确；但如果后续回归为无条件
      `setDetail(next)`，该测试仍会全绿。
    accepted_reason: >-
      这是测试假绿风险，不是当前产品缺陷；代码实现符合 ADR-0024 §7.4。T12b 可加 render counter/
      相同 updatedAt 的 DOM 不变断言，不影响本任务功能验收。
    evidence: >-
      apps/web/src/pages/workflow/run/index.test.tsx:142-194；
      apps/web/src/pages/workflow/run/index.tsx:238-242。
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T16a 前（TASK-0246）"
    title: "T12a 页面未注册路由，生产构建暂不包含 run chunk"
    summary: >-
      `apps/web/config/routes.ts` 只有 `/workflows/list`、`/workflows/:workflowId/edit`、
      `/workflows/templates*` 与 `/workflows/approvals*`，没有 `/workflows/runs/:workflowRunId`；
      `run/detail.tsx` 的路由包装组件已经实现，但当前生产构建不会包含运行详情 chunk
      （EV-1098 日志无 `p__workflow__run` 产物），深链/运行历史入口也不可达。T12c
      `TASK-0246` 已创建（status=planned，write_scope 含 `apps/web/config/routes.ts` 与
      `apps/web/src/pages/workflow/run/**`），负责接入路由、导航、面包屑和 chunk。
    accepted_reason: >-
      TASK-0232 的 write_scope 明确只有 `apps/web/src/pages/workflow/run/**`，路由文件不可改；
      路由缺口不是 T12a 的功能缺陷，但它是 T16a 真实浏览器旅程的前置条件，TASK-0246 必须在 T16a
      前完成并绑定生产构建 chunk 证据。本评审不据此判 T12a fail。
    evidence: >-
      apps/web/config/routes.ts:54-72（无 runs 路由）；EV-1098 构建日志无 run chunk；
      apps/web/src/pages/workflow/run/detail.tsx:15-37；.riccon/tasks/TASK-0246.json（planned）。
evidence:
  - EV-1095
  - EV-1096
  - EV-1097
  - EV-1098
  - EV-1099
reviewed_evidence:
  - EV-1095
  - EV-1096
  - EV-1097
  - EV-1098
  - EV-1099
created_at: "2026-11-25T21:55:00+08:00"
---

# REV-TASK-0232-001：TASK-0232（M06 T12a 运行详情）独立评审

## 结论

**fail**。T12a 的功能主路径、3 秒轮询、引用遮蔽、结果三态、审批记录与脱敏展示已实现，34 个
定向用例和独立复跑的 web 全量 871 个用例通过，typecheck/lint 通过，EV-1095..EV-1099 的
commit/dirty/hash/bytes 可核对；但 AC-19 的组件级可访问性存在 3 个确定的 axe serious 违规
（F-001 对比度、F-002 Label in Name、F-003 可滚动区域不可聚焦）。三者都是 T12a 源码/CSS 中的
可直接修复问题，必须在 T12a 修复轮次解决并重新评审；真实浏览器 axe/E2E/375px 仍由 T16a 做最终
门禁，但 T12a 不应把已知 serious 违规带入 T16a。

## AC 对账

| AC        | 判定                 | 依据与残余                                                                                                                                                                                                                                                                                                                                                                                         |
| --------- | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M06-AC-04 | pass (with residual) | 节点时间线渲染全部 attempt、状态文字+图标、当前节点（最后一条尝试）、开始/结束/耗时、输入/输出摘要与失败原因；`index.tsx`/`run-detail.tsx`/`format.ts` 与 5 个定向文件测试支撑。真实浏览器按节点顺序/刷新一致性由 `E2E-M06-03`（T16a）验证，本任务未执行。                                                                                                                                         |
| M06-AC-13 | pass (with residual) | 引用 `NO_ACCESS`/`DELETED` 强制遮蔽标题/片段/定位，`UPDATED` 显示快照提示；结果 `NONE`/`SAVED`/`OVER_LIMIT` 三态、审批状态/counts/dataScopeSummary/脱敏参数、失败原因与重试入口只展示均有实现。真实浏览器 `E2E-M06-08`、ACL 撤销时序与“结果再次打开”由 T16a 验证；F-004 的已打开抽屉旧快照与 F-006 的 CANCELLED-only 摘要为本任务残余。                                                            |
| M06-AC-19 | fail                 | loading/空/错误/403/404/503/缺 ID/无权限、错误态重试/返回、禁用态说明、标题聚焦、引用按钮可聚焦、375px 结构和状态文字+图标已实现；但 F-001（13px 元信息对比度 3.36:1）、F-002（“展开全文”aria-label 不含可见文本）、F-003（可滚动 pre 不可键盘聚焦）都是 axe serious 级别的确定违规，AC-19 的 WCAG 2.1 AA 组件级基线未通过。真实浏览器 axe/375px/完整键盘旅程仍归 T16a；修复后需重新跑 T16a 门禁。 |

## 验证了什么

- **源码与范围**：逐行审阅 `apps/web/src/pages/workflow/run/**` 11 个文件；`git diff 7e6f50d5..a1ede823`
  只新增 run/** 与 `.riccon` 任务/状态，未改后端、`contracts/**`、PRODUCT、AGENTS、迁移或 T12b。
- **契约/设计**：对照 `contracts/openapi.yaml` 1.6.0-m06 的 `WorkflowRunDetail`、`WorkflowNodeRun`、
  `WorkflowApproval`、`WorkflowResult`、`Citation`，字段/枚举/端点路径与前端类型一致；GET 不发送 CSRF，
  runId 做 `encodeURIComponent`；404/403/503 不吞状态码。
- **轮询**：默认 3000ms；仅 `updatedAt` 变化时 `setDetail`；终态 `isActiveRunStatus=false` 后不再排定；
  轮询 500 保留旧数据并提示；轮询 404/403 清空详情并切结果页；手动刷新与初始加载共用请求序号丢弃过期响应。
- **安全基线**：只渲染服务端 `inputSummary`/`outputSummary`、聚合引用与最终结果；不渲染 prompt、完整
  工具参数、凭据、`planSha256` 或无 ACL 正文；`paramsPreview` 仅在服务端返回时显示；引用按
  `sourceStatus` 二次遮蔽；React 文本节点转义，无 `dangerouslySetInnerHTML`/`innerHTML`/console 日志。
- **测试**：本会话独立执行 `pnpm --filter @riccon/web exec vitest run src/pages/workflow/run`
  → 5 files / 34 tests 全通过（6.07s）；独立执行 `pnpm --filter @riccon/web test` → 98 files / 871 tests
  全通过（97.62s）；独立执行 `pnpm --filter @riccon/web run typecheck` 与 `run lint` 均 exit 0。
- **证据复核**：EV-1095 883B/hash `699d6c…`、EV-1096 734B/`8b98c8…`、EV-1097 455B/`6681da…`、
  EV-1098 4706B/`6bc8d5…`、EV-1099 14107B/`5da83a…`，均 `dirty=false`、`exit=0`；EV-1095 日志
  871 tests、98 files；EV-1099 为 `pnpm -w test -- --coverage`，Statements 87.47% / Branches 80.13% /
  Functions 85.72% / Lines 87.36%，`run/**` Lines 92.83%，高于合同 line 80 / branch 70；
  其中 `run-detail.tsx` Lines 83.33%，未覆盖行含 393-427（抽屉打开/同步路径）与 587（收起结果），
  与 F-004/F-007 的测试缺口一致。EV-1099 记录 commit `a8f2e02c`，
  `git diff a1ede823..a8f2e02c -- apps/web` 为空；EV-1099 之后的 `a184ca21` 只含 `.riccon` 证据文件。
- **反向核对**：EV-1098 生产构建日志没有 `p__workflow__run` chunk，与路由未注册一致；已建 T12c
  `TASK-0246`，未把该缺口算作 T12a fail。T08 的 `/workflows/{workflowId}/runs` 契约/实现交错不在本
  评审范围；T12b 的未跟踪 `apps/web/src/pages/workflow/history/history-api.ts` 在 21:48 才出现，
  未纳入 21:44-21:46 的 34/871 复跑结果。

## 未验证

- **真实浏览器 E2E**：`E2E-M06-03`、`E2E-M06-08`、`E2E-M06-09`、`@a11y/axe`、375px 实际无横向滚动、
  完整键盘旅程、Drawer 焦点返回、真实 API/ACL 时序；归 T16a。本评审不把 jsdom 组件测试当作这些
  旅程的替代证据；F-001..F-003 是组件级可访问性缺陷，T16a 的 axe 仍须做最终复验。任务卡
  acceptance 中的 `E2E-M06-03/08` 证据尚未在本任务绑定，须由 T16a 补齐。
- **路由/导航/生产 chunk**：`/workflows/runs/:workflowRunId` 注册、运行历史入口、面包屑、Studio
  子导航选中态、生产构建 chunk；归 T12c（TASK-0246）。
- **全量模块 gate**：合同 build/test/coverage/E2E/a11y/perf/security、T16b 回滚、T17 模块评审；
  归 T17/T16a/T16b。
- **T12b/T13/T16a/T16b 行为**：发布/版本/运行历史/重试/取消动作、审批决定、真实越权套件与
  50 并发 perf；不在本任务范围。
- **构建/覆盖率独立重跑**：本评审独立复跑了 34 定向、871 web 全量、typecheck、lint；未独立重跑
  `pnpm --filter @riccon/web run build` 和 coverage 全量，这两项以 EV-1098/EV-1099 的 clean 证据与
  hash/commit 复核支撑。

## Findings

- **F-001（P2，open）**：`.workflow-run-node-meta` 13px 文本 `rgb(0 0 0 / 45%)` 白底对比度 3.36:1，
  低于 WCAG AA 4.5:1；axe serious。owner `riccon/builder-b3`，due T12a 修复轮次；改为 antd secondary/
  更深色并在 axe 复验。
- **F-002（P2，open）**：“展开全文”按钮 `aria-label="展开完整结果"` 不含可见文本“展开全文”，
  WCAG 2.5.3 Label in Name 失败；axe `label-content-name-mismatch` serious。owner `riccon/builder-b3`，
  due T12a 修复轮次；统一可见文本与 aria-label。
- **F-003（P2，open）**：输入/输出摘要与结果 `<pre>` 使用 `overflow:auto` 但没有 tabIndex/role/
  aria-label，键盘用户无法聚焦滚动；axe `scrollable-region-focusable` serious（375px/长文本时）。
  owner `riccon/builder-b3`，due T12a 修复轮次；补 `tabIndex={0}` + `role="region"` + 中文标签。
- **F-004（P3，accepted）**：引用抽屉打开后，轮询把引用更新为 NO_ACCESS/DELETED 时 `activeCitation`
  仍持有旧 AVAILABLE 视图，抽屉继续显示旧标题/片段/定位；列表已遮蔽。owner `riccon/builder-b3`，
  due T12b/T16a；T16a ACL 时序应覆盖。
- **F-005（P3，accepted）**：手动刷新与 3 秒轮询并发时，轮询先返回会让刷新 seq 过期，刷新按钮
  保持 loading（并禁用点击）；自动轮询仍继续。owner `riccon/builder-b3`，due T12b/T12c。
- **F-006（P3，accepted）**：仅含 CANCELLED 审批的运行，摘要显示“无审批节点”，审批记录区仍列出
  已取消项；契约 counts 不含 CANCELLED，approvalStatus 为 null。owner `riccon/builder-b3`，
  due T12b/M06 gate。
- **F-007（P3，accepted）**：“仅 updatedAt 变化重绘”测试只断言 API 调用次数，没有 render spy；
  代码行为正确，测试存在假绿风险。owner `riccon/builder-b3`，due T12b/M06 gate。
- **F-008（P3，accepted）**：路由未注册，生产构建无 run chunk，深链暂不可达；T12c TASK-0246 已建。
  owner `riccon/builder-b3`，due T16a 前。

## 剩余风险

- **F-001..F-003 a11y**：三项都是 T12a 可直接修复的 serious 违规；修复前 AC-19 不能判 pass，
  T16a 的 axe 也会失败。修复后需重新提交并复审，不能在本任务内接受为残余。
- **F-004/F-005**：分别涉及撤销后的旧抽屉快照与刷新按钮卡住，均为低概率时序问题；T16a/T12b 应
  补真实浏览器时序用例。
- **F-006/F-007**：摘要文案与测试断言缺口，不影响主路径，但 T12b 复用时需统一。
- **F-008 路由**：T12a 页面目前不可从生产入口到达；T16a 前必须由 TASK-0246 完成并绑定生产 chunk。
- **T08 交错**：T08 的契约路径/历史列表修复不在本任务写范围，本评审未替 T08 的 accepted finding
  重新定性；T12a 只消费 `GET /api/v1/workflow-runs/{id}` 并已验证其错误与权限语义。
- **T12b 并发**：评审期间 builder-b3 的 `history/**` 未跟踪文件写入不影响本次 871/34 结果，但后续
  任务仍应在独立提交上取证，避免共享工作树漂移。

## 证据

- EV-1095：`pnpm --filter @riccon/web test`，commit a1ede823，dirty=false，98 files / 871 tests。
- EV-1096：`pnpm --filter @riccon/web run typecheck`，commit a1ede823，dirty=false。
- EV-1097：`pnpm --filter @riccon/web run lint`，commit a1ede823，dirty=false。
- EV-1098：`pnpm --filter @riccon/web run build`，commit a1ede823，dirty=false；无 run chunk（路由未注册）。
- EV-1099：`pnpm -w test -- --coverage`，commit a8f2e02c，dirty=false；Statements 87.47% / Branches
  80.13% / Functions 85.72% / Lines 87.36%；`run/**` Lines 92.83%。

## 回执

```text
任务：TASK-0232（M06 T12a 运行详情时间线/摘要/引用/结果/审批记录）
review_id：REV-TASK-0232-001
verdict：fail
findings：P0=0；P1=0；P2=3 open（F-001 对比度、F-002 Label in Name、F-003 可滚动区域不可聚焦）；
  P3=5 accepted（F-004 抽屉旧快照、F-005 刷新 loading 竞态、F-006 CANCELLED-only 摘要、
  F-007 updatedAt 测试假绿、F-008 路由/T12c）
关键结论：功能主路径、轮询、遮蔽、结果/审批展示完整，34 定向 + 871 web 全量 + typecheck/lint 通过，
  EV-1095..1099 clean/hash 一致；但 AC-19 有 3 个确定的 axe serious 组件级违规，必须修复后重新评审。
  真实 E2E/axe/375px 归 T16a，路由/导航/chunk 归 T12c；无 open P0/P1。
证据：EV-1095、EV-1096、EV-1097、EV-1098、EV-1099
遗留：F-001..F-003 修复并复审；F-004..F-008 由 T12b/T12c/T16a/M06 gate 跟踪。
```
