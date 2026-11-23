---
review_id: REV-M23-001
module_id: M23
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dfb83bfaeffeHE3t1fEmJrlE5i
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: 3ae6335a7215e3adc68744467dcfb71943ebd790
reviewed_commits:
  - 979248cbbed7ed1a791081829d047a3d810916e6
  - 3ae6335a7215e3adc68744467dcfb71943ebd790
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
    - EV-0770
    - EV-0815
    - EV-0816
  build:
    - EV-0814
  tests:
    - EV-0815
  coverage:
    - EV-0815
  e2e:
    - EV-0816
  docs:
    - EV-0817
findings:
  - id: F-001
    severity: P3
    status: accepted
    title: "E2E stub 未下发 departmentId/departmentName；四态 UI 仅走 /departments 兜底，新会话字段未被浏览器路径覆盖"
    owner: "riccon/builder-web / riccon/builder-identity"
    due: "M05 gate 前"
    accepted_reason: >-
      e2e/stub/server.mjs 的 sessionInfo 未带部门字段，但 M13 R-M03-01 仍通过，因为
      use-workspace-management 先按 session.user.departmentId 匹配 /departments，缺 ID 时才取第一条；
      stub 的 /departments 种子提供了销售部、停用状态与未分配空列表，所以成员/未分配/停用/租户管理员
      四态用户结果没有回归。后端字段本身由 SessionDepartmentApiTest 4 例与
      UserSummaryDepartmentContractTest 3 例在真实 MySQL + HTTP 链路上直接覆盖；缺口只是浏览器层
      没有证明“仅凭会话字段也足够”。补 stub 字段并加一条只读 session 的 E2E 属测试保真度增强，
      不改变 M23 的产品结论，也不影响 M13 已验收结果。
    evidence: "e2e/stub/server.mjs:864-878；apps/web/src/services/workspaces/use-workspace-management.ts:103-112；EV-0770；EV-0816"
  - id: F-002
    severity: P3
    status: accepted
    title: "前端展示仍以 /departments 为主部门名称/停用状态来源；SessionInfo.departmentName 未被消费，AC1 的“前端不再必须依赖”只完成 ID 选择收口"
    owner: riccon/builder-web
    due: "M05 gate 前"
    accepted_reason: >-
      currentDepartment 先用 session.user.departmentId 在 /departments 结果中按 ID 精确匹配，避免取错
      第一条；徽标仍从 Department 对象读取 name/status。后端现在保证下发 departmentId/departmentName
      （未分配为 null），因此“识别主部门”不再依赖位置兜底；但停用状态、可管理空间列表仍需要
      /departments，且 apps/web/src/services/api/types.ts:40 的“服务端可能暂未下发，界面以 /departments
      为准”注释已过期。M13 F-001 的核心后端缺口已关闭，四态用户可见结果不变；完全去掉列表依赖需要
      把部门状态纳入会话契约，属后续契约扩展，不宜在 M23 夹带。
    evidence: "apps/web/src/services/workspaces/use-workspace-management.ts:96-112；apps/web/src/services/workspaces/current-department-badge.tsx:41；apps/web/src/services/api/types.ts:38-44"
  - id: F-003
    severity: P3
    status: accepted
    title: "TASK-0163 属认证/身份高风险变更，但 done 前没有任务级独立 review；模块 review 在 done 后才覆盖"
    owner: riccon/lead
    due: "M05 gate 前"
    accepted_reason: >-
      TASK-0163 修改 UserAccount、SessionInfo/UserSummary 与认证/管理端查询，属于认证与身份面高风险任务；
      流程要求 done 前有独立 Verifier 结论，当前只有本模块 review（任务已 done 后）。本评审在同一 HEAD
      独立阅读 diff、重跑定向契约/四态测试、全量 test+coverage 与 E2E，实质覆盖了该任务验收，代码结论
      不受影响；但该流程例外应由 Lead 明确记录/接受，后续身份类任务不得复用此路径。
    evidence: ".riccon/tasks/TASK-0163.json（status=done，无任务级 review）；本评审 §3/§4"
