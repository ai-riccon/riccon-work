---
review_id: REV-M02-002
module_id: M02
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M02_TASK-0029_20261118
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
    - EV-0259
  tests:
    - EV-0262
  coverage:
    - EV-0263
  docs:
    - EV-0269
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
  - EV-0150
  - EV-0154
  - EV-0155
  - EV-0156
  - EV-0157
  - EV-0148
  - EV-0149
  - EV-0151
  - EV-0152
  - EV-0153
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
  - EV-0158
  - EV-0159
  - EV-0160
  - EV-0161
  - EV-0162
  - EV-0173
  - EV-0174
  - EV-0175
  - EV-0176
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
  - EV-0207
  - EV-0208
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
  - EV-0234
  - EV-0235
  - EV-0236
  - EV-0237
  - EV-0238
  - EV-0239
  - EV-0240
  - EV-0241
  - EV-0242
  - EV-0243
  - EV-0254
  - EV-0255
  - EV-0256
  - EV-0257
  - EV-0244
  - EV-0245
  - EV-0246
  - EV-0247
  - EV-0248
  - EV-0249
  - EV-0250
  - EV-0251
  - EV-0252
  - EV-0253
  - EV-0258
  - EV-0259
  - EV-0260
  - EV-0261
  - EV-0262
  - EV-0263
  - EV-0264
  - EV-0265
  - EV-0266
  - EV-0267
  - EV-0268
  - EV-0269
  - EV-0270
findings:
  - id: F-001
    severity: P1
    title: "J2 真实 UI 无法选择 MCP 工具：/api/v1/mcp-tools 契约端点缺失"
    status: fixed
    evidence: "EV-0259/EV-0262 契约与集成测试；EV-0268 真实 UI 工具加载与创建；EV-0268 real-stack-checks.json mcpTools.alphaTotal=1、betaTotal=0"
  - id: F-002
    severity: P1
    title: "真实链路首字被缓冲到模型流结束"
    status: fixed
    evidence: "EV-0268：REAL_FIRST_TOKEN_MS=326；增长采样 [10,30,39,60,70,80,100,110,130]；J1 运行详情首字 73ms"
  - id: F-003
    severity: P1
    title: "J2 检索阶段只返回 hitCount，不返回来源条目/片段"
    status: fixed
    evidence: "EV-0268：审批前 stage details.sources=4、任务 sources=4，片段非空；UI 显示 [1]-[4] 来源与片段"
  - id: F-004
    severity: P1
    title: "J1 运行详情端点 /api/v1/runs/{runId} 未实现"
    status: fixed
    evidence: "EV-0268：UI 运行详情抽屉显示 fake-chat/首字 73ms/耗时 3.2s/成功；API runs 200，跨租户 404/404"
  - id: F-005
    severity: P2
    title: "审批拒绝后 TOOL/APPROVAL 阶段仍为 RUNNING"
    status: fixed
    evidence: "EV-0268：HTTP 拒绝路径 TOOL:SKIPPED、APPROVAL:SKIPPED，details 含 REJECT/审批人/时间；数据库终态一致"
  - id: F-006
    severity: P2
    title: "J1 filesRef 同步竞态：解析成功后立即提问会省略 fileIds"
    status: fixed
    evidence: "EV-0268：REAL_J1_FAST_REQUEST_BODY 含 fileIds=[...]；快速发送产生 4 条 market.docx 引用"
  - id: F-007
    severity: P2
    title: "合同 commands.perf 指向不存在的 e2e/perf/mvc-smoke.js"
    status: accepted
    evidence: "AGENTS.md commands.perf；仓库仍无 e2e/perf/；作为 M03/M09 前置，不计入 M02 module DoD"
  - id: F-008
    severity: P2
    title: "model_invocation.first_token_ms 记录整段模型流耗时"
    status: fixed
    evidence: "EV-0268：DB first_token_ms=72ms、duration_ms=3162ms；指标已能区分首字与总耗时"
  - id: F-009
    severity: P3
    title: "J1 回答以原始 Markdown 文本展示"
    status: open
    evidence: "EV-0268 真实栈页面快照可见 '## 市场规模与增长'；不阻断 M02 验收"
  - id: F-010
    severity: P3
    title: "真实 J2 a11y 仅覆盖工具加载失败错误态"
    status: fixed
    evidence: "EV-0268：J2 创建→审批→产物正常态 axe 无 serious/critical，375px 无横向滚动"
  - id: F-011
    severity: P2
    title: "OpenAPI 声明的 /models 与四个 admin 目录端点返回 404"
    status: accepted
    evidence: "EV-0268 real-stack-checks.json gaps：/models、admin/model-providers、admin/models、admin/mcp-servers、admin/mcp-tools；M04/M07 前补齐或删除声明"
  - id: F-012
    severity: P1
    title: "J2 产物正文在真实浏览器不可读：对象存储预签名 URL 缺 CORS，下载按钮 disabled"
    status: open
    evidence: "EV-0268：J2 UI 用例在“下载 Markdown 应可用”处失败；浏览器 console 报 CORS，预签名 GET 127.0.0.1:19000 为 net::ERR_FAILED；截图与 trace 见 real-stack-j2-artifact-body-*"
  - id: F-013
    severity: P2
    title: "审批拒绝后前端停止轮询，时间线在刷新前仍显示 TOOL/APPROVAL 进行中"
    status: open
    evidence: "EV-0268：REAL_J2_REJECT_STALE_RUNNING_STAGES=2；刷新后为 已跳过；后端数据库阶段已是 SKIPPED"
  - id: F-014
    severity: P3
    title: "J2 首条检索片段来自文档标题段，信息量低"
    status: open
    evidence: "EV-0268：REAL_J2_SNIPPET=2026 年中国企业协同办公市场研究（第 1 段）；第 2-4 条为正文数据，来源/片段均非空"
