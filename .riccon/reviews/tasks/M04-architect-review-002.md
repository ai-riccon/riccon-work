---
review_id: REV-M04-ARCH-002
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-ARCH-001
reviewer:
  agent: riccon/verifier
  session: ses_e095e5cceffe4JZ6MdlCsSCgZi
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
findings:
  - id: F-001
    severity: P1
    status: open
    title: 拆分后威胁模型 gate 未收口：M04 gate 仍要求 B-18/B-19/B-20 及 M17 证据
    summary: >-
      THREAT-MODEL §8 规定 M04 gate 额外关闭 B-18、B-19、B-20；但 B-18 的关闭集合包含
      `SEC-M04-003b`（V2/M17），B-19 全部是配额/成本（`IT-QUOTA-102`、`IT-COST-101..104`、
      `TEN-032/033`），B-20 需要 V2 的 `/actuator/prometheus` 抓取。M04 阶段这些证据尚未产生，
      M04 gate 按原文无法通过；§10 的“M04 核对清单”也仍要求配额/成本项。拆分只改了 ADR §8.4，
      没有同步 THREAT-MODEL 的 gate 归属。
    evidence: "docs/security/THREAT-MODEL.md:603,624-626,860-868; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:665-666,682-684"
  - id: F-002
    severity: P1
    status: open
    title: AC-02 未被拆分：M04 承接的 AC-02 含配额写接口与只读配额页，M17 才交付
    summary: >-
      PRODUCT M04-AC-02 的 Then 同时要求“租户管理员或员工直接调用模型路由/key 配置写接口，
      或租户管理员尝试修改配额”、界面无写入口、租户管理员可只读查看本租户配额、响应不含配额内容。
      配额写接口和只读配额页属于 M17（B6/B12/B9b），M04 的 V1 只跑 `SEC-M04-003a`，
      不跑 `SEC-M04-003b`。M04 模块 acceptance 写“AC-01..07 全部通过”，但 AC-02 的配额子句
      在 M04 阶段没有端点/页面/证据，无法整条判 pass。
    evidence: "docs/PRODUCT.md:346; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:665,682,869"
  - id: F-003
    severity: P2
    status: open
    title: IT-MODEL-108 跨 M04/M17，B2 的 1.3.0-m04a 分段与契约测试口径不一致
    summary: >-
      ADR-0021 §11.1 把 `IT-MODEL-108` 定义为“三个模型端点 + 路由/凭据/开关/配额/成本 schema”，
      owner 为 B2；但 B2 的验收要求 M04 gate 前完成 `1.3.0-m04a`，quota/cost 端点 deferred/移除，
      M04 不声称实现。B2 在同一个 ID 下既测试被移除/标注 deferred 的配额/成本 schema，又声称
      契约分段完成，验收口径不一致；M17-B12 的配额/成本契约测试也没有独立 ID。R-03 新增了 B12，
      但没有拆 `IT-MODEL-108`，B2/B12 的契约测试边界需要收口（不阻塞实现，但会让评审对同一 ID
      的期望不一致）。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:581,652,660,855; contracts/README.md:7"
  - id: F-005
    severity: P1
    status: open
    title: M04 模块 acceptance 要求 quota/cost 表最小读写冒烟，但没有任务验收绑定
    summary: >-
      ADR-0021 §8.4 的 M04 acceptance 明确要求“V11 的 quota/cost 表结构与最小读写冒烟通过”，
      并说明 M04 gate 必须对 `quota_limit`/`quota_usage`/`model_invocation_attempt` 做
      空表 INSERT/ROLLBACK、FK/CHECK 断言。B1a 的验收只有“V10 种子后 V11 通过、多 key 唯一键/索引、
      权限回填、配置键占位、schema=11”，没有最小读写冒烟；V1 的验收也不包含迁移结构冒烟。
      M04 gate 的这条 acceptance 没有 owner 和证据路径。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:649,665,682,690"
  - id: F-006
    severity: P2
    status: open
    title: ARCHITECTURE/THREAT-MODEL 仍写 20s 首字前预算，与 ADR 的 60s/2.5s 不一致
    summary: >-
      ADR-0021 §5.2 已把生产默认改为 `pre-first-token-budget=60s`、AC-17 perf profile 改为
      `2500ms`；但 ARCHITECTURE M04 流程图仍写“首字前预算 20s”，THREAT-MODEL D-04-3/TM-41
      仍写“总预算（20s）”。实现者按架构图或威胁模型落地会得到第三种超时语义，上一轮 F-005 的修复
      没有贯穿到实现地图/威胁模型。
    evidence: "docs/ARCHITECTURE.md:257,368; docs/security/THREAT-MODEL.md:152,550; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:382-384"
  - id: F-007
    severity: P2
    status: open
    title: B3 被标为高风险但未列入独立 Verifier 任务评审清单
    summary: >-
      ADR-0021 §8.1 把 B3（路由配置 API、乐观锁、审计、生效读模型）标为“高（权限/AI 路由）”，
      但 §8.5 的高风险任务清单只有 B1a/B1b/B2/B4/B5/B6，没有 B3。项目硬规则要求权限类高风险
      任务在 done 前有独立 Verifier 评审；B3 目前可由 Builder 自评后进入 B4。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:603,695; AGENTS.md:379,411"
  - id: F-008
    severity: P2
    status: open
    title: V2 依赖表缺 B11b；E2E-05/06/07b 未就绪时 V2 可被创建
    summary: >-
      V2 的依赖列写 `M17-B6、M17-B7、M17-B12、M04-B8、M04-B10`，没有 B11b；但 V2 验收包含
      `E2E-M04-05/06`、`E2E-M04-07b` 四页和四页 `@a11y`，这些由 B11b 交付。§8.2 的图
      有 `B11b → V2`，表与图不一致，按表创建任务会让 V2 在 E2E 规格不存在时启动。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:616,631-634,666"
  - id: F-009
    severity: P2
    status: open
    title: B12 依赖表缺 M04-B2，契约 writer 串行边界只在散文
    summary: >-
      §8.2 散文要求“B2 独占 M04 契约，M17-B12 独占 M17 分段，两者不得并行/跨模块同时写，
      B12 依赖 B2 完成”；但 B12 的依赖列只写 `M17-B6、M17-B7`，没有 `M04-B2`。B2 与 B12
      都是 `builder-route`，同一 owner 只能有一个 in_progress，实际可避免并行，但任务图没有
      表达“B12 必须在 B2 之后”，跨模块会话仍可能先创建 B12。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:613,642,652"
  - id: F-010
    severity: P2
    status: open
    title: B1c 与 B10 的 scripts/ops 写范围重叠，且同 owner 都被描述并行
    summary: >-
      B1c 写 `scripts/ops/metrics-check*`，B10 写 `scripts/ops/**`，两者重叠；B1c 与 B10
      的 owner 都是 `riccon/ops`，§8.2 又把 B1c 描述为并行、B10 描述为并行。项目规则要求同一
      owner 同一时间只能有一个 in_progress，且一个文件只能有一个 writer；需要拆分目录或使用
      实例 owner，并在任务卡写清 metrics-check 的唯一 writer。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:601,608,638,651,662; AGENTS.md:375"
  - id: F-011
    severity: P2
    status: open
    title: 旧 SEC-MODEL-003/004/005/006 仍被 M04 文档引用，AC 到测试映射未收口
    summary: >-
      R-01 已在 §11.1 拆出 `SEC-M04-003a/003b`，但 ADR-0021 §9、ADR-0022 §4、THREAT-MODEL
      T-04-2/E-04-2/TM-40 仍使用 `SEC-MODEL-003`；PRODUCT AC-05/07/15 仍引用
      `SEC-MODEL-004/005/006`，这些 ID 在任何测试目录中都没有定义。ADR 脚注只解释了
      `SEC-MODEL-003`，没有覆盖 004/005/006，AC → 测试 → 证据仍可能对不上。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:706,889; docs/adr/ADR-0022-m04-quota-and-cost.md:284; docs/security/THREAT-MODEL.md:149,153,549; docs/PRODUCT.md:349,351,359"
  - id: F-012
    severity: P2
    status: open
    title: model.routing.updated 的 result 在 §4.4 允许、§7.4 白名单缺失
    summary: >-
      ADR-0021 §4.4 写 `model.routing.updated.details` 只允许 `purpose`、`modelIds`、`version`、
      `previousVersion`、`result`；§7.4 的 details 白名单表却没有 `result`。AC-02 要求审计可查到
      “结果”；若 B1a 按 §7.4 预置白名单，写 `result` 会被审计 fail-closed 拒绝，若不写又无法
      满足 AC-02 的“结果”语义（虽然审计事件本身有 outcome 字段）。两处需要统一。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:346,539; docs/PRODUCT.md:346; apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:180-194"
  - id: F-013
    severity: P2
    status: open
    title: M17 首发配额开关 false 仅为建议，B10 验收未绑定
    summary: >-
      ADR-0021 §8.4 写“M17 首发建议先将开关置 false，确认默认限额/租户用量后再打开”，
      §12 又把“M17 首发配额开关先 false 再打开”列为需要 DEC-0024 批准的项；但 B10 的验收
      没有包含这条发布步骤，B10 又是 RUNBOOK 的 owner。M17 上线当天如果直接沿用 V11 种的
      `quotaEnforcementEnabled=true`，可能在确认限额前即开始拒绝请求。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:662,691,934"
  - id: F-014
    severity: P2
    status: open
    title: ADR-0012 仍写 M04 租户级路由/可写租户级凭据，与平台托管边界冲突
    summary: >-
      ADR-0012 §4 仍写“model_provider/model：M04 再引入租户级路由”和“M04 可写租户级记录，
      不改变表结构”；ADR-0021 §6.1 明确 M04 只有 `tenant_id='platform'` 的平台托管 key 池，
      Worker 删除租户回退。F-019 把 ADR-0012 标为 deferred（旧 ADR 不改），但这两句是安全边界
      陈述，不只是历史文档；Builder 若先读 ADR-0012 可能实现出租户级凭据。
    evidence: "docs/adr/ADR-0012-m02-data-model-identifiers-and-tenant-keys.md:47,56; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:430,980"
  - id: F-015
    severity: P2
    status: open
    title: 模块状态未落地：M04.json 仍 18 AC/24h，M17/DEC-0024 不存在
    summary: >-
      `.riccon/modules/M04.json` 的 acceptance 仍是“M04-AC-01..18 全部通过”、
      `wall_clock_limit_hours: 24`、`tasks: []`、`split_from: null`、`aliases: []`；仓库没有
      M17 模块 JSON，也没有 DEC-0024。ADR 已把“M04 只承接 AC-01..07/15/16、M17 承接
      AC-08..14/17/18”写成设计，但 Lead 的“收窄 acceptance/创建 M17/建 DEC-0024”尚未执行。
      这不否定 ADR 设计，但在 Lead 落地前不能创建 M04 任务。
    evidence: ".riccon/modules/M04.json:13-17,27,31; `riccon status` 输出：M04 planned、0/0 tasks、0 open decisions；`.riccon/decisions/` 无 DEC-0024"
  - id: F-016
    severity: P2
    status: open
    title: M17 任务仅依赖 M04-B1a/B4，未表达 M04 验收后再启动
    summary: >-
      §8.2 的 M17 图从 `M04-B1a → B6`、`M04-B4 → B7` 开始，没有 `M04-V1`/M04 gate/用户验收
      依赖。按图 B6/B7 可以在 M04 还没通过模块 gate 时启动；项目规则要求前一个用户可见模块的
      Product/用户验收完成后才能启动下一个用户可见模块。M17 是 `split_from: M04` 的用户可见
      模块，需要明确在 M04 验收后启动，或在 DEC-0024 中批准例外。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:611-616,631-634; docs/COLLABORATION.md:28"
