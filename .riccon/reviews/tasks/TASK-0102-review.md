---
review_id: REV-TASK-0102-001
module_id: M04
task_id: TASK-0102
reviewer:
  agent: riccon/verifier
  session: ses_e062e7324ffePd3jQeTZ5qmZq6
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [e554b13, 62a9e0c]
head_commit: 617fa4b07542d515f7615b3219129c4bf898ab31
evidence: [EV-0534, EV-0537]
reviewed_evidence: [EV-0534, EV-0537]
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/ops + B2/TASK-0103
    due: B2 完成前
    title: "Worker 负向测试的 allowed-cidrs 被基类 @DynamicPropertySource 覆盖"
    summary: >-
      WorkerIntegrationTestBase 在 @DynamicPropertySource 中注册 riccon.ops.allowed-cidrs=""
      （WorkerIntegrationTestBase.java:209、:246）；PrometheusExposureDenyTest 用类级 @TestPropertySource
      设 10.99.0.0/16（PrometheusExposureDenyTest.java:31-35）。Spring 7 的 @DynamicPropertySource
      文档明确动态属性优先级高于 @TestPropertySource（spring-test-7.0.10 DynamicPropertySource.java
      Precedence 段），因此 Worker 测试实际以空值（ADR 语义为 loopback 允许）运行，并没有模拟
      “非允许来源”。B1c 阶段 B2 尚未实现 CIDR 过滤，SecurityConfig 的 anyRequest().denyAll() 仍让
      断言通过，所以当前“默认拒绝”结论成立；但 B2 落地后空值会放行 127.0.0.1，测试可能错误地拿到
      200 或不能证明非允许来源被拒。修复：删除基类对该键的动态注册（application-test.yml:24 已设
      空字符串，子类 @TestPropertySource 即可覆盖），或提供可覆盖的动态属性 seam；B2 完成前必须复核。
    evidence: "apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:209,246；apps/worker/src/test/java/com/riccon/worker/actuator/PrometheusExposureDenyTest.java:31-35；apps/worker/src/test/resources/application-test.yml:24"
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/ops
    title: "Java 契约与 metrics-check 的禁止标签清单存在漂移"
    summary: >-
      M04MetricsContract.FORBIDDEN_LABELS 含 sessionId（M04MetricsContract.java:24-25），
      scripts/ops/metrics-check 的 FORBIDDEN_LABEL_PATTERN 不含 sessionId/session_id
      （metrics-check:48）；M04MetricsContractTest.scriptMatchesJavaContract 只比较 METRIC_SPECS
      （M04MetricsContractTest.java:64-69），不比较禁止标签。结果是 sessionId 标签不会被
      metrics-check 拒绝，Java 契约与脚本的禁止清单不是单一事实源。ADR-0021 §10.6 只点名
      tenant/userId/runId，当前验收不受影响；建议把禁止标签也做成脚本可解析清单并纳入交叉校验。
    evidence: "apps/worker/src/test/java/com/riccon/worker/actuator/M04MetricsContract.java:24-25；apps/worker/src/test/java/com/riccon/worker/actuator/M04MetricsContractTest.java:64-69；scripts/ops/metrics-check:48"
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/ops / riccon/lead
    title: "EV-0534 的提交晚于 TASK-0102 end_commit"
    summary: >-
      EV-0534.json 的 commit 为 9d07224，TASK-0102.json 的 end_commit 为 62a9e0c；62a9e0c 是
      9d07224 的祖先，且 git diff 62a9e0c 9d07224 -- <本任务写范围内的 7 个文件> 为空，说明被证据覆盖的
      代码与 end_commit 一致，9d07224 只带入任务范围外的后续提交。证据本身 dirty=false、exit=0、
      sha256 匹配；但 provenance 与 end_commit 不一致，建议 closeout 时把 end_commit 指向 9d07224
      或在 62a9e0c 重录。EV-0537 已在 HEAD 617fa4b 全量 verify 通过，风险低。
    evidence: ".riccon/evidence/TASK-0102/EV-0534.json；.riccon/tasks/TASK-0102.json:39；git diff 62a9e0c 9d07224 -- apps/api/pom.xml apps/worker/pom.xml scripts/ops/metrics-check apps/api/src/test/java/com/riccon/api/actuator apps/worker/src/test/java/com/riccon/worker/actuator"
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/ops
    title: "metrics-check 标签检查的意外 awk 退出码会被当成通过"
    summary: >-
      scripts/ops/metrics-check:141-179 的标签检查用 missing="$(awk ...)" || rc=$? 捕获退出码，
      但只处理 rc=3/4；若 awk 因内部/环境异常返回其他非零（1、2、127 等），代码不会失败，反而继续
      打印 OK。当前 awk 脚本固定、self-test 已覆盖正常/缺标签/高基数三条路径，实际风险低；建议把
      所有非零 rc 统一判失败，避免“失败非 0”被内部错误绕过。
    evidence: "scripts/ops/metrics-check:140-179"
