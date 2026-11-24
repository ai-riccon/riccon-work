---
review_id: REV-M06-ARCH-003
module_id: M06
task_id: TASK-0217
previous_review: REV-M06-ARCH-002
reviewer:
  agent: riccon/verifier
  session: ses_df14e751fffe3T1jgZe69jGekI
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - "4e575a0"
  - "c0a6b37"
  - "48211e7"
  - "06b3db2"
head_commit: "06b3db297975a4fe29d502d669584dd09dd87298"
findings:
  - id: F-007
    severity: P1
    status: fixed
    title: "T16a 已覆盖安全/审计测试写范围，surefire 零 skip/零 xfail 要求明确"
    summary: >-
      ADR §13.1 的 T-M06-16a（第 837 行）write_scope 已包含
      `apps/api/src/test/java/com/riccon/api/security/**` 与
      `apps/api/src/test/java/com/riccon/api/audit/**`；任务内容明确
      `TEN-M06-01`/`DEPT-M06-01`/`SEC-WF-002/003/006` 落 surefire XML、
      `IT-AUDIT-M06-01` 归审计测试，并要求零 skip/零 xfail。T-M06-03（第 821 行）
      明确负责 `IT-WF-111`。现有 `TenantIsolationSecurityTest`/
      `DepartmentIsolationTest`/`SecurityTestSupport` 与 `audit/` 测试目录均落在
      T16a 写范围内，THREAT-MODEL §8 的 surefire 命令可产出证据。T16a 7h、T17 7h，
      21 任务合计仍 120h，关键路径仍 75h。
    evidence: "ADR-0024 §13.1 T03（821）、T16a（837）、T17（839）、合计/调剂（840-841）；THREAT-MODEL:826-833；apps/api/src/test/java/com/riccon/api/security/、audit/"
  - id: F-021
    severity: P1
    status: fixed
    title: "AC-15 已移除 M06b 共享/私有模板子句并移除 SEC-WF-002 的 M06 测试线索"
    summary: >-
      `docs/PRODUCT.md:832` 的 M06-AC-15 已把“编排/发布/共享”改为“编排/发布”，
      404 子句删除“私有模板”，并在末尾明确模板共享/私有模板 404 与审计归 M06b
      AC-11/25（M06 不实现共享）；测试线索为 `SEC-WF-003/006`、`TEN-M06-01`、
      `DEPT-M06-01`、`IT-WF-112`，不再包含 `SEC-WF-002`。§12.4 的跨租户/跨部门
      指标（第 874 行）已把口径改为“流程、运行、审批、历史（模板共享归 M06b
      AC-11/25）”，验证列移除 `SEC-WF-002`；补充约定（第 853 行）明确
      M06-AC-15 只判 M06 的流程/运行/审批/权限/取消/停用/授权/移交。
      AC-15 文本只有三处变化，测试线索另行从 `SEC-WF-002/003/006` 改为
      `SEC-WF-003/006`；AC-15 其余 Then 子句未动。
    evidence: "docs/PRODUCT.md:832,853,874；git 48211e7；c0a6b37..HEAD 逐字 diff"
  - id: F-013
    severity: P2
    status: accepted
    owner: "安全评审/riccon/product"
    due: "M06 gate 前"
    accepted_reason: >-
      U-05 仍是 M06 gate 前的安全/Product 二选一决策：当前临时默认“理由原文≤500 字
      进入审计并在审批 UI 提示”；替代方案是审计只存 `commentSha256`、审批记录保留理由。
      ADR §8.3/§16 已明确未解决、owner 与 due；本轮 delta 未改变该状态。本评审不声称
      安全评审已通过，M06 gate 前必须二选一并补测试、必要时回改 AC-16 文案。
    title: "U-05 审批理由入审计仍为 P2 residual，不是已接受的安全结论"
    summary: >-
      ADR §8.3（第 607 行）与 §16 U-05（第 960 行）仍写“未解决”；本轮只复核
      AC-15/F-007/F-021 的 delta，没有把 U-05 改成 pass。AC-16 因此保留
      `pass (with residual)`。
    evidence: "ADR-0024 §8.3（607）、§16 U-05（960）；docs/PRODUCT.md:833"
  - id: F-014
    severity: P2
    status: accepted
    owner: "riccon/verifier（独立安全评审）"
    due: "T06a 完成前"
    accepted_reason: >-
      F-014 的机制设计已补齐：claim SELECT 恰好 4 列并有静态列集合断言、非法/空
      tenant fail-closed、`claim_failures_total`/denied 审计、`IT-WF-103` 与
      `TEN-M06-01` 覆盖负向场景。仍缺的是独立安全评审本身；本轮 delta 未改变该状态，
      不得把 U-13/TM-85 当作已通过。该评审必须在 T06a 完成前完成并留下证据，否则
      不得把 T06a 标 done。
    title: "U-13 跨租户领取机制已补齐，独立安全评审仍是 accepted residual"
    summary: >-
      ADR §4.3（第 331 行）与 §16 U-13（第 968 行）仍要求独立安全评审
      （F-014/U-13）；本轮只确认 T16a 的 surefire 写范围闭合，没有伪造安全评审结论。
    evidence: "ADR-0024 §4.3（331）、§16 U-13（968）；REV-M06-ARCH-002 F-014"
  - id: F-022
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "M06 gate 前"
    accepted_reason: >-
      AC-01 文本、E2E 约束与 ADR §6.2/§14 已一致；本轮 delta 已修正 §12.4 的
      “跨租户/跨部门”指标，但“审计覆盖”行仍列“共享”，ADR §16 顶部仍写
      “U-03..U-13 仍未决”，U-03 行仍以同侪协同为未决问题。该滞后不改变
      AC-01/AC-16 的可验收文本；作为 P3 文档清理项接受，M06 gate 前由
      Architect/Product 一并修正。
    title: "U-03 状态表与 Product §12.4 审计覆盖指标仍滞后"
    summary: >-
      `docs/PRODUCT.md:875` 的审计覆盖口径仍含“共享”，而 AC-16 已把模板共享审计
      明确归 M06b；ADR §16（第 953 行）与 U-03 行未同步。不影响本轮 F-021 的
      AC-15 结论。
    evidence: "docs/PRODUCT.md:875；ADR-0024 §16（953）、U-03；REV-M06-ARCH-002 F-022"
  - id: F-023
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M06 实现任务创建时"
    accepted_reason: >-
      T06b 8h、T16a 7h、T17 7h 仍处于高风险任务上限；T16a 在本轮新增了
      TEN/DEPT/SEC-WF/IT-AUDIT 套件落点，7h 更紧，T17 从 8h 降到 7h。总额仍 120h、
      关键路径 75h，且 120h 留约 45h 修复/评审余量。建卡时若实际估算仍超，应按
      DEC-0046 的 20% 触发条件回用户，不再静默扩预算或削减
      AC-15/16/17/18/21/23/24。作为 P3 residual 接受。
    title: "T16a 7h/T17 7h 与 T06b 8h 仍偏大，剩余为预算风险"
    summary: >-
      T16a 现覆盖 E2E/axe/键盘/perf/越权/注入/重试，以及 TEN/DEPT/SEC-WF/IT-AUDIT
      五个 Java 安全/审计落点；T17 覆盖独立评审/gate/全量回归/回滚/威胁模型核对。
      任务表与总表仍自洽（关键路径含 T16a 7 + T16b 4 + T17 7，整条关键路径合计
      75h），但实际工作量需 Lead 建卡时复核。
    evidence: "ADR-0024 §13.1 T06b（825）、T16a（837）、T17（839）、合计（840）；OPS-M06-013"
  - id: F-024
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "M06 gate 前"
    accepted_reason: >-
      Product AC-15 已把 `SEC-WF-002` 的 M06b 私有模板段移出，但 ADR §14 AC-15
      （第 879 行）与 T16a（第 837 行）仍把 `SEC-WF-002` 列为 M06 证据；
      `SEC-WF-002` 在 THREAT-MODEL 同时覆盖跨租户/私有模板/平台管理员。M06 只应执行
      跨租户/平台管理员子集，私有模板段由 M26-B5 覆盖；需在 T16a 建卡或 ADR 修正时
      写明，避免把 M06b 用例计入 M06 零 skip。另 OPS-M06-013 行仍写
      “T16a/b 合计 10h”，与当前 7+4=11h 不一致，不影响总表 120h。作为 P3
      文档/范围澄清项接受。
    title: "ADR §14/T16a 仍列 SEC-WF-002，OPS-M06-013 的 T16a/b 10h 口径滞后"
    summary: >-
      本轮不把该文档残留当作 P1/P2：AC-15 的 M06 测试线索已改为
      `SEC-WF-003/006`、`TEN-M06-01`、`DEPT-M06-01`、`IT-WF-112`，不依赖
      `SEC-WF-002` 的私有模板段；但 T16a 的“零 skip/零 xfail”要求需要按 M06 子集
      执行。M06 gate 前由 Architect/T16a 明确子集并修正工时文案。
    evidence: "docs/PRODUCT.md:832；ADR-0024 §14（879）、T16a（837）、§17 OPS-M06-013（990）；THREAT-MODEL:537,719"
