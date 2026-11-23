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
head_commit: 9012edb9ffe3157a7ce7a5a2da684c1bb5beaed0
baseline_commit: 81bd9c6a5383289edb3719f253f6ee4feb4d0380
dod:
  acceptance: false
  build: true
  tests: false
  security: false
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
  TASK-0204 是连接器后端任务，适用 acceptance/tests/security/review/evidence；build 由 Maven
  test-compile 与 ArchUnit ModuleBoundaryTest 覆盖；docs 由同级 O2a 负责，本任务没有 docs 写范围；
  coverage/e2e/migration/perf/a11y 由 M25 模块 gate 在最终提交上跑合同命令，本任务不冒充已通过。
  acceptance/tests/security 为 false：存在 2 个 P1（源桶写入、平台管理员 403 而非 404）及多个
  覆盖 AC 的 P2；定向测试虽全绿，但缺少证明这些 AC 的负向用例。
reviewed_evidence:
  - EV-0967
  - EV-0968
  - EV-0969
  - EV-0970
  - EV-0971
  - EV-0972
evidence:
  - EV-0967
  - EV-0968
  - EV-0969
  - EV-0970
  - EV-0971
  - EV-0972
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前；B7b 启动前必须关闭
    title: "连接器原文被写入源端 bucket/源端客户端，而非平台对象存储，B7b 与知识库下载将读不到对象"
    summary: >-
      同步在下载源对象后调用 `writeObject`，但 `PutObjectRequest.bucket` 使用 `connector.config().bucket()`
      （源端 bucket），S3 客户端也是按源端点/源凭据构建的一次性 client。于是导入原文写入客户源桶的
      `tenants/{tenantId}/connector/{yyyy}/{documentId}`，而不是平台 `RICCON_S3_BUCKET`。这与 ADR-0009 §6
      的 `file|artifact|export` 平台 key 类别扩展、ADR-0023 §6.2/§6.3 的“原文存对象存储”冲突：B7b 的
      `S3FileContentReader` 使用 `StorageProperties.bucket()` 读平台桶，将找不到连接器对象；典型只读源凭据
      （连接测试只验证 ListObjectsV2，不验证 PutObject）会导致每个对象下载成功、上传失败、整次同步 PARTIAL；
      源前缀为空时还会把服务端写回的对象再次当作源对象摄入，形成自摄入/放大，甚至把其他租户写入的副本带入
      本租户。测试使用同一个 RustFS 桶同时充当源桶与平台桶，掩盖了该缺陷。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:436-438、
      :460-461（派生 key，但写入用源 client）、:575-584（`bucket(connector.config().bucket())`）；
      apps/worker/src/main/java/com/riccon/worker/parse/service/S3FileContentReader.java:44-50（平台桶
      `StorageProperties.bucket()`）；docs/adr/ADR-0009-tenant-isolation-enforcement.md §6；docs/adr/ADR-0023-m05-knowledge-depth.md
      §6.2/§6.3；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:558
      与 apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:73、:253（源桶与平台桶同为
      `BUCKET`）；apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorApiTestSupport.java:41-42、:88、:135
      （同样同桶）。复现：部署源端点/源 bucket 与平台 `RICCON_S3_BUCKET` 不同，使用只有 List/Get 权限的源凭据
      触发 sync；预期连接器对象出现在平台桶并可由 B7b/下载读取，实际写入源桶，平台桶无对象。
  - id: F-002
    severity: P1
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "平台管理员访问连接器接口返回 403 RICCON-AUTH-403，而 M05-AC-10（M05b 段）/ADR §5.4 要求统一 404 RICCON-TENANT-404"
    summary: >-
      所有 8 个连接器端点都在 controller 上声明 `@RequirePermission`；`PermissionInterceptor` 在进入
      controller/服务层前调用 `AuthService.requirePermission`，平台管理员角色不持有
      `knowledge.connector.read/manage`，因此直接得到 403。M05-AC-10（M05b 段）明确要求“平台管理员统一 404
      RICCON-TENANT-404”，ADR-0023 §5.4 的 403/404 矩阵也把“平台管理员访问租户连接器端点”归为 404；
      普通员工 403 是可接受的。由于权限检查先于租户/对象查询，返回体不会因 connectorId 是否存在而变化，
      但仍不符合 AC 的响应码与跨租户不可区分要求。现有 `ConnectorCrudApiTest` 只覆盖“员工 403、他租户
      管理员 404”，没有平台管理员用例。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/controller/ConnectorController.java:101-102、
      :124-126、:149-150、:173-174、:198-199、:220-221、:236-238、:262-263；
      apps/api/src/main/java/com/riccon/api/auth/controller/PermissionInterceptor.java:59-63；
      apps/api/src/main/java/com/riccon/api/auth/service/AuthService.java:362-366；
      apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:87-100（平台管理员权限集合不含
      连接器权限）；contracts/openapi.yaml:1244、:1415、:1445、:1477；docs/PRODUCT.md:560（M05-AC-10 的 M05b 段）；
      docs/adr/ADR-0023-m05-knowledge-depth.md:353-363（§5.4）。复现：平台管理员会话
      `GET /api/v1/knowledge/connectors` 或任意连接器端点 → 403 RICCON-AUTH-403；期望 404 RICCON-TENANT-404。
  - id: F-003
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "删除传播的 item、文档软删除、document.deleted 审计与 DELETE 索引事件不在同一事务，审计/事件失败不回滚"
    summary: >-
      `propagateDeletes` 先 `markItemDeleted`、再 `softDeleteDocument`（各自经 `TenantJdbc` 自动提交），
      之后才写 `knowledge.document.deleted` 审计并投递索引 DELETE；`ConnectorSyncService`/`ConnectorAuditWriter`
      都没有事务边界。若审计写失败，文档已软删除且 item 已 DELETED，后续重试的 `findUnseen` 不再包含该 item，
      审计永久缺失，违反 AC-16“审计写入失败时对应管理操作失败”和 §6.6 fail-closed。若索引事件投递失败，
      文档已删除但 DELETE 索引事件永久缺失，TM-63“源端删除传播”不完整。`ConnectorSyncCompletionIntegrationTest`
      只覆盖 finishSync 的终态审计回滚，没有覆盖本路径。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:492-521；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorAuditWriter.java:227-239；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorEventPublisher.java:100-121；
      apps/worker/src/main/java/com/riccon/worker/persistence/TenantJdbc.java:89-110（单语句自动提交）；
      docs/adr/ADR-0023-m05-knowledge-depth.md:401、:609-619（§5.6/§6.6）；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncCompletionIntegrationTest.java:49-113
      （仅覆盖 complete）。复现：mock/使 `ConnectorAuditWriter.documentDeleted` 抛异常后触发源端删除同步；
      预期文档/条目与审计同回滚，实际文档与条目已删除、审计无记录。
  - id: F-004
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "索引事件投递失败后文档停留在 PENDING；下一次同步 checksum 相同路径不再补投索引事件"
    summary: >-
      新建文档路径先插入 `knowledge_document(status=PENDING)`、标记 item SYNCED，再 `publishIndex`；更新路径
      先更新 checksum/置 PENDING、标记 item SYNCED，再 `publishIndex`。`publishIndex` 只吞
      `DuplicateKeyException`，其他 DataAccessException 被 `processObject` 的 RuntimeException 捕获后把 item
      标成 FAILED。下一次同步重新下载后，若文档 checksum 与源一致，`processSupportedObject` 的
      `downloaded.checksum().equals(document.checksum())` 分支只 `markItemSynced` 并返回，不补投索引事件；
      文档永久 PENDING，B7b 不会索引。该路径违反映射 AC-11/12/15 的“创建/更新后投递索引”和可重试语义。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:423-487
      （create/update 后 publish）、:598-611（只吞 DuplicateKey）、:416-421（未变化/checksum 相同直接返回）、
      :381-397（异常标记 FAILED）；docs/adr/ADR-0023-m05-knowledge-depth.md:581-582、:589。复现：mock
      `ConnectorEventPublisher.enqueueIndex` 抛非 duplicate 的 DataAccessException 执行首次同步；预期重试会补投，
      实际文档保持 PENDING 且 item SYNCED。
  - id: F-005
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "PATCH 改名到已存在名称返回 500，而合同要求 409 RICCON-KB-409"
    summary: >-
      `ConnectorService.update` 直接调用 `connectorRepository.updateConnector`，没有捕获名称唯一键
      （`uk_kb_connector_name`）冲突；`ConnectorService.create` 有 DuplicateKeyException→ConnectorConflictException
      的映射，update 没有。异常落到 `GlobalExceptionHandler` 的兜底 `Exception` 分支，返回 500
      RICCON-INTERNAL-500。契约明确“名称冲突返回 409 RICCON-KB-409”，现有测试只覆盖 create 重名 409。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:230-244；
      apps/api/src/main/java/com/riccon/api/web/GlobalExceptionHandler.java:276-289；
      contracts/openapi.yaml:1313；apps/api/src/main/resources/db/migration/V12__knowledge_depth.sql（
      `uk_kb_connector_name`）。复现：创建 A、B 两个连接器，PATCH B `{"name":"A"}` → 500；期望 409
      RICCON-KB-409。
  - id: F-006
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "连接器在同步过程中被软删除时，进行中的同步仍可能继续创建文档"
    summary: >-
      Handler 在开始同步前检查 `connector.deletedAt() == null`，但随后 API 可以并发软删除连接器；
      `synchronize` 的 `markSyncStarted` 返回 0 时没有中止，仍用旧 `ConnectorRecord` 下载并创建文档。
      API 的 `documents=SOFT_DELETE` 只删除删除时刻已存在的文档，之后创建的文档会留在知识库中，表现为
      已删除连接器仍产生可见内容。应在开始/每个对象写入前用条件更新或行锁确认连接器仍 ACTIVE/未删除。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncHandler.java:105-110；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:151-154；
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:306-321。复现：先启动
      一个慢同步（大对象/延迟端点），在同步进行中 DELETE connector?documents=SOFT_DELETE；预期不再创建新文档，
      实际可能新增文档。
  - id: F-007
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前（可与 F-003 一起修）
    title: "list 响应 isTruncated=true 但 nextContinuationToken 为空时被当作完整 list 并传播删除"
    summary: >-
      `runSync` 仅在 `Boolean.TRUE.equals(response.isTruncated())` 时设置 continuation，下一轮循环条件只检查
      continuation 是否为 null；如果 SDK/兼容实现返回 `isTruncated=true` 且 token 为空（或空串被当作 null），
      循环正常退出，`state.complete` 仍为 true，随后 `propagateDeletes` 会删除本轮未见对象。这与
      “只有完整 list 才传播删除；SDK 截断一律不删”冲突，属于 fail-open。应对 `isTruncated=true` 但无 token
      显式置 `complete=false` 并记录错误码。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:263-317
      （尤其 :312-316）；docs/adr/ADR-0023-m05-knowledge-depth.md:579。复现：mock ListObjectsV2 返回
      `isTruncated=true` 且 `nextContinuationToken=null`，先同步 2 个对象，再只返回 1 个对象；预期不删除未见对象，
      实际会传播删除。
  - id: F-008
    severity: P2
    status: accepted
    owner: riccon/lead
    due: TASK-0205（B7b）启动前 / M25 gate 前
    accepted_reason: >-
      TASK-0204 的写范围只扩展了 `KnowledgeService` 等读路径文件，没有 `KnowledgeDownloadService` 或
      `FileStorage`；任务 AC-6 只要求列表/详情读路径，B6 也确实按“创建文档 + 投递索引事件”交付。但
      ADR-0023 §5.3/§6.3 与 `contracts/openapi.yaml` 的连接器段明确“连接器原文下载仍按文档 ACL 与既有下载
      端点执行”，当前 `fileId=null` 的 CONNECTOR 文档会被下载服务按无源文件返回 RICCON-KB-404。该缺口不影响
      B6 的读列表/详情，但会在 M05b 用户闭环（打开连接器文档原文）失败；需在 B7b 前由 Lead 决定补任务或扩展
      B7b 写范围，不能在模块 gate 时静默忽略。
    title: "连接器文档原文下载未实现：KnowledgeDownloadService 对 fileId=null 的 CONNECTOR 文档直接返回 RICCON-KB-404"
    summary: >-
      M05 下载服务只解析 `knowledge_document.file_id` 到 `file` 行再读平台对象；连接器文档按设计
      `file_id=null`、只有 `connector_id/external_id`，因此即使 ACL 通过也必然抛
      `KnowledgeSourceNotFoundException`（RICCON-KB-404）。ADR §5.3 要求下载服务按“上传文件或连接器对象”
      解析源对象，§6.3 说“原文下载仍按文档 ACL 与既有下载端点执行”。B6 没有实现该分支，B7b 写范围也未包含
      `KnowledgeDownloadService`，属于模块级 residual，需在 B7b 启动前补齐任务边界。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:129-131；
      docs/adr/ADR-0023-m05-knowledge-depth.md:319-322、:589；contracts/openapi.yaml:63（连接器段）；
      docs/adr/ADR-0009-tenant-isolation-enforcement.md §6（平台对象存储 key）。复现：创建 CONNECTOR 文档后调用
      `GET /api/v1/knowledge/documents/{id}/download`（ACL 已通过）→ 404 RICCON-KB-404；预期返回原文件流。
  - id: F-009
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "externalId 末段展示对恰好两段路径只返回最后一段，与“最后 2 个路径段”契约文字不完全一致"
    summary: >-
      `ConnectorExternalIds.display("a/b")` 返回 `b`，而不是 `a/b`；对三段及以上才返回最后两段。契约
      `ConnectorItem.externalId`/端点描述使用“最后 2 个路径段或 32 字符”。这是少显示而非多泄露，安全影响
      有界，但要在 M25 gate 前统一口径或补 `a/b` 测试并修正。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/domain/ConnectorExternalIds.java:30-35；
      contracts/openapi.yaml:1476、:5974-5977；apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorExternalIdsTest.java:13-22
      （无两段用例）。
  - id: F-010
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "超限/总字节超限/校验失败时下载临时文件未删除，且下载期间没有硬字节上限"
    summary: >-
      `download` 先 `Files.createTempFile`，写入完成后才检查 size/maxTotalBytes；`OBJECT_TOO_LARGE`、
      `TOTAL_BYTES_LIMIT` 与 `sha256Hex` 抛出的 `DownloadFailure` 不在 S3/SDK/IOException 的 catch 内，
      不会调用 `deleteQuietly(temp)`，会在临时目录留下大文件。S3 清单大小不可信时，流式写入也没有
      100 MB 硬上限，存在磁盘耗尽风险（TM-69）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:529-553；
      docs/adr/ADR-0023-m05-knowledge-depth.md:604-607（§6.5）。复现：设置 `maxObjectBytes` 小于实际对象，
      或让 `maxTotalBytes` 在下载后触发，检查 `/tmp/riccon-connector-*` 残留。
  - id: F-011
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      B6 写范围不含共享 `AuditDetailsPolicy`，Worker 本地 `ConnectorAuditPolicy` 的已写动作键集合与
      API `AuditDetailsPolicy` 的连接器条目逐键一致（sync.started/completed/failed/retried、status.changed、
      document.deleted），且 Worker 不写 created/updated/deleted/credential.changed；当前没有发现漂移。
      重复白名单确有维护风险，建议 M25 gate 前由 Lead 决定是否折入共享策略；本任务不因此阻断。
    title: "Worker 本地 ConnectorAuditPolicy 复制 API 白名单，存在未来漂移风险"
    summary: >-
      Worker 在 `ConnectorAuditPolicy` 维护一份连接器审计 details 白名单，API 在 `AuditDetailsPolicy`
      维护另一份；两者当前键集合一致，但没有任何测试比较两份白名单，新增字段时容易只改一侧导致审计
      写入 fail-closed 或读端拒绝。B6 的注释已说明因写范围限制暂不修改共享策略。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorAuditPolicy.java:21-57；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:207-273；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorAuditPolicyTest.java:16-52（只测
      sync.completed 一个动作）。
  - id: F-012
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    accepted_reason: >-
      `ConnectorStatus.PAUSED` 仍有 `nextSyncAt` 只影响响应可读性；Worker `findDue` 明确要求
      `status='ACTIVE' AND schedule_enabled=TRUE`，暂停连接器不会被调度。契约“连接器启用”可解释为
      功能开关 `RICCON_CONNECTOR_ENABLED` 而非连接器状态；建议在 M25 gate 前统一文案/补测试，不影响
      当前安全与调度行为。
    title: "PAUSED 连接器在 scheduleEnabled=true 时仍返回 nextSyncAt，调度器不会领取"
    summary: >-
      创建/更新时只要 `scheduleEnabled=true` 就计算 `nextSyncAt`，不区分 `status=ACTIVE|PAUSED`；
      `ConnectorCrudApiTest` 还锁定创建（默认 PAUSED）时 `nextSyncAt` 非空。Worker 到期查询会过滤
      `status='ACTIVE'`，所以不会误触发，但响应语义与“仅 scheduleEnabled=true 且连接器启用时非空”的
      契约文字存在解释差异。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:176、:394-402；
      contracts/openapi.yaml:5750-5753；apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:195-215；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCrudApiTest.java:46-53。
  - id: F-013
    severity: P2
    status: open
    owner: riccon/builder-kb-connector
    due: TASK-0204 done 前
    title: "关键 AC 的负向用例缺失，现有全绿不能证明 AC-09/10/11/12/15/16"
    summary: >-
      现有 29+26+12 条定向用例全部通过，但缺少以下验收用例：平台管理员连接器端点 404（F-002）、PATCH
      重名 409（F-005）、删除传播审计失败回滚与 DELETE 索引事件失败（F-003）、索引事件投递失败后文档
      PENDING 的恢复（F-004）、同步进行中删除连接器（F-006）、isTruncated=true 无 token（F-007）、
      连接器文档下载（F-008）；同步集成测试还把源桶与平台桶设为同一个 `BUCKET`，无法发现 F-001。
      这些不是“零测试”，而是覆盖 AC 的负向证据缺口；在补齐前 `tests` DoD 不能判 true。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:547-560、:655-665；
      apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:73、:253；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCrudApiTest.java:158-187
      （无平台管理员）；apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCredentialSyncApiTest.java:154-201
      （无 PATCH 重名）。
  - id: F-014
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "短 accessKeyId 的掩码会暴露 7/8 个字符"
    summary: >-
      `maskedHint` 对长度 ≥8 的 accessKeyId 永远取前 4 + 后 3；长度为 8-10 时几乎完整暴露 access key ID。
      验证器允许 accessKeyId 最短 1 字符，因此可构造 `abcdefgh` 之类的短 key。accessKeyId 不是 secretAccessKey，
      但仍属凭据材料；建议对长度 < 12/16 的键统一 `****`，并补单测。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorCredentials.java:44-46、:152-156；
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorRequestValidator.java:304-305。
  - id: F-015
    severity: P3
    status: open
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    title: "claimDue 把 updated_at 写成未来的 nextAt，连接器 updatedAt 可能显示未来时间"
    summary: >-
      调度器条件领取到期连接器时执行 `SET next_sync_at = :nextAt, updated_at = :nextAt`，其中 `nextAt`
      是 `now + syncInterval`；这会把 `updated_at` 写成一个未来时间。`ConnectorResponse.updatedAt` 会向
      管理端返回未来时间；后续 API 更新会写回当前时间，但期间列表/审计时间语义不自然。应改为
      `updated_at = :now`（领取时的时间）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:226-241；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncScheduler.java:113-119；
      contracts/openapi.yaml:5787-5789（`Connector.updatedAt`）。
