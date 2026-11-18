---
review_id: REV-M11-DOCS-001
module_id: M11
task_id: TASK-0035
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M11_TASK-0036_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [da7c00a, 0a106f2]
head_commit: c14eae2
created_at: 2026-11-18T16:03:00+08:00
dod:
  acceptance: true
  review: true
  docs: true
  evidence: true
dod_not_applicable:
  - build
  - tests
  - coverage
  - e2e
dod_note: "docs-only 任务：未运行 ./mvnw verify、pnpm、E2E、a11y、security、perf；这些命令不适用于本次文档规范评审，不用「未运行」冒充 pass。"
findings:
  - id: F-001
    title: "PRODUCT 状态表标 pass，未 footnote REV-TASK-0033-002 已 accepted 的 PRODUCT 文档漂移"
    severity: P2
    status: accepted
  - id: F-002
    title: "CODING-STANDARDS/AGENTS 将 DATETIME(6) UTC 归因到 ADR-0008/0012，但 ADR-0008 未规定时间存储类型"
    severity: P3
    status: accepted
  - id: F-003
    title: "ACCEPTANCE.md 结果值列表缺 pending，与表格实际使用不一致"
    severity: P3
    status: accepted
  - id: F-004
    title: "ARCHITECTURE 状态表的 ADR 归属与「本次评审」记录不够可追溯，用户批准行语义混用"
    severity: P3
    status: accepted
  - id: F-005
    title: "TASK-0035 自身证据仅覆盖 contract validate，未覆盖 acceptance 中的 git diff --check 与 state validate"
    severity: P3
    status: accepted
evidence:
  - EV-0308
  - EV-0309
---

# M11 文档规范独立评审（REV-M11-DOCS-001）

## 结论

**verdict: pass**。M11 合同与六份文档的规范升级整体诚实、可追溯：

- 合同只新增 `project.timezone: Asia/Shanghai`，未改产品目标、users、MVC、non-goals、stack、commands、DoD；
- `contract validate --strict` 与 `state validate` 均为 0 errors / 0 warnings；
- PRODUCT/ARCHITECTURE 状态表把 M01/M02 标 legacy、M03 引用真实 DEC/ADR/review、M10 标 pending，未伪造 pass；
- ARCHITECTURE 状态表令 ADR-0018 保持 `proposed`，用户/Ops/安全均为 pending 且有理由；
- ADR README 只改模板与规则，已有 ADR 正文/结论未改；
- 全部改动限于六份文档与 `.riccon` 状态/模块/任务/证据，无源码、迁移、契约、威胁模型改动。

**P0/P1 为 0**。开放项为 1 个 P2（F-001）与 4 个 P3，均已 accepted 并写明承接位置，不阻断 M11 文档任务关闭；其中 F-001 是本次评审要求重点判断的 PRODUCT 已知漂移问题，详见下文。

## 审查范围与命令

环境：干净树，`HEAD c14eae2`，工作区 `git status --short` 为空。

| 命令                                        | 退出码 | 结果                                                            |
| ------------------------------------------- | ------ | --------------------------------------------------------------- |
| `git status --short`                        | 0      | 空；HEAD `c14eae2`                                              |
| `git show --stat da7c00a 0a106f2`           | 0      | da7c00a 改六文档 + `.riccon`；0a106f2 仅 `tasks/` 移动 + README |
| `git show --summary --find-renames 0a106f2` | 0      | `R100` 两个任务级评审；README 新增                              |
| `git diff --check`                          | 0      | 无空白错误                                                      |
| `contract validate --strict --project .`    | 0      | `OK (0 errors, 0 warnings)`；EV-0308                            |
| `state validate --project .`                | 0      | `OK (0 errors, 0 warnings)`；EV-0309                            |

另做了只读核对：合同受管区块前后 diff、`8c905d0..HEAD` 全量 name-only、ADR 目录 diff、M03 模块/任务/证据 diff、EV-0308 JSON 与日志、DEC-0010/0011/0014/0015 的 owner/status、REV-TASK-0033-002 的 F-003..F-005。

