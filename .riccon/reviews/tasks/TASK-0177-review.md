---
review_id: REV-TASK-0177-001
module_id: M05
task_id: TASK-0177
reviewer:
  agent: riccon/verifier
  session: ses_df725a8bcffeY1s6OZoe1y6LIa
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 8c5781c9048befdc1911fc9738503ea938a44f38
  - c384c3579caf3725f6830d5460211844bf939e71
  - c1bd0e7fb0bdab80904a8e929e64946ce0d22b51
  - f8b6211b841e71db394628cc796177d2c86289de
head_commit: f8b6211b841e71db394628cc796177d2c86289de
baseline_commit: 8c5781c9048befdc1911fc9738503ea938a44f38
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  migration: true
  security: false
  perf: true
  review: true
  evidence: true
  docs: false
dod_note: >-
  V2 的越权/安全矩阵、真实栈性能复测、V13/V14 迁移回滚、OCR 零出网、restore-drill 与全量合同
  build/test/coverage/E2E/a11y 均完成；security 仅因 `deploy/Dockerfile.worker` 的 DS-0026（LOW，
  缺 HEALTHCHECK）使合同 `commands.security` exit 1，0 漏洞/0 密钥；docs 因 ADR-0023 §5.10/§9、
  RUNBOOK 与 CODING-STANDARDS §10.4 仍停留在 V12/LIKE/“承接中”而标 false。两者均为 Architect/Ops
  义务，见 F-001/F-003/F-004，不改变本任务“验证完成、无 P0/P1”的结论，但 M05 模块 review/gate
  前必须处理或明确记录。AC-20 的 ACL 写 residual 见 F-002；AC-20 在模块评审应写
  `pass (with residual)`，不得写成无条件 pass。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/ops
    due: M05 模块 review/gate 前
    accepted_reason: >-
      合同 `commands.security` 的失败不是漏洞或密钥：trivy 报告 0 vulnerabilities / 0 secrets，
      唯一 finding 是 `deploy/Dockerfile.worker` 的 DS-0026（LOW，缺 HEALTHCHECK），因命令带
      `--exit-code 1` 而退出 1。O1a 镜像为长驻 worker，补 HEALTHCHECK 的风险与成本都很低；
      在 Ops 修复（或 Lead 书面接受该 LOW 例外）前，不得把 security 证据标成无条件通过。
    title: "合同 trivy 因 Dockerfile.worker DS-0026（LOW，缺 HEALTHCHECK）exit 1"
    summary: >-
      EV-0945（f8b6211，dirty=false，exit 1）使用合同原文命令，扫描 pnpm/pom/根 pom 与
      `deploy/Dockerfile.worker`：0 漏洞、0 密钥；Dockerfile.worker 1 条 DS-0026 LOW
      “Add HEALTHCHECK instruction”。M04 时该文件尚不存在（EV-0626 仅 8 个 target），是 O1a
      新增镜像带来的新 misconfiguration，不是 M05 运行时回归。
    evidence: >-
      .riccon/evidence/TASK-0177/EV-0945-*.log:13-41；deploy/Dockerfile.worker（无 HEALTHCHECK）；
      EV-0626（M04，Detected config files num=0）对照。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/lead + riccon/architect
    due: M05 模块 review/gate 前（作为 AC-20 residual 明确记录）或 M09/perf 后续
    accepted_reason: >-
      检索 p95 97.516ms < 500ms、首字估算 201.5ms < 3s、API p95 386.8ms < 500ms、索引
      p95 7.7s < 60s，均达标；唯一超预算的是 ACL 写 p95 4330.25ms。TASK-0173 已把根因记录为
      QuotaJdbc 租户行锁争用（独立于 ACL 谓词），residual owner 为 Lead+Architect；这是 T4
      性能预算的已知残余，不是本次越权/功能回归。模块评审必须把 AC-20 写成 `pass (with residual)`，
      或在 gate 前取得用户对 ACL 写预算的单独决策；不得静默放宽 500ms。
    title: "AC-20 ACL 写 p95 4330ms > 500ms（quota 租户行锁 residual）"
    summary: >-
      EV-0947（f8b6211，dirty=false，exit 1）按 B11 相同口径复测：ACL-on/50 VU/100k chunk/
      1% 选择性查询；检索 p95 97.516ms（p50 27.27ms，samples 610），首字服务端 p95 104ms /
      估算用户首字 201.5ms，API p95 386.8ms，下载 p95 301ms，无 OCR 索引 p95 7717ms；ACL 写
      p95 4330.25ms、ACL 读 p95 225.75ms。k6 因此 exit 99，脚本整体 exit 1。EV-0922（B11）为
      73.412ms / 4576.75ms，两次结果同向，说明检索已达标而 ACL 写稳定超标。
    evidence: >-
      .riccon/evidence/TASK-0177/EV-0947-*.log:29-70、:194-195；EV-0922（TASK-0173）；
      .riccon/tasks/TASK-0173.json residual；docs/ACCEPTANCE.md M05-AC-20。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/architect + riccon/ops
    due: M05 模块 review/gate 前
    accepted_reason: >-
      纯文档漂移，不影响运行时代码与已通过的实现；TASK-0198 F-004 已以 P3 accepted 跟踪同一问题。
      ADR 是 M05 架构权威、RUNBOOK 是迁移/回滚操作依据，gate 前必须把 V14/FULLTEXT 事实写清，
      否则 M09 会按旧 LIKE 口径做容量与回滚演练。
    title: "ADR-0023 §5.10/§9 与 RUNBOOK 仍写 LIKE/V12，缺 V14 FULLTEXT/锁/回滚"
    summary: >-
      `docs/adr/ADR-0023-m05-knowledge-depth.md:430-432` 仍写“检索仍以 knowledge_chunk 的 LIKE
      扫描为主”，`:804` 仍写 `AgentContextJdbc` 为 `LIKE '%term%'`；`docs/RUNBOOK.md` 仍以 V1–V12
      为迁移/恢复演练口径（grep V13/V14 无命中）。实际实现已由 V14 切换为 ngram FULLTEXT
      （EV-0939 全量迁移到 v14；EV-0947 OpenSearch v2 alias/mapping 通过；V14MigrationTest 4/4）。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:430-432、:798-814；
      docs/RUNBOOK.md（grep V13/V14/FULLTEXT 无命中）；
      apps/api/src/main/resources/db/migration/V14__knowledge_fulltext_search.sql；
      EV-0939（V14MigrationTest 4/4）、EV-0947（v2 alias v2、bbox/acl mapping 通过）。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/architect
    due: M05 模块 review/gate 前
    accepted_reason: >-
      CODING-STANDARDS 维护规则要求“实现完成后把实际类名与本映射同步”；V2 已新增
      M05TenantIsolationTest/M05DepartmentIsolationTest/M05AclSecurityMatrixTest/M05AclAuditOrderTest
      并全量通过，映射仍写“承接中/尚未全部实现”。这是文档维护义务，不影响测试执行。
    title: "CODING-STANDARDS §10.4 M05 security 映射仍写“承接中”，缺实际类名"
    summary: >-
      `docs/CODING-STANDARDS.md:244` 仍写 M05 security “当前尚未全部实现”，并把 TEN-034、
      DEPT-011/014、IT-KB-110/119 等列为计划项；V2 已落地实际类名与测试方法（见 §2 矩阵表），
      模块 gate 前应由 Architect 更新映射，避免后续新增测试重复/漏登记。
    evidence: >-
      docs/CODING-STANDARDS.md:244；
      apps/api/src/test/java/com/riccon/api/knowledge/M05TenantIsolationTest.java、
      M05DepartmentIsolationTest.java、M05AclSecurityMatrixTest.java、M05AclAuditOrderTest.java；
      EV-0939（API 772 + worker 579，0 skip）。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/verifier（下次维护）/ riccon/lead
    due: M05b 前或下次真实栈维护
    accepted_reason: >-
      真实栈 full 模式没有像 perf 模式那样先 probe OcrCli；宿主缺 libtesseract 时 worker 按
      RUNBOOK 正确 fail-fast，导致 full 编排在 health 阶段超时（EV-0949），而不是产品启动缺陷。
      V2 按 RUNBOOK 用 `RICCON_OCR_ENABLED=0` 显式关闭 OCR 后，full 29/29 通过（EV-0951）。
      建议脚本复用 perf 的 ocrProbe 并把 `measured=false` 写入报告；在此之前，无 Tesseract 宿主
      必须显式设 `RICCON_OCR_ENABLED=0`，不能把未执行当通过。
    title: "真实栈 full 模式未 probe OCR，缺 libtesseract 宿主需手工 RICCON_OCR_ENABLED=0"
    summary: >-
      EV-0949 首次 full 运行 worker 因 `libtesseract.dylib` 缺失退出 1，编排等待
      `http://127.0.0.1:18081/actuator/health` 超时；这与 RUNBOOK “生产 OCR 启用且原生库缺失时
      worker 启动失败”一致。EV-0951 加 `RICCON_OCR_ENABLED=0` 后 full 全绿。OCR 引擎本身由
      EV-0946（O1b 镜像 `--network none`）覆盖，未把 OCR 场景标成通过。
    evidence: >-
      .riccon/evidence/TASK-0177/EV-0949-*.log；.riccon/evidence/TASK-0177-full/real-stack-worker.log；
      EV-0951（exit 0，29/29）；EV-0946（O1b ocr-check）。
