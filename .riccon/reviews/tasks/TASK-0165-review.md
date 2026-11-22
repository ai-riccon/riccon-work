---
review_id: REV-TASK-0165-001
module_id: M05
task_id: TASK-0165
reviewer:
  agent: riccon/verifier
  session: ses_dfc2ce768ffezPoklGGC1JvXdR
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - e059162041bbbfc739c37281f6c882ba6710b7f2
  - 5118aaca4559d98ca9c30e8f6802249fd011128c
  - 8eefc20f5cf65e0ba8d4c218697060e1397eebce
head_commit: 8eefc20f5cf65e0ba8d4c218697060e1397eebce
evidence: [EV-0779, EV-0785]
reviewed_evidence: [EV-0779, EV-0785]
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "TASK-0166（M05-B3）实现前 / M05 gate 前"
    title: "`knowledge.acl.manage` 端点门与 AC-06/09「不可见→404」的资源判定顺序未在契约中锁定"
    summary: >-
      契约把 ACL GET/PUT、DELETE 的 `x-riccon-permission` 都声明为 `knowledge.acl.manage`
      （openapi.yaml:1063、1084、1109），同时承诺「不可见/跨租户统一 404、已可见但无管理能力 403」；
      ADR-0023 §5.4:340 也要求「先按同一谓词取文档；取不到 -> 404；取到但无管理能力 -> 403」。
      但 ADR-0014:33 要求每个操作在 URL + 方法级做双重 RBAC。普通员工（`MEMBER` 只有 `knowledge.read`）
      在严格端点门上会先得到 403，永远到不了资源谓词，因此 AC-06/09 与 §5.4 矩阵对「不可见文档」
      要求的 404 对普通员工不可达；B3 若按端点注解实现，B7/SEC-KB-002 的 404 断言会失败。
      契约本身是实现得通的（先 ACL 取文档再判管理能力，或自定义授权），但没有写明这个顺序，
      也没有说明 `x-riccon-permission` 不是 B3 的硬路由门。未发现数据泄露（403 比 404 更严格），
      故按 P2 accepted，但必须在 B3 开工前定口径并由 SEC-KB-002 锁定。
    evidence: >-
      contracts/openapi.yaml:1061-1063、1083-1084、1108-1109、3821-3822；
      docs/adr/ADR-0023-m05-knowledge-depth.md:340、353-359、727-731；
      docs/adr/ADR-0014-m02-api-event-contract-and-sse.md:33；
      .riccon/tasks/TASK-0166.json（B3 planned，尚未实现 ACL/delete/reindex）。
    accepted_reason: >-
      B2 忠实复刻 ADR-0023 §7.4/§5.4，漂移来自 ADR 自身的端点门与资源谓词顺序，不是 B2 自创；
      未产生现实泄露，且 M05 创建人当前恒为 `TENANT_ADMIN`，创建人例外不会绕过角色。B3 尚未开工，
      现在接受不阻断 B2 契约交付；但 M05 gate 前必须由 Architect/Lead 明确「资源先于角色」或修订 AC 措辞，
      并让 SEC-KB-002 绑定最终口径，否则 AC-06/09 的 404 子句不可验证。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-contract"
    due: "M05 gate 前"
    title: "通用错误组件描述与端点语义不一致（KnowledgeForbidden/KnowledgeConflict）"
    summary: >-
      `KnowledgeForbidden`（openapi.yaml:3821-3822）描述「非创建人且非 `knowledge.acl.manage`」，
      但该组件也被 GET 详情（1052）、下载（1176）引用；读端点只需 `knowledge.read` + ACL 可见，
      非创建人/非管理员的可见读取应返回 200，不应被这段描述误导为 403。`KnowledgeConflict`
      （3839-3840）把「文档已软删除」列为 409，但 DELETE 明确「重复删除幂等，已软删除仍返回 204」
      （1059-1062），且 DELETE 并不引用该组件。两处都是 prose 级歧义，端点自身描述与 ADR 更具体，
      不影响机器可读 schema；按 P3 接受，M05 gate 前择一收敛（读端点改用通用 Forbidden，或给组件加适用操作说明）。
    evidence: >-
      contracts/openapi.yaml:1052、1059-1063、1176、3821-3822、3839-3840；
      docs/adr/ADR-0023-m05-knowledge-depth.md:730（下载仅 `knowledge.read` + ACL）。
    accepted_reason: >-
      端点级 description 与 ADR 已给出更精确语义，组件描述只影响阅读者理解，不会改变响应码或字段；
      B3 实现按端点描述即可，M05 gate 前顺带修正文档。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/builder-kb-contract"
    due: "TASK-0166（M05-B3）实现下载前 / M05 gate 前"
    title: "下载响应契约精度：502 无稳定错误码、200 media type 与动态 `Content-Type` 冲突、Content-Disposition 编码措辞"
    summary: >-
      (1) 下载 404 之后的失败路径引用通用 `BadGateway`（openapi.yaml:1180、3772-3776），
      该组件描述不含任何 `RICCON-*` 码；ADR §7.4:753 只列出 M05 的 `RICCON-KB-400/404/409/503`，
      B3 遇到对象存储读取失败时没有可用的已批准码，只能自创（如 KB-502）或改用 503，都会变成未记录的契约变更。
      (2) 200 响应只声明 `application/octet-stream`（openapi.yaml:1169），但描述要求 `Content-Type` 取文档
      `mimeType`（1141）；OAS 3.1 明确忽略响应头里声明的 `Content-Type`，生成客户端会按 octet-stream 处理，
      与真实响应不一致。(3) `Content-Disposition` 描述写「RFC 5987 编码」却给出 `filename="..."`（1155-1157），
      中文标题应使用 `filename*=UTF-8''...`；建议 B3 同时输出 `filename` 与 `filename*`。
      三点都不影响当前契约 YAML 合法性（Redocly 0 error），按 P3 接受，B3 下载实现前收敛并在 M05 契约测试补断言。
    evidence: >-
      contracts/openapi.yaml:1137-1181、3772-3776；
      docs/adr/ADR-0023-m05-knowledge-depth.md:319、753；
      Phase 2 Redocly：`contracts/openapi.yaml` valid，0 error / 3 既有 warning（与 M04 版本一致）。
    accepted_reason: >-
      下载尚未实现，当前没有行为与契约不一致；契约仍可生成客户端并表达「服务端代理、不暴露 key」，
      缺口是错误码与头部精度，属于 B3 实现前的契约收口项，不影响 B2 的端点/权限/安全边界。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-contract"
    due: "M05 gate 前"
    title: "`contracts/README.md` 写「M05 已交付文档级 ACL/OCR 引用定位」，早于实际实现"
    summary: >-
      `contracts/README.md:108` 写「M05 已交付文档级 ACL/OCR 引用定位」；当前 M05 仍 `building`，
      B3–B11/V1/V2 未完成，本任务只交付契约。措辞会让读者把「契约已声明」误读为「功能已交付」，
      与 AC-22「端点真实实现」和模块 gate 的口径冲突。预期改为「M05 契约已声明文档级 ACL/OCR 引用定位，
      实现由 B3–B11 交付，M05 gate 复验」。
    evidence: >-
      contracts/README.md:108；
      .riccon/modules/M05.json（status=building）；
      .riccon/tasks/TASK-0166.json（B3 planned）。
    accepted_reason: >-
      纯文档措辞，不影响契约结构、测试或用户行为；B2 closeout 或 M05 文档整理时修正即可。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "TASK-0165 closeout / M05 gate 前"
    title: "任务验收第 2 条「端点真实实现」超出 B2 写范围，任务级不可验证"
    summary: >-
      `TASK-0165.acceptance[1]` 同时要求「每个 operationId 契约测试」与「端点真实实现，不以 404/deferred 冒充」。
      B2 的写范围只有 `contracts/**` 与契约测试目录；`e059162` 的 6 个文件不含任何 controller/service，
      运行期实现属于 TASK-0166（M05-B3）。本评审能证明的是：契约声明了真实响应（202/200/204，无
      `x-riccon-deferred`，无 404 占位），每个 operationId 有契约用例；不能证明端点已实现。
      建议 closeout 时把该条改写为「契约层不得以 404/deferred 冒充，运行期实现由 TASK-0166 与 AC-22 复验」，
      避免任务 done 被误读为 ACL/下载/reindex 已可用。
    evidence: >-
      .riccon/tasks/TASK-0165.json:12-15；
      `git diff --name-status 67af216..e059162` = 6 文件，均在 write_scope；
      apps/api/src/main/java/com/riccon/api/knowledge/controller/KnowledgeController.java:66-106（当前只有 list/create/get）；
      docs/adr/ADR-0023-m05-knowledge-depth.md:964（B3 负责实现）。
    accepted_reason: >-
      验收措辞问题不影响 B2 产物质量；契约测试注释也已明示「实现由 M05-B3 交付」，没有虚假声称。
      但必须在任务 closeout 时改写，否则模块 gate/AC-22 无法区分「契约声明」与「真实实现」。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-contract"
    due: "M05 gate 前"
    title: "TASK-0165 的 EV 日志为空，测试计数不能由 EV 自证；EV-0777 保留文件缺少 superseded 注记"
    summary: >-
      EV-0779 的命令带 `-q` 与 `-Dsurefire.failIfNoSpecifiedTests=false`，日志只有 326 字节的元数据头，
      stdout/stderr 为空（SHA-256 与 JSON 一致，未被篡改）。这种日志在「15 tests 通过」和「0 tests 被匹配」
      两种情况下完全相同，EV 自身不能证明 builder 报告的 15 run/0 fail。独立复核结果支持 builder 的数字：
      TASK-0180 的全量 EV-0782 在 `eb7285b` 生成 surefire 报告
      KnowledgeContractM05Test 10/0/0、ModelContract108a 1/0/0、108b 1/0/0、UserSummaryDepartmentContractTest 3/0/0；
      verifier EV-0785 复跑 10/1/1，合计 12/0/0（命令未含 UserSummary）。EV-0777（dirty=true）文件保留、
      SHA 校验通过、JSON 与日志自洽，task.evidence 只移除引用是诚实的 supersede；但 EV-0777 自身 note
      未写「被 EV-0779 取代」，只有 EV-0779 的 note 提到 supersedes。建议后续定向 EV 去掉 `-q` 或附加
      surefire 汇总，并给 superseded EV 加注记。
    evidence: >-
      .riccon/evidence/TASK-0165/EV-0779.json（commit 5118aac、dirty=false、exit 0，日志 sha256 c8994db8…）；
      .riccon/evidence/TASK-0165/EV-0777.json（commit e059162、dirty=true、exit 0，日志 sha256 674b698b…）；
      apps/api/target/surefire-reports/TEST-com.riccon.api.contract.*.xml（EV-0782 生成）；
      .riccon/evidence/TASK-0165/EV-0785.json（commit 8eefc20、dirty=false、exit 0）。
    accepted_reason: >-
      没有假绿：同一命令在本评审 EV-0785 下 exit 0 且 surefire 显示 10/1/1 全通过，EV-0782 另有 3 个
      UserSummary 用例全通过，builder 的数字可复现；EV-0777 未被篡改且已由 EV-0779 取代。
      缺口是证据可读性与注记，M05 gate 前改进即可，不阻断 B2。