created_at: "2026-11-21T00:44:41+08:00"
---

# REV-TASK-0102-001：TASK-0102 / M04-B1c 独立评审

本评审是任务级独立技术验收，不是 Product 预验收或用户产品验收，也不替代 M04 模块评审。

## 结论

**pass（任务级）**。5 条验收均有可核对的实现与证据，无 P0/P1；F-001（P2）不影响 B1c 当前的默认拒绝结论，但 B2 落地前必须修复或复核。M04 模块 gate 仍应失败（模块 building、多个任务 review/planned、无模块 review），本任务 pass 不等于模块 gate pass。

## 逐条验收（5 条）

| #   | 验收原文                                                               | 结论 | 依据                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| --- | ---------------------------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Prometheus registry 依赖（API/Worker）                                 | pass | `apps/api/pom.xml:41-54`、`apps/worker/pom.xml:91-101` 均新增 `io.micrometer:micrometer-registry-prometheus`，未写 `<version>`；Boot 4.1.2 BOM 解析为 Micrometer 1.17.2（`~/.m2/repository/io/micrometer/micrometer-registry-prometheus/1.17.2`，构建 exit 0）。两侧 `PrometheusExposureDenyTest.prometheusRegistryIsAutoConfigured` 断言 `PrometheusMeterRegistry` 已装配（API 4 tests / Worker 3 tests，0 fail / 0 skip）。                                                                                             |
| 2   | 7 个路由/降级/key 指标名与标签约定（Worker 注册，B4 落地）             | pass | `M04MetricsContract.METRICS` 与 ADR-0021 §10.6 逐字一致：requests `{provider,model,outcome}`、failover `{from,to,reason}`、attempt_duration histogram、credential_invalid `{provider}`、credential_cooldown `{provider}`、chain_empty `{purpose}`、agent_runs_inflight gauge。`M04MetricsContractTest` 断言恰好 7 个、`riccon_` 前缀、唯一、必需标签不含 `tenant/userId/runId` 等高基数键；契约类只在 `apps/worker/src/test/java`（`git ls-tree` e554b13/62a9e0c 均为测试路径，无 `src/main` 同名文件）。注册留 B4 正确。 |
| 3   | `scripts/ops/metrics-check` 以 `--base-url` 指向 Worker，只断言这 7 个 | pass | 脚本 `--base-url` 必填，帮助与注释明确指向 Worker；`METRIC_SPECS` 恰好 7 项（metrics-check:37-45），不检查 API 配额/成本指标；`set -euo pipefail`、无 \|\| true 这类吞错、失败 exit 1、用法错误 exit 2；`--self-test` 覆盖合法样本 + 缺指标 + 缺标签 + tenant 高基数四种场景。我独立运行 `scripts/ops/metrics-check --self-test`（exit 0）和 `--help`（exit 0）；真实 `--base-url` 抓取未跑，因为 B4 尚未注册 7 指标，见“未验证项”。                                                                                      |
| 4   | 暴露实现归 B2，B1c 只做 registry 依赖与负向测试                        | pass | `git show --stat e554b13` 只有 2 个 pom、3 个测试类、1 个契约类、1 个脚本（共 7 个文件）；`62a9e0c` 只加 5 行 javadoc。未改 `SecurityConfig`、`application.yml`、`contracts/`。测试用 `@TestPropertySource` 强制暴露仅限测试上下文；生产暴露/来源控制留给 B2。                                                                                                                                                                                                                                                            |
| 5   | 不改变默认拒绝基线                                                     | pass | API `SecurityConfig` 的 `.anyRequest().denyAll()` 与 Worker `SecurityConfig` 的 `.anyRequest().denyAll()` 均未改动。API 4 个测试覆盖匿名/平台管理员被拒 + 健康检查公开；Worker 3 个测试覆盖非允许来源被拒 + 健康检查公开；surefire 均为 0 fail / 0 skip。EV-0534 日志两次出现 `Exposing 3 endpoints beneath base path '/actuator'`（health/info/prometheus），说明 403/404 不是端点未暴露造成的假绿。F-001 是 Worker “非允许来源”模拟的配置缺陷，不影响当前 denyAll 结论。                                                |

## 重点结论

### 依赖与版本

- `micrometer-registry-prometheus` 在 API/Worker 都是 Boot BOM 管理、无手写版本，无冲突 pin；API 用于 B6/B7 配额/成本指标，Worker 用于 B4 路由/降级/key 指标，依赖位置合理。
- 新增依赖未在本任务跑 Trivy；按 T4 口径留给模块 release/security gate（见“未验证项”）。

### 指标契约与单一事实源

