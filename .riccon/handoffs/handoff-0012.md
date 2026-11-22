---
handoff_id: HANDOFF-0012
session: ses_dfda6b3ffffeFZXs5EMiSpozcX
summary: M05 规划完成：DEC-0034（拆分/P6=A/P8=A/U8/M03 supersession/技术默认值）与 DEC-0035 Stage 1（M05 104h + M23 6h + M24 20h = 130h；M05b 53h 待 M05 用户验收后另批）。ADR-0023 proposed、Product §11、THREAT-MODEL TM-55..71、ACCEPTANCE M05 规划段已提交（HEAD 1a57d5b）。已创建 M05（14 任务 TASK-0164..0177）、M23（TASK-0163）、M24（TASK-0157..0162）；全部 planned，无实现、无 dirty 证据。
next: |-
  1) 读 handoff-0012、ADR-0023 §12.1、PRODUCT §11、M05/M23/M24 任务卡；运行 status 与 state validate。
  2) 设 M23/M24/M05 为 building；启动 3 个 builder：M23/TASK-0163（riccon/builder-identity）、M24/TASK-0158 或 TASK-0157（riccon/builder-key）、M05/TASK-0164 B1（riccon/builder-kb-core）。不要同时超过 3 个 builder，owner 唯一。
  3) B1 完成后按 ADR 依赖启动 B2/B3/B4/B5/O1a；B8 必须等 M24/TASK-0160（web flake）done，避免 apps/web 写冲突。
  4) M05 gate 前必须 M23/M24 全部 done；模块 gate 用最终提交全量合同证据，M05 检索 p95 量测>500ms 时先走用户决策，不得静默放宽。
  5) M05 Product 预验收 pass 后才能 module done；用户 accepted 后才创建 M05b（新模块 ID M25，split_from M05，alias M05b，53h，任务见 ADR §12.1）。
invariants:
  - M05 范围/AC/拆分/默认值由 DEC-0034 批准；Stage 1 预算 130h 由 DEC-0035 批准，M05b 53h 未批准；M05 超 125h、M24 超 24h、M23 超 8h 暂停。
  - P6=A 文本块/坐标，不新增页图端点；P8=A 软删除+索引 DELETE，物理清理归 M09/B-26 release blocker；U8 仅 TENANT_ADMIN 管理性读取，绕过 ACL 写 admin_read；M03-AC-06 由 M05 取代。
  - M05 契约 1.4.0-m05 只含 ACL/下载/解析/引用/删除/重索引；连接器契约/UI 归 M05b；M05 gate 不以 M05b 段判 pass。
  - M05 gate 前置债务：M23（M13 F-001）、M24（M04 F-006/F-008/F-010/F-012、M22 F-001、M20 F-004、R-M04-03）；M22 F-002、ADR-0022 摘要、F-013 文档已关闭。
risks:
  - OCR 镜像/权重与零出网 ocr-check 是 M05 B5/V1 的前置；O1a 必须先于 B5。下载流式代理需改 FileStorage 端口，B3 已含 scope。
  - 检索 p95 在 10 万 chunk/50 并发下未量测；>500ms 无用户决策则 M05 gate 不通过。V1/V2 已加时到 8+8h，仍偏紧。
  - M05b 连接器（S3）未创建；M05 用户验收前不得开工。M14/M11 legacy、DEC-0003 MySQL 驱动许可仍为发布遗留。
first_read:
  - .riccon/state.json
  - .riccon/handoffs/handoff-0011.md
  - AGENTS.md
  - docs/COLLABORATION.md
  - docs/adr/ADR-0023-m05-knowledge-depth.md
  - docs/PRODUCT.md
  - docs/ARCHITECTURE.md
  - docs/ACCEPTANCE.md
  - docs/security/THREAT-MODEL.md
  - .riccon/modules/M05.json
  - .riccon/modules/M23.json
  - .riccon/modules/M24.json
  - .riccon/decisions/DEC-0034.md
  - .riccon/decisions/DEC-0035.md
  - .riccon/archive/reviews/M05-plan-pre-review-001.md
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
  checked_at: 2026-11-22T19:26:03.601+08:00
