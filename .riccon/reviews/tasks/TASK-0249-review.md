---
review_id: REV-TASK-0249-001
module_id: M06
task_id: TASK-0249
reviewer:
  agent: riccon/verifier
  session: ses_deb1c8aceffeuYfUe4YiIH4Pc4
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 27c36e6469e24325f134f62e1502f1c0ccf71d46
  - 2ce72fcec0775a03f1ecd30896f3df366ca6fcb3
head_commit: b78b058c9eb3f28b1587d66f7047141c87d9b158
code_commit: 27c36e6469e24325f134f62e1502f1c0ccf71d46
evidence_commit: 2ce72fcec0775a03f1ecd30896f3df366ca6fcb3
baseline_commit: ef3f5006238f24cdb1a9b3b3c9dc90605c36b091
task_card_baseline_commit: ef3f5006
task_card_end_commit: b78b058c
review_scope: >-
  M06 T16a-F2（TASK-0249）A′ 配额锁性能修复的独立任务评审：对照 ADR-0022 §1.3-R
  检查 prepare 独立短事务/tenant S/J1-J2 行锁前 S/M06 创建只读校验后 S/M06 重试 X/
  admit 4 条原子条件 UPDATE/0 行区分 429-503/死锁与锁失败 503/锁序/幂等冲突与 429 后新事务重读/
  Research 热路径非锁定读与冲突重读；复核 EV-1142（74 个定向用例）与 EV-1143
  （PERF-M06-001）的 commit、dirty、sha256、测试报告与性能报告；在 27c36e64 代码上独立复跑
  42 个关键配额/并发/M06 准入用例并运行 apps/api spotless:check；检查 8 个 write_scope 与
  “不改 schema/迁移/契约/前端/T16a/T16b”。不评审 T16a 的真实浏览器 E2E/axe/375px、T16b、
  M06 模块 gate、Product 预验收或用户最终验收；不运行 riccon gate check。
dod:
  acceptance: true
  build: true
  tests: true
  performance: true
  review: true
  evidence: true
  scope: true
  security: true
dod_not_applicable:
  - coverage
  - e2e
  - accessibility
  - migration
  - docs
