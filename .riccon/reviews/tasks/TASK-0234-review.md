---
review_id: REV-TASK-0234-001
module_id: M06
task_id: TASK-0234
reviewer:
  agent: riccon/verifier
  session: ses_ded7e1019ffe111ws5vrVfxKTr
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - ea5af615d797a97dc730b205726f6a8f52c14d84
  - b11e557d12f33b13d0fbdee4a7ad56be784b2a11
  - d9a729d111049953708a7b3b0f40fbcaa6d77060
  - d297186ae5f3e04e593d608182741a0b5e232b60
  - 0f6f25714f7bdd5fce38a71fc8d13d6654b476d3
head_commit: 0f6f25714f7bdd5fce38a71fc8d13d6654b476d3
code_commit: ea5af615d797a97dc730b205726f6a8f52c14d84
ev_commit: b11e557d12f33b13d0fbdee4a7ad56be784b2a11
evidence_commit: d9a729d111049953708a7b3b0f40fbcaa6d77060
end_commit: d9a729d111049953708a7b3b0f40fbcaa6d77060
baseline_commit: 0511afd31791f227a402ff262bf3c58cafb01909
review_scope: >-
  M06 T13（TASK-0234）独立任务评审：核对审批待办/详情/决定页面的 AC-05/06 前端用户可见结果、
  AC-19/20 的页面级状态/键盘/375px 基线、EV-1089 clean 证据与旧 EV 引用、提交范围与 T08 交错；
  独立复跑 approvals 定向 55 条 + web 全量 829 条 + typecheck/lint/build。不运行真实浏览器
  E2E/axe/375px（T16a）、不运行后端全量、不重审 T09 API 实现、不运行 gate check，不替代
  M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  review: true
  security: true
  evidence: true
  coverage: true
  scope: true
dod_not_applicable:
  - e2e
  - accessibility
  - performance
  - migration
  - docs
