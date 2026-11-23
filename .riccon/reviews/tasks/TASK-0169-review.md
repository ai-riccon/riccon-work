---
review_id: REV-TASK-0169-001
module_id: M05
task_id: TASK-0169
reviewer:
  agent: riccon/verifier
  session: ses_df9080a2effex7cglBJyh4mQAD
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 539cc731e396cc2c7597036fa54bbb6df18049f1
  - ca6dfd942f57c3fa742949ad0ea75fdd65160505
  - 4b83271f67e8bc856ca6955138b7be701112363c
  - 492c949b5ffe17e4a7a972d9d61d9ce87c62d9b1
  - 12e8183ca700f1066661dfbb3a72923dc4a6a535
  - 2ea0c28960d74f4392b2ce30a50bebd006a3d551
  - 7c199c1cad6e8f59be6ce3d70f87e45430aefcad
head_commit: 7c199c1cad6e8f59be6ce3d70f87e45430aefcad
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - docs
  - security
  - perf
  - migration
dod_note: >-
  任务级 DoD 只判 B7 的索引 v2/模式/parse/bbox、配置修复与定向证据。build 由 EV-0849/EV-0852/EV-0855 的
  Maven test/spotless 编译支撑；tests 由 builder EV-0849（45 用例）、verifier EV-0852（16 用例）与
  EV-0855（配置绑定 + 模式/V2Migration + spotless）支撑；coverage/E2E/a11y/security/perf/迁移属 M05-V1/V2
  与模块 gate，不在任务评审范围。
evidence:
  - EV-0849
  - EV-0852
  - EV-0855
