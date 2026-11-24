---
review_id: REV-TASK-0221-002
module_id: M06
task_id: TASK-0221
previous_review: REV-TASK-0221-001
reviewer:
  agent: riccon/verifier
  session: ses_df076f762ffeoyI2EKJP47Gqfn
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - f6594ad7e44aa9174e63e4a15419ea9f52e6a7c2
  - 0f9e4add28a7a6d78b36bc7ebdfee36c38b1eca6
  - 26a2fbd15cb961a9400f651f8685395746d85394
head_commit: 0f9e4add28a7a6d78b36bc7ebdfee36c38b1eca6
baseline_commit: f5010ac084a7894f2a4fa6d4510fc0bbaee12983
end_commit: f6594ad7e44aa9174e63e4a15419ea9f52e6a7c2
review_scope: >-
  REV-TASK-0221-001 F-001 修复复审：MCP_TOOL.toolId=null 在 SAVE/PUBLISH/RUNTIME 三 scope
  返回 REQUIRED_CONFIG、SAVE 400 且不写库/不写审计、VALIDATE（PUBLISH 口径）阻断、内置模板复制后
  保存/发布须先选工具，以及相关回归与证据复核。不重审已由 T04/T16a 承接的 F-002/F-003/F-004，
  不替代 M06 模块 gate、Product 预验收或用户产品验收。
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
dod_note: >-
  acceptance=true 指 T03 任务范围的 F-001 已关闭、规则与 SAVE/VALIDATE 接线经真实 DB 集成测试确认；
  发布路径、运行前复检、画布/列表 UI 一致性分别由 T04/T05/T10/T16a 验证，本评审不把 API 证据当
  UI/E2E 证据。build 指独立复跑的测试编译/执行，未跑合同全量 build；coverage、E2E、security、
  performance、accessibility 属 T16a/模块 gate，本任务未运行。
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: riccon/builder-b1
    due: "T03 done 前（已完成）"
    title: "MCP_TOOL.toolId=null 未被 SAVE/VALIDATE/PUBLISH 阻断"
    summary: >-
      f6594ad 在 `WorkflowValidator.validateTool` 的 null 分支返回 REQUIRED_CONFIG（node.id、
      config.toolId）。`validate` 对每个节点调用 `validateTool`，scope 只影响审批人可用性，不影响工具
      必填；因此 SAVE/PUBLISH/RUNTIME 三 scope 均被阻断。SAVE 在写库/审计前抛出
      `WorkflowValidationException`（400 RICCON-WF-400）；VALIDATE 使用 PUBLISH 口径返回 valid=false；
      内置模板复制后 validate 与 save 均被阻断。新增单元/集成测试覆盖三 scope、400 不写库、无
      workflow.draft.saved 审计、模板复制阻断；合法图均改为使用已授权工具。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowValidator.java:86-134,605-620；
      apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionService.java:275-312,324-330；
      apps/api/src/test/java/com/riccon/api/workflow/validation/WorkflowValidatorTest.java:58-74；
      apps/api/src/test/java/com/riccon/api/workflow/definition/WorkflowValidationApiTest.java:117-238,498-506；
      apps/api/src/test/java/com/riccon/api/workflow/definition/WorkflowDefinitionApiTest.java:653-763
reviewed_evidence:
  - EV-1029
  - EV-1030
evidence:
  - EV-1029
  - EV-1030
notes: >-
  评审时 HEAD 为 0f9e4ad（仅证据/状态/任务文件），T03 代码与 end_commit f6594ad 一致；独立复跑在
  HEAD 上完成。26a2fbd 是评审前已有的契约 profile 描述提交（14+/9-，info.version 仍 1.6.0-m06，
  未改 schema/paths/components），沿用上一轮结论。按任务要求未运行 gate check，未运行全量模块
  build/test/coverage/E2E/security/perf/a11y，未修改生产源码、测试、迁移、契约、PRODUCT、AGENTS
  或 .riccon 状态；只新增本评审文件。
created_at: "2026-11-25T05:56:00+08:00"
---

# REV-TASK-0221-002：M06 T03 F-001 修复复审

## 结论

