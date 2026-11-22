---
review_id: REV-M04-ARCH-003
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-ARCH-002
reviewer:
  agent: riccon/verifier
  session: ses_e0932f591ffeo3V6L6U60bXuGp
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: 威胁模型 gate 已按 M04/M17 拆分收口
    summary: >-
      THREAT-MODEL §8 已把 M04 gate 限定为 B-18a、B-20a 与 B-06 续期，M17 gate 才要求
      B-18b、B-19、B-20b 与 B-06 续期；B-18a 只含 M04 的 SEC-M04-001/002/003a/004/005/006/007/008/009、
      IT-MODEL-105/106/107、TEN-031，B-18b 才含 SEC-M04-003b、TEN-032/033。§10 已拆出
      “M04 的核对必须额外回答”和“M17 的核对（不得由 M04 gate 声称）”，M04 清单不再要求
      配额/成本证据，且明确 M04 只核对 B-20a、不做 V2 抓取。ADR-0021 的 M04 acceptance、
      V1 acceptance 与 THREAT §8 一致，不再误引 SEC-M04-003b/TEN-032/033/B-19 作为 M04
      gate 条件；只保留一处 P2 文本残留（F-020）。
    evidence: "docs/security/THREAT-MODEL.md:603,624-628,864-879; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:669,687,1031"
  - id: F-002
    severity: P1
    status: fixed
    title: AC-02 整条归 M17，M04 不再声称
    summary: >-
      ADR-0021 §8.4 的 M04 acceptance 只写 M04-AC-01、03、04、05、06、07、15、16（8 条），
      并明确 M04-AC-02、08..14、17、18 不在本模块 gate 声称通过；M17 acceptance 写
      AC-02、08..14、17、18（10 条）；V1 只跑 M04 的 8 条 AC，V2 跑 M17 的 10 条 AC，
      AC-02 用 `SEC-M04-003a` 的 M04 证据 + `SEC-M04-003b` 验证。8 + 10 = PRODUCT §10.3 的
      18 条 AC，未增未减。PRODUCT 原文仍把 AC-02 列在 M04 段，但任务已明确这是预期的
      Product/Lead 后续同步项，不是架构遗漏。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:669,671,687-689,693,1032; docs/ARCHITECTURE.md:322"
  - id: F-005
    severity: P1
    status: fixed
    title: quota/cost 表结构冒烟已绑定 owner 与 V1 独立证据
    summary: >-
      B1a 验收已含 quota/cost/attempt 表结构、空表 INSERT/ROLLBACK、PK 顺序/默认值断言
      （按 B6 upsert 形状）；V1 scope 增加 V11 quota/cost/attempt 结构 + 空表 INSERT/ROLLBACK
      独立断言；M04 模块 acceptance 写“V11 的 quota/cost 表结构与最小读写冒烟通过”，
      §8.4 拆分注意进一步要求 FK/CHECK 断言。owner（B1a）与独立证据（V1）均已落地。
      仅 B1a 验收文本未逐字写 FK/CHECK，依赖 §8.4 注；作为 P3 文本项记录（F-023），不影响关闭。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:653,669,687,698,1033,1038"
  - id: F-017
    severity: P2
    status: open
    title: M17-B13 未进入 §8.1 总览表，任务数 19 与表内 18 不一致
    summary: >-
      ADR-0021 §8.1 的 M17 任务表只有 B6/B7/B12/B9b/B11b/V2 六行，没有 M17-B13；但同节
      计数写“M17 7 个（…B13…）”，§8.3 也有 B13 的写范围/验收，§8.2 依赖图有
      `M17-B12/B6/B7 → B13 → V2`，§8.2 散文写 B13 的 owner 是 `riccon/ops`，V2 依赖还引用
      M17-B13。缺少 §8.1 这一行会让 Lead 按总览建任务时漏掉 B13 的 owner/依赖。
      不阻塞架构方向：B13 在 §8.3/§8.2 有完整写范围、验收和 owner；但必须在 Lead 创建
      M17 任务前补行，否则 M17 的 quota 开关 false→true、成本对账窗口没有正式任务卡。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:614-621,636,647,670,1048,1056"
  - id: F-018
    severity: P2
    status: open
    title: ARCHITECTURE 迁移顺序仍把 cost_day 回填写成 V11 步骤，与 ADR 的“V11 不回溯”冲突
    summary: >-
      docs/ARCHITECTURE.md:315 的 M04 迁移顺序写“加列 → … → `cost_day` 分批回填”，
      但 ADR-0021 §3.3/§10.1 明确“V11 不回溯 cost_day；需要时由 B10 的
      `backfill-cost-day` 在 CHECK 后独立分批执行，M04 gate 不依赖全量回填”，
      ADR-0022 §5 也重复该口径。按 ARCH 这句话实现，Builder 可能把回填放回 V11，重新引入
      OPS-M04-012 的单批/LIMIT 风险。权威 ADR 已正确，故列 P2；必须在 B1a 实现前把
      ARCH 行改为“V11 不回溯，B10 迁移后可选回填”。
    evidence: "docs/ARCHITECTURE.md:315; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:251,279,732; docs/adr/ADR-0022-m04-quota-and-cost.md:297"
  - id: F-019
    severity: P2
    status: open
    title: ADR §11.1 测试 ID 目录缺 SEC-M04-007/008/009
    summary: >-
      B-18a（M04 gate）和 V1 acceptance 都引用 `SEC-M04-007/008/009`，THREAT-MODEL §10 也给出
      三者场景/断言；但 ADR-0021 §11.1 的“场景 / 断言 / owner / 证据”目录只到 SEC-M04-006，
      没有 007/008/009 的 owner 与证据路径。该目录是 AC → 测试 ID → 报告路径的登记表，
      缺行会让 V1 对这三个 ID 的归属/证据口径不一致。补三行即可（owner V1，证据
      surefire/扫描报告），不改变设计。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:669,884-904,921; docs/security/THREAT-MODEL.md:624,803-805"
  - id: F-020
    severity: P2
    status: open
    title: THREAT-MODEL §10 的 M04 核对首条仍写 TM-39–TM-54，含 M17 的配额/成本项
    summary: >-
      THREAT-MODEL §8 的 gate 归属已正确；但 §10 “M04 的核对必须额外回答”的第一条仍写
      “`TM-39`–`TM-54` 是否都有可执行证据”，其中 TM-45..48、TM-51、TM-54 的配额/成本部分属于
      M17。虽然紧邻的 M17 清单已写“不得由 M04 gate 声称”，且 M04 详细问题不再含配额/成本，
      该首条仍可能被 M04 verifier 理解为需要 M17 证据。建议改成“TM-39–44/49/50/52/53 的
      M04 段；TM-45–48/51/54 的 M17 段”，与 §8 完全一致。
    evidence: "docs/security/THREAT-MODEL.md:864-879; docs/security/THREAT-MODEL.md:603"
  - id: F-021
    severity: P2
    status: open
    title: ADR-0022 §8 仍把 M17 首发配额开关归 B10 RUNBOOK，B13 才是 owner
    summary: >-
      ADR-0021 §8.4/§8.3 已新增 M17-B13（`riccon/ops`），由 B13 验证默认限额/租户用量、
      配置漂移与成本对账窗口后显式 `PUT true`；但 ADR-0022 §8 的不可逆项仍写
      “B10 RUNBOOK + M17 评审包”。B13 在 ADR-0022 §7 已正确出现，§8 是残留文本。
      建议改成“B13 RUNBOOK + M17 评审包”，避免 Lead/Builder 把 M17 发布步骤派给 B10。
    evidence: "docs/adr/ADR-0022-m04-quota-and-cost.md:324,361; docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:670,699"
  - id: F-022
    severity: P3
    status: open
    title: ARCHITECTURE 评审状态表仍引用 REV-M04-ARCH-001/-002 之前的结论
    summary: >-
      docs/ARCHITECTURE.md 顶部评审状态表仍写“REV-M04-ARCH-001 fail；修订 F-001..F-026，
      待复审”，未反映 REV-M04-ARCH-002 的 3×P1 与本次第三轮复审。该表不参与 gate，
      但会误导后续会话对当前评审轮次的判断；本次评审后应更新为 REV-M04-ARCH-003 的结论。
    evidence: "docs/ARCHITECTURE.md:7-12,22-23; docs/adr/README.md:61"
  - id: F-023
    severity: P3
    status: open
    title: B1a 验收文本未逐字写 FK/CHECK，只在 §8.4 注中要求
    summary: >-
      B1a 验收写“ quota/cost/attempt 表结构 + 空表 INSERT/ROLLBACK + PK 顺序/默认值断言”，
      §8.4 拆分注意才写“FK/CHECK 断言”。两者合起来覆盖 F-005 的最低要求，但任务卡本身
      未逐字列出 FK/CHECK，Builder 可能只做 PK/defaults。建议 B1a 验收补“FK/CHECK”四字，
      不影响本轮架构结论。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:653,698"
