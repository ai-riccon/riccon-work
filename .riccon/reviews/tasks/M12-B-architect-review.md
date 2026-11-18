---
review_id: REV-M12-B-ARCH-001
module_id: M12
task_id: TASK-0055
reviewer:
  agent: riccon/architect
  session: ses_architect_M12_TASK-0058_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [3e83b3a, 20fa604]
head_commit: a2448f7
reviewed_evidence: [EV-0370, EV-0371]
findings:
  - id: F-001
    title: "e2e/real 的 .spec.ts 先命中定向 E2E 分支，真实栈手动分支对 spec 不可达，会给出默认配置找不到的测试路径"
    severity: P2
    status: accepted
    evidence: "scripts/test-affected.mjs analyzeE2eFile：`.spec.ts` 判断在 `e2e/real/` 之前（第 1200-1220 行）；e2e/playwright.config.ts testDir=./tests，e2e/real 由独立 playwright.real.config.ts 驱动"
  - id: F-002
    title: "迁移文件映射只给 API V9MigrationTest + API 模块全量，未覆盖 worker SchemaVersionCheckerTest 的期望版本"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs 第 859-873 行；apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java 持有 EXPECTED_SCHEMA_VERSION；§10.1 已要求迁移变更在 L2 跑全量，风险限于 L1 便利性"
  - id: F-003
    title: "根 package.json 变更只映射前端全量，不提示 e2e/a11y script 变更"
    severity: P3
    status: accepted
    evidence: "scripts/test-affected.mjs ROOT_FRONTEND_FILES 分支；package.json 同时定义 e2e/a11y/dev 脚本"
  - id: F-004
    title: "§10.2 与 RUNBOOK 约束条目重复，存在双写漂移风险"
    severity: P3
    status: accepted
    evidence: "docs/CODING-STANDARDS.md 第 206-212 行与 docs/RUNBOOK.md 第 75-81 行当前一致；后续修改需同步两处"
  - id: F-005
    title: "§10.2 的“同一 namespace 同时只允许一个测试 JVM”比实现更强：命名锁按 api/worker 分别加锁"
    severity: P3
    status: accepted
    evidence: "ExternalTestServices 锁名分别为 riccon_test_<ns>_api / _worker；数据库、S3 桶、OpenSearch 前缀也按模块派生。文档更保守，按文档执行安全"
created_at: "2026-11-19T03:33:00+08:00"
---

# M12-B 架构确认：CODING-STANDARDS §10.2 与受影响测试映射

## 1. 结论

**verdict: pass。** §10.2 只补充热栈与 `test-affected` 的操作说明，没有改写 §10.1 的分层、取证与 gate 结论；与 M12-A 的实现和 CI 默认行为一致；没有新增平行测试文档；两个提交未触碰生产源码、功能测试、迁移、契约或依赖。P0/P1 为 0，P2 一条（F-001，L1 建议命令错误、失败即报错、不影响 gate 证据）已接受并列入后续修复建议。

M12-B 可进入 verifier 评审（TASK-0059）；M12 gate 仍需 verifier 的模块评审与合同全量证据。

## 2. 审查范围与方法

- 审查提交：`3e83b3a`（脚本 + §10.2 + RUNBOOK + package script）、`20fa604`（多文件聚合修正）。
- 代码/文档对照：`docs/CODING-STANDARDS.md` §10.1/§10.2、`docs/RUNBOOK.md` 热栈小节、`scripts/test-affected.mjs`、`scripts/test-services.mjs`、`package.json`、M12-A 的 `ExternalTestServices`/`ApiIntegrationTestSupport`/`V9MigrationTest`/`SchemaVersionCheckerTest`、`.github/workflows/ci.yml`。
- 实际执行（未跑全量测试）：
  - `node --check scripts/test-affected.mjs` → 0；
  - `node scripts/test-affected.mjs --dry-run --base bcd39b8` → 0，13 个变更文件正确分类（`.riccon/**` 忽略、`docs/**` 手册检查、根 `package.json` 前端全量、脚本 `node --check`）；
  - 未知路径探针（临时未跟踪 `.tmp-probe-zone/unknown.txt`）→ 退出码 1，输出“无法识别的路径”，探针已删除；
  - `git diff --check` → 0；`contract validate --strict` → 0 errors/0 warnings；`state validate` → 0 errors/1 warning（M11 历史状态，任务允许）。

