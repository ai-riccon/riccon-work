---
review_id: REV-M18-REVALIDATION-001
module_id: M18
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e08d1b473ffezoltlVNmpba9tZ
  model: deepseek-v4.1-flash
  independent: true
review_kind: revalidation-report
verdict: blocked
verdict_note: "verdict=blocked 指 M18 模块自身尚未收口（TASK-0091 planned，且本文件不是 M18 module review）；7 个回溯模块的 gate 全部 pass，详见正文。"
task_id: TASK-0090
head_commit: cb977f98a493bfb7db9f9d4b02731ac62e195053
scope_modules: [M01, M02, M03, M10, M12, M13, M15]
dod:
  acceptance: false
  build: false
  tests: false
  coverage: false
  e2e: false
  review: false
  docs: false
  evidence: false
findings: []
created_at: "2026-11-20T14:16:30+08:00"
---

# M18 跨模块证据回溯报告（TASK-0090）

## 1. 结论

> 注：本文件 front matter 的 `verdict: blocked` 指 **M18 模块自身尚未收口**（TASK-0091 planned，且本文件不是 M18 module review）；正文结论是 **7 个回溯模块的 gate 全部 pass**。两者范围不同，不要混读。

**结果：pass（仅限本回溯范围）。** 在 HEAD `cb977f98a493bfb7db9f9d4b02731ac62e195053`（工作树干净）上，用当前合同 `commands.test` 全量命令为 M01/M02/M03/M10/M12/M13/M15 各重录一条 test/coverage 证据，重写 7 份 module review（真实 `ses_...` 会话、`previous_review` 指向归档旧评审），并逐个运行 `gate check`。

