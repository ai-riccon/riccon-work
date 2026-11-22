---
review_id: REV-TASK-0128-001
module_id: M04
task_id: TASK-0128
reviewer:
  agent: riccon/verifier
  session: ses_e049028d6ffeFKQkRasGaR9lMi
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 1209f98c4e468af349342e33504a9a1a5f48e902
  - f6385ab0b21b2b8e850cc10ff7bf69bfdd3d16e5
head_commit: a9da689eb366ae8448a405b30f9af226d6030056
task_end_commit: f6385ab0b21b2b8e850cc10ff7bf69bfdd3d16e5
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/ops + riccon/lead（到期 M04 gate 前）"
    title: "RUNBOOK 仍把 /actuator/info 记为未放行的 M04 发布阻断项，且未写明 B10 preflight 的来源要求"
    summary: >-
      `docs/RUNBOOK.md:375-377` 仍写“已知缺口（M04 发布阻断项）：默认拒绝安全链当前未放行
      /actuator/info”，与本任务落地的行为相反；`docs/RUNBOOK.md:340` 与 `:601` 仍只写
      “info 403/401 即发布阻断”，但没有说明 `seed-model-catalog --check` 必须从
      `RICCON_OPS_ALLOWED_CIDRS` 允许来源（空=loopback）发起、生产/预览须先把 ingress 或
      sidecar 源地址纳入允许网段。观察值：代码已放行允许来源；期望值：RUNBOOK 删除“未放行”缺口，
      补充“从允许来源执行 --check、401/403 仍 exit 1 阻断”的操作步骤。影响：ops 按旧文本会误判
      M04 发布仍被阻断，或从非允许来源执行 preflight 得到 403 并把环境误报为漂移。
      不阻塞本任务代码 verdict，但 M04 gate/发布前必须关闭或由 Lead 书面接受。
    evidence: >-
      `docs/RUNBOOK.md:340`、`:375-377`、`:587-601`；
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:150-151`；
      `scripts/ops/seed-model-catalog.mjs:724-731`（status 非 200 直接 failed）；
      TASK-0128 验收第 2 条本身只要求“可执行”，未把 RUNBOOK 更新放入写范围。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/architect（到期 M04 gate 前）"
    title: "ADR-0021 §2.2/§10.6 与威胁模型仍只授权 prometheus 的来源门禁，info 复用未记录"
    summary: >-
      ADR-0021 `:117`（§2.2 Actuator 来源控制）与 `:875`（§10.6 暴露）只写
      `RICCON_OPS_ALLOWED_CIDRS` 限制 `/actuator/prometheus`，未写 `/actuator/info` 复用同一门禁；
      §2.2 还写“非允许来源返回 404/403”，而 API 匿名来源按同一过滤器链为 401（REV-TASK-0103-002 F-003
      已由 Lead 接受，本次沿用同一语义）。`docs/security/THREAT-MODEL.md:85` 仍写“仅
      /actuator/health 放行”（prometheus 落地后已过时）。观察值：实现扩大了允许来源可读的
      actuator 面；期望值：Architect 在 §2.2/§10.6 增补 info 条目、明确匿名 401 可接受，并同步
      TM 默认拒绝行。影响：安全合同与实现不一致，后续评审/运维无法仅凭 ADR 判断 info 是否应放行；
      `docs/CODING-STANDARDS.md §12` 要求新增白名单路径必须评审，本次评审已覆盖，但 ADR 仍是缺口。
      不阻塞本任务代码 verdict，M04 gate 前必须关闭或书面接受。
    evidence: >-
      `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:117`、`:875`、`:946`；
      `docs/security/THREAT-MODEL.md:85`；
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:150-151`；
      `apps/worker/src/main/java/com/riccon/worker/config/SecurityConfig.java:53-54`；
      `.riccon/reviews/tasks/TASK-0103-review-002.md` F-003（匿名 401 已接受）。
  - id: F-003
    severity: P3
    status: open
    owner: "riccon/builder-route + riccon/lead（到期 M04 gate 前）"
    title: "info 测试复用 OPS-M04-02b，与 ADR 的 API 配额/成本指标 ID 冲突"
    summary: >-
      ADR-0021 `:946`/`:950`/`:1123` 把 `OPS-M04-02b` 定义为“API 配额/成本 2 个主指标 + 3 个辅助
      指标存在/标签正确，V2 记录抓取样本”，并明确 02a/02b 不得互相替代；本次 API/Worker 的 info
      测试在类 Javadoc 与 `@DisplayName` 中复用 `OPS-M04-02b`。观察值：同一 ID 同时指向 info
      来源控制与指标验收；期望值：为 info 用独立标识（如 OPS-M04-02c 或 TASK-0128 专属 ID），
      避免 M04 gate 证据映射把 info 测试误当作指标验收。不影响代码行为，属可追溯性缺陷。
    evidence: >-
      `apps/api/src/test/java/com/riccon/api/actuator/InfoAllowedCidrAccessTest.java:13,28`；
      `apps/api/src/test/java/com/riccon/api/actuator/InfoExposureDenyTest.java:13,28,38,50`；
      `apps/api/src/test/java/com/riccon/api/actuator/InfoLoopbackAccessTest.java:13,28,47`；
      `apps/worker/src/test/java/com/riccon/worker/actuator/InfoCidrAccessTest.java:16`；
      `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:946,950,1123`。
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/builder-route / riccon/verifier（到期 M04 gate 前）"
    title: "info 内容只断言必需键存在，未断言键集合等于既有非敏感 allowlist"
    summary: >-
      本次测试断言 `deploymentMode`/`masterKeyId`/`currency`/四个配额键存在（API 7 键、Worker 3 键），
      但没有断言响应键集合“只有”既有非敏感键。静态核对：两个 `application.yml` 的 `info:` 只有
      `riccon.*` 一组（`masterKeyId`、`endpointSetHash`、`deploymentMode`、`routingMode`、
      `failoverEnabled`、`quotaEnforcementEnabled`、`currency` 与四个配额），没有 build-info/
      git.properties 或其他 `InfoContributor`；`management.info.env.enabled=true` 只暴露 `info.*`。
      因此当前未发现密钥/DB/内部地址泄露。期望值：增加精确键集合断言或由 SEC 扫描覆盖，防止未来
      新增 `info.*` 时被允许来源静默读取。当前不阻塞。
    evidence: >-
      `apps/api/src/main/resources/application.yml:66-89`；
      `apps/worker/src/main/resources/application.yml:37-60`；
      `apps/api/src/test/java/com/riccon/api/actuator/InfoLoopbackAccessTest.java:29-45`；
      `apps/worker/src/test/java/com/riccon/worker/actuator/InfoLoopbackAccessTest.java:37-55`；
      `grep -rn "InfoContributor" apps/api/src/main apps/worker/src/main` 无结果；
      `find apps -name build-info.properties -o -name git.properties` 无结果。
