---
review_id: REV-M12-002
previous_review: REV-M12-001
module_id: M12
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e08d1b473ffezoltlVNmpba9tZ
  model: deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: cb977f98a493bfb7db9f9d4b02731ac62e195053
verdict: pass
reviewed_evidence:
  - EV-0363
  - EV-0364
  - EV-0365
  - EV-0367
  - EV-0368
  - EV-0372
  - EV-0374
  - EV-0375
  - EV-0376
  - EV-0377
  - EV-0378
  - EV-0379
  - EV-0380
  - EV-0381
  - EV-0382
  - EV-0383
  - EV-0384
  - EV-0494
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
  acceptance:
    - EV-0363
    - EV-0365
    - EV-0374
    - EV-0375
    - EV-0377
  build:
    - EV-0376
  tests:
    - EV-0494
  coverage:
    - EV-0494
  e2e:
    - EV-0379
  docs:
    - EV-0382
findings:
  - id: F-001
    title: "E2E 定向命令过滤与 e2e/real 优先级 P1 已修复"
    severity: P1
    status: fixed
    evidence: "REV-TASK-0055-002；EV-0374/EV-0375；scripts/test-affected.mjs:1225 先判 e2e/real，:605/:614 改为 pnpm -w exec playwright test --config ... <spec>；探针 --list 默认/real 各 1 test，不存在 spec 两种配置均 exit 1 No tests found。"
  - id: F-002
    title: "迁移文件映射只建议 API V9MigrationTest + API 模块全量，未提示 worker SchemaVersionCheckerTest"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs:883-898；SchemaVersionChecker.java:29 定义 EXPECTED_SCHEMA_VERSION；§10.1 已要求迁移类变更在 L2 跑全量。承接 M12 后续。"
  - id: F-003
    title: "根 package.json 变更只映射前端全量，不提示 e2e/a11y script 变更"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs ROOT_FRONTEND_FILES 分支；package.json 同时定义 e2e/a11y/dev 脚本。承接 M12 后续。"
  - id: F-004
    title: "§10.2 与 RUNBOOK 的 opt-in/namespace/一次性容器约束双写，存在漂移风险"
    severity: P3
    status: accepted
    evidence: "docs/CODING-STANDARDS.md:206-212 与 docs/RUNBOOK.md:75-81 当前一致；后续修改需同步两处。承接文档同步。"
  - id: F-005
    title: "文档写“同一 namespace 只允许一个测试 JVM”，实现按 api/worker 分别加锁，文档更保守"
    severity: P3
    status: accepted
    evidence: "ExternalTestServices 锁名分别为 riccon_test_<ns>_api / _worker；按文档执行安全。承接文档措辞。"
  - id: F-006
    title: "E2E_SPECS 死代码已删除"
    severity: P3
    status: fixed
    evidence: "a5abeda 删除 scripts/test-affected.mjs 原 E2E_SPECS；grep -n E2E_SPECS 无结果。"
  - id: F-007
    title: "real 配置的 --list 会写 .riccon/evidence/TASK-0024/real-report.json，形成未跟踪文件"
    severity: P3
    status: accepted
    evidence: "e2e/real/playwright.real.config.ts 的 json reporter；执行 real --list 后出现未跟踪文件，探针已删除。承接 M12 后续/RUNBOOK 说明。"
  - id: REVALIDATION
    severity: P3
    status: fixed
    title: "2026-11-20 用当前内核按合同 commands.test 重录 test/coverage；旧 review 归档为历史"
    evidence: "EV-0494；旧 review REV-M12-001 归档于 .riccon/archive/reviews/M12-review-REV-M12-001.md"
created_at: "2026-11-20T13:38:00+08:00"
---

# M12 模块评审：测试效率与文档

## 0. 回溯说明（2026-11-20）

