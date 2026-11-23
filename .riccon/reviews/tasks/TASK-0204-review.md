---
review_id: REV-TASK-0204-001
module_id: M25
task_id: TASK-0204
reviewer:
  agent: riccon/verifier
  session: ses_df563b2e6ffeVjF7cCHZYTDmmN
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 81bd9c6a5383289edb3719f253f6ee4feb4d0380
  - 85878b71f1fa3a84fd99c25904490cb9ca11692a
  - f31e18052347a7bf9501f1fafaa187a20a9ed005
  - 9012edb9ffe3157a7ce7a5a2da684c1bb5beaed0
  - 9275eb08401981b6e7ca092fa292904b4e1e0c78
  - f745dc6509922c5525845bb51b6c8baf9ccfdb69
  - d87c8ccc2c9de9a02056d248e496fb7e9c6bda24
head_commit: d87c8ccc2c9de9a02056d248e496fb7e9c6bda24
baseline_commit: 81bd9c6a5383289edb3719f253f6ee4feb4d0380
dod:
  acceptance: false
  build: true
  tests: false
  security: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - migration
  - perf
  - accessibility
  - docs
dod_note: >-
  Delta 复核后原两个 P1（F-001 源桶写入、F-002 平台管理员 403）已关闭；F-003/F-004/F-005/F-006/
  F-009/F-010 的原始缺陷已修复。但 F-016（首次创建文档仍绕过 ConnectorDocumentWriteService 事务）、
  F-007（截断无 token 时整页已确认对象被丢弃）和 F-013（sync 路径负向用例仍缺）为 open P2，因此
  acceptance/tests 仍为 false；本任务不能判 pass。security 为 true 指原 P1 安全缺陷已关闭；F-014/F-015/
  F-018 仍有 P3 残余。coverage/e2e/migration/perf/a11y/docs 由 M25 模块 gate/其他任务承接。
reviewed_evidence:
  - EV-0967
  - EV-0968
  - EV-0969
  - EV-0970
  - EV-0971
  - EV-0972
  - EV-0973
  - EV-0974
  - EV-0975
  - EV-0976
  - EV-0977
  - EV-0978
