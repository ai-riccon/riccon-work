---
review_id: REV-TASK-0230-001
module_id: M06
task_id: TASK-0230
reviewer:
  agent: riccon/verifier
  session: ses_deffe8f29ffetV3lgqHC8VKNxX
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - ebc3c13bd7bf4af3b757a39871d1dbc5f4d61868
  - 53a3c841cbff1a2762e5fe66f89a8bfb140fa3fc
  - e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
head_commit: 90d8d406b4d9b243f5678ac338e50016d9994ea9
code_commit: 53a3c841cbff1a2762e5fe66f89a8bfb140fa3fc
evidence_head: e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
baseline_commit: 70bcccb682d16dab7dd82f0a000e82af15a115c2
related_reviews:
  - REV-TASK-0220-001
review_scope: >-
  M06 T10（TASK-0230）前端流程列表与表单编辑器的独立任务评审：列表我的/可运行/全部视图、状态筛选、分页、
  草稿与发布版本摘要、空/加载/错误/403/503 禁用态、中文文案与 375px；编辑器表单主路径、节点增删/顺序/
  连线/条件 TRUE-FALSE/审批 assignee/actionKind/targetNodeKey/模型/工具/检索/结果、保存草稿与 409 另存
  副本、校验面板调用 /validate、发布入口调用 /publish、400 展示，以及 REV-TASK-0220-001 F-003 的关闭核对。
  不判 T10b（TASK-0242）路由与导航；不替代 M06 模块 review、T16a E2E/a11y、Product 预验收或用户验收。
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
  - accessibility
