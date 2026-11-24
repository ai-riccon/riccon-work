---
review_id: REV-TASK-0205-001
module_id: M25
task_id: TASK-0205
reviewer:
  agent: riccon/verifier
  session: ses_df4c8868affePnlcmkdERrmMfz
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 579059db91b32ebc50abbced8014d66126d50bbc
  - 55eadba114cf6b34cd9485d1c6c1c12fd73cd861
  - 1bde396976cb117c3e973a526efafd4640c7bf8f
  - a96753ddc48f547ccaee8fe15d7ff4c2cc7792a2
head_commit: a96753ddc48f547ccaee8fe15d7ff4c2cc7792a2
baseline_commit: 6a015708b8d5c8291e0fc99bea9c0b1333e2627a
dod:
  acceptance: true
  build: true
  tests: true
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
  acceptance 按 4 条 AC 逐条对账均通过；build 由定向测试的 Maven 编译与 `spotless:check`（EV-0988，exit 0）
  支撑；tests 由 EV-0986（builder）与 EV-0987（verifier 独立复跑，57 用例 0 失败 0 错误 0 跳过）支撑；
  security 覆盖连接器读取只走平台桶/平台 client、租户前缀、跨租户同 documentId、错误信息不含 key、索引不写入
  externalId；review 为本文件；evidence 为 EV-0986/EV-0987/EV-0988。coverage/e2e/migration/perf/a11y 由
  M25 模块 gate 与 V1b/V2b 承接；docs 无行为变化（ADR-0023 §6.3/§7.1 已描述连接器 key 与索引入口），记 N/A。
reviewed_evidence:
  - EV-0986
  - EV-0987
  - EV-0988
test_impact: >-
  定向 7 个测试类共 57 用例（真实 MySQL 8.4 / OpenSearch / RustFS external 栈）在 verifier 命名空间
  `verifier-b7b` 全绿；未跑全量 Maven、E2E、coverage、perf（按任务说明由 M25 gate/V1b/V2b 承接）。
evidence:
  - EV-0987
  - EV-0988
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    title: "连接器纯文本没有独立的字符/分块上限，极端大文本会生成数万分块与超大 bulk 请求"
    summary: >-
      `ConnectorPlainTextExtractor.extract` 按 UTF-8 解出全文后交给 `TextChunker`，只受
      `riccon.worker.parse.max-file-bytes` 默认 50 MB 的字节上限约束；`ParseProperties`/`TextChunker`
      没有每文档分块数或字符数上限。以 50 MB 文本、chunk-size=800/overlap=80 估算约 6.9 万块，
      需要逐块插入 MySQL 与组装一次 bulk 请求（正文+向量可达 100 MB 量级），期间 Worker 内存与 heavy
      通道都会承压；OpenSearch 超限时会以 `RICCON-INDEX-400` 失败，文档落 FAILED（可 reindex，无静默成功）。
      ADR-0023 §8 TM-59 的控制项列出“字符上限”，当前文本路径只有字节上限间接约束；同一形状的上限缺口
      对 UPLOAD 文本型 PDF 已存在，不是 B7b 独有，但 txt/md 让最坏情况更容易构造。
    accepted_reason: >-
      50 MB 字节上限提供了硬上界（≤约 5000 万字符），连接器配置只有租户管理员可改，且异常会以 FAILED
      可见并可用既有 reindex 端点恢复，不会静默写入空索引或串租户；真正加字符/分块上限需要改共享
      `TextChunker`/`ParseProperties` 或为连接器文本单独设限，属于跨任务资源策略，已在 M25 的 B11b（连接器
      perf）与 V2b（安全/性能）范围内复核。任务本身不因此阻断。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/index/service/ConnectorPlainTextExtractor.java:55-74；
      apps/worker/src/main/java/com/riccon/worker/config/ParseProperties.java:17-32；
      apps/worker/src/main/resources/application.yml:145-146；
      apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:361-368。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-kb-web
    due: M25 gate 前（B8b 启动时一并处理）
    title: "连接器 txt/md 的 parse_engine 落 NONE，知识库详情页显示“未进入解析”"
    summary: >-
      纯文本抽取返回 `ParsedContent(..., ParseEngine.NONE)`，`handleFull` 会把
      `knowledge_document.parse_engine` 与索引文档的 `parse_engine` 都写成 `NONE`；前端
      `parseEngineLabel('NONE')` 文案为“未进入解析”，而该文档实际 status=INDEXED、分块与引用都正常。
      契约枚举（contracts/openapi.yaml:4404-4405）没有“纯文本”引擎值，因此这不是契约违规，但与用户看到的
      事实不一致；连接器文档详情沿用了 M05 的解析引擎标签。
    accepted_reason: >-
      新增文本引擎值需要同时改契约枚举、DB CHECK（V12 `parse_engine` 约束）与前端类型/文案，超出 TASK-0205
      写范围（worker index/S3FileContentReader）；当前索引、检索、引用与 ACL 均正确，仅详情页标签措辞偏差。
      B8b 正在处理连接器 UI，可在详情页按 mime/来源展示“纯文本（未使用专用解析引擎）”或由后续任务统一新增枚举，
      风险有界且可见。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/index/service/ConnectorPlainTextExtractor.java:73；
      apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:196-201；
      apps/web/src/services/knowledge/format.ts:30-36；
      contracts/openapi.yaml:4404-4405。
