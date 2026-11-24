# 架构

## 评审状态

| 检查                        | 状态    | 记录                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Architect 草案              | pending | M06 ADR-0024 `proposed`（2026-11-25，draft；DEC-0046=A 已批准范围/拆分/预算，待 Lead/Verifier/Ops/安全评审）；M05 ADR-0023 `proposed`（2026-11-22，REV-M05-AC-001/002 架构项已修复；DEC-0034 已批准拆分与技术默认值，157h 预算待 DEC-0035）；M04 ADR-0021/0022 `accepted`（2026-11-20；REV-M04-ARCH-005 pass、REV-M04-OPS-006 pass；DEC-0024=B 单模块 48h 口径）；M10 ADR-0019/0020 `proposed`（REV-M10-ARCH-002 已通过评审，ADR 状态仍为 proposed）；M03 ADR-0018 `proposed`；M02 ADR-0001..0017 |
| Lead 完整性/合同一致性      | pass    | M04 设计检查通过（2026-11-20）：DEC-0024=B 单模块 48h、18 条 AC 全归 M04、17 个实施任务、3 builder 关键路径 ≈28h、契约保持 `1.3.0-m04` 全量；评审链收口：REV-M04-ARCH-005 pass、REV-M04-OPS-006 pass（ARCH-004 的 P1 已修复；F-014 已用 EV-0526 重录，F-015 随 TASK-0089 closeout 关闭）；M10 pass（REV-M10-ARCH-002）；M03 已通过                                                                                                                                                                |
| 用户批准（不可逆/高成本项） | pass    | M04 DEC-0023=A（范围、18 条 AC 与 OQ 默认值）+ DEC-0024=B（2026-11-20）：不拆，M04 单模块 48h，18 条 AC 全保留；V11/API 主密钥+pepper/生产端点允许列表/准入即计费/默认限额/回滚窗口/单币种/Prometheus 内网/AC-17 perf profile 批准；契约保持 `1.3.0-m04`。ADR-0021/0022 已转 `accepted`（设计评审口径，§8 17 个实施任务）；M04 模块 gate 与用户最终验收仍未进行。DEC-0013/0014/0015/0017 已确认产品口径；ADR-0019/0020 仍 proposed；DEC-0003 许可仍是 release blocker                             |
| Verifier 可测试性/安全边界  | pass    | M04 pass（REV-M04-ARCH-005，2026-11-20）：ARCH-004 的 P1（AC-02 归属）已修复并按 V1/V2 逐条复核；F-009/F-013 为历史证据项（accepted，模块 gate 须绑定最终实现的全量合同 EV）；REV-M04-ARCH-003/004 为历史轮次记录，REV-003 的拆分口径已被 DEC-0024=B 撤销；M10 pass（REV-M10-ARCH-002）；M03 pass                                                                                                                                                                                                 |
| Ops 可运维/可回滚           | pass    | M04 pass（REV-M04-OPS-006，2026-11-20：OPS-005 的 6 个 P2 全部 fixed；OPS-M04-057/058 两项 P3 已由 TASK-0099 关闭；REV-M04-OPS-004/005 为历史轮次记录）；M10 OPS 生产演练仍未完成；M09 Helm/备份/升级                                                                                                                                                                                                                                                                                             |
| 安全/合规评审（如适用）     | pass    | M04 设计安全评审 pass（2026-11-20）：安全 gate = B-18/B-19/B-20/B-21（THREAT §8）；B-21 含 `PERF-M04-001/002`、`OPS-M04-01` 与 RPO ≤24h/RTO ≤4h；TB-04、TM-39..TM-54、ABUSE-07..11、R-21..R-25、pepper 与 metrics 暴露不变；模块运行期证据待实现与 V2 重放，T4 生产安全评审与发布批准仍待 release                                                                                                                                                                                                 |

状态值：`draft`、`pending`、`pass`、`changes-requested`；不可逆或高成本决策未经用户批准，不进入实现。历史无记录用 `legacy`/`not-recorded`。

### M04 评审状态（ADR-0021 / ADR-0022）

| 检查                        | 状态 | 记录                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| --------------------------- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Lead 完整性/合同一致性      | pass | Lead 检查通过（2026-11-20）：DEC-0024=B 单模块 48h、18 条 AC 全归 M04、17 个实施任务、3 builder 关键路径 ≈28h、契约保持 `1.3.0-m04` 全量；评审链收口 REV-M04-ARCH-005 pass / REV-M04-OPS-006 pass（ARCH-004 的 P1 已修复；F-014 已用 EV-0526 重录，F-015 随 TASK-0089 closeout 关闭）                                                                                                                                                                                 |
| 用户批准（不可逆/高成本项） | pass | M04 DEC-0023=A（范围、18 条 AC 与 OQ 默认值）+ DEC-0024=B（2026-11-20）：不拆，M04 单模块 48h，18 条 AC 全保留；V11/API 主密钥+pepper/生产端点允许列表/准入即计费/默认限额/回滚窗口/单币种/Prometheus 内网/AC-17 perf profile 批准；契约保持 `1.3.0-m04`。ADR-0021/0022 已转 `accepted`（设计评审口径，§8 17 个实施任务）；M04 模块 gate 与用户最终验收仍未进行。DEC-0013/0014/0015/0017 已确认产品口径；ADR-0019/0020 仍 proposed；DEC-0003 许可仍是 release blocker |
| Verifier 可测试性/安全边界  | pass | M04 pass（REV-M04-ARCH-005，2026-11-20）：ARCH-004 的 P1（AC-02 归属）已修复；V1 判 AC-01、AC-03..16，V2 判 AC-02/17/18 并在最终提交独立全量复验；REV-M04-ARCH-003/004 为历史轮次记录（拆分口径已被 DEC-0024=B 撤销）；ADR-0021 §13 保留历次评审响应，§13.2 为 ARCH-005/OPS-006 收口                                                                                                                                                                                  |
| Ops 可运维/可回滚           | pass | M04 pass（REV-M04-OPS-006，2026-11-20：OPS-005 的 6 个 P2 全部 fixed；OPS-M04-057/058 两项 P3 已由 TASK-0099 关闭；REV-M04-OPS-004/005 为历史轮次记录）；M10 OPS 生产演练仍未完成；M09 Helm/备份/升级                                                                                                                                                                                                                                                                 |
| 安全/合规评审（如适用）     | pass | M04 设计安全评审 pass（2026-11-20）：安全 gate = B-18/B-19/B-20/B-21（THREAT §8）；B-21 含 `PERF-M04-001/002`、`OPS-M04-01` 与 RPO ≤24h/RTO ≤4h；TB-04、TM-39..TM-54、ABUSE-07..11、R-21..R-25、pepper 与 metrics 暴露不变；模块运行期证据待实现与 V2 重放，T4 生产安全评审与发布批准仍待 release                                                                                                                                                                     |

> REV-M04-ARCH-003/004 与 REV-M04-OPS-004/005 是历史轮次记录（其中拆分/分段方案已被 DEC-0024=B 撤销），不再作为当前方案依据；当前评审依据为 REV-M04-ARCH-005 与 REV-M04-OPS-006。ADR-0021/0022 的 `accepted` 只覆盖设计评审；M04 模块 gate、用户最终验收与生产发布仍需 17 个实施任务、全量合同证据与 release 批准。

### M05 评审状态（ADR-0023）

| 检查                        | 状态    | 记录                                                                                                                                                                                                                                                                                                                                                 |
| --------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Architect 草案              | draft   | ADR-0023 `proposed`（2026-11-22，REV-M05-AC-001/002 架构项已修复）：Excel 流式、Tesseract OCR + SPI、文档级 ACL、S3 连接器、V12、索引 v2、outbox 双通道、TM-55..TM-71；DEC-0034：Product 拆分、P6=A、P8=A、U8=管理性读取+绕过 ACL 审计；预算 157h（M05 104 + M05b 53）                                                                               |
| Lead 完整性/合同一致性      | pass    | M05 检查通过（2026-11-22）：范围/AC/优先级与合同一致；DEC-0034 拆分与技术默认值已批准；157h 预算待用户 DEC-0035；M05 `1.4.0-m05` / M05b `1.5.0-m05b` 分段契约；`connector` 对象 key 类别需 ADR-0009 增量修订（M05b）                                                                                                                                 |
| 用户批准（不可逆/高成本项） | pass    | DEC-0034（2026-11-22）：Product 拆分、P6=A、P8=A（物理清理 M09 release blocker）、U8=管理性读取+绕过 ACL 审计、M03-AC-06 由 M05 取代；S3、Tesseract+SPI、权重随镜像、连接器原文可下载受 ACL、新文档默认 TENANT、ACL 后禁止应用回滚、429 并入 M05                                                                                                     |
| Verifier 可测试性/安全边界  | pass    | M05 pass（2026-11-22，REV-M05-AC-001/002 三轮）：403/404 矩阵、`SEC-KB-002/003`、`SEC-CONN-004`、delete/reindex、下载流式代理 `IT-KB-119`、`admin_read`、完整 list guard、tombstone、同步 start/finish、OCR 测试 profile/零出网、注入 harness B10/B10b、性能场景、套件 manifest 30→37/10→14；F-001..F-123 关闭；正式模块 gate 仍由独立 Verifier 重跑 |
| Ops 可运维/可回滚           | pending | 待 Ops 评审：M05-O1a/O1b（镜像/权重/SBOM/CI/`ocr-check`）与 M05-O2a/O2b（网络策略/凭据置空/RUNBOOK），V12 回滚演练，索引 v2 重灌/别名切换                                                                                                                                                                                                            |
| 安全/合规评审（如适用）     | pending | 待安全评审：提示注入新建 harness、SSRF、凭据、跨租户/跨部门、`admin_read` 审计、TM-55..TM-71（70/71 仅在 P6=B/AC-21=B 时适用，选 A 登记不适用）                                                                                                                                                                                                      |

> 状态值：`draft`、`pending`、`pass`、`changes-requested`。ADR-0023 未评审前不进入 M05 实现；本表不声称 M05 已批准或已交付。

### M06 评审状态（ADR-0024）

| 检查                        | 状态    | 记录                                                                                                                                                                                                   |
| --------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Architect 草案              | draft   | ADR-0024 `proposed`（2026-11-25）：`workflow_*` 9 张表（V15）、串行 + 条件求值、新建 `workflow_approval`、`WORKFLOW_EDITOR` 角色、`1.6.0-m06`、`workflow_run` 持久运行队列（TM-85 跨租户领取例外）、TM-72..TM-85、120h/48h 任务拆分 |
| Lead 完整性/合同一致性      | pending | 待 Lead 检查：23+2 条 AC 归属、120h + 48h 预算与 DEC-0046=A、V15、契约 `1.6.0-m06`；DEC-0047=A 已确认回滚口径、DEC-0048=A 已确认配额按运行 1 单位 |
| 用户批准（不可逆/高成本项） | pass | DEC-0046=A（2026-11-25）：拆分 M06 120h + M06b/M26 48h、25 条 AC、non-goals、角色模型、16 条 OQ 默认值、代码执行不开放；DEC-0047=A 确认 AC-23 回滚口径；DEC-0048=A 确认配额按运行准入 1 单位 |
| Verifier 可测试性/安全边界  | changes-requested | REV-M06-ARCH-001 fail（7 P1/8 P2/4 P3）；ADR §17.1 设计响应待复验；403/404、契约 schema、审批字段、工具策略、测试写范围仍需复核；`SEC-WF-001..006`、`TEN-M06-01`、`DEPT-M06-01` |
| Ops 可运维/可回滚 | changes-requested | REV-M06-OPS-001 fail（5 P1/8 P2 open + 2 accepted/1 P3）；ADR §17 设计响应待复验；队列参数/心跳池、指标、V15 联动断言、迁移失败处置、RUNBOOK 写范围已补；DEC-0047=A 已确认回滚口径，AC-23 文本由 Product 同步 |
| 安全/合规评审（如适用）     | pending | 待安全：TM-72..TM-85、B-27..B-30、提示注入/canary、工具参数加密/plan 绑定、审批理由入审计（U-05）                                                                                                      |

> ADR-0024 未评审前不进入 M06 实现；本表不声称 M06 已批准或已交付。M06b/M26 在 M06 用户验收后另行设计/评审。

## 背景

