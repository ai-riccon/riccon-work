---
review_id: REV-M04-ARCH-001
module_id: M04
task_id: TASK-0089
reviewer:
  agent: riccon/verifier
  session: ses_e09ab05e9ffeC5WKUz69Crj17M
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
findings:
  - id: F-001
    severity: P1
    status: open
    title: OpenAPI M04 契约结构缺陷：provider PUT 路径参数、10/10 写端点缺 CSRF、AuditTargetType 缺新值
    summary: >-
      `PUT /api/v1/admin/model-providers` 的 operation 声明了 `providerId` path 参数，但路径模板没有 `{providerId}`；
      M04 的 10 个写端点全部没有声明 `X-CSRF-TOKEN`；`AuditTargetType` 枚举缺少 ADR 使用的 `MODEL_ROUTING`、
      `MODEL_CREDENTIAL`、`QUOTA`。三者都会让契约测试/生成客户端无法按 ADR 实现。
    evidence: "contracts/openapi.yaml:2686,2709-2720,14-16,3947-3967; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:443,486-498; docs/adr/ADR-0022-m04-quota-and-cost.md:255-258; platform/platform-domain/src/main/java/com/riccon/platform/domain/audit/AuditTargetType.java:8-36"
  - id: F-002
    severity: P1
    status: open
    title: 审计 details 校验拒绝数组与 null，路由保存和首次配额设置会因 fail-closed 失败
    summary: >-
      ADR-0021 的 `model.routing.updated` details 使用 `modelIds` UUID 数组，但 API 的 `AuditDetailsPolicy.validateValue`
      只允许 String/Number/Boolean；ADR-0022 的 `quota.limit.changed.previousLimit` 在首次设置时没有旧值，若按 ADR
      写 null 会被同一校验拒绝（省略又会丢失“前后值”语义）。审计 fail-closed 会把这两类正常写入变成 400/500，
      AC-02 与 AC-10 的保存路径不可用。
    evidence: "apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:180-194; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:301,488; docs/adr/ADR-0022-m04-quota-and-cost.md:139,257"
  - id: F-003
    severity: P1
    status: open
    title: AC-11 平台侧“成本明细可展开”与 AC-12/OQ-19/ADR-0022 的平台无用户明细冲突；契约还允许平台返回用户字段
    summary: >-
      AC-11 的 Then 要求成本明细可展开查看每次调用的价格快照，但 ADR-0022 §2.4 明确平台管理员不提供调用明细；
      AC-12/OQ-19 又要求平台只看到租户聚合、不展示租户内用户级明细。同时 `CostBucket` 仍允许 `userId`/`userDisplayName`，
      `CostReport.groupBy` 枚举包含 `USER`，使跨租户用户字段泄露无法被契约测试发现。
    evidence: "docs/PRODUCT.md:355-356; docs/adr/ADR-0022-m04-quota-and-cost.md:195-199; contracts/openapi.yaml:5851-5872,5891-5925"
  - id: F-004
    severity: P1
    status: open
    title: CostInvocation.priceCurrency 必填且非空，与 UNPRICED/USAGE_UNKNOWN 的空值口径冲突
    summary: >-
      契约把 `priceCurrency` 放入 `CostInvocation.required` 且声明为非空字符串；ADR-0022 允许未定价/缺 usage/全链失败调用
      的价格与币种为空，ADR-0021 的 `price_currency` 列也可空。成本明细接口无法同时满足两者，契约测试或响应校验必然失败。
    evidence: "contracts/openapi.yaml:5932-5968; docs/adr/ADR-0022-m04-quota-and-cost.md:161,180-188; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:197"
  - id: F-005
    severity: P1
    status: open
    title: AC-17 首字 p95 ≤3s 与 5s connect timeout/20s 首字预算冲突；现有客户端内层重试会放大尝试次数并绕过审计
    summary: >-
      ADR-0021 §5.2 设置 connect-timeout 5s、首字前总预算 20s，而 AC-17 要求在注入首字前超时时仍满足 p95 ≤3s；
      若主模型挂起，单次连接超时就已超过 3s。现有 `OpenAiCompatibleChatModel` 还会以 `maxAttempts=3` 对 429/5xx 做内层重试，
      M04 外层路由若再重试，实际 HTTP 尝试数、`model_invocation_attempt`、`model.failover.triggered` 审计和“尝试上限 5”都会失真。
    evidence: "docs/PRODUCT.md:361; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:335-339; apps/worker/src/main/java/com/riccon/worker/config/ModelGatewayProperties.java:24,47-58; apps/worker/src/main/java/com/riccon/worker/model/service/OpenAiCompatibleChatModel.java:138-187"
  - id: F-006
    severity: P1
    status: open
    title: AC-17 的可观察性与 50 并发不可交付：无 metrics 端点/依赖，且 Worker 默认只允许 16 条流
    summary: >-
      AC-17 要求运维可观察降级次数、按 provider/model 的错误率、配额拒绝数与当前并发 run；但 API/Worker 的 actuator
      只暴露 health/info，仓库没有 Micrometer/Prometheus 依赖，任务图也没有 metrics 实现归属。Worker 的
      `RICCON_CHAT_MAX_STREAMS` 默认 16，50 并发会在第 17 个请求返回 `RICCON-QUEUE-429`；没有任务负责性能环境配置。
    evidence: "apps/api/src/main/resources/application.yml:51-55; apps/worker/src/main/resources/application.yml:26-30,93; apps/worker/src/main/java/com/riccon/worker/config/ChatStreamProperties.java:20; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:707-709; docs/PRODUCT.md:361"
  - id: F-007
    severity: P1
    status: open
    title: 任务图缺少关键文件/环境所有权：real-stack run.mjs、RoleDefinitions、Web API client、测试 profile、契约文件
    summary: >-
      M04 新增 API 主密钥依赖、受管端点、DEMO 模式、计费币种和 50 并发要求，但 `scripts/real-stack/run.mjs` 不在 B8/B10
      write_scope；API env 仍缺 `RICCON_MASTER_KEY`/`RICCON_MODEL_ENDPOINTS`/`RICCON_MODEL_DEPLOYMENT_MODE`。新权限没有加入
      `RoleDefinitions`，M04 后新开通租户不会获得 quota/cost 权限。B9 需要给 `RicconApi` 增加方法，但 `client.ts` 不在其 scope。
      测试 profile 与 `contracts/openapi.yaml` 也没有明确 owner。
    evidence: "scripts/real-stack/run.mjs:214-252; apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:45-78; apps/web/src/services/api/client.ts:74; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:585,593-595; apps/api/src/test/resources/application-test.yml"
  - id: F-008
    severity: P1
    status: open
    title: 配额准入的审计 fail-closed 与并发 SQL 设计存在不可实现点：拒绝审计会被回滚，INSERT IGNORE 可能死锁/吞错
    summary: >-
      ADR-0022 要求任一步配额失败时整体回滚，又要求 `quota.exceeded` 审计失败时返回 500；如果审计写在同一事务，
      拒绝时会被一起回滚，无法留下“无审计不拒绝”的记录。准入 SQL 先 `INSERT IGNORE` 再条件 UPDATE；50 并发同一 PK
      的 INSERT IGNORE 会取共享锁并与 UPDATE 升级产生死锁，且 INSERT IGNORE 会吞掉非重复键错误，导致 500 或错误拒绝。
    evidence: "docs/adr/ADR-0022-m04-quota-and-cost.md:97-117,139; docs/PRODUCT.md:353-354"
  - id: F-009
    severity: P1
    status: open
    title: 拆分后的 AC/gate 判定不可执行：AC-13/14 原子、AC-17/18 跨模块、模块 acceptance 仍写 18 条、M04b 不是实际模块 ID
    summary: >-
      ADR-0021 §8.4 把 AC-13/14 按页面拆成两段，但 AC-13 要求四个入口、AC-14 要求四个页面同时满足；AC-17/18 是单条
      P0 AC，不能由任一单模块独立判 pass。`.riccon/modules/M04.json` 的 acceptance 仍是 AC-01..18 全部通过；架构文档又用
      `M04a/M04b` 充当模块 ID，而项目要求实际 ID 为 `M` + 数字、别名才可用 M04a/M04b。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:610-621; docs/PRODUCT.md:357-362; .riccon/modules/M04.json; AGENTS.md:39-59"
  - id: F-010
    severity: P1
    status: open
    title: fingerprint=SHA-256(provider_id||key) 无 pepper，构成离线猜测校验 oracle，威胁模型未登记
    summary: >-
      ADR-0021 以“API key 高熵、SHA-256 不可反推”为由接受 fingerprint；但 API 只要求 16..512 字符，没有熵校验，
      provider_id 已知且无 salt/pepper。获得数据库备份但未获得主密钥的攻击者可离线枚举弱 key 并用 fingerprint 验证；
      这与 ABUSE-07 “密文受主密钥保护”的边界不一致，也没有对应 R-26 残余风险。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:165,185; docs/security/THREAT-MODEL.md:413-430; contracts/openapi.yaml:5555-5575"
  - id: F-011
    severity: P1
    status: open
    title: 网关开关 env 变量名未定义，quotaEnforcementEnabled 未进入配额准入算法
    summary: >-
      ADR-0021 §3.4/§10.5 规定 env > DB > 默认，并允许关闭路由/降级/配额执行，但没有给出任何环境变量名；
      AC-18 的运维关闭路径无法配置和测试。ADR-0022 §1.3 的准入算法完全没有检查 `quotaEnforcementEnabled`，
      关闭配额执行时仍会拒绝新 run，和 ADR-0021 §10.5 冲突。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:261-263,699-704; docs/adr/ADR-0022-m04-quota-and-cost.md:95-117; docs/PRODUCT.md:362"
  - id: F-012
    severity: P1
    status: open
    title: UI AC 证据缺口：AC-07/11/13/14/15 的界面/文案/入口没有绑定 E2E 或任务验收
    summary: >-
      AC-15 要求端点标签在页面可见，AC-11 要求成本明细可展开价格快照，AC-07 要求在保存前说明在途影响，
      AC-13 要求空态引导，AC-14 要求四页 axe；但 B9 验收只写“四入口/状态/375px/键盘/固定文案”，
      B11 只写 E2E-M04-01..08 和零 skip，没有逐条断言。AC-15 的测试 ID 是 SEC/IT API 测试，
      存在“API 证据代替 UI 证据”的假绿风险。
    evidence: "docs/PRODUCT.md:351,355,357-359; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:594-597"
  - id: F-013
    severity: P2
    status: open
    title: 受管端点元数据 denylist 不完整，且没有可注入 DNS 解析 seam
    summary: >-
      URL 策略只列 AWS/GCP 元数据地址；中国区常见的阿里云 `100.100.100.200`、Oracle `192.0.0.192`、
      ECS `169.254.170.2` 未覆盖，也未列 IPv4-mapped IPv6、末尾点 FQDN、十进制/十六进制 IP 的归一化。
      同时“启动时解析 host”没有可注入 resolver，解析类负向测试会依赖外部 DNS，难以确定性执行。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:102-108; docs/security/THREAT-MODEL.md:432-449"
  - id: F-014
    severity: P2
    status: open
    title: routingMode=BASELINE 未明确继续执行受管端点允许列表与平台 key 池校验
    summary: >-
      ADR-0021 §4.1 的 BASELINE 分支只写“使用 M02 语义选择最早 ACTIVE 模型”，没有重申端点允许列表、生产/演示标签
      与 `tenant_id='platform'` key 约束；而 §2.1 要求调用时双重校验。若 BASELINE 绕过，AC-15 的 kill switch 会成为
      绕过受管端点的路径。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:88,269-276; docs/PRODUCT.md:359"
  - id: F-015
    severity: P2
    status: open
    title: 成本“6 位小数 + 合计按未舍入值汇总”口径未澄清，存储 6 位后再求和可能不满足 AC 原文
    summary: >-
      ADR-0022 先把每次调用结果 HALF_UP 到 6 位写入 `cost_amount`，再对 6 位值求和；AC-11 写“计算保留 6 位小数…
      合计按未舍入值汇总后展示”。若“未舍入”指不按 2 位展示单位舍入，当前设计成立；若指不对每次调用先舍入，则设计不成立。
      需要 Product 明确并给 `IT-COST-101` 固定断言。
    evidence: "docs/PRODUCT.md:355; docs/adr/ADR-0022-m04-quota-and-cost.md:168-176"
  - id: F-016
    severity: P2
    status: open
    title: 测试 ID 目录未定义，SEC-M04-006 无场景，SEC-MODEL-003 被跨模块复用
    summary: >-
      ADR-0021 §11 只列测试层与 ID 范围，没有给出 `IT-MODEL-101..109`、`IT-QUOTA-101..105`、`IT-COST-101..104`、
      `SEC-M04-001..006`、`TEN-031..033`、`PERF-M04-001/002`、`OPS-M04-01` 的场景、断言和 owner。
      `SEC-M04-006` 只在引用中出现，`SEC-MODEL-003` 是 M02 既有 ID，无法判断 M04 新端点是否被覆盖。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:711-726; docs/PRODUCT.md:345-362; docs/security/THREAT-MODEL.md:621"
  - id: F-017
    severity: P2
    status: open
    title: 审计 details 白名单缺 emitted；Worker 自动失效使用 SERVICE actor 且无法显式切到 platform 租户
    summary: >-
      ADR-0021 §5.3 要求首字后失败审计带 `emitted=true`，但现有 Worker `AuditDetailsPolicy` 对
      `model.invocation.failed` 只允许 modelId/durationMs/errorCode/retryable；`emitted` 会被 fail-closed 拒绝。
      ADR 又要求 `model.credential.invalidated` 以 SYSTEM、tenant=platform 写入，而 Worker AuditService 固定
      SERVICE actor 且沿用调用方租户；两者都需要新增显式 API 和测试。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:347,500-501; apps/worker/src/main/java/com/riccon/worker/audit/domain/AuditDetailsPolicy.java:16-25; apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java:31-33,276-301"
  - id: F-018
    severity: P2
    status: open
    title: 现有 ModelRepository 仍先读租户级凭据，M04 未明确移除 BYO 回退
    summary: >-
      M04 规定模型凭据只能来自 `tenant_id='platform'`，但现有 `ModelRepository.findActiveCredential` 先查当前租户，
      再回退 platform。B4 若只改路由层而不移除该回退，租户级遗留凭据仍可能被使用，违背平台托管 key 池边界。
      ADR 需要明确删除回退并加“租户级凭据被忽略”的负向测试。
    evidence: "apps/worker/src/main/java/com/riccon/worker/model/repository/ModelRepository.java:58-65; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:380-384"
  - id: F-019
    severity: P2
    status: open
    title: 文档状态不一致：PRODUCT §9 OQ 仍 pending、ACCEPTANCE 仍把 M13 F-001/R-11 归 M04、ADR-0012 全局表清单未更新
    summary: >-
      DEC-0023 已批准 OQ-16..26 默认值，但 PRODUCT §9 仍标 `pending`；ACCEPTANCE 仍写 M13 F-001 由 M04/M10 承接、
      M03 F-008 R-11 由 M04/M09 设计迁移，与 PRODUCT §10.5/DEC-0023 的“M04 不承接”冲突；ADR-0012 §4 仍是旧全局表清单，
      而 ADR-0021 新增三张全局表并声称已扩展。另有 ARCHITECTURE 将 proposed ADR 写成“定稿”、ADR-0021/ARCHITECTURE
      把共享 key 健康残余风险写成 R-22（实际 R-21）。
    evidence: "docs/PRODUCT.md:278-290; docs/ACCEPTANCE.md:55-58; docs/adr/ADR-0012-m02-data-model-identifiers-and-tenant-keys.md:39-52; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:127,368; docs/ARCHITECTURE.md:115,322; docs/security/THREAT-MODEL.md:650-651"
  - id: F-020
    severity: P2
    status: open
    title: 契约/测试资产边界未收口：TooManyRequests oneOf、/costs/invocations 缺 404、员工 Model 允许价格字段、audit target UI 未归属
    summary: >-
      发送消息/创建报告的 429 仍是 ProblemDetail/QuotaExceededProblem oneOf，契约测试无法断定配额形状；
      `/api/v1/costs/invocations?userId=<他租户用户>` 没有 404 响应；员工 `Model` schema 仍允许价格字段；
      M04 新增 audit target 类型后，`apps/web/src/pages/admin/audit/filter-fields.tsx` 与
      `services/audit/types.ts` 的硬编码联合类型没有任务更新，界面会显示 undefined。
    evidence: "contracts/openapi.yaml:3616-3633,3243-3283,5258-5264; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:512-517; apps/web/src/pages/admin/audit/filter-fields.tsx:17-36; apps/web/src/services/audit/types.ts:18-36"
  - id: F-021
    severity: P2
    status: open
    title: 拆分估算仍假设 4 个并行 builder，M04a 在 2–3 上限下约 23h 且无缓冲
    summary: >-
      ADR-0021 §8.4 的 26–28h 与 M04a 20–22h 都按“并行 4 builder”计算；M04a 的关键路径
      B1a→B2→B3→B4→B9→B11→V1a 按人时相加约 23h，已触及 24h 上限，任何返工都会超时。
      拆分后需要按 2–3 builder 重算，或把 B10/V1a 移出 M04a 关键路径。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:600-621"
  - id: F-022
    severity: P2
    status: open
    title: AC-03/§10.4 的降级事件默认 7 天未落到 M10 审计查询；M10 默认仍是 24 小时
    summary: >-
      PRODUCT §10.4 规定审计查询中的降级事件默认展示最近 7 天；ADR-0021 只复用 M10 查询，没有改默认值，
      而 M10 契约的审计查询缺省是最近 24 小时。没有任务负责改 M10 审计页/查询默认，AC-03 的“按模型与时间筛选”
      只能用 API 手写参数验证，不能按产品口径在界面成立。
    evidence: "docs/PRODUCT.md:392; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:502; contracts/openapi.yaml:1651-1660"
  - id: F-023
    severity: P2
    status: open
    title: 应急 down SQL 无法在多 key 数据上恢复旧唯一键
    summary: >-
      ADR-0021 §10.3 的演练 SQL 先把 DISABLED/INVALID 改成 RETIRED，再恢复
      `UNIQUE (tenant_id, provider_id, credential_type)`；M04 后同一 provider 可有多把 key，即使全部 RETIRED，
      旧唯一键仍会因多行冲突而无法创建。SQL 不能在迁移测试中“验证可执行”，需要先删除/归档多余行或明确只允许单 key
      才能回滚。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:667-685"
  - id: F-024
    severity: P3
    status: open
    title: API key 请求 DTO 与错误路径缺少 toString/日志脱敏的显式要求
    summary: >-
      ADR 要求 key 明文不出现在日志/审计/错误，但没有要求 `ModelCredentialCreateRequest`/`RotateRequest` 的
      `toString` 掩码、禁止请求体日志、异常消息不回显 apiKey。现有仓库没有统一的请求体脱敏器；
      `SEC-M04-005` 应把“异常/校验失败/DTO toString”纳入扫描。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:421-426; contracts/openapi.yaml:5555-5585"
  - id: F-025
    severity: P3
    status: open
    title: 尝试明细/配额用量/降级审计的保留与容量未定义
    summary: >-
      `model_invocation_attempt` 每次尝试一行，`quota_usage` 保留历史窗口，`model.failover.triggered` 每次切换一条审计；
      ADR 只给 quota_usage 的 M09 清理，没有给 attempt 表保留期或降级审计的量级/告警阈值。T4 长时间故障下可能放大
      数据库与审计分区压力。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:211-231,707-709; docs/adr/ADR-0022-m04-quota-and-cost.md:91,210-212"
  - id: F-026
    severity: P2
    status: open
    title: 高风险任务缺独立任务级 Verifier closeout；V2 的 e2e/perf write_scope 与 B8 重叠
    summary: >-
      B1a（迁移）、B1b（加密）、B2（SSRF/契约）、B4（AI 路由/并发）、B5（凭据）、B6（配额）都是高风险任务，
      但 ADR-0021 §8 只有末端 V1/V2，没有为这些任务安排 done 前的独立 Verifier 任务评审。另 V2 的 write_scope
      写 `e2e/perf/**`（又说“只运行不修改”），与 B8 的 `e2e/perf/**` 重叠；B11 也没有显式要求 `@a11y` 标签。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:545-558,593-598"