notes: >-
  本次只读评审设计文档与契约，未修改 ADR/ARCHITECTURE/THREAT-MODEL/PRODUCT/源码/测试/迁移；
  只写本评审文件。未运行 build/test/coverage/E2E/k6/security/a11y：设计阶段无源码变更，
  且本 verdict 为 fail。ADR-0021/0022 仍为 proposed，DEC-0024 尚未创建，`.riccon/modules/M04.json`
  仍为 18 AC/24h。上一轮 F-001..F-012 的逐条结论见正文 §3；本轮新增 P1 为 F-001/F-002/F-005。
  编号 F-004 在复审中撤销：B9b 可按既有 `AuditClient`/`UserAdminClient` 模式在 `services/quota|cost`
  内建独立 client，不需要写 `client.ts`，不构成 write_scope 缺陷；保留空位以免与上一轮 F-004 混淆。
created_at: "2026-11-20T10:07:05+08:00"
---

# REV-M04-ARCH-002：M04 模型网关纵深 — 第二轮独立架构复审（focused）

## 1. 结论

**fail。** 第一轮 12 个 P1 中，契约结构、审计 details、成本 schema、内层重试、指标/并发、配额审计、fingerprint pepper、开关 env 等主体修复已经到位，契约脚本检查全部通过；但拆分后的 **gate 归属、AC-02 原子性、quota/cost 表冒烟 ownership** 仍有 3 个 P1 不可执行。M04 如果按当前文档创建任务并跑 gate，会在“M04 gate 需要 M17 证据”和“AC-02 需要 M17 页面/端点”两处同时失败。

