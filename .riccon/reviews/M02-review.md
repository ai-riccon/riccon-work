---
review_id: REV-M02-003
module_id: M02
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M02_TASK-0032_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
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
    - EV-0299
    - EV-0298
  build:
    - EV-0289
  tests:
    - EV-0292
  coverage:
    - EV-0293
  e2e:
    - EV-0301
  docs:
    - EV-0297
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
  - EV-0285
  - EV-0286
  - EV-0287
  - EV-0271
  - EV-0272
  - EV-0273
  - EV-0274
  - EV-0275
  - EV-0276
  - EV-0277
  - EV-0281
  - EV-0282
  - EV-0283
  - EV-0284
  - EV-0288
  - EV-0289
  - EV-0290
  - EV-0291
  - EV-0292
  - EV-0293
  - EV-0294
  - EV-0295
  - EV-0296
  - EV-0297
  - EV-0298
  - EV-0299
  - EV-0300
  - EV-0301
findings:
  - id: F-001
    severity: P1
    title: "J2 真实 UI 无法选择 MCP 工具：/api/v1/mcp-tools 契约端点缺失"
    status: fixed
    evidence: "EV-0299 真实 UI 加载 market_data · 只读 · 需审批 并创建任务；real-stack-checks.json mcpTools.alphaTotal=1、betaTotal=0"
  - id: F-002
    severity: P1
    title: "真实链路首字被缓冲到模型流结束"
    status: fixed
    evidence: "EV-0299：REAL_FIRST_TOKEN_MS=337；REAL_GROWTH_SAMPLES=[10,30,39,60,70,80,100,110,130]；J1 运行详情首字 70ms"
  - id: F-003
    severity: P1
    title: "J2 检索阶段只返回 hitCount，不返回来源条目/片段"
    status: fixed
    evidence: "EV-0299：审批前 details.sources=4、任务 sources=4，片段非空；真实 UI 显示 [1]-[4] 来源与片段"
  - id: F-004
    severity: P1
    title: "J1 运行详情端点 /api/v1/runs/{runId} 未实现"
    status: fixed
    evidence: "EV-0299：真实 UI 运行详情显示 fake-chat/首字 70ms/耗时 3.167s；API runs 200，跨租户 404/404"
  - id: F-005
    severity: P2
    title: "审批拒绝后 TOOL/APPROVAL 阶段仍为 RUNNING"
    status: fixed
    evidence: "EV-0299：拒绝后 TOOL:SKIPPED、APPROVAL:SKIPPED，details 含 REJECT/审批人/时间；数据库终态一致"
  - id: F-006
    severity: P2
    title: "J1 filesRef 同步竞态：解析成功后立即提问会省略 fileIds"
    status: fixed
    evidence: "EV-0299：REAL_J1_FAST_REQUEST_BODY 含 fileIds；快速发送产生文件引用"
  - id: F-007
    severity: P2
    title: "合同 commands.perf/deploy_preview 指向不存在的脚本或 compose 文件"
    status: accepted
    evidence: "AGENTS.md commands.perf 仍指向 e2e/perf/mvc-smoke.js；仓库无 e2e/perf/ 与 docker-compose.preview.yml；作为 M03/M09 前置，不计入 M02 module DoD"
  - id: F-008
    severity: P2
    title: "model_invocation.first_token_ms 记录整段模型流耗时"
    status: fixed
    evidence: "EV-0299：DB first_token_ms=73ms、duration_ms=3167ms；指标已区分首字与总耗时"
  - id: F-009
    severity: P3
    title: "J1 回答以原始 Markdown 文本展示"
    status: open
    evidence: "真实栈 J1 页面仍显示原始 Markdown；不阻断 M02 验收"
  - id: F-010
    severity: P3
    title: "真实 J2 a11y 仅覆盖工具加载失败错误态"
    status: fixed
    evidence: "EV-0299：J2 创建→审批→产物正常态 axe 无 serious/critical，375px 无横向滚动；EV-0295 7 个 @a11y 用例"
  - id: F-011
    severity: P2
    title: "OpenAPI 声明的 /models 与四个 admin 目录端点返回 404"
    status: accepted
    evidence: "EV-0299 real-stack-checks.json gaps：/models、admin/model-providers、admin/models、admin/mcp-servers、admin/mcp-tools；M04/M07 前补齐或删除声明"
  - id: F-012
    severity: P1
    title: "J2 产物正文在真实浏览器不可读：对象存储预签名 URL 缺 CORS，下载按钮 disabled"
    status: fixed
    evidence: "EV-0299：正文经同源 /content 渲染为分节 Markdown；REAL_J2_DOWNLOAD_BYTES=2156，下载文件含 # / ## 摘要 / ## 引用清单 / MCP 工具；无 CORS 错误、无对象存储请求；content 响应 text/markdown;charset=UTF-8、no-store、nosniff、Content-Length=2156"
  - id: F-013
    severity: P2
    title: "审批拒绝后前端停止轮询，时间线在刷新前仍显示 TOOL/APPROVAL 进行中"
    status: fixed
    evidence: "EV-0299：REAL_J2_REJECT_STALE_RUNNING_STAGES=0（无需刷新）；REAL_J2_REJECT_DETAIL_REQUESTS settled=4 total=4，终态后两个轮询间隔无新增详情请求"
  - id: F-014
    severity: P3
    title: "J2 首条检索片段来自文档标题段，信息量低"
    status: open
    evidence: "EV-0299：REAL_J2_SNIPPET=2026 年中国企业协同办公市场研究（第 1 段）；第 2-4 条为正文数据，来源/片段均非空"
  - id: F-015
    severity: P2
    title: "超过 2 MiB 的产物无法从 UI 下载；前端注释声称仍可下载"
    status: accepted
    evidence: "artifact-panel.tsx handleDownload 使用同源 content 端点，而 ArtifactService.content 对 >2MiB 返回 413（EV-0299 oversizedContent=413 RICCON-ARTIFACT-413）；M02 报告实测 2156 字节；M03/M04 前确定大产物下载策略（预签名回退或独立流式端点）"
  - id: F-016
    severity: P3
    title: "证据卫生：TASK-0030 有 3 条 dirty 未引用 EV，TASK-0031 活动列表保留失败重试 EV-0277"
    status: accepted
    evidence: "EV-0278/0279/0280 dirty=true 且未被任务引用，正式证据为 EV-0285/0286/0287；EV-0277 exit=1 为 stub E2E 文案定位缺陷，已被 EV-0281 替代；证据审计显式允许并记录，不影响 DoD 绑定"
  - id: F-017
    severity: P3
    title: "ADR-0009/ADR-0014 仍只描述预签名下载，未记录同源 content 读取路径"
    status: accepted
    evidence: "docs/adr/ADR-0009-tenant-isolation-enforcement.md、ADR-0014-m02-api-event-contract-and-sse.md；新增端点已在 contracts/openapi.yaml 记录，属增量路径，M03 文档收口时补充"
