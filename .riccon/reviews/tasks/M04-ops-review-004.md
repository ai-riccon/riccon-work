---
review_id: REV-M04-OPS-004
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-OPS-003
reviewer:
  agent: riccon/ops
  session: ses_e09023f44ffer5DZx7Pcmnufpg
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: []
head_commit: d800410a7f0f413c12740eadfd3daa1ebe82ae67
created_at: "2026-11-20T11:35:08+08:00"
findings:
  - id: OPS-M04-020
    severity: P1
    status: fixed
    owner: riccon/architect + riccon/builder (B1a) + riccon/ops (B10)
    title: "回滚断言已改为每组 1 行、优先 ACTIVE，不再写死全表 COUNT"
    summary: >-
      §10.3 演练断言改为“按 (tenant_id, provider_id, credential_type) 分组，每组恰好 1 行；组内若有 ACTIVE
      则保留 ACTIVE，否则保留 RETIRED；fixture 可跨 provider 为 1 ACTIVE + 1 RETIRED（总行数=2、ACTIVE=1）；
      不得把总行数写死为 1”。这与 §3.2 的两段式种子（V10: 1 ACTIVE + 1 RETIRED 跨 provider；V11 后同一
      provider 再形成 2 ACTIVE + 1 INVALID）相容，也允许 provider B 的 RETIRED-only 组。SQL 第 3 步仍是
      `UPDATE provider_credential SET status='RETIRED' WHERE status IN ('DISABLED','INVALID')`，保留 ACTIVE。
      B1a 验收写“按分组断言 ACTIVE，不写死总行数”，B10 验收写“回滚（先 DROP CHECK、收敛 key、分组断言
      ACTIVE、Flyway 属性）”，已同步。
    evidence: "ADR-0021:777、:802、:654、:667；§3.2:206-213"
  - id: OPS-M04-022
    severity: P1
    status: fixed
    owner: riccon/architect + riccon/lead + riccon/ops (B10/B13)
    title: "发布第 6 步已改为 ROUTED/true/false，M04 观测不再包含配额拒绝"
    summary: >-
      §10.2 第 6 步已写“默认 `ROUTED/true/false`（`quotaEnforcementEnabled=false`）……M04 观测 30 分钟只看
      路由/降级/key/链空/5xx，不观测配额拒绝（M17-B13 负责）”。§10.5 默认值、V11 种子（`false`）、
      ARCHITECTURE 开关段、ADR-0022 §8、B10 验收（M17 首发保持 false）与 B13 验收（false→true 演练）一致。
      全文唯一的 `true/true` 出现在 §13 T-02 的“文档 grep 无 `true/true`”自引用描述中，不是默认值或发布步骤；
      “配额拒绝”只出现在 M17 告警与 M17 核对段。
    evidence: "ADR-0021:306、:700、:745、:837、:1058；ADR-0022:128、:350-362；ARCHITECTURE:267；B10:667；B13:671"
  - id: OPS-M04-035
    severity: P1
    status: fixed
    owner: riccon/architect + riccon/ops (B1c)
    title: "M04 gate 指标已拆为 M04 7 个、M17 2 个；B1c/metrics-check 只断言 7 个"
    summary: >-
      §10.6 已按模块拆分：M04 7 个（`riccon_model_requests_total`、`riccon_model_failover_total`、
      `riccon_model_attempt_duration_seconds`、`riccon_model_credential_invalid_total`、
      `riccon_model_credential_cooldown_total`、`riccon_model_chain_empty_total`、
      `riccon_agent_runs_inflight`，B4 注册）；M17 2 个（`riccon_quota_rejections_total`、
      `riccon_quota_usage_ratio`，B6/B7 注册）+ 成本/存储辅助指标。B1c 验收与 `metrics-check` 只断言 M04 7 个；
      V2 验收抓取 M17 2 个；THREAT B-20a 改为 7 个，B-20b 保留 M17 2 个；§11.1 新增 `OPS-M04-02`
      （owner B1c/V1，证据 metrics 抓取报告），可作 M04 gate 证据。全文无“M04 gate 9 个指标”要求。
    evidence: "ADR-0021:604、:656、:844-846、:865、:919、:1059；THREAT:603、:627-628、:871；PRODUCT:361、:453；V2:672"
  - id: OPS-M04-042
    severity: P2
    status: open
    owner: riccon/architect + riccon/lead
    title: "评审响应矩阵仍写“各组 ACTIVE=1”，与跨 provider 种子不完全一致"
    summary: >-
      §10.3 规范文本是正确的（每组 1 行；有 ACTIVE 则保留，否则 RETIRED；允许总行数 2、ACTIVE=1），
      但 §13 的 T-01/S-02 仍写“分组各自断言 ACTIVE=1”“每组 ACTIVE=1”，B1a/B10 验收简称“分组断言 ACTIVE”。
      按字面对 provider B 的 RETIRED-only 组执行会失败，可能诱导演练者删掉 RETIRED 种子或弱化断言。
      建议统一为“每组 1 行；组内有 ACTIVE 则断 ACTIVE，无 ACTIVE 则断 RETIRED；不写死总行数”。
      这是汇总措辞问题，不推翻 §10.3 的可执行断言，不阻塞 M04 gate。
    evidence: "ADR-0021:802、:1025、:1057、:654、:667"
  - id: OPS-M04-043
    severity: P2
    status: open
    owner: riccon/ops (B1c/B10) + riccon/architect
    title: "metrics-check 抓取目标进程未写明，B4/B6/B7 验收未绑定指标注册"
    summary: >-
      M04 的 7 个指标都由 Worker 产生（模型调用/降级/key/agent run），M17 的配额指标与成本辅助指标由 API
      产生；但 §10.6/B1c/B-20a 只写 `/actuator/prometheus` 和“metrics-check”，未写抓 Worker 还是 API。
      若在 API 端口抓取，M04 7 个可能为 0，B-20a 直接失败或被迫假绿。另：§10.6 把注册责任分给
      B4/B6/B7，但 B4/B6/B7 的任务验收没有“注册这 7/2 个指标”一句，只有 B1c 的“指标约定”。
      建议 metrics-check 明确 `--base-url`/服务名（M04=Worker，M17=API），并在 B4/B6/B7 验收各加注册清单。
      不影响本轮 7/2 拆分结论，但影响 gate 取证的可执行性。
    evidence: "ADR-0021:844-846、:656、:659、:661-662、:919；THREAT:627-628"
  - id: OPS-M04-044
    severity: P2
    status: open
    owner: riccon/architect + riccon/verifier (V2)
    title: "V2 只写抓取 M17 2 个，B-20b 还要求 3 个辅助指标"
    summary: >-
      B-20b 要求 `riccon_quota_rejections_total`、`riccon_quota_usage_ratio` 以及
      `riccon_cost_report_queries_total`、`riccon_quota_store_failure_total`、
      `riccon_quota_over_limit_observed_total` 存在；V2 验收只写“抓取 M17 2 个配额/成本指标”。
      建议写成“2 个主指标 + 3 个辅助指标（B-20b 全清单）”。属 M17 gate 取证覆盖，不阻塞 M04 gate。
    evidence: "ADR-0021:845、:672；THREAT:628"
  - id: OPS-M04-039
    severity: P2
    status: open
    owner: riccon/lead
    title: "B13 已补 ≈2h 且移出关键路径，但 M17 17h 仍隐含 B7≈1h"
    summary: >-
      §8.1/§8.4 已给 B13 ≈2h、与 B9b/B11b 并行、不在关键路径，这部分已修；但 M17 总人时仍为 17h，
      B6 4 + B12 1 + B9b 3 + B11b 2 + V2 4 + B13 2 = 16，B7 只剩约 1h。B7 含成本快照、平台/租户聚合、
      明细 API 与边界测试，1h 明显不足。建议 Lead 在派单前给 B7 单独估算并重算 17h；不阻塞 M04，
      但会影响 M17 排期与演练证据。
    evidence: "ADR-0021:619、:672、:678-681、:1068"
  - id: OPS-M04-040
    severity: P3
    status: open
    owner: riccon/architect
    title: "B1c 验收仍写 `/actuator/prometheus` 仅内网/受控，暴露实现归 B2"
    summary: >-
      §10.6 已统一“registry/约定归 B1c、暴露归 B2”，但 B1c 验收仍写“`/actuator/prometheus` 仅内网/受控”，
      而 B1c 只写 pom、metrics-check 与 actuator 测试，不写 SecurityConfig。建议 B1c 验收改为
      “registry 依赖 + 指标约定 + 默认拒绝负向测试；暴露由 B2 实现”。写范围本身不重叠
      （B1c=`src/test/**/actuator/**`，B2=SecurityConfig），仅措辞问题。
    evidence: "ADR-0021:656、:843、:849、:1021"
  - id: OPS-M04-046
    severity: P3
    status: open
    owner: riccon/architect
    title: "§13 F-021 的 M17 工时 13–15h 与 §8.4 的 14–16h 不一致"
    summary: >-
      §13 F-021 写“M17 13–15h”，§8.4 关键路径写“14–16h”，ARCHITECTURE:322 也写“约 14–16h”。
      属文档数字漂移，取 §8.4 为准即可；不影响 M04 gate。
    evidence: "ADR-0021:680、:1014；ARCHITECTURE:322"
