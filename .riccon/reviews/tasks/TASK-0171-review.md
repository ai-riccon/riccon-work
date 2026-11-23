---
review_id: REV-TASK-0171-001
module_id: M05
task_id: TASK-0171
reviewer:
  agent: riccon/verifier
  session: ses_df8ca0ceeffe0osHjMIbwSsFki
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 595120ec73f344b9111b7646068a90459fefcdb2
head_commit: 595120ec73f344b9111b7646068a90459fefcdb2
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
  任务级 DoD 只判 B9 的 source_version 写入、sourceStatus 四态、J1/J2 遮蔽与定向测试。build 由 Maven
  test 编译支撑；tests 由 builder EV-0860 与 verifier EV-0866（15 类/82 + 3 类/18，0/0/0）支撑。
  coverage/E2E/a11y/security/perf/迁移属于 M05-V1/V2 与模块 gate，不在本任务范围。bbox/ocrConfidence
  暴露是模块级 residual（F-001），不能据此把 M05-AC-03/04 标成无残余 pass。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead（排 B9 后续/B2 契约）+ riccon/architect + riccon/builder-kb-citation"
    due: "M05-V1（TASK-0176）前"
    accepted_reason: >-
      本任务验收只覆盖 source_version/sourceStatus/遮蔽，bbox/ocrConfidence 暴露不在 B9 的两条 acceptance 内；
      该缺口不涉及 ACL、越权或数据泄露，AC-02 需要的 sheetName/cellRange/命中片段已由本任务透传并测试。
      但 M05-AC-03（P0）要求引用详情显示 bbox，M05-AC-04 要求低置信引用带提示，当前 API 无法提供这两个字段，
      因此模块级必须把 AC-03/04 标为 residual/不通过，V1 前必须补齐或由 Product 明确接受残余；不能由 B9 或
      模块 review 宣称 AC-03 无残余 pass。
    title: "Citation/ArtifactCitation 未暴露 bbox/ocrConfidence；J2 artifact_citation 也没有 chunk_id/bbox 数据源"
    summary: >-
      `chat.dto.CitationResponse`、`research.dto.CitationResponse`、`research.dto.ArtifactCitationResponse`
      均没有 bbox/ocrConfidence 字段；J1/J2 引用详情因此无法显示 AC-03 要求的页内坐标，也无法显示 AC-04
      的低置信提示。B7 已把 bbox/ocr_confidence 持久化到 `knowledge_chunk`/`file_chunk`，B8 前端的
      `citation.ts` 与 `citation-detail-drawer.tsx` 也在等待 `citation.bbox`/`citation.ocrConfidence`，
      但 API 从不返回它们。J2 还缺少可回填的数据路径：`artifact_citation` 没有 `chunk_id`，V12 也没有给
      该表加 bbox/ocr_confidence 列，只有 `knowledge_document_id`/`ordinal`/`snippet` 无法可靠关联到分块；
      因此 ArtifactCitation 的 bbox 需要 Architect 决定新增 additive 列/契约或调整 AC，不能只靠 B9 改 DTO。
      OpenAPI 1.4.0-m05 的 `Citation`/`ArtifactCitation` 已声明 bbox/ocrConfidence 为可选，故当前响应通过
      schema 校验不代表能力已实现。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/chat/dto/CitationResponse.java:32-47（无 bbox/ocrConfidence）；
      apps/api/src/main/java/com/riccon/api/research/dto/ArtifactCitationResponse.java:24-39（同上）；
      apps/api/src/main/java/com/riccon/api/research/dto/CitationResponse.java:33-48（同上）；
      contracts/openapi.yaml:4871-4877、5589-5595（可选 bbox/ocrConfidence）、4835-4890/5551-5607（schema）；
      docs/PRODUCT.md:553（M05-AC-03 要求 bbox）、589/681（OCR 引用定位 bbox）；
      apps/web/src/services/knowledge/citation.ts:30-31、145-183（前端等待 bbox/ocrConfidence）；
      apps/web/src/pages/knowledge/components/citation-detail-drawer.tsx:76、199-205（仅在有 bbox 时渲染）；
      apps/api/src/main/resources/db/migration/V4__research_runs_and_artifacts.sql:236-252（artifact_citation 无 chunk_id/bbox）；
      apps/api/src/main/resources/db/migration/V12__knowledge_depth.sql:140-154（bbox 只加到 file_chunk/knowledge_chunk）；
      .riccon/reviews/tasks/TASK-0169-review.md（B7 已持久化 bbox；B9/B8 承接读取/UI）。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-citation"
    due: "M05 gate 前"
    accepted_reason: >-
      该分支只在产物版本列表出现，默认每页 20、最大 100；没有逐条引用 N+1，也没有正确性或安全影响，
      因此不阻塞 B9 任务验收。但它违反 ADR-0023 §5.4 的“按 document_id 批量查询、禁止逐条 N+1”和
      CODING-STANDARDS §13 的“禁止 N+1 查询”，且是 B9 新引入的查询扇出，应在前端/性能验证前合并为
      一次状态查询，否则 M05 gate 的列表 p95 证据会带未知查询放大。
    title: "产物版本列表对每个版本各查一次引用状态，新增 per-version N+1"
    summary: >-
      `ArtifactService.listVersions` 先一次批量取版本引用，但随后对每个版本调用 `withCitations`；
      B9 在 `withCitations` 内调用 `CitationStatusRepository.findStates`，而该仓储每次又会先执行
      `KnowledgeAccessJdbc.resolve`。因此一页 N 个有引用的版本会产生约 2N 次额外查询（N 默认 20、最大 100），
      而不是整页一次。同类的聊天消息、研究任务时间线和单个产物详情都已按页/按文档批量，只有版本列表遗漏。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/research/service/ArtifactService.java:179-183（按版本 map）、
      :375-399（withCitations 每版本调用）、:412（findStates 每版本一次）；
      apps/api/src/main/java/com/riccon/api/chat/repository/CitationStatusRepository.java:57-65（每次 findStates 先 resolve）；
      docs/adr/ADR-0023-m05-knowledge-depth.md §5.4（按 document_id 批量、禁止逐条 N+1）；
      docs/CODING-STANDARDS.md:325（禁止 N+1，批量 IN/join 并在测试中验证查询次数）。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-citation"
    due: "M05 gate 前"
    accepted_reason: >-
      行为本身正确（删除/不可见文档由读取时的 deleted/ACL 状态遮蔽；不可见行必须返回才能保证
      NO_ACCESS > DELETED 优先级），只是注释与实现不一致，不影响安全或功能；在后续触碰这些文件时一并修正即可。
    title: "四处新增 Javadoc 与实现不一致（返回不可见行/删除文档仍返回 checksum/cellRange 已查询）"
    summary: >-
      `CitationStatusRepository.findStates` 与 `CitationStatusJdbc.findStates` 的 @return 写“ACL 开启时
      不可见或跨租户文档不返回”，但实现会返回本租户不可见行（accessible=false），这是保证
      NO_ACCESS 优先于 DELETED 所必需；`MessageJdbc.findKnowledgeChecksums` 与 worker
      `ArtifactJdbc.findKnowledgeChecksums` 的注释写“文档已被删除时 不返回版本”，SQL 实际没有
      `deleted_at IS NULL`，删除文档仍会返回 checksum；API `ArtifactJdbc.CITATION_COLUMNS` 的注释仍写
      “cell_range 不在 OpenAPI ArtifactCitation 中，故不查询”，但下方 SQL 已查询 cell_range。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/chat/repository/CitationStatusRepository.java:55；
      apps/api/src/main/java/com/riccon/api/chat/persistence/CitationStatusJdbc.java:55；
      apps/api/src/main/java/com/riccon/api/chat/persistence/MessageJdbc.java:385-386、:396-397；
      apps/worker/src/main/java/com/riccon/worker/research/persistence/ArtifactJdbc.java:334-335、:345-346；
      apps/api/src/main/java/com/riccon/api/research/persistence/ArtifactJdbc.java:47-51。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      该字段是 M02 既有响应的历史漂移，不是 B9 新增；当前前端与测试依赖它，单纯删除会破坏 J2 MCP 来源关联。
      它不泄露内容、不影响授权，但违反 OpenAPI Citation 的 additionalProperties:false；应由 Architect
      在 B2 契约中把它补成可选字段（或用独立 schema），而不是继续让实现和测试固化漂移。
    title: "ResearchTaskDetail.sources[].mcpInvocationId 不在 OpenAPI Citation schema 中"
    summary: >-
      `ResearchTaskDetail.sources` 的契约元素是 `#/components/schemas/Citation`，该 schema
      `additionalProperties: false` 且没有 mcpInvocationId；research `CitationResponse` 会序列化
      mcpInvocationId，且 `ResearchTaskApiTest:254` 明确断言它。B9 在 DTO 注释中承认这是“M02 既有响应字段”，
      但本任务触及了该 DTO 且没有修正契约，属于本次评审应带出的契约漂移。
    evidence: >-
      contracts/openapi.yaml:4835-4890（Citation 属性与 additionalProperties:false）、:5395-5398（sources 元素为 Citation）；
      apps/api/src/main/java/com/riccon/api/research/dto/CitationResponse.java:15、:40、:67；
      apps/api/src/test/java/com/riccon/api/ResearchTaskApiTest.java:252-255。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/ops + riccon/builder-kb-verify"
    due: "M05 gate 前"
    accepted_reason: >-
      builder EV-0860 与 verifier 重跑 EV-0866 都通过 18/18；失败只发生在第一次独立运行的高负载窗口，
      OpenSearch Testcontainer 180s 启动窗口内节点仍在初始化，随后被测试 JVM 判定超时，属于测试基础设施
      flake，不是产品失败。保留此 finding 是为了禁止用“重跑通过”掩盖 flake；gate 前应提高 startup timeout
      或让全量测试独占资源，避免 CI 偶发 11 个 error 被误判为产品回归。
    title: "Worker 集成测试的 OpenSearch Testcontainer 在并行负载下可能超过 180s 启动窗口"
    summary: >-
      首次独立运行在 `595120e` 上执行同一命令时，`ReportArtifactIntegrationTest` 的静态容器初始化等待
      OpenSearch 180s 超时，18 个 worker 用例中 11 个以 ExceptionInInitializerError/NoClassDefFoundError
      失败，7 个独立用例通过；同 commit 的 builder EV-0860 与本次重跑 EV-0866 均 18/18 通过，容器日志显示
      节点在 180s 边界仍在初始化。该 flake 会影响 M05 gate 的可重复性，需 ops/测试基础设施处理。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:295-305（OpenSearch wait 180s）；
      首次独立运行日志：worktree `riccon-b9-verify-wt`（EV-0856，exit 1，未用作通过证据）；
      EV-0860（builder 定向，exit 0）、EV-0866（verifier 重跑，exit 0，18/18 0/0/0）。
