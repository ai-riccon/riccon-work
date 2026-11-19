---
review_id: REV-TASK-0076-001
module_id: M10
task_id: TASK-0076
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0081_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [9fcdd95]
head_commit: 4ef744f
reviewed_evidence: [EV-0427, EV-0428, EV-0429, EV-0430]
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/lead + riccon/builder（扩展 TASK-0080 或新建导航任务）"
    title: "用户管理页无导航入口，TASK-0080 现有 AC 也未承接"
    summary: "`/admin/users` 与 `/admin/tenants/:tenantId/users` 已注册路由，页面本身具备权限守卫、authSource、绑定/解绑能力；但 `adminNavigation` 仍是 overview/tenants/settings 三项，`/admin/tenants` 页也没有跳转链接。普通成员确实无入口，但租户管理员/平台管理员同样只能手输 URL。TASK-0080 的标题与 acceptance 只覆盖审计查询/导出导航，未包含用户管理，因此‘TASK-0080 扩展承接’尚未落到任务卡。M03-AC-09 的管理员显式绑定和 V1 的 E2E-M03-04 都需要可导航入口。"
    evidence: "apps/web/config/routes.ts:67、72（注册用户管理路由）、144-148（adminNavigation 三项）；apps/web/src/layouts/admin/index.tsx:5、37（菜单直接由 adminNavigation 生成）；.riccon/tasks/TASK-0080.json（acceptance 只写审计导航）；.riccon/tasks/TASK-0076.json（已知缺口记录）"
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/verifier（V1）+ riccon/lead（E2E 排期）"
    title: "B4 缺少浏览器级 E2E/a11y；现有 login.spec 不覆盖企业登录、联合登出与用户管理页"
    summary: "定向 `e2e/tests/login.spec.ts` 3 个用例只覆盖 M02 内置账号登录、错误凭据与 375px；企业账号按钮只是被动进入 axe 扫描，没有点击 providers、顶层授权跳转或联合登出用例。`/admin/users` 没有 E2E、axe、375px 或键盘用例。E2E stub 也没有 OIDC providers/authorize/callback、租户 `/api/v1/users`、`tenant.user.manage` 权限或 `authSource` 字段，V1 必须先扩展 stub/测试 IdP 才能跑 E2E-M03-03/04/07 与绑定 axe。任务 AC #6 已把完整 E2E/a11y 延到 V1，故不阻塞 B4，但 M10 gate 前必须补齐。"
    evidence: "e2e/tests/login.spec.ts:10-33（3 个用例）；e2e/stub/server.mjs:2457（handleApi 仅 M02/M03 子集；`grep -n 'oidc\\|tenant.user.manage' e2e/stub/server.mjs` 无命中）；apps/web/config/routes.ts:67、72（用户管理路由无 E2E）"
  - id: F-003
    severity: P3
    status: open
    owner: "riccon/builder（B4 后续小修）"
    title: "sessionStorage 写入/移除异常会中断登出调用方，与‘本地登出是权威结果’不一致"
    summary: "`sessionStore()` 只捕获访问 `sessionStorage` 时的异常；`setItem`/`removeItem` 本身没有 try/catch。若浏览器策略让存储写入抛错（配额、隐私模式、企业策略），`rememberFederatedLogoutUrl` 会在服务端已使会话失效后把异常抛给 `ApiClient.logout()`，调用方据此显示‘退出登录失败’并保留本地 UI 状态。`markFederatedLogoutAttempted`/`clearFederatedLogout` 也有同样风险。建议把存储读写全部包进 try/catch：失败时退化为不记忆联合登出地址，但不得让本地登出结果变成失败。"
    evidence: "apps/web/src/services/auth/federated-logout.ts:70-96、117-133；apps/web/src/services/api/client.ts:273-280；apps/web/src/layouts/workspace/index.tsx:62-72；apps/web/src/pages/chat/index.tsx:138-148；ADR-0019 第 5 节（本地登出先执行且是权威结果）"
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder（B4 后续小修）"
    title: "logout 改用 performRequest 后网络错误不再归一为 ApiError(status=0)"
    summary: "B4 前 `logout` 走 `this.request`，网络/解析异常会经 `ApiError.network` 归一；现在直接调用 `performRequest`，只保留 `finally` 清理 CSRF，原始 TypeError 会抛给调用方。`WorkspaceLayout`/`ChatPage` 的 `describeApiError` 对非 ApiError 返回 fallback，用户看到‘退出登录失败，请重试。’而不是‘无法连接服务器’。错误没有被吞掉（这一点通过），但错误语义与其它 API 调用不一致。建议在 `logout` 内 catch 后 `throw ApiError.network(error)`，保持既有约定。"
    evidence: "apps/web/src/services/api/client.ts:273-281（performRequest 直调）与 1065-1078（request 的 ApiError.network 归一）；apps/web/src/layouts/workspace/index.tsx:67-72；apps/web/src/pages/chat/index.tsx:142-148；apps/web/src/services/api/errors.ts:147-150"
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/lead + riccon/product（判断 M10 是否需要分页）"
    title: "用户列表固定 size=100 且无分页/搜索，超过 100 人的租户无法管理后续账号"
    summary: "`UserAdminClient.listUsers`/`listTenantUsers` 固定请求 `page=1&size=100`，页面直接渲染返回的 items，没有分页、搜索或截断提示。演示租户无影响，但真实租户超过 100 个账号时，后续账号无法被列出、绑定或解绑。若 M10 的验收只要求演示规模，可书面接受；否则应在 B4 或后续任务补分页/搜索及 total 提示。"
    evidence: "apps/web/src/services/users/api.ts:100-102、139-141；apps/web/src/pages/admin/users/index.tsx:93-105（只取第一页 items）；contracts/openapi.yaml:1855-1873（UserPage 支持分页）"
  - id: F-006
    severity: P3
    status: open
    owner: "riccon/builder（B4 后续小修）+ riccon/verifier（V1 用例）"
    title: "联合登出 URL 的 sessionStorage 状态可能在后续登录/本地登出间残留"
    summary: "`rememberFederatedLogoutUrl(null)` 在无响应头时直接返回 false，不会清理上一次登出留下的地址；OIDC 登录成功由后端 302 到 `/`，不会经过登录页的 `handleFinish`/`clearFederatedLogout`，因此失败或取消的联合登出留下的 URL/attempted 标记可能跨一次 OIDC 登录残留。后续如切换为 LOCAL 账号并登出，页面可能展示旧 IdP 的‘重试退出企业账号’按钮（attempted=1 时不会自动跳转；attempted 被清除时存在误导航窗口）。建议每次登出先清空旧地址，或在 providers 登录成功/会话建立时统一清理。"
    evidence: "apps/web/src/services/auth/federated-logout.ts:87-90（无 header 不清旧值）、130-134（清理入口）；apps/web/src/pages/login/index.tsx:141-154（仅本地登录成功清理）；apps/api/src/main/java/com/riccon/api/oidc/controller/OidcLoginSuccessHandler.java:125（OIDC 成功 302 到 `/`，不经过登录页）"
