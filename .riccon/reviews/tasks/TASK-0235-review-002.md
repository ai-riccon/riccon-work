---
review_id: REV-TASK-0235-002
module_id: M06
task_id: TASK-0235
reviewer:
  agent: riccon/verifier
  session: ses_def793c08ffe7c2Yr5mhgk9CVH
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-TASK-0235-001
reviewed_commits:
  - 26c4d578f246257924364d03e213f0fb8922510f
  - 1d106efedcedfd3f6d5cee3ef11982f2a7aedecd
  - 59988e0c962e8453a19b17001851e8350eb20974
  - 0fea9304a4509904cca84ea61cce9c2c0339fbf3
  - 3d397dd95892e5b586b54a61b874a94bb1c874aa
  - 3ecdffc108ace11c8567b5ba7d95a3d1124e831e
head_commit: 3ecdffc108ace11c8567b5ba7d95a3d1124e831e
code_commit: 1d106efedcedfd3f6d5cee3ef11982f2a7aedecd
evidence_commit: 3ecdffc108ace11c8567b5ba7d95a3d1124e831e
baseline_commit: 26c4d578f246257924364d03e213f0fb8922510f
task_card_baseline_commit: 26c4d578f246257924364d03e213f0fb8922510f
out_of_scope_commits:
  - 580849392aaad5d690b6ed074138dcd7b8877b1d
  - 5a951e47902b7ce254f83422d1639b72eb3e4691
  - 98740f6f5ebec182fa89905b79b791d3e3f6bdf0
  - 33bcea8ba4ef176a5384a07b1040bb16ebf96c76
review_scope: >-
  M06 T14（TASK-0235）第二轮独立任务评审：只审证据门禁与上一轮残余。核对任务卡 evidence 是否仅为
  EV-1064/EV-1065，复核两条 EV 的 commit/dirty/exit/cwd/hash/bytes/祖先关系，独立重跑 EV-1064/1065
  命令（24 backend、18 frontend + typecheck + eslint），确认 T14 代码/测试不回归；关闭
  REV-TASK-0235-001 F-001（clean EV 主工作树取证）与 F-002（copy 幂等文案，59988e0）。不重审
  T14 全部实现（代码仍为 1d106ef，上一轮已逐项评审），不评审 T14b（TASK-0244）路由/导航/EV-1066、
  不评审 T16a E2E/axe、不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  typecheck: true
  review: true
  evidence: true
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
  - migration