**未运行**：`./mvnw verify`、pnpm build/test、E2E、a11y、security、perf。这是 docs-only 评审；按任务要求不跑全量，不把文档复审伪装成代码验证。

## 逐项对账

### 1. 合同

| 检查                                                              | 结果 | 证据                                                                                    |
| ----------------------------------------------------------------- | ---- | --------------------------------------------------------------------------------------- |
| `project.timezone: Asia/Shanghai` 存在                            | pass | `AGENTS.md:16`                                                                          |
| 合同只改时区，未改产品目标/users/MVC/non-goals/stack/commands/DoD | pass | 受管区块前后 diff 仅 `+  timezone: Asia/Shanghai` 一行；`git show da7c00a -- AGENTS.md` |
| `contract validate --strict` 0 error/0 warning                    | pass | EV-0308，exit 0，`manual: false`，`dirty: false`，commit `da7c00a`                      |
| `contract sync` 后 sha 一致                                       | pass | `.riccon/project.json` sha 更新为 `e3e9357…`；严格校验通过                              |

结论：合同变更边界干净，没有借 M11 改产品范围或技术栈。

### 2. 协作约定

`AGENTS.md:369-374` 六条新增覆盖：

| 要求                                                                                    | 落点 | 判断                                   |
| --------------------------------------------------------------------------------------- | ---- | -------------------------------------- |
| Product 评审链（Lead 完整性 → 用户范围 → Architect 可行性 → Verifier 可验证性）         | 369  | 完整                                   |
| Architect/ADR 评审链（Lead、用户〔不可逆/高成本〕、Verifier、Ops；未批准保持 proposed） | 370  | 完整                                   |
| Builder 只到 review、不自行 done；高风险任务独立 Verifier                               | 371  | 完整，与既有「任何人不能自我验收」一致 |
| Product 预验收 + 用户最终验收（accepted/rejected/conditional）                          | 372  | 完整，未替代 gate 或用户最终权         |
| 默认北京时间 + API/日志/事件/UI 带偏移 ISO 8601 + 存储按 ADR                            | 373  | 完整                                   |
| L1/L2/L3 验证分层、一次取证、干净提交、负向证据保留、单一全量并行                       | 374  | 完整，与 `riccon-verify` §1.1 一致     |

冲突检查：新增 369-372 是在既有「用户拥有范围/不可逆/生产」规则上增加评审与预验收，不删除用户决定权；372 与既有「gate 后才向用户提交」一致。**无冲突**。

### 3. PRODUCT 状态表

| 检查                                      | 结果                                                          | 证据                                                                                        |
| ----------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| M01/M02 标 legacy，不伪造 pass            | pass                                                          | `docs/PRODUCT.md:7-8,19-20`                                                                 |
| M03 引用真实 DEC-0010/0011/0014/0015      | pass                                                          | 四个决策均 `status: answered`、`owner: user`；`docs/PRODUCT.md:21`                          |
| M03 引用真实 ADR-0018 / REV-TASK-0033-002 | pass                                                          | ADR README 索引 `ADR-0018 proposed`；REV-TASK-0033-002 `verdict: pass`、`independent: true` |
| M10 标 pending，不借 M03 冒充通过         | pass                                                          | `docs/PRODUCT.md:22`；`docs/ACCEPTANCE.md` 亦将 OIDC/审计归 M10                             |
| 已知漂移是否被如实登记                    | **部分**：已登记在 REV-TASK-0033-002，但状态表自身未 footnote | 见 F-001                                                                                    |

F-001 的关键事实：PRODUCT 仍保留旧文案，且这些文本与已批准决策不一致：

