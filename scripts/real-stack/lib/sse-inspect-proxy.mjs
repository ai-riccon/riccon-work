#!/usr/bin/env node
// 真实栈诊断代理：记录 API → Worker 响应分块到达时间，用于判断 SSE 是否逐事件 flush。
//
// 只用于验证，不改变请求内容；默认监听 18082，转发到 18081。

import http from "node:http";

const listenPort = Number(process.env.PROXY_PORT ?? 18082);
const targetPort = Number(process.env.PROXY_TARGET_PORT ?? 18081);
const targetHost = process.env.PROXY_TARGET_HOST ?? "127.0.0.1";

const server = http.createServer((req, res) => {
  const startedAt = Date.now();
  const chunks = [];
  console.log(`[${new Date().toISOString()}] request ${req.method} ${req.url}`);
  const upstream = http.request(
    {
      host: targetHost,
      port: targetPort,
      method: req.method,
      path: req.url,
      headers: { ...req.headers, host: `${targetHost}:${targetPort}` },
    },
    (upstreamResponse) => {
      res.writeHead(
        upstreamResponse.statusCode ?? 502,
        upstreamResponse.headers,
      );
      upstreamResponse.on("data", (chunk) => {
        const t = Date.now() - startedAt;
        chunks.push({
          t,
          bytes: chunk.length,
          preview: chunk.toString("utf8").slice(0, 80).replaceAll("\n", "\\n"),
        });
        console.log(
          `[chunk t=${t}ms bytes=${chunk.length}] ${chunk.toString("utf8").slice(0, 80).replaceAll("\n", "\\n")}`,
        );
        res.write(chunk);
      });
      upstreamResponse.on("end", () => {
        console.log(
          `[end t=${Date.now() - startedAt}ms chunks=${chunks.length}]`,
        );
        res.end();
      });
    },
  );
  upstream.on("error", (error) => {
    console.error("upstream error", error.message);
    res.writeHead(502);
    res.end();
  });
  req.pipe(upstream);
});

server.listen(listenPort, "127.0.0.1", () => {
  console.log(
    `sse inspect proxy listening on ${listenPort} -> ${targetHost}:${targetPort}`,
  );
});
