---
review_id: REV-TASK-0252-001
module_id: M06
task_id: TASK-0252
reviewer:
  agent: riccon/verifier
  session: ses_dea194441ffeZEH08GBBPhhHU3
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - a9608afebd9318b2d0bbd2cad04c4f1c13849dab
  - 9c59e79a5febf1ef82356c46079b7d110a254039
head_commit: 9c59e79a5febf1ef82356c46079b7d110a254039
code_commit: a9608afebd9318b2d0bbd2cad04c4f1c13849dab
evidence_commit: 9c59e79a5febf1ef82356c46079b7d110a254039
baseline_commit: e26fee8fe97abca9dd78f35e37c4f91cd20cc759
task_card_baseline_commit: e26fee8f
task_card_end_commit: a9608afe
review_scope: >-
  M06 TASK-0252（U-05 / DEC-0050=B）独立任务评审：核对 API/Worker 的
  `workflow.approval.decided` 审计 details 只写 `commentSha256`（64 位小写 hex、
  `strip()` + UTF-8 稳定）；审批记录 `workflow_approval.decision_comment` 仍保留 ≤500 原文、
  审批详情 assignee 可读；201–500 接受、501 拒绝；`AuditDetailsPolicy` 的 `validate`（读兼容旧
  `decisionComment`）/`validateWrite`（拒历史明文键）读写分离；Worker 审计策略、双份 hash 固定向量
  与审计失败 fail-closed；历史行真实 DB 插入后的读兼容。复核 EV-1163 的 commit/dirty/output_sha256/
  bytes 与 API 44 + Worker 52 + spotless 结果；在 `verifier-t0252` namespace 独立复跑同一组定向测试与
  spotless。检查 8 个 write_scope 与“不改 V15/schema/contracts/PRODUCT/AGENTS/T16a/T17”。不评审
  UI/E2E/axe、perf、Trivy/semgrep、迁移、模块 gate、Product 预验收或用户最终验收；不运行
  `riccon gate check`。本次 reviewer 复跑只作独立验证，不写成任务 EV；任务证据仍为 EV-1163。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  review: true
  evidence: true
  scope: true
  security: true
dod_not_applicable:
  - coverage
  - e2e
  - performance
  - accessibility
  - migration
  - docs