evidence:
  - EV-0967
  - EV-0968
  - EV-0969
  - EV-0970
  - EV-0971
  - EV-0972
  - EV-0973
  - EV-0974
  - EV-0975
  - EV-0976
  - EV-0977
  - EV-0978
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "源端 client 不再 PutObject；连接器原文写入平台 StorageProperties bucket，双桶测试锁定 F-001"
    summary: >-
      新增 `ConnectorObjectWriter`，注入平台 `S3Client` 与 `StorageProperties`，写入
      `storageProperties.bucket()`；同步服务源端 client 只保留 List/Get，源码中已无源端 `putObject`。
      `ConnectorSyncIntegrationTest` 使用 `SOURCE_BUCKET = BUCKET + "-source"` 与平台 `BUCKET` 双桶，
      首次同步后通过 `S3FileContentReader` 从平台 key 读回内容，并断言源桶下没有 `tenants/` 对象。
      证据：EV-0974/EV-0977。该缺陷不再以同桶测试掩盖。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorObjectWriter.java:26-80、
      :57-69；apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:486、
      :504（仅 objectWriter）；`grep -R putObject apps/worker/src/main/java/.../connector` 仅命中
      ConnectorObjectWriter；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:74、
      :127-137、:169-195；EV-0974/EV-0977。
  - id: F-002
    severity: P1
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "8 个连接器端点改为统一 authorize：平台租户 404、普通员工 403、他租户 404"
    summary: >-
      `ConnectorController` 移除方法级 `@RequirePermission`，改为 `authorize()`：平台保留租户
      `PlatformTenant.ID` 先抛 `NotFoundException`（RICCON-TENANT-404），其余主体再经
      `AuthService.requirePermission` 走数据库实时权限（普通员工 403）；服务层仍按租户过滤
      connectorId（他租户管理员 404）。`ConnectorCrudApiTest` 新增平台管理员 8 端点全部 404 的用例，
      原有员工 403/他租户 404 用例继续通过。证据：EV-0973/EV-0976。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/controller/ConnectorController.java:107-120、
      :134-144、:157-169、:180-192、:204-214、:225-232、:242-255、:266-281、:288-301；平台租户常量
      apps/api/src/main/java/com/riccon/api/identity/domain/PlatformTenant.java:10-13；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCrudApiTest.java:224-250；
      EV-0973/EV-0976。
  - id: F-003
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "删除传播改为 ConnectorDeletePropagationService 单条短事务：item/文档/审计/索引 DELETE 同事务"
    summary: >-
      `propagate` 在 `@Transactional` 内依次 `markItemDeleted`、`softDeleteDocument`、
      `auditWriter.documentDeleted`、`eventPublisher.enqueueDelete`；审计或事件异常回滚 item/文档。
      `ConnectorDeletePropagationIntegrationTest` 用 Mockito 让审计/事件失败，断言 item 仍 SYNCED、
      文档 deleted_at 为 null。原 F-003 的核心失败路径已闭环。残余：`enqueueDelete` 的
      DuplicateKeyException 不再被本地吞掉，若并发 API 删除已投递同名事件，会回滚本次传播并让同步
      以 ERROR 收尾；该窗口很小，列 P3 残余（未新增测试）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDeletePropagationService.java:48-77；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:535-547；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDeletePropagationIntegrationTest.java:50-80；
      EV-0974/EV-0977。
  - id: F-004
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0（残余见 F-016）
    title: "ensureIndexEvent 已补投 PENDING 文档的索引事件，覆盖首次投递失败后的重试"
    summary: >-
      原 F-004 的“索引事件失败后 checksum 相同路径不再补投”已修复：`processSupportedObject` 在
      ETag 未变和 checksum 相同的分支调用 `ensureIndexEvent`，仅当文档仍为 PENDING 且无 PENDING/
      DISPATCHED 索引事件时补投；`ConnectorDocumentWriteService.updateDocument` 把更新文档、item SYNCED
      与 enqueueIndex 放在同一事务。原缺陷本身已闭环。首次创建路径仍未使用该服务，见新增 F-016。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:462-467、
      :473-482、:599-615；apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDocumentWriteService.java:115-133；
      apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:703-722；
      EV-0974/EV-0977。
  - id: F-005
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "PATCH 重名捕获 DuplicateKeyException 并返回 409 RICCON-KB-409"
    summary: >-
      `ConnectorService.update` 对 `updateConnector` 的 DuplicateKeyException 增加
      `ConnectorConflictException` 映射；`ConnectorCrudApiTest` 新增“创建 A/B 后 PATCH B 为 A 名 → 409”用例。
      证据：EV-0973/EV-0976。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:230-249；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCrudApiTest.java:275-289；
      EV-0973/EV-0976。
  - id: F-006
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0（残余见 F-018）
    title: "markSyncStarted=0 中止，且每个对象前 isSyncable 复核；同步中删除连接器不再创建文档"
    summary: >-
      `synchronize` 在 `markSyncStarted` 返回 0 时直接返回 ERROR，不写终态；`processObject` 在每个对象
      处理前调用 `isSyncable`（连接器未删除且租户 ACTIVE），失败则置错误并停止本轮。测试用 mock S3
      在 list 时删除连接器，断言 documents 为空、last_sync_status 为 null。核心路径已阻止新文档；
      check-then-act 与 insertDocument 之间的极小窗口仍存在，见 P3 F-018。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:166-179、
      :377-385；apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:111-138；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:634-670；
      EV-0974/EV-0977。
  - id: F-007
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "截断无 token 已不再传播删除，但整页已确认对象被跳过，违反“只处理已确认对象”"
    summary: >-
      新代码在 `truncated && nextToken 为空` 时立即 `withComplete(false).withError(TRUNCATED)` 并
      `break`，发生在 `for (S3Object object : response.contents())` 之前。因此本轮**不会删除**未见对象
      （原 F-007 的安全目标已达成），但同一页里已经由源端确认的对象也不会被处理；Product M05-AC-11
      要求“不传播删除，只处理已确认对象，并在状态中标注部分/截断”。如果该页含新的受支持对象，本轮
      会丢失导入。测试 `truncatedWithoutToken` 只返回一个不支持类型的 `a.zip`，没有断言已确认对象被
      处理，无法暴露该回归。建议保留已确认对象处理，再在页尾标记不完整并跳过删除传播。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:311-317
      （break 在对象循环前）；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:590-631
      （只放 `a.zip`，未断言已确认对象）；docs/PRODUCT.md:561（M05-AC-11）。
  - id: F-008
    severity: P2
    status: accepted
    owner: riccon/lead
    due: TASK-0214 / B7b 启动前
    accepted_reason: >-
      TASK-0204 写范围不含 KnowledgeDownloadService；本轮已按 DEC-0041=A 创建 TASK-0214
      （M05b-B12b 连接器文档原文下载，3h，owner riccon/builder-kb-connector，planned，due B7b 前），
      明确要求按文档 ACL 返回平台对象流、key 与 B6 一致、无源对象 RICCON-KB-404、测试源桶≠平台桶。
      该 residual 有独立任务和截止，不在 B6 中造假实现。
    title: "连接器文档原文下载已转入 TASK-0214（DEC-0041=A）"
    summary: >-
      `KnowledgeDownloadService` 仍只处理 `file_id`，CONNECTOR 文档 `file_id=null` 会返回
      RICCON-KB-404；ADR §5.3/§6.3 与契约连接器段要求连接器原文可按文档 ACL 下载。该缺口已由
      TASK-0214 承接，B7b 启动前必须完成或明确扩展 B7b 范围。
    evidence: >-
      .riccon/tasks/TASK-0214.json；.riccon/decisions/DEC-0041.md；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:129-131。
  - id: F-009
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "externalId 两段路径改为展示最后两段，测试补 a/b"
    summary: >-
      `ConnectorExternalIds.display` 改为按 `/` 分段取最后两段；`a/b` 返回 `a/b`，`a/b/c.pdf` 返回
      `b/c.pdf`，再截断到 32 字符。`ConnectorExternalIdsTest` 增加 `a/b` 断言。证据：EV-0973/EV-0976。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/domain/ConnectorExternalIds.java:23-39；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorExternalIdsTest.java:13-23。
  - id: F-010
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0（流式硬上限残余见 notes）
    title: "下载临时文件在超限/总字节/校验失败时清理；平台写入前 100 MB 硬上限"
    summary: >-
      `download` 增加 `catch (DownloadFailure)` 统一 `deleteQuietly(temp)`；新增 `ConnectorObjectWriter`
      在上传平台桶前检查 100 MB。`totalBytesLimit_cleansTempFile` 使用临时文件工厂 spy 断言文件不存在。
      原临时文件泄漏已修复。大小检查仍在下载完成后；恶意/不实 list size 的源端仍可能在检查前把大对象
      写入本地临时目录（P3 资源残余，见 notes）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:552-588、
      :578-582；apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorObjectWriter.java:57-80；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:672-706；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorObjectWriterTest.java:20-38。
  - id: F-011
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      B6 写范围不含共享 AuditDetailsPolicy；Worker 本地 ConnectorAuditPolicy 当前键集合与 API
      AuditDetailsPolicy 一致，且 Worker 不写 created/updated/deleted/credential.changed。重复白名单的
      维护风险仍存在，但已有原评审 accepted 记录，本轮不因未改共享策略阻断。
    title: "Worker 本地 ConnectorAuditPolicy 保留，未折入共享策略"
    summary: >-
      本轮未修改 `ConnectorAuditPolicy`/`AuditDetailsPolicy`；键集合仍逐项一致。建议 M25 gate 前由
      Lead 决定是否合并，避免未来漂移。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorAuditPolicy.java:21-57；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:207-273。
  - id: F-012
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    accepted_reason: >-
      PAUSED 连接器 `nextSyncAt` 非空只影响响应可读性；Worker findDue 明确过滤 status='ACTIVE'，
      不会误调度。契约“连接器启用”可解释为功能开关，建议 gate 前统一文案/补测试。
    title: "PAUSED 连接器 scheduleEnabled=true 时仍返回 nextSyncAt"
    summary: >-
      本轮未改；`ConnectorCrudApiTest` 仍锁定创建默认 PAUSED 时 nextSyncAt 非空，调度器不会领取。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:176、
      :394-402；apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:195-215。
  - id: F-013
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "新增测试覆盖大部分原缺口，但仍缺 sync 路径索引失败/截断已确认对象断言"
    summary: >-
      本轮已补：平台管理员 8 端点 404、PATCH 重名 409、双桶写入/S3FileContentReader 读回、删除传播
      审计/事件失败回滚、同步中删除、截断无 token 不删除、临时文件清理、externalId 两段。
      但 F-016 的首次创建文档事务只由 `ConnectorDocumentWriteServiceIntegrationTest` 直接调用服务验证，
      生产 `ConnectorSyncService` 新文档路径未使用该服务；F-007 的截断页只放不支持对象，未断言已确认
      对象被处理。这两处仍是绿测未覆盖的生产路径。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDocumentWriteServiceIntegrationTest.java:39-136；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:590-631；
      `grep -R "createDocument(" apps/worker/src/main/java/.../connector` 仅 ConnectorDocumentWriteService
      定义，无 SyncService 调用。
  - id: F-014
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "短 accessKeyId 的掩码仍可能暴露 7/8 个字符"
    summary: >-
      `maskedHint` 对长度 ≥8 的 accessKeyId 取前 4 + 后 3；长度 8–10 的键几乎完整暴露。验证器允许
      最短 1 字符。accessKeyId 不是 secretAccessKey，但仍是凭据材料，建议对短键统一 `****`。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorCredentials.java:44-46、
      :152-156；apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorRequestValidator.java:304-305。
  - id: F-015
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "claimDue 仍把 updated_at 写成未来的 nextAt"
    summary: >-
      本轮未改 `ConnectorJdbc.claimDue`：`SET next_sync_at = :nextAt, updated_at = :nextAt`，nextAt 是
      未来时间，导致 ConnectorResponse.updatedAt 可能显示未来。应改为 `updated_at = :now`。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:226-241；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncScheduler.java:113-119。
  - id: F-016
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "首次创建文档路径仍绕过 ConnectorDocumentWriteService，事务只覆盖更新路径"
    summary: >-
      `ConnectorDocumentWriteService.createDocument` 未被任何生产代码调用；`ConnectorSyncService` 的
      新文档路径仍直接执行 `connectorRepository.insertDocument`、`markItemSynced`、`publishIndex`
      （:509-525），三者不在同一事务。若 insertDocument 后 markItemSynced 或 publishIndex 失败，会留下
      item PENDING/document_id 为空与已创建文档；下一次同步会把它当新对象再次 insert，产生重复文档。
      `ConnectorDocumentWriteServiceIntegrationTest` 只直接调用服务，未覆盖 SyncService 新文档路径，
      因此“文档写入+item SYNCED+enqueueIndex 同事务”的修复摘要与生产路径不一致。应让新文档路径调用
      `documentWriteService.createDocument`（或等价单事务），并补 sync 路径失败测试。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:502-525；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDocumentWriteService.java:63-98；
      `grep -R "createDocument(" apps/worker/src/main/java/com/riccon/worker/connector` 只返回
      ConnectorDocumentWriteService 定义；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDocumentWriteServiceIntegrationTest.java:39-136。
  - id: F-017
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: B7b 启动前
    title: "isSyncable 与 insertDocument 之间仍有 check-then-act 窗口"
    summary: >-
      `processObject` 在对象处理前调用 `isSyncable`，但之后的 `findOrCreateItem`、`insertDocument` 与
      `markItemSynced` 没有行锁/条件插入；管理员可在检查通过后立即软删除连接器，仍可能新增文档。
      测试的删除发生在 list 回调内，早于 `isSyncable`，只覆盖确定路径。建议把“连接器仍可同步”与文档
      写入放入同一事务/条件写入，或对 kb_connector 行加锁；列为 P3 残余。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:377-385、
      :502-525；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:634-670。
  - id: F-018
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "删除传播事务未保留 DuplicateKeyException 的幂等处理"
    summary: >-
      旧实现 `propagateDeletes` 捕获 `DuplicateKeyException` 视为 DELETE 事件已投递；新
      `ConnectorDeletePropagationService` 不再捕获，`eventPublisher.enqueueDelete` 的重复键会回滚本次
      item/文档传播并让同步事件以 ERROR 重试。正常路径难以触发，但与 API 删除并发时可能出现
      “事件已在、传播反复失败”。建议恢复幂等吞掉重复键或使用 insert-if-absent。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDeletePropagationService.java:71-75；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorEventPublisher.java:100-121。
