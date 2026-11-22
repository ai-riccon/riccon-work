---
review_id: REV-M04-OPS-006
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-OPS-005
reviewer:
  agent: riccon/ops
  session: ses_e0710ec8dffd6xHlmNblzBgVNj
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [9185ca6, ffb570e, 7a25a04]
head_commit: 7a25a044ba5642f2dbf8b1c587a3a9c4b3443551
reviewed_evidence: [EV-0525]
findings:
  - id: OPS-M04-042
    severity: P2
    status: fixed
    owner: riccon/architect
    title: "回滚矩阵 S-02/T-01 已与 §10.3 统一为分组断言"
    summary: >-
      §13 S-02:1053 与 T-01:1085 均改为“按 (tenant_id, provider_id, credential_type) 每组 1 行；组内若有 ACTIVE
      则保留 ACTIVE，否则保留 RETIRED；不写死总行数”。全文再无“每组 ACTIVE=1”式断言；§10.3:825 的
      “总行数=2、ACTIVE=1”仅作 fixture 说明，明写“不得把总行数写死为 1”。B1a:650 与 B10:662 验收同步。
    evidence: "ADR-0021:825、:1053、:1085、:1116、:1120；B1a:650；B10:662"
  - id: OPS-M04-047
    severity: P2
    status: fixed
    owner: riccon/architect + riccon/verifier (V2) + riccon/ops (B1c)
    title: "OPS-M04-02 已拆为 02a（Worker 7，B1c/V1）与 02b（API 2+3，V2）"
    summary: >-
      §11.1:943 定义 `OPS-M04-02a`（Worker 7 个 `riccon_*` 指标存在/标签正确/无 tenant 高基数；
      `metrics-check --base-url` 指向 Worker 通过；默认拒绝未回归），owner B1c/V1，证据“metrics 抓取报告”；
      :944 定义 `OPS-M04-02b`（API 配额/成本 2 主 + 3 辅助，V2 记录抓取样本），owner V2，证据同名；
      :948 明写两者不得互相替代。§8.3 V2:666 与 :707（AC-17 行）、:889（运维测试项）、THREAT B-20:626
      全部改为引用 02a/02b；:1087（T-03）与 :1121（本轮响应）记录拆分理由。
    evidence: "ADR-0021:666、:707、:889、:943、:944、:948、:1087、:1121；THREAT-MODEL:626"
  - id: OPS-M04-048
    severity: P2
    status: fixed
    owner: riccon/architect + riccon/builder (B1a) + riccon/ops (B10)
    title: "默认配额 4 键已命名并贯通 ADR-0022、B1a、B10 preflight 与 /actuator/info 漂移检查"
    summary: >-
      ADR-0022 §1.1:39 命名 `RICCON_QUOTA_DEFAULT_TENANT_DAILY`（10,000）/
      `..._TENANT_MONTHLY`（200,000）/`..._USER_DAILY`（1,000）/`..._USER_MONTHLY`（20,000），0/NULL=不限；
      §1.3:129 禁止代码内硬编码第二套默认值，并写“B1a 一次性加齐这些键，B10 preflight 与 `/actuator/info`
      指纹比对”；§8:357 同步。ADR-0021 B1a:650 的配置键清单已含 4 键；B10:662 的 preflight 与漂移检查点名
      `RICCON_QUOTA_DEFAULT_*`；§10.2 步骤 0:762 列入 preflight；§2.1:89 与 §10.5:862 定义
      `info.riccon.quota.tenantDaily/tenantMonthly/userDaily/userMonthly` 四个生效整数键（非密钥），
      S-12:1063 同步。四文件 grep 一致，无遗漏侧。
    evidence: "ADR-0022:39、:129、:357；ADR-0021:89、:650、:662、:762、:862、:1063"
  - id: OPS-M04-049
    severity: P2
    status: fixed
    owner: riccon/architect + riccon/ops (B10) + riccon/verifier (V2)
    title: "B10 回滚已绑定 schema 校验关闭/固定 WARN/工单，V2 改为独立重放"
    summary: >-
      B10:662 验收串起“先 DROP CHECK、收敛 key、分组断言、`RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false`
      启动旧 Worker、固定 WARN `RICCON-WORKER-002`、工单登记、Flyway 属性”；§10.3:779 给出工单内容
      （仅回滚窗口、人工核对 `MAX(version)`、只允许 M04 前最后一个 worker 版本）与风险接受；§10.3:828
      把 `OPS-M04-01` 通过条件写成“V2 在一次性 MySQL 8.4.13 上独立重放回滚并核对固定 WARN 与工单”；
      V2:666 同步为“独立重放并核对 WARN/工单”；ARCHITECTURE:317 与 THREAT B-21:627 一致。
      残余措辞问题见 OPS-M04-057（P3）。
    evidence: "ADR-0021:662、:666、:779、:828；ARCHITECTURE:317；THREAT-MODEL:627"
  - id: OPS-M04-050
    severity: P2
    status: fixed
    owner: riccon/architect + riccon/ops (B10)
    title: "RPO/RTO 与成本对账容差已给数值，restore-drill 可判 pass/fail"
    summary: >-
      §6.5:472 与 B10:662、§10.3:828 统一为“预览环境 RPO ≤24h、RTO ≤4h；客户生产 SOW 可覆盖并写入
      RUNBOOK”；restore-drill 判据补齐为“表数/触发器/一行 credential 可解密/J1 冒烟/`MAX(version)=11`”
      （B10:662）。成本对账容差写为“金额差 0.00 CNY、调用量差 ≤0.1% 且差异需解释”（B10:662）；
      THREAT B-21:627 同步 RPO/RTO 与独立重放。三项均可作为二分判据，不再只有“跑过”。
    evidence: "ADR-0021:472、:662、:828；THREAT-MODEL:627"
  - id: OPS-M04-051
    severity: P3
    status: fixed
    owner: riccon/lead + riccon/ops (B10)
    title: "B10 已给 ≈6h 估算与 8h 窗口，48h 余量约 20h 写明"
    summary: >-
      §8.4:677 写“B10 估算 ≈6h；B7 在 16h 完成后启动、V2 在 24h 前完成；8h 窗口内完成才不拉长 ≈28h
      关键路径，48h 仍有约 20h 余量”；:633 关键路径与 :639 隐藏依赖说明一致；:685 停止条件不变。
      估算仍是估计值，未用实际工时验证（见第 5 节）。
    evidence: "ADR-0021:633、:639、:677、:685、:1125"
  - id: OPS-M04-052
    severity: P2
    status: fixed
    owner: riccon/ops (B10) + riccon/architect
    title: "quota false→true 演练已要求真实超限并断言 429/审计/指标/开关回退"
    summary: >-
      B10:662 改为可执行的五步：“临时把测试租户日限额设为已用量+1 → 验证 429、`quota.exceeded` 审计、
      `riccon_quota_rejections_total` → `PUT false` 只计量不阻断 → 恢复限额 → `PUT true`”；
      覆盖了上轮指出的“只证明计量、不证明执行”的缺口，并含开关双向回退。
    evidence: "ADR-0021:662；§13:1131（OPS-M04-052 响应）"
  - id: OPS-M04-053
    severity: P3
    status: fixed
    owner: riccon/architect + riccon/ops (B10)
    title: "发布顺序改为“preflight + 六步（0–6）”，drain 有 5 分钟上限与在途 run 判据"
    summary: >-
      §10.2 标题:760 与正文步骤 0–6 一致（:762 步骤 0 preflight、:767 步骤 5 drain）；drain 判据写明
      “先停止领取新 run（优雅停机），上限 5 分钟；在途 run 记 `runId` 清单，由新 Worker 按租约到期恢复
      （同 run 不重复准入、attempt 不重复计数）；超时不得强杀，按失败/取消语义结束并核对审计”。
      B10:662 与 ARCHITECTURE:316 同步。
    evidence: "ADR-0021:760、:762、:767、:662、:1127；ARCHITECTURE:316"
  - id: OPS-M04-054
    severity: P3
    status: fixed
    owner: riccon/ops (B10)
    title: "CI perf 改为 nightly + 发布前必跑，manual 仅本地预检"
    summary: >-
      B10:662 写“CI perf job（nightly 定期 + 发布前必跑；manual gate 仅限本地预检，不得替代 CI/nightly）
      + 预算/主机规格记录”；上轮“可长期不跑 perf”的缺口关闭，`docs/RUNBOOK.md` 与 `.github/workflows/ci.yml`
      仍在 B10 write_scope 内，口径可落地。
    evidence: "ADR-0021:662、:1132"
  - id: OPS-M04-055
    severity: P3
    status: fixed
    owner: riccon/lead + riccon/architect + riccon/product
    title: "ARCH 评审表记录 OPS-005 pass/待 ARCH-005；07a/07b 与 108a/108b 全链路对齐"
    summary: >-
      ARCHITECTURE:9/:11/:23 记录“REV-M04-ARCH-004 fail（1 P1，修复中）、REV-M04-OPS-005 pass（6 P2 open，
      已分配）；Verifier 待 REV-M04-ARCH-005”，:13/:25 的 Ops 行保持 OPS-005 pass。§11.1:906/907 定义
      `IT-MODEL-108a`（模型/路由/凭据/开关）、`IT-MODEL-108b`（配额/成本，同一 `1.3.0-m04`，无 deferred）；
      :935/936 定义 `E2E-M04-07a`（路由/key 两页）/`07b`（四页 + 三条键盘路径），:948 说明两者合起来才构成
      AC-13/14 完整证据。PRODUCT:346/:357/:358/:360/:385/:386 已改用 07a/07b 与 108a，`SEC-M04-003a/003b`
      不再写 SEC-MODEL-003。docs/adr/README:61 同步为“OPS-005 pass、ARCH-004 fail 已修复、待 ARCH-005”。
    evidence: "ARCHITECTURE:9、:11、:13、:23、:25；ADR-0021:906、:907、:935、:936、:948；PRODUCT:346、:357、:358、:360、:385、:386；docs/adr/README:61"
  - id: OPS-M04-056
    severity: P3
    status: fixed
    owner: riccon/product + riccon/lead
    title: "ACCEPTANCE 已写“R-11、M13 F-001 不承接，归 M09/M10”"
    summary: >-
      `docs/ACCEPTANCE.md:59` 改为“R-11 不承接，归 M09 专用迁移（DEC-0023）”，:61 改为“R-11、M13 F-001
      不承接，归 M09/M10（R-11→M09 专用迁移；M13 F-001 由 Lead 在 M04 后按 `kind: fix` 建维护模块，
      最迟 M05 gate 前）”，与 ADR-0021:687、ARCHITECTURE:169 一致；由 TASK-0098/`18226e3` 落地。
      ADR §13 该行仍写 `deferred`，属文档同步残留，见 OPS-M04-058。
    evidence: "docs/ACCEPTANCE.md:59、:61；ADR-0021:687；ARCHITECTURE:169；git show 18226e3"
  - id: OPS-M04-043
    severity: P2
    status: fixed
    owner: riccon/ops (B1c) + riccon/architect
    title: "（上轮已 fixed，本轮复核保持）metrics-check 抓取目标为 Worker"
    summary: >-
      B1c:651 与 §10.6:840 仍写 `metrics-check --base-url` 指向 Worker、只断言 7 个；B4/B6/B7 的指标注册
      验收未回退。本轮无新变化，状态保持 fixed。
    evidence: "ADR-0021:651、:654、:656、:657、:840"
  - id: OPS-M04-044
    severity: P2
    status: fixed
    owner: riccon/architect + riccon/verifier (V2)
    title: "（上轮已 fixed，本轮复核保持）V2 覆盖 API 2 主 + 3 辅助"
    summary: >-
      V2:666 仍写 7+2 拆分核对，THREAT B-20:626 逐项列出 2 主 + 3 辅助；拆分到 02a/02b 后归属更清晰。
    evidence: "ADR-0021:666、:943、:944；THREAT-MODEL:626"
  - id: OPS-M04-057
    severity: P3
    status: open
    owner: riccon/architect
    title: "§10.3:779 仍把固定 WARN 码写成“建议”，与 B10 强制码口径差一层"
    summary: >-
      §10.3:779 写“关闭校验时必须输出固定 WARN（建议 `RICCON-WORKER-002 schema validation disabled`）”，
      而 B10:662 验收、ARCHITECTURE:317 与 THREAT B-21:627 都已把 `RICCON-WORKER-002` 作为固定码。
      规范性小节保留“建议”会让演练者输出别的码仍自称合规，V2 的“核对 WARN”没有码可锚定。
      建议删去“建议”二字，写成“必须输出固定码 `RICCON-WORKER-002`，文案固定”。
    evidence: "ADR-0021:779、:662、:828；ARCHITECTURE:317；THREAT-MODEL:627"
  - id: OPS-M04-058
    severity: P3
    status: open
    owner: riccon/lead (+ riccon/architect)
    title: "评审状态与 §13 响应行需同步到 REV-006（OPS-056 行仍 deferred）"
    summary: >-
      (1) ADR-0021 §13:1133 仍把 OPS-M04-056 记为 `deferred`（理由是“ACCEPTANCE.md 不在本任务写入范围”），
      但 TASK-0098/`18226e3` 已完成该修改，应改为 `fixed`；(2) 两份 ADR 的 Review-date 仍写
      “待 REV-M04-ARCH-005”，§13 未登记 REV-M04-OPS-006；(3) ARCHITECTURE:11/:13/:23/:25 仍记
      “6 P2 open”，本评审后应写“REV-M04-OPS-006 pass，OPS 侧 P2 全部 fixed，余 2 项 P3”。
      均为文档状态同步，不影响设计可执行性，由 Lead 在 ARCH-005 前一次性更新。
    evidence: "ADR-0021:1133、:6；ADR-0022:6；ARCHITECTURE:11、:13、:23、:25；docs/ACCEPTANCE.md:59、:61"