reviewed_task_reviews: []
evidence:
  - EV-0770
  - EV-0814
  - EV-0815
  - EV-0816
  - EV-0817
reviewed_evidence:
  - EV-0770
  - EV-0814
  - EV-0815
  - EV-0816
  - EV-0817
notes: "M23（fix M13）独立验证：UserAccount/SessionInfo/UserSummary 实际下发部门字段，M13 F-001 关闭；4 个新增 HTTP 集成用例 + 3 个契约用例通过；全量 Java 220 类/1247 用例/0/0/0、web 57/493；全量 E2E 40/40（含 M13 四态）；build/lint 通过；a11y EV-0811 15/15 与 security EV-0812 0/0/0 在同一 HEAD 由 M24 全量运行覆盖。无 P0/P1；P3 残余为 stub/E2E 保真度、前端仍依赖 /departments、任务级 review 流程。本评审是独立技术验收，不替代 Product 预验收或用户最终验收。"
created_at: "2026-11-23T09:20:00+08:00"
---

# REV-M23-001：M23（fix M13）部门字段契约对齐的模块独立评审

## 结论

**pass（附 P3 残余 F-001/F-002/F-003，无 P0/P1）**。在最终提交 `3ae6335`（M23 代码提交 `979248c`，`3ae6335` 只含 .riccon closeout）上独立复核：

- `SessionInfo.user` 与租户管理员 `/api/v1/users` 的 `UserSummary` 已在真实 MySQL + HTTP 链路上实际下发 `departmentId`/`departmentName`；未分配为 `null`，停用部门仍返回名称。`contracts/openapi.yaml` 的 `UserSummary` 本就声明这两个可空字段，实现与契约一致，M23 未改契约。
- 新增 `SessionDepartmentApiTest` **4/4**、`UserSummaryDepartmentContractTest` **3/3**，并回归 AuthFlow/OIDC/TenantIsolation 等 8 个目标类（EV-0770）。
- 合同 build（EV-0814）、test+coverage（EV-0815）、E2E（EV-0816，**40/40**，含 M13 R-M03-01 四态）、lint/typecheck（EV-0817）全部 exit 0、dirty=false。
- Java **220 类 / 1247 用例 / 0 failure / 0 error / 0 skip**；web **57 文件 / 493 用例**；覆盖率 api 92.47%/75.55%、worker 88.22%/71.99%、platform-common 90.83%/75.41%、platform-domain 96.51%、web 87.01%/80.01%（line/branch），全部过 80/70。
- M13 F-001 的“后端未下发部门字段”缺口关闭；四态 UI 与既有 M13 E2E 无回归；未改变 M03/M13 已验收 AC。
- a11y（EV-0811，15/15）与 security（EV-0812，0 漏洞/0 密钥/0 错配）在同一 HEAD 由 M24 全量运行覆盖；因 strict gate 只允许本模块任务证据进入 M23 的 `dod_evidence`，这两条仅在正文引用，不列入 `reviewed_evidence`。

本评审是独立技术验收，**不替代** Product 预验收或用户最终验收；M23 状态、Product/用户验收与 gate 重跑由 Lead 处理。

## 1. 变更范围与边界

`git diff --name-status da9b459..979248c` 共 10 个文件：

| 区域     | 文件                                                                    | 判断                                          |
| -------- | ----------------------------------------------------------------------- | --------------------------------------------- |
| 身份领域 | `UserAccount.java`（+departmentId/departmentName，兼容构造器默认 null） | 纯增量；响应路径使用规范构造器                |
| 会话 DTO | `UserSummaryResponse.java`（+两字段，新增规范构造器）                   | 未改 required；null 正常序列化                |
| 会话入口 | `AuthController.sessionInfo`、`AuthService.refresh`                     | 登录/会话摘要传递部门字段                     |
| 查询     | `UserAccountJdbc`、`AdminJdbc`、`OidcBindingJdbc`                       | 同租户 `LEFT JOIN department`；未分配仍可查询 |
| 管理端   | `TenantUserAdminService.toSummary`/updateStatus/withIdentity            | 账号列表与状态变更响应传递部门字段            |
| 测试     | `SessionDepartmentApiTest`、`UserSummaryDepartmentContractTest`         | 新增 7 个用例                                 |