reviewed_evidence:
  - EV-0849
  - EV-0852
  - EV-0855
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-index + riccon/builder-kb-ocr（B5 页级解析）"
    due: "M05 gate 前 / TASK-0176（M05-V1）"
    accepted_reason: >-
      当前 FULL 整本重解析仍能补齐缺失/失败页，且重复投递幂等、不产生重复文档或重复引用；
      变化是成功页的 chunk_id 会重生成，但引用以快照和 source_version 解释，M05 检索也不依赖 OpenSearch。
      B5 当前没有页级解析入口，修复需 B5 提供“只解析缺失页”或 V1 按 page_no 过滤；风险有界，
      但 AC-04 的“force=false 只补缺失页”必须在模块 review 标 residual，不能宣称无残余 pass。
    title: "reindex 的 force=false 与 force=true 行为相同：FULL 始终整本重解析，未实现只补缺失页"
    summary: >-
      OpenAPI `POST /api/v1/knowledge/documents/{id}/reindex` 与 ADR-0023 §7.3/§4.5 明确：缺省
      `force=false` 只补缺失页/失败页，不重复已成功页；`force=true` 才整本重做。`KnowledgeIndexService.handle`
      读取 `force` 后只把它传给 `handleFull`，`handleFull` 只用它写日志，`loadDocument` 总是调用
      `contentExtractor.extract` 并对整本文件重新 `textChunker.chunk`，随后 `replaceChunks` 整本替换。
      因此两个参数值行为完全相同；EV-0849 日志也显示 `mode=FULL force=false` 但整本 2 页重解析。
      当前行为不会产生重复文档/重复引用，但不符合契约的“不重复已成功页”，且会让历史引用的 chunk_id 变化。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:130（读取 force）、
      :150（Javadoc 承认“当前解析链总是整本解析”）、:152-185（handleFull 未按 force 分支）；
      scripts/ops/reindex-knowledge.mjs:15/403（BACKFILL_V2 发送 force:false）；
      contracts/openapi.yaml:1194/1203（force 语义）；docs/adr/ADR-0023-m05-knowledge-depth.md:707、§4.5。
  - id: F-002
    severity: P2
    status: fixed
    owner: "riccon/builder-kb-index + riccon/ops"
    fixed_in: "7c199c1cad6e8f59be6ce3d70f87e45430aefcad"
    title: "application.yml 索引 version/prefix 未使用 ADR env 占位符（已由 7c199c1 修复）"
    summary: >-
      原问题：`ca6dfd9`/`2ea0c28` 提交树的 `application.yml` 使用字面量 `index-version: v1` 与
      `index-prefix: riccon-kb`，没有 `${RICCON_OPENSEARCH_INDEX_VERSION:v1}` /
      `${RICCON_OPENSEARCH_INDEX_PREFIX:riccon-kb}` 占位；`OpenSearchProperties` 的 prefix 是
      `riccon.worker.opensearch`，因此按 ADR/脚本注释设置 `RICCON_OPENSEARCH_INDEX_VERSION=v2` 不会生效
      （独立 Binder 探针显示绑定仍 v1，只有 `RICCON_WORKER_OPENSEARCH_INDEX_VERSION` 生效）。
      修复：`7c199c1` 改为 env 占位符，并新增 `OpenSearchPropertiesBindingTest` 5 例，锁定 ADR 键、
      Spring worker 键、默认 v1/riccon-kb、ADR/Spring 前缀两套键。EV-0855 在干净 worktree `7c199c1`
      上 exit 0，包含 binding/modes/V2Migration 回归与 worker `spotless:check`；binding 测试的 5 例已由评审静态审阅。
      residual：RUNBOOK M05-7 第 951-954 行仍描述修复前的“字面量 v1/需修复”，由 F-008 跟踪。
    evidence: >-
      `git show 7c199c1 -- apps/worker/src/main/resources/application.yml`（index-prefix/index-version 占位符）；
      apps/worker/src/main/resources/application.yml:139-140；
      apps/worker/src/test/java/com/riccon/worker/index/service/OpenSearchPropertiesBindingTest.java:49-95（5 个绑定用例）；
      .riccon/evidence/TASK-0169/EV-0855.json（commit 7c199c1，dirty=false，exit 0，worker spotless）；
      .riccon/evidence/TASK-0169/probe-2026-11-23-index-version-env.log（修复前对照）。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-index"
    due: "M05 gate 前"
    accepted_reason: >-
      脚本在计数不一致/目标索引缺失时 fail-closed，不会把错误状态切到别名；小租户能完整比较分块 ID。
      对于 10k–50k 分块租户，可通过把 `--max-verify-chunks` 降到 10000 先完成计数校验（但会失去 ID 全量比较），
      对 >50k 只计数；这不是数据损坏，而是迁移验证能力缺口。M05-AC-20 的 100k chunk 场景必须先修正或
      明确记录“只计数”的残余，不能声称 IT-KB-113 全量 ID 校验通过。
    title: "重灌脚本的 ID 校验在 10k–50k 分块时因 OpenSearch max_result_window 失败，>50k 仅比计数且未比较文档数/document_id"
    summary: >-
      `verifyIndex` 先比较 MySQL 与目标索引的 `_count`，然后在 `chunkCount <= maxVerifyChunks`
      （默认 50000）时用一次 `_search size=maxVerifyChunks` 拉取全部 ID。OpenSearch 默认
      `index.max_result_window=10000`，所以 10000 < chunkCount <= 50000 的租户会得到 400，脚本失败；
      chunkCount > 50000 时跳过 ID 比较，只保留计数。即使启用 ID 比较，请求使用 `_source:false`，
      只比较 `_id`，没有比较索引文档数、`document_id` 归属或 mapping 版本。
      ADR IT-KB-113 要求“重灌后文档数/分块 ID 与 MySQL 一致且无重复”；当前脚本在大租户上不能完整证明。
    evidence: >-
      scripts/ops/reindex-knowledge.mjs:10、657-689（`size: options.maxVerifyChunks`、`_source:false`、
      `documentCount` 只写入报告未比较）；docs/RUNBOOK.md:1002（已记录超出只比计数，但仍写“文档数”比较）；
      docs/adr/ADR-0023-m05-knowledge-depth.md:640（IT-KB-113 口径）。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/builder-kb-index"
    due: "M05 gate 前"
    accepted_reason: >-
      当前没有 ArchUnit 包级规则被破坏，`FileParseService` 是 service 依赖 repository，MVC 分层仍单向；
      行为由集成测试覆盖。问题只是包职责与未来维护：B5 的 file_chunk 解析仓库和 B7 的 index 仓库被混在
      `index/repository`，后续若有人从 parse 仓反向依赖 index 容易形成环。B7 之后可移动到
      `parse/repository`，或由 Architect 明确架构说明；不影响本任务验收。
    title: "FileChunkRegionRepository 放在 index/repository 并让 FileParseService 依赖 index 包，职责/归属倒置"
    summary: >-
      `FileChunkRegionRepository` 写的是 `file_chunk`（B5 解析链数据），却位于
      `com.riccon.worker.index.repository`；`FileParseService` 因此 import 了 index 包的两个仓库。
      类注释已说明是 TASK-0169 write_scope 的临时放置，`FileRepository.replaceChunks` 语义未变。
      功能与测试通过，但“解析仓库属于 index 包”不是稳定的架构归属，也不利于以后把 B5/B7 仓库合并。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/index/repository/FileChunkRegionRepository.java:1-27、49-80；
      apps/worker/src/main/java/com/riccon/worker/parse/service/FileParseService.java:4-8、51-55、70-83、136-142；
      platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java（Service→Repository 规则）。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/verifier（TASK-0176/0177） + riccon/builder-kb-index"
    due: "M05 gate 前"
    accepted_reason: >-
      `updateParseMetadata` 只是把 `ParseSummaryJson.from(summary)` 序列化后写三列，静态审查与
      `ParsePersistenceIntegrationTest` 已覆盖同一仓库方法；`ParseSummaryJsonTest` 锁定字段 shape，
      API shape test 锁定反序列化。缺口是“FULL 路径没有端到端断言”和“脚本没有集成测试”，不是已知功能失败；
      V1/V2 增加一条 FULL 解析后的 parse_* 断言与脚本 dry-run/DB 用例即可关闭。
    title: "KnowledgeIndexService FULL 写 parse_* 的成功路径和 reindex 脚本缺少直接集成测试"
    summary: >-
      `ParsePersistenceIntegrationTest` 只覆盖 `FileParseService` 写 parse_*，`KnowledgeIndexServiceIntegrationTest`
      没有断言 FULL 成功后 `knowledge_document.parse_engine/parse_warnings/parse_summary` 的值；
      `scripts/ops/reindex-knowledge.mjs` 只有 `--self-test`（纯函数、不连 MySQL/OpenSearch），
      EV-0849 也没有运行脚本的数据库/别名路径。若 `KnowledgeIndexService.updateParseMetadata` 或脚本
      SQL/别名切换回归，现有测试可能仍然全绿。`ParseSummaryJsonTest` 与
      `KnowledgeDocumentParseShapeTest` 分别锁 worker/API shape，但不覆盖索引服务写入链路。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:175-179；
      apps/worker/src/test/java/com/riccon/worker/parse/service/ParsePersistenceIntegrationTest.java（FileParseService 路径）；
      apps/worker/src/test/java/com/riccon/worker/index/service/KnowledgeIndexServiceIntegrationTest.java（无 parse_* 断言）；
      scripts/ops/reindex-knowledge.mjs:850-940（仅 self-test）；
      apps/worker/src/test/java/com/riccon/worker/index/domain/ParseSummaryJsonTest.java；
      apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDocumentParseShapeTest.java。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-index + riccon/architect"
    due: "M05 gate 前"
    accepted_reason: >-
      M05 的检索/授权以 MySQL 为权威，`AgentContextJdbc.search` 过滤 `d.status='INDEXED' AND
      d.deleted_at IS NULL`；索引字段不作为授权依据。FULL 在替换前已把文档置 INDEXING，DELETE 后文档为
      DELETED，因此部分 chunk/索引不会通过检索暴露；outbox 重试可修复大多数中间态。剩余风险是
      索引/分块清理不最终（尤其 FULL 与 DELETE 并发）和诊断字段过期，需在 M05 gate 前决定是否加
      每文档锁/版本回读，或在架构/运行手册中明确残余；不能把“原子替换”写成无条件保证。
    title: "replaceChunks/updateParseMetadata 非事务且 FULL/ACL_ONLY/DELETE 并发时可留下部分分块、过期 ACL 诊断字段或复活已删文档索引"
    summary: >-
      `KnowledgeRepository.replaceChunks` 先 DELETE 再逐条 INSERT，没有 `@Transactional`；进程崩溃/DB
      异常会留下部分或零分块（文档状态 INDEXING/FAILED，检索不命中，重试会重做）。FULL 读取的
      `KnowledgeDocumentRecord` 是开始时的 ACL 快照；如果 ACL_ONLY 先完成、FULL 后写索引，FULL 可能把旧
      visibility/acl_version 写回索引（仅诊断字段，MySQL 授权仍正确）。FULL 与 DELETE 并发时，FULL 在
      `markIndexing` 后仍可能重新插入已软删除文档的 chunks/index，`markIndexed` 因状态不是 INDEXING 而
      失败；检索按 DELETED/status 过滤，不泄露内容，但 DELETE 的“清理分块与索引”不是最终状态，且
      重灌脚本计数会因此 fail-closed。另 `OpenSearchIndexClient.deleteDocumentDocuments` 使用
      `conflicts=proceed` 但不检查响应 `failures`，冲突时可能留下旧文档。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/index/repository/KnowledgeRepository.java:204-250（无事务）；
      apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:152-209（FULL 快照/异常）、
      :219-268（ACL_ONLY）、:270-300（DELETE）；
      apps/worker/src/main/java/com/riccon/worker/index/service/OpenSearchIndexClient.java:313-329（delete_by_query conflicts）；
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:99-108（检索过滤 status/deleted_at）；
      docs/adr/ADR-0023-m05-knowledge-depth.md:707（FULL 原子替换表述）。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      EV-0849/0852 的命令、commit、dirty 状态与 surefire 结果为真实；EV-0855 的 commit/dirty/exit/spotless
      与测试源码已核对（其 10 例计数为 builder 自报，本次独立复跑因锁超时未完成）。EV-0852/0855 在隔离
      worktree 的 ca6dfd9/7c199c1 上运行，EV JSON/日志已回灌主树。实际 B7 代码 diff `539cc73..ca6dfd9`
      16 个文件全部在 write_scope（含 Lead 补录的 3 条测试 glob）；配置修复 `7c199c1` 是独立的 2 文件
      diff（application.yml + binding test）。Lead 计划把 done 的 scope 区间重绑为
      `baseline=7c199c1^`、`end=7c199c1`，只覆盖配置修复；这是**诚实且可追溯**的做法，因为原 B7 代码
      范围仍由 REV-001 + EV-0849 覆盖，配置修复由 EV-0855 + `OpenSearchPropertiesBindingTest` 单独覆盖。
      当前 gate 的 task-scope 报错是 HEAD 已前进到 B8/B9/B11 并行提交导致的范围污染，不是 B7 代码失败；
      Lead 需在干净 checkout/按 rebind 后的 end_commit 重跑模块 gate，并在任务卡保留原 ca6dfd9 范围的
      commit_attribution_note，避免历史归因丢失。
    title: "证据/范围卫生：EV-0849 日志无 surefire 汇总；EV-0852（verifier）与 EV-0855（builder）使用隔离 worktree；当前 gate 的 task-scope 被 B8/B9/B11 并行 WIP 污染"
    summary: >-
      EV-0849 命令使用 `-q`，日志只有 Spring/Testcontainers 输出，没有 “Tests run” 行；builder 自报
      11 类 45 用例，评审从 `apps/worker/target/surefire-reports` 与 `apps/api/target/surefire-reports`
      中 13:48–13:50 的 11 个 XML 汇总得到 45 tests/0 failures/0 errors/0 skipped，与 EV-0849 的 commit
      ca6dfd9、dirty=false、exit 0、log SHA-256 1ac109… 一致。主树在评审期间出现 B8/B9/B11 并行 WIP
      （HEAD 2ea0c28/595120e 及未提交文件），无法在主树录制 dirty=false 的定向复验，因此 EV-0852 在
      `$TMPDIR/riccon-b7-verify-wt`（detached ca6dfd9）运行 6 个测试类 16 用例 0/0/0，EV-0855 在 builder
      worktree 7c199c1 运行配置修复 + spotless。`riccon gate check --module M05` 当前 `task-scope:TASK-0169` 列出
      B8/B9/B11 文件，实际 `git diff --name-status 539cc73..ca6dfd9` 只有 16 个 B7 文件；这是 gate 使用
      当前 HEAD/工作树而非任务 end_commit 的流程问题。范围重绑是诚实的：原 range 由初版 EV/评审覆盖，
      7c199c1 配置修复单独成段，不是把原始 B7 改动藏进新 range。
    evidence: >-
      .riccon/evidence/TASK-0169/EV-0849.json 与 EV-0849 日志（SHA-256 1ac109…，45 tests 汇总自 surefire XML）；
      .riccon/evidence/TASK-0169/EV-0852.json 与日志（SHA-256 6aa660…，16 tests/0/0/0，worktree ca6dfd9）；
      .riccon/evidence/TASK-0169/EV-0855.json 与日志（commit 7c199c1，配置修复/spotless）；
      git diff --name-status 539cc73..ca6dfd9（16 文件）；git diff 7c199c1^..7c199c1（2 文件）；
      git log 7c199c1/2ea0c28/4b83271/8ead4b4；
      `riccon gate check --module M05 --json`（2026-11-23T14:57:00+08:00，task-scope 列出 B8/B9/B11 文件；
      TASK-0193 已 done，仍无模块 review）。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/ops + riccon/architect"
    due: "M05 gate 前"
    accepted_reason: >-
      代码修复已由 `7c199c1` 提交并由 EV-0855 验证；RUNBOOK 第 951-954 行仍写“index-version 是字面量
      v1/修复需改 application.yml”，这是修复前的历史描述，和当前提交树（7c199c1 起已是占位符）不一致。
      只影响运维文档准确性与部署指引，不改变代码/部署行为；Ops/Architect 在 M05-7 注明“7c199c1 已修复”即可。
    title: "RUNBOOK M05-7 仍保留 F-002 修复前的“已知不一致/需修复”措辞"
    summary: >-
      `docs/RUNBOOK.md:947-955` 的 M05-7 仍写着 `application.yml` 的 `index-version` 是字面量 `v1`，并建议
      “修复需把 ... 改成 `${RICCON_OPENSEARCH_INDEX_VERSION:v1}`”。这个建议已经由 `7c199c1` 落地并通过
      `OpenSearchPropertiesBindingTest`/EV-0855 验证；文档若不同步，会让运维误以为仍需额外改动或误用旧键。
    evidence: >-
      docs/RUNBOOK.md:947-955（M05-7 旧表述）；git show 7c199c1 -- apps/worker/src/main/resources/application.yml；
      apps/worker/src/test/java/com/riccon/worker/index/service/OpenSearchPropertiesBindingTest.java；
      .riccon/evidence/TASK-0169/EV-0855.json（配置绑定与 spotless）。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-index"
    due: "M05 gate 前"
    accepted_reason: >-
      EV-0855 在干净环境通过；只有“默认 v1”用例依赖宿主环境。M05 gate/V1 若在 shell 中导出
      `RICCON_OPENSEARCH_INDEX_VERSION` 或 `RICCON_WORKER_OPENSEARCH_INDEX_VERSION=v2` 跑全量测试，
      `ApplicationContextRunner` 的 StandardEnvironment 会看到该变量，默认用例会误失败。生产代码已修复，
      只需在测试中移除/隔离相关 env 键（或用独立 `MockEnvironment`/`withPropertyValues`）；属于 gate 假红风险。
    title: "OpenSearchPropertiesBindingTest 的默认 v1 用例未隔离宿主环境，gate 导出 v2 时可能误失败"
    summary: >-
      `OpenSearchPropertiesBindingTest` 用 `ApplicationContextRunner`（默认 `AnnotationConfigApplicationContext`
      + `StandardEnvironment`）加载真实 `application.yml`；`defaults_areV1AndRicconKb` 没有屏蔽进程的
      `RICCON_OPENSEARCH_INDEX_VERSION` / `RICCON_WORKER_OPENSEARCH_INDEX_VERSION`。若 M05 gate 在 v2 环境跑
      全量测试，默认断言会得到 v2 而失败（生产配置正确，失败是测试隔离问题）。建议在测试初始化器里移除
      这两个环境键，或显式构造不含宿主 env 的 Environment。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/index/service/OpenSearchPropertiesBindingTest.java:40-44、69-78；
      EV-0855（干净 worktree）；docs/RUNBOOK.md:1016-1026（M05 gate 必须使用 v2 索引）。
