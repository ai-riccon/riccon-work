---
review_id: REV-TASK-0100-001
module_id: M04
task_id: TASK-0100
reviewer:
  agent: riccon/verifier
  session: ses_e062e7326ffedxtuNvV01FYB6A
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [f52fefe, c2c3f86, 3351c41, 54be8d6, 89830b4]
head_commit: 617fa4b07542d515f7615b3219129c4bf898ab31
evidence: [EV-0531, EV-0532, EV-0537]
reviewed_evidence: [EV-0531, EV-0532, EV-0537]
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead → M04-B10 / V2"
    due: "M04 gate 前（OPS-M04-01 / AC-18）"
    title: "V11MigrationTest 未覆盖 ADR-0021 §10.1 的失败注入/repair 重跑与旧应用读取历史调用"
    summary: >-
      ADR-0021 §10.1 明确要求 V11MigrationTest 覆盖“失败注入（唯一键提前）”和“旧应用在 V11 库读取历史调用”。
      当前 16 个用例覆盖 V10→V11 成功、fresh V1→V11、结构/权限/配额/回滚，但没有 V11 的失败注入或
      flyway repair 重跑用例（V10MigrationTest 有 failureInjection_recoversWithRepairAndRerun，V11 无对应）；
      V11 的 DDL 不是全量幂等的，ADR §10.4 依赖手动清理 + repair。B1a 的 6 条验收不含这两项，
      由 B10/OPS-M04-01 与 V2 独立重放承接，但 M04 gate 前必须补齐或书面接受。
    evidence: "docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:760、:827-845；apps/api/src/test/java/com/riccon/api/migration/V11MigrationTest.java:878-951、:942；apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:685"
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/lead"
    due: "TASK-0100 closeout / M04 gate 前"
    title: "gate 把 HEAD 617fa4b 的 B1b 加密文件归到 TASK-0100 越界；任务基线/证据提交需要按任务边界重绑"
    summary: >-
      5 个被评审提交（f52fefe/c2c3f86/3351c41/54be8d6/89830b4）的 stat 都在 TASK-0100 write_scope 内，
      未改 contracts/openapi；但 `.riccon/state.json` 00:42:41 的 gate 仍把 CryptoProperties、
      CryptoConfig、CryptoStartupValidationTest 列为 TASK-0100 越界。原因是任务仍为 review，
      scope 检查按 baseline..HEAD（scope.mjs:75-97），HEAD 617fa4b 含 TASK-0101 的 CryptoConfig 移动；
      工作区还把已提交 baseline 0f6cfbf 改成 c35f1f0，使 f52fefe/3351c41 等任务主提交不再进入
      scope 检查，失去本任务写范围证明。closeout 应使用 5 个任务提交 + end_commit 或模块级证据重绑，
      不要把 baseline 后移当作规避。
    evidence: ".riccon/state.json:10-12；git diff 89830b4..617fa4b --name-only；~/.config/opencode/riccon/scripts/lib/scope.mjs:75-97；.riccon/tasks/TASK-0100.json（工作区 baseline c35f1f0 vs HEAD 0f6cfbf）"
  - id: F-003
    severity: P3
    status: accepted
    owner: "M04-B6 / M04-B2"
    due: "B6 写审计前"
    title: "quota.limit.changed 的 limit 没有 nullable 白名单，B6 不能用 null 表达“明确不限”"
    summary: >-
      ADR-0022 允许 QuotaUpdateRequest 的 daily/monthly 为 null（明确不限），但 AuditDetailsPolicy
      只允许 previousLimit 为 null（ADR 字面要求），limit 仍必须是数字；B6 若把 null 直接写审计会被
      fail-closed。建议 B6 用 0 表达 explicit unlimited，或在 B6/ADR 中补充 limit 的 nullable 语义；
      这是 B6 集成约定，不是 B1a 当前 6 条验收的失败。
    evidence: "apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:167-169、:180-181；docs/adr/ADR-0022-m04-quota-and-cost.md:152-153、:275-276"
created_at: "2026-11-21T00:54:36+08:00"
---

# REV-TASK-0100-001：TASK-0100 / M04-B1a 独立评审

