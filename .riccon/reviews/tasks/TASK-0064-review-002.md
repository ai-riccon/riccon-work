---
review_id: REV-TASK-0064-002
module_id: M10
task_id: TASK-0064
reviewer:
  agent: riccon/verifier
  session: ses_df2728ecbffemLxQuSwhpsQOMZ
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-M10-OPS-001
reviewed_commits: [e4dd9ad, 2edfa6c, e07f35b, 7f96a2e, 9a5651e]
head_commit: 9a5651efca5b9b13e06888cd31da11de0b445559
reviewed_evidence: [EV-0398, EV-0409, EV-1015]
evidence: [EV-1015]
findings:
  - id: OPS-001
    severity: P1
    status: fixed
    title: CI 迁移冒烟与 RUNBOOK 结构断言已兼容 V10 及后续迁移
    summary: "`.github/workflows/ci.yml:210-259` 不再写死表数：expected_tables 从 `V*.sql` 的 CREATE TABLE 数量推导，并显式断言 v10_table=1、两条 audit_event 索引、两条审批权限；`docs/RUNBOOK.md:268-358` 已改为 V1–V14/48（Flyway 口径 49）表结构断言，`docs/RUNBOOK.md:415-483` 有完整 V10 生产迁移段。当前代码无 V1–V7/36 表残留。"
    evidence: ".github/workflows/ci.yml:210-259；docs/RUNBOOK.md:255-358、415-483；提交 7f96a2e；EV-1015（grep expected_tables/v10_table/V10 生产迁移 均通过）"
  - id: OPS-002
    severity: P1
    status: fixed
    title: worker schema 期望版本已收敛为 14 并被测试锁定
    summary: "原 9→10 缺口已由 B3a（e07f35b）补齐；后续 M04/M05 迁移继续升到 11/13/14。当前 `SchemaVersionChecker.java:54-55` 为 `EXPECTED_SCHEMA_VERSION = 14`，`SchemaVersionCheckerTest.java:62-66` 有 `expectedSchemaVersion_isLockedAtV14` 锁定常量，9 个 schema 测试 0 skip。"
    evidence: "apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:54-55、130-134；apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java:62-66；提交 e07f35b、2b41cea；EV-0409、EV-1015"
  - id: OPS-003
    severity: P1
    status: fixed
    title: OIDC 配置以 String 承接并在应用内脱敏解析，真实启动失败不泄漏 secret
    summary: "`OidcProperties` 的 `idps` 仍是原始 String（`:9-11、74-75`），解析由 `OidcConfigurationParser` 完成；解析失败只报告字段路径/JSON 类型/行列，不保留原始 message/cause（`:64-69、283-295`）。`OidcStartupValidationTest` 用完整 SpringApplication 启动验证 malformed JSON 与缺 clientSecret 均失败且日志/异常链不含 secret（2/2）；`OidcConfigurationParserTest` 另有 malformed、缺 secret、toString 掩码断言（11/11）。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/config/OidcProperties.java:9-11、66-69、74-75；apps/api/src/main/java/com/riccon/api/oidc/domain/OidcConfigurationParser.java:64-69、283-295；apps/api/src/test/java/com/riccon/api/m10/oidc/OidcStartupValidationTest.java:33-67；apps/api/src/test/java/com/riccon/api/m10/oidc/OidcConfigurationParserTest.java:82-94、177-187；提交 2edfa6c；EV-1015"
  - id: OPS-004
    severity: P1
    status: fixed
    title: V10 协调切换顺序已写入 RUNBOOK/迁移/worker 注释并完成测试环境 schema 前置
    summary: "`docs/RUNBOOK.md:415-483` 明确“停 worker → 停 API 写 → 一次性迁移 Job → 起新 API → 起新 worker”的协调切换、维护窗口、结构校验、失败恢复和严格反向回滚，并标注尚未在生产执行；`SchemaVersionChecker.java:23-28` 与 V10 文件头同步说明不能纯滚动；`docs/M10-OPS-DRILL.md:23-33、74-75` 记录 schema 前置通过、完整编排未复制。P1 要求的协调切换方案已落地。"
    evidence: "docs/RUNBOOK.md:415-483；apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:23-28；docs/M10-OPS-DRILL.md:23-33、74-75；提交 7f96a2e；EV-1015"
  - id: OPS-005
    severity: P2
    status: fixed
    title: V10 索引 ALTER 已使用单语句 INPLACE/LOCK=NONE 并明确维护窗口
    summary: "V10:142-146 为单条 `ALTER TABLE audit_event`，同语句新增两条索引并带 `ALGORITHM = INPLACE, LOCK = NONE`；`V10MigrationTest.migration_indexDdlIsSingleInplaceStatement` 读取真实迁移文件断言该结构，生产窗口要求见 RUNBOOK:434。"
    evidence: "apps/api/src/main/resources/db/migration/V10__audit_query_export.sql:142-146；apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:474-493；docs/RUNBOOK.md:434；EV-1015"
  - id: OPS-006
    severity: P2
    status: fixed
    title: 迁移失败恢复矩阵已写入 V10/RUNBOOK 并有失败注入重跑测试
    summary: "V10 文件头按 CREATE TABLE/权限 INSERT/角色回填/索引 ALTER 四类失败列出残留与 repair 路径；RUNBOOK:452-459 给出同一矩阵；`V10MigrationTest.failureInjection_recoversWithRepairAndRerun` 用重名索引真实注入失败后 repair/重跑成功。"
    evidence: "apps/api/src/main/resources/db/migration/V10__audit_query_export.sql:24-31；apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:679-728；docs/RUNBOOK.md:452-459；docs/M10-OPS-DRILL.md:46-53；EV-1015"
  - id: OPS-007
    severity: P2
    status: fixed
    title: 回滚已包含 flyway_schema_history V10 行处理与 MAX(version)=9 校验
    summary: "V10:150-169 与 RUNBOOK:461-481 均包含结构回滚、维护账号删除 V10 history 行、校验 MAX(version)=9；`V10MigrationTest.rollback_returnsToV9AndKeepsDataReadable` 在一次性容器验证回滚后 V10 pending、M02/M03 数据与审计仍可读。"
    evidence: "apps/api/src/main/resources/db/migration/V10__audit_query_export.sql:150-169；apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:613-673；docs/RUNBOOK.md:461-481；docs/M10-OPS-DRILL.md:55-64；EV-1015"
  - id: OPS-008
    severity: P2
    status: accepted
    owner: riccon/architect + riccon/ops
    due: M09 gate 前
    accepted_reason: "V10 无 created_at 清理索引；audit_export_request 体量受审批/导出频率限制，M09 可按 (tenant_id,status,created_at) 逐租户清理或新增后续迁移，M10 不声称已支持跨租户清理。"
    title: audit_export_request 缺按 created_at 的跨租户清理索引
    summary: "当前 V10 只有 (tenant_id,status,created_at) 与 (tenant_id,status,expires_at)，跨租户清理需逐租户循环；已由 Product 验收材料记为 M09 承接。"
    evidence: "V10__audit_query_export.sql:55-57；docs/ACCEPTANCE.md:146；REV-M10-002 F-012"
  - id: OPS-009
    severity: P2
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "导出按 500 行分页查询，连接在批次间释放，不持有跨客户端下载的单一连接；rowLimit 10000 有界。生产连接池占用/下载时长监控与并发上限归 M09。"
    title: 流式导出连接占用已由 500 行分批缓解，生产监控待 M09
    summary: "AuditExportService 每页 500 行查询并逐批写出；未全量物化。"
    evidence: "apps/api/src/main/java/com/riccon/api/audit/service/AuditExportService.java:62、294-317；REV-TASK-0071-001 §8；docs/ACCEPTANCE.md:146"
  - id: OPS-010
    severity: P2
    status: accepted
    owner: riccon/ops + riccon/product
    due: M09 gate 前
    accepted_reason: "RUNBOOK V10 回滚第 2 步已要求先解绑/写回本地密码；完整 OIDC 回滚演练与每租户 OIDC TENANT_ADMIN 清单未执行，属生产发布前置，不在 M10 冒充已验证。"
    title: OIDC 代码回滚前的账号恢复流程
    summary: "docs/RUNBOOK.md:465 与 M10-OPS-DRILL:63-64 已记录恢复方向，但未做生产演练。"
    evidence: "docs/RUNBOOK.md:461-465；docs/M10-OPS-DRILL.md:63-64；docs/ACCEPTANCE.md:145"
  - id: OPS-011
    severity: P2
    status: fixed
    title: 测试 IdP 密钥运行时生成，未扩大 .trivyignore
    summary: "e2e/oidp/server.mjs 使用 generateKeyPairSync 在运行时生成 RSA 密钥，clientSecret 从 OIDP_REALMS 环境变量读取且不落盘；.trivyignore 仍只有两条 MCP CVE（2027-03-31 到期），security EV-0436 exit 0。"
    evidence: "e2e/oidp/server.mjs:11-16、27；e2e/oidp/README.md:17-21；.trivyignore；EV-0436"
  - id: OPS-012
    severity: P2
    status: accepted
    owner: riccon/architect + riccon/ops
    due: M09 gate 前
    accepted_reason: "M10 基线是 RICCON_OIDC_IDPS 环境变量单字符串；ADR-0019 F-012 已把 Helm Secret/挂载文件注入与 configtree 支持留给 M09。当前实现无需改代码即可支持 env 注入，文件注入若需要由 M09 决定。"
    title: Helm Secret/挂载 Secret 与单字符串属性的注入方式差异
    summary: "ADR-0019 §1/§6 的配置样例使用 env var，正文仍提“挂载 Secret”；M10 不实现 idps-file。"
    evidence: "docs/adr/ADR-0019-m10-oidc-sso.md §1、§6；apps/api/src/main/resources/application.yml OIDC 配置样例；REV-M10-OPS-001 OPS-012"
  - id: OPS-013
    severity: P2
    status: accepted
    owner: riccon/product + riccon/ops
    due: M09 gate 前
    accepted_reason: "平台租户自身导出在 M10 明确 403/deferred，避免自批；M09 需提供维护账号受控 SQL 导出 + 审批 + 留存兜底，验收材料保持 deferred，不声称已支持。"
    title: 平台租户导出 403 的合规取数缺口由 M09 兜底
    summary: "ADR-0020 §2/§10 与 OpenAPI deferred 已记录；M10 不提供平台侧自助导出。"
    evidence: "docs/adr/ADR-0020-m10-audit-query-export.md §2、§10；contracts/openapi.yaml x-riccon-deferred；docs/ACCEPTANCE.md:146"
  - id: OPS-014
    severity: P2
    status: accepted
    owner: riccon/ops + riccon/verifier
    due: M09 gate 前
    accepted_reason: "M10 写路径均由 AuditDetailsPolicy 白名单控制；历史/运维直写清点需在客户数据副本执行，RUNBOOK 已约束 migration.smoke 无 details。M09 上线前完成 action/details 清点并确认读取白名单覆盖。"
    title: 审计读取 fail-closed 对历史/运维直写事件的可用性风险
    summary: "正常数据由写侧策略保证；历史数据清点未在 M10 执行，不能声称已排除。"
    evidence: "docs/RUNBOOK.md:323-340；apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java；REV-TASK-0071-001 F-003；docs/ACCEPTANCE.md:146"
  - id: OPS-015
    severity: P3
    status: accepted
    owner: riccon/ops + riccon/builder
    due: M09 gate 前
    accepted_reason: "当前只校验 absolute http(s)、无 query/fragment、去尾斜杠，且允许生产 http；生产 HTTPS fail-fast、Secure Cookie 与反代访问日志脱敏属 M09 部署配置。"
    title: publicBaseUrl 缺生产 HTTPS fail-fast；回调 query string 日志需脱敏
    summary: "OidcIdpRegistry.normalizePublicBaseUrl 已做基本校验，未区分生产环境强制 https。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/service/OidcIdpRegistry.java:206-245；REV-TASK-0065-002 F-003；OPS-015"
  - id: OPS-016
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "ADR-0019 §1 已规定多实例必须使用同一份配置协调重启，M10 无热更新；M09 运行手册需把它固化为变更流程。"
    title: OIDC 配置变更需要全实例一致重启
    summary: "配置无热更新，混跑会造成同租户在新旧实例行为不一致。"
    evidence: "docs/adr/ADR-0019-m10-oidc-sso.md §1；REV-M10-OPS-001 OPS-016"
  - id: OPS-017
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "V6 分区只预建到 2027-12 并留 pmax；M09 需滚动新增月度分区并设置距最后分区 <60 天告警，M10 不改分区。"
    title: audit_event 分区预建到 2027-12，M09 需滚动维护
    summary: "M10 未变更分区策略。"
    evidence: "apps/api/src/main/resources/db/migration/V6__audit_event.sql；REV-M10-OPS-001 OPS-017"
  - id: OPS-018
    severity: P3
    status: accepted
    owner: riccon/ops + riccon/product
    due: M09 gate 前
    accepted_reason: "批准 24h/上限 7 天、过期 410、无重发接口已在 ADR/OpenAPI 定义；支持流程（重新申请 + 审批）与 export.ttl/retention-days 配置基线归 M09。"
    title: 导出 TTL/重下载语义与支持流程待 M09 固化
    summary: "M10 已实现时点语义，未建支持流程。"
    evidence: "docs/adr/ADR-0020-m10-audit-query-export.md §1、§2；contracts/openapi.yaml 审计导出；REV-M10-OPS-001 OPS-018"
