---
review_id: REV-M10-001
module_id: M10
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0083_20261119
  model: deepseek-v4.1-flash
  independent: true
review_kind: module
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
    - EV-0438
  coverage:
    - EV-0443
  e2e:
    - EV-0440
  docs:
    - EV-0437
findings:
  - id: F-001
    severity: P2
    status: accepted
    title: "OIDC 身份键 collation 弱于精确匹配（B2 F-001 residual）"
    evidence: "TASK-0069 评审 F-001；真实栈 caseVariantSubjectMatched=true"
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
    title: "绑定/解绑不即时撤销既有会话"
    evidence: "ADR-0019 §5；TASK-0069 F-003"
  - id: F-006
    severity: P3
    status: accepted
    title: "平台跨租户绑定审计 details 无 targetTenantId；M03 F-009 residual"
    evidence: "TASK-0069 F-004；ADR-0020 §6.1"
  - id: F-007
    severity: P3
    status: accepted
    title: "审计 100 行展示名未断言 SQL 次数/耗时"
    evidence: "TASK-0071 F-002"
  - id: F-008
    severity: P3
    status: accepted
    title: "CSV 中途失败/行数硬上限竞态"
    evidence: "TASK-0071 F-003/F-004"
  - id: F-009
    severity: P3
    status: accepted
    title: "导出申请/下载只校验 audit.read"
    evidence: "TASK-0071 F-005"
  - id: F-010
    severity: P3
    status: accepted
    title: "publicBaseUrl 缺生产 fail-fast"
    evidence: "TASK-0065 F-003；OPS-015"
  - id: F-011
    severity: P3
    status: accepted
    title: "失败审计/旧 CSRF 失效断言不完整"
    evidence: "TASK-0065 F-005/F-006；V1 真实栈失败属性扫描"
  - id: F-012
    severity: P3
    status: accepted
    title: "OPS-008/009/010/013/014/015 仍属 M09/生产"
    evidence: "M10-OPS-review；docs/M10-OPS-DRILL.md"
  - id: F-013
    severity: P3
    status: accepted
    title: "SEC-AUDIT-001 编号未落地"
    evidence: "IT-AUDIT-106；PRODUCT M03-AC-18 测试线索"
  - id: F-014
    severity: P3
    status: accepted
    title: "M10 页面 E2E 在真实栈而非合同 stub 套件"
    evidence: "EV-0440；EV-0448"
  - id: F-015
    severity: P3
    status: accepted
    title: "ARCHITECTURE 评审状态表未随 REV-M10-ARCH-002 更新"
    evidence: "docs/ARCHITECTURE.md 评审状态表；ADR-0019/0020 proposed"
created_at: "2026-11-19T18:36:17+08:00"
---

# REV-M10-001：M10（身份与审计纵深，M03b）模块独立验证

## 1. 结论

**verdict: pass（模块级）。** 在最终提交上独立执行合同 build/test/coverage/E2E/a11y/security、真实栈 OIDC/审计/导出与 V10 迁移/回滚演练后，M10 的用户可见结果可复验：

- 员工可用企业账号（OIDC 授权码 + PKCE S256 + nonce）登录，会话只落 `RicconPrincipal`，浏览器/响应/日志无 JWT、access/refresh token 或 userinfo 调用；失败路径清理会话并返回稳定原因码；登出本地权威并自动联合登出。
- 管理员可按时间/动作/操作者/对象过滤审计、固定倒序分页、查询自身被审计；导出需另一管理员批准，申请人不能自批，只有申请人可下载有时效的 CSV；平台租户自身导出 403，平台只审批单管理员租户元数据。
- V10 为纯 additive 迁移；一次性容器验证 V1–V9 seed → V10 → 回滚 V9、权限回填、索引单语句 INPLACE/LOCK=NONE、失败注入 repair 重跑；worker 期望 schema 10。
- AC 08–18 全部通过；AC-19 导出请求段、AC-20 跨租户新对象、AC-21 M10 页面段通过；零 skip、零 xfail。

本结论是**独立技术验收**，不是 Product 预验收或用户最终验收。M10 仍需 Product 在 `docs/ACCEPTANCE.md` 做预验收、Lead 记录本评审并把 TASK-0083 置 done；用户 accepted 前不进入下一个用户可见模块。

## 2. 验证范围与证据