evidence:
  - EV-0860
  - EV-0866
reviewed_evidence:
  - EV-0860
  - EV-0866
created_at: "2026-11-23T15:26:00+08:00"
---

# REV-TASK-0171：TASK-0171（M05-B9 引用 source_version/sourceStatus/遮蔽）独立评审

## 结论

**通过。** B9 的两条任务验收（source_version 写入；AVAILABLE/NO_ACCESS/DELETED/UPDATED 计算与遮蔽）在 `595120e` 上成立：J1 消息/会话详情与 J2 产物/任务/时间线读取都走同一 ACL 谓词与状态计算，NO_ACCESS/DELETED 遮蔽标题、片段与全部定位字段；历史空版本保守按 AVAILABLE；跨租户、跨部门、软删除均有真实 MySQL 用例。

模块级仍有必须转交的 residual：`bbox`/`ocrConfidence` 未暴露（F-001，阻塞 AC-03/04）、产物版本列表 per-version N+1（F-002）、注释与契约漂移（F-003/F-004）、Testcontainers flake（F-005）。本任务评审的 pass 不等于 M05-AC-03/04 或 M05-AC-22 已无残余通过。

## 验证了什么

### 范围与证据

- 提交范围 `47723ca..595120e` 共 31 个文件，全部落在 TASK-0171 的 write_scope（chat/research 的 api、worker 与对应测试）；没有 B8/B11 文件，没有迁移、构建配置或合同文件改动。`git diff --check 47723ca..595120e` exit 0。
- EV-0860：commit `595120e`、`dirty=false`、`exit_code=0`；日志实际 SHA-256 与记录 `70f9c3…1ac` 一致，输出 398,528 字节。命令为 `*Citation*Test,*Chat*Test,*Research*Test` + `*Report*Test,*Research*Test`，与任务声明一致。
- 独立 EV-0866：在隔离 worktree 的 `595120ec73f344b9111b7646068a90459fefcdb2` 上重跑同一命令，`dirty=false`、`exit_code=0`、日志 SHA-256 与记录一致。由于 worktree 生成 ID 与主树并行证据冲突，回灌时重编号为 EV-0866，命令/哈希/commit 不变。
- 第一次独立运行（worktree EV-0856，exit 1）失败原因是 OpenSearch Testcontainer 启动超时，不是代码失败；未将失败 EV 作为通过证据，重跑结果见 F-005。

