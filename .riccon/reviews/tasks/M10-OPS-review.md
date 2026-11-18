---
review_id: REV-M10-OPS-001
module_id: M10
task_id: TASK-0064
reviewer:
  agent: riccon/ops
  session: ses_ops_M10_TASK-0064_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [e4dd9ad]
head_commit: 1dedcfe
scope: "M10 Ops 评审（非编码）：Secret 注入/轮换、V10 索引与回滚、worker schema 9→10、清理与 B-06；不改代码/迁移/契约/文档正文"
created_at: "2026-11-19T06:34:42+08:00"
findings:
  - id: OPS-001
    severity: P1
    status: open
    owner: riccon/lead + riccon/ops
    title: V10 落地后 CI 迁移冒烟与 RUNBOOK 结构断言会变红
    summary: "`.github/workflows/ci.yml:64` 断言 `tables=36`；V10 新增 `audit_export_request` 后为 37，main 分支 CI 必红。`docs/RUNBOOK.md:146/167/176/212-213` 仍写 V1–V7、36 张表，也需随 V10 更新（建议断言改为“表/索引存在 + triggers=2”而非总数）。当前 M10 任务图没有任何任务覆盖 ci.yml/RUNBOOK，B3 的 write_scope 也不含它们。"
    evidence: ".github/workflows/ci.yml:39-74；docs/RUNBOOK.md:112、146-217；ADR-0020 §8 任务表"
  - id: OPS-002
    severity: P1
    status: open
    owner: riccon/lead
    title: worker `EXPECTED_SCHEMA_VERSION` 9→10 无 owner
    summary: "ADR-0020 §5.3 要求 Builder 把 worker 期望版本升到 10，但 §8 的 B1–B5/V1 write_scope 均未覆盖 `apps/worker/**`。若 B3 只落 V10，DB 到 10 后所有 worker 以 `RICCON-WORKER-001 expected=9 actual=10` 启动失败（扩容/重启即故障）。需 Lead 给 B3 增加该文件或拆一个小任务（建议独立 B6，避免 B3 超 8 项）。"
    evidence: "apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:29,80-83；apps/worker/src/main/resources/application.yml（validation-enabled）"
  - id: OPS-003
    severity: P1
    status: open
    owner: riccon/builder (B1)
    title: OIDC 配置解析失败可能经 Spring 绑定错误泄露 secret
    summary: "ADR-0019 §1 要求不打印原始 JSON/clientSecret，但 Spring Boot 属性绑定失败的异常消息会带 `Property … Value: <原始字符串>`。若 `riccon.oidc.idps` 直接绑定为类型化对象，malformed JSON 会把含 `clientSecret` 的整段值写进启动日志/异常。B1 必须先把该属性接成 String、在应用内解析并只记录序号/字段路径/错误类型，且 `SEC-OIDC-001` 必须覆盖“带 malformed RICCON_OIDC_IDPS 的真实启动”日志扫描，而不只是单元测试解析器。"
    evidence: "ADR-0019 §1（52-57）、§9（238）；apps/api/src/main/resources/application.yml 尚无 oidc 段"
  - id: OPS-004
    severity: P1
    status: open
    owner: riccon/ops + riccon/builder (B3)
    title: V10 期间旧 worker 实例必然 crash-loop，必须协调切换而非纯滚动
    summary: "Flyway 默认 `ignoreMigrationPatterns=*:future`，旧 API（V1–V9 jar）可在 V10 已应用的库上启动；但 worker 严格比较 `MAX(success version)==10`，DB 升到 10 后任何旧 worker 实例重启都会启动失败。因此 M10 发布必须是“停 worker → 停 API 写 → 迁移 → 起新 API → 起新 worker”的协调切换（维护窗口），不能依赖 maxSurge/maxUnavailable 纯滚动；否则节点驱逐/探针失败/HPA 抖动即造成 worker 不可用与租约重试。"
    evidence: "SchemaVersionChecker.java:80-83；ADR-0010（迁移只由 API 执行）；ADR-0020 §5.3（199）"
  - id: OPS-005
    severity: P2
    status: open
    owner: riccon/builder (B3) + riccon/ops
    title: 分区表加索引需显式 INPLACE/LOCK 子句，并按维护窗口结论执行
    summary: "InnoDB 对普通二级索引 ADD KEY 返回 `HA_ALTER_INPLACE_NO_LOCK_AFTER_PREPARE`（LOCK=NONE 可用），分区表按分区执行；但没有显式子句时 MySQL 可能静默回退 COPY（长锁）。V10 应写成单条 `ALTER TABLE audit_event ADD KEY …, ADD KEY … , ALGORITHM=INPLACE, LOCK=NONE`：两条索引同语句保证原子（MySQL 8 原子 DDL，失败整体回滚，无半成品索引）。结论：生产执行选**维护窗口 + 停写**，在线 DDL 仅作演练后回退方案；不把“online DDL 可用”当作免窗口依据。"
    evidence: "MySQL 8.4 InnoDB `check_if_supported_inplace_alter`（ADD_INDEX 保持 online）；ADR-0020 §4（107）、§5.2（168-170）"
  - id: OPS-006
    severity: P2
    status: open
    owner: riccon/builder (B3) + riccon/ops
    title: 迁移失败恢复矩阵未定义（非事务 DDL + Flyway failed 行）
    summary: "MySQL DDL 非事务，V10 由 CREATE TABLE → 2 索引 → 4 组权限 INSERT 组成，中途失败会留下已提交的前缀。Flyway 会写入 success=0 的 V10 行，之后新 API 启动直接报 “Detected failed migration to version 10”，必须维护账号 `flyway repair` 后重试。恢复矩阵需按失败点写清：索引 ALTER 失败（表在、索引无 → 删表/repair/重跑）；权限 INSERT 失败（表与索引已在 → 只补幂等 INSERT，不要重建索引）；并禁止修改已应用的 V10 文件（checksum）。"
    evidence: "ADR-0020 §5.4（204-217）、ADR-0010；apps/api/src/main/resources/application.yml flyway.validate-on-migrate=true"
  - id: OPS-007
    severity: P2
    status: open
    owner: riccon/ops
    title: V10 回滚必须同时处理 flyway_schema_history 的 V10 行
    summary: "回滚 SQL（DROP TABLE/INDEX、删权限映射）只还原结构；若保留 history 的 V10 行，重新部署 V10 代码时 Flyway 认为已应用而跳过 → 缺表。回滚流程须含：记录该行 → 结构回滚 → 维护账号删除 `version='10' AND success=1`（ADR-0010 授权动作）→ 校验 `MAX(version)=9`。旧 API 启动本身不依赖删除（future 默认忽略），但删行是回滚后重新前滚的必要条件；ADR-0020 §5.4（217）的“按 ADR-0010 处理”要落成具体 SQL。"
    evidence: "ADR-0020 §5.4（204-217）；Flyway 默认 ignoreMigrationPatterns=*:future"
  - id: OPS-008
    severity: P2
    status: open
    owner: riccon/architect + riccon/builder (B3)
    title: audit_export_request 缺按 created_at 的清理路径
    summary: "ADR 说导出请求由 M09 按 ≥180 天清理，但 V10 只有 `(tenant_id,status,created_at)` 与 `(tenant_id,status,expires_at)`，无 `created_at` 前缀索引，跨租户清理无法走索引。建议 V10 现在补 `KEY idx_audit_export_created (created_at)`（新表为空，零成本）；若不改，M09 清理必须逐租户循环并写入运行手册。"
    evidence: "ADR-0020 §5.1（134-135）、§4（109）"
  - id: OPS-009
    severity: P2
    status: open
    owner: riccon/builder (B3)
    title: 流式导出在客户端下载期间占用 DB 连接
    summary: "CSV 在请求线程内边查边写会把 DB 连接持有到客户端接收完毕（10k 行 details JSON 可能数 MB，慢客户端更久），与 API 连接池/Worker 争用。B3 应在行数上限内先把结果物化到有界内存（同时释放连接）再流式输出，或至少设置语句/请求超时与并发上限；运维侧需监控导出下载时长与连接池占用。"
    evidence: "ADR-0020 §2（80-88，rowLimit 10000、不落盘）"
  - id: OPS-010
    severity: P2
    status: open
    owner: riccon/ops + riccon/product
    title: OIDC 代码回滚会锁死已绑定账号
    summary: "绑定后 `user_credential` 被删除，回滚到 M02/M03 代码后这些账号无本地密码。回滚 runbook 必须“先解绑/写回本地密码（含平台管理员路径），再回滚代码”；且回滚前清单须列出每个租户 `auth_source=OIDC` 的 TENANT_ADMIN（否则整个租户失去管理员）。建议把“OIDC 账号恢复”列入 V1 演练。"
    evidence: "ADR-0019 §3（107、115）、§6（195-198）；DEC-0014（显式绑定）"
  - id: OPS-011
    severity: P2
    status: open
    owner: riccon/verifier (V1)
    title: 测试 IdP 密钥不得入库，避免 B-06 扩大
    summary: "`trivy fs --scanners secret` 会扫 `e2e/**`。若测试 IdP 提交静态签名私钥/固定 clientSecret，会被判 secret，只能用新增 `.trivyignore` 抑制——那就是扩大 B-06。V1 必须运行时生成密钥、凭据走环境变量或临时文件，使 security gate 保持 0 未抑制。"
    evidence: ".trivyignore（仅 2 条 MCP CVE，exp 2027-03-31）；ADR-0020 §9（330）、ADR-0019 §6（193）"
  - id: OPS-012
    severity: P2
    status: open
    owner: riccon/architect + riccon/builder (B1)
    title: “Helm Secret/挂载 Secret”与单字符串属性不一致
    summary: "ADR-0019 写“Helm Secret/挂载 Secret 注入”，但配置是 `${RICCON_OIDC_IDPS:[]}` 单一字符串；挂载文件需 `spring.config.import=optional:configtree:` 之类的支持，否则 M09 无法按文件注入。M10 基线明确为“K8s Secret → 环境变量”，并接受 env 对容器内进程/`docker inspect` 可见的残余风险（靠 pod 权限收敛）；若 M09 要文件注入，B1 现在就应支持 `idps-file`，不要留到部署期返工。"
    evidence: "ADR-0019 §1（38、57）、§6（163-176）"
  - id: OPS-013
    severity: P2
    status: open
    owner: riccon/ops + riccon/product
    title: 平台租户导出 403 的合规取数缺口需在 M09 兜底
    summary: "M10 平台租户（`tenant_id=platform`）申请/下载审计导出一律 403（deferred）。客户合规审计若需要平台侧事件导出，M10 无自助路径；M09 需定义“维护账号受控 SQL 导出 + 审批 + 留存”的兜底流程并写入运行手册，同时在验收材料中保持 residual/deferred 表述，不得声称已支持。"
    evidence: "ADR-0020 §2（56）、§10（340）；contracts/openapi.yaml x-riccon-deferred"
  - id: OPS-014
    severity: P2
    status: open
    owner: riccon/builder (B3/B1) + riccon/ops
    title: 审计读取 fail-closed 对历史/运维直写事件的可用性风险
    summary: "读取对“未知 action + 非空 details / 白名单外键”返回 500，而 audit_event 不可 UPDATE/DELETE，一旦历史行不合规就无法修补。上线前需做一次 action/details 清点（distinct action + details 键集合）确认 API+Worker 白名单覆盖；运维脚本直写审计时只能用白名单动作且 details 为空（如 RUNBOOK 的 `migration.smoke` 无 details），否则会让包含该行的查询整页 500。"
    evidence: "ADR-0020 §1（43）、§6.1（248-253）；apps/api AuditDetailsPolicy、worker AuditDetailsPolicy（两套白名单并集）"
  - id: OPS-015
    severity: P3
    status: open
    owner: riccon/builder (B1) + riccon/ops
    title: publicBaseUrl 缺生产校验；回调 query string 不得进访问日志
    summary: "ADR-0019 校验 issuer 为 HTTPS，但未校验 `riccon.public-base-url`。生产若误配 http 或带尾斜杠，会造成 redirect_uri 不匹配与 Secure Cookie 失效。建议 B1 fail-fast（非 local/test 时要求 absolute https、无尾斜杠、非 localhost），或在启动日志显式告警。另：反代访问日志若用 `$request` 会记录 `?code=…&state=…`，必须改为 `$uri` 或对 `/api/v1/auth/oidc/callback/**` 脱敏；导出下载需 no-store、不缓存、代理读超时 ≥ 生成时间。"
    evidence: "ADR-0019 §1（52）、§6（163-176）；application.yml server.servlet.session.cookie.secure"
  - id: OPS-016
    severity: P3
    status: open
    owner: riccon/ops
    title: OIDC 配置变更需要全实例一致重启
    summary: "配置无热更新，混跑新旧配置会出现“同一租户在新实例可登录、旧实例提示未开通”。运维流程须规定：改 `RICCON_OIDC_IDPS` = 全副本协调重启（非逐台长窗口滚动），新租户开通公告放在全部实例就绪之后；`publicBaseUrl` 变更另需先改 IdP 回调注册、证书与新主机 Cookie 域名，属变更审批而非例行轮换。"
    evidence: "ADR-0019 §1（55-57）、§6（163）"
  - id: OPS-017
    severity: P3
    status: open
    owner: riccon/ops
    title: audit_event 分区只预建到 2027-12 + pmax
    summary: "V6 预建到 `p2027_12` 并留 `pmax`。M09 必须提前滚动新增月度分区，否则 2028 起数据全落 pmax，既无法按分区 DROP 也不利于索引/查询；纳入 M09 例行维护与告警（距最后分区 < 60 天告警）。"
    evidence: "apps/api/src/main/resources/db/migration/V6__audit_event.sql（分区定义）"
  - id: OPS-018
    severity: P3
    status: open
    owner: riccon/ops + riccon/product
    title: 导出 TTL/重下载语义与支持流程
    summary: "批准 24h（上限 7 天）后下载返回 410，且无重新签发接口；同一下载在有效期内重复生成（occurredTo 固定，理论上行集稳定，但边界事件可能变化，非字节一致）。支持流程需写明“过期后重新申请 + 审批”，并把 `riccon.audit.export.ttl`、`riccon.audit.retention-days`（<180 启动失败）纳入生产配置基线。"
    evidence: "ADR-0020 §1（46）、§2（69-71、78-80）"
