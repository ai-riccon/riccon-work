---
review_id: REV-M04-003
previous_review: REV-TASK-0115-002
module_id: M04
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e03bda5e8ffexbPqdZ2ZasFHH2
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
head_commit: b6fbff470e8f1caf3564da8fd22a081224e04c64
verdict: fail
reviewed_evidence:
  - EV-0640
  - EV-0641
  - EV-0642
  - EV-0643
  - EV-0644
  - EV-0645
  - EV-0646
  - EV-0647
  - EV-0648
  - EV-0649
  - EV-0650
  - EV-0651
  - EV-0652
  - EV-0653
  - EV-0654
  - EV-0655
  - EV-0656
  - EV-0657
  - EV-0658
  - EV-0659
  - EV-0522
  - EV-0523
  - EV-0525
  - EV-0526
  - EV-0527
  - EV-0528
  - EV-0534
  - EV-0537
  - EV-0543
  - EV-0544
  - EV-0545
  - EV-0546
  - EV-0552
  - EV-0553
  - EV-0554
  - EV-0555
  - EV-0556
  - EV-0557
  - EV-0560
  - EV-0564
  - EV-0583
  - EV-0591
  - EV-0600
  - EV-0601
  - EV-0602
  - EV-0603
  - EV-0604
  - EV-0605
  - EV-0606
  - EV-0607
  - EV-0610
  - EV-0613
  - EV-0622
  - EV-0634
  - EV-0635
  - EV-0636
  - EV-0639
dod:
  acceptance: false
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
dod_evidence:
  build:
    - EV-0642
  tests:
    - EV-0641
  coverage:
    - EV-0641
  e2e:
    - EV-0643
  docs:
    - EV-0659
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/builder-metrics（B6/B7）"
    title: "API 配额存储故障指标名与 ADR/THREAT B-20 不一致：实现为 riccon_quota_store_failures_total，合同要求 riccon_quota_store_failure_total"
    evidence: "EV-0650；apps/api/src/main/java/com/riccon/api/quota/service/QuotaMetrics.java:34；docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:871；docs/security/THREAT-MODEL.md:626"
  - id: F-002
    severity: P1
    status: open
    owner: "riccon/builder-core（TASK-0127）"
    title: "SchemaVersionChecker 在真实 Worker 中从未运行：@ConditionalOnBean(JdbcClient.class) 在用户组件阶段不匹配自动配置 Bean，导致 V11 schema 严格校验与 RICCON-WORKER-002 回滚 WARN 均失效"
    evidence: "EV-0650（worker.log 无 schema.version.checked；validation=false 启动无 RICCON-WORKER-002）；apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:41"
  - id: F-003
    severity: P1
    status: open
    owner: "riccon/builder-core / riccon/ops（PERF-M04-001）"
    title: "PERF-M04-001 未通过：first_token 场景 50 并发 API p95=519.459ms，超过合同 500ms 预算"
    evidence: "EV-0657；.riccon/evidence/TASK-0116/perf-m04-first_token.json"
  - id: F-004
    severity: P1
    status: open
    owner: "riccon/builder-gateway-e2e（B8/B11a）"
    title: "PERF-M04-002 无法取证：k6 同 VU 三次登录（平台/租户管理员/员工）后，per-VU cookie jar 覆盖显式 platform Cookie，平台读开关返回 403，配额争用场景未执行"
    evidence: "EV-0657；.riccon/evidence/TASK-0116/perf-m04-quota.log；e2e/perf/mvc-smoke.js:413-419"
  - id: F-005
    severity: P2
    status: open
    owner: "riccon/ops（B10）"
    title: "restore-drill 同实例演练缺少对演练库的应用账号授权步骤：credential check 以 riccon 用户访问 riccon_restore_drill 报 ERROR 1044；RUNBOOK M04-9 未记录 GRANT"
    evidence: "EV-0652；EV-0653（授权后通过）；docs/RUNBOOK.md M04-9"
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-key / riccon/builder-gateway-e2e（V1 F-006，承接）"
    title: "AC-07 在途轮换仍只证明旧密文可解密，未覆盖真实并发流式请求"
    evidence: "TASK-0115-report-002 F-006；EV-0640"
  - id: F-007
    severity: P2
    status: open
    owner: "riccon/lead（TASK-0115 closeout）"
    title: "TASK-0115 已 done 但 gate 对 review 任务按 baseline..HEAD 计算 write_scope，会把修复提交算到 TASK-0115（V1 F-014）"
    evidence: "TASK-0115-report-002 F-014"
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-route（V1 F-013，承接）"
    title: "非 503 Worker detail 仍写入 run.error_message；当前 detail 为静态脱敏文本，风险有界（V1 F-013）"
    evidence: "TASK-0115-report-002 F-013；EV-0640 ChatModelUnavailableApiTest 6/6"
  - id: F-009
    severity: P3
    status: open
    owner: "riccon/ops（B10）"
    title: "restore-drill 报告仍为 partial（J1 skipped）；V2 以独立 API/Worker 在恢复库上补跑 J1 并读取历史运行详情，但脚本本身不支持一次完成"
    evidence: "EV-0653；EV-0654"
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/builder-route（V1 残余，承接）"
    title: "AC-01 仍无单条 UI 保存→下一次 CHAT 请求的跨组件 E2E"
    evidence: "TASK-0115-report-002 AC-01 残余"
