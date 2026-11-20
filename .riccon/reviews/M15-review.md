---
review_id: REV-M15-002
previous_review: REV-M15-001
module_id: M15
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
  - EV-0449
  - EV-0450
  - EV-0478
  - EV-0480
  - EV-0481
  - EV-0482
  - EV-0483
  - EV-0484
  - EV-0485
  - EV-0486
  - EV-0488
  - EV-0489
  - EV-0496
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
    - EV-0449
    - EV-0450
    - EV-0478
    - EV-0488
  build:
    - EV-0482
  tests:
    - EV-0496
  coverage:
    - EV-0496
  e2e:
    - EV-0485
  docs:
    - EV-0489
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "导出页“下载 CSV/查看详情”按钮可访问名称不含可见文本（WCAG 2.5.3）"
    evidence: "TASK-0087/c4ebd3e；EV-0478；EV-0480 定向 2 passed；EV-0488 完整 real-stack 18 passed，label-content-name-mismatch 0 命中"
  - id: F-002
    severity: P2
    status: fixed
    title: "M10 E2E-M03-07 在页面渲染前执行 axe，a11y 证据假绿"
    evidence: "修正为等待真实内容后扫描；EV-0480/EV-0488 在修复后重新执行"
  - id: F-003
    severity: P2
    status: accepted
    title: "antd 绿色状态标签（启用/已批准）对比度 3.37:1，低于 WCAG AA 4.5:1"
    evidence: "EV-0481/EV-0488；既有 antd 6 Tag 配色；stub/真实栈测试仅放行 node.html 命中 ant-tag-green 的节点"
  - id: F-004
    severity: P2
    status: fixed
    title: "审计页“查看事件详情”按钮同类 label-content-name-mismatch"
    evidence: "TASK-0087/c4ebd3e；EV-0478；EV-0488 完整 real-stack 通过"
  - id: F-005
    severity: P2
    status: accepted
    title: "OIDC 身份键 collation 弱于精确匹配（B2 F-001 residual）"
    evidence: "real-stack caseVariantSubjectMatched=true；不声称已修复"
  - id: F-006
    severity: P3
    status: accepted
    title: "两 issuer 真实栈夹具用测试 SQL 模拟历史 issuer"
    evidence: "绑定 API 只接受租户当前配置 issuer；夹具只写测试栈，不改变产品契约"
  - id: REVALIDATION
    severity: P3
    status: fixed
    title: "2026-11-20 用当前内核按合同 commands.test 重录 test/coverage；旧 review 归档为历史"
    evidence: "EV-0496；旧 review REV-M15-001 归档于 .riccon/archive/reviews/M15-review-REV-M15-001.md"
created_at: "2026-11-20T14:04:00+08:00"
---

# REV-M15-001：M15（M10 验收补丁：来源 IdP 展示）模块独立验证

## 0. 回溯说明（2026-11-20）

- **回溯原因**：DEC-0022 合并后合同 `commands.test` 改为 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`；本模块旧 test EV（EV-0483）命令文本不再匹配，`gate check` 在 2026-11-20 以 `EV-0483 command does not match commands.test` 失败。代码未回归，属于证据/流程漂移。
- **本次动作**：在当前 HEAD `cb977f9`（工作树干净）上用合同 `commands.test` 全量重跑一次，并按项目规则同时作为 `test` 与 `coverage` 证据：**EV-0496**（挂 TASK-0084），exit 0，`commit=cb977f98a493bfb7db9f9d4b02731ac62e195053`，`dirty=false`。
- **本次实测**：Java 765 tests（api 453 / worker 263 / platform-common 33 / arch 12 / domain 4），failures 0、errors 0、skipped 0；前端 46 文件 384 tests passed、0 skipped；前端覆盖率 statements 87.8% / branches 80.75% / functions 84.63% / lines 87.71%；Maven `verify` 含 `jacoco:check` 通过。
- **评审边界**：本次只重录 test/coverage；真实栈 E2E 18 项、a11y、security、来源 IdP 展示与按钮可访问名称复验未重跑，仍绑定旧 EV（见正文 §2–§5），原结论不扩大。旧 review `REV-M15-001` 原样归档在 `.riccon/archive/reviews/M15-review-REV-M15-001.md`。
- **Product 复验**：M15-AC-03 的 Product 复验不由本次技术回溯代替；本评审只恢复 gate 的证据链。
- **findings 处理**：旧 findings 全部保留，状态未改（F-001/F-002/F-004 fixed，F-003/F-005/F-006 accepted）；正文为旧评审原文，当前状态以 front matter 为准。


## 1. 结论

**verdict: pass（模块级）。** R-M10-01 的来源 IdP 用户可见结果与 TASK-0087 的按钮可访问名称修复均已在最终提交上独立复验：

- 企业登录后工作空间显示“来源：Alpha 测试企业 IdP”；LOCAL 显示“来源：本地账号”；用户管理列表显示配置 providerName 与历史 issuer host（`beta-idp.example.test`）且可区分；会话/列表/绑定响应不含完整 issuer、subject、邮箱或令牌。
- TASK-0087 修复后，审计/导出页面“查看详情/下载 CSV”按钮的 `aria-label` 以可见文本开头并保留行上下文；完整真实栈 E2E 18 passed，`label-content-name-mismatch` 0 命中。
- 合同 build/test/coverage/test_e2e/a11y/security 与一次完整真实栈全部 exit 0；无 P0/P1 开放。

本结论是独立技术验收，不是 Product 预验收或用户产品验收。M15 gate 的最终通过还需 Lead 将 TASK-0086/TASK-0087 置 done 并记录本评审；随后由 Product 复验 M10 AC-08/10，再由 Lead 提交用户最终验收。

## 2. 验证范围与证据

| 检查       | 命令 / 场景                                                         | 结果                                                                                                                    |
| ---------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| build      | `./mvnw -q -T1C -DskipTests package && pnpm -w build`               | exit 0（EV-0482）                                                                                                       |
| tests      | `./mvnw -q -T1C verify && pnpm -w test`                             | exit 0；后端 765 tests（0 fail/0 error/0 skip），前端 384 passed（EV-0483）                                             |
| coverage   | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage` | exit 0；API line 91.53% / branch 74.13%；Worker line 87.87% / branch 70.77%；Web line 87.71% / branch 80.75%（EV-0484） |
| 合同 E2E   | `docker compose ... up -d --wait && pnpm -w e2e`                    | exit 0；29 passed（含 M15 stub 3 条，EV-0485）                                                                          |
| a11y       | `pnpm -w a11y`                                                      | exit 0；9 passed（EV-0481）                                                                                             |
| security   | `trivy fs --scanners vuln,secret,misconfig ... --exit-code 1 .`     | exit 0（EV-0486）                                                                                                       |
| 定向真实栈 | `REAL_PLAYWRIGHT_GREP='E2E-M03-07\|E2E-M15-03' ... run.mjs`         | exit 0；2 passed（EV-0480；securityChecks 按设计 skipped-targeted）                                                     |
| 完整真实栈 | `REAL_EVIDENCE_TASK=TASK-0086 node scripts/real-stack/run.mjs`      | exit 0；18 passed，全部阶段 passed（EV-0488）                                                                           |
| docs       | `git diff --check`                                                  | exit 0（EV-0489，干净树）                                                                                               |

