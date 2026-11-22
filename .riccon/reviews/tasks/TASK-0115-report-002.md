---
review_id: REV-TASK-0115-002
module_id: M04
task_id: TASK-0115
title: M04-V1 delta 复核（三个 P1 修复后；AC-01、AC-03..16；不判 AC-02 整条/17/18）
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e0426351dffeuTc5rUnx2hbjke
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
supersedes: REV-TASK-0115-001
reviewed_commits:
  - ff6c38364216f72ac48fe958f9310b2ac52c45a3
  - ffd12aca7f0487bb06793d8d453a6e2e11af38c3
  - 493067a9d776b016bc977e3dadb2c82e80bf1a7d
  - dc95e6c66b50eb607c07fd7ce15e036e52ce8c19
  - fb82173050d344bd2a4b10d1ba8a42cd1ffc7df2
  - 6bc0c43aad9b5a063963e9823af70fe9841bc551
  - 93c6498da1bd17776b7665a289ded407ae3e04eb
  - a6c59a53606e179ce3a6f500fab4246a53348efa
created_at: "2026-11-21T12:00:00+08:00"
evidence:
  - EV-0623
  - EV-0624
  - EV-0625
  - EV-0626
  - EV-0627
  - EV-0628
  - EV-0637
  - EV-0638
  - EV-0639
referenced_builder_evidence:
  - EV-0634
  - EV-0635
  - EV-0636
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-route（TASK-0132）"
    title: "ChatService 不再打印 Worker detail 原文；canary 日志零命中"
  - id: F-002
    severity: P1
    status: fixed
    owner: "riccon/builder-route（TASK-0133）"
    title: "模型网关开关 GET/PUT 端点已实现并通过独立权限/审计/版本/环境覆盖复核"
  - id: F-003
    severity: P1
    status: fixed
    owner: "riccon/builder-gateway-e2e（TASK-0134）"
    title: "B11b 恢复路由真实保存；全量真实栈 25/25 通过，E2E-M04-02 通过"
  - id: F-004
    severity: P2
    status: fixed
    owner: "riccon/builder-gateway-e2e（TASK-0134）"
    title: "checks.mjs 使用显式 J1 run；securityChecks/m04Checks 通过"
  - id: F-005
    severity: P2
    status: fixed
    owner: "riccon/lead"
    title: "TASK-0112/TASK-0128 的 5 条 dirty EV 已从任务证据移除，gate 不再报 dirty"
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-key / riccon/builder-gateway-e2e（V2）"
    title: "AC-07 在途轮换仍只证明旧密文可解密，未覆盖真实并发流式请求"
  - id: F-013
    severity: P2
    status: accepted
    owner: "riccon/builder-route / riccon/verifier（V2 安全评审）"
    title: "非 503 Worker detail 仍会写入 run.error_message；本轮未做 canary，建议 V2 冲减或书面接受"
  - id: F-014
    severity: P2
    status: open
    owner: "riccon/lead（closeout）"
    title: "gate 对 review 任务按 baseline..HEAD 计算 write_scope，会把修复提交算到 TASK-0115"
  - id: F-015
    severity: P3
    status: open
    owner: "riccon/lead / riccon/ops"
    title: "TASK-0024 real-report.json 再次被 real-stack --list 覆盖；已恢复到 HEAD 版本"
---

# M04-V1 delta 复核报告（F-001/F-002/F-003 修复后）

## 结论

**M04-V1：pass（带 P2/P3 残余）。** 三个 P1 均已在当前代码上独立复验为 **fixed**：

1. **F-001 日志泄露**：`ChatService` 不再输出 Worker `detail`；独立复跑 `ChatModelUnavailableApiTest` 6/6，surefire canary grep **零命中**，`canary-scan-002.log` clean。
2. **F-002 开关端点**：`/api/v1/admin/model-gateway/switches` GET/PUT 已实现；独立复跑 `ModelGatewaySwitch*` 18/18 + Verifier 独立类 1/1，覆盖 GET/PUT/409/审计/权限/环境覆盖；真实栈 checks 使用 switch API 且 `m04Checks=passed`。
3. **F-003 真实栈假绿**：B11b 恢复路由改为真实 PUT + 版本/API/DB 三重复核；**EV-0635 全量真实栈 25/25 通过、E2E-M04-02 passed**；独立定向 `E2E-M04-02` **EV-0639 1/1 passed**；DB 审计显示两次恢复保存（version +1/+2）后才进入 B11a。

