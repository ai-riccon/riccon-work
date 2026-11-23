---
review_id: REV-TASK-0166-001
module_id: M05
task_id: TASK-0166
reviewer:
  agent: riccon/verifier
  session: ses_df9a7ec2affefiTzIX6zXpfKTs
  model: "opencode-go/deepseek-v4.1-flash#max"
  independent: true
verdict: fail
reviewed_commits:
  - 7829dd37d0e259c9e8d766d827d4d8c1d7e7c1a0
  - 96d0111b0a5d48445e3b635ff793238cd7f5421f
  - e24a4edd1aeba215f94eda73cffd8d8dc545c322
  - dbef7ba5107734f06da14ff5fd6061b9bcdf1c0e
head_commit: dbef7ba5107734f06da14ff5fd6061b9bcdf1c0e
evidence:
  - EV-0821
  - EV-0822
  - EV-0823
reviewed_evidence:
  - EV-0821
  - EV-0822
  - EV-0823
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/lead + riccon/architect + riccon/builder-kb-acl"
    due: "M05-B3 修复 / M05 gate 前"
    title: "生产 chat/report/knowledge_search 未接入带 actor 的检索 ACL，DEPT/USERS 文档实际不可检索"
    summary: >-
      B3 新增的 `AgentContextJdbc.search(TenantId, UUID, boolean, String, int, int)` 在直连测试中正确过滤
      TENANT/DEPT/USERS，但生产调用链仍走无 actor 的兼容入口：`AgentContextRepository.search`（40-42）只调用
      四参 `contextJdbc.search`，而该重载固定传入 `actorUserId=null`（AgentContextJdbc 67-69）并按 fail-closed
      只返回 `TENANT` 文档。`KnowledgeRetriever.retrieve/retrieveKnowledge`（51-62、91-92）没有 actor 参数，
      `ChatAgentService.prepare` 收到 `userId` 后仍只调用 `retriever.retrieve(tenantId, query, fileIds)`（81），
      `ResearchAgentService`（72、128）与 `KnowledgeSearchTool`（94）同。结果：AC-07“王敏使用文档列表、检索、
      引用打开与知识库下载，四处均成功”和 AC-08“新范围在下一次检索请求即生效”在检索面失败——被授权的
      DEPT/USERS 文档永远检索不到。该路径是 fail-closed，不构成数据泄露，但核心 ACL 功能不完整，属于 P1。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/repository/AgentContextRepository.java:40-42；
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:54-69、86-87；
      apps/worker/src/main/java/com/riccon/worker/agent/service/KnowledgeRetriever.java:51-62、91-92；
      apps/worker/src/main/java/com/riccon/worker/agent/service/ChatAgentService.java:68-81；
      apps/worker/src/main/java/com/riccon/worker/agent/service/ResearchAgentService.java:72、128；
      apps/worker/src/main/java/com/riccon/worker/agent/service/KnowledgeSearchTool.java:94；
      `AgentContextJdbcAclTest#search_legacyOverload_onlyTenantDocuments` 已用测试断言兼容入口只返回 TENANT。
  - id: F-002
    severity: P1
    status: open
    owner: "riccon/builder-kb-acl + riccon/lead + riccon/architect"
    due: "M05-B3 修复 / M05 gate 前"
    title: "U8 admin_read 缺少 scope=SEARCH，检索管理性读取无审计"
    summary: >-
      ADR-0023 §5.4/§5.6 与 AC-06 要求 `knowledge.document.admin_read` 覆盖 LIST/DETAIL/SEARCH/DOWNLOAD；
      B3 已实现 LIST（KnowledgeService 列表）、DETAIL（详情）与 DOWNLOAD（下载）的审计，但没有任何
      `scope=SEARCH` 的写入方或测试。`KnowledgeSearchTool`/`KnowledgeRetriever` 当前也没有把管理员标记传入
      检索入口（F-001 同根因），因此即使修好检索 wiring，绕过 ACL 的 SEARCH 仍不会写 `reason=MANAGEMENT_READ`、
      `resultCount` 的审计记录，违反 U8 留痕与 AC-16“重索引/管理性读取均可查”的完整性要求。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeAuditEvents.java:183-216（只有构造器支持 SEARCH）；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeService.java:113-140（LIST）、158-200（DETAIL）；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:96-135（DOWNLOAD）；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeAdminReadAuditTest.java:24-74 仅 LIST/DETAIL；
      `grep -R '"SEARCH"' apps/api/src/main/java apps/worker/src/main/java` 无检索审计写入方。
  - id: F-003
    severity: P1
    status: open
    owner: "riccon/lead + riccon/architect + riccon/builder-kb-acl"
    due: "M05-B3 修复 / M05 gate 前"
    title: "reindex 没有审计动作，AC-16 的“重索引可查/审计失败即操作失败”无法满足"
    summary: >-
      AC-16 明确要求“删除、ACL、U8 管理性读取、重索引……均有动作；审计写入失败时对应管理操作失败”。
      `KnowledgeDocumentLifecycleService.reindex`（144-182）只做 ACL/RBAC/409 判定并投递 FULL 索引事件，
      没有调用 `auditService.append`；`AuditActions` 也没有任何 reindex 动作常量（111-137）。因此重索引既不可按
      租户/操作者/动作/时间查询，也没有“审计失败回滚”的 fail-closed 路径。该缺口跨越平台审计词汇与 B3 服务，
      需要 Lead/Architect 先扩展 `AuditActions`/`AuditDetailsPolicy`，再由 B3 或后续任务写事件；P1 因为它是
      T4 审计 P0 的组成部分。
    evidence: >-
      docs/PRODUCT.md:566（M05-AC-16 原文）；docs/adr/ADR-0023-m05-knowledge-depth.md:383-403、823-828；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDocumentLifecycleService.java:144-182；
      platform/platform-domain/src/main/java/com/riccon/platform/domain/audit/AuditActions.java:110-137；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDeleteReindexApiTest.java:120-190（只断言 outbox，无审计断言）。
  - id: F-004
    severity: P1
    status: open
    owner: "riccon/builder-kb-acl"
    due: "M05-B3 修复 / M05 gate 前"
    title: "历史 NULL retention_expires_at 按当前租户策略计算，违反“策略变更不追溯旧文档”"
    summary: >-
      ADR-0023 §10 规定历史行 `retention_expires_at IS NULL` 按 `created_at + 180 天` 计算；当前实现用
      `createdCutoff = now - tenant.retention_days`（KnowledgeRetentionProcessor 83-89）匹配 NULL 行
      （KnowledgeJdbc 458-459），即把当前租户策略套到历史文档。若租户把 `retention_days` 从 180 调低，
      升级前创建的 M02/M03 文档会在下一次 03:00 任务中被提前软删除（30 天恢复窗口内可恢复，但已违反
      AC-21 的“策略变更不追溯”）。同一错误也出现在响应层：`retentionDays`/`retentionPolicy` 对 NULL 行返回
      当前租户天数而不是 180（KnowledgeDocumentRecord 105-124）。测试只覆盖新建文档，未覆盖历史 NULL 行。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:823-824（历史 NULL 与 COALESCE(..., created_at + 180 天)）；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeRetentionProcessor.java:83-89；
      apps/api/src/main/java/com/riccon/api/knowledge/persistence/KnowledgeJdbc.java:448-463；
      apps/api/src/main/java/com/riccon/api/knowledge/domain/KnowledgeDocumentRecord.java:99-124；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeRetentionTest.java:25-66（无 NULL 历史行用例）。
  - id: F-005
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-acl + riccon/architect"
    due: "M05 gate 前"
    accepted_reason: >-
      这是灰度开关一致性问题，且当前生产检索尚未接入 actor（F-001），实际影响被 F-001 掩盖；谓词方向是
      fail-closed，不会在 ACL 关闭时错误放行，先按 P2 跟踪，修复 F-001 时必须同时接上 worker 开关。
    title: "Worker 检索不读取 RICCON_KB_ACL_ENFORCEMENT_ENABLED，关闭 ACL 时 API/Worker 语义不一致"
    summary: >-
      ADR-0023 §5.8 规定该开关生效于“API + Worker 每次查询”，false 时谓词退化为 tenant_id。Worker 的
      `AgentContextJdbc.appendAclPredicate`（302-365）没有 enforcement 参数，也没有 worker 侧
      `KnowledgeAclProperties`；只要调用方传入 actor 就会始终强制 ACL，导致 `ENFORCEMENT=false` 时 API 把
      DEPT/USERS 文档按 TENANT 展示、Worker 检索却静默隐藏，灰度期行为不一致。未发现泄露（更严格方向），
      但会阻碍 F-001 修复后的灰度流程。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:412-421；
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:302-365；
      apps/api/src/main/java/com/riccon/api/config/KnowledgeAclProperties.java:9-18；
      `apps/worker/src/main/resources/application.yml` 无 `riccon.kb.acl.*`。
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/architect + riccon/builder-kb-contract"
    due: "M05 gate 前"
    accepted_reason: >-
      运行时行为已由下载测试验证且不泄露 key；缺口是契约精度/错误码登记，B3 写范围不含 contracts，不能在本任务内修；
      按 B2 F-003 的既有接受口径继续跟踪，M05 契约测试复验前必须收敛。
    title: "下载 502 稳定码与动态 Content-Type 仍未写入契约，B2 F-003 残留未收敛"
    summary: >-
      B3 实现 `RICCON-KB-502`（KnowledgeDownloadFailedException 19-21）并按文档 mimeType 设置
      `Content-Type`（KnowledgeController 239-245），但 OpenAPI 1.4.0-m05 的下载 200 只声明
      `application/octet-stream`（contracts/openapi.yaml:1168-1172），502 仍是通用 `BadGateway` 且无稳定码
      （1180）。B2 F-003 曾要求“B3 下载实现前收敛并补契约断言”；B3 只在实现和下载测试中断言了
      `application/pdf`/`RICCON-KB-502`，契约与契约测试未同步。运行时下载本身可用，故按 P2 而非行为 P1。
    evidence: >-
      contracts/openapi.yaml:1134-1180；apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadFailedException.java:6-21；
      apps/api/src/main/java/com/riccon/api/knowledge/controller/KnowledgeController.java:239-245；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDownloadApiTest.java:42-50、130-150；
      .riccon/reviews/tasks/TASK-0165-review.md F-003。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      EV 内容真实、绑定提交且测试报告可复核，不因此判任务失败；但这是对 strict 证据惯例的流程例外，且
      verifier 在同样的 B4/B5 占用下只能录到 dirty 证据（EV-0822/0823），需要 Lead 在 M05 gate 前明确
      并行任务期间的取证规范，避免模块评审拿到 dirty 证据。
    title: "EV-0821 在隔离 worktree 录制；主树被 B4/B5 WIP 占用，verifier 证据为 dirty"
    summary: >-
      EV-0821 的 `cwd` 为 `/var/folders/.../riccon-b3-evidence-wt`，commit `dbef7ba`，dirty=false；已核对
      log sha256 `69aa...4858` 与 JSON 一致，worktree HEAD 仍是 dbef7ba，且其 surefire 报告为 API 137 个
      测试类 727 用例、worker 3 个类 14 用例，0 failure/error/skip，内容可信。评审时主树 HEAD 已到 f6ccf9c，
      B4/B5 有多项 WIP（worker pom、parse/OCR 源码与测试）；`git diff dbef7ba..HEAD` 不含 B3 代码，故定向验证仍针对 B3 代码，但
      EV-0822/EV-0823 的 `dirty` 为 true，不能替代模块 gate 的干净全量证据。
    evidence: >-
      .riccon/evidence/TASK-0166/EV-0821.json；.riccon/evidence/TASK-0166/EV-0822.json；
      .riccon/evidence/TASK-0166/EV-0823.json；`shasum -a 256 EV-0821-*.log`；
      `/var/folders/.../riccon-b3-evidence-wt/apps/api/target/surefire-reports`、`apps/worker/...`。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-acl + riccon/verifier（M05-V1/V2）"
    due: "M05 gate 前"
    accepted_reason: >-
      现有测试均为真实 MySQL/RustFS 集成测试且断言有效；缺的是边界矩阵而非假绿。F-001/F-002/F-004 修复后，
      这些用例必须补齐，否则 ACL/保留的 AC 仍不可由自动化证据复核。
    title: "ACL/下载/保留测试矩阵仍有边界缺口（部门移出、200 人上限、软删除下载、平台管理员动作、历史 NULL）"
    summary: >-
      `KnowledgeAclApiTest` 只测部门停用（199-201），未测“成员移出部门后下一请求 404”；只测 201 人拒绝
      （181-196），未测 200 人恰好通过；`KnowledgeDownloadApiTest` 未测文档软删除后的下载、平台管理员
      下载/删除/reindex 的 404；`KnowledgeRetentionTest` 未测历史 NULL 行。上述缺口与 F-001/F-002/F-004
      互相印证：测试绿灯不能证明生产检索与历史保留策略正确。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeAclApiTest.java:181-201；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDownloadApiTest.java:53-97；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeRetentionTest.java:25-66；
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextJdbcAclTest.java:94-119。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core（B1 既有 F-014）"
    due: "M05 gate 前"
    accepted_reason: >-
      B3 写入方已经在 `KnowledgeAuditEvents.adminRead` 对非 LIST scope 强制 visibility/documentId，现有
      API 路径不会被绕过；这是策略层防御纵深缺口，按 B1 F-014 的既有 P3 跟踪，不阻塞 B3 代码评审。
    title: "B1 AuditDetailsPolicy 仍把 admin_read.visibility 列为全 scope nullable，B3 写入方单点兜底"
    summary: >-
      `AuditDetailsPolicy.validateRequiredDetails`（422-455）只强制非 LIST 的 `documentId`，`visibility`
      仍在 `NULLABLE_KEYS` 中（287）；若未来有其他写入方直接构造 DETAIL/SEARCH/DOWNLOAD 审计而漏 visibility，
      策略层仍会接受。B3 的 `KnowledgeAuditEvents.adminRead`（190-220）已在写入前抛错，故当前行为正确；
      这是 B1 F-014 的残留，记录以便 M05 gate 前收敛。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:287、422-455；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeAuditEvents.java:190-220；
      .riccon/reviews/tasks/TASK-0164-review.md F-009/F-014。