created_at: "2026-11-22T23:28:02+08:00"
---

# REV-TASK-0165-001：TASK-0165 / M05-B2 契约 `1.4.0-m05` 独立评审

本评审是任务级独立技术验收，不是 Product 预验收、模块评审或用户产品验收。Phase 1 静态审查与
Phase 2 独立定向验证均已完成，`verdict: pass` 仅表示 B2 的契约交付诚实、可测、与 ADR-0023 §7.4 一致；
M05 仍 `building`，模块 gate 不因本评审通过。

## 1. 结论

- **未发现 P0/P1。** 契约版本 `1.4.0-m05`；5 个 M05 端点（ACL GET/PUT、DELETE、download、reindex）
  的路径、operationId、权限、CSRF、幂等键、响应码与 ADR-0023 §7.4:723-753 逐项对齐；`KnowledgeDocument`、
  CreateRequest、ACL、Citation/ArtifactCitation、parseSummary 增量齐全；M05b 连接器端点/字段零泄漏。
- **Phase 2 独立测试通过**：EV-0785 在干净提交 `8eefc20`（`contracts/**` 与契约测试自 `e059162` 起未变）
  exit 0、`dirty=false`，`surefire` 显示 `KnowledgeContractM05Test` 10、`ModelContract108aTest` 1、
  `ModelContract108bTest` 1，合计 12 tests / 0 failure / 0 error / 0 skip。