notes: >-
  评审对象是工作区未提交的 ADR-0021/0022、ARCHITECTURE、THREAT-MODEL、contracts/openapi.yaml 与
  contracts/README；HEAD d800410 尚不包含这些文件。设计阶段未运行 build/test/coverage/E2E/perf，也未修改任何生产文件。
created_at: "2026-11-20T08:49:35+08:00"
---

# REV-M04-ARCH-001：M04「模型网关纵深」独立架构评审

## 1. 结论

**fail。** M04 的范围、状态机、数据模型和威胁模型主体是完整的，但当前设计还不能进入实现：OpenAPI 有三处硬性契约缺陷，审计 details 的类型校验会让路由/配额保存必然失败，AC-11/AC-12 的平台成本口径互相冲突，AC-17 的 3s 首字目标与超时/重试/并发配置不一致，配额准入的 fail-closed 审计和并发 SQL 存在不可实现点，拆分后的 AC/模块/gate 映射也不可执行。

这不是对产品范围的否定；范围（DEC-0023=A）和 P0/P1 划分是清楚的。问题集中在“可测试、可实现、可 gate”的工程闭环。

## 2. 评审范围与验证方法

已读：

- `docs/PRODUCT.md` §9 OQ-16..26、§10 全文；
- `.riccon/decisions/DEC-0023.md`；
- `docs/adr/ADR-0021-*.md`、`ADR-0022-*.md`、`docs/adr/README.md`；
- `docs/ARCHITECTURE.md` M04 段、评审状态表、风险表；
- `docs/security/THREAT-MODEL.md` TB-04、TM-39..53、ABUSE-07..11、R-21..25、B-18/B-19、§10；
- `contracts/openapi.yaml` M04 增量、三个模型端点、quota/cost 端点、`x-riccon-deferred`、`contracts/README.md`；
- `docs/ACCEPTANCE.md` 已接受残余；
- 现有实现：`apps/worker/.../model/**`、`crypto/**`、`apps/api/.../chat/service/ChatSubmissionService.java`、`research/service/ResearchTaskService.java`、`V5__model_gateway_and_mcp.sql`、`e2e/**`、`apps/web/src/pages/admin/**`、`RoleDefinitions.java`、`scripts/real-stack/run.mjs`。

