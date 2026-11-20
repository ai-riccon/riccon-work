---
review_id: REV-TASK-0105-003
previous_review: REV-TASK-0105-002
module_id: M04
task_id: TASK-0105
reviewer:
  agent: riccon/verifier
  session: ses_e053898b1ffeLLwGmyauUPugpC
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - d2a815b50616e85e0b23dc88eb596fee0871c1c0
  - e9c57ee80d6760c0bd92eba4547d7c874b525a43
  - 1046929f1f5b61ad14210e03c0ad48650d649e2d
  - 0f62df186453a9d1c8b6b976de66443fc3212599
head_commit: 0f62df186453a9d1c8b6b976de66443fc3212599
task_end_commit: e9c57ee80d6760c0bd92eba4547d7c874b525a43
evidence:
  - EV-0550
  - EV-0557
  - EV-0558
  - EV-0560
reviewed_evidence:
  - EV-0550
  - EV-0557
  - EV-0558
  - EV-0560
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-failover"
    title: "ROUTED 空链稳定 503，无 updated_by/迁移回退；BASELINE 仍受 allowlist/key 约束"
    summary: >-
      e9c57ee 删除 updated_by/isChainConfigured/configured 回退、ModelRoute.configured 字段与迁移兼容分支；
      ROUTED 空条目/全停用/端点不允许/无 key 均 chainEmpty 503，不读目录、不建 model_invocation、无 HTTP；
      BASELINE 保留 M02 单模型但仍走 ModelEndpointGuard + 平台 key 池，空目录 503。IT-M04-12/13/14/11
      与 EV-0557 通过；EV-0560 全量再次覆盖。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelRouteResolver.java:107-149；
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:322-417；
      apps/worker/src/test/java/com/riccon/worker/support/TestDataFactory.java:586-612；
      EV-0557；EV-0560。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-failover（B10 观测）"
    due: "2026-12-04"
    title: "429 未读取 Retry-After，冷却固定 60 秒"
    summary: "ADR 默认冷却 60 秒仍生效；Retry-After 解析是优化，不阻断本次验收。按 Lead 建议 accepted。"
    evidence: "CredentialHealthService.java:154-158；ADR-0021 §5.1。"
  - id: F-003
    severity: P2
    status: fixed
    owner: "riccon/builder-failover"
    title: "成功后重置 consecutive_failures"
    summary: "UPDATE_LAST_USED 已清零 consecutive_failures；IT-M04-11 覆盖 500 失败后成功归零。"
    evidence: "apps/worker/src/main/java/com/riccon/worker/model/persistence/CredentialJdbc.java:108-121；ModelFailoverIntegrationTest.java:399-417。"
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/builder-failover + riccon/verifier（V1）"
    due: "2026-12-04"
    title: "外层降级 Flux 未传播取消，断开后可能等下一帧/超时"
    summary: "已知残余；延迟受 first-token timeout 上限，无重复输出；V1/B10 在取消场景验证。"
    evidence: "FailoverChatModel.java:193-197、360-419；ADR-0021 §5.4。"
  - id: F-005
    severity: P2
    status: accepted
    owner: "riccon/builder-failover + riccon/verifier（V1）"
    due: "2026-12-04"
    title: "同 provider 多模型共享 key 池时 INVALID key 可能被再次尝试"
    summary: "已知边界；无安全泄露，尝试数受 maxAttempts 上限；V1 补同 provider 多模型 401/冷却负例。"
    evidence: "ModelRouteResolver.java:121-139；FailoverChatModel.java:360-375、465-473。"
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-failover + riccon/builder-cost（B7）"
    due: "B7 closeout"
    title: "报表 TOOL/GENERATION 的 model_invocation 不记录 first_token_ms"
    summary: "attempt 明细已有每尝试 first_token；AC-04 聊天路径已正确。按 Lead 建议 accepted，B7 收口。"
    evidence: "AgentRunRequestedHandler.java:432-446；AgentRunResumeHandler.java:606-620、429-438。"
  - id: F-007
    severity: P2
    status: fixed
    owner: "riccon/builder-failover"
    title: "链空失败审计写入 reason=CHAIN_EMPTY"
    summary: "Worker AuditService + Worker AuditDetailsPolicy 已写/允许 reason；IT-M04-06/12 断言。API 读取侧由 TASK-0122/F-012 同步。"
    evidence: "AuditService.java:173-203；Worker AuditDetailsPolicy.java:27-31；EV-0558；EV-0560。"
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-gateway-e2e（B11a）+ riccon/builder-route（B2）"
    due: "M04 gate 前（模块级仍必须关闭）"
    title: "API 代理把 RICCON-MODEL-503 映射为员工可见稳定错误仍是 B11a/B2 待办"
    summary: >-
      任务级按 Lead 建议 accepted：B4 范围外；但 M04-AC-05 的员工稳定错误码/中文文案仍需 B11a/B2 在模块
      E2E 前修正，本任务不能把该 P2 当作模块已验收。
    evidence: "ChatService.java:640-665；ErrorCodes.java:71、110；M04-AC-05；REV-TASK-0105-002 F-008。"
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "8 类失败矩阵与 BASELINE allowlist 独立测试证据不完整"
    summary: "403/3xx/INVALID_RESPONSE/CONFIGURATION/首字后超时与 BASELINE allowlist 负例由 V1 补；IT-MODEL-103 已覆盖 429/5xx/401/超时。"
    evidence: "ModelFailoverIntegrationTest.java:91-282；application-test.yml:17；ADR-0021 §11.1。"
  - id: F-010
    severity: P3
    status: fixed
    owner: "riccon/lead"
    title: "任务证据与 baseline/end 已修正"
    summary: "TASK-0105 evidence 为 EV-0550/EV-0557，baseline=f97674e、end=e9c57ee；11 个修复文件全部在 scope，未改 API/contracts/迁移。"
    evidence: ".riccon/tasks/TASK-0105.json:30-33、43-44；git diff --name-only f97674e e9c57ee。"
  - id: F-011
    severity: P3
    status: fixed
    owner: "riccon/builder-failover"
    title: "credential_cooldown_total 近似口径已说明"
    summary: "ModelMetrics 注释明确冷却事件近似次数；F-011 关闭。"
    evidence: "ModelMetrics.java:149-159。"
  - id: F-012
    severity: P1
    status: fixed
    owner: "riccon/builder-core（TASK-0122）"
    title: "API AuditDetailsPolicy 未同步 reason，M10 审计查询/导出会 500"
    summary: >-
      TASK-0122 的 1046929 在 API AuditDetailsPolicy 的 model.invocation.failed 白名单加入 reason；
      AuditDetailsPolicyTest 正/负断言、AuditDetailsReaderTest 读取 reason、AuditQueryApiTest 200 且保留
      details.reason；其他模型动作仍拒绝。EV-0558（53 tests、clean）与 EV-0560 全量通过；本次独立用 HEAD
      编译类 jshell 复核：failed+reason ACCEPTED，completed+reason OTHER_REJECTED，F-012 关闭。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:130-134；
      apps/api/src/test/java/com/riccon/api/audit/domain/AuditDetailsPolicyTest.java；
      apps/api/src/test/java/com/riccon/api/m10/audit/AuditDetailsReaderTest.java；
      apps/api/src/test/java/com/riccon/api/m10/audit/AuditQueryApiTest.java；
      EV-0558；EV-0560。
  - id: F-013
    severity: P3
    status: accepted
    owner: "riccon/builder-failover + riccon/architect"
    due: "2026-12-04"
    title: "成功清 cooldown_until 可能抵消并发 429 冷却"
    summary: "已知边界；V1 并发 429+成功用例后由 Architect/B4 确认是否保留；不阻断本次验收。"
    evidence: "CredentialJdbc.java:108-121；ADR-0021 §5.1/§5.5。"
  - id: F-014
    severity: P2
    status: fixed
    owner: "riccon/lead"
    title: "最终提交缺 full verify/coverage"
    summary: >-
      EV-0560 在 0f62df1 上执行 `./mvnw -q -T1C verify`，dirty=false、exit 0；聚合 1034 tests、
      0 failure/error/skip（api 595、worker 336、arch 12、common 87、domain 4）；worker line 87.68%/
      branch 70.66%，API line 91.91%/branch 74.47%。F-014 关闭。
    evidence: ".riccon/evidence/TASK-0122/EV-0560.json 及日志；surefire/jacoco 报告（06:00–06:06）。"
  - id: F-015
    severity: P3
    status: accepted
    owner: "riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "IT-M04-12 只测 resolver，未覆盖 ChatStream/Report 实际空链入口"
    summary: "resolver 级真实；ChatStream/Report 入口的 503/无 invocation 断言由 V1 补。"
    evidence: "ModelFailoverIntegrationTest.java:322-362；ChatStreamService.java:213-226；AgentRunRequestedHandler.java:298-314。"
