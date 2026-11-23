---
review_id: REV-TASK-0197-001
module_id: M05
task_id: TASK-0197
reviewer:
  agent: riccon/verifier
  session: ses_df88702a6ffe0cnQutcdPEpCaH
  model: deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - a97619783a0586378a0828caff0be303e8ad0c08
head_commit: a97619783a0586378a0828caff0be303e8ad0c08
baseline_commit: 15472ad20f7011fe0876c873a2b781d605f22c54
previous_review: REV-TASK-0171-001
dod:
  acceptance: false
  build: true
  tests: false
  migration: true
  security: true
  perf: false
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - docs
dod_note: >-
  任务级实现路径基本成立：V13 additive 迁移与回滚在 Testcontainers MySQL 8.4.13 上通过，J1/J2/阶段
  sources 的 bbox/ocrConfidence 暴露、AVAILABLE/UPDATED 正常、NO_ACCESS/DELETED 遮蔽与历史 NULL
  降级均有真实 MySQL 用例；worker schema 13 与 Spotless 通过。但 acceptance #2 的“无 N+1”未完全满足：
  ArtifactService.listVersions 仍按版本各查一次区域（F-002，P2 accepted）；tests=false 是因为
  V10MigrationTest fresh install 在 V13 后仍断言 schema 12，独立复现失败（F-001，P1 open），会使合同
  test / 模块 gate 的迁移基线变红。docs 按 Lead 决定（RUNBOOK 等 V14 后统一更新）列为 P3 accepted，
  不计入本任务 DoD。security 只代表本任务定向 ACL/租户遮蔽与静态边界，不代表模块 security gate。
  本评审不把 M05-AC-03/04 判 pass：本任务没有 web diff，也没有真实浏览器 E2E；API 前置已补齐，
  模块级 AC 仍需 V1/V2 UI/E2E 证据。
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-kb-citation
    due: 重新提交前（M05 gate 前）
    title: "V13 后 V10MigrationTest fresh install 仍断言 schema 12，迁移基线测试失败"
    summary: >-
      V10MigrationTest 的 fresh install 使用 migrateLatest(...)，会应用当前全部迁移；V13 加入后
      Flyway 当前版本为 13，但测试仍断言 "12"。独立 EV-0912 在 a976197 上稳定复现
      expected: "12" but was: "13"（11 tests / 1 failure / 0 error），不是 flake。
      builder EV-0910 的 -Dtest 只选 V11/V12/V13 与 Citation/Chat/Research，未包含 V10，
      因此“定向全绿”是测试选择缺口，不是伪造证据。write_scope 已含
      `apps/api/src/test/java/**/migration/**`，该文件本可在本任务内修复；修复应把 V10 fresh 断言与注释
      升到 13（或明确改名为固定 target 12，但不能继续用 latest 目标断言旧版本），并把 V10MigrationTest
      加入该任务的迁移回归命令；随后在全量 test 或至少全部 `*MigrationTest` 上复验。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:223（migrateLatest）、
      :585-590（V1–V12 注释与 isEqualTo("12")）；
      EV-0912（verifier，exit 1，expected "12" but was "13"）、
      EV-0911（verifier，同一 commit 定向全绿，证明其他改动面未红）、
      EV-0910（builder，命令未包含 V10MigrationTest）。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/builder-kb-citation
    due: M05 gate 前（与 REV-TASK-0171-001 F-002 一并修复）
    accepted_reason: >-
      该扇出只在产物版本列表，不在 J1 消息页、J2 产物详情/单版本或阶段 sources 的主路径；
      REV-TASK-0171-001 F-002 已接受同一端点的状态/ACL per-version N+1，本任务只是把区域查询也放进
      同一循环，没有新增安全或正确性风险。它仍违反本任务 acceptance #2 的“无 N+1”字面要求，
      必须在模块 gate 前与 F-002 一起把版本列表的状态与区域查询改为整页批量，不能留到 release。
    title: "产物版本列表按版本各查一次分块区域，新增 N+1"
    summary: >-
      ArtifactService.listVersions 对每个版本调用 withCitations → withStatus；withStatus 内
      citationRegionRepository.findRegions 每次调用执行一次 knowledge_chunk IN 查询。N 个版本
      产生 N 次区域查询（另加每版本状态/ACL 查询），而不是整页一次。单版本/详情/聊天页/阶段 sources
      均已是批量；现有测试也没有请求级覆盖 listVersions 的 bbox 或查询次数。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/research/service/ArtifactService.java:187-190（按版本 map）、
      :383-399（每版本 withCitations）、:421-427（每版本 findRegions）；
      apps/api/src/main/java/com/riccon/api/chat/persistence/CitationRegionJdbc.java:47-74（一次调用一次查询）；
      .riccon/reviews/tasks/TASK-0171-review.md F-002（同源 accepted）。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-citation + riccon/ops"
    due: M05 gate 前
    accepted_reason: >-
      V13MigrationTest#rollbackV13Objects 与 SchemaVersionChecker 的 Javadoc 已包含删除 history 13，
      回滚语义也有真实 MySQL 演练；缺的只是迁移文件注释的自包含性。生产尚未执行 V13，
      按 V14 后统一 RUNBOOK 的计划补齐即可；不改变当前 forward/rollback 行为。
    title: "V13 头注释的回滚 SQL 未包含删除 history 13，按注释执行旧 Worker 会启动失败"
    summary: >-
      V13 注释只列出 DROP INDEX / DROP COLUMN，未写
      DELETE FROM flyway_schema_history WHERE version='13'；旧 Worker expected=12 依赖 history 删除。
      测试（V13MigrationTest:311）和 SchemaVersionChecker:37 都有该步骤，说明实现/测试顺序正确，
      但迁移文件作为运维执行入口不完整，按注释逐步执行会把 schema 版本留在 13。
    evidence: >-
      apps/api/src/main/resources/db/migration/V13__citation_chunk_link.sql:8-11；
      apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:35-37；
      apps/api/src/test/java/com/riccon/api/migration/V13MigrationTest.java:301-312。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/ops + riccon/architect + riccon/builder-kb-citation"
    due: V14 收口时（M05 gate 前至少补 RUNBOOK 迁移章节）
    accepted_reason: >-
      Lead 已明确 RUNBOOK 等 V14 后统一更新；这些是版本号与迁移对象描述漂移，不影响 V13
      forward/rollback 或当前 API 行为。M05 gate 前至少要把 RUNBOOK 迁移章节补到 V13/V14，
      不能把 V1–V12 当成 V13 的操作说明；ADR/ARCHITECTURE 的 V13 数据模型补充可随 V14 统一处理。
    title: "RUNBOOK/架构文档/测试注释仍停留在 V12（V13 未进入运行手册）"
    summary: >-
      docs/RUNBOOK.md:224、:260、:338 仍写 Flyway V1–V12；application-test.yml:2 写 V1–V11；
      docs/ARCHITECTURE.md:432-436 与 ADR-0023 §7.2（:642-650）只描述 V12；V13 的 chunk_id/索引、
      停 Worker 与删除 history 顺序只存在于迁移头注释、SchemaVersionChecker 和 V13MigrationTest。
    evidence: >-
      docs/RUNBOOK.md:224、:260、:338；
      apps/api/src/test/resources/application-test.yml:2；
      docs/ARCHITECTURE.md:432-436；docs/adr/ADR-0023-m05-knowledge-depth.md:642-650。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-citation + riccon/verifier"
    due: M05 V1/V2 / gate 前
    accepted_reason: >-
      读取实现有 tenant_id + TenantJdbc 双重守卫，ACL/删除状态先于区域附加，静态路径没有发现泄露；
      真实 MySQL 用例已覆盖 AVAILABLE/UPDATED/NO_ACCESS/DELETED 与历史 NULL。缺口是负向/边界覆盖，
      不影响当前验收，但 T4 任务应在 V1/V2 用请求级测试锁死。
    title: "区域读取缺少跨租户 chunk、FILE/MCP chunk_id、J2 task-detail artifact 路径与 listVersions 的定向测试"
    summary: >-
      1）现有跨租户聊天用例的 chunk_id 为 null，未覆盖“同租户引用指向他租户 chunk”或他租户文档的 chunk；
      2）未断言 artifact_citation 的 FILE/MCP 行 chunk_id 为 NULL（静态 ReportComposer:104/121 已保证）；
      3）ResearchTaskService.artifactDetail→withCitationStatus 的 J2 任务详情路径未做请求级 bbox/遮蔽断言；
      4）ArtifactService.listVersions 的 bbox 与查询次数无用例。实现静态正确，但安全/性能边界需要测试。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/chat/CitationStatusApiTest.java:81-82（crossTenant chunkId=null）、
      apps/api/src/test/java/com/riccon/api/research/CitationStatusApiTest.java:115-137（仅阶段 fallback）、
      apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:459-537（artifact 路径）、
      apps/api/src/main/java/com/riccon/api/research/service/ArtifactService.java:187-190（listVersions）。
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-kb-citation
    due: M05 gate 前
    accepted_reason: >-
      file_chunk.id 与 knowledge_chunk.id 都是 UUIDv7，实际碰撞概率可忽略；stage 来源的 chunkId
      也不进入 artifact_citation。静态 tenant/ACL 守卫仍在，故不构成当前泄露；但“仅 KNOWLEDGE 关联
      knowledge_chunk”的约束应在批量收集处显式加 sourceType 判断，避免未来数据/ID 变化时误配。
    title: "阶段 sources 收集区域时未过滤 sourceType=KNOWLEDGE，FILE 来源的 file_chunk ID 也进入 knowledge_chunk 查询"
    summary: >-
      Worker 对 FILE 来源也写 details.sources[].chunkId（file_chunk.id，见 AgentContextJdbc.fileChunks
      与 AgentRunRequestedHandler.retrievalJson:632-634）；ResearchTaskService.stageResponses:421-423
      未按 sourceType 过滤，withStageStatus:448 直接按 chunkId 取区域。当前 knowledge_chunk 无同 ID 行，
      返回 null；若未来 ID 复用/迁移，FILE 引用可能挂上其他知识分块的 bbox。聊天路径已有显式
      Knowledge 判断（MessageRepository:224/240），阶段路径应保持一致。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:236-244（FILE chunkId=file_chunk.id）、
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:632-634（写 chunkId）、
      apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:421-423、:448。
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/lead
    due: TASK-0197 关闭前 / M05 gate 前
    accepted_reason: >-
      两个文件都是迁移/安全基线测试，改动必要且仅测试；当前任务卡已在 a976197 扩展 scope，
      对提交范围 gate 不会报这两个文件越界（Gate 另报的 V14 WIP 越界是工作树问题）。但 scope 变更
      发生在实现提交中而非派单前，削弱“不要静默扩大 write_scope”的审计价值；Lead 应在任务卡/回执
      追认，并在后续任务避免同提交自扩。
    title: "write_scope 在实现提交 a976197 中被 builder 自行扩展（ApiApplicationTests、M04V2VerifierBoundaryTest）"
    summary: >-
      15472ad 的任务卡 write_scope 只到 chat/research/migration 通配与 worker research/persistence；
      a976197 在同一提交加入 apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java 与
      security/m04verify/M04V2VerifierBoundaryTest.java。两者是 V13 schema 计数/版本断言的必要基线，
      无生产行为，但属于“静默扩大 write_scope”，需要 Lead 明确追认。
    evidence: >-
      .riccon/tasks/TASK-0197.json:8-21（a976197 后的 scope）；
      git diff 15472ad..a976197 -- .riccon/tasks/TASK-0197.json（同提交新增两条）；
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:31-44、M04V2VerifierBoundaryTest.java:344-346。
evidence:
  - EV-0910
  - EV-0911
  - EV-0912
  - EV-0913
  - EV-0914