evidence: [EV-0428, EV-0429, EV-0430]
created_at: "2026-11-19T15:04:00+08:00"
---

# REV-TASK-0076-001：TASK-0076（M10-B4 Web OIDC/绑定）独立评审

## 1. 结论

**verdict: pass（任务级）。** B4 的前端登录企业入口、联合登出落地、用户绑定/解绑、authSource 展示、错误文案与 OpenAPI 类型对齐可以独立复验；P0/P1 = 0。开放 **P2 2 条**（管理端用户导航缺失且 TASK-0080 未承接；B4 浏览器级 E2E/a11y 未覆盖）与 **P3 4 条**。本结论不代替 M10 模块 gate，也不代表 ADR-0019 已复审通过（ADR 仍为 `proposed`）。

**B4 可以 done 的前提**（由 Lead 执行，本评审不代改任务）：

1. 记录本评审，并把 F-001 落到 TASK-0080 的 acceptance/write_scope（或新建导航任务），确保 M10 gate 前用户管理页有可发现的入口；TASK-0080 现有 AC 只写审计导航，不能直接视为已承接。
2. 接受 F-002 的 V1 承接：真实 IdP/联合登出/绑定 UI 的 E2E、axe、375px 与键盘验证必须在 V1 补齐；E2E stub 需要先支持 OIDC 与用户管理端点。
3. P3 可接受或由 builder 小修；不阻塞 B4 done，但 F-003/F-004 建议在 M10 gate 前修掉，避免登录/登出链路的错误语义和存储异常。

## 2. 评审范围与独立复验