created_at: "2026-11-20T11:11:23+08:00"
notes: >-
  本次只读评审设计文档与契约，未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/源码/测试/迁移，
  只写本评审文件。未运行 build/test/coverage/E2E/perf/security/a11y：设计阶段无源码变更，且本
  verdict 为设计通过而非模块 gate 通过。F-001/F-002/F-005 已关闭；新发现均为 P2/P3 文档一致性
  项，未发现新 P0/P1。ADR-0021/0022 仍为 `proposed`；DEC-0024 尚未创建；`.riccon/modules/M04.json`
  仍为 18 AC/24h，M17 尚未创建。M04 模块 gate 在 F-015（Lead 收窄模块、创建 M17 与 DEC-0024）
  完成前不可能通过，这是状态前置条件，不是本轮设计 fail。Product §10.3/§10.6 仍按原文写 18 条
  属于 M04、AC-02 含配额子句，属预期未改文本；Lead/Product 需在 M04 评审包或 M17 前同步。
---

# REV-M04-ARCH-003：M04 架构第三轮修订 — 最终 focused 复审

## 1. 结论

**pass（设计评审通过；不是 M04 模块 gate 通过）。** 上一轮 3 个 P1（F-001 威胁模型 gate、F-002 AC-02 归属、F-005 quota/cost 冒烟 owner）均已实质关闭；第三轮未引入新的 P0/P1。发现 7 项 P2/P3 文档一致性问题（F-017..F-023），其中 F-017/F-018 必须在 Lead 创建 M17 任务或 B1a 实现前修掉，但不改变架构方向。