- **回溯原因**：DEC-0022 合并后合同 `commands.test` 改为 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`；本模块旧 test EV（EV-0377）命令文本不再匹配，`gate check` 在 2026-11-20 以 `EV-0377 command does not match commands.test` 失败。代码未回归，属于证据/流程漂移。
- **本次动作**：在当前 HEAD `cb977f9`（工作树干净）上用合同 `commands.test` 全量重跑一次，并按项目规则同时作为 `test` 与 `coverage` 证据：**EV-0494**（挂 TASK-0054），exit 0，`commit=cb977f98a493bfb7db9f9d4b02731ac62e195053`，`dirty=false`。
- **本次实测**：Java 765 tests（api 453 / worker 263 / platform-common 33 / arch 12 / domain 4），failures 0、errors 0、skipped 0；前端 46 文件 384 tests passed、0 skipped；前端覆盖率 statements 87.8% / branches 80.75% / functions 84.63% / lines 87.71%；Maven `verify` 含 `jacoco:check` 通过。
- **评审边界**：本次只重录 test/coverage；build/e2e/a11y/security 与 test-affected 定向基准未重跑，仍绑定旧 EV（见正文 §3–§8），原结论不扩大。旧 review `REV-M12-001` 原样归档在 `.riccon/archive/reviews/M12-review-REV-M12-001.md`。
- **legacy/N-A**：M12 是维护/效率模块（`kind: fix`），无新的用户可见 AC，Product 预验收/用户验收按 N/A 处理（延续旧评审 §9 口径），不作为本模块 gate 前置。
- **findings 处理**：旧 findings 全部保留，状态未改（F-001/F-006 fixed，其余 accepted P3）；正文为旧评审原文，当前状态以 front matter 为准。


## 1. 结论

**verdict: pass。** M12 是维护/效率模块（`kind: fix`），没有新的用户可见验收标准；本次在最终代码提交 `eff5252`（HEAD `eff5252`，干净树）上独立执行了合同 build/test/coverage/test_e2e/a11y/security 与 docs 检查，全部 exit 0，每个 EV `dirty:false`。另外执行了 lint/typecheck 与真实栈 J1/J2 回归（补充证据，不作为 gate 证据）。

- 合同测试：Java **628** tests（apps 579 = api 316 + worker 263；platform 49），frontend **212** tests，**0 skipped**；与预期 579 apps 一致。
- 覆盖率：Java 行 **89.75%** / 分支 **72.21%**；frontend 行 **87.67%** / 分支 **80.51%**；均高于合同 80/70。
- E2E：**26/26**；a11y：**8/8**，无 serious/critical；security：0 vulnerabilities / 0 secrets / 0 misconfigurations。
- 真实栈：10/10 浏览器测试通过，J2 HTTP 闭环、安全检查与 M03 checks 全部 passed（EV-0383，补充证据）。
- P0/P1 open = 0；F-001/F-006 已 fixed；F-002/F-003/F-004/F-005/F-007 为 accepted P3，承接 M12 后续/文档同步。

M12 gate **可以运行**：模块评审与合同 EV 已就绪；本评审不自行把任务/模块标 done，也不运行 `gate check`。Lead 将 TASK-0060 置 done 后即可运行 gate。

## 2. 评审范围与方法

- 最终代码提交：`eff5252`（`chore(M12): 启动模块评审与全量证据`）。所有合同 EV 均在此提交、干净工作树上录制。
- 对照：`docs/ARCHITECTURE.md`、`docs/ACCEPTANCE.md`、`docs/CODING-STANDARDS.md` §10.1/§10.2、`docs/RUNBOOK.md`、M12 任务卡与评审、`scripts/test-services.mjs`、`scripts/test-affected.mjs`、`package.json`、CI 配置。
- 没有修改生产源码/测试逻辑/迁移/contracts/AGENTS/PRODUCT/威胁模型/compose/CI；本次只新增本评审文件。
- 没有使用 external 热栈作为 gate 证据：EV-0366（`RICCON_TEST_SERVICES=external` 全量）未列入 `reviewed_evidence`/`dod_evidence`；合同 test/e2e 均为默认路径（未设置 `RICCON_TEST_SERVICES`）。

## 3. 合同命令证据

| DoD                  | 合同命令                                                                                                                 | EV      | 结果                                                                                        |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------ | ------- | ------------------------------------------------------------------------------------------- |
| build                | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                    | EV-0376 | exit 0；Web 生产构建成功                                                                    |
| tests                | `./mvnw -q -T1C verify && pnpm -w test`                                                                                  | EV-0377 | exit 0；Java **628**（api 316 / worker 263 / platform 49），frontend **212**，**0 skipped** |
| coverage             | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                      | EV-0378 | exit 0；Java 行 **89.75%** / 分支 **72.21%**；frontend 行 **87.67%** / 分支 **80.51%**      |
| test_e2e             | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                   | EV-0379 | exit 0；Playwright **26/26**（J1/J2/M03/M13/错误/响应式/a11y 回归）                         |
| a11y                 | `pnpm -w a11y`                                                                                                           | EV-0380 | exit 0；**8/8**，无 serious/critical                                                        |
| security             | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` | EV-0381 | exit 0；0 vulnerabilities / 0 secrets / 0 misconfigurations                                 |
| docs                 | `git diff --check`                                                                                                       | EV-0382 | exit 0；无空白错误                                                                          |
| 补充：lint/typecheck | `./mvnw -q spotless:check && pnpm -w lint && pnpm -w typecheck`                                                          | EV-0384 | exit 0；Prettier/ESLint/tsc 通过                                                            |
| 补充：真实栈 J1/J2   | `REAL_EVIDENCE_TASK=TASK-0060 node scripts/real-stack/run.mjs`                                                           | EV-0383 | exit 0；10/10 真实栈浏览器测试；J2 闭环/安全检查/M03 checks passed                          |