实际运行的验证：

| 命令                                                                                           | 退出码 | 结果                                                                               |
| ---------------------------------------------------------------------------------------------- | ------ | ---------------------------------------------------------------------------------- |
| `node $HOME/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict --project .` | 0      | 合同 YAML 结构化校验通过（0 errors / 0 warnings）                                  |
| Ruby YAML 解析 `contracts/openapi.yaml`                                                        | 0      | OpenAPI 3.1，version `1.3.0-m04`，87 paths / 151 schemas                           |
| 自写 path 参数模板检查（Ruby）                                                                 | 0      | 发现 1 处 path 参数不在模板中：`PUT /api/v1/admin/model-providers` 的 `providerId` |
| 自写 M04 写端点 CSRF 检查（Ruby）                                                              | 0      | 10/10 个 M04 POST/PUT/PATCH 端点缺 `CsrfToken`                                     |
| 自写 `$ref` 解析检查（Ruby）                                                                   | 0      | 942 个 `$ref` 全部可解析，0 坏引用                                                 |
| `riccon gate check --module M04`                                                               | 1      | 预期失败：M04 仍 planned、无任务、无模块 review；不是本设计评审的通过条件          |

未运行：`build`、`lint`、`typecheck`、`test`、`coverage`、`e2e`、`perf`、`security`、`a11y`。设计阶段没有代码变更，运行全量没有证据意义；且本评审 verdict 为 fail，模块 gate 不应通过。

## 3. AC 对账表（M04-AC-01..18）

说明：设计阶段没有 EV；下表“证据路径/缺口”列的是计划路径与当前缺口，不是已通过证据。PRODUCT 要求最终评审包登记“AC → 测试 ID → 报告路径”；当前还没有任何 AC 有真实 EV。

| AC        | 设计落点                                       | 测试 ID                                                                                  | 证据路径 / 缺口                                                                                                                            |
| --------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| M04-AC-01 | ADR-0021 §3.1、§4.1、§4.3、§7.1；路由页 B9     | `E2E-M04-01`、`IT-MODEL-101`                                                             | 计划：Playwright 报告 + Surefire；缺口：B9 的 API client 无 owner（F-007）；E2E 未定义逐条断言                                             |
| M04-AC-02 | ADR-0021 §4.3、§4.4、§7.4；API/Worker 审计策略 | `TEN-031`、`SEC-MODEL-003`、`IT-MODEL-102`                                               | 缺口：`SEC-MODEL-003` 是 M02 旧 ID（F-016）；审计 details 数组/null 校验会失败（F-002）；测试目录未定义                                    |
| M04-AC-03 | ADR-0021 §5.1–5.3、§7.4；M10 审计查询          | `E2E-M04-02`、`IT-MODEL-103`                                                             | 缺口：§10.4 默认 7 天未落到 M10 查询（F-022）；内层重试会让 attempt 审计失真（F-005）                                                      |
| M04-AC-04 | ADR-0021 §5.3、§6.4；现有 ChatStreamService    | `E2E-M04-03`、`IT-MODEL-104`                                                             | 缺口：`emitted` 未进审计白名单（F-017）；UI 重试入口/固定文案未进 B9/B11 验收（F-012）                                                     |
| M04-AC-05 | ADR-0021 §4.1 第 5 步、§5.3                    | `E2E-M04-03`、`SEC-MODEL-004`                                                            | 计划：E2E + 安全日志扫描；缺口：现有 `ChatStreamService` 将模型解析失败映射 500，M04 需改 503 的验收未写入 B4 测试                         |
| M04-AC-06 | ADR-0021 §5.1、§6.3、§7.4                      | `IT-MODEL-105`、`PERF-M04-001`                                                           | 缺口：自动失效的 SYSTEM/platform 审计 seam 未定义（F-017）；“至少 2 key”未成为系统不变量，只是测试前置                                     |
| M04-AC-07 | ADR-0021 §6.3–6.4                              | `E2E-M04-04`、`SEC-MODEL-005`、`IT-MODEL-106`                                            | 缺口：保存前“在途影响”文案未进 B9 验收（F-012）；轮换在途测试需 slow fake stream，B8 场景未定义                                            |
| M04-AC-08 | ADR-0022 §1.1–1.5                              | `E2E-M04-05`、`IT-QUOTA-101`                                                             | 缺口：拒绝审计事务不可实现（F-008）；quotaEnforcement 开关未进算法（F-011）                                                                |
| M04-AC-09 | ADR-0022 §1.3                                  | `PERF-M04-002`、`IT-QUOTA-102`                                                           | 缺口：`INSERT IGNORE` 并发死锁/吞错（F-008）；Worker 默认 16 流（F-006）；未要求真实 HTTP/独立事务                                         |
| M04-AC-10 | ADR-0022 §1.5、§2.4                            | `E2E-M04-05`、`TEN-032`、`IT-QUOTA-103`                                                  | 缺口：新权限未进 `RoleDefinitions`，新租户 tenant admin 无 quota.read（F-007）；quota_usage 无 user FK                                     |
| M04-AC-11 | ADR-0022 §2.1–2.4                              | `E2E-M04-06`、`IT-COST-101`                                                              | 缺口：平台“明细可展开”与 AC-12 冲突（F-003）；priceCurrency 必填冲突（F-004）；6 位/未舍入口径需 Product 明确（F-015）                     |
| M04-AC-12 | ADR-0022 §2.4                                  | `TEN-033`、`E2E-M04-06`                                                                  | 缺口：CostBucket 允许 userId/userDisplayName（F-003）；/costs/invocations 缺 404（F-020）                                                  |
| M04-AC-13 | B9 四页；ADR-0021 §7.5                         | `E2E-M04-07`                                                                             | 缺口：四页入口/加载/空/错误/403/成功未逐页绑定断言；空态引导未进验收（F-012）；client.ts 无 owner（F-007）                                 |
| M04-AC-14 | B9 四页 + `commands.a11y`                      | `E2E-M04-07`、`@a11y`                                                                    | 缺口：B11 验收未要求 `@a11y` 标签；拆分按两页会破坏“四页同时 pass”的 AC（F-009/F-012）                                                     |
| M04-AC-15 | ADR-0021 §2.1–2.4、§7.1                        | `SEC-MODEL-002`、`SEC-MODEL-006`、`IT-MODEL-107`                                         | 缺口：`SEC-M04-006` 无定义；provider PUT 路径无效（F-001）；CSRF 缺失（F-001）；标签 UI 无 E2E（F-012）；元数据列表不完整（F-013）         |
| M04-AC-16 | ADR-0021 §7.6；现有 M02/M03/M10 套件           | `E2E-M04-08`、`IT-MODEL-108`、`TEN-001..033`、`DEPT-001..010`、`IT-OIDC-*`、`IT-AUDIT-*` | 计划：契约测试 + 回归；缺口：contracts 文件无 owner（F-007）；`/api/v1/models` 无既有 Web 消费者，回归面小                                 |
| M04-AC-17 | ADR-0021 §5、§10.6；ADR-0022 §1.3/§2.5         | `PERF-M04-001`、`PERF-M04-002`                                                           | 缺口：3s vs 5s/20s（F-005）；16 流上限（F-006）；无 metrics 端点（F-006）；无 perf 环境 owner（F-007）                                     |
| M04-AC-18 | ADR-0021 §3.4、§10.3–10.5；B10                 | `IT-MODEL-109`、`OPS-M04-01`                                                             | 缺口：env 变量名未定义（F-011）；quotaEnforcement 未进准入（F-011）；BASELINE 未重申 allowlist（F-014）；down SQL 多 key 不可执行（F-023） |