Riccon 是面向企业私有化部署的多租户 AI 协同办公系统。员工在云工作空间中与 Agent 协作完成办公任务；平台统一管理模型、知识库、MCP 与权限。风险等级 T4，架构必须优先保证租户隔离、审计、可回滚和密钥安全。

本文描述 M01 落地的架构基线。后续模块在此基线上纵向扩展，不重新引入第二套 Web 框架、第二套 ORM 或第二套 Agent 运行时。

## 架构原则

1. **模块化单体优先**：M02–M06 在同一个 Maven reactor 内按模块纵向切片，避免过早拆微服务带来的分布式事务、观测和部署成本。
2. **两个部署物，一套契约**：同步 HTTP 服务 `apps/api` 与异步 Agent 运行器 `apps/worker` 分开部署、分开扩缩容；两者只通过契约通信。
3. **Agent 运行时隔离**：`io.agentscope` 只允许出现在 `apps/worker`。`apps/api` 与 `platform` 由 ArchUnit 与 Maven Enforcer 双重禁止依赖。
4. **默认拒绝**：安全过滤器链默认拒绝一切，只显式放行健康检查；认证、租户解析与 RBAC 在 M02 通过独立过滤器链接入。
5. **向前兼容的数据库**：Flyway 只做向前迁移；破坏性变更必须有 ADR、回滚脚本和演练记录。
6. **无聊、成熟、可运维**：技术选型以团队熟悉度和私有化运维成本为先，不为未来两年设计。

## 组件

| 组件     | 路径                                     | 职责                                                             | 边界                                             |
| -------- | ---------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------ |
| API 服务 | `apps/api`                               | 同步 REST、认证会话、RBAC、租户解析、业务读写、审计入口、OpenAPI | 不得依赖 `io.agentscope`；不得直接执行 Agent     |
| Worker   | `apps/worker`                            | 异步 Agent 运行、模型调用、MCP 代理、HITL 编排、长任务           | 唯一允许依赖 `io.agentscope`；不直接面向浏览器   |
| 共享内核 | `platform/platform-common`               | 租户上下文、通用值对象、主密钥 SPI、schema 版本                  | 不得依赖 Spring Web、`apps/*` 或 `io.agentscope` |
| 领域词汇 | `platform/platform-domain`               | 跨部署物共享的状态枚举、角色/权限/审计动作/事件名常量            | 只含 JDK 类型；不得依赖 Spring、JPA、`apps/*`    |
| 架构测试 | `platform/platform-architecture-tests`   | 模块边界、分层、技术栈隔离的机器校验                             | 只含测试，不进入生产包                           |
| Web 前端 | `apps/web`                               | 员工端与管理端（TASK-0003 建立）                                 | 只调用 API，不直连 Worker、模型或 MCP            |
| 测试栈   | `deploy/compose/docker-compose.test.yml` | MySQL、OpenSearch、S3 兼容对象存储                               | 只用于本地与 CI，不是生产编排                    |

### 为什么是 api + worker 两个部署物

- **安全边界**：Agent 运行需要访问模型网关与 MCP，风险面远大于同步 CRUD；分开部署后可以对 Worker 做网络隔离、独立限流和独立审计。
- **扩缩容差异**：API 是低延迟短请求，Worker 是长任务与流式输出，资源模型不同。
- **故障隔离**：模型或 MCP 故障不应拖垮登录与业务 API。
- **可回滚**：任一部署物可以独立回滚，不需要同时回滚数据库与 Agent 运行时。

## 数据流

### 同步请求

```text
浏览器 -> apps/web -> apps/api
                        |-- 认证（会话 Cookie + CSRF） / 租户解析 / RBAC
                        |-- 部门数据范围（workspace 谓词；跨部门统一 404）
                        |-- 业务读写 -> MySQL（tenant_id 行级隔离 + 复合主键）
                        |-- 检索 -> MySQL 权威分块（OpenSearch 为可重建索引；M05 不切换检索后端）
                        |-- 产物/文件 -> S3 兼容对象存储
                        `-- 审计事件 -> audit_event（同事务，append-only）
```

### 身份与审计（M10）

```text
OIDC 登录（顶层浏览器导航）
浏览器 -> apps/api /auth/oidc/authorize/{registrationId}
        -> 企业 IdP（授权码 + PKCE + state/nonce）
        -> apps/api /auth/oidc/callback/{registrationId}
             |-- 会话内校验 state/nonce，换 token，校验 ID Token（iss/aud/exp/签名）
             |-- 丢弃 token；按 (tenant_id, issuer, subject) 查账号并检查启用
             |-- 轮换会话 ID/CSRF，写 RicconPrincipal 到 Spring Session
             `-- 审计 auth.login.succeeded/failed（同事务 fail-closed）

审计查询/导出
浏览器 -> apps/api /audit-events（只读，租户来自主体）
        -> TenantJdbc -> audit_event（时间范围 + 租户索引）
        -> 写 audit.queried
        -> /audit-exports 申请 -> 另一管理员审批 -> 申请人下载 CSV 流
        -> 写 audit.export.* / data.exported；导出不落盘
```

### Agent 任务

```text
J1 对话（低延迟，不走 outbox）
浏览器 -> apps/api --(HMAC 服务令牌 + SSE)--> apps/worker
                                                |-- 打开 TenantContext，校验 run.tenant_id
                                                |-- AgentScope / 模型网关 / 检索
                                                `-- SSE 增量 -> API -> 浏览器

J2 报告 / 文件解析 / 知识库索引（可排队）
apps/api --(同事务写 agent_run + agent_run_outbox)--> MySQL
apps/worker --(轮询领取 FOR UPDATE SKIP LOCKED)--> MySQL
             |-- 打开 TenantContext，校验令牌与 run 租户
             |-- 阶段时间线、审批、MCP、模型调用
             `-- 产物/审计写回 MySQL 与对象存储
```

API 不执行 Agent；Worker 不面向浏览器。任务契约、SSE 事件与幂等规则见 `contracts/` 与 ADR-0014；outbox 领取的跨租户例外见 ADR-0016。

## 数据模型

M02 数据模型由 ADR-0012 定稿，M03 组织与数据范围由 ADR-0018（proposed）设计，M10 身份与审计由 ADR-0019/0020（proposed）设计，M04 模型网关由 ADR-0021/0022（accepted，设计评审口径）设计，M05 知识库纵深由 ADR-0023（proposed）设计，M06 编排 Studio 由 ADR-0024（proposed）设计；迁移位于 `apps/api/src/main/resources/db/migration`（V2–V15；V1 不修改；V9 已在 M03 发布后冻结；V10 为 M10 只增不减的 additive 迁移；V11 为 M04 的 additive 迁移加 `provider_credential` 唯一键替换；V12 为 M05 的 additive 迁移加 `knowledge_document` 可见性约束与 COPY 重建、分块 bbox/OCR 列、outbox 双通道索引；V13/V14 为 M05 引用链接与 FULLTEXT；V15 为 M06 的 additive 迁移，新增 workflow 表、`model_invocation` 可空工作流引用与权限/角色回填）。ADR-0012 §4 的“M04 租户级路由/可写租户级记录”由 ADR-0021 明确覆盖为“平台全局路由、平台托管 key、BYO 后移”；ADR-0012 的全局表清单由 ADR-0021 §3 显式扩展（`model_routing`、`model_routing_entry`、`model_gateway_switch`），并由 ADR-0024 §3.3.7 扩展 `workflow_template`（内置模板只读目录）；旧 ADR 正文不改。M05 不新增全局表。

### 标识与租户键

- 业务对象主键：应用生成 UUIDv7，数据库 `BINARY(16)`；REST/JSON 用 UUID 字符串。
- 租户键：`tenant_id VARCHAR(64) ascii_bin`；所有租户表复合主键 `(tenant_id, id)`，唯一键与索引以 `tenant_id` 开头。
- 复合外键 `(tenant_id, parent_id) → parent(tenant_id, id)` 阻止跨租户关联；跨聚合/可空引用使用逻辑引用 + 索引。
- 状态列 `VARCHAR + CHECK`；时间 `DATETIME(6)` UTC；软删除 `deleted_at`；审计 `audit_event` 按月分区且 append-only。
- 全局表：`tenant`、`permission`、`model_provider`、`model`、`mcp_server`、`mcp_tool`、Flyway 元数据与 Spring Session 表；M04 显式扩展 `model_routing`、`model_routing_entry`、`model_gateway_switch`（ADR-0021 §3）；`audit_event` 主键与 outbox 领取索引是记录在案的特殊例外。

### 表分组

| 分组         | 表                                                                                                                                                                                                                                                | 说明                                                                                                               |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 身份与租户   | `tenant`、`app_user`、`user_credential`、`role`、`permission`、`role_permission`、`user_role`                                                                                                                                                     | 内置账号；M10 接入 OIDC（`auth_source`/issuer/subject 已由 V2 预留，无新列）                                       |
| 组织与部门   | `department`、`app_user.department_id`                                                                                                                                                                                                            | 单级部门、单主部门；M03 新增                                                                                       |
| 工作空间     | `workspace`、`workspace_member`                                                                                                                                                                                                                   | 个人/团队；可见性 `PRIVATE`/`TENANT`/`DEPT`/`CUSTOM`；M03 扩展                                                     |
| 文件与知识库 | `file`、`file_parse`、`file_chunk`、`knowledge_document`、`knowledge_chunk`、`knowledge_document_acl`、`kb_connector`、`kb_connector_item`                                                                                                        | 原文在对象存储；分块与定位在 MySQL；M03 知识库租户级；M05 增加文档可见性、用户级 ACL 与 1 个外部连接器（ADR-0023） |
| 会话与引用   | `conversation`、`message`、`message_citation`                                                                                                                                                                                                     | `seq` 保证顺序；引用保存标题/片段/页码/工作表/段落快照                                                             |
| 任务与运行   | `research_task`、`agent_run`、`agent_run_stage`、`approval`、`agent_run_outbox`                                                                                                                                                                   | J2 状态机、阶段时间线、HITL 与幂等投递                                                                             |
| 编排 Studio  | `workflow_definition`、`workflow_version`、`workflow_run`、`workflow_node_run`、`workflow_approval`、`workflow_approval_assignee`、`workflow_tool_invocation`、`workflow_template`（全局）、`workflow_user_template`（M06b 预留）                 | M06 流程/版本/运行/审批/工具调用；`model_invocation` 复用并新增可空工作流引用（ADR-0024）                          |
| 产物         | `artifact`、`artifact_version`、`artifact_citation`                                                                                                                                                                                               | 一个任务最多一个产物；版本唯一；引用清单可核对                                                                     |
| 模型与 MCP   | `model_provider`、`model`、`tenant_data_key`、`provider_credential`、`model_invocation`、`model_invocation_attempt`、`model_routing`、`model_routing_entry`、`model_gateway_switch`、`mcp_server`、`mcp_tool`、`mcp_tool_grant`、`mcp_invocation` | 凭据信封加密；M02 单一部署级模型端点；M04 多 key/路由/尝试明细/开关                                                |
| 配额         | `quota_limit`、`quota_usage`                                                                                                                                                                                                                      | 租户 + 用户、日 + 月、按请求数；准入计数（ADR-0022）                                                               |
| 审计         | `audit_event`、`audit_export_request`                                                                                                                                                                                                             | 只追加；触发器拒绝 UPDATE/DELETE；M10 提供只读查询与流式导出，导出申请/审批记录单独存表                            |
| 会话存储     | `SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES`                                                                                                                                                                                                     | 基础设施表，租户从主体派生                                                                                         |

### 删除与保留

- 会话与产物写入 `retention_expires_at`（默认 180 天）；账号只禁用不删除；文件/会话/知识库文档软删除。
- 审计至少保留 180 天；M02 无删除 API，M09 由维护账号按分区 `DROP PARTITION`。M10 增加只读查询与导出，不改变 append-only；导出文件不落盘，`audit_export_request` 记录随审计保留策略由 M09 清理。
- M04 `model_invocation` 默认保留 ≥180 天，与成本可查范围一致；`quota_usage` 历史窗口由 M09 维护任务清理。成本快照列不可 UPDATE；应用回滚不删除历史调用记录。
- 迁移只向前；破坏性变更按 ADR-0010 的 expand → contract 两阶段，回滚以前向修复 + 备份恢复为主。

## M03 组织与部门数据范围

