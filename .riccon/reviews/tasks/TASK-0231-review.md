---
review_id: REV-TASK-0231-001
module_id: M06
task_id: TASK-0231
reviewer:
  agent: riccon/verifier
  session: ses_defbb6487ffeQzz2H4Rrgf3LyY
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
reviewed_commits:
  - d45e9e9751ee9d853a0452b6f1536e4c9985235c
  - f690380af93777cf73bf8c4e2dbf6a997b76fe74
  - 9e010c0e8de2befa16dcd545a902e2ddeba5f3f1
  - 97b847a72b0376bbd6ec3ef7e0812a380a5c60a3
head_commit: 97b847a72b0376bbd6ec3ef7e0812a380a5c60a3
code_commit: f690380af93777cf73bf8c4e2dbf6a997b76fe74
evidence_commit: 9e010c0e8de2befa16dcd545a902e2ddeba5f3f1
baseline_commit: d45e9e9751ee9d853a0452b6f1536e4c9985235c
task_card_baseline_commit: 093eeafc3d8f22aee5af475937d72975e579adc3
related_reviews:
  - REV-TASK-0230-001
review_scope: >-
  M06 T11（TASK-0231）前端画布最小增强的独立任务评审：SVG 布局/坐标归一化、无坐标回退、连线端点、
  条件标签、悬空与自连边、viewBox 与无横向滚动、<768px 降级、原生按钮与键盘、aria-pressed/aria-label、
  连线 title 与隐藏有序列表、画布到节点/连线表单的等价入口、T10 编辑器接入、F-003 条件引用清理
  及回归。
  不评审 T16a 的 E2E-M06-09/axe/真实浏览器键盘、不评审 T12+ 后续任务、不替代 M06 模块 review、
  Product 预验收或用户最终验收。
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
  acceptance 记 true 但按 **pass (with residual)** 理解：T11 任务范围内的画布几何（除 F-002 的
  模板/无坐标路径）、键盘/aria、降级与 T10 集成由源码、65 个定向测试（6 个文件）和 web 全量
  749 个测试支撑；真实浏览器 E2E-M06-09、
  @a11y/axe、真实键盘全流程与 375px 实际无横向滚动属 T16a，本评审未运行，因此这不是 M06-AC-19/20
  的模块级 pass。build/tests/lint/typecheck 由 EV-1050..EV-1053 与评审时独立复跑支撑；evidence 复核
  commit=f690380、dirty=false、exit=0、日志 hash/bytes 一致。F-001（P3 文案）与 F-002（P2 无坐标
  回退被规范化绕过，影响内置模板画布）为 accepted 残余，F-003（前序 T10 F-003）已 fixed；
  coverage/e2e/security/performance/accessibility 由模块 gate/T16a 承担，本任务未跑。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/builder-b3
    due: "T16a/M06 gate 前"
    title: "画布空态提示把节点列表方向写反"
    summary: >-
      `apps/web/src/components/workflow/canvas/index.tsx:110-117` 的空图提示写“请在上方节点列表添加节点”，
      但编辑器里画布 Card 位于节点列表 Card 之前（`apps/web/src/pages/workflow/edit/index.tsx:1213-1228`），
      实际节点列表在画布下方；同一组件的宽屏提示又写“请在下方列表/表单中完成”
      （`canvas/index.tsx:130-132`），两处方向不一致。观察：空草稿时用户被指向不存在的“上方”列表；
      期望：改为“下方节点列表”或“页面下方的节点列表”。现有空态测试只断言 `/还没有节点/`
      （`canvas/index.test.tsx:154-158`），未校验方向文案。
    accepted_reason: >-
      纯文案方向错误，不影响节点添加、保存、校验、发布或画布渲染；节点列表与添加入口在当前视口内可见，
      375px 降级提示仍正确指向列表/表单；属可读性问题，不构成 AC-19 状态覆盖或 AC-20 键盘路径阻断。
      由于本轮评审不改源码，接受并在 T16a/M06 gate 前由 builder 修正。
    evidence: >-
      apps/web/src/components/workflow/canvas/index.tsx:110-117,130-132；
      apps/web/src/pages/workflow/edit/index.tsx:1213-1228；
      apps/web/src/components/workflow/canvas/index.test.tsx:154-158
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/builder-b3
    due: "T16a/M06 gate 前"
    title: "无坐标回退在编辑器集成路径失效：模板图的缺失坐标被 normalizeGraphForSave 填成 0"
    summary: >-
      `layout.ts:121-126` 只在 `position.x/y` 非有限数字时使用回退网格，`layout.test.ts:62-88` 直接
      调用纯函数验证了该分支。但编辑器加载草稿时先执行 `normalizeGraphForSave`
      （`edit/index.tsx:556`），后者对缺失/非有限坐标一律写成 `{x:0,y:0}`
      （`graph.ts:431-441`）；契约中 `WorkflowNode.position` 是可选的
      （`contracts/openapi.yaml:8204-8230`，`apps/api/.../WorkflowGraphNode.java:18` 标注“可空”）。
      **这不是仅限旧草稿的边界：V15 内置模板 `market-research` 的 `graph_json` 完全没有 position**
      （`V15__workflow_studio.sql:487-493`），`createFromTemplate` 原样复制模板 graph
      （`WorkflowDefinitionService.java:248-258`），因此“从模板创建”的流程首次打开编辑器时，
      所有节点都会被规范化到同一个 (0,0)；布局层不再触发回退，节点完全重叠、连线端点退化，
      且后渲染节点会遮住先渲染节点的焦点轮廓。现有 canvas/edit 测试的图夹具均带 position
      （如 `edit/index.test.tsx:41-66` 的 `VALID_GRAPH`），没有覆盖模板/无坐标加载路径。
      期望：保存规范化与画布回退采用同一套确定性坐标（或加载时保留 undefined 供画布回退），
      而不是用 0 覆盖可选字段。
    accepted_reason: >-
      影响内置模板创建流程以及省略 position 的旧/外部草稿，属于 M06 模板主路径的画布视觉缺陷；
      但画布仍是增强视图，节点列表、配置表单、校验、保存与发布不受影响，也没有数据、权限或安全
      影响，故按 P2 接受而非 P1。由于本轮评审不改源码，需在 T16a/M06 gate 前由 builder 修复，
      并补一条“经编辑器/模板加载”的集成断言，避免纯函数测试再次掩盖该路径。
    evidence: >-
      apps/web/src/pages/workflow/edit/index.tsx:556；
      apps/web/src/pages/workflow/edit/graph.ts:431-441；
      apps/web/src/components/workflow/canvas/layout.ts:121-126；
      apps/web/src/components/workflow/canvas/layout.test.ts:62-88；
      apps/api/src/main/resources/db/migration/V15__workflow_studio.sql:487-493；
      apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionService.java:248-258；
      contracts/openapi.yaml:8204-8230；
      platform/platform-domain/src/main/java/com/riccon/platform/domain/workflow/WorkflowGraphNode.java:18
  - id: F-003
    severity: P3
    status: fixed
    owner: riccon/builder-b3
    title: "REV-TASK-0230-001 F-003 已关闭：removeNode 清理 CONDITION.left.nodeId"
    summary: >-
      前序评审 F-003 指出 `removeNode` 只清理 END.resultFrom 与 APPROVAL.targetNodeKey，删除被
      CONDITION（source=NODE_OUTPUT）引用的上游节点后会留下 `condition.left.nodeId` 悬空引用。
      `apps/web/src/pages/workflow/edit/graph.ts:174-205` 已增加条件引用判断并清空 nodeId；
      `apps/web/src/pages/workflow/edit/graph.test.ts:101-135` 新增回归测试，同时断言 resultFrom 清理和
      condition.left.path 不被误伤。独立定向运行该测试通过（见“F-003 关闭”）。
    evidence: >-
      apps/web/src/pages/workflow/edit/graph.ts:174-205；
      apps/web/src/pages/workflow/edit/graph.test.ts:101-135；
      定向运行 pnpm exec vitest run src/components/workflow/canvas src/pages/workflow/edit