evidence: []
---

# REV-M10-OPS-001：M10 Ops 评审（TASK-0064）

## 结论

**verdict: pass（条件式）。** ADR-0019/0020 的运维面**可实现、可回滚**：OIDC 全部状态在部署 Secret 与既有会话层，无新增有状态服务；V10 是纯 additive 迁移，旧代码可继续启动（Flyway 默认忽略 future 迁移），回滚只丢导出请求数据、`audit_event` 不受影响。

本结论的边界：

- **不阻塞 B1/B2**：两者无迁移、无新增部署依赖；B1 只需按 OPS-003 落实 secret 脱敏，B2 与运维无交集。
- **不构成 V10 生产执行批准**：本评审通过与 must-fix 关闭、V1 非生产演练证据、M09 Helm/备份/迁移账号就绪，三者共同构成前置；生产动作仍需用户按 T4 明确批准。
- **M10 gate 前置**：must-fix 中 OPS-001/002/003、OPS-004/005/006/007 的落地物（CI 与手册更新、worker 版本、演练记录）必须在 gate 前具备；其余为 M09 或后续模块承接。

## 审查范围与命令

- 起点：`git status --short` 空；HEAD `1dedcfe`（`chore(M10): B1 等待依赖任务，启动 TASK-0066`）。
- 读取：ADR-0019 全文、ADR-0020 §1–§10、`docs/RUNBOOK.md`、`docs/SANDBOX.md`、`AGENTS.md` 合同（T4、`commands.*`、`data.retention`、`constraints.security`）、`deploy/compose/docker-compose.test.yml`、`V6__audit_event.sql`、`V9__organization_and_workspace_scope.sql`、V1/V2 的 `permission`/`role`/`role_permission`/`app_user` 定义、`SchemaVersionChecker`、`apps/api|worker application.yml`、`.github/workflows/ci.yml`、`.trivyignore`、DEC-0012、`contracts/openapi.yaml` 的审计/OIDC 端点。
- 外部依据：MySQL 8.4 InnoDB `check_if_supported_inplace_alter`（ADD_INDEX 走 online，返回 `HA_ALTER_INPLACE_NO_LOCK_AFTER_PREPARE`）；Flyway `ignoreMigrationPatterns` 默认 `*:future`。
- **未执行**：未跑任何 DDL/迁移/测试/E2E（任务要求不跑全量；仓库无生产库与 Helm chart）。在线 DDL 结论来自引擎行为与文档，**必须**由 V1 在副本上实测确认（见 §6.4）。

