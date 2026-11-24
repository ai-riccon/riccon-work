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
  - 38852d71729053b2ff133157ecb8ef289e69f919
  - e30c1b9f30b521040b1d807c8a2bb90e9283309d
  - d2a0d9376494525669d5cd16380e092107337bc9
head_commit: d2a0d9376494525669d5cd16380e092107337bc9
baseline_commit: 81bd9c6a5383289edb3719f253f6ee4feb4d0380
dod:
  acceptance: true
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
  第三轮 delta 复核：F-001/F-002（P1）与 F-003..F-018 的功能修复均已核对，EV-0979/0980/0981
  及 verifier 独立复跑均通过；acceptance/build/security/review/evidence 可判 true。tests 仍为 false：
  F-013 的唯一剩余点是 F-018 的“已存在删除事件”测试使用 `@MockitoBean ConnectorEventPublisher`，
  实际未调用生产 `enqueueDelete`/`hasDeleteIndexEvent`，属于假绿；补真实 publisher 路径测试前不能判 pass。
  coverage/e2e/migration/perf/a11y/docs 由 M25 模块 gate/其他任务承接。
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
  - EV-0979
  - EV-0980
  - EV-0981
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
  - EV-0979
  - EV-0980
  - EV-0981
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "源端 client 不再 PutObject；连接器原文写入平台 StorageProperties bucket，双桶测试锁定"
    summary: >-
      新增 `ConnectorObjectWriter`，注入平台 `S3Client` 与 `StorageProperties`，写入
      `storageProperties.bucket()`；同步服务源端 client 只保留 List/Get，源码中已无源端 `putObject`。
      `ConnectorSyncIntegrationTest` 使用 `SOURCE_BUCKET = BUCKET + "-source"` 与平台 `BUCKET` 双桶，
      首次同步后通过 `S3FileContentReader` 从平台 key 读回内容，并断言源桶下没有 `tenants/` 对象。
      证据：EV-0974/EV-0977/EV-0980。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorObjectWriter.java:26-80、
      :57-69；apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:504；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:74、:127-137、
      :169-195；EV-0974/EV-0977/EV-0980。
  - id: F-002
    severity: P1
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "8 个连接器端点统一 authorize：平台租户 404、普通员工 403、他租户 404"
    summary: >-
      `ConnectorController` 移除方法级 `@RequirePermission`，改为 `authorize()`：平台保留租户
      `PlatformTenant.ID` 先抛 `NotFoundException`（RICCON-TENANT-404），其余主体再经
      `AuthService.requirePermission` 走数据库实时权限（普通员工 403）；服务层仍按租户过滤
      connectorId（他租户管理员 404）。`ConnectorCrudApiTest` 新增平台管理员 8 端点全部 404 的用例。
      证据：EV-0973/EV-0976/EV-0979。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/controller/ConnectorController.java:107-120、
      :288-301；apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCrudApiTest.java:224-250；
      EV-0973/EV-0976/EV-0979。
  - id: F-003
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "删除传播为单条短事务：item/文档/审计/索引 DELETE 同事务"
    summary: >-
      `ConnectorDeletePropagationService.propagate` 在 `@Transactional` 内依次 `markItemDeleted`、
      `softDeleteDocument`、`auditWriter.documentDeleted`、`eventPublisher.enqueueDelete`；审计或事件
      异常回滚 item/文档。`ConnectorDeletePropagationIntegrationTest` 失败注入测试通过。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDeletePropagationService.java:48-80；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDeletePropagationIntegrationTest.java:50-80；
      EV-0974/EV-0977/EV-0980。
  - id: F-004
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "ensureIndexEvent 补投 PENDING 文档索引事件，覆盖首次投递失败后的重试"
    summary: >-
      `processSupportedObject` 在 ETag 未变和 checksum 相同的分支调用 `ensureIndexEvent`，仅当文档仍为
      PENDING 且无 PENDING/DISPATCHED 索引事件时补投；`ConnectorDocumentWriteService.updateDocument`
      把更新文档、item SYNCED 与 enqueueIndex 放在同一事务。EV-0980 的新文档事件失败/重试测试
      （`createDocumentEventFailureRollsBackAndNextSyncRecovers`）走真实 `ConnectorSyncService` 路径。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:462-482、
      :599-615；apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDocumentWriteService.java:115-133；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:701-730；
      EV-0974/EV-0977/EV-0980。
  - id: F-005
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "PATCH 重名捕获 DuplicateKeyException 并返回 409 RICCON-KB-409"
    summary: >-
      `ConnectorService.update` 映射 DuplicateKeyException 为 ConnectorConflictException；
      `ConnectorCrudApiTest` 新增重名 PATCH 409 用例。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:230-249；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCrudApiTest.java:275-289；
      EV-0973/EV-0976/EV-0979。
  - id: F-006
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "markSyncStarted=0 中止，每个对象前 isSyncable 复核；同步中删除连接器不再创建文档"
    summary: >-
      `synchronize` 在 `markSyncStarted` 返回 0 时返回 ERROR；`processObject` 每对象复核
      `isSyncable`（连接器未删除且租户 ACTIVE）。`connectorDeletedDuringSync` 测试删除后 documents 为空。
      残余 check-then-act 窗口已由 F-017 的事务锁进一步收口。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:166-179、
      :377-385；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:634-670；
      EV-0974/EV-0977/EV-0980。
  - id: F-007
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 38852d7
    title: "截断无 token 时先处理当前页已确认对象，再标记 complete=false 并跳过删除"
    summary: >-
      新代码把 `truncated && nextToken 为空` 的检查移到 `for (S3Object object : response.contents())`
      之后：当前页已返回对象先按正常流程处理，页尾再置 `complete=false`/`KB_CONNECTOR_TRUNCATED`，
      不进入删除传播。`truncatedWithoutToken` 现在使用已存在的支持类型 `a.pdf` 并断言 item 被 touch/
      SYNCED，同时断言 documents/items 无删除；满足 M05-AC-11“不传播删除、处理已确认对象”。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:311-347；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:590-660；
      EV-0974/EV-0977/EV-0980。
  - id: F-008
    severity: P2
    status: accepted
    owner: riccon/lead
    due: TASK-0214 / B7b 启动前
    accepted_reason: >-
      TASK-0204 写范围不含 KnowledgeDownloadService；DEC-0041=A 已创建 TASK-0214
      （M05b-B12b 连接器文档原文下载，3h，owner riccon/builder-kb-connector，planned，due B7b 前），
      验收包含平台对象 ACL 下载、key 与 B6 一致、无源对象 RICCON-KB-404、源桶≠平台桶。该 residual
      有独立任务和截止，不在 B6 中造假实现。
    title: "连接器文档原文下载已转入 TASK-0214（DEC-0041=A）"
    summary: >-
      `KnowledgeDownloadService` 仍只处理 `file_id`，CONNECTOR 文档 `file_id=null` 会返回
      RICCON-KB-404；ADR §5.3/§6.3 与契约连接器段要求连接器原文可按文档 ACL 下载。该缺口由
      TASK-0214 承接，B7b 启动前必须完成或明确扩展 B7b 范围。
    evidence: >-
      .riccon/tasks/TASK-0214.json；.riccon/decisions/DEC-0041.md；
      apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:129-131。
  - id: F-009
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "externalId 两段路径展示最后两段，测试补 a/b"
    summary: >-
      `ConnectorExternalIds.display` 按 `/` 分段取最后两段；`a/b` 返回 `a/b`，`a/b/c.pdf` 返回
      `b/c.pdf`，再截断到 32 字符。`ConnectorExternalIdsTest` 增加 `a/b` 断言。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/domain/ConnectorExternalIds.java:23-39；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorExternalIdsTest.java:13-23。
  - id: F-010
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 9275eb0
    title: "下载临时文件在超限/总字节/校验失败时清理；平台写入前 100 MB 硬上限"
    summary: >-
      `download` 增加 `catch (DownloadFailure)` 统一 `deleteQuietly(temp)`；`ConnectorObjectWriter` 在
      平台桶上传前检查 `MAX_OBJECT_BYTES`（100 MB）。`totalBytesLimit_cleansTempFile` 断言临时文件不存在。
      流式下载阶段仍依赖源端 list size 预检，作为残余观察记录，不作为 finding 阻断。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:552-588；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorObjectWriter.java:57-80；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:672-706；
      EV-0974/EV-0977/EV-0980。
  - id: F-011
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      B6 写范围不含共享 AuditDetailsPolicy；Worker 本地 ConnectorAuditPolicy 当前键集合与 API
      AuditDetailsPolicy 一致，Worker 不写 created/updated/deleted/credential.changed。重复白名单的维护
      风险仍存在，已有原评审 accepted 记录，本轮不阻断。
    title: "Worker 本地 ConnectorAuditPolicy 保留，未折入共享策略"
    summary: >-
      本轮未修改 `ConnectorAuditPolicy`/`AuditDetailsPolicy`；键集合仍逐项一致。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorAuditPolicy.java:21-57；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:207-273。
  - id: F-012
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前
    accepted_reason: >-
      PAUSED 连接器 `nextSyncAt` 非空只影响响应可读性；Worker findDue 明确要求 status='ACTIVE'，不会
      误调度。契约“连接器启用”可解释为功能开关；建议 gate 前统一文案/补测试。
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
    title: "F-018 的 existing delete event 测试使用 Mockito mock，未验证生产 enqueueDelete 幂等"
    summary: >-
      本轮已补 F-016 sync 路径事件失败/重试、F-007 支持对象截断页、F-014 掩码、F-015 updated_at、
      F-017 删除后写入回滚等测试；但 `ConnectorDeletePropagationIntegrationTest` 类级使用
      `@MockitoBean ConnectorEventPublisher eventPublisher`，新增的
      `propagate_existingDeleteEventIsIdempotent` 只预插 outbox 行并调用 `propagate`，实际
      `eventPublisher.enqueueDelete` 是 mock，完全不执行生产 `hasDeleteIndexEvent`/`enqueueDelete`
      分支。该测试因此不能证明 F-018“已存在事件不重复插入 outbox”的生产行为。需要改为
      `@MockitoSpyBean`（或独立真实 publisher 测试）并断言真实 outbox 唯一键/已存在路径。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDeletePropagationIntegrationTest.java:35-40
      （`@MockitoBean ConnectorEventPublisher`）、:83-128（测试本身）；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorEventPublisher.java:100-120；
      `grep -R "enqueueDelete" apps/worker/src/test/java/com/riccon/worker/connector` 仅命中该 mock 调用。
  - id: F-014
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 38852d7
    title: "短 accessKeyId（<12）掩码统一 ****"
    summary: >-
      `MIN_MASK_LENGTH` 从 8 改为 12；长度 <12 返回 `****`，≥12 才展示前 4+后 3。
      `ConnectorCredentialsTest` 覆盖 null、5/11/12 字符和正常 key。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorCredentials.java:44-46、
      :152-156；apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorCredentialsTest.java:13-20；
      EV-0979。
  - id: F-015
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 38852d7
    title: "claimDue 的 updated_at 改为当前领取时间，不再写未来 nextAt"
    summary: >-
      `claimDue` 新增 `now` 参数并写入 `updated_at=:now`；Scheduler 传入当前时间。
      `ConnectorSyncIntegrationTest.scheduleDue...` 断言 `updated_at < next_sync_at`。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:275-295；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncScheduler.java:113-119；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:554-590；
      EV-0974/EV-0977/EV-0980。
  - id: F-016
    severity: P2
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 38852d7
    title: "首次创建文档改为调用 ConnectorDocumentWriteService.createDocument，单事务"
    summary: >-
      `ConnectorSyncService` 新文档路径已从直接 `insertDocument` + `markItemSynced` + `publishIndex`
      改为 `documentWriteService.createDocument`；`createDocument` 的 `@Transactional` 同时插入文档、
      标记 item SYNCED、投递索引事件。新增 `createDocumentEventFailureRollsBackAndNextSyncRecovers`
      走真实 `ConnectorSyncService`，断言首次失败无文档/事件、下一次成功 1 文档/1 事件。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorSyncService.java:502-526；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDocumentWriteService.java:63-98；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorSyncIntegrationTest.java:701-730；
      EV-0974/EV-0977/EV-0980。
  - id: F-017
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 38852d7
    title: "文档写入事务内 SELECT ... FOR UPDATE 锁 kb_connector 并校验租户 ACTIVE"
    summary: >-
      `ConnectorDocumentWriteService.createDocument/updateDocument` 首步
      `requireSyncable` → `lockSyncableConnector`（JOIN tenant、deleted_at IS NULL、status ACTIVE、
      FOR UPDATE）；锁持有到事务提交。新增删除后的连接器写入测试断言不插入文档、item 保持 PENDING。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:136-162；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDocumentWriteService.java:79、
      :128-145；apps/worker/src/test/java/com/riccon/worker/connector/ConnectorDocumentWriteServiceIntegrationTest.java:138-204；
      EV-0974/EV-0977/EV-0980。
  - id: F-018
    severity: P3
    status: fixed
    owner: riccon/builder-kb-connector
    due: fixed in 38852d7
    title: "删除事件幂等：先查已有 outbox 事件，删除传播捕获 DuplicateKeyException"
    summary: >-
      `ConnectorEventPublisher.enqueueDelete` 先 `hasDeleteIndexEvent` 查询精确幂等键，存在则返回 null；
      `ConnectorDeletePropagationService` 捕获 DuplicateKeyException 视为已存在。代码路径已覆盖；
      但现有测试使用 mock publisher，未真正触发该分支（见 F-013）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorEventPublisher.java:100-120；
      apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:711-735；
      apps/worker/src/main/java/com/riccon/worker/connector/service/ConnectorDeletePropagationService.java:74-80；
      EV-0974/EV-0977/EV-0980。