reviewed_evidence:
  - EV-0910
  - EV-0911
  - EV-0912
  - EV-0913
  - EV-0914
created_at: "2026-11-23T16:55:00+08:00"
---

# REV-TASK-0197：TASK-0197（M05-B9 修复：引用 bbox/ocrConfidence 暴露，V13 artifact chunk_id）独立评审

## 结论

**不通过（fail）。** V13 additive 迁移、J1/J2/阶段 sources 的 bbox/ocrConfidence 暴露与 NO_ACCESS/DELETED 遮蔽在 `a976197` 上成立，定向测试 20 类/126 + 6 类/29、Spotless 与 M04V2 schema 13 基线均通过；但 V13 使 `V10MigrationTest` 的 fresh install 断言从 12 变成失败（expected `"12"` but was `"13"`），这是合同 test / 模块 gate 会直接撞到的 P1 回归，且 builder 的 EV-0910 测试选择遗漏了它。另有一个已接受 P2：产物版本列表的 per-version 区域查询仍违反“无 N+1”。

本评审只覆盖任务级行为与定向证据，不是 M05 模块 gate，也不把 M05-AC-03/04 判为无残余通过（见 AC 对账）。

## 验证了什么

### 范围与证据

- 评审提交：`15472ad..a976197`，36 个文件；没有 `contracts/`、`docs/`、`deploy/` 或 `apps/web` 改动，没有 V14/B11 文件。`git diff --check` exit 0。当前任务卡 write_scope 已覆盖该提交范围（gate 工作树里另有未提交 V14/B11 WIP，见 Gate 结果）；其中 `ApiApplicationTests` 与 `M04V2VerifierBoundaryTest` 是 builder 在 `a976197` 同提交内加入 scope 的（F-007，P3 accepted）。
- EV-0910（builder）：commit `a97619783a0586378a0828caff0be303e8ad0c08`、`dirty=false`、`exit_code=0`、agent `riccon/builder-kb-citation`；日志实际 SHA-256 `8c49d2b2a818…e0a41fdb` 与记录一致，输出 446,004 字节。EV 的 `cwd` worktree 已删除，但同一 commit 的独立复核见 EV-0911。
- 独立 EV-0911（verifier）：在 `a976197` 的隔离 worktree 上重跑同一 Maven 测试选择，仅额外加 `${TMPDIR}/riccon-maven.lock` 串行锁（与并行 agent 共享 Maven/JDK，避免 target 污染；不改变测试选择），`dirty=false`、`exit_code=0`；Worktree 原生成 EV-0910，回灌主树时因与 builder EV-0910 冲突重编号为 EV-0911，commit/日志内容不变（note 已注明）。
- EV-0912（verifier，负向）：同一 worktree 上 `-Dtest=V10MigrationTest`，`exit_code=1`，固定复现 P1；未用作通过证据。
- EV-0913（verifier）：`./mvnw -q spotless:check`，`exit_code=0`。
- EV-0914（verifier）：`-Dtest=M04V2VerifierBoundaryTest`，`exit_code=0`，4 tests / 0/0/0，验证 schema 12→13 基线。