evidence:
  - EV-0599
  - EV-0600
reviewed_evidence:
  - EV-0599
  - EV-0600
created_at: "2026-11-21T08:22:00+08:00"
---

# REV-TASK-0128-001：TASK-0128 独立评审（/actuator/info 运维漂移读取路径）

## 结论

**pass（代码/安全行为面）。** `/actuator/info` 已与 `/actuator/prometheus` 共用
`RICCON_OPS_ALLOWED_CIDRS` 来源门禁（空=loopback），没有引入 `permitAll`，`anyRequest().denyAll()`
未放宽；API/Worker 的正例、负例、已认证管理员负例与 prometheus 回归均通过独立复跑。未发现 P0/P1。

P2 均为文档/合同漂移：RUNBOOK 仍把 info 记为“未放行”的发布阻断项（F-001），ADR-0021 §2.2/§10.6
与威胁模型仍未记录 info 复用同一门禁（F-002）。它们不否定本次实现，但 M04 gate/发布前必须关闭或由
Lead 书面接受。另有 2 个 P3 可追溯性/测试强度建议（F-003、F-004）。本评审不把任务标记为 done。

## 安全边界逐项

| #   | 检查项                                                                        | 结论                                                                                                                                                                                                                                                                                                                                                                | 证据                                                                                                                                       |
| --- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `/actuator/info` 与 prometheus 共用 `RICCON_OPS_ALLOWED_CIDRS`（空=loopback） | **pass**。API/Worker 均改为 `.requestMatchers("/actuator/info", "/actuator/prometheus").access(opsEndpointAccess(...))`；`AllowedCidrs.parse` 空值仅 loopback、非空只认列出的 CIDR，且不额外放行 loopback。                                                                                                                                                         | `apps/api/.../SecurityConfig.java:150-151,77-80,64-65`；`apps/worker/.../SecurityConfig.java:53-54,79-82,37`                               |
| 2   | 未新增 `permitAll`；默认拒绝未放宽                                            | **pass**。新增行只有 matcher + `access(...)`；`anyRequest().denyAll()` 保持在 API `:168-169`、Worker `:61-62`。diff 中无新增 `permitAll`。                                                                                                                                                                                                                          | `git diff 1209f98^ f6385ab -- ...SecurityConfig.java`；两侧 `anyRequest().denyAll()`                                                       |
| 3   | 非允许来源被拒绝（API 匿名/管理员、Worker）                                   | **pass**。API 匿名 401（测试断言允许 401/403/404；同链 prometheus 的 401 已在 REV-TASK-0103-002 F-003 由 Lead 接受）、平台管理员 403；Worker 非允许来源 403；响应不含 `deploymentMode`/`riccon`/指标样本。                                                                                                                                                          | `InfoExposureDenyTest:29-58`；`InfoCidrAccessTest:69-89`；独立复跑通过                                                                     |
| 4   | 其他 Actuator 端点（loggers 等）仍拒绝                                        | **pass**。API 在 `exposure.include` 含 `loggers` 的上下文断言 401/403/404 且无 `levels`；Worker 精确断言 403。运行日志显示该上下文确实暴露 4 个端点（health/info/prometheus/loggers），不是 404 掩盖。                                                                                                                                                              | `InfoLoopbackAccessTest:48-56`；`apps/worker/.../InfoLoopbackAccessTest.java:58-61`；独立运行日志 `Exposing 4 endpoints`                   |
| 5   | prometheus 既有行为不回归                                                     | **pass**。API `PrometheusExposureDenyTest` 4 例 + `PrometheusLoopbackAccessTest` 2 例、Worker `PrometheusExposureDenyTest` 3 例独立复跑全绿；显式 CIDR 下 Worker prometheus 仍可抓取、非允许来源仍 403。                                                                                                                                                            | EV-0599/EV-0600；独立复跑 12+9 例                                                                                                          |
| 6   | info 内容只含既有非敏感键                                                     | **pass（静态核对）**。两侧 `info:` 仅 `riccon.*`：deploymentMode/endpointSetHash/routingMode/failoverEnabled/quotaEnforcementEnabled/masterKeyId/currency/四个配额；无 build-info、git.properties、其他 `InfoContributor`；`endpointSetHash` 为哈希、`masterKeyId` 为密钥标识而非密钥，均符合 ADR S-12；未见 DB/内部 URL/密钥。测试未做精确键集合断言（见 F-004）。 | `apps/api/src/main/resources/application.yml:66-89`；`apps/worker/src/main/resources/application.yml:37-60`；`grep InfoContributor` 无结果 |
| 7   | 写范围                                                                        | **pass**。仅 2 个 `SecurityConfig.java` + 5 个 actuator 测试文件（累计 diff 7 文件）；未改 info 内容、契约、迁移、前端。                                                                                                                                                                                                                                            | `git diff --name-only 1209f98^ f6385ab`（除 `.riccon/` 外 7 文件）                                                                         |
| 8   | 认证/CSRF/代理语义                                                            | **pass（当前配置）**。GET 不触发 CSRF；未配置 `server.forward-headers-strategy`/`RemoteIpValve`，`getRemoteAddr()` 取 socket 地址，`X-Forwarded-For` 当前不能伪造来源。生产 ingress 的源地址语义归 M09（见剩余风险）。                                                                                                                                              | 两侧 `src/main/resources` 检索 forward-headers 与 RemoteIpValve 无结果                                                                     |