### 测试计数

| 模块                               | 类数 | 用例 | 失败 | 错误 | 跳过 | 证据                                      |
| ---------------------------------- | ---: | ---: | ---: | ---: | ---: | ----------------------------------------- |
| apps/api（Citation/Chat/Research） |   15 |   82 |    0 |    0 |    0 | EV-0860、EV-0866（surefire reports 汇总） |
| apps/worker（Report/Research）     |    3 |   18 |    0 |    0 |    0 | EV-0860、EV-0866（surefire reports 汇总） |

使用真实 MySQL 8.4.13（Testcontainers；worker 另起 OpenSearch 3.9.0 与 RustFS），未使用 mock 数据库。新增的 5 个 API 测试类/用例与 2 个 DTO/domain 单元测试类都有实质断言，没有 `@Disabled`、`Assumptions` 或 surefire skip。由于 `mvn -q` 不输出 surefire 汇总，逐类计数另存于 `.riccon/evidence/TASK-0171/EV-0866-surefire-summary.log`（从 worktree 的 surefire-reports 汇总，命令/commit 与 EV-0866 一致）。

### 静态审查

- 逐文件读取 `47723ca..595120e` 的 31 个 diff，并读取最终源码：`CitationStatusRepository`/`CitationStatusJdbc`、`CitationSourceState`、聊天/研究 DTO、`MessageRepository`/`MessageJdbc`、`ChatEventRecorder`、`ArtifactService`/`ArtifactJdbc`、`ResearchTaskService`、worker `ReportArtifactService`/`ArtifactJdbc`。
- 使用 CodeGraph 核对 `findStates`/`statusFor`/`withSourceStatus` 的调用面；未发现绕过 `CitationStatusRepository` 的引用读取路径。
- 未修改生产源码、测试、迁移或任务状态；只写入本评审与 EV-0866 证据文件。