## 1. Secret：注入、轮换、回滚

### 1.1 注入

| 键                       | 来源                                                      | 生产要求                                                  |
| ------------------------ | --------------------------------------------------------- | --------------------------------------------------------- |
| `RICCON_OIDC_IDPS`       | K8s Secret → 容器环境变量（JSON 数组，含 `clientSecret`） | 禁止进仓库/镜像/values/证据/提示词；只允许 `secretKeyRef` |
| `RICCON_PUBLIC_BASE_URL` | ConfigMap/环境变量                                        | 绝对 `https://`、无尾斜杠、与 IdP 精确注册的回调前缀一致  |
| `RICCON_OIDC_ALLOW_HTTP` | 环境变量                                                  | 生产固定 `false`                                          |
| `RICCON_COOKIE_SECURE`   | 环境变量                                                  | 生产 `true`（TLS 终止后）                                 |

- **托管边界**：`clientSecret` 只存在于 Secret 与环境变量内存。env 对同容器进程、`/proc/1/environ`、`docker inspect`（宿主有权限者）可见；补偿控制为 pod 级 RBAC 收敛、禁止 `kubectl exec`、禁止将进程环境或堆转储写入共享存储。ADR-0019 §1 的“挂载 Secret”若指文件注入，需要 `spring.config.import=...configtree:` 支持，当前属性形态不支持（OPS-012）。
- **启动校验**：malformed JSON 必须由应用自行解析并脱敏（OPS-003）。部署策略上要求“先起 1 个新副本（canary）再扩”，使配置错误只影响 canary；但 V10 迁移与 API 启动耦合时该策略失效，见 OPS-004。

