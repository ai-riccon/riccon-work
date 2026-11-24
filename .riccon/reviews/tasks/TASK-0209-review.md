---
review_id: REV-TASK-0209-001
module_id: M25
task_id: TASK-0209
reviewer:
  agent: riccon/verifier
  session: ses_df43a1a89ffeX4ojC7tBM9mMsd
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 4b43cc1cb02b553f0469d3dfc9a773dbb01c4f2b
  - 5b4feded0ee2df9dfe2c378b91f05f3cd202ce4d
  - 6a1b77e4f30b3be405a0076aa3a0a2e966cede87
head_commit: 6a1b77e4f30b3be405a0076aa3a0a2e966cede87
baseline_commit: d7d4aa597f674ceff4a3ade0ba7bd826b0c6b33b
dod:
  acceptance: true
  tests: true
  perf: true
  review: true
  evidence: true
dod_not_applicable:
  - build
  - coverage
  - e2e
  - migration
  - a11y
  - docs
dod_note: >-
  本任务只修改 perf harness（`e2e/perf/mvc-smoke.js`、`scripts/real-stack/run.mjs`），无生产源码、迁移、
  契约、部署或文档行为变化。acceptance 记 true 但任务 AC“报告可复现”按 **pass (with residual)** 记录 F-001：
  EV-1002 的 command 字段完整且可复现，但报告内 `reproduceCommand` 未带 `PERF_M05_ACL_WRITES=0`，且
  `env.perfM05.concurrency.aclWriteVus` 仍写 1；照抄报告命令会启动已接受的 ACL 写场景并触发 500ms 阈值。
  perf 以 EV-1002 为准；独立 10 对象 smoke 只验证 harness 正路径，不替代 1000 对象预算。build/coverage/e2e/
  migration/a11y 由 M25 模块 gate/V2b 承接；本任务无文档行为变化，docs 记 N/A。
reviewed_evidence:
  - EV-1002
evidence:
  - EV-1002
test_impact: >-
  `node --check scripts/real-stack/run.mjs` 与 `node --check e2e/perf/mvc-smoke.js` exit 0；
  `node scripts/real-stack/run.mjs --self-test` 14/14 passed exit 0；`k6 inspect` 验证默认 ACL=10 保留
  `knowledge_acl_write` 场景与 ACL 阈值、ACL=0 时两者都消失、`PERF_M05_CONNECTOR=1` 缺 ID 时 fail-closed；
  独立 10 对象 connector smoke exit 0（10/10、sync 531ms、queue p95 1,453.584ms、retrieval p95 39.021ms、
  cleanup 10/10、bucketStatus 204、ACL null、无 failure 文件）。未跑全量 Maven/E2E/coverage。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-kb-perf
    due: V2b 前（M25 gate 前）
    title: "报告内复现命令与 ACL 写并发元数据未反映 PERF_M05_ACL_WRITES=0"
    summary: >-
      `scripts/real-stack/run.mjs:836-880` 的 env 快照把 `perfM05.concurrency.aclWriteVus` 硬编码为 1，
      且 `reproduceCommand`（:860-877）没有带 `PERF_M05_ACL_WRITES=0`。EV-1002 的日志第 397-400 行仍显示
      `aclWriteVus: 1`，第 406 行 reproduceCommand 缺该开关；但同一次运行写出的 k6 报告
      `perf-m05-knowledge.json` 的 `m05.concurrency.aclWriteVus=0`，EV-1002 command 本身也明确带
      `PERF_M05_ACL_WRITES=0`。verifier 10 对象 smoke 复现了同一不一致：`real-stack-env.json` 写
      `aclWriteVus: 1`，`perf-m05-knowledge.json` 写 0。若按报告里的 reproduceCommand 复跑，会使用默认 ACL=10，
      启动 `knowledge_acl_write` 场景并断言 `m05_acl_write_ms p95<500`；DEC-0043 记录的 ACL 写 residual 为
      2.98s，复跑会失败，无法复现 EV-1002 的 passed 矩阵。
    accepted_reason: >-
      判据数据本身没有假绿：EV-1002 command 完整记录了 `PERF_M05_ACL_WRITES=0`，按 EV 命令可复现；
      该缺口只在报告内的便捷复现命令和 env 快照元数据，不改变 connector 同步/索引/队列/检索/首字数据。
      但若不修，V2b 复验照抄报告命令会误触发已接受的 ACL 写 residual。修复很小（记录实际 `aclWriteVus`，
      并在 ACL=0 时把该开关写入 reproduceCommand），列入 V2b 前承接；不影响本任务证据成立。
    evidence: >-
      scripts/real-stack/run.mjs:836-880；.riccon/evidence/TASK-0209/EV-1002-2026-11-24T12-16-48-759+08-00-set-e-mvnw-q-t1c-dskiptests-package-pl-apps-api-.log:397-406；
      .riccon/evidence/TASK-0209/verifier-smoke-10obj-20261124T122039.log（对应 temp
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/verifier-b11b-smoke-20261124T122039/real-stack-env.json
      与 perf-m05-knowledge.json）。
