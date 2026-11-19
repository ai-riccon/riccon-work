---
review_id: REV-M15-001
module_id: M15
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M15_TASK-0086_20261119
  model: deepseek-v4.1-flash
  independent: true
review_kind: module
verdict: fail
reviewed_evidence:
  - EV-0449
  - EV-0450
  - EV-0466
  - EV-0467
  - EV-0468
  - EV-0469
  - EV-0470
  - EV-0471
  - EV-0472
  - EV-0473
  - EV-0474
  - EV-0476
  - EV-0477
dod:
  acceptance: false
  build: true
  tests: true
  coverage: true
  e2e: false
  review: true
  docs: true
  evidence: true
dod_evidence:
  build:
    - EV-0467
  tests:
    - EV-0468
  coverage:
    - EV-0469
  docs:
    - EV-0473
findings:
  - id: F-001
    severity: P1
    status: open
    title: "导出页“下载 CSV”与“查看详情”按钮 aria-label 不含可见文本（WCAG 2.5.3）"
    evidence: "EV-0476/EV-0477；real-stack-playwright.log E2E-M03-07；apps/web/src/pages/admin/tenant-exports/index.tsx:271,280"
  - id: F-002
    severity: P2
    status: fixed
    title: "M10 E2E-M03-07 在页面渲染前执行 axe，a11y 证据假绿"
    evidence: "修正为等待真实内容后扫描；EV-0476/EV-0477 暴露 F-001/F-003/F-004"
  - id: F-003
    severity: P2
    status: accepted
    title: "用户管理“启用”绿色状态标签对比度 3.37:1，低于 WCAG AA 4.5:1"
    evidence: "stub E2E-M15-03；apps/web/src/pages/admin/users/index.tsx:280；antd 6 默认 Tag 配色"
  - id: F-004
    severity: P2
    status: accepted
    title: "审计页“查看事件详情”按钮同类 label-content-name-mismatch（既有）"
    evidence: "EV-0476/EV-0477；apps/web/src/pages/admin/audit/index.tsx:272；建议 TASK-0087 一并修复"
  - id: F-005
    severity: P2
    status: accepted
    title: "OIDC 身份键 collation 弱于精确匹配（B2 F-001 residual）"
    evidence: "real-stack caseVariantSubjectMatched=true；不声称已修复"
created_at: "2026-11-19T22:56:00+08:00"
---

# REV-M15-001：M15（M10 验收补丁：来源 IdP 展示）模块独立验证

## 1. 结论

**verdict: fail（模块级）。** R-M10-01 的来源 IdP 用户可见行为已经实现并通过独立真实栈验证，但**最终真实栈 E2E 在 `/admin/tenant-exports` 暴露 P1 可访问性缺陷**，导致 M15 的 e2e/acceptance DoD 不成立，M15 不能进入 gate，也不能提交 Product 复验：

- P1：`/admin/tenant-exports` 的“下载 CSV”按钮可见文本为“下载 CSV”，`aria-label` 为“下载导出 <导出ID>”；可见文本未包含在可访问名称中，触发 axe `label-content-name-mismatch`（WCAG 2.5.3，serious）。同页“查看详情”按钮（`aria-label="查看导出详情 <id>"`）存在同类问题。
- 来源 IdP 本身通过：企业登录后工作空间显示“来源：Alpha 测试企业 IdP”；LOCAL 显示“来源：本地账号”；用户管理列表显示配置 providerName 与历史 issuer host（`beta-idp.example.test`）且可区分；会话/列表/绑定响应不含完整 issuer、subject、邮箱或令牌；真实栈 E2E-M15-01/02/03 在 EV-0466 全绿，EV-0477 的 Playwright 日志也显示这三条通过（当日 17/18 passed，唯一失败为 E2E-M03-07 的 P1 a11y）。
- 本结论是独立技术验收，不是 Product 预验收或用户产品验收。R-M10-01 的“UI 文案/可区分性”已在实现层面修复，但 M15 gate 被 P1 阻塞；Product 复验应等待 TASK-0087 修复后重新运行本评审的 E2E。

## 2. 验证范围与证据