## 3. §10.2 是否只做操作补充

`git show 3e83b3a -- docs/CODING-STANDARDS.md` 为纯新增 8 行，§10.1（第 195-204 行）逐字未改。逐条核对：

| §10.2 断言                                                        | 事实                                                                                                                    | 结论 |
| ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | ---- |
| 不改 §10.1 的 L1/L2/L3、取证与 gate 结论                          | §10.1 原文未改；§10.2 开头明确服从 §10.1                                                                                | 通过 |
| 热栈显式 opt-in：`RICCON_TEST_SERVICES=external` + 唯一 namespace | `ExternalTestServices` 对未知取值直接失败，external 缺 namespace 直接失败，不回退 Testcontainers                        | 通过 |
| 同一 namespace 只允许一个测试 JVM，锁失败不得强用                 | `GET_LOCK` 立即失败并给出明确错误；实现按 api/worker 分锁，文档更保守（F-005）                                          | 通过 |
| `test:affected` 只给 L1 定向命令、不录 EV、不能替代 L2/L3         | 脚本只读 git 与测试清单、只打印建议，不执行命令、不写状态；输出页眉固定声明 L1                                          | 通过 |
| 迁移/回滚/ schema 版本测试始终一次性容器                          | `V9MigrationTest` 自建 Testcontainers；`SchemaVersionCheckerTest` external 分支自建容器；RUNBOOK 故障表含“误连热栈”处置 | 通过 |
| 默认、CI、gate 不设 `RICCON_TEST_SERVICES`                        | `.github/workflows/ci.yml` 直接 `./mvnw -q -T1C verify`，全仓仅 docs 出现该变量                                         | 通过 |
| 启动/停止/重置、namespace、故障见 RUNBOOK                         | RUNBOOK 小节名与 §10.2 引用一致，命令均存在                                                                             | 通过 |

§10.2 没有新增覆盖率、E2E 或 DoD 规则，也没有把热栈写进合同命令。

## 4. 与 §10.1、M12-A 及 RUNBOOK 的一致性

- §10.1 第 197 行已预留“可用后续的受影响测试脚本”且“不录 EV”，§10.2 是同一结论的操作展开，不是新分层。
- §10.1 第 204 行已写“热栈只用于单 agent 内层循环、默认/CI 用隔离容器、迁移用一次性容器”，§10.2 未放宽。
- RUNBOOK 中 `start/status/stop/stop --volumes/prepare --namespace/reset --namespace/benchmark` 全部在 `scripts/test-services.mjs` 有对应实现；`pnpm test:affected -- --dry-run` 可被 `parseOptions` 正确忽略 `--`。
- 没有新文档：唯一新增正文是 CODING-STANDARDS 的 8 行与 RUNBOOK 的 56 行，均引用现有路径。
- `package.json` 仅新增 `"test:affected": "node scripts/test-affected.mjs"`，未改合同命令。

## 5. test-affected 映射核查

- API：`main/java` 按 feature 匹配同包/类名测试；共享包（`common/config/web/persistence/audit/admin/error`）与入口类回退 API 模块全量；测试支撑类回退模块全量；删除的测试类回退模块全量。
- Worker：同规则，共享包为 `common/config/persistence`。
- Web：同目录同名测试 → 定向；逐级向上找最近含测试目录 → 子树测试；配置/测试支撑/删除测试 → 前端全量。
- E2E：定向 spec；共享配置/fixture → E2E 全量；`e2e/real/**` 设计为手册检查（但 F-001 使 spec 到不了该分支）。
- 文档：`docs/**` 手册检查；`.riccon/**` 明确忽略；未知路径非零退出（已实测）。
- 未知/无法定位时均打印原因，不回退为“空建议”。

多文件聚合（`20fa604`）修正了同一模块只保留第一条命令的问题：Java 按模块合并 `-Dtest=A,B`，Web/E2E 合并为一条命令，理由去重后拼接。