评审起点：`git status --short` 为空；HEAD `4ef744f`；被评审代码提交 `9fcdd95`（B4），`EV-0427` 由 builder 在同一提交、`dirty=false` 录制。独立证据：**EV-0428**（typecheck/lint/test+coverage/build/bundle，commit `4ef744f`，`dirty=false`，exit 0）、**EV-0429**（定向登录 E2E）、**EV-0430**（diff/合同/state 静态校验）。

| 检查/命令                                                                        | 退出码 | 结果                                                                                                             |
| -------------------------------------------------------------------------------- | ------ | ---------------------------------------------------------------------------------------------------------------- |
| `git status --short`；`git rev-parse HEAD`                                       | 0      | 评审起点工作区干净；HEAD `4ef744f4f04c7c30be593eedb7acbc3f46dcf8f7`                                              |
| `git show --stat 9fcdd95`                                                        | 0      | 26 个文件、3386 insertions、36 deletions；全部落在 8 项 write_scope 内                                           |
| `pnpm -w typecheck`                                                              | 0      | 通过（EV-0428）                                                                                                  |
| `pnpm -w lint`                                                                   | 0      | ESLint + Prettier 通过（EV-0428）                                                                                |
| `pnpm -w test -- --coverage`                                                     | 0      | 43 files / **355 tests** 全通过，0 skip；coverage line **87.64%**、branch **80.65%**（合同 80/70）               |
| `pnpm -w build`                                                                  | 0      | Umi 生产构建成功，包含 `p__login__index.async.js`、`p__admin__users__index.async.js`                             |
| `node apps/web/scripts/check-prod-bundle.mjs`                                    | 0      | 59 个文件、2526.8 KiB，未命中 mock/stub 特征                                                                     |
| `pnpm -w e2e login.spec.ts`                                                      | 0      | 3 passed（2 个 @a11y、1 个 375px；EV-0429）                                                                      |
| `git diff --check`；`riccon contract validate --strict`；`riccon state validate` | 0      | diff 无空白错误；contract 0 errors/0 warnings；state 0 errors/1 条与 M10 无关的 M11 历史警告（EV-0430）          |
| `EV-0427` 审计                                                                   | —      | `commit=9fcdd95`、`dirty=false`、`exit=0`；日志 SHA-256 与 EV JSON 一致；日志含 43 files/355 tests 与 build 成功 |

覆盖率按合同门槛独立复算为 **line 87.64% / branch 80.65%**，与 builder 报告的 87.61/80.57 同向且均高于门槛；EV-0427 本身未包含 coverage，B4 的覆盖率证据由本次 EV-0428 补足。未重复后端全量（按分层要求，EV-0427 为前端；后端 B1/B2 只读核对契约）。

## 3. 登录（任务 AC 1）

**通过项：**

- **providers 查询不泄露租户存在性**：登录页只以 `tenantCode` 查询；前端 `OidcAuthClient.getProvider` 先做 `^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$` 校验，非法值不发网络请求；未知/未配置/停用/租户暂停均由后端统一返回 `enabled=false`（只读核对 `OidcAuthController.providers` 与 `OidcLoginService.providerAvailability`）。页面文案“该租户未开通企业账号登录”不区分未知与未开通。
- **七类/多类错误态**：provider 查询覆盖成功、未开通、非法 tenantCode、429、5xx、网络失败与通用失败；OIDC 回调原因码 `idp_unavailable`、`state_invalid`、`access_denied`、`account_not_bound`、`account_disabled`、`tenant_unavailable`、`issuer_mismatch`、`rate_limited`、`login_failed` 均有独立标题、说明与下一步，未知值走通用文案且不回显查询参数（`oidc-errors.test.ts`、`login/index.test.tsx` 的 `it.each`）。
- **内置登录错误不枚举账号**：401 统一为“租户代码、账号或密码不正确”，429/403/网络/通用分别映射；测试覆盖。
- **授权跳转**：企业账号按钮校验 tenantCode 后调用 `assign(oidcApi.authorizeUrl(tenantCode))`，地址为 `/api/v1/auth/oidc/authorize/tenant-{code}`，由顶层导航打开，不使用 fetch/XHR；`OidcAuthClient` 不读取、不保存 token。
- **无 in-app mock**：`src` 与 `config` 中无 `RICCON_API_MOCK`/`createMockFetch`/`mock-api`；`check-prod-bundle` 通过；测试替身位于 `apps/web/tests/m10-oidc/**`，不进入生产构建。

