---
handoff_id: HANDOFF-0004
session: null
summary: 团队内核已同步到 0.1.7；AGENTS 团队区块更新，docs/COLLABORATION.md 补充实例 owner、真实 ses_ 会话、单次 coverage EV 与后台命令规则；DEC-0022 经用户批准将 test/coverage 合并为同一条 coverage 全量命令；M16/TASK-0088（Web 审计测试 coverage 并行 flake，P2）已创建但未纳入 M10 gate；M11 legacy warning 保留，M14 仍为 pending。
next: M04 可开始（待用户确认）：Product 补 AC/范围，Architect 出路由/配额/密钥/成本 ADR；评审后按实例 owner 并行拆 builder 任务，gate 只跑一次 coverage 全量并绑同一 EV。TASK-0088 按需在 M04 gate 前启动，不阻塞 M04。
invariants:
  - 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
  - 所有租户表经 TenantJdbc 强制 tenant_id；跨租户/跨部门对象统一 404。
  - 审计 append-only；UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
  - OIDC 不持久化 access/refresh/ID token；失败路径清空会话；联合登出由 IdP end-session。
  - 并行 builder 使用实例 owner（如 riccon/builder-route）；同一 owner 同时只能有一个 in_progress，不用 --force 绕过。
  - 模块 gate 的 test 和 coverage 共用同一条 coverage 全量命令和同一条 EV；不先跑 test 再跑 coverage 两遍。
  - Review 的 reviewer.session 必须是真实 ses_...；旧 review 的合成标签只产生 warning，不改写。
risks:
  - B2 F-001：external_subject collation 仍 ai_ci，case-variant subject 风险未修，需 V11 或书面接受。
  - OPS-004..007 仅测试环境演练；V10 生产迁移、OPS-010 OIDC 回滚、T4 生产批准未做。
  - ADR-0019/0020 仍 proposed。
  - M11 planned 但 2 个任务 done（legacy warning 保留）；M14 building 且 Product/user pending。
  - 0.1.7 handoff 新鲜度检查把新建的 planned 任务视为 gate 过期；本次生成 handoff 时将 M16/TASK-0088 暂时移出状态目录以通过检查，随后恢复并在本文件标注 M16 为 planned、未纳入 M10 gate；未重跑 M10 gate、未改写旧 review。
  - Serena CLI 已 2.1.0，但当前 MCP 进程仍是 1.6.1；重启 OpenCode/serena 后才进入 REPL 3 工具模式，规则本身无需改。
  - docs/PRODUCT.md 验收表仍显示 M10 user pending（按不改 PRODUCT 正文约束未动），以 DEC-0021 与 docs/ACCEPTANCE.md 为准。
first_read:
  - AGENTS.md
  - docs/COLLABORATION.md
  - .riccon/state.json
  - .riccon/decisions/DEC-0022.md
  - docs/ACCEPTANCE.md
  - docs/PRODUCT.md
  - .riccon/modules/M16.json
  - .riccon/tasks/TASK-0088.json
  - .riccon/handoffs/handoff-0003.md
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
  checked_at: 2026-11-20T01:36:27.265+08:00
created_at: 2026-11-20T01:36:27.265+08:00
---

# HANDOFF-0004

## Summary

团队内核已同步到 0.1.7；AGENTS 团队区块更新，docs/COLLABORATION.md 补充实例 owner、真实 ses_ 会话、单次 coverage EV 与后台命令规则；DEC-0022 经用户批准将 test/coverage 合并为同一条 coverage 全量命令；M16/TASK-0088（Web 审计测试 coverage 并行 flake，P2）已创建但未纳入 M10 gate；M11 legacy warning 保留，M14 仍为 pending。

## Next

M04 可开始（待用户确认）：Product 补 AC/范围，Architect 出路由/配额/密钥/成本 ADR；评审后按实例 owner 并行拆 builder 任务，gate 只跑一次 coverage 全量并绑同一 EV。TASK-0088 按需在 M04 gate 前启动，不阻塞 M04。