reviewed_task_reviews:
  - REV-TASK-0164-001
  - REV-TASK-0165-001
  - REV-TASK-0166-002
  - REV-TASK-0167-001
  - REV-TASK-0168-002
  - REV-TASK-0169-001
  - REV-TASK-0171-001
  - REV-TASK-0172-001
  - REV-TASK-0180-001
  - REV-TASK-0197-002
  - REV-TASK-0198-001
reviewed_evidence:
  - EV-0939
  - EV-0940
  - EV-0941
  - EV-0942
  - EV-0943
  - EV-0944
  - EV-0945
  - EV-0946
  - EV-0947
  - EV-0948
  - EV-0951
created_at: "2026-11-24T00:20:00+08:00"
---

# REV-TASK-0177-001：M05-V2 越权/安全/性能/迁移/回滚独立验证

## 结论

**通过（pass，附 P2/P3 残余，无 P0/P1）。** 在 `8c5781c`（V1 closeout）之上，独立完成：

- 新增 5 个 API/worker 测试文件，落地 `TEN-034..036`、`DEPT-011..014`、`SEC-KB-002/003`、
  连续 ACL 审计顺序（V1 F-008）与 USERS 撤销后检索失效；全量合同 verify 后 API 772 用例、
  worker 579 用例，0 failure / 0 error / 0 skip。
