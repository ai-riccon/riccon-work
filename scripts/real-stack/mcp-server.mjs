#!/usr/bin/env node
// 真实栈验证专用 MCP stdio server。
//
// 按 MCP 2024-11-05 的换行分隔 JSON-RPC 约定实现 initialize、ping、tools/list 与 tools/call；
// 它是 Worker 通过 mcp_server.command 启动的独立子进程，不是应用内 mock。
// 协议消息只写 stdout，日志只写 stderr，避免污染 stdin/stdout。

import process from "node:process";
import { createInterface } from "node:readline";

const TOOL_NAME = "market_data";
const PROTOCOL_VERSION = "2024-11-05";

/**
 * 返回工具结果；内容为可读的市场数据文本，供报告生成阶段引用。
 */
function toolResult(argumentsValue) {
  const query = argumentsValue?.query ?? "未提供查询";
  return {
    content: [
      {
        type: "text",
        text: [
          `市场数据查询：${query}`,
          "2025 年中国企业协同办公市场规模约 486 亿元，同比增长 18.4%。",
          "2026 年预计规模约 574 亿元，增速 18.1%。",
          "头部三家厂商合计份额约 41%，平台化与私有化部署是主要竞争维度。",
        ].join("\n"),
      },
    ],
    isError: false,
  };
}

/**
 * 处理单条 JSON-RPC 请求；通知（无 id）不响应。
 */
function respond(request) {
  const id = request?.id;
  if (id === undefined || id === null) {
    return null;
  }
  const method = request?.method;
  const result = (() => {
    switch (method) {
      case "initialize":
        return {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: { tools: {} },
          serverInfo: { name: "riccon-real-mcp", version: "1.0.0" },
        };
      case "ping":
        return {};
      case "tools/list":
        return {
          tools: [
            {
              name: TOOL_NAME,
              description: "只读市场数据查询工具（验证用）",
              inputSchema: {
                type: "object",
                properties: {
                  query: { type: "string", description: "查询主题" },
                },
                required: ["query"],
              },
            },
          ],
        };
      case "tools/call":
        return toolResult(request?.params?.arguments);
      default:
        return {
          __error: { code: -32601, message: `Method not found: ${method}` },
        };
    }
  })();
  if (result?.__error) {
    return { jsonrpc: "2.0", id, error: result.__error };
  }
  return { jsonrpc: "2.0", id, result };
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });

input.on("line", (line) => {
  if (!line.trim()) {
    return;
  }
  let request;
  try {
    request = JSON.parse(line);
  } catch (error) {
    process.stderr.write(`mcp-server: invalid json: ${error.message}\n`);
    return;
  }
  const response = respond(request);
  if (response) {
    process.stdout.write(`${JSON.stringify(response)}\n`);
  }
});

input.on("close", () => {
  process.exit(0);
});

process.stderr.write("mcp-server: ready\n");