created_at: "2026-11-18T13:09:58+08:00"
---
# M02 独立评审（复验 2）：云工作空间与办公协同闭环

- **Verdict：`pass`**。REV-M02-002 的两个开放问题均已修复并在真实栈复验：**F-012（P1）** 产物正文改走同源 `GET /api/v1/artifacts/{id}/versions/{v}/content`，浏览器正文渲染为分节 Markdown、下载真实成功（2156 字节）、无 CORS 错误且不再请求对象存储；**F-013（P2）** 审批拒绝进入终态后无需刷新即无“进行中”，且终态后详情轮询停止。
- **范围**：M02-AC-01..16、合同命令、TASK-0008..0031 全部活动证据审计、真实栈 J1/J2 全链路、F-012/F-013 定向回归、跨租户、审计 append-only、a11y、安全扫描。
- **独立性**：本评审由 `riccon/verifier` 在独立会话完成；未修改任何生产源码、迁移、构建配置、合同或 `contracts/`。本轮新增/修改仅限 `e2e/real/tests/j2.spec.ts`、`scripts/real-stack/{checks,audit-evidence}.mjs` 与 `.riccon/**`。
- **HEAD 与基线**：任务输入基线为 `ef8cab6`；验证资产提交 `791b071`、`162044f`，合同命令与真实栈证据录制于其后（commit `c6f5cb8`/`162044f`），全部 EV 的 commit 均为最终 HEAD 祖先。
- **上一轮评审**：`REV-M02-002`（verdict=fail）已原样归档到 `.riccon/archive/reviews/M02-review-002-fail.md`，未删除。

