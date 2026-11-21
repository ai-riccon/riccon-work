---
review_id: REV-M19-001
module_id: M19
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dffd10ae3ffewtWrcf3gijW826
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
head_commit: 254e2a8efdae752a266e228a84235d29bc026628
reviewed_commits:
  - f31b3ec
  - a260d0b
  - 43b2e5b
  - 13f1485
  - b69f019
  - 254e2a8
verdict: pass
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
dod_evidence:
  acceptance: [EV-0721, EV-0723]
  build: [EV-0717]
  tests: [EV-0716]
  coverage: [EV-0716]
  e2e: [EV-0718]
  docs: [EV-0724]
  evidence: [EV-0716, EV-0721]
findings:
  - id: F-001
    severity: P2
    title: "CI 以 4-shard test 取代原 backend verify 后，后端覆盖率门槛不再由 CI 强制"
    summary: "M19 前 CI 的 backend job 运行 `./mvnw -q -T1C verify`，会执行 jacoco:check；M19 后只有 backend-shards（`mvnw test -Djacoco.skip=true`）、backend-merge 与 backend-smoke（迁移+spotless），没有任何 job 运行 canonical `verify`/coverage。主分支合并后，line 80/branch 70 的合同覆盖率门槛只在 verifier 本机 gate 被强制，CI 不会因覆盖率下降到门槛以下而失败。"
    status: accepted
    owner: riccon/ops
    due: M20 gate 前
    accepted_reason: "DEC-0026/DEC-0028 明确 commands.test/commands.coverage 不变、canonical gate 仍由 verifier 用合同命令取证；EV-0716 已独立证明后端 api 92.41%/75.08%、worker 87.89%/70.71%、platform 均过门槛，前端 line 87.01%/branch 80.01%。4-shard 的设计目标就是只做 ≤5min 快速反馈（CI 墙钟 198–213s），拆分后移除原 12 分钟 backend job 属于 P2 实现范围。风险有界但真实：建议 M20 增加独立 coverage/verify job 或在合同中明确“CI 不做覆盖率门禁”，避免主分支长期无自动覆盖率兜底。"
    evidence: ".github/workflows/ci.yml:28-184; git show f31b3ec:.github/workflows/ci.yml:26-117; docs/RUNBOOK.md:116,137,324; EV-0716"
  - id: F-002
    severity: P3
    title: "P4b 是新增 platform 词汇守卫，不是把 app 测试迁出；P4 不降低 app 用例数与墙钟"
    summary: "TASK-0148 在 platform-domain 新增 RoleCodesTest/PermissionCodesTest/AuditVocabularyTest 共 14 个用例，断言共享词汇的码值格式、唯一性、常量名一致性与平台/租户边界；apps/api 的 RoleDefinitionsTest 角色→权限映射和 API/Worker AuditDetailsPolicyTest 行为断言原样保留。P4 的“下沉”实际是增量平台覆盖，app 用例数（642）与 app 墙钟没有因 P4 减少。"
    status: accepted
    owner: riccon/builder-m19
    due: M20 gate 前
    accepted_reason: "任务 AC 点名了 RoleCodes/PermissionCodes/Audit Vocabulary 三类，且明确禁止新增 platform→app 反向依赖；映射与 details 策略依赖 apps/api 类，迁出会形成 Maven reactor 环或需要生产改动。3 个新测试为共享词汇提供了具体可证伪守卫，app 断言零删除、全量 1115/0/0/0 未损失覆盖。接受 P4 的提速主要由 P2/P3 承担、P4b 只承担分层与词汇守卫这一边界。"
    evidence: "platform/platform-domain/src/test/java/com/riccon/platform/domain/identity/RoleCodesTest.java; PermissionCodesTest.java; platform/platform-domain/src/test/java/com/riccon/platform/domain/audit/AuditVocabularyTest.java; apps/api/src/test/java/com/riccon/api/identity/domain/RoleDefinitionsTest.java（M19 零改动）；EV-0710"
  - id: F-003
    severity: P3
    title: "GitHub Actions 各 job 出现 Node.js 20 与 setup-java v4 弃用告警"
    summary: "CI run 44955035025 的全部 job 都被 GitHub 标注 Node.js 20 deprecation（actions/checkout@v4、setup-java@v4、setup-node@v4、upload/download-artifact@v4 被强制跑在 Node 24），backend 各 job 另有 setup-java v4 弃用提示。功能上本次全绿，但升级窗口关闭后可能变成失败。"
    status: accepted
    owner: riccon/ops
    due: M20 gate 前
    accepted_reason: "告警为 warn-only，runner 已自动强制 Node 24，CI run 44955035025 结论 success；不影响 M19 的测试集合、墙钟或证据有效性。属 Ops 的工具链维护项，建议 M20 前升级 setup-java@v5 与各 action 主版本并在 CI 复跑。"
    evidence: "gh api repos/ai-riccon/riccon-work/actions/runs/44955035025/jobs 的 check-run annotations；EV-0713/EV-0721"
  - id: F-004
    severity: P3
    title: "分片 baseline 的计时值来自一次未单独录 EV 的全量运行，时间来源不可复核"
    summary: "scripts/test-shards-baseline.json 的 generated_from 指向 2026-11-22 02:05–02:14 的 external 全量，但该次运行没有独立 EV/日志；baseline time_total_seconds=657.788s，EV-0709 的另一次全量为 639.187s。类集合 196/1115 与 0/0/0 可由本次独立全量逐类复核，但计时值本身不能从现有证据重建。"
    status: accepted
    owner: riccon/builder-m19
    due: M20 gate 前
    accepted_reason: "时间值只用于分片负载估算；--check、合并校验、用例集合与 gate 结论都不依赖它。本次独立全量 196 类/1115 用例与 baseline 完全一致，suite 累计 531.132s（低于 657.788s），分片墙钟 CI 198–213s 也证明估算可用。接受为证据可复现性残余；后续重建 baseline 必须用已录 EV 的全量报告。"
    evidence: "scripts/test-shards-baseline.json:1-12; EV-0709; EV-0710; EV-0716; EV-0723"
  - id: F-005
    severity: P3
    title: "测试隔离仍依赖“唯一租户 + 全局状态 helper”约定，并行/换 fork 配置需重新验证"
    summary: "Worker 基类已移除 39 表 TRUNCATE，但 ModelRegistryIntegrationTest 仍有全表 `DELETE FROM provider_credential` / `UPDATE model_provider`，ModelCatalogApiTest 的 DEK 前缀清理与 CostAdminApiTest 按模型 ID 清理属于精确补偿；这些在默认顺序、fresh namespace、同 namespace 第二次全量（EV-0707）都通过，但 surefire 若改为多 fork/并行执行，全局目录与顺序假设需要重新验证。"
    status: accepted
    owner: riccon/builder-m19
    due: M20 gate 前
    accepted_reason: "ISOLATION-DESIGN/ORDER-FIX 已把例外写入文档，且所有模型/MCP/outbox 测试类在 @BeforeEach 调用 reset helper；默认 fresh namespace + 默认顺序是 CI 路径，反转与同 namespace 第二次全量均由独立 Verifier 复跑通过（EV-0707）。当前 surefire 为 forkCount=1/reuseForks=true，风险限定在未来并行化或新增全局状态测试，接受为 P3 维护性残余。"
    evidence: ".riccon/evidence/TASK-0144/ISOLATION-DESIGN.md:33-46; apps/worker/src/test/java/com/riccon/worker/model/ModelRegistryIntegrationTest.java:133,160; apps/api/src/test/java/com/riccon/api/model/catalog/ModelCatalogApiTest.java:104-114,319-349; apps/api/src/test/java/com/riccon/api/admin/cost/CostAdminApiTest.java:27-46; EV-0707"
