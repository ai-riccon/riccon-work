---
review_id: REV-TASK-0221-001
module_id: M06
task_id: TASK-0221
reviewer:
  agent: riccon/verifier
  session: ses_df08ae3d0ffezXlRdbNTkZJxpC
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - f9ab9b1c5cd0dd59b8f151a812eeb1e30e927430
  - 684ba32b0c03ea954bda317702738aed06ca0447
  - b0950a584ccd4c38be106d2a9e4a2cc99d5c525b
  - 26a2fbd15cb961a9400f651f8685395746d85394
head_commit: 26a2fbd15cb961a9400f651f8685395746d85394
baseline_commit: f5010ac084a7894f2a4fa6d4510fc0bbaee12983
review_scope: >-
  M06 T03（TASK-0221）工作流校验器、SAVE 接入与 POST /workflows/{id}/validate 的独立任务评审。
  核对 ADR-0024 §4.1/§4.4/§4.5/§6.2/§9.3、PRODUCT §12 AC-02/06/09/18/24、契约 1.6.0-m06 与
  26a2fbd 的 SAVE/VALIDATE/PUBLISH profile 对齐；不替代 M06 模块 gate、Product 预验收或用户产品验收。
dod:
  acceptance: false
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
  acceptance=false 仅因 F-001（P1）：MCP_TOOL 的 toolId=null 未被 SAVE/VALIDATE/PUBLISH 拒绝。
  build 指独立复跑的测试编译/执行通过，未跑合同全量 build；coverage、E2E、安全、性能、a11y 属
  T16a/模块 gate，本任务未运行。UI（画布/表单同问题列表）由 T10/T16a 验证。
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-b1
    due: "T03 done 前；若改为发布级守卫则 T04 开工前"
    title: "MCP_TOOL.toolId=null 未被 SAVE/VALIDATE/PUBLISH 阻断，内置模板图可原样通过发布口径校验"
    summary: >-
      `WorkflowValidator.java:611-615` 在 toolId 为空时直接 return，未产生 REQUIRED_CONFIG；
      `WorkflowValidationApiTest.java:72-90,114-136` 用 toolId=null 的 TEMPLATE_GRAPH 保存并调用
      VALIDATE（PUBLISH scope），断言 `valid=true`，本次独立复跑该用例通过。观察：复制内置模板后不改工具，
      可保存草稿，且 VALIDATE 返回 `valid=true`（`NO_APPROVER` 由其他租户管理员满足）。期望：MCP_TOOL
      节点缺少 toolId 时至少按“缺失必填”返回 REQUIRED_CONFIG（SAVE/PUBLISH 一致），或由 Lead 明确修订
      ADR §4.4/契约 26a2fbd 的“缺失必填”口径。依据：ADR-0024 §4.4 `MCP_TOOL` 配置列为 `toolId`、
      `argsTemplate`（无 `?`），`MODEL` 才标 `modelId?`；26a2fbd `contracts/openapi.yaml:2643-2644`
      明确 SAVE 拒绝“缺失必填”；T02 评审
      `REV-TASK-0220-001`（`.riccon/reviews/tasks/TASK-0220-review.md:262,334`）已把
      “模板 toolId=null”交给 T03/T14，并写明“不把模板原样当可发布流程”。风险：AC-02 的“未填写必填参数
      → 发布被阻止”和 AC-18 的工具前置条件对该节点不成立；发布出的流程运行到该节点只能失败或空操作。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowValidator.java:611-615；
      apps/api/src/test/java/com/riccon/api/workflow/definition/WorkflowValidationApiTest.java:72-90,114-136；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:346；
      contracts/openapi.yaml:2643-2644；
      apps/api/src/main/resources/db/migration/V15__workflow_studio.sql:481；
      .riccon/reviews/tasks/TASK-0220-review.md:262,334
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/lead / riccon/architect（T04 落实）
    due: "M06 gate 前（T04 开工时定稿）"
    title: "26a2fbd 的 VALIDATE=发布范围与 SAVE 同口径文案超出当前实现/端点能力"
    summary: >-
      `contracts/openapi.yaml:2714` 声明 VALIDATE 与 PUBLISH 完全同口径，并包含“发布范围”；
      但 VALIDATE 端点没有 runScope 请求体/参数（`:2710-2716`），`WorkflowDefinitionService.validateDraft`
      （`WorkflowDefinitionService.java:324-330`）只把草稿交给 PUBLISH scope，校验器不检查 runScope。
      同一文件 `:2644` 又说 SAVE 的 issues 与 `/validate` 同口径，但同段明确允许 NO_APPROVER 在保存
      暂不阻断（实现也确实如此，`WorkflowValidationApiTest.java:270-290`）。期望：把文案改为
      “SAVE=编辑级（NO_APPROVER 暂缓）；VALIDATE=发布口径的结构/必填/模型/工具/条件/结果/审批可达，
      不含 runScope；PUBLISH 再加 runScope 角色/部门范围”，或给 VALIDATE 增加可选 scope 参数。
      影响：UI 可能显示“校验通过”但发布因 runScope 失败；不会造成安全或数据问题。
    accepted_reason: >-
      contracts 不在 T03 write_scope；runScope 只有发布请求才携带，validate 无法在无参数时判定。
      T04 必须在 publish 路径独立校验 runScope，并在 T10 接入前对齐文案；该 residual 不阻塞 T03 的
      规则/接线，但 M06 gate 前必须由 Lead/Architect/T04 收敛，避免把“validate 通过”当成“必可发布”。
    evidence: >-
      contracts/openapi.yaml:2643-2644,2710-2716；
      apps/api/src/main/java/com/riccon/api/workflow/definition/service/WorkflowDefinitionService.java:314-330；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowValidationScope.java:10-28；
      apps/api/src/test/java/com/riccon/api/workflow/definition/WorkflowValidationApiTest.java:270-290
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/lead（T-M06-01 契约测试 owner）/ T16a
    due: "M06 gate 前"
    title: "contracts/README 声称 WorkflowContractM06Test 断言 SAVE/VALIDATE/PUBLISH profile，但测试没有这些断言"
    summary: >-
      26a2fbd 在 `contracts/README.md:190` 追加“并断言校验 profile：SAVE 非法图返回 400 ... VALIDATE ...
      PUBLISH ...”。实际 `WorkflowContractM06Test.java` 只有 6 个测试方法（版本/路径/operation 权限、
      图词汇、节点配置、运行/审批 schema、拒绝理由、模板延迟字段），没有任何保存 400、VALIDATE 只读或
      PUBLISH 失败不产版本的断言；这些行为由 `WorkflowValidationApiTest`（T03）和 T04 测试覆盖。
      期望：README 指向实际测试类，或在 T16a 契约套件补齐断言后再声称。影响仅为契约交付文档准确性。
    accepted_reason: >-
      是文档准确性 residual，不影响 T03 运行行为与安全；实际接口行为已有 T03 集成测试，T04 还会补发布测试。
      T16a 契约/模块 gate 前统一 README 与测试归属即可。
    evidence: >-
      contracts/README.md:190；
      apps/api/src/test/java/com/riccon/api/contract/WorkflowContractM06Test.java:40-134,136-193,195-248,250-271
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b1 / T16a
    due: "M06 gate 前"
    title: "校验器若干规则分支无直接负向测试（END 出边、START 入边、MODEL 动作、WRITE 风险、ROLE 无审批人、provider 状态）"
    summary: >-
      `WorkflowValidatorTest`（13 用例）覆盖 START/END、环/孤立/入度/出度、条件分支、targetNodeKey
      pattern、必填、模型、工具授权/停用/审批前驱、审批配置/NO_APPROVER、条件值、resultFrom 与上限；
      `WorkflowValidationApiTest`（9 用例）覆盖真实 DB/权限/保存不写库。未直接覆盖：END 节点的出边
      （`WorkflowValidator.java:242-248`）、START 的入边（`:249-255`）、`actionKind=MODEL` 的后继绑定
      （`:748-757`）、`risk_level=WRITE` 触发审批（`WorkflowToolAvailability.java:65-67`）、ROLE 无可用
      审批人的真实 SQL（`WorkflowValidationJdbc.java:74-83`）、provider 停用/协议/端点允许列表分支
      （`WorkflowValidationRepository.java:59-73`）。这些分支逻辑简单且非当前主链路，但高风险管理模块
      应在 T16a/模块覆盖率中补齐；F-001 的 null tool 负向用例也在修复时一并补。
    accepted_reason: >-
      主路径（结构、必填、工具授权/停用、审批前驱、NO_APPROVER、模型 REPORT、条件、结果、上限）均有
      单元或真实 DB 集成测试；缺口是分支覆盖而非已知错误。M06 为 T4，T16a 的 SEC-WF-004/005 与模块
      coverage 会把剩余分支纳入；不阻塞 T03，但 F-001 修复时必须增加 null toolId 的保存/校验负向用例。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/workflow/validation/WorkflowValidatorTest.java:44-461；
      apps/api/src/test/java/com/riccon/api/workflow/definition/WorkflowValidationApiTest.java:114-390；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowValidator.java:242-255,748-757；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowToolAvailability.java:65-67；
      apps/api/src/main/java/com/riccon/api/workflow/validation/persistence/WorkflowValidationJdbc.java:74-83；
      apps/api/src/main/java/com/riccon/api/workflow/validation/repository/WorkflowValidationRepository.java:59-73
