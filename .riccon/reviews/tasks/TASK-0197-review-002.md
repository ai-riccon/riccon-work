---
review_id: REV-TASK-0197-002
module_id: M05
task_id: TASK-0197
reviewer:
  agent: riccon/verifier
  session: ses_df88702a6ffe0cnQutcdPEpCaH
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 3d6e78d87325aa2ed0f57da0530a78db46a54564
  - 2b41cea1fe527ec86345e7b29bac674c26ff288a
head_commit: 3d6e78d87325aa2ed0f57da0530a78db46a54564
baseline_commit: 629ae3bb77fc11a1b975e148d8562aef5e8d1b47
previous_review: REV-TASK-0197-001
dod:
  acceptance: true
  build: true
  tests: true
  migration: true
  security: true
  perf: true
  review: true
  evidence: true
  module_baseline: false
dod_not_applicable:
  - coverage
  - e2e
  - docs
dod_note: >-
  TASK-0197 的 F-001/F-002/F-003 均已修复：3d6e78d 上 V10=13、V11=16、V12=13、V13=4、
  ArtifactApiTest 5 与引用遮蔽用例全绿（独立 EV-0920：21 类/137 + 6 类/29，0/0/0）；
  HEAD d2558c2 的最终 V14 基线由独立 EV-0918 复核（V10=14 11/11、V13=13 4/4、
  ArtifactApiTest 5/5、Citation 17/17）。F-002 的批量实现静态无 per-version 查询，
  单版本与批量共用 resolveStates/resolveRegions/applySource。F-003 回滚注释已补
  DELETE history 13。TASK-0197 本身可 done。
  但最终模块基线仍有跨任务 P1 X-001：V14 提交 2b41cea 把 schema 升到 14 后，
  ApiApplicationTests 仍断言 13L、M04V2VerifierBoundaryTest 仍断言 schema 13，
  HEAD 上独立 EV-0919 稳定失败（expected 13 / was 14）。这不是 TASK-0197 的代码回归，
  但 M05 模块 gate 的合同 test 在修复前不能绿；详见 cross_task_blockers。
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: riccon/builder-kb-citation
    due: fixed in 3d6e78d；最终 V14 基线由 2b41cea 复核
    title: "V10MigrationTest fresh install 版本断言已修复（3d6e78d=13，HEAD=14）"
    summary: >-
      3d6e78d 将 V10MigrationTest 的 fresh install 从 V1–V12/12 升到 V1–V13/13；
      V14 提交 2b41cea 再把同一处升到 V1–V14/14。独立 EV-0920 在 3d6e78d 上跑 V10MigrationTest
      11/11 通过；独立 EV-0918 在 HEAD d2558c2 上跑 V10MigrationTest 11/11 通过，当前源码
      `V10MigrationTest:589-590` 为 `.isEqualTo("14")`。REV-001 F-001 解除。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:585-590（HEAD 为 V1–V14/14）；
      git show 3d6e78d:.../V10MigrationTest.java:585-590（V1–V13/13）；
      EV-0915（builder，3d6e78d，hash/commit/dirty 已核对）、
      EV-0920（verifier 独立复跑 EV-0915 同命令，3d6e78d，21 类/137 + 6 类/29，0/0/0）、
      EV-0918（verifier 独立 HEAD，V10 11/11、V13 4/4）。
  - id: F-002
    severity: P2
    status: fixed
    owner: riccon/builder-kb-citation
    due: fixed in 3d6e78d
    title: "ArtifactService.listVersions 改为 withCitationsBatch，整页只查一次状态/区域"
    summary: >-
      `listVersions` 现在调用 `withCitationsBatch`：先把整页所有版本的 citations 展平，
      一次 `resolveStates`（全部 document_id）和一次 `resolveRegions`（全部 chunk_id），
      再在内存中按版本 `applySource`；循环内不再访问仓储，因此没有 per-version N+1。
      单版本 `withCitations` 仍走 `withStatus`，与批量共用 `resolveStates`/`resolveRegions`/
      `applySource`，状态、区域与遮蔽语义一致。EV-0918 的 ArtifactApiTest 5/5 通过；
      但列表用例只种了 MCP 引用，未覆盖 knowledge bbox 或查询次数，见 F-008（P3 accepted）。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/research/service/ArtifactService.java:187-190（listVersions 调用批量）、
      :402-422（withCitationsBatch 一次 resolve）、:454-480（resolveStates/resolveRegions 各一次）、
      :490-500（applySource 与单版本共用）；
      apps/api/src/test/java/com/riccon/api/ArtifactApiTest.java:104-135（版本列表/单版本）；
      EV-0918（ArtifactApiTest 5/5）。
  - id: F-003
    severity: P3
    status: fixed
    owner: riccon/builder-kb-citation
    due: fixed in 3d6e78d
    title: "V13 回滚注释已补删除 history 13"
    summary: >-
      V13 头注释现在明确“先删索引、再删列、最后删除 history 13”，并给出
      `DELETE FROM flyway_schema_history WHERE version = '13'`；顺序与
      V13MigrationTest#rollbackV13Objects 一致。独立 EV-0918/EV-0920 的 V13MigrationTest
      4/4 通过，正向/回滚/历史 NULL 语义自洽。
    evidence: >-
      apps/api/src/main/resources/db/migration/V13__citation_chunk_link.sql:8-11；
      apps/api/src/test/java/com/riccon/api/migration/V13MigrationTest.java:301-312；
      EV-0918（V13MigrationTest 4/4）、EV-0920（V13MigrationTest 4/4）。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/ops + riccon/architect"
    due: V14 收口 / M05 gate 前
    accepted_reason: >-
      RUNBOOK/ARCHITECTURE/ADR/application-test 的版本号仍停留在 V12/V11，V14 已提交但未更新文档；
      这些是版本描述漂移，不影响 V13/V14 forward/rollback 或 TASK-0197 行为。Lead 已明确文档在
      V14 收口统一更新，M05 gate 前至少要把 RUNBOOK 迁移章节补到 V14。
    title: "RUNBOOK/架构文档/测试注释仍停留在 V12/V11"
    summary: >-
      docs/RUNBOOK.md:224/260/338 仍写 V1–V12；application-test.yml:2 写 V1–V11；
      docs/ARCHITECTURE.md:432-436 与 ADR-0023 §7.2 只描述 V12。V14 已新增 FULLTEXT 迁移，
      文档仍缺 V13/V14 的停 Worker、history 删除与回滚对象。
    evidence: >-
      docs/RUNBOOK.md:224、:260、:338；apps/api/src/test/resources/application-test.yml:2；
      docs/ARCHITECTURE.md:432-436；docs/adr/ADR-0023-m05-knowledge-depth.md:642-650。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-citation + riccon/verifier"
    due: M05 V1/V2 / gate 前
    accepted_reason: >-
      区域读取有 tenant_id + TenantJdbc 双重守卫，ACL/删除状态先于区域附加，静态路径没有发现泄露；
      真实 MySQL 用例已覆盖 AVAILABLE/UPDATED/NO_ACCESS/DELETED 与历史 NULL。缺口是负向/边界覆盖，
      不影响当前任务验收，但 T4 任务应在 V1/V2 用请求级测试锁死。
    title: "区域读取仍缺跨租户 chunk、FILE/MCP chunk_id 与 J2 task-detail artifact 路径的定向测试"
    summary: >-
      现有跨租户聊天用例的 chunk_id 为 null；未断言 artifact_citation 的 FILE/MCP 行 chunk_id 为 NULL；
      ResearchTaskService.artifactDetail→withCitationStatus 的 J2 任务详情路径未做请求级 bbox/遮蔽断言。
      实现静态正确，但安全/性能边界需要测试。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/chat/CitationStatusApiTest.java:81-82（crossTenant chunkId=null）、
      apps/api/src/test/java/com/riccon/api/research/CitationStatusApiTest.java:115-137（阶段 fallback）、
      apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:459-537。
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-kb-citation
    due: M05 gate 前
    accepted_reason: >-
      file_chunk.id 与 knowledge_chunk.id 都是 UUIDv7，实际碰撞概率可忽略；stage 来源的 chunkId
      也不进入 artifact_citation。静态 tenant/ACL 守卫仍在，故不构成当前泄露；但“仅 KNOWLEDGE 关联
      knowledge_chunk”的约束应在批量收集处显式加 sourceType 判断，避免未来数据/ID 变化时误配。
    title: "阶段 sources 收集区域时未过滤 sourceType=KNOWLEDGE"
    summary: >-
      Worker 对 FILE 来源也写 details.sources[].chunkId（file_chunk.id）；ResearchTaskService
      stageResponses 收集 chunkIds 时未按 sourceType 过滤，withStageStatus 直接按 chunkId 取区域。
      当前 knowledge_chunk 无同 ID 行，返回 null；聊天路径已有显式 Knowledge 判断，阶段路径应保持一致。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:236-244、
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:632-634、
      apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:421-423、:448。
  - id: F-007
    severity: P3
    status: fixed
    owner: riccon/lead
    due: fixed in 629ae3b
    title: "write_scope 自扩已由 Lead 追认（scope_amendment）"
    summary: >-
      629ae3b 在任务卡写入 scope_amendment，明确追认 a976197 同提交加入的 ApiApplicationTests 与
      M04V2VerifierBoundaryTest，并说明 EV-0912 为负向失败证据、不绑定。REV-001 F-007 解除。
    evidence: >-
      .riccon/tasks/TASK-0197.json:40-48（scope_amendment）；
      git show 629ae3b -- .riccon/tasks/TASK-0197.json。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-citation + riccon/verifier"
    due: M05 V1/V2 / gate 前
    accepted_reason: >-
      F-002 的批量实现已由静态审查确认无 per-version 仓储调用；现有 ArtifactApiTest 列表用例只种了
      MCP 引用，未覆盖 knowledge bbox/状态，也没有请求级查询次数断言。它不影响当前任务验收，
      但 F-002 是性能修复，应在 V1/V2 补“多版本 + knowledge 引用 + 查询次数”用例，防止回归。
    title: "withCitationsBatch 缺少 knowledge 引用与查询次数的请求级测试"
    summary: >-
      ArtifactApiTest.versions_listAndReadSingle 的两个版本只包含 MCP citation（无 chunkId/bbox），
      因此列表路径的 resolveStates/resolveRegions 实际为空；没有断言一次请求只查一次状态/区域。
      单版本路径由 research CitationStatusApiTest 覆盖，批量路径的 knowledge 分支未被锁定。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/ArtifactApiTest.java:104-135、
      apps/api/src/test/java/com/riccon/api/support/ResearchApiTestSupport.java:390-405（seedCitation 只写 MCP）、
      apps/api/src/main/java/com/riccon/api/research/service/ArtifactService.java:402-422。