dod_note: >-
  acceptance 记 true 但按 **pass (with residual)** 解释：AC-01/02/19/20 在任务范围内由 7 个 workflow
  测试文件（76 用例）与源码核对支撑；真实浏览器 E2E-M06-01/09、键盘全流程与 axe 属 T16a，本评审未运行，
  因此本结论不是模块级 AC pass。AC-02 的保存路径残留 F-004（服务端-only 校验失败时校验面板不可达，
  但 400 detail 已含逐项 node/field 摘要）。build/tests/lint/typecheck 由 EV-1038/EV-1036 与评审时
  独立复跑支撑；evidence 为 true（EV-1036/1038 + T10b EV-1048/1049 覆盖最终 T10 代码），任务级证据
  归属残留见 F-006。coverage/e2e/security/performance/accessibility 由模块 gate/T16a 承担，本任务未跑。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-b3（T10/T12b 发布范围 UI）
    due: T12b（TASK-0233）/M06 gate 前
    title: "重新打开 DEPARTMENT 范围流程时，异步目录兜底把发布范围重置为 ONLY_SELF，默认范围被静默改变"
    summary: >-
      `apps/web/src/pages/workflow/edit/index.tsx:566-569` 在加载详情时按 `latestVersion` 恢复
      `runScope`/`departmentId`，但同一页的兜底 effect `:652-660` 在 `managerDepartments.length===0`
      时立即重置为 `ONLY_SELF` 并清空部门；部门目录 effect `:598-640` 要等 `phase==='ready'` 之后才异步
      拉取，首次渲染必然先看到空部门列表。观察：打开一个已发布范围为“本部门”的流程（部门负责人会话），
      发布区的分段控件初始显示“仅自己”，而不是最近版本的“本部门”；若直接点发布，新版本会以 ONLY_SELF
      发布，部门成员从此不可运行。期望：等目录加载完成或部门状态明确后再回退，保留最近发布范围；仅在
      确实不再负责该部门/部门停用后回退并提示。测试未覆盖 `latestVersion.runScope='DEPARTMENT'` 的加载
      场景（`edit/index.test.tsx` 的部门用例为 `latestVersion:null` 后手动选择）。
    accepted_reason: >-
      回退方向是收窄范围（ONLY_SELF），不会扩大可见性；发布弹窗仍显示当前范围，用户可手动重选，服务端
      也按提交范围重新判定，无越权或数据泄露。T10 任务卡 AC 不直接包含 AC-03 的版本范围展示，但该默认值
      会改变再次发布的用户可见结果，必须在 T12b/M06 gate 前修复并补 DEPARTMENT 重开用例，不能由 T16a 的
      AC-03 用“用户手动改回”掩盖。
    evidence: >-
      apps/web/src/pages/workflow/edit/index.tsx:566-569,598-660；
      apps/web/src/pages/workflow/edit/index.test.tsx:108-148,365-377
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: M06 gate 前（可在 T16a 前补）
    title: "客户端仍可提交后端会 400 的输入：IN 值类型/20 上限、审批人 50 上限、另存副本名 200 上限"
    summary: >-
      REV-TASK-0220-001 F-003 的 position x/y 与空字符串/空白已关闭，但同类“客户端放行、后端拒绝”仍有
      三处：(1) `edit/node-form.ts:441-457` 对 `operator=IN` 只检查非空，不校验每个值是否匹配
      `valueType`，也不限制 20 项；`applyNodeFormValues:254-261` 对无法解析的 NUMBER/BOOLEAN 保留原文
      字符串；`edit/validation.ts:543-551` 同样只查非空。契约 `contracts/openapi.yaml:8119-8124` 有
      `maxItems:20`，后端 `WorkflowValidator.java:890-897` 会逐个校验类型并返回
      `CONDITION_BRANCH_INVALID`。(2) `edit/node-form.ts:392-398` 与 `validation.ts:469-477` 对
      `assigneeUserIds` 只检查 UUID/非空，不限制契约 `contracts/openapi.yaml:8178-8180` 与
      `WorkflowGraphNodeConfig.java:117-121` 的 50 人上限。(3) `edit/index.tsx:954-962` 用
      `${name} 副本` 生成副本名，未截断到 `WorkflowDuplicateRequest.name` 的 200 字符上限
      （`contracts/openapi.yaml:8855-8862`）；名称输入允许 200 字符，冲突后另存副本可能直接 400。
      观察：上述输入通过页面校验后被服务端 400 拒绝；期望：字段级中文提示或截断，保持 F-003 的同一严格度。
      现有测试未覆盖这三条边界。
    accepted_reason: >-
      都是边界输入（IN 集合类型/数量、51 个审批人、198+ 字名称），服务端 fail-closed，不产生草稿、版本或
      越权数据；保存 400 的 detail 含 code/node/field 摘要，用户可定位后修正。主路径（普通条件、少量审批人、
      常规名称）已通过。由于 F-003 的验收目标就是避免这类客户端保存被拒，M06 gate 前仍应补齐上限与类型
      校验，避免 T16a 用服务端错误路径当通过。
    evidence: >-
      apps/web/src/pages/workflow/edit/node-form.ts:254-261,392-398,441-457；
      apps/web/src/pages/workflow/edit/validation.ts:469-477,543-551；
      apps/web/src/pages/workflow/edit/index.tsx:954-962；
      contracts/openapi.yaml:8119-8124,8178-8180,8855-8862；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowValidator.java:890-897；
      apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphNodeConfig.java:117-121
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: M06 gate 前（T11 共用图操作时）
    title: "removeNode 清理了 END.resultFrom 与 APPROVAL.targetNodeKey，但漏掉 CONDITION.left.nodeId"
    summary: >-
      `apps/web/src/pages/workflow/edit/graph.ts:173-194` 的 `removeNode` 注释要求“清空 END.resultFrom
      与 APPROVAL.targetNodeKey 中指向该节点的值，避免留下悬空配置”，实现也只处理这两类引用；删除一个被
      CONDITION 节点 `condition.left.nodeId`（source=NODE_OUTPUT）引用的上游节点后，该引用仍保留。
      观察：删除检索/模型节点后，条件节点的配置仍指向已删除节点；`validateGraph` 会报
      `CONDITION_BRANCH_INVALID`（`validation.ts:578-610`），保存被阻止，但节点配置 Select 会显示一个
      不在选项中的旧 ID，用户必须重新打开条件节点修正。期望：删除时一并清空 `condition.left.nodeId`
      （或明确改为 RUN_INPUT），保持三类引用清理一致。`graph.test.ts` 只覆盖 resultFrom/targetNodeKey，
      未覆盖条件引用。
    accepted_reason: >-
      悬空引用会被客户端校验阻断，不会静默写入无效草稿，也不涉及权限/数据泄露；影响是删除后的额外修正
      步骤。T11 画布会复用同一 `removeNode`，因此必须在 T11/M06 gate 前修复并补测试，不能把带悬空引用的
      图留给画布侧。
    evidence: >-
      apps/web/src/pages/workflow/edit/graph.ts:173-194；
      apps/web/src/pages/workflow/edit/validation.ts:578-610；
      apps/web/src/pages/workflow/edit/graph.test.ts:70-99
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: T16a/M06 gate 前
    title: "服务端-only 校验失败时校验面板不可达：保存先于 /validate，400 后只显示 ProblemDetail 文本"
    summary: >-
      `apps/web/src/pages/workflow/edit/index.tsx:846-877` 的 `handleValidate` 先 `persistIfNeeded(false)`；
      若图能通过客户端校验但被服务端保存校验拒绝（典型：所选模型不在 REPORT 路由链，客户端无法从
      `/models` 得知；或工具目录加载失败时的授权校验），保存返回 400，`persistIfNeeded` 返回 false，
      函数直接退出，不会调用 `POST /validate`，面板回到 idle；页面顶部只显示 400 detail。后端
      `WorkflowValidationException.java:48-69` 的 detail 已含最多 20 条 code/node/field/message，因此
      用户仍能看到逐项信息，但不是中文面板、也不可“定位到节点”。契约 2710-2719 的 `/validate` 校验的是
      已持久化草稿，保存失败后调用它会校验旧草稿，所以这不是简单补一次请求能解决；期望明确“保存 400 时
      解析 detail 或提供图校验入口”的产品口径。发布路径（先 validate 再 publish）能正常展示面板。
    accepted_reason: >-
      不在 T10 验收的 golden path（校验/发布正常路径均工作）；服务端 fail-closed，保存失败不产生草稿变更/
      版本；400 detail 已逐项给出 node/field，用户可手动定位修正；发布入口仍会先调用 `/validate` 并展示
      中文面板。作为有界残余接受，但 AC-02 的“保存或发布逐项指出问题”在保存 400 分支只算部分满足，T16a
      应按真实错误路径记录；若 T16a 要求保存即得中文定位，需在 gate 前改契约或 UI。
    evidence: >-
      apps/web/src/pages/workflow/edit/index.tsx:794-877；
      apps/web/src/services/api/errors.ts:150-190；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowValidationException.java:48-69；
      contracts/openapi.yaml:2710-2719
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/lead（角色权限口径）/ riccon/builder-b3（UI 提示）
    due: M06 gate 前（T12b/T16a）
    title: "运行范围 UI 与 WorkflowPublishScopeValidator 的角色口径不完全一致，部门目录失败也被静默忽略"
    summary: >-
      (1) `edit/index.tsx:642-649,1088-1096` 只按 `department.managerUserId===当前用户` 启用“本部门”，
      没有排除租户管理员；后端 `WorkflowPublishScopeValidator.java:68-72` 明确拒绝租户管理员发布
      DEPARTMENT。租户管理员同时被任命为某部门负责人时（现有编辑者测试会话就是 TENANT_ADMIN +
      部门 managerUserId），UI 会允许选择“本部门”，随后 validate/publish 收到 400。期望 UI 在
      `canManage` 时禁用本部门，或后端口径调整并写 ADR。(2) `edit/index.tsx:604-634` 对
      `/departments` 失败只 `setDepartments([])`，没有像模型/工具那样加入 `failed` 警告；仅有
      WORKFLOW_EDITOR 角色（RoleDefinitions.java:126-132 不含 department.read）的部门负责人无法加载
      部门目录，UI 显示“只有部门负责人可以选择本部门范围”，与事实不符，也无法发布本部门（后端实际允许
      部门负责人）。期望把部门失败纳入目录告警，并提供兜底/明确角色权限。
    accepted_reason: >-
      服务端范围校验是最终守卫：非法范围返回 400，不产生版本、不扩大可见性；AC-01 的目标人物通常是
      MEMBER + WORKFLOW_EDITOR（有 department.read），主路径可用；租户管理员 + 部门负责人属于边界组合。
      但 ADR-0024 §6.1 明确 WORKFLOW_EDITOR 可发布本部门，因此 M06 gate 前必须统一角色/UI 口径或记录
      决策，不能仅靠服务端 400 兜底。
    evidence: >-
      apps/web/src/pages/workflow/edit/index.tsx:604-660,1088-1096,1441-1479；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowPublishScopeValidator.java:60-83；
      apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:126-132；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:470-480
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-b3（补录）/ riccon/lead（done 前复核）
    due: T10 done 前
    title: "TASK-0230 自身证据止于 ebc3c13，未把最终修复 53a3c84 绑定到任务卡；T10b 的全量 EV 覆盖但归属不同"
    summary: >-
      TASK-0230.evidence 只有 EV-1036/EV-1038，两者 commit 都是 `ebc3c13`；最终 T10 代码含
      `53a3c84`（antd 6 showSearch 修复）。该提交在 e5f5586 之前，且 `git diff
      53a3c84..e5f5586 -- apps/web/src/pages/workflow/{list,edit}` 为空；TASK-0242 的
      EV-1048/EV-1049 在 clean `e5f5586` 上跑全量 web 82 文件/736 用例与 typecheck/lint/build。
      本评审在当前 HEAD `90d8d40` 独立复跑 82/736、workflow 7 文件/76 用例、typecheck/lint/build
      全部通过。观察：功能证据足够，但 TASK-0230 任务卡没有把最终代码提交绑定到自己的 EV，若 Lead 直接
      `done` 会留下“任务证据提交早于 end_commit”的追溯缺口；模块 review 还必须覆盖 EV-1048/1049。
      期望：Lead 在 done 前把 EV-1048/1049 记入 TASK-0230.evidence 并写清跨任务覆盖，或补一条
      T10-owned clean EV（commit 53a3c84/其后）。
    accepted_reason: >-
      53a3c84 只改 `edit/index.tsx` 3 处 `showSearch` 调用（+3/-6），属于 T10 write_scope；e5f5586 的
      clean 全量 web EV 已覆盖该提交，且本评审独立复跑确认无回归，所以不构成功能/假绿风险。仅证据归属
      与任务闭环不完整，T10b 评审也会复核同一 EV；作为有界 P3 接受，done 前补录即可。
    evidence: >-
      .riccon/tasks/TASK-0230.json:18-21；
      .riccon/evidence/TASK-0230/EV-1036.json；.riccon/evidence/TASK-0230/EV-1038.json；
      .riccon/evidence/TASK-0242/EV-1048.json；.riccon/evidence/TASK-0242/EV-1049.json；
      git diff --name-only 53a3c84..e5f5586 -- apps/web/src/pages/workflow/list apps/web/src/pages/workflow/edit
