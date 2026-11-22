---
review_id: REV-M04-OPS-005
module_id: M04
task_id: TASK-0089
previous_review: REV-M04-OPS-004
reviewer:
  agent: riccon/ops
  session: ses_e074ca195ffeY6bQFfDhkPkt0b
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [91c2d44, dd4a6a8]
head_commit: dd4a6a8f7b5dba8ec627370524c635be4496ccf8
created_at: "2026-11-20T19:31:41+08:00"
findings:
  - id: OPS-M04-042
    severity: P2
    status: open
    owner: riccon/architect + riccon/lead
    title: "回滚矩阵 T-01/S-02 仍写“每组 ACTIVE=1”，与跨 provider 种子不一致"
    summary: >-
      §10.3:796 的规范断言是“每组 1 行；有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数”，但
      §13:1021（S-02）与 §13:1053（T-01）仍写“每组 ACTIVE=1”。provider B 的 RETIRED-only 组按字面执行会失败。
      建议统一为 §10.3 措辞；不推翻本轮 pass。
    evidence: "ADR-0021:796、:1021、:1053；B1a:649；B10:661"
  - id: OPS-M04-043
    severity: P2
    status: fixed
    owner: riccon/ops (B1c) + riccon/architect
    title: "metrics-check 抓取目标已明确为 Worker，B4/B6/B7 验收已绑定指标注册"
    summary: >-
      B1c 验收写明 `scripts/ops/metrics-check` 以 `--base-url` 指向 Worker，只断言 7 个路由/降级/key 指标；
      §10.6:840 同步；B4:654 写“注册 7 个”、B6:656 写注册 2 个配额指标、B7:657 写注册成本查询指标。
      上轮“抓错进程”的可执行性风险关闭。
    evidence: "ADR-0021:651、:654、:656、:657、:840"
  - id: OPS-M04-044
    severity: P2
    status: fixed
    owner: riccon/architect + riccon/verifier (V2)
    title: "V2 验收已覆盖 API 2 个主指标 + 3 个辅助指标"
    summary: >-
      V2:665 写“7+2 指标核对（Worker 7 + API 2 主 + 3 辅助，OPS-M04-02）”；B-20（THREAT:626）要求
      `riccon_cost_report_queries_total`/`riccon_quota_store_failure_total`/`riccon_quota_over_limit_observed_total`
      三个辅助指标，V2 验收文本已逐项覆盖。上轮“V2 只抓 2 个”关闭；测试 ID 归属另见 OPS-M04-047。
    evidence: "ADR-0021:665、:839、:913；THREAT:626"
  - id: OPS-M04-039
    severity: P2
    status: fixed
    owner: riccon/lead
    title: "单模块关键路径已给 B7 3h，原 M17 17h 隐含 B7≈1h 的问题消失"
    summary: >-
      §8.4:673 明确 B7 3h；原分模块方案的 17h 总人时已随 DEC-0024=B 撤销，OPS-M04-039 不再适用。
    evidence: "ADR-0021:620、:673、:680、:1010"
  - id: OPS-M04-040
    severity: P3
    status: fixed
    owner: riccon/architect
    title: "B1c 验收已把 Actuator 暴露归 B2"
    summary: >-
      B1c:651 改为“registry 依赖 + 指标约定 + 负向测试；暴露实现归 B2，不改默认拒绝基线”，不再要求 B1c
      写 `/actuator/prometheus` 暴露。
    evidence: "ADR-0021:651、:843、:849"
  - id: OPS-M04-046
    severity: P3
    status: fixed
    owner: riccon/architect
    title: "M17 工时数字漂移随拆分撤销失效"
    summary: >-
      §13 F-021 已标 superseded（:1010），不再存在“M17 13–15h 与 14–16h 不一致”的现行文本。
    evidence: "ADR-0021:680、:1010"
  - id: OPS-M04-047
    severity: P2
    status: open
    owner: riccon/architect + riccon/verifier (V2) + riccon/ops (B1c)
    title: "OPS-M04-02 只定义 Worker 7 个指标，但 V2 用它承载 7+2"
    summary: >-
      V2:665 写“7+2 指标核对（Worker 7 + API 2 主 + 3 辅助，OPS-M04-02）”，但 §11.1:913 的 `OPS-M04-02`
      只定义“M04 7 个 `riccon_*` 指标”，owner 为 B1c/V1；API 的 2 主 + 3 辅助没有独立测试 ID/owner。
      B-20（THREAT:626）要求 V2 记录 API 抓取样本，严格证据绑定下会找不到对应 ID。
      建议拆 `OPS-M04-02a`（Worker 7，B1c/V1）与 `OPS-M04-02b`（API 2+3，V2），或扩展 `OPS-M04-02`
      的定义、owner 与证据要求。
    evidence: "ADR-0021:665、:913；THREAT:626、:871"
  - id: OPS-M04-048
    severity: P2
    status: open
    owner: riccon/architect + riccon/builder (B1a/B6) + riccon/ops (B10)
    title: "默认配额限额没有命名的部署配置键，B10 无法做 preflight/漂移检查"
    summary: >-
      ADR-0022:39 批准默认限额（租户日 10,000/月 200,000；用户日 1,000/月 20,000），:44/:105 规定无显式行时取
      部署配置；但 B1a:649 的 M04 配置键清单只有 crypto/endpoint/management/routing/currency，全文没有默认配额
      键名（`grep RICCON_QUOTA` 0 命中）。B10:661 要求 quota 演练验证“默认限额/租户用量/失败回滚”，
      preflight:733 也未列默认配额。B6 若在代码内硬编码，客户无法按 DEC-0024 调整；若自定义键，B10 又无法
      写一致的漂移检查。建议在 ADR-0022 §1.1 命名键（如 `RICCON_QUOTA_DEFAULT_TENANT_DAILY` 等），
      纳入 B1a 配置键与 B10 preflight/`/actuator/info`。
    evidence: "ADR-0022:39、:44、:105、:129；ADR-0021:649、:661、:733"
  - id: OPS-M04-049
    severity: P2
    status: open
    owner: riccon/ops (B10) + riccon/verifier (V2) + riccon/architect
    title: "回滚验收未绑定 Worker schema 校验关闭、固定 WARN 与 V2 独立重放口径"
    summary: >-
      §10.3:750 要求回滚窗口以 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动旧 Worker，输出固定
      WARN 并登记工单；ARCHITECTURE:317 同步。但 B10:661 的回滚验收只写“先 DROP CHECK、收敛 key、
      分组断言 ACTIVE、Flyway 属性”，V2:665 只写“迁移/回滚/备份恢复（OPS-M04-01）”，都没有绑定该 env、
      WARN 或工单，也没有说明 V2 是独立在一次性 MySQL 上重放回滚，还是仅复核 B10 日志。严格证据绑定下，
      回滚证据可能漏掉最关键的一步。建议 B10 验收补“schema 校验关闭 + 固定 WARN + 工单”，V2 验收补
      “独立重放并核对 WARN/工单，或明确仅复核 OPS-M04-01 日志”。
    evidence: "ADR-0021:661、:665、:750、:796；ARCHITECTURE:317"
  - id: OPS-M04-050
    severity: P2
    status: open
    owner: riccon/architect + riccon/ops (B10) + riccon/lead
    title: "RPO/RTO 与成本对账没有数值目标/容差，演练无法判 pass/fail"
    summary: >-
      ADR-0021:472 与 B10:661 要求 RPO/RTO，但全文没有数值目标；restore-drill 的通过条件只有“表数/触发器/
      一行 credential 可解密/J1 冒烟”。B10:661 还要求“成本对账窗口与告警阈值表”，但没有对账容差/窗口定义
      （ADR-0022:201 只说混合版本窗口不可对账）。没有目标值，restore-drill 与成本对账只能记录“跑过”，
      不能判定通过。建议给 RPO/RTO 数值（或明确由客户 SOW 提供并写入 RUNBOOK），以及对账容差
      （例如金额差异 0、调用量差异上限）。
    evidence: "ADR-0021:472、:661；ADR-0022:201、:298"
  - id: OPS-M04-051
    severity: P3
    status: open
    owner: riccon/lead + riccon/ops (B10)
    title: "B10 是 V2 的 join 前置，但 §8.4 未给 B10 工时估算"
    summary: >-
      §8.4:633 说 B10 与关键路径并行，但 B10:614 依赖 B7；B7 在 16h 完成，V2 在 24h 开始且依赖 B10，
      因此只有 B10 在 8h 内完成时 28h 关键路径才成立。B10 有 10 个 `write_scope` 项（:661）和 8 类脚本/
      5 类演练，没有工时估算；COLLABORATION:41 提示 `write_scope` >8 项应拆分。48h 预算仍有约 20h 余量，
      故不构成 P1，但“48h 可行”的论证缺少 B10 估算。建议 Lead 派单前单独估算 B10 并写入 §8.4。
    evidence: "ADR-0021:614、:633、:661、:673-683；docs/COLLABORATION.md:41"
  - id: OPS-M04-052
    severity: P2
    status: open
    owner: riccon/ops (B10) + riccon/architect
    title: "quota false→true 演练缺少真实超限请求、429/审计/指标与开关回退断言"
    summary: >-
      B10:661 的 quota 演练只写“默认限额/租户用量/失败回滚”，没有要求构造一次真实超限请求来验证
      false→true 后返回 429、写 `quota.exceeded` 审计、增加 `riccon_quota_rejections_total`；
      §10.2:739 又要求发布观测“配额拒绝”，但默认限额 10,000 下正常 smoke 不会产生拒绝。没有超限请求，
      演练只能证明“计量”，不能证明“执行”。建议加：临时把某租户日限额设为已用量+1（或使用测试租户），
      验证 429/审计/指标，再把开关 `PUT false` 验证回退，最后恢复限额并 `PUT true`。
    evidence: "ADR-0021:661、:693、:739、:845；ADR-0022:127-128、:164"
  - id: OPS-M04-053
    severity: P3
    status: open
    owner: riccon/architect + riccon/ops (B10)
    title: "“五段式”标签与实际 7 步不一致；drain 旧 Worker 缺超时判据"
    summary: >-
      §10.2 标题写“五段式”，实际列了 0–6 共 7 步（preflight/备份/迁移 Job/结构校验/新 API/drain Worker/
      开关）；drain 旧 Worker 没有 drain 超时、租约或强制终止判据。属措辞/可执行性，不阻塞。
      建议统一为“preflight + 六步”或“七步”，并在 RUNBOOK 写 drain 上限与在途 run 处理。
    evidence: "ADR-0021:731-741；RUNBOOK:257-269"
  - id: OPS-M04-054
    severity: P3
    status: open
    owner: riccon/ops (B10)
    title: "CI perf job 的“或明确 manual gate”缺少跳过条件"
    summary: >-
      B10:661 允许“CI perf job 或明确 manual gate”。在 T4 与 AC-17 下，这会让流水线长期不跑 perf；
      manual gate 仍可用 `evidence run` 记录 k6，但缺少“何时必须跑、谁批准跳过”的约束。
      建议 CI 至少 nightly/发布前强制跑，manual gate 仅限本地预检。
    evidence: "ADR-0021:661、:910；PRODUCT:361"
  - id: OPS-M04-055
    severity: P3
    status: open
    owner: riccon/lead + riccon/architect
    title: "ARCHITECTURE 评审表与 PRODUCT 测试 ID 映射仍引用旧口径"
    summary: >-
      ARCHITECTURE:11/:23 仍写 Ops pass（REV-M04-OPS-004），而 :9 已写待 REV-M04-OPS-005；本评审后应由
      Lead 更新为 REV-005。另 PRODUCT:357-358 的 AC-13/14 仍引用 `E2E-M04-07`，ADR §11.1 用 07a/07b；
      PRODUCT:360 的 AC-16 引用 `IT-MODEL-108`，ADR 用 108a/108b。属文档映射，不影响本轮 OPS 结论，
      但 gate 证据表应按 ADR 的 07a/07b/108a/108b 登记。
    evidence: "ARCHITECTURE:9、:11、:23；PRODUCT:357-358、:360；ADR-0021:876-877、:907-908"
  - id: OPS-M04-056
    severity: P3
    status: open
    owner: riccon/lead + riccon/product
    title: "ACCEPTANCE 仍写 M13 F-001 与 M03 F-008 由 M04 承接，与 ADR/ARCHITECTURE 冲突"
    summary: >-
      `docs/ACCEPTANCE.md:61` 写 M13 F-001“契约对齐由 M04/M10 承接”，:59 写历史 TENANT 空间“M04/M09 设计
      专用迁移”；但 ADR-0021:687 的 M04 acceptance 明确“R-11、M13 F-001 不承接”，ARCHITECTURE:169 写
      “转换按 DEC-0023 归 M09 专用迁移，M04 不承接”。这是 scope/验收文本冲突，可能让 Product 预验收误判
      M04 范围。建议 Lead/Product 在 M04 gate 前把 ACCEPTANCE 的 M04 引用改为“不承接，归 M09/M10”。
    evidence: "docs/ACCEPTANCE.md:59、:61；ADR-0021:687；ARCHITECTURE:169"
