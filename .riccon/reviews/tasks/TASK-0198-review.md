---
review_id: REV-TASK-0198-001
module_id: M05
task_id: TASK-0198
reviewer:
  agent: riccon/verifier
  session: ses_df81c5db5ffeAoF2yxg6rwnjVa
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 3d6e78d87325aa2ed0f57da0530a78db46a54564
  - d2558c2242bda9004bb7a4f78387059f8ba68e71
  - 10e8bc8c1e0cec82f8337f560be1337261a64107
head_commit: 10e8bc8c1e0cec82f8337f560be1337261a64107
baseline_commit: 75d10f25925970559977863f3f6d56a0e0ad49e6
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
  TASK-0198 的代码/迁移/ACL 验收已通过；X-001（V14 后两处 V13 断言）已在 10e8bc8 修复并由
  EV-0921（builder）复核，61/0/0/0；verifier 独立 worktree 复跑同命令为 API 68 + worker 22/0/0/0
  （未回灌，见“测试与证据”）。任务验收第 3 条的 B11 real-stack 检索 p95 已由 TASK-0173 EV-0922
  （ACL-on/50 VU/100k/1% 选择性）测得 73.412ms <500ms；B11 任务本身仍因 ACL 写 p95 >500ms 与
  perf JSON 缺失而 exit 1/blocked，属 TASK-0173/Lead 的模块 gate 收口项。EV-0916 的 100k 微基准
  是 ACL 关闭/串行辅助信号，由 F-002（P3 accepted）记录。coverage/e2e/docs 属模块 gate 或
  Architect/Ops 义务，任务级不适用；模块基线仍为 false，直到 B11 ACL 写收口、V1/V2 与模块
  全量 gate 完成。
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: riccon/builder-kb-acl + riccon/lead
    due: fixed in 10e8bc8
    title: "X-001：V14 升 schema 14 后 ApiApplicationTests/M04V2 仍断言 13，合同 test 红"
    summary: >-
      d2558c2 上 `ApiApplicationTests.java:42` 断言 `migrations == 13L`、`M04V2VerifierBoundaryTest.java:348`
      断言 `schemaVersion() == 13`；V14 应用后实际为 14。独立负向复现（worktree，d2558c2）：
      expected 13L but was 14L；expected 13 but was 14（13 tests / 2 failures）。这不是 TASK-0197 的
      代码回归（3d6e78d 上两者对应 schema 13 并通过），但 V14 最终基线不能绿。10e8bc8 将两处改为 14
      并同步 V1–V14 注释；EV-0921（builder，10e8bc8）61/0/0/0，verifier 独立 worktree 复跑
      （10e8bc8）API 68 + worker 22/0/0/0，X-001 解除。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:42；
      apps/api/src/test/java/com/riccon/api/security/m04verify/M04V2VerifierBoundaryTest.java:348；
      负向复现见 TASK-0197 review-002 X-001 的 EV-0919（d2558c2，exit 1，13 tests/2 failures）；
      修复 git show 10e8bc8；EV-0921（builder，10e8bc8，61/0/0/0）；
      verifier 独立 worktree 复跑（10e8bc8，API 68 + worker 22/0/0/0，未回灌）。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-kb-perf
    due: M05b 或下次维护（B11 已用 real-stack 覆盖）
    accepted_reason: >-
      B11 真实栈 EV-0922（TASK-0173，ACL-on/50 VU/100k chunk/1% 选择性查询）已记录检索 p95
      73.412ms <500ms，并判定“检索 p95 在 500ms 内，无需额外决策”，任务验收第 3 条的检索部分已满足；
      微基准的 ACL-off/串行口径仅作为辅助信号保留。B11 任务本身仍因 ACL 写 p95 >500ms（独立 quota
      行锁问题）与 perf JSON 缺失而 exit 1/blocked，属 TASK-0173/Lead 的模块 gate 收口项，不是
      TASK-0198 的代码缺陷。
    title: "100k 微基准为 ACL 关闭/单租户/串行/无断言口径；B11 real-stack 已测检索 p95 73.4ms"
    summary: >-
      `AgentContextSearchFulltextBenchmarkIT` 未声明 `@TestPropertySource`，Worker 默认
      `riccon.kb.acl.enforcement-enabled=false`，测量的 `contextJdbc.search` 不包含 ACL 谓词；
      数据集为单租户 5,000 文档/100,000 分块，30 次串行迭代，5 次预热；测试只 `LOG.info` 不设阈值断言。
      EV-0916 记录中文 p50/p95 32/34ms、英文 25/26ms；verifier 独立 worktree 复跑为中文 36/49ms、
      英文 29/72ms（未回灌，见“测试与证据”）。B11 real-stack EV-0922（TASK-0173，commit f4a1b04，
      exit 1）在 ACL 开启、50 VU、100k chunk、1% 选择性语料下记录检索 p95 73.412ms，已覆盖
      ADR-0023 §9 的检索口径；该 EV exit 1，task blocker 记录 ACL 写 p95 4577ms >500ms（日志另有
      5409ms 指标），real-stack summary 报 perf JSON 缺失；这些均与检索 p95 73.4ms 无关。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextSearchFulltextBenchmarkIT.java:31
      （无 @TestPropertySource）、:43（ITERATIONS=30）、:89-101（串行测量）、:70-80（只记录不断言）；
      EV-0916 日志 PERF 行；verifier worktree 复跑日志（未回灌）；
      TASK-0173 EV-0922 日志 `检索 p95 73.412ms` 与 decision 行；
      docs/adr/ADR-0023-m05-knowledge-depth.md:798-814。
  - id: F-003
    severity: P2
    status: accepted
    owner: riccon/builder-kb-acl
    due: M05 gate 前（建议随 B11/V2 补一条生产查询 EXPLAIN 用例）
    accepted_reason: >-
      生产代码已静态确认 `AgentContextJdbc.search` 只使用 MATCH...AGAINST，无 leading-wildcard LIKE；
      V14MigrationTest 已锁定索引存在与等价 SQL 的 EXPLAIN fulltext；B11 的 p95 复测会对 LIKE 回归
      形成性能兜底。缺口是“测试直接锁生产查询计划”的强度，而非当前实现错误，故按 P2 accepted
      跟踪，不阻塞本任务 done。
    title: "生产检索路径未被自动化测试锁定为 FULLTEXT；旧 LIKE 实现仍可通过现有 ACL/检索用例"
    summary: >-
      `V14MigrationTest.java:175-230` 的 EXPLAIN 使用手写等价 SQL，不经过 `AgentContextJdbc`；
      `AgentContextJdbcAclTest` 新增的标题/英文/单字/跨租户用例在旧 LIKE 实现下同样会通过（LIKE 对
      标题、英文、单字行为相同）。若未来把 `search` 改回 `%term%`，除 B11 性能外没有测试会变红。
      期望：至少一条测试对生产检索 SQL 做 EXPLAIN/查询计划断言，或断言不存在 LIKE 主路径。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:101-106、169-227；
      apps/api/src/test/java/com/riccon/api/migration/V14MigrationTest.java:175-230；
      apps/worker/src/test/java/com/riccon/worker/agent/persistence/AgentContextJdbcAclTest.java:314-334。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/architect + riccon/ops
    due: M05 gate 前
    accepted_reason: >-
      纯文档缺口，不影响运行时代码；V14 迁移头注释已记录 LOCK=SHARED、写阻塞与回滚顺序，代码级
      依据完整。ADR/RUNBOOK 属 Architect/Ops 的模块 DoD，M05 gate 前补齐即可。
    title: "ADR §5.10/§9 与 RUNBOOK 仍描述 LIKE 检索，缺 V14 FULLTEXT/迁移锁/回滚窗口"
    summary: >-
      `docs/adr/ADR-0023-m05-knowledge-depth.md:432` 仍写“检索仍以 knowledge_chunk 的 LIKE 扫描为主”，
      `:804` 的检索 p95 行仍写“待量测，不预设通过阈值/AgentContextJdbc 为 LIKE '%term%'”；DEC-0038=A
      与 V14 已改变该事实。`docs/RUNBOOK.md` 无 FULLTEXT/V14/LOCK=SHARED/回滚窗口说明。期望：ADR
      §5.10/§9 更新为 FULLTEXT 口径，RUNBOOK 增加 V14 迁移锁、维护窗口与回滚顺序。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:430-435、:798-814；
      docs/RUNBOOK.md（grep FULLTEXT/V14/ngram/LOCK=SHARED 无命中）；
      apps/api/src/main/resources/db/migration/V14__knowledge_fulltext_search.sql:12-19。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M09 迁移演练 / release 前
    accepted_reason: >-
      T4 迁移 DoD 要求的“副本 forward + rollback”已在 Testcontainers MySQL 8.4.13 完成；
      ADR-0023 §7.2 对 V12 已采用同一原则，把生产数据量下的锁等待/维护窗口明确交给 M09 备份恢复
      演练。M05 不执行生产迁移，故该残余有明确 release 兜底。
    title: "V14 的 LOCK=SHARED 写阻塞与真实数据量迁移时长未量测，仅有 Testcontainers 2 行数据"
    summary: >-
      `V14MigrationTest` 在 V14 前只种 2 个文档/2 个分块；V14 头注释已声明 LOCK=SHARED 阻塞写、实际
      耗时随 knowledge_chunk 行数增长、生产窗口与回滚演练由 M09 在客户数据量上测量。期望：M09 在
      真实备份/恢复链上测量并写入 RUNBOOK；M05 不得声称已量测生产锁等待。
    evidence: >-
      apps/api/src/main/resources/db/migration/V14__knowledge_fulltext_search.sql:17-19；
      apps/api/src/test/java/com/riccon/api/migration/V14MigrationTest.java:273-283（seed 2 文档/2 分块）。
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-kb-acl
    due: M05b 或下次改动
    accepted_reason: >-
      开销是常数级（每次检索 2 次部门解析 + 2 条 FULLTEXT 查询），不是按文档/分块的 N+1；B11
      ACL-on 复测会把该开销计入。当前 p50 32–36ms（ACL 关闭）远低于预算，正确性不受影响；注释
      与实际往返次数不一致，建议下次改动一并修正或缓存部门解析结果。
    title: "每次检索重复解析部门：collectCandidates 两次调用 appendAclPredicate，实际 4 次往返"
    summary: >-
      `AgentContextJdbc.search` 先调用正文、再调用标题 `collectCandidates`，两次都在
      `appendAclPredicate` 内查询 `app_user`/`department`；ACL 开启时每次检索为 2 条 FULLTEXT +
      2 次部门解析。代码注释写“两条固定批量查询”，未说明部门解析的额外往返。期望：每次检索只
      解析一次部门并复用；至少修正注释。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:105-106、198、
      445-491（appendAclPredicate 内部门查询）。
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/builder-kb-acl
    due: M05b 或下次改动
    accepted_reason: >-
      回滚行为本身已在 Testcontainers 上验证（索引删除、history 14 删除、数据保留、版本回 13）；
      测试同时断言迁移文件包含相同的 DROP INDEX/DELETE 语句，漂移风险有界。为减少双份 SQL，建议
      后续改为直接执行迁移头注释脚本或抽取单一来源。
    title: "V14 回滚演练 SQL 在测试中重复实现，未直接执行迁移头注释的回滚脚本"
    summary: >-
      `V14MigrationTest.rollbackV14Objects` 硬编码 `ALTER TABLE ... DROP INDEX ...` 与
      `DELETE FROM flyway_schema_history WHERE version='14'`；`freshInstallAndRollbackSqlAreStable`
      只断言迁移文件包含这些字符串。若迁移头注释的回滚 SQL 变化而测试未同步，演练会与文档漂移。
      期望：回滚演练直接执行文件中的 SQL，或把回滚 SQL 抽成单一可执行来源。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/migration/V14MigrationTest.java:258-265、336-343；
      apps/api/src/main/resources/db/migration/V14__knowledge_fulltext_search.sql:12-15。
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M05 gate 前
    accepted_reason: >-
      这是审计链说明问题，不是代码问题：V14 实现 diff 已按 3d6e78d..d2558c2 逐文件核对，全部在
      write_scope 内；baseline 前移是为排除并行 B11 的 e2e/perf 提交。建议 Lead 在 handoff/模块
      评审中写明“实现范围以 EV-0916/d2558c2 为准，75d10f2..10e8bc8 只覆盖 X-001 修复”，避免后续
      只读 baseline..end 时漏看 V14 实现。
    title: "任务卡 baseline 改为 75d10f2 后，baseline..end 只覆盖 X-001 修复，不覆盖 V14 实现"
    summary: >-
      当前 TASK-0198 `baseline_commit=75d10f2`、`end_commit=10e8bc8`，两者之间只有 10e8bc8 的两处
      测试断言修复；V14 迁移、AgentContextJdbc、SchemaVersionChecker 与 V14MigrationTest 均在
      2b41cea（早于 baseline）。观察值：`git diff --name-only 75d10f2..10e8bc8` 仅 2 个测试文件；
      期望：任务范围审计同时引用 3d6e78d..d2558c2 的 V14 实现 diff 与 EV-0916。
    evidence: >-
      .riccon/tasks/TASK-0198.json（baseline_commit/end_commit）；
      git diff --name-only 3d6e78d..d2558c2（10 个源码/测试文件，全部在 write_scope）；
      EV-0916（d2558c2，V14 实现最终证据）。