M04 模块 gate 仍会被 F-015（`M04.json` 仍 18 AC/24h、M17 未创建、DEC-0024 未创建）和 Lead/Product 的文档同步动作阻塞；这不是对第三轮架构设计的否定。

## 2. F-001 / F-002 / F-005 关闭表

| 上一轮 finding                  | 结论                           | 证据 / 残留                                                                                                                                                                                                                                                                                                                                                    |
| ------------------------------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 威胁模型 gate 未收口      | **fixed（P2 文本残留 F-020）** | THREAT §8 已按 M04/M17 拆分：M04 gate 只要求 B-18a、B-20a 与 B-06 续期；M17 gate 才要求 B-18b、B-19、B-20b。B-18a 只含 M04 的 `SEC-M04-001/002/003a/004/005/006/007/008/009`、`IT-MODEL-105/106/107`、`TEN-031`；`SEC-M04-003b`/`TEN-032/033`/B-19 归 B-18b/B-19/M17。§10 已拆 M04/M17 核对清单，M04 只核对 B-20a。残留：§10 M04 首条仍写 TM-39–TM-54（F-020） |
| F-002 AC-02 未被拆分            | **fixed**                      | M04 acceptance 只写 AC-01、03、04、05、06、07、15、16（8 条），并明确 AC-02、08..14、17、18 不在 M04 gate 声称通过；M17 acceptance 写 AC-02、08..14、17、18（10 条）；V1 只跑 M04 8 条 AC，V2 跑 M17 10 条 AC，AC-02 用 `SEC-M04-003a` 的 M04 证据 + `SEC-M04-003b` 验证；总计 18 条不变。PRODUCT 原文未改属预期，Lead/Product 后续同步                        |
| F-005 quota/cost 表冒烟无 owner | **fixed（P3 文本残留 F-023）** | B1a 验收含 quota/cost/attempt 表结构、空表 INSERT/ROLLBACK、PK 顺序/默认值断言（按 B6 upsert 形状）；V1 scope 含 V11 quota/cost/attempt 结构 + 空表 INSERT/ROLLBACK 独立断言；M04 acceptance 写“V11 的 quota/cost 表结构与最小读写冒烟通过”；§8.4 注明确 FK/CHECK 断言。残留：B1a 验收未逐字写 FK/CHECK                                                        |

结论：上一轮 3 个 P1 全部关闭；没有新的 P0/P1。

## 3. 新 P2/P3 与拆分 gate 核对

### 3.1 新发现（均为 P2/P3，不阻塞设计通过）

