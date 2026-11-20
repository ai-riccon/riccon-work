---
review_id: REV-M18-001
module_id: M18
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e07d3f38bffeSmo9kdP9zb096n
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: f4f399ab085a1c97377b7a7d4e2272033051f496
verdict: fail
verdict_note: "M18 的 acceptance 与最终 DoD 证据成立；但 TASK-0094 证据列表保留了 dirty 失败记录 EV-0511（含 5 个 API 测试失败），strict 绑定下 M18 gate 无法通过。删除/归档该记录、书面说明失败根因并复核后，本评审可转 pass。"
reviewed_evidence:
  - EV-0498
  - EV-0500
  - EV-0506
  - EV-0507
  - EV-0508
  - EV-0509
  - EV-0512
  - EV-0513
  - EV-0514
  - EV-0515
  - EV-0516
  - EV-0517
  - EV-0518
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
  security: true
dod_evidence:
  acceptance:
    - EV-0517
  build:
    - EV-0512
  tests:
    - EV-0513
  coverage:
    - EV-0513
  e2e:
    - EV-0514
  docs:
    - EV-0518
  evidence:
    - EV-0517
  security:
    - EV-0516
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "M02-AC-16 完成态引用链接与停止生成对比度不足（低于 WCAG AA 4.5:1）"
    evidence: "apps/web/src/pages/chat/chat.css:183-263（停止生成 #cf1322/#a8071a/#820014；引用 link 默认 #0958d9、hover #003eb3、active #002c8c）；apps/web/src/pages/chat/index.tsx:738（chat-stop-button）；EV-0508（J1 5/5）、EV-0509（J1 6/6，含颜色断言与 axe）、EV-0515（全量 a11y 13 passed）。按 #f0f0f0 气泡计算引用默认 5.4:1、hover 7.9:1；停止生成白底 5.57:1。"
  - id: F-002
    severity: P2
    status: fixed
    title: "上传/default 按钮 hover 与失败态 danger/重试文本对比度不足"
    evidence: "chat.css:241-260（outlined hover #0958d9；danger 文本 #cf1322）；EV-0509（上传按钮 hover rgb(9,88,217)、danger rgb(207,19,34)、重试 hover rgb(0,62,179) 且 axe 无 serious/critical）、EV-0515。"
  - id: F-003
    severity: P2
    status: accepted
    title: "primary 实心按钮 hover 白字 #2e7ae6 仅 4.16:1，窄 allowlist 暂放行"
    owner: riccon/builder-web
    due: "2026-12-04"
    allowlist: "仅放行 target 命中 .ant-btn-primary 的 color-contrast 节点；其他 serious/critical 仍失败；修复后删除 helper。"
    evidence: "e2e/tests/j1.spec.ts:20-55（isKnownPrimaryHoverContrast/expectNoNewSeriousA11yViolations）、:269-274（仅失败态空页使用）；docs/ACCEPTANCE.md:218/230；EV-0515（j1 失败态 @a11y 通过）。未声称已修复，未扩大 allowlist 到其他节点。"
  - id: F-004
    severity: P1
    status: open
    title: "TASK-0094 保留 dirty 失败证据 EV-0511（5 个 API 测试失败），strict gate 失败且失败根因未记录"
    evidence: ".riccon/tasks/TASK-0094.json:20-21；.riccon/evidence/TASK-0094/EV-0511.json（dirty=true、exit_code=1、commit=eabb184）；EV-0511 日志：api `Tests run: 453, Failures: 5`（ChatScopeApiTest.stopGeneration_creatorOrRunInitiatorOnly、ChatStreamApiTest.stopGeneration_preservesContentAndAudits、AuditExportApiTest.list_filtersByStatus、AuditExportClockTest.download_shortTtlExpiresWithClock、AuditExportPlatformApiTest.platformApproval_singleAdminTenant）；gate check 2026-11-20T17:30:13+08:00 报 `1 evidence record(s) were recorded from a dirty working tree: EV-0511`。"
    note: "eabb184..ec6a650 之间没有 Java 代码变更（只有 .riccon、AGENTS.md、docs/COLLABORATION.md、.serena/project.yml）；最终 clean EV-0513 为 764 tests / 0 fail / 0 error / 0 skip，且 `apps/api/target/surefire-reports` 中这 5 个类在 EV-0513 运行窗口（16:29–16:32）均为 0 failures/0 errors/0 skipped。失败很可能是并发 Serena JDT LS 触碰 target/ 或测试环境隔离问题（ec6a650 才加入该约束），但没有任何书面根因或定向复跑证据，属于 retry 掩盖失败。"
  - id: F-005
    severity: P2
    status: open
    title: "外部残余：M01 仍有 5 条 open P2（F-002/F-004/F-005/F-006/F-007，F-007 为 MySQL 驱动发布阻断）"
    evidence: "REV-M01-002 front matter；EV-0517 M01 gate 8 warnings；不属于 M18 修复面。"
  - id: F-006
    severity: P3
    status: open
    title: "外部残余：M02 F-009/F-014 仍 open P3"
    evidence: "REV-M02-004；EV-0517 M02 gate 2 warnings。"
  - id: F-007
    severity: P3
    status: open
    title: "外部残余：M11 仍 planned 但有 done 任务；M14 docs-only 仍 pending"
    evidence: "EV-0517 state validate 1 warning（M11 TASK-0035/0036 done）；M14 模块 status=building、product_acceptance=pending。"