因此 **V1 负责的 15 条 AC（AC-01、AC-03..16）全部 pass**；AC-02 的模型/key/开关段证据全部 pass，**不判 AC-02 整条、AC-17、AC-18**（V2）。

**重要边界**：本 delta 只跑定向修复验证，**未重跑全量合同 test/coverage/E2E/a11y/security**。EV-0623..0628 是修复前提交的全量历史证据，不能作为最终代码的模块 review 证据；V2 必须在最终提交重跑全量合同并按 AC-02/17/18 收口。

## 修复证据与独立复核

| 修复               | 提交                 | Builder EV                                       | V1 独立复核                                                                                                                                                                                                                                                            |
| ------------------ | -------------------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 日志         | `ff6c383`            | EV-0636 clean（17 tests + surefire grep 零命中） | **EV-0638**：`ChatModelUnavailableApiTest` 6/6；`canary-scan-002.log` 零命中；日志行只含 `status/code/runId/traceId`（surefire `chat.worker.rejected status=500 code=RICCON-MODEL-503 runId=…`）                                                                       |
| F-002 开关         | `ffd12ac`            | EV-0634 clean（18 tests）                        | **EV-0638**：`ModelGatewaySwitch*` 18/18；新增 `M04VerifierSwitchDeltaTest` 1/1（平台 GET/PUT、version 2、旧版本 409、3 条审计、租户/员工 403、匿名 401）                                                                                                              |
| F-003/F-004 真实栈 | `493067a`、`dc95e6c` | **EV-0635 clean 全量真实栈 passed**              | **EV-0639**：`REAL_PLAYWRIGHT_GREP='E2E-M04-02'` 定向 **1 passed**；DB 查询确认 EV-0635 在 03:41:45/46（UTC）连续两次 `model.routing.updated` 将 CHAT 从 `[secondary, primary, disabled]` 恢复为 `[primary, secondary, disabled]`（version 2→3），随后 E2E-M04-02 通过 |
| F-004 checks       | `493067a`            | EV-0635 `securityChecks/m04Checks=passed`        | 检查 `real-stack-checks.log`：`switches=3`、`routing_audit=5`、`status=passed`；J1 使用显式 run，不再取“最新 CHAT run”                                                                                                                                                 |
| F-005 dirty EV     | Lead 清理            | —                                                | `gate check` 不再列出 EV-0577/0579/0580/0597/0598                                                                                                                                                                                                                      |

## AC 对账（V1：AC-01、AC-03..16）