## 1. 结论先行

| 项目                  | 结果                                                                                                                                                                                                 |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-012 产物正文        | **pass**：真实浏览器正文渲染出“摘要/分析”分节；下载文件 2156 字节且含 Markdown 标记；console 无 CORS 错误；无 `/objects/` 请求；同源 content 端点返回 `text/markdown;charset=UTF-8`、`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Content-Length=2156` |
| F-012 2 MiB 上限      | **pass**：元数据 2,097,153 字节的版本返回 **413 `RICCON-ARTIFACT-413`**，响应不含 storage key；恰好 2 MiB 边界与对象实际超限路径由 `ArtifactContentApiTest` 覆盖                                       |
| F-013 终态残留        | **pass**：拒绝后 `REAL_J2_REJECT_STALE_RUNNING_STAGES=0`（未刷新）；补刷落定后 `settled=4 total=4`，两个轮询间隔无新增详情请求                                                                       |
| 首字预算（AC-03）     | **pass**：真实浏览器 **337ms**（上一轮 326ms）；增长采样 `[10,30,39,60,70,80,100,110,130]` 严格递增；DB `first_token_ms=73ms`、`duration_ms=3167ms`                                                  |
| J1 主链路             | **pass**：登录、上传、解析、提问、引用、重开；快速发送请求体含 `fileIds`；运行详情显示 `fake-chat`、首字 70ms、耗时 3.167s                                                                         |
| J2 主链路             | **pass**：创建、4 条来源/片段、审批、MCP 调用、4 阶段 SUCCEEDED、5 条引用（4 KNOWLEDGE + 1 MCP）、产物正文/引用/数据来源可读                                                                          |
| J2 拒绝路径           | **pass**：任务终止、无产物、`TOOL:SKIPPED`/`APPROVAL:SKIPPED`、审批记录含决定/审批人/时间；`rejectToolInvocationCount=0`                                                                             |
| 跨租户                | **pass**：8 类对象（含新增 content 端点）404；J1/J2 运行详情 404/404；beta 工具目录 total=0；响应不含 A 内容                                                                                          |
| 审计 append-only      | **pass**：16 类动作有记录（含 `artifact.downloaded` 5 次）；UPDATE/DELETE 均 ERROR 1644 `audit_event is append-only`                                                                                  |
| a11y / 375px          | **pass**：stub 7 个 @a11y 通过；真实栈 J1、J2 列表与 J2 正常详情 axe 无 serious/critical，375px 无横向滚动                                                                                            |
| 合同命令              | **pass**：install/build/lint/typecheck/test/coverage/security/a11y/test_e2e/docs 全部 exit 0；证据审计 196 条活动 EV 通过（9 条显式预期失败、3 条脏证据未引用）                                                                         |

## 2. 验收标准逐条对账

