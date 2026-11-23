---
review_id: REV-TASK-0203-001
module_id: M25
task_id: TASK-0203
reviewer:
  agent: riccon/verifier
  session: ses_df5c53b2fffeJxVlm80mJACB2G
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 3928aeefab2f95b9d0ecc7e18a57c715e37b525a
  - d605ed1a6641234393696f16c254e3965df6e5a6
  - 8d96d043352c24af1b7033d6210aaaeae30b721e
head_commit: 8d96d043352c24af1b7033d6210aaaeae30b721e
baseline_commit: c3c8d2c788383fbc7f51e283d1bc55a8b9e90a9d
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
  docs: true
  security: true
dod_not_applicable:
  - coverage
  - e2e
  - migration
  - perf
  - accessibility
dod_note: >-
  TASK-0203 是“契约 + 契约测试”任务，适用 acceptance/tests/review/evidence；build 由 Maven 契约测试编译与
  `spotless:check` 覆盖；docs 由 `contracts/README.md` 的 1.5.0-m05b 更新覆盖；security 仅指契约层的权限、
  CSRF、凭据只写不读、跨租户 404 语义，运行时代码级 SSRF/凭据加密/越权与本任务无关。coverage/e2e/migration/perf/a11y
  不适用：本任务没有生产代码、迁移、UI、E2E 或性能预算变更。acceptance 判 pass 但保留 F-001（P2）文档残余。
reviewed_evidence:
  - EV-0963
