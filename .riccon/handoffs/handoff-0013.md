---
handoff_id: HANDOFF-0013
session: ses_dfcd7c85cffe2naBSbZEv7pvPF
summary: >-
  M05 知识库纵深已 done + 用户 accepted（DEC-0039=A）：gate PASS（REV-M05-002，0 errors），
  Product 预验收 pass（residual R-M05-01..05），E2E 52/52、a11y 18/18、真实栈 29/29、
  检索 p95 97.5ms（V14 FULLTEXT）。M23/M24 已 done + Product pass；M05b 53h 已批准并创建
  M25（split_from M05，alias M05b，11 个任务 TASK-0203..0213）。M05 成本 29.5h/104h。
next: |-
  1) 读 handoff-0013、ADR-0023 §12.1、PRODUCT §11、M25/M05 任务卡；运行 `riccon status --project .` 与 `riccon state validate --project .`。
  2) 设 M25 为 building；先启动 2 个任务：M25-B2b 契约（TASK-0203，owner riccon/builder-kb-contract）与 M25-O2a 出网 allowlist（TASK-0210，owner riccon/ops）。不要同时超过 3 个 builder，owner 唯一。
  3) B2b + O2a 完成后启动 B6（TASK-0204，builder-kb-connector）；B6 完成后 B7b（TASK-0205）→ B8b/B9b/B10b/B11b（TASK-0206..0209）→ V1b/V2b（TASK-0212/0213）。O2b（TASK-0211）在 V1b/V2b 前。
  4) M25 契约只写 1.5.0-m05b，唯一 writer 是 TASK-0203；其他任务不得改 contracts/**。
  5) M25 gate 用最终提交全量合同证据（test/coverage/E2E/a11y/security + 连接器真实栈/零出网）；M25 Product 预验收 pass 后才能 done，用户 accepted 后才能启动 M06。
  6) ACL 写 p95 4.3–4.6s residual 由 Lead+Architect 跟踪，due M25 gate；如需修复，单开任务，不在 M25 内静默放宽。
invariants:
  - "M05 已 done + user accepted（DEC-0039=A）；M23/M24 done + Product pass（user pending，内部修复，引用既有决策）；M14/M11 legacy 保留。"
  - "M05b 预算 53h 已批准（DEC-0039=A）；M25 split_from M05、alias M05b、wall_clock_limit 53h；M25 契约 1.5.0-m05b，M05 契约 1.4.0-m05 不变。"
  - "V14 FULLTEXT/schema 14 已上线；V13 artifact chunk_id；M05 gate 证据 REV-M05-002；不要回退 V13/V14。"
  - "M05 范围/P6=A/P8=A/U8/M03-AC-06 supersession 由 DEC-0034；ACL=true 验收/回滚约束 DEC-0037；MySQL FULLTEXT DEC-0038；M05/M05b 验收与预算 DEC-0039。"
  - "M05 残余：ACL 写 p95 4.3–4.6s（quota 租户行锁，非聊天热路径）；force=false 仍整本重解析；宿主 Tesseract IT 未跑（O1b 覆盖）；V14 生产锁/物理清理 M09。"
risks:
  - "M25 连接器是 M05b 最高风险：SSRF/端点允许列表、凭据加密/掩码/30 天置空、删除传播、并发同步 409；B6 必须独立 verifier 评审。"
  - "ACL 写 residual 未修复；用户已在 DEC-0039 接受 M05，但 M25 gate 前必须决定修复或再次书面接受。"
  - "M04 F-008 pass-through、M14/M11 legacy、DEC-0003 MySQL Connector/J 许可仍为发布遗留；worker 覆盖率 70.01% 仅高于门槛 0.01pp。"
  - "并行 worktree 取证会撞 EV ID；本会话多次手工重编号。下一会话优先主树串行取证；如用 worktree，先审计全局 EV ID（max+1）再绑定。"
first_read:
  - .riccon/state.json
  - .riccon/handoffs/handoff-0012.md
  - AGENTS.md
  - docs/COLLABORATION.md
  - docs/adr/ADR-0023-m05-knowledge-depth.md
  - docs/PRODUCT.md
  - docs/ACCEPTANCE.md
  - docs/RUNBOOK.md
  - .riccon/modules/M25.json
  - .riccon/reviews/M05-review.md
  - .riccon/decisions/DEC-0039.md
  - .riccon/decisions/DEC-0035.md
quality_gate:
  status: pass
  gate_status: pass
  gate_module_id: M05
  gate_checked_at: 2026-11-24T01:39:20.871+08:00
  release_gate_status: none
  product_acceptance_open:
    - id: M14
      product_status: pending
      user_status: pending
      fixes: []
    - id: M23
      product_status: pass
      user_status: pending
      fixes: []
    - id: M24
      product_status: pass
      user_status: pending
      fixes: []
  active_tasks: 0
  checked_at: 2026-11-24T02:45:58.218+08:00
created_at: 2026-11-24T02:45:58.218+08:00
---

# HANDOFF-0013

## Summary

M05 知识库纵深已完成并获用户接受：

- **M05 done + user accepted**（DEC-0039=A）；gate PASS（REV-M05-002，0 errors），Product 预验收 pass（residual R-M05-01..05）。
- 交付：Excel 流式/区域引用、OCR/扫描 PDF/bbox、文档级 ACL（TENANT/DEPT/USERS/U8）、delete/reindex、流式下载、保留、审计、V13 artifact chunk_id、V14 MySQL FULLTEXT、Web ACL/引用/登记/429/a11y、注入 harness。
- 证据：E2E 52/52、a11y 18/18、真实栈 29/29、检索 p95 97.5ms（V14）、首字/API 达标；ACL 写 p95 4.3–4.6s residual。
- M23/M24 已 done + Product pass（内部修复，user pending）；M05 成本 29.5h/104h。
- **M05b 53h 已批准**，M25 已创建（11 任务 TASK-0203..0213）。

## Next

按顺序启动 M25；先 B2b + O2a，再 B6，再 B7b/B8b/B9b/B10b/B11b/O2b，最后 V1b/V2b。具体见 front matter `next`。

## Invariants

- M05 验收与 M05b 预算由 DEC-0039；M05 范围由 DEC-0034；ACL=true 回滚约束 DEC-0037；FULLTEXT DEC-0038。
- M25 契约 1.5.0-m05b；M05 契约 1.4.0-m05 不变；V13/V14 不回退。
- M25 不修改 M05 已验收行为；连接器端点允许列表/凭据/删除传播必须 T4 评审。

## Risks

- 连接器 SSRF/凭据/删除传播；ACL 写 residual；M04 F-008/M14/M11/DEC-0003 发布遗留；并行 worktree EV ID 冲突。

## 上下文

- 触发原因：M05 gate/用户验收完成、M05b 预算批准、上下文预算。
- 质量门槛：gate=pass（M05）、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 M25 gate、review 或用户批准；M23/M24/M14 user acceptance 仍 pending。
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `.riccon/state.json`
2. `.riccon/handoffs/handoff-0012.md`
3. `AGENTS.md`
4. `docs/COLLABORATION.md`
5. `docs/adr/ADR-0023-m05-knowledge-depth.md` §12.1
6. `docs/PRODUCT.md` §11
7. `docs/ACCEPTANCE.md`
8. `docs/RUNBOOK.md`
9. `.riccon/modules/M25.json`
10. `.riccon/reviews/M05-review.md`
11. `.riccon/decisions/DEC-0039.md`
12. `.riccon/decisions/DEC-0035.md`