evidence:
  - EV-0910
  - EV-0911
  - EV-0912
  - EV-0913
  - EV-0914
  - EV-0915
  - EV-0918
  - EV-0919
  - EV-0920
reviewed_evidence:
  - EV-0910
  - EV-0911
  - EV-0913
  - EV-0914
  - EV-0915
  - EV-0918
  - EV-0919
  - EV-0920
cross_task_blockers:
  - id: X-001
    severity: P1
    status: open
    owner: "riccon/builder-kb-acl + riccon/lead"
    due: V14 收口 / M05 gate 前
    title: "V14 最终基线仍有两处 V13 断言未升 14（ApiApplicationTests、M04V2）"
    summary: >-
      V14 提交 2b41cea 把 schema 升到 14，并更新了 V10/V11/V12 的 fresh 目标，但没有更新
      ApiApplicationTests（migrations 仍断言 13L）和 M04V2VerifierBoundaryTest（schemaVersion
      仍断言 13）。HEAD d2558c2 独立 EV-0919 稳定复现：ApiApplicationTests expected 13L but was 14L；
      M04V2 expected 13 but was 14（13 tests / 2 failures）。TASK-0198 的 write_scope 不包含这两个
      文件，需要 V14/Lead 做 scope 修订或单独修复。这不是 TASK-0197 的代码回归：3d6e78d 上两者仍
      对应 schema 13 并通过；但最终模块基线以 V14 为准，合同 test 在修复前不能绿。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:42（isEqualTo(13L)）、
      apps/api/src/test/java/com/riccon/api/security/m04verify/M04V2VerifierBoundaryTest.java:348（isEqualTo(13)）；
      git show 2b41cea --stat（V14 未包含这两个文件）；
      .riccon/tasks/TASK-0198.json:8-14（write_scope 不含这两个文件）；
      EV-0919（verifier，HEAD d2558c2，exit 1，13 tests / 2 failures）。
