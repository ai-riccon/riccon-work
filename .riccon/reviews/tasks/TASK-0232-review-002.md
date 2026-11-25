---
review_id: REV-TASK-0232-002
module_id: M06
task_id: TASK-0232
previous_review: REV-TASK-0232-001
reviewer:
  agent: riccon/verifier
  session: ses_ded0f5072ffeEi7SjqHiK1HOWX
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - fc5adcabb87f5e3ce2e1ef5c29e390bdc71cae3c
  - 3175adc907af2dfa28522be307717ee54a8d61ec
head_commit: 3175adc907af2dfa28522be307717ee54a8d61ec
code_commit: fc5adcabb87f5e3ce2e1ef5c29e390bdc71cae3c
evidence_commit: 3175adc907af2dfa28522be307717ee54a8d61ec
baseline_commit: 3667ee80
task_card_baseline_commit: 3667ee80
task_card_end_commit: fc5adcab
review_scope: >-
  仅复审 REV-TASK-0232-001 的 F-001..F-003：F-001 节点元信息对比度（#595959/≥4.5:1）及
  run-a11y 防回归；F-002 label-in-name（展开全文/引用按钮可访问名称包含可见文本，测试按可见名称
  查询）；F-003 可滚动输入/输出摘要/结果 pre 的 tabIndex/role/aria-label 与键盘聚焦测试。
  复核 fc5adcab/3175adc9、EV-1100 commit/dirty/hash 与 write_scope；独立复跑 run 定向、
  web 全量、typecheck/lint/build。不重新评审 REV-TASK-0232-001 的 F-004..F-008 accepted P3；
  不评审 T12b 已合入的运行历史/发布/重试取消、T12c 路由接入、T13 审批决定、T16a 的真实浏览器
  E2E/axe/375px、T16b/T17，也不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: false
  build: true
  tests: true
  lint: true
  typecheck: true
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
  acceptance 记 false：F-001（对比度 #595959，约 7:1）、F-002（移除不匹配 aria-label，按可见文本
  查询）和 F-003 的三个指定 pre（输入/输出摘要、结果）均已修复并由独立复跑证明；但 F-003 的根因
  `.workflow-run-summary-block > pre` 仍有一个未修复实例：审批脱敏参数 pre
  （run-detail.tsx:510-514）同样 max-height + overflow:auto，却没有 tabIndex/role/aria-label，
  真实浏览器中 axe `scrollable-region-focusable`（impact=serious）在展开该 details 后仍会命中。
  因此 AC-19 仍有一个 open P2，需修复后重新评审。EV-1100 clean、commit fc5adcab、dirty=false、
  hash/bytes 一致，但其 command/日志只覆盖 36 个 run 定向测试；969 web/typecheck/lint/build 由本次
  独立复跑通过，未在 EV-1100 自证，记为 P3 accepted 证据完整性残余。真实 E2E/axe/375px 归 T16a；
  REV-TASK-0232-001 的 F-004..F-008 accepted P3 保持，不重开。T12b 交错提交 3667ee80 不属于
  T12a 修复范围，本次仅审 3667ee80..fc5adcab 的 run/** 4 个文件。
findings:
  - id: F-001
    severity: P2
    status: open
    owner: riccon/builder-b1
    due: "T12a 修复轮次（重新提交后复审）"
    title: "F-003 未完全关闭：审批脱敏参数 pre 仍是不可键盘聚焦的滚动区"
    summary: >-
      `apps/web/src/pages/workflow/run/run-detail.tsx:510-514` 的审批记录仍渲染
      `<details className="workflow-run-summary-block"><pre>{formatApprovalParams(...)}</pre></details>`。
      该 pre 命中 `apps/web/src/pages/workflow/run/run.css:168-176` 的
      `.workflow-run-summary-block > pre { max-height: 240px; overflow: auto; }`，但未加
      `tabIndex={0}`、`role="region"` 或 `aria-label`；审批参数最多 16 个键、单值最多 200 字符，
      展开 details 后内容可超过 240px，键盘用户无法聚焦并滚动查看。F-003 修复只覆盖了输入/输出摘要
      和结果 pre，根因 CSS 仍适用于审批参数；真实浏览器 axe `scrollable-region-focusable`
      （tags=wcag2a、impact=serious）会命中。期望按同一方案给审批参数 pre 补
      `tabIndex={0}` + `role="region"` + 中文 `aria-label`，并在聚焦测试中覆盖。
    evidence: >-
      apps/web/src/pages/workflow/run/run-detail.tsx:510-514（审批参数 pre）；
      apps/web/src/pages/workflow/run/run.css:168-176（滚动样式）；
      apps/web/src/pages/workflow/run/run-detail.test.tsx:372-397（只覆盖输入/输出/结果三个 region）；
      axe-core 4.14.1 `scrollable-region-focusable` tags=wcag2a、impact=serious；
      e2e/tests/a11y.ts:14-23（serious/critical 过滤）。
      独立 Chromium 探针（Playwright 1.65 + axe-core 4.14.1，375×812）：等价的
      `pre { max-height:240px; overflow:auto }` 且无 tabIndex 时上报 scrollable-region-focusable
      serious；补 `tabIndex=0 + role=region + aria-label` 后该规则通过。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "M06 gate 前"
    title: "EV-1100 只自证 36 个 run 定向测试，969 web/typecheck/lint/build 未在同一 EV 日志中"
    summary: >-
      `.riccon/evidence/TASK-0232/EV-1100.json` 的 command 为
      `pnpm --filter @riccon/web exec vitest run src/pages/workflow/run`，日志只显示 6 files / 36 tests；
      commit 信息和任务交接声称的 969 web tests、typecheck、lint、build 不在 EV-1100 的命令/输出中。
      本次评审独立复跑 `pnpm --filter @riccon/web test`（104 files / 969 tests）、typecheck、lint、
      build 均 exit 0，证明当前提交行为通过；但 EV-1100 本身不能自证这些全量结果。
    accepted_reason: >-
      clean EV 只能自证 36 个定向测试，独立复跑已证明 969 web/typecheck/lint/build 通过，故当前没有
      功能或证据失效风险；问题是证据完整性，不是代码缺陷。M06 gate 前应补一条覆盖 web 全量 +
      typecheck/lint/build 的 clean EV，避免“提交信息声称、EV 日志未覆盖”的证据漂移。
    evidence: >-
      .riccon/evidence/TASK-0232/EV-1100.json；EV-1100 日志 6 files / 36 tests；
      本会话独立执行 pnpm --filter @riccon/web test = 104 files / 969 tests，
      typecheck/lint/build 均 exit 0。
evidence:
  - EV-1100
reviewed_evidence:
  - EV-1100
created_at: "2026-11-25T22:46:12+08:00"
---

# REV-TASK-0232-002：TASK-0232（M06 T12a）F-001..F-003 第二轮复审

## 结论

**fail**。上一轮 F-001、F-002 和 F-003 的三个指定 pre 已按预期修复，独立复跑 36 个 run 定向
用例、969 个 web 全量用例、typecheck、lint、build 均通过；但 F-003 的根因
`.workflow-run-summary-block > pre` 仍有一个未修复实例：审批脱敏参数 pre 没有 tabIndex/role/
aria-label，键盘用户展开后无法聚焦并滚动。该问题与上一轮 F-003 同属 axe
`scrollable-region-focusable` serious，作为 F-001（本轮）open P2，需修复后重新提交复审。
无其他新 P1/P2；EV-1100 只自证 36 tests，969/typecheck/lint/build 由本次独立复跑证明，作为 P3
证据完整性残余 accepted。

## F-001..F-003 复核

| 上一轮 finding      | 本轮结论 | 证据                                                                                                                                                                                                                                                                                                         |
| ------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| F-001 对比度        | 已修复   | `run.css:143-145` 改为 `#595959`，13px 元信息白底对比度约 7.0:1；新增 `run-a11y.test.ts` 读取 CSS 并断言颜色与 ≥4.5:1；独立 36 定向 + 969 全量通过。                                                                                                                                                         |
| F-002 label-in-name | 已修复   | `run-detail.tsx:586-588` 移除“展开完整结果”aria-label，按钮以“展开全文”可见文本为名称；引用按钮移除组合 aria-label，以完整可见文本（含定位）为名称；`run-detail.test.tsx:292-293,406-409` 按可见名称查询并断言 label-in-name；独立测试通过。                                                                 |
| F-003 滚动区        | 部分修复 | 输入/输出摘要 pre（`:332-343`）和结果 pre（`:570-578`）已加 `tabIndex={0}`、`role="region"`、中文 aria-label；`run-detail.test.tsx:372-397` 聚焦三个 region 并断言 tabindex。**但审批脱敏参数 pre（`:510-514`）仍使用同一 `.workflow-run-summary-block > pre` 滚动样式，未加任何可聚焦属性**，见本轮 F-001。 |

## 验证了什么

- **提交与范围**：`fc5adcab` 只改 `apps/web/src/pages/workflow/run/**` 4 个文件
  （run-a11y.test.ts、run-detail.test.tsx、run-detail.tsx、run.css），`3667ee80..fc5adcab` 无
  后端、contracts、PRODUCT、AGENTS、T12b/T12c 变更；T12b 的 3667ee80 是任务基线，本次 diff 不包含它。
- **独立测试**：`pnpm --filter @riccon/web exec vitest run src/pages/workflow/run` → 6 files / 36 tests
  全通过（13.71s）；`pnpm --filter @riccon/web test` → 104 files / 969 tests 全通过（200.24s）；
  `pnpm --filter @riccon/web run typecheck`、`run lint`、`run build` 均 exit 0（build 17.13s 编译成功）。
- **EV-1100**：`commit=fc5adcab`、`dirty=false`、`cwd` 主工作树、`output_bytes=517`，实际
  sha256 `a03437501667f9ef7f1ccdbf8ea2a0f439cdb272d996524f0be29258d53187dc` 与记录一致；
  command/log 只显示 36 个 run 定向测试，不包含 969 web/typecheck/lint/build（见 F-002）。
- **a11y 修复核对**：`#595959` 白底约 7.0:1；label-in-name 已按可见文本构造；输入/输出/结果
  `<pre>` 均有 `tabindex="0" role="region" aria-label`；这些点在 jsdom 测试和源码层面均成立。
  独立 Chromium 探针进一步确认：无 tabIndex 的等价滚动 pre 在 375px 下触发 axe
  `scrollable-region-focusable` serious，补齐 `tabIndex=0 + role=region + aria-label` 后该规则通过。
- **未改范围**：REV-TASK-0232-001 的 F-004..F-008（抽屉旧快照、刷新 loading 竞态、
  CANCELLED-only 摘要、updatedAt 测试假绿、路由/T12c）仍为 accepted P3，本次不重开。

## 未验证

- 真实浏览器 E2E-M06-03/08/09、axe/375px/完整键盘旅程：归 T16a；本评审没有用 jsdom 替代
  axe，也没有执行真实浏览器滚动/焦点验证。
- 路由注册、导航、面包屑、生产 run chunk：归 T12c（TASK-0246）；本次 build 仍无 run chunk，
  属于上一轮 F-008 的 accepted P3，不在本轮重开。
- 全量模块 gate、覆盖率复跑、T16b 回滚、T17 模块评审：未执行。
- 969 web 全量包含 T12b 已合入的 history 页面测试；本轮只把“全量通过”作为回归背景，不把
  T12b 行为纳入 T12a 判定。

## Findings

- **F-001（P2，open）**：F-003 根因未完全关闭——审批脱敏参数 pre 仍是 `max-height` +
  `overflow:auto` 的滚动区，却没有 `tabIndex=0`/`role="region"`/`aria-label`；展开 details 后
  键盘无法聚焦滚动，axe `scrollable-region-focusable` serious。owner `riccon/builder-b1`，
  due T12a 修复轮次；按输入/输出摘要同一方案修复并补测试。
- **F-002（P3，accepted）**：EV-1100 只自证 36 个 run 定向测试；969 web/typecheck/lint/build
  不在其 command/log 中，独立复跑已通过。owner `riccon/builder-b1`，due M06 gate 前；
  M06 gate 前补一条覆盖全量 + typecheck/lint/build 的 clean EV。

## 剩余风险

- **F-001**：审批参数 pre 是最后一块同类滚动区；修复后本轮 F-001..F-003 可闭环，但仍需 T16a 在
  真实浏览器 axe 中确认 0 serious/critical。
- **F-002**：EV-1100 证据范围小于交接声明，独立复跑已证明当前提交；M06 gate 前补 EV 后风险关闭。
- **REV-TASK-0232-001 F-004..F-008**：保持 accepted P3 状态，不因本轮复审重开；T12b/T12c/T16a
  仍需按各自 due 跟踪。
- **E2E/375px/axe**：T16a 前不得把本任务的 jsdom 结果当作模块级 a11y pass。

## 证据

- EV-1100：`pnpm --filter @riccon/web exec vitest run src/pages/workflow/run`，commit fc5adcab，
  dirty=false，cwd 主工作树，6 files / 36 tests，sha256 `a0343750…`，bytes 517。
- 本会话独立复跑（未写入 `.riccon/evidence`）：run 定向 36/36；web 全量 104 files / 969 tests；
  typecheck、lint、build 均 exit 0。

## 回执

```text
任务：TASK-0232（M06 T12a）F-001..F-003 第二轮复审
review_id：REV-TASK-0232-002
previous_review：REV-TASK-0232-001
verdict：fail
findings：P0=0；P1=0；P2=1 open（F-001 审批脱敏参数 pre 不可键盘聚焦）；
  P3=1 accepted（F-002 EV-1100 只自证 36 tests）
关键结论：上一轮 F-001 对比度、F-002 label-in-name、F-003 的输入/输出/结果 pre 均已修复；
  独立复跑 36 run + 969 web + typecheck/lint/build 全通过；但同一滚动样式的审批参数 pre 仍未加
  tabIndex/role/aria-label，F-003 未完全关闭，需修复后重新评审。EV-1100 clean/hash 一致，
  但只覆盖 36 run tests；969/typecheck/lint/build 为本次独立复跑证据。
  真实 E2E/axe/375px 归 T16a；REV-001 F-004..F-008 accepted P3 不重开。
证据：EV-1100
恢复下一步：修复审批参数 pre 的可访问滚动属性（run-detail.tsx:510-514）并补聚焦测试，然后
  `pnpm --filter @riccon/web exec vitest run src/pages/workflow/run` 与 web 全量复跑、clean 取证、
  重新提交评审。
```
