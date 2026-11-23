---
review_id: REV-TASK-0166-002
module_id: M05
task_id: TASK-0166
reviewer:
  agent: riccon/verifier
  session: ses_df9a7ec2affefiTzIX6zXpfKTs
  model: "opencode-go/deepseek-v4.1-flash#max"
  independent: true
verdict: pass
reviewed_commits:
  - 7829dd37d0e259c9e8d766d827d4d8c1d7e7c1a0
  - 96d0111b0a5d48445e3b635ff793238cd7f5421f
  - e24a4edd1aeba215f94eda73cffd8d8dc545c322
  - dbef7ba5107734f06da14ff5fd6061b9bcdf1c0e
  - ab79182afe0f0bbd6fcfd38edea9c409b5715a1b
head_commit: ab79182afe0f0bbd6fcfd38edea9c409b5715a1b
previous_review: REV-TASK-0166-001
evidence:
  - EV-0821
  - EV-0831
  - EV-0838
reviewed_evidence:
  - EV-0821
  - EV-0831
  - EV-0838
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-kb-acl"
    due: "fixed in ab79182"
    title: "生产检索链路已贯通 actor/unrestricted：chat/report/knowledge_search 均传入 run 用户"
    summary: >-
      `ChatAgentService.prepare`（81）与 `ResearchAgentService`（72、79、128、137）已把 `userId`/`runId`
      传给 `KnowledgeRetriever.retrieve/retrieveKnowledge`，`KnowledgeSearchTool`（106）用构造时注入的
      actor/run 调用检索；报告 outbox 处理器也改为 `run.userId()`（AgentRunRequestedHandler:236）。
      `KnowledgeRetriever.searchKnowledge`（121-134）通过 `AgentContextRepository.isKnowledgeAclManager`
      服务端查询角色权限得到 `unrestricted`，再调用带 actor 的 `AgentContextJdbc.search`；`AgentContextJdbc`
      的谓词（396-410）按 TENANT/DEPT/USERS/创建人/ACL 分支执行，管理性读取只由服务端权限决定。
      测试：`KnowledgeWiringTest` 2 用例断言 chat/report wiring；`AgentContextJdbcAclTest` 的 retriever/tool
      用例用真实 MySQL 验证 DEPT/USERS 授权与未授权；`KnowledgeRetrieverIntegrationTest` 验证关闭开关。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/service/ChatAgentService.java:81、106；
      apps/worker/src/main/java/com/riccon/worker/agent/service/ResearchAgentService.java:72、79、128、137；
      apps/worker/src/main/java/com/riccon/worker/agent/service/KnowledgeSearchTool.java:106；
      apps/worker/src/main/java/com/riccon/worker/agent/service/KnowledgeRetriever.java:108-168；
      apps/worker/src/main/java/com/riccon/worker/agent/repository/AgentContextRepository.java:40-46、49-64；
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:321-410；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:236；
      apps/worker/src/test/java/com/riccon/worker/agent/KnowledgeWiringTest.java:40-82；
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextJdbcAclTest.java:79-244。
  - id: F-002
    severity: P1
    status: fixed
    owner: "riccon/builder-kb-acl"
    due: "fixed in ab79182"
    title: "U8 admin_read(scope=SEARCH) 已有写入方、details 与 fail-closed"
    summary: >-
      `KnowledgeRetriever.auditHiddenHits`（147-168）在管理员检索命中文档但按 ACL 不可见时，按文档去重写
      `KnowledgeSearchAudit`；details 含 scope=SEARCH、documentId、visibility、aclUserCount、
      resultCount=hits.size、reason=MANAGEMENT_READ，不含标题/片段/正文。worker `AuditService`
      （435-461）与 worker `AuditDetailsPolicy`（116-150）校验 scope/visibility/documentId/resultCount/reason。
      测试：`AgentContextJdbcAclTest.adminSearch_writesSearchAudit` 断言 details 且不含标题/正文；
      `normalSearch_writesNoSearchAudit` 断言普通用户不写；`KnowledgeSearchAuditFailClosedTest` 断言
      audit 写入失败时检索直接抛错。审计失败会沿 `ChatAgentService/ResearchAgentService` 传播并使运行失败。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/service/KnowledgeRetriever.java:121-168；
      apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java:424-461；
      apps/worker/src/main/java/com/riccon/worker/audit/domain/AuditDetailsPolicy.java:116-150；
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextJdbcAclTest.java:222-286；
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/KnowledgeSearchAuditFailClosedTest.java:35-66。
  - id: F-003
    severity: P1
    status: fixed
    owner: "riccon/builder-kb-acl + riccon/architect"
    due: "fixed in ab79182"
    title: "reindex 已增加 knowledge.document.reindexed 审计，先审计后 outbox，失败回滚"
    summary: >-
      `AuditActions` 新增 `knowledge.document.reindexed`；API `AuditDetailsPolicy` 白名单为
      documentId/force/status，并强制 documentId/force/status 必填、status 属于 KnowledgeDocumentStatus；
      `KnowledgeAuditEvents.reindexed` 构造事件；`KnowledgeDocumentLifecycleService.reindex`（167-169）
      在 `nextIndexAttempt` 和 outbox enqueue 之前写审计，异常回滚。测试覆盖正常审计（重索引测试检查
      force/status）、审计失败不投递新 index 事件（KnowledgeAuditFailClosedTest）和未知键拒绝
      （KnowledgeAuditPolicyTest）。
    evidence: >-
      platform/platform-domain/src/main/java/com/riccon/platform/domain/audit/AuditActions.java:118-120；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:202、345、433-470；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeAuditEvents.java:177-216；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDocumentLifecycleService.java:167-185；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDeleteReindexApiTest.java:112-122；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeAuditFailClosedTest.java:95-118；
      apps/api/src/test/java/com/riccon/api/audit/KnowledgeAuditPolicyTest.java:43-77。
  - id: F-004
    severity: P1
    status: fixed
    owner: "riccon/builder-kb-acl"
    due: "fixed in ab79182"
    title: "历史 NULL retention 行固定按 created_at + 180 天，不随租户策略调低追溯"
    summary: >-
      `KnowledgeRetentionProcessor.expireTenant` 用 `DEFAULT_RETENTION_DAYS`（180）计算 NULL 行的
      createdCutoff，不再传当前租户策略；`KnowledgeDocumentRecord.retentionPolicy/retentionDays` 对 NULL
      行返回 DEPLOYMENT_DEFAULT/180；新建文档仍按租户策略写显式 retention_expires_at。
      `KnowledgeRetentionTest.retention_historicalNullUsesDeploymentDefault` 设置租户 90 天，验证历史 150 天
      保留、历史 181 天到期、响应 180/DEPLOYMENT_DEFAULT，同时新文档仍 90/TENANT_POLICY。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:823-824；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeRetentionProcessor.java:84-91；
      apps/api/src/main/java/com/riccon/api/knowledge/domain/KnowledgeDocumentRecord.java:105-126；
      apps/api/src/main/java/com/riccon/api/knowledge/persistence/KnowledgeRetentionJdbc.java:29-38；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeRetentionJob.java:44-53；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeRetentionTest.java:72-106。
  - id: F-005
    severity: P2
    status: fixed
    owner: "riccon/builder-kb-acl"
    due: "fixed in ab79182"
    title: "Worker 已读取 RICCON_KB_ACL_ENFORCEMENT_ENABLED，API/Worker 灰度语义一致"
    summary: >-
      `AgentContextJdbc` 通过 `@Value("${riccon.kb.acl.enforcement-enabled:false}")` 注入开关；
      `appendAclPredicate` 在 `!enforcementEnabled` 时退化为租户谓词。`KnowledgeRetrieverIntegrationTest`
      的 `retrieve_whenAclEnforcementDisabled_ignoresDocumentAcl` 验证关闭时 DEPT 文档仍可检索，真实开启场景由
      `AgentContextJdbcAclTest` 的 `@TestPropertySource` 覆盖。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:49-58、396-410；
      apps/worker/src/test/java/com/riccon/worker/agent/KnowledgeRetrieverIntegrationTest.java:104-132；
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextJdbcAclTest.java:31。
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/architect + riccon/builder-kb-contract"
    due: "M05 gate 前"
    accepted_reason: >-
      运行时行为已由下载测试覆盖且不泄露 key；在本任务目标提交 ab79182 上契约 502/Content-Type 精度仍属
      B2 F-003 的既有接受项，B3 写范围不含 contracts。评审期间主树后续提交 `7ad975d` 已把下载 502/
      Content-Type 契约对齐（不在本次 reviewed_commits 内），由模块契约测试复核即可，不需回退本任务。
    title: "下载 502 稳定码与动态 Content-Type 在目标提交 ab79182 上仍是契约残留"
    summary: >-
      目标提交实现返回 `RICCON-KB-502` 和文档 mimeType 动态 Content-Type，但契约 200 仍只声明 octet-stream、
      502 仍引用通用 BadGateway；与 REV-TASK-0166-001 F-006 相同，非本次修复引入。评审期间后续
      `7ad975d` 已修改 contracts/openapi.yaml 与 KnowledgeContractM05Test，Lead 在模块评审时复核该后续提交。
    evidence: >-
      contracts/openapi.yaml:1168-1180（ab79182）；后续 `7ad975d` 修改
      contracts/openapi.yaml、contracts/README.md、KnowledgeContractM05Test；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadFailedException.java:6-21；
      apps/api/src/main/java/com/riccon/api/knowledge/controller/KnowledgeController.java:239-245；
      .riccon/reviews/tasks/TASK-0165-review.md F-003。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      EV-0831 与 EV-0838 内容真实、绑定 ab79182、clean 且日志哈希/surefire 报告可复核；worktree 例外是主树被
      B4/B5 占用时的既定流程。回灌时 EV-0836 已被 TASK-0191 占用，本评审将 verifier EV 重编号为 EV-0838；
      随后 `9c7c3f4`（TASK-0192 closeout）把 EV-0838 JSON/日志一并提交，但未挂入 TASK-0166.evidence。
      需 Lead 统一审计全局 ID、提交归因和任务证据挂载；不影响 EV 内容与本次判定。
    title: "EV-0831/EV-0838 在隔离 worktree 录制；EV-0836 全局 ID 冲突后重编号 EV-0838，随后被 TASK-0192 提交夹带"
    summary: >-
      EV-0831：builder worktree `/var/folders/.../riccon-b3-fix-wt`，HEAD ab79182，dirty=false，API 138 类/
      732 用例、worker 5 类/23 用例，0 fail/error/skip。EV-0838：verifier 独立 worktree
      `/var/folders/.../riccon-b3-review-wt`，HEAD ab79182，dirty=false，API 28 用例、worker 23 用例。
      主树仍被 B5 WIP 占用；回灌 EV-0838 时发现 EV-0836 已被 TASK-0191 使用，故重编号。随后另一个任务的
      closeout 提交 `9c7c3f4` 把 EV-0838 文件一起提交（提交信息为 TASK-0192），但 TASK-0166.evidence 仍只列
      EV-0821/EV-0831。EV-0822/0823/EV-0834/EV-0835 不作为通过证据。
    evidence: >-
      .riccon/evidence/TASK-0166/EV-0821.json；.riccon/evidence/TASK-0166/EV-0831.json；
      .riccon/evidence/TASK-0166/EV-0838.json；`verifyEvidenceRecord` 对三者均返回 ok=true；
      `git show --stat 9c7c3f4` 显示 EV-0838 被 TASK-0192 closeout 夹带；
      `/var/folders/.../riccon-b3-fix-wt/apps/{api,worker}/target/surefire-reports`。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-acl + riccon/verifier（M05-V1/V2）"
    due: "M05 gate 前"
    accepted_reason: >-
      新增测试已补部门移出、200 人上限、软删除/平台管理员下载、平台管理员 reindex、历史 NULL、SEARCH、
      reindex 审计等关键边界；剩余缺口是平台管理员 delete、ACL 收紧后的下载/检索下一次请求、以及
      ChatAgentService/ResearchAgentService 的完整模型运行级集成（当前为 wiring 单测 + 真实 DB retriever）。
      这些是 V1/V2 的补充覆盖，不影响本次修复的正确性判断。
    title: "剩余边界矩阵缺口：平台管理员 delete、ACL 收紧后的下载/检索、完整 Agent 运行级集成"
    summary: >-
      `KnowledgeAclApiTest` 已补成员移出与 200 人通过；`KnowledgeDownloadApiTest` 已补软删除/平台管理员；
      `KnowledgeDeleteReindexApiTest` 已补平台管理员 reindex；`KnowledgeRetentionTest` 已补历史 NULL；
      `AgentContextJdbcAclTest`/`KnowledgeSearchAuditFailClosedTest` 覆盖检索和 SEARCH。仍建议 M05-V1/V2
      增加平台管理员 delete、ACL 变更后的 download/search 下一请求、真实 Agent 运行级审计断言。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeAclApiTest.java:139-147、202-225；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDownloadApiTest.java:70-96；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDeleteReindexApiTest.java:165-174；
      apps/worker/src/test/java/com/riccon/worker/agent/KnowledgeWiringTest.java:40-82。
  - id: F-009
    severity: P3
    status: fixed
    owner: "riccon/builder-kb-core（B1 既有 F-014）"
    due: "fixed in ab79182"
    title: "AuditDetailsPolicy 已强制非 LIST admin_read.visibility，B1 F-014 收敛"
    summary: >-
      `AuditDetailsPolicy.validateRequiredDetails` 对非 LIST 的 admin_read 增加 visibility 必填；
      `KnowledgeAuditPolicyTest` 验证 LIST 可空、DETAIL 缺 visibility 拒绝、SEARCH 合法。B3 writer 的
      既有兜底仍在。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:464-468；
      apps/api/src/test/java/com/riccon/api/audit/KnowledgeAuditPolicyTest.java:20-46；
      .riccon/reviews/tasks/TASK-0164-review.md F-014。
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-acl"
    due: "M05 gate 前"
    accepted_reason: >-
      纯注释/可读性债务；已确认新方法签名和测试通过，不影响行为、安全边界或证据。作为 M05 gate 前的
      代码卫生项跟踪，不阻断本次任务评审。
    title: "两处注释漂移/重复 Javadoc：AgentContextJdbcAclTest 仍描述旧 wiring，AuditService 有重复块"
    summary: >-
      `AgentContextJdbcAclTest` 类注释仍写“生产 chat/report 调用方需要把 run 用户传入新重载”，已被 ab79182
      修复；`AuditService` 410-424 的旧 `append` Javadoc 与 424-434 的 `knowledgeAdminSearch` Javadoc 相邻，
      形成重复块。建议 M05 gate 前清理，避免后续读者误判 wiring 状态。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextJdbcAclTest.java:28-32；
      apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java:410-434。