reviewed_evidence:
  - EV-0916
  - EV-0921
created_at: "2026-11-23T18:58:00+08:00"
---

# REV-TASK-0198-001：TASK-0198（M05-B3b：MySQL FULLTEXT 检索优化，V14）独立评审

## 结论

**通过（pass）。** V14 迁移、`AgentContextJdbc` 的 FULLTEXT 检索、ACL 谓词与 worker schema 14
均按任务验收和 DEC-0038=A 实现；迁移 forward/rollback、中文/英文/标题/空查询、DEPT/USERS/创建人/
跨租户用例在 MySQL 8.4.13 上通过。TASK-0197 review-002 标记、本次独立复现的跨任务 P1
（X-001：V14 后两处 V13 断言）已在 `10e8bc8` 修复，并由 EV-0921（builder）与 verifier 独立
worktree 复跑复核全绿；B11 real-stack EV-0922 已测得检索 p95 73.412ms <500ms。

本 pass 只代表 TASK-0198 的代码/迁移/ACL/检索性能测量，不代表 M05 模块 gate 通过：B11 任务仍因
ACL 写 p95 >500ms（独立 quota 行锁问题）与 perf JSON 缺失而 blocked，ADR/RUNBOOK 文档与 M09
迁移锁量测仍为模块级残余（F-004/F-005）。