| AC                                 | 结果                                       | 说明 / 证据                                                                                                                                               |
| ---------------------------------- | ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M02-AC-01 登录                     | **pass**                                   | 真实栈浏览器正确凭据进入 `tenant-alpha`；错误凭据统一提示。EV-0299。                                                                                        |
| M02-AC-02 上传/解析                | **pass（DOCX 真栈；其余格式集成测试）**    | 真实栈 DOCX 上传/解析/引用通过；PDF/XLSX/PNG/JPEG 由 Maven 集成测试覆盖，未逐格式跑真实浏览器。EV-0292/EV-0299。                                             |
| M02-AC-03 首字 ≤3s、逐步增长、停止 | **pass（停止为 stub）**                    | 337ms、增长采样严格递增；`first_token_ms` 与总耗时分离；停止按钮由 stub E2E 覆盖。EV-0299。                                                                  |
| M02-AC-04 引用可定位               | **pass**                                   | 真实 J1 显示文件引用；快速发送仍带 `fileIds`；运行详情可查。EV-0299。                                                                                       |
| M02-AC-05 失败/重试                | **pass（stub/集成；真栈未注入）**          | stub E2E 与 Maven 集成测试覆盖；真实栈未注入模型/解析故障。                                                                                                 |
| M02-AC-06 J2 创建入口              | **pass**                                   | 真实 UI 加载 `market_data · 只读 · 需审批` 并创建任务；`/api/v1/mcp-tools` 200。EV-0299。                                                                   |
| M02-AC-07 检索来源                 | **pass**                                   | 审批前 UI/HTTP 均见 4 条来源、标题、定位与片段；`details.sources=4`、任务级 `sources=4`。EV-0299。                                                          |
| M02-AC-08 MCP 工具阶段             | **pass**                                   | 审批预览含工具名与脱敏参数；批准后 MCP 调用成功，阶段详情含调用 ID/摘要；真实 UI 显示工具与数据来源。EV-0299。                                               |
| M02-AC-09 审批通过/拒绝            | **pass**                                   | 批准/拒绝均通过；审批卡片显示审批人 `Alpha 员工`、时间与决定；拒绝后无需刷新即无“进行中”。EV-0299。                                                         |
| M02-AC-10 报告产物                 | **pass**                                   | 正文经同源 content 端点渲染为 Markdown；下载 2156 字节成功；引用清单与数据来源可见；2 MiB 上限返回 413。EV-0299。                                            |
| M02-AC-11 MCP 超时/生成失败重试    | **pass（stub/集成；真栈未注入）**          | stub E2E 与 Worker 集成测试覆盖；真实栈未注入超时/生成失败。                                                                                                 |
| M02-AC-12 跨租户对象               | **pass**                                   | 会话、任务、产物、版本、下载 URL、**正文**、知识库文档、文件 8 类 404；运行详情 404/404。EV-0299。                                                            |
| M02-AC-13 越权套件零 skip          | **pass（当前 M02 面）**                    | Maven 533 tests、0 skipped；前端 153 tests、0 skipped；真实栈覆盖当前对象、工具、运行与应用层管理入口；M03/M04 新增 admin 接口需继续扩展。                   |
| M02-AC-14 审计覆盖                 | **pass**                                   | 真实库 16 类动作：登录、文件上传/删除、会话、消息、模型调用开始/完成、MCP、审批、任务、产物、权限变更等；tenant_id 非空。EV-0299。                          |
| M02-AC-15 审计不可改删             | **pass**                                   | `audit_event` UPDATE/DELETE 均被触发器以 1644 拒绝；API 无删除/修改端点。EV-0299。                                                                            |
| M02-AC-16 状态、375px、键盘        | **pass（键盘路径 stub）**                  | 真实 J1/J2 列表/J2 详情 axe 无 serious/critical、375px 无横向滚动；键盘上传/审批由 stub E2E 覆盖。EV-0295/EV-0299。                                          |

**模块验收**：J1/J2 E2E、跨租户、审计不可改删、合同命令与 B-06 安全门禁均通过；`dod.acceptance=true`、`dod.e2e=true`。

## 3. 合同命令执行（TASK-0032）

