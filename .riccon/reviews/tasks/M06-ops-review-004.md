---
review_id: REV-M06-OPS-004
module_id: M06
task_id: TASK-0218
previous_review: REV-M06-OPS-003
reviewer:
  agent: riccon/ops
  session: ses_df14e751dffeRKnipYowo22wnY
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - a0b6216cee64b7a12cc4b13e734531f5b2e7f33d
  - 08f5a03503193afafaabb3e868e7f06d7ef396b4
baseline_commit: 06b3db297975a4fe29d502d669584dd09dd87298
head_commit: a0b6216cee64b7a12cc4b13e734531f5b2e7f33d
review_scope: >-
  M06 运维第四轮（最后 delta）复评，只审 REV-M06-OPS-003 剩余三条：OPS-M06-021（无窗口回滚顺序与
  “滞留清单”放行是否删除，T16b 是否按序断言）、OPS-M06-024（关闭态启动审计是否归 T01 / T15 是否移除
  config scope 避免双写 / 关闭态不抛错是否明确）、OPS-M06-025（首次禁用启动后“全 0”不可达、演练用
  独立空栈/备份夹具与顺序是否写清）；顺带确认 T01 worker Hikari 10→16 已入验收、21 任务/120h 与
  M26 48h 未变。未修改任何被评审文件、源码/测试/迁移、PRODUCT、AGENTS 或 .riccon 状态；未运行
  构建/测试/迁移/演练；按 Lead 要求未运行 `riccon gate check --module M06`。
tools_used: >-
  只读 shell（git log/show/diff、grep、sed、node 算术校验、date）。未使用 Serena/CodeGraph：delta 仅
  `docs/ARCHITECTURE.md` 与 `docs/adr/ADR-0024` 两份 Markdown，符号工具不适用；代码既成事实
  （KnowledgeAclStartupCheck、AuditDetailsPolicy）用 grep 只读核对并已在 REV-002/003 记录。
carried_over_findings: >-
  OPS-M06-001..020/022/023 状态沿用 REV-M06-OPS-002/003：OPS-001..006/008..011/013..022 fixed，
  OPS-007/012/023 accepted（owner/due/accepted_reason 见 REV-M06-OPS-002/003），本轮未回归变更。