created_at: "2026-11-23T11:15:34+08:00"
---

# REV-TASK-0166：TASK-0166（M05-B3 ACL/delete/reindex/流式下载/admin_read/保留）独立评审

## 结论

**fail（任务级）**。代码的 API 面 ACL、下载、删除/重索引、保留任务和审计写入质量总体良好，定向集成测试
23 + 5 个用例全部通过、0 skip；但发现 4 个 P1：生产 chat/report/knowledge_search 仍未接入带 actor 的
检索 ACL（DEPT/USERS 文档实际不可检索）、U8 `admin_read(scope=SEARCH)` 完全缺失、reindex 无审计、
历史 `retention_expires_at IS NULL` 行按当前租户策略计算而违反“不追溯”。这些问题不构成数据泄露（检索为
fail-closed），但直接使 AC-06/07/08/16/21 的 Then 子句不可满足，必须在 M05-B3 收口或由 Lead 拆出后续任务修复。

任务级评审不等于模块评审；M05 仍需 V1/V2 全量 build/test/coverage/E2E/a11y/security/性能/迁移证据。

## 审查范围与证据

| 项         | 值                                                                                                                                      |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| baseline   | `5c230e4`                                                                                                                               |
| 任务提交   | `7829dd3`、`96d0111`、`e24a4ed`、`dbef7ba`                                                                                              |
| end commit | `dbef7ba5107734f06da14ff5fd6061b9bcdf1c0e`                                                                                              |
| diff       | 50 文件，全部命中 write_scope；无 B4/B5 文件，无迁移/构建配置改动                                                                       |
| 任务级证据 | EV-0821（builder，隔离 worktree，dbef7ba，dirty=false）；EV-0822（verifier API 定向，23 用例）；EV-0823（verifier worker 定向，5 用例） |
| 未跑       | 全仓 `verify`、coverage、E2E、a11y、security、perf（属 M05-V1/V2 与 gate；B3 无 UI）                                                    |