notes: >-
  1) 复核了 TASK-0214 的 accepted 残余 F-002（API 侧 `KnowledgeDownloadService.connectorObjectKey` 与 worker
  `ConnectorContentTypes.objectKey` 各一份）：B7b 没有新增第三份实现，worker 读路径显式复用
  `ConnectorContentTypes.objectKey`，写读一致性反而收紧；API 重复逻辑仍按 REV-TASK-0214-001 F-002 跟踪。
  2) B6 评审（REV-TASK-0204-001）的 accepted F-008/F-011/F-012 与本任务无交叠；B6 的 `ensureIndexEvent` 只对
  PENDING 文档补投，因此 FAILED 连接器文档不会被下一次同步自动重试，但可用 `KnowledgeDocumentLifecycleService.reindex`
  （允许 FAILED）或内容变化（updateDocumentContent 重置 PENDING）恢复，任务 AC 的“失败可重试”有出口。
  3) 连接器单对象上限 100 MB（B6）大于 Worker 解析上限 50 MB，50–100 MB 对象会在索引阶段以
  `RICCON-FILE-413` FAILED；这与 UPLOAD 200 MB/解析 50 MB 的既有产品语义一致，建议 V1b/模块 gate 明确
  连接器是否应把不可索引大小挡在同步阶段，不属本任务缺陷。
  4) 本任务各任务卡 `tools_used` 均为空（TASK-0200..0205/0210/0214），无法从卡片核实 builder 是否使用
  Serena/CodeGraph；这是项目级记录习惯问题，不作为 B7b finding，建议 Lead 统一要求回填或写明降级理由。
  5) `.markdown` 抽取支持是防御性分支：B6/API 白名单只有 `md`，该分支不可达且无单测；建议后续删除或补测。
  6) 并发 FULL/DELETE 交错导致分块/索引残留的窗口是 M05-B7 既有风险（REV-TASK-0169-001 F-006 accepted），
  B7b 未扩大该窗口。
created_at: 2026-11-24T09:53:00+08:00
---

## 结论

TASK-0205（M05b-B7b）实现连接器文档的平台对象索引分支与 txt/md 文本索引，满足 4 条 AC；租户隔离、失败语义、
重灌幂等与 UPLOAD/SEED/ACL_ONLY/DELETE/v2 回归均通过独立复验。判定 **pass**，无 P0/P1/P2，2 个 P3 accepted
作为 M25 gate 前的跟踪项。

## 评审范围与提交核对

- 代码提交 `579059d`（读取器/索引分支/领域与仓储/测试）与 `55eadba`（txt/md 抽取）；merge `1bde396` 的树与
  `55eadba` 仅差 `.riccon/state.json`、`.riccon/tasks/TASK-0205.json`（任务簿记），生产代码一致。
- HEAD `a96753d` 只增加 EV-0986 与状态转 review，生产代码无变化；`a96753d` 相对 `55eadba` 的 diff 只有
  `.riccon/` 下 4 个文件。
- `git diff --name-only 6a01570..55eadba` 共 **8** 个文件，全部在任务写范围内：`S3FileContentReader.java`、
  `index/domain/KnowledgeDocumentRecord.java`、`index/repository/KnowledgeRepository.java`、
  `index/service/ConnectorPlainTextExtractor.java`、`index/service/KnowledgeIndexService.java`，以及
  `ConnectorKnowledgeIndexIntegrationTest`、`KnowledgeIndexV2MigrationTest`、`S3FileContentReaderTest`。
- 无 `contracts/`、`docs/`、`deploy/`、迁移或 `.riccon/` 生产改动；`git diff --check 6a01570..55eadba` exit 0。

## 证据核对

| EV      | 命令/范围                                       | commit    | dirty | exit | sha256 核对                        |
| ------- | ----------------------------------------------- | --------- | ----- | ---- | ---------------------------------- |
| EV-0986 | 7 类连接器索引/回归，external 栈                | `1bde396` | false | 0    | `dd20a7a8…` 与日志文件本机复算一致 |
| EV-0987 | verifier 同命令复跑（namespace `verifier-b7b`） | `a96753d` | false | 0    | `a240e5d9…` 一致                   |
| EV-0988 | `./mvnw -q spotless:check`                      | `a96753d` | false | 0    | `935508c8…` 一致                   |