这不是对 M04/M17 拆分方向的否定；拆分后的 18 个任务、3 builder 关键路径和 `1.3.0-m04a`/`1.4.0-m04b` 分段方向是成立的。问题集中在拆分边界没有全部落到 gate、AC、测试 ID 和写范围。

## 2. 评审范围与验证方法

已读：

- 第一轮 `.riccon/reviews/tasks/M04-architect-review.md`、Ops 评审 `.riccon/reviews/tasks/M04-ops-review.md`；
- `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md` 全文（重点 §2、§3、§4.4、§5.2、§6、§7、§8.1–8.5、§10、§11.1、§13）；
- `docs/adr/ADR-0022-m04-quota-and-cost.md` 全文（§1、§2、§3、§4、§5、§7、§8）；
- `docs/ARCHITECTURE.md` M04 段、性能预算、评审状态表；
- `docs/security/THREAT-MODEL.md` TB-04、TM-39..TM-54、B-18..B-20、R-21..R-25、§10 核对清单；
- `contracts/openapi.yaml` M04 增量、`contracts/README.md`、`x-riccon-deferred`；
- `docs/PRODUCT.md` §9 OQ、§10.1–§10.6、`docs/ACCEPTANCE.md` 已接受残余；
- `.riccon/modules/M04.json`、`riccon status`、`.riccon/decisions/`；
- 现有实现基线（只读）：`apps/web/src/services/api/client.ts`、`apps/api/.../AuditDetailsPolicy.java`、`apps/worker/.../AuditDetailsPolicy.java`、`apps/worker/.../AuditService.java`、`apps/api/.../ChatSubmissionService.java`、`ResearchTaskService.java`、`V5`/`V10`。

