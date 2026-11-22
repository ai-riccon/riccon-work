---
review_id: REV-M20-001
module_id: M20
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dfde1f0a9ffeycdXnyr3TZKsjt
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: 845d21919e8395466d79f12f1b333579062d4a02
reviewed_commits:
  - 3541007dadb3bbc802cd6f611006de02b8b79c5f
  - 1e6f655f47402d404051526d65be629b1a4d187f
  - 694d0e6fa0781d2178970b101e4042b2a4d1797c
  - 7d3d1626cfcf5f16cf21aec4bbecc39bac357229
  - e1f9137c1df717868dffe9a79b34fa9cc1f9ed14
  - 5f09a6bb3e310b7cb457628ed870e3fbbd2426d6
  - 22e1be9f5a504a9a231609e96ccfd12083f19d3a
  - dd22cfda85b8b65a0074f3eb477970b94c2279ac
  - 4eefad767ecd833c5d4e6d05de90d91147033207
  - 845d21919e8395466d79f12f1b333579062d4a02
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
  acceptance: [EV-0749, EV-0760, EV-0761, EV-0764, EV-0765, EV-0768]
  build: [EV-0763]
  tests: [EV-0764]
  coverage: [EV-0764]
  e2e: [EV-0765]
  docs: [EV-0754, EV-0762]