- **Redocly 复核**：`@redocly/cli@1.34.3 lint` 通过，0 error / 3 warning；对 M04 版本同命令也是同样 3 条
  warning（OIDC 302 两条 + 未使用的 `QuotaExceeded` 组件），确认无新增告警。
- **证据诚实性成立**：EV-0779 commit `5118aac`、dirty=false、exit 0、日志 SHA-256 与 JSON 一致；
  `67af216..e059162` 6 个文件全部落在 write_scope；EV-0777（dirty=true）文件保留且未篡改，
  task.evidence 移除引用是可解释的 supersede。EV-0779 的空日志使测试数不能由 EV 自证（F-006），
  但已由 EV-0782 与本评审 EV-0785 独立复现 builder 的计数。
- **verdict=`pass`**（P2/P3 全部 accepted，不保留 open finding）。F-001 是 B3 开工前必须定口径的
  403/404 语义，不是 B2 契约错误；F-002–F-006 是文档/证据卫生项，M05 gate 前处理。

## 2. 审查范围

| 项          | 值                                                                                                               |
| ----------- | ---------------------------------------------------------------------------------------------------------------- |
| baseline    | `67af2161dbecd0e8110aa6bf4e4404421dde14b5`                                                                       |
| 代码提交    | `e059162041bbbfc739c37281f6c882ba6710b7f2`                                                                       |
| 证据提交    | `eb7285b`（task JSON 记录 tools_used）/ 合并 `743e7c2`                                                           |
| 验证时 HEAD | `8eefc20f5cf65e0ba8d4c218697060e1397eebce`（TASK-0181 的 web 测试提交，与契约无关）                              |
| diff        | 6 文件 / +931 −49：`contracts/openapi.yaml`、`contracts/README.md`、`OpenApiContract.java`、3 个契约测试         |
| write_scope | `contracts/**`、`apps/api/src/test/java/com/riccon/api/contract/**`；6 个文件全部命中，无生产源码/迁移/配置夹带  |
| 规模变化    | `ruby YAML`：OpenAPI 3.1.0，90 paths / 165 schemas，112 operations，0 重复 operationId，214 个 `$ref` 全部可解析 |