## 4. Findings

### F-001（P1，open）OpenAPI M04 契约结构缺陷

**问题（三处，均已用脚本复现）**

1. **provider PUT 路径参数无效**：`contracts/openapi.yaml:2686` 的 path 是 `/api/v1/admin/model-providers`，但 `put`（`:2709`）在 `:2720` 声明了 `providerId` path 参数。OpenAPI 要求 path 参数必须出现在路径模板中；实际 ADR-0021 §7.1（`:443`）和 `contracts/README` 写的是 `/api/v1/admin/model-providers/{providerId}`。
2. **10/10 M04 写端点缺 CSRF**：契约头 `contracts/openapi.yaml:14-16` 规定所有 POST/PUT/PATCH/DELETE 必须带 `X-CSRF-TOKEN`，但 M04 的 10 个写端点（`:2709`、`:2808`、`:2856`、`:2915`、`:2950`、`:2985`、`:3010`、`:3051`、`:3147`、`:3181`）都没有 `$ref: "#/components/parameters/CsrfToken"`。生成客户端和契约测试会漏掉 CSRF；实现若按契约不发送，接口会 403。
3. **AuditTargetType 缺新值**：契约枚举 `contracts/openapi.yaml:3947-3967` 只有 16 个旧值，没有 `MODEL_ROUTING`、`MODEL_CREDENTIAL`、`QUOTA`；而 ADR-0021 §7.4（`:486-498`）和 ADR-0022 §3.4（`:255-258`）明确使用这些目标类型。平台管理员按 `targetType` 过滤 M04 审计时会拿不到枚举值，M10 契约测试也会失败。

**影响**：AC-15 的“保存端点”接口路径不可调用；AC-02/10 的审计查询目标类型不可表达；CSRF 契约与安全规则不一致。这是“契约已声明但实现/测试无法按它工作”的硬缺陷。

**建议修复**：把 provider PUT 改为 `/api/v1/admin/model-providers/{providerId}`（或去掉 path 参数、用 body 中的 id，但需与 ADR 统一）；为 10 个写端点补 `CsrfToken` 参数；把三个 target type 加进 OpenAPI、`AuditTargetType.java`、Web 的 `AuditTargetType` 联合类型与 `filter-fields.tsx` 选项。

**验证方式**：重跑 path 参数检查（0 违规）、CSRF 检查（10/10 通过）、`IT-MODEL-108` 契约测试、M10 审计查询按 `targetType=MODEL_ROUTING|MODEL_CREDENTIAL|QUOTA` 返回 200。

### F-002（P1，open）审计 details 校验拒绝数组/null，路由/配额写必然失败

**问题**

- ADR-0021 §4.4（`:301`）和 §7.4（`:488`）要求 `model.routing.updated.details.modelIds` 是有序 UUID 数组。
- API `AuditDetailsPolicy.validateValue`（`apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:180-194`）只接受 String/Number/Boolean，遇到 `List` 直接抛 `IllegalArgumentException`；Worker 策略同样只接受基本类型。
- ADR-0022 §1.5（`:139`）和 §3.4（`:257`）要求 `quota.limit.changed.details.previousLimit`；首次设置配额时没有旧行，若按 ADR 写 null，`validateValue` 会明确拒绝；若省略该键，又丢失“前后值”语义。

**影响**：审计 fail-closed（ADR-0007/ADR-0021 §1 不变量 6）会把这两种正常业务写入变成 400/500。AC-02“保存路由并写审计”和 AC-10“配额变更审计”无法通过；如果 Builder 为了通过而把 `modelIds` 拼成字符串或把 previousLimit 写成 0，又会制造错误审计语义。

**建议修复**：扩展 `AuditDetailsPolicy` 支持受限数组（最多 20 个 UUID 字符串、总长度上限）和动作级 nullable 键（`previousLimit` 允许 null，落库为 JSON null）；或者把 `modelIds` 改为有明确编码的字符串并在 ADR/契约中固定。两种方案都要在 ADR §11 增加 `IT-MODEL-102`/`IT-QUOTA-101` 的 fail-closed 断言。

**验证方式**：构造 20 个模型的链保存，断言 200 且审计 details 是数组；构造首次配额设置，断言 200 且 `previousLimit=null`；注入审计失败，断言业务回滚。

### F-003（P1，open）AC-11 平台成本明细与 AC-12/OQ-19 冲突，契约未强制平台无用户字段

**问题**

- PRODUCT AC-11（`docs/PRODUCT.md:355`）的 Then 写“成本明细可展开查看该次调用的输入/输出价格快照”，未限定角色；AC-12（`:356`）又要求平台管理员“只看到租户聚合，不展示租户内用户级明细”。
- ADR-0022 §2.4（`:195-199`）明确平台管理员**不提供**按用户的调用明细；租户管理员才有 `/costs/invocations`。这意味着平台管理员无法满足 AC-11 字面上的“明细可展开”。
- 契约 `CostBucket`（`contracts/openapi.yaml:5851-5872`）仍包含 `userId`/`userDisplayName`，`CostReport.groupBy` 枚举也包含 `USER`（`:5891-5925`）。平台端点使用同一个 `CostReport` schema，即使实现错误返回用户字段，契约测试也不会失败。

**影响**：E2E-M04-06 无法写出同时满足 AC-11 和 AC-12 的断言；实现可能为了 AC-11 给平台加用户明细，直接违反 OQ-19/ABUSE-11；或者平台侧 AC-11 只能判 partial。

**建议修复**：由 Product 明确 AC-11 的“明细可展开”只适用于租户管理员，平台管理员只到租户/模型/日聚合；ADR/契约同步。平台成本使用独立 `PlatformCostReport`/`PlatformCostBucket`（不含 userId/userDisplayName，groupBy 不含 USER），或在 schema 中把这两个字段 `const: null`，让泄露可被契约测试捕获。

**验证方式**：`TEN-033` 断言平台 `/admin/costs` 响应无用户字段；租户 `/costs/invocations` 展开价格快照；E2E-M04-06 按角色分别断言。

### F-004（P1，open）CostInvocation.priceCurrency 必填与非空冲突

**问题**：契约 `CostInvocation`（`contracts/openapi.yaml:5932-5968`）把 `priceCurrency` 放在 `required`，类型为非空 `string`；ADR-0022 §2.1（`:161`）和 §2.3（`:180-188`）规定 `UNPRICED`/`USAGE_UNKNOWN`/全链失败调用的价格、金额为空，ADR-0021 §3.3（`:197`）的 `price_currency` 列也可空。`/api/v1/costs/invocations` 返回一个失败调用时，响应无法同时通过 schema 和真实数据。

**影响**：`IT-COST-102`（未定价/缺 usage）会失败；实现可能被迫填一个假币种，破坏“未定价/用量未知”的财务口径。

**建议修复**：`priceCurrency` 改为 `type: [string, "null"]` 或从 required 移除；在 `CostInvocation` 中明确 `pricingStatus=UNPRICED|USAGE_UNKNOWN` 时价格/币种/金额可为 null。加契约测试覆盖三类状态。

**验证方式**：对三类调用分别请求 `/costs/invocations`，断言 schema 通过、字段与 `pricingStatus` 一致。

### F-005（P1，open）AC-17 3s 首字目标与超时/内层重试不一致

**问题**

- AC-17（`docs/PRODUCT.md:361`）要求在 50 并发注入首字前超时、5xx、429、key 失效时首字 p95 ≤3s。
- ADR-0021 §5.2（`:335-339`）设置 `connect-timeout` 5s、每模型请求超时可能 30–60s、首字前总预算 20s。主模型挂起时，仅连接超时就已超过 3s；per-attempt timeout 取 `min(model.request-timeout, remaining budget)` 也不会低于 3s。
- 现有 `OpenAiCompatibleChatModel`（`:138-187`）内部按 `maxAttempts=3` 对 429/5xx 做重试；`ModelGatewayProperties` 默认 3（`:24,47-58`）。M04 的外层路由若把一次客户端调用当一个 attempt，实际 HTTP 调用会成倍增加，`model_invocation_attempt`、`model.failover.triggered`、尝试上限 5 和 AC-03 的“每次降级写审计”都会失真。

**影响**：AC-17 的性能断言在第一个 timeout 场景就无法满足；attempt/审计计数不可复核；如果为了过测试把超时改短，又可能伤害正常模型的首字。

