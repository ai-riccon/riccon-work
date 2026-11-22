---
review_id: REV-TASK-0107-001
module_id: M04
task_id: TASK-0107
reviewer:
  agent: riccon/verifier
  session: ses_e05aaad94ffe2prLtqd5rbw3tW
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - f9bff57b5b682ab7a41f87224775eb7edd269ee3
head_commit: 84e78c9a535bcac2602e4a4be061b7d006e4c5b6
evidence: [EV-0547]
reviewed_evidence: [EV-0547]
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/architect + riccon/ops/B10"
    due: "M04 模块 gate 前（ADR-0022 §1.3 修订）"
    title: "租户行锁是必要的死锁修正，但 ADR-0022 §1.3 未更新，且实际加锁顺序与 ADR/Javadoc 不一致"
    summary: >-
      B6 在准入事务中增加 `SELECT id FROM tenant WHERE id=:tenantId FOR UPDATE`（QuotaJdbc.java:45-53、
      190-198），并在 ChatSubmissionService.java:156、ResearchTaskService.java:181 的业务行锁之前调用，
      QuotaAdmissionService.admit:140 再次幂等确认。判断：这是必要修正，不是过度串行化。理由：MySQL 的
      `INSERT ... ON DUPLICATE KEY UPDATE` 在 50 并发首次创建同一 quota_usage 主键时存在重复检测共享锁
      升级排他锁的死锁窗口；ADR §1.3 禁止自动重试且把任何死锁视为 B6 缺陷，租户行 X 锁在配额行创建之前
      消除了该窗口。准入本来就会在 TENANT/DAILY 热点行上串行到事务提交，租户锁只是把串行点提前，未新增
      第二套热点；不同租户互不阻塞。独立执行 IT-QUOTA-102 通过（1×201、49×429、0 个 ≥500），EV-0547
      日志中恰好 49 条该租户的 quota.exceeded，无 deadlock 日志，四个窗口 used_count=1。
      但该锁扩大了锁范围：事务持 X 锁期间，任何带 tenant 外键的插入（消息、run、文件、任务）都会因 FK
      父行 S 锁而等待；混合负载未测量。ADR §1.3 还要求 innodb_lock_wait_timeout 小于 API 超时（建议 3s），
      仓库中没有该配置（OPS-M04-014 仍部分开放）。另外实际顺序是 TENANT/DAILY → USER/DAILY →
      TENANT/MONTHLY → USER/MONTHLY（QuotaAdmissionService:145-147），而 ADR §1.3 与
      QuotaAdmissionService 类 Javadoc:36-37 写的是 TENANT/DAILY → TENANT/MONTHLY → USER/DAILY →
      USER/MONTHLY；所有事务同序，不构成死锁缺陷，但文档与实现不一致。
    evidence: "apps/api/src/main/java/com/riccon/api/quota/persistence/QuotaJdbc.java:45-53、190-198；apps/api/src/main/java/com/riccon/api/quota/service/QuotaAdmissionService.java:36-37、101-122、132-201；apps/api/src/main/java/com/riccon/api/chat/service/ChatSubmissionService.java:156-169；apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:181-196；docs/adr/ADR-0022-m04-quota-and-cost.md:95-131；.riccon/evidence/TASK-0107/EV-0547-*.log（49 条 quota.exceeded，无 deadlock）"
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/lead → audit domain（TASK-0065 scope）+ M04-B6 测试"
    due: "M04 模块 gate 前"
    title: "quota.limit.changed.previousLimit 首次/明确不限→数字时被省略而非 JSON null；AuditEvent 无法承载 null"
    summary: >-
      ADR-0022 §1.5/§3.4 与 ADR-0021 §7.4 要求 quota.limit.changed 的 previousLimit 首次设置允许 JSON
      null，且 AuditDetailsPolicy 已把 previousLimit 放进动作级 nullable 白名单（AuditDetailsPolicy.java:164-181）。
      但 AuditEvent 的紧凑构造器用 Map.copyOf 复制 details（AuditEvent.java:65），Map.copyOf 遇到 null 值直接
      NPE，因此该 nullable 语义在事件层不可达。B6 的绕行是省略 previousLimit（PlatformQuotaService.java:303-309），
      并在 QuotaAdminApiTest:144、155、160 断言 isMissingNode；这不仅覆盖首次设置，也覆盖
      “显式不限（null）→ 数字”的非首次变更（同文件:157-160）。影响：审计仍能查到对象、操作者、新值、时间和
      结果，但无法查到这两类变更的前值，M04-AC-10 的“前后值”只部分满足；不同实现/旧版本事件的缺键也无法
      区分。判为 P2 而非 P1：审计行本身没有丢失，授权、计数和可见性不受影响；但若 M04 模块评审按 AC-10 严格
      解释，应在模块级升级为 P1 并阻止 gate。修复建议（按优先级）：① 让 AuditEvent 接受 null 值（例如
      `Collections.unmodifiableMap(new LinkedHashMap<>(details))` 或等价的 null 容忍拷贝），补
      `AuditEvent.create` + AuditJdbc 序列化 null 的测试，B6 恢复总是写 previousLimit（允许 null），并把
      QuotaAdminApiTest 改为断言 isNull；AuditEvent 不在 B6 write_scope（属 TASK-0065 的
      apps/api/src/main/java/com/riccon/api/audit/domain/**），需 Lead 新建 fix 任务或重开 audit-domain 任务。
      ② 若决定保留 absent 语义，必须由 Architect/Product 修订 ADR-0022 §1.5/§3.4、ADR-0021 §7.4 与
      PRODUCT AC-10，明确“缺键 == null”，并补 M10 读取端测试；不得用字符串哨兵。limit 为 null 时写 0 是
      B1a REV-TASK-0100-001 F-003（P3 accepted）的既有约定，本评审不重复要求改 limit。
    evidence: "apps/api/src/main/java/com/riccon/api/audit/domain/AuditEvent.java:65；apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:164-181；apps/api/src/main/java/com/riccon/api/admin/quota/service/PlatformQuotaService.java:277-326；apps/api/src/test/java/com/riccon/api/admin/quota/QuotaAdminApiTest.java:140-161；docs/adr/ADR-0022-m04-quota-and-cost.md:152-153、273-279；docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:550-558"
  - id: F-003
    severity: P2
    status: open
    owner: "riccon/builder-quota"
    due: "M04 模块全量测试/模块 gate 前"
    title: "QuotaAdminApiTest 平台列表断言假设目标租户在第 1 页；共享测试库租户超过 100 会假失败"
    summary: >-
      QuotaAdminApiTest.platformList_hasNoUserFields 以 `page=1&size=100` 请求
      /api/v1/admin/quotas，再用 findTenant 查找刚创建的 fixture 租户（QuotaAdminApiTest.java:93-100）。
      AdminJdbc.LIST_TENANTS 按 `created_at ASC, id ASC` 排序（AdminJdbc.java:46-47），新租户在末尾；
      ApiIntegrationTestSupport 明确所有测试共享同一 MySQL 容器、只用唯一租户代码隔离（ApiIntegrationTestSupport.java:20-26），
      外部热栈还会跨运行累积。当前 API 测试有 589 个 @Test、约 186 处 fixture/租户创建调用；一旦库内 tenant 超过 100，
      新 fixture 不在第 1 页，findTenant 抛 AssertionError。EV-0547 与我的定向复跑都在小库上通过，未触发；
      这是全量套件的假失败风险，不是假绿，但会阻碍模块 gate。
      修复：先读第 1 页响应的 totalPages，再请求最后一页查找；或循环分页；或直接 `SELECT COUNT(*) FROM tenant`
      计算 page。不要把 size 提到 100 以上（OpenAPI PageSize 上限 100）。
    evidence: "apps/api/src/test/java/com/riccon/api/admin/quota/QuotaAdminApiTest.java:88-107；apps/api/src/main/java/com/riccon/api/admin/persistence/AdminJdbc.java:46-47；apps/api/src/test/java/com/riccon/api/support/ApiIntegrationTestSupport.java:19-26；contracts/openapi.yaml PageSize（maximum 100）"
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder-quota → M04-V1/V2"
    due: "M04 模块 gate 前补齐或书面接受"
    title: "高价值测试缺口：IT-QUOTA-104 未覆盖在途 run 继续、AC-09 未覆盖调高限额后重试、并发同幂等键未测、49 条拒绝审计未断言"
    summary: >-
      ① QuotaStoreFailureApiTest 的类注释明确“本用例只验证新建路径”（QuotaStoreFailureApiTest.java:18-23）；
      ADR §6 的 IT-QUOTA-104 要求在途 run 继续，未测。② AC-09/ADR §1.4 “超限后调高限额重试→新建 run→
      正常扣 1”没有测试覆盖；现有用例只验证降低限额后拒绝。③ 并发同 Idempotency-Key 未测：当前租户锁在
      幂等查询之前取得，理论上保证 loser 看到 winner 且不会重复扣（ResearchTaskService.java:181-205），
      但没有回归测试守住这个关键不变量；若未来移除租户锁，先查幂等→准入→插入冲突→返回既有任务且不抛异常
      的路径会提交一次多余扣减。Chat 的 clientMessageId 也只有顺序重放测试。④ IT-QUOTA-102 只断言状态码和
      used_count，没有断言 49 条 quota.exceeded 审计；EV 日志中该租户恰好有 49 条，但测试本身不防回归。
      建议：补“已有 run + 配额存储故障 → 旧 run 仍在途、新请求 503”“拒绝→调高限额→重试成功” “10 并发同
      Idempotency-Key → 1 task/1 run/used=1”“并发测试断言该租户 quota.exceeded=49”。这些不阻塞 B6 任务级
      pass，但应进入 V1/V2 或模块 gate 前补齐。
    evidence: "apps/api/src/test/java/com/riccon/api/quota/QuotaStoreFailureApiTest.java:18-23、43-60；apps/api/src/test/java/com/riccon/api/quota/QuotaAdmissionApiTest.java:33-144；apps/api/src/test/java/com/riccon/api/quota/QuotaConcurrencyApiTest.java:53-98；apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java:181-205；docs/adr/ADR-0022-m04-quota-and-cost.md:133-145、303-322；.riccon/evidence/TASK-0107/EV-0547-*.log（49 条 quota.exceeded）"
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/builder-quota"
    due: "TASK-0107 closeout 回执"
    title: "高风险任务只记录 codegraph，未记录 Serena 使用或降级理由"
    summary: >-
      AGENTS.md 项目补充要求代码任务默认使用 Serena 做符号定位/引用/重命名，并在任务回执与 tools_used 记录
      使用或降级理由；Verifier 在高风险代码任务中要检查。TASK-0107 tools_used 只有 ["codegraph"]
      （.riccon/tasks/TASK-0107.json:30-32），没有 Serena 或“为何降级”的说明。这不影响代码正确性，但违反
      项目工具纪律，closeout 回执需补齐理由（例如：编辑范围大、以 CodeGraph 影响面分析为主、Serena 未用）。
    evidence: ".riccon/tasks/TASK-0107.json:30-32；AGENTS.md 项目补充“工具使用（Serena / CodeGraph）”"
created_at: "2026-11-21T03:15:12+08:00"
---

# REV-TASK-0107-001：TASK-0107 / M04-B6 独立评审

本评审是任务级独立技术验收，不是 Product 预验收、用户产品验收或 M04 模块评审，也不替代 M04 模块 gate。

## 1. 结论

**pass（任务级）**。write_scope 内的实现、6 条任务验收均有可核对代码与测试；未发现 P0/P1；开放 **F-001（P2，ADR/锁顺序）**、**F-002（P2，审计 nullable）**、**F-003（P2，全量测试分页假设）**，以及 **F-004/F-005（P3，测试与工具纪律）**。

任务级 pass 不等于 M04 模块 gate pass：F-002 若不在 M04 gate 前修复或由 Architect/Product 书面接受 absent 语义，模块级应按 AC-10 的“前后值”要求升级处理；F-001 需要 Architect 修订 ADR-0022 §1.3。TASK-0107 仍保持 `review`，本评审不把它标 done，也不改变 `.riccon/state.json`。

## 2. 评审范围与独立复验

被评审提交：`f9bff57b5b682ab7a41f87224775eb7edd269ee3`（feat: 配额原子准入、读写端点与并发测试）。基线 `d67582cf0577aaf5652ee136b8bc275aa1aa1a63..f9bff57` 只有这一个提交；48 个文件全部落在 TASK-0107 write_scope 内，没有改 `contracts/openapi.yaml`、V11 迁移、B3/B5 或构建配置。

- 评审时 HEAD：`84e78c9a535bcac2602e4a4be061b7d006e4c5b6`（仅 `.riccon/` 的 TASK-0104/0107 状态与证据变更；生产代码与 f9bff57 一致）。
- 工作区：只有 `.riccon/state.json`、`.riccon/tasks/TASK-0106.json`、`.riccon/evidence/TASK-0106/` 与其他 agent 的 review 变更；生产源码、迁移、契约无未提交改动。
- **EV-0547 审计**：`manual: false`、`commit: f9bff57…`、`dirty: false`、`exit_code: 0`；220,251 字节日志的 sha256 `7132fcbb…1f0fae` 与 JSON 记录一致；命令是定向测试（`Quota*Test` + Chat/Research/Conversation 回归），不是全量，符合任务级证据要求。
- **独立复验**：在 HEAD 运行 `./mvnw -q -T1C -pl apps/api -am test -Dtest='QuotaConcurrencyApiTest,QuotaAdminApiTest' -Dsurefire.failIfNoSpecifiedTests=false`，exit 0；surefire 报告 8/8 通过（IT-QUOTA-102 1/1，QuotaAdminApiTest 7/7），0 fail / 0 skip。另运行 `./mvnw -q spotless:check`，exit 0。
- **未独立重跑全量**：本任务按要求只跑定向；模块 gate 的 test/coverage/E2E/a11y/security/perf 留给 M04-V1/V2。

## 3. 验收对账

| #   | 任务验收（TASK-0107.json）                                                                                                                      | 结果 | 证据                                                                                                                                                                                                                                                          |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | V11 `quota_usage` PK 顺序/默认值匹配 upsert、安全 upsert、条件 UPDATE                                                                           | pass | `V11__model_gateway_depth.sql:252-270`（PK 含 `window_start`、`used_count DEFAULT 0`）；`QuotaJdbc.java:82-110`（ODKU `used_count=used_count`，无 `INSERT IGNORE`，条件 `used_count < :limit`）；B6 未改迁移                                                  |
| 2   | 50 并发 1 成功/49 个 429/无 500 + 拒绝审计 REQUIRES_NEW（失败 500）                                                                             | pass | `QuotaConcurrencyApiTest.java:53-98`；独立复跑 1×201/49×429/0≥500；四窗口 used=1；EV 日志中该并发租户恰好 49 条 `quota.exceeded`，无 deadlock；`QuotaAuditService.java:56`；`QuotaAuditFailClosedTest.java:40-63`（审计故障→500、无 run、used 不重复）        |
| 3   | `quotaEnforcement=false` 只计量并注册 `riccon_quota_rejections_total{scope,window}`、`riccon_quota_usage_ratio{scope,window}`（无 tenant 标签） | pass | `QuotaAdmissionService.java:143、155-185`（跳过拒绝仍计数）；`QuotaMetrics.java:47-60、85-118`（仅 scope/window/reason 标签）；`QuotaAdmissionApiTest.java:169-198`（不阻断、不写拒绝审计、gauge/over-limit 指标变化）                                        |
| 4   | 日/月边界                                                                                                                                       | pass | `QuotaWindows.java:27-81`；`QuotaWindowsTest.java:20-70`（11-30 23:59:59.999+08 属 11 月、12-01 00:00+08 同时切日/月、+08:00 格式）                                                                                                                           |
| 5   | 权限/跨租户                                                                                                                                     | pass | `TenantQuotaController.java:46-69`（`quota.read`、租户从主体派生）；`PlatformQuotaController.java:61-110`（`platform.quota.manage`）；`QuotaAdminApiTest.java:31-225`（员工 403、租户管理员只读/平台写 403、平台无用户字段、跨租户/不存在 404、非法请求 400） |
| 6   | 存储故障 → 503 不创建 run                                                                                                                       | pass | `QuotaStoreFailureApiTest.java:43-60`（503 `RICCON-QUOTA-503`、task/run=0、used=0）；`QuotaAdmissionService.java:187-200`；`GlobalExceptionHandler` 日志见 EV                                                                                                 |

**契约对账**：`contracts/openapi.yaml:3090-3225` 的 5 个配额端点、`x-riccon-permission`、400/401/403/404/409/429/503 与实现一致；`QuotaWindowState`/`TenantQuota`/`UserQuota`/`UserQuotaPage`/`PlatformQuotaRow`/`PlatformQuotaPage`/`QuotaUpdateRequest`/`QuotaExceededProblem` 与 `contracts/openapi.yaml:5667-5827` 字段、枚举、required 一致；429 响应使用 `ProblemDetail` 子集（type/title/status/detail/code/traceId + scopeType/window/limit/used/resetsAt），`resetsAt` 为 `+08:00` 的 ISO 8601。B6 没有修改契约，也没有修改 V11；写范围只到 `apps/api/.../quota/**`、`admin/quota/**`、Chat/Research 服务与测试。

**实现要点复核**：

- 准入在 `ChatSubmissionService.prepare`（`ChatSubmissionService.java:156-169`）和 `ResearchTaskService.create`（`ResearchTaskService.java:181-196`）的 run/任务插入之前，且与 run 创建同事务；拒绝时事务内尚无业务写入。
- 幂等：Chat 的 `clientMessageId` 重复在准入前抛 409；Research 的 `Idempotency-Key` 命中在准入前返回既有任务（`QuotaAdmissionApiTest.java:120-144`、`ChatQuotaAdmissionTest.java:45-104`），均不重复扣。
- 条件 UPDATE 失败后读取精确 `used` 再抛 `QuotaExceededException`，异常携带 tenant/actor/scopeId/window/limit/used/resetsAt；Web 边界在事务回滚后写拒绝审计。`QuotaAuditService` 是 `REQUIRES_NEW`，审计失败返回 500 而非 429。
- 50 并发测试使用真实 MySQL、真实 HTTP、真实事务，首次创建 `quota_usage` 行；断言 4 个窗口 `used_count=1` 和 task/run=1，能证明“部分窗口已扣、后窗口拒绝”时整个事务回滚。

## 4. 两项设计决策

### 4.1 租户行锁：必要修正，保留，但必须由 Architect 更新 ADR-0022 §1.3（F-001）

**判断：必要，不是过度串行化。** 证据与推理：

1. `QuotaJdbc.LOCK_TENANT`（`:45-53`）在 `ChatSubmissionService:156`、`ResearchTaskService:181` 的业务行锁之前取得，`QuotaAdmissionService.admit:140` 再幂等确认；所有 run 创建事务同序，避免“先锁会话/幂等行、再等租户锁”的交叉死锁。
2. MySQL `INSERT ... ON DUPLICATE KEY UPDATE` 在 50 并发首次插入同一 PK 时，重复检测共享锁升级排他锁是已知死锁窗口；ADR §1.3 禁止自动重试，并把任何死锁视为 B6 缺陷，因此必须在配额行创建之前串行化。租户锁是比“预置窗口行/专用锁表”更小的修正。
3. 准入本来就会在 `TENANT/DAILY` 热点行上串行到事务提交；租户锁只是把串行点提前，没有增加第二个独立热点。不同租户锁不同行，跨租户不受影响。独立复跑与 EV 都是 1×201/49×429/0 个 ≥500，四窗口 used=1，EV 中无 deadlock。
4. 该锁还有一个正面作用：它在 Research 的幂等查询之前取得，避免“两个同幂等键请求都通过查询→都扣配额→一个插入冲突后返回既有任务且不抛异常，从而多扣一次”的并发路径；目前正确，但没有测试守住（F-004）。

**代价与要求**：事务持 `tenant` 行 X 锁期间，任何带 `tenant_id` 外键的插入（消息、run、文件、任务）都会因 FK 父行 S 锁等待；本次 50 并发只测 run 创建，没有混合负载。ADR §1.3 要求的 `innodb_lock_wait_timeout < API 超时（建议 3s）` 与连接池/事务超时仍没有落地（OPS-M04-014 仍部分开放）。建议 Architect 在 ADR-0022 §1.3 中明确：锁语句、取得时机必须在任何业务行锁之前、实际固定顺序、ODKU 与幂等双重理由、上述 FK 串行化残余风险，以及 B10 的锁等待/混合负载测量；同时把 `QuotaAdmissionService` 类 Javadoc 的顺序从 ADR 写法改成实现顺序（或反向改代码，但必须统一）。不要在未更新 ADR 的情况下把该锁作为“实现细节”静默保留。

### 4.2 审计 `previousLimit`：缺席不是 null，建议改 AuditEvent 接受 null（F-002）

ADR 明确要求首次设置 `previousLimit` 为 JSON null（ADR-0022 §1.5/§3.4、ADR-0021 §7.4），`AuditDetailsPolicy` 也已允许（`AuditDetailsPolicy.java:164-181`），真正阻塞的是 `AuditEvent` 的 `Map.copyOf`（`AuditEvent.java:65`）。B6 选择省略键并在测试中断言 `isMissingNode`（`QuotaAdminApiTest:144、155、160`），还把“显式不限→数字”的非首次变更也变成缺键（同文件:157-160），所以不仅是“首次设置”的表示问题。

**建议优先修复**，而不是接受 absent：

- 将 `AuditEvent` 的 details 拷贝改为允许 null 的不可变拷贝（保留插入顺序更好），补 `AuditEvent.create` + `AuditJdbc` 序列化 null 的测试；这是 audit-domain 的修复，`AuditEvent` 属 TASK-0065 的 `apps/api/.../audit/domain/**` 范围，B6 不能自行改。
- B6 恢复总是写 `previousLimit`（首次/显式不限为 null），把 `QuotaAdminApiTest` 改为 `.isNull()`，并补一条“显式不限→数字”的审计断言。
- 不要用字符串哨兵；`limit=0` 表示显式不限是 B1a F-003（P3 accepted）的既有约定，可以保留，但应在 M10 审计 UI/CSV 中把 0 解释为“不限”，避免误读。
- 如果 Architect/Product 决定接受 absent，必须修订 ADR-0022 §1.5/§3.4、ADR-0021 §7.4 和 PRODUCT AC-10，明确“缺键等价 null”，并补 M10 读取端测试；这是 T4 审计合同变更，不能只在代码注释里宣布。

## 5. Findings

### F-001（P2，open，owner: Architect + Ops/B10）

租户行锁必要且当前 50 并发无死锁，但 ADR-0022 §1.3 未记录该锁与真实顺序；实际顺序是 `TENANT/DAILY → USER/DAILY → TENANT/MONTHLY → USER/MONTHLY`，ADR/Javadoc 写的是 `TENANT/DAILY → TENANT/MONTHLY → USER/DAILY → USER/MONTHLY`。需 Architect 修订 ADR、B6 修 Javadoc，并处理 FK 父行阻塞、`innodb_lock_wait_timeout`、混合负载测量。详见 §4.1。

### F-002（P2，open，owner: Lead → audit-domain + B6 测试）

`AuditEvent` 的 `Map.copyOf` 拒绝 null，导致 `previousLimit` 首次/显式不限→数字时被省略；`QuotaAdminApiTest` 把缺键写成断言。需在 audit-domain 修 `AuditEvent`（或由 Architect/Product 正式接受 absent），B6 更新测试。详见 §4.2。M04 模块 gate 前必须解决；未解决时模块评审应按 AC-10 升级处理。

### F-003（P2，open，owner: builder-quota）

`QuotaAdminApiTest.platformList_hasNoUserFields` 假设新 fixture 租户在 `/admin/quotas?page=1&size=100` 第 1 页；`AdminJdbc.LIST_TENANTS` 按 `created_at ASC` 排序，共享 Testcontainers/热栈累计超过 100 个租户时新租户落在后面，`findTenant` 会假失败。EV/定向复跑未触发；全量模块测试前修复分页查找（用 `totalPages` 取最后一页或循环）。详见 finding 摘要。

### F-004（P3，open，owner: builder-quota → V1/V2）

测试缺口：IT-QUOTA-104 未测在途 run 继续；AC-09 未测调高限额后重试成功；并发同 Idempotency-Key 未测；IT-QUOTA-102 未断言 49 条拒绝审计（EV 日志有 49 条）。当前代码路径正确，但这些是模块 AC/ADR 的关键证据，建议在 V1/V2 或模块 gate 前补齐。

### F-005（P3，open，owner: builder-quota）

高风险任务 `tools_used` 只有 `codegraph`，没有 Serena 使用或降级理由，违反 AGENTS.md 项目补充的工具纪律；closeout 回执需补说明。不影响代码正确性。

## 6. 未验证与剩余风险

- **未跑全量**：本评审没有跑合同 `commands.test`/`commands.coverage`、`pnpm -w test -- --coverage`、E2E、a11y、trivy、k6/PERF-M04-002；这些属于 M04-V1/V2 与模块 gate，不能用本任务定向 EV 替代。
- **未在真实浏览器验证**：B6 无 UI；E2E-M04-05/07 属 B11a/B11b/V1。
- **未验证真实数据库故障注入**：IT-QUOTA-104 使用 Mockito 让 `QuotaJdbc.findEnforcementSwitch` 抛错（`QuotaStoreFailureApiTest.java:27-37`），不是实际 DROP/锁表；503 映射与“不创建 run”已验证，但真实表缺失/连接超时的行为留给 V2/OPS。
- **未验证混合负载**：租户 X 锁对同租户消息/文件/任务 FK 插入的影响未测量；`innodb_lock_wait_timeout` 与 Hikari/事务超时未配置（OPS-M04-014 仍部分开放）。B10 必须在 RUNBOOK/PERF 中给出锁等待与 API p95 证据，否则 ADR §1.3 的“3s”只是纸面要求。
- **未验证 M04 模块指标抓取**：`riccon_quota_rejections_total` 在首次拒绝时才由 Micrometer 注册（`QuotaMetrics.java:85-91`），`riccon_quota_usage_ratio` 是“本实例观察到的最大用量比”（`:47-60`），不是当前全局比例；Prometheus 抓取名/标签与 B10 指标表需在 OPS-M04-02b/V2 实际抓取确认。
- **未验证 per-tenant 锁等待与 Worker 在途 run**：Worker 不调用准入；在途 run 继续依赖既有 Worker 语义，IT-QUOTA-105 只验证调低限额不影响 PENDING run。
- **门禁**：`.riccon/state.json` 在 2026-11-21T03:07:29+08:00 的 M04 gate 为 `fail`，原因包括 TASK-0104/0106/0107 仍为 review、TASK-0105/0108-0116 planned、无 M04 模块 review，以及 TASK-0104/0106 的 baseline..HEAD 把 B6 文件归到其 write_scope 之外。TASK-0107 自己的 `d67582c..f9bff57` 范围是干净的；TASK-0104/0106 的 scope 归因需要 Lead 在 closeout 用 reviewed commits/end_commit 重绑，不能用改 baseline 规避。本任务 review 不改变模块 gate，模块 gate 仍应失败。

## 7. 环境

- 评审时间：2026-11-21T03:15:12+08:00（Asia/Shanghai）。
- HEAD：`84e78c9a535bcac2602e4a4be061b7d006e4c5b6`；被评审代码提交：`f9bff57b5b682ab7a41f87224775eb7edd269ee3`。
- EV-0547：`.riccon/evidence/TASK-0107/EV-0547.json`，日志 `.riccon/evidence/TASK-0107/EV-0547-2026-11-21T02-58-58-478+08-00-mvnw-q-t1c-pl-apps-api-am-test-dtest-quota-test-.log`。
- 独立命令：`./mvnw -q -T1C -pl apps/api -am test -Dtest='QuotaConcurrencyApiTest,QuotaAdminApiTest' -Dsurefire.failIfNoSpecifiedTests=false`（exit 0；8/8 通过，0 skip）；`./mvnw -q spotless:check`（exit 0）。