notes: >-
  最终任务级复评；不替代 M04 模块评审、Product 预验收或用户验收，不修改任务状态/不标 done。
  F-001/F-012 已关闭，无开放 P0/P1；F-002/F-004/F-005/F-006/F-008/F-009/F-013/F-015 为 accepted
  P2/P3，按 owner/到期跟进。EV-0560 是 0f62df1 上最终全量；EV-0559 的 296 个 NoClassDefFound 根因是
  Testcontainers Ryuk 连接失败（日志第 56 行起），EV-0560 重跑通过，属环境 flake，不作为代码失败。
created_at: "2026-11-21T06:12:00+08:00"
---

# REV-TASK-0105-003：TASK-0105 / M04-B4 最终复评

## 结论

**任务级 pass**。F-001（ROUTED 空链回退）与 F-012（API 审计读取未同步 reason）均已关闭；最终 HEAD
`0f62df1` 上的 EV-0560 全量 verify clean，覆盖 B4 `e9c57ee` 与 TASK-0122 `1046929`。无开放 P0/P1。
F-002/F-004/F-005/F-006/F-008/F-009/F-013/F-015 按 Lead 建议记录为 accepted P2/P3，附 owner/到期；
F-008 仍明确由 B11a/B2 在模块 gate 前承接。

## F-001 关闭证据