- 真实栈 AC-20 复测：检索 p95 97.516ms、首字估算 201.5ms、API p95 386.8ms、索引 p95 7.7s
  达标；**ACL 写 p95 4330.25ms 仍超 500ms**，与 B11 EV-0922 同向，属 quota 租户行锁 residual
  （F-002）。模块评审必须把 AC-20 写 `pass (with residual)`。
- AC-05/SEC-KB-001 零出网 OCR 用 O1b 镜像在 `--network none` 下复验通过（EV-0946）；OCR 关闭
  开关、无伪引用与资源上限由 worker 测试覆盖。
- V13/V14 迁移、回滚与 schema 14 由 V13/V14MigrationTest、ApiApplicationTests 与 restore-drill
  （schema 14、49 表、2 触发器）验证；restore-drill 已从 V12 更新到 V14。
- 真实栈全量 29/29（EV-0951）覆盖 J1/J2、M03/M10/M04 与浏览器 E2E；首次失败的 3 条 M04 用例
  均为 M05 已批准的 429 文案、相对重置时间与引用抽屉改版导致的旧期望，已按 M05 语义更新并复跑。
- 合同 build/lint/typecheck/test+coverage/E2E/a11y 全部 exit 0；security 仅因 DS-0026 LOW
  exit 1（F-001）；ADR/RUNBOOK/CODING-STANDARDS 仍有文档漂移（F-003/F-004）。

本评审是独立技术验收，不是 M05 模块 gate、Product 预验收或用户验收。M05 模块 review 仍需
Lead 处理 F-001..F-005、AC-20 residual 与 Product/用户验收。

## 1. 实际写入与独立测试

