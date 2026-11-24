---
review_id: REV-TASK-0223-002
module_id: M06
task_id: TASK-0223
reviewer:
  agent: riccon/verifier
  session: ses_df00634bdffeULnyWV8W26T8zH
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-TASK-0223-001
reviewed_commits:
  - a35d1f572bf10c1bc75130cbc91c469a518e9769
  - 3bd8a1698d30e4b73ae9dadd7e28e19c24f66431
  - 70bcccb682d16dab7dd82f0a000e82af15a115c2
head_commit: e5f5586858a0d7c3cd42ac050a2f95e2a41a232e
code_commit: 70bcccb682d16dab7dd82f0a000e82af15a115c2
format_commit: a35d1f572bf10c1bc75130cbc91c469a518e9769
evidence_head: 3bd8a1698d30e4b73ae9dadd7e28e19c24f66431
baseline_commit: d130da6c49149c32163d5942420ad18c0ddf9f26
review_scope: >-
  T05 第二轮复审：只审 REV-TASK-0223-001 F-001 的格式修复（a35d1f5）、证据 EV-1044/EV-1045
  与 3bd8a16 的任务/证据记录。复核提交范围、无功能/测试逻辑变更、spotless:check、T05 5 个测试类
  加 WorkflowContractM06Test，并确认 F-002 仍由 T04b 承接；不重审 T05 全部功能/安全，不评审
  T04b/T10b/T16a，不替代模块 gate、Product 预验收或用户最终验收。
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
  acceptance/build/tests/evidence/lint=true 仅指本轮格式修复与 T05 定向回归：a35d1f5 只做格式化
  （10 个文件去空白/token 等价，仅 import 排序与换行变化）；独立重跑 spotless:check exit 0、
  T05 24 用例加 WorkflowContractM06Test 6 用例全部 0 failures/0 errors/0 skipped；
  EV-1044/EV-1045 的 commit/dirty/exit/hash/bytes 与记录一致。coverage/e2e/security/performance/
  accessibility 属 T16a 与模块 gate，本轮不适用；T05 功能/安全结论沿用 REV-TASK-0223-001。
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: riccon/builder-b1
    due: "T05 复审前（已满足）"
    title: "T05 变更文件未通过 spotless:check，CI lint 门禁失败"
    summary: >-
      上一轮在 70bcccb 上复现 10 个 run 包文件格式违规、spotless:check exit 1。a35d1f5 仅对这
      10 个文件执行 spotless 格式化，未改其他路径；token 归一化对比 10/10 等价，无功能/测试逻辑
      变更。独立重跑 `./mvnw -q spotless:check` exit 0，CI 的同一命令
      （`.github/workflows/ci.yml:199-200`）已可通过。
    evidence: >-
      a35d1f5 name-status 10 文件均在 apps/api/src/{main,test}/java/.../workflow/run/**；
      EV-1044 commit=a35d1f5、dirty=false、exit=0、sha256 与日志一致；独立复跑 exit 0。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T04b/TASK-0241 收口）"
    title: "F-005：detail.canRun 的 owner 例外由 T04b 承接，T05 不依赖该旗标"
    summary: >-
      T05 运行准入独立按用户所选版本的 run_scope + department_id 判定，不读取 detail.canRun；
      该残余只影响 T10 详情页“可运行”按钮提示。T04b 已提交 d324e11 修复 canRun，但 T04b 当前评审
      REV-TASK-0241-001 verdict=fail（证据 dirty），F-005 的关闭仍需 T04b 后续评审确认。本轮不把
      T04b 的未复验提交计入 T05 证据，也不改变上一轮的 accepted 归属。
    accepted_reason: >-
      不构成越权：运行端点由服务端独立判定，范围外统一 404，T05 不读取该旗标；误导仅限详情页
      按钮提示，且已明确转 T04b/TASK-0241，不影响 T05 的行为与安全边界。
    evidence: >-
      REV-TASK-0223-001 F-002；d324e11（T04b）；REV-TASK-0241-001 verdict=fail（F-005 待 T04b 收口）。
evidence:
  - EV-1044
  - EV-1045
reviewed_evidence:
  - EV-1037
  - EV-1044
  - EV-1045
notes: >-
  复审期间并行任务把 HEAD 从 3bd8a16 推进到 e5f5586（4448778 仅改 apps/web 两个测试文件，
  e5f5586 仅改 .riccon 评审），`git diff --name-only a35d1f5 HEAD -- apps/api apps/worker platform
  contracts docs AGENTS.md pom.xml` 为空，故当前工作树的独立复跑仍代表 a35d1f5 的后端代码。
  工作树非 .riccon 路径干净；按任务要求未运行全量 build/coverage/E2E/axe/Trivy/k6 与 gate check，
  独立复跑结果只写入本评审正文，未新增 EV。
created_at: "2026-11-25T07:56:50+08:00"
---

# REV-TASK-0223-002：M06 T05 格式修复复审

## 结论

**通过（pass）**。上一轮唯一阻断 F-001 已修复：`a35d1f5` 只对 T05 的 10 个 `workflow/run/**`
文件做 spotless 格式化，无功能/测试逻辑变更；独立重跑全仓 `spotless:check` exit 0，
T05 24 个用例加 `WorkflowContractM06Test` 6 个用例全部 0 failures/0 errors/0 skipped；
EV-1044/EV-1045 的 commit、dirty、exit 与 hash 复核一致。未发现新的格式或逻辑问题，
上一轮 F-002（F-005）维持 accepted 并继续由 T04b/TASK-0241 承接，不阻塞 T05。

## 复审范围

- 任务卡：`.riccon/tasks/TASK-0223.json`，`end_commit=70bcccb`、状态 `review`、
  证据 `EV-1037/EV-1044/EV-1045`（`3bd8a16` 追加后两项）。
- 格式修复提交 `a35d1f5`（父提交 `4e302e7`，T10b）。
- 证据提交 `3bd8a16`：仅新增 EV-1044/EV-1045 日志与 JSON，并更新 `.riccon/state.json`、
  `.riccon/tasks/TASK-0223.json`。
- 不重审 T05 的幂等/准入/配额/并发/取消/重试/审计功能面（结论见 REV-TASK-0223-001），
  不评审 T04b 的 F-005 代码修复，不评审 T10b/T10 UI、T16a 或 M06 模块 gate。

## 格式与范围核对

### 提交范围

`git show --name-status a35d1f5` 只有 10 个文件，全部位于：

- `apps/api/src/main/java/com/riccon/api/workflow/run/{controller,persistence,repository,service}/**`
- `apps/api/src/test/java/com/riccon/api/workflow/run/**`

未触碰 contracts、PRODUCT、AGENTS、迁移、worker、web、chat/research 或 CI 配置。

### 无逻辑变更

- 对 10 个文件分别取 `a35d1f5^` 与 `a35d1f5`，剔除 import 后去掉全部空白做 token 对比：
  10/10 完全相等；import 集合排序后也相等。说明只有换行、缩进、参数折行与 import 排序变化。
- `git diff --name-only 70bcccb a35d1f5^ -- <run main/test 路径>` 为空，说明格式化前的内容
  就是 T05 代码提交 `70bcccb` 的内容；`70bcccb..a35d1f5` 之间也只有 `a35d1f5` 触碰过 run 路径。
- `end_commit` 保持 `70bcccb` 合理：格式提交不带其他任务文件，T05 代码范围仍可按文件级追溯。

### spotless

- 独立执行 `./mvnw -q spotless:check`：**exit 0**。
- 复核时 HEAD 已因并行任务推进到 `e5f5586`，但
  `git diff --name-only a35d1f5 HEAD -- apps/api apps/worker platform contracts docs AGENTS.md
pom.xml` 为空（期间提交只改 apps/web 测试与 .riccon），工作树非 .riccon 路径干净，
  因此该结果等价于 `a35d1f5` 的 Java 源码。
- CI `.github/workflows/ci.yml:199-200` 的“代码格式检查”正是
  `./mvnw -q spotless:check`，上一轮的 lint 门禁阻断已消除。

## 证据复核

| 证据    | 记录值                                                                                           | 复核结果                                                                                                                        |
| ------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| EV-1044 | `./mvnw -q spotless:check`；commit `a35d1f5`；dirty=false；exit=0；249 bytes                     | `sha256=1d0b82e0…c41f` 与日志一致；日志/耗时（07:48:59–07:49:06）与记录一致；独立复跑同样 exit 0                                |
| EV-1045 | 5 个 T05 测试类 + `WorkflowContractM06Test`；commit `a35d1f5`；dirty=false；exit=0；177615 bytes | `sha256=8dd2b2e5…9b30` 与日志一致；日志含 6 个测试类实际启动记录；独立复跑 exit 0，30 tests / 0 failures / 0 errors / 0 skipped |
| EV-1037 | 上一轮已复核的 T05 前端后续提交上的定向证据                                                      | 未发现被改写；本轮沿用其功能结论，不重复复跑                                                                                    |

### 独立复跑（当前工作树，后端等同 `a35d1f5`）

| 命令                                                                                                                                                                                                                                                      | 结果                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `./mvnw -q spotless:check`                                                                                                                                                                                                                                | exit 0（07:52 前后）                                                                                                                            |
| `./mvnw -q -pl apps/api -am test -Dtest=WorkflowRunAdmissionApiTest,WorkflowRunScopeApiTest,WorkflowRunLifecycleApiTest,WorkflowRunFeatureDisabledApiTest,WorkflowRunAuditFailClosedTest,WorkflowContractM06Test -Dsurefire.failIfNoSpecifiedTests=false` | exit 0；Admission 8、AuditFailClosed 3、FeatureDisabled 3、Lifecycle 6、Scope 4、Contract 6，合计 30，全部 0/0/0；surefire 报告时间 07:53–07:55 |

按任务要求，独立复跑未录成新 EV，也未运行全量 build/coverage/E2E/axe/Trivy/k6 或 gate check。

## F-001 / F-002 闭环

### F-001（P2，fixed）

上一轮在 `70bcccb` 干净导出上复现 10 个文件格式违规、`spotless:check` exit 1；本轮
`a35d1f5` 恰只修改这 10 个文件并使其通过，独立复跑 exit 0。修复是机械的、无行为变化，
F-001 关闭。

### F-002（P2，accepted）

F-005 的 `detail.canRun` owner 例外不在 T05 write_scope，T05 运行准入不读取该字段，
不构成越权；T04b 已提交 `d324e11`，但 `REV-TASK-0241-001` 当前 verdict=fail（证据 dirty），
F-005 尚未在 T04b 评审中闭环。按上一轮归属保持 accepted：owner=`riccon/builder-b2`，
due=M06 gate 前（T04b/TASK-0241 收口），accepted_reason 见 front matter。该残余不阻塞 T05。

## 未验证

- T05 全量功能/安全复验：沿用 REV-TASK-0223-001（功能/安全已通过），本轮不重复。
- T04b 的 F-005 修复正确性：由 T04b 任务评审负责；当前 T04b 评审 fail，未把 `d324e11`
  算作 T05 证据。
- T10b/T16a、M06 模块 build/coverage/E2E/a11y/security/perf、迁移演练、gate check：
  按任务要求未运行。

## 剩余风险

1. **证据提交落在含其他任务的 commit 上**：`a35d1f5` 的父提交是 `4e302e7`，包含 T04b
   `d324e11` 与 T10b 前端提交；但 run 路径相对 `70bcccb` 只有格式化变化，且任务 `end_commit`
   仍为 `70bcccb`。模块级追溯应按文件范围而非仅按 commit 归属。
2. **F-005 尚未在 T04b 关闭**：T04b 当前评审 fail；若 M06 gate 前 T04b 未通过，F-002 的
   accepted 责任仍在 builder-b2，不应被当作已修复。
3. **上一轮功能残余未变**：创建重放响应聚合、重试键级重放、停用流程后的重试、RUNNING 取消/
   部分结果、平台管理员 cancel/retry、到期运行等风险仍由 T08/T16a 覆盖，本轮未重新评估。
4. **本轮无模块级证据**：pass 只代表 T05 格式修复与定向回归通过，不代表 M06 模块 gate 或
   Product/用户验收通过。

## 是否可 done

**T05 可结束 review 进入 done**（格式阻断已消除、定向回归全绿、证据一致、无开放 P0/P1，
F-002 accepted 且有 owner/due/accepted_reason）。M06 模块 done 前仍须完成模块 gate 和
T04b 对 F-005 的收口；本评审不修改源码/测试/迁移/契约/PRODUCT/AGENTS/.riccon 状态，
未运行 gate check。
