---
review_id: REV-TASK-0248-001
module_id: M06
task_id: TASK-0248
reviewer:
  agent: riccon/verifier
  session: ses_deb1c8acdffeXPKfraCtUGBLr8
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 425ea51e1cdd06a3c327ed95929d358417a8159d
  - 214cdc6a7bc56749759c6d7128e0bdecc7e499dd
head_commit: fdb8b661cb0bb9e478a83f603c02e2d85bf2ebc0
baseline_commit: ef3f5006
code_commit: 214cdc6a7bc56749759c6d7128e0bdecc7e499dd
evidence_commit: f1bee5a6cbe58ca3fadd2158446b4d4978f8aedc
review_scope: >-
  M06 T16a-F1（TASK-0248）前端 a11y 修复的任务级独立评审：T16a E2E-M06-09a 暴露的
  绿色/橙色/金色 Tag 对比度、编辑器发布/上移/删除/删除连线与模板复制/运行历史主按钮的
  label-content-name-mismatch、审批元信息对比度，以及 REV-TASK-0230-001 F-004（保存 400
  时校验面板可达）。不替代 T16a 的真实 axe 重跑、M06 模块 review、Product 预验收或用户验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
dod_note: >-
  acceptance 记 true 但按 **pass (with residual)** 解释：五类 axe 违规点在组件/CSS/源码层
  已逐条消除，并由组件与 CSS 对比度测试守住；真实浏览器 axe（E2E-M06-09a）由 T16a 在修复后
  重跑，本评审未运行，因此不把 jsdom/CSS 测试当成模块级 a11y pass。e2e/coverage/security/
  performance 由 T16a 与 M06 gate 承担。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: T16a 重跑 E2E-M06-09a 前 / M06 gate 前
    title: "编辑器新增的 label-in-name 测试只按可访问名称查询，未断言可见文本包含关系"
    summary: >-
      `apps/web/src/pages/workflow/edit/index.test.tsx:834-857` 对“校验/发布/上移/下移/删除/
      删除连线”仅用 `getByRole('button', { name })` 查询可访问名称。若移除
      `autoInsertSpace={false}`，可见文本会变回“发 布”等，而 aria-label 仍是“发布”，该测试
      仍会通过，无法守住 T16a `label-content-name-mismatch` 的回归。模板库、运行历史、审批
      测试已用 `aria-label`/`textContent` 包含断言，编辑器这一组缺失。期望对每个两字中文按钮
      断言 `button.textContent.trim()` 与 `aria-label` 的包含关系。
    accepted_reason: >-
      实现本身已正确：源码对相关按钮设置 `autoInsertSpace={false}`，aria-label 与可见文本一致；
      本评审独立复跑 1020 用例及 typecheck/lint 通过，T16a 重跑真实 axe 是最终门禁。该缺口只
      降低组件级回归检出能力，不造成当前用户可见缺陷，作为有界 P3 接受。
    evidence: >-
      apps/web/src/pages/workflow/edit/index.test.tsx:834-857；
      apps/web/src/pages/workflow/edit/index.tsx:1284-1300,1442-1475,1571-1585；
      对比 apps/web/src/pages/workflow/templates/index.test.tsx:245-259、
      apps/web/src/pages/workflow/history/run-history.test.tsx:188-199,510-525。
reviewed_evidence:
  - EV-1145
evidence:
  - EV-1145
created_at: "2026-11-26T06:55:00+08:00"
---

# REV-TASK-0248-001：M06 T16a-F1 前端 a11y 修复独立评审

## 结论

**Pass（带 1 个 accepted P3 测试残余）**。T16a 在 EV-1130 记录的 8 条 axe serious 违规点已逐条修复：流程列表/编辑器/运行详情的绿色 Tag 对比度、编辑器发布/上移/删除/删除连线与运行历史主按钮的 `label-content-name-mismatch`、模板复制按钮的可访问名称、审批元信息对比度；REV-TASK-0230-001 F-004 的保存 400 校验面板入口已落地并有组件测试。未发现新的 P0/P1/P2；真实 axe 由 T16a 在修复后重跑，本评审不以组件/CSS 测试替代。

