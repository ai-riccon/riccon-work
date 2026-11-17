---
review_id: REV-M02-001
module_id: M02
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M02_TASK-0024_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: fail
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
    - EV-0225
  tests:
    - EV-0228
  coverage:
    - EV-0229
  docs:
    - EV-0233
reviewed_evidence:
  - EV-0085
  - EV-0086
  - EV-0087
  - EV-0088
  - EV-0089
  - EV-0090
  - EV-0091
  - EV-0092
  - EV-0093
  - EV-0094
  - EV-0095
  - EV-0096
  - EV-0097
  - EV-0098
  - EV-0099
  - EV-0100
  - EV-0101
  - EV-0102
  - EV-0103
  - EV-0104
  - EV-0105
  - EV-0106
  - EV-0107
  - EV-0108
  - EV-0109
  - EV-0110
  - EV-0111
  - EV-0112
  - EV-0113
  - EV-0114
  - EV-0115
  - EV-0116
  - EV-0117
  - EV-0118
  - EV-0119
  - EV-0120
  - EV-0121
  - EV-0122
  - EV-0123
  - EV-0124
  - EV-0125
  - EV-0126
  - EV-0127
  - EV-0128
  - EV-0129
  - EV-0130
  - EV-0131
  - EV-0132
  - EV-0133
  - EV-0134
  - EV-0135
  - EV-0136
  - EV-0137
  - EV-0138
  - EV-0139
  - EV-0140
  - EV-0141
  - EV-0142
  - EV-0143
  - EV-0144
  - EV-0145
  - EV-0146
  - EV-0147
  - EV-0148
  - EV-0149
  - EV-0150
  - EV-0151
  - EV-0152
  - EV-0153
  - EV-0154
  - EV-0155
  - EV-0156
  - EV-0157
  - EV-0158
  - EV-0159
  - EV-0160
  - EV-0161
  - EV-0162
  - EV-0163
  - EV-0164
  - EV-0165
  - EV-0166
  - EV-0167
  - EV-0168
  - EV-0169
  - EV-0170
  - EV-0171
  - EV-0172
  - EV-0173
  - EV-0174
  - EV-0175
  - EV-0176
  - EV-0177
  - EV-0178
  - EV-0179
  - EV-0180
  - EV-0181
  - EV-0182
  - EV-0183
  - EV-0184
  - EV-0185
  - EV-0186
  - EV-0187
  - EV-0188
  - EV-0189
  - EV-0190
  - EV-0193
  - EV-0194
  - EV-0195
  - EV-0196
  - EV-0197
  - EV-0198
  - EV-0199
  - EV-0200
  - EV-0201
  - EV-0202
  - EV-0203
  - EV-0204
  - EV-0207
  - EV-0208
  - EV-0209
  - EV-0210
  - EV-0211
  - EV-0212
  - EV-0213
  - EV-0214
  - EV-0215
  - EV-0216
  - EV-0217
  - EV-0218
  - EV-0219
  - EV-0220
  - EV-0221
  - EV-0222
  - EV-0223
  - EV-0224
  - EV-0225
  - EV-0226
  - EV-0227
  - EV-0228
  - EV-0229
  - EV-0230
  - EV-0231
  - EV-0232
  - EV-0233