### 测试计数

| 模块                                                              | 类数 | 用例 | 失败 | 错误 | 跳过 | 证据                                                                            |
| ----------------------------------------------------------------- | ---: | ---: | ---: | ---: | ---: | ------------------------------------------------------------------------------- |
| apps/api（Citation/Chat/Research + V11/V12/V13 + ApiApplication） |   20 |  126 |    0 |    0 |    0 | EV-0911；逐类计数存于 `.riccon/evidence/TASK-0197/EV-0911-surefire-summary.log` |
| apps/worker（Report/Research/SchemaVersion）                      |    6 |   29 |    0 |    0 |    0 | EV-0911（同上汇总）                                                             |
| apps/api（M04V2 schema 基线）                                     |    1 |    4 |    0 |    0 |    0 | EV-0914                                                                         |
| apps/api（V10 fresh install，负向）                               |    1 |   11 |    1 |    0 |    0 | EV-0912，expected `12` but was `13`                                             |

使用真实 MySQL 8.4.13（Testcontainers；worker 另起 OpenSearch/RustFS），没有 `@Disabled`、`Assumptions` 或 surefire skip。EV-0910 自身因 `mvn -q` 不含 surefire 汇总，计数由 EV-0911 同 commit 的 surefire reports 汇总补齐；EV-0911 的 Maven 测试选择与 EV-0910 相同，仅额外加 Maven 锁（见上）。

