---
review_id: REV-TASK-0065-002
module_id: M10
task_id: TASK-0065
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0067_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [fb6661b, bac78d5]
previous_review: REV-TASK-0065-001
head_commit: f8c4f96
reviewed_evidence: [EV-0403, EV-0404, EV-0405, EV-0406, EV-0407]
evidence: [EV-0407]
findings:
  - id: F-001
    severity: P1
    status: accepted
    assigned_to: TASK-0068
    title: "RoleDefinitions 导出审批权限缺口已由 ADR-0020 §8.1 + B3a 承接，不再是 owner 悬空"
    summary: "ADR-0020 §8/§8.1 明确：B1 仍是共享词汇与 RoleDefinitions 默认 owner；仅两条导出审批权限（TENANT_ADMIN += audit.export.approve、PLATFORM_ADMIN += platform.audit.export.approve）作为窄例外归 B3a（TASK-0068），并要求 V10 后才落地。TASK-0068 write_scope 含 V10、RoleDefinitions、SchemaVersionChecker、迁移测试与 RoleDefinitionsTest，acceptance 覆盖 V10 权限字典/角色回填、新建租户与空库引导两条审批权限、worker 9→10、V1–V9 迁移/回滚。F-001 由 P1 open 转为 accepted/assigned；仍必须在 B3a 完成并录 clean EV 后才能 fixed，M10 gate 前不可省略。"
    evidence: "docs/adr/ADR-0020-m10-audit-query-export.md §8（任务表）、§8.1（Owner 例外/F-001）；.riccon/tasks/TASK-0068.json（write_scope/acceptance）；TASK-0068 已建（planned）。"
  - id: F-002
    severity: P2
    status: open
    assigned_to: TASK-0069
    title: "platform 租户 OIDC 绑定/配置边界转由 B2 验收覆盖"
    summary: "上一轮 P2 仍保留：配置校验未拒绝 tenantCode=platform，且 platform OIDC 登录在 ADR-0019 §10 后置。TASK-0069（B2）acceptance 已明确“platform 租户拒绝”，但 B1 配置侧未改；B2 评审必须验证绑定端点拒绝 platform，且 B1 不接受 platform 配置的上线口径有测试或运行手册约束。"
    evidence: ".riccon/tasks/TASK-0069.json acceptance 2“platform 租户拒绝”；apps/api/src/main/java/com/riccon/api/oidc/domain/OidcConfigurationParser.java:30、82-88；ADR-0019 §10。"
  - id: F-003
    severity: P3
    status: open
    title: "publicBaseUrl 生产 fail-fast（OPS-015）仍 open"
    summary: "B1 未改变 publicBaseUrl 校验；OPS-015 仍由 B1/Ops 承接，M09 运行手册兜底。"
    evidence: "OidcIdpRegistry.java:209-239；M10-OPS-review OPS-015。"
  - id: F-004
    severity: P3
    status: open
    title: "EV-0402 的 673 含陈旧报告，fresh 672"
    summary: "上一轮证据口径问题保留：EV-0402 窗口内 fresh 672 tests/0 skip；673 含 2026-11-18 的 AgentStreamTimingProbeTest 陈旧报告。B1 代码未变，不影响覆盖率和 0 skip 结论；后续 EV 需清理 target 或显式断言。"
    evidence: "EV-0402；apps/worker/target/surefire-reports/com.riccon.worker.agent.AgentStreamTimingProbeTest.txt。"
  - id: F-005
    severity: P3
    status: open
    title: "CSRF 旧令牌失效与失败路径会话属性断言仍缺"
    summary: "上一轮 P3 保留：IT-OIDC-001/007 未断言登录前旧 CSRF 失效；失败路径未显式扫描 SPRING_SESSION_ATTRIBUTES。V1 的 SEC-OIDC-003/失败清理套件承接。"
    evidence: "OidcLoginFlowTest.java:208-251、438-452；ADR-0019 §2.1。"
  - id: F-006
    severity: P3
    status: open
    title: "失败审计覆盖与 sessionIdHash 校验仍缺"
    summary: "上一轮 P3 保留：state_invalid/重放/login_failed 未断言 auth.login.failed；成功审计 sessionIdHash 只校验长度。V1 补。"
    evidence: "OidcLoginFlowTest.java:379-433；OidcLoginService.java:168-189。"
  - id: F-007
    severity: P3
    status: open
    title: "OpenAPI callback 404 / authorize 429 漂移转 B3b/architect"
    summary: "上一轮 P3 保留；B3b 实现审计 API 时不要私改契约，接口响应集合由 architect 收敛。"
    evidence: "contracts/openapi.yaml:248-300；OidcAuthorizePreflightFilter/OidcLoginFailureHandler。"
  - id: F-008
    severity: P3
    status: open
    title: "endSessionEndpoint fragment/query 与 AC-12 UI 提示"
    summary: "上一轮 P3 保留：B1 只交付 header；OIDC 无 end-session 场景与 IdP 不可达同页提示由 B4/V1 覆盖；endSessionEndpoint 的 fragment 拼接仍建议 B1/Ops 加校验。"
    evidence: "OidcConfigurationParser.java:170-188；OidcLogoutService.java:73-81；ADR-0019 §5。"
  - id: F-009
    severity: P3
    status: open
    title: "invalid_request/invalid_grant 失败原因映射仍需 V1 矩阵确认"
    summary: "上一轮 P3 保留；不泄露敏感信息，但可能把 IdP/token 错误误标为 state_invalid/login_failed。"
    evidence: "OidcFailureReasonMapper.java:89-109。"
  - id: F-010
    severity: P3
    status: open
    assigned_to: riccon/lead
    title: "B3b 任务卡尚未创建；B3a 启动可接受，但 M10 gate 前必须建卡"
    summary: "ADR-0020 §8 已定义 B3b（审计查询/导出 API）写范围与依赖，但 M10.tasks 当前只有 TASK-0062..0070，没有 B3b 任务卡。作为 B1 复审与 B3a 启动条件可接受；Lead 必须在 B3a 完成前或最迟 M10 gate 前创建 B3b（建议 TASK-0071）并加入 M10.tasks，否则审计 API、B5、V1 与模块验收无承载。"
    evidence: ".riccon/modules/M10.json.tasks；ADR-0020 §8 B3b 行；ADR-0020 §8 依赖图。"