evidence:
  - EV-1050
  - EV-1051
  - EV-1052
  - EV-1053
reviewed_evidence:
  - EV-1050
  - EV-1051
  - EV-1052
  - EV-1053
notes: >-
  评审时 HEAD=97b847a（9e010c0/97b847a 只改 `.riccon` 证据与任务记录），
  `git diff f690380..HEAD -- apps` 为空，故独立复跑代表 f690380 的 web 代码。工作树仅存在其他任务
  的 `.riccon` 未提交修改与未跟踪评审/证据文件，不涉及 T11 源码。按任务要求未运行 gate check、
  E2E、axe、Trivy、k6 或 coverage，也未新增 EV。
created_at: "2026-11-25T09:22:11+08:00"
---

# REV-TASK-0231-001：M06 T11 画布最小增强独立评审

## 结论

**通过（pass，任务级含残余）**。画布以原生 SVG/HTML 实现，节点是可聚焦原生按钮，连线有 `<title>`
和隐藏有序列表，<768px 直接降级为列表/表单提示；画布已接入 T10 编辑器并通过选中节点同步配置表单、
两个“在表单中编辑”入口完成键盘等价路径。`removeNode` 的 CONDITION 引用清理已修复并有回归测试。
未发现 P0/P1；1 条 P3 文案方向问题被接受（F-001），1 条 P2 无坐标回退在编辑器加载路径被
`normalizeGraphForSave` 的 0 兜底绕过、会使内置模板流程的节点在画布上重叠（F-002），前序
T10 F-003 已关闭（本轮 F-003）。

