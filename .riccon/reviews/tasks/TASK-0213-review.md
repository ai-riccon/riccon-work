---
review_id: REV-TASK-0213-001
module_id: M25
task_id: TASK-0213
reviewer:
  agent: riccon/verifier
  session: ses_df3abd5feffe0J3gwFBZXXDNEo
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 40650a6802840309f5d78a7f1f0187196e2b9c73
reviewed_evidence:
  - EV-1002
  - EV-0997
  - EV-1005
  - EV-1006
  - EV-1007
head_commit: 40650a6802840309f5d78a7f1f0187196e2b9c73
baseline_commit: e7a5a5b
dod:
  acceptance: true
  build: true
  tests: true
  security: true
  perf: true
  migration: true
  review: true
  evidence: true
dod_note: >-
  本任务只新增测试，无生产源码/契约/迁移/部署变更（`git diff --name-status e7a5a5b..HEAD` 仅 7 个
  `apps/**/test/**` 文件）。在最终提交 40650a6 上：定向连接器/安全/注入测试 133/133（API 48、Worker 85）
  零失败零跳过；V12 迁移 13/13、SchemaVersionChecker 9/9；1000 对象 perf 报告 `status=passed`（同步
  33.2s、队列 p95 45.4s、检索 p95 33.6ms、首字估算 p95 76.6ms、API p95 169.7ms）；Trivy exit 0
  （0 漏洞/0 密钥/0 错配，仅 ADR-0011 两条既有抑制）；零出网探针 PASS；O2b self-test 与 dry-run exit 0。
  命令日志见正文；EV 由 Lead 在最终提交记录，本会话不修改任务状态。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-kb-perf
    due: M25 gate 前
    title: "性能报告 reproduceCommand/env 快照仍未反映 PERF_M05_ACL_WRITES=0（TASK-0209 F-001 未修复）"
    summary: >-
      在 40650a6 上按 EV-1002 等价命令独立跑 1000 对象 perf：k6 报告 `aclWriteVus=0` 且整体
      `status=passed`，但同一报告目录的 `real-stack-env.json` 仍写 `perfM05.concurrency.aclWriteVus=1`，
      `reproduceCommand` 仍缺 `PERF_M05_ACL_WRITES=0`。照抄报告内复现命令会启动已接受的 ACL 写场景并触发
      500ms 阈值，无法复现本次 passed 矩阵。EV-1002 的 command 字段本身正确，问题只在报告元数据。
    accepted_reason: >-
      本次独立取证显式带 `PERF_M05_ACL_WRITES=0`，连接器同步/索引/队列/检索/首字数据有效；缺陷不改变
      性能结论，但属于可复现性 P2。TASK-0209 已把 due 定为 V2b 前，当前仍未修复；`scripts/real-stack/`
      不在 TASK-0213 写范围，必须在 M25 gate 前修复或由 Lead 书面接受，不能静默忽略。
    evidence: >-
      scripts/real-stack/run.mjs:850,860-877；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/v2b-perf-evidence-perf/real-stack-env.json；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/riccon-m25-v2b-logs/perf-1000.log:398；
      REV-TASK-0209-001 F-001。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/ops
    due: M25 gate 前
    title: "继承 TASK-0211 F-001/F-002：RUNBOOK 误删恢复与维护账号最小权限仍未修订"
    summary: >-
      当前 RUNBOOK 仍写“误置空且源端凭据仍在客户密钥库，只能由管理员在管理端重新录入”，但已软删除连接器
      在现有 API 中不可见、不可写凭据，也没有 restore/undelete；同一节仍未列出
      `blank-connector-credentials.mjs --apply` 的最小 DB 权限（`SELECT/UPDATE kb_connector`、
      `INSERT audit_event`、`CREATE TEMPORARY TABLES`）。脚本行为本身已在 TASK-0211 的 51 项真实 MySQL
      drill 中验证，本次 `--self-test` 与 `--dry-run` 也通过，缺陷只在 RUNBOOK 可执行性。
    accepted_reason: >-
      只影响 M09 前运维文档与首次生产执行指导，不改变脚本 fail-closed 行为；M25 无生产调度。继承
      REV-TASK-0211-001 的已接受 P2，不能因为本任务通过就忘记在 M25 gate 前修订。
    evidence: >-
      docs/RUNBOOK.md:1262,1270,1287,1337-1338；REV-TASK-0211-001 F-001/F-002；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/riccon-m25-v2b-logs/o2b-dryrun.log。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector + riccon/ops
    due: M25 gate 前（文档口径）
    title: "RICCON_CONNECTOR_ENABLED=false 时只读列表与软删除端点仍可用，RUNBOOK 措辞需明确"
    summary: >-
      独立测试实测关闭开关后 `GET /api/v1/knowledge/connectors` 仍返回 200（只读观察可用）；
      代码审计显示 `ConnectorService.delete` 也未调用 `requireEnabled()`，关闭期间仍可对连接器执行
      `documents=SOFT_DELETE`。RUNBOOK 写“连接器完全不可用”且“已同步文档不删除数据”，与只读/清理能力
      存在措辞差异。
    accepted_reason: >-
      这些端点需要 `TENANT_ADMIN`，且关闭开关不是 SSRF/凭据修复手段（RUNBOOK 已明确）；只读列表和显式
      管理删除在回滚期可能是有意保留的观察/清理能力，不触发新同步或网络。记录为 P3，建议 RUNBOOK 明确
      “关闭只阻止创建/更新/测试/同步/凭据写入，不隐藏历史数据、不阻止管理员显式软删除”，避免下一班运维
      误判。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:108-147,304-327；
      apps/api/src/test/java/com/riccon/api/knowledge/connector/ConnectorDisabledRollbackVerifyTest.java；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/riccon-m25-v2b-logs/final-targeted-connector-40650a6.log。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-kb-connector
    due: M25 gate 前（后续替换 HTTP 客户端时）
    title: "verifyRedirectTarget 未校验 HTTPS/allowHttp，当前无生产调用"
    summary: >-
      静态审计 `ConnectorEndpointPolicy.verifyRedirectTarget`：只检查 scheme 是 http/https、主机在允许
      列表、解析结果非元数据/链路本地；没有像 `validateUrl` 那样拒绝 `http`（即使原端点为 HTTPS 且
      `allowHttp=false`）。该方法当前无生产调用，同一类新增的 302 真实本地服务测试已证明 AWS SDK
      默认客户端不自动重定向，因此当前无凭据降级路径。
    accepted_reason: >-
      不可达代码；实际防护由 S3 客户端禁用重定向承担（新测试证明第二主机零请求）。保留为纵深防御
      遗留：若未来改用会重定向的 HTTP 客户端并启用逐 Location 校验，必须同时校验 scheme/allowHttp。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/connector/domain/ConnectorEndpointPolicy.java:220-252；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorS3RedirectSafetyTest.java；
      apps/worker/src/test/java/com/riccon/worker/connector/ConnectorEndpointPolicySsrfVerifyTest.java。