**残余：** 企业账号按钮始终展示，`providers` 只在点击后决定可用性；这与 ADR-0019 §7 “该端点用于登录页决定是否展示入口”的字面表述有轻微差异，但产品任务 AC 未要求隐藏入口，且未知租户不泄露成立。作为设计观察记录，不单独列 finding；V1 UX 评审可决定是否在输入租户代码后预查询并隐藏/禁用入口。

## 4. 联合登出（任务 AC 2）

**通过项：**

- **响应头捕获**：`ApiClient.logout` 成功后读取 `X-Riccon-Federated-Logout-Url`，只调用 `rememberFederatedLogoutUrl` 暂存；后端 `OidcLogoutService.FEDERATED_LOGOUT_HEADER` 使用同名常量，只有 `auth_source=OIDC` 且租户配置了 end-session 才返回，LOCAL 不返回。
- **地址安全校验**：`isSafeFederatedLogoutUrl` 只接受 HTTPS 与本机 HTTP（localhost/127.0.0.1/[::1]），拒绝 `javascript:`、非 URL 与非本机 HTTP；测试覆盖。
- **一次自动导航**：登录页在本地退出后读取暂存地址，先 `replaceWithPendingLanding` 改写到 `/login?loggedOut=1&federated=pending`，再用 `beginFederatedLogoutOnce` 调用 `window.location.assign`；attempted 标记保证同一次落地只自动导航一次，后退后显示“重试退出企业账号”。
- **IdP 不可达 / 未配置**：无暂存地址时只显示“已退出本地会话”，并明确企业账号会话可能仍有效；不会把 URL 改写为 `federated=1`，不声称企业会话已结束。IdP 回跳 `federated=1` 时显示“已退出本地会话和企业账号”，并清理暂存地址。
- **LOCAL 不导航**：后端不返回响应头时，页面不自动导航；内置账号主动退出只显示本地退出提示（单元测试覆盖）。
- **sessionStorage 不含 token**：只存 `riccon.federated-logout-url`（URL）与 attempted 标记；代码无 token/ID Token 写入。测试未显式断言“存储中无 token”，但实现路径清晰；V1 可在 E2E 中断言。

**残余：** F-003（存储异常中断登出）、F-004（logout 网络错误语义）、F-006（旧 URL 跨登录残留）。真实浏览器的跨域 end-session 导航、IdP 不可达路由中止、后退恢复由 V1 的 E2E-M03-03/12 覆盖，本任务未执行。

## 5. 绑定 / 解绑（任务 AC 3）

**通过项：**

- **页面与权限**：`/admin/users`（租户管理员，`tenant.user.manage`）与 `/admin/tenants/:tenantId/users`（平台管理员，`platform.tenant.manage`）按会话权限判断；普通成员看到 403 且不发起账号列表请求；平台管理员仅访问目标租户路径。
- **authSource 展示**：列表显示“企业账号”/“内置账号”；历史响应缺字段显示“未标注”，不猜测来源；颜色与标签有测试。
- **绑定**：弹窗校验 issuer 绝对 http(s) URL、≤255；subject 1–255、不允许首尾空白；请求只发送 `{issuer, subject}`；成功后更新列表并提示本地密码已删除；409 冲突、400 issuer/格式、404 账号/租户分别映射为中文文案，不回显英文 detail。
- **解绑**：必须设置 12–200 字符新密码并二次确认；成功后列表回到“内置账号”并提示已恢复本地密码；409 保留弹窗并提示未绑定/并发修改；没有“无凭据解绑”入口。
- **平台拒绝**：平台租户页面显示“平台租户不支持企业账号绑定”，隐藏绑定/解绑按钮；后端 `rejectPlatformTenant` 返回 `RICCON-OIDC-400`，前后端边界一致。
- **普通成员无入口**：管理端导航没有用户管理项；页面本身也会在无权限时拒绝，且不请求列表。
- **CSRF / 401**：绑定/解绑使用会话 CSRF，403 CSRF 失败刷新一次后重试，401 回调 `authStore.markExpired`；均有单测。

**残余：** F-001（管理员无导航入口）、F-005（列表固定 100 条、无分页/搜索）。后端 B2 的绑定/解绑语义（issuer 必须匹配租户配置、删除本地凭据、审计、平台租户拒绝）只读核对，未在本次重复运行后端测试；由 B2 任务评审与 V1 覆盖。

## 6. 类型、路由、客户端与构建（任务 AC 4/5）

