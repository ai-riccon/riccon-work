---
handoff_id: HANDOFF-0006
session: null
summary: 团队内核 0.1.9 同步；T4 sandbox 合同修复（network none/cpus/attestation/wrapper/EV-0519）；T4 DoD 保留现状；M18 done/accepted；M04 planned 48h 待新会话
next: 新会话运行 /riccon-resume。M04 48h no-split：先让 Architect 把 ADR-0021 §8.4 改为单模块 48h（移除 M17/B12/B13，17 个实施任务），创建 M04 任务并补 TASK-0089 done，然后按 3 builder 启动 B1a/B1b/B1c/B2。
invariants:
  - 内核 0.1.9；命令统一用 node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" --project .
  - T4 sandbox：network=none、cpus、attestation_terms、commands.sandbox=bash scripts/sandbox.sh；release evidence 必须包含 EV-0519
  - T4 DoD：definition_of_done.module 保留 8 key；security/accessibility/performance/migration 作为 Verifier 评审项与 release evidence，不新增模块 key
  - M18 done/accepted（DEC-0025）；M04 planned 48h、18 AC、不拆、契约保持 1.3.0-m04；M17 不创建
  - 代码任务默认 Serena 符号工具 + CodeGraph 调用链；全量 Maven 测试期间不得并行激活 Serena JDT LS
  - 模块 gate 的 test/coverage 共用同一条 coverage 全量 EV；最终提交只跑一次全量
risks:
  - T4 sandbox 采用 network=none；若生产需要沙箱访问外部模型/MCP，必须改 allowlist 并走用户决策
  - F-003 P2 accepted（builder-web，到期 2026-12-04）；F-008 P3；M01 open P2 含 F-007 MySQL 驱动 release blocker；M02 P3/accepted P2
  - M11 legacy warning 保留；M14 docs-only Product/user pending；M16/TASK-0088 planned
  - ADR-0021 §8.4 仍写拆分方案，M04 新会话必须先改 no-split；M04 实施任务与 M17 尚未创建
  - 真实栈 E2E/a11y、迁移回滚、k6/perf 未重跑；M04 perf 资产仍需补齐
first_read:
  - .riccon/state.json
  - AGENTS.md#project-contract
  - docs/COLLABORATION.md
  - docs/SANDBOX.md
  - .riccon/reviews/M18-review.md
  - docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md
  - .riccon/decisions/DEC-0025.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M18
  gate_checked_at: 2026-11-20T17:49:57.736+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-20T18:32:12.965+08:00
created_at: 2026-11-20T18:32:12.965+08:00
---

# HANDOFF-0006

## Summary

团队内核 0.1.9 同步；T4 sandbox 合同修复（network none/cpus/attestation/wrapper/EV-0519）；T4 DoD 保留现状；M18 done/accepted；M04 planned 48h 待新会话

## Next

新会话运行 /riccon-resume。M04 48h no-split：先让 Architect 把 ADR-0021 §8.4 改为单模块 48h（移除 M17/B12/B13，17 个实施任务），创建 M04 任务并补 TASK-0089 done，然后按 3 builder 启动 B1a/B1b/B1c/B2。

## Invariants

- 内核 0.1.9；命令统一用 node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" --project .
- T4 sandbox：network=none、cpus、attestation_terms、commands.sandbox=bash scripts/sandbox.sh；release evidence 必须包含 EV-0519
- T4 DoD：definition_of_done.module 保留 8 key；security/accessibility/performance/migration 作为 Verifier 评审项与 release evidence，不新增模块 key
- M18 done/accepted（DEC-0025）；M04 planned 48h、18 AC、不拆、契约保持 1.3.0-m04；M17 不创建
- 代码任务默认 Serena 符号工具 + CodeGraph 调用链；全量 Maven 测试期间不得并行激活 Serena JDT LS
- 模块 gate 的 test/coverage 共用同一条 coverage 全量 EV；最终提交只跑一次全量

## Risks

- T4 sandbox 采用 network=none；若生产需要沙箱访问外部模型/MCP，必须改 allowlist 并走用户决策
- F-003 P2 accepted（builder-web，到期 2026-12-04）；F-008 P3；M01 open P2 含 F-007 MySQL 驱动 release blocker；M02 P3/accepted P2
- M11 legacy warning 保留；M14 docs-only Product/user pending；M16/TASK-0088 planned
- ADR-0021 §8.4 仍写拆分方案，M04 新会话必须先改 no-split；M04 实施任务与 M17 尚未创建
- 真实栈 E2E/a11y、迁移回滚、k6/perf 未重跑；M04 perf 资产仍需补齐

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
4. `docs/SANDBOX.md`
5. `.riccon/reviews/M18-review.md`
6. `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md`
7. `.riccon/decisions/DEC-0025.md`
