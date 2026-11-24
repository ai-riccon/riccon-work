---
review_id: REV-TASK-0207-001
module_id: M25
task_id: TASK-0207
reviewer:
  agent: riccon/verifier
  session: ses_df49fb205ffekqENopJitADVhI
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - af7da1b0877e14848e8500f4f6d7b05d754f78af
  - 70814871cae433dad33c5dfc81fa2109993d686c
  - 8d06bd412e81f6b5ba4136667c5f7e40213328fa
head_commit: 8d06bd412e81f6b5ba4136667c5f7e40213328fa
baseline_commit: 96885ef5ff26315942a0c5023cfd388606a1e5eb
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
  acceptance 按任务卡 3 条逐条对账，主体通过；保留 P3 accepted F-001（J2 检索阶段 sources 无
  source_version，永不出现 UPDATED）作为 AC1 的残余。该残余是 ADR-0023 §5.1/§5.4 的既有设计取舍
  （阶段 details 不是 citation 快照），J2 在产物生成后的权威来源 `artifact_citation` 已覆盖四态并与 J1
  同判，不能据此把任务判 fail。build 由定向 Maven 测试的编译与 EV-0994 `spotless:check` 支撑；tests 由
  EV-0992（builder）与 EV-0993（verifier 独立复跑，13 个用例，0 失败 0 错误 0 跳过）支撑；security 覆盖
  ACL 收窄、跨租户、软删除、历史空版本、索引 PENDING 不参与判定与响应遮蔽/无 key/connectorId 泄露；
  快照对抗性输入见 F-002；review 为本文件；evidence 为 EV-0992/EV-0993/EV-0994。coverage/e2e/migration/perf/a11y 由 M25 模块 gate
  与 V1b/V2b 承接；本任务只改测试，docs 无行为变化，记 N/A。
reviewed_evidence:
  - EV-0992
  - EV-0993
  - EV-0994
test_impact: >-
  verifier 独立复跑 `RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-b9b ./mvnw -q
  -T1C -pl apps/api,apps/worker -am -Dtest='com.riccon.api.chat.CitationStatusApiTest,
  com.riccon.api.research.CitationStatusApiTest,ReportArtifactIntegrationTest#connectorSourceUpdate_persistsVersionSnapshotPerReport'
  -Dsurefire.failIfNoSpecifiedTests=false test`，exit 0；真实 MySQL 8.4.13、OpenSearch 3.9.0、RustFS
  external 栈。surefire 汇总：chat.CitationStatusApiTest 7、research.CitationStatusApiTest 5、
  worker.ReportArtifactIntegrationTest 1，共 13 用例，failures 0 / errors 0 / skipped 0。`./mvnw -q
  spotless:check` exit 0。未跑 M25 全量 test/coverage/E2E/a11y/perf/security（按任务说明由模块 gate 与
  V1b/V2b 承接），未使用 skip、retry 或额外串行参数。
