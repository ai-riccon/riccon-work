---
review_id: REV-TASK-0206-001
module_id: M25
task_id: TASK-0206
reviewer:
  agent: riccon/verifier
  session: ses_df48d2e1bffeye8NFiRhNnZNPK
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 52e6d0fd960debf579d35423c712a812492a2432
  - 381d7d1607918910b6b5538fb77c7eff25c77d72
  - a518bf1e6023c0e8a274f8c1ba5184705bea4a3e
  - 7ac5d3c1cc1158b8ca3a380eb5e8c1a3ff62cbb7
head_commit: 7ac5d3c1cc1158b8ca3a380eb5e8c1a3ff62cbb7
baseline_commit: 96885ef5ff26315942a0c5023cfd388606a1e5eb
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: false
  a11y: false
  security: true
  review: true
  evidence: true
  docs: true
dod_not_applicable:
  - migration
  - perf
dod_note: >-
  acceptance 按任务卡两条逐条对账，主体通过：8 个 1.5.0-m05b 端点、schema、错误码、权限、凭据只写+掩码、
  defaultVisibility 显式必填、PATCH 部分更新、sync 202/409、test 分类、items 状态/errorCode、删除
  KEEP/SOFT_DELETE 均在实现和测试中对上；两条 AC 分别带 F-001（端点 allowlist 只能文本输入）与
  F-002/F-003（E2E stub、真实浏览器 a11y 由 TASK-0212 V1b 承接）的残余，标为 pass (with residual)，
  不是无条件 pass。build 由 EV-0996（381d7d1 的 web typecheck/lint/build，exit 0）与 EV-0999
  （verifier 在 7ac5d3c 复跑 typecheck/lint）支撑；tests 由 EV-0995（全量 web 73 files/640 tests）
  与 EV-0998（verifier 定向 5 files/58 tests）支撑；coverage 由 EV-0995（87.11/80.32/86.98，
  新增 connectors 85.47/75.46、services/connector 84.23/79.28）支撑；security 由本次代码/契约/
  权限/凭据/DOM/URL/日志审查支撑；review 为本文件；evidence 为 EV-0995/0996/0998/0999，均核对
  commit/dirty/exit/hash。e2e/a11y 为 false：当前 e2e stub 没有连接器权限与端点，也没有 connector
  spec，无法进行真实浏览器验证；由 TASK-0212 V1b 与 M25 模块 gate 承接。docs 为 true（no-impact：
  部署 allowlist 与凭据保留已由 TASK-0211 写入 RUNBOOK，本次只改 web）。migration/perf 不适用。
reviewed_evidence:
  - EV-0995
  - EV-0996
  - EV-0998
  - EV-0999
test_impact: >-
  verifier 在 7ac5d3c 独立复跑 `pnpm --filter @riccon/web exec vitest run
  src/pages/admin/connectors src/services/connector config/routes.test.ts --reporter=dot`
  （EV-0998，exit 0，5 files/58 tests，0 fail/0 skip），并复跑
  `pnpm --filter @riccon/web typecheck && pnpm --filter @riccon/web lint`（EV-0999，exit 0）。
  全量 web test/coverage 复用 builder/Lead 的 EV-0995（381d7d1，dirty=false，exit 0，73 files/640
  tests，覆盖率 87.11/80.32/86.98），build 复用 EV-0996（381d7d1，dirty=false，exit 0）；核对
  381d7d1..7ac5d3c 无 apps/web 变更，HEAD 期间只增加了 .riccon、docs/RUNBOOK.md 与 scripts/ops。
  未跑全量 E2E、axe 或真实浏览器 375px/键盘路径：stub 无连接器支持，且按任务说明由 TASK-0212
  V1b 承接。未使用 skip、retry、`--no-file-parallelism` 或放宽断言；测试文件无 `.skip/.only/.todo`。