## 验收对账

| 任务验收原文                                                                                                                        | 结论                         | 证据/说明                                                                                                                                                                                                                                                                         |
| ----------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| V14 additive：`knowledge_chunk/content` 与 `knowledge_document/title` ngram FULLTEXT 索引 + 回滚 SQL；worker schema 14              | pass                         | `V14__knowledge_fulltext_search.sql:21-29`；`V14MigrationTest` 4/4（索引/EXPLAIN/回滚/fresh）；`SchemaVersionChecker` 14 与 `SchemaVersionCheckerTest` 9/9；EV-0916/EV-0921                                                                                                       |
| 检索改用 MATCH...AGAINST（中文/英文、ACL join），去掉 leading-wildcard LIKE；保持 DEPT/USERS/创建人/平台 404 语义与评分排序；无 N+1 | pass（测试锁定强度见 F-003） | `AgentContextJdbc.java:95-235` 仅两条 MATCH 查询 + 同一 ACL 谓词；grep 无 `content LIKE`/`title LIKE`；`AgentContextJdbcAclTest` 12/12；跨租户/部门/名单/创建人/兼容入口/unrestricted 均覆盖；平台 404 属 API 层，未被 V14 改动，沿用既有 `KnowledgeAclApiTest` 等证据（EV-0916） |
| 迁移/回滚/ACL 检索测试通过；B11 在 v2 索引/空闲宿主复测检索 p95 ≤500ms（不达标则保留决策，不得放宽）                                | pass（检索 p95 已达标）      | 迁移 48/48、ACL 12/12；B11 real-stack EV-0922（TASK-0173，ACL-on/50 VU/100k/1% 选择性）检索 p95 73.412ms <500ms，decision“无需额外决策”；B11 任务仍因 ACL 写 p95 >500ms 与 perf JSON 缺失 exit 1/blocked（独立问题，F-002）                                                       |