notes: >-
  未运行全量 Maven/E2E/coverage/a11y（按 TASK-0213 说明由 Lead 在 M25 gate 最终提交执行）；未在 V2b
  重新跑浏览器 E2E，V1b 的 EV-1005/1006/1007（63/63、22/22）仍是 UI/a11y 依据。未运行 `riccon` 状态
  命令，未修改任务/模块状态、生产源码、contracts、migrations 或 deploy。`riccon gate check --module M25`
  当前应失败：TASK-0209/0213 等任务状态未完成且无模块 review，这不是本任务代码问题。
created_at: "2026-11-24T15:46:00+08:00"
---

# REV-TASK-0213：M05b-V2b 越权/安全/性能/迁移/回滚独立验证

## 结论

**通过（pass）**。在最终提交 `40650a6` 上，M05b 连接器越权/SSRF/凭据/删除传播/注入矩阵、1000 对象
性能、V12 迁移/回滚、零出网和 Trivy 扫描均达到本任务验收；没有 P0/P1。保留 4 条已接受的 P2/P3
残余（性能报告复现元数据、O2b RUNBOOK 文档、关闭开关范围、未使用的重定向校验），均不影响本任务
的安全/性能结论，但 M25 gate 前必须处理 F-001/F-002。

## 验证了什么