M03 的完整决策见 `docs/adr/ADR-0018-m03-organization-and-department-scope.md`（`proposed`，M03 gate 前评审）与 DEC-0015。本节只给实现者需要的最小地图。

### 组织模型

- 单级部门：`department(tenant_id, id)`，`name` 租户内唯一；`manager_user_id` 是关系字段，不是 RBAC 角色。
- 单主部门：`app_user.department_id` 直接列 + 复合外键；多部门归属后移（DEC-0014）。
- **管理主体**：平台管理员跨租户管理租户部门元数据，复用 `/api/v1/admin/tenants/{tenantId}` 路径族；只操作部门、成员归属、负责人，不读取目标租户业务内容。租户管理员不获得部门管理权限；部门负责人只管理本部门空间（DEC-0015）。
- 部门停用只改 `status`/`disabled_at`，保留成员归属；访问判定同时检查 `department.status='ACTIVE'`，停用后下一次请求即失去该部门空间访问。
- 负责人必须是本部门当前启用成员；移除负责人必须先移交（409）。

### 空间可见性

| `kind`     | `visibility` | `department_id` | 访问范围                                     |
| ---------- | ------------ | --------------- | -------------------------------------------- |
| `PERSONAL` | `PRIVATE`    | 空              | 仅 `owner_user_id`                           |
| `TEAM`     | `TENANT`     | 空              | 本租户启用员工；仅 M02 历史迁移、只读        |
| `TEAM`     | `DEPT`       | 必填            | 该部门当前启用成员                           |
| `TEAM`     | `CUSTOM`     | 必填            | 该部门当前启用成员且在 `workspace_member` 中 |

- M03 之后新建团队空间必须给部门，只允许 `DEPT`/`CUSTOM`；历史 `TENANT` 空间只读，M03 不提供转换；转换按 DEC-0023 归 M09 专用迁移，M04 不承接。
- `workspace_member` 只服务 `CUSTOM`；`DEPT` 成员由 `app_user.department_id` 派生。
- 个人空间保持仅本人；任何成员行写入个人空间都拒绝。
- 租户管理员保留 M02 的 `workspace.write` 空间管理能力，但不管理部门元数据；平台管理员不管理业务空间；内容访问对所有角色使用同一空间谓词。

### 访问判定

唯一入口是 `WorkspaceService.requireAccessible(tenantId, workspaceId, userId)`，SQL 谓词固定在 `WorkspaceJdbc`；列表、详情、下载、报告、产物、运行详情复用同一谓词。对象不存在、跨租户、跨部门、个人空间他人统一 `RICCON-TENANT-404`；可见但无管理能力返回 `RICCON-AUTH-403`；其他部门空间的管理操作返回 404。

M03 不引入跨请求共享缓存；成员移出、部门停用、负责人移交下一次请求生效。若后续引入缓存，键必须含 `tenantId`/`userId`/`workspaceId` 并在关系变更时失效（ADR-0018 §3.3）。

### 权限与审计

| 权限码                       | `PLATFORM_ADMIN` | `TENANT_ADMIN` | `MEMBER` / 部门负责人                  |
| ---------------------------- | ---------------- | -------------- | -------------------------------------- |
| `platform.department.manage` | 是               | 否             | 否                                     |
| `department.read`            | 否               | 是             | 是（仅本人部门）                       |
| `workspace.write`            | 否               | 是             | 否（服务层按负责人关系授权本部门空间） |

审计动作：`department.created/renamed/disabled/enabled/member.added/member.removed/manager.changed`、`workspace.created/updated/visibility.changed/member.added/member.removed`；目标类型新增 `DEPARTMENT`。跨租户部门事件沿用 M02 管理员口径：`tenant_id=platform`，目标租户写入 `details.targetTenantId`；M10 的结论是目标租户不可见这些平台来源事件，见 ADR-0020 §3 与 M03 F-009。details 只允许 UUID、计数、枚举与部门名，不含姓名、邮箱、成员名单或业务内容（ADR-0018 §5、ADR-0007）。

### API 摘要

| 能力             | 端点                                                                                                                                                                                                 |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 部门读取（只读） | `GET /api/v1/departments`、`GET /api/v1/departments/{id}/members`（普通员工本人部门，租户管理员本租户全部）                                                                                          |
| 部门空间管理     | `GET` / `POST /api/v1/departments/{id}/workspaces`、`POST /workspaces`（租户管理员）、`PATCH /workspaces/{id}`                                                                                       |
| CUSTOM 成员      | `GET` / `POST /workspaces/{id}/members`、`DELETE /workspaces/{id}/members/{userId}`                                                                                                                  |
| 平台部门管理     | `GET` / `POST /api/v1/admin/tenants/{tenantId}/departments`、`PATCH /.../departments/{id}`、`POST /.../disable`、`POST /.../enable`                                                                  |
| 平台成员/负责人  | `GET` / `POST /.../departments/{id}/members`、`DELETE /.../members/{userId}`、`PUT /.../manager`                                                                                                     |
| 知识库口径       | 列表/详情/检索/引用/知识库下载按文档 ACL（`TENANT`/`DEPT`/`USERS`，创建人始终可见）；原工作空间文件路径仍只走空间谓词、不放大；界面不再声明“全租户共享”或文档级权限后续提供的过期文案（ADR-0023 §5） |

### 迁移与回滚

- V9（唯一迁移，发布前可变）：新增 `department`、`workspace_member`、`app_user.department_id`、`workspace.department_id/visibility`、`department.read` / `platform.department.manage` 权限与角色回填；历史 `PERSONAL→PRIVATE`、`TEAM→TENANT`，不猜部门、不删除数据。
- 发布前由 Builder 直接修正 V9：回填旧代码可能写入的 `NULL` 可见性，改 `NOT NULL` 并收紧 CHECK；不新增 V10。
- 回滚优先“前向修复 + 备份恢复”；V9 的精确回退 SQL 见 ADR-0018 §6.3。只有 `apps/api` 执行 Flyway，worker 保持关闭并校验 schema 版本（V9 → 9）。

### 交付计划

Builder 任务边界、写范围、验收和验证命令见 ADR-0018 §8：6 个 Builder 任务（B1a 领域词汇/共享接口、B2 平台部门后端、B3 空间可见性、B4 内容贯通、B1b V9 收紧与迁移测试、B5 Web 管理界面）+ Verifier 独立 `DEPT-001..010`、`TEN-027`、`E2E-M03-01/02/07` 与 a11y。Builder 写 `IT-DEPT-*` 与代码级测试；Verifier 不改生产代码。

## M10 身份与审计纵深

M10 的完整决策见 `docs/adr/ADR-0019-m10-oidc-sso.md` 与 `docs/adr/ADR-0020-m10-audit-query-export.md`（均为 `proposed`）。本节只给实现者需要的最小地图。

### 身份（OIDC/SSO）

- **每租户 1 个 IdP，配置只来自部署 Secret**：`RICCON_OIDC_IDPS`（JSON 数组）提供 `tenantCode`、`issuer`、`clientId`、`clientSecret`、可选 `providerName`/`scopes`/`endSessionEndpoint`/`enabled`。M10 不建 `tenant_idp_config` 表、不提供运维 UI；未配置即关闭企业登录，内置账号不受影响。
- **协议**：Authorization Code + PKCE（S256）+ OIDC nonce；registrationId `tenant-{tenantCode}`；授权入口 `/api/v1/auth/oidc/authorize/{registrationId}`，回调 `/api/v1/auth/oidc/callback/{registrationId}`；ID Token 只校验 `iss`/`aud`/`exp`/nonce/签名，之后丢弃。
- **无 token**：access/refresh/ID token 不写会话、不写库、不写日志、不回传浏览器；使用 no-op `OAuth2AuthorizedClientRepository`，并用 OIDC-aware `SecurityContextRepository` 阻止 `OidcUser`/`OAuth2AuthenticationToken` 落 `HttpSessionSecurityContextRepository`。业务失败（未绑定/禁用/租户暂停）在认证成功前抛错；失败处理器清空 `SecurityContext`、失效会话、删除授权请求，成功路径只落 `RicconPrincipal`（F-001）。会话仍是 `RICCON_SESSION` + Spring Session JDBC（ADR-0013）；成功后轮换会话 ID 与 CSRF，权限每请求查库。
- **账号**：身份键 `(tenant_id, issuer, subject)`；不自动开通、不按邮箱关联；管理员显式绑定已有账号（转换 `auth_source=OIDC`、删除本地密码凭据）或直接创建 OIDC 账号；同邮箱不同 issuer 生成不同账号；提供“解绑并设置本地密码”的恢复路径。
- **错误态**：`idp_unavailable`、`state_invalid`、`access_denied`、`account_not_bound`、`account_disabled`、`tenant_unavailable`、`issuer_mismatch`、`rate_limited`、`login_failed`；登录页按原因给下一步，不回显 IdP 原始错误、token、内部地址或堆栈。
- **登出（DEC-0017 A）**：本地会话先失效并写审计；配置了 IdP `end_session_endpoint` 时，前端自动顶层导航到 IdP 结束会话，再经 `post_logout_redirect_uri` 回登录页；IdP 不可达时本地登出已成功并显示“已退出本地会话”，不声称 IdP 会话已结束。不持久化 ID token，因此不使用 `id_token_hint`。并发会话允许，M10 不做全局踢出；账号禁用下一次请求生效。
- **后置**：MFA、多 IdP、IdP 组映射、SCIM/LDAP、自动开通、平台管理员 OIDC 登录。MFA 不检查 `acr`/`amr`，不声称强认证（DEC-0013）。

### 审计查询与导出

- **只读查询**：`GET /api/v1/audit-events`（`audit.read`），租户只从主体派生；时间范围必带偏移、默认最近 24h、最大 180 天；动作/操作者/对象/结果过滤；固定 `occurred_at DESC, event_id DESC`；`page` 1..200、`size` ≤100（契约用 `AuditPage`）；不返回 `source_ip`。读取 `details` 执行与写入相同的 action-aware 白名单，禁止 `token`/`content` 子串匹配（`promptTokens`/`completionTokens` 合法）；`actorDisplayName` 批量解析。查询写 `audit.queried`。
- **导出**：`POST /api/v1/audit-exports` 申请（服务端计算预计行数，默认上限 10,000）；同租户另一管理员用 `audit.export.approve` 审批，禁止自批；**仅单管理员租户**（ACTIVE TENANT_ADMIN ≤1）由平台管理员通过 `/api/v1/admin/tenants/{tenantId}/audit-exports` 审批（仅元数据），多管理员租户平台审批返回 409。批准后 `expires_at = decided_at + ttl`（默认 24h、上限 7 天，注入 Clock 便于测试）；只有申请人可下载；平台租户自身导出在 M10 返回 403（deferred）。
- **不落盘**：下载是 `text/csv` 流，含 `#` 元数据（exportId、generatedAt、filters、rowCount）、固定字段白名单与 `details`；不含密码哈希、token、模型/MCP 凭据、文件原文、邮箱、`source_ip`。过期 410；无持久文件、无预签名 URL、无清理作业。每次成功下载写 `data.exported`。
- **平台边界**：平台管理员只能查询 `platform` 租户自身事件；不能读取目标租户审计明细。单管理员租户导出由平台审批（仅元数据，多管理员租户 409）；平台租户自身导出 M10 返回 403（deferred）。M03 平台来源事件（`tenant_id=platform` + `details.targetTenantId`）在 M10 对目标租户仍不可见（M03 F-009，residual 不是 fixed）；跨租户可见模型需另立 ADR（DEC-0013/OQ-13）。
- **保留/索引**：审计保留 ≥180 天，不随租户保留期缩短；M09 按分区清理。V10 新增 `(tenant_id, action, occurred_at)`、`(tenant_id, actor_id, occurred_at)` 索引；不改变分区与 append-only 触发器。
- **后置**：MFA、审计哈希链、跨租户审计明细、平台租户自身导出、导出归档/常驻链接、导出专用重复下载配额、在线删除、定时导出。

### 契约与迁移摘要