reviewed_evidence:
  - EV-1036
  - EV-1038
  - EV-1048
  - EV-1049
evidence:
  - EV-1036
  - EV-1038
  - EV-1048
  - EV-1049
test_impact: >-
  EV-1036（ebc3c13，clean，exit 0）：`pnpm --filter @riccon/web test`，81 文件/726 用例通过，日志
  sha256 2615d551…82f8d5 与 EV 记录一致；EV-1038（同 commit，exit 0）：typecheck+lint+build 通过，
  sha256 631a4776…1bbfc 一致。EV-1048（e5f5586，clean）：82 文件/736 用例通过；EV-1049（同 commit）：
  typecheck+lint+build 通过；两者 sha256 c592ea37…a9892 / f36946c9…e4cf 与记录一致。评审时在当前
  HEAD 90d8d40 独立执行：`pnpm --filter @riccon/web test` = 82 files/736 tests passed；`
  pnpm exec vitest run src/pages/workflow` = 7 files/76 tests passed；`typecheck`、`lint`、
  `build` 均 exit 0。7 个 workflow 测试文件无 skip/todo/xit；未使用 `--no-file-parallelism` 等
  为通过追加的参数。coverage/E2E/a11y/perf/security 未跑（T16a/模块 gate）。
notes: >-
  基线说明：委托消息写“baseline 9fdfcff”，但 git 图谱中 9fdfcff 是 ebc3c13 的子提交（T05 记录
  EV-1037），不是 T10 父提交；任务卡 baseline_commit=70bcccb 与 `git log` 一致，本评审按 70bcccb
  核对 T10 自身两个提交。范围核对：ebc3c13 的 16 个文件与 53a3c84 的 1 个文件全部落在
  `apps/web/src/pages/workflow/list/**` 与 `edit/**`，无 apps/api、contracts、PRODUCT、AGENTS、
  routes 或 M06b 改动。gate 观察（未运行 gate check）：当前 `.riccon/state.json` 的 gate reason 把
  apps/api 与 routes 文件算作 TASK-0230 越界，这是任务 baseline 70bcccb 累积到 HEAD 的差异
  （T05/T04b/T10b 的提交），不是 T10 自身文件；Lead 在模块 gate 前需要重定 baseline 或补 scope
  归因证据，否则会误报。T10b 路由/导航由 REV-TASK-0242 系列另行评审，本评审未判。
