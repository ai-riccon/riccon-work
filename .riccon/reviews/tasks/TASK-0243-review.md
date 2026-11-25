---
review_id: REV-TASK-0243-001
module_id: M06
task_id: TASK-0243
reviewer:
  agent: riccon/verifier
  session: ses_defa294dfffecgCwxw3YHdMGGD
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - b7fb68cf037c0539d984761dcc16a539e00ae30c
  - b042f05c9e351cda6df7c37e5b154b7824cdd6d0
head_commit: b042f05c9e351cda6df7c37e5b154b7824cdd6d0
code_commit: b7fb68cf037c0539d984761dcc16a539e00ae30c
evidence_commit: b042f05c9e351cda6df7c37e5b154b7824cdd6d0
baseline_commit: 64573b5be586e9a3d27f85f352b6d86d1bbbbc20
task_card_baseline_commit: bf518a9b018365d03706a435b5e4e2fa5d258a39
previous_review: REV-TASK-0231-001
review_scope: >-
  M06 T11b 独立任务评审：REV-TASK-0231-001 F-002 的缺失坐标保留/画布网格回退/保存 round-trip/
  混合坐标，F-001 的画布空态文案方向，编辑页保存/校验/发布/409/375px/键盘回归，write_scope 与
  EV-1054..EV-1057 的 commit/dirty/hash 复核。不运行 E2E-M06-09、axe 或真实浏览器 375px/键盘
  旅程（归 T16a），不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  review: true
  evidence: true
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
  - migration
dod_note: >-
  任务范围内 acceptance/build/tests/lint/typecheck/review/evidence/scope 均为真实结论：
  F-001/F-002 已由源码、单元/组件测试和编辑器集成测试关闭，web 全量 752/752、typecheck、lint、
  build 均在 b7fb68c 干净树独立复跑通过。真实浏览器 E2E-M06-09、@a11y/axe、375px 实际无横向
  滚动和真实键盘旅程属 T16a；coverage/security/performance/migration 属 M06 模块 gate。本评审
  不代表 M06-AC-19/20 的模块级 pass，也不替代 Product 预验收或用户最终验收。
findings:
  - id: F-001
    severity: P3
    status: fixed
    owner: riccon/builder-b3
    title: "REV-TASK-0231-001 F-001 已关闭：画布空态提示改为指向下方节点列表"
    summary: >-
      `apps/web/src/components/workflow/canvas/index.tsx:114` 已把“上方节点列表”改为
      “下方的节点列表”，与画布下方节点/表单 Card 的实际 DOM 顺序一致；同组件宽屏提示
      （`index.tsx:131`）仍为“下方列表/表单”。`index.test.tsx:192-198` 以精确子串断言新文案，
      并显式断言不包含旧文案“上方节点列表”；源码检索未发现残留旧提示。
    evidence: >-
      apps/web/src/components/workflow/canvas/index.tsx:110-117,130-132；
      apps/web/src/components/workflow/canvas/index.test.tsx:192-198；
      定向与全量测试通过
  - id: F-002
    severity: P2
    status: fixed
    owner: riccon/builder-b3
    title: "REV-TASK-0231-001 F-002 已关闭：normalizeGraphForSave 不再把缺失坐标写成 (0,0)"
    summary: >-
      `graph.ts:436-443` 的 `normalizePosition` 仅在 x/y 均为有限数字时返回新坐标；缺失、null、
      NaN、Infinity 或任一轴非有限时返回 undefined。`normalizeGraphForSave`（`graph.ts:451-465`）
      只在 `position` 存在时写入属性，否则整体省略，且通过新建节点对象/新坐标对象保持入参不变。
      `loadDetail`（`edit/index.tsx:556`）与 `applySavedDetail`（`edit/index.tsx:785`）均走该规范化，
      保存/另存副本 payload 也走 `normalizeGraphForSave`（`edit/index.tsx:823,971`）；画布
      `computeCanvasLayout`（`layout.ts:115-126`）对缺失坐标按节点索引用 3 列网格回退。服务端契约
      允许省略 `position`（`contracts/openapi.yaml` 的 WorkflowNode.position 非 required），
      `WorkflowGraphJson.parsePosition` 接受缺省并返回 null，`WorkflowGraphNode` 的
      `@JsonInclude(NON_NULL)` 与既有 `toJson_omitsNullFields` 测试保证保存响应不会补回坐标。
      测试覆盖：`graph.test.ts:155-218`（NaN/null/缺失/round-trip）、`canvas/index.test.tsx:133-141`
      （3 节点无坐标网格）、`edit/index.test.tsx:328-369`（V15 market-research 7 节点无坐标经编辑器
      加载、画布坐标唯一、保存 payload 无 position、保存回填后仍唯一）。
    evidence: >-
      apps/web/src/pages/workflow/edit/graph.ts:436-465；
      apps/web/src/pages/workflow/edit/index.tsx:556,785,823,971；
      apps/web/src/components/workflow/canvas/layout.ts:115-126；
      apps/web/src/pages/workflow/edit/graph.test.ts:155-218；
      apps/web/src/components/workflow/canvas/index.test.tsx:133-141；
      apps/web/src/pages/workflow/edit/index.test.tsx:328-369；
      contracts/openapi.yaml:8204-8230；
      apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphJson.java:215-238；
      apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphNode.java:20-22