相关 PRODUCT AC：

- **M05-AC-07/08（ACL 四处一致、创建人始终可见、404 不泄露）**：V14 不改 API 列表/详情/下载/引用
  谓词，只改 worker 检索 SQL；worker 谓词与 `KnowledgeAccessJdbc` 逐分支一致（TENANT/DEPT 启用部门/
  created_by/ACL EXISTS），跨租户与 DEPT/USERS 用例通过。平台 404 由既有 API ACL 套件覆盖，本次
  diff 无回归。
- **M05-AC-20（检索 p95 量测与决策）**：DEC-0038=A 已选择 MySQL FULLTEXT；本任务实现优化并记录
  微基准；B11 real-stack EV-0922（TASK-0173，ACL-on/50 VU/100k/1% 选择性）记录检索 p95 73.412ms
  <500ms，decision“检索 p95 在 500ms 内，无需额外决策”，检索预算已达标。EV-0916/verifier worktree
  的微基准（中文 34/49ms、英文 26/72ms）是 ACL 关闭口径，仅作辅助信号。B11 任务仍因 ACL 写
  p95 >500ms 与 perf JSON 缺失 exit 1/blocked，属 TASK-0173/Lead 收口，不影响本任务检索实现。
- **M05-AC-22（回归与契约）**：`contracts/openapi.yaml` 仍为 1.4.0-m05，无端点变更；本任务无契约
  影响。J1/J2/M03/M10/M04 回归与 M05 契约属 V1/V2 与模块 gate。