test_impact: >-
  Delta 独立复跑（HEAD d87c8cc，工作树无生产改动）：
  EV-0976 API 5 类（ConnectorCrud/CredentialSync/DocumentRead/ExternalIds/KnowledgeAcl）→ exit 0，
  21 tests / 0 failures / 0 errors / 0 skipped（Crud 10、Credential 4、DocumentRead 1、ExternalIds 1、
  KnowledgeAcl 5）；
  EV-0977 Worker `Connector*Test` → exit 0，33 tests / 0/0/0（Audit 1、DeletePropagation 2、
  DocumentWrite 1、Domain 2、Endpoint 5、ObjectWriter 1、Properties 2、Budget 2、Completion 1、
  SyncIntegration 16）；
  EV-0978 ArchUnit `ModuleBoundaryTest` → exit 0，12 tests / 0/0/0。
  Lead 证据 EV-0973（API，含 KnowledgeDeleteReindex/KnowledgeDownload 的 7 类）、EV-0974（Worker
  `Connector*Test` 33）、EV-0975（ArchUnit）均为 commit=f745dc6、dirty=false、exit=0，日志哈希与
  JSON 一致。`git diff --name-status 85878b71..9275eb0` 共 17 文件，全部在 TASK-0204 写范围；
  `git diff --check 85878b71..9275eb0` exit 0；无 contracts/docs/deploy/migration 改动。
  未运行 M25 全量 test/coverage/E2E/a11y/perf；所有定向测试未使用 skip/retry/额外串行参数。