| 能力     | 端点                                                                                                                                            |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 企业登录 | `GET /auth/oidc/providers`、`GET /auth/oidc/authorize/{registrationId}`、`GET /auth/oidc/callback/{registrationId}`                             |
| 账号绑定 | `PUT /users/{userId}/oidc-binding`、`POST /users/{userId}/oidc-binding/remove`（平台管理端同路径族）                                            |
| 审计查询 | `GET /audit-events`、`GET /audit-events/policy`                                                                                                 |
| 导出     | `POST /audit-exports`、`GET /audit-exports`、`GET /audit-exports/{id}`、`POST /audit-exports/{id}/decision`、`GET /audit-exports/{id}/download` |
| 平台审批 | `GET /admin/tenants/{tenantId}/audit-exports`、`POST /admin/tenants/{tenantId}/audit-exports/{id}/decision`                                     |

- V10 单迁移：`audit_export_request`、两个 `audit_event` 索引、`audit.read`/`audit.export.approve`/`platform.audit.export.approve` 权限与角色回填。OIDC 无 schema 变更。回滚 SQL 见 ADR-0020 §5.4；worker schema 期望版本 9→10。

## M04 模型网关纵深

M04 的完整决策见 `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md` 与 `docs/adr/ADR-0022-m04-quota-and-cost.md`（均为 `accepted`；M04 模块 gate 未运行）。本节只给实现者需要的最小地图。

### 路由与降级

```text
J1/J2 Worker
  -> 读开关（model_gateway_switch；env 覆盖）
  -> ROUTED：读 model_routing_entry(purpose) + model/provider/key 池
     过滤 ACTIVE、端点允许列表、生产/演示标签、key 冷却
  -> 逐个尝试（默认 3/硬上限 5；首字前预算生产 60s、perf 2.5s）：
       首字前失败 -> 同一模型下一 key -> 下一模型
       首字后失败 -> 不重试，保留内容，SSE failed（502/504）
  -> 成功：更新 model_invocation 为成功模型 + 价格快照/成本（成功终态读取一次，ADR-0022 §2.1 修订）
      失败：RICCON-MODEL-503 统一文案
```

- 路由是全局配置（不分租户），按用途 `CHAT|REPORT`；停用模型保留在链中但运行时跳过；保存后下一次请求生效（无缓存）。
- 降级成功对员工不可见；`chat.started` 使用最终成功模型；不新增“已切换”SSE 事件。
- 无进程内熔断；key 健康状态在 `provider_credential.cooldown_until/consecutive_failures`，跨实例共享；401/403 立即 `INVALID` 并审计。
- 关闭 `routingMode`（`BASELINE`）回到 M02 单模型；关闭 `failoverEnabled` 只尝试一次；关闭 `quotaEnforcementEnabled` 只计量不阻断。发版种子 `quotaEnforcementEnabled=false`；B10 验证默认限额/漂移后显式 `PUT true`，M04 gate 以 true 状态取证 AC-08/09。环境变量优先级高于 DB 开关。BASELINE 仍执行端点允许列表与平台 key 池校验。
- 观测：B1c 引入 `micrometer-registry-prometheus`，`/actuator/prometheus` 仅内网/受控；Worker 7 个路由/降级/key 指标覆盖错误率/并发，API 2 个配额主指标 + 成本查询/存储辅助指标；`scripts/ops/metrics-check` 断言 Worker 7 个指标，V2 核对 2+3；告警阈值在 RUNBOOK（M09 接入）。

### 数据模型增量（V11）

| 表/列                                  | 用途                                                                                                                          |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `model_routing`、`model_routing_entry` | 全局有序模型链；`version` 乐观锁                                                                                              |
| `provider_credential` 扩展             | 多 key：`label`/`fingerprint`/`masked_hint`/`priority`/`status`（ACTIVE/DISABLED/INVALID）/冷却/失效时间；删除旧单 key 唯一键 |
| `model_invocation` 扩展                | `provider_id`、`user_id`、`cost_day`、价格/币种/`cost_amount`/`cost_status`、`attempt_count`                                  |
| `model_invocation_attempt`             | 每次尝试的模型/provider/key/状态/错误/首字                                                                                    |
| `model_gateway_switch`                 | `routingMode`、`failoverEnabled`、`quotaEnforcementEnabled`                                                                   |
| `quota_limit`、`quota_usage`           | 租户/用户 × 日/月；原子准入计数                                                                                               |

- `model_invocation` **不加 CHECK**（MySQL 8.4 ADD CHECK 只能 COPY、阻塞 DML）；`cost_status`/`cost_amount`/`attempt_count` 由应用层校验。小表保留 CHECK。
- `provider_credential.fingerprint` = HMAC-SHA-256 + 独立 pepper，不使用裸 SHA-256；`V11` 先 DROP 旧 CHECK 再 `RETIRED→DISABLED`。
- `model_invocation_attempt` 与 `model_invocation` 保留 ≥180 天，清理先 attempt；`quota_usage` 历史窗口同批清理；成本报告对 `cost_day IS NULL` 用 `unbucketedCalls` 显式呈现。
- **配额准入锁序（B6）**：准入事务先 `SELECT id FROM tenant WHERE id=:tenantId FOR UPDATE`（必须先于任何业务行锁），再按 `TENANT/DAILY → USER/DAILY → TENANT/MONTHLY → USER/MONTHLY` 条件累加；租户锁只负责串行化、不判定准入，条件 `UPDATE ... WHERE used_count < :limit` 仍是唯一判据，锁等待/死锁映射 503 fail-closed。`innodb_lock_wait_timeout` 与同租户混合负载（含 FK 父行等待）由 B10（OPS-M04-014）测量，B6 的 50 并发只覆盖单租户争用（ADR-0022 §1.3）。

### API 摘要

| 能力              | 端点                                                                                                                                                                                   |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 员工模型目录      | `GET /api/v1/models`                                                                                                                                                                   |
| 平台端点/模型目录 | `GET /api/v1/admin/model-providers`、`PUT /admin/model-providers/{providerId}`（只改 name）、`GET /api/v1/admin/models`、`PATCH /admin/models/{modelId}`                               |
| 路由              | `GET /api/v1/admin/model-routing`、`PUT /admin/model-routing/{purpose}`                                                                                                                |
| key 池            | `GET /admin/model-providers/{providerId}/credentials`、`POST /admin/model-providers/{providerId}/credentials`、`POST .../{credentialId}/rotate`、`POST .../disable`、`POST .../enable` |
| 开关              | `GET /api/v1/admin/model-gateway/switches`、`PUT /api/v1/admin/model-gateway/switches`                                                                                                 |
| 配额              | `GET /api/v1/quotas`、`GET /api/v1/quotas/users`、`GET /api/v1/admin/quotas`、`PUT /admin/tenants/{tenantId}/quotas`、`PUT /admin/tenants/{tenantId}/users/{userId}/quotas`            |
| 成本              | `GET /api/v1/costs`、`GET /api/v1/costs/invocations`、`GET /api/v1/admin/costs`                                                                                                        |

MCP 目录端点保留并标 `x-riccon-deferred: M07`；F-011 三个模型端点 M04 实现并通过契约测试。

### 权限矩阵

| 能力                                          | `PLATFORM_ADMIN` | `TENANT_ADMIN` | `MEMBER` |
| --------------------------------------------- | ---------------- | -------------- | -------- |
| `model.read`（员工目录）                      | 是               | 是             | 是       |
| `platform.model.read`                         | 是               | 否             | 否       |
| `platform.model.manage`（路由/模型/key/开关） | 是               | 否             | 否       |
| `quota.read`（本租户）                        | 否（用平台端点） | 是             | 否       |
| `platform.quota.manage`                       | 是               | 否             | 否       |
| `cost.read`（本租户）                         | 否（用平台端点） | 是             | 否       |
| `platform.cost.read`（跨租户聚合）            | 是               | 否             | 否       |

跨租户对象统一 404；平台成本/配额不返回用户明细；员工无配额/成本入口。

### 迁移与回滚

- V11 单迁移，接 V10；只增不删（唯一键替换与 `RETIRED→DISABLED` 数据归一除外）；worker schema 10→11。迁移顺序：加列（INSTANT）→ `model_invocation` 不加 CHECK（COPY 阻塞写）→ `provider_credential` 先 DROP 旧 CHECK 再归一/新约束；**V11 不回溯 `cost_day`**（历史 NULL 计入 `unbucketedCalls`，B10 在 CHECK 后独立分批回填）。
- 发布：preflight → 备份（记录 `master_key_id`）→ 一次性迁移 Job → 新 API → drain 旧 Worker（上限 5 分钟；在途 run 记 `runId` 清单，由新 Worker 按租约恢复）→ 新 Worker → 开关；旧 Worker 不需要在迁移前停，但**V11 之后不得重启旧 Worker**。
- 回滚：应用回滚到 M04 前版本，schema 前向兼容、不做 down migration；旧 API 依赖 `ignore-migration-patterns=*:future` 默认；旧 worker 回滚窗口以 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动并输出固定 WARN `RICCON-WORKER-002`，登记工单（仅回滚窗口、人工核对 `MAX(version)`、只允许 M04 前最后一个 worker 版本）；多 key 必须先收敛到一把再恢复旧唯一键；历史调用/成本快照不删除、不改写。
- 开关与前向修复优先于回滚；V11 结构问题用 V12 前向修复。

### 交付计划

Builder/Ops 任务、写范围、依赖与关键路径见 ADR-0021 §8。单模块 **M04 = 17 个实施任务**（B1a/B1b/B1c/B2/B3/B4/B5/B6/B7/B8/B9a/B9b/B10/B11a/B11b/V1/V2），承接 PRODUCT §10.3 全部 18 条 AC（V1 判 AC-01、AC-03..16；V2 判 AC-02、AC-17、AC-18 并在最终提交独立全量复验）；3 builder 关键路径 ≈28h，48h 上限约 1.7× 余量（依据与停止条件见 ADR-0021 §8.4）。B2 是唯一契约 writer（`1.3.0-m04` 全量，含 `IT-MODEL-108a/108b`），B10 是唯一发布/回滚/quota 开关演练/成本对账任务；不拆分子模块、不做契约分段。T4 威胁模型 gate 要求 B-18/B-19/B-20/B-21。

### 风险

- 受管端点配置错误导致 Worker 拒绝调用或启动失败 → 启动校验 + `DEMO`/`PRODUCTION` 标签 + RUNBOOK 预检。
- 平台 key 池共享健康状态：单租户流量可能让 key 冷却影响其他租户 → 租户配额 + 并发上限 + provider 限流；残余 R-21。
- 首字后失败无法续写 → 保留内容 + 新消息重试；不得自动重放。
- 成本/配额查询跨租户泄漏 → 主体派生 + 固定维度 + 统一 404；`TEN-031..033`。
- 回滚窗口关闭 worker schema 校验 → 仅回滚窗口、人工核对迁移版本、RUNBOOK 记录。

## M05 知识库纵深

M05 的完整决策见 `docs/adr/ADR-0023-m05-knowledge-depth.md`（`proposed`，待 Lead/Verifier/Ops/安全评审；DEC-0034 已批准 Product 拆分与技术默认值，157h 预算待 DEC-0035）。本节只给实现者需要的最小地图。

### 范围与现状

- 合同范围：Excel 结构化抽取、图片/扫描件 OCR、文档级 ACL、1 个外部连接器。DEC-0034 已批准将 M04 R-M04-01/02（`RICCON-QUOTA-429` 文案与相对重置时间）并入 M05，B8 承担前端（ADR-0023 §13.3 L4）。
- M02 已有：PDF/DOCX/XLSX/PNG/JPEG 上传与引用；XLSX 用 POI DOM（20k 行 × 200 列上限，整表一个 `cellRange`）；图片只返回元数据；PDF 仅文本层；检索走 MySQL `LIKE`，OpenSearch 只做可重建索引；知识库租户级共享。
- M05 不切换检索后端：**MySQL 是 ACL 与命中的权威**，OpenSearch 新增字段只用于诊断/未来优化（若切换必须两阶段复核，`IT-KB-117`）。

### Excel 结构化抽取

- 继续 Apache POI 5.5.1（Apache-2.0），XLSX 主路径改为 `XSSFReader` 流式；不新增解析依赖。
- 定位：工作表 × 20 行行带（`A1:F20`），字符切分后所有块继承同一 `cellRange`；数值 `BigDecimal`、日期 `yyyy-MM-dd HH:mm:ss`、公式只读缓存值（禁止 `FormulaEvaluator`）、合并单元格只取左上角、隐藏工作表默认跳过并出警告。
- 上限：50 MB、50 表、20k 行/表、200 列、8M 字符、5k 段落/5k 分块、180 s；超限截断出警告，资源型超限 `FAILED`。
- 警告落 `knowledge_document.parse_warnings`，不写 `error_message`。