test_impact: >-
  独立复跑（当前 HEAD 9012edb，工作树无生产改动）：
  EV-0970 = `RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=builder-b6 ./mvnw -q -T1C
  -pl apps/api -am -Dtest='ConnectorCrudApiTest,ConnectorCredentialSyncApiTest,ConnectorDocumentReadApiTest,
  ConnectorExternalIdsTest,KnowledgeAclApiTest' -Dsurefire.failIfNoSpecifiedTests=false test` → exit 0，
  29 tests / 0 failures / 0 errors / 0 skipped；
  EV-0971 = worker 同 namespace 定向命令（完成/同步/SSRF/预算/审计）→ exit 0，22 tests / 0/0/0；
  EV-0972 = `ModuleBoundaryTest` → exit 0，12 tests / 0/0/0。
  核对 Lead 证据：EV-0967 = 29 tests（含 KnowledgeDeleteReindex/KnowledgeDownload 回归），EV-0968 = 26
  tests（含 ConnectorDomain/Properties），EV-0969 = ArchUnit；三个证据 commit=f31e180、dirty=false、exit=0，
  日志哈希与 JSON 一致，surefire 计数与 note 相符。EV-0971 的 selector 未包含
  `ConnectorDomainTest/ConnectorPropertiesTest`，这两类由 EV-0968 的 26 条覆盖，未在 verifier 侧独立复跑。
  未运行模块全量 test/coverage/E2E/a11y/perf；未修改生产源码、测试、迁移或契约。所有定向测试均未使用
  skip/retry/额外串行参数。
