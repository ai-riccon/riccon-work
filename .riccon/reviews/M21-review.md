---
review_id: REV-M21-001
module_id: M21
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dff639390ffeb05YaM1tN49j8B
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
head_commit: 40e969aadd4e5c2237b397e8f425024e8b5562cc
reviewed_commits:
  - 5017e1d
  - 2bee040
  - 9508fc6
  - 1434733
  - 37a5be7
  - 40e969a
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
  acceptance:
    - EV-0733
    - EV-0729
  build:
    - EV-0727
  tests:
    - EV-0729
  coverage:
    - EV-0729
  e2e:
    - EV-0728
  docs:
    - EV-0734
findings:
  - id: F-001
    severity: P3
    title: "首响应闸门依赖“首个 ChatResponse 必为首段内容”，OpenAI 客户端将来若输出空 role/finish 响应会重新引入竞态"
    status: accepted
    owner: riccon/builder-m19
    due: M22 gate 前
    accepted_reason: "当前 OpenAiCompatibleChatModel.handlePayload 只在 blocks 非空、finishReason 或 usage 存在时 sink.next，role-only 帧不会产生 ChatResponse，因此首个 doOnNext 响应确定为首段内容；FailoverChatModel.runAttempt 在 sink.next 前设置 emitted，闸门释放时降级状态机已标记首字。若将来客户端改为逐帧输出空响应，本用例会重新 flake（以 502→503 的确定性失败暴露，而非假绿），届时需把闸门改为显式 content 回调。属维护性残余，不影响本次修复成立。"
reviewed_task_reviews: []
evidence:
  - EV-0725
  - EV-0726
  - EV-0727
  - EV-0728
  - EV-0729
  - EV-0730
  - EV-0731
  - EV-0732
  - EV-0733
  - EV-0734
reviewed_evidence:
  - EV-0725
  - EV-0726
  - EV-0727
  - EV-0728
  - EV-0729
  - EV-0730
  - EV-0731
  - EV-0732
  - EV-0733
  - EV-0734
notes: "M21 为 fix M19 的合并前稳定性修复；只改 ModelFailoverIntegrationTest 的时序闸门。独立复跑 10×17/0/0/0、Worker 全量 356/0/0/0、canonical 196/1115/0/0/0、build/E2E 通过，CI run 44965051297 shard2 与 merge 报告复核通过。无 P0/P1 open；F-001 P3 accepted。"
created_at: "2026-11-22T08:48:00+08:00"
---

# REV-M21-001：M21（fix M19）ModelFailover 时序 flake 修复的模块独立评审

## 结论

**pass**。本评审独立审计 `2bee040` 的 diff、时序语义和失败历史，并在干净提交 `40e969a` 上复跑：

- `ModelFailoverIntegrationTest` 连续 **10 次 × 17 用例**，每次 `17/0/0/0`；
- Worker 全量 external **356/0/0/0**（namespace `m19v21`）；
- 合同 `commands.test` + `commands.coverage`：**196 类 / 1115 用例 / 0 failure / 0 error / 0 skip**，前端 57 文件 / 493 用例通过；后端覆盖率 api 92.41%/75.08%、worker 87.90%/70.71%、platform-common 90.83%/75.41%、platform-domain 94.74%（line/branch），均过 80/70；
- 合同 `commands.build`、`commands.test_e2e`（40 passed / 4.3m）、`spotless:check`、`git diff --check` 通过；
- CI run **44965051297 @ `9508fc6`** 全绿；重新下载 artifact 复核 shard2 `ModelFailoverIntegrationTest` 为 `tests=17 errors=0 skipped=0 failures=0 flakes=0` 且含被修用例；merge 报告 **196/1115/0/0/0**、`verdict=pass`、每类 `runs=1`、该测试类 `shard=2`。

修复真实消除了 502/503 竞态：stub 在写出首段内容后阻塞，测试消费到首个响应才放开闸门，错误帧只能在首字被降级状态机确认之后到达。断言未放宽、未删除、未 skip、未引入 `Thread.sleep`；无生产代码/迁移/`pom.xml`/CI/其他测试改动。无 P0/P1；1 个 P3 accepted（闸门对客户端响应粒度的隐含依赖）。

本评审是独立技术验收，不是 Product 预验收或用户产品验收；模块状态、任务 done、合并与用户验收由 Lead 处理。

## 1. 修复 diff 审计

`git show 2bee040` 只包含 4 个文件：`ModelFailoverIntegrationTest.java`（**31+/2-**）、`.riccon/modules/M21.json`、`.riccon/state.json`、`.riccon/tasks/TASK-0150.json`。逐项核对：