| 检查      | 命令                                                           | 结果                                    |
| --------- | -------------------------------------------------------------- | --------------------------------------- |
| build     | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                | exit 0（EV-0435）                  |
| tests     | `./mvnw -q -T1C verify && pnpm -w test`                                                 | exit 0，751 tests（0 fail / 0 error / 0 skip；121 个 fresh surefire 报告）（EV-0438）   |
| coverage  | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage --no-file-parallelism`                                             | exit 0，API line 91.49% / branch 73.99%（EV-0443） |
| 合同 E2E  | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                                  | exit 0，26 passed（EV-0440）     |
| a11y      | `pnpm -w a11y`                                                 | exit 0，8 passed（EV-0439）   |
| security  | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`                                             | exit 0，trivy exit 0（仅既有 .trivyignore 抑制）（EV-0436） |
| 真实栈    | `REAL_EVIDENCE_TASK=TASK-0083 node scripts/real-stack/run.mjs` | exit 0，真实栈全部阶段通过（EV-0448）   |
| docs/diff | `git diff --check`                                             | exit 0（EV-0437）                   |

覆盖率为避免 Vitest 文件级并行在插桩下的计时抖动影响既有 `audit/index.test.tsx`，在同一条合同 coverage 命令后追加 `--no-file-parallelism`（同一套件、同一覆盖率阈值；合同命令前缀仍被 EV 覆盖）。这不改变被测代码，也不跳过任何测试。

所有证据在最终提交 `a88d003` 的干净工作区录制（`dirty=false`），非 manual、exit 0。任务级/评审证据按分层引用，不把构建者总结当证据。

### 2.1 真实栈 OIDC/审计/导出

`scripts/real-stack/m10-checks.mjs` 使用 `e2e/oidp` 测试 IdP（运行时生成 RSA 密钥与 clientSecret，不落盘）验证：

- providers 对未知租户与未开通租户统一 `enabled=false`；
- 授权码 + PKCE(S256) + nonce：测试 IdP 校验 challenge/verifier、client Basic、code 一次性；产品无 userinfo 调用；
- 失败注入 `access_denied`、`state_invalid`、`idp_unavailable`：会话 401、`SPRING_SESSION_ATTRIBUTES` 无 OidcUser/id_token/access_token；
- 联合登出返回 end-session 地址，浏览器回跳 `federated=1`；
- B2 绑定后登录：管理员通过 `PUT /users/{id}/oidc-binding` 绑定 issuer+subject，密码凭据被删除，OIDC 登录命中同一账号与原有角色（B2 F-005）；
- 审计：220 行分页不重叠、固定倒序、`actorDisplayName` 批量解析、180 天上限/偏移/分页上限 400、查询写 `audit.queried`、模型事件 `promptTokens` 可查、普通员工 403、平台只看 platform；
- 导出：申请自批 403、另一管理员批准、审批人下载 403、申请人 CSV、公式前缀转义、敏感字段缺失、跨租户 404、平台租户 403、多管理员租户平台审批 409、单管理员租户平台审批成功；
- 405：只读审计路径错误方法返回 `RICCON-METHOD-405`；
- API 日志扫描无 clientSecret/id_token/access_token。

## 3. AC 对账（M10-AC-08..18）

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
- PRODUCT 的 `SEC-AUDIT-001` 编号未单独建测试；其用户可见内容由 `IT-AUDIT-106`（CSV 元数据/白名单/公式转义/过期）与策略端点覆盖，见 F-013。

## 4. OIDC 安全（SEC-OIDC-001..003 对照）

| 项                              | 结果 | 证据                                                                                                              |
| ------------------------------- | ---- | ----------------------------------------------------------------------------------------------------------------- |
| SEC-OIDC-001 无 token 泄漏      | pass | IT-OIDC-001（会话无 token、userinfo 0 调用）、真实栈响应体/浏览器 Cookie/localStorage/sessionStorage/API 日志扫描 |
| SEC-OIDC-002 禁用/失败清理      | pass | IT-OIDC-004、真实栈 access_denied/state_invalid/idp_unavailable 后会话 401、DB 无 OidcUser/id_token/access_token  |
| SEC-OIDC-003 会话与内置账号一致 | pass | IT-OIDC-007（Cookie 名/属性、CSRF、无 JWT）、E2E-M03-03 后退防护                                                  |

**B2 F-001（collation）不作为已修复**：V2 的 `external_issuer/external_subject` 仍是 `utf8mb4_0900_ai_ci`，大小写/重音变体可能命中同一账号。真实栈探针 `caseVariantSubjectMatched=true`；本评审按 **P2 accepted/residual** 承接，要求后续迁移显式 `ascii_bin`/`utf8mb4_0900_as_cs` 或 ADR 书面风险接受，不冒充已解决。

