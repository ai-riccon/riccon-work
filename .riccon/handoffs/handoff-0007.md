---
handoff_id: HANDOFF-0007
session: null
summary: M04 模型网关纵深 done/accepted（DEC-0027，18 AC 全 pass，gate M04 pass）；M19 测试提速 P0–P2 尚未开始，P0 启动后无文件/提交即停止，工作树干净。
next: 新会话运行 /riccon-resume。M19 从零开始：node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" module update M19 --status building --project .；task update TASK-0117 --status ready --project .；task update TASK-0117 --status in_progress --project .；先做 P0（scripts/test-timings.mjs + CI artifact + 预算），再 P1 热栈、P2 4-shard；commands.test 不变。
invariants:
  - M04 已 done/accepted（DEC-0027）；gate M04 pass；18 AC 全 pass；证据 EV-0675/0676/0677/0678/0689/0669/0681/0682..0686
  - M19 模块 planned，TASK-0117/0118/0119 planned；P0 曾启动但无文件/无提交，工作树干净
  - DEC-0026 P0–P4 已接受；M19 P0–P2 先做，P3/P4 在 P2 数据后再拆
  - M04 残余 F-006/008/009/010/012/013、R-M04-01/02/03 转 M05；R-M04-01 是 P2（聊天 429 文案透传）
  - 代码任务默认 Serena/CodeGraph；全量 Maven 测试期间不并行激活 JDT LS
risks:
  - M11 legacy planned 但有 done 任务（state warning）；M14 product/user pending；M16/TASK-0088 planned
  - DEC-0003 MySQL Connector/J 许可仍是 release blocker
  - M19 P1/P2 不得减少/跳过用例；若把分片命令作为 canonical gate，需另提合同决策
  - M04 证据在 2019 Intel i7/12 vCPU 主机上取得；M09 生产环境仍 not-applicable
first_read:
  - .riccon/state.json
  - AGENTS.md
  - docs/COLLABORATION.md
  - .riccon/modules/M19.json
  - .riccon/tasks/TASK-0117.json
  - .riccon/decisions/DEC-0026.md
  - .riccon/reviews/M04-review.md
  - docs/ACCEPTANCE.md
  - docs/CODING-STANDARDS.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M04
  gate_checked_at: 2026-11-21T16:33:27.093+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-21T16:38:33.396+08:00
created_at: 2026-11-21T16:38:33.396+08:00
---

# HANDOFF-0007

## Summary

M04 模型网关纵深 done/accepted（DEC-0027，18 AC 全 pass，gate M04 pass）；M19 测试提速 P0–P2 尚未开始，P0 启动后无文件/提交即停止，工作树干净。

## Next

新会话运行 /riccon-resume。M19 从零开始：node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" module update M19 --status building --project .；task update TASK-0117 --status ready --project .；task update TASK-0117 --status in_progress --project .；先做 P0（scripts/test-timings.mjs + CI artifact + 预算），再 P1 热栈、P2 4-shard；commands.test 不变。

## Invariants

- M04 已 done/accepted（DEC-0027）；gate M04 pass；18 AC 全 pass；证据 EV-0675/0676/0677/0678/0689/0669/0681/0682..0686
- M19 模块 planned，TASK-0117/0118/0119 planned；P0 曾启动但无文件/无提交，工作树干净
- DEC-0026 P0–P4 已接受；M19 P0–P2 先做，P3/P4 在 P2 数据后再拆
- M04 残余 F-006/008/009/010/012/013、R-M04-01/02/03 转 M05；R-M04-01 是 P2（聊天 429 文案透传）
- 代码任务默认 Serena/CodeGraph；全量 Maven 测试期间不并行激活 JDT LS

## Risks

- M11 legacy planned 但有 done 任务（state warning）；M14 product/user pending；M16/TASK-0088 planned
- DEC-0003 MySQL Connector/J 许可仍是 release blocker
- M19 P1/P2 不得减少/跳过用例；若把分片命令作为 canonical gate，需另提合同决策
- M04 证据在 2019 Intel i7/12 vCPU 主机上取得；M09 生产环境仍 not-applicable

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `AGENTS.md`
3. `docs/COLLABORATION.md`
4. `.riccon/modules/M19.json`
5. `.riccon/tasks/TASK-0117.json`
6. `.riccon/decisions/DEC-0026.md`
7. `.riccon/reviews/M04-review.md`
8. `docs/ACCEPTANCE.md`
9. `docs/CODING-STANDARDS.md`
