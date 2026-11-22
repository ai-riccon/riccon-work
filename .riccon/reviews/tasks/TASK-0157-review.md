---
review_id: REV-TASK-0157-001
module_id: M24
task_id: TASK-0157
reviewer:
  agent: riccon/verifier
  session: ses_dfbf2307affeLCeCtViCsPIimF
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - a71793ccda16b4028e7017f5c12369a52a2d4e3c
  - a6153f965541ae7fbb29fd9a9849e3c8f50b62ed
  - 4611019c921ebd21dbe7ff895cae696a57fdb37d
head_commit: 4611019c921ebd21dbe7ff895cae696a57fdb37d
dod:
  acceptance: true
  tests: true
  evidence: true
  review: true
dod_not_applicable:
  - build
  - coverage
  - e2e
  - docs
dod_note: >-
  任务级评审只验证受影响行为；build/coverage/E2E/docs 由 M24 模块 review 的全量命令与
  既有 M04 E2E 证据承接，本任务不重复跑全量。
evidence: [EV-0794, EV-0795]
reviewed_evidence: [EV-0794, EV-0795]
findings:
  - id: F-001
    severity: P3
    status: accepted
    title: "跨进程 HTTP 轮换端点 + 活跃 Worker 流的实栈 E2E 未覆盖；轮换为测试侧 SQL 复刻"
    summary: >-
      本用例覆盖了 Worker 在真实 MySQL/真实 SSE 流下的并发行为，但轮换由测试用与 API
      `ModelCredentialService.rotate` 相同的单事务 SQL 语义自行执行，并通过 Worker 的生产
      `AuditRepository` 写审计；没有调用 API 的 HTTP 轮换端点或 `apps/api` 实现。另有两处
      非逐字复刻：测试为新 key 新插一条 `tenant_data_key`（API 轮换默认复用 active DEK），
      且未做 API 的 fingerprint 去重预检。API 侧的 HTTP 轮换/掩码/审计/并发 409 已由
      IT-MODEL-106、`ModelCredentialLifecycleApiTest.itModel106_concurrentRotationAllowsSingleWinner`、
      `M04VerifierGatewaySecurityTest.ac06_ac07`、SEC-M04-006 覆盖。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/run/InFlightCredentialRotationIntegrationTest.java:383-491、69-88；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/service/ModelCredentialService.java:267-324、538-547；
      apps/api/src/main/java/com/riccon/api/admin/model/credential/persistence/ModelCredentialJdbc.java:92-134；
      apps/api/src/test/java/com/riccon/api/admin/model/credential/ModelCredentialLifecycleApiTest.java:231-357、532-552；
      apps/api/src/test/java/com/riccon/api/security/m04/M04VerifierGatewaySecurityTest.java:251、286-305。
    owner: "riccon/builder-key / riccon/builder-gateway-e2e"
    due: "M05 gate 前（M24 closeout 时确认是否补真实栈 E2E）"
    accepted_reason: >-
      F-006 原文缺口是“真实并发流式请求未覆盖”，不是“轮换必须经 HTTP 端点”；Worker 侧在真实
      在途流与真实状态变更下的行为已由本用例直接验证，API 侧端点行为由独立 HTTP/服务级用例
      验证，两侧共用同一 MySQL/AAD/信封契约，残余组合风险有界。跨进程 E2E 主要补部署拓扑与
      连接池可见性，适合在 M05 真实栈套件扩展时承接，不应阻塞本任务关闭 F-006。
  - id: F-002
    severity: P3
    status: accepted
    title: "无明文扫描范围有限：仅最后一次模型请求体、仅 com.riccon.worker logger，DEK HEX 扫描对 ASCII 明文无效"
    summary: >-
      `assertNoPlaintextInDatabase` 覆盖 provider_credential 文本列、按 target_id 取 audit details、
      以及 tenant_data_key 的 `HEX(wrapped_dek/wrap_nonce)`；但 ASCII key 不可能出现在 HEX 输出里，
      该子断言即使 DEK 行混入明文也不会失败。模型请求体只查 `stub.lastRequest()`（第二个请求），
      日志只挂 `com.riccon.worker` 的 ListAppender（INFO+），未扫描 surefire/测试输出、非 worker
      logger 或 API HTTP 响应。正面路径已覆盖：旧/新 Authorization、模型流响应、凭据密文列与
      轮换审计 details 均断言无明文。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/run/InFlightCredentialRotationIntegrationTest.java:309-318、614-661、706-721；
      apps/worker/src/test/java/com/riccon/worker/support/TestModelStub.java:232-236、277-288。
    owner: "riccon/builder-key"
    due: "M24 closeout 前（若再次修改该测试）"
    accepted_reason: >-
      key 实际流向只有 provider_credential.ciphertext（已扫）与上游 Authorization（已正面断言），
      响应/审计/DB 已覆盖且未观测泄漏；其余是防御性增强。API 响应与页面掩码由既有
      IT-MODEL-106/M04VerifierGatewaySecurityTest 与 E2E-M04-04 承接，不构成本任务缺口。
  - id: F-003
    severity: P3
    status: accepted
    title: "docs/CODING-STANDARDS.md §10.4 security 映射未列 InFlightCredentialRotationIntegrationTest"
    summary: >-
      新类标注 `@Tag("security")` 并继承基类 `@Tag("integration")`，`-Dgroups=security` 能选中，
      默认全量也不受影响；但 §10.4 的 security 类清单没有新增该类，属文档映射漂移（同类问题见
      TASK-0142 F-003）。`docs/` 不在 TASK-0157 write_scope。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/run/InFlightCredentialRotationIntegrationTest.java:93-94；
      apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:66；
      docs/CODING-STANDARDS.md:242。
    owner: "riccon/architect"
    due: "M24 gate 前"
    accepted_reason: >-
      纯文档漂移，不改变默认执行集合、安全套件选择或断言；标签已在类上，`-Dgroups=security`
      仍可选中。由 architect 在下一次编码规范整理时补齐即可。
  - id: F-004
    severity: P3
    status: accepted
    title: "TASK-0157 绑定 EV-0792/0793/0794 三条，前两条为过期提交/重复，且 EV-0792 无 Tests run 计数"
    summary: >-
      三条 EV 均为 clean、exit 0、哈希匹配；EV-0792（a71793c）日志末尾没有 `Tests run`，
      EV-0793 在同一提交补了 surefire 计数，EV-0794 在最终提交 a6153f9 并有 `Tests run: 3`。
      gate 的 task-passing-evidence 取最新 EV，不会误判；但保留过期 EV 会让 closeout 与模块
      review 的 reviewed_evidence 选择产生歧义。
    evidence: >-
      .riccon/evidence/TASK-0157/EV-0792.json；EV-0793.json；EV-0794.json；EV-0795.json；
      .riccon/evidence/TASK-0157/EV-0792-*.log（无 Tests run）；EV-0794-*.log（Tests run: 3）。
    owner: "riccon/lead"
    due: "TASK-0157 closeout 前"
    accepted_reason: >-
      不影响本轮通过结论：三条均通过且 gate 只取最新；EV-0794 与本次独立 EV-0795 都有
      `Tests run: 3`。建议 closeout 时 task.evidence 只保留 EV-0794 + EV-0795，EV-0792/0793
      仅作历史记录留在 evidence 目录，不作为权威；模块 review 的 reviewed_evidence 必须包含
      最新 EV-0795。