test_impact: >-
  第三轮 delta 独立复跑（HEAD d2a0d93，工作树无生产改动）：
  API 指定 6 类（ConnectorCrud/CredentialSync/DocumentRead/ExternalIds/Credentials/KnowledgeAcl）→
  exit 0，22 tests / 0 failures / 0 errors / 0 skipped（Crud 10、Credential 4、DocumentRead 1、
  ExternalIds 1、Credentials 1、KnowledgeAcl 5）；
  Worker `Connector*Test` → exit 0，36 tests / 0/0/0（Audit 1、DeletePropagation 3、DocumentWrite 2、
  Domain 2、Endpoint 5、ObjectWriter 1、Properties 2、Budget 2、Completion 1、SyncIntegration 17）；
  ArchUnit `ModuleBoundaryTest` → exit 0，12 tests / 0/0/0。
  Lead 证据 EV-0979（API）、EV-0980（Worker 36）、EV-0981（ArchUnit）均 commit=e30c1b9、dirty=false、
  exit 0，日志哈希与 JSON 一致。`git diff --name-status 9275eb0..38852d7` 共 12 文件，全部在
  TASK-0204 写范围；`git diff --check 9275eb0..38852d7` exit 0；无 contracts/docs/deploy/migration 改动。
  未运行 M25 全量 test/coverage/E2E/a11y/perf；所有定向测试未使用 skip/retry/额外串行参数。