| ID    | 严重度 | 结论                                                                      | 影响 / 最小修复                                                                                                               |
| ----- | ------ | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| F-017 | P2     | §8.1 的 M17 总览表缺 M17-B13，表内 18 行 vs 计数 19                       | §8.3 有 B13 写范围/验收，§8.2 有 owner/依赖；Lead 创建 M17 任务前补一行 `M17-B13 / riccon/ops / M17-B6、M17-B7、M17-B12 / 中` |
| F-018 | P2     | ARCHITECTURE:315 把 `cost_day` 回填写进“迁移顺序”，与 ADR“V11 不回溯”冲突 | 改为“V11 不回溯；B10 在 CHECK 后独立分批回填”。权威 ADR 已正确，B1a 实现前必须改                                              |
| F-019 | P2     | ADR §11.1 缺 `SEC-M04-007/008/009` 的 owner/证据行                        | 补三行（V1，surefire/扫描报告），与 THREAT §10 的 803-805 对齐                                                                |
| F-020 | P2     | THREAT §10 M04 核对首条仍写 TM-39–TM-54，含 M17 项                        | 改为“TM-39–44/49/50/52/53 的 M04 段；TM-45–48/51/54 的 M17 段”或注明 M17 复核                                                 |
| F-021 | P2     | ADR-0022 §8 仍把 M17 配额开关归 B10 RUNBOOK                               | 改为 B13 RUNBOOK；B13 已是 ADR-0021 的 owner                                                                                  |
| F-022 | P3     | ARCHITECTURE 评审状态表未更新到 REV-002/003                               | 本评审后由 Architect/Lead 更新                                                                                                |
| F-023 | P3     | B1a 验收未逐字写 FK/CHECK                                                 | 补“FK/CHECK”四字即可                                                                                                          |

### 3.2 无新 P0/P1 的核对表

| 检查点                  | 结论                 | 核对结果                                                                                                                                                                                                 |
| ----------------------- | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC 总数                 | 通过                 | M04 8 条 + M17 10 条 = 18；与 PRODUCT §10.3 的 18 条一致；M04 不声称 AC-02                                                                                                                               |
| 任务数 19               | **P2 残留（F-017）** | §8.3 有 19 个任务（含 B13），计数行写 19；§8.1 总览只有 18 行（缺 B13）                                                                                                                                  |
| M17-B13                 | **P2 残留（F-017）** | §8.2/§8.3/§8.4/V2 依赖均有 B13；§8.1 缺 owner/依赖行                                                                                                                                                     |
| V1/V2 测试 ID           | 通过                 | `IT-MODEL-108a` owner B2、`108b` owner B12；`SEC-M04-003a` V1、`003b` V2；`E2E-M04-07a` B11a、`07b` B11b；§11.1 41 个 ID 无重复。`SEC-M04-007/008/009` 缺 §11.1 行（F-019），但 THREAT 有定义、V1 有引用 |
| B3 独立 Verifier        | 通过                 | §8.5 的高风险清单含 B1a/B1b/B2/B3/B4/B5/B6；B3 不再漏评                                                                                                                                                  |
| V2 依赖                 | 通过                 | V2 依赖 `M17-B6/B7/B12/B11b/B13` + `M04-B8/B10`；图与表都有 B13                                                                                                                                          |
| B12 依赖                | 通过                 | B12 依赖 `M17-B6/B7/M04-B2`；B2 是 M04 契约唯一 writer                                                                                                                                                   |
| 契约分段                | 通过                 | 当前 `contracts/openapi.yaml` 仍 `1.3.0-m04` 全量设计；DEC-0024 后、M04 gate 前 `1.3.0-m04a`；M17 `1.4.0-m04b`；ADR/README/ARCH 口径一致                                                                 |
| M17 `depends_on: [M04]` | 通过                 | §8.4 写明；B6/B7 等 M04 Product 预验收 + 用户 accepted 后启动；DEC-0024 例外必须记录                                                                                                                     |
| AC-17/18 合并证据       | 通过                 | M17 acceptance 写 M04+M17 合并证据；V2 跑 PERF-M04-001/002 与 M04 子集；M04 只提供 failover/首字/开关子集                                                                                                |
| 开发/测试 owner 边界    | 通过（P2/P3 注释）   | B1c/B2/B4/B7 测试范围已收窄；B13/B10 同 owner `riccon/ops` 串行；B13 与 B10 都写 `docker-compose.test.yml`，靠同 owner 串行避免并行写                                                                    |

结论：未发现新 P0/P1；F-017 是当前唯一需要 Lead 在创建任务前处理的表结构回填，F-018 是 B1a 实现前必须修的迁移说明。

## 4. 仍 deferred 项是否阻塞