## 逐项审查

### 1. source_version 写入

- **聊天**：`ChatEventRecorder.recordCitations` 在同一事务内按 `KNOWLEDGE` 文档 ID 批量 `findKnowledgeChecksums`，逐条写入 `message_citation.source_version`；FILE 引用保持 NULL；`CitationRecord` 构造器拒绝非 KNOWLEDGE 携带版本。
- **产物**：`ReportArtifactService.persist` 在写 `artifact_citation` 前批量取 checksum，`withSourceVersion` 只给 KNOWLEDGE 引用赋值；FILE/MCP 保持 NULL。
- **历史**：读取时 `CitationSourceState.statusFor(null)` 不做 UPDATED 判定，符合 ADR §5.1。
- **时间线**：`agent_run_stage.details.sources` 是检索阶段 JSON，不是 `message_citation`/`artifact_citation` 快照；B9 对它按当前 ACL/deleted 计算状态，但不写 source_version，因此不会出现 UPDATED。这与 ADR §5.1 只要求两张 citation 表写入版本一致，属于设计取舍而非遗漏。
- 写入点齐全：消息引用、产物引用；时间线与版本列表只读。没有发现第二个写入路径，也没有逐条 checksum 查询。

### 2. sourceStatus 计算与遮蔽

- 判定顺序严格为 `NO_ACCESS > DELETED > UPDATED > AVAILABLE`（`CitationSourceState.statusFor`）。
- `CitationStatusJdbc` 一次查询按 `tenant_id + document_id IN` 取 `deleted_at`、`checksum` 和 ACL 谓词结果；ACL 谓词直接复用 `KnowledgeAccessJdbc.appendVisibilityPredicate`，未复制权限规则。
- 软删除行不参与 SQL 过滤，而是由状态计算决定 DELETED/NO_ACCESS；这样保留优先级与“历史引用区分不可访问/已删除”的 ADR 语义。
- 跨租户/不存在文档不出现在结果中，映射为 NO_ACCESS；ACL 可见但 deleted 的文档为 DELETED；ACL 不可见且 deleted 的文档为 NO_ACCESS。
- `title`/`snippet` 在 NO_ACCESS/DELETED 时为空串；`locatorType`/`pageNo`/`sheetName`/`cellRange`/`paragraphNo` 全部为 null；required 字段（ordinal/sourceType/title/snippet/sourceStatus）保留。测试逐项断言 `cellRange` 也被遮蔽。
- `knowledgeDocumentId`/`chunkId`/`fileId`/`score` 保留：它们是用户此前已持有的快照 ID，不是标题/片段/定位；ADR 明确历史引用区分删除与不可访问是有意行为，不构成新的存在性泄露。
- 没有逐条 N+1：聊天按页、任务时间线按阶段集合、单个产物详情各一次批量查询；唯一例外是产物版本列表（F-002）。

