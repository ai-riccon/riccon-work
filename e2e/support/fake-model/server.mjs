// 可复用的假 OpenAI 兼容模型 HTTP 服务。
//
// 只依赖 Node 内置模块；不读取生产配置、不发起出网请求。真实栈脚本与 B11a/B11b
// 可分别用 `startFakeModelServer` 启动独立进程或同进程服务。
//
// 协议：`POST <任意前缀>/chat/completions`，返回 SSE 文本增量；J2 工具阶段返回 tool_call。
// 控制端点（测试专用，不属于产品契约）：
// - GET  /__health__   就绪与当前场景
// - GET  /__log__      请求时间线与固定 usage（不含 Authorization 明文）
// - POST /__mode__     旧 M02 兼容：{mode:"ok"|"slow"|"error"}
// - POST /__scenario__ 队列场景：{scenario,count}，按到达顺序消费
// - POST /__keys__     key 维度：{keys:{apiKey:"scenario"}}；不回声 key
// - POST /__reset__    清空队列、日志与场景覆盖
//
// 场景选择优先级（ADR-0021 §11 确定性要求）：
// 请求头 x-fake-model-scenario > API key 映射 > 队列 > 全局场景。

import http from 'node:http';

import {
  FIXED_USAGE,
  SCENARIOS,
  SCENARIO_NAMES,
  errorBody,
  fixedChunks,
  normalizeScenario,
} from './scenarios.mjs';

/** 无法从请求解析主题时的固定兜底文本；不随请求变化。 */
const DEFAULT_TOPIC = '2026 年中国企业协同办公市场';

/** 工具名与 J2 契约保持一致。 */
const MARKET_DATA_TOOL = 'market_data';

/**
 * 从 Authorization 头提取 Bearer key。
 *
 * @param {string|undefined} header Authorization 头
 * @returns {string|null} key 或 null
 */
function bearerKey(header) {
  if (typeof header !== 'string') {
    return null;
  }
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

/**
 * 只保留 key 末 4 位，用于日志与断言；日志不得出现明文 key。
 *
 * @param {string} key 明文 key
 * @returns {string} 掩码
 */
function maskKey(key) {
  return key.length <= 4 ? '****' : `****${key.slice(-4)}`;
}

/**
 * 从请求消息中提取调研主题；只用于生成固定报告文本，不影响场景。
 *
 * @param {object} body 请求体
 * @returns {string} 主题
 */
function extractTopic(body) {
  const messages = Array.isArray(body?.messages) ? body.messages : [];
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const text = typeof messages[index]?.content === 'string' ? messages[index].content : '';
    const match = text.match(/调研主题：([^\n]+)/);
    if (match) {
      return match[1].trim();
    }
    if (text.trim()) {
      return text.trim().slice(0, 80);
    }
  }
  return DEFAULT_TOPIC;
}

/**
 * 判断是否 J2 工具阶段。
 *
 * @param {object} body 请求体
 * @returns {boolean} 是否含 market_data 工具
 */
function isToolStage(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : [];
  return tools.some((tool) => tool?.function?.name === MARKET_DATA_TOOL);
}

/**
 * 发送一帧 SSE data。
 *
 * @param {http.ServerResponse} res 响应
 * @param {object} payload JSON 载荷
 */
function sendEvent(res, payload) {
  if (!res.destroyed) {
    res.write(`data: ${JSON.stringify(payload)}\n\n`);
  }
}

/**
 * 可中断等待；响应已关闭时立即返回。
 *
 * @param {number} ms 毫秒
 * @param {() => boolean} aborted 是否已中断
 * @returns {Promise<void>}
 */
async function sleep(ms, aborted) {
  if (ms <= 0 || aborted()) {
    return;
  }
  const step = Math.min(50, Math.max(10, Math.floor(ms / 4)));
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (aborted()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, Math.min(step, deadline - Date.now())));
  }
}