实际运行的验证（退出码）：

| 命令                                                                                           | 退出码 | 结果                                                                       |
| ---------------------------------------------------------------------------------------------- | ------ | -------------------------------------------------------------------------- |
| `node $HOME/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict --project .` | 0      | 0 errors / 0 warnings                                                      |
| Ruby YAML 解析 `contracts/openapi.yaml`                                                        | 0      | OpenAPI 3.1.0，`info.version=1.3.0-m04`，88 paths / 154 schemas            |
| Ruby path 参数模板检查                                                                         | 0      | 0 个 path 参数不在模板中（F-001 provider PUT 已修）                        |
| Ruby 写端点 CSRF 检查                                                                          | 0      | 53/53 个 POST/PUT/PATCH/DELETE 声明 `CsrfToken`（M04 10 个写端点全部覆盖） |
| Ruby `$ref` 解析检查                                                                           | 0      | 956 个 `$ref` 全部可解析，0 坏引用                                         |
| Ruby operationId 唯一性检查                                                                    | 0      | 109 个 operation，0 重复                                                   |
| Python 测试 ID 目录检查                                                                        | 0      | §11.1 共 40 个 ID，0 个同表重复；`IT-MODEL-108` 仍跨模块                   |
| `node .../riccon.mjs status`                                                                   | 0      | M04 planned、0/0 tasks、gate 仍为 M10、0 open decisions；无 DEC-0024       |
| `node .../riccon.mjs gate check --module M04`                                                  | 1      | 预期失败：M04 planned、0 tasks、无模块 review；不是本设计评审的通过条件    |
| `git diff --check`                                                                             | 0      | 无空白错误                                                                 |