notes: >-
  第三轮 delta 提交链：38852d7（父 9275eb0，12 文件）→ e30c1b9（merge）→ d2a0d93（状态/证据）。
  原 F-001..F-018 的功能修复均已核对；唯一阻断点是 F-013 的 F-018 测试假绿：类级
  `@MockitoBean ConnectorEventPublisher` 使 `propagate_existingDeleteEventIsIdempotent` 没有调用生产
  `enqueueDelete`/`hasDeleteIndexEvent`，因此不能证明 F-018。F-018 生产代码本身有 pre-check + catch，
  但并发重复键仍可能把当前事务标记 rollback-only（P3 残余，未阻断本轮）。F-010 的流式下载硬上限仍
  依赖源端 list size 预检（P3 观察）。F-008 由 TASK-0214/DEC-0041 承接；F-011/F-012 accepted。
  `riccon gate check --module M25` 仍 FAIL：TASK-0204 review、TASK-0205..0209/0211..0214 planned、
  无模块 review；本任务 verdict=fail 仅因 F-013。未修改任务 status/源码/测试/契约。
created_at: "2026-11-24T08:08:00+08:00"
---

# REV-TASK-0204-001：TASK-0204（M05b-B6 S3/OSS 连接器）独立评审（第三轮 delta 复核）