notes: >-
  本轮只读复审 3 个残留 P1（OPS-M04-020/022/035）及其相关 P2/P3 文本，未修改 ADR/契约/
  ARCHITECTURE/THREAT-MODEL/PRODUCT/源码/测试/迁移，未创建任务/模块/决策，未提交 git。
  OPS-M04-020 的规范断言已可执行；任务描述中“每组 1 个 ACTIVE”的字面要求与跨 provider 种子
  （provider B 只有 RETIRED）不相容，当前“每组 1 行、优先 ACTIVE”是正确的可执行形式，故判 fixed，
  另立 OPS-M04-042 统一矩阵/验收措辞。OPS-M04-022/035 已关闭。仍有 OPS-M04-039/042/043/044/046
  等 P2/P3 文档与取证可执行性问题，均不构成新的 P0/P1，也不推翻本轮 pass。M04 gate 仍被
  DEC-0024/模块收窄/M17 创建阻塞（OPS-M04-031），不是本轮设计复审的失败项；ADR-0021/0022 仍
  proposed，本评审不代替 Lead/用户/Verifier/Architect 签字，也不构成任何发布批准。
---

# REV-M04-OPS-004：M04 架构第三轮窄修 — 最终 focused 运维确认

## 1. 结论

**verdict: pass。** 上轮残留的 3 个 P1 在规范文本层面全部关闭：