| 检查项                     | 观察值                                                                                                                        | 结论 |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ---- |
| 生产代码                   | `apps/*/src/main`、`platform/*/src/main` 零 diff                                                                              | pass |
| 迁移 / `pom.xml` / CI      | `deploy/`、`.github/`、`pom.xml`、`Flyway` 零 diff（EV-0731）                                                                 | pass |
| 其他测试 / `TestModelStub` | 只有 `ModelFailoverIntegrationTest.java` 变更；stub 零 diff，`holdAfterFirstContent/releaseContent` 为 M19/TASK-0149 既有设施 | pass |
| 断言强度                   | `assertThat` 91→91，`@Test` 17→17，方法数/用例集合不变；diff 中无任何断言行增删（`git diff … \| grep assert` 为空）           | pass |
| skip / disabled / sleep    | 该测试类无 `@Disabled`、`assume*`、`@Enabled*`、`Thread.sleep`                                                                | pass |
| 用例执行                   | surefire 该测试类 `tests=17`，与 M19 基线一致；`stream_failureAfterFirstToken_keepsContentAndDoesNotReplay` 正常执行          | pass |
| 先提交后取证               | EV-0725 `commit=2bee040`、`dirty=false`、单行命令；本评审 EV 记录在 `40e969a` 干净树                                          | pass |

## 2. 时序正确性审计（闸门与钩子）

关键代码路径：

- `ModelFailoverIntegrationTest.java:440-448`：`errorAfterChunk()` + `holdAfterFirstContent()` 后调用 `run(tenant, CHAT, primary::releaseContent)`。
- `ModelFailoverIntegrationTest.java:593-627`：新增 `run(..., Runnable afterFirstResponse)`；`doOnNext` 在**首个响应加入 results 后**用 `AtomicBoolean.compareAndSet(false, true)` 保证钩子只执行一次，随后 `releaseContent()`。
- `TestModelStub.java:144-152, 269-280`：stub 写出首个含 `content` 的 SSE 帧后 `contentGate.await(15, TimeUnit.SECONDS)`；`releaseContent()` 只做 `countDown`，非阻塞。
- `OpenAiCompatibleChatModel.java:337-364, 437-469`：SSE 逐行读取；`blocks` 为空且无 `finishReason`/`usage` 的 role-only 帧不会 `sink.next`，首个 `ChatResponse` 必为内容帧。
- `FailoverChatModel.java:400-414`：收到含可见文本的响应时先置 `emitted=true`，再 `sink.next`；`299-303` 在 `emitted` 为真时返回 `interrupted(error)`（502），不重试不重放。

因果链：stub 写出首段内容 → 客户端逐帧解析并上抛 → `FailoverChatModel` 置 `emitted=true` → 测试 `doOnNext` 收到内容后释放闸门 → stub 才写出第二个内容帧和错误帧 → 降级状态机只能走“首字后中断”分支。错误帧在物理上不可能早于首段内容被读取，**不再依赖线程调度顺序**。

挂起风险评估：

- `contentGate.await` 有 **15s 上限**，`blockLast` 有 **10s** 上限；即使测试忘记释放，最坏是 `blockLast` 超时导致断言失败（不是假绿），stub 处理线程 ≤15s 退出，`tearDown` 关闭 stub。无永久挂起。
- `CountDownLatch.countDown` 在 `doOnNext` 中同步执行；响应先于 await 到达时 latch 已打开，`await` 立即返回，无死锁分支。
- 首响应必然为内容帧（见上），钩子不会在 role/finish 帧被误触发。

## 3. 独立复跑

环境：macOS + Docker Desktop，compose 测试栈（MySQL 8.4.13 / OpenSearch / RustFS）healthy；JDK 21.0.13、Node 26、Maven Wrapper；`RICCON_TEST_SERVICES=external`、namespace `m19v21`（新建并 reset）；全量 Maven 期间未启动 Java LSP。

| 检查                 | 命令要点                                                                                          | 结果                                                          | EV                |
| -------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- | ----------------- |
| 定向稳定性           | reset namespace + `mvnw -q -T1C -pl apps/worker -am test -Dtest=ModelFailoverIntegrationTest` ×10 | 10 次均 `tests=17 failures=0 errors=0 skipped=0`              | EV-0726 / EV-0733 |
| Worker 全量          | `mvnw -q -T1C -pl apps/worker -am test`（external）                                               | `apps/worker: tests=356 failures=0 errors=0 skipped=0`，57.7s | EV-0726 / EV-0733 |
| 格式 / 空白          | `mvnw -q spotless:check`、`git diff --check`                                                      | exit 0                                                        | EV-0726 / EV-0733 |
| 合同 test + coverage | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                               | exit 0；后端 196/1115/0/0/0；前端 493/493；541.3s             | EV-0729           |
| 用例计数             | `node scripts/test-timings.mjs` 读取 EV-0729 surefire                                             | 196 类/1115 用例/0/0/0；worker 356；api 642；platform 117     | EV-0730           |
| 合同 build           | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                             | exit 0；前端生产构建成功                                      | EV-0727           |
| 合同 E2E             | `docker compose … up -d --wait && pnpm -w e2e`                                                    | 40 passed（4.3m）                                             | EV-0728           |
| 文档 no-impact       | `git diff --check` + 基线到 HEAD 的 AGENTS/架构/RUNBOOK/编码规范/contracts/deploy/CI 零 diff      | exit 0；非 `.riccon` 变更仅测试文件与 `docs/ACCEPTANCE.md`    | EV-0731 / EV-0734 |