findings:
  - id: F-001
    severity: P1
    title: "J2 真实 UI 无法选择 MCP 工具：/api/v1/mcp-tools 契约端点未实现"
    status: open
    evidence: "EV-0232；.riccon/evidence/TASK-0024/real-stack-checks.json"
  - id: F-002
    severity: P1
    title: "真实链路首字被缓冲到模型流结束，实测 4380ms > 3 秒预算"
    status: open
    evidence: "EV-0232；.riccon/evidence/TASK-0024/real-stack-playwright.log；real-stack-db-observations.txt"
  - id: F-003
    severity: P1
    title: "J2 检索阶段只返回 hitCount，不返回来源条目/片段，M02-AC-07 不成立"
    status: open
    evidence: "EV-0232；.riccon/evidence/TASK-0024/real-stack-j2.json"
  - id: F-004
    severity: P1
    title: "J1 运行详情端点 /api/v1/runs/{runId} 未实现，模型调用记录不可见"
    status: open
    evidence: "EV-0232；.riccon/evidence/TASK-0024/real-stack-checks.json"
  - id: F-011
    severity: P2
    title: "OpenAPI 声明的 /models 与四个 admin 目录端点均返回 404"
    status: open
    evidence: "EV-0232；.riccon/evidence/TASK-0024/real-stack-checks.json"
  - id: F-005
    severity: P2
    title: "审批拒绝后 TOOL/APPROVAL 阶段仍为 RUNNING，时间线与任务终态不一致"
    status: open
    evidence: "EV-0232；real-stack-j2.json；real-stack-db-observations.txt"
  - id: F-006
    severity: P2
    title: "J1 前端 filesRef 同步竞态：解析成功后立即提问会省略 fileIds，导致无引用"
    status: open
    evidence: "EV-0232；e2e/real/tests/j1.spec.ts"
  - id: F-007
    severity: P2
    title: "合同 commands.perf 指向不存在的 e2e/perf/mvc-smoke.js，首字 p95 无 k6 证据"
    status: open
    evidence: "AGENTS.md commands.perf；仓库无 e2e/perf/"
  - id: F-008
    severity: P2
    title: "model_invocation.first_token_ms 记录为整段模型流耗时，掩盖首字缓冲回归"
    status: open
    evidence: "real-stack-db-observations.txt"
  - id: F-009
    severity: P3
    title: "J1 回答以原始 Markdown 文本展示（## 标题可见），未做 Markdown 渲染"
    status: open
    evidence: "EV-0232 截图与 Playwright error-context"
  - id: F-010
    severity: P3
    title: "真实 J2 页面 a11y 仅覆盖工具加载失败的错误态，正常创建/审批/产物路径未覆盖"
    status: open
    evidence: "EV-0232；e2e/real/tests/a11y.spec.ts"
created_at: 2026-11-18T07:45:00+08:00
---

# M02 独立评审：云工作空间与办公协同闭环

- **Verdict：`fail`**。真实栈证明 J1 主链路可运行（登录、上传、解析、引用、重开、跨租户与审计均通过），但 **J2 用户入口不可达**、**首字被缓冲到模型结束（4380ms）**、**J2 检索阶段没有来源列表**；三者均为 P1，M02 不能进入 gate。
- **范围**：M02-AC-01..16、合同命令、TASK-0008..0023 证据审计、真实栈 J1/J2、跨租户、审计 append-only、a11y、安全扫描。
- **独立性**：本评审由 `riccon/verifier` 在独立会话完成；未修改任何生产源码、迁移、合同、`contracts/` 或 `platform/`。新增代码仅限 `e2e/real/**`、`scripts/real-stack/**` 与 `.riccon/**`。
- **HEAD**：证据录制于 `6808833`（验证脚本与真实栈报告路径修正后）；任务创建时 HEAD 为 `257538a`，比派单中的 `5bfabac` 多一个 builder 证据落盘提交。

## 1. 验收标准逐条对账