未运行：`build`、`lint`、`typecheck`、`test`、`coverage`、`e2e`、`perf`、`security`、`a11y`。设计阶段没有源码变更；本 verdict 为 fail，模块 gate 不应通过。

## 3. 第一轮 P1 关闭表（F-001..F-012）

| 上一轮 finding                                           | 结论                 | 证据 / 残留                                                                                                                                                                                                                                                                   |
| -------------------------------------------------------- | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 provider PUT 路径、10 写端点 CSRF、AuditTargetType | **fixed**            | provider PUT 已是 `/api/v1/admin/model-providers/{providerId}`（`openapi.yaml:2709-2721`）；53/53 写端点带 CSRF；`AuditTargetType` 枚举含 `MODEL_ROUTING`/`MODEL_CREDENTIAL`/`QUOTA`（`:3947-3967`）；B1a 写范围覆盖 `platform-domain/.../audit/**` 与 `RoleDefinitions.java` |
| F-002 AuditDetailsPolicy 数组/nullable                   | **fixed（P2 残留）** | ADR-0021 §7.4 增加受限数组（≤20 UUID、≤2 KiB）与动作级 nullable；B1a 同时改 API/Worker 两份 policy。残留：`model.routing.updated.result` 在 §4.4 允许、§7.4 白名单缺失（本轮 F-012）                                                                                          |
| F-003 平台/租户成本 schema、平台无用户字段               | **fixed**            | `TenantCostReport`/`TenantCostBucket` 含用户字段，`PlatformCostReport`/`PlatformCostBucket` 不含；平台 `groupBy` 只有 `TENANT/MODEL/DAY`（`openapi.yaml:5851-5925`）；ADR-0022 §2.4 明确 AC-11 明细只适用租户管理员                                                           |
| F-004 `priceCurrency` 必填与非空冲突                     | **fixed**            | `CostInvocation.priceCurrency`/`amount`/`inputPricePer1M`/`outputPricePer1M` 均为 `[type, "null"]`，`priceCurrency` 不在 required（`openapi.yaml:5932-5968`）                                                                                                                 |
| F-005 3s 首字与 timeout/attempt 语义                     | **partial（P2）**    | ADR §5.2 已固定内层 `maxAttempts=1`、生产 60s、perf 2.5s、attempt 数 = HTTP 调用数；但 ARCHITECTURE:257 与 THREAT-MODEL:152,550 仍写 20s，见本轮 F-006                                                                                                                        |
| F-006 指标/50 并发                                       | **fixed（P2 残留）** | 新增 B1c（Micrometer/Prometheus、9 指标、`metrics-check`）、B2 Actuator 暴露、B8 perf env（`RICCON_CHAT_MAX_STREAMS=50`、`RICCON_DB_POOL_SIZE>=25`）。残留：B1c/B10 `scripts/ops` 写范围重叠（本轮 F-010），B-20 gate 归属（本轮 F-001）                                      |
| F-007 write_scope 关键文件                               | **fixed**            | run.mjs 归 B8、RoleDefinitions 归 B1a、test profile 归 B1a、contracts 归 B2/B12、B9a 的 `client.ts` 已补；B9b 可按现有 `AuditClient`/`UserAdminClient` 模式在 `services/quota                                                                                                 | cost`内建独立 client，不需要改`client.ts`。残留（P3）：`WorkerIntegrationTestBase` 未显式落到某个任务，建议在 B1a 或 B4 验收中写清 |
| F-008 配额审计 REQUIRES_NEW/upsert                       | **fixed**            | ADR-0022 §1.3 改为 `ON DUPLICATE KEY UPDATE`（无 `INSERT IGNORE`）、拒绝审计 `REQUIRES_NEW`、失败 500、锁超时 503、不自动重试；B6 验收覆盖 1 成功/49 个 429/无 500                                                                                                            |
| F-009 拆分 gate/AC 判定                                  | **partial（P1）**    | ADR §8.4 已有 M04/M17、18 任务、AC 整条归属；但 AC-02 仍含 M17 配额子句（本轮 F-002），模块 JSON/DEC-0024 未落地（本轮 F-015）                                                                                                                                                |
| F-010 fingerprint pepper                                 | **fixed**            | ADR-0021 §3.2 改为 HMAC-SHA-256 + `RICCON_CREDENTIAL_FINGERPRINT_PEPPER`；B1b/B10；DEC-0024 列入                                                                                                                                                                              |
| F-011 env 名称、quotaEnforcement                         | **fixed**            | ADR-0021 §10.5 定义三个 env 与优先级；ADR-0022 §1.3 增加 `quotaEnforcementEnabled=false` 只计量不拒绝                                                                                                                                                                         |
| F-012 UI AC 证据                                         | **fixed（P2 残留）** | B9a/B11a/B11b 逐条绑定 AC-07/11/13/14/15；`E2E-M04-07a/07b` 分两页/四页；B11 显式 `@a11y`。残留：PRODUCT 仍引用未定义 `SEC-MODEL-004/005/006`（本轮 F-011）                                                                                                                   |