notes: >-
  本轮只读复核 DEC-0024=B 后的单模块 48h 方案，未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/PRODUCT/
  源码/测试/迁移，未创建任务/模块/决策，未提交 git；只新增本评审文件。ADR-0021/0022 仍 proposed，
  本评审不代替 Lead/用户/Verifier/Architect 签字，也不构成发布批准。
---

# REV-M04-OPS-005：单模块 48h 方案的运维/回滚 delta 复核

## 1. 复核范围

对 DEC-0024=B（不拆、M04 单模块 48h、18 条 AC 全保留、17 个实施任务、契约保持 `1.3.0-m04`）后的
ADR-0021/0022、ARCHITECTURE、THREAT-MODEL、contracts 与 TASK-0095/0096 证据做 Ops 视角的 delta 复核。
重点不是重跑实现测试，而是确认发布/回滚、B10 吸收 B13、7+2 观测、迁移证据边界、48h 可行性与文档一致性
在单模块口径下是否仍然可执行、可取证、可回滚。

基线：`HEAD=dd4a6a8f7b5dba8ec627370524c635be4496ccf8`，设计收口提交 `91c2d44`，证据提交 `dd4a6a8`。

## 2. 逐项结论

### 2.1 单模块发布/回滚是否完整

**结论：完整，保留上轮修正；仅余文档措辞 P2/P3。**

