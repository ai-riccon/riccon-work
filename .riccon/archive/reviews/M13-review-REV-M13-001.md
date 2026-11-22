---
review_id: REV-M13-001
module_id: M13
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M13_TASK-0052_20261119
  model: deepseek-v4.1-flash
  independent: true
review_kind: module
verdict: pass
reviewed_evidence:
  - EV-0350
  - EV-0351
  - EV-0352
  - EV-0353
  - EV-0354
  - EV-0355
  - EV-0356
  - EV-0357
  - EV-0358
  - EV-0359
  - EV-0361
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
    - EV-0354
    - EV-0361
  build:
    - EV-0352
  tests:
    - EV-0354
  coverage:
    - EV-0355
  e2e:
    - EV-0361
  docs:
    - EV-0353
findings:
  - id: F-001
    severity: P2
    status: accepted
    title: "SessionInfo/UserSummary 后端未下发 departmentId/departmentName"
    evidence: "前端以 /departments 与可选 UserSummary.departmentId 兜底；当前成员接口只返回本人部门，M13 E2E 覆盖成员/未分配/停用/租户管理员四态。后续契约对齐由 M04/M10 承接。"
  - id: F-002
    severity: P3
    status: accepted
    title: "没有独立的工作空间产物列表；产物只从报告任务详情进入"
    evidence: "Product R-M03-03 要求的是部门报告可见可打开，已由研究页空间选择与任务详情满足；独立产物列表缺口留待后续模块。"
  - id: F-003
    severity: P3
    status: accepted
    title: "重命名 409/403/404 的 E2E 使用 stub 场景，而非真实后端触发"
    evidence: "前端错误映射由组件/页面测试与 R-M03-02 E2E 覆盖；服务端空间授权与 403/404 边界由 M03 DEPT 套件覆盖，真实栈不跑该 UI 分支。"
  - id: F-004
    severity: P2
    status: accepted
    title: "历史 TENANT 空间保持全租户可见，M03/M13 不提供转换"
    evidence: "ADR-0018 §2.1/§8 R-11；M13 E2E 验证 TENANT 无重命名入口，转换不在本补丁范围。"
  - id: F-005
    severity: P3
    status: accepted
    title: "PRODUCT 的 AC-07 演员、AC-21 分段、AC-03 历史数据口径仍为原始文本"
    evidence: "ADR-0018 §8 与 TASK-0033 评审已登记；本补丁不修改 PRODUCT，不声称已解决。"
  - id: F-006
    severity: P3
    status: accepted
    title: "AntD Select 键盘关闭后隐藏 listbox 缺少 accessible name"
    evidence: "仅在扫描前先打开/关闭工作空间 Select 时触发 aria-input-field-name；合同 a11y 8/8 在初始态通过。后续设计系统或 AntD 升级统一处理，不阻塞 M13。"
created_at: 2026-11-19T01:55:00+08:00
---

# M13 模块评审：M03 验收补丁

## 结论

**verdict: pass。** TASK-0051 的 R-M03-01/02/03 已在最终代码 `6564879` 上独立复验；E2E、a11y、完整合同 build/test/coverage/test_e2e/security 与真实栈 J1/J2/M03 checks 均通过。无开放 P0/P1。

M13 可进入 gate 前置收口：Lead 需将 TASK-0051（`end_commit: 410cddd`）与 TASK-0052 关闭为 done 后运行 `riccon gate check --module M13`；本任务不自行改模块/任务状态。

## 合同命令证据

| DoD/命令                                                                      | EV      | 结果                                                                                                |
| ----------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------------------------------------- |
| build：`./mvnw -q -T1C -DskipTests package && pnpm -w build`                  | EV-0352 | exit 0，`dirty=false`                                                                               |
| test：`./mvnw -q -T1C verify && pnpm -w test`                                 | EV-0354 | exit 0；Java **628 tests**（api 316 / worker 263 / platform 49），frontend **212 tests**，0 skipped |
| coverage：`./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage` | EV-0355 | exit 0；Java 行 **89.75%** / 分支 **72.21%**；frontend 行 **87.67%** / 分支 **80.51%**              |
| test_e2e：`docker compose ... up -d --wait && pnpm -w e2e` | EV-0361 | exit 0；**26/26** Playwright（含 M13 3 条、M03 4 条、M02 全部回归）                                 |
| a11y：`pnpm -w a11y`                                                          | EV-0357 | exit 0；**8/8**，无 serious/critical                                                                |
| security：Trivy fs vuln/secret/misconfig                                      | EV-0358 | exit 0；无未抑制 finding；例外有 ADR-0011 依据                                                      |
| real-stack：`REAL_EVIDENCE_TASK=TASK-0052 node scripts/real-stack/run.mjs`    | EV-0359 | exit 0；browserE2e **10/10**、J2、security、m03Checks 全部 passed                                   |
| docs：`git diff --check`                                                      | EV-0353 | exit 0                                                                                              |