notes: >-
  reviewed baseline 81bd9c6，任务最终代码提交 85878b71（父 bd515cb），合并提交 f31e180；当前 HEAD
  9012edb 仅为 Lead 的 EV/评审记账提交，代码内容与 f31e180 相同。独立复跑在 HEAD 9012edb、工作树干净时
  录制；EV-0970/0971/0972 为 verifier 新增证据，未改变任务 status/owner/acceptance。`git diff
  --name-status 81bd9c6..85878b71` 共 82 文件，全部在任务 write_scope（API connector 主/测试、
  KnowledgeSourceType/KnowledgeDocumentRecord/KnowledgeDocumentResponse/KnowledgeJdbc/KnowledgeService、
  Worker connector 主/测试）；无 contracts/docs/deploy/migration 改动，未发现 B7b 索引分支。
  `riccon gate check --module M25` 当前 FAIL 属预期：TASK-0204 review 未 done、TASK-0205..0209/0211..0213
  planned、无模块 review；本任务 verdict=fail 还因 F-001/F-002 两个 P1，不应由 Lead 直接标 done。
created_at: "2026-11-24T07:12:00+08:00"
---

# REV-TASK-0204-001：TASK-0204（M05b-B6 S3/OSS 连接器）独立评审

## 结论

**不通过（fail）。** 连接器 CRUD、凭据信封加密/只写不读、SSRF allowlist、首次/增量/删除/tombstone、同步终态审计同事务等主体能力已经落地，EV-0967/0968/0969 与独立复跑 EV-0970/0971/0972 均为 exit 0；但存在两个 P1：