created_at: "2026-11-24T21:08:00+08:00"
---

# REV-TASK-0064-002：TASK-0064 Ops 评审复审（OPS-001..004 P1 关闭）

## 1. 结论

**verdict: pass。** 在 HEAD `9a5651e` 上独立复核后，`REV-M10-OPS-001` 的 4 条 P1 均已由后续实现/文档修复：

- OPS-001：CI 与 RUNBOOK 不再写死 36 表/V1–V7，改为按迁移文件推导表数并断言 V10 结构；
- OPS-002：worker `EXPECTED_SCHEMA_VERSION` 已随迁移推进并当前锁定为 14，测试有常量锁定；
- OPS-003：OIDC 配置以 String 承接、应用内脱敏解析，真实启动失败不泄漏 secret；
- OPS-004：V10 协调切换顺序已写入 RUNBOOK/迁移/worker 注释，测试环境 schema 前置通过。

**边界：** OPS-004 的生产完整编排（K8s/Compose 停写、维护窗口、备份恢复、一次性迁移 Job）仍未执行，属 M09/release 前置；本复审只确认 P1 要求的协调切换方案与代码/测试前置已落地，不把未执行的生产动作写成已验证。

## 2. 验证证据

- **EV-1015**（TASK-0064，exit 0，`commit=9a5651e`，`dirty=false`，69.6s）：`node scripts/test-services.mjs reset --namespace m10-p1 && RICCON_TEST_SERVICES=external ... ./mvnw -q -T1C -pl apps/api,apps/worker -am -Dtest=OidcStartupValidationTest,OidcConfigurationParserTest,SchemaVersionCheckerTest,RoleDefinitionsTest,V10MigrationTest -Dsurefire.failIfNoSpecifiedTests=false test && grep ...`。
- Surefire 报告：`OidcStartupValidationTest` 2/2、`OidcConfigurationParserTest` 11/11、`SchemaVersionCheckerTest` 9/9、`RoleDefinitionsTest` 5/5、`V10MigrationTest` 11/11；合计 38 tests，0 failures/errors/skipped。
- `grep` 断言：`expected_tables`、`v10_table`、`V10 生产迁移`、`OPS-004 协调切换顺序`、`EXPECTED_SCHEMA_VERSION = 14` 全部命中。
- 说明：测试栈 reset 使用独立 namespace `m10-p1`；迁移/回滚/schema 测试仍各自使用一次性 Testcontainers 容器。工作树在取证前仅有内核同步造成的 `AGENTS.md` 未提交文档差异，取证期间临时还原、结束后恢复；`.riccon/` 不参与 dirty 判定，EV 的 `commit`/`dirty=false` 与 HEAD 一致。