created_at: 2026-11-18T11:52:00+08:00
---

# M02 独立评审（复验）：云工作空间与办公协同闭环

- **Verdict：`fail`**。REV-M02-001 的四个 P1（mcp-tools、首字缓冲、sources 片段、runs 详情）以及 filesRef 竞态均已在真实栈复验中修复；但复验新发现一个 **P1**：J2 产物正文在真实浏览器中因对象存储预签名 URL 缺少 CORS 而无法读取，下载按钮保持 disabled，M02-AC-10 不成立。
- **范围**：M02-AC-01..16、合同命令、TASK-0008..0028 活动证据审计、真实栈 J1/J2、跨租户、审计 append-only、a11y、安全扫描。
- **独立性**：本评审由 `riccon/verifier` 在独立会话完成；未修改任何生产源码、迁移、构建配置、合同或 `contracts/`。新增/修改仅限 `e2e/real/**`、`scripts/real-stack/**` 与 `.riccon/**`。
- **HEAD**：`c476264`（验证资产与证据落盘提交之后）；合同命令与真实栈证据录制于该提交及其直接祖先，全部 EV 的 commit 均为 HEAD 祖先。
- **上一轮评审**：`REV-M02-001`（verdict=fail）已原样归档到 `.riccon/archive/reviews/M02-review-001-fail.md`，未删除。

## 1. 结论先行

| 项目                  | 结果                                                                                                                                                                   |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 首字预算（M02-AC-03） | **pass**：真实浏览器实测 **326ms**（前两次 321/325ms），正文 250ms 采样序列 `[10,30,39,60,70,80,100,110,130]` 严格增长；DB `first_token_ms=72ms`，`duration_ms=3162ms` |
| J1 主链路             | **pass**：登录、上传、解析、提问、4 条引用、刷新重开；快速发送请求体含 `fileIds`                                                                                       |
| J1 运行详情           | **pass**：真实 UI 抽屉显示 `fake-chat`、首字 73ms、耗时 3.2s、状态成功；API `/runs/{runId}` 200，跨租户 404                                                            |
| J2 创建与检索         | **pass**：真实 UI 工具列表可加载并创建任务；审批前展示 4 条来源、标题与片段；阶段 `details.sources=4`                                                                  |
| J2 审批               | **pass**：批准后 4 阶段 SUCCEEDED、5 条引用（4 KNOWLEDGE + 1 MCP）；拒绝后任务终止、无产物、审批记录含决定/审批人/时间；后端阶段 SKIPPED                               |
| J2 产物正文           | **fail（P1/F-012）**：浏览器读取预签名 URL 被 CORS 拒绝，正文区域显示“报告正文加载失败”，下载按钮 disabled；报告标题、摘要、引用清单与数据来源仍可见                   |
| 跨租户                | **pass**：7 类对象 + J1/J2 运行详情均 404；beta 工具目录 total=0                                                                                                       |
| 审计 append-only      | **pass**：16 类动作有记录；UPDATE/DELETE 均 ERROR 1644 `audit_event is append-only`                                                                                    |
| a11y / 375px          | **pass（真实栈已覆盖 J2 正常态）**：J1 页面、J2 列表与 J2 详情 axe 无 serious/critical，375px 无横向滚动                                                               |
| 合同命令              | build/lint/typecheck/test/coverage/security/a11y/docs 通过；`test_e2e` 因 F-012 退出 1                                                                                 |