DoD 代码证据在最终代码提交 `864b0a7` 录制；docs 证据 EV-0489 在随后证据提交 `de33ed8` 的干净树录制（均 `dirty=false`）。全部非 manual、exit 0。TASK-0087 的组件测试/typecheck/lint 证据为 EV-0478。

## 3. 来源 IdP（R-M10-01）验证

真实栈 `m10-checks`（EV-0488 的 `real-stack-m10-checks.log`/`real-stack-m10-summary.json`）与浏览器 E2E 独立确认：

- 后端：绑定响应与会话 `identityProvider=Alpha 测试企业 IdP`；LOCAL 会话省略该字段；租户/平台用户列表一致；`beta-idp.example.test` 历史 issuer 回退 host；完整 issuer/`/realms/`/subject/邮箱/token 不出现（EV-0449 定向测试 + EV-0488 真实栈）。
- 前端：会话头部 `来源：<providerName>`、LOCAL `来源：本地账号`；用户管理列表两 issuer 文案不同；375px 无横向滚动；键盘焦点不进入来源文本；新增文案 axe 无 serious/critical（EV-0450/EV-0478/EV-0480/EV-0488）。
- 浏览器 E2E-M15-01/02/03 在完整真实栈 18 passed 中全部通过（EV-0488 的 `real-stack-playwright.log`）。

## 4. P1 回归（F-001/F-004）

- 旧问题：`/admin/tenant-exports` 的“下载 CSV”按钮 `aria-label="下载导出 <id>"` 不包含可见文本“下载 CSV”；审计/导出“查看详情”按钮同类（WCAG 2.5.3，serious）。旧证据 EV-0476/EV-0477 与 trace 保留在 `.riccon/evidence/TASK-0086/`（历史）。
- 修复：`c4ebd3e` 将 aria-label 改为 `下载 CSV：导出 <id>`、`查看详情：导出/事件 <id>`，并给批准/拒绝按钮加 `autoInsertSpace={false}`；平台页同类。
- 复验：定向真实栈 E2E-M03-07 + E2E-M15-03 2 passed（EV-0480）；完整真实栈 18 passed（EV-0488），`label-content-name-mismatch` 0 命中；`pnpm -w a11y` 9 passed（EV-0481）。F-001/F-004 fixed。

## 5. 其他 Findings

- **F-002（P2，fixed）**：M10 的 E2E-M03-07 在 `page.goto` 后用 `.ant-skeleton` 计数判断渲染完成，可能在 React 挂载前扫描空页，导致 M10 a11y 证据假绿。M15 已改为等待审计/导出/用户管理真实内容后扫描；这也是 F-001/F-004 被发现的直接原因。修正后的测试在 EV-0480/EV-0488 重新执行通过。
- **F-003（P2，accepted）**：antd 6 绿色 `Tag color="green"`（启用/已批准）在 12px 下对比度约 3.37:1，低于 WCAG AA 4.5:1；自 M02/M03 起存在，不属于来源 IdP 变更面。修正后的测试只放行 `node.html` 命中 `ant-tag-green` 的 color-contrast 节点，其他 serious/critical 仍失败；后续 UI 主题任务统一调整成功色文本。
- **F-005（P2，accepted/residual）**：B2 F-001 collation 仍为 residual；真实栈 `caseVariantSubjectMatched=true`，不声称已修复。
- **F-006（P3，accepted）**：两 issuer 真实栈夹具用测试 SQL 模拟历史 issuer（绑定 API 只接受租户当前配置 issuer）；只写测试栈，不改变产品契约。

## 6. Gate 与下一步

- 评审层面 **pass**；无开放 P0/P1；DoD 八项均 true，合同命令绑定见 `dod_evidence`。
- M15 gate 仍依赖 Lead 将 TASK-0086/TASK-0087 置 done 并记录本评审；Product 复验 M10 AC-08/10 在 gate 通过后进行，再由 Lead 提交用户最终验收。
- 全量只在最终提交执行一次（EV-0482..0486、EV-0488）；定向复验为 EV-0480/EV-0481，未重复全量。