notes: >-
  本轮为窄复核：只读核对 TASK-0097/0098 的设计修复是否关闭 OPS-005 的 6 个 P2 与 5 个 P3，未修改 ADR/
  契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/ACCEPTANCE/源码/测试/迁移，未创建任务/模块/决策/证据，未提交 git；
  只新增本评审文件。本文件 **supersedes REV-M04-OPS-005 的 open 状态**（OPS-005 文件本身不改，作为历史记录）：
  OPS-M04-042/047/048/049/050/051/052/053/054/055/056 全部 fixed，新增 057/058 两项 P3。
  ADR-0021/0022 仍 `proposed`；本评审不代替 Lead/用户/Verifier/Architect 签字，也不构成发布批准。
created_at: "2026-11-20T20:52:31+08:00"
---

# REV-M04-OPS-006：TASK-0097 设计修复的窄复核（Ops 视角）

## 1. 复核范围与基线

- 目的：确认 TASK-0097（`9185ca6` + `ffb570e`）与 TASK-0098（`18226e3`）是否真正关闭
  REV-M04-OPS-005 的 6 个 P2（042/047/048/049/050/052）与 5 个 P3（051/053/054/055/056），
  而不是只写进 §13 响应矩阵。
- 基线：`HEAD=7a25a044ba5642f2dbf8b1c587a3a9c4b3443551`；设计修复提交 `9185ca6`，残留 ID 清理 `ffb570e`，
  状态/证据提交 `7a25a04`。评审开始前 `git status --porcelain` 为空（工作区干净）。
