---
review_id: REV-TASK-0107-002
previous_review: REV-TASK-0107-001
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
  - 5e922f5a3bde9ff8dacb03f52e5cc6f01afb97cd
head_commit: 5e922f5a3bde9ff8dacb03f52e5cc6f01afb97cd
evidence: [EV-0547]
reviewed_evidence: [EV-0547]
notes: "父提交 a6d52ee（ADR-0022/ARCHITECTURE 锁序修订）一并核对，见 F-001；它不是 TASK-0107 的代码 write_scope 提交。"
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: "riccon/architect + riccon/ops/B10（B10 残余按 OPS-M04-014 跟踪）"
    due: "已修复；B10 运行验证在 M04 gate 前"
    title: "租户行锁/锁序的 ADR 与 Javadoc 已同步；仅剩 B10 锁等待与混合负载测量"
    summary: >-
      a6d52ee 在 ADR-0022 §1.3 增加修订说明、租户行 X 锁语句与时机、实测锁序（租户行 → TENANT/DAILY →
      USER/DAILY → TENANT/MONTHLY → USER/MONTHLY）、ODKU 死锁理由、幂等相关与残余风险；docs/ARCHITECTURE.md:286
      同步。5e922f5 将 QuotaAdmissionService 类 Javadoc 改为实际顺序（:35-39）。我已核对实现循环仍是
      window 外层、scope 内层（QuotaAdmissionService.java:145-147），与 ADR/ARCHITECTURE/Javadoc 一致；没有
      遗留的旧顺序描述（ADR 仅把旧写法标为“未采用”）。租户锁是必要修正、不是过度串行化的结论保持不变。
      F-001 的文档/顺序部分关闭；B10 的 innodb_lock_wait_timeout、Hikari/事务超时与同租户混合负载测量
      仍未完成，ADR 已明确归 OPS-M04-014，本评审不把它重新算作 B6 代码缺陷，但 M04 gate 仍需 B10 证据。
    evidence: "docs/adr/ADR-0022-m04-quota-and-cost.md:14、99-147；docs/ARCHITECTURE.md:286；apps/api/src/main/java/com/riccon/api/quota/service/QuotaAdmissionService.java:33-40、145-147；git show a6d52ee"
  - id: F-002
    severity: P2
    status: fixed
    owner: "riccon/lead → audit domain; M04-B6 测试"
    due: "已修复"
    title: "AuditEvent 支持 null，quota.limit.changed 始终写 previousLimit；JSON null 与 M10 读回已验证"
    summary: >-
      AuditEvent 把 Map.copyOf 换成 `details == null || details.isEmpty() ? Map.of() :
      Collections.unmodifiableMap(new LinkedHashMap<>(details))`（:64-72）：空 details 仍 Map.of()，非空 details
      保留 null 值、插入顺序、防御性拷贝和不可写；PlatformQuotaService 无条件写 previousLimit（:303-305），
      首次/显式不限→数字为 JSON null，limit=null→0 不变。QuotaAuditEventNullTest 锁定 null 值保留、
      `"previousLimit":null` 序列化、源 map 修改不影响事件、事件 map 不可写；QuotaAdminApiTest 同时断言
      DB 审计行 has("previousLimit") && isNull，并通过 `/api/v1/audit-events?action=quota.limit.changed&targetId=...`
      读回 4 条，确认 M10 读取端能返回 null；非 null 的 previousLimit=5 仍可读。独立在干净 worktree
      `5e922f5` 运行用户给出的 57 tests（Quota*Test、ChatQuotaAdmissionTest、AuditDetailsPolicyTest、
      AuditDetailsReaderTest）→ 57/0 fail/0 error/0 skip；这些覆盖既有非 null 审计与 AuditDetailsPolicy/
      Reader。空 details 分支没有单独测试，但代码路径明确保持 Map.of()。
    evidence: "apps/api/src/main/java/com/riccon/api/audit/domain/AuditEvent.java:64-72；apps/api/src/main/java/com/riccon/api/admin/quota/service/PlatformQuotaService.java:300-310；apps/api/src/test/java/com/riccon/api/quota/QuotaAuditEventNullTest.java:35-90；apps/api/src/test/java/com/riccon/api/admin/quota/QuotaAdminApiTest.java:140-183；独立 57 tests/0 skip 运行（5e922f5 clean worktree）"
  - id: F-003
    severity: P2
    status: fixed
    owner: "riccon/builder-quota"
    due: "已修复"
    title: "平台配额列表测试改为 totalPages 倒序跨页查找，不再假设 fixture 在第 1 页"
    summary: >-
      QuotaAdminApiTest 新增 adminQuotaPage / findTenantAcrossPages（:322-368）：先查第 1 页，未命中则读取
      totalPages 并从最后一页倒序查到第 2 页；始终 size=100（≤ OpenAPI 上限），不依赖第 1 页，也不使用超过
      100 的 size；未命中时抛出带页数的 AssertionError。独立运行 QuotaAdminApiTest 7/7 通过（该测试包含
      平台列表与审计读回）。残余：本次干净库租户数 <100，实际只走了“第 1 页命中”分支；多页循环是代码审查
      通过、尚未被测试数据直接触发的；若共享库/热栈租户数超过 20,000（totalPages>200），page 会超过 OpenAPI
      上限 200，这在实际测试库中不现实但仍是边界。
    evidence: "apps/api/src/test/java/com/riccon/api/admin/quota/QuotaAdminApiTest.java:88-107、312-368；contracts/openapi.yaml Page/PageSize（page ≤200、size ≤100）；独立 QuotaAdminApiTest 7/7"
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder-quota → M04-V1/V2"
    due: "M04 模块 gate 前补齐或书面接受"
    title: "原测试缺口仍在：在途 run 继续、调高限额后重试、并发同幂等键、49 条拒绝审计"
    summary: >-
      5e922f5 只新增审计 null 加固与分页修复，没有覆盖 REV-TASK-0107-001 F-004 的四项：IT-QUOTA-104 仍只测
      新建路径，未测已有 run 在配额存储故障时继续；仍无“超限→调高限额→重试成功→used+1”；仍无并发同
      Idempotency-Key 的“1 task/1 run/used=1”回归；IT-QUOTA-102 仍未断言该租户 49 条 quota.exceeded 审计
      （EV-0547 日志有 49 条，但测试本身不防回归）。这些不阻塞本次任务级 pass，但应在 V1/V2 或模块 gate
      前补齐或书面接受。
    evidence: "apps/api/src/test/java/com/riccon/api/quota/QuotaStoreFailureApiTest.java:18-23、43-60；apps/api/src/test/java/com/riccon/api/quota/QuotaAdmissionApiTest.java；apps/api/src/test/java/com/riccon/api/quota/QuotaConcurrencyApiTest.java:53-98；.riccon/evidence/TASK-0107/EV-0547-*.log（该租户 49 条 quota.exceeded）"
  - id: F-005
    severity: P3
    status: fixed
    owner: "riccon/builder-quota"
    due: "已修复"
    title: "tools_used 已记录 codegraph + Serena 降级理由"
    summary: >-
      .riccon/tasks/TASK-0107.json:32-34 现在是 ["codegraph", "serena:degraded（JDT LS 与并行 Maven 测试
      冲突，未启动；结构/影响面由 CodeGraph 覆盖）"]。这与 AGENTS.md 允许的“未使用 Serena 时说明降级理由”
      一致，记录诚实、可审计；没有把未使用的工具冒充为已使用。该字段不影响代码正确性。
    evidence: ".riccon/tasks/TASK-0107.json:32-34；AGENTS.md 项目补充“工具使用（Serena / CodeGraph）”"
  - id: F-006
    severity: P2
    status: open
    owner: "riccon/lead + riccon/builder-quota"
    due: "TASK-0107 closeout / EV 重录前"
    title: "5e922f5 修改 AuditEvent.java 越过 TASK-0107 write_scope；fix 未进入 end_commit/evidence"
    summary: >-
      TASK-0107 write_scope 只覆盖 quota/、admin/quota/、ChatSubmissionService、ResearchTaskService、对应
      测试与 .riccon/；`apps/api/src/main/java/com/riccon/api/audit/domain/AuditEvent.java` 不在其中
      （该路径属 TASK-0065 的 audit domain scope，且 TASK-0065 已 done）。5e922f5 直接修改了该文件；事前
      F-002 建议的是“新建 fix 任务或重开 audit-domain 任务”，但当前没有新任务/scope 扩展。任务卡
      `end_commit` 仍是 f9bff57，`evidence` 仍是 EV-0547，5e922f5 的审计 null 修复和 57 tests 没有被任务卡/
      EV 覆盖；Lead 计划在干净 HEAD 重录，但重录前必须把 5e922f5 纳入 end_commit/reviewed commits，或为
      AuditEvent 修复建一个 kind: fix 的任务并重绑 scope。功能修复本身正确（见 F-002），这是流程/证据绑定
      问题，不是代码缺陷；strict gate 的 scope 检查可能因此失败。
    evidence: ".riccon/tasks/TASK-0107.json:8-18、39-40；git diff a6d52ee..5e922f5 --name-status（AuditEvent.java）；TASK-0065 write_scope；docs/adr/ADR-0022 §1.3 修订与 REV-TASK-0107-001 F-002"
created_at: "2026-11-21T03:35:13+08:00"
---

# REV-TASK-0107-002：TASK-0107 / M04-B6 复评

本评审是任务级独立技术验收，不是 Product 预验收、用户产品验收或 M04 模块评审，也不替代 M04 模块 gate。

## 1. 结论

**pass（任务级）**。F-002/F-003/F-005 关闭；F-001 的 ADR/Javadoc/锁序部分随父提交 `a6d52ee` 与 `5e922f5` 关闭（B10 的运行时测量仍按 OPS-M04-014 跟踪）；无 P0/P1。剩余 **F-004（P3，测试缺口）** 与新增 **F-006（P2，5e922f5 修改 AuditEvent 越过 task write_scope，且 fix 未被 end_commit/EV 覆盖）**。任务仍保持 `review`，本评审不标 done。

任务级 pass 不改变 M04 模块 gate：F-006 需要 Lead 在 closeout/EV 重录前处理；F-004 的测试缺口和 B10 的锁等待/混合负载测量仍是模块 gate 的剩余项。

## 2. 评审范围与独立复验

被评审代码提交：`f9bff57b5b682ab7a41f87224775eb7edd269ee3`（原实现）+ `5e922f5a3bde9ff8dacb03f52e5cc6f01afb97cd`（fix）。父提交 `a6d52ee234326e1229d6811a7927ead43dc78e61` 是 ADR-0022/ARCHITECTURE 的锁序修订，一并核对（见 F-001）；它不是 TASK-0107 的代码 write_scope 提交。当前 HEAD 为 `5e922f5…`。

- **5e922f5 diff**：6 个文件 — `.riccon/tasks/TASK-0107.json`、`PlatformQuotaService.java`、`AuditEvent.java`、`QuotaAdmissionService.java`（仅 Javadoc）、`QuotaAdminApiTest.java`、新增 `QuotaAuditEventNullTest.java`。除 `AuditEvent.java` 外均在 TASK-0107 write_scope；`AuditEvent` 越界问题见 F-006。没有改 `contracts/openapi.yaml`、V11 迁移、Worker AuditEvent、配额准入/查询语义或构建配置。
- **工作树注意**：主工作树有 B4/B8 的未提交 worker 文件（`apps/worker/.../model/**`、`scripts/real-stack/**`、`e2e/support/fake-model/**` 等）；这些不是 `5e922f5` 的内容，不纳入本次评审。为避免污染，我在 **detached clean worktree at 5e922f5** 运行验证，未使用主工作树的 B4/B8 变更。
- **独立验证命令**（用户给出的定向命令，在 clean worktree 执行）：

  ```
  ./mvnw -q -T1C -pl apps/api -am test \
    -Dtest='Quota*Test,ChatQuotaAdmissionTest,AuditDetailsPolicyTest,AuditDetailsReaderTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
  ```

  结果：**57 tests / 0 failures / 0 errors / 0 skipped**，13 个测试类；surefire 报告逐类通过（QuotaAdmin 7、QuotaAdmission 6、QuotaAuditEventNull 2、QuotaConcurrency 1、QuotaStoreFailure 1、QuotaAuditFailClosed 1、QuotaProperties 8、QuotaEnforcementProperties 3、QuotaWindows 4、QuotaLimitUpdate 8、ChatQuotaAdmission 1、AuditDetailsPolicy 11、AuditDetailsReader 4）。

- **格式**：同一 clean worktree `./mvnw -q spotless:check` exit 0。
- **EV**：TASK-0107 仍只有 EV-0547（f9bff57，修复前）；5e922f5 没有新 EV。用户说明 EV 由 Lead 在干净 HEAD 重录；本评审的 verdict 依据 `5e922f5` + 上述独立命令，不把旧 EV 当作 fix 的证据。F-006 记录该绑定缺口。

## 3. F-002：审计 nullable 关闭证据

**实现**：

- `AuditEvent.java:64-72`：`details == null || details.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details))`。
  - null 值保留（不再 `Map.copyOf` NPE）；`LinkedHashMap` 保留插入顺序；`new LinkedHashMap` 是防御性拷贝；`unmodifiableMap` 禁止写；空/null details 仍是 `Map.of()`。
- `PlatformQuotaService.java:303-305`：`details.put("previousLimit", previous.get(window));` 无条件写入；首次设置和“显式不限（null）→ 数字”都得到 JSON null。`details.put("limit", limit == null ? 0 : limit)` 未改（B1a F-003 的既有约定）。
- `QuotaAuditEventNullTest.java:35-62`：构造 `AuditEvent.create` 带 `previousLimit=null`，断言 `containsKey`、`containsEntry(..., null)`、`ObjectMapper.writeValueAsString` 输出 `"previousLimit":null`；`:67-90` 断言源 map 修改不影响事件、事件 map 不可写。
- `QuotaAdminApiTest.java:140-182`：DB 审计行断言 `previousLimit` 键存在且为 null（首次 daily 5、first monthly 0、monthly 8 的“显式不限→8”），非 null 的 daily 7 previous 5 仍可读；再通过 `/api/v1/audit-events?action=quota.limit.changed&targetId=...&page=1&size=100` 读回 4 条，断言 daily 5 的 details 键存在且 null、daily 7 的 previousLimit=5。M10 读取端（`AuditDetailsReader`/`AuditEventView`）确实返回 null，而不是丢键。

**验证**：

- 独立 clean worktree 57 tests：0 fail / 0 skip；其中 `QuotaAdminApiTest` 7/7 包含上述 DB + M10 读回；`AuditDetailsPolicyTest` 11/11、`AuditDetailsReaderTest` 4/4 覆盖既有非 null 审计与读取策略；`Quota*Test` 的其余用例仍通过。
- 代码审查确认空 details 分支仍是 `Map.of()`；没有改 `AuditDetailsPolicy` 的 null 白名单，也没有改 `limit=null→0`。
- 残余（P3 级，不重新打开 F-002）：`AuditEvent` 公共构造器不再用 `Map.copyOf`，因此不再隐式拒绝 null key；生产写入统一走 `AuditEvent.create`（`AuditDetailsPolicy` 仍拒绝 null/blank key），但直接 `new AuditEvent` 的调用会失去该守卫。建议后续在构造器显式拒绝 null key，或在 audit-domain 测试中锁定。空 details 与 details 顺序没有专门断言；代码路径清晰、风险低。

## 4. F-003：分页测试关闭证据

- `QuotaAdminApiTest.java:93-107` 不再直接用 `page=1` 查找 fixture；`findTenantAcrossPages`（:339-349）先查第 1 页，未命中则读 `totalPages`，从最后一页倒序到第 2 页逐页查找；`adminQuotaPage`（:322-328）固定 `size=100`，符合 OpenAPI PageSize ≤100；未命中抛出带页数的 AssertionError。
- 不再有“fixture 一定在第 1 页”的假设；共享库/热栈超过 100 个租户时，新 fixture 在后续页，会被倒序循环找到。
- 独立运行的 `QuotaAdminApiTest` 7/7 通过（本次干净库租户数 <100，实际走的是第 1 页命中分支；多页循环是代码审查通过，尚未被测试数据直接触发）。
- 残余（低风险）：若 `totalPages > 200`（>20,000 租户），`page` 会超过 OpenAPI 上限 200；实际测试库不现实。若要把多页分支纳入自动化，可在测试里预置 >100 个租户或对 helper 做单元测试。

## 5. F-005：tools_used 关闭证据

`.riccon/tasks/TASK-0107.json:32-34` 现为：

```json
"codegraph",
"serena:degraded（JDT LS 与并行 Maven 测试冲突，未启动；结构/影响面由 CodeGraph 覆盖）"
```

这记录了“未使用 Serena”的降级理由，符合 AGENTS.md“未用时要求说明降级理由”的规则；没有把未使用工具冒充为使用。F-005 关闭。

## 6. F-001：ADR/Javadoc/锁序关闭，B10 残余

- `a6d52ee` 在 `docs/adr/ADR-0022-m04-quota-and-cost.md:14` 增加修订说明；`:99-147` 增加租户行 X 锁语句/时机、实测锁序（租户行 → TENANT/DAILY → USER/DAILY → TENANT/MONTHLY → USER/MONTHLY）、ODKU S→X 死锁理由、幂等相关、锁等待/死锁 503 与残余风险；`:147` 明确 B10/OPS-M04-014 负责 `innodb_lock_wait_timeout`、Hikari/事务超时和同租户混合负载测量。
- `docs/ARCHITECTURE.md:286` 同步了同一锁序与“租户锁只串行化、条件 UPDATE 才是准入判据”。
- `5e922f5` 把 `QuotaAdmissionService.java:35-39` Javadoc 改成实际顺序；代码循环（`:145-147`）与 ADR/ARCHITECTURE/Javadoc 一致；grep 未发现遗留的旧顺序描述（ADR 只把旧写法标为“未采用”）。
- 结论：F-001 的文档/顺序部分关闭；不是过度串行化、租户锁必要的结论保持不变。对应文档修复任务 `TASK-0120`（owner `riccon/architect`，write_scope 为 ADR/ARCHITECTURE）当前仍是 `in_progress`，Lead 需在 M04 gate 前把它关闭；这属于任务状态收尾，不改变“ADR 内容已提交”的事实。B10 的运行时验证仍是 **OPS-M04-014** 的开放项，不能把 ADR 修订当作“已测量生产并发”的证明；M04 gate 需要 B10 证据。

## 7. F-004：仍开放的测试缺口

5e922f5 没有覆盖原 F-004 的四项：

1. `QuotaStoreFailureApiTest` 仍只创建新 run 的失败路径，未测“已有 run 在配额存储故障时继续”。
2. 仍无“首次超限拒绝 → 平台调高限额 → 重试成功 → used+1”的 AC-09 回归。
3. 仍无并发同 `Idempotency-Key` 的 1 task/1 run/used=1 回归；当前租户锁在幂等查询之前保证正确，但没有测试守住该不变量。
4. `QuotaConcurrencyApiTest` 仍只断言状态码与 used_count，未断言该租户 49 条 `quota.exceeded` 审计（EV-0547 日志有 49 条，但测试本身不防回归）。

这些是 P3，不阻塞任务级 pass；建议在 V1/V2 或模块 gate 前补齐或书面接受。

## 8. F-006：5e922f5 的写范围与证据绑定

- TASK-0107 `write_scope`（`.riccon/tasks/TASK-0107.json:8-18`）不包含 `apps/api/src/main/java/com/riccon/api/audit/**`；`AuditEvent.java` 属 TASK-0065 的 `apps/api/src/main/java/com/riccon/api/audit/domain/**`（TASK-0065 已 done）。5e922f5 直接修改了该文件，没有新 fix 任务/scope 扩展。
- TASK-0107 的 `end_commit` 仍是 `f9bff57`，`evidence` 仍是 `EV-0547`；5e922f5 的审计 null 修复与 57 tests 没有被 task card/EV 覆盖。用户说明 EV 由 Lead 在干净 HEAD 重录；重录前必须把 5e922f5 纳入 `end_commit`/`reviewed_commits`，或为 AuditEvent 修复创建 `kind: fix` 任务并重绑 scope。
- 功能上该修改正确且必要（F-002 已独立验证），P2 是流程/证据绑定问题；strict gate 的 scope 检查可能因此标红。Lead 不能在未处理 F-006 前把 TASK-0107 标 done。

## 9. 未验证与剩余风险

- **未跑模块全量**：本评审只跑 57 个定向测试，没有跑 `commands.test`/`commands.coverage`、E2E/a11y、trivy、k6/PERF-M04-002；这些属于 M04-V1/V2/模块 gate。
- **未在真实浏览器验证**：B6 无 UI；E2E-M04-05/07 属 B11a/B11b/V1。
- **B10 锁等待/混合负载**：仍未测量 `innodb_lock_wait_timeout`、Hikari/事务超时、同租户 FK 父行阻塞与 API p95；ADR 已明确归 OPS-M04-014，不能用 50 并发单租户 run 创建替代。
- **F-003 多页分支**：本次干净库 <100 租户，只走了第 1 页命中分支；多页倒序循环是代码审查通过、未被测试数据直接触发；`totalPages > 200` 的边界也未覆盖。
- **F-002 空 details/顺序/null key**：空 details 代码分支保持 Map.of()，但没有专门断言；`AuditEvent` 公共构造器不再隐式拒绝 null key（生产 create 路径仍有 policy 守卫）；审计 CSV/导出对 null 的展示未单独验证。
- **EV 绑定**：EV-0547 是 f9bff57 的修复前定向证据，不能作为 5e922f5 的 fix 证据；Lead 重录前任务 done 不应通过 strict evidence binding。
- **工作树**：主工作树仍有 B4/B8 未提交 worker 文件，未被 5e922f5 修改；本次验证在 detached clean worktree at 5e922f5 完成，未触碰这些文件。
- **M04 gate**：state 在 2026-11-21T03:07:29+08:00 仍为 fail（TASK-0104/0106/0107 review、TASK-0105/0108-0116 planned、无 M04 review、scope 归因）；本次复评不改变 gate，TASK-0107 仍不是 done。F-006 与 F-004 需要 Lead/Verifier 在模块 gate 前处理。

## 10. 环境

- 评审时间：2026-11-21T03:35:13+08:00（Asia/Shanghai）。
- HEAD：`5e922f5a3bde9ff8dacb03f52e5cc6f01afb97cd`；被评审代码提交：`f9bff57` + `5e922f5`；父文档提交：`a6d52ee`。
- 独立验证：detached clean worktree at `5e922f5`；命令见 §2；57/0/0/0；`spotless:check` exit 0。
- 证据：`.riccon/evidence/TASK-0107/EV-0547.json`（修复前）；`5e922f5` 无新 EV，待 Lead 重录。