created_at: "2026-11-25T08:18:00+08:00"
---

# REV-TASK-0230-001：M06 T10 前端流程列表/表单编辑器独立评审

## 结论

**Pass（带 accepted residual）**。T10 在页面范围内完成了列表与表单编辑主路径：我的/可运行/全部视图、状态筛选、分页、草稿与版本摘要、空/加载/错误/403/503 态、中文文案与 375px 单列布局；编辑器覆盖节点增删/顺序、连线与条件 TRUE/FALSE、审批人/动作/目标、模型/工具/检索/结果配置、保存草稿、409 冲突另存副本、校验面板调用 `/validate`、发布入口调用 `/publish` 与 400 展示。REV-TASK-0220-001 F-003 的 position x/y 与空字符串/空白清理已在 `normalizeGraphForSave` 落地，但仍有边界输入会把客户端校验通过后的请求交给后端 400（F-002）。

独立复跑确认 T10 最终代码（53a3c84，包含 ebc3c13）在 clean `e5f5586` 的全量 web EV 中覆盖：82 文件/736 用例、typecheck/lint/build 通过；评审时在当前 HEAD `90d8d40` 再跑得到同一结果，且 7 个 workflow 测试文件 76/76 通过、无 skip。无 open P0/P1；保留 1 个 P2（DEPARTMENT 范围重开被重置为 ONLY_SELF）和 5 个 P3（客户端边界校验、条件悬空引用、服务端-only 校验面板、范围角色口径、任务证据归属），均有 owner/due/accepted_reason。

