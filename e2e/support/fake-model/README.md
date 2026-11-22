# 假模型（B8 测试资产）

`scenarios.mjs` + `server.mjs` 是真实栈与 B11a/B11b 共用的假 OpenAI 兼容模型。它只依赖 Node
内置模块，不启动生产代码、不发起出网请求。

## 场景

| 场景            | 行为                                                      |
| --------------- | --------------------------------------------------------- |
| `ok`            | 固定正文 + 固定 usage（128/96/224），SSE 正常结束         |
| `slow`          | 固定正文，首字与增量更慢                                  |
| `redirect`      | 302，Location 固定，客户端不得跟随                        |
| `unauthorized`  | 401，`code=fake_unauthorized`                             |
| `rate_limited`  | 429 + `Retry-After: 1`，`code=fake_rate_limited`          |
| `server_error`  | 500，`code=fake_server_error`                             |
| `unavailable`   | 503，`code=fake_unavailable`                              |
| `timeout`       | 不写响应头，保持连接直到客户端超时或 `timeoutHoldMs` 到期 |
| `stream_break`  | 发送固定分片后断开，不发 `[DONE]`                         |
| `missing_usage` | 正常结束但不返回 usage                                    |
| `invalid_json`  | SSE 帧中的 JSON 非法                                      |
| `tool_call`     | 返回固定 `market_data` tool_call（J2 工具阶段）           |

旧别名 `ok`/`slow`/`error` 仍可用，`error` 映射到 `server_error`。

## 控制端点（测试专用）

| 端点            | 方法 | 用途                                               |
| --------------- | ---- | -------------------------------------------------- |
| `/__health__`   | GET  | 就绪、全局场景、key 掩码与队列长度                 |
| `/__log__`      | GET  | 请求时间线、固定 usage；不含 Authorization 明文    |
| `/__mode__`     | POST | 兼容旧模式：`{mode:"ok"\|"slow"\|"error"}`         |
| `/__scenario__` | POST | 队列场景：`{scenario,count}`，按到达顺序消费       |
| `/__keys__`     | POST | key 维度：`{keys:{apiKey:"scenario"}}`；不回声 key |
| `/__reset__`    | POST | 清空日志、队列与 key 覆盖                          |

场景优先级：请求头 `x-fake-model-scenario` > API key 映射 > 队列 > 全局场景。

## 用法

```js
import { startFakeModelServer } from './index.mjs';

const fake = await startFakeModelServer({ port: 0, globalScenario: 'ok' });
fake.control.setKeyScenarios({ 'bad-key': 'server_error' });
// ...请求 fake.url/v1/chat/completions，Authorization: Bearer bad-key
await fake.close();
```

真实栈 CLI：

```bash
node scripts/real-stack/fake-model.mjs --scenario ok
node scripts/real-stack/fake-model.mjs --self-test
```

`--self-test` 会验证全部场景、key 维度、超时中断、固定 usage 与请求可重复性；失败时退出码非零。