findings:
  - id: F-001
    severity: P2
    title: "价格快照仍是“成功终态读取一次”，存在调用期间运维改价的在途窗口；V11/ADR-0021 旧列注释保留历史文案"
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M20 gate 前由 Architect/Lead 书面认可；任何价格维护端点或批量改价作业（M05+）上线前必须重访 ADR-0022 §2.1"
    accepted_reason: >-
      TASK-0140 走了任务验收允许的第二路径：ADR-0022 §2.1 与 ARCHITECTURE 已如实修订为“成功终态读取一次”，
      写明与历史“最终成功尝试开始时”文案的差异、影响边界和价格维护前重访条件。实现只按最终成功模型读一次价格，
      不混合多个价格；M04 不提供价格编辑端点，普通用户无自助改价入口，风险有界。本次模块全量测试与 E2E
      不改变该结论；残余是在途运维改价窗口和已应用迁移/历史 ADR 的旧列注释，不是当前功能缺陷。
    evidence: >-
      docs/adr/ADR-0022-m04-quota-and-cost.md:195-199；
      docs/ARCHITECTURE.md:262、427；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:278；
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:168-191、315-337；
      REV-TASK-0140-001 F-001；EV-0750、EV-0751、EV-0764。
  - id: F-002
    severity: P3
    title: "真实 3 秒证据使用同提示受控慢查询与真实服务/HTTP 映射，生产聚合 SQL 本身未在生产数据量下超时"
    status: accepted
    owner: "riccon/builder-cost + riccon/ops"
    due: "M09 性能观测或生产能力验证前"
    accepted_reason: >-
      CostQueryTimeoutIntegrationTest 在真实 Testcontainers MySQL 8.4.13 上证明
      MAX_EXECUTION_TIME(3000) 约 3 秒中止（错误码 3024），并经真实 CostQueryService/PlatformCostService
      与真实 HTTP 返回 503 RICCON-COST-503、不返回部分结果；生产 SQL 的 totals/bucketCount/buckets
      三个入口均带同一提示。测试数据量下无法让生产聚合 SQL 自然超过 3 秒，剩余是对生产数据量与查询形状的
      推演，属有界残余；类 Javadoc 已披露边界。故接受为 P3，由 M09 生产性能观测复核。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/cost/CostQueryTimeoutIntegrationTest.java:35-79、84-132、166-195；
      apps/api/src/main/java/com/riccon/api/cost/persistence/CostJdbc.java:37、53-79；
      apps/api/src/main/java/com/riccon/api/admin/cost/persistence/PlatformCostJdbc.java:30、68-95；
      apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java:86-107；
      REV-TASK-0141-001 F-001；EV-0755、EV-0756、EV-0764。
  - id: F-003
    severity: P3
    title: "EV-0755 只有类级耗时，未逐用例保留 3 个超时用例的耗时"
    status: accepted
    owner: "riccon/builder-cost"
    due: "后续证据整理 / M05 gate 前"
    accepted_reason: >-
      类级耗时、MySQL 3024 异常堆栈、3 秒断言区间和独立重跑（3.755s/3.700s/3.012s）共同证明超时行为；
      缺逐用例控制台耗时是证据粒度问题，不是零测试、跳过或功能失败，不影响 3 秒保护验收结论。
    evidence: >-
      .riccon/evidence/TASK-0141/EV-0755 日志；
      apps/api/target/surefire-reports/TEST-com.riccon.api.cost.CostQueryTimeoutIntegrationTest.xml；
      REV-TASK-0141-001 F-003；EV-0764（本次全量重跑 3 tests）。
  - id: F-004
    severity: P3
    title: "OpenAPI CostPage maximum=200 与三处引用没有专门的自动化契约断言"
    status: accepted
    owner: riccon/architect
    due: "M05 gate 前"
    accepted_reason: >-
      TASK-0143 的 CostPage（minimum 1/maximum 200/default 1）与三个成本端点引用已逐行核对，
      ADR-0022 §2.4/§2.5 一致；ModelContract108bTest 验证 YAML 可解析且既有契约断言未回归，
      但未断言 maximum/引用数量。属回归保护强度问题，不影响当前契约正确性，接受为 P3。
    evidence: >-
      contracts/openapi.yaml:3238、3279、3323、3373-3383；
      apps/api/src/test/java/com/riccon/api/contract/ModelContract108bTest.java:128-163；
      apps/api/target/surefire-reports/TEST-com.riccon.api.contract.ModelContract108bTest.xml（1 test，0/0/0）；
      REV-TASK-0143-001 F-002；EV-0749、EV-0764。
  - id: F-005
    severity: P3
    title: "PRODUCT/ACCEPTANCE 仍保留“统一 404”摘要与 M04-AC-12 的 pass 标签，未标 pass (with residual)"
    status: accepted
    owner: "riccon/architect + riccon/product"
    due: "M20 Product 预验收前"
    accepted_reason: >-
      M04-AC-12 主行、ADR-0022 §2.4 与 403/404 测试已一致；残余只在 PRODUCT 优先级摘要
      “统一 404”、ADR Scope 摘要与 ACCEPTANCE 的状态标签，不改变用户可见行为、错误码或 M04 已验收结论。
      按 REV-TASK-0143-001 的建议转 Product 预验收统一措辞并标 pass (with residual F-006/TASK-0142)。
    evidence: >-
      docs/PRODUCT.md:356、448；docs/ACCEPTANCE.md:307；docs/adr/ADR-0022-m04-quota-and-cost.md:27；
      REV-TASK-0143-001 F-003。
  - id: F-006
    severity: P3
    title: "CODING-STANDARDS §10.4 的 security 映射未列 PlatformCostTenantFilterTest"
    status: fixed
    owner: riccon/architect
    evidence: >-
      TASK-0156 / 4eefad7；docs/CODING-STANDARDS.md:242；
      EV-0762（grep 断言通过）。
  - id: F-007
    severity: P3
    title: "首字口径漂移与测试强度不足：TextBlockStartEvent 被计为可见首字、GENERATION 只断言非空、TOOL attempt 未断言"
    status: fixed
    owner: riccon/builder-cost
    evidence: >-
      TASK-0154 / 7d3d162；
      apps/worker/src/main/java/com/riccon/worker/research/service/ModelFirstToken.java:53-75；
      ModelFirstTokenTest 11 tests、ModelFirstTokenHandlerTest 6 tests；
      ReportArtifactIntegrationTest 断言 TOOL attempt first_token_ms 为 NULL、GENERATION 非空；
      docs/adr/ADR-0022-m04-quota-and-cost.md:263-265；EV-0752、EV-0753、EV-0764。
  - id: F-008
    severity: P3
    title: "ChatStreamIntegrationTest.setModelPrices 依赖全局 ACTIVE 集合更新价格"
    status: fixed
    owner: riccon/builder-cost
    evidence: >-
      TASK-0154 / 7d3d162；apps/worker/src/test/java/com/riccon/worker/chat/ChatStreamIntegrationTest.java:597-603
      已按 modelId 更新；EV-0753、EV-0764（18 tests/0 skip）。
  - id: F-009
    severity: P3
    title: "ADR-0022 §2.4 仍写着 normalizeTenantFilter 把空白归一为未提供"
    status: fixed
    owner: "riccon/builder-core + riccon/architect"
    evidence: >-
      TASK-0142 / 22e1be9、dd22cfd；docs/adr/ADR-0022-m04-quota-and-cost.md:249；
      apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java:127-139；
      EV-0760、EV-0761。
  - id: F-010
    severity: P3
    title: "TenantContextFilter 类级 javadoc 未描述平台成本窄例外；例外内会话失效曾归为 403 missing_permission"
    status: fixed
    owner: riccon/builder-core
    evidence: >-
      TASK-0142 / dd22cfd；TenantContextFilter.java:37-50、199-233；
      TASK-0156 / 4eefad7 记录 ADR-0009/ADR-0022 的 401 语义；
      REV-TASK-0142-002 F-002/F-004；EV-0760、EV-0761。
reviewed_task_reviews:
  - REV-TASK-0140-001
  - REV-TASK-0141-001
  - REV-TASK-0142-002
  - REV-TASK-0143-001