reviewed_task_reviews:
  - REV-TASK-0144
  - REV-TASK-0145
  - REV-TASK-0146
  - REV-TASK-0148
reviewed_evidence:
  - EV-0690
  - EV-0694
  - EV-0695
  - EV-0696
  - EV-0697
  - EV-0698
  - EV-0700
  - EV-0703
  - EV-0704
  - EV-0705
  - EV-0706
  - EV-0707
  - EV-0708
  - EV-0709
  - EV-0710
  - EV-0711
  - EV-0712
  - EV-0713
  - EV-0714
  - EV-0715
  - EV-0716
  - EV-0717
  - EV-0718
  - EV-0719
  - EV-0720
  - EV-0721
  - EV-0722
  - EV-0723
  - EV-0724
evidence:
  - EV-0716
  - EV-0717
  - EV-0718
  - EV-0719
  - EV-0721
  - EV-0722
  - EV-0723
  - EV-0724
notes: "独立模块验证；M19 的 9 个任务全部 done，代码与 CI 的最终提交为 b69f019（PR #1 head），最终 .riccon 状态提交 254e2a8；两者之间无源码/CI 差异。"
created_at: "2026-11-22T06:56:00+08:00"
---

# REV-M19-001：M19 测试基础设施与全量测试提速的模块独立评审