findings:
  - id: OPS-M06-017
    severity: P1
    status: fixed
    title: "T01 migration 测试目录与应急 drop SQL 单一落点（沿用 REV-003）"
    summary: >-
      本轮未变更。T01 write_scope 仍含 `apps/api/src/test/java/com/riccon/api/migration/**`；§15.2 仍
      内联 drop SQL 于 V15MigrationTest、不新增 `scripts/ops/*.sql`；T16b 只做判定断言。V9–V14MigrationTest
      的 Testcontainers 先例支持该测试形态。
    evidence: >-
      docs/adr/ADR-0024 §13.1 T01 L820、§15.2 L912；
      apps/api/src/test/java/com/riccon/api/migration/{V9..V14}MigrationTest.java
  - id: OPS-M06-018
    severity: P2
    status: fixed
    title: "T16a real-stack M06 env 注入与快照登记（沿用 REV-003）"
    summary: >-
      本轮未变更。T16a 仍点名 `scripts/real-stack/run.mjs` 显式注入 M06 env，并把键/默认值/来源登记进
      `real-stack-env.json`；写范围含 `scripts/real-stack/**`。机制与 run.mjs 既有的 sharedModelEnv/
      buildEnvSnapshot/ENV_SNAPSHOT_FILE 一致。
    evidence: >-
      docs/adr/ADR-0024 §13.1 T16a L838；scripts/real-stack/run.mjs:117、778-820、900-940
  - id: OPS-M06-019
    severity: P2
    status: fixed
    title: "§8.1 白名单与 §4.8 一致；T01 承接 AuditDetailsPolicy（沿用 REV-003）"
    summary: >-
      本轮未变更。§8.1 `workflow.feature.changed` details 含 `instanceId`/`observedAt`；T01 承接
      `AuditDetailsPolicy.java` 与关闭态启动审计断言；AuditDetailsPolicy.validate 对白名单外键
      fail-closed，故白名单先行是必要的。
    evidence: >-
      docs/adr/ADR-0024 §4.8 L406、§8.1 L592、§13.1 T01 L820；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:391-423
  - id: OPS-M06-020
    severity: P2
    status: fixed
    title: "§3.5 可执行断言同提交 / RUNBOOK 由 T15 gate 前更新（沿用 REV-003）"
    summary: >-
      本轮未变更。CI/ApiApplicationTests/SchemaVersionCheckerTest/restore-drill 与 V15 同提交；
      RUNBOOK 由 T15 在 M06 gate 前更新（V1–V15、57/58、4 触发器、restore 15/4、MAX=15）。
    evidence: >-
      docs/adr/ADR-0024 §3.5 L274、§13.1 T15 L837；.github/workflows/ci.yml:237-246；
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:42-45
  - id: OPS-M06-021
    severity: P2
    status: fixed
    title: "回滚改为无窗口顺序：停领取+宽限→断言三者均 0→回滚→复断言；“滞留清单”放行已删除"
    summary: >-
      §10.4 受支持回滚重写为 8 步：1 关 ENABLED（取消仍可用）；2 关 CLAIM_ENABLED/停止领取并按
      SHUTDOWN_GRACE_SECONDS 等宽限，明确“宽限后不得再有新 claim”；3 取消 WAITING_APPROVAL 并断言 0；
      4 清零 PENDING/RUNNING，回滚前断言 `WAITING_APPROVAL=0 AND PENDING=0 AND RUNNING=0`，明确
      “不再允许已登记滞留清单放行”，可重复 3–4，**无法清零则不得回滚 Worker**；5 回滚 Worker；
      6 用保留的 M06 API **复断言 0**，出现新行（在途收尾）先取消/等待到 0 再宣告完成；7–8 保留 API
      与 V15 对象。§10.5 第 7 章与 §13.1 T16b 按同一顺序复述（关 ENABLED → 关 CLAIM/宽限 → WAITING=0 →
      PENDING/RUNNING=0 → 回滚 Worker → 复断言 0），T16b 明写“无窗口顺序断言”。竞争根因已按上轮要求
      消除：上轮指出的“ENABLED=false 不停 Worker”由第 2 步的 CLAIM_ENABLED+宽限覆盖；断言点前已无领取、
      断言后 API 只允许取消（创建/重试/审批决定 503），`workflow_run` 不再产生新行，窗口关闭；
      即便有强制停机时的在途事务延迟提交，第 6 步复断言兜底。
    evidence: >-
      docs/adr/ADR-0024 §10.4 L739-748、§10.5 L779、§13.1 T16b L839、§4.8 L406、§4.3 L335、
      §10.1 L691；T-M06-17 复核项含 OPS-M06-01
  - id: OPS-M06-022
    severity: P2
    status: fixed
    title: "Hikari 规则/claim 节奏/hikaricp 指标（沿用 REV-003），T01 默认 10→16 已入任务内容"
    summary: >-
      上一轮已 fixed；本轮 T01 描述进一步写明“worker Hikari `RICCON_DB_POOL_SIZE` 10→16，或按规则
      fail-closed”，§10.1 的启动校验（≥并发+4）与 §4.3/§10.2/§10.5 的轮询/退避/指标保持不变。
      worker 现状 `maximum-pool-size: ${RICCON_DB_POOL_SIZE:10}` 与 10+4 下限的冲突因此有明确处置。
    evidence: >-
      docs/adr/ADR-0024 §13.1 T01 L820、§4.3 L329、§10.1 L694-698、§10.2 L719、§10.5 L772/L774；
      apps/worker/src/main/resources/application.yml:19；scripts/real-stack/run.mjs:904
  - id: OPS-M06-023
    severity: P3
    status: accepted
    title: "文案/映射小项（a/b/c/e/f 已补；d 项 T15 4h 仍偏紧）"
    summary: >-
      本轮未变更，沿用上轮接受：AC-23 映射含 T16b；restore fixtures 58/57、triggers 4/3；§10.3 首发固定
      false；5 MiB 实插归 T16b；两个 scheduler pool 在 RUNBOOK 中区分。T15 仍 4h，范围未减，属“偏紧但
      非关键路径”，超支按 §13.1 候选后移调剂，不得删 RUNBOOK 回滚/预检章节。
    owner: "riccon/lead（建卡/工时）；T15（RUNBOOK）；T16b（容量报告）"
    due: "M06 建卡时（2026-11-25）；T15/T16b 完成前复核"
    accepted_reason: >-
      文案/映射/验收细化，不改变队列、回滚或审计语义，不影响 CI；T15 不在 75h 关键路径，模块有余量；
      5 MiB 实插与容量报告可在 T16b 内完成，不阻塞 T01 开工。
    evidence: >-
      docs/adr/ADR-0024 §3.5 L274、§10.3 L730、§10.5 L772、§13.1 L837-844、§14 L888
  - id: OPS-M06-024
    severity: P3
    status: fixed
    title: "启动观测组件归 T01（config main+test scope），T15 移除该 config scope，无双写"
    summary: >-
      §13.1 T01 write_scope 新增 `apps/api/src/main/java/com/riccon/api/config/**` 与
      `apps/api/src/test/java/com/riccon/api/config/**`，且 T01 描述保留“关闭态启动审计断言”；
      T15 的 write_scope 已删除 `apps/api/src/main/java/com/riccon/api/config/Workflow*.java`，全文仅 T01
      持有 `api/config` glob（grep 计数 2 = T01 的 main/test 两条），不再双写。落点与既有启动检查先例
      `KnowledgeAclStartupCheck`（`api/config`，ApplicationRunner）一致。“关闭态不抛错”的语义由 §4.8
      （关闭时写审计、启动继续）与 §17 OPS-019 行（“关闭态不抛错”）承载；建议建卡时把该句直接抄进
      T01 任务卡，避免只读任务表时歧义（风险项，不阻塞）。
    evidence: >-
      docs/adr/ADR-0024 §13.1 T01 L820 / T15 L837、§8.1 L592、§17 OPS-024 L1002；
      apps/api/src/main/java/com/riccon/api/config/KnowledgeAclStartupCheck.java:22,45；
      grep "api/config"（仅 T01 的 main+test 两条）
  - id: OPS-M06-025
    severity: P3
    status: fixed
    title: "首次禁用后“全 0”不可达已写清；演练改为独立空栈/备份夹具并按无窗口顺序执行"
    summary: >-
      §10.5 第 8 章新增：首次 `ENABLED=false` 启动会写 `workflow.feature.changed`，此后“全 0”不可达，
      试发布失败只能恢复 V15 前备份，且不得把该审计行排除出判定（除非用户新决策）。第 11 章与
      §13.1 T16b 改为：**先在未首次禁用启动的空栈验证“无数据允许”，再用独立栈/备份夹具验证“有数据
      拒绝”**，有数据侧不与首次禁用启动共用库；随后按无窗口顺序演练，并记录旧 Worker 来源
      （tag/镜像 digest）。§10.4 判定 SQL 仍为“任一 >0 必须拒绝”，与 AC-23/DEC-0047=A 一致，不存在
      “同库先关闭再断言无数据允许”的自相矛盾顺序。§17 该行把 §10.4 列为修改位置略不精确（实际落在
      §10.5/T16b），不影响可执行性。
    evidence: >-
      docs/adr/ADR-0024 §10.5 L780/L783、§13.1 T16b L839、§10.4 L765、§4.8 L406、§10.3 L730；
      docs/PRODUCT.md M06-AC-23 L840；.riccon/decisions/DEC-0047.md（A）