本结论是 T11 任务级评审，不是 M06-AC-19/20 的模块级通过：真实浏览器 E2E-M06-09、axe、真实键盘
全流程与 375px 实际无横向滚动仍由 T16a 验证；本评审不代表 Product 预验收或用户验收。

## 评审范围与提交

- 任务卡：`.riccon/tasks/TASK-0231.json`，状态 `review`，证据 EV-1050..EV-1053，
  `end_commit=f690380`。
- 任务卡 `baseline_commit` 为 `093eeaf`；用户给定的代码基线为 `d45e9e9`（T11 扩写写范围以接入
  `edit/**`）。`093eeaf..d45e9e9` 只改 `.riccon/state.json` 与任务卡，无代码差异；代码评审按
  `d45e9e9..f690380`。
- 代码提交 `f690380`；记录提交 `9e010c0`（EV 目录与任务/状态）和 `97b847a`（Serena 记录）均只改
  `.riccon`。
- 不评审 T12a/T12b/T13/T16a 的后继实现，不重审 T10 全量功能，不替代模块 gate。

## 画布

### 几何与降级

- `apps/web/src/components/workflow/canvas/layout.ts` 是纯函数：逐节点读取有限 `position.x/y`，
  缺失/NaN/Infinity 时按创建索引用 3 列网格回退；以最小 x/y 平移到 `CANVAS_PADDING`，再计算
  `viewBox` 宽高（不低于 640×280）。负坐标、无坐标回退、空图均有纯函数测试
  （`layout.test.ts:25-88`）。**但编辑器加载路径先调用 `normalizeGraphForSave` 把缺失坐标填成 0**
  （`edit/index.tsx:556`、`graph.ts:431-441`），使画布收到的图不再触发该回退；契约允许省略
  `WorkflowNode.position`（`openapi.yaml:8204-8230`），且 V15 内置 `market-research` 模板的
  graph 没有 position（`V15__workflow_studio.sql:487-493`），模板创建会原样复制
  （`WorkflowDefinitionService.java:248-258`），所以模板主路径会命中该问题。见 F-002。
- 连线端点取源节点右中与目标节点左中，条件分支保留在布局结果并生成中点标签坐标
  （`layout.ts:153-178`，`layout.test.ts:90-122`）；悬空边与自连边跳过，交由校验面板报告
  （`layout.ts:155-161`，`layout.test.ts:124-139`）。