dod_note: >-
  结论按 **pass（带 P3 residual）** 解释：AC1（hash-only、审批记录保留理由、201–500/501、
  查询 hash、历史读兼容）和 AC2（API/Worker 双端测试、T16a `WorkflowAuditM06Test` 同步、
  不改 schema/契约/PRODUCT/AGENTS、clean EV、独立 Verifier）均满足。build 指受影响模块由
  Maven 定向测试完成编译并通过；tests 指 EV-1163 的 44 API + 52 Worker 与本次独立复跑；
  lint 指 `./mvnw -q spotless:check` exit 0；security 指变更面代码审查（hash-only、明文拒绝、
  读写守卫分离、审计失败不吞异常、无新端点/数据），未运行 Trivy/semgrep。coverage/e2e/
  performance/accessibility/migration 属模块 gate、T17 或本任务未改 UI 的不适用项；docs 指项目
  文档（ADR/PRODUCT/ARCHITECTURE/ACCEPTANCE），本任务验收明确不改，但仍有 U-05 文档口径滞后的
  剩余风险（见正文）。本结论不是 Product 预验收或用户最终验收。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/lead（安全口径跟踪）"
    due: "M06 gate 前（把 residual 写入 ADR-0024 §8.3 / THREAT-MODEL）"
    title: "无盐 SHA-256 只提高猜测成本，不能抵抗低熵审批理由的字典/暴力确认"
    summary: |-
      审计 details 只保存 `commentSha256`，不保存明文，满足 DEC-0050=B 的最小化要求；但
      `WorkflowApprovalCommentHash.sha256`（`apps/api/.../WorkflowApprovalCommentHash.java:29-42`；
      Worker 同名文件）是无盐 SHA-256/UTF-8。审批理由是用户自由文本，可能只有几个字（如“同意”），
      持有审计导出的人在可枚举候选集内可直接确认原文；哈希的可核对性依赖“猜测成本”而非密码学
      不可逆性。审计保留 180 天且可导出，DEC-0050=B 已明确选择该取舍；本 review 不把该 residual
      当成已消除。建议在 ADR §8.3/威胁模型记录“无盐 hash 的确认风险”和审计导出审批控制。
    accepted_reason: |-
      DEC-0050=B 已由用户明确选择“审计只存 commentSha256、理由保留在审批记录”，Product 推荐理由
      是最小化 PII；无盐哈希的字典确认风险是该选择的已知 residual，不影响本次“不写明文”的验收
      目标。审批记录仍受 assignee/权限约束，审计导出需审批；风险有界且已由 owner 跟踪。本 finding
      只要求文档化 residual，不要求本任务改算法（改盐/密钥哈希会改变 DEC 与可核对性语义）。
    evidence: |-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalCommentHash.java:29-42；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalCommentHash.java:29-42；
      .riccon/decisions/DEC-0050.md（answer=B）；docs/adr/ADR-0024-m06-workflow-orchestration.md:619-621。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/builder-b1"
    due: "M06b/M09 前（或下次修改任一侧时抽到 platform 共享工具）"
    title: "API/Worker 各自维护一份完全相同的 WorkflowApprovalCommentHash"
    summary: |-
      `apps/api/.../WorkflowApprovalCommentHash.java` 与
      `apps/worker/.../WorkflowApprovalCommentHash.java` 内容逐字相同（`strip()`、UTF-8、
      SHA-256、`HexFormat.of()`、空/空白返回 null）。两侧测试用同一固定向量
      `905819e2e3a059a02087855e9f326d05c877c1767ec31dc012c5c44652840230`（UTF-8 “同意”）锁定，
      本次复跑两侧均通过；但未来只改一侧（例如加盐、换 NFC）会造成跨部署物审计哈希漂移，且
      没有编译期共享约束。当前没有行为差异，风险是维护性/可核对性。
    accepted_reason: |-
      两侧实现和测试向量一致，本次 diff 未引入漂移；API 与 Worker 分属不同部署物，抽到 platform
      需要跨模块写范围/构建调整，超出本任务 scope。固定向量测试已在两侧同时锁定算法，漂移会在
      测试中立即暴露；作为有界维护性 residual 接受，在 M06b/M09 或下次修改任一侧时收敛。
    evidence: |-
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalCommentHash.java:1-43；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalCommentHash.java:1-43；
      apps/api/src/test/java/com/riccon/api/workflow/approval/WorkflowApprovalCommentHashTest.java:18-23；
      apps/worker/src/test/java/com/riccon/worker/workflow/approval/WorkflowApprovalCommentHashTest.java:18-23。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/builder-b1"
    due: "M06b/M07 前（新增 workflow 审计写入方时）"
    title: "写入守卫 validateWrite 依赖调用方显式调用，AuditEvent.create 仍接受历史明文键"
    summary: |-
      `AuditDetailsPolicy.validateWrite`（`apps/api/.../AuditDetailsPolicy.java:580-591`）是新增的
      写侧守卫，但通用入口 `AuditEvent.create` 仍只调用读兼容的
      `AuditDetailsPolicy.validate`（`apps/api/.../AuditEvent.java:108`），而 `validate` 为历史行
      保留了 `workflow.approval.decided.decisionComment`（`:329-345`）。当前唯一的 API
      写入方 `WorkflowApprovalAuditEvents.decided` 已显式调用 `validateWrite`（`:69`），Worker
      侧策略则直接不允许该键；因此今天没有明文写入路径。风险是未来新增写入方若只走
      `AuditEvent.create`，可在不触发守卫的情况下写回明文。
    accepted_reason: |-
      DEC-0050=B 要求“读写守卫分离”，当前实现与该语义一致：读端兼容历史行，写端在唯一工厂
      显式拒绝；API/Worker 现有写入路径均有测试锁定。把 `validateWrite` 提升进 `AuditEvent.create`
      或增加动作级写策略需要改共享审计入口，超出本任务 scope；作为有界防御纵深 residual 接受，
      新增 workflow 审计写入方时必须复用 `validateWrite` 并补测试。
    evidence: |-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:329-345,580-591；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditEvent.java:108；
      apps/api/src/main/java/com/riccon/api/workflow/approval/service/WorkflowApprovalAuditEvents.java:63-69；
      apps/api/src/test/java/com/riccon/api/workflow/approval/WorkflowApprovalAuditCommentPolicyTest.java:73-84。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-b1 + riccon/architect（文档复核）"
    due: "M06 gate 前 / T17 文档清理"
    title: "Worker WorkflowApprovalDecisionService 类注释仍写 U-05 临时默认完整理由入审计"
    summary: |-
      `apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalDecisionService.java:54-55`
      仍写“决定理由的审计口径（U-05）仍是临时默认：完整理由进入审计，长度 1–500”。该文件在本次
      write_scope 内，但其类注释未随 `WorkflowApprovalAuditor`/`WorkflowApprovalAuditPolicy` 一起
      更新；它与 DEC-0050=B 及实际行为（只写 `commentSha256`）相反，可能让后续评审误判安全口径。
      行为、测试和审计 details 均已是 hash-only，故这是生产注释陈旧，不是行为/安全边界缺陷。
    accepted_reason: |-
      同任务的 auditor/policy 和 API 事件工厂 Javadoc 已更新，代码与测试也强制 hash-only；仅决定
      服务类注释滞后，不影响运行、审计或测试。按 P3 接受，要求在 M06 gate/T17 文档清理时改为
      “只写 commentSha256、理由保留在审批记录”，避免把陈旧口径带进模块评审；只改注释不需要重跑
      行为全量（若同时改行为则必须重跑）。
    evidence: |-
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalDecisionService.java:54-55；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalAuditor.java:159-186；
      apps/worker/src/main/java/com/riccon/worker/workflow/approval/domain/WorkflowApprovalAuditPolicy.java:11-18。