## 验收标准对账

| AC（TASK-0128.json）                                                                                            | 结论               | 说明                                                                                                                                                                                                         |
| --------------------------------------------------------------------------------------------------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1. `/actuator/info` 对 `RICCON_OPS_ALLOWED_CIDRS` 允许来源可读（空=loopback），默认拒绝其余来源；不得 permitAll | **pass**           | API loopback 200、显式 `127.0.0.1/32` 200、显式 `10.99.0.0/16` 下 127.0.0.1 被拒、管理员仍被拒；Worker 空配置 loopback 200、显式 `10.99.0.0/16` 下 127.0.0.1 403、`10.99.0.5` 200；无 permitAll。            |
| 2. B10 `seed-model-catalog --check` 的 info 指纹漂移检查可执行；补 API/Worker 正/负向测试                       | **pass（有保留）** | 端点路径、11 个指纹键的配置源和 5+3 个定向测试均存在；B10 抓取逻辑在非 200 时直接 failed（不会把 403 当一致）。保留：本任务没有实跑一次 `--check` 的 EV（见“未验证”），且实际执行须从允许来源发起（F-001）。 |
| 3. 不改 info 内容/契约/迁移；定向测试 + EV                                                                      | **pass**           | 累计 diff 仅 SecurityConfig + actuator 测试；EV-0599/0600 clean、exit 0、commit `f6385ab`、sha256/字节数复核一致。                                                                                           |