created_at: "2026-11-23T12:09:48+08:00"
---

# REV-TASK-0166-002：TASK-0166（M05-B3）复评

## 结论

**pass（任务级复评）**。REV-TASK-0166-001 的 4 条 P1 均已在 `ab79182` 修复：

- **F-001 fixed**：chat/report/knowledge_search 三条生产链路已把 run 用户与 runId 传入 retriever；
  `unrestricted` 由服务端角色权限查询决定；DEPT/USERS 授权用户可检索、未授权不可。
- **F-002 fixed**：U8 `admin_read(scope=SEARCH)` 有写入方、完整 details 与 fail-closed 测试；普通用户不写。
- **F-003 fixed**：`knowledge.document.reindexed` 已进入 AuditActions/Policy/生命周期服务，先审计后 outbox，
  审计失败回滚且不投递。
- **F-004 fixed**：历史 NULL retention 行固定 180 天，租户策略调低不追溯；新文档仍按租户策略。

无新 P0/P1。剩余 P2/P3 为契约精度、worktree/EV 流程和补充测试矩阵，已按 accepted 记录 owner/due；
不阻断任务 done。任务级评审不等于模块 gate：M05 仍是 building，模块 review/V1/V2 未完成。

## 范围与证据

| 项        | 值                                                                                             |
| --------- | ---------------------------------------------------------------------------------------------- |
| baseline  | `9815e13`                                                                                      |
| 修复提交  | `ab79182afe0f0bbd6fcfd38edea9c409b5715a1b`                                                     |
| 初版提交  | `7829dd3`、`96d0111`、`e24a4ed`、`dbef7ba`                                                     |
| 修复 diff | 32 文件，全部在 Lead 扩后的 write_scope；无 B4/B5 文件                                         |
| 权威证据  | EV-0821（初版 worktree，dbef7ba，clean）、EV-0831（修复 worktree，ab79182，clean）             |
| 独立复评  | EV-0838（verifier worktree，ab79182，clean；API 28 + worker 23）                               |
| 未跑      | 全模块 build/test/coverage/E2E/a11y/security/perf；这些属于 M05-V1/V2/gate，不用任务级评审替代 |