findings:
  - id: F-001
    severity: P2
    status: accepted
    title: "端点 ID 只能自由文本输入，未提供部署 allowlist 选择；与任务 AC1 / M05-AC-10 的“从允许列表选择、端点不可自由输入”有残余"
    summary: >-
      `apps/web/src/pages/admin/connectors/connector-form-modal.tsx:325-343` 使用 AutoComplete 文本输入，
      建议值只来自当前页已有 connector 的 `endpointId`（`apps/web/src/pages/admin/connectors/index.tsx:252-255`），
      不是部署的 `RICCON_CONNECTOR_ENDPOINTS` 目录。契约 1.5.0-m05b 没有端点目录读取端点；
      `contracts/openapi.yaml:1278-1284` 只要求 endpointId 必填/长度并由服务端拒绝非 allowlist，
      `:5812-5816` 说明不在 allowlist 返回 400 `RICCON-KB-400`；ADR-0023 §6.5
      （`docs/adr/ADR-0023-m05-knowledge-depth.md:601`）明确 allowlist 由部署配置提供。
      服务端 `ConnectorService.update/create` 调 `requireAllowedEndpoint`，非 allowlist 400 且不发起连接，
      因此 SSRF/凭据安全边界没有削弱；但 Product 的 M05-AC-10（`docs/PRODUCT.md:560`）明确写
      “从部署允许列表选择端点”“端点不可自由输入”，任务 AC1 也写“端点 allowlist 选择”。观察值：页面是自由输入；
      期望值（字面）：只能从部署 allowlist 选择。V1b 的 E2E-M05-04 若按该 Then 子句编写会失败。
    accepted_reason: >-
      安全由服务端 allowlist 强制（非 allowlist 400、无出网），前端错误文案明确指向
      RICCON_CONNECTOR_ENDPOINTS（`apps/web/src/services/connector/format.ts:338-340`）；当前契约没有
      可选用端点列表 API，且本任务 write_scope 不含 contracts/apps/api，前端硬编码 allowlist 会与部署配置漂移。
      残余属于产品形态/UX 与 AC 字面，不是凭据或 SSRF 漏洞。需 Product/Lead 在 M25 gate 前选择：
      （A）新增只读端点目录，建议最小形状 `GET /api/v1/knowledge/connectors/endpoints`，权限
      `knowledge.connector.read`，只返回 `id/label/production`（不返回 URL），UI 改 Select，并补
      stub/E2E/契约测试；（B）修订 M05-AC-10 与 E2E-M05-04 措辞为“填写部署提供的 endpointId，服务端拒绝
      非 allowlist 并给出可读原因”。未决策前 M05-AC-10 不得标为无条件 pass。
    owner: "riccon/product（AC/文案）+ riccon/lead（决策/排期）；若选 A 则加 riccon/architect + riccon/builder-kb-web"
    due: "M25 gate 前（TASK-0212 V1b 编写 E2E-M05-04 前）"
    evidence: "apps/web/src/pages/admin/connectors/connector-form-modal.tsx:325-343; apps/web/src/pages/admin/connectors/index.tsx:252-255; apps/web/src/services/connector/format.ts:338-340; contracts/openapi.yaml:1276-1284,5812-5816; docs/PRODUCT.md:560; docs/adr/ADR-0023-m05-knowledge-depth.md:601"
  - id: F-002
    severity: P2
    status: accepted
    title: "未提交 E2E stub 钩子；任务 AC2 的 E2E 部分由 TASK-0212 V1b 承接"
    summary: >-
      `e2e/stub/server.mjs` 没有连接器端点，stub 的 TENANT_ADMIN 权限也不含
      `knowledge.connector.*`（`e2e/stub/server.mjs:227-229`），`e2e/tests/` 没有 connector spec；
      builder 未修改 `e2e/`。任务 AC2 明确要求“组件测试 + E2E stub 钩子”，但任务 write_scope 只含
      `apps/web/**`，`e2e/**` 属于 TASK-0212（scope `apps/**/test/**`、`e2e/**`）。观察值：没有可执行的
      连接器 E2E 旅程；期望值：V1b 前应有 stub 权限/端点与至少一条 202/409/凭据掩码/375px 旅程。
    accepted_reason: >-
      这是任务卡 AC 与 write_scope 的冲突，不是生产缺陷；V1b 已明确负责 E2E/a11y，模块 gate 也需要
      E2E 证据。页面组件测试 + 真实 API 客户端测试已通过，E2E 缺口不会让已实现行为变成假绿；但
      TASK-0212 启动时必须把 stub 权限/连接器端点与 connector spec 纳入，否则 M25 gate 不能通过。
    owner: "riccon/verifier（TASK-0212 V1b）+ riccon/lead（确认 scope/排期）"
    due: "M25 gate 前（TASK-0212 V1b 完成时）"
    evidence: "e2e/stub/server.mjs:227-229（无 connector 权限）; e2e/tests/（无 connector spec）; .riccon/tasks/TASK-0212.json scope=apps/**/test/**,e2e/**"
  - id: F-003
    severity: P2
    status: accepted
    title: "新页面未做真实浏览器 a11y（axe/375px/键盘）验证；仅组件级标签与对比度检查"
    summary: >-
      `apps/web/src/pages/admin/connectors/index.test.tsx:159-176` 只校验状态 Tag 的内联前景/背景
      对比度，`:557-566` 只校验行按钮 label-in-name；没有 axe、375px 或 Modal/Drawer 焦点/键盘路径断言。
      `pnpm -w a11y` 依赖 e2e stub + `@a11y` spec（`e2e/playwright.config.ts`、`e2e/tests/a11y.ts`），
      当前 stub 没有连接器权限/端点，运行也不会覆盖新页面，故本任务未执行浏览器 a11y。潜在风险：
      `.connectors-table` 使用 `scroll={{x:1600}}`，Modal/Drawer 使用 `width/size = min(...,100vw)`
      （`apps/web/src/pages/admin/connectors/index.tsx:994,1034`、`connector-items-drawer.tsx:249`），
      需在 375px 真实浏览器确认页面级无横向滚动、焦点可见、Modal/Drawer 焦点陷阱与“打开连接器→测试→保存”
      键盘路径。
    accepted_reason: >-
      组件级可访问性基线（表单 label、aria-label、按钮 label-in-name、Tag 对比度、antd Modal/Drawer
      默认焦点管理）已实现并有测试；未发现已知 serious/critical 违规，但“axe 无 serious/critical、
      375px 无页面横向滚动、纯键盘完成关键路径”只能由 TASK-0212 V1b 的真实浏览器/a11y gate 证明。
      本任务把它记为残余，模块 gate 不得用组件测试替代 axe/E2E。
    owner: "riccon/verifier（TASK-0212 V1b）"
    due: "M25 gate 前"
    evidence: "apps/web/src/pages/admin/connectors/index.test.tsx:159-176,557-566; apps/web/src/pages/admin/connectors/connectors.css:26-41; apps/web/src/pages/admin/connectors/index.tsx:994,1034; apps/web/src/pages/admin/connectors/connector-items-drawer.tsx:249; e2e/tests/a11y.ts; e2e/playwright.config.ts"