**建议修复**：明确“首字前每 attempt 的 first-token timeout”≤剩余预算（例如 1.5s，保证一次 5xx 后仍能在 3s 内），连接超时也必须小于该预算；把内层 `maxAttempts` 固定为 1（或删除内层重试），由 `FailoverChatModel` 统一拥有重试/退避/审计；在 `IT-MODEL-103` 中断言精确 HTTP 调用次数和 attempt 行数。

**验证方式**：用 fake provider 在 50 并发下分别注入 connect-timeout、延迟首字、5xx、429、401；k6 报告首字 p95；数据库断言 attempt 数 ≤5 且每次切换一条审计。

### F-006（P1，open）AC-17 的可观察性与 50 并发不可交付

**问题**

- AC-17 要求运维可观察降级次数、按 provider/model 的错误率、配额拒绝数与当前并发 run。ADR-0021 §10.6（`:707-709`）列了指标名，但 API/Worker 的 actuator 只暴露 `health,info`（`apps/api/src/main/resources/application.yml:51-55`、`apps/worker/.../application.yml:26-30`），仓库没有 Micrometer/Prometheus 依赖，任务图也没有任何任务实现指标注册或暴露。
- AC-17 要求 50 并发 Agent run，但 Worker 默认 `RICCON_CHAT_MAX_STREAMS=16`（`apps/worker/src/main/resources/application.yml:93`、`ChatStreamProperties.java:20`）。第 17 个请求会得到 `RICCON-QUEUE-429`，不会进入模型/降级测试；没有任务负责性能环境的 50 并发配置，`scripts/real-stack/run.mjs` 也未设该变量。

**影响**：PERF-M04-001/002 要么失败，要么被人为降到 16 并发而“假绿”；AC-17 的运维可观察部分没有任何可执行证据。

**建议修复**：明确引入 Micrometer + Prometheus registry（或内部 ops 指标端点），只在内网/ops 暴露，加入 B4/B6/B7/B10 的 write_scope 与验收；在 perf 环境显式设置 `RICCON_CHAT_MAX_STREAMS>=50`（run.mjs/deploy），并在 V2 证据中记录环境变量；给 `PERF-M04-001` 加“指标可抓取且包含 failover/quota/inflight”断言。

**验证方式**：`curl /actuator/prometheus`（或 ops 端点）包含指标；k6 50 并发无 QUEUE-429；V2 报告记录 scrape 样本。

### F-007（P1，open）任务图缺少关键文件/环境所有权

**问题**（均可在任务表对照）

1. **`scripts/real-stack/run.mjs` 无 owner**：B8 scope（ADR-0021 `:593`）只有 fake-model/seed/checks/e2e/perf，B10（`:595`）只有 deploy/RUNBOOK/scripts/ops。M04 API 新增主密钥依赖，但 run.mjs 的 API env（`scripts/real-stack/run.mjs:214-238`）没有 `RICCON_MASTER_KEY`；也没有 `RICCON_MODEL_ENDPOINTS`、`RICCON_MODEL_DEPLOYMENT_MODE=DEMO`、`RICCON_BILLING_CURRENCY`；Worker env（`:241-252`）也没有 M04 端点配置。按 ADR 默认 `PRODUCTION` + 无端点，API/Worker 会启动失败，所有真实栈 E2E/perf 都会挂。
2. **`RoleDefinitions.java` 无 owner**：B1a（`:585`）只写 `PermissionCodes.java` 与迁移，不写 `apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java`。该文件（`:45-78`）没有 `quota.read`/`cost.read`/`platform.model.manage`/`platform.quota.manage`/`platform.cost.read`。V11 可以回填既有租户角色，但 M04 之后新开通的租户走 `tenantSystemRoles()`，tenant admin 不会获得 quota/cost 权限，AC-10/12 对新租户失败。
3. **`apps/web/src/services/api/client.ts` 无 owner**：`RicconApi` 定义在 `client.ts:74`，B9 scope（`:594`）只有 pages 与 services/model|quota|cost，没有 `client.ts`/`types.ts`。B9 无法按现有模式给 API 增加方法，页面无法编译/联调。
4. **测试 profile 无 owner**：`apps/api/src/test/resources/application-test.yml` 没有 M04 的 `RICCON_MODEL_DEPLOYMENT_MODE=DEMO`/端点配置；默认 PRODUCTION + 无端点会让 Spring 上下文启动失败。
5. **契约文件无 owner**：B2 负责“契约测试”（`:587`），但 `contracts/openapi.yaml` 不在任何 builder 的 write_scope。评审修复或实现期契约调整会无人可改。

**影响**：这不是“写范围重叠”问题，而是“写范围缺文件”，会让对应任务无法完成或在真实栈/新租户/前端编译阶段失败。

**建议修复**：把 `scripts/real-stack/run.mjs` 加入 B8（或 B10）write_scope 与验收；把 `RoleDefinitions.java` 加入 B1a；把 `apps/web/src/services/api/client.ts`、`types.ts` 加入 B9；把 `apps/api/src/test/resources/application-test.yml` 加入 B1b/B2；明确 `contracts/openapi.yaml` 由 B2 独占。任务卡里写清“B2 是契约唯一 writer”。

**验证方式**：检查 task `write_scope` 覆盖所有被修改文件；M04 真实栈启动脚本在无额外手工 env 下能启动 API/Worker；新租户 tenant admin 登录后 `/quotas`、`/costs` 返回 200。

### F-008（P1，open）配额准入的审计 fail-closed 与并发 SQL 不可实现

**问题**

- ADR-0022 §1.3（`:111-115`）要求任一步失败整体回滚；§1.5（`:139`）又要求拒绝时写 `quota.exceeded` 且审计失败返回 500。若 `quota.exceeded` 写在与准入相同的 `@Transactional` 内，拒绝异常会把它一起回滚；若写在事务外，需要显式的 `REQUIRES_NEW`/独立事务 seam，ADR 没有定义。现有 `AuditService.append` 是默认 `REQUIRED`（加入调用方事务），Builder 很可能直接写进被回滚的事务。
- 准入 SQL（`:100-107`）先 `INSERT IGNORE` 再 `UPDATE ... WHERE used_count < limit`。50 并发对同一 PK 的 `INSERT IGNORE` 会取共享锁，再升级为 UPDATE 排他锁，存在死锁窗口；`INSERT IGNORE` 还会把非重复键错误（数据截断、FK 失败）降级为 warning，导致没有行却继续执行。ADR 的“固定顺序避免死锁”只覆盖四个 scope 的顺序，没有覆盖同一行 INSERT/UPDATE 的死锁。

**影响**：AC-09 的 50 并发零超卖可能表现为部分 500 而不是配额 429；AC-08“拒绝写审计”无法验证；审计失败时可能既不拒绝也不留痕。

**建议修复**：把准入做成“先尝试条件 UPDATE（行不存在则用 `INSERT ... ON DUPLICATE KEY UPDATE` 或预创建窗口行）→ 失败后由外层捕获并开启新事务写 `quota.exceeded` → 审计失败返回 500”；显式处理死锁（重试一次但要记录指标，不能掩盖为成功）。`INSERT` 不用 IGNORE，避免吞错。`IT-QUOTA-102` 必须用真实 MySQL、独立事务/真实 HTTP，断言 1 成功、49 个 429、无 500、`used_count=1`。

**验证方式**：50 并发集成测试 + `PERF-M04-002`；注入审计表故障，断言拒绝返回 500 且无 run；检查 `quota.exceeded` 行存在。

### F-009（P1，open）拆分后的 AC/gate 判定不可执行

**问题**

- ADR-0021 §8.4（`:612-621`）把 AC-13/14 按页面拆成 M04a（路由/key 页）和 M04b（配额/成本页），但 AC-13 要求“平台管理员看到四个入口”、AC-14 要求“路由、key、配额、成本四个页面”同时满足 375px/键盘/axe。单模块无法只凭两页判 AC-13/14 pass。
- AC-17/18 是单条 P0 AC；ADR 自己承认“M04a gate 只验收子集，M04b gate 用两模块证据合并判定”。但 `.riccon/modules/M04.json` 的 acceptance 仍是“AC-01..18 全部通过”，模块 gate 不知道“合并判定”，也不能引用另一个模块的 evidence 来满足本模块 acceptance。
- `M04a`/`M04b` 被当成模块 ID；项目实际模块 ID 必须是 `M` + 数字，别名才可用 M04a/M04b（先例：M03b 的实际 ID 是 M10，`split_from: M03`）。

**影响**：拆分后无论先做哪个模块，gate 都会在“AC 未全部通过”或“引用了不存在/不属于本模块的证据”之间二选一；如果强行标 pass，就是假绿。

**建议修复**：二选一，并让 Lead/用户明确：

- **方案 A（推荐，若拆分）**：M04（首段）的模块 acceptance 只写 AC-01..07、15、16 + 任务级 UI/性能子集；新建实际数字模块（如 M17，`split_from: M04`、aliases `["M04a","M04b"]`）承接 AC-08..14、17、18 全部；最终模块 gate 绑定两段证据。AC-13/14/17/18 **整条**归最终模块，M04 不声称 pass。PRODUCT §10.3 原文不改，只在评审包登记“AC → 子集 → 证据”。
- **方案 B（若坚持单模块）**：保留一个 M04，两个执行阶段，只在最后跑一次 gate；24h 预算必须提高或经用户批准削减范围。不能创建 `M04b` 假模块 ID。

同时：契约按 `1.3.0-m04a`/`1.4.0-m04b` 两段发布，M04a 不暴露 quota/cost 端点（或标 deferred），避免“契约已声明但实现不存在”。