created_at: "2026-11-19T09:04:00+08:00"
---

# REV-TASK-0065-002：TASK-0065 复审（F-001 owner 承接确认）

## 1. 结论

**verdict: pass。** 上一轮 `REV-TASK-0065-001` 的唯一 P1（F-001 RoleDefinitions 导出审批权限无 owner）已由 `bac78d5` 的 ADR-0020 §8/§8.1 与 `TASK-0068`（B3a）真正收口：

- B1 仍是 `AuditActions`/`AdditionalAuditActions`/`AuditDetailsPolicy`/`ErrorCodes` 与 `RoleDefinitions` 的默认 owner；
- `RoleDefinitions` 只有两条导出审批权限作为**唯一窄例外**归 B3a，且明确要求 V10 之后落地；
- TASK-0068 的 write_scope 含 V10、RoleDefinitions、worker `SchemaVersionChecker`、迁移测试与 `RoleDefinitionsTest`；acceptance 覆盖 F-001 与 OPS-002；
- B3a/B3b 写范围不重叠；B3b 尚未建卡可作为 B3a 启动后的后续条件，但 M10 gate 前必须建卡。

因此 **B1 可以在 Lead 标记 `TASK-0065` done 后继续**；F-001 仍不能标 fixed，须等 B3a 完成并录 clean EV。上一轮 P2/P3 保留，新增 P3 F-010 跟踪 B3b 任务卡。

## 2. 复审范围与命令

评审时工作区干净，HEAD `f8c4f96`；B1 代码最终提交 `fb6661b` 未变，ADR 修订为 `bac78d5`。

