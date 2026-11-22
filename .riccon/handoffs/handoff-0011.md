---
handoff_id: HANDOFF-0011
session: ses_dfe911929ffeTNUpJoD6UW1bTj
summary: M20 done/accepted（DEC-0033）；gate PASS/REV-M20-001；全量 1148+493 tests、E2E 40、a11y 15、security 0；M05 未创建，下一会话先做 M05 Product/Architect 规划；不要启动 M11。
next: |-
  新会话先读 handoff-0011、REV-M20-001、DEC-0032/0033、M20 模块、PRODUCT/ARCHITECTURE/ACCEPTANCE 与 M05 路线图。然后：
  1) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" status --project . && node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" state validate --project .
  2) 确认 M20 done/product pass/user accepted（DEC-0033）、无 active task、gate pass；不要自动启动 M11 legacy，也不要重做 M20。
  3) M05 尚未创建模块文件。先由 Product 出 M05 brief/AC（Excel/图片 OCR、文档级 ACL、1 个外部连接器），Architect 评可行性/数据/安全/契约，Lead 再创建 M05 模块与任务卡；未获用户对 M05 范围/AC 的批准前不写实现。
  4) M05 计划时同时排入 M05-gate 债务：M13 F-001 部门字段 fix 模块（DEC-0023 承诺）、M22 F-001 前端 workspace 异步竞态、M22 F-002 §10.5 例外清单、M20 F-004 CostPage 契约断言、ADR-0022 Context“统一 404”摘要；F-001 价格快照在 M05+ 价格维护/批量改价前重访 ADR-0022 §2.1；F-002 3s 证据由 M09 观测复核。
  5) M05 是用户可见模块，按“计划→构建→独立验证→gate→Product 预验收→用户验收”执行；不要跳过独立 Verifier。
invariants:
  - M20 done，product_status=pass，user_status=accepted（DEC-0033），7 个任务 done；gate PASS（REV-M20-001）；全量 EV-0763..EV-0768。
  - M04-AC-11 pass（with residual F-001）；M04-AC-12 pass；M20 不改变用户可见 AC/页面/文案/入口；E2E-M04-06 已在 EV-0765 重新通过。
  - 残余：F-001 价格快照成功终态读取（M05+ 价格维护/批量改价前重访 ADR-0022 §2.1）；F-002 3s 证据由 M09 观测；F-003 EV 行级耗时；F-004 CostPage 契约断言（M05 gate 前）；ADR-0022 Context 摘要（architect，M05 gate 前）。
  - M05 模块文件不存在；M11 legacy planned 且有 done 任务，禁止自动启动；M14 docs-only product/user pending；M16/TASK-0088 planned；DEC-0003 MySQL 驱动许可仍是 release blocker。
risks:
  - M05 规划前不要假设模块/任务存在；必须先 Product brief/AC + Architect 可行性评审，再由 Lead 建卡。
  - M05-gate 债务需在 M05 计划中显式排入：M13 F-001 部门字段 fix（DEC-0023）、M22 F-001 前端异步竞态、M22 F-002 §10.5 例外清单、M20 F-004、ADR-0022 摘要。
  - M22 F-001 前端 workspace 单测竞态可能让 canonical/CI 偶发红；不得用 retry 静默吞掉，修复归 builder，M05 gate 前。
  - F-001 在途改价窗口在价格维护能力上线时会放大；在 M05+ 任何价格写入前必须实现尝试开始快照或再次书面接受。
first_read:
  - .riccon/state.json
  - .riccon/handoffs/handoff-0011.md
  - AGENTS.md
  - docs/COLLABORATION.md
  - .riccon/reviews/M20-review.md
  - .riccon/modules/M20.json
  - .riccon/decisions/DEC-0033.md
  - docs/PRODUCT.md
  - docs/ARCHITECTURE.md
  - docs/ACCEPTANCE.md
  - docs/adr/ADR-0022-m04-quota-and-cost.md
  - docs/CODING-STANDARDS.md
  - .riccon/reviews/tasks/TASK-0142-review-002.md
  - contracts/openapi.yaml
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M20
  gate_checked_at: 2026-11-22T15:52:42.399+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-22T16:05:26.998+08:00