/**
 * 创建假模型服务（未监听）。
 *
 * @param {object} [options] 覆盖项
 * @param {string} [options.host] 监听地址
 * @param {number} [options.port] 端口；0 表示随机
 * @param {string} [options.globalScenario] 初始全局场景
 * @param {Record<string,string>} [options.keyScenarios] 初始 key → 场景
 * @param {number} [options.firstDelayMs] 首个增量前延迟
 * @param {number} [options.chunkIntervalMs] 增量间隔
 * @param {number} [options.chunkCount] 增量数量
 * @param {number} [options.slowIntervalMs] slow 场景的额外增量间隔
 * @param {number} [options.timeoutHoldMs] timeout 场景保持连接时长
 * @param {boolean} [options.logBodies] 是否记录请求体（默认 false）
 * @returns {{server: http.Server, control: object}} 服务与控制句柄
 */
/** M06 重试 E2E 的一次性故障标记；与真实业务提示词无关，只在验证专用假模型生效。 */
const WORKFLOW_RETRY_MARKER = '__WF_FAIL_3__';

/**
 * 计算提示词文本，用于识别 M06 重试夹具。
 *
 * @param {object} body 请求体
 * @returns {string} 所有消息文本的拼接
 */
function promptText(body) {
  const messages = Array.isArray(body?.messages) ? body.messages : [];
  return messages
    .map((message) => (typeof message?.content === 'string' ? message.content : ''))
    .join('\n');
}