reviewed_evidence:
  - EV-1026
  - EV-1027
  - EV-1028
evidence:
  - EV-1027
  - EV-1028
notes: >-
  评审时 HEAD 为 26a2fbd（仅 contracts/README.md 与 contracts/openapi.yaml 描述，无 schema 变更），
  T03 代码提交为 f9ab9b1；684ba32/b0950a5 只追加 EV-1027/EV-1028 证据。独立复跑在 HEAD 上完成，
  T03 代码与 f9ab9b1 一致。按任务要求未运行 `gate check --module M06`，未运行全量 E2E/perf/coverage/
  security/axe，未修改生产源码、测试、迁移、契约、PRODUCT、AGENTS 或 `.riccon` 状态。
created_at: "2026-11-25T05:50:00+08:00"
---

# REV-TASK-0221-001：M06 T03 校验器与 SAVE/VALIDATE 接线独立评审

## 结论

**不通过（fail）**。T03 的规则主体、接线和证据总体扎实：结构（START/END、环、孤立、入度/出度、条件
TRUE/FALSE、节点/边上限）、targetNodeKey pattern、必填参数、模型 REPORT/ACTIVE、工具授权/停用/审批
前驱、审批 assignee/actionKind/targetNodeKey、发布级 NO_APPROVER、条件源/算子/值类型、END resultFrom
与负向节点类型均有实现和测试；`saveDraft` 在写库/审计前校验，非法图返回 400 且不写库；`POST
/workflows/{id}/validate` 只读、返回 `WorkflowValidationResult{valid,issues}`、不改 draftVersion、不写
审计；独立复跑 5 个测试类 39 个用例 0 失败/0 错误/0 跳过，spotless 通过。