reviewed_evidence:
  - EV-1163
evidence:
  - EV-1163
created_at: "2026-11-26T11:42:00+08:00"
---

# REV-TASK-0252-001：M06 TASK-0252 审批审计只存 `commentSha256` 独立评审

## 结论

**pass（带 P3 residual）**。P0/P1 open = 0，新增 P2 = 0；4 个 P3 accepted（无盐哈希确认风险、双份 hash 实现、写入守卫复用、Worker 决定服务类注释陈旧），均已给 owner/due/accepted_reason。TASK-0252 的代码行为与 DEC-0050=B 一致：API/Worker 新写入的 `workflow.approval.decided` 审计 details 只有 `commentSha256`，不写明文 `decisionComment`；审批记录仍保留 ≤500 原文且 assignee 可在审批详情读回；历史 `decisionComment` 行在读取端继续可读；写入端拒绝历史明文键。独立复跑 API 44 + Worker 52 个定向用例与 spotless 全绿，与 EV-1163 的计数一致。

本结论是独立技术验收，不是 Product 预验收/用户最终验收。`docs/ACCEPTANCE.md` 在 `9c59e79a` 的 Product 预验收仍是 `changes-requested`，且仍把 U-05/F-002 写成“当前 A/未解决”；Product 需按 DEC-0050=B 复验，不能把本 task review 当作 Product 已接受。

## 验收对账

任务卡两条验收（`.riccon/tasks/TASK-0252.json:18-20`）：