## 评审范围与提交

| 项          | 值                                                                                                                                  |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| baseline    | `ef3f5006`（任务卡 baseline）                                                                                                       |
| 任务代码    | `425ea51e`（27 文件，+958/-71）与 `214cdc6a`（2 文件，+9/-1）                                                                       |
| 证据提交    | `f1bee5a6`（EV-1145；任务 `end_commit=214cdc6a` 的后代提交）                                                                        |
| 评审时 HEAD | `fdb8b661`（仅 docs/.riccon 变更；`214cdc6a..f1bee5a6` 与 `f1bee5a6..HEAD` 的 workflow 范围 diff 均为空）                           |
| 变更范围    | 全部落在 `apps/web/src/pages/workflow/**`、`apps/web/src/components/workflow/**`；未改 `apps/api`、`contracts`、`PRODUCT`、`AGENTS` |

`git diff --name-only ef3f5006..214cdc6a` 的 27 个文件均为前端 workflow 页面/组件/测试/CSS，无后端、迁移、契约、路由配置文件；`git diff --check` 无输出；无新增依赖、无 `eslint-disable`/`ts-ignore`/`console`/`TODO`，新增注释均为中文。

## Tag 对比度

`apps/web/src/components/workflow/accessible-tag.tsx` 新增 `WorkflowTag`：保留 antd preset 类名（如 `ant-tag-green`），用内联样式覆盖前景/背景/边框。7 种语义色的 WCAG 2.1 对比度（前景/背景）独立计算如下，全部 ≥4.5:1：

| 语义色     | 前景/背景           | 对比度 |
| ---------- | ------------------- | ------ |
| default    | `#595959`/`#f5f5f5` | 6.43   |
| blue       | `#0958d9`/`#e6f4ff` | 5.50   |
| green      | `#237804`/`#f6ffed` | 5.44   |
| orange     | `#ad4e00`/`#fff7e6` | 5.10   |
| gold       | `#874d00`/`#fffbe6` | 6.53   |
| red        | `#a8071a`/`#fff1f0` | 7.04   |
| processing | `#0958d9`/`#e6f4ff` | 5.50   |

- **使用面**：列表（状态/已发布）、编辑器（标题状态/节点状态/条件分支/问题码）、运行详情（运行/节点/审批/当前节点）、运行历史（运行/审批/当前发布）、模板库（内置模板）、审批待办/详情（状态）均已替换为 `WorkflowTag`；生产代码中不再有 `<Tag color=...>`（中性 `<Tag>` 保留，默认前景 `#595959` 对白底 7.0:1）。
- **测试**：`apps/web/src/components/workflow/accessible-tag.test.tsx` 对 `WORKFLOW_TAG_STYLES` 7 色逐一断言 ≥4.5:1，并对 green/blue/orange/gold/red/default/processing 的渲染结果断言 preset 类名与内联对比度；`a11y-css.test.ts` 覆盖编辑器/运行/历史/审批的 danger 文本按钮、校验面板焦点环与审批元信息 `dt`，均通过。
- **审批页追加**：`approvals.css` 的 `.workflow-approvals-meta dt` 由 `#8c8c8c`（约 3.0:1）改为 `#595959`（7.0:1），并在 `214cdc6a` 增加了 CSS 回归断言；这是 T16a 页面扫描之外的前瞻修复，不影响既有语义。

## aria-label 与 label-in-name

