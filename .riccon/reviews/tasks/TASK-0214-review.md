---
review_id: REV-TASK-0214-001
module_id: M25
task_id: TASK-0214
reviewer:
  agent: riccon/verifier
  session: ses_df4e7ff11ffeIyJ42Xk870wg4V
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - c1d9fe24c7b7b870cbe68ceb79fdae2f075a15b5
  - 662ae89770dd1c7be01a03b412ee0f01c2388f64
  - eea093e5813198d353dd5ea549f6891b492c25c4
  - 8e38943b2d1ccb5bebe7e490a51508df1898591d
head_commit: 8e38943b2d1ccb5bebe7e490a51508df1898591d
baseline_commit: 8664b5df93adb0c0884e2763129226e73376bb63
dod:
  acceptance: true
  tests: true
  security: true
  review: true
  evidence: true
dod_not_applicable:
  - build
  - coverage
  - e2e
  - migration
  - perf
  - a11y
  - docs
dod_note: >-
  acceptance 记为 true，但 AC1 按“pass (with residual)”记录 F-001：连接器文档无源元数据（fileId/connectorId
  均为空）返回 KB-404 已覆盖；平台对象缺失返回 KB-502 与 AC 字面“无源对象返回 KB-404”存在解释差异，已作为
  P2 accepted 交 Lead 确认。build/coverage/e2e/migration/perf/a11y 由 M25 模块 gate 承接；本任务无文档行为
  变化，ADR-0023 §5.3/§6.2/§6.3 已描述连接器下载与 key，docs 记 N/A。
reviewed_evidence:
  - EV-0984
