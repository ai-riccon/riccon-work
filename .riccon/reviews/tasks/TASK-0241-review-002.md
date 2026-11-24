---
review_id: REV-TASK-0241-002
module_id: M06
task_id: TASK-0241
reviewer:
  agent: riccon/verifier
  session: ses_df003aeb8ffe4Y5D2p86eilPET
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - c1f8f19096cb02a1cdbc138c1b05ea7acb957484
  - d324e11f06fa189569175cdc5e82ddb16a1f3426
  - 98c88e8b0c0eb6572f2454deb3db667ed22f79c2
  - e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
head_commit: e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
code_commit: d324e11f06fa189569175cdc5e82ddb16a1f3426
evidence_head: e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
baseline_commit: 53a3c84
contract_commit: ca651583e2075a8e6fc11d2fa9093d9e8ea75765
previous_review: REV-TASK-0241-001
supersedes: REV-TASK-0241-001
related_reviews:
  - REV-TASK-0222-001
  - REV-TASK-0223-001
review_scope: >-
  M06 T04b 第二轮复审，仅核对证据门禁：TASK-0241.evidence 是否只保留 clean 证据、EV-1041 的
  commit/dirty/exit/hash 是否与日志一致、是否仍有 dirty 或非零退出证据会让 strict
  evidence_attestation 失败。功能 F-001/F-002/F-003/F-005 沿用 REV-TASK-0241-001 的通过结论，
  本轮不重审、不重跑功能测试，不运行 gate check，不替代 M06 模块 review、Product 预验收或用户验收。
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
  lint: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
dod_note: >-
  acceptance/build/tests/review/lint 沿用 REV-TASK-0241-001 的功能与测试复核结论（EV-1041 覆盖
  88 个 API 用例 + 12 个 ArchUnit 用例，0 failures/errors/skipped；上一轮独立复跑 56 API +
  12 ArchUnit 通过；a35d1f5 全仓 spotless 通过），本轮未重跑。evidence=true 是本次修复的复审结论：
  TASK-0241.evidence 只剩 EV-1041，且 EV-1041 的 commit/dirty/exit/hash 均校验通过。
  coverage/e2e/security/performance/accessibility 属 M06 模块 gate 与 T16a，本任务未运行。