## 5. 审计/导出安全边界

- 租户只来自主体；`tenantId` 查询参数在值不等于当前租户时 403，平台管理员只能读 `platform` 租户事件（TEN-029）。
- 普通员工查询/策略/导出/下载均 403 且无审计内容（TEN-029/030、DEPT-010）。
- 跨租户导出详情/下载/审批统一 404，响应不含 filter/requestedBy/eventId（TEN-028）。
- 平台审批只返回元数据；平台租户自身导出 403；多管理员租户 409；单管理员租户平台审批成功（TEN-030、真实栈）。
- CSV：无 `source_ip`、requestId、密码、令牌、邮箱；公式前缀 `= + - @ \t \r` 前置 `'`；`data.exported` 流前 fail-closed、重复下载逐次审计。
- 只读路径错误方法 405 `RICCON-METHOD-405`（TASK-0077/0082），不再是 500。

## 6. E2E 与可访问性

- E2E-M03-03：真实 IdP 顶层授权跳转、登录成功、无 token 落地、联合登出回跳、后退不恢复。
- E2E-M03-04：管理员 UI 绑定企业账号、IdP 拒绝/state 失效/不可达三类文案与下一步。
- E2E-M03-05：动作/操作者过滤、220+ 行分页、空态、普通员工无入口/403。
- E2E-M03-06：申请导出 → 另一管理员批准 → 申请人下载 CSV（含元数据/表头/无敏感字段）。
- E2E-M03-07：375px 无横向滚动、键盘查询路径、axe 无 serious/critical。
- 合同 `pnpm -w e2e` 继续覆盖 M02/M03 stub 回归；M10 页面在真实栈执行（见 F-014 的测试拓扑说明）。

## 7. V10 迁移、回滚与 OPS

- `V10MigrationTest`（11 tests，一次性 Testcontainers MySQL 8.4）：V1–V8 → seed M02 → V9 → seed M03/audit → V10；表/租户键/复合外键/CHECK/状态机/索引列序/权限回填/M02-M03 可读/空库 fresh install/回滚 V9/失败注入 repair 重跑全部通过。
- 回滚包含 `flyway_schema_history` V10 行删除与 `MAX(version)=9` 校验；不接触生产。
- `SchemaVersionCheckerTest` 5/5；真实栈 worker 在 schema 10 上健康。
- OPS 演练记录见 `docs/M10-OPS-DRILL.md`：OPS-005/006/007 测试环境通过；OPS-004 的 schema 前置通过，完整协调切换与 OPS-010 OIDC 回滚锁死仍是生产发布前置，未冒充已验证。

## 8. Findings

### F-001（P2，accepted / residual）：OIDC 身份键 collation 弱于精确匹配

V2 的 `external_issuer/external_subject` 使用 `utf8mb4_0900_ai_ci`；同一 issuer 下大小写/重音变体可能被唯一键视为同一身份。B2 写范围不含迁移；真实栈 case-variant 探针 `true`。需后续迁移或 ADR 风险接受，M10 不声称修复。

### F-002（P2，fixed）：审计只读路径错误方法曾返回 500

TASK-0077 修复 `HttpRequestMethodNotSupportedException` 映射；TASK-0082 登记 `RICCON-METHOD-405`；定向测试与真实栈均返回 405。

### F-003（P2，fixed）：用户管理页无导航入口

TASK-0080 在管理端菜单补 `用户管理`（`tenant.user.manage`）与 `审计查询`/`审计导出` 入口；E2E 可从菜单到达。

### F-004（P2，fixed）：B4 缺浏览器级 OIDC/绑定/a11y

V1 新增测试 IdP、真实栈 OIDC/绑定/失败文案与 E2E-M03-03/04/07；B4 F-002 关闭。

### F-005（P3，accepted）：绑定/解绑不即时撤销既有会话

与 ADR-0019 §5 并发会话口径一致；受 30 分钟空闲超时限制，未声称全局踢出。

### F-006（P3，accepted）：平台跨租户绑定审计 details 无 targetTenantId

与 ADR-0019 §3/ADR-0020 §6.1 字段清单一致；M03 F-009（平台来源事件对目标租户不可见）继续 residual，不声称解决。

### F-007（P3，accepted）：审计 100 行展示名未断言 SQL 次数/耗时