本评审是任务级独立技术验收，不是 Product 预验收或用户产品验收，也不替代 M04 模块评审。

## 1. 结论

**pass（任务级）**。6 条验收均有实现与可核对的测试/配置证据；未发现 P0/P1。开放 **F-001（P2，B10/V2 承接）** 与 **F-002（P2，Lead closeout 重绑）**，另有 **F-003（P3，B6 集成约定）**；P2 不阻塞本任务 pass，但 F-001 未承接前 M04 模块 gate 不得通过。

本结论不等于 M04 模块 gate pass：`.riccon/state.json` 00:42:41 的 gate 仍为 `fail`（TASK-0100/0101/0102 均为 review、TASK-0103..0116 planned、无 M04 review，以及 F-002 的 scope 归因）。Lead 不能在 TASK-0100 closeout 时把本评审当作模块验收或用户验收。

## 2. 评审范围与独立复验

评审起点：`git rev-parse HEAD` = `617fa4b07542d515f7615b3219129c4bf898ab31`；工作区只有 `.riccon/` 下的状态/证据/评审变更，生产源码与迁移无未提交改动。被评审的 5 个 TASK-0100 提交：

| 提交      | 内容                                                                        | 范围核对                                                            |
| --------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `f52fefe` | V11、权限/审计词汇、错误码、RoleDefinitions、配置键、schema 测试            | 18 个文件均在 TASK-0100 `write_scope` 内                            |
| `c2c3f86` | ApiApplicationTests 同步 schema 11（11 migrations / 46 tables）             | 在 `apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java` |
| `3351c41` | V11 补 `platform.model.read` 幂等种子、V10 版本钉住、fresh 权限全集反射测试 | 在 migration 测试 glob 与 V11 文件内                                |
| `54be8d6` | 报告重试用例按内层 `max-attempts=1` 修正                                    | 在 `ReportArtifactIntegrationTest.java`                             |
| `89830b4` | Worker 审计策略 M04 分支覆盖                                                | 在 `AuditDetailsPolicyTest.java`                                    |

**独立执行**：在 HEAD 运行 `./mvnw -q -T1C -pl apps/api -am test -Dtest='V11MigrationTest,V11VocabularyTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`，exit 0；`V11MigrationTest` 16/16、`V11VocabularyTest` 7/7，0 fail / 0 skip。没有独立重跑全量 `verify`；全量采用 Lead 的 EV-0537（任务说明要求不重复全量）。

**证据审计**：

| EV      | 命令                                                                                                 | commit    | dirty | exit | 结果/用途                                                                                                                                                                                 |
| ------- | ---------------------------------------------------------------------------------------------------- | --------- | ----- | ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-0531 | `./mvnw -q -T1C -pl apps/api -am test -Dtest='V11MigrationTest,V11VocabularyTest' ...`               | `f52fefe` | false | 0    | 早期定向 V11 迁移/词汇；日志 55.7s；已被后续 3351c41 扩展，最终以 EV-0537 + 独立定向为准                                                                                                  |
| EV-0532 | `./mvnw -q -T1C -pl apps/worker -am test -Dtest='WorkerApplicationTests,AuditDetailsPolicyTest' ...` | `f52fefe` | false | 0    | Worker test profile/DEMO/主密钥可启动；schema=11；最终以 EV-0537 为准                                                                                                                     |
| EV-0535 | `./mvnw -q -T1C verify`                                                                              | `8b8585b` | false | 1    | 失败 L2：worker jacoco `check-coverage` 未达标（EV-0535 log:4402）；未作为通过证据                                                                                                        |
| EV-0536 | `./mvnw -q -T1C verify`                                                                              | `3df8dc0` | false | 1    | 失败 L2：ArchUnit `configurationClassesLiveInConfigPackage` 命中 `CryptoConfig`（EV-0536 log:4403-4416）；未作为通过证据                                                                  |
| EV-0537 | `./mvnw -q -T1C verify`                                                                              | `617fa4b` | false | 0    | 最终 L2 全量：V11Migration 16、V11Vocabulary 7、Worker AuditDetailsPolicy 11、WorkerApplication 2、ApiApplication 9、V10Migration 11，均 0 fail；jacoco:check 通过；log sha256 与 EV 一致 |

