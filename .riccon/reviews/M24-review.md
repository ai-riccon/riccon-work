---
review_id: REV-M24-001
module_id: M24
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_dfb83bfaeffeHE3t1fEmJrlE5i
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
head_commit: 3ae6335a7215e3adc68744467dcfb71943ebd790
reviewed_commits:
  - a71793c
  - a6153f9
  - 02dbc6d
  - fb5fda4
  - 501658e
  - 78b0dbe
  - 3473414
  - 8fa8523
  - 58e76bf
  - 5d9cbba
  - 8eefc20
  - 30d2f75
  - 9018026
  - 3ae6335
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
dod_evidence:
  acceptance:
    - EV-0795
    - EV-0771
    - EV-0798
    - EV-0796
    - EV-0804
    - EV-0807
    - EV-0809
    - EV-0810
  build:
    - EV-0808
  tests:
    - EV-0809
  coverage:
    - EV-0809
  e2e:
    - EV-0810
  docs:
    - EV-0813
findings:
  - id: F-001
    severity: P3
    status: accepted
    title: "TASK-0157 用测试侧 SQL 复刻 API 轮换语义，未覆盖“API HTTP 轮换端点 + 独立 Worker 活跃流”的跨进程实栈 E2E"
    owner: "riccon/builder-key / riccon/builder-gateway-e2e"
    due: "M05 gate 前"
    accepted_reason: >-
      F-006 的原文缺口是“真实并发流式请求未覆盖”，不是“轮换必须经 HTTP 端点”。用例在真实 MySQL
      8.4、真实 SSE 流与生产 CredentialEnvelope/AuditRepository 上把第一条流挂在首字之后，执行与 API
      rotate 相同的单事务 SQL 语义，再证明第二条流选到新 key、旧流不中断/不重放；API 侧 HTTP 轮换、
      并发 409、掩码与审计由 IT-MODEL-106、ModelCredentialLifecycleApiTest 与 M04VerifierGatewaySecurityTest
      独立覆盖，两侧共用同一 AAD/信封契约。跨进程 E2E 主要补部署拓扑与连接池可见性，适合 M05 真实栈
      扩展时承接，不阻塞 F-006 在集成层关闭。
    evidence: "TASK-0157 EV-0794/EV-0795；REV-TASK-0157-001 F-001"
  - id: F-002
    severity: P3
    status: accepted
    title: "无明文扫描范围有限：只查最后一次模型请求体、com.riccon.worker logger 与 DEK HEX，未扫描 API 响应、非 worker logger 或 surefire 输出"
    owner: "riccon/builder-key"
    due: "M05 gate 前"
    accepted_reason: >-
      key 的实际流向只有 provider_credential.ciphertext（已扫描）与上游 Authorization（已正面断言
      旧/新 key），模型流响应、轮换审计 details、provider_credential 文本列也已覆盖；DEK 的 HEX 子断言
      对 ASCII 明文无效，属防御性增强而非当前泄漏路径。API HTTP 响应与页面掩码由既有
      IT-MODEL-106/M04VerifierGatewaySecurityTest 与 E2E-M04-04 承接。扩大扫描范围可作为后续安全测试
      加固，不影响 F-006 的关闭结论。
    evidence: "InFlightCredentialRotationIntegrationTest.java:309-318、614-661、706-721；TestModelStub.java:232-236、277-288"
  - id: F-003
    severity: P3
    status: accepted
    title: "docs/CODING-STANDARDS.md §10.4 的 security 映射未列 InFlightCredentialRotationIntegrationTest；原 due M24 gate 前已到期"
    owner: riccon/architect
    due: "M05 gate 前"
    accepted_reason: >-
      该测试类已带 @Tag("security") 并继承 @Tag("integration")，`-Dgroups=security` 能选中，默认执行集合
      与断言强度不受影响；缺口只是编码规范中的类清单漂移。M24 的 gate 不以文档清单为阻断，故转
      M05 gate 前由 architect 补齐；这与 TASK-0142 F-003 属同类纯文档债务。
    evidence: "docs/CODING-STANDARDS.md:242；InFlightCredentialRotationIntegrationTest.java:93-94；REV-TASK-0157-001 F-003"
  - id: F-004
    severity: P3
    status: accepted
    title: "apps/web/src/test/setup.ts 把 asyncUtilTimeout 全局从 1s 提到 3s，所有 web 测试共享；可能掩盖 1–3s 的慢回归"
    owner: riccon/builder-web
    due: "M05 gate 前"
    accepted_reason: >-
      改动只提高等待上限，不改变任何断言、不使用 retry/skip、也不降低文件并行度；元素不出现仍会在
      3s 后失败，所以不是假绿。workspace 与 knowledge-and-sharing 两处已改为先 `findByText('销售部')`
      的确定性等待；EV-0783 连续 5 次完整 web 套件（57 文件/493 用例）通过，EV-0786 单文件连续 10 次
      通过。3s 只把 CPU 争抢下的慢路径从“偶发超时”变成“仍可失败”的稳定窗口，属可接受稳定性措施；
      M05 前如需更严格，可把超时限定到 flake 文件或增加显式性能断言。
    evidence: "apps/web/src/test/setup.ts:16；EV-0783；EV-0786；M22 F-001"
  - id: F-005
    severity: P3
    status: accepted
    title: "真实栈 E2E 依赖单 worker 文件内顺序与跨用例全局状态；新增用例必须遵守路由链/seed key 清理约定"
    owner: riccon/builder-gateway-e2e
    due: "M05 gate 前"
    accepted_reason: >-
      EV-0805/EV-0806 已暴露“key 生命周期用例修改 CHAT 链后污染 R-M04-03 引用用例”的确定性问题；
      9018026 让 M04-AC-01/key 生命周期用例在 try/finally 中按进入快照还原路由链，并让
      resetPlatformKeyHealth 只重置 seed 写入的 key，避免把 lifecycle 用例新建的 key 误激活。
      EV-0807 连续两次全量 B11a/B11b 11/11 通过，失败 EV 保留，Playwright retries=0、workers=1，
      失败会显式暴露而非重试掩盖。残留是测试约定风险：后续新增真实栈用例若忘记清理全局状态，仍可能
      互相污染；该约定应写进真实栈 spec 的注释/清单，但不影响本轮 M24 结论。
    evidence: "e2e/real/tests/m04-routing.spec.ts:191-215、542-565、729-835；EV-0805/EV-0806（失败保留）；EV-0807"
  - id: F-006
    severity: P3
    status: accepted
    title: "M04 F-008 的生产 pass-through 仍在：ChatService 将 Worker failedMessage 原样写入 run.error_message；M24 只锁定当前 Worker 静态文案与负向回显"
    owner: riccon/builder-route
    due: "M05 gate 前"
    accepted_reason: >-
      M04 评审已接受该残余：当前写入的是 Worker 固定脱敏文案，不含 provider 地址、key、堆栈或用户输入。
      TASK-0158 新增 400/409/429/500 与首字前 failed 的逐字静态文案断言，并用 canary 证明原始 detail
      不会回显到员工响应或 com.riccon 日志；但 API 侧仍未做白名单/截断/结构化分类，若 Worker 未来发送
      原始 detail，run.error_message 仍可能落库。真正的收敛属于产品行为变更（F-008 原文即要求收敛写入/
      展示策略），M24 明确只做“脱敏确认”，故保留 accepted，M05 前由 builder-route 决定是否改 API 侧策略。
    evidence: "apps/api/src/main/java/com/riccon/api/chat/service/ChatService.java:390-395；ChatEventRecorder.java:150-155；ChatModelUnavailableApiTest.java:295-325；EV-0771"
  - id: F-007
    severity: P3
    status: fixed
    title: "TASK-0157 旧证据冗余（EV-0792/0793/0794 混绑）已收敛为 EV-0794 + EV-0795"
    evidence: ".riccon/tasks/TASK-0157.json；REV-TASK-0157-001 F-004"
  - id: F-008
    severity: P3
    status: fixed
    title: "TASK-0162/0184 真实栈用例顺序污染已修复：EV-0805/EV-0806 失败保留，EV-0807 连续两次全量 11/11"
    evidence: "EV-0805；EV-0806；9018026；EV-0807"