dod_note: >-
  acceptance 记 true 按 **pass (with residual)** 理解：T14 代码仍为 1d106ef，上一轮已评审 API +
  模板库页面并独立复跑；本轮复核 clean EV-1064/1065 与 18 个前端测试（其中 T14 13 个、T14b
  studio-nav 5 个），AC-10 的真实浏览器 E2E-M06-07/axe/375px 仍归 T16a。build 不在 EV-1064/1065
  命令中，但 T14 代码自 1d106ef 未变，上一轮已在干净导出独立执行 `pnpm --filter @riccon/web build`
  通过，T14b EV-1066 也包含当前 web 生产构建；故不因本轮未重复 build 判失败。evidence 记 true：
  EV-1064/1065 均为主工作树、dirty=false、exit=0，hash/bytes 与日志一致。
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "REV-TASK-0235-001 F-001 已关闭：EV-1064/1065 在主工作树干净树重录"
    summary: >-
      任务卡 `.riccon/tasks/TASK-0235.json:18-21` 的 evidence 已收敛为 `[EV-1064, EV-1065]`，
      `3ecdffc` 的 diff 明确删除 EV-1058/EV-1060，仅保留两条 clean EV。EV-1064/EV-1065 的
      commit 均为 `0fea930`（HEAD 祖先），dirty=false、exit_code=0、manual=false，
      cwd=`/Users/ricky/Documents/projects/riccon-work`（存在），日志第 2 行 `# cwd` 同为主工作树；
      output_bytes 分别为 24061/1166，SHA-256 分别为
      `9001f602b4529e1192a0b467ec2f8f65e4ed7db43edf68326f58d9acd346b5eb` /
      `828f08994f37a39e4d85976d479e33f9bac9be00df25ed25462dfde76d48587e`，与记录一致。
      旧 EV-1058/EV-1060 文件保留在 `.riccon/evidence/TASK-0235/` 作为历史，但 tasks/modules/state
      中无引用。评审时主工作树 `git status` 为空。F-001 关闭。
    evidence: >-
      .riccon/tasks/TASK-0235.json:18-21；git show 3ecdffc -- .riccon/tasks/TASK-0235.json；
      .riccon/evidence/TASK-0235/EV-1064.json:7-15；
      .riccon/evidence/TASK-0235/EV-1065.json:7-15；
      .riccon/evidence/TASK-0235/EV-1064-2026-11-25T11-08-08-646+08-00-*.log:1-6；
      .riccon/evidence/TASK-0235/EV-1065-2026-11-25T11-08-42-334+08-00-*.log:1-6；
      docs/CODING-STANDARDS.md:201；docs/COLLABORATION.md:75
  - id: F-002
    severity: P3
    status: fixed
    title: "REV-TASK-0235-001 F-002 已关闭：copy 幂等文案在 59988e0 收敛"
    summary: >-
      `59988e0` 把 `copyWorkflowTemplate` 从引用通用 `IdempotencyKey` 改为内联参数，并明确
      “M06 的 Idempotency-Key 仅做长度/兼容校验，不承诺键级重放；每次成功复制生成新草稿并写审计，
      重复请求可能产生多个副本；前端应防重复提交”（contracts/openapi.yaml:3216-3231）；
      `contracts/README.md:99,145` 也把 create/duplicate/template copy 统一为不承诺键级重放。
      实现未变，仍为 `WorkflowTemplateController.java:143-158` 只校验 8..128，
      `WorkflowTemplateApiTest.java:182-193` 断言重复复制产生新草稿；EV-1064 的
      `WorkflowContractM06Test` 重跑通过。F-002 关闭。
    evidence: >-
      git show 59988e0 -- contracts/openapi.yaml contracts/README.md；
      contracts/openapi.yaml:3216-3231；contracts/README.md:99,145；
      apps/api/src/main/java/com/riccon/api/workflow/template/controller/WorkflowTemplateController.java:143-158；
      apps/api/src/test/java/com/riccon/api/workflow/template/WorkflowTemplateApiTest.java:182-193；
      EV-1064
evidence:
  - EV-1064
  - EV-1065
reviewed_evidence:
  - EV-1064
  - EV-1065
notes: >-
  任务卡 baseline=26c4d57、end_commit=1d106ef（代码提交）；证据提交为 0fea930（EV 录制）、
  3d397dd（EV 文件/任务卡）与 3ecdffc（evidence 收敛）。评审时 HEAD=3ecdffc，工作树干净。
  1d106ef..HEAD 的 apps 改动只有 T06a a7870c4 与 T14b 5808493；T14 自身的模板目录新增文件仅为
  T14b 的 studio-nav.tsx/studio-nav.test.tsx 与 templates.css 追加，T14 的 index.tsx/template-api
  未变。T14b clean EV-1066 已记录，另开评审，不计入本 review。独立复跑未新增 EV，仅用于复核；
  EV-1064 日志因 `-q` 无 surefire 汇总，24 个测试数由独立复跑的 4 个 surefire XML 复核。
created_at: "2026-11-25T11:17:00+08:00"
---

# REV-TASK-0235-002：M06 T14 第二轮独立评审

## 结论

**通过（pass）**。上一轮唯一 P1 `REV-TASK-0235-001 F-001` 已关闭：EV-1064/EV-1065 改为在
主工作树 `/Users/ricky/Documents/projects/riccon-work` 录制，`dirty=false`、`exit_code=0`、
commit `0fea930` 是 HEAD 祖先，hash/bytes 与日志一致；任务卡 evidence 已收敛为仅
`[EV-1064, EV-1065]`，旧 EV-1058/EV-1060 仅保留文件、不再被任务/模块引用。F-002 的 copy 幂等文案
已由 `59988e0` 收敛。独立重跑 EV-1064/EV-1065 命令：后端 24/24、前端 18/18，typecheck/eslint
通过，未发现新 P1/P2。T14b 路由/导航/EV-1066 与 T16a E2E/axe 仍按独立任务处理，不并入 T14。

## 证据门禁

