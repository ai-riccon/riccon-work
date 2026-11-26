---
review_id: REV-M06-001
module_id: M06
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dead3ed7affeHC0nDR5UL97NZZ
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
review_status: final
head_commit: 5094cb0b726d78f4a511f569bd0029d186a7a81f
baseline_commit: 9347f11cc0f235a4b7cd7cb7119822beda0eafb3
reviewed_commits:
  - 9347f11cc0f235a4b7cd7cb7119822beda0eafb3
  - ff5824ef6749dd013ff746d116a8bace1df37b2d
  - db7e617b99a3921cb94290842f32e088596d392c
  - 99820a04
  - 8ff23f4754167320d8ca3eeeb062285385bbc7e9
  - 5094cb0b726d78f4a511f569bd0029d186a7a81f
  - 27c36e6469e24325f134f62e1502f1c0ccf71d46
  - 2ce72fcec0775a03f1ecd30896f3df366ca6fcb3
  - f1bee5a6cbe58ca3fadd2158446b4d4978f8aedc
  - 9fa19324
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
    - EV-1155
    - EV-1156
    - EV-1147
    - EV-1150
  build:
    - EV-1152
  tests:
    - EV-1155
  coverage:
    - EV-1155
  e2e:
    - EV-1156
  docs:
    - EV-1158
  evidence:
    - EV-1152
    - EV-1155
    - EV-1156
    - EV-1157
    - EV-1158
    - EV-1146
    - EV-1147
    - EV-1148
    - EV-1149
    - EV-1150
    - EV-1151
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead（范围/验收）；riccon/builder-b3（Web）"
    due: "M06b gate 前或用户最终验收前"
    title: "AC-17 的“租户管理员只读查看本租户限制、限制值在界面可见”未实现独立界面，超限文案未展示具体限制/当前值"
    summary: >-
      AC-17 同时要求服务端强制与用户可见性：租户管理员只读查看本租户限制、限制值在界面可见、
      超限提示包含具体限制/当前值与下一步。实现对服务端部分完整（`GET /api/v1/workflows/runtime-limits`、
      IT-WF-109、并发 429 `RICCON-WF-429`、失败/结果上限稳定码、部署配置默认值），但 Web
      `apps/web/src` 中没有任何 `/workflows/runtime-limits` 读取或限制值展示；grep
      `runtime-limits|runtimeLimits|maxNodes|resultMaxBytes` 只命中 list 页的一条旧注释。
      E2E-M06-10 只用 API 断言 limits 字段，E2E-M06-09a/b/c 是 axe/375px/键盘，不覆盖限制界面。
      `describeCreateRunFailure`/`describeRunControlFailure` 的 429 文案是“并发运行已达上限，请稍后
      重试”，未包含具体 limit/current/等待建议；结果上限文案固定写“默认 5 MiB”。因此 AC-17 的
      用户可见 Then 子句不能判无条件 pass。
    accepted_reason: >-
      服务端资源限制、稳定错误码与 5 MiB/节点/步数/并发拒绝行为均有集成测试与 E2E，未发现绕过或
      静默截断；缺的是租户只读限制视图与超限文案的具体数值，属于产品完整性/UX residual，不是
      安全/数据边界缺陷。作为 P2 accepted，由 Lead 与 builder-b3 在 M06b gate 或用户最终验收前
      决定补一个只读限制面板/帮助浮层并把 429 limit/current 带入文案；Product 预验收必须显式
      接受或要求修复。
    evidence: >-
      docs/PRODUCT.md:834（M06-AC-17 原文）；apps/api/src/main/java/com/riccon/api/workflow/runtime/controller/WorkflowRuntimeLimitsController.java；
      apps/api/src/test/java/com/riccon/api/workflow/runtime/WorkflowRuntimeLimitsApiTest.java:23-97；
      apps/web/src/pages/workflow/history/format.ts:595-610；grep -rn "runtime-limits|runtimeLimits|maxNodes|resultMaxBytes" apps/web/src（仅 list/index.tsx:73 注释）。
  - id: F-002
    severity: P2
    status: accepted
    owner: "安全评审/riccon/product（Lead 跟踪）"
    due: "M06 gate 的 Product/安全明确决定"
    title: "U-05 审批理由入审计 PII 二选一仍未发生（ADR 明确 gate 前必须解决）"
    summary: >-
      ADR-0024 §8.3 与 §16 U-05 仍写“未解决”，要求 M06 gate 前由安全/Product 在“≤500 字符理由入
      审计”与“审计只存 commentSha256、审批记录保留理由”之间二选一并补测试。代码仍把
      201–500 字符 `decisionComment` 原样写入 `workflow.approval.decided`；T16a 已补 500/501 与
      审计读回证据，但没有 commentSha256 替代路径或新的 Product/安全结论。本 review 不把 T16a/
      TASK-0229 的 accepted 当成安全批准。
    accepted_reason: >-
      风险有界：理由为用户输入、≤500 字符、只进入审批记录与审计 details、不写 prompt/文档正文；
      审计 details 白名单与长度上限已实现。作为 P2 accepted，由 Lead 在 M06 gate/用户最终验收前
      取得 Product/安全检查的明确选择；若选择 commentSha256 需另立 fix 任务。M06 交付不声称
      U-05 已解决。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:620,974,1050；
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalAuditEvents.java:42-91；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:323-335,501；
      REV-TASK-0229-001 F-002；REV-TASK-0237-001 F-001。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/verifier（CI/测试栈）"
    due: "M06b/CI 下次全量前"
    title: "本机默认 Testcontainers 全量测试出现 connector RustFS 静态初始化 503/启动长等待；T17 用 external 热栈 + t17 namespace 完成合同 test/coverage"
    summary: >-
      2026-11-26 的两次默认全量运行中：EV-1153（仅 override）1040 tests 0 errors 但命中历史
      cancel/decide flake 1 failure；EV-1154（override）23 个 connector errors，原因是
      `ConnectorApiTestSupport` 静态初始化在 RustFS Testcontainer 创建桶时收到 S3 503
      （SDK Attempt Count 3，容器 health 已过但 S3 API 未就绪）。另一次默认运行的
      V13MigrationTest MySQL 容器在本机 Docker Desktop 资源紧张时等待超过 25 分钟，被终止。
      独立定位：`OBJECT_STORAGE.getHost()` 的 localhost/IPv6 与 Testcontainers RustFS 就绪竞态
      叠加，并非产品 connector 代码失败；单类 `ConnectorCrudApiTest` 加
      `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1` 后 10/10 通过。最终 T17 按 CODING-STANDARDS 的
      外部热栈模式（compose MySQL/OpenSearch/RustFS，`RICCON_TEST_SERVICES=external` +
      `RICCON_TEST_SERVICES_NAMESPACE=t17`）完成合同 test/coverage（EV-1155），迁移测试仍用一次性
      容器；命令包含合同 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage` 子串。
    accepted_reason: >-
      该问题只影响本机 Testcontainers 主机解析/资源，不影响产品端点与 M06/M05b 业务断言；CI 与
      其他主机仍使用默认命令。作为 P2 accepted，由 Lead/verifier 在 CI 或 M06b 前把
      `TESTCONTAINERS_HOST_OVERRIDE`/RustFS 就绪重试收敛进测试 harness 或 CI 环境，禁止把环境
      变量当成永久静默参数；EV-1155 的 note/命令已记录 external 模式、namespace、原因与影响范围，
      不能把它当作“默认 Testcontainers 全量已稳定”的声明。
    evidence: >-
      EV-1153（exit 1，API 1040 tests/0 errors/1 failure flake）；EV-1154（exit 1，API 1040
      tests/23 connector errors，S3 503）；2026-11-26 第一次默认运行 V13MigrationTest 卡住（已
      终止，无 EV）；`TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -q -pl apps/api test
      -Dtest=ConnectorCrudApiTest` 独立复跑 10/10；EV-1155（exit 0，external 热栈 + t17）。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/architect（归属）；riccon/lead 跟踪"
    due: "M06b/M07 gate 前"
    title: "T07 F-001/F-002 残余：mcp_tool.input_schema 执行前校验缺失；READ+requires_approval=false 的保存/运行语义不一致"
    summary: >-
      TASK-0237 任务卡要求关闭 REV-TASK-0227-001 F-001..F-003；T16a 已补预算/输出上限/并发唯一键/
      PENDING/自绑目标等用例，但 `McpCallExecutor`/`WorkflowToolNodeExecutor` 仍未按
      `mcp_tool.input_schema` 校验参数，`WorkflowToolApprovalVerifier` 仍对所有工具无条件要求审批，
      而 T03 校验器允许 READ+requires_approval=false 的图发布。当前 V5/M02 目录全部
      requires_approval=TRUE，运行侧更严格，只可能拒绝、不会放行，未发现越权/副作用绕过。
    accepted_reason: >-
      可达工具目录没有免审批条目，缺失 schema 校验未证明可绕过租户授权/审批/plan 哈希；影响是
      未来 M07 工具目录扩展的纵深防御与语义一致性。作为 P3 accepted，由 Architect 在 M06b/M07
      前决定归属并补执行前 schema 校验/统一口径。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:380；
      apps/worker/src/main/java/com/riccon/worker/workflow/tool/service/WorkflowToolNodeExecutor.java:224-228；
      apps/worker/src/main/java/com/riccon/worker/workflow/tool/service/WorkflowToolApprovalVerifier.java:35,92-95；
      apps/api/src/main/java/com/riccon/api/workflow/validation/WorkflowToolAvailability.java:65-67；
      REV-TASK-0227-001 F-001/F-002；REV-TASK-0237-001 F-002。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/lead（角色口径）；riccon/builder-b3（UI 兜底）"
    due: "M06b gate 前"
    title: "WORKFLOW_EDITOR 单独角色缺 department.read，部门负责人选择“本部门”发布范围仅靠 UI 告警"
    summary: >-
      TASK-0247 F-002 仍未关闭：`WORKFLOW_EDITOR_PERMISSIONS` 不含 `DEPARTMENT_READ`，仅有
      WORKFLOW_EDITOR 的部门负责人在编辑页无法读取部门目录，页面已显式告警且不会静默回退
      ONLY_SELF；默认人物 MEMBER+WORKFLOW_EDITOR 正常。astral 副本名字符边界已由
      `limits.test.ts`/`WorkflowApprovalAuditCommentPolicyTest` 覆盖，不再是缺口。
    accepted_reason: >-
      默认授权组合包含 MEMBER（含 department.read），单独 WORKFLOW_EDITOR 是边界角色；影响是
      UX/角色口径而非越权发布或数据泄露。作为 P3 accepted，由 Lead 在 M06b 前决定补
      department.read 或只读部门兜底。
    evidence: >-
      platform/platform-domain/src/main/java/com/riccon/platform/domain/identity/RoleDefinitions.java:44-60,134-135；
      apps/web/src/pages/workflow/edit/index.tsx:662,1703；REV-TASK-0247-001 F-002；
      apps/web/src/pages/workflow/edit/limits.test.ts:28-29。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder-b1（测试）；riccon/verifier（复跑）"
    due: "M06b 前硬化"
    title: "WorkflowRunHistoryConcurrencyApiTest cancel/decide 竞态断言 flake：cancel 硬期望 202，decide 先赢时实际 409"
    summary: >-
      T17 第一次全量（EV-1153）在 1040 tests/0 errors 下仅 1 failure：
      `WorkflowRunHistoryConcurrencyApiTest.concurrentCancelAndDecide_consistentOutcome:120`
      期望 cancel 202，decide 先赢使运行进入终态后实际 409。单类复跑 3/3 通过；T17 第二次全量
      见 EV-1155。测试本身已允许 decide 200/409，但 cancel 202 是硬断言，属于测试时序缺陷，
      不是 M06 功能/安全失败。
    accepted_reason: >-
      产品语义允许“运行已终态时 cancel 409”，该 failure 是测试期望过窄；单类复跑与全量复跑可
      恢复绿色。作为 P3 accepted，由 builder-b1/verifier 在 M06b 前把 cancel 期望放宽为 202/409
      或加同步闸门，禁止用重试参数掩盖。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryConcurrencyApiTest.java:85-141；
      EV-1153（exit 1，1 failure/1040 tests）；单类复跑 surefire 3/0/0/0（09:10:50）；EV-1155。
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
      演练已证明旧 Worker 不领取、运行计数 0、历史只读、schema 15。作为 P3 accepted，由 ops 在
      M09/release 生产演练前补 TRIGGER 读取、J1/J2 冒烟与签名镜像校验。
    evidence: >-
      .riccon/evidence/TASK-0238/workflow-rollback-drill.json；scripts/ops/workflow-rollback-drill.mjs:588-610,706-715；
      docs/RUNBOOK.md M06-8/M06-12；apps/api/src/test/java/com/riccon/api/migration/V15MigrationTest.java:198-207；
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:38-46；REV-TASK-0238-001 F-001..F-004。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-b3（Web）；riccon/lead（验收口径）"
    due: "M06b gate 前"
    title: "AC-14 只显示每条运行“保留到期”，未显式显示“实际生效的保留值（180 天/租户覆盖）”"
    summary: >-
      M06-AC-14 要求运行历史/详情显示实际生效的保留值与每条运行到期时间。运行详情已显示
      “保留到期”时间（run-detail.tsx:227-229，E2E-M06-08 断言），但 Web 没有显示“保留 180 天”或
      租户实际保留天数；`/workflows/runtime-limits` 未在 Web 使用（见 F-001）。到期不可见、
      无在线删除、审批保留过滤由 IT-WF-107/T08 覆盖。
    accepted_reason: >-
      每条运行的到期时间已可见，足以判断可见窗口；缺的是把部署/租户保留天数明示，属于合规文案
      完整性 residual，不影响到期过滤/删除边界。作为 P3 accepted，与 F-001 一起在 M06b/用户最终
      验收前补限制/保留值展示。
    evidence: >-
      docs/PRODUCT.md:831；apps/web/src/pages/workflow/run/run-detail.tsx:227-229；
      e2e/real/tests/m06-studio.spec.ts:519-522；apps/api/src/test/java/com/riccon/api/workflow/history/WorkflowRunHistoryRetentionApiTest.java:22。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/architect（ADR/ARCH 状态）"
    due: "M06 gate 后 closeout"
    title: "ADR-0024 仍 proposed、ARCHITECTURE 仍写“M06 设计版本尚未实现”，实现/收口后需 Architect 转状态"
    summary: >-
      ADR-0024 头部与 ARCHITECTURE.md:555 仍保留“proposed/尚未实现”的设计态文字；实现、任务评审、
      模块 gate 已完成。文档状态转换属 Architect/Lead，不在 Verifier 写范围。技术内容
      （TM-72..TM-85、V15、回滚、限制、AC 映射）与实现一致，未发现相反断言。
    accepted_reason: >-
      设计文档状态字段不改变 M06 行为与证据；按项目流程由 Architect 在模块收口后转 accepted。
      作为 P3 accepted，避免 Verifier 越权修改 docs/ARCHITECTURE/ADR。
    evidence: >-
      docs/adr/ADR-0024-m06-workflow-orchestration.md:1-15；docs/ARCHITECTURE.md:7,41-52,555。
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/architect（THREAT-MODEL 命令）；riccon/verifier（核对方式）"
    due: "M06b gate 前或下一次 THREAT-MODEL 修订"
    title: 'THREAT-MODEL §8 的 `grep -q "TEN-M06-01" apps/api/target/surefire-reports/*.xml` 在 Surefire 3.5.6/JUnit 6 下失效：XML testcase 名是方法名，不含 @DisplayName ID'
    summary: >-
      本评审在 EV-1155 的 surefire XML 上执行 THREAT-MODEL §8 的命名用例 grep 命令，
      `TEN-M06-01`/`DEPT-M06-01`/`IT-WF-101..112`/`SEC-WF-*` 均不出现；XML 的 testcase name 是
      方法名（例如 `tenM0601_crossTenantAndPlatformAreInvisible`），`@DisplayName` 文本未写入。
      独立核对改用“源码 @DisplayName/类 → 方法名 → surefire 报告 0 failure/0 skip”的映射：
      WorkflowTenantIsolationTest 5/0/0、WorkflowDepartmentIsolationTest 2/0/0、
      WorkflowAuditM06Test 3/0/0、IT-WF-101..112 对应类均 0 failure/0 skip；测试真实存在并执行，
      文档中的 grep 命令与当前报告格式不一致。
    accepted_reason: >-
      命名用例本身存在且通过，B-28/B-30 的关闭条件可由类/方法映射与套件完整性测试证明；失效的
      是 THREAT-MODEL 的机械 grep 命令，不是产品测试。作为 P3 accepted，由 Architect 在 M06b/
      下次威胁模型修订时把命令改为按类/方法或增加 surefire `includeDisplayName`，Verifier 在
      release 前复查。
    evidence: >-
      docs/security/THREAT-MODEL.md:826-833；apps/api/target/surefire-reports/TEST-com.riccon.api.security.WorkflowTenantIsolationTest.xml（方法名）；
      apps/api/src/test/java/com/riccon/api/security/WorkflowTenantIsolationTest.java:52；
      apps/api/src/test/java/com/riccon/api/security/WorkflowDepartmentIsolationTest.java:61；
      apps/api/src/test/java/com/riccon/api/workflow/audit/WorkflowAuditM06Test.java；EV-1155。
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
evidence:
  - EV-1152
  - EV-1155
  - EV-1156
  - EV-1157
  - EV-1158
  - EV-1159
reviewed_evidence:
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
  - EV-1149
  - EV-1151
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
  - EV-1152
  - EV-1155
  - EV-1156
  - EV-1157
  - EV-1158
  - EV-1159
  - EV-1146
  - EV-1147
  - EV-1148
  - EV-1150
notes: >-
  M06 模块评审在最终提交 5094cb0b 上重跑合同 build/test+coverage/e2e；EV-1155 为 external 热栈
  + t17 namespace 的合同 test/coverage（api 1040、worker 924、common 87、domain 99、ArchUnit 12，
  全 0/0/0 skip；web coverage 87.59/80.62/85.39/87.5），EV-1152 为合同 build，EV-1156 为合同
  stub E2E + 真实栈 full；a11y/perf/安全复用 T16a/T16b clean EV（EV-1146..1151）并在最终提交
  重跑真实栈 E2E。M06b/M26 不在本评审范围。
created_at: "2026-11-26T10:15:00+08:00"
---

# REV-M06-001：M06 编排 Studio 模块评审

## 结论

**通过（pass，with residual）**。M06-AC-01..10、12..24（23 条）在最终提交 `5094cb0b` 上逐条
对账通过；合同 build、test+coverage（同一条全量命令与 EV）、compose 合同 E2E + 真实栈 E2E、a11y、
perf、V15 迁移/回滚、越权/审计与威胁模型 B-27..B-30 均有 clean 证据。开放项均为 P2/P3
residual：AC-17 租户限制界面/超限具体数值（F-001）、U-05（F-002）、本机 Testcontainers 主机
解析（F-003）以及 T07/T12/T09/T16b 残余（F-004..F-009）；无 open P0/P1。

本评审是独立技术验收，不是 Product 预验收或用户最终验收。`module.product_acceptance` 仍为
pending；Lead 在本评审后运行 `gate check --module M06`，再由 Product 预验收、用户最终验收。

## 验证了什么

### 合同命令与证据（最终提交 5094cb0b，工作树 clean）

| DoD / 审查项       | 命令（与合同对应）                                                                                                                                                                                                                | 结果                                                                                                                                                                                                                                                              | EV               |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| build              | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                                                                                                                             | exit 0                                                                                                                                                                                                                                                            | EV-1152          |
| test + coverage    | `RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=t17 TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`（包含合同子串；默认 Testcontainers 两次失败见 F-003） | exit 0；api 1040、worker 924、platform-common 87、platform-domain 99、ArchUnit 12，全 0 failure/0 error/0 skip；API line 92.21/branch 74.07、worker 87.35/71.89、common 90.83/75.41、domain 97.33；web statements 87.59/branches 80.62/functions 85.39/lines 87.5 | EV-1155          |
| E2E                | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e && REAL_EVIDENCE_TASK=TASK-0239-e2e node scripts/real-stack/run.mjs --mode=full`                                                            | 合同 stub E2E 63 passed；真实栈 41 passed（E2E-M06-01..10、E2E-M06-09a/b/c @a11y、J1/J2/M10/M04 回归、m03Checks/securityChecks/j2Journey/m04Checks 全 passed）                                                                                                    | EV-1156          |
| coverage           | 与 test 同一条 EV（禁止跑两遍）                                                                                                                                                                                                   | line/branch 门槛由 jacoco:check 与 vitest thresholds 强制                                                                                                                                                                                                         | EV-1155          |
| a11y               | T16a EV-1146/EV-1148                                                                                                                                                                                                              | axe 无 serious/critical；375px 无横向滚动；键盘全流程通过                                                                                                                                                                                                         | EV-1146、EV-1148 |
| perf               | T16a EV-1147（等价 `commands.perf` harness）                                                                                                                                                                                      | J1 首字 p95 50.1ms、Studio 读 API p95 141ms、运行准入写路径 p95 2557ms（DEC-0049=A ≤3s）、run_ok 100%、50 VU 混合                                                                                                                                                 | EV-1147          |
| security           | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`                                                                                                          | exit 0（见 EV-1157；B-06 例外仍按 ADR-0011 跟踪）                                                                                                                                                                                                                 | EV-1157          |
| migration/rollback | V15 迁移测试 + T16b 演练                                                                                                                                                                                                          | V9–V15 迁移测试全过；OPS-M06-01 27 assertions、0 failed                                                                                                                                                                                                           | EV-1155、EV-1150 |
| docs               | RUNBOOK M06-0..12、ADR-0024、THREAT-MODEL M06、PRODUCT AC 核对 + `contract validate --strict`                                                                                                                                     | exit 0；ADR 状态转换归 Architect                                                                                                                                                                                                                                  | EV-1158          |
| typecheck + lint   | `pnpm -w typecheck && pnpm -w lint`                                                                                                                                                                                               | exit 0（非模块 gate key，作为质量基线）                                                                                                                                                                                                                           | EV-1159          |

### 回归

- J1/J2：contract stub E2E + 真实栈 `j1.spec/j2.spec` + E2E-M06-10（J1 问答、J2 入口/阶段/产物不变）。
- M03/M10：真实栈 `m03Checks`、`m10.spec`（OIDC/审计/导出）与 stub `m03.spec/m10.spec`。
- M04/M05/M25：真实栈 `m04-*` 与 stub `m05.spec/m05b.spec`；M05 文档 ACL/引用遮蔽、M25 连接器
  端点/凭据/同步回归未发现失败。
- 越权/审计：EV-1149（TEN-M06-01、DEPT-M06-01、IT-AUDIT-M06-01、WorkflowValidationNegativeMatrix）
  0 failure/0 skip；全量 test 中 `TEN/DEPT/SEC/AUDIT` 命名用例零 skip。

### 威胁模型 B-27..B-30

| 阻断项                                                        | 结论                       | 证据                                                                                                                         |
| ------------------------------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| B-27 代码/脚本/HTTP/并行/循环/定时入口与工具白名单/写工具审批 | pass                       | SEC-WF-004/005、IT-WF-104/111；`WorkflowNodeType` 7 类封闭枚举、条件算子 7 类、无表达式/脚本；UI 无人为入口                  |
| B-28 权限/租户/部门 403/404、平台管理员、自批                 | pass                       | SEC-WF-001/002/003/006、TEN-M06-01、DEPT-M06-01、IT-WF-112；全量 0 skip；`WorkflowPlatformTenantFilter` 对 platform 统一 404 |
| B-29 资源上限/并发/结果大小/超时/kill switch/回滚             | pass (with residual F-007) | IT-WF-109/110、PERF-M06-001、OPS-M06-01；演练缺口见 F-007                                                                    |
| B-30 威胁模型/审计白名单/注入/审批摘要 ACL                    | pass                       | THREAT-MODEL §4.13/§5.12/§6；`AuditDetailsPolicy` workflow 动作写入/读取白名单；SEC-WF-004、IT-WF-106/108、IT-AUDIT-M06-01   |

## AC 对账（M06-AC-01..24；AC-11/25 归 M06b）

| AC    | 用户可见 Then（摘要）                                                                                                                                  | 结果                                   | 证据（测试 ID / 报告）                                                                 |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------- | -------------------------------------------------------------------------------------- |
| AC-01 | 草稿出现在“我的流程”、重开一致、未发布不可运行、并发保存 409 可另存副本                                                                                | pass                                   | E2E-M06-01（real-stack-playwright.log）、IT-WF-101                                     |
| AC-02 | 校验逐项指出节点/原因/修正入口，发布被阻止；修正后通过且画布/表单一致                                                                                  | pass                                   | E2E-M06-01、IT-WF-101、WorkflowValidatorTest/IT-WF-111                                 |
| AC-03 | 发布不可变版本、版本号/发布人/时间/范围可见；运行绑定版本；旧版本仍可运行                                                                              | pass                                   | E2E-M06-02、IT-WF-102；V15 trigger 测试                                                |
| AC-04 | 运行时间线状态/耗时/当前节点高亮；刷新一致；结果摘要不泄露凭据/提示词                                                                                  | pass                                   | E2E-M06-03                                                                             |
| AC-05 | 审批待办只显示自己、按时间升序、详情脱敏；批准继续、拒绝终止且理由必填                                                                                 | pass                                   | E2E-M06-04、SEC-WF-001、IT-WF-108                                                      |
| AC-06 | 禁自批 403+审计；范围外/停用/他租户不泄露；无审批人不自动放行                                                                                          | pass                                   | SEC-WF-001、E2E-M06-04                                                                 |
| AC-07 | A 成功/B 失败/C 跳过，整体失败；重试从 B 继续不重跑 A                                                                                                  | pass                                   | E2E-M06-05、IT-WF-103                                                                  |
| AC-08 | 重复运行/重试不重复执行/追加/建产物；写工具重试重新审批；未知副作用默认不自动重试                                                                      | pass                                   | IT-WF-104、E2E-M06-05                                                                  |
| AC-09 | 条件只走一支、另一支跳过；无法求值失败不默认分支                                                                                                       | pass                                   | E2E-M06-06、IT-WF-105                                                                  |
| AC-10 | 内置市场调研模板复制为当前用户草稿、模板不被修改、填写参数后发布运行产出带引用结果                                                                     | pass                                   | E2E-M06-07                                                                             |
| AC-11 | M06b（租户内模板共享/私有模板 404/审计）                                                                                                               | not in M06 gate                        | 归 M06b AC-11/25                                                                       |
| AC-12 | 发起人/所有者/管理员可见范围、倒序分页、过滤/空态；越权统一 404                                                                                        | pass                                   | E2E-M06-08、SEC-WF-003                                                                 |
| AC-13 | 时间线/输入输出摘要/引用/审批/失败/重试；引用遵守 M05 ACL/sourceStatus；不泄露 prompt/参数/正文                                                        | pass                                   | E2E-M06-08、IT-WF-106                                                                  |
| AC-14 | 默认 180 天、租户策略优先、到期不可见、无在线删除                                                                                                      | **pass (with residual F-008)**         | IT-WF-107、E2E-M06-08（“保留到期”）；审批到期过滤已由 T08 覆盖                         |
| AC-15 | 角色/部门/租户/平台管理员 403/404 矩阵；取消/停用/启用/授权/移交权限与审计                                                                             | pass                                   | SEC-WF-002/003/006、TEN-M06-01、DEPT-M06-01、IT-WF-112                                 |
| AC-16 | 发布/运行/取消/审批/停用/授权/移交审计可查；管理读取审计；审计失败业务失败；不记 prompt/参数/正文                                                      | **pass (with residual F-002 U-05)**    | IT-WF-108/112、IT-AUDIT-M06-01；U-05 见 F-002                                          |
| AC-17 | 节点/步数/超时/并发/5 MiB 拒绝且提示具体限制/当前值/下一步；调整对新请求生效；租户只读查看；限制值界面可见                                             | **pass (with residual F-001)**         | IT-WF-109、E2E-M06-10（limits API）、run-detail 5 MiB 文案；缺只读限制界面/具体数值    |
| AC-18 | 未授权/停用工具/越权文档拒绝且不调用；写/高风险工具审批；写工具重试重审批；注入不改变图/工具/不泄露 canary；审批摘要 ACL                               | **pass (with residual F-004)**         | SEC-WF-004、E2E-M06-04、IT-WF-104/105                                                  |
| AC-19 | 五界面 loading/empty/error/无权限/禁用/成功态中文说明；375px 无横向滚动；键盘焦点可见                                                                  | pass                                   | E2E-M06-09a/b、web 组件测试（503/空态/错误态）                                         |
| AC-20 | 键盘完成模板→节点/连线→校验→保存→发布→运行→审批→详情；等价列表/表单；axe 无 serious/critical；375px                                                    | pass                                   | E2E-M06-09c、E2E-M06-09a/b                                                             |
| AC-21 | J1 首字 ≤3s、Studio 读 API ≤500ms、运行准入写路径 ≤3s（DEC-0049=A）；50 并发无重复/状态丢失/审批错配；超限稳定提示                                     | **pass (with residual R-M06-PERF-01)** | EV-1147（p95 50.1ms/141ms/2557ms；run_ok 100%）                                        |
| AC-22 | J1/J2/M03/M10/M04/M05/M25 无回归；M06 端点真实实现；M06 动作可在 M10 审计按对象/时间查到                                                               | pass                                   | E2E-M06-10、contract stub E2E、真实栈 m03Checks/m04/m10/j1/j2、WorkflowContractM06Test |
| AC-23 | 关闭编排入口新运行不受理、J1/J2 可用、历史只读、关闭审计；取消保留结果；关开关+回滚 Worker、历史/审计可读；有数据不支持 API 回滚；威胁模型 gate 前更新 | **pass (with residual F-007)**         | IT-WF-110、EV-1150（OPS-M06-01 27 assertions）、RUNBOOK M06-8/9/12、THREAT §7/§8       |
| AC-24 | 无代码/脚本/HTTP/并行/循环/子流程/定时入口；平台管理员 404；文案不声称已支持                                                                           | pass                                   | SEC-WF-005、IT-WF-111、WorkflowValidationNegativeMatrix；UI/grep 无相反文案            |

## Findings

见 front matter F-001..F-010。无 P0/P1；P2 为 AC-17 限制界面（F-001）、U-05（F-002）、
Testcontainers 主机解析/就绪竞态（F-003）。P3 为 T07/T12/T09/T16b 残余、文档状态与
THREAT 命名用例 grep 工具（F-004..F-010）。

## 剩余风险（Residual）

- **R-M06-PERF-01**：运行准入写路径 p95 2588.6ms（A′ 修复前 3394.4ms），DEC-0049=A 已接受
  ≤3s 口径；owner=Lead/Architect，due=release 前跟踪，M06b/M09 可再优化。EV-1143（旧 500ms
  阈值 exit 1）、EV-1147（新口径 p95 2557ms pass）。
- **U-05**：审批理由入审计 PII；owner=安全评审/Product，due=M06 gate/用户最终验收前；见 F-002。
- **T16b R1/R2**：触发器计数测量权限与旧 Worker digest 来源；owner=Ops，due=M09/release 演练前；
  见 F-007。
- **T09/T12/T07 残余**：T09 F-001 审批保留期已由 T08 `0511afd3` 在查询层补上（`retention_expires_at`
  条件），F-003 待办 N+1 在 EV-1147 默认 size 下 p95 141ms；F-004 cancel/decide 测试竞态见 F-006；
  T12 F-002 department.read 见 F-005；T07 F-001/F-002 见 F-004。均 P3 accepted。
- **AC-14/F-008**：保留到期时间已显示，保留天数未显式展示；P3 accepted。
- **M06b/M26**：模板库/共享/升级提示不在 M06 范围；不得把 M06 证据用于 M06b gate。

## 未验证

- Product 预验收与用户最终验收：`module.product_acceptance` 仍 pending，本评审不代替。
- 生产 K8s/Helm、备份恢复、镜像签名与客户真实容量：M09/release。
- M06b 模板共享/升级：AC-11/25。
- U-05 的安全/Product 二选一：未发生。

## Lead 下一步

1. 运行 `gate check --module M06`；若 gate 失败，按本 review findings 修复/接受，不降低标准。
2. 让 Product 做预验收；F-001/F-002/F-008 必须由 Product 显式接受或转 fix 任务。
3. 用户最终验收后再启动 M06b/M26；本 review 不批准 M06b。