- **7/7 模块 gate 通过**：M01、M02、M03、M10、M12、M13、M15 全部 `PASS`，0 errors。
- **无代码回归**：根因是 DEC-0022 合并后合同 `commands.test` 改为 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`，旧 test EV 命令文本不再覆盖合同命令；代码本身在同一命令下全绿。
- **无生产源码/测试/迁移/合同/文档改动**：工作树非 `.riccon/` 变更为 0。`gate check`/`evidence run` 对 `state.json`、任务 JSON 的写入是内核副作用，不是手工修改。
- **未发现真实代码/测试缺陷**；本回溯只恢复证据链，不重新宣称 E2E/a11y/security/真实栈结论。
- **未把 TASK-0090 或 M18 标 done**；提交与模块验收由 Lead 处理。

## 2. 根因与处理策略

### 2.1 根因

DEC-0022 合并后，合同 `commands.test` 与 `commands.coverage` 同为：

```bash
./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage
```

7 个模块旧的 test EV 录制的是旧命令 `./mvnw -q -T1C verify && pnpm -w test`（无 `jacoco:report`、无 `-- --coverage`）。`gate check` 的 `dod-evidence:tests` 要求证据命令覆盖合同 `commands.test`，于是逐个报：

- M01 `EV-0073 command does not match commands.test`
- M02 `EV-0292 ...`
- M03 `EV-0344 ...`
- M10 `EV-0438 ...`
- M12 `EV-0377 ...`
- M13 `EV-0354 ...`
- M15 `EV-0483 ...`

旧的 coverage EV（EV-0074/0293/0345/0443/0378/0355/0484）命令本身已与合同一致，但为了让 `tests` 与 `coverage` 都绑定当前 HEAD 的真实执行，本次按项目规则共用同一条全量 EV（合同两项命令相同），不再拆两次跑。

### 2.2 每个模块的处理顺序

1. `git status --short` 确认工作树干净（`.riccon/` 之外无改动）。
2. 旧 review 原样复制到 `.riccon/archive/reviews/<MOD>-review-<旧ID>.md`，不修改原文、不覆盖已存在文件。
3. 在代表任务上执行一次合同全量命令，用 `riccon evidence run` 记录（exit 0、`dirty=false`、`commit=cb977f98…`）。
4. 生成新 review：`review_id` 递增、`previous_review` 指向旧 ID、`reviewer.session` 使用真实当前会话、`dod` 复制旧值、`dod_evidence.tests/coverage` 绑定新 EV、`reviewed_evidence` 追加新 EV、`created_at` 晚于新 EV。
5. 运行 `gate check --module <MOD>`，记录退出码与 warnings。

## 3. 模块处理结果

| 模块 | 代表任务  | 旧 review   | 归档路径                                            | 新 review   | 新 EV   | gate     | warnings                                                       |
| ---- | --------- | ----------- | --------------------------------------------------- | ----------- | ------- | -------- | -------------------------------------------------------------- |
| M01  | TASK-0001 | REV-M01-001 | `.riccon/archive/reviews/M01-review-REV-M01-001.md` | REV-M01-002 | EV-0490 | **PASS** | 8（11 条 legacy 无 commit 证据 + 5 条 open P2 + 2 条 open P3） |
| M02  | TASK-0008 | REV-M02-003 | `.riccon/archive/reviews/M02-review-REV-M02-003.md` | REV-M02-004 | EV-0491 | **PASS** | 2（F-009、F-014 open P3）                                      |
| M03  | TASK-0033 | REV-M03-001 | `.riccon/archive/reviews/M03-review-REV-M03-001.md` | REV-M03-002 | EV-0492 | **PASS** | 0                                                              |
| M10  | TASK-0065 | REV-M10-001 | `.riccon/archive/reviews/M10-review-REV-M10-001.md` | REV-M10-002 | EV-0493 | **PASS** | 0                                                              |
| M12  | TASK-0054 | REV-M12-001 | `.riccon/archive/reviews/M12-review-REV-M12-001.md` | REV-M12-002 | EV-0494 | **PASS** | 0                                                              |
| M13  | TASK-0051 | REV-M13-001 | `.riccon/archive/reviews/M13-review-REV-M13-001.md` | REV-M13-002 | EV-0495 | **PASS** | 0                                                              |
| M15  | TASK-0084 | REV-M15-001 | `.riccon/archive/reviews/M15-review-REV-M15-001.md` | REV-M15-002 | EV-0496 | **PASS** | 0                                                              |

补充：

- M01 的 F-001（前端覆盖率 7.69%/7.81%）与 F-003（生成代码计入覆盖率分母）本次由 EV-0490 + 当前 `apps/web/vitest.config.ts` 直接证明已修复，状态改为 `fixed`；其余 findings 保留原状态。
- 新 review 均在正文 H1 后加入“回溯说明（2026-11-20）”，保留旧正文作为历史快照，并注明“正文 findings 表为旧评审原文，当前状态以 front matter 为准”。
- 旧 review 全部保留在归档路径，未被删除或改写。

## 4. 新证据明细

| EV      | 任务      | 开始（+08:00） | 结束（+08:00） | 耗时     | exit | commit   | dirty |
| ------- | --------- | -------------- | -------------- | -------- | ---- | -------- | ----- |
| EV-0490 | TASK-0001 | 12:22:59       | 12:35:28       | 12.5 min | 0    | cb977f98 | false |
| EV-0491 | TASK-0008 | 12:38:56       | 12:52:41       | 13.7 min | 0    | cb977f98 | false |
| EV-0492 | TASK-0033 | 12:53:43       | 13:07:14       | 13.5 min | 0    | cb977f98 | false |
| EV-0493 | TASK-0065 | 13:07:54       | 13:21:11       | 13.3 min | 0    | cb977f98 | false |
| EV-0494 | TASK-0054 | 13:21:53       | 13:35:12       | 13.3 min | 0    | cb977f98 | false |
| EV-0495 | TASK-0051 | 13:35:37       | 13:49:03       | 13.4 min | 0    | cb977f98 | false |
| EV-0496 | TASK-0084 | 13:49:30       | 14:01:58       | 12.5 min | 0    | cb977f98 | false |
| EV-0498 | TASK-0090 | 14:12:52       | 14:14:01       | 1.1 min  | 0    | cb977f98 | false |

- EV-0490..EV-0496 命令均为合同原文 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`，日志哈希/字节数与记录一致（gate 逐条校验通过）。
- EV-0498 为最终 gate 循环（7 个模块顺序执行、任一失败即退出非零），exit 0。
- **superseded**：首次尝试录制的 EV-0497 使用了多行命令，内核日志头只保留首行 `set -e`，导致 `command does not match its log header`；该记录从未被任何 review/DoD 引用，已从 TASK-0090 的 evidence 列表移除并保留原始文件作审计（项目既有 superseded 证据惯例），改以单行等价命令重录为 EV-0498。
- 8 条新 EV 均为 `manual=false`、`dirty=false`、commit 为 HEAD 本身。