实现为每页一次批量 `IN` 查询；真实栈 100 行展示名通过，但无 N+1 回归护栏。

### F-008（P3，accepted）：CSV 中途批次失败/行数硬上限竞态

正常数据由写侧白名单保证；历史/运维直写非法 details 与计数-流式间隙仍可能截断或略超行数。M09/OPS-014 清点与后续加固承接。

### F-009（P3，accepted）：导出申请/下载只校验 `audit.read`，未显式断言 TENANT_ADMIN 角色

当前只有 TENANT_ADMIN/PLATFORM_ADMIN 拥有该权限；未来自定义角色会继承导出能力（审批仍受 `audit.export.approve`）。需架构确认权限模型。

### F-010（P3，accepted）：`publicBaseUrl` 缺生产 fail-fast

OPS-015 仍 open；生产误配会造成 redirect_uri 不匹配。M09 运行手册兜底。

### F-011（P3，accepted）：B1 失败审计/旧 CSRF 失效断言仍不完整

V1 真实栈补了失败路径会话属性扫描与 401；`auth.login.failed` 细节与旧 CSRF 失效仍未单列断言。

### F-012（P3，accepted）：OPS-008/009/010/013/014/015 仍属 M09/生产

导出清理索引、流式连接占用、OIDC 回滚锁死、平台合规取数、历史 details 清点、publicBaseUrl/访问日志脱敏均为生产前置；M10 不冒充关闭。

### F-013（P3，accepted）：`SEC-AUDIT-001` 测试编号未落地

PRODUCT 的测试线索使用 `SEC-AUDIT-001`；实际由 `IT-AUDIT-106` 覆盖 CSV 白名单/公式转义/过期。建议后续统一编号，不影响 AC-18 行为。

### F-014（P3，accepted）：M10 页面 E2E 在真实栈而非合同 stub 套件

`pnpm -w e2e` 仍是 M02/M03 stub 回归；M10 浏览器旅程由真实栈 EV 承担。测试拓扑已记录，不用 API 证据替代 UI。

### F-015（P3，accepted）：`docs/ARCHITECTURE.md` 评审状态表未随 REV-M10-ARCH-002 更新

表内仍写 Lead 复审 pending、Verifier changes-requested；ADR-0019/0020 仍 `proposed`。Lead 应在模块 done 前更新状态表并完成 ADR 批准记录；不影响代码验收。

## 9. DoD

| 项         | 结果 | 说明                                                                    |
| ---------- | ---- | ----------------------------------------------------------------------- |
| acceptance | true | AC 08–18/19–21 段通过；见 §3、§4、§6                                    |
| build      | true | 合同 build                                                              |
| tests      | true | 合同 test，零 skip                                                      |
| coverage   | true | 见 §2；高于合同 80/70                                                   |
| e2e        | true | 合同 E2E + 真实栈 M10 旅程                                              |
| review     | true | 本报告；任务级评审见 `.riccon/reviews/tasks/`                           |
| docs       | true | diff check；RUNBOOK/CI 已由 TASK-0073 更新，ARCHITECTURE 状态表见 F-015 |
| evidence   | true | 所有引用 EV 非 manual、exit 0、commit 绑定                              |

## 10. 未验证与剩余风险

1. **未执行生产迁移/回滚**：只在不接触生产的 Testcontainers 与 Compose 测试栈演练；生产需 T4 批准与 OPS-004/010 编排。
2. **F-001 collation 未修**：P2 residual，身份键精确匹配语义弱于 OIDC 规范。
3. **MFA、审计哈希链、平台跨租户审计明细**：按 DEC-0013 后置；M10 不声称支持。
4. **平台来源审计事件对目标租户不可见**：M03 F-009 residual。
5. **OPS P2 项**：OPS-008/009/010/013/014 由 M09 承接；M10 gate 前 Lead 需确认接受口径。
6. **ADR 状态**：ADR-0019/0020 仍 `proposed`，ARCHITECTURE 状态表陈旧；模块 done 前需 Lead/用户批准记录。
7. **Product 预验收**：`docs/ACCEPTANCE.md` 尚无 M10 AC 表；gate 后由 Product 完成预验收。

**下一步（Lead/Product）**：记录 REV-M10-001 → TASK-0083 done → `riccon gate check --module M10` → Product 在 `docs/ACCEPTANCE.md` 预验收 → 用户最终验收。任何 P0/P1 出现即停止。