测试数说明：合同 `./mvnw verify` 同时运行 `platform` 架构/公共模块测试；apps 579（api 316 + worker 263）与预期一致，platform 49 为合同全量的一部分，合计 628。所有 628 + 212 个测试均通过，0 skipped。

## 4. Module acceptance 对账

| Module acceptance                                   | 结论 | 证据                                                                                                                                                                                                                      |
| --------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 显式 opt-in 热测试栈，默认/CI 仍隔离容器            | 通过 | TASK-0054 独立评审（REV-TASK-0054-001）确认 opt-in 与默认隔离；本次 EV-0377 在未设置 `RICCON_TEST_SERVICES` 的干净树运行默认 Testcontainers 路径；CI 未设置该变量（grep 无结果）。EV-0366 external 全量不作为 gate 证据。 |
| Representative 定向测试固定启动时间降低且全量不更慢 | 通过 | EV-0365 benchmark（M12-A）：Hitl 42.89→7.13s（83.4%）、Auth 20.99→7.77s（63.0%）；全量 default 484s vs external 475s（TASK-0054 评审记录）。本次未重复 external 基准。                                                    |
| test-affected 脚本与 RUNBOOK/CODING-STANDARDS 落地  | 通过 | EV-0374 修复 E2E 过滤/real 优先级；EV-0375 复审 pass（REV-TASK-0055-002）；`docs/CODING-STANDARDS.md` §10.2 与 `docs/RUNBOOK.md` 热栈/受影响测试小节已落地并与 M12-A 实现一致。                                           |

M12 是维护模块：以上为技术/效率验收，**没有用户可见 AC**；Product 预验收 **N/A**。本评审不修改模块的 `product_acceptance` 状态，由 Lead/Product 决定是否标记 N/A 或跳过。

## 5. DoD 明细

| DoD        | 结论 | 说明                                                                             |
| ---------- | ---- | -------------------------------------------------------------------------------- |
| acceptance | true | 三项 module acceptance 均有独立证据；默认路径未使用 external 作为 gate 证据。    |
| build      | true | EV-0376。                                                                        |
| tests      | true | EV-0377；628 Java + 212 frontend，0 skipped。                                    |
| coverage   | true | EV-0378；Java 89.75/72.21，frontend 87.67/80.51，高于 80/70。                    |
| e2e        | true | EV-0379；26/26 默认 stub E2E。                                                   |
| review     | true | 本模块评审 + TASK-0054/0055/0056/0058/0059 任务级评审；无 open P0/P1。           |
| docs       | true | EV-0382；§10.2/RUNBOOK 已在 TASK-0055 审查；本次未改文档正文。                   |
| evidence   | true | 所有 EV `dirty:false`、commit `eff5252`、exit 0；哈希/输出路径已由 riccon 记录。 |

## 6. 默认路径、CI 与证据边界

- 本次合同 test/e2e/a11y 均在未设置 `RICCON_TEST_SERVICES` 的 shell 中运行；环境检查无该变量。日志中可见 Testcontainers 创建 MySQL/OpenSearch/RustFS 容器，证明默认隔离路径生效。
- `.github/workflows/ci.yml` 未设置 `RICCON_TEST_SERVICES`，也未使用 `test:affected`；CI 仍执行合同全量。
- EV-0366（external 全量）是 M12-A 的历史验证记录，本次模块 gate **未**将其列入 `reviewed_evidence` 或 `dod_evidence`。EV-0365 benchmark 仅用于性能验收，不作为 test/e2e 通过证据。
- 热栈/真实栈都不得替代 §10.1 的 L2/L3；`test-affected` 仍只输出 L1 建议，不录 EV。