reviewed_task_reviews:
  - REV-TASK-0157-001
evidence:
  - EV-0794
  - EV-0795
  - EV-0771
  - EV-0797
  - EV-0798
  - EV-0783
  - EV-0796
  - EV-0802
  - EV-0803
  - EV-0804
  - EV-0775
  - EV-0786
  - EV-0805
  - EV-0806
  - EV-0807
  - EV-0808
  - EV-0809
  - EV-0810
  - EV-0811
  - EV-0812
  - EV-0813
reviewed_evidence:
  - EV-0794
  - EV-0795
  - EV-0771
  - EV-0797
  - EV-0798
  - EV-0783
  - EV-0796
  - EV-0802
  - EV-0803
  - EV-0804
  - EV-0775
  - EV-0786
  - EV-0805
  - EV-0806
  - EV-0807
  - EV-0808
  - EV-0809
  - EV-0810
  - EV-0811
  - EV-0812
  - EV-0813
notes: "M24（fix M04/M20/M22）独立验证：9 个任务全部 done；build/test+coverage/E2E/a11y/security/lint 全绿；Java 220 类/1247 用例/0/0/0，web 57/493，覆盖率全部过 80/70；真实栈 EV-0807 连续两次 11/11。无 P0/P1；任务级残余与新增稳定性风险均 P3 accepted，失败 EV-0803/0805/0806 保留。本评审是独立技术验收，不替代 Product 预验收或用户最终验收。"
created_at: "2026-11-23T09:08:00+08:00"
---