| 检查/命令                                                  | 退出码 | 结果                                                                                                               |
| ---------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------ |
| `git status --short` / `git rev-parse --short HEAD`        | 0      | 空；HEAD `f8c4f96`                                                                                                 |
| `git show --stat bac78d5`                                  | 0      | 仅 `docs/adr/ADR-0020-m10-audit-query-export.md`，43+/24-；未改代码/契约/迁移                                      |
| `git diff --name-status 1d820c4..HEAD`                     | 0      | 仅 ADR、TASK-0068/0069/0070、modules/state/evidence；无 `apps/**` 生产代码                                         |
| `node … riccon.mjs contract validate --strict --project .` | 0      | 0 errors / 0 warnings                                                                                              |
| `node … riccon.mjs state validate --project .`             | 0      | 0 errors；仅既有 M11 legacy warning（M11 planned 但有 done 任务）                                                  |
| `git diff --check`                                         | 0      | 无空白错误                                                                                                         |
| EV-0404/0405/0406 复核                                     | 0      | 均 `manual:false`、`dirty:false`、exit 0、commit `bac78d5`；输出 SHA-256 与 JSON 一致（diff-check/contract/state） |

未跑代码测试/全量 verify：B1 代码与测试未变化，上一轮 EV-0402/EV-0403 仍有效；本次只验证任务图/ADR 与状态。未改代码、文档、契约、迁移；仅写本评审。

## 3. F-001 承接审计

### 3.1 owner 是否仍悬空

**不再悬空。** ADR-0020 §8.1 明确：

- `RoleDefinitions.java` 默认仍归 B1（B1 已交付 `audit.read`）；
- 唯一例外是两条导出审批权限：`TENANT_ADMIN += audit.export.approve`、`PLATFORM_ADMIN += platform.audit.export.approve`，以及对应 Javadoc / `RoleDefinitionsTest` 断言，归 B3a；
- B3a 不得改其他角色/权限；B3b/B4/B5/V1 不得修改该文件；
- 原因被准确记录：V10 的 `INSERT ... SELECT FROM role` 只回填迁移前已存在的角色，覆盖不到 fresh install、空库引导与 V10 后新建租户；这些路径都从 RoleDefinitions 建角色。

TASK-0068 的 scope/acceptance 与该例外一致：

- `write_scope` 5 项：V10 SQL、RoleDefinitions、worker `SchemaVersionChecker`、`apps/api/src/test/java/com/riccon/api/migration/**`、`RoleDefinitionsTest.java`；
- `acceptance` 6 项：V10 表/索引/权限字典/角色回填/回滚；RoleDefinitions 两条审批权限（新建租户与空库引导）；worker 9→10；V1–V9 迁移/回滚；定向+全量 verify；不写审计 API/UI；
- B1 与 B3a 在 `RoleDefinitions.java` 上有**有意例外**的文件级重叠，ADR §8.1 限定为“仅两条权限”，且 B1 先完成、B3a 后执行；不属于并发写冲突。

### 3.2 B3a 是否只扩两条

**按 ADR 是；但 write_scope 是文件级，执行时必须靠 acceptance/评审约束。** §8.1 逐字限定“仅是两条导出审批权限……B3a 不得借机改其他角色/权限”。TASK-0068 acceptance 2 也只列这两条。B3a 评审必须 diff 检查 `RoleDefinitions.java` 只新增两行权限/相应 Javadoc，不得调整 `MEMBER` 或其他角色；建议 `RoleDefinitionsTest` 明确断言 `tenantRole(TENANT_ADMIN)` 含 `audit.export.approve`、`platformAdminRole()` 含 `platform.audit.export.approve`、`MEMBER` 不含审计权限。ADR §8.1 已写“空库引导”，B3a 应覆盖 `platformAdminRole`。

### 3.3 B3a/B3b 写范围与依赖

- B3a 与 B3b：B3a 是 V10/RoleDefinitions/worker schema/迁移测试；B3b 是 `audit/controller|service|repository|persistence`、`admin/audit`、`m10/audit` 测试；无路径重叠。
- B3a 与 B2：B2 是 `admin/identity|controller|service`、`identity/dto`、`auth/repository|persistence`、`m10/identity`；无路径重叠。
- B1 与 B3a：仅 `RoleDefinitions.java` 的有意窄例外（§8.1），顺序为 B1 → B3a。
- 依赖图自洽：B1 → B2/B3a；B3a → B3b；B2 → B4；B3b → B5；B4+B5 → V1。B3a 可与 B2 并行；B3b 必须在 B3a 的 V10/权限回填可应用后启动。TASK-0068/0069 均为 planned，M10 依赖 M03（done），可启动。

## 4. TASK-0068 是否足以承接 F-001 / OPS-002

**是。** 除 F-001 外，TASK-0068：

