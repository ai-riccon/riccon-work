---
review_id: REV-TASK-0215-002
module_id: M25
task_id: TASK-0215
reviewer:
  agent: riccon/verifier
  session: ses_df42ef55effexUMdIC3CQa67vA
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-TASK-0215-001
reviewed_commits:
  - 58f5a2beec913ca72707b0553e876fdd418ab3ea
  - 2a946ea7854a986bd3f50a7e7d3cbc0b5365c707
  - fa5129da9626b5b2b419243e83b3f7d0d498e19a
  - 377576c3fc238be08d4a6e70967dba35060fef3d
head_commit: 377576c3fc238be08d4a6e70967dba35060fef3d
baseline_commit: fd6a5f0d1d6fb24eed0fcfd4b04ea12e38fb07ba
dod:
  acceptance: true
  build: true
  tests: true
  e2e: true
  accessibility: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - migration
  - perf
dod_note: >-
  F-001/F-002/F-003 在上一轮已 fixed；本轮 58f5a2b..2a946ea 新增 `focus-trap.ts` 与
  `focus-trap.test.ts`，把可见性/隐藏祖先过滤与 Tab 目标计算独立出来，并在抽屉中改用生产 helper。
  独立浏览器复验：375px 从“页 1”Tab 回“关闭”，20 次 Tab/Shift+Tab 焦点始终在 Drawer；1280px
  分页大小选择器可见时从“页 1”到“页码”再到“关闭”，20 次循环不逃逸；Escape、关闭按钮、遮罩三条
  路径关闭后焦点均回到“条目”触发按钮。F-005 的 jsdom 假绿也由生产 helper 复用、隐藏分页大小选择器
  用例和新增 E2E 断言关闭。无 open finding，无新 P0/P1/P2。
reviewed_evidence:
  - EV-1003
  - EV-1004
  - EV-1005
  - EV-1006
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "danger 按钮对比度不足（列表删除 + 确认删除）已修复"
    summary: >-
      `index.tsx:120-127` 的本页 danger CSS 变量应用于行内删除 `:835` 与确认删除 `:1051`。
      真实浏览器计算样式：行内 `rgb(207,19,34)` on 白 = 5.57:1；确认白字 on `rgb(207,19,34)`
      = 5.57:1；axe 列表/表单/抽屉/删除弹窗均无 color-contrast serious。
    evidence: "58f5a2b; index.tsx:120-127,835,1051; /tmp/task0215-adhoc2.log; /tmp/task0215b-a11y.log"
  - id: F-002
    severity: P1
    status: fixed
    title: "条目抽屉横向滚动区域不可键盘访问已修复"
    summary: >-
      `connector-items-drawer.tsx` 渲染后给 `.ant-table-content` 补 `tabindex=0`、`role=region`、
      `aria-label=同步条目表格（可横向滚动）`。独立浏览器确认属性存在，ArrowRight 使 scrollLeft
      0→108/234；axe 无 `scrollable-region-focusable`。
    evidence: "58f5a2b; connector-items-drawer.tsx; /tmp/task0215-adhoc2.log; /tmp/task0215b-a11y.log"
  - id: F-003
    severity: P2
    status: fixed
    title: "375px 固定列遮挡行操作已修复"
    summary: >-
      名称列与操作列移除 fixed；375×812 独立浏览器无 `ant-table-cell-fix-left/right`，鼠标点击
      “条目 销售合同归档”成功打开抽屉；键盘路径同样通过。
    evidence: "58f5a2b; index.tsx; /tmp/task0215-adhoc2.log"
  - id: F-004
    severity: P2
    status: fixed
    title: "Drawer 焦点陷阱与分页边界已修复"
    summary: >-
      新增 `focus-trap.ts`：`isTabbable` 过滤 `tabIndex<0`、disabled/hidden、`checkVisibility`
      CSS 隐藏、祖先 hidden/inert/aria-hidden/内联 display:none/visibility:hidden；
      `resolveTabTrapTarget` 只在活动元素位于抽屉面板内时接管。独立 Chromium 复验：
      375px Tab 顺序为 关闭 → 状态筛选 → 表格滚动区 → 页 1 → 关闭，20 次 Tab / 20 次 Shift+Tab
      焦点始终在 Drawer；1280px 分页大小选择器为 51×22 可见控件，顺序为 页 1 → 页码 → 关闭，
      20 次循环同样不逃逸。Escape、关闭按钮、遮罩三条关闭路径焦点均回到“条目”触发按钮。
      额外验证：真实 Chromium 会把可见 0×0 input 纳入 Tab 顺序，因此 `isTabbable` 不按 rect 过滤
      零尺寸是正确行为；原 F-004 的 375px 隐藏输入由 `display:none` 的祖先过滤。
    evidence: "2a946ea; focus-trap.ts:18-126; connector-items-drawer.tsx:235-245,411; /tmp/task0215b-adhoc.log; 独立浏览器合成 0×0 input 验证"
  - id: F-005
    severity: P3
    status: fixed
    title: "组件测试复用生产 helper，不再用 jsdom 假绿覆盖 F-004"
    summary: >-
      删除了测试内重复的 focusable 选择器；`index.test.tsx` 改为导入生产 `queryFocusableElements`，
      并显式隐藏 `.ant-pagination-options` 后断言“页 1”是最后元素、Tab 回关闭、Shift+Tab 反向；
      `focus-trap.test.ts` 覆盖隐藏祖先、禁用、tabindex=-1、首尾环绕、中间不拦截、焦点在容器外不接管、
      无可聚焦元素时阻止默认行为。另新增 E2E 断言（m25-v1b `1e01aa5`）在 375px 从“页 1”Tab 回关闭、
      Shift+Tab 反向、12 次 Tab 不逃逸、Escape 归还“条目”，已在含 2a946ea 的 main 上单测通过。
    evidence: "2a946ea; focus-trap.test.ts; index.test.tsx:632-700; 1e01aa5; /tmp/task0215b-new-e2e.log"
