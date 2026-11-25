---
review_id: REV-TASK-0247-001
module_id: M06
task_id: TASK-0247
reviewer:
  agent: riccon/verifier
  session: ses_decbf8405ffeeWl7q5bJxTkVK
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 0393aca3938925949b7b89778575d62ff0a1a2d7
  - bb858487d019f1047b32c95e429fbdbd843662ab
head_commit: 8696d6f859d540fcafc8f42847ce0eb76f46ff6a
code_commit: 0393aca3938925949b7b89778575d62ff0a1a2d7
evidence_commit: bb858487d019f1047b32c95e429fbdbd843662ab
baseline_commit: 9639710723470b4374d38a8ca8c06ea8bdcb3fd2
task_card_baseline_commit: 96397107
task_card_end_commit: 0393aca3
related_reviews:
  - REV-TASK-0230-001
  - REV-TASK-0233-002
review_scope: >-
  M06 T12d（TASK-0247）编辑器发布范围与客户端限制修复的独立任务评审：REV-TASK-0230-001
  F-001（DEPARTMENT 重开不得静默回退 ONLY_SELF、部门目录 loading/loaded/failed 不静默改范围、
  重试与发布二次确认）、F-002（IN 值数量/类型、按人审批 50 人、另存副本名 200 的客户端与已保存图
  校验）、F-005（范围 UI 与 WorkflowPublishScopeValidator 的 TENANT_ADMIN/WORKFLOW_EDITOR 口径、
  部门目录失败显式告警）在 edit/** 的残余关闭情况；write_scope 是否只含 edit/**；EV-1107..EV-1110
  的 commit/dirty/hash；981 个 web 用例与 typecheck/lint/build 的独立复跑。不评审 T12a/T12b/T12c
  自身实现、后端/契约改动、T16a 真实浏览器 E2E/axe/375px/perf、M06 模块 gate、Product 预验收或
  用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  typecheck: true
  lint: true
  review: true
  evidence: true
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - accessibility
  - performance
  - security
  - migration
  - docs
dod_note: >-
  acceptance 记 true 但按 **pass (with residual)** 解释：F-001/F-005 的 edit 页残余已关闭，
  F-002 的 IN 值/审批人/副本名主路径已关闭；副本名在 astral 字符接近 200 时仍与后端 UTF-16
  码元上限不一致（P3 accepted，见 F-001），WORKFLOW_EDITOR 单独角色缺 department.read 的残余
  已显式处理但未形成角色决策（P3 accepted，见 F-002）。build/tests/typecheck/lint 由
  EV-1107..EV-1110（clean bb858487，hash 一致）与本次在 0393aca3 独立复跑的 105 文件/981 用例、
  5 文件/68 个 edit 用例、typecheck/lint/build 支撑；coverage/e2e/accessibility/performance/
  security/migration 归模块 gate 与 T16a，本任务未跑；scope 只含 edit/**，对 history/format 仅
  只读复用。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/builder-b1（客户端副本名截断）/ riccon/lead（契约与后端口径）
    due: M06 gate 前
    title: "副本名按 Unicode 码点截断，与后端 UTF-16 码元上限在 astral 字符接近 200 时仍不一致"
    summary: |-
      `apps/web/src/pages/workflow/edit/limits.ts:33-40` 的 `duplicateWorkflowName` 用
      `[...base]` 按 Unicode 码点截断到 197 个码点，再追加 3 个码点的“ 副本”。契约
      `contracts/openapi.yaml:8871-8877` 的 `WorkflowDuplicateRequest.name.maxLength=200` 按
      JSON Schema 是码点，但后端 `WorkflowDefinitionRequestParser.java:166-180` 用
      `String.length()`（UTF-16 码元）校验，`parseDuplicate` 在第 94 行同样走这条路径。
      观察（本评审在 0393aca3 用 Node 26 直接调用真实函数）：
      - `'a'.repeat(200)` → 输出 200 码点/200 码元，后端通过；
      - `'😀'.repeat(100)` → 输出 103 码点/203 码元，后端 400；
      - `'😀'.repeat(99) + 'ab'` → 输出 104 码点/203 码元，后端 400；
      - `'中'.repeat(200)` → 输出 200 码点/200 码元，后端通过。
      期望：与后端同口径地按 UTF-16 码元截断到 197（截断时不要把代理对切成半个），或由
      lead 决定把后端/契约统一到码点；`limits.test.ts:28-33` 当前断言“200 个 emoji 输出
      200 码点”，该结果在后端是 400，不能作为“不超过上限”的证明。
    accepted_reason: |-
      只有名称含 astral 字符且 UTF-16 码元超过 197 时才触发；服务端 fail-closed，不产生副本、
      草稿或越权数据，用户收到 400 后可改名。ASCII/CJK 主路径已正确截断并有测试，T12d 的
      write_scope 只允许 edit/**，属于可在 T16a/M06 gate 前用小改动关闭的边缘残余；不影响
      F-002 的主路径验收，故接受为 P3。
    evidence: >-
      apps/web/src/pages/workflow/edit/limits.ts:33-40；
      apps/web/src/pages/workflow/edit/limits.test.ts:28-33；
      contracts/openapi.yaml:8871-8877；
      apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionRequestParser.java:88-95,166-180；
      本评审复现命令：node --experimental-strip-types 直接 import limits.ts，观察
      emoji100=203 UTF-16 码元、ASCII200=200 UTF-16 码元。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/lead（角色权限口径）
    due: M06 gate 前
    title: "仅有 WORKFLOW_EDITOR 角色的部门负责人没有 department.read，UI 仍无法选择本部门范围"
    summary: |-
      `apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:126-131`
      的 `WORKFLOW_EDITOR_PERMISSIONS` 不含 `DEPARTMENT_READ`，而部门目录
      `DepartmentController.java:50-61` 要求 `department.read`。`edit/index.tsx:682-694` 对
      403 给出“没有部门读取权限（department.read）……可先发布‘仅自己’范围”的显式告警和
      “重试加载”，`buildScopeOptions` 在 failed 状态禁用“本部门”，因此不会静默忽略；但一个
      只有 WORKFLOW_EDITOR 角色、且是某部门负责人的用户，无法从 UI 选择“本部门”为新流程
      发布（若最近版本已是 DEPARTMENT，`evaluatePublishScope` 会保留原范围并警告，允许再次
      提交）。ADR-0024 §6.1 与角色描述（`RoleDefinitions.java:134-135`）声明 WORKFLOW_EDITOR
      可发布本部门，故角色/权限口径仍需 lead 决策。
    accepted_reason: |-
      目标人物通常是 MEMBER + WORKFLOW_EDITOR（MEMBER 含 department.read，见
      `RoleDefinitions.java:44-60`），仅有 WORKFLOW_EDITOR 属于边界角色组合；页面已显式告警、
      可重试，服务端仍按负责人/范围规则 fail-closed，不产生越权或数据泄露。T12d 的 AC 只要求
      “部门目录失败不静默忽略”，该点在 edit 页已完成；补 department.read 或提供只读部门兜底
      属于角色/产品决策，不阻断 T12d 任务关闭，但必须在 M06 gate 前由 lead 记录决定。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:44-60,126-135；
      apps/api/src/main/java/com/riccon/api/department/controller/DepartmentController.java:50-61；
      apps/web/src/pages/workflow/edit/index.tsx:674-694,1620-1660；
      apps/web/src/pages/workflow/history/format.ts:283-296（buildScopeOptions 的 failed 禁用）；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:481,491；
      apps/web/src/pages/workflow/edit/index.test.tsx:622-640（403 告警与重试测试）。
evidence:
  - EV-1107
  - EV-1108
  - EV-1109
  - EV-1110
reviewed_evidence:
  - EV-1107
  - EV-1108
  - EV-1109
  - EV-1110
created_at: "2026-11-25T23:36:00+08:00"
---

# REV-TASK-0247-001：任务 TASK-0247 独立评审

## 结论

**pass（带 2 个 P3 accepted 残余）**。T12d 在 `apps/web/src/pages/workflow/edit/**` 内关闭了
REV-TASK-0230-001 F-001/F-002/F-005 的 edit 页主路径：DEPARTMENT 重开不再静默回退
ONLY_SELF，部门目录 loading/loaded/failed 有独立状态、显式告警与重试，范围 UI 与后端
TENANT_ADMIN/WORKFLOW_EDITOR 口径一致；IN 值 20/类型、审批人 50、副本名 200 均有客户端
与已保存图校验及测试。没有发现新的 P0/P1/P2；两个 P3 边界残余（astral 副本名、仅有
WORKFLOW_EDITOR 角色时的 department.read）已记录并接受。E2E/axe/覆盖率/性能由 T16a 与
模块 gate 承担，本次未运行。

## 评审对象与范围

| 项          | 值                                                                                                                     |
| ----------- | ---------------------------------------------------------------------------------------------------------------------- |
| 任务卡      | `.riccon/tasks/TASK-0247.json`，status=review，owner=riccon/builder-b1                                                 |
| baseline    | `96397107`（任务卡 `baseline_commit`）                                                                                 |
| 代码提交    | `0393aca3`（任务卡 `end_commit`，8 个文件）                                                                            |
| 证据提交    | `bb858487`（clean；相对 0393aca3 只多 T12b 评审文档，代码树相同）                                                      |
| 评审时 HEAD | `8696d6f8`（T12b 关闭）；`git diff 0393aca3 HEAD -- apps/web/src/pages/workflow/edit` 为空，`history/format.ts` 也未变 |
| 证据        | EV-1107（web test 981）、EV-1108（typecheck）、EV-1109（lint）、EV-1110（build）                                       |

`git diff --name-status 96397107 0393aca3` 只有：

```
apps/web/src/pages/workflow/edit/index.test.tsx
apps/web/src/pages/workflow/edit/index.tsx
apps/web/src/pages/workflow/edit/limits.test.ts
apps/web/src/pages/workflow/edit/limits.ts
apps/web/src/pages/workflow/edit/node-form.test.ts
apps/web/src/pages/workflow/edit/node-form.ts
apps/web/src/pages/workflow/edit/validation.test.ts
apps/web/src/pages/workflow/edit/validation.ts
```

未改后端、`contracts/`、`docs/PRODUCT.md`、`AGENTS.md`，也未改 T12a/T12b/T12c 源码；对
`../history/format` 的 `buildScopeOptions`、`evaluatePublishScope`、`describeVersionScope`
和 `DepartmentsState` 只做只读复用，符合 `write_scope`。T12b 后续的 history/** 修复
（4ec6dc91）与 T12d 无关，本评审不据此加减分。

## 验证了什么

### 1. F-001：DEPARTMENT 重开与部门目录状态

- 旧实现中“`managerDepartments.length===0` 立即 `setRunScope('ONLY_SELF')`”的兜底 effect
  已删除；`loadDetail` 只在 `scopeInitializedFor.current !== workflowId` 时按
  `latestVersion.runScope/departmentId` 初始化一次（`index.tsx:585-594`），之后的
  “重新加载/放弃修改”不会覆盖用户当前范围。
- 部门目录从模型/工具目录中拆出，独立维护 `idle/loading/loaded/failed` 与
  `departmentsError`、`departmentReloadKey`（`index.tsx:658-696`）；403 有专门的
  department.read 文案，其他错误有重试文案；失败时 `buildScopeOptions` 禁用“本部门”，
  但 `evaluatePublishScope` 在“最近版本就是该部门”时保留范围并给出警告，不静默回退。
- 发布入口 `openPublish`/`handlePublish` 在 `!scopeEvaluation.allowed` 时直接阻断，
  发布弹窗展示 `scopeImpactText` 和 warning，发布成功后以服务端返回的
  `version.runScope/departmentId` 回写本地范围（`index.tsx:1043-1049`）。
- 测试覆盖：加载中保留 DEPARTMENT 并完成发布（`index.test.tsx:556-598`）、租户管理员
  恢复 DEPARTMENT 时显式阻断且两个发布按钮都禁用（`:601-620`）、部门目录 403 告警 +
  “重试加载”后恢复（`:622-640`）；`format.test.ts:230-278` 覆盖 loaded/failed/loading
  的允许/阻断分支。
- 残余说明：目录 **loaded** 且最近版本部门已不在负责人列表时，`evaluatePublishScope`
  返回 `allowed=true + warning`（`format.ts:359-370`），用户仍可提交并得到服务端 400。
  这是 T12b 共用纯函数的既有设计（“保留原范围 + 服务端再校验”），不是 T12d 引入的静默
  回退；原 F-001 的“回退并提示”在这里被替换为“保留并显式警告”，满足任务 AC 的
  “不得静默回退 ONLY_SELF”。T16a 应把该真实 400 路径记入 AC 残余，而不是当作主路径通过。

### 2. F-002：IN 值/审批人/副本名限制

- `limits.ts` 集中定义 `MAX_CONDITION_IN_VALUES=20`、`MAX_ASSIGNEE_USER_IDS=50`、
  `MAX_WORKFLOW_NAME=200`，并新增 `duplicateWorkflowName`。
- 节点表单：IN 分支先查空、再查 >20、再按 `NUMBER`/`BOOLEAN` 逐项校验
  （`node-form.ts:446-472`）；按人审批在 UUID 校验之后检查 >50（`:392-407`）。测试
  `node-form.test.ts:314-361` 覆盖 21 项、`1、abc`、`true、yes` 与 50/51 人边界。
- 已保存图：`validation.ts:550-580` 对 `condition.right.values` 检查空、>20 和
  `matchesConditionValue` 类型一致性；`validation.ts:476-485` 检查审批人 >50。
  `matchesConditionValue`（`:680-697`）与后端 `WorkflowValidator.matchesValueType`
  （`instanceof Number/String/Boolean`）对 JSON 解析后的值同口径。测试
  `validation.test.ts:263-336` 覆盖 21 项、`[1,'2']` 类型不匹配和 50/51 人。
- 副本名：`handleDuplicate` 使用 `duplicateWorkflowName(name, detail?.name)`，ASCII/CJK
  200 字符会截断到 197 后加“ 副本”，总长 200；`index.test.tsx:643-680` 通过冲突弹窗
  验证 `duplicateWorkflow` 与 `saveDraft` 都收到 200 字符名称。astral 字符的码点/码元
  差异见 F-001。

### 3. F-005：角色与范围口径

- 前端 `canManage = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.manage)`
  （`index.tsx:513`），后端 `WorkflowPublishController.actor` 的 `tenantAdmin` 也是
  `hasPermission(PermissionCodes.WORKFLOW_MANAGE)`（`WorkflowPublishController.java:106-116`），
  而 `WORKFLOW_MANAGE` 仅在内置 `TENANT_ADMIN_PERMISSIONS`（`RoleDefinitions.java:63-99`）。
  因此“租户管理员只能 ONLY_SELF/TENANT、其他发布者只能 ONLY_SELF/DEPARTMENT”的 UI 与
  `WorkflowPublishScopeValidator.resolve`（`WorkflowPublishScopeValidator.java:60-84`）一致。
- 编辑者测试会话改为 `WORKFLOW_EDITOR`（不再带 `workflow.manage`），租户管理员单独
  `TENANT_ADMIN_SESSION`，测试断言“本租户”禁用、租户管理员恢复 DEPARTMENT 被阻断。
- 部门目录失败不再静默：warning Alert、403 专属文案、重试按钮、DEPARTMENT 选项禁用；
  `departmentOptions` 在最近版本部门不在目录时保留“（最近发布版本）”选项，避免选择器
  空白或被迫改回“仅自己”。
- 仅有 WORKFLOW_EDITOR 角色且无 department.read 的残余见 F-002：UI 已显式提示，但没有
  角色决策，需 lead 在 M06 gate 前记录。

### 4. 证据与独立复跑

- EV-1107..EV-1110 的 `commit=bb858487`、`dirty=false`、`exit_code=0`，四个日志
  `sha256` 与 JSON 中的 `output_sha256` 全部一致；`git diff 0393aca3 bb858487` 只多
  `.riccon/reviews/tasks/TASK-0233-review.md`，T12d 代码树相同。EV ID 在 `.riccon/evidence`
  中无重复。
- 本次在独立 detached worktree（`git worktree add --detach ... 0393aca3`，临时软链
  `node_modules` 供依赖解析，不改主工作区）复跑：
  - `pnpm --filter @riccon/web test`：**105 files / 981 tests passed**，与 EV-1107 一致；
  - `pnpm --filter @riccon/web exec vitest run src/pages/workflow/edit`：**5 files / 68 tests passed**；
  - `pnpm --filter @riccon/web run typecheck`：exit 0；
  - `pnpm --filter @riccon/web run lint`：exit 0；
  - `pnpm --filter @riccon/web run build`：exit 0。
- 新测试没有 `.skip`/`.only`/`todo`；F-001/F-005 的发布测试实际断言
  `publishWorkflow`/`validateWorkflow` 的入参，DOM 状态与按钮禁用也做了断言，不是只渲染
  不校验的假绿。

## 未验证

- **真实浏览器 E2E/axe/375px**：T16a 负责；本次未启动 compose/浏览器，未执行
  `commands.e2e`、`commands.a11y`。
- **模块级全量合同命令与覆盖率**：本任务 AC 只要求 web test/typecheck/lint/build；
  `coverage`、Maven verify/jacoco、合同 `test`/`coverage` 的完整组合留给 M06 模块 gate，
  不能把 EV-1107 当作模块 tests/coverage 证据。
- **后端/契约改动**：T12d 未改后端；本次没有起 API/MySQL，也没有跑后端集成测试。
  后端 `WorkflowValidator`/`WorkflowPublishScopeValidator` 只做源码口径对照。
- **T12b/T12c 自身实现**：history/** 的 a11y 修复、路由与导航不属本任务；本评审只确认
  `edit/**` 对 `history/format` 的只读调用在 0393aca3 可编译、可测试。
- **UI 视觉与真实键盘路径**：只在 jsdom 组件测试中验证，未做真实浏览器截图/键盘全流程。

## Findings

### F-001（P3，accepted）：副本名按码点截断与后端 UTF-16 码元上限不一致

- 位置：`apps/web/src/pages/workflow/edit/limits.ts:33-40`；`limits.test.ts:28-33`；
  `WorkflowDefinitionRequestParser.java:88-95,166-180`；`contracts/openapi.yaml:8871-8877`。
- 观察：`'😀'.repeat(100)` 经 `duplicateWorkflowName` 输出 203 个 UTF-16 码元，后端
  `String.length()>200` 直接 400；`'a'.repeat(200)` 与 `'中'.repeat(200)` 都是 200 码元，
  正常通过。现有 emoji 用例断言 200 码点（实际 400 码元），不能证明“不超过后端上限”。
- 期望：客户端按 UTF-16 码元截断到 197 且不拆代理对，或 lead 决策把后端/契约统一到码点。
- 接受理由：边缘输入、服务端 fail-closed、无数据/权限影响；ASCII/CJK 主路径已修复并测试；
  T12d write_scope 只允许 edit/**，可在 M06 gate 前小改关闭。

### F-002（P3，accepted）：仅有 WORKFLOW_EDITOR 角色时缺 department.read

- 位置：`RoleDefinitions.java:44-60,126-135`；`DepartmentController.java:50-61`；
  `edit/index.tsx:674-694,1620-1660`；`format.ts:283-296`；ADR-0024 §6.1/§7.4。
- 观察：只有 WORKFLOW_EDITOR 角色的部门负责人请求 `/departments` 会 403，UI 显式告警、
  可重试，但无法为新流程选择“本部门”；后端仍允许该用户按负责人关系发布 DEPARTMENT。
- 期望：lead 在 M06 gate 前决定给 WORKFLOW_EDITOR 补 department.read，或提供只读部门
  兜底/明确角色组合；当前 UI 已不再静默忽略。
- 接受理由：默认人物是 MEMBER + WORKFLOW_EDITOR（MEMBER 含 department.read），单独
  WORKFLOW_EDITOR 属边界；服务端 fail-closed、无越权/泄露；T12d AC 只要求失败不静默，
  角色决策归 lead，不阻断本任务。

## 剩余风险

1. **F-001 副本名 astral 残余**：T16a 若用 emoji 名称夹具验收“另存副本”，仍可能看到
   400；建议在 M06 gate 前按 F-001 修正并补一个 UTF-16 码元断言。
2. **F-002 角色口径**：若产品坚持“仅 WORKFLOW_EDITOR 也可发布本部门”，需补权限或
   兜底；否则应在 ADR/角色说明中明确需要 MEMBER + WORKFLOW_EDITOR 组合，避免 T16a
   用管理员会话掩盖。
3. **loaded 但负责人关系消失时的“警告 + 允许提交”**：继承自 T12b 的
   `evaluatePublishScope`，会得到服务端 400；不是静默回退，但 T16a 应把它记录为 AC 残余
   而非主路径通过。
4. **证据提交与代码提交差一个提交**：EV 在 `bb858487` 录制，比 `end_commit` `0393aca3`
   多一个只含 T12b 评审文档的提交；代码树相同，任务级可接受。模块 gate 必须在最终提交上
   重新录制合同 `test/coverage` 全量 EV，不能把 EV-1107 当作模块证据。
5. **共享纯函数漂移**：T12d 复用 `history/format` 的范围纯函数；本次核对
   `git diff 0393aca3 HEAD -- history/format.ts` 为空，但 M06 gate 前若 T12b 再改该文件，
   应重跑本任务的 edit 定向测试。

## 证据链接

- `.riccon/evidence/TASK-0247/EV-1107.json` 与 `EV-1107-*.log`（105 files / 981 tests）
- `.riccon/evidence/TASK-0247/EV-1108.json` 与 `EV-1108-*.log`（typecheck）
- `.riccon/evidence/TASK-0247/EV-1109.json` 与 `EV-1109-*.log`（lint）
- `.riccon/evidence/TASK-0247/EV-1110.json` 与 `EV-1110-*.log`（build）
- 本评审独立复跑日志（临时 worktree）：
  `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t12d-full-test.log`
  （105 files / 981 tests，exit 0）；定向 edit 68 用例、typecheck/lint/build 均 exit 0。
