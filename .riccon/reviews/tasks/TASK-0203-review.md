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
  - 34db8d2aeca6e806f4b4db38dd1e64f36bc634b5
  - 75f76dcfd079f6cf8f4106dc901f6ce016a2a893
head_commit: 75f76dcfd079f6cf8f4106dc901f6ce016a2a893
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
  不适用：本任务没有生产代码、迁移、UI、E2E 或性能预算变更。F-001/F-002/F-003/F-004 已在 34db8d2 + EV-0966
  修复；F-005 为 gate scope 口径残余，accepted 跟踪。
reviewed_evidence:
  - EV-0963
  - EV-0966
evidence:
  - EV-0963
  - EV-0966
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: riccon/builder-kb-contract
    due: fixed in 34db8d2
    title: "contracts/README 与 Connector.config.bucket 的脱敏表述已对齐"
    summary: >-
      原评审发现 README 称响应/日志/审计不返回 bucket 全名，但 `Connector.config.bucket` 是必填且
      `ConnectorConfig.bucket` 描述明确“响应原样返回”。34db8d2 已把 README 改为“API 响应向有权限的管理员
      返回配置（含 `config.bucket`/`config.prefix`）；日志不记 bucket 全名、`externalId`、对象 key 或凭据；
      审计 details 只记 `bucket`/`prefix` 摘要（`prefix` 截断 ≤200）”，并同步 `ConnectorConfig.bucket/prefix`
      与 `Connector.config` 的 OpenAPI 描述。该口径与 ADR-0023 §6.5/§6.6 一致：API 可向 TENANT_ADMIN 返回配置，
      日志禁止 bucket 全名/externalId/对象 key/凭据，审计 details 用摘要。已无残留冲突。
    evidence: >-
      contracts/README.md:105、:117、:130；contracts/openapi.yaml:5674、:5679、:5733-5735；
      docs/adr/ADR-0023-m05-knowledge-depth.md:601-606、:619（§6.5/§6.6）；
      `git diff 3928aee..34db8d2 -- contracts/README.md contracts/openapi.yaml`。
  - id: F-002
    severity: P3
    status: fixed
    owner: riccon/lead
    due: fixed in EV-0966
    title: "EV-0966 note 已改为 29 用例；独立复验 29/0/0/0"
    summary: >-
      EV-0963 的 note 写 26 个用例，实际 `*Contract*Test` 选择器运行 29 个（Connector 11 + Knowledge 10 +
      Model108a/108b 各 1 + CostPage 3 + UserSummaryDepartmentContract 3）。EV-0966 的 note 已改为 29，
      命令/commit/dirty=false/exit 0/日志 sha256 均已核对；该 EV 仍因 `-q` 日志为空，但本次独立复验已记录
      29/0/0/0，证据可审计性缺口已按 accepted_reason 的“在模块 review 记录独立复验”替代路径收敛。
    evidence: >-
      .riccon/evidence/TASK-0203/EV-0966.json:5-10、:18；EV-0966 日志 sha256
      `ba8a96d8a009508eb9642f1109de05dba993cebcc638b494822d41b01731f6e8`；
      独立复跑 `./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false`
      → 29 个用例 0/0/0；surefire 报告六类计数。
  - id: F-003
    severity: P3
    status: fixed
    owner: riccon/builder-kb-contract
    due: fixed in 34db8d2
    title: "8 个连接器 operation 已断言 operationId；Connector 已禁止 credentialKeyId/credentialCiphertext"
    summary: >-
      34db8d2 在 8 个 operation 测试中逐一加入 `assertThat(operation.get("operationId")).isEqualTo("<name>")`：
      listConnectors/createConnector/updateConnector/deleteConnector/putConnectorCredential/testConnector/
      syncConnector/listConnectorItems；同时在 `connectorSchemas` 的 `Connector` propertyNames 禁止列表加入
      `credentialKeyId` 与 `credentialCiphertext`。当前 OpenAPI 的 8 个 operationId 经独立 grep 核对无重复且
      与测试一致；Connector schema 也确实不含这两个字段。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest.java:72、:105、:153、:200、
      :227、:261、:313、:344、:410-417；contracts/openapi.yaml:1240、:1274、:1309、:1344、:1376、:1410、
      :1440、:1473；`git diff 3928aee..34db8d2 -- apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest.java`。
  - id: F-004
    severity: P3
    status: fixed
    owner: riccon/builder-kb-contract
    due: fixed in 34db8d2
    title: "ConnectorTestResult 已用 OpenAPI 3.1 if/then/else 锁定失败必填 category"
    summary: >-
      34db8d2 在 `ConnectorTestResult` 增加条件 schema：`if` success=false 时 `then.required=[category]` 且
      category 为 string；`else`（成功）category 为 null；父 required 仍不含 category，允许成功时省略。
      该写法和既有 `UserCreateRequest` 的 OpenAPI 3.1 `if/then/else` 风格一致；`ConnectorContractM05bTest`
      已断言 if/then/else 的 required 与 type，并用独立 Ajv 样本验证：失败必须带 AUTH/NETWORK/ENDPOINT/CONFIG，
      失败缺 category/失败 null/成功带 string 均被拒，成功省略或 null 通过。
    evidence: >-
      contracts/openapi.yaml:5882-5915、:7327-7343（既有 if/then/else 风格）；
      apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest.java:280-302；
      docs/adr/ADR-0023-m05-knowledge-depth.md:576；独立 Ajv 条件 schema 样本验证（6/6 符合预期）。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M25 gate 前
    accepted_reason: >-
      这是 gate 的范围计算口径问题，不是 TASK-0203 的越界：实际实现提交 `c3c8d2c..34db8d2` 只有
      contracts/** 与 contract 测试 6 个文件；gate 使用 baseline..HEAD（75f76dc）把同级 O2a 的
      deploy/docs 改动算入 TASK-0203，和 TASK-0210 review F-003 是同一模式的对称表现。任务产物无需修改；
      Lead 在 M25 closeout 说明即可。
    title: "gate check 把同级 O2a 的 deploy/docs 改动计入 TASK-0203 越界"
    summary: >-
      `riccon gate check --module M25` 报 TASK-0203 changed files outside write_scope：
      `deploy/compose/docker-compose.test.yml`、`docs/ACCEPTANCE.md`、`docs/RUNBOOK.md`；但
      `git diff --name-status c3c8d2c..34db8d2` 只有 `contracts/openapi.yaml`、`contracts/README.md`、
      `ConnectorContractM05bTest.java`、`KnowledgeContractM05Test.java`、ModelContract108a/b 六个文件，全部在
      write_scope 内。越界文件来自同级 `68e3f03`（O2a）与 `d9e2cb0`（M23/M24 记账），随 merge 进入
      baseline..HEAD 的伪差异。期望：gate/closeout 以任务 end_commit 或实际 diff 为准；M25 gate 本身仍因
      9 个任务未完成、无模块 review 而失败，属预期。
    evidence: >-
      `riccon gate check --module M25` 输出；`git diff --name-status c3c8d2c..34db8d2`；
      `git diff --name-status c3c8d2c..75f76dc`；`git show --stat 68e3f03`、`d9e2cb0`；
      .riccon/reviews/tasks/TASK-0210-review.md F-003。
test_impact: >-
  独立运行任务指定命令
  `./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  → exit 0；surefire 报告 29 个用例，0 failures/0 errors/0 skipped（Connector 11、Knowledge 10、
  Model108a 1、Model108b 1、CostPage 3、UserSummaryDepartmentContract 3）。EV-0963 与 EV-0966 均为同一
  合同命令；EV-0966 note 已为 29。另运行 `./mvnw -q spotless:check` → exit 0；`ruby -e "require 'yaml';
  YAML.load_file('contracts/openapi.yaml'); ..."` → yaml-ok。未运行模块全量 test/coverage/E2E/a11y/perf/
  migration；本任务无生产代码、迁移、UI 或部署改动。
notes: >-
  实际 head 为 75f76dc；delta 修复提交为 34db8d2（父 3928aee，3 文件：contracts/README.md、
  contracts/openapi.yaml、ConnectorContractM05bTest.java），已 merge 到 75f76dc。`git diff
  34db8d2..75f76dc -- contracts apps/api/src/test/java/com/riccon/api/contract` 为空，EV-0966 在 merge 上
  运行的契约/测试内容与 34db8d2 完全相同；75f76dc 的其余差异来自 `8d96d04..174d88c` 的 .riccon
  评审/状态与同级 O2a 文档/部署。EV-0963 为首次交付证据，EV-0966 为返工后证据，均由 riccon/lead 录制（任务 owner 是
  builder-kb-contract），process fact 不影响证据内容。M25 gate 当前失败是预期：TASK-0203 现为 review、
  TASK-0210 已关闭、其余 9 个任务未完成、无模块 review；F-005 的 scope 伪差异不影响任务 done 判断。
created_at: "2026-11-24T05:16:03+08:00"
updated_at: "2026-11-24T05:30:52+08:00"
---

# REV-TASK-0203-001：TASK-0203（M05b-B2b 契约 1.5.0-m05b）独立评审（含 delta 复核）

## 结论

**通过（pass，delta 复核无新 P0/P1）。** TASK-0203 在 write_scope 内交付了 OpenAPI `1.5.0-m05b` 的 8 个连接器
operationId、连接器/凭据/test/sync/items schema，以及 `ConnectorContractM05bTest` 的 11 个契约测试；M05
`1.4.0-m05` 端点与 schema 未删除、未改变既有 required/property，只做 `KnowledgeDocument.connectorId/externalId`
与 `sourceType=CONNECTOR` 的 additive 增量。M04/Knowledge/Cost/UserSummary 的既有契约测试仍通过。

原评审的 F-001/F-002/F-003/F-004 已在 `34db8d2` + `EV-0966` 修复：bucket 脱敏表述与 ADR §6.5/§6.6 对齐、
EV note 改为 29 用例、8 个 operation 均断言 operationId、`Connector` 禁止 `credentialKeyId/credentialCiphertext`、
`ConnectorTestResult.category` 用 OpenAPI 3.1 `if/then/else` 锁定失败必填。F-005（gate scope 伪差异）保持
accepted，不影响任务产物。本评审独立复跑 `*Contract*Test` 为 29/0/0/0，`spotless:check` exit 0。

本 pass 只代表 TASK-0203 的契约与契约测试验收，**不代表** B6 的真实端点/同步/凭据实现、M25 模块 gate、
Product 预验收或用户验收通过。

## Delta 复核（3928aee..34db8d2）

| Finding                           | 复核结果   | 关键证据                                                                                                                                                                                                                                                                                                                                                 |
| --------------------------------- | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001（P2，bucket 表述）          | **已修复** | `contracts/README.md:105` 列表改为“返回配置（含 `bucket`/`prefix`），凭据仅掩码，不含端点 URL/对象 key”；`:117` 与 `:130` 明确“API 响应向有权限管理员返回配置；日志不记 bucket 全名/externalId/对象 key/凭据；审计 details 只记 bucket/prefix 摘要（prefix ≤200）”；`contracts/openapi.yaml:5674`、`:5679`、`:5733-5735` 同步描述。与 ADR §6.5/§6.6 一致 |
| F-003（P3，operationId/禁用字段） | **已修复** | `ConnectorContractM05bTest.java:72/:105/:153/:200/:227/:261/:313/:344` 逐一断言 8 个 operationId；`:410-417` 的 Connector propertyNames `doesNotContain` 已含 `credentialKeyId`/`credentialCiphertext`；当前 YAML 8 个 operationId 正确且无重复                                                                                                          |
| F-004（P3，category 条件必填）    | **已修复** | `contracts/openapi.yaml:5882-5915` 使用 `if success=false -> then required category(type string)` / `else category(type null)`；与既有 `UserCreateRequest:7327-7343` 的 3.1 风格一致；测试 `:280-302` 覆盖 if/then/else；独立 Ajv 六个样本 6/6 符合预期                                                                                                  |
| F-002（P3，证据 note）            | **已修复** | `EV-0966.json:18` note 为 29 个用例；commit `75f76dc`、dirty=false、exit 0、日志 sha256 匹配；独立复跑 29/0/0/0。日志仍因 `-q` 为空，但 note 已准确且本次复验记录已补足可审计性                                                                                                                                                                          |

## 验收对账

任务卡验收原文：

1. “OpenAPI 1.5.0-m05b：连接器 CRUD/credential/test/sync/items，M05 段不回归；每个 operationId 契约测试”
2. “端点真实实现由 B6 承接，不以 404/deferred 冒充”

| 验收子项                                                                                                                                                 | 结论 | 证据/说明                                                                                                                                                                                                                                                                                                                                                                                     |
| -------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `info.version=1.5.0-m05b`，8 个连接器 operationId 齐全（list/create/update/delete/credential/test/sync/items）                                           | pass | `contracts/openapi.yaml:4`；operationId 在 `:1240`/`:1274`/`:1309`/`:1344`/`:1376`/`:1410`/`:1440`/`:1473`；8 个测试逐一断言 operationId（`ConnectorContractM05bTest.java:72-344`）；独立 grep 无重复                                                                                                                                                                                         |
| 权限：读 `knowledge.connector.read`，写 `knowledge.connector.manage`                                                                                     | pass | list/items 用 read（`:1245`/`:1478`），create/update/delete/credential/test/sync 用 manage（`:1281`/`:1315`/`:1350`/`:1382`/`:1416`/`:1446`）；`PermissionCodes.java:40-44` 与 `RoleDefinitions.java:66-68` 一致；测试逐一断言                                                                                                                                                                |
| CSRF 与 IdempotencyKey                                                                                                                                   | pass | 6 个写操作全部引用 `CsrfToken`；create 与 sync 引用 `IdempotencyKey`，该参数 `required: false`（`:3779-3787`），符合 README “可选幂等键”；测试断言引用存在                                                                                                                                                                                                                                    |
| 错误码 `RICCON-KB-400/404/409/503`、`RICCON-TENANT-404`、`RICCON-AUTH-403`                                                                               | pass | `KnowledgeBadRequest:4105-4110`（KB-400）、`KnowledgeForbidden:4111-4116`（AUTH-403）、`KnowledgeNotFound:4117-4122`（TENANT-404）、`KnowledgeConflict:4129-4134`（KB-409）、`KnowledgeIndexUnavailable:4135-4140`（KB-503）；连接器端点 404 按 §5.4 用 TENANT-404；`RICCON-KB-404` 保留在共享文档下载端点 `:4123-4128`，由 `KnowledgeContractM05Test.downloadKnowledgeDocument:306-343` 断言 |
| Schema：`Connector`、`ConnectorCreate/Update`（创建必填 `defaultVisibility`，含 `scheduleEnabled`，无 cron）、`ConnectorCredential`、`ConnectorItemPage` | pass | `Connector:5700-5789`、`ConnectorCreate:5800-5834`、`ConnectorUpdate:5835-5862`、`ConnectorCredential:5863-5880`、`ConnectorItem:5949-5990`、`ConnectorItemPage:5991-6000`；Create required 含 `defaultVisibility`，Update `minProperties: 1` 且 `scheduleEnabled` 可选，均无 cron；测试 `createConnector`/`updateConnector`/`connectorSchemas` 断言                                          |
| `KnowledgeDocument` 增加 `connectorId`/`externalId`，`sourceType` 加 `CONNECTOR`                                                                         | pass | `KnowledgeDocument:5397-5460`；两个连接器字段可选、`connectorId` uuid、`externalId` 描述末段展示；`sourceType` enum 三值；测试 `knowledgeDocumentConnectorFields`                                                                                                                                                                                                                             |
| M05 `1.4.0-m05` 端点/schema 向后兼容，无删除/破坏性变更                                                                                                  | pass | 旧/新 YAML 结构化比较：removed_paths=[]、removed_ops=[]、removed_schemas=[]；delta 也未改变既有 required/property；唯一 enum 变化是 `sourceType` 追加 `CONNECTOR`                                                                                                                                                                                                                             |
| M04/Knowledge 既有契约测试仍通过                                                                                                                         | pass | 独立复跑同一选择器：Knowledge 10、Model108a/108b 各 1、CostPage 3、UserSummaryDepartmentContract 3，全部 0/0/0                                                                                                                                                                                                                                                                                |
| 每个 operationId 至少一条契约测试；断言路径/方法/权限/CSRF/错误码/请求响应 schema/必填字段/additionalProperties/deferred；测试只读 YAML                  | pass | 8 个 operationId 各一个方法，另有 `connectorSchemas` 与 `knowledgeDocumentConnectorFields`；8 个方法均断言 operationId；`OpenApiContract` 用 SnakeYAML 直接读 YAML，无 Spring/DB；F-003/F-004 已修复                                                                                                                                                                                          |
| `ConnectorUpdate` 部分更新（只含 `scheduleEnabled` 合法）与 ADR §7.4 一致                                                                                | pass | ADR §7.4（`:756`）把 PATCH 定义为“改配置/可见范围/调度开关”，§6.3（`:575`）明确 `scheduleEnabled` 是连接器级布尔；`ConnectorUpdate` 全部字段可选 + `minProperties: 1`，只含 `scheduleEnabled` 合法，空 body 400；要求强必填会阻断“连接测试后只启用调度”的合法流程。无需 finding                                                                                                               |
| 不以 404/deferred 冒充实现                                                                                                                               | pass | 8 个连接器 operation 均无 `x-riccon-deferred`；`x-riccon-deferred` 无 `knowledge-connectors`；`KnowledgeContractM05Test` 也断言 M05b 路径非 deferred。真实端点实现按任务边界由 B6 承接，不在本任务验证                                                                                                                                                                                        |

## 验证了什么

### 静态范围与 diff

- 首版实现 `3928aee` 的父为 `c3c8d2c`；delta `34db8d2` 的父为 `3928aee`，只改 3 个文件：
  `contracts/README.md`、`contracts/openapi.yaml`、`ConnectorContractM05bTest.java`。
- `git diff --name-status c3c8d2c..34db8d2` 仍只有 6 个文件：上述 3 个加首版的
  `KnowledgeContractM05Test.java`、`ModelContract108aTest.java`、`ModelContract108bTest.java`；全部落在
  `contracts/**` 与 `apps/api/src/test/java/**/contract/**`，无 Java 生产代码、V12/迁移、前端、部署或
  `.riccon` 生产改动；`git diff --check` exit 0。
- `git diff 34db8d2..75f76dc -- contracts apps/api/src/test/java/com/riccon/api/contract` 为空，说明
  EV-0966 在 merge 上运行的契约/测试内容与 `34db8d2` 完全相同；merge 的其余差异来自 `8d96d04..174d88c`
  的 .riccon 评审/状态与同级 O2a 文档/部署。
- 未发现密钥、token、真实租户数据或调试输出进入 diff；契约示例仍为占位符。

### 契约结构与向后兼容

- 用 Ruby YAML 解析旧/新契约并做结构比较：首版新增 6 个连接器 path、8 个 operation、18 个 `Connector*`
  schema；delta 无删除 path/operation/schema、无 required/property 变化，只新增描述与 `ConnectorTestResult`
  的 `if/then/else` 条件。既有 `KnowledgeDocument` 只追加可选 `connectorId`/`externalId` 与 `sourceType`
  枚举 `CONNECTOR`，属于 ADR §7.4 明确要求的 additive 增量。
- `ConnectorTestResult` 的条件 schema 与既有 `UserCreateRequest`（`openapi.yaml:7327-7343`）的 3.1
  `if/then/else` 风格一致；独立 Ajv 样本验证：失败+category 通过、失败缺 category/失败 null 拒绝、
  成功省略/null 通过、成功 string 拒绝（6/6 符合预期）。
- 版本、info 描述、Knowledge tag、错误响应描述均更新到 M05b；`x-riccon-deferred` 不再包含
  `knowledge-connectors`，符合“不以 deferred 冒充”。
- 连接器错误响应使用现有 `Knowledge*` 组件并扩展描述：KB-400/403/409/503 与 TENANT-404 均可由
  `resolvedResponse` 解析；`sync`/`test` 的 503 语义与 ADR §6.6/§7.5 的“服务不可用”一致；`KB-404` 仅用于
  ACL 通过后的无源文件下载，连接器端点不存在该场景（§5.4 矩阵要求 connectorId 不存在/跨租户用 TENANT-404）。

### 测试与证据

| 证据    | 提交    | 执行者      | 结果                    | 复核                                                                                       |
| ------- | ------- | ----------- | ----------------------- | ------------------------------------------------------------------------------------------ |
| EV-0963 | d605ed1 | riccon/lead | exit 0；note 称 26 用例 | 首版证据；commit/dirty=false/exit 0/日志 sha256 匹配；日志因 `-q` 为空；独立复验 29/0/0/0  |
| EV-0966 | 75f76dc | riccon/lead | exit 0；note 称 29 用例 | commit/dirty=false/exit 0/日志 sha256 `ba8a96d8…` 匹配；日志仍 326 字节；独立复验 29/0/0/0 |

- 独立复跑（main 工作树，HEAD 75f76dc，2026-11-24）：`./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  → exit 0；`apps/api/target/surefire-reports/*Contract*.txt`：
  - `ConnectorContractM05bTest` Tests run 11, Failures 0, Errors 0, Skipped 0
  - `KnowledgeContractM05Test` 10/0/0/0
  - `ModelContract108aTest` 1/0/0/0
  - `ModelContract108bTest` 1/0/0/0
  - `CostPageContractTest` 3/0/0/0
  - `UserSummaryDepartmentContractTest` 3/0/0/0
    合计 29/0/0/0。
- `./mvnw -q spotless:check` → exit 0（根 reactor，含 delta 后的新测试格式）。
- `ruby -e "require 'yaml'; YAML.load_file('contracts/openapi.yaml'); YAML.load_file('contracts/internal-api.yaml')"`
  → `yaml-ok`。
- 契约测试只依赖 SnakeYAML 与 JUnit/AssertJ；`OpenApiContract.locateRepositoryRoot` 从 `user.dir` 向上定位
  `contracts/openapi.yaml`，不启动 Spring、不连接数据库、不调用端点，满足“只读 YAML”。

### Gate

`riccon gate check --module M25` 当前 FAIL，原因包括：

- TASK-0203 仍为 review，其余 9 个任务未完成，模块无 review；
- gate 把同级 O2a/M23-M24 记账提交的 `deploy/compose/docker-compose.test.yml`、`docs/ACCEPTANCE.md`、
  `docs/RUNBOOK.md` 算入 TASK-0203 越界（F-005；实际 `c3c8d2c..34db8d2` 六文件均在 scope 内）；
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
- **OpenAPI 外部校验/生成**：只做了 YAML 解析、任务契约测试、手工结构比较与条件 schema 的 Ajv 样本验证；
  未运行 openapi-generator、Redocly 或其他外部 linter。
- **`RICCON-KB-404` 连接器运行路径**：共享下载端点契约保留该码，但连接器文档下载的实际实现对 B6 交付，
  本任务不验证。

## Findings

| ID    | 级别 | 状态     | 摘要                                                                                   | Owner / Due                            |
| ----- | ---- | -------- | -------------------------------------------------------------------------------------- | -------------------------------------- |
| F-001 | P2   | fixed    | README/OpenAPI 的 bucket/prefix 脱敏表述已与 ADR §6.5/§6.6 对齐                        | builder-kb-contract / fixed in 34db8d2 |
| F-002 | P3   | fixed    | EV-0966 note 已改为 29；独立复验 29/0/0/0                                              | lead / fixed in EV-0966                |
| F-003 | P3   | fixed    | 8 个 operation 均断言 operationId；Connector 禁止 credentialKeyId/credentialCiphertext | builder-kb-contract / fixed in 34db8d2 |
| F-004 | P3   | fixed    | `ConnectorTestResult.category` 已用 3.1 if/then/else 锁定失败必填，测试与 Ajv 样本覆盖 | builder-kb-contract / fixed in 34db8d2 |
| F-005 | P3   | accepted | gate 把同级 O2a 的 deploy/docs 计入 TASK-0203 越界（实际 diff 六文件在 scope 内）      | lead / M25 gate 前                     |

P0/P1 已清零；F-001/F-002/F-003/F-004 已 fixed，F-005 为 accepted（owner/due/accepted_reason 齐全）；verdict=pass。

## 剩余风险

1. **证据日志仍为 quiet 输出（F-002 修复的残余）**：EV-0966 的 note 已准确、独立复验已记录 29/0/0/0，但
   `-q` 日志仍不能单独展示测试计数；M25 gate 前如再录证据，建议补一条非 quiet 输出或保存 surefire 报告。
2. **gate scope 伪差异（F-005）**：M25 gate 仍会把同级 O2a 的 deploy/docs 算入 TASK-0203；Lead 在 closeout
   以 `c3c8d2c..34db8d2` 六文件为准说明。
3. **运行时实现未验证**：B6 的 SSRF、凭据加密、跨租户/非管理员 403/404、同步 202/409、删除传播是 M05b
   的主要风险面；本任务 pass 不能被引用为 SEC-CONN-001/003/004 或 IT-CONN-101..107 的通过证据。
4. **M25 gate 仍失败**：9 个任务未完成、无模块 review；TASK-0203 可先 done，但模块 gate 需全部任务收口。

## 下一步

- **建议 Lead 将 TASK-0203 标 done**：任务卡 evidence 保持 `EV-0963`/`EV-0966`，`end_commit=34db8d2`；
  F-001/F-002/F-003/F-004 已 fixed，无需带 residual；F-005 在 closeout 说明。
- **B6（TASK-0204）启动**：契约已按 OpenAPI/ADR 对齐，可直接按 `contracts/openapi.yaml` 实现；同时处理
  TASK-0210 review 的 F-001/F-002。
- **M25 gate 前**：B6/V1b/V2b 完成真实端点、安全、性能与迁移验证；Verifier 跑模块全量合同 build/test/coverage/
  E2E/a11y/security/perf 并写模块 review。
- 本评审不替代 M25 模块 review、Product 预验收或用户验收。