evidence:
  - EV-0995
  - EV-0996
  - EV-0998
  - EV-0999
created_at: "2026-11-24T10:57:49+08:00"
---

# REV-TASK-0206-001：TASK-0206（M05b-B8b 连接器 Web）独立评审

## 结论

**通过（pass，带残余）**。连接器管理页的契约、权限、安全边界、测试与证据总体诚实、可复现：
8 个 1.5.0-m05b 端点全部接入，凭据只写+掩码，平台管理员 404/普通员工 403 与后端语义一致，
全量 web 覆盖率 87.11/80.32、定向 58 个测试与 typecheck/lint 均通过，范围严格落在 16 个 web 文件。
三项残余已在 F-001/F-002/F-003 记录：端点 allowlist 只能文本输入（与 M05-AC-10 字面有差距）、
E2E stub 钩子未提交、axe/375px/键盘的真实浏览器验证未执行；后两项由 TASK-0212 V1b 承接。

## AC 对账

任务卡 AC1：

> 连接器列表/创建/编辑/删除、端点 allowlist 选择、defaultVisibility、scheduleEnabled、
> 凭据只写+掩码、test/sync/items 状态与错误/重试入口；权限 403/404 文案与平台管理员 404 一致

**结果：pass (with residual)**。列表、创建、编辑、删除、defaultVisibility 显式必填、scheduleEnabled、
凭据掩码、test/sync/items 状态/错误/重试入口、403/404 文案均有实现与组件/服务层断言；
“端点 allowlist 选择”实际是自由文本输入 + 部署说明，残余见 F-001。