| 位置            | 修复                                                                                                                    | 测试                                                                               |
| --------------- | ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| 编辑器工具栏    | “校验/发布”设置 `autoInsertSpace={false}`；发布弹窗 `okButtonProps` 同样关闭自动空格                                    | `edit/index.test.tsx` 按 `校验`、`发布` 查询；源码核对可见文本无空格               |
| 编辑器节点/连线 | 上移/下移/删除节点/删除连线设置 `autoInsertSpace={false}`，aria-label 以可见文本开头并补充节点/连线上下文               | 同上按 `上移节点 开始`、`删除节点 开始`、`删除连线 start 到 retrieval-1` 查询      |
| 模板库复制      | `aria-label` 改为 `复制模板：${name} 复制为草稿`，包含可见文本“复制为草稿”                                              | `templates/index.test.tsx` 断言 `textContent` 为“复制为草稿”且 `aria-label` 包含它 |
| 运行历史主按钮  | “查询”按钮设置 `autoInsertSpace={false}`，aria-label 为“查询运行历史”                                                   | `run-history.test.tsx` 用 `expectLabelInName` 断言可见文本“查询”被可访问名称包含   |
| 审批            | “批准/拒绝”设置 `autoInsertSpace={false}`；待办“查看详情”aria-label 追加“ 查看详情”                                     | 审批 inbox/detail 测试断言名称包含关系                                             |
| 重试类按钮      | 所有两字“重试”按钮统一 `autoInsertSpace={false}`；带图标的两字“刷新”按钮不触发 antd 自动空格（`needInserted` 为 false） | 按中文名称查询的既有测试通过                                                       |

- AntD 6 的 `Button` 通过 `spaceChildren(children, needInserted && mergedInsertSpace)` 在**渲染时**插入空格；`autoInsertSpace={false}` 使可见文本与 aria-label 一致，而不是只在 CSS 上拉字距。
- **残余**：编辑器新增测试只验证可访问名称查询，未对两字按钮断言 `textContent`/aria-label 包含关系（F-001）；实现经源码核对正确，T16a 真实 axe 仍是最终判据。

## F-004：保存 400 校验面板可达

- `edit/index.tsx:927-944` 在 `persistIfNeeded` 捕获 400 时先于通用错误分支处理：`setSaveError("保存失败（400）：" + message)`、`setPanelPhase('error')`、`setPanelError(message)`，并返回 false；`handleValidate` 随后保持面板 error 而不是回到 idle。
- `message` 来自 `ApiError.message`；`apps/web/src/services/api/errors.ts` 的 `toApiError` 明确以 `problem.detail` 优先构造消息（`errors.ts:139-157`），因此面板展示的是后端 `WorkflowValidationException` 的逐项 detail，而不是丢失 detail 的通用文案。
- 面板容器为 `#workflow-validation-panel`、`tabIndex={-1}`、`aria-live="polite"`；保存失败时顶部 Alert 的 `action` 出现“查看校验问题”按钮，点击后 `focusValidationPanel` 滚动并聚焦面板；`editor.css` 提供 `scroll-margin-top` 与 `:focus-visible` 焦点环（`#0958d9` 对白底 5.5:1）。
- `edit/index.test.tsx:859-882` 用 `saveDraft` 抛 400 detail 的 fake API 覆盖：断言顶部“保存失败（400）：…”文本、面板内 detail 文本、点击“查看校验问题”后面板获得焦点；测试通过。验收要求的“显示后端 issues 或明确入口”由面板 detail + 明确入口同时满足。
- 该实现关闭 REV-TASK-0230-001 F-004；原 accepted 残余不再影响 M06-AC-02 的保存 400 分支。

## 证据与测试

| 项           | 结果                                                                                                                                                                                                            |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-1145      | `pnpm --filter @riccon/web test && typecheck && lint && build`；commit `f1bee5a6`，`dirty=false`，`exit_code=0`；108 文件/1020 用例、typecheck/lint/build 全通过；日志 sha256 `edc19afa…b16abb` 与 EV JSON 一致 |
| 独立定向复跑 | `pnpm --filter @riccon/web exec vitest run src/pages/workflow src/components/workflow`：33 文件/349 用例通过（44.37s）                                                                                          |
| 独立全量复跑 | `pnpm --filter @riccon/web test`：108 文件/1020 用例通过（151.27s），与 EV-1145 计数一致                                                                                                                        |
| 独立静态检查 | `pnpm --filter @riccon/web typecheck` 与 `lint` 均 exit 0（Prettier 全通过）                                                                                                                                    |
| 真实 axe/E2E | 未由本评审运行；T16a 重跑 E2E-M06-09a 后以真实浏览器结果关闭 TASK-0237 的 a11y 阻塞项（其余 blocker 另行判定）                                                                                                  |
| 未运行       | coverage、Maven 全量/契约测试、k6/perf、Trivy/security、真实数据库/迁移；本任务无后端/迁移改动，归 T16a/M06 gate                                                                                                |