范围诚实性：`git diff --stat 67af216..e059162` 与 `git log e059162` 一致；`git diff --check` exit 0。
baseline 从 `b90f379` 改绑 `67af216` 是排除并行 M24 提交的诚实重绑：`b90f379..67af216` 对
`contracts/**` 与 `apps/api/src/test/java/com/riccon/api/contract/**` 的 diff 为空。

## 3. 契约对账（ADR-0023 §7.4）

| 端点                                                 | ADR §7.4 要求                                             | 契约                                                                                                    | 结论 |
| ---------------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | ---- |
| `PUT /knowledge/documents/{id}/acl`                  | 创建人或 `knowledge.acl.manage`；整体替换；200 文档       | `putKnowledgeDocumentAcl`，`knowledge.acl.manage`，CSRF，200→KnowledgeDocument，400/401/403/404         | 一致 |
| `GET /knowledge/documents/{id}/acl`                  | 同上；visibility/departmentId/users[]/creatorUserId       | `getKnowledgeDocumentAcl`，`knowledge.acl.manage`，200→KnowledgeDocumentAcl                             | 一致 |
| `DELETE /knowledge/documents/{id}`                   | 创建人或 `knowledge.acl.manage`；软删除 reason=USER       | `deleteKnowledgeDocument`，`knowledge.acl.manage`，CSRF，204，幂等                                      | 一致 |
| `GET /knowledge/documents/{id}/download`             | `knowledge.read` + ACL；流式；无源文件 KB-404；不暴露 key | `downloadKnowledgeDocument`，`knowledge.read`，200 二进制 + 4 个响应头，404 区分 TENANT-404/KB-404，502 | 一致 |
| `POST /knowledge/documents/{id}/reindex?force=false` | `knowledge.manage`；默认补缺失页；force 整本              | `reindexKnowledgeDocument`，`knowledge.manage`，CSRF + Idempotency-Key，202，400/403/404/409/503        | 一致 |