### 任务卡与引用收敛

- `.riccon/tasks/TASK-0235.json:18-21`：evidence 为 `[EV-1064, EV-1065]`，不含 EV-1058/EV-1060。
- `3ecdffc` 的 diff 只改任务卡 evidence 列表与 `updated_at`，明确删除 EV-1058/EV-1060。
- `grep` 复核：`.riccon/tasks`、`.riccon/modules`、`.riccon/state.json` 中没有 EV-1058/EV-1060
  引用；旧 JSON/日志仍保留在 `.riccon/evidence/TASK-0235/`，属于历史记录，不参与 done。
- 任务卡 `end_commit=1d106ef` 是代码提交；EV 录制提交为 `0fea930`，证据记录/收敛提交为
  `3d397dd`/`3ecdffc`，三者均为 HEAD 祖先。`end_commit` 与 EV commit 不同不构成失败，代码提交
  与证据提交在 review 中分别记录。

### EV-1064 / EV-1065 字段复核

| 项           | EV-1064                                               | EV-1065                                |
| ------------ | ----------------------------------------------------- | -------------------------------------- |
| task_id      | TASK-0235                                             | TASK-0235                              |
| command      | 后端模板/契约/定义/平台租户定向测试                   | 前端模板库 vitest + typecheck + eslint |
| commit       | `0fea9304a4509904cca84ea61cce9c2c0339fbf3`            | 同左                                   |
| dirty        | false                                                 | false                                  |
| exit_code    | 0                                                     | 0                                      |
| manual       | false                                                 | false                                  |
| cwd          | `/Users/ricky/Documents/projects/riccon-work`（存在） | 同左                                   |
| output_bytes | 24061（与日志一致）                                   | 1166（与日志一致）                     |
| SHA-256      | `9001f602…6b5eb`（与日志一致）                        | `828f0899…587e`（与日志一致）          |
| HEAD 祖先    | 是                                                    | 是                                     |

两条日志第 2 行的 `# cwd` 都是主工作树；评审时 `git status --short --untracked-files=all` 为空，
主工作树干净。证据记录时间 11:08，录制提交 `0fea930` 时间 11:05:36，之后才提交 EV 元数据，
时间顺序自洽。

## 独立复核

### EV-1064（后端）

复核命令与 EV 完全一致：