# REV-M24-001：M24（fix M04/M20/M22）M05 gate 前置债务收口的模块独立评审

## 结论

**pass（附 P3 残余，无 P0/P1）**。在最终提交 `3ae6335`（`1046807..3ae6335` 的 M24 代码/测试提交，及 `3ae6335` 的 .riccon closeout）上独立复核：

- M24 的 9 个任务全部 done；除 TASK-0157 外均为测试/E2E/格式修复，生产代码、迁移、OpenAPI 与用户可见 UI 行为零改动（`git diff --name-only 1046807..9018026 -- apps platform` 只有 2 个测试文件）。
- 合同 build（EV-0808）、test+coverage（EV-0809）、E2E（EV-0810，40/40）、a11y（EV-0811，15/15）、security（EV-0812，0 漏洞/0 密钥/0 错配）、lint/typecheck（EV-0813）全部 exit 0、dirty=false。
- Java **220 类 / 1247 用例 / 0 failure / 0 error / 0 skip**；web **57 文件 / 493 用例**；覆盖率 api 92.47%/75.55%、worker 88.22%/71.99%、platform-common 90.83%/75.41%、platform-domain 96.51%、web 87.01%/80.01%（line/branch），全部过合同 80/70。
- M04 F-006/F-008/F-010/F-012、M22 F-001、M20 F-004、R-M04-03 均已 fixed（F-008 的生产 pass-through 保留为 accepted 残余，见 F-006）；M04 F-013、M22 F-002 已由文档提交关闭；M04 F-009 仍按 M04 评审 accepted 到 M09。无 open P0/P1。
- 真实栈 EV-0807 连续两次全量 B11a/B11b **11/11**；EV-0804 的 6 条定向用例（E2E-M04-02、M04-AC-05、E2E-M04-05、3 条 R-M04-03）全通过；失败 EV-0803/0805/0806 保留，未用 retry 掩盖。

本评审是独立技术验收，**不替代** Product 预验收或用户最终验收；M24 状态、Product/用户验收与 gate 重跑由 Lead 处理。

## 1. 变更范围与边界

| 任务      | 变更                                                                                                      | 边界判断                                             |
| --------- | --------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| TASK-0157 | 新增 `InFlightCredentialRotationIntegrationTest`（+798）                                                  | 只新增 Worker 测试；生产代码未改                     |
| TASK-0158 | `ChatModelUnavailableApiTest`、`ResearchModelUnavailableMappingTest`、`RunFailureMessageSanitizationTest` | 只新增/加强断言；生产代码未改                        |
| TASK-0159 | `e2e/real/tests/m04-routing.spec.ts` 新增 M04-AC-01 真实栈用例                                            | 只改真实栈 spec；不绕过 UI                           |
| TASK-0160 | `apps/web/src/layouts/workspace/index.test.tsx` + `apps/web/src/test/setup.ts`                            | 测试等待修复；全局 asyncUtilTimeout 提到 3s（F-004） |
| TASK-0161 | 新增 `CostPageContractTest`                                                                               | 只读契约；`contracts/openapi.yaml` 未改              |
| TASK-0162 | `m04-routing.spec.ts`、`m04-quota-cost.spec.ts` 的 R-M04-03 三段断言                                      | 只改真实栈 spec                                      |
| TASK-0178 | `RunFailureMessageSanitizationTest` 纯格式                                                                | 断言/语义不变                                        |
| TASK-0181 | `knowledge-and-sharing.test.tsx` 异步等待修复                                                             | 只改测试                                             |
| TASK-0184 | `m04-routing.spec.ts` 时序/路由链还原修复                                                                 | 只改真实栈 spec；失败 EV 保留                        |

没有生产源码、迁移、OpenAPI、部署配置或用户可见文案改动；因此 M04/M20/M22 已验收 AC 的用户可见结果不受影响。`3ae6335` 只包含 `.riccon` closeout。

## 2. 全量证据