任务卡 AC2：

> 路由 /admin/connectors + 管理导航；375px、键盘、axe 无 serious/critical；组件测试 + E2E stub 钩子；
> 复用 1.5.0-m05b API，不实现后端

**结果：pass (with residual)**。路由与管理导航已加（`config/routes.ts:78-84,194-199`），
组件测试存在且真实断言；但 E2E stub 钩子未提交（F-002），375px/键盘/axe 的真实浏览器验证未执行（F-003）。
“复用 1.5.0-m05b API，不实现后端”满足：`96885ef..52e6d0f` 无后端/契约/e2e/docs/deploy 改动。

## 验证了什么

### 1. 契约一致性（8 端点/schema/错误码）

逐条对照 `contracts/openapi.yaml` 1.5.0-m05b：

| 端点                   | 方法/路径                                             | 前端实现                                 | 结论 |
| ---------------------- | ----------------------------------------------------- | ---------------------------------------- | ---- |
| listConnectors         | GET `/api/v1/knowledge/connectors`                    | `api.ts:101-112`，page/size/status/type  | 一致 |
| createConnector        | POST 同上                                             | `api.ts:121-127`，POST + Idempotency-Key | 一致 |
| updateConnector        | PATCH `/connectors/{id}`                              | `api.ts:136-141`，PATCH                  | 一致 |
| deleteConnector        | DELETE `/connectors/{id}?documents=KEEP\|SOFT_DELETE` | `api.ts:149-158`，204                    | 一致 |
| putConnectorCredential | PUT `/connectors/{id}/credential`                     | `api.ts:167-172`，只写                   | 一致 |
| testConnector          | POST `/connectors/{id}/test`                          | `api.ts:182-187`                         | 一致 |
| syncConnector          | POST `/connectors/{id}/sync`                          | `api.ts:196-205`，202                    | 一致 |
| listConnectorItems     | GET `/connectors/{id}/items`                          | `api.ts:214-227`                         | 一致 |

- `defaultVisibility`：创建表单不预选、Form.Item required 阻断，页面 `handleSubmit` 二次拦截
  （`index.tsx:339-348`），测试覆盖“缺省不提交”（`index.test.tsx:263-278`）。
- PATCH 部分更新：状态切换只发 `{status}`（`index.tsx:434`，测试 `index.test.tsx:340-351`）；
  编辑保存发送表单字段，未发送 `type`/`status`；后端 `parseUpdate` 对 `defaultDepartmentId: null`
  与 TENANT 组合接受（`ConnectorService.resolveDepartment`），编辑 TENANT/DEPT 切换不会被误 400。
- 凭据只写：创建可带 `credential`，编辑轮换走 PUT；响应只消费 `credentialMaskedHint`，任何响应
  都不回显明文/密文（`api.test.ts:172-194`、`index.test.tsx:385-408`）。
- sync 202/409：202 后本地标记 `lastSyncStartedAt=acceptedAt`、`lastSyncFinishedAt=null` 并禁用重复触发
  （`index.tsx:489-508`）；409 映射“已有排队或运行中的同步”（`format.ts:359-369`）。
- test 分类：AUTH/NETWORK/ENDPOINT/CONFIG 中文名 + 原分类展示（`format.ts:41-46`、`index.tsx:468-475`）。
- items：五种状态、errorCode、generation、lastSeenAt、summary 计数均有列/筛选（`connector-items-drawer.tsx:51-58,180-239,262-323`）。
- 删除：默认 KEEP，SOFT_DELETE 有二次警示；请求 `?documents=` 与契约一致（`index.tsx:1023-1070`、`api.test.ts:253-277`）。
- 错误码：400/403/404/409/503 与 RICCON-KB-400/409/503、RICCON-AUTH-403、RICCON-TENANT-404 均有固定中文映射
  （`format.ts:309-383`），不直接展示后端英文字段详情。