- 发布顺序仍在 §10.2:733-739：preflight（Secret/端点/模式/币种/表体积/长事务）→ 备份（含
  `flyway_schema_history`/`tenant_data_key`/`provider_credential`/`SPRING_SESSION`，记录 `master_key_id`）
  → 一次性迁移 Job（只跑 Flyway，不启 Web）→ 结构校验（`MAX(version)=11`、6 张新表、无 FK、CHECK 生效、
  历史 `cost_day` 可 NULL）→ 新 API health → drain 旧 Worker/新 Worker → 冒烟与开关。旧 Worker 不需要在
  迁移前停，但 V11 之后不得重启旧 Worker（:738、:741）。
- 回滚仍是应用回滚 + schema 前向兼容 + 不做 down migration（§10.3:745-752）；应急 SQL 仅用于演练/
  灾难恢复，生产优先备份恢复（:752-794）；旧 API 依赖 `ignore-migration-patterns=*:future` 默认，
  旧 Worker 回滚窗口关闭 schema 校验（:749-750）。
- **B10 是唯一 RUNBOOK/compose writer**：§8.2:642 明确 B1c 与 B10 同 owner 串行，B10 是唯一 RUNBOOK 与
  `docker-compose.test.yml` writer；B10 write_scope 独占 `docs/RUNBOOK.md`、compose、`scripts/ops/*` 与
  `.github/workflows/ci.yml`（:661）。B13 并入 B10 后没有第二个 Ops writer。