## 验证了什么

### 静态审查与范围

- 实现 diff `3d6e78d..d2558c2`：V14 迁移、`AgentContextJdbc`、`SchemaVersionChecker`、V10/V11/V12
  基线版本断言、V14MigrationTest、AgentContextJdbcAclTest、benchmark IT、SchemaVersionCheckerTest，
  共 10 个源码/测试文件，全部在 TASK-0198 write_scope 内；无 V13、B11、contracts、web、deploy 文件。
- 修复 diff `10e8bc8`：只改 `ApiApplicationTests` 与 `M04V2VerifierBoundaryTest` 两处 schema 断言及
  注释，属于 Lead 追加的 scope；`git diff --check 75d10f2..10e8bc8` 干净。
- `3d6e78d..d2558c2` 内另有 TASK-0197 的 EV-0915 日志/task JSON（fadd1e2 记账提交），不是 TASK-0198
  代码；已在 EV 说明中标注。spotless check 对 apps/api、apps/worker 通过。
- 未发现密钥、调试输出、吞错或跳过测试；新增测试 `skipped=0`、`failures=0`、`errors=0`。

### 迁移与回滚（V14）

- 索引：`ft_knowledge_chunk_content`（`knowledge_chunk.content`）与 `ft_knowledge_document_title`
  （`knowledge_document.title`），`WITH PARSER ngram`，`ALGORITHM=INPLACE, LOCK=SHARED`；V14 头注释
  说明 MySQL 8.4 FULLTEXT 创建要求 SHARED（允许读、阻塞写），生产窗口归 M09。
