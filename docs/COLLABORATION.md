# 协作约定

本文件是 `AGENTS.md` 索引出来的详细协作规则，由 Riccon 团队维护。开始或恢复任何模块前必须读；`AGENTS.md` 中的硬规则和 Project Contract 优先于本文件。

## 1. 角色边界

- **Lead**：合同、状态、计划、门禁、决策、交接；不写生产源码。只有 Lead 可以 spawn subagent 或直接问用户。
- **Product**：目标、用户、MVC、验收标准、UX 流程、文案；不写生产源码。Product 不替代 Verifier，也不替用户做最终验收。
- **Architect**：技术栈、架构、数据模型、API/事件契约、ADR、初始脚手架；不写功能代码。
- **Builder**：在任务 `write_scope` 内写生产源码、测试、迁移和代码级文档；不自行把任务标为 done。
- **Verifier**：独立验收/E2E、代码审查、安全基线、评审报告；不修改生产源码。
- **Ops**：按需负责 CI/CD、容器、预览/生产部署、可观测性和回滚；不写功能代码。

任何人不能自我验收。高风险任务（认证、权限、迁移、并发、安全、AI 工具调用）在 done 前必须有独立 Verifier 评审。

## 2. 开始与恢复

1. 读 `.riccon/state.json`、最新 handoff、`AGENTS.md` 的 Project Contract 和本文件。
2. 运行 `riccon state validate`；有 error 先停下来修状态。
3. 读模块、任务、决策和评审；不要从聊天历史推断状态。
4. 新会话运行 `/riccon-resume`；需要切换会话时先写 handoff。

合同是技术栈、命令、工具和约束的唯一事实源。合同里没有的目录、命令或工具不要假设存在。

## 3. 模块与任务

- 模块是带用户可见结果的纵向切片；MVC 是第一个模块。
- 依赖全部 done，且前一个用户可见模块的 Product/用户验收完成后，下一个用户可见模块才能开始。修复模块用 `kind: fix` + `fixes_module`，不受“源模块未 accepted”阻挡。
- 模块状态：`planned` → `building` → `verifying` → `review_pending` → `done`；活动状态可进入 `blocked`。
- 模块进入 `review_pending` 前，所有任务必须 done。
- 任务进入 `in_progress` 前，模块必须是 `building`，依赖必须全部 done。
- 任务过大时在开始前拆分：`write_scope` 超过 8 项或 acceptance 达到 6 项是提示；最终按“一个可独立验收的用户可见结果”判断。
- 模块拆分用 `split_from`/`aliases` 记录；模块 ID 仍是 `M` + 数字。验收失败用 `kind: fix` + `fixes_module`，不要只靠标题或 `depends_on`。

## 4. 验收顺序

1. 所有任务 done，证据完整。
2. 运行 `riccon gate check --module Mxx`，gate 必须 pass。
3. gate 通过后、模块 done 前，Product 按 `docs/PRODUCT.md`、UX 和范围做预验收。
4. 用 `riccon module update Mxx --product-status pass` 记录 Product 结论；未 pass 不能 done。
5. `riccon module update Mxx --status done`。
6. done 之后再做用户最终验收，用 `--user-status accepted|rejected|conditional` 记录；`accepted` 必须同时满足 Product pass。
7. 用户未 accepted 前不启动下一个用户可见模块。

详细 AC 表和证据写在 `docs/ACCEPTANCE.md`；模块的 `product_acceptance` 只保存状态、决策 ID 和引用。验收到失败时先记录 `changes-requested`/`rejected`，再建修复模块；不要静默回滚已 done 模块。

## 5. 评审

- 模块评审：`.riccon/reviews/<module>-review.md`，由 `review.schema.json` 校验，参与模块 gate。
- 任务级/架构级独立评审：`.riccon/reviews/tasks/<task>-review.md`，由 `task-review.schema.json` 校验；不参与模块 gate，由 Lead 在任务 closeout 核对。
- Review 必须绑定当前模块、最新证据，reviewer 独立，verdict 为 pass。
- AC 对账必须引用 AC 原文的用户可见结果；AC 要求界面、文案或入口时，API 证据不能单独判 pass，必须绑定 UI/E2E 证据。
- P0/P1 必须修复；P2/P3 需要书面接受。不要改 review 来强行通过 gate。

## 6. 证据与测试

- 证据是命令、退出码、日志、commit 和 hash，不是“我跑过了”。
- 先提交，再 `riccon evidence run`；strict 下 `dirty: true` 的证据会让 gate 失败。
- 空命令、`not-implemented`、`true`/`echo`/`sleep`、未加引号的管道、`|| true`、`; true`、后台 `&` 等掩盖失败的写法不能建立 done。
- 测试分层：任务级跑受影响测试；模块 gate 在最终提交上跑一次全量合同 test/coverage/E2E。高风险、跨模块契约和迁移任务在任务完成时跑全量。
- 同一份代码的全量验证只跑一次；提交后用 `riccon evidence run` 记录，不要先跑一遍再取证，也不要为取证新建临时工作树。
- 长测试设置超时并保留日志；超时是失败，不是通过。执行零个测试的测试命令是失败。
- 覆盖率默认指代码行/分支覆盖率，不是 AC 覆盖；门槛见合同 `constraints.coverage`，必须可由 `commands.coverage` 复现。
- 新项目由 Architect 在 Builder 开始前创建 `docs/CODING-STANDARDS.md`；Builder 必须遵循，Verifier 检查。

## 7. 文档、注释与时间

- 文档、注释、commit message、决策包、评审包和 handoff 默认中文。
- 代码注释必须中文；每个方法至少方法级注释；复杂业务流程有行级或段落注释；不写重复、无信息量的注释。
- 标识符、命令、API/字段名、错误码和日志键保持英文。
- 更新已有文档，不要制造平行文档；代码变更同步更新文档，或记录 no-impact 理由。
- 所有时间默认北京时间（`Asia/Shanghai`，UTC+08:00）；API/日志/UI 使用带偏移的 ISO 8601，数据库按合同/ADR。
- 模块结束用 `riccon module cost` 记录成本；全零且 `confidence` 不是 `unmeasured` 会被拒绝，0 不等于免费。

## 8. 安全与决策

- 不把密钥写进仓库、`.riccon/`、日志、任务卡、证据、提示词或 handoff。
- 产品范围、不可逆技术选择、生产发布、花钱和破坏性操作由用户拥有；其余授权给团队。
- 编辑合同、`.opencode/**`、`.git/**` 和写项目外目录前按权限规则询问。
- 生产发布、真实 PII、生产密钥和 T4 生产环境必须单独评审与批准。

## 9. 项目补充

项目特定规则写在这里，例如成本口径、数据库时区、验证分层或工具路由。项目补充不能削弱 `AGENTS.md` 的硬规则和 Project Contract。