notes: >-
  设计阶段最后 delta 只读复核，未产生 EV（manual 证据不能建立 done）。delta 为 `a0b6216`（仅
  docs/ARCHITECTURE.md 与 docs/adr/ADR-0024，19+/16-）；`a0b6216..08f5a03`（当前 HEAD）只含
  TASK-0217 的评审/证据/状态变更，设计未变。未运行构建/测试/迁移/演练；按 Lead 要求未运行
  `riccon gate check --module M06`。本轮不标任务 done、不改状态。Ops 设计评审链（REV-001..004）至此
  关闭：0 open P0/P1/P2/P3。
created_at: "2026-11-25T02:06:21+08:00"
---

# REV-M06-OPS-004：M06 运维设计第四轮（最后 delta）复评

## 结论

**通过（pass）**。REV-M06-OPS-003 剩余三条全部闭合：

- **OPS-M06-021（P2）→ fixed**：§10.4/§10.5/T16b 改为无窗口顺序“关 ENABLED → 关 CLAIM_ENABLED/停领取 +
  宽限 → WAITING_APPROVAL=0 → PENDING/RUNNING=0 → 回滚 Worker → 保留 API 复断言 0”，删除
  “已登记滞留清单”放行，并明确“无法清零则不得回滚 Worker”。上轮指出的“ENABLED=false 不停 Worker”
  由 CLAIM_ENABLED+宽限覆盖，断言前后都不再有领取/新建入口，窗口关闭。