范围核对：`git diff --name-status 5c230e4..dbef7ba` 共 50 文件：knowledge 生产 37、file/storage 3、
knowledge 测试 8、worker AgentContextJdbc 1、worker 测试 1；无超出 Lead 补录后 write_scope 的路径。

### EV-0821 真实性核对（隔离 worktree 例外）

- JSON 的 commit 是 `dbef7ba`，`dirty=false`，命令为 API 全量 `-pl apps/api -am test` 加 worker 定向；
  log sha256 `69aa1b7f...874858` 与 JSON 一致，输出 1,679,551 bytes。
- 隔离 worktree 仍存在，`git -C ... rev-parse HEAD` = `dbef7ba`；其 surefire 报告为 API 137 个测试类
  **727 用例**、worker 3 个类 **14 用例**，failure/error/skip 均为 0；与 EV 的 exit 0 一致。
- 结论：EV 内容真实、绑定 dbef7ba，可作为**任务级**证据；但“主树 dirty 时用 worktree 取证”仍是流程例外，
  见 F-007。评审时主树 HEAD 已到 `f6ccf9c`（B4 的 `.riccon` 记录与 B5 WIP），`dbef7ba..HEAD` 不含 B3
  代码，EV-0822/0823 的 dirty 只反映 B4/B5 文件，不改变对 B3 代码的定向验证结论。