- ADR-0021 §10.6、Java 契约、脚本三处 7 个指标名称/标签/类型完全一致；`M04MetricsContractTest.scriptMatchesJavaContract` 直接解析脚本 `'name|type|labels'` 行，单改任一侧会失败。
- 禁止标签：Java 契约含 `tenant/tenantId/tenant_id/userId/user_id/runId/run_id/sessionId`；脚本正则含前 7 个变体、缺 `sessionId/session_id`；交叉校验测试只比指标清单，不比禁止标签（F-002，P3）。
- 脚本类型字段只用于 histogram（要求 `_bucket`）；counter/gauge 的类型未在脚本中断言，Java 契约测试固定了类型，B4 注册正确性最终由 OPS-M04-02a 抓取报告核对。

### metrics-check

- 脚本可执行（git mode 100755），无 `|| true`，失败路径清晰；`--self-test` 不访问网络，适合 CI/本地预检。
- `normalize` 把 `_bucket/_sum/_count` 归到 histogram 族；标签检查逐样本要求必需标签；高基数检查只扫描这 7 个族，不会误判 API/framework 指标（self-test 的 good fixture 含 `http_server_requests_seconds` 也通过）。
- F-004（P3）：标签检查只处理 awk 的 rc=3/4，其他非零会落成 OK；实际输入固定、self-test 覆盖了正常路径，风险低，仍建议收紧。

### 默认拒绝与负向测试

- API 测试走真实 Tomcat + 真实 `SecurityConfig`；匿名 401/403/404、平台管理员 403/404、健康检查 200。Worker 测试走 MockMvc + 安全过滤器链；非允许来源 401/403/404、健康检查 200。两者都断言响应体不含 `# HELP`/`# TYPE`/`riccon_*` 样本。
- EV-0534 日志确认两侧 Actuator 均暴露 3 个端点，排除“端点不存在导致 404”的假绿；API 测试还覆盖“已认证平台管理员仍需被拒”，证明指标读取不是普通业务 RBAC。
- F-001（P2）：Worker 基类 `@DynamicPropertySource` 的 `riccon.ops.allowed-cidrs=""` 覆盖了子类 `@TestPropertySource` 的 `10.99.0.0/16`。当前 denyAll 让测试通过；B2 实现 CIDR 后必须修正，否则空值按 ADR 语义放行 loopback，测试会错误失败或失去“非允许来源”证明力。详见 findings。

### 边界、秘密与覆盖率

- 本任务两次提交只触及写范围内的 7 个文件（2 pom、4 测试、1 脚本；落在 6 条 write_scope glob 内），未改生产源码、迁移、安全配置或合同；`git diff 62a9e0c HEAD -- <这 7 个路径>` 为空，后续提交未漂移。
- `git show e554b13` 未发现真实密钥（grep `AKIA`、`BEGIN ...`、`password/secret/api_key` 无命中）；Worker 测试基类里的 master key/pepper 是既有测试占位，非本任务新增。
- 覆盖率（EV-0537 `./mvnw -q -T1C verify`，exit 0，干净提交 617fa4b；jacoco.csv 生成于 00:30:20）：worker line **87.38%**（4267/4883）、branch **70.85%**（1327/1873）；API line 91.55%、branch 73.87%；均过 80/70。worker branch 仅高出 0.85pp，B4/B6/B7 新增生产代码后需模块 gate 重跑。

### 证据与 gate

- EV-0534：manual=false、dirty=false、exit=0、commit=9d07224、output_sha256 与文件一致（21194 bytes）；log 含 API/Worker 两个测试上下文与 `metrics-check` 自检输出。EV-0534 的 commit 晚于 end_commit=62a9e0c 但范围内代码一致（F-003，P3）。
- EV-0537：manual=false、dirty=false、exit=0、commit=617fa4b、hash 匹配；`verify` 覆盖 API/Worker 测试并生成 jacoco 报告/执行 jacoco:check。它是 Lead 的 L2 后端全量，不含前端/E2E，不能替代模块 gate。
- `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04` 当前 **FAIL**：TASK-0100/0101/0102 review 未 done、TASK-0103..0116 planned、无模块 review；这是模块未完成时的预期结果，不是本任务失败。gate 对 review 任务按 baseline..HEAD 报的 TASK-0102 “changed files outside write_scope” 是基线到 HEAD 含其他任务提交的噪声；本任务 end_commit 区间 `3ef774b..62a9e0c` 只有 7 个文件且都在写范围内（见 `git diff --name-only 3ef774b 62a9e0c`）。
- 任务状态保持 `review`，未标记 done。

## 证据

