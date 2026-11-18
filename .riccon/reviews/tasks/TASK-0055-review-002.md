---
review_id: REV-TASK-0055-002
module_id: M12
task_id: TASK-0055
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M12_TASK-0059_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [3e83b3a, 20fa604, a5abeda]
previous_review: REV-TASK-0055-001
head_commit: f38c59e
evidence: [EV-0370, EV-0371, EV-0374]
reviewed_evidence: [EV-0370, EV-0371, EV-0374]
findings:
  - id: F-001
    title: "上一轮 P1 已修复：e2e/real 优先级前移，E2E 命令改为可过滤的 pnpm -w exec 形式"
    severity: P1
    status: fixed
    evidence: "a5abeda：scripts/test-affected.mjs:1225 先判 e2e/real；:605 默认配置命令与 :614 real 配置命令均为 `pnpm -w exec playwright test --config ... <spec...>`，不再传 `--`。探针 --list：默认 1 test、real 1 test；现有 login spec：默认 3 tests、real 2 tests；不存在 spec 两种配置均 exit 1 `No tests found`。"
  - id: F-002
    title: "迁移文件映射只建议 API V9MigrationTest + API 模块全量，未提示 worker SchemaVersionCheckerTest"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs:893-907 迁移分支；apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java:30-38 持有 schema 版本期望；§10.1 已要求迁移类变更在 L2 跑全量。"
  - id: F-003
    title: "根 package.json 变更只映射前端全量，不提示 e2e/a11y script 变更"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs ROOT_FRONTEND_FILES 分支；package.json 同时定义 e2e/a11y/dev 脚本。"
  - id: F-004
    title: "§10.2 与 RUNBOOK 的 opt-in/namespace/一次性容器约束双写，存在漂移风险"
    severity: P3
    status: accepted
    evidence: "docs/CODING-STANDARDS.md:206-212 与 docs/RUNBOOK.md:75-81 当前一致；后续修改需同步两处。"
  - id: F-005
    title: "文档写“同一 namespace 只允许一个测试 JVM”，实现按 api/worker 分别加锁，文档更保守"
    severity: P3
    status: accepted
    evidence: "ExternalTestServices 锁名分别为 riccon_test_<ns>_api / _worker；数据库、S3 桶、OpenSearch 前缀也按模块派生。"
  - id: F-006
    title: "E2E_SPECS 死代码已删除"
    severity: P3
    status: fixed
    evidence: "a5abeda 删除 scripts/test-affected.mjs 原 :377 的 E2E_SPECS；`grep -n E2E_SPECS scripts/test-affected.mjs` 无结果。"
  - id: F-007
    title: "real 配置的 --list 会写 .riccon/evidence/TASK-0024/real-report.json，形成未跟踪文件"
    severity: P3
    status: accepted
    evidence: "e2e/real/playwright.real.config.ts 的 json reporter 固定输出到 evidenceDir/real-report.json；执行 `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts --list ...` 后出现未跟踪文件，探针已删除。"
created_at: "2026-11-19T04:00:26+08:00"
---

# TASK-0055 独立复审：F-001 E2E 过滤与 e2e/real 优先级

## 1. 结论

**verdict: pass（F-001 fixed，P0/P1 为 0）。** `a5abeda` 把 `e2e/real/**` 判断前移到 `.spec.ts` 之前，真实栈 spec 改用 `e2e/real/playwright.real.config.ts`；默认与真实栈 E2E 命令都改为 `pnpm -w exec playwright test --config <config> <spec...>`，不再经过 pnpm 的 `--` 转发，文件过滤实际生效。

本次独立探针验证：

- 默认配置 `--list` 只列 1 个目标 test；真实栈配置 `--list` 只列 1 个目标 test。
- 现有 `e2e/tests/login.spec.ts` 默认配置列出 3 tests / 1 file；`e2e/real/tests/login.spec.ts` real 配置列出 2 tests / 1 file。
- 不存在的 spec：默认与 real 配置均 exit 1 `Error: No tests found`；默认命令会先启动 webServer 再失败，但不会运行测试；real 命令直接失败。
- 删除 spec 的 `--staged` 模拟：默认/real 分别回退到对应配置的全量命令，不再生成指向不存在路径的定向命令。
- 多文件聚合、`--staged`、未知路径 exit 1、`pnpm test:affected -- --dry-run` 均仍成立。