### 独立定向验证

```bash
# EV-0822（主树；B4/B5 WIP 使 dirty=true，B3 路径未变）
./mvnw -q -T1C -pl apps/api -am test \
  -Dtest='KnowledgeAclApiTest,KnowledgeAdminReadAuditTest,KnowledgeAuditFailClosedTest,KnowledgeDeleteReindexApiTest,KnowledgeDownloadApiTest,KnowledgeIndexUnavailableTest,KnowledgeRetentionTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# 23 tests, 0 failures, 0 errors, 0 skipped

# EV-0823
./mvnw -q -T1C -pl apps/worker -am test \
  -Dtest='AgentContextJdbcAclTest' -Dsurefire.failIfNoSpecifiedTests=false
# 5 tests, 0 failures, 0 errors, 0 skipped
```

未先跑 test 再跑 coverage 两遍；这里没有跑覆盖率，任务级评审只验证行为。构建锁按 Lead 指定的
`${TMPDIR}/riccon-maven.lock` 与并行任务互斥。

### Gate check（预期失败）

`riccon gate check --module M05` 返回 `ok=false`：除 M05 仍在 building、TASK-0166 仍为 review、无模块 review
等模块级原因外，还因主树上的 B4/B5 WIP 被 `task-scope` 计入 TASK-0166/TASK-0167/TASK-0168，以及 verifier
的 EV-0822/EV-0823 为 dirty。B3 自身的 `5c230e4..dbef7ba` 50 文件范围已单独核对干净（见上）；这些 gate 错误
不属于 B3 代码缺陷，但说明模块 gate 现在不应通过，需 B4/B5 收口后在干净提交上重跑。