- `computeCanvasLayout` 的宽度/高度由节点包围盒加内边距计算；SVG 用 `width:100%` +
  `aspectRatio` 和 `viewBox` 等比缩放，CSS 同时设置 `max-width:100%`、容器 `min-width:0`
  （`canvas/index.tsx:133-139`，`canvas.css:25-39`），结构化上不会由画布产生横向滚动。
- `<768px` 在 `useWideViewport` 中直接返回提示，不渲染 SVG/节点/连线列表
  （`canvas/index.tsx:97-108`）；空图返回添加提示，不渲染空白 SVG（`index.tsx:110-118`）。
  `index.test.tsx:145-158` 覆盖 375px 与空图。
- 未引入 React Flow/其他画布依赖：`git diff d45e9e9..f690380` 不含 `package.json`/`pnpm-lock.yaml`，
  仓库检索无 `reactflow`/`@xyflow`。

### 条件标签与边列表

- 画布对每条绘制边输出 `<g><title>源 → 目标（条件 TRUE/FALSE）</title>`；条件标签文本单独渲染
  （`canvas/index.tsx:153-170`）。另有视觉隐藏的 `<ol aria-label="画布连线列表">`，按图边顺序输出
  源→目标与条件（`index.tsx:215-223`，`canvas.css:115-126`）。
- 组件测试断言了两条边的 `<title>` 数量与隐藏列表文本（`index.test.tsx:92-98`），布局测试断言
  `branch` 保留（`layout.test.ts:109-120`）。**条件标签在组件级的直接文本断言缺失**，但布局层与
  隐藏列表逻辑均已覆盖；真实浏览器可读性留给 T16a/axe，本评审记为残差而非 fail。

## a11y 与等价路径

- 节点是原生 `<button type="button">`，渲染在 SVG `foreignObject` 内；Tab 顺序跟随
  `graph.nodes`（`layout.nodes`）顺序。`aria-pressed` 表达选中状态，`aria-label` 为
  “名称，类型，已配置/待修正”；Enter 与空格显式 `preventDefault` 后选中同一节点，点击路径保留
  （`canvas/index.tsx:191-210`，`index.test.tsx:105-132`）。
- 选中/待修正同时用颜色与文字表达：节点元信息显示“已配置/待修正”，`aria-label` 也带状态；
  待修正节点有 `workflow-canvas-node-invalid` 类（`index.tsx:172-209`，`index.test.tsx:139-143`）。
  `:focus-visible` 用内缩轮廓，避免 `foreignObject` 裁剪焦点框（`canvas.css:77-81`）。
- 画布下方提供“在表单中编辑选中节点”（未选中时禁用）和“在表单中编辑连线”两个按钮
  （`canvas/index.tsx:225-238`）；编辑器通过 `focusNodeForm/focusEdgeForm` 聚焦节点表单首控件与
  连线表单源节点下拉（`edit/index.tsx:993-1016`），连线表单增加 `id="workflow-edge-form"`
  （`edit/index.tsx:1342`）。集成测试验证 Enter 选中→按钮→`节点名称` 焦点、以及连线表单
  combobox 焦点（`edit/index.test.tsx` 新增用例）。
- 隐藏列表在无障碍树中保留；视觉层 `<svg>` 设 `focusable="false"`，节点按钮仍在 DOM/Tab 序列中。
  `data-testid="workflow-canvas"`/`workflow-canvas-visual`、按钮无障碍名、列表 `aria-label`、
  表单控件 `aria-label` 足以让 T16a 定位画布存在/缺失、节点、边列表和等价入口；真实浏览器键盘与
  axe 未在本轮运行。
- 表单仍是主路径：画布是只读增强，节点列表、节点配置、连线表、校验面板均保留，画布按钮只负责
  聚焦既有表单；未在画布复制编辑字段，避免两套写路径。**画布 Card 位于节点列表之前**，键盘用户
  会先经过画布节点按钮再进入列表/表单；这是“画布为增强、表单为主路径”的焦点顺序残余，T16a 应在
  真实浏览器键盘旅程中确认是否需要跳转链接或调整 DOM 顺序。

## 集成