- EV-1145 的 commit `f1bee5a6` 晚于任务 `end_commit=214cdc6a`，但 `git diff --name-only 214cdc6a..f1bee5a6 -- apps/web/src/pages/workflow apps/web/src/components/workflow apps/web/src/layouts/workspace` 为空，证明该 clean 全量运行覆盖任务最终代码；任务卡只引用 EV-1145，未引用更早的 EV-1132..EV-1139。
- 测试无 `.skip`/`.todo`/`.only`/`xit`；未使用 `--no-file-parallelism` 等为通过追加的参数；新增测试均有实际断言，不是零测试或快照坏态。

## AC 对账（任务级）

| 项                                       | 结论                                                                                                                                           |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| T16a F-01（Tag 对比度 + 可访问名称）     | **pass（with residual）**：8 条 axe serious 选择器对应的源码点均已修复；组件/CSS 测试守住对比度与名称；真实 axe 由 T16a 重跑，未在本评审运行。 |
| REV-TASK-0230-001 F-004（保存 400 面板） | **fixed**：面板 error 态展示后端 detail，顶部“查看校验问题”入口聚焦 `#workflow-validation-panel`，组件测试覆盖保存 400、文本与焦点。           |
| M06-AC-19（五界面状态/文案/375px/a11y）  | **pass (with residual)**：本任务只改 a11y 样式/名称；375px 与 axe 真实浏览器结果仍由 T16a E2E-M06-09a/09b 判定。                               |
| M06-AC-20（键盘与 axe 无 serious）       | **pass (with residual)**：编辑器的发布/上移/删除等按钮可访问名称已与可见文本一致；真实键盘全流程与 axe 由 T16a E2E-M06-09a/09c 判定。          |
| 任务卡验收 3（范围/命令）                | **pass**：只改指定前端目录；未改后端/contracts/PRODUCT/AGENTS；EV-1145 clean，web test/typecheck/lint/build 通过。                             |

## Findings

- **F-001（P3，accepted）** 编辑器 label-in-name 组件测试未断言可见文本包含关系；owner=`riccon/builder-b3`，due=T16a 重跑 E2E-M06-09a 前/M06 gate 前，accepted_reason 见 front matter。
- 无 open P0/P1/P2；无 open finding。

## 未验证与剩余风险

1. **真实 axe 未在本评审重跑**：T16a 必须在修复后重跑 E2E-M06-09a，确认五界面/模板库/审批/历史/详情无 serious/critical；本评审只确认 T16a 列出的 8 条违规点在源码与组件测试层已消除。
2. **非默认态模态框/确认框未由 axe 覆盖**：例如停用流程 Modal 的 danger primary 按钮、Popconfirm 等只在交互后出现，T16a 的默认页面扫描不覆盖；本任务未修改这些非默认态，建议 T16a 在键盘流程中按需复核。
3. **F-001 的回归检出缺口**：组件测试不能替代真实 axe；若后续修改按钮 `autoInsertSpace` 或 aria-label，需由 T16a axe 兜底。
4. **EV 提交追溯**：EV-1145 在 `f1bee5a6` 而非 `end_commit=214cdc6a`；已用 workflow 范围 diff 为空证明覆盖最终代码，但任务卡若要求“证据必须绑 end_commit”，Lead 可在 done 前补一条 `214cdc6a` 上的 clean web EV 或写清覆盖说明。

本评审只写了本评审文件，未修改生产源码、测试、契约、PRODUCT、AGENTS 或 `.riccon` 状态，未运行 `gate check --module M06`，也未把任务标 done。