| AC                              | 结果                       | 说明 / 证据                                                                                                                                                                                                                                                                     |
| ------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M02-AC-01 登录                  | **pass**                   | 真实栈浏览器用例：正确凭据进入 `tenant-alpha`、显示“Alpha 演示企业”；错误凭据统一提示且不出现“账号不存在”。EV-0232 的 5 个通过用例包含 `login.spec.ts` 两条。                                                                                                                   |
| M02-AC-02 文件上传/解析         | **partial**                | 真实栈 DOCX 上传、Worker 解析成功、4 个分块可引用；PDF/DOCX/XLSX/PNG/JPEG 的其余格式只有 Maven 集成/单元测试，未在真实栈 E2E 逐格式验证。                                                                                                                                       |
| M02-AC-03 首字 ≤3s 与停止       | **fail**                   | 真实栈实测 `REAL_FIRST_TOKEN_MS=4380`（另一次 3489ms）；所有 delta 在模型流结束后一次性到达。DB `first_token_ms` 3147–3345ms，与 `duration_ms` 几乎相等。停止按钮在短回答上已来不及调用（本验证中 stop 返回 409），见 F-002。                                                   |
| M02-AC-04 引用可定位            | **partial**                | 真实 J1 在文件已同步到发送请求时显示 4 条 `market.docx` 引用，点击可打开“引用详情（第 1 段）”；但 F-006 的 `filesRef` 竞态会让快速提问退化为“未检索到相关资料”。                                                                                                                |
| M02-AC-05 失败/重试             | **not verified（真实栈）** | 解析失败、模型失败与重试入口主要在 stub E2E 与 Maven 测试中覆盖；真实栈未注入模型/对象存储故障。                                                                                                                                                                                |
| M02-AC-06 J2 创建入口           | **fail**                   | 真实 `/research/tasks` 页面显示“授权工具加载失败”，数据工具下拉禁用，无法创建任务。根因是 `/api/v1/mcp-tools` 未实现（F-001）。                                                                                                                                                 |
| M02-AC-07 检索来源              | **fail**                   | Worker 实际写入 `details.sources`，但 API `RunStageDetails` 白名单丢弃；任务级 `sources` 只在产物存在后才有值。真实等待审批时 `hitCount=4`、`sources=0`。见 F-003。                                                                                                             |
| M02-AC-08 MCP 工具阶段          | **partial**                | API/Worker 真实闭环通过：审批预览含工具名 `market_data` 与参数摘要；批准后 MCP 调用约 530ms 成功，阶段详情含 `invocationId`/`resultSummary`。但浏览器入口被 AC-06 阻断。                                                                                                        |
| M02-AC-09 审批通过/拒绝         | **partial**                | 批准路径通过；拒绝路径任务 `CANCELLED`、无产物、审批记录含 `decidedBy/decidedAt/comment`。拒绝后 TOOL/APPROVAL 阶段仍 `RUNNING`，见 F-005。                                                                                                                                     |
| M02-AC-10 报告产物              | **partial**                | 真实 HTTP 闭环通过：报告 Markdown 含标题/摘要/分析/引用清单/数据来源，5 条引用（4 KNOWLEDGE + 1 MCP），下载可读取；UI 因 AC-06 不可达。                                                                                                                                         |
| M02-AC-11 MCP 超时/生成失败重试 | **not verified（真实栈）** | 现有证据是 stub E2E 的 MCP 超时重试用例与 Worker 集成测试；真实栈未注入超时/生成失败。                                                                                                                                                                                          |
| M02-AC-12 跨租户对象            | **pass**                   | 真实栈 B 租户读取 A 的会话、任务、产物、版本、下载 URL、知识库文档、文件均返回 404，响应不包含 A 内容；审批列表为空。EV-0232 的 `real-stack-checks.json`。                                                                                                                      |
| M02-AC-13 越权套件零 skip       | **partial**                | 全量 Maven 499 tests、0 skipped，前端 133 tests、0 skipped；真实栈覆盖了主要对象与应用层管理入口。未逐一覆盖所有 admin/未来 M03 管理接口。                                                                                                                                      |
| M02-AC-14 审计覆盖              | **pass**                   | 真实库中确认 `auth.login.succeeded`、`user.created`、`tenant.created`、`file.uploaded/deleted`、`session.created`、`message.sent`、`model.invocation.started/completed`、`mcp.tool.invoked`、`approval.decided`、`research.task.created`、`artifact.saved` 均有记录，租户非空。 |
| M02-AC-15 审计不可改删          | **pass**                   | 数据库触发器拒绝 `UPDATE`/`DELETE`（均 ERROR 1644 `audit_event is append-only`）；API 无审计查询/删除端点，普通员工无法访问。                                                                                                                                                   |
| M02-AC-16 状态、375px、键盘     | **partial**                | 真实栈 J1/J2 页面 axe 无 serious/critical，375px 无横向滚动；J2 页面是工具失败错误态。键盘上传/审批只在 stub E2E 覆盖，真实栈 J1 上传使用点击。                                                                                                                                 |

