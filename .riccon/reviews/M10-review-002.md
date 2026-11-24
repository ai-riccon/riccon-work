---
review_id: REV-M10-003
previous_review: REV-M10-002
module_id: M10
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_df2728ecbffemLxQuSwhpsQOMZ
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: 9a5651efca5b9b13e06888cd31da11de0b445559
reviewed_commits:
  [a88d003, 864b0a7, de33ed8, cb977f9, e07f35b, 7f96a2e, 9a5651e]
verdict: pass
reviewed_evidence:
  - EV-0395
  - EV-0396
  - EV-0398
  - EV-0402
  - EV-0397
  - EV-0408
  - EV-0409
  - EV-0418
  - EV-0406
  - EV-0413
  - EV-0411
  - EV-0412
  - EV-0415
  - EV-0424
  - EV-0427
  - EV-0433
  - EV-0425
  - EV-0426
  - EV-0432
  - EV-0430
  - EV-0434
  - EV-0435
  - EV-0436
  - EV-0437
  - EV-0438
  - EV-0443
  - EV-0440
  - EV-0439
  - EV-0448
  - EV-0493
  - EV-1015
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
    - EV-0448
    - EV-0440
  build:
    - EV-0435
  tests:
    - EV-0493
  coverage:
    - EV-0493
  e2e:
    - EV-0440
  docs:
    - EV-0437
