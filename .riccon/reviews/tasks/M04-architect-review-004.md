---
review_id: REV-M04-ARCH-004
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-ARCH-003
reviewer:
  agent: riccon/verifier
  session: ses_e074ca196ffe4bC6UPJPgZQXqb
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: [91c2d44, dd4a6a8]
head_commit: dd4a6a8f7b5dba8ec627370524c635be4496ccf8
findings:
  - id: F-001
    severity: P1
    status: open
    title: "AC-02 的配额写越权与界面无写入口证据跨 V1/V2 且 V1 无法在自身时点完成判定"
    summary: >-
      ADR-0021 §8.3 让 V1 判定 AC-01..16，但 AC-02 的“租户管理员尝试修改配额”由
      `SEC-M04-003b` 覆盖，而该 ID 的 owner 是 V2；V2 又依赖 V1（§8.1/§8.2），形成
      V1 必须先判 AC-02、却拿不到 V2 证据的时序矛盾。ADR-0021 全文没有 `AC-02` 字面
      映射，PRODUCT AC-02 的测试线索仍是 M02 旧 ID `SEC-MODEL-003`。AC-02 同时要求
      “界面无写入口”，但 V1 验收只列 `SEC-M04-003a`/`TEN-031`，没有 UI/E2E 证据。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:664-665,890-900,915; docs/PRODUCT.md:346; .riccon/tasks/TASK-0089.json: acceptance"
  - id: F-002
    severity: P2
    status: open
    title: "AC-13/14 的四页加载/错误/键盘/axe 证据未完整绑定到 V1 或 B11b 验收"
    summary: >-
      V1 声称判定 AC-13/14（ADR-0021:664），但验收列出的 E2E 只有 `E2E-M04-07a` 两页；
      四页的 `E2E-M04-07b` 在 B11b/V2。`E2E-M04-07b` 场景只写“空/403/成功”，没有
      PRODUCT AC-13 明确要求的“加载、错误”状态；AC-14 要求的“调整顺序→保存、修改限额→保存、
      切换聚合维度”键盘路径也没有在 07b 场景或 B11b 验收中逐条出现。
    evidence: "docs/PRODUCT.md:357-358; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:663-665,907-908"
  - id: F-003
    severity: P2
    status: open
    title: "V2 的“模块级独立复验”只覆盖 AC-17/18 与配额/成本/perf/回滚，未独立复跑 AC-01..16"
    summary: >-
      V2 的验收写“模块级独立复验（引用 V1 证据但不替代、零 skip）”，但没有定义独立复验
      是否重跑 V1 的 AC-01..16、E2E 或全量测试；实际列举的独立工作只有 AC-17/18、
      `SEC-M04-003b`、`TEN-032/033`、`IT-QUOTA/COST`、`OPS-M04-01` 和 7+2 指标。
      因此它是“后段独立复核”，不是对整模块的第二次独立验收。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:618,665"
  - id: F-004
    severity: P2
    status: open
    title: "THREAT §8 的 M04 gate 只绑定 B-18/B-19/B-20，未覆盖 AC-17 的 PERF-M04-001 与 AC-18 的迁移/回滚"
    summary: >-
      THREAT §8 写“M04 gate 额外要求 B-18、B-19、B-20 与 B-06 续期”。B-19 只列
      `PERF-M04-002`（配额争用），没有 `PERF-M04-001`（首字/API p95）；B-18/B-19/B-20
      均未列 `OPS-M04-01`（V11 迁移/回滚/备份恢复）。B-13 是回滚演练但归 M09，
      B-10 是通用性能阻断但未列入 M04 gate。结果：AC-17/AC-18 虽在 ADR §8.4 acceptance
      和 V2 中，但威胁模型 gate 清单没有可执行的 M04 前置绑定。
    evidence: "docs/security/THREAT-MODEL.md:603,616,619,624-626; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:687; docs/PRODUCT.md:361-362"
  - id: F-005
    severity: P2
    status: open
    title: "THREAT §10.4 的 M04 核对清单只列 TM-39..54 与 7+2 指标，没有把 18 条 AC 纳入 gate"
    summary: >-
      §10.4 要求 M04 verifier 逐条核对 TM-39..TM-54，并核对 Worker 7 指标 + API 2 主 + 3 辅助，
      但没有写“M04 gate 以 PRODUCT §10.3 AC-01..18 为 acceptance”。18 条 AC 目前只出现在
      PRODUCT §10.6、ADR §8.4 和 M04 模块 acceptance 中；威胁模型 gate 清单缺少 AC 口径，
      后续 verifier 可能只按 TM/指标核对而漏掉 AC-13/14 等 UI 项。
    evidence: "docs/security/THREAT-MODEL.md:862-872; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:687; docs/PRODUCT.md:427"
  - id: F-006
    severity: P2
    status: open
    title: "PRODUCT 批准记录仍出现 M17/M04b/m04a/b 字样，与“全文无 M17/M04b 计划引用”的 grep 口径冲突"
    summary: >-
      `docs/PRODUCT.md:31` 写“不创建 M17/M04b；契约保持 1.3.0-m04，不做 m04a/b 分段”。
      这是对 DEC-0024=B 的否定式历史记录，不是计划引用；但 TASK-0095 验收要求“全文无
      M17/M04b 计划引用”，EV-0522 只扫描 §10（`awk '/^## 10\. M04/,/^## 11\./'`），
      没有覆盖该行。严格 grep 口径下它会被误报，需改为“DEC-0024=B 否决拆分”之类不重复
      已撤销 ID 的表述，或在评审中明确列为历史记录例外。
    evidence: "docs/PRODUCT.md:31; .riccon/tasks/TASK-0095.json: acceptance; .riccon/evidence/TASK-0095/EV-0522.json: command"
  - id: F-007
    severity: P2
    status: open
    title: "ARCHITECTURE 评审状态仍把 REV-M04-ARCH-003 / REV-M04-OPS-004 的 pass 用于当前单模块方案，且与 ADR §13 T-09 矛盾"
    summary: >-
      ARCHITECTURE 顶部与 M04 表写 Verifier pass（REV-003）、Ops pass（REV-004）、Lead pass；
      REV-003 与 REV-004 的 head 都是 DEC-0024=B 之前的拆分方案，其中 OPS-004 的
      OPS-M04-022（M04 false、M17 true）和 OPS-M04-035（M04 7 / M17 2）已被单模块改写；
      不能作为当前 18 AC/48h 方案的 pass。工作树已有未提交的 REV-M04-OPS-005 pass，
      但 HEAD/ARCH 尚未记录它。ADR-0021 §13 T-09 反而写“Lead pending；Verifier pass；
      Ops changes-requested；不写 Ops pass”，与 ARCH 当前表格直接矛盾。
    evidence: "docs/ARCHITECTURE.md:8-12,20-24; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:1061; docs/adr/README.md:61"
  - id: F-008
    severity: P2
    status: open
    title: "B10 依赖只列 B1a/B1b/B2/B6/B7，但其发布/回滚/开关演练验收需要 B3/B4/B5"
    summary: >-
      §8.1 给 B10 的依赖是 B1a、B1b、B2、B6、B7；§8.3 的 B10 验收却包含“五段发布、回滚、
      quota false→true 演练、API/Worker 开关漂移检查”。要实际演练路由/降级/key/开关，
      需要 B3（路由 API）、B4（Worker 降级/开关）、B5（key 池）已完成。当前图没有这些边，
      属隐藏跨任务依赖；若 Lead 按表在 B7 后立即派 B10，演练会因实现缺失而无法完成。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:614,661,693"
  - id: F-009
    severity: P2
    status: open
    title: "EV-0522/EV-0523 只覆盖任务验收的一部分，不能单独证明 TASK-0095/0096 的完整 acceptance"
    summary: >-
      EV-0522 只验证 §10 有 36 行 `M04-AC-*`、含 DEC-0024=B、§10 内无 M17；没有验证
      AC 文本/优先级/non-goals 未变，也没有验证 §10 之外无 M17。EV-0523 只验证目标文件
      无 M17/M04a/b 等串、`^| M04-B` 15 行、B-18/B-19/B-20 行和“17 个实施任务”字符串；
      没有验证每任务 write_scope/依赖/风险/≤6 条验收、关键路径、ADR proposed、openapi 未改。
      两项证据均由 `riccon/lead` 录制，而任务 owner 分别是 product/architect。
    evidence: ".riccon/evidence/TASK-0095/EV-0522.json; .riccon/evidence/TASK-0096/EV-0523.json; .riccon/tasks/TASK-0095.json; .riccon/tasks/TASK-0096.json"
  - id: F-010
    severity: P2
    status: open
    title: "OPS-M04-042 仍开放：§13 S-02/T-01 的“每组 ACTIVE=1”与 §10.3 允许 RETIRED-only 组冲突"
    summary: >-
      §10.3 已正确写“按 (tenant_id, provider_id, credential_type) 分组，每组 1 行；组内有
      ACTIVE 则保留 ACTIVE，否则保留 RETIRED；fixture 可跨 provider 为 1 ACTIVE + 1
      RETIRED”。但 §13 S-02 仍写“每组 ACTIVE=1”，T-01 写“分组各自断言 ACTIVE=1”。
      按字面对只有 RETIRED 的 provider 组执行会失败，可能诱导演练者删除 RETIRED 种子。
      这是 OPS-004 留下的 P2，仍未关闭。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:796,1021,1053"
  - id: F-011
    severity: P3
    status: open
    title: "AC-15 的 PRODUCT 线索 SEC-MODEL-002 仍未定义或映射"
    summary: >-
      PRODUCT AC-15 的测试线索沿用 `SEC-MODEL-002`、`SEC-MODEL-006`；ADR-0021 §11.1
      只把 `SEC-MODEL-004/005/006` 映射到 `SEC-M04-007/008/009`，没有定义或映射
      `SEC-MODEL-002`。THREAT I-04-2 也只把它写作验证线索。AC-15 的 M04 证据实际是
      `SEC-M04-001/002/009` + `IT-MODEL-107`，PRODUCT 线索需要 Product/Lead 同步。
    evidence: "docs/PRODUCT.md:359; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:915; docs/security/THREAT-MODEL.md:144"
  - id: F-012
    severity: P3
    status: open
    title: "B1a 验收写“两应用 M04 配置键/测试 profile 占位”，write_scope 只覆盖 API 测试 yml"
    summary: >-
      §8.3 B1a 验收要求“两应用 M04 配置键占位 + Worker 集成测试上下文 DEMO+端点+主密钥可启动”，
      但 write_scope 只列 `apps/api/src/test/resources/application-test.yml`，没有
      `apps/worker/src/test/resources/**`。若 Worker 集成测试需要独立测试 profile，
      B1a 会超出写范围或漏占位；需确认 Worker 测试是否复用主 yml/环境变量。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:649"
  - id: F-013
    severity: P3
    status: open
    title: "EV-0522/EV-0523 的 recorder 是 lead，与任务 owner product/architect 不一致"
    summary: >-
      TASK-0095 owner 是 `riccon/product`，TASK-0096 owner 是 `riccon/architect`；两项证据
      的 `agent` 均为 `riccon/lead`。若 Lead 代录，应在 note 说明；否则证据归属与任务 owner
      不一致，会影响后续追溯谁对验收负责。
    evidence: ".riccon/evidence/TASK-0095/EV-0522.json: agent; .riccon/evidence/TASK-0096/EV-0523.json: agent; .riccon/tasks/TASK-0095.json: owner; .riccon/tasks/TASK-0096.json: owner"