因此上一轮 `REV-TASK-0055-001` 的 P1 F-001 可以关闭；TASK-0055 可以进入 done 收口。M12 gate 仍需 TASK-0055/TASK-0059 done、模块评审与最终提交上的合同全量 test/coverage/E2E/a11y/security EV。

## 2. 审查范围与方法

- 新增提交：`a5abeda fix(M12): 修复 test-affected 的 E2E 过滤与 e2e/real 优先级`，`git show --name-status` 只有 `scripts/test-affected.mjs`（72 insertions / 23 deletions）。
- 对照：`scripts/test-affected.mjs`、`e2e/playwright.config.ts`（`testDir=./tests`）、`e2e/real/playwright.real.config.ts`（`testDir=e2e/real/tests`）、上一轮 `TASK-0055-review.md`、`EV-0374`。
- 独立验证以 HEAD `f38c59e` 的干净工作区为起点；临时探针（`e2e/tests/probe-f001.spec.ts`、`e2e/real/tests/probe-f001.spec.ts`、API/worker/web 聚合探针、未知路径探针）在验证后全部删除，`git status --short` 恢复为空。
- 没有运行全量 E2E：只使用 `--list` 和不存在的 spec 路径；默认不存在路径命令启动了 webServer 后以 `No tests found` 退出，未执行任何测试。

## 3. 独立验证记录

| 检查/命令                                                                                                              | 退出码 | 结果                                                                                                                           |
| ---------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------ |
| `git show --name-status --format='%h %s' a5abeda`                                                                      | 0      | 仅 `scripts/test-affected.mjs`；无文档/package/生产/测试/迁移/契约改动                                                         |
| `node --check scripts/test-affected.mjs`                                                                               | 0      | 语法通过                                                                                                                       |
| `node scripts/test-affected.mjs --dry-run --base bcd39b8`                                                              | 0      | 21 个变更文件正确分类；`.riccon/**` 忽略、docs 手册、根 package 前端全量、脚本 node --check                                    |
| `pnpm test:affected -- --dry-run`                                                                                      | 0      | package script 可用；`--` 被脚本忽略，输出“变更文件（0）”与“无需运行自动化测试”                                                |
| 探针：`e2e/tests/probe-f001.spec.ts`                                                                                   | —      | 生成 `pnpm -w exec playwright test --config e2e/playwright.config.ts e2e/tests/probe-f001.spec.ts`                             |
| 探针：`e2e/real/tests/probe-f001.spec.ts`                                                                              | —      | 生成 `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts e2e/real/tests/probe-f001.spec.ts`              |
| `pnpm -w exec playwright test --config e2e/playwright.config.ts --list e2e/tests/probe-f001.spec.ts`                   | 0      | `Total: 1 test in 1 file`，只列 `probe default`                                                                                |
| `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts --list e2e/real/tests/probe-f001.spec.ts`    | 0      | `Total: 1 test in 1 file`，只列 `probe real`                                                                                   |
| `pnpm -w exec playwright test --config e2e/playwright.config.ts --list e2e/tests/login.spec.ts`                        | 0      | `Total: 3 tests in 1 file`（只列 login）                                                                                       |
| `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts --list e2e/real/tests/login.spec.ts`         | 0      | `Total: 2 tests in 1 file`（只列 real login）                                                                                  |
| `pnpm -w exec playwright test --config e2e/playwright.config.ts e2e/tests/does-not-exist-probe.spec.ts`                | 1      | `Error: No tests found`；webServer 启动后失败，未运行测试                                                                      |
| `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts e2e/real/tests/does-not-exist-probe.spec.ts` | 1      | `Error: No tests found`；直接失败                                                                                              |
| staged 删除探针（`git add` 后 `rm`，再 `--staged`）                                                                    | 0      | real spec 删除 → `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts`；默认 spec 删除 → 默认配置全量命令 |
| 多文件聚合探针：API `file`+`research`、worker `research`、web `chat`、默认/real spec                                   | 1      | API 一条 `-Dtest=` 并集；worker 一条；web 一条；默认 E2E 一条；real E2E 一条；未知路径打印原因并 exit 1                        |
| `node scripts/test-affected.mjs --staged --dry-run`（暂存 4 个探针）                                                   | 0      | 来源“暂存区”；默认与 real E2E 分组都出现，文件被正确纳入                                                                       |
| `node scripts/test-affected.mjs --dry-run`（带未知路径探针）                                                           | 1      | 打印 `probe-unknown-zone/unknown.txt：未匹配任何已知模块或映射规则`                                                            |
| `git diff --check`                                                                                                     | 0      | 无空白错误                                                                                                                     |
| `riccon contract validate --strict --project .`                                                                        | 0      | 0 errors, 0 warnings                                                                                                           |
| `riccon state validate --project .`                                                                                    | 0      | 0 errors, 1 warning（M11 planned 但已有 done task，允许）                                                                      |
| `cat .riccon/evidence/TASK-0055/EV-0374.json`                                                                          | —      | `manual:false`、`dirty:false`、`exit_code:0`、commit `a5abeda`、note 为 F-001 修复                                             |
| 探针清理后 `git status --short`                                                                                        | 0      | 空输出；`git ls-files --others --exclude-standard` 也为空                                                                      |

