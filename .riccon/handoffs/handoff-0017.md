---
handoff_id: HANDOFF-0017
session: ses_df5f45b27ffestY8NHw7ww7W8X
summary: M06 编排 Studio 已被用户选为下一模块，但尚未开始规划：用户要求先 handoff、新会话再处理 M06；先前启动的 Product 草案代理已取消，未写 docs/PRODUCT.md，工作树 clean，没有创建 M06 模块/任务/决策。当前基线：团队内核 0.1.15；M25 done+user accepted（DEC-0044=A）且 gate pass（0 errors/2 warnings）；M10 5 条 P1 已关闭，gate pass（0 errors/33 warnings），新证据 EV-1015、新评审 REV-M10-003/REV-TASK-0064-002/REV-TASK-0065-003；M11/M14 按 DEC-0045=A 归档为 blocked legacy docs-only，不声称完成/验收。M06 依赖 M02/M04/M05 均 done；合同 roadmap M06 仍 planned，尚未创建模块。当前 state.current_module=M16 只是下一个未归档 planned 模块占位，不要启动。
next: 新会话运行 `node ~/.config/opencode/riccon/scripts/riccon.mjs status --project .`，然后从 M06 规划开始：spawn riccon/product 起草 M06 一页 brief/AC/non-goals/预算（只写 docs/PRODUCT.md 草案，不创建模块/任务、不写源码）；Lead 做完整性与合同一致性检查后，向用户提交 M06 范围/预算决策；批准后再让 Architect 评可行性/数据/安全、Verifier 检查 AC 可验证性，最后 module new 建 M06 与任务。不要启动 M16，也不要在批准前写实现代码。
invariants:
  - M06 尚未创建模块/任务/决策；Product 草案已取消，docs/PRODUCT.md 无未提交改动；不要从聊天历史推断 M06 已批准。
  - M25 为 done + user accepted（DEC-0044=A），但 F-001..F-012 residual 未修复；M10 gate pass 依赖 REV-M10-003/REV-TASK-0064-002/REV-TASK-0065-003 与 EV-1015。
  - M11/M14 仅按 DEC-0045=A 归档为 blocked legacy docs-only；没有模块 gate、没有 Product/用户验收，不得作为完成或 accepted 声称。
  - 团队内核 0.1.15；AGENTS 托管区块与 project.json 一致；M25 road map 已改 done；contract validate --strict 0/0，state validate 0 errors/44 warnings。
risks:
  - M06 范围、AC、预算、架构可行性均未评估；该模块涉及 DAG 编辑器、流程版本、运行/审批、模板、权限/审计与 a11y，必须走完整 Product→用户→Architect/Verifier 链，不能直接开写。
  - M06 依赖 M02/M04/M05 已 done，但现有 approval/run 原语是 J2 专用；DAG 执行器、版本模型、节点类型、模板共享与调度边界需 Architect 收敛，避免范围膨胀。
  - M25 无 security EV；ADR-0023 仍 proposed；MySQL Connector/J 许可仍是 release blocker；M10 OPS-004 生产编排与 OPS P2/P3 归 M09/release；M11/M14 归档不等于完成。
first_read:
  - .riccon/state.json
  - AGENTS.md#project-contract
  - docs/PRODUCT.md
  - docs/ARCHITECTURE.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M25
  gate_checked_at: 2026-11-24T21:22:37.228+08:00
  release_gate_status: none
  product_acceptance_open: []
  active_tasks: 0
  checked_at: 2026-11-24T21:32:17.958+08:00
created_at: 2026-11-24T21:32:17.958+08:00
---

# HANDOFF-0017

## Summary

M06 编排 Studio 已被用户选为下一模块，但尚未开始规划：用户要求先 handoff、新会话再处理 M06；先前启动的 Product 草案代理已取消，未写 docs/PRODUCT.md，工作树 clean，没有创建 M06 模块/任务/决策。当前基线：团队内核 0.1.15；M25 done+user accepted（DEC-0044=A）且 gate pass（0 errors/2 warnings）；M10 5 条 P1 已关闭，gate pass（0 errors/33 warnings），新证据 EV-1015、新评审 REV-M10-003/REV-TASK-0064-002/REV-TASK-0065-003；M11/M14 按 DEC-0045=A 归档为 blocked legacy docs-only，不声称完成/验收。M06 依赖 M02/M04/M05 均 done；合同 roadmap M06 仍 planned，尚未创建模块。当前 state.current_module=M16 只是下一个未归档 planned 模块占位，不要启动。

## Next

新会话运行 `node ~/.config/opencode/riccon/scripts/riccon.mjs status --project .`，然后从 M06 规划开始：spawn riccon/product 起草 M06 一页 brief/AC/non-goals/预算（只写 docs/PRODUCT.md 草案，不创建模块/任务、不写源码）；Lead 做完整性与合同一致性检查后，向用户提交 M06 范围/预算决策；批准后再让 Architect 评可行性/数据/安全、Verifier 检查 AC 可验证性，最后 module new 建 M06 与任务。不要启动 M16，也不要在批准前写实现代码。

## Invariants

- M06 尚未创建模块/任务/决策；Product 草案已取消，docs/PRODUCT.md 无未提交改动；不要从聊天历史推断 M06 已批准。
- M25 为 done + user accepted（DEC-0044=A），但 F-001..F-012 residual 未修复；M10 gate pass 依赖 REV-M10-003/REV-TASK-0064-002/REV-TASK-0065-003 与 EV-1015。
- M11/M14 仅按 DEC-0045=A 归档为 blocked legacy docs-only；没有模块 gate、没有 Product/用户验收，不得作为完成或 accepted 声称。
- 团队内核 0.1.15；AGENTS 托管区块与 project.json 一致；M25 road map 已改 done；contract validate --strict 0/0，state validate 0 errors/44 warnings。

## Risks

- M06 范围、AC、预算、架构可行性均未评估；该模块涉及 DAG 编辑器、流程版本、运行/审批、模板、权限/审计与 a11y，必须走完整 Product→用户→Architect/Verifier 链，不能直接开写。
- M06 依赖 M02/M04/M05 已 done，但现有 approval/run 原语是 J2 专用；DAG 执行器、版本模型、节点类型、模板共享与调度边界需 Architect 收敛，避免范围膨胀。
- M25 无 security EV；ADR-0023 仍 proposed；MySQL Connector/J 许可仍是 release blocker；M10 OPS-004 生产编排与 OPS P2/P3 归 M09/release；M11/M14 归档不等于完成。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：无未完成项
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `AGENTS.md#project-contract`
3. `docs/PRODUCT.md`
4. `docs/ARCHITECTURE.md`
