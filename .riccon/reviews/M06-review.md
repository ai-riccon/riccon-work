---
review_id: REV-M06-002
module_id: M06
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dea0bfe62ffebOYyfAdr115DjN
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
review_status: final
previous_review: REV-M06-001
supersedes: REV-M06-001
head_commit: 9b86e2b6bf9523f53ff95f32406b09ae4f07f3b7
baseline_commit: 9347f11cc0f235a4b7cd7cb7119822beda0eafb3
reviewed_commits:
  - 9347f11cc0f235a4b7cd7cb7119822beda0eafb3
  - ff5824ef6749dd013ff746d116a8bace1df37b2d
  - db7e617b99a3921cb94290842f32e088596d392c
  - 99820a04d73a115531b55ecf7e31198813606670
  - 27c36e6469e24325f134f62e1502f1c0ccf71d46
  - 2ce72fcec0775a03f1ecd30896f3df366ca6fcb3
  - f1bee5a6cbe58ca3fadd2158446b4d4978f8aedc
  - 9fa19324
  - 5094cb0b726d78f4a511f569bd0029d186a7a81f
  - e26fee8fe97abca9dd78f35e37c4f91cd20cc759
  - a9608afebd9318b2d0bbd2cad04c4f1c13849dab
  - 9c59e79a5febf1ef82356c46079b7d110a254039
  - b196be877c4c0345e96e6d561c7618ec77332142
  - 8e73c072edefbd61baf4d6df1cb0ba35975c0da1
  - 5d706f0b12033dd63ff7f27d74c7fc749b71ec20
  - 335e173d
  - 9b86e2b6bf9523f53ff95f32406b09ae4f07f3b7
verdict: pass
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
dod_evidence:
  acceptance:
    - EV-1166
    - EV-1167
    - EV-1163
    - EV-1164
    - EV-1146
    - EV-1147
    - EV-1148
    - EV-1149
    - EV-1150
    - EV-1151
  build:
    - EV-1165
  tests:
    - EV-1166
  coverage:
    - EV-1166
  e2e:
    - EV-1167
  docs:
    - EV-1169
  evidence:
    - EV-1165
    - EV-1166
    - EV-1167
    - EV-1168
    - EV-1169
    - EV-1170
    - EV-1171
    - EV-1163
    - EV-1164
    - EV-1146
    - EV-1147
    - EV-1148
    - EV-1149
    - EV-1150
    - EV-1151