| 合同项         | 命令                                                                                                                     | EV              | 退出码 | 观察                                                                                                                                          |
| -------------- | ------------------------------------------------------------------------------------------------------------------------ | --------------- | ------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| install        | `./mvnw -q -T1C -DskipTests dependency:go-offline && pnpm install --frozen-lockfile`                                     | EV-0288         | 0      | lockfile 未变                                                                                                                                  |
| build          | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                    | EV-0289         | 0      | API/Worker exec jar 与 web dist 生成                                                                                                           |
| lint           | `./mvnw -q spotless:check && pnpm -w lint`                                                                               | EV-0290/EV-0300 | 0      | Spotless、ESLint、Prettier 通过；EV-0300 覆盖最终工作树                                                                                       |
| typecheck      | `pnpm -w typecheck`                                                                                                      | EV-0291         | 0      | `tsc --noEmit` 通过                                                                                                                            |
| test           | `./mvnw -q -T1C verify && pnpm -w test`                                                                                  | EV-0292         | 0      | 后端 **533 tests**，0 failure/error/skip；前端 **153 tests**，0 skip                                                                           |
| coverage       | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                      | EV-0293         | 0      | api line 90.31%/branch 71.16%；worker 87.89%/70.77%；platform-common 98.55%/98.15%；web lines 87.38%/branches 80.39%；均过 80/70               |
| security       | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` | EV-0294         | 0      | 0 可见 critical/high；MCP 两条 CVE 按 ADR-0011/DEC-0005 抑制，到期 2026-12-31                                                                  |
| a11y           | `pnpm -w a11y`                                                                                                           | EV-0295         | 0      | 7 个 @a11y 用例通过（含 J1 运行详情与 J2 完整闭环）                                                                                            |
| test_e2e（合同） | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                | EV-0301         | 0      | stub **19 passed**；与合同命令字符串完全一致                                                                                                   |
| test_e2e（真栈） | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e && REAL_EVIDENCE_TASK=TASK-0032 node scripts/real-stack/run.mjs` | EV-0296/EV-0299 | 0      | stub 19 passed；真实栈 **10 passed**；J2 HTTP 闭环与安全检查通过；EV-0299 覆盖最终测试树                                                       |
| docs           | docs/contracts 存在性与 diff                                                                                             | EV-0297         | 0      | `docs/PRODUCT.md`、`ARCHITECTURE.md`、`CODING-STANDARDS.md`、`docs/security/THREAT-MODEL.md`、`RUNBOOK.md`、`contracts/openapi.yaml` 存在；OpenAPI +67 行 |
| evidence audit | `node scripts/real-stack/audit-evidence.mjs`                                                                             | EV-0298         | 0      | TASK-0008..0031 共 **196** 条活动 EV 全部存在、哈希/字节数匹配、dirty=false、commit 为祖先；无重复/孤立（显式例外见 §6）                         |
| perf           | `k6 run e2e/perf/mvc-smoke.js`                                                                                           | —               | —      | **未执行**：文件仍不存在，见 F-007；作为 M03/M09 前置，不计入 M02 module DoD                                                                  |

说明：EV-0301 为与合同完全一致的 `test_e2e`；EV-0296/EV-0299 在同一合同命令后追加真实栈 runner，均 exit 0；EV-0299 在补强“终态停轮询非空守卫 + 下载字节证据”后重跑，最终真实栈结论以 EV-0299 为准，`dod_evidence.e2e` 绑定合同命令 EV-0301。

## 4. 真实栈复验结果（EV-0299）

环境：Docker Compose（MySQL 8.4.13、OpenSearch、RustFS）、JDK 21、Node 26、Web 生产构建同源代理 8092、API 18080、Worker 18081、假模型 18990；J2 工具调用经 Worker 启动 `node scripts/real-stack/mcp-server.mjs` 子进程完成。Playwright `retries: 0`。

