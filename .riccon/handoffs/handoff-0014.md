---
handoff_id: HANDOFF-0014
session: null
summary: Handoff at phase modules.
next: "Continue M11: 团队规范与验证效率升级."
invariants: []
risks: []
first_read:
  - .riccon/state.json
  - AGENTS.md#project-contract
  - docs/COLLABORATION.md
  - .riccon/handoffs/handoff-0014.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M25
  gate_checked_at: 2026-11-24T19:14:56.618+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-24T19:15:23.639+08:00
created_at: 2026-11-24T19:15:23.639+08:00
---
> **Superseded**：本文件由 `riccon handoff new --help` 误生成，仅含默认摘要；已被 `handoff-0015.md` 取代，不作为恢复依据。



# HANDOFF-0014

## Summary

Handoff at phase modules.

## Next

Continue M11: 团队规范与验证效率升级.

## Invariants

- None recorded.

## Risks

- None recorded.

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
4. `.riccon/handoffs/handoff-0014.md`