created_at: "2026-11-20T19:42:04+08:00"
notes: >-
  本轮只读复核 DEC-0024=B 后的单模块 48h 设计，未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/
  PRODUCT/源码/测试/迁移，只新建本评审文件；未提交 git。结论为设计评审 fail，不是 M04 模块
  gate 的最终判定：TASK-0089 仍 in_progress，Lead 修复 P1 后可再次复核。未运行 build/test/
  coverage/E2E/perf/a11y/迁移探针；设计阶段无对应源码/迁移，V11 尚不存在。ADR-0021/0022
  仍保持 proposed，本评审不代替 Lead/用户/Ops/Product 签字，也不构成发布批准。
---

# REV-M04-ARCH-004：DEC-0024=B 单模块 48h 方案 delta 复核

## 1. 复核范围与结论

**verdict: fail（设计评审）。** DEC-0024=B 已把 M04 收口为单模块、48h、18 条 AC、17 个实施任务、契约 `1.3.0-m04` 全量；设计主体已经按此改写，但仍有一个 P1：**AC-02 的配额写越权与“界面无写入口”证据被拆到 V1/V2，且 V1 在自身时点无法完成整条 AC-02 判定**。另有 9 项 P2（AC-13/14 证据、V2 独立性、威胁模型 gate、THREAT §10.4、PRODUCT 残留、ARCH 状态、B10 隐藏依赖、EV 覆盖、OPS-042）和 3 项 P3。