### 2. 安全/权限

- 普通员工无 `knowledge.connector.read`：页面直接 403 `RICCON-AUTH-403`，且不请求列表
  （`index.tsx:597-620`、测试 `index.test.tsx:178-187`）。
- 平台管理员：无连接器权限，页面显示 404 `RICCON-TENANT-404`，与后端“统一 404 以避免跨租户探测”一致
  （`access.ts:27-39`、`index.tsx:600-609`、测试 `index.test.tsx:189-199`）。
- 凭据：只写不读；列表/编辑只显示 `formatCredentialHint` 掩码，疑似完整凭据再次裁剪为首 4 末 3
  （`format.ts:258-273`）；页面无 `console.*`/localStorage/sessionStorage；凭据不出现在 URL、错误文案或通知
  （`index.tsx:331,384-395,406-407`；`api.test.ts:193`）；编辑弹窗 `destroyOnHidden` + `clearOnDestroy`。
- externalId：服务端契约只返回末段，前端再次取末两段并截 32 字符（`format.ts:239-246`），
  items 测试断言完整 `bucket/secret/b.pdf` 不出现、只出现 `secret/b.pdf`（`index.test.tsx:492-493`）。
- 不暴露 bucket/对象 key/预签名 URL：列表不展示 bucket/prefix；编辑表单展示配置 bucket/prefix
  （契约允许向有权限管理员返回 config），无预签名 URL、无对象下载入口。
- CSRF：非 GET 全部带 `X-CSRF-TOKEN`，403 仅刷新一次（`api.ts:266-336`）；没有 tenantId 参数（`api.test.ts:112`）。

### 3. a11y（组件级）

- 表单 label 与 `aria-label`：名称/Bucket/前缀/区域/扩展名/glob/默认可见范围/部门/凭据均有 label；
  Select/AutoComplete/Radio.Group 有 `aria-label`。
- 行按钮 `aria-label` 以可见文字开头，`autoInsertSpace={false}` 避免 antd 两字中文插空格；
  测试辅助 `expectLabelInName` 覆盖 6 个按钮（`index.test.tsx:77-83,557-566`）。
- Tag 对比度：antd 6 filled 绿色/橙色/金色 12px 低于 AA，页面用内联色阶 8/9 修正；组件测试计算
  相对亮度并断言 ≥4.5:1（`index.test.tsx:91-117,159-176`）。
- 375px：工具栏在 `max-width:575px` 换行，Table 用横向滚动承载宽表（`connectors.css:26-41`）。
- 未做真实浏览器 axe/焦点/键盘验证，见 F-003；antd Modal/Drawer 默认焦点陷阱未在浏览器确认。

### 4. 测试与覆盖率

- EV-0995：全量 `pnpm --filter @riccon/web test:coverage`，73 files / 640 tests 全通过，
  Statements 87.11% / Branches 80.32% / Functions 85.12% / Lines 86.98%，高于合同 80/70。
  新增 `pages/admin/connectors` 85.55/75.46、`services/connector` 84.4/79.28；`access.ts` 在 lcov 中 100%。
- EV-0998：verifier 定向 5 files / 58 tests 全通过，exit 0。
- 测试无 `.skip/.only/.todo`、无 retry、无 `--no-file-parallelism`；断言针对契约路径、请求体、错误码、
  权限文案、掩码、externalId 截断、202/409、删除处置与 label-in-name，不是空断言或坏状态快照。
- 未测：全量 E2E、真实浏览器 a11y、真实后端往返；见“未验证”。

### 5. 范围与证据

- `git diff --stat 96885ef..52e6d0f`：16 个文件，全部在 `apps/web/config/**`、`apps/web/src/pages/**`、
  `apps/web/src/services/**`、`apps/web/src/test/**` 内；无后端/contracts/docs/deploy/e2e 改动。