| 检查      | 命令 / 场景                                                         | 结果                                                                                                                    |
| --------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| build     | `./mvnw -q -T1C -DskipTests package && pnpm -w build`               | exit 0（EV-0467）                                                                                                       |
| tests     | `./mvnw -q -T1C verify && pnpm -w test`                             | exit 0；后端 765 tests（0 fail/0 error/0 skip），前端 381 passed（EV-0468）                                             |
| coverage  | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage` | exit 0；API line 91.53% / branch 74.13%；Worker line 87.87% / branch 70.77%；Web line 87.71% / branch 80.75%（EV-0469） |
| 合同 E2E  | `docker compose ... up -d --wait && pnpm -w e2e`                    | exit 0；29 passed（含 M15 stub 3 条，EV-0470）                                                                          |
| a11y      | `pnpm -w a11y`                                                      | exit 0；9 passed（EV-0471）                                                                                             |
| security  | `trivy fs --scanners vuln,secret,misconfig ... --exit-code 1 .`     | exit 0（EV-0472）                                                                                                       |
| typecheck | `pnpm -w typecheck && pnpm -w lint`                                 | exit 0（EV-0474）                                                                                                       |
| docs      | `git diff --check`                                                  | exit 0（EV-0473）                                                                                                       |
| 真实栈    | `REAL_EVIDENCE_TASK=TASK-0086 node scripts/real-stack/run.mjs`      | **exit 1**（EV-0477；最终提交 8cc73a2）；浏览器 17 passed / 1 failed                                                    |

测试/覆盖率证据在代码提交 `2b761cf` 上录制；之后仅修改 `e2e/real/tests/m10.spec.ts`（等待渲染与已知残余白名单），生产/前端代码未变。真实栈最终提交为 `8cc73a2`。

## 3. 来源 IdP（R-M10-01）验证

真实栈 `m10-checks`（EV-0476/EV-0477 的 `real-stack-m10-checks.log`）与浏览器（EV-0466）独立确认：

- 后端：绑定响应与会话 `identityProvider=Alpha 测试企业 IdP`；LOCAL 会话省略该字段；租户/平台用户列表一致；`beta-idp.example.test` 历史 issuer 回退 host；完整 issuer/`/realms/`/subject/邮箱/token 不出现（EV-0449 定向测试 + EV-0466/EV-0476 真实栈）。
- 前端：会话头部 `来源：<providerName>`、LOCAL `来源：本地账号`；用户管理列表两 issuer 文案不同；375px 无横向滚动；键盘焦点不进入来源文本；新增文案 axe 无 serious/critical（stub E2E-M15-03、真实栈 E2E-M15-03）。
- 前端单元测试 381 passed（EV-0450/EV-0468）；OpenAPI 字段与前端类型对齐（EV-0449/EV-0450）。

## 4. P1 失败详情（EV-0476/EV-0477）

- 页面：`/admin/tenant-exports`（审计导出，租户管理员）。
- 违规：`label-content-name-mismatch`，impact `serious`，WCAG 2.1 AA 2.5.3。
- 元素：
  - `apps/web/src/pages/admin/tenant-exports/index.tsx:280`：`<button aria-label="下载导出 <id>"><span>下载 CSV</span></button>`；可访问名称不包含可见文本“下载 CSV”。
  - `apps/web/src/pages/admin/tenant-exports/index.tsx:271`：`<button aria-label="查看导出详情 <id>"><span>查看详情</span></button>`；可访问名称不包含“查看详情”。
- 期望：可见文本完整包含在可访问名称中，例如 `aria-label="下载 CSV：导出 <id>"` / `aria-label="查看详情：导出 <id>"`，或让可见文本与名称一致。
- 复现：真实栈登录 Alpha 管理员 → `/admin/tenant-exports`（存在导出申请行）→ axe 扫描；`.riccon/evidence/TASK-0086/EV-0477-E2E-M03-07-failed.png` 与 `EV-0477-E2E-M03-07-trace.zip`（Playwright trace 可用 `pnpm exec playwright show-trace` 打开）。
- 日志：`.riccon/evidence/TASK-0086/real-stack-playwright.log`（EV-0477）。

## 5. 其他 Findings

- **F-002（P2，fixed）**：M10 的 E2E-M03-07 在 `page.goto` 后用 `.ant-skeleton` 计数判断渲染完成，可能在 React 挂载前扫描空页，导致 M10 a11y 证据假绿。M15 已改为等待审计/导出/用户管理真实内容后扫描；这才暴露 F-001/F-003/F-004。
- **F-003（P2，accepted）**：用户管理“启用”绿色 Tag 对比度 3.37:1（12px），低于 WCAG AA 4.5:1；自 M02/M03 起存在，不属于来源 IdP 变更面。M15-03/stub 测试只放行该已知节点，建议后续 UI 主题任务统一调整成功色文本。
- **F-004（P2，accepted）**：审计页“查看事件详情 <动作>”按钮同类 label-content-name-mismatch（`apps/web/src/pages/admin/audit/index.tsx:272`）；与 F-001 同规则、既有问题；建议 TASK-0087 一并修复，未修复前由 M03-07 的已知残余白名单显式放行。
- **F-005（P2，accepted/residual）**：B2 F-001 collation 仍为 residual；真实栈 `caseVariantSubjectMatched=true`，不声称已修复。

## 6. Gate 与下一步

- **M15 gate 不就绪**：verdict fail；`dod.acceptance=false`、`dod.e2e=false`；P1 F-001 必须在 gate 前修复。
- 建议 TASK-0087（UI fix）范围：`apps/web/src/pages/admin/tenant-exports/**`，修复“查看详情/下载 CSV”按钮可访问名称与可见文本一致；建议同时覆盖 `apps/web/src/pages/admin/audit/**` 的同规则问题（F-004）。
- 修复后需重新运行真实栈 E2E（至少 E2E-M03-07/E2E-M15-03）与 `pnpm -w a11y`，更新本评审并重跑 M15 gate；Product 复验 R-M10-01/AC-08/10 在 gate 通过后进行。