notes: >-
  Delta 复核提交：9275eb0（父 85878b71，17 文件）已 merge 到 f745dc6；当前 HEAD d87c8cc 是
  Lead 的证据/状态提交。原两个 P1 已关闭；原 F-003/F-004/F-005/F-006/F-009/F-010 的原始缺陷已修复。
  但 F-016（首次创建文档仍非事务）与 F-007（截断页丢弃已确认对象）是生产路径问题，不能仅凭新增
  测试判 fixed；F-013 的测试缺口仍存在。P3 残余：F-014 短掩码、F-015 未来 updated_at、F-017
  check-then-act、F-018 删除事件重复键；F-010 的流式下载硬上限仍在下载完成后检查（依赖源端 list size），
  F-003 的重复键幂等见 F-018。TASK-0214（B12b 下载，DEC-0041=A）已创建，F-008 有独立承接。
  `riccon gate check --module M25` 仍 FAIL：TASK-0204 review、TASK-0205..0209/0211..0213 planned、
  无模块 review；本任务 verdict=fail 还因 open P2 F-007/F-013/F-016。未修改任务 status/源码/测试/契约。
created_at: "2026-11-24T07:48:00+08:00"
---

# REV-TASK-0204-001：TASK-0204（M05b-B6 S3/OSS 连接器）独立评审（含 delta 复核）

