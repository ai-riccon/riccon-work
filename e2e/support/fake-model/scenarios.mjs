// 假 OpenAI 兼容模型的确定性场景定义。
//
// 该文件是 B8 的唯一场景事实源：`scripts/real-stack/fake-model.mjs`（真实栈）与
// `e2e/support/fake-model/server.mjs`（B11a/B11b 可复用）都从这里取场景表。
//
// 确定性约束（ADR-0021 §11）：
// - 固定正文、固定 usage、固定错误码、固定 HTTP 状态；不使用随机失败；
// - 场景由控制端点、请求头或 API key 选择，同一输入永远得到同一输出；
// - 本模块不发起任何出网请求，只在本机回环监听。
//
// 场景覆盖 M04 降级分类（ADR-0021 §5.1）：
// 302 / 401 / 429 / 5xx / 首字前超时 / 首字后断流 / 缺 usage / 无效 JSON。
// 另外保留 J2 工具阶段（`tool_call`）与 M02 的 `ok`/`slow`/`error` 兼容别名。

/** 固定 usage；所有成功流式响应都返回同一组 token 数，便于成本断言复现。 */
export const FIXED_USAGE = Object.freeze({
  prompt_tokens: 128,
  completion_tokens: 96,
  total_tokens: 224,
});

/** 固定报告正文；分片后按固定顺序发送。 */
export const REPORT_TEXT = [
  '## 市场规模与增长\n\n围绕演示主题，2025—2026 年市场保持两位数增长，头部厂商集中度上升。',
  '\n\n## 竞争格局\n\n第一梯队厂商依靠平台化能力扩大份额，第二梯队通过行业解决方案维持差异。',
  '\n\n## 结论与建议\n\n建议优先验证企业协同与知识管理场景，并以可审计的 Agent 工作流作为差异化能力。',
].join('');

/** 固定错误正文中的 message；不包含 provider、key、baseUrl 或堆栈。 */
const ERROR_MESSAGES = Object.freeze({
  redirect: 'fake model redirect',
  unauthorized: 'fake model unauthorized',
  rate_limited: 'fake model rate limited',
  server_error: 'fake model server error',
  unavailable: 'fake model unavailable',
  timeout: 'fake model timeout',
  stream_break: 'fake model stream interrupted',
  missing_usage: 'fake model stream completed without usage',
  invalid_json: 'fake model invalid response',
});

/**
 * 场景表。`kind` 决定响应方式：
 * - `stream`：正常 SSE 文本流；
 * - `http_error`：固定状态码 JSON 错误；
 * - `redirect`：固定 302，不跟随；
 * - `timeout`：不写响应头，保持连接直到客户端超时或 hold 到期；
 * - `stream_break`：先发送固定分片再断开连接；
 * - `invalid_json`：SSE 帧中的 JSON 非法。
 */
export const SCENARIOS = Object.freeze({
  ok: Object.freeze({ name: 'ok', kind: 'stream', status: 200, errorCode: null }),
  slow: Object.freeze({ name: 'slow', kind: 'stream', status: 200, errorCode: null, slow: true }),
  redirect: Object.freeze({
    name: 'redirect',
    kind: 'redirect',
    status: 302,
    errorCode: 'fake_redirect',
  }),
  unauthorized: Object.freeze({
    name: 'unauthorized',
    kind: 'http_error',
    status: 401,
    errorCode: 'fake_unauthorized',
  }),
  rate_limited: Object.freeze({
    name: 'rate_limited',
    kind: 'http_error',
    status: 429,
    errorCode: 'fake_rate_limited',
    retryAfter: '1',
  }),
  server_error: Object.freeze({
    name: 'server_error',
    kind: 'http_error',
    status: 500,
    errorCode: 'fake_server_error',
  }),
  unavailable: Object.freeze({
    name: 'unavailable',
    kind: 'http_error',
    status: 503,
    errorCode: 'fake_unavailable',
  }),
  timeout: Object.freeze({
    name: 'timeout',
    kind: 'timeout',
    status: null,
    errorCode: 'fake_timeout',
  }),
  stream_break: Object.freeze({
    name: 'stream_break',
    kind: 'stream_break',
    status: 200,
    errorCode: 'fake_stream_break',
  }),
  missing_usage: Object.freeze({
    name: 'missing_usage',
    kind: 'stream',
    status: 200,
    errorCode: 'fake_missing_usage',
    omitUsage: true,
  }),
  invalid_json: Object.freeze({
    name: 'invalid_json',
    kind: 'invalid_json',
    status: 200,
    errorCode: 'fake_invalid_json',
  }),
  tool_call: Object.freeze({
    name: 'tool_call',
    kind: 'stream',
    status: 200,
    errorCode: null,
    toolCall: true,
  }),
});

/** 旧 M02 模式到新场景名的兼容映射。 */
export const LEGACY_MODE_ALIASES = Object.freeze({
  ok: 'ok',
  slow: 'slow',
  error: 'server_error',
});

/** 场景名列表；控制端点与自检输出使用稳定顺序。 */
export const SCENARIO_NAMES = Object.freeze(Object.keys(SCENARIOS));

/** 允许通过控制端点设置的名称（含旧别名）。 */
export const SCENARIO_ALIASES = Object.freeze({
  ...Object.fromEntries(SCENARIO_NAMES.map((name) => [name, name])),
  ...LEGACY_MODE_ALIASES,
});

/**
 * 把外部输入解析为规范场景名；未知值返回 null，由调用方决定回退。
 *
 * @param {unknown} value 场景名或旧模式
 * @returns {string|null} 规范场景名
 */
export function normalizeScenario(value) {
  if (typeof value !== 'string') {
    return null;
  }
  const key = value.trim().toLowerCase();
  return Object.hasOwn(SCENARIO_ALIASES, key) ? SCENARIO_ALIASES[key] : null;
}

/**
 * 返回场景的固定错误正文。
 *
 * @param {string} scenario 规范场景名
 * @returns {{error: {message: string, type: string, code: string}}} 错误 JSON
 */
export function errorBody(scenario) {
  const message = ERROR_MESSAGES[scenario] ?? 'fake model failure';
  const type = scenario === 'unauthorized' ? 'invalid_request_error' : 'server_error';
  return { error: { message, type, code: `fake_${scenario}` } };
}

/**
 * 计算固定分片数；保持分片边界稳定，内容不随请求变化。
 *
 * @param {number} chunkCount 期望分片数
 * @returns {string[]} 固定分片
 */
export function fixedChunks(chunkCount) {
  const safeCount = Number.isInteger(chunkCount) && chunkCount > 0 ? chunkCount : 18;
  const size = Math.max(1, Math.ceil(REPORT_TEXT.length / safeCount));
  const chunks = [];
  for (let index = 0; index < REPORT_TEXT.length; index += size) {
    chunks.push(REPORT_TEXT.slice(index, index + size));
  }
  return chunks;
}