**结论**：M02 的 J1 后端与安全基线基本成立，但 J2 用户旅程和流式体验不成立；AC-03、AC-06、AC-07 明确失败，AC-08/09/10 因入口阻断只能算部分成立。

## 2. 合同命令执行（TASK-0024，HEAD=`6808833`）

| 合同项    | 命令                                                                                                                      | EV      | 退出码 | 观察                                                                                                                                      |
| --------- | ------------------------------------------------------------------------------------------------------------------------- | ------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| install   | `./mvnw -q -T1C -DskipTests dependency:go-offline && pnpm install --frozen-lockfile`                                      | EV-0224 | 0      | 依赖已就绪，lockfile 未变                                                                                                                 |
| build     | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                     | EV-0225 | 0      | API/Worker jar 与 web `dist` 生成                                                                                                         |
| lint      | `./mvnw -q spotless:check && pnpm -w lint`                                                                                | EV-0226 | 0      | Spotless、ESLint、Prettier 全部通过                                                                                                       |
| typecheck | `pnpm -w typecheck`                                                                                                       | EV-0227 | 0      | `tsc --noEmit` 通过                                                                                                                       |
| test      | `./mvnw -q -T1C verify && pnpm -w test`                                                                                   | EV-0228 | 0      | 后端 499 tests / 87 reports，0 failures、0 errors、0 skipped；前端 18 files / 133 tests                                                   |
| coverage  | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                       | EV-0229 | 0      | api line 90.14% / branch 70.82%；worker 88.04% / 70.95%；platform-common 98.55% / 98.15%；前端 lines 86.35% / branches 79.52%；均过 80/70 |
| security  | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`  | EV-0230 | 0      | 0 visible critical/high；2 条 MCP CVE 按 ADR-0011/DEC-0005 抑制                                                                           |
| a11y      | `pnpm -w a11y`                                                                                                            | EV-0231 | 0      | stub E2E 6 个 @a11y 用例通过                                                                                                              |
| test_e2e  | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e && node scripts/real-stack/run.mjs` | EV-0232 | **1**  | stub E2E 17 passed；真实栈 5 passed / 2 failed：首字 4380ms、J2 工具加载失败。J2 HTTP 闭环与安全检查通过                                  |
| docs      | `git diff --stat 9c64dce..HEAD -- docs && test -f ...`                                                                    | EV-0233 | 0      | M02 文档/ADR/威胁模型/契约存在                                                                                                            |

**说明**：`commands.perf` 未执行，因为合同指向的 `e2e/perf/mvc-smoke.js` 不存在（F-007）；这不是本项目新增的失败，而是 M02 性能 DoD 未被合同命令覆盖。

## 3. 真实栈 E2E 方法与环境

验证者新增、且只新增验证资产：

- `scripts/real-stack/run.mjs`：干净 compose → 启动真实 API/Worker/Web 生产构建 → seed → Playwright → J2 HTTP 闭环 → 安全检查。
- `scripts/real-stack/fake-model.mjs`：独立进程的 OpenAI 兼容 SSE 假模型，记录首字/结束时间；不 import 生产 mock。
- `scripts/real-stack/mcp-server.mjs`：独立 stdio MCP server，按 MCP 2024-11-05 实现 initialize/tools/list/tools/call。
- `scripts/real-stack/seed.mjs`：Admin API 创建 2 个租户/3 个账号；SQL 写入 model/MCP 目录与 AES-256-GCM 信封凭据；上传真实 DOCX 并登记知识库，等待 `INDEXED`。
- `e2e/real/**`：真实 Web 生产构建 + 同源反向代理的 Playwright 用例（登录、J1、J2 入口、a11y/375px）。