evidence:
  - EV-0992
  - EV-0993
  - EV-0994
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: "riccon/lead + riccon/architect（如需修改 ADR）"
    due: "M25 gate 前"
    title: "J2 检索阶段 sources（产物生成前的任务详情回退）没有 source_version，永不显示 UPDATED"
    summary: >-
      `ResearchTaskService.stageResponses` 对 `agent_run_stage.details.sources` 调用
      `CitationSourceState.statusFor(null)`，`withStageStatus` 的 Javadoc 也明确“检索阶段没有版本快照，
      不会出现 UPDATED”；而 `resolveSources` 只有在产物当前版本存在非空引用时才改用
      `artifact_citation`，否则回退到阶段 sources。因此同一连接器文档在“产物已生成”的 J2 路径可以显示
      UPDATED，在“尚未生成产物”的任务 sources/时间线路径则不会。J1/J2 同判在 artifact 路径已覆盖；
      该差异不影响 ACL/删除遮蔽，也不是 B9b 引入的回归，但与 AC1 的“J1/J2 同判”字面仍有残余。
    accepted_reason: >-
      ADR-0023 §5.1/§5.4 只要求 `message_citation`/`artifact_citation` 写入 source_version 快照；
      阶段 details 是检索事件不是 citation 快照，没有历史版本可比较，按空版本不判 UPDATED 是既定设计
      （REV-TASK-0171-001 已记录同一取舍）。J2 在产物生成后使用 artifact citations，已支持 UPDATED，
      且与 J1 使用同一 `CitationStatusRepository`/`CitationSourceState.statusFor`；阶段路径仍正确遮蔽
      NO_ACCESS/DELETED。若 Product 要求审批前的时间线也显示“已更新”，需要新增检索时的 checksum 快照，
      属于独立范围与迁移/契约决策，不在本任务测试边界内。风险有界且可见，故按 P3 接受。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:373-393（resolveSources
      回退）、:433-450（statusFor(null)）、:499-537（artifact 路径 statusFor(sourceVersion)）；
      apps/api/src/test/java/com/riccon/api/research/CitationStatusApiTest.java:98-140（阶段 sources 只覆盖
      AVAILABLE/NO_ACCESS/DELETED）、:283-379（J1/J2 同判仅比较消息与 artifact）；
      .riccon/reviews/tasks/TASK-0171-review.md（B9 已记录同一 design tradeoff）。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-citation + riccon/builder-kb-connector"
    due: "M25 gate 前 / V1b"
    title: "新增用例对“完整 externalId/对象 key 不进入引用快照”只验证安全输入，未做对抗性输入"
    summary: >-
      `recordCitations_connectorWritesVersionSnapshot` 与 worker
      `connectorSourceUpdate_persistsVersionSnapshotPerReport` 都传入已经是对象 key 末段的 title，再断言
      `title_snapshot` 等于该安全值且不含 key 前缀；没有用完整 externalId 作为 title 去验证写入侧会
      拒绝、截断或规范化。`ChatEventValidator` 对 citation.title 只做 300 字符上限，不做路径剥离。
      实际不泄露依赖上游 B6 `ConnectorContentTypes.title`（取最后一段）与 worker
      `ChatEventFactory.title(RetrievedChunk)` 使用文档标题；这是有效的产品数据流，但本任务测试不是
      对抗性证明 AC 的最后一段。
    accepted_reason: >-
      B9b 的生产改动为零，引用标题来源是 B6 写入的 `knowledge_document.title`，B6 已由
      REV-TASK-0204-001 评审并覆盖 `ConnectorContentTypes.title` 的末段逻辑；citation 表也没有
      externalId/对象 key 列。现有测试已证明响应在 NO_ACCESS/DELETED 时遮蔽标题/片段/定位，且
      AVAILABLE/UPDATED 响应与快照不包含测试设置的 key 前缀/平台对象 key/connectorId。要真正验证
      对抗性输入需要在 `ChatEventValidator` 或写入侧增加“拒绝路径样式 title”的规则，可能影响既有
      文件名含 `/` 的合法场景，属于契约/行为决策，不在本任务范围；V1b 应用真实 B6 同步→worker→API
      链路补一次端到端断言。风险有界，P3 接受。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/chat/CitationStatusApiTest.java:186-234（安全 title 输入）、
      :380-386（响应无 key 前缀/平台 key/connectorId）；
      apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java:543-636
      （安全 title 输入与快照断言）；
      apps/api/src/main/java/com/riccon/api/chat/service/ChatEventValidator.java:355-358（只限长度）；
      apps/worker/src/main/java/com/riccon/worker/connector/domain/ConnectorContentTypes.java:88-99
      （末段标题）；apps/worker/src/main/java/com/riccon/worker/chat/service/ChatEventFactory.java:208-215。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-kb-citation
    due: "M25 gate 前 / V1b"
    title: "新增测试未覆盖组合优先级、J2 历史版本 ACL 收窄、快照留存与 J1/task/timeline 逐条同判"
    summary: >-
      新增用例分别覆盖 AVAILABLE/UPDATED/DELETED/NO_ACCESS，但没有构造“已软删除且 ACL 不可见”的
      组合来锁定 ADR 声明的 NO_ACCESS > DELETED 优先级；历史空版本 ACL 收窄只在 chat 测试验证，
      J2 artifact 的历史空版本只验证 AVAILABLE，没有收窄后 NO_ACCESS；读取后没有回查
      `message_citation`/`artifact_citation` 以证明库内审计快照未被展示遮蔽逻辑改写；J1/J2 同判用例
      只比较消息引用与 artifact 引用，未把 task `sources`/timeline 纳入同一批文档的逐条对照。
      这些组合在现有生产实现中有统一入口，但测试未全部锁定，属于覆盖缺口而非已复现缺陷。
    accepted_reason: >-
      `CitationSourceState.statusFor` 的优先级是单一 4 行实现（NO_ACCESS → DELETED → UPDATED →
      AVAILABLE），组合语义可从代码和 `CitationStatusJdbc` 的可见性 CASE 直接核对；J2 与 J1 共用同一
      `CitationStatusRepository`，阶段 sources 又已在 F-001 单独记录；库内快照读取路径不会写 citation
      表。该缺口不影响当前 AC 的可验证主体，V1b 可用真实链路和少量组合用例补齐；不阻断 TASK-0207。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/chat/domain/CitationSourceState.java:26-36；
      apps/api/src/test/java/com/riccon/api/chat/CitationStatusApiTest.java:108-125（删除/无权分列）、
      :391-426（仅 chat 历史 ACL 收窄）；
      apps/api/src/test/java/com/riccon/api/research/CitationStatusApiTest.java:54-84（artifact 历史空版本
      仅 AVAILABLE）、:339-379（同判仅消息 vs artifact）。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/lead
    due: "M25 gate 前"
    title: "gate 对 review 任务按 baseline..HEAD 计算 write_scope，把并行 TASK-0208/merge 文件误报为 TASK-0207 越界"
    summary: >-
      `riccon gate check --module M25` 的当前输出把 `AGENTS.md`、TASK-0208 的注入测试等文件列为
      “TASK-0207 changed files outside write_scope”。根因是内核 `scope.mjs` 在 task.status 不是 `done`
      时把 `end` 置空，按 `baseline_commit..HEAD` 计算；TASK-0207 处于 review，HEAD `8d06bd4` 已包含
      并行 TASK-0208 的 merge/提交，而任务自己的 `git diff --name-only 96885ef..af7da1b` 只有 3 个测试
      文件。EV-0992 也录在 merge commit `7081487`（树包含 TASK-0208），虽然 EV 命令用 `-Dtest` 只执行
      TASK-0207 用例，但模块 gate 在 clean 提交上重跑更可追溯。
    accepted_reason: >-
      这是 gate 的基线计算问题，不是 TASK-0207 的代码越界：`scope.mjs` 仅在 status=done 时使用 end_commit，
      本任务处于 review 因此按 baseline..HEAD 计算，必然把并行 TASK-0208 的已合并/工作区文件算进来。任务
      提交范围已用显式 diff 核对，`git diff --check` exit 0，EV-0993 在 HEAD 的同一代码树复跑通过。M25 gate
      当前本来就因其他任务未 done 失败；Lead 应在并行任务收口后以干净提交重跑 module gate，并按需要调整
      baseline/HEAD 口径或重绑 evidence，不需要修改本任务测试。风险限于证据可追溯性，P3 接受。
    evidence: >-
      `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M25` 输出；
      `~/.config/opencode/riccon/scripts/lib/scope.mjs:78-80`（end 仅在 done 时使用）；
      `git diff --name-only 96885ef..af7da1b`（3 个测试文件）；
      `git rev-list --parents -n 1 7081487`（第二父 af7da1b）；
      EV-0992/EV-0993。