findings:
  - id: F-001
    severity: P2
    status: fixed
    title: "AC-17 的“租户只读限制视图 / 429 具体限制值”已由 TASK-0251 修复并在真实浏览器复核"
    summary: >-
      REV-M06-001 F-001 的用户可见缺口已关闭：`WorkflowStudioNav` 在 Studio 子导航提供
      “运行限制”入口，`WorkflowRuntimeLimitsDrawer` 只读展示 featureEnabled/maxNodes/maxSteps/
      各类节点超时/运行超时/租户与单流程并发/结果大小/保留天数与策略/生成时间，并含加载、403、
      错误、编排关闭态与中文下一步；429 `RICCON-WF-429` 在运行准入、运行历史、编辑器保存/发布/
      另存与重试/取消路径展示 `limit/current` 与等待/调整流程/联系管理员建议，且不误报
      `RICCON-QUOTA-429`。TASK-0251 的 REV-TASK-0251-001 pass、EV-1164 clean（web 1049 tests、
      coverage 87.70/80.84/85.46/87.61、typecheck/lint/build exit 0）；T17b 另以真实 Chromium
      打开 Drawer 复核（桌面与 375px axe WCAG 2.1 AA 无 serious/critical、无横向滚动、键盘
      Enter/Tab/Escape 与焦点回归），并由 EV-1167 真实栈 E2E-M06-09a/b/c、E2E-M06-10 回归。
    evidence: >-
      apps/web/src/pages/workflow/templates/studio-nav.tsx:79-127；apps/web/src/components/workflow/runtime-limits.tsx；
      apps/web/src/components/workflow/runtime-limits-format.ts:124-167；apps/web/src/pages/workflow/history/format.ts:564-628；
      REV-TASK-0251-001；EV-1164；EV-1171；EV-1167（E2E-M06-09a/b/c、E2E-M06-10）。
  - id: F-002
    severity: P2
    status: fixed
    title: "U-05 审批理由入审计 PII 已按 DEC-0050=B 解决：审计只写 commentSha256，原文保留在审批记录"
    summary: >-
      REV-M06-001 F-002 已按 DEC-0050=B 关闭。API `WorkflowApprovalAuditEvents.decided` 与 Worker
      `WorkflowApprovalAuditor` 的 `workflow.approval.decided` details 只写 `commentSha256`
      （`strip()` + UTF-8 SHA-256 + 64 位小写 hex），不写 `decisionComment` 明文；理由原文仍写
      `workflow_approval.decision_comment`（≤500）并在审批详情/运行详情按 ACL 可见；201–500 接受、
      501 拒绝；旧历史 `decisionComment` 行读取兼容且新写入被 `validateWrite` 拒绝。TASK-0252 的
      REV-TASK-0252-001 pass、EV-1163 clean（API 44 + Worker 52 定向 + spotless）；ADR-0024 §8.3/
      §16、THREAT-MODEL、ARCHITECTURE 与 PRODUCT §12 已同步；EV-1166 全量审批/审计用例 0 skip。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalAuditEvents.java:63-69；
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalCommentHash.java；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalAuditor.java；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:319-345,395-396,580-591；
      EV-1163；REV-TASK-0252-001；EV-1166（WorkflowAuditM06Test/WorkflowApproval*）。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/verifier（CI/测试栈）"
    due: "CI 或 M06b 前"
    title: "本机默认 Testcontainers 仍有 RustFS 503/启动等待 flake；T17b 用 external 热栈 + t17b namespace 完成合同全量"
    summary: >-
      EV-1166 按 docs/CODING-STANDARDS 的外部热栈模式（`RICCON_TEST_SERVICES=external`、
      `RICCON_TEST_SERVICES_NAMESPACE=t17b`、`TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`）通过；
      迁移/V15 相关测试仍使用一次性容器。REV-M06-001 F-003 记录的本机 RustFS createBucket 503、
      容器启动等待与 CI 主机差异没有被证明消失，不能据此声称“默认 Testcontainers 全量已稳定”。
    accepted_reason: >-
      该问题只影响本机 Testcontainers 主机解析/资源与 RustFS 就绪，不影响 EV-1166 的产品断言
      （API 1049/Worker 924/domain 99/common 87/ArchUnit 12，全 0 failure/0 error/0 skip）；
      证据命令已在 note 记录 external 模式、namespace、原因与影响范围。作为 P2 accepted，由
      Lead/Verifier 在 CI 或 M06b 前把 host override/RustFS 就绪重试收敛进测试 harness 或 CI 环境；
      不把环境变量当作永久静默参数。
    evidence: >-
      EV-1166；docs/CODING-STANDARDS.md 热栈模式；EV-1153/EV-1154（历史失败）；REV-M06-001 F-003。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/architect（归属）；riccon/lead 跟踪"
    due: "M06b/M07 gate 前"
    title: "T07 残余：mcp_tool.input_schema 执行前校验缺失；READ+requires_approval=false 的保存/运行语义不一致"
    summary: >-
      TASK-0237 已补预算/输出上限/并发唯一键/PENDING/自绑目标等用例，但
      `McpCallExecutor`/`WorkflowToolNodeExecutor` 仍未按 `mcp_tool.input_schema` 校验参数，
      `WorkflowToolApprovalVerifier` 仍对所有工具要求审批，而 T03 校验器允许
      READ+requires_approval=false 的图发布。当前 V5/M02 目录全部 requires_approval=TRUE，
      运行侧更严格，只可能拒绝、不会放行，未发现越权/副作用绕过。
    accepted_reason: >-
      可达工具目录没有免审批条目，缺失 schema 校验未证明可绕过租户授权/审批/plan 哈希；影响是
      未来 M07 工具目录扩展的纵深防御与语义一致性。作为 P3 accepted，由 Architect 在 M06b/M07
      前决定归属并补执行前 schema 校验/统一口径。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:380；
      apps/worker/src/main/java/com/riccon/worker/workflow/tool/service/WorkflowToolNodeExecutor.java；
      apps/worker/src/main/java/com/riccon/worker/workflow/tool/service/WorkflowToolApprovalVerifier.java；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowToolAvailability.java；
      REV-TASK-0227-001 F-001/F-002；REV-TASK-0237-001 F-002。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/lead（角色口径）；riccon/builder-b3（UI 兜底）"
    due: "M06b gate 前"
    title: "仅有 WORKFLOW_EDITOR 角色的部门负责人缺 department.read，选择“本部门”发布范围仅靠 UI 告警"
    summary: >-
      `WORKFLOW_EDITOR_PERMISSIONS` 不含 `DEPARTMENT_READ`，仅有 WORKFLOW_EDITOR 的部门负责人
      在编辑页无法读取部门目录，页面显式告警且不会静默回退 ONLY_SELF；默认人物
      MEMBER+WORKFLOW_EDITOR 正常。astral 副本名字符边界已由 `limits.test.ts`/
      `WorkflowApprovalAuditCommentPolicyTest` 覆盖。
    accepted_reason: >-
      默认授权组合包含 MEMBER（含 department.read），单独 WORKFLOW_EDITOR 是边界角色；影响是
      UX/角色口径而非越权发布或数据泄露。作为 P3 accepted，由 Lead 在 M06b 前决定补
      department.read 或只读部门兜底。
    evidence: >-
      platform/platform-domain/src/main/java/com/riccon/platform/domain/identity/RoleDefinitions.java:44-60,134-135；
      apps/web/src/pages/workflow/edit/index.tsx；REV-TASK-0247-001 F-002；REV-TASK-0237-001 F-003；
      apps/web/src/pages/workflow/edit/limits.test.ts。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder-b1（测试）；riccon/verifier（复跑）"
    due: "M06b 前硬化"
    title: "WorkflowRunHistoryConcurrencyApiTest cancel/decide 竞态断言 flake：cancel 硬期望 202，decide 先赢时实际 409"
    summary: >-
      历史 EV-1153 曾在 1040 tests/0 errors 下仅 1 failure：cancel/decide 并发测试期望 cancel 202，
      decide 先赢使运行进入终态后实际 409。EV-1166 本次全量未复现；测试本身已允许 decide 200/409，
      但 cancel 202 是硬断言，属于测试时序缺陷，不是 M06 功能/安全失败。
    accepted_reason: >-
      产品语义允许“运行已终态时 cancel 409”；单类复跑与全量复跑可恢复绿色。作为 P3 accepted，
      由 builder-b1/verifier 在 M06b 前把 cancel 期望放宽为 202/409 或加同步闸门，禁止用
      retry 参数掩盖；本次全量不把“未复现”当作已修复。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryConcurrencyApiTest.java:85-141；
      EV-1153（历史失败）；EV-1166（本次全量 0 failure）；REV-M06-001 F-006。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/ops（演练）；riccon/verifier（复核）"
    due: "M09/release 回滚演练前"
    title: "T16b 回滚演练证据缺口：post-rollback triggers 测量为 0、旧 Worker digest 由 operator 提供、关闭态 J1/J2 冒烟未执行、容量 p95 仅 13 样本"
    summary: >-
      EV-1150 的 27 条断言覆盖无窗口回滚主链，但 post-rollback-assert.schema.triggers=0（应用账号
      无 TRIGGER 权限导致 information_schema 不显示；V15MigrationTest/ApiApplicationTests 已断言
      4 triggers）、rollbackTarget.digestSource=operator 且旧 Worker 关闭 schema 校验、
      `RICCON_WORKFLOW_ENABLED=false` 下未调用 J1/J2、容量 p95 基于 13 个假模型样本。RUNBOOK
      M06-12 要求触发器等人工核对与 J1/J2 冒烟，演练脚本未全部自证。
    accepted_reason: >-
      产品侧 V15 触发器由迁移/启动测试覆盖，关闭态 503 仅作用于 workflow 端点，J1/J2 路径独立；
      演练已证明旧 Worker 不领取、运行计数 0、历史只读、schema 15。作为 P3 accepted，由 Ops 在
      M09/release 生产演练前补 TRIGGER 读取、J1/J2 冒烟与签名镜像校验。
    evidence: >-
      .riccon/evidence/TASK-0238/workflow-rollback-drill.json；scripts/ops/workflow-rollback-drill.mjs；
      docs/RUNBOOK.md M06-8/M06-12；apps/api/src/test/java/com/riccon/api/migration/V15MigrationTest.java；
      REV-TASK-0238-001 F-001..F-004。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-b3（Web）；riccon/lead（验收口径）"
    due: "M06b gate 前或用户最终验收前"
    title: "AC-14 运行历史/详情未显示“实际生效的保留值（180 天/租户覆盖）”，只显示每条运行的保留到期"
    summary: >-
      M06-AC-14 要求运行历史/详情显示实际生效的保留值与每条运行到期时间。运行详情已显示
      “保留到期”时间（run-detail.tsx，E2E-M06-08 断言），且 TASK-0251 的限制 Drawer 已显示
      “运行记录保留 180 天（部署默认）”；但运行历史/详情页本身仍未显示实际保留天数或租户策略
      来源，AC-14 的该子句仍需在运行历史/详情补齐。
    accepted_reason: >-
      每条运行的到期时间已可见，足以判断可见窗口；限制 Drawer 也已展示部署默认/租户策略，缺的是
      运行历史/详情的入口与文案完整性，属于合规文案 residual，不影响到期过滤/删除边界。作为 P3
      accepted，与 F-001 一起在 M06b/用户最终验收前补运行历史/详情展示。
    evidence: >-
      docs/PRODUCT.md:831；apps/web/src/pages/workflow/run/run-detail.tsx；
      e2e/real/tests/m06-studio.spec.ts:519-522；apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryRetentionApiTest.java；
      EV-1167（E2E-M06-08）；REV-M06-001 F-008。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/architect（ADR/ARCH 状态）"
    due: "M06b/closeout 前"
    title: "ADR-0024 仍 proposed；ARCHITECTURE §M06 仍写“M06 设计版本尚未实现”，与已交付实现不一致"
    summary: >-
      ADR-0024 头部与 `docs/ARCHITECTURE.md:556` 仍保留“proposed / M06 设计版本尚未实现”的设计态
      文字；本次已同步 U-05=B、R-38、TM-76/80，但状态字段未转 accepted。实现、任务评审与模块 gate
      已完成。文档状态转换属 Architect/Lead，不在 Verifier 写范围；技术内容与实现一致，未发现相反
      行为断言。
    accepted_reason: >-
      设计文档状态字段不改变 M06 行为与证据；按项目流程由 Architect 在模块收口/closeout 时转
      accepted 并删除“尚未实现”。作为 P3 accepted，避免 Verifier 越权修改 docs/ARCHITECTURE/ADR。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:1-15；docs/ARCHITECTURE.md:7,45,52,556；REV-M06-001 F-009。
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/architect（THREAT-MODEL 命令）；riccon/verifier（核对方式）"
    due: "M06b 或下一次 THREAT-MODEL 修订"
    title: 'THREAT-MODEL §8 的 `grep -q "TEN-M06-01" apps/api/target/surefire-reports/*.xml` 在 Surefire/JUnit 下失效：XML testcase 名是方法名，不含 @DisplayName ID'
    summary: >-
      本评审在 EV-1166 的 surefire XML 上执行 THREAT-MODEL §8 的命名用例 grep 命令，
      `TEN-M06-01`/`DEPT-M06-01`/`IT-WF-101..112`/`SEC-WF-*` 均不出现；XML 的 testcase name 是
      方法名（例如 `tenM0601_crossTenantAndPlatformAreInvisible`），`@DisplayName` 文本未写入。
      独立核对改用“源码 @DisplayName/类 → 方法名 → surefire 报告 0 failure/0 skip”的映射：
      WorkflowTenantIsolationTest、WorkflowDepartmentIsolationTest、WorkflowAuditM06Test、
      IT-WF-101..112 对应类均 0 failure/0 skip；测试真实存在并执行，文档中的 grep 命令与当前
      报告格式不一致。
    accepted_reason: >-
      命名用例本身存在且通过，B-28/B-30 的关闭条件可由类/方法映射与套件完整性测试证明；失效的
      是 THREAT-MODEL 的机械 grep 命令，不是产品测试。作为 P3 accepted，由 Architect 在 M06b/
      下次威胁模型修订时把命令改为按类/方法或增加 surefire `includeDisplayName`，Verifier 在
      release 前复查。
    evidence: >-
      docs/security/THREAT-MODEL.md §8；EV-1166 surefire 报告；apps/api/src/test/java/com/riccon/api/security/WorkflowTenantIsolationTest.java；
      apps/api/src/test/java/com/riccon/api/security/WorkflowDepartmentIsolationTest.java；
      apps/api/src/test/java/com/riccon/api/audit/WorkflowAuditM06Test.java；REV-M06-001 F-010。
  - id: F-011
    severity: P3
    status: accepted
    owner: "安全评审/riccon/architect（DEC-0050=B 残余）；riccon/lead 跟踪"
    due: "M06 gate 前已登记，M06b/M09 复核是否改 keyed HMAC"
    title: "R-38：审计只存无盐 SHA-256，低熵审批理由可被字典/暴力确认"
    summary: >-
      DEC-0050=B 已选择审计 details 只写 `commentSha256`、理由原文保留在审批记录；但
      `WorkflowApprovalCommentHash` 是无盐 SHA-256/UTF-8。审批理由是用户自由文本，可能只有几个字
      （如“同意”），持有审计导出的人在可枚举候选集内可直接确认原文；哈希的可核对性依赖“猜测成本”
      而非密码学不可逆性。审计保留 180 天且可导出。ADR-0024 §8.3/R-38 与 THREAT-MODEL R-38 已
      登记该残余，M06 不声称已消除。
    accepted_reason: >-
      该风险是 DEC-0050=B 明确选择的已知取舍（最小化 PII 优先）；审批记录仍受 assignee/权限约束，
      审计导出需审批。作为 P3 accepted，owner=安全评审/Architect，due=M06b/M09 复核是否改
      keyed HMAC；不要求本模块改算法（改盐/密钥哈希会改变 DEC 与可核对性语义）。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalCommentHash.java；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalCommentHash.java；
      .riccon/decisions/DEC-0050.md（answer=B）；docs/adr/ADR-0024-m06-workflow-orchestration.md:621,674；
      docs/security/THREAT-MODEL.md R-38；REV-TASK-0252-001 F-001。
  - id: F-012
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/builder-b1"
    due: "M06b/M07 前（新增 workflow 审计写入方时）"
    title: "API/Worker 各自维护一份相同的 WorkflowApprovalCommentHash，且写入守卫 validateWrite 依赖调用方显式调用"
    summary: >-
      两侧 `WorkflowApprovalCommentHash` 内容逐字相同，固定向量测试已同时锁定；未来只改一侧会
      造成跨部署物审计哈希漂移，没有编译期共享约束。`AuditDetailsPolicy.validateWrite` 是新增写侧
      守卫，但通用入口 `AuditEvent.create` 仍只调用读兼容的 `validate`；当前唯一 API 写入方
      `WorkflowApprovalAuditEvents.decided` 已显式调用 `validateWrite`，Worker 策略直接不允许
      明文，因此今天没有明文写入路径；风险是未来新增写入方若只走 `AuditEvent.create` 可写回明文。
    accepted_reason: >-
      两侧实现与测试向量一致，本次 diff 未引入漂移；API 与 Worker 分属不同部署物，抽到 platform
      需要跨模块写范围/构建调整，超出本任务 scope。写侧守卫的调用点当前唯一且有测试锁定；作为
      有界维护性/防御纵深 residual 接受，在 M06b/M07 新增写入方时收敛共享实现并强制复用
      `validateWrite`。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalCommentHash.java；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalCommentHash.java；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:580-591；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditEvent.java；REV-TASK-0252-001 F-002/F-003。
  - id: F-013
    severity: P3
    status: accepted
    owner: "riccon/builder-b1（实现）/ riccon/architect（ADR 幂等语义）"
    due: "M06b gate 前"
    title: "A′ 配额后同键并发在上限边界可能把幂等重放变成 RICCON-WF-429；相关并发/失败分支测试仍有缺口"
    summary: >-
      `WorkflowRunService.create` 在取 S 之前做非锁定幂等读，随后 lock/admit/requireAdmissionLimits
      在 insertRun 之前；外层 catch 只处理 `DuplicateKeyException`/`QuotaExceededException`，
      不处理 `WorkflowRunRateLimitedException`。两个同键创建并发、先到者恰好把租户 10 或流程 5
      推到上限时，后到者会先等待先到者提交，随后 count 看到已提交运行并抛 WF-429，永远到不了
      唯一键冲突→新事务重读路径；`retry` 同样在上限边界可能返回 429 而不是重放 202。结果可重试、
      无重复对象/多扣配额/审计，但语义与 ADR-0022 §1.3-R 的“同键重放返回既有对象”有差。
      测试未覆盖同键满额重放、并发 M06 重试、prepare 锁重试耗尽与 admit 无条件 0 行 503。
    accepted_reason: >-
      触发需要同一幂等键的并发请求恰好落在租户/流程并发上限；结果是可重试的 WF-429，无数据损坏、
      无重复对象、无超卖、无重复扣费；顺序重放与主路径测试通过。A′ 的 X/S 位置符合
      ADR-0022 §1.3-R 的锁序，边界语义需 architect 明确，作为可在 M06b 前补测/小修的 P3 accepted。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java；
      apps/api/src/test/java/com/riccon/api/quota/QuotaMixedWorkloadApiTest.java；
      apps/api/src/test/java/com/riccon/api/workflow/run/WorkflowRunLifecycleApiTest.java；
      apps/api/src/main/java/com/riccon/api/quota/service/QuotaAdmissionService.java；
      docs/adr/ADR-0022-m04-quota-and-cost.md:170,173,215,220-228；REV-TASK-0249-001 F-002/F-003。
  - id: F-014
    severity: P3
    status: accepted
    owner: "riccon/builder-b1（Worker/配额测试）；riccon/architect（文档复核）"
    due: "M06b gate 前"
    title: "生产/测试注释仍与 DEC-0050=B、DEC-0049=A 当前口径相反（Worker 决定服务、配额混合测试）"
    summary: >-
      `apps/worker/.../WorkflowApprovalDecisionService.java` 类注释仍写“U-05 临时默认：完整理由
      进入审计，长度 1–500”，与 DEC-0050=B 和实际 hash-only 行为相反；
      `QuotaMixedWorkloadApiTest` 注释仍把“M06 运行准入的合同预算（p95 ≤500ms）”当作合同口径，
      而 DEC-0049=A 已将运行准入写路径单列为 ≤3s、500ms 只适用于不含准入的 Studio API。两者都
      是注释/测试文档滞后，行为与测试断言本身正确；可能误导后续评审误判安全/性能口径。
    accepted_reason: >-
      行为、审计 details、测试断言均已是当前口径；仅注释滞后，不影响运行、审计或测试结果。
      作为 P3 accepted，由 builder-b1/architect 在 M06b 前修正注释，避免把陈旧口径带进 M07/发布材料。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalDecisionService.java:54-55；
      apps/api/src/test/java/com/riccon/api/quota/QuotaMixedWorkloadApiTest.java:36-37；
      REV-TASK-0252-001 F-004；REV-TASK-0249-001 F-004；DEC-0049=A；DEC-0050=B。
  - id: F-015
    severity: P3
    status: accepted
    owner: "riccon/builder-b3"
    due: "M06b gate 前"
    title: "编辑器 label-in-name 回归测试只按可访问名称查询，未断言可见文本包含关系"
    summary: >-
      `apps/web/src/pages/workflow/edit/index.test.tsx` 对“校验/发布/上移/下移/删除/删除连线”仅用
      `getByRole('button', { name })` 查询可访问名称；若移除 `autoInsertSpace={false}`，可见文本
      会变回“发 布”等，而 aria-label 仍是“发布”，该测试仍会通过，无法守住 T16a
      `label-content-name-mismatch` 的回归。模板库、运行历史、审批测试已用 aria-label/textContent
      包含断言，编辑器这一组缺失。
    accepted_reason: >-
      实现本身已正确（源码对相关按钮设置 `autoInsertSpace={false}`，aria-label 与可见文本一致），
      真实 axe 由 E2E-M06-09a 在最终提交通过（EV-1167）；该缺口只降低组件级回归检出能力，不造成
      当前用户可见缺陷，作为有界 P3 accepted。
    evidence: >-
      apps/web/src/pages/workflow/edit/index.test.tsx；
      apps/web/src/pages/workflow/edit/index.tsx；REV-TASK-0248-001 F-001；EV-1167（E2E-M06-09a）。
  - id: F-016
    severity: P3
    status: accepted
    owner: "riccon/product（状态表）；riccon/architect（§12 复核）"
    due: "M06b/下一次 PRODUCT 修订"
    title: "PRODUCT 顶部评审状态表仍把 U-05 记为 accepted residual，与 §12.4/AC-16 的“已按 DEC-0050=B 解决”不一致"
    summary: >-
      `docs/PRODUCT.md` §12.4 与 AC-16 已写 U-05 由 DEC-0050=B 解决、F-001 fixed；但文件顶部
      “Verifier AC 可验证性 / M06 状态”历史表仍保留“U-05/U-13 accepted residual”的设计评审口径。
      该表是 2026-11-25 设计评审的历史记录，不影响 §12 AC 文本、ACCEPTANCE 或用户可见行为；
      但若被 release/评审材料直接引用，会与最终结论冲突。
    accepted_reason: >-
      §12/ACCEPTANCE/ADR/THREAT 均已同步最终口径，顶部表是历史评审状态而非当前 AC；风险是文档
      引用歧义，不影响行为与证据。作为 P3 accepted，由 Product 在 M06b/下次 PRODUCT 修订时把
      M06 行更新为“U-05 resolved（DEC-0050=B）、F-001 fixed”或标注历史口径。
    evidence: >-
      docs/PRODUCT.md:11,26,28 与 docs/PRODUCT.md:852,887；docs/ACCEPTANCE.md M06 段；
      docs/adr/ADR-0024-m06-workflow-orchestration.md §8.3/§16；REV-M06-001 F-002/F-009。
  - id: F-017
    severity: P3
    status: accepted
    owner: "riccon/builder-b3（UI）；riccon/verifier（a11y 用例）"
    due: "M06b 或下一次 a11y 用例硬化"
    title: "打开 Drawer 后立即扫描时 antd Button 颜色过渡可能瞬时命中 axe color-contrast；稳定态/键盘焦点态无 serious/critical"
    summary: >-
      EV-1171 在点击“运行限制”后立即扫描时，axe 曾在触发按钮 `.ant-btn-link > span` 命中一次
      `color-contrast`（serious），计算色为 `rgb(87,157,242)`、背景 #f5f5f5；等待约 1s 过渡结束后
      桌面与 375px 稳定态均无 serious/critical，键盘 focus-visible 态与页面静止态触发按钮为
      #595959（约 6.4:1）。该现象是 antd Button 200ms 颜色 transition 的瞬时态，不是持久可操作
      状态，也不影响当前 E2E（E2E-M06-09a 在页面稳定后扫描并通过）。
    accepted_reason: >-
      持久状态（页面静止、键盘焦点、Drawer 打开稳定态）axe WCAG 2.1 AA 无 serious/critical；
      该瞬时态只在点击后 <0.3s 窗口可观测，属于测试时序/动画细节。作为 P3 accepted，后续 a11y
      用例应等待 transition 结束或在稳定态扫描，避免把动画中间帧当作持久违规；不要求改产品颜色。
    evidence: >-
      EV-1171；.riccon/evidence/TASK-0253/runtime-limits-drawer-a11y.log；
      apps/web/src/components/workflow/runtime-limits.tsx；apps/web/src/pages/workflow/templates/templates.css:51-59。