## 结论

**不通过（fail）。** 返工提交 `9275eb0` 真实关闭了两个 P1：

1. **F-001**：平台桶写入已落地，源客户端不再 PutObject；双桶测试 + `S3FileContentReader` 读回证明生产路径。
2. **F-002**：8 个连接器端点改为统一 `authorize()`，平台管理员 404、员工 403、他租户 404 均有测试。

F-003（删除传播事务）、F-004（ensureIndexEvent 补投）、F-005（PATCH 409）、F-006（同步中删除）、F-009（externalId）、F-010（临时文件清理）的原始缺陷也已修复。

但 delta 复核发现 **3 个 open P2**，不能判 pass：

- **F-016**：`ConnectorDocumentWriteService.createDocument` 未被生产代码调用；首次创建文档仍直接 `insertDocument` + `markItemSynced` + `publishIndex`，不在同一事务。事件失败路径有 ensureIndexEvent 恢复，但 insert 后失败会留下 orphan/重复文档；新测试只直接测服务，未覆盖 sync 路径。
- **F-007**：截断且无 token 时现在在对象循环前 `break`，虽然“不删除未见对象”已满足，但同一页已确认对象也被丢弃，违反 M05-AC-11“只处理已确认对象”；测试只放不支持类型，未断言。
- **F-013**：上述两条生产路径没有对应负向测试。