## 2. 验收标准逐条对账

| AC                                 | 结果                       | 说明 / 证据                                                                                                                                 |
| ---------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| M02-AC-01 登录                     | **pass**                   | 真实栈浏览器：正确凭据进入 `tenant-alpha`、显示租户名；错误凭据只显示统一提示。EV-0268。                                                    |
| M02-AC-02 上传/解析                | **partial**                | 真实栈 DOCX 上传/解析/4 分块引用通过；PDF/XLSX/PNG/JPEG 仅 Maven 集成测试覆盖，未逐格式跑真实浏览器。                                       |
| M02-AC-03 首字 ≤3s、逐步增长、停止 | **pass（停止为 stub）**    | 326ms、增长采样严格递增；`first_token_ms` 与总耗时已分离。停止按钮由 stub E2E 覆盖，真实栈未重复测量。                                      |
| M02-AC-04 引用可定位               | **pass**                   | 真实 J1 显示 4 条 `market.docx` 引用，抽屉显示“第 1 段”；快速发送仍带 `fileIds` 并产生引用。EV-0268。                                       |
| M02-AC-05 失败/重试                | **not verified（真实栈）** | 真实栈未注入模型/解析故障；stub E2E 与 Maven 集成测试覆盖。                                                                                 |
| M02-AC-06 J2 创建入口              | **pass**                   | 真实 UI 加载 `market_data · 只读 · 需审批`，创建后进入任务详情；`/api/v1/mcp-tools` 200。EV-0268。                                          |
| M02-AC-07 检索来源                 | **pass**                   | 审批前 UI/HTTP 均见 4 条来源、标题、定位与片段；`details.sources=4`、任务级 `sources=4`；无结果提示在 stub 覆盖。                           |
| M02-AC-08 MCP 工具阶段             | **pass**                   | 审批预览含工具名与脱敏参数；批准后 MCP 调用成功，阶段详情含 `invocationId`/`resultSummary`；真实 UI 显示工具与数据来源。                    |
| M02-AC-09 审批通过/拒绝            | **pass（有 P2 显示缺陷）** | 批准/拒绝均通过；审批卡片显示审批人 `Alpha 员工`、时间与决定。拒绝后前端刷新前阶段仍显示“进行中”，见 F-013（P2）。                          |
| M02-AC-10 报告产物                 | **fail**                   | 产物元数据、摘要、引用清单与数据来源可见，但正文在浏览器不可读、下载禁用；根因 F-012（P1）。                                                |
| M02-AC-11 MCP 超时/生成失败重试    | **not verified（真实栈）** | stub E2E 与 Worker 集成测试覆盖；真实栈未注入超时/生成失败。                                                                                |
| M02-AC-12 跨租户对象               | **pass**                   | 会话、任务、产物、版本、下载 URL、知识库文档、文件 7 类 404；J1/J2 运行详情 404/404；响应不含 A 内容。EV-0268。                             |
| M02-AC-13 越权套件零 skip          | **partial**                | Maven 524 tests、0 skipped；前端 147 tests、0 skipped；真实栈覆盖主要对象、工具、运行与应用层管理入口，未覆盖未来 M03/M04 全部 admin 接口。 |
| M02-AC-14 审计覆盖                 | **pass**                   | 真实库 16 类动作：登录、文件上传/删除、会话、消息、模型调用开始/完成、MCP、审批、任务、产物、权限变更等；tenant_id 非空。EV-0268。          |
| M02-AC-15 审计不可改删             | **pass**                   | `audit_event` UPDATE/DELETE 均被触发器以 1644 拒绝；API 无删除/修改端点。EV-0268。                                                          |
| M02-AC-16 状态、375px、键盘        | **partial**                | 真实 J1/J2 列表/J2 详情 axe 无 serious/critical、375px 无横向滚动；键盘上传/审批仅 stub E2E 覆盖。                                          |