notes: >-
  1) 提交范围：`git diff --name-only 96885ef..af7da1b` 只有 3 个测试文件
  （chat/CitationStatusApiTest.java、research/CitationStatusApiTest.java、
  worker/research/ReportArtifactIntegrationTest.java），无生产源码、contracts、docs、deploy、迁移或
  `.riccon` 改动；`git diff --check 96885ef..af7da1b` exit 0。HEAD `8d06bd4` 相对 `7081487` 仅增加
  EV-0992 与状态/任务簿记，无代码变化。
  2) EV-0992 录在 merge commit `7081487`（第二父为 B9b 代码提交 `af7da1b`），该 merge 树同时包含并行
  TASK-0208 的注入测试/AGENTS 变更；EV 命令用 `-Dtest` 只选择本任务测试类，未执行 TASK-0208 用例。
  verifier 独立 EV-0993 在 HEAD `8d06bd4` 复跑同一命令，日志 sha256 与 JSON 一致。
  3) 独立复验使用 external namespace `verifier-b9b`；首次为该 namespace 执行
  `node scripts/test-services.mjs prepare --namespace verifier-b9b --module all`，之后命令未改。
  4) 当前 `riccon gate check` 的 “changed files outside write_scope” 列表来自 `scope.mjs` 对 review 任务
  按 `baseline_commit..HEAD` 计算（HEAD 含并行 TASK-0208 的提交），不是 `96885ef..af7da1b` 的任务范围；
  模块 gate 在并行任务收口后应以干净提交重跑。任务卡 `tools_used` 为空，无法核实 builder 是否使用
  Serena/CodeGraph；本评审使用 CodeGraph 做引用读取面/符号核对，未启动 Serena JDT LS（避免与 Maven
  测试并发触碰 target/），属项目规则的记录习惯问题，不单独作为 finding。
  5) 既有 B9 评审 REV-TASK-0171-001 的 F-001（bbox/ocrConfidence）已由 TASK-0197 修复并由本任务测试
  继续断言 bbox/ocrConfidence；本任务没有重新引入该缺口。