created_at: 2026-11-22T16:05:26.998+08:00
---

# HANDOFF-0011

## Summary

M20 done/accepted（DEC-0033）；gate PASS/REV-M20-001；全量 1148+493 tests、E2E 40、a11y 15、security 0；M05 未创建，下一会话先做 M05 Product/Architect 规划；不要启动 M11。

## Next

新会话先读 handoff-0011、REV-M20-001、DEC-0032/0033、M20 模块、PRODUCT/ARCHITECTURE/ACCEPTANCE 与 M05 路线图。然后：
1) node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" status --project . && node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" state validate --project .
2) 确认 M20 done/product pass/user accepted（DEC-0033）、无 active task、gate pass；不要自动启动 M11 legacy，也不要重做 M20。
3) M05 尚未创建模块文件。先由 Product 出 M05 brief/AC（Excel/图片 OCR、文档级 ACL、1 个外部连接器），Architect 评可行性/数据/安全/契约，Lead 再创建 M05 模块与任务卡；未获用户对 M05 范围/AC 的批准前不写实现。
4) M05 计划时同时排入 M05-gate 债务：M13 F-001 部门字段 fix 模块（DEC-0023 承诺）、M22 F-001 前端 workspace 异步竞态、M22 F-002 §10.5 例外清单、M20 F-004 CostPage 契约断言、ADR-0022 Context“统一 404”摘要；F-001 价格快照在 M05+ 价格维护/批量改价前重访 ADR-0022 §2.1；F-002 3s 证据由 M09 观测复核。
5) M05 是用户可见模块，按“计划→构建→独立验证→gate→Product 预验收→用户验收”执行；不要跳过独立 Verifier。

## Invariants

- M20 done，product_status=pass，user_status=accepted（DEC-0033），7 个任务 done；gate PASS（REV-M20-001）；全量 EV-0763..EV-0768。
- M04-AC-11 pass（with residual F-001）；M04-AC-12 pass；M20 不改变用户可见 AC/页面/文案/入口；E2E-M04-06 已在 EV-0765 重新通过。
- 残余：F-001 价格快照成功终态读取（M05+ 价格维护/批量改价前重访 ADR-0022 §2.1）；F-002 3s 证据由 M09 观测；F-003 EV 行级耗时；F-004 CostPage 契约断言（M05 gate 前）；ADR-0022 Context 摘要（architect，M05 gate 前）。
- M05 模块文件不存在；M11 legacy planned 且有 done 任务，禁止自动启动；M14 docs-only product/user pending；M16/TASK-0088 planned；DEC-0003 MySQL 驱动许可仍是 release blocker。

## Risks

- M05 规划前不要假设模块/任务存在；必须先 Product brief/AC + Architect 可行性评审，再由 Lead 建卡。
- M05-gate 债务需在 M05 计划中显式排入：M13 F-001 部门字段 fix（DEC-0023）、M22 F-001 前端异步竞态、M22 F-002 §10.5 例外清单、M20 F-004、ADR-0022 摘要。
- M22 F-001 前端 workspace 单测竞态可能让 canonical/CI 偶发红；不得用 retry 静默吞掉，修复归 builder，M05 gate 前。
- F-001 在途改价窗口在价格维护能力上线时会放大；在 M05+ 任何价格写入前必须实现尝试开始快照或再次书面接受。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `.riccon/handoffs/handoff-0011.md`
3. `AGENTS.md`
4. `docs/COLLABORATION.md`
5. `.riccon/reviews/M20-review.md`
6. `.riccon/modules/M20.json`
7. `.riccon/decisions/DEC-0033.md`
8. `docs/PRODUCT.md`
9. `docs/ARCHITECTURE.md`
10. `docs/ACCEPTANCE.md`
11. `docs/adr/ADR-0022-m04-quota-and-cost.md`
12. `docs/CODING-STANDARDS.md`
13. `.riccon/reviews/tasks/TASK-0142-review-002.md`
14. `contracts/openapi.yaml`