| AC                                                                                                                                                                                                                                           | 结果                      | 本次核对                                                                                                                                                                                                                                                                                                                                                                                                                     |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. 按 DEC-0050=B：API/Worker `workflow.approval.decided` 审计只写 `commentSha256`，不写 `decisionComment`；审批记录仍保留 ≤500 理由、详情可读；201–500 接受、501 拒绝；审计查询/导出返回 hash；旧 `decisionComment` 历史行可读，不删除历史。 | **pass（with residual）** | API `WorkflowApprovalAuditEvents.decided`（`:63-69`）与 Worker `WorkflowApprovalAuditor.decisionDetails`（`:182-186`）只 put `commentSha256`；`WorkflowApprovalApiTest`、`WorkflowAuditM06Test`、Worker integration 断言审批行原文 + 审计 hash/无明文；201/500/501 双向覆盖；legacy 行真实 DB 插入后查询可读。残余：审计导出未单独 E2E（复用 `AuditDetailsReader`，见“未验证”）；docs/Product 仍滞留旧口径（见“剩余风险”）。 |
| 2. 测试 API/Worker approval/audit：理由仍在审批记录、audit 无明文、hash 稳定、历史读兼容；T16a `WorkflowAuditM06Test` 同步；不改 schema/契约/PRODUCT/AGENTS；提交 clean EV；高风险 done 前独立 Verifier 评审。                               | **pass**                  | `WorkflowAuditM06Test` 4 tests（含 legacy 插入读取）；Worker 500/501 边界用例；两侧固定向量测试；17 个变更文件全部落在 8 个 write_scope；无 V15/schema/contracts/PRODUCT/AGENTS/T17 变更；EV-1163 clean；本 review 即独立 Verifier 结论。                                                                                                                                                                                    |

## 代码与行为核对

### 1. 写入面：只写 `commentSha256`

- API `WorkflowApprovalAuditEvents.decided`：`details` 只放 `workflowRunId/workflowApprovalId/nodeId/decision/approverUserId/targetToolName?`，理由经 `WorkflowApprovalCommentHash.sha256(comment)` 后放 `commentSha256`；无理由时不放该键（`:63-67`）。
- Worker `WorkflowApprovalAuditor.decisionDetails`：同样只放 `commentSha256`（`:182-186`）；`denied` 路径传 `comment=null`，不写理由或 hash。
- 两侧 `WorkflowApprovalCommentHash.sha256`：`null`/空白返回 `null`，否则 `comment.strip().getBytes(UTF_8)` 的 SHA-256，`HexFormat.of().formatHex` 输出 64 位小写 hex（`apps/api/...:29-42`；Worker 同名文件同实现）。与 `WorkflowApprovalDecisionCommand` 构造器的 `strip()`/空白归一一致，避免“记录理由与审计哈希规范化不同”。
- 测试：`WorkflowApprovalCommentHashTest`（API/Worker 各 5 tests）锁定固定向量 `905819e2...40230`（UTF-8 “同意”）、首尾空白、内部空白不同哈希、空值、64 hex 正则；`WorkflowApprovalApiTest`/`WorkflowAuditM06Test`/Worker integration 均断言审计 details 含 hash、不含 `decisionComment` 且序列化文本不含理由原文。

### 2. 审批记录仍保留原文，详情可读

- API/Worker 的 `decide` 都把 `command.comment()`（已 strip、空白归一）写入 `workflow_approval.decision_comment`；`WorkflowApprovalService.response(...)` 仍返回 `approval.decisionComment()`（`:743-766`），`get` 对 assignee 开放。
- `WorkflowApprovalApiTest` 拒绝用例在决定后再次 `GET` 审批详情，断言 `decisionComment` 等于 201 字符理由（`:293-299`）；同一用例的审计断言改为 `commentSha256` 且 `rawDetails` 不含理由（`:318-327`）。
- `WorkflowAuditM06Test.approvalCommentReadBackBoundaries` 用 201（批准）和 500（拒绝）字符理由，断言审批行 `decisionComment` 原文、审计只有 hash、501 字符时审批仍 `PENDING` 且无成功审计。Worker `WorkflowApprovalDecisionServiceIntegrationTest.decide_commentLengthBoundaries` 覆盖 500 拒绝与 501 拒绝，断言审批记录原文、审计 hash、无 `decisionComment`、日志 JSON 不含理由。

### 3. `AuditDetailsPolicy` 读写分离与历史兼容