- **F-012 正文/下载**：`.research-artifact-body` 渲染出“摘要”“分析”分节；`REAL_J2_DOWNLOAD_BYTES=2156`，下载文件含 `# `、`## 摘要`、`## 引用清单`、`MCP 工具`；浏览器 `corsErrors=[]`、`objectStorageFailures=[]`；产物请求包含 `/content`，不含 `/objects/`。
- **F-012 响应头/上限**：`artifactContent.bytes=2156`、`Content-Type: text/markdown;charset=UTF-8`、`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Content-Length=2156`；跨租户 content 404；2,097,153 字节版本 413 `RICCON-ARTIFACT-413` 且不泄露 storage key。
- **F-013 终态**：`REAL_J2_REJECT_STALE_RUNNING_STAGES=0`；`REAL_J2_REJECT_DETAIL_REQUESTS settled=4 total=4`，终态后观察 2.2s 无新增详情请求；刷新后仍为 0 且出现“已跳过”。
- **首字**：`REAL_FIRST_TOKEN_MS=337`，wall 401ms；增长采样 `[10,30,39,60,70,80,100,110,130]`；J1 运行详情首字 70ms。
- **J1**：快速发送请求体含 `fileIds`；运行详情含模型调用；刷新重开正常。
- **J2**：`citationCount=5`、`firstStageDetailsSources=4`、任务 sources=4、首片段长度 18；批准后 4 阶段 `SUCCEEDED`，J2 运行详情 2 条模型调用；拒绝后 `TOOL:SKIPPED`、`APPROVAL:SKIPPED`、`rejectToolInvocationCount=0`。
- **跨租户**：conversation、task、artifact、artifactVersion、artifactDownload、**artifactContent**、knowledgeDocument、file 共 8 类 404；J1/J2 runs 404/404；beta 工具目录 total=0。
- **审计**：16 类动作；`artifact.downloaded=5`；UPDATE/DELETE 均 ERROR 1644 `audit_event is append-only`；tenant_id 无空值。
- **密钥**：API/Worker/模型日志未出现假 API key/主密钥；`provider_credential.ciphertext` 无明文。
- **a11y**：真实栈 10 个浏览器用例含 J1/J2 a11y 与 375px；无 serious/critical、无横向滚动。

## 5. Findings

| ID    | 级别 | 状态     | 发现                                                                 | 位置 / 复现                                                                                                                                                          | 观察 vs 期望                                                       |
| ----- | ---- | -------- | -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| F-001 | P1   | fixed    | J2 真实 UI 无法选择 MCP 工具                                         | 旧 `/api/v1/mcp-tools` 404；EV-0299 真实 UI 加载并创建任务；checks `alphaTotal=1`、`betaTotal=0`                                                                     | 已按租户返回授权工具                                               |
| F-002 | P1   | fixed    | 真实链路首字被缓冲                                               | 旧 4380ms；EV-0299 首字 337ms、正文严格增长；DB 73ms vs duration 3167ms                                                                                             | 满足 ≤3s 且逐段到达                                                |
| F-003 | P1   | fixed    | J2 检索无来源/片段                                                   | EV-0299 4 条来源、片段与定位；审批前 UI 可见                                                                                                                        | 满足 AC-07                                                         |
| F-004 | P1   | fixed    | J1 运行详情端点缺失                                                  | EV-0299 UI 抽屉与 API `/runs/{runId}` 可用，跨租户 404                                                                                                              | 满足 PRODUCT J1 模型调用记录                                       |
| F-005 | P2   | fixed    | 拒绝后 TOOL/APPROVAL 仍 RUNNING                                      | EV-0299 数据库与 HTTP 均为 SKIPPED，含审批决定/人/时间                                                                                                              | 后端已修复                                                         |
| F-006 | P2   | fixed    | J1 filesRef 竞态                                                     | EV-0299 快速发送请求体含 `fileIds` 并产生引用                                                                                                                       | 已修复                                                             |
| F-007 | P2   | accepted | `commands.perf`/`deploy_preview` 指向不存在文件                      | `AGENTS.md`；仓库无 `e2e/perf/` 与 `docker-compose.preview.yml`                                                                                                      | M03/M09 前置；本轮无 k6 p95                                        |
| F-008 | P2   | fixed    | `first_token_ms` 记录整段流耗时                                      | EV-0299 DB 73ms vs duration 3167ms                                                                                                                                   | 指标已分离                                                         |
| F-009 | P3   | open     | J1 回答以原始 Markdown 展示                                          | 真实栈页面快照仍见原始 Markdown                                                                                                                                      | 期望 Markdown 渲染或明确纯文本策略；不阻断                         |
| F-010 | P3   | fixed    | J2 a11y 只覆盖错误态                                                 | EV-0299 J2 正常详情 axe 0 serious/critical、375px 无横向滚动；EV-0295 7 个 @a11y                                                                                     | 已覆盖正常路径                                                     |
| F-011 | P2   | accepted | `/models` 与 4 个 admin 目录端点 404                                 | EV-0299 `real-stack-checks.json.gaps`                                                                                                                               | 契约漂移；M04/M07 前补齐或删除声明                                 |
| F-012 | P1   | fixed    | J2 产物正文浏览器不可读、下载 disabled                               | EV-0299 同源 content 正文/下载/响应头/413；无 CORS、无对象存储请求                                                                                                   | 期望正文可读且下载可用，已满足                                     |
| F-013 | P2   | fixed    | 拒绝后前端停止轮询，刷新前仍“进行中”                                 | EV-0299 `STALE=0`、`settled=4 total=4`                                                                                                                               | 期望终态无残留且停轮询，已满足                                     |
| F-014 | P3   | open     | 首条检索片段是文档标题段                                             | EV-0299 第 1 段为标题；第 2-4 条为正文数据                                                                                                                          | 片段非空但信息量低；不阻断                                         |
| F-015 | P2   | accepted | >2 MiB 产物无法从 UI 下载，前端注释声称仍可下载                      | `artifact-panel.tsx` handleDownload 走 content 端点；`ArtifactService.content` 对 >2MiB 返回 413（EV-0299）                                                           | M02 报告 2156 字节；M03/M04 前定大产物策略                         |
| F-016 | P3   | accepted | 证据卫生：3 条 dirty 未引用 EV + 1 条失败重试 EV 留在活动列表        | EV-0278/0279/0280 dirty 且未引用；EV-0277 exit=1 已被 EV-0281 替代；审计显式允许                                                                                     | 不影响 DoD 绑定；建议后续归档                                       |
| F-017 | P3   | accepted | ADR-0009/ADR-0014 未记录同源 content 读取路径                         | `docs/adr/ADR-0009...`/`ADR-0014...`；新增端点仅在 OpenAPI 记录                                                                                                      | 增量路径；M03 文档收口补充                                         |

