---
handoff_id: HANDOFF-0010
session: ses_dff30f48bffeB4H5hxpLcfP7iG
summary: "M22 done/accepted；PR #2 merged 7593585；main CI 44982446453 success；DEC-0031=A（接受+合并）、DEC-0032=A（M20 优先）；M20 planned 8h，下一任务 TASK-0143（architect）。"
next: |-
  新会话先读 handoff-0010、M22 review、M20 模块与 4 张任务卡、REV-TASK-0108、ADR-0009/0022 与 contracts/openapi.yaml。然后：
  1) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" module update M20 --status building --project .
  2) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" task update TASK-0143 --status ready --project .
  3) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" task update TASK-0143 --status in_progress --owner riccon/architect --baseline-commit 75935852fdbfc85172b650e2ee00d253c1090dcc --project .
  4) spawn riccon/architect 执行 TASK-0143（成本契约/AC-12）；完成后 TASK-0140/0141 串行（riccon/builder-cost），最后 TASK-0142（riccon/builder-core，安全高风险，需独立 Verifier）。
  不要自动启动 M11 legacy；不要并行写 ADR-0022；M20 每任务先提交再取证。
invariants:
  - "M22 done，product_status=pass，user_status=accepted（DEC-0031）；PR #2 merged 7593585；main CI 44982446453 success；3 个任务 done，gate PASS（REV-M22-001，11:26）。"
  - M22 证据：EV-0747 合同 test+coverage 196/1116/0/0/0 + 前端 493；EV-0744 build；EV-0745 E2E 40；EV-0740 baseline 196/1116/834.18；EV-0741 CI run 44976746616；EV-0743 文档/零产品 diff。
  - DEC-0032=A：M20 fix 优先，之后 M05；M20 planned 8h，4 任务（TASK-0140/0141 builder-cost、TASK-0142 builder-core、TASK-0143 architect）；active tasks=0。
  - F-001 P2（前端 workspace 单测异步竞态，owner riccon/builder，M05 gate 前）与 F-002 P3（§10.5 例外清单，owner riccon/architect，M05 gate 前）仍 accepted；DEC-0003 MySQL 驱动许可仍是 release blocker。
  - M14 product/user pending；M11 planned legacy；M16 Web 审计 flake 仍 planned；不要自动启动 M11。
risks:
  - F-001 仍可能让 canonical/CI 偶发红；不得用 retry 静默吞掉，修复归 builder（M05 gate 前）。
  - coverage job 约 8.5 分钟；CI 总墙钟不承诺 ≤5 分钟；前端覆盖率仍不由 CI 强制。
  - M20 写范围冲突：TASK-0140/0142/0143 都涉及 ADR-0022，必须串行或明确分工；TASK-0140/0141 同一 owner builder-cost 不能并行。TASK-0142 安全高风险，done 前必须有独立 Verifier 评审。
  - M20 TASK-0143 先定 AC-12/Page 契约，再让 0140/0141/0142 落地；不要先改实现再补契约。
first_read:
  - .riccon/state.json
  - .riccon/handoffs/handoff-0010.md
  - AGENTS.md
  - docs/COLLABORATION.md
  - .riccon/reviews/M22-review.md
  - .riccon/modules/M20.json
  - .riccon/tasks/TASK-0143.json
  - .riccon/tasks/TASK-0140.json
  - .riccon/tasks/TASK-0141.json
  - .riccon/tasks/TASK-0142.json
  - .riccon/reviews/tasks/TASK-0108-review.md
  - docs/adr/ADR-0009-tenant-isolation-enforcement.md
  - docs/adr/ADR-0022-m04-quota-and-cost.md
  - contracts/openapi.yaml
  - docs/PRODUCT.md
  - docs/ACCEPTANCE.md
  - .riccon/decisions/DEC-0031.md
  - .riccon/decisions/DEC-0032.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M22
  gate_checked_at: 2026-11-22T11:31:10.532+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-22T12:00:44.605+08:00
