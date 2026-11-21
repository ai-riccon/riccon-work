---
review_id: REV-M04-006
previous_review: REV-M04-005
module_id: M04
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e028b1e0cffegSYGgmhSpG7xDC
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module-supplement
verdict: pass
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
reviewed_task_reviews:
  - REV-M04-ARCH-005
  - REV-M04-OPS-006
  - REV-TASK-0100-001
  - REV-TASK-0101-002
  - REV-TASK-0102-001
  - REV-TASK-0103-002
  - REV-TASK-0104-001
  - REV-TASK-0105-003
  - REV-TASK-0106-001
  - REV-TASK-0107-002
  - REV-TASK-0108-002
  - REV-TASK-0115-002
  - REV-TASK-0128-001
findings:
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-key / riccon/builder-gateway-e2e"
    due: "M05 gate 前"
    title: "AC-07 在途轮换仍只证明旧密文可解密，未覆盖真实并发流式请求（V1 F-006）"
    accepted_reason: >-
      原评审已接受，本补充仅补理由；不追溯改写 REV-M04-005。现有 E2E-M04-04 + IT-MODEL-106 已证明
      旧密文可解密、轮换影响文案与无明文泄露，缺口只是“真实并发流式请求”用例；由 M05 gate 前
      builder-key/builder-gateway-e2e 补真实并发流式轮换用例承接；M20 不涉及该残余。
    evidence: "TASK-0115-report-002 F-006"
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-route"
    due: "M05 gate 前"
    title: "非 503 Worker detail 仍写入 run.error_message；当前为静态脱敏文本（V1 F-013）"
    accepted_reason: >-
      原评审已接受，本补充仅补理由；不追溯改写 REV-M04-005。当前写入 run.error_message 的是固定
      脱敏文案，不含 provider 地址、key、堆栈或用户输入，风险有界；由 M05 gate 前 builder-route
      收敛 Worker detail 的写入/展示策略承接；M20 不涉及该残余。
    evidence: "TASK-0115-report-002 F-013"
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/ops"
    due: "M09 交付前"
    title: "restore-drill 报告仍为 partial（J1 skipped）；V2 以独立 API/Worker 在恢复库补跑 J1 与历史读取"
    accepted_reason: >-
      原评审已接受，本补充仅补理由；不追溯改写 REV-M04-005。V2 已用独立 API/Worker 在恢复库补跑
      J1 与历史读取，restore-drill 报告的 J1 skipped 只是演练清单口径未更新；由 M09 交付前 ops
      完成完整演练报告承接；M20 不涉及该残余。
    evidence: "EV-0683；EV-0684"
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/builder-route"
    due: "M05 gate 前"
    title: "AC-01 仍无单条 UI 保存→下一次 CHAT 请求的跨组件 E2E（V1 残余）"
    accepted_reason: >-
      原评审已接受，本补充仅补理由；不追溯改写 REV-M04-005。API 读取新链与 Worker 路由解析已有
      IT-MODEL-101 间接覆盖，缺的是单条 UI 保存到下一次 CHAT 的跨组件 E2E；由 M05 gate 前
      builder-route 补该 E2E 承接；M20 不涉及该残余。
    evidence: "TASK-0115-report-002 AC-01 残余"
  - id: F-012
    severity: P2
    status: accepted
    owner: "riccon/builder-web / riccon/ops"
    due: "M05 gate 前"
    title: "合同 test/coverage 的 web 套件两次 flake（不同用例），第三次通过；单文件与 web 单独全量均通过"
    accepted_reason: >-
      原评审已接受，本补充仅补理由；不追溯改写 REV-M04-005。EV-0673/0674 的失败是不同用例的
      flake，EV-0675 第三次通过且单文件与 web 单独全量均通过；失败 EV 保留，未用
      `--no-file-parallelism` 等参数掩盖。由 M05 gate 前 builder-web/ops 消除 flake 承接；
      M20 不涉及该残余。
    evidence: "EV-0673；EV-0674；EV-0675"
  - id: F-013
    severity: P2
    status: accepted
    owner: "riccon/architect / riccon/builder-gateway-e2e"
    due: "M05 gate 前"
    title: "PERF API p95 测量口径收窄为稳态 JSON API（排除登录/setup；SSE TTFB 单列），需 Architect 对齐 PRODUCT/ADR 措辞"
    accepted_reason: >-
      原评审已接受，本补充仅补理由；不追溯改写 REV-M04-005。性能阈值未变，业务 API p95 61.8ms、
      首字 p95 32ms 均有 EV-0669 证据，缺口只是 PRODUCT/ADR 对“API p95”测量口径的措辞未同步；
      由 M05 gate 前 architect 对齐文档、builder-gateway-e2e 维持脚本口径承接；M20 不涉及该残余。
    evidence: "EV-0669；e2e/perf/README.md"