## 审查重点逐项结论

| #   | 重点                                                                                                                                     | 结论                        | 依据/残余                                                                                                                                                                                              |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------- | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | ACL 判定顺序：租户+ACL 先取，404/403，平台管理员 404，创建人可见/可改，DEPT/USERS 规则，下一次请求生效                                   | **部分 pass**               | API 的 list/get/ACL GET/PUT/delete/download 使用 `KnowledgeAccessJdbc` 同一谓词；ACL 变更后下一请求测试通过。**检索面失败**：生产调用链未传 actor（F-001）。                                           |
| 2   | delete/reindex：软删除+USER 审计+索引 DELETE+幂等；reindex 补页/force/409/503；RBAC 先于状态                                             | **pass（审计除外）**        | `KnowledgeDeleteReindexApiTest` 4 用例全绿；软删除、reason=USER、delete 事件、204 幂等、403/404、409、503 均验证。reindex 无审计（F-003）。                                                            |
| 3   | 流式下载：FileStorage.open/stream、每请求 ACL、不暴露 key/预签名、KB-404/TENANT-404、安全头、矩阵                                        | **pass**                    | `KnowledgeDownloadApiTest` 5 用例；真实 RustFS 字节流、`application/pdf`、`private, no-store`、`nosniff`、无 key/签名、USERS/跨租户/无源文件/存储失败均验证。契约 502/Content-Type 漂移见 F-006。      |
| 4   | U8 admin_read：LIST/DETAIL/SEARCH/DOWNLOAD、字段、resultCount、MANAGEMENT_READ、fail-closed、不写正文                                    | **部分 fail**               | LIST/DETAIL/DOWNLOAD 有实现与测试；**SEARCH 没有写入方**（F-002）。LIST resultCount、DETAIL documentId/visibility、DOWNLOAD 可见性均正确。                                                             |
| 5   | 审计必填/可空：deleted.reason、added.sourceType/visibility、acl.changed.previousVisibility；未知键/超限/类型 fail-closed                 | **pass（writer 层）**       | `KnowledgeAuditEvents` 构造器显式 require；`AuditDetailsPolicy` 白名单/枚举/数组上限；`KnowledgeAuditFailClosedTest` 4 用例覆盖 LIST/delete/acl/download 回滚。B1 F-014 策略层 nullable 残留见 F-009。 |
| 6   | 保留：180/租户策略、状态覆盖、INDEXING 有分块清索引、未索引无 DELETE、不物理删除                                                         | **部分 fail**               | `KnowledgeRetentionTest` 覆盖默认/租户策略、INDEXED/INDEXING/FAILED/PENDING、DELETE 边界、幂等；无物理删除。历史 NULL 行策略错误见 F-004。                                                             |
| 7   | 索引一致性：acl_version 幂等键、ACL/delete 事件、无 stale 缓存                                                                           | **pass（worker 开关残余）** | ACL 变更 `acl_version+1` 并投 `doc:{id}:acl:{version}` ACL_ONLY；删除投 `doc:{id}:delete:{version}`；无缓存。Worker 不读 enforcement 开关见 F-005。                                                    |
| 8   | 测试质量：ACL 四范围、创建人、403/404、跨租户/部门、USERS 上限、download 无 key、审计 fail-closed、retention 各状态、delete/reindex 幂等 | **绿灯但有缺口**            | 23 + 5 用例无 skip、均为真实容器；但主树测试没有覆盖生产 retriever wiring，边界矩阵缺口见 F-008。                                                                                                      |
| 9   | 范围/证据：50 文件全在 scope、无 B4/B5、EV 命令/哈希/commit、worktree 例外记录                                                           | **pass（流程 P3）**         | 50/50 在 scope；EV-0821 内容与哈希/commit/surefire 一致；worktree 例外见 F-007。                                                                                                                       |