evidence:
  - EV-0984
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/lead
    due: M25 gate 前（B7b 启动前确认 AC 口径）
    title: "平台对象缺失返回 RICCON-KB-502，与任务 AC 字面“无源对象返回 RICCON-KB-404”存在差异"
    summary: >-
      ConnectorDownloadApiTest#download_missingPlatformObjectReturnsKb502 实测：文档可见但平台桶对象不存在的
      GET /api/v1/knowledge/documents/{id}/download 返回 502 RICCON-KB-502，而不是 404 RICCON-KB-404。
      实现位于 KnowledgeDownloadService.java:150-159。builder 的理由是 FileStorage.openObject 只抛
      StorageReadException，故意不暴露 SDK 的 NoSuchKey 分类；改 FileStorage/** 超出 TASK-0214 写范围，
      且 UPLOAD 路径对对象缺失同样返回 KB-502（KnowledgeDownloadApiTest#download_storageFailureReturnsKb502），
      强行只给连接器分支改 404 会造成两条路径不一致。
    accepted_reason: >-
      契约 contracts/openapi.yaml 的 downloadKnowledgeDocument 明确“对象存储读取失败返回 RICCON-KB-502”，
      ADR-0023 §5.3 的“无源文件”在既有 M05 实现中指文档没有可解析的源元数据/源文件行（fileId 为空或
      file 行已删除）；平台对象已清理的场景一直按存储读取失败 502 处理，并在 TASK-0165/0166 评审中接受。
      TASK-0214 的“无源对象 404”若按字面要求对象
      缺失也 404，最小修复是给 FileStorage 增加“对象不存在”稳定异常（S3 404）并在下载服务映射 KB-404，
      这需要 Lead 扩展写范围到 apps/api/src/main/java/com/riccon/api/file/storage/** 并同步契约/回归，
      不是本任务可静默完成的行为变更。当前选择不泄露 key、不把存储故障伪装成“无权限”，风险有界。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:150-159；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorDownloadApiTest.java:148-167；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDownloadApiTest.java:154-177；
      contracts/openapi.yaml:1152-1156。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    title: "平台对象 key 派生在 API 与 worker 各一份，缺少跨模块一致性测试"
    summary: >-
      API 在 KnowledgeDownloadService.java:177-180 复制了
      tenants/{tenantId}/connector/{yyyy}/{documentId} 的派生逻辑；worker 的权威实现在
      ConnectorContentTypes.java:109-112。两处当前逐字符一致，但 API 无法直接依赖 worker 模块，
      API 测试的 platformKey 又是第三份复制；若 worker 未来改格式，API 编译和现有单测不会失败，
      只会在集成环境表现为 KB-502。
    accepted_reason: >-
      两处实现均使用 ZoneOffset.UTC 与同一字符串模板；worker ConnectorDomainTest 断言正常年份 key，
      API ConnectorDownloadApiTest 用跨年样例从平台桶实际读取成功，已提供间接一致性。把派生抽到
      platform-domain 或增加跨模块契约测试会扩大 TASK-0214 写范围并触及架构边界，留到 M25 模块 gate
      或后续重构任务处理。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:177-180；
      apps/worker/src/main/java/com/riccon/worker/connector/domain/ConnectorContentTypes.java:109-112；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDomainTest.java:28-32。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "跨年用例依赖测试 JVM 默认时区非 UTC 才能捕获 systemDefault 回归"
    summary: >-
      ConnectorDownloadApiTest#download_connectorDocumentStreamsPlatformObject 使用
      createdAt=2025-12-31T23:30Z 并把对象写到 UTC 年 2025 key；本机测试 JVM 为 Asia/Shanghai（日志 +08:00），
      若实现误用 ZoneId.systemDefault() 会派生 2026 而失败，因此当前环境确实锁住了 UTC 年。但构建未固定
      user.timezone；在 UTC 的 CI JVM 上，systemDefault 与 UTC 同值，同一用例无法捕获该回归。
    accepted_reason: >-
      生产实现显式使用 ZoneOffset.UTC，与 worker 完全一致；跨年样例还同时锁住了“必须使用 createdAt
      而不是 now”。风险仅在“未来有人改成 systemDefault 且 CI 为 UTC”的组合，属于测试加固，不影响
      当前验收；建议后续在测试内写出 UTC/北京时间两个 key 并断言只读 UTC，或给 key 派生加包级单测。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorDownloadApiTest.java:54-60,266-269；
      本机独立复跑日志时间戳 2026-11-24T09:13+08:00。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "连接器下载没有独立的 U8 admin_read 审计用例"
    summary: >-
      KnowledgeDownloadApiTest#download_managementReadWritesAudit 已覆盖 UPLOAD 文档的
      knowledge.document.admin_read scope=DOWNLOAD；TASK-0214 新增的连接器分支位于 ACL 判定与审计之后
      （KnowledgeDownloadService.java:103-134 在 :147 分支之前），逻辑上复用同一路径，但
      ConnectorDownloadApiTest 没有“管理员下载非本人创建的 DEPT/USERS 连接器文档并断言审计”的用例。
    accepted_reason: >-
      代码路径未被连接器分支改变，既有 UPLOAD 用例可证明审计写入与 fail-closed；连接器分支只在 ACL
      通过后选择源对象。该缺口属于安全回归用例补强，当前行为有共享代码 + 既有测试覆盖，不阻塞本任务；
      补测需重录任务 EV，留待后续安全回归批次。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:103-134；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDownloadApiTest.java:123-152。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "KnowledgeJdbc 注释仍称无源文件一律返回 KB-404，与连接器文档可下载不一致"
    summary: >-
      KnowledgeJdbc.java:594 的 sourceFileAccessLimited 注释写“无源文件（SEED）或源文件已删除：知识库下载
      会返回 KB-404”；连接器文档同样没有 file/space，但现在按平台对象正常下载 200。该注释在本次任务后
      已不准确，可能误导后续维护者把连接器来源当成 404 处理。
    accepted_reason: >-
      只是注释/文档一致性问题，无运行时影响；文件不在 TASK-0214 写范围（apps/api/.../knowledge/persistence），
      由后续触碰该文件的 builder 或文档任务修正即可，不阻塞本任务。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/persistence/KnowledgeJdbc.java:590-596。
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    title: "EV-0983/EV-0985 与 EV-0984 并发运行，EV-0983 因共享 target 初始化失败"
    summary: >-
      EV-0984（builder-b12b，09:04:11–09:07:07）运行期间，EV-0983（lead-b12b，09:05:00–09:05:32）
      和 EV-0985（lead-b12b，09:05:49–09:08:30）也在同一工作树启动 Maven；EV-0983 以 31 个
      NoClassDefFound/ExceptionInInitializer 错误 exit 1，原因是 ConnectorApiTestSupport 静态初始化在
      并发编译/类文件替换中失败；EV-0985 已被 8e38943 归档为 superseded。EV-0984 的 exit 0 不可能是
      测试失败伪装（Maven 会非零退出），但其 surefire 报告在并发运行中被后续进程覆盖，不能逐类归因。
    accepted_reason: >-
      权威 EV-0984 的 commit/dirty/exit/hash 已核对（commit 662ae89、dirty=false、exit 0、
      sha256 6e633dae… 匹配）；失败只由并发运行造成假阴性，不会造成假绿。verifier 在不与任何 Maven
      并发的 HEAD 8e38943 上独立复跑同一受影响集合，31/31 通过，故不阻塞本任务；但需继续遵守
      “同一工作树同一时间只跑一个全量/长测试”的规则，后续由 Lead 在 handoff 中提醒。
    evidence: >-
      .riccon/evidence/TASK-0214/EV-0984.json；.riccon/evidence/TASK-0214/EV-0983.json；
      .riccon/archive/evidence-superseded/TASK-0214/README.md；本 review 的独立复跑记录。
test_impact: >-
  新增 ConnectorDownloadApiTest 5 个真实 HTTP/MySQL/RustFS 用例（成功平台桶流、ACL 收窄+跨租户、
  软删除、平台对象缺失+源桶隔离、无来源 KB-404）；ConnectorApiTestSupport 把连接器源桶与平台桶拆开。
  Verifier 在 HEAD 8e38943 独立复跑 6 个受影响类共 31 用例：
  ConnectorDownloadApiTest 5、KnowledgeDownloadApiTest 6、KnowledgeAclApiTest 5、ConnectorCrudApiTest 10、
  ConnectorCredentialSyncApiTest 4、ConnectorDocumentReadApiTest 1，均 0 failures/0 errors/0 skipped；
  `./mvnw -q spotless:check` exit 0；未跑 worker 全量、E2E、coverage、perf（模块 gate 承接）。
notes: >-
  - 代码范围核对：8664b5d..c1d9fe2 仅 3 个文件（KnowledgeDownloadService + ConnectorApiTestSupport +
    ConnectorDownloadApiTest），无 contracts/docs/deploy/.riccon 生产改动；git diff --check 通过。
  - 证据核对：EV-0984 command/commit=662ae89/dirty=false/exit=0/output_sha256=6e633daeedef5e9fa2fa538d6813a120034fe052adbca3150651fb111bdd90bb
    全部匹配；EV-0983 exit 1 仅作并发失败记录，不用于通过；EV-0985 已归档。
  - ACL/安全核对：下载先按文档 ACL（不可见/跨租户/软删除统一 RICCON-TENANT-404），连接器分支不读 workspace/
    源空间；平台 key 由服务端派生且 FileStorage 再次校验 tenants/{tenantId}/ 前缀；响应只含文档 title/MIME/
    size 与流，错误体/日志无 key/bucket/endpoint/预签名 URL；管理性读取审计在打开流之前 fail-closed。
  - Key 核对：API 与 worker 均按 createdAt 的 UTC 年派生 tenants/{tenantId}/connector/{yyyy}/{documentId}；
    worker 新增文档用同一个 now 写对象与 created_at，更新文档用库内 created_at，下载侧一致。
  - 未验证：worker 模块全量、E2E、coverage、perf/a11y、M25 模块 gate；本评审不替代 Product 预验收/用户验收。
  - 当前 HEAD 8e38943 只包含 .riccon 证据归档；被测代码与 662ae89 相同。
created_at: "2026-11-24T09:16:50+08:00"
---

# REV-TASK-0214-001：连接器文档原文下载独立评审

## 结论

**Pass（带 residual）**。连接器文档（`file_id=null`、`connector_id!=null`）现在按文档 ACL 从平台桶流式下载，
平台 key、UTC 年、源桶隔离、ACL 收紧/软删除/跨租户 404、无 key/预签名泄露均有真实 MySQL/HTTP/RustFS 测试；
UPLOAD 路径与既有 M05 下载测试回归通过。唯一需要 Lead 明确的是“平台对象缺失”返回 `RICCON-KB-502` 与 AC
字面 404 的差异（F-001，P2 accepted），以及五项 P3 测试/架构加固项。没有 P0/P1。

## AC 对账

| AC                                                                                                 | 结论                     | 证据/说明                                                                                                                                                                                                                                                                                   |
| -------------------------------------------------------------------------------------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC1 CONNECTOR 文档按文档 ACL 返回平台对象流；UPLOAD 不回归；无源对象 KB-404；不暴露 key/预签名 URL | **pass (with residual)** | `ConnectorDownloadApiTest#download_connectorDocumentStreamsPlatformObject` 200/字节/`Content-Type`/`Content-Length`；`KnowledgeDownloadApiTest` 6/6 回归；无来源文档用例断言 `RICCON-KB-404`；响应/日志无 key。residual=F-001：平台对象缺失返回 `RICCON-KB-502`（AC 字面 404 的解释差异）。 |
| AC2 key 与 B6 写入一致；跨租户/软删除 404；测试源桶≠平台桶                                         | pass                     | `ConnectorDownloadApiTest` 断言 `/connector/2025/`（createdAt=2025-12-31T23:30Z），对象写在平台桶后读取成功；`download_missingPlatformObjectReturnsKb502` 在源桶写同名 key 仍 502；跨租户/软删除均 404；`ConnectorApiTestSupport` 拆桶。                                                    |
| AC3 大小/MIME 与文档一致；ACL 收窄/删除后立即 404；既有 KnowledgeDownloadApiTest 全绿              | pass                     | 成功用例断言 `application/pdf`、字节数与 `Content-Length`；TENANT→USERS 后成员下一请求 404、管理员 200；软删除后成员/管理员均 404；既有 6 个下载用例独立复跑通过。                                                                                                                          |

## 验证了什么

- 读任务卡、DEC-0041、ADR-0023 §5.3/§6.2/§6.3、openapi `downloadKnowledgeDocument` 与连接器段、worker
  `ConnectorContentTypes.objectKey`；逐行核对 `KnowledgeDownloadService`、`KnowledgeDownload`、`S3FileStorage`
  与 `ObjectKeys.requireTenantPrefix`。
- 独立复跑受影响集合（HEAD `8e38943`，工作树干净，external 真实 MySQL 8.4/RustFS/Tomcat，namespace
  `verifier-b12b`）：`ConnectorDownloadApiTest 5/5`、`KnowledgeDownloadApiTest 6/6`、`KnowledgeAclApiTest 5/5`、
  `ConnectorCrudApiTest 10/10`、`ConnectorCredentialSyncApiTest 4/4`、`ConnectorDocumentReadApiTest 1/1`，
  合计 31/31，0 failures/0 errors/0 skipped；无 Maven `[ERROR]`。
- `./mvnw -q spotless:check` exit 0；`git diff --check` 通过。
- 逐项审计 ACL 顺序、审计 fail-closed、response header 构造（CRLF/引号过滤）、S3 前缀校验、异常脱敏与日志。

## 未验证

- worker 模块全量、M25 模块 gate 的 build/test/coverage/E2E/a11y/perf；本任务不涉及迁移，未跑 migration。
- 连接器同步 worker 端到端（B6 已由 EV-0982 等覆盖）；本评审只核对 key 公式和下载读取，不重启 worker。
- 未做 Product 预验收/用户验收；本任务评审不替代模块验收。

## 剩余风险

1. **F-001（P2 accepted）**：平台对象被清理但文档仍可见时返回 502 而非 404；需要 Lead 确认 AC 口径，若要
   严格 404 则由 FileStorage 端口变更承接（超出本任务写范围）。
2. key 派生 API/worker 双份，未来格式漂移只能在集成环境暴露（F-002）。
3. 跨年测试在 UTC CI 下不能捕获 `systemDefault` 回归（F-003）；连接器分支缺独立 admin_read 用例（F-004）。
4. 并发 Maven 曾导致 EV-0983 假阴性；EV-0984 已核对 hash/commit/dirty，独立复跑亦通过（F-006）。

## 建议

- Lead 可将 TASK-0214 标 done；B7b 启动前只需确认 F-001 的 AC 口径（接受 502 或将 FileStorage 404 映射列入
  后续 fix 任务），不需要为 B7b 重开本任务。
- 模块 gate 时由 Verifier 另跑合同 build/test/coverage/E2E；本任务级 EV 不能替代模块全量。
