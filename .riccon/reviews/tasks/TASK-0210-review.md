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
head_commit: ece49eb5301c7302b806a7aae3d109dd42512ca4
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
  SEC-CONN-003 代码级通过。acceptance 项因 F-001 标为 pass (with residual)。
reviewed_evidence:
  - EV-0961
  - EV-0962
evidence:
  - EV-0961
  - EV-0962
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/ops
    due: B6（TASK-0204）启动前
    accepted_reason: >-
      ADR-0023 §6.5 与 M04 ManagedEndpointPolicy 已明确“RFC1918 仅在端点显式允许时放行”，M04 测试
      validateConfiguredUrl_allowsPrivateDnsAndRejectsUnresolved 也允许 allowlist 内私网 DNS；本行新增的
      “主机名解析到 RFC1918 视为拒绝”是文档歧义，运行时代码尚未实现，不影响已交付的 internal 网络。
      风险是 B6 若照字面实现会拒绝文档中的本地示例并造成返工，故要求在 B6 启动前改写为“allowlist 条目
      解析到 RFC1918 放行；仅未列入 allowlist/重定向目标拒绝”，并补一个私网 DNS 正例；在此之前 B6 以
      ADR/M04 为准。
    title: "RUNBOOK 的 RFC1918/DNS rebinding 规则与示例、ADR §6.5 及 M04 复用规则冲突"
    summary: >-
      docs/RUNBOOK.md:1155 写“主机名解析到 RFC1918 视为拒绝（防 DNS rebinding）”，但 :1130 的本地示例
      http://object-storage:9000 在 compose 内解析为 172.18.0.4（本评审实测 `getent hosts object-storage`），
      按该行字面会被拒绝；ADR-0023 §6.5 只要求 RFC1918 在端点显式列出时放行（“内部 S3 的常态”），
      platform/platform-common/.../ManagedEndpointPolicy.java:244-281 也只拒绝元数据/链路本地/生产回环，
      ManagedEndpointPolicyTest.java:147-154 明确允许 allowlist 内私网 DNS。B6 若按 RUNBOOK 字面实现，
      文档示例不可用；若按 ADR/M04 实现，则 RUNBOOK 规则与实现不一致。期望：改写该行并在 B6 前锁定测试。
    evidence: >-
      docs/RUNBOOK.md:1155、:1130、:1138、:1140；
      docs/adr/ADR-0023-m05-knowledge-depth.md:601-602；
      platform/platform-common/src/main/java/com/riccon/platform/common/model/ManagedEndpointPolicy.java:244-281；
      platform/platform-common/src/test/java/com/riccon/platform/common/model/ManagedEndpointPolicyTest.java:147-154；
      复现：`docker run --rm --network riccon-test_riccon-test-net --entrypoint bash mysql:8.4.13 -c 'getent hosts object-storage'`
      → 172.18.0.4。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/ops
    due: TASK-0204 开始前或 M25 gate 前
    accepted_reason: >-
      遗留网络为 internal、无服务接入，不影响零出网结论、测试栈或生产；修复只需在 `config`/`down` 加
      `--profile connector-egress`，并在验证记录中断言网络不存在。该假绿不构成 P1，因为连接器/Worker 尚未接入，
      B6/M09 也不依赖第 4 步清理；但在重复演练/CI 中会累积 orphan 网络，故需在 B6 前修正。
    title: "RUNBOOK 第 4 步 down 未带 --profile，实测 exit 0 但遗留 internal 网络（假绿）"
    summary: >-
      docs/RUNBOOK.md:1185 的 `docker compose -p riccon-o2a-egress -f ... down --remove-orphans` 没有
      `--profile connector-egress`。本评审按 RUNBOOK 原命令执行：第 3 步探针 PASS、第 4 步 exit 0，
      但 `docker network ls` 仍显示 `riccon-o2a-egress_riccon-connector-egress`；只有加
      `--profile connector-egress` 的 down 才输出 `Network ... Removed`。EV-0961 日志同样没有网络删除行。
      另外，第 1 步 `config` 不带 profile 时输出 117 行、不包含新服务/网络，不能单独验证 profile 定义；
      `--profile connector-egress config` 才显示 `internal: true` 与探针。期望：两步都带 profile，
      并断言清理后网络不存在；标准测试栈 `down -v` 也不会删除该 profile-only 网络，RUNBOOK:50 的
      “不改变停止命令”需补一句说明。
    evidence: >-
      docs/RUNBOOK.md:1167-1185、:50；deploy/compose/docker-compose.test.yml:94-140、:150-156；
      EV-0961 日志（`down --remove-orphans` 无输出/无 Removed 行）；
      独立复验：`docker compose -p riccon-o2a-egress --profile connector-egress ... run --rm --no-deps connector-egress-probe`
      → PASS/exit 0；随后原第 4 步 → exit 0，网络仍在；加 `--profile connector-egress` 的 down → Removed。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      纯审计口径问题，不是任务代码越界：实际任务提交 68e3f03 只改两个 write_scope 文件；docs/ACCEPTANCE.md
      来自同级 d9e2cb0（M23/M24 用户验收记账），被 merge ece49eb 的第一父带入。gate 使用 baseline..HEAD，
      故把该文件算到 TASK-0210。无需修改任务产物；Lead 在 closeout/模块 review 说明即可，无生产风险。
    title: "gate check 将 d9e2cb0 的 docs/ACCEPTANCE.md 计入 TASK-0210 越界（审计口径）"
    summary: >-
      `riccon gate check --module M25` 报 “TASK-0210 changed files outside write_scope: docs/ACCEPTANCE.md”；
      实际 `git diff --name-status c3c8d2c..68e3f03` 只有 deploy/compose/docker-compose.test.yml 与
      docs/RUNBOOK.md。docs/ACCEPTANCE.md 由 d9e2cb0 修改（同级 chore 提交，merge ece49eb 的第一父），
      不属于 TASK-0210。M25 gate 本身因 10 个任务未完成且无模块 review 而失败，属预期；本条只记录
      scope 计算伪差异，避免后续把 d9e2cb0 当成 TASK-0210 越界。
    evidence: >-
      `riccon gate check --module M25` 输出；`git diff --name-status c3c8d2c..68e3f03`；
      `git show --stat d9e2cb0`；`git show --no-patch --format='%P' 68e3f03`；
      `git diff --name-status d9e2cb0..ece49eb`。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/ops
    due: TASK-0204 开始前
    accepted_reason: >-
      只是 RUNBOOK 观测行的简写，下表与 ADR §6.6 的完整错误码正确；运行时代码未实现，不会改变行为。
      建议在 B6 实现错误码时一并改为完整 `KB_CONNECTOR_*` 前缀，避免运维按错误码检索日志时漏配；风险低。
    title: "RUNBOOK M05b-5 观测行错误码省略 KB_CONNECTOR_ 前缀，与 ADR §6.6/下表不一致"
    summary: >-
      docs/RUNBOOK.md:1220-1221 写 `last_error_code` 取 `KB_CONNECTOR_AUTH`/`UNREACHABLE`/`TIMEOUT`/`CREDENTIAL`，
      但 ADR-0023 §6.6 和同节表格使用 `KB_CONNECTOR_AUTH`/`KB_CONNECTOR_UNREACHABLE`/`KB_CONNECTOR_TIMEOUT`/
      `KB_CONNECTOR_CREDENTIAL`。期望统一为完整前缀。
    evidence: >-
      docs/RUNBOOK.md:1220-1221、:1228-1231；docs/adr/ADR-0023-m05-knowledge-depth.md:609-619。