## 5. 本次实测结果

7 次全量运行结果一致（无 flake、无 retry、无额外参数）：

- **Java**：765 tests（api 453 / worker 263 / platform-common 33 / platform-architecture-tests 12 / platform-domain 4），failures 0、errors 0、**skipped 0**；Maven `verify`（含 `jacoco:check`）exit 0。
- **前端**：46 个测试文件、384 tests passed、**0 skipped**。
- **前端覆盖率**（v8）：statements **87.8%**、branches **80.75%**、functions 84.63%、lines **87.71%**，高于合同 line 80 / branch 70。
- **后端覆盖率**：`verify jacoco:report` 内的 `jacoco:check` 通过（未逐模块抄录百分比；旧 review 的门槛结论不变）。
- 日志中出现的 `Migration of schema riccon_failure ... failed!` 是迁移失败注入负向测试的预期输出，随后 `repair` 与重跑成功；不是产品失败。

### 5.1 假绿检查

- 每个 EV 的 surefire XML 汇总非零（765），前端 384；没有“0 tests”通过。
- skipped 总数为 0；未使用 `--no-file-parallelism`、未加重试、未跳过测试、未改测试命令。
- EV 命令与合同原文完全一致，日志头 `# command`/`# exit_code` 与记录一致，gate 重算 SHA-256 通过。

## 6. 仍 open 的 findings / legacy 项

### M01（历史遗留，均非本次新发现）

- F-002（P2，open）：`commands.security` 在 M01 时失败（pnpm-lock 1 critical/16 high、pom 1 high）；M02 TASK-0008 已做 B-06 处置，但本次**未重跑** `commands.security`，故不把该 finding 改为 fixed。
- F-004（P2，open）：Umi dev overlay console TypeError 与 antd 6 `Alert message` 废弃警告；M02 之后的页面已重写，但本次未重做 console 断言，保留历史状态。
- F-005（P2，open）：ProLayout beta 折叠按钮因 axe serious 被关闭；未在本次复核。
- F-006（P2，open）：TASK-0001 的 11 条工具安装 EV 无 commit attestation（git init 前），gate 仍报 legacy warning。
- F-007（P2，open）：MySQL Connector/J GPLv2+FOSS exception（DEC-0003=C），发布前仍需商业许可或替换驱动。
- F-008/F-009（P3，open）：RUNBOOK 旧口径、E2E 覆盖面历史限制。
- 11 条 `evidence_attestation` legacy warning 属预期，不影响 pass。
- **legacy/N-A**：M01 是工程基线模块（合同命令、agentscope 冒烟、威胁模型初稿），无用户可见 AC；Product 预验收/用户验收按 N/A 处理，不作为 gate 前置。

### M02

- F-009（P3，open）：J1 回答以原始 Markdown 展示。
- F-014（P3，open）：J2 首条检索片段来自标题段，信息量低。
- **Product/用户验收缺口（legacy 流程项）**：M02 模块没有 `product_acceptance` 记录（Product 预验收/用户最终验收为 `not-recorded`）。本评审是独立技术验收，不替代 Product 预验收或用户验收；M02 是用户可见模块，建议 Lead/Product 按协作规则补录或明确接受该历史缺口。

### 其他模块（均为旧 review 已接受的 P2/P3）