- 覆盖 OPS-002：`SchemaVersionChecker` 从 9 升 10，worker 继续关闭 Flyway、只校验版本；
- 覆盖 OPS-004/005/006/007 的 builder 侧：worker 版本/协调切换前提、V10 单语句 `ALGORITHM=INPLACE, LOCK=NONE`、迁移失败恢复、回滚；运行手册与 V1 演练仍在 Ops/V1；
- 明确“空库通过不算”，要求 V1–V9 seed→V10 迁移/回滚与权限/索引/请求表断言；
- 明确不写审计 API/UI，不与 B3b 范围重叠。

**B3b 未建卡是否可接受：** 在“B1 复审 / B3a 启动”这个时点可以接受；ADR 已给出 B3b 边界和依赖。但 B3b 是审计查询/导出 API 主体，B5/V1 和 M10 验收依赖它，**M10 gate 前必须有 B3b 任务卡并加入 `M10.tasks`**。建议 Lead 在 B3a 完成前创建 B3b（如 TASK-0071），否则任务图仍不完整（F-010 P3）。

## 5. 剩余 P2/P3 与 M10 gate 前置

保留上一轮 P2/P3，均不阻断 B1：

- **P2 F-002**（platform tenant OIDC 边界）→ TASK-0069 acceptance 已写“platform 租户拒绝”；B2 评审验证。
- **P3 F-003** publicBaseUrl 生产校验（OPS-015）。
- **P3 F-004** EV-0402 673/672 证据口径。
- **P3 F-005/F-006** CSRF 旧令牌/失败路径属性扫描/失败审计覆盖（V1）。
- **P3 F-007** OpenAPI callback 404 / authorize 429 漂移（B3b/architect）。
- **P3 F-008** endSession fragment/query 与 AC-12 UI 提示（B4/V1）。
- **P3 F-009** invalid_request/invalid_grant 映射（V1）。
- **P3 F-010** B3b 任务卡未建（Lead；M10 gate 前必须）。

**M10 gate 前置（本复审后仍成立）：**

1. **B3a（TASK-0068）done + clean EV**：F-001 才能在任务/模块评审标 fixed；V10 权限/角色回填/迁移测试/worker 9→10 全部通过。
2. **B3b 建卡并 done**：审计查询/导出 API、`IT-AUDIT-*`、平台单管理员/403、CSV 等。
3. **B4/B5 + V1**：OIDC/UI/绑定徽标、审计/导出 UI、E2E-M03-03..07、a11y、security、V10 回滚演练、模块 review。
4. **Ops/T4**：OPS-001（CI/RUNBOOK 36 表/V1–V7 断言）、OPS-004/005/006/007、OPS-011、OPS-014；OPS-M10 评审结论；用户按合同 `decision_owners.production` 完成 T4 批准。OPS-003 已在 B1 层 fixed；OPS-002 已转 B3a。OPS-008+ 仍按原 owner（M09）。
5. **ADR-0019/0020 仍 `proposed`**：本复审只确认任务图/owner，不代替 Lead/User/Ops 的正式 ADR 批准。

## 6. 未验证

- 未跑代码测试/全量 verify（B1 代码未变；本次只验证 ADR/任务图/状态）。
- 未实现/验证 B2/B3a/B3b；F-001 的最终 fixed 依赖 B3a 的代码、迁移测试与 clean EV。
- 未验证 Ops 运行手册、V10 生产执行、T4 批准；这些是 M10 gate 前置，不是本复审结论。

## 7. 剩余风险

1. **F-001 accepted（P1）**：owner 已解决，但 B3a 未完成前 AC-17 审批链仍未闭环；M10 gate 必须等 B3a。
2. **F-002（P2）**：platform 绑定边界依赖 B2 实现与测试；若 B2 未按 acceptance 拒绝，需回到配置层拒绝。
3. **F-010（P3）**：B3b 任务卡缺失会让 M10.tasks 不完整；Lead 应在 B3a 完成前建卡。
4. **OPS-001** 仍无新 owner：它是 OPS 评审 P1 的既有 must-fix，M10 gate 前必须由 Lead/Ops 关闭；本次 ADR 修订只明确“owner 不变”，未新分配。
5. **证据/测试风险**：F-004 的 673/672 口径、F-005/F-006 的负向断言仍需 V1 补强。