## 结论

**不通过（fail）。** 第三轮返工 `38852d7` 已真实关闭 F-016/F-007/F-014/F-015/F-017/F-018 的生产代码问题；F-001/F-002 两个 P1 和第二轮 F-003/F-004/F-005/F-006/F-009/F-010 仍保持 fixed。独立复跑 API 22/0/0/0、Worker 36/0/0/0、ArchUnit 12/0/0/0。

但 **F-013 仍 open P2**：F-018 的“已存在删除事件”测试所在类使用 `@MockitoBean ConnectorEventPublisher`，新增测试只预插 outbox 行并调用 `propagate`，实际 `enqueueDelete`/`hasDeleteIndexEvent` 是 mock，完全没有验证生产幂等分支。这是假绿，不能作为 F-018 的测试证据；在改为真实 publisher（或 `@MockitoSpyBean` 调用真实方法）前，不能判 pass。

## Delta 复核范围

- `git diff --name-status 9275eb0..38852d7`：12 文件，全部在 TASK-0204 write_scope；无 contracts/docs/deploy/migration/.riccon 生产改动。
- `git diff --check 9275eb0..38852d7`：exit 0。
- 新变更：`ConnectorCredentials` 短掩码、`ConnectorJdbc` `lockSyncableConnector`/`claimDue`/`hasDeleteIndexEvent`、`ConnectorDocumentWriteService` 事务锁与 `createDocument` 接线、`ConnectorSyncService` 截断页与新文档路径、`ConnectorEventPublisher` 删除事件幂等，以及对应 API/Worker 测试。
- 合并链：`38852d7`（父 `9275eb0`）→ `e30c1b9`（merge）→ `d2a0d93`（状态/证据）；当前 HEAD `d2a0d93` 与 `e30c1b9` 的 production 代码相同。