- **回滚断言“按分组 ACTIVE、不写死总行数”保留**：§10.3:796 规定按
  `(tenant_id, provider_id, credential_type)` 分组，每组恰好 1 行，有 ACTIVE 则保留 ACTIVE，否则保留
  RETIRED，fixture 可为 1 ACTIVE + 1 RETIRED（总行数 2、ACTIVE 1），不得写死总行数；B1a:649 与
  B10:661 验收同步。**但** §13:1021（S-02）与 :1053（T-01）仍写“每组 ACTIVE=1”，对 provider B 的
  RETIRED-only 组不可满足，见 OPS-M04-042（P2，carry）。
- 发布标题“五段式”实际列出 7 步，且 drain 无超时判据，见 OPS-M04-053（P3）。

### 2.2 B10 吸收 B13 后是否闭环

**结论：主要运维项已吸收，但默认配额配置键、quota 演练判据、RPO/RTO 与对账容差仍未闭环（均 P2）。**

已闭环：

- **quota 开关 false→true**：V11 种子 `quotaEnforcementEnabled=false`（ADR-0021:306、:831）；
  B10:661 要求“只计量不阻断 → 默认限额/租户用量 → 失败回滚 → 显式 `PUT true`”；§10.2:739 要求发布
  观测包含配额拒绝；M04 gate 以 true 取证 AC-08/09（:693）。
- **API/Worker 漂移检查**：B10:661 要求端点/模式/币种/开关漂移检查（`/actuator/info` 指纹）；
  S-12:1031 定义 info 键（deploymentMode/endpointSetHash/masterKeyId/currency/routingMode/
  failoverEnabled/quotaEnforcementEnabled）。
- **成本对账窗口与告警阈值表**：B10:661 明确交付；ADR-0021:845 给出路由/降级/配额/成本告警项。
- **备份恢复/RPO-RTO、主密钥 rewrap、幂等种子/价格、backfill**：B10:661 覆盖
  `rewrap-*`/`seed-*`/`update-*`/`restore-*`/`backfill-*`，`backfill-cost-day` 有干跑/进度/幂等验收；
  §6.5:469-472 给出 rewrap 四步与 restore-drill 校验项。
- **CI perf job**：B10:661 要求“CI perf job 或明确 manual gate + 预算/主机规格记录”。

未闭环：