## 结论

**pass**。在干净提交 `254e2a8`（源码/CI 与 PR #1 head `b69f019` 完全一致）上独立复跑：

- 合同 `commands.test` + `commands.coverage`：**196 类 / 1115 用例 / 0 failure / 0 error / 0 skip**，后端覆盖率 api line 92.41%、branch 75.08%；worker 87.89%/70.71%；platform 均过 80/70；前端 57 文件 / 493 用例通过，line 87.01%、branch 80.01%。
- 合同 `commands.build`、`commands.e2e`（40 passed / 4.3m）、`commands.a11y`（15 passed / 2.2m）、`commands.security`（trivy exit 0，仅 ADR-0011 的两条带 exp 例外）全部通过。
- CI run 44955035025（PR #1 @ b69f019）：4 个 shard job 墙钟 **213/204/209/198s**（≤300s），合并报告 **196 类/1115 用例/0/0/0、每类恰好一次、problems=0**；smoke 80s、frontend 330s。
- 独立本地 4 片模拟 + 合并校验通过；CI artifact 重新下载后由本会话再次合并通过。
- 独立全量 suite 累计 531.132s；对 P2 baseline（a260d0b，1231.995s）下降 **56.9%**，远超 P3 的 ≥30% 要求；baseline 自身的时间值（657.788s）下降 46.6%。Worker 从 P2 445.214s 降到 ~90–106s，API 从 828.171s 降到 417–424s。
- 无 P0/P1。3 个 P3 accepted（P4b 增量而非迁出、baseline 时间来源、并行隔离约定）+ 1 个 P3 accepted（Actions 弃用告警）+ 1 个 P2 accepted（CI 不再强制覆盖率，canonical gate 仍由 verifier 执行）。P2/P3 均已写 owner/due/accepted_reason，无 open finding。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；模块状态与 Product/用户验收由 Lead 处理。

## 验证环境与独立命令

环境：macOS + Docker Desktop，compose 测试栈（MySQL 8.4.13 / OpenSearch 3.9 / RustFS）在线；JDK 21.0.13、Node 26.13.0、pnpm 10.34.9；默认 Testcontainers（未设置 RICCON_TEST_SERVICES），全量 Maven 期间未启动 Java LSP。