- M03：F-006～F-010 accepted（J2 双“引用清单”标题、PRODUCT 漂移、历史 TENANT 空间、平台来源审计可见性、TenantIsolation tag）。
- M10：F-001、F-005～F-015 accepted（OIDC collation、会话撤销、跨租户审计 details、CSV 边界、publicBaseUrl、OPS 项等）。
- M12：F-002～F-005、F-007 accepted P3；M12 无用户可见 AC，Product 验收 N/A。
- M13：F-001～F-006 accepted（部门字段契约、产物列表、stub 409/403/404、历史 TENANT、PRODUCT 文本、AntD Select a11y）。
- M15：F-003、F-005、F-006 accepted（antd 绿色 Tag 对比度、OIDC collation residual、两 issuer 夹具）。

### 项目级

- `state validate`：0 errors，1 warning（M11 planned 但有 done 任务，历史遗留，符合 M18 acceptance 允许项）。
- M14、M18 的 Product/用户验收仍为 pending（state 显示），不属于本任务范围。

## 7. 真实代码/测试缺陷

**本次未发现。** 7 次全量测试均全绿，覆盖率达标，无 skip、无 flake。若后续需要重新确认 E2E/a11y/security/真实栈/迁移回滚，应另立任务，不应把本次“证据重绑”当作这些维度的复验。

## 8. 实际命令与退出码

| #   | 命令                                                                                                            | 退出码 / 结果                              |
| --- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| 1   | `git status --short`                                                                                            | 空（干净）；结束时仅 `.riccon/**` 变更     |
| 2   | `git rev-parse HEAD`                                                                                            | `cb977f98a493bfb7db9f9d4b02731ac62e195053` |
| 3   | `riccon contract validate --project .`                                                                          | 0（OK，0 errors/0 warnings）               |
| 4   | `riccon task show TASK-0001/0008/0033/0065/0054/0051/0084`                                                      | 0，模块归属核对通过                        |
| 5   | `riccon evidence run --task TASK-0001 … -- './mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage'` | 0 → EV-0490                                |
| 6   | 同上 `--task TASK-0008`                                                                                         | 0 → EV-0491                                |
| 7   | 同上 `--task TASK-0033`                                                                                         | 0 → EV-0492                                |
| 8   | 同上 `--task TASK-0065`                                                                                         | 0 → EV-0493                                |
| 9   | 同上 `--task TASK-0054`                                                                                         | 0 → EV-0494                                |
| 10  | 同上 `--task TASK-0051`                                                                                         | 0 → EV-0495                                |
| 11  | 同上 `--task TASK-0084`                                                                                         | 0 → EV-0496                                |
| 12  | `riccon gate check --module M01/M02/M03/M10/M12/M13/M15 --project .`                                            | 全部 0（PASS）                             |
| 13  | 最终 gate 循环（单行：`set -e; for m in …; do …                                                                 |                                            | exit 1; done`） | 0 → EV-0498（EV-0497 superseded） |
| 14  | `riccon state validate --project .`                                                                             | 0（OK，1 legacy warning）                  |
| 15  | `riccon doctor --project .`                                                                                     | 0（OK，0 errors/0 warnings）               |

命令未做任何“为通过而追加”的调整：没有 `--no-file-parallelism`、没有重跑掩盖失败、没有 `|| true`、没有跳过测试。

## 9. 未验证 / 边界

- 本次只重录 **test/coverage** 并复核证据链；**E2E、a11y、security、性能、真实栈、迁移回滚**未重跑，仍绑定旧 EV，相关结论保持旧评审原样，不扩大。
- 新 review 的 `dod` 复制旧值；`dod_evidence` 的 acceptance/build/e2e/docs 仍指向旧 EV（未受影响），仅 `tests/coverage` 换为新 EV。
- `gate check` 会写 `.riccon/state.json` 的 `gate`/`updated_at`，`evidence run` 会更新任务 JSON 的 evidence 列表；这是内核行为，未手工编辑状态。
- 未提交 git、未改任务状态、未创建/修改模块或决策；TASK-0090 仍为 `in_progress`，M18 仍为 `building`。

## 10. 下一步（Lead）