export function createFakeModelServer(options = {}) {
  const config = {
    host: options.host ?? '127.0.0.1',
    port: Number(options.port ?? 18990),
    globalScenario: normalizeScenario(options.globalScenario ?? 'ok') ?? 'ok',
    firstDelayMs: Number(options.firstDelayMs ?? 60),
    chunkIntervalMs: Number(options.chunkIntervalMs ?? 180),
    chunkCount: Number(options.chunkCount ?? 18),
    slowIntervalMs: Number(options.slowIntervalMs ?? 900),
    timeoutHoldMs: Number(options.timeoutHoldMs ?? 15_000),
    logBodies: Boolean(options.logBodies),
  };

  /** 当前全局场景；控制端点可改。 */
  let globalScenario = config.globalScenario;

  /** key → 场景映射；控制端点可改。 */
  const keyScenarios = new Map();
  for (const [key, scenario] of Object.entries(options.keyScenarios ?? {})) {
    const normalized = normalizeScenario(scenario);
    if (normalized) {
      keyScenarios.set(key, normalized);
    }
  }

  /** 队列场景；按到达顺序消费。 */
  const queued = [];

  /** 请求时间线；不含 key 明文与请求体。 */
  const requestLog = [];

  /** 自增序号，便于断言请求顺序。 */
  let sequence = 0;

  /** M06 重试夹具：同一提示词的剩余故障次数；耗尽后恢复正常。 */
  const workflowRetryFailures = new Map();

  /**
   * 选择本次请求的场景。
   *
   * @param {http.IncomingMessage} req 请求
   * @returns {string} 规范场景名
   */
  function resolveScenario(req) {
    const fromHeader = normalizeScenario(req.headers['x-fake-model-scenario']);
    if (fromHeader) {
      return fromHeader;
    }
    const key = bearerKey(req.headers.authorization);
    if (key && keyScenarios.has(key)) {
      return keyScenarios.get(key);
    }
    if (queued.length > 0) {
      return queued.shift();
    }
    return globalScenario;
  }

  /**
   * 打开 SSE 响应头；显式禁用缓冲。
   *
   * @param {http.ServerResponse} res 响应
   */
  function openStream(res) {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
      'x-fake-model-scenario': res.fakeScenario ?? 'unknown',
    });
    res.flushHeaders?.();
  }

  /**
   * 发送固定 tool_call 流。
   *
   * @param {http.ServerResponse} res 响应
   * @param {object} body 请求体
   * @param {object} log 请求日志
   */
  function streamToolCall(res, body, log) {
    const callId = 'call_fake_market_data';
    sendEvent(res, {
      id: 'chatcmpl-fake-tool',
      object: 'chat.completion.chunk',
      choices: [
        {
          index: 0,
          delta: {
            role: 'assistant',
            tool_calls: [
              {
                index: 0,
                id: callId,
                type: 'function',
                function: {
                  name: MARKET_DATA_TOOL,
                  arguments: JSON.stringify({ query: extractTopic(body) }),
                },
              },
            ],
          },
          finish_reason: null,
        },
      ],
    });
    sendEvent(res, {
      id: 'chatcmpl-fake-tool',
      object: 'chat.completion.chunk',
      choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }],
    });
    res.write('data: [DONE]\n\n');
    res.end();
    log.status = 200;
    log.chunks = 1;
    log.finishedAt = new Date().toISOString();
  }

  /**
   * 发送固定文本流；按场景省略 usage 或中断连接。
   *
   * @param {http.ServerResponse} res 响应
   * @param {object} body 请求体
   * @param {object} scenario 场景
   * @param {object} log 请求日志
   * @param {() => boolean} aborted 中断判断
   * @returns {Promise<void>}
   */
  async function streamText(res, body, scenario, log, aborted) {
    const topic = extractTopic(body);
    const text = fixedChunks(config.chunkCount);
    log.topic = topic;
    openStream(res);
    sendEvent(res, {
      id: 'chatcmpl-fake-text',
      object: 'chat.completion.chunk',
      choices: [{ index: 0, delta: { role: 'assistant', content: '' }, finish_reason: null }],
    });
    const firstDelay = scenario.slow
      ? config.firstDelayMs + config.slowIntervalMs
      : config.firstDelayMs;
    await sleep(firstDelay, aborted);
    for (let index = 0; index < text.length; index += 1) {
      if (aborted()) {
        log.aborted = true;
        return;
      }
      if (index === 0) {
        log.firstEventAt = new Date().toISOString();
      }
      sendEvent(res, {
        id: 'chatcmpl-fake-text',
        object: 'chat.completion.chunk',
        choices: [{ index: 0, delta: { content: text[index] }, finish_reason: null }],
      });
      log.chunks = index + 1;
      if (scenario.kind === 'stream_break' && index + 1 >= Math.min(3, text.length)) {
        // 首字后断流：不发 finish/usage/DONE，直接断开，触发 INTERRUPTED 语义。
        log.status = 200;
        log.interrupted = true;
        log.finishedAt = new Date().toISOString();
        res.destroy();
        return;
      }
      if (index < text.length - 1) {
        const interval = scenario.slow
          ? config.chunkIntervalMs + config.slowIntervalMs
          : config.chunkIntervalMs;
        await sleep(interval, aborted);
      }
    }
    if (aborted()) {
      log.aborted = true;
      return;
    }
    const finalChunk = {
      id: 'chatcmpl-fake-text',
      object: 'chat.completion.chunk',
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
    };
    if (!scenario.omitUsage) {
      finalChunk.usage = { ...FIXED_USAGE };
      log.usage = { ...FIXED_USAGE };
    }
    sendEvent(res, finalChunk);
    res.write('data: [DONE]\n\n');
    log.status = 200;
    log.finishedAt = new Date().toISOString();
    res.end();
  }

  /**
   * 固定错误响应。
   *
   * @param {http.ServerResponse} res 响应
   * @param {object} scenario 场景
   * @param {object} log 请求日志
   */
  function writeHttpError(res, scenario, log) {
    const payload = JSON.stringify(errorBody(scenario.name));
    const headers = {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(payload),
      'x-fake-model-scenario': scenario.name,
    };
    if (scenario.retryAfter) {
      headers['retry-after'] = scenario.retryAfter;
    }
    res.writeHead(scenario.status, headers);
    res.end(payload);
    log.status = scenario.status;
    log.errorCode = scenario.errorCode;
    log.finishedAt = new Date().toISOString();
  }

  /**
   * timeout 场景：不写响应头，保持连接；客户端超时或 hold 到期为止。
   *
   * @param {http.ServerResponse} res 响应
   * @param {object} scenario 场景
   * @param {object} log 请求日志
   */
  function holdConnection(res, scenario, log) {
    let done = false;
    const finish = (status, errorCode) => {
      if (done) {
        return;
      }
      done = true;
      log.status = status;
      log.errorCode = errorCode;
      log.finishedAt = new Date().toISOString();
      if (!res.destroyed) {
        res.writeHead(504, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(errorBody(scenario.name)));
      }
    };
    res.on('close', () => finish(499, scenario.errorCode));
    setTimeout(() => finish(504, scenario.errorCode), config.timeoutHoldMs);
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(
      req.url ?? '/',
      `http://${req.headers.host ?? `${config.host}:${config.port}`}`,
    );
    const method = req.method ?? 'GET';
    const pathname = url.pathname;

    // ---------- 控制端点（测试专用） ----------
    if (pathname === '/__health__' && method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(
        JSON.stringify({
          status: 'ok',
          scenarios: SCENARIO_NAMES,
          globalScenario,
          keyScenarios: [...keyScenarios.keys()].map(maskKey),
          queued: queued.length,
          requests: requestLog.length,
        }),
      );
      return;
    }
    if (pathname === '/__log__' && method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ requests: requestLog }));
      return;
    }
    if (pathname === '/__reset__' && method === 'POST') {
      queued.length = 0;
      requestLog.length = 0;
      sequence = 0;
      keyScenarios.clear();
      globalScenario = config.globalScenario;
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: true, globalScenario }));
      return;
    }
    if (pathname === '/__mode__' && method === 'POST') {
      const body = JSON.parse((await readRaw(req)) || '{}');
      const normalized = normalizeScenario(body.mode);
      if (!normalized) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'unknown mode' }));
        return;
      }
      globalScenario = normalized;
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ mode: globalScenario }));
      return;
    }
    if (pathname === '/__scenario__' && method === 'POST') {
      const body = JSON.parse((await readRaw(req)) || '{}');
      const normalized = normalizeScenario(body.scenario);
      if (!normalized) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'unknown scenario' }));
        return;
      }
      const count = Number.isInteger(body.count) && body.count > 0 ? body.count : 1;
      for (let index = 0; index < count; index += 1) {
        queued.push(normalized);
      }
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ scenario: normalized, queued: queued.length }));
      return;
    }
    if (pathname === '/__keys__' && method === 'POST') {
      const body = JSON.parse((await readRaw(req)) || '{}');
      const mapping = body.keys && typeof body.keys === 'object' ? body.keys : {};
      for (const [key, scenario] of Object.entries(mapping)) {
        const normalized = normalizeScenario(scenario);
        if (!normalized) {
          res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: `unknown scenario for key ${maskKey(key)}` }));
          return;
        }
        keyScenarios.set(key, normalized);
      }
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ keys: [...keyScenarios.keys()].map(maskKey) }));
      return;
    }

    // ---------- 模型调用 ----------
    if (method !== 'POST' || !pathname.endsWith('/chat/completions')) {
      res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'not found' }));
      return;
    }

    let body = {};
    const raw = await readRaw(req);
    try {
      body = raw ? JSON.parse(raw) : {};
    } catch {
      res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'invalid json' }));
      return;
    }

    sequence += 1;
    const key = bearerKey(req.headers.authorization);
    const scenarioName = resolveScenario(req);
    const scenario = SCENARIOS[scenarioName];
    const log = {
      sequence,
      scenario: scenarioName,
      method,
      path: pathname,
      authorizationPresent: Boolean(key),
      keyMasked: key ? maskKey(key) : null,
      model: typeof body?.model === 'string' ? body.model : null,
      stream: body?.stream === true,
      status: null,
      errorCode: scenario.errorCode,
      chunks: 0,
      usage: null,
      topic: null,
      firstEventAt: null,
      finishedAt: null,
    };
    requestLog.push(log);
    res.fakeScenario = scenarioName;

    // M06 E2E-M06-05：提示词含标记时，同一提示词前 3 次返回 500（覆盖路由 3 次尝试），
    // 第 4 次起恢复；用于验证“节点失败 → 重试成功、上游不重跑”的真实链路。
    const prompt = promptText(body);
    if (prompt.includes(WORKFLOW_RETRY_MARKER)) {
      const remaining = workflowRetryFailures.has(prompt) ? workflowRetryFailures.get(prompt) : 3;
      if (remaining > 0) {
        workflowRetryFailures.set(prompt, remaining - 1);
        writeHttpError(res, SCENARIOS.server_error, log);
        return;
      }
    }

    if (scenario.kind === 'http_error' || scenario.kind === 'redirect') {
      if (scenario.kind === 'redirect') {
        res.writeHead(302, {
          location: '/v1/redirect-target',
          'x-fake-model-scenario': scenario.name,
        });
        res.end();
        log.status = 302;
        log.finishedAt = new Date().toISOString();
        return;
      }
      writeHttpError(res, scenario, log);
      return;
    }
    if (scenario.kind === 'timeout') {
      holdConnection(res, scenario, log);
      return;
    }
    if (scenario.kind === 'invalid_json') {
      openStream(res);
      res.write('data: {"error": this-is-not-json}\n\n');
      res.end();
      log.status = 200;
      log.errorCode = scenario.errorCode;
      log.finishedAt = new Date().toISOString();
      return;
    }
    if (
      scenario.toolCall ||
      (isToolStage(body) && scenario.kind === 'stream' && !scenario.omitUsage)
    ) {
      streamToolCall(res, body, log);
      return;
    }

    const aborted = () => res.destroyed || res.writableEnded;
    await streamText(res, body, scenario, log, aborted);
  });

  /** 已建立的连接；关闭时强制断开，避免 listen 测试挂住。 */
  const sockets = new Set();
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
  });

  const control = {
    /**
     * 设置全局场景。
     *
     * @param {string} scenario 场景名
     */
    setGlobalScenario(scenario) {
      const normalized = normalizeScenario(scenario);
      if (!normalized) {
        throw new Error(`unknown scenario: ${scenario}`);
      }
      globalScenario = normalized;
    },
    /**
     * 设置 key 维度场景。
     *
     * @param {Record<string,string>} mapping key → 场景
     */
    setKeyScenarios(mapping) {
      for (const [key, scenario] of Object.entries(mapping)) {
        const normalized = normalizeScenario(scenario);
        if (!normalized) {
          throw new Error(`unknown scenario: ${scenario}`);
        }
        keyScenarios.set(key, normalized);
      }
    },
    /** 清空日志、队列与覆盖。 */
    reset() {
      queued.length = 0;
      requestLog.length = 0;
      sequence = 0;
      keyScenarios.clear();
      workflowRetryFailures.clear();
      globalScenario = config.globalScenario;
    },
    /** 返回日志快照。 */
    log() {
      return requestLog.map((entry) => ({ ...entry }));
    },
  };

  return { server, control, config };
}

/**
 * 读取请求体。
 *
 * @param {http.IncomingMessage} req 请求
 * @returns {Promise<string>} UTF-8 文本
 */
function readRaw(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 8 * 1024 * 1024) {
        reject(new Error('request body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/**
 * 启动假模型服务并等待监听完成。
 *
 * @param {object} [options] 同 createFakeModelServer
 * @returns {Promise<{server: http.Server, control: object, port: number, url: string, close: () => Promise<void>}>}
 *   启动结果
 */
export async function startFakeModelServer(options = {}) {
  const { server, control } = createFakeModelServer(options);
  const host = options.host ?? '127.0.0.1';
  const port = Number(options.port ?? 18990);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      resolve();
    });
  });
  const actualPort = server.address().port;
  return {
    server,
    control,
    port: actualPort,
    url: `http://${host}:${actualPort}`,
    close: () =>
      new Promise((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections?.();
      }),
  };
}