P0：**无**。P1：**无 open**（F-012 fixed）。P2/P3 中 F-007/F-011/F-015/F-016/F-017 已书面接受；F-009/F-014 为 P3 open，不阻断 M02。

## 6. 证据审计

- **TASK-0008..0031**：196 条活动 EV 全部存在，SHA-256/字节数匹配，`manual=false`，`dirty=false`，commit 均为 HEAD 祖先；无重复 EV ID；无 M02 范围内未被任务引用的孤立 EV（显式允许的 3 条 dirty 证据除外）。
- **非零退出共 9 条**，均为显式预期：EV-0129/0130（证明 audit_event UPDATE/DELETE 被拒绝）、EV-0133/0134（证明触发器创建在缺参时失败）、EV-0232/0266/0267/0268（历史真实栈失败/定位器缺陷，保留追溯）、EV-0277（stub E2E 文案定位缺陷，已被 EV-0281 替代）。
- **TASK-0032**：EV-0288..EV-0301 共 14 条，全部 `dirty=false`、exit 0、commit 为 HEAD 祖先；覆盖 install/build/lint/typecheck/test/coverage/security/a11y/test_e2e/docs/audit。
- **假绿检查**：测试命令非零测试（后端 533、前端 153），0 skipped；Playwright `retries: 0`；真实栈用例全部含强断言（下载文件内容、轮询请求计数、响应头、413、跨租户 404）；未发现吞错、空断言或只对坏状态快照。
- 审计脚本：`scripts/real-stack/audit-evidence.mjs`（EV-0298），本轮扩展覆盖 TASK-0031，并对历史脏/失败证据显式 allowlist。

## 7. 安全、租户与审计