evidence:
  - EV-0963
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-kb-contract
    due: B6（TASK-0204）启动前
    accepted_reason: >-
      OpenAPI 与 ADR-0023 §6.2/§7.4 才是权威：`Connector.config.bucket` 是必填字段，`ConnectorConfig.bucket`
      的描述明确“响应原样返回，日志/审计只记截断摘要”，而连接器列表仅对 `knowledge.connector.read`
      （当前仅 TENANT_ADMIN）可见，不向员工或跨租户泄露。README 的两处“响应/日志/审计不返回 bucket 全名”
      是过度概括，属于文档与 schema 的表述冲突，不是当前契约字段错误；B6 按 OpenAPI 落地不会产生越权。
      风险是 B6 若按 README 字面省略 bucket，会破坏 `Connector` 必填 schema，故要求在 B6 启动前修正 README。
    title: "contracts/README 称响应不返回 bucket 全名，但 Connector.config.bucket 是必填且明确原样返回"
    summary: >-
      `contracts/README.md:117` 与 `:130` 写“响应/日志/审计不返回……bucket 全名或对象 key 全文”；但
      `contracts/openapi.yaml:5666-5674` 的 `ConnectorConfig` 把 `bucket` 列为 required，并在 `:5674`
      写明“响应原样返回，日志/审计只记截断摘要”；`:5700-5734` 的 `Connector` 又要求 `config` 必填。
      同一目录内两处安全边界表述相反，B6 可能据此省略或掩码 bucket，导致与 schema 不一致。期望：README 改为
      “响应仅向 `knowledge.connector.read` 管理员返回 bucket/prefix；日志/审计不记 bucket 全名”，或明确
      schema 若需隐藏 bucket 则先改 OpenAPI/ADR。按任务边界，Verifier 不自行修改契约。
    evidence: >-
      contracts/README.md:105、:112、:117、:130；contracts/openapi.yaml:5666-5674、:5700-5734；
      docs/adr/ADR-0023-m05-knowledge-depth.md:502（config 含 bucket/prefix）、:754-766（M05b 仅排除凭据）。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      EV-0963 命令真实执行且 exit 0，契约测试本身已由本次独立复验确认：`*Contract*Test` 实际运行 29 个用例
      （Connector 11 + Knowledge 10 + Model 108a/108b 各 1 + CostPage 3 + UserSummaryDepartmentContract 3），
      0 failures/0 errors/0 skipped。风险是证据日志因 `-q` 为空、note 少计 3 个用例，未来审计者无法仅凭 EV
      日志证明“不是零测试通过”；不影响本次任务结论。建议在 M25 gate 前补录一条非 quiet 输出或附带 surefire
      报告的 EV，或在模块 review 记录本次独立复验。
    title: "EV-0963 日志为空且 note 的 26 用例与实际 29 不一致，证据可审计性不足"
    summary: >-
      `EV-0963` 的命令带 `-q`，日志文件只有 326 字节的 header（无测试摘要）；同时
      `-Dsurefire.failIfNoSpecifiedTests=false` 在测试选择器未命中时也会 exit 0。EV JSON 的 note 写
      “26 用例”，本次独立复验的 surefire 报告为 29 个用例：`ConnectorContractM05bTest` 11、
      `KnowledgeContractM05Test` 10、`ModelContract108aTest` 1、`ModelContract108bTest` 1、
      `CostPageContractTest` 3、`UserSummaryDepartmentContractTest` 3，全部 0/0/0。期望：补录非 quiet
      证据或保存 surefire 报告，并修正 note。
    evidence: >-
      .riccon/evidence/TASK-0203/EV-0963.json:5-8、:18；
      .riccon/evidence/TASK-0203/EV-0963-2026-11-24T05-05-19-256+08-00-...log（326 字节，仅 header）；
      apps/api/target/surefire-reports/*Contract*.txt（独立复验 29 用例，0/0/0）；
      复现命令：`./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-kb-contract
    due: B6（TASK-0204）启动前或 M25 gate 前
    accepted_reason: >-
      当前 OpenAPI 的 8 个 operationId 与 Connector 禁止字段均正确，本次已独立核对，契约交付没有错误；
      缺口仅在回归保护强度：重命名 operationId 或向 Connector 加入 credentialKeyId/credentialCiphertext
      等字段时，现有断言不会变红。B6 仍可直接按现有 OpenAPI 实施；建议 B6 启动前补两条低成本断言。
    title: "Connector 契约测试未断言 operationId，且未覆盖 credentialKeyId/credentialCiphertext 的禁止"
    summary: >-
      `ConnectorContractM05bTest.java:30-46` 只按 path/method 断言操作存在，没有读取 `operationId` 字段；
      如果契约把 `listConnectors` 改名为别的值，测试仍会通过（当前 YAML 的 8 个 operationId 经独立 grep
      核对正确）。同文件 `:375-385` 的 `Connector` propertyNames 只排除 `credential/accessKeyId/
      secretAccessKey/cron`，未排除 ADR 明确不应出现在响应中的 `credentialKeyId`/`credentialCiphertext`/
      DEK 等字段；当前 schema 确实没有这些字段，但未来回归不会被测试捕获。期望：补 operationId 断言与
      禁用字段断言。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest.java:30-46、:375-385；
      contracts/openapi.yaml:1240、:1274、:1309、:1344、:1376、:1410、:1440、:1473（8 个 operationId）；
      独立核对：`grep -n "operationId: " contracts/openapi.yaml` 无重复连接器 operationId。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-kb-contract
    due: B6（TASK-0204）启动前
    accepted_reason: >-
      ADR-0023 §6.3 要求连接测试失败时返回 AUTH/NETWORK/ENDPOINT/CONFIG 分类中文原因；当前 schema 的
      description 已表达该意图，B6 可据描述实现。但 `category` 不在 required 中，生成客户端不会要求它，
      失败响应缺少 category 仍能通过 schema；这是契约表达强度问题，不是当前字段错误。建议在 B6 启动前
      把 category 设为可空必填（成功为 null）或在 description 明确“失败时必填”，并补契约断言。
    title: "ConnectorTestResult.category 未声明为失败必填，ADR §6.3 的失败分类无法由 schema 锁定"
    summary: >-
      `contracts/openapi.yaml:5881-5897` 的 `ConnectorTestResult.required` 仅为
      `[success, message, testedAt]`，`category` 是可选字段；`ConnectorContractM05bTest.java:267-278`
      也只断言 category 的 `$ref` 与 enum 值。ADR-0023 §6.3（:576）要求连接测试返回
      auth/network/endpoint/config 分类中文原因；若失败响应省略 category，客户端无法区分失败类型。
      期望：将 category 声明为必填可空（success 时 null），或补明确的“失败时必填”约束/测试。
    evidence: >-
      contracts/openapi.yaml:5881-5897；apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest.java:267-278；
      docs/adr/ADR-0023-m05-knowledge-depth.md:576。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      这是 gate 的范围计算口径问题，不是 TASK-0203 的越界：实际实现提交 `c3c8d2c..3928aee` 只有
      contracts/** 与 contract 测试 6 个文件；gate 使用 baseline..HEAD（8d96d04）把同级 O2a 的
      deploy/docs 改动算入 TASK-0203，和 TASK-0210 review F-003 是同一模式的对称表现。任务产物无需修改；
      Lead 在 M25 closeout 说明即可。
    title: "gate check 把同级 O2a 的 deploy/docs 改动计入 TASK-0203 越界"
    summary: >-
      `riccon gate check --module M25` 报 TASK-0203 changed files outside write_scope：
      `deploy/compose/docker-compose.test.yml`、`docs/ACCEPTANCE.md`、`docs/RUNBOOK.md`；但
      `git diff --name-status c3c8d2c..3928aee` 只有 `contracts/openapi.yaml`、`contracts/README.md`、
      `ConnectorContractM05bTest.java`、`KnowledgeContractM05Test.java`、ModelContract108a/b 六个文件，全部在
      write_scope 内。越界文件来自同级 `68e3f03`（O2a）与 `d9e2cb0`（M23/M24 记账），随 merge 进入
      baseline..HEAD 的伪差异。期望：gate/closeout 以任务 end_commit 或实际 diff 为准；M25 gate 本身仍因
      10 个任务未完成、无模块 review 而失败，属预期。
    evidence: >-
      `riccon gate check --module M25` 输出；`git diff --name-status c3c8d2c..3928aee`；
      `git diff --name-status c3c8d2c..8d96d04`；`git show --stat 68e3f03`、`d9e2cb0`；
      .riccon/reviews/tasks/TASK-0210-review.md F-003。
test_impact: >-
  独立运行任务指定命令
  `./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  → exit 0；surefire 报告 29 个用例，0 failures/0 errors/0 skipped（Connector 11、Knowledge 10、
  Model108a 1、Model108b 1、CostPage 3、UserSummaryDepartmentContract 3）。另运行 `./mvnw -q spotless:check`
  → exit 0；`ruby -e "require 'yaml'; YAML.load_file('contracts/openapi.yaml'); ..."` → yaml-ok。
  未运行模块全量 test/coverage/E2E/a11y/perf/migration；本任务无生产代码、迁移、UI 或部署改动。
notes: >-
  实际 head 为 8d96d04（仅 .riccon 证据/状态提交）；契约与测试内容以 3928aee 为准，d605ed1 的 contracts/
  与 contract 测试与 3928aee 完全相同。3928aee 的父为 c3c8d2c，diff 只有 6 个文件，无 Java 生产代码、
  迁移、前端、部署或 .riccon 生产改动。EV-0963 由 riccon/lead 录制（任务 owner 是 builder-kb-contract），
  这是流程事实，不是证据失效；其 commit/dirty=false/exit 0/日志哈希已核对。M25 gate 当前失败是预期：
  TASK-0203/0210 review、其余 9 个任务未完成、无模块 review；F-005 的 scope 伪差异不影响任务 done 判断。
  TASK-0210 的 F-001/F-002 是 O2a/B6 前置残余，不属于本任务，但 B6 启动前应一并处理。
created_at: "2026-11-24T05:16:03+08:00"
---

# REV-TASK-0203-001：TASK-0203（M05b-B2b 契约 1.5.0-m05b）独立评审

## 结论

**通过（pass，含 P2 residual）。** TASK-0203 在 write_scope 内交付了 OpenAPI `1.5.0-m05b` 的 8 个连接器
operationId、连接器/凭据/test/sync/items schema，以及 `ConnectorContractM05bTest` 的 11 个契约测试；M05
`1.4.0-m05` 端点与 schema 未删除、未改变既有 required/property，只做 `KnowledgeDocument.connectorId/externalId`
与 `sourceType=CONNECTOR` 的 additive 增量。M04/Knowledge/Cost/UserSummary 的既有契约测试仍通过。

本评审独立复跑了任务命令：`*Contract*Test` 实际 29 个用例，0 failures/0 errors/0 skipped；`spotless:check`
exit 0；YAML 解析通过。未发现 P0/P1。发现 1 个 P2（`contracts/README` 与 `Connector.config.bucket` 的
安全边界表述冲突）和 4 个 P3（EV-0963 可审计性、契约测试的 operationId/禁用字段断言、`ConnectorTestResult.category`
失败必填表达、gate scope 伪差异），均已 accepted 并写 owner/due/reason。

本 pass 只代表 TASK-0203 的契约与契约测试验收，**不代表** B6 的真实端点/同步/凭据实现、M25 模块 gate、
Product 预验收或用户验收通过。

## 验收对账

任务卡验收原文：

1. “OpenAPI 1.5.0-m05b：连接器 CRUD/credential/test/sync/items，M05 段不回归；每个 operationId 契约测试”
2. “端点真实实现由 B6 承接，不以 404/deferred 冒充”

| 验收子项                                                                                                                                                 | 结论                  | 证据/说明                                                                                                                                                                                                                                                                                                                                                                                           |
| -------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `info.version=1.5.0-m05b`，8 个连接器 operationId 齐全（list/create/update/delete/credential/test/sync/items）                                           | pass                  | `contracts/openapi.yaml:4`；operationId 在 `:1240`/`:1274`/`:1309`/`:1344`/`:1376`/`:1410`/`:1440`/`:1473`；独立 grep 无重复连接器 operationId；`ConnectorContractM05bTest.java:30-46` 逐 path/method 存在且非 deferred                                                                                                                                                                             |
| 权限：读 `knowledge.connector.read`，写 `knowledge.connector.manage`                                                                                     | pass                  | list/items 用 read（`:1245`/`:1478`），create/update/delete/credential/test/sync 用 manage（`:1281`/`:1315`/`:1350`/`:1382`/`:1416`/`:1446`）；`PermissionCodes.java:40-44` 与 `RoleDefinitions.java:66-68` 一致；测试 `listConnectors`/`listConnectorItems` 与其余操作逐一断言                                                                                                                     |
| CSRF 与 IdempotencyKey                                                                                                                                   | pass                  | 6 个写操作全部引用 `CsrfToken`；create 与 sync 引用 `IdempotencyKey`，该参数 `required: false`（`:3779-3787`），符合 README “可选幂等键”；测试断言引用存在                                                                                                                                                                                                                                          |
| 错误码 `RICCON-KB-400/404/409/503`、`RICCON-TENANT-404`、`RICCON-AUTH-403`                                                                               | pass                  | `KnowledgeBadRequest:4105-4110`（KB-400）、`KnowledgeForbidden:4111-4116`（AUTH-403）、`KnowledgeNotFound:4117-4122`（TENANT-404）、`KnowledgeConflict:4129-4134`（KB-409）、`KnowledgeIndexUnavailable:4135-4140`（KB-503）；连接器端点 404 按 §5.4 用 TENANT-404；`RICCON-KB-404` 保留在共享文档下载端点 `:4123-4128`，由 `KnowledgeContractM05Test.downloadKnowledgeDocument:306-343` 断言       |
| Schema：`Connector`、`ConnectorCreate/Update`（创建必填 `defaultVisibility`，含 `scheduleEnabled`，无 cron）、`ConnectorCredential`、`ConnectorItemPage` | pass                  | `Connector:5700-5789`、`ConnectorCreate:5800-5834`、`ConnectorUpdate:5835-5862`、`ConnectorCredential:5863-5880`、`ConnectorItem:5949-5990`、`ConnectorItemPage:5991-6000`；Create required 含 `defaultVisibility`，Update `minProperties: 1` 且 `scheduleEnabled` 可选，均无 cron；测试 `createConnector`/`updateConnector`/`connectorSchemas` 断言                                                |
| `KnowledgeDocument` 增加 `connectorId`/`externalId`，`sourceType` 加 `CONNECTOR`                                                                         | pass                  | `KnowledgeDocument:5397-5460`；两个连接器字段可选、`connectorId` uuid、`externalId` 描述末段展示；`sourceType` enum 三值；测试 `knowledgeDocumentConnectorFields`                                                                                                                                                                                                                                   |
| M05 `1.4.0-m05` 端点/schema 向后兼容，无删除/破坏性变更                                                                                                  | pass                  | 旧/新 YAML 结构化比较：removed_paths=[]、removed_ops=[]、removed_schemas=[]；既有 schema 无 required/property 删除，唯一 enum 变化是 `sourceType` 追加 `CONNECTOR`；`git diff --name-status` 无 M05 端点删除                                                                                                                                                                                        |
| M04/Knowledge 既有契约测试仍通过                                                                                                                         | pass                  | 独立复跑同一选择器：Knowledge 10、Model108a/108b 各 1、CostPage 3、UserSummaryDepartmentContract 3，全部 0/0/0                                                                                                                                                                                                                                                                                      |
| 每个 operationId 至少一条契约测试；断言路径/方法/权限/CSRF/错误码/请求响应 schema/必填字段/additionalProperties/deferred；测试只读 YAML                  | pass（with residual） | 8 个 operationId 各一个方法（`listConnectors`/`createConnector`/`updateConnector`/`deleteConnector`/`putConnectorCredential`/`testConnector`/`syncConnector`/`listConnectorItems`），另有 `connectorSchemas` 与 `knowledgeDocumentConnectorFields`；`OpenApiContract` 用 SnakeYAML 直接读 YAML，无 Spring/DB。残余 F-003：未断言 operationId 字段与部分禁用字段；F-004：`category` 失败必填表达不足 |
| `ConnectorUpdate` 部分更新（只含 `scheduleEnabled` 合法）与 ADR §7.4 一致                                                                                | pass                  | ADR §7.4（`:756`）把 PATCH 定义为“改配置/可见范围/调度开关”，§6.3（`:575`）明确 `scheduleEnabled` 是连接器级布尔；`ConnectorUpdate` 全部字段可选 + `minProperties: 1`，只含 `scheduleEnabled` 合法，空 body 400；要求强必填会阻断“连接测试后只启用调度”的合法流程。无需 finding                                                                                                                     |
| 不以 404/deferred 冒充实现                                                                                                                               | pass                  | 8 个连接器 operation 均无 `x-riccon-deferred`；`x-riccon-deferred` 无 `knowledge-connectors`；`KnowledgeContractM05Test` 也断言 M05b 路径非 deferred。真实端点实现按任务边界由 B6 承接，不在本任务验证                                                                                                                                                                                              |

## 验证了什么

### 静态范围与 diff

- 实现提交 `3928aee` 的父为 `c3c8d2c`；`git diff --name-status c3c8d2c..3928aee` 只有 6 个文件：
  `contracts/openapi.yaml`、`contracts/README.md`、`ConnectorContractM05bTest.java`、
  `KnowledgeContractM05Test.java`、`ModelContract108aTest.java`、`ModelContract108bTest.java`。
  全部落在 `contracts/**` 与 `apps/api/src/test/java/**/contract/**`，无 Java 生产代码、V12/迁移、前端、部署或
  `.riccon` 生产改动；`git diff --check` exit 0。
- `d605ed1` 是 `3928aee` 与 `bb7dec4` 的 merge；`git diff 3928aee..d605ed1 -- contracts apps/api/src/test/java/com/riccon/api/contract`
  为空，说明 EV-0963 在 merge 上运行时契约/测试内容与 `3928aee` 完全相同。`d605ed1..8d96d04` 只增加 EV-0963
  与状态/任务 JSON。
- 未发现密钥、token、真实租户数据或调试输出进入 diff；契约示例仍为占位符。

### 契约结构与向后兼容

- 用 Ruby YAML 解析旧/新契约并做结构比较：新增 6 个连接器 path、8 个 operation、18 个 `Connector*` schema；
  无删除 path/operation/schema；既有 schema 无 required/property 删除；唯一 enum 变化是
  `KnowledgeDocument.sourceType` 从 `[UPLOAD, SEED]` 追加 `CONNECTOR`，属于 ADR §7.4 明确要求的 additive 增量。
- 版本、info 描述、Knowledge tag、错误响应描述均更新到 M05b；`x-riccon-deferred` 不再包含
  `knowledge-connectors`，符合“不以 deferred 冒充”。
- 连接器错误响应使用现有 `Knowledge*` 组件并扩展描述：KB-400/403/409/503 与 TENANT-404 均可由
  `resolvedResponse` 解析；`sync`/`test` 的 503 语义与 ADR §6.6/§7.5 的“服务不可用”一致；`KB-404` 仅用于
  ACL 通过后的无源文件下载，连接器端点不存在该场景（§5.4 矩阵要求 connectorId 不存在/跨租户用 TENANT-404）。

### 测试与证据

| 证据    | 提交    | 执行者      | 结果                    | 复核                                                                                                |
| ------- | ------- | ----------- | ----------------------- | --------------------------------------------------------------------------------------------------- |
| EV-0963 | d605ed1 | riccon/lead | exit 0；note 称 26 用例 | commit/dirty=false/exit 0/日志 sha256 均匹配；日志因 `-q` 为空；独立复验实际 29 用例 0/0/0（F-002） |

- 独立复跑（main 工作树，2026-11-24）：`./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  → exit 0；`apps/api/target/surefire-reports/*Contract*.txt`：
  - `ConnectorContractM05bTest` Tests run 11, Failures 0, Errors 0, Skipped 0
  - `KnowledgeContractM05Test` 10/0/0/0
  - `ModelContract108aTest` 1/0/0/0
  - `ModelContract108bTest` 1/0/0/0
  - `CostPageContractTest` 3/0/0/0
  - `UserSummaryDepartmentContractTest` 3/0/0/0
    合计 29/0/0/0。
- `./mvnw -q spotless:check` → exit 0（根 reactor，含新测试格式）。
- `ruby -e "require 'yaml'; YAML.load_file('contracts/openapi.yaml'); YAML.load_file('contracts/internal-api.yaml')"`
  → `yaml-ok`。
- 契约测试只依赖 SnakeYAML 与 JUnit/AssertJ；`OpenApiContract.locateRepositoryRoot` 从 `user.dir` 向上定位
  `contracts/openapi.yaml`，不启动 Spring、不连接数据库、不调用端点，满足“只读 YAML”。

### Gate

`riccon gate check --module M25` 当前 FAIL（exit 非 0），原因包括：

- TASK-0203/TASK-0210 仍为 review，其余 9 个任务 planned，模块无 review；
- gate 把同级 O2a/M23-M24 记账提交的 `deploy/compose/docker-compose.test.yml`、`docs/ACCEPTANCE.md`、
  `docs/RUNBOOK.md` 算入 TASK-0203 越界（F-005；实际 `c3c8d2c..3928aee` 六文件均在 scope 内）；
- EV-0571/EV-0908 为 manual 证据的 warning（非本任务）。
  这些是 M25 尚在 building 的预期状态，不是 TASK-0203 的契约失败；本任务 review 不使模块 gate 通过。

## 未验证

- **B6 真实端点/同步/凭据实现**：本任务只交付契约与 YAML 契约测试；`POST /connectors`、`/test`、`/sync`、
  `/items` 的真实 controller/service、SSRF allowlist、凭据信封加密/掩码、并发 409、删除传播、tombstone 复用
  均归 TASK-0204 及 V1b/V2b，未在本 review 验证。
- **运行时安全**：契约层已检查权限/CSRF/凭据只写不读/跨租户 404，但实际 authz、日志脱敏、审计白名单、
  `RICCON_CONNECTOR_ENABLED=false` 行为、连接器对象下载的 `RICCON-KB-404` 运行路径均未验证。
- **M05/M04 全量回归与模块 gate**：未运行合同全量 build/test/coverage/E2E/a11y/perf/migration；任务级只跑
  `*Contract*Test` 与 spotless，模块全量应在 M25 gate 由 verifier 一次执行。
- **OpenAPI 外部校验/生成**：只做了 YAML 解析、任务契约测试与手工结构比较；未运行 openapi-generator、
  Redocly 或其他外部 linter。`contracts/README.md` 的 YAML 校验命令已执行，未发现语法错误。
- **`RICCON-KB-404` 连接器运行路径**：共享下载端点契约保留该码，但连接器文档下载的实际实现对 B6 交付，
  本任务不验证。

## Findings

| ID    | 级别 | 状态     | 摘要                                                                              | Owner / Due                                   |
| ----- | ---- | -------- | --------------------------------------------------------------------------------- | --------------------------------------------- |
| F-001 | P2   | accepted | README 称响应不返回 bucket 全名，但 `Connector.config.bucket` 必填且明确原样返回  | builder-kb-contract / B6 启动前               |
| F-002 | P3   | accepted | EV-0963 日志为空、note 26 与实际 29 不一致；独立复验 29/0/0/0                     | lead / M25 gate 前                            |
| F-003 | P3   | accepted | 契约测试未断言 operationId，未覆盖 credentialKeyId/credentialCiphertext 禁止      | builder-kb-contract / B6 启动前或 M25 gate 前 |
| F-004 | P3   | accepted | `ConnectorTestResult.category` 未声明失败必填，ADR §6.3 分类无法由 schema 锁定    | builder-kb-contract / B6 启动前               |
| F-005 | P3   | accepted | gate 把同级 O2a 的 deploy/docs 计入 TASK-0203 越界（实际 diff 六文件在 scope 内） | lead / M25 gate 前                            |

P0/P1 已清零；P2/P3 均 accepted，且写 owner/due/accepted_reason。verdict=pass。

## 剩余风险

1. **契约文档与 schema 冲突（F-001）**：B6 若按 README 字面省略 bucket，会破坏 `Connector` 必填 schema；
   按 OpenAPI/ADR 实现则 README 的安全边界描述失真。建议 B6 启动前修正 README，或在 B6 任务卡明确
   “以 OpenAPI/ADR §7.4 为准”。
2. **证据可审计性（F-002）**：EV-0963 的 `-q` 空日志不能单独证明测试执行；本次独立复验已补足，但 M25 gate
   前最好补录一条非 quiet EV。
3. **契约测试回归强度（F-003/F-004）**：operationId 重命名、Connector 加入凭据相关字段、连接测试失败省略
   category 时现有测试不会变红；B6 启动前补断言成本低。
4. **运行时实现未验证**：B6 的 SSRF、凭据加密、跨租户/非管理员 403/404、同步 202/409、删除传播是 M05b
   的主要风险面；本任务 pass 不能被引用为 SEC-CONN-001/003/004 或 IT-CONN-101..107 的通过证据。
5. **M25 gate 仍失败**：10 个任务未完成、无模块 review；F-005 的 scope 伪差异需 Lead 在 closeout 说明，
   不影响 TASK-0203 的 done 判断。

## 下一步

- **建议 Lead 将 TASK-0203 标 done**：任务卡 evidence 保持 `EV-0963`，`end_commit=3928aee`；在 closeout
  记录 F-001/F-003/F-004 为 B6 启动前残余，F-002 为 M25 gate 前证据补录项，F-005 为 gate scope 口径说明。
- **B6（TASK-0204）启动前**：修正 F-001 的 README bucket 表述；补 F-003 的 operationId/禁用字段断言；明确
  F-004 的失败分类（category 必填可空或“失败时必填”）；同时处理 TASK-0210 review 的 F-001/F-002。
- **M25 gate 前**：B6/V1b/V2b 完成真实端点、安全、性能与迁移验证；Verifier 跑模块全量合同 build/test/coverage/
  E2E/a11y/security/perf 并写模块 review。
- 本评审不替代 M25 模块 review、Product 预验收或用户验收。