evidence: []
created_at: "2026-11-25T02:00:30+08:00"
---

# REV-M06-ARCH-003：TASK-0217 M06 架构/AC 可验证性第三轮 delta 复评

## 结论

**verdict: pass**。新基线 `06b3db2`（ADR/ARCHITECTURE 修订）与 Product `48211e7`
（AC-15 修订）已关闭 REV-M06-ARCH-002 的两个 open P1：

1. **F-007 fixed**：T16a 的 write_scope 已包含
   `apps/api/src/test/java/com/riccon/api/security/**` 与
   `apps/api/src/test/java/com/riccon/api/audit/**`，任务内容明确
   `TEN-M06-01`/`DEPT-M06-01`/`SEC-WF-002/003/006` 落 surefire XML、
   `IT-AUDIT-M06-01` 归审计测试，并要求零 skip/零 xfail；T03 明确 `IT-WF-111`。
   T16a 7h/T17 7h、总 120h 成立。
2. **F-021 fixed**：M06-AC-15 已删除“共享”和“私有模板”的 M06 子句，测试线索
   去掉 `SEC-WF-002`，保留 `SEC-WF-003/006`、`TEN-M06-01`、`DEPT-M06-01`、
   `IT-WF-112`；§12.4 跨租户/跨部门指标去掉 M06 模板范围，补充约定明确
   模板共享/私有模板 404 归 M06b AC-11/25。