环境：Docker Compose（MySQL 8.4.13、OpenSearch 3.9.0、RustFS 1.0.6）、JDK 21、Node 26、API 18080、Worker 18081、Web 8092、假模型 18990。API/Worker/Web/模型/MCP 都是真实进程；J2 工具调用经 Worker 启动 `node scripts/real-stack/mcp-server.mjs` 子进程完成。

**真实栈结果**：7 个浏览器用例中 5 passed：

- 登录成功/失败、J1 上传/解析/引用/重开、J1 a11y/375px、J2 错误态 a11y/375px 通过；
- 首字预算失败：`REAL_FIRST_TOKEN_MS=4380`；
- J2 创建入口失败：页面显示“授权工具加载失败”，下拉 disabled。
- J2 HTTP 闭环通过：检索命中 4 条、审批 `market_data`、MCP 成功、4 个阶段 SUCCEEDED、产物 5 条引用（4 KNOWLEDGE + 1 MCP）、Markdown 可下载。
- 安全检查通过：7 类跨租户对象 404，审计动作齐全，append-only 触发器生效，日志无假 API key/主密钥，目录凭据无明文。

**真实栈限制**：

- 使用假模型与假 MCP，不验证真实供应商延迟/输出质量；这符合 M02 的 `RICCON_FAKE_MODEL` 口径。
- 模型进程日志与 Worker/API 日志保留在 EV-0232 的输出目录；JSON 观察见 `real-stack-summary.json`、`real-stack-j2.json`、`real-stack-checks.json`、`real-stack-db-observations.txt`。
- J2 浏览器入口被 F-001 阻断，故 J2 的审批/产物用户界面只能在 stub E2E 中验证；这是产品缺口，不是验证者用 stub 冒充。

## 4. 证据审计

- TASK-0008..0023 + TASK-0024 的活动证据共 **145** 条：输出文件全部存在，SHA-256 与字节数匹配，`manual=false`，`dirty=false`，commit 均为 HEAD 祖先。
- 非零退出共 **5** 条：
  - TASK-0011 `EV-0129`、`EV-0130`：预期失败，证明 `audit_event` UPDATE/DELETE 被触发器拒绝；
  - TASK-0011 `EV-0133`、`EV-0134`：预期失败，证明未设置 `log_bin_trust_function_creators` 时触发器创建报 1419；
  - TASK-0024 `EV-0232`：真实栈 E2E 失败，正是本评审 F-002/F-001 的证据。
- TASK-0021 的 `EV-0191/0192/0205/0206` 已归档到 `.riccon/archive/evidence-superseded/TASK-0021/`，不在任务引用中；活动证据是 detached worktree 录制的 `EV-0207/0208`，commit `436f851` 为 HEAD 祖先，dirty=false。
- TASK-0023 的 builder E2E 是独立 stub API，不是应用内 mock；但它不能替代真实栈证据，真实栈结果以 EV-0232 为准。

## 5. 安全、租户与审计

- **跨租户**：B 租户读取 A 的会话/任务/产物/版本/下载 URL/知识库文档/文件全部 404；B 在 A 工作空间创建任务 404；B 使用 A 的 MCP 工具创建任务返回 400 `mcpToolId is not authorized for this tenant`（未泄露对象内容；不是 AC-12 的对象范围）。
- **审计**：真实库动作覆盖 13 类关键动作；`audit_event` UPDATE/DELETE 均被触发器以 1644 拒绝；审计行 tenant_id 非空。
- **密钥**：API/Worker/模型日志中未出现假 API key 或主密钥；`provider_credential.ciphertext` 不含明文。
- **B-06**：Trivy 0 可见项；仅两条 MCP CVE 按 ADR-0011/DEC-0005 抑制。补偿控制核对：MCP server 为 STDIO + `riccon-builtin` 白名单、工具 READ、`requires_approval=TRUE`、全量 HITL、Worker 无对外业务端口。
- **会话/CSRF**：真实 Web 生产构建经同源代理登录成功；错误凭据统一提示；未登录 API 401。