**模块验收**：J1 E2E 基本成立；J2 E2E 因 AC-10 失败；跨租户与审计成立；合同命令除 `test_e2e` 外通过。`dod.acceptance=false`、`dod.e2e=false`。

## 3. 合同命令执行（TASK-0029，HEAD=`c476264`）

| 合同项         | 命令                                                                                                                                                   | EV      | 退出码 | 观察                                                                                                                             |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- | ------ | -------------------------------------------------------------------------------------------------------------------------------- |
| install        | `./mvnw -q -T1C -DskipTests dependency:go-offline && pnpm install --frozen-lockfile`                                                                   | EV-0258 | 0      | lockfile 未变                                                                                                                    |
| build          | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                                                  | EV-0259 | 0      | API/Worker exec jar 与 web dist 生成                                                                                             |
| lint           | `./mvnw -q spotless:check && pnpm -w lint`                                                                                                             | EV-0260 | 0      | Spotless、ESLint、Prettier 通过                                                                                                  |
| typecheck      | `pnpm -w typecheck`                                                                                                                                    | EV-0261 | 0      | `tsc --noEmit` 通过                                                                                                              |
| test           | `./mvnw -q -T1C verify && pnpm -w test`                                                                                                                | EV-0262 | 0      | 后端 93 reports / **524 tests**，0 failure/error/skip；前端 **147 tests**，0 skip                                                |
| coverage       | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                                                    | EV-0263 | 0      | api line 90.25%/branch 71.19%；worker 87.89%/70.77%；platform-common 98.55%/98.15%；web lines 86.85%/branches 79.67%；均过 80/70 |
| security       | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`                               | EV-0264 | 0      | 0 可见 critical/high；MCP CVE 按 ADR-0011/DEC-0005 抑制                                                                          |
| a11y           | `pnpm -w a11y`                                                                                                                                         | EV-0265 | 0      | stub E2E 7 个 @a11y 用例通过（含 J1 运行详情与 J2 完整闭环）                                                                     |
| test_e2e       | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e && REAL_EVIDENCE_TASK=TASK-0029 node scripts/real-stack/run.mjs` | EV-0268 | **1**  | stub E2E 19 passed；真实栈 9 passed / 1 failed（F-012）；J2 HTTP 闭环与安全检查通过                                              |
| docs           | docs/contracts 存在性与 diff                                                                                                                           | EV-0269 | 0      | `docs/PRODUCT.md`、`ARCHITECTURE.md`、`CODING-STANDARDS.md`、`THREAT-MODEL.md`、`RUNBOOK.md`、`contracts/openapi.yaml` 存在      |
| evidence audit | `node scripts/real-stack/audit-evidence.mjs`                                                                                                           | EV-0270 | 0      | 169 条活动 EV 全部存在、哈希/字节数匹配、dirty=false、commit 为祖先；无重复/孤立                                                 |
| perf           | `k6 run e2e/perf/mvc-smoke.js`                                                                                                                         | —       | —      | **未执行**：文件仍不存在，见 F-007；作为 M03/M09 前置，不计入 M02 module DoD                                                     |

说明：EV-0266/EV-0267 是本次复验中真实栈用例自身定位器缺陷导致的失败尝试（已修正用例后重跑），保留用于追溯；产品失败以 EV-0268 为准。EV-0268 的 `test_e2e` 非零仅由 F-012 造成。

## 4. 真实栈复验方法与结果

验证资产（仅为验证者资产，不改生产）：

- `scripts/real-stack/run.mjs`：增加 `REAL_EVIDENCE_TASK`，把证据写入 TASK-0029，避免覆盖上一轮 TASK-0024 日志。
- `scripts/real-stack/j2-journey.mjs`：强断言检索 `sources` 数量/片段/定位、审批阶段 `approvalDecision/approvalDecidedBy/approvalDecidedAt`、J2 运行详情、拒绝阶段无 RUNNING。
- `scripts/real-stack/checks.mjs`：`/api/v1/mcp-tools` 必须 200 且租户隔离、J1/J2 runs 必须 200 且含模型调用、J1/J2 运行跨租户 404、DB `first_token_ms<3000`；契约缺口只剩 `/models` 与 4 个 admin 目录。
- `e2e/real/**`：首字 + 逐段增长、快速发送 `fileIds`、J1 运行详情、J2 真实 UI 创建/审批/拒绝/产物、J2 详情 a11y/375px。