- API `AuditDetailsPolicy`：`ALLOWED_KEYS` 同时登记 `commentSha256` 与历史 `decisionComment`；`VALUE_LENGTH_LIMITS` 为 `decisionComment=500`、`commentSha256=64`（`:319-345,516-519`）；`LEGACY_READ_ONLY_KEYS={workflow.approval.decided: decisionComment}`（`:395`）。
- `validate(String, Map)` 仍是读兼容路径：`AuditDetailsReader.read` 用它，历史 201–500 `decisionComment` 可读，501 仍 fail-closed；`validateWrite` 先 `validate`，再对登记动作拒绝历史键（`:580-591`）。API 唯一写入方 `WorkflowApprovalAuditEvents` 在 `AuditEvent.create` 前显式调用 `validateWrite`（`:69`）。
- 真实 DB 读兼容：`WorkflowAuditM06Test.legacyDecisionCommentAuditRowRemainsReadable` 直接 `INSERT INTO audit_event` 一条含 `decisionComment` 的历史行（无 `commentSha256`），再经审计查询 API 读回原文，断言无 hash；单元策略测试同时覆盖 legacy 201/500 通过、501 拒绝、`validateWrite` 拒绝而 `validate` 接受。
- Worker 侧没有审计读取路径（`apps/worker/src/main/java` 无 `SELECT ... audit_event`；`AuditDetailsPolicy.validate` 只用于 `AuditService.append` 等写入），其共享策略直接不包含 `decisionComment`，本地 `WorkflowApprovalAuditPolicy` 也只允许 `commentSha256`；即 Worker 写入更严格，API 读端负责历史兼容。该不对称在“Worker 无读路径”的前提下成立；若未来 Worker 增加审计读取，需补 legacy 读兼容（见剩余风险）。

### 4. Worker 策略、固定向量与 fail-closed

- Worker `AuditDetailsPolicy` 新增 `workflow.approval.decided` 白名单（`commentSha256`，nullable `targetToolName/commentSha256`）、`decision` 枚举（APPROVE/REJECT）与必填 ID/decision/approver（`:85-105,125,255`）；明文 `decisionComment` 不在白名单，测试 `AuditDetailsPolicyTest.validate_workflowApprovalDecided_acceptsHashAndRejectsPlaintext` 覆盖。
- Worker 本地 `WorkflowApprovalAuditPolicy` 移除 `decisionComment`/500 上限，改为 `commentSha256`/64；拒绝明文、65 字符哈希、空白哈希。
- `WorkflowApprovalAuditorTest`：`decided_writesCommentHashOnly` 捕获 `AuditEvent.details`，断言 hash、无明文；`decided_propagatesAuditFailure` 断言仓储异常向上抛；`denied_omitsReasonFields` 断言 denied 无理由/hash。`WorkflowApprovalDecisionService` 的 `@Transactional` `noRollbackFor` 只列业务 4xx，审计 `DataAccessException` 会使决定回滚（fail-closed），与既有 M06 审计失败语义一致。

### 5. 审计查询/导出

- `WorkflowAuditM06Test` 通过审计查询 API 读 `workflow.approval.decided` 的 details：`commentSha256` 等于固定算法结果，`decisionComment` 不存在，`details.toString()` 不含理由；legacy 行查询返回原文。
- 导出路径 `AuditExportService` 对每行调用 `AuditDetailsReader.read`（`apps/api/src/main/java/com/riccon/api/audit/service/AuditExportService.java:591`），因此新行导出同样只有 hash、历史行按 DEC 保持可读。本次没有单独跑导出 E2E（见“未验证”）。

## 证据复核：EV-1163

- `EV-1163`：`command` 为 API `WorkflowApproval*Test,WorkflowAuditM06Test,AuditDetailsPolicyTest,AuditDetailsReaderTest` + Worker `com.riccon.worker.workflow.approval.**.*Test,com.riccon.worker.audit.*Test` + `./mvnw -q spotless:check`；`manual=false`，`commit=9c59e79a5febf1...`，`dirty=false`，`exit_code=0`，`duration=164665ms`。
- `output_sha256=c5513fc60507cc3687759f989d5e7e57ecb8b282d31ac7700a1e951bb33c89c5` 与本地 `shasum -a 256` 一致；`output_bytes=80516` 与文件大小一致。
- 日志：API `Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`，其中 `WorkflowAuditM06Test` 4/4、`AuditDetailsPolicyTest` 12/12、`AuditDetailsReaderTest` 6/6、`WorkflowApprovalApiTest` 8/8；Worker `Tests run: 52, Failures: 0, Errors: 0, Skipped: 0`，其中审批决定 9/9、执行器 6/6、`AuditDetailsPolicyTest` 18/18、`WorkflowApprovalAuditorTest` 4/4；尾部 `BUILD SUCCESS`。日志明确列出这次新增的 `decide_commentLengthBoundaries` 所在类（`IT-WF-108/SEC-WF-001 审批决定` 9 tests）。
- 提交关系：`e26fee8f -> a9608afe -> 9c59e79a`；`git diff a9608afe..9c59e79a` 只有 `docs/ACCEPTANCE.md`，代码路径 `apps/`+`platform/` 无差异；`git diff a9608afe..HEAD -- apps platform` 为空。即 EV 记录时 `9c59e79a` 的代码与 `a9608afe` 相同。
- 复核时工作树仅 `.riccon/modules/M06.json` 有未提交修改（非本任务源码；EV-1163 自身记录 `dirty=false`），不影响本任务代码/证据判定。