## 6. Findings

| ID    | 级别 | 状态 | 发现                                                | 位置 / 复现                                                                                                                                                                                      | 观察 vs 期望                                                                                                                             |
| ----- | ---- | ---- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 | P1   | open | J2 真实 UI 无法选择工具；`/api/v1/mcp-tools` 未实现 | `contracts/openapi.yaml:1219`；`apps/web/src/services/api/client.ts:502`；`apps/web/src/pages/research/use-research-workspace.ts:85`；`scripts/real-stack/run.mjs` → `e2e/real/tests/j2.spec.ts` | 实测 GET `/api/v1/mcp-tools` 404；页面“授权工具加载失败”+下拉 disabled；期望返回租户授权工具并允许创建任务                               |
| F-002 | P1   | open | 首字被缓冲到模型流结束                              | `apps/worker/src/main/java/com/riccon/worker/chat/service/ChatStreamService.java:294`；EV-0232；`real-stack-playwright.log`                                                                      | 实测首字 4380ms/3489ms；所有 delta 在模型结束后到达；DB `first_token_ms` 3147–3345ms，与 `duration_ms` 几乎相同；期望 ≤3000ms 且逐字到达 |
| F-003 | P1   | open | J2 检索阶段无来源条目/片段                          | `apps/api/src/main/java/com/riccon/api/research/dto/RunStageDetails.java:43-68`（丢弃未知键）；`ResearchTaskService.java:295`（sources 只来自产物）；`stage-timeline.tsx:194`                    | 等待审批时 `hitCount=4`、`details.sources=0`、任务 `sources=0`；期望展示 ≥1 条标题/片段/引用                                             |
| F-004 | P1   | open | J1 运行详情端点缺失，模型调用记录不可见             | `contracts/openapi.yaml:698`；`apps/api` 无 `/runs/{runId}` 控制器；真实栈 GET 404                                                                                                               | PRODUCT J1 要求“会话运行详情中可见模型调用记录（模型标识、开始/结束时间、结果状态）”；实际只有 message.modelId 快照，无运行详情 API/UI   |
| F-011 | P2   | open | `/models` 与四个 admin 目录端点未实现               | `contracts/openapi.yaml:1199,1518,1578`；真实栈 GET 全部 404                                                                                                                                     | 契约漂移；不影响 M02 员工旅程，但 M04/M07 前必须补齐或删除声明                                                                           |
| F-005 | P2   | open | 拒绝审批后阶段仍 RUNNING                            | `apps/api/.../ApprovalService.java:254` 先 markCancelled；`apps/worker/.../OutboxPoller.java:116-120` 对终态 run 直接 markDone，`AgentRunCancelHandler` 不执行                                   | 任务 CANCELLED，但 TOOL/APPROVAL 阶段 `RUNNING`、无决定详情；期望阶段 SKIPPED 且时间线含审批人/时间/决定                                 |
| F-006 | P2   | open | J1 `filesRef` 同步竞态导致 fileIds 丢失             | `apps/web/src/pages/chat/use-j1-workspace.ts:117`（effect 同步）与 `:423`（send 读取 ref）                                                                                                       | 解析成功后 1s 内发送：请求体无 `fileIds`，页面仍显示“1 个已解析文件”，回答“未检索到相关资料”；等待 2–5s 后请求含 fileIds 且引用正常      |
| F-007 | P2   | open | 性能合同命令不可执行                                | `AGENTS.md` `commands.perf`；仓库无 `e2e/perf/mvc-smoke.js`                                                                                                                                      | 期望 k6 首字 p95 基准；实际文件不存在，M02 性能 DoD 只能由本次自建测量部分替代                                                           |
| F-008 | P2   | open | `first_token_ms` 记录整段流耗时                     | `real-stack-db-observations.txt`；`ChatStreamService.java:294`                                                                                                                                   | 假模型首字实际约 60ms，但 DB 记录 3147–3345ms；该指标无法区分模型慢与 Worker 缓冲                                                        |
| F-009 | P3   | open | J1 回答以原始 Markdown 文本展示                     | EV-0232 截图/error-context：气泡中可见 `## 市场规模与增长`                                                                                                                                       | 期望 Markdown 渲染或明确的纯文本策略；非验收阻断                                                                                         |
| F-010 | P3   | open | 真实 J2 a11y 只覆盖错误态                           | `e2e/real/tests/a11y.spec.ts`；F-001 阻断正常页                                                                                                                                                  | 期望正常创建/审批/产物三态 axe；当前只能覆盖工具失败态                                                                                   |