created_at: "2026-11-20T17:34:00+08:00"
---

# REV-M18-001：M18（已完成模块 gate 证据回溯）模块独立验证

## 1. 结论

**verdict: fail（模块级独立技术验收）。**

M18 的六条 acceptance 与最终 DoD 证据本身成立：7 个回溯模块在新 review 下 gate 全部 PASS（EV-0498/EV-0517）；M02 回溯 Product 预验收为 `pass`（with residual F-003）；AC-02 逐格式 UI 证据与 AC-16 a11y P1 修复已由 EV-0508/0509/0514/0515 支撑；最终代码 `ec6a650` 的 build/test/coverage/E2E/a11y/security 全部干净通过（EV-0512..0516），state 0 errors/1 M11 warning、doctor OK（EV-0517）。

但 **M18 gate 仍不能通过**：TASK-0094 的 `evidence` 列表保留了 `EV-0511`——一条 `dirty=true`、`exit_code=1` 的失败全量测试，且该次运行有 **5 个 API 测试失败**。strict 绑定下内核直接判 fail；同时该失败没有任何书面根因或定向复跑说明，属于“retry 掩盖失败”的假绿风险。必须先做证据卫生与根因收口，本评审才能转 `pass`。

本评审是独立技术验收，不是 Product 预验收，也不是用户产品验收；M18 的 `product_acceptance` 仍为 `pending`，不得由本评审替代。

## 2. M18 acceptance 对账

