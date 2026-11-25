---
review_id: REV-TASK-0233-001
module_id: M06
task_id: TASK-0233
reviewer:
  agent: riccon/verifier
  session: ses_decd72014ffekaPhts56XPZWSX
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 3667ee8005827a930288ac84fbe98fc6f78d1390
  - 3175adc907af2dfa28522be307717ee54a8d61ec
head_commit: cbcb3882eb52cfa18b8a336b0c68771c870e65f7
code_commit: 3667ee8005827a930288ac84fbe98fc6f78d1390
evidence_commit: 3175adc907af2dfa28522be307717ee54a8d61ec
baseline_commit: 3175adc907af2dfa28522be307717ee54a8d61ec
actual_code_baseline: e45a69223da3027d91816342853a093ce839f3f2
task_card_baseline_commit: 3175adc907af2dfa28522be307717ee54a8d61ec
task_card_end_commit: 3175adc907af2dfa28522be307717ee54a8d61ec
previous_review: REV-TASK-0230-001
review_scope: >-
  M06 T12b（TASK-0233）前端运行历史与发布/版本/重试/取消的独立任务评审：历史页发起人/所有者/管理员视图、
  流程/版本/状态/时间/发起人过滤、分页与开始时间倒序、审批聚合/counts、空态/清除筛选/403/404/503/通用错误、
  入口到 T12a 运行详情；发布二次确认与 400/409/503、版本列表与选择、停用/启用、旧版本运行、范围显示、
  DEPARTMENT 重开不回退、部门目录失败显式告警、T04 范围口径；取消/重试仅发起人/管理员、409/终态提示、
  成功后刷新与副作用控制；变更面可访问性（对比度、label-in-name）、375px 结构、中文注释与覆盖率；
  EV-1101..EV-1105 的 commit/dirty/hash；write_scope 是否只含 history/**。不评审 T12a 运行详情本身的
  引用/审批/轮询（REV-TASK-0232-001/002）、T12c 路由与导航（TASK-0246）、T12d 编辑页发布范围/客户端
  限制残余（TASK-0247）、T13 审批决定、T16a 真实浏览器 E2E/axe/375px/perf、T16b/T17，也不替代
  M06 模块 review、Product 预验收或用户最终验收。
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
  acceptance 记 false：AC-03/12/13 的前端主路径、过滤分页、审批聚合、发布/版本/停用启用、旧版本运行、
  DEPARTMENT 重开保留范围、部门目录失败显式告警、取消/重试权限与 409 文案均已实现并有 96 个 history
  定向用例与源码核对支撑；但变更面仍有 2 个确定的可访问性 serious 违规（P2 open），分别对应 T12a
  已修复的同类问题（REV-TASK-0232-001 F-001/F-002），会使 M06-AC-19 的 axe 无 serious 断言在 T16a
  失败。build/tests/lint/typecheck/coverage 由 EV-1101..EV-1105（clean 3175adc9，hash 一致）与本次
  独立复跑的 96 个 history 用例、typecheck 支撑；e2e 归 T16a，performance/migration/docs 不在本任务
  范围。真实浏览器导航无法验证：`/workflows/runs` 路由属 T12c（TASK-0246），不据此判 fail，但 T16a
  前必须完成。edit/** 的 F-001/F-002/F-005 残余属 T12d（TASK-0247），不据此判 T12b fail。
findings:
  - id: F-001
    severity: P2
    status: open
    owner: riccon/builder-b3
    due: "T12b 修复轮次（重新提交后复审）"
    title: "运行历史/版本元信息标签 #8c8c8c 白底对比度 3.36:1，低于 WCAG 2.1 AA"
    summary: |-
      `apps/web/src/pages/workflow/history/history.css:192-196` 把 `.workflow-history-meta dt` 与
      `.workflow-history-version-meta dt` 的标签文字设为 `color: #8c8c8c`；这些 dt 使用页面继承的
      14px 常规字重，位于 `.workflow-history-item`/`.workflow-history-version` 的 `background: #fff`
      之上（同文件 136-149 行）。计算对比度：#8c8c8c 对白底为 3.36:1，低于 WCAG 2.1 AA 普通文本
      4.5:1；axe-core 的 `color-contrast` 规则（tags 含 wcag2aa/wcag143，impact=serious）会命中
      发起人、开始时间、结束时间、耗时、审批状态、发布人、发布时间、节点数等标签。
      这与 REV-TASK-0232-001 F-001 是同一类问题；T12a 修复已把 run.css 的元信息改为 `#595959`
      （白底约 7:1），history.css 未同步。期望：改为 antd `colorTextSecondary`/`#595959` 等
      ≥4.5:1 的颜色，并新增读取 history.css 的样式断言（可复用 `run-a11y.test.ts` 的方式），
      避免 jsdom 用例全绿却在 T16a axe 才发现。
    evidence: >-
      apps/web/src/pages/workflow/history/history.css:136-149,192-196；
      apps/web/src/pages/workflow/history/index.tsx:14（样式被页面引入）；
      对比度计算 #8c8c8c/#fff=3.36:1、#595959/#fff=7.00:1；
      node_modules/axe-core@4.14.1 规则 `color-contrast` impact=serious，tags 含 wcag2aa；
      e2e/tests/a11y.ts:11-24 仅放行非 serious/critical。
  - id: F-002
    severity: P2
    status: open
    owner: riccon/builder-b3
    due: "T12b 修复轮次（重新提交后复审）"
    title: "5 个按钮的 aria-label 不含可见文本，label-in-name（WCAG 2.5.3）serious"
    summary: |-
      以下按钮用 `aria-label` 覆盖了可见文字，但可访问名称不包含可见文本；axe-core 的
      `label-content-name-mismatch`（tags 含 wcag21a/wcag253，impact=serious）会命中：
      (1) `release-panel.tsx:777` aria-label=“运行最近发布版本”，可见文字为
          `:781` 的“运行流程”；
      (2) `release-panel.tsx:939` aria-label=“用版本 vN 运行流程”，可见文字为
          `:945` 的“用此版本运行”；
      (3) `release-panel.tsx:1037,1039` 运行弹窗 okText=“提交运行”，aria-label=“确认运行流程”；
      (4) `run-history.tsx:603,606` aria-label=“查看运行详情：{workflowName}”，可见文字为“查看详情”；
      (5) `run-history.tsx:689,692` 重试弹窗 okText=“提交重试”，aria-label=“确认重试失败节点”。
      当前测试按 aria-label 查询按钮（如 `release-panel.test.tsx:399`、`run-history.test.tsx:195`），
      因此测不出可见文本与可访问名称不一致。REV-TASK-0232-001 F-002 已在 run/** 修复同类问题并
      以可见文本查询；T12b 的 history/** 未沿用该口径。期望：让可访问名称包含可见文本
      （如“查看详情：{workflowName}”“用此版本运行（vN）”“运行流程（最近发布版本）”），或在可见
      文本已足够时移除 aria-label；补组件级 label-in-name 断言。
    evidence: >-
      apps/web/src/pages/workflow/history/release-panel.tsx:777,781,939,945,1037,1039；
      apps/web/src/pages/workflow/history/run-history.tsx:603,606,689,692；
      node_modules/axe-core@4.14.1 规则 `label-content-name-mismatch` impact=serious，tags 含
      wcag21a/wcag253；REV-TASK-0232-001 F-002 的修复先例；
      apps/web/src/pages/workflow/run/run-a11y.test.ts 的样式/名称断言方式。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T16a 前（可与 T12c 路由接入一并处理）"
    accepted_reason: >-
      流程 owns 的运行只返回列表元数据、不开放详情是 T04/T05 的服务端口径
      （WorkflowHistoryService.java:251-252 注释明确“流程所有者只拥有列表元数据，不获得详情”）；
      列表对所有者展示“查看详情”会进入 T12a 的 404 结果页，有清晰文案、无数据泄露，AC-12 只要求
      所有者看到元数据。属于 UX 打磨，不阻断 T12b 功能或安全，但 T16a 前建议按 workflowOptions 的
      ownerUserId 隐藏非发起人/非管理员的详情入口，避免用户反复进入 404。
    title: "流程所有者看到的他人运行仍显示“查看详情”，点击必然 404"
    summary: |-
      `run-history.tsx:601-607` 对列表每一行都渲染“查看详情”，但服务端运行详情只对发起人与租户管理员
      可见（`WorkflowHistoryService.java:251-252`），而列表按 AC-12 对流程所有者返回其流程的运行元数据
      （`WorkflowHistoryJdbc.java:309`）。观察：所有者打开他人发起的运行详情必然得到 404；T12a 会显示
      “不可见/不存在”的中文结果页。期望：所有者视图隐藏该入口或明确说明只可查看元数据。
    evidence: >-
      apps/web/src/pages/workflow/history/run-history.tsx:601-607；
      apps/api/src/main/java/com/riccon/api/workflow/history/service/WorkflowHistoryService.java:251-252；
      apps/api/src/main/java/com/riccon/api/workflow/history/persistence/WorkflowHistoryJdbc.java:309。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "M06 gate 前（T16a 版本夹具不覆盖时）"
    accepted_reason: >-
      超过 100 个版本的流程在 v1 演示与 T16a 夹具中不现实；页面已明确显示“共 N 个版本，当前仅显示
      最近 100 个”，服务端仍允许按 versionId 创建运行，且旧版本在常见版本量下均在选择器中。属于
      边界容量限制，不影响 AC-03 的演示路径；若 M06 gate 前有真实超过 100 版的租户，再补分页即可。
    title: "版本选择器只加载最近 100 个版本，超过 100 版的旧版本无法从界面运行"
    summary: |-
      `release-panel.tsx:63` 固定 `VERSION_PAGE_SIZE = 100`，`loadVersions` 只请求第 1 页
      （`:267-295`）；版本数超过 100 时 `:952-956` 只提示“仅显示最近 100 个”，更旧版本没有分页或
      “加载更多”入口，因而无法通过“用此版本运行”选择。AC-03 要求“已发布旧版本在选择器中仍可运行”。
      观察：101 版以上的旧版本只能直接调用 API 运行，不能从本页选择。期望：版本列表分页/加载更多，
      或明确把该限制写入 AC 残余。
    evidence: >-
      apps/web/src/pages/workflow/history/release-panel.tsx:63,267-295,952-956；
      contracts/openapi.yaml:2785-2805（版本列表分页契约）。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "M06 gate 前"
    accepted_reason: >-
      T12b 没有后端改动，web 全量 969 用例由 EV-1101（clean、exit 0）与本次独立复跑的 96 个 history
      用例支撑；EV-1105 的 web 覆盖率数据本身有效且高于门槛。问题只在 note 的表述会让人误以为已跑
      合同完整命令，模块 review 必须重新录制完整契约 EV，不能把 EV-1105 当作模块 tests/coverage
      证据。修正 note 或补一条完整命令即可，不需要重写 T12b 代码。
    title: "EV-1105 note 声称包含 Maven verify/jacoco，但实际命令只跑了 web coverage"
    summary: |-
      `EV-1105.json` 的 command 是 `pnpm -w test -- --coverage`，note 却写“合同 test/coverage 全量
      （Maven verify + jacoco + web coverage）”；日志开头是 `riccon-work@0.1.0 test ... node
      apps/web/scripts/test.mjs -- --coverage`，没有任何 `./mvnw` 或 jacoco 输出。合同
      `commands.coverage` 是 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`。
      观察：EV-1105 只能证明 web 969 用例与 web 覆盖率（All files 87.33 行/80.31 分支；history
      87.04/82.15），不能证明后端 verify。期望：修正 note，或在模块 gate 用完整合同命令重新取证。
    evidence: >-
      .riccon/evidence/TASK-0233/EV-1105.json（command/note）；
      .riccon/evidence/TASK-0233/EV-1105-2026-11-25T22-39-22-611+08-00-pnpm-w-test-coverage.log:1-14；
      AGENTS.md commands.coverage。
evidence:
  - EV-1101
  - EV-1102
  - EV-1103
  - EV-1104
  - EV-1105
reviewed_evidence:
  - EV-1101
  - EV-1102
  - EV-1103
  - EV-1104
  - EV-1105
created_at: "2026-11-25T22:54:00+08:00"
---

# REV-TASK-0233-001：任务 TASK-0233（M06 T12b）独立评审

## 结论

**fail**。T12b 的功能面（运行历史、发布/版本、停用/启用、旧版本运行、重试/取消、DEPARTMENT 范围重开）实现完整，96 个定向用例与 969 个 web 全量用例通过，范围没有越界；但变更面仍有 **2 个 P2 open 的可访问性 serious 违规**：元信息标签对比度 3.36:1，以及 5 个按钮的 label-in-name 失败。两者都对应 T12a 已修复的同类问题（REV-TASK-0232-001 F-001/F-002），并会让 M06-AC-19 的 axe 断言在 T16a 失败，因此不能判 pass。另有 3 个 P3 accepted，均为有界残余。

## AC 对账

| AC        | 本任务结论                             | 依据                                                                                                                                                                                                                 |
| --------- | -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M06-AC-03 | **pass（任务级；E2E defer T16a）**     | 发布二次确认、版本列表/选择、旧版本运行、停用/启用、范围显示、DEPARTMENT 重开保留最近范围、部门目录失败显式告警均已实现；T04 范围规则由服务端最终裁决，前端不绕过。真实 E2E-M06-02/05 归 T16a。                      |
| M06-AC-12 | **pass（任务级；E2E/SEC defer T16a）** | 列表展示流程/版本/状态/发起人/开始结束/耗时/审批聚合；流程/版本/状态/时间/发起人过滤、分页、服务端开始时间倒序、空态清除筛选、403/404/503；可见性由服务端过滤，前端不展示名称或数量。E2E-M06-08/SEC-WF-003 归 T16a。 |
| M06-AC-13 | **pass（任务级；E2E defer T16a）**     | 重试入口只对 FAILED、只选最新尝试失败节点；取消只对活跃状态；仅发起人/管理员可见操作；409/403/404/503 中文下一步；成功后刷新列表。详情展示本身属 T12a。                                                              |
| M06-AC-19 | **fail**                               | 加载/空/错误/无权限/禁用/成功态与 375px 结构、焦点样式已实现，但 axe serious 断言会因 F-001/F-002 失败；真实浏览器 axe/375px 归 T16a。                                                                               |

## 历史页

- 视图：租户管理员用 `view=ALL` 加载流程候选，其他人合并 `MINE+RUNNABLE` 并按 ID 去重；列表可见性完全依赖服务端（`index.tsx:81-133`、`format.ts:257-265`）。发起人、流程所有者、管理员三种视图的服务端过滤在 T04/T05 已有实现（`WorkflowHistoryJdbc.java:309` 同时按 requester/owner 过滤）。
- 过滤：流程、版本号（≥1 整数）、状态、时间范围（DatePicker 值转北京时间 `+08:00`，客户端先校验反向区间）、发起人 UUID；`只看我发起的` 优先覆盖手工发起人。空值全部省略，不会发送 `status=ALL`（`format.ts:179-217`；`run-history.test.tsx:245-291`）。
- 分页/倒序：`PAGE_SIZE=20`，服务端排序；最后一页因动作变空且总数非 0 时回到第 1 页，避免误显示空态（`run-history.tsx:153-173`；测试覆盖翻页）。
- 审批聚合：状态 Tag + `待审批/已批准/已拒绝` 计数；无审批节点显示固定文案（`format.ts:432-438`；`run.test` 夹具断言）。
- 空/错误：空态显示固定文案、当前筛选摘要与“清除全部筛选”；403/404/503/通用错误分别有结果页、重试与返回 Studio（`run-history.tsx:454-541`）。
- 入口到 T12a：`查看详情` 导航到 `/workflows/runs/{id}`（`index.tsx:147-150`），路由注册归 T12c。

## 发布/版本

- 发布：按钮受 `detail.canPublish` 与范围评估双重控制；二次确认弹窗展示草稿版本、范围与部门；400/403/404/409/503 有中文下一步；资源级幂等返回既有版本时用 info 提示，不误报新版本（`release-panel.tsx:369-417,963-1007`）。
- 范围：`buildScopeOptions` 与 `evaluatePublishScope` 对齐 `WorkflowPublishScopeValidator`：租户管理员禁用本部门、非管理员禁用本租户；部门目录失败/加载中显式禁用并说明；DEPARTMENT 重开时以 `latestVersion` 初始化，不因目录为空回退 ONLY_SELF；目录失败但最近范围匹配时保留并警告，最终由服务端复核（`format.ts:278-404`；`release-panel.test.tsx:296-350`；后端校验 `WorkflowPublishScopeValidator.java:48-84`）。
- 版本：列表显示版本号、发布人、发布时间、节点数、范围与“当前发布”；`versionsTotal>100` 有显式提示；旧版本“用此版本运行”提交指定 `versionId`；停用/启用带确认、成功刷新详情/版本/候选（`release-panel.tsx:267-328,434-456,859-959`）。
- 创建运行：客户端按契约限制预校验 JSON 对象、键名、16 键、64 KiB；429/503/409 有中文提示；成功后导航详情（`format.ts:626-657`；`release-panel.tsx:483-507`）。

## 重试/取消

- 可见性：`operable = workflow.run 权限 && (发起人 || 租户管理员)`；取消只对 PENDING/RUNNING/WAITING_APPROVAL，重试只对 FAILED（`format.ts:449-467`；`run-history.tsx:551,600-625`；后端 `WorkflowRunService.java:221-320` 的口径一致）。
- 重试节点：先读详情，要求 `status=FAILED`，按节点取最新 attempt，只有最新 attempt 仍 FAILED 才进入选择器；没有可重试节点或状态已变化时不提交（`format.ts:478-487`；`run-history.tsx:269-318`；`run-history.test.tsx:349-407`）。
- 409/终态：取消 409 与重试 409 各有文案并保留弹窗；终态不展示按钮；成功后 `load()` 刷新当前页（`format.ts:558-581`）。
- 副作用：发布/取消/重试均有 Idempotency-Key；后端 publish 资源级幂等、cancel 按 CANCELLED 幂等、retry 按状态机重放返回 202；测试覆盖 202 空响应与 409（`history-api.test.ts:37-51`；`WorkflowRunService.java:227-240,292-306`）。未发现重复版本的路径。

## 测试与证据复核

| EV      | 命令                                      | commit   | dirty | exit | 复核结果                                                                                                                                         |
| ------- | ----------------------------------------- | -------- | ----- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| EV-1101 | `pnpm --filter @riccon/web test`          | 3175adc9 | false | 0    | 104 文件 969 用例通过；日志 sha256 与 EV 一致。note 写“103 文件 965+”，与日志的 104/969 有小差异，不影响结论。                                   |
| EV-1102 | `pnpm --filter @riccon/web run typecheck` | 3175adc9 | false | 0    | 通过；本次独立复跑同命令 exit 0。                                                                                                                |
| EV-1103 | `pnpm --filter @riccon/web run lint`      | 3175adc9 | false | 0    | ESLint `--max-warnings=0` + Prettier 通过。                                                                                                      |
| EV-1104 | `pnpm --filter @riccon/web run build`     | 3175adc9 | false | 0    | 构建通过；页面未注册路由，chunk 由 T12c 接入，符合任务边界。                                                                                     |
| EV-1105 | `pnpm -w test -- --coverage`              | 3175adc9 | false | 0    | 969 用例通过；All files 87.33 行/80.31 分支，history 87.04/82.15，高于合同 80/70。命令只覆盖 web，note 的“Maven verify + jacoco”不实，见 F-005。 |

- 五个 EV 的日志 sha256 与 JSON 记录逐条一致，commit 均为 3175adc9，dirty=false；3667ee80 是 3175adc9 的祖先，`git diff 3175adc9..HEAD -- apps/web/src/pages/workflow/history` 为空，证据对应当前代码。
- 本次独立复跑：`pnpm --filter @riccon/web exec vitest run src/pages/workflow/history` → 5 文件 96 用例全部通过（17.51s）；`pnpm --filter @riccon/web run typecheck` → exit 0。未重跑 969 全量与 build/lint，由 clean EV 支撑。复跑时工作区已出现 T12d（TASK-0247）的未提交 `edit/**` 改动（并发任务，不在本任务写范围）；history 定向用例不导入 `edit/**`，typecheck 在该工作区状态通过，但 T12b 的权威 clean typecheck 证据仍以 EV-1102 为准。
- 覆盖率：history 目录 87.04 行/82.15 分支；新增源码函数均有中文 JSDoc，未发现空实现、吞错或无断言的测试；测试断言了请求参数、状态机与导航，不是快照凑数。

## 范围与边界

- T12b 代码提交 `3667ee80` 只改了 11 个文件，全部位于 `apps/web/src/pages/workflow/history/**`：`format.ts/test`、`history-api.ts/test`、`index.tsx/test`、`release-panel.tsx/test`、`run-history.tsx/test`、`history.css`。未改后端、contracts、PRODUCT、AGENTS、T12a `run/**`、T12c 路由或 T12d `edit/**`。
- 任务卡把 baseline/end 都设为 `3175adc9`，因此自动 write_scope 差异为空；本次改为手工核对 `3667ee80` 相对其父 `e45a6922` 的文件列表，结论为只含 history/**。这不影响任务功能，但说明该任务的自动范围校验不可依赖，模块审计需保留手工 diff。
- `TASK-0246`（T12c）未接入 `/workflows/runs` 与 `/workflows/runs/:workflowRunId`，因此本评审无法在真实路由中打开页面；按任务边界不判 fail，但 T16a 前必须完成。
- `TASK-0247`（T12d）承接 edit/** 的 REV-TASK-0230-001 F-001/F-002/F-005 残余；REV-TASK-0230-001 F-001 的 history 发布范围部分已在本任务关闭，模块 gate 前仍需 T12d 关闭编辑页部分。
- `/workflows/runs` 入口未接入也意味着生产 build 不会包含 history chunk；这是 T12c 的验收项，不是 T12b 的缺口。

## 未验证

- 真实浏览器 E2E-M06-02/05/08、axe、375px 实际无横向滚动、完整键盘旅程与性能预算未运行（T16a）。本次没有用 jsdom 测试冒充 axe 通过。
- 未重跑 Maven/后端集成测试；T12b 未改后端，列表可见性、范围校验与重试状态机依据契约与 T04/T05 代码核对，未独立复验。
- 页面尚未注册路由，无法验证真实导航、面包屑与生产 chunk 加载（T12c）。
- Product 预验收与用户最终验收不在本评审范围。

## 剩余风险

- F-001/F-002 是确定的 axe serious；若不在 T16a 前修复，M06-AC-19 会失败，且会重复 T12a 的返工路径。修复很小：history.css 改 `#595959`，按钮可访问名称包含可见文本或移除多余 aria-label，并补组件级断言。
- REV-TASK-0232-002（T12a）仍有 1 个 open P2（审批脱敏参数 pre 的可聚焦性），与本任务无直接关系，但模块 gate 前必须关闭；T12a 当前仍为 in_progress。
- T12d 未完成前，REV-TASK-0230-001 F-001 在编辑页仍可能把 DEPARTMENT 静默回退，模块 gate 不能放行。
- F-004 的 100 版上限在极端租户会成为“旧版本无法从界面运行”的 AC-03 残余，T16a 夹具若超过 100 版会暴露。

## 回执

- review_id：REV-TASK-0233-001
- verdict：fail
- findings：P0=0，P1=0，P2=2 open（F-001 对比度、F-002 label-in-name），P3=3 accepted（F-003 所有者详情 404、F-004 版本 100 上限、F-005 EV-1105 note/命令不一致）
- 关键结论：功能与范围通过；AC-19 变更面可访问性未通过，需修复 2 个 P2 后复审。
- 证据：EV-1101..EV-1105（clean 3175adc9，hash 一致）；独立复跑 96 个 history 用例 + typecheck。
