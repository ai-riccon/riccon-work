---
review_id: REV-TASK-0143-001
module_id: M20
task_id: TASK-0143
reviewer:
  agent: riccon/verifier
  session: ses_dfe8626c9ffesOuYvPbv4Y0ErW
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 3541007dadb3bbc802cd6f611006de02b8b79c5f
  - 35968c85edb039e2286fcea6fc69db161ee079f2
  - 1e6f655f47402d404051526d65be629b1a4d187f
  - b672f2c104287169352153eb9018e65226ceceb7
head_commit: b672f2c104287169352153eb9018e65226ceceb7
evidence:
  - EV-0749
reviewed_evidence:
  - EV-0748
  - EV-0749
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/builder-cost（TASK-0141）+ riccon/lead（补任务验收）"
    due: "TASK-0141 / M20 gate 前"
    title: "空白 tenantId 在契约中声明 400，当前实现仍归一为未提供并返回全租户"
    summary: >-
      contracts/openapi.yaml:3313-3317 与 ADR-0022 §2.4:239 已声明 tenantId 空串/纯空白
      → 400 RICCON-COST-400；ADR 同时诚实记录当前 PlatformCostService.normalizeTenantFilter
      仍把空白归一为“未提供”（PlatformCostService.java:122-126），实际返回 200 全租户聚合。
      ADR 把实现修正与边界测试指派给 TASK-0141，但 .riccon/tasks/TASK-0141.json:15-19 的验收
      仍只有真实 3s 保护与平台五项计数，没有空白 tenantId 条目；若 Lead 不在派单时补验收，
      M20 gate 可能在没有实现/测试的情况下关闭该残余。影响有界：仅平台主体可进入该端点；
      空白值等价于未提供过滤，平台主体本就有全租户读权限，无越权或跨租户泄露。
    accepted_reason: >-
      当前无安全影响，契约目标与 ADR 记录一致且透明；任务 AC-3 允许“有测试或明确错误码”，
      TASK-0143 已给出明确错误码；实现与测试由 TASK-0141 承接。风险是契约对外承诺 400 而
      实现返回 200，需在 M20 gate 前补 TASK-0141 验收并落地，否则不得关闭 F-010。
    evidence: >-
      contracts/openapi.yaml:3313-3317；docs/adr/ADR-0022-m04-quota-and-cost.md:239；
      apps/api/src/main/java/com/riccon/api/admin/cost/service/PlatformCostService.java:122-131；
      .riccon/tasks/TASK-0141.json:15-19。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "M20 gate 前"
    title: "EV-0749 的合同校验是项目合同校验，Contract108bTest 未断言 CostPage/错误码变更"
    summary: >-
      EV-0749 的 `riccon contract validate --strict` 只校验 AGENTS.md Project Contract
      （日志 “Project contract: OK”），不解析 contracts/openapi.yaml；
      ModelContract108bTest（apps/api/src/test/java/com/riccon/api/contract/ModelContract108bTest.java:128-163）
      只断言三个成本端点的权限与响应 schema，没有断言 CostPage.maximum=200、三处引用、
      tenantId 空串 400 或 userId 非 UUID 400。当前变更由本次评审逐行读 diff 验证正确，
      但 AC-1/AC-4 的关键行为没有自动化回归保护；未来 OpenAPI 漂移不会被该 EV 捕获。
    accepted_reason: >-
      变更本身正确（CostPage maximum=200、三端点引用、全局 Page 未改），本次评审已独立核对；
      Contract108bTest 能验证 YAML 可解析且其他契约断言未回归，风险限于“变更无专门断言”
      的证据强度问题。建议在任务允许的 apps/api/src/test/java/com/riccon/api/cost/** 内补
      一条读取 contracts/openapi.yaml 的断言，或由 Lead 在 M20 gate 接受该残余。
    evidence: >-
      .riccon/evidence/TASK-0143/EV-0749-2026-11-22T12-16-18-468+08-00-node-users-ricky-config-opencode-riccon-scripts-.log:1-9、127-133；
      apps/api/src/test/java/com/riccon/api/contract/ModelContract108bTest.java:128-163；
      contracts/openapi.yaml:3373-3383。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/product"
    due: "M20 gate 前"
    title: "AC-12 口径收口后仍有“统一 404”摘要与 pass 状态标签残余"
    summary: >-
      PRODUCT/ACCEPTANCE 的 M04-AC-12 主行已一致表达 403/404（docs/PRODUCT.md:356、
      docs/ACCEPTANCE.md:307），但同文件仍有两处摘要写“统一 404”：
      docs/PRODUCT.md:448（M04-AC-12 优先级说明）与 docs/adr/ADR-0022-m04-quota-and-cost.md:27
      （ADR Scope 摘要）；ACCEPTANCE:307 仍标 `pass` 而非 `pass（with residual）`，
      而 X-Tenant-Id/平台成本过滤负例由 TASK-0142 承接（REV-TASK-0108-002 F-006 accepted）。
      note 已披露该 residual，故不影响 AC 结论；但文档内部不一致，且不符合
      docs/COLLABORATION.md:61 对 accepted finding 的 “pass (with residual)” 要求。
    accepted_reason: >-
      AC 主行与 ADR §2.4 是权威口径且已一致；残余只在摘要/状态标签，不改变用户可见行为，
      也不降低 M04 已验收 AC 的安全结果。建议 M20 gate 前统一措辞并把该行标为
      `pass（with residual F-006/TASK-0142）`；不影响本任务契约修正的通过。
    evidence: >-
      docs/PRODUCT.md:356、448；docs/ACCEPTANCE.md:307；docs/adr/ADR-0022-m04-quota-and-cost.md:27；
      docs/COLLABORATION.md:61；.riccon/reviews/tasks/TASK-0108-review-002.md:123-141。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-core（TASK-0142）+ riccon/architect"
    due: "TASK-0142 / M20 gate 前"
    title: "平台成本例外只按 tenantId 查询参数描述，未说明 X-Tenant-Id 头同样被过滤器放行"
    summary: >-
      ADR-0022 §2.4:234-238 把平台成本例外描述为查询参数 tenantId 的过滤；
      但 TenantContextFilter.isPlatformCostFilter（TenantContextFilter.java:139-143）对
      GET /api/v1/admin/costs + platform 主体同时放行 X-Tenant-Id 头（firstPresent 取头/参数，
      :112-119），因此该路径上的头伪造不会按 ADR-0009 §1 返回 403。头值不会被 controller
      当作过滤参数，不能扩大平台主体的数据范围，当前无提权/泄露；ADR 已明确把
      “过滤器内角色校验与 X-Tenant-Id 负向用例”归 TASK-0142，但例外描述本身可更精确。
    accepted_reason: >-
      无当前越权：平台主体本就有全租户成本读权限，头值被忽略且不改变过滤结果；该偏差
      已由 TASK-0142 承接（含 ADR-0009 例外修订与负向测试）。建议 TASK-0142 在 ADR-0022
      例外段落明确“该路径的 X-Tenant-Id 也被忽略/不参与过滤”，并补负例。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:112-119、139-143；
      docs/adr/ADR-0009-tenant-isolation-enforcement.md:17；
      docs/adr/ADR-0022-m04-quota-and-cost.md:234-238；
      .riccon/tasks/TASK-0142.json:15-19。
notes: "任务级独立评审；F-007/F-008 已 fixed，F-010 契约边界已 fixed，实现残余由 TASK-0141 承接。不参与 M20 模块 gate。"
created_at: "2026-11-22T12:24:34+08:00"
---

# REV-TASK-0143-001：TASK-0143 成本契约与 AC-12 口径独立评审

## 结论

**pass（任务级）**。`contracts/openapi.yaml` 已为三个成本端点引入专用 `CostPage`（`minimum: 1 / maximum: 200 / default: 1`），全局 `Page` 保持无上限，三处引用精确；`docs/PRODUCT.md` 与 `docs/ACCEPTANCE.md` 的 M04-AC-12 已一致表达“伪造 tenantId/X-Tenant-Id → 403 `RICCON-AUTH-403` + 拒绝审计；资源 ID（userId）跨租户 → 404 `RICCON-TENANT-404`”，并保留原有“平台只到租户聚合、无用户明细、员工无入口/403”结果。未发现 P0/P1。

存在 1 条 P2 与 3 条 P3，均已在 front matter 标为 `accepted`（owner/due/accepted_reason 齐全）：空白 `tenantId` 的契约 400 与当前实现不一致（TASK-0141 承接，但其验收卡尚未包含）；EV-0749 的 `contract validate --strict` 是项目合同校验而非 OpenAPI 校验，`ModelContract108bTest` 未断言本次新增参数。任务本身可关闭，残余须在 M20 gate 前由对应 owner 处理。

## 评审对象与范围

- 评审范围：基線 `4930443` → `end_commit=1e6f655`，工作树 HEAD `b672f2c`（仅证据/状态提交）。
- 实际提交链：`3541007`（docs/contract）、`35968c8`（EV-0748 + task review 状态）、`1e6f655`（ADR 修订范围精确化）、`b672f2c`（EV-0749 + end_commit）。`35968c8` 未在派单摘要中单列，但属于同一任务且只改 `.riccon/` 证据与状态。
- `git diff --name-status 4930443..1e6f655` 共 9 个文件：3 个文档、1 个 OpenAPI、EV-0748 日志/JSON、M20/state/TASK-0143。全部在 `write_scope`（`contracts/openapi.yaml`、`docs/{PRODUCT,ACCEPTANCE}.md`、`docs/adr/ADR-0022...`、`.riccon/**`）；`apps/api/src/test/java/com/riccon/api/cost/**` 未被使用，允许但不强制。
- **未改**生产源码、测试、迁移、构建配置、`docs/adr/ADR-0009-tenant-isolation-enforcement.md`、历史 review 或历史 evidence。`b672f2c` 仅新增 EV-0749 日志/JSON，并同步 TASK-0143/state（模块 M20 未在最终提交再改，已在 `3541007` 置 `building`）。
- 无越界文件；无密钥/敏感值新增。

## 证据核查（EV-0749）

| 项              | 观察                                                                                                                                                                                    |
| --------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-0749 JSON    | `exit_code=0`、`manual=false`、`dirty=false`、`commit=1e6f655`、`task_id=TASK-0143`、`agent=riccon/architect`                                                                           |
| 日志头命令      | 与 JSON `command` 完全一致：`node ... contract validate --strict --project . && ./mvnw -T1C -pl apps/api -am test -Dtest=ModelContract108bTest -Dsurefire.failIfNoSpecifiedTests=false` |
| 日志内容        | `Project contract: OK (0 errors, 0 warnings)`；`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`                                                                      |
| 哈希            | 日志 SHA-256 `d99e8a1e...dbaf00` 与 JSON `output_sha256` 一致；`output_bytes=9252`                                                                                                      |
| EV-0748         | 同命令、`exit_code=0`、`manual=false`、`dirty=false`、`commit=3541007`；EV-0749 note 写明“取代 EV-0748 的提交绑定”；任务卡同时保留 EV-0748/EV-0749，最终 `end_commit` 指向 `1e6f655`    |
| gate 内证据校验 | `riccon gate check --module M20` 对 EV-0748/EV-0749 均报 “output matches its hash and belongs to TASK-0143”                                                                             |

未把 builder 的总结当证据：以上均直接读取 JSON、日志哈希、日志头与日志正文。

## 契约核查

- `components.parameters.CostPage` 位于 `contracts/openapi.yaml:3373-3383`：`minimum: 1`、`maximum: 200`、`default: 1`，与 `CostPage.of`（`MAX_PAGE=200`、空值默认 1）一致。
- `/api/v1/costs`（:3238）、`/api/v1/costs/invocations`（:3279）、`/api/v1/admin/costs`（:3323）恰好 3 处引用 `CostPage`；无其他端点引用；`PageSize` 仍为 `1..100`（`default: 20`），与实现一致。
- 全局 `parameters.Page` 未被修改（仍只有 `minimum: 1`、`default: 1`，无 `maximum`），27 处非成本引用保持原样；ADR-0022 §2.4:242 与 §2.5:252 均记录该契约落点。
- 独立重跑 `riccon contract validate --strict --project .`：`Project contract: OK (0 errors, 0 warnings)`，exit 0。
- **注意**：该命令校验的是 `AGENTS.md` Project Contract，不是 `contracts/openapi.yaml`；OpenAPI 的实际解析由 `ModelContract108bTest` 完成。该测试通过（见下），但未断言 `CostPage` 的 `maximum` 或三处引用（F-002）。

## AC-12 口径核查

- `docs/PRODUCT.md:356` 与 `docs/ACCEPTANCE.md:307` 主行一致：
  - 伪造 `tenantId` 查询参数或 `X-Tenant-Id` 请求头（与主体租户不一致）→ 403 `RICCON-AUTH-403`，写拒绝审计，不返回租户数据（ADR-0009）。
  - 资源 ID（如 `userId`）指向其他租户/不存在 → 统一 404 `RICCON-TENANT-404`，与“不存在”不可区分，不泄露存在性。
  - 保留原结果：租户 A 管理员只看本租户用户/模型/日金额与明细；平台只到租户聚合、无用户列/明细；员工无入口或 403、响应不含金额。
- 与现有证据对齐：
  - EV-0640（`TASK-0116`，commit `d0ce847`，exit 0）包含 `CostAdminApiTest` 与 `M04V2VerifierBoundaryTest`；`CostAdminApiTest` 对 `/api/v1/costs?tenantId=other` 断言 403 `RICCON-AUTH-403`，对 `/api/v1/costs/invocations?userId=他租户` 断言 404 `RICCON-TENANT-404`（`CostAdminApiTest.java:160-164、190-192`）。
  - EV-0676（`pnpm -w e2e`，commit `4db8c33`，exit 0）与 EV-0689（真实栈，commit `a8d2bbc`，exit 0）覆盖 E2E-M04-06 的平台/租户成本视图、员工无入口/403；本次未重跑，仅核对元数据与 spec 源码。
- 残余：AC 的 `X-Tenant-Id` 成本端点负例未在 `IT-COST-104` 中直接断言；共享过滤器 `TenantContextFilter` 的头伪造 403 + 审计有 `TEN-002`（`TenantIsolationTest` / `TenantIsolationSecurityTest`）覆盖，成本专用负例由 TASK-0142 承接，ACCEPTANCE note 已披露。该 residual 不改变 AC 结果，但状态标签未按 `pass (with residual)` 书写（F-003）。

## F-010 边界与安全语义

- **契约边界**：`userId`/`modelId` 非 UUID → 400 `RICCON-REQUEST-400`；`tenantId` 空串/纯空白/超 64 → 400 `RICCON-COST-400`；`modelId` 未命中返回空结果而非 404。契约与 ADR-0022 §2.4:239、§3.3 错误码表一致。
- **实现核对**：
  - `@RequestParam UUID userId/modelId` → Spring `MethodArgumentTypeMismatchException` → `GlobalExceptionHandler.handleBadParameter` → `RICCON-REQUEST-400`（`GlobalExceptionHandler.java:101-112`）。
  - `PlatformCostService.normalizeTenantFilter` 对超 64 抛 `CostInvalidRequestException` → `RICCON-COST-400`（`PlatformCostService.java:127-129`）。
  - **空白 `tenantId`**：`isBlank()` → `null`，随后返回全租户（`PlatformCostService.java:122-126`），与契约“400”不一致；ADR-0022:239 已诚实记录“当前仍归一为未提供，修正归 TASK-0141”，没有写成已实现。TASK-0141 验收卡未列该条（F-001）。
- **安全语义**：ADR-0022 §2.4:234 准确记录平台成本 `tenantId` 是 ADR-0009 的记录例外：`isPlatformCostFilter` 只放行 `GET + /api/v1/admin/costs + 主体 platform`，其他路径/方法/主体仍按 ADR-0009；权限由 `@RequirePermission(PLATFORM_COST_READ)` 在 controller 强制（`PlatformCostController.java:47`）。角色校验与 `X-Tenant-Id` 负例明确归 TASK-0142；ADR-0009 本身未改（符合任务边界）。`modelId` 全局模型目录、未命中空结果的例外与 `model` 表（无 `tenant_id`）及 `CostQueryService` 实现一致。

## 独立命令结果

| 命令                                                                                                     | 结果                                                                                                            |
| -------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `node "$HOME/.config/opencode/riccon/scripts/riccon.mjs" contract validate --strict --project .`         | `Project contract: OK (0 errors, 0 warnings)`；exit 0                                                           |
| `./mvnw -T1C -pl apps/api -am test -Dtest=ModelContract108bTest -Dsurefire.failIfNoSpecifiedTests=false` | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；exit 0（独立运行，日志在临时目录，未提交） |
| `git diff --name-status 4930443..1e6f655`                                                                | 9 文件，全部在 `write_scope`                                                                                    |
| `git show --name-status b672f2c`                                                                         | EV-0749 日志/JSON + `.riccon/state.json` + `.riccon/tasks/TASK-0143.json`                                       |
| `grep -c 'parameters/CostPage' contracts/openapi.yaml`                                                   | 3（另有 1 个 `CostPage` 定义）                                                                                  |
| `riccon gate check --module M20`                                                                         | exit 1：TASK-0140/0141/0142 `planned`、TASK-0143 `review`、无 M20 模块 review；预期失败，见下                   |

## Findings 摘要

- **F-001（P2 accepted）**：空白 `tenantId` 契约 400 vs 实现 200 全租户；ADR 已诚实记录，TASK-0141 承接，但 TASK-0141 验收未列。
- **F-002（P3 accepted）**：EV-0749 的 `contract validate` 是项目合同校验；`ModelContract108bTest` 未断言 CostPage/错误码变更，缺少变更级回归断言。
- **F-003（P3 accepted）**：PRODUCT/ADR 摘要仍写“统一 404”，ACCEPTANCE:307 仍标 `pass` 而非 `pass（with residual）`；note 已披露，不改变 AC 结果。
- **F-004（P3 accepted）**：平台成本例外描述只覆盖 `tenantId` 查询参数，未说明 `X-Tenant-Id` 头在该路径同样被过滤器放行；无当前提权，TASK-0142 承接。

## 未验证

- 未重跑 EV-0640（TEN-033/IT-COST-104）与 EV-0676/0689（E2E-M04-06）；只读取证据元数据、测试源码与 E2E spec，未在本次会话重放 M04 的集成/E2E 栈。
- 未跑 M20 模块全量 build/lint/typecheck/test/coverage/E2E/a11y/security/perf；TASK-0143 是文档/契约任务，按任务级节奏只跑定向契约测试。M20 gate 仍被 TASK-0140/0141/0142 阻塞。
- 未对真实 API 发起空白 `tenantId` 请求；空白归一为未提供的结论来自 `PlatformCostService` 代码阅读，未做运行期复现。
- 未验证 TASK-0141/TASK-0142 的未来实现与测试；F-001/F-004 的关闭依赖它们。
- 未使用外部 OpenAPI linter 校验 `contracts/openapi.yaml`；只做了手工 diff、YAML 解析测试与 `Contract108bTest`。

## 剩余风险

- **空白 `tenantId`**：在 TASK-0141 落地前，OpenAPI 对外声明 400，服务端实际返回 200 全租户；无越权（平台主体本有全租户权限），但生成客户端/集成测试可能按 400 预期失败。TASK-0141 验收卡需补充该条，否则 F-010 可能在 M20 gate 时被误关。
- **契约回归**：`CostPage.maximum` 与三处引用目前没有断言；后续 OpenAPI 编辑可能漂移而 EV 仍绿。
- **文档一致性**：`PRODUCT.md:448`、ADR-0022:27 的“统一 404”摘要与 AC 主行/§2.4 不一致；`ACCEPTANCE:307` 的 `pass` 标签未体现 TASK-0142 residual。
- **平台头例外**：`GET /api/v1/admin/costs` 上 `X-Tenant-Id` 不触发 403；当前头值不参与过滤、无提权，但 ADR-0009 例外条款与负例未闭环（TASK-0142）。
- **M04 用户验收时序**：M04-AC-12 已于 DEC-0027 用户验收；本次只澄清文案、不改变行为与结果。建议 Lead/Product 在 M20 预验收时确认该文案澄清不构成 AC 结果变更。
- **未验证的 E2E 证据**：本次未重放 EV-0676/0689；若 M20 模块 gate 需要最新提交上的 E2E，应在最终提交重跑并绑定新 EV，不能复用 M04 旧 EV 代替。

## 独立性声明

本评审使用独立会话 `ses_dfe8626c9ffesOuYvPbv4Y0ErW`，直接读取任务/模块卡、提交 diff、OpenAPI、ADR-0009/0022、PRODUCT/ACCEPTANCE、测试源码与 EV-0748/0749 JSON/日志；独立重跑了 `contract validate --strict` 与 `ModelContract108bTest`。未采信 builder 总结，未修改生产源码、测试、迁移、合同、ADR 或历史评审/证据，未提交评审之外的任何文件。

## Gate

`riccon gate check --module M20` 当前 **FAIL**（exit 1），原因：TASK-0140/0141/0142 仍为 `planned`、TASK-0143 为 `review`、尚无 M20 模块 review；另有 2 个 warning（EV-0571 为 manual；3/4 任务缺 baseline/end_commit 校验）。这是 M20 尚未完成其他任务的预期状态，不构成本任务评审的失败。任务评审不参与模块 gate；M20 模块评审须在 TASK-0140..0142 完成、TASK-0143 done 后，由独立 Verifier 在全量合同命令上绑定 `dod_evidence`。
