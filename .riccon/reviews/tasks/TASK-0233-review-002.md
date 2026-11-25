---
review_id: REV-TASK-0233-002
module_id: M06
task_id: TASK-0233
previous_review: REV-TASK-0233-001
reviewer:
  agent: riccon/verifier
  session: ses_decd72014ffekaPhts56XPZWSX
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 4ec6dc91c00be2b9393c51c5d871c9fe42d54bb7
  - 3175adc907af2dfa28522be307717ee54a8d61ec
head_commit: 837e9b6e0f477cdfc07d087810c733aae157f5f5
code_commit: 4ec6dc91c00be2b9393c51c5d871c9fe42d54bb7
evidence_commit: 4ec6dc91c00be2b9393c51c5d871c9fe42d54bb7
baseline_commit: 07649b8612eddb0d6d4384aaa9ec291a39decd92
task_card_baseline_commit: 07649b8612eddb0d6d4384aaa9ec291a39decd92
task_card_end_commit: 4ec6dc91c00be2b9393c51c5d871c9fe42d54bb7
review_scope: >-
  仅复审 REV-TASK-0233-001 的 F-001/F-002：history.css 元信息 dt 颜色 #595959 与对比度 ≥4.5:1、
  history-a11y.test.ts 的样式/对比度断言是否有效；5 个按钮（运行流程、用此版本运行、提交运行、
  查看详情、提交重试）移除不匹配 aria-label 后，可访问名称是否包含可见文本、label-in-name 断言与
  按可见名称查询是否覆盖；独立重跑 history 定向、web 全量、typecheck/lint/build；复核
  EV-1115..EV-1118 与上一轮 EV-1101 的 commit/dirty/hash；write_scope 是否仍只含 history/**。
  P3 残余 F-003/F-004/F-005 仅承接、不重开；不评审 T12a 详情页、T12c 路由/导航（TASK-0246）、
  T12d 编辑页残余（TASK-0247）、T13 审批决定、T16a 真实浏览器 E2E/axe/375px/perf、T16b/T17，
  也不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  coverage: true
  review: true
  evidence: true
  scope: true
  security: true
  accessibility: true
dod_not_applicable:
  - e2e
  - performance
  - migration
  - docs
dod_note: >-
  acceptance 记 true 仅表示 T12b 任务级的 F-001/F-002 已关闭且历史/发布/重试取消功能未被回归：
  F-001 由 #595959（白底 7.00:1）与 history-a11y.test.ts 的三条断言关闭；F-002 由移除 5 个不匹配
  aria-label、按可见文本查询与组件级 label-in-name 断言关闭。build/tests/lint/typecheck 由
  EV-1115..EV-1118（clean 4ec6dc91，hash 一致）与本次独立复跑支撑：history 定向 6 文件 98 用例、
  web 全量 106 文件 983 用例、typecheck/lint/build 均 exit 0。coverage 沿用上一轮 clean EV-1105
  （history 87.04 行/82.15 分支），本轮修复只删 aria-label 与改 CSS 颜色、未新增生产逻辑，模块
  gate 仍需重跑完整合同 coverage。真实浏览器 axe/E2E/375px 与性能归 T16a；T12c 路由未接入不据此
  判 fail；P3 残余 F-003..F-006 均为 accepted，无 open P0/P1/P2。
findings:
  - id: F-001
    severity: P2
    status: fixed
    title: "REV-TASK-0233-001 F-001 已关闭：元信息 dt 改为 #595959，白底 7.00:1"
    summary: |-
      `apps/web/src/pages/workflow/history/history.css:192-197` 的 `.workflow-history-meta dt` 与
      `.workflow-history-version-meta dt` 已由 `#8c8c8c` 改为 `#595959`；按 sRGB 计算对白底
      对比度为 7.00:1（≥ WCAG 2.1 AA 4.5:1）。`history.css` 中已无 `#8c8c8c`；仅
      `history-a11y.test.ts` 的注释/断言字符串引用该旧值用于防回归。新增
      `history-a11y.test.ts:75-89` 读取真实 CSS 源码，断言声明块存在、颜色恰为 #595959、
      对比度 ≥4.5，并断言 CSS 不含 #8c8c8c；该文件在 6 文件/98 用例的 history 定向与 106 文件/
      983 用例的 web 全量中均通过。
    evidence: >-
      apps/web/src/pages/workflow/history/history.css:192-197；
      apps/web/src/pages/workflow/history/history-a11y.test.ts:75-89；
      独立复跑 history 6/98 与 web 全量 106/983 均通过；对比度 #595959/#ffffff=7.00:1。
  - id: F-002
    severity: P2
    status: fixed
    title: "REV-TASK-0233-001 F-002 已关闭：5 个按钮可访问名称等于可见文本"
    summary: |-
      `release-panel.tsx:780`（运行流程）、`:943`（用此版本运行）、`:1035`（提交运行）与
      `run-history.tsx:602`（查看详情）、`:685`（提交重试）均已移除不匹配的 aria-label，可访问
      名称回落到完整可见文本；测试改为按这些可见名称查询，并新增 `expectLabelInName` 断言
      （release-panel.test.tsx:219-233、run-history.test.tsx:179-193），要求可见文本非空且包含
      在可访问名称中。全库不再存在“用版本 v…/运行最近发布版本/确认运行流程/确认重试失败节点/
      查看运行详情：”的旧查询或旧标签。history/** 其余保留的 aria-label（重试、刷新运行历史、
      查询运行历史、取消运行：流程名、重试失败节点：流程名、确认发布版本、确认停用/启用流程、
      确认取消运行）均包含对应可见文本，未发现新的 label-in-name 不匹配。
    evidence: >-
      apps/web/src/pages/workflow/history/release-panel.tsx:780,943,1035；
      apps/web/src/pages/workflow/history/run-history.tsx:602,685；
      apps/web/src/pages/workflow/history/release-panel.test.tsx:219-233；
      apps/web/src/pages/workflow/history/run-history.test.tsx:179-193；
      独立复跑 history 6/98 与 web 全量 106/983 均通过；全库 grep 旧名称无残留。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T16a 前（可与 T12c 路由接入一并处理）"
    accepted_reason: >-
      沿用 REV-TASK-0233-001 F-003，不重开。流程所有者只获得列表元数据、不开放详情是服务端口径；
      列表“查看详情”进入 T12a 的 404 结果页有清晰文案、无数据泄露；AC-12 只要求所有者看到元数据。
      属于 UX 打磨，T16a 前可结合 T12c 按 ownerUserId 隐藏非发起人/非管理员的详情入口。
    title: "沿用上一轮：流程所有者看到的他人运行仍显示“查看详情”，点击必然 404"
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "M06 gate 前（T16a 版本夹具不覆盖时）"
    accepted_reason: >-
      沿用 REV-TASK-0233-001 F-004，不重开。超过 100 个版本的流程在 v1 演示与 T16a 夹具中不现实；
      页面已显示“共 N 个版本，当前仅显示最近 100 个”，服务端仍允许按 versionId 创建运行。属于边界
      容量限制，不影响 AC-03 演示路径；模块 gate 前若有真实超 100 版租户再补分页。
    title: "沿用上一轮：版本选择器只加载最近 100 个版本，更旧版本无法从界面运行"
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "M06 gate 前"
    accepted_reason: >-
      沿用 REV-TASK-0233-001 F-005，不重开。EV-1105 的 command 仍只是 `pnpm -w test -- --coverage`，
      note 的“Maven verify + jacoco”不实；但 T12b 没有后端改动，web 覆盖率数据有效。模块 review
      必须重新录制完整合同 test/coverage EV，不能把 EV-1105 当作模块 tests/coverage 证据。
    title: "沿用上一轮：EV-1105 note 声称包含 Maven verify/jacoco，实际只跑 web coverage"
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "M06 gate 前"
    accepted_reason: >-
      修复只改 history/** 且本次 verifier 已独立跑 web 全量 106 文件 983 用例、typecheck/lint/build
      全部通过，代码风险有界；EV-1115 仅覆盖 history 定向 98 用例，EV-1116..1118 只覆盖静态检查与
      构建，确实没有 post-fix 的 web 全量 EV。模块 gate 必须重录完整合同 test/coverage EV，不能把
      EV-1115 或上一轮 EV-1101（969 全量、3175adc9、修复前）当作修复后全量证据。
    title: "修复后只有 history 定向 EV-1115，未在 post-fix EV 中自证 web 全量"
    summary: |-
      EV-1115 的 command 是 `pnpm --filter @riccon/web exec vitest run src/pages/workflow/history`
      （6 文件/98 用例，commit 4ec6dc91，dirty=false）；EV-1116/1117/1118 分别是 typecheck/lint/
      build。上一轮 EV-1101 是 3175adc9 上的 104 文件/969 用例全量，早于 4ec6dc91。观察：修复提交
      没有对应的 post-fix web 全量 EV；本次独立复跑 `pnpm --filter @riccon/web test` 得到 106 文件/
      983 用例全过（其中 T12b history 6/98，其余为并行 T12a/T12d 等任务新增），补上了该验证，但
      独立复跑不是 EV。期望：模块 gate 用完整合同命令重新取证，或 builder 补一条 post-fix 全量 EV。
    evidence: >-
      .riccon/evidence/TASK-0233/EV-1115.json（6 文件/98 用例，4ec6dc91）；
      .riccon/evidence/TASK-0233/EV-1101.json（969 全量，3175adc9）；
      本次独立复跑 `pnpm --filter @riccon/web test`：106 文件/983 用例通过。
evidence:
  - EV-1101
  - EV-1115
  - EV-1116
  - EV-1117
  - EV-1118
reviewed_evidence:
  - EV-1101
  - EV-1115
  - EV-1116
  - EV-1117
  - EV-1118
created_at: "2026-11-25T23:26:00+08:00"
---

# REV-TASK-0233-002：任务 TASK-0233（M06 T12b）第二轮复审

## 结论

**pass**。REV-TASK-0233-001 的 2 个 P2 均已关闭：

- **F-001 对比度**：`history.css` 元信息 dt 改为 `#595959`，白底 7.00:1；无 `#8c8c8c` 残留；新增 `history-a11y.test.ts` 读取真实 CSS 断言颜色与对比度。
- **F-002 label-in-name**：5 个按钮移除不匹配 aria-label，可访问名称等于可见文本；测试按可见名称查询并断言 label-in-name；未发现新的不匹配。

独立复跑：history 定向 **6 文件/98 用例**、web 全量 **106 文件/983 用例**、typecheck/lint/build 均 exit 0。范围只含 `apps/web/src/pages/workflow/history/**`，无后端/contracts/PRODUCT/AGENTS/T12a/T12c/T12d 改动。无 open P0/P1/P2；4 个 P3 accepted（F-003..F-006）为上一轮残余与本轮证据完整性残余。真实浏览器 axe/E2E/375px 仍归 T16a。

## F-001 关闭验证

- `history.css:192-197` 的两个 dt 选择器使用 `color: #595959`，注释说明与 antd `colorTextSecondary` 一致；`grep -rn "#8c8c8c" apps/web/src/pages/workflow/history` 仅命中 `history-a11y.test.ts` 的注释和“不得包含”断言，CSS 本身无残留。
- 对比度按 WCAG 公式计算为 **7.00:1**（#595959 相对亮度约 0.103，白底 1.05/0.153），高于 4.5:1。
- `history-a11y.test.ts` 直接 `readFileSync` 读取 `history.css`，用选择器定位声明块，断言颜色恰为 `#595959`、对比度 ≥4.5、CSS 不含 `#8c8c8c`；这是有效的防回归静态断言（jsdom 不计算级联，真实 axe 仍由 T16a 负责）。该文件在定向与全量中都执行并通过。

## F-002 关闭验证

| 按钮                 | 位置                          | 可见文本     | aria-label | 可访问名称   |
| -------------------- | ----------------------------- | ------------ | ---------- | ------------ |
| 运行流程             | `release-panel.tsx:776-782`   | 运行流程     | 已移除     | 运行流程     |
| 用此版本运行         | `release-panel.tsx:933-945`   | 用此版本运行 | 已移除     | 用此版本运行 |
| 提交运行（运行弹窗） | `release-panel.tsx:1032-1040` | 提交运行     | 无         | 提交运行     |
| 查看详情             | `run-history.tsx:601-605`     | 查看详情     | 已移除     | 查看详情     |
| 提交重试（重试弹窗） | `run-history.tsx:683-691`     | 提交重试     | 无         | 提交重试     |

- 测试已按可见名称查询，并用 `expectLabelInName` 断言“可见文本非空且包含在可访问名称中”；旧的 `用版本 v1 运行流程`、`运行最近发布版本`、`确认运行流程`、`确认重试失败节点`、`查看运行详情：…` 查询在全库无残留。
- 其余保留 aria-label 的按钮（重试、刷新运行历史、查询运行历史、取消运行：流程名、重试失败节点：流程名、确认发布版本、确认停用/启用流程、确认取消运行）的可见文本均是可访问名称的子串，未发现新的 label-in-name 违规。
- 说明：移除 aria-label 后，多个“用此版本运行”与多个“查看详情”会共享同一可访问名称；这不违反 WCAG 2.5.3，也不是 axe serious，但屏幕阅读器按按钮列表浏览时缺少版本/运行上下文，属可用性残余（T16a 人工抽查可决定是否用 aria-describedby 补充，不作为 finding）。

## 证据复核

| EV      | 命令                                                                   | commit   | dirty | exit | 复核结果                                                                                 |
| ------- | ---------------------------------------------------------------------- | -------- | ----- | ---- | ---------------------------------------------------------------------------------------- |
| EV-1115 | `pnpm --filter @riccon/web exec vitest run src/pages/workflow/history` | 4ec6dc91 | false | 0    | 6 文件 98 用例通过（12.23s）；日志 sha256 与 EV 一致。                                   |
| EV-1116 | `pnpm --filter @riccon/web run typecheck`                              | 4ec6dc91 | false | 0    | 通过；本次独立复跑 exit 0。                                                              |
| EV-1117 | `pnpm --filter @riccon/web run lint`                                   | 4ec6dc91 | false | 0    | ESLint `--max-warnings=0` + Prettier 通过；本次独立复跑 exit 0。                         |
| EV-1118 | `pnpm --filter @riccon/web run build`                                  | 4ec6dc91 | false | 0    | 构建通过；history 仍未注册路由，chunk 由 T12c 接入。                                     |
| EV-1101 | `pnpm --filter @riccon/web test`                                       | 3175adc9 | false | 0    | 上一轮 104 文件 969 用例全量；早于修复提交，只能证明修复前基线，不能替代 post-fix 全量。 |

- 四个修复 EV 的日志 sha256 与 JSON 记录逐条一致，commit 均为 4ec6dc91，dirty=false；`git diff 4ec6dc91..HEAD -- apps contracts docs` 为空，修复提交后生产源码未再变化。
- 本次独立复跑：
  - `pnpm --filter @riccon/web exec vitest run src/pages/workflow/history` → 6 文件/98 用例通过（12.29s）。
  - `pnpm --filter @riccon/web test` → **106 文件/983 用例通过**（125.26s）。其中 T12b history 为 6/98；其余增量来自并行 T12a/T12d 等任务（不是在 T12b 写范围内）。
  - `pnpm --filter @riccon/web run typecheck` → exit 0；`run lint` → exit 0；`run build` → exit 0。
- F-006 记录 post-fix 全量 EV 缺失：EV-1115 只有 98 定向，EV-1101 是修复前 969；本次独立全量 983 通过但未记为 EV，模块 gate 必须用完整合同命令重新取证。

## 范围

- 修复提交 `4ec6dc91` 共 7 个文件，全部在 `apps/web/src/pages/workflow/history/**`：`history-a11y.test.ts`、`history.css`、`index.test.tsx`、`release-panel.test.tsx`、`release-panel.tsx`、`run-history.test.tsx`、`run-history.tsx`。任务卡 baseline/end `07649b86..4ec6dc91` 的 diff 与这 7 个文件一致。
- 未改后端、contracts、PRODUCT、AGENTS、T12a `run/**`、T12c 路由、T12d `edit/**`；`git diff 4ec6dc91..HEAD -- apps contracts docs` 为空。
- T12c（TASK-0246）未接入 `/workflows/runs` 与 `/workflows/runs/:workflowRunId`；本评审无法在真实路由中打开页面，按任务边界不判 fail，但 T16a 前必须完成。
- T12a 已由 REV-TASK-0232-003 pass、T12d 为独立任务，均不属于本次复审。

## 未验证

- 真实浏览器 axe、E2E-M06-02/05/08、375px 实际无横向滚动、完整键盘旅程与性能预算未运行（T16a）。本次没有用 jsdom 或静态 CSS 断言冒充 axe。
- 未重跑 Maven/后端集成测试；T12b 未改后端。
- 未重跑 coverage；沿用上一轮 clean EV-1105（history 87.04 行/82.15 分支），修复未新增生产逻辑，模块 gate 需重跑完整合同 coverage。

## 剩余风险

- 重复的“用此版本运行/查看详情”可访问名称在按钮列表中缺少上下文（非 WCAG 2.5.3 违规，axe 不报 serious）；T16a 人工屏幕阅读器抽查可决定是否补 aria-describedby。
- F-003（所有者详情 404）、F-004（100 版上限）、F-005（EV-1105 note/命令不一致）、F-006（post-fix 全量 EV 缺失）均为 accepted P3，按各自 due 跟踪；模块 gate 不得把 EV-1115/EV-1101 当作全量或完整合同证据。
- T12c 路由未接入前，T12b 页面在真实浏览器不可达；T16a 前必须完成 TASK-0246。

## 回执

- review_id：REV-TASK-0233-002
- verdict：pass
- findings：P0=0，P1=0，P2=0 open（F-001/F-002 fixed），P3=4 accepted（F-003/F-004/F-005 沿用不重开，F-006 post-fix 全量证据缺口）
- 关键结论：F-001 对比度与 F-002 label-in-name 均已修复并有有效静态断言；独立复跑 history 98 + web 全量 983 + typecheck/lint/build 全过；范围只含 history/**。
- 证据：EV-1115..EV-1118（clean 4ec6dc91，hash 一致）；EV-1101 为修复前 969 全量；独立 web 全量 106 文件/983 用例通过。
