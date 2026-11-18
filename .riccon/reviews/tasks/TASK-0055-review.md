---
review_id: REV-TASK-0055-001
module_id: M12
task_id: TASK-0055
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M12_TASK-0059_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: [3e83b3a, 20fa604]
head_commit: eee9a58
evidence: [EV-0370, EV-0371]
reviewed_evidence: [EV-0370, EV-0371]
findings:
  - id: F-001
    title: "E2E 定向命令实际不受文件过滤控制：pnpm 转发的 `--` 让 Playwright 跑全量 stub E2E；e2e/real spec 可假绿"
    severity: P1
    status: open
    evidence: "scripts/test-affected.mjs:595 生成 `pnpm -w e2e -- <specs>`；pnpm 10.34.9 实际执行 `playwright test --config e2e/playwright.config.ts -- <specs>`。实测 `pnpm -w e2e -- e2e/tests/does-not-exist-probe.spec.ts` 打印 `Running 26 tests`（文件过滤被忽略）；`pnpm -w e2e -- --list e2e/tests/login.spec.ts` 跑完 26 个测试并 exit 0（--list 与文件过滤均被忽略）。scripts/test-affected.mjs:1200 的 `.spec.ts` 分支先于 :1214 的 `e2e/real/` 分支，故 `e2e/real/tests/*.spec.ts` 也得到同一命令；默认配置 testDir=./tests 不含真实栈 spec，26 个 stub 测试全绿时命令 exit 0 而未执行变更 spec。"
  - id: F-002
    title: "迁移文件映射只建议 API V9MigrationTest + API 模块全量，未提示 worker SchemaVersionCheckerTest 的 EXPECTED_SCHEMA_VERSION"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs:859-873；apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java:30-38 持有 schema 版本期望；§10.1 已要求迁移类变更在 L2 跑全量，风险限于 L1 便利性。"
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
    evidence: "ExternalTestServices 锁名分别为 riccon_test_<ns>_api / _worker；数据库、S3 桶、OpenSearch 前缀也按模块派生。保守措辞不会导致不安全生产，只会限制并行。"
  - id: F-006
    title: "E2E_SPECS 在启动时递归收集但未被引用，属于死代码"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs:377 定义 E2E_SPECS；全文件无其他引用。不影响输出，建议删除或用于 spec 存在性校验。"
created_at: "2026-11-19T03:45:20+08:00"
---

# TASK-0055 独立评审：M12-B test-affected 与运行手册

## 1. 结论

**verdict: fail。** P0 为 0，但有一条 **P1（F-001）**：脚本为 E2E 生成的“定向”命令实际上会跑全量 stub E2E。原因是 `pnpm -w e2e -- <spec>` 在 pnpm 10.34.9 下把 `--` 原样传给 Playwright（`playwright test --config e2e/playwright.config.ts -- <spec>`），Playwright 忽略 `--` 之后的文件过滤，直接跑 `e2e/tests` 的全部 26 个测试；再叠加 `e2e/real/` 判断晚于 `.spec.ts`，`e2e/real/tests/*.spec.ts` 变更会得到一条**可能 exit 0 却没有执行变更 spec** 的建议命令，构成 L1 假绿。

Architect 的 F-001 把该缺陷定为 P2 accepted，理由是“命令会失败而不会产生假绿”。本次独立复验证明该前提不成立：命令不会因为找不到 spec 而失败，而是跑全量 stub E2E。按 verifier 规则，P1 必须在 TASK-0055 done / M12 gate 前修复，因此本任务当前不可 done，M12 gate 不可启动（修复并复验后可重新提交）。

其余部分质量良好：