- **默认配额限额没有命名的部署配置键**：ADR-0022:39 批准默认值，:44/:105 说取部署配置，但 B1a:649 的
  配置键清单没有配额键，全文 `grep RICCON_QUOTA` 0 命中；B10 的“默认限额/漂移检查”没有可核对的键名。
  见 OPS-M04-048（P2）。
- **quota 演练缺少真实超限请求**：只验证“计量”无法证明“执行”，见 OPS-M04-052（P2）。
- **RPO/RTO 无数值目标、成本对账无容差**：见 OPS-M04-050（P2）。
- **CI perf job 的 manual gate 无跳过条件**：见 OPS-M04-054（P3）。
- **`quota_usage` 人工修正工单**（ADR-0022:299）未在 B10 `quota-*` 验收中显式出现，建议写入 RUNBOOK；
  属 P3 残余，不单列 finding。

未发现“M17 时代”还有未吸收的独立运维任务：旧 B13 的 RUNBOOK、`quota-*`/`cost-*`、compose 追加均已
并入 B10 的 write_scope 与验收。

### 2.3 观测 7+2 与 B-20 关闭条件

**结论：7+2 拆分与抓取目标已明确；B-20 的 V2 部分存在测试 ID 归属不一致（P2）。**

- Worker 7 个（B4 注册）：`riccon_model_requests_total{provider,model,outcome}`、
  `riccon_model_failover_total{from,to,reason}`、`riccon_model_attempt_duration_seconds`、
  `riccon_model_credential_invalid_total{provider}`、`riccon_model_credential_cooldown_total{provider}`、
  `riccon_model_chain_empty_total{purpose}`、`riccon_agent_runs_inflight`（ADR-0021:838）。
- API 2 个主指标（B6/B7 注册）：`riccon_quota_rejections_total{scope,window}`、
  `riccon_quota_usage_ratio{scope,window}`；3 个辅助：`riccon_cost_report_queries_total{group_by}`、
  `riccon_quota_store_failure_total`、`riccon_quota_over_limit_observed_total`（:839）。
- **抓取目标明确**：B1c:651 与 §10.6:840 写明 `metrics-check --base-url` 指向 Worker，只断言 7 个；
  V2:665 断言 API 的 2 主 + 3 辅助。上轮 OPS-M04-043/044 已 fixed。
- **B-20 一致**：THREAT:626 要求 `metrics-check` 通过、Worker 7 个存在且标签正确、V2 记录 API 抓取样本
  并覆盖 2 主 + 3 辅助，否则 AC-17 不可 pass；与 V2:665 文本一致。
- **测试 ID 归属缺口**：V2 把“7+2”整体挂在 `OPS-M04-02`，但 `OPS-M04-02` 只定义 Worker 7 个且
  owner 为 B1c/V1（:913）。见 OPS-M04-047（P2）。
- **Prometheus 仅内网 CIDR**：`RICCON_OPS_ALLOWED_CIDRS`（空=loopback），B2 实现、B1c 负向测试
  （:843、:115；THREAT:871）。未见放宽默认拒绝的表述。

### 2.4 迁移/回滚证据边界

**结论：V11 一次建全表、结构冒烟、无 FK/无新增 CHECK 均在；V2 的证据独立性口径需补（P2）。**

- **V11 一次建全表**：B1a:620 明确 V11 同时建路由/key/配额/成本/尝试明细；:649 验收含
  `quota/cost/attempt` 表结构、FK/CHECK、空表 INSERT/ROLLBACK、PK 顺序、默认值断言；:691 规定 gate
  发现表结构错误用 V12 前向修复，不重开 V11。
- **结构+冒烟**：B1a:649 与 V1:664 均含 `information_schema` 无 `fk_model_invocation_provider`、
  `model_invocation` 无新增 CHECK、空表 INSERT/ROLLBACK；V1 另含 V11 结构断言。
- **回滚窗口 Worker schema 校验关闭**：§10.3:750 明确 env、固定 WARN、工单与“只允许 M04 前最后一个
  worker 版本”；ARCHITECTURE:317 同步。