dod_note: |-
  acceptance 记 true 但按 **pass (with residual)** 解释：AC1（A′ 实现）与 AC2（50 并发/冷补/混合/
  同键/租户 10/流程 5/重试不扣配额/M02/M04 回归）由代码审查、EV-1142 与本次独立复跑支撑；
  AC3 的 write_scope、clean EV、独立 Verifier 评审已满足，但“T16a 复跑 PERF-M06-001 通过后才算
  关闭”尚未满足（EV-1143 exit 1，TASK-0237 仍 blocked），因此本评审通过不等于 TASK-0249 可以标
  done；AC4 的写路径 2588.6ms≤3000ms 通过，Studio API（列表/详情/保存/发布/历史，不含运行准入写路径）
  500ms 未单独量测（F-001 P2 accepted）。
  build 由定向测试编译与 `./mvnw -q -pl apps/api spotless:check` exit 0 支撑，未跑全量合同 build；
  tests 由 EV-1142 74 个用例与本次独立 42 个用例支撑；performance 只按 DEC-0049=A 的写路径口径
  记 true，Studio API 分位（不含准入）归 T16a 复跑；evidence 中 EV-1142 clean，EV-1143 是 exit 1 的实测报告而非
  通过证据，EV-1140/EV-1141 失败且不作为本任务通过依据；security 只做变更面代码审查
  （fail-closed、租户隔离、无新端点/数据），Trivy/滥用用例归模块 gate/release；
  coverage/e2e/accessibility/migration/docs 属 T16a、模块 gate 或 architect/lead，本任务未跑。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/verifier（T16a PERF-M06-001 复跑）"
    due: "M06 gate 前（TASK-0237 复跑）"
    title: "EV-1143 未隔离 Studio API（不含运行准入）p95；m06_api_ms 混合了准入写样本，500ms 阈值未过"
    summary: |-
      `e2e/perf/mvc-smoke.js:236-240` 仍把 `m06_api_ms` 与 `m06_run_admission_ms` 都设为
      `p(95)<500`；`m06_api_ms` 由 workflow-list/detail/history/approvals/run-detail 读、
      workflow-save/validate/publish 写与 workflow-run-admission 写混合
      （`mvc-smoke.js:729-730,844-880,936-975`）。DEC-0049=A 保留“Studio API（列表/详情/保存/
      发布/历史）500ms”，只把运行准入写路径单列 3s；`m06_api_ms` 必须排除准入才能判读 500ms。
      EV-1143 报告 `m06_api_ms` count=949、p95=2345.6ms；其中 113 个准入样本
      min=954ms、p95=2588.6ms，混合 p95 被准入样本主导。k6 日志明确：
      `thresholds on metrics 'm06_api_ms, m06_run_admission_ms' have been crossed`。
      结论：EV-1143 能证明准入写路径 2588.6ms（DEC-0049=A 的 3s 内）与 J1 51ms，
      但不能证明 M06-AC-21/DEC-0049 的“Studio API（不含运行准入写路径）p95≤500ms”；报告也没有
      按 op 的读分位（k6 summary-export 未导出 tag 子指标）。若 T16a 只把
      `m06_run_admission_ms` 改成 3s，`m06_api_ms` 仍会因混合准入样本超 500ms 而失败；必须在
      同一复跑中把 `workflow-run-admission` 从 500ms 的 `m06_api_ms` 排除（或按 op 断言），
      其余 Studio API 仍按 500ms。
      同报告的无 5xx 结论成立：`http_req_failed` 5/2686 与 5 个 429 拒绝一致，`m06_run_ok` 108/108。
    accepted_reason: |-
      本任务 diff 只改配额准入写路径与 M06 创建/重试，不修改任何 Studio API 代码；EV-1143 的
      `m06_api_ms` 中位数 109ms、J1 run detail `m04_run_detail_ms` p95=116ms（601 样本）与
      `http_req_failed` 仅 5 个 429 表明 Studio API 没有可见回归，缺的是“500ms”独立分位证据与
      脚本口径。这是性能证据/T16a 校准时序问题，不是已知 Studio API 故障；TASK-0237 已因 perf 阻塞，
      TASK-0249 AC3 也要求 T16a 复跑通过后才关闭，故接受为 P2 并在模块 gate 前追踪。
    evidence: |-
      e2e/perf/mvc-smoke.js:236-240,729-730,844-880,936-975；
      .riccon/evidence/TASK-0249-perf/perf-m06-workflow.json（metrics.m06_api_ms、m06_run_admission_ms、http_req_failed）；
      EV-1143 日志第 315 行；DEC-0049=A；ADR-0022 §1.3-R 性能预算（docs/adr/ADR-0022-m04-quota-and-cost.md:216,243）。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-b1（实现）/ riccon/architect（ADR 幂等语义）"
    due: "M06 gate 前"
    title: "M06 同键并发在并发上限边界可能把幂等重放变成 RICCON-WF-429"
    summary: |-
      `WorkflowRunService.create` 在取 S 之前做非锁定幂等读（`WorkflowRunService.java:208`），
      随后 `lockTenantShared`/`admit`/`requireAdmissionLimits`（:219-223）在 `insertRun`（:246）
      之前；外层 catch 只处理 `DuplicateKeyException`/`QuotaExceededException`（:177-183），
      不处理 `WorkflowRunRateLimitedException`（:560-568）。两个同键创建并发、先到者恰好把租户
      10 或流程 5 推到上限时，后到者会先在 admit 的 TENANT/DAILY X 上等待先到者提交，随后
      count 看到先到者已提交的运行并抛 WF-429，永远到不了唯一键冲突→新事务重读路径；同键重放
      因此可能得到 429 而不是既有运行 201。`retry` 同样在只读校验后取 tenant X（:395-396）、
      在 `markRetried` 之前 `requireAdmissionLimits`（:396-399），并发同键重试在上限边界会得到
      WF-429 而不是重放 202。旧实现把 tenant X 放在读取之前，同键并发被串行化并在重读后返回既有
      对象；这是 A′ 把 S/X 后移后的边界语义差。
      期望（ADR-0022 §1.3-R 幂等段）：同键重放应返回既有对象，不因另一笔同键请求占满并发上限而
      变成 WF-429。可在取 S/admit 后、count 前重读幂等键并以回滚+新事务返回，或捕获 WF-429 后
      走 `replayAfterConflict`；无论哪种，都不得重复扣费/审计或产生第二个对象。当前无重复对象/
      配额/审计，无超卖，429 可重试且下一次会返回既有对象。
      测试未覆盖该边界：`QuotaMixedWorkloadApiTest:113-125` 的同键阶段活跃运行=0、配额 1000；
      `WorkflowRunLifecycleApiTest:100-141` 只做顺序重试。ADR 第 226 行原本只列
      duplicate key/配额 429 的重读，未写 WF-429 边界，故需 architect 确认语义后再改。
    accepted_reason: |-
      触发需要同一幂等键的并发请求恰好落在租户 10/流程 5 的上限边界；结果是可重试的 WF-429，
      无数据损坏、无重复对象、无超卖、无重复扣费；顺序重放与主路径测试通过。A′ 的 X/S 位置符合
      ADR 第 170/173/215 行，边界语义需 architect 明确，属于可在 M06 gate 前补测/小修的 P3，
      不阻断本任务实现验收。
    evidence: |-
      apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java:177-183,208-223,246,395-399,560-568；
      apps/api/src/test/java/com/riccon/api/quota/QuotaMixedWorkloadApiTest.java:113-125；
      apps/api/src/test/java/com/riccon/api/workflow/run/WorkflowRunLifecycleApiTest.java:100-141；
      docs/adr/ADR-0022-m04-quota-and-cost.md:170,173,215,220-228。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "M06 gate 前"
    title: "并发/失败分支测试缺口：同键满额重放、M06 重试并发、prepare 锁重试"
    summary: |-
      ADR-0022 §1.3-R 测试清单（第 244-245 行）要求“同键 10 并发……配额满时仍返回既有而非 429”
      与“创建/重试并发（含跨日窗口）”，本任务测试覆盖了同键 10 并发（活跃=0、配额 1000）和
      顺序重试，但没有覆盖：
      (1) 并发配额满/并发上限边界时同键重放返回既有：顺序重放已有
          `QuotaAdmissionApiTest.replayAndLoweredLimit_keepOneChargeAndRun`（先重放再调低限额），
          但 `QuotaMixedWorkloadApiTest` 未构造满额，`WorkflowRunService.java:180-182` 的
          QuotaExceeded 重读分支在并发触发时没有 API 用例；
      (2) 并发 M06 重试（`WorkflowRunLifecycleApiTest` 只做顺序重放）；
      (3) `prepare` 冷补 `DeadlockLoserDataAccessException`/`CannotAcquireLockException` 的
          重试与耗尽（`QuotaAdmissionService.java:377-405` 无单测）；
      (4) `admitUnconditionally` 更新 0 行返回 503 的分支（`QuotaAdmissionService.java:241-266`）。
      74 个定向用例已覆盖冷/热 50 并发、混合负载、租户 10/流程 5、重试不扣配额与 M02/M04
      回归；上述是防御/边界分支，代码审查未见错误，但缺少回归保护。
    accepted_reason: |-
      主路径已有 EV-1142 74 个定向用例和本次独立复跑 42 个用例；缺口集中在并发边界与锁重试
      防御分支，未发现功能错误；模块 gate 的覆盖率/全量测试与 T16a 复跑会再覆盖。作为 P3 接受，
      在 M06 gate 前补 1-2 个用例即可关闭。
    evidence: |-
      docs/adr/ADR-0022-m04-quota-and-cost.md:244-245；
      apps/api/src/test/java/com/riccon/api/quota/QuotaMixedWorkloadApiTest.java:98-125；
      apps/api/src/test/java/com/riccon/api/workflow/run/WorkflowRunLifecycleApiTest.java:100-141；
      apps/api/src/main/java/com/riccon/api/quota/service/QuotaAdmissionService.java:241-266,377-405。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-b1"
    due: "M06 gate 前"
    title: "QuotaMixedWorkloadApiTest 注释仍写 500ms 合同预算，与 DEC-0049=A 的 3s 不一致"
    summary: |-
      `QuotaMixedWorkloadApiTest.java:36-37,169` 仍写“M06 运行准入的合同预算（p95 ≤500ms）由
      PERF-M06-001……”，但 DEC-0049=A 已把运行准入写路径改为 ≤3s（Studio API，不含运行准入，仍 500ms）；同文件
      常量 `M06_P95_SANITY_BOUND_MS=3000` 与注释矛盾。只影响可读性与口径，不影响断言
      （断言已是 3s）。
    accepted_reason: |-
      注释错误不影响测试结果；ADR/ARCH/合同/PRODUCT 已由 architect/lead 在 fdb8b661/aab2fa47
      同步，测试注释属于可随手修正的 P3，M06 gate 前处理即可。
    evidence: |-
      apps/api/src/test/java/com/riccon/api/quota/QuotaMixedWorkloadApiTest.java:36-37,61-62,169；
      DEC-0049=A；AGENTS.md:272。