created_at: "2026-11-23T14:12:00+08:00"
---

# REV-TASK-0169：TASK-0169（M05-B7 索引 v2/ACL_ONLY/DELETE/别名 + parse_*/bbox 持久化）独立评审

## 一页摘要

**verdict：pass（任务级）**。B7 的代码在 `ca6dfd9` 上完成三组核心交付，配置修复在 `7c199c1` 完成并由
EV-0855 验证：

1. **索引 v2/别名**：mapping 含 ADR §7.1 的 `visibility/department_id/acl_version/source_type/connector_id/ocr_confidence/bbox_*`，
   `dynamic: strict`，索引名 `riccon-kb-v2-{tenant}`，别名 `riccon-kb-{tenant}`；`ensureIndex` 只创建目标版本索引，
   不自动把已有 v1 别名切到 v2；`switchAlias` 用一次 `_aliases` 完成 remove/add，支持 v2→v1 回切。
2. **FULL/ACL_ONLY/DELETE**：FULL 从源文件重解析并替换 `knowledge_chunk`、写 parse_*；ACL_ONLY 只读现有分块、
   保持 chunk_id、旧 `aclVersion` 事件跳过、不把文档标 FAILED；DELETE 删除目标文档分块与索引、重复投递幂等、
   软删除文档状态保持 DELETED，跨租户 DELETE 进入 DEAD 不触碰对方。