环境：Docker Compose（MySQL 8.4.13、OpenSearch 3.9.0、RustFS 1.0.6）、JDK 21、Node 26、Web 生产构建同源代理 8092、API 18080、Worker 18081、假模型 18990。J2 工具调用经 Worker 启动 `node scripts/real-stack/mcp-server.mjs` 子进程完成。

真实栈结果（EV-0268）：

- **首字**：`REAL_FIRST_TOKEN_MS=326`，wall 385ms；`REAL_GROWTH_SAMPLES=[10,30,39,60,70,80,100,110,130]`。
- **J1 运行详情**：`REAL_RUN_DETAIL_FIRST_TOKEN_MS=73`；抽屉含模型 `fake-chat`、首字 73ms、耗时 3.2s、状态成功。
- **快速发送**：`REAL_J1_FAST_REQUEST_BODY` 含 `"fileIds":["..."]`，页面显示 `market.docx` 4 条引用。
- **J2 检索**：`hitCount=4`、`details.sources=4`、任务 `sources=4`、首片段长度 18；UI 显示 `[1]`–`[4]` 与片段（第 2–4 条为正文数据）。
- **J2 审批/产物**：批准后 4 阶段 SUCCEEDED、`citationCount=5`（4 KNOWLEDGE + 1 MCP）、J2 运行详情 2 条模型调用；拒绝后 `TOOL:SKIPPED`、`APPROVAL:SKIPPED`、无产物。
- **J2 产物正文**：`下载 Markdown` 在 30s 内始终 disabled；截图显示“报告正文加载失败 / 无法连接服务器”。浏览器 console：`Access to fetch at 'http://127.0.0.1:19000/...' from origin 'http://127.0.0.1:8092' has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header`；网络记录 `net::ERR_FAILED`。
- **拒绝时间线**：`REAL_J2_REJECT_STALE_RUNNING_STAGES=2`；刷新后 `进行中` 0、`已跳过` 出现。
- **跨租户**：7 类对象 404；J1/J2 运行 404/404；beta 工具目录 total=0。
- **审计**：16 类动作；UPDATE/DELETE ERROR 1644。
- **密钥**：API/Worker/模型日志未出现假 API key/主密钥；`provider_credential.ciphertext` 无明文。

真实栈 runner 退出码为 1，唯一失败是用例 `j2.spec.ts:63` 的产物正文断言；其余 9 个浏览器用例通过，J2 HTTP 闭环与安全检查通过。

## 5. Findings