evidence:
  - EV-1140
  - EV-1141
  - EV-1142
  - EV-1143
reviewed_evidence:
  - EV-1142
  - EV-1143
notes: |-
  - EV-1142：命令 `./mvnw -q -pl apps/api -am test -Dtest='QuotaAdmissionServiceTest,...'`，
    exit 0，commit=27c36e64，dirty=false，日志 sha256=1f1e8823…（与 EV JSON 一致）；surefire
    reports 17 个类合计 74 个用例（2+1+2+9+6+1+6+7+7+12+7+1+2+1+3+4+3），全部 0 失败/0 跳过，
    时间戳 05:10-05:11。
  - EV-1143：命令 `PERF_M06_SCENARIO=workflow REAL_PERF_SKIP_M04=1 REAL_PERF_DURATION=45s
    REAL_EVIDENCE_TASK=TASK-0249-perf node scripts/real-stack/run.mjs --mode=perf`，exit 1，
    commit=27c36e64，dirty=false，日志 sha256=d96a3ec4…（与 EV JSON 一致）；报告
    `perf-m06-workflow.json`：m06_run_admission_ms p95=2588.6（count=113）、m06_j1_first_token_ms
    p95=51（count=601）、m06_api_ms p95=2345.6（count=949）、http_req_failed 5/2686、
    创建 108/成功 108/失败 0/非终态 0/重复成功节点 0/自批 0/审批错配 0；fakeModel=1；
    并发 38 J1+1 J2+10 studio+1 write=50；限制 tenant=10、flow=5。k6 日志第 315 行显示
    `m06_api_ms, m06_run_admission_ms` 两个 500ms 阈值均未过；写路径 2588.6ms 按 DEC-0049=A
    接受，Studio API（不含准入）分位见 F-001。
  - EV-1140（exit 1）是 platform-domain 覆盖率 0.59<0.80，已由 TASK-0250/f1bee5a6/EV-1144 修复；
    EV-1141（exit 1，1040 用例/1 failure/38 errors）主要是 connector context 的 `local-rustfs`
    端点/pepper 环境配置失败、AuthFlowTest 150s 超时，以及一个未被本 diff 触及的
    `WorkflowRunHistoryConcurrencyApiTest.concurrentCancelAndDecide_consistentOutcome` 竞态
    （期望 cancel 202、实际 decide 先赢导致 409）；两者均不作为本任务通过证据；模块 gate 必须在
    干净环境重跑全量。
  - 独立复跑：在 27c36e64 代码（HEAD b78b058c 相对该提交 `apps/api/src/main apps/api/src/test
    e2e scripts` 为空；仅 ADR/ARCH/PRODUCT/AGENTS/评审/.riccon 文档提交）运行
    `./mvnw -q -pl apps/api -am test -Dtest='QuotaConcurrencyApiTest,QuotaMixedWorkloadApiTest,
    QuotaAdmissionServiceTest,WorkflowRunAdmissionApiTest,WorkflowRunLifecycleApiTest,
    ChatQuotaAdmissionTest,QuotaAdmissionApiTest,ResearchTaskApiTest,QuotaAdminApiTest,
    QuotaStoreFailureApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`，
    exit 0，42 个用例/0 失败；`-Djacoco.skip=true` 只为缩短定向复跑时间，未改变测试选择/断言，
    覆盖率归模块 gate；日志在临时目录 `task0249-verifier-tests.log`，不作为 EV。
  - 同一工作树运行 `./mvnw -q -pl apps/api spotless:check`，exit 0。
  - write_scope 核对：27c36e64 的 15 个文件全部匹配 8 个声明 glob（quota/**、workflow/run/**、
    chat/**、research/** 的 main/test），未出现 contracts、schema/迁移、前端、T16a/T16b 路径；
    ADR/ARCH/PRODUCT/AGENTS 的 DEC-0049 同步在代码提交之后由 architect/lead 完成。
  - 按任务要求未运行 `riccon gate check --module M06`；本评审不新增 EV、不改源码/测试/契约/
    PRODUCT/AGENTS/.riccon 状态；除本评审文件外无代码改动。
created_at: "2026-11-26T07:06:00+08:00"
---

# REV-TASK-0249-001：M06 T16a-F2 配额锁 A′ 独立评审

## 结论

**pass（实现验收通过，带 residual）**。A′ 的代码实现与 ADR-0022 §1.3-R 的算法、锁序、事务边界和失败语义一致；EV-1142 的 74 个定向用例与本次独立复跑的 42 个关键用例均通过；EV-1143 的写路径 p95 2588.6ms 在 DEC-0049=A 的 3s 预算内，J1 51ms，无 5xx、无重复对象/节点/审计。

但本评审不等于 TASK-0249 可以关闭，也不等于 M06-AC-21 已全量通过：

- **AC3 的关闭条件未满足**：EV-1143 exit 1（旧 500ms 阈值下 `m06_api_ms` 与 `m06_run_admission_ms` 均未过），TASK-0237 仍 blocked；必须按 DEC-0049=A 的口径由 T16a 复跑 PERF-M06-001 并产出通过证据后，才能把 TASK-0249 标 done。
- **AC4 的 Studio API（不含运行准入）分支未单独证明**：EV-1143 的 `m06_api_ms` 混合了准入写样本，p95 2345.6ms 不能代表 Studio API；F-001 P2 accepted，T16a 复跑需按 op 排除准入并记录其余 Studio API 分位。
- **F-002/F-003/F-004** 是 A′ 后移 S/X 带来的同键并发边界语义、测试缺口和测试注释 P3，均有 owner/due/accepted_reason，作为 M06 gate 前的残余跟踪。

本评审只做独立技术验收，不替代 M06 模块 review、Product 预验收或用户最终验收；按任务要求未运行 `gate check`。

## 验证了什么

### A′ 正确性（对照 ADR-0022 §1.3-R）

| 检查                                 | 结论                                                                                                                                                                   | 证据                                                                                                                                                      |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `prepare` 在业务事务之外、独立短事务 | 通过：`TransactionSynchronizationManager.isActualTransactionActive()` fail-fast；`TransactionTemplate(REQUIRES_NEW)` 仅用于补行，调用方都在业务事务前调用              | `QuotaAdmissionService.java:135-147,377-405`；`ChatSubmissionService.java:170-173`；`ResearchTaskService.java:217-230`；`WorkflowRunService.java:170-175` |
| 缺失行唯一种子、X 锁内重查           | 通过：短事务先 `lockTenantExclusive`，再 `usageKeys` 重查，只对仍缺失行 ODKU；不清零/不改计数                                                                          | `QuotaAdmissionService.java:414-427`；`QuotaJdbc.java:86-94`                                                                                              |
| 业务事务 tenant S                    | 通过：J1/J2 在会话行/幂等业务读之前取 S；M06 创建在定义/版本/范围只读校验后、`admit` 前取 S                                                                            | `QuotaAdmissionService.java:158-161`；`ChatSubmissionService.java:201`；`ResearchTaskService.java:266`；`WorkflowRunService.java:208-223`                 |
| `admit` 只做 4 条原子 UPDATE         | 通过：`TENANT/DAILY → USER/DAILY → TENANT/MONTHLY → USER/MONTHLY`，条件更新 `affectedRows` 为唯一判据；不取 tenant 锁、不补行                                          | `QuotaAdmissionService.java:189-226,241-320`；`QuotaJdbc.java:96-117`                                                                                     |
| 0 行区分 429/503                     | 通过：条件更新 0 行先 `findUsed`；行存在→429，行缺失→503；无条件更新 0 行→503                                                                                          | `QuotaAdmissionService.java:295-317,248-257`；`QuotaAdmissionServiceTest.java:63-87`                                                                      |
| 死锁/锁失败 503                      | 通过：`admit` 捕 `DeadlockLoserDataAccessException`/`CannotAcquireLockException`→`QuotaUnavailableException`；`prepare` 仅对锁类异常做最多 2 次 10-50ms 抖动重试后 503 | `QuotaAdmissionService.java:212-225,377-405,483-507`                                                                                                      |
| enforcement=false/不限               | 通过：关闭执行或 `limit=null` 走无条件累加，仍计数并记录超限观察，不抛 429                                                                                             | `QuotaAdmissionService.java:194-210,241-266`                                                                                                              |
| 锁序                                 | 通过：tenant S/X → 业务行 → 配额 4 行；`admit` 内不再申请 tenant 锁；`retry` X 在只读校验后、count/update 前                                                           | `WorkflowRunService.java:219-223,393-399`；`QuotaAdmissionService.java:218-226`                                                                           |
| M06 重试 X                           | 通过：`lockTenantExclusive` 在 `findById`/节点/版本校验之后、`requireAdmissionLimits` 之前；重试不调 `admit`                                                           | `WorkflowRunService.java:362-419`；`WorkflowRunLifecycleApiTest.java:100-141`                                                                             |
| 幂等冲突/429 后新事务重读            | 通过（主路径）：Research/M06 捕获 `DuplicateKeyException`/`QuotaExceededException` 后用新事务按幂等键重读；命中返回既有，未命中抛原异常；Chat 由 conversation 行串行   | `ResearchTaskService.java:203-238,350-374`；`WorkflowRunService.java:159-184,277-290`；`ChatSubmissionService.java:201-206`                               |
| Research 热路径非锁定读              | 通过：热路径 `FIND_BY_IDEMPOTENCY` 去掉了 `FOR UPDATE`，仅冲突回滚后的新事务使用 `FIND_BY_IDEMPOTENCY_FOR_UPDATE`，避免缺失键间隙锁与插入意向锁交叉死锁                | `ResearchTaskJdbc.java:104-113,243-280`                                                                                                                   |

### 调用方

- **J1**：`ChatSubmissionService.prepare` 先做会话可见性读取（非锁定），再 `prepare`，然后 `TransactionTemplate` 内 `lockTenantShared` → `findByIdForUpdate`（会话行）→ 幂等检查/附件 → `admit` → 消息/run/审计。S 早于业务行锁，符合 ADR。
- **J2**：`ResearchTaskService.create` 先 workspace 可见性，再 `prepare`，事务内 `lockTenantShared` → 非锁定幂等读 → 工具授权 → `admit` → task/run/outbox/audit；同键冲突或配额 429 后新事务 `findByIdempotencyKeyForUpdate` 重读。
- **M06 创建**：`prepare` 在事务外；事务内先做幂等/定义/版本/范围只读校验，再 `lockTenantShared` → `admit` → `requireAdmissionLimits`（count 在配额行 X 之后）→ 插入运行/审计；`TENANT/DAILY` 行 X 串行 count+insert，租户 10/流程 5 不超卖。
- **M06 重试**：只读校验后取 tenant X，`requireAdmissionLimits` → `markRetried` → 节点 attempt/下游恢复/审计；重试不扣配额。
- **Research 热路径**：非锁定读 + 冲突后新事务当前读，修复了 b1 发现的间隙锁死锁面；本次 `QuotaMixedWorkloadApiTest` 的 J2 同键 10 并发与独立复跑未出现死锁。

### 测试

- **EV-1142（clean）**：commit 27c36e64、dirty=false、exit 0、74 个定向用例 0 失败/0 跳过；surefire 报告合计 74（QuotaConcurrency 2、QuotaMixedWorkload 1、QuotaAdmissionService 2、WorkflowRunAdmission 9、WorkflowRunLifecycle 6、ChatQuotaAdmission 1、QuotaAdmissionApi 6、ResearchTaskApi 7、QuotaAdmin 7、ChatModelUnavailable 12、CitationStatus 7、QuotaAuditFailClosed 1、QuotaAuditEventNull 2、QuotaStoreFailure 1、WorkflowRunAuditFailClosed 3、WorkflowRunScope 4、WorkflowRunFeatureDisabled 3）。
- **50 并发热/冷**：`QuotaConcurrencyApiTest` 两用例覆盖剩余 1 次时 1×201/49×429/0×5xx，冷启动 `quotaRowCount=4`、四窗口 `used_count=1`，无死锁。
- **混合负载/同键**：`QuotaMixedWorkloadApiTest` 覆盖 J2 同键 10 并发（1 任务/1 配额/全 201）、M06 同键 10 并发（1 运行/1 配额/全 201）、39 J1+1 J2+10 M06 的 50 VU 突发，各 scope 计数=成功数、无 5xx、同键 1 对象/1 配额。
- **M06 并发/重试**：`WorkflowRunAdmissionApiTest.createRun_parallelAdmissionLimits_areExact` 两波 10/5 并发，单流程 5、租户 10 精确，第三流程 429 且配额/审计回滚；`WorkflowRunLifecycleApiTest` 重试新增 attempt、下游恢复、顺序重放幂等、重试不扣配额、重试恢复后并发超限 429。
- **M02/M04 回归**：EV-1142 中的 ChatQuotaAdmission、ResearchTaskApi、QuotaAdmissionApi、QuotaAdmin、ChatModelUnavailable、CitationStatus 等通过；本次独立复跑同样通过。
- **独立复跑**：42 个用例（10 个类）在 27c36e64 代码上 exit 0；命令和 `-Djacoco.skip=true` 的理由见 front matter notes；未使用 `--no-file-parallelism`、未重试、未跳过断言。

### 性能（EV-1143，PERF-M06-001）

| 指标             | 观察                                                                                                           | 对 DEC-0049=A                 | 结论                                 |
| ---------------- | -------------------------------------------------------------------------------------------------------------- | ----------------------------- | ------------------------------------ |
| M06 运行准入 p95 | 2588.6ms（count=113，min 954，med 2313，max 2748）                                                             | 写路径 ≤3000ms                | **通过**（余量约 411.4ms）           |
| J1 首字 p95      | 51ms（count=601）                                                                                              | J1 首字 ≤3000ms               | **通过**（模型首字口径，见剩余风险） |
| m06_api_ms       | 2345.6ms（count=949，113 个准入样本主导）                                                                      | Studio API ≤500ms（不含准入） | **不能据此判读**；F-001              |
| http_req_failed  | 5/2686，与 5 个 429 拒绝一致                                                                                   | 无 5xx                        | **通过**（0×5xx）                    |
| 运行完整性       | 创建 108/成功 108/失败 0/非终态 0；重复成功节点 0；自批 0；审批错配 0                                          | 无重复执行/状态丢失/审批错配  | **通过**                             |
| 环境记录         | fakeModel=1；并发 38 J1+1 J2+10 studio+1 write=50；tenant=10、flow=5；duration 45s；报告含 host/limits/k6 版本 | 报告记录假模型/并发/限制/参数 | **通过**                             |

EV-1143 的 exit 1 是旧 500ms 阈值（`m06_api_ms` 与 `m06_run_admission_ms` 均被 k6 报告 crossing）造成；按 DEC-0049=A，写路径 2588.6ms 接受，R-M06-PERF-01 保留为 residual（基线 3394.4ms，降幅约 23.7%），M06b/M09 再优化；不声称 Studio API（不含准入）500ms 已通过（F-001）。

### 证据与范围

- EV-1142/EV-1143 的 commit、dirty、sha256 与 JSON 一致；EV-1142 日志在 05:10-05:11 产生，surefire 报告合计 74。
- 27c36e64 的 15 个文件全部落在 8 个 write_scope glob 内；`git diff 27c36e64..HEAD -- apps/api/src/main apps/api/src/test e2e scripts` 为空；未改 contracts/schema/迁移/前端/T16a/T16b。
- ADR-0022 §1.3-R、ARCHITECTURE §B6/§性能、PRODUCT AC-21、AGENTS performance 已在后续 fdb8b661/aab2fa47 同步 DEC-0049=A；测试脚本阈值尚未同步（F-001，T16a）。
- 独立复跑与 spotless 的临时日志不在 `.riccon/evidence`，未新增 EV；按任务要求未运行 gate check。

## AC 对账

| AC  | 原文要点                                                                                                                     | 结论                                                                                                                               | 依据                                                                          |
| --- | ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| AC1 | prepare 独立短事务/tenant S/admit 4 条 UPDATE/M06 重试 X/锁序/死锁 503/幂等重读/不改 schema 迁移契约                         | pass                                                                                                                               | 代码审查 10 项；EV-1142；无 schema/契约 diff                                  |
| AC2 | IT-QUOTA-106/107 50 并发不超卖/冷补无死锁；M06 写路径 p95≤3s、J1≤3s、无 5xx/重复扣费；IT-WF-109 租户 10/流程 5；M02/M04 回归 | pass                                                                                                                               | EV-1142 74 用例；独立复跑 42 用例；EV-1143 写路径 2588.6/J1 51/无 5xx/108-108 |
| AC3 | 只改写范围；不改 contracts/PRODUCT/AGENTS/V15/T16a/T16b；高风险独立评审；clean EV；T16a 复跑通过后才关闭                     | **pass (with residual)**：scope/clean EV/独立评审已满足；T16a 复跑**未**完成（EV-1143 exit 1，TASK-0237 blocked），任务不得标 done | 27c36e64 文件清单；EV-1142；本评审；EV-1143                                   |
| AC4 | DEC-0049=A：Studio API（不含运行准入）≤500ms；写路径≤3s；实测 2588.6；R-M06-PERF-01 residual                                 | **pass (with residual)**：写路径 pass；Studio API 未单独量测，F-001 P2 accepted；residual 已记录                                   | EV-1143；F-001；ADR-0022:216,243                                              |

本任务是性能/并发修复，无新增用户界面；用户可见效果是 M06 运行准入响应时间与限额提示不变，由 T16a 的 E2E/PERF 覆盖。AC3 的 T16a 关闭条件、AC4 的 Studio API 分支均不能由本评审替代。

## 未验证

- **T16a 复跑 PERF-M06-001（新口径）**：EV-1143 是旧 500ms 阈值下的 exit 1；TASK-0237 仍 blocked；未产生按 DEC-0049=A 的通过 EV。
- **Studio API（不含准入）独立 p95**：`m06_api_ms` 混合了准入写样本，报告无按 op 分位；F-001。
- **全量 apps/api 合同测试**：EV-1141 exit 1（1040 用例/1 failure/38 errors；connector context `local-rustfs`/pepper 环境配置、AuthFlowTest 150s 超时、一个既有 cancel/decide 竞态），未作为本任务证据；模块 gate 必须在干净环境重跑。
- **覆盖率/jacoco**：EV-1141 在测试失败后未产出可用的 apps/api 覆盖率；EV-1140 的 platform-domain 覆盖率失败已由 TASK-0250 修复；本任务的新分支覆盖率归模块 gate（F-003 已列测试缺口）。
- **E2E/a11y/axe/375px**：T16a 负责；本任务无 UI 变更。
- **Trivy/security、迁移/回滚**：无新端点/数据/迁移；模块 gate/release。
- **Product 预验收/用户最终验收**：不属于本任务评审。
- **J1 用户端到端首字**：报告中的 `m06_j1_first_token_ms` 是 run 详情 modelInvocation 首字，不含 SSE submit TTFB；EV-1143 的 `m04_chat_submit_ttfb_ms` p95=2746ms，与 51ms 相加约 2.8s，仍≤3s 但余量小，T16a 报告应写明口径。

## Findings

见 front matter F-001..F-004：

- **F-001（P2 accepted）**：EV-1143 未隔离 Studio API（不含准入）p95；`m06_api_ms` 混合准入写样本，500ms 阈值未过；T16a 复跑需排除准入并记录其余 Studio API 分位。
- **F-002（P3 accepted）**：M06 同键并发在租户/流程并发上限边界可能把幂等重放变成 WF-429（create 201 / retry 202 的边界语义），无数据损坏/超卖；需 architect 确认后小修。
- **F-003（P3 accepted）**：同键满额重放、并发 M06 重试、prepare 锁重试、admit 无条件 0 行 503 的测试缺口。
- **F-004（P3 accepted）**：`QuotaMixedWorkloadApiTest` 注释仍写 500ms 合同预算，与 DEC-0049=A 的 3s 不一致。

## 剩余风险

1. **EV-1143 单次 45s、主机 load average 3.93/6.86/7.48**：写路径 2588.6ms 余量仅约 411ms；更慢主机/更高并发可能超 3s。R-M06-PERF-01 保留，M06b/M09 再优化；若未来 >3s，按 ADR 第 216 行暂停并另立 ADR/用户决策，不得静默拆事务。
2. **Studio API（不含准入）500ms 证据缺口（F-001）**：T16a 复跑若只改 admission 阈值，`m06_api_ms` 仍会失败；必须把 `workflow-run-admission` 从 500ms 指标排除。
3. **同键并发上限边界（F-002）**：可能得到可重试的 WF-429；无重复扣费/对象，下一次重试返回既有。
4. **全量套件未在干净环境复跑**：EV-1141 的失败里有环境配置和既有 cancel/decide 竞态；模块 gate 需重跑，避免把环境问题误判为 M06 回归。
5. **J1 首字口径**：模型首字 51ms 不等于用户端到端首字；合并 submit TTFB 约 2.8s，T16a 报告需明确。
6. **无 P0/P1**：A′ 的核心正确性、限额不超卖、无死锁/无 5xx、幂等主路径均有证据；上述风险均有 owner/due。

## 下一步

1. **T16a / TASK-0237**：把 `e2e/perf/mvc-smoke.js` 的 `m06_run_admission_ms` 阈值改为 3000ms，并把 `workflow-run-admission` 从 `m06_api_ms` 的 500ms 阈值排除（其余 Studio API 保持 500ms，准入写单列 3s）或为各 op 增加独立 trend/threshold；按 DEC-0049=A 复跑 PERF-M06-001，产出通过 EV，替换 EV-1143 的失败状态。关闭 F-001。
2. **builder-b1 / architect**：确认 F-002 的同键并发上限语义；在 `WorkflowRunService.create`/`retry` 增加 WF-429 后的重读/回滚路径或明确接受；补 F-003 的边界用例；修正 F-004 注释。均在 M06 gate 前。
3. **Verifier（模块级）**：在干净环境重跑 apps/api 全量合同测试与覆盖率，复核 EV-1141 的环境/既有竞态问题，绑定模块 review 的全量 EV；不把 EV-1142 的定向 EV 当模块 tests/coverage 证据。
4. **Lead**：在 T16a 复跑通过前保持 TASK-0249 为 in_progress/blocked，不标 done；本评审的 pass 只表示 A′ 实现验收通过。