## 独立复跑

为不依赖 builder 的 EV，本 reviewer 在 `verifier-t0252` namespace 用同一组定向测试独立复跑（reviewer 侧验证，不写成任务 EV）：

```bash
node scripts/test-services.mjs reset --namespace verifier-t0252
RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-t0252 \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -pl apps/api test \
  -Dtest='WorkflowApproval*Test,WorkflowAuditM06Test,AuditDetailsPolicyTest,AuditDetailsReaderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=verifier-t0252 \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -pl apps/worker -am test \
  -Dtest='com.riccon.worker.workflow.approval.**.*Test,com.riccon.worker.audit.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q spotless:check
```

结果：API `44/0/0/0`（`WorkflowAuditM06Test` 4/4，含 legacy insert → query；`WorkflowApprovalApiTest` 8/8；`AuditDetailsReaderTest` 6/6），Worker `52/0/0/0`（审批决定 9/9，含 500/501 边界；`WorkflowApprovalAuditorTest` 4/4；`AuditDetailsPolicyTest` 18/18），`spotless:check` exit 0，命令以 `VERIFIER_RERUN_OK` 结束。未使用 `--no-file-parallelism`、未加 retry、未跳过测试；与 EV-1163 的计数和命令一致，没有为通过而放宽参数。

## 范围核对

- `git diff --name-only e26fee8f..a9608afe` 共 17 个文件，全部落在 TASK-0252 的 8 个 write_scope（API approval main/test、API `AuditDetailsPolicy`/audit test、Worker approval main/test、Worker audit `AuditDetailsPolicy`/test）；`WorkflowAuditM06Test` 位于 `apps/api/src/test/java/com/riccon/api/audit/**`，且任务卡明确要求“T16a WorkflowAuditM06Test 同步”。
- 未改 `V15`/迁移、schema、`contracts/`、`PRODUCT.md`、`AGENTS.md`、`e2e/`（T16a E2E/T17）；`git diff --name-only ... | grep -Ei 'migration|V15|schema|contract|PRODUCT|AGENTS|e2e|t17'` 无结果。
- 生产源码只改审批审计/策略/哈希三个面，没有新端点、新表、新权限或 UI 变更。

## 未验证

- **未跑全量合同 build/test/coverage 与 E2E/a11y/perf/migration/T17**：任务要求本次只复核并复跑关键 approval/audit 定向测试；EV-1163 也是定向 EV。全量合同套件、模块 gate 与 T17 必须在最终提交上重跑，不能把本 review 当成 coverage/E2E 证据。
- **审计导出的 decided 行没有单独 E2E**：导出复用 `AuditDetailsReader`（`AuditExportService.java:591`），同一策略已由查询/读回与单测覆盖；风险低，T17/模块 gate 可补一条导出断言。
- **API 审批审计失败的 rollback 没有本任务专属用例**：`WorkflowApprovalService.decide` 的 `@Transactional` `noRollbackFor` 不含 `DataAccessException`，审计失败会抛出并回滚；既有 `WorkflowVersionAuditFailClosedTest`/`WorkflowRunAuditFailClosedTest` 覆盖同一审计事务模式，本任务未新增 API 专属审计失败测试。
- **真实浏览器/axe/375px**：本任务无 UI 变更，不适用。
- **生产数据库**：legacy 行只在测试 namespace 的真实 MySQL 插入读取，没有触碰生产数据；迁移不适用。

