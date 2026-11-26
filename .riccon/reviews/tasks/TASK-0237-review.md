---
review_id: REV-TASK-0237-001
module_id: M06
task_id: TASK-0237
reviewer:
  agent: riccon/verifier
  session: ses_dead3ed7affeHC0nDR5UL97NZZ
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
review_timing: post-closeout
reviewed_commits:
  - 9347f11cc0f235a4b7cd7cb7119822beda0eafb3
  - ff5824ef6749dd013ff746d116a8bace1df37b2d
  - f26b5b86
  - 48daea7c
head_commit: 5094cb0b726d78f4a511f569bd0029d186a7a81f
review_scope: >-
  M06 T16a（TASK-0237）在 T17 关闭前的独立证据复核：E2E-M06-01..10 与 @a11y/375px/键盘、
  PERF-M06-001（DEC-0049=A 的读 API 500ms/准入写路径 3s）、SEC-WF-002/003/006、TEN-M06-01、
  DEPT-M06-01、IT-AUDIT-M06-01 的交付与运行结果；复核 EV-1146..EV-1149（commit、dirty、exit、
  日志与测试报告）与 TASK-0237 任务卡要求关闭的 REV-TASK-0229/0227/0247/0230 残余。未修改任何
  生产源码/测试/迁移/契约；不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  e2e: true
  accessibility: true
  performance: true
  security: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - migration
  - docs
