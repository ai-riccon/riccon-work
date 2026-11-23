---
review_id: REV-TASK-0210-001
module_id: M25
task_id: TASK-0210
reviewer:
  agent: riccon/verifier
  session: ses_df5d27f89ffeJEB4oT7zB2lUc0
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 68e3f0360c02a7d909d98c6eff8ffd03a4601b66
  - ece49eb5301c7302b806a7aae3d109dd42512ca4
  - 712f3b6e3dd698b54d813e731d8d5dcd5a86b513
  - 572ed85a625ab89735fa11a024f3b36260d3defa
head_commit: 572ed85a625ab89735fa11a024f3b36260d3defa
baseline_commit: c3c8d2c788383fbc7f51e283d1bc55a8b9e90a9d
dod:
  acceptance: true
  docs: true
  security: true
  review: true
  evidence: true
dod_not_applicable:
  - build
  - tests
  - e2e
  - coverage
  - migration
  - perf
  - accessibility
dod_note: >-
  O2a 是部署配置与 RUNBOOK 任务，适用 acceptance/docs/evidence/review；build/tests/e2e/coverage
  无代码改动不适用，migration/perf/a11y 亦不适用。security 只指 O2a 的网络底座与 allowlist/SSRF 规则文档；
  B6（TASK-0204）的代码级 allowlist/SSRF 拒绝不在本任务，未在此验证，不能把本 review 当成
  SEC-CONN-003 代码级通过。返工 712f3b6 已关闭 F-001/F-002/F-004；F-003 为 gate 审计口径伪差异，
  不影响本任务 acceptance。
reviewed_evidence:
  - EV-0961
  - EV-0962
  - EV-0964
  - EV-0965