| #   | M18 acceptance 原文（摘要）                                                                                               | 结果                          | 证据                                                                                                                                                                         | 说明                                                                                                                                                                                                                                                                 |
| --- | ------------------------------------------------------------------------------------------------------------------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | M01/M02/M03/M10/M12/M13/M15 在当前 HEAD 按合同 `commands.test` 重录 test/coverage，逐个 gate 通过或登记 N/A               | **pass**                      | EV-0490..EV-0496（第一阶段）、EV-0498（7 模块 gate 循环）、EV-0517（最终 gate 循环）                                                                                         | 7/7 gate PASS；M01 8 warnings、M02 2 warnings，其余 0；EV-0490..0496 均为 `manual=false`、`dirty=false`、exit 0。                                                                                                                                                    |
| 2   | 每个受影响模块有新 module review，`reviewer.session` 为真实 `ses_...`，`dod_evidence` 绑新 EV，旧 review 保留为历史       | **pass**                      | REV-M01-002/M02-004/M03-002/M10-002/M12-002/M13-002/M15-002；`.riccon/archive/reviews/*-REV-*-001.md`                                                                        | 7 份新 review 的 session 均为 `ses_e08d1b473ffezoltlVNmpba9tZ`（真实格式），`previous_review` 指向旧 ID，`dod_evidence.tests/coverage` 指向 EV-0490..0496；归档旧 review 与 `e44a809~1` 的 git 版本逐字节一致，未改写。                                              |
| 3   | M02 补 Product 预验收与用户最终验收；M03/M10/M15 补丁式复验；M01/M12 legacy/no-impact；AC/证据缺口写入评审包              | **pass（with pending user）** | `docs/ACCEPTANCE.md`「M18 回溯复验」「Product 预验收（M02，M18 回溯）」「用户最终验收（M02/M18 回溯）」；REV-M03-002/M10-002/M13-002/M15-002                                 | Product 预验收 M02 为 `pass`（with residual F-003），M03/M10/M13/M15 复验已记录，M01/M12 legacy 已登记；用户最终验收仍为 `pending` 决策包，Product 未伪造 `accepted`。该项按规则需用户签字，本评审不代签，也不计为技术失败。                                         |
| 4   | `state validate` 0 errors（仅允许 M11 legacy warning）；doctor pass；除 M02 AC-16 a11y 对比度修复外不改生产源码/测试/迁移 | **pass**                      | EV-0517（state 0 errors/1 warning、doctor OK）；`git diff cb977f9~1..HEAD`                                                                                                   | 生产代码变更仅 `apps/web/src/pages/chat/chat.css` 与 `index.tsx` 的 `chat-stop-button` className；E2E 资产仅 `e2e/tests/j1.spec.ts`；无迁移、无合同 YAML 变更。`ec6a650` 另含 AGENTS/COLLABORATION/.serena 配置提交，不在任何 M18 任务 write_scope，见 §7 边界说明。 |
| 5   | M02-AC-02 逐格式 UI 证据补齐（XLSX/PNG/JPEG 上传→解析→提问）或用户书面接受残余                                            | **pass**                      | EV-0509（j1 6/6：XLSX/PNG/JPEG 各 1 + PDF 闭环 + 运行详情 + 失败态）、EV-0500/0506（前两轮 5/5）、EV-0514（合同 E2E 33 passed 含三格式）、EV-0515（a11y 13 passed 含三格式） | 浏览器断言“解析成功”→提问→引用；XLSX/PNG/JPEG 使用 stub 解析验证 UI 路径，真实解析由 `DocumentParserTest`/`FileParseServiceIntegrationTest` 覆盖，缺口已在 `docs/ACCEPTANCE.md` 标注，不冒充真实栈。                                                                 |
| 6   | M02-AC-16 a11y P1 修复：完成态引用链接与停止生成对比度 ≥4.5:1；定向 E2E + axe 复验                                        | **pass**                      | EV-0507（chat 单测/typecheck/lint）、EV-0508（J1 5/5）、EV-0509（J1 6/6）、EV-0515（a11y 13 passed）；chat.css + index.tsx                                                   | 引用默认 #0958d9 / hover #003eb3，停止生成 #cf1322/#a8071a，均高于 4.5:1；E2E 断言实际计算颜色并执行 axe。F-003（primary 实心按钮 hover）是另一节点，P2 accepted，不声称已修复。                                                                                     |

## 3. DoD 与最终全量证据

最终代码提交 `ec6a650`；`ec6a650..HEAD` 只改 `.riccon/**` 与 `docs/ACCEPTANCE.md`，无代码变更。以下证据均为 `manual=false`、`dirty=false`、exit 0、绑定当前提交：