- `docs/PRODUCT.md:36`：「审计查询在 M03」——DEC-0011 已把审计查询/导出拆到 M10（M03b）。
- `docs/PRODUCT.md:94`：「OIDC/SSO 与部门数据范围（M03）」——OIDC 已归 M10。
- `docs/PRODUCT.md:201`：M03-AC-07 演员写「负责人把她移出部门」——DEC-0015 已定为平台管理员跨租户管理租户部门元数据。
- `docs/PRODUCT.md:215`：M03-AC-21 仍写部门管理、OIDC、审计三处界面一起验——PRODUCT §8.5 已把 OIDC/审计页归 M10。

这些漂移在 `.riccon/reviews/tasks/TASK-0033-review-002.md:28-36,105,126-128` 被明确记录为 **F-003/F-004/F-005，P2 accepted，不是 fixed**；同一文件的 Verifier 列也被 PRODUCT 状态表引用。因此不是「伪造 pass」，但状态表只写 `Product 草案 pass` / `Lead 完整性 pass`，没有把「原文仍含 accepted 漂移、M03 gate 评审包与 M10 任务卡承接」写在表内或紧邻说明，读者可能把 pass 误读为 PRODUCT 已完全同步。按 P2 accepted 处理，不阻断 M11 文档任务关闭；承接要求见 Findings。

### 4. ARCHITECTURE 状态表

| 检查                    | 结果 | 证据                                                                                             |
| ----------------------- | ---- | ------------------------------------------------------------------------------------------------ |
| ADR-0018 仍 `proposed`  | pass | `docs/adr/ADR-0018-*.md:3`；`docs/adr/README.md` 索引；`docs/ARCHITECTURE.md:7`                  |
| 用户批准未伪造          | pass | `docs/ARCHITECTURE.md:9` 为 `pending`，写明 DEC-0003 release blocker；未声称不可逆项已批准       |
| Ops/安全 pending 有理由 | pass | `docs/ARCHITECTURE.md:11-12`：B1b 回滚演练、M09 Helm/备份；威胁模型已更新，M03 gate 安全评审待做 |
| Verifier 评审引用真实   | pass | `REV-TASK-0033-002` 与 `REV-M02-003` 均为 `pass`                                                 |

F-004 记录的精度问题：`docs/ARCHITECTURE.md:7` 把 M02 架构范围写成 `ADR-0001..0017`，但 ADR-0001..0004 属 M01（PRODUCT 按模块表已正确拆分）；`:8` 的「本次 M03 评审」没有 commit/decision/review 链接；`:9` 的「用户批准 pending」与「M03 无不可逆项」并存，实际 pending 由 DEC-0003 release blocker 驱动。均不影响「未批准不实现」的结论，列为 P3。

### 5. ADR README

| 检查                                                      | 结果 | 证据                                                                                    |
| --------------------------------------------------------- | ---- | --------------------------------------------------------------------------------------- |
| 模板含 Reviewers / Approved-by / Review-date / Supersedes | pass | `docs/adr/README.md` 模板 4 个字段齐全                                                  |
| 说明 proposed → accepted 条件                             | pass | 「未完成 Lead/Verifier/Ops 评审或用户批准（不可逆/高成本项）前，不得改为 accepted」     |
| Approved-by 必须真实决策 ID                               | pass | 规则明确要求引用 `DEC-0005` 类真实 ID                                                   |
| 已有 ADR 正文/结论未改                                    | pass | `git diff --name-only 8c905d0..HEAD -- docs/adr` 仅 `README.md`；17 份已有 ADR 内容未变 |

### 6. CODING-STANDARDS