```text
./mvnw -q -pl apps/api -am test \
  -Dtest='WorkflowTemplateApiTest,WorkflowContractM06Test,WorkflowDefinitionApiTest,WorkflowPlatformTenantFilterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

当前 HEAD（`3ecdffc`，T14 代码仍为 `1d106ef`）上 exit 0；surefire XML：

- `WorkflowTemplateApiTest`：5 tests，0 failures / 0 errors / 0 skipped
- `WorkflowContractM06Test`：6 tests，0/0/0
- `WorkflowDefinitionApiTest`：8 tests，0/0/0
- `WorkflowPlatformTenantFilterTest`：5 tests，0/0/0

合计 **24 tests，0/0/0**。EV-1064 日志因 `-q` 没有 `Tests run` 汇总，24 个测试数由上述独立
surefire XML 复核；T14 的列表/复制/契约/定义回归/平台租户守卫覆盖不变。

### EV-1065（前端）

复核命令与 EV 完全一致：

```text
pnpm --filter @riccon/web exec vitest run src/pages/workflow/templates
pnpm --filter @riccon/web typecheck
pnpm --filter @riccon/web exec eslint --max-warnings=0 src/pages/workflow/templates
```

vitest exit 0：**3 test files / 18 tests passed**；typecheck exit 0；eslint exit 0。
18 个测试 = T14 的 `index.test.tsx` 9 + `template-api.test.ts` 4（13 个） + T14b 已提交的
`studio-nav.test.tsx` 5 个。T14 的 13 个测试全部包含并通过；T14b 的 5 个测试随目录命令一起通过，
T14b 另有 clean EV-1066，故不构成 T14 失败或掩盖。EV-1065 日志明确 `3 passed / 18 passed`，
typecheck 与 eslint 在命令链中 exit 0。

### 代码/测试不回归

- T14 代码提交仍为 `1d106ef`；上一轮已对该提交逐项评审 API/前端/数据/范围，未发现新问题。
- `1d106ef..HEAD` 的 `apps` 改动只有 T06a `a7870c4` 与 T14b `5808493`；T14 的
  `index.tsx`/`template-api.ts`/`api` 模板后端文件未变，T14b 只在同目录新增 `studio-nav.*` 并向
  `templates.css` 追加样式。本轮的 24 backend + 18 frontend 复跑证明 T14 代码/测试未回归。
- 未运行 `gate check`（按任务要求），未新增 EV；独立复跑日志在临时目录，仅供本 review 复核。

## F-001 关闭

上一轮 F-001 的失败原因是：EV-1058/EV-1060 在临时 detached worktree 录制，cwd 已删除，违反
`docs/CODING-STANDARDS.md:201` 与 `docs/COLLABORATION.md:75` 的主工作树取证规则。

本轮关闭依据：

1. EV-1064/EV-1065 的 cwd 是主工作树 `/Users/ricky/Documents/projects/riccon-work`，日志头一致；
2. `dirty=false`，评审时主工作树 `git status` 为空；EV 录制提交 `0fea930` 是 HEAD 祖先；
3. hash/bytes 与日志一致，exit 0、manual false；
4. 任务卡 evidence 只保留 EV-1064/EV-1065，EV-1058/EV-1060 不再被任务/模块/state 引用；
5. 独立重跑两条命令，24 backend + 18 frontend/typecheck/eslint 均通过。

F-001 标记 **fixed**。旧的 EV-1058/EV-1060 文件作为历史保留，不再作为 done 依据。

## F-002 关闭

`59988e0` 已把 copy 端点的幂等语义写清楚：

- `contracts/openapi.yaml:3216-3231`：copy 不再引用通用 `IdempotencyKey`，改为内联参数；
  description 明确“仅做长度/兼容校验，**不承诺键级重放**；每次成功复制生成新草稿并写审计，
  重复请求可能产生多个副本；前端应防重复提交”。
- `contracts/README.md:99,145`：把 `create/duplicate/template copy` 统一为不承诺键级重放。
- 实现未变，仍为 `WorkflowTemplateController.java:143-158` 只校验 8..128；
  `WorkflowTemplateApiTest.java:182-193` 断言第二次复制生成新草稿；EV-1064 的
  `WorkflowContractM06Test` 重跑通过。

F-002 标记 **fixed**，不作为 T14 fail。

## 残余与未验证

- **T14b（TASK-0244）**：路由/导航已提交 `5808493`，clean EV-1066 已记录（commit `0fea930`，
  dirty=false，web 全量 test/typecheck/lint/build）；本评审不验收 T14b，其独立 review/结论另开。
- **T16a**：E2E-M06-07（真实浏览器从模板库复制→编辑器→校验/发布/运行→报告）、axe/`@a11y`、
  真实键盘旅程、真实 375px 无横向滚动仍未执行；AC-10 按 pass (with residual) 记录，不是模块级
  E2E pass。
- **M06b**：模板升级提示、`latestBuiltInVersion`、用户模板共享/私有模板 404 与审计归 M06b
  AC-11/25；T14 未实现，符合 ADR §12。
- **M06 模块 gate**：coverage/security/perf/migration 与全量 test/coverage/E2E 由模块 review 另跑；
  本 review 不代表 M06 模块通过。
- EV-1065 的目录命令包含 T14b 的 5 个 `studio-nav` 测试（18 = T14 13 + T14b 5）；T14 的 13 个
  全部通过，T14b 另有 clean EV-1066，故不判失败，但在证据口径上明确记录，避免误认为 18 个全是
  T14 新测试。

## Findings

- **F-001（P1，fixed）**：clean EV-1064/1065 主工作树取证，任务卡 evidence 收敛，旧 EV 不再引用。
- **F-002（P3，fixed）**：copy 幂等文案在 `59988e0` 收敛，不再承诺键级重放。
- 无新增 P0/P1/P2/P3；无 open finding。

## 结论

**pass**。T14 的 clean EV、任务卡 evidence、F-001/F-002 残余均已完成；未发现新的阻塞项。T14 可
进入 done 流程（由 Lead/模块 gate 按流程处理），但 T14b、T16a 与 M06 模块 gate 仍需按各自任务
和模块 review 验收。