1. **F-001**：同步把连接器原文写入**源端** bucket/源端客户端，而不是平台对象存储。B7b 的平台 `S3FileContentReader`/知识库下载读不到对象；典型只读源凭据无法上传；源前缀为空时还可能自摄入/跨租户污染。测试同桶掩盖了该问题。
2. **F-002**：平台管理员访问连接器端点返回 **403**，M05-AC-10（M05b 段）/ADR §5.4 要求 **404 RICCON-TENANT-404**。

此外有多个 P2（删除传播审计/索引事件非事务、索引事件失败后 PENDING 无恢复、PATCH 重名 500、同步/删除竞态、截断 list fail-open、连接器下载缺口、关键负向测试缺失）。因此 `acceptance/tests/security` DoD 为 false，不能建议 Lead 将 TASK-0204 标 done。

## 验证了什么

- **范围与产物**：按任务卡、`AGENTS.md` 合同、ADR-0023 §5.4/§5.6/§6.2–6.7/§7.3–7.5/§8/§12.1、`contracts/openapi.yaml` 1.5.0-m05b、RUNBOOK O2a 逐项核对；`git diff --name-status 81bd9c6..85878b71` 82 个文件全部在 write_scope，没有 contracts/docs/deploy/migration 改动，没有 B7b 索引分支。
- **静态审计**：`ConnectorController/Service/SyncService/TestService/Credentials/RequestValidator/EndpointPolicy/EndpointRegistry/AuditEvents/IndexEvents/SyncEvents/Persistence` 和 Worker `ConnectorSyncService/Handler/Scheduler/S3ClientFactory/Credentials/AuditWriter/AuditPolicy/Persistence`；Knowledge 读路径的 additive 改动；API/Worker `ConnectorEndpointPolicy` 的 diff 仅包名不同；S3 SDK 2.59.2 的 `Apache5HttpClient` 确认调用 `disableRedirectHandling()`。
- **定向测试**：EV-0970 API 29/0/0/0；EV-0971 Worker 22/0/0/0；EV-0972 ArchUnit 12/0/0/0；EV-0967/0968/0969 的 commit=f31e180、dirty=false、exit=0 与日志哈希已核对。
- **合同/权限**：8 个端点、CSRF/Idempotency-Key 参数、错误码、Connector/ConnectorCreate/Update/Credential/Test/SyncAccepted/ItemPage schema 与实现基本一致；跨租户租户管理员 404、普通员工 403 有测试；平台管理员 404 缺失。
- **安全**：凭据 purpose/AAD、只写不读、掩码、审计 details、错误文案、日志脱敏；SSRF 私网正反例；S3 客户端禁重定向。发现 F-001/F-002/F-014 及 F-003/F-004 的事务性安全缺口。

