---
review_id: REV-TASK-0144
module_id: M19
task_id: TASK-0144
reviewer:
  agent: riccon/verifier
  session: ses_e01bc2dacffeI0whsakaZdlWLq
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
reviewed_commits:
  - c56f71ff99c2ff513cf45cd540ebe44192f3d5c5
  - f05a2f31c8d6306f1af78e378af181da4be2a04d
  - 11a7323da4922ecbddd8ef0b99fe72adfd208eaf
head_commit: 11a7323da4922ecbddd8ef0b99fe72adfd208eaf
dod:
  acceptance: true
  tests: true
  review: true
  evidence: true
dod_note: "任务级评审；build/coverage/E2E 属 M19 模块 gate，不在本任务 DoD。"
findings:
  - id: F-001
    severity: P3
    title: "隔离设计对 provider_credential/tenant_data_key 的描述与实现不一致，且未列出两个全局目录写法"
    summary: "ISOLATION-DESIGN 声称 provider_credential/tenant_data_key 的测试行都归 platform 租户且无需删除，但 ModelFailoverIntegrationTest 会写入租户级凭据，ApprovalParamsCryptoTest 会为唯一租户写 DEK，ModelRegistryIntegrationTest 还会全局 DELETE provider_credential / UPDATE model_provider。当前顺序执行下不影响结果，但文档与实现不一致，未来并行或新增测试时可能误判隔离边界。"
    status: accepted
    owner: riccon/builder-m19
    due: M19 gate 前
    accepted_reason: "实际隔离由每测试唯一 tenant 保证；两个全局写法都位于会先调用 resetModelGatewayState() 的模型测试类内，顺序执行和反转顺序复跑均通过，当前风险有界。接受为文档/可维护性残余，要求 M19 模块评审前在 ISOLATION-DESIGN 或后续测试规范中校正，不阻断本任务。"
    evidence: "ISOLATION-DESIGN.md:33-36; ModelRegistryIntegrationTest.java:133,160; ModelFailoverIntegrationTest.java:230; ApprovalParamsCryptoTest.java:110"
reviewed_evidence:
  - EV-0695
  - EV-0696
  - EV-0697
  - EV-0698
evidence:
  - EV-0697
  - EV-0698
created_at: "2026-11-21T21:34:09+08:00"
---

# REV-TASK-0144：Worker 租户级隔离替代每测试 39 表 TRUNCATE 的独立评审

## 结论

**pass**。TASK-0144 诚实地移除了 `WorkerIntegrationTestBase` 的每测试 39 表 TRUNCATE，改为唯一租户隔离 + 三类显式全局状态 helper；测试集合、断言强度和跳过状态均未弱化。Verifier 在全新 namespace `m19p3v` 上独立复跑两次全量（第二次不 reset）以及一次反转类顺序全量，均为 356 tests / 0 failures / 0 errors / 0 skipped，suite 时间相对 P2 基线 `EV-0695` 下降约 79.7%，远超 ≥30% 的模块要求。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；任务状态不由此文件推进，由 Lead 决定。

## 独立复跑

环境：macOS 本地 compose 热栈（MySQL 8.4.13 / OpenSearch / RustFS），`RICCON_TEST_SERVICES=external`，namespace `m19p3v`（首次执行 `node scripts/test-services.mjs reset --namespace m19p3v`），命令与 CI 同款 `./mvnw -q -T1C -pl apps/worker -am test`，硬超时 1500s。证据：`EV-0697`、`EV-0698`，commit `11a7323`，dirty=false，exit 0。

| 运行    | 条件                              | tests | fail/error/skip | worker suite | 证据    |
| ------- | --------------------------------- | ----: | --------------- | -----------: | ------- |
| 第 1 次 | 新 namespace，先 reset            |   356 | 0/0/0           |     111.617s | EV-0697 |
| 第 2 次 | 同 namespace，不清库              |   356 | 0/0/0           |      90.378s | EV-0697 |
| 第 3 次 | 同 namespace，reversealphabetical |   356 | 0/0/0           |      84.809s | EV-0698 |
| P2 基线 | 4 shard 累计（EV-0695）           |   356 | 0/0/0           |     445.214s | EV-0695 |
| P1 参考 | 单 JVM external 全量（EV-0694）   |  358* | 0/0/0           |     398.953s | EV-0694 |