- `ModelRouteResolver.resolve` 已无 `updated_by`/`isChainConfigured`/迁移兼容分支；ROUTED 只读
  `model_routing_entry`，逐条做 target/endpoint guard/platform key pool，空结果抛
  `RICCON-MODEL-503`；`ModelRoute` 无 `configured` 字段。`grep apps/worker/src/main` 无回退调用。
- ROUTED 空链不读目录、无 HTTP、无 `model_invocation`；调用方在 `invocationService.start` 前解析。
- BASELINE：最早 ACTIVE 单模型 + `ModelEndpointGuard` + 平台 key 池；空目录 503。
- `TestDataFactory` 在 CHAT/REPORT 无条目时显式播种 position=1，J1/J2 不再依赖回退。
- IT-M04-12（ROUTED 无条目 503/无 HTTP/invocation=0/chain_empty+1/审计 reason）、IT-M04-13
  （BASELINE 空链可用）、IT-M04-14（BASELINE 空目录 503）、IT-M04-11（成功重置失败计数）均真实执行。
- EV-0557：`e9c57ee`、dirty=false、exit 0、worker 336 tests、0 skip；本次独立复跑
  `ModelFailoverIntegrationTest` 17 tests、0 failure/error/skip、exit 0；EV-0560 全量再次覆盖。

## F-012 关闭证据

- `1046929` 在 API `AuditDetailsPolicy` 的 `model.invocation.failed` 白名单加入 `reason`，与 Worker
  策略对称；其他模型动作不放宽。
- 新增测试：`AuditDetailsPolicyTest.modelInvocationFailedAllowsReason`（positive + completed/started
  negative）、`AuditDetailsReaderTest.read_allowsWorkerModelFailureReason` 与
  `read_rejectsReasonOnOtherModelActions`、`AuditQueryApiTest.listAudit_allowsWorkerModelFailureReason`
  （200、total=1、details.reason=CHAIN_EMPTY、emitted=false）。
- EV-0558：`1046929`、dirty=false、exit 0、53 tests、0 skip；本次独立复跑
  `AuditDetailsPolicyTest,AuditDetailsReaderTest` 18 tests、0 failure/error/skip、exit 0；jshell 复核
  failed+reason → ACCEPTED，completed+reason → OTHER_REJECTED。
- EV-0560 全量 API 595 tests、0 skip 通过，M10 审计查询/导出无回归。F-012 关闭。