evidence:
  - EV-1054
  - EV-1055
  - EV-1056
  - EV-1057
reviewed_evidence:
  - EV-1054
  - EV-1055
  - EV-1056
  - EV-1057
notes: >-
  任务卡记录 baseline_commit=bf518a9、end_commit=b7fb68c；用户给定的代码基线为 64573b5。
  bf518a9..64573b5 只改 `.riccon`（状态/任务/前序证据），`git diff 64573b5..b7fb68c -- apps`
  只有 5 个 web 文件，故代码评审按 64573b5..b7fb68c。HEAD=b042f05 只多一个 `.riccon` 记录提交，
  `git diff b7fb68c..HEAD -- apps` 为空。按任务要求未运行 `riccon gate check`，未运行 E2E/axe、
  Trivy、k6 或 coverage，也未新增 EV；除本次新增评审文件外，工作树无代码/证据改动（build 产物为
  忽略项）。
created_at: "2026-11-25T09:50:44+08:00"
---

# REV-TASK-0243-001：M06 T11b 模板坐标回退与画布空态文案独立评审

## 结论

**通过（pass）**。TASK-0243 诚实关闭了上一轮 `REV-TASK-0231-001` 的两条残余：

- **F-002（P2，fixed）**：`normalizeGraphForSave` 不再把缺失/非有限坐标补成 `(0,0)`，而是整体省略；
  V15 内置 `market-research` 模板的 7 节点图经真实编辑器加载后走画布 3 列网格回退，坐标互不重叠；
  保存 payload 与保存响应回填都保持“缺失坐标仍缺失”，有效坐标原样保留。
- **F-001（P3，fixed）**：画布空态提示改为“请在下方的节点列表添加节点”，与画布位于节点列表上方的
  实际 DOM 顺序一致；测试以精确子串断言新文案并排除旧文案。

未发现 P0/P1，也没有新增 open finding。真实浏览器 `E2E-M06-09`、`@a11y`/axe、375px 实际无横向滚动
和真实键盘旅程仍归 T16a，本评审记录为残余而非 fail；本评审不是 M06-AC-19/20 的模块级通过。

## 评审范围与提交

- 任务卡：`.riccon/tasks/TASK-0243.json`，状态 `review`，证据 EV-1054..EV-1057，
  `end_commit=b7fb68c`。任务卡 `baseline_commit` 为 `bf518a9`；用户给定的代码基线为 `64573b5`。
  `bf518a9..64573b5` 只改 `.riccon`（`state.json`、任务卡、前序证据），`git diff
64573b5..b7fb68c -- apps` 与 `git diff bf518a9..b7fb68c -- apps` 均为同一组 5 个 web 文件，
  因此代码评审以 `64573b5..b7fb68c` 为准，不影响 write_scope 结论。