created_at: 2026-11-22T19:26:03.601+08:00
---

# HANDOFF-0012

## Summary

M05 规划完成：DEC-0034（拆分/P6=A/P8=A/U8/M03 supersession/技术默认值）与 DEC-0035 Stage 1（M05 104h + M23 6h + M24 20h = 130h；M05b 53h 待 M05 用户验收后另批）。ADR-0023 proposed、Product §11、THREAT-MODEL TM-55..71、ACCEPTANCE M05 规划段已提交（HEAD 1a57d5b）。已创建 M05（14 任务 TASK-0164..0177）、M23（TASK-0163）、M24（TASK-0157..0162）；全部 planned，无实现、无 dirty 证据。

## Next

1) 读 handoff-0012、ADR-0023 §12.1、PRODUCT §11、M05/M23/M24 任务卡；运行 status 与 state validate。
2) 设 M23/M24/M05 为 building；启动 3 个 builder：M23/TASK-0163（riccon/builder-identity）、M24/TASK-0158 或 TASK-0157（riccon/builder-key）、M05/TASK-0164 B1（riccon/builder-kb-core）。不要同时超过 3 个 builder，owner 唯一。
3) B1 完成后按 ADR 依赖启动 B2/B3/B4/B5/O1a；B8 必须等 M24/TASK-0160（web flake）done，避免 apps/web 写冲突。
4) M05 gate 前必须 M23/M24 全部 done；模块 gate 用最终提交全量合同证据，M05 检索 p95 量测>500ms 时先走用户决策，不得静默放宽。
5) M05 Product 预验收 pass 后才能 module done；用户 accepted 后才创建 M05b（新模块 ID M25，split_from M05，alias M05b，53h，任务见 ADR §12.1）。

## Invariants

- M05 范围/AC/拆分/默认值由 DEC-0034 批准；Stage 1 预算 130h 由 DEC-0035 批准，M05b 53h 未批准；M05 超 125h、M24 超 24h、M23 超 8h 暂停。
- P6=A 文本块/坐标，不新增页图端点；P8=A 软删除+索引 DELETE，物理清理归 M09/B-26 release blocker；U8 仅 TENANT_ADMIN 管理性读取，绕过 ACL 写 admin_read；M03-AC-06 由 M05 取代。
- M05 契约 1.4.0-m05 只含 ACL/下载/解析/引用/删除/重索引；连接器契约/UI 归 M05b；M05 gate 不以 M05b 段判 pass。
- M05 gate 前置债务：M23（M13 F-001）、M24（M04 F-006/F-008/F-010/F-012、M22 F-001、M20 F-004、R-M04-03）；M22 F-002、ADR-0022 摘要、F-013 文档已关闭。

## Risks

- OCR 镜像/权重与零出网 ocr-check 是 M05 B5/V1 的前置；O1a 必须先于 B5。下载流式代理需改 FileStorage 端口，B3 已含 scope。
- 检索 p95 在 10 万 chunk/50 并发下未量测；>500ms 无用户决策则 M05 gate 不通过。V1/V2 已加时到 8+8h，仍偏紧。
- M05b 连接器（S3）未创建；M05 用户验收前不得开工。M14/M11 legacy、DEC-0003 MySQL 驱动许可仍为发布遗留。

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
5. `docs/adr/ADR-0023-m05-knowledge-depth.md`
6. `docs/PRODUCT.md`
7. `docs/ARCHITECTURE.md`
8. `docs/ACCEPTANCE.md`
9. `docs/security/THREAT-MODEL.md`
10. `.riccon/modules/M05.json`
11. `.riccon/modules/M23.json`
12. `.riccon/modules/M24.json`
13. `.riccon/decisions/DEC-0034.md`
14. `.riccon/decisions/DEC-0035.md`
15. `.riccon/archive/reviews/M05-plan-pre-review-001.md`