reviewed_task_reviews:
  - REV-M06-ARCH-003
  - REV-M06-OPS-004
  - REV-TASK-0219-001
  - REV-TASK-0220-001
  - REV-TASK-0221-002
  - REV-TASK-0222-001
  - REV-TASK-0223-002
  - REV-TASK-0224-002
  - REV-TASK-0225-002
  - REV-TASK-0226-001
  - REV-TASK-0227-001
  - REV-TASK-0228-003
  - REV-TASK-0229-001
  - REV-TASK-0230-001
  - REV-TASK-0231-001
  - REV-TASK-0232-003
  - REV-TASK-0233-002
  - REV-TASK-0234-001
  - REV-TASK-0235-002
  - REV-TASK-0236-002
  - REV-TASK-0237-001
  - REV-TASK-0238-001
  - REV-TASK-0240-001
  - REV-TASK-0241-002
  - REV-TASK-0242-001
  - REV-TASK-0243-001
  - REV-TASK-0244-002
  - REV-TASK-0245-001
  - REV-TASK-0246-001
  - REV-TASK-0247-001
  - REV-TASK-0248-001
  - REV-TASK-0249-001
  - REV-TASK-0251-001
  - REV-TASK-0252-001
evidence:
  - EV-1165
  - EV-1166
  - EV-1167
  - EV-1168
  - EV-1169
  - EV-1170
  - EV-1171
  - EV-1163
  - EV-1164
  - EV-1146
  - EV-1147
  - EV-1148
  - EV-1149
  - EV-1150
  - EV-1151