但 **F-001（P1，open）** 使任务不能 done：`MCP_TOOL` 的 `toolId=null` 被 `WorkflowValidator` 直接放行，
内置模板图（toolId 为空）可保存，且 VALIDATE（PUBLISH scope）返回 `valid=true`。这与 ADR-0024 §4.4
（MCP_TOOL 的 toolId 必填）、26a2fbd 契约“SAVE 缺失必填不写库”、AC-02“未填写必填参数发布被阻止”冲突，
也违背 T02 评审“不把模板原样当可发布流程”的 residual 约定。另有 2 个契约/文档准确性 finding（P2/P3）
和 1 个测试分支覆盖 finding（P3），均有 owner/due。

## 评审范围与提交

| 项         | 值                                                                                                                     |
| ---------- | ---------------------------------------------------------------------------------------------------------------------- |
| baseline   | `f5010ac`（T03 扩写 write_scope 以接入保存/校验端点）                                                                  |
| 代码       | `f9ab9b1`（T03 feat + 测试清理；20 文件，含 2 个测试类、9 个 validation 类、controller/service/parser 接线）           |
| 后续提交   | `684ba32`/`b0950a5` 仅追加 EV-1027/EV-1028；`26a2fbd` 仅契约描述/README（无 schema，版本仍 1.6.0-m06）                 |
| 证据       | `EV-1027`（39 tests，commit f9ab9b1，dirty=false，exit 0）、`EV-1028`（spotless，commit 684ba32，dirty=false，exit 0） |
| 设计依据   | ADR-0024 §4.1/§4.4/§4.5/§6.2/§9.3、PRODUCT §12 AC-02/06/09/18/24、contracts 1.6.0-m06、26a2fbd profile 对齐            |
| 评审时HEAD | `26a2fbd`（仅 docs；T03 代码未变；独立复跑在 HEAD 上）                                                                 |

`git diff --name-status f5010ac..f9ab9b1` 的 20 个文件全部落在任务卡 4 个 write_scope 或 `.riccon` 证据/任务
文件内：