1. **OPS-M04-020**：§10.3 回滚断言已从全表 `COUNT(*)=1 AND status='ACTIVE'` 改为按 `(tenant_id, provider_id, credential_type)` 分组断言，明确允许跨 provider 的 1 ACTIVE + 1 RETIRED（总行数=2、ACTIVE=1），不写死全表行数；SQL 仍保留 ACTIVE。B1a/B10 验收已同步。
2. **OPS-M04-022**：§10.2 第 6 步已改为 `ROUTED/true/false`，M04 观测不再包含配额拒绝；§10.5、ARCHITECTURE、ADR-0022 §8/§1.3、B10/B13 验收一致，未发现配置性 `true/true` 残留。
3. **OPS-M04-035**：指标已按模块拆为 M04 7 个（B4 注册）+ M17 2 个（B6/B7 注册）；B1c/`metrics-check` 只断言 7 个，B-20a 只要求 7 个，V2 抓取 M17 2 个，`OPS-M04-02` 已进 §11.1。

未发现新的 P0/P1。剩余 OPS-M04-042/043/044/039/040/046 均为 P2/P3 的文档与取证可执行性问题，不阻塞本轮设计确认；其中 **OPS-M04-043（metrics-check 抓 Worker 还是 API）** 建议在 B1c 派单前一并写清，否则 B-20a 取证仍可能抓错进程。

**M04 gate 仍不可开：** DEC-0024 未创建、`.riccon/modules/M04.json` 仍为 18 AC/0 task/24h、M17 模块不存在（OPS-M04-031/S-18/S-32）。这是 Lead 的 gate 前置，不是本轮 OPS 复审的 fail 项。

## 2. 3 个 P1 关闭表

