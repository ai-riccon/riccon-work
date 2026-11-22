---
review_id: REV-M04-ARCH-005
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-ARCH-004
reviewer:
  agent: riccon/verifier
  session: ses_e0710ec8dffe0Ra5GLrMeeb4VO
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: ["18226e3", "9185ca6", "ffb570e", "7a25a04"]
head_commit: 7a25a044ba5642f2dbf8b1c587a3a9c4b3443551
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "AC-02 整条归 V2，V1 只判 15 条且不引用 V2 证据"
    summary: >-
      §8.4 新增 18 行 AC→V1/V2 映射：V1 15 条（AC-01、AC-03..16），V2 3 条整条（AC-02/17/18）；
      AC-02 的配额段用 `SEC-M04-003b`/`IT-QUOTA-103`，UI 无写入口用 `E2E-M04-07a/07b`；V1 行不含
      `SEC-M04-003b`/`TEN-033`，AC-12 用 `IT-COST-104` + `E2E-M04-06`；§8.4:711 明确 V2 依赖 V1 不产生
      V1 等待 V2 证据的时序。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:665-666,687-711; docs/PRODUCT.md:346"
  - id: F-002
    severity: P2
    status: fixed
    title: "AC-13/14 五态与三条键盘路径已绑定到 B11b/V1"
    summary: >-
      B11b:664 逐条写 AC-13 五态（加载/空/错误/403/成功）+ 375px、AC-14 三条键盘路径；V1:665 同时列
      `E2E-M04-07a` 与 `E2E-M04-07b`；§8.4 AC-13/14 行与 §8.5:729 同步，V1 deps 含 B11b。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:664-665,703-704,729; docs/PRODUCT.md:357-358"
  - id: F-003
    severity: P2
    status: fixed
    title: "V2 在最终提交独立跑全量合同并抽样重跑 8 条 P0"
    summary: >-
      V2:666 明确“在最终提交上独立运行一次全量合同 test/coverage/E2E/a11y/security（与 V1 分开取证）”，
      并抽样重跑 AC-01/03/05/08/09/11/12/16（均为 P0）；§8.5:728 重复该边界。V1/V2 类路径分别为
      `security/m04/**` 与 `security/m04verify/**`，不互相覆盖。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:665-666,710-711,728; docs/PRODUCT.md:438-452"
  - id: F-004
    severity: P2
    status: fixed
    title: "THREAT 新增 B-21 并把 M04 gate 扩为 B-18..B-21"
    summary: >-
      B-21 关闭条件含 `PERF-M04-001`（首字 p95 ≤3s、API p95 ≤500ms、降级成功率 100%）、`PERF-M04-002`、
      `OPS-M04-01`（含 V2 独立重放）与 RPO ≤24h/RTO ≤4h；§8 发布阻断句与 §8.4 模块 acceptance 均改为
      B-18/B-19/B-20/B-21。
    evidence: "docs/security/THREAT-MODEL.md:603,627; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:715"
  - id: F-005
    severity: P2
    status: fixed
    title: "THREAT §10.4 把 PRODUCT §10.3 AC-01..18 写入 M04 gate acceptance"
    summary: >-
      §10.4 明写“M04 gate 的 acceptance 口径 = PRODUCT §10.3 AC-01..18 全部通过；安全 gate 额外要求
      B-18/B-19/B-20/B-21；TM-39..54、7+2+3 指标与 AC-13/14 UI 是补充核对项，不得替代 AC”，并点名
      五态、三条键盘路径与 axe。
    evidence: "docs/security/THREAT-MODEL.md:866"
  - id: F-006
    severity: P2
    status: fixed
    title: "PRODUCT 已无 M17/M04b/m04a/b 字面"
    summary: >-
      PRODUCT:31 改为“DEC-0024=B 否决了此前的拆分方案……契约保持 1.3.0-m04 全量，不做分段”；
      指定目标路径（含 PRODUCT）的禁用 ID grep 零命中。AC 文本与优先级未变（脚本比对 `18226e3^` 与 HEAD）。
    evidence: "docs/PRODUCT.md:31; git show 18226e3 -- docs/PRODUCT.md"
  - id: F-007
    severity: P2
    status: fixed
    title: "ARCH 记录 ARCH-004 fail/OPS-005 pass/待 ARCH-005，T-09 一致"
    summary: >-
      ARCHITECTURE:7-12 与 :20-24 的 Verifier/安全两行均为 pending（待 REV-M04-ARCH-005），Ops 行记
      REV-M04-OPS-005 pass，Lead 行记 ARCH-004 fail（1 P1）；ADR §13 T-09:1093 与之逐项一致。
      OPS 侧后续 REV-M04-OPS-006 的状态同步另见 OPS-M04-058（P3）。
    evidence: "docs/ARCHITECTURE.md:7-12,20-24; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:1093"
  - id: F-008
    severity: P2
    status: fixed
    title: "B10 依赖已补 B3/B4/B5/B6，并给出 ≈6h 估算"
    summary: >-
      §8.1 B10:614 依赖为 B1a/B1b/B2/B3/B4/B5/B6/B7；§8.2:639 显式说明发布/回滚/quota/漂移演练要等
      路由 API、Worker 降级/开关、key 池、配额与成本完成，并给 8h 窗口与 ≈6h 估算。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:614,639,677"
  - id: F-009
    severity: P2
    status: accepted
    title: "EV-0522/0523 覆盖不足：按 doc-only 任务级证据接受"
    summary: >-
      EV-0522/0523 仍只覆盖任务 acceptance 的一部分（AC 行数、无拆分 ID、15 个 B 行、B-18/19/20、
      “17 个实施任务”），未覆盖 AC 文本/优先级/non-goals、每任务 scope/依赖/风险、关键路径、ADR proposed、
      openapi 未改。但这些是 doc-only 任务，据 Lead 说明其在提交前人工核对了完整 diff；本轮又在 HEAD 上独立复验了
      上述全部内容，设计文档随后经 ARCH-004/005 与 OPS-005/006 复核，未发现实质内容风险。
      owner `riccon/lead`，到期 M04 gate；条件：不得把 EV-0522/0523 当作模块 gate 的 DoD 证据，模块 review
      必须绑定最终实现的全量合同 EV。
    evidence: ".riccon/evidence/TASK-0095/EV-0522.json; .riccon/evidence/TASK-0096/EV-0523.json; .riccon/tasks/TASK-0095.json; .riccon/tasks/TASK-0096.json"
  - id: F-010
    severity: P2
    status: fixed
    title: "回滚分组断言 S-02/T-01 已与 §10.3 统一"
    summary: >-
      §13 S-02:1053 与 T-01:1085 均改为“按 (tenant_id, provider_id, credential_type) 每组 1 行；有 ACTIVE
      则保留 ACTIVE，否则保留 RETIRED；不写死总行数”；§10.3:796 与 B1a:650/B10:662 验收一致，全文不再有
      “每组 ACTIVE=1”式断言。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:796,1053,1085,650,662"
  - id: F-011
    severity: P3
    status: fixed
    title: "AC-15 的 SEC-MODEL-002 已在 ADR/PRODUCT 双向映射"
    summary: >-
      ADR §11.1:947 写明 `SEC-MODEL-002` 由 `SEC-M04-001/002/009` + `IT-MODEL-107` 承接；PRODUCT AC-15
      的测试线索已改为 `SEC-M04-001/002/009`、`IT-MODEL-107`。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:947; docs/PRODUCT.md:359"
  - id: F-012
    severity: P3
    status: fixed
    title: "B1a write_scope 已补 Worker 测试资源"
    summary: >-
      B1a:650 的 write_scope 增加 `apps/worker/src/test/resources/**` 与
      `apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java`，与其“两应用配置键/测试
      profile 占位 + Worker 集成测试上下文可启动”验收一致。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:650"
  - id: F-013
    severity: P3
    status: accepted
    title: "EV-0522/0523 recorder=lead 与任务 owner 不一致：按历史记录接受"
    summary: >-
      两项证据的 `agent` 均为 `riccon/lead`，任务 owner 分别为 product/architect；EV note 未注明代录。
      内容已在 HEAD 独立复验且 EV 绑定干净 commit（91c2d44），不影响本轮设计结论。
      owner `riccon/lead`，到期 M04 gate；可在 EV note 补“Lead 代录”或重录，不需重开已 done 任务。
    evidence: ".riccon/evidence/TASK-0095/EV-0522.json: agent; .riccon/evidence/TASK-0096/EV-0523.json: agent"
  - id: F-014
    severity: P2
    status: open
    title: "EV-0524 是 dirty 证据，strict gate 会失败"
    summary: >-
      EV-0524（TASK-0098）记录为 `dirty:true`、commit `9185ca6`、exit 0；在临时 clone 上执行
      `gate check --module M04` 得到 “1 evidence record(s) were recorded from a dirty working tree: EV-0524;
      commit before recording evidence”。内容本身已在 HEAD 复验（AC 文本/优先级未变、无拆分 ID），
      但该记录不能作为 strict 绑定证据。owner `riccon/product` + `riccon/lead`，到期 M04 gate；
      修复：在干净 HEAD 上重跑同一命令并新增/替换 TASK-0098 证据，EV-0524 标 superseded。
    evidence: ".riccon/evidence/TASK-0098/EV-0524.json: dirty; 临时 clone gate check 输出（见第 4 节）"
  - id: F-015
    severity: P2
    status: open
    title: "TASK-0089 baseline/write_scope 会阻止本任务 closeout，且 acceptance 仍写 ARCH-004 pass"
    summary: >-
      TASK-0089 baseline 为 `dd4a6a8`，write_scope 只含 review/`.riccon`；设计修复提交
      18226e3/9185ca6/ffb570e 改了 docs/**，在临时 clone 上 `task update TASK-0089 --status review`
      直接报 “changed files outside write_scope: docs/ACCEPTANCE.md, docs/ARCHITECTURE.md, ...”。
      TASK-0089 acceptance 第 3 条仍写“REV-M04-ARCH-004 与 REV-M04-OPS-005 复核 pass”，与 ARCH-004 实际
      fail 矛盾。owner `riccon/lead`，到期 TASK-0089 closeout；修复：把 TASK-0089 baseline 重置到
      7a25a04（或本评审提交前的干净提交），并把 acceptance 改为“REV-M04-ARCH-005 pass；REV-M04-OPS-005/006
      pass”。
    evidence: ".riccon/tasks/TASK-0089.json: baseline_commit, acceptance; 临时 clone task update 输出（见第 4 节）"
created_at: "2026-11-20T20:44:00+08:00"
notes: >-
  本轮为设计修复复核：只读核对 TASK-0097/0098 对 REV-M04-ARCH-004 F-001..F-013 与 REV-M04-OPS-005
  OPS-M04-042..056 的关闭情况，未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/ACCEPTANCE/源码/测试/
  迁移，未创建任务/模块/决策，未提交 git；只新增本评审文件。verdict=pass 仅表示设计修复通过，不表示
  M04 模块 gate 可运行：F-014（EV-0524 dirty）、F-015（TASK-0089 closeout）与 17 个实施任务/模块 review
  缺失仍会让 gate 失败。ADR-0021/0022 仍 proposed；本评审不代替 Lead/用户/Ops/Product 签字，也不构成
  发布批准。
---

# REV-M04-ARCH-005：TASK-0097/0098 对 ARCH-004 的修复复核

## 1. 结论

**verdict: pass（设计修复）。** REV-M04-ARCH-004 的 P1（AC-02 归属与 V1/V2 时序）已修：AC-02 整条由 V2 判定，V1 只判 15 条（AC-01、AC-03..16），§8.4 有 18 行映射，V1 不引用 V2 owner 的 `SEC-M04-003b`/`TEN-033`。9 项 P2 与 3 项 P3 中，F-002..F-008、F-010..F-012 已修；F-009/F-013 按 doc-only 任务级证据接受。REV-M04-OPS-005 的 6 个 P2 与 5 个 P3 也已在文本上关闭（Ops 侧另有 REV-M04-OPS-006 pass 复核，2 项 P3 open）。

本轮的“pass”不是 M04 模块 gate 通过。新增两项 P2：**F-014**（EV-0524 是 dirty 证据，strict gate 会失败）和 **F-015**（TASK-0089 baseline/write_scope 阻止本任务 closeout，且 acceptance 仍写 ARCH-004 pass）。两项都是证据/状态收尾问题，不改设计结论；必须在 M04 gate 前修复。

## 2. F-001..F-013 逐项结论

### 2.1 F-001（P1）AC-02 归属与时序：fixed

- **映射表**：§8.4:687-711 新增“AC → V1/V2 判定归属（18 条，无重叠、无遗漏）”。脚本统计 18 行：V1 15 行、V2 3 行（AC-02/17/18），AC 编号完整。
- **V1**：§8.3:665 写“**AC-01、AC-03..16（15 条）逐条判定，不声称 AC-02**”；为 AC-02 只提供 `SEC-M04-003a`/`TEN-031`/`IT-MODEL-102` 的模型/key/开关段证据。脚本确认 V1 行不含 `SEC-M04-003b`，也不含 `TEN-033`。
- **V2**：§8.3:666 写“**AC-02 整条**”：模型/key/开关写越权复用 V1 证据 + 独立抽检，配额写越权 `SEC-M04-003b`/`IT-QUOTA-103`，界面无写入口 `E2E-M04-07a/07b` 角色断言，审计 fail-closed `IT-MODEL-102`。§11.1:921 的 `SEC-M04-003b` owner 已是 V2。
- **AC-12**：§8.4:702 为 `IT-COST-104` + `E2E-M04-06` 员工无入口断言；`TEN-033` 仅作“V2 独立补充证据，不参与 V1 判定”。这满足“V1 不引用 V2 owner 证据”的口径。
- **时序**：§8.4:711 明确“V2 依赖 V1 只表示读取 V1 已录证据 + 最终提交全量独立复验，不产生 V1 等待 V2 证据的时序”；AC-02 的配额/UI 段本来就在 B11b/V2 时点之后。V1 deps 含 B11b，可完成 AC-13/14；V2 deps 含 V1，可读 003a/TEN-031/IT-MODEL-102。**无矛盾。**
- **PRODUCT 线索**：PRODUCT:346 已改 `SEC-M04-003a`、`SEC-M04-003b`、`TEN-031`、`IT-MODEL-102`、`E2E-M04-07a/07b`；§10 内已无 `SEC-MODEL-003`。

结论：P1 关闭。

### 2.2 F-002/F-003（P2）AC-13/14 证据与 V2 独立性：fixed

- **AC-13 五态**：B11b:664 写“AC-13 五态（加载/空/错误/403/成功）+ 375px 无横向滚动”；V1:665 同时列 `E2E-M04-07a`（两页）与 `E2E-M04-07b`（四页）；§8.5:729 要求五态、三条键盘路径与 375px 必须在 07b 与 B11b 验收中逐条出现。
- **AC-14 三条键盘路径**：B11b:664 逐条写“路由页调整顺序→保存、配额页修改限额→保存、成本页切换聚合维度”；§8.4 AC-14:704 与 §8.5:729 同步。
- **V2 独立全量**：V2:666 与 §8.5:728 要求“在最终提交上独立运行一次全量合同 test/coverage/E2E/a11y/security，并与 V1 分开取证”；另抽样重跑 AC-01/03/05/08/09/11/12/16。PRODUCT §10.6 显示这 8 条均为 P0。
- **类路径**：V1 `apps/api/src/test/java/com/riccon/api/security/m04/**`，V2 `.../security/m04verify/**`，不互相覆盖。

### 2.3 F-004/F-005（P2）威胁模型 gate：fixed

- THREAT §8:603 的 M04 gate 已改为“**B-18、B-19、B-20、B-21** 与 B-06 续期”；B-21:627 含 `PERF-M04-001`（首字 p95 ≤3s、API p95 ≤500ms、降级成功率 100%）、`PERF-M04-002`、`OPS-M04-01`（含 V2 在一次性 MySQL 8.4.13 独立重放并核对 WARN/工单）与 RPO ≤24h/RTO ≤4h。
- THREAT §10.4:866 明写“M04 gate acceptance = PRODUCT §10.3 AC-01..18；安全 gate = B-18..B-21；TM/指标/UI 是补充核对项，不得替代 AC”。F-004/F-005 均关闭。

### 2.4 F-006..F-008、F-010..F-012：fixed

- **F-006**：PRODUCT:31 已改为“DEC-0024=B 否决了此前的拆分方案……契约保持 1.3.0-m04 全量，不做分段”；指定目标路径的禁用 ID grep 零命中；AC 文本/优先级/non-goals 未变（脚本比对 `18226e3^` 与 HEAD，18 条 AC 文本零差异，优先级一致）。
- **F-007**：ARCHITECTURE:7-12 与 :20-24 已记 REV-M04-ARCH-004 fail（1 P1）、REV-M04-OPS-005 pass（6 P2 open）、Verifier/安全 pending（待 ARCH-005）；ADR §13 T-09:1093 逐项一致。OPS 侧后来有 REV-M04-OPS-006 pass，状态同步问题记在 OPS-M04-058。
- **F-008**：B10:614 依赖为 B1a/B1b/B2/B3/B4/B5/B6/B7；§8.2:639 显式化隐藏依赖并给 ≈6h 估算/8h 窗口（§8.4:677）。
- **F-010**：§13 S-02:1053 与 T-01:1085 与 §10.3:796 统一为“每组 1 行；有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数”；B1a:650/B10:662 验收同步。
- **F-011**：ADR §11.1:947 补 `SEC-MODEL-002` → `SEC-M04-001/002/009` + `IT-MODEL-107` 映射；PRODUCT:359 已同步。
- **F-012**：B1a:650 write_scope 已补 `apps/worker/src/test/resources/**` 与 `WorkerIntegrationTestBase.java`。

### 2.5 F-009/F-013（P2/P3）证据覆盖与归属：accepted

- **F-009**：EV-0522/0523 仍不覆盖任务 acceptance 的全部内容（例如 AC 文本/优先级/non-goals、每任务 scope/依赖/风险、关键路径、ADR proposed、openapi 未改）。但这两项是 doc-only 任务，据 Lead 说明其在提交前人工核对了完整 diff；本轮在 HEAD 上独立复验了上述全部内容，设计文档又经 ARCH-004/005 与 OPS-005/006 复核，**未发现实质内容风险**。按 P2 accepted，owner `riccon/lead`，到期 M04 gate。条件：模块 review 的 `dod_evidence` 不得引用 EV-0522/0523，必须绑定最终实现的全量合同 EV。
- **F-013**：EV-0522/0523 的 recorder 为 lead，任务 owner 为 product/architect；EV note 未注明代录。两项证据绑定干净 commit 91c2d44，内容已独立复验；按 P3 accepted，owner `riccon/lead`，到期 M04 gate。可在 EV note 补说明或重录，不需要重开已 done 任务。

### 2.6 OPS-M04-042..056：文本关闭（Ops 侧另有 REV-M04-OPS-006 pass）

本轮逐项抽查 TASK-0097 的 OPS 修复，结论如下（不代替 Ops 的独立签字）：

| ID          | 状态  | 关键证据                                                                                                    |
| ----------- | ----- | ----------------------------------------------------------------------------------------------------------- |
| OPS-M04-042 | fixed | ADR-0021:796/1053/1085 分组断言统一；无“每组 ACTIVE=1”                                                      |
| OPS-M04-047 | fixed | §11.1:943 `OPS-M04-02a`（Worker 7，B1c/V1）与 :944 `02b`（API 2+3，V2）；:948 不得互相替代；THREAT:626 引用 |
| OPS-M04-048 | fixed | ADR-0022:39/129/357 命名 4 键；ADR-0021:650/662/762 贯通 B1a/B10/preflight                                  |
| OPS-M04-049 | fixed | B10:662 绑定 schema 校验关闭/固定 WARN `RICCON-WORKER-002`/工单；V2:666 与 §10.3:828 独立重放               |
| OPS-M04-050 | fixed | §6.5:472、B10:662、§10.3:828：RPO ≤24h、RTO ≤4h；对账金额差 0.00 CNY、调用量差 ≤0.1%                        |
| OPS-M04-051 | fixed | §8.4:677 B10 ≈6h、8h 窗口、48h 余约 20h                                                                     |
| OPS-M04-052 | fixed | B10:662 真实超限（限额=已用量+1）→ 429/审计/指标 → `PUT false` → 恢复 → `PUT true`                          |
| OPS-M04-053 | fixed | §10.2:760-767 “preflight + 六步（0–6）”；drain 上限 5 分钟 + 在途 run 判据                                  |
| OPS-M04-054 | fixed | B10:662 CI perf nightly + 发布前必跑；manual 仅本地预检                                                     |
| OPS-M04-055 | fixed | ARCH/PRODUCT/ADR 的 07a/07b、108a/108b 对齐（OPS-058 仍要求状态同步）                                       |
| OPS-M04-056 | fixed | ACCEPTANCE:59/61 已写“R-11、M13 F-001 不承接，归 M09/M10”；ADR §13 该行仍 deferred（OPS-058）               |

## 3. 新增 findings

### F-014（P2，open）EV-0524 是 dirty 证据

- `.riccon/evidence/TASK-0098/EV-0524.json` 记录 `dirty: true`、`commit: 9185ca6`、`exit_code: 0`。记录时刻（20:27:47）工作树存在未提交的文档改动（ffb570e 于 20:28:14 才提交，时序上吻合）。
- 在临时 clone 上运行 `gate check --module M04`，strict 绑定明确失败：`1 evidence record(s) were recorded from a dirty working tree: EV-0524; commit before recording evidence`。
- 内容本身没有假绿：EV-0524 的断言（PRODUCT 36 行 AC、无 M17/M04b/m04a/m04b、SEC-M04-003a/003b、IT-MODEL-108a、ACCEPTANCE 两处不承接）在 HEAD 上均成立；AC 文本/优先级未变已由本轮脚本独立复验。因此按 P2 而非 P1。
- 修复：在干净 HEAD 上重跑同一命令，新增 EV 并更新 TASK-0098 evidence（EV-0524 标 superseded）。owner `riccon/product` + `riccon/lead`，到期 M04 gate。

### F-015（P2，open）TASK-0089 closeout 会被 baseline/write_scope 拦住

- TASK-0089 baseline 为 `dd4a6a8`，write_scope 只含 `.riccon/reviews/tasks/M04-*.md` 与 `.riccon/**`；设计修复提交 `18226e3`/`9185ca6`/`ffb570e` 修改了 `docs/ACCEPTANCE.md`、`docs/ARCHITECTURE.md`、`docs/PRODUCT.md`、`docs/adr/**`、`docs/security/THREAT-MODEL.md`。在临时 clone 上 `task update TASK-0089 --status review` 返回：
  `error: task TASK-0089 changed files outside write_scope: docs/ACCEPTANCE.md, docs/ARCHITECTURE.md, docs/PRODUCT.md, docs/adr/ADR-0021-..., docs/adr/ADR-0022-..., docs/adr/README.md, docs/security/THREAT-MODEL.md`。
- TASK-0089 acceptance 第 3 条仍写“REV-M04-ARCH-004 与 REV-M04-OPS-005 复核 pass”，而 ARCH-004 实际是 fail（1 P1），与本轮事实矛盾。
- 修复：Lead 把 TASK-0089 baseline 重置到 `7a25a04`（或本评审提交前的干净提交），并把 acceptance 改为“REV-M04-ARCH-005 pass；REV-M04-OPS-005/006 pass”。owner `riccon/lead`，到期 TASK-0089 closeout。

## 4. 实际执行的验证命令与输出

> 临时 clone 的 `gate check`/`task update` 只用于观察，不触碰工作区；`gate check` 会写 `.riccon/state.json`，因此没有在仓库内执行。

| 命令                                                                                                                                                         | 退出码       | 输出摘要                                                                                                                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                                                                                                         | 0            | `7a25a044ba5642f2dbf8b1c587a3a9c4b3443551`                                                                                                                    |
| `git log --oneline -6`                                                                                                                                       | 0            | `7a25a04` 设计修复任务 done；`ffb570e` 清理残留 ID；`9185ca6` 修复 ARCH-004/OPS-005；`18226e3` Product 收口；`4fa0c16` 单模块 delta 评审；`dd4a6a8` 设计收口  |
| `git status --short`                                                                                                                                         | 0            | 评审前为空                                                                                                                                                    |
| `git show --stat 18226e3 9185ca6 ffb570e 7a25a04`                                                                                                            | 0            | 18226e3：PRODUCT/ACCEPTANCE + `.riccon`；9185ca6：ARCHITECTURE/ADR-0021/0022/adr README/THREAT；ffb570e：ADR-0021 单行；7a25a04：仅 `.riccon` 证据/任务/state |
| `grep -rn "M17\|M04a\|M04b\|1\\.3\\.0-m04a\|1\\.4\\.0-m04b" docs/adr docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md contracts/README.md docs/PRODUCT.md` | 1            | **0 命中**                                                                                                                                                    |
| `grep -rn "B-18a\|B-18b\|B-20a\|B-20b" 同上目标`                                                                                                             | 0            | 仅 ADR-0021:1057 的 S-06“原 B-20a”，属允许的一处                                                                                                              |
| Node 解析 §8.4 映射表                                                                                                                                        | 0            | 18 行；V1 15；V2 3（AC-02/AC-17/AC-18）；V1 行不含 `SEC-M04-003b`/`TEN-033`                                                                                   |
| Node 解析 §8.1/§8.3                                                                                                                                          | 0            | 两张表均 17 行；§8.1 `M04-B*` 15 行；B10 依赖 B1a..B7；V1/V2 类路径不互覆                                                                                     |
| Node 解析 §11.1                                                                                                                                              | 0            | 46 行 / 46 unique；无重复、无缺失；无裸 `OPS-M04-02`                                                                                                          |
| `grep -rho "RICCON_QUOTA_DEFAULT_[A-Z_]*" docs \| sort -u`                                                                                                   | 0            | 4 个唯一键：TENANT_DAILY/TENANT_MONTHLY/USER_DAILY/USER_MONTHLY                                                                                               |
| `grep -rn "RPO ≤24h\|RTO ≤4h\|0.00 CNY\|≤0.1%" docs`                                                                                                         | 0            | ADR-0021:472/662/828/1124；THREAT:627（RPO/RTO）                                                                                                              |
| `node ... contract validate --strict`                                                                                                                        | 0            | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                 |
| `grep "^Status:" docs/adr/ADR-0021* docs/adr/ADR-0022*`                                                                                                      | 0            | 均为 `proposed`                                                                                                                                               |
| `git diff --stat 4fa0c16..HEAD -- contracts/openapi.yaml contracts/README.md`                                                                                | 0            | 空（契约未改）；`contracts/openapi.yaml:4` 仍 `1.3.0-m04`                                                                                                     |
| Node 比较 `18226e3^` 与 HEAD 的 PRODUCT                                                                                                                      | 0            | 18 条 AC 文本零差异；18 行优先级一致                                                                                                                          |
| `git diff --check 4fa0c16..HEAD`                                                                                                                             | 2            | 仅两份 EV 日志“new blank line at EOF”；非合同命令，观察项                                                                                                     |
| 临时 clone：`gate check --module M04 --json`                                                                                                                 | 1            | 4 errors：TASK-0089 in_progress；TASK-0089 文档越界；**EV-0524 dirty**；无 M04 review。前两项/末项是阶段预期，EV-0524 见 F-014                                |
| 临时 clone：`task update TASK-0089 --status review`                                                                                                          | 非 0（报错） | write_scope 错误，文档越界（见 F-015）                                                                                                                        |

**未运行**：`build`、`lint`、`typecheck`、`test`、`coverage`、`e2e`、`perf`、`security`、`a11y`、真实 MySQL 迁移/回滚、浏览器 E2E。原因：这是设计阶段复核；V11 与 17 个实施任务尚不存在，没有可验证的实现/迁移产物。未在仓库内运行 `gate check`（会写 `.riccon/state.json`，超出本任务写入边界）。

## 5. 剩余 P3 观察（不阻断本轮）

| 项                          | owner/到期                                 | 说明                                                                                                                                                                                            |
| --------------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| OPS-M04-057                 | riccon/architect / V1 派单前               | §10.3:779 仍写“建议 `RICCON-WORKER-002`”，而 B10:662、ARCH:317、THREAT:627 已把该码作为固定码；删去“建议”以免演练者输出别的码仍自称合规。来自 REV-M04-OPS-006。                                 |
| OPS-M04-058                 | riccon/lead / M04 gate                     | ADR §13:1130 的 OPS-M04-056 仍 `deferred`（实际已由 18226e3 落地）；ARCH/ADR 未登记 REV-M04-OPS-006；状态表仍写“6 P2 open”。                                                                    |
| PRODUCT AC-05/07 旧线索     | riccon/product / M04 gate                  | PRODUCT:349/351 仍写 `SEC-MODEL-004`/`SEC-MODEL-005`；ADR:946 已映射到 `SEC-M04-007/008`。不影响 AC 文本，但严格 grep 会看到两套 ID。                                                           |
| V1 write_scope 缺 `.riccon` | riccon/lead + riccon/architect / V1 派单前 | V1:665 只有测试类 + e2e 只读，V2:666 含 `.riccon/evidence/**`；若 V1 自己提交证据/任务状态，建议统一补 `.riccon/evidence/**`、`.riccon/tasks/**`、`.riccon/state.json`，避免任务级 scope 摩擦。 |
| AC-06 的 PERF 引用          | riccon/architect / V1 派单前               | §8.4 AC-06 行同时写 `IT-MODEL-105` 与 `PERF-M04-001`；后者 owner 为 B8/V2，V1 deps 不含 B8。建议标注“PERF 为 V2 补充（AC-17）”，或把 B8 加入 V1 deps，避免被读成 V1 必须等 V2。                 |
| §8.3 “≤6 条/任务”           | riccon/architect / 下一版 ADR              | B10 吸收 B13 与 OPS 修复后验收分组约 9 项；标题仍写“≤6 条/任务”。不影响可执行性，但与文本不完全一致。                                                                                           |

## 6. 未验证与残余风险

- 未验证任何 build/test/coverage/E2E/a11y/security/perf；V11 迁移、B10 RUNBOOK/脚本、`metrics-check`、备份恢复/rewrap/quota 演练均未实际执行，本评审不对这些结果作通过性判断。
- 未验证浏览器四页、375px、键盘路径与 axe；属于 B9a/B9b/B11a/B11b 的后续证据。
- M04 仍是 `building`；17 个实施任务与模块 review 尚未创建。F-014/F-015 修复前，模块 gate 不应运行。
- ADR-0021/0022 仍 `proposed`；本评审不修改状态、不标记 TASK-0089 done、不代替 Lead/用户/Ops/Product 签字，也不构成任何发布批准。
- 既有 release blocker：DEC-0003 的 MySQL Connector/J 许可仍是发布前必须处理的项（合同既有，不属本轮设计）。

## 7. 边界声明

- 本轮只新增 `.riccon/reviews/tasks/M04-architect-review-005.md`；未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/ACCEPTANCE/源码/测试/迁移，未创建任务/模块/决策，未提交 git。
- 临时 clone 的 `gate check` 与 `task update` 只用于观察 strict 绑定与 write_scope 行为，clone 已删除；工作区未被写入。
- 本评审基于 `HEAD=7a25a04` 的设计文本与 `18226e3`/`9185ca6`/`ffb570e`/`7a25a04` 四个提交；后续实现变更需重新核对。