- **不依赖 M09 `deploy_preview`**：§10.2:743 明确 M04 不以 `deploy_preview`/`deploy_prod` 取证，迁移/
  回滚走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`，评审包标 `deploy_preview not-applicable`（M09）。
  V2:665 未引用 `deploy_preview`。
- **V2 独立性**：V2:665 只写“迁移/回滚/备份恢复（`OPS-M04-01`）”，未说明是 V2 独立在一次性 MySQL 上
  重放，还是仅复核 B10 的演练日志；B10/V2 验收也未绑定 schema 校验关闭与固定 WARN。见 OPS-M04-049（P2）。

### 2.5 48h 运维可行性

**结论：48h 预算本身可行，停止条件明确；B10 的 28h 关键路径窗口与体量缺估算（P3）。**

- **B1c/B10 同 owner 串行**：§8.2:642 明确；B1c 早期完成 registry/约定，B10 在依赖齐后独占
  RUNBOOK/compose/CI，不存在两个 Ops 并行写。
- **B10 依赖**：B10:614 依赖 B1a/B1b/B2/B6/B7；B7 在 16h 完成，V2 在 24h 开始且依赖 B10，因此维持
  28h 关键路径时 B10 只有约 8h 窗口（§8.4:633、:673）。48h 预算仍有约 20h 余量，B10 滑到 30h 仍可在
  48h 内 gate；问题在于“B10 与关键路径并行”的表述不精确，且 B10 体量（10 个 write_scope、8 类脚本、
  5 类演练）没有估算。见 OPS-M04-051（P3）。
- **维护窗口与迁移演练**：§10.2:741 明确不能纯滚动，必须维护窗口；迁移/回滚/备份恢复/rewrap 均在
  B10 内，时间上可放进 48h，但 B10 的实际人时需 Lead 在派单前确认。
- **停止条件明确**：§8.4:683 规定 48h 未达 gate 暂停并向 Lead/用户报告重估；超过 2 倍（96h）暂停询问
  用户；不得自行扩预算、静默降级 DoD、后移 AC 或重启拆分。**通过**。

### 2.6 文档一致性

**结论：无撤销 ID 残留（仅允许的 S-06 一处）；ADR 仍 proposed；契约未改且为 1.3.0-m04。**

- 禁用 ID grep（`M17|B12|B13|B-18a|B-18b|B-20a|B-20b|1.4.0-m04b`）在
  `docs/adr docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md docs/RUNBOOK.md contracts/README.md`
  只命中 ADR-0021:1025 的 S-06“原 B-20a”，属任务允许的一处；其余 0 命中。
- ADR-0021:3 与 ADR-0022:3 仍 `Status: proposed`；Approved-by 绑定 DEC-0024=B，Review-date 待
  REV-M04-ARCH-004 / REV-M04-OPS-005，符合“本评审不转 accepted”。
- `contracts/openapi.yaml` 未在 `91c2d44`/`dd4a6a8` 中修改（`git show --stat` 未列该文件），
  YAML 解析为 `openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`；contracts/README:5 也写
  `1.3.0-m04`。
- PRODUCT:31 的“不创建 M17/M04b”是 DEC-0024=B 的历史记录，不在本轮禁用 grep 的目标文件内；建议
  后续文档统一写成“已撤销拆分”，避免 grep 型 gate 误报（P3 残余，不单列 finding）。
- ARCHITECTURE:11/:23 仍写 Ops pass（REV-M04-OPS-004），:9 已写待 REV-005；PRODUCT AC-13/14/16 的
  测试 ID 仍是 `E2E-M04-07`/`IT-MODEL-108`，ADR 已拆 07a/07b 与 108a/108b。见 OPS-M04-055（P3）。
- `docs/ACCEPTANCE.md:59/:61` 仍写 M13 F-001 与历史 TENANT 空间转换由 M04 承接，而 ADR-0021:687 与
  ARCHITECTURE:169 写“M04 不承接”；这是验收范围文本冲突，见 OPS-M04-056（P3）。

## 3. 证据命令与输出摘要

| 命令                                                                                                                                                                 | 退出码 | 输出摘要                                                                                                                                                                                        |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git rev-parse HEAD`                                                                                                                                                 | 0      | `dd4a6a8f7b5dba8ec627370524c635be4496ccf8`                                                                                                                                                      |
| `git log --oneline -3`                                                                                                                                               | 0      | `dd4a6a8` 设计收口任务 done（EV-0522/0523）；`91c2d44` ADR/架构/威胁模型收口为单模块 48h（17 任务）；`f63c9db` PRODUCT 预算同步（DEC-0024=B）与模块启动                                         |
| `git status --porcelain`                                                                                                                                             | 0      | 评审前只有 `.riccon/state.json`、`.riccon/tasks/TASK-0089.json` 两处既有修改；本轮只新增本评审文件，未改其他文件                                                                                |
| `grep -rn "M17\|B12\|B13\|B-18a\|B-18b\|B-20a\|B-20b\|1\.4\.0-m04b" docs/adr docs/ARCHITECTURE.md docs/security/THREAT-MODEL.md docs/RUNBOOK.md contracts/README.md` | 0      | 仅 `ADR-0021:1025` 的 S-06“原 B-20a”，属允许项；其余 0 命中                                                                                                                                     |
| `grep -n "metrics-check\|7+2\|quota.*false→true\|成本对账\|rewrap\|RPO" docs/adr/ADR-0021*.md docs/adr/ADR-0022*.md docs/security/THREAT-MODEL.md`                   | 0      | 命中 B1c:651、B10:661、§10.6:839-840、V2:665、OPS-M04-02:913、B-20:626、TM-54:563、ADR-0022:128 等                                                                                              |
| `grep -rn "QUOTA_DEFAULT\|quota.*default\|default.*limit\|限额.*配置\|RICCON_QUOTA" docs contracts apps/api/src/main/resources`                                      | 0      | 只有 ADR-0022 的“部署默认”文字（:39/:44/:105），没有任何 `RICCON_QUOTA_*` 键名；默认配额键缺口成立                                                                                              |
| `ruby -ryaml -e '...YAML.load_file("contracts/openapi.yaml")...'`                                                                                                    | 0      | `openapi=3.1.0 version=1.3.0-m04 paths=88 schemas=154`                                                                                                                                          |
| `docker compose -f deploy/compose/docker-compose.test.yml config -q`                                                                                                 | 0      | 编排可解析（Docker 可用，无降级）                                                                                                                                                               |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict`                                                                                       | 0      | `Project contract: OK (0 errors, 0 warnings)`                                                                                                                                                   |
| `git show --stat --oneline 91c2d44`                                                                                                                                  | 0      | 只改 `contracts/README.md`、`docs/ARCHITECTURE.md`、ADR-0021/0022、`docs/adr/README.md`、`docs/security/THREAT-MODEL.md`；未改 `contracts/openapi.yaml`                                         |
| `git show --stat --oneline dd4a6a8`                                                                                                                                  | 0      | 只改 `.riccon/evidence`、`state.json`、TASK-0095/0096；未改契约                                                                                                                                 |
| `cat .riccon/evidence/TASK-0095/EV-0522.json`、`TASK-0096/EV-0523.json`                                                                                              | 0      | 均 `manual:false`、`dirty:false`、`exit_code:0`、commit `91c2d44`；EV-0522 校验 PRODUCT M04 段 36 行 AC 与无 M17 计划；EV-0523 校验 6 个目标文件无 M17/M04a/M04b/分段残留、17 任务与 B-18/19/20 |

**未运行（按只读设计复核约束）：** `./mvnw verify`、`pnpm -w *`、k6、E2E、a11y、Prometheus 真实抓取、
真实 V11 迁移（`V11__model_gateway_depth.sql` 尚不存在）、B10 RUNBOOK/脚本（尚未创建）、备份恢复/rewrap
演练。本文不对覆盖率、p95、E2E、迁移执行结果作通过性判断。

## 4. Findings

### P0/P1

无。没有发现新的 P0/P1；`verdict=pass`。

### P2

| ID          | 状态          | 摘要                                                                                                                                            |
| ----------- | ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| OPS-M04-042 | open（carry） | §13 T-01/S-02 仍写“每组 ACTIVE=1”，与 §10.3 的“每组 1 行、优先 ACTIVE、否则 RETIRED”不一致；provider B 的 RETIRED-only 组按字面会失败。         |
| OPS-M04-047 | open          | V2 把“7+2”整体挂在 `OPS-M04-02`，但该 ID 只定义 Worker 7 个且 owner=B1c/V1；API 2 主 + 3 辅助没有独立 ID/owner。                                |
| OPS-M04-048 | open          | 默认配额限额没有命名的部署配置键，B1a 配置键清单未含配额；B10 无法做默认限额 preflight/漂移检查。                                               |
| OPS-M04-049 | open          | B10/V2 回滚验收未绑定 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false`、固定 WARN、工单，也未说明 V2 独立重放还是仅复核 B10 日志。               |
| OPS-M04-050 | open          | RPO/RTO 无数值目标；成本对账无窗口/容差定义，restore-drill 与对账无法判 pass/fail。                                                             |
| OPS-M04-052 | open          | quota false→true 演练缺少真实超限请求、429/`quota.exceeded` 审计/`riccon_quota_rejections_total` 与开关回退断言，只能证明“计量”不能证明“执行”。 |