| 文件                                                                           | 内容                                                                                                                             | 覆盖                                                  |
| ------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `apps/api/src/test/java/com/riccon/api/knowledge/M05AclMatrixTestSupport.java` | 独立矩阵基座：citation 种子、404/403 断言、遮蔽断言                                                                              | SEC-KB-002/003、TEN-035/036、DEPT-011                 |
| `M05TenantIsolationTest.java`                                                  | TEN-034（跨租户读/删/ACL/重索引/下载）、TEN-035（跨部门四处）、TEN-036（USERS 未授权/移出/停用）、manifest                       | 零 skip 清单 `SUITE-MANIFEST TEN-034 TEN-035 TEN-036` |
| `M05DepartmentIsolationTest.java`                                              | DEPT-011（四处一致）、DEPT-012（停用/移出下一请求）、DEPT-013（DEPT→USERS 后新成员不可见）、DEPT-014（知识库下载不放宽空间路径） | `SUITE-MANIFEST DEPT-011..014`                        |
| `M05AclSecurityMatrixTest.java`                                                | SEC-KB-002（可见无权 403 / 不可见 404 / 平台 404 / 无副作用）、SEC-KB-003（ACL 收紧后列表/详情/下载/旧引用四处一致）             | AC-07/09 M05 段                                       |
| `M05AclAuditOrderTest.java`                                                    | V1 F-008：连续 3 次 ACL 变更审计按时间顺序记录前后范围；第二次审计失败保留第一次结果且不部分生效                                 | M05-AC-08、ADR-0023 §5.6                              |
| `apps/worker/.../AgentContextJdbcAclTest.java`                                 | 新增 USERS 撤销后下一次检索立即失效                                                                                              | TEN-036 检索面、AC-09                                 |
| `e2e/real/tests/m04-quota-cost.spec.ts`、`m04-routing.spec.ts`                 | 真实栈 M04 旧期望对齐 M05 已批准文案/UI（429、+08:00 相对时间、引用抽屉）                                                        | M05-AC-17/18、M04-AC-08/10 回归                       |
| `scripts/ops/restore-drill.mjs`                                                | `EXPECTED_SCHEMA_VERSION` 12→14，self-test fixture 与提示同步；V13/V14 additive 说明                                             | 迁移/恢复演练                                         |

所有新增/修改测试均只使用真实 MySQL/对象存储或真实栈；未修改生产源码、迁移、契约或构建配置。
`reviewed_task_reviews` 中的旧 finding 已逐条对账：TASK-0166 F-001..F-005 fixed、F-006/F-007/F-008/F-010
accepted；TASK-0167 F-007 延期 IT 由 V2/模块 gate 承接；TASK-0168 F-001/F-002/F-003/F-005 fixed；
TASK-0197 X-001 已由 TASK-0198/EV-0939 解除；TASK-0198 F-001 fixed。未发现新的 P0/P1。

## 2. AC 对账

| AC                     | 结论                                             | 证据与说明                                                                                                                                                                                                                                                                                                                                                                                 |
| ---------------------- | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| M05-AC-05 零出网 OCR   | **pass**                                         | EV-0946：O1b 镜像 `--network none` 无路由、无 DB/OpenSearch/S3 env、uid 10001；OcrCli 文本 `RICCON OCR 12345`、avgConfidence 0.922、bbox 存在。OCR 关闭开关/无伪引用由 `OcrServiceTest`、`FileParseServiceIntegrationTest`、`ImageDocumentParserTest` 与 E2E-M05-02 覆盖；资源上限由 Xlsx ZIP、OcrImagePreprocessor 像素炸弹、Pdf 页数测试覆盖（EV-0939）。宿主 Tesseract IT 未跑，见 §4。 |
| M05-AC-09 M05 段越权   | **pass（M05 段；TEN-037/SEC-CONN-004 属 M05b）** | 新增 TEN-034..036、DEPT-011..014 与 SEC-KB-002/003 全部通过、零 skip；跨租户/跨部门/平台 404、同租户无权 403、U8 `admin_read` 审计、列表/详情/检索/引用/下载/删除/reindex 覆盖。检索面由 `AgentContextJdbcAclTest`（12+1 用例）复跑，含跨租户、DEPT、USERS 与撤销后立即失效。SEC-KB-004 注入 harness 12/12；SEC-KB-005 资源上限通过。                                                      |
| M05-AC-20 性能         | **pass (with residual)**                         | EV-0947：检索 p95 97.516ms <500、首字估算 201.5ms <3s、API p95 386.8ms <500、索引 7.7s <60s、下载 301ms；**ACL 写 p95 4330.25ms >500ms**（F-002，quota 租户行锁 residual）。检索 p95 在 500ms 内，`decisionRequired=false`，无需额外 FTS/OpenSearch 决策；OCR 量测 measured=false（宿主无 libtesseract），不把未执行当通过。                                                               |
| 迁移/回滚              | **pass**                                         | V13MigrationTest 4/4、V14MigrationTest 4/4（forward/rollback、索引/列保留、回滚后版本 13/12）；ApiApplicationTests 9/9 断言 migrations=14；EV-0948 restore-drill 源/演练库均 49 表、2 触发器、schema 14、dataKeys=1、credentials=2，target 清理与授权回收成功（partial：无主密钥/J1）。                                                                                                    |
| 安全/密钥              | **partial（P2 accepted）**                       | EV-0945：0 漏洞、0 密钥；仅 DS-0026 LOW 使合同命令 exit 1。审计 fail-closed（ACL/删除/U8/重索引/下载）由 `KnowledgeAuditFailClosedTest` 5/5 与 worker `KnowledgeSearchAuditFailClosedTest` 1/1 覆盖；下载无对象 key/预签名、错误响应无引擎配置/凭据由 `KnowledgeDownloadApiTest` 6/6 与 E2E 覆盖。                                                                                         |
| M05-AC-22 回归         | **pass（M05 段，附 residual）**                  | EV-0951 真实栈 29/29：J1/J2、M03/M10/M04、browser E2E、securityChecks、m04Checks、m03Checks 全 passed；EV-0943 stub E2E 51/51、EV-0944 a11y 18/18。M05 真实栈知识库 UI 未单列，见 §4；M05 契约 `KnowledgeContractM05Test` 10/10。                                                                                                                                                          |
| V1 F-008 连续 ACL 审计 | **fixed**                                        | `M05AclAuditOrderTest` 两个用例在 EV-0939 通过：三次变更审计顺序 `TENANT->USERS / USERS->DEPT / DEPT->TENANT`；第二次审计失败 500，visibility/acl_version 保持第一次结果，恢复后第三次成功。                                                                                                                                                                                               |