- EV-0531/0532 输出 sha256 分别与记录一致（`39f37d...`、`d8f040...`）；EV-0537 输出 sha256 `0ab91d...` 与记录一致。
- 证据文件：[EV-0531](../../evidence/TASK-0100/EV-0531.json)、[EV-0532](../../evidence/TASK-0100/EV-0532.json)、[EV-0537](../../evidence/TASK-0100/EV-0537.json)。
- EV-0537 的 Maven `-q` 日志里 `riccon_failure` 的 V10 失败注入 ERROR 是 V10MigrationTest 的预期负向用例；不是全量失败。
- 5 个提交未改 `contracts/openapi.yaml`、`contracts/README.md`、`docs/ARCHITECTURE.md`、`docs/PRODUCT.md`、pom 或 `.opencode/**`；`git diff f52fefe^..89830b4 --name-only | grep` 无 contracts/构建配置命中。
- 仓库/测试 profile 中的密钥均为已知测试占位（例如 `cmljY29u...` 测试主密钥、`riccon_test_only`），未发现真实密钥、`sk-...`、`AKIA...`、私钥块或生产地址；EV-0537 日志也没有输出 key 值。

## 3. 逐条验收（6 条）

| #   | 验收原文                                                                                                                                       | 结论 | 依据                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | V10 种子（1 ACTIVE + 1 RETIRED 跨 provider + 1 万历史调用）后 V11 通过                                                                         | pass | `V11MigrationTest.prepareSchemas` 先 `migrate(..., "10")`，`seedV10Data` 造 PROVIDER_A ACTIVE + PROVIDER_B RETIRED（不同 provider），`seedHistoricalInvocations` 用 4 位数字笛卡尔积造 10,000 行，再 `migrate(..., "11")`；`migration_appliesOnV10SeedData` 断言 history `version=11` 且 `model_invocation` 仍为 10,000 行（V11MigrationTest:167-195、200-208、954-975、1079-1109）。EV-0537 与独立定向均通过。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 2   | V11 后 helper 造 2 ACTIVE + 1 INVALID 并完成多 key 回滚（每组 1 行；有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数）                  | pass | `seedMultiKeyCredentials` 给 PROVIDER_C 造 C1/C2 ACTIVE + C3 INVALID，并给 PROVIDER_B 补 B_INVALID（V11MigrationTest:978-995）；`rollbackV11Objects` 按 `(tenant_id, provider_id, credential_type)` 用 `ROW_NUMBER` 收敛（ACTIVE > DISABLED > INVALID，再 created_at/id），先归档语义不强制、直接删除多余行，再按 ADR 顺序 DROP 新 CHECK、非 ACTIVE 改 RETIRED、恢复旧唯一键/旧 CHECK、删新索引/新列/新表（V11MigrationTest:998-1075）。`rollback_converges...` 断言分组 `COUNT(*) <> 1` 为 0（不写死总行数），A/C1 仍 ACTIVE、B 为 RETIRED（V11MigrationTest:877-950），并用 group count 隐含 C2/C3/B_INVALID 被收敛。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| 3   | `information_schema` 无 `fk_model_invocation_provider`、`model_invocation` 无新增 CHECK、加列 INSTANT/索引 INPLACE                             | pass | `migration_keepsInvocationWithoutProviderForeignKeyOrNewChecks` 断言无 `fk_model_invocation_provider`，check 名称恰好为 V5 既有 `chk_model_invocation_tenant/purpose/status/tokens`（V11MigrationTest:413-427）；`migration_ddlUsesInstantAndInplaceAlgorithms` 去注释后断言 provider_credential 步骤顺序、`model_invocation` 加列块含 `ALGORITHM = INSTANT` 且无 `CONSTRAINT/CHECK`、4 条索引各自 `ALGORITHM = INPLACE, LOCK = NONE`（V11MigrationTest:719-772）。V11 文件对应行：model_invocation 加列 147-158、索引 162-177、无 FK 179-181、无 CHECK 146/183-185。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 4   | 权限回填 + M04 权限码/审计动作/目标类型/details 数组+nullable 白名单预置                                                                       | pass | V11 插入 6 个权限码（含幂等补 `platform.model.read`）并回填 TENANT_ADMIN 的 `quota.read`/`cost.read`、PLATFORM_ADMIN 的 `platform.model.manage`/`platform.quota.manage`/`platform.cost.read`（V11:280-358）；`migration_backfillsM04Permissions` 断言权限行与角色映射（V11MigrationTest:775-808）；`freshDatabase_permissionDictionaryCoversAllPermissionCodes` 反射读取 `PermissionCodes` 全部 **30** 个常量并与 fresh 字典一一对应（V11MigrationTest:849-873）。`V11VocabularyTest` 覆盖 M04 权限码/RoleDefinitions、11 个错误码、12 个审计动作、3 个目标类型、`model.routing.updated.modelIds` 最多 20 个 UUID 数组、`quota.limit.changed.previousLimit` nullable、敏感子串与未登记键 fail-closed（V11VocabularyTest:31-293）。API 策略含 M04 全部动作与数组/nullable 白名单（AuditDetailsPolicy:138-172、180-192、293-312）；Worker 策略含自身写入的 failover/invalidated/emitted 动作（worker AuditDetailsPolicy:22-43），对 API-only 路由/配额动作保持 fail-closed（`AuditDetailsPolicyTest.validate_apiOnlyM04Actions_failClosedOnWorker`）。                                                                                                                                                                             |
| 5   | 两应用 M04 配置键占位（crypto/endpoint/management/routing/currency + 默认配额 4 键）+ Worker 集成测试上下文 DEMO+端点+主密钥可启动 + schema=11 | pass | API `application.yml` 有 `riccon.crypto.*`、`riccon.model.deployment-mode/endpoints/gateway.*`、`riccon.billing.currency`、`riccon.quota.defaults.{tenant-daily,tenant-monthly,user-daily,user-monthly}`、`riccon.ops.allowed-cidrs`、`management.info.env.enabled`、`info.riccon.*`（35-133）；Worker 有同一组模型/币种/配额/ops 键与 `riccon.worker.crypto.canary/fingerprint-pepper`（43-85、126-133）；`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION = 11`（SchemaVersionChecker:41）；API/Worker test profile 均有 DEMO 端点、`allow-http`、测试主密钥/pepper 占位（`apps/api/src/test/resources/application-test.yml`、`apps/worker/src/test/resources/application-test.yml`）；`WorkerIntegrationTestBase` `@ActiveProfiles("test")`、DEMO endpoints、`riccon.model.endpoints`、`riccon.worker.crypto.fingerprint-pepper` 注入（WorkerIntegrationTestBase:53、65、69、235、242）；`ApiApplicationTests` 断言 11 migrations / 46 tables（ApiApplicationTests:42-44）。EV-0532（WorkerApplicationTests 2/2）与 EV-0537 的 API/Worker 上下文均通过。                                                                                                                                                                         |
| 6   | quota/cost/attempt 表结构+FK/CHECK+空表 INSERT/ROLLBACK+PK 顺序/默认值断言（按 B6 upsert 形状）                                                | pass | V11 建 `model_invocation_attempt`（PK `(tenant_id, invocation_id, attempt_no)`、FK invocation/model/provider、status/attempt_no CHECK，V11:193-216）、`quota_limit`（PK `(tenant_id, scope_type, scope_id, window_kind)`、tenant/user FK、scope/window/scope_user/count CHECK，V11:225-248）、`quota_usage`（PK `(tenant_id, scope_type, scope_id, window_kind, window_start)`、tenant FK、scope/window/count CHECK、`used_count DEFAULT 0`，V11:253-270）；cost 快照列为 `model_invocation` 的 `input_price_per_1m/output_price_per_1m/price_currency/pricing_version/cost_amount/cost_status/cost_day/attempt_count`（V11:147-158）。`migration_createsQuotaTablesForAtomicAdmission` 断言列顺序、PK 顺序、FK 列、CHECK、`used_count` 默认 0，并实际执行 B6 形状的 `INSERT ... ON DUPLICATE KEY UPDATE used_count=used_count` 两次 + `UPDATE ... WHERE used_count < 1`，断言首次 affectedRows=1、第二次 0、used_count=1（V11MigrationTest:497-615）；`migration_emptyTablesAcceptInsertAndRollback` 在同一连接事务写入 quota/attempt/cost 更新后回滚，断言无残留（V11MigrationTest:676-716）；`migration_createsInvocationAttemptTable` 事务插入/回滚并断言非法 status/attempt_no/NULL FK 被拒绝（V11MigrationTest:430-494）。 |