补充核对：

- **CSRF**：ACL PUT、DELETE、reindex 均引用 `CsrfToken`；GET/下载不要求；与既有写端点一致。
- **幂等**：reindex 引用 `Idempotency-Key`；DELETE 描述重复删除幂等 204；ACL PUT 是整体替换，天然幂等。
- **错误码**：新组件只承载 `RICCON-KB-400/404/409/503` 与 `RICCON-AUTH-403`/`RICCON-TENANT-404`，
  与 ADR §7.4:753 一致；下载 502 的码缺口见 F-003。
- **文案**：`/knowledge/documents` 列表/创建、`/workspaces/{id}/files` 列表/上传的“租户级共享 /
  文档级权限在后续版本”已移除，改为 M05 ACL 语义并明确原空间路径不放大；根 `x-riccon-deferred`
  删除 `knowledge-acl-ocr`，未引入连接器项（openapi.yaml:48-59、142-146、973-1001、542-548、575-580）。
- **M05b 零泄漏**：全文无 `connectorId`/`externalId`/`sourceType=CONNECTOR`/`/knowledge/connectors`；
  `KnowledgeDocument.sourceType` 仍 `[UPLOAD, SEED]`；契约测试显式断言（KnowledgeContractM05Test:67-76）。

## 4. Schema 对账

| 项                               | 内容                                                                                                                                                                                                                               | 结论                                           |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `KnowledgeDocument`              | 新增 `visibility`/`departmentId`/`aclUserCount`/`parseEngine`/`warnings[]`/`parseSummary`/`sourceFileAccessLimited`/`retentionExpiresAt`/`retentionDays`/`retentionPolicy`；`additionalProperties:false`；required 仍是 M02 8 字段 | 与 ADR §7.4:744 一致；旧响应仍合法             |
| `KnowledgeDocumentCreateRequest` | 新增可选 `visibility`（default `TENANT`）/`departmentId`/`userIds`（maxItems 200, unique）；required `title,fileId`；`additionalProperties:false`                                                                                  | 一致                                           |
| ACL 模型                         | `KnowledgeDocumentAcl` required `visibility,users`；users 项 `userId/displayName` + `creator`；`creatorUserId` 可空；`KnowledgeDocumentAclUpdate` required `visibility`，整替语义                                                  | 与 §5.1/§5.2/§7.4 一致                         |
| `parseSummary`                   | `sheets[]`（name/cellRange/empty/indexed/indexedRange）、`hiddenSheetCount`、`ocr`（averageConfidence/lowConfidencePages/failedPages）                                                                                             | 与 §5.1/§4.3 一致；子 schema 必要              |
| Citation                         | `bbox`/`ocrConfidence`/`sourceStatus` 可选；`locatorType` 可空；`NO_ACCESS/DELETED` 空串+null 写明；`LocatorType` 枚举仍 `PAGE/SHEET/PARAGRAPH`                                                                                    | 与 §7.4 一致；未扩枚举                         |
| ArtifactCitation                 | 同上 + `cellRange`（Excel 区域）                                                                                                                                                                                                   | 与 AC-02 一致；`Citation` 原本已有 `cellRange` |