notes: >-
  验证时 HEAD 6a1b77e 相对 5b4fede 只改 `.riccon`（EV-1002、EV-1000/1001 归档、state/task），
  代码与脚本未变；EV-1002 的 commit 5b4fede 中两个脚本与任务 end_commit 4b43cc1 完全一致
  （`git diff 4b43cc1..5b4fede -- e2e/perf/mvc-smoke.js scripts/real-stack/run.mjs` 为空）。
  `riccon gate check --module M25` 于 2026-11-24T12:20:57+08:00 FAIL：TASK-0209 仍 review、TASK-0212/0213
  planned、无 M25 模块评审；另有 write_scope 误报——scope.mjs 只在任务 done 时使用 end_commit，当前按
  baseline..HEAD 把 main 上 TASK-0206/0207/0211 的提交算进 TASK-0209。手工核对 d7d4aa5..4b43cc1 只有
  2 个脚本文件；标 done 并写 end_commit=4b43cc1 后范围应收敛，不需要扩 write_scope。本任务评审不替代
  M25 模块 gate、Product 预验收或用户验收。请求中的 `.ricon/reviews/tasks/` 目录不存在，按项目规范写到
  `.riccon/reviews/tasks/`。
created_at: "2026-11-24T12:26:00+08:00"
---

# REV-TASK-0209-001：M05b-B11b 连接器 perf 分支独立评审

## 结论

**Pass（带 residual）**。EV-1002 在 commit `5b4fede`（脚本与任务 end commit `4b43cc1` 一致、dirty=false）
真实跑通 knowledge + 1000 对象连接器分支：连接器 `passed`、`scanned/created=1000`、`failed/skipped/deleted=0`、
`complete=true`、sync 40.099s ≤15min、索引 1000/1000、队列 p95 48.773s ≤5min、检索 p95 37.546ms ≤500ms、
首字估算 85.546ms ≤3s、k6 exit 0、矩阵 exit 0。错误传播、ACL=0 语义、S3 fixture、默认 ACL 回归和范围均通过
代码审计与独立 smoke。唯一非阻塞缺口是 F-001（P2 accepted）：报告内 reproduceCommand/env 快照未记录 ACL=0，
照抄报告命令会触发已接受的 ACL 写 residual；EV-1002 command 本身完整可复现。

## AC 对账

任务卡只有一条 AC：`PERF_M05_SCENARIO=knowledge 连接器同步分支；整条场景复验；报告可复现；不启用连接器以外分支`。
ADR-0023 §9 的判据拆解如下。