evidence:
  - EV-0961
  - EV-0962
  - EV-0964
  - EV-0965
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: riccon/ops
    due: fixed in 712f3b6
    title: "RUNBOOK 的 RFC1918/DNS rebinding 规则与示例、ADR §6.5 及 M04 复用规则冲突"
    summary: >-
      原 docs/RUNBOOK.md:1155 写“主机名解析到 RFC1918 视为拒绝（防 DNS rebinding）”，但 :1130 的本地示例
      http://object-storage:9000 在 compose 内解析为 172.18.0.4（本评审实测 `getent hosts object-storage`），
      按该行字面会被拒绝；ADR-0023 §6.5 只要求 RFC1918 在端点显式列出时放行（“内部 S3 的常态”），
      platform/platform-common/.../ManagedEndpointPolicy.java:244-281 也只拒绝元数据/链路本地/生产回环，
      ManagedEndpointPolicyTest.java:147-154 明确允许 allowlist 内私网 DNS。
    closure: >-
      712f3b6 将规则改为“未列入 allowlist 的主机名/重定向目标解析到 RFC1918 一律拒绝”，并新增“私网解析
      正例/反例”与 B6 测试要求（docs/RUNBOOK.md:1155-1164）。这与 ADR/M04 一致：显式 allowlist 条目允许
      私网 DNS，未列入/重定向目标拒绝；本地 object-storage 示例不再被字面拒绝。独立核对 diff 确认关闭。
    evidence: >-
      docs/RUNBOOK.md:1155-1164；git show 712f3b6；docs/adr/ADR-0023-m05-knowledge-depth.md:601-602；
      platform/platform-common/src/main/java/com/riccon/platform/common/model/ManagedEndpointPolicy.java:244-281；
      platform/platform-common/src/test/java/com/riccon/platform/common/model/ManagedEndpointPolicyTest.java:147-154。
  - id: F-002
    severity: P2
    status: fixed
    owner: riccon/ops
    due: fixed in 712f3b6
    title: "RUNBOOK 第 4 步 down 未带 --profile，实测 exit 0 但遗留 internal 网络（假绿）"
    summary: >-
      原 docs/RUNBOOK.md:1185 的 `docker compose -p riccon-o2a-egress -f ... down --remove-orphans` 没有
      `--profile connector-egress`。按原命令执行时第 3 步探针 PASS、第 4 步 exit 0，但 `docker network ls`
      仍显示 `riccon-o2a-egress_riccon-connector-egress`；只有加 `--profile connector-egress` 的 down 才输出
      `Network ... Removed`。EV-0961 日志同样没有网络删除行。第 1 步 `config` 不带 profile 时也不包含
      新服务/网络，不能单独验证 profile 定义。
    closure: >-
      712f3b6 让第 1 步和第 4 步都带 `--profile connector-egress`，并在第 4 步后用
      `docker network inspect riccon-o2a-egress_riccon-connector-egress` 断言网络不存在（残留则打印 FAIL 并
      返回 1）；RUNBOOK:50 补充“profile-only 网络不会被普通 down -v 清理”。EV-0964 记录该完整命令 exit 0、
      日志含 `Network ... Removed` 与 `OK: 演练网络已删除`；本评审独立复跑得到同样结果（见“独立复验”）。
    evidence: >-
      docs/RUNBOOK.md:50、:1176-1179、:1194-1202、:1215-1219；git show 712f3b6；
      EV-0964 日志（`Network riccon-o2a-egress_riccon-connector-egress Removing/Removed`、`OK: 演练网络已删除`）；
      独立复验：带 profile 的 config/run/down + `docker network inspect` 不存在。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      纯审计口径问题，不是任务代码越界：TASK-0210 的实际实现/返工提交（68e3f03、712f3b6）只改
      deploy/compose/docker-compose.test.yml 与 docs/RUNBOOK.md；gate 使用 baseline..HEAD，会把并行
      B2b 契约提交、d9e2cb0 记账提交和 docs/ACCEPTANCE.md 一起算到 TASK-0210。无需修改任务产物；
      Lead 在 closeout/模块 review 说明即可，无生产风险。
    title: "gate check 把并行 B2b/记账提交计入 TASK-0210 越界（审计口径）"
    summary: >-
      `riccon gate check --module M25` 报 “TASK-0210 changed files outside write_scope” 并列出
      apps/api/.../contract/*.java、contracts/openapi.yaml、contracts/README.md、docs/ACCEPTANCE.md 等；
      这些来自 TASK-0203/B2b（3928aee、d605ed1 等）和 d9e2cb0（M23/M24 用户验收记账），不属于 TASK-0210。
      实际任务范围 `git diff --name-status c3c8d2c..68e3f03`（两文件）与 `68e3f03..712f3b6`（仅 RUNBOOK）
      均未越界。M25 gate 本身因 10 个任务未完成且无模块 review 而失败，属预期；本条只记录 scope 计算伪差异。
    evidence: >-
      `riccon gate check --module M25` 输出；`git diff --name-status c3c8d2c..68e3f03`；
      `git diff --name-status 68e3f03..712f3b6`；`git show --stat d9e2cb0`；
      `git show --no-patch --format='%P' 68e3f03`；`git diff --name-status d9e2cb0..ece49eb`。
  - id: F-004
    severity: P3
    status: fixed
    owner: riccon/ops
    due: fixed in 712f3b6
    title: "RUNBOOK M05b-5 观测行错误码省略 KB_CONNECTOR_ 前缀，与 ADR §6.6/下表不一致"
    summary: >-
      原 docs/RUNBOOK.md:1220-1221 写 `last_error_code` 取 `KB_CONNECTOR_AUTH`/`UNREACHABLE`/`TIMEOUT`/`CREDENTIAL`，
      但 ADR-0023 §6.6 和同节表格使用 `KB_CONNECTOR_AUTH`/`KB_CONNECTOR_UNREACHABLE`/`KB_CONNECTOR_TIMEOUT`/
      `KB_CONNECTOR_CREDENTIAL`。
    closure: >-
      712f3b6 已改为完整前缀 `KB_CONNECTOR_UNREACHABLE`/`KB_CONNECTOR_TIMEOUT`/`KB_CONNECTOR_CREDENTIAL`
      （docs/RUNBOOK.md:1238-1241），与 ADR §6.6/下表一致；独立核对 diff 确认关闭。
    evidence: >-
      docs/RUNBOOK.md:1238-1241；git show 712f3b6；docs/adr/ADR-0023-m05-knowledge-depth.md:609-619。
test_impact: >-
  本任务无 Java/测试改动，未跑 Maven/pnpm 测试；独立执行了 `docker compose --profile connector-egress config`、
  `--network none` 路由/外网探针、`connector-egress` internal 网络探针、带 profile 的 down 与网络不存在断言、
  `bash scripts/sandbox.sh`，并核对 EV-0961/EV-0962/EV-0964/EV-0965 的命令、提交、dirty=false 与日志哈希。
  本次复验不注册为 TASK-0210 正式 EV，只用于评审结论。
notes: >-
  EV-0961/EV-0962/EV-0964/EV-0965 的 agent 为 riccon/lead（任务 owner 是 riccon/ops），内容已独立复现。
  返工 712f3b6 仅改 docs/RUNBOOK.md（+30/−11）；merge 572ed85 同时带入 B2b 契约/测试与记账提交，
  gate 会把这些并行提交误算到 TASK-0210（F-003）。M25 gate 当前失败是预期：10 个任务未完成、无模块 review。
  B6 代码级 SSRF、M09 K8s NetworkPolicy 和真实连接器/Worker 的零出网均未在本任务验证。B6 新增的
  “私网 DNS 正/反例测试”需要 Lead 确认 TASK-0204 write_scope（当前只列 main 路径）是否包含测试文件。
created_at: "2026-11-24T04:59:26+08:00"
updated_at: "2026-11-24T05:08:26+08:00"
---

# REV-TASK-0210-001：TASK-0210（M25-O2a 连接器出网 allowlist/网络策略）独立评审

## 结论

**通过（pass）。** 返工提交 `712f3b6` 仅修改 `docs/RUNBOOK.md`（+30/−11），已关闭 F-001、F-002、F-004；
F-003 仍是 gate 的审计口径伪差异，不影响任务验收。无新的 P0/P1。当前 main HEAD `572ed85` 为返工 merge，
EV-0964/EV-0965 已按当前 HEAD 录制并且内容独立复现。

O2a 的两个交付物仍在任务卡 write_scope 内：`deploy/compose/docker-compose.test.yml` 新增
`riccon-connector-egress`（`internal: true`、默认无服务接入）与仅 profile 启用的 `connector-egress-probe`；
`docs/RUNBOOK.md` 覆盖端点允许列表、B6 归口的 SSRF 规则、零出网验证、开关/回滚与 M09 交接。原始实现提交
`68e3f03` 与其父 `c3c8d2c` 之间只有 compose 与 RUNBOOK 两个文件，没有 Java、contracts 或 `.riccon` 生产改动；
返工 `68e3f03..712f3b6` 只有 RUNBOOK。

本 pass 只代表 TASK-0210 的部署配置/RUNBOOK/网络底座验收，**不代表** B6 的代码级 SSRF/allowlist 实现、
M09 的生产 NetworkPolicy 或 M25 模块 gate。任务可进入 done；B6 启动前仍需按 RUNBOOK 实现代码级拒绝与测试。

## 验收对账

任务卡验收原文：**“连接器端点 allowlist/网络策略；零出网默认；B6 前置；RUNBOOK/验证”**。

| 验收子项                                                                                                                      | 结论 | 证据/说明                                                                                                                                                                                                                                                                                                                                          |
| ----------------------------------------------------------------------------------------------------------------------------- | ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RICCON_CONNECTOR_ENDPOINTS` 格式（`id`/`url`/`label`/`allowHttp`/`production`）与默认空=零出网，与 ADR §6.5 一致             | pass | `docs/RUNBOOK.md:1113-1144` 字段与 ADR-0023 §6.5:601 一致；未设置/`[]` 为空 + `RICCON_CONNECTOR_ENABLED=false` 默认（`apps/worker/src/main/resources/application.yml:71`）→ 连接器不可用；空列表无法创建连接器明确归 B6 fail-closed                                                                                                                |
| SSRF 拒绝规则写清（HTTPS/allowHttp、无 userinfo/query/redirect、169.254/16、::1、fe80::/10、RFC1918 仅显式），并明确判定归 B6 | pass | `docs/RUNBOOK.md:1146-1167` 列出全部规则并声明“判定归 B6，Ops 不绕过”；`169.254.0.0/16`、`::1`、`fe80::/10`、userinfo/query/redirect 与 ADR §6.5:602 一致；F-001 已由 `712f3b6` 关闭：`allowlist` 显式条目允许私网 DNS，未列入/重定向目标拒绝（`:1155-1164`）                                                                                      |
| 测试栈 `riccon-connector-egress` 是否 internal=true、默认无服务接入；探针是否只证明网络底座、不冒充 B6 代码级拒绝             | pass | `deploy/compose/docker-compose.test.yml:150-156` `internal: true`；`:94-140` 探针仅接入该网络，profile 默认不启动；`docker compose --profile connector-egress config` 确认无其他服务接入；RUNBOOK:1211-1213 明确“测试栈只证明网络底座，真实出网拒绝由 B6 SEC-CONN-003 与 M09 NetworkPolicy 承担”；F-002 的 profile 清理已由 `712f3b6`/EV-0964 关闭 |
| 现有 `riccon-test-net`/MySQL/OpenSearch/RustFS 未被静默改坏；`scripts/sandbox.sh` 证据仍有效                                  | pass | diff `c3c8d2c..68e3f03` 仅新增服务/网络，现有三个服务定义未改；EV-0962/EV-0965 日志 `sandbox-probe-ok` + 三服务 healthy，hash 匹配；本评审独立运行 `bash scripts/sandbox.sh` 同样 exit 0、三服务 healthy                                                                                                                                           |
| 回滚路径（`RICCON_CONNECTOR_ENABLED=false`）与 M09 NetworkPolicy 交接清楚                                                     | pass | `docs/RUNBOOK.md:1221-1234` 回滚四步（API+Worker 同键 false、重启、验证审计/指标、数据保持）；`:1211-1213` 明确 M09 NetworkPolicy/客户防火墙，M09 前无 NetworkPolicy 记 release blocker；`docs/RUNBOOK.md:1254-1257` 凭据 30 天置空交接 M09/O2b                                                                                                    |

## 验证了什么

### 静态审查与范围

- 原始实现 diff `c3c8d2c..68e3f03`：
  - `deploy/compose/docker-compose.test.yml`：+55 行，新增 `connector-egress-probe`（profile `connector-egress`、只读、cap-drop ALL、no-new-privileges）与 `riccon-connector-egress`（`driver: bridge`、`internal: true`）。
  - `docs/RUNBOOK.md`：+141 行，新增 M05b-1..5 章节、顶部一行索引、故障恢复一行。
  - `git diff --name-status` 确认无 Java、contracts、`.riccon`、前端或测试文件；`git diff --check` exit 0。
- 返工 diff `68e3f03..712f3b6`：仅 `docs/RUNBOOK.md`，+30/−11；`git diff --check` exit 0。改动覆盖：
  - F-001：`:1155` 规则改写 + `:1158-1164` 私网正例/反例与 B6 测试要求。
  - F-002：`:50` 补充 profile-only 网络清理说明；`:1176-1179` config 带 profile；`:1194-1202` down 带 profile + 网络不存在断言；`:1215-1219` 验证记录更新。
  - F-004：`:1238-1241` 完整 `KB_CONNECTOR_*` 错误码。
- 提交关系：`68e3f03` 的父为 `c3c8d2c`；`712f3b6` 的父为 `68e3f03`；main HEAD `572ed85` 为 `8d96d04`（含 B2b 契约/测试）与 `712f3b6` 的 merge。B2b/记账文件不属于 TASK-0210（F-003）。
- 未发现密钥、凭据或调试输出进入 diff；测试凭据仍为既有占位值。

### 独立复验（main 工作树，HEAD `572ed85`，2026-11-24）

1. **compose 解析（带 profile）**
   - `docker compose --profile connector-egress -f deploy/compose/docker-compose.test.yml config >/dev/null` → exit 0。
   - 输出包含 `connector-egress-probe`，且 `riccon-connector-egress` 为 `driver: bridge` + `internal: true`；默认 `config --services` 仍只有 mysql/object-storage/opensearch，加 profile 才出现探针。
2. **RUNBOOK §M05b-3 第 3 步（原命令，项目 `riccon-o2a-egress`）**
   - `docker compose -p riccon-o2a-egress --profile connector-egress -f deploy/compose/docker-compose.test.yml run --rm --no-deps connector-egress-probe`
   - 结果：路由表只有 internal 网段（`000013AC` 一类）、无默认路由；1.1.1.1:443 与 169.254.169.254:80 均被拒绝；末行 `connector-egress-probe: PASS`，exit 0。
3. **清理与网络不存在断言（F-002 修复）**
   - `docker compose -p riccon-o2a-egress --profile connector-egress -f deploy/compose/docker-compose.test.yml down --remove-orphans` → exit 0，日志含 `Network riccon-o2a-egress_riccon-connector-egress Removing/Removed`。
   - `docker network inspect riccon-o2a-egress_riccon-connector-egress` → 不存在；与 EV-0964 的 `OK: 演练网络已删除` 一致。
4. **`--network none` 对照**
   - 同参数 `cat /proc/net/route` → 只有表头；`/dev/tcp/1.1.1.1/443` → `external connect exit=1`，与 RUNBOOK 期望一致。
5. **测试栈回归**
   - `bash scripts/sandbox.sh` → exit 0；`sandbox-probe-ok`；MySQL/OpenSearch/RustFS healthy；与 EV-0965 日志一致。

### 证据审计

| 证据    | 提交    | 执行者      | 结果                                                           | 复核                                                                                                                      |
| ------- | ------- | ----------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| EV-0961 | ece49eb | riccon/lead | exit 0；compose config + 探针 PASS + down                      | commit/dirty=false 正确；日志 sha256 `0217bf22…` 与 JSON 一致；探针结果独立复现；旧 down 未真正清理网络（F-002 历史证据） |
| EV-0962 | ece49eb | riccon/lead | exit 0；sandbox attestation + 三服务 healthy                   | commit/dirty=false 正确；日志 sha256 `38f8c190…` 与 JSON 一致；独立复跑 exit 0                                            |
| EV-0964 | 572ed85 | riccon/lead | exit 0；profile config + 探针 PASS + profile down + 网络已删除 | commit/dirty=false 正确；日志 sha256 `2f1c29c7…` 与 JSON 一致；独立复跑同样 PASS/网络不存在                               |
| EV-0965 | 572ed85 | riccon/lead | exit 0；sandbox attestation + 三服务 healthy                   | commit/dirty=false 正确；日志 sha256 `e06f7261…` 与 JSON 一致；独立复跑 exit 0                                            |

四份 EV 均为非 manual、exit 0、绑定提交且日志哈希匹配；EV-0964/EV-0965 绑定返工后的 merge `572ed85`，
与当前 main HEAD 一致。EV 由 Lead 而非任务 owner `riccon/ops` 录制，是流程事实，不是证据失效。

## 未验证

- **B6 代码级 allowlist/SSRF 拒绝**：本任务明确不实现；`RICCON_CONNECTOR_ENDPOINTS` 解析、`SEC-CONN-003` 的“不发起连接”拒绝、重定向/DNS rebinding 代码级测试均归 TASK-0204，未在本 review 验证。
- **真实连接器/Worker 的零出网**：测试栈不启动 API/Worker，`riccon-connector-egress` 目前无任何实际服务接入；本任务只证明网络底座。B6 接入后需重新验证连接器容器的实际网络路径；生产由 M09 NetworkPolicy/客户防火墙承担，尚未交付。
- **M09 NetworkPolicy**：M09 仍为 planned；RUNBOOK 明确把“无 NetworkPolicy”登记为 release blocker，但本任务不能证明生产已具备该能力。
- **完整模块 gate**：M25 仍在 building（10 个任务未完成、无模块 review）；本次未跑合同全量 build/test/coverage/E2E，也不应把本任务 review 当成 M25 gate。
- **IPv6 专项探针**：探针断言的是 IPv4 `/proc/net/route` 与 IPv4 外部/元数据地址；Docker 网络当前 `EnableIPv6=false`，未单独验证 IPv6 默认路由/外部地址。若 M09 或客户网络启用 IPv6，需补同类断言（不改变本任务当前结论）。
- **B2b 契约一致性**：`contracts/openapi.yaml` 已为 `1.5.0-m05b`（B2b 交付，TASK-0203 仍在 review）；本任务未验证契约 `endpointId`/错误码与 B6 实现的一致性，B6 启动前需确认。

## Findings

| ID    | 级别 | 状态     | 摘要                                                                                                      | Owner / Due                   |
| ----- | ---- | -------- | --------------------------------------------------------------------------------------------------------- | ----------------------------- |
| F-001 | P2   | fixed    | RUNBOOK RFC1918/DNS rebinding 规则与 `object-storage` 示例、ADR §6.5、M04 私网 DNS 规则冲突；712f3b6 修正 | riccon/ops / fixed in 712f3b6 |
| F-002 | P2   | fixed    | RUNBOOK 第 4 步 down 未带 profile，exit 0 却遗留 internal 网络（假绿）；712f3b6 + EV-0964 修正            | riccon/ops / fixed in 712f3b6 |
| F-003 | P3   | accepted | gate 把并行 B2b/记账提交计入 TASK-0210 越界；实际任务提交仅 compose + RUNBOOK                             | riccon/lead / M25 gate 前     |
| F-004 | P3   | fixed    | RUNBOOK M05b-5 观测行错误码省略 `KB_CONNECTOR_` 前缀；712f3b6 改为完整前缀                                | riccon/ops / fixed in 712f3b6 |

F-001/F-002/F-004 的 closure 分别引用 `712f3b6` 的具体行与 EV-0964；F-003 仍为 accepted 的审计口径 residual，
不是任务代码缺陷。无 open P0/P1/P2/P3。

## 剩余风险

1. **B6 代码级实现**：RUNBOOK 已把私网 DNS 正/反例规则写清；B6 必须按 `712f3b6` 后的规则实现并补测试，不能引用 O2a 探针替代 `SEC-CONN-003`。
2. **B6 测试写范围**：RUNBOOK 要求 B6 补“私网 DNS 正例/反例”测试，但 TASK-0204 的 write_scope 目前只列 `apps/*/src/main/java/**`；Lead 在启动 B6 前需确认测试文件由 B6 还是 V1b/V2b 承接。
3. **生产出网策略未交付**：M09 前生产没有 NetworkPolicy；RUNBOOK 已要求发布单登记 release blocker，这是 release 级残余，不是 O2a 缺陷。
4. **零出网只到网络底座**：真实连接器/Worker 尚未接入；B6/M09 必须分别完成代码级拒绝与生产网络策略。
5. **F-003 审计口径**：gate 仍会把 B2b/记账提交算到 TASK-0210；Lead 需在 closeout/模块 review 说明实际范围。
6. **契约时序**：B2b 已交付 1.5.0-m05b，但 TASK-0203 仍在 review；B6 开始前需确认契约字段/错误码与固定后的 RUNBOOK 一致。
7. **IPv6**：当前探针只覆盖 IPv4；若生产/客户网络启用 IPv6，需在 M09 增加同类验证。

## 下一步

- **建议 Lead 可将 TASK-0210 标 done**：F-001/F-002/F-004 已 fixed，F-003 为 accepted 审计 residual；任务证据使用 EV-0961/EV-0962/EV-0964/EV-0965，end_commit=712f3b6，head=572ed85。
- **B6（TASK-0204）启动前**：按 `712f3b6` 后的 RUNBOOK 实现私网 DNS 正/反例与完整错误码；确认 TASK-0204 write_scope 是否覆盖测试文件；确认 1.5.0-m05b 契约字段。
- **M25 gate 前**：Lead 说明 F-003 的 baseline..HEAD 伪差异；B6/M09 完成代码级 SSRF 与生产 NetworkPolicy 后，V1b/V2b 再做整条模块验证。
- 本评审不替代 M25 模块 review、Product 预验收或用户验收。