本轮只做设计/delta 复核，不运行 build/test/coverage/E2E/perf/a11y；不是 M04 模块 gate 通过，也不代表用户验收。

## 2. 逐项结论

### 2.1 AC 归属：18 条无遗漏，但 AC-02 的证据归属不可执行

- PRODUCT §10.3 仍是 18 条；ADR-0021 §8.3 的 V1 写 `AC-01..16`、V2 写 `AC-17/18`，合计 18 条，无“整条归后续模块”（V1/V2 都属于 M04）。统计见 `docs/PRODUCT.md:345-362`、`docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:664-665,687`。
- **AC-02（P0）**：PRODUCT 明确包含三块——模型路由/key/开关写越权、配额写越权、界面无写入口（`docs/PRODUCT.md:346`）。ADR-0021 全文没有 `AC-02` 字面；V1 只列 `TEN-031`/`SEC-M04-003a`/`IT-MODEL-102`，没有覆盖配额写越权和 UI 无入口；配额写越权的专用测试 `SEC-M04-003b` 在 §11.1 的 owner 是 **V2**（`docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:890-891`），而 V2 依赖 V1（`:617-618`、`:665`）。如果 V1 要“AC-01..16 逐条判定”，它在 V2 之前拿不到 AC-02 的完整证据；如果它跳过，则 AC-02 变成“整条归后续 V2”，违反任务验收要求。**这是 P1。**
- **AC-13/14（P1 优先级，但证据等级为 P2）**：V1 声称 AC-13/14 用四页 E2E + `@a11y`（`:664`），但 V1 的证据清单只写 `E2E-M04-07a` 两页；四页的 `E2E-M04-07b` 在 B11b/V2（`:663,665,907-908`）。V1 的依赖包含 B11b，所以可读取，但验收文本没有把它列入，容易让 V1 只跑两页就判 AC-13/14。另外 PRODUCT AC-13 要求“加载、空、错误、403、成功”五态（`docs/PRODUCT.md:357`），`E2E-M04-07b` 场景只写“空/403/成功”（`docs/adr/ADR-0021...:908`）；AC-14 的具体键盘流程（调整顺序→保存、修改限额→保存、切换聚合维度）也没有逐条写入 07b 或 B11b 验收。**P2。**
- **AC-17/18（P0）**：V2 明确承接 AC-17/18，`PERF-M04-001/002` 与 `OPS-M04-01` 均有 owner/证据（`:618,665,910-912`），无“整条归后续模块”。AC-18 的威胁模型核对映射 THREAT §4.4/§7（`docs/PRODUCT.md:362`; `docs/security/THREAT-MODEL.md:137-155,572`）存在；AC-17 的运维可观察指标由 7+2+3 指标覆盖（`docs/adr/ADR-0021...:838-840`）。
- 其他 AC 的 Product 线索与 ADR 测试 ID 基本可对上：AC-01 `E2E-M04-01/IT-MODEL-101`；AC-03/04 `E2E-M04-02/03`；AC-05 `SEC-M04-007`；AC-06 `IT-MODEL-105/PERF-M04-001`；AC-07 `E2E-M04-04/SEC-M04-008/IT-MODEL-106`；AC-08 `E2E-M04-05/IT-QUOTA-101`；AC-09 `PERF-M04-002/IT-QUOTA-102`；AC-10 `E2E-M04-05/TEN-032(V2)/IT-QUOTA-103`；AC-11 `E2E-M04-06/IT-COST-101`；AC-12 `TEN-033(V2)/E2E-M04-06`；AC-15 `SEC-M04-001/002/009/IT-MODEL-107`；AC-16 `E2E-M04-08/IT-MODEL-108a`。其中 AC-10/12 的 `TEN-032/033` 也是 V2 owner，但 V1 明确写“AC-08..12 复用 B6/B7 集成报告与 B11b E2E-M04-05/06”（`:664`），可由 B6/B7 的 `IT-QUOTA-103`/`IT-COST-104` 覆盖，故不单列为 P1，但仍应在 AC 映射表中写清“V1 用 builder 证据、V2 用独立 TEN 证据”的区别。

