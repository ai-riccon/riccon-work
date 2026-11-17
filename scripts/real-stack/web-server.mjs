#!/usr/bin/env node
// 真实栈 Web 服务：静态托管生产构建，并把同源 `/api` 反向代理到真实 API。
//
// 用途：生产构建 + 同源反向代理是 M02 的真实部署形态；这里不引入任何演示适配层。
// 代理保留浏览器 Host（不做 changeOrigin），使 API 的同源校验与浏览器实际 Origin 一致。

import http from "node:http";
import { createReadStream, existsSync, statSync } from "node:fs";
import path from "node:path";

const port = Number(process.env.REAL_WEB_PORT ?? 8092);
const host = process.env.REAL_WEB_HOST ?? "127.0.0.1";
const distDir = path.resolve(process.env.REAL_WEB_DIST ?? "apps/web/dist");
const apiHost = process.env.REAL_API_HOST ?? "127.0.0.1";
const apiPort = Number(process.env.REAL_API_PORT ?? 18080);

const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".map": "application/json; charset=utf-8",
};

/**
 * 代理 API 请求；保留原始 Host、Cookie 与流式响应。
 */
function proxyApi(req, res) {
  const upstream = http.request(
    {
      host: apiHost,
      port: apiPort,
      method: req.method,
      path: req.url,
      headers: req.headers,
    },
    (upstreamResponse) => {
      res.writeHead(
        upstreamResponse.statusCode ?? 502,
        upstreamResponse.headers,
      );
      upstreamResponse.pipe(res);
    },
  );
  upstream.on("error", (error) => {
    console.error(`api proxy error: ${error.message}`);
    if (!res.headersSent) {
      res.writeHead(502, { "content-type": "application/json" });
      res.end(
        JSON.stringify({
          code: "REAL_STACK_PROXY_ERROR",
          message: "API unavailable",
        }),
      );
    } else {
      res.end();
    }
  });
  req.pipe(upstream);
}

/**
 * 返回静态文件；未知前端路由回退到 index.html。
 */
function serveStatic(req, res) {
  const url = new URL(req.url ?? "/", `http://${req.headers.host}`);
  let filePath = path.join(distDir, decodeURIComponent(url.pathname));
  if (!filePath.startsWith(distDir)) {
    res.writeHead(403);
    res.end("forbidden");
    return;
  }
  if (!existsSync(filePath) || statSync(filePath).isDirectory()) {
    const candidate = path.join(filePath, "index.html");
    filePath = existsSync(candidate)
      ? candidate
      : path.join(distDir, "index.html");
  }
  if (!existsSync(filePath)) {
    res.writeHead(404);
    res.end("not found");
    return;
  }
  const ext = path.extname(filePath).toLowerCase();
  res.writeHead(200, {
    "content-type": CONTENT_TYPES[ext] ?? "application/octet-stream",
    "cache-control": ext === ".html" ? "no-store" : "public, max-age=300",
    // 禁止压缩，保证 SSE 不被中间层缓冲。
    "content-encoding": "identity",
  });
  createReadStream(filePath).pipe(res);
}

const server = http.createServer((req, res) => {
  if (req.url?.startsWith("/api/")) {
    proxyApi(req, res);
    return;
  }
  serveStatic(req, res);
});

server.listen(port, host, () => {
  console.log(
    `real web listening on http://${host}:${port} -> api ${apiHost}:${apiPort}`,
  );
});