### 图片与扫描件 OCR

- 默认 Tesseract 5 + `tessdata_fast`（chi_sim+eng）经 Tess4J（Apache-2.0；JNA 选 Apache-2.0）；`OcrEngine` SPI，替代为 RapidOCR/PP-OCR ONNX（Apache-2.0）；拒绝 Python PaddleOCR、OCRmyPDF（MPL-2.0）、公有云 OCR。
- 图片：EXIF 方向校正 → 50 MP/4000 px 上限 → OCR；置信度 < 0.45 时旋转 90° 重试一次。扫描 PDF：逐页判断文本层，无文本页用 PDFBox `PDFRenderer` 200 DPI 渲染后 OCR；混合页只 OCR 无文本页。
- 定位：不新增 `locator_type` 枚举；图片/区域用 `page_no` + 可空 `bbox_x/y/w/h` + `ocr_confidence` 表达，契约新增可选 `bbox`。
- 资源：每实例并发 2、单页 30 s、单文档 100 页/600 s、逐页释放；**仅生产启用且原生库/引擎缺失时 worker 启动失败**；关闭时 OCR 文档 `FAILED/KB_OCR_DISABLED`；语言包缺失为启动 WARN + 文档 `KB_OCR_LANG_UNAVAILABLE`，运行期失败落 `KB_OCR_*` 稳定码。
- 测试 profile 默认 `RICCON_OCR_ENABLED=false`/懒加载，无 Tesseract 的宿主/CI 全量不失败；OCR 用例走 `@Tag("ocr")` 专用 job；`scripts/ops/ocr-check` 在 `--network none` 容器只跑 B5 的 `OcrCli` + fixture，不启动 MySQL/OpenSearch（ADR-0023 §4.7，F-011/F-115）。
- 精度门槛：固定 20 张印刷体夹具，中文 CER ≤ 10%、英文 ≤ 5%；未达标切换替代引擎（需用户批准）。
- 不可信内容：Excel/OCR/连接器文本统一走 `UntrustedData`；仓库当前无 `INJ-001..012`，M05-B10 新建 Excel/OCR 注入 harness（假模型 + 工具 stub + canary 凭据/内网地址），M05b-B10b 补连接器样本；`SEC-KB-004`/`SEC-CONN-002` 执行、V2 复核。
- 区域高亮（DEC-0034 P6=A）：M05 返回 `bbox`/`sheetName`/`cellRange` 与命中文本块，引用详情用表格/文本块高亮；不新增页图端点、不引入 PDF.js/SheetJS。B 方案（页图叠加 +12–16h）不启用。

### 文档级 ACL

- `knowledge_document.visibility ∈ {TENANT, DEPT, USERS}` + `knowledge_document_acl`（USERS，上限 200 人）；`DEPT` 必填 `department_id` 并复用 M03 单主部门与启用状态；创建人隐式可见且不可移除。
- 权限：`knowledge.manage`/`knowledge.acl.manage` 只授予 `TENANT_ADMIN`，`MEMBER` 只有 `knowledge.read`；登记/添加文档是管理员操作，“创建人”是资源关系而不是角色（ADR-0023 §5.6、ADR-0018 §3.4）。
- 单一谓词集中在知识库访问层，列表/详情/检索/引用/下载/删除/重索引共用；不可见统一 `RICCON-TENANT-404`；可见但非创建人/非 `TENANT_ADMIN` 改 ACL → 403。平台管理员持有租户文档 ID 直接访问按跨租户统一 404，不返回 403（避免存在性探测，ADR-0023 §5.2）。
- 租户管理员对本租户知识文档有管理性读取（列表/检索/详情/下载全量、不做 ACL 过滤）；**绕过 ACL 的读取写 `knowledge.document.admin_read` 审计**（LIST 每次一条；DETAIL/SEARCH/DOWNLOAD 仅在 ACL 本不可见时写），按 ACL 可见的读取不写；ACL 变更必须审计（DEC-0034 U8）。
- 403/404：不可见/跨租户/跨部门/平台管理员 → 404 `RICCON-TENANT-404`；已可见但非创建人/非 `TENANT_ADMIN` 做 ACL/delete/reindex → 403 `RICCON-AUTH-403`；连接器跨租户 → 404，同租户非管理员 → 403（ADR-0023 §5.4）。
- 与 M03 空间 ACL：知识可见性只由文档 ACL 决定，覆盖列表/详情/检索/引用/知识库下载五处；原工作空间文件路径仍只由空间谓词决定，两条路径互不放大。可见范围宽于源空间时详情给 `sourceFileAccessLimited`，默认不阻断（用户可用知识库下载）。
- 引用可用性：聊天与产物引用读取时统一计算 `sourceStatus`（`AVAILABLE` / `NO_ACCESS` / `DELETED` / `UPDATED`）；不可访问时不返回标题/片段/定位，库内快照保留供审计/取证。
- 知识库下载：`GET /knowledge/documents/{id}/download` 走文档 ACL 并以**服务端流式代理**返回（每请求重判 ACL），不暴露对象 key/预签名 URL；无源文件返回 `RICCON-KB-404`。
- 无跨请求缓存；ACL 变更下一次请求生效；OpenSearch 的 ACL 字段通过 `mode=ACL_ONLY` 异步刷新，不重解析源文件，且不作为授权依据。
- 审计：`knowledge.document.added/deleted`（`reason` USER/RETENTION/CONNECTOR）、`knowledge.acl.changed`、`knowledge.document.admin_read`；details 白名单与 fail-closed 见 ADR-0023 §5.6。
- 开关 `RICCON_KB_ACL_ENFORCEMENT_ENABLED`：首次部署 `false` 时启动 WARN `RICCON-KB-001` 并暴露 `riccon_kb_acl_enforcement_disabled=1`，RUNBOOK 仅允许非敏感测试文档；**ACL 打开后应用回滚会放宽为租户全员可见，禁止应用回滚、只前向修复**（DEC-0034 U4）。

### 外部连接器（S3 兼容目录）

- 推荐 S3 兼容 bucket + prefix（AWS SDK v2 已依赖）；替代 Confluence Data Center；拒绝网页抓取与 SharePoint。
- `kb_connector` + `kb_connector_item`：`external_id_hash` 唯一键、ETag/checksum 增量、`last_seen_at` 对账删除、tombstone 180 天；**删除-重现复用 tombstone 行（`generation+1`）**，不撞唯一键。
- 同步时间与完整性：`last_sync_started_at`/`last_sync_finished_at`/`last_sync_status`（SUCCESS/PARTIAL/ERROR）/`last_sync_complete`；失败也写 finish；只有完整 list 才传播删除，部分/截断/超限不删除（ADR-0023 §6.3，F-008/F-009）。
- 手动 `POST /knowledge/connectors/{id}/sync`（202）+ 部署固定周期（`RICCON_CONNECTOR_SYNC_INTERVAL_MINUTES` + `scheduleEnabled`，`next_sync_at` 条件更新）；不提供 per-connector cron/可视化调度；同步在 worker `heavy` 通道执行。
- 凭据走 ADR-0006 信封加密（`purpose=connector-credential`），只写不读、掩码、用后清零；端点只来自 `RICCON_CONNECTOR_ENDPOINTS`，禁重定向/`169.254/16`，RFC1918 仅显式允许；连接器软删除后密文 30 天由 M09 置空（ops，M09 gate）。
- 对象 key：`tenants/{tenantId}/connector/{yyyy}/{documentId}`，是 ADR-0009 §6 的类别扩展（需 Lead 增量修订）；不为连接器造“系统工作空间”。
- 删除传播：源端删除 → 项 `DELETED`、文档软删除、索引 `mode=DELETE`；部分失败继续，同步级失败按 1 m/5 m/15 m 退避 3 次后 `ERROR`。

### 数据模型（V12）

| 表/列                                       | 用途                                                                                                                                                                                         |
| ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `knowledge_document` 扩展                   | `visibility`、`department_id`、`connector_id`、`external_id`、`parse_engine`、`parse_warnings`、`parse_summary`、`acl_version`、`retention_expires_at`；`source_type` CHECK 增加 `CONNECTOR` |
| `knowledge_document_acl`                    | USERS 授权；PK `(tenant_id, document_id, user_id)`，`idx_kda_user` 支撑检索 `EXISTS`                                                                                                         |
| `message_citation`/`artifact_citation` 扩展 | `source_version`（引用时文档 checksum），用于 `sourceStatus=UPDATED` 判定与遮蔽                                                                                                              |
| `kb_connector`                              | 类型、状态、端点 ID、配置 JSON、默认可见范围、凭据密文/掩码、调度开关、同步 start/finish/status/complete、同步摘要                                                                           |
| `kb_connector_item`                         | 源对象哈希唯一键、版本/校验和、文档引用、同步状态、tombstone、`generation`（删除-重现代数）                                                                                                  |
| `file_chunk`/`knowledge_chunk` 扩展         | `bbox_x/y/w/h`、`ocr_confidence`（可空，不加 CHECK）                                                                                                                                         |
| `agent_run_outbox` 扩展                     | `idx_outbox_lane (status, event_type, available_at, lease_until)` 支撑 default/heavy 双通道领取                                                                                              |

- 新增权限 `knowledge.acl.manage`、`knowledge.connector.read/manage`（`TENANT_ADMIN`），按 V9 模式回填既有租户角色；新租户由 `RoleDefinitions` 写入。
- worker schema 11 → 12；回滚 SQL 见 ADR-0023 §7.2，只在无 `CONNECTOR`/ACL 数据时可执行。
- 保留（DEC-0034 P8=A）：默认 180 天、租户 `retention_days` 覆盖；到期只做软删除 + 索引 `DELETE` + 到期标记/展示，物理对象与分块清理由 M09 在软删除后 30 天内完成，作为 release blocker；B 方案（M05 物理清理 +8h）不启用。

### 异步与索引

- outbox 拆 `default`（chat/report）与 `heavy`（`FILE_PARSE`、`KNOWLEDGE_INDEX`、`KNOWLEDGE_CONNECTOR_SYNC`，默认并发 2），避免 OCR/大文件阻塞 J2；新增 `OutboxLeaseHeartbeat` 续期；看门狗按通道配置：`default` 30 min（`RICCON_WORKER_OUTBOX_MAX_PROCESSING`）、`heavy` 65 min（`RICCON_WORKER_OUTBOX_HEAVY_MAX_PROCESSING`，覆盖连接器同步 60 min 总超时）。
- `KNOWLEDGE_INDEX_REQUESTED` payload 增加可选 `mode`（`FULL` / `ACL_ONLY` / `DELETE`）与 `reason`；新增 `riccon.knowledge.connector.sync.requested.v1`。
- OpenSearch v2：新增 `visibility`/`department_id`/`acl_version`/`source_type`/`connector_id`/`ocr_confidence`/`bbox_*`；`scripts/ops/reindex-knowledge.mjs` 重灌并逐租户切别名，v1 保留 7 天可切回。运行期检索不依赖索引，切换无用户影响。

### 契约摘要（M05 `1.4.0-m05` / M05b `1.5.0-m05b`）

| 能力           | 端点                                                                                                                                |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| ACL/文档       | `PUT/GET /knowledge/documents/{documentId}/acl`、`DELETE /knowledge/documents/{documentId}`、`GET .../download`、`POST .../reindex` |
| 连接器（M05b） | `GET/POST /knowledge/connectors`、`PATCH/DELETE .../{id}`、`PUT .../credential`、`POST .../test`、`POST .../sync`、`GET .../items`  |

- M05 `KnowledgeDocument` 增加 `visibility`/`departmentId`/`aclUserCount`/`parseEngine`/`warnings[]`/`parseSummary`/`sourceFileAccessLimited`/`retentionExpiresAt`/`retentionPolicy`；M05b 再加 `connectorId`/`externalId`/`sourceType=CONNECTOR`；`Citation` 加 `bbox`/`ocrConfidence`/`sourceStatus`。
- 错误码：`RICCON-KB-400/404/409/503`；跨租户 `RICCON-TENANT-404`；权限 `RICCON-AUTH-403`；异步 `KB_*` 稳定码。

### 迁移与回滚