findings:
  - id: F-016
    severity: P1
    status: fixed
    title: "REV-M10-OPS-001 OPS-001..004 已由后续实现/文档修复"
    summary: "OPS-001 CI/RUNBOOK 结构断言、OPS-002 worker schema 常量、OPS-003 OIDC secret 脱敏、OPS-004 协调切换方案均已在 HEAD 9a5651e 复核并录 EV-1015。OPS-004 的生产完整编排仍是 M09/release 前置。"
    evidence: "REV-TASK-0064-002；EV-1015；7f96a2e；e07f35b；2edfa6c"
  - id: F-017
    severity: P1
    status: fixed
    title: "REV-TASK-0065-002 F-001 已由 TASK-0068 收口"
    summary: "RoleDefinitions 两条导出审批权限（TENANT_ADMIN += audit.export.approve、PLATFORM_ADMIN += platform.audit.export.approve）由 B3a e07f35b 完成，REV-TASK-0068-001 独立确认；V10 回填、RoleDefinitionsTest、V10MigrationTest 均通过。"
    evidence: "REV-TASK-0068-001；REV-TASK-0065-003；EV-0409；EV-1015"
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/architect + riccon/lead
    due: M09 gate 前
    accepted_reason: "V2 的 external_issuer/external_subject 仍为 utf8mb4_0900_ai_ci，大小写/重音变体可能命中同一身份；真实栈 caseVariantSubjectMatched=true。修复需后续迁移或 ADR 书面风险接受，M10 不声称已修。"
    title: "OIDC 身份键 collation 弱于精确匹配（B2 F-001 residual）"
    evidence: "TASK-0069-review F-001；真实栈 caseVariantSubjectMatched=true；docs/ACCEPTANCE.md:136"
  - id: F-002
    severity: P2
    status: fixed
    title: "审计只读路径错误方法返回 500"
    evidence: "TASK-0077/0082；EV-0433/EV-0434；真实栈 405"
  - id: F-003
    severity: P2
    status: fixed
    title: "用户管理页无导航入口"
    evidence: "TASK-0080；EV-0431/EV-0432"
  - id: F-004
    severity: P2
    status: fixed
    title: "B4 缺浏览器级 OIDC/绑定/a11y"
    evidence: "E2E-M03-03/04/07；EV-0440；EV-0448"
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/architect
    due: M09 gate 前
    accepted_reason: "与 ADR-0019 §5 并发会话口径一致，受 30 分钟空闲超时限制；绑定/解绑不即时撤销既有会话。M10 不声称全局踢出。"
    title: "绑定/解绑不即时撤销既有会话"
    evidence: "ADR-0019 §5；TASK-0069 F-003"
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M09 gate 前
    accepted_reason: "与 ADR-0019 §3/ADR-0020 §6.1 字段清单一致；M03 F-009（平台来源事件对目标租户不可见）继续 residual，需另立跨租户可见模型。"
    title: "平台跨租户绑定审计 details 无 targetTenantId；M03 F-009 residual"
    evidence: "TASK-0069 F-004；ADR-0020 §6.1；docs/ACCEPTANCE.md:137"
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M09 gate 前
    accepted_reason: "真实栈 100 行展示名通过，实现为每页一次批量 IN 查询；未断言 SQL 次数/耗时，缺 N+1 回归护栏。"
    title: "审计 100 行展示名未断言 SQL 次数/耗时"
    evidence: "TASK-0071 F-002；EV-0448"
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M09 gate 前
    accepted_reason: "正常数据由写侧白名单保证；历史/运维直写非法 details 与计数-流式间隙仍可能截断或略超行数。M09/OPS-014 清点与后续加固承接。"
    title: "CSV 中途失败/行数硬上限竞态"
    evidence: "TASK-0071 F-003/F-004；OPS-014"
  - id: F-009
    severity: P3
    status: accepted
    owner: riccon/architect
    due: M09 gate 前
    accepted_reason: "当前只有 TENANT_ADMIN/PLATFORM_ADMIN 拥有 audit.read；未来自定义角色会继承导出能力，审批仍受 audit.export.approve 限制。需架构确认权限模型。"
    title: "导出申请/下载只校验 audit.read"
    evidence: "TASK-0071 F-005；模块评审旧 F-009"
  - id: F-010
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "OidcIdpRegistry 已校验 absolute http(s)、无 query/fragment、去尾斜杠；生产 HTTPS fail-fast 与访问日志脱敏属 M09 部署配置。"
    title: "publicBaseUrl 缺生产 fail-fast"
    evidence: "TASK-0065 F-003；OPS-015"
  - id: F-011
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M09 gate 前
    accepted_reason: "V1 真实栈已补失败路径会话属性扫描与 401；auth.login.failed 细节与旧 CSRF 失效仍未单列断言。"
    title: "失败审计/旧 CSRF 失效断言不完整"
    evidence: "TASK-0065 F-005/F-006；EV-0448"
  - id: F-012
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "OPS-008/009/010/013/014/015 的清理索引、连接占用、OIDC 回滚、平台合规取数、历史 details 清点、publicBaseUrl/日志脱敏均为生产前置；M10 不冒充关闭。"
    title: "OPS-008/009/010/013/014/015 仍属 M09/生产"
    evidence: "REV-TASK-0064-002；docs/M10-OPS-DRILL.md；docs/ACCEPTANCE.md:145-146"
  - id: F-013
    severity: P3
    status: accepted
    owner: riccon/product
    due: M09 gate 前
    accepted_reason: "PRODUCT 的 SEC-AUDIT-001 编号未单独建测试；用户可见内容由 IT-AUDIT-106 与策略端点覆盖。建议后续统一编号，不影响 AC-18 行为。"
    title: "SEC-AUDIT-001 编号未落地"
    evidence: "IT-AUDIT-106；PRODUCT M03-AC-18 测试线索"
  - id: F-014
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M06 gate 前
    accepted_reason: "M10 页面 E2E 在真实栈执行，合同 pnpm -w e2e 仍覆盖 M02/M03 stub 回归；测试拓扑已记录，不用 API 证据替代 UI。"
    title: "M10 页面 E2E 在真实栈而非合同 stub 套件"
    evidence: "EV-0440；EV-0448"
  - id: F-015
    severity: P3
    status: fixed
    title: "ARCHITECTURE 评审状态表已随 REV-M10-ARCH-002 更新"
    summary: "docs/ARCHITECTURE.md 评审状态表现已记录 M10 ADR-0019/0020 的 REV-M10-ARCH-002 pass；ADR 仍为 proposed，属 Lead/用户批准事项，不在本 finding 内。"
    evidence: "docs/ARCHITECTURE.md:7-10"
  - id: REVALIDATION
    severity: P3
    status: fixed
    title: "2026-11-20 用当前内核按合同 commands.test 重录 test/coverage；旧 review 归档为历史"
    evidence: "EV-0493；旧 review REV-M10-001 归档于 .riccon/archive/reviews/M10-review-REV-M10-001.md"