3. **parse\_*/bbox**：`ParseSummaryJson` 与 API `KnowledgeParseSummary/KnowledgeParseSheetSummary` shape 对齐；
   `FileParseService` 写 parse_*；`knowledge_chunk`/`file_chunk` 的 bbox/ocr_confidence 落库并在 `knowledge_chunk`
   读回；`ParseSummaryJsonTest`、`ParsePersistenceIntegrationTest`、`ChunkRegionPersistenceIntegrationTest`、
   `KnowledgeDocumentParseShapeTest` 覆盖。

没有 P0/P1。**F-002 已 fixed**（`7c199c1` + EV-0855 + `OpenSearchPropertiesBindingTest`）。仍有 **2 个 P2
accepted**：`force=false` 实际整本重解析（F-001）、重灌脚本大租户 ID 校验缺口（F-003）；**6 个 P3 accepted**：
FileChunkRegionRepository 归属（F-004）、FULL/脚本测试缺口（F-005）、并发/非事务/过期诊断字段（F-006）、
证据与 gate 范围卫生（F-007）、RUNBOOK M05-7 旧措辞（F-008）、binding 测试 env 隔离（F-009）。

**任务级 pass 不等于 M05 模块 gate/AC 完成**：F-001 影响 M05-AC-04 的 `force=false` 语义，模块 review 必须标
`pass (with residual)`；F-002 的代码修复已完成，但真实 v2 重灌/别名切换仍由 Ops 在 M05 gate 前执行；B9 的
引用读取/sourceStatus、B8 的 UI、E2E/a11y、全量 contract/coverage/E2E/perf/迁移由 M05-V1/V2 承接。

## 1. 评审对象与依据

- 任务：TASK-0169（M05-B7），高风险（索引/ACL 一致性/重灌），owner `riccon/builder-kb-index`，状态 `review`。
- 初版 B7：baseline `539cc731e396cc2c7597036fa54bbb6df18049f1`，代码提交
  `ca6dfd942f57c3fa742949ad0ea75fdd65160505`；`4b83271` 补录测试 scope；`492c949` 只格式化
  `KnowledgeIndexModesIntegrationTest`（2 行空白，无功能变化）。
- 配置修复：`7c199c1cad6e8f59be6ce3d70f87e45430aefcad`（`application.yml` 占位符 + `OpenSearchPropertiesBindingTest`
  5 例），由 EV-0855 取证；binding 测试的 5 个用例已由评审静态审阅。
- 评审期间 Ops/Lead 新增 `TASK-0193`（索引 v2 部署/重灌 RUNBOOK，commit `12e8183`；EV-0854 只做
  grep/diff/contract），Lead 在 `2ea0c28` 把 application.yml 追加进 TASK-0169 scope。
- **范围重绑计划**：Lead 计划把 done 的 `baseline/end` 重绑为 `baseline=7c199c1^`、`end=7c199c1`，只覆盖
  配置修复。评审认为这是诚实的做法：原 B7 代码 range 仍由 REV-001 + EV-0849 覆盖，配置修复 diff 只有
  2 文件（application.yml + binding test），由 EV-0855 单独覆盖；任务卡应同时保留原 `ca6dfd9` 范围的
  `commit_attribution_note`，避免历史归因被新 range 抹掉。
- 依据：ADR-0023 §7.1/§7.3/§5.5/§4.3/§10/§11.2（IT-KB-113）；PRODUCT M05-AC-02/03/04/07/08/15/21/22；
  `contracts/openapi.yaml` 1.4.0-m05（reindex/force、parseSummary、Citation bbox/sourceStatus）；
  V12 DDL；REV-TASK-0167-001 F-001、REV-TASK-0168-001 F-004；`docs/ARCHITECTURE.md` M05 异步与索引段。

## 2. 静态审查（先读产物）

### 2.1 mapping / 索引命名 / 别名

- **mapping v2**：`OpenSearchIndexClient.indexMapping`（`OpenSearchIndexClient.java:511-557`）包含 ADR §7.1 全部字段；
  `dynamic: strict`；`embedding` 维度来自 `EmbeddingModel`。`KnowledgeIndexV2MigrationTest.assertMappingV2` 在真实
  OpenSearch 上断言 11 个新字段和 strict，测试通过。
- **索引命名**：`indexName(tenantId)` 使用 `indexPrefix + "-" + indexVersion + "-" + tenantSuffix`；
  `indexSuffix` 拒绝大写租户和非法字符，避免大小写归一化跨租户碰撞。v1/v2 命名由 `indexVersion` 决定。
- **ensureIndex 不误切**：`ensureIndex`（:116-123）先 `createIndexIfMissing(indexName(tenantId))`，仅当别名不存在时
  创建别名；已有 v1 别名不会被自动切到 v2。`KnowledgeIndexV2MigrationTest` 第 2 步断言 v2 创建后别名仍指 v1。
- **switchAlias 原子切换/回切**：`switchAlias`（:277-304）先 HEAD 校验目标索引存在，再构造 remove/add actions，
  一次 `POST /_aliases` 提交；测试在真实 OpenSearch 上 v1→v2→v1 两次切换均通过，切换前后目标断言唯一。
- **重灌脚本**：`scripts/ops/reindex-knowledge.mjs` 按 `status='INDEXED'` 写 FULL 事件、等待 DONE、按租户
  verify（计数/ID）后切别名；`--alias-only --index-version v1` 支持回切。`--self-test` 通过。
  设计上 `v1` 保留 7 天、运行期检索不依赖 OpenSearch，符合 ADR；脚本的验证能力有缺口（F-003）；
  RUNBOOK 已由 TASK-0193 补齐（`12e8183`），application.yml 的占位符修复已由 `7c199c1` 提交并验证（F-002 fixed）；
  RUNBOOK M05-7 的旧措辞仍需同步（F-008）。