| 判据                                                           | 结论                           | 观察值 / 证据                                                                                                                                                                   |
| -------------------------------------------------------------- | ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 连接器分支启用且只启用连接器分支（M04 显式跳过）               | pass                           | EV-1002 `phases.connectorSync=passed`、`phases.m04=skipped-by-REAL_PERF_SKIP_M04`；`PERF_M05_CONNECTOR=1` 仅在 knowledge 场景允许，非 knowledge 直接报错（run.mjs:3510-3516）。 |
| 连接器同步 1000 对象 ≤15min（PERF-M05-003）                    | pass                           | sync `SUCCESS`、`complete=true`；`processingMs=40,099`、`endToEndMs=40,347`，budget 900,000ms；`withinBudget=true`。                                                            |
| 同步计数真实（scanned/created/updated/skipped/failed/deleted） | pass                           | summary `{scanned:1000, created:1000, updated:0, skipped:0, failed:0, deleted:0, complete:true}`；items `{SYNCED:1000}`；checks `countsOk=true`。                               |
| 索引 1000/1000（不是“同步完成即索引完成”）                     | pass                           | `indexing.counts={total:1000,indexed:1000,failed:0,pending:0}`、`timedOut=false`、`chunksWithToken=1000`；独立轮询文档状态。                                                    |
| heavy 通道队列等待 p95 ≤5min（PERF-M05-004）                   | pass                           | 1,001 个样本（1 sync + 1,000 index），p95 `48,772.708ms`，budget 300,000ms；`queueOk=true`。                                                                                    |
| 检索 p95 ≤500ms（整条场景）                                    | pass                           | slow_log 660 样本，p95 `37.546ms`，max `98.834ms`；connector 分支强制 `enforceSearch`，>500ms 直接 failed（run.mjs:2915-2918）。                                                |
| 首字 p95 ≤3s                                                   | pass（口径为估算）             | 服务端 `firstTokenServerP95=48ms`；用户首字估算 = retrieval p95 + 服务端首字 = `85.546ms`；报告明确标注 k6 SSE 整段缓冲，`m05_chat_submit_ttfb_ms` 不是首字口径。               |
| 报告/矩阵可复现                                                | **pass (with residual F-001)** | EV-1002 command 完整、commit/dirty/hash 核对通过；报告内 `reproduceCommand` 缺 `PERF_M05_ACL_WRITES=0` 且 `aclWriteVus` 写 1，见 F-001。                                        |
| ACL=0 不冒充通过/失败                                          | pass                           | EV-1002 `aclWriteP95Ms=null`、`aclGetP95Ms=null`、`aclWithinBudget=null`；k6 报告无 ACL 指标/阈值；ACL 写 residual 由 DEC-0043=A 单独接受。                                     |
| OCR 未执行不冒充通过                                           | pass（未量测）                 | `ocr.probed.available=false`、`ocr.measured=false`，报告明确需在含 Tesseract 的 worker 镜像/CI 复验。                                                                           |
| 矩阵退出码                                                     | pass                           | EV-1002 `exit_code=0`；log `k6 knowledge exit=0`；matrix `status=passed`、`k6.status=0`。                                                                                       |

## 验证了什么