## 验证了什么

1. **提交与范围**：`git show --stat 1209f98 f6385ab` —— `1209f98` 改 API/Worker `SecurityConfig` 并加 4 个测试；`f6385ab` 补 `InfoAllowedCidrAccessTest`、`InfoCidrAccessTest`，加强 API `InfoExposureDenyTest`，删除 Worker `InfoExposureDenyTest`（其 2 个场景已并入 `InfoCidrAccessTest` 的 4 个用例）。`git diff --stat 8d8b48d f6385ab -- apps platform contracts docs deploy` 仅 7 个文件（2 生产 + 5 测试）。
2. **静态安全审查**：逐行读 API/Worker `SecurityConfig`，确认 matcher 顺序、`access(...)` 语义、`AllowedCidrs` 空/显式/CIDR 边界、`anyRequest().denyAll()`；确认没有 `permitAll` 新增、没有 `management.server.port`/独立 actuator 端口、没有 forward-headers 配置。
3. **独立定向复跑（当前 HEAD，代码与 `f6385ab` 一致）**：
   - API：`./mvnw -T1C -pl apps/api -am test -Dtest='InfoLoopbackAccessTest,InfoAllowedCidrAccessTest,InfoExposureDenyTest,PrometheusExposureDenyTest,PrometheusLoopbackAccessTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false` → `EXIT=0`，`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
   - Worker：`./mvnw -T1C -pl apps/worker -am test -Dtest='InfoLoopbackAccessTest,InfoCidrAccessTest,PrometheusExposureDenyTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false` → `EXIT=0`，`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
4. **证据审计**：EV-0599/EV-0600 均为 `manual=false`、`dirty=false`、`exit_code=0`、`commit=f6385ab`；按文件重算 sha256 与 `output_bytes` 均匹配。EV-0592/0594 记录在 `bddce69`（`1209f98` 的后代，已含修复与测试，不属于零测试假绿）；EV-0597/0598 为 dirty（工作树有未提交测试），**不作为本任务 closeout 证据**。
5. **任务 JSON**：`baseline_commit=8d8b48d`（`1209f98` 的父）、`end_commit=f6385ab`；`8d8b48d..f6385ab` 还夹带 TASK-0127/TASK-0113 的 `.riccon` bookkeeping 提交，但 `-- apps platform contracts docs deploy` 的源码 diff 只属于 TASK-0128。`git diff f6385ab a9da689 -- apps platform contracts` 为空（评审期间 HEAD 从 `b78ba1a` 前进到 `a9da689`，仅其他任务的 `.riccon` bookkeeping），说明评审代码与 HEAD 一致。

## 未验证