- V12 单迁移：加列（INSTANT）/索引（INPLACE, LOCK=NONE）→ 复合外键与可见范围/`source_type` CHECK（MySQL 8.4 不支持 INPLACE，两次 `ALGORITHM=COPY, LOCK=SHARED`，允许读、阻塞写）→ 两张新表 → `message_citation`/`artifact_citation` 加 `source_version` → 分块加 `bbox_x/y/w/h`/`ocr_confidence` → `agent_run_outbox` 加 `idx_outbox_lane` → 权限回填。生产锁等待/维护窗口由 M09 迁移演练测量，M05 只有 Testcontainers 证据。
- 发布：V12 + 代码（ACL/OCR/连接器开关全 false）→ 重灌索引 v2 并切别名 → 打开 ACL → 建 `DEPT`/`USERS` 文档核对 → 打开 OCR/连接器 → gate。
- 回滚：先关开关；ACL 打开后不做应用回滚（前向修复）；索引别名可切回 v1；数据库不做 down migration，应急回滚清单见 ADR-0023 §7.2（含 `bbox_*`/`ocr_confidence`、`idx_outbox_lane` 与三条权限行，生产走 M09 备份/演练）。

### 交付计划与预算

- 任务边界见 ADR-0023 §12：M05 = B1/B2/B3/B4/B5/B7/B8/B9/B10/B11/O1a/O1b/V1/V2（104h）；M05b = B2b/B6/B7b/B8b/B9b/B10b/B11b/O2a/O2b/V1b/V2b（53h）；合计 157h。P1 修复新增项包括 delete/reindex、下载流式代理、`admin_read`、同步时间/tombstone/list guard、注入/OCR/perf harness、OCR 镜像/零出网与连接器 Ops。
- DEC-0034 已批准 Product 拆分（M05=Excel+OCR+ACL+M04 429；M05b=S3 连接器），不再保留风险拆分/单模块选项；B1/B2/B2b/B3/B6/B7/B7b/B9/B9b/B10/B10b 为高风险任务，done 前必须有独立 Verifier 任务级评审；M05 gate 前完成 O1a/O1b，M05b gate 前完成 O2a/O2b。
- **AC-20/22 分段 gate**：M05 gate 判本段（首字/列表/详情/ACL 写、50 并发、OCR/索引不阻塞、检索量测；J1/J2/M03/M10/M04 回归 + M05 契约）；M05b gate 判整条（加连接器同步/契约并整条复验）。M05 不得把性能/回归全部留到 M05b。

### 风险

- Tesseract 中文精度不达标 → 固定夹具 + CER 门槛 + SPI 切换 PP-OCR（需用户批准）。
- OCR/解析资源耗尽 → 像素/页/行/字符上限、逐页释放、heavy 并发 2、租约续期。
- ACL 谓词漏入口 → 单一访问层 + `TEN-034..037`/`DEPT-011..014`/`SEC-KB-002/003` 越权矩阵；租户管理员 `admin_read` 绕过 ACL 必须留审计（`IT-KB-110`）。
- 连接器 list 不完整误删 → 只有 `last_sync_complete=true` 才传播删除；tombstone 复用 + `generation`（`IT-CONN-103`）。
- ACL 上线后回滚放宽可见性 → 默认禁止应用回滚，前向修复；用户批准才允许放宽。
- 连接器 SSRF/凭据 → 端点允许列表、无重定向、禁元数据地址、信封加密、只写不读。
- 索引 v2 重灌遗漏 → 以 MySQL 重灌、逐租户别名切换、v1 保留 7 天。
- outbox 长任务阻塞 → 双通道 + 租约续期 + `IT-WORKER-LANE-001`/`IT-WORKER-LEASE-001`。

## M06 编排 Studio

M06 的完整决策见 `docs/adr/ADR-0024-m06-workflow-orchestration.md`（`proposed`；DEC-0046=A 已批准范围/拆分/预算，待 Lead/Verifier/Ops/安全评审）。本节只给实现者需要的最小地图。

### 范围与运行流

```text
Studio 编辑（列表/表单为主，画布增强）
  -> apps/api：草稿/校验/发布（workflow_definition / workflow_version）
  -> 手动运行：workflow_run（持久运行队列，PENDING + current_node_key）
  -> apps/worker：版本快照串行执行
       开始 -> 检索（发起人 ACL）-> 模型（M04 REPORT 链）-> 审批（暂停，释放执行槽）
            -> MCP 工具（审批/参数解密/plan 校验）-> 条件分支（封闭算子）-> 结束
  -> 运行时间线/结果/引用/审批记录（轮询）；审计同事务 fail-closed
```

- 只做手动触发、串行 + 条件分支、7 类节点；不做代码执行/脚本/任意 HTTP、并行/循环/子流程、定时触发、跨租户模板市场、审批通知/代理（AC-24）。
- 运行绑定不可变发布版本；草稿修改不影响在跑运行；旧版本仍可运行，除非流程被停用。

### 数据模型（V15，additive）

| 表                                                                      | 用途                                                                                |
| ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `workflow_definition`                                                   | 流程元数据 + 可变草稿（`draft_version` 乐观锁）                                     |
| `workflow_version`                                                      | 不可变发布快照（`graph_json`/`graph_sha256`/`run_scope`）；触发器拒绝 UPDATE/DELETE |
| `workflow_run`                                                          | 运行权威（状态/版本/发起人/结果/租约/保留到期）                                     |
| `workflow_node_run`                                                     | 节点尝试时间线（状态/输出摘要/引用/错误）                                           |
| `workflow_approval` + `workflow_approval_assignee`                      | 通用审批节点；指定角色/人、禁自批、参数密文/plan 哈希                               |
| `workflow_tool_invocation`                                              | 工作流 MCP 调用记录（不复用 `mcp_invocation`）                                      |
| `workflow_template`（全局）/`workflow_user_template`（租户，M06b 预留） | 内置模板目录 / 用户模板共享                                                         |

- **不复用 `agent_run`**：J2 的 `kind`/阶段/审批/工具调用绑定不变；工作流是独立运行聚合。模型调用复用 `model_invocation`（`run_id=NULL`，新增可空 `workflow_run_id`/`workflow_node_run_id`，`purpose=REPORT`）。
- 结果存 MySQL JSON，单节点 ≤256 KiB、单运行合计 ≤5 MiB；超限运行失败 `WF_RESULT_LIMIT`，不静默截断；不新增对象存储类别/预签名 URL。
- 运行默认保留 180 天（租户策略优先），到期统一不可见；物理清理 M09。

### 执行与并发

- `workflow_run` 自身是持久运行队列：worker 以 `FOR UPDATE SKIP LOCKED` 领取 `PENDING` 或租约过期的 `RUNNING`，租约 90s + 心跳，默认并发 10；审批等待释放线程/租约，恢复/重试通过把运行置回 `PENDING` + `current_node_key` 触发。不复用 J2 `agent_run_outbox`（`run_id NOT NULL` FK `agent_run`，`OutboxPoller` 绑定 agent_run）。
- 队列不变量：每槽位 batch=1、独立调度池≥并发、心跳池≥并发、续期≤lease/3；启动校验失败即拒绝启动。`RICCON_WORKFLOW_CLAIM_ENABLED` 控制停止领取/优雅停机；`queue_depth`/`oldest_pending_age`/`lease_lost`/`claim_exhausted` 指标与阈值进 RUNBOOK（T15）。
- 运行准入：M04 配额 1 单位/运行（DEC-0048=A）+ 租户 10/单流程 5（含等待审批）；超限 429 RICCON-WF-429；不新增 token/成本配额。
- 节点超时：模型 300s、检索 120s、MCP 60s；运行 30min（审批等待不计）；步数 200、节点 50。
- 重试只针对失败节点，上游不重跑；写工具重试必须重新审批；工具调用未知终态 fail-closed（`WF_SIDE_EFFECT_UNKNOWN`）。
- kill switch `RICCON_WORKFLOW_ENABLED`：关闭后新运行/重试/审批不受理，历史只读，J1/J2 不受影响。

### 审批与权限

- 新建 `workflow_approval`，不扩 J2 `approval`；审批人按角色/人解析，排除发起人；无可用审批人保持等待（不自动放行）；发布校验阻断；平台管理员 404。
- 新增权限 `workflow.read/edit/publish/run/approve/manage` 与系统角色 `WORKFLOW_EDITOR`（租户管理员经既有角色管理授予/撤销，审计 `permission.changed`）。部门负责人仅在发布 `DEPARTMENT` 范围时要求 `department.manager_user_id` 关系；关系本身不授权。
- 403/404：跨租户/跨部门/私有草稿/他人运行详情 → `RICCON-TENANT-404`；同租户可见无能力 → `RICCON-AUTH-403`；平台管理员统一 404。租户管理员查看他人运行写 `workflow.run.admin_read`。

### 契约与迁移

| 能力                                    | 端点/事件                                                                                                                                                                                                                   |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 定义/草稿/校验/发布/版本/停用/启用/移交 | `GET/POST /api/v1/workflows`、`GET/PUT /workflows/{id}`、`/draft`、`/duplicate`、`/validate`、`/publish`、`/versions`、`/disable`、`/enable`、`/transfer`                                                                   |
| 运行/历史/审批/模板/限制                | `POST/GET /workflows/{id}/runs`、`GET /workflow-runs`、`GET /workflow-runs/{id}`、`/cancel`、`/retry`、`GET /workflow-approvals`、`/{id}`、`/decision`、`GET /workflow-templates`、`/copy`、`GET /workflows/runtime-limits` |
| 事件                                    | 无（M06 不使用 outbox；`workflow_run` 为持久队列）                                                                                                                                                                          |

- 契约 `1.6.0-m06`（additive）；internal API 不变；运行详情轮询，不新增 SSE；M06b 端点/共享/升级字段以 `x-riccon-deferred: M06b` 预留。
- V15：9 张新表 + `model_invocation` 可空引用 + 权限/`WORKFLOW_EDITOR` 回填 + 内置模板种子 + 版本不可变触发器；worker schema 14→15；不做 down migration。

### 交付与预算

- M06 = 21 个任务、**120h**，关键路径 ≈75h（T01→T02→T03→T04→T05→T06a→T06b→T07a→T07b→T16a→T16b→T17）；M06b/M26 = 6 个任务、**48h**（AC-11/25，M06 用户验收后启动）。任务边界/写范围/AC/证据见 ADR-0024 §13；T15 3→4h、T16 8→10h 由 T11 画布 5→2h 调剂。
- M06 gate：AC-01..10、12..24；AC-11/25 不进 M06 gate。AC-19/20/21/23/24 仍须在模块 gate 与 release 前验证（T4 决策不新增 DoD key）。

### 风险

- AC-23 的“应用回滚后历史仍可读”与旧 API 无 workflow 端点/审计白名单冲突 → DEC-0047=A 已确认：受支持回滚 = 关开关 + 回滚 Worker + 保留/前向修复 API；全量 API 回滚仅限无数据/审计行（ADR-0024 §10.4）。
- 按运行计 1 个配额单位（DEC-0048=A），模型成本按 `model_invocation` 聚合；资源上限约束套利；R-36 为已接受 residual。
- 单入边树状图表达力有限；提示注入无法根治；结果/节点输出存 MySQL 明文（与现有会话内容同级）；180 天物理清理归 M09。
- 审批理由进入审计可能含 PII（U-05）；平台管理员不可读租户工作流内容；`WORKFLOW_EDITOR` 授予/撤销必须审计并立即生效。

## 契约

- REST 前缀 `/api/v1`；破坏性变更升 `/api/v2`，不允许在同一版本内改变字段语义。
- 错误响应使用 RFC 9457 `ProblemDetail`，扩展字段至少包含 `code`、`traceId`、`tenantId`（脱敏后）。
- 契约目录（M02 建立，M03/M10/M04/M05/M06 扩展）：
  - `contracts/openapi.yaml`：认证（含 M10 OIDC）、工作空间与部门、文件、会话、知识库、报告任务、审批、产物、模型/MCP 目录、账号管理（含 OIDC 绑定）、审计查询/导出与平台导出审批、模型路由/key/开关、配额、成本；
  - `contracts/internal-api.yaml`：API → Worker 内部 HTTP/SSE，仅集群内可达（M03/M04/M10 无变更）；
  - `contracts/events/`：outbox 信封与事件 JSON Schema、SSE 事件 schema；
  - `contracts/README.md`：版本、兼容、幂等与安全规则。