- 范围：只读核对文本与证据，不重跑实现测试；M04 的 17 个实施任务尚未创建，V11 迁移、B10 脚本与
  RUNBOOK 均不存在，故不涉及运行期验证。
- 本轮**不**复核 ARCH-004 的 P1（AC-02 归属）是否修好，那是 REV-M04-ARCH-005 的职责；只在
  OPS 影响面（V1/V2 判据、gate 口径）做交叉确认。

## 2. 逐项结论

| ID  | 上轮  | 本轮  | 结论要点                                                                                                      |
| --- | ----- | ----- | ------------------------------------------------------------------------------------------------------------- |
| 042 | open  | fixed | §13 S-02:1053 / T-01:1085 已与 §10.3:825 统一；全文无“每组 ACTIVE=1”断言                                      |
| 043 | fixed | fixed | 保持：`metrics-check` 指向 Worker，B4/B6/B7 指标注册验收未回退                                                |
| 044 | fixed | fixed | 保持：V2 覆盖 API 2 主 + 3 辅助                                                                               |
| 047 | open  | fixed | `OPS-M04-02a`（Worker 7，B1c/V1）/`OPS-M04-02b`（API 2+3，V2）在 §11.1:943/944 定义，V2、AC-17、THREAT 均引用 |
| 048 | open  | fixed | ADR-0022 §1.1 命名 4 键，B1a 配置键、B10 preflight、`/actuator/info` 四键与 S-12 全链路一致                   |
| 049 | open  | fixed | B10 绑定 env + 固定 WARN + 工单；V2 改为“一次性 MySQL 独立重放并核对 WARN/工单”（残余措辞见 057）             |
| 050 | open  | fixed | RPO ≤24h / RTO ≤4h；restore-drill 判据补齐；对账容差 0.00 CNY / ≤0.1%                                         |
| 051 | open  | fixed | §8.4:677 B10 ≈6h、8h 窗口、48h 余约 20h                                                                       |
| 052 | open  | fixed | B10 要求真实超限 → 429/审计/指标 → `PUT false` → 恢复 → `PUT true`                                            |
| 053 | open  | fixed | “preflight + 六步（0–6）”；drain 5 分钟 + 在途 run `runId` 清单 + 不得强杀                                    |
| 054 | open  | fixed | CI perf nightly + 发布前必跑；manual 仅本地预检                                                               |
| 055 | open  | fixed | ARCH 评审表记 OPS-005 pass/待 ARCH-005；07a/07b、108a/108b 全链路对齐                                         |
| 056 | open  | fixed | ACCEPTANCE:59/:61 已写“不承接，归 M09/M10”                                                                    |