created_at: "2026-11-24T10:43:23+08:00"
---

# REV-TASK-0207-001：TASK-0207（M05b-B9b 连接器源更新/删除的引用状态）独立评审

## 结论

**通过（pass，带残余）。** `96885ef..af7da1b` 只新增 3 个测试文件、1044 行，没有生产源码或契约改动；
现有生产链的 `CitationStatusJdbc` / `CitationSourceState` / `MessageRepository` / `ArtifactService` /
`ResearchTaskService` 对连接器文档（`file_id=null`、`source_type=CONNECTOR`）确实是 source-agnostic 的：
读取时统一按当前 MySQL ACL、`deleted_at`、`checksum` 计算 AVAILABLE/UPDATED/DELETED/NO_ACCESS，未发现
绕过该计算的引用读取面。新增测试覆盖连接器 J1/J2 四态、ACL 收窄、软删除、源端删除-重现、历史空版本、
PENDING 索引未追平、响应遮蔽和无 key/connectorId 泄露，并在真实 MySQL/OpenSearch 栈上独立复跑通过。

保留 4 个 P3 accepted：F-001 是 J2 检索阶段 sources（无 source_version）永不 UPDATED 的既有设计取舍，
影响 AC1 的字面残余；F-002 是“快照不含完整 externalId”只有安全输入测试、缺少对抗性输入；F-003 是
组合优先级/历史 J2/快照留存/任务时间线逐条同判的测试覆盖缺口；F-004 是 gate 当前对并行
TASK-0208/merge 树的 write_scope 误报与 EV-0992 录在 merge 提交的可追溯性问题。四者均有明确的
owner、due 和接受理由，不构成本任务失败；建议 Lead 将 TASK-0207 标 done，并把上述残余带入 V1b/M25 gate。

## 评审范围与提交核对

- `git diff --name-only 96885ef..af7da1b`：3 个文件，全部在任务写范围：
  - `apps/api/src/test/java/com/riccon/api/chat/CitationStatusApiTest.java`（+438）
  - `apps/api/src/test/java/com/riccon/api/research/CitationStatusApiTest.java`（+418）
  - `apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java`（+188）