`9815e13..ab79182` 32 文件分布：API knowledge 9、API audit 1、API knowledge 测试 6、API audit 测试 1、
worker agent 6、worker audit 2、worker research 1、worker agent 测试 5、platform audit 1；全部命中扩后的
`apps/api/**/knowledge`、`apps/api/**/file/storage`、`apps/worker/**/agent`、`apps/worker/**/audit`、
`apps/worker/**/research`、`apps/api/**/audit`、`apps/worker/src/test/**/agent`、`platform/**/audit` 范围。

### 证据真实性

- **EV-0821**：commit dbef7ba，dirty=false，log sha256 与 JSON 一致；初版 API 全量 137 类/727 用例、
  worker 3 类/14 用例，0 fail/error/skip。
- **EV-0831**：commit ab79182，dirty=false，log sha256 `733a...7cc2` 与 JSON 一致；worktree HEAD
  ab79182，surefire 报告 API **138 类/732 用例**、worker **5 类/23 用例**，0 fail/error/skip。
- **EV-0838**：verifier 在独立 worktree 录制，commit ab79182，dirty=false，log sha256
  `223e...290e`；API **28 用例**、worker **23 用例**，0 fail/error/skip。
- `verifyEvidenceRecord` 对 EV-0821/0831/0838 均返回 `ok=true`（哈希、commit ancestor、clean）。
- **EV-0822/0823/0834/0835 不作为通过证据**：EV-0822/0823 是上次主树 dirty 探测，EV-0834 主树 exit 1，
  EV-0835 主树 dirty；均未列入 `reviewed_evidence`。