**通过（pass）**。F-001 已修复：`MCP_TOOL.toolId=null` 在 SAVE/PUBLISH/RUNTIME 三个 scope 均返回
`REQUIRED_CONFIG`（`nodeId` 为工具节点、`field=config.toolId`），不再有放行分支；SAVE 返回 400
`RICCON-WF-400` 且在写库/审计前失败；VALIDATE（PUBLISH 口径）返回 `valid=false` 与结构化 issues；
内置模板复制后保留 `toolId=null`，保存与发布口径校验都被阻断。合法测试图均改为使用已授权工具。
独立复跑 5 个测试类共 **41 个用例，0 failures / 0 errors / 0 skipped**；`spotless:check` 通过；
EV-1029/EV-1030 的 commit、dirty、exit code 与输出 hash 复核一致。

本轮只复核 F-001 与相关回归；REV-TASK-0221-001 的 F-002/F-003/F-004 仍为 accepted，分别由
T04/T16a 承接，本评审不重新裁定、不改变其 owner/due。

## F-001 修复核验

### 三 scope 一致性

- `WorkflowValidator.validate` 在节点循环中对 `MCP_TOOL` 无条件调用 `validateTool`
  （`WorkflowValidator.java:119-131`）；scope 只通过 `validateApproval` 的
  `checksApproverAvailability()` 影响审批人可用性（`WorkflowValidationScope.java:10-28`），工具必填
  不随 scope 变化。
- null 分支现在产生 `REQUIRED_CONFIG`、`node.id()`、`config.toolId`，并带明确说明
  （`WorkflowValidator.java:611-620`），随后直接返回，不会落入授权/停用/审批前驱检查，也不存在
  “null 视为不发起调用”的旧放行逻辑。
- 单元测试 `WorkflowValidatorTest.nullToolId_isRequiredConfig` 遍历
  `WorkflowValidationScope.values()`（SAVE、PUBLISH、RUNTIME），逐 scope 断言 `valid=false`，且
  issues 中存在 `REQUIRED_CONFIG` + `nodeId=tool` + `field=config.toolId`
  （`WorkflowValidatorTest.java:58-74`）。

### SAVE：400、不写库、版本不变、无保存审计

- `WorkflowDefinitionService.saveDraft` 在 `repository.updateDraft` 与
  `auditService.append(draftSaved)` 之前调用校验；`!valid()` 直接抛
  `WorkflowValidationException`，事务内没有写入（`WorkflowDefinitionService.java:275-312`）。
- 集成测试 `WorkflowValidationApiTest.nullToolId_isRequiredAndBlocksSaveValidate` 使用 `TEMPLATE_GRAPH`
  （`toolId:null`）保存：`save400` 断言 HTTP 400 且 `code=RICCON-WF-400`，detail 同时包含
  `REQUIRED_CONFIG`、`node=tool`、`field=config.toolId`；随后断言 `draft_version` 仍为 1、
  `workflow.draft.saved` 审计为零、草稿图仍为空（`WorkflowValidationApiTest.java:209-221`）。
  `save400` 辅助方法本身断言 400 + `RICCON-WF-400`（`:688-694`）。

### VALIDATE（PUBLISH 口径）与模板复制

- `validateDraft` 只读，调用 `WorkflowValidationRequest.publish(...)`，返回
  `WorkflowValidationResult{valid,issues}`，不推进版本、不写审计
  （`WorkflowDefinitionService.java:324-330`；controller `POST /{workflowId}/validate` 未变）。
- 新测试从 V15 种子模板 `01990000-0000-7000-8000-000000000601` 创建草稿，断言 201；随后 VALIDATE
  返回 `valid=false`，issues 中存在 `REQUIRED_CONFIG` + `nodeId=tool` + `field=config.toolId`；再保存
  同一 `toolId:null` 图仍 400，`draft_version` 保持 1（`WorkflowValidationApiTest.java:223-237`）。
- V15 种子图确实使用 `toolId:null`（`V15__workflow_studio.sql:479-497`），`createFromTemplate` 原样
  复制 `template.graph()`（`WorkflowDefinitionService.java:225-260`），因此该用例覆盖的是实际内置
  模板复制路径，而不是仅测试常量。
- 错误 detail 会带上 issue message，null 工具节点明确提示 “select an authorized tool before saving or
  publishing”（`WorkflowValidationException.java:48-68`）。测试断言了 code/node/field 与 400，未逐字
  断言该英文文案；T14 工具选择入口与 T10 画布/列表中文提示仍需在对应任务验证。

### 合法图与回归