created_at: "2026-11-21T13:32:00+08:00"
---

# M04 模型网关纵深 · 模块评审（V2 最终全量）

## 结论

**verdict: fail。** 18 条 AC 中 16 条 pass、1 条 fail（AC-17）、1 条 partial（AC-18）；4 条 P1 未修复（F-001..F-004）。模块 gate 不能通过；不声称 Product 预验收、用户验收或发布批准（M04 仍为 `building`，`acceptance: product=pending user=pending`）。

- V1（TASK-0115 / REV-TASK-0115-002）负责的 AC-01、AC-03..16 在本最终提交上独立抽样复跑后保持 pass；V2 新增独立类覆盖 AC-02 整条与 AC-18 开关关闭/历史保留。
- 失败集中在可观测性契约（B-20）、回滚窗口安全控制（B-21/AC-18）与性能（AC-17）。
- 本评审与 V1 使用不同会话、独立夹具与命令；V1 报告仅作对照，不作为本评审证据。

## 验证环境

| 项             | 值                                                                                                                                     |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 最终提交       | `b6fbff470e8f1caf3564da8fd22a081224e04c64`（评审写入前；评审提交另计）                                                                 |
| 主机           | macOS 24.6.0 x64，Intel i7-9750H，12 vCPU，16 GiB                                                                                      |
| 运行时         | JDK 21.0.13、Node 26.13.0、pnpm 10.34.9、Maven 3.10 wrapper、Docker 29.9.3                                                             |
| 数据库         | 合同版本 MySQL 8.4.13（compose `riccon-test-mysql` 与 Testcontainers 一次性实例）                                                      |
| perf           | k6 v2.4.0（commit dd9395906d, go1.26.11, darwin/amd64）；`RICCON_FAKE_MODEL=1`；`RICCON_CHAT_MAX_STREAMS=50`、`RICCON_DB_POOL_SIZE=25` |
| OpenSearch     | perf 模式声明 excluded；主机仍运行 compose 的 OpenSearch/RustFS（未停，属环境噪声）                                                    |
| 密钥处理       | 主密钥/pepper 由 Verifier 在仓库外临时文件生成，仅以 `$(cat …)` 注入；未写入仓库、日志或 EV 命令文本                                   |
| deploy_preview | **not-applicable**（M09 交付物不存在，按 ADR-0021 §10.2）                                                                              |

## 命令与证据（最终提交、clean tree）