| DoD               | EV      | 命令 / 结果                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| ----------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| build             | EV-0512 | `./mvnw -q -T1C -DskipTests package && pnpm -w build`，exit 0（合同 `commands.build` 原文）                                                                                                                                                                                                                                                                                                                                                              |
| tests / coverage  | EV-0513 | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`，exit 0（合同 `commands.test`/`commands.coverage` 原文）；Java 764 tests（api 453 / worker 262 / platform-common 33 / arch 12 / domain 4），failures 0、errors 0、skipped 0；前端 46 文件 384 tests passed、0 skipped；覆盖率 API lines 91.53% / branches 73.79%，worker lines 87.58% / branches 70.34%，web lines 87.71% / branches 80.75%，均高于合同 80/70；`jacoco:check` 通过。 |
| e2e               | EV-0514 | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`（合同 `commands.test_e2e` 原文），33 passed；含 XLSX/PNG/JPEG 三格式、375px 与键盘路径。                                                                                                                                                                                                                                                                          |
| a11y              | EV-0515 | `pnpm -w a11y`（合同 `commands.a11y` 原文），13 passed；j1 三格式、流式/引用/完成态、失败态均覆盖。                                                                                                                                                                                                                                                                                                                                                      |
| security          | EV-0516 | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`（合同 `commands.security` 原文），exit 0；仅 `CVE-2026-35568`/`CVE-2026-34237` 按 `.trivyignore` + ADR-0011（exp 2027-03-31）抑制，不是新增例外。                                                                                                                                                                               |
| acceptance / gate | EV-0517 | 7 模块 gate 循环 + `state validate` + `doctor`：7/7 PASS；state OK（0 errors，1 warning：M11 planned 但有 done 任务）；doctor OK（0 errors/0 warnings）。                                                                                                                                                                                                                                                                                                |
| docs              | EV-0518 | `git diff --check && grep -q "M02-AC-02" docs/ACCEPTANCE.md && grep -q "M18 回溯复验" docs/ACCEPTANCE.md`，exit 0；本评审另行人工核对了 `docs/ACCEPTANCE.md` 的 M18/M02/M03/M10/M13/M15/M01/M12 段。                                                                                                                                                                                                                                                     |

**失败尝试（必须处理）**：TASK-0094 另留有 EV-0510（`eabb184`、build、clean exit 0）与 EV-0511（`eabb184`、test/coverage、**dirty=true、exit 1**）。EV-0511 的 api surefire 报 `Tests run: 453, Failures: 5`：

- `ChatScopeApiTest.stopGeneration_creatorOrRunInitiatorOnly`（expected 202，was 500）
- `ChatStreamApiTest.stopGeneration_preservesContentAndAudits`（expected 202，was 500）
- `AuditExportApiTest.list_filtersByStatus`（expected 1L，was 0L）
- `AuditExportClockTest.download_shortTtlExpiresWithClock`（expected 1L，was 0L）
- `AuditExportPlatformApiTest.platformApproval_singleAdminTenant`（expected 200，was 500）

`eabb184..ec6a650` 没有 Java 代码变更；最终 clean 重跑 EV-0513 这 5 个测试全部通过。这更像并发 Serena JDT LS / `target/` 污染或测试环境隔离问题（`ec6a650` 才把该约束写进 AGENTS），但任务/证据/文档均未记录根因或定向复跑，属于 retry 掩盖失败。详见 F-004。

## 4. 7 个模块 review 与 gate 复核

- **review 元数据**：7 份新 review 的 `reviewer.agent=riccon/verifier`、`session=ses_e08d1b473ffezoltlVNmpba9tZ`、`model=deepseek-v4.1-flash`、`independent=true`；`head_commit=cb977f98…`；`review_kind=module`。
- **DoD 绑定**：`dod_evidence.tests/coverage` 均指向 EV-0490..EV-0496，其余 acceptance/build/e2e/docs 沿用旧 EV；EV-0517 逐项验证通过。
- **旧 review 保留**：7 个归档文件与归档前 git 版本逐字节一致，未被删除或改写。
- **findings 状态**：仅 M01 F-001/F-003 从 open 改 fixed（EV-0490 + `apps/web/vitest.config.ts` 已排除 `.umi-production`，前端覆盖率 87.71%/80.75%），其余所有模块的 accepted/fixed/open 状态与旧 review 一致；M03 F-006..F-010、M10 F-001/F-005..F-015、M12 F-002..F-005/F-007、M13 F-001..F-006、M15 F-003/F-005/F-006 仍为 accepted，未被改写为 fixed。
- **gate**：EV-0517 中 M01/M02/M03/M10/M12/M13/M15 全部 PASS；M01 8 warnings、M02 2 warnings，与 `docs/ACCEPTANCE.md` 总表一致。

## 5. AC-02 / AC-16 与残余对账

### 5.1 M02-AC-02（逐格式 UI）

- EV-0509 的 j1 定向 6/6 明确包含 `XLSX 上传后显示解析成功并可提问引用`、`PNG …`、`JPEG …`，并断言文件名、`解析成功`、提问后的引用按钮与引用抽屉定位；EV-0514 合同 E2E 33 passed 再次包含三格式；EV-0515 a11y 13 passed 也覆盖三格式。
- 限制如实：三格式浏览器用例使用 stub API/解析（验证 UI 路径），真实解析器由集成测试覆盖；`docs/ACCEPTANCE.md` 已标注“真实栈未逐格式验证”，未冒充真实栈通过。判定 **pass**（UI 证据成立）。

### 5.2 M02-AC-16 与 F-003

- EV-0509 覆盖失败态 danger 文本、重试入口、流式/引用 hover/完成态 axe；EV-0514 覆盖四界面状态、375px/键盘；EV-0515 全量 13 passed。AC-16 判定 **pass（with residual）**。
- **F-001（P1，fixed）**：引用链接默认/hover、停止生成按钮实际计算颜色均 ≥4.5:1；E2E 用 `toHaveCSS` 锁定修复后 token，并用 axe 扫描，未只靠 CSS 注释。
- **F-002（P2，fixed）**：上传/default 按钮 hover 与失败态 danger/重试文本同样修复并有颜色断言 + axe。
- **F-003（P2，accepted，不是 fixed）**：primary 实心按钮 hover 白字 `#2e7ae6` 仍为 4.16:1；`e2e/tests/j1.spec.ts` 的 allowlist 仅放行 target 命中 `.ant-btn-primary` 的 color-contrast 节点，其他 serious/critical 仍失败；owner `builder-web`、到期 `2026-12-04`、修复后删除 helper。`docs/ACCEPTANCE.md` 明确写 accepted/residual，未声称全局零 serious/critical。
- **M03/M10 历史 accepted 未被改写**：逐项对比归档旧 review，M03 F-006..F-010、M10 F-001/F-005..F-015 状态保持 accepted；DEC-0016/DEC-0021 用户 accepted 未被重新解释。