reviewed_evidence:
  - EV-1165
  - EV-1166
  - EV-1167
  - EV-1168
  - EV-1169
  - EV-1170
  - EV-1171
  - EV-1163
  - EV-1164
  - EV-1146
  - EV-1147
  - EV-1148
  - EV-1149
  - EV-1150
  - EV-1151
  - EV-1016
  - EV-1017
  - EV-1019
  - EV-1021
  - EV-1030
  - EV-1033
  - EV-1045
  - EV-1067
  - EV-1077
  - EV-1079
  - EV-1084
  - EV-1094
  - EV-1082
  - EV-1038
  - EV-1053
  - EV-1114
  - EV-1118
  - EV-1089
  - EV-1065
  - EV-1076
  - EV-1159
  - EV-1025
  - EV-1041
  - EV-1049
  - EV-1057
  - EV-1066
  - EV-1091
  - EV-1119
  - EV-1110
  - EV-1145
  - EV-1142
  - EV-1144
  - EV-1172
notes: >-
  M06 模块评审在最终文档提交 9b86e2b6 上重跑合同 build/test+coverage/e2e、security、docs、
  typecheck+lint；EV-1166 为 external 热栈 + t17b namespace 的合同 test/coverage（API 1049、
  Worker 924、common 87、domain 99、ArchUnit 12，全 0/0/0 skip；web 111 文件/1049 用例，
  coverage 87.7/80.84/85.46/87.61），EV-1165 为合同 build，EV-1167 为合同 stub E2E 63 passed +
  真实栈 41 passed（含 E2E-M06-01..10、E2E-M06-09a/b/c @a11y、J1/J2/M03/M10/M04/M05/M25 回归），
  EV-1168 为 security，EV-1169 为 docs/contract validate，EV-1170 为 typecheck/lint；a11y/perf/
  rollback 复用 T16a/T16b clean EV（EV-1146..1151），EV-1171 为 AC-17 Drawer 真实浏览器补充。
  AC-17/F-001 与 U-05/F-002 已关闭；P0/P1 为 0，P2 只剩 F-003 环境 residual，其余为 P3 accepted。
  本评审是独立技术验收，不是 Product 预验收或用户最终验收；M06b/M26 不在本评审范围。