- 代码提交 `b7fb68c`；记录提交 `b042f05` 只改 `.riccon/evidence/TASK-0243/**`、
  `.riccon/tasks/TASK-0243.json` 与 `.riccon/state.json`。`git diff b7fb68c..HEAD -- apps` 为空。
- 只评审 T11b 的坐标/文案修复及受影响回归；不重审 T11 全量画布设计，不评审 T12+，不替代模块 review。

## F-002 关闭：缺失坐标保留与画布回退

### 生产代码

- `graph.ts:428-443` 新增 `normalizePosition`：只有 `position` 存在且 `position.x`、`position.y`
  均为 `Number.isFinite` 时才返回 `{x, y}`；缺失、`null`、`NaN`、`Infinity` 或任一轴非有限时返回
  `undefined`。这不会丢弃有效坐标，也不会把无效轴“修正”为 0。
- `graph.ts:451-465` 的 `normalizeGraphForSave` 改为先构造新节点
  `{id, type, name, config}`，仅在 `normalizePosition` 有结果时写入 `position`；因此保存 payload
  对无坐标节点完全没有 `position` 键，而不是 `{x:0,y:0}`。节点、坐标、边均为新建对象，未修改入参；
  返回图仍为 `{schemaVersion:1,nodes,edges}`。
- 编辑器所有相关入口一致：`loadDetail`（`index.tsx:556`）、`applySavedDetail`（`index.tsx:785`）、
  保存草稿（`index.tsx:823`）与 409 另存副本（`index.tsx:971`）都经过同一规范化；画布收到的是
  保留缺失坐标的图，`computeCanvasLayout`（`layout.ts:121-126`）对缺失坐标按节点索引生成
  `3 列 × 240/128` 网格回退，随后统一平移到 `CANVAS_PADDING`。
- 契约与服务端未改且已支持省略：`contracts/openapi.yaml` 的 `WorkflowNode` 只要求
  `[id,type,name,config]`，`position` 可选；`WorkflowGraphJson.parsePosition` 对缺省返回 `null`，
  `WorkflowGraphNode` 使用 `@JsonInclude(NON_NULL)`，既有
  `WorkflowGraphJsonTest.toJson_omitsNullFields` 断言序列化不出现 `position`。因此“保存缺失坐标”
  在前后端都是合法往返，而不是仅前端 mock 行为。
- 保存/校验/发布路径未改语义：`validateGraph` 仍在客户端校验前使用同一规范化；模板节点的缺失坐标
  不会让服务端校验失败，也不会在发布快照中被补成 0。

### 测试与实测

- `graph.test.ts:155-198` 覆盖 `NaN`、`null`、缺失 position，断言整体省略且非坐标字段清理不变；
  `graph.test.ts:200-218` 覆盖有效坐标（12/34）保留、缺失坐标不重建，并断言
  `normalizeGraphForSave(saved)` 与 `saved` 相等，即保存响应再次进入编辑器时不会产生 0 坐标。
- `canvas/index.test.tsx:133-141` 用 3 节点无坐标图断言 `foreignObject` 的 `x,y` 数量与唯一性，
  可捕获“全部叠在 (0,0)”的退化。
- `edit/index.test.tsx:328-369` 是本任务最关键的集成断言：`getWorkflow` 返回与 V15
  `market-research` 模板一致的 7 节点/6 边、无任何 `position` 的图；渲染编辑器后断言画布 7 个
  `foreignObject` 坐标互不相同；按真实用户路径为 `tool` 节点选择授权工具后保存，断言
  `saveDraft` 收到的每个节点都不含 `position` 键；保存响应回填后画布坐标仍互不相同。
  该测试同时证明修复发生在 `loadDetail` 规范化之后，而不是只证明纯函数分支。
- 独立定向运行该测试文件组：3 个文件、37 个用例全部通过，4 条与 F-001/F-002 直接相关的用例
  （2 条 graph、1 条 canvas、1 条 edit 集成）均显示 `✓`；未发现 skip/only/todo。

### 混合坐标