## 4. F-001 修复点对账

| 上一轮问题                                                     | 修复证据                                                                                                               | 结论  |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ----- |
| `e2e/real/**` 的 `.spec.ts` 先命中默认 `.spec.ts` 分支         | `scripts/test-affected.mjs:1225` 先判 `e2e/real/`；`e2e/real` spec 在 :1227-1245 走 real 配置                          | fixed |
| real spec 生成默认配置找不到的命令                             | :1238 调 `addE2eSpec(..., true)`；:614 生成 `--config e2e/real/playwright.real.config.ts <spec>`；`--list` 实测 1 test | fixed |
| `pnpm -w e2e -- <spec>` 的 `--` 让 Playwright 忽略过滤、跑全量 | :605/:614 改为 `pnpm -w exec playwright test --config ... <spec>`，不传 `--`；默认/real 探针 `--list` 各只列 1 test    | fixed |
| 不存在的 spec 可能 exit 0 跑全量                               | 默认与 real 配置的 exact 命令均 exit 1 `No tests found`；默认命令只是先启动 webServer，未运行测试                      | fixed |
| spec 删除/重命名                                               | :1227-1245 与 :1259-1268 的 `!existsSync` 分支分别回退 real/default 全量；staged 删除探针验证                          | fixed |
| 未使用 `E2E_SPECS` 死代码                                      | a5abeda 删除；`grep E2E_SPECS` 无结果                                                                                  | fixed |

补充说明：

- 默认命令 `pnpm -w exec playwright test --config e2e/playwright.config.ts <spec>` 对不存在的 spec 会先启动 webServer（约数秒到数十秒）再报 `No tests found`；这是 Playwright 的 webServer 生命周期行为，不是脚本缺陷。它不会执行任何测试，最终 exit 1。
- real 命令 `pnpm -w exec playwright test --config e2e/real/playwright.real.config.ts <spec>` 不会启动 stub webServer；没有真实栈时，`--list` 只验证过滤，实际执行会因连接失败而报错。脚本理由已写明“先启动 scripts/real-stack/run.mjs”。
- 上一轮 `--list` 探针产生的 `.riccon/evidence/TASK-0024/real-report.json` 已作为探针副作用删除（见 F-007）。

## 5. 边界与状态

- `a5abeda` 只改 `scripts/test-affected.mjs`；没有改 `docs/CODING-STANDARDS.md`、`docs/RUNBOOK.md`、`package.json`、生产/测试/迁移/contracts/compose/CI。
- `joinReasons` 的改动只是格式化换行，无行为变化；`E2E_SPECS` 删除和多文件聚合保留均正确。
- `EV-0374` 为 `manual:false`、`dirty:false`、exit 0、commit `a5abeda`；TASK-0055 状态 `review`、end commit `a5abeda`。
- 工作区在评审探针清理后干净；`state validate` 仅剩允许的 M11 warning。
- 上一轮评审 `TASK-0055-review.md` 保留未动。