### 静态审查

逐文件读取了 `15472ad..a976197` 的 36 个文件，并读取最终源码：V13 migration、`CitationRegionJdbc`/`CitationRegionRepository`、`CitationRecord`/`MessageRepository`/`MessageJdbc`、chat/research `CitationResponse`、`ArtifactCitationSnapshot`、`ArtifactService`/`ArtifactJdbc`、`ResearchTaskService`、worker `ReportComposer`/`ReportArtifactService`/`ArtifactJdbc`/`SchemaVersionChecker`，以及 V11/V12/V13 迁移测试。未修改生产源码、测试、迁移或任务状态。

## 逐项审查

### 1. V13 迁移

- additive：`artifact_citation` 只新增可空 `chunk_id BINARY(16) NULL`；`ALGORITHM=INSTANT` 与后续 `ADD KEY idx_artifact_citation_chunk (tenant_id, chunk_id)` 的 `ALGORITHM=INPLACE, LOCK=NONE` 分开执行，符合 ADR-0023 §7.2 的“列与索引分开”原则；没有外键，`chunk_id` 是逻辑引用。
- 回滚：V13MigrationTest 在一次性 schema 中执行删索引、删列并删除 history 13，回到 12 后旧引用行/正文元数据仍可读；实际 MySQL 8.4.13 通过。
- 历史 NULL：V12 数据写入的引用在 V13 后 `chunk_id` 仍为 NULL，数据不丢失；API 读取返回 bbox/ocrConfidence `null`，不报错。
- 基线：V11/V12 的 fresh install 目标与断言从 12 升到 13，diff 只改版本/注释，没有削弱原断言；ApiApplicationTests 迁移数 12→13、表数 49 不变；worker `EXPECTED_SCHEMA_VERSION=13`。但 V10MigrationTest 也使用 latest 却未更新，见 F-001。
- 头注释的回滚 SQL 缺 history 删除，见 F-003；这是注释/运维文档问题，不是 forward/rollback 语义失败。