### 3. J1/J2 一致性

| 读取面                          | 实现                                                                                                      | 结论                                            |
| ------------------------------- | --------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 会话详情                        | `ConversationService.get` → `MessageRepository.listLatest(..., actor.userId())` → `CitationResponse.from` | 走同一状态计算与遮蔽                            |
| 消息列表                        | `ConversationService.messages` → 同一 `listLatest`                                                        | 同上                                            |
| 产物详情                        | `ArtifactService.get/detail` → `withCitations` → `ArtifactCitationResponse.from`                          | 同上                                            |
| 产物版本列表                    | `ArtifactService.listVersions` → `withCitations` 每版本                                                   | 功能同上；查询扇出见 F-002                      |
| 单版本                          | `ArtifactService.getVersion` → `withCitations`                                                            | 同上                                            |
| 任务 sources                    | `ResearchTaskService.resolveSources` 优先用已遮蔽的 artifact citations，否则用已遮蔽的 stage sources      | 两条路径都遮蔽                                  |
| 时间线 stages[].details.sources | `ResearchTaskService.stageResponses` 先跨阶段收集文档 ID 一次查询，再逐阶段映射                           | 同上；测试通过任务 sources 的 fallback 间接覆盖 |

没有发现未遮蔽的引用读取入口：`MessageResponse`/`ArtifactVersionResponse`/`ResearchTaskDetailResponse` 的引用字段全部来自上述 DTO；`RunStageDetails` 的 `sources` 在 `KNOWN_KEYS` 中被排除，不会被 `extra` 原样透传。

### 4. ACL 开关与管理性读取

- `RICCON_KB_ACL_ENFORCEMENT_ENABLED=false` 时 `KnowledgeAccessJdbc.resolve` 返回无谓词上下文，`CitationStatusJdbc` 的 `CASE WHEN 1 = 1` 让本租户行全部 visible；缺失行由 `CitationStatusRepository` 按 M03 语义补 `accessible=true`，只有显式 `deleted_at` 仍按 DELETED。符合“关闭时退化到 M03”。
- `true` 时按 `TENANT`/`DEPT`/创建人/`USERS` 谓词 fail-closed；`resolve` 找不到启用账号时按无部门处理，DEPT 分支恒假。集成测试在 `enforcement-enabled=true` 下覆盖跨租户、跨部门、USERS 撤销。
- 引用展示刻意不传 `unrestricted=true`：若走管理性读取旁路，引用页每次请求都要为每个绕过 ACL 的文档写 `knowledge.document.admin_read`，会造成读放大；当前选择“宁可少展示也不泄露”，TENANT_ADMIN 需要管理性读取时应走知识库详情/下载路径（该路径已有审计）。判断为可接受设计，不是缺陷。
- 开关每次请求读取，无跨请求缓存；ACL 变更下一请求生效与 ADR §5.5 一致。

### 5. bbox/ocrConfidence

见 F-001。结论：**不阻塞 M05-AC-02**（Excel 的 sheetName/cellRange/snippet 已具备）；**阻塞 M05-AC-03 的 bbox 子句与 M05-AC-04 的“引用带同样低置信提示”子句**。J1 可通过 `message_citation.chunk_id → knowledge_chunk.bbox/ocr_confidence` 补；J2 需要 Architect 决策（`artifact_citation` 增加 chunk_id/bbox 列或改契约/AC），不能只改 DTO。

### 6. 测试质量

- 新增测试对旧实现有区分力：旧代码没有 `sourceStatus`/`withSourceStatus`/`viewerId` 参数，测试无法编译；行为断言直接检查四态、空版本、跨租户/跨部门、FILE 和遮蔽字段。
- API 测试用真实 MySQL/登录链路，不依赖 mock 权限；worker 测试用真实 MySQL/OpenSearch/RustFS，断言 artifact_citation.source_version 等于文档 checksum。
- 未覆盖/未直接断言：`ArtifactService.listVersions` 的逐版本遮蔽（静态实现已检查，建议 V1 补请求级断言）；`stages[].details.sources` 的独立 JSON 断言（任务 sources fallback 间接覆盖）；ACL 从开到关/从关到开的 E2E（AC-08 需 V1）；查询次数（F-002）。

### 7. 范围与证据