- 无生产源码、迁移、`contracts/`、`docs/`、`deploy/` 或 `.riccon/` 改动；`git diff --check
96885ef..af7da1b` exit 0。
- 提交链：`af7da1b`（B9b 测试，父 `96885ef`）→ `7081487`（merge，第一父 `1eb7669`、第二父
  `af7da1b`）→ `8d06bd4`（仅 EV-0992/状态/任务簿记）。HEAD 与 `7081487` 的代码树一致。
- 任务卡 `write_scope` 包含生产源码路径，但 builder 实际只改测试；这是更窄的改动，不是越界。AC 依赖
  既有生产逻辑，本评审已独立核对实现，而不是接受 builder 的“未改生产源码”声明本身。

## 证据核对

| EV      | 命令/范围                                                                                    | commit    | dirty | exit | sha256 核对                        |
| ------- | -------------------------------------------------------------------------------------------- | --------- | ----- | ---- | ---------------------------------- |
| EV-0992 | builder：chat/research CitationStatusApiTest + Worker 连接器快照单测，external `b9b-builder` | `7081487` | false | 0    | `4f5a7984…dd6e` 与本机日志复算一致 |
| EV-0993 | verifier：同一命令，external `verifier-b9b`，HEAD `8d06bd4`                                  | `8d06bd4` | false | 0    | `a7a3177a…d3fc` 与本机日志复算一致 |
| EV-0994 | verifier：`./mvnw -q spotless:check`                                                         | `8d06bd4` | false | 0    | `99cdfc4f…a7f2` 与本机日志复算一致 |

- EV-0992 的 commit 是 merge `7081487`，其树包含并行 TASK-0208 的注入测试/AGENTS 变更；EV 命令通过
  `-Dtest='...chat.CitationStatusApiTest,...research.CitationStatusApiTest,ReportArtifactIntegrationTest#connectorSourceUpdate_...'`
  只选择本任务 3 个类/方法，未执行 TASK-0208 用例。证据仍可追溯，但模块 gate 应在最终 clean 提交重跑。
- EV-0993 的 13 个用例全部真实执行：chat 7、research 5、worker 1；0 failures / 0 errors / 0 skipped。
  `-q` 日志不打印汇总，计数取自 verifier 运行后 `apps/*/target/surefire-reports/*.txt`。

## 独立复验

```bash
node scripts/test-services.mjs prepare --namespace verifier-b9b --module all
RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-b9b ./mvnw -q -T1C \
  -pl apps/api,apps/worker -am \
  -Dtest='com.riccon.api.chat.CitationStatusApiTest,com.riccon.api.research.CitationStatusApiTest,ReportArtifactIntegrationTest#connectorSourceUpdate_persistsVersionSnapshotPerReport' \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q spotless:check
```

- 测试命令 exit 0（EV-0993）；`spotless:check` exit 0（EV-0994）。
- 真实栈：MySQL 8.4.13、OpenSearch 3.9.0、RustFS（worker base 默认）；未使用 mock 数据库，未使用
  `@Disabled`/`Assumptions`/retry/额外并行参数。
- 未跑 M25 全量 test/coverage/E2E/a11y/perf/security；这些由模块 gate 与 V1b/V2b 承接。

## AC 对账

| 任务卡 AC                                                                                                       | 结论                  | 依据/残余                                                                                                                                                                                                                                                              |
| --------------------------------------------------------------------------------------------------------------- | --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. 连接器源 UPDATED/DELETED/NO_ACCESS 遮蔽；J1/J2 同判；历史引用保守；不把完整 externalId/对象 key 写入引用快照 | pass（with residual） | chat 测试四态 + artifact 测试四态 + 安全响应断言；J1 vs J2 artifact 逐条状态/标题/片段一致；历史空版本 AVAILABLE；P3 F-001 为 J2 阶段 sources 无 UPDATED 的残余，F-002 为快照对抗性输入测试缺口，均 accepted。                                                         |
| 2. ACL 收窄/软删除/源端重现后引用状态按当前 MySQL 计算，索引字段不作为授权依据                                  | pass                  | `connectorHistoryCitation_followsAclNarrowing` 收窄后下一次请求 NO_ACCESS；`markDeleted` 后 DELETED；`connectorReappearance` 旧文档 DELETED、新文档 AVAILABLE；源更新后 `status=PENDING` 仍按 checksum 判 UPDATED（`CitationStatusJdbc` 查询不读 status/OpenSearch）。 |
| 3. API/Worker 测试覆盖上述状态与历史引用；不修改 contracts/docs/deploy                                          | pass                  | 3 个测试文件，13 个用例；Worker 锁定引用写入当时 checksum、历史版本不回溯改写；无 contracts/docs/deploy/.riccon 生产改动。                                                                                                                                             |