## 3. OPS-001/002 复核

### OPS-001

- `ci.yml:210-259`：迁移按 `sort -V` 重放；`expected_tables` 由 `CREATE TABLE` 数量推导；显式断言 `audit_export_request`、两条 audit_event 索引、两条审批权限，以及 append-only 触发器。
- `RUNBOOK.md:268-358`：V1–V14 演练、48 张表（Flyway 口径 49）、V10/V12/V13/V14 对象断言；`RUNBOOK.md:415-483` 为 V10 生产迁移、结构校验、失败恢复与回滚。
- `grep` 未发现 `V1–V7`/`36 张表` 旧断言。

### OPS-002

- `SchemaVersionChecker.java:54-55`：`EXPECTED_SCHEMA_VERSION = 14`。
- `SchemaVersionCheckerTest.java:62-66`：`expectedSchemaVersion_isLockedAtV14` 直接断言 14，常量回退会被测试发现。
- 原 9→10 已由 `e07f35b` 完成；后续 V11/V13/V14 由 M04/M05 迁移任务继续升版，不是本 P1 的回归。

## 4. OPS-003 复核

- `OidcProperties` 只暴露原始 `String idps`，未把 `RICCON_OIDC_IDPS` 绑定成类型化对象；`toString()` 输出 `<redacted>`。
- `OidcConfigurationParser` 自行解析 JSON；异常只含配置项、字段路径、错误类型与行列，不保留原始 message/cause。
- `OidcStartupValidationTest` 真实启动两个失败用例，断言根因与 root logger 均不含 secret/原始片段；`OidcConfigurationParserTest` 另有 malformed、缺 secret、toString 掩码断言。
- EV-1015 中二者 2/2、11/11 通过。