### P3

| ID          | 状态 | 摘要                                                                                                                                   |
| ----------- | ---- | -------------------------------------------------------------------------------------------------------------------------------------- |
| OPS-M04-051 | open | B10 是 V2 的 join 前置，维持 28h 路径只有 8h 窗口；B10 有 10 个 write_scope、8 类脚本、5 类演练但无工时估算；48h 预算仍有余量，故 P3。 |
| OPS-M04-053 | open | “五段式”实际 7 步；drain 旧 Worker 缺超时/租约判据。                                                                                   |
| OPS-M04-054 | open | CI perf job 的“或明确 manual gate”无跳过条件/批准人。                                                                                  |
| OPS-M04-055 | open | ARCHITECTURE 评审表仍写 OPS-004；PRODUCT AC-13/14/16 仍用 07/108，ADR 已拆 07a/07b、108a/108b。                                        |
| OPS-M04-056 | open | ACCEPTANCE:59/:61 仍写 M13 F-001 与 M03 F-008 由 M04 承接，ADR:687 与 ARCHITECTURE:169 写“不承接”；scope/验收文本冲突。                |

### 已关闭（本轮 delta）

| ID          | 上轮 | 本轮  | 理由                                                                                                |
| ----------- | ---- | ----- | --------------------------------------------------------------------------------------------------- |
| OPS-M04-043 | open | fixed | `metrics-check --base-url` 指向 Worker；B4/B6/B7 验收绑定指标注册（ADR-0021:651/654/656/657/840）。 |
| OPS-M04-044 | open | fixed | V2:665 覆盖 API 2 主 + 3 辅助；B-20:626 一致。                                                      |
| OPS-M04-039 | open | fixed | 单模块 §8.4:673 明确 B7 3h，原 M17 17h 隐含 B7≈1h 的问题随拆分撤销消失。                            |
| OPS-M04-040 | open | fixed | B1c:651 把 Actuator 暴露归 B2，只做 registry/约定/负向测试。                                        |
| OPS-M04-046 | open | fixed | §13 F-021 已 superseded（:1010），M17 工时数字不再现行。                                            |