## Findings 详情

### F-001（P1）生产检索链路未接入 ACL，DEPT/USERS 文档实际不可检索

**观察**：`AgentContextRepository.search` 只调用四参重载；四参重载在 `AgentContextJdbc` 固定
`actorUserId=null`，而新六参重载在 `actorUserId==null` 时只允许 `visibility='TENANT'`。`KnowledgeRetriever`
没有 actor 形参，`ChatAgentService.prepare` 明明已接收 `userId`（第 72 行）却仍调用三参 `retrieve`（第 81 行）；
`ResearchAgentService` 和 `KnowledgeSearchTool` 同样。`AgentContextJdbcAclTest#search_legacyOverload_onlyTenantDocuments`
甚至把这个兼容行为固化了。

**期望**：chat/report/knowledge_search 调用链把当前 run 用户（以及 TENANT_ADMIN 的 unrestricted 标记）传入
六参重载，使被 ACL 授权的 DEPT/USERS 文档可检索；管理性读取同时写 SEARCH 审计（F-002）。

**影响**：AC-07 的“检索”一面和 AC-08 的“下一次请求生效”在真实用户旅程中失败；属于功能不完整，不是泄露。
修复需要扩展 `KnowledgeRetriever`/`AgentContextRepository`（B3 write_scope 不含；Lead 需扩 scope 或拆任务）。