### 2.1 OPS-042：回滚分组断言一致性

- §10.3:825 是规范断言：按 `(tenant_id, provider_id, credential_type)` 分组，每组恰好 1 行；组内若有
  ACTIVE 则保留 ACTIVE，否则保留 RETIRED；fixture 可跨 provider 为 1 ACTIVE + 1 RETIRED（总行数=2、
  ACTIVE=1），**不得把总行数写死为 1**。
- §13 S-02:1053 与 T-01:1085 已逐字采用同一措辞；:1116（A4-F-010）与 :1120（OPS-M04-042）记录改法。
- `grep "ACTIVE=1"` 仅剩 §10.3 的 fixture 说明一处，不是断言；全文再无“每组 ACTIVE=1”。
- B1a:650 验收与 B10:662 验收同步为“每组 1 行；有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数”。
  provider B 的 RETIRED-only 组现在可满足。**判 fixed。**

### 2.2 OPS-047：测试 ID 拆分与归属

- §11.1:943 `OPS-M04-02a`：Worker 7 个 `riccon_*` 指标存在/标签正确/无 tenant 高基数；
  `metrics-check --base-url` 指向 Worker 通过；默认拒绝未回归。owner **B1c/V1**，证据“metrics 抓取报告”。
- §11.1:944 `OPS-M04-02b`：API 配额/成本 2 主 + 3 辅助指标存在/标签正确；V2 记录抓取样本。owner **V2**，
  证据“metrics 抓取报告”。