### 2.2 FULL / ACL_ONLY / DELETE

- **FULL**：`handleFull`（`KnowledgeIndexService.java:152-210`）条件 `markIndexing`（排除 DELETED）、校验 payload
  fileId、从文件重解析或从现有分块重建、`replaceChunks`、`updateParseMetadata`、embedding、`replaceDocument`、
  `markIndexed`；异常按 Event/Parse/Index/Runtime 分类 `markFailed`。`KnowledgeIndexServiceIntegrationTest`
  覆盖文件文档/SEED/缺失来源/已删除/跨租户 fileId；`KnowledgeIndexV2MigrationTest` 覆盖重灌幂等与 ID 一致。
- **ACL_ONLY**：`handleAclOnly`（:219-268）对 DELETED 跳过；`payloadAclVersion < document.aclVersion()` 跳过；
  读现有 chunks；重新 embedding 并 `replaceDocument`；失败只抛 EventProcessingException，不把文档标 FAILED。
  `KnowledgeIndexModesIntegrationTest.aclOnly_...` 断言 2 个 chunk ID 不变、索引 visibility/acl_version 更新、
  旧版本事件跳过且 outbox DONE、文档仍 INDEXED。
- **DELETE**：`handleDelete`（:270-300）删除 `knowledge_chunk` 后 `indexClient.deleteDocument`；
  `deleteDocument` 同时清理当前版本索引与别名目标，`_delete_by_query` 带 tenant_id+document_id 双过滤；
  重复 DELETE 幂等；文档状态保持 DELETED。`KnowledgeIndexModesIntegrationTest` 覆盖删除分块/索引、二次删除、
  无分块失败文档、跨租户 DELETE（DEAD 且 B 租户分块/索引不变）。
- **软删除文档**：FULL 在入口检查 DELETED 返回；ACL_ONLY 跳过；DELETE 不改状态。测试覆盖 FULL/默认跳过。
- **并发/事务边界**：replaceChunks 与 updateParseMetadata 不是同一事务，FULL/ACL_ONLY/DELETE 可并发；见 F-006。
  当前检索 `AgentContextJdbc.search` 过滤 `status='INDEXED' AND deleted_at IS NULL`，所以中间态/已删文档不会
  通过检索泄露；这是把该问题留在 P3 的主要依据。

### 2.3 parse_* 契约 shape 与持久化

- `ParseSummaryJson`（`index/domain`）只序列化 `sheets[{name,cellRange,empty,indexed,indexedRange}]`、
  `hiddenSheetCount`、`ocr{averageConfidence,lowConfidencePages,failedPages}`；不包含 worker 内部的
  `sheetName/rowCount/columnCount/skippedRange/indexedRanges`。`ParseSummary.SheetSummary` 新增 `name()/cellRange()/
indexed()` 契约视图，`unionRange` 合并已索引/未索引范围。
- `KnowledgeRepository.parseMetadataParams` 用 `ParseSummaryJson.from(summary)` 写 `parse_engine/parse_warnings/
parse_summary`；`FileParseService` 在文件解析成功后调用 `updateParseMetadataForFile`，只更新
  PENDING/INDEXING/FAILED，避免覆盖已 INDEXED 文档；`KnowledgeIndexService.handleFull` 在同一次解析结果中写。
- API `KnowledgeJdbc.parseSummary` 用 `KnowledgeParseSummary` 反序列化；`KnowledgeDocumentParseShapeTest`
  锁定 worker JSON shape 可被 API 读取、`KnowledgeDocumentResponse` 映射不丢字段。
- `ParsePersistenceIntegrationTest` 覆盖 XLSX `POI`/工作表摘要/2 行区域、PDF `PDFBOX`/空 warnings/无 summary；
  `ParseSummaryJsonTest` 覆盖 null/字段名/OCR 平均置信度/unionRange。

### 2.4 bbox / ocr_confidence 持久化

- `KnowledgeChunkRecord` 增加 `BoundingBox bbox`、`BigDecimal ocrConfidence`，构造器校验 0–1；保留 8 参数兼容构造器。
- `KnowledgeRepository.replaceChunks` 写 `knowledge_chunk` 的 `bbox_x/y/w/h/ocr_confidence`，`findChunks` 读回；
  `ChunkRegionPersistenceIntegrationTest` 断言 OCR chunk 四列 + 置信度、文本 chunk NULL、读回一致。
- `FileChunkRegionRepository.updateRegions` 在 `FileRepository.replaceChunks` 之后按 `parse_id + ordinal` 补
  `file_chunk` 区域；文本分块跳过；`ChunkRegionPersistenceIntegrationTest` 断言 ordinal 1 写入、ordinal 2 NULL。
- B9（TASK-0171）在评审期间由 planned 转 in_progress，尚未读取这些列；B7 只保证数据可供 B9 读取。
  引用详情 API/UI 仍是模块 residual。

### 2.5 sourceStatus 不入索引

- `OpenSearchIndexClient.indexMapping/document` 没有 `source_status`；index 只写 `visibility/department_id/acl_version/
source_type/connector_id/parse_engine/bbox/ocr_confidence` 等诊断字段。`AgentContextJdbc.search` 和知识库 ACL
  都在 MySQL 上计算，`SOURCE_STATUS`/`source_version` 的读取由 B9 计划。符合 ADR §5.5/§7.1“索引字段不作为授权依据”。
- 没有发现把 sourceStatus 写入索引造成的过期快照；B9 未实现，因此本任务不能验证 AC-07 的终点。

### 2.6 代码质量/注释/范围

- 新增/修改生产代码均有中文 Javadoc（类、方法、参数、返回、异常边界），测试有中文说明；无 TODO/FIXME/吞异常。
- `FileChunkRegionRepository` 的包位置是 B7 write_scope 限制下的临时决定，类注释说明了理由；见 F-004。
- scope：`git diff --name-status 539cc73..ca6dfd9` 为 16 个文件，全部落在原 write_scope 或 Lead 补录的 3 条测试 glob：
  - `apps/worker/src/main/java/com/riccon/worker/index/**`（domain/repository/service）
  - `apps/worker/src/main/java/com/riccon/worker/parse/service/FileParseService.java`
  - `apps/worker/src/main/java/com/riccon/worker/parse/domain/ParseSummary.java`
  - `apps/worker/src/test/java/com/riccon/worker/index/**`
  - `apps/worker/src/test/java/com/riccon/worker/parse/service/ParsePersistenceIntegrationTest.java`
  - `apps/api/src/test/java/com/riccon/api/knowledge/KnowledgeDocumentParseShapeTest.java`
  - `scripts/ops/reindex-knowledge.mjs`
    已评审的 16 文件没有迁移、配置、前端、契约文件越界。配置修复 `7c199c1` 单独为 2 文件 diff
    （`application.yml` + `OpenSearchPropertiesBindingTest`），任务卡已在 scope_amendment 追加
    `application.yml`；EV-0855 在该提交上取证。范围重绑时应在任务卡保留原 `ca6dfd9` 范围的
    `commit_attribution_note`，避免丢失历史归因。