- **EV ID 冲突**：回灌时 EV-0836 已被 TASK-0191 占用，verifier 记录重编号为 **EV-0838**；随后 `9c7c3f4`
  （TASK-0192 closeout）把 EV-0838 JSON/日志一并提交，但未挂入 TASK-0166.evidence。本评审已报告 Lead，
  需统一审计全局 ID、提交归因，并决定是否把 EV-0838 挂入 `TASK-0166.evidence`（当前任务证据仍只列
  EV-0821/EV-0831）。

## F-001..F-004 逐条结论

### F-001（fixed）生产检索链路

- `ChatAgentService.prepare` 第 81 行调用 `retriever.retrieve(tenantId, userId, runId, query, fileIds)`；
  第 106 行用 `userId/runId` 构造 `KnowledgeSearchTool`。
- `ResearchAgentService` 两个阶段（72、79、128、137）同样传 actor/run；`AgentRunRequestedHandler:236`
  使用 `run.userId()`（`agent_run.user_id` 为 NOT NULL）。
- `KnowledgeRetriever.searchKnowledge`（121-134）用
  `AgentContextRepository.isKnowledgeAclManager` 服务端查询 `knowledge.acl.manage`，再把 `unrestricted`
  传给 `AgentContextJdbc.search`；客户端不能设置该布尔值。