`KnowledgeParseSheetSummary.required=[name,cellRange,empty]` 允许 `cellRange:null`（空表），与 ADR
「空表可为 null、不伪造单元格」一致。`bbox` 为整数像素、`ocrConfidence` 0..1，与 V12 `DECIMAL(4,3)` 兼容。

## 5. 测试质量

- `KnowledgeContractM05Test` 10 个用例：`contractVersionAndScope`、8 个 operationId 各一
  （list/create/get/putAcl/getAcl/delete/download/reindex）、`citationSchemas`；全部只读
  `contracts/openapi.yaml`（SnakeYAML），无 Spring 上下文、无网络。
- 每个 operationId 断言：路径+方法存在（不存在会抛 `IllegalArgumentException`）、`x-riccon-permission`、
  CSRF/Idempotency 引用、响应 `$ref`、错误码描述、请求/响应 schema 字段与 required/上限。
  `contractVersionAndScope` 另断言 5 个 M05 操作无 `x-riccon-deferred`、根 deferred 无 `knowledge-acl-ocr`、
  无连接器路径/字段、M02/M03/M10/M04 代表路径仍在（:40-76）。
- **没有用 404/deferred 冒充**：download 断言 200 有二进制 content 与 4 个响应头、404 同时写明两种码、
  502 存在；reindex 断言 202→KnowledgeDocument、`force` 默认 false；delete 断言 204。
- `ModelContract108aTest`/`108bTest`：diff 只有版本断言 `1.3.0-m04→1.4.0-m05` 与注释，未删除或放宽
  任何 M04 断言；契约测试目录的回归面完整。
- `OpenApiContract.java` 只新增 helper（`response/resolvedResponse/parameter/oneOfReferences/referencesSchema`）
  并把 `responseSchemaName` 重载化，原行为不变；helper 在缺失组件时返回空映射/抛错，不会静默通过。
- `-Dtest='*Contract*Test'` 在 `apps/api` reactor 实际命中 4 类：Knowledge(10)、108a(1)、108b(1)、
  `UserSummaryDepartmentContractTest`(3)=15；worker 的 `M04MetricsContractTest` 不在 `-pl apps/api` reactor 内，
  不参与计数。与 builder 报告的 15 run/0 fail 一致。
- 未覆盖（本任务范围外）：运行期端点行为、AC 功能/E2E/a11y、M05b 连接器契约；由 B3+/V1/V2/M05b 覆盖。

## 6. 证据审计

| EV      | 产生者                                   | commit    | dirty | exit | 日志/校验                                                                                                      |
| ------- | ---------------------------------------- | --------- | ----- | ---- | -------------------------------------------------------------------------------------------------------------- |
| EV-0777 | builder-kb-contract                      | `e059162` | true  | 0    | 326 B，sha256 `674b698b…` 与 JSON 一致；因并行 TASK-0180 未提交而 dirty；已从 task.evidence 移除引用、文件保留 |
| EV-0779 | builder-kb-contract                      | `5118aac` | false | 0    | 326 B，sha256 `c8994db8…` 与 JSON 一致；note 写明 supersedes EV-0777；日志 stdout/stderr 为空（F-006）         |
| EV-0782 | builder-kb-core（TASK-0180 全量 verify） | `eb7285b` | false | 0    | 1.7 MB；surefire：Knowledge 10、108a 1、108b 1、UserSummary 3，全 0 fail/skip；用于交叉核对 15                 |
| EV-0784 | riccon/verifier（本评审首次 Phase 2）    | `2fdb20f` | true  | 0    | 测试本身通过，但并行 TASK-0181 在运行中写入未提交 web 文件导致 dirty；已从 task.evidence 移除引用              |
| EV-0785 | riccon/verifier（本评审 Phase 2）        | `8eefc20` | false | 0    | 380 B，sha256 `11082b43…` 与 JSON 一致；surefire 10/1/1，全 0 fail/skip                                        |