## 7. Findings

| ID    | 级别 | 状态     | 摘要                                                                                     |
| ----- | ---- | -------- | ---------------------------------------------------------------------------------------- |
| F-001 | P1   | fixed    | E2E 定向命令的 `--` 过滤失效与 `e2e/real` 优先级已修复；REV-TASK-0055-002 独立探针通过。 |
| F-002 | P3   | accepted | 迁移映射未提示 worker `SchemaVersionCheckerTest`；承接 M12 后续。                        |
| F-003 | P3   | accepted | 根 `package.json` 不提示 e2e/a11y script 变更；承接 M12 后续。                           |
| F-004 | P3   | accepted | §10.2 与 RUNBOOK 双写；承接文档同步。                                                    |
| F-005 | P3   | accepted | namespace 锁措辞比实现更严（保守）；承接文档措辞。                                       |
| F-006 | P3   | fixed    | `E2E_SPECS` 死代码已删除。                                                               |
| F-007 | P3   | accepted | real `--list` 写 `real-report.json`；承接 M12 后续/RUNBOOK 说明。                        |

F-002/F-003/F-004/F-005/F-007 为已登记的残余 P3，**不冒充解决**；均不阻塞 M12 gate。F-001/F-006 已由 REV-TASK-0055-002 确认 fixed。

## 8. 安全、性能与真实栈

- Security：Trivy 未发现未抑制的 vulnerability/secret/misconfiguration。`.trivyignore` 中 CVE-2026-35568/CVE-2026-34237 为 ADR-0011 已登记的 MCP SDK 服务器端例外（Riccon 只用 client，无入站 MCP server；到期 2027-03-31），本次未新增例外。
- 性能：M12-A benchmark 显示定向测试启动时间显著降低；本次未重复 external 基准。合同 test 全量运行时间与 M12-A 记录同量级，未见回归。
- 真实栈：EV-0383 的 10/10 浏览器测试覆盖 J1 上传/流式/引用/重开、J1 运行详情、快速发送、J2 创建/审批/产物/拒绝、a11y；`real-stack-j2.json` 记录 citationCount=5、阶段 RETRIEVAL→TOOL→APPROVAL→GENERATION 全 SUCCEEDED；`real-stack-checks.json` 记录 J1 firstTokenMs=67、审计 append-only、跨租户 404 矩阵；M03 checks passed。真实栈是补充验证，不是模块 gate 的替代。

## 9. M12 gate 就绪

- 模块评审 `REV-M12-001` 已写，`reviewed_evidence` 覆盖 TASK-0054 最新 EV-0367、TASK-0055 EV-0374、TASK-0056 EV-0368、TASK-0058 EV-0372、TASK-0059 EV-0375，以及 TASK-0060 本轮 EV-0376..EV-0384。
- 合同 build/tests/coverage/e2e/docs 均绑定合同命令的 EV；acceptance 绑定 EV-0363/EV-0365/EV-0374/EV-0375/EV-0377。
- 当前唯一任务状态缺口是 TASK-0060 本身（本任务仍为 in_progress → 交付后置 review）；Lead 将其置 done 后即可运行 `riccon gate check --module M12`。**本评审不运行 gate、不自行标 done。**
- M12 无用户可见 AC，Product 验收 N/A；不阻塞 M10 前置的文档同步（M10 本身仍是 planned，见模块路线图）。

## 10. 未验证与残余风险

- 未重复 external 全量基准/外部模式测试；按本次要求，external 不作为 gate 证据。
- 未在真实客户环境或非本机硬件上复测性能；benchmark 仍是本机结果。
- 真实栈脚本会 reset compose 测试栈；本次执行后 compose 服务已恢复 healthy，但 M12-A 的 namespace 数据库可能被清理；这不影响代码或 gate 证据。
- F-002/F-003/F-004/F-005/F-007 仍是 P3 残余；不阻塞本模块。
- `.trivyignore` 两个 CVE 例外有 ADR 与到期日；M07/到期复核仍需执行。
- 工作区在 EV 录制后只有 `.riccon/` 下的证据/任务/状态文件未提交（按交付流程留给 Lead 提交）；非 `.riccon` 文件无变更。
