# TASK-0134 修复验证（2026-11-21T11:20+08:00）

> 非 EV。记录 F-003/F-004 修复事实、命令结果与并行任务影响；权威 EV 见
> `.riccon/evidence/TASK-0134/EV-0631.json`、`EV-0632.json`。

## 提交

- 修复提交：`493067a`（`fix(m04): 真实栈恢复路由与 J1 定位`），只改
  `e2e/real/tests/m04-quota-cost.spec.ts` 与 `scripts/real-stack/checks.mjs`。
- TASK-0134 baseline 前移到 `ff6c383`（TASK-0132 提交之后、本任务提交之前），
  使 `ff6c383..493067a` 只包含本任务两个文件。

## F-003：a11y 恢复路由真正保存

修复点：

- 保存前通过 `GET /api/v1/admin/model-routing` 读取 CHAT 链版本与顺序。
- 首次“下移→保存”等待 `PUT /api/v1/admin/model-routing/CHAT` 成功，断言
  `version = 初始+1`、顺序为交换后的预期顺序。
- 恢复对刚下移的模型（`#2`）执行“上移→保存”，等待第二次 PUT 成功，断言
  `version = 初始+2`、顺序回到初始。
- 再通过 API 与 DB `model_routing_entry`（`GROUP_CONCAT(LOWER(HEX(model_id)) ORDER BY position)`）
  双重复核；UI 成功提示按精确版本号断言，不再用通用正则命中上次提示。

验证：

- 定向 `M04-B11b`：3 passed（EV-0631，clean）。
- 定向后 DB 复核：CHAT 顺序 `primary → secondary → disabled`（seed 顺序），
  `model_routing.version = 3`（初始 1 + 2），`model.routing.updated` 审计恰好 2 条。
- **全量真实栈（修复前最后一次诊断）**：25 条浏览器用例全部 passed，其中
  `E2E-M04-02` passed（此前因顺序污染必失败）；日志 `full-run-diagnostic.log`。
- 定向 `M04-B11a`：4 passed（EV-0632；dirty 原因见下）。

## F-004：checks.mjs 的 J1 使用显式 run

修复点：

- checks.mjs 在既有 `alphaConversation` 上发送一条 J1 消息，从 SSE 解析 `runId`。
- 用 `waitFor` 轮询该 run 的详情直到 CHAT invocation `SUCCEEDED`，不再查询
  `agent_run ORDER BY created_at DESC` 的“最新 CHAT run”。
- DB `first_token_ms` 断言按 `run_id = UNHEX(REPLACE(<显式 runId>))` 过滤。

验证：

- 全量真实栈的 `checks.mjs` 已越过 J1 段，在 TASK-0133 尚未实现的
  `/api/v1/admin/model-gateway/switches` 处失败（`switches must be 200`，F-002，
  非本任务范围）；说明 F-004 的 J1 段通过。
- DB 证据：checks 创建的会话“跨租户验证会话”对应 run
  `01a1fc165e83707fb238eebe99e06c98` 为 `SUCCEEDED`，CHAT invocation `SUCCEEDED`，
  `first_token_ms = 79`。

## 并行任务影响

- `EV-0632`（M04-B11a 定向）录制时，并行任务 TASK-0133 在
  `apps/api/src/main/java/com/riccon/api/admin/model/gateway/**` 与
  `apps/api/src/test/.../gateway/**` 留下未跟踪文件，导致 `dirty=true`。
  这是并行写入而非本任务改动；TASK-0133 提交后应在 clean 提交重录。
- 全量真实栈当前仍会因 F-002（开关端点，TASK-0133 in_progress）在 checks 阶段
  exit 1；浏览器阶段已全绿。