created_at: "2026-11-26T12:48:18+08:00"
---

# REV-M06-002：M06 编排 Studio 模块评审（最终版，取代 REV-M06-001）

## 结论

**通过（pass，with residuals）**。M06 的 23 条 gate AC（AC-01..10、12..24）在最终文档提交
`9b86e2b6` 上逐条对账：19 条 `pass`、4 条 `pass (with residual)`（AC-14/18/21/23），0 条 `fail`；
AC-16 按 DEC-0050=B 通过（审计只写决定 + commentSha256），R-38 无盐哈希确认风险作为已接受 P3
残余另行登记。合同 build、test+coverage（同一条
全量命令与 EV）、compose 合同 E2E + 真实栈 E2E、Trivy security、docs/contract validate、typecheck/
lint 均在最终提交上 exit 0；a11y/perf/rollback 复用 T16a/T16b 的 clean EV，并新增 AC-17 Drawer
的真实浏览器复核。无 open P0/P1。

本评审是独立技术验收，不是 Product 预验收或用户最终验收。Product 已在 `9b86e2b6` 记录 M06 预验收
`pass`（with residual F-003/P3/R-M06-PERF-01），`module.product_acceptance` 仍需 Lead/Product 按
流程更新；本评审不代替用户最终验收。

## 验证了什么（最终提交 `9b86e2b6`，工作树 clean）