- **EV-1002 真实性**：`.riccon/evidence/TASK-0209/EV-1002.json` 的 command、commit `5b4feded0ee2df9dfe2c378b91f05f3cd202ce4d`、`dirty=false`、`exit_code=0` 均与任务卡/提交一致；输出日志 25,663 字节，sha256 `e589a83055baa6e1c8f45d892910ce7f5607f1515087d17e082256dced7270e1` 与 JSON 记录一致。日志内 `scriptSha256=9ba7dbc5…56cdf` 与当前 `e2e/perf/mvc-smoke.js` 及 5b4fede/4b43cc1 版本一致。
- **代码状态绑定**：`git diff 4b43cc1..5b4fede -- e2e/perf/mvc-smoke.js scripts/real-stack/run.mjs` 为空；`git rev-parse 5b4fede:e2e/perf/mvc-smoke.js`/`run.mjs` 的 sha 分别为 `9ba7db…`/`02fba8…`，与工作树一致。HEAD `6a1b77e` 相对 5b4fede 只改 `.riccon` 证据/状态，因此 EV-1002 对两个脚本的结论可绑定到 end commit 4b43cc1。
- **错误传播**：`launchK6` 默认 `PERF_M05_CONNECTOR:"0"`（run.mjs:2451-2456），只有 connector id 与 syncId 同时存在时才通过 `extra` 覆盖为 1（:2833-2844），父进程 env 不会泄漏给 k6。连接器分支失败时写 `perf-m05-connector-failure.json` 并打印 `status/stage/error`（:2795-2812）；`result.error` 带 stage 前缀（:2161-2162）。归档 EV-1001 的日志在同一脚本版本上实际触发该路径：`connector branch failed: status=failed stage=createConnector error=...`，并生成 failure JSON（`stage=createConnector`）；随后 k6 正常进入 knowledge 场景，没有退化成“缺少 PERF_M05_CONNECTOR_ID”。
- **ACL=0 语义**：`m05AclWrites` 校验非负整数；ACL=0 时 `knowledge_acl_write` 场景不注册、ACL 四组阈值不注册（mvc-smoke.js:80-88、203-227、257-279）；`requireKnowledgeInputs` 不再要求 ACL 文档/用户 ID（:606-630）。run.mjs 的 `aclWithinBudget` 在 ACL=0 时为 null（:3012-3019），并写入 notes（:3043-3047、:3098-3102）。默认 ACL=10 行为保留。
- **S3 fixture**：`s3RequestWithRetry` 只重试网络异常、429、5xx，最多 3 次，500ms 级退避；成功/404/409 响应体显式 drain，避免 1000 个短 PUT 耗尽 undici 连接池（run.mjs:442-480）。上传并发默认 8（:106-116），1000 对象上传 1.608s；源桶 `riccon-perf-b11b-source` 与平台桶 `riccon-test` 隔离（:1048、:1600-1694）；cleanup 对 404 幂等，本次 1000/1000 删除、bucketStatus 204。无新增 npm/Maven 依赖。
- **回归**：connector env 只在 `context.connectorPerf` 时注入 API/worker（run.mjs:927-940）；`PERF_M05_CONNECTOR=1` 非 knowledge 直接抛错；M04 走独立 `runK6`，EV-1002 显式 `REAL_PERF_SKIP_M04=1`，phase 为 `skipped-by-REAL_PERF_SKIP_M04`。`k6 inspect` 验证默认 knowledge（ACL 未设置=10）仍有 `knowledge_acl_write` 和全部 ACL 阈值，ACL=0 时二者消失。
- **独立 smoke（harness 正路径，非 1000 对象性能替代）**：命令为
  `PERF_M05_SCENARIO=knowledge PERF_M05_CONNECTOR=1 PERF_M05_CONNECTOR_OBJECTS=10 PERF_M05_DOCS=5000 PERF_M05_CHUNKS_PER_DOC=20 PERF_M05_DURATION=15s PERF_M05_BACKLOG=0 PERF_M05_ACL_WRITES=0 REAL_PERF_SKIP_M04=1 REAL_EVIDENCE_DIR=<temp> node scripts/real-stack/run.mjs --mode=perf`；
  exit 0，top matrix `passed`，connector `passed`，10/10 创建/索引，sync 531ms，queue p95 1,453.584ms，retrieval p95 39.021ms，首字估算 93.021ms，API p95 194.444ms，cleanup 10/10 + bucket 204，`aclWithinBudget=null`，无 failure 文件。日志：`.riccon/evidence/TASK-0209/verifier-smoke-10obj-20261124T122039.log`（sha256 `62fe9fab…d1e9b`）。
- **范围/证据卫生**：`git diff --stat d7d4aa5..4b43cc1` 仅 `e2e/perf/mvc-smoke.js`、`scripts/real-stack/run.mjs`；`git diff --check d7d4aa5..4b43cc1` 通过；无 contracts/docs/deploy/生产源码改动。EV-1000/EV-1001 只在 `.riccon/archive/evidence-superseded/TASK-0209/`；active 目录只有 EV-1002，任务卡 evidence 只列 EV-1002，`state validate` 无重复 EV 告警。

## 未验证