### 2.2 任务图：17 个任务、writer 边界与关键路径基本可执行，有两个隐藏依赖

**统计与拆分**

- §8.1 与 §8.3 都是 17 行：B1a/B1b/B1c/B2/B3/B4/B5/B6/B7/B8/B9a/B9b/B10/B11a/B11b/V1/V2（`docs/adr/ADR-0021...:598-620,645-665`；脚本计数见第 3 节）。§8.1 的 `M04-B` 行是 15 行，加 V1/V2 共 17；EV-0523 的 `grep -c '^| M04-B' = 15` 与此一致。
- **B12 并入 B2**：B2 验收同时包含 `IT-MODEL-108a`（三个模型端点 + 路由/凭据/开关 schema）与 `IT-MODEL-108b`（配额/成本 schema，同一 `1.3.0-m04` 全量），并且是 `contracts/openapi.yaml` 与 `contracts/README.md` 的唯一 writer（`docs/adr/ADR-0021...:652,588`；`contracts/README.md:7,126`）。完整。
- **B13 并入 B10**：B10 验收包含 `quota` 开关 false→true 演练（默认限额/租户用量/失败回滚）、回滚、成本对账窗口、漂移检查、CI perf job；write_scope 含 `scripts/ops/quota-*`/`cost-*`、RUNBOOK 与 compose（`docs/adr/ADR-0021...:661,639-643`）。V11 种子 `quotaEnforcementEnabled=false` 与 gate 以 true 取证（`:306,693`）一致。完整。
- **writer 唯一性**：`contracts/**` 只有 B2；`application.yml` 只有 B1a；`worker/model/**` 由 B4 写、B7 依赖 B4 后再改；`routes.ts`/`access.ts` 由 B9a 写、B9b 依赖 B9a 后追加；RUNBOOK 与 `docker-compose.test.yml` 只有 B10。§8.2 已明确“这三组不得并行派单”（`:639-643`）。脚本还发现 B2 的测试 glob `apps/api/src/test/java/com/riccon/api/admin/model/**` 是 B3/B5 子目录的父路径，B4 的 `worker/model/**` 是 B7 文件的父路径；因 B3/B5 依赖 B2、B7 依赖 B4，按依赖串行即可，不构成并行写冲突。**writer 边界设计成立，但依赖表必须严格执行。**
- **关键路径算术**：`B1a 2 + B2 4 + B3 2 + B4 5 + B7 3 + B9b 3 + B11b 2 + V1 3 + V2 4 = 28h`（`:673`），48h/28h ≈ 1.71×；人时 ≈52–56，3 builder × 48h 容量足够。算术可信，余量用于高风险任务评审与证据重录（`:677-683`）。
- **隐藏依赖 1（P2）**：B10 的验收含五段发布/回滚/开关演练，但依赖只列 B1a/B1b/B2/B6/B7，没有 B3/B4/B5；实际演练需要路由 API、Worker 降级/开关与 key 池（`:614,661,693`）。见 F-008。
- **隐藏依赖 2（P1）**：AC-02 的 V1→V2 证据时序；见 F-001。另有较轻的 B2 `IT-MODEL-108a` 若被理解为“运行路由/凭据/开关端点”而非 schema 契约测试，则需要 B3/B5；§11.1 明确写的是 schema（`:876`），故按 schema 理解可通过，但验收文本应避免“契约测试”歧义。
- 17 个实施任务的 `.riccon/tasks/TASK-*.json` 尚未创建（`.riccon/modules/M04.json` 只有 TASK-0089/0095/0096），因此 write_scope/owner 的机器校验要等 Lead 创建任务后重跑；本评审只能核对 ADR 表。