## 重点核对

### 1. 生产逻辑是否 source-agnostic

- `CitationStatusJdbc.findStates` 只查 `knowledge_document.id/deleted_at/checksum` 与
  `KnowledgeAccessJdbc.appendVisibilityPredicate`；SQL 不引用 `file_id`、`connector_id`、`source_type`，
  因此连接器文档 `file_id=null` 与 UPLOAD/SEED 文档走同一计算。
- `CitationSourceState.statusFor` 的优先级是 `NO_ACCESS > DELETED > UPDATED > AVAILABLE`；空
  `sourceVersion` 不判 UPDATED。J1 `MessageRepository.statusOf`、J2 `ArtifactService.applySource`、
  J2 `ResearchTaskService.withStageStatus/withCitationStatus` 都是同一 helper/仓储的不同入口。
- 独立符号检索只发现 3 个引用读取面：`MessageRepository`（J1 消息页）、`ArtifactService`（J2 产物
  详情/版本/列表）、`ResearchTaskService`（J2 任务 sources/timeline + artifact detail）；没有发现第四个
  绕过 `CitationStatusRepository` 的用户可见引用读取。`ChatService.findAssistantByRun` 只用于停止运行，
  不返回引用；`ConversationService` 的消息读取走 `MessageRepository.listLatest(..., viewerId)`。

### 2. 四态矩阵与索引字段

- chat `connectorCitations_computeStatusAndHideExternalId` 在同一响应中构造 connector
  AVAILABLE/UPDATED/DELETED/NO_ACCESS + 历史空版本，UPDATED 保留标题、片段、bbox、sheetName；
  DELETED/NO_ACCESS 的标题/片段/locator/bbox/ocrConfidence 全部遮蔽。
- research `connectorArtifactCitations_computeStatusAndHideExternalId` 覆盖 artifact 四态，并在
  `setDocumentStatus(updated, "PENDING")` 后仍断言 UPDATED，证明索引状态不参与状态判定。
- 状态由 MySQL checksum 比较得出；`sourceStatus` 不在 OpenSearch mapping 中（worker 索引代码注释也
  明确不写 `sourceStatus`）。

### 3. J1/J2 同判

- `j1AndJ2_agreeOnConnectorStatuses` 对同一批 4 个 connector 文档逐条比较 chat citation 与 artifact
  citation 的 `sourceStatus`、`title`、`snippet`，四态为 AVAILABLE/UPDATED/DELETED/NO_ACCESS，逐条一致。
- `connectorTaskSources_maskHiddenSources` 对 task `sources` 单独覆盖 AVAILABLE/NO_ACCESS/DELETED，
  并断言响应不含 key 前缀/平台 key/connectorId。阶段 sources 没有 UPDATED（设计取舍，见 F-001）。

### 4. 历史/ACL/删除-重现

- `connectorHistoryCitation_followsAclNarrowing` 用空 `source_version` 的历史引用先 AVAILABLE，管理员
  收窄为 USERS 后下一次请求即 NO_ACCESS，证明历史快照不绕过当前 ACL。
- `connectorReappearance_keepsOldCitationDeleted`：旧文档软删除、新文档同 externalId 不同
  knowledgeDocumentId；旧引用 DELETED、新引用 AVAILABLE，响应不含完整 externalId。
- 普通文档的跨租户、跨部门、USERS 未授权、软删除仍由既有 `listMessages_computesSourceStatusAndMasks`
  覆盖；连接器用例复用同样的 `CitationStatusJdbc`。