- :948 明确“两者不得互相替代”，并要求每个 ID 只在一个测试方法/用例中定义。
- 引用面：V2:666（7+2 拆分核对）、:707（AC-17 行）、:889（运维测试项）、THREAT B-20:626（分别点名 02a/02b
  与三个辅助指标）。严格证据绑定下已能定位到 ID、owner 与产出物。**判 fixed。**

### 2.3 OPS-048：默认配额配置键

- ADR-0022 §1.1:39 命名四键及批准默认值；§1.3:129 禁止“代码内硬编码第二套默认值”，并写
  “B1a 一次性加齐这些键，B10 preflight 与 `/actuator/info` 指纹比对”；§8:357 复述。
- ADR-0021 B1a:650 的验收列（配置键占位）显式列出四个键；B10:662 的 preflight 与漂移检查均写
  “默认配额键（`RICCON_QUOTA_DEFAULT_*`）”；§10.2 步骤 0:762 列入 preflight；§2.1:89 定义四个生效整数键
  `info.riccon.quota.tenantDaily`/`tenantMonthly`/`userDaily`/`userMonthly`；§10.5:862 说明这是默认配额键的
  漂移检查；S-12:1063 记录增补。
- 五个位置（ADR-0022 §1.1/§1.3、ADR-0021 B1a/B10/§2.1+§10.5）键名逐字一致。B6 不再是“自定义键”，
  客户可按 DEC-0024=B 调整。**判 fixed。**

### 2.4 OPS-049：回滚窗口绑定与 V2 独立性

- B10:662 回滚验收现为完整链：先 DROP CHECK → 收敛 key → 分组断言 → `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false`
  启动旧 Worker → 固定 WARN `RICCON-WORKER-002` → 工单登记 → Flyway 属性。
- §10.3:779 给出工单内容（“仅回滚窗口、人工核对 `MAX(version)`、只允许启动 M04 前最后一个 worker 版本”
  与风险接受）；§10.3:828 把 `OPS-M04-01` 通过条件写成“V2 在一次性 MySQL 8.4.13 上独立重放回滚并核对
  固定 WARN 与工单”，V2:666 同步；ARCHITECTURE:317 与 THREAT B-21:627 一致。
- 上轮“可能只复核 B10 日志”的歧义消失，`OPS-M04-01` 现在是可判定的独立重放。**判 fixed**；
  §10.3:779 仍带“建议”二字的措辞问题另记 OPS-M04-057（P3）。

### 2.5 OPS-050：RPO/RTO 与对账容差

- §6.5:472、B10:662、§10.3:828 三处一致：预览环境默认 **RPO ≤24h、RTO ≤4h**；客户生产 SOW 可覆盖，
  RUNBOOK 必须写实际值与恢复判据。THREAT B-21:627 同步。