## 6. Findings

### F-001（P1，fixed）

上一轮的 P1 已修复：`e2e/real` 优先级前移，E2E 命令改为可过滤的 `pnpm -w exec playwright test --config ... <spec...>`。独立探针证明默认/real 均只命中目标 spec；不存在的 spec 均 exit 1；删除 spec 回退到对应全量。无残留 exit 0 未执行目标 spec 的路径。

### F-002（P3，accepted，承接 M12 后续）

迁移映射只建议 `V9MigrationTest` + API 模块全量，未提示 worker `SchemaVersionCheckerTest` 的 `EXPECTED_SCHEMA_VERSION`。§10.1 已要求迁移类变更在 L2 跑全量，风险限于 L1 便利性。承接：M12 后续把迁移分支改为后端全量或在输出中显式提示 worker。

### F-003（P3，accepted，承接 M12 后续）

根 `package.json` 只映射 `pnpm -w test`，若改动 `e2e`/`a11y` script 不会提示。承接：M12 后续在根 package 分支补一句人工确认。

### F-004（P3，accepted，承接文档同步）

§10.2 与 RUNBOOK 对 opt-in/namespace/一次性容器约束双写；当前逐条一致。承接：后续保持 §10.2 规范、RUNBOOK 操作细节的分工，修改时同步两处。

### F-005（P3，accepted，承接文档措辞）

“同一 namespace 只允许一个测试 JVM”比实现更强：锁按 api/worker 分开。文档更保守，按文档执行安全。承接：M12 后续可改为“同一模块”，或保留保守表述。

### F-006（P3，fixed）

`E2E_SPECS` 死代码已在 a5abeda 删除；`grep -n E2E_SPECS scripts/test-affected.mjs` 无结果。

### F-007（P3，accepted，承接 M12 后续）

real 配置的 JSON reporter 在 `--list` 时也会写 `.riccon/evidence/TASK-0024/real-report.json`（未跟踪），脚本提示“可追加 --list”会让工作区出现该文件。本次已删除探针副作用。建议 M12 后续在 RUNBOOK/脚本提示中说明该文件，或让 real 配置在 `--list` 时跳过 JSON reporter。

## 7. 未验证与说明

- 没有跑全量 E2E；只运行 `--list` 与不存在的 spec 路径。上一轮误触发的两次全量 stub E2E 已记录在上一轮评审，本次未重复。
- 没有跑 Java 定向 Maven 测试；只验证命令形式和聚合类名清单。M12 gate 会跑合同全量。
- 没有启动真实栈、没有执行 real E2E 测试；只验证 real 配置的 `--list` 过滤。real 测试需要 `scripts/real-stack/run.mjs` 与目标环境，属于人工编排，不是模块 gate 证据。
- 没有修改生产/测试/脚本/文档正文；只新增本复审文件。探针、`real-report.json` 和遗留的 Umi dev server（上一轮 E2E 残留，端口 8123）均已清理。

## 8. M12 gate 就绪

- **F-001 fixed**，TASK-0055 可以进入 done 收口；TASK-0059 本复审完成后可由 Lead 标记 done。
- M12 gate 仍需要的条件：
  1. TASK-0055/TASK-0059 状态 done（本复审不自己标 done）；
  2. M12 模块评审 `.riccon/reviews/M12-review.md`，绑定最终提交上的合同全量 `commands.test` + `commands.coverage` + E2E/a11y/security EV；
  3. 当前 `gate check --module M12` 的失败项主要是任务未 done 与无模块评审，不是 F-001；模块全量证据齐备后再跑 gate。
- `test-affected` 仍然只是 L1 建议，不录 EV，不改变 §10.1 的 L2/L3 与 gate 证据规则；热栈仍不得作为 gate 证据。