没有迁移、OpenAPI、部署配置、前端源码或用户可见文案改动；变更只向既有响应增加字段，不移除/重命名任何字段，因此 M03/M13 已验收 AC 的输入与行为不变。

## 2. AC 对账

| AC 原文                                                                                                                                       | 结论                                                                                                                                   | 证据                                                      |
| --------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| SessionInfo.user 与 UserSummary 实际下发 departmentId/departmentName（未分配为 null），OpenAPI 与实现一致；前端不再必须依赖 /departments 兜底 | **pass（with residual F-002）**：后端字段、null 语义与契约全部成立；前端“按 ID 选择”已不依赖位置兜底，但名称/停用状态仍读 /departments | EV-0770；EV-0815；`UserSummaryDepartmentContractTest` 3/3 |
| 成员/未分配/停用/租户管理员四态 UI 与既有 E2E 不回归                                                                                          | **pass**                                                                                                                               | EV-0816 40/40，含 M13 R-M03-01 四态；EV-0815 web 493 用例 |
| 受影响测试与契约校验通过；不改变 M03/M13 已验收的 AC 结果                                                                                     | **pass**                                                                                                                               | EV-0770 定向 8 类；EV-0815 全量；无契约/UI/既有字段变更   |

AC1 的残余说明：后端契约已满足，四态结果不变；但 `CurrentDepartmentBadge` 仍从 `/departments` 的 `Department` 对象读取名称与停用状态（F-002），且 stub E2E 未下发新字段（F-001）。因此不写成无残余的 pass。

## 3. 全量验证

| 项                            | 结果                                                                                                                                              | EV                   |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------- |
| 定向契约/四态                 | exit 0；`SessionDepartmentApiTest` 4/4、`UserSummaryDepartmentContractTest` 3/3，另有 AuthFlow/OIDC/IdentityProviderResolver/TenantIsolation 回归 | **EV-0770**          |
| 合同 build                    | exit 0，32s                                                                                                                                       | **EV-0814**          |
| 合同 test+coverage（同一 EV） | exit 0，682s；Java 220 类/1247 用例/0/0/0；web 57/493                                                                                             | **EV-0815**          |
| 合同 E2E                      | exit 0，276s；**40 passed**（含 R-M03-01/02/03）                                                                                                  | **EV-0816**          |
| lint/typecheck                | exit 0；spotless、eslint `--max-warnings=0`、prettier、`tsc --noEmit` 全通过                                                                      | **EV-0817**          |
| a11y（T4，共享）              | exit 0；**15 passed**；与 M24 同一 HEAD                                                                                                           | EV-0811（TASK-0184） |
| security（T4，共享）          | exit 0；依赖漏洞/密钥/错配全 0                                                                                                                    | EV-0812（TASK-0184） |

覆盖率（EV-0815 的 JaCoCo/vitest 报告）：

| 范围            |   Line | Branch | 门槛  | 结论 |
| --------------- | -----: | -----: | ----- | ---- |
| apps/api        | 92.47% | 75.55% | 80/70 | pass |
| apps/worker     | 88.22% | 71.99% | 80/70 | pass |
| platform-common | 90.83% | 75.41% | 80/70 | pass |
| platform-domain | 96.51% | 无分支 | 80/70 | pass |
| apps/web        | 87.01% | 80.01% | 80/70 | pass |

证据卫生：EV-0815 日志末尾出现既有的 `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)` 警告（M21 评审已登记同类现象）；命令 exit 0，surefire 报告 0 failure/0 error/0 skip，不作为失败，但建议后续统一处理 fork 退出。