本轮 delta 未发现新 P1/P2。仍保留 **2 个 accepted P2**（F-013 U-05、F-014 U-13，
都是 M06 gate 前的安全/Product 或独立安全评审项）和 **3 个 accepted P3**
（F-022 文档滞后、F-023 预算风险、F-024 SEC-WF-002 子集与 OPS 工时文案澄清）。
它们均不是 open；本评审没有把任何 accepted residual 改写成 pass，也没有声称
U-05/U-13 已通过安全评审。

本评审是 TASK-0217 的 **delta 复评**，不是模块 gate，也不替代 Product 预验收或用户
产品验收。按任务要求未运行 `riccon gate check --module M06`；未修改生产源码、测试、
迁移、`docs/PRODUCT.md`、`AGENTS.md`、ADR、威胁模型、契约或 `.riccon` 状态。AC 的
全量逐条结论沿用 REV-M06-ARCH-002；本轮只复核 delta 影响的 AC-15/16/24、F-007 的
测试 owner 与任务/工时完整性。

## Delta 范围与基线

| 提交      | 内容                                                  |
| --------- | ----------------------------------------------------- |
| `4e575a0` | REV-M06-ARCH-002 的代码/设计基线（上一轮 head）       |
| `c0a6b37` | REV-M06-ARCH-002 复评落盘（本轮 diff 起点）           |
| `48211e7` | Product 按 F-021 修订 M06-AC-15 与 §12.4              |
| `06b3db2` | 关闭 OPS-017..023 与 F-007 写范围缺口（21 任务/120h） |