可接受。`normalizePosition` 按节点保留有效坐标、只对无效/缺失节点省略，画布对缺失节点按索引回退；
因此“部分节点有坐标、部分没有”不会再把缺失节点集体推到 (0,0)，已有显式布局也不会被改写。当前
产品路径中，内置模板整体无坐标，新增节点由 `createNode` 写入显式坐标；对 7 节点模板，新增节点
的 `createNode` 坐标（740,220 / 80,360 / 300,360 …）与 0–6 号回退网格（0,0 / 240,0 / 480,0 /
720,0 / 0,128 / 240,128 / 480,128）按 176×72 盒模型检查无重叠。真正的混合坐标只可能来自外部 API
直接提交的部分坐标图，理论上回退网格可能与显式坐标碰撞；这不属于 T11b 验收范围，画布仍是增强视图，
列表/表单主路径不受影响，记为残余风险而非 fail。

## F-001 关闭：空态文案方向

- `canvas/index.tsx:110-117` 的空图提示已改为“还没有节点。请在下方的节点列表添加节点，画布会自动
  生成预览；画布不是唯一入口。”；编辑器 DOM 中画布 Card（`edit/index.tsx:1213` 起）位于节点列表
  所在的 `workflow-editor-grid`（`edit/index.tsx:1228` 起）之前，因此“下方”方向正确。宽屏提示
  `canvas/index.tsx:130-132` 仍为“下方列表/表单”，两处一致。
- `canvas/index.test.tsx:192-198` 断言“请在下方的节点列表添加节点”、可见性，并断言
  `not.toHaveTextContent('上方节点列表')`；不是只匹配“还没有节点”的弱断言。
- `grep` 检查 `apps/web/src` 未发现残留“上方节点列表”文案；其他页面的“上方/下方”提示与本次画布
  无关。

## 回归

- 独立复跑 `pnpm --filter @riccon/web test`：84 个测试文件、752/752 用例通过，exit 0（与 EV-1054
  的 752 一致；较 T11 的 749 增加 3 条新用例）。
- 编辑页关键回归仍在全量中通过：保存草稿（位置 x/y 完整、空字段省略）、服务端校验面板与成功提示、
  发布 validate→publish 与 400、409 另存副本/放弃重载、只读与 403/404/503、375px 单列且关键按钮
  可聚焦、画布 Enter/空格选中并回到表单。未改这些路径的生产代码。
- `pnpm --filter @riccon/web typecheck`、`lint`（eslint --max-warnings=0 + prettier --check）、
  `build` 均 exit 0；与 EV-1055/EV-1056/EV-1057 结果一致。`git diff --check 64573b5..b7fb68c --
apps/web` 无空白错误。
- 全量测试未显示 skipped；diff 中未出现 `.skip`、`.only`、`todo` 或吞错/retry 参数。

## 范围

- `git diff --name-only 64573b5..b7fb68c` 只有 5 个文件，全部落在任务 `write_scope`：
  `apps/web/src/pages/workflow/edit/graph.ts`、`graph.test.ts`、`index.test.tsx`，
  `apps/web/src/components/workflow/canvas/index.tsx`、`index.test.tsx`。
- 未改 `apps/api`、`contracts/`、`docs/PRODUCT.md`、`AGENTS.md`、`package.json`/lockfile、T12+
  页面、路由或部署配置；无新运行时依赖。
- 任务卡的 `baseline_commit=bf518a9` 与用户给定基线 `64573b5` 差异只涉及 `.riccon` 快照；按
  `riccon` 的 scope 实现，`.riccon/` 会被排除，不会把前序任务/状态文件误归到本任务。当前任务
  status 仍为 `review`，未自行 `done`；记录提交 b042f05 中没有源码变更。

## 证据复核

