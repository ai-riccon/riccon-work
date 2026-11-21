# TASK-0137 被替代证据

- EV-0667：perf 矩阵，clean；测量口径修正前（`m04_api_ms` 仍含 setup，聊天 TTFB 曾尝试并入 API p95）。
- EV-0668：perf 矩阵，clean；测量口径已修正（认证/setup 排除），但 perf profile 连接池仍为 25，
  first_token API p95 506.499ms 超 500ms。
- 权威证据：`.riccon/evidence/TASK-0137/EV-0669.json`（commit `efabac9`，perf profile pool=50，
  矩阵 exit 0，PERF-M04-001/002 均 pass）。
