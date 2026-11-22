---
review_id: REV-M22-001
module_id: M22
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dfedb071fffe4QF01hgScszo9p
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
head_commit: 2c690e54241eadb2df4a7cc6378bfecbd01fed7f
reviewed_commits:
  - b6bd1f6
  - fa226ce
  - e9b351b
  - ed7cf21
  - 294e164
  - 1d58f03
  - 4d5cfc5
  - 6dad1a0
  - 30215d2
  - 106655b
  - b2ddd62
  - 8a7d2c6
  - 2250d0e
  - 2d3000d
  - 2c690e5
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
  acceptance: [EV-0740, EV-0741]
  build: [EV-0744]
  tests: [EV-0747]
  coverage: [EV-0747]
  e2e: [EV-0745]
  docs: [EV-0743]
findings:
  - id: F-001
    severity: P2
    title: "前端工作空间布局单测存在既有异步竞态：部门名可能在加载完成前被同步查询"
    status: accepted
    owner: riccon/builder
    due: M05 gate 前
    accepted_reason: "M22 对 apps/web 零源码/测试改动（EV-0743 的路径断言与 git diff 均证明）。EV-0746 首次 canonical 运行中该用例失败 1 次：先 findByText('所属部门：') 立即命中加载态标签，再同步 getByText('销售部') 时部门数据尚未返回；随后同一条合同命令原样复跑 EV-0747 通过（前端 57 文件/493 用例）。定向单文件普通运行 10 次均 6/6 通过；单文件带 coverage 运行 5 次，6 个用例均通过（命令因单文件全局覆盖率阈值低于 80/70 而退出 1，属预期）。属既有测试写法在负载下暴露的竞态，不是 M22 引入，也不改变 M22 AC；已记录给前端 owner，在 M05 gate 前改为 findByText/waitFor 精确等待部门名。"
    evidence: "EV-0746（失败快照）；EV-0747（原样复跑通过）；apps/web/src/layouts/workspace/index.test.tsx:88-89"
  - id: F-002
    severity: P3
    title: "CODING-STANDARDS §10.5 的全局清理例外清单未覆盖 API 路由测试的全表清理"
    status: accepted
    owner: riccon/architect
    due: M05 gate 前
    accepted_reason: "§10.5 已明确默认单 fork、测试不得依赖执行顺序、并行化前必须逐项重验，安全约束成立；API 路由测试的 delete 属于既有全局表 writer 行为，M22 未改这些测试。当前单 fork 下全量 196/1116/0/0/0 可复现（EV-0747），风险有界。建议下次并行化/baseline 任务把 ModelRoutingApiTest 与 ModelRoutingAuditSecurityTest 的 DELETE FROM model_routing_entry 补入例外清单并保留证据，而不是在 M22 扩大改动。"
    evidence: "docs/CODING-STANDARDS.md:274-283；apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingApiTest.java:69,83,333；apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingAuditSecurityTest.java:249；EV-0747"
reviewed_task_reviews: []
evidence:
  - EV-0735
  - EV-0736
  - EV-0737
  - EV-0738
  - EV-0739
  - EV-0740
  - EV-0741
  - EV-0742
  - EV-0743
  - EV-0744
  - EV-0745
  - EV-0746
  - EV-0747
reviewed_evidence:
  - EV-0735
  - EV-0736
  - EV-0737
  - EV-0738
  - EV-0739
  - EV-0740
  - EV-0741
  - EV-0742
  - EV-0743
  - EV-0744
  - EV-0745
  - EV-0746
  - EV-0747
notes: "M22（fix M19）独立验证：CI coverage 兜底、Actions Node24、baseline EV 重建、并行/首响应语义、全限定 JaCoCo goal 均通过；本地 canonical 196 类/1116 用例/0/0/0、前端 493、build/E2E 通过；无 P0/P1，F-001 P2 与 F-002 P3 均 accepted。"
created_at: "2026-11-22T11:23:19+08:00"
---

# REV-M22-001：M22（fix M19）CI coverage、Actions、baseline 与首响应语义的模块独立评审

## 结论