### 2.3 契约与威胁模型：`1.3.0-m04` 全量通过，gate 映射仍不完整

- `contracts/openapi.yaml` 未被 91c2d44 改动；`grep -n "version:"` 首个业务版本是第 4 行 `1.3.0-m04`（其余是 schema 属性）。Ruby 解析结果：`openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`。配额/成本路径全部存在：`/api/v1/quotas`、`/quotas/users`、`/admin/quotas`、`/costs`、`/costs/invocations`、`/admin/costs`（`contracts/openapi.yaml:3090,3110,3133,3225,3255,3298`），且这些路径没有 `x-riccon-deferred`；MCP 两个端点保留并标 `x-riccon-deferred: M07`（`:2772,2794`）。`x-riccon-deferred` 列表不再含 `model-routing-quota`，新增/保留项为 BYO、价格编辑、多币种、账单对账、cost-rollup、workspace-archive 等（`:101-143`）。**通过。**
- 未发现 `M17`/`M04a`/`M04b`/`1.3.0-m04a`/`1.4.0-m04b`/`B-18a`/`B-18b`/`B-20b` 在 ADR/ARCH/THREAT/contracts README 中的计划引用；`B12`/`B13` 也无命中。唯一允许的残留是 ADR-0021:1025 的 S-06“原 B-20a”。`docs/PRODUCT.md:31` 有 M17/M04b/m04a/b 字样（F-006，否定式历史记录）。
- **B-18/B-19/B-20**：B-18 覆盖模型/端点/key/配额写越权与跨租户（`SEC-M04-001/002/003a/003b/004/005/006/007/008/009`、`IT-MODEL-105/106/107`、`TEN-031/032/033`）；B-19 覆盖配额/成本正确性（`IT-QUOTA-102`、`PERF-M04-002`、`IT-COST-101..104`、`TEN-032/033`）；B-20 覆盖 7+2+3 指标与 `metrics-check`（`docs/security/THREAT-MODEL.md:624-626`）。模型越权、配额/成本越权、观测均有关闭条件。**perf/迁移不完整**：AC-17 的 `PERF-M04-001` 不在 B-18/B-19/B-20；AC-18 的 `OPS-M04-01` 也不在；B-13 归 M09、B-10 未列入 M04 gate（F-004）。
- **THREAT §10.4**：M04 清单要求核对 TM-39..TM-54，并明确 7+2+3 指标（`docs/security/THREAT-MODEL.md:864,871`）；7+2 指标已纳入（B-20/TM-54），但 18 条 AC 没有逐条写入 gate 清单（F-005）。威胁模型按 TM 核对是合理的，但任务要求“18 AC 与 7+2 指标都纳入 M04 gate”，当前文本不满足“18 AC”这一半。

### 2.4 独立性：类路径互不覆盖、零 skip 存在，但 V2 不是完整模块级复验

- V1 测试类路径 `apps/api/src/test/java/com/riccon/api/security/m04/**`，V2 是 `.../security/m04verify/**`；两者不互相覆盖（`docs/adr/ADR-0021...:664-665`）。
- 零 skip：B11a/B11b/V1/V2 都写“零 skip”（`:662-665`）；PRODUCT §10.4 还要求 `TEN-031..033` 零 skip、零 xfail（`docs/PRODUCT.md:384`）。满足。
- V2 的独立性 **partial**：它独立覆盖 AC-17/18、`SEC-M04-003b`、`TEN-032/033`、`IT-QUOTA/COST`、`OPS-M04-01` 与 7+2 指标，但不独立复跑 AC-01..16；对 V1 只“引用证据但不替代”（`:665`）。如果“模块级独立复验”要求第二个 verifier 在最终提交上重跑全量合同测试并独立判定 18 AC，当前设计不成立（F-003）。若定义为“后段独立复核 + V1 负责前半”，则应改名并在模块 review 的 `reviewer`/`dod_evidence` 中明确组合关系。