## 3. 全量验证与证据

| 项                        | 命令/口径                                                                                 | EV           | 结果                                                                                                                               |
| ------------------------- | ----------------------------------------------------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| build                     | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                     | EV-0940      | exit 0，dirty=false                                                                                                                |
| lint                      | `./mvnw -q spotless:check && pnpm -w lint`                                                | EV-0941      | exit 0                                                                                                                             |
| typecheck                 | `pnpm -w typecheck`                                                                       | EV-0942      | exit 0                                                                                                                             |
| test+coverage             | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                       | EV-0939      | exit 0；API 772、worker 579、web 597，0/0/0/skip；覆盖率 API 92.78%/76.29%、worker 85.84%/70.01%、web 87.19%/80.71%（line/branch） |
| E2E（合同）               | `docker compose ... up -d --wait && pnpm -w e2e`                                          | EV-0943      | exit 0；51 passed                                                                                                                  |
| a11y                      | `pnpm -w a11y`                                                                            | EV-0944      | exit 0；18 passed（含 M05 三页 @a11y）                                                                                             |
| security                  | `trivy fs --scanners vuln,secret,misconfig ... --exit-code 1 .`                           | EV-0945      | **exit 1**；0 漏洞/0 密钥；DS-0026 LOW（F-001）                                                                                    |
| OCR 零出网                | `scripts/ops/ocr-check --image riccon-worker-ocr:o1b`                                     | EV-0946      | exit 0；manifest/权重/语言包/uid/无路由/OcrCli 全通过                                                                              |
| perf knowledge            | `PERF_M05_SCENARIO=knowledge ... node scripts/real-stack/run.mjs --mode=perf`             | EV-0947      | **exit 1（预期）**；检索 97.516ms、首字 201.5ms、API 386.8ms、索引 7717ms；ACL 写 4330.25ms（F-002）                               |
| restore-drill             | `REAL_EVIDENCE_TASK=... node scripts/ops/restore-drill.mjs --apply --allow-partial`       | EV-0948      | exit 0（partial）；schema 14、49 表、2 触发器、恢复耗时 12.4s                                                                      |
| 真实栈全量（V1 residual） | `RICCON_OCR_ENABLED=0 REAL_EVIDENCE_TASK=... node scripts/real-stack/run.mjs --mode=full` | EV-0951      | exit 0；**29/29 passed**，J1/J2/M03/M10/M04/security/m04/m03 全 passed                                                             |
| 失败保留                  | 首次 full（EV-0949 worker OCR fail-fast）、第二次 full（EV-0950 3 条 M04 stale 期望）     | EV-0949/0950 | 保留，未用 retry 掩盖；根因与修复见 §1/F-005                                                                                       |

定向命令（EV-0939 之前迭代，未单独回灌 EV）：
`./mvnw -q -pl apps/api -Dtest='M05TenantIsolationTest,M05DepartmentIsolationTest,M05AclSecurityMatrixTest,M05AclAuditOrderTest' test`
13/0/0/0，`SUITE-MANIFEST TEN-034 TEN-035 TEN-036` 与 `DEPT-011..014` 输出；现有
`TenantIsolationSecurityTest` 31/31、`DepartmentIsolationTest` 11/11 同批通过。