- **OpenAPI 对齐**：`AuthSource`（LOCAL/OIDC）、`UserSummary.authSource`（可选）、`OidcProviderAvailability`（enabled 必填、providerName 可选可空）、`OidcBindingRequest`（issuer URI ≤255、subject 1–255）、`OidcUnbindRequest`（newPassword 12–200）与 `services/api/types.ts` 一致；请求路径与 OpenAPI 的租户/平台绑定端点一致；`listUsers` 使用 `page=1&size=100`（PageSize 上限 100）。
- **路由注册**：`/admin/users` 与 `/admin/tenants/:tenantId/users` 已在 `config/routes.ts` 注册并有 title；Umi 生产构建包含对应异步 chunk；但菜单未注册（F-001）。
- **客户端登出**：`ApiClient.logout` 不吞错误（无 catch，仅 finally 清 CSRF），成功时捕获响应头；响应头名与后端一致；`api-logout.test.ts` 覆盖有/无/非法地址。
- **生产 bundle**：`check:bundle` 通过，未发现 mock/stub/演示凭据特征；`dist` 未进入 git。
- **边界**：`9fcdd95` 只改 `apps/web` 与追认的 `config/routes.ts`/`api/client.ts`；没有后端、契约、迁移、AGENTS 或 PRODUCT 改动。写范围扩展是 Lead 在 `4ef744f` 追认的（B4 代码先于范围扩展提交）；这是流程偏差，不是代码缺陷，已由 Lead 明确记录。

## 7. 测试与假绿审计

- **计数与通过**：独立 `pnpm -w test` 为 43 files / 355 tests，与 EV-0427 日志一致；0 skipped、0 todo、无 `.skip`/`xit`；本评审复跑未出现 flake。
- **删测/改测**：`9fcdd95` 对 `login/index.test.tsx` 的 diff 是重命名/扩写（`主动退出后展示成功提示` 改为 `内置账号主动退出后只提示本地会话结束`），没有删除测试文件或降低断言；新增 `admin/users/index.test.tsx`、`users/*.test.ts`、`auth/*.test.ts`。
- **断言质量**：登录测试覆盖企业入口、未开通、429、空租户、9 个 OIDC 原因码、未知原因码、联合登出一次导航/后退重试/无地址；用户管理测试覆盖普通成员 403、authSource、绑定 409/表单校验、解绑 12 位/二次确认/409、平台端点、平台租户拒绝、空态、列表 403。均有具体文案/调用/状态断言，不是快照。
- **覆盖缺口**：`login.spec.ts` 不点击 B4 企业入口；`/admin/users` 无浏览器/axe 用例；真实 IdP、联合登出、E2E stub 的 OIDC 支持由 V1 承接（F-002）。这是任务 AC #6 的显式延后，不是本次假绿。
- **覆盖率**：独立 EV-0428 line 87.64% / branch 80.65%，高于合同 80/70；新文件 `federated-logout.ts` 92/85.36、`oidc-api.ts` 100/95.65、`users/api.ts` 90/83.78（v8 文本报告口径），未发现因新增文件拉低门槛。

## 8. 未验证与剩余风险

- **未执行**：真实 IdP 授权码 + PKCE 浏览器旅程、联合登出 end-session 回跳/后退、绑定页 axe/375px/键盘、完整 E2E 套件、`commands.a11y` 全量、`commands.security`、k6 性能、后端 B1/B2 全量重跑、M10 模块 gate。
- **已知承接**：管理端用户管理导航（F-001，TASK-0080 需扩展）；V1 真实 IdP E2E/联合登出/绑定 axe（F-002）；E2E stub 需新增 OIDC 与用户管理端点。
- **模块级**：ADR-0019 仍为 `proposed`，M10 模块评审需要在最终提交上跑合同 build/lint/typecheck/test/coverage/E2E/a11y/security；本任务评审不能作为模块 gate 证据。
- **风险**：F-003/F-004 是登录/登出链路的健壮性与错误语义问题，建议 M10 gate 前修复；F-005 是 100 人规模上限，需产品确认是否接受；F-006 是低频状态残留，V1 可加一条“失败联合登出后重新 OIDC 登录再本地登出”的用例。

## 9. Findings 详述

### F-001（P2）用户管理页无导航入口，TASK-0080 未承接