| ID    | 级别 | 状态     | 发现                                                                                | 位置 / 复现                                                                                                                                                                   | 观察 vs 期望                                                   |
| ----- | ---- | -------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| F-001 | P1   | fixed    | J2 真实 UI 无法选择 MCP 工具                                                        | 旧：`/api/v1/mcp-tools` 404；现 EV-0268 真实 UI 加载 `market_data · 只读 · 需审批` 并创建任务；checks `alphaTotal=1`、`betaTotal=0`                                           | 已从 404 修复为按租户返回授权工具                              |
| F-002 | P1   | fixed    | 真实链路首字被缓冲到流结束                                                          | 旧：4380ms；现 EV-0268 首字 326ms、正文严格增长；DB 72ms vs duration 3162ms                                                                                                   | 已满足 ≤3s 且逐段到达                                          |
| F-003 | P1   | fixed    | J2 检索无来源/片段                                                                  | 旧：`details.sources=0`；现 4 条来源、片段与定位，审批前 UI 可见                                                                                                              | 已满足 AC-07                                                   |
| F-004 | P1   | fixed    | J1 运行详情端点缺失                                                                 | 旧：404；现 UI 抽屉与 API `/runs/{runId}` 均可用，跨租户 404                                                                                                                  | 已满足 PRODUCT J1 模型调用记录                                 |
| F-005 | P2   | fixed    | 拒绝审批后 TOOL/APPROVAL 仍 RUNNING                                                 | `AgentRunCancelHandler.handlesTerminalRun` + `skipRunningStages`；EV-0268 数据库与 HTTP 均为 SKIPPED                                                                          | 后端已修复；前端陈旧显示另立 F-013                             |
| F-006 | P2   | fixed    | J1 filesRef 竞态                                                                    | `use-j1-workspace.ts` 同步 `commitFiles`；EV-0268 解析成功即发送请求体含 `fileIds`                                                                                            | 已修复                                                         |
| F-007 | P2   | accepted | `commands.perf` 指向不存在脚本；`commands.deploy_preview` 指向不存在的 compose 文件 | `AGENTS.md commands.perf`；仓库无 `e2e/perf/`；`deploy/compose/docker-compose.preview.yml` 不存在                                                                             | M03/M09 前置；M02 无 k6 p95 证据，本次只做重复单次测量         |
| F-008 | P2   | fixed    | `first_token_ms` 记录整段流耗时                                                     | EV-0268 DB 72ms vs duration 3162ms                                                                                                                                            | 指标已能区分首字与总耗时                                       |
| F-009 | P3   | open     | J1 回答以原始 Markdown 展示                                                         | EV-0268 页面快照可见 `## 市场规模与增长`                                                                                                                                      | 期望 Markdown 渲染或明确纯文本策略；不阻断                     |
| F-010 | P3   | fixed    | J2 a11y 只覆盖错误态                                                                | EV-0268 J2 正常详情 axe 0 serious/critical、375px 无横向滚动；EV-0265 7 个 @a11y 用例                                                                                         | 已覆盖正常创建/审批/产物路径                                   |
| F-011 | P2   | accepted | `/models` 与 4 个 admin 目录端点 404                                                | EV-0268 `real-stack-checks.json.gaps`                                                                                                                                         | 契约漂移；M04/M07 前补齐或删除声明                             |
| F-012 | P1   | open     | J2 产物正文浏览器不可读、下载 disabled                                              | `apps/web/src/services/api/client.ts:619-636` 直接 fetch 预签名 URL；`deploy/compose/docker-compose.test.yml` object-storage 无 CORS；`docs/RUNBOOK.md` 无 CORS 配置；EV-0268 | 浏览器 console CORS、`net::ERR_FAILED`；期望正文可读且下载可用 |
| F-013 | P2   | open     | 拒绝后前端停止轮询，刷新前阶段仍“进行中”                                            | `apps/web/src/pages/research/use-research-task.ts:221-238` 只在 PENDING/RUNNING/WAITING_APPROVAL 轮询；`detail.tsx` 无终态后刷新                                              | 实测 2 个“进行中”；刷新后 0；后端已是 SKIPPED                  |
| F-014 | P3   | open     | 首条检索片段是文档标题段                                                            | `apps/worker/.../AgentRunRequestedHandler.java:510-526` 对 chunk 1 生成 snippet                                                                                               | 片段非空且非来源 ID/标题字段，但信息量低；第 2–4 条为正文数据  |

P0：**无**。P1：F-012 **open**，因此 verdict=fail。P2/P3 中 F-007/F-011 已书面接受为后续模块前置；F-009/F-013/F-014 不阻断本轮结论但需记录。

### F-012 复现

```bash
REAL_EVIDENCE_TASK=TASK-0029 node scripts/real-stack/run.mjs
# 或浏览器：登录 → 市场调研报告 → 创建任务 → 批准 → 打开产物
# 观察：报告正文区域“报告正文加载失败”，下载 Markdown disabled
# console：blocked by CORS policy: No 'Access-Control-Allow-Origin'
# 预签名 GET：http://127.0.0.1:19000/riccon-test/... -> net::ERR_FAILED
```

修复方向：在对象存储（测试 compose 与 M09 Helm/客户 S3/OSS）为 Web 源配置 GET CORS；或让 API 以同源端点流式代理产物正文，避免浏览器直连对象存储。两种方案都要补真实栈 E2E。

## 6. 证据审计

- TASK-0008..0028 共 **169** 条活动 EV：输出文件全部存在，SHA-256/字节数匹配，`manual=false`，`dirty=false`，commit 均为 HEAD 祖先；无重复 EV ID，无 M02 范围内未被任务引用的孤立 EV。
- 非零退出共 **5** 条，均为显式预期失败：
  - TASK-0011 EV-0129/EV-0130：证明 `audit_event` UPDATE/DELETE 被触发器拒绝；
  - TASK-0011 EV-0133/EV-0134：证明未设置 `log_bin_trust_function_creators` 时触发器创建报 1419；
  - TASK-0024 EV-0232：REV-M02-001 的真实栈失败证据，保留用于追溯，已被 EV-0268 复验替代。