## 验证了什么

- **F-016**：`ConnectorSyncService` 新文档路径已调用 `documentWriteService.createDocument`；`createDocument` `@Transactional` 内插入文档、item SYNCED、enqueueIndex。`createDocumentEventFailureRollsBackAndNextSyncRecovers` 走真实 SyncService，首次事件失败无文档/事件、第二次成功 1 文档/1 事件。
- **F-007**：截断无 token 的检查移到对象循环之后；当前页 `a.pdf` 已处理并 touch/SYNCED，随后 `complete=false`/TRUNCATED，不传播删除。`truncatedWithoutToken` 用支持类型对象断言。
- **F-013**：新测试覆盖 F-016/F-007 的 sync 路径；但 F-018 的 existing-event 测试是 mock，见 finding。
- **F-014**：`MIN_MASK_LENGTH=12`；`ConnectorCredentialsTest` 覆盖短 key。
- **F-015**：`claimDue` 新增 `now`，`updated_at=:now`；调度测试断言 `updated_at < next_sync_at`。
- **F-017**：`createDocument/updateDocument` 事务首步 `SELECT ... FOR UPDATE` 锁 connector + tenant ACTIVE；删除后写入测试回滚、item 保持 PENDING。
- **F-018**：生产 `enqueueDelete` 有 `hasDeleteIndexEvent` 预查，`propagate` 捕获 DuplicateKeyException；但测试未走真实分支（F-013）。
- **独立复跑**：API 22/0/0/0；Worker 36/0/0/0；ArchUnit 12/0/0/0；Lead EV-0979/0980/0981 均为 e30c1b9、dirty=false、exit 0。
- **未验证**：M25 全量 test/coverage/E2E/a11y/perf/security、真实浏览器/UI（无 UI 本任务）、真实客户源桶生产网络、F-008 下载（TASK-0214 未启动）。

## AC 对账（任务卡 7 条）