- **OPS-M06-024（P3）→ fixed**：启动观测组件与断言归 T01（`api/config/**` main+test），T15 删除该
  config scope，无双写；“关闭态不抛错”由 §4.8/§17 承载（建议抄进任务卡）。
- **OPS-M06-025（P3）→ fixed**：首次禁用后“全 0”不可达、试发布失败恢复备份、演练用独立空栈/备份夹具
  与演练顺序均写入 §10.5/T16b，与 AC-23/DEC-0047=A 一致。

无 open P0/P1/P2/P3。OPS-M06-001..020/022 为 fixed，OPS-007/012/023 为 accepted（owner/due/
accepted_reason 见历史评审与本文件）。任务/预算未变：21 任务、120h、关键路径 75h、M26 6 任务 48h。

## 本轮 delta 与方法

- `git show a0b6216`：只改 `docs/ARCHITECTURE.md`（Ops 评审状态行）与 `docs/adr/ADR-0024`
  （§10.4、§10.5、§13.1 T01/T15/T16b、§17），无源码/契约/迁移/PRODUCT/状态变更。
- `git diff a0b6216..08f5a03`：仅 TASK-0217 的 `.riccon` 评审/证据/状态文件，设计未变，故以 a0b6216
  为设计 head。
- 只读对账：`grep "滞留清单|CLAIM_ENABLED|复断言|不得回滚 Worker"`、`grep "api/config"` 全量计数、
  任务表逐行小时数求和（node 脚本）、`sed` 读取 §10.4/§10.5/§13.1/§14/§17 原文。

## OPS-M06-021：无窗口回滚（fixed）

新顺序（§10.4 L739-748）：

1. 关 `RICCON_WORKFLOW_ENABLED`：新运行/重试/审批决定 503，取消仍可用；
2. 关 `RICCON_WORKFLOW_CLAIM_ENABLED`（或停止 Worker 领取），按 `SHUTDOWN_GRACE_SECONDS` 等宽限，
   **宽限后不得再有新 claim**；
3. 用保留 API 取消 `WAITING_APPROVAL` 并断言 0；
4. 清零/取消 `PENDING`、`RUNNING`，回滚前断言 `WAITING_APPROVAL=0 AND PENDING=0 AND RUNNING=0`，
   **不再允许滞留清单放行**，可重复 3–4，**无法清零不得回滚 Worker**；
5. 回滚 Worker（旧 Worker 不认 `workflow_run`）；
6. **保留 M06 API 复断言 0**，出现新行先取消/等待到 0 再宣告完成；
   7–8. 保留 API 与 V15 对象，J1/J2 不受影响。