- `V14MigrationTest` 在 MySQL 8.4.13 上验证：V1–V13 历史数据 → V14 索引存在/parser/行保留；
  中文、英文、中英混排、标题、空查询、单字查询；正文与标题 EXPLAIN 分别命中
  `ft_knowledge_chunk_content`/`ft_knowledge_document_title`；回滚删除索引、删除 history 14、
  数据保留、版本回 13；fresh install V1–V14。
- `V10/V11/V12MigrationTest` fresh install 只从 V13 升到 V14 的版本断言/注释，未削弱原有断言；
  V13 中间态测试未改。worker `SchemaVersionChecker.EXPECTED_SCHEMA_VERSION=14`，启动 WARN/失败路径
  测试 9/9。
- `V14` 是 additive（只加索引、不改列、不回填），旧 worker（expected=13）在回滚窗口可继续运行；
  回滚顺序（停新 worker → 删索引 → 删 history 14 → 起旧 worker）与 `SchemaVersionChecker` 注释一致。

### 查询正确性

- `search` 先 `terms(query)` 过滤空/单字查询；正文与标题各一条 `MATCH ... AGAINST(:query IN NATURAL
LANGUAGE MODE)`，两条都走 `appendAclPredicate`；按 chunkId 去重（保留较高 ft_score），评分
  `max(词面子串分, ft_score/(ft_score+1))`，排序 `score desc → documentId → chunkId`，确定性。
- 无 leading-wildcard LIKE；无逐文档/逐 chunk 查询；每次检索固定 2 条 FULLTEXT +（ACL 开启时）
  2 次部门解析，不是 N+1（F-006 记录重复解析的小优化）。
- 空/单字/停用词/特殊字符：空与单字由 `terms()` 返回空；NATURAL LANGUAGE MODE 对特殊字符不做
  boolean 解析，未发现注入或语法错误路径；中文/英文/标题/混排由 V14MigrationTest 与 ACL 测试覆盖。
- 兼容入口（无 actor）在 ACL 开启时只返回 TENANT 文档，测试锁定；`unrestricted=true` 仅由
  `KnowledgeRetriever` 按 `knowledge.acl.manage` 解析后传入，测试覆盖。

### ACL/安全

- worker 谓词与 `KnowledgeAccessJdbc.appendVisibilityPredicate` 分支一致：TENANT；DEPT 且账号部门
  启用；created_by；`knowledge_document_acl` 显式授权。部门停用/移出、USERS 名单外、创建人、
  兼容入口、管理性读取、跨租户均有测试。
- 跨租户隔离由 `TenantJdbc` 强制；新增 `search_crossTenant_isIsolated` 验证租户 B 查不到租户 A
  内容。V14 不改 API 404/403 矩阵；平台管理员 404 与 admin_read 沿用既有 API/worker 证据。
- 检索 SQL 全部参数绑定，`match` 片段由布尔选择固定字面量拼接，无用户输入进入 SQL 结构。

### 性能

- EV-0916（builder，d2558c2）：中文 p50/p95 32/34ms、英文 25/26ms；EXPLAIN `Limit` 顶层 + 正文
  fulltext 候选 1501/60 join（日志记录）。verifier 在隔离 worktree（10e8bc8）独立复跑：中文
  36/49ms、英文 29/72ms。两者均远低于 500ms，但都是 ACL 关闭、单租户、串行、无断言口径（F-002）。
