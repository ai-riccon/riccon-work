#!/usr/bin/env node
// 真实栈验证专用假 OpenAI 兼容模型服务。
//
// 该服务是独立进程，不是应用内 mock，也不 import 生产代码；它只按 OpenAI chat/completions
// 的 SSE 协议返回确定性内容，并记录每个请求的到达/首字/结束时间，供验证者判断 SSE 是否被缓冲。
//
// 行为：
// - 请求 tools 中包含 market_data（J2 工具阶段）：返回一次 tool_call，触发 HITL 审批；
// - 其他请求（J1 问答、J2 生成阶段）：返回多段文本增量，首字立即发送，后续按 interval 发送。
//
// 控制端点（仅验证使用）：
// - GET /__health__：就绪检查
// - POST /__mode__：{"mode":"ok"|"slow"|"error"} 切换下一次调用的行为
// - GET /__log__：返回请求时间戳记录

import http from "node:http";

const port = Number(process.env.FAKE_MODEL_PORT ?? 18990);
const host = process.env.FAKE_MODEL_HOST ?? "127.0.0.1";
const firstDelayMs = Number(process.env.FAKE_MODEL_FIRST_DELAY_MS ?? 60);
const chunkIntervalMs = Number(process.env.FAKE_MODEL_CHUNK_INTERVAL_MS ?? 180);
const chunkCount = Number(process.env.FAKE_MODEL_CHUNK_COUNT ?? 18);

/** 当前模式；验证者可通过控制端点切换。 */
let mode = "ok";

/** 请求时间线；用于输出首字与结束时间戳。 */
const requestLog = [];

/**
 * 构造一段包含可引用关键词的报告正文。生成阶段会把它放进报告的“分析”小节。
 */
function reportBody(topic) {
  return [
    `## 市场规模与增长\n\n围绕“${topic}”，演示资料显示 2025—2026 年市场保持两位数增长，头部厂商集中度上升。`,
    `\n\n## 竞争格局\n\n第一梯队厂商依靠平台化能力扩大份额，第二梯队通过行业解决方案维持差异。`,
    `\n\n## 结论与建议\n\n建议优先验证企业协同与知识管理场景，并以可审计的 Agent 工作流作为差异化能力。`,
  ];
}

/**
 * 从请求消息中提取调研主题。
 */
function extractTopic(body) {
  const messages = Array.isArray(body?.messages) ? body.messages : [];
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const text =
      typeof messages[index]?.content === "string"
        ? messages[index].content
        : "";
    const match = text.match(/调研主题：([^\n]+)/);
    if (match) {
      return match[1].trim();
    }
  }
  return "2026 年中国企业协同办公市场";
}

/**
 * 判断是否为 J2 工具阶段：请求的 tools 里包含数据库授权的 MCP 工具。
 */
function isToolStage(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : [];
  return tools.some((tool) => tool?.function?.name === "market_data");
}

/**
 * 写一条 SSE data 帧；显式 flush，避免被 Nagle 或缓冲合并。
 */
function sendEvent(res, payload) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

/**
 * 返回 tool_call 流。首个 delta 带 id/name/arguments 分片，终止帧带 finish_reason。
 */
function streamToolCall(res, body) {
  const topic = extractTopic(body);
  const callId = `call_${Date.now()}`;
  sendEvent(res, {
    id: "chatcmpl-tool",
    object: "chat.completion.chunk",
    choices: [
      {
        index: 0,
        delta: {
          role: "assistant",
          tool_calls: [
            {
              index: 0,
              id: callId,
              type: "function",
              function: {
                name: "market_data",
                arguments: JSON.stringify({ query: topic }),
              },
            },
          ],
        },
        finish_reason: null,
      },
    ],
  });
  sendEvent(res, {
    id: "chatcmpl-tool",
    object: "chat.completion.chunk",
    choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }],
  });
  res.write("data: [DONE]\n\n");
  res.end();
}

/**
 * 流式输出文本；每段间隔 chunkIntervalMs，便于浏览器验证“逐字增长”和停止。
 */
async function streamText(res, body) {
  const topic = extractTopic(body);
  const text = reportBody(topic).join("");
  const chunks = [];
  const size = Math.max(1, Math.ceil(text.length / chunkCount));
  for (let index = 0; index < text.length; index += size) {
    chunks.push(text.slice(index, index + size));
  }
  const log = {
    startedAt: new Date().toISOString(),
    firstTokenAt: null,
    finishedAt: null,
    mode,
    topic,
    chunks: chunks.length,
  };
  requestLog.push(log);
  sendEvent(res, {
    id: "chatcmpl-text",
    object: "chat.completion.chunk",
    choices: [
      {
        index: 0,
        delta: { role: "assistant", content: "" },
        finish_reason: null,
      },
    ],
  });
  await new Promise((resolve) => setTimeout(resolve, firstDelayMs));
  for (let index = 0; index < chunks.length; index += 1) {
    if (index === 0) {
      log.firstTokenAt = new Date().toISOString();
    }
    sendEvent(res, {
      id: "chatcmpl-text",
      object: "chat.completion.chunk",
      choices: [
        { index: 0, delta: { content: chunks[index] }, finish_reason: null },
      ],
    });
    if (index < chunks.length - 1) {
      await new Promise((resolve) => setTimeout(resolve, chunkIntervalMs));
    }
  }
  sendEvent(res, {
    id: "chatcmpl-text",
    object: "chat.completion.chunk",
    choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
    usage: {
      prompt_tokens: 128,
      completion_tokens: Math.ceil(text.length / 4),
    },
  });
  log.finishedAt = new Date().toISOString();
  res.write("data: [DONE]\n\n");
  res.end();
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/__health__") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ status: "ok" }));
    return;
  }
  if (req.method === "GET" && req.url === "/__log__") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ requests: requestLog }));
    return;
  }
  if (req.method === "POST" && req.url === "/__mode__") {
    let raw = "";
    for await (const chunk of req) {
      raw += chunk;
    }
    try {
      const parsed = JSON.parse(raw);
      mode =
        parsed.mode === "slow" || parsed.mode === "error" ? parsed.mode : "ok";
    } catch {
      mode = "ok";
    }
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ mode }));
    return;
  }
  if (req.method !== "POST" || !req.url?.endsWith("/chat/completions")) {
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return;
  }
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
  }
  let body = {};
  try {
    body = JSON.parse(raw);
  } catch {
    res.writeHead(400, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: "invalid json" }));
    return;
  }
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-store",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  });
  res.flushHeaders();
  if (mode === "error") {
    res.write(
      'data: {"error":{"message":"fake model failure","type":"server_error"}}\n\n',
    );
    res.end();
    return;
  }
  if (isToolStage(body)) {
    streamToolCall(res, body);
    return;
  }
  await streamText(res, body);
});

server.listen(port, host, () => {
  console.log(`fake model listening on http://${host}:${port}`);
});