## 6. Findings

### F-004（P1，open）：dirty 失败 EV-0511 阻断 strict gate，且 5 个测试失败无根因记录

- **位置**：`.riccon/tasks/TASK-0094.json:20-21`；`.riccon/evidence/TASK-0094/EV-0511.json`；`EV-0511-*.log`。
- **观察**：`EV-0511` 为 `dirty=true`、`exit_code=1`，api `Tests run: 453, Failures: 5`；`gate check --module M18` 在 strict 绑定下报 `1 evidence record(s) were recorded from a dirty working tree: EV-0511`。
- **期望**：strict 下不得保留 dirty 证据；失败尝试应像 EV-0497 一样从任务 evidence 移除/归档，并在报告/任务回执中写明根因与最终 clean 复跑结论，不能仅用 clean rerun 覆盖。
- **风险**：5 个失败覆盖停止生成与审计导出，若未来复现可能影响 M02/M10 的用户可见路径；现有 clean EV-0513 + EV-0490..0496 多次通过支持“环境性失败”判断，但缺少书面根因/定向复跑，仍属 retry 掩盖失败的假绿风险。
- **最小修复**：
  1. 从 TASK-0094 `evidence` 移除 EV-0511（保留日志作审计，可归档到 `.riccon/archive/evidence-superseded/TASK-0094/`），EV-0510 可一并移除（已被 EV-0512 取代，非 gate 必需）。
  2. 在 TASK-0094 回执或 `M18-revalidation-report.md` 记录 EV-0511 的根因（并发 Serena JDT LS/target 污染或测试环境隔离）与 clean 复跑结论；若无法确认，至少对 5 个失败类在干净环境定向复跑并记录 EV。
  3. 重跑 `gate check --module M18`；待 F-004 置 fixed、verdict 转 pass 后由 Lead 提交。
- **说明**：该 finding 不是产品代码回归；EV-0513 在最终代码上 764/0/0/0，E2E/a11y/security 均干净。它的严重级别来自 strict gate 的硬失败与未解释的失败历史，不是当前 HEAD 的功能缺陷。

### F-005 / F-006 / F-007（外部残余，open）

- M01：5 条 open P2（F-002 security、F-004 console、F-005 ProLayout a11y、F-006 legacy attestation、F-007 MySQL Connector/J 发布阻断）；M18 不修，未声称解决。EV-0517 M01 gate 8 warnings。
- M02：F-009/F-014 open P3；F-007/F-011/F-015 accepted P2 仍按 accepted 记录。EV-0517 M02 gate 2 warnings。
- M11 planned 但有 TASK-0035/0036 done（state 1 warning）；M14 docs-only 仍 pending。均为外部 legacy，不阻塞 M18 技术 gate 的 P0/P1 判定，但需 Lead/M11/M14 承接。

## 7. 边界与范围