- `apps/api/src/main/java/com/riccon/api/workflow/validation/**`（11 个新增类，含 `persistence`/`repository`）；
- `apps/api/src/main/java/com/riccon/api/workflow/definition/**`（controller/service/parser 三个接线改动）；
- `apps/api/src/test/java/com/riccon/api/workflow/validation/**`、`.../definition/**`（2 个测试类）；
- `.riccon/evidence/TASK-0221/**`、`.riccon/tasks/TASK-0221.json`、`.riccon/state.json`（证据与任务状态）。

未发现 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`、J1/J2 生产代码、
T02 的 `workflow/domain/**` 或 `workflow/persistence/**` 改动；`git diff --check` 对 `apps/`、`platform/`、
`docs/`、`contracts/` 干净（仅自动生成的 EV-1026 日志有行尾空格，不是源码问题）。

## 任务验收对账

| 任务卡验收                                                                                                                     | 结论                                                                                                                                                                           |
| ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 实现图结构/必填/模型/工具/审批可达/条件/结果/限制；AC-02/06/09/18/24；IT-WF-101、SEC-WF-005、IT-WF-111                         | **部分不通过**：除 F-001（MCP_TOOL toolId=null）外，规则均有实现与正/负测试；IT-WF-101/T02 回归通过；IT-WF-111/SEC-WF-005 映射到本任务测试；详见“校验规则覆盖”。               |
| 保存/发布/运行三处一致；画布与表单同问题列表；参照 ADR §4.1/§4.5；受影响测试通过                                               | **部分通过**：SAVE/VALIDATE 已接同一校验器，PUBLISH/RUNTIME scope 已定义；T04 发布、T05 运行尚未实现；UI 同列表归 T10；T02/T03 受影响测试全部通过。                            |
| 高风险任务：done 前有独立 Verifier 评审；只改 write_scope；不改 contracts/PRODUCT/AGENTS；不启动 M06b；提交后取证，只移 review | **通过**：本评审即独立评审；20 个文件命中 4 个 scope；无 contracts/PRODUCT/AGENTS/M06b 改动；EV-1027/1028 在代码后取证；任务 status=review。                                   |
| 关闭 REV-TASK-0220-001 F-002：校验 targetNodeKey pattern，拒绝 "bad key" 等；补负向用例                                        | **通过**：`WorkflowValidator.java:487-501` 用 `^[A-Za-z0-9_-]{1,64}$` 校验；`WorkflowValidatorTest:187-200` 与 `WorkflowValidationApiTest:167-173` 覆盖 `"bad key"`。          |
| WorkflowValidator 接入 saveDraft（非法 400 逐项问题）与 POST /workflows/{id}/validate；T04 复用 publish profile                | **通过（F-001 残留在规则层）**：`WorkflowDefinitionService.java:275-312` 在 updateDraft/audit 前校验；controller 新增 validate；`validateDraft` 用 PUBLISH scope；T04 未实现。 |

## 校验规则覆盖

- **图结构（通过）**：恰好 1 个 START、≥1 个 END、每节点入边 ≤1、非 END 恰 1 条出边、CONDITION 恰
  2 条且 TRUE/FALSE 各一、无环（Kahn）、无孤立（START BFS）、END 无出边、START 无入边；`WorkflowValidator.java:160-444`；
  `WorkflowValidatorTest:67-158`；`WorkflowGraph`/`WorkflowGraphJson` 在解析/构造层拒绝重复 ID、>50 节点、>100 边。
- **targetNodeKey pattern（通过）**：`WorkflowValidator.java:487-501`；`WorkflowValidatorTest:187-200`；
  `WorkflowValidationApiTest:167-173`。T02 F-002 关闭。
- **必填参数（部分不通过，F-001）**：RETRIEVAL `queryTemplate/topK`、MODEL `promptTemplate/outputKind`、
  CLASSIFICATION `labels`、CONDITION `condition`、END `resultFrom`、APPROVAL `assigneeType/actionKind`
  均有检查；**MCP_TOOL `toolId=null` 被 `:611-615` 直接放行**，与 ADR §4.4 和 26a2fbd SAVE“缺失必填”
  冲突。
- **模型（通过，带 residual）**：`validateModel/:563-594` 要求 promptTemplate/outputKind，分类要求
  labels；`modelId` 非空时必须 ACTIVE、provider ACTIVE、OPENAI_COMPATIBLE、REPORT 链、部署端点允许
  列表内（`WorkflowValidationRepository.java:59-73`）；`modelId=null` 按 ADR §4.4 是可选的，依赖运行
  时 REPORT 路由（剩余风险）。
- **MCP 工具（部分不通过，F-001）**：非空 toolId 时检查未授权（`TOOL_NOT_AUTHORIZED`）、工具/server
  停用（`TOOL_DISABLED`）、要求审批时直接前驱必须是 `APPROVAL` 且 `actionKind=MCP_TOOL`、
  `targetNodeKey` 指向该工具；`WorkflowValidatorTest:278-313`；`WorkflowValidationApiTest:292-314`；
  null toolId 未检查。
- **审批（通过）**：assigneeType USER/ROLE 二选一、USER 非空且不得带 role、ROLE 不得带 users、
  actionKind 必填、MCP_TOOL target 必填且为直接后继工具、MODEL/CONTINUE target 绑定直接后继；
  `WorkflowValidator.java:671-818`；`WorkflowValidatorTest:315-353`。
- **发布级 NO_APPROVER（通过）**：仅 `PUBLISH` scope 检查；排除发布者后按 USER/ROLE 统计 ACTIVE 账号；
  `WorkflowValidator.java:769-783`、`WorkflowValidationJdbc.java:63-83,163-195`；
  `WorkflowValidatorTest:327-346`；`WorkflowValidationApiTest:270-290`。SAVE 不阻断、VALIDATE 阻断，
  与 AC-06 的 T03 范围一致。
- **条件（通过，带 residual）**：left/right valueType 一致；GT/GTE/LT/LTE 仅 NUMBER；IN 必须有
  values、不得有 value、values 类型匹配；非 IN 必须有 value、不得有 values；NODE_OUTPUT 的 nodeId
  必须存在、pattern 合法、为上游，path 仅 `hitCount/classificationLabel/decision/status` 且与来源节点
  类型匹配；`WorkflowValidator.java:838-1050`；`WorkflowValidatorTest:355-414`。未校验
  `classificationLabel` 比较值必须属于来源 MODEL 的 labels 枚举（ADR §4.5 的严格读法），留 T06b/运行时。
- **结果（通过，带 residual）**：END resultFrom 必填、必须存在、非 START、且为 END 的严格上游；
  `WorkflowValidator.java:511-533`；`WorkflowValidatorTest:416-439`。未限制 resultFrom 不能指向
  CONDITION/APPROVAL 等非文本/JSON 输出节点，留 T06b。
- **限制与 ID 唯一（通过）**：`WorkflowGraph` 构造器 `MAX_NODES=50`/`MAX_EDGES=100`/节点与边 ID 唯一；
  `WorkflowGraphJson` 解析层同样拒绝；`WorkflowValidator.java:160-175` 保留防御性 issue；测试
  `WorkflowValidatorTest:441-461`（51/101 在解析层拒绝）、`WorkflowValidationApiTest:373-390`。
- **负向节点/脚本/HTTP/并行/循环/定时（通过）**：`WorkflowGraphJson` 封闭字段/枚举拒绝，API 对
  SCRIPT/CODE/HTTP/PARALLEL/LOOP/SUBFLOW/CRON/EXEC 与 script/code/http/cron/parallel/loop/subflow
  配置均返回 400 不落库；`WorkflowValidationApiTest:200-222`；AC-24 的接口部分满足（UI 入口 T10）。

## 接线与只读语义

- **SAVE**：`WorkflowDefinitionService.saveDraft`（`:275-312`）在 `repository.updateDraft` 和
  `auditService.append` 之前调用 `validator.validate(..., WorkflowValidationRequest.save(...))`；
  失败抛 `WorkflowValidationException`（400 `RICCON-WF-400`），`@Transactional` 下无写入；测试
  `WorkflowValidationApiTest:139-198` 断言 draftVersion 不推进、`workflow.draft.saved` 为零、draftGraph
  不变。
- **VALIDATE**：controller 新增 `POST /{workflowId}/validate`（`WorkflowDefinitionController.java:163-176`，
  `@RequirePermission(WORKFLOW_EDIT)`）；`validateDraft`（`:314-330`）用 PUBLISH scope、只读事务、只调用
  `requireEditable`/validator 查询；返回 200 + `WorkflowValidationResult`；无草稿版本/审计/版本写入；
  测试 `WorkflowValidationApiTest:114-136`（draftVersion=2、审计仅 1 条保存）与 `:224-268`（owner 200、
  其他编辑者 404、普通员工 403、跨租户 404、平台管理员 404）。
- **T04/T05**：`WorkflowValidationScope.PUBLISH`/`RUNTIME` 与请求工厂已定义；T04 发布把 PUBLISH
  profile 用于发布前校验、T05 运行前用 RUNTIME；本任务未实现发布/运行服务，未声称已接线。
- **错误契约**：`WorkflowValidationException` 走既有 `RicconException` → `GlobalExceptionHandler` →
  `ProblemDetail`（`code`/`detail`/`traceId`）；未新增错误 body schema；detail 最多列 20 条，只含稳定码、
  nodeId、field 与规则说明，不含 prompt/工具参数（`WorkflowValidationException.java:28-69`）。
- **权限与私有草稿**：VALIDATE 与 SAVE 同为 `workflow.edit`；非 owner/非 TENANT_ADMIN 404，符合
  ADR §6.2 草稿私有性。

## 契约一致性（26a2fbd）

- `26a2fbd` 只改 `contracts/README.md` 与 `contracts/openapi.yaml` 的 description（14+/9-），
  `info.version` 仍为 `1.6.0-m06`，未改 paths/components/schema，未新增错误 schema；`git show --stat`
  已核对。因此 T03 的“SAVE 400 沿用 ProblemDetail、不新增 schema”成立。
- SAVE profile：实现为编辑级（结构/必填/节点配置/模型/工具/条件/结果/限制 + 审批配置），仅 NO_APPROVER
  延后；与 26a2fbd 描述一致，**但 F-001 的 toolId=null 与该段“缺失必填”不一致**。
- VALIDATE profile：实现走 PUBLISH scope（结构/必填/模型/工具/条件/结果/限制 + NO_APPROVER）；但
  26a2fbd 写“发布范围”，端点无 runScope 输入/实现（**F-002**），且“SAVE issues 与 validate 同口径”
  与 SAVE 延后 NO_APPROVER 的同一段自相矛盾（**F-002** 一并记录）。
- PUBLISH profile：T04 未实现；`WorkflowValidator` 的 PUBLISH scope 可供复用，但 T04 必须自行补
  runScope 角色/部门范围并保证失败不产生 `workflow_version`。
- “不声称保存允许非法图”：契约明确“保存成功不代表通过发布校验”，实现也确实在 SAVE 拒绝大部分非法
  图；F-001 是当前唯一不满足“缺失必填”的例外，不能把该图称为已通过发布校验。

## AC 对账

- **AC-02（P0，T03 主责）**：原文要求草稿存在环、缺开始/结束、孤立、未填写必填参数、引用停用模型或
  未授权 MCP 工具时“校验逐项指出问题节点、原因与修正入口，发布被阻止且不产生发布版本”；全部修正后
  允许保存/发布，结果在画布与列表/表单一致可见。**T03 结论：不通过（F-001）**。结构/模型/工具
  （非空）/条件/结果/限制在 SAVE 与 VALIDATE 均有逐项 issue；但 `MCP_TOOL` 未填 `toolId` 时未返回
  REQUIRED_CONFIG，VALIDATE 仍 `valid=true`。发布版本生成和 UI 一致性分别由 T04/T10 验证，本评审不
  把 API 证据当 UI/发布证据。
- **AC-06（P0，T03 部分）**：原文要求审批人全部不可用时运行保持等待、不自动放行；发布校验或运行前
  给出“需先配置非发起人审批人”。**T03 结论：通过（T03 范围）**：SAVE 允许暂存，VALIDATE/PUBLISH
  profile 在排除发布者后无可用审批人时返回 NO_APPROVER；自批 403、运行等待与 UI 提示分别归
  T07a/T09、T05/T06、T10，未在本任务验证。
- **AC-09（P0，T03 配置部分）**：原文要求条件基于检索命中数或模型分类，只执行满足条件的分支；条件
  无法求值（上游输出缺失、类型不符或表达式无效）时运行失败、不默认分支。**T03 结论：配置校验通过，
  运行未验证**：条件源/算子/值类型/IN/上游/受控字段均有检查；运行时求值与“不默认分支”归 T06b。
- **AC-18（P0/P1，T03 静态部分）**：原文要求引用未授权/停用工具时保存/发布被拒绝或运行失败；写工具
  或高风险必须审批。**T03 结论：部分通过（F-001）**：非空 toolId 的未授权/停用/审批前驱拒绝均有
  真实 DB 测试；null toolId 未阻断；工具运行时策略、重试重新审批与提示注入归 T07b/T16a。
- **AC-24（P0，T03 接口部分）**：原文要求代码/脚本/任意 HTTP/并行/循环/子流程/定时节点接口拒绝并
  返回可读原因。**T03 结论：通过（API）**：负向类型与配置字段测试均 400、不落库；UI 不提供入口与文案
  归 T10/T16a。
- **AC-01/03**：T02/T04 主责；本任务只复用 T02 的 saveDraft 与详情读取，未改变草稿并发/版本语义。

## 范围、回归与架构

- 20 个文件全部命中 4 个 write_scope；未改 T02 的 `workflow/domain/**`、`workflow/persistence/**`，
  未改 contracts/PRODUCT/AGENTS/V15/J2，未启动 M06b；任务 status=review、owner=builder-b1。
- T02 回归：独立复跑 `WorkflowDefinitionApiTest` 7/7、`WorkflowGraphJsonTest` 6/6、
  `WorkflowGraphDomainTest` 4/4，全部 0 失败/0 错误/0 跳过。
- 分层：`WorkflowValidator`（validation 根包）只依赖 `WorkflowValidationSupport` 端口，
  `WorkflowValidationRepository`（repository）依赖 `WorkflowValidationJdbc`（persistence）；
  `WorkflowDefinitionController`→`Service`→`Validator`/`Repository` 方向符合既有 MVC 规则。
  `ModuleBoundaryTest` 未在任务级运行（属模块 gate）；静态核对未发现 T03 类违反 controller/service/
  repository/persistence 或 JdbcClient 只在 persistence 的规则。
- `spotless:check` 独立复跑 exit 0（全 reactor），EV-1028 同命令同结果。

## 证据复核与独立重跑

- **EV-1027**：命令
  `./mvnw -q -pl apps/api -am test -Dtest='WorkflowDefinitionApiTest,WorkflowGraphDomainTest,WorkflowGraphJsonTest,WorkflowValidatorTest,WorkflowValidationApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`；
  commit `f9ab9b1c5cd0dd59b8f151a812eeb1e30e927430`；manual=false、dirty=false、exit 0；output
  29,812 bytes，sha256 `92c882ed992ab0d2ae3ace0569533feb8eae88e4bcf7d1c9982da305798f09a2`，与证据 JSON
  和实际文件一致。EV-1026 是同命令在 e6a7b3f 的旧快照（测试清理前），本评审以 EV-1027 为权威。
- **EV-1028**：命令 `./mvnw -q spotless:check`；commit `684ba32`；manual=false、dirty=false、exit 0；
  output 249 bytes，sha256 `24833bcb2669fdc4ad9d917f8584b09fa0d72003cc3bf3debb105ad880b1842a`，一致。
- **独立重跑（HEAD 26a2fbd；T03 代码=f9ab9b1）**：同一命令 exit 0；surefire 报告
  `WorkflowDefinitionApiTest` 7、`WorkflowValidationApiTest` 9、`WorkflowValidatorTest` 13、
  `WorkflowGraphJsonTest` 6、`WorkflowGraphDomainTest` 4，共 **39 个用例，0 failures / 0 errors /
  0 skipped / 0 flakes**；无 `@Disabled`/`assumeTrue`。`./mvnw -q spotless:check` 独立 exit 0。
- 未运行：coverage、全量 `build`/`test`/`contract` 套件、E2E、axe/a11y、k6/perf、Trivy/security、
  `gate check --module M06`（按任务要求）。

## 未验证

- **T04 发布路径**：发布不可变版本、`graph_sha256`、runScope 角色/部门范围、失败不产生版本、
  old-version 运行语义——本任务只提供 PUBLISH profile，未实现发布服务。
- **T05/T06b 运行**：RUNTIME scope 未接线；条件求值、节点执行、工具审批、`WF_NO_APPROVER` 等待、
  `WF_CONDITION_INVALID` 等运行时行为未验证。
- **T07/T09 审批**：自批 403、审批详情 ACL、拒绝理由、并发决定——未验证。
- **T10/T16a UI/E2E**：画布与列表/表单同问题列表、加载/空/错误/无权限态、axe、键盘、375px——
  未验证；不把 API 证据当 UI 证据。
- **模块 gate**：全量 build/lint/typecheck/test/coverage/E2E/security/perf、ArchUnit、
  `WorkflowContractM06Test`、审计 fail-closed、匿名 401、并发/越权套件——未运行。
- **契约测试**：`WorkflowContractM06Test` 未在本任务运行；26a2fbd 只改 description，未新增 schema。

## Findings

- **F-001（P1，open）** MCP_TOOL `toolId=null` 未被 SAVE/VALIDATE/PUBLISH 阻断；内置模板图可原样通过
  发布口径校验。owner=builder-b1，due=T03 done 前（或 T04 开工前若改为发布级守卫）。修复建议：在
  `validateTool` 的 null 分支前返回 `REQUIRED_CONFIG`（至少覆盖 SAVE/PUBLISH），把
  `WorkflowValidationApiTest` 的“合法图”改为含已授权 tools 的图，新增 `toolId=null` 保存/校验负向用例；
  若团队决定 null 表示“不发起调用”的合法空操作，必须修订 ADR §4.4/26a2fbd 文案并更新 T02 residual，
  不能既保留“缺失必填”契约又放行。
- **F-002（P2，accepted）** 26a2fbd 的 VALIDATE=发布范围与 SAVE 同口径文案超出端点/实现能力；
  owner=Lead/Architect（T04 落实），due=M06 gate 前；accepted_reason 见 front matter。T04 必须独立校验
  runScope，T10 前对齐文案。
- **F-003（P3，accepted）** contracts/README 声称 `WorkflowContractM06Test` 断言 SAVE/VALIDATE/PUBLISH
  profile，实际测试没有这些断言；owner=Lead/T16a，due=M06 gate 前；文档准确性 residual。
- **F-004（P3，accepted）** 校验器若干分支缺直接负向测试（END 出边、START 入边、MODEL actionKind、
  WRITE 风险、ROLE 无审批人、provider 状态）；owner=builder-b1/T16a，due=M06 gate 前；主路径已有
  单元/真实 DB 测试，T16a 覆盖率补齐。

无 open P0；P1 仅 F-001 且必须 fixed；P2/P3 均 accepted 且有 owner/due/accepted_reason。

## 剩余风险

1. **占位符白名单未在本任务校验**：ADR §4.5 要求 `promptTemplate/queryTemplate/argsTemplate` 的
   `{{input.*}}`/`{{node.*}}` 白名单与缺失字段在保存/发布/运行失败；T03 校验器未解析占位符。任务卡
   未把占位符列入 T03 验收，T06b（引擎/节点执行）和 T14 模板任务必须补；若两者都不补，AC-09/18 的
   “表达式无效/提示注入”边界会留缺口。
2. **modelId=null 依赖 REPORT 链**：校验器只检查显式 modelId；若部署/租户没有任何可用 REPORT 模型，
   保存/发布仍通过，运行时才失败。T04/T05 或 T14 的发布校验应评估是否要求 REPORT 链非空。
3. **END resultFrom 类型未限制**：允许指向 CONDITION/APPROVAL 等非文本/JSON 输出节点；ADR §4.1
   “结果=该节点输出（文本/JSON）”建议 T06b 明确允许的输出节点类型。
4. **classificationLabel 比较值未对照来源 labels**：条件可配置 `EQ` 到一个来源模型 labels 之外的值，
   永远只能走 FALSE；ADR §4.5 的“必须来自 labels 枚举”建议 T06b 在运行前/执行时再确认。
5. **VALIDATE 的“发布者”近似**：validate 用当前操作者当发布者排除；若实际发布者是另一账号，NO_APPROVER
   可能出现保守误报。T04 发布时必须用真实发布者重新校验。
6. **N+1 审批人查询**：每个 APPROVAL 节点一次 `countActiveApprovers`；节点上限 50，T16a 的
   Studio API p95 ≤ 500ms 必须覆盖（AC-21）。
7. **测试数据卫生**：`WorkflowValidationApiTest` 清理了模型目录，但创建的 MCP server/tool/grant 未清理；
   同一 JVM 其他 MCP 测试若枚举目录可能受影响，T16a 视需要补 cleanup。
8. **`saveDraft` 先校验后乐观锁**：陈旧版本 + 非法图返回 400 而非 409；不是契约违规，但客户端冲突处理
   需知道该顺序。
9. **`TEMPLATE_INVALID` 未使用**：模板非法问题码留给 T14；T14 必须实现模板校验/拒绝。

## 是否可 done

**不可 done（fail）**。F-001（P1）修复并在最终提交上复跑 T03 受影响测试后，本评审可重开；
F-002/F-003/F-004 已 accepted，不阻塞 T03，但 M06 gate 前必须由 owner 收敛。T04/T10/T16a 仍需分别
完成发布、UI 与全量 E2E/a11y/perf/security/coverage；本评审不替代 M06 模块 review、Product 预验收或
用户验收，也未运行 gate check。