- 事件命名：`riccon.<domain>.<event>.v1`；事件必须带 `eventId`、`occurredAt`、`tenantId`、`traceId`、`idempotencyKey`。
- M04 契约版本 `1.3.0-m04`（全量设计、单模块一次交付）：模型路由、降级、多 key、受管端点、开关、配额与成本已在同一版本定义；`x-riccon-deferred` 移除 `model-routing-quota`，`workspace-archive` 改为 M09，MCP 目录标 M07，BYO/多币种/价格编辑/账单对账后置。DEC-0024=B 已撤销拆分，契约不做分段，配额/成本端点由 B2 同任务实现并通过契约测试。
- M05 设计版本 `1.4.0-m05`（ADR-0023 `proposed`，尚未实现）：知识库 `visibility`/`departmentId`/`warnings`/`parseSummary`、ACL 两端点、文档 delete/download/reindex、`Citation.bbox`/`sourceStatus`；M05b 设计版本 `1.5.0-m05b`：连接器六端点、`sourceType=CONNECTOR`、`connectorId`/`externalId`。全部为 additive，破坏性变更才升 `/api/v2`。
- M06 设计版本 `1.6.0-m06`（ADR-0024 `proposed`，尚未实现）：`/workflows` 定义/草稿/校验/发布/版本/停用/移交、`/workflow-runs` 运行/历史/取消/重试、`/workflow-approvals` 待办/决定、`/workflow-templates` 列表/复制、`/workflows/runtime-limits`；不使用 outbox/新增事件；internal API 不变；运行详情轮询，不新增 SSE；M06b 端点/共享/升级字段标 `x-riccon-deferred: M06b`。全部 additive。
- OpenAPI 契约手工维护为 `contracts/openapi.yaml`；实现可用 springdoc 生成运行时描述并在契约测试中与契约对比。M01 默认关闭文档端点，M02 按环境开启。

## 安全与隐私

- **默认拒绝**：`apps/api` 与 `apps/worker` 的过滤器链只放行 `/actuator/health`，其余请求在 M02 前一律拒绝。
- **无默认用户**：排除 Spring Boot 内存用户自动配置，避免生成默认密码。
- **租户隔离**：`TenantContext` 基于 ThreadLocal，入口 Filter 打开作用域，出口关闭；跨线程必须显式传递。M02 接入异步/响应式链路时按 ADR-0003 扩展。
- **认证**：M02 使用内置账号 + Spring Session JDBC 服务端会话；Cookie `HttpOnly`、`SameSite=Lax`、非本地 `Secure`；登录后轮换会话 ID，登出失效；所有写操作校验 `X-CSRF-TOKEN`。不使用 JWT。M10 OIDC 使用 Authorization Code + PKCE + nonce，复用同一会话层（ADR-0013/0019）；access/refresh/ID token 不落会话、不落库、不落日志。
- **登录防护**：统一失败提示、bcrypt 12、连续 5 次失败锁定 15 分钟、进程内按 IP + 租户限流；登录成功/失败/登出写审计。OIDC 失败使用稳定原因码，不回显 IdP 原始错误。
- **密钥**：只从环境变量或密钥管理服务读取；禁止写入仓库、镜像、日志、证据和提示词。OIDC `clientSecret` 走同一模式，配置只从 Secret 注入。
- **MCP**：工具白名单、参数校验、超时与出网限制；高风险工具必须人工审批。
- **提示注入**：知识库与 MCP 返回内容一律视为不可信数据，不进入系统指令；M02 建立注入用例集。
- **审计**：模型、工具、MCP、权限和租户变更必须留痕；审计写入失败时业务失败，不允许静默丢弃。M10 查询只读、强制租户与时间范围；导出需另一管理员审批、申请人不得自批、只有申请人可下载、CSV 字段白名单、无持久文件、过期即不可下载。平台管理员不读取目标租户审计明细。
- **模型端点与凭据（M04）**：端点只来自部署允许列表，生产拒绝演示端点；API/Worker 共用 URL 校验并禁止重定向/元数据地址/编码 IP 绕过。平台 key 池用 ADR-0006 信封加密，API 与 Worker 都必须注入主密钥（current/previous/id）与 fingerprint pepper；响应/日志/审计只出现掩码。路由/模型/key/开关写接口仅 `platform.model.manage`，审计 fail-closed；`/actuator/prometheus` 仅内网。
- **配额与成本（M04）**：配额按租户/用户、日/月、请求数准入；超限拒绝新 run；平台写、租户只读。成本只读、价格快照不可改写；平台只看租户聚合，租户只看本租户，员工无入口；金额/用量不进日志。
- **知识库 ACL（M05，ADR-0023 proposed）**：文档可见性只由 `knowledge_document.visibility` + `knowledge_document_acl` 决定，单一谓词覆盖列表/详情/检索/引用/知识库下载/删除/重索引；不可见统一 `RICCON-TENANT-404`；原工作空间路径仍只由空间谓词决定。ACL 变更同事务审计、fail-closed；无跨请求缓存，下一次请求生效。
- **OCR 与解析（M05）**：只使用本地引擎（Tesseract 默认，PP-OCR ONNX 替代），不调用公网；模型/原生库版本与哈希在启动自检与 `/actuator/info` 可见；像素/页/行/字符/时间上限 fail-closed；OCR 文本与 Excel 文本同属不可信数据。
- **连接器（M05）**：端点只来自部署允许列表，禁重定向与 `169.254/16`，RFC1918 仅显式允许；凭据走 ADR-0006 信封加密、只写不读；对象/条目/时间有上限；源端删除必须传播，同步动作审计。

## 性能预算

| 指标           | 预算                                     | 说明                                                                                                                                                                                                 |
| -------------- | ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| API p95        | ≤ 500 ms                                 | **稳态 JSON API**：知识库/配额/成本/运行详情等；排除登录、workspace/setup/cleanup（bcrypt 与初始化不计入）；SSE 提交的 TTFB 单列（`m04_chat_submit_ttfb_ms`），不并入本预算（M04 F-013，2026-11-22） |
| 首字延迟 p95   | ≤ 3 s                                    | 假模型基准，不含真实模型排队                                                                                                                                                                         |
| 并发 Agent run | 50                                       | 单 Worker 实例基线，按水平扩展提升                                                                                                                                                                   |
| 健康检查       | ≤ 1 s                                    | 不依赖外部模型或 MCP                                                                                                                                                                                 |
| 降级尝试上限   | 3（硬上限 5）                            | 单次模型调用（含首次）；per-attempt 计数                                                                                                                                                             |
| 首字前总预算   | 生产 60s / perf 2.5s                     | AC-17 使用 perf profile；超出返回 503/504                                                                                                                                                            |
| 成本查询 p95   | ≤ 2 s                                    | 30 天、租户/平台聚合；超时返回 503                                                                                                                                                                   |
| 配额准入       | ≤ 20 ms                                  | 4 次条件 UPDATE + run 插入同事务                                                                                                                                                                     |
| 指标抓取       | `/actuator/prometheus`                   | 仅内网/受控；M04 7 指标存在                                                                                                                                                                          |
| ACL 检索增量   | ≤ 20%                                    | ACL 开启后检索/首字相对 M04 基线；`PERF-M05-001`                                                                                                                                                     |
| 检索 p95       | **待量测，不预设阈值**                   | 10 万 chunk、50 并发；`LIKE` + ACL EXISTS 先量测；超 500ms 时 FTS/OpenSearch/单独预算需用户决策；`PERF-M05-005`                                                                                      |
| OCR 单页 p95   | ≤ 3 s                                    | A4 200 DPI、2 并发、`tessdata_fast`；`PERF-M05-002`                                                                                                                                                  |
| 索引延迟       | p95 ≤ 60s（无 OCR）/ ≤ 120s（20 页 OCR） | create → `INDEXED`；`PERF-M05-002`                                                                                                                                                                   |
| 连接器同步     | 1,000 对象 ≤ 15 min                      | 增量、无 >10 MB 对象；`PERF-M05-003`                                                                                                                                                                 |

性能预算由 k6 在 sandbox/preview 验证；未达到预算的变更不得进入 release。**API p95 只统计稳态 JSON API**：登录、workspace 列表、会话创建、setup/cleanup 不计入；聊天提交是 SSE，TTFB 单列（`m04_chat_submit_ttfb_ms`），受首字 p95 ≤ 3s 约束，不并入 500ms 预算（M04 F-013 口径）。M05 场景由 `PERF_M05_SCENARIO=knowledge` 提供（`e2e/perf/mvc-smoke.js` + `scripts/real-stack/run.mjs`，owner M05-B11），`IT-WORKER-LANE-001` 证明 OCR/索引积压不阻塞 chat/report；检索 p95 未量测前不得声称 AC-20 通过。AC-17 的 50 并发/perf profile 必须在报告中记录 `RICCON_CHAT_MAX_STREAMS=50`、`RICCON_DB_POOL_SIZE≥25`、`RICCON_FAKE_MODEL=1`、`RICCON_MODEL_DEPLOYMENT_MODE=DEMO`。

## 运行模型

- **local**：macOS + Docker Desktop；`deploy/compose/docker-compose.test.yml` 提供 MySQL、OpenSearch、对象存储。
- **CI**：GitHub Actions，JDK 21 + Maven 缓存 + Compose 测试栈 + `./mvnw -T1C verify` + `spotless:check`。
- **preview/production**：M09 提供 Helm 与备份、升级、回滚演练；M01 不写生产编排。
- **可观测性**：Actuator 健康检查；结构化日志（键值对）；traceId 在 M02 接入。审计与业务日志分离。

## 扩展点

| 扩展点       | 位置                                    | 约定                                                                                                                                     |
| ------------ | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| 认证方式     | `apps/api` 过滤器链                     | 新增方式不得放宽 `anyRequest().denyAll()`；按需增加 `permitAll` 白名单并写 ADR                                                           |
| Agent 运行时 | `apps/worker` 内部 SPI                  | `platform` 定义接口，`worker` 实现；接口不得泄漏 agentscope 类型                                                                         |
| 模型网关     | `apps/worker` 适配层                    | OpenAI 兼容协议优先；M04 路由/降级/多 key 在 `ModelRouter`/`FailoverChatModel` 内扩展，新增协议另立 ADR                                  |
| 配额         | `apps/api` 准入层                       | 只按请求数、租户+用户、日+月；token/成本配额后移，扩展不得绕过 `agent_run` 创建事务                                                      |
| 成本         | `apps/api` 只读查询                     | 价格快照不可改写；预聚合/多币种另立 ADR                                                                                                  |
| 知识库       | `apps/api` + `apps/worker` + OpenSearch | 文档级 ACL 在 M05 引入（ADR-0023）：MySQL 为可见性与命中权威，OpenSearch 字段只做诊断；检索结果必须带引用；OCR 只在本地引擎执行          |
| 编排 Studio  | `apps/api` + `apps/worker`              | M06 自研 DAG（ADR-0024）：节点/算子/占位符封闭白名单，无脚本/表达式/HTTP/并行/循环/定时；工具调用必须审批；新增节点类型另立 ADR/威胁模型 |
| MCP          | `apps/worker` 代理                      | 注册审批、凭据托管、工具搜索在 M07 扩展                                                                                                  |
| 前端         | `apps/web`                              | Umi Max 4 + Ant Design Pro 6 + Ant Design X 2；只消费 API 契约                                                                           |

## 风险