evidence:
  - EV-0748
  - EV-0749
  - EV-0750
  - EV-0751
  - EV-0752
  - EV-0753
  - EV-0754
  - EV-0755
  - EV-0756
  - EV-0757
  - EV-0758
  - EV-0759
  - EV-0760
  - EV-0761
  - EV-0762
  - EV-0763
  - EV-0764
  - EV-0765
  - EV-0766
  - EV-0767
  - EV-0768
reviewed_evidence:
  - EV-0748
  - EV-0749
  - EV-0750
  - EV-0751
  - EV-0752
  - EV-0753
  - EV-0754
  - EV-0755
  - EV-0756
  - EV-0757
  - EV-0758
  - EV-0759
  - EV-0760
  - EV-0761
  - EV-0762
  - EV-0763
  - EV-0764
  - EV-0765
  - EV-0766
  - EV-0767
  - EV-0768
notes: >-
  M20（fix M04）独立模块验证：在最终代码提交 845d219 上跑合同 build、test+coverage、E2E、a11y、security
  与 contract/state 校验，均 exit 0；后端 200 suites/1148 tests/0 failure/0 error/0 skip，前端 57 files/493 tests，
  覆盖率全部过 80/70；E2E 40 passed、a11y 15 passed、Trivy 0 漏洞/0 密钥/0 错误配置（2 条 ADR-0011 CVE 例外）。
  REV-TASK-0108-001 P2 F-001..F-008 均已 fixed 或由 owner 书面 accepted；无 open P0/P1，accepted 残余
  F-001..F-005 均有 owner/due/accepted_reason。TASK-0154/0155/0156 无独立 task review，由 Lead 复核，
  本模块评审已覆盖其代码/文档结果。本评审不替代 Product 预验收或用户验收，也不代替 Lead 运行 gate。
created_at: "2026-11-22T15:40:28+08:00"
---

# REV-M20-001：M20（fix M04）成本快照、查询保护与租户过滤的模块独立评审

## 结论

**pass（带 accepted 残余 F-001..F-005）**。在 M20 最终代码提交 `845d219` 上独立执行完整模块 DoD：

- 合同 `commands.build`（`./mvnw -q -T1C -DskipTests package && pnpm -w build`）exit 0（EV-0763）；
- 合同 `commands.test` + `commands.coverage` 共用同一全量命令
  （`./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`）exit 0（EV-0764）；
  后端 **200 suites / 1148 tests / 0 failure / 0 error / 0 skip**，前端 **57 files / 493 tests passed**；
- 合同 `commands.test_e2e`（compose 测试栈 + `pnpm -w e2e`）**40 passed**（EV-0765），含 `E2E-M04-06` 成本页；
- `pnpm -w a11y` **15 passed**（EV-0766）；
- 合同 `commands.security`（Trivy）exit 0，0 漏洞、0 密钥、0 错误配置（EV-0767；2 条 CVE 按 ADR-0011 例外）；
- `contract validate --strict` **0 errors/0 warnings**，`state validate` **0 errors**（43 条历史 warning，无 M20 新增错误，EV-0768）。

M20 的三条模块验收均成立：REV-TASK-0108-001 的 P2 F-001..F-008 已全部 fixed 或由 owner 书面 accepted；
M04-AC-11/AC-12 未被降低（当前提交 E2E 与集成测试重新证明）；认证/金额相关任务均有独立 Verifier 评审，
且未修改历史评审或历史证据。无 P0/P1、无 open finding。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；模块状态、gate 与后续发布由 Lead/用户处理。

## 1. 变更范围、任务与独立性

- 验证对象：`4930443`（M20 启动前 handoff）→ `845d219`（最终代码/状态提交）。核心提交：
  `3541007`（契约与 AC-12 文案）、`694d0e6`（成本快照/报表断言）、`7d3d162`（首字与测试隔离）、
  `e1f9137`（ADR/RUNBOOK 文档一致性）、`5f09a6b`（空白 tenantId/3s/五项计数）、
  `22e1be9`（过滤器角色校验）、`dd22cfd`（header/query 冲突与 401）、`4eefad7`（security 映射与 401 文档）、
  `845d219`（TASK-0156 done，进入 review_pending）。
- 变更面：68 个文件，全部落在 M20 任务 write_scope 的并集或 `.riccon/**`；生产代码仅涉及
  `PlatformCostService`、`TenantContextFilter`、`ModelInvocationService`、两个报表 handler 与新增
  `ModelFirstToken`；无迁移、无构建配置、无前端产品代码、无合同外 API/DB 变更。`docs/` 仅 ADR-0021/0022、
  ADR-0009、ARCHITECTURE、PRODUCT、ACCEPTANCE、CODING-STANDARDS、RUNBOOK。