| #   | 命令                                                                                                                                        | 结果                                                                                                                                            | EV                                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| 1   | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                                         | exit 0；Java 465 tests / 0 fail / 0 skip；web 493 tests / 57 files；Java LINE 90.76%、BRANCH 73.17%；web lines 86.98%、branches 80.01%          | **EV-0641**                                    |
| 2   | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                                       | exit 0                                                                                                                                          | **EV-0642**                                    |
| 3   | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                                      | exit 0；40 passed / 0 skipped                                                                                                                   | **EV-0643**                                    |
| 4   | `pnpm -w a11y`                                                                                                                              | exit 0；15 passed（含 E2E-M04-07a/07b 四页 axe/375px/键盘）                                                                                     | **EV-0644**                                    |
| 5   | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .`                    | exit 0；仅 ADR-0011 已登记例外（CVE-2026-35568，exp 2027-03-31）                                                                                | **EV-0645**                                    |
| 6   | `REAL_EVIDENCE_TASK=TASK-0116 node scripts/real-stack/run.mjs --mode=full`                                                                  | exit 0；compose/fingerprint/seed/seedIdempotent/m10/browserE2e(25 passed)/j2/security/m04/m03 全 passed                                         | **EV-0646**                                    |
| 7   | 定向复验：16 个类（M04Verifier*、M04V2VerifierBoundaryTest、ModelRouting*、Quota*、Cost*、ModelContract108a/b、ModelFailover/CostSnapshot） | exit 0；65 tests / 0 fail / 0 skip                                                                                                              | **EV-0640**                                    |
| 8   | `scripts/ops/backfill-cost-day.mjs`（dry-run → apply → apply）                                                                              | exit 0；pending 1 → updated 1 → 0，幂等                                                                                                         | **EV-0651**                                    |
| 9   | `restore-drill.mjs --apply --keep --allow-partial` + 主密钥/pepper                                                                          | exit 0；表 46/46、触发器 2、V11、credential check passed；RTO 3.1s、RPO 声明 24h                                                                | **EV-0653**（首跑授权失败见 EV-0652）          |
| 10  | 恢复库 API/Worker/假模型 J1 + 历史运行详情                                                                                                  | exit 0；J1 run SUCCEEDED、历史 run 详情含模型标识                                                                                               | **EV-0654**                                    |
| 11  | `rewrap-credentials.mjs`（verify-only → apply mk-1→mk-2 → verify-only ×2）                                                                  | exit 0；2 个 DEK rewrap、2 个 provider credential 解密/fingerprint 校验通过                                                                     | **EV-0655**                                    |
| 12  | 回滚重放 SQL + 断言（一次性 `riccon_rollback_v2`，MySQL 8.4.13）                                                                            | exit 0；MAX(version)=10、每组 1 行、2 行归档、旧唯一键恢复、6 张新表删除、成本列保留、旧应用历史可读                                            | **EV-0656**                                    |
| 13  | `REAL_EVIDENCE_TASK=TASK-0116 node scripts/real-stack/run.mjs --mode=perf --perf-modes=server_error,rate_limited,unauthorized,timeout`      | **exit 1**；first_token API p95 519.459ms（>500ms）；quota 场景 403（脚本缺陷）；其余 failover 4 模式 100% 成功、cost p95 65ms                  | **EV-0657**                                    |
| 14  | 运行时验证（Verifier 资产 `m04-v2-runtime-verify.mjs`）：AC-18 开关关闭 J1/J2、7+2+3 指标、Worker WARN                                      | **exit 1**；AC-18 J1/J2 passed、OPS-M04-02a passed、指标 2 主 + 2 辅助 passed；3 项失败：存储故障指标名、WARN 0 次、SchemaVersionChecker 未运行 | **EV-0650**（前序失败 EV-0647/0648/0649 保留） |
| 15  | 文档核对（RUNBOOK M04-7、WARN、THREAT B-18/B-21、ADR-0021/0022 accepted）                                                                   | exit 0                                                                                                                                          | **EV-0659**（大小写失败的 EV-0658 保留）       |

命令调整说明：perf 使用合同 `commands.perf` 的编排入口 `run.mjs --mode=perf`（内部执行 `k6 run e2e/perf/mvc-smoke.js`），因为裸 k6 需要环境快照/种子/开关；真实栈与 drills 额外注入仓库外主密钥以便 rewrap/restore 解密，不影响被测代码与错误暴露面。上述调整均未放宽断言。

## AC 对账（AC-01..18）

| AC    | 结论（V1/V2）                 | 用户可见结果与证据                                                                                                                                                                                                                                                          |
| ----- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-01 | pass（V1 + V2 复跑）          | 路由页保存有序链、停用/启用、下一次读取生效：E2E-M04-01（EV-0643/0646）、IT-MODEL-101（EV-0640）。残余 P3 F-010。                                                                                                                                                           |
| AC-02 | **pass（V2 判定）**           | 租户管理员/员工写模型/key/开关 403、配额写 403、跨租户 404、平台聚合无用户明细、`groupBy=USER` 400：`M04V2VerifierBoundaryTest` 4/4（EV-0640）。界面无写入口：E2E-M04-07a/07b 角色断言（EV-0643/0644）。路由审计含操作者/动作/摘要/结果，审计失败 500 且链回滚（EV-0640）。 |
| AC-03 | pass（V1 + V2 复跑）          | 首字前 429/5xx/401/超时降级 100% 成功：ModelFailoverIntegrationTest 17/17（EV-0640）；perf failover 4 模式 `m04_degraded_success=1`（EV-0657）；真实栈 E2E-M04-02（EV-0646）。                                                                                              |
| AC-04 | pass（V1）                    | 首字后断流内容保留、不重放、重试：IT-MODEL-104 + E2E-M04-03（EV-0643/0646）。                                                                                                                                                                                               |
| AC-05 | pass（V1 + V2 复跑）          | 全链不可用稳定 `RICCON-MODEL-503` 固定中文文案、无内部信息：ChatModelUnavailableApiTest 6/6（EV-0640）；真实栈 M04-AC-05（EV-0646）。                                                                                                                                       |
| AC-06 | pass（V1 + V2 复跑）          | 多 key 停用/失效/恢复、provider 不整体失效、key 生命周期审计：IT-MODEL-105 + ModelFailoverIntegrationTest（EV-0640）。                                                                                                                                                      |
| AC-07 | pass（带 P2 F-006）           | 轮换在途/掩码/审计：E2E-M04-04 + IT-MODEL-106（EV-0640/0643）；真实并发流式在途未覆盖（accepted）。                                                                                                                                                                         |
| AC-08 | pass（V1 + V2 复跑）          | 配额超限 429、重置时间、不产生模型调用、拒绝审计：QuotaAdmissionApiTest 6/6（EV-0640）；E2E-M04-05（EV-0643/0646）。                                                                                                                                                        |
| AC-09 | pass（集成）；perf 子项未取证 | IT-QUOTA-102 50 并发 1 成功/49×429/无 500（EV-0640）；`PERF-M04-002` 因 F-004 未执行，性能口径归入 AC-17 fail。                                                                                                                                                             |
| AC-10 | pass（V1 + V2 复跑）          | 租户管理员本租户只读、平台聚合无用户明细、配额变更/拒绝审计：QuotaAdminApiTest 7/7（EV-0640）。                                                                                                                                                                             |
| AC-11 | pass（V1 + V2 复跑）          | 价格快照、6 位小数、未定价/用量未知单列、固定免责文案：CostApiTest 4/4（EV-0640）；E2E-M04-06（EV-0643/0646）。                                                                                                                                                             |
| AC-12 | pass（V1 + V2 复跑）          | 平台无用户明细、租户隔离、跨租户 404、员工无金额：CostAdminApiTest 3/3 + `M04V2VerifierBoundaryTest`（EV-0640）；E2E-M04-06（EV-0643/0646）。                                                                                                                               |
| AC-13 | pass（V1）                    | 四入口按角色可见、四页五态、密钥只掩码：E2E-M04-07a/07b（EV-0643/0644/0646）。                                                                                                                                                                                              |
| AC-14 | pass（V1 + V2 a11y）          | 375px 无横向滚动、键盘三条路径、axe 无 serious/critical：EV-0644 15/15（含四页）。                                                                                                                                                                                          |
| AC-15 | pass（V1 + V2 复跑）          | 端点标签、部署字段不可写、非 HTTPS/userinfo/query/允许列表外拒绝且不出网、无 key 明文：M04VerifierProductionEndpointTest + M04VerifierGatewaySecurityTest（EV-0640）。                                                                                                      |
| AC-16 | pass（V1 + V2 复跑）          | J1/J2/M03/M10 无回归、历史调用可读、三契约端点非 404：EV-0640 ModelContract108a/b；EV-0643 E2E-M04-08；EV-0646 真实栈 25 browser + J2/M03/M10。                                                                                                                             |
| AC-17 | **fail（V2）**                | 首字 p95 33ms、降级成功率 100%、成本 p95 65ms 通过；**API p95 519.459ms > 500ms**（F-003），**PERF-M04-002 未执行**（F-004）。                                                                                                                                              |
| AC-18 | **partial / fail（V2）**      | 开关关闭后 J1/J2 仍可用（EV-0650/0654）、变更 4 条审计、schema 仍 V11 且成本快照不变（EV-0656）通过；**回滚窗口固定 WARN `RICCON-WORKER-002` 未出现、schema 校验未运行**（F-002），OPS-M04-01 不完整。                                                                      |

## 性能（PERF-M04-001/002）

| 指标                                                         | 预算                    | 实测                             | 结论              |
| ------------------------------------------------------------ | ----------------------- | -------------------------------- | ----------------- |
| 首字 p95（first_token，50 VU）                               | ≤3000ms                 | 33ms                             | pass              |
| API p95（first_token，50 VU）                                | ≤500ms                  | **519.459ms**                    | **fail（F-003）** |
| 降级成功率（server_error/rate_limited/unauthorized/timeout） | 100%                    | 1.0 / 1.0 / 1.0 / 1.0            | pass              |
| 降级首字 p95                                                 | ≤3000ms                 | 27 / 25 / 25 / 1061.5ms          | pass              |
| 成本查询 p95（30/180 天，租户+平台）                         | ≤2000ms                 | 65ms；ok rate 1                  | pass              |
| 配额零超卖（50 并发，限额=已用+1）                           | 1 allowed / 49 rejected | **未执行**（403 脚本缺陷 F-004） | **fail**          |

报告与日志：`.riccon/evidence/TASK-0116/perf-m04-matrix.json`、`perf-m04-*.json/.log`；主机与 k6 版本见 `real-stack-env.json`/matrix env。

## 迁移 / 回滚 / 备份 / 指标

- **V11 forward/rollback**：合同全量 `verify` 中 `V11MigrationTest` 16/16（EV-0641）；V2 独立在一次性 `riccon_rollback_v2`（MySQL 8.4.13 快照）重放 ADR-0021 §10.3 回滚 SQL：多 key 收敛 2 行归档、每组 1 行、旧唯一键恢复、6 张新表删除、`model_invocation` 成本列保留、旧应用历史投影可读、`MAX(version)=10`（EV-0656）。
- **备份恢复**：结构 46 表/2 触发器/V11/credential check passed，RTO 3.1s ≤4h、RPO 声明 24h ≤24h（EV-0653）；恢复库 J1 与历史运行详情通过（EV-0654）；首跑因演练库未授权失败（F-005，EV-0652）。
- **rewrap**：mk-1→mk-2，2 个 DEK rewrap、2 个凭据解密与 fingerprint 校验通过，双密钥窗口与移除旧钥后均通过（EV-0655）。
- **backfill-cost-day**：pending 1→0，第二次 apply 0 行，幂等（EV-0651）。
- **Worker 7+2+3 指标**：`metrics-check --base-url` Worker 7 指标通过（EV-0650 `opsM0402a`）；API 抓取 `riccon_quota_rejections_total{TENANT,DAILY}=1`、`riccon_quota_usage_ratio` 4 序列、`riccon_cost_report_queries_total`、`riccon_quota_over_limit_observed_total` 通过；`riccon_quota_store_failure_total` 不存在，实际为 `riccon_quota_store_failures_total{reason="lock_wait"}`（F-001）。
- **回滚窗口 WARN**：`RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动 60s 未出现 `RICCON-WORKER-002`；校验启用时也无 `schema.version.checked`。根因 `@ConditionalOnBean(JdbcClient.class)`（F-002）。