## AC 对账（任务卡 7 条）

| AC                                                                                                                          | 结论                   | 依据/残余                                                                                                                                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------------- | ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. CRUD/凭据/测试/202/409/契约真实实现                                                                                      | **fail**               | CRUD、凭据、test、sync 202/409、契约端点均有实现和定向测试；但平台管理员跨租户 404 未满足（F-002），PATCH 重名 500（F-005）。                                                                                                                                        |
| 2. 首次/增量/ETag/checksum/未变化只更新 last_seen_at/完整 list 才删/tombstone/限额/PARTIAL/重试/last_sync_*                 | **pass with residual** | EV-0968/0971 覆盖首次 100、增量、无变化、删除、tombstone、对象上限、PARTIAL、auth 重试；F-007 的 `isTruncated=true` 无 token 仍可能 fail-open，F-003 删除传播非事务。                                                                                                |
| 3. 端点 allowlist + SSRF（HTTPS/allowHttp、userinfo/query/fragment/redirect、169.254/::1/fe80、RFC1918 仅显式、DNS 正反例） | **pass**               | API/Worker policy 同源（仅包名 diff），`ConnectorEndpointPolicyTest` 覆盖 HTTPS/allowHttp、元数据/链路本地、userinfo/query/fragment、私网 DNS 正例、未列入 redirect 私网反例；SDK 2.59.2 Apache5 禁用重定向。F-011/F-012 是策略维护与响应语义小项。                  |
| 4. `knowledge.connector.*` 同事务 fail-closed、details 白名单、无凭据/正文/externalId；租户停用 API 拒绝、worker 再校验     | **pass with residual** | API 生命周期审计在同一 `@Transactional`；Worker sync.started/completed/failed/retried 与 scheduler 审计同事务；Handler 再校验租户 ACTIVE；details 白名单无凭据/正文/externalId。F-003 的 `knowledge.document.deleted` 审计不在删除传播事务内，需按 AC-16/§6.6 修正。 |
| 5. 真实 RustFS、无凭据/原文泄露、scheduleEnabled/next_sync_at 条件领取、不实现 B7b、不改 contracts                          | **fail**               | RustFS 定向测试通过、凭据/日志/审计无明文、调度器条件领取有测试、无 B7b/contracts 改动；但源桶写入（F-001）是 AC-5 的真实实现缺陷，且测试同桶无法证明。                                                                                                              |
| 6. CONNECTOR 列表/详情返回 connectorId + 末段 externalId、UPLOAD 为 null、读路径不抛异常                                    | **pass**               | `ConnectorDocumentReadApiTest` 锁定；`ConnectorExternalIds` 仅展示末段/32 字符。F-009 对两段路径的“最后 2 段”文字略有偏差。                                                                                                                                          |
| 7. finishSync 与 sync.completed/sync.failed 同短事务 fail-closed、长同步不包大事务                                          | **pass**               | `ConnectorSyncCompletionService` `@Transactional`；`ConnectorSyncCompletionIntegrationTest` 用 Mockito 使审计失败，断言 last_sync_* 回滚；长同步主流程无大事务。                                                                                                     |