- 未重复 1000 对象全量 perf；按任务约定以 EV-1002 为准，V2b 在独立上下文复验。
- 未跑“不启用 connector”的默认 knowledge 全量；只通过代码路径和 `k6 inspect` 验证场景/阈值未变，M05 基线由既有证据承接。
- 未执行 ACL 写/读场景（DEC-0043=A 单列，`aclWithinBudget=null`）；ACL 写 2.98s residual 不在本任务判据内。
- OCR 未量测（宿主 libtesseract 不可用，报告 `ocr.measured=false`）。
- 未跑 M25 模块全量 build/test/coverage/E2E/a11y/security；这些由模块 gate 与 V2b 承接。
- 未测“连接器同步进行中 chat/report 是否被阻塞”的并发场景；本次同步串行在 k6 之前，lane 隔离由 `IT-WORKER-LANE-001` 等承接。
- 未验证真实客户 S3/SSRF/凭据边界；B6/O2a 功能与安全证据负责，本 perf 夹具只用测试 RustFS 与测试凭据。

## 剩余风险

1. **F-001（P2 accepted）**：报告 reproduceCommand 和 env 快照未记录 ACL=0；EV 命令可复现，但 V2b 照抄报告会误触发 ACL 写 residual。
2. **ACL 写 residual（DEC-0043=A accepted）**：历史 2.98s > 500ms，owner Lead+Architect 跟踪到 M09/发布；本任务不声称 ACL 写达标。
3. **OCR residual**：宿主 libtesseract 不可用，OCR 单页/20 页索引未量测，需含 Tesseract 的 worker 镜像/CI 复验。
4. **宿主负载**：EV-1002 1 分钟 load peak 13.08 / 12 CPU，报告已提示结果偏保守；V2b 应在空闲宿主复验。
5. **首字口径**：85.546ms 是 retrieval p95 + 服务端首字 p95 的估算，不是端到端用户 TTFB；k6 SSE 整段缓冲的 3.27s 不能当首字。
6. **连接器索引延迟**：`connectorIndexP95Ms=48.815s` 已记录但未设 60s 阈值；当前低于 ADR §9 无 OCR 60s 预算，V2b 可补断言。
7. **EV-1000/1001 归档 JSON 的 `output_path` 仍指向旧 active 路径**；README 已说明 superseded，active 无重复，不影响 EV-1002，但按路径直接校验归档文件会找不到原日志。
8. **V2b 仍需独立 perf/security 复验**；M25 gate 还受 TASK-0212/0213 planned 和无模块评审阻塞，本任务评审不能替代模块 gate。

## 建议

- Lead 可将 TASK-0209 标 done，`end_commit=4b43cc1`；不要为 gate 的 write_scope 误报扩写范围——任务 done 后 `scope.mjs` 会改用 baseline..end_commit，范围应收敛到 2 个脚本。
- F-001 交 `riccon/builder-kb-perf` 在 V2b 前修：记录实际 `aclWriteVus`，ACL=0 时把 `PERF_M05_ACL_WRITES=0` 写入 `reproduceCommand`；不需要重开本任务。
- V2b 独立复验 1000 对象与 security；ACL residual 按 DEC-0043=A 记录 owner/due，不在 M25 内静默修复或扩大预算。
- M25 模块 gate 仍需 TASK-0212/0213 完成和模块 review；本任务级评审不参与模块 gate。

## Gate 说明

`riccon gate check --module M25` 在 2026-11-24T12:20:57+08:00 返回 FAIL，属于预期：TASK-0209 仍是 review（未 done）、TASK-0212/0213 planned、没有 M25 模块评审。另有 `TASK-0209 changed files outside write_scope` 的误报：`scope.mjs` 对 review 状态任务取 `baseline..HEAD`，把 main 上其他任务的提交算入；手工核对 `d7d4aa5..4b43cc1` 只有两个脚本文件。该 gate 结果不改变本任务 verdict；模块 gate 应在 TASK-0209 done、0212/0213 完成并产出模块评审后再跑。