created_at: "2026-11-23T18:25:00+08:00"
---

# REV-TASK-0197-002：TASK-0197（M05-B9 修复）复评

## 结论

**TASK-0197 通过（pass）。** REV-001 的 F-001/F-002/F-003 均已修复：3d6e78d 上 V10=13、V11=16、V12=13、V13=4、ArtifactApiTest 5 与引用遮蔽用例全绿；HEAD d2558c2 的最终 V10=14、V13=13、ArtifactApiTest 5 也由独立 EV-0918 复核。F-002 的 `withCitationsBatch` 静态确认无 per-version 仓储调用，单版本与批量共用同一 `resolveStates`/`resolveRegions`/`applySource`；F-003 回滚注释已补 history 删除。

**但最终 M05 模块基线仍有一个跨任务 P1（X-001）**：V14 提交 2b41cea 把 schema 升到 14 后，`ApiApplicationTests` 仍断言 13L、`M04V2VerifierBoundaryTest` 仍断言 13；HEAD 上独立 EV-0919 稳定失败（expected 13 / was 14）。这不属于 TASK-0197 的代码回归（3d6e78d 上两者仍对应 schema 13 并通过），但合同 test / 模块 gate 在 V14 修复前不能绿。TASK-0197 本身可 done；不要把本 pass 当成 M05 模块 gate 的通过证明。