- `AgentContextJdbc.appendAclPredicate`（396-410）在开启时执行 TENANT/DEPT/USERS/创建人/ACL 谓词；
  `KnowledgeSearchTool`/`KnowledgeRetriever` 使用真实 MySQL 测试。
- 测试断言：`KnowledgeWiringTest` 验证 chat/report wiring；`AgentContextJdbcAclTest.retriever_deptDocument...`
  与 `retriever_usersDocument...` 验证授权/未授权；`searchTool_usesActor` 验证 knowledge_search 工具；
  `KnowledgeRetrieverIntegrationTest` 验证关闭开关退化。EV-0831 worker 23 用例全绿。

### F-002（fixed）U8 SEARCH 审计

- `KnowledgeRetriever.auditHiddenHits` 仅对 `unrestricted=true` 且按 ACL 不可见的命中写
  `scope=SEARCH`；每文档去重；details 含 `documentId`、`visibility`、`aclUserCount`、
  `resultCount=hits.size()`、`reason=MANAGEMENT_READ`，不写标题/片段/正文。
- `AuditService.knowledgeAdminSearch` 写入 `knowledge.document.admin_read`；worker `AuditDetailsPolicy`
  强制 SEARCH、MANAGEMENT_READ、documentId、visibility 枚举、非负 resultCount。
- 测试：管理员命中写审计且不含标题/正文；普通用户不写；审计失败检索抛错（fail-closed）。
- `ChatAgentService`/`ResearchAgentService` 均不吞异常，审计失败会使对应运行失败。

### F-003（fixed）reindex 审计

- `AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED` 新增；API `AuditDetailsPolicy` 允许
  `documentId/force/status`，强制三键与 status 枚举，未知键 fail-closed。
- `KnowledgeAuditEvents.reindexed` 构造 `KNOWLEDGE_DOCUMENT` 事件；`KnowledgeDocumentLifecycleService`
  在 `nextIndexAttempt`/outbox 之前写审计，`@Transactional` 保证失败回滚。