按任务要求未运行 E2E-M06-01/09、axe、coverage、perf、security 或 `gate check --module M06`；未修改生产源码、测试、契约、PRODUCT、AGENTS 或 `.riccon` 状态，也未把任务标 done。本评审不替代 M06 模块 review、Product 预验收或用户验收。

## 评审范围与提交

| 项          | 值                                                                                                                         |
| ----------- | -------------------------------------------------------------------------------------------------------------------------- |
| baseline    | `70bcccb682d16dab7dd82f0a000e82af15a115c2`（任务卡 baseline；T10 两个提交的父链）                                          |
| T10 代码    | `ebc3c13bd7bf4af3b757a39871d1dbc5f4d61868`（16 文件，+6636）与 `53a3c841cbff1a2762e5fe66f89a8bfb140fa3fc`（1 文件，+3/-6） |
| 证据提交    | `e5f5586858a0d7c3cd42ac050a2f95e2a41a232e`（T10b EV-1048/1049；T10 文件在该提交前无变化）                                  |
| 评审时 HEAD | `90d8d406b4d9b243f5678ac338e50016d9994ea9`（T10b 文档 chore；T10 业务代码未变）                                            |
| 设计依据    | `docs/PRODUCT.md` M06-AC-01/02/19/20、`contracts/openapi.yaml` Workflow 段落、ADR-0024 §6/§7、`docs/CODING-STANDARDS.md`   |

## 列表页

- **视图与权限**：`Segmented` 提供“我的流程/可运行/全部流程”；“全部流程”仅在 `workflow.manage` 时出现，与契约 `view=ALL` 仅租户管理员可用一致。无 `workflow.read` 时进入 403 态并给出“联系租户管理员申请”；无 `workflow.edit` 时隐藏创建入口并显示说明。
- **筛选与分页**：状态筛选为“全部状态/启用/已停用”，切换视图或状态会回到第 1 页；`Pagination` 在 `total>20` 时显示，请求固定 `page/size=20`，API 客户端测试断言 query 为 `view/status/page/size`。
- **摘要与文案**：每项显示名称、草稿/已停用/已发布/有未发布修改标签、发布版本 `vN`、`草稿 vN · 发布 vM/尚未发布`、说明与 +08:00 更新时间；主按钮名可点击，操作按钮为“打开编辑/查看流程”。
- **状态覆盖**：`Skeleton`（`aria-busy`+中文 aria-label）、`Result 403` 带重试、`Result warning` 编排关闭“已有运行记录仍可查看”、`Result error` 带重试、按角色区分的空态文案（有编排权限引导模板/空白；无权限引导联系管理员或流程所有者）、成功列表；创建弹窗支持空白/内置模板（固定 `01990000-…0601`）与 400 错误展示。
- **375px**：卡片式列表、工具栏/卡片/操作区允许换行、`overflow-wrap:anywhere`，窄屏操作按钮占满一行；无固定宽度表格。
- **私有草稿**：页面不本地合并或缓存列表，完全渲染服务端过滤结果；“可运行”由服务端排除未发布草稿。未发现客户端泄露他人草稿名称、数量或图数据的路径。