- `git diff --check 96885ef..52e6d0f`：无空白错误。
- EV-0995/0996：commit 均为 `381d7d1`（合并提交），dirty=false，exit 0；日志 SHA-256 与 EV JSON 一致
  （e800745a…、eac520bb…）。提示中的“EV-0996 commit=a518bf1”与实际 JSON 不符，实际为 381d7d1；
  以 EV JSON 为准，不影响结论。
- 复核时 HEAD 已前进到 `7ac5d3c`（TASK-0211 状态/证据提交），`381d7d1..7ac5d3c` 无 `apps/web` 变更，
  因此 EV-0995/0996 的 web 结论仍适用于当前代码；verifier 在 7ac5d3c 重新录制了 EV-0998/0999。

## 未验证

- **真实浏览器 E2E**：`e2e/stub/server.mjs` 没有连接器端点，stub TENANT_ADMIN 无
  `knowledge.connector.*` 权限，`e2e/tests/` 无 connector spec；本任务 write_scope 不含 e2e。
  无法验证列表→创建→测试→同步→条目→删除的完整旅程，也无法验证真实 API 往返。
- **axe/375px/键盘**：`pnpm -w a11y` 依赖 stub + `@a11y` spec，当前不会覆盖新页面；且并发 agent
  期间避免同时跑全量 E2E/a11y 套件。由 TASK-0212 V1b 与 M25 模块 gate 承接。
- **build at HEAD 7ac5d3c**：build 复用 EV-0996（381d7d1）；HEAD 后无 apps/web 变更，未重复 build。
- **模块级**：M25 全量 test/coverage/E2E/a11y/security/perf 与 M05b AC-09/10..14/19/20/22
  的连接器段，由模块 gate 与 V1b/V2b 验证；本任务不替代模块评审。
- **迁移/性能**：本任务不涉及，N/A。

## Findings

### F-001（P2，accepted）端点 allowlist 只能自由输入

- 观察：`connector-form-modal.tsx:325-343` 的 `AutoComplete` 允许任意 `endpointId` 文本；
  建议项仅来自当前页 connector（`index.tsx:252-255`），不是部署 `RICCON_CONNECTOR_ENDPOINTS`。
- 期望：任务 AC1“端点 allowlist 选择”；Product M05-AC-10（`docs/PRODUCT.md:560`）
  “从部署允许列表选择端点”“端点不可自由输入”。
- 影响：安全边界未削弱（服务端 `requireAllowedEndpoint` 400 且不出网；前端文案指向部署配置）；
  但 V1b 若按 AC 字面写 E2E-M05-04 会失败；产品形态与部署 allowlist 可能漂移。
- 最小建议：二选一——（A）新增只读端点目录（建议 `GET /api/v1/knowledge/connectors/endpoints`，
  `knowledge.connector.read`，只返回 `id/label/production`，不返回 URL，UI 改 Select，补契约/stub/E2E）；
  （B）修订 AC/E2E 为“填写部署提供的 endpointId，服务端拒绝非 allowlist”。需 Product/Lead 在
  M25 gate 前决策。
- Owner/due：见 front matter；未决策前 M05-AC-10 不得标无条件 pass。

### F-002（P2，accepted）未提交 E2E stub 钩子

- 观察：`e2e/stub/server.mjs:227-229` 的 TENANT_ADMIN 权限无 `knowledge.connector.*`；
  stub 无连接器端点；`e2e/tests/` 无 connector spec；builder 未改 e2e。
- 期望：任务 AC2“E2E stub 钩子”。
- 影响：本任务无法形成可执行 E2E 旅程；模块 gate 需要 E2E 证据。
- 处置：TASK-0212 V1b（scope `e2e/**`）承接；启动时先补 stub 权限/端点与 connector spec。
- 该残余是任务卡 AC 与 write_scope 的冲突，不是生产缺陷；不影响组件/API 层结论。

### F-003（P2，accepted）真实浏览器 a11y 未验证