- 测试覆盖正常审计 details、审计失败不新增 index 事件、未知键失败。

### F-004（fixed）历史 NULL retention

- `KnowledgeRetentionProcessor.expireTenant` 固定 `DEFAULT_RETENTION_DAYS=180` 计算 NULL 行 cutoff；
  `KnowledgeDocumentRecord` 对 NULL 返回 180/DEPLOYMENT_DEFAULT；新建文档仍使用租户策略写入显式
  `retention_expires_at`。
- 测试把租户策略调到 90，历史 150 天保留、历史 181 天删除、响应 180/DEPLOYMENT_DEFAULT，新文档
  90/TENANT_POLICY。

## F-005/F-008/F-009 回归

| 项                    | 结论                      | 说明                                                                                                                                                         |
| --------------------- | ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| F-005 worker ACL 开关 | **fixed**                 | `AgentContextJdbc` 注入 `riccon.kb.acl.enforcement-enabled`；关闭时谓词退化；`retrieve_whenAclEnforcementDisabled...` 断言。                                 |
| F-008 边界矩阵        | **部分补强，P3 accepted** | 已补成员移出、200 人上限、软删除/平台管理员下载、平台管理员 reindex、历史 NULL；仍缺平台管理员 delete、ACL 收紧后的 download/search、完整 Agent 运行级集成。 |
| F-009 审计必填        | **fixed**                 | API `AuditDetailsPolicy` 强制非 LIST visibility；`KnowledgeAuditPolicyTest` 覆盖 LIST/DETAIL/SEARCH。                                                        |

## 测试质量与范围

- 所有运行均为真实 MySQL 8.4 / OpenSearch / RustFS Testcontainers；EV-0831/0838 的 surefire 报告
  0 failure/error/skip，没有 skip 或 retry 掩盖。
- 新增测试对旧实现有失败能力：F-001 的 actor 形参在旧代码不存在（编译失败）；F-002/F-003 旧代码无审计
  （断言失败）；F-004 旧实现会把 150 天历史 NULL 文档按租户 90 天删除（测试失败）；F-005 旧 worker 会
  在关闭开关时隐藏 DEPT（测试失败）；F-009 旧策略会接受 DETAIL 缺 visibility（测试失败）。
- 未见新 P0/P1；未见跨租户/跨部门越权放宽；管理员 SEARCH 审计只在服务端权限为真且命中 ACL 不可见文档时写。

## 仍存在的 P2/P3

- **F-006（P2 accepted）**：目标提交 ab79182 的 OpenAPI 仍未登记 `RICCON-KB-502` 与动态 Content-Type；
  评审期间后续 `7ad975d` 已对齐契约，Lead 在模块契约测试/AC-22 时复核即可。
- **F-007（P3 accepted）**：EV-0831/EV-0838 worktree 取证；EV-0836 与 TASK-0191 冲突后重编号 EV-0838，
  随后被 `9c7c3f4`（TASK-0192 closeout）提交夹带；Lead 需统一 ID/归因/挂载审计。
- **F-008（P3 accepted）**：剩余边界矩阵/完整运行级集成。
- **F-010（P3 accepted）**：`AgentContextJdbcAclTest` 旧注释、`AuditService` 重复 Javadoc，代码卫生项。

## 未验证与 gate 口径

- 未跑 M05 模块全量 build/lint/typecheck/coverage/E2E/a11y/security/perf；这些必须由 M05-V1/V2 与模块
  gate 在最终提交上重跑。任务级 `pass` 不能替代模块 gate。
- M05 模块仍 `building`、TASK-0166 仍 `review`、模块 review 未生成；`riccon gate check --module M05`
  现在仍应失败（不是本次任务修复的失败，而是模块未收口）。本评审不修改任务状态。
- EV-0838 已回灌 JSON/日志，但被 `9c7c3f4` 夹带提交且未挂入 `TASK-0166.evidence`；Lead 若要求严格绑定，
  请在审计全局 ID/归因后挂入。

## 结论

**verdict=pass（REV-TASK-0166-002）**。F-001..F-004 均有代码、真实容器测试和 clean EV 支撑；无新 P0/P1。
F-006/F-007/F-008/F-010 为 accepted P2/P3，按 owner/due 在 M05 gate 前跟踪。任务可进入 done 流程，
但模块仍须完成 V1/V2 与 Product 预验收。