### 1.2 轮换

1. IdP 侧先允新旧 `clientSecret` 并存。
2. 更新 Secret → **全副本协调重启**（配置无热更新；混跑会新旧配置并存）。
3. 用新 secret 完成一次企业登录与一次淘汰旧 secret 后的登录，记录证据。
4. IdP 侧吊销旧 secret。

在途影响：已发出未回调的授权码可能失败并落到 `idp_unavailable`，用户重试即可；因为不存 token，**轮换无需失效会话**。

### 1.3 回滚

| 场景                     | 动作                                    | 后果                                           |
| ------------------------ | --------------------------------------- | ---------------------------------------------- |
| 配置写错/IdP 故障        | 移除该租户项或 `enabled=false` → 重启   | 企业登录关闭，内置账号可用；已绑定账号无法登录 |
| 需要保留 OIDC 但代码回退 | **先**解绑/写回本地密码，**再**回滚代码 | 否则账号无凭据被锁死（OPS-010）                |
| `issuer` 变更            | 不靠改配置静默完成；按身份迁移流程重绑  | 属身份迁移，需变更审批                         |

**日志与错误**：`RICCON_OIDC_IDPS` 原始值、`clientSecret`、`code`/`state`/token 都不得进入日志、审计、Actuator、启动 banner。Actuator 当前只暴露 `health,info` 且 `show-details: never`，满足要求；但反代访问日志默认会记录 query string（OPS-015）。