### 1. 定向安全/越权测试（最终提交 40650a6）

命令：

```bash
RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=v2b-verify \
./mvnw -q -T1C -pl apps/api,apps/worker -am \
  -Dtest='com.riccon.api.knowledge.connector.*Test,com.riccon.api.knowledge.KnowledgeRetentionTest,com.riccon.api.knowledge.M05TenantIsolationTest,com.riccon.api.contract.ConnectorContractM05bTest,com.riccon.api.injection.ConnectorCitationInjectionTest,com.riccon.worker.connector.*Test,com.riccon.worker.index.service.ConnectorKnowledgeIndexIntegrationTest,com.riccon.worker.injection.InjectionHarnessTest,com.riccon.worker.injection.InjCatalogTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：exit 0；**API 48/48、Worker 85/85，failures 0 / errors 0 / skipped 0**。日志：
`/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/riccon-m25-v2b-logs/final-targeted-connector-40650a6.log`。
真实 MySQL 8.4.13、RustFS、OpenSearch external 栈，namespace `v2b-verify`；未使用 retry、skip 或
串行参数。

覆盖与独立补充：

| 面                                               | 证据                                                                                                                                                                                             |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 连接器 8 端点角色/租户矩阵                       | `ConnectorCrudApiTest` 10、`ConnectorCredentialSyncApiTest` 4；新增 `ConnectorTenantIsolationVerifyTest` 3（员工 8 端点 403、跨租户凭据 404、TEN-037 列表/详情/跨部门、U8 admin_read、套件清单） |
| SSRF/端点策略                                    | `ConnectorEndpointPolicyTest` 5；新增 `ConnectorEndpointPolicySsrfVerifyTest` 3（允许列表 DNS 解析到元数据/链路本地、字面量元数据变体、运行期 DNS 重绑定）                                       |
| S3 客户端不重定向                                | 新增 `ConnectorS3RedirectSafetyTest` 1：真实本地 302 到第二主机，第二主机零请求                                                                                                                  |
| 凭据只写/掩码/轮换/审计/日志                     | `ConnectorCredentialSyncApiTest` 4、`ConnectorCrudApiTest` 10、新增日志脱敏用例 1；`SEC-CONN-001` 覆盖                                                                                           |
| 删除传播/tombstone/不完整 list/重试/409/租户停用 | `ConnectorSyncIntegrationTest` 18、`ConnectorDeletePropagationIntegrationTest` 3、`ConnectorSyncCompletionIntegrationTest` 1、`ConnectorDocumentWriteServiceIntegrationTest` 2                   |
| 连接器文档索引/平台桶/租户前缀                   | `ConnectorKnowledgeIndexIntegrationTest` 12                                                                                                                                                      |
| 注入 canary/tripwire/引用遮蔽                    | `InjectionHarnessTest` 25、`InjCatalogTest` 6、`ConnectorCitationInjectionTest` 3                                                                                                                |
| 保留与删除传播（IT-CONN-107）                    | 新增 `KnowledgeRetentionTest#retention_coversConnectorSourceDocuments`，软删除 + `reason=RETENTION` + 索引 DELETE                                                                                |
| 回滚开关                                         | 新增 `ConnectorDisabledRollbackVerifyTest` 1（管理写/测试/同步/凭据 503、文档仍可读）、`ConnectorDisabledFeatureVerifyTest` 1（Worker 跳过历史事件）                                             |
| 契约                                             | `ConnectorContractM05bTest` 11/11；契约版本 `1.5.0-m05b`                                                                                                                                         |
| M05 段回归                                       | `M05TenantIsolationTest` 4/4；`KnowledgeRetentionTest` 4/4                                                                                                                                       |

### 2. 性能（1000 对象，ACL 写按 DEC-0043=A 单列）