**pass（附残差 F-001/F-002）**。在最终状态提交 `2c690e5`（`2d3000d..2c690e5` 仅 `.riccon` 状态差异，代码/CI/文档与 PR #2 head `2d3000d` 完全一致）上独立复核：

- 合同 `commands.test` + `commands.coverage`：后端 **196 类 / 1116 用例 / 0 failure / 0 error / 0 skip**；前端 **57 文件 / 493 用例**；后端覆盖率 api 92.41%/75.08%、worker 87.89%/70.71%、platform-common 90.69%/75.41%、platform-domain 94.59%（line/branch），前端 87.01%/80.01%，均过 80/70（EV-0747）。
- 合同 `commands.build`（EV-0744）与 `commands.test_e2e` 前端部分（40 passed / 4.2m，EV-0745）通过。
- 分片 baseline 从 EV-0737 @ `4d5cfc5` 的 196 份 surefire 报告独立复算，与 `scripts/test-shards-baseline.json` **逐类逐值完全一致**（196/1116/0/0/0、834.18s）；`--check`、4 片模拟与合并校验通过，每类恰好一次（EV-0740）。
- CI 最终 run **44976746616 @ `2d3000d`** conclusion success；`后端 canonical 覆盖率兜底` success（8m20s）、4 份 `jacoco.csv`、所有 job check-run annotations 0、日志无 `Node.js 20`；merge artifact **196/1116/0/0/0、每类 runs=1**（EV-0741）。
- 失败历史保留：run **44974295661** 的 coverage job 因 `No plugin found for prefix 'jacoco'` 失败，修复后 44975826579/44976746616 均绿；RUNBOOK 与 CI 采用全限定 `org.jacoco:jacoco-maven-plugin:report`，版本来自根 pom `jacoco.version=0.8.16`（EV-0741、EV-0743）。
- 无 P0/P1。F-001 P2 accepted（既有前端单测异步竞态，EV-0746 一次失败、EV-0747 原样复跑通过）、F-002 P3 accepted（§10.5 例外清单遗漏一处既有 API 全表清理），均写 owner/due/accepted_reason，无 open finding。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；模块状态、Product/用户验收、PR 合并由 Lead 与用户处理。

## 1. 变更范围与边界

`git diff --name-only origin/main...HEAD` 只包含：

| 区域        | 文件                                                                                         | 结论                                       |
| ----------- | -------------------------------------------------------------------------------------------- | ------------------------------------------ |
| CI          | `.github/workflows/ci.yml`                                                                   | 新增 backend-coverage、Actions 升级        |
| 文档        | `docs/RUNBOOK.md`、`docs/CODING-STANDARDS.md`（§10.5）                                       | 与实现/合同一致（EV-0743）                 |
| 分片基线    | `scripts/test-shards-baseline.json`                                                          | 196/1116、834.18s，来源 EV-0737（EV-0740） |
| Worker 测试 | `OpenAiCompatibleChatModelStreamingTest.java`（+28）、`TestModelStub.java`（+8，仅 Javadoc） | 新增 1 个回归用例，未改既有断言（EV-0747） |
| 状态        | `.riccon/**`、handoff                                                                        | 不含产品行为                               |