## 4. 重点审查结论

### 4.1 V11 向前兼容与幂等/repair

- V11 只新增列/表、替换 `provider_credential` 唯一键并把 `RETIRED→DISABLED`；不删除既有业务列/表，不提供 down migration；旧的 V1–V10 文件未修改。
- 数据/种子部分可重跑：权限与角色映射用 `NOT EXISTS`（V11:280-358），路由链/条目用 `NOT EXISTS`（V11:364-385），开关用 `ON DUPLICATE KEY UPDATE switch_key = switch_key`（V11:389-393）；`RETIRED→DISABLED` 与 fingerprint 回填只命中待处理行（V11:117-123）。
- DDL 部分不是全量幂等的：重建表/加列/加索引/删旧唯一键重复执行会失败；ADR-0021 §10.4 明确依赖“残留清单 + 手动补齐 + flyway repair”。当前**没有** V11 的失败注入/repair 重跑测试，也没有旧应用在 V11 库读取历史调用的测试（F-001）；这是本次唯一实质测试缺口，B1a 的 6 条验收不含它，不能据此把任务本身判 fail，但 M04 gate 前必须由 B10/OPS-M04-01 与 V2 承接。
- V11 的 rollback SQL 在测试中用独立 schema 执行，未触碰生产；`rollbackV11Objects` 先按分组收敛多余行，再 DROP 新索引/新 CHECK、状态归一、恢复旧唯一键/旧 CHECK、删新列/新表，最后测试删除 flyway V11 行并验证 current=10、pending=11（V11MigrationTest:877-951）。这满足“副本上 forward + rollback”的 T4 口径，但没有把“单 key 仅一组”的独立场景另起 schema；当前 seed 的 A/B 两组本身就是单 key，C/B_INVALID 是多 key，覆盖了两种形状。