P3 残余：F-014 短掩码、F-015 `claimDue.updated_at` 未来时间、F-017 isSyncable 与 insert 的 check-then-act 窗口、F-018 删除事件重复键幂等。F-008（连接器下载）已 accepted 并转 TASK-0214/DEC-0041。

## Delta 复核范围

- `git diff --name-status 85878b71..9275eb0`：17 文件，全部在 TASK-0204 write_scope（API connector 主/测试、Worker connector 主/测试）；无 contracts/docs/deploy/migration/.riccon 生产改动。
- `git diff --check 85878b71..9275eb0`：exit 0。
- 新文件：`ConnectorObjectWriter`、`ConnectorTempFileFactory`、`ConnectorDocumentWriteService`、`ConnectorDeletePropagationService`；新增 3 个 Worker 测试类，修改 Sync/API 测试。
- 合并链：`9275eb0`（父 `85878b71`）→ `f745dc6`（merge）→ `d87c8cc`（状态/证据）；当前 HEAD `d87c8cc` 与 `f745dc6` 的 production 代码相同（仅 `.riccon` 状态/证据差异）。

## 验证了什么

- **F-001 平台桶**：`ConnectorObjectWriter` 注入平台 `S3Client` + `StorageProperties`；同步源 client 只用于 list/get；`ConnectorSyncIntegrationTest` 的 `SOURCE_BUCKET = BUCKET + "-source"` 与平台 `BUCKET` 分离，首次同步后用 `S3FileContentReader` 按平台 key 读回内容，并断言源桶下无 `tenants/` 对象。
- **F-002 权限**：8 个端点 `authorize()` 先判平台租户 → 404，再 `AuthService.requirePermission` → 员工 403；服务层租户过滤 → 他租户 404。`ConnectorCrudApiTest` 新增 8 端点平台管理员 404、PATCH 重名 409。
- **F-003 事务**：`ConnectorDeletePropagationService` `@Transactional`；审计/事件失败测试断言 item SYNCED、文档 deleted_at null。
- **F-004 补投**：`ensureIndexEvent` 在 ETag 未变和 checksum 相同分支补投 PENDING 文档索引；`ConnectorDocumentWriteService.updateDocument` 更新路径事务化。但首次创建路径见 F-016。
- **F-005 PATCH**：DuplicateKeyException → ConnectorConflictException 409，测试覆盖。
- **F-006 删除竞态**：markSyncStarted=0 中止；每对象 isSyncable 复核；测试删除后 documents 为空。
- **F-007 截断**：不再删除未见对象，但把整页已确认对象一起跳过；见 open finding。
- **F-009/F-010**：externalId 两段路径测试、临时文件清理测试、100 MB 写入前上限。
- **独立复跑**：EV-0976 API 21/0/0/0；EV-0977 Worker 33/0/0/0；EV-0978 ArchUnit 12/0/0/0；Lead EV-0973/0974/0975 均为 f745dc6、dirty=false、exit 0。
- **未验证**：M25 全量 test/coverage/E2E/a11y/perf/security、真实浏览器/UI（无 UI 本任务）、真实客户源桶生产网络、F-008 下载实现（TASK-0214 未启动）。

## AC 对账（任务卡 7 条）

