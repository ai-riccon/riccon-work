# TASK-0134 修复验证（2026-11-21T11:38+08:00）

> 非 EV。记录 F-003/F-004 与开关 API 切换的修复事实与最终全量结果；
> 权威证据：`.riccon/evidence/TASK-0134/EV-0635.json`（clean，commit `dc95e6c`）。

## 提交

- `493067a` `fix(m04): 真实栈恢复路由与 J1 定位`
- `dc95e6c` `test(m04): B11b 使用开关 PUT 并全量真实栈`
- TASK-0134 baseline `ff6c383`（TASK-0132 提交之后、本任务首个提交之前），
  `end_commit=dc95e6c`；`ff6c383..dc95e6c` 只含本任务两个文件。

## F-003：a11y 恢复路由真正保存

- 保存前 `GET /api/v1/admin/model-routing` 读取 CHAT 版本与顺序。
- 两次“键盘调整→保存”均 `waitForResponse(PUT /api/v1/admin/model-routing/CHAT)`，
  分别断言 `version=初始+1`、`初始+2` 与对应顺序；成功提示按精确版本号断言。
- 恢复目标为刚下移模型在 `#2` 的“上移”按钮，避免 `.first()` 命中不可用按钮。
- 恢复后 API 与 DB `model_routing_entry` 双重复核；定向运行后 DB 为
  `primary → secondary → disabled`，`version=3`，`model.routing.updated` 恰好 2 条。
- 全量真实栈 25 条浏览器用例全部通过，`E2E-M04-02` 通过，不再因顺序污染失败。

## F-004：checks.mjs 的 J1 使用显式 run

- checks 在既有会话上发送 J1 消息，从 SSE 解析 `runId`，轮询该 run 直到 CHAT
  invocation `SUCCEEDED`；DB `first_token_ms` 按该 `run_id` 过滤。
- 全量真实栈 `securityChecks/m04Checks=passed`；checks 内
  `model.gateway.switch.changed`、`quota.exceeded` 等断言均通过。

## 配额开关改用 GET/PUT（TASK-0133 后端）

- 删除“API 未提供开关写端点、直接改 SQL”的捷径；`e2e/support/m04-quota-cost.ts`
  新增 `readGatewaySwitches` / `setQuotaEnforcement` / `restoreGatewaySwitches`：
  - `GET /api/v1/admin/model-gateway/switches` 读取 `version`、原值与 `source`；
  - `PUT` 整组保存，断言 `version=读到的版本+1` 且 `quotaEnforcementEnabled` 为目标值；
  - `source=ENVIRONMENT` 或 409 时以明确错误报告覆盖键/错误码，不静默跳过；
  - 用例 `finally` 恢复 `routingMode/failoverEnabled/quotaEnforcementEnabled` 原值。
- 全量运行后 DB：三个开关 `version=5`、`quotaEnforcementEnabled=false`；
  `model.gateway.switch.changed` 恰好 2 条（B11b true→false），checks 的重复保存不制造多余审计。

## 最终全量（EV-0635）

命令：`REAL_EVIDENCE_TASK=TASK-0134 node scripts/real-stack/run.mjs --mode=full`

```text
compose passed / fingerprint passed / seed passed / seedIdempotent passed
m10Checks passed / browserE2e passed (25 passed) / j2Journey passed
securityChecks passed / m04Checks passed / m03Checks passed
status: passed
```

## 被替代证据

- EV-0631（B11b 定向，clean）：代码在 `493067a`，仍是 SQL 开关捷径，已被 EV-0635 替代。
- EV-0632（B11a 定向，dirty）：录制时并行 TASK-0133 未提交文件导致 dirty；
  TASK-0133 已提交 `ffd12ac`，且全量 EV-0635 已覆盖 B11a。
- 二者归档在 `.riccon/archive/evidence-superseded/TASK-0134/`。