## 编辑器

- **表单主路径**：流程名称/说明、节点列表（类型/名称/摘要/待修正或已配置）、节点配置、连线、校验结果与发布卡片；没有画布也能完成节点/连线/参数/校验/保存/发布。
- **节点**：开始节点唯一（选项禁用 + 校验）；增删、上移/下移写回 `graph.nodes` 顺序；删除清理关联边、`END.resultFrom`、`APPROVAL.targetNodeKey`（`CONDITION.left.nodeId` 残留见 F-003）；新建节点生成合法 ID 与有限 x/y。
- **节点配置**：检索（queryTemplate/topK）、模型（prompt/outputKind/class 标签/modelId）、MCP 工具（toolId/argsTemplate JSON）、审批（assigneeType ROLE/USER、assigneeRole、assigneeUserIds、actionKind、targetNodeKey、直接后继校验）、条件（RUN_INPUT/NODE_OUTPUT、受控输出字段、算子/值类型、单值/IN 集合）、结束（resultFrom/resultTitle）；表单值经纯函数校验与按类型清洗后才写回，空字符串/不相关字段不会污染图。
- **连线与条件**：源/目标/分支必填，自连/重复/非条件分支误用被拒；条件节点出边显示 TRUE/FALSE；条件节点必须恰好一条 TRUE 与一条 FALSE；与后端 `WorkflowValidator` 的结构口径一致。
- **校验**：`handleValidate` 先保存本地草稿（必要时）再调用 `POST /validate?runScope=…&departmentId=…`；服务端 issues 映射为中文文案、问题码标签与“定位到节点”按钮；`aria-live` 通知结果。DEPARTMENT 缺部门时先给中文提示、不发请求。
- **发布**：发布弹窗确认范围后，先保存/本地校验 → `/validate` → `/publish`；校验不通过时阻断 publish 并展示问题计数；400 在弹窗内展示；成功后更新 `publishedVersionNo/latestVersion/hasUnpublishedChanges` 并提示版本与范围。
- **409**：保存 409 打开冲突弹窗，提供“另存副本”（服务端 duplicate 后把本地图写入副本并跳转）与“放弃本地修改并加载最新草稿”；副本创建失败保留 `duplicateState` 以便重试，不重复建副本。
- **只读/错误态**：403/404/503/500 分别为“没有访问权限/流程不存在或不可见/编排功能已关闭/流程加载失败”，均带重试或返回；非所有者/非管理员只有 readOnly Result，不渲染 draftGraph 或节点配置。
- **375px/键盘**：双栏在 ≤900px 变单列，节点/连线/问题行可换行，分段控件自身滚动；关键按钮使用原生 Button/Select，无 tabindex=-1，`global.css` 提供 `:focus-visible`。

## 保存、校验、发布与 F-003

- **保存请求**：`name.trim()`、`description.trim()||null`、`version=draftVersion`、`graph=normalizeGraphForSave(graph)`；成功后以响应图/版本重置本地状态。
- **F-003 关闭**：`normalizeGraphForSave` 为每个节点补齐有限数字 `{x,y}`（缺失/NaN 用 0），按类型清空无关字段，文本/标签/审批人 trim 后空值省略，`branch` 只保留 CONDITION 出边的 TRUE/FALSE，空 source/target 边过滤；`graph.test.ts` 覆盖 `position:null/NaN`、`resultFrom:""`、空白 query/resultTitle、条件 IN/空标签、USER/ROLE 互斥等。F-003 的原始用例已关闭；边界上限/类型残留见 F-002。
- **校验/发布契约**：`/validate` 使用 POST + query `runScope/departmentId`，与 `contracts/openapi.yaml:2710-2719` 一致；发布请求只带 `runScope/departmentId`，Idempotency-Key 由客户端生成合法长度值，服务端 M06 为资源级幂等。
- **400 展示**：保存 400 走 `describeApiError` 的 message（含服务端 detail），发布 400 在弹窗中前缀“发布失败（400）”；服务端 `WorkflowValidationException` detail 最多列 20 条 code/node/field/message。服务端-only 错误的逐项中文面板缺口见 F-004。

## 测试与证据