### 2.7 测试对旧实现的回归力

- `KnowledgeIndexV2MigrationTest` 依赖新的 `switchAlias/aliasTargets`、v2 mapping 和 `KnowledgeIndexService`
  构造，旧实现无法编译/断言；`KnowledgeIndexModesIntegrationTest` 依赖 `mode` 解析、DELETE 和 bbox 新字段，
  旧实现会把未知 mode 当 FULL/DELETE 不生效；`ChunkRegionPersistenceIntegrationTest`、`ParsePersistenceIntegrationTest`
  依赖 V12 列与 parse_* 写入，旧实现失败；`ParseSummaryJsonTest` 依赖新类，旧实现编译失败。
- `KnowledgeDocumentParseShapeTest` 只锁定 API DTO 反序列化 shape，单独对旧实现也可能通过，不能作为 B7 行为
  回归证据；它和 `ParseSummaryJsonTest` 组合才覆盖 worker→API 的字段名一致性。
- 45 个 builder 用例与 16 个 verifier 用例都无 skip/zero-test；`_q` 日志本身没有测试汇总（F-007），但 surefire
  XML 与测试类名一致，不是“执行零个测试”的假绿。

## 3. 证据审计

### 3.1 EV-0849（builder）

- `commit=ca6dfd9`、`dirty=false`、`exit_code=0`、`output_bytes=33447`、`output_sha256=1ac10999...1e82bd`，
  与文件实际 SHA-256 一致。
- 命令包含 11 个测试类和 Maven 锁；`-q` 导致日志没有 surefire 汇总。评审按日志时间窗 13:48–13:50
  汇总 `apps/worker/target/surefire-reports` 与 `apps/api/target/surefire-reports` 的 11 个 XML：
  **45 tests / 0 failures / 0 errors / 0 skipped**，与 builder 自报一致；汇总见
  `.riccon/evidence/TASK-0169/EV-0849-surefire-summary.txt`。各测试类数量：
  ParseSummaryJson 5、HashingEmbeddingModel 6、OpenSearchIndexClientError 2、KnowledgeDocumentParseShape 2、
  KnowledgeIndexServiceIntegration 7、OpenSearchIndexClient 5、KnowledgeIndexModes 4、KnowledgeIndexV2Migration 1、
  ChunkRegionPersistence 2、ParsePersistence 2、FileParseServiceIntegration 9。
- 未发现跳过的 `@Disabled`/`assumeTrue`；没有 retry/`--no-file-parallelism` 等掩盖参数。

### 3.2 EV-0852（verifier，独立）

- 环境：主树在评审期间出现 B8/B9/B11 并行 WIP（HEAD 2ea0c28 及未提交文件），为保持 `dirty=false` 和
  `ca6dfd9` 代码，使用 `$TMPDIR/riccon-b7-verify-wt` detached ca6dfd9；复制 `.riccon` 元数据后运行 6 个测试类。
- 结果：`commit=ca6dfd9`、`dirty=false`、`exit_code=0`、`output_bytes=21175`、`output_sha256=6aa66013...77927`；
  surefire 汇总 **16 tests / 0 failures / 0 errors / 0 skipped**（Modes 4、V2Migration 1、ChunkRegion 2、
  ParsePersistence 2、ParseSummaryJson 5、KnowledgeDocumentParseShape 2），见
  `.riccon/evidence/TASK-0169/EV-0852-surefire-summary.txt`。
- EV JSON/日志已回灌主树 `.riccon/evidence/TASK-0169/`。worktree 是验证环境例外（F-007），不是主树代码缺陷。

### 3.3 EV-0855（builder，配置修复）

- EV-0855：`commit=7c199c1`、`dirty=false`、`exit_code=0`，命令运行
  `OpenSearchPropertiesBindingTest,KnowledgeIndexModesIntegrationTest,KnowledgeIndexV2MigrationTest` 后
  worker `spotless:check`；builder worktree 为 `$TMPDIR/riccon-task-0169-config-wt`。日志因 `-q` 无汇总，
  需用 surefire/独立 EV 复核。
- verifier 独立复跑尝试：在 `$TMPDIR/riccon-b7-config-verify-wt` detached `7c199c1` 运行同一组合，
  因 Maven 锁等待超过 10 分钟超时未完成，**未作为通过依据**；F-002 以 EV-0855、binding 测试静态审阅与
  原 EV-0852 的 B7 回归为依据。
- `OpenSearchPropertiesBindingTest` 5 例锁定：ADR `RICCON_OPENSEARCH_INDEX_VERSION=v2` → v2；
  `RICCON_WORKER_OPENSEARCH_INDEX_VERSION=v2` 覆盖；默认 v1/riccon-kb；ADR/Spring 前缀两套键。
  测试直接加载真实 `application.yml`，不是只测常量，能对“按 ADR 设置却跑 v1”的旧实现失败。

### 3.4 里程碑/门禁

- `riccon gate check --module M05 --json`（2026-11-23T14:57:00+08:00）`ok=false`：
  TASK-0169 仍 review、TASK-0170/0171/0173 in_progress、TASK-0176/0177 planned、无模块 review；这是模块未收口。
  TASK-0193 已 done，不再出现在失败列表。`task-scope:TASK-0169` 当前列出 B8/B9/B11 文件，是 HEAD 已前进
  导致的**范围污染**，不是 `539cc73..ca6dfd9` 的真实 diff（真实 diff 16 文件全在 scope）；Lead 需在干净
  checkout/按 `end_commit` 重跑模块 gate。任务级 verdict 不替代模块 gate。

## 4. 结论对照（任务 acceptance）

| #   | 验收原文（TASK-0169.acceptance）                                                                                                                                   | 结论                      | 证据/残余                                                                                                                                          |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | mapping v2、FULL/ACL_ONLY/DELETE、别名切换/回切；IT-KB-113                                                                                                         | **pass**                  | 静态 §2.1–2.2；`KnowledgeIndexV2MigrationTest`、`KnowledgeIndexModesIntegrationTest`；EV-0849/0852/0855；F-002 已 fixed；残余 F-003 脚本大租户校验 |
| 2   | ACL_ONLY 不替换分块；重灌幂等；无跨租户写入                                                                                                                        | **pass**                  | ACL_ONLY chunk_id 不变/旧版本跳过；V2Migration 重复重灌计数/ID 一致；Modes cross-tenant DELETE DEAD；EV-0849/0852                                  |
| 3   | 持久化 parse_engine/parse_warnings/parse_summary；knowledge_chunk/file_chunk bbox/ocr_confidence；worker ParseSummary 与 API KnowledgeParseSheetSummary shape 对齐 | **pass（with residual）** | ParsePersistence/ChunkRegion/ParseSummaryJson/KnowledgeDocumentParseShape 测试；F-005 只测 FileParseService 路径；B9 读取/UI 未实现                |