### 2.5 文档一致性：ADR 仍 proposed、18 AC 文本未改、契约未改；有 3 处状态/文本残留

- ADR-0021/0022 的 `Status:` 仍是 `proposed`（`docs/adr/ADR-0021...:3`、`docs/adr/ADR-0022...:3`），`Approved-by` 只绑定 DEC-0024=B 并明确待 REV-004/OPS-005 后才转 accepted（`:6-7`）。`docs/adr/README.md:61` 与 `docs/ARCHITECTURE.md:9,21` 也写“待 REV-004/OPS-005”。**未提前声称 accepted。**
- PRODUCT 18 条 AC 文本、§10.6 优先级（13 P0 / 5 P1）、§10.1 non-goals 均未改：独立脚本比较 `f63c9db^` 与当前文件，36 行 AC 行（18 条 × 10.3/10.6）无差异，18 行优先级无差异，non-goals 段相等。**AC 文本未改。**
- ADR §12 与 DEC-0024=B 一致：不拆、单模块 48h、18 条 AC、17 个任务、契约 `1.3.0-m04`、V11/主密钥+pepper/端点允许列表/准入即计费/默认限额/回滚窗口/单币种/Prometheus/AC-17 perf profile 均列为已批准（`docs/adr/ADR-0021...:945-964`；ADR-0022:350-364）。
- `contracts/openapi.yaml` 未改、版本 `1.3.0-m04`（见 2.3）。**通过。**
- 残留 1：PRODUCT:31 的 M17/M04b/m04a/b 否定式记录（F-006）。残留 2：PRODUCT:359 的 `SEC-MODEL-002` 未映射（F-011）。残留 3：ARCH 顶部/M04 表仍用 REV-003/OPS-004 的 pass，且与 ADR §13 T-09 的“Lead pending/Ops changes-requested”矛盾（F-007）。

### 2.6 可验证性：每条 AC 有测试 ID，UI 证据仍有 3 类缺口

- 18 条 AC 都至少有一个测试线索；V1/V2 的 §11.1 目录有 45 个唯一 ID（脚本计数），覆盖 `IT-MODEL-101..109`（含 108a/108b）、`IT-QUOTA-101..105`、`IT-COST-101..104`、`SEC-M04-001..009`（含 003a/003b）、`TEN-031..033`、`E2E-M04-01..08`（含 07a/07b）、`PERF-M04-001/002`、`OPS-M04-01/02`。**数量与唯一性通过。**
- UI/文案/入口类 AC 的缺口：
  - **AC-02**“界面无写入口”没有绑定到具体 E2E；V1 只有 `SEC-M04-003a`（surefire），`E2E-M04-07a` 的“403”也没有明确“租户管理员只读、无写入口”（F-001）。
  - **AC-12**“员工访问成本/配额入口 → 无入口或 403”没有 E2E 明确断言；`E2E-M04-06` 只写平台/租户视图（`docs/adr/ADR-0021...:906`）；B9b 只写“平台/租户可见性”（`:660`）。
  - **AC-13/14** 的加载/错误态、四页键盘流程与四页 axe 已在 F-002 记录；`E2E-M04-07b` 有“四页 375px/键盘/axe/空/403/成功”，但缺少“加载/错误”和具体键盘操作。
- a11y：B11a/B11b 都要求 `@a11y`（`:662-663`），`E2E-M04-07a/07b` 都写 axe（`:907-908`），PRODUCT §10.4 也要求 axe 无 serious/critical（`docs/PRODUCT.md:385`）。**a11y 路径存在**；只需把它与 AC-13/14 的完整断言绑定。
- AC-17/18 可验证性通过：`PERF-M04-001/002` + `OPS-M04-01` 都有 owner/证据（`:665,910-912`），但威胁模型 gate 绑定不完整（F-004）。

## 3. 实际执行的验证命令与输出摘要