| 项                                                       | 是否阻塞 M04 gate         | 说明                                                                                                                                                                                                                                                                                         |
| -------------------------------------------------------- | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-015（M04.json 18 AC/24h、M17 未创建、DEC-0024 未创建） | **阻塞任务创建/M04 gate** | `.riccon/modules/M04.json:13-17` 仍是 AC-01..18、`wall_clock_limit_hours: 24`、`split_from: null`、`aliases: []`；`.riccon/decisions/` 无 DEC-0024；`.riccon/modules/` 无 M17。ADR §8.4 已写清 Lead 前置动作（收窄 M04、创建 M17、创建 DEC-0024）；这是状态/Lead 动作，不是第三轮设计 fail。 |
| F-019（ACCEPTANCE/ADR-0012 文档同步）                    | 不阻塞                    | ADR-0012 §4 旧文字仍在（`:47,:56`），但 ADR-0021 顶部已有覆盖声明（`:41`），ARCHITECTURE:115 同步；ACCEPTANCE 仍写 M13 F-001/R-11 承接，属 Lead/Product 文档动作。M04 acceptance 已明确 R-11/F-015/M13 F-001 不承接。                                                                        |
| OPS-M04-017（deploy_preview/deploy_prod 缺失）           | 不阻塞                    | ADR-0021:748 明确 M04 不以 deploy 取证，迁移/回滚走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`；M04 评审包标 `deploy_preview not-applicable`（M09）；不得把 `commands.run` 当 M04 gate。                                                                                              |
| OPS-M04-012（cost_day 回填）的 ARCH 残留                 | 不阻塞设计，B1a 前修      | ADR 已改对；ARCH:315 仍写迁移顺序含回填，见 F-018。                                                                                                                                                                                                                                          |
| OPS-M04-002 系 P0/P1（019/020/021/012/022/023/008）      | 不阻塞本轮设计            | ADR §3.2/§3.3/§10.3/§8.3 已有对应修正（无 FK、回滚 WHERE、两阶段种子、V11 不回溯、B13、riccon_* 指标、B1a 接管 WorkerIntegrationTestBase）；Ops 应再出一次独立复审，本评审不代替 Ops 签字。                                                                                                  |

## 5. 实际验证命令与退出码

| 命令                                                                                       | 退出码    | 结果                                                                                                                                                                        |
| ------------------------------------------------------------------------------------------ | --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                                       | 0         | `d800410a7f0f413c12740eadfd3daa1ebe82ae67`                                                                                                                                  |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict --project .` | 0         | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                               |
| Ruby YAML/`$ref`/路径参数/CSRF/operationId 检查                                            | 0         | OpenAPI 3.1.0，`info.version=1.3.0-m04`，88 paths、154 schemas；956 个 `$ref` 全部可解析；0 个 path 参数不在模板；53/53 个写端点声明 `CsrfToken`；109 个 operationId 无重复 |
| Python 解析 ADR §11.1                                                                      | 0         | 41 个 ID，0 个同表重复；108a owner B2、108b owner B12；003a V1、003b V2；07a B11a、07b B11b；缺 SEC-M04-007/008/009 行（F-019）                                             |
| Python 解析 ADR §8.1/§8.3                                                                  | 0         | §8.1 18 行、§8.3 19 行、计数行 19；§8.1 缺 M17-B13（F-017）                                                                                                                 |
| `git diff --check`                                                                         | 0         | 无空白错误                                                                                                                                                                  |
| `node ... riccon.mjs status`                                                               | 0         | M04 planned、0/0 tasks、gate fail、0 open decisions；无 DEC-0024；M17 不存在                                                                                                |
| `node ... riccon.mjs gate check --module M04`                                              | 1（预期） | M04 planned、无任务、无模块 review；不是设计评审的通过条件                                                                                                                  |

未运行：`build`、`lint`、`typecheck`、`test`、`coverage`、`e2e`、`perf`、`security`、`a11y`。设计阶段没有源码变更；本 verdict 是设计/架构通过，不是模块 gate 通过。

## 6. 未验证与剩余风险

- 未在真实 MySQL 8.4.13 上验证 V11（尚不存在）、50 并发、3s 首字、成本查询 p95、Prometheus 抓取；这些属于 B1a/B6/B7/B8/V2 的后续证据。
- 未在浏览器中验证四个管理页、375px、键盘、axe；属于 B9a/B9b/B11a/B11b 的后续证据。
- ADR-0021/0022 仍为 `proposed`；DEC-0024 未创建；`M04.json` 仍 18 AC/24h；M17 模块不存在。本评审不代替 Lead/用户/Architect/Ops 的签字，也不构成生产发布批准。
- Product §10.3/§10.6 仍按原文写 18 条属于 M04、AC-02 含配额子句，属预期未改文本；Lead/Product 必须在 M04 评审包登记并在 M17 前同步，否则会与 ADR 的 8/10 拆分读数冲突。
- Ops 评审（REV-M04-OPS-002）仍为 fail；本轮只核对架构侧对应修正，未替代 Ops 对 deploy/迁移/回滚的独立复审。