| AC                                                                              | 结论                     | 依据/残余                                                                                              |
| ------------------------------------------------------------------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------ |
| 1. CRUD/凭据/测试/202/409/契约真实实现                                          | **pass**                 | F-002/F-005 已修；平台管理员 404、员工 403、他租户 404、PATCH 409、凭据掩码/加密、test/sync 均有测试。 |
| 2. 首次/增量/ETag/未变化/完整 list 删除/tombstone/限额/PARTIAL/重试/last_sync_* | **pass (with residual)** | 原同步能力保留，EV-0974/0977 全绿；F-007 截断页丢弃已确认对象、F-016 首次创建事务化仍 open。           |
| 3. 端点 allowlist + SSRF                                                        | **pass**                 | API/Worker policy 同源，私网 DNS 正反例、元数据/链路本地、redirect、SDK 禁重定向均在；本轮未回退。     |
| 4. 审计同事务 fail-closed/details 白名单/租户停用                               | **pass (with residual)** | F-003 删除传播事务已修；F-018 重复键幂等为 P3；F-011 本地策略 accepted。                               |
| 5. 真实 RustFS/无泄露/调度/不改 contracts                                       | **pass**                 | F-001 双桶测试真实通过；F-008 下载转 TASK-0214（accepted）；契约无改动。                               |
| 6. CONNECTOR 列表/详情 connectorId/externalId/UPLOAD null/读路径                | **pass**                 | API 读路径测试继续通过；F-009 两段 externalId 已修。                                                   |
| 7. finishSync 与 sync.completed/sync.failed 同短事务 fail-closed                | **pass**                 | `ConnectorSyncCompletionService` `@Transactional` + 完成审计失败回滚测试继续通过。                     |

## 未验证

- 未跑 M25 模块全量 `test/coverage`、`test_e2e`、`a11y`、`perf`、`security` 合同命令；由模块 gate 承接。
- 未运行真实浏览器/UI（本任务无 UI 改动）。
- 未在真实客户源端点/源桶与平台桶分离的生产网络复现；双桶测试使用真实 RustFS 的两个 bucket。
- 未运行连接器下载（TASK-0214 未启动）。
- 未复现 F-017 极小 check-then-act 竞态、F-018 重复键并发窗口；为静态分析残余。

## Findings

详见 front matter。Delta 结论：

- **P1（0 open）**：F-001/F-002 fixed。
- **P2（3 open + 1 accepted）**：
  - F-016 首次创建文档未使用事务服务（open）。
  - F-007 截断页丢弃已确认对象（open）。
  - F-013 sync 路径负向测试缺口（open）。
  - F-008 下载转 TASK-0214（accepted）。
- **P3**：F-014 短掩码、F-015 claimDue updated_at、F-017 check-then-act、F-018 重复键幂等为 open；F-011/F-012 为 accepted。

## 剩余风险与下一步

1. **F-016**：让 `ConnectorSyncService` 新文档路径调用 `ConnectorDocumentWriteService.createDocument`，并补 sync 路径 Mockito 事件失败/DB 失败测试；否则“文档写入 + item SYNCED + enqueueIndex 同事务”的修复只覆盖 update。
2. **F-007**：保留已确认对象处理，页尾标记 `TRUNCATED/complete=false` 后跳过删除传播；补一个“截断页含新受支持对象”的测试。
3. **F-013**：补 sync 路径的 F-016/F-007 用例，并把 `ConnectorDocumentWriteServiceIntegrationTest` 的直接服务测试改为至少一条从 `ConnectorSyncHandler`/`ConnectorSyncService` 进入的测试。
4. **F-017/F-018/F-015/F-014**：P3 残余，按 due 在 M25 gate/B7b 前处理。
5. **F-008**：TASK-0214 完成前，B7b 不应假设连接器下载可用；下载 key 必须与 B6 平台桶一致。
6. M25 gate 仍需全量 test/coverage/E2E/a11y/perf/security；本任务定向证据只支撑 TASK-0204。

## Gate

`riccon gate check --module M25` 当前 FAIL（预期）：TASK-0204 仍 review、TASK-0205..0209/0211..0213 planned、无模块 review。本任务自身 verdict=fail；建议 Lead **暂不标 done**，先处理 F-016/F-007/F-013。B7b 可继续做不依赖连接器索引读取的准备工作，但在 F-016/F-007 修复并复核前不应消费连接器同步事件/索引。