## 5. 未验证项与残余风险

**未验证：**

- `V11__model_gateway_depth.sql` 尚不存在，未在真实 MySQL 8.4.13 上验证 DDL 耗时、复制延迟、磁盘余量与
  `>200 万行/2 GiB` 停写判据；B1a 实施后需重跑 REV-003 的迁移探针。
- B10 的 RUNBOOK、`scripts/ops/*`、compose 漂移检查、CI perf job 尚未创建；本评审只核对设计与验收文本。
- 备份恢复/rewrap/quota false→true/成本对账均为设计验收项，未实际演练。
- 7+2 指标未真实抓取；`metrics-check` 脚本未运行。
- 48h 排期为估算，未用实际工时验证；B10 人时未估算。
- M04 当前只有 TASK-0089/0095/0096 三个设计任务，17 个实施任务尚未创建（`.riccon/modules/M04.json`
  `tasks` 仅列这三个）；这是 Lead 的派单前置，不是本轮设计 fail。

**残余风险：**

- B10 体量大且是 V2 的前置；若 B10 超过 ~12h，28h 关键路径失效，但 48h 预算仍有约 20h 余量，需 Lead
  在派单前确认 B10 估算与 B1c/B10 串行窗口。
- 默认配额配置键缺失（OPS-M04-048）可能导致 B6 硬编码默认值，客户无法按 DEC-0024 调整，或 B10 的
  “默认限额漂移检查”退化为只读 API 断言。
- 回滚窗口 schema 校验关闭是已批准的风险接受项；若 B10 RUNBOOK/V2 证据漏掉 WARN/工单，回滚演练可能
  “跑过但不可审计”（OPS-M04-049）。
- RPO/RTO 与对账容差未量化（OPS-M04-050）；T4 发布评审前必须由 Lead/用户给出目标或明确由客户 SOW
  提供，否则 `OPS-M04-01` 的通过标准不完整。
- 回滚矩阵措辞（OPS-M04-042）仍可能诱导演练者删除 RETIRED 种子或弱化断言；规范文本 §10.3 是权威，
  但 §13 矩阵应在下一次文档修订时统一。
- ADR-0021/0022 仍 `proposed`；本评审通过不等于 ADR accepted，也不等于用户批准发布。DEC-0003 的
  MySQL Connector/J 许可仍是 release blocker（合同既有）。

## 6. 边界声明

- 本轮只新增 `.riccon/reviews/tasks/M04-ops-review-005.md`；未修改 ADR/契约/ARCHITECTURE/THREAT-MODEL/
  PRODUCT/源码/测试/迁移，未创建任务/模块/决策，未提交 git。
- 不修改 ADR 状态；不标记 TASK-0089 done；不代替 Lead/用户/Verifier/Architect 签字；不构成任何发布批准。
- 评审基于 `dd4a6a8` 的设计文本与 `91c2d44` 的收口提交；后续实现变更需重新核对。