created_at: 2026-11-22T12:00:44.605+08:00
---

# HANDOFF-0010

## Summary

M22 done/accepted；PR #2 merged 7593585；main CI 44982446453 success；DEC-0031=A（接受+合并）、DEC-0032=A（M20 优先）；M20 planned 8h，下一任务 TASK-0143（architect）。

## Next

新会话先读 handoff-0010、M22 review、M20 模块与 4 张任务卡、REV-TASK-0108、ADR-0009/0022 与 contracts/openapi.yaml。然后：
1) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" module update M20 --status building --project .
2) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" task update TASK-0143 --status ready --project .
3) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" task update TASK-0143 --status in_progress --owner riccon/architect --baseline-commit 75935852fdbfc85172b650e2ee00d253c1090dcc --project .
4) spawn riccon/architect 执行 TASK-0143（成本契约/AC-12）；完成后 TASK-0140/0141 串行（riccon/builder-cost），最后 TASK-0142（riccon/builder-core，安全高风险，需独立 Verifier）。
不要自动启动 M11 legacy；不要并行写 ADR-0022；M20 每任务先提交再取证。

## Invariants

- M22 done，product_status=pass，user_status=accepted（DEC-0031）；PR #2 merged 7593585；main CI 44982446453 success；3 个任务 done，gate PASS（REV-M22-001，11:26）。
- M22 证据：EV-0747 合同 test+coverage 196/1116/0/0/0 + 前端 493；EV-0744 build；EV-0745 E2E 40；EV-0740 baseline 196/1116/834.18；EV-0741 CI run 44976746616；EV-0743 文档/零产品 diff。
- DEC-0032=A：M20 fix 优先，之后 M05；M20 planned 8h，4 任务（TASK-0140/0141 builder-cost、TASK-0142 builder-core、TASK-0143 architect）；active tasks=0。
- F-001 P2（前端 workspace 单测异步竞态，owner riccon/builder，M05 gate 前）与 F-002 P3（§10.5 例外清单，owner riccon/architect，M05 gate 前）仍 accepted；DEC-0003 MySQL 驱动许可仍是 release blocker。
- M14 product/user pending；M11 planned legacy；M16 Web 审计 flake 仍 planned；不要自动启动 M11。

## Risks

- F-001 仍可能让 canonical/CI 偶发红；不得用 retry 静默吞掉，修复归 builder（M05 gate 前）。
- coverage job 约 8.5 分钟；CI 总墙钟不承诺 ≤5 分钟；前端覆盖率仍不由 CI 强制。
- M20 写范围冲突：TASK-0140/0142/0143 都涉及 ADR-0022，必须串行或明确分工；TASK-0140/0141 同一 owner builder-cost 不能并行。TASK-0142 安全高风险，done 前必须有独立 Verifier 评审。
- M20 TASK-0143 先定 AC-12/Page 契约，再让 0140/0141/0142 落地；不要先改实现再补契约。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `.riccon/handoffs/handoff-0010.md`
3. `AGENTS.md`
4. `docs/COLLABORATION.md`
5. `.riccon/reviews/M22-review.md`
6. `.riccon/modules/M20.json`
7. `.riccon/tasks/TASK-0143.json`
8. `.riccon/tasks/TASK-0140.json`
9. `.riccon/tasks/TASK-0141.json`
10. `.riccon/tasks/TASK-0142.json`
11. `.riccon/reviews/tasks/TASK-0108-review.md`
12. `docs/adr/ADR-0009-tenant-isolation-enforcement.md`
13. `docs/adr/ADR-0022-m04-quota-and-cost.md`
14. `contracts/openapi.yaml`
15. `docs/PRODUCT.md`
16. `docs/ACCEPTANCE.md`
17. `.riccon/decisions/DEC-0031.md`
18. `.riccon/decisions/DEC-0032.md`