- restore-drill 通过判据补齐为“表数/触发器/一行 credential 可解密/J1 冒烟/`MAX(version)=11`”（B10:662）。
- 成本对账容差：“金额差 0.00 CNY、调用量差 ≤0.1% 且差异需解释”（B10:662），窗口沿用 ADR-0022 §2.4
  的混合版本不可对账口径。三项均有二分数值，演练可以判 pass/fail。**判 fixed。**

### 2.6 OPS-051：B10 估算与 8h 窗口

- §8.4:677：“B10 估算 ≈6h；B7 在 16h 完成后启动、V2 在 24h 前完成；8h 窗口内完成才不拉长 ≈28h
  关键路径，48h 仍有约 20h 余量”；:633 关键路径、:639 隐藏依赖（B3/B4/B5/B6/B7）与 §8.1:614 依赖列一致；
  :685 停止条件（48h 未达 gate 暂停、96h 询问用户）未松动。
- 仍是估算而非实测；B6 的完成时刻未在 §8.4 显式列出（B6 不在关键路径上，作为排期观察项记入第 5 节）。
  **判 fixed。**

### 2.7 OPS-052：quota 真实超限演练

- B10:662 现在写明五步：临时把测试租户日限额设为“已用量+1” → 验证 **429**、`quota.exceeded` 审计、
  `riccon_quota_rejections_total` → `PUT false` 只计量不阻断 → 恢复限额 → `PUT true`。
- 覆盖“执行”而非仅“计量”，并包含开关双向回退；与 §10.2:769（步骤 6 冒烟观测、默认
  `quotaEnforcementEnabled=false`）不冲突，演练后 gate 才以 true 取证。**判 fixed。**

### 2.8 OPS-053：发布步骤与 drain 判据

- §10.2 标题:760 改为“preflight + 六步（0–6）”，正文正好 0–6 共 7 个条目（:762 步骤 0 preflight，
  :763–:768 步骤 1–6），标题与正文不再错位。
- 步骤 5:767 的 drain 判据：先停止领取新 run（优雅停机），上限 **5 分钟**；在途 run 记 `runId` 清单，
  由新 Worker 按租约到期恢复（同 run 不重复准入、attempt 不重复计数）；超时**不得强杀**，按失败/取消
  语义结束并核对审计。B10:662 与 ARCHITECTURE:316 同步。**判 fixed。**

### 2.9 OPS-054：CI perf 门禁

- B10:662：“CI perf job（nightly 定期 + 发布前必跑；manual gate 仅限本地预检，不得替代 CI/nightly）
  - 预算/主机规格记录”。跳过条件不再是开放的“或 manual”，manual 被降级为本地预检。
    `.github/workflows/ci.yml` 与 `docs/RUNBOOK.md` 都在 B10 write_scope，可落地。**判 fixed。**

### 2.10 OPS-055：ARCH 评审表与测试 ID 映射

- ARCHITECTURE:9/:11/:23 现记“REV-M04-ARCH-004 fail（1 P1，修复中）、REV-M04-OPS-005 pass
  （6 P2 open，已分配）；Verifier 待 REV-M04-ARCH-005”，:13/:25 Ops 行保持 OPS-005 pass；
  docs/adr/README:61 同步。
- §11.1:906/907 定义 `IT-MODEL-108a`/`108b`（B2 一次交付，同一 `1.3.0-m04` 全量、无 deferred 分段）；
  :935/936 定义 `E2E-M04-07a`（路由/key 两页五态、375px、键盘、axe、无写入口）/`07b`（四页五态 + 三条
  键盘路径 + audit 7 天预设），:948 说明两者合起来才构成 AC-13/14 证据。
- PRODUCT:346/:357/:358/:360/:385/:386 已改用 07a/07b、108a，AC-02 线索改 `SEC-M04-003a/003b`。
  **判 fixed**（ARCH 行内容仅需在本轮后追加 REV-006，见 OPS-M04-058）。

### 2.11 OPS-056：ACCEPTANCE 范围冲突

- `docs/ACCEPTANCE.md:59`：“R-11 不承接，归 M09 专用迁移（DEC-0023）”；:61：“R-11、M13 F-001 不承接，
  归 M09/M10（M13 F-001 由 Lead 在 M04 后按 `kind: fix` 建维护模块，最迟 M05 gate 前）”。
- 与 ADR-0021:687（M04 acceptance 明写不承接）和 ARCHITECTURE:169 一致；由 TASK-0098（`18226e3`）落地，
  EV-0524 覆盖。**判 fixed**。ADR §13:1133 仍写 `deferred`，属同步残留 → OPS-M04-058（P3）。