| 要求                                                     | 结果 | 证据                                                                                                                |
| -------------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------- |
| 合同默认时区                                             | pass | §5.5 `CODING-STANDARDS.md:124` 引 `project.timezone`                                                                |
| API/日志/事件/UI 带 +08:00 偏移                          | pass | `:126` 示例 `2026-11-18T09:30:00+08:00`，禁止无偏移格式                                                             |
| 存储层 UTC 例外                                          | pass | `:125,127` 明确仅存储层 UTC，边界/展示转北京时间                                                                    |
| MySQL 8.4 `DATETIME(6)` UTC 诚实表述，不谎称 timestamptz | pass | `:125` 明确「MySQL 8.4 没有 timestamptz」，按 ADR-0008/0012 用 `DATETIME(6)` 存 UTC；`§11:214` 同时禁止 `TIMESTAMP` |
| §10.1 测试分层完整                                       | pass | L1/L2/L3、一次取证、先提交、显式超时、热栈仅内层循环、并行单全量均覆盖                                              |

F-002 仅涉引用精度：ADR-0008 正文只提到 outbox 的 `created_at` 字段（`docs/adr/ADR-0008-*.md:39`），没有规定 `DATETIME(6)`/UTC；真正规定类型与时区的是 `ADR-0012:37`。建议下次文档修订只引 ADR-0012，或补 ADR-0008 的具体段落。

### 7. ACCEPTANCE.md

| 检查                                         | 结果 | 证据                                                    |
| -------------------------------------------- | ---- | ------------------------------------------------------- |
| M03 Product/用户验收 pending                 | pass | `docs/ACCEPTANCE.md:32,42` 均 `pending`                 |
| M01/M02 not-recorded/legacy，不补假 accepted | pass | `:51-52` 两行均 `not-recorded`，备注「不补假 accepted」 |
| 保留「gate 未过不提交用户验收」              | pass | `:56-57` 规则完整                                       |
| 不替代 gate/release/生产批准                 | pass | `:60` 明确                                              |

F-003：`:26` 的结果值列表是 `pass`/`fail`/`waived`，但表格与结论实际使用 `pending`（`:21-24,32,42`）。补 `pending` 或把未开始行改成「未开始」，P3。

### 8. 边界

`git diff --name-only 8c905d0..HEAD | grep -vE '^(\.riccon/|docs/|AGENTS\.md)'` 为空。具体：

- `da7c00a`：`AGENTS.md` + `docs/PRODUCT.md`、`ARCHITECTURE.md`、`adr/README.md`、`CODING-STANDARDS.md`、`ACCEPTANCE.md` + `.riccon` 状态/模块/任务；
- `0a106f2`：`R100` 移动两份 TASK-0033 评审 + 新增 `tasks/README.md`，内容 hash 不变；
- `92a3046`：EV-0308、TASK-0035/0036、state；
- `c14eae2`：只改 M11 模块记录。

无 `apps/**`、`platform/**`、`contracts/**`、迁移 SQL、`.trivyignore`、威胁模型改动。M03 的 `M03.json`、TASK-0033/0034、M03 证据与 `M01/M02-review.md` 在 `8c905d0..HEAD` 内 diff 为空。

### 9. 状态真实性

| 检查                                   | 结果 | 证据                                                                                              |
| -------------------------------------- | ---- | ------------------------------------------------------------------------------------------------- |
| M11 仍 `planned`，未伪造 done          | pass | `.riccon/modules/M11.json`：`status: planned`、`review: null`、`started_at: null`                 |
| TASK-0035 为 `review`                  | pass | `.riccon/tasks/TASK-0035.json`                                                                    |
| TASK-0036 为 `in_progress`（本次评审） | pass | `.riccon/tasks/TASK-0036.json`；state `active_tasks.riccon/verifier = TASK-0036`                  |
| `state validate` OK                    | pass | EV-0309，exit 0                                                                                   |
| M11 未被塞进 M03 gate                  | pass | `state.current_module = M03`；M11 独立 `depends_on: [M03]`、`review: null`；M03 review/证据无改动 |

## Findings

### F-001（P2，accepted）PRODUCT 状态表标 pass，未 footnote 已 accepted 的原文漂移