**验证方式**：Lead 创建任务前，用 `riccon module`/`task` 检查模块 ID、acceptance、`split_from`/`aliases`；模拟 gate 检查 proof：M04a review 的 `dod.acceptance` 只绑定 scoped AC，最终 M17 review 绑定 AC-17/18 的合并 EV。

### F-010（P1，open）fingerprint 无 pepper 的离线猜测风险

**问题**：ADR-0021 §3.2（`:165`）定义 `fingerprint=SHA-256(provider_id||0x00||明文 key)`，§3.2 说明（`:185`）以“API key 高熵、SHA-256 不可反推”接受。但契约只要求 `apiKey` 16..512 字符（`contracts/openapi.yaml:5555-5575`），不校验熵；`provider_id` 在 DB 中已知，fingerprint 无 salt/pepper。获得数据库备份但未获得主密钥的攻击者可以离线枚举弱 key 并验证 fingerprint，绕开 ABUSE-07 的“密文受主密钥保护”边界；威胁模型没有对应残余项。

**影响**：T4 密钥泄漏边界被削弱；如果客户使用短/可猜的网关 token，风险实际存在。

**建议修复**：用 `HMAC-SHA256(deployment_pepper, provider_id||key)` 替代裸 SHA-256（pepper 从 `RICCON_MASTER_KEY` 派生或独立 Secret），仍可做唯一去重；或明确接受残余，新增 R-26，并要求 API key 最小长度/熵校验（拒绝明显弱 key）。`SEC-M04-005` 增加“DB dump 无 fingerprint 可离线验证”的说明/测试。

**验证方式**：设计评审确认 pepper 来源与轮换；若保留裸 SHA-256，威胁模型 §9 必须有 R-26 与用户/安全签字。

### F-011（P1，open）开关 env 名称缺失，quotaEnforcement 未进准入

**问题**：ADR-0021 §3.4（`:261-263`）规定 env > DB > 默认，§10.5（`:699-704`）允许关闭路由/降级/配额执行，但没有定义任何环境变量名；AC-18 要求运维关闭这些开关，B10 无法写 RUNBOOK 和演练。ADR-0022 §1.3 的准入算法（`:95-117`）没有检查 `quotaEnforcementEnabled`；按 ADR-0021 §10.5，关闭该开关应“只记录用量、不阻断”，但算法仍会抛 QuotaExceeded。

**影响**：AC-18 的配额开关不可实现/不可测；运维无法在故障时快速止血。

**建议修复**：定义 `RICCON_MODEL_ROUTING_MODE`、`RICCON_MODEL_FAILOVER_ENABLED`、`RICCON_MODEL_QUOTA_ENFORCEMENT_ENABLED`（或等价），写清优先级、启动审计 `source=DEPLOYMENT`、`PUT switches` 409 的字段；ADR-0022 准入算法加 `if !quotaEnforcementEnabled: record usage only, no reject`，并定义此时 `quota.exceeded` 是“观测到超限”还是“拒绝”；`IT-MODEL-109` 覆盖三种关闭路径。

**验证方式**：设置 env 启动，`GET switches` 返回 `source=ENVIRONMENT`；关闭 quota 后 50 并发全部准入但用量仍增长；审计可查。

### F-012（P1，open）UI AC 证据缺口

**问题**：AC-15 明确要求端点标签在“端点目录或路由选择器”可见；AC-11 要求成本明细可展开价格快照；AC-07 要求保存前说明在途影响；AC-13 要求四页空态引导；AC-14 要求四页 axe。B9 验收（ADR-0021 `:594`）只有“四入口；平台/租户可见性；加载/空/错误/403/成功；375px/键盘；key 只显示掩码；固定文案”，B11（`:596`）只有 `E2E-M04-01..08；... 零 skip`，没有逐条断言；AC-15 的测试 ID 是 `SEC-MODEL-002/006`、`IT-MODEL-107`（API/安全），不是 UI。

**影响**：这些 AC 很容易被 API 证据或“页面能打开”判 pass，违反 PRODUCT §10.3 补充约定“UI AC 不能只用 API 证据判 pass”。

**建议修复**：在 B9 验收和 B11 规格中逐条绑定：

- AC-15：路由选择器显示 `PRODUCTION`/`DEMO_ONLY` 标签，生产模式选择 demo 端点时保存被拒并显示中文原因；
- AC-11：租户成本页展开调用明细，显示输入/输出价格快照、未定价/用量未知；
- AC-07：停用/轮换确认框显示固定影响说明；
- AC-13：四页各自的空态引导文案（先配置模型目录/路由），403 态；
- AC-14：四个页面 `@a11y` 标签、375px、键盘路径。

**验证方式**：`E2E-M04-07` 按角色/页面拆成明确断言；`commands.a11y` 覆盖四个 URL；评审包附截图/报告路径。

### F-013（P2，open）受管端点元数据 denylist 不完整且无可注入 DNS seam

**问题**：ADR-0021 §2.2（`:102`）只列 `169.254.0.0/16`、`fd00:ec2::254`、`metadata.google.internal`；ABUSE-08 的“元数据地址”没有覆盖阿里云 `100.100.100.200`、Oracle `192.0.0.192`、ECS `169.254.170.2`、IPv4-mapped IPv6、末尾点 FQDN、十进制/十六进制 IP。策略也没有可注入的 resolver，启动解析/负向测试会依赖真实 DNS，无法确定性执行。

**影响**：在客户私有化环境（尤其中国区云）存在元数据探测绕过；测试无法稳定覆盖。

**建议修复**：补齐元数据/链路本地 denylist，统一 `InetAddress` 归一化后判断；为 `ManagedEndpointPolicy` 注入 `HostResolver`（生产用 `InetAddress`，测试用固定 map），并在 `SEC-M04-001..004` 加编码 IP、IPv6-mapped、末尾点用例。

**验证方式**：单元矩阵覆盖上述地址全部拒绝；测试不依赖外部 DNS。

### F-014（P2，open）BASELINE 未明确继续执行受管端点/平台 key 校验

**问题**：ADR-0021 §4.1（`:269-276`）的 BASELINE 分支只说“M02 语义选择最早 ACTIVE 模型”，没有写端点允许列表、生产/演示标签与 `tenant_id='platform'` key 约束；而 §2.1（`:88`）要求调用时双重校验。若 BASELINE 直接走 M02 的 `findActive`，可能绕过允许列表。

**影响**：AC-15 的 kill switch 成为绕过受管端点的路径；运维在故障时关闭路由反而放宽安全边界。

**建议修复**：在 BASELINE 分支显式复用 `ManagedEndpointPolicy` 与平台 key 池过滤；`IT-MODEL-109` 增加“DB 端点不在允许列表 + BASELINE → 503，无出网”用例。

**验证方式**：构造未允许端点，切 BASELINE，断言无 HTTP 请求、503。

### F-015（P2，open）成本 6 位小数/未舍入合计口径需 Product 明确

**问题**：AC-11（`docs/PRODUCT.md:355`）说“计算保留 6 位小数…合计按未舍入值汇总后展示”；ADR-0022 §2.2（`:173-175`）先把每次调用 HALF_UP 到 6 位写入 `cost_amount`，再对 6 位值求和。若“未舍入”指不按 2 位展示单位舍入，设计成立；若指不对每次调用先舍入，则存储 6 位后再求和会产生累计误差。

**影响**：`IT-COST-101` 的断言可能有歧义，评审时无法判断“成本重算误差 0”是否成立。

**建议修复**：Product/ADR 明确“6 位小数是计算精度；合计对 6 位小数值求和，仅在展示时按币种最小单位舍入”；或在 `model_invocation` 存更高精度（如 12 位）用于合计。加固定数值样例（含 0.0000004 等边界）。

**验证方式**：`IT-COST-101` 用固定 token/价格断言每笔 6 位、合计与展示舍入；跨多笔验证累计。

### F-016（P2，open）测试 ID 目录未定义，SEC-M04-006 无场景

**问题**：ADR-0021 §11（`:711-726`）只列测试层与 ID 范围；PRODUCT 的测试线索给 ID 但没有 Given/When/Then/断言。`SEC-M04-006` 只在引用中出现（ADR `:557,597,720`、THREAT `:621`），没有定义；`SEC-MODEL-003` 是 M02 既有 ID，被 ADR-0021 `:630`/ADR-0022 `:268` 复用于 M04 新端点。

**影响**：Builder 和 Verifier 可能对同一 ID 写不同测试；`SEC-M04-006` 可能被任意测试“占位”通过；AC → 测试 → 证据无法独立复核。

**建议修复**：在 ADR-0021/0022 §11 增加测试目录：ID、层（unit/integration/contract/E2E/perf/security/ops）、场景、关键断言、owner、证据路径。M04 新端点使用 `SEC-M04-*`，不要复用 `SEC-MODEL-003`；若复用，明确扩展范围和旧测试不覆盖的部分。

**验证方式**：评审包按目录逐条绑定；`grep` 确认每个 ID 只有一个定义。

### F-017（P2，open）审计 emitted 白名单与 Worker SYSTEM/platform 写入缺失

**问题**：ADR-0021 §5.3（`:347`）要求首字后失败审计带 `emitted=true`；Worker `AuditDetailsPolicy`（`apps/worker/.../AuditDetailsPolicy.java:16-25`）对 `model.invocation.failed` 只允许 modelId/durationMs/errorCode/retryable，`emitted` 会被拒绝。ADR §7.4（`:500-501`）要求 `model.credential.invalidated` 以 SYSTEM、tenant=platform 写入；Worker `AuditService`（`:31-33,276-301`）固定 SERVICE actor，并使用调用方租户。

**影响**：首字后失败可能因审计 fail-closed 变成另一种错误；自动失效的审计租户/actor 不符合 ADR，租户侧可能看不到/看到错误事件。