### F-002（P1）U8 admin_read 缺少 SEARCH

**观察**：全仓没有任何 `scope=SEARCH` 的 `adminRead` 调用；`KnowledgeAdminReadAuditTest` 只有 LIST/DETAIL
两个用例，下载另测；`KnowledgeAuditEvents.adminRead` 支持 SEARCH 但无人使用。检索入口没有管理员标记，
因此不可能写 SEARCH 审计。

**期望**：当 TENANT_ADMIN 通过检索绕过文档 ACL 读取本租户内容时，写
`scope=SEARCH`、目标 documentId/visibility、`resultCount`、`reason=MANAGEMENT_READ`，且审计失败使检索请求
失败（或明确记录失败结果），不写标题/片段/正文。

**影响**：AC-06 U8 留痕和 AC-16“每类管理读取可查”不完整；T4 审计 P0 不能通过。

### F-003（P1）reindex 无审计

**观察**：`KnowledgeDocumentLifecycleService.reindex` 只投递 outbox，不写 audit；`AuditActions` 没有
reindex 动作。`KnowledgeDeleteReindexApiTest` 只断言 outbox，无审计断言。

**期望**：增加稳定的 reindex 审计动作（如 `knowledge.document.reindexed`）与 details 白名单（documentId、
force、reason、结果），同事务写失败则请求失败；或由 Architect 明确另一种可查询审计载体并让 AC-16 可验证。

**影响**：AC-16 的“重索引均有动作/审计失败对应操作失败”不可满足。该动作常量在 B3 写范围外，需 Lead/
Architect 先扩词汇。

### F-004（P1）历史 NULL retention 行被当前租户策略提前到期

**观察**：`expireTenant` 用当前 `retentionDays` 算 `createdCutoff`；`findExpired` 对 NULL 行用
`created_at <= createdCutoff`。ADR §10 明确历史 NULL 应为 `created_at + 180 天`，且“策略变更不回溯已有文档”。
响应层 `retentionDays`/`retentionPolicy` 对 NULL 行同样返回当前租户策略。

**影响**：租户把保留期调短时，升级前的历史文档可能在首次 03:00 任务被软删除，违反 AC-21；虽可通过 30 天
恢复窗口恢复，但属于数据保留策略错误，P1。

### F-005（P2 accepted）Worker 不读 ACL enforcement 开关

`AgentContextJdbc.appendAclPredicate` 没有 enforcement 形参，worker 也没有 `riccon.kb.acl.*` 配置；ADR §5.8
要求 API+Worker 同一开关，false 时退化为 tenant_id。当前生产检索未接 actor，故影响被 F-001 掩盖；修复 F-001
时必须同时接入 worker 开关。

### F-006（P2 accepted）下载 502/Content-Type 契约漂移

运行时返回 `RICCON-KB-502` 与动态 `Content-Type`，契约仍只声明 octet-stream + 通用 BadGateway。B2 F-003
已接受该缺口并预期 B3 收敛；B3 写范围不含 contracts，按 P2 继续跟踪，M05 契约测试必须补断言。

### F-007（P3 accepted）隔离 worktree 取证流程例外

EV-0821 内容真实且绑定 dbef7ba；但 strict 证据惯例要求主树/干净提交取证。Lead 已批准本任务例外；评审时
主树被 B4/B5 WIP 占用，verifier 的 EV-0822/0823 也只能是 dirty。请 Lead 在 M05 gate 前明确并行任务期
证据规范，避免模块评审使用 dirty EV 绑定 gate。

### F-008（P3 accepted）测试矩阵缺口

部门成员移出（只测停用）、200 人上限通过、软删除文档下载、平台管理员下载/删除/reindex、历史 NULL 保留行
均无用例。现有用例不是假绿（真实容器、断言有效），但不足以证明 AC-07/08/21 的完整 Then 子句；修复 F-001/
F-002/F-004 时一并补齐。