命令与 EV-1002 等价，额外使用独立证据目录：

```bash
PERF_M05_SCENARIO=knowledge PERF_M05_CONNECTOR=1 PERF_M05_CONNECTOR_OBJECTS=1000 \
PERF_M05_DOCS=5000 PERF_M05_CHUNKS_PER_DOC=20 PERF_M05_DURATION=30s PERF_M05_BACKLOG=0 \
PERF_M05_ACL_WRITES=0 REAL_PERF_SKIP_M04=1 \
PERF_M05_CONNECTOR_SYNC_TIMEOUT_MS=1200000 PERF_M05_CONNECTOR_INDEX_TIMEOUT_MS=1200000 \
REAL_EVIDENCE_DIR=/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/v2b-perf-evidence-perf \
node scripts/real-stack/run.mjs --mode=perf
```

结果：exit 0；报告 `status=passed`。关键值：

- 连接器同步：1000/1000（created 1000、failed 0、skipped 0、deleted 0、complete=true），处理 33.2s
  ≤ 900s；端到端 33.7s；
- 队列等待 p95 45.4s ≤ 300s（index p95 45.5s；sync p95 430ms）；
- 检索 p95 33.6ms ≤ 500ms（760 样本）；用户首字估算 p95 76.6ms ≤ 3s；API p95 169.7ms ≤ 500ms；
- 1000 文档全部 INDEXED、chunksWithToken=1000、清理 1000/1000、bucketStatus=204；OpenSearch v2
  别名/mapping 校验通过；
- `PERF_M05_ACL_WRITES=0` 时 `aclWithinBudget=null`，按 DEC-0043=A 未把 ACL 写残差算作连接器 perf
  通过或失败；OCR 因宿主无 libtesseract 未量测（M05 F-005 既有残余，不冒充通过）。

报告目录：`/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/v2b-perf-evidence-perf/`
（`perf-m05-knowledge.json`、`real-stack-env.json`）。运行日志：
`.../riccon-m25-v2b-logs/perf-1000.log`。EV-1002 可核对：其既有数值同步 40.1s、队列 48.8s、检索
37.5ms，均与本轮同量级且达标。

### 3. 迁移/回滚

```bash
./mvnw -q -T1C -pl apps/api,apps/worker -am \
  -Dtest='com.riccon.api.migration.V12MigrationTest,com.riccon.worker.persistence.SchemaVersionCheckerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：exit 0；`V12MigrationTest` 13/13、`SchemaVersionCheckerTest` 9/9，零失败零跳过；日志
`.../riccon-m25-v2b-logs/final-migration-40650a6.log`。覆盖 V11 种子数据升 V12、历史文档
`visibility=TENANT`、`knowledge_document` 新列/CHECK、`knowledge_document_acl`、连接器两表、
citation `source_version`、V12 回滚到 V11 且不残留 M05 对象、schema 版本固定 14。
真实栈 `riccon` 库直接核对：Flyway 14/1，`knowledge_document_acl`/`kb_connector`/`kb_connector_item`
三表存在，`visibility`/`department_id`/`connector_id`/`external_id`/`acl_version` 列存在。未伪造生产
迁移，未执行 down migration。

### 4. 零出网（O2a）与凭据置空（O2b）

- 零出网：`docker compose --profile connector-egress ... run --rm --no-deps connector-egress-probe`
  exit 0，末行 `connector-egress-probe: PASS`（无默认路由、1.1.1.1 不可达、169.254.169.254 不可达）；
  `--network none` 探针路由表仅表头、外部连接 exit 1；演练网络 `down` 后已删除。日志
  `.../riccon-m25-v2b-logs/egress-drill.log`、`egress-none.log`。
- O2b：`node scripts/ops/blank-connector-credentials.mjs --self-test` exit 0；对真实 V12 测试库
  `--dry-run --via=compose --database riccon_test_v2b_verify_api` exit 0（due=0、activeWithCiphertext=7、
  total=36）。脚本自 TASK-0211 提交 `b7dac1e` 后无变更；TASK-0211 的 51 项真实 MySQL drill
  （`.riccon/evidence/TASK-0211/verifier-o2b-drill-2026-11-24.log`）仍适用，本次不重复其全量。
  其 F-001/F-002 文档残余见 F-002。

### 5. Security 扫描

```bash
trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target \
  --skip-dirs .git --exit-code 1 .