**建议修复**：把 `emitted` 加入 `model.invocation.failed` 白名单；为 Worker 增加显式 `appendSystem(platform, ...)` 或可指定 actor/tenant 的内部方法，并在 platform `TenantContext` 下写；`IT-MODEL-104/105` 断言审计行 `actor_type=SYSTEM`、`tenant_id=platform`、`emitted=true`。

**验证方式**：首字后断流用例查审计；401 失效用例查审计租户/actor。

### F-018（P2，open）现有租户级凭据回退未移除

**问题**：`ModelRepository.findActiveCredential`（`apps/worker/.../ModelRepository.java:58-65`）先查当前租户，再回退 platform。M04 §6.1（`:380-384`）规定只能平台托管；B4 scope 覆盖 `model/**`，可以改，但 ADR 没有明确“删除租户回退”及负向测试。若只改路由层，遗留租户级 `provider_credential` 仍可能被使用。

**影响**：平台 key 池边界被破坏；租户级遗留凭据可能绕过平台配额/审计。

**建议修复**：M04 的 `ModelRepository` 只查 `tenant_id='platform'`；`IT-MODEL-105` 插入一条租户级凭据，断言运行时忽略且不影响 key 选择。

**验证方式**：集成测试构造租户级凭据，断言模型调用仍用平台 key。

### F-019（P2，open）文档状态不一致

**问题**

- PRODUCT §9 OQ-16..26 仍标 `pending`，与 DEC-0023=A 已批准冲突（`:278-290`）。
- ACCEPTANCE 仍写 M13 F-001 由 M04/M10 承接、M03 F-008 R-11 由 M04/M09 设计迁移（`:55-58`），与 PRODUCT §10.5/DEC-0023 “M04 不承接”冲突。
- ADR-0012 §4 全局表清单未更新，而 ADR-0021 `:127` 新增三张全局表并称已扩展。
- ARCHITECTURE `:115` 把 proposed ADR 写成“定稿”；`:322` 与 ADR-0021 `:368` 把共享 key 健康残余风险写成 R-22（实际 R-21，R-22 是 BYO）。

**影响**：实现者/评审者会读到互相矛盾的归属和状态；M04 可能再次承接 R-11/M13 F-001，或把 proposed 当已批准。

**建议修复**：Product 更新 OQ 状态并绑定 DEC-0023；Lead 更新 ACCEPTANCE 的归属；Architect 在 ADR-0021/ARCHITECTURE 明确 ADR-0012 例外并修正 R-21/R-22 与“定稿”措辞。

**验证方式**：文档 review；`grep` 状态一致性。

### F-020（P2，open）契约/测试资产边界未收口

**问题**

- 发送消息/创建报告的 429 仍引用 `TooManyRequests`（`ProblemDetail | QuotaExceededProblem`），契约测试无法断定 `RICCON-QUOTA-429` 形状（`contracts/openapi.yaml:3616-3633`）。
- `/api/v1/costs/invocations` 的 `userId` 过滤“必须属于本租户”，但没有 404 响应（`:3243-3283`）；AC-12 要求跨租户统一 404。
- 员工 `Model` schema 仍允许 `inputPricePer1M`/`outputPricePer1M`（`:5258-5264`），AC-15/§7.5 要求员工目录不返回价格，schema 不阻止泄露。
- M04 新增 audit target 类型后，`apps/web/src/pages/admin/audit/filter-fields.tsx:17-36` 与 `apps/web/src/services/audit/types.ts:18-36` 的硬编码联合类型没有任务更新。

**影响**：AC-08 的 429 形状、AC-12 的 404、AC-15 的价格边界、审计页的新目标类型都可能“实现正确但契约/UI 不表达”，或者反过来泄露。

**建议修复**：sendMessage/createResearchTask 的 429 直接引用 `QuotaExceeded`；`/costs/invocations` 加 404；员工目录改用不含价格字段的 `EmployeeModel` 或加测试断言 null；把 audit UI 的 target type 更新加入 B9 或单独的 M04 任务。

**验证方式**：契约测试逐端点断言；TEN-033 跨租户 404；员工 `/models` 无价格键；审计页可选新 target type。

### F-021（P2，open）拆分估算仍假设 4 个并行 builder

**问题**：ADR-0021 §8.4（`:600-621`）的 26–28h 与 M04a 20–22h 按“并行 4 builder”计算；M04a 的关键路径 `B1a→B2→B3→B4→B9→B11→V1a` 按人时相加约 23h（2+2+2+5+5+3+4），已触及 24h。Lead 的并行上限是 2–3 个 builder；拆分后若仍按 4 个排期，M04a 会超 24h。

**影响**：拆分仍可能触发预算超限；或者为了赶 gate 压缩 E2E/安全验证。

**建议修复**：按 2–3 builder 重算每段关键路径；把 B10（只依赖 B1a）移出 M04a 关键路径或与 V1a 并行；明确每段 wall clock（例如 M04 24h、M17 18h）并让 Lead 在任务卡记录；若无法压到 24h 内，回到用户决策。

**验证方式**：Lead 排期表与 `module wall_clock_limit_hours` 对齐；实际 EV 记录每任务耗时。

### F-022（P2，open）AC-03 的降级事件默认 7 天未落地

**问题**：PRODUCT §10.4（`:392`）规定审计查询中的降级事件默认展示最近 7 天；ADR-0021 §7.4（`:502`）只复用 M10 查询，M10 契约缺省是最近 24 小时（`contracts/openapi.yaml:1651-1660`）。没有任务负责 M10 审计页/查询的默认值。

**影响**：AC-03 的“按模型与时间筛选”在 UI 上只能手工选 7 天；产品口径不成立。

**建议修复**：明确 7 天是 M04 审计页的预设筛选（由 B9/B11 或 M10 页面 owner 实现），或在 M10 查询端点加 `defaultRange=7d` 参数；记录 owner。

**验证方式**：E2E 打开审计页，默认时间范围为 7 天，按 `action=model.failover.triggered`/`targetType=MODEL` 过滤。

### F-023（P2，open）应急 down SQL 在多 key 数据上无法恢复旧唯一键

**问题**：ADR-0021 §10.3（`:667-685`）的演练 SQL 把 DISABLED/INVALID 改成 RETIRED 后恢复 `UNIQUE (tenant_id, provider_id, credential_type)`。M04 后同一 provider 可有多行（AC-06 要求 ≥2 key），即使全部 RETIRED，旧唯一键仍会因多行冲突创建失败。ADR 却说该 SQL 要在迁移测试中验证可执行。

**影响**：回滚演练会失败；如果生产灾难恢复按此 SQL 执行，会卡在恢复唯一键。

**建议修复**：明确 down SQL 仅用于“单 key 数据”的回滚演练；多 key 场景必须先归档/删除多余 `provider_credential` 行（并记录影响），或只做应用回滚 + 备份恢复。迁移测试分别覆盖单 key/多 key。

**验证方式**：在 V11 多 key 数据上执行 SQL，预期失败并记录；单 key 数据执行成功。

### F-024（P3，open）API key DTO/日志脱敏要求未显式化

**问题**：ADR-0021 §6.6（`:421-426`）禁止 key 明文进日志/审计/错误，但没有要求 `ModelCredentialCreateRequest`/`RotateRequest` 的 `toString` 掩码、禁止请求体日志、异常消息不回显 `apiKey`。现有仓库没有统一请求体脱敏器；`SEC-M04-005` 只写“扫描响应/日志/审计/DB”，未覆盖校验失败/DTO toString/异常。

**影响**：实现可能在校验错误日志或调试输出中泄露 key；T4 证据不完整。

**建议修复**：在 B5 验收中加“DTO toString 掩码/不打印请求体/异常不带 apiKey”；`SEC-M04-005` 用 canary key 扫描应用日志、错误响应、审计、DB 证据、Playwright trace。

**验证方式**：用 canary key 调用成功/失败/校验失败路径，扫描全部输出无明文。

### F-025（P3，open）尝试/配额/降级审计的保留与容量未定义

**问题**：`model_invocation_attempt` 每次尝试一行，`quota_usage` 保留历史窗口，`model.failover.triggered` 每次切换一条审计；ADR 只给 `quota_usage` 的 M09 清理，没有 attempt 表保留期、降级审计量级/告警阈值。T4 长时间故障下可能放大 DB/审计分区压力。

**影响**：故障风暴时审计/attempt 表快速增长，影响查询和保留策略。

**建议修复**：定义 attempt 表保留（与 `model_invocation` 一致 ≥180 天或更短）、M09 清理任务；给 `model_failover_total`/审计写入量设告警；在 V2 演练中记录容量估算。

**验证方式**：故障注入 10 分钟，观察行数增长和查询 p95；M09 清理计划写入 RUNBOOK。

### F-026（P2，open）高风险任务缺独立任务级 Verifier closeout；V2 的 e2e/perf write_scope 与 B8 重叠

**问题**：B1a（迁移）、B1b（加密）、B2（SSRF/契约）、B4（AI 路由/并发）、B5（凭据）、B6（配额）都是高风险任务，但 ADR-0021 §8（`:545-558`）只有末端 V1/V2，没有为这些任务安排 done 前的独立 Verifier 任务评审。另 V2 的 write_scope（`:598`）写 `e2e/perf/**`（又说“只运行不修改”），与 B8 的 `e2e/perf/**`（`:593`）重叠；B11（`:596`）也没有显式要求 `@a11y` 标签。

**影响**：高风险任务可能由 Builder 自评后进入下一个任务，违反“高风险任务 done 前必须有独立 Verifier 评审”；V2 的 scope 会与 B8 冲突，`@a11y` 可能漏跑。