- **B11 real-stack EV-0922**（TASK-0173，builder-kb-perf，commit f4a1b04）：ACL 开启、50 VU、
  100k chunk、1% 选择性查询，检索 p95 **73.412ms <500ms**，decision“检索 p95 在 500ms 内，无需
  额外决策”；检索预算已达标。该 EV exit 1：task blocker 记录 ACL 写 p95 4577ms >500ms（日志另有
  5409ms 指标），real-stack summary 报 `perf-m05-knowledge.json` 缺失；这些属 TASK-0173 的独立
  quota 行锁/证据收口问题，与检索 p95 无关。
- B11 已提交 f4bbd31/75d10f2/f4a1b04 调整 real-stack 采样（V14 迁移对齐、索引积压不中断、选择性
  ngram 查询词/1% 命中语料）；B11 任务本身仍为 review/blocked，M05 gate 仍需其 exit 0 证据与
  ACL 写问题收口。

### 测试与证据

| 证据    | 提交    | 执行者         | 结果                                                                                   |
| ------- | ------- | -------------- | -------------------------------------------------------------------------------------- |
| EV-0916 | d2558c2 | builder-kb-acl | V10–V14 迁移 48 + API 知识库 42（API 命令合计 90）+ worker 35，0/0/0；含 100k 微基准   |
| EV-0921 | 10e8bc8 | builder-kb-acl | ApiApplicationTests 9 + M04V2 4 + V10 11 + V11 16 + V12 13 + V13 4 + V14 4 = 61，0/0/0 |

- EV-0916/EV-0921 的 commit、dirty=false、exit 0、日志哈希与 surefire 计数均已核对。
- verifier 在隔离 worktree（10e8bc8）独立复跑同一组合：API 68（61 + KnowledgeContractM05Test 10 +
  M04V2#ac18 1）+ worker 22（ACL 12 + SchemaVersion 9 + benchmark 1）= 90，0/0/0。该次运行原分配
  EV-0921，与主树 builder EV-0921 撞号；回灌时又与 TASK-0173 B11 real-stack EV-0922 撞号，故
  **不导入主树**，仅在本评审叙述中引用（命令与计数可复现）。B11 EV-0922 属 TASK-0173，不是本任务
  reviewed_evidence。
- EV-0917 为 dirty 补充证据，不绑定；EV-0919 为 TASK-0197 的负向 X-001 证据，不列入本任务
  reviewed_evidence。

## 未验证

- **B11 任务收口/ACL 写 p95**：检索 p95 已由 TASK-0173 EV-0922 测得 73.412ms <500ms；但该 EV
  exit 1（ACL 写 p95 4577/5409ms、perf JSON 缺失），B11 任务仍 blocked。模块 gate 需要 B11 的
  exit 0 证据与 ACL 写独立修复/决策，不在 TASK-0198 范围内。
- **模块全量 build/lint/typecheck/test/coverage/E2E/a11y/security**：属模块 review/V1/V2，不在任务级
  重复；本任务未跑 coverage，未跑浏览器 E2E（无 UI 变更）。
- **生产数据量下的 V14 锁等待/迁移时长**：Testcontainers 只有 2 行历史数据；按 ADR §7.2 先例归
  M09 迁移演练（F-005）。
- **真实多租户 FULLTEXT 索引选择性**：微基准是单租户 100k；生产多租户共享索引时的过滤/排序开销
  需 B11 real-stack 观察。
- **旧 LIKE 实现回归的自动化红灯**：现有用例不能区分 LIKE 与 FULLTEXT（F-003），依赖 B11 性能兜底。

## Findings