- EV-0779 的 commit 不是 `end_commit` 而是其子提交 `5118aac`（B1 收口），但 `git diff
e059162..5118aac -- contracts apps/api/src/test/java/com/riccon/api/contract` 为空，测试对象与
  `e059162` 完全一致；`requireAncestor` 成立。
- EV-0785 的 commit 为 `8eefc20`，同样为 `e059162` 的后代，契约与契约测试未变，判定有效。
- **EV-0784 说明**：首次 Phase 2 在 `2fdb20f` 干净起点执行，但 TASK-0181 的 `builder-web` 在
  23:23:04 将 `apps/web/src/pages/chat/knowledge-and-sharing.test.tsx` 改为未提交状态并在 23:23:55
  提交，`riccon evidence run` 在命令结束后取 dirty 快照，因此 EV-0784 记为 dirty=true（exit 0）。
  这是并行写入的取证污染，不是测试失败或环境 flake；重跑一次得到 EV-0785（dirty=false）。
  EV-0784 不作为 gate 证据，且已从 `TASK-0165.evidence` 移除引用（文件保留可审计）。
- EV-0777 的移除诚实：EV-0777 文件/日志/SHA 未被修改，task.evidence 只保留 EV-0779/EV-0785；
  EV-0779 的 note 明确 supersedes。唯一缺口是 EV-0777 自身没有 superseded 注记（F-006）。

## 7. AC 对账（任务级）

| #   | 验收原文                                                                                                               | 结论                      | 依据                                                                                   | 残余                                                                                                  |
| --- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------- | -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 1   | OpenAPI 1.4.0-m05：ACL GET/PUT、流式下载、reindex、delete、retention 字段、bbox/ocrConfidence/sourceStatus；不含连接器 | **pass**                  | 第 3/4 节；EV-0785；Redocly 0 error                                                    | F-003（下载头部/502 精度）                                                                            |
| 2   | 每个 operationId 契约测试；端点真实实现，不以 404/deferred 冒充                                                        | **pass（with residual）** | 10 用例逐 operationId；5 操作无 deferred、响应为 200/202/204；`git diff` 无 controller | **运行期实现属 TASK-0166（B3），本任务不能证明“端点可用”**；F-005。AC-22 与模块 gate 必须复验真实实现 |

任务级评审不把第 2 条判为无残余 pass：契约层“非 404/deferred 占位”成立，但“端点真实实现”在
`e059162` 尚不存在（`KnowledgeController` 只有 list/create/get），必须由 B3 与 AC-22 关闭。

## 8. Builder 报告的 7 条 ADR 偏差裁决

| #   | 偏差                               | 裁决             | 理由                                                                                                              |
| --- | ---------------------------------- | ---------------- | ----------------------------------------------------------------------------------------------------------------- |
| 1   | `ArtifactCitation.cellRange`       | **接受（必要）** | AC-02 要求 J2 产物引用显示 Excel 区域；`Citation` 已有 `cellRange`，Artifact 缺失会导致 J2 无区域；可选、additive |
| 2   | `KnowledgeAclUser.creator`         | **接受（必要）** | ADR §5.2 明确“ACL 列表接口返回 creator 标记，不允许移除”；§7.4 只列 userId/displayName 是简写                     |
| 3   | `parseSummary` 子 schema           | **接受（必要）** | ADR §5.1/§4.3 要求工作表清单/范围/空表/已索引范围与 OCR 置信度；无子 schema 无法表达                              |
| 4   | `locatorType` 可空                 | **接受（必要）** | §7.4 要求 `NO_ACCESS/DELETED` 时定位为 null；不改枚举、不删 required，仅从“不可空”放宽                            |
| 5   | 知识库专用错误组件                 | **接受（必要）** | 通用 Forbidden/NotFound 不含 `RICCON-KB-*` 码；新组件被实际引用（Redocly 无 unused 新告警）；描述精度见 F-002     |
| 6   | DELETE 权限 `knowledge.acl.manage` | **接受（必要）** | §7.4:729 明确“创建人或 knowledge.acl.manage”；旧的 `knowledge.manage` 是 M02 占位                                 |
| 7   | reindex 400                        | **接受（必要）** | §7.4:753 的 M05 错误码含 `RICCON-KB-400`；`force` 绑定失败/非法状态属 400                                         |