**建议修复**：为 B1a/B1b/B2/B4/B5/B6 各安排独立 Verifier 任务评审（可复用 V1/V2 人员，但不能由 Builder 自评），或在任务卡写明“V1 对应任务完成前不得 done”；V2 scope 改为 `e2e/perf/**` 只读 + `.riccon/evidence/**` 写；B11 验收加 `@a11y` 标签和四页扫描。测试资产顺序本身成立：B8 只依赖 B1a，先于 B11/V1/V2；B11 依赖 B9+B8；V1 依赖 B4/B5/B9/B11；V2 依赖 B6/B7/B8/B10。

**验证方式**：任务 JSON 中高风险任务有独立 review 引用；V2 不修改 B8 文件；`commands.a11y` 覆盖四页。

## 5. 拆分评估

### 5.1 单模块可行性

**不可行（24h）。**

- 关键路径 26–28h（ADR-0021 `:608`），且是 4 个并行 builder 的假设；在 2–3 builder 上限下不会更短。
- P0 13 条不能后移。即使把 P1 的 AC-04/07/10/13/14 全部后移，B9 仍必须交付路由/配额/成本三个管理页（AC-01/08/11 的 UI 证据），关键路径只减少很少；AC-13/14 是质量基线，后移也需用户批准。
- 压缩只能落在 E2E/安全/性能验证上，恰好是 T4 gate 的必需项；不建议。
- 若用户愿意把预算提高到约 36h 或接受范围削减，单模块可以作为“一个 M04 + 两个内部阶段 + 最后一次 gate”执行；这不是当前 DEC-0023 的 24h 口径。

**单模块下必须后移的范围（需用户批准）**：AC-04、AC-07、AC-10、AC-13、AC-14（全部 P1），并明确 AC-17/18 的验证仍保留；风险是管理面可用性、在途轮换语义和首字后失败体验缺失，且 AC-13/14 后移会让四个入口/axe 基线不判 pass。即使这样，剩余路径仍接近 24h，不能保证。

### 5.2 拆分可行性

**方向可行，但当前拆分表不可执行。** 建议按下表修正后再由 Lead 创建任务：

| 模块（实际 ID）                                                                 | AC 归属                                                                                                                      | 任务                                                                                | gate 判定                                                                                       |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| M04（别名 M04a）                                                                | AC-01..07、15、16；任务级 UI：路由/key 两页；AC-17 的 failover/首字部分只作 partial EV；AC-18 的路由/降级开关只作 partial EV | B1a、B1b、B2、B3、B4、B5、B8（路由部分）、B9（路由/key）、B10、B11（路由 E2E）、V1a | 只按 scoped acceptance 判 pass；**不得**声称 AC-13/14/17/18 完整 pass；评审包列明 partial       |
| 新数字模块（建议 M17，别名 M04b，`split_from: M04`，aliases `["M04a","M04b"]`） | AC-08..14、17、18 全部；配额/成本两页 + 四页总验收                                                                           | B6、B7、B8（成本/配额）、B9（配额/成本）、B11（成本/配额 E2E）、V1b、V2             | 最终模块；AC-13/14 四页、AC-17/18 用 M04a + M17 合并 EV；`dod.acceptance` 只有全部 18 条才 true |

关键约束：

- **AC-13/14 不能按页面拆**：AC-13 的“四个入口”、AC-14 的“四个页面”是原子断言；M04 只做任务级页面验收，AC 本身归最终模块。
- **AC-17/18 不能按测试类型拆**：单条 AC 的 Then 同时包含 failover 与 quota；M04 只产出 partial EV，最终模块合并判定。
- **契约**：M04a 发布 `1.3.0-m04a`（路由/key/端点/开关），quota/cost 端点要么不出现，要么 `x-riccon-deferred: M04b`；M04b 再升 `1.4.0-m04b`。不能先把全部端点写进 `1.3.0-m04` 再声称 M04a 已实现。
- **V11**：M04a 一次性建全部表（ADR 已定），M04b 不改 schema；M04b 若需修正另立 V12。
- **模块 JSON**：`.riccon/modules/M04.json` 的 acceptance 必须在任务创建前由 Lead 收窄；新模块用数字 ID + `split_from`/`aliases`，不要用 M04b 当 ID。
- **2–3 builder**：M04a 关键路径约 23h，仍无缓冲；建议把 B10 移出关键路径、V1a 安全测试在 B2/B5 后即可开始，并按 3 builder 重排。若 M04a 仍 >24h，需要再拆或用户批准预算。
- **用户决策**：拆分改变 AC 归属和模块范围，属于产品/交付决策；Lead 应把“AC-13/14/17/18 整条归最终模块、M04a acceptance 收窄”写进决策包，不能只靠 ADR 表格。

### 5.3 推荐

**推荐方案 A**：拆 M04 + M17（M04b），按上表修正 AC/gate/契约/模块 JSON；先做 M04 的路由/降级/key，再做 M17 的配额/成本，最终模块合并 AC-17/18。若用户不接受新模块 ID 或 AC 归属调整，则回到**单模块 + 预算提高/范围削减**的决策，不要在 24h 内假装完成。

## 6. 安全 / 契约 / 测试专项结论

### 6.1 安全

**成立的部分**：端点只来自部署允许列表、无端点创建 API、`Redirect.NEVER`、禁止 userinfo/query/fragment、加密信封、掩码返回、审计 details 白名单、租户从主体派生、跨租户 404、无链外回退、配额 fail-closed 的方向都是正确的；威胁模型 TB-04/TM-39..53 覆盖了主要面。

**必须补的缺口**：

1. fingerprint 无 pepper（F-010）；
2. 元数据 denylist 不完整/无 resolver seam（F-013）；
3. BASELINE 未重申 allowlist（F-014）；
4. M04 写端点契约缺 CSRF（F-001）；
5. 平台成本 schema 允许用户字段（F-003）；
6. metrics 暴露边界未定义（F-006）；
7. API key DTO/错误日志脱敏未显式化（F-024）。

### 6.2 契约

**成立的部分**：版本 `1.3.0-m04`、三个模型端点、MCP 目录 deferred M07、workspace-archive M09、BYO/多币种/价格编辑 deferred 的方向正确；权限码与错误码分组清楚。

**必须修**：

- provider PUT 路径（F-001）；
- 10 个写端点 CSRF（F-001）；
- AuditTargetType 三个新值（F-001）；
- CostInvocation.priceCurrency 可空（F-004）；
- 平台成本 schema 不含用户字段（F-003）；
- 429 形状/404/员工价格字段（F-020）；
- 契约文件 owner（F-007）。

### 6.3 测试

**成立的部分**：真实 MySQL（Testcontainers/Compose）用于并发；Clock 注入；BigDecimal 金额；fake provider 场景可确定性控制；a11y 用 Playwright axe；perf 用 k6；E2E 有真实页面基础。

**必须补**：

- 测试 ID 目录（F-016）；
- 50 并发独立事务/真实 HTTP 断言（F-008/F-006）；
- 3s 首字与 attempt 精确计数（F-005）；
- 审计 fail-closed 故障注入（F-002/F-008/F-017）；
- UI AC 逐条 E2E（F-012）；
- `@a11y` 四页标签（F-012）；
- run.mjs/test profile 环境（F-007）；
- 迁移 forward + rollback 多 key 场景（F-023）。

## 7. 不应进入实现的条件（最小修复清单）

以下任一未关闭，不应创建 M04 任务或写生产代码：

1. **F-001**：OpenAPI provider PUT 路径、10 个写端点 CSRF、AuditTargetType 三值。
2. **F-002**：审计 details 支持 `modelIds` 数组/`previousLimit` null，并有 fail-closed 测试。
3. **F-003/F-004**：Product 明确 AC-11 平台明细范围；平台成本 schema 去掉用户字段；`priceCurrency` 可空。
4. **F-005/F-006**：定义 3s 首字预算下的 per-attempt 超时；内层重试 `maxAttempts=1`；补 metrics 端点/50 并发环境。
5. **F-007**：补齐 run.mjs、RoleDefinitions、Web client、test profile、contracts 文件的 write_scope。
6. **F-008**：配额拒绝审计独立事务 + 安全 upsert/死锁处理；50 并发测试断言 1 成功/49 个 429/无 500。
7. **F-009**：拆分方案（或单模块 + 预算提高）经 Lead/用户确认；模块 ID/acceptance/契约分段落地。
8. **F-010**：fingerprint pepper 或 R-26 残余风险接受 + key 熵要求。
9. **F-011**：开关 env 名称与 quotaEnforcement 准入逻辑。
10. **F-012**：UI AC 逐条 E2E/验收绑定。
11. **DEC-0024**：V11 数据模型、API 主密钥依赖、准入计费、回滚窗口、单币种经用户批准；ADR-0021/0022 从 proposed 进入 accepted 前，Verifier/Ops/Lead 评审完成。

## 8. 未验证与剩余风险

- 未运行 build/lint/typecheck/test/coverage/E2E/perf/security/a11y：设计阶段无代码变更，且 verdict 已为 fail。
- 未验证真实 MySQL 上的 V11 在线 DDL、迁移时长、回滚 SQL；未验证 50 并发/3s 首字/成本查询 p95；未验证 API 主密钥启动失败路径。
- 未验证 Ops 的出网策略、Helm/备份、RUNBOOK；未验证 Product 对 AC-11/AC-18 文案的最终确认。
- ADR-0021/0022 仍为 `proposed`；DEC-0024 未创建/未回答；Lead、Ops、安全评审未完成。本评审不代替这些签字，也不代表用户验收。
- `gate check --module M04` 失败（planned/no tasks/no module review）是设计阶段的预期结果；即使创建任务，本 review 的 P1 未关闭前 gate 也不应通过。