created_at: "2026-11-23T00:26:58+08:00"
---

# REV-TASK-0157-001：TASK-0157 独立评审

## 结论

**pass（F-006 可在集成层关闭，带 P3 残余）**。

`1046807..a6153f9` 只新增一个 Worker 测试类及其证据/状态文件，没有生产代码、迁移或其他任务文件夹带。
用例在真实 MySQL 8.4 Testcontainers、真实 OpenSearch/RustFS 基类、真实 `ModelRouter`/`FailoverChatModel`/
`OpenAiCompatibleChatModel` 与真实本地 OpenAI 兼容 SSE 桩上，把第一条流挂在首字之后，执行单事务轮换，
再发起第二条流并释放闸门；独立复跑 `Tests run: 3, Failures: 0, Errors: 0`（EV-0795）。任务验收成立：
旧流不中断且不重放、新请求使用新 key、轮换审计/掩码/无明文均有可失败断言。F-006 原文的“真实并发流式
请求未覆盖”已补齐；跨进程 HTTP 端点实栈 E2E 属可接受残余（F-001）。

## 关键待判问题

1. **轮换路径口径**：Worker 不能依赖 `apps/api`（`ModuleBoundaryTest` 明确 api/worker 不得互相编译依赖），
   测试用 API `ModelCredentialJdbc` 的 INSERT/条件停用 SQL 语义 + 生产 `CredentialEnvelope`/
   `MasterKeyProvider`/`AuditRepository` 执行同一事务，是当前架构下可用的最近真实路径。API 端点自身的
   HTTP 轮换、并发 409、掩码与审计已由 IT-MODEL-106、`ModelCredentialLifecycleApiTest.itModel106_concurrentRotationAllowsSingleWinner`、
   `M04VerifierGatewaySecurityTest.ac06_ac07`、SEC-M04-006 覆盖。**判断：足以在集成层关闭 F-006**；
   跨服务实栈 E2E 不是关闭 F-006 的前置，列为 P3 accepted（F-001）。