- TASK-0029 新增 EV-0258..EV-0270；EV-0266/EV-0267 是验证用例定位器缺陷导致的失败尝试，EV-0268 为最终真实栈结果。全部列入 `reviewed_evidence`。
- 审计脚本：`scripts/real-stack/audit-evidence.mjs`（EV-0270）。

## 7. 安全、租户与审计

- **跨租户**：B 租户读取 A 的会话/任务/产物/版本/下载 URL/知识库文档/文件全部 404；J1/J2 运行详情 404/404；B 工具目录 total=0；B 在 A 工作空间创建任务 404；B 用 A 工具返回 400，未泄露对象内容。
- **审计**：16 类动作覆盖登录、文件、会话、消息、模型、MCP、审批、任务、产物与权限变更；UPDATE/DELETE 均被触发器拒绝；tenant_id 非空。
- **密钥**：日志中无假 API key/主密钥；目录凭据无明文。
- **B-06**：Trivy 0 可见项；两条 MCP CVE 按 ADR-0011/DEC-0005 抑制；MCP server 为 STDIO + `riccon-builtin` 白名单、工具 READ、`requires_approval=TRUE`、全量 HITL。
- **会话/CSRF**：真实 Web 生产构建经同源代理登录成功；错误凭据统一提示；未登录 API 401。
- **新风险**：F-012 的对象存储 CORS 属于浏览器侧数据路径；不要用关闭浏览器安全策略或把凭据放进 URL 的方式绕过。

## 8. 残余风险与 M03 前置

1. **F-012（P1，必须先修）**：对象存储 CORS 或同源代理；修复后重跑 J2 UI 产物正文/下载。当前 M02 无法演示“打开报告正文”。
2. **F-013（P2）**：终态后刷新一次时间线，或在取消/拒绝后短暂继续轮询，避免用户看到“已终止 + 进行中”的矛盾状态。
3. **F-007（P2）**：补 `e2e/perf/mvc-smoke.js` 与 `deploy/compose/docker-compose.preview.yml`（或修改合同）；当前只有单次 321–326ms 首字测量，不能声称 p95。
4. **F-011（P2）**：M04/M07 前补齐或删除 `/models` 与 admin 目录声明，避免继续契约漂移。
5. **真实栈覆盖缺口**：AC-02 其余 4 种格式、AC-05/AC-11 失败路径、AC-16 键盘路径仍需真实栈或更强的集成证据。
6. **墙钟**：M02 已超过原 8h；DEC-0009 已由用户选择提高到 20h，本复验在 20h 内完成。M03 前应复盘拆分粒度，避免再次靠提高上限收尾。
7. **环境**：真实栈使用假模型与假 MCP，不验证供应商延迟/输出质量；真实 OpenAI/客户网关、真实 MCP 供应商、OIDC、OCR/Excel 不在本轮范围。
8. **frontend 注释/规范**：本轮未发现新增生产代码的注释或规范问题；验证资产均含中文说明。

## 9. 未验证 / 不声称

- 未执行 `commands.perf`（F-007）、`commands.run`、`commands.deploy_preview`、`commands.deploy_prod`、`riccon release check`。
- 未验证真实 OpenAI/客户网关、真实 MCP 供应商、真实 OIDC（M03）、OCR/Excel（M05）。
- 未验证 Firefox/WebKit、移动端真机、生产 TLS/反向代理、K8s/Helm、对象存储生产 CORS 策略。
- 未验证真实栈 MCP 超时/生成失败重试；未验证 J2 全部失败态。

## 10. 证据链接

- 合同命令：EV-0258 install、EV-0259 build、EV-0260 lint、EV-0261 typecheck、EV-0262 test、EV-0263 coverage、EV-0264 security、EV-0265 a11y、EV-0268 test_e2e（exit 1）、EV-0269 docs、EV-0270 evidence audit。
- 真实栈：`.riccon/evidence/TASK-0029/real-stack-summary.json`、`real-stack-j2.json`、`real-stack-checks.json`、`real-stack-playwright.log`、`real-stack-*.log`。
- F-012：`.riccon/evidence/TASK-0029/real-stack-j2-artifact-body-failed.png`、`real-stack-j2-artifact-body-trace.zip`、`real-stack-playwright.log`（`j2.spec.ts:63` 失败与 30s disabled）。
- 证据审计：EV-0270 输出与 `scripts/real-stack/audit-evidence.mjs`。
- 上一轮评审：`.riccon/archive/reviews/M02-review-001-fail.md`。