| EV      | 命令                                  | commit  | dirty | exit | 日志/校验                                        |
| ------- | ------------------------------------- | ------- | ----- | ---- | ------------------------------------------------ |
| EV-1054 | `pnpm --filter @riccon/web test`      | b7fb68c | false | 0    | 881 B，sha256 `de367241…`；84 文件/752 用例通过  |
| EV-1055 | `pnpm --filter @riccon/web typecheck` | b7fb68c | false | 0    | 743 B，sha256 `d8b2a61d…`                        |
| EV-1056 | `pnpm --filter @riccon/web lint`      | b7fb68c | false | 0    | 451 B，sha256 `47839876…`；eslint+prettier 通过  |
| EV-1057 | `pnpm --filter @riccon/web build`     | b7fb68c | false | 0    | 4162 B，sha256 `f2024dbf…`；Umi/Webpack 编译成功 |

复核结果：

- 四个 EV JSON 的 `commit=b7fb68c`、`dirty=false`、`exit_code=0`、`manual=false`、`cwd` 为项目根；
  `output_bytes` 与日志实际字节数一致，四个日志 sha256 均与 JSON 记录逐字一致。
- EV-1054 日志的 752/752 与独立复跑一致；EV-1055/1056/1057 的独立复跑也均为 exit 0。
- 证据 ID 全仓唯一（EV-1054..EV-1057 只出现在 TASK-0243 证据目录与任务卡）；未发现重复 ID、
  空命令、dirty 证据或掩盖失败写法。
- 独立复跑未新增 EV，未运行 `riccon gate check`（按任务要求）；除本次新增评审文件外，工作树无
  代码/证据改动。

## Findings

- **F-001（P3，fixed，owner `riccon/builder-b3`）**：`REV-TASK-0231-001 F-001` 已关闭；空态提示
  改为指向下方节点列表，测试断言新文案并排除旧文案。见上文“F-001 关闭”。
- **F-002（P2，fixed，owner `riccon/builder-b3`）**：`REV-TASK-0231-001 F-002` 已关闭；
  `normalizeGraphForSave` 仅保留 x/y 均有限的坐标，缺失/非有限坐标整体省略；无坐标模板经编辑器
  加载走画布网格回退，保存 round-trip 保持缺失。见上文“F-002 关闭”。
- 无新增 open finding；无 P0/P1。

## 未验证与剩余风险

- **T16a 未跑**：`E2E-M06-09`（真实浏览器键盘全流程、375px 实际无横向滚动）、`@a11y`（axe 五界面）、
  真实 `foreignObject` 焦点/键盘兼容性，均按任务安排留给 T16a；本评审不据此判 fail，但 M06-AC-19/20
  仍需 T16a 证据才能做模块级判定。
- **历史数据**：修复前旧版前端可能已把缺失坐标持久化成 `(0,0)`；本次修复只阻止新保存/新模板加载
  继续写入 0，不会自动迁移已存在的 0 坐标草稿。M06 尚未发布，若演示/种子复用旧草稿，需重新从模板
  创建或清理；属数据修复策略，不是本任务代码缺陷。
- **混合坐标**：外部 API 直接提交部分坐标时，理论上回退网格可能和显式坐标碰撞；当前产品路径
  （模板无坐标 + 新增节点显式坐标）已验证无重叠，列表/表单主路径不受影响。未在真实浏览器验证
  混合布局，作为 T16a/后续增强的残余。
- **测试观察（非 finding）**：`graph.test.ts` 注释提到 Infinity，但用例用 NaN/null/缺失覆盖了同一
  `!Number.isFinite` 分支，未单列 `Infinity`；实现谓词为 `Number.isFinite`，风险可忽略。
- **覆盖率/安全/性能/迁移**：本任务未产生 coverage EV，也未跑 Trivy/k6/迁移；由 M06 模块 gate 与
  T16a 承担。本评审不替代模块 review、Product 预验收或用户最终验收。

## 下一步

1. builder/lead 在 T16a 运行 `E2E-M06-09`、`@a11y`/axe、真实 375px 与键盘旅程，覆盖本任务修复后的
   模板加载/空态文案路径；完成后由模块 review 对账。
2. 若演示环境存在修复前保存的 `(0,0)` 草稿，由 lead 决定重新从模板创建或做一次性清理；不要把
   历史数据问题误判为本次代码回归。
3. 模块 review 在 M06 gate 前继续承接 T11 的 T16a 残余和本评审的“混合坐标/历史数据”风险说明。