### 4.2 多 key 与 `provider_credential`

- V10 阶段只造 1 ACTIVE + 1 RETIRED 且必须跨 provider（旧唯一键禁止同 provider 多行）；V11 后才由 helper 给同 provider 造 2 ACTIVE + 1 INVALID，符合 ADR-0021 §3.2 的两阶段测试要求。
- 新唯一键 `(tenant_id, provider_id, credential_type, fingerprint)`、池索引 `(tenant_id, provider_id, credential_type, status, priority, created_at)`、状态/priority/failures CHECK 均在 `information_schema` 断言（V11MigrationTest:250-298）；旧 `uk_provider_credential` 已删除，fingerprint 回填为 64 位 hex（V11MigrationTest:260-283）。
- 多 key 回滚断言按分组而非总行数；A、C1 保留 ACTIVE，B 组无 ACTIVE 保留 RETIRED；C2/C3/B_INVALID 由 group count 证明被收敛。该断言形状与 ADR-0021 §10.3/§10.3 的回滚判据一致。

### 4.3 `model_invocation` 与成本快照

- 新增 10 列全部可空或有常量默认；历史 10,000 行在 V11 后 `cost_status=USAGE_UNKNOWN`、`attempt_count=1`、`cost_day/cost_amount/provider_id` 全 NULL（V11MigrationTest:325-400）；V11 不回溯 `cost_day` 或 `cost_status`，符合 ADR-0021 §3.3/ADR-0022 §5。
- `model_invocation` 无 `fk_model_invocation_provider`、无新增 CHECK；4 条索引逐条 `INPLACE/LOCK=NONE`，加列块 `INSTANT`；测试既检查 `information_schema` 也检查迁移文本顺序（V11MigrationTest:413-427、719-772）。
- `model_invocation_attempt` 的 `tenant_id` 取请求租户、`credential_id` 逻辑引用不建 FK；provider/model FK 与 CHECK 存在；attempt 主键顺序 `(tenant_id, invocation_id, attempt_no)` 与 ADR 一致。

### 4.4 配额/成本/尝试表与 B6 upsert