reviewed_task_reviews:
  - REV-M10-ARCH-002
  - REV-M10-OPS-001
  - REV-TASK-0064-002
  - REV-TASK-0065-002
  - REV-TASK-0065-003
  - REV-TASK-0068-001
  - REV-TASK-0069-001
  - REV-TASK-0071-001
  - REV-TASK-0076-001
created_at: "2026-11-24T21:10:00+08:00"
---

# REV-M10-003：M10（身份与审计纵深，M03b）模块复审

## 0. 本轮复审说明（2026-11-24）

- **触发原因**：内核升级到 0.1.15 后，gate 会读取每个任务最新 task review 的 verdict/findings。原 M10 gate 因 `REV-M10-OPS-001` 的 OPS-001..004（P1）与 `REV-TASK-0065-002` 的 F-001（P1 accepted）失败。
- **本轮动作**：在 HEAD `9a5651e` 上独立复核上述 5 条 P1；确认已由后续实现/文档修复后，新增两条 superseding task review（`REV-TASK-0064-002`、`REV-TASK-0065-003`）和本模块 review。没有修改生产源码、测试、迁移、契约或任务/模块状态。
- **本轮新证据**：**EV-1015**（TASK-0064，exit 0，`commit=9a5651e`，`dirty=false`，69.6s）：重放 `V10MigrationTest`、`SchemaVersionCheckerTest`、`RoleDefinitionsTest`、`OidcStartupValidationTest`、`OidcConfigurationParserTest`（38 tests，0 fail/0 error/0 skipped），并 grep 断言 CI/RUNBOOK/schema 常量。
- **未重跑**：合同全量 build/test/coverage/E2E/a11y/security 与真实栈迁移演练。`dod_evidence` 沿用旧模块评审的合同证据（EV-0435 build、EV-0493 test/coverage、EV-0440 E2E、EV-0437 docs）；本轮不使用 EV-1015 冒充全量 test/coverage。旧 review `REV-M10-002` 原样保留。
- **reviewed_task_reviews**：按 `task_id`/`created_at` 取最新，共 7 条（REV-M10-ARCH-002、REV-TASK-0064-002、REV-TASK-0065-003、REV-TASK-0068-001、REV-TASK-0069-001、REV-TASK-0071-001、REV-TASK-0076-001）；front matter 另保留被 supersede 的 REV-M10-OPS-001、REV-TASK-0065-002 以便追溯，不参与 latest 判定。

## 1. 结论

**verdict: pass（模块级）。** M10 的用户可见结果与工程 DoD 仍成立：

- 员工可用企业账号（OIDC 授权码 + PKCE S256 + nonce）登录，会话只落 `RicconPrincipal`，浏览器/响应/日志无 JWT、access/refresh token 或 userinfo 调用；失败路径清理会话并返回稳定原因码；登出本地权威并自动联合登出。
- 管理员可按时间/动作/操作者/对象过滤审计、固定倒序分页、查询自身被审计；导出需另一管理员批准，申请人不能自批，只有申请人可下载有时效的 CSV；平台租户自身导出 403，平台只审批单管理员租户元数据。
- V10 为纯 additive 迁移；一次性容器验证 V1–V9 seed → V10 → 回滚 V9、权限回填、索引单语句 INPLACE/LOCK=NONE、失败注入 repair 重跑；worker 期望 schema 14（V10 时为 10，后续迁移已升版）。
- AC 08–18 全部通过；AC-19 导出请求段、AC-20 跨租户新对象、AC-21 M10 页面段通过；零 skip、零 xfail。

本结论是**独立技术验收**，不是 Product 预验收或用户最终验收。M10 已于 2026-11-20 完成 Product 预验收与用户 accepted（DEC-0021）；本轮只恢复新内核下的 gate 一致性，不改变历史验收结论。

## 2. 本轮增量验证（P1 关闭）