## 4. 未验证与 residual

- **TesseractOcrEngineIT 未在宿主运行**：宿主无 libtesseract，`RICCON_OCR_IT=true` 会失败；OCR
  原生引擎由 O1b 镜像内 OcrCli（EV-0946）验证，IT 与真实栈 OCR 单页/多页场景仍 residual。
- **M05 真实栈知识库 UI 未单列 E2E**：EV-0951 的 29 条真实栈 E2E 不含 M05 知识库页面；M05 UI
  仍由 stub E2E 51/51 + a11y 18/18 + API 矩阵/真实栈 seed 覆盖。m05.spec 的 stub 属性未在本 V2
  扩展；建议 M05b 或下次真实栈维护补一条知识库列表/ACL/下载真实栈旅程。
- **TASK-0166 F-008 剩余缺口**：平台管理员 delete 未在本轮新增断言（下载/reindex 已有）；
  ChatAgentService/ResearchAgentService 完整模型运行级知识引用集成仍由静态/检索层测试覆盖。
- **TASK-0197 F-008 批量 knowledge 引用测试**：`ArtifactApiTest` 列表仍只种 MCP 引用，批量
  knowledge bbox/查询次数断言未补；不影响本轮 M05 越权/安全/性能，但仍是 P3 accepted。
- **检索 max 24.2s / avgRowsExamined 81420**：EV-0947 检索 p95 97.5ms 达标，但 slow_log 中
  单条最大值 24.2s、平均扫描行数明显高于 EV-0922（1105）；可能是造数/索引重建期间的冷查询，
  未定位。建议 M09/perf 在空闲宿主复测时记录并排除；不改变 AC-20 p95 结论。
- **restore-drill partial**：未提供主密钥/pepper 与 J1 API，因此 credential 解密与 J1 冒烟未执行；
  结构判据（schema 14/49 表/2 触发器/授权回收）已通过。完整演练仍归 M09/release。

## 5. 剩余风险

1. **AC-20 ACL 写**：4330ms 是用户可见预算的 8.7×，即使 ACL 写不在聊天热路径，也必须在模块
   review 中作为 `pass (with residual)` 或取得用户单独决策；不能写成无残余 pass。
2. **security DoD**：DS-0026 很容易修，但在 Ops 修复前合同 `commands.security` 仍非 exit 0；
   M05 模块 review 不能把 security 标成无条件通过。
3. **worker 分支覆盖率 70.01%**：仅高于 70% 门槛 0.01pp；后续任何删除 worker 测试都会让合同
   `verify` 变红。不是本次 finding，但属于模块 gate 的脆弱点，建议 M05b 前加缓冲。
4. **文档漂移**：ADR/RUNBOOK/CODING-STANDARDS 未同步 V14/实际类名；M09 按旧 LIKE 口径做迁移
   演练会错误评估锁与回滚窗口。
5. **真实栈 full 的 OCR 开关**：脚本未 probe；无 Tesseract 宿主必须显式 `RICCON_OCR_ENABLED=0`，
   否则 full 在 worker health 阶段失败（EV-0949 已保留）。

## 6. 下一步

- Lead：把 F-001（Ops 补 HEALTHCHECK 或书面接受 DS-0026）、F-003/F-004（Architect/Ops 更新
  ADR/RUNBOOK/CODING-STANDARDS）、F-002（AC-20 residual/用户决策）写入 M05 模块 review/gate；
  不要把本 V2 pass 当成模块 gate 通过。
- TASK-0177 可进入 review/done 流程；任务卡 evidence 建议列入 EV-0939..EV-0948、EV-0951，
  EV-0945/EV-0947 作为负向/带残余证据保留，EV-0949/0950 作为失败尝试保留。
- 模块评审前确认 M05 `reviewed_task_reviews` 的 REV-TASK-0164-001..REV-TASK-0198-001 均已读，
  并处理 V1/V2 的 residual（Tesseract IT、M05 真实栈 UI、TASK-0166/0197 的 P3 accepted）。
- `riccon gate check --module M05` 当前应继续失败：TASK-0177 尚未 done、无 M05 模块 review、
  F-001/F-002 未在模块 review 记录；这是预期状态，修复/记录后再重跑。