### 2. 写入

- worker `ReportComposer.citations` 对 KNOWLEDGE 命中写 `chunkId`，FILE 与 MCP 分支显式写 `null`；`ReportArtifactService.withSourceVersion` 在补 `sourceVersion` 时保留 `chunkId`，未破坏 B9 的 source_version 写入。
- worker `ArtifactJdbc.insertCitation` 已把 `chunk_id` 加入列与参数；`findCitations` 也读回该列。
- 历史引用不回填；同一正文摘要复用旧版本时保留旧引用，不补 chunk_id，符合“历史 NULL 保守”。
- 未发现仅 KNOWLEDGE 的写入约束被破坏；数据库层没有 CHECK/外键强制，由 Worker/API domain 校验，属于既有设计取舍。

### 3. 读取与暴露

- J1：`message_citation` 原本已有 `chunk_id`；`MessageJdbc` 读回后 `MessageRepository.attachCitations` 先算 `sourceStatus`，再批量读 `CitationRegion`，最后 `CitationResponse.from` 映射 bbox/ocrConfidence。一次页面请求的引用、状态、区域查询是固定次数，不是逐条 N+1。
- J2：`ArtifactJdbc.CITATION_COLUMNS` 已含 `chunk_id`；`ArtifactService` 与 `ResearchTaskService` 在版本/任务详情路径按版本批量读引用后，再按页内 chunkIds 一次查 `knowledge_chunk`（`CitationRegionJdbc`）。`ArtifactCitationResponse`/research `CitationResponse` 暴露 bbox/ocrConfidence。
- 阶段 sources：`ResearchTaskService.stageResponses` 跨阶段收集 chunkIds 后一次查询，再映射回每个阶段；测试 `taskSources_computeSourceStatus` 断言 bbox/ocrConfidence。
- 历史 NULL：`chunkId == null` 或分块不存在时返回 null 字段，不失败；测试覆盖历史消息/历史产物引用。
- 未完全满足“无 N+1”的只有 `ArtifactService.listVersions`：按版本循环调用 `withCitations → withStatus → findRegions`，见 F-002。单版本、详情、任务时间线均不是 N+1。

