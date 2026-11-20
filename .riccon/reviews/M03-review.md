---
review_id: REV-M03-002
previous_review: REV-M03-001
module_id: M03
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e08d1b473ffezoltlVNmpba9tZ
  model: deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: cb977f98a493bfb7db9f9d4b02731ac62e195053
verdict: pass
reviewed_evidence:
  - EV-0306
  - EV-0307
  - EV-0312
  - EV-0313
  - EV-0314
  - EV-0315
  - EV-0316
  - EV-0317
  - EV-0318
  - EV-0319
  - EV-0320
  - EV-0321
  - EV-0323
  - EV-0337
  - EV-0341
  - EV-0342
  - EV-0343
  - EV-0344
  - EV-0345
  - EV-0346
  - EV-0347
  - EV-0348
  - EV-0349
  - EV-0492
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
    - EV-0344
    - EV-0346
  build:
    - EV-0342
  tests:
    - EV-0492
  coverage:
    - EV-0492
  e2e:
    - EV-0346
  docs:
    - EV-0343
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "部门管理页 axe serious 对比度与可见文本/accessible name 不一致"
    evidence: "EV-0347：pnpm -w a11y 8/8，无 serious/critical"
  - id: F-002
    severity: P1
    status: fixed
    title: "缺少打开部门空间的用户旅程（M03-AC-03/04）"
    evidence: "EV-0346：E2E-M03-02 切换部门空间、会话/文件加载并继续提问"
  - id: F-003
    severity: P2
    status: fixed
    title: "负责人未显式加入 CUSTOM 时刷新后看不到可管理空间"
    evidence: "EV-0346：CUSTOM 未把负责人加为显式成员，刷新后仍可见并可管理"
  - id: F-004
    severity: P2
    status: fixed
    title: "平台管理端没有租户管理导航入口"
    evidence: "EV-0346：从 /admin/overview 点击“租户管理”进入租户列表"
  - id: F-005
    severity: P3
    status: fixed
    title: "DEPT/TEN 完整性守卫依赖 DisplayName，surefire XML 不含编号"
    evidence: "EV-0344：SUITE-MANIFEST + 反射断言；完整性守卫无缺失 ID"
  - id: F-006
    severity: P3
    status: accepted
    title: "J2 产物正文与引用区存在两个“引用清单”标题"
    evidence: "e2e/real/tests/j2.spec.ts 已精确定位 #artifact-citations-title；产品语义留待后续收敛"
  - id: F-007
    severity: P2
    status: accepted
    title: "PRODUCT 文档漂移：AC-07 演员、AC-21 分段、AC-03 历史数据口径"
    evidence: "ADR-0018 §8 / REV-TASK-0033-002 F-003/F-004/F-005 已登记；PRODUCT 未改，不能声称已解决"
  - id: F-008
    severity: P2
    status: accepted
    title: "历史 TENANT 空间保持全租户可见，M03 不提供转换"
    evidence: "ADR-0018 §2.1/§8 R-11；M03 E2E 使用无历史 TEAM 空间租户 fixture"
  - id: F-009
    severity: P2
    status: accepted
    title: "平台来源部门审计事件对目标租户不可见"
    evidence: "ADR-0018 §5/§8；M10 需显式授权查询模型承接"
  - id: F-010
    severity: P3
    status: accepted
    title: 'M02 TenantIsolationTest 未使用 @Tag("tenant-isolation")'
    evidence: "full verify 仍执行该测试；tag 过滤不会选中，后续测试整理补 tag"
  - id: REVALIDATION
    severity: P3
    status: fixed
    title: "2026-11-20 用当前内核按合同 commands.test 重录 test/coverage；旧 review 归档为历史"
    evidence: "EV-0492；旧 review REV-M03-001 归档于 .riccon/archive/reviews/M03-review-REV-M03-001.md"
created_at: "2026-11-20T13:10:00+08:00"
---

# M03 模块评审：权限纵深与组织模型

## 0. 回溯说明（2026-11-20）