| AC    | delta 结论                 | 证据                                                                                                                                                                                                                                                                      |
| ----- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-01 | **pass**                   | E2E-M04-01（EV-0624/历史）；IT-MODEL-101 + `M04VerifierGatewaySecurityTest.ac01`（EV-0623/0638）；真实栈 key 生命周期（EV-0635）保存路由审计。残余 P3：无单条“UI 保存→下一次 CHAT 请求”E2E。                                                                              |
| AC-03 | **pass（原 fail 已修复）** | IT-MODEL-103（EV-0623 17 tests）；**EV-0635 全量 25 passed 且 E2E-M04-02 passed**；**EV-0639 定向 E2E-M04-02 1 passed**；DB 审计确认 a11y 测试两次保存恢复 seed 顺序；worker 日志显示降级后成功。                                                                         |
| AC-04 | **pass**                   | IT-MODEL-104（EV-0623）；E2E-M04-03（EV-0635 第 15 条）pass；首字后保留/不重复/审计 emitted。                                                                                                                                                                             |
| AC-05 | **pass**                   | `M04-AC-05` 真实栈（EV-0635 第 17 条）pass；`ChatModelUnavailableApiTest` 6 tests（EV-0638）pass。残余 P3：未断言已上传文件保留。                                                                                                                                         |
| AC-06 | **pass**                   | IT-MODEL-105（EV-0623）、SEC-M04-006（EV-0623）、真实栈 key 生命周期（EV-0635 第 16 条）pass；多 key/401 失效/恢复审计。                                                                                                                                                  |
| AC-07 | **pass（带 P2 残余）**     | E2E-M04-04（EV-0624 第 29 条）、IT-MODEL-106（EV-0623）、`M04VerifierGatewaySecurityTest.ac06_ac07`；轮换先插新后停旧/旧密文保持/掩码/审计。F-006：真实并发在途流仍未覆盖（P2 accepted）。                                                                                |
| AC-08 | **pass**                   | E2E-M04-05（EV-0624 第 25 条、EV-0635 第 11 条）；IT-QUOTA-101/105（EV-0623）；429/重置时间/租户只读/拒绝审计。                                                                                                                                                           |
| AC-09 | **pass（integration）**    | IT-QUOTA-102（EV-0623）50 并发 1 成功/49×429/used=1；性能门槛 V2。P3：提额后重试无单独断言。                                                                                                                                                                              |
| AC-10 | **pass**                   | E2E-M04-05（EV-0624/0635）；IT-QUOTA-103（EV-0623）；平台聚合/租户只读/审计。                                                                                                                                                                                             |
| AC-11 | **pass**                   | E2E-M04-06（EV-0624 第 26 条、EV-0635 第 12 条）；IT-COST-101/102/103（EV-0623）；快照/舍入/未定价/未分桶/免责文案。                                                                                                                                                      |
| AC-12 | **pass**                   | E2E-M04-06（EV-0624/0635）；IT-COST-104（EV-0623）；平台无用户字段/跨租户 404/员工无入口。                                                                                                                                                                                |
| AC-13 | **pass**                   | E2E-M04-07a（EV-0624 第 30 条）、E2E-M04-07b（EV-0624 第 27 条）；真实栈四页（EV-0635 第 13 条）pass。                                                                                                                                                                    |
| AC-14 | **pass（带 P3）**          | EV-0625 15/15 a11y；E2E-M04-07a/07b 键盘/375px/axe；真实栈四页 pass。P3：焦点指示样式未断言。                                                                                                                                                                             |
| AC-15 | **pass（原 fail 已修复）** | 独立 EV-0638：6 个 Chat 测试 + canary 零命中；`M04VerifierGatewaySecurityTest.ac15` + `M04VerifierProductionEndpointTest` + `ProductionEndpointPolicyApiTest`；端点标签/不可写/生产拒绝/拒绝原因均通过。**残余 P2 F-013**：非 503 detail 仍写入 run.error_message；见下。 |
| AC-16 | **pass**                   | E2E-M04-08（EV-0624 第 31 条）；IT-MODEL-108a/108b（EV-0623）；EV-0635 的 J1/J2/M03/M10 24 条通过（仅 M04-02 现在也通过，共 25）；历史调用可读。                                                                                                                          |

## AC-02 模型/key/开关段（只提供证据，不判整条）

- 模型/key 写越权：`SEC-M04-003a`/`TEN-031` 由 `M04VerifierGatewaySecurityTest.ten031` 覆盖（EV-0623/0638），pass。
- **开关段：pass**。`M04VerifierSwitchDeltaTest` 独立断言平台 GET/PUT、version 2、旧版本 409、3 条 `model.gateway.switch.changed` 审计、租户管理员/员工 403、匿名 401；Builder EV-0634 的 18 tests + env override 2 tests 独立复跑通过；EV-0635 真实栈 `checks` 使用 switch API 且 `m04Checks=passed`。
- 路由审计 fail-closed：IT-MODEL-102（EV-0623）pass。
- AC-02 整条（含配额写越权 `SEC-M04-003b`、界面无写入口、审计 fail-closed 的最终 UI 组合）仍由 **V2** 判定。

## 独立命令与结果