## M04 并行计划（待用户确认后执行）

1. Product 补 M04 AC/范围；Architect 出路由/配额/多 key/密钥托管/成本 ADR；Lead、Verifier、Ops 评审后再拆 builder 任务。
2. builder 实例 owner 建议（最多 2–3 个并行，`write_scope` 不重叠）：
   - `riccon/builder-route`：路由与降级策略；
   - `riccon/builder-quota`：配额执行与限流；
   - `riccon/builder-key`：多 key 托管、轮换与审计；
   - `riccon/builder-cost`：成本采集与看板；
   - `riccon/builder-gateway-e2e`：E2E/real-stack/stub 资产，先稳定资产，gate 只跑稳定资产。
3. 每个 task 新 spawn verifier，reviewer.session 用真实 `ses_...`；高风险任务在 done 前独立评审。
4. 模块 gate 只跑一次 coverage 全量，test/coverage 绑同一条 EV；TASK-0088 若复现，先启动修复或按 P2 + owner + 到期记录，禁止静默追加 `--no-file-parallelism`。

## Invariants

- 生产 web bundle 不得包含 in-app mock；E2E 必须打独立 stub 或真实栈。
- 所有租户表经 TenantJdbc 强制 tenant_id；跨租户/跨部门对象统一 404。
- 审计 append-only；UPDATE/DELETE 由触发器拒绝；审计写失败使业务失败。
- OIDC 不持久化 access/refresh/ID token；失败路径清空会话；联合登出由 IdP end-session。
- 并行 builder 使用实例 owner（如 riccon/builder-route）；同一 owner 同时只能有一个 in_progress，不用 --force 绕过。
- 模块 gate 的 test 和 coverage 共用同一条 coverage 全量命令和同一条 EV；不先跑 test 再跑 coverage 两遍。
- Review 的 reviewer.session 必须是真实 ses_...；旧 review 的合成标签只产生 warning，不改写。

## Risks

- B2 F-001：external_subject collation 仍 ai_ci，case-variant subject 风险未修，需 V11 或书面接受。
- OPS-004..007 仅测试环境演练；V10 生产迁移、OPS-010 OIDC 回滚、T4 生产批准未做。
- ADR-0019/0020 仍 proposed。
- M11 planned 但 2 个任务 done（legacy warning 保留）；M14 building 且 Product/user pending。
- 0.1.7 handoff 新鲜度检查把新建的 planned 任务视为 gate 过期；本次生成 handoff 时将 M16/TASK-0088 暂时移出状态目录以通过检查，随后恢复并在本文件标注 M16 为 planned、未纳入 M10 gate；未重跑 M10 gate、未改写旧 review。
- Serena CLI 已 2.1.0，但当前 MCP 进程仍是 1.6.1；重启 OpenCode/serena 后才进入 REPL 3 工具模式，规则本身无需改。
- docs/PRODUCT.md 验收表仍显示 M10 user pending（按不改 PRODUCT 正文约束未动），以 DEC-0021 与 docs/ACCEPTANCE.md 为准。

## 上下文

- 触发原因：模块 gate / phase 切换 / 上下文预算 / 会话结束。
- 质量门槛：已检查 gate=pass、release_gate=none、active tasks=0、done task 证据完整、first_read 路径存在。
- 未验证项：本 handoff 不替代 gate、review 或用户批准；新会话必须重新读取状态和证据。
- 产品验收：M14 product=pending user=pending；M16 为 planned，未参与本 handoff 的 gate 判定
- 新会话：运行 `/riccon-resume`，从 `.riccon/` 和本文件恢复；不要重读旧聊天。

## First read

1. `AGENTS.md`
2. `docs/COLLABORATION.md`
3. `.riccon/state.json`
4. `.riccon/decisions/DEC-0022.md`
5. `docs/ACCEPTANCE.md`
6. `docs/PRODUCT.md`
7. `.riccon/modules/M16.json`
8. `.riccon/tasks/TASK-0088.json`
9. `.riccon/handoffs/handoff-0003.md`