2. **真实并发**：第一条流在模型桩写出首个 content 帧后被 `contentGate` 挂起（`TestModelStub.java:277-288`），
   `firstStream.isDone()==false`、`stub.requestCount()==1` 在轮换后仍成立，证明轮换发生在真实打开的 SSE 连接
   进行中，而非顺序执行；第二条流也在首字后同样挂起，进一步证明两条流并存。`@RepeatedTest(3)` 每轮唯一
   租户/provider/model，`@BeforeEach`/`@AfterEach` 均 `resetModelGatewayState()`，`@AfterEach` 新增的
   tearDown 基线恢复（a6153f9）把全局模型/路由/开关恢复到 V11 基线；无 surefire 并行配置，且 4 次记录
   运行共 12 次重复均通过，未见跨类污染或 flake。旁证（非 EV）：本地 target 还保留 00:16 一次
   `-Dtest=ModelFailoverIntegrationTest,InFlightCredentialRotationIntegrationTest` 的组合运行报告
   （17 + 3 tests，0 failures），说明 tearDown 基线恢复不会破坏相邻模型网关测试类。
3. **断言有效性**：`requestCount()==1/2`（轮换不重建旧连接、整场景只有两个上游请求）、`lastAuthorization()`
   旧/新 key、两条 attempt 的 `credential_id` 旧/新、旧行 `DISABLED`+`rotated_at`+`version=2`、新行
   `ACTIVE`+`version=1`+fingerprint、路由池只剩新 key、`texts()` 精确等于两段且各一次、终态 `SUCCEEDED`、
   无 `model.invocation.failed`、轮换审计 tenant/actor/target/outcome/details 与掩码无明文，均是对
   DB/HTTP 桩状态的直接断言而非看日志。需要诚实区分：轮换审计行由测试经 Worker `AuditRepository`
   写入，断言证明的是“该审计格式可落库且不含明文”，不是 API 审计写入器的行为（后者由
   `M04VerifierGatewaySecurityTest.ac06_ac07`/SEC-M04-006 覆盖）。即便如此，若 Worker 缓存旧 key、
   轮换时重建/中断旧连接、重放已输出内容、新 key 解密或路由选择错误，均会失败。
4. **无明文**：覆盖 `provider_credential` 的 ciphertext/fingerprint/masked_hint/label、按 target_id 的
   audit details、`tenant_data_key` 包裹 DEK、模型流响应、`lastRequest()` 请求体与 `com.riccon.worker`
   日志；未覆盖 API HTTP 响应/页面、surefire 输出、非 worker logger，且 DEK 的 `HEX()` 扫描对 ASCII
   明文无效。关键路径（密文列与 Authorization）已覆盖，列为 P3 accepted（F-002）。