`git diff --stat c0a6b37..HEAD` 只涉及 3 个文件：
`docs/PRODUCT.md`（+47/-46）、`docs/adr/ADR-0024-m06-workflow-orchestration.md`
（+43/-28）、`docs/ARCHITECTURE.md`（+3/-3）。本轮 `contracts/openapi.yaml` 没有改动，
REV-M06-ARCH-002 的契约结论（`branch` 字符串枚举、审批节点字段、`dataScopeSummary`、
`updatedAt`、`inputSummary`、审批聚合、`BUILT_IN` 等）继续有效，本轮不重复全量契约检查。

## F-007 核对：安全/审计 surefire 写范围

### 任务落点

- **T16a（第 837 行）write_scope** 已包含：
  - `apps/api/src/test/java/com/riccon/api/security/**`
  - `apps/api/src/test/java/com/riccon/api/audit/**`
- **T16a 内容**明确：“安全套件 `TEN-M06-01`/`DEPT-M06-01`/`SEC-WF-002/003/006`
  落 surefire XML、`IT-AUDIT-M06-01` 归审计测试；……零 skip/零 xfail”。
- **T03（第 821 行）** 内容与证据列均明确 `IT-WF-111`，write_scope 含
  `apps/api/src/test/java/com/riccon/api/workflow/validation/**`。
- **现有测试根**：`apps/api/src/test/java/com/riccon/api/security/` 已有
  `TenantIsolationSecurityTest.java`、`DepartmentIsolationTest.java`、
  `SecurityTestSupport.java`；`apps/api/src/test/java/com/riccon/api/audit/` 已有
  `KnowledgeAuditPolicyTest.java` 与 `domain/`。T16a 可在不改动其他任务写范围的前提下
  新增 `TEN-M06-01`/`DEPT-M06-01`/`SEC-WF-002/003/006` 与 `IT-AUDIT-M06-01` 用例。

### 验收与证据

- THREAT-MODEL §8（第 826-833 行）要求 M06 的 `TEN-M06-01`/`DEPT-M06-01` 出现在
  `apps/api/target/surefire-reports/*.xml`，并对 surefire XML 检查
  `skipped="[1-9]|failures="[1-9]|errors="[1-9]`。T16a 的“落 surefire XML”与
  “零 skip/零 xfail”直接覆盖该要求；T16a 证据列同时列出
  `SEC-WF-002/003/006`、`TEN-M06-01`、`DEPT-M06-01`、`IT-AUDIT-M06-01`。
- 结论：**F-007 fixed**。上一轮“T16a 只能写 e2e/scripts、不能产出 Java surefire”的
  缺口已闭合；安全/审计测试 owner 不再需要 T17 或其他任务临时承担。

## F-021/AC-15 核对：M06b 共享/私有模板已移出

### AC-15 文本逐字核对

对 `c0a6b37` 与 `HEAD` 的 M06-AC-15 单元格做逐字 diff，AC-15 文本只有以下三处
变化（测试线索的调整见下一节）：

1. `只有授权角色可编排/发布/共享` → `只有授权角色可编排/发布`；
2. `私有草稿、私有模板、他人运行详情等不可见对象` →
   `私有草稿、他人运行详情等不可见对象`；
3. 末尾新增：`模板共享/私有模板的 404 与审计在 M06b 的 AC-11/25 验收
（M06 不实现共享）。`