## 6. Findings

| ID    | 级别 | 状态     | 摘要                                                                                                                                                                                                                                                                            |
| ----- | ---- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 | P2   | accepted | `e2e/real/tests/*.spec.ts` 先命中 `.spec.ts` 分支，生成 `pnpm -w e2e -- e2e/real/tests/x.spec.ts`；默认配置 `testDir=./tests` 找不到该文件，真实栈手动分支不可达。建议把 `e2e/real/` 判断移到 `.spec.ts` 之前。仅影响 L1 建议命令，命令会失败而不会产生假绿，不影响 gate 证据。 |
| F-002 | P3   | accepted | 迁移变更只建议 `V9MigrationTest` + `apps/api` 模块全量；新增迁移会要求 worker `SchemaVersionCheckerTest` 同步 `EXPECTED_SCHEMA_VERSION`。§10.1 已要求迁移类变更在 L2 跑合同全量，建议后续把该分支改为后端全量或在输出中显式提示 worker。                                        |
| F-003 | P3   | accepted | 根 `package.json` 只映射 `pnpm -w test`；若改动 `e2e`/`a11y` script，建议输出补一句人工确认。                                                                                                                                                                                   |
| F-004 | P3   | accepted | §10.2 与 RUNBOOK 对 opt-in/namespace/一次性容器的约束双写；当前一致，后续修改需同步，建议保持 §10.2 规范、RUNBOOK 操作细节的分工。                                                                                                                                              |
| F-005 | P3   | accepted | “同一 namespace 只允许一个测试 JVM”比实现更强（锁按 api/worker 分开）；文档更保守，按文档执行安全，可保留或在后续措辞中改为“同一模块”。                                                                                                                                         |

可选修复 diff（F-001，建议在 M12 后续任务或 verifier 发现时处理，不阻塞本次确认）：

```diff
 function analyzeE2eFile(file, suggestions) {
+  if (file.startsWith("e2e/real/")) {
+    suggestions.addManual(
+      file,
+      "真实栈 E2E 编排：不进入模块 gate；按 docs/RUNBOOK.md 与 scripts/real-stack 手动运行。",
+    );
+    return;
+  }
   if (file.endsWith(".spec.ts")) {
     ...
   }
-  if (file.startsWith("e2e/real/")) {
-    suggestions.addManual(...);
-    return;
-  }
```

## 7. 架构风险判断

- **热栈默认误用**：低。默认无环境变量即 Testcontainers；错误取值/缺 namespace 直接失败；CI 未设置；§10.1/§10.2/RUNBOOK 三处明确“不能作为 gate 证据”。残余风险是操作者在本机 shell 长期 export 后忘记 unset，RUNBOOK 已给出误连排查行。
- **namespace 共用**：低。数据库、S3 桶、OpenSearch 前缀均按 namespace 派生；命名锁失败即报错，不静默复用。
- **迁移误连热栈**：低。迁移/schema 版本测试不读取 external 模式的数据源，external 分支自建容器；文档和脚本提醒均覆盖。
- **文档与脚本漂移**：低到中。§10.2 与 RUNBOOK 重复 4 条约束（F-004）；当前逐条一致。建议后续修改只改一处并同步另一处。
- **证据边界**：`test-affected` 只输出建议、不可录 EV，不存在“定向测试冒充 gate 证据”的路径。

## 8. 流程备注

TASK-0058 与 TASK-0059 的 `write_scope` 写成了 `.ricon/reviews/tasks/**`，规范目录是 `.riccon/reviews/tasks/**`。riccon 的 scope 校验会忽略 `.riccon/**`，因此不影响任务流转；但任务卡路径应更正，避免后续会话按错误路径放置评审。本次评审按项目事实源写入 `.riccon/reviews/tasks/`。

## 9. 是否可进入 verifier / M12 gate

- §10.2 与 §10.1 无冲突，可接受。
- 无需修改文档正文；F-001 为脚本 P2（L1 建议命令），建议作为 M12 内小修或后续任务处理。
- 可进入 TASK-0059 verifier 独立评审；M12 gate 仍以 verifier 的合同全量测试、覆盖率、E2E/a11y 证据为准。