findings:
  - id: F-001
    severity: P2
    status: fixed
    title: "F-001 幂等收敛：create/duplicate/publish 可选键，publish 资源级幂等"
    summary: >-
      沿用 REV-TASK-0241-001：c1f8f19 已让 create/duplicate/publish 接受可选 Idempotency-Key，
      publish 按 graph_sha256+runScope+departmentId 命中既有版本时不写第二版/第二条审计；
      run/retry/approval 键级幂等未改。本轮不重审功能，无新增证据问题。
    evidence: "REV-TASK-0241-001 F-001；EV-1041"
  - id: F-002
    severity: P2
    status: fixed
    title: "F-002 VALIDATE 可选 runScope/departmentId 并复用 PUBLISH 范围校验"
    summary: >-
      沿用 REV-TASK-0241-001：validate 端点接受可选范围参数，复用 WorkflowPublishScopeValidator；
      DEPARTMENT 缺 departmentId 或非 DEPARTMENT 带 departmentId 返回 400；只读不改 draftVersion、
      不写审计。本轮不重审功能，无新增证据问题。
    evidence: "REV-TASK-0241-001 F-002；EV-1041"
  - id: F-003
    severity: P2
    status: fixed
    title: "F-003 WorkflowContractM06Test 与 1.6.0-m06 同步"
    summary: >-
      沿用 REV-TASK-0241-001：契约测试断言 create/duplicate/publish 不再要求 Idempotency-Key，
      run/retry/approval 仍 required=true，validate 的 runScope/departmentId 可选且枚举封闭。
      本轮不重审功能，无新增证据问题。
    evidence: "REV-TASK-0241-001 F-003；EV-1041"
  - id: F-005
    severity: P2
    status: fixed
    title: "F-005 detail/get/saveDraft 的 canRun 与 T05 准入/RUNNABLE 同口径"
    summary: >-
      沿用 REV-TASK-0241-001：d324e11 让 get/saveDraft 对 owner 与普通主体一致按发布版本
      runScope 计算范围，detail 的 canRun 与 T05 requireRunnable/withinVersionScope 及 RUNNABLE
      列表一致。本轮不重审功能，无新增证据问题。
    evidence: "REV-TASK-0241-001 F-005；EV-1041"
  - id: F-006
    severity: P1
    status: fixed
    title: "TASK-0241 evidence 仅保留 clean EV-1041，dirty EV-1034/EV-1035 已移除"
    summary: >-
      HEAD e5f5586 的 .riccon/tasks/TASK-0241.json evidence 为 ["EV-1041"]；e5f5586 的 diff 明确
      删除 EV-1034/EV-1035，且 .riccon/tasks 与 .riccon/modules 中已无任何任务/模块引用这两条。
      EV-1041 记录 commit=d324e11（已确认是 HEAD 祖先）、dirty=false、exit_code=0、manual=false，
      output_sha256=680dc30acd47ee1c1018cefbc34d12d9d122ffbd05866ab2e6bfb17133349915、
      output_bytes=143869，与日志文件实测 SHA-256/字节数一致；日志 # command 与 # exit_code: 0
      头也与记录一致。按 gate.mjs:386-408，模块 evidence 只取任务卡引用，dirty 列表不含 EV-1041，
      strict 下 evidence_attestation 不再因 TASK-0241 失败；verifyEvidenceRecord 以
      requireAncestor/requireCommit 复核返回 {ok:true, errors:[]}。F-006 关闭。
    evidence: >-
      .riccon/tasks/TASK-0241.json:22-24；git show e5f5586 -- .riccon/tasks/TASK-0241.json；
      .riccon/evidence/TASK-0241/EV-1041.json:7-12；
      .riccon/evidence/TASK-0241/EV-1041-2026-11-25T07-39-58-075+08-00-*.log:1-6；
      gate.mjs:386-408；evidence.mjs:411
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前"
    title: "F-005 的 saveDraft 返回路径与租户管理员 canRun 分支缺少独立断言"
    summary: >-
      沿用 REV-TASK-0241-001：saveDraft 与 get 共用 detail()/withinScope，新增用例未直接断言
      owner 移出部门后保存返回的 canRun，也未单独断言 tenantAdmin 在 ONLY_SELF/DEPARTMENT 版本上
      canRun=true；T05 WorkflowRunScopeApiTest 已覆盖管理员运行准入。本轮不重审功能。
    accepted_reason: >-
      同一 detail()/withinScope 逻辑被 get/saveDraft 复用，管理员分支由 T05 运行准入用例独立覆盖；
      属于测试完整性改进，不改变当前可运行性判断，不影响本任务验收结论。
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/lead
    due: "M06 gate 前"
    title: "任务卡未把 F-005 写入 acceptance，baseline 由 219e196 改为 53a3c84"
    summary: >-
      沿用 REV-TASK-0241-001：Task 卡 baseline=53a3c84、end=d324e11，baseline..end 不覆盖
      F-001/F-002 主体提交 c1f8f19，且 acceptance 三条未列入 F-005。实际两个 T04b 提交的全部
      文件路径均在 write_scope 内，功能无缺口。本轮不重审功能。
    accepted_reason: >-
      仅影响任务卡追溯与 gate 的 baseline..end 视图，不改变代码/测试/证据有效性；模块 review
      应按 c1f8f19 + d324e11 逐提交复核 T04b，并在 M06 gate 前补任务卡 acceptance 或模块说明。
evidence:
  - EV-1041
reviewed_evidence:
  - EV-1041
created_at: "2026-11-25T07:58:00+08:00"
---

# REV-TASK-0241-002：M06 T04b 证据门禁第二轮复审

## 结论

**通过（pass）**。上一轮唯一阻断 F-006 已修复：`TASK-0241.evidence` 在 `e5f5586` 中只剩
`EV-1041`，`EV-1034`/`EV-1035` 已移除且全仓任务/模块不再引用；`EV-1041` 记录
`commit=d324e11`、`dirty=false`、`exit_code=0`，日志 SHA-256/字节数与记录一致，commit 是当前
HEAD 的祖先。按 `gate.mjs:386-408` 的 strict `evidence_attestation` 规则，TASK-0241 不再贡献
dirty 证据，证据门禁失败项已消除。功能 F-001/F-002/F-003/F-005 沿用上一轮通过结论，本轮不重审。

## 本轮验证了什么

| 检查项               | 观察                                                                                                        | 结论               |
| -------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------ |
| 任务卡 evidence 清单 | `.riccon/tasks/TASK-0241.json:22-24` = `["EV-1041"]`；工作区文件与 HEAD 一致                                | 只剩 EV-1041       |
| dirty ID 移除        | `git show e5f5586 -- .riccon/tasks/TASK-0241.json` 删除 EV-1034/EV-1035 两行                                | 已移除             |
| 残留引用             | `.riccon/tasks`、`.riccon/modules` 中无 EV-1034/EV-1035                                                     | 无残留引用         |
| EV-1041 task 绑定    | `task_id=TASK-0241`，`manual=false`                                                                         | 归属正确、可取证   |
| EV-1041 commit       | `d324e11f06fa189569175cdc5e82ddb16a1f3426`；`git merge-base --is-ancestor d324e11 HEAD` 通过                | 绑定当前 HEAD 祖先 |
| EV-1041 clean/exit   | `dirty=false`、`exit_code=0`                                                                                | clean pass         |
| EV-1041 输出一致性   | 实测 SHA-256 `680dc30a…49915`、143869 字节，与记录一致                                                      | hash/bytes 匹配    |
| 日志头一致性         | 日志 `# command` 与记录一致，`# exit_code: 0` 与记录一致                                                    | 非拼接/改写证据    |
| 机器校验             | `verifyEvidenceRecord(..., {taskId, requireAncestor:true, requireCommit:true})` → `{"ok":true,"errors":[]}` | 证据有效           |