- 两个提交的边界干净：`3e83b3a` 只改 `scripts/test-affected.mjs`、`package.json`（1 行 script）、`docs/CODING-STANDARDS.md`（+8 行 §10.2）、`docs/RUNBOOK.md`（+56 行）；`20fa604` 只改脚本。没有生产/测试逻辑、迁移、contracts、compose、CI 改动。
- API/worker/web/docs/scripts 映射、多文件聚合、`--staged`、`--base`、未知路径非零退出、无法定位回退模块全量并打印原因，均通过独立探针验证。
- §10.2 没有改 §10.1；RUNBOOK 的 start/stop/status/prepare/reset、`stop --volumes`、namespace、迁移一次性容器、故障表与 M12-A 实现一致；热栈未被写进 gate 证据路径。
- EV-0370/EV-0371 均为 `manual: false`、`dirty: false`、exit 0；工作区干净，HEAD `eee9a58`；`state validate` 只有允许的 M11 历史 warning。

## 2. 审查范围与方法

- 审查提交：`3e83b3a`（脚本 + §10.2 + RUNBOOK + package script）、`20fa604`（多文件聚合修正）。
- 对照：`docs/CODING-STANDARDS.md` §10.1/§10.2、`docs/RUNBOOK.md` 热测试栈小节、`scripts/test-affected.mjs`、`scripts/test-services.mjs`、`package.json`、M12-A 的 `ExternalTestServices` / `V9MigrationTest` / `SchemaVersionCheckerTest`、`.github/workflows/ci.yml`、`e2e/playwright.config.ts`、`e2e/real/playwright.real.config.ts`。
- 独立验证以 HEAD `eee9a58` 的干净工作区为起点；临时探针文件在验证后全部删除，`git status --short` 恢复为空。
- 评审不跑合同全量 test/coverage/E2E；M12 gate 另行在最终提交上跑全量并录 EV。

## 3. 独立验证记录