| ID               | 上轮 | 本轮      | 结论与依据                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ---------------- | ---- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| OPS-M04-020 (P1) | open | **fixed** | §10.3:802 改为“按 `(tenant_id, provider_id, credential_type)` 分组，每组恰好 1 行；组内若有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；fixture 可跨 provider 为 1 ACTIVE + 1 RETIRED（总行数=2、ACTIVE=1）；不得把总行数写死为 1”。与 §3.2:206-213 两段式种子相容；SQL:777 的 `WHERE status IN ('DISABLED','INVALID')` 保留 ACTIVE；B1a:654“按分组断言 ACTIVE，不写死总行数”、B10:667“分组断言 ACTIVE”已同步。**注：** 任务描述中的字面“每组 1 个 ACTIVE”对 provider B 的 RETIRED-only 组不可满足，当前“每组 1 行、优先 ACTIVE”才是正确可执行形式；矩阵 T-01/S-02 仍写“各组 ACTIVE=1”，另立 OPS-M04-042。 |
| OPS-M04-022 (P1) | open | **fixed** | §10.2:745 第 6 步改为“默认 `ROUTED/true/false`（`quotaEnforcementEnabled=false`）……M04 观测 30 分钟只看路由/降级/key/链空/5xx，不观测配额拒绝（M17-B13 负责）”。§10.5:837 默认值、V11 种子:306 `false`、ARCHITECTURE:267、ADR-0022 §8:350-362（B13 负责 false→true）、B10:667（M17 首发保持 false）、B13:671（false→true 演练）一致。全文唯一 `true/true` 在 §13 T-02 的“文档 grep 无 true/true”自引用里，不是默认值/发布步骤；未发现“M04 观测配额拒绝”。                                                                                                                                              |
| OPS-M04-035 (P1) | open | **fixed** | §10.6:844-846 拆为 M04 7 个（B4 注册）+ M17 2 个（B6/B7 注册）+ 成本/存储辅助；B1c:656 与 `metrics-check` 只断言 M04 7 个；B-20a:627 改为 7 个；B-20b:628 保留 M17 2 个 + 3 个辅助；V2:672 抓取 M17 2 个；§11.1:919 新增 `OPS-M04-02`（B1c/V1，metrics 抓取报告；M04 模块 review 需在 `dod_evidence` 绑定该 ID）。全文无“M04 gate 9 指标”要求。                                                                                                                                                                                                                                                        |

## 3. 相关 P2 核对

| 检查项                                                             | 结论                                                                                                                                                                                                                                                                                       | 证据                                                               |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| B1a/V1 是否有“无 `fk_model_invocation_provider`”断言               | **有**。B1a 验收逐字包含 `information_schema` 无该 FK；V1 scope 同样包含。                                                                                                                                                                                                                 | ADR-0021:654、:670、:1066                                          |
| ARCHITECTURE 是否不再写 V11 `cost_day` 回填                        | **已改**。写“V11 不回溯 `cost_day`；历史 NULL 计入 `unbucketedCalls`；B10 在 CHECK 后独立分批回填”。                                                                                                                                                                                       | ARCHITECTURE:315；ADR-0021:733、:279；ADR-0022:297                 |
| B10 `backfill-cost-day` 是否有干跑/进度/幂等验收                   | **有**。B10 验收含“`backfill-cost-day` 干跑/进度/幂等验收”；写范围含 `scripts/ops/backfill-*`。                                                                                                                                                                                            | ADR-0021:667、:647、:251                                           |
| B13 工时/依赖                                                      | **部分改善，仍有 P2**。B13 ≈2h、依赖 B12/B6/B7、与 B9b/B11b 并行、不在关键路径；但 M17 17h 隐含 B7≈1h，见 OPS-M04-039。                                                                                                                                                                    | ADR-0021:619、:678-681、:1068                                      |
| B1c/B2/B10/B13 写范围是否重叠                                      | **不重叠**。B1c=poms+`scripts/ops/metrics-check*`+`src/test/**/actuator/**`；B2=SecurityConfig/模型/契约；B10=compose+RUNBOOK+`seed-*/update-*/rewrap-*/restore-*/backfill-*`+CI；B13=RUNBOOK M17 章节+`quota-*/cost-*`+compose 仅追加。compose 同 owner 串行，B13 明确只追加不改 B10 段。 | ADR-0021:647-648、:656、:667、:671                                 |
| `riccon_model_requests_total` 是否复用 ADR-0008 名称并明确标签扩展 | **是**。§10.6:848 明确复用 ADR-0008 名称、扩展为 `{provider,model,outcome}`、B4 唯一注册；ADR README:63 记录，ADR-0008 正文不改（写入边界）。                                                                                                                                              | ADR-0021:848；docs/adr/README.md:63；ADR-0008:90                   |
| OPS-036/037/038/040/041                                            | **036/037/038/041 fixed**；040 仅余 B1c 验收措辞（P3）。                                                                                                                                                                                                                                   | ARCHITECTURE:315；ADR-0021:654、:670、:667、:671、:848、:1069-1070 |

