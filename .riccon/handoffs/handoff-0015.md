---
handoff_id: HANDOFF-0015
session: ses_df5f45b27ffestY8NHw7ww7W8X
summary: M25（M05b S3 连接器）已 done + gate pass + Product 预验收 pass + 用户 accepted（DEC-0044=A，with residuals R-M25-01..11）：14/14 任务、14.7h/56h；REV-M25-001 final/pass 无 P0/P1。最终代码树 c446f1e（HEAD d77c0c4 仅 .riccon/docs 变更）；EV-1011 build、EV-1012 全量 test/coverage（API 821、Worker 688、Web 650；91.50/71.57、86.81/71.98、87.02/80.26）、EV-1007 E2E 63/63（m05b 11 条）、EV-1006 a11y 22/22、EV-1002 1000 对象 perf passed、EV-1014 docs/contract。残余：F-001 ACL 写（DEC-0043=A）、F-002 perf 报告 reproduceCommand/env、F-003 O2b RUNBOOK、F-004 KB-502 等 P2/P3 accepted，跟踪到 M09/release；accepted 不表示已修复。当前 phase=modules，current_module=M11（planned，2 任务 3h）。HANDOFF-0014 为误生成的默认摘要，本 HANDOFF-0015 取代它。
next: 运行 `node ~/.config/opencode/riccon/scripts/riccon.mjs status --project .` 复核状态；M25 已 accepted，下一步启动 M11（fix，planned，TASK-0035/0036）：`node ~/.config/opencode/riccon/scripts/riccon.mjs module update M11 --status building`，按任务卡派单；M06/M07/M09 仍 planned，未获用户选择前不启动。
invariants:
  - M25 user=accepted（DEC-0044=A）不等于 residual 已修复；F-001/F-002/F-003/F-004 仍按 accepted residual 跟踪到 M09/release。
  - 最终代码树为 c446f1e；b5e79c1/bf7260d/9ca4fb6/7f279c8/cd07a81/2fc7c6d/6986e85/d77c0c4 只改 .riccon/docs，apps/platform/contracts/deploy/e2e 无生产 diff。
  - M25 证据以 EV-1011/1012/1007/1006/1002/1014 与 REV-M25-001 为准；EV-1009/1010 为探索性失败已归档，不用于通过。
risks:
  - F-002 perf 报告内 reproduceCommand/env 仍写 aclWriteVus=1，不能照抄复现 passed 矩阵；EV-1002 的 command 字段才带 PERF_M05_ACL_WRITES=0。
  - F-003 O2b RUNBOOK 误删恢复/维护账号最小权限未修订，生产首次 apply 前必须修或按 M09 计划重写。
  - F-001 ACL 写 p95 未量测（DEC-0043=A）；M09/release 必须解决或再次书面接受。
  - ADR-0023 仍 proposed、无 M25 独立架构评审（F-010 P3）；发布前需 Architect/Release 收口。
  - M14 product=pending user=pending 仍未收口；release check 会要求 product=pass、user=accepted。
first_read:
  - .riccon/state.json
  - AGENTS.md#project-contract
  - .riccon/reviews/M25-review.md
  - docs/ACCEPTANCE.md
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
  checked_at: 2026-11-24T19:16:13.693+08:00
created_at: 2026-11-24T19:16:13.693+08:00
---

# HANDOFF-0015

## Summary

M25（M05b S3 连接器）已 done + gate pass + Product 预验收 pass + 用户 accepted（DEC-0044=A，with residuals R-M25-01..11）：14/14 任务、14.7h/56h；REV-M25-001 final/pass 无 P0/P1。最终代码树 c446f1e（HEAD d77c0c4 仅 .riccon/docs 变更）；EV-1011 build、EV-1012 全量 test/coverage（API 821、Worker 688、Web 650；91.50/71.57、86.81/71.98、87.02/80.26）、EV-1007 E2E 63/63（m05b 11 条）、EV-1006 a11y 22/22、EV-1002 1000 对象 perf passed、EV-1014 docs/contract。残余：F-001 ACL 写（DEC-0043=A）、F-002 perf 报告 reproduceCommand/env、F-003 O2b RUNBOOK、F-004 KB-502 等 P2/P3 accepted，跟踪到 M09/release；accepted 不表示已修复。当前 phase=modules，current_module=M11（planned，2 任务 3h）。HANDOFF-0014 为误生成的默认摘要，本 HANDOFF-0015 取代它。

## Next

运行 `node ~/.config/opencode/riccon/scripts/riccon.mjs status --project .` 复核状态；M25 已 accepted，下一步启动 M11（fix，planned，TASK-0035/0036）：`node ~/.config/opencode/riccon/scripts/riccon.mjs module update M11 --status building`，按任务卡派单；M06/M07/M09 仍 planned，未获用户选择前不启动。

## Invariants

- M25 user=accepted（DEC-0044=A）不等于 residual 已修复；F-001/F-002/F-003/F-004 仍按 accepted residual 跟踪到 M09/release。
- 最终代码树为 c446f1e；b5e79c1/bf7260d/9ca4fb6/7f279c8/cd07a81/2fc7c6d/6986e85/d77c0c4 只改 .riccon/docs，apps/platform/contracts/deploy/e2e 无生产 diff。
- M25 证据以 EV-1011/1012/1007/1006/1002/1014 与 REV-M25-001 为准；EV-1009/1010 为探索性失败已归档，不用于通过。

## Risks

- F-002 perf 报告内 reproduceCommand/env 仍写 aclWriteVus=1，不能照抄复现 passed 矩阵；EV-1002 的 command 字段才带 PERF_M05_ACL_WRITES=0。
- F-003 O2b RUNBOOK 误删恢复/维护账号最小权限未修订，生产首次 apply 前必须修或按 M09 计划重写。
- F-001 ACL 写 p95 未量测（DEC-0043=A）；M09/release 必须解决或再次书面接受。
- ADR-0023 仍 proposed、无 M25 独立架构评审（F-010 P3）；发布前需 Architect/Release 收口。
- M14 product=pending user=pending 仍未收口；release check 会要求 product=pass、user=accepted。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `AGENTS.md#project-contract`
3. `.riccon/reviews/M25-review.md`
4. `docs/ACCEPTANCE.md`
