# TASK-0137 被替代证据

- EV-0667：perf 矩阵，clean，但录制于测量口径修正前（`m04_api_ms` 仍包含 workspace/会话创建等
  setup 请求，且聊天 SSE TTFB 曾尝试计入 API p95）。
- 权威证据：`.riccon/evidence/TASK-0137/EV-0668.json`（commit `57fb682`，认证/setup 已排除，
  聊天 TTFB 单独记录；PERF-M04-002 通过，PERF-M04-001 first_token API p95 506.499ms 仍超预算）。