| P1              | 修复物                                                                                                                               | 复核证据                                                   | 结论                                 |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------- | ------------------------------------ |
| OPS-001         | `.github/workflows/ci.yml:210-259` 从迁移文件推导表数并断言 V10 表/索引/权限；`docs/RUNBOOK.md:268-358、415-483` V1–V14/V10 生产迁移 | EV-1015 grep；7f96a2e                                      | fixed                                |
| OPS-002         | `SchemaVersionChecker.java:54-55` =14；`SchemaVersionCheckerTest.java:62-66` 锁定 14                                                 | EV-1015（9/9）                                             | fixed                                |
| OPS-003         | `OidcProperties` 原始 String + `OidcConfigurationParser` 脱敏解析；`OidcStartupValidationTest` 真实启动失败不泄漏                    | EV-1015（2/2 + 11/11）                                     | fixed                                |
| OPS-004         | RUNBOOK 协调切换/维护窗口/回滚；`M10-OPS-DRILL` schema 前置通过、完整编排未执行                                                      | EV-1015；RUNBOOK grep                                      | fixed（生产编排仍 M09/release 前置） |
| TASK-0065 F-001 | `RoleDefinitions.java:79-81、98-100`；`RoleDefinitionsTest`；V10 回填                                                                | REV-TASK-0068-001；EV-0409/EV-0410；EV-1015（5/5 + 11/11） | fixed                                |

详细任务级结论见 `REV-TASK-0064-002` 与 `REV-TASK-0065-003`。

## 3. 验证范围与证据