| DoD / 审查项       | 命令（与合同对应）                                                                                                                                                                                                                                                                                       | 结果                                                                                                                                                                                                                                                                                                      | EV                                 |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| build              | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                                                                                                                                                                                                    | exit 0；Web 生产构建成功（含 `p__workflow__templates__studio-nav` chunk）                                                                                                                                                                                                                                 | EV-1165                            |
| test + coverage    | `node scripts/test-services.mjs reset --namespace t17b --module all && RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=t17b TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`（包含合同子串；默认 Testcontainers 环境问题见 F-003） | exit 0；API 1049、Worker 924、platform-common 87、platform-domain 99、ArchUnit 12，全 0 failure/0 error/0 skip；API line 92.29/branch 74.09、Worker 87.49/71.97、common 90.83/75.41、domain 97.33；Web 111 文件/1049 用例，statements 87.7/branches 80.84/functions 85.46/lines 87.61（均高于合同 80/70） | EV-1166                            |
| E2E                | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e && REAL_EVIDENCE_TASK=TASK-0253-e2e node scripts/real-stack/run.mjs --mode=full`                                                                                                                                   | 合同 stub E2E **63 passed**；真实栈 **41 passed**（E2E-M06-01..10、E2E-M06-09a/b/c `@a11y`、J1/J2/M03/M10/M04/M05/M25 回归；all phases passed：compose/migration/fingerprint/seed/seedIdempotent/m10Checks/browserE2e/j2Journey/securityChecks/m04Checks/m03Checks）                                      | EV-1167                            |
| coverage           | 与 test 同一条 EV（禁止跑两遍）                                                                                                                                                                                                                                                                          | line/branch 门槛由 jacoco:check 与 vitest thresholds 强制；API/Worker/common/domain/Web 均完成报告                                                                                                                                                                                                        | EV-1166                            |
| a11y               | T16a EV-1146/EV-1148 + T17b EV-1171（AC-17 Drawer 真实浏览器）                                                                                                                                                                                                                                           | E2E-M06-09a/b/c 真实栈通过（axe 无 serious/critical、375px 无横向滚动、键盘全流程）；EV-1171 在真实 Chromium 打开限制 Drawer，桌面与 375px axe WCAG 2.1 AA 无 serious/critical，scrollWidth=375，Enter/Tab/Escape 焦点正确                                                                                | EV-1146、EV-1148、EV-1171、EV-1167 |
| perf               | T16a EV-1147（等价 `commands.perf` harness）                                                                                                                                                                                                                                                             | J1 首字 p95 50.1ms（619 样本）、Studio 读 API p95 141ms（860 样本）、运行准入写路径 p95 2557ms（115 样本，DEC-0049=A ≤3s）、run_ok 108/108、50 VU 混合；报告记录假模型/限制值/退出码                                                                                                                      | EV-1147                            |
| security           | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`                                                                                                                                                                                 | exit 0；0 vulnerabilities / 0 secrets / 0 misconfigurations（B-06/ADR-0011 例外仍按既有决策跟踪）                                                                                                                                                                                                         | EV-1168                            |
| migration/rollback | V15 迁移测试（EV-1166）+ T16b 演练（EV-1150）                                                                                                                                                                                                                                                            | V9–V15 迁移测试全过；OPS-M06-01 27 assertions、0 failed；残余见 F-007                                                                                                                                                                                                                                     | EV-1166、EV-1150                   |
| docs               | `git diff --check`；RUNBOOK M06、THREAT-MODEL TM-85/R-38、PRODUCT AC-16/AC-17/DEC-0050=B、ACCEPTANCE M06 pass、ARCHITECTURE U-05、OpenAPI `1.6.0-m06`、`contract validate --strict` 一致性 grep                                                                                                          | exit 0；`contract validate --strict` 0 errors/0 warnings；最终文档与最终代码一致（U-05=B、AC-17 fixed、R-38、ACCEPTANCE 19 pass + 4 residual + 0 fail）                                                                                                                                                   | EV-1169                            |
| typecheck + lint   | `./mvnw -q spotless:check && pnpm -w lint && pnpm -w typecheck`                                                                                                                                                                                                                                          | exit 0（非模块 gate key，作为质量基线；Spotless/ESLint/Prettier/tsc 全通过）                                                                                                                                                                                                                              | EV-1170                            |

> 证据绑定说明：EV-1165..1171 均记录在 `9b86e2b6`，`dirty=false`、`exit_code=0`、日志 sha256/bytes
> 与 JSON 一致；EV-1146/EV-1148 是 T16a 在 `ff5824ef` 的页面级 a11y EV，`ff5824ef` 之后只有
> TASK-0251 的 workflow UI 与 TASK-0252 的审批审计变更，页面级 a11y 已由最终提交的 EV-1167
> （E2E-M06-09a/b/c）重跑，新增 Drawer 由 EV-1171 真实浏览器复核；EV-1147（perf）、EV-1150
> （回滚）、EV-1151（worker 结果/节点输出上限）的代码路径在对应 EV 之后未被修改：
> `git diff db7e617b..9b86e2b6 -- apps/api/src/main/resources/db/migration scripts/ops` 为空，
> `git diff 99820a04..9b86e2b6 -- apps/worker/src/main/java/com/riccon/worker/workflow/engine
> apps/worker/src/main/java/com/riccon/worker/workflow/tool` 为空，
> `git diff ff5824ef..9b86e2b6 -- apps/api/src/main/java/com/riccon/api/quota
> apps/api/src/main/java/com/riccon/api/workflow/run apps/worker/src/main/java/com/riccon/worker/workflow/queue`
> 为空（EV-1147 量测的准入写路径未变）。EV-1171 是 manual 证据，仅作 AC-17 Drawer
> 真实浏览器补充，不用于 DoD binding；DoD a11y 仍由 EV-1146/EV-1148/EV-1167 支撑。

## 回归与套件完整性

- **J1/J2**：合同 stub E2E 与真实栈 `j1.spec`/`j2.spec` + E2E-M06-10 全部通过；J2 的入口、阶段与
  产物行为不变。
- **M03/M10/M04/M05/M25**：真实栈 `m03Checks`、`m10.spec`（OIDC/审计/导出）、`m04-*`、`m05.spec`/
  `m05b.spec` 全通过；M05 文档 ACL/引用遮蔽、M25 连接器端点/凭据/同步回归未发现失败。
- **越权/审计**：EV-1149 定向 `TEN-M06-01`、`DEPT-M06-01`、`IT-AUDIT-M06-01`、
  `WorkflowValidationNegativeMatrix` 0 failure/0 skip；EV-1166 全量中相关命名用例 0 skip。