notes: "补充评审：不替代 M04-review.md，不参与 gate；DoD 与证据绑定以 REV-M04-005 为准。"
created_at: "2026-11-21T17:37:49+08:00"
---

# M04 模型网关纵深 · 模块补充评审（REV-M04-006）

本文件是**补充评审**，不替代 `.riccon/reviews/M04-review.md`（最新 `REV-M04-005`），也不参与模块 gate。它只做两件事：

1. 补齐 `REV-M04-005` 中 6 条 `accepted` finding 缺失的 `accepted_reason`；
2. 补齐 `REV-M04-005` 缺失的 `reviewed_task_reviews` 列表，使模块评审覆盖 M04 当前每个任务的最新 task review。

`dod` 沿用 `REV-M04-005` 的键值（全部 true），但本文件**没有重跑** build/test/coverage/E2E/a11y/security/perf，也不重新绑定 `dod_evidence`；M04 的 DoD 证据以 `REV-M04-005` 为准。`verdict: pass` 只表示“补充评审通过”，不改变 M04 已完成的模块 gate、Product 预验收与用户验收结论。

## 背景与边界

- `REV-M04-005` 于 `2026-11-21T16:20:00+08:00` 给出 `verdict: pass`，DoD 全 true；M04 gate 于 `2026-11-21T16:33:27+08:00` 通过，Product 预验收 pass，用户最终验收 accepted（DEC-0027）。
- `REV-M04-005` 的 F-006、F-008、F-009、F-010、F-012、F-013 状态为 `accepted`，但只写了 owner/due/title，缺 `accepted_reason`；本补充只补理由，不追溯改写 005 原文。
- `REV-M04-004`、`REV-M04-003` 已从 git history 恢复到 `.riccon/archive/reviews/`（`M04-review-REV-M04-004.md`、`M04-review-REV-M04-003.md`）；本补充不追溯改写这两份旧评审。
- 未修改 `.riccon/reviews/M04-review.md`、历史 task review、`.riccon/state.json`、任务/模块状态、生产源码、测试、迁移或合同；只新增本文件与 `tasks/TASK-0108-review-002.md`。
- M04 的 6 条残余与 M20 的 8 条 P2 是两组独立事项：`REV-TASK-0108-002` 的 F-001..F-012 由 M20（TASK-0140..0143）承接；本文件的 F-006/008/009/010/012/013 属于 M04-005，按原 due 由 M05/M09 承接。

## reviewed_task_reviews（以实际最新为准）

| 任务      | 最新 review_id      | 说明                                                                                                                                                |
| --------- | ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| TASK-0089 | `REV-M04-OPS-006`   | 最新为 Ops 第 6 轮（created_at `2026-11-20T20:52:31+08:00`）；`REV-M04-ARCH-005` 为同任务较早的架构设计评审（`20:44:00`），一并列出以保留设计侧结论 |
| TASK-0100 | `REV-TASK-0100-001` | B1a                                                                                                                                                 |
| TASK-0101 | `REV-TASK-0101-002` | B1b（覆盖 `REV-TASK-0101-001`）                                                                                                                     |
| TASK-0102 | `REV-TASK-0102-001` | B1c                                                                                                                                                 |
| TASK-0103 | `REV-TASK-0103-002` | B2（覆盖 `REV-TASK-0103-001`）                                                                                                                      |
| TASK-0104 | `REV-TASK-0104-001` | B3                                                                                                                                                  |
| TASK-0105 | `REV-TASK-0105-003` | B4（覆盖 `REV-TASK-0105-001/002`）                                                                                                                  |
| TASK-0106 | `REV-TASK-0106-001` | B5                                                                                                                                                  |
| TASK-0107 | `REV-TASK-0107-002` | B6（覆盖 `REV-TASK-0107-001`）                                                                                                                      |
| TASK-0108 | `REV-TASK-0108-002` | B7；本补充配套的残余闭环评审（覆盖 `REV-TASK-0108-001`）                                                                                            |
| TASK-0115 | `REV-TASK-0115-002` | V1（覆盖 `REV-TASK-0115-001`）                                                                                                                      |
| TASK-0128 | `REV-TASK-0128-001` | ACT（`/actuator/info` 收口）                                                                                                                        |

核对方法：遍历 `.riccon/reviews/tasks/` 中 `module_id: M04` 的文件，按 `task_id` 取 `created_at` 最新的一份；同任务旧 review 不删除、不改写，仅在此表标注覆盖关系。TASK-0089 同时存在架构与 Ops 两条评审，按时间 latest 为 `REV-M04-OPS-006`；表中保留 `REV-M04-ARCH-005` 便于追溯设计侧结论。

## 补充的 accepted 理由

### F-006（P2，owner `riccon/builder-key / riccon/builder-gateway-e2e`，due M05 gate 前）