### 4. 遮蔽

- `CitationResponse.from`（J1）、`ArtifactCitationResponse.from`（J2 产物）与 research `CitationResponse.masked()` 在 NO_ACCESS/DELETED 时把 `locatorType/pageNo/sheetName/cellRange/paragraphNo/bbox/ocrConfidence` 全部置 null，`title/snippet` 置空串，保留 `ordinal/sourceType/sourceStatus` 等 required 字段。
- 真实 MySQL 用例覆盖 AVAILABLE/UPDATED 正常展示，DELETED/NO_ACCESS 遮蔽，历史 null，FILE 无 bbox；`CitationMaskingTest` 覆盖 DTO 层。
- 区域查询本身带 `tenant_id = :tenantId`，且经 `TenantJdbc` 的当前租户校验；ACL/删除状态先算后附加区域，跨租户 chunk 不会返回。跨租户/跨部门 chunk 的请求级负向用例仍缺（F-005）。

### 5. J1/J2 与 AC-03/04

- J2 产物详情/单版本与任务 sources 的 API 数据路径现在能提供 bbox/ocrConfidence；M05-AC-03 的“页/区域引用”与 M05-AC-04 的低置信提示具备了 API 数据源。前端 `citation.ts` 已期望 `bbox {x,y,w,h}`、`ocrConfidence` 与 0.6 低置信阈值，字段名一致。
- 但 M05-AC-03/04 是用户可见 UI 行为：本任务没有 web diff，也没有真实浏览器 E2E；B8 的组件测试不能替代 E2E。因此本评审**不把 M05-AC-03/04 判为 pass**，只确认 API 前置完成，模块级仍需 V1/V2 的 UI/E2E 证据。
- J2 的 `ArtifactCitation` 契约没有 chunkId 字段（只有 Citation 有），但 bbox/ocrConfidence 已由 artifact_citation join 后返回，满足契约 1.4.0-m05；未改契约。

### 6. 测试质量与假绿

- 126 + 29 + 4 定向用例全部真实 MySQL，0 skip；`CitationRegionJdbcTest` 验证一次调用一次 SQL，集成用例验证历史 null、四态遮蔽、bbox/ocr 值。
- EV-0910 的命令选择遗漏 V10MigrationTest，导致迁移基线回归未被发现（F-001）；这不是证据伪造，但属于“只跑选定测试”的假绿风险。
- 未发现 retry 掩盖、空断言、错误吞掉或零测试；EV-0912 的失败是确定性断言差异，不是 flake。
- 未覆盖：跨租户 chunk、FILE/MCP chunk_id 非空、J2 task-detail artifact 路径、listVersions bbox/查询次数（F-005）。

## AC 对账（任务级）

| 任务 acceptance 原文                                                                                                  | 结论                           | 说明                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| V13 additive：artifact_citation 增加 chunk_id（BINARY(16) NULL，逻辑引用）+ 回滚 SQL；worker schema 13                | pass（回滚注释有 P3 residual） | V13MigrationTest 4/4、worker schema 13、V11/V12 fresh 13 通过；回滚注释缺 history 删除（F-003）。                                               |
| J1/J2 按 chunk_id 批量 join knowledge_chunk 暴露 bbox/ocrConfidence；NO_ACCESS/DELETED 遮蔽；无 N+1；AC-03/04 J2 可验 | partial（无 N+1 未完全满足）   | J1/J2/阶段 sources 功能与遮蔽通过；`ArtifactService.listVersions` 仍 per-version region 查询（F-002）。AC-03/04 仅 API 前置，UI/E2E 未判 pass。 |
| V13MigrationTest/V12/V11 基线与遮罩测试通过；不改契约；spotless                                                       | pass（但 V10 回归 P1）         | V11 16 / V12 13 / V13 4 与遮罩测试通过；Spotless exit 0；V10MigrationTest 失败（F-001）。                                                       |

## 未验证