P0：**无**。P1：F-001/F-002/F-003/F-004 均为 open，按 Riccon 规则不能通过 gate。

## 7. 残余风险与 M03 前置

1. **J2 入口必须先补契约端点**：实现 `/api/v1/mcp-tools`（至少按租户返回已授权、ACTIVE、READ、requiresApproval 的工具），并补 `/api/v1/runs/{runId}` 让 J1 模型调用记录可见；否则 M03 的权限/审计纵深没有可信的用户入口。
2. **流式必须端到端验收**：修复 Worker/AgentScope 聚合导致的首字缓冲；用真实进程测量首字而不是只断言事件顺序；`first_token_ms` 应记录模型首个可见增量，而非流结束。
3. **J2 检索时间线**：把 Worker 已写入的 `details.sources` 暴露到 API（或明确契约字段），并在审批前渲染标题/片段/引用；否则 AC-07 无法验收。
4. **拒绝/取消时间线**：取消 handler 不应因 API 先置终态而被跳过；应显式把未完成阶段置 SKIPPED 并写入审批决定。
5. **文件发送竞态**：`send` 应从当前 `files` 状态派生附件，而不是依赖 effect 同步的 ref，或在上传/解析完成时同步更新 ref。
6. **性能合同**：补 `e2e/perf/mvc-smoke.js` 或修改合同命令；当前 F-007 使 M02 性能 DoD 无法由合同命令复现。
7. **范围**：AC-02 的 5 种格式、AC-05/AC-11 的失败路径、AC-16 的键盘路径仍需真实栈/集成证据；本次只验证了 DOCX 与主要成功/拒绝路径。
8. **墙钟**：验证期间观察到 `.riccon/modules/M02.json` 的 `wall_clock_limit_hours` 已由 8 调整为 16（与 DEC-0007 默认 A 一致），当前 elapsed 约 12.5h 未超 16h；DEC-0007 仍为 open/未回答，Lead 应在 gate 前确认决策状态。

## 8. 未验证 / 不声称

- 未执行 `commands.perf`（文件不存在）、`commands.run`、`commands.deploy_preview`、`commands.deploy_prod`、`riccon release check`。
- 未验证真实 OpenAI/客户网关、真实 MCP 供应商、真实 OIDC（M03）、真实 OCR/Excel（M05）。
- 未验证 Firefox/WebKit、移动端真机、生产 TLS/反向代理、K8s/Helm。
- 未验证 J2 正常 UI 的 axe（被 F-001 阻断）；未验证真实栈的 MCP 超时/生成失败重试。

## 9. 证据链接

- 合同命令：EV-0224 install、EV-0225 build、EV-0226 lint、EV-0227 typecheck、EV-0228 test、EV-0229 coverage、EV-0230 security、EV-0231 a11y、EV-0232 test_e2e（失败）、EV-0233 docs。
- 真实栈：`.riccon/evidence/TASK-0024/real-stack-summary.json`、`real-stack-j2.json`、`real-stack-checks.json`、`real-stack-db-observations.txt`、`real-stack-playwright.log`、`real-stack-*.log`。
- 截图/trace：`test-results/*-chromium/`（失败用例）；真实栈 Playwright 行报告与失败摘要见 `.riccon/evidence/TASK-0024/real-stack-playwright.log`。
- 证据审计：`riccon evidence list --task TASK-0024 --json`；EV 哈希/字节数逐条核对。