| 检查                    | 命令                                                                                                                     | 结果                                                                              | EV      |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------- | ------- |
| 合同 test + coverage    | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                      | exit 0；196/1115/0/0/0；前端 493/493；541.5s                                      | EV-0716 |
| 合同 build              | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                    | exit 0；前端生产构建成功                                                          | EV-0717 |
| 合同 test_e2e           | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                   | 40 passed（4.3m）                                                                 | EV-0718 |
| 合同 a11y               | `pnpm -w a11y`                                                                                                           | 15 passed（2.2m）                                                                 | EV-0719 |
| 合同 security           | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` | exit 0；0 未抑制漏洞；2 条 ADR-0011 例外（exp 2027-03-31）                        | EV-0722 |
| 分片自检 + 本地模拟合并 | `node scripts/test-shards.mjs --check` + `--simulate` + `test-merge-shards.mjs`                                          | 196 类/1115 用例；4 片互斥完整；合并 pass                                         | EV-0720 |
| CI artifact 复核        | `gh run download 44955035025` + 本地重新合并 + job 墙钟断言                                                              | 4 shard success；walls 213/204/209/198s；merge 196/1115/0/0/0、runs=1、problems=0 | EV-0721 |
| 计时报告                | `node scripts/test-timings.mjs --out-dir $TMPDIR/m19-verify-timings`                                                     | 1115/0/0/0；531.132s；top-20 输出                                                 | EV-0723 |
| 文档/合同一致性         | docs 段落 grep + YAML 校验 + `spotless:check` + `test-shards --check`                                                    | exit 0                                                                            | EV-0724 |

说明：EV-0714/EV-0715 是本次验证中先跑出的两条辅助证据；由于命令中的 `&&` 被外层 shell 拆分，它们只记录了 Maven 半段，不作为 DoD 依据，权威证据为 EV-0716/EV-0717（完整合同命令、单条 EV、`dirty=false`）。未删除失败历史：EV-0711 记录 CI shard 2 的 ChatStream 竞态失败（1 failure），EV-0712 记录修复后的 10 次定向 + Worker 全量，EV-0713 记录最终 CI 全绿。

## AC 对账（M19 验收原文）

| #   | AC 原文要点                                                                                                                                            | 结论                        | 依据                                                                                                                                                                                                                                                                                                                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 全量后端用例集合不减少（基线 771，零 skip/零 @Disabled），gate 仍可复现；禁止 -DskipTests 或删除用例                                                   | pass                        | 196 类/1115 用例（P2 193/1101，P4 +3 类/+14 用例；远高于 771）；`@Disabled`/assume/`@Enabled*` 源码计数 0；surefire skipped=0；`@Test` 增 14/删 0；EV-0716 可复现 canonical gate。                                                                                                                                               |
| 2   | P0：scripts/test-timings.mjs 生成 top-N 与总计报告并进 CI artifact；L2/L3 时间预算写入 docs                                                            | pass                        | EV-0690（空目录/坏 XML 负向、YAML/格式）；EV-0723 top-20 + 总计 531.132s；CI artifact 每个 shard 的 `.ci/test-timings/shard-N/test-timings.{md,json}` 实际存在（EV-0721 下载目录）；`docs/CODING-STANDARDS.md` §10.3 L2 ≤5min、后端 ≤10/≤5min，超预算处理明确。                                                                  |
| 3   | P1：CI 使用 RICCON_TEST_SERVICES=external + 每 job 唯一 namespace 复用 compose 热栈；迁移/回滚仍用一次性容器；commands.test 命令不变                   | pass                        | `.github/workflows/ci.yml` shard job `external + ci-<run_id>-b<N>`、`prepare --namespace`、失败日志、`down -v`；V9/V10/V11MigrationTest 与 SchemaVersionCheckerTest 在 external 下仍自建 MySQLContainer；`AGENTS.md` commands.test 未被 M19 diff 修改（EV-0724 校验）。                                                          |
| 4   | P2：CI 4-shard 矩阵，每个用例恰好执行一次；合并报告校验 tests/failures/errors/skips；后端墙钟 ≤5 分钟；分片命令不替代 canonical gate，除非另有合同决策 | pass                        | EV-0721：4 shard success、walls 213/204/209/198s、merge 196 类/1115 用例/0/0/0、每类 runs=1、problems=0；EV-0716 仍是 canonical gate，分片未作为 DoD 证据。F-001 记录 CI 覆盖率兜底残余，不影响本条结论。                                                                                                                        |
| 5   | P3：Worker 以租户级隔离替代每测试 39 表 TRUNCATE、API fixture 缓存；P2 后基线再降 ≥30%；Verifier 独立复跑同一套件                                      | pass（附 F-005 残余）       | Worker 基类无 TRUNCATE（仅注释），uniqueTenant + 三类全局 helper；API 平台会话/连接池/bcrypt 缓存有探针与绕过；独立全量 531.132s 对 P2 1231.995s 降 56.9%；EV-0697/0698（worker 依次 356/356/356，含反转与同 namespace）与 EV-0704/0705/0707（API 642 全量/反转/同 namespace 第二次）由独立 Verifier 执行。                      |
| 6   | P4：测试 tag 分层与至少 3 个纯逻辑类下沉（等价断言），由 Verifier 评审；不改变产品 AC/DoD                                                              | pass（with residual F-002） | 3 个 integration 基类（65 个子类继承）、10 个 security、4 个 migration、untagged=unit 文档化；EV-0708 真实选择冒烟证明安全/集成/迁移标签选中预期类、未打标签类被排除、默认不过滤仍执行；3 个 platform-domain 词汇测试 14 用例，app 断言零改动（RoleDefinitionsTest 等未改）。残余：新增而非迁出，未减少 app 用例/墙钟（F-002）。 |

## CI 4-shard、合并与用例集合

| 指标             | 值                                                                | 来源              |
| ---------------- | ----------------------------------------------------------------- | ----------------- |
| CI run           | 44955035025（PR #1 @ b69f019），conclusion success                | EV-0713 / EV-0721 |
| shard job 墙钟   | shard0 213s、shard1 204s、shard2 209s、shard3 198s（均 ≤300s）    | EV-0721           |
| shard suite 时间 | 135.036s（245）/142.420s（254）/148.561s（317）/134.162s（299）   | EV-0721           |
| merge            | 196 类 / 1115 用例 / 0/0/0；每类 runs=1；problems=0；verdict=pass | EV-0721           |
| backend-smoke    | 80s，迁移冒烟（V1→V11 `sort -V`）+ spotless                       | EV-0713           |
| frontend         | 330s：typecheck/lint/unit/build/E2E/a11y                          | EV-0713           |
| 本地独立全量     | 196/1115/0/0/0；suite 531.132s；wall 541.5s                       | EV-0716 / EV-0723 |

用例集合核对：M19 源码 diff 中 `@Test/ParameterizedTest/RepeatedTest` 新增 14、删除 0；无 `@Disabled`、`assume*`、`@Enabled*`；114 个 assert 行被替换为带本测试租户/唯一目标的精确断言（如 `artifactCount()`→`artifactCount(fixture.tenant())`），新增 80 行 assert（含 3 个 platform 词汇测试）；未见放宽计数或删除测试类。P2 baseline 193 类/1101 用例 → 当前 196 类/1115 用例，仅增加 P4 的 3 类/14 用例。

## 覆盖率

| 范围                  |   Line | Branch | 门槛  | 结论 |
| --------------------- | -----: | -----: | ----- | ---- |
| apps/api              | 92.41% | 75.08% | 80/70 | pass |
| apps/worker           | 87.89% | 70.71% | 80/70 | pass |
| platform-common       | 90.83% | 75.41% | 80/70 | pass |
| platform-domain       | 94.74% | 无分支 | 80/70 | pass |
| apps/web（vitest v8） | 87.01% | 80.01% | 80/70 | pass |

覆盖率命令与合同 `commands.coverage` 完全相同，EV-0716 同时作为 tests 与 coverage 证据，未重复跑第二遍。

## 任务评审对账

| 任务评审  | 最新 review_id | 结论 | 对账                                                                                                                                                                                                                      |
| --------- | -------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| TASK-0144 | REV-TASK-0144  | pass | F-001 P3 accepted（ISOLATION-DESIGN 与实现不一致）已由 TASK-0145/0146 校正文档；worker 356/356/356 独立复跑通过。                                                                                                         |
| TASK-0145 | REV-TASK-0145  | pass | F-001/F-002 P2 accepted、F-003 P3 accepted，已由 TASK-0146 全部 fixed（SecurityTestSupport/ApiIntegrationTestSupport 注释、ModelCatalog/Cost @AfterEach、计时口径）；REV-TASK-0146 独立反转/同 namespace 642/0/0/0 复核。 |
| TASK-0146 | REV-TASK-0146  | pass | F-001/F-002/F-003 均 fixed；本轮未发现这些清理引入新回归。                                                                                                                                                                |
| TASK-0148 | REV-TASK-0148  | pass | F-001 P3 accepted（P4b 增量而非迁出）→ 模块 F-002；F-002 P3 accepted（baseline 计时来源）→ 模块 F-004。                                                                                                                   |

TASK-0117/0118/0119/0147/0149 无任务级 review 文件（Ops/低风险任务由本模块评审覆盖）；TASK-0149 的 ChatStream 竞态修复有 EV-0712（10 次定向 + worker 全量）与 CI shard2 复现/修复记录。

## P0–P4 结果摘要

- **P0**：`test-timings.mjs` 自带空/坏报告负向自检；CI 每 shard 上传计时报告；§10.3 写入 L2 ≤5min、后端 ≤10min（分片前）/≤5min（分片后）及超预算处理，禁止删用例/`@Disabled`/放宽断言/`-DskipTests`。
- **P1**：CI shard 使用 external + 每 job 唯一 namespace；迁移/schema 测试仍用一次性容器；`commands.test` 文本未变；失败导出 compose 日志并 `down -v`。
- **P2**：4 shard 确定性分组、排除语法防漏跑、合并报告校验 every-class-once + 0/0/0；CI walls ≤213s；分片只跑 test，canonical gate 仍由 verifier 的 `verify jacoco:report` 取证。
- **P3**：Worker 移除每测试 39 表 TRUNCATE，改唯一租户 + `resetModelGatewayState/resetMcpCatalog/clearOutbox/ensureGlobalBaseline`；API 缓存平台会话/连接池/bcrypt 哈希并带 `/auth/session` 探针；P2→P4 独立全量 −56.9%（≥30%）。
- **P4**：tag 分层与选择冒烟通过；platform 新增 3 个词汇测试类（14 用例），app 断言未动；P4b 是增量守卫而非迁出（F-002 accepted）。

## 文档一致性

- `docs/CODING-STANDARDS.md` §10.1–§10.4：证据分层、热栈 opt-in、时间预算、tag 约定与 Surefire `-Dgroups` 示例完整；明确 `-Dgroups=unit` 不会选中 untagged、untagged=unit 是分类约定。
- `docs/RUNBOOK.md` 热栈/CI/分片/计时小节与实际 workflow 对齐：shard 只跑 test、`-Djacoco.skip=true`、artifact include-hidden-files、merge 校验项、baseline 重建流程均一致；并明确 canonical gate 由 verifier 执行。
- `docs/COLLABORATION.md` 新增“evidence run 命令必须单行”的规则，与本轮证据实践一致。
- `AGENTS.md` Project Contract 在 M19 范围内零改动（EV-0724 用 `git diff --quiet f31b3ec..HEAD -- AGENTS.md` 验证），commands.test 仍是 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`。

