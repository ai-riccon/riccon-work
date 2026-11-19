---
handoff_id: HANDOFF-0002
session: null
summary: M10 身份与审计（OIDC/账号绑定/审计查询导出）已 gate pass、Product 预验收 pass、用户 accepted DEC-0021；M15 修复来源 IdP 展示后 gate pass。M12 测试效率、M13 验收补丁、M03 均 done；M11/M14 为 docs-only legacy 模块，保留 warning。远程 main 仍停在 19da921（M10 前），本次未 push。
next: M10 已完成；下一步创建并启动 M04 模型网关纵深（PRODUCT §6/M04）。M11/M14 docs-only legacy closeout 作为独立维护项，不阻塞 M04。
invariants:
  - 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
  - 所有租户表经 TenantJdbc 强制 tenant_id；跨租户/跨部门对象统一 404。
  - 审计 append-only；UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
  - OIDC 不持久化 access/refresh/ID token；失败路径清空会话；联合登出由 IdP end-session。
risks:
  - B2 F-001：external_subject collation 仍 ai_ci，case-variant subject 风险未修，需 V11 或书面接受。
  - OPS-004..007 仅测试环境演练；V10 生产执行、OPS-010 OIDC 回滚、OPS-008/009/013/014/015 需 M09/T4 批准。
  - ADR-0019/0020 仍 proposed；M11 warning/M14 building docs legacy 未归一。
  - 远程 main 未 push 最新 M10/M15/M12 提交。
first_read:
  - AGENTS.md
  - .riccon/state.json
  - .riccon/reviews/M10-review.md
  - .riccon/reviews/M15-review.md
  - docs/ACCEPTANCE.md
  - docs/PRODUCT.md
  - docs/RUNBOOK.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M10
  gate_checked_at: 2026-11-20T00:23:03.678+08:00
  release_gate_status: none
  product_acceptance_open: []
  active_tasks: 0
  checked_at: 2026-11-20T00:24:28.112+08:00
created_at: 2026-11-20T00:24:28.112+08:00
---

# HANDOFF-0002

## Summary

M10 身份与审计（OIDC/账号绑定/审计查询导出）已 gate pass、Product 预验收 pass、用户 accepted DEC-0021；M15 修复来源 IdP 展示后 gate pass。M12 测试效率、M13 验收补丁、M03 均 done；M11/M14 为 docs-only legacy 模块，保留 warning。远程 main 仍停在 19da921（M10 前），本次未 push。

## Next

M10 已完成；下一步创建并启动 M04 模型网关纵深（PRODUCT §6/M04）。M11/M14 docs-only legacy closeout 作为独立维护项，不阻塞 M04。

## Invariants

- 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
- 所有租户表经 TenantJdbc 强制 tenant_id；跨租户/跨部门对象统一 404。
- 审计 append-only；UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
- OIDC 不持久化 access/refresh/ID token；失败路径清空会话；联合登出由 IdP end-session。

## Risks

- B2 F-001：external_subject collation 仍 ai_ci，case-variant subject 风险未修，需 V11 或书面接受。
- OPS-004..007 仅测试环境演练；V10 生产执行、OPS-010 OIDC 回滚、OPS-008/009/013/014/015 需 M09/T4 批准。
- ADR-0019/0020 仍 proposed；M11 warning/M14 building docs legacy 未归一。
- 远程 main 未 push 最新 M10/M15/M12 提交。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：无未完成项
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `AGENTS.md`
2. `.riccon/state.json`
3. `.riccon/reviews/M10-review.md`
4. `.riccon/reviews/M15-review.md`
5. `docs/ACCEPTANCE.md`
6. `docs/PRODUCT.md`
7. `docs/RUNBOOK.md`