- `quota_usage` 主键顺序正好匹配 B6 的“先 `INSERT ... ON DUPLICATE KEY UPDATE used_count=used_count` 取行锁，再条件 `UPDATE ... WHERE used_count < :limit`”形状；真实 MySQL 8.4.13 上首次 affectedRows=1、第二次 0、`used_count=1`（V11MigrationTest:566-610）。
- `quota_limit`/`quota_usage` 的 tenant FK、user 复合 FK、scope/window/count CHECK、`used_count DEFAULT 0` 均有断言；错误维度、负数、跨租户 user、缺失 tenant 全部被拒绝（V11MigrationTest:618-673）。
- 空表事务 INSERT/ROLLBACK 后 quota/attempt 无残留，历史 invocation 仍为 `USAGE_UNKNOWN`，证明测试不污染共享 schema（V11MigrationTest:676-716）。

### 4.5 权限、审计与错误码

- `PermissionCodes` 当前 30 个常量；V11 fresh 字典反射对账 exactly 30、无重复；V11 还预算 `platform.model.read` 的幂等补齐，避免 fresh/local 恢复差异（V11:280-282；V11MigrationTest:849-873）。
- V11 回填既有租户/平台角色；`RoleDefinitions` 覆盖新租户开通（TENANT_ADMIN 得 quota/cost，PLATFORM_ADMIN 得三项平台权限，MEMBER 不得）；V11VocabularyTest:31-56 与 V11MigrationTest:775-846 双向断言。
- 审计动作/目标类型完整：12 个动作、MODEL_ROUTING/MODEL_CREDENTIAL/QUOTA 三个目标类型；API 策略含全部 M04 动作和 details，Worker 策略含自身写入的 `model.failover.triggered`/`model.credential.invalidated`/`emitted`。数组只允许 `model.routing.updated.modelIds`（≤20 UUID、总长 ≤2048）；nullable 只允许 `quota.limit.changed.previousLimit`；敏感子串和未登记键 fail-closed；API 白名单优先避免 `promptTokens/completionTokens` 被 `token` 子串误杀（API AuditDetailsPolicy:227-312；worker AuditDetailsPolicy:92-174；V11VocabularyTest:97-293；AuditDetailsPolicyTest:70-214）。
- **API/Worker 一致性**：共享动作和共享键一致；Worker 不登记数组/nullable 与 API-only 动作，这是刻意缩小 Worker 写面，`validate_apiOnlyM04Actions_failClosedOnWorker` 明确断言 Worker 不能写路由/配额事件。若 B2/B4 未来让 Worker 写新动作，必须同步扩白名单并补测试。
- 错误码 11 个与 ADR-0021 §7.3/ADR-0022 §3.3 对齐，V11VocabularyTest:59-73 逐项断言；Worker 内部 401/403/429 仍在 `ModelException`，不直接暴露员工。

### 4.6 配置键与 test profile

- API/Worker 两侧 `riccon.model.*`、`riccon.billing.currency`、`riccon.quota.defaults.*`、`riccon.ops.allowed-cidrs`、`management.info.env.enabled`、`info.riccon.*` 键名一致；四个 `RICCON_QUOTA_DEFAULT_*` 都是 env 占位且默认 10000/200000/1000/20000（API 127-130；Worker 79-82）。
- API `riccon.crypto` 与 Worker `riccon.worker.crypto` 的主密钥/previous/master-key-id/canary/fingerprint-pepper 均为空默认或测试占位；`info.riccon.masterKeyId` 与 `billing.currency` 为同一 env 源；不暴露 endpoint URL、key、密文（API 73-106；Worker 43-82、126-133）。
- WorkerIntegrationTestBase 通过 `@ActiveProfiles("test")` + DEMO endpoints + 主密钥/pepper 动态属性启动；EV-0532 的 WorkerApplicationTests 2/2 通过，EV-0537 的 Worker 上下文/集成测试通过；schema 版本 11 由 `SchemaVersionChecker` 与 `WorkerIntegrationTestBase` 共同核对。
- 未发现真实密钥写入仓库；测试 profile 的 master-key/pepper 是已知测试值，测试容器密码 `riccon_test_only` 仅容器内使用。`application-test.yml` 和 `WorkerIntegrationTestBase` 的测试端点 `http://127.0.0.1:19090/v1` 是 DEMO 占位，不是生产地址。