\* `EV-0694` 未清理 `target/surefire-reports`，358 含历史报告残留（P2 基线生成时已剔除重命名残留报告）。P3 前后源码逐项一致：66 个测试类、356 个 `@Test` 方法，与 `EV-0695` 的 356 完全对齐，因此不能据此认为 P3 删除了 2 个用例。

性能对账：

- 相对 P2 基线 `EV-0695` 的 445.214s，第二次全量 90.378s，降幅 **79.7%**；首次 111.617s，降幅 **74.9%**。
- P2 分片累计口径含 4 个 JVM 的固定启动/上下文成本，严格口径的降幅会略低于 79.7%；即使改用 P1 单 JVM 全量 398.953s 对比，第二次全量降幅仍为 **77.3%**，≥30% 的模块要求稳健成立。
- 反转顺序运行通过，说明结果不依赖类执行顺序；残留数据（MySQL/S3）不改变结果。`ExternalTestServices.purgeSearchIndices()` 会在每次 JVM 启动时清理本 namespace 的 OpenSearch 索引，因此“第二次不 reset”主要验证 MySQL/S3 残留，不覆盖 OpenSearch 长期残留。

## 测试完整性

逐文件对比 `c56f71f^`（7dd6d0d）与 `c56f71f`，并用 `git ls-tree`/符号解析交叉核对：

- **测试类**：两个提交均为 66 个 `*Test(s).java`，类集合逐项相同，无新增/删除/重命名。
- **测试方法**：356 个 `@Test` 方法逐项相同（无删除、无新增）；与 `EV-0695`/`scripts/test-shards-baseline.json` 的 356 一致。
- **跳过**：改动前后 `apps/worker/src/test/**` 均无 `@Disabled`、`assumeTrue`/`Assumptions`、`@EnabledIf`/`@EnabledOn*` 或 `abort()`；三次复跑 skipped 均为 0。
- **断言**：diff 中新增 44 行/删除 44 行 `assertThat`，无任何新增 `isGreaterThanOrEqualTo`、`isNotNull`、`isNotEmpty`、`hasSizeGreaterThan` 等弱断言；精确计数（`isEqualTo(1)`/`isZero()`/`hasSize`）全部保留，只是补充了唯一租户/唯一目标过滤。
- **未执行零测试**：每次全量均实际执行 356 个 worker 用例并产出 surefire 报告。
- **跨提交补充**：P1 取证提交 `77138a8` 到 P3 前 `7dd6d0d` 的 worker/api 测试源码零改动（`git diff 77138a8..7dd6d0d --name-status -- apps/worker/src/test apps/api/src/test` 为空），进一步排除 P2 曾删除用例的可能。

## 隔离设计审查