### F-009（P3 accepted）B1 策略层 admin_read.visibility 仍全 scope nullable

B3 writer 已强制非 LIST 的 visibility/documentId；B1 `AuditDetailsPolicy` 的 nullable 仍未按 scope 收窄。
这是 B1 F-014 的残留，当前不影响 API 行为，但防御纵深应在 M05 gate 前收敛。

## 对上轮任务评审的对账

| 评审              | 条目                                    | 本任务状态                     | 说明                                                                                                                                                                                    |
| ----------------- | --------------------------------------- | ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| REV-TASK-0164-001 | F-009 文档审计必填键                    | **fixed（writer 层）**         | `KnowledgeAuditEvents` 对 deleted.reason、added.sourceType/visibility、acl.changed.previousVisibility 显式 require；B1 策略层仍只校验部分键。                                           |
| REV-TASK-0164-001 | F-014 admin_read.visibility nullable    | **accepted（未修，B1 owner）** | 见 F-009；B3 writer 已兜底。                                                                                                                                                            |
| REV-TASK-0165-001 | F-001 ACL 资源先于端点 RBAC             | **fixed（运行时）**            | 控制器读端点不设路由级 `knowledge.read`，服务先按租户+ACL 取文档再判 403；`KnowledgeAclApiTest#aclValidationAndMatrix` 验证 404/403/平台管理员 404。契约 prose 仍可读性差，不影响行为。 |
| REV-TASK-0165-001 | F-003 下载 502/Content-Type/Disposition | **accepted（未收敛）**         | B3 使用 KB-502 与动态 Content-Type；契约未更新，见 F-006。                                                                                                                              |
| REV-TASK-0165-001 | F-005 B2 验收“端点真实实现”超范围       | **fixed（B3）**                | 5 个端点已真实实现，delete/reindex/download/ACL 均有集成测试；M05-AC-22 仍需 V1 契约回归。                                                                                              |

## 未验证与剩余风险

- 未跑全仓 `verify`/coverage/lint/typecheck/E2E/a11y/security/perf；这些属于 M05-V1/V2 与模块 gate，不能由
  本任务级评审替代。
- 未在真实浏览器验证 UI；B3 无 UI，ACL 编辑页/下载入口/旧引用遮蔽在 B8/B9。
- 未验证生产 chat/report 的 ACL 用户旅程——F-001 说明当前代码路径会隐藏 DEPT/USERS 文档；修复后需要
  新增 `KnowledgeRetriever`/`ChatAgentService` 级集成测试。
- 未验证历史 NULL retention 的真实迁移数据；F-004 需补一个 `retention_expires_at IS NULL` 的种子用例。
- EV-0822/0823 为 dirty 证据（B4/B5 WIP），不能作为模块 gate 的 `dod_evidence`；M05 gate 前需在干净提交上
  重跑合同全量并录 EV。
- 任务卡状态未改；本评审不修改生产源码/测试/迁移。

## 下一步（Lead）

1. 决定 F-001/F-002 的归属：扩 `TASK-0166` 写范围（`KnowledgeRetriever`/`AgentContextRepository`/
   `ChatAgentService`/`ResearchAgentService`/`KnowledgeSearchTool`）或拆 B3-fix 任务；同时把 run 用户与
   TENANT_ADMIN 标记传入检索，补 SEARCH 审计。
2. 为 F-003 增加审计动作与 details 白名单（Architect/平台词汇），B3 写 reindex 审计并补 fail-closed 测试。
3. 修 F-004：NULL 历史行固定按 `created_at + 180 天`，响应层 `retentionDays`/`retentionPolicy` 同步。
4. F-005/F-006/F-008/F-009 在 M05 gate 前按 owner/due 收敛；F-007 的 worktree/并行取证规范由 Lead 定。
5. 修复后在干净提交上跑任务定向测试与模块全量；本 review 不因 F-007 判任务失败，但 F-001..F-004 未修复前
   `verdict` 保持 fail。