AC-15 其余 Then 子句（部门负责人发布范围、普通员工只运行、运行详情仅发起人/
租户管理员、审批人范围且非发起人、取消/停用/重新启用、授权/撤销/移交、403/404、
移出部门/停用/禁用、平台管理员 404）均未删除或改写。M06 gate 判 AC-15 时仍能引用
用户可见结果，且不再包含 M06 无法产生的“共享/私有模板”Then 子句。

### 测试线索与指标

- **AC-15 测试线索**（第 832 行）现为：
  `SEC-WF-003/006`、`TEN-M06-01`、`DEPT-M06-01`、`IT-WF-112`；
  已去掉 `SEC-WF-002`，保留原要求的其余四项。
- **§12.4 跨租户/跨部门指标**（第 874 行）现为：
  口径“流程、运行、审批、历史（模板共享归 M06b AC-11/25）”，验证列
  `SEC-WF-003`、`TEN-M06-01`、`DEPT-M06-01`；不再把 M06 的模板范围或
  `SEC-WF-002` 算入 M06 指标。
- **补充约定**（第 853 行）明确：“M06-AC-15 只判 M06 的流程/运行/审批/权限/取消/
  停用/授权/移交；模板共享与私有模板 404 归 M06b AC-11/25。”
- **AC-15 优先级理由**（第 962 行）仍只列编排/运行/查看/审批/取消/停用/授权/移交，
  与修订后的 AC-15 一致，没有把共享/私有模板列为 M06 T4 阻断项。

结论：**F-021 fixed**。Product 侧 AC-15、测试线索、§12.4 指标与补充约定四处一致，
M06 gate 不再被 M06b 的模板共享/私有模板子句阻塞。

### 残余（P3，F-024）

ADR §14 AC-15（第 879 行）与 T16a（第 837 行）仍列 `SEC-WF-002`。THREAT-MODEL
第 537 行把 `SEC-WF-002` 描述为“跨租户/私有模板/平台管理员”，B-28（第 719 行）又把
`SEC-WF-002` 列为 M06 gate 的全过/零 skip 项。M06 只应执行其中的跨租户/平台管理员
子集；私有模板段由 M26-B5 覆盖。该文档残留不影响 Product AC-15 的可验收文本，但需在
T16a 建卡或 ADR 修正时写明，避免把 M06b 用例计入 M06 的零 skip。另 OPS-M06-013
（第 990 行）仍写“T16a/b 合计 10h”，与当前 7+4=11h 不一致；总表 120h 不受影响。

## F-013/F-014 的 accepted P2 状态

本轮没有把上一轮的 accepted P2 静默改成 pass：

| Finding      | 严重度 | 本轮状态     | Owner / Due                                  | 关键结论                                                                            |
| ------------ | ------ | ------------ | -------------------------------------------- | ----------------------------------------------------------------------------------- |
| F-013 (U-05) | P2     | **accepted** | 安全评审/riccon/product；M06 gate 前         | 审批理由入审计仍是临时默认；安全评审/Product 必须在 M06 gate 前二选一，未声称已接受 |
| F-014 (U-13) | P2     | **accepted** | riccon/verifier（独立安全评审）；T06a 完成前 | claim 机制已补齐；独立安全评审本身仍待完成，未声称 TM-85 例外已通过                 |

- ADR §8.3（第 607 行）与 §16 U-05（第 960 行）仍写“未解决”；ADR §4.3（第 331 行）
  与 §16 U-13（第 968 行）仍要求独立安全评审。本轮 delta 未改变这两项。
- 因此 AC-16 保留 `pass (with residual)`（U-05 可能改变“审批含理由”的审计口径）；
  AC-15 的 M06 文本已可验收，但 U-13/TM-85 独立安全评审仍是 gate 前安全项，不写成
  无残余的 pass。
- 本评审不伪造安全评审结论；两项 residual 必须在 M06 gate/T06a 前由对应 owner 关闭
  或继续以 accepted 记录跟踪。

## Delta 影响的 AC 结论

未变化的 AC 结论沿用 REV-M06-ARCH-002 的逐条表；本轮 delta 只更新以下三条：