- **测试真实性**：EV-1166 的 5 个 Maven 模块合计 2171 个测试（1049+924+87+99+12），全 0 failure/
  0 error/0 skip；Web 1049 用例 111 文件全通过；worker `target/surefire-reports` 中有 3 个
  10:40 的旧报告（OcrCliTest 5、AgentContextSearchFulltextBenchmarkIT 1、OcrLineAssemblerTest 4）
  不属于本次运行窗口，已排除；窗口内 worker 为 160 文件/924 tests。未发现被跳过且无 owner 的测试、零测试命令、
  吞错 exit 0 或 retry 掩盖。EV-1166 的 external 热栈模式已在 note 记录原因与 namespace。

## 威胁模型 B-27..B-30

| 阻断项                                                        | 结论                       | 证据                                                                                                                                                                   |
| ------------------------------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B-27 代码/脚本/HTTP/并行/循环/定时入口与工具白名单/写工具审批 | pass                       | SEC-WF-004/005、IT-WF-104/111；`WorkflowNodeType` 7 类封闭枚举、条件算子 7 类、无表达式/脚本；UI 无人为入口（EV-1166/1167）                                            |
| B-28 权限/租户/部门 403/404、平台管理员、自批                 | pass                       | SEC-WF-001/002/003/006、TEN-M06-01、DEPT-M06-01、IT-WF-112；全量 0 skip；`WorkflowPlatformTenantFilter` 对 platform 统一 404（EV-1166/1167）                           |
| B-29 资源上限/并发/结果大小/超时/kill switch/回滚             | pass (with residual F-007) | IT-WF-109/110、PERF-M06-001、OPS-M06-01；演练缺口见 F-007（EV-1147/1150/1166）                                                                                         |
| B-30 威胁模型/审计白名单/注入/审批摘要 ACL                    | pass (with residual R-38)  | THREAT-MODEL §4.13/§5.12/§6；`AuditDetailsPolicy` 写入/读取白名单；U-05=B 审计 hash-only；SEC-WF-004、IT-WF-106/108、IT-AUDIT-M06-01；R-38 已登记（EV-1163/1166/1169） |

## AC 对账（M06-AC-01..24；AC-11/25 归 M06b/M26）

| AC    | 用户可见 Then（摘要）                                                                                                                                                         | 结果                                   | 证据（测试 ID / 报告）                                                                                      |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| AC-01 | 草稿出现在“我的流程”、重开一致、未发布不可运行、并发保存 409 可另存副本                                                                                                       | pass                                   | E2E-M06-01、IT-WF-101（EV-1167/1166）                                                                       |
| AC-02 | 校验逐项指出节点/原因/修正入口，发布被阻止；修正后通过且画布/表单一致                                                                                                         | pass                                   | E2E-M06-01、IT-WF-101、WorkflowValidatorTest/IT-WF-111（EV-1167/1166）                                      |
| AC-03 | 发布不可变版本、版本号/发布人/时间/范围可见；运行绑定版本；旧版本仍可运行                                                                                                     | pass                                   | E2E-M06-02、IT-WF-102；V15 trigger 测试（EV-1167/1166）                                                     |
| AC-04 | 运行时间线状态/耗时/当前节点高亮；刷新一致；结果摘要不泄露凭据/提示词                                                                                                         | pass                                   | E2E-M06-03（EV-1167）                                                                                       |
| AC-05 | 审批待办只显示自己、按时间升序、详情脱敏；批准继续、拒绝终止且理由必填                                                                                                        | pass                                   | E2E-M06-04、SEC-WF-001、IT-WF-108（EV-1167/1166）                                                           |
| AC-06 | 禁自批 403+审计；范围外/停用/他租户不泄露；无审批人不自动放行                                                                                                                 | pass                                   | SEC-WF-001、E2E-M06-04（EV-1167/1166）                                                                      |
| AC-07 | A 成功/B 失败/C 跳过，整体失败；重试从 B 继续不重跑 A                                                                                                                         | pass                                   | E2E-M06-05、IT-WF-103（EV-1167/1166）                                                                       |
| AC-08 | 重复运行/重试不重复执行/追加/建产物；写工具重试重新审批；未知副作用默认不自动重试                                                                                             | pass                                   | IT-WF-104、E2E-M06-05（EV-1167/1166）                                                                       |
| AC-09 | 条件只走一支、另一支跳过；无法求值失败不默认分支                                                                                                                              | pass                                   | E2E-M06-06、IT-WF-105（EV-1167/1166）                                                                       |
| AC-10 | 内置市场调研模板复制为当前用户草稿、模板不被修改、填写参数后发布运行产出带引用结果                                                                                            | pass                                   | E2E-M06-07（EV-1167）                                                                                       |
| AC-11 | M06b（租户内模板共享/私有模板 404/审计）                                                                                                                                      | not in M06 gate                        | 归 M06b AC-11/25；未验收，不得用 M06 证据替代                                                               |
| AC-12 | 发起人/所有者/管理员可见范围、倒序分页、过滤/空态；越权统一 404                                                                                                               | pass                                   | E2E-M06-08、SEC-WF-003（EV-1167/1166）                                                                      |
| AC-13 | 时间线/输入输出摘要/引用/审批/失败/重试；引用遵守 M05 ACL/sourceStatus；不泄露 prompt/参数/正文                                                                               | pass                                   | E2E-M06-08、IT-WF-106（EV-1167/1166）                                                                       |
| AC-14 | 默认 180 天、租户策略优先、到期不可见、无在线删除；显示实际生效保留值与每条运行到期时间                                                                                       | **pass (with residual F-008)**         | IT-WF-107、E2E-M06-08（“保留到期”）；限制 Drawer 显示 180 天/部署默认；运行历史/详情未显示保留天数          |
| AC-15 | 角色/部门/租户/平台管理员 403/404 矩阵；取消/停用/启用/授权/移交权限与审计                                                                                                    | pass                                   | SEC-WF-002/003/006、TEN-M06-01、DEPT-M06-01、IT-WF-112（EV-1166/1167）                                      |
| AC-16 | 发布/运行/取消/审批/停用/授权/移交审计可查；管理读取审计；审计失败业务失败；不记 prompt/参数/正文；审批含决定+commentSha256，明文理由只在审批记录，旧行读取兼容（DEC-0050=B） | **pass (U-05 B)**                   | IT-WF-108/112、IT-AUDIT-M06-01（EV-1166）；EV-1163；R-38 无盐哈希确认风险见 F-011                           |
| AC-17 | 节点/步数/超时/并发/5 MiB 拒绝且提示具体限制/当前值/下一步；调整对新请求生效；租户只读查看；限制值界面可见                                                                    | **pass (F-001 fixed)**                 | IT-WF-109、E2E-M06-10（EV-1167）、EV-1164；Drawer 真实浏览器 EV-1171；429 limit/current 组件测试（EV-1166） |
| AC-18 | 未授权/停用工具/越权文档拒绝且不调用；写/高风险工具审批；写工具重试重审批；注入不改变图/工具/不泄露 canary；审批摘要 ACL                                                      | **pass (with residual F-004)**         | SEC-WF-004、E2E-M06-04、IT-WF-104/105（EV-1167/1166）                                                       |
| AC-19 | 五界面 loading/empty/error/无权限/禁用/成功态中文说明；375px 无横向滚动；键盘焦点可见                                                                                         | pass                                   | E2E-M06-09a/b（EV-1167）；web 组件测试                                                                      |
| AC-20 | 键盘完成模板→节点/连线→校验→保存→发布→运行→审批→详情；等价列表/表单；axe 无 serious/critical；375px                                                                           | pass                                   | E2E-M06-09c、E2E-M06-09a/b（EV-1167）                                                                       |
| AC-21 | J1 首字 ≤3s、Studio 读 API ≤500ms、运行准入写路径 ≤3s（DEC-0049=A）；50 并发无重复/状态丢失/审批错配                                                                          | **pass (with residual R-M06-PERF-01)** | EV-1147（p95 50.1ms/141ms/2557ms；run_ok 108/108）                                                          |
| AC-22 | J1/J2/M03/M10/M04/M05/M25 无回归；M06 端点真实实现；M06 动作可在 M10 审计按对象/时间查到                                                                                      | pass                                   | E2E-M06-10（EV-1167）、contract stub E2E、WorkflowContractM06Test（EV-1166）                                |
| AC-23 | 关闭编排入口新运行不受理、J1/J2 可用、历史只读、关闭审计；取消保留结果；关开关+回滚 Worker、历史/审计可读；有数据不支持 API 回滚；威胁模型 gate 前更新                        | **pass (with residual F-007)**         | IT-WF-110（EV-1166）、EV-1150（OPS-M06-01 27 assertions）、RUNBOOK M06-8/9/12、THREAT §7/§8                 |
| AC-24 | 无代码/脚本/HTTP/并行/循环/子流程/定时入口；平台管理员 404；文案不声称已支持                                                                                                  | pass                                   | SEC-WF-005、IT-WF-111、WorkflowValidationNegativeMatrix（EV-1166）；UI/grep 无相反文案                      |