## Findings

P0/P1 open：无。新增 P2：无。P3 accepted 4 个（详见 front matter）：

- **F-001**：无盐 SHA-256 对低熵理由存在字典/暴力确认风险；DEC-0050=B 已选择该取舍，要求文档化并保留导出审批控制。owner `riccon/architect + riccon/lead`，due M06 gate 前。
- **F-002**：API/Worker 两份完全相同的 `WorkflowApprovalCommentHash`；固定向量测试已双侧锁定，M06b/M09 或下次修改任一侧时抽到共享工具。owner `riccon/architect + riccon/builder-b1`。
- **F-003**：`validateWrite` 依赖调用方显式调用，通用 `AuditEvent.create` 仍按读兼容允许历史键；当前唯一写入方已调用、Worker 策略更严格，未来新增写入方必须复用。owner `riccon/architect + riccon/builder-b1`。
- **F-004**：Worker `WorkflowApprovalDecisionService` 类注释（`:54-55`）仍写“U-05 临时默认完整理由入审计”，与 DEC-0050=B/实际行为相反；行为正确，属生产注释陈旧。owner `riccon/builder-b1 + riccon/architect`，due M06 gate/T17。

## 剩余风险

1. **文档口径滞后（不由本任务修复）**：`docs/adr/ADR-0024` §8.1 `:604` 仍列 `decisionComment?`、§8.3 `:621` 仍写“U-05 未解决/临时默认完整理由”、§16 `:975` 与 F-013 `:1051` 仍未解决；`docs/PRODUCT.md:11,26,28`、`docs/ARCHITECTURE.md:46,48,50` 仍把 U-05 记为 accepted P2 residual；`docs/ACCEPTANCE.md` 在 `9c59e79a` 的 Product 预验收仍称“当前 A/F-002 open”。本任务验收明确不改这些文档，但模块 gate 前必须由 architect/product/lead 按 DEC-0050=B 更新，否则模块 docs DoD/Product 预验收不能声称 U-05 已解决。owner `riccon/architect + riccon/product + riccon/lead`，due M06 gate/T17 前；这条如需正式 finding，应落在模块 review（`REV-M06-001` F-002/F-009 重签发），本任务 review 只记录剩余风险。
2. **Product 预验收仍需复核**：`docs/ACCEPTANCE.md` 的 `changes-requested`（AC-17/U-05）在 TASK-0251/0252 修复后需要 Product 复验；本 review 不替代 Product 或用户验收。
3. **Worker 未来读路径**：当前 Worker 不读取 `audit_event`，其策略不允许 `decisionComment`；若 M06b/M07 给 Worker 增加审计读取，需补历史读兼容或明确只读新行，否则旧行会 `RICCON-AUDIT-500`。
4. **无盐哈希确认风险**：见 F-001；已由 DEC-0050=B 接受，需在 ADR/威胁模型记录。
5. **审计导出**：新行 hash-only 由共享 `AuditDetailsReader` 保证；导出 E2E 未单独复跑，T17 可补。
6. **前序 review 的连续性**：`REV-TASK-0219-001 F-001`/`REV-TASK-0226-001 F-002` 的“201–500 理由在审计可读”要求已被 DEC-0050=B 取代；本次用 legacy 读兼容测试保留历史行可读，不再要求新写入明文。模块 review 重签发时应把该 P2 标为 superseded/关闭，而不是重新要求明文入审计。

## 下一步

- 代码/证据层面：本任务可进入 done 流程（由 Lead 按模块状态推进）；无需本 reviewer 再改源码。
- 文档/产品层面：architect 更新 ADR-0024 §8.1/§8.3/§16 与 ARCHITECTURE/PRODUCT 的 U-05 口径；Product 在 U-05 修复后重跑预验收；T17 在最终提交上重跑全量/coverage/E2E 并复核 F-001..F-004 的状态。
- 不建议把本 review 当作 M06 模块 gate 的替代；模块 review 仍需按合同重跑全量并重签发。
