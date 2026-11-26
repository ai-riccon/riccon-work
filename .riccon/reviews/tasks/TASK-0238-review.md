---
review_id: REV-TASK-0238-001
module_id: M06
task_id: TASK-0238
reviewer:
  agent: riccon/verifier
  session: ses_dead3ed7affeHC0nDR5UL97NZZ
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
review_timing: post-closeout
reviewed_commits:
  - db7e617b99a3921cb94290842f32e088596d392c
  - 99820a04
  - 8ff23f4754167320d8ca3eeeb062285385bbc7e9
head_commit: 5094cb0b726d78f4a511f569bd0029d186a7a81f
review_scope: >-
  M06 T16b（TASK-0238）在 T17 关闭前的独立证据复核：workflow-rollback-drill.mjs 的无窗口回滚
  顺序、无数据允许/有数据拒绝、等待审批与活跃运行清零、无法清零拒绝回滚、旧 Worker tag/digest、
  5 MiB 容量与包大小预检、首次禁用启动审计；复核 EV-1150（演练 27 assertions、exit 0）与
  EV-1151（IT-WF-109 上限定向 2 用例）的 commit/dirty/exit/日志/报告。未修改脚本或生产代码；
  不替代模块 review、Product 预验收或生产发布批准。
dod:
  acceptance: true
  build: true
  tests: true
  security: true
  rollback: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - accessibility
  - performance
  - migration
  - docs
dod_note: >-
  acceptance 记 true 但按 **pass (with residual)** 解释：EV-1150 的 27 条断言覆盖空栈允许、
  有数据拒绝（definitions/versions/runs/node_runs/approvals/tool_invocations/workflow audit 均
  >0）、5 MiB 结果近满、停止领取+宽限、WAITING/PENDING/RUNNING 清零、跨租户残留拒绝回滚、
  真实旧 Worker 容器启动且不领取、回滚后 API 历史/限制/健康 200、schema 15、首次禁用启动审计；
  EV-1151 的 2 个上限用例通过。残余是演练报告 `post-rollback-assert.schema.triggers=0`（测量权限
  导致，不是产品触发器缺失）、旧 Worker digest 来源为 operator、以及关闭开关下 J1/J2 冒烟未在
  演练脚本中执行；均按 P3 accepted 记录，见 findings。
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: "riccon/ops（演练脚本）/ riccon/verifier（复核）"
    due: "M09/release 回滚演练前"
    title: "演练报告 post-rollback triggers=0，未在脚本内断言 V15 触发器 4/4 保留"
    summary: >-
      workflow-rollback-drill.json 的 environment.source 为 tables=58/triggers=4/schema=15，
      但 post-rollback-assert.schema 为 tables=58/triggers=0/schema=15。核对脚本：
      createDrillDatabase 只给应用账号授予 SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/DROP/
      REFERENCES，没有 TRIGGER；mysqldump 用 admin 导入（含触发器），但 schemaMetrics 用应用
      账号读 information_schema.triggers，MySQL 对无 TRIGGER 权限的账号不显示触发器行，故 0 是
      测量权限伪影。脚本只断言 version=15，没有断言触发器数量，AC-23/RUNBOOK M06-12 的
      “触发器 4”未由该演练自证。V15MigrationTest 与 ApiApplicationTests 已独立断言 4 triggers。
    accepted_reason: >-
      产品侧 V15 触发器由迁移测试与 ApiApplicationTests 断言 4/4，演练脚本未看到触发器是测量
      权限问题，不是触发器被删除；回滚步骤保留 V15 对象，API 历史仍可读。作为 P3 evidence
      residual，由 ops 在 M09/release 演练前给演练账号补 TRIGGER 或改用 admin 读取并加断言。
    evidence: >-
      .riccon/evidence/TASK-0238/workflow-rollback-drill.json（environment.source /
      post-rollback-assert.schema）；scripts/ops/workflow-rollback-drill.mjs:588-610,706-715；
      apps/api/src/test/java/com/riccon/api/migration/V15MigrationTest.java:198-207；
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:38-46。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/ops
    due: "M09/release 回滚演练前"
    title: "旧 Worker 回滚目标 digest 由 operator 提供，且回滚窗口关闭 schema 校验"
    summary: >-
      EV-1150 报告 rollbackTarget.digestSource=operator、digest/localImageId 匹配
      `riccon-worker-ocr:o1b`；preRollbackWorker 只记录本机 jar sha256，未提供该 tag 与 M06 前
      生产镜像的签名/来源证明。worker-rollback 以 `schemaValidation=disabled-for-rollback-window`、
      expectedSchema=14 启动旧 Worker，断言不领取且运行计数保持 0。演练可以证明“旧 Worker 不
      识别 workflow_run、不领取”，但不能证明 operator 提供的 digest 一定是正确 M06 前版本。
    accepted_reason: >-
      digest 匹配本地镜像且旧 Worker 行为断言通过；生产回滚按 RUNBOOK 由人工核对 tag/digest 与
      工单，演练脚本不替代发布审批。作为 P3 accepted，M09 生产演练应改为签名镜像/发布记录校验。
    evidence: >-
      .riccon/evidence/TASK-0238/workflow-rollback-drill.json（worker.rollbackTarget、
      phases.worker-rollback）；docs/RUNBOOK.md M06-8/M06-12。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/ops（演练）/ riccon/verifier（模块回归）"
    due: "M06b/release 前"
    title: "关闭编排开关后的 J1/J2 冒烟未在演练脚本中执行"
    summary: >-
      AC-23 与 RUNBOOK M06-12 第 4 步要求“回滚后 J1/J2 冒烟通过”。EV-1150 的
      post-rollback-assert 只断言 workflow 历史/limits API 200、actuator health 200、schema 15；
      没有在 `RICCON_WORKFLOW_ENABLED=false` 下调用 J1/J2。EV-1148 的真实栈 full 在开关开启时
      覆盖 J1/J2，但不等价于关闭态回归。代码层面 J1/J2 与 workflow 端点/队列表分离，风险有界。
    accepted_reason: >-
      关闭态 503 仅作用于 workflow 端点（WorkflowRun/Approval 服务），J1/J2 不经过该路径；
      演练已证明 API 在关闭态可启动且历史可读。作为 P3 accepted，由 ops 在 M06b/release 前把
      J1/J2 冒烟加入演练或在模块 E2E 补关闭态用例。
    evidence: >-
      .riccon/evidence/TASK-0238/workflow-rollback-drill.json（phases.post-rollback-assert）；
      docs/RUNBOOK.md M06-12；docs/PRODUCT.md M06-AC-23；EV-1148 的 J1/J2 开启态回归。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/ops
    due: "M09/release 容量校准时"
    title: "容量 p95 基于 13 个样本，报告已写 caveat"
    summary: >-
      EV-1150 的 capacity-5mib 使用源库 workflow_run.result_size_bytes nearest-rank p95=482B、
      13 个样本、overhead 1.35、180 天、备份 2x，得到 22.34 MiB；同时给出每次运行接近 5 MiB 的
      上界 243000 MiB。报告明确写“源库为假模型测试数据时 p95 偏小；生产必须用客户真实分位数
      校准”。容量报告是部署预留输入，不是 M06 功能验收。
    accepted_reason: >-
      公式、样本数、上界与 caveat 均记录，未把假模型 p95 冒充生产容量；5 MiB 实插与存储/传输
      不截断已由本演练断言。作为 P3 accepted，生产容量校准归 M09/客户真实数据。
    evidence: >-
      .riccon/evidence/TASK-0238/workflow-rollback-drill.json（phases.capacity-5mib）；
      docs/RUNBOOK.md M06-11。