- 位置：`docs/PRODUCT.md:7,21`；漂移正文 `:36,94,201,215`；登记处 `.riccon/reviews/tasks/TASK-0033-review-002.md:28-36,105,126-128`。
- 观察值：状态表 M03 的 `Product 草案`、`Lead 完整性` 均为 `pass`，表内无 F-003/F-004/F-005 footnote；PRODUCT 仍保留「审计查询在 M03」、OIDC 归 M03、AC-07 演员「负责人」、AC-21 三处界面合并等旧文案。
- 期望值：状态表或其紧邻说明明确「PRODUCT 原文仍含 F-003..F-005 accepted 漂移，M03 gate 评审包与 M10 任务卡承接，不在 M11 重写 PRODUCT」；或把对应行改为 `pass（含 accepted 文档漂移）`。
- 判断：漂移已在 REV-TASK-0033-002 登记为 **P2 accepted（不是 fixed）**，且状态表引用了该 review，因此不构成伪造 pass；但状态表自身没有把「pass 不等于原文完全同步」写清楚，存在误读风险。按 P2 accepted 处理，**不阻断 M11 文档任务关闭**。
- 承接：M03 gate 评审包必须引用 F-003..F-005；M10 任务卡承接 M10 AC/审计可见性；下一次 PRODUCT 修订补状态表 footnote。**需要 Product/Architect 在下一次文档任务处理，不需要用户重新决策**（DEC-0010/0011/0014/0015 已答复）。

### F-002（P3，accepted）DATETIME(6) UTC 的 ADR 引用不精确

- 位置：`docs/CODING-STANDARDS.md:125`、`AGENTS.md:373`。
- 观察值：两处都写「按 ADR-0008/0012 使用 `DATETIME(6)` 存储 UTC」；但 ADR-0008 只在 `docs/adr/ADR-0008-*.md:39` 提到 outbox `created_at` 字段，没有规定类型/时区/连接初始化。
- 期望值：只引 `ADR-0012:37`（明确 `DATETIME(6)`、UTC、`SET time_zone='+00:00'`、禁止 `TIMESTAMP`），或补充 ADR-0008 中具体沿用该口径的段落。
- 影响：文档归因不精确，不改变实现口径；P3 accepted，下次文档修订修正。

### F-003（P3，accepted）ACCEPTANCE.md 结果值列表缺 pending

- 位置：`docs/ACCEPTANCE.md:26`，对照 `:21-24,32,42`。
- 观察值：允许值为 `pass`/`fail`/`waived`，实际使用 `pending`。
- 期望值：列表加入 `pending`，或未开始项改用「未开始」。P3，不影响验收链语义。

### F-004（P3，accepted）ARCHITECTURE 状态表归因与记录精度

- 位置：`docs/ARCHITECTURE.md:7-9`。
- 观察值：
  - `:7` 写「M02 ADR-0001..0017」，但 ADR-0001..0004 属 M01（`docs/PRODUCT.md:19-20` 已正确拆分）；
  - `:8` 的「本次 M03 评审」没有 commit/decision/review 链接；
  - `:9` 的「用户批准 pending」与「M03 无不可逆项」并存，实际 pending 由 DEC-0003 release blocker 驱动。
- 期望值：按模块拆分 ADR 归属；「本次评审」补可追溯记录（如 REV-TASK-0033-002 或 commit）；用户批准行拆成「模块级 n/a/pass」+「release blocker pending」。
- 影响：不改变「ADR-0018 未批准、不得实现不可逆项」的结论；P3 accepted。

### F-005（P3，accepted）TASK-0035 证据覆盖不足

- 位置：`.riccon/tasks/TASK-0035.json`（`evidence: [EV-0308]`，acceptance 第 6 条）、`.riccon/evidence/TASK-0035/EV-0308.json`。
- 观察值：EV-0308 只覆盖 `contract validate --strict`；acceptance 第 6 条还要求 `git diff --check` 与 `state validate`，两者没有作为 TASK-0035 的 EV 记录。
- 期望值：Lead 在 closeout 时补录 `git diff --check`/`state validate` 的 EV，或明确引用本次 verifier 的 EV-0309。verifier 已独立复验两项均 exit 0，故不影响 verdict；P3。
- 说明：EV-0309 属 TASK-0036，不冒充 TASK-0035 证据；这里只做证据卫生提示。