| 风险                                            | 可能性 | 影响                      | 缓解                                                                                                                                             |
| ----------------------------------------------- | ------ | ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| 租户上下文在线程池/异步链路丢失                 | 中     | 高（跨租户泄漏）          | ThreadLocal 只用于同步；跨线程显式传递；M02 增加越权测试套件                                                                                     |
| AgentScope 依赖泄漏到 API                       | 中     | 高（边界腐蚀）            | ArchUnit + Maven Enforcer 双重门禁                                                                                                               |
| MySQL Connector/J 的 GPLv2 许可与私有化分发冲突 | 中     | 高（法务）                | 发布前由 Lead/法务确认 Oracle 商业许可或替代驱动；见遗留风险                                                                                     |
| MinIO 官方镜像停止发布                          | 高     | 低（仅测试栈）            | 测试栈使用 RustFS（ADR-0002）；生产对象存储由 ops 选型                                                                                           |
| 覆盖率门槛在骨架期被排除项稀释                  | 中     | 中                        | 排除项仅限启动类；新增排除必须评审                                                                                                               |
| 假模型基准与真实模型差异                        | 中     | 中                        | M02 建立假模型基准与真实模型对比记录                                                                                                             |
| 审计触发器需要迁移账号权限                      | 中     | 高（迁移失败）            | 迁移账号需 `TRIGGER`；binlog 开启时需 `log_bin_trust_function_creators=1`，写入 RUNBOOK 与部署检查                                               |
| 会话表成为登录关键路径                          | 低     | 中（无法登录）            | MySQL 健康检查、会话过期清理、备份包含 `SPRING_SESSION*`                                                                                         |
| 部门成员关系陈旧或缓存导致移出后仍可访问        | 中     | 高（跨部门泄露）          | 每请求查库；无跨请求缓存；成员移出/部门停用/负责人移交下一次请求生效；`DEPT-007`                                                                 |
| M02 遗留 `TENANT` 空间在 M03 仍全租户可见       | 中     | 中（租户内越权）          | M03 不提供转换；评审列遗留清单；转换按 DEC-0023 归 M09 专用迁移；新空间禁止 `TENANT`（R-11）                                                     |
| 知识库租户级共享被误读为部门隔离                | 中     | 中（预期错位）            | M05 以文档 ACL（`TENANT`/`DEPT`/`USERS` + 创建人）取代租户级共享；界面按文档可见范围显示，不再声明“全租户共享”；源文件原工作空间路径仍走空间 404 |
| IdP 配置漂移/密钥轮换失败导致企业登录不可用     | 中     | 高（无法登录）            | 配置启动校验；内置账号兜底；滚动重启；`oidc-binding/remove` 恢复本地密码；M09 运行手册                                                           |
| OIDC token 或 IdP 原始错误泄漏到浏览器/日志     | 低     | 高（账号接管）            | 不存 token；no-op authorized client；失败只映射原因码；`SEC-OIDC-001`；日志脱敏                                                                  |
| 审计导出被审批绕过或跨租户读取                  | 低     | 高（批量泄露）            | 申请人≠审批人；只有申请人下载；平台审批仅元数据；`TEN-028..030`、`SEC-AUDIT-001`                                                                 |
| 深层分页/大范围查询拖慢审计库                   | 中     | 中（可用性）              | 最大 180 天、page ≤200、size ≤100、导出上限 10,000、V10 索引；M09 观测慢查询                                                                     |
| 流式导出在有效期内重复生成导致行数变化          | 中     | 低（结果解释）            | `occurredTo` 固定；CSV 元数据写实际 `rowCount`；审批记录与 `data.exported` 可核对                                                                |
| OIDC 业务失败残留 OidcUser/ID Token 会话        | 低     | 高（账号接管）            | OIDC-aware SecurityContextRepository 不落 OIDC 主体；失败清理并失效会话；`IT-OIDC-001/004/006`、`SEC-OIDC-001`                                   |
| 登出未结束 IdP 会话导致共享工作站 SSO 残留      | 中     | 高（账号接管）            | DEC-0017 A：本地先失效 + 自动 end-session 导航；IdP 不可达提示仅本地退出；`E2E-M03-03`                                                           |
| 审计读取用 token 子串误杀模型事件               | 中     | 中（功能失败）            | action-aware 白名单；禁止子串匹配；`IT-AUDIT-101` 覆盖 promptTokens/completionTokens                                                             |
| 平台管理员审批多管理员租户导出                  | 低     | 高（审批绕过）            | ACTIVE TENANT_ADMIN ≥2 时平台端点 409；只允许单管理员租户；`IT-AUDIT-104`                                                                        |
| 平台租户导出无合法审批人                        | 低     | 中（功能缺口）            | M10 显式 403/deferred；不引入自批；M10+ 另立审批模型（F-023）                                                                                    |
| 审计 CSV 公式注入                               | 低     | 中（客户端执行）          | 危险前缀单元格前置 `'`；`SEC-AUDIT-001` 扫描；CSV 元数据固定                                                                                     |
| M04 受管端点被篡改或误配导致凭据发往错误地址    | 中     | 高（凭据外发）            | 部署允许列表 + 启动校验 + 调用前二次校验 + 禁止重定向/元数据地址；`SEC-M04-001..004`                                                             |
| M04 首字后失败被自动重试产生重复内容            | 中     | 中（错误内容）            | 首字后绝不重试/重放；保留内容 + 新消息重试；`IT-MODEL-104`、`E2E-M04-03`                                                                         |
| M04 配额并发超卖                                | 中     | 高（成本失控）            | 单条条件 UPDATE + 固定顺序事务；50 并发用例；`IT-QUOTA-102`、`PERF-M04-002`                                                                      |
| M04 成本快照被价格更新污染或重复重算            | 中     | 高（账单不可信）          | 成功终态读取一次价格快照（M20/TASK-0140 修订，见 ADR-0022 §2.1；价格维护上线前必须重访）+ 只读历史；禁止 UPDATE `cost_amount`；`IT-COST-101`     |
| M04 key 轮换失败或最后一个 key 被停用           | 中     | 高（模型不可用）          | 先插新 key 再停旧；HMAC fingerprint 去重/409；运行时跳到下一模型；`IT-MODEL-105/106`                                                             |
| M04 降级风暴放大上游故障                        | 中     | 中（延迟/成本）           | 尝试上限/预算/退避抖动/key 冷却/`failoverEnabled` 开关；`PERF-M04-001`                                                                           |
| M04 V11 大表 COPY/回填导致写阻塞或复制延迟      | 中     | 高（升级窗口）            | `model_invocation` 不加 CHECK；索引 INPLACE/LOCK=NONE；V11 不回填 `cost_day`，由 B10 在 CHECK 后独立分批回填；大表阈值停写窗口；B10 演练         |
| M04 可观测性缺失导致 AC-17 不可验收             | 中     | 中（假绿）                | B1c 引入 Micrometer/Prometheus/M04 7 指标；`metrics-check`；V2 抓取证据                                                                          |
| M04 跨租户成本/配额泄漏                         | 低     | 高（商业数据）            | 主体派生 + 平台无用户明细 + 统一 404；`TEN-032/033`、`IT-QUOTA-103`                                                                              |
| M05 ACL 谓词漏入口导致租户内越权                | 中     | 高（敏感文档）            | 单一访问层集中谓词；列表/详情/检索/引用/下载/删除/重索引共用；`TEN-034..037`、`DEPT-011..014`、`SEC-KB-002/003`                                  |
| M05 ACL 上线后应用回滚放宽为租户全员可见        | 中     | 高（越权）                | 默认禁止应用回滚、只前向修复；关闭开关同样需用户书面同意；ADR-0023 §5.9                                                                          |
| M05 OCR/解析资源耗尽或原生库崩溃                | 中     | 中（可用性）              | 像素/页/行/字符/超时上限；逐页释放；heavy 通道并发 2；租约续期；`SEC-KB-005`                                                                     |
| M05 OCR 中文精度不达标                          | 中     | 中（体验）                | 固定夹具 CER 门槛；`OcrEngine` SPI 切换 PP-OCR ONNX；需用户批准                                                                                  |
| M05 P6=B 页图端点增加渲染/缓存/越权面           | 中     | 中（内容泄露/DoS）        | 每请求 ACL、private 缓存、页号/DPI/像素上限、超时；`SEC-KB-006`；选 A 则无此风险                                                                 |
| M05 AC-21=B 物理清理误删或扩大权限面            | 低     | 高（数据丢失）            | 只处理到期且已软删除、批量上限、dry-run、审计、幂等条件删除；`IT-KB-118`；选 A 则无此风险                                                        |
| M05 连接器 SSRF 或凭据泄露                      | 低     | 高（内网/接管）           | 端点允许列表、无重定向、禁元数据地址；信封加密、只写不读、掩码；`SEC-CONN-001/003`                                                               |
| M05 源端删除未传播或误删                        | 中     | 中（越权残留/数据丢失）   | 每轮 list + `last_seen_at` 对账；只有 `last_sync_complete=true` 才删除；tombstone 复用（generation）；`IT-CONN-103`                              |
| M05 outbox 长任务阻塞报告与文件解析             | 中     | 中（延迟）                | default/heavy 双通道 + 租约续期 + 最大处理时长；`IT-WORKER-LANE-001`                                                                             |
| M06 工作流审批/参数/摘要泄露                    | 中     | 高（敏感内容/越权）       | 新建 `workflow_approval`；工具参数密文 + plan 哈希；审批摘要按审批人 ACL；运行详情不返回 prompt/完整参数；`SEC-WF-001/004`                       |
| M06 越权重试/取消/取消后副作用                  | 中     | 高（重复副作用/数据错乱） | 节点/审批/工具唯一键；写工具重审批；未知副作用 fail-closed；取消优先；`IT-WF-103/104`                                                            |
| M06 运行/历史跨部门或平台管理员读取             | 低     | 高（越权）                | 双重 RBAC + 资源范围；统一 404；平台管理员 404；管理员读取审计；`TEN-M06-01`、`DEPT-M06-01`                                                      |
| M06 回滚后历史不可读/审计查询失败               | 中     | 高（运维/合规）           | DEC-0047=A 已确认：受支持回滚 = 关开关 + 回滚 Worker + 保留/前向修复 API；不做 down migration；RUNBOOK 记录限制；ADR-0024 §10.4                                |
| M06 运行队列挤占 J1/J2                          | 中     | 中（延迟）                | 独立 `workflow_run` 队列 + 默认并发 10 + 租约/看门狗；审批等待释放槽位；`PERF-M06-001`                                                           |
| M06 队列参数/租约/心跳不闭合 | 中 | 高（重复执行/静默降并发） | batch=1/槽位、独立调度池、心跳池≥并发、续期≤lease/3、启动校验；`IT-WF-103`、`OPS-M06-01` |
| M06 V15 单迁移非事务失败/联动断言变红 | 中 | 高（半迁移/构建红） | TRIGGER/binlog/MDL/包大小预检、失败矩阵/repair/备份恢复、DML 幂等；CI/ApiApplicationTests/SchemaVersionCheckerTest/restore-drill/RUNBOOK 同提交更新 |
| M05 索引 v2 重灌遗漏或跨租户写入                | 低     | 高（越权/缺检索）         | 以 MySQL 重灌、索引名由租户派生、逐租户切别名、v1 保留 7 天；`IT-KB-113`、`TEN-034`                                                              |

## 相关决策

- `docs/adr/ADR-0001-modular-monolith-two-deployables.md`
- `docs/adr/ADR-0002-test-object-storage-rustfs.md`
- `docs/adr/ADR-0003-tenant-context-thread-local.md`
- `docs/adr/ADR-0004-agentscope-core-not-starter.md`
- `docs/adr/ADR-0005-mysql-connector-j-license-gate.md`
- `docs/adr/ADR-0006-key-lifecycle-envelope-encryption.md`
- `docs/adr/ADR-0007-audit-event-model.md`
- `docs/adr/ADR-0008-mysql-outbox-async-and-observability.md`
- `docs/adr/ADR-0009-tenant-isolation-enforcement.md`
- `docs/adr/ADR-0010-flyway-single-migrator.md`
- `docs/adr/ADR-0011-b06-dependency-exceptions.md`
- `docs/adr/ADR-0012-m02-data-model-identifiers-and-tenant-keys.md`
- `docs/adr/ADR-0013-m02-session-auth-csrf.md`
- `docs/adr/ADR-0014-m02-api-event-contract-and-sse.md`
- `docs/adr/ADR-0015-m02-domain-modules-and-persistence.md`
- `docs/adr/ADR-0016-worker-outbox-claim-cross-tenant.md`
- `docs/adr/ADR-0017-approval-params-encrypted-replay.md`
- `docs/adr/ADR-0018-m03-organization-and-department-scope.md`
- `docs/adr/ADR-0019-m10-oidc-sso.md`
- `docs/adr/ADR-0020-m10-audit-query-export.md`
- `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md`
- `docs/adr/ADR-0022-m04-quota-and-cost.md`
- `docs/adr/ADR-0023-m05-knowledge-depth.md`
- `docs/adr/ADR-0024-m06-workflow-orchestration.md`
