---
review_id: REV-TASK-0103-002
previous_review: REV-TASK-0103-001
module_id: M04
task_id: TASK-0103
reviewer:
  agent: riccon/verifier
  session: ses_e05eb9fecffeE1MdJRsf8d5JT2
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 4752c4f34d258fa649a74d072c992be8a7077c1a
  - 9b5d336471985b6cb6d51be8fdb3108b3a719a59
  - 76bfb225268f2982efdf30f94777385dd9d50daf
  - 46a6cebcd1d0fbba59c93aca3e615d6e5aa786ad
  - b09aca222ed667e6764b495e60843b1cf6cc5789
  - 10fbba2c27b1de7b4c4f8d99ad244dfc91804ce9
head_commit: 10fbba2c27b1de7b4c4f8d99ad244dfc91804ce9
task_end_commit: 46a6cebcd1d0fbba59c93aca3e615d6e5aa786ad
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/lead + riccon/builder-route"
    title: "高风险任务最终提交不是全量绿：EV-0541 仅定向，46a6ceb 上既有 CryptoStartupValidationTest 失败"
    summary: >-
      P1 已关闭。Lead 在最终 HEAD `10fbba2` 的干净树运行 `./mvnw -q -T1C verify`，EV-0544
      dirty=false、exit 0、commit 10fbba2、sha256 匹配；完整后端套件 929 tests / 0 fail / 0 error /
      0 skip，API/worker/platform-common coverage 分别 91.67/73.88、87.41/70.92、90.69/75.41，均过 80/70。
      `CryptoStartupValidationTest` 12 tests / 0 fail（02:08:23 surefire 报告），EV-0542 的历史失败已被
      b09aca2 修复并在 EV-0544 中通过。`git diff 46a6ceb 10fbba2 -- B2 生产/测试范围` 为空；两提交差异
      仅 `.riccon/**` 与 `apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java`，
      说明 EV-0544 覆盖的 B2 代码面与 end_commit 46a6ceb 一致。P1 不再成立。
    evidence: >-
      `.riccon/evidence/TASK-0103/EV-0544.json`（command `./mvnw -q -T1C verify`、commit 10fbba2、
      dirty=false、exit 0、sha256 dfa3b28e…、831078 bytes）；
      `.riccon/evidence/TASK-0103/EV-0544-*.log`（4629 行；无 `<<< FAILURE/ERROR`、无 `Failures: [1-9]`）；
      `apps/api/target/surefire-reports/com.riccon.api.crypto.CryptoStartupValidationTest.txt`（12/0/0/0）；
      `git diff --name-only 46a6ceb 10fbba2`（仅 `.riccon/**` 与 CryptoStartupValidationTest）；
      `git diff --name-only 46a6ceb 10fbba2 -- apps/api/src/main/java/com/riccon/api/model apps/api/src/main/java/com/riccon/api/admin/model apps/api/src/main/java/com/riccon/api/config apps/worker/src/main/java/com/riccon/worker/config platform/platform-common/src/main/java contracts`（空）。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect（到期 M04 gate 前）"
    title: "provider 改名（platform.model.manage 写操作）没有审计"
    summary: >-
      同意 Lead 的 P2 接受：provider 改名只改展示元数据，不改变路由/端点/凭据/启停；M04 的模型配置
      留痕由 `model.updated`（启停）、routing、credential 动作覆盖。残余要求：ADR-0021 §7.4 与
      ARCHITECTURE 记录“provider name 不属于 M04 审计动作”的范围说明；若用户或审计要求，再以
      `model.provider.updated` + MODEL_PROVIDER 目标类型补齐。接受不改变本轮技术 verdict。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/admin/model/provider/service/ModelProviderService.java:71-78`；
      ADR-0021 §7.4；PRODUCT §10.4；Lead 2026-11-21 书面接受。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect（到期 M04 gate 前）"
    title: "API 匿名非允许来源访问 /actuator/prometheus 返回 401，未满足 ADR 的 403/404"
    summary: >-
      同意 Lead 的 P2 接受：匿名请求先认证后授权，401 是认证语义；非允许来源已实际被拒，响应不含指标
      样本，不存在指标泄露或绕过。残余要求：Architect 在 gate 前澄清 ADR-0021 §2.2 与 ARCHITECTURE
      的 403/404 措辞（明确匿名 401 可接受）；测试保留 `in(401,403,404)`。若 ADR 坚持 403/404，则需
      改为来源拒绝入口并重开 P2。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:149-151`；
      `apps/api/src/main/java/com/riccon/api/web/JsonAuthenticationEntryPoint.java:19-54`；
      `apps/api/src/test/java/com/riccon/api/actuator/PrometheusExposureDenyTest.java:50-62`；
      Lead 2026-11-21 书面接受。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/ops（B10，到期 B10）"
    title: "缺少 ADR 要求的启动告警：PRODUCTION 空 RICCON_OPS_ALLOWED_CIDRS、端点配置与 DB 目录不一致"
    summary: >-
      同意 P3 接受并转 B10：安全行为不受影响；由 B10 在启动检查/RUNBOOK 补 WARN（不输出 URL）。
      到期 B10；M04 gate 前复核是否仍有缺口。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java:64-81`；
      `platform/platform-common/src/main/java/com/riccon/platform/common/model/ManagedEndpointRegistry.java:41-43`；
      ADR-0021 §2.1/§2.2；Lead 2026-11-21 书面接受。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-route（B2 follow-up；Lead 写的 B3/B5 可参考既有 AuditFailClosedTest）"
    title: "model.updated 审计失败回滚没有负向测试"
    summary: >-
      同意 P3 接受，但 owner 应明确为 builder-route/B2 follow-up：`PATCH /admin/models/{id}` 是 B2 的
      写端点，B3/B5 不拥有该端点；可复用 `AuditFailClosedTest` 的 `@MockitoBean AuditRepository` 模式，
      断言 500 且 DB 状态保持原值。到期 B3 done 或 M04 gate 前（取早）。
    evidence: >-
      `apps/api/src/main/java/com/riccon/api/admin/model/catalog/service/PlatformModelService.java:108-115`；
      `apps/api/src/main/java/com/riccon/api/audit/service/AuditService.java:35-37`；
      `apps/api/src/test/java/com/riccon/api/AuditFailClosedTest.java:31-50`；Lead 2026-11-21 书面接受。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder-failover（B4）"
    title: "Worker 空 allowed-cidrs 的 loopback 正例没有安全链集成测试；API/Worker CIDR 实现重复"
    summary: >-
      同意 P3 接受并转 B4：B4 补 Worker MockMvc 空配置 + 127.0.0.1 → 200 的集成正例，并评估把
      `AllowedCidrs` 抽到 platform-common 或加交叉测试，避免两份实现漂移。
    evidence: >-
      `apps/api/src/test/java/com/riccon/api/config/PrometheusLoopbackAccessTest.java:22-30`；
      `apps/worker/src/test/java/com/riccon/worker/actuator/PrometheusExposureDenyTest.java:54-68`；
      Lead 2026-11-21 书面接受。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/builder-route（B2 follow-up，到期 M04 gate 前）"
    title: "安全关键类 ManagedEndpointPolicy 类级分支覆盖率 66.8%；未覆盖 0.0.0.0/:: 等边界"
    summary: >-
      同意 P3 接受并转 B2 follow-up：gate 前补 unspecified 地址拒绝与配置/host/port/DNS 空结果等分支，
      重新生成 jacoco 并复核类级覆盖率；M04 gate 必须重跑 bundle 80/70。当前 bundle 75.41% 过线，
      但安全关键类分支低于类级 70% 仍需收敛。
    evidence: >-
      `platform/platform-common/target/site/jacoco/jacoco.csv`（ManagedEndpointPolicy 187/280 branch；
      bundle 90.69/75.41，EV-0544 生成）；`ManagedEndpointPolicy.java:390-443`；Lead 2026-11-21 书面接受。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/lead（closeout 说明）"
    title: "B2 修改了 B1a/B1c 拥有的测试文件；scope 补录已在 10fbba2 提交"
    summary: >-
      接受：B2 对 WorkerIntegrationTestBase/worker PrometheusExposureDenyTest 的修改来自 TASK-0102
      评审 F-001 的明确指派，任务 JSON 的 scope 补录已随 10fbba2 提交；Lead 在 closeout 记录该例外，
      避免后续任务照此扩大 scope。
    evidence: >-
      `git show 4752c4f -- apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java
      apps/worker/src/test/java/com/riccon/worker/actuator/PrometheusExposureDenyTest.java`；
      `.riccon/tasks/TASK-0103.json:24-28`；`.riccon/reviews/tasks/TASK-0102-review.md` F-001。
  - id: F-009
    severity: P2
    status: open
    owner: "riccon/lead（TASK-0103 done 前）"
    title: "gate 仍把 TASK-0103 报为 b09aca2 越界；“gate 区间不含 B1b 文件”未复现"
    summary: >-
      这是对 Lead 关闭说明的事实校正，不是新的代码失败。2026-11-21T02:17:31+08:00 实际运行
      `gate check --module M04` 仍输出 `task TASK-0103 changed files outside write_scope:
      apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java`。原因：TASK-0103 的
      baseline_commit=ef2b0f2、end_commit=46a6ceb，但 gate 的 write_scope 检查按 baseline..HEAD
      （HEAD=10fbba2）计算，把 b09aca2 的测试夹具修复算入 TASK-0103。EV-0544 已证明 B2 生产/测试范围
      与 46a6ceb 一致，故这是元数据/工具区间问题，不是 P1 复发；但 TASK-0103 在 done 前仍会被 gate
      拒绝。需 Lead 在 closeout 修正 baseline/end/scope 归属，或建独立 fix 任务承接 b09aca2，或调整
      gate 区间规则；修复后重跑 gate 并复核本 finding。
    evidence: >-
      `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04`（2026-11-21T02:17:31+08:00）；
      `.riccon/tasks/TASK-0103.json`（baseline ef2b0f2、end 46a6ceb）；
      `git diff --name-only 46a6ceb 10fbba2`；
      `docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md` §8.3；TASK-0102 评审 F-007 同类区间噪声。
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/lead + riccon/ops（M04 gate 前观察）"
    title: "EV-0544 全量绿，但 API Surefire fork 在 System.exit(0) 后 30s 被 kill"
    summary: >-
      EV-0544 日志第 4625 行有 `[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed
      30 seconds after System.exit(0).`；所有 929 个测试仍 0 fail/0 error/0 skip，Maven exit 0。它说明
      某个 API 测试 fork 的非 daemon 线程/清理未及时退出，可能造成 CI 偶发超时；不是测试失败，也不
      推翻全量绿。作为 P3 接受，建议 B4/B10 在模块 gate 前观察一次全量是否复现，并排查 Testcontainers/
      Spring context 清理。
    evidence: >-
      `.riccon/evidence/TASK-0103/EV-0544-*.log:4625`；
      `apps/api/target/surefire-reports/*`（146 个报告、929 tests、0 fail/0 error/0 skip）。
evidence:
  - EV-0537
  - EV-0538
  - EV-0539
  - EV-0540
  - EV-0541
  - EV-0542
  - EV-0543
  - EV-0544
reviewed_evidence:
  - EV-0541
  - EV-0542
  - EV-0543
  - EV-0544
notes: >-
  本复评只针对 TASK-0103 的 4 个实现提交及其后的 b09aca2 测试夹具修复、10fbba2 集成/元数据提交；
  EV-0544 是 Lead 在 10fbba2 干净树上的后端全量 verify。任务级评审不替代 M04 模块评审、Product 预验收
  或用户验收；TASK-0103 状态保持 review，未标 done。
created_at: "2026-11-21T02:19:00+08:00"
---

# REV-TASK-0103-002：TASK-0103 / M04-B2 P1 关闭复评

本复评是 `REV-TASK-0103-001` 的增量评审，只做任务级独立技术验收；不修改代码/契约、不提交、不标 done，不替代 M04 模块评审、Product 预验收或用户产品验收。

## 结论

**pass（任务级技术验收）**。P1 F-001 已关闭：EV-0544 在最终 HEAD `10fbba2` 的干净树跑完整后端 `./mvnw -q -T1C verify`，929 tests / 0 fail / 0 error / 0 skip，三模块覆盖率均过 80/70；`CryptoStartupValidationTest` 12/0/0，历史 46a6ceb 失败已被 b09aca2 修复并在全量中通过。F-002/F-003 按 Lead 的 P2 接受成立，F-004..F-008 按 P3 accepted 跟踪，F-010 为新 P3 观察。**但 F-009（P2 open）是事实校正：实际 gate 仍把 b09aca2 的 `CryptoStartupValidationTest` 报为 TASK-0103 out-of-scope，Lead “gate 区间不含 B1b 文件”的说法未复现**；TASK-0103 在 done 前仍需修元数据/区间。当前 M04 gate 仍应失败（TASK-0103 review、F-009、其他任务 planned、无 M04 模块评审），本复评不改变模块 gate。

## P1 关闭证据

| 项             | 观察                                                                                                                                                                                                                                             |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| EV-0544 元数据 | `id=EV-0544`、task TASK-0103、command `./mvnw -q -T1C verify`、manual=false、commit `10fbba2`、dirty=false、exit 0、output 831078 bytes、sha256 `dfa3b28e…` 与文件一致。                                                                         |
| 全量套件       | `./mvnw -q -T1C verify` 覆盖 API/worker/platform-common/platform-domain/architecture-tests；146 个 surefire 报告合计 929 tests、0 fail、0 error、0 skip；无 `<<< FAILURE`/`<<< ERROR`/`BUILD FAILURE`。                                          |
| 覆盖率         | API 91.67% line / 73.88% branch；worker 87.41% / 70.92%；platform-common 90.69% / 75.41%；jacoco.csv 由 EV-0544 生成（02:06–02:16），均过合同 80/70。                                                                                            |
| 历史失败       | EV-0542（TASK-0101，commit 46a6ceb、clean、exit 1）记录 `CryptoStartupValidationTest.startupFailure_neverLogsMasterKey` 失败；b09aca2 补合法端点后 EV-0543 exit 0；EV-0544 中该测试 12/0/0/0（02:08:23 报告）。                                  |
| 代码面一致性   | `git diff --name-only 46a6ceb 10fbba2` 仅 `.riccon/**` 与 `apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java`；对 B2 生产代码、B2 测试、contracts 的 diff 为空。EV-0544 覆盖的 B2 代码面与 end_commit 46a6ceb 一致。 |
| 结论           | P1 关闭；不再以“最终提交红/全量未覆盖”判 fail。剩余的是 F-009 gate 元数据问题，不是 P1 复发。                                                                                                                                                    |

## P2/P3 接受复核

### F-002（P2 → accepted，同意）

- Lead 理由成立：provider 改名只改展示元数据，不影响路由、允许列表、凭据、启停；这些模型配置的留痕由 `model.updated`、`model.routing.updated`、`model.credential.*` 覆盖。
- 残余：ADR-0021 §7.4 与 ARCHITECTURE 需要记录“provider name 不属于 M04 审计动作”的范围说明；若用户/审计要求，再补 `model.provider.updated` + MODEL_PROVIDER。到期 M04 gate 前。
- 我同意 P2 accepted，不升 P1；本轮技术 verdict 不受影响。

### F-003（P2 → accepted，同意）

- 匿名非允许来源返回 401 是“先认证后授权”的认证语义；非允许来源已被拒绝，响应不含 `# HELP`/`# TYPE`/`riccon_*` 样本，无指标泄露或绕过。
- 残余：Architect 在 gate 前澄清 ADR-0021 §2.2 与 ARCHITECTURE 的 403/404 措辞，明确匿名 401 可接受；测试保留 `in(401,403,404)`。若坚持 403/404，则需改拒绝入口并重开 P2。
- 我同意 P2 accepted；没有新的安全阻断。

### F-004..F-008（P3 → accepted，同意，含归属校正）

| finding                              | 结论                                              | 备注                                                                                                                                                  |
| ------------------------------------ | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-004 启动 WARN                      | accepted，B10                                     | 安全行为不受影响；B10 补 PRODUCTION 空 CIDR 与目录漂移 WARN，不输出 URL。                                                                             |
| F-005 `model.updated` 审计失败测试   | accepted，owner 校正为 builder-route/B2 follow-up | `PATCH /admin/models` 是 B2 端点，B3/B5 不拥有；可复用 `AuditFailClosedTest` 的 `@MockitoBean AuditRepository`，到期 B3 done 或 M04 gate 前（取早）。 |
| F-006 Worker loopback 正例/CIDR 去重 | accepted，B4                                      | B4 补 Worker MockMvc 空配置 127.0.0.1 → 200；评估抽到 platform-common。                                                                               |
| F-007 0.0.0.0/:: 与类级覆盖率        | accepted，B2 follow-up，gate 前                   | 补 unspecified 地址与配置/host/port/DNS 边界，重跑 jacoco；bundle 75.41% 当前过线，类级 66.8% 需收敛。                                                |
| F-008 测试目录 scope                 | accepted                                          | B1c F-001 明确指派 B2；scope 补录已随 10fbba2 提交；Lead closeout 记录例外。                                                                          |

### 新 F-009（P2 open，事实校正）

实际运行 `gate check --module M04`（2026-11-21T02:17:31+08:00）仍输出：

```text
✗ task TASK-0103 changed files outside write_scope: apps/api/src/test/java/com/riccon/api/crypto/CryptoStartupValidationTest.java
```

这与 Lead 的“gate 区间不含 B1b 文件”不一致。原因边界：TASK-0103 的 `baseline_commit=ef2b0f2`、`end_commit=46a6ceb`，但 gate 的 write_scope 检查按 baseline..HEAD（HEAD=10fbba2）计算，b09aca2 的测试夹具因此落入 TASK-0103 区间；EV-0544 已证明 B2 生产/测试代码与 46a6ceb 一致，所以这是元数据/工具区间问题，不是 P1 复发或代码越界。TASK-0103 done 前必须由 Lead 修正 baseline/end/scope 归属、建独立 fix 任务承接 b09aca2，或调整 gate 区间规则；修复后重跑 gate。该 P2 open 不改变本轮技术 pass，但仍是 closeout blocker。

### 新 F-010（P3 accepted）

EV-0544 日志 4625 行：`[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).` 所有 929 个测试仍 0 fail/0 error/0 skip，Maven exit 0；说明某个 API 测试 fork 的非 daemon 线程/清理未及时退出，可能造成 CI 偶发超时，不是测试失败。接受 P3，建议 B4/B10 在模块 gate 前观察是否复现并排查 Testcontainers/Spring context 清理。

## 验收结论保持

上一轮 6 条验收的代码/测试结论不变：108a/108b 契约、三端点与权限、URL 负向矩阵、内层 `max-attempts=1`、Actuator 来源过滤、契约唯一 writer/零回归均通过；本轮没有新增 P0/P1。EV-0544 只补强“高风险任务最终提交全量绿”的证据，不改变 F-002/F-003/F-004..F-008 的残余范围。

## 未验证项

- **模块级 E2E/a11y/perf/security/Trivy**：EV-0544 是后端 Maven 全量，不含前端、E2E、axe、k6/Trivy；由 M04 模块 gate 执行。
- **真实 DNS/出网/反向代理**：端点策略仍用固定 HostResolver/字面量；生产 DNS、K8s ingress、Prometheus 真实抓取、`X-Forwarded-For` 未验证。
- **配额/成本运行时**：108b 只做契约，B6/B7 端点未调用。
- **provider 改名审计、401 状态码、启动 WARN、Worker loopback 正例、model.updated 审计失败、unspecified 地址**：均按 accepted 跟踪，未在本轮修复；gate 前复核。
- **F-009 gate 元数据**：仍未修复；`gate check --module M04` 继续 fail，TASK-0103 不能 done。
- **任务元数据提交**：EV-0544 与 TASK-0103 任务卡的 evidence 更新目前在 worktree（未提交）；Lead closeout 需提交并复核。

## 剩余风险

- F-009 是 closeout blocker：即使代码/证据已绿，TASK-0103 仍会被 gate 报越界；需 Lead 修正区间或任务归属。
- worker branch 70.92% 仅比 70% 高 0.92pp；B4/B5/B6/B7 新增代码后模块 gate 必须重跑。
- EV-0544 的 Surefire fork kill（F-010）可能造成 CI 偶发超时；建议 gate 前再跑一次全量确认。
- provider 改名审计（F-002）与 401 状态码（F-003）依赖 ADR/ARCH 的书面澄清；未澄清前仍是 T4 审计/契约口径残余。
- F-007 的 unspecified 地址与类级分支覆盖尚未收敛；网关正式上线前建议补齐。