结论：F-001/F-003/F-004/F-007/F-008/F-010/F-011 完全关闭；F-002/F-006/F-012 主体关闭但有 P2 文本残留；F-005/F-009 仍有实质残留，分别对应本轮 F-005/F-002。

## 4. R-01..R-04 核对

| 项                       | 结论        | 核对结果                                                                                                                                                                                                                                                                                                                                     |
| ------------------------ | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R-01 V1/V2 测试归属      | **partial** | V1 只跑 M04 9 AC、`TEN-031`、`SEC-M04-001/002/004/005/006`、`SEC-M04-003a`；V2 跑 M17 9 AC、`TEN-032/033`、`SEC-M04-003b`、`IT-QUOTA/COST`、`E2E-M04-05/06/07b`。`E2E-M04-07a/07b` 与 `SEC-M04-003a/b` 已拆分，§11.1 40 个 ID 无同表重复。残留：`IT-MODEL-108` 仍跨 M04/M17（本轮 F-003）；旧 `SEC-MODEL-003` 仍在 TM/ADR 引用（本轮 F-011） |
| R-02 B1c/B8/B10 角色边界 | **partial** | B1c 已移除 `SecurityConfig`（归 B2），B8 已移除 `ci.yml`（归 B10）；B1c 保留 pom/metrics-check/负向测试。残留：B1c 的 `scripts/ops/metrics-check*` 与 B10 的 `scripts/ops/**` 重叠，且同 owner 并行（本轮 F-010）                                                                                                                            |
| R-03 M17 契约 writer     | **partial** | 新增 M17-B12（`builder-route`，仅契约/README/契约测试），B12 在 B6/B7 之后、B9b 之前，M04a/B12 分段方向正确。残留：B12 依赖表缺 `M04-B2`（本轮 F-009）；`IT-MODEL-108` 未拆 M04/M17（本轮 F-003）                                                                                                                                            |
| R-04 AC-13/14 整条归 M17 | **fixed**   | B11a 只用 `E2E-M04-07a`（两页），B11b 用 `E2E-M04-07b`（四页）；AC-13/14 整条归 M17，M04 不声称完整 pass。B11b 的 07b 还带 audit 7 天预设，属于轻微重复覆盖，不构成 P1                                                                                                                                                                       |

## 5. 新任务图与拆分 gate 结论

**任务数量与关键路径：成立。** §8.1 共 18 个任务，M04 12 个（B1a/B1b/B1c/B2–B5/B8/B9a/B10/B11a/V1），M17 6 个（B6/B7/B12/B9b/B11b/V2）；§8.4 的 M04 17–19h、M17 14–16h 按 3 builder 计算，关键路径与依赖图基本一致。B8 测试资产先于 B11a，B11a/B11b 只写 E2E 规格，V1/V2 对 `e2e/**` 只读，写范围不重叠。