## 2. publicBaseUrl、代理头与外部 URL

- `redirect_uri` 与 `post_logout_redirect_uri` 由 `publicBaseUrl` 拼出（ADR-0019 §2、§5），因此**代理头不是正确性来源**；显式配置才是权威。仍要求 `server.forward-headers-strategy=framework` 且反代透传 `X-Forwarded-Proto/Host`，否则 Cookie `Secure` 判断、绝对 URL 与 302 目标可能不一致。
- 生产要求 TLS 终止在反代、`RICCON_COOKIE_SECURE=true`；`publicBaseUrl` 为 http 或含尾斜杠会导致回调不匹配，建议 B1 fail-fast（OPS-015）。
- `X-Riccon-Federated-Logout-Url` 指向 IdP `endSessionEndpoint`，属外域顶层导航；反代 CSP/`frame-ancestors` 不涉及，但**不得**改写或缓存 204 响应头。
- 出网：API 需访问 IdP 的 discovery/JWKS/token/end-session（443）。私有化环境的网络策略应只允许这些 host，禁止通配；IdP 使用客户内网 CA 时，必须把 CA 注入 JVM truststore（`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=…`），否则表现为 `idp_unavailable`。节点需 NTP，ID Token 允许偏移 ≤60s。
- **测试 IdP 不进生产**：`e2e/oidp/**`、`e2e/support/oidc/**` 只在 V1 与测试 compose 内；生产镜像不得打包 `e2e/`，测试 IdP 端口只绑 `127.0.0.1`，密钥运行时生成（OPS-011）。

## 3. V10：DDL 评审

### 3.1 `audit_export_request`

- 键设计正确：`(tenant_id, id)` 主键保证租户维度寻址；复合外键 `(tenant_id, requested_by) → app_user` 阻止跨租户申请人；`approved_by` 不建外键（平台审批人属 `platform`）符合 ADR；三条 CHECK（状态/行数/审批字段一致性/自批）在 MySQL 8.4 生效。
- `filters JSON NOT NULL` 限 4 KiB（应用层）——服务端必须校验长度，避免 4 KiB 之外的写入放大。
- 建表时对父表 `app_user` 取短暂 MDL；`app_user` 持续增长，执行前需确认无长事务（见 §3.3）。
- 缺 `created_at` 清理索引（OPS-008）。
- **不参与 append-only**：导出请求可 DELETE，清理不会触发 `audit_event` 触发器；`audit_event` 仍只允许 INSERT。

### 3.2 分区表加索引：结论

- 引擎侧：普通二级索引 ADD INDEX 走 in-place 且可 `LOCK=NONE`；分区表按分区逐个构建，预建的 2027 空分区近乎瞬时。
- 但“可 online”≠“可无窗口”：
  1. `audit_event` 是**持续写入**表（每次业务动作都写审计）。在线构建期间并发 DML 进入 `innodb_online_alter_log_max_size`（默认 128 MiB），超限报 `ER_INNODB_ONLINE_LOG_TOO_BIG` 并整体回滚 → 长时间构建后失败重试。
  2. ALTER 持有 `MDL_SHARED_UPGRADABLE`，末尾需升级；长事务/大范围审计查询会互相阻塞，`lock_wait_timeout` 默认极长，可能造成“DDL 卡住 + 新查询排队”。
  3. 副本环境（若客户自建只读副本）会在副本上串行重放该 ALTER，产生明显延迟。