原评审已接受，本补充仅补理由。AC-07 的现有证据（E2E-M04-04、IT-MODEL-106）证明了旧密文可解密、轮换影响文案与无明文泄露；缺口只是缺少“真实并发流式请求”用例。轮换窗口短、旧 key 会停用，且当前无明文暴露，风险有界。该残余由 M05 gate 前的 builder-key/builder-gateway-e2e 用例承接；M20 不涉及。

### F-008（P2，owner `riccon/builder-route`，due M05 gate 前）

原评审已接受，本补充仅补理由。非 503 的 Worker detail 写入 `run.error_message` 时当前是固定脱敏文本，不含 provider 地址、key、堆栈或用户输入，风险有界。由 M05 gate 前 builder-route 收敛 Worker detail 的写入/展示策略承接；M20 不涉及。

### F-009（P3，owner `riccon/ops`，due M09 交付前）

原评审已接受，本补充仅补理由。V2 已用独立 API/Worker 在恢复库补跑 J1 与历史读取，restore-drill 报告的 J1 skipped 是演练清单口径问题，不是恢复能力未验证。由 M09 交付前的 ops 完整演练报告承接；M20 不涉及。

### F-010（P3，owner `riccon/builder-route`，due M05 gate 前）

原评审已接受，本补充仅补理由。API 读取新链与 Worker 路由解析已由 IT-MODEL-101 间接覆盖，缺的是单条 UI 保存 → 下一次 CHAT 请求的跨组件 E2E。由 M05 gate 前 builder-route 补该 E2E 承接；M20 不涉及。

### F-012（P2，owner `riccon/builder-web / riccon/ops`，due M05 gate 前）

原评审已接受，本补充仅补理由。EV-0673/0674 的失败是不同用例的 flake，EV-0675 第三次通过，单文件与 web 单独全量均通过；失败 EV 保留，未用 `--no-file-parallelism` 等参数掩盖。由 M05 gate 前 builder-web/ops 消除 flake 承接；M20 不涉及。

### F-013（P2，owner `riccon/architect / riccon/builder-gateway-e2e`，due M05 gate 前）

原评审已接受，本补充仅补理由。性能阈值未变，业务 API p95 61.8ms、首字 p95 32ms 均有 EV-0669 证据；缺口只是 PRODUCT/ADR 对“API p95”测量口径的措辞未同步。由 M05 gate 前 architect 对齐文档、builder-gateway-e2e 维持脚本口径承接；M20 不涉及。

## 与 M04-005 的关系

- 本文件不改变 `REV-M04-005` 的 verdict、DoD、findings 状态、owner 或 due；只补 `accepted_reason` 与 `reviewed_task_reviews`。
- `REV-M04-005` 的 F-001..F-005、F-011 已为 `fixed`，本文件不重复列出。
- `REV-M04-005` 的 F-006/008/009/010/012/013 仍按原 due 跟踪；M05/M09 关闭时应在对应模块评审引用本文件与本补充。
- `REV-TASK-0108-002` 是另一条线：处理 TASK-0108（B7）的 12 条残余，全部由 M20 承接；不要与本文件的 6 条 M04 残余混淆。

## 验证

- 运行 `riccon state validate --project .`：0 errors、42 warnings；写入前基线为 43 warnings。新增的 `REV-M04-006` 与本补充配套的 `REV-TASK-0108-002` 自身 0 warning；总数减少 1 条来自 TASK-0108 最新 task review 的切换。
- `REV-M04-005` 的 legacy warning（6 条缺失 `accepted_reason`、12 条缺失 `reviewed_task_reviews`）仍会指向旧文件；本补充不追溯改写，故这些 warning 不会因本文件消失。

## 未验证

- 本补充是文档级评审，未重跑 M04 的 build/lint/typecheck/test/coverage/E2E/a11y/security/perf，也未重录任何 EV；M04 DoD 以 `REV-M04-005` 的 `dod_evidence` 为准。
- 未验证 M05/M09 是否已具备承接上述 6 条残余的任务卡；本补充只补理由，不新建任务、不改任务状态。
- 未修改历史评审或证据，无法消除 `REV-M04-005` 自身的 state validate legacy warning（缺 `accepted_reason` 的 warning 仍会指向旧文件）；本文件提供可追溯的补充理由，供后续评审与 gate 使用。

## 独立性声明

本评审使用独立会话 `ses_e028b1e0cffegSYGgmhSpG7xDC`，直接读取 `REV-M04-005`、全部 M04 task review、M04 模块状态、TASK-0108/M20 任务卡与验收文档；未采信 builder 总结，未修改生产源码、测试、迁移、合同、任务状态或历史文件。

## Gate

本文件是补充评审，不参与 gate，不替代 `M04-review.md`。M04 gate 已于 `2026-11-21T16:33:27+08:00` 通过；M20 尚未启动，M20 的 gate 需要独立模块评审按 M20 的验收标准重新验证。