- 四个有独立 task review 的任务：REV-TASK-0140-001、REV-TASK-0141-001、REV-TASK-0142-002（最新）、
  REV-TASK-0143-001。TASK-0154/0155/0156 无独立 task review，属 Lead 复核的 P3/文档收口；本评审直接读取其
  代码、测试和文档结果并纳入验证（不把 Lead 的总结当证据）。
- 本评审使用独立会话 `ses_dfde1f0a9ffeycdXnyr3TZKsjt`；先读任务卡、模块卡、全部 task review、ADR、
  OpenAPI 与源码，再在最终提交上执行命令。未采信 builder/Lead 的结论；未修改生产源码、测试、迁移、
  合同、历史评审或历史证据。评审新产生的 EV-0763..EV-0768 由 `riccon evidence run` 录制，均为
  `manual=false`、`dirty=false`、`commit=845d219`，日志 hash/字节数与 JSON 一致。

## 2. DoD 全量证据

| DoD / 合同命令                                                                                                                     | EV      | 结果            | 观察值                                                                                                                    |
| ---------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------- | ------------------------------------------------------------------------------------------------------------------------- |
| build：`./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                       | EV-0763 | exit 0，24.99s  | Maven package 成功；Umi/Webpack 生产构建成功，`dist/umi.js` 299.67 kB gzip                                                |
| test + coverage：`./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                               | EV-0764 | exit 0，549.44s | 后端 200 suites / **1148 tests / 0 failure / 0 error / 0 skip**；前端 **57 files / 493 tests passed**；覆盖率见 §2.1      |
| E2E：`docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                        | EV-0765 | exit 0，4.3m    | **40 passed**；含 `E2E-M04-06` 成本页 AC-11 断言、`E2E-M04-05/07b` 配额/成本可访问性与入口                                |
| a11y：`pnpm -w a11y`                                                                                                               | EV-0766 | exit 0，2.1m    | **15 passed**；M04 成本/配额/路由/key 四页与 J1/J2/M03/M10 页面 axe 无 serious/critical                                   |
| security：`trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` | EV-0767 | exit 0          | 7 个依赖清单 0 漏洞、0 密钥、0 错误配置；2 条 CVE 例外见 §7                                                               |
| contract + state：`contract validate --strict` / `state validate`                                                                  | EV-0768 | exit 0          | `Project contract: OK (0 errors, 0 warnings)`；`State: OK (0 errors, 43 warnings)`（均为历史 warning，无 M20 新增 error） |

补充：EV-0764 的 surefire 逐类汇总为 api 122 suites/654 tests、worker 68/377、platform-common 5/87、
platform-domain 4/18、architecture 1/12；四个新增/改动关键类均通过：`CostQueryTimeoutIntegrationTest` 3、
`PlatformCostTenantFilterTest` 5、`ModelFirstTokenTest` 11、`ModelFirstTokenHandlerTest` 6；报表
`ReportArtifactIntegrationTest` 11、聊天 `ChatStreamIntegrationTest` 18、成本快照
`ModelCostSnapshotIntegrationTest` 6、契约 `ModelContract108bTest` 1。全部 0 failure/0 error/0 skip。

### 2.1 覆盖率

| 范围                  |                Line |              Branch | 门槛  | 结论 |
| --------------------- | ------------------: | ------------------: | ----- | ---- |
| apps/api              | 92.39%（9144/9897） | 75.41%（2055/2725） | 80/70 | pass |
| apps/worker           | 88.00%（5192/5900） | 71.22%（1594/2238） | 80/70 | pass |
| platform-common       |   90.83%（713/785） |   75.41%（365/484） | 80/70 | pass |
| platform-domain       |   94.74%（252/266） |              无分支 | 80/70 | pass |
| apps/web（vitest v8） | 87.01%（3772/4335） | 80.01%（2826/3532） | 80/70 | pass |

EV-0764 同时作为 `tests` 与 `coverage` 证据；合同允许 test 与 coverage 共用同一条全量命令，未重复跑第二遍。
覆盖率排除项按合同为生成代码、deploy/、e2e/ 与纯展示组件；未发现新增未排除的生成代码。

## 3. M20 模块验收对账

| #   | 验收原文要点                                                                                       | 结论                                  | 依据                                                                                                                                                                                                                                                              |
| --- | -------------------------------------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | REV-TASK-0108-001 P2 F-001..F-008 全部 fixed 或由 owner 书面 accepted（owner/due/accepted_reason） | pass                                  | F-001 accepted（F-001 本评审）；F-002..F-008 均 fixed；映射与证据见 §5。REV-TASK-0142-001 的 P2 F-001（header 掩盖 query）也已由 dd22cfd fixed 并经 REV-TASK-0142-002 二次评审                                                                                    |
| 2   | 不降低 M04 已验收的 AC-11/AC-12 与 M04 gate 证据；受影响测试与契约校验通过                         | pass（带 accepted 残余 F-001..F-005） | 当前提交 EV-0764（成本/隔离全量）、EV-0765（E2E-M04-06）重新通过；EV-0749/EV-0764 的契约测试通过；EV-0754/0762 文档一致性通过。残余均为已书面 accepted 的窗口/证据强度/文档标签，不改变用户可见结果（见 §4/§6）                                                   |
| 3   | 认证/金额相关任务由独立 Verifier 评审；不修改历史评审与证据                                        | pass                                  | 金额：REV-TASK-0140-001、REV-TASK-0141-001；认证/权限：REV-TASK-0142-001 与二次评审 REV-TASK-0142-002；契约：REV-TASK-0143-001；本模块评审再次独立验证。`git diff --name-status 4930443..845d219` 未出现历史 review/evidence 的修改（仅新增 M20 review/evidence） |

## 4. M04-AC-11 / AC-12 对账（不得降低）

### AC-11 成本口径

**pass（with residual F-001）**。EV-0765 的 `E2E-M04-06` 在当前提交重新通过：平台按租户/模型/日、租户按用户/模型/日聚合；
金额 6 位计算、2 位展示、明细展开显示调用时价格快照、未定价/用量未知/币种不一致/未分桶单列且不计入合计、
固定免责文案、单一币种、Asia/Shanghai 日桶均被 UI 断言。EV-0764 中 `ReportArtifactIntegrationTest` 与
`ChatStreamIntegrationTest` 用固定样例（12×3 + 8×6 = 0.000084）断言报表/聊天两条路径的 PRICED 快照；
负 token 归一为 USAGE_UNKNOWN，不生成负金额。残余仅为 F-001 的“成功终态读取一次”在途改价窗口和旧列注释，
M04 无价格编辑端点，AC-11 用户可见结果不变。

### AC-12 租户/角色边界

**pass（with residual F-005）**。EV-0764 中 `CostAdminApiTest`、`PlatformCostTenantFilterTest`、`CostApiTest`
与全量隔离测试重新通过：租户管理员伪造 `tenantId`/`X-Tenant-Id` → 403 `RICCON-AUTH-403` + 拒绝审计；
平台非管理员无 `platform.cost.read` → filter 403（无参数由 interceptor 403）；header 与 query 同时出现且不一致
→ 403 `tenant_mismatch`（任何一方不能掩盖另一方）；例外内会话失效/账号禁用 → 401 `RICCON-AUTH-401` 且不写
`tenant.access.denied`；资源 ID（userId）跨租户 → 404 `RICCON-TENANT-404`，与不存在不可区分；平台管理员
query 过滤生效、header 不参与过滤。EV-0765 重新通过平台/租户成本视图、员工入口/403 的 E2E。残余 F-005
只是 PRODUCT/ACCEPTANCE 摘要与 pass 标签的文档不一致，不改变 403/404 实际行为或 M04 已验收结论。

## 5. REV-TASK-0108-001 P2 finding 对账

| 原 finding                               | 处置                                                                                                                            | 本评审结论                           | 证据                                                                            |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------- |
| F-001 价格快照时点                       | ADR-0022/ARCHITECTURE 修订为“成功终态读取一次”，记录差异、影响边界和 M05+ 重访条件；实现单次读取、不混价                        | **accepted（F-001，P2）**            | EV-0750/0751、EV-0764；ADR-0022:195-199；REV-TASK-0140-001 F-001                |
| F-002 3s 查询保护只有 mock               | 真实 MySQL 8.4.13 证明 3 秒中止 + 真实服务/HTTP 503，不返回部分结果；生产 SQL 同提示                                            | **fixed**（残余 P3 F-002）           | EV-0755/0756、EV-0764；REV-TASK-0141-001                                        |
| F-003 报表/聊天成本无断言                | 报表与聊天端到端断言 PRICED、固定样例 0.000084、币种/版本/日桶/首字                                                             | **fixed**                            | EV-0750/0751、EV-0764；ReportArtifactIntegrationTest、ChatStreamIntegrationTest |
| F-004 报表 first_token 未写              | GENERATION 从 ModelCallStartEvent→首个非空 TextBlockDeltaEvent 事件时间计算；TOOL 仅 tool_calls 保持 NULL                       | **fixed**                            | EV-0750/0753、EV-0764；ModelFirstToken 与 handler 测试                          |
| F-005 平台五项计数无测试                 | 五类互斥计数各 1、求和等于终态行数、租户过滤后一致性、金额币种/桶断言                                                           | **fixed**                            | EV-0755/0756、EV-0764；CostAdminApiTest:273-330                                 |
| F-006 filter 例外不校验角色/负向测试不足 | filter 内 `requirePermission(platform.cost.read)`；平台非管理员、伪造 header/query、其他路径/方法、冲突、401 均有真实 HTTP 测试 | **fixed**                            | EV-0760/0761、EV-0764；REV-TASK-0142-002                                        |
| F-007 通用 Page 无 maximum               | 新增 `CostPage`（1..200）并仅用于三个成本端点，全局 Page 不变                                                                   | **fixed**（残余 P3 F-004 断言强度）  | EV-0749、EV-0764；contracts/openapi.yaml:3373-3383；REV-TASK-0143-001           |
| F-008 AC-12“统一 404”与 403 不一致       | PRODUCT/ACCEPTANCE 主行改为 403/404 分工，ADR-0022 §2.4 对齐                                                                    | **fixed**（残余 P3 F-005 摘要/标签） | EV-0749；REV-TASK-0143-001                                                      |
| F-010 空白 tenantId/非法 UUID/page 边界  | 空串/纯空白/超长 → 400 RICCON-COST-400；userId/modelId 非 UUID → 400 RICCON-REQUEST-400；page=201 → 400                         | **fixed**                            | EV-0755/0756、EV-0764；CostAdminApiTest:224-249                                 |
| F-012 负 token 生成负金额                | 写库前整组归一 NULL → USAGE_UNKNOWN，价格快照保留；DB CHECK 兜底                                                                | **fixed**                            | EV-0750/0751、EV-0764；ModelCostSnapshotIntegrationTest:155-190                 |

## 6. Task review findings 逐条对账

| Task review       | finding                                 | 最新状态                      | 本模块评审处置               |
| ----------------- | --------------------------------------- | ----------------------------- | ---------------------------- |
| REV-TASK-0140-001 | F-001 P2 价格快照终态读取窗口           | accepted                      | 保留为模块 F-001（accepted） |
| REV-TASK-0140-001 | F-002 P3 TextBlockStartEvent 口径漂移   | accepted → 由 TASK-0154 fixed | 模块 F-007（fixed）          |
| REV-TASK-0140-001 | F-003 P3 全局价格更新测试隔离           | accepted → 由 TASK-0154 fixed | 模块 F-008（fixed）          |
| REV-TASK-0140-001 | F-004 P3 first_token 测试强度           | accepted → 由 TASK-0154 fixed | 模块 F-007（fixed）          |
| REV-TASK-0141-001 | F-001 P3 3s 证据形状                    | accepted                      | 保留为模块 F-002（accepted） |
| REV-TASK-0141-001 | F-002 P3 ADR §2.4 过时句                | accepted → 由 TASK-0142 fixed | 模块 F-009（fixed）          |
| REV-TASK-0141-001 | F-003 P3 EV 逐用例耗时                  | accepted                      | 保留为模块 F-003（accepted） |
| REV-TASK-0142-002 | F-001 P2 header/query 冲突              | fixed（dd22cfd）              | 已关闭，无残余               |
| REV-TASK-0142-002 | F-002 P3 类级 javadoc                   | fixed                         | 模块 F-010（fixed）          |
| REV-TASK-0142-002 | F-003 P3 CODING-STANDARDS security 映射 | accepted → 由 TASK-0156 fixed | 模块 F-006（fixed）          |
| REV-TASK-0142-002 | F-004 P3 会话失效 401 语义              | fixed                         | 模块 F-010（fixed）          |
| REV-TASK-0143-001 | F-001 P2 空白 tenantId 400 vs 200       | accepted → 由 TASK-0141 fixed | 已关闭（§5 F-010）           |
| REV-TASK-0143-001 | F-002 P3 CostPage 无自动断言            | accepted                      | 保留为模块 F-004（accepted） |
| REV-TASK-0143-001 | F-003 P3 统一 404/状态标签              | accepted                      | 保留为模块 F-005（accepted） |
| REV-TASK-0143-001 | F-004 P3 X-Tenant-Id 例外描述           | accepted → 由 TASK-0142 fixed | 已关闭（ADR-0022:243-244）   |

无 open finding；P0/P1 为零。TASK-0140-001 的 3 条 P3 与 TASK-0142-002 的 F-003 均由后续任务修复并有
提交/证据，不是“只改文档不验证”。

## 7. 代码、测试与安全抽查

- **TenantContextFilter**：header/query 分别 `nonBlank` 解析；冲突检查在平台成本例外之前，冲突时 403 +
  `tenant.access.denied`（`reason=tenant_mismatch`，details 同时记录 header/query，长度受限）；
  例外只匹配 `GET /api/v1/admin/costs` + `platform` 主体 + `platform.cost.read`；401/403 分支互斥，
  权限读取异常 fail-closed；审计写入失败返回 500；`finally` 清理 `TenantContext`。未发现可绕过/越权路径。
- **ModelFirstToken / handlers**：只认非空且非纯空白 `TextBlockDeltaEvent`，起点为产生该增量的
  `ModelCallStartEvent.createdAt`；首个结果出现后冻结，后续 Start 不覆盖；缺失/非法时间戳返回 null；
  TOOL 仅 tool_calls 时 attempt/invocation 的 `first_token_ms` 均为 NULL。11+6 个单测 + 报表 IT 覆盖。
- **ModelInvocationService**：`succeeded` 在写库前把任一负 token 整组置 NULL；`costSnapshot` 只在最终成功模型
  读一次价格，UNPRICED/USAGE_UNKNOWN/PRICED 分支清晰；金额先求分子和再 6 位 HALF_UP；日桶 Asia/Shanghai。
- **成本查询**：`PlatformCostService` 对 totals/bucketCount/buckets 三个仓储调用共用 try/catch，超时映射
  503；`CostJdbc`/`PlatformCostJdbc` 的查询均带 `MAX_EXECUTION_TIME(3000)`；空白/超长 tenantId 在服务层拒绝；
  `CostPage` 上限 200。
- **测试质量**：改动/新增测试无 `@Disabled`、`assumeTrue`、`Thread.sleep`、TODO/FIXME 或 retry 包装；
  超时用例有 `@Timeout(30)` 且断言耗时区间；平台五项测试使用唯一租户/模型和保留窗口清理；无零测试命令。
- **安全基线**：EV-0767 0 漏洞/0 密钥/0 错误配置。`.trivyignore` 仅保留 ADR-0011 记录的
  CVE-2026-35568/CVE-2026-34237（MCP Java SDK 0.17.2，exp 2027-03-31；worker 仅用 MCP client，
  不启动入站 MCP server）。M20 未改依赖，无新增例外。新过滤器变更已由两轮独立安全评审覆盖。

## 8. 性能、迁移与可访问性

- **性能（no-impact）**：M20 未改变查询 SQL、端点形状或性能预算；价格快照仍为成功写库前一次 `findPricing`，
  未增加调用路径查询次数；过滤器新增的权限查询只发生在“平台成本例外 + 伪造 tenantId”请求，不影响普通请求。
  合同 `commands.perf` 未运行，理由为无性能面变更；M04 的 EV-0669/0681 性能基线仍是权威，M20 不降低它。
- **迁移（no-impact）**：`git diff 4930443..845d219 -- '*db/migration*'` 为空；未修改已应用 V11，未新增迁移。
  因此未在副本上做 forward/rollback 演练，也没有新的迁移风险。
- **可访问性**：M20 无前端产品代码变更；`pnpm -w a11y` 15/15 通过，`E2E-M04-06` 的 @a11y 用例在当前提交
  重新通过。未新增页面或文案入口。

## 9. 未验证

- **生产数据量下的聚合 SQL 超时**：未用生产规模数据让 `CostJdbc`/`PlatformCostJdbc` 的真实聚合 SQL 自然超过
  3 秒；只用同 `MAX_EXECUTION_TIME(3000)` 提示的受控慢查询与真实服务/HTTP 证明中止与映射（F-002）。
- **真实模型 provider**：worker 测试使用 `TestModelStub`/固定 usage，未接真实外部模型；未验证真实 provider 的
  usage 异常、空文本块、事件时间戳格式差异。
- **性能 k6**：未运行 `k6 run e2e/perf/mvc-smoke.js`，理由见 §8；因此本评审不声称当前提交的 p95 数值。
- **迁移 forward/rollback**：未运行，原因见 §8（无迁移变更）。
- **Product 预验收/用户最终验收**：不属于 Verifier 职责；本评审不替代 Product/用户结论。
- **`gate check`**：按派单要求未运行（Lead 负责 gate 与模块状态）；本评审只提供独立 verdict 与证据。
- **真实浏览器人工 UX 抽查**：仅通过 Playwright E2E/a11y 自动化；未做人工视觉走查。
- **Trivy 之外的 SAST**：未运行 semgrep/gitleaks；合同 security 命令为 Trivy，M20 无新增依赖/密钥。

## 10. 剩余风险

1. **F-001 在途改价窗口**：M04 无价格编辑端点，风险有界；M05+ 开放价格维护/批量改价前必须重访 ADR-0022 §2.1，
   否则在途调用会使用改价后的价格。V11/ADR-0021 旧列注释仍存在，已在 ADR-0022 标为历史设计记录。
2. **F-002 生产 SQL 形状**：3 秒提示与异常映射已由真实 MySQL 证明，但生产聚合 SQL 在真实数据量下是否自然触发
   3 秒中止仍依赖 M09 观测；若数据规模超过私有化假设，需评估 rollup 或索引。
3. **F-003 证据粒度**：EV-0755 缺逐用例耗时；后续证据整理可附 surefire XML，但不影响本次结论。
4. **F-004 契约回归**：CostPage maximum/引用数量没有专门断言；未来 OpenAPI 编辑可能漂移而 `ModelContract108bTest`
   仍绿，建议 M05 前补一条读取 `contracts/openapi.yaml` 的断言。
5. **F-005 文档残余**：PRODUCT:448/ADR-0022:27 的“统一 404”摘要与 ACCEPTANCE:307 的 pass 标签仍不一致；
   建议 Product 预验收统一并标 `pass (with residual F-006/TASK-0142)`。
6. **审计 target_id 语义/截断**：冲突时 target_id 取 query 值，反向冲突时可能指向自身；details 同时记录
   header/query 与 reason，审计事实完整。超长 header 可能截断复合 details（≤200 字符），最坏审计失败返回 500，
   仍 fail-closed。属 REV-TASK-0142-002 剩余风险，非 M20 gate 阻断。
7. **Surefire fork 收尾噪声**：EV-0764 仍有 `Surefire is going to kill self fork JVM ... 30 seconds`，为历史性
   测试进程问题，退出码 0、测试汇总 0 failure/0 error/0 skip；建议后续单独治理，避免掩盖未来真实泄漏。
8. **Trivy 例外**：2 条 MCP SDK CVE 仍按 ADR-0011 抑制至 2027-03-31；M20 未改变依赖或入站 MCP 能力。
9. **状态历史 warning**：`state validate` 仍有 43 条历史 warning（旧 review open/accepted 字段、M11 legacy 状态等），
   均非 M20 新增；M20 的 “module.review is not set” warning 需 Lead 在 gate/模块状态更新时设置 `module.review`
   或由 gate 认可本文件，不属于 Verifier 写入范围。

## 11. Findings 摘要

- **F-001（P2，accepted）**：价格快照终态读取的在途改价窗口 + 旧列注释；owner Architect/Lead，
  M20 gate 前书面认可，M05+ 价格维护前重访。详见 front matter。
- **F-002（P3，accepted）**：3 秒证据是“真实 MySQL 提示 + 真实服务/HTTP 映射”，不是生产聚合 SQL 本身在生产数据量下超时；
  owner builder-cost/ops，M09 性能观测前复核。
- **F-003（P3，accepted）**：EV-0755 仅类级耗时；证据粒度问题，后续整理。
- **F-004（P3，accepted）**：OpenAPI CostPage 无专门自动断言；回归保护强度问题。
- **F-005（P3，accepted）**：PRODUCT/ACCEPTANCE 的“统一 404”摘要与 pass 标签；转 Product 预验收清理。
- **F-006..F-010（P3，fixed）**：CODING-STANDARDS security 映射、首字口径/测试强度、chat 测试隔离、ADR §2.4 过时句、
  filter javadoc/401 语义，均由 TASK-0154/0155/0156 或 dd22cfd 修复并有证据/测试。

无 P0/P1，无 open finding。

## 12. 独立性声明

本评审由 `riccon/verifier` 在独立会话 `ses_dfde1f0a9ffeycdXnyr3TZKsjt` 执行，使用独立上下文，不继承 builder/Lead
的推理。先读取 `.riccon/modules/M20.json`、7 张任务卡、4 份最新 task review、M22 模块评审模板、
`docs/COLLABORATION.md`、`docs/PRODUCT.md`/`docs/ACCEPTANCE.md` 的 M04-AC-11/12、ADR-0009/0021/0022 与
`contracts/openapi.yaml` 成本端点，再用 CodeGraph 读取 `ModelInvocationService`、`ModelFirstToken`、
`AgentRunRequestedHandler`/`AgentRunResumeHandler`、`TenantContextFilter`、`PlatformCostService` 的当前源码与调用路径；
独立执行 build、test+coverage、E2E、a11y、security、contract/state 全量命令并录制 EV-0763..EV-0768。
未修改生产源码、测试、迁移、构建配置、合同、ADR、历史评审或历史证据；未把 builder 总结当证据。
评审期间未启动 Serena/JDT LS，避免与全量 Maven 测试并发污染 `target/`。

## 13. Gate

按派单要求**未运行** `riccon gate check --module M20`，也未运行 `module update`/`release`；Lead 负责 gate 与模块状态。
本评审给出的预期 gate 条件：M20 七个任务均 done、21 条 M20 任务证据 hash/归属有效、strict DoD 的
acceptance/build/tests/coverage/e2e/docs 均绑定 EV-0749/0760/0761/0763/0764/0765/0754/0762 等已评审证据、
review 独立性/真实 session 通过、无 open P0/P1。F-001..F-005 的 accepted 状态需 Lead 在 gate closeout 书面确认；
F-005 的文档清理可在 Product 预验收完成，不阻塞技术 gate。