## 最终全量与证据

- EV-0560：`0f62df1`、dirty=false、exit 0、`./mvnw -q -T1C verify`；聚合 **1034 tests、0 failure/error/skip**
  （api 595、worker 336、platform-architecture-tests 12、platform-common 87、platform-domain 4）。
- 覆盖率：worker line 87.68%、branch 70.66%；API line 91.91%、branch 74.47%，均满足合同 80/70。
- EV-0550（B4 d2a815b）、EV-0557（修复 e9c57ee）均 clean；EV-0558（API 修复 1046929）clean；
  EV-0560 覆盖最终 HEAD。
- EV-0559 的失败日志首因是 `Can not connect to Ryuk at localhost:50397`（Testcontainers 环境 flake），
  导致 296 个测试 `NoClassDefFound`；EV-0560 未改代码重跑通过。作为环境残余记录，不作为代码失败，
  也不引入新的 P0/P1。

## accepted P2/P3

| Finding                           | 状态                  | owner / 到期                               | 理由                                            |
| --------------------------------- | --------------------- | ------------------------------------------ | ----------------------------------------------- |
| F-002 429 Retry-After             | accepted P2           | builder-failover/B10，2026-12-04           | ADR 默认 60s 仍生效；解析属优化                 |
| F-004 取消传播                    | accepted P2           | builder-failover + V1，2026-12-04          | 延迟受 first-token 上限，无重复输出             |
| F-005 同 provider key 复用        | accepted P2           | builder-failover + V1，2026-12-04          | 边界浪费尝试，无安全泄露，maxAttempts 有界      |
| F-006 报表 first_token_ms         | accepted P2           | B4 + B7，B7 closeout                       | attempt 已有 first_token；AC-04 聊天路径正确    |
| F-008 API 503 映射                | accepted P2（任务级） | B11a + B2，M04 gate 前（模块级仍必须关闭） | B4 范围外；AC-05 由 B11a/B2 承接                |
| F-009 失败矩阵/BASELINE allowlist | accepted P3           | V1，M04 gate 前                            | IT-MODEL-103 已覆盖四类；其余由 V1 补           |
| F-013 成功清 cooldown             | accepted P3           | builder-failover/architect，2026-12-04     | 并发 429 边界，V1 确认是否保留                  |
| F-015 IT-M04-12 入口覆盖          | accepted P3           | V1，M04 gate 前                            | resolver 级真实，ChatStream/Report 入口由 V1 补 |

## 范围

- `1046929` 的 4 个文件全部在 TASK-0122 scope（API AuditDetailsPolicy + 三个测试），只改 API 审计读取
  白名单与测试；未改 Worker/契约/迁移/其他策略，未降低 fail-closed。
- `0f62df1` 只新增 TASK-0122 任务元数据。
- `e9c57ee` 的 11 个文件全部在 TASK-0105 更新后的 scope；`f97674e..e9c57ee` 无 API/contracts/迁移。
- TASK-0105 evidence 仍为 EV-0550/EV-0557；最终全量 EV-0560 属 TASK-0122，模块 review 需把
  EV-0560 与 TASK-0122 证据一起绑定/引用，避免“任务级证据只有 worker test”的误读。

## 未验证

- 未运行 M04 模块 gate 的 E2E/a11y/security/perf/migration；EV-0560 是全量 build/test/coverage，
  不替代 E2E-M04、k6、Trivy、V11 迁移回滚演练。
- F-008 的 B11a/B2 员工 503 映射、F-009/F-015 的 V1 补测、F-004/F-005/F-013 的真实客户端/并发场景
  仍未验证，按 accepted owner/到期跟进。
- 未把本任务的 `state.json`/task 状态改为 done；模块 gate 仍需模块 review、E2E 与 Product/用户验收。

## 剩余风险

- F-008 未由 B11a/B2 完成前，M04-AC-05 员工可见 503 文案仍不可达；这是模块 gate 风险，不是 B4 代码。
- F-002/F-004/F-005/F-006/F-013 是已登记 accepted 风险，需按 owner/到期跟进。
- EV-0559 的 Ryuk flake 属测试基础设施残余，B10/Ops 需关注；EV-0560 已证明本次最终提交 clean。