- `apps/*/src/main`、`platform/*/src/main`、`apps/web/src`、`pom.xml`、`contracts/`、`deploy/` **零 diff**；EV-0743 在工作树内再次断言了这些路径。
- `AGENTS.md` Project Contract 零 diff，`commands.test`/`commands.coverage` 文本仍是 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`；M22 不改变覆盖率门槛、测试集合与 DoD。

## 2. 验收标准对账（M22 原文）

| #   | AC 原文要点                                   | 结论 | 依据                                                                                                                                                                                                                                                                                                                                                    |
| --- | --------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | CI 具备 coverage/verify 兜底或书面合同口径    | pass | `.github/workflows/ci.yml:284-347` 新增 backend-coverage：external + `ci-<run_id>-cov`、`prepare`、`verify org.jacoco:jacoco-maven-plugin:report`、上传 4 份报告、失败导出日志、`down -v`；最终 run 44976746616 该 job success、4 份 jacoco.csv 均过门槛（EV-0741）。负向演练 EV-0735 日志明确出现 `Coverage checks have not been met`，不是缺失 exec。 |
| 1   | Actions 升级且 CI 绿                          | pass | checkout@v7×6、setup-java@v6×4、setup-node@v7×4、upload-artifact@v7×5、download-artifact@v8×1、pnpm/action-setup@v6×1；pnpm 在 setup-node 前；无旧主版本残留；最终 run 全部 job 无 failure/cancelled，annotations=0，日志无 Node.js 20（EV-0741、EV-0743）。                                                                                            |
| 1   | baseline 由已录 EV 全量重建                   | pass | `generated_from` 指向 `M22/TASK-0152 EV-0737 @ 4d5cfc5 external namespace m22t152 2026-11-22T09:48:26–10:01:54 ./mvnw -q -T1C clean verify jacoco:report；0 failure/0 error/0 skip`；用 EV-0737 产生的 196 份 XML 独立复算，类集合、每类 tests、每类 time、总计 196/1116/0/0/0/834.18 全部一致（EV-0740）。                                             |
| 1   | 并行/多 fork 隔离与首响应语义有验证或明确约束 | pass | `docs/CODING-STANDARDS.md` §10.5 写明 surefire 3.5.6 默认 `forkCount=1/reuseForks=true/不并行`、禁止依赖执行顺序、全局写例外、并行化前必须重跑 API/Worker 全量与标签套件；新增 `stream_defaultTextPlan_firstResponseCarriesFirstTextChunk` 锁定首响应语义并在 EV-0747 通过。F-002 记录例外清单不完整（P3 accepted，不改变约束本身）。                   |
| 2   | 不改产品 AC/API/DB/UX                         | pass | 路径级零 diff；无迁移、无 API/契约、无前端产品代码、无 pom/部署变更（EV-0743）。                                                                                                                                                                                                                                                                        |
| 2   | 先提交再取证                                  | pass | EV-0740/0741/0743/0744/0745/0747 均 `dirty=false`、commit 为对应已提交 HEAD，命令单行、非 manual；最新每任务证据均通过。                                                                                                                                                                                                                                |
| 2   | 独立 Verifier 评审                            | pass | 本文件 REV-M22-001，独立会话、独立命令；`reviewed_task_reviews: []`（M22 三个任务没有 task review 文件，模块评审覆盖任务级验证；任务不涉及 auth/权限/迁移/并发/安全/AI 工具调用等强制独立 task review 的高风险面）。                                                                                                                                    |

## 3. 任务 AC 对账

| 任务      | AC 要点                                                            | 结论 | 依据                                                                                                                                                 |
| --------- | ------------------------------------------------------------------ | ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| TASK-0151 | 恢复后端 coverage/verify 兜底，不替代 4-shard                      | pass | backend-coverage 独立 job；4-shard 仍只跑 `test -Djacoco.skip=true`；EV-0741/EV-0735。                                                               |
| TASK-0151 | 升级弃用 Actions，CI 全绿且无 Node20 告警                          | pass | 版本清单与 annotations=0、日志 grep 均通过（EV-0741、EV-0743）。                                                                                     |
| TASK-0151 | 不改产品代码/测试断言；先提交再取证；证据单行                      | pass | 零产品 diff；EV-0735/0736 单行、clean。                                                                                                              |
| TASK-0152 | baseline time 来自已录 EV 的干净全量；`--check` 通过               | pass | EV-0737 → baseline；EV-0740 独立逐值复算；`--check` 输出 196/1116/4 片互斥完整。                                                                     |
| TASK-0152 | surefire 并行/多 fork 约定与首响应语义有测试或书面约束；不弱化断言 | pass | §10.5 + 新回归用例；测试 diff 仅新增（28+8 行），既有断言零改动；Worker 类 3/0/0/0，新用例 0.006s（EV-0747）。                                       |
| TASK-0152 | 不改生产代码/迁移/pom；独立 Verifier 评审                          | pass | 零 diff；本评审。                                                                                                                                    |
| TASK-0153 | backend-coverage 使用全限定 JaCoCo goal，保留 check 与上传         | pass | `.github/workflows/ci.yml:322-334`；最终 run command 日志逐字为 `./mvnw -q -T1C verify org.jacoco:jacoco-maven-plugin:report`；4 份 CSV（EV-0741）。 |
| TASK-0153 | 最终 CI success、4 份 artifact、无 Node20；不改产品/测试/pom/合同  | pass | run 44976746616 success、coverage success、artifact 4 CSV、annotations=0、无 Node20；零相关 diff（EV-0741、EV-0743）。                               |
| TASK-0153 | RUNBOOK 同步全限定 goal 与原因；先提交后取证；证据单行             | pass | RUNBOOK:119-131 说明原因/等价性/版本来源/负向演练；EV-0739/0741/0743 单行、clean。                                                                   |

## 4. 独立复跑与数值

环境：macOS + Docker Desktop；compose 测试栈 MySQL 8.4.13/OpenSearch/RustFS healthy；JDK 21.0.13、Node 26.13.0、pnpm 10.34.9、Maven 3.10.0；全量 Maven 期间未启动 Serena/JDT LS（避免 `target/` 并发污染）。

| 检查                 | 命令要点                                                                                                                   | 结果                                                                                     | EV      |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- | ------- |
| baseline 复算 + 分片 | 解析 EV-0737 的 196 份 XML 逐值比对 + `test-shards.mjs --check` + `--simulate` + `test-merge-shards`                       | 精确匹配 196/1116/0/0/0/834.18；4 片 45/259、48/282、49/273、54/302；合并 pass，每类一次 | EV-0740 |
| 合同 test + coverage | external `m22verif2`，清空 surefire 后 `perl alarm 2400 ./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage` | exit 0；196/1116/0/0/0（api 642、worker 357、platform 117）；前端 57 文件/493；502.745s  | EV-0747 |
| 合同 build           | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                      | exit 0；前端生产构建成功                                                                 | EV-0744 |
| 合同 test_e2e 前端   | `docker compose … up -d --wait && pnpm -w e2e`                                                                             | 40 passed（4.2m）                                                                        | EV-0745 |
| CI 最终 run          | `gh run view`/`gh api`/`gh run download` 断言 run、coverage job、4 CSV、annotations、Node20、失败历史                      | 全部通过                                                                                 | EV-0741 |
| 文档/合同一致性      | YAML 解析、全限定 goal/RUNBOOK/§10.5/根 pom、Actions 版本与顺序、合同文本、零产品 diff、`git diff --check`                 | exit 0                                                                                   | EV-0743 |

首次 canonical 运行 EV-0746 在前端 workspace 布局用例失败（`销售部` 异步竞态），后端已 196/1116/0/0/0；同命令原样复跑 EV-0747 通过。该失败不删除、不计入 DoD 证据，作为 F-001 的复现快照；EV-0742 是 verifier 文档检查脚本的 awk 比较写错（拿第一处 setup-node 与 pnpm 行比较），属验证脚本自身错误，不用于任何 DoD 结论。

### 4.1 覆盖率

| 范围                  |   Line |                Branch | 门槛  | 结论 |
| --------------------- | -----: | --------------------: | ----- | ---- |
| apps/api              | 92.41% |                75.08% | 80/70 | pass |
| apps/worker           | 87.89% |                70.71% | 80/70 | pass |
| platform-common       | 90.69% |                75.41% | 80/70 | pass |
| platform-domain       | 94.59% | 无分支（报告记 100%） | 80/70 | pass |
| apps/web（vitest v8） | 87.01% |                80.01% | 80/70 | pass |

EV-0747 同时作为 tests 与 coverage 证据（DEC-0022 允许合同 test/coverage 共用同一全量命令与 EV），未重复跑第二遍。

### 4.2 用例集合与首响应回归

- 当前 196 类 = 120 api + 66 worker + 1 architecture + 5 platform-common + 4 platform-domain；1116 用例 = 642 + 357 + 12 + 87 + 18。
- `OpenAiCompatibleChatModelStreamingTest` 3 个用例（原 2 + 新增 1），全部 0/0/0；新用例 `stream_defaultTextPlan_firstResponseCarriesFirstTextChunk` 在 EV-0747 中 0.006s 通过。
- 新用例逻辑：`stub.reset()` 恢复默认文本计划（role-only → 首段文本 → 第二段 → finish → usage），`blockFirst(10s)` 后断言首个响应非空、content 非空、TextBlock 文本 `containsExactly("根据资料，")`。若客户端将来把 role-only 帧也 `sink.next`，该断言会确定性失败，而不是让 M21 的首字后失败用例重新 flake。
- 生产实现核对：`OpenAiCompatibleChatModel.handlePayload`（`apps/worker/src/main/java/com/riccon/worker/model/service/OpenAiCompatibleChatModel.java:437-469`）只在 `blocks` 非空或存在 finishReason/usage 时 `sink.next`，role-only 帧被抑制；`TestModelStub` 在首个含 `"content"` 的帧后 `contentGate.await(15s)`，`releaseContent()` 仅 countDown。
- 既有断言零删除/放宽：M22 对测试文件的 diff 只有新增 28 行（含 import）与 8 行 Javadoc；无 `@Disabled`/`assume`/`Thread.sleep`。

## 5. baseline 与分片独立复算

- `scripts/test-shards-baseline.json:2` 的 `generated_from` 完整引用 EV-0737 @ `4d5cfc5`、external `m22t152`、`2026-11-22T09:48:26+08:00–10:01:54+08:00`、`clean verify jacoco:report`、0/0/0。
- EV-0737 生成的 196 份 XML（mtime 09:48–10:01）被逐文件解析：196 类、1116 用例、0 failure、0 error、0 skip、time 合计 834.18s；与 baseline 的类集合完全相等，每类 tests 与 time 差异均为 0（EV-0740）。
- `--check`：196 类/1116 用例、4 片互斥完整、源码测试类均已登记。
- `--simulate` + `test-merge-shards`：45/259、48/282、49/273、54/302；合计 196/1116/0/0/0，`verdict=pass`，每类恰好一次。
- CI merge artifact（run 44976746616）再次独立确认：196/1116/0/0/0、`runs` 全部为 1、新测试类在 shard 3、tests=3、problems=[]。
- 观察：EV-0747 本地复跑 502.745s，低于 baseline 834.18s（约 40%）；类数/用例数完全一致。时间值受机器负载影响，只用于分片相对负载估算；`--check` 不比较时间，分片模拟仍按 baseline 得到 204–221s 的均衡分布。基线来源可复核性（M19 F-004）已关闭。

## 6. CI / Actions / artifact 复核

| 项目              | 观察值                                                                                                        | 来源                          |
| ----------------- | ------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| 最终 run          | 44976746616 @ `2d3000d`，conclusion success                                                                   | EV-0741                       |
| backend-coverage  | success，8m20s，`ci-<run_id>-cov`                                                                             | EV-0741                       |
| coverage artifact | `backend-coverage-44976746616`，4 份 `jacoco.csv`（api/worker/platform-common/platform-domain），数值均过门槛 | EV-0741                       |
| annotations       | 9 个 job 的 `annotations_count` 之和 0                                                                        | EV-0741                       |
| Node.js 20        | 全 run 日志 0 个匹配 `Node.js 20/nodejs 20/node20`                                                            | EV-0741                       |
| merge artifact    | `test-shards-merged`：196/1116/0/0/0、`runs` 全 1、`problems=[]`、`verdict=pass`                              | 本会话 `gh run download`      |
| 失败历史          | run 44974295661 @ `106655b` coverage job failure，日志 `No plugin found for prefix 'jacoco'`；未删除          | EV-0741                       |
| 修复后 run        | 44975826579 @ `2250d0e` success（EV-0739）；44976746616 @ `2d3000d` success                                   | EV-0741                       |
| PR #2             | OPEN，head `2d3000d`，base `main`，不是 draft                                                                 | 本会话 `gh pr view`           |
| 本地等价性        | `2d3000d..2c690e5` 仅 `.riccon` 状态文件；代码/CI/文档与 CI head 相同                                         | 本会话 `git diff --name-only` |

Actions 版本核对：checkout@v7（6 处）、setup-java@v6（4）、setup-node@v7（4）、upload-artifact@v7（5）、download-artifact@v8（1）、pnpm/action-setup@v6（1）；pnpm 在 frontend job 的 setup-node 前 5 行；旧主版本正则无命中（EV-0743）。

## 7. 文档与合同一致性

- `docs/RUNBOOK.md:110-131`：四后端 job 表、backend-coverage 细节、全限定 goal 原因（fresh runner prefix 元数据失败，run 44974295661）、与合同命令等价、版本来自根 pom `jacoco.version`（0.8.16，插件声明在 `build/plugins`）、报告路径与保留期、负向演练、前端 coverage 未纳入 CI 的观察。
- `docs/CODING-STANDARDS.md:274-283` §10.5：surefire 版本/默认单 fork、uniqueTenant、审计 append-only、reset helper、精确清理示例、ModelRegistry 全局写例外、并行化前重验、首响应闸门失效路径。effective-pom 实测 surefire 3.5.6，无 `forkCount/reuseForks/parallel` 配置；`TestModelStub.holdAfterFirstContent` Javadoc 与实现一致。
- `AGENTS.md` 合同文本未改；CI 全限定 goal 已由 RUNBOOK 书面说明等价性，合同仍保留本地/verifier 的 `jacoco:report`。EV-0743 全部断言通过。
- F-002：§10.5 的“其他测试不得做全表清理”表述与 `ModelRoutingApiTest`/`ModelRoutingAuditSecurityTest` 的 `DELETE FROM model_routing_entry` 例外清单不完全对齐；属文档完整性残余，不影响当前单 fork 可复现性。

## 8. DoD

| DoD        | 结论                  | 证据                                                                              |
| ---------- | --------------------- | --------------------------------------------------------------------------------- |
| acceptance | true                  | EV-0740、EV-0741（正文另引 EV-0743/0747）                                         |
| build      | true                  | EV-0744                                                                           |
| tests      | true（附 F-001 残差） | EV-0747（EV-0746 为一次前端 flake 快照）                                          |
| coverage   | true                  | EV-0747（4 后端模块 + 前端均过门槛）                                              |
| e2e        | true                  | EV-0745（40 passed）                                                              |
| review     | true                  | 本文件 REV-M22-001                                                                |
| docs       | true                  | EV-0743（RUNBOOK/§10.5/合同一致性；F-002 残余）                                   |
| evidence   | true                  | EV-0735–EV-0747，全部单行、非 manual、hash 可复核；dod_evidence 只用 M22 任务证据 |

## 9. Findings

- **F-001（P2，accepted）**：前端 `apps/web/src/layouts/workspace/index.test.tsx:88-89` 的既有异步竞态。EV-0746 首次 canonical 运行 1 次失败，EV-0747 原样复跑 493/493 通过；定向单文件普通运行 10 次均 6/6 通过，单文件带 coverage 运行 5 次 6 个用例均通过（命令因单文件全局覆盖率阈值退出 1，属预期）。M22 零前端改动；owner `riccon/builder`，due M05 gate 前；修复方向是 `findByText('销售部')`/`waitFor`，不放宽断言。
- **F-002（P3，accepted）**：§10.5 例外清单遗漏 API 路由测试的全局 `DELETE FROM model_routing_entry`；owner `riccon/architect`，due M05 gate 前；当前约束已禁止未重验并行化，风险有界。

无 P0/P1，无 open finding。

## 10. 未验证 / 明确不声称

- **未重跑前端 typecheck/lint**：M22 零前端源码改动；CI frontend job 在最终 run success（EV-0741），本轮只验证了合同 test/build/E2E。
- **未单独跑 a11y**：`pnpm -w e2e` 的 40 个用例包含 `@a11y` 标签用例；本轮 EV-0745 通过，未再单独跑 `pnpm -w a11y`。
- **未跑 security/trivy**：M22 无依赖、密钥、端点或数据变更；M19 EV-0722 仍是权威基线。
- **未跑性能**：M22 无产品/运行时变更；CI perf-m04 仅在 nightly/workflow_dispatch 运行，本次 PR run 为 skipped。
- **未做迁移 forward/rollback 演练**：M22 未改迁移，无需演练。
- **未实际开启 surefire 多 fork/并行**：§10.5 只给约束并明确“并行化前必须重验”；当前默认单 fork 是验证前提，不是已证明并行安全。
- **Product 预验收/用户验收未执行**：不属于 Verifier 职责；模块状态与合并由 Lead 处理。
- **明确不声称**：M22 消除了所有测试 flake（F-001 仍存在）；CI 总墙钟 ≤5 分钟（coverage job 约 8.5 分钟，RUNBOOK 已明确不承诺）；前端覆盖率已由 CI 强制（仍由 verifier gate 采集）。

## 11. 剩余风险

1. **F-001 前端竞态**：CI/本地 canonical 仍可能偶发失败；修复前每次失败都不应被 retry 静默吞掉。
2. **F-002 文档例外清单**：并行化前若只按 §10.5 现有清单排查，可能漏掉 API 路由测试的全局写。
3. **baseline 时间负载敏感**：834.18s（EV-0737）与本地 502.745s（EV-0747）差异约 40%；只影响分片均衡估算，`--check` 不比较时间。
4. **CI coverage 仅后端**：前端覆盖率仍由 verifier 本地 gate 执行；RUNBOOK 已标注为 M22 观察项，未扩大改动。
5. **Worker 分支覆盖率 70.71%** 仅比 70% 门槛高 0.71 个百分点，后续测试/生产变更容易触碰门槛。
6. **Surefire fork 退出噪声**：EV-0747 仍有 `Surefire is going to kill self fork JVM ... 30 seconds`，属 M19 既有现象，未计入 M22。
7. **Actions 大版本单次验证**：Node24 主版本只在本次 run 证明可用；未来 action 行为变化仍需 CI 复验。

## 12. 独立性与方法声明

本评审由 `riccon/verifier` 在独立会话 `ses_dfedb071fffe4QF01hgScszo9p` 执行；未采信 Lead/Builder 的总结作为结论。先读 M22 模块/三个任务卡、M19/M21 评审与 ACCEPTANCE 记录、CI workflow、RUNBOOK/CODING-STANDARDS §10.5、baseline 与测试 diff，再独立：

1. 用 `git diff` 核对变更面与零产品代码；
2. 从 EV-0737 的 surefire XML 逐类复算 baseline（EV-0740）；
3. 用 `gh run view`/`gh api`/artifact 下载复核 run 44976746616、失败历史 44974295661、annotations 与 Node20；
4. 在最终提交 `2c690e5` 上跑合同 test+coverage、build、E2E 与文档一致性（EV-0743/0744/0745/0747）；
5. 用 `--check`/`--simulate`/merge 独立复验分片。

未修改生产源码、迁移、构建配置、CI、测试或合同；仅写本评审与 `.riccon` 证据/任务状态。全量 Maven 期间未启动 Serena/JDT LS；M22 的验证对象是 CI YAML、脚本、文档与 Worker 测试，按项目规则用 CodeGraph 定位 `handlePayload`/`TestModelStub`，未使用 Serena 符号编辑。

## 13. Gate

写完本文件后运行：

```bash
node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M22 --project .
```

结果见回执与 `.riccon/state.json` 的 gate 记录；本评审不自行标记模块 done。

2026-11-22T11:25:03+08:00 实际结果：**PASS**。关键通过项：合同有效、M22/3 个任务均 done、13 条 M22 证据 hash/归属/commit 绑定有效、每任务最新执行证据 exit 0、strict DoD 六项（acceptance/build/tests/coverage/e2e/docs）均绑定 M22 任务证据、review 独立性/新鲜度/真实 session、无阻塞决策、墙钟 2.2h/4.0h。唯一 warning 为历史 EV-0571 manual，与本模块无关。

Gate PASS 不改变 F-001/F-002 的 accepted 状态：F-001 是既有前端单测竞态，F-002 是 §10.5 例外清单完整性；两者均有 owner/due，且不影响 M22 AC 的当前技术验收。模块 done、Product 预验收、PR 合并与用户验收由 Lead/用户处理。