说明：M19 的 EV-0716/0717/0718/0724 仍是 M19 交付的权威证据；M21 的模块 review 受 strict gate 约束（`dod_evidence` 只能引用本模块任务证据），因此本评审在 `TASK-0150` 下重新录入了 M21 自己的全量证据，而不是把 M19 EV 写进 `dod_evidence`。两者数值一致（见下）。EV-0726/EV-0731 是原始复跑记录；EV-0733/EV-0734 是同一验证的 gate 可解析格式复跑（gate 的掩码检测会拒绝带 `; echo` 的命令），`dod_evidence` 使用 EV-0733/EV-0734。

覆盖率（来自 EV-0729 的 JaCoCo 报告）：

| 范围                  |   Line | Branch | 门槛  | 结论 |
| --------------------- | -----: | -----: | ----- | ---- |
| apps/api              | 92.41% | 75.08% | 80/70 | pass |
| apps/worker           | 87.90% | 70.71% | 80/70 | pass |
| platform-common       | 90.83% | 75.41% | 80/70 | pass |
| platform-domain       | 94.74% | 无分支 | 80/70 | pass |
| apps/web（vitest v8） | 87.01% | 80.01% | 80/70 | pass |

## 4. CI 证据复核

| 项目                     | 观察值                                                                                                                       | 来源                    |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| run 44965051297          | `conclusion=success`，`headSha=9508fc6`                                                                                      | EV-0732 / `gh run view` |
| shard job 墙钟           | 172 / 202 / 204 / 206s（start→complete）                                                                                     | `gh run view` jobs      |
| shard2 ModelFailover XML | `tests="17" errors="0" skipped="0" failures="0" flakes="0"`；含 `stream_failureAfterFirstToken_keepsContentAndDoesNotReplay` | EV-0732 下载 artifact   |
| merge 报告               | 196 类/1115 用例/0 failure/0 error/0 skip；`verdict=pass`；每类 `runs=1`；该测试类 `shard=2`                                 | EV-0732 下载 artifact   |
| 失败历史                 | run 44963600457 @ `5017e1d` shard2 失败：`expected "RICCON-MODEL-502"`，日志 `errorCode=RICCON-MODEL-502 emitted=false`      | CI job log 复核         |

失败日志的 `emitted=false` 正是原竞态形态：错误帧先于首段内容被降级状态机看到。修复后 CI 与本地都只出现 `emitted=true` 的 502 中断路径。

代码等价性：`git diff --name-only 9508fc6 40e969a` 的非 `.riccon` 变更只有 `docs/ACCEPTANCE.md`；merge `1434733` 未带入其他源码/测试/CI 改动。CI @`9508fc6` 与本地 @`40e969a` 运行的是同一份测试代码。

## 5. AC 对账（M21 原文）

| #   | AC 原文要点                                                                                                                                                 | 结论 | 依据                                                                                                                                                                                                 |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `ModelFailoverIntegrationTest.stream_failureAfterFirstToken_keepsContentAndDoesNotReplay` 稳定通过；定向 ≥10 次、Worker 全量 356+/0/0/0、CI shard2 复跑通过 | pass | EV-0726 / EV-0733（10×17/0/0/0 + 356/0/0/0）、EV-0732（run 44965051297 shard2 17/0/0/0、merge 196/1115/0/0/0）、EV-0725                                                                              |
| 2   | 不改生产代码/迁移/`pom.xml`/其他测试；先提交再取证；证据命令单行                                                                                            | pass | 2bee040 diff 审计（31+/2- 全部在测试类）；EV-0731/EV-0734（baseline→HEAD 非 `.riccon` 仅测试 + `docs/ACCEPTANCE.md`）；EV-0725 commit=2bee040、dirty=false、单行；本评审 EV-0726..0734 均单行、clean |

## 6. DoD