**但以下 gate 结论不成立：**

1. **M04 gate 依赖 M17 证据（P1，F-001）。** THREAT-MODEL §8 仍要求 M04 gate 关闭 B-18/B-19/B-20；B-18 的 `SEC-M04-001..006` 含 `SEC-M04-003b`（V2/M17），B-19 是 `IT-QUOTA-102`/`IT-COST-*`/`TEN-032/033`（M17），B-20 需要 V2 的 Prometheus 抓取。M04 阶段无法产生这些证据，gate 无法通过。
2. **AC-02 不能整条归 M04（P1，F-002）。** AC-02 的配额写接口、只读配额页、响应不含配额内容属于 M17；V1 只跑 `SEC-M04-003a`，不跑 `SEC-M04-003b`。M04 acceptance 写“AC-01..07 全部通过”，但 AC-02 在 M04 没有完整证据。
3. **契约分段与契约测试口径不一致（P2，F-003）。** B2 要在 M04 gate 前把 `1.3.0-m04a` 的 quota/cost 端点移除/标 deferred，但 `IT-MODEL-108`（B2 owner）仍要求测试配额/成本 schema；M17-B12 没有独立契约测试 ID。
4. **M04 模块 acceptance 无 owner（P1，F-005）。** “quota/cost 表结构与最小读写冒烟”没有落到 B1a 或 V1 的验收。
5. **M17 启动顺序未表达（P2，F-016）。** B6/B7 只依赖 M04-B1a/B4，没有依赖 M04 gate/用户验收；按图可以在 M04 未验收时启动 M17，违反 `docs/COLLABORATION.md:28`。

**契约分段顺序：** 当前 `1.3.0-m04` 全量设计 → DEC-0024 批准 → B2 发布 `1.3.0-m04a`（quota/cost deferred/移除）→ M04 gate → M17-B12 发布 `1.4.0-m04b`。这个顺序本身可执行，但前提是 DEC-0024 先批准、B2 与 B12 的 writer 串行关系写进依赖表，且 `IT-MODEL-108` 按段拆分。M04a 不能声明 quota/cost 已实现；B2 验收已写明“M04 不声称实现”，但 AC-02/F-005 仍会让 M04 部分声称配额能力。

**M17 首发配额开关：** ADR §8.4 只写“建议先将开关置 false”，B10 验收没有包含该发布步骤；需要在 B10 RUNBOOK 验收和 M17 评审包中变成强制步骤（P2，F-013）。

## 6. 新发现的 P0/P1 与最小修复清单

### P1（必须在 M04/M17 任务创建或 gate 前关闭）

1. **F-001 威胁模型 gate 按拆分收口。** 在 THREAT-MODEL §8 明确：
   - M04 gate：B-18 只关闭 `SEC-M04-001/002/003a/004/005/006` + `IT-MODEL-105/106/107` + `TEN-031`；B-20 只关闭 B1c `metrics-check`（可定义 B-20a）；
   - M17 gate：B-19、`SEC-M04-003b`、`TEN-032/033`、`IT-QUOTA/COST`、B-20b（V2 抓取）；
   - §10 的 M04 核对清单同步去掉配额/成本项，或标注“M17 复核”。
2. **F-002 拆分 AC-02。** 由 Product/Architect 把 AC-02 的“租户管理员修改配额 / 只读配额页 / 响应不含配额内容”明确归 AC-10/M17（或拆成 AC-02a/02b）；M04 acceptance 只保留模型路由/key 配置写越权与路由审计。
3. **F-005 绑定 quota/cost 表冒烟。** 在 B1a 验收加入“`quota_limit`/`quota_usage`/`model_invocation_attempt` 空表 INSERT/ROLLBACK、FK/CHECK 断言”，并在 V1 或 B1a 证据中绑定；否则 M04 acceptance 该条无 owner。

### P2（可与上述修复同批处理，不单独阻塞，但需在实现前写清）