### 4.7 L2 失败根因与“是否掩盖”

| 失败                         | 根因                                                                                                                                                                     | 修复                                                                                                                                                    | 是否掩盖                                                                        |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| 权限种子失败                 | V11 迁移的权限字典/角色回填需要覆盖 fresh 与既有库；builder 选择在 V11 幂等补齐 `platform.model.read`，并新增 30 个 `PermissionCodes` 常量的反射对账，避免只依赖 V2 单点 | `3351c41` 在 V11 增加 `platform.model.read` 的 `NOT EXISTS` 幂等补齐，并在 V11MigrationTest 增加反射读取全部 30 个常量的 fresh 对账；未删除任何权限断言 | 否；新增幂等种子 + 反射测试，fresh 字典 exactly 30                              |
| worker 覆盖率失败（EV-0535） | 新增 M04 代码使 worker branch/line 低于门槛                                                                                                                              | `c35f1f0` 补 worker 解密/密钥环测试，`89830b4` 补审计策略 M04 分支测试；root pom 门槛仍为 line 0.80 / branch 0.70，无降门槛                             | 否；测试真实执行，EV-0537 jacoco:check 通过（worker line 87.4%、branch 70.85%） |
| ArchUnit 失败（EV-0536）     | `CryptoConfig` 在 `crypto` 包，违反 `@Configuration` 必须在 `..config..` 的架构规则                                                                                      | `617fa4b` 将 `CryptoConfig` 移到 `crypto/config` 并补 import；未加 ArchUnit 豁免                                                                        | 否；规则仍生效，EV-0537 架构测试通过                                            |
| V10 迁移测试版本漂移         | V10 测试的 `info().current()` / fresh 路径未钉 target，被 V11 影响                                                                                                       | `3351c41` 对 V10 forward/rollback/failure 用例显式 target 10，fresh 路径改用 latest 并断言 11                                                           | 否；V10 仍验证自己的 V9→V10，fresh 专职验证最新 schema                          |
| 报告重试语义错误             | 内层 OpenAI 客户端改为 `max-attempts=1` 后，旧测试注入 3 次 429 期待同调用重试 3 次                                                                                      | `54be8d6` 改为 `rateLimitNext(1)`，注释说明重试由 outbox 租约释放承担；测试仍断言 outbox PENDING→再次 poll→WAITING_APPROVAL→成功，不删除重试断言        | 否；测试仍验证重试链路，只是把重试层从内层 HTTP 移到 outbox                     |

- 未发现 `-DskipTests`、空测试命令、`@Disabled`、`assumeTrue` 或 retry 掩盖；V11/Vocabulary/Worker 审计套件均 0 skip。EV-0535/0536 的失败日志保留，没有被删除或改写为通过证据。
- 仍然存在的类似隐患：`WorkerIntegrationTestBase` 对 `riccon.ops.allowed-cidrs=""` 的 `@DynamicPropertySource` 注册会覆盖子类 `@TestPropertySource`（TASK-0102 评审 F-001 已记录，属 B2/TASK-0103 范围）；B2 落地 CIDR 过滤前必须修复或复核，本任务不重复计为 TASK-0100 finding。

### 4.8 覆盖率

- EV-0537 的 `verify` 执行了 `jacoco:check`，exit 0。HEAD 的 `apps/worker/target/site/jacoco/jacoco.xml`：LINE covered 4263 / missed 615 = **87.39%**；BRANCH covered 1327 / missed 546 = **70.85%**，均高于合同 80%/70%。
- API：LINE 6894 / 637 = 91.54%；BRANCH 1563 / 553 = 73.85%；platform-common：LINE 362/383 = 94.52%、BRANCH 127/151 = 84.11%。这些是 EV-0537 生成结果，非本评审重跑全量。

## 5. Findings

### F-001（P2，accepted，owner: Lead → B10/V2）