| 证据                                                                                                                       | 用途                                                                   | 结果                                        |
| -------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------- |
| EV-0534 / `.riccon/evidence/TASK-0102/EV-0534*.log`                                                                        | 定向 11 测试（API 4 + Worker 3 + 契约 4）+ 脚本 `--help`/`--self-test` | exit 0、dirty=false、hash 匹配              |
| EV-0537 / `.riccon/evidence/TASK-0100/EV-0537*.log`                                                                        | Lead L2 `./mvnw -q -T1C verify`（HEAD 617fa4b）                        | exit 0、dirty=false、含 jacoco report/check |
| `apps/{api,worker}/target/surefire-reports/*PrometheusExposureDenyTest*.txt`、`*M04MetricsContractTest*.txt`               | 新测试执行数与跳过数                                                   | API 4/0/0、Worker 3/0/0、契约 4/0/0         |
| `apps/{api,worker}/target/site/jacoco/jacoco.csv`                                                                          | 覆盖率                                                                 | worker 87.38%/70.85%；API 91.55%/73.87%     |
| `scripts/ops/metrics-check --self-test` / `--help`（本次独立运行）                                                         | 脚本自检与用法                                                         | 均 exit 0                                   |
| `./mvnw -q -pl apps/worker -am test -Dtest=M04MetricsContractTest -Dsurefire.failIfNoSpecifiedTests=false`（本次独立运行） | 契约/脚本交叉校验                                                      | exit 0；surefire 4 run / 0 fail / 0 skip    |
| `git show --stat e554b13 62a9e0c`；`git diff 62a9e0c HEAD -- <写范围>`                                                     | 变更面与后续漂移                                                       | 仅 7 个写范围内文件；后续无漂移             |
| ADR-0021 §10.6、THREAT B-20                                                                                                | 指标/暴露验收口径                                                      | 7 个指标与标签逐字对应                      |

## Findings

| ID    | 级别 | 状态                       | 标题                                             | 影响                                                            |
| ----- | ---- | -------------------------- | ------------------------------------------------ | --------------------------------------------------------------- |
| F-001 | P2   | accepted（B2 前修复/复核） | Worker 负向测试 allowed-cidrs 被基类动态属性覆盖 | 当前默认拒绝仍成立；B2 落地后可能误失败或失去“非允许来源”证明力 |
| F-002 | P3   | accepted                   | Java 契约与脚本禁止标签清单漂移（sessionId）     | `sessionId` 标签不会被 metrics-check 拒绝                       |
| F-003 | P3   | accepted                   | EV-0534 commit 晚于 end_commit                   | 证据代码一致，仅 provenance 不一致                              |
| F-004 | P3   | accepted                   | 标签检查其他非零 awk rc 被当成 OK                | 低概率内部错误可绕过“失败非 0”                                  |

## 未验证项

- **真实 Worker 抓取**：7 个 `riccon_*` 指标尚未由 B4（TASK-0105）注册，无法用 `--base-url` 对真实 Worker 验证“存在/标签正确”；本任务只验证脚本逻辑与自检。OPS-M04-02a 的真实抓取报告归 B4/V2。
- **B2 的 `RICCON_OPS_ALLOWED_CIDRS` 非允许来源拒绝**：B2 尚未实现；API 侧 `@TestPropertySource` 已设非 loopback 网段但当前无代码读取，Worker 侧如 F-001 所述被基类动态属性覆盖。当前只验证了默认 denyAll 基线。
- **模块级 E2E/a11y/security/perf/迁移**：任务级不跑；EV-0537 是后端 Maven 全量，不含前端/E2E。M04 模块 gate 需要 Lead/Verifier 在最终提交跑合同 test/coverage/E2E/a11y/security。
- **Trivy/依赖漏洞扫描**：新增 `micrometer-registry-prometheus` 未在本任务跑 Trivy；按 T4 口径在模块 release/security gate 覆盖。
- **Worker 负向测试的具体状态码**：测试只断言 401/403/404 且响应体无指标；日志未打印具体码。端点暴露已由 `Exposing 3 endpoints` 证明，未进一步区分 403 与 404 的来源。

## 剩余风险

- worker branch 覆盖率 70.85%，距 70% 门槛仅 0.85pp；B4/B6/B7 新代码可能把模块覆盖率拉低，模块 gate 必须重跑。
- ADR-0021 §10.6 不能被测试机器读取；Java 契约、测试内 ADR 清单（`ADR_METRIC_NAMES`）、脚本各有一份清单，改 ADR 时需人工同步三处；当前交叉校验只覆盖契约↔脚本。
- metrics-check 只强制 histogram 的 `_bucket`，counter/gauge 类型未在抓取脚本中断言；B4 注册类型错误时可能只被 Java 契约测试或人工评审发现。
- EV-0534 与 end_commit 的 provenance 差异（F-003）在 closeout 审计时可能被重复提及；建议 Lead 在 closeout 时统一。
- F-001 是 B2 的前置条件：B2 实现来源过滤时若不先修 Worker 测试基类的动态属性，负向测试会与“空=loopback”语义冲突。