## 4. 无新 P0/P1 的核对

### 4.1 指标 7/2 拆分与 AC-17/V2 合并证据

- M04：B1c 只定义 registry/约定，B4 注册 7 个；V1/B-20a/`OPS-M04-02` 只核对 7 个，M04 gate 不再要求 9 个。
- M17：B6/B7 注册 2 个主指标 + 成本/存储辅助；V2/B-20b 抓取 M17 2 个；AC-17 整条归 M17，使用 M04+M17 合并证据（M04 的 failover/首字/开关子集 + M17 的配额/成本/负载），与 §8.4:690、PRODUCT:361/453 一致。
- 新发现仅是取证可执行性：metrics-check 抓取进程未写明（OPS-M04-043）、B4/B6/B7 验收未绑定注册（同 043）、V2 辅助指标覆盖不足（OPS-M04-044）。均不构成 P1。

### 4.2 回滚分组断言与跨 provider 种子

- §3.2 种子：V10 1 ACTIVE + 1 RETIRED 跨 provider；V11 后同一 provider 形成 2 ACTIVE + 1 INVALID；回滚演练从 V11 后开始。
- 回滚后：provider A 组 1 行 ACTIVE；provider B 组 1 行 RETIRED；总行数 2、ACTIVE=1。§10.3:802 的新断言逐项匹配，且明确“不得把总行数写死为 1”。
- 未发现新矛盾。OPS-M04-042 只是矩阵/验收的简称与规范文本不一致，建议统一措辞。

### 4.3 契约分段

- `contracts/openapi.yaml` 仍 `1.3.0-m04` 全量设计（88 paths / 154 schemas），符合“DEC-0024 后、M04 gate 前出 `1.3.0-m04a`，M17 出 `1.4.0-m04b`”的分段计划。
- B2 验收含 `1.3.0-m04a` 分段，B12 依赖 M04-B2，V2 依赖 B12/B13/B11b；contracts/README:5-7 已写明拆分计划。未发现分段与 7/2 指标拆分的新冲突。
- 唯一前置仍是 DEC-0024（OPS-M04-031）。

### 4.4 B13 并行、compose 与写范围

- B13 依赖 B12/B6/B7，与 B9b/B11b 并行、不在 M17 关键路径；V2 依赖 B13。
- `docker-compose.test.yml` 由 B10 独占，B13 仅追加 M17 漂移检查变量；同一 owner 串行，无并发写。
- `scripts/ops` 前缀分工：B1c `metrics-check*`、B10 `seed-*/update-*/rewrap-*/restore-*/backfill-*`、B13 `quota-*/cost-*`；无文件级重叠。

### 4.5 发布第 6 步与开关

- §10.2:745 `ROUTED/true/false`；§10.5:837 默认值；V11:306 种子 `quotaEnforcementEnabled=false`；M17-B13 验证后显式 `PUT true`（§8.4:700、ADR-0022 §8）。
- 未发现 `ROUTED/true/true` 或“M04 观测配额拒绝”的配置性残留。T-02 自引用里的 `true/true` 是验证描述，不是发布默认；若项目要用字面 grep 做 gate，建议改写 T-02 的表述（P3，不单列 finding）。

## 5. 仍 deferred 项是否阻塞