| 命令                                                                                                                                                                                                                                                                                        | 结果                                                                                                                         | EV                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------- |
| `./mvnw -T1C -pl apps/api -am test -Dtest='ChatModelUnavailableApiTest,ModelGatewaySwitch*,M04VerifierSwitchDeltaTest' -Dsurefire.failIfNoSpecifiedTests=false && test -z "$(grep -R -n -E 'apiKey=\|baseUrl=\|sk-live\|10\.9\.8\.7' apps/api/target/surefire-reports/)" && echo CANARY-OK` | exit 0；**25 tests / 0 fail / 0 skip**；`CANARY-OK`                                                                          | **EV-0638**（commit `6bc0c43`） |
| `REAL_EVIDENCE_TASK=TASK-0115 REAL_PLAYWRIGHT_GREP='E2E-M04-02' node scripts/real-stack/run.mjs --mode=full`                                                                                                                                                                                | exit 0；**1 passed**；compose/seed/m10/j2/m03 passed（targeted 模式 checks 跳过）                                            | **EV-0639**（commit `93c6498`） |
| 参考 Builder 全量                                                                                                                                                                                                                                                                           | EV-0635 clean：25 browser passed；compose/fingerprint/seed/seedIdempotent/m10/browserE2e/j2/security/m04/m03 **全部 passed** | EV-0635（commit `dc95e6c`）     |
| 参考 Builder 修复                                                                                                                                                                                                                                                                           | EV-0636 clean（日志 17 tests + grep 零命中）；EV-0634 clean（开关 18 tests）                                                 | TASK-0132/0133                  |

历史 V1 全量（EV-0623..0628，commit `42233ff`/`6e01571`/`5e81a6b`/`71a0d98`/`7024e83`/`c1e2b94`）保留为修复前基线；EV-0627 是修复前真实栈失败证据。**这些不得作为最终代码的模块 review 全量证据。**

## 剩余 P2/P3

- **F-006（P2 accepted）AC-07 在途轮换**：IT-MODEL-106 只证明旧密文/DEK 材料在轮换后仍可解密，E2E-M04-04 验证 UI 影响说明；真实并发流式请求未覆盖。owner B5/B11a，V2 或 Lead 书面接受。
- **F-013（P2 accepted）非 503 detail 入库**：`ChatService.java:170/715-716` 对非 `RICCON-MODEL-503` 仍把 Worker `detail` 作为 `failureMessageForStorage`，`ChatEventRecorder.recordFailed` 写入 `agent_run.error_message`，并由 `RunDetailResponse.errorMessage` 暴露。当前 Worker 的 detail 是静态脱敏文本，风险有界；建议 V2 增加 canary 或改为固定错误文案。
- **F-014（P2 open）gate write_scope**：TASK-0115 仍是 review，gate 按 `baseline..HEAD` 计算，把 TASK-0132/0133/0134 的修复文件列为越界。Lead closeout 时把 baseline 调到修复后（如 `fb82173`）或记录 reviewed_commits，gate 才能通过；这不是 V1 代码越界。
- **F-015（P3 open）TASK-0024 证据覆盖**：本轮开始再次发现 `TASK-0024/real-report.json` 被 `real-stack --list` 覆盖；已 `git checkout` 恢复，提交记录中仍有历史覆盖（e272fff），建议 Lead 清理/注明。
- **P3 残余（沿用初版）**：AC-01 跨组件 E2E、AC-05 文件保留断言、AC-09 提额重试、AC-14 焦点可见、3xx 重定向负向、开关测试 ID 复用 `IT-MODEL-103a..f/104a..b`（未登记 ADR §11.1 目录）。均不阻塞 V1 pass，V2/后续测试整理处理。

## V2 必须完成的最终全量

1. 在最终提交（修复 + V1 delta 之后）运行合同 `test`/`coverage`/`test_e2e`/`a11y`/`security`/`build`，录新的 clean EV；不得复用 EV-0623..0628。
2. 全量真实栈（无 grep）重跑，确认 browserE2e 25/25、checks `m04Checks/securityChecks=passed`；EV-0635 是修复提交的证据，最终提交仍需重录。
3. 判 AC-02 整条、AC-17/AC-18、`PERF-M04-001/002`、`OPS-M04-01`、`OPS-M04-02b`；覆盖 V1 的 pass 但不替代。
4. 在全部 task done 后写 `.riccon/reviews/M04-review.md`，`dod_evidence` 绑定 V2 的最终全量 EV。

## 结论与建议

**V1 verdict：pass（带 P2/P3 残余；无 P0/P1）。** 建议 Lead：

- 将 TASK-0115 从 `review` closeout 为 `done`（最新 EV-0639 为 clean exit 0；EV-0627 失败证据保留）；
- 调整 TASK-0115 baseline/end 或记录已评审提交，消除 gate 的 write_scope 误报；
- 启动 V2（TASK-0116）按上述清单做最终全量并判 AC-02/17/18；V2 完成前模块 gate 仍不能通过。

报告-002 不替代模块 review；V2 的最终全量 EV 才是模块 gate 的证据。