dod_note: >-
  acceptance 记 true 按 **pass (with residual F-001/F-002)** 理解：T13 页面实现、组件级状态与
  错误处理通过；`/workflows/approvals` 路由、生产入口、E2E-M06-04 与 @a11y 真实浏览器证据由
  T13b/TASK-0245 和 T16a/TASK-0237 承接，本任务未执行。build/tests/lint/typecheck/coverage 由
  EV-1089 与本评审独立复跑支撑；scope 由 ea5af615 的提交内容逐文件支撑。security 记 true 指
  页面级：类型与模板不含 prompt/密文/正文渲染，自批按钮隐藏，403 不自动重试；端点级 authz/
  越权仍由 T09/T16a 承担。performance、migration、docs 不属本任务范围。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-b3
    due: "M06 T16a E2E 前（先完成 T13b/TASK-0245 路由）"
    title: "审批页尚未接入 /workflows/approvals，生产构建无 approvals chunk；E2E-M06-04/@a11y 未执行"
    summary: >-
      `apps/web/config/routes.ts:54-85` 只有 `/workflows`、`/workflows/list`、
      `/workflows/:workflowId/edit` 与 `/workflows/templates`，没有 `/workflows/approvals` 或
      `/workflows/approvals/:workflowApprovalId`。`WorkflowApprovalsPage` 仅被同目录测试引用，
      EV-1089 的生产构建产物列表也没有 approvals async chunk；页面在真实路由中不可达。
      `TASK-0245`（T13b，planned）负责路由与 Studio 子导航；`TASK-0237`（T16a，planned）负责
      E2E-M06-04、@a11y、375px 与键盘旅程。因此任务卡 acceptance 第 1 条列出的 E2E-M06-04/@a11y
      未在 T13 产生。
    accepted_reason: >-
      T13 的 write_scope 明确不含 routes/nav；Lead 已建 T13b/TASK-0245 承接，用户确认路由缺口
      不作为 T13 失败。页面级 55 条定向测试、829 条 web 全量和 typecheck/lint/build 均通过，
      风险有界于“当前生产不可达”，不影响页面实现本身的组件级验收。T16a 在路由完成前无法执行
      审批 E2E，因此 T13b 必须先于 T16a。
    evidence: >-
      git show ea5af615；apps/web/config/routes.ts:54-85；.riccon/tasks/TASK-0245.json:1-24；
      .riccon/tasks/TASK-0237.json:1-31；EV-1089 构建日志（无 approvals chunk）
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: "M06 T16a/模块 gate 前"
    title: "375px/键盘/axe 仅 jsdom 与静态 CSS 验证，真实浏览器证据归 T16a"
    summary: >-
      inbox 测试 `:212-224` 与 detail 测试 `:327-334` 仅把 `window.innerWidth` 设为 375 后断言
      元素可见/可聚焦；jsdom 不计算布局，不能证明无横向滚动。detail 测试 `:312-325` 逐个
      `focus()` 返回、意见输入与决定按钮，不是完整 Tab 顺序旅程；axe 未运行。
      `approvals.css:1-222` 使用 `min-width:0`、`max-width:100%`、`flex-wrap`、
      `overflow-wrap:anywhere` 与 575px 媒体查询，无固定宽度，静态风险有界。
    accepted_reason: >-
      任务卡与 TASK-0237 明确把真实浏览器 375px、axe 与键盘旅程归 T16a；T13 是页面实现任务，
      jsdom 本就不做 CSS 布局。当前 CSS 无固定宽度，且 `<pre>` 自身可横向滚动、不撑破页面；
      不属于 T13 代码缺陷，但本评审不把 jsdom 结果当作真实浏览器通过。
    evidence: >-
      apps/web/src/pages/workflow/approvals/workflow-approvals-inbox.test.tsx:212-224；
      apps/web/src/pages/workflow/approvals/workflow-approvals-detail.test.tsx:312-334；
      apps/web/src/pages/workflow/approvals/approvals.css:1-222；
      .riccon/tasks/TASK-0237.json:15-18
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/lead
    due: "M06 模块 gate 前（行政项）"
    title: "任务卡 baseline 0511afd3 晚于 T13 代码提交 ea5af615，baseline..end diff 不是 T13 代码范围证据"
    summary: >-
      `git log` 显示 ea5af615 是 0511afd3 的祖先；`git diff --name-only 0511afd3 d9a729d1`
      只包含 `.riccon` 证据/任务/状态文件，不包含 approvals 源码，因为 T13 代码已包含在
      0511afd3 的树中。任务卡把 baseline 设为 T08 提交 `0511afd3` 是为了避免并行 T08 文件被
      误报为 T13 越界，但这也使 baseline..end diff 无法独立证明 T13 的源码范围。实际范围由
      `git show ea5af615` 逐文件核对：11 个 approvals 文件 + 任务卡，未改后端/contracts/
      PRODUCT/AGENTS；T08 的 backend 文件属于并行提交，不归 T13。
    accepted_reason: >-
      实际代码提交无越界，T08 交错文件已有独立归属；baseline 仅用于 gate 噪声规避，不作为
      T13 代码范围证据。建议模块 gate 引用 ea5af615 的提交内容，而不是 baseline..end diff；
      不影响本任务代码验收。
    evidence: >-
      git rev-parse ea5af615 0511afd3；git diff --name-only 0511afd3 d9a729d1；
      git show --stat ea5af615；.riccon/tasks/TASK-0234.json:31-32
evidence:
  - EV-1089
reviewed_evidence:
  - EV-1089