## Findings

### F-001（P1 open）API 存储故障指标名与合同不一致

- 观察：抓取 API `/actuator/prometheus` 得到 `riccon_quota_store_failures_total{reason="lock_wait"} 1.0`；ADR-0021 §10.6 与 THREAT B-20 要求 `riccon_quota_store_failure_total`。
- 位置：`apps/api/src/main/java/com/riccon/api/quota/service/QuotaMetrics.java:34` 定义为 `riccon.quota.store.failures`（Micrometer 输出复数 total）。
- 影响：按 ADR 配置的告警/看板查不到配额存储故障（fail-closed 503），B-20 不成立；AC-17 的“运维可观察”主张不能按合同名字验收。
- 期望：改为单数 `riccon.quota.store.failure`（或先改 ADR/THREAT 并走合同评审），补 API 测试断言 Prometheus 名称。

### F-002（P1 open）SchemaVersionChecker 未运行

- 观察：所有真实 Worker 启动日志均无 `schema.version.checked`；`RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动无 `RICCON-WORKER-002`（0 次，等待 60s）。
- 位置：`apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:40-42`。`@ConditionalOnBean(JdbcClient.class)` 加在用户 `@Component` 上，条件在自动配置注册 `JdbcClient` 之前评估，Bean 永不注册；单元测试直接 new 对象，未覆盖真实上下文。
- 影响：V11 后旧 Worker 不会因版本不符失败（T4 迁移/回滚安全控制形同虚设）；回滚窗口无法输出固定 WARN，AC-18/OPS-M04-01/B-21 的判据缺失。
- 期望：移除 `@ConditionalOnBean`（JdbcClient 为必需依赖）或在自动配置后注册；补一个真实 Spring Boot 上下文启动测试，断言启用时 INFO、禁用时 WARN 恰好一次。

### F-003（P1 open）PERF-M04-001 API p95 超预算

- 观察：first_token 50 并发 `m04_api_ms` p95=519.459ms（count 898，avg 354.6ms），超过 500ms；同批 failover 场景 410–439ms 通过。
- 证据：EV-0657、`perf-m04-first_token.json`。
- 影响：AC-17 的 API p95 预算不成立；不能以“接近”判 pass。
- 期望：定位 50 VU 下 run-detail 轮询/登录/列表端点的 p95 热点，或按合同重新评估测量口径（不得静默放宽）；修复后按合同 perf 命令复测并记录同一主机规格。

### F-004（P1 open）PERF-M04-002 配额场景被脚本缺陷阻断

- 观察：quota 场景 `read switches failed: 403`；平台/租户管理员/员工三次登录共享同 VU cookie jar，后续显式 `cookie` 头被 jar 的成员会话覆盖，平台读 → 403。最小 k6 复现：平台登录→成员登录→带平台 cookie GET switches = 403。
- 位置：`e2e/perf/mvc-smoke.js:413-419`（quotaContention 三次 login）与 `api()` 的 cookie 传递。
- 影响：PERF-M04-002 零超卖未取证，AC-17 fail；不是产品 403 缺陷，但证据链不能成立。
- 期望：为每个角色使用独立 cookie jar（k6 `http.cookieJar()`）或在 login 间清空 jar；补脚本自检，确保 quota 场景 1/49 断言可重复。

### F-005（P2 open）restore-drill 缺演练库授权步骤

- 观察：首次 restore drill `credentialCheck=failed`，`rewrap-credentials` 以 `riccon` 用户访问 `riccon_restore_drill` 报 `ERROR 1044`；root 授权后 EV-0653 通过。
- 影响：RUNBOOK M04-9 的“完整演练”命令在测试栈不可直接复现；生产最小权限账号也需等价授权。
- 期望：RUNBOOK 增加授权/回收步骤，或脚本在创建演练库后用管理账号授予应用账号只读权限。

### F-006（P2 accepted）AC-07 在途轮换并发

沿用 V1 F-006：真实并发流式请求未覆盖；当前证据为 E2E-M04-04 影响说明 + IT-MODEL-106 密文可解。接受理由：风险有界、key 审计由 AC-06 覆盖；后续测试整理承接。

### F-007（P2 open）TASK-0115 gate write_scope 误报

沿用 V1 F-014：TASK-0115 已 done，gate 按 `baseline..HEAD` 计算会把 TASK-0132/0133/0134 的修复文件列为越界。Lead 需调整 baseline 或记录 reviewed_commits，否则 gate 的 scope 噪声无法消除。

### F-008（P2 accepted）非 503 detail 入库

沿用 V1 F-013：`ChatService` 对非 `RICCON-MODEL-503` 仍把 Worker `detail` 写入 `run.error_message`；当前 detail 为静态脱敏文本，风险有界。V2 复跑 ChatModelUnavailableApiTest 6/6 未发现新增泄露。

### F-009（P3 open）/ F-010（P3 accepted）

- F-009：restore-drill 报告仍是 partial（J1 skipped），V2 用独立脚本补跑；建议脚本支持一次完成。
- F-010：AC-01 缺单条 UI 保存→下一次 CHAT 请求的跨组件 E2E（V1 残余，不阻塞）。

## 边界与下一步

- 本评审是独立技术验收，不是 Product 预验收或用户产品验收；M04 仍 `building`，`acceptance: product=pending user=pending`。
- `deploy_preview`/`deploy_prod` 为 not-applicable（M09）；本评审不声称生产可用或发布批准。
- 未验证：真实供应商端点、跨区域容灾、生产规模数据（>200 万行）迁移窗口、旧 Worker 二进制本体（仅有当前 Worker 的 WARN/版本校验证据）。
- 建议 Lead：先建 `kind: fix` 任务修复 F-001/F-002/F-003/F-004（owner 与期望见上），再按合同命令重录 EV；F-002 修复后必须重新跑 AC-18/OPS-M04-01 的 WARN 与回滚演练。gate 当前应失败，不应用 review 文本强行通过。
- Serena 说明：本轮验证以运行时/脚本/定向测试为主，未编辑生产符号；符号影响面用 CodeGraph 核对 `SchemaVersionChecker`，未引入 Serena Java LSP（避免与全量 Maven 测试并发触碰 `target/`）。