`config/routes.ts` 已注册 `/admin/users`（67）与 `/admin/tenants/:tenantId/users`（72），但 `adminNavigation` 仍只有 `/admin/overview`、`/admin/tenants`、`/admin/settings`（144-148）；`AdminLayout` 的 ProLayout 菜单直接映射 `adminNavigation`（`layouts/admin/index.tsx:5、37`）。因此普通成员“无入口”成立，但管理员也没有入口，只能直接输入 URL。任务背景中“TASK-0080 扩展承接”在任务卡层面尚未落实：`.riccon/tasks/TASK-0080.json` 的标题是“M10 Web 审计导航入口”，acceptance 只要求审计查询/导出入口与路由测试，不包含用户管理。M03-AC-09 要求管理员为内置账号录入 issuer + subject，V1 的 E2E-M03-04 需要真实导航路径；M10 gate 前应由 Lead 扩展 TASK-0080 的 acceptance/write_scope（`layouts/admin/**` 与 `config/routes.test.ts` 已足够）或新建任务，并更新 `adminNavigation` 断言。

### F-002（P2）B4 缺少浏览器级 E2E/a11y

`e2e/tests/login.spec.ts` 的 3 个用例是 M02 登录基线：错误凭据、成功进入工作空间、375px 无横向滚动；两个 @a11y 用例只在页面渲染后跑 axe，没有点击“企业账号”、没有 providers 查询、没有授权跳转、没有联合登出。`/admin/users` 没有任何 E2E/axe。E2E stub 的 `handleApi`（`e2e/stub/server.mjs:2457`）只实现 M02/M03 子集，没有 OIDC providers/authorize/callback、租户 `/api/v1/users`、`tenant.user.manage` 权限或 `authSource` 字段；`grep -n 'oidc\|tenant.user.manage' e2e/stub/server.mjs` 无命中。V1 需要先扩展 stub/测试 IdP，再补 E2E-M03-03/04/07 与绑定页 axe/375/键盘。任务 AC #6 已明确“完整 E2E/a11y 由 V1 复验”，因此本条不阻塞 B4，但 M10 gate 前必须关闭。

### F-003（P3）sessionStorage 异常中断登出

`sessionStore()`（`federated-logout.ts:70`）只捕获访问异常，`store.setItem`（95）、`store.removeItem`（96）、`markFederatedLogoutAttempted` 的 `setItem`（124）、`clearFederatedLogout` 的 `removeItem`（132-133）都没有 try/catch。若存储写入因配额/隐私模式/企业策略抛错，`ApiClient.logout`（`client.ts:276`）会在服务端会话已失效后 reject；`WorkspaceLayout`/`ChatPage` 捕获后显示“退出登录失败”，且 `authStore` 未执行 `signOut`，与 ADR-0019 第 5 节“本地登出先执行且是权威结果”不一致。修复：所有存储操作 try/catch，失败时退化为不记忆/不导航，但不得让 `logout` 失败。

### F-004（P3）logout 错误归一回归

B4 前 `logout` 通过 `this.request`（`client.ts:1065-1078`）把网络异常包装成 `ApiError.network`；现在直接 `performRequest`（275），原始错误直接抛出。`describeApiError` 对非 ApiError 返回 fallback（`errors.ts:147-150`），所以网络断开时用户看到“退出登录失败，请重试。”而不是“无法连接服务器，请检查网络后重试。”。没有吞错，但错误语义与其它 API 不一致；建议 catch 后包装为 `ApiError.network`。

### F-005（P3）用户列表 100 条上限

`listUsers` 固定 `/api/v1/users?page=1&size=100`（`users/api.ts:100-102`），`listTenantUsers` 同理（139-141）；页面只渲染本次 items（`admin/users/index.tsx:100-105`），没有分页/搜索/截断提示。演示规模无影响，真实租户超过 100 个账号时后续账号无法被绑定/解绑。建议 Lead/Product 明确 M10 是否接受；若接受，在模块评审中记录，若需要则在 B4 后续任务补分页。

### F-006（P3）联合登出状态残留

`rememberFederatedLogoutUrl(null)` 无响应头时直接 return false（`federated-logout.ts:88-90`），不清理旧地址；OIDC 成功由后端 302 到 `/`（`OidcLoginSuccessHandler.java:125`），不经过登录页 `handleFinish` 的 `clearFederatedLogout`（`login/index.tsx:152`）。因此一次失败/取消的联合登出可能在后续 OIDC 登录后保留旧 URL/attempted 标记；后续切换 LOCAL 账号登出时可能显示旧 IdP 的“重试退出企业账号”按钮，极端情况下（attempted 被清除）存在误导航窗口。建议每次 `logout` 先清空旧地址，或在会话建立/登录成功时统一清理；V1 可加一条状态残留用例。