- **生产代码**：`git diff cb977f9~1..HEAD` 中仅 `apps/web/src/pages/chat/chat.css`（+37 行）与 `apps/web/src/pages/chat/index.tsx`（+1 行 `className="chat-stop-button"`）属于产品代码，且都在 TASK-0093 的 `apps/web/src/pages/chat/**` 写范围内；无 Java/迁移/合同 YAML 变更。
- **测试资产**：`e2e/tests/j1.spec.ts`（+230 行）由 TASK-0092 编写，写范围包含 `e2e/tests/**`；未改 `e2e/stub/**`（本次 diff 无 stub 变更），无生产测试文件绕过。
- **配置/文档提交（观察，非 M18 交付）**：`ec6a650` 修改 `AGENTS.md` 团队区块、`docs/COLLABORATION.md`、`.serena/project.yml`，不在任何 M18 任务 `write_scope` 内，也不属于生产代码/测试/迁移。`riccon contract validate` 为 0 errors/0 warnings；建议 Lead 确认该提交是用户/Lead 的独立配置变更，而非 M18 任务产出。
- **旧 review**：已归档且逐字节一致；未删除。
- **未提交 git**：本评审只写 `.riccon/reviews/M18-review.md`；gate check 按内核行为写入 `.riccon/state.json` 的 gate 结果与 `updated_at`，不是手工修改。

## 8. Gate 结果

在 `M18-review.md` 写入后运行：

```bash
node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" gate check --module M18 --project .
```

实测结果（`2026-11-20T17:35:51+08:00`）：**FAIL，EXIT=1，Errors: 3，Warnings: 3**。三条 errors：

1. `1 evidence record(s) were recorded from a dirty working tree: EV-0511`（strict 绑定，F-004）。
2. `review verdict is fail, expected pass`（因为 F-004 P1 open）。
3. `P1 finding F-004 is open`（F-004 本身）。

3 条 warnings 为 F-005/F-006/F-007 外部残余（M01 open P2、M02 open P3、M11/M14 legacy）。DoD 证据绑定（EV-0512/0513/0514/0515/0516/0517/0518）全部 pass；7 个回溯模块的 gate 已在 EV-0517 中全部 PASS。**这不是 gate 标准或最终证据本身失败，而是 TASK-0094 证据列表的卫生问题 + 未解释失败。** 修复 F-004 后需重新运行 gate 并更新本评审。

## 9. 最小修复清单（按顺序）

1. **TASK-0094 证据列表**：移除 `EV-0511`；可选移除 `EV-0510`；保留日志文件并在 `M18-revalidation-report.md`/任务回执标注 superseded。
2. **失败根因**：书面记录 EV-0511 的 5 个 API 测试失败原因（并发 Serena JDT LS/target 污染或测试环境隔离），并在干净环境对 5 个测试类做一次定向复跑（或确认 EV-0513 的干净全量已覆盖），记录 EV。
3. **复核**：重新运行 `gate check --module M18`，确认 0 errors（允许 M01/M02 的 open P2/P3 warnings、M11 state warning）。
4. **评审更新**：Verifier 复核后将 F-004 置 `fixed`、verdict 转 `pass`；Lead 提交 `.riccon/**` 并记录 M18 module review。
5. **后续**：gate 通过后由 Product 做 M18 预验收，再由 Lead 提交用户最终验收；M02 用户最终验收仍按 `docs/ACCEPTANCE.md` 的 decision package 保持 pending。

## 10. 未验证项与限制

- **真实栈 E2E/a11y 未重跑**：EV-0299/EV-0488 为旧证据；本轮合同 E2E 为 stub API，真实栈 a11y 不能由 stub 代替。`docs/ACCEPTANCE.md` 已明确标注。
- **迁移回滚未重跑**：M10 V10 的迁移 drill 为旧证据；M18 未执行 rollback drill。
- **性能/k6 未执行**：`commands.perf` 未运行，不声称 p95 达标；M02 F-007 仍 accepted。
- **M18 用户最终验收**：pending，属于用户决策，不在本评审范围。
- **M11/M14**：未做模块级 gate/review；仅登记 legacy/pending。
- **7 份模块 review 的 head_commit**：停在 `cb977f9`（第一阶段）；`ec6a650` 的差异仅 chat CSS/className 与 E2E 测试资产，最终全量 EV-0512..0517 由本 M18 评审承接。若要求每份模块 review 与最终 HEAD 完全对齐，需另行回写 head_commit，但不改变其 gate 结论。

---

**评审包摘要**：M18 的 7 模块 gate 恢复、M02 AC-02/AC-16 证据与 a11y 修复均成立；**verdict=fail** 仅因 TASK-0094 保留了 dirty 失败 EV-0511（含 5 个 API 测试失败、无根因记录），strict gate 无法通过。修复证据卫生与根因说明后，本评审可转 pass。