## 4. 验证了什么（独立复验）

- **字段链路**：`UserAccountJdbc` 与 `AdminJdbc` 的 `USER_COLUMNS` 都包含 `u.department_id, d.name AS department_name`，`LEFT JOIN department ON d.tenant_id = u.tenant_id AND d.id = u.department_id`，未分配仍返回行；`mapUser` 读取两列。`OidcBindingJdbc` 的管理端绑定查询同步。
- **响应路径**：grep `new UserSummaryResponse(` 仅 `AuthController` 与 `TenantUserAdminService` 两处生产调用，均使用规范 12 参构造器；兼容构造器只被测试使用。grep `new UserAccount(` 的响应路径（AuthService refresh、TASK TUA update/withIdentity）均显式传递部门；仅 bootstrap/create-new/OIDC 登录校验使用旧构造器，符合“未分配/不展示”语义。
- **契约**：`UserSummaryDepartmentContractTest` 读取仓库 `contracts/openapi.yaml`，断言 `type: [string, "null"]`、`format: uuid`、两字段不在 required、`SessionInfo.user` `$ref` 到 `UserSummary`；并断言未分配时 JSON 包含字段且值为 null（不是省略/空串）。
- **HTTP 行为**：`SessionDepartmentApiTest` 用真实租户/部门/成员 HTTP API 构造四态，断言 `/auth/session` 与 `/api/v1/users` 的字段值；停用部门后名称仍返回。
- **回归**：全量 test 与 E2E；M13 R-M03-01 四态、R-M03-02/03 通过。

## 5. 未验证 / 剩余风险

- **性能**：未重跑 k6。M23 在用户/账号查询上新增同租户 PK 左连接，没有新端点或 N+1；M04 稳态 API p95 61.8ms 对 500ms 预算有较大余量。若后续量测显示回归，再按合同 perf 处理；本轮不据此阻断。
- **OIDC 会话 + 部门**：OIDC 登录处理器只做身份校验与 principal 建立，后续 `/auth/session` 走同一 `UserAccountJdbc` 查询；没有专门的 OIDC 用户部门 E2E。逻辑上与本地账号共用同一条查询，风险低。
- **E2E 保真度**：stub 未下发部门字段，浏览器路径未单独证明新字段；见 F-001。
- **前端依赖**：名称/停用状态仍来自 `/departments`；见 F-002。
- **流程**：TASK-0163 无任务级 review；见 F-003。
- 未验证真实浏览器中的部门名超长/特殊字符渲染；React 转义与既有部门页面测试覆盖，风险低。

## 6. 操作提示（Lead）

- 本评审绑定 `head_commit: 3ae6335`；EV-0814..0817 均为该提交、干净树、exit 0。
- 本地 `riccon gate check --module M23`（2026-11-23T09:20）除 wall_clock 外全部通过：review/证据归属/DoD 绑定/模块任务均 green；唯一错误是 `module wall-clock 13.3h exceeds 6.0h`。同一 warning 里的 `EV-0571 is manual` 是全局历史证据，不属于 M23。
- M23 的全量证据在 `TASK-0163` 下单独录制；M24 的全量证据在 `TASK-0184` 下录制（EV-0808..0813）。两者同一 HEAD，但 strict gate 的模块归属校验不允许把一个模块的 EV 直接写进另一个模块的 `dod_evidence`，因此不能真正“共用一条 EV”；本评审已按模块归属分别绑定。
- **wall_clock 风险**：M23 `started_at=2026-11-22T20:01+08:00`、限额 6h；本轮验证完成时已约 13h。`riccon gate check --module M23` 会报 `module wall-clock ... exceeds 6h`。这是等待/会话插曲造成的预算超时，不是代码失败；Lead 需用 `--allow-overrun` 或记录预算决策后再判 gate，不能把 wall_clock 失败当作验收失败。
- 跑 `riccon gate check --module M23` 前，Product 预验收仍需按流程完成；本评审不代替 Product/用户结论。