为什么窗口确实关闭（逐条核对状态跃迁）：

- 步骤 2 之后没有领取者（claim 关/Worker 停），`PENDING` 不会再进入 `RUNNING`，`RUNNING` 不再产生新
  `WAITING_APPROVAL`；
- 步骤 3/4 把三类行清零后，API 在 ENABLED=false 下只允许取消，创建/重试/审批决定全部 503，
  `workflow_run` 没有新行来源；
- 步骤 5 之后旧 Worker 不认该表；若强制停机导致在途事务延迟提交（步骤 4 与 5 之间），步骤 6 复断言
  捕获并清理后才宣告完成；
- 与 §4.3 的优雅停机语义自洽：宽限内到边界、超时退出、租约过期由其他实例恢复（但此时无实例领取）。

T16b（§13.1 L839）与 §10.5 第 7 章（L779）按同一顺序复述，且 T16b 明确“无窗口顺序断言”，与 §10.4
一致，可直接作为演练断言清单。上一轮的两条放行口（“PENDING/RUNNING 为 0 或已登记滞留清单”）已显式删除。

## OPS-M06-024：启动审计归 T01（fixed）

- T01 write_scope 新增 `apps/api/src/main/java/com/riccon/api/config/**` 与
  `apps/api/src/test/java/com/riccon/api/config/**`（L820）；
- T15 write_scope 删除 `apps/api/src/main/java/com/riccon/api/config/Workflow*.java`（L837）；
- 全文 `api/config` 只出现 2 次，均为 T01 的 main/test glob，无双写；
- 与先例一致：`KnowledgeAclStartupCheck` 就在 `api/config` 且是 ApplicationRunner，启动组件放这里合理；
- “关闭态启动不抛错”：§4.8（关闭时写 `workflow.feature.changed`，启动继续）+ §17 OPS-019 行
  （“关闭态不抛错”）；T01 任务卡只写“关闭态启动审计断言”。建议 Lead 建卡时把“关闭态启动成功、
  写一条审计、不抛错”原句写进验收，避免实现只断言白名单（风险项，不阻塞）。

## OPS-M06-025：首次禁用后全 0 不可达（fixed）

- §10.5 第 8 章：首次 `ENABLED=false` 启动写 `workflow.feature.changed` → “全 0”不可达；试发布失败
  只能恢复 V15 前备份；不得把该审计行排除出判定（除非用户新决策）。
- §10.5 第 11 章 + T16b：“无数据允许”在未首次禁用启动的空栈验证；“有数据拒绝”用独立栈/备份夹具；
  有数据侧不与首次禁用启动共用库；随后按无窗口顺序演练；旧 Worker 来源（tag/镜像 digest）必须记录。
- 与 §10.4 判定 SQL（任一 >0 拒绝）和 AC-23/DEC-0047=A 一致，消除了“同库先关闭再断言无数据允许”
  的自相矛盾。
- §17 把 §10.4 列为 025 的修改位置略不精确（实际在 §10.5/T16b），属描述性瑕疵，不影响执行。

## T01 / T15 / T16b 变化与“顺带确认”

| 项                        | 结果         | 依据                                                                                                                                           |
| ------------------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| T01 worker Hikari 10→16   | 已入任务内容 | T01 L820“（worker Hikari `RICCON_DB_POOL_SIZE` 10→16，或按规则 fail-closed）”；§10.1 启动校验 ≥并发+4；worker 现状默认 10 与下限冲突有明确处置 |
| T01 启动审计落点          | 已入 scope   | `api/config/**` main+test；T15 不再持有                                                                                                        |
| T15 config scope          | 已移除       | T15 L837 仅保留 runtime/worker config main/metrics/RUNBOOK                                                                                     |
| T16b 演练顺序             | 已明确       | L839：无窗口顺序 + 独立空栈/夹具 + 复断言 0 + 旧 Worker 来源                                                                                   |
| AC-23 映射                | 未变且正确   | §14 L888：T15、T16b、T17                                                                                                                       |
| 21 任务/120h              | 未变         | 脚本逐行求和 = 120h、21 行（T01..T17 拆分）                                                                                                    |
| 关键路径 75h / 前端链 41h | 未变         | 9+6+6+6+6+6+8+5+5+7+4+7=75；9+6+10+2+5+5+4=41                                                                                                  |
| M26 48h/6 任务            | 未变         | 4+8+10+8+10+8=48                                                                                                                               |