1. **39 表分类**：`WorkerIntegrationTestBase` 旧 TRUNCATE 列表逐表核对，31 张租户业务表 + 7 张全局目录表 + `audit_event` = 39，覆盖完整；`department`/`workspace_member`/`audit_export_request` 不在旧 39 表内，worker 测试也未写入（`TestDataFactory` 仅把 `department_id` 置空），不存在未分类的 worker 业务表。`TRUNCATE` 仅剩基类注释。
2. **唯一租户**：`uniqueTenant(prefix)` 生成 `prefix-<10 位十六进制>`，`tenant`/`app_user`/`workspace`/文件/知识库/会话/消息/研究任务/运行/审批/产物/调用记录均按本测试租户或本测试 UUID 写入与断言；剩余 `tenant-a`/`tenant-b` 常量仅存在于不落库的单元测试、查询上下文和 `uniqueTenant` 前缀参数中。
3. **全局状态 helper**：
   - `resetModelGatewayState()` 顺序为停用旧 `model` → 清 `model_routing_entry` → 复位 `model_routing.updated_by` → 恢复 `model_gateway_switch` 三个发版种子，无外键冲突，符合 BASELINE/最早 ACTIVE 模型语义；V11 的 CHECK 只允许三个 switch key，复位完整。
   - `resetMcpCatalog()` 按 `mcp_tool_grant` → `mcp_invocation` → `mcp_tool` → `mcp_server` 删除，先子表后主表，测试实际通过。
   - `clearOutbox()` 单表删除；所有调用 `pollOnce()`/`claim()` 的测试类（OutboxPoller、OutboxClaimer、Hitl、ReportArtifact、FileParse、KnowledgeIndex）均在 `@BeforeEach` 调用；`OutboxSchedulerTest` 使用 mock，无需清理。
   - 所有使用模型目录的测试类均调用 `resetModelGatewayState()`；使用固定 MCP server code 的测试类均调用 `resetMcpCatalog()`。
4. **审计 append-only**：worker 测试无任何 `DELETE`/`UPDATE`/`TRUNCATE audit_event`；`audit_event` 的 `trg_audit_event_no_update/no_delete` 触发器仍在。审计断言改为按本测试唯一 `tenant_id` 或唯一 `target_id` 过滤，精确计数仍可证伪（漏写→0，重复写→>1；平台事件用唯一 credentialId 的 `target_id` 精确匹配）。`audit_event` 只增不删符合 V6/ADR-0007。
5. **初始全局种子**：`ensureGlobalBaseline(...)` 在 JVM 启动时以 `NOT EXISTS` 幂等补齐 `platform` 租户、CHAT/REPORT 路由行和三个开关，不覆盖已有值；`createBucket` 幂等。热栈数据库来自上次运行时不会读到空种子。
6. **隐式顺序依赖**：两次同序全量 + 一次反转类顺序全量均通过；未发现依赖前一测试残留业务行的断言。`audit_event` 历史行不会命中新租户/新目标 ID。

## 范围与合规

- `git diff c56f71f^..c56f71f --name-status`：22 个文件，仅 `apps/worker/src/test/**` 与 `.riccon/**`；生产源码、迁移、`pom.xml`、CI workflow 零改动。
- 注释：新增 `uniqueTenant`、`resetModelGatewayState`、`resetMcpCatalog`、`clearOutbox`、`ensureGlobalBaseline`、`prepareData` 均有中文方法级 Javadoc，复杂顺序依赖有段落说明。
- `./mvnw -q spotless:check` 通过；`git diff --check` 通过。
- 证据命令为单行，先提交后取证：实现提交 `c56f71f`，Builder 证据 `EV-0696`；Verifier 证据 `EV-0697`/`EV-0698` 记录于 `11a7323`，dirty=false、exit 0。
- 符号与引用：CodeGraph 复核 helper 影响面（`uniqueTenant` 38 个调用点；`resetMcpCatalog` 4 个；`clearOutbox` 6 个；`resetModelGatewayState` 直接调用点 CodeGraph 标注 7 个，`grep` 补正为 9 个模型/聊天类，结论不变），与人工逐文件核对一致。

## AC 对账

| AC 原文要点                                                                             | 结论 | 依据                                                             |
| --------------------------------------------------------------------------------------- | ---- | ---------------------------------------------------------------- |
| 不再每测试 TRUNCATE 39 张表，改唯一租户/租户级清理，显式处理 M04 全局种子，不改生产代码 | pass | 基类 TRUNCATE 删除；三类 helper；仅测试与 `.riccon` diff         |
| 所有 Worker 测试仍真实执行（≥356，零 skip/@Disabled），断言不放宽，全量通过             | pass | 66 类/356 方法逐项一致；三次 356/0/0/0；44/44 断言替换，无弱断言 |
| 无跨测试泄漏：独立 namespace 连续两次全量通过，重复运行顺序不改变结果                   | pass | EV-0697 两次同 namespace；EV-0698 反转顺序；均 356/0/0/0         |
| 记录 Worker 累计 suite 时间与 P2 基线对比，说明 per-class 清理例外                      | pass | 设计 §4/§5；独立测得 90.378s vs 445.214s（-79.7%）               |
| 先提交再取证；证据命令单行；任务 review 前由独立 Verifier 评审隔离设计                  | pass | c56f71f 后 EV-0696；EV-0697/0698 单行、clean commit；本评审      |