- **Ops 结论：V10 生产执行采用维护窗口 + 停写**（API/Worker 均停或进维护态），不把 online DDL 作为免窗口依据；online DDL 可作为窗口内加速手段，并在演练中记录实际耗时。

### 3.3 执行前置（生产）

```sql
-- 1) 预检：权限码未被提前占用、audit.read 无历史手工授予（ADR-0020 §5.4 F-022 前提）
SELECT code FROM permission WHERE code IN ('audit.export.approve','platform.audit.export.approve');
SELECT COUNT(*) FROM role_permission
 WHERE permission_code IN ('audit.read','audit.export.approve','platform.audit.export.approve');

-- 2) 体量与写放大基线
SELECT partition_name, table_rows, data_length, index_length
  FROM information_schema.partitions
 WHERE table_schema = DATABASE() AND table_name = 'audit_event'
 ORDER BY partition_ordinal_position;
SHOW VARIABLES LIKE 'innodb_online_alter_log_max_size';
SHOW VARIABLES LIKE 'lock_wait_timeout';

-- 3) 确认没有长事务/未释放 MDL
SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE object_name IN ('audit_event','app_user');

-- 4) 磁盘余量：两索引预计 = 行数 × (tenant_id 64 + action 100|actor_id 64 + occurred_at 8 + 开销)
--    预留 ≥ 1.2 × 预计索引体积 + binlog 增量
```

其它前置：全量备份并**做一次恢复到副本的验证**；确认迁移账号具备 DDL 权限（与 ADR-0010 一致）；确认 `audit_event` 插入速率已归零（`SELECT MAX(occurred_at)` 60s 不变）。

## 4. 执行与滚动顺序（API/Worker）

V10 与代码强耦合（新 API 需要 V10 对象），且 worker 严格校验 schema 版本，因此**只能协调切换**：

```text
0. 备份 + 预检（§3.3）
1. 停 worker（drain；接受在途运行按租约 90s 重试或先等待结束）
2. 停 API 写入（维护模式 503 或缩容到 0）
3. 用新 API 镜像的一次性迁移 Job 执行 Flyway（不经由常驻 API 启动触发）
4. 结构校验（§5.1）
5. 起新 API（v10）→ 健康检查 → smoke（内置登录、审计查询、导出闭环、J1/J2 冒烟）
6. 起新 worker（EXPECTED=10）→ 确认 outbox 恢复领取
7. 解除维护模式
```

为什么不是纯滚动：DB 升到 10 后，任何**旧 worker** 实例重启都会 `RICCON-WORKER-001 expected=9 actual=10` 失败（OPS-004）；旧 API 虽能在 V10 库上启动（Flyway future 默认忽略），但旧实例在滚动期一旦重启仍是可用性风险。把迁移从“常驻 API 启动”挪到一次性 Job，也让长 DDL 不受 Pod 启动探针超时约束。

回滚顺序（严格反向）：

```text
1. 停新 worker → 停新 API
2. （若涉及 OIDC）先在新版本上解绑/写回本地密码（OPS-010）
3. 执行 ADR-0020 §5.4 回滚 SQL（单条 ALTER DROP INDEX；先删 role_permission 再删 permission）
4. 维护账号删除 flyway_schema_history 的 V10 行，并记录操作（OPS-007）
5. 起旧 API（v9）→ 健康检查
6. 起旧 worker（v9）→ 确认版本校验通过
7. 记录回滚证据：结构、行数、审计可读性、导出请求丢失数量
```

禁止：DB 在 10 时启动旧 worker（必然失败）；DB 回滚后保留 V10 history 行（再前滚会跳过迁移导致缺表）；修改已应用的 V10 文件。

## 5. 验证与失败恢复

### 5.1 结构校验

```sql
SHOW CREATE TABLE audit_export_request;                       -- 表/索引/CHECK/外键
SELECT DISTINCT index_name FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'audit_event'
   AND index_name IN ('idx_audit_tenant_action_time','idx_audit_tenant_actor_time');
SELECT index_name, COUNT(*) AS partitions_covered FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'audit_event' GROUP BY index_name;
SELECT COUNT(*) FROM information_schema.triggers
 WHERE trigger_schema = DATABASE() AND event_object_table = 'audit_event';   -- 必须仍为 2
SELECT COUNT(*) FROM role_permission rp JOIN role r
  ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
 WHERE r.code = 'TENANT_ADMIN' AND rp.permission_code = 'audit.read';        -- 应等于有管理员的租户数
SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;  -- 10
```

