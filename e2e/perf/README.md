# M04 性能冒烟（k6）

`mvc-smoke.js` 覆盖 `PERF-M04-001/002`：50 并发首字、首字前降级矩阵、配额争用零超卖、成本查询。
脚本本身只读 API，不修改生产数据；配额场景会在测试栈内临时打开开关并设置租户/用户限额，结束后恢复原值。

## 推荐入口

```bash
# 一次性 MySQL 8.4.13 + 假模型 + API/Worker；不启动 OpenSearch/Web（主机规格另计）
node scripts/real-stack/run.mjs --mode=perf --perf-modes=server_error,rate_limited,unauthorized,timeout
```

`run.mjs --mode=perf` 会：

1. 只启动 compose 的 `mysql` 服务（OpenSearch/RustFS 不启动，报告记录 `opensearch: excluded`）；
2. 生成同一份 DEMO/端点/主密钥 env 注入 API 与 Worker，并设置
   `RICCON_CHAT_MAX_STREAMS=50`、`RICCON_DB_POOL_SIZE=50`、`RICCON_FAKE_MODEL=1`；
3. 用 `seed.mjs --mode=perf` 创建账号与 M04 目录（幂等，不含明文密钥）；
4. 对每个降级模式重设 fake provider key 场景，依次运行 `first_token`、`failover`、`quota`、`cost`；
5. 汇总 `perf-m04-matrix.json`，任一 k6 阈值失败即非零。

## 直接运行 k6

必须显式提供环境快照，否则脚本直接失败，避免把默认 pool/stream 当成 perf 结果：

```bash
k6 run \
  -e PERF_M04_ENV_JSON='{"chatMaxStreams":50,"dbPoolSize":50,"fakeModel":1,"host":{"cpus":8,"memoryBytes":17179869184},"images":{"mysql":"mysql:8.4.13"},"k6Version":"k6 v2.4.0","scriptSha256":"<sha256>","startCommand":"..."}' \
  -e PERF_M04_BASE_URL=http://127.0.0.1:18080 \
  -e PERF_M04_SCENARIO=first_token \
  -e PERF_M04_WORKSPACE_ID=<工作空间 ID> \
  e2e/perf/mvc-smoke.js
```

场景变量：

| 变量                    | 说明                                                                                                   |
| ----------------------- | ------------------------------------------------------------------------------------------------------ |
| `PERF_M04_SCENARIO`     | `first_token`（默认）/ `failover` / `quota` / `cost`                                                   |
| `PERF_M04_FAILURE_MODE` | `failover` 的故障类型：`unauthorized`/`rate_limited`/`server_error`/`unavailable`/`timeout`/`redirect` |
| `PERF_M04_REPORT_DIR`   | 报告目录；默认当前目录                                                                                 |
| `PERF_M04_REPORT_FILE`  | 报告文件覆盖；默认 `mvc-smoke-<scenario>[-<mode>].json`                                                |

直接运行 `failover` 时，需要先通过 fake provider 控制端点 `POST /__keys__` 把主 key 的场景设为
`PERF_M04_FAILURE_MODE`、备用 key 设为 `ok`（`run.mjs` 会自动完成并重置 key 健康状态）。

## 判定与记录

- 首字延迟取运行详情 `modelInvocations[].firstTokenMs`（服务端首字，不是整段 SSE 时长）；
- `m04_api_ms` 只统计稳态 JSON API：`GET /runs`、成本查询；登录（bcrypt）、workspace 列表、
  会话创建、开关/配额 setup 与 cleanup 不计入，阈值仍为 p95 ≤ 500ms；
- 聊天提交是 SSE 端点：TTFB 记入 `m04_chat_submit_ttfb_ms`，完整流记入 `m04_chat_stream_ms`，
  由首字 p95 ≤ 3s 预算约束，不并入 500ms 的 JSON API 预算；
- 其他阈值：首字 p95 ≤ 3s、降级成功率 > 99%、配额 1/49、成本查询 p95 ≤ 2s；
- **perf profile 连接池为 50**（`dbPoolSize=50`，`dbPoolFloor=25`）；ADR 只要求容量 ≥25，
  50 是性能验证 profile，不是生产默认承诺，生产池大小仍由部署配置决定；
- `handleSummary` 输出 host CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令、OpenSearch 是否启动；
- 降级矩阵按“模式 × 一次 k6 运行”执行：每次运行由 `run.mjs` 重设 fake provider key 场景与 key 健康，
  避免 401 失效/冷却让后续请求跳过故障 key，从而把降级跑成“没有降级”。

## 边界

- `quota` 场景需要 B6 配额端点；`cost` 场景需要 B7 成本端点。缺失时脚本非零失败并打印明确原因，
  不静默跳过；
- 不启动 OpenSearch 时，Chat 检索走 SQL（`knowledge_chunk`），不依赖 OpenSearch；若改用知识库索引阶段，
  需要另计主机规格；
- 报告与日志不得包含 API key、主密钥或 pepper；fake provider 的 key 只来自 `REAL_MODEL_KEYS` 环境变量。