## 为什么 strict `evidence_attestation` 不再失败

`gate.mjs:386-392` 只汇总**模块内任务卡引用的** evidence：

```js
const moduleEvidence = tasks.flatMap((task) =>
  (task.evidence || []).map((id) => evidenceById.get(id)).filter(Boolean),
);
const dirty = moduleEvidence.filter((record) => record.dirty === true);
```

因此：

- TASK-0241 只引用 EV-1041（`dirty=false`），不在 `dirty` 列表；
- EV-1034/EV-1035 虽仍作为历史日志留在 `.riccon/evidence/TASK-0241/` 目录，但没有任何任务卡
  或模块引用它们，不会进入 `moduleEvidence`，不触发 `evidence_attestation`；
- 当前 M06 任务卡引用的全部证据中 `dirty=true` 数量为 0；TASK-0241 不是任何 dirty 证据的来源。

本轮同时确认：M06 引用的非零退出证据只有 TASK-0240 的 EV-1022（历史负向证据，`dirty=false`，
且不是该任务最新证据；其最新 EV-1025 `exit=0`），`evidence_attestation` 只判定 `dirty`，故不
构成 strict 失败。此为只读快照，不构成 M06 模块 gate 结论。

## Findings 汇总

- **F-006（P1，fixed）**：dirty EV-1034/EV-1035 已从任务卡删除，EV-1041 clean 且 hash/commit
  校验通过。上一轮唯一阻断关闭。
- **F-001/F-002/F-003/F-005（P2，fixed）**：沿用 REV-TASK-0241-001 的功能复核，本轮不重审。
- **F-007/F-008（P3，accepted）**：沿用上一轮 accepted，owner/due/accepted_reason 已填写。

## 未验证

- 未重跑 F-001/F-002/F-003/F-005 的功能测试；本轮只复核证据字段、日志 hash/头与 gate 规则。
  功能结论依赖 REV-TASK-0241-001 的独立复跑与 EV-1041 原始日志。
- 未运行 `spotless:check`：`e5f5586` 只改 `.riccon/tasks/TASK-0241.json`、
  `.riccon/reviews/tasks/TASK-0241-review.md`、`.riccon/state.json`，不含 Java/TS；上一轮
  `a35d1f5` 的全仓 spotless 结论未被本轮提交改变。
- 未运行 M06 合同全量 build/test/coverage/E2E/a11y/security/perf，也未运行
  `riccon gate check --module M06`（按本轮任务要求）。模块 gate 仍需最终提交上的全量 EV、
  模块 review 与其他任务闭环。
- EV-1034/EV-1035 取证时的 dirty 工作树内容无法从日志重放；本轮只确认它们已不被引用。

## 剩余风险

1. **历史 dirty 日志仍留在目录**：EV-1034/EV-1035 的 JSON/日志仍在
   `.riccon/evidence/TASK-0241/`。当前不被引用，gate 不会读取其 dirty 字段；但若后续有人把它们
   重新写回任务卡，strict `evidence_attestation` 会再次失败。建议 M06 gate 前不要重新引用，或
   由 Lead/Builder 归档到 `.riccon/archive/evidence-superseded/`。
2. **TASK-0242 仍在 in_progress**：并行任务后续新增证据可能改变 M06 的模块级证据快照；本评审
   只对 TASK-0241 当前 HEAD `e5f5586` 负责，模块 gate 前需重新核对。
3. **F-008 追溯问题未修**：任务卡 acceptance 仍未列 F-005，baseline `53a3c84` 不覆盖 `c1f8f19`；
   模块 review 必须按两个 T04b 提交逐提交审计，不能只看 `baseline..end`。

## 是否可 done

**证据门禁已满足，TASK-0241 可以进入 done**（功能已在 REV-TASK-0241-001 通过；本轮 F-006
修复确认）。本评审不构成 M06 模块 gate 通过：还需其他 M06 任务 done、最终提交上的全量
build/test/coverage/E2E、模块 review、clean 工作树与 Lead 的 gate 操作。