### 5.2 失败恢复矩阵

| 失败点                                      | 残留                       | 恢复                                                                                                   |
| ------------------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------ |
| `CREATE TABLE` 失败（FK/重复表）            | 无                         | 修因 → `flyway repair` → 重跑；若表已存在需先确认是否半成品                                            |
| 索引 ALTER 失败（超 online log / MDL 超时） | 表已建、索引无（原子回滚） | 确认 `SHOW CREATE TABLE audit_event` 无新索引 → `flyway repair` → 窗口内重跑；**不要**手建其中一个索引 |
| 权限 INSERT 失败                            | 表 + 索引已就绪            | 只补幂等 INSERT（`INSERT IGNORE` 语义由维护账号执行）→ 校验后 `flyway repair`                          |
| 迁移 Job 中途被杀                           | 取决于最后提交的语句       | 按上表定位；禁止反复重跑而不 repair                                                                    |

### 5.3 非生产演练（V1 必交证据）

1. 在一次性 MySQL 8.4.13 容器上应用 V1–V9 + 种子数据（多租户、每租户 TENANT_ADMIN、审计行跨 ≥2 个分区、含模型事件 `promptTokens`/`completionTokens` 与 Worker 事件）。
2. 用 **Flyway** 应用 V10（不是 mysql CLI 直放，需覆盖 history 行为）；执行 §5.1 全部断言。
3. 负向：`SchemaVersionChecker` 对 V9 必须失败、对 V10 必须通过。
4. 回滚：执行 §4 回滚步骤 → 旧 API/旧 worker 起得来、`audit_event` 可读、导出请求丢失（预期）。
5. 失败注入：预先创建同名索引使 ALTER 失败，确认无半成品索引 + repair/重跑路径可用（OPS-006）。
6. 演练记录写入 M10 评审包；不连接热栈，容器一次性销毁（`docs/SANDBOX.md`）。

## 6. 清理、保留与导出运维含义

- **保留**：`audit_event` ≥180 天，M09 按分区 `DROP PARTITION`；V10 不改变 append-only 与触发器。分区预建到 2027-12（OPS-017）。
- **`audit_export_request`**：不属 append-only，M09 按 ≥180 天清理（OPS-008 需索引或逐租户循环）。删除导出请求不影响 `audit_event`；但 `filters`/`approval` 记录届时一并丢失，属预期。
- **导出不落盘**：无对象存储、无临时文件，过期即 410——运维含义是“没有可被事后读取的副本”，因此**不要**引导用户把 CSV 存到共享盘；合规归档另立模块。流式请求占用 DB 连接（OPS-009），需纳入连接池与下载时长监控。
- **TTL/410**：批准后 24h（`riccon.audit.export.ttl`，上限 7 天）；过期无重发接口，支持流程为“重新申请 + 审批”。重复下载会重新生成，非字节一致（OPS-018）。
- **平台租户 403**：平台侧无自助导出，M09 需受控 DB 导出兜底（OPS-013），文档保持 deferred。
- **fail-closed 读取**：历史/运维直写事件若不合规会让查询 500 且无法修补（append-only），上线前清点（OPS-014）。

## 7. B-06 与安全门禁

- `.trivyignore` 仍只有 `CVE-2026-35568`（HIGH）与 `CVE-2026-34237`（MEDIUM），`exp:2027-03-31`，与 DEC-0012 “2027-03-31 或 M07 gate 取早”一致；**M10 未扩大例外**。
- 新增依赖仅为 `spring-boot-starter-security-oauth2-client`（Boot BOM 管理，Apache-2.0，内含 Nimbus）。若它或传递依赖带来 high/critical，**不得**新增抑制；应升级/替换并上报 Lead（B-06 例外只覆盖 MCP SDK 的两条）。
- M10 gate 的 `commands.security` 必须 **0 条未抑制** findings；`secret` 扫描覆盖 `e2e/**`（OPS-011）。OIDC 的 `clientSecret` 不得出现在证据、日志、镜像层或 CI 变量打印中。

## 8. 可观测性（M09 承接，M10 gate 只需日志证据）

- 登录：按 `method` 的成功/失败计数、`idp_unavailable` 比例、`providers` 端点 429 速率；发现文档刷新失败日志只含 issuer 与错误类型。
- 审计：查询 p95、`RICCON-AUDIT-500` 计数（fail-closed 触发即告警）、导出申请/审批/下载计数、410 比例、下载时长与 DB 连接池占用。
- 审计写入失败必须告警并使业务失败（既有约束），不得降级为只写日志。
- 告警阈值与面板属 M09；M10 只要求上述事件在日志中可定位（含 `traceId`、`exportId`、`reasonCode`），且不含 token/secret/query string。