| 检查      | 命令                                                                                                                     | 结果                                                     |
| --------- | ------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------- |
| build     | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                    | exit 0（EV-0435，`49813f0`）                             |
| tests     | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                      | exit 0（EV-0493，`cb977f9`；后端 765、前端 384，0 skip） |
| coverage  | 同 EV-0493                                                                                                               | API line 91.49% / branch 73.99%；高于合同 80/70          |
| 合同 E2E  | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                   | exit 0（EV-0440，26 passed）                             |
| a11y      | `pnpm -w a11y`                                                                                                           | exit 0（EV-0439，8 passed）                              |
| security  | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` | exit 0（EV-0436，仅既有 .trivyignore）                   |
| 真实栈    | `REAL_EVIDENCE_TASK=TASK-0083 node scripts/real-stack/run.mjs`                                                           | exit 0（EV-0448）                                        |
| docs/diff | `git diff --check`                                                                                                       | exit 0（EV-0437）                                        |
| 本轮定向  | EV-1015 命令见 §0                                                                                                        | exit 0，38 tests 0 skip                                  |

> 上述 EV-0435/EV-0440/EV-0437/EV-0493 均为历史合同证据，在 `9a5651e` 的祖先提交上录制；本轮未重跑全量，不扩大其结论。EV-1015 只覆盖 P1 相关定向测试与结构断言。

## 4. AC 对账（M10-AC-08..18）

| AC  | 用户可见结果                                                                                             | 结论 | 证据                                                              |
| --- | -------------------------------------------------------------------------------------------------------- | ---- | ----------------------------------------------------------------- |
| 08  | 企业账号登录回到工作空间、显示租户/用户/来源，Cookie 同 M02，无 JWT/access/refresh token                 | pass | E2E-M03-03、IT-OIDC-001、SEC-OIDC-001（真实栈会话/存储/日志扫描） |
| 09  | 不按邮箱自动绑定；未绑定提示联系管理员；管理员录入 issuer+subject 后命中同账号/角色                      | pass | E2E-M03-04、IT-OIDC-001..002、真实栈绑定→登录                     |
| 10  | 同邮箱不同 issuer 生成两个互不覆盖账号并显示来源                                                         | pass | IT-OIDC-003                                                       |
| 11  | 禁用账号不建会话并提示；既有会话下一请求失效                                                             | pass | IT-OIDC-004、SEC-OIDC-002                                         |
| 12  | 登出本地立即失效、后退不可恢复；配置 end-session 时联合登出；IdP 不可达本地仍成功                        | pass | E2E-M03-03、IT-OIDC-005、真实栈联合登出                           |
| 13  | IdP 不可达/state 不匹配/拒绝授权/未开通四类文案与下一步，不显示 IdP 原始错误/令牌/堆栈                   | pass | E2E-M03-04、IT-OIDC-006、真实栈失败注入                           |
| 14  | 内置与 OIDC 会话 Cookie/CSRF/超时一致；角色下一请求生效；无 JWT                                          | pass | IT-OIDC-007、SEC-OIDC-003                                         |
| 15  | ≥200 条审计按时间/动作/操作者/对象过滤、倒序分页、显示六字段、空态、查询被审计                           | pass | E2E-M03-05、IT-AUDIT-101..102、真实栈 220 行                      |
| 16  | 无修改/删除入口；接口拒绝写；DB 拒绝 UPDATE/DELETE；普通员工 403 且无内容                                | pass | IT-AUDIT-103、TEN-028..030、405 修复（真实栈/定向测试）           |
| 17  | 导出申请待审批、不可提前下载、不可自批；他人批准后可下载有时效文件；审计记录审批/范围/行数；拒绝不可下载 | pass | E2E-M03-06、IT-AUDIT-104..105、真实栈导出链                       |
| 18  | 保留 ≥180 天、界面显示策略、无删除；CSV 无密码/令牌/凭据/原文；过期不可下载                              | pass | IT-AUDIT-106、AuditExportClockTest、真实栈 CSV 白名单/公式转义    |

补充：

- **AC-19（导出请求段）**：`DEPT-010` 扩展为部门成员不能通过 `/audit-exports` 列表/详情/下载旁路读取；`TEN-028..030` 覆盖跨租户导出对象。套件零 skip。
- **AC-20（新对象）**：`TEN-028..030` 与 `TenantIsolationSecurityTest` 清单 30/30 通过；M02 既有回归由合同 test/E2E 覆盖。
- **AC-21（M10 页面段）**：真实栈 E2E-M03-07 覆盖登录页、审计、导出、用户管理 375px/键盘/axe 无 serious/critical；合同 a11y 继续覆盖 M02/M03 页面。
- **残余**：F-001（身份键 collation）为 accepted residual，不影响 AC 的 Then 子句；AC 表不写“已修复”。

## 5. OIDC 安全（SEC-OIDC-001..003 对照）

| 项                              | 结果 | 证据                                                                                                                                            |
| ------------------------------- | ---- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| SEC-OIDC-001 无 token 泄漏      | pass | IT-OIDC-001（会话无 token、userinfo 0 调用）、真实栈响应体/浏览器 Cookie/localStorage/sessionStorage/API 日志扫描；OPS-003/EV-1015 启动失败脱敏 |
| SEC-OIDC-002 禁用/失败清理      | pass | IT-OIDC-004、真实栈 access_denied/state_invalid/idp_unavailable 后会话 401、DB 无 OidcUser/id_token/access_token                                |
| SEC-OIDC-003 会话与内置账号一致 | pass | IT-OIDC-007（Cookie 名/属性、CSRF、无 JWT）、E2E-M03-03 后退防护                                                                                |

**B2 F-001（collation）不作为已修复**：V2 的 `external_issuer/external_subject` 仍是 `utf8mb4_0900_ai_ci`，大小写/重音变体可能命中同一账号。真实栈探针 `caseVariantSubjectMatched=true`；本评审按 **P2 accepted/residual** 承接，要求后续迁移显式 `ascii_bin`/`utf8mb4_0900_as_cs` 或 ADR 书面风险接受。

## 6. 审计/导出安全边界

- 租户只来自主体；`tenantId` 查询参数在值不等于当前租户时 403，平台管理员只能读 `platform` 租户事件（TEN-029）。
- 普通员工查询/策略/导出/下载均 403 且无审计内容（TEN-029/030、DEPT-010）。
- 跨租户导出详情/下载/审批统一 404，响应不含 filter/requestedBy/eventId（TEN-028）。
- 平台审批只返回元数据；平台租户自身导出 403；多管理员租户 409；单管理员租户平台审批成功（TEN-030、真实栈）。
- CSV：无 `source_ip`、requestId、密码、令牌、邮箱；公式前缀 `= + - @ \t \r` 前置 `'`；`data.exported` 流前 fail-closed、重复下载逐次审计。
- 只读路径错误方法 405 `RICCON-METHOD-405`（TASK-0077/0082），不再是 500。

## 7. E2E 与可访问性

- E2E-M03-03：真实 IdP 顶层授权跳转、登录成功、无 token 落地、联合登出回跳、后退不恢复。
- E2E-M03-04：管理员 UI 绑定企业账号、IdP 拒绝/state 失效/不可达三类文案与下一步。
- E2E-M03-05：动作/操作者过滤、220+ 行分页、空态、普通员工无入口/403。
- E2E-M03-06：申请导出 → 另一管理员批准 → 申请人下载 CSV（含元数据/表头/无敏感字段）。
- E2E-M03-07：375px 无横向滚动、键盘查询路径、axe 无 serious/critical。
- 合同 `pnpm -w e2e` 继续覆盖 M02/M03 stub 回归；M10 页面在真实栈执行（F-014）。