5. **范围**：`git diff --name-status 1046807..a6153f9` 仅
   `InFlightCredentialRotationIntegrationTest.java`（+798）与 `.riccon` 证据/状态文件；无生产代码或迁移。
   `spotless:check` 对 `apps/worker` 通过；文件使用中文注释、`@DisplayName`，无 `sleep/@Disabled/retry`。
6. **证据卫生**：EV-0792/0793/0794 均 `dirty=false`、`exit 0`、输出哈希匹配、提交为 HEAD 祖先；EV-0792 在
   a71793c 且无 `Tests run`，EV-0793 同提交补计数，EV-0794 在最终 a6153f9 有 `Tests run: 3`。
   **权威建议：EV-0794（最终提交 builder 证据）+ EV-0795（本次 verifier 独立证据）**；EV-0792/0793
   不列为权威绑定（P3 accepted，F-004）。

## 验证了什么

- 任务 brief/验收：TASK-0157 两条 acceptance 与 M04-AC-07 原文的“新请求使用新 key；在途请求正常完成且不重复输出；
  掩码/无明文”逐条对账。
- diff 范围：`1046807..a6153f9` 文件清单；`a71793c` 仅新增测试、`a6153f9` 仅 2 行 tearDown 基线恢复。
- 生产路径阅读：`FailoverChatModel.runInTenant/runAttempt`（route 在 stream 开始时展平候选，
  `attempt.started` 记录 `credential.id`，首字后失败不重试/不重放）、`ModelRegistry.resolve(target, credential)`
  （每次尝试重新解密、不缓存明文）、`ModelCredentialJdbc` 的 INSERT/`DISABLE_FOR_ROTATION` 与
  `ModelCredentialService.rotate` 对照、`CredentialEnvelope`/`EnvelopeDecryptor` AAD 一致性。
- 独立定向运行（主工作树）：
  `./mvnw -q -T1C -pl apps/api,apps/worker -am test -Dtest='InFlightCredentialRotationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false && cat apps/worker/target/surefire-reports/com.riccon.worker.run.InFlightCredentialRotationIntegrationTest.txt`
  → exit 0，`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`，EV-0795（commit 4611019，dirty=false）。
- 格式：`./mvnw -q -pl apps/worker spotless:check` → exit 0。

## 未验证

- 未跑 M24 模块全量 `commands.test`/`commands.coverage`/`commands.test_e2e`/`a11y`/`security`；本任务改的是测试，
  生产行为未变，模块 review 需在最终提交上跑一次全量并绑定 EV。
- 未通过 API HTTP 轮换端点与独立 Worker 进程做跨服务 E2E；未覆盖“在途请求以可重试错误结束”的另一合法分支
  （本用例验证的是更强的“正常完成”分支）；“保存前页面影响说明”仍由 E2E-M04-04 承接。
- 未做故障注入/变异测试（无生产代码改动可注入）；对错误实现的失败能力基于逐条断言的静态推演。

## 剩余风险

- 若未来 Worker 改为按尝试重新解析路由/凭据或在轮换后重建上游连接，本用例能捕获；但若 API 轮换事务语义漂移
  （例如不再条件停用或改为多事务），本用例不会发现，需依赖 API 侧用例。
- 测试的 15s 模型桩闸门与 20s `blockLast` 在极端负载下有超时风险，失败会表现为用例失败而非假绿；已通过
  12 次重复未复现，暂不视为 flake。

## 操作提示（closeout）

- TASK-0157 的最新 EV 是 **EV-0795**；M24 模块 review 的 `reviewed_evidence` 必须包含 EV-0795（以及 EV-0794），
  否则 gate 的 `review_evidence` 会因“review does not cover latest evidence”失败。
- 建议把 `task.evidence` 收敛为 EV-0794 + EV-0795；本评审不修改任务状态与证据绑定，由 Lead closeout 执行。
- 本轮未运行 `riccon gate check --module M24`：M24 仍有 planned 任务且无模块 review，gate 必然失败，
  与本任务 verdict 无关；模块 gate 应在 M24 全部任务 done 后由 verifier 在全量证据上执行。