| 项                            | 结果                                                                              | EV          |
| ----------------------------- | --------------------------------------------------------------------------------- | ----------- |
| 合同 build                    | exit 0，32.7s                                                                     | **EV-0808** |
| 合同 test+coverage（同一 EV） | exit 0，695s；Java 220 类/1247 用例/0/0/0；web 57/493                             | **EV-0809** |
| 合同 E2E                      | exit 0，302s；**40 passed**（含 M13 R-M03-01/02/03）                              | **EV-0810** |
| a11y（T4 评审项）             | exit 0；**15 passed**（登录/工作空间/J1/J2/管理端/M03/M04/M10 的 axe/375px/键盘） | **EV-0811** |
| security（T4 评审项）         | exit 0；依赖漏洞/密钥/错配全 0（仅 ADR-0011 已登记例外）                          | **EV-0812** |
| lint/typecheck                | exit 0；spotless、eslint `--max-warnings=0`、prettier、`tsc --noEmit` 全通过      | **EV-0813** |
| 真实栈 B11a/B11b              | 连续 2 次，**11/11 + 11/11**                                                      | **EV-0807** |
| 真实栈定向                    | E2E-M04-02、M04-AC-05、E2E-M04-05、R-M04-03 ×3；**6/6**                           | **EV-0804** |
| 真实栈 M04-AC-01              | 5/5（含 M04-AC-01 与 B11a 其余）                                                  | **EV-0797** |

覆盖率（EV-0809 的 JaCoCo/vitest 报告）：

| 范围            |   Line | Branch | 门槛  | 结论 |
| --------------- | -----: | -----: | ----- | ---- |
| apps/api        | 92.47% | 75.55% | 80/70 | pass |
| apps/worker     | 88.22% | 71.99% | 80/70 | pass |
| platform-common | 90.83% | 75.41% | 80/70 | pass |
| platform-domain | 96.51% | 无分支 | 80/70 | pass |
| apps/web        | 87.01% | 80.01% | 80/70 | pass |

## 3. AC 对账

| AC 原文                                                                                           | 结论                                          | 证据                                                      |
| ------------------------------------------------------------------------------------------------- | --------------------------------------------- | --------------------------------------------------------- |
| M04 F-006/F-008/F-010/F-012、M22 F-001、M20 F-004、R-M04-03 全部 fixed 或 accepted；无 open P0/P1 | **pass**（F-008 生产残余 accepted，无 P0/P1） | 见 §3.1；EV-0795/0771/0798/0783/0796/0804/0786            |
| 不改变 M04/M20/M22 已验收 AC 的用户可见结果；受影响测试通过                                       | **pass**                                      | 生产/契约/UI 零改动；全量 E2E 40/40；EV-0809/0810         |
| M05 gate 前债务清单可核对；未用 retry 静默吞掉 flake                                              | **pass**                                      | 见 §3.1；EV-0803/0805/0806 失败保留；Playwright retries=0 |

### 3.1 债务逐条对账

| 来源    | 债务                              | 结论                                                                    | 证据                                                  |
| ------- | --------------------------------- | ----------------------------------------------------------------------- | ----------------------------------------------------- |
| M04     | F-006 真实并发在途轮换            | **fixed**（集成层；跨进程 E2E 为 F-001 P3）                             | EV-0794/0795；REV-TASK-0157-001                       |
| M04     | F-008 非 503 detail 脱敏确认      | **fixed（验证）；生产 pass-through accepted（F-006）**                  | EV-0771                                               |
| M04     | F-010 保存→下一次 CHAT 跨组件 E2E | **fixed**                                                               | EV-0797/0798；最终版 EV-0807                          |
| M04     | F-012 web 套件 flake              | **fixed**（5× 全量 + 10× 单文件；3s 上限 F-004）                        | EV-0783/0786                                          |
| M04     | F-013 API p95 口径文档            | **fixed**（PRODUCT §8 / ARCHITECTURE §7 已写稳态 JSON + SSE TTFB 分列） | `docs/PRODUCT.md:383`、`docs/ARCHITECTURE.md:490-505` |
| M04     | F-009 restore-drill partial       | **accepted**（M09 交付前，不在 M24 范围）                               | REV-M04-005/006                                       |
| M22     | F-001 workspace 单测竞态          | **fixed**                                                               | EV-0783/0786                                          |
| M22     | F-002 §10.5 全局清理例外清单      | **fixed**                                                               | `docs/CODING-STANDARDS.md:282`；4d5cfc5               |
| M20     | F-004 CostPage 契约断言           | **fixed**                                                               | EV-0796；`CostPageContractTest` 3/3                   |
| M20     | F-001/F-002/F-003/F-005           | **accepted**（价格/观测/证据整理残余）                                  | REV-M20-001                                           |
| M20     | F-006..F-009                      | **fixed**                                                               | REV-M20-001                                           |
| Product | R-M04-03 三处定向证据             | **fixed**                                                               | EV-0802/0804；EV-0807                                 |