| AC                            | 本轮结论                   | 依据/残余                                                                                       |
| ----------------------------- | -------------------------- | ----------------------------------------------------------------------------------------------- |
| M06-AC-15                     | **pass (with residual)**   | F-021 fixed：M06 Then 子句可验收；残余为 U-13/TM-85 独立安全评审（F-014，T06a 前）              |
| M06-AC-16                     | **pass (with residual)**   | F-007 fixed：`IT-AUDIT-M06-01` 已由 T16a 承接；残余为 U-05 审批理由入审计（F-013，M06 gate 前） |
| M06-AC-24                     | **pass**                   | F-007 fixed：`IT-WF-111` 由 T03 承接；`SEC-WF-005` 落点不变                                     |
| M06-AC-01..10、12..14、17..23 | 沿用 REV-M06-ARCH-002 pass | 本轮 delta 未触及这些 AC 的文本、测试线索或任务映射                                             |
| M06-AC-11/25                  | not in M06 gate            | 仍只在 M06b/M26 gate；本轮 Product 只修订 AC-15                                                 |

**AC 统计**：M06 gate 的 23 条 AC 中，20 条沿用上一轮 pass；AC-15/16 为
`pass (with residual)`，残余分别是 F-014/F-013 的 gate 前安全项；AC-24 由上一轮的
`pass (with residual)` 转为 pass（F-007 fixed）；没有 fail 的 AC。

## 任务/工时与 AC 完整性

### 任务与工时

用脚本解析 ADR §13.1 任务表：

```text
任务数：21（T01..T17，含 T06a/b、T07a/b、T12a/b、T16a/b）
工时：9+6+6+6+6+6+8+5+5+6+5+10+2+5+5+4+4+4+7+4+7 = 120h
关键路径：T01→T02→T03→T04→T05→T06a→T06b→T07a→T07b→T16a→T16b→T17 = 75h
前端链：T01→T02→T10→T11→T12a→T12b→T13 = 41h
M26 §13.2：M26-B1..B6 = 4+8+10+8+10+8 = 48h（6 任务）
```

- T16a 由 6h 调为 7h，T17 由 8h 调为 7h，总额仍 120h；关键路径仍 75h。
- 21 个任务、M26 48h 未被破坏；`ARCHITECTURE.md` 的“21 个任务、120h、M26 48h”
  与 ADR 一致。
- 残余：F-023（T16a 7h/T17 7h/T06b 8h 仍偏大）与 F-024（OPS-M06-013 的 10h
  文案滞后）均为 accepted P3，不影响总额成立。

### AC 完整性

- `docs/PRODUCT.md` 的 M06-AC-01..25 共 25 行，编号无删除、无新增。
- 逐字比较 `c0a6b37` 与 `HEAD` 的 AC 定义行，只有 M06-AC-15 变化；其余 24 条
  AC 的 Given/When/Then、验证类型与测试线索未改。AC-11/25 仍只在 M06b/M26 gate。
- AC-15 的修订见上文；AC-16 的 M06b 共享审计说明是 9d30ac1 的既有修订，本轮未改。

## 证据命令摘要