- **跨租户**：8 类对象（含新增 content）404；运行详情 404/404；B 工具目录 total=0；B 在 A 工作空间创建任务 404；响应不泄露 A 内容。
- **审计**：16 类动作覆盖登录、文件、会话、消息、模型、MCP、审批、任务、产物、权限变更；UPDATE/DELETE 被触发器拒绝；tenant_id 非空。
- **正文安全**：content 端点固定 `nosniff`、`no-store`，MIME 白名单为 Markdown/纯文本（`canonicalContentType`），不返回 storage key/URL/凭据；2 MiB 元数据与对象实际大小双重校验。
- **密钥**：日志无假 API key/主密钥；目录凭据无明文。
- **B-06**：Trivy 0 可见项；两条 MCP CVE 按 ADR-0011/DEC-0005 抑制且带到期日；MCP server 为 STDIO + `riccon-builtin` 白名单、工具 READ、`requires_approval=TRUE`、全量 HITL。
- **会话/CSRF**：真实 Web 生产构建经同源代理登录成功；错误凭据统一提示；未登录 API 401。

## 8. 残余风险与 M03 前置

1. **F-015（P2，已接受）**：>2 MiB 产物无 UI 下载路径且前端注释不准确；M03/M04 前确定预签名回退或独立流式下载端点。
2. **F-007（P2，已接受）**：补 `e2e/perf/mvc-smoke.js` 与 `deploy/compose/docker-compose.preview.yml`（或修改合同）；当前只有单次 337ms 首字测量，不能声称 p95。
3. **F-011（P2，已接受）**：M04/M07 前补齐或删除 `/models` 与 admin 目录声明，避免契约漂移。
4. **真实栈覆盖缺口**：AC-02 其余 4 种格式、AC-05/AC-11 失败路径、AC-16 键盘路径仍需真实栈或更强集成证据。
5. **F-017（P3，已接受）**：ADR-0009/ADR-0014 需在 M03 文档收口时补充同源 content 路径。
6. **环境限制**：真实栈使用假模型与假 MCP，不验证供应商延迟/输出质量；真实 OpenAI/客户网关、真实 MCP 供应商、OIDC、OCR/Excel 不在本轮范围。
7. **墙钟**：M02 已超过原 8h；DEC-0009 将上限提高到 20h，本轮复验在 20h 内完成。

## 9. 未验证 / 不声称

- 未执行 `commands.perf`（F-007）、`commands.run`、`commands.deploy_preview`、`commands.deploy_prod`、`riccon release check`。
- 未验证真实 OpenAI/客户网关、真实 MCP 供应商、真实 OIDC（M03）、OCR/Excel（M05）。
- 未验证 Firefox/WebKit、移动端真机、生产 TLS/反向代理、K8s/Helm、生产对象存储 CORS 策略。
- 未验证真实栈 MCP 超时/生成失败重试；未验证 J2 全部失败态。

## 10. 证据链接

- 合同命令：EV-0288 install、EV-0289 build、EV-0290/EV-0300 lint、EV-0291 typecheck、EV-0292 test、EV-0293 coverage、EV-0294 security、EV-0295 a11y、EV-0301 合同 test_e2e、EV-0296/EV-0299 真栈 test_e2e、EV-0297 docs、EV-0298 evidence audit。
- 真实栈：`.riccon/evidence/TASK-0032/real-stack-summary.json`、`real-stack-j2.json`、`real-stack-checks.json`、`real-stack-playwright.log`、`real-stack-*.log`。
- 关键日志行：`REAL_FIRST_TOKEN_MS=337`、`REAL_GROWTH_SAMPLES=[10,30,39,60,70,80,100,110,130]`、`REAL_J2_DOWNLOAD_BYTES=2156`、`REAL_J2_REJECT_STALE_RUNNING_STAGES=0`、`REAL_J2_REJECT_DETAIL_REQUESTS settled=4 total=4`。
- 证据审计：EV-0298 输出与 `scripts/real-stack/audit-evidence.mjs`。
- 上一轮评审：`.riccon/archive/reviews/M02-review-002-fail.md`；更早：`M02-review-001-fail.md`。
