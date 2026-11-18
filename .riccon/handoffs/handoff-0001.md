---
handoff_id: HANDOFF-0001
session: null
summary: M01 工程基线与 M02 MVC 均已完成；M02 gate PASS（2 条 P3 warning）。J1/J2 真实栈通过：首字 337ms、跨租户 404、审计 append-only、同源产物正文/下载。远程私有仓库 main=5004d93。M03 已建 planned，尚未启动。
next: 等待用户批准启动 M03；批准后运行 node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" module update M03 --status building，并按 docs/PRODUCT.md 的 M03 范围拆任务。
invariants:
  - 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
  - 所有租户表经 TenantJdbc 强制 tenant_id；跨租户对象统一 404。
  - 审计 append-only，UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
  - Worker SSE 必须逐事件 flush；首字预算 3s，真实栈已实测 337ms。
  - 审批参数 params_preview 只存脱敏摘要；完整参数用 V8 密文重放。
risks:
  - DEC-0003=C：MySQL Connector/J 许可仍是 release blocker，发布前必须商业许可或切换驱动。
  - B-06 MCP SDK 例外到期 2026-12-31 或 M03 gate；升级 agentscope 后删除 .trivyignore 条目。
  - commands.perf 与 deploy_preview 仍缺可执行脚本；M03/M09 前处理。
  - Pro 6 beta 依赖锁定；升级需人工回归。真实栈仍用假模型/假 MCP，未跑 k6 p95/Helm。
first_read:
  - AGENTS.md
  - .riccon/state.json
  - .riccon/reviews/M02-review.md
  - docs/PRODUCT.md
  - docs/security/THREAT-MODEL.md
  - docs/RUNBOOK.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M02
  gate_checked_at: 2026-11-18T13:19:44.728+08:00
  release_gate_status: none
  active_tasks: 0
  checked_at: 2026-11-18T13:23:18.018+08:00
created_at: 2026-11-18T13:23:18.018+08:00
---

# HANDOFF-0001

## Summary

M01 工程基线与 M02 MVC 均已完成；M02 gate PASS（2 条 P3 warning）。J1/J2 真实栈通过：首字 337ms、跨租户 404、审计 append-only、同源产物正文/下载。远程私有仓库 main=5004d93。M03 已建 planned，尚未启动。

## Next

等待用户批准启动 M03；批准后运行 node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" module update M03 --status building，并按 docs/PRODUCT.md 的 M03 范围拆任务。

## Invariants

- 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
- 所有租户表经 TenantJdbc 强制 tenant_id；跨租户对象统一 404。
- 审计 append-only，UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
- Worker SSE 必须逐事件 flush；首字预算 3s，真实栈已实测 337ms。
- 审批参数 params_preview 只存脱敏摘要；完整参数用 V8 密文重放。

## Risks

- DEC-0003=C：MySQL Connector/J 许可仍是 release blocker，发布前必须商业许可或切换驱动。
- B-06 MCP SDK 例外到期 2026-12-31 或 M03 gate；升级 agentscope 后删除 .trivyignore 条目。
- commands.perf 与 deploy_preview 仍缺可执行脚本；M03/M09 前处理。
- Pro 6 beta 依赖锁定；升级需人工回归。真实栈仍用假模型/假 MCP，未跑 k6 p95/Helm。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `AGENTS.md`
2. `.riccon/state.json`
3. `.riccon/reviews/M02-review.md`
4. `docs/PRODUCT.md`
5. `docs/security/THREAT-MODEL.md`
6. `docs/RUNBOOK.md`