## 5. AC 对账（M05 段，任务级不做模块 pass）

| AC        | 结论                               | 依据/残余                                                                                                       |
| --------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| M05-AC-02 | **pass with residual（B8/B9）**    | `knowledge_chunk` 的 `sheetName`/`cellRange` 已持久化并可读；引用详情显示/高亮由 B8/B9。                        |
| M05-AC-03 | **pass with residual（B7/B8/B9）** | OCR bbox/ocr_confidence 落库，文本型 PDF/DOCX 为 NULL；B5 解析链与 B7 bbox 落库已闭环；引用详情展示仍属 B8/B9。 |
| M05-AC-04 | **pass with residual（F-001）**    | `force=false` 仍整本重解析；能补齐缺失页且幂等，但不满足“不重复已成功页”的 OpenAPI 语义；B5/V1 承接。           |
| M05-AC-07 | **not in B7（B9）**                | sourceStatus 未入索引是正确的；B9 尚未实现读取遮蔽，不能由本任务声称 AC-07 pass。                               |
| M05-AC-08 | **partial（B3/B7/B9）**            | ACL 变更由 B3 立即生效；ACL_ONLY 索引刷新已实现；B9/E2E 未跑。                                                  |
| M05-AC-15 | **partial（B3/B7/B8）**            | DELETE 模式与幂等已实现；API 端点/B8 重试入口属其他任务。                                                       |
| M05-AC-21 | **partial（B3/B7/M09）**           | DELETE 清理索引/分块；保留到期任务与物理清理不属 B7。                                                           |
| M05-AC-22 | **partial（V1/V2）**               | IT-KB-113 在 worker 集成层通过；全量 contract/E2E/越权/性能由 M05-V1/V2。                                       |

## 6. Findings

按严重级别排序；全部 `accepted`，无 open/P0/P1。详细 owner/due/accepted_reason 见 front matter。

### F-001（P2）reindex `force=false` 与 `force=true` 行为相同

**观察**：`handleFull` 只读取 `force` 用于日志；`loadDocument` 总是整本解析并 `replaceChunks`。EV-0849 日志中
`force=false` 但 2 页文档仍是 `chunks=2 parseEngine=PDFBOX` 的整本重做。

**期望**：OpenAPI/ADR 的默认 `force=false` 只补 `knowledge_chunk.page_no` 缺失/失败页，不重复已成功页；
`force=true` 才整本重做。B5 当前没有页级入口，修复应归 B5/V1 或由 B7 在解析结果上按页过滤后保留旧 chunk。

**影响**：重试仍可补齐缺失页且幂等，不产生重复文档/引用；但 chunk_id 全量变化，与契约“不重复已成功页”
不符。M05 gate 前要么实现，要么由 Lead 明确修订契约并在模块 review 保留 AC-04 residual。

### F-002（P2 → fixed）v2 部署开关未接通

**原观察**：`ca6dfd9`/`2ea0c28` 提交树中 `application.yml` 仍是字面量 `index-version: v1`，Binder 探针显示
ADR/脚本写的 `RICCON_OPENSEARCH_INDEX_VERSION=v2` 不生效（绑定仍 v1），只有
`RICCON_WORKER_OPENSEARCH_INDEX_VERSION` 生效；旧 v1 strict mapping 缺新增字段，新 FULL 事件会 400，
重灌 wait/verify 失败且不切别名。

**修复**：`7c199c1` 把 `application.yml` 改为
`index-prefix: ${RICCON_OPENSEARCH_INDEX_PREFIX:riccon-kb}`、
`index-version: ${RICCON_OPENSEARCH_INDEX_VERSION:v1}`，新增 `OpenSearchPropertiesBindingTest` 5 例：
ADR version 键、Spring worker version 键、默认 v1/riccon-kb、ADR/Spring prefix 键。EV-0855 在干净 worktree
`7c199c1` 上 exit 0（含 binding/modes/V2Migration 回归与 worker spotless）；binding 测试 5 例已静态审阅。`git show 7c199c1 -- ...application.yml` 与测试文件均已核对。

**残余**：RUNBOOK M05-7 的旧“已知不一致/需修复”措辞需同步（F-008）；真实 v1→v2 重灌/别名切换仍需 Ops 在
M05 gate 前执行，不能把 EV-0855 的单元/集成测试当作真实重灌。F-002 代码问题本身 fixed。

### F-003（P2）重灌脚本大租户校验缺口

**观察**：`verifyIndex` 使用 `_search size=maxVerifyChunks`（默认 50000），超过 OpenSearch 默认
`max_result_window=10000` 时 10k–50k 分块直接 400；>50k 只计数不比较 ID；`_source:false` 未比较
`document_id`，`documentCount` 未与索引文档数比较。ADR IT-KB-113 要求文档数/分块 ID 一致且无重复。
评审期间 RUNBOOK M05-8（`docs/RUNBOOK.md:1002`）已记录 `--max-verify-chunks` 默认 50000 与“超出只比计数”，
但仍写“对比 MySQL 分块数与 v2 索引文档数/分块 ID”，与脚本实际未比较文档数/`document_id` 不一致。

**期望**：用 PIT/scroll/分页拉取 ID，或先按 `document_id` 聚合比较文档数；设置 `maxVerifyChunks` 与
OpenSearch `max_result_window` 一致并写文档；大租户至少保留计数 + 文档数 + 抽样/全量 ID 的可配置策略。

### F-004（P3）FileChunkRegionRepository 归属倒置

`FileChunkRegionRepository` 写 `file_chunk` 却位于 `index/repository`；`FileParseService` 因此 import
index 包。类注释说明是 B7 write_scope 的临时放置；没有 ArchUnit 规则被破坏，行为测试通过。建议 B7 之后
移动到 `parse/repository` 或由 Architect 写归属说明，避免 parse→index 的反向依赖长期化。

### F-005（P3）FULL/脚本测试缺口

`ParsePersistenceIntegrationTest` 只覆盖 `FileParseService` 写 `parse_*`；`KnowledgeIndexServiceIntegrationTest`
没有断言 FULL 后三列值；`reindex-knowledge.mjs` 只有纯函数 self-test，未连 MySQL/OpenSearch。建议 V1/V2 增加
一条 FULL 文件文档 `parse_*` 断言和脚本 dry-run/DB 用例；否则这两条链路的回归可能漏检。

### F-006（P3）并发/非事务/过期诊断字段

`replaceChunks` 的 DELETE+INSERT 无事务；FULL 的文档快照与后到的 ACL_ONLY 可能让索引 ACL 诊断字段回退；
FULL 与 DELETE 并发可重新插入已删文档 chunk/index；`_delete_by_query?conflicts=proceed` 未检查 failures。
检索以 MySQL `status='INDEXED' AND deleted_at IS NULL` 为准，索引不参与授权，所以没有内容泄露；但“原子替换”
表述过强，DELETE 清理不是最终状态。M05 gate 前决定加每文档锁/版本回读或写残留说明。