## 8. V10 迁移、回滚与 OPS

- `V10MigrationTest`（11 tests，一次性 Testcontainers MySQL 8.4）：V1–V8 → seed M02 → V9 → seed M03/audit → V10；表/租户键/复合外键/CHECK/状态机/索引列序/权限回填/M02-M03 可读/空库 fresh install/回滚 V9/失败注入 repair 重跑全部通过。
- 回滚包含 `flyway_schema_history` V10 行删除与 `MAX(version)=9` 校验；不接触生产。
- `SchemaVersionCheckerTest` 9/9（含常量锁定 14）；真实栈 worker 在 schema 10 上健康，后续迁移已升到 14。
- OPS 演练记录见 `docs/M10-OPS-DRILL.md`：OPS-005/006/007 测试环境通过；OPS-004 的 schema 前置通过，完整协调切换与 OPS-010 OIDC 回滚锁死仍是生产发布前置，未冒充已验证。
- OPS-001（CI/RUNBOOK 结构断言）、OPS-002（worker 版本）、OPS-003（启动脱敏）、OPS-004（协调切换方案）已 fixed；OPS-008/009/010/012/013/014/015/016/017/018 为 accepted，由 M09/生产承接。

## 9. Findings 摘要

- **P1**：F-016（OPS-001..004）fixed；F-017（TASK-0065 F-001）fixed。无 open P0/P1。
- **P2**：F-001（OIDC collation）accepted；F-002/F-003/F-004 fixed。
- **P3**：F-005..F-014 accepted（owner/due/accepted_reason 见 front matter）；F-015 与 REVALIDATION fixed。无 open P2/P3。

## 10. DoD

| 项         | 结果 | 说明                                          |
| ---------- | ---- | --------------------------------------------- |
| acceptance | true | AC 08–18/19–21 段通过；见 §4、§6              |
| build      | true | 合同 build EV-0435                            |
| tests      | true | 合同 test EV-0493，零 skip                    |
| coverage   | true | EV-0493，高于合同 80/70                       |
| e2e        | true | 合同 E2E EV-0440 + 真实栈 EV-0448             |
| review     | true | 本报告；任务级评审见 `.riccon/reviews/tasks/` |
| docs       | true | EV-0437；RUNBOOK/CI 已由 TASK-0073 更新       |
| evidence   | true | 所有引用 EV 非 manual、exit 0、commit 绑定    |

## 11. 未验证与剩余风险

1. **未重跑全量**：本轮只跑 EV-1015 定向测试；合同 build/test/coverage/E2E/a11y/security 沿用历史 EV，未在 `9a5651e` 上重跑。
2. **未执行生产迁移/回滚**：只在不接触生产的 Testcontainers 与 Compose 测试栈演练；生产需 T4 批准与 OPS-004/010 编排。
3. **F-001 collation 未修**：P2 residual，身份键精确匹配语义弱于 OIDC 规范。
4. **MFA、审计哈希链、平台跨租户审计明细**：按 DEC-0013 后置；M10 不声称支持。
5. **OPS 残余**：OPS-008/009/010/012/013/014/015/016/017/018 由 M09/production 承接；M10 gate 前 Lead 已确认接受口径（见 REV-TASK-0064-002）。
6. **ADR 状态**：ADR-0019/0020 仍 `proposed`；ARCHITECTURE 评审状态表已记录 REV-M10-ARCH-002 pass（F-015 fixed），ADR 正式批准仍属 Lead/用户。
7. **Product/用户验收**：M10 已于 2026-11-20 Product pass、用户 accepted（DEC-0021）；本轮不改变该状态。

## 12. 下一步（Lead）

1. 本 review 与两条 task review 已作为 M10 的最新 gate 依据；`module.review` 已指向本文件（`.riccon/reviews/M10-review-002.md`），模块/任务状态未改。
2. `riccon gate check --module M10` 结果：**PASS，0 errors / 33 warnings**（警告均为历史 task review 的 legacy open/accepted P2/P3 与 2 条 manual 证据，不影响新规则下的 gate）。
3. M10 已 done，不需要重新打开模块；若后续发现 P0/P1，按 `kind: fix` 建修复模块。