## 4. 任务级评审对账

- `REV-TASK-0157-001`：**pass**。F-001（跨进程 E2E）、F-002（无明文扫描范围）、F-003（编码规范 security 映射）accepted；F-004（EV 冗余）已 fixed——当前 `TASK-0157.evidence` 已收敛为 EV-0794 + EV-0795。三条 accepted 均已在本评审对应保留（F-001/F-002/F-003）。
- M24 其余任务没有任务级 review：均为测试/E2E/格式修复，不触发“高风险任务 done 前独立评审”的强制项；TASK-0157 作为并发/凭据类高风险任务已有独立 review。
- `reviewed_task_reviews` 已列出本模块唯一的已有任务评审 `REV-TASK-0157-001`。

## 5. Findings（无 P0/P1）

| ID    | 级别 | 状态     | 摘要                                                                          |
| ----- | ---- | -------- | ----------------------------------------------------------------------------- |
| F-001 | P3   | accepted | 跨进程 HTTP 轮换 + 活跃 Worker 流实栈 E2E 未覆盖；轮换为测试侧同事务 SQL 复刻 |
| F-002 | P3   | accepted | 无明文扫描范围有限（最后一次请求体、worker logger、DEK HEX）                  |
| F-003 | P3   | accepted | CODING-STANDARDS §10.4 security 映射未列新测试类；原 due 已到期，转 M05       |
| F-004 | P3   | accepted | web 全局 asyncUtilTimeout 1s→3s，可能掩盖 1–3s 慢回归                         |
| F-005 | P3   | accepted | 真实栈 E2E 仍依赖单 worker 顺序与全局状态清理约定                             |
| F-006 | P3   | accepted | M04 F-008 生产 pass-through 仍在；M24 只锁静态文案与负向回显                  |
| F-007 | P3   | fixed    | TASK-0157 旧 EV 冗余已收敛                                                    |
| F-008 | P3   | fixed    | TASK-0162/0184 顺序污染已修复（失败 EV 保留，最终 2×11/11）                   |

所有 accepted 均写 owner/due/accepted_reason；无 open finding。

## 6. 未验证 / 剩余风险

- 未用 API HTTP 轮换端点驱动独立 Worker 进程做跨服务 E2E；未覆盖“在途请求以可重试错误结束”的合法分支（本用例验证更强的“正常完成”分支）。属 F-001，M05 前可补。
- 未重跑 k6 性能脚本：M24 无生产代码/端点/查询变更，性能预算不受影响；M23 的部门 join 只在 M23 评审记录。
- 未在本轮做全量真实栈的四页 a11y/security 段（EV-0807 按 grep 运行，`securityChecks`/`m04Checks` 为 skipped-targeted）；全量 stub E2E 的 a11y（EV-0811）与 security（EV-0812）已覆盖浏览器与依赖面。
- 3s 全局等待、真实栈顺序约定与文档映射均为 P3 accepted 残余，见 Findings。

## 7. 操作提示（Lead）

- 本评审绑定 `head_commit: 3ae6335`；`EV-0808..0813` 均为该提交、干净树、exit 0。
- 本地 `riccon gate check --module M24`（2026-11-23T09:20）**PASS**；唯一 warning 是全局 `EV-0571` 为历史 manual 证据，不属于 M24，不影响本模块结论。
- M24 的 full 证据只录制一次（TASK-0184）；M23 因 strict gate 按任务归属校验，另行录制了 M23 自己的全量 EV（见 M23 评审），两份评审不共用 EV 绑定，但运行 HEAD 相同。
- **证据绑定说明**：EV-0783/EV-0786 的命令含 `echo`/`tail` 分支，被 gate 的 masking 检测拒绝用于 `dod_evidence`；它们仍列入 `reviewed_evidence`，并在 §3.1 作为 M04 F-012/M22 F-001 的直接 5×/10× 证据；`dod_evidence.acceptance` 改用同一 HEAD 的全量 EV-0809（full test 已包含两个用例）。这不是降低验证强度，只是满足 gate 对 DoD 证据命令的保守规则。
- M24 wall-clock 20h 限额内。M23 的 6h 限额已因等待验证超时（见 M23 评审/回执），Lead 需决定 `--allow-overrun` 或补记录决策，不能把 wall_clock 失败当成代码失败。