### F-007（P3）证据/范围卫生

EV-0849 的 `-q` 日志没有测试汇总；评审用日志时间窗的 11 个 surefire XML 复核 45/0/0/0，EV commit/dirty/exit/SHA
均一致。主树并行 WIP 使 verifier 只能在 worktree ca6dfd9 录 EV-0852（16/0/0/0）；EV-0855 是 builder 在
worktree 7c199c1 的配置修复证据（builder 自报 10 例 + spotless；本次独立复跑因锁超时未完成，未作为通过依据）。
范围重绑为 `baseline=7c199c1^`、`end=7c199c1` 是诚实的：原 B7 代码 range 仍由
REV-001 + EV-0849 覆盖，配置修复单独由 2 文件 diff + EV-0855 覆盖。当前 gate 的 task-scope 把
B8/B9/B11 并行提交算到 TASK-0169；实际初版 end_commit diff 16 文件全在 scope。Lead 需在干净 checkout/
按 rebind 后 end_commit 运行模块 gate，并保留原 range 的 commit_attribution_note。

### F-008（P3）RUNBOOK M05-7 旧措辞未同步

`docs/RUNBOOK.md:951-954` 仍写 `application.yml` 的 `index-version` 是字面量 v1、建议改成占位符；该修复
已由 `7c199c1` 落地并由 EV-0855 验证。文档若不更新，会误导运维重复修改或误用旧键。仅文档同步，
不改变代码/部署行为；Ops/Architect 在 M05-7 注明“7c199c1 已修复”即可。

### F-009（P3）binding 测试默认用例未隔离宿主 env

`OpenSearchPropertiesBindingTest` 的 `defaults_areV1AndRicconKb` 通过 `ApplicationContextRunner`
（默认 `StandardEnvironment`）加载真实 `application.yml`，但没有移除宿主进程的
`RICCON_OPENSEARCH_INDEX_VERSION` / `RICCON_WORKER_OPENSEARCH_INDEX_VERSION`；M05 gate 在 v2 环境跑全量测试时
该默认断言会误失败。生产配置修复本身正确（EV-0855），只需测试隔离 env 键；作为 P3 accepted 跟踪，避免把
gate 假红当成 v2 配置回归。

## 7. 未验证 / 不适用

- **全量合同 build/lint/typecheck/coverage/E2E/a11y/security/perf/迁移**未在任务级运行；由 M05-V1/V2 在最终
  提交上执行，本评审的 EV-0849/0852/0855 只是定向 worker/api 测试。
- **真实 v1→v2 部署/重灌**未在 compose 测试栈执行：只做了 Testcontainers 内 v2 客户端/服务测试、脚本
  `--self-test` 与 `OpenSearchPropertiesBindingTest`/Binder 探针。F-002 的代码修复已由 7c199c1 提交并
  由 EV-0855 验证，但真实重灌/别名切换仍需 Ops 在 M05 gate 前执行；F-003 需要修正脚本/记录大租户限制。
- **B9 引用读取/sourceStatus**未实现：B7 只证明 bbox/ocr_confidence 可持久化/读回；AC-07/引用 UI 不能由本
  任务判 pass。
- **100k chunk/50 并发/大租户**：TASK-0173 已有 100k chunk 的 perf 证据，但 `perf-m04-matrix.json` 的
  `openSearch` 是 `index-version v1`，且 `knowledge.retrievalCapture=failed`（`samples=0`），不能作为
  M05-AC-20 或 v2 迁移通过证据；V2/Perf 必须在 v2 索引下重跑。F-003 的脚本 ID 校验也需大租户补证。
- **并发故障注入**未跑：F-006 的 FULL/ACL_ONLY/DELETE 竞态只做静态分析和检索过滤核对，没有线程/故障注入测试。
- **v1 保留 7 天清理**未实现：脚本只切别名，没有自动删除 v1；由 Ops/M09 运维策略处理。
- **`reindex-knowledge.mjs` 真实 DB/OpenSearch 集成**未跑：F-005。
- **API `KnowledgeJdbc.parseSummary` 从真实 DB 读取 worker JSON** 未在 EV 中端到端跑；由 `ParsePersistenceIntegrationTest`
  原始 JSON + `KnowledgeDocumentParseShapeTest` 组合覆盖，B8/B9 可再补。

## 8. 需 Lead/Ops 处理与下一步

| 项          | 动作                                                                                                                                                                                                   |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| F-001       | Lead 确认：B5 增加页级解析或 V1 在重灌时按页保留成功 chunk；否则在 OpenAPI/ADR 明确改为“整本重做”并重开 AC-04。                                                                                        |
| F-002       | 代码修复已 fixed（7c199c1/EV-0855）。Ops/Architect：按计划重绑 `baseline=7c199c1^`、`end=7c199c1` 并保留原 ca6dfd9 归因；M05 gate 前在测试栈运行 `reindex-knowledge.mjs --apply` 并录 dirty=false EV。 |
| F-008       | Ops/Architect：把 RUNBOOK M05-7 第 951-954 行改为“7c199c1 已修复”，保留 workaround/升级说明；文档-only。                                                                                               |
| F-009       | builder-kb-index：binding 测试的默认用例移除/隔离宿主 `RICCON_OPENSEARCH_INDEX_VERSION`/`RICCON_WORKER_OPENSEARCH_INDEX_VERSION`，避免 M05 gate v2 环境假红。                                          |
| F-003       | builder-kb-index：把 `verifyIndex` 改为 PIT/scroll/分页并比较文档数/document_id；或用 `--max-verify-chunks` + 文档数校验形成明确策略。                                                                 |
| F-004/F-005 | Architect/verifier：B7 之后移仓库或写归属说明；V1/V2 补 FULL parse_* 与脚本集成测试。                                                                                                                  |
| F-006       | builder/architect：评估每文档锁/版本回读；至少修正“原子”措辞并在 RUNBOOK 写 DELETE 最终一致性残余。                                                                                                    |
| F-007       | Lead：在干净 checkout 按 end_commit 重跑 `gate check`；不要把 B8/B9/B11 并行提交算作 TASK-0169 越界；统一后续 EV 取证口径。                                                                            |
| B9/B8/V1/V2 | 引用 sourceStatus/bbox UI、E2E、a11y、contract、perf、迁移回滚由后续任务完成；模块 review 必须把这些写成 residual。                                                                                    |

**任务状态建议**：保持 `review`；本评审不修改任务/模块状态。**F-002 fixed、无 P0/P1，任务可从 verifier
角度 done**，前提是 Lead 完成计划中的范围重绑（`baseline=7c199c1^`、`end=7c199c1`，保留原 ca6dfd9 归因）
并把 EV-0855 列入证据；M05 模块 gate 仍应失败（任务未 done、模块未评审、F-001/F-003 accepted 与真实
v2 重灌/Ops 文档同步尚未完成）。