- 未跑合同全量 `./mvnw verify`、coverage、E2E/a11y/security/perf；本任务无 web diff，M05-AC-03/04 的真实浏览器 E2E 留给 V1/V2。发现 V10 已足以判定全量迁移基线会红，故没有用完整套件重复获取同一失败。
- 未在生产数据量上量测 V13 的 MDL/锁等待；按 ADR-0023 §7.2 由 M09 迁移演练负责。
- 未独立执行跨租户 chunk 的负向请求；静态 tenant 守卫与 ACL 测试已审，缺口见 F-005。
- 主树当前有 V14/B11 未提交 WIP（`SchemaVersionChecker`、`AgentContextJdbc`、V14 migration 等），不在本评审范围；本评审所有通过证据来自 a976197 的隔离 worktree。
- 未验证 builder 是否实际使用 Serena/CodeGraph：任务卡 `tools_used` 为空。按团队规则要求 builder 在回执补充或说明降级理由；这不影响本评审的技术 verdict。

## Gate 结果

`node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M05` 在 2026-11-23T16:49+08:00 返回 **FAIL（9 errors / 4 warnings）**，其中与本任务相关的是：

- `task TASK-0197 is review, not done`（本评审 verdict=fail，任务不能 done）。
- `task TASK-0197 changed files outside write_scope: apps/worker/.../AgentContextJdbc.java, apps/api/.../V14__knowledge_fulltext_search.sql`：这两个是主树未提交的 V14/B11 WIP，不在 `15472ad..a976197` 提交内；gate 用工作树计算，需 Lead 先隔离/提交 V14 WIP 或修正任务边界，不能把它们算给 TASK-0197。
- TASK-0173 仍 review + blocker，且 gate 报告它越界包含 B9 文件；`state validate` 另有 `active_tasks.riccon/builder-kb-perf references TASK-0173 with status review` 的 INVALID。这些是 Lead 状态问题，不是 TASK-0197 代码失败。
- 无 M05 模块评审，TASK-0176/0177 planned、TASK-0198 in_progress；模块 gate 本就不应通过。

## 需 Lead 处理

1. **F-001**：让 builder 更新 V10MigrationTest 到 13，并把 `*MigrationTest` 或 V10 加入任务回归命令；修复后重新提交独立评审。
2. **EV 回灌**：EV-0911..EV-0914 已复制到主树 `.riccon/evidence/TASK-0197/`（EV-0912 为负向 exit 1，不用于通过），但未改任务 JSON/state；请审计 worktree ID 冲突并决定是否加入 `task.evidence`。
3. **F-007**：追认 a976197 同提交扩展的 write_scope（ApiApplicationTests、M04V2VerifierBoundaryTest）；后续任务避免 builder 自扩 scope。
4. **state/gate**：当前 gate 把 B9 文件算到 TASK-0173 的越界，并把主树 V14/B11 WIP 算到 TASK-0197 越界；`state.active_tasks` 还指向 review 状态的 TASK-0173。TASK-0173 的 baseline/end/scope 与 V14 WIP 需要 Lead 校正/隔离，否则 M05 gate 会被非 TASK-0197 原因阻塞。

## 剩余风险

- 产物版本列表 N+1（F-002）若不在 M05 gate 前批量修复，列表 p95 会被查询放大。
- V13 rollback 注释与 RUNBOOK 未含 history 删除/停 API 顺序（F-003/F-004）；生产回滚必须按 V13MigrationTest + RUNBOOK 完整顺序执行。
- 阶段 sources 对 FILE chunkId 未显式过滤 sourceType（F-006）；当前 UUID 命名空间下无实际泄露，但属于未来数据变化的误配风险。
- REV-TASK-0171-001 F-003 的 `ArtifactJdbc.CITATION_COLUMNS` 注释漂移（写“cell_range 不查询”但 SQL 已查询）仍存在；本任务触碰了该文件但未修，沿用 accepted，建议随 F-002 批量改造时一并清理。
- M05-AC-03/04 的 UI/E2E、T4 生产迁移锁等待与 M09 演练仍未在本次任务验证。