## Findings

见 front matter：F-001（P2 accepted，CI 覆盖率兜底）、F-002（P3 accepted，P4b 增量）、F-003（P3 accepted，Actions Node 20/setup-java v4）、F-004（P3 accepted，baseline 计时来源）、F-005（P3 accepted，并行隔离约定）。无 P0/P1，无 open。

## 未验证

- **CI 之外的 GitHub Actions 缓存行为**（Maven/pnpm cache 命中率）未逐 job 剖析；只以 run 44955035025 的 job 墙钟与步骤结论为准。
- **4 个 shard 在同一 runner 上的资源争抢**未在本地复现；本地只做 4 片模拟合并，真实并行由 CI 证明。
- **前端 typecheck/lint** 未在本会话重跑；CI frontend job 在 b69f019 上通过（EV-0713 校验 job conclusion），且 M19 未改前端源码。
- **P4 tag 的完整类矩阵**未逐类枚举（65 个继承基类 + 10 security + 4 migration 已统计；选择行为由 EV-0708 覆盖代表类）。
- **Product 预验收/用户验收**未执行（不属于 Verifier 职责）。

## 剩余风险

1. **CI 无覆盖率/verify job（F-001）**：主分支合并后仅 verifier 本地 gate 强制覆盖率；建议 M20 前补 coverage job 或明确合同口径。
2. **P4 不降 app 墙钟（F-002）**：P4b 只新增 platform 词汇守卫；若未来要求 P4 直接缩短 app 套件，需另拆任务。
3. **Actions 弃用（F-003）**：Node 20 强制到 Node 24、setup-java v4 停止更新；升级前 CI 仍可运行但存在未来失效风险。
4. **baseline 计时来源（F-004）**：时间值仅用于分片估算；新增/删除测试类必须重建 baseline 并录 EV。
5. **并行隔离（F-005）**：surefire 改多 fork/并行、或新增写全局目录的测试时需重新验证；当前 forkCount=1/reuseForks=true。
6. **Surefire fork 退出等待**：全量日志仍出现 `Surefire is going to kill self fork JVM ... 30 seconds`（EV-0700/0704/0706/0709/0716 均存在），属既有现象，约增加单轮 30s；未计入 M19 改造。
7. **既有覆盖缺口**：账号创建路径仍无短密码/密码策略拒绝断言（REV-TASK-0145 F-003 已记录，既有缺口）；M19 的 seed fixture 未取代任何策略测试，但后续不应把 seed 用于该目标。
8. **M01/M04 的 release 级安全例外**：trivy 的 CVE-2026-35568/34237 由 ADR-0011/.trivyignore 带 exp 2027-03-31 抑制，属 release 证据而非 M19 新增。

## 独立性声明

本次评审由 `riccon/verifier` 在独立会话 `ses_dffd10ae3ffewtWrcf3gijW826` 执行；未采信 Lead/Builder 总结作为结论。先读模块 AC、9 张任务卡、4 份任务评审、全部 M19 EV、CI workflow、分片/合并/计时脚本与设计文档，再在干净提交 `254e2a8` 上独立运行合同全量 test+coverage、build、E2E、a11y、security、分片自检/模拟合并，并重新下载 CI artifact 复核 4 shard 与 merge。未修改生产源码、迁移、构建配置、CI 或测试源码；只写本评审与 `.riccon` 证据/状态。全量 Maven 期间未启动 Serena/JDT LS；本会话未使用符号级 LSP（M19 验证对象是 CI/测试基础设施与文档，CodeGraph/Serena 对新增脚本无索引，按任务说明降级为 grep/read/git）。

## Gate

本评审写完后运行 `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M19 --project .`；结果见回执。模块状态、Product 预验收与用户验收由 Lead 处理，本文件不自行标记 done。