test_impact: >-
  本任务无 Java/测试改动，未跑 Maven/pnpm 测试；独立执行了 compose config（默认与 --profile）、
  `--network none` 路由/外网探针、`connector-egress` internal 网络探针、`bash scripts/sandbox.sh`，
  并核对 EV-0961/EV-0962 的命令、提交、dirty=false 与日志哈希。本次复验不注册为 TASK-0210 正式 EV，
  只用于评审结论。
notes: >-
  EV-0961/EV-0962 的 agent 为 riccon/lead（任务 owner 是 riccon/ops），内容已独立复现，未发现矛盾。
  M25 gate 当前失败是预期：10 个任务未完成、无模块 review；其中 TASK-0210 的 docs/ACCEPTANCE.md
  scope 报错来自 d9e2cb0，不是任务提交越界（F-003）。B6 代码级 SSRF、M09 K8s NetworkPolicy 和
  真实连接器/Worker 的零出网均未在本任务验证；F-001/F-002 是文档残差，不改变 compose 网络底座结论。
created_at: "2026-11-24T04:59:26+08:00"
---

# REV-TASK-0210-001：TASK-0210（M25-O2a 连接器出网 allowlist/网络策略）独立评审

## 结论

**通过（pass，含 P2 residual）。** O2a 的两个交付物都落在任务卡 write_scope 内：`deploy/compose/docker-compose.test.yml`
新增 `riccon-connector-egress`（`internal: true`、默认无服务接入）与仅 profile 启用的 `connector-egress-probe`；
`docs/RUNBOOK.md` 新增端点允许列表、B6 归口的 SSRF 规则、零出网验证、开关/回滚与 M09 交接。实现提交
`68e3f03` 相对基线 `c3c8d2c` 只有这两个文件，没有 Java、contracts 或 `.riccon` 生产改动。

