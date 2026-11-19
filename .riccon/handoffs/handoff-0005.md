---
handoff_id: HANDOFF-0005
session: null
summary: 团队内核 0.1.8；handoff gate 新鲜度修复；M16/TASK-0088 保留 planned；M04 待启动
next: M04 可开始（待用户确认）：Product 补 AC/范围，Architect 出路由/配额/密钥/成本 ADR；评审后按实例 owner 并行拆 builder 任务，gate 只跑一次 coverage 全量并绑同一 EV。
invariants:
  - 并行 builder 使用实例 owner；同一 owner 同时只能有一个 in_progress。
  - 模块 gate 的 test/coverage 共用同一条 coverage 全量 EV。
  - Review 的 reviewer.session 必须是真实 ses_...；旧 review 不改写。
risks:
  - M11 legacy warning 保留；M14 Product/user pending；M16/TASK-0088 planned，未纳入 M10 gate。
  - B2 F-001 collation、ADR-0019/0020 proposed、OPS 生产项仍未完成。
first_read:
  - .riccon/state.json
  - AGENTS.md#project-contract
  - docs/COLLABORATION.md
  - .riccon/handoffs/handoff-0005.md
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
  checked_at: 2026-11-20T01:51:23.948+08:00
created_at: 2026-11-20T01:51:23.948+08:00
---

# HANDOFF-0005

## Summary

团队内核 0.1.8；handoff gate 新鲜度修复；M16/TASK-0088 保留 planned；M04 待启动

## Next

M04 可开始（待用户确认）：Product 补 AC/范围，Architect 出路由/配额/密钥/成本 ADR；评审后按实例 owner 并行拆 builder 任务，gate 只跑一次 coverage 全量并绑同一 EV。

## Invariants

- 并行 builder 使用实例 owner；同一 owner 同时只能有一个 in_progress。
- 模块 gate 的 test/coverage 共用同一条 coverage 全量 EV。
- Review 的 reviewer.session 必须是真实 ses_...；旧 review 不改写。

## Risks

- M11 legacy warning 保留；M14 Product/user pending；M16/TASK-0088 planned，未纳入 M10 gate。
- B2 F-001 collation、ADR-0019/0020 proposed、OPS 生产项仍未完成。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `AGENTS.md#project-contract`
3. `docs/COLLABORATION.md`
4. `.riccon/handoffs/handoff-0005.md`
