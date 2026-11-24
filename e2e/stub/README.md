# E2E stub API

`e2e/stub/server.mjs` 是端到端测试专用的独立 HTTP 服务，**不是应用内 mock**。

## 边界

- 不进入 `apps/web` 源码或生产构建；`apps/web/scripts/check-prod-bundle.mjs` 会扫描
  `apps/web/dist`，命中 mock/stub 特征即失败。
- 只由 `e2e/playwright.config.ts` 的 `webServer` 启动，监听 `E2E_STUB_PORT`（默认 8124）。
- 浏览器侧使用同源 `/api`：Playwright 通过 `RICCON_E2E_API_TARGET` 让 Umi dev server
  把 `/api` 代理到 stub，因此 Cookie、CSRF、ProblemDetail 与 SSE 都走真实 `ApiClient`。
- 数据只保存在进程内存，每个用例开始由 `POST /__stub__/reset` 重置；用例之间不共享会话。
- 仅实现 M02 J1/J2、M03/M10 管理端、M04 模型网关与 M05/M05b 知识库使用的 OpenAPI 子集，
  数据全部为合成内容，不连接模型、MCP 或对象存储。

## 覆盖的契约子集

- 认证：`/auth/csrf`、`/auth/login`、`/auth/session`、`/auth/logout`（Cookie + `X-CSRF-TOKEN`）。
- J1：工作空间、会话、消息 SSE、文件 multipart 上传、解析轮询、解析重试、下载地址、运行详情
  （模型调用记录与首字/耗时/token/状态）。
- J2：报告任务创建/列表/详情/重试、MCP 工具列表、审批决定、产物列表/详情/版本/下载。
- SSE：`riccon.chat.started/delta/citation/completed/failed/stopped.v1`，停止与断线会落 STOPPED。
- M05b 连接器：`/knowledge/connectors` 列表/创建、`{id}` 部分更新/软删除、
  `{id}/credential`（只写掩码）、`{id}/test`（200 内成功/失败）、`{id}/sync`（202/409）、
  `{id}/items`（五态、末段 externalId）。TENANT_ADMIN 持有 `knowledge.connector.read/manage`；
  普通员工 403、平台管理员 404；数据由 `POST /__stub__/m05b-seed` 填充。

## 控制端点（测试专用，非产品契约）

| 端点                        | 方法 | 用途                                                                       |
| --------------------------- | ---- | -------------------------------------------------------------------------- |
| `/__stub__/health`          | GET  | Playwright `webServer` 就绪检查                                            |
| `/__stub__/reset`           | POST | 清空业务数据、会话与活动 SSE 运行                                          |
| `/__stub__/scenario`        | POST | 切换错误场景（如 `m05b-list-500`、`m05b-sync-409`、`m05b-test-fail-auth`） |
| `/__stub__/m05b-seed`       | POST | 播种连接器/条目/导入文档；`{"empty":true}` 只保留空列表                    |
| `/__stub__/revoke-sessions` | POST | 让服务端会话失效，验证 401 → 登录页                                        |

## 错误路径与 console 守卫

浏览器会把 4xx 响应记为 `console.error`。`e2e/tests/fixtures.ts` 默认只放行
`/api/v1/auth/session` 的探测型 401；错误凭据、会话失效、403、409 等负路径用例必须在
用例内显式调用 `consoleGuard.allowNetworkError(url, status)` 登记预期错误。
新增白名单必须绑定具体 URL 与状态码，禁止泛化放行。

## 本地运行

```bash
node e2e/stub/server.mjs
curl http://127.0.0.1:8124/__stub__/health
```

完整 E2E：`pnpm -w e2e`。