- **未实跑 B10 `--check`**：没有对运行中的 API/Worker 执行 `node scripts/ops/seed-model-catalog.mjs --check --api-base ... --worker-base ...` 的 EV，因此“脚本 + 真实端点”的集成只经端点行为、脚本 `--self-test`（EV-0586 历史证据）和脚本失败语义间接验证；M04 preflight 仍需实跑一次（并须从允许来源发起）。
- **未跑全量 `./mvnw verify`/`./mvnw -q -T1C verify jacoco:report`**：任务级只跑定向；全量 test/coverage/E2E/a11y/security 由 M04 模块评审在最终提交统一取证。
- **未验证生产/预览 ingress 的源地址**：M09 尚未实现；若 ingress 转发后 `getRemoteAddr()` 是 ingress pod IP，ops 必须把该网段加入 `RICCON_OPS_ALLOWED_CIDRS`。若未来启用 `server.forward-headers-strategy=native`，`X-Forwarded-For` 可能影响来源判定；当前配置未启用，故不构成本任务回归（见剩余风险）。
- **未验证 `tools_used` 中的 Serena 实际调用**：任务卡自报 `serena`，EV 未记录符号工具调用轨迹；变更仅为 matcher/方法重命名与注释，影响有限，未计为 finding，建议 Lead closeout 时确认或要求补说明。
- **未重跑 EV-0597/0598**（dirty）和 EV-0592/0594；只以最终干净 EV-0599/0600 作为 closeout 证据。

## Findings

### F-001（P2，open）RUNBOOK 仍写 info 未放行/发布阻断

`docs/RUNBOOK.md:375-377` 的“已知缺口（M04 发布阻断项）”现在与实现相反；`:340`/`:601` 只写 403/401
阻断，未写“必须从 `RICCON_OPS_ALLOWED_CIDRS` 允许来源执行”。观察值：允许来源可读 info；期望值：
删除“未放行”表述，补充 B10 preflight 的来源配置与执行步骤。owner：Ops + Lead，到期 M04 gate 前。
不阻塞本任务 verdict。

### F-002（P2，open）ADR/威胁模型未记录 info 复用来源门禁

ADR-0021 `:117`、`:875` 仍只写 prometheus；`:946` 的 OPS-M04-02b 与本次测试 ID 冲突（见 F-003）；
`docs/security/THREAT-MODEL.md:85` 仍写“仅 /actuator/health 放行”。观察值：info 已纳入来源门禁；
期望值：Architect 在 §2.2/§10.6 增补 info，并澄清匿名 401（沿用 REV-TASK-0103-002 F-003 的已接受
语义），同步 TM。owner：Architect，到期 M04 gate 前。

### F-003（P3，open）OPS-M04-02b 测试 ID 复用

ADR 把 `OPS-M04-02b` 定义为 API 配额/成本 2 主 + 3 辅助指标（V2 抓取），并明确 02a/02b 不得互相替代；
本次 info 测试在 Javadoc/`@DisplayName` 复用该 ID。建议改为独立 ID，避免 gate 证据映射混淆。
owner：builder-route + Lead。

### F-004（P3，open）info 键集合缺少精确断言

测试只断言必需键存在，未断言“只有”既有非敏感键。当前静态核对未发现额外 `info.*`/build/git/密钥
泄露；建议补精确键集合断言或 SEC 扫描，防止未来新增 `info.*` 被允许来源静默读取。owner：
builder-route/verifier，到期 M04 gate 前。

## 剩余风险

- **ingress 源地址与 XFF**：`opsEndpointAccess` 只看 `getRemoteAddr()`。当前未启用 forward-headers，
  无 XFF 伪造面；M09 若启用 `native`/`framework` 策略，需同时限制可信代理或改为在 ingress 层做
  NetworkPolicy，否则 `X-Forwarded-For` 可能绕过来源判定（对 prometheus 同样是既有风险）。
- **CIDR 配置即信任边界**：`0.0.0.0/0` 或过宽网段会把 info/prometheus 暴露给整个网段；RUNBOOK/ADR
  应要求最小网段并纳入发布检查（本任务未改配置，属运维约束）。
- **info 新增可达面**：允许来源现在能读到 `masterKeyId`、`endpointSetHash`、默认配额与币种；内容按
  ADR S-12 为非敏感，但应保持允许网段最小化，并在 M04 gate 的 SEC 扫描中复核响应无密钥。
- **API/Worker `AllowedCidrs` 双实现**：本次未抽公共类（B2 F-006 已作为 P3 接受给 B4）；两份实现
  仍可能漂移，M04 gate 前可复核差异。
- **EV-0597/0598 为 dirty**：不得在 closeout/模块 gate 引用；最终以 EV-0599/0600 为准。