| DoD        | 结论 | 证据                                                                                     |
| ---------- | ---- | ---------------------------------------------------------------------------------------- |
| acceptance | true | EV-0733、EV-0729（正文另引 EV-0726、EV-0732）                                            |
| build      | true | EV-0727                                                                                  |
| tests      | true | EV-0729（EV-0730 支持用例计数）                                                          |
| coverage   | true | EV-0729                                                                                  |
| e2e        | true | EV-0728                                                                                  |
| review     | true | 本文件 REV-M21-001                                                                       |
| docs       | true | EV-0734、EV-0731（无合同/架构/运行手册/编码规范影响；`docs/ACCEPTANCE.md` 由 Lead 更新） |
| evidence   | true | EV-0725..EV-0734，全部 clean、exit 0、非 manual                                          |

## 7. Findings

- **F-001（P3，accepted）**：首响应闸门隐含依赖“首个 `ChatResponse` 必为首段内容”。当前 `OpenAiCompatibleChatModel.handlePayload` 只在 content/finish/usage 时 `sink.next`，role-only 帧被抑制，因此成立；若未来客户端改为逐帧输出空响应，本用例会重新 flake（以 502→503 失败暴露，不会假绿），届时需把闸门改为显式 content 回调。owner `riccon/builder-m19`，due M22 gate 前。

无 P0/P1。M21 无 task review（`.riccon/reviews/tasks/` 无 TASK-0150/M21 文件），故 `reviewed_task_reviews` 为空；本模块评审覆盖任务级独立评审。

## 8. 未验证

- CI 未重跑（按任务要求以 run 44965051297 的 artifact 复核为准）。
- a11y / security / performance 未重跑：M21 只改 Worker 测试时序，无运行时/UI/端点变更；M19 的 EV-0719（a11y 15 passed）、EV-0722（trivy exit 0）、EV-0724（文档一致性）仍是权威。
- 本地未模拟更低核数或 surefire 多 fork；低核路径由 CI shard2 证明，同 M19 F-005 的前提。
- Product 预验收与用户验收不属 Verifier 职责；`docs/ACCEPTANCE.md` 已由 Lead 记录 M21 Product 预验收 pass，本评审不替代。

## 9. 剩余风险

1. **单次 CI 绿**：run 44965051297 只证明该次 shard2 调度下稳定；flake 概率无法由一次运行统计消除，10 次本地定向 + 一次 CI 是当前可执行的最大证据。若后续 CI 再出现 502/503，应优先检查 F-001 的隐含依赖。
2. **闸门超时语义**：stub 15s 上限 + `blockLast` 10s 上限保证不永久挂起；失败路径会花约 10s 并留下 stub 线程最多 15s，属可接受噪声。
3. **M19 证据复用边界**：M19 EV-0716/0717/0718/0724 仍支撑交付整体；M21 gate 只绑定本模块 EV，二者数值一致但不可互相替代。

## 10. 独立性声明

本评审由 `riccon/verifier` 在独立会话 `ses_dff639390ffeb05YaM1tN49j8B` 执行；未采信 builder/Lead 总结作为结论。先读 M21 AC、TASK-0150、`2bee040`/`9508fc6` diff、`TestModelStub`、`FailoverChatModel`、`OpenAiCompatibleChatModel`、M19 review 与任务评审，再下载 CI artifact 复核，并在干净提交 `40e969a` 上独立跑定向 10 次、Worker 全量、canonical test+coverage、build、E2E、格式与文档 no-impact。未修改生产源码、迁移、构建配置、CI 或测试源码；只写本评审、`.riccon` 证据与任务证据列表。

## 11. Gate

写完本文件后运行：

```bash
node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M21 --project .
```

2026-11-22T08:48:31+08:00 结果：**FAIL（2 errors）**，均为生命周期状态而非技术验收问题：

1. `task TASK-0150 is review, not done`：任务 done 由 Lead 在评审通过后推进；本 Verifier 不标记 done。
2. `task TASK-0150 changed files outside write_scope: docs/ACCEPTANCE.md`：任务仍为 `review`（无 `end_commit`）时，scope 检查会覆盖工作树至 HEAD，包含 Lead 在 `2bee040` 之后提交的 `docs/ACCEPTANCE.md`（M21 Product 预验收记录）；任务 done 后以 `end_commit=2bee040` 计算，范围仅含测试文件与 `.riccon`，此项应消失。

其余全部通过：合同、模块/任务存在、10 条 EV hash/归属/commit 绑定、strict evidence binding、四类合同命令、review 独立性/新鲜度/session 格式、8 项 DoD、DoD evidence 绑定、无阻塞决策、墙钟 51m/3h。唯一 warning 为历史 EV-0571 manual，与本模块无关。模块 done、合并/推送与用户验收由 Lead 处理，本评审不标记 done。
