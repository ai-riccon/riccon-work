---
review_id: REV-TASK-0251-001
module_id: M06
task_id: TASK-0251
reviewer:
  agent: riccon/verifier
  session: ses_dea18d327ffeyzemTmU5NAblLh
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - e26fee8fe97abca9dd78f35e37c4f91cd20cc759
  - b196be877c4c0345e96e6d561c7618ec77332142
  - 8e73c072edefbd61baf4d6df1cb0ba35975c0da1
head_commit: 8e73c072edefbd61baf4d6df1cb0ba35975c0da1
baseline_commit: d3ab1bda5f866460492e454ee763d9b9df3760a9
code_commit: e26fee8fe97abca9dd78f35e37c4f91cd20cc759
evidence_commit: b196be877c4c0345e96e6d561c7618ec77332142
review_scope: >-
  M06 TASK-0251（Product 预验收 F-001 / AC-17）的任务级独立评审：只读运行限制视图
  （GET /workflows/runtime-limits，入口在 Studio 子导航）与运行准入/历史/编辑器的
  RICCON-WF-429 具体 limit/current/下一步文案。核对 e26fee8f 的 17 个 web 文件是否在
  write_scope 内、是否改动后端/contracts/PRODUCT/AGENTS；复核 EV-1164（clean、commit、
  sha256、1049 用例、coverage）并独立重跑 web 全量/定向测试、typecheck、lint。真实浏览器
  打开态 axe/375px/E2E 由 T16a/T17 复核，本评审记录 residual；不评审 M06 模块 gate、
  Product 最终预验收或用户验收，不运行 gate check。
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  lint: true
  typecheck: true
  accessibility: true
  review: true
  evidence: true
  scope: true
dod_not_applicable:
  - e2e
  - security
  - performance
  - migration
  - docs
dod_note: >-
  acceptance 记 true 但按 **pass (with residual)** 解释：AC-17 的只读限制字段、入口、
  加载/403/错误/编排关闭态、只读性与 429 limit/current/下一步由组件测试和 429 用例覆盖；
  真实浏览器打开态的 axe、375px 横向滚动与 E2E-M06-09/10 未在本任务运行，列入 F-001
  （P3 accepted）。e2e/security/performance/migration/docs 不在本任务验收：任务卡仅要求
  web 组件测试与 test/typecheck/lint/build；安全面为只读 GET、无新写入/密钥/PII；
  build 引用 EV-1164 clean 证据，本评审未重复执行同一 build 以免重复全量。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: Product 复验收前（M06 done 前）
    title: 真实浏览器打开态的 axe/375px/E2E 未由本任务覆盖
    summary: >-
      AC-17 的界面由 jsdom 组件测试与 CSS 静态断言验证：入口、全字段、只读（无输入/编辑）、
      加载/403/错误/编排关闭态、键盘焦点和 375px 规则均已覆盖；但本任务未在真实浏览器
      打开抽屉跑 axe、375px 横向滚动和 E2E-M06-09/10。
      `runtime-limits.test.tsx:185-197` 的标题写“样式不产生横向滚动”，实际只断言刷新/关闭
      按钮可聚焦，滚动由 `a11y-css.test.ts:113-135` 的静态 CSS 规则兜底；jsdom 不计算布局。
    accepted_reason: >-
      抽屉为只读 antd Drawer，无写入/编辑控件，焦点陷阱、Escape 关闭与关闭按钮复用 antd
      既有 a11y 实现；新增入口颜色 #595959 对白底约 7:1，新增 CSS 只做宽度/换行约束，真实
      axe 风险有界。任务卡验收只要求 Web 组件测试；Lead/Product 已将真实 axe/E2E 打开态交给
      T16a/T17 复核，因此作为有界 P3 接受，不阻塞本任务 pass。
    evidence: >-
      EV-1164；apps/web/src/components/workflow/runtime-limits.test.tsx:185-197；
      apps/web/src/pages/workflow/a11y-css.test.ts:113-135
reviewed_evidence:
  - EV-1164
evidence:
  - EV-1164
created_at: "2026-11-26T11:39:13+08:00"
---

# REV-TASK-0251-001：任务 TASK-0251 独立评审

## 结论