## 未验证

- 未跑 M25 模块全量 `test/coverage`、`test_e2e`、`a11y`、`perf`、`security` 合同命令；这些由模块 gate 在最终提交上执行，本任务证据不能替代。
- 未在真实浏览器跑 UI/E2E（本任务无 UI 改动；连接器页属于 M05b-B8b）。
- 未在“源端点/源 bucket 与平台 `RICCON_S3_BUCKET` 不同”的真实部署上复现 F-001；结论来自代码路径、ADR-0009 平台对象存储定义和测试同桶设置，属于静态可判定缺陷。
- 未运行迁移 forward/rollback（本任务无迁移）；未执行安全扫描/性能脚本。
- 平台管理员 403 未用运行时测试复现，但权限拦截链（Controller 注解→PermissionInterceptor→RoleDefinitions 无连接器权限）是确定的。

## Findings

详见 front matter。按严重级别：

- **P1 F-001**：源桶写入/源端客户端；B7b/下载读不到对象、只读凭据无法同步、空前缀自摄入。必须改为平台对象存储（`StorageProperties`/平台 S3 client）并补“源桶≠平台桶”集成测试。
- **P1 F-002**：平台管理员 403 而非 404；需在连接器路径增加平台管理员的统一 404 处理或移除 controller 前置权限、改在租户解析后判定，并补平台管理员用例。
- **P2 F-003**：删除传播的 item/文档/审计/索引事件非事务；审计或事件失败会留下无审计删除或无索引清理。
- **P2 F-004**：索引事件投递失败后 PENDING 文档无法通过重试补投。
- **P2 F-005**：PATCH 重名 500，应为 409。
- **P2 F-006**：同步中被删除的连接器仍可能创建文档。
- **P2 F-007**：截断但无 continuation token 被当作完整 list，可能错误传播删除。
- **P2 F-008（accepted residual）**：连接器文档原文下载未实现，需 B7b 前补任务/扩范围。
- **P2 F-013**：关键 AC 负向用例缺失，现有绿测不能证明 AC-09/10/11/12/15/16。
- **P3 F-009/F-010/F-014/F-015**：externalId 两段路径展示、临时文件泄漏/无硬字节上限、短 accessKeyId 掩码过宽、claimDue 把 updated_at 写成未来时间；F-011/F-012 为 accepted（白名单重复、PAUSED nextSyncAt 语义）。