- **回溯原因**：DEC-0022 合并后合同 `commands.test` 改为 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`；本模块旧 test EV（EV-0344）命令文本不再匹配，`gate check` 在 2026-11-20 以 `EV-0344 command does not match commands.test` 失败。代码未回归，属于证据/流程漂移。
- **本次动作**：在当前 HEAD `cb977f9`（工作树干净）上用合同 `commands.test` 全量重跑一次，并按项目规则同时作为 `test` 与 `coverage` 证据：**EV-0492**（挂 TASK-0033），exit 0，`commit=cb977f98a493bfb7db9f9d4b02731ac62e195053`，`dirty=false`。
- **本次实测**：Java 765 tests（api 453 / worker 263 / platform-common 33 / arch 12 / domain 4），failures 0、errors 0、skipped 0；前端 46 文件 384 tests passed、0 skipped；前端覆盖率 statements 87.8% / branches 80.75% / functions 84.63% / lines 87.71%；Maven `verify` 含 `jacoco:check` 通过。
- **评审边界**：本次只重录 test/coverage；M03 E2E、a11y、security、真实栈检查未重跑，仍绑定旧 EV（见正文“合同命令证据”表），原结论不扩大。旧 review `REV-M03-001` 原样归档在 `.riccon/archive/reviews/M03-review-REV-M03-001.md`。
- **findings 处理**：旧 findings 全部保留，状态未改（P1 均已 fixed，P2/P3 为 accepted）；正文为旧评审原文，当前状态以 front matter 为准。


## 结论

**verdict: pass。** 在最终提交 `bc97d1c` 上完成合同 build/test/coverage/test_e2e/a11y/security 与真实栈 J1/J2 + M03 检查；M03-AC-01..07、19 部门段、20、21 均有执行证据。无开放 P0/P1。

M03 gate **可以运行**，前置：Lead 按流程把 TASK-0050 从 `review` 关闭为 `done`（本 verifier 任务按指令不自行置 done）；模块状态保持 `building` 是 gate 允许的输入。

## 合同命令证据

| DoD/命令                                                                                         | EV      | 结果                                                                                        |
| ------------------------------------------------------------------------------------------------ | ------- | ------------------------------------------------------------------------------------------- |
| build：`./mvnw -q -T1C -DskipTests package && pnpm -w build`                                     | EV-0342 | exit 0，dirty=false                                                                         |
| test：`./mvnw -q -T1C verify && pnpm -w test`                                                    | EV-0344 | exit 0；Java 628 tests（api 316 / worker 263 / platform 49），frontend 200 tests，0 skipped |
| coverage：`./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                    | EV-0345 | exit 0；Java 行 89.75% / 分支 72.21%；frontend 行 87.79% / 分支 80.76%                      |
| test_e2e：`docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e` | EV-0346 | exit 0；23/23 Playwright                                                                    |
| a11y：`pnpm -w a11y`                                                                             | EV-0347 | exit 0；8/8，无 serious/critical                                                            |
| security：Trivy fs vuln/secret/misconfig                                                         | EV-0348 | exit 0；报告表无未抑制 finding；`.trivyignore` 两个 CVE 例外见 ADR-0011（exp 2027-03-31）   |
| real-stack：`REAL_EVIDENCE_TASK=TASK-0050 node scripts/real-stack/run.mjs`                       | EV-0349 | exit 0；compose/seed/browserE2e（10/10）/J2/securityChecks/m03Checks 全部 passed            |
| docs：`git diff --check`                                                                         | EV-0343 | exit 0                                                                                      |

所有 EV 均为 `manual=false`、`dirty=false`，录制 commit `bc97d1c`，且是最终提交的祖先。

## AC 对账

| AC                      | 结论   | 证据                                                                           |
| ----------------------- | ------ | ------------------------------------------------------------------------------ |
| M03-AC-01               | pass   | EV-0346 E2E-M03-01：平台管理员创建部门、加/移成员、任命负责人；EV-0344 IT/DEPT |
| M03-AC-02               | pass   | EV-0346 E2E-M03-02：负责人创建/改可见性/CUSTOM 成员，普通成员无管理入口        |
| M03-AC-03               | pass   | EV-0346：切换部门空间后显示该空间会话；DEPT-001                                |
| M03-AC-04               | pass   | EV-0346：部门空间会话/文件加载并继续提问；DEPT-002/003                         |
| M03-AC-05               | pass   | DEPT-004：同部门成员访问个人空间 404                                           |
| M03-AC-06               | pass   | EV-0346：知识库“全租户共享”固定文案、源文件 404 文案；DEPT-010                 |
| M03-AC-07               | pass   | DEPT-006/007/008：部门停用、成员移出、负责人移交下一次请求生效                 |
| M03-AC-19（部门段）     | pass   | DEPT-001..010，零 skip、零 xfail                                               |
| M03-AC-20               | pass   | TEN-001..027，零 skip；完整性清单覆盖全部编号                                  |
| M03-AC-21（部门管理页） | pass   | EV-0346 375px/键盘；EV-0347 axe 无 serious/critical                            |
| M03-AC-08..18           | 不适用 | OIDC/账号绑定/审计查询/导出归 M10，未在 M03 冒充                               |

## DoD 结论

- acceptance：AC-01..07、19 部门段、20、21 通过；AC-08..18 明确归 M10。
- build/tests/coverage/e2e：合同命令均 exit 0，覆盖率高于合同 line 80 / branch 70。
- review/evidence：本评审独立完成；`reviewed_evidence` 覆盖 M03 每个任务最新 EV。
- docs：`git diff --check` 通过；PRODUCT 漂移已登记为 F-007，不冒充已修复。
- security：Trivy 无未抑制高/严重问题；例外有 ADR 依据且带到期日。

## 残余风险与承接

- **F-006（P3）**：J2 产物正文与引用区重复“引用清单”标题；测试已精确定位，产品语义后续收敛。
- **F-007（P2 accepted）**：PRODUCT 的 AC-07 演员、AC-21 分段、AC-03 历史数据口径仍为原始文本；ADR-0018 §8 与 TASK-0033 评审已登记，M03 评审包引用该口径，不声称 PRODUCT 已改。
- **F-008（P2 accepted）**：历史 `TENANT` 空间保持 M02 全租户可见面，M03 不提供转换；R-11 登记，M04/M09 设计专用迁移。
- **F-009（P2 accepted）**：平台来源部门审计事件目前只写 `platform` 租户 + `details.targetTenantId`；目标租户可见模型由 M10 承接。
- **F-010（P3 accepted）**：M02 `TenantIsolationTest` 未加 `tenant-isolation` tag；full verify 仍执行，tag 过滤不会选中，后续测试整理补齐。
- 知识库仍为租户级共享（R-01），界面已固定文案；文档级 ACL 属 M05。

## Gate 就绪

- 合同命令证据齐全且全部 exit 0；`dod_evidence` 的 build/tests/coverage/e2e 命令均与合同对应项匹配。
- `reviewed_evidence` 覆盖 M03 每个任务最新 EV（B1a/B2/B3/B4/B1b/B5/V1/V2）。
- 无开放 P0/P1；P2/P3 均有承接位置。
- 预期 Lead 将 TASK-0050 置 `done` 后执行 `riccon gate check --module M03`。