## 9. Must-fix（关闭后方可 V10 生产 / M10 gate）

| ID      | 内容                                                                             | Owner                    | 阻塞                             |
| ------- | -------------------------------------------------------------------------------- | ------------------------ | -------------------------------- |
| OPS-001 | CI 迁移冒烟与 RUNBOOK 的 36 表/V1–V7 断言更新为 V10 结构（建议改为结构断言）     | ops 跟进任务 + Lead 建卡 | B3 合入后 main CI；M10 gate 文档 |
| OPS-002 | 给 worker `EXPECTED_SCHEMA_VERSION` 9→10 指定 owner（B3 扩范围或独立任务）       | Lead                     | V10 生产、M10 gate               |
| OPS-003 | B1 以 String 承接 `RICCON_OIDC_IDPS` 并脱敏解析；`SEC-OIDC-001` 覆盖真实启动日志 | B1                       | M10 gate 安全证据                |
| OPS-004 | 采用协调切换（停 worker/API → 迁移 → 起新版本），迁移用一次性 Job                | ops 运行手册 + B3        | V10 生产                         |
| OPS-005 | V10 索引 ALTER 采用单语句 `ALGORITHM=INPLACE, LOCK=NONE`，生产走维护窗口停写     | B3 + ops                 | V10 生产                         |
| OPS-006 | 写清迁移失败恢复矩阵（repair 流程、禁止改已应用文件）                            | ops 运行手册 + V1 演练   | M10 gate（回滚/恢复记录）        |
| OPS-007 | 回滚含 flyway V10 history 行处理的具体步骤                                       | ops 运行手册 + V1 演练   | M10 gate                         |
| OPS-008 | `audit_export_request` 补 `created_at` 清理索引，或 M09 逐租户清理写入手册       | architect/B3             | M09（建议 V10 现补）             |
| OPS-009 | 导出先有界物化再流式，或限制并发/超时                                            | B3                       | V10 生产（容量）                 |
| OPS-010 | OIDC 回滚前的账号恢复流程                                                        | ops + V1 演练            | V10 生产 / OIDC 启用             |
| OPS-011 | 测试 IdP 密钥运行时生成，不新增 `.trivyignore`                                   | V1                       | M10 gate（security）             |
| OPS-014 | 上线前 action/details 清点，确认读取白名单覆盖                                   | B3/V1                    | M10 gate                         |

其余 finding（OPS-012/013/015/016/017/018）为 M09 或文案/流程承接，不阻塞 M10 gate。

## 10. B1/B2 是否受阻

**不受阻。** 两者无迁移、无新部署单元；B1 的 secret 脱敏本就在其验收内（OPS-003 只是把“真实启动路径”明确成验收口径），B2 与运维无交集。B1 当前 blocked 的原因是等待 TASK-0066 的 starter（`TASK-0065.json`），与本评审无关。

## 11. 未验证

- 未在 MySQL 上执行 V10/回滚/失败注入（本任务只读评审）；§3.2 的在线 DDL 结论来自 InnoDB 源码路径与官方语义，**必须**由 V1 演练实测。
- 无生产/预览环境与 Helm chart（M09 才交付），所以 Secret 注入、TLS/代理、CA、网络策略均无法实测，只能给出要求。
- 未运行 `verify`/E2E/a11y/security/性能；这些属 M10-V1 与 gate 证据。
- 未核对 Spring Security 7 OIDC 实现细节（B1 的 IT/SEC 用例负责）。

## 12. 剩余风险

1. **单点发布风险**：V10 与 M10 代码同批上线，回滚窗口内导出功能整体不可用（可接受，属新功能）。
2. **审计不可修补**：读取 fail-closed + append-only 意味着历史数据问题无法在线修（OPS-014 以清点缓解）。
3. **M09 缺口**：备份/恢复、迁移账号权限、连接池与导出监控、分区滚动、平台侧取证导出均未实现；本评审只确认“M10 不使这些问题恶化”。
4. **残留可见性边界**：M03 F-009（目标租户看不到平台来源事件）在 M10 仍是 residual，不得在运维文档中表述为已解决。
5. **Secret env 可见性**：`clientSecret` 在容器环境与进程内存中可被高权限者读取；靠平台权限收敛，非应用层可消除。

## 13. 需要用户拍板的决策

**没有新的、阻塞 B1/B2 的决策。** 唯一需要用户在时点到来时明确表态的是：**M09 阶段 V10 生产迁移的维护窗口与发布批准**（T4 生产动作，属 `production` owner；本评审不代替）。若客户要求“平台租户审计可导出”（OPS-013），则需另行决策是接受 M10 deferred，还是在 M09 增加受控导出流程。
