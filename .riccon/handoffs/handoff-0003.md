---
handoff_id: HANDOFF-0003
session: null
summary: 团队内核已同步到 0.1.6（AGENTS.md 团队区块 + docs/COLLABORATION.md）；M10 OPS 演练记录迁至 docs/M10-OPS-DRILL.md；M15 补齐 Product pass 并保留 DEC-0021 accepted；M14 docs-only Product/user 重置 pending；M11/M12 legacy 保留注记。state validate 0 errors，仅剩 M11 legacy warning；工作区已提交。
next: M04 可开始：先由 Product 补 M04 AC/范围，Architect 出路由/配额/密钥/成本 ADR；评审通过后拆分 builder 任务，按 T4、三级验证、模块 gate、Product 预验收 + 用户最终验收执行。M11/M14 docs-only legacy closeout 作为独立维护项，不阻塞 M04。
invariants:
  - 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
  - 所有租户表经 TenantJdbc 强制 tenant_id；跨租户/跨部门对象统一 404。
  - 审计 append-only；UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
  - OIDC 不持久化 access/refresh/ID token；失败路径清空会话；联合登出由 IdP end-session。
  - 团队硬规则与 Project Contract 冲突时以合同为准；项目补充不得削弱硬规则。
  - .riccon/reviews/ 顶层只放模块评审；任务级评审放 reviews/tasks/。
risks:
  - B2 F-001：external_subject collation 仍 ai_ci，case-variant subject 风险未修，需 V11 或书面接受。
  - OPS-004..007 仅测试环境演练；V10 生产迁移、OPS-010 OIDC 回滚、T4 生产批准未做。
  - ADR-0019/0020 仍 proposed。
  - M11 planned 但 2 个任务 done（legacy warning 保留）；M14 building 且 Product/user pending（TASK-0053/0057/0061 done，但无模块 review/gate）。
  - M12 Product/user not-recorded 为 legacy N/A，不伪造 pass。
  - docs/PRODUCT.md 验收表仍显示 M10 user pending（按不改 PRODUCT 正文约束未动），以 DEC-0021 与 docs/ACCEPTANCE.md 为准。
first_read:
  - AGENTS.md
  - docs/COLLABORATION.md
  - .riccon/state.json
  - docs/ACCEPTANCE.md
  - docs/PRODUCT.md
  - .riccon/reviews/M10-review.md
  - docs/M10-OPS-DRILL.md
  - .riccon/handoffs/handoff-0002.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M10
  gate_checked_at: 2026-11-20T00:23:03.678+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-20T00:44:50.356+08:00
created_at: 2026-11-20T00:44:50.356+08:00
---

# HANDOFF-0003

## Summary

团队内核已同步到 0.1.6（AGENTS.md 团队区块 + docs/COLLABORATION.md）；M10 OPS 演练记录迁至 docs/M10-OPS-DRILL.md；M15 补齐 Product pass 并保留 DEC-0021 accepted；M14 docs-only Product/user 重置 pending；M11/M12 legacy 保留注记。state validate 0 errors，仅剩 M11 legacy warning；工作区已提交。

## Next

M04 可开始：先由 Product 补 M04 AC/范围，Architect 出路由/配额/密钥/成本 ADR；评审通过后拆分 builder 任务，按 T4、三级验证、模块 gate、Product 预验收 + 用户最终验收执行。M11/M14 docs-only legacy closeout 作为独立维护项，不阻塞 M04。

## Invariants

- 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
- 所有租户表经 TenantJdbc 强制 tenant_id；跨租户/跨部门对象统一 404。
- 审计 append-only；UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
- OIDC 不持久化 access/refresh/ID token；失败路径清空会话；联合登出由 IdP end-session。
- 团队硬规则与 Project Contract 冲突时以合同为准；项目补充不得削弱硬规则。
- .riccon/reviews/ 顶层只放模块评审；任务级评审放 reviews/tasks/。

## Risks

- B2 F-001：external_subject collation 仍 ai_ci，case-variant subject 风险未修，需 V11 或书面接受。
- OPS-004..007 仅测试环境演练；V10 生产迁移、OPS-010 OIDC 回滚、T4 生产批准未做。
- ADR-0019/0020 仍 proposed。
- M11 planned 但 2 个任务 done（legacy warning 保留）；M14 building 且 Product/user pending（TASK-0053/0057/0061 done，但无模块 review/gate）。
- M12 Product/user not-recorded 为 legacy N/A，不伪造 pass。
- docs/PRODUCT.md 验收表仍显示 M10 user pending（按不改 PRODUCT 正文约束未动），以 DEC-0021 与 docs/ACCEPTANCE.md 为准。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `AGENTS.md`
2. `docs/COLLABORATION.md`
3. `.riccon/state.json`
4. `docs/ACCEPTANCE.md`
5. `docs/PRODUCT.md`
6. `.riccon/reviews/M10-review.md`
7. `docs/M10-OPS-DRILL.md`
8. `.riccon/handoffs/handoff-0002.md`