## 剩余风险与 B7b 前置

1. **F-001 必须在 B7b 启动前修复**：否则 B7b 的 `S3FileContentReader` 读平台桶会 404；如果反向改 B7b 读源桶，则会把源端凭据写权限、自摄入和跨租户污染带入模块，且违反 ADR-0009 平台对象存储边界。
2. **F-008 连接器下载**需在 B7b 前明确 owner/任务边界；当前 M05b 任务列表没有 `KnowledgeDownloadService` 写范围。
3. **F-003/F-004**的删除/索引事务与重试恢复，应在 B7b 索引消费/重灌前修复，否则 B7b 会消费到 PENDING 文档或缺失 DELETE 事件。
4. **F-013 测试补齐**至少覆盖：源桶≠平台桶、平台管理员 404、PATCH 重名 409、删除审计失败回滚、索引事件失败恢复、同步/删除竞态、截断无 token。
5. 模块 gate 仍需全量 test/coverage/E2E/a11y/perf/security；本任务定向证据只支撑 TASK-0204，不支撑 M25 gate。

## Gate

`riccon gate check --module M25` 当前 FAIL（预期）：

- TASK-0204 status=review，非 done；TASK-0205..0209/0211..0213 planned；无模块 review。
- 本任务 verdict=fail 还有 F-001/F-002 两个 P1；在修复并重新评审前，不应将 TASK-0204 标 done，也不应启动 B7b 的索引读取实现。