本评审在 main 工作树独立复跑了 `docker compose config`、RUNBOOK §M05b-3 的探针、`--network none` 对照
和 `bash scripts/sandbox.sh`：探针 PASS，现有 MySQL/OpenSearch/RustFS 全部 healthy。发现两处需要在 B6 前
修正的文档问题：F-001（RFC1918/DNS rebinding 规则与示例及 M04 复用规则冲突）和 F-002（RUNBOOK 第 4 步
down 未带 profile，exit 0 但遗留 internal 网络，属验证假绿）；均按 P2 accepted 跟踪，不影响 O2a 网络底座
本身的验收。

本 pass 只代表 TASK-0210 的部署配置/RUNBOOK/网络底座验收，**不代表** B6 的代码级 SSRF/allowlist 实现、
M09 的生产 NetworkPolicy 或 M25 模块 gate。任务可进入 done；F-001/F-002 必须在 TASK-0204 开始前处理或
由 Lead 明确带 residual 启动 B6。

## 验收对账

任务卡验收原文：**“连接器端点 allowlist/网络策略；零出网默认；B6 前置；RUNBOOK/验证”**。

| 验收子项                                                                                                                      | 结论                     | 证据/说明                                                                                                                                                                                                                                                                                                      |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RICCON_CONNECTOR_ENDPOINTS` 格式（`id`/`url`/`label`/`allowHttp`/`production`）与默认空=零出网，与 ADR §6.5 一致             | pass                     | `docs/RUNBOOK.md:1113-1144` 字段与 ADR-0023 §6.5:601 一致；未设置/`[]` 为空 + `RICCON_CONNECTOR_ENABLED=false` 默认（`apps/worker/src/main/resources/application.yml:71`）→ 连接器不可用；空列表无法创建连接器明确归 B6 fail-closed                                                                            |
| SSRF 拒绝规则写清（HTTPS/allowHttp、无 userinfo/query/redirect、169.254/16、::1、fe80::/10、RFC1918 仅显式），并明确判定归 B6 | **pass (with residual)** | `docs/RUNBOOK.md:1146-1159` 列出全部规则并声明“判定归 B6，Ops 不绕过”；`169.254.0.0/16`、`::1`、`fe80::/10`、userinfo/query/redirect 与 ADR §6.5:602 一致。残余 F-001：`:1155` 的“主机名解析到 RFC1918 视为拒绝”与 `:1130` 的 `object-storage` 示例（实测解析 172.18.0.4）、ADR 及 M04 允许私网 DNS 的规则冲突 |
| 测试栈 `riccon-connector-egress` 是否 internal=true、默认无服务接入；探针是否只证明网络底座、不冒充 B6 代码级拒绝             | pass（清理残差 F-002）   | `deploy/compose/docker-compose.test.yml:150-156` `internal: true`；`:94-140` 探针仅接入该网络，profile 默认不启动；`docker compose --profile connector-egress config` 确认无其他服务接入；RUNBOOK:1194-1196 明确“测试栈只证明网络底座，真实出网拒绝由 B6 SEC-CONN-003 与 M09 NetworkPolicy 承担”               |
| 现有 `riccon-test-net`/MySQL/OpenSearch/RustFS 未被静默改坏；`scripts/sandbox.sh` 证据仍有效                                  | pass                     | diff 仅新增服务/网络，现有三个服务定义未改（`git diff c3c8d2c..68e3f03`）；EV-0962 日志 `sandbox-probe-ok` + 三服务 healthy，hash 匹配；本评审独立运行 `bash scripts/sandbox.sh` 同样 exit 0、三服务 healthy                                                                                                   |
| 回滚路径（`RICCON_CONNECTOR_ENABLED=false`）与 M09 NetworkPolicy 交接清楚                                                     | pass                     | `docs/RUNBOOK.md:1203-1216` 回滚四步（API+Worker 同键 false、重启、验证审计/指标、数据保持）；`:1194-1196` 明确 M09 NetworkPolicy/客户防火墙，M09 前无 NetworkPolicy 记 release blocker；`docs/RUNBOOK.md:1235-1238` 凭据 30 天置空交接 M09/O2b                                                                |

## 验证了什么

### 静态审查与范围

- 任务实现 diff `c3c8d2c..68e3f03`：
  - `deploy/compose/docker-compose.test.yml`：+55 行，新增 `connector-egress-probe`（profile `connector-egress`、只读、cap-drop ALL、no-new-privileges）与 `riccon-connector-egress`（`driver: bridge`、`internal: true`）。
  - `docs/RUNBOOK.md`：+141 行，新增 M05b-1..5 章节、顶部一行索引、故障恢复一行。
  - `git diff --name-status` 确认无 Java、contracts、`.riccon`、前端或测试文件；`git diff --check` exit 0。
- 提交关系：`68e3f03` 的父为 `c3c8d2c`；main HEAD `ece49eb` 为 `d9e2cb0`（M23/M24 用户验收记账）与 `68e3f03` 的 merge。`d9e2cb0` 的 `docs/ACCEPTANCE.md` 不属于 TASK-0210（F-003）。
- 未发现密钥、凭据或调试输出进入 diff；测试凭据仍为既有占位值。

### 独立复验（main 工作树，2026-11-24）

1. **compose 解析**
   - `docker compose -f deploy/compose/docker-compose.test.yml config >/dev/null` → exit 0；输出 117 行，默认 profile 不含探针/新网络（符合预期，但不足以单独验证 profile 定义）。
   - `docker compose --profile connector-egress -f deploy/compose/docker-compose.test.yml config` → exit 0；`riccon-connector-egress` 为 `driver: bridge` + `internal: true`，`connector-egress-probe` 仅挂该网络；默认 `config --services` 只有 mysql/object-storage/opensearch，加 profile 才出现探针。
2. **RUNBOOK §M05b-3 第 3 步（原命令，项目 `riccon-o2a-egress`）**
   - `docker compose -p riccon-o2a-egress --profile connector-egress -f deploy/compose/docker-compose.test.yml run --rm --no-deps connector-egress-probe`
   - 结果：路由表只有 internal 网段（`172.20.0.0/16` 一类的 `000014AC`）、无默认路由；1.1.1.1:443 与 169.254.169.254:80 均被拒绝；末行 `connector-egress-probe: PASS`，exit 0。
3. **`--network none` 对照**
   - `docker run --rm --network none --read-only --cap-drop ALL --user 10001:10001 --entrypoint bash mysql:8.4.13 -c 'cat /proc/net/route'` → 只有表头。
   - 同参数下 `/dev/tcp/1.1.1.1/443` → `external connect exit=1`（容器内打印；外层 exit 0），与 RUNBOOK 期望一致。
4. **测试栈回归**
   - `bash scripts/sandbox.sh` → exit 0；`sandbox-probe-ok`；MySQL/OpenSearch/RustFS healthy；与 EV-0962 日志一致。
5. **清理命令（发现 F-002）**
   - 按 RUNBOOK 原第 4 步 `docker compose -p riccon-o2a-egress -f ... down --remove-orphans` → exit 0，但 `docker network ls` 仍有 `riccon-o2a-egress_riccon-connector-egress`；加 `--profile connector-egress` 后才输出 `Network ... Removed`。复验后已用带 profile 的 down 清理，当前只剩 `riccon-test_riccon-test-net`。

### 证据审计

| 证据    | 提交    | 执行者      | 结果                                         | 复核                                                                                                          |
| ------- | ------- | ----------- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| EV-0961 | ece49eb | riccon/lead | exit 0；compose config + 探针 PASS + down    | commit/dirty=false 正确；日志 sha256 `0217bf22…` 与 JSON 一致；探针结果独立复现；down 未真正清理网络（F-002） |
| EV-0962 | ece49eb | riccon/lead | exit 0；sandbox attestation + 三服务 healthy | commit/dirty=false 正确；日志 sha256 `38f8c190…` 与 JSON 一致；独立复跑 exit 0                                |

EV 由 Lead 而非任务 owner `riccon/ops` 录制；这是流程事实，不是证据失效。两份 EV 均为非 manual、exit 0、绑定提交且日志哈希匹配，满足任务级证据要求。

## 未验证

- **B6 代码级 allowlist/SSRF 拒绝**：本任务明确不实现；`RICCON_CONNECTOR_ENDPOINTS` 解析、`SEC-CONN-003` 的“不发起连接”拒绝、重定向/DNS rebinding 代码级测试均归 TASK-0204，未在本 review 验证。
- **真实连接器/Worker 的零出网**：测试栈不启动 API/Worker，`riccon-connector-egress` 目前无任何实际服务接入；本任务只证明网络底座。B6 接入后需重新验证连接器容器的实际网络路径；生产由 M09 NetworkPolicy/客户防火墙承担，尚未交付。
- **M09 NetworkPolicy**：M09 仍为 planned；RUNBOOK 明确把“无 NetworkPolicy”登记为 release blocker，但本任务不能证明生产已具备该能力。
- **完整模块 gate**：M25 仍在 building（10 个任务未完成、无模块 review）；本次未跑合同全量 build/test/coverage/E2E，也不应把本任务 review 当成 M25 gate。
- **IPv6 专项探针**：探针断言的是 IPv4 `/proc/net/route` 与 IPv4 外部/元数据地址；Docker 网络当前 `EnableIPv6=false`，未单独验证 IPv6 默认路由/外部地址。若 M09 或客户网络启用 IPv6，需补同类断言（不改变本任务当前结论）。
- **B2b 契约**：`contracts/openapi.yaml` 仍为 1.4.0-m05（TASK-0203 in progress）；RUNBOOK 引用的 `1.5.0-m05b` 尚未落地，属预期前置依赖。

## Findings

| ID    | 级别 | 状态     | 摘要                                                                                                  | Owner / Due                                 |
| ----- | ---- | -------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| F-001 | P2   | accepted | RUNBOOK RFC1918/DNS rebinding 规则与 `object-storage` 示例、ADR §6.5、M04 私网 DNS 规则冲突           | riccon/ops / B6 启动前                      |
| F-002 | P2   | accepted | RUNBOOK 第 4 步 down 未带 profile，exit 0 却遗留 internal 网络（假绿）；第 1 步 config 也不含 profile | riccon/ops / TASK-0204 开始前或 M25 gate 前 |
| F-003 | P3   | accepted | gate 把 d9e2cb0 的 docs/ACCEPTANCE.md 计入 TASK-0210 越界；实际任务提交仅两文件                       | riccon/lead / M25 gate 前                   |
| F-004 | P3   | accepted | RUNBOOK M05b-5 观测行错误码省略 `KB_CONNECTOR_` 前缀，与 ADR §6.6/下表不一致                          | riccon/ops / TASK-0204 开始前               |

## 剩余风险

1. **文档先于实现**：RUNBOOK 是 B6 的输入；F-001 若不在 B6 启动前澄清，B6 可能实现与示例/ADR 不一致的私网 DNS 策略。建议先改文档再开 TASK-0204。
2. **清理残差**：F-002 会让重复演练/CI 累积 internal orphan 网络；无安全影响，但会污染 Docker 网络列表。修复后应在验证记录中断言 `docker network ls` 无该网络。
3. **生产出网策略未交付**：M09 前生产没有 NetworkPolicy；RUNBOOK 已要求发布单登记 release blocker，但这是 release 级残余，不是 O2a 缺陷。
4. **零出网只到网络底座**：真实连接器/Worker 尚未接入；B6/M09 必须分别完成代码级拒绝与生产网络策略，不能引用本任务探针替代 SEC-CONN-003。
5. **契约时序**：TASK-0203 的 1.5.0-m05b 尚未完成；B6 开始前需确认契约与 RUNBOOK 端点字段/错误码一致。

## 下一步

- **建议 Lead 可将 TASK-0210 标 done**，在 closeout 中记录 F-001/F-002 residual 与 owner/due；任务证据保持 EV-0961/EV-0962，end_commit=68e3f03。
- **B6（TASK-0204）启动前**：修正 F-001（RFC1918 行改为 allowlist 条目显式允许私网，未列入/重定向目标拒绝）与 F-004（完整错误码前缀）；修正 F-002（config/down 加 `--profile connector-egress`，验证记录断言网络已删除）。
- **M25 gate 前**：Lead 说明 F-003 的 d9e2cb0 scope 伪差异；B6/M09 完成代码级 SSRF 与生产 NetworkPolicy 后，V1b/V2b 再做整条模块验证。
- 本评审不替代 M25 模块 review、Product 预验收或用户验收。