`reviewed_evidence` 覆盖 TASK-0051 最新 EV（EV-0351）与 TASK-0052 全部合同命令 EV（EV-0352..0359）。

## R-M03-01..03 与 AC 对账

| 补丁项 / AC           | 结论         | 独立证据                                                                                                                                                                             |
| --------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| R-M03-01（M03-AC-01） | **pass**     | EV-0361：成员端显示“所属部门：销售部”；未分配账号显示“未分配部门”；停用部门显示“综合部 + 已停用”；租户管理员显示“角色：租户管理员”且不误报销售部。375px 无横向滚动、axe 通过         |
| R-M03-02（M03-AC-02） | **pass**     | EV-0361：可管理空间重命名成功，列表与工作空间切换器更新；409/403/404 均有中文文案；TENANT 历史空间无重命名入口；普通成员无“工作空间共享”入口                                         |
| R-M03-03（M03-AC-04） | **pass**     | EV-0361：从 chat 部门空间进入 `/research/tasks?workspaceId=ws-sales-project`，研究页选中销售部空间，部门报告可见并可打开；默认个人空间不显示部门报告；他部门成员列表/详情 API 均 404 |
| M03-AC-03/05/06/07    | pass（回归） | EV-0361 全量 E2E；EV-0354 M03 DEPT 套件                                                                                                                                              |
| M03-AC-19/20          | pass（回归） | EV-0354 DEPT-001..010、TEN-001..027，0 skip                                                                                                                                          |
| M03-AC-21             | pass         | EV-0357 8/8；EV-0361 M13 375px/键盘段                                                                                                                                                |
| M03-AC-08..18         | 不适用       | 归 M10，未冒充 M13/M03 交付                                                                                                                                                          |

## DoD 结论

- acceptance/build/tests/coverage/e2e/docs：合同命令均 exit 0，且 `dod_evidence` 命令与合同完全匹配。
- review/evidence：本评审独立完成，`reviewed_evidence` 覆盖 M13 两个任务最新 EV。
- 覆盖率达到合同 80/70 门槛；Java 聚合行 89.75%、分支 72.21%；前端行 87.67%、分支 80.51%。

## 残余风险与承接

- **F-001（P2 accepted）**：后端 `SessionInfo/UserSummary` 未下发 `departmentId/departmentName`；当前由 `/departments` + 可选字段兜底。成员接口目前仅返回本人部门，故 M13 可用；后续契约/后端对齐由 M04/M10 承接，不能声称后端已下发。
- **F-002（P3 accepted）**：没有跨空间的独立产物列表，产物仍从报告任务详情进入；R-M03-03 的“部门报告可见可打开”已满足。
- **F-003（P3 accepted）**：重命名 409/403/404 的 UI 文案由 stub 场景覆盖；真实服务端授权边界由 M03 DEPT 套件覆盖，真实栈不覆盖该 UI 错误分支。
- **F-004（P2 accepted）**：历史 TENANT 空间全租户可见且不可转换（R-11）；本补丁只验证无重命名入口。
- **F-005（P3 accepted）**：PRODUCT 文档漂移（AC-07 演员、AC-21 分段、AC-03 历史数据口径）仍未改，ADR-0018 §8 登记，不在 M13 修复。
- **F-006（P3 accepted）**：键盘打开/关闭 AntD 工作空间 Select 后，隐藏 listbox 缺少 accessible name；合同 a11y 8/8 在初始态扫描通过，键盘路径本身已验证；后续设计系统/AntD 升级统一处理。
- 知识库仍为租户级共享（R-01）；M13 未改变。

## Gate 就绪

- 合同命令与真实栈均通过，EV 全部 `dirty=false`，无 P0/P1。
- 需 Lead 关闭 TASK-0051（`end_commit 410cddd`，使 apps/web 提交进入模块 scope）和 TASK-0052 后运行 `riccon gate check --module M13`；否则 task 状态/`module-scope-unclaimed` 检查会失败。
- `state validate` 在本报告写入前已通过；M03-V1-report 的 `reviewer` 元数据已补齐，不改历史结论。