```text
# 基线与 delta
git rev-parse HEAD
-> 06b3db297975a4fe29d502d669584dd09dd87298
git log --oneline -6
-> 06b3db2, 48211e7, c0a6b37, 79b23db, 4e575a0, 31121fb
git diff --stat c0a6b37..HEAD
-> docs/ARCHITECTURE.md | 6 +- ; docs/PRODUCT.md | 93 +++++---- ;
   docs/adr/ADR-0024-m06-workflow-orchestration.md | 65 +++++----

# AC 逐字比较（c0a6b37 vs HEAD）
25 行 M06-AC-01..25；仅 M06-AC-15 变化：
- “编排/发布/共享” -> “编排/发布”
- “私有草稿、私有模板、他人运行详情” -> “私有草稿、他人运行详情”
- 新增 M06b AC-11/25 归属说明
- 测试线索 `SEC-WF-002/003/006` -> `SEC-WF-003/006`

# 任务表解析（ADR §13.1）
21 tasks, sum=120h; T16a=7h, T17=7h; key path=75h
M26 §13.2: 6 tasks, sum=48h

# F-007 落点
ADR §13.1 T16a write_scope includes
  apps/api/src/test/java/com/riccon/api/security/**
  apps/api/src/test/java/com/riccon/api/audit/**
T16a content: TEN-M06-01/DEPT-M06-01/SEC-WF-002/003/006 -> surefire XML;
  IT-AUDIT-M06-01 -> audit tests; zero skip/zero xfail
T03 content/evidence: IT-WF-111; write_scope validation/**
ls apps/api/src/test/java/com/riccon/api/security
-> TenantIsolationSecurityTest.java DepartmentIsolationTest.java SecurityTestSupport.java m04 m04verify
ls apps/api/src/test/java/com/riccon/api/audit
-> KnowledgeAuditPolicyTest.java domain

# F-013/F-014 仍未决
ADR §8.3 and §16 U-05: 未解决
ADR §4.3 and §16 U-13: 独立安全评审仍待完成

# 未运行
未运行全量 Maven/前端测试、真实浏览器 E2E、axe、perf、迁移正反演练、
OpenAPI 运行时 validator；未运行 `riccon gate check --module M06`
（任务明确禁止，最终 gate 由 Lead 运行）。
```

## 未验证与剩余风险

- **未运行实现测试**：本轮是设计阶段 delta 复评，没有在实现提交上跑全量测试/E2E。
  T16a 的 surefire 零 skip/零 xfail、T03 的 `IT-WF-111`、`IT-AUDIT-M06-01` 仍需在
  实现任务和模块 gate 上验证。
- **F-013/F-014 未关闭**：U-05（审批理由入审计）与 U-13（跨租户 claim 例外）仍是
  M06 gate/T06a 前的安全项；本评审不声称安全评审通过。
- **F-022/F-023/F-024 为 accepted P3**：§12.4 审计覆盖的“共享”、ADR §16 U-03
  状态、T16a/T17 工时与 OPS-M06-013 文案、ADR §14/T16a 的 `SEC-WF-002` 子集，
  均需在 M06 gate 前由对应 owner 清理；它们不影响本轮 F-007/F-021 的 delta 结论。
- **OPS-017..023 不在本评审范围**：本轮只检查这些 ADR 修改没有破坏 §13.1 任务表/
  120h、T16a 写范围和 AC-15 文本；Ops 的独立 delta 复评仍由 Ops 评审负责。
- **ARCHITECTURE 状态表**：`docs/ARCHITECTURE.md` 仍写“REV-M06-ARCH-002 剩 F-007
  open、待复验”，这是本轮评审前的状态；Lead/Architect 应在 REV-M06-ARCH-003 落盘后
  更新为 Verifier pass/F-007 fixed，并保留 F-013/F-014 的 gate 前安全项。
- **评审期间工作区有未提交改动**：另一会话正在修改 `docs/ARCHITECTURE.md` 与
  `docs/adr/ADR-0024-m06-workflow-orchestration.md`（OPS-003 的回滚顺序、
  T01/T15/T16b 写范围与内容、§17 状态）。本评审按任务要求以已提交 `06b3db2` 为准，
  不把未提交改动当作本轮证据；这些改动不影响 F-007/F-021 的结论，但 Ops delta 与
  T01/T15 写范围互斥性需由 Ops 复评确认。
- **后续动作**：Lead 可按本评审结果推进 TASK-0217 的 done 与 M06 建卡；建卡时复核
  T16a 7h/T17 7h 与 F-024 的 SEC-WF-002 子集；M06 gate 前关闭 F-013/F-014 或继续
  以 accepted residual 跟踪。

本评审不替代模块 review、Product 预验收或用户产品验收；它是 TASK-0217 设计阶段的
独立技术 delta 验收。