| 命令                                                                                                                                                                                 | 退出码 | 结果摘要                                                                                                                                                                                                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                                                                                                                                 | 0      | `dd4a6a8f7b5dba8ec627370524c635be4496ccf8`                                                                                                                                                                                                    |
| `git log --oneline -3`                                                                                                                                                               | 0      | `dd4a6a8 chore(m04): 设计收口任务 done（EV-0522/0523）`；`91c2d44 docs(m04): ADR/架构/威胁模型收口为单模块 48h（17 任务）`；`f63c9db docs(m04): PRODUCT 预算同步（DEC-0024=B）与模块启动`                                                     |
| `git diff 91c2d44~2..91c2d44 --stat`                                                                                                                                                 | 0      | 11 files changed，264 insertions/203 deletions；含 `.riccon/modules/M04.json`、`.riccon/state.json`、TASK-0095/0096、`contracts/README.md`、ARCHITECTURE、PRODUCT、ADR-0021/0022、adr README、THREAT-MODEL；**不含 `contracts/openapi.yaml`** |
| `grep -n "version:" contracts/openapi.yaml`                                                                                                                                          | 0      | 第 4 行 `version: 1.3.0-m04`；其余为 schema `version` 属性                                                                                                                                                                                    |
| `grep -rn "M17\|M04a\|M04b\|1\.3\.0-m04a\|1\.4\.0-m04b\|B-18a\|B-18b\|B-20a\|B-20b" docs/adr docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md contracts/README.md docs/PRODUCT.md` | 0      | 2 命中：ADR-0021:1025 的 S-06“原 B-20a”（允许）；PRODUCT:31 的否定式 M17/M04b/m04a/b（F-006）                                                                                                                                                 |
| `grep -rn "\bB12\b\|\bB13\b" docs/adr docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md contracts/README.md docs/PRODUCT.md`                                                        | 1      | 无命中；B12/B13 已并入 B2/B10                                                                                                                                                                                                                 |
| Python 解析 ADR §8.1/§8.3                                                                                                                                                            | 0      | §8.1 17 行、§8.3 17 行；§8.1 `M04-B` 15 行 + V1/V2；关键路径算式 2+4+2+5+3+3+2+3+4=28                                                                                                                                                         |
| Python 解析 ADR §11.1                                                                                                                                                                | 0      | 45 个测试 ID，0 个同表重复；含 108a/108b、003a/003b、07a/07b、OPS-M04-01/02                                                                                                                                                                   |
| Python 比较 PRODUCT `f63c9db^` 与当前                                                                                                                                                | 0      | 36 行 AC 行无差异；18 行优先级无差异（13 P0/5 P1）；non-goals 段相等                                                                                                                                                                          |
| `ruby -ryaml` 解析 openapi                                                                                                                                                           | 0      | `openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`；`quota_cost_deferred=[]`；deferred 列表无 `model-routing-quota`                                                                                                                       |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict`                                                                                                       | 0      | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                                                                                                 |
| `git diff --check`                                                                                                                                                                   | 0      | 无空白错误                                                                                                                                                                                                                                    |
| `git status --short`                                                                                                                                                                 | 0      | 仅 `.riccon/state.json`、`.riccon/tasks/TASK-0089.json` 为进入本任务前已有的 dirty 状态；本评审只新增 review 文件                                                                                                                             |

未运行：`build`、`lint`、`typecheck`、`test`、`coverage`、`e2e`、`perf`、`security`、`a11y`、真实 MySQL 迁移探针、浏览器 E2E。原因：这是设计评审，V11 文件尚不存在，17 个实施任务尚未创建；没有可验证的实现/迁移产物。**未运行 `riccon gate check --module M04`**：TASK-0089 是设计评审任务，不是模块 gate；M04 目前只有设计任务、没有模块 review 与实现任务，gate 必然 fail，且该命令会触及 `.ricon/state`，本任务写入边界只允许 review 文件。

## 4. Findings

| ID    | 严重度 | 状态 | 标题                                                                  | 影响/最小修复                                                                                                                                                                                                                |
| ----- | ------ | ---- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 | P1     | open | AC-02 配额写越权与 UI 无写入口证据跨 V1/V2，V1 无法在自身时点完成判定 | V1 加 `SEC-M04-003b`/`IT-QUOTA-103`/`E2E-M04-05` 证据并显式覆盖 UI 无写入口；或把 AC-02 拆成 02a（V1 模型/key/开关）+ 02b（V2 配额）由模块 review 合并；Product/Lead 把 PRODUCT 线索从 `SEC-MODEL-003` 改为 `SEC-M04-003a/b` |
| F-002 | P2     | open | AC-13/14 四页加载/错误/键盘/axe 证据未完整绑定                        | V1 验收加入 `E2E-M04-07b`；07b 场景补“加载/错误”和 AC-14 的三段键盘流程；B11b 验收逐条写 AC-13/14                                                                                                                            |
| F-003 | P2     | open | V2 “模块级独立复验”只覆盖 AC-17/18 与新领域                           | 明确定义 V2 是否重跑 AC-01..16/全量合同测试；若不是，改名“后段独立复核”并在模块 review 说明组合证据                                                                                                                          |
| F-004 | P2     | open | THREAT §8 M04 gate 未覆盖 `PERF-M04-001` 与 `OPS-M04-01`              | 在 B-18/19/20 之外把 B-10 的 PERF-M04-001 与 B-13/OPS-M04-01 纳入 M04 gate，或在 §8 明写 M04 gate 额外要求 PERF/OPS 证据                                                                                                     |
| F-005 | P2     | open | THREAT §10.4 未把 18 条 AC 纳入 M04 gate                              | 加一句“M04 gate = PRODUCT §10.3 AC-01..18 + 7+2+3 指标；TM-39..54 为补充核对”，避免 verifier 只按 TM 核对                                                                                                                    |
| F-006 | P2     | open | PRODUCT:31 仍有 M17/M04b/m04a/b 字样                                  | 改为“DEC-0024=B 否决拆分”或登记为历史记录例外；EV-0522 只扫描 §10，未覆盖该行                                                                                                                                                |
| F-007 | P2     | open | ARCH 评审状态仍使用 REV-003/OPS-004 的 pass，且与 ADR §13 T-09 矛盾   | OPS-005 已落盘但未提交/未进 ARCH；提交后把 ARCH 更新为 REV-004/OPS-005 的真实结论，并更新 T-09 或 ARCH 表格使二者一致                                                                                                        |
| F-008 | P2     | open | B10 依赖缺 B3/B4/B5，发布/回滚/开关演练无法按其依赖完成               | 给 B10 加 B3/B4/B5 依赖，或把 B10 范围收窄为文档/脚本，演练转给 V2/独立任务                                                                                                                                                  |
| F-009 | P2     | open | EV-0522/0523 只覆盖任务 acceptance 的一部分                           | 重新录制能验证 AC/优先级/non-goals、任务 write_scope/依赖/风险/≤6 验收、关键路径、ADR proposed、openapi 未改的证据；或把这些明确标为 reviewer 手工核对                                                                       |
| F-010 | P2     | open | OPS-M04-042 仍开放：§13 S-02/T-01“每组 ACTIVE=1”                      | 统一为“每组 1 行；有 ACTIVE 断 ACTIVE，无 ACTIVE 断 RETIRED；不写死总行数”                                                                                                                                                   |
| F-011 | P3     | open | AC-15 的 `SEC-MODEL-002` 未定义/未映射                                | Product/Lead 换成 `SEC-M04-001/002/009` 或在 §11.1 补定义                                                                                                                                                                    |
| F-012 | P3     | open | B1a 验收“两应用配置/测试 profile”与 write_scope 不一致                | 确认 Worker 测试 profile 路径并加入 B1a scope，或把验收改为“API 测试 yml + Worker 环境变量占位”                                                                                                                              |
| F-013 | P3     | open | EV-0522/0523 recorder 是 lead，任务 owner 是 product/architect        | 在 EV note 说明 Lead 代录，或由任务 owner 重新录制                                                                                                                                                                           |

P0：无。P1：F-001。P2：F-002..F-010。P3：F-011..F-013。P1 未修复前本评审不能 pass；P2/P3 不单独阻断设计，但 F-002..F-005 建议在 B1a/B9a/B11 派单前修掉，否则会在实现阶段放大返工。

## 5. 上一轮评审结论是否仍成立

| 上一轮           | 结论                     | 本轮判断                                                                                                                                                                                                                                                                                                                                                       |
| ---------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| REV-M04-ARCH-003 | pass（8/10 AC 拆分方案） | **不能直接沿用**。该轮的 F-001/F-002/F-005 都是 M04/M17 拆分口径；DEC-0024=B 后单模块 18 AC 需要本轮重新判定。ARCH 仍把它写成 Verifier pass（F-007）                                                                                                                                                                                                           |
| REV-M04-OPS-004  | pass（M04/M17 拆分方案） | **部分仍成立**。OPS-M04-020（回滚分组断言）仍成立；OPS-M04-022（M04 false/M17 true）与 OPS-M04-035（M04 7/M17 2）已被单模块改写；OPS-M04-043/044/040 在当前文本已修；OPS-M04-042 仍开放（F-010）；OPS-M04-039/046 随 M17 消失而失效。OPS-004 不能作为单模块 Ops 签字；工作树已有未提交的 REV-M04-OPS-005 pass（OPS-042 仍 open），需提交并更新 ARCH 后才可引用 |

## 6. 未验证项与残余风险

- 未在真实 MySQL 8.4.13 上验证 V11（文件尚不存在）、V10→V11 升级、回滚分组、主密钥 rewrap、50 并发、首字 p95、成本查询 p95；这些属于 B1a/B6/B7/B8/B10/V2 的后续证据。
- 未在浏览器中验证四页、375 px、键盘、axe；属于 B9a/B9b/B11a/B11b 的后续证据。
- 未运行 `gate check`（原因见第 3 节）；M04 当前仍 `building`，17 个实施任务未创建，模块 review 不存在。
- ADR-0021/0022 仍 `proposed`；本评审不修改状态、不标记 TASK-0089 done、不代替 Lead/用户/Ops/Product 签字，也不构成任何发布批准。
- 整体风险：F-001 若按当前 V1/V2 分工直接派单，V1 很可能在 AC-02 上给出口头 pass 或跳过配额写越权，形成假绿；F-004/F-005 会让 T4 的 perf/迁移证据在威胁模型 gate 中失去锚点；F-007 会让后续会话误以为当前单模块方案已有 Verifier/Ops 签字。