## 验证了什么

### 证据与范围

- 任务范围：baseline `629ae3b`，end `3d6e78d`；修复 diff 只有 4 个文件（ArtifactService、V13 注释、V10MigrationTest、任务卡），全部在 write_scope 内；`git diff --check 629ae3b..3d6e78d` exit 0，无 contracts/docs/web/deploy 改动。
- EV-0915（builder，3d6e78d）：commit/dirty/exit 与日志哈希核对一致；命令包含 V10/V11/V12/V13/ApiApplication/Citation/Chat/Research + worker Report/Research/SchemaVersion。
- EV-0920（verifier，独立复跑 EV-0915 同命令，3d6e78d，仅加 Maven 锁）：**21 类/137 + 6 类/29，0/0/0**；V10 11/11、V11 16/16、V12 13/13、V13 4/4、ApiApplication 9/9。
- EV-0918（verifier，HEAD d2558c2）：`V10MigrationTest,V13MigrationTest,ArtifactApiTest,*Citation*Test` 9 类/37，0/0/0；V10 11/11（14）、V13 4/4（13）、ArtifactApiTest 5/5、Citation 17/17。
- EV-0919（verifier，负向，HEAD d2558c2）：`ApiApplicationTests,M04V2VerifierBoundaryTest` 13 tests / 2 failures；expected 13 vs 14，见 X-001；不作为通过证据。
- EV-0910/0911/0913/0914 保留；EV-0912 为 REV-001 的负向探针，不作为通过证据。

### 测试计数

| 运行                               |   类数 |     用例 | 失败 | 错误 | 跳过 | 证据                   |
| ---------------------------------- | -----: | -------: | ---: | ---: | ---: | ---------------------- |
| 3d6e78d 全量定向（EV-0915 同命令） | 21 + 6 | 137 + 29 |    0 |    0 |    0 | EV-0920（独立）        |
| HEAD 最终 V14 基线正向             |      9 |       37 |    0 |    0 |    0 | EV-0918（独立）        |
| HEAD 最终 V14 基线负向             |      2 |       13 |    2 |    0 |    0 | EV-0919（独立，X-001） |