| 检查/命令                                                                                    | 退出码 | 结果                                                                                                                                                                                                              |
| -------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git status --short` / `git rev-parse HEAD`                                                  | 0      | 空输出；HEAD `eee9a5811944ae841838fedec84a7d8adab4e4bf`                                                                                                                                                           |
| `git show --name-status 3e83b3a 20fa604`                                                     | 0      | `3e83b3a` 仅 4 个文件（M/A），`20fa604` 仅脚本；无源码/测试/迁移/contracts/compose/CI                                                                                                                             |
| `node --check scripts/test-affected.mjs`                                                     | 0      | 语法通过                                                                                                                                                                                                          |
| `node scripts/test-affected.mjs --dry-run --base bcd39b8`                                    | 0      | 16 个变更文件正确分类（`.riccon/**` 忽略、docs 手册、根 package 前端全量、脚本 node --check）                                                                                                                     |
| `pnpm test:affected -- --dry-run`                                                            | 0      | package script 可用；pnpm 转发的 `--` 被脚本忽略，输出“变更文件（0）”和“无需运行自动化测试”，非静默空建议                                                                                                         |
| 临时探针：API `file`+`research` 两文件、worker `research`、web `chat`、`e2e/tests/*.spec.ts` | 1      | 每个模块/类别各聚合为**一条**命令；API 输出 `-Dtest=` 并集；web 输出一条 vitest；E2E 输出一条命令                                                                                                                 |
| 同一探针 + `probe-unknown-zone/unknown.txt`                                                  | 1      | 打印“无法识别的路径”和原因，非零退出；探针已删除                                                                                                                                                                  |
| `node scripts/test-affected.mjs --staged --dry-run`（暂存 3 个探针）                         | 1      | 暂存区文件与未跟踪文件都被纳入；来源标注“暂存区（git diff --cached）”                                                                                                                                             |
| `node scripts/test-affected.mjs --staged --base HEAD --dry-run`                              | 1      | `[test-affected] --base 与 --staged 不能同时使用`                                                                                                                                                                 |
| `node scripts/test-affected.mjs --base does-not-exist --dry-run`                             | 1      | `无法解析 --base does-not-exist: fatal: Needed a single revision`                                                                                                                                                 |
| 临时探针：`apps/api/.../zzzprobe/Probe.java`（无匹配测试）                                   | 0      | 回退 `./mvnw -q -T1C -pl apps/api -am verify`，打印“无法从 feature=zzzprobe 唯一确定测试类”                                                                                                                       |
| `pnpm --filter @riccon/web exec vitest run src/app.test.tsx`                                 | 0      | 1 file / 1 test passed；生成命令形式可执行                                                                                                                                                                        |
| `node scripts/test-services.mjs status`                                                      | 0      | compose 三服务 healthy；列出 namespace 数据库/索引；未启动新服务                                                                                                                                                  |
| `git diff --check`                                                                           | 0      | 无空白错误                                                                                                                                                                                                        |
| `riccon contract validate --strict --project .`                                              | 0      | 0 errors, 0 warnings                                                                                                                                                                                              |
| `riccon state validate --project .`                                                          | 0      | 0 errors, 1 warning（M11 planned 但已有 done task，任务允许）                                                                                                                                                     |
| `cat .riccon/evidence/TASK-0055/EV-0371.json`                                                | —      | `manual:false`、`dirty:false`、`exit_code:0`、commit `20fa604`；EV-0370 同样 `dirty:false`、commit `3e83b3a`                                                                                                      |
| E2E 命令形状复验（详见 F-001）                                                               | 1 / 0  | `pnpm -w e2e -- e2e/tests/does-not-exist-probe.spec.ts` 打印 `Running 26 tests`（1 个无关测试失败，exit 1）；`pnpm -w e2e -- --list e2e/tests/login.spec.ts` 跑完 26 个测试、exit 0；证明文件过滤/`--list` 被忽略 |
| 修复形状复验                                                                                 | 0      | `pnpm -w exec playwright test --config e2e/playwright.config.ts --list e2e/tests/login.spec.ts` → 3 tests in 1 file                                                                                               |
| 探针清理后 `git status --short`                                                              | 0      | 空输出                                                                                                                                                                                                            |

## 4. 验收标准对账

| #   | AC 原文                                                                        | 结论       | 依据                                                                                                                                        |
| --- | ------------------------------------------------------------------------------ | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | CODING-STANDARDS 增加测试执行分层与热栈约束，Architect 确认不改变 M11 结论     | 通过       | `3e83b3a` 对 `docs/CODING-STANDARDS.md` 为纯新增 8 行 §10.2；§10.1 原文未改；`REV-M12-B-ARCH-001` pass，仅接受 P2/P3                        |
| 2   | RUNBOOK 增加热栈启动/停止/重置、单 agent 约束、迁移一次性容器、常见故障        | 通过       | `docs/RUNBOOK.md:50-104`；命令与 `scripts/test-services.mjs` 的 start/status/stop/stop --volumes/prepare/reset 一致；锁/迁移容器/故障表齐全 |
| 3   | test-affected 根据 git diff/未跟踪文件映射测试；无法确定回退模块全量并打印原因 | **不通过** | API/worker/web/docs/scripts 映射与回退正确；但 E2E 命令不受文件过滤控制，`e2e/real` spec 会假绿（F-001）                                    |
| 4   | package.json 增加明确 script；只用于内层循环，不用于 gate 证据                 | 通过       | `package.json:36` 仅新增 `test:affected`；CI/合同命令/当前 EV 均未把它当测试或 gate 证据（EV-0370/0371 只做脚本 dry-run）                   |
| 5   | 不新增平行文档；不碰源码/迁移/契约；脚本在样例 diff 上可运行                   | 通过       | `name-status` 仅脚本 + 两份文档 + package script；`--base bcd39b8` 样例 dry-run exit 0；无新文档                                            |

## 5. 映射、RUNBOOK、文档与边界

### 5.1 test-affected 映射

- API：`main/java` 按 feature 匹配同包/类名测试；`common/config/web/persistence/audit/admin/error` 与入口类回退 API 全量；测试支撑类/删除测试类回退模块全量。探针验证 `file` + `research` 两文件只产出一条合并 `-Dtest=` 命令。
- Worker：同规则，共享包为 `common/config/persistence`；探针命中 research 相关测试并聚合为一条命令。
- Web：同目录同名测试 → 定向；逐级向上找最近含测试目录 → 子树 vitest；配置/测试支撑/删除测试 → 前端全量。探针 `apps/web/src/pages/chat/ProbeWidget.tsx` 输出一条包含 5 个 chat 测试的命令。
- E2E：`.spec.ts` → 定向命令（但见 F-001，实际不定向）；共享配置/fixture → E2E 全量；`e2e/real/**` 设计为手册检查，但被 `.spec.ts` 分支抢先，且生成命令会跑全量 stub E2E。
- Docs/scripts：docs → 手册检查；`scripts/*.mjs` → `node --check` + 手动提示；`.riccon/**` → 明确忽略；未知路径 → 打印原因、exit 1。
- 多文件聚合（`20fa604`）已修好同一模块只保留第一条命令的问题：Java 按模块合并，Web/E2E 合并为一条，理由去重拼接。

### 5.2 RUNBOOK 与 M12-A 实现

- `start` / `status` / `stop` / `stop --volumes` / `prepare --namespace` / `reset --namespace` / `benchmark` 均在 `scripts/test-services.mjs` 有对应实现；`stop --volumes` 走 `docker compose down -v`。
- 单 agent 唯一 namespace、同 namespace 命名锁、凭据同源、迁移/回滚/schema 版本测试一次性容器均写明；`ExternalTestServices` 用 `GET_LOCK` 立即失败，`V9MigrationTest` 自建 Testcontainers MySQL，`SchemaVersionCheckerTest` external 分支也自建容器。
- 热栈不作为 gate 证据在 §10.2、RUNBOOK 和脚本输出三处明确；CI 与合同命令未设置 `RICCON_TEST_SERVICES`。

### 5.3 文档一致性

- §10.2 是 §10.1 的操作补充，逐字未改 §10.1；引用 RUNBOOK 的小节名与实际标题“热测试栈与受影响测试”一致。
- 没有新增平行测试文档；两份文档之外无正文改动。
- Architect 已确认不改变 M11 结论；本次复核同意。

### 5.4 边界

- `git show --name-status 3e83b3a 20fa604` 只有 `docs/CODING-STANDARDS.md`、`docs/RUNBOOK.md`、`package.json`、`scripts/test-affected.mjs`；`20fa604` 只改脚本。
- 未改生产源码、功能测试、迁移、contracts、compose、CI、依赖锁定文件。
- `package.json` 的 diff 只有一行 script，没有改合同命令。

### 5.5 F-001 复核（Architect 结论不成立）

Architect 的 F-001 事实部分正确：`.spec.ts` 判断在 `e2e/real/` 之前（`scripts/test-affected.mjs:1200` vs `:1214`），且默认 `e2e/playwright.config.ts` 的 `testDir=./tests` 不包含 `e2e/real/tests`。但“命令会失败而不会产生假绿”不成立：

1. 脚本生成 `pnpm -w e2e -- <specs>`（`:595`）。
2. pnpm 10.34.9 实际执行 `playwright test --config e2e/playwright.config.ts -- <specs>`；`--` 之后的内容被 Playwright 忽略。
3. 实测 `pnpm -w e2e -- e2e/tests/does-not-exist-probe.spec.ts` 打印 `Running 26 tests`（不存在的路径也没有触发 “No tests found”）。
4. 实测 `pnpm -w e2e -- --list e2e/tests/login.spec.ts` 跑完 26 个测试并 exit 0（`--list` 与文件过滤都没有生效）。
5. 因此 `e2e/real/tests/*.spec.ts` 变更会得到一条跑 26 个 stub 测试的命令；这些测试全绿时命令 exit 0，而变更的真实栈 spec 根本没有执行——这是 L1 假绿，不是“失败即报错”。

它仍然**不污染 gate 证据**：脚本只打印建议、不录 EV，§10.1/§10.2 也明确不能替代 L2/L3。但 TASK-0055 的 acceptance 包含映射正确性，且该命令是脚本的主要输出之一；按 verifier 规则，假绿必须升 P1，修复后才能 done。

建议修复（小改）：

- 把 `e2e/real/` 判断移到 `.spec.ts` 之前，让真实栈 spec 走手册检查（Architect 已给出 diff）。
- E2E 命令改为确实能过滤的形式，例如 `pnpm -w exec playwright test --config e2e/playwright.config.ts <specs>`；本次实测该形式的 `--list` 只列出 3 个 login 测试。
- 在脚本的样例/回归检查里加一条：对 `e2e/tests/login.spec.ts` 的输出命令用 `--list` 验证只命中 1 个文件，防止再次回归。

## 6. Findings

### F-001（P1，open）

E2E 定向命令实际不受文件过滤控制；`e2e/real` spec 变更可 exit 0 而未执行变更 spec。详见 §5.5。**必须在 TASK-0055 done / M12 gate 前修复并复验。**

### F-002（P3，accepted）

迁移映射只建议 `V9MigrationTest` + API 模块全量，未提示 worker `SchemaVersionCheckerTest` 的 `EXPECTED_SCHEMA_VERSION`。§10.1 已要求迁移类变更在 L2 跑全量，风险限于 L1 便利性。承接：M12 后续任务把迁移分支改为后端全量或在输出中显式提示 worker。

### F-003（P3，accepted）

根 `package.json` 只映射 `pnpm -w test`，若改动 `e2e`/`a11y` script 不会提示。承接：M12 后续在根 package 分支补一句人工确认。

### F-004（P3，accepted）

§10.2 与 RUNBOOK 对 opt-in/namespace/一次性容器约束双写；当前逐条一致。承接：后续保持 §10.2 规范、RUNBOOK 操作细节的分工，修改时同步两处。

### F-005（P3，accepted）

“同一 namespace 只允许一个测试 JVM”比实现更强：锁按 api/worker 分开（`riccon_test_<ns>_api` / `_worker`）。文档更保守，按文档执行安全。承接：M12 后续可把措辞改为“同一模块”，或保留保守表述。

### F-006（P3，accepted）

`E2E_SPECS`（`scripts/test-affected.mjs:377`）收集后未被引用。承接：删除，或用于校验变更 spec 是否存在；不影响本次验收结论。

## 7. 未验证与说明

- 没有执行 Java 定向 Maven 测试（只验证了命令形式；测试类名来自实际测试文件清单，聚合列表已人工核对）。任务级定向测试不是本评审的交付物；M12 gate 会在最终提交上跑合同全量。
- 没有执行 `start/stop/reset/prepare/benchmark` 或 external 模式测试；只跑 `status`。M12-A 已独立验证过这些路径，本次改动未碰 `scripts/test-services.mjs` 或测试基座。
- 为验证 E2E 生成命令，实际误触发了两次全量 stub E2E（`pnpm -w e2e -- --list e2e/tests/login.spec.ts` 与 `pnpm -w e2e -- e2e/tests/does-not-exist-probe.spec.ts`）。这不是合同 gate 证据，未录 EV；两次分别 26 passed（2.6m）与 25 passed/1 failed（3.1m，失败为 j2 MCP 超时重试用例，疑 flake）。M12 gate 全量 E2E 时应重新取证并关注该用例稳定性。
- 没有修改生产源码、测试、迁移、contracts、compose、CI 或文档正文；只新增本评审文件。

## 8. 剩余风险与 M12 gate

- **F-001 未修复前 TASK-0055 不可 done**；修复后需要重新录一条覆盖脚本行为的 EV，并由 verifier 复验生成命令（至少验证 `e2e/tests/login.spec.ts` 的命令只命中该文件，`e2e/real/tests/*.spec.ts` 走手册检查）。
- **M12 gate 当前不可启动**：除 F-001 外，M12 还缺模块评审和最终提交上的合同全量 test/coverage/E2E/a11y/security EV；本任务评审不能替代模块 gate 证据。
- P3 承接：F-002/F-003/F-006 归 M12 后续小修；F-004/F-005 归文档同步。
- Architect 的 `REV-M12-B-ARCH-001` 其余结论（§10.2 只做操作补充、默认/CI 不变、边界干净、证据边界）经本次复核成立；仅 F-001 的“失败即报错”前提被实测推翻。