- `EV-0985` 属 TASK-0214 并发重复、已归档；本任务只使用 EV-0986，未混用。
- EV-0986/EV-0987 日志本身不含 surefire 汇总（`-q`），用例数以 `apps/worker/target/surefire-reports` 的
  XML 为准（见下），两者 exit 均为 0。

## 独立复验

```bash
RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-b7b ./mvnw -q -T1C -pl apps/worker \
  -am -Dtest='ConnectorKnowledgeIndexIntegrationTest,S3FileContentReaderTest,KnowledgeIndexServiceIntegrationTest,KnowledgeIndexModesIntegrationTest,KnowledgeIndexV2MigrationTest,FileParseServiceIntegrationTest,ConnectorSyncIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 首次执行因 `verifier-b7b` 命名空间数据库未创建而以 `Access denied … riccon_test_verifier_b7b_worker` 失败；
  按错误提示执行 `node scripts/test-services.mjs prepare --namespace verifier-b7b --module worker` 后**原命令未改**
  通过。这是测试栈准备问题，不是代码失败，失败运行未录证据。
- 结果（surefire XML）：**57 用例，failures 0 / errors 0 / skipped 0**，分布为
  `ConnectorKnowledgeIndexIntegrationTest 12`、`S3FileContentReaderTest 7`、`ConnectorSyncIntegrationTest 17`、
  `FileParseServiceIntegrationTest 9`、`KnowledgeIndexServiceIntegrationTest 7`、`KnowledgeIndexModesIntegrationTest 4`、
  `KnowledgeIndexV2MigrationTest 1`；EV-0987 exit 0。
- `./mvnw -q spotless:check` exit 0（EV-0988）。
- 未跑全量 Maven/E2E/coverage/perf；未修改任何生产源码、测试或契约。
- `riccon state validate` 对该评审无 error/warning；`riccon gate check --module M25` 仍失败，但原因是模块仍在
  building：TASK-0205 处 review、TASK-0206..0209/0211..0213 仍 planned、且缺 M25 模块评审，与任务级结论不矛盾。

## AC 对账

| AC                                                                                                                                                       | 结论 | 证据/说明                                                                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC1 连接器文档按 `tenants/{tenantId}/connector/{yyyy}/{documentId}` 读取并解析；`fileId=null`/`connectorId` 非空分支；不依赖下载端点；不破坏 UPLOAD/SEED | pass | `S3FileContentReader.readConnector`(83-88) → `connectorObjectKey`(101-106) → `ConnectorContentTypes.objectKey`(109-112) 为同一实现；`KnowledgeIndexService.loadDocument`(324-338) 三分支互斥；`KnowledgeIndexServiceIntegrationTest` 的 UPLOAD/SEED 用例与 `FileParseServiceIntegrationTest` 全绿                                                                                              |
| AC2 连接器对象 index/ACL_ONLY/DELETE/tombstone；重灌幂等；不破坏 M05 索引 v2/别名                                                                        | pass | `connectorDocument_indexesPlatformObjectWithConnectorFields`、`aclOnly_refreshesConnectorAclWithoutTouchingContent`、`delete_clearsConnectorChunksAndIndex`、`sourceReappears_indexesNewDocumentAndRemovesOldIndex`、`fullEventReplay_doesNotDuplicateChunks`、`markdownConnectorDocument_indexesLongTextIdempotently`；`KnowledgeIndexV2MigrationTest` 通过；本轮未改 `OpenSearchIndexClient` |
| AC3 测试覆盖对象删除/重现/失败/重灌幂等；真实 RustFS/MySQL                                                                                               | pass | 12 个连接器索引用例 + 7 个读取器用例；external compose 栈（`riccon-test-mysql`/`-opensearch`/`-object-storage`，真实 RustFS）                                                                                                                                                                                                                                                                  |
| AC4 不修改 contracts/docs/deploy；B6 平台桶写入与 TASK-0214 下载 key 为前置                                                                              | pass | 8 个文件全部在 worker 写范围；前置任务 TASK-0204（REV-TASK-0204-001 pass）与 TASK-0214（REV-TASK-0214-001 pass）已完成                                                                                                                                                                                                                                                                         |

## 重点核对

1. **连接器读取**：`S3FileContentReader` 只注入平台 `S3Client` 与 `StorageProperties.bucket()`（48-55），
   `readConnector` 无连接器 endpoint/凭据参数；key 前后各做一次租户前缀校验（84-87、173-177）；
   `readKey` 先 HEAD 再校验 `maxFileBytes`（127-146）；404/403 统一 `notFound`、5xx/IO 统一
   `storageUnavailable`（185-195、154-165），错误信息不含 bucket/key（测试断言）。
2. **索引分支**：`fileId != null` 走 UPLOAD；`fileId == null && connectorId != null` 走连接器；两者皆空才是 SEED。
   `missingConnectorObject_failsWithoutSeedFallback` 预置一条 stale chunk 后仍以 `RICCON-TENANT-404` FAILED，
   证明不会把连接器文档误走“读取已有分块”。
3. **租户隔离**：跨租户同 documentId 用例（`crossTenantConnectorObject_isNotReadable`）与 `S3FileContentReaderTest`
   的跨租户用例均 404；索引名由 `OpenSearchIndexClient.indexName(tenantId)` 派生，写入带 `tenant_id`；
   索引字段只写 `source_type`/`connector_id`（`OpenSearchIndexClient.java:389-401`），mapping（512-557）不含
   `external_id`，测试断言 `_source` 不含 externalId 与 `connector/`。
4. **模式/幂等**：FULL 重灌先 `replaceChunks` 后 `replaceDocument`（先删后写），重复事件不产生重复块/文档；
   ACL_ONLY 只读 MySQL 分块并保持 chunk_id；DELETE 两次均 DONE 且清空分块/索引；tombstone 重现以新
   documentId 索引新内容、旧文档保持 DELETED 且索引为 0。失败路径：对象缺失/损坏为不可重试 FAILED+DEAD，
   存储不可用为 `RICCON-FILE-503` 可重试（S3FileContentReaderTest），poller 不崩溃（第二次 pollOnce=0）。
5. **txt/md**：`ConnectorPlainTextExtractor` 使用 UTF-8 替换解码、去 BOM、CRLF/CR 归一、按空行分段并保留段落定位；
   空/仅空白抛 `RICCON-FILE-422`；不注册为 `DocumentParser`、不依赖 `FileFormat`/上传校验；长文本由既有
   `TextChunker` 切分，测试断言 ≤800 字符且重灌内容完全一致。`.markdown` 分支不可达（见 notes 5）。
6. **回归**：57 用例全绿，覆盖 UPLOAD、SEED、ACL_ONLY、DELETE、v2 重灌/别名、文件解析、连接器同步（17 用例）。
7. **范围/质量**：8 文件均在写范围；`git diff --check` 干净；新增方法与类均有中文 Javadoc；无 skip/零测试/吞错；
   测试断言对象状态、分块内容、索引 `_source` 与 outbox/run 状态，不是只对坏状态快照。
   `FileContentExtractor` 的字面复用（其实现仅 `read`+`parse` 两行）可接受：连接器需要派生 key 且无 `file` 行，
   现方案仍复用同一 `DocumentParserRegistry`/`TextChunker`；若未来出现第三来源，建议抽 `extractBytes(FileRecord, byte[])`
   作为最小重构，但不构成本任务阻塞。
8. **证据**：EV-0986 的 commit/dirty/exit/sha256 已核对；EV-0985 归档未混用；verifier 独立证据 EV-0987/EV-0988
   记录在同一 commit、dirty=false。

## Findings

- **F-001（P3，accepted）** 连接器纯文本缺少独立字符/分块上限，极端大文本会产生数万分块与超大 bulk 请求；
  owner `riccon/lead`，due M25 gate 前，理由与影响见 front matter。
- **F-002（P3，accepted）** 连接器 txt/md 的 `parse_engine=NONE` 在详情页显示“未进入解析”，与 INDEXED 事实
  不一致；owner `riccon/builder-kb-web`，due B8b 启动时（M25 gate 前）。

## 残余风险与下一步

- 50–100 MB 连接器对象会在索引阶段因 Worker 50 MB 解析上限 FAILED（B6 允许 100 MB）；建议 V1b/模块 gate
  明确是否在同步阶段就拒绝不可索引大小（不属 B7b 缺陷）。
- FAILED 连接器文档不会被下一次同步自动重试（`ensureIndexEvent` 只补投 PENDING），恢复依赖 reindex 端点或
  内容变化；建议 V1b 增加“FAILED 连接器文档 reindex 可恢复”的用例。
- 本任务测试以 SQL 直插 CONNECTOR 文档模拟 B6 产物，B6 写入 → worker 读取的整链验证依赖代码核对
  （两边同用 `ConnectorContentTypes.objectKey`、同一 `now` 写 `created_at`）与 V1b 端到端；任务级测试保持隔离是可接受的。
- API 侧 key 派生重复（REV-TASK-0214-001 F-002）仍未消除；B7b 未新增重复实现。

## 工具使用

本评审用 **codegraph** 做调用链/影响面核对（`S3FileContentReader`、`ConnectorContentTypes.objectKey`、
`OpenSearchIndexClient`、`ConnectorSyncService` 写入路径）；未启动 Serena/JDT LS，原因是评审期间要运行 Maven
定向测试，按项目 AGENTS 避免 JDT LS 触碰 `target/` 与测试并发。未发现需要 LSP 精确定位才能判断的符号级修改。