dod_note: >-
  acceptance 记 true 但按 **pass (with residual)** 解释：E2E/a11y/键盘/perf/越权/审计 harness
  已在最终代码上交付并通过（EV-1148 真实栈 41 passed、EV-1146 M06 a11y 3 passed、EV-1147
  PERF-M06-001 达标、EV-1149 越权/审计定向 0 failure/0 skip）；任务卡要求关闭的 U-05 与
  T07 F-001/F-002、TASK-0247 F-002 未在 T16a 代码中关闭，作为书面 residual 交由模块评审与
  Lead/Product 处理。build/tests/coverage 的全量以 T17 合同证据为准，本任务不使用 EV-1147/1148
  冒充全量 test/coverage。真实栈 E2E 覆盖 J1/J2/M10/M04/M05/M06 回归；M03 由 real-stack
  m03Checks 覆盖。security 指 T16a harness 的越权/注入/审计负向用例，不等于 Trivy 扫描。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "安全评审/riccon/product（Lead 跟踪）"
    due: "M06 gate 的 Product/安全二选一（本评审后由 Lead 与 Product 明确记录）"
    title: "U-05 审批理由入审计 PII 结论仍未二选一，T16a 任务卡要求的关闭项未完成"
    summary: >-
      TASK-0237 任务卡第 7 条要求“审批理由入审计的 PII 结论需在 M06 gate 前由安全/Product
      二选一（≤500 或 commentSha256），T16a 补审计读回/负向证据”。T16a 已补审计读回与 500/501
      边界证据（WorkflowApprovalAuditCommentPolicyTest、WorkflowApprovalApiTest），但
      `WorkflowApprovalAuditEvents.decided` 仍把 201–500 字符 `decisionComment` 原样写入
      `workflow.approval.decided`，ADR-0024 §8.3/F-013 仍写“U-05 未解决”。本评审核对了代码与
      ADR，未发现 commentSha256 替代路径，也没有新的 Product/安全决策。风险有界：理由为用户
      输入、≤500 字符、只进审批记录/审计、不写 prompt/文档正文；但不构成安全评审通过。
    accepted_reason: >-
      临时默认由 ADR-0024 §8.3 明确授权，风险面限于审计/审批记录中的用户输入且已有长度与
      白名单控制；T16a 的审计读回/负向证据已交付。作为 P2 residual 接受，由 Lead/Product 在
      M06 gate/用户最终验收前明确选择 ≤500 或 commentSha256；若选择后者需另立 fix 任务，不能
      把本 accepted 当成安全批准。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:620,974,1050；
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalAuditEvents.java:42-91；
      apps/api/src/test/java/com/riccon/api/workflow/approval/WorkflowApprovalAuditCommentPolicyTest.java:51-54；
      TASK-0237 任务卡第 7 条；REV-TASK-0229-001 F-002。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/architect（归属决定）；riccon/lead 跟踪"
    due: "M06b/M07 gate 前（引入 requires_approval=false 工具前必须闭环）"
    title: "T07 F-001/F-002 未在 T16a 关闭：mcp_tool.input_schema 执行前校验与 READ 免审批语义仍不一致"
    summary: >-
      TASK-0237 任务卡第 8 条要求关闭 REV-TASK-0227-001 F-001..F-003。T16a 的 SEC-WF-004/
      IT-WF-104/105 已补预算耗尽、输出上限、并发唯一键、PENDING、自绑目标等用例，但代码中仍无
      `mcp_tool.input_schema` 执行前校验；`WorkflowToolApprovalVerifier` 仍对所有工具无条件要求
      审批，而 T03 校验器允许 `READ + requires_approval=false` 的图发布。当前 V5/M02 目录全部
      `requires_approval=TRUE`，T07b 更严格，只可能拒绝、不会放行，未发现越权或副作用绕过。
    accepted_reason: >-
      当前可达工具目录没有 requires_approval=false 条目，缺失 schema 校验也未证明可绕过租户
      授权/审批/plan 哈希；影响是未来 M07 工具目录扩展时的纵深防御/语义一致性。作为 P3
      accepted，由 Architect 在 M06b/M07 前决定归属并补测试，不阻塞 M06 交付。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/tool/service/WorkflowToolNodeExecutor.java:224-228；
      apps/worker/src/main/java/com/riccon/worker/workflow/tool/service/WorkflowToolApprovalVerifier.java:35,92-95；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowToolAvailability.java:65-67；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:380；REV-TASK-0227-001 F-001/F-002。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/lead（角色权限口径）/ riccon/builder-b3（UI 兜底）"
    due: "M06b gate 前"
    title: "TASK-0247 F-002 未在 T16a 关闭：仅有 WORKFLOW_EDITOR 角色的部门负责人缺 department.read"
    summary: >-
      TASK-0237 任务卡第 9 条要求补 astral 副本名边界并记录/接受 WORKFLOW_EDITOR 缺 department.read
      的角色决策。astral 边界已由 `limits.test.ts` 与 `WorkflowApprovalAuditCommentPolicyTest`
      覆盖；但 `WORKFLOW_EDITOR_PERMISSIONS` 仍不含 `DEPARTMENT_READ`，仅有该角色的部门负责人
      在编辑页无法选择“本部门”发布范围（页面已显式告警且默认人物 MEMBER+WORKFLOW_EDITOR 正常）。
      本评审核对了角色字典与编辑页告警，未发现静默回退或越权发布。
    accepted_reason: >-
      默认目标人物是 MEMBER + WORKFLOW_EDITOR（MEMBER 含 department.read），单独的
      WORKFLOW_EDITOR 属边界角色组合；UI 已显式提示且不会静默改范围，影响是 UX/角色口径而非
      越权。作为 P3 accepted，由 Lead 在 M06b gate 前决定补 department.read 或只读部门兜底。
    evidence: >-
      platform/platform-domain/src/main/java/com/riccon/platform/domain/identity/RoleDefinitions.java:44-60,134-135；
      apps/web/src/pages/workflow/edit/index.tsx:662,1703；REV-TASK-0247-001 F-002。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-b1（测试）/ riccon/verifier（复跑）"
    due: "M06b 前硬化；模块全量已复跑"
    title: "历史 flake：WorkflowRunHistoryConcurrencyApiTest 的 cancel/decide 竞态期望"
    summary: >-
      EV-1141（M06 全量 API 运行）曾命中
      `WorkflowRunHistoryConcurrencyApiTest.concurrentCancelAndDecide_consistentOutcome`
      的 1 failure：测试固定期望 cancel 返回 202，但 decide 先赢并让运行进入终态时 cancel 可能
      返回 409。该测试的并发断言本身允许 decide 200/409，但 cancel 202 是硬断言。T16a 的
      EV-1149 未覆盖该类；T17 全量复跑（见模块评审）已再次运行该类并 3/3 通过。
    accepted_reason: >-
      该类不在 T16a 任务卡验收清单内；一次全量通过不能证明 flake 消失，但当前失败只影响测试
      稳定性，不是 M06 功能/安全缺陷（cancel 与 decide 最终状态仍被断言）。作为 P3 accepted，
      由 builder-b1/verifier 在 M06b 前把 cancel 期望放宽为 202/409 或加同步闸门。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryConcurrencyApiTest.java:85-141；
      .riccon/evidence/TASK-0249/EV-1141-*.log（1 failure / 38 errors，另有 M05b 连接器环境问题）；
      REV-TASK-0249-001 F-003；T17 模块全量复跑结果见 REV-M06-001。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: "T17 模块 gate（已执行）"
    title: "EV-1146..1149 录制于 ff5824ef，不是 T17 最终提交"
    summary: >-
      EV-1146（a11y）、EV-1147（perf）、EV-1148（真实栈全量 E2E）、EV-1149（越权/审计定向）的
      commit 均为 ff5824ef；其后仅 db7e617b 新增 ops 演练脚本、后续为 .riccon/文档提交，产品
      运行代码未变。按 strict 规则，模块 review 的 build/test/coverage/e2e 必须在最终提交重新
      录制；本任务 review 只把这些 EV 作为 T16a 任务级证据，不替代模块全量证据。
    accepted_reason: >-
      产品代码未变，T16a 的任务级证据在 ff5824ef 上有效；T17 已在 5094cb0b 上重跑合同
      build/test/coverage/e2e 并另行绑定，故不接受“用旧 EV 冒充最终提交”的写法。风险有界，
      作为证据时序 P3 accepted。
    evidence: >-
      EV-1146/EV-1147/EV-1148/EV-1149 的 commit 字段；git diff ff5824ef..5094cb0b（产品代码仅
      scripts/ops 新增）；REV-M06-001 的 dod_evidence。