- 观察：组件测试只覆盖 Tag 对比度与 label-in-name；没有 axe、375px 页面级横向滚动、
  Modal/Drawer 焦点或键盘路径断言；`pnpm -w a11y` 当前不会覆盖新页面。
- 期望：任务 AC2“375px、键盘、axe 无 serious/critical”。
- 影响：存在未验证的 a11y 风险（宽表 `scroll.x=1600`、`min(...,100vw)` Modal/Drawer 在 375px 的表现）；
  未发现已知 serious/critical 违规。
- 处置：TASK-0212 V1b 在真实浏览器执行 axe + 375px + 键盘“打开连接器→测试→保存”，
  模块 gate 不得用组件测试替代。

## 验证表

| 命令/检查                                                                                                                          | 结果                                                    | 证据        |
| ---------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- | ----------- |
| `pnpm --filter @riccon/web exec vitest run src/pages/admin/connectors src/services/connector config/routes.test.ts --reporter=dot` | exit 0，5 files / 58 tests 全通过                       | EV-0998     |
| `pnpm --filter @riccon/web typecheck && pnpm --filter @riccon/web lint`                                                            | exit 0                                                  | EV-0999     |
| EV-0995 `pnpm --filter @riccon/web test:coverage`（381d7d1，dirty=false）                                                          | exit 0，73 files / 640 tests，87.11/80.32/86.98         | EV-0995     |
| EV-0996 `set -e; web typecheck; lint; build`（381d7d1，dirty=false）                                                               | exit 0，build 成功，connectors chunk 22.69 kB gzip      | EV-0996     |
| `git diff --check 96885ef..52e6d0f`                                                                                                | clean                                                   | 本评审      |
| `git diff --name-status 96885ef..52e6d0f`                                                                                          | 16 个 web 文件，无后端/契约/e2e/docs/deploy             | 本评审      |
| 契约/ADR/权限/凭据/DOM/URL 静态审查                                                                                                | 通过；残余 F-001/F-002/F-003                            | 本评审      |
| `pnpm -w a11y` / E2E                                                                                                               | 未执行（N/A：stub 无连接器支持，由 TASK-0212 V1b 承接） | F-002/F-003 |

## 剩余风险

1. **产品 AC 字面风险（F-001）**：若 V1b 按 M05-AC-10 的“从 allowlist 选择/不可自由输入”写 E2E
   而不先做产品/契约决策，E2E-M05-04 会失败；这是当前最需要在 M25 gate 前关闭的残余。
2. **E2E/a11y 空白（F-002/F-003）**：当前只有组件级验证；真实浏览器 375px、焦点、键盘路径与 axe
   尚未证明，宽表/抽屉/弹窗在窄屏的表现需要 V1b 实测。
3. **真实后端往返**：web 客户端用 fetch 替身验证了方法/路径/请求体；`test`/`sync` 的空 `{}` body 与后端
   `@PostMapping` 无 body 参数兼容（已核对 controller），但没有浏览器到真实 API 的端到端证据，留给 V1b。
4. **CSRF 重试语义（非本任务缺陷）**：`isCsrfError` 把任何 403 `RICCON-AUTH-403` 当 CSRF 刷新重试一次，
   与既有 audit 客户端一致；真实权限 403 会多一次请求，但不改变结果、不泄露数据，记为观察而非 finding。
5. **流程观察（非 finding）**：任务卡 `tools_used` 为空（本模块其他任务相同），未记录 Serena/CodeGraph
   使用或降级理由；属于既有流程债务，不影响本次结论。

## 建议

- **建议 Lead 标 done**（技术验收通过），但保留 F-001/F-002/F-003 的 owner/due；**不建议**在
  M25 gate 前把 M05-AC-10 标为无条件 pass，也不建议模块 gate 在没有 connector E2E/a11y 证据时通过。
- V1b 启动前：Product/Lead 先对 F-001 做二选一决策；TASK-0212 把 stub 连接器端点/权限、connector spec、
  axe/375px/键盘与真实 API 往返纳入验收。
- 本评审不修改任务状态、源码、测试或契约；仅新增本评审与 verifier 证据 EV-0998/EV-0999。