| AC                                                                              | 结论     | 依据/残余                                                                                              |
| ------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------ |
| 1. CRUD/凭据/测试/202/409/契约真实实现                                          | **pass** | F-002/F-005 已修；平台管理员 404、员工 403、他租户 404、PATCH 409、凭据掩码/加密、test/sync 均有测试。 |
| 2. 首次/增量/ETag/未变化/完整 list 删除/tombstone/限额/PARTIAL/重试/last_sync_* | **pass** | EV-0974/0977/0980 全绿；F-007 截断页处理、F-016 新文档事务均已修。                                     |
| 3. 端点 allowlist + SSRF                                                        | **pass** | API/Worker policy 同源，私网 DNS 正反例、元数据/链路本地、redirect、SDK 禁重定向均保留。               |
| 4. 审计同事务 fail-closed/details 白名单/租户停用                               | **pass** | F-003 删除传播事务已修；F-018 生产幂等有 pre-check/catch，F-018 测试假绿见 F-013；F-011 accepted。     |
| 5. 真实 RustFS/无泄露/调度/不改 contracts                                       | **pass** | 双桶测试真实通过；F-008 转 TASK-0214（accepted）；契约无改动。                                         |
| 6. CONNECTOR 列表/详情 connectorId/externalId/UPLOAD null/读路径                | **pass** | API 读路径测试继续通过；F-009 两段 externalId 已修。                                                   |
| 7. finishSync 与 sync.completed/sync.failed 同短事务 fail-closed                | **pass** | `ConnectorSyncCompletionService` `@Transactional` + 完成审计失败回滚测试继续通过。                     |

## 未验证

- 未跑 M25 模块全量 `test/coverage`、`test_e2e`、`a11y`、`perf`、`security` 合同命令；由模块 gate 承接。
- 未运行真实浏览器/UI（本任务无 UI 改动）。
- 未在真实客户源端点/源桶与平台桶分离的生产网络复现；双桶测试使用真实 RustFS 两个 bucket。
- 未运行连接器下载（TASK-0214 未启动）。
- 未复现 F-018 的并发 DuplicateKey 竞态；当前测试是 mock，属 F-013 假绿。

## Findings

详见 front matter。第三轮状态：

- **P0/P1**：0 open；F-001/F-002 fixed。
- **P2**：仅 F-013 open；F-003/F-004/F-005/F-006/F-007/F-016 fixed；F-008 accepted。
- **P3**：F-009/F-010/F-014/F-015/F-017/F-018 fixed；F-011/F-012 accepted。

## 剩余风险与下一步

1. **F-013（阻断）**：把 `ConnectorDeletePropagationIntegrationTest` 的事件 publisher 从 `@MockitoBean` 改为 `@MockitoSpyBean`（或独立测试 real `ConnectorEventPublisher`），真正执行 `hasDeleteIndexEvent`/`enqueueDelete`，断言已有事件不新增 outbox 行、propagate 成功；再删除 mock 对 existing-event 用例的影响。
2. **F-018 并发残余**：pre-check + catch 已覆盖常规路径；并发重复键仍可能把当前事务标记 rollback-only，建议后续用 insert-if-absent/`INSERT IGNORE` 或独立事务，列为 P3 观察。
3. **F-010 流式上限**：临时文件清理已 fixed；下载阶段仍依赖源端 list size 预检，极端不实 size 仍可能先写大临时文件，列为 P3 观察。
4. **F-008**：TASK-0214 完成前 B7b 不应假设连接器下载可用；下载 key 必须与 B6 平台桶一致。
5. M25 gate 仍需全量 test/coverage/E2E/a11y/perf/security；本任务定向证据只支撑 TASK-0204。

## Gate

`riccon gate check --module M25` 当前 FAIL（预期）：TASK-0204 仍 review、TASK-0205..0209/0211..0214 planned、无模块 review。本任务自身 verdict=fail 仅因 F-013 的假绿测试；建议 Lead 在修复 F-013 后复核再标 done，不应在假绿测试存在时直接 done。B7b 的 F-008 残余仍由 TASK-0214 承接。