### 5. 泄露检查

- 引用响应 DTO 在 NO_ACCESS/DELETED 时把 title/snippet 置空、locator/bbox/ocrConfidence 置 null；
  `message_citation`/`artifact_citation` 的库内快照没有在读取时被改写。
- 测试断言 chat/artifact 响应不含 `reports/finance/2026/` 前缀、`tenants/{tenant}/connector/` 平台 key 和
  `connectorId`；标题只使用对象 key 末段。F-002 记录了对抗性输入测试的缺口，但生产数据流（B6
  `ConnectorContentTypes.title` + `ChatEventFactory` 使用检索 chunk 标题）没有发现把完整 externalId
  写入 citation 的路径。

### 6. 测试质量与范围

- 3 个文件、1044 行新增；新增方法都有中文 Javadoc；无 `@Disabled`、无 `Assumptions`、无 retry/skip。
- 测试使用真实 MySQL/OpenSearch/RustFS；只通过 SQL 构造 Worker 写入的数据，符合 `ResearchApiTestSupport`
  既有模式，不是 mock 业务逻辑。
- 关键断言是响应 JSON、DB 快照、`source_version`、`sourceStatus`、标题/片段/定位遮蔽，不是快照式空断言。
- 3 个文件均在 `write_scope` 内；`git diff --check 96885ef..af7da1b` exit 0。

## Findings

见 front matter。四个 P3 均 accepted：

- **F-001** J2 检索阶段 sources 无 source_version，永不 UPDATED；产物生成后的 artifact 路径已覆盖，
  ADR/REV-TASK-0171-001 已记录该取舍。
- **F-002** “快照不含完整 externalId/对象 key”只验证安全输入，未做对抗性输入；依赖 B6 末段标题与 worker
  chunk 标题的数据流。
- **F-003** 组合优先级、J2 历史 ACL 收窄、快照留存、J1/task/timeline 逐条同判的测试覆盖缺口。
- **F-004** gate 当前把并行 TASK-0208/merge 树误报为 TASK-0207 越界；EV-0992 录在 merge 提交，
  模块 gate 应以 clean 提交重跑。

无 open finding；P0/P1/P2 为 0。

## 剩余风险与下一步

1. **V1b**：用真实 B6 同步 → worker 报告 → API 读取链路复验连接器 source update/delete/reappear，而不是
   只依赖 SQL 种子；补 F-003 的组合优先级、J2 历史空版本 ACL 收窄、读取后 DB 快照留存断言。
2. **F-001**：若 Product 要求审批前时间线也显示 UPDATED，需要新增检索时 checksum 快照（迁移/契约/ADR
   范围），不能在 B9b 内静默实现。
3. **F-002**：V1b/V2b 可加一个对抗性用例（title 传完整 externalId）验证拒绝/规范化策略；若不改行为，需
   在威胁模型/契约中明确“标题由 B6 末段派生”的边界。
4. **M25 gate**：本任务定向证据只支撑 TASK-0207；模块 gate 仍需在 clean 提交上跑全量合同
   test/coverage/E2E/a11y/perf/security。当前 `riccon gate check` 仍 FAIL，原因包括其他任务未 done 和
   并行 TASK-0208 工作区/merge 树导致的 outside-write_scope 提示，不是本任务代码缺陷。

## 工具使用

本评审使用 **CodeGraph** 核对 `CitationStatusJdbc`/`CitationSourceState`/`MessageRepository`/
`ArtifactService`/`ResearchTaskService`/`ChatEventRecorder` 的调用链与引用读取面，并用 `grep` 做符号级
反查（`CitationSourceStatus.`、`findStates`、`sourceStatus`、`externalId`）。未启动 Serena/JDT LS：
评审期间要运行 Maven 定向测试，按项目 AGENTS 避免 JDT LS 触碰 `target/` 与测试并发。未修改生产源码、
测试、任务状态或契约；只写入本评审和 EV-0993/EV-0994 验证证据。