## 证据命令（只读）

```bash
git log -5 --format='%H %s'
git show --stat a0b6216
git show a0b6216 -- docs/adr/ADR-0024-m06-workflow-orchestration.md docs/ARCHITECTURE.md
git diff --stat a0b6216..HEAD && git diff --name-only a0b6216..HEAD
sed -n '737,752p;770,786p;885,890p;995,1004p' docs/adr/ADR-0024-m06-workflow-orchestration.md
grep -n "滞留清单\|CLAIM_ENABLED\|复断言\|不得回滚 Worker" docs/adr/ADR-0024-m06-workflow-orchestration.md
grep -n "api/config" docs/adr/ADR-0024-m06-workflow-orchestration.md
node -e "<读取 T-M06-* 行求和与计数>"   # 21 行 / 120h；M26-B* 6 行 / 48h
grep -n "Ops 可运维" docs/ARCHITECTURE.md
```

未执行构建/测试/迁移/演练；未运行 `riccon gate check --module M06`（按 Lead 要求避免污染 state）。

## 剩余风险

1. **多副本滚动应用 CLAIM_ENABLED**：步骤 2 的“宽限后不得再有新 claim”需确认**所有** Worker 副本都已
   停领取（滚动重启期间旧副本仍可能领取）。建议 RUNBOOK 给出按副本/实例核对的方法（`riccon_workflow_*`
   指标 label 或副本清单），演练在 2 副本下执行。
2. **强制停机下的在途事务**：步骤 4→5 之间若旧 Worker 的事务延迟提交，步骤 6 复断言能兜底，但演练必须
   真的制造“节点执行中被杀”的场景，否则复断言等于未验证。T16b 应记录该用例。
3. **旧 Worker 物资**：单机 preview 无 registry 时需按 tag/commit 构建旧 jar；§10.5 第 11 章已要求记录
   tag/镜像 digest，T16b 报告要落地这一字段，否则“回滚 Worker”可能退化为“停止领取”。
4. **T01 的 `api/config/**` 范围偏宽**：config 包含 SecurityConfig/AppConfig 等既有文件；虽无跨任务重叠，
   建议建卡时收窄为 `config/Workflow*.java` + 观测组件，降低误改面。
5. **T15 的 worker 启动校验测试落点**：T15 有 `worker/workflow/config/**`（main）但测试 scope 只有
   `worker/workflow/metrics/**`；若校验器落在 config 包，测试需落在 metrics 包（不自然）或由 T06a 的
   `workflow/queue/**` 承接（T06a 描述含“启动校验”）。建卡时明确 owner，避免“文档要求校验、实现无测试”。
6. **T15 4h 仍偏紧**（OPS-023 accepted 残留）：本轮 T15 又增“无窗口回滚顺序/首次禁用后备份恢复”两章；
   若超支按 §13.1 候选后移调剂，不得删回滚/预检章节。
7. **全量回滚窗口**：首次禁用启动后只能备份恢复（DEC-0047=A 的必然结果）。若客户要求试发布可干净回退，
   需用户新决策（排除 SYSTEM/platform 的 `workflow.feature.changed`），团队不得静默改 AC-23。

## 下一步

Ops 设计评审链（REV-M06-OPS-001..004）关闭，无 open finding。Lead 可据此建 M06 任务卡；实现后的
模块 gate 由 Verifier 执行，不由 Ops 代跑。建卡时建议落实上表第 1/2/3/5 项与 OPS-023 d 项（T15 工时复核）。