evidence:
  - EV-1150
  - EV-1151
reviewed_evidence:
  - EV-1150
  - EV-1151
notes: |-
  - EV-1150：命令运行 `scripts/ops/workflow-rollback-drill.mjs --apply --grace-seconds 2
    --old-worker-tag riccon-worker-ocr:o1b --old-worker-digest sha256:986d...`，exit 0，
    commit=db7e617b、dirty=false；报告 verdict=pass、assertions=27、failed=0、duration 67s。
  - EV-1151：`WorkflowEngineIntegrationTest#resultOverLimit_marksOverLimit+nodeOutputOverLimit_failsNode`
    2 用例 exit 0，commit=99820a04、dirty=false。
  - 本评审在 T17 进行，属于 closeout 后的独立证据审计；T16b 任务卡要求 done 前有独立评审，
    本评审不声称该流程时点已满足，模块评审记录该流程缺口。
created_at: "2026-11-26T08:53:00+08:00"
---

# REV-TASK-0238-001：TASK-0238（M06 T16b）独立证据评审

## 结论

**通过（pass，with residual）**。EV-1150 的无窗口回滚演练 27 条断言全过：空栈允许、有数据拒绝、
5 MiB 近满容量、停止领取与宽限、WAITING/PENDING/RUNNING 清零、跨租户残留拒绝回滚、真实旧
Worker 容器不领取、回滚后 API/历史/schema 15 正常、首次禁用启动审计存在；EV-1151 的上限
2 用例通过。残余为演练触发器计数测量权限、旧 Worker digest 来源、关闭态 J1/J2 冒烟与容量样本，
均为 P3 accepted。

## 验证了什么

- 回滚顺序：report phases 覆盖 empty-allow、data-reject、capacity-5mib、claim-stop-grace、
  clear-active、refuse-dirty、worker-rollback、post-rollback-assert、disabled-startup-audit、
  cleanup；27 assertions，failed=0。
- 有数据拒绝：definitions/versions/runs/node_runs/approvals/tool_invocations/workflow audit 均
  > 0，guard exit 3，拒绝全量回滚/应急 drop。
- 清零与拒绝：WAITING_APPROVAL/PENDING/RUNNING 清零；跨租户残留 WAITING_APPROVAL 时拒绝回滚。
- 5 MiB：result payload 5,238,768/5,242,880（sha256 match、JSON valid、stored matches），
  node output 261,116/262,144。
- 旧 Worker：容器启动、schema validation disabled、expected 14、counts 0→0、noNewClaims=true。
- 证据完整性：EV-1150/1151 均 clean、exit 0、commit/dirty 可追溯。

## 未验证

- 生产真实镜像签名/发布记录：F-002。
- 关闭态 J1/J2 E2E：F-003。
- 生产真实容量分位：F-004。

## Findings

F-001..F-004 均为 P3 accepted，owner/due/accepted_reason 见 front matter。

## 剩余风险

AC-23 的“触发器 4 保留”由 V15MigrationTest/ApiApplicationTests 支撑，不由本演练自证；生产回滚
仍需 ops 人工核对镜像 digest、备份与用户书面同意。M06 不做 down migration。