evidence:
  - EV-1003
  - EV-1004
  - EV-1005
  - EV-1006
test_impact: >-
  独立复跑 main HEAD 377576c（含 58f5a2b + 2a946ea + fa5129d）：
  `pnpm --filter @riccon/web exec vitest run src/pages/admin/connectors --reporter=dot` exit 0，
  2 files/32 tests；`pnpm -w typecheck`、`pnpm -w lint` exit 0；
  `pnpm exec playwright test --config e2e/playwright.config.ts e2e/tests/m05b.spec.ts` exit 0，11/11；
  EV-1005 `pnpm -w e2e` exit 0，63/63（fork fa5129d，dirty=false）；
  `pnpm -w a11y` 独立复跑 exit 0，22/22（EV-1006 同结果）。
  额外临时 Playwright 脚本（不提交）：375px/1280px 各 20 次 Tab、20 次 Shift+Tab 焦点不出抽屉；
  Escape/关闭/遮罩归还触发按钮；`/tmp/task0215b-adhoc.log`。新增 E2E 断言在 main 上 1/1 通过
  （`/tmp/task0215b-new-e2e.log`），提交到 m25-v1b `1e01aa5`，Lead 合并后重跑合同 E2E 即可。
  未修改生产源码、任务状态或契约；只更新本评审与 m25-v1b E2E 断言。
created_at: "2026-11-24T14:42:00+08:00"
---

# REV-TASK-0215-002：TASK-0215（M05b B8b a11y/交互 fix）复审

## 结论

**通过（pass）**。F-001、F-002、F-003 保持 fixed；F-004 的分页焦点边界已由 `focus-trap.ts` 真正修复，375px 与 1280px 的 20 次 Tab/Shift+Tab 均不逃逸，Escape/关闭按钮/遮罩三条路径都归还“条目”触发按钮；F-005 的 jsdom 假绿已由生产 helper 复用、隐藏分页大小选择器用例与新增 E2E 断言关闭。无 open P0/P1/P2，未发现新阻断项。

上一轮 `REV-TASK-0215-001` 记录了 F-004/F-005 open；本轮为 superseding 复审。

## 范围与 diff

- `58f5a2b..2a946ea` 仅 4 个 web 文件：
  `focus-trap.ts`、`focus-trap.test.ts`、`connector-items-drawer.tsx`、`index.test.tsx`；
  无 e2e、后端、contracts、docs、deploy 改动。
- `git diff --check 58f5a2b..2a946ea` 通过；merge `fa5129d` 只含 `.riccon` 状态/证据。
- 新增 E2E 断言 commit：m25-v1b `1e01aa5`，仅改 `e2e/tests/m05b.spec.ts`。

## F-004 边界复核

| 视口   | 真实 Tab 顺序                                     | 复验结果                                |
| ------ | ------------------------------------------------- | --------------------------------------- |
| 375px  | 关闭 → 状态筛选 → 表格滚动区 → 页 1 → 关闭        | 20 次 Tab / 20 次 Shift+Tab 不出 Drawer |
| 1280px | 页 1 → 页码 → 关闭 → 状态筛选 → 表格滚动区 → 页 1 | 20 次 Tab / 20 次 Shift+Tab 不出 Drawer |

- `isTabbable`：`tabIndex<0`、disabled/hidden、`checkVisibility({checkVisibilityCSS:true})`、
  祖先 hidden/inert/aria-hidden/内联 display:none/visibility:hidden 均过滤；`resolveTabTrapTarget`
  只在活动元素位于面板内时接管，Select 浮层不被抢焦点。
- 零尺寸说明：真实 Chromium 会把可见 0×0 input 纳入 Tab 顺序（合成 0×0 输入可被 Tab 命中），
  因此“零尺寸一律过滤”反而会误判最后元素；原 375px “页码”输入是祖先 `display:none` 导致，
  已由 `checkVisibility` 过滤。
- 组件测试 `focus-trap.test.ts` 6 个用例 + `index.test.tsx` 隐藏分页大小选择器用例，均为实质断言；
  新增 E2E 断言覆盖真实浏览器集成，不再依赖 jsdom 的 focus 顺序。

## 验证与证据

- 单元/组件：32/32（2 files）；typecheck/lint exit 0。
- m05b E2E：11/11（main HEAD 377576c）。
- 全量 E2E：EV-1005 `fa5129d` dirty=false 63/63；a11y：EV-1006 同 commit 22/22；本轮独立复跑 a11y 22/22。
- 新增 E2E 断言在 main 上单测 1/1 通过；Lead 合并 `1e01aa5` 后重跑合同 E2E 即可。
- 未运行：V2b 的真实后端同步/安全/perf/迁移，仍归 TASK-0213。

## 关闭建议

- **TASK-0215**：可以 pass/关闭；F-004/F-005 已 fixed。
- **TASK-0212**：可以 unblock 并进入 done 流程；F-001/F-002 P1 已清除，F-004 P2 已 fixed，M05-AC-19 无残余。V2b 仍按 TASK-0213 完成真实栈安全/性能/迁移验证。