| ID    | 级别 | 状态     | 摘要                                                                                       | Owner / Due                              |
| ----- | ---- | -------- | ------------------------------------------------------------------------------------------ | ---------------------------------------- |
| F-001 | P1   | fixed    | X-001：V14 后两处 V13 断言导致合同 test 红；10e8bc8 修复，EV-0921 + verifier worktree 复跑 | builder-kb-acl + lead / fixed in 10e8bc8 |
| F-002 | P3   | accepted | 100k 微基准 ACL 关闭/单租户/串行/无断言；B11 real-stack 已测检索 p95 73.4ms                | builder-kb-perf / M05b 或下次维护        |
| F-003 | P2   | accepted | 生产检索路径未被测试锁定为 FULLTEXT；旧 LIKE 仍可通过现有用例                              | builder-kb-acl / M05 gate 前             |
| F-004 | P3   | accepted | ADR §5.10/§9 与 RUNBOOK 仍写 LIKE，缺 V14 FULLTEXT/锁/回滚                                 | architect + ops / M05 gate 前            |
| F-005 | P3   | accepted | V14 LOCK=SHARED 写阻塞/真实数据量时长未量测，归 M09                                        | ops / M09 release                        |
| F-006 | P3   | accepted | 每次检索重复解析部门（2 次），注释称两条查询但实际 4 次往返                                | builder-kb-acl / M05b 或下次改动         |
| F-007 | P3   | accepted | V14 回滚 SQL 在测试中重复实现，存在文档/测试漂移风险                                       | builder-kb-acl / M05b 或下次改动         |
| F-008 | P3   | accepted | baseline 前移后 baseline..end 只覆盖 X-001，需在 handoff 说明 V14 实现范围                 | lead / M05 gate 前                       |

P0/P1 已清零；P2/P3 均 fixed 或 accepted，accepted 均写 owner/due/reason。verdict=pass。

## 剩余风险

1. **AC-20 检索 p95 已达标，但 B11 任务未收口**：B11 real-stack EV-0922 已测得检索 p95
   73.412ms <500ms（ACL-on/50 VU/100k/1% 选择性）；剩余 blocker 是 ACL 写 p95 4577/5409ms
   （独立 quota 行锁问题）与 perf JSON 缺失，B11 需 exit 0 证据或单独决策。M05 gate 仍以 B11
   收口为准，不得把本任务 pass 当成 AC-20 整条通过。
2. **FULLTEXT 多租户选择性**：100k 单租户微基准未覆盖多租户共享索引的过滤/排序成本；B11 若使用
   多租户语料需记录。
3. **迁移锁**：V14 在 `knowledge_chunk` 建 FULLTEXT 会阻塞写；生产窗口/M09 演练未完成前，不得在
   客户库直接执行。回滚窗口按 `SchemaVersionChecker` 注释执行。
4. **文档**：ADR §5.10/§9 与 RUNBOOK 仍描述 LIKE 检索；M05 gate 的 docs DoD 前必须补齐（F-004）。
5. **测试锁定强度**：旧 LIKE 回归只能由 B11 性能发现（F-003）；建议补一条生产查询 EXPLAIN 用例。
6. **baseline 审计链**：任务卡 baseline 75d10f2 只覆盖 X-001 修复；模块 review 必须同时引用
   `3d6e78d..d2558c2` 的 V14 实现 diff 与 EV-0916（F-008）。

## 下一步

- TASK-0198 可由 Lead 移入 done；done 前确认任务卡 evidence 含 EV-0916/EV-0921，end_commit=10e8bc8，
  并在 handoff 说明 V14 实现范围（F-008）。
- M05 gate 前：B11 以 exit 0 证据收口 ACL 写 p95 >500ms（独立 quota 行锁问题）并修复 perf JSON
  缺失；检索 p95 已由 EV-0922 测得 73.412ms。Architect/Ops 更新 ADR §5.10/§9 与 RUNBOOK；
  V1/V2 跑模块全量与越权/性能/迁移验证。
- 本评审不替代模块 review、Product 预验收或用户验收。