- F-003 拆 `IT-MODEL-108`：定义 `IT-MODEL-108a`（M04：三个模型端点 + 路由/凭据/开关 schema）与 `IT-MODEL-108b`（M17：quota/cost schema），或给 B12 一个独立契约测试 ID；B2/B12 验收和 §11.1 同步。

- F-006 统一 ARCHITECTURE:257、THREAT-MODEL:152/550 的超时口径为“生产 60s / perf 2.5s”。
- F-007 把 B3 加入 §8.5 高风险任务独立评审清单。
- F-008 给 V2 依赖补 B11b；F-009 给 B12 依赖补 M04-B2。
- F-010 拆分 B1c/B10 的 `scripts/ops` 写范围（或使用实例 owner 并写唯一 writer）。
- F-011 把 THREAT/ADR/PRODUCT 中旧 `SEC-MODEL-003/004/005/006` 统一映射到 `SEC-M04-*`。
- F-012 统一 `model.routing.updated` 的 `result` 白名单位置。
- F-013 把 M17 首发配额开关 false 写进 B10 RUNBOOK 验收与 M17 评审包。
- F-014 在 ADR-0012 顶部加“M04 以 ADR-0021 为准”的 superseded 注记，或由 Architect 修正两句；F-019 仍需 Lead/Product 更新 ACCEPTANCE。
- F-015 Lead 创建 DEC-0024、M17 模块，并收窄 `.riccon/modules/M04.json` 的 acceptance/wall clock。
- F-016 在任务图或 module `depends_on` 表达“M17 在 M04 验收后启动”。

## 7. 仍 deferred 的两项是否阻塞

### OPS-M04-017（deploy 文件缺失）

**不阻塞 M04 gate，但必须记录。** ADR-0021 §10.2（`:740`）已明确 M04 不使用 `deploy_preview`/`deploy_prod` 取证，迁移/回滚走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`；M04 评审包应标 `deploy_preview not-applicable`（M09）。Lead 在 gate 说明中确认即可。若 Lead 要求 M04 必须有预览环境，则需另立任务并加预算。

### F-019（ADR-0012/PRODUCT/ACCEPTANCE 文档同步）

**不阻塞 M04 gate，但不能当作已解决。** PRODUCT §9 OQ 已改为“已定（DEC-0023）”，R-21/R-22 与“定稿”措辞已在 ADR-0021/ARCHITECTURE 修正；但 ADR-0012 §4 仍写“M04 再引入租户级路由”“M04 可写租户级记录”（`:47,:56`），ACCEPTANCE 仍把 M13 F-001/R-11 写成 M04/M10、M04/M09 承接（`docs/ACCEPTANCE.md:55-58`）。M04 acceptance 已明确“R-11、F-015、M13 F-001 不承接”，所以不阻塞 gate；但 Lead/Product 必须在 M04 评审包登记该残余，并在 M04 gate 前或 M17 前完成 ACCEPTANCE 更新，Architect 对 ADR-0012 的两句加 superseded 注记。否则 Builder/评审者仍可能按旧 ADR 实现租户级凭据。

## 8. 未验证与剩余风险

- 未运行 build/lint/typecheck/test/coverage/E2E/perf/security/a11y；设计阶段没有源码变更，且 verdict 为 fail。
- 未在真实 MySQL 8.4.13 上验证 V11（尚不存在）、50 并发、3s 首字、成本查询 p95、Prometheus 抓取；这些属于 B1a/B6/B7/B8/V2 的后续证据。
- 未在浏览器中验证四个管理页、375px、键盘、axe；属于 B9a/B9b/B11a/B11b 的后续证据。
- ADR-0021/0022 仍为 `proposed`；DEC-0024 尚未创建；`M04.json` 仍为 18 AC/24h；M17 模块不存在。本评审不代替 Lead/用户/Architect/Ops 的签字，也不构成生产发布批准。
- `gate check --module M04` 目前会因 M04 planned、0 tasks、无模块 review 失败，这是设计阶段的预期结果；即使创建任务，本轮 P1 未关闭前 gate 也不应通过。