## 5. OPS-004 复核

- `docs/RUNBOOK.md:415-483` 给出完整正向/反向切换顺序、维护窗口、结构校验、失败矩阵与回滚 SQL；明确“尚未执行”并要求 T4 批准。
- `SchemaVersionChecker.java:23-28` 与 V10 文件头说明 DB=10 后旧 worker 重启会 `RICCON-WORKER-001`，只能协调切换。
- `docs/M10-OPS-DRILL.md:23-33` 记录 schema 前置通过、完整停写/起停编排未在测试环境复制；`:74-75` 明确 OPS-004 完整编排仍是生产发布前置。

## 6. OPS-005..018 闭环

见 front matter：OPS-005/006/007/011 为 fixed；OPS-008/009/010/012/013/014/015/016/017/018 为 accepted，均写 owner/due/accepted_reason，由 M09 或生产发布承接。没有 open P2/P3。

## 7. 未验证与剩余风险

1. 未执行生产 V10 迁移、维护窗口、备份恢复、K8s Job 化迁移；本复审只确认方案与测试环境前置。
2. OPS-001 只验证 CI/RUNBOOK 断言结构与 V10MigrationTest；未在本地重放 `backend-smoke` 的完整 MySQL 手工迁移步骤（该 job 在 CI 上按同一迁移文件执行）。
3. OPS-010 的 OIDC 回滚账号恢复未演练；OPS-014 的历史 details 清点未在客户数据副本执行；OPS-008 的清理索引仍未添加。
4. 本次只跑定向测试，不重复全量 `verify`/E2E/a11y/security；模块 gate 的全量证据沿用 M10 模块评审的合同 EV。

## 8. 回执

- **OPS-001/002/003/004：fixed。** 旧评审 4 条 P1 全部关闭；OPS-004 的生产执行仍为 M09/release 前置。
- **OPS-005..018：全部 fixed 或 accepted，无 open。**
- **证据：** EV-1015（新）、EV-0398（原 Ops 评审）、EV-0409（B3a worker/V10）。