- `apps/web/src/pages/workflow/edit/index.tsx` 新增 `WorkflowCanvas` 引入、`invalidNodeIds`
  （由 `liveIssues` 去重前的节点 ID 列表）、画布 Card、`onSelectNode={setSelectedNodeId}` 与
  两个表单聚焦回调；未改保存、409 另存副本、校验、发布、权限/禁用态逻辑。
- 画布选中节点后，`NodeConfigForm` 以 `key={selectedNode.id}` 重新挂载并读取同一 `graph`；节点列表
  的选中态与 `aria-pressed` 同步。连线表单 id 变更只增加锚点，不改变 `onFinish`/校验。
- T10 的列表/表单路径测试仍通过：web 全量 749/749；编辑器用例覆盖保存规范化、校验面板、发布、
  409、375px 与 403/404/503 状态（见“证据”）。
- 未验证：真实浏览器中点击/键盘选中后页面滚动位置、焦点可见性与 `foreignObject` 兼容性；T16a。

## F-003 关闭

前序 `REV-TASK-0230-001` F-003（P3，owner builder-b3，due M06 gate 前/T11）已关闭：

- `graph.ts:174-205` 的 `removeNode` 在删除节点时同时判断
  `condition.left.source === 'NODE_OUTPUT' && condition.left.nodeId === nodeId`，清空
  `condition.left.nodeId`，保留 `source/path/valueType` 等其余字段；原有 `resultFrom`、
  `targetNodeKey` 与关联边清理不变。
- `graph.test.ts:101-135` 新增回归用例，断言条件引用被清空、`END.resultFrom` 仍按原行为清空、
  `condition.left.path` 不被误伤。
- 独立定向运行（canvas + edit 6 个测试文件）65/65 通过，其中该用例明确显示
  `删除被条件节点引用的上游节点会清空 condition.left.nodeId（REV-TASK-0230-001 F-003）`。

该修复不改变画布或表单的写路径；保存前 `normalizeNodeConfig` 仍会对空 nodeId 做 compact，客户端
校验会在用户未重新选择上游节点时阻止保存，符合 F-003 的“清理悬空引用、要求用户重新选择”口径。

## 范围与回归

- `git diff --name-only d45e9e9..f690380` 只有 9 个文件，全部在
  `apps/web/src/components/workflow/canvas/**` 与 `apps/web/src/pages/workflow/edit/**`：
  canvas 新增 5 个文件，edit 修改 `graph.ts/graph.test.ts/index.tsx/index.test.tsx`。
- 未改后端、`contracts/`、`PRODUCT.md`、`AGENTS.md`、package/lockfile、T12+ 页面；无新运行时依赖。
- 当前 HEAD `97b847a` 比代码提交多两个 `.riccon`-only 提交；`git diff f690380..HEAD -- apps` 为空。
  工作树有任务 TASK-0224/TASK-0235、TASK-0230 证据和 TASK-0223 评审等未提交 `.riccon` 变更，
  不属于 T11 源码；未因这些变更影响独立复跑。
- 未运行 E2E/axe/coverage/Trivy/k6；T16a 与模块 gate 负责。

## 证据复核

| EV      | 命令                                  | commit  | dirty | exit | 日志/校验                                       |
| ------- | ------------------------------------- | ------- | ----- | ---- | ----------------------------------------------- |
| EV-1050 | `pnpm --filter @riccon/web test`      | f690380 | false | 0    | 883 B，sha256 `c170da10…`；84 文件/749 用例通过 |
| EV-1051 | `pnpm --filter @riccon/web typecheck` | f690380 | false | 0    | 735 B，sha256 `b3508311…`                       |
| EV-1052 | `pnpm --filter @riccon/web lint`      | f690380 | false | 0    | 451 B，sha256 `f374e3ef…`                       |
| EV-1053 | `pnpm --filter @riccon/web build`     | f690380 | false | 0    | 4176 B，sha256 `4710e057…`；Umi build 成功      |

复核结果：