## 9. Phase 2 独立验证（已执行）

Lead 确认测试窗口后，在**主工作树**执行（不新建 worktree、不修改生产/测试/契约）：

```bash
./mvnw -q -T1C -pl apps/api -am test \
  -Dtest='KnowledgeContractM05Test,ModelContract108aTest,ModelContract108bTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- **EV-0785**：commit `8eefc20`、dirty=false、exit 0、21.3 s；surefire
  Knowledge 10 / 108a 1 / 108b 1，0 failure/0 error/0 skip。
- 首次 EV-0784 因并行 TASK-0181 未提交文件被记为 dirty=true（测试仍 exit 0）；按“环境并发污染”
  重跑一次得 EV-0785，非测试重试、非掩盖 flake；两次日志均保留。
- **Redocly**：`npx --yes @redocly/cli@1.34.3 lint contracts/openapi.yaml` → valid，0 error / 3 warning；
  对 `67af216` 的 M04 契约同命令同为 3 warning，确认无新增。
- 未运行：模块全量 build/coverage/E2E、运行期端点测试、M05b 契约、UI/a11y/性能/迁移——均不在
  B2 任务级范围，留给 B3+/V1/V2/模块 gate。

## 10. 未验证 / 没有做什么

- **未验证运行期 ACL/下载/delete/reindex**：实现属 TASK-0166；本任务只证明契约声明与测试。
- **未验证 `RICCON-KB-*` 常量/运行时错误码**：`ErrorCodes.java` 尚无 KB 码，B3 才落地。
- **未做 UI/E2E/a11y/浏览器**：B2 无 UI/端点。
- **未跑 M05 模块 gate**：M05 仍 `building`，B3–B11/V1/V2 未完成，gate 不应通过；本任务评审
  不替代模块评审、Product 预验收或用户验收。

## 11. 跨任务观察（不计入本任务 finding）

- `docs/ARCHITECTURE.md:214`（M03 章节）仍写“知识库端点不变；界面固定明示‘全租户共享，文档级权限在
  后续版本’”，与 M05 已落地的 ACL 语义不一致。该文件属 TASK-0179/architect 写范围，不在 B2
  write_scope；建议 M05 gate 前由 Architect 清理，避免 AC-22 的“不再出现过期声明”只覆盖契约/前端。
- `contracts/README.md:108` 的“已交付”措辞见 F-004。
- TASK-0165 的 AC 第 2 条见 F-005。

## 12. 剩余风险与下一步

1. **B3（TASK-0166）开工前**：Lead/Architect 定稿 F-001 的资源判定顺序（ACL 先于角色），并让
   `SEC-KB-002`/`IT-KB-106/107/119` 绑定；否则 AC-06/09 的 404 子句无法同时满足。
2. **B3 下载实现前**：收敛 F-003（502 码、media type、`filename*`），避免实现与契约漂移。
3. **M05 gate 前**：修正 F-002（错误组件描述）、F-004（README“已交付”）、F-005（AC 措辞）、
   F-006（EV 注记/汇总），并清理 §11 的 ARCHITECTURE 过期文案；F-001 必须在 gate 前关闭或重新评估。
4. **本任务评审不替代模块 gate**：M05 仍有 B3–B11/O1a/O1b/V1/V2 与 Product 预验收；本次 pass
   只表示 B2 契约交付通过技术验收。

---

**评审结论：Phase 1 静态审查 + Phase 2 独立定向测试完成，verdict=pass（任务级）。未发现 P0/P1；
EV-0785 与 Redocly 均通过；契约与 ADR §7.4 一致、M05b 零泄漏、测试逐 operationId 且无 deferred/404 占位；
P2/P3 accepted，F-001 必须在 B3 开工前定口径。**