| 证据     | 命令/提交                                                               | 结果                                                                                |
| -------- | ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| EV-1036  | `pnpm --filter @riccon/web test` @ ebc3c13，clean                       | exit 0；81 文件 / 726 用例通过；日志 sha256 与 EV JSON 一致                         |
| EV-1038  | `pnpm --filter @riccon/web typecheck && lint && build` @ ebc3c13，clean | exit 0；sha256 与 EV JSON 一致                                                      |
| EV-1048  | `pnpm --filter @riccon/web test` @ e5f5586，clean                       | exit 0；82 文件 / 736 用例通过；sha256 与 EV JSON 一致                              |
| EV-1049  | `pnpm --filter @riccon/web typecheck && lint && build` @ e5f5586，clean | exit 0；sha256 与 EV JSON 一致                                                      |
| 独立复跑 | 评审时 HEAD 90d8d40                                                     | 82/736 通过；workflow 7 文件/76 用例通过；typecheck/lint/build exit 0；无 skip/todo |

- 76 个 workflow 用例按文件为：`format.test.ts` 4、`list/index.test.tsx` 10、`workflow-api.test.ts` 10、`graph.test.ts` 11、`edit/index.test.tsx` 15、`node-form.test.ts` 14、`validation.test.ts` 12；全部有断言，未发现零断言/吞错/快照坏态。
- EV-1036/1038 在 ebc3c13，早于 53a3c84；但 `git diff 53a3c84..e5f5586 -- apps/web/src/pages/workflow/{list,edit}` 为空，EV-1048/1049 的 clean 全量已覆盖最终 T10 代码。任务卡证据归属见 F-006。
- 未运行：coverage/jacoco、E2E-M06-01/09、axe/键盘真实浏览器、k6/perf、Trivy/security、Maven 全量、T10b 路由 E2E；这些由 T16a/模块 gate 承担。

## 范围与契约

- `ebc3c13` 的 16 个文件 + `53a3c84` 的 1 个文件均在 `apps/web/src/pages/workflow/list/**` 与 `edit/**`；没有 apps/api、contracts、PRODUCT、AGENTS、routes、workspace layout 或 M06b 改动。
- 所有页面文案/注释为中文（代码标识符、枚举、API 字段保持英文）；无 TODO/FIXME/console/localStorage 密钥、无 eslint-disable/ts-ignore、无新增依赖。
- 契约交叉核对：列表 `view/status/page/size`、详情 `draftGraph/latestVersion/canEdit/canPublish`、保存 `version+graph`、校验 query、发布 body 与 201、409 `RICCON-WF-409`、400 `RICCON-WF-400`；类型定义与 `contracts/openapi.yaml` Workflow 段落一致（只声明页面消费字段）。
- T10b 的 `/workflows/list`、`/workflows/:workflowId/edit` 路由与工作空间导航不在本评审范围；本评审只验证页面注入 `navigate` 后的跳转目标。

## AC 对账（任务级，非模块 AC pass）

| AC                      | 任务级结论                                                                                                                                                           |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M06-AC-01（P0）         | **pass（组件/源码级）**：新建/打开/保存/重开、我的流程草稿标签、未发布不进可运行、409 另存副本/放弃均有实现与测试；E2E-M06-01 的真实浏览器与双会话并发由 T16a 验证。 |
| M06-AC-02（P0）         | **pass (with residual)**：客户端结构/必填/模型格式/工具/条件/结果校验与 `/validate` 面板、发布阻断均落地；服务端-only 保存 400 的逐项中文面板不可达（F-004）。       |
| M06-AC-19（P1）         | **pass（组件/CSS 级）**：列表/编辑器加载、空、错误、403、503、只读、成功态均有中文说明与重试/返回；375px 单列与无固定宽度表格；axe 与真实键盘由 T16a `@a11y` 判。    |
| M06-AC-20（P1）         | **pass（表单路径级）**：表单为主路径、节点/连线/配置/校验/保存/发布键盘可达，无画布依赖；真实全流程键盘与 axe 由 T16a 判。                                           |
| REV-TASK-0220-001 F-003 | **pass (with residual)**：position x/y 与空字符串/空白已关闭；IN 类型/数量、审批人 50、副本名 200 的客户端上限残留见 F-002。                                         |

## 不通过项

无 P0/P1。所有 finding 均为 accepted，不阻塞 T10 任务级 done；F-001/F-002/F-005 必须在 M06 gate 前闭环，F-003/F-004 在 T11/T16a 前闭环，F-006 在 T10 done 前补录。

## 未验证