**pass（with residual）**。AC-17 的只读运行限制视图与 429 limit/current/下一步文案已按 Product 预验收 F-001 完成；代码范围、clean 证据与独立复跑全部通过。唯一残余是真实浏览器打开态的 axe/375px/E2E 未由本任务验证，作为 P3 accepted 记录。

## 验证了什么

### AC-17 UI

- **入口**：`apps/web/src/pages/workflow/templates/studio-nav.tsx:110-119` 在 Studio 子导航内提供“运行限制”帮助按钮，仅 `workflow.read` 可见（`:96`），不新增编辑入口。
- **字段**：`apps/web/src/components/workflow/runtime-limits.tsx:156-272` 展示 `featureEnabled`、`maxNodes`、`maxSteps`、模型/检索/MCP 节点超时、运行超时（审批等待不计）、租户/单流程并发、结果大小、`retentionDays` + `retentionPolicy`、`generatedAt`；`disabledReason` 在编排关闭时展示（`:162-176`），中文翻译仅对后端默认英文基线生效（`runtime-limits-format.ts:104-113`）。
- **只读**：抽屉说明“租户只能查看，不能在此修改”（`:108-110`），测试断言无 `textbox`/`spinbutton`/编辑按钮（`runtime-limits.test.tsx:88-92`）。
- **状态**：加载骨架（`:112-116`）、403（`:118-135`）、加载失败 + 重试（`:137-154`）、编排关闭警告（`:162-176`）均有中文说明与下一步；关闭时不请求接口（`runtime-limits.test.tsx:157-172`）。
- **375px/键盘**：抽屉 `size="min(480px, 100vw)"` + CSS `max-width:100vw`/`overflow-x:hidden`/换行规则（`runtime-limits.css`）；测试断言入口与刷新/关闭按钮可聚焦（`runtime-limits.test.tsx:185-197`、`studio-nav.test.tsx:243-252`）；静态 CSS 断言见 `a11y-css.test.ts:113-135`。真实浏览器 axe/滚动 residual 见 F-001。
- **契约对齐**：字段名与 `contracts/openapi.yaml` 的 `WorkflowRuntimeLimits`（`8747` 起）及后端 `WorkflowRuntimeLimitsResponse` 一致；API 客户端为 GET、同源、不发 CSRF（`runtime-limits-api.ts:90-102`）。

### 429 文案

- **解析**：`runtime-limits-format.ts:19-20,124-140` 只接受 `status=429 && code=RICCON-WF-429`，从 `ProblemDetail.detail`（或 `ApiError.message`）解析 `tenant|workflow active run limit reached (limit=N, current=M)`；后端实际 detail 与 `WorkflowRunRateLimitedException.java:23-27` 格式一致。
- **展示**：租户/单流程分别输出“当前 N 个，上限 M 个”+ 等待、调整流程、联系管理员（`runtime-limits-format.ts:151-167`）；无法解析时仍给出可执行兜底，不回到“请求过于频繁”。
- **入口覆盖**：运行准入 `history/format.ts:604-628`（经 `release-panel.tsx:504` 渲染）；运行历史重试/取消 `history/format.ts:564-592`（`run-history.tsx:256/293/315`）；编辑器保存/发布/另存 `edit/index.tsx:134-144`（`:949/993/1086/1123`）。
- **配额不误报**：`history/format.ts:578-586,615-625` 与 `describeApiError` 的 `RICCON-QUOTA-429` 分支保持 M05 限额/重置文案；测试 `format.test.ts` 断言不出现“并发运行”。
- **不泄露敏感**：文案只包含 limit/current 与操作建议，不含 tenantId、其他运行内容、凭据或正文；后端 detail 同样只含 scope/limit/current。

### 测试与证据

- **EV-1164（clean）**：commit `b196be87`，`dirty=false`，`exit_code=0`，日志 sha256 与 `EV-1164.json` 一致；命令为 `pnpm --filter @riccon/web test:coverage` + `typecheck` + `lint` + `build`；111 文件 / **1049 用例通过**，coverage **87.70 / 80.84 / 85.46 / 87.61**（stmts/branch/funcs/lines，均高于合同 80/70）；typecheck、lint、build 均 exit 0。
- **EV 与代码提交的关系**：EV-1164 记录在 `b196be87`，该提交含 TASK-0252 的后端审计修复；`git diff e26fee8f..b196be87 -- apps/web` 为空，因此 EV-1164 的 web 测试/coverage/typecheck/lint/build 结论直接覆盖 TASK-0251 的 web 变更。
- **独立复跑**：
  - 定向 9 个相关测试文件（runtime-limits 组件/格式化/API、history format/run-history/release-panel、edit、studio-nav、a11y-css）：**143 passed**，exit 0。
  - `pnpm --filter @riccon/web test` 全量：**111 文件 / 1049 用例 passed**，exit 0，113.79s。
  - `pnpm --filter @riccon/web typecheck`：exit 0；`pnpm --filter @riccon/web lint`：exit 0（eslint + prettier）。