## 未验证

- **默认 Testcontainers 模式**未独立复跑：本任务 AC 明确要求 external 热栈；`commands.test` 默认模式（一次性容器）留待 M19 模块 gate 覆盖。
- **全量后端（apps/api + worker）与 GitHub Actions**未运行：`DEC-0028` 选择 A（P3 后再单独申请 CI 取证），本任务仅 worker external 套件。
- CI 4-shard 合并报告未重跑（属 TASK-0119/P2 范围，已由 `EV-0695` 覆盖）。
- 覆盖率、安全、性能预算不属于本任务 DoD，未在本任务重跑。

## Findings

### F-001（P3，accepted）：隔离设计文档与 provider_credential/tenant_data_key 实现不一致

- 文件/行：`.riccon/evidence/TASK-0144/ISOLATION-DESIGN.md:33-36`；`apps/worker/src/test/java/com/riccon/worker/model/ModelRegistryIntegrationTest.java:133,160`；`apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:230`；`apps/worker/src/test/java/com/riccon/worker/research/ApprovalParamsCryptoTest.java:110`。
- 观察：设计文档称 `provider_credential`/`tenant_data_key` 的测试行都归 `platform` 租户且“无需删除”；实际 `stream_tenantCredentialIsIgnored` 会写入租户级凭据，`ApprovalParamsCryptoTest` 会为唯一租户写 DEK，`resolve_noCredential_fails` 会 `DELETE FROM provider_credential`（全表），`resolve_httpsEndpoint_succeeds` 会更新所有 `model_provider.base_url`。后两者是 P3 之前的既有写法，但在取消全表 TRUNCATE 后成为跨测试全局副作用。
- 期望：设计文档准确描述租户级凭据/DEK 行，并把这些全局写法列入“按测试恢复的全局状态”例外，或改为按本测试 provider 精准更新。
- 影响：当前 surefire 顺序执行且模型测试类每次 `resetModelGatewayState()`，三次全量（含反转顺序）通过，无现实失败；风险限于未来并行执行、新增测试或文档误导。
- 状态：accepted（owner `riccon/builder-m19`，due `M19 gate 前`，原因见 front matter）。

## 剩余风险

1. 默认 Testcontainers 模式与全量后端未在本任务独立复跑，需 M19 模块 gate 覆盖。
2. 全局目录写入依赖“模型/MCP/outbox 测试类自行调用 helper”的约定，基类无强制检查；F-001 已记录并接受，新增测试需沿用该约定。
3. 热栈 namespace 内业务数据与 `audit_event` 只增不删（设计如此）；本地长期复用 namespace 需要定期 `reset`，CI 每 run/分片使用新 namespace。
4. `SchemaVersionCheckerTest` 在 external 模式仍启动一次性 MySQL，单类 17.8–23.4s，约占 worker suite 20%，是 P3b/P4 之后的候选优化点，不属于本任务缺陷。
5. 反转顺序运行中 `OutboxPollerTest` 从约 1s 变为 14.2s（JIT/冷启动），三次 suite 总量在 84.8–111.6s 间波动（主要来自 `SchemaVersionCheckerTest` 一次性 MySQL 容器与 JIT/冷缓存）；均无失败，性能结论以 suite 总量和多轮区间为准，不影响 ≥30% 判定。
6. 任务卡 358 与 P2 基线 356 的差异已定位为 `EV-0694` surefire 历史报告残留；建议 M19 模块评审沿用 356 作为 worker 基线，避免误判“用例减少”。