- **E2E-M06-01/09**：真实浏览器创建→编辑→校验→保存→发布、409 双会话、375px 无横向滚动、键盘全流程、axe；T16a 负责，本评审无截图/trace，不以 jsdom 测试替代。
- **后端 API 实际行为**：本任务只改前端；未运行 Workflow API/MySQL/Testcontainers，只做契约/源码核对与组件测试替身。
- **coverage**：未跑 jacoco/web coverage，未对照 80/70 阈值；T10 是页面/纯函数，模块 gate 需看 excluded/实际覆盖率。
- **security/perf/migration**：未跑 Trivy、k6、迁移/回滚；无后端/迁移改动，T4 证据归模块 gate/T16b。
- **T10b 路由/导航**：另开评审；本评审只验证列表/编辑页面注入导航后的路径字符串。

## 剩余风险

1. **列表视图/筛选/分页的页面级测试缺口**：`list/index.test.tsx` 覆盖空/错误/403/503/创建，未覆盖切换 MINE/RUNNABLE/ALL、状态筛选触发请求与 `page>1`；API 客户端测试覆盖 query 参数，E2E 应补真实交互。
2. **401 会话过期未接入全局 `authStore.sessionExpired`**：本页自建 `workflow-api` 只做 CSRF 重试，401 显示中文错误但不立即跳登录；刷新后 workspace layout 会按 expired 跳转，影响可恢复，T16a/后续共享客户端迁移时统一。
3. **“重新加载”与浏览器刷新不提示未保存修改**：`loadDetail` 直接重置状态，可能丢失本地未保存编辑；建议后续加 dirty 确认或自动草稿。
4. **模型选择未按 REPORT 路由链过滤**：`/models` 返回员工可见模型（无 purpose 字段），UI 无法区分 REPORT 链；选择任意 UUID 可能保存 400（F-004 的触发路径之一）。模板主路径可留空走默认路由。
5. **节点摘要显示部分枚举码**（`MCP_TOOL`、`TENANT_ADMIN`、`EQ`），中文一致性可再打磨；不影响功能。
6. **“打开编辑”对非所有者编辑权限用户仍显示编辑文案**：列表项没有 per-flow `canEdit`，点击后编辑器进入 readOnly Result，无泄露但文案略有落差；T15/T12b 接入运行入口时一并调整。
7. **gate 归属**：当前 state.json 把 T05/T04b/T10b 的 apps/api/routes 文件算作 TASK-0230 越界（baseline 70bcccb 累积），模块 gate 前必须重定 baseline/补 scope 归因，否则会误报 T10 失败；这是状态记录问题，不是 T10 代码越界。

## Findings

- **F-001（P2，accepted）** 重新打开 DEPARTMENT 流程时异步目录兜底把范围重置为 ONLY_SELF；owner=builder-b3，due=T12b/M06 gate。
- **F-002（P3，accepted）** 客户端未校验 IN 值类型/20、审批人 50、副本名 200；owner=builder-b3，due=M06 gate。
- **F-003（P3，accepted）** `removeNode` 漏清 `CONDITION.left.nodeId`；owner=builder-b3，due=M06 gate/T11。
- **F-004（P3，accepted）** 服务端-only 校验失败时校验面板不可达，仅 400 detail；owner=builder-b3，due=T16a/M06 gate。
- **F-005（P3，accepted）** 运行范围 UI 与后端角色口径/部门目录失败提示不一致；owner=lead/builder-b3，due=M06 gate。
- **F-006（P3，accepted）** TASK-0230 证据未绑定最终 53a3c84，T10b EV 已覆盖；owner=builder-b3/lead，due=T10 done 前。

无 open P0/P1；P2/P3 均 accepted 且有 owner/due/accepted_reason。

## 是否可 done

**可以（pass，带 accepted residual）**，但需满足：

1. Lead 在 done 前把 EV-1048/EV-1049（或补一条 T10-owned clean EV）记入 TASK-0230.evidence，并修正/说明模块 gate 的 baseline scope 归属；
2. F-001 在 T12b/M06 gate 前修复并补“DEPARTMENT 重开保留范围”测试；F-002/F-005 在 gate 前闭环；F-003 在 T11 前、F-004 在 T16a 前闭环；
3. T16a 仍须产出 E2E-M06-01/09、axe 与真实键盘证据；本评审不把组件测试当成模块 AC pass。

本评审未运行 `gate check --module M06`，未改任务状态，也未替代模块 review、Product 预验收或用户验收。