逐类计数：`.riccon/evidence/TASK-0197/EV-0918-surefire-summary.log`、`EV-0919-surefire-summary.log`、`EV-0920-surefire-summary.log`。EV-0920 与 EV-0915 的 21/137+29 计数一致；EV-0918/0919 的 Maven 锁只用于与并行 agent 串行，不改变测试选择。

## F-001 / F-002 / F-003 结论

### F-001（P1，fixed）

- 3d6e78d：`V10MigrationTest` fresh install 从 V1–V12/12 改为 V1–V13/13；EV-0920 在 3d6e78d 上 11/11 通过。
- HEAD d2558c2：V14 提交 2b41cea 将同一处最终改为 V1–V14/14；EV-0918 在 HEAD 上 11/11 通过。
- 当前源码 `V10MigrationTest:585-590` 为 `.isEqualTo("14")`。REV-001 F-001 解除。

### F-002（P2，fixed）

- `listVersions` 调用 `withCitationsBatch`（ArtifactService:187-190）；批量方法先把整页 citations 展平，一次 `resolveStates` + 一次 `resolveRegions`（:410-413），循环内只 `applySource`/构造快照（:415-420），无仓储调用，因此无 per-version N+1。
- 单版本 `withCitations` 仍走 `withStatus`（:432-444），与批量共用 `resolveStates`/`resolveRegions`/`applySource`（:454-500），状态、区域、遮蔽语义一致。
- `ArtifactApiTest` 5/5 通过（EV-0918）；但列表用例只种 MCP citation，未覆盖 knowledge bbox/状态与查询次数（F-008，P3 accepted）。

### F-003（P3，fixed）

- V13 注释明确“先删索引、再删列、最后删除 history 13”，并加入 `DELETE FROM flyway_schema_history WHERE version = '13'`（V13:8-11）；与 V13MigrationTest#rollbackV13Objects 的顺序一致。
- EV-0918/EV-0920 的 V13MigrationTest 4/4 通过；正向、回滚、历史 NULL 自洽。

## 新 P1（跨任务）

### X-001（P1，open，V14 基线）

- `ApiApplicationTests.flywayAppliesAllMigrationsOnCleanDatabase` 仍断言 `migrations == 13L`；V14 后实际 14，EV-0919 报 expected 13L but was 14L。
- `M04V2VerifierBoundaryTest.ac18_switchClosureKeepsHistory` 仍断言 `schemaVersion() == 13`；V14 后实际 14，EV-0919 报 expected 13 but was 14。
- V14 提交 2b41cea 更新了 V10/V11/V12 的 fresh 目标，但没有包含这两个文件；TASK-0198 write_scope 也不含它们。需要 V14/Lead 做 scope 修订或单独修复。
- 这不是 TASK-0197 的代码回归：3d6e78d 上两者仍对应 schema 13 并通过；但最终模块基线以 V14 为准，修复前合同 test / M05 gate 不能绿。

## P3 accepted（保留）

- **F-004**：RUNBOOK/ARCHITECTURE/ADR/application-test 仍 V12/V11；V14 已提交但未更新文档。按 Lead 决定 V14 收口统一更新；M05 gate 前至少补 RUNBOOK 迁移章节。
- **F-005**：跨租户 chunk、FILE/MCP chunk_id、J2 task-detail artifact 路径仍缺请求级负向测试；实现静态有 tenant/ACL 守卫。
- **F-006**：阶段 sources 收集区域未过滤 `sourceType=KNOWLEDGE`（FILE 的 file_chunk ID 也进入 knowledge_chunk 查询）；当前 UUID 命名空间下无实际泄露。
- **F-008**：F-002 批量修复缺 knowledge 引用与查询次数测试；ArtifactApiTest 列表用例只种 MCP citation，需在 V1/V2 补“多版本 + knowledge + 查询次数”用例。