- **无假绿**：相关测试无 `skip`/`todo`；429 断言检查实际渲染文本与“不误报并发”；仅新增/替换测试，未削弱既有断言（原 release-panel 的 429/503 测试名与断言不符，实际只测 429；本次改为具体 429 断言，503 发布/详情由既有用例覆盖）。
- **范围**：`git show --name-status e26fee8f` 的 17 个文件全部位于 `apps/web/src/pages/workflow/**` 与 `apps/web/src/components/workflow/**`；未改后端、`contracts/`、`docs/PRODUCT.md`、`AGENTS.md`；`git diff e26fee8f..HEAD -- apps/web` 为空。
- **任务状态**：TASK-0251 仍为 `review`，未被移到 `done`；本评审未修改任务卡或 `.riccon` 状态。
- **旧 EV**：EV-1160/EV-1161 为 `dirty=true`，任务卡 `evidence` 仅引用 EV-1164；未把 dirty 证据当作本次通过依据。

## 证据

- EV-1164：`.riccon/evidence/TASK-0251/EV-1164.json`（日志同目录，sha256 已核对；commit `b196be87`，`dirty=false`，exit 0；1049 用例、coverage 87.70/80.84/85.46/87.61）。
- 本评审：`.riccon/reviews/tasks/TASK-0251-review.md`（review_id `REV-TASK-0251-001`）。
- 独立复跑为本次评审的临时终端结果，按派单要求只写评审文件、未新增 EV；命令与结果见上节。

## 未验证

- 真实浏览器中打开运行限制抽屉后的 axe 扫描、375px 实际横向滚动、E2E-M06-09/10 回归；按任务指令由 T16a/T17 复核，见 F-001。
- 未重跑后端契约/集成测试：本任务未改后端，`GET /workflows/runtime-limits` 的服务端实现与 `RICCON-WF-429` detail 沿用已通过的 M06 证据；本次通过代码核对确认格式一致。
- 未运行 build 的第二次重复执行（EV-1164 clean build exit 0）；未运行 `riccon gate check`（按派单要求）。

## Findings

### F-001（P3，accepted）真实浏览器打开态的 axe/375px/E2E 未由本任务覆盖

- 现状：组件/CSS 测试覆盖语义、只读、状态、焦点与静态 375px 规则；`runtime-limits.test.tsx:185-197` 的标题“不产生横向滚动”实际只断言焦点，jsdom 不计算布局。
- 影响：不构成当前用户可见缺陷，但真实浏览器抽屉打开态的 axe/横向滚动仍缺少本次修复后的直接证据。
- 处置：owner `riccon/verifier`，due Product 复验收前（M06 done 前）；由 T16a/T17 在既有 E2E-M06-09/10 上补一次打开态 axe/375px 检查。风险有界（只读、antd Drawer 原生焦点管理、入口对比度约 7:1）。

## 剩余风险

- Product 预验收要求“重跑相关 E2E-M06-09/10”；本任务只跑了 Web 单测，E2E 打开态与模块回归留给 T16a/T17 复核，Product 复验收前应确认已执行。
- 429 文案中“联系租户管理员调整部署配置”与抽屉“联系平台运维提交部署配置变更”口径略有差异；AC-17 只要求“联系管理员”，不阻塞本任务，但建议 Product 后续统一文案时一并处理。
- 本评审不是模块 review、Product 预验收或用户最终验收；M06 是否可 done 仍取决于模块 gate 与 Product 复验收。

## 独立性声明

本评审使用新上下文，未继承 builder 的推理；只读取任务卡、diff、契约、代码、EV-1164 日志并独立复跑测试/typecheck/lint。未修改生产源码、测试、迁移、构建配置、合同或 `.riccon` 状态；仅新增本评审文件。
