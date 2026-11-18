# Worker Agent 运行时（M02 / TASK-0016）

本文件描述 `apps/worker` 中模型网关、ReAct Agent、MCP 与 HITL 的内部约束，供评审与后续任务使用；合同、ADR 与 `contracts/` 仍是唯一事实源。

## 组成

| 包         | 职责                                                          |
| ---------- | ------------------------------------------------------------- |
| `crypto`   | 主密钥校验、AES-256-GCM 信封解密；启动 fail-closed            |
| `audit`    | 模型/MCP/租户/用户拒绝审计，append-only 写入                  |
| `model`    | 全局模型目录、平台级凭据解析、OpenAI 兼容流式客户端、调用记录 |
| `agent`    | 知识库检索工具、不可信数据边界、对话与报告 Agent 构建         |
| `mcp`      | MCP 目录/授权、stdio/HTTP 客户端工厂、全量审批调用网关        |
| `chat`     | HMAC 服务令牌、SSE 事件、内部流式端点、取消端点               |
| `research` | requested/resume/cancel outbox 处理器、审批与阶段持久化       |

## 不变量

- 服务令牌：`riccon1.<Base64Url(payload)>.<Base64Url(HMAC)>`，校验签名、`iss/aud/scope/iat/exp/jti`、运行绑定与 300 秒上限。
- 租户：所有业务 SQL 经 `TenantJdbc`；平台凭据只在显式 `platform` 作用域内读取；跨租户运行不可见统一 404（ADR-0009），令牌与请求体租户不一致返回 400。
- 用户：每次执行前校验 `app_user.status=ACTIVE`；禁用用户写 `user.access.denied` 审计并拒绝。
- 密钥：主密钥缺失/长度错误/已知测试密钥时启动失败；API key 只进入 `Authorization` 头；异常与日志不含密钥、密文、nonce 或 DEK。
- 提示注入：系统指令为静态常量；检索/文件/工具结果用 `<<<UNTRUSTED_DATA>>>` 边界作为独立消息传入，不拼入系统指令。
- HITL：MCP 工具必须 READ、在平台白名单、租户已授权、`requires_approval=TRUE` 且存在审批 ID；resume 阶段再校验审批为 APPROVED 并与 run/task 绑定。
- SSE：帧格式 `id/event/data`；事件字段严格对应 `chat-event.v1`；首字前失败返回 ProblemDetail，首字后失败发送 `riccon.chat.failed.v1`；取消发送 `riccon.chat.stopped.v1` 并保留已生成内容。
- 模型流式：`OpenAiCompatibleChatModel.stream` 的 HTTP 读循环是阻塞的，必须经 `subscribeOn(boundedElastic)` 在独立线程执行。阻塞式消费方（`Flux.toIterable().iterator()` 会在 `subscribe` 内同步执行生成器）否则要等整段模型流结束才拿到第一个元素，表现为“所有 delta 在模型流结束后一次性到达”。取消时通过 `sink.onCancel/onDispose` 关闭响应体，避免阻塞的 `readLine` 占住线程。
- 首字指标：`model_invocation.first_token_ms` 记录第一个可见 delta 写入 SSE 的时间，不是流结束时间；返回值应显著小于 `duration_ms`。

## 状态机与恢复

- 对话流不走 outbox；运行从 `PENDING/RETRY_WAIT` 条件推进到 `RUNNING`，防止并发双流。
- 报告任务：`RETRIEVAL → TOOL` 后模型请求 MCP 工具时创建 `approval(PENDING)`、写入 `APPROVAL` 阶段并置 `WAITING_APPROVAL`；outbox 事件完成但运行不标记成功。
- 审批通过：`resume` 事件执行已批准工具（写 `mcp_invocation` + 审计），完成 TOOL/APPROVAL 阶段，进入 GENERATION；当前任务只把生成摘要写入阶段 details，报告产物与任务状态由后续任务接管。
- 审批拒绝/过期：`resume` 将 APPROVAL 置 SKIPPED 并取消运行；`cancel` 事件取消 PENDING 审批与运行，并把运行下仍为 RUNNING 的阶段（常见为 TOOL/APPROVAL）补齐为 SKIPPED 并写 `finished_at`。API 拒绝路径会先把 run/task 置为 CANCELLED 再投递 `cancel`，因此取消处理器声明 `handlesTerminalRun()=true`：轮询器对终态运行仍会执行该处理器，但不再推进运行状态。

## 已知边界与后续动作

1. **审批参数存储**：`approval.params_preview` 保存规范化、≤8000 字符、不含敏感键的完整参数 JSON，以便审批后精确重放；这与 ADR-0007 的“脱敏摘要”字面描述有偏差，需在下一次契约/ADR 评审中确认或新增专用列。
2. **跨租户状态码**：为避免新增跨租户业务读取（ADR-0016 只允许 outbox 领取例外），跨租户运行返回 404 而不是 403；令牌租户与请求体不一致返回 400。
3. **报告编排**：research_task 状态与 artifact/引用清单不在本任务范围；GENERATION 只记录摘要，后续任务替换为报告生成与保存。
4. **模型调用粒度**：对话按流记录一次调用；报告按阶段记录一次调用。若 ReAct 在单阶段内多次调用模型，调用记录不会逐次拆分；后续可用 `Model` 装饰器统一处理。
5. **信封加密位置**：ADR-0006 规划在 `platform-common` 提供 `MasterKeyProvider/EnvelopeEncryption`；当前任务写入范围仅 `apps/worker`，因此实现位于 Worker，后续应下沉或由决策确认。
6. **MCP 审批校验**：`McpGateway` 只校验审批 ID 存在，审批状态与 run/task 绑定由 `AgentRunResumeHandler` 校验；新增直接调用方必须复用该路径。
7. **SSE 首字超时**：使用 60 秒事件空闲超时覆盖首字等待；3 秒首字预算是性能测试目标，不在 Worker 内硬编码。