## AC/DoD 对账（任务级）

| 任务 acceptance                                                                      | 结论                | 说明                                                                                                                                   |
| ------------------------------------------------------------------------------------ | ------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| V13 additive + 回滚 SQL；worker schema 13                                            | pass                | V13 4/4；worker schema 13 在 3d6e78d 通过；HEAD worker schema 14 由 V14 任务负责。                                                     |
| J1/J2 批量 join bbox/ocrConfidence；NO_ACCESS/DELETED 遮蔽；无 N+1；AC-03/04 J2 可验 | pass（P3 测试缺口） | J1/J2/阶段 sources 功能与遮蔽通过；`withCitationsBatch` 静态无 N+1；批量 knowledge 测试缺口见 F-008；AC-03/04 的 UI/E2E 仍不在本任务。 |
| V13/V12/V11 基线与遮罩测试通过；不改契约；spotless                                   | pass                | EV-0920 21/137+29；EV-0918 V13 4/4；无 contracts 改动；EV-0913 spotless exit 0（REV-001 保留）。                                       |

## 未验证

- 未跑合同全量 `./mvnw verify`、coverage、E2E/a11y/security/perf；未跑 UI/浏览器 E2E（本任务无 web diff，M05-AC-03/04 仍不能据此判 pass）。
- 未在生产数据量上量测 V13/V14 的锁等待；按 ADR/M09 迁移演练负责。
- 未独立执行跨租户 chunk 的负向请求；静态 tenant 守卫与 ACL 测试已审，缺口见 F-005。
- 未验证 V14 的检索性能与 FULLTEXT 语义（TASK-0198 范围）；本评审只验证 V14 对 TASK-0197 基线的影响。

## Gate 结果

`node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M05` 在 2026-11-23T18:27+08:00 返回 **FAIL（10 errors / 4 warnings）**：

- TASK-0173 仍 review + blocker；TASK-0176/0177 planned；TASK-0197/0198 review/in_progress；无 M05 模块评审。这些是预期中的模块未收口。
- gate 把 `V14__knowledge_fulltext_search.sql`、`AgentContextJdbc.java`、`scripts/real-stack/run.mjs` 算到 TASK-0197 越界：TASK-0197 的 end 是 3d6e78d，V14/B11 提交在 3d6e78d 之后；这是 gate 的 baseline..HEAD/任务边界问题，不是本任务 diff 越界（`629ae3b..3d6e78d` 只有 4 个文件）。需 Lead 校正 TASK-0197/TASK-0173 的 end/scope 或 gate 区间。
- `state validate` 当前 OK（0 errors / 43 warnings，均为历史 warning），本 review schema 通过。

## 需 Lead 处理

1. **X-001**：V14/Lead 将 `ApiApplicationTests` 的 migrations 升到 14L、`M04V2VerifierBoundaryTest` 的 schemaVersion 升到 14（或修订 TASK-0198 scope），然后重跑合同 test；否则 M05 gate 仍红。
2. **EV 回灌**：EV-0918/0919/0920 已复制到主树 `.riccon/evidence/TASK-0197/`（EV-0919 为负向 exit 1，不用于通过）；未改任务 JSON/state；请审计 worktree ID 冲突并决定是否加入 `task.evidence`。
3. **F-004**：V14 收口时更新 RUNBOOK/ARCHITECTURE/ADR 的 V13/V14 迁移与回滚说明。
4. **F-008**：V1/V2 补批量 knowledge/查询次数测试。
5. **gate 边界**：修正 gate 把 V14/B11 文件算到 TASK-0197、把 B9 文件算到 TASK-0173 的 baseline..HEAD 归因；TASK-0197 本任务 diff 未越界。

## 剩余风险

- X-001 使最终 M05 模块基线仍红；TASK-0197 可 done，但模块 gate 不能 pass。
- F-002 批量修复的 knowledge 分支缺请求级测试（F-008）；当前静态实现正确，但回归保护不足。
- F-004/F-005/F-006 仍为 accepted P3；V14 的文档与测试基线更新仍需收口。