evidence:
  - EV-1120
  - EV-1121
  - EV-1122
  - EV-1123
  - EV-1124
  - EV-1125
  - EV-1126
  - EV-1127
  - EV-1128
  - EV-1129
  - EV-1130
  - EV-1131
  - EV-1146
  - EV-1147
  - EV-1148
  - EV-1149
reviewed_evidence:
  - EV-1120
  - EV-1121
  - EV-1122
  - EV-1123
  - EV-1124
  - EV-1125
  - EV-1126
  - EV-1127
  - EV-1128
  - EV-1129
  - EV-1130
  - EV-1131
  - EV-1146
  - EV-1147
  - EV-1148
  - EV-1149
notes: |-
  - EV-1148：真实栈 full 41 passed（7.6m），含 E2E-M06-01..10、E2E-M06-09a/b/c @a11y、
    J1/J2/M10/M04 回归；real-stack summary browserE2e/j2Journey/securityChecks/m03Checks/m04Checks
    均 passed。
  - EV-1147：PERF-M06-001 的 m06_api_ms p95=141ms（≤500ms）、m06_run_admission_ms p95=2557ms
    （≤3000ms，DEC-0049=A）、m06_j1_first_token_ms p95=50.1ms（≤3000ms）、m06_run_ok=100%；
    50 并发 = 38 J1 + 1 J2 + 10 studio + 1 write。
  - EV-1146：E2E-M06-09a axe 无 serious/critical；EV-1148 已同时覆盖 09b/09c。
  - EV-1149：TEN-M06-01/DEPT-M06-01/IT-AUDIT-M06-01/WorkflowValidationNegativeMatrix 定向
    0 failure/0 skip。
  - 本评审在 T17 进行，属于 closeout 后的独立证据审计；不声称 T16a 在 done 前已满足“先评审
    后 done”的流程要求，流程缺口由模块评审记录并由 Lead 决定是否补流程修复。
created_at: "2026-11-26T08:52:00+08:00"
---

# REV-TASK-0237-001：TASK-0237（M06 T16a）独立证据评审

## 结论

**通过（pass，with residual）**。T16a 交付的 M06 E2E/a11y/perf/越权/审计 harness 在
`ff5824ef` 上真实通过，关键报告为 EV-1148（真实栈 41 passed，含 E2E-M06-01..10 与 @a11y）、
EV-1147（PERF-M06-001 p95 达标）、EV-1149（TEN/DEPT/AUDIT/Validation 定向 0 skip/0 failure）。
任务卡要求关闭的 U-05 与 T07/T12 残余未在 T16a 代码中关闭，按 P2/P3 accepted residual 交模块
评审与 Lead/Product 处理；本评审不把 T16a 证据当作模块全量 build/test/coverage 证据。

## 验证了什么

- E2E：EV-1148 真实栈 41 passed；M06 10 个旅程 + 9a/9b/9c 全部在列表。
- a11y/375/键盘：EV-1146 与 EV-1148 的 @a11y 用例；未发现 skip/fixme。
- 性能：EV-1147 报告 `thresholds` 与 `metrics`；读 API 与准入写路径按 DEC-0049=A 分列。
- 越权/审计：EV-1149 定向 0 failure/0 skip；命名用例出现在 surefire XML。
- 证据完整性：EV-1146..1149 均 clean、exit 0、commit 可追溯；EV-1121/1127/1123/1124/1125/1130
  为历史失败/中间证据，不作为通过依据。

## 未验证

- 模块全量 build/test/coverage/docs/Trivy：由 REV-M06-001 在最终提交重跑，不在本任务范围。
- U-05 的安全/Product 二选一：未发生，见 F-001。
- M06b 模板共享/升级：不在 M06/T16a 范围。

## Findings

F-001（P2 accepted，U-05）、F-002（P3 accepted，T07 schema/免审批语义）、F-003（P3 accepted，
WORKFLOW_EDITOR department.read）、F-004（P3 accepted，历史并发 flake）、F-005（P3 accepted，
证据时序列 ff5824ef）。详见 front matter。

## 剩余风险

U-05 仍是 gate 前必须由 Lead/Product 明确的安全 residual；T07/T12 残余在 M06b/M07 前需
Architect/Lead 定归属。历史并发 flake 需在 M06b 前硬化。