- 31 个文件均在 scope；未发现 B8/B11 文件、合同或迁移改动。
- EV-0860 的 commit/dirty/exit/hash 与任务卡一致；EV-0866 为 verifier 在 `595120e` 的独立复验（worktree 例外，已重编号）。
- 任务卡 evidence 只列 EV-0860，符合 builder 视角；本评审额外引用 EV-0866，不改任务状态。

### 8. 契约与文档

- `sourceStatus` 取值、遮蔽语义、`cellRange` 保留与 OpenAPI 1.4.0-m05 一致；未发现 `sourceStatus` 缺少或枚举漂移。
- bbox/ocrConfidence 是“契约已声明但实现未返回”的缺口（F-001）。
- J2 task sources 的 `mcpInvocationId` 是“实现返回但 Citation schema 未声明”的漂移（F-004）。
- 注释与实现不一致见 F-003；不影响行为，但按 CODING-STANDARDS §9 应在 gate 前修正。

## AC 对账

| AC        | 本任务相关判定              | 说明 / 残余                                                                                                                                                                   |
| --------- | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M05-AC-02 | **pass（本任务贡献）**      | J1/J2 引用透传 `sheetName`/`cellRange`/`snippet`，AVAILABLE/UPDATED 保留，NO_ACCESS/DELETED 遮蔽；B9 测试覆盖 cellRange。UI 表格高亮、无源文件提示与 E2E 属 B8/V1。           |
| M05-AC-03 | **不通过（F-001）**         | OCR 引用详情需要 bbox；API 三处引用 DTO 均无 bbox/ocrConfidence，J2 还缺 artifact_citation 数据源。B7 的 bbox 落库不能替代引用 API/UI 闭环；V1 不能把 AC-03 标成无残余 pass。 |
| M05-AC-04 | **partial（F-001）**        | 低置信引用需要 ocrConfidence 提示；当前引用 API 无该字段。OCR 四态/失败/重试本身不在 B9。                                                                                     |
| M05-AC-07 | **pass（本任务相关部分）**  | ACL 收紧后旧引用 NO_ACCESS，标题/片段/cellRange 等定位全部遮蔽；跨租户/部门/软删除用例通过；完整四路径 E2E/下载 404 由 V1 复验。                                              |
| M05-AC-08 | **pass（by construction）** | 每次请求重新 resolve ACL，无缓存；未在 B9 直接做“修改 ACL 前后”E2E，V1 需复验。                                                                                               |
| M05-AC-22 | **partial**                 | sourceStatus 契约符合；bbox 缺失（F-001）与 J2 mcpInvocationId 漂移（F-004）需在模块契约/评审处理；B9 不以 404/deferred 冒充端点。                                            |

## 未验证

- coverage、全量 build/spotless、E2E、a11y、security 扫描、perf、迁移回滚：模块级 V1/V2 与 gate 范围，未在任务级重复。
- B8 前端实际渲染 bbox/低置信提示：后端未返回字段，无法验证（F-001）。
- `ArtifactService.listVersions` 的请求级遮蔽断言：静态实现已审，现有测试未覆盖该端点（F-002 关联）。
- ACL 开关热切换与 AC-08 的“修改后下一请求生效”端到端场景：代码按请求读取，未在 B9 直接跑 E2E。
- 旧实现对同一测试的行为：未在 baseline 上编译运行；新 API/字段在 baseline 不存在，测试无法编译。

## 剩余风险

- UPDATED 只比较文档 checksum 并保留原快照定位，未回填当前 chunk 定位；ADR-0023 §5.4 的“定位指向当前版本”更多依赖前端按文档重新打开，若 Product 要求精确定位到当前版本，V1 需评估。
- 时间线 sources 不持久化 source_version，因此永远不会显示 UPDATED；这是 ADR §5.1 的两张 citation 表范围取舍，作为已记录 residual。
- `CitationSourceStatus` 放在 `com.riccon.api.chat.domain` 并被 research 复用，形成 API 内 chat→research 的领域枚举耦合；未违反当前 ArchUnit 规则，但后续若拆模块可考虑迁入 platform-domain。
- 本任务 `tools_used` 记录 codegraph，未记录 Serena 或降级理由；本评审为只读验证，未发现因此导致的漏改，建议后续高风险任务在回执中补工具降级说明。

## 证据链接

- `.riccon/evidence/TASK-0171/EV-0860.json`（builder 定向，commit 595120e，dirty=false，exit 0）
- `.riccon/evidence/TASK-0171/EV-0866.json`（verifier 独立复验，commit 595120e，dirty=false，exit 0；由 worktree EV-0857 回灌重编号）