- `WorkflowDefinitionApiTest` 的夹具现在为每个租户创建已授权、ACTIVE、requires_approval 的 MCP 工具，
  `graph(fixture)` 把模板常量中的 `toolId:null` 替换为该工具 ID
  （`WorkflowDefinitionApiTest.java:653-763`）；所有原 `GRAPH` 使用点均改为 `graph(fixture)`，未降低
  原有断言。
- `WorkflowValidationApiTest` 的合法保存/校验图使用 `templateGraph(toolId)`，工具由
  `createTool(..., ACTIVE, true, true)` 授权（`WorkflowValidationApiTest.java:117-140,498-506`）；
  原非法图用例改用 `validWithTool` 隔离工具变量，`RESULT_INVALID` 用例改用无工具的
  `CONDITION_GRAPH`，避免被 REQUIRED_CONFIG 遮蔽（`:145-204,400`）。
- 全仓库检索未发现除负向用例外仍以 `"toolId":null` 作为合法图并期待保存/校验通过的测试；模板 ID
  仅在上述两个 workflow 测试和 V15 迁移测试中使用。

## 测试与证据复核

### 独立复跑

命令（与 EV-1029 相同）：

```text
./mvnw -q -pl apps/api -am test -Dtest='WorkflowDefinitionApiTest,WorkflowGraphDomainTest,WorkflowGraphJsonTest,WorkflowValidatorTest,WorkflowValidationApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

在 HEAD `0f9e4ad`（代码与 `f6594ad` 相同）上 exit code 0；surefire 汇总：

| 测试类                      | 用例数 | 结果                              |
| --------------------------- | -----: | --------------------------------- |
| `WorkflowDefinitionApiTest` |      7 | 0 failures / 0 errors / 0 skipped |
| `WorkflowGraphDomainTest`   |      4 | 0 failures / 0 errors / 0 skipped |
| `WorkflowGraphJsonTest`     |      6 | 0 failures / 0 errors / 0 skipped |
| `WorkflowValidatorTest`     |     14 | 0 failures / 0 errors / 0 skipped |
| `WorkflowValidationApiTest` |     10 | 0 failures / 0 errors / 0 skipped |
| **合计**                    | **41** | **0 / 0 / 0**                     |

新增用例确实执行：surefire XML 的 testcase 列表包含 `nullToolId_isRequiredConfig` 与
`nullToolId_isRequiredAndBlocksSaveValidate`；5 个测试类无 `@Disabled`/`assumeTrue`/跳过标记。
`./mvnw -q spotless:check` 独立复跑 exit 0。

### EV-1029 / EV-1030

- **EV-1029**：命令同上；commit `f6594ad7e44aa9174e63e4a15419ea9f52e6a7c2`；manual=false、
  dirty=false、exit_code=0；output 30,347 bytes，sha256
  `d63013e64dca526b85a75aaee89d726980c09ef13ac188804bf9f7e1722b9d0d`，与证据 JSON 和实际文件一致。
  该次运行的 surefire 报告（05:47）为 7+4+6+14+10=41，0 failures/0 errors/0 skipped。因命令带 `-q`，
  EV 日志没有 surefire 汇总行，只能从该次 surefire 报告核对；日志无 ERROR/FAILURE，exit 0。
- **EV-1030**：`./mvnw -q spotless:check`；commit `f6594ad`；manual=false、dirty=false、exit_code=0；
  output 249 bytes，sha256
  `25722adb66e9ea178709ee5a1d04ea724f4e8a7bc6c74e9a24375a50d6fa9c55`，一致。
- 取证提交 `0f9e4ad` 的 `git diff --name-status f6594ad..0f9e4ad` 只有 `.riccon/evidence/TASK-0221/**`、
  `.riccon/state.json`、`.riccon/tasks/TASK-0221.json`，没有源码/测试漂移。

## 范围与合同

- `git show --name-only f6594ad` 仅包含：`WorkflowValidator.java`、`WorkflowDefinitionApiTest.java`、
  `WorkflowValidationApiTest.java`、`WorkflowValidatorTest.java` 以及 `.riccon/state.json`、
  `.riccon/tasks/TASK-0221.json`。生产代码只动 `workflow/validation/**`；测试只动
  `workflow/validation/**` 与既有 `workflow/definition/**`，均命中任务卡 write_scope。
- f6594ad 与 0f9e4ad 未触碰 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`
  或 J1/J2 生产代码；未启动 M06b。`git diff --check f5010ac..f6594ad -- apps/api/src/main apps/api/src/test`
  clean。
- 26a2fbd（评审前已有）只改 `contracts/README.md` 与 `contracts/openapi.yaml` 的 description；
  `info.version` 仍为 `1.6.0-m06`，未改 schema/paths/components。其 SAVE 描述“非法图返回 400、不写草稿、
  不写审计、缺失必填不允许”与本轮实现现已一致；F-002 的 VALIDATE=发布范围文案与 runScope 问题仍
  accepted，由 T04 落实，本轮不重开。

## AC 对账（T03 范围）

- **M06-AC-02（T03 部分，pass）**：未填写必填参数中的 `MCP_TOOL.toolId=null` 现在在保存与
  VALIDATE/PUBLISH 口径均逐项返回 `REQUIRED_CONFIG`，不再出现“保存成功且 validate=true”。发布版本
  生成、画布/列表 UI 一致性仍由 T04/T10/T16a 验证，本轮不把 API 证据当作 UI/发布证据。
- **M06-AC-18（T03 静态部分，pass）**：引用未授权/停用工具仍被现有测试阻断；null 工具现在也被阻断。
  工具运行时授权复查、写工具重试重新审批、提示注入边界仍归 T07b/T16a。
- **M06-AC-06/09/24**：本轮未改相关规则；上一轮 41 个用例中的审批 NO_APPROVER、条件、负向节点类型
  用例全部通过。运行时等待、条件求值与 UI 入口仍由 T05/T06b/T10/T16a 验证。

## 未验证

- T04 发布路径：发布不可变版本、`graph_sha256`、runScope 角色/部门范围、失败不产生版本。
- T05/T06b 运行：RUNTIME scope 接线、条件求值、工具审批等待、`WF_NO_APPROVER`/`WF_CONDITION_INVALID`
  等运行时行为。
- T10/T14/T16a：画布与列表/表单同问题列表、工具选择入口与中文提示、加载/空/错误态、axe/键盘/
  375px、全量 E2E、coverage、security、perf、ArchUnit、`WorkflowContractM06Test`、模块 gate。
- 按任务要求未运行 `riccon gate check --module M06`；本评审不替代 M06 模块 review、Product 预验收
  或用户验收。

## Findings

- **F-001（P1，fixed）**：`MCP_TOOL.toolId=null` 已在 SAVE/PUBLISH/RUNTIME 三 scope 返回
  REQUIRED_CONFIG；SAVE 400 不写库/不写审计、VALIDATE 阻断、模板复制后保存/发布需先选工具；新增
  单元与真实 DB 集成测试，合法图改用已授权工具。owner=riccon/builder-b1，due=T03 done 前（已修复）。
- 本轮未发现新的 P0/P1/P2。REV-TASK-0221-001 的 F-002（P2，accepted，owner=Lead/Architect/T04，
  due=M06 gate 前）、F-003（P3，accepted，owner=Lead/T16a，due=M06 gate 前）、F-004（P3，accepted，
  owner=builder-b1/T16a，due=M06 gate 前）按派单要求不重审，状态与承接关系保持不变。

## 剩余风险

1. **T14/T10 的工具选择与文案**：validator 已返回明确英文 issue message，但内置模板复制后的中文
   “需选择工具”提示、画布/表单入口和同问题列表仍由 T14/T10 实现；本轮只验证 API 行为。
2. **测试数据卫生**：新/旧 workflow 测试创建的 MCP server/tool/grant 没有统一 cleanup，沿用既有模式；
   本轮 41 个用例未出现跨测试污染，若 MCP 目录用例改为全局精确计数，T16a 需补清理。
3. **F-002 的 VALIDATE/runScope 文案**仍与当前端点能力不完全一致，T04 必须补发布路径的 runScope
   校验并在 T10 前对齐文案，避免把 “validate 通过” 当成“必可发布”。
4. **证据日志为 `-q`**：EV-1029 自身不含 surefire 汇总行；本轮用该时间点的 surefire 报告核对 41 个
   用例。模块 gate 仍应在最终提交上跑一次全量并记录含汇总的 EV。

## 是否可 done

**可 done（T03 任务级）**：F-001 已 fixed，独立复跑 41 个用例 0 失败/0 错误/0 跳过，spotless 通过，
范围与证据复核一致。该结论只覆盖 T03 任务范围；T04 发布、T05/T06b 运行、T10/T14/T16a UI/E2E/
coverage/security/perf 与 M06 模块 gate 仍需分别完成，本评审未运行 gate check。