1. 复核 7 份新 review 与 EV-0490..EV-0498，确认无异议后提交 `.riccon/**`；EV-0497 为 superseded，不得绑定 DoD。
2. 更新 TASK-0090 状态（done）与 M18 acceptance/模块收口；M18 模块 review 需另行独立编写（本报告不是 module review）。
3. 记录 M18 墙钟成本（`riccon module cost M18 --confidence estimated --source wall-clock-only …`）。
4. 决定 M02 Product 预验收/用户验收缺口与 M01 历史 open P2 的承接方式（建议另立任务，不在 M18 修）。
5. 若要对 7 个模块做完整模块级复验（E2E/a11y/security/真实栈），另立高风险验证任务，避免把本次证据回溯误读为全量复验。

## 11. 并发污染与重跑（F-004 收口，2026-11-20）

- **触发**：TASK-0094 首次全量 test/coverage（EV-0511，`eabb184`，`dirty=true`，exit 1）在 api 出现 5 个失败：`ChatScopeApiTest.stopGeneration_creatorOrRunInitiatorOnly`、`ChatStreamApiTest.stopGeneration_preservesContentAndAudits`、`AuditExportApiTest.list_filtersByStatus`、`AuditExportClockTest.download_shortTtlExpiresWithClock`、`AuditExportPlatformApiTest.platformApproval_singleAdminTenant`。
- **根因（已确认）**：Serena 的 Eclipse JDT LS（PID 51374）于 16:06 启动后改写 `target/classes`，与 EV-0511 的测试 JVM 并行；EV-0511 日志可见 `NoClassDefFoundError` 堆栈（`com.riccon.api.chat.service.ChatService$1`、`com.riccon.api.audit.repository.AuditExportRepository$2`、`com.riccon.worker.agentscope.HitlPermissionSmokeTest$1` 等），导致停止生成 500 与审计导出计数错误。
- **清理步骤**：kill JDT LS → 提交 `.serena/project.yml`（`ec6a650`）→ `./mvnw clean` → 用合同命令重跑；**EV-0511 已从 `TASK-0094.evidence` 移除**（`870760c`），不再参与 gate/DoD。
- **干净重跑结论**：`eabb184..ec6a650` 无 Java 源码变更；EV-0513（`ec6a650`，clean）Java 764 / 前端 384，0 fail/0 error/0 skip；EV-0511 中失败的 5 个类在 EV-0513 的 surefire XML 中均 0 failures/0 errors/0 skipped。EV-0512 build、EV-0514 E2E 33 passed、EV-0515 a11y 13 passed、EV-0516 security、EV-0517 gate 均 exit 0。
- **判定**：EV-0511 是工具并发污染导致的**环境性失败**，不是源码回归，也不是 retry 掩盖产品失败；清理后的 clean 重跑与最终全量可作为权威证据。EV-0511 已从任务证据移除，不再作为 DoD 证据；其日志/归档状态见下一条。
- **EV-0511 日志保留状态（evidence hygiene，记录校正）**：Lead 回执称 JSON/日志在清理中未保留、归档目录为空；核对结果：`.ricon/archive/evidence/TASK-0094/` 为空，`find .ricon -name 'EV-0511*'` 无结果；但 `find .riccon -name 'EV-0511*'` 仍命中 `.riccon/evidence/TASK-0094/EV-0511.json`（891 B）与日志（880,740 B）。因此准确表述是“**已从 `TASK-0094.evidence` 移除、未被任何任务/DoD 引用、未移动到归档路径**”，不能写“已归档”；截至本报告更新时点也不是“未保留”。若 Lead 后续删除文件，应将本节更新为“未保留/不可审计”；若保留，建议移动到 `.riccon/archive/evidence-superseded/TASK-0094/` 并记录实际路径。根因与 clean 重跑结论已记录，不影响 DoD。
- **front matter 说明**：本报告 front matter 的 `verdict: blocked` 是 14:16 时 M18 尚未收口的历史状态；当前 M18 的结论以 `REV-M18-001` 为准。