```

结果：exit 0；报告表 7 个 pom/pnpm 目标 0 漏洞、0 secret、0 misconfig；仅 `.trivyignore` 中
`CVE-2026-35568`/`CVE-2026-34237` 两条 ADR-0011 既有抑制（exp:2027-03-31），未新增例外。日志
`.../riccon-m25-v2b-logs/final-trivy-40650a6.log`。

## 未验证

- 未跑全量 `./mvnw verify`/coverage、`pnpm -w test -- --coverage`、全量 E2E/a11y；按 TASK-0213
  说明由 Lead 在 M25 gate 最终提交执行。V1b 的 EV-1005/1006/1007（E2E 63/63、a11y 22/22）仍是
  UI/a11y 依据，本评审不复述为 V2b 执行。
- 未在 V2b 浏览器重跑连接器页面凭据掩码/禁用态/axe；V1b 与 TASK-0215 的浏览器证据仍权威。
- 未测 OCR 引擎/真实扫描件（宿主 libtesseract 不可用；M05 F-005 既有残余），也未测 ACL 写 p95
  （DEC-0043=A 单列）；二者不被本任务判为连接器 perf 通过。
- 未做镜像级 Trivy、生产 K8s NetworkPolicy、客户真实 S3/OSS；测试使用 RustFS，生产出网策略归 M09。
- 未运行 `riccon` 状态命令，未修改任务状态、生产源码、契约、迁移或部署。

## Findings

F-001..F-004 均为已接受残余，无 open P0/P1。详见 front matter：F-001 性能报告复现元数据（P2，builder-kb-perf，
M25 gate 前）；F-002 继承 O2b RUNBOOK 误删恢复/最小权限（P2，ops，M25 gate 前）；F-003 关闭开关只读/软删除
端点范围需文档明确（P3）；F-004 未使用的 `verifyRedirectTarget` 未校验 HTTPS/allowHttp（P3）。

## 剩余风险

- **F-001 未修复**：若 M25 gate 照抄 perf 报告的 `reproduceCommand`，会启动 ACL 写场景并触发已接受的
  500ms 残差；必须以 EV-1002 command 或本轮显式命令取证。
- **F-002 未修复**：M09 首次生产 `--apply` 前若按 RUNBOOK 操作，可能因缺 `CREATE TEMPORARY TABLES` 失败，
  或误以为已软删除连接器能在管理端重新录入凭据；M09 前必须修订。
- **O2b 无仓库内回归 harness**：TASK-0211 F-003 仍成立；脚本改动没有自动保护，M09 或 ops 后续任务应补。
- **M05b 模块级证据仍缺**：全量 E2E/a11y、coverage、最终 EV 由 Lead 在模块 gate 记录；当前分支任务
  TASK-0209/0213 状态未变，`riccon gate check --module M25` 预期 fail，不是本任务代码缺陷。
- **ACL 写 / OCR**：M05 既有 residual（ACL 写 p95 4.3–4.6s、宿主 Tesseract IT 未跑）未被本任务改变；
  不得在本任务通过中静默视为已修复。

## 下一班

1. Lead 在最终提交（本分支 `40650a6`，合并后以 Lead 的最终提交为准）记录 EV：定向连接器矩阵、迁移、
   perf、trivy、零出网；复核本 review 的 F-001/F-002。
2. 模块 gate 前修复或重新书面接受 F-001；修订 RUNBOOK F-002；由 Verifier 在模块 review 汇总
   TASK-0204/0205/0207/0208/0209/0214 的 accepted residuals 与 AC 残余。
3. 运行 M25 合同全量 build/lint/typecheck/test/coverage/E2E/a11y/security，再写模块 review。