## 3. 证据命令与输出摘要

| 命令                                                                                                                            | 退出码 | 输出摘要                                                                                                                                                                        |
| ------------------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                                                                            | 0      | `7a25a044ba5642f2dbf8b1c587a3a9c4b3443551`                                                                                                                                      |
| `git log --oneline -5`                                                                                                          | 0      | `7a25a04` 设计修复任务 done（EV-0524/0525）；`ffb570e` 清理 ADR 响应矩阵残留拆分 ID；`9185ca6` 修复 ARCH-004/OPS-005 findings；`18226e3` TASK-0098；`4fa0c16` 单模块 delta 评审 |
| `git status --porcelain`                                                                                                        | 0      | 评审前为空（工作区干净）；本轮只新增本评审文件                                                                                                                                  |
| `grep -rn "RICCON_QUOTA_DEFAULT" docs/adr docs/ARCHITECTURE.md`                                                                 | 0      | 命中 ADR-0021:650/:662/:762（B1a/B10/preflight）与 ADR-0022:39/:129/:357，键名四处逐字一致                                                                                      |
| `grep -n "RPO ≤24h\|RTO ≤4h\|0.00 CNY\|≤0.1%\|RICCON-WORKER-002\|5 分钟" docs/adr/ADR-0021*`                                    | 0      | RPO/RTO 命中 :472/:662/:828；`RICCON-WORKER-002` 命中 :662/:779；`≤0.1%`/`0.00 CNY` 命中 :662；`5 分钟` 命中 :662/:767                                                          |
| `grep -n "OPS-M04-02a\|OPS-M04-02b" docs/adr/ADR-0021* docs/security/THREAT-MODEL.md`                                           | 0      | ADR-0021:666/:707/:889/:943/:944/:948/:1087/:1121；THREAT:626                                                                                                                   |
| `grep -rn "M17\|B12\|B13\|B-18a\|B-18b\|B-20b" docs/adr docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md contracts/README.md` | 1      | 0 命中                                                                                                                                                                          |
| `grep -rn "B-20a" 同上目标`                                                                                                     | 0      | 仅 ADR-0021:1057 的 S-06“原 B-20a”，属任务允许的一处                                                                                                                            |
| `docker compose -f deploy/compose/docker-compose.test.yml config -q`                                                            | 0      | 编排可解析，Docker 可用，无降级                                                                                                                                                 |
| `ruby -ryaml -e 'YAML.load_file("contracts/openapi.yaml")...'`                                                                  | 0      | `ok openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`                                                                                                                       |
| `git diff 9185ca6^..9185ca6 --stat`                                                                                             | 0      | 只改 ARCHITECTURE、ADR-0021/0022、docs/adr/README、THREAT-MODEL（5 文件）；**无契约、无源码、无测试**                                                                           |
| `git diff ffb570e^..ffb570e --stat`                                                                                             | 0      | ADR-0021 单行（残留 ID 清理）                                                                                                                                                   |
| `git diff 7a25a04^..7a25a04 --stat`                                                                                             | 0      | 只有 `.riccon/evidence`（EV-0524/0525 + 日志）、`state.json`、TASK-0089/0097/0098                                                                                               |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict`                                                  | 0      | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                                   |
| `cat .riccon/evidence/TASK-0097/EV-0525.json`                                                                                   | 0      | `manual:false`、`dirty:false`、`exit_code:0`、commit `ffb570e`、output `DESIGN-FIX-OK`；校验 15 个 M04-B 行、B-21、四配额键、RPO/RTO、ADR proposed、无残留 ID                   |

**未运行（窄复核约束）：** `./mvnw verify`、`pnpm -w *`、k6、E2E、a11y、真实 V11 迁移、`metrics-check`、
`scripts/ops/*`、备份恢复/rewrap/quota 演练。本轮不对构建、覆盖率、p95、E2E、迁移执行结果作通过性判断。

## 4. Findings

### P0/P1

无。未发现新的 P0/P1；`verdict=pass`。

### P2

无开放 P2。OPS-042/047/048/049/050/052 全部 fixed（见第 2 节），043/044 保持 fixed。

### P3

| ID          | 状态  | 摘要                                                                                            | owner             |
| ----------- | ----- | ----------------------------------------------------------------------------------------------- | ----------------- |
| OPS-M04-051 | fixed | B10 ≈6h/8h 窗口/48h 余量已写明                                                                  | lead + ops        |
| OPS-M04-053 | fixed | “preflight + 六步（0–6）”；drain 5 分钟与在途 run 判据                                          | architect + ops   |
| OPS-M04-054 | fixed | CI perf nightly + 发布前必跑；manual 仅本地                                                     | ops               |
| OPS-M04-055 | fixed | ARCH 评审表 + 07a/07b/108a/108b 对齐                                                            | lead/arch/product |
| OPS-M04-056 | fixed | ACCEPTANCE“不承接，归 M09/M10”                                                                  | product + lead    |
| OPS-M04-057 | open  | §10.3:779 仍写“固定 WARN（建议 `RICCON-WORKER-002 ...`）”，应去掉“建议”改为强制码               | architect         |
| OPS-M04-058 | open  | §13:1133 OPS-056 行仍 `deferred`；两份 ADR Review-date 未登记 REV-006；ARCH 状态行仍“6 P2 open” | lead              |

到期建议：OPS-M04-057 在 B10 启动前（`docs/RUNBOOK.md` 首次写入固定 WARN 前）修正；OPS-M04-058 在
REV-M04-ARCH-005 出具前一并更新（同一次文档提交即可），不影响 M04 排期。

## 5. 未验证项与残余风险

**未验证：**

- 设计修复本身的可执行性：V11 迁移尚未创建，RPO/RTO、restore-drill、rewrap、quota 真实超限、成本对账
  全部未实际演练；本轮只确认“验收文本可判 pass/fail”。
- `metrics-check`、`/actuator/info` 指纹键、`scripts/ops/*`、CI perf job 均不存在，未运行；B10 实施后
  需重录 `OPS-M04-01/02a/02b` 证据。
- 4 个 `RICCON_QUOTA_DEFAULT_*` 键只存在于 ADR/验收文本，`apps/api`、`apps/worker` 的 `application.yml`
  尚无对应占位（B1a 实施项），未做两侧一致性运行验证。
- 48h 排期为估算；B10 的 ≈6h 无人时实测。B6 的完成时刻未在 §8.4 显式列出（仅 B7 的 16h 与 V2 的 24h
  被引用），若 B6 晚于 18h，B10 的 8h 窗口会收窄——受 48h 的约 20h 余量保护，但需 Lead 在派单时观察。
- `.riccon/modules/M04.json` 仍只有 TASK-0089/0095/0096/0097/0098 五个设计任务，17 个实施任务未创建；
  这是 Lead 的派单前置，不是本轮设计 fail。
- EV-0525 是文档一致性断言（15 个 M04-B 行、B-21、四键、RPO/RTO、proposed、无残留 ID），不验证运行行为。

**残余风险：**

- OPS-M04-057（WARN 措辞）若带入 RUNBOOK，回滚演练可能记一个非固定码，V2 的“核对 WARN”无法逐字比对，
  回滚证据的可审计性下降；属 P3，B10 前修正即可。
- B10 是 V2 的 join 前置且承担 10 个 write_scope、8 类脚本、5 类演练；≈6h 估算偏紧时，28h 关键路径会
  拉长，但 48h 预算仍有约 20h 余量。
- 默认配额键已命名，但若 B6 实施时漏读配置（回退硬编码），漂移检查会暴露但发布时才发现；建议 B6 的
  集成测试直接断言“无显式行 + 配置缺省 → DEPLOYMENT_DEFAULT 取 10,000”，属 B6 验收细化，不单列 finding。
- ADR-0021/0022 仍 `proposed`；本评审 pass 不等于 ADR accepted，也不等于用户批准发布。DEC-0003 的
  MySQL Connector/J 许可仍是 release blocker（合同既有）。
- `deploy_preview`/`deploy_prod` 指向 M09 交付物尚不存在，M04 不以它们取证（§10.2:769 一带），评审包需标
  `deploy_preview not-applicable`。

## 6. 边界声明与历史关系

- 本文件 **supersedes REV-M04-OPS-005 的 open 状态**：OPS-M04-042/047/048/049/050/051/052/053/054/055/056
  全部转为 fixed，新增 OPS-M04-057/058 两项 P3。OPS-005 文件保持原样，作为历史记录与上一轮证据链。
- 本轮只新增 `.riccon/reviews/tasks/M04-ops-review-006.md`；未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/
  PRODUCT/ACCEPTANCE/源码/测试/迁移，未创建任务/模块/决策/证据，未提交 git，未标记 TASK-0089 done。
- 不修改 ADR 状态；不代替 Lead/用户/Verifier/Architect 签字；不构成任何发布批准。
- 评审基于 `7a25a04` 的文本与 `9185ca6`/`ffb570e`/`18226e3` 的变更；后续实现变更需重新核对。