## Findings

见 front matter F-001..F-017。**无 P0/P1**。P2：F-003（本机 Testcontainers 环境 residual，accepted）；
F-001（AC-17）与 F-002（U-05）已 fixed。其余均为 P3 accepted（T07/T12/T09/T16b 残余、A′ 幂等边界、
无盐哈希 R-38、双份 hash/写守卫、陈旧注释/测试守卫、ADR 状态与 PRODUCT 顶部表历史口径）。

## 剩余风险（Residual）

- **R-M06-PERF-01**：运行准入写路径 p95 2588.6ms（A′ 修复前 3394.4ms），DEC-0049=A 已接受
  ≤3s 口径；EV-1147 复跑 p95 2557ms（115 样本）、读 API p95 141ms（860 样本）。owner=Lead/Architect，
  due=release 前跟踪，M06b/M09 可再优化。EV-1143（旧 500ms 阈值 exit 1）不作为当前判定。
- **R-38 / U-05 残余**：审计只存无盐 SHA-256，低熵理由可被字典/暴力确认；DEC-0050=B 接受，
  owner=安全评审/Architect，due=M06b/M09 复核是否改 keyed HMAC；见 F-011。
- **F-003 环境**：本机默认 Testcontainers RustFS 503/启动等待；EV-1166 用 external 热栈 + t17b
  namespace 通过；owner=Lead/Verifier，due=CI/M06b 前；不声称默认 Testcontainers 全量稳定。
- **F-007（T16b）**：触发器计数测量权限、旧 Worker digest 来源、关闭态 J1/J2 冒烟、容量样本不足；
  owner=Ops，due=M09/release 演练前。
- **F-013（A′ 幂等边界）**：同键并发在上限边界可能返回可重试 WF-429；owner=builder-b1/architect，
  due=M06b；无重复对象/扣费/审计。
- **F-017（a11y 时序）**：Drawer 打开瞬间的 antd 颜色过渡可能被 axe 瞬时命中；稳定态/键盘焦点态
  通过；owner=builder-b3/verifier，due=M06b 硬化 a11y 用例（等待过渡结束）。
- **F-004/F-005/F-008/F-009/F-010/F-012/F-014/F-015/F-016/F-017**：T07/T12/T09/T16b/文档/注释/a11y 时序类 P3
  accepted；owner/due 见 front matter。均不影响 AC 的 Then 子句或安全边界。
- **M06b/M26**：模板库/共享/升级提示不在 M06 范围；不得把 M06 证据用于 M06b gate。

## 未验证

- Product 预验收与用户最终验收：Product 已在 `9b86e2b6` 记录 `pass`（with residual F-003/P3/
  R-M06-PERF-01），但 `module.product_acceptance` 仍 pending（由 Lead/Product 更新）；本评审不代替
  用户最终验收。
- 生产 K8s/Helm、备份恢复、镜像签名与客户真实容量：M09/release。
- M06b 模板共享/升级：AC-11/25。
- 默认 Testcontainers 全量稳定性：见 F-003；EV-1166 是 external 热栈 + t17b 模式。
- 429 UI 文案的真实栈浏览器触发：EV-1166 的组件测试与 EV-1167 的 E2E-M06-10 API 覆盖；未单独在
  真实栈触发 429 toast（组件测试已断言具体文本；风险低）。
- 审计导出中 `workflow.approval.decided` 行的单独 E2E：导出复用 `AuditDetailsReader`，查询/读回由
  WorkflowAuditM06Test（EV-1166）覆盖；未单独跑导出专项，风险低（见 TASK-0252 评审核对）。

## Lead 下一步

1. 运行 `riccon gate check --module M06`；本评审 `REV-M06-002` verdict=pass，DoD evidence 均为
   最终提交 `9b86e2b6` 上的 clean EV。
2. 将 `module.review` 指向本文件，并把 `module.product_acceptance` 更新为 Product 的 `pass`
   （with residual F-003/P3/R-M06-PERF-01）；用户最终验收前不标 module done。
3. 用户最终验收 `accepted` 后再启动 M06b/M26；本评审不批准 M06b。
4. Architect/Lead 在 closeout 前处理 F-009（ADR 状态/ARCH“尚未实现”）与 F-016（PRODUCT 顶部历史
   表口径）；Ops 按 F-007 在 M09/release 前补演练证据；builder/architect 按 F-011..F-015 在 M06b 前
   收敛 P3 残余。
5. 不把 EV-1166 的 external/t17b 模式当作“默认 Testcontainers 已稳定”；CI/M06b 前按 F-003 收敛。