`V11MigrationTest` 有 16 个用例，但 ADR-0021 §10.1 要求的“失败注入（唯一键提前）”和“旧应用在 V11 库读取历史调用”没有对应用例；V11 的 DDL 不是全量幂等的，ADR §10.4 依赖手动残留清理 + `flyway repair`。B1a 的 6 条验收不含此项，因此任务级 pass；但这不是“已验证 repair/幂等”。M04 gate 前必须由 B10/OPS-M04-01 在一次性 MySQL 8.4.13 上补齐失败注入、repair/重跑与旧应用读取证据，或由 Lead/Architect 书面接受；V2 应独立重放。

### F-002（P2，open，owner: Lead）

5 个任务提交本身在写范围内，没有越界改 contracts/其他任务文件；`gate` 的 TASK-0100 越界原因是 HEAD 617fa4b 的 TASK-0101 CryptoConfig 移动被 `baseline..HEAD` 归到 TASK-0100，以及工作区把 baseline 从 `0f6cfbf` 后移到 `c35f1f0`，使任务主提交不再被 scope 检查。closeout 必须以 5 个 reviewed commits + `end_commit`（或模块级证据）重绑，不能以“改 baseline 让 gate 变绿”的方式处理。

### F-003（P3，accepted，owner: B6/B2）

`AuditDetailsPolicy` 只允许 `quota.limit.changed.previousLimit` 为 null；B6 的 `QuotaUpdateRequest` 允许 daily/monthly 为 null（明确不限）。B6 不能把 null 直接作为 `limit` 写审计（会被 fail-closed）；需用 0 表达 explicit unlimited，或由 Architect/B6 补充 `limit` nullable 语义并补测试。当前 B1a 实现与 ADR 字面一致，不阻塞本任务。

## 6. 未验证项与剩余风险

- **未跑模块 gate**：`.riccon/state.json` 00:42:41 已记录 `fail`（TASK-0100/0101/0102 review、TASK-0103..0116 planned、无 M04 review、F-002 scope），本评审没有重跑 gate；任务级 pass 不应被用作模块 gate 通过理由。
- **未独立重跑全量**：EV-0537 是 Lead 的 L2 全量；本评审只独立跑了一个定向命令（23 tests）。合同 `pnpm -w test -- --coverage`、E2E/a11y/perf/security/trivy 不在 B1a 范围，留给模块 gate/release。
- **未验证 V11 失败注入/repair/旧应用读取**：见 F-001。
- **未验证 B2+ 审计写入端**：API/Worker 策略的单元语义已验证，但实际的 `AuditService`/repository 写入、数据库 `audit_event.details` JSON 序列化和 M10 读取端要到 B2/B3/B4/M10 才闭环。
- **未验证 `/actuator/info` 有效值**：当前 `info.riccon.*` 的 deploymentMode/routingMode 等来自 env 占位，不是 DB 生效值；B10 preflight 需要 B2 暴露实现后再核对两侧指纹。
- **B6 明确不限审计**：见 F-003。
- **跨任务已知风险**：`WorkerIntegrationTestBase` 的 `allowed-cidrs` 动态属性覆盖问题已由 TASK-0102 评审 F-001 记录，归属 B2/TASK-0103；本任务不重复发现，但 M04 gate 前必须复核。
- 未做真实浏览器/E2E：本任务无 UI；E2E-M04-* 属 B11a/B11b/V1。
- 未在真实生产或生产副本执行 V11；迁移只在 Testcontainers 一次性 MySQL 8.4.13 上验证，生产执行仍需 B10 RUNBOOK、备份/恢复与用户 T4 批准。

## 7. 门禁与环境

- HEAD：`617fa4b07542d515f7615b3219129c4bf898ab31`。
- 工作区：只有 `.riccon/state.json`、`.riccon/tasks/TASK-0100/0101/0102.json`、`.riccon/evidence/TASK-0100/0101/0102/`、`.riccon/reviews/tasks/TASK-0100/0101/0102-review.md` 的未提交变更；生产源码/迁移无未提交改动。
- 本评审写于 `2026-11-21T00:54:36+08:00`（Asia/Shanghai）。
- **gate 应失败**：M04 还有 3 个 review 任务、14 个 planned 任务、无模块 review；F-002 的 scope 归因也需 Lead 在 closeout 修复。TASK-0100 任务级 pass 不等于 M04 模块 gate pass，更不等于用户验收。
