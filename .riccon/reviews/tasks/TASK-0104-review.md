---
review_id: REV-TASK-0104-001
module_id: M04
task_id: TASK-0104
reviewer:
  agent: riccon/verifier
  session: ses_e05aaad98ffeC6mrcbk7wHO28R
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 9b8ab4cf7e77d1751af72cba1b25932ff81f2dfc
  - d67582cf0577aaf5652ee136b8bc275aa1aa1a63
head_commit: 84e78c9a535bcac2602e4a4be061b7d006e4c5b6
task_end_commit: d67582cf0577aaf5652ee136b8bc275aa1aa1a63
evidence_commit: f9bff57b5b682ab7a41f87224775eb7edd269ee3
evidence: [EV-0546]
reviewed_evidence: [EV-0546]
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/lead（closeout 重绑 baseline/end）"
    due: "TASK-0104 done / M04 gate 前"
    title: "gate 按 baseline..HEAD 计算写范围，把 B5/B6 提交算作 TASK-0104 越界"
    summary: >-
      2026-11-21T03:05:19+08:00 运行 `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04`
      仍为 FAIL；TASK-0104 行 `changed files outside write_scope` 同时列出 B5 的
      `apps/api/src/test/java/com/riccon/api/admin/model/credential/ModelCredentialLifecycleApiTest.java`
      （5f9b37c，位于 0638626..d67582c）和 B6 的 quota/chat/research 文件（f9bff57，位于 d67582c..HEAD 84e78c9）。
      实际 B3 提交干净：9b8ab4c 的 19 个文件全部在 routing 写范围内，d67582c 只新增
      `ModelRoutingServiceRaceTest.java`；`git diff --name-only 0638626 9b8ab4c` 无范围外文件，
      `git diff --name-only d67582c f9bff57 -- <routing 路径>` 为空。这是 gate 区间/元数据问题，不是 B3
      代码越界；Lead closeout 需把 baseline/end 绑定到 9b8ab4c..d67582c（或 reviewed_commits）后重跑 gate。
      当前 gate 还包含 TASK-0104 review、其他任务 planned/review 与无模块 review，这些是模块未完成时的
      预期失败，不是本任务技术验收失败。
    evidence: >-
      `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04`（2026-11-21T03:05:19+08:00，
      exit 1）；`git show --stat 9b8ab4c`（19 个文件，全部 routing）；`git show --stat d67582c`
      （仅 ModelRoutingServiceRaceTest）；`git diff --name-only 0638626 d67582c`（21 个文件：20 个 B3 +
      ModelCredentialLifecycleApiTest）；`git diff --name-only d67582c f9bff57 -- apps/api/src/main/java/com/riccon/api/admin/model/routing apps/api/src/main/java/com/riccon/api/model/routing apps/api/src/test/java/com/riccon/api/admin/model/routing`（空）。
  - id: F-002
    severity: P3
    status: open
    owner: "riccon/architect + riccon/lead"
    due: "M04 gate / 契约同步"
    title: "409 只在 detail 文本返回 currentVersion，不是机器可读字段；ADR §4.3 与契约的 ProblemDetail 不一致"
    summary: >-
      ADR-0021 §4.3 要求版本冲突时“返回当前 version”。实现 `RoutingProblems.versionConflict` 只在
      `detail` 写 `routing version conflict; current version is N`，并给出 `field=version`、
      `reason=VERSION_CONFLICT`；因为 `contracts/openapi.yaml` 的 ProblemDetail 是
      `additionalProperties: false` 且没有 currentVersion 字段。客户端仍可通过 GET 刷新，但 ADR 的
      “返回当前 version”缺少稳定字段，前端/契约无法直接消费。本任务 6 条验收只要求 409，故不阻塞
      pass；建议 Architect 在 ADR/契约中明确“detail 文本”语义，或补一个兼容的机器可读字段（补字段需
      B2 契约变更与前端同步）。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/admin/model/routing/web/RoutingProblems.java:31-39`；
      `contracts/openapi.yaml:3668-3701`（ProblemDetail）；ADR-0021 §4.3；
      `apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingApiTest.java:171-179`。
  - id: F-003
    severity: P3
    status: open
    owner: "riccon/builder-failover（B4）"
    due: "B4 IT-MODEL-101 / E2E-M04-01"
    title: "IT-MODEL-101 的“下一次请求读新链”只验证 admin GET，未验证实际模型请求"
    summary: >-
      `ModelRoutingApiTest.itModel101` 在 PUT 后再次 `GET /admin/model-routing`（第 125-130 行），
      证明 admin 读路径不缓存；但真正的“下一次模型请求”由 Worker 路由解析完成（B4），本任务没有员工
      聊天请求经新链的断言。这不推翻 B3 的读模型验证（`ModelRoutingReadRepository`/`ModelRoutingQueryJdbc`
      每次查询数据库，无 `@Cacheable`），但 AC-01 的端到端部分仍需 B4/IT-MODEL-101 与 E2E-M04-01 补齐；
      请 B4 至少断言保存后下一次模型调用使用新链的第一个可用模型。
    evidence: >-
      `apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingApiTest.java:125-130`；
      `apps/api/src/main/java/com/riccon/api/model/routing/repository/ModelRoutingReadRepository.java`
      类注释“读路径刻意不做缓存”；ADR-0021 §4.3/§11.1；B4 写范围（apps/worker/src/main/java/com/riccon/worker/model/**）。
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder-route + riccon/builder-failover"
    due: "B4 / M04 gate"
    title: "并发版本冲突只有 mock 分支测试，没有真实双事务/HTTP 并发用例"
    summary: >-
      IT-MODEL-101 只验证顺序旧版本的 409；`ModelRoutingServiceRaceTest` 用 mock 让 `advanceVersion`
      返回 false，覆盖 0 行分支与“不写条目/审计”，证明代码分支，但没有两个真实事务同时保存的集成断言。
      乐观锁 SQL 是单条条件 UPDATE，风险较低；建议 B4/模块 gate 补一个 Testcontainers 双线程保存用例，
      或由 Lead 书面接受当前分支测试作为充分证据。
    evidence: >-
      `apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingServiceRaceTest.java:59-111`；
      `apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingApiTest.java:171-179`；
      `apps/api/src/main/java/com/riccon/api/admin/model/routing/service/ModelRoutingService.java:155-166`。
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/builder-route（测试隔离）"
    due: "B4/B11 测试接入前"
    title: "路由集成测试直接清空全局 model_routing_entry，后续依赖 V11 种子的测试可能受污染"
    summary: >-
      `ModelRoutingApiTest.java:332-333` 和 `ModelRoutingAuditSecurityTest.java:247` 在每个测试前后执行
      `DELETE FROM model_routing_entry`，并保留/推进 `model_routing` 链头版本，不恢复 V11 种子条目。
      当前只有这两个 API 路由测试使用该表，EV-0546 通过；但 B4/B11 或后续全量套件若假定 V11 的
      CHAT/REPORT 种子仍存在，会看到空链。建议测试改用独立事务/唯一 provider 数据，或在 `@AfterAll`
      恢复种子，避免测试顺序依赖。
    evidence: >-
      `apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingApiTest.java:332-333`；
      `apps/api/src/test/java/com/riccon/api/admin/model/routing/ModelRoutingAuditSecurityTest.java:247`；
      `apps/api/src/main/resources/db/migration/V11__model_gateway_depth.sql:371-383`。
  - id: F-006
    severity: P3
    status: open
    owner: "riccon/builder-route"
    due: "TASK-0104 closeout"
    title: "tools_used 未记录 Serena/CodeGraph，也未给出降级理由"
    summary: >-
      `TASK-0104.json` 的 `tools_used` 只有 git、maven、jdk21、docker、riccon；项目 AGENTS 要求代码任务
      默认使用 Serena 符号工具，未用时在任务回执与 `tools_used` 记录降级原因。B3 是高风险（权限/路由）
      任务，建议 builder-route 在 closeout 补记实际工具或书面说明；不影响本次技术验收。
    evidence: >-
      `.riccon/tasks/TASK-0104.json` 的 `tools_used`；`AGENTS.md` 项目补充“工具使用（Serena / CodeGraph）”。
created_at: "2026-11-21T03:10:00+08:00"
---

# REV-TASK-0104-001：TASK-0104 / M04-B3 独立评审

本评审是任务级独立技术验收，不是 Product 预验收或用户产品验收，也不替代 M04 模块评审。任务状态保持
`review`，未标记 done；本结论不等于 M04 模块 gate pass。

## 结论

**pass（任务级）**。GET/PUT 两链、版本乐观锁 409、空链/未知模型/不可路由端点 400、停用模型保留、审计
数组 details 与审计失败回滚均有实现与可核对测试；未发现 P0/P1。开放 **F-001（P2，Lead closeout
重绑 baseline）** 与五个 P3；F-001 不改变本任务技术验收，但 TASK-0104 在 gate 中仍会被报越界，模块 gate
不能靠本评审通过。

## 逐条验收（6 条）

| #   | 验收原文              | 结论 | 依据                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| --- | --------------------- | ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | GET/PUT 两链          | pass | `ModelRoutingController` 仅暴露 `GET /api/v1/admin/model-routing`（:57-60）和 `PUT /api/v1/admin/model-routing/{purpose}`（:72-74）；GET 固定返回 CHAT/REPORT 两条链（`ModelRoutingService.listChains`），PUT 整链保存；`ModelRoutingApiTest.itModel101` 断言初始 GET 两条链、PUT 后版本 +1、条目顺序与 `updatedBy/updatedAt`；契约 `contracts/openapi.yaml:2840-2883` 与实现路径/权限一致。                                                                                                                                                                                                    |
| 2   | 版本冲突 409          | pass | `ModelRoutingService.java:155-166` 先比较当前版本，`advanceVersion` 以 `WHERE purpose=? AND version=?` 条件更新，0 行重读当前版本并抛冲突；`ModelRoutingApiTest.java:171-179` 断言 409、`RICCON-MODEL-409`、`field=version`、`reason=VERSION_CONFLICT`、detail 含当前版本且链不变；`ModelRoutingServiceRaceTest` 覆盖条件更新 0 行分支。真实双事务并发见 F-004（P3）。                                                                                                                                                                                                                          |
| 3   | 空链/未知模型 400     | pass | `ModelRoutingRules.parseModelIds` 拒绝空链（`CHAIN_EMPTY`）、超长（`CHAIN_TOO_LONG`）、重复（`DUPLICATE_MODEL`）、非法 UUID（`INVALID_MODEL_ID`）；`saveChain` 对每个 ID 调 `PlatformModelRepository.findById`，未知模型抛 `UNKNOWN_MODEL`；`ModelRoutingApiTest.java:181-202` 覆盖空链、未知模型、不可路由端点、重复与超长，均断言 `RICCON-MODEL-400` + 字段级 reason，且失败后链不变。                                                                                                                                                                                                        |
| 4   | 停用模型保留          | pass | ADR-0021 §4.2 明确“允许包含 DISABLED 模型（保留位置）”；`ModelRoutingRules.requireRoutable` 只拒绝 provider 非 ACTIVE、协议非 `OPENAI_COMPATIBLE` 或端点不在允许列表（`ModelRoutingRules.java:86-103`）。`ModelRoutingRulesTest` 分别覆盖停用 provider/协议不支持/端点不允许 400 与停用模型允许；`ModelRoutingApiTest.java:132-169` 通过 PATCH 停用 B 后 GET 仍显示 position=2、status=DISABLED，重新启用后状态恢复、链版本不变。                                                                                                                                                               |
| 5   | 审计数组 details 通过 | pass | `ModelRoutingService.appendAudit` 以 platform 租户、`AuditActorType.USER`、`MODEL_ROUTING` + purpose 写 `model.routing.updated`，details 仅 `purpose/modelIds/version/previousVersion/result`（:191-219）；`AuditDetailsPolicy` 为该动作开放受限 UUID 数组（`AuditDetailsPolicy.java:141,190`）。`ModelRoutingAuditSecurityTest.java:128-150` 断言 tenant=platform、actorType=USER、targetType/ID、数组顺序、previousVersion 且无 baseUrl/apiKey/Authorization；`ModelRoutingAuditSecurityTest.java:152-161` 用真实仓储 spy 让路由审计抛错，断言 500 且版本/条目/审计数均不变，证明同事务回滚。 |
| 6   | 下一次请求读新链      | pass | 读路径无缓存：`ModelRoutingQueryJdbc.findAll/find` 每次查询数据库，`ModelRoutingReadRepository` 类注释明确不做跨请求缓存，`ModelRoutingService.listChains` 无 `@Cacheable`；`ModelRoutingApiTest.java:125-130` 在 PUT 后重新 GET 即看到新链，证明 admin 读路径不缓存。实际员工模型请求由 B4 解析，端到端断言归 B4/E2E（F-003，P3）。                                                                                                                                                                                                                                                            |

补充（不在 6 条验收内，按用户关注点）：

- **权限**：GET 要求 `platform.model.read`（`ModelRoutingController.java:57-58`），PUT 要求
  `platform.model.manage`（:72-73）；`ModelRoutingAuditSecurityTest.java:109-113` 断言租户管理员与员工
  对读/写均 403，且响应不含链内容。`RoleDefinitions` 只把 `platform.model.*` 给 PLATFORM_ADMIN。
- **跨租户 404**：路由是全局平台资源，路径不接受 tenantId（ADR-0021 §4.3），不存在“跨租户对象”可测；
  `ModelRoutingAuditSecurityTest.java:115-120` 断言不存在的子路径 `/model-routing/CHAT/versions` 返回
  404 `RICCON-REQUEST-404`，证明没有新增契约端点。原文要求的“跨租户 404”在本资源上 N/A，不是失败。
- **不新增契约端点**：`contracts/openapi.yaml` 只有 `GET /api/v1/admin/model-routing` 与
  `PUT /api/v1/admin/model-routing/{purpose}`；B3 提交未修改 `contracts/**`。

## 验证了什么

- **提交面**：`9b8ab4c` 共 19 个文件，全部位于 `apps/api/src/main/java/com/riccon/api/{admin/,}model/routing/**`
  或 `apps/api/src/test/java/com/riccon/api/admin/model/routing/**`；`d67582c` 只新增
  `ModelRoutingServiceRaceTest.java`。未改 B2/B5/B6 生产代码，未改 contracts、迁移、构建配置。
- **密钥扫描**：对 `git diff 0638626 d67582c` 执行 `sk-`、`api_key=...`、`password=...`、`secret=...`、
  `BEGIN ... PRIVATE KEY` 等 grep，无命中；B3 测试里的 base URL 是 10.20.30.x 私有地址，不是真实凭据。
- **EV-0546**：`manual=false`、`dirty=false`、`exit_code=0`、`commit=f9bff57`、`output_sha256`
  与 48987 字节日志一致（`12b83012…`）；录制时 surefire 报告为 `ModelRoutingApiTest 1/0/0`、
  `ModelRoutingAuditSecurityTest 1/0/0`、`ModelRoutingRulesTest 8/0/0`、`ModelRoutingServiceRaceTest 1/0/0`
  （tests/errors/skipped/failures 口径，均 0 skip/0 failure）。EV 的 commit f9bff57 晚于 end_commit d67582c，
  但 `git diff --name-only d67582c f9bff57 -- <routing 路径>` 为空，说明被证据覆盖的 routing 代码与
  end_commit 一致；f9bff57 带入的是 B6 quota/chat/research 变更，不是 B3 代码。
- **独立复验**：本次在 HEAD 84e78c9 的工作区运行
  `./mvnw -q -T1C -pl apps/api -am test -Dtest='ModelRoutingApiTest,ModelRoutingAuditSecurityTest,ModelRoutingRulesTest,ModelRoutingServiceRaceTest' -Dsurefire.failIfNoSpecifiedTests=false`，
  exit 0；surefire 报告 03:04–03:05 重新生成，仍为 11 tests / 0 fail / 0 error / 0 skip。该命令与 EV-0546
  相同；`-Dsurefire.failIfNoSpecifiedTests=false` 未造成假绿，因为四个类的报告均实际生成。
- **gate 观测**：`gate check --module M04` 在 2026-11-21T03:05:19+08:00 为 FAIL；TASK-0104 的越界列表
  来自 baseline..HEAD 区间（B5 5f9b37c 与 B6 f9bff57），实际 B3 提交面干净（F-001）。

## 未验证

- **任务级全量**：未在 B3 最终提交上跑 `./mvnw -q -T1C verify`/覆盖率；EV-0546 是 11 个定向测试。
  B3 是 ADR-0021 §8.5 的高风险任务，M04 模块 gate 必须在最终提交上跑合同 `test`/`coverage` 并复核
  API/worker/platform-common 覆盖率；本评审不把定向 EV 当作全量绿。
- **实际模型请求**：未通过员工聊天请求验证“下一次模型请求使用新链”；B4 尚未实现/接入 Worker 路由解析
  （F-003）。当前只验证 admin 读模型不缓存。
- **真实并发**：未跑两个真实事务/HTTP 同时保存的集成用例；只验证了顺序旧版本 409 与 mock 的 0 行分支
  （F-004）。
- **匿名 401**：B3 测试覆盖租户管理员/员工 403，未单独断言匿名请求 401；`SecurityConfig` 对
  `/api/v1/**` 要求 authenticated，按既有安全链应返回 401，但不在本任务测试证据内。
- **E2E/a11y/perf/security 扫描**：任务级不跑；由 M04 模块 gate/release 的 E2E、axe、k6、Trivy 覆盖。
- **模块 gate**：当前 gate 仍 FAIL（TASK-0104 review、其他任务 planned/review、无 M04 模块 review）；
  本评审不是模块评审，不能替代 M04 的独立全量验证。

## Findings

| ID    | 级别 | 状态 | 标题                                                                | 影响/要求                                                                       |
| ----- | ---- | ---- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| F-001 | P2   | open | gate 按 baseline..HEAD 计算写范围，把 B5/B6 提交算作 TASK-0104 越界 | 非代码问题；Lead closeout 需把 baseline/end 绑定到 9b8ab4c..d67582c 后重跑 gate |
| F-002 | P3   | open | 409 只在 detail 文本返回 currentVersion，不是机器可读字段           | 不阻塞 6 条验收；Architect 需在 ADR/契约明确 detail 语义或补兼容字段            |
| F-003 | P3   | open | IT-MODEL-101 的“下一次请求”只验证 admin GET，未验证实际模型请求     | B4/IT-MODEL-101 与 E2E-M04-01 需补端到端模型请求断言                            |
| F-004 | P3   | open | 并发版本冲突只有 mock 分支测试，没有真实双事务用例                  | 乐观锁 SQL 风险低；B4/模块 gate 补集成并发或 Lead 书面接受                      |
| F-005 | P3   | open | 路由集成测试直接清空全局 model_routing_entry                        | 当前 EV 通过；B4/B11 依赖 V11 种子时需恢复种子或改隔离方式                      |
| F-006 | P3   | open | tools_used 未记录 Serena/CodeGraph，也未给降级理由                  | 项目工具规则的过程项；closeout 补记或说明，不影响技术验收                       |

## 剩余风险

- F-001 是 gate 层面唯一会直接阻止 TASK-0104 在 gate 中显示为 done 的问题；Lead 收窄区间后应重跑
  `gate check --module M04` 并确认 TASK-0104 的越界列表消失。
- F-003/F-004 说明“路由保存到实际模型请求生效”的端到端与真实并发证据仍依赖 B4；M04 模块 AC-01/AC-03
  不能在 B3 单独判 pass。
- B3 定向 EV 不覆盖覆盖率；新增 routing 类（约 1.9k 行含测试）若覆盖率不足，模块 gate 的 80/70 门槛
  仍可能失败。
- `ModelRoutingApiTest`/`ModelRoutingAuditSecurityTest` 的全局 `DELETE FROM model_routing_entry` 可能让
  后续测试类看到空链；目前未复现失败，但 B4/B11 接入前应处理（F-005）。
- 版本冲突的 `currentVersion` 只在 detail 文本中，前端若需要直接刷新版本，应重新 GET 而不是解析
  detail；F-002 需 Architect/Lead 在 gate 前统一口径。