| 项                                            | 结论                                                                                                                                                                                                 | 是否阻塞 M04 gate                                                                                                    |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| OPS-M04-017（`deploy_preview`/`deploy_prod`） | ADR-0021 §10.2:749 明确 M04 不以 `deploy_preview`/`deploy_prod` 取证；迁移/回滚走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`，评审包标 `not-applicable`（M09）。RUNBOOK M04 章节由 B10 交付。 | **否（有条件）**：条件是在评审包登记 `not-applicable` 与一次性环境证据。                                             |
| OPS-M04-031（DEC-0024 时序）                  | `.riccon/decisions/` 仍无 DEC-0024；`.riccon/modules/M04.json` 仍为 18 AC/0 task/24h；M17 模块不存在。ADR-0021 §8.4:696 要求 Lead 在派 B2 前拿到 DEC-0024，未答复则 M04 gate 等待。                  | **是（gate 前置）**：不阻塞本轮设计复审，但阻塞 M04 gate 与 B2 派单。                                                |
| F-019（文档同步）                             | ADR/ARCHITECTURE 侧已修正；`docs/ACCEPTANCE.md:55` 仍写 M13 F-001“契约对齐由 M04/M10 承接”，与 M04 acceptance“M13 F-001 不承接”冲突。                                                                | **否（设计层面）**：建议 Lead/Product 在 M04 gate 前更新 ACCEPTANCE 表，否则验收范围会有歧义；不构成 OPS 设计 fail。 |

## 6. 实际验证命令与退出码

| 命令                                                                                                                                               | 退出码 | 结果                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                                                                                               | 0      | `d800410a7f0f413c12740eadfd3daa1ebe82ae67`（设计改动仍未提交）                            |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict`                                                                     | 0      | `Project contract: OK (0 errors, 0 warnings)`                                             |
| `ruby -ryaml -e 'YAML.load_file("contracts/openapi.yaml")'`                                                                                        | 0      | `openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`                                    |
| `docker compose -f deploy/compose/docker-compose.test.yml config`                                                                                  | 0      | 编排可解析                                                                                |
| `grep -n 'COUNT(\*)=1' docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md`                                                                  | 1      | 0 命中：全表 COUNT 断言已删除                                                             |
| `grep -n 'true/true' docs/adr/ADR-0021* docs/adr/ADR-0022* docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md docs/PRODUCT.md contracts/README.md` | 0      | 1 命中：仅 §13 T-02 的“文档 grep 无 `true/true`”自引用，不是默认值/发布步骤               |
| `grep -n '9 个 M04\|9 个 riccon_\|M04 gate.*9 个' ...`                                                                                             | 1      | 0 命中：无 M04 gate 9 指标要求                                                            |
| `grep -c 'M04（7 个，B4 注册）' ADR-0021`                                                                                                          | 0      | 1                                                                                         |
| `grep -c 'metrics-check.*只断言 M04 7 个' ADR-0021`                                                                                                | 0      | 3（B1c、§10.6、T-03）                                                                     |
| `grep -c 'information_schema.*无.*fk_model_invocation_provider' ADR-0021`                                                                          | 0      | 3（B1a、V1、T-10）                                                                        |
| `grep -c 'backfill-cost-day.*干跑/进度/幂等' ADR-0021`                                                                                             | 0      | 2（B10、T-11）                                                                            |
| §13 矩阵计数                                                                                                                                       | 0      | 95 数据行；91 `fixed` / 4 `deferred`（OPS-017、F-019、S-18、S-32），与 Architect 声称一致 |

**未运行（按任务约束）：** `./mvnw verify`、`pnpm -w *`、k6、E2E、a11y、Prometheus 抓取、真实 V11 迁移（V11 文件尚不存在）。**本轮未重跑 MySQL 探针**：§10.3 的 SQL 自 REV-M04-OPS-003 后未改，仅断言文本改为分组形式；REV-003 的探针结果（回滚后 2 行/1 ACTIVE；provider A 组 ACTIVE、provider B 组 RETIRED；archive=2；旧唯一键恢复；重复插入 `ERROR 1062`）与新断言逐项对应。本文不对覆盖率、p95、E2E 作任何通过性判断。

## 7. 剩余风险与边界

- V11 文件尚不存在；未验证真实 V10 数据量下的索引耗时、磁盘余量与复制延迟，>200 万行/2 GiB 的停写判据仍需 B1a/B10 在真实体积上实测并写入 RUNBOOK。
- `metrics-check` 的抓取目标进程未写明（OPS-M04-043）；M04 7 个指标在 Worker，M17 指标在 API，若抓错端口，B-20a/B-20b 会失败或假绿。
- B4/B6/B7 的任务验收未显式绑定指标注册（同 OPS-M04-043）；§10.6 的分配是设计意图，但严格证据绑定下建议落到验收条目。
- ADR-0021/0022 仍 `proposed`；DEC-0024 未创建；M04.json 仍 18 AC/0 task/24h；M17 模块与 M17-B13 任务卡未创建。本评审不代替 Lead/用户/Verifier/Architect 签字，也不构成任何发布批准。