notes: >-
  T13 代码提交 ea5af615 只包含 apps/web/src/pages/workflow/approvals/** 与任务卡；EV-1089 在
  b11e557d 的 clean 主工作树录制，dirty=false、exit=0、hash/bytes 一致，是 d9a729d1/HEAD 的
  祖先。旧 EV-1085/1086 dirty=true，任务卡与状态文件未引用。评审复跑结束时工作树 clean；
  随后并行 T08 history 工作写入了 5 个未提交后端文件（评审窗口观察），不归 T13，本评审未修改
  或回退。未运行真实浏览器 E2E/axe/375px（T16a）与 gate check。
created_at: "2026-11-25T19:55:41+08:00"
---

# REV-TASK-0234-001：M06 T13 前端审批待办/详情/决定独立评审

## 结论

**通过（pass，带残余 F-001/F-002）**。审批待办、详情与决定页面在组件/页面级满足 AC-05/06 的
前端用户可见结果：待办请求固定 `status=PENDING`，服务端按 assignee/非发起人/PENDING 过滤并按
`created_at ASC` 排序；列表显示流程、节点、发起人、等待时长与 `dataScopeSummary`，空/加载/
错误/403/404/503 态均有中文说明与下一步；详情显示动作、目标工具、数据范围、脱敏参数摘要与
审批记录，拒绝理由必填且 ≤500，自批入口隐藏、非 assignee 404、409/503 有固定提示，决定成功后
返回待办并刷新。EV-1089 clean、哈希一致，独立复跑 55 条 approvals 定向测试、829 条 web 全量
测试与 typecheck/lint/build 全部通过，未发现新的 P0/P1/P2 代码缺陷。

两个必须跟踪的残余不是 T13 代码失败：**F-001（P2）** `/workflows/approvals` 路由未接入、
生产构建不含 approvals chunk、E2E-M06-04/@a11y 未执行，由 T13b/TASK-0245 与 T16a/TASK-0237
承接；**F-002（P3）** jsdom 不能证明 375px 无横向滚动与完整键盘/axe，真实浏览器证据归 T16a。
另有 **F-003（P3，行政）** 任务卡 baseline 晚于代码提交，baseline..end diff 不能作为 T13 源码
范围证据，实际范围按 `git show ea5af615` 核对。

## AC 对账

| AC 原文用户可见结果（节选）                                                       | 结论                                                          | 证据                                                                                                                   |
| --------------------------------------------------------------------------------- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| AC-05：只显示分配给自己的待审批项，未分配给自己的不显示                           | pass（服务端过滤；页面不自造数据）                            | `workflow-approvals-inbox.tsx:84`；`WorkflowApprovalJdbc.java:75-99`；`approval-api.test.ts:72-89`                     |
| AC-05：按创建时间升序（最早等待优先）                                             | pass（服务端 `created_at ASC`；页面保序）                     | `WorkflowApprovalJdbc.java:324-338`；inbox 不重排列表                                                                  |
| AC-05：每项显示流程、节点、发起人、等待时长与数据范围摘要                         | pass                                                          | `workflow-approvals-inbox.tsx:228-258`；`format.ts:89-128`；`workflow-approvals-inbox.test.tsx:89-101`                 |
| AC-05：空态说明没有待审批项与下一步                                               | pass（固定文案 + 刷新/返回）                                  | `format.ts:42`；`workflow-approvals-inbox.tsx:207-217`；测试 `:103-114`                                                |
| AC-05：详情显示动作、目标工具、数据范围与脱敏参数摘要，审批人可批准或拒绝         | pass                                                          | `workflow-approvals-detail.tsx:249-324`；`format.ts:136-191`；测试 `:98-112`                                           |
| AC-05：拒绝理由必填；审批记录显示审批人、时间、决定与理由                         | pass（前端必填校验与记录展示；运行终止由 T09 服务端执行）     | `workflow-approvals-detail.tsx:132-153,326-371`；测试 `:151-191,292-310`                                               |
| AC-05：无 ACL 时只显示脱敏摘要与来源标识，不显示片段/正文/定位                    | pass（页面只显示摘要/计数，无正文；契约无逐文档标题字段）     | `format.ts:182-191`；`approval-api.ts:30-68`；detail 仅渲染 `paramsPreview`                                            |
| AC-06：发起人界面不提供批准/拒绝入口，直接调用接口 403 并写拒绝审计               | pass（服务端 403/404；页面 `selfApprovalBlocked` 防御性隐藏） | `workflow-approvals-detail.tsx:213,373-381`；`WorkflowApprovalService.java:382-401,459-463`；`approval-api.ts:131-141` |
| AC-06：范围外/停用/他租户不能审批且不泄露                                         | pass（403/404 固定中文提示；服务端判定归 T09）                | `format.ts:213-233`；detail 测试 `:204-217,251-277`；inbox 测试 `:127-169`                                             |
| AC-19：加载/空/错误/无权限/禁用/成功态有中文说明与下一步                          | pass（禁用态为决定 503 固定文案；待办/详情只读可查）          | `workflow-approvals-inbox.tsx:153-217`；`workflow-approvals-detail.tsx:161-210,236-247`；`format.ts:54-55,226-229`     |
| AC-19/20：375px 无横向滚动、关键按钮键盘可聚焦、焦点可见、axe 无 serious/critical | 页面级部分通过；真实浏览器证据未执行 → F-002                  | `approvals.css:1-222`；inbox 测试 `:204-224`；detail 测试 `:114-119,312-334`；axe/375 归 T16a                          |

## 待办

### 请求与排序

- `approval-api.ts:213-223` 的 `listApprovals` 发送 `page`/`size`，并在提供 `status` 时追加
  `status`；页面 `workflow-approvals-inbox.tsx:84` 固定传 `status: 'PENDING'`、`page`、`size: 20`。
  代码注释 `:48-51` 明确页面只展示服务端返回的“分配给当前用户、非本人发起、PENDING”项，不
  重新排序、不推断其他审批存在性。
- 服务端 `WorkflowApprovalJdbc.java:75-99` 的 `INBOX_FILTER` 同时约束 `a.status='PENDING'`、
  `a.requested_by <> :userId`、运行未到期，以及 USER/ROLE 两种 assignee 归属；
  `:324-338` 的 `listInbox` 使用 `ORDER BY a.created_at ASC, a.id ASC`。页面保持服务端顺序，
  符合 AC-05 的“最早等待优先”。
- `list` 服务层 `WorkflowApprovalService.java:139-165` 对非 PENDING 的显式 `status` 返回空页，
  不会把已决定记录混入待办。

### 展示与状态

- 列表项 `workflow-approvals-inbox.tsx:219-273` 显示流程名（缺省“未命名流程”）、节点名、
  发起人截断 ID（`title` 保留完整值）、等待时长、数据范围与“将执行”动作；
  `format.ts:89-110` 处理不足 1 分钟、分钟、小时、天和非法时间，`:121-128` 只使用
  `runScope`/`departmentId`/`documentReferenceCount`，不推断文档标题或正文。
- 状态覆盖：加载 `:153-157`（`aria-busy` + 中文 label）、403 `:159-173`（联系租户管理员、
  重试/返回）、404 `:175-189`（不可见即不存在、平台管理员不查看租户审批）、错误 `:191-205`
  （重试/返回）、空 `:207-217`（PRODUCT 固定文案 + 刷新/返回）、成功 `:219-287`。
- 503 在列表走通用错误分支 `:108`，显示“服务暂时不可用，请稍后重试。”并有重试；列表按
  ADR-0024 §10.1 保持只读，规范化关闭态不应对列表返回 503，本评审不把该分支当作禁用态入口。
- 分页 `:274-285` 仅在 `total > PAGE_SIZE` 时显示；`load` 在末页被清空且 `total > 0` 时回到
  第一页（`:88-92`），避免误显示空态；测试 `:171-192` 覆盖。
- 刷新令牌 `:113-116` 让父页面在决定成功后重新拉取；`focusToken` `:118-123` 在返回时把焦点
  交还标题（`:130` 的 `tabIndex={-1}` + CSS `:focus` 轮廓）。

## 详情与决定

### 展示

- 详情 `workflow-approvals-detail.tsx:249-309` 显示流程、节点、状态、发起人、创建/等待时间、
  `dataScopeSummary`、部门、引用文档数、动作、目标工具与 `planSha256`（截断 + `title`）；
  `:311-324` 的“脱敏参数摘要”只渲染 `paramsPreview` 的键值和占位，截断超长值
  （`format.ts:155-191`），不渲染 HTML。
- 类型 `approval-api.ts:43-68` 未声明 `prompt`/`paramsCiphertext`/文档正文；页面没有对应渲染
  路径。服务端 `WorkflowApprovalService.java:743-772` 在列表响应省略 `paramsPreview`，仅详情
  返回；`WorkflowApprovalParamsService.java:89-111` 在服务端做敏感键拒绝、脱敏预览和信封加密，
  前端只消费脱敏摘要。
- 已决定状态 `:326-371` 显示审批人、决定时间、决定与理由；`CANCELLED` 显示取消说明。测试
  `:292-310` 覆盖 REJECTED 记录与无决定入口。

### 决定

- `:132-153` 的 `handleDecide` 对 REJECT 去空白后校验必填并聚焦输入框；批准理由可选；提交值
  去空白。文本域 `:418-432` 使用 `maxLength=500` + `showCount`，与契约 `maxLength:500` 和
  PRODUCT“1–500 字”一致。测试 `:151-191` 覆盖空/空白/正常拒绝与批准。
- `selfApprovalBlocked === true` 时 `:373-381` 显示固定自批文案，`:383` 不渲染决定区；决定
  403 不自动重试（`approval-api.ts:131-141`，测试 `:149-172`），避免重复写 `DENIED` 审计；
  403 统一提示自批/范围固定文案并提供“刷新审批”（测试 `:204-217`）。
  注意：服务端 `WorkflowApprovalService.java:176-188` 对运行发起人直接 404，且
  `selfApprovalBlocked` 当前恒传 `false`，因此前端自批分支是防御性代码；生产自批边界由
  T09 的 404/403 承担，页面不会为发起人提供入口。
- 非 assignee 详情 404（测试 `:251-265`）；决定 409（`:219-234`）与编排关闭 503
  （`:236-249`）使用固定中文提示；409 的“刷新审批”重新拉详情，避免盲目重提。
- 决定成功后 `index.tsx:100-105` 提示结果、关闭详情、递增 `refreshToken`/`focusToken`；
  `workflow-approvals-inbox.tsx:113-123` 重新加载并聚焦标题；index 测试 `:143-176` 覆盖
  批准/拒绝后的返回与列表刷新。

## a11y 与 375px

- 焦点：待办标题在返回时程序化聚焦（`inbox.tsx:118-123,130`），详情就绪后聚焦标题
  （`detail.tsx:118-123,238`），标题有可见焦点轮廓（`approvals.css:29-34`）；详情测试
  `:114-119`、inbox 测试 `:204-210` 断言 `toHaveFocus`。
- 键盘可达性：返回、刷新、批准、拒绝均为原生 `Button`；意见输入通过 `<label htmlFor>` 绑定
  （`detail.tsx:414-438`）；状态使用文字 + 颜色，不只靠颜色（`format.ts:18-32`）；列表使用
  `<ul>/<li>`，元数据使用 `<dl>/<dt>/<dd>`，详情使用 `h1` + `h2` 分节。
- 固定文案：`format.ts:41-61` 与 PRODUCT §12.4 逐字一致（空态、自批、拒绝结果、拒绝理由提示、
  500 上限）；`format.test.ts:41-55` 精确断言。
- 375px：`approvals.css:9-16` 页面 `max-width:1200px`、`min-width:0`、`overflow-wrap:anywhere`；
  `:69-135` 卡片式列表可换行；`:206-222` 在 575px 以下操作按钮占满一行；`:164-176` 参数
  摘要自身 `overflow-x:auto`，不撑破页面。jsdom 测试只验证 375px 下元素可见与按钮可聚焦，
  不能证明真实布局；见 F-002，真实浏览器归 T16a。
- 画布/表单：`git show --stat ea5af615` 只涉及 approvals 目录与任务卡，没有改动画布/表单
  代码，回归风险为零。

## 证据复核

### EV-1089

| 字段      | 值                                                                                   | 结论                                |
| --------- | ------------------------------------------------------------------------------------ | ----------------------------------- |
| command   | `pnpm --filter @riccon/web test:coverage && … typecheck && … lint && … build`        | 覆盖 web 测试/覆盖率/类型/规范/构建 |
| commit    | `b11e557d12f33b13d0fbdee4a7ad56be784b2a11`                                           | ea5af615 的后代；包含 T13 代码      |
| dirty     | `false`                                                                              | clean 主工作树                      |
| exit_code | `0`                                                                                  | 命令链全部通过                      |
| manual    | `false`                                                                              | 可作执行证据                        |
| output    | 18,699 B；SHA-256 `6fa6e108135a80108007f3a9d62c7d9d16640e88fc1e2decea676625f91b673d` | 与日志实际值逐字一致                |
| coverage  | 语句 87.26% / 分支 79.73% / 函数 85.52% / 行 87.14%；approvals 目录行 93.48%         | 高于合同 80/70 门槛                 |
| tests     | 92 files / 829 tests passed；无 FAIL/skipped                                         | 与任务卡 829 一致                   |

- EV-1089 的 `git diff ea5af615..b11e557d -- apps/web/src/pages/workflow/approvals` 为空，
  说明证据运行时的 approvals 代码与代码提交一致；`b11e557d` 是 `d9a729d1` 和当前 HEAD 的祖先。
- 日志显示 `typecheck`、`eslint --max-warnings=0`、`prettier --check`、Umi `build` 全部成功，
  以 `event - Build index.html` 结束；构建产物列表没有 approvals chunk（与 F-001 一致）。
- 旧 EV-1085/EV-1086 的 `dirty` 均为 `true`，虽然 `exit_code=0`、`commit=ea5af615`，但属于并行
  工作树 dirty 期间的旧证据；任务卡 `evidence` 仅为 `[EV-1089]`，`.riccon/state.json` 与
  `M06.json` 未引用 1085/1086，本评审不把它们当作证据（文件保留作为历史）。

### 独立复跑

在当前 HEAD `0f6f2571`（approvals 代码与 ea5af615 完全相同；`git diff ea5af615..HEAD -- approvals`
为空）执行：

| 命令                                                                     | 结果                                                 |
| ------------------------------------------------------------------------ | ---------------------------------------------------- |
| `pnpm --filter @riccon/web exec vitest run src/pages/workflow/approvals` | exit 0；5 files / 55 tests passed；9.15s             |
| `pnpm --filter @riccon/web test`                                         | exit 0；92 files / 829 tests passed；与 EV-1089 一致 |
| `pnpm --filter @riccon/web typecheck`                                    | exit 0                                               |
| `pnpm --filter @riccon/web lint`（eslint + prettier）                    | exit 0；All matched files use Prettier code style    |
| `pnpm --filter @riccon/web build`                                        | exit 0；Webpack compiled successfully                |

- 未使用 skip、retry、`--no-file-parallelism` 或其他为通过追加的参数；定向 55 条覆盖
  API 客户端、格式化、待办、详情、页面集成。复跑结束时 `git status --porcelain` 为空，复跑
  未污染工作树；随后并行 T08 history 工作写入了 5 个未提交后端文件（见“范围与回归”注），
  不归 T13。
- 本次独立复跑没有重跑 coverage（按任务要求“test 或定向 approvals + typecheck/lint/build”），
  coverage 由 EV-1089 覆盖；这不影响证据结论。

## 范围与回归

- `git show --stat ea5af615`：11 个 `apps/web/src/pages/workflow/approvals/**` 文件 +
  `.riccon/tasks/TASK-0234.json`；没有后端、contracts、PRODUCT、AGENTS、构建配置改动。
- `git diff ea5af615..HEAD -- apps/web` 为空；T13 代码提交后没有源码回归。`d9a729d1..HEAD`
  只有 `.riccon/state.json` 与 `TASK-0234.json` 的收口（证据/状态/active_tasks），无生产代码。
- baseline `0511afd3` 是 T08 提交且晚于 ea5af615；`0511afd3..ea5af615` 的 backend 差异是
  T08 交错提交的反向差异，不归 T13。实际 T13 范围按 ea5af615 自身提交内容核对，见 F-003。
- 注（评审窗口观察）：本评审复跑结束后（2026-11-25 19:59 后）工作树出现 5 个未提交的
  `apps/api/src/main/java/com/riccon/api/workflow/history/**` 与对应测试修改（216 行），时间戳
  晚于本评审文件写入，判定为并行 T08 history 工作；不归 T13，也不影响 ea5af615/EV-1089 的
  clean 结论。本评审未修改、未回退这些文件。
- 任务卡当前 `status=review`、owner `riccon/builder-b3`、evidence `[EV-1089]`；未 done，符合
  “builder 只移 review”的边界。

## 路由缺口

- `apps/web/config/routes.ts:54-85` 没有 approvals 路由；`src` 内除 approvals 目录测试外没有
  对 `WorkflowApprovalsPage` 的引用，EV-1089 构建也没有 approvals chunk。页面目前是“已实现但
  生产不可达”的状态。
- `TASK-0245`（M06 T13b，planned，owner builder-b3）已包含
  `/workflows/approvals` 与 `/workflows/approvals/:workflowApprovalId`、workspace layout、
  Studio 子导航与生产 chunk 验收；`TASK-0237`（T16a，planned，owner verifier）包含
  E2E-M06-04/@a11y/375/键盘。按用户指示，该缺口不作为 T13 失败，但 T16a E2E 前必须先完成
  T13b；否则 approvals 页面无法进入 E2E 旅程。

## Findings

- **F-001（P2，accepted，owner `riccon/builder-b3`，due M06 T16a E2E 前）**：路由/导航未接入，
  生产构建无 approvals chunk，E2E-M06-04/@a11y 未执行；由 T13b/TASK-0245 与 T16a/TASK-0237
  承接，T13b 必须先于 T16a。
- **F-002（P3，accepted，owner `riccon/verifier`，due M06 T16a/模块 gate 前）**：jsdom 375px/
  键盘/axe 证据强度不足，真实浏览器归 T16a；CSS 无固定宽度，风险有界。
- **F-003（P3，accepted，owner `riccon/lead`，due M06 模块 gate 前）**：baseline 晚于代码提交，
  baseline..end diff 不能作为 T13 源码范围证据；实际范围已按 ea5af615 核对，无越界。
- 无 open P0/P1/P2 代码 finding；无未決定的阻塞项。

## 未验证与剩余风险

- **真实浏览器 E2E**：`E2E-M06-04` 的待办打开→详情→批准/拒绝→运行继续/终止，以及
  `@a11y`（axe serious/critical=0）、375px 无横向滚动、完整 Tab 顺序和焦点可见，均未在本任务
  执行，归 T16a；本评审不据此判 fail，也不写成已通过。
- **路由**：`/workflows/approvals` 与深链 `/workflows/approvals/:workflowApprovalId` 未接入；
  T13b/TASK-0245 完成前，生产用户无法进入本页。该缺口已作为 F-001 跟踪。
- **编排关闭态**：ADR-0024 §10.1 规定待办/详情只读可查，因此页面不会在进入时收到 WF-503；
  本页在决定 503 后显示固定禁用文案。若 T16a 的 AC-19 期望“进入即显示禁用原因”，需要在
  T13b/T16a 补 runtime-limits 读取；本评审不把 T13 的现有 503 提示判为失败。
- **AC-05 来源标识**：契约 `WorkflowDataScopeSummary` 只提供范围/部门/引用计数，详情没有逐文档
  标题或来源字段；页面只显示计数与脱敏摘要，不显示正文。这是 T09/契约的接口深度限制，不是
  T13 页面越权或漏渲染；若模块要求逐文档来源标识，应在契约层补充。
- **AC-06 审批人全部不可用/发布提示**：属 T07/T08/T09 的服务端解析、发布校验与运行详情；
  T13 只负责审批页前端，不重复验证。
- **后端端点行为**：本评审未重跑 T09 API 全量；403/404/409/503 的服务端语义以 T09 评审和
  代码阅读为依据，页面错误处理测试使用 `ApiError` 替身。

## 下一步

1. Lead 可将 TASK-0234 从 review 推进到 done；F-001/F-002 作为已接受残余进入 T13b/T16a 跟踪，
   F-003 作为模块 gate 行政项跟踪。
2. 先完成 T13b/TASK-0245 路由与 Studio 导航，再执行 T16a/TASK-0237 的 E2E-M06-04、@a11y、
   375px、键盘与 AC-19 禁用态检查。
3. M06 模块 gate 不要用 `0511afd3..d9a729d1` 作为 T13 代码范围证据；应以 ea5af615 的提交
   内容和 EV-1089 为准。