## 未测与不适用

- 未跑 Java/pnpm 构建、单测、覆盖率、E2E、a11y、security、perf：M11 是 docs-only 模块，任务明确要求不跑全量；这些 DoD 项在 M11 不适用。
- 未做浏览器/UI 验证：本次无 UI 变更。
- 未验证 M03 实现：M11 不进入 M03 gate；M03 的代码验证由 TASK-0033/0034 及其后续 M03 gate 负责。

## 剩余风险

1. **任务级评审不再被 kernel schema 校验**：`0a106f2` 把 TASK-0033 评审移到 `.riccon/reviews/tasks/`；`readMarkdownRecords` 只读顶层 `.md`，所以 `tasks/` 下的 front matter 不再参与 `state validate`。README 已写约定，但后续任务级评审需 Lead 在 closeout 人工抽查；这是流程风险，不是本次文档错误。
2. **PRODUCT 漂移仍在**：F-001 的 F-003..F-005 是 accepted，不是 fixed；M03 gate 评审包与 M10 任务卡必须引用，否则 M03 的「AC 已通过」叙述会与 PRODUCT 原文冲突。
3. **ADR-0018 仍 proposed**：用户批准/Ops 回滚/安全评审均 pending；M03 实现前不得把 ADR-0018 描述为 accepted，也不得把 DEC-0015 的部门管理主体批准等同于 ADR 整体批准。
4. **M11 依赖 M03 但文档任务已在 review**：M11 `depends_on: [M03]` 且 `status: planned`，而 TASK-0035/0036 已产生文档与评审；建议 Lead 决定 M11 是独立 docs-only 模块还是并入 M03 closeout，避免模块状态与实际产物长期不一致。
5. **TASK-0035 证据卫生**：见 F-005；不影响本次 verdict，但 closeout 时应补 state validate/diff-check 证据或引用 verifier EV。

## 是否可关闭 M11 文档任务

**可以（按当前范围）**：P0/P1 为 0，六份文档的规范升级与边界均成立；TASK-0035 可由 Lead 标 done，TASK-0036 可转 review 后关闭。关闭前建议：

1. Lead 知悉并接受 F-001..F-005 的处置；F-001 必须写入 M03 gate 评审包与 M10 任务卡的承接清单；
2. 补 TASK-0035 的 `state validate`/`git diff --check` 证据，或引用 EV-0309（F-005）；
3. 不修改 PRODUCT 正文（本次范围不含），但下一次 PRODUCT 修订补 F-001 footnote 与 F-002 引用修正。

**不需要用户重新决策**：DEC-0010/0011/0014/0015 均已由 user 答复；M03 用户最终验收仍在 M03 gate + Product 预验收之后，按 `docs/ACCEPTANCE.md` 保持 pending。需要人工确认的是 Product/Architect 对 F-001/F-002 的下一次文档修订排期，以及 Ops 对 M03 回滚演练的排期（ARCHITECTURE `:11`）。

## 评审包摘要

- verdict：**pass**
- 测了什么：合同 diff、`contract validate --strict`、`state validate`、`git diff --check`、rename 检测、六文档内容对账、DEC/ADR/REVIEW 引用核对、EV-0308 审计、M03 边界核对
- 没测什么：Java/pnpm 构建与测试、覆盖率、E2E、a11y、security、perf（docs-only，不适用）
- 证据：EV-0308（TASK-0035，contract validate）、EV-0309（TASK-0036，state validate）
- P0/P1：无；P2：F-001；P3：F-002/F-003/F-004/F-005
- 下一步：Lead closeout TASK-0035/0036；M03 gate 评审包承接 F-001；M11 模块状态由 Lead 决定