- EV JSON 的 commit、dirty、exit_code、output_bytes 与实际日志一致；四个日志 sha256 均与 JSON 记录一致。
- 独立复跑（未新增 EV）：`pnpm --filter @riccon/web test` → 84 files / 749 tests passed，exit 0；
  `typecheck`、`lint`、`build` 均 exit 0；定向 `vitest run src/components/workflow/canvas
src/pages/workflow/edit` → 6 files / 65 tests passed，exit 0。
- 证据 ID 全仓唯一（EV-1050..EV-1053 仅出现在 TASK-0231 证据目录）；未发现重复 ID 或脏证据。
- 按任务要求未运行 `riccon gate check`；本评审未修改源码、测试、契约、PRODUCT、AGENTS 或 `.riccon` 状态。

## Findings

- **F-001（P3，accepted，owner `riccon/builder-b3`，due T16a/M06 gate 前）**：空态提示“上方节点列表”
  方向写反；属文案问题，不阻断操作。修正建议：改为“下方节点列表/表单”。
- **F-002（P2，accepted，owner `riccon/builder-b3`，due T16a/M06 gate 前）**：`normalizeGraphForSave`
  在加载/保存时把缺失坐标填成 0，使画布的“无坐标回退”在真实编辑器路径失效；内置
  `market-research` 模板的 graph 无 position，模板创建流程打开后所有节点会叠在 (0,0)。需统一
  坐标兜底并补“经编辑器/模板加载”的集成断言。
- **F-003（P3，fixed）**：前序 `REV-TASK-0230-001` F-003 已由 `removeNode` 条件引用清理与回归测试关闭。
- 无 P0/P1；无其他 open finding。

## 未验证与剩余风险

- **T16a 未跑**：`E2E-M06-09`（真实浏览器键盘全流程、375px 实际无横向滚动）、`@a11y`（axe 五界面）、
  真实 `foreignObject` 焦点/键盘兼容性、画布与表单校验结果一致性；这些是 T11 任务验收中明确留给
  T16a 的部分，本评审不将其判为 T11 fail，但模块 AC-19/20 仍不能据此判 pass。
- **焦点顺序**：画布 Card 位于节点列表前，节点较多时键盘用户需先经过所有画布节点按钮；当前有
  “在表单中编辑选中节点/连线”快捷入口，但 T16a 应评估是否需要跳转链接或把画布后移。未在真实
  浏览器验证。
- **未保存表单值**：在节点配置表单未点“保存节点配置”时切换画布节点，会因 `key={selectedNode.id}`
  重挂载而丢失未应用输入；该行为在 T10 节点列表切换时已存在，不是 T11 引入，但 T16a/Product 应
  确认是否可接受。
- **条件标签组件级断言**：布局测试覆盖 `branch`，组件测试未直接断言条件 `<text>`；T16a/E2E 可补。
- **无坐标回退**：F-002 的纯函数回退本身正确，但被编辑器加载时的 `normalizeGraphForSave` 0 兜底绕过；
  修复前，内置 `market-research` 模板创建流程以及省略 `position` 的合法草稿在画布上会重叠。
  画布仍是增强视图，列表/表单路径不受影响，但这是模板主路径的可见缺陷。
- **覆盖率**：本任务未产生 coverage EV；新 canvas 文件的行/分支覆盖由模块 gate 的合同 coverage 命令
  统一检查。
- 本评审不替代模块 review、Product 预验收或用户最终验收；M06-AC-19/20 的最终判定需 T16a 证据。

## 下一步

1. builder 在 T16a/M06 gate 前修正 F-001 文案并向“下方节点列表”对齐（可同时补一条方向断言）。
2. builder 修复 F-002：让 `normalizeGraphForSave` 的缺失坐标兜底与 `computeCanvasLayout` 使用同一套
   确定性网格（至少覆盖 V15 内置模板 graph），或在加载时保留 `undefined` 给画布回退；补一条经
   编辑器/模板创建加载省略 position 的集成断言。
3. T16a 运行 E2E-M06-09/@a11y，覆盖真实键盘、axe 与 375px 无横向滚动；完成后由模块 review 对账。
4. 模块 review 继续承接 T10 的 F-001/F-004 等残余，不要把本次 T11 任务级 pass 当作模块 AC pass。
