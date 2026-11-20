# 产品验收

本文件记录两层验收：

1. **Product 预验收**：实现是否按 `docs/PRODUCT.md`、UX 设计和范围落地；
2. **用户最终验收**：用户是否接受这个用户可见结果。

它不是 Verifier 的模块 review，也不是发布批准。Verifier 负责独立验证 AC、安全和行为；Product 不能替代 Verifier，用户保留最终接受权。

## 范围

- 模块：M03「权限纵深与组织模型」；M10「身份与审计纵深（M03b）」；M13 为 M03 验收补丁；M18 回溯复验（含 M02 回溯 Product 预验收）
- M18 回溯复验（2026-11-20）：对 M01/M02/M03/M10/M12/M13/M15 用当前合同 `commands.test` 重录 test/coverage（EV-0490..EV-0496），新 review `REV-*-002`，最终 gate 循环 EV-0498。只重录 test/coverage；E2E/a11y/security/真实栈/迁移回滚未重跑。详见「M18 回溯复验（2026-11-20）」。
- M03 原 gate：`bc97d1c`（REV-M03-001，EV-0342..EV-0349）
- M03 验收补丁（M13）：代码 `410cddd`，gate pass（REV-M13-001，EV-0352..EV-0359、EV-0361）；完整合同证据 `6564879`，最终 E2E `650663f`。EV-0360 为中间失败，不引用
- M10 gate：代码最终提交 `a88d003`，gate pass（REV-M10-001，29 条 reviewed_evidence；DEC-0018 将上限从 12h 提高到 20h）；V1 TASK-0083 done
- M15 补丁（fixes M10）：后端 `08e0fc6`（EV-0449）、前端 `4538d8c`（EV-0450）、a11y `c4ebd3e`（EV-0478）；TASK-0084/0085/0087/0086 done，gate pass（REV-M15-001）；代码证据 EV-0482..0486、EV-0488 在 `864b0a7`，docs EV-0489 在 `de33ed8`（EV-0487 为中间失败，不引用）
- 验收日期：2026-11-19（M10 初次）、2026-11-20（M15 后复验）、2026-11-20（M18 回溯）
- 验收人：Product（M03/M10/M15 预验收；M02 回溯预验收）；用户（M03/M10 accepted；M02/M18 待定）
- 参考：REV-M13-001、REV-M03-001、REV-M03-V1-002、REV-M10-001、REV-M15-001、REV-M10-ARCH-002、REV-M10-OPS-001、M10-OPS-DRILL、REV-TASK-0033-002、REV-TASK-0037/0039/0041/0043/0045、REV-TASK-0065-002/0068/0069/0071/0076；M18 回溯：REV-M01-002、REV-M02-004、REV-M03-002、REV-M10-002、REV-M12-002、REV-M13-002、REV-M15-002、REV-M18-REVALIDATION-001

## 模块关系与验收状态

- **M02**：`mvc`，done（2026-11-18）；关闭时无 `product_acceptance` 记录（Product/用户均为 `not-recorded`）。M18 回溯补做 Product 预验收：`partial`（AC-02 逐格式 UI 证据缺口，见「Product 预验收（M02，M18 回溯）」）；用户最终验收 `pending`，不补造 accepted。
- **M18**：`fix`，`fixes_module=M02`，building；TASK-0090 完成 7 模块证据回溯（EV-0490..EV-0498，gate 全 pass）；TASK-0091 为本文件回溯预验收。M18 自身 Product 预验收 `pending`，在 M18 gate 后另做，本文件不标 pass。
- **M03**：`feature`，done；原 gate `bc97d1c` + M13 补丁 gate pass；Product `pass`，用户 `accepted`（DEC-0016）。M18 只重绑 test/coverage（REV-M03-002），不改写原结论。
- **M13**：`fix`，`fixes_module=M03`；代码 `410cddd`，gate pass（REV-M13-001，EV-0352..EV-0359、EV-0361）；Product 复查 R-M03-01..03 `pass`；用户随 M03 `accepted`（DEC-0016）。
- **M10**：`feature`，`split_from=M03`，alias `M03b`；V1 TASK-0083 done，gate pass（REV-M10-001，29 条 reviewed_evidence；DEC-0018 上限 20h）；M15 修复后 Product 预验收 `pass`，用户最终验收 `accepted`（DEC-0021）。
- **M15**：`fix`，`fixes_module=M10`；TASK-0084/0085/0087/0086 done，gate pass（REV-M15-001）；R-M10-01 `fixed`；无独立用户 AC，随 M10 复验；B2 F-001 collation 仍为 residual。
- **M11**：legacy docs-only 模块，TASK-0035/0036 done；无模块 gate；状态保持 planned 并保留 `state validate` warning，后续单独 docs-only closeout 或 handoff 注记，不增加任务、不使用 `--force`。
- **M12**：维护模块，done；热栈/test-affected/文档无用户可见 AC，Product/用户验收为 `not-recorded`（N/A legacy），不伪造 pass。
- **M14**：docs-only legacy，building；TASK-0053/0057/0061 已 done，但尚无模块 review/gate；Product/user 结论重置为 `pending`，待独立 closeout 后再验收。

## 验收标准（M03，M13 补丁后）

| AC                        | 结果 | 证据                                           | 备注                                                                                                                                                                                                                      |
| ------------------------- | ---- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M03-AC-01                 | pass | EV-0361（R-M03-01）；EV-0354（IT-DEPT-001）    | 成员端显示“所属部门：销售部”；未分配显示“未分配部门”；停用部门显示“综合部 + 已停用”；租户管理员显示“角色：租户管理员”且不误报；375px、键盘、axe 通过。M13 F-001（后端未下发部门字段，前端以 `/departments` 兜底）accepted |
| M03-AC-02                 | pass | EV-0361（R-M03-02）；EV-0354（IT-DEPT-002）    | 共享抽屉内可管理空间可重命名，列表与工作空间切换器同步；409/403/404 中文文案；历史 TENANT 无重命名入口；普通成员无“工作空间共享”入口。M13 F-003（错误分支走 stub）accepted                                                |
| M03-AC-03                 | pass | EV-0361（M03 E2E 回归）；EV-0354（DEPT-001）   | 切换部门空间后只显示该空间会话；F-008 历史 TENANT 例外按 residual，不声称历史数据字面成立                                                                                                                                 |
| M03-AC-04                 | pass | EV-0361（R-M03-03）；EV-0354（DEPT-002/003）   | 研究页支持按可访问空间选择；从聊天部门空间进入 `/research/tasks?workspaceId=…` 可看到并打开部门报告；默认个人空间不显示部门报告；他部门列表/详情统一 404。M13 F-002（无独立产物列表）accepted                             |
| M03-AC-05                 | pass | EV-0354（DEPT-004）                            | 同部门成员访问个人空间会话/文件统一 404                                                                                                                                                                                   |
| M03-AC-06                 | pass | EV-0361（M03 E2E 回归）；EV-0354（DEPT-010）   | 固定文案“知识库为全租户共享，文档级权限在后续版本提供。”；源文件 404 只显示文件名与不可访问原因；未发现部门隔离/文档级权限虚假声明                                                                                        |
| M03-AC-07                 | pass | EV-0354（DEPT-006/007/008）；REV-M13-001 回归  | 停用、移出、负责人移交下一次请求生效；审计可查（DEPT 段）                                                                                                                                                                 |
| M03-AC-19（部门/空间段）  | pass | EV-0354（DEPT-001..010）；REV-M13-001 回归     | 零 skip、零 xfail；导出请求段归 M10                                                                                                                                                                                       |
| M03-AC-20                 | pass | EV-0354（TEN-001..027）；REV-M13-001 回归      | M02 跨租户回归；零 skip；完整性清单无缺失                                                                                                                                                                                 |
| M03-AC-21（部门管理页段） | pass | EV-0357（8/8）；EV-0361（M03/M13 375px、键盘） | 部门管理页空/成功/403 态、375px、键盘、axe 无 serious/critical；M13 F-006（AntD Select 关闭后隐藏 listbox 缺 accessible name，P3）accepted；OIDC/审计段归 M10                                                             |

结果值：`pass`、`partial`、`fail`、`waived`（必须有用户书面理由）。`partial` 表示 AC 原文的部分用户可见结果未被真实证据覆盖，不能当作 pass。

M10 的 OIDC、审计查询/导出与对应 a11y 段落在本文件随 M10 另列，不在 M03 预验收中冒充通过；AC-08..18 不计入 M03。

### 已接受/残余项（不改变本次结论，但不得声称已解决）

| 来源 | ID    | 级别 | 状态                | 说明                                                                                                                                                                                       |
| ---- | ----- | ---- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| M03  | F-006 | P3   | accepted            | J2 产物正文与引用区重复“引用清单”标题；真实栈测试已定位 `#artifact-citations-title`，产品语义后续收敛                                                                                      |
| M03  | F-007 | P2   | accepted / residual | PRODUCT 文本漂移：AC-07 演员（平台管理员执行）、AC-21 分段（M03 只验部门管理页）、AC-03 历史数据口径（E2E 用无历史 TEAM 空间租户）；ADR-0018 §8 与 REV-M03-001 已登记，PRODUCT AC 正文未改 |
| M03  | F-008 | P2   | accepted / residual | 历史 TENANT 空间保持 M02 全租户可见面（R-11）；M03/M13 不提供转换，M04/M09 设计专用迁移                                                                                                    |
| M03  | F-009 | P2   | accepted / residual | 平台来源部门审计事件只写 platform 租户 + `details.targetTenantId`，目标租户不可见；M10 需显式授权查询模型承接                                                                              |
| M13  | F-001 | P2   | accepted            | 后端 `SessionInfo`/`UserSummary` 未下发 `departmentId`/`departmentName`；前端以 `/departments` 与可选字段兜底，成员/未分配/停用/租户管理员四态 E2E 通过；契约对齐由 M04/M10 承接           |
| M13  | F-002 | P3   | accepted            | 没有跨空间的独立产物列表；产物从报告任务详情进入；“部门报告可见可打开”已满足，独立产物列表留待后续模块                                                                                     |
| M13  | F-003 | P3   | accepted            | 重命名 409/403/404 的 UI 文案由 stub 场景覆盖；真实服务端空间授权边界由 M03 DEPT 套件覆盖，真实栈不跑该 UI 错误分支                                                                        |
| M13  | F-004 | P2   | accepted            | 历史 TENANT 空间保持全租户可见，M03/M13 不提供转换；与 M03 F-008 同一风险（R-11）                                                                                                          |
| M13  | F-005 | P3   | accepted            | PRODUCT 的 AC-07 演员、AC-21 分段、AC-03 历史数据口径仍为原始文本；与 M03 F-007 同一漂移，本补丁不修改 PRODUCT                                                                             |
| M13  | F-006 | P3   | accepted            | 键盘打开/关闭 AntD 工作空间 Select 后，隐藏 listbox 缺 accessible name；键盘路径本身已验证，合同 a11y 初始态 8/8，后续设计系统/AntD 升级统一处理                                           |

## Product 预验收（M03）

- 结论：`pass`
- 需求一致性：R-M03-01..03 均已修复；AC-01/02/04 从 fail 转 pass；AC-03/05/06/07/19/20/21 回归通过；未发现新的阻塞性用户可见缺口。M13 F-001..F-006 为契约/测试/a11y/历史数据残余，已由 REV-M13-001 accepted；M03 F-006/F-007/F-008/F-009 仍 accepted/residual，不声称解决。AC-08..18（M10）未计入 M03。
- UX/文案：
  - 成员部门：聊天页与报告工作空间头部显示“所属部门：…”，未分配“未分配部门”，停用部门“名称 + 已停用”，租户管理员“角色：租户管理员”；375px 无横向滚动，axe 通过。
  - 空间重命名：共享抽屉内可管理空间有“重命名”，列表与切换器同步；409“空间名称已存在，请换一个名称。”、403“你不是该空间的管理者，无法执行此操作。”、404“空间或部门不存在，或你没有管理权限。”；历史 TENANT 无入口；普通成员无共享入口。
  - 部门报告：研究页“切换工作空间”可选择个人/部门空间；从聊天部门空间进入时带 `workspaceId`，部门报告可见并可打开“报告产物”；默认个人空间不显示部门报告；他部门列表/详情 404。
  - 知识库“全租户共享”明示与来源文件 404 文案保持；AC-21 部门管理页 375px、键盘、axe 保持。
- 备注：M13 F-001 的 UI 兜底已由 `/departments` 覆盖，用户可见结果成立；M13 F-003 的错误文案分支使用 stub，服务端授权边界由 M03 DEPT 套件覆盖；M13 F-006 的 AntD Select 隐藏 listbox 命名问题为 P3，不阻塞。部门管理页 403 态仍只有“重试”，作为 P3 文案改进（建议“返回工作空间/联系管理员”），不阻塞用户验收。

### 退回任务（M13 已修复）

| ID       | 状态  | AC        | 原缺口               | 修复与验证                                                                                                                   |
| -------- | ----- | --------- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| R-M03-01 | fixed | M03-AC-01 | 成员端无所属部门     | 聊天/报告工作空间头部新增“所属部门”徽标；未分配、停用部门、租户管理员分支；EV-0361 R-M03-01，375px/axe 通过                  |
| R-M03-02 | fixed | M03-AC-02 | 部门空间无重命名入口 | 共享抽屉新增“重命名”，复用 `PATCH /workspaces/{id}` name；列表/切换器同步；409/403/404 文案；TENANT 无入口；EV-0361 R-M03-02 |
| R-M03-03 | fixed | M03-AC-04 | 部门空间内报告不可见 | 研究页支持按可访问空间选择；聊天部门空间带 `workspaceId` 进入并打开部门报告；默认个人空间不变；他部门 404；EV-0361 R-M03-03  |

Product 预验收不检查实现细节、安全边界或测试充分性；这些由 Verifier 负责。发现缺口时先退回任务，不把问题留给用户验收。

## 用户最终验收（M03）

- 结论：`accepted`
- 决策 ID：`DEC-0016`
- 条件/剩余项：M03 F-006/F-007/F-008/F-009 与 M13 F-001..F-006 作为已接受/残余项；F-009 由 M10 承接审计可见性
- 下一步：M10「身份与审计纵深（M03b）」按 DEC-0011 顺序启动；M11/M12 维护任务按用户要求先于 M10 完成

## 验收标准（M10，gate pass 后）

M10 原 gate 在 `a88d003`（REV-M10-001，29 条 reviewed_evidence；DEC-0018 将上限从 12h 提高到 20h）；M15 修复后以 `864b0a7`（代码）与 `de33ed8`（docs）的 EV-0482..0486、EV-0488、EV-0489 复验（REV-M15-001；EV-0487 为中间失败，不引用）。编号沿用 PRODUCT §8.3 的 `M03-AC-08..18`（§8.5 归属 M10，即任务中的 M10-AC-08..18）；AC-19/20/21 只核对 M10 段。`partial` 表示 AC 原文的部分用户可见结果未被真实 UI 证据覆盖，不能当作通过。

| AC（PRODUCT §8.3） | 结果 | 证据                                                                                                                       | 备注                                                                                                                                                                                |
| ------------------ | ---- | -------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M03-AC-08          | pass | EV-0449（identityProvider 解析）；EV-0450（前端展示）；EV-0478（a11y）；EV-0480（E2E-M15-03）；EV-0488（E2E-M15-01/02/03） | 企业登录后会话头部显示“来源：Alpha 测试企业 IdP”，LOCAL 显示“来源：本地账号”；租户名/显示名/来源 IdP 可见；无 JWT/access/refresh token、完整 issuer/subject/邮箱；E2E-M15-01 通过。 |
| M03-AC-09          | pass | EV-0448（E2E-M03-04、真实栈绑定→登录）；EV-0438（IT-OIDC-001..002）                                                        | 不按邮箱自动绑定；未绑定提示“请联系管理员关联账号”；管理员录入 issuer+subject 后命中同一账号与原有角色。B2 F-001 collation 为 accepted/residual，不声称精确匹配已修复。             |
| M03-AC-10          | pass | EV-0449/EV-0450（identityProvider）；EV-0488（E2E-M15-02 两 issuer）                                                       | 后端同邮箱不同 issuer 生成两个互不覆盖账号；用户管理显示配置 providerName 与历史 issuer host（`beta-idp.example.test`）且不同；完整 issuer/subject/邮箱不展示。                     |
| M03-AC-11          | pass | EV-0438（IT-OIDC-004、SEC-OIDC-002）                                                                                       | 禁用账号不建立会话，登录页提示联系管理员；已存在会话在下一请求被拒绝。                                                                                                              |
| M03-AC-12          | pass | EV-0448（E2E-M03-03、真实栈联合登出）；EV-0438（IT-OIDC-005）                                                              | 本地会话先失效、后退不可恢复；IdP 支持时自动联合登出；IdP 不可达时本地登出仍成功并提示“已退出本地会话”。B4 F-003/F-006 的 P3 见残余。                                               |
| M03-AC-13          | pass | EV-0448（E2E-M03-04、真实栈失败注入）；EV-0438（IT-OIDC-006）                                                              | 拒绝授权、state 失效、IdP 不可达、账号未开通等均有独立文案与下一步；不显示 IdP 原始错误、令牌、内部地址或堆栈。                                                                     |
| M03-AC-14          | pass | EV-0438（IT-OIDC-007、SEC-OIDC-003）                                                                                       | 内置与 OIDC 会话 Cookie/CSRF/空闲超时一致；角色变更下一请求生效；响应体与前端存储无 JWT/refresh token。                                                                             |
| M03-AC-15          | pass | EV-0448（E2E-M03-05、真实栈 220 行）；EV-0438（IT-AUDIT-101..102）                                                         | 时间/动作/操作者/对象过滤、固定倒序、分页上限、空态、六字段、查询写 `audit.queried`；普通员工 403。                                                                                 |
| M03-AC-16          | pass | EV-0448（真实栈 405/普通员工 403）；EV-0438（IT-AUDIT-103、TEN-028..030）                                                  | 无修改/删除入口；错误方法返回 405 `RICCON-METHOD-405`（TASK-0077/0082）；数据库 append-only 拒绝 UPDATE/DELETE。                                                                    |
| M03-AC-17          | pass | EV-0448（E2E-M03-06、真实栈导出链）；EV-0438（IT-AUDIT-104..105）                                                          | 申请待审批、不可提前下载、不可自批；另一管理员批准；单管理员租户由平台管理员审批；申请人限时下载 CSV；拒绝原因展示且不可下载；审批/范围/行数入审计。                                |
| M03-AC-18          | pass | EV-0448（真实栈 CSV 白名单/公式转义/410）；EV-0438（IT-AUDIT-106、AuditExportClockTest）                                   | 界面显示保留策略与可查范围、无删除入口；CSV 不含密码/令牌/凭据/原文/邮箱/来源 IP；过期 410 不可下载。CSV 中途失败/行数竞态 P3 见残余。                                              |

补充段（PRODUCT §8.5 归属）：

- **M03-AC-19（导出请求段）**：pass；DEPT-010、TEN-028..030；部门成员不能旁路读取导出列表/详情/下载；零 skip。
- **M03-AC-20（部门与导出新对象）**：pass；TEN-028..030、`TenantIsolationSecurityTest` 30/30；M02 跨租户回归由合同 test/E2E 覆盖。
- **M03-AC-21（M10 页面段）**：pass；EV-0480（E2E-M03-07 + E2E-M15-03 定向 2 passed）、EV-0481（a11y 9 passed）、EV-0488（完整 real-stack 18 passed）覆盖登录页、审计、导出、用户管理 375px、键盘与 axe；M15 修正了 M10 原 E2E 在页面渲染前扫描的假绿。M15 F-003（antd 绿色 Tag 对比度 3.37:1，既有 P2）accepted，测试仅白名单 `ant-tag-green`，不声称全局 axe 零例外。

模块级证据（M15 复验，代码 `864b0a7`、docs `de33ed8`）：EV-0482 build；EV-0483 test（后端 765、前端 384，0 fail/0 error/0 skip）；EV-0484 coverage（API line 91.53%/branch 74.13%，Worker line 87.87%/branch 70.77%，Web line 87.71%/branch 80.75%）；EV-0485 合同 E2E 29 passed；EV-0481 a11y 9 passed；EV-0486 security exit 0；EV-0488 完整 real-stack 18 passed；EV-0489 docs/diff check。M10 原 gate 证据 EV-0435/0438/0443/0440/0439/0436/0448/0437 作为历史保留，不覆盖 M15 复验。

### 已接受/残余项（M10/M15，不改变 Product 结论，但不得声称已解决）

| 来源        | ID                                        | 级别  | 状态                | 说明                                                                                                                                                                                                                    |
| ----------- | ----------------------------------------- | ----- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B2          | F-001                                     | P2    | accepted / residual | `external_issuer/external_subject` 使用 `utf8mb4_0900_ai_ci`，大小写/重音变体可能命中同一身份；真实栈 `caseVariantSubjectMatched=true`；**未修**，需后续迁移显式 `ascii_bin`/`utf8mb4_0900_as_cs` 或 ADR 书面风险接受。 |
| B2          | F-003/F-004                               | P3    | accepted            | 绑定/解绑不即时撤销既有会话（受 30 分钟空闲超时限制，ADR-0019 §5）；平台跨租户绑定审计 details 无 `targetTenantId`，目标租户看不到平台来源事件（M03 F-009 residual）。                                                  |
| B4          | F-003/F-004/F-006                         | P3    | accepted            | sessionStorage 异常可能让本地登出显示失败；logout 网络错误文案未归一；联合登出 URL/attempted 状态可能跨登录残留。正常路径不受影响，后续小修。                                                                           |
| B4          | F-005                                     | P3    | accepted / residual | 用户管理列表固定 `size=100`、无分页/搜索；M10 验收规模内可用，真实租户超过 100 个账号时后续账号无法在界面管理，后续模块补。                                                                                             |
| B5          | F-002/F-003/F-004/F-005                   | P3    | accepted / residual | 审计 100 行未断言 SQL 次数/耗时；CSV 中途批次失败可能已发 200 后截断；计数与流之间行数上限竞态；导出申请/下载只校验 `audit.read`。正常数据与当前角色模型下可用，不声称护栏完整。                                        |
| B3a/B5/模块 | F-003/F-004/F-005/F-007/F-013/F-014/F-015 | P3    | accepted            | worker 期望版本未锁定、V10 类型断言缺口、`audit.read` 描述漂移、405 测试编号、`SEC-AUDIT-001` 未落地、M10 页面 E2E 在真实栈、ARCHITECTURE 状态表未更新；均为测试/文档/编号残余。                                        |
| M15         | F-003                                     | P2    | accepted            | antd 6 绿色状态标签（启用/已批准）对比度约 3.37:1，低于 WCAG AA 4.5:1；自 M02/M03 起存在，不属于来源 IdP 变更面；测试仅白名单 `ant-tag-green`，后续 UI 主题任务统一调整。                                               |
| M15         | F-006                                     | P3    | accepted            | 两 issuer 真实栈夹具用测试 SQL 模拟历史 issuer（绑定 API 只接受租户当前配置 issuer）；只写测试栈，不改变产品契约。                                                                                                      |
| OPS         | OPS-004..007                              | P1/P2 | test-env-only       | V10 迁移/回滚仅在 Testcontainers/Compose 测试栈演练通过；生产协调切换、维护窗口、备份恢复、OIDC 回滚锁死（OPS-010）未执行，需 M09/生产 T4 批准。                                                                        |
| OPS         | OPS-008/009/013/014/015                   | P2/P3 | open                | 导出清理索引、流式连接占用、平台租户导出兜底、历史 details 清点、publicBaseUrl/访问日志脱敏由 M09 承接。                                                                                                                |
| ADR         | ADR-0019/0020                             | —     | proposed            | REV-M10-ARCH-002 pass，但 ADR 仍 `proposed`；ARCHITECTURE 评审状态表待 Lead 更新（F-015），不视为已批准。                                                                                                               |
| 范围        | MFA/审计哈希链/平台跨租户审计             | —     | deferred            | 按 DEC-0013 后置；M10 不声称支持 MFA、审计哈希链/完整性证明、平台跨租户查看租户业务审计明细。                                                                                                                           |

## Product 预验收（M10，M15 后复验）

- 结论：`pass`
- 需求一致性：AC-08/10 在 M15 后通过：会话头部显示“来源：Alpha 测试企业 IdP”，LOCAL 显示“来源：本地账号”；用户管理同时显示配置 providerName 与历史 issuer host（`beta-idp.example.test`），两个 issuer 可区分且不泄露完整 issuer/subject/邮箱。AC-09、11–18 与 AC-19/20/21 的 M10 段有真实证据，通过。M15 同时修复原 M10 E2E 的 a11y 假绿与导出/审计按钮可访问名称（F-001/F-002/F-004 fixed）；未发现新的用户可见缺口。B2 F-001 collation P2 仍为 accepted/residual；M15 F-003 绿色 Tag 对比度 P2 accepted；B4/B5 P3、OPS 生产未演练、ADR proposed 均按残余/deferred 记录，不声称解决。
- UX/文案：
  - 登录/会话头部：企业账号入口、未开通与九类原因码（IdP 不可达、state 失效、拒绝授权、未绑定、禁用、租户不可用、issuer 不匹配、限流、登录未完成/未知原因）均有独立标题、说明与下一步；“来源：…”显示脱敏 providerName，LOCAL 显示“本地账号”；不泄露租户存在性、完整 issuer、subject、邮箱或令牌。
  - 登出：本地会话先失效、联合登出自动回跳；IdP 不可达时明确“已退出本地会话”，浏览器后退不恢复登录态。
  - 审计：保留策略与只读边界、筛选/倒序分页/空态/错误/403/加载态齐全，无删除入口；查询本身写入 `audit.queried`。
  - 导出：申请→待审批→另一管理员批准/拒绝→申请人限时下载；自批无入口；单管理员租户由平台管理员审批；平台租户自身导出 403 文案；拒绝原因与 410 过期文案可读。
  - 用户管理：authSource 标签、来源 IdP 标签、绑定/解绑、平台租户拒绝、普通成员 403/无入口；空/错误/加载态完整。
  - a11y/移动端：M15 修正 a11y 假绿后，EV-0480（E2E-M03-07 + E2E-M15-03 2 passed）、EV-0481（9 passed）、EV-0488（完整 real-stack 18 passed）覆盖登录/审计/导出/用户管理 375px、键盘与 axe；M15 F-003 为既有 antd 绿色 Tag 对比度 P2 accepted，不声称全局 axe 零例外。
  - 残余：用户列表 100 条上限为 P3 accepted/residual，不作为本次阻塞项。
- 备注：M10 原 gate pass（REV-M10-001）；M15 gate pass（REV-M15-001）；本次复验基于 `864b0a7`（代码）与 `de33ed8`（docs）的 EV-0482..0486、EV-0488、EV-0489（build/test 765+384/coverage/E2E/security/real-stack/docs；EV-0487 中间失败不引用）。生产迁移、OPS-004/010、T4 批准未完成，ADR-0019/0020 仍 proposed；MFA、审计哈希链、平台跨租户审计明细 deferred，不冒充支持。

### 退回任务（M10）

| ID       | 状态  | AC                   | 原缺口                                                                                                                                                                       | 修复与验证                                                                                                                                          |
| -------- | ----- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| R-M10-01 | fixed | M03-AC-08、M03-AC-10 | 登录后工作空间只显示租户名与显示名；用户管理列表只显示认证来源类型，未显示具体 IdP（providerName/issuer），两个不同 issuer 的 OIDC 账号不可区分；E2E-M03-03 未断言来源 IdP。 | M15：后端 `08e0fc6`/EV-0449、前端 `4538d8c`/EV-0450、a11y `c4ebd3e`/EV-0478；E2E-M15-01/02/03 在 EV-0480/EV-0488 通过；Product 复验 AC-08/10 pass。 |

Product 预验收不检查实现细节、安全边界、并发或测试充分性；这些由 Verifier 负责。发现需求缺口时通过 Lead 退回任务，不把问题留给用户。

## 用户最终验收（M10）

- 结论：`accepted`
- 决策 ID：`DEC-0021`
- 条件/剩余项：R-M10-01 已 `fixed`；Product 预验收 `pass`（M15 后）；M15（fixes M10）随本次验收一并接受。B2 F-001 collation P2、M15 F-003 P2、B4/B5 P3、OPS 生产未演练、ADR proposed 仍按残余/待办记录，不声称解决。
- 下一步：M04「模型网关纵深」；生产迁移/OPS-004/OPS-010/T4 批准仍为后续发布前置。

## M18 回溯复验（2026-11-20）

### 回溯范围与限制

- **触发**：DEC-0022 合并后合同 `commands.test` 变为 `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`，7 个模块旧 test EV 的命令文本不再匹配合同；2026-11-20 `gate check` 逐模块失败。属证据/流程漂移，不是代码回归（`.riccon/reviews/M18-revalidation-report.md`）。
- **已做**：在 HEAD `cb977f98a493bfb7db9f9d4b02731ac62e195053`（工作树干净）上，为 M01/M02/M03/M10/M12/M13/M15 各重录一条 test/coverage 证据（EV-0490..EV-0496），重写 7 份 module review（`REV-*-002`，真实会话 `ses_e08d1b473ffezoltlVNmpba9tZ`，`previous_review` 指向归档旧评审），逐个 `gate check` 通过；最终循环 EV-0498，exit 0。EV-0497 为 superseded，不引用。
- **本次实测**（7 次一致）：Java 765 tests（api 453 / worker 263 / platform-common 33 / arch 12 / domain 4），0 fail / 0 error / 0 skip；前端 46 文件 384 tests passed、0 skip；前端覆盖率 lines 87.71% / branches 80.75%（合同 80/70），Maven `jacoco:check` 通过。
- **未重跑**：E2E、a11y、security、真实栈、迁移回滚。这些维度仍绑定旧 EV：M02 为 EV-0301（合同 E2E）、EV-0295（a11y）、EV-0294（security）、EV-0299（真实栈）；M03/M10/M13/M15 为各自 review 正文的 EV。M15 的 EV-0485（合同 E2E 29 passed）、EV-0481（a11y 9 passed）、EV-0486（security exit 0）、EV-0488（真实栈 18 passed）录于 `864b0a7`，晚于 M02 自身证据；`git diff --name-only 864b0a7..HEAD -- apps platform e2e deploy` 为空，`contracts/openapi.yaml` 有 M04 架构契约变更但未重跑契约检查。
- **口径**：「证据未重跑」「历史 open finding」「代码回归」三者分开记录。本次未发现代码回归，未发现新的用户可见缺陷；不声称全流程重跑。

### 总表

| 模块 | 旧 review   | 新 review   | 新 test/coverage EV  | gate | Product 结论               | 残余                                                                                                                               |
| ---- | ----------- | ----------- | -------------------- | ---- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| M01  | REV-M01-001 | REV-M01-002 | EV-0490              | PASS | N/A（工程基线，无用户 AC） | 5 条 open P2（F-002/F-004/F-005/F-006/F-007）+ 2 条 open P3（F-008/F-009）+ 11 条 legacy 无 commit attestation；F-007 为发布阻断项 |
| M02  | REV-M02-003 | REV-M02-004 | EV-0491              | PASS | `partial`（回溯预验收）    | F-009/F-014 open P3；F-007/F-011/F-015 accepted P2；AC-02 逐格式 UI 证据缺口；E2E/a11y/security 本次未重跑                         |
| M03  | REV-M03-001 | REV-M03-002 | EV-0492              | PASS | `pass`（沿用 DEC-0016）    | F-006..F-010 accepted/residual                                                                                                     |
| M10  | REV-M10-001 | REV-M10-002 | EV-0493              | PASS | `pass`（沿用 DEC-0021）    | B2 F-001 collation、B4/B5 P3、OPS 生产未演练、ADR-0019/0020 proposed                                                               |
| M12  | REV-M12-001 | REV-M12-002 | EV-0494              | PASS | N/A（无用户可见 AC）       | F-002..F-005、F-007 accepted P3                                                                                                    |
| M13  | REV-M13-001 | REV-M13-002 | EV-0495              | PASS | `pass`（随 M03 DEC-0016）  | F-001..F-006 accepted                                                                                                              |
| M15  | REV-M15-001 | REV-M15-002 | EV-0496              | PASS | `pass`（随 M10 DEC-0021）  | F-003/F-005/F-006 accepted                                                                                                         |
| M18  | —           | —           | EV-0498（gate 循环） | —    | `pending`（gate 后另做）   | TASK-0091 只写本文件；M18 module review/gate 未做                                                                                  |

gate warnings：M01 8 条、M02 2 条，其余 0；M01/M02 的 warning 内容与上表残余一致，不因 gate PASS 视为已解决。

### M02 预验收明细（M02-AC-01..16）

**限制**：本节证据来自本次 test/coverage 重录（EV-0491）与旧 E2E/a11y/security/真实栈 EV；**本次未重跑 E2E/a11y/security**。旧 EV 的代码路径自 `864b0a7` 起未变，但不能当作本次执行。

| AC        | AC 原文的用户可见结果（Then 摘要）                                                                         | 结果    | 证据                                                                                                                                                                                                                                    | 限制 / 缺口                                                                                                                                                                 |
| --------- | ---------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M02-AC-01 | 进入租户 A 工作空间并显示租户名；错误凭据只显示统一失败提示，不泄露账号是否存在                            | pass    | EV-0299 真实栈（正确凭据进入 `tenant-alpha`；错误凭据统一提示）；EV-0485 合同 E2E 登录 2 条（未重跑）                                                                                                                                   | 本次未重跑                                                                                                                                                                  |
| M02-AC-02 | PDF/DOCX/XLSX/PNG/JPEG 各一个均显示解析状态并可用于提问；不支持格式或超限被拒绝并提示原因                  | partial | 真实栈 DOCX 上传/解析/引用（EV-0299）；stub E2E PDF 解析成功并提问（EV-0485）；解析器集成测试 pdf/docx/xlsx/图片/JPEG（`DocumentParserTest`、`FileParseServiceIntegrationTest`）；超限 413（`FileUploadLimitTest`）；不支持格式组件测试 | **缺口**：PDF/XLSX/PNG/JPEG 未在浏览器/真栈证明“显示解析状态并可用于提问”；格式解析由集成测试覆盖，UI 只验了 PDF/DOCX。去向：用户决策包（接受为残余或补逐格式 UI 验证任务） |
| M02-AC-03 | 3 秒内出现首字，回答逐步增长；中途停止后已生成内容保留                                                     | pass    | EV-0299 真实栈首字 337ms、增长采样 `[10,30,39,60,70,80,100,110,130]`、DB 73ms vs 3167ms；EV-0485 stub 停止后显示“已停止生成”且内容仍在（未重跑）                                                                                        | 337ms 为单次实测，非 p95；`commands.perf`/k6 未执行（M02 F-007 accepted）。不声称 p95 达标                                                                                  |
| M02-AC-04 | 每条引用显示文件名与定位（页码/工作表/段落）且可打开；无命中提示未检索到资料，不出现伪造引用               | pass    | EV-0299 真实 J1 DOCX 引用抽屉（段落定位）与快速发送 fileIds；EV-0485 stub 引用抽屉“第 3 页”+ 片段、无命中“当前知识库未检索到相关资料”（未重跑）                                                                                         | 引用打开为抽屉内定位信息，不打开原文件；无命中为 stub/组件证据                                                                                                              |
| M02-AC-05 | 失败原因与重试入口；已上传文件与已有消息不丢失                                                             | pass    | 组件测试：解析失败原因 + “重试解析”成功、模型失败提示 + 重试、SSE 断线保留内容；API 集成：`RICCON-MODEL-500`、`FileLifecycleApiTest` 解析失败重试                                                                                       | 验证类型为组件+集成，非 E2E；真栈未注入解析/模型故障。PRODUCT 验证列为 E2E，按证据类型记残余                                                                                |
| M02-AC-06 | 任务进入运行中并显示当前阶段（检索/工具/审批/生成）                                                        | pass    | EV-0299 真实 UI 加载 `market_data · 只读 · 需审批` 并创建任务；阶段 RETRIEVAL/TOOL/APPROVAL/GENERATION（未重跑）                                                                                                                        | 本次未重跑                                                                                                                                                                  |
| M02-AC-07 | 展示 ≥1 条来源、标题与片段并带引用；无结果时提示调整主题且任务不进入生成                                   | pass    | EV-0299 审批前 4 条来源/片段/定位；无结果：worker 集成 `ReportArtifactIntegrationTest`（RETRIEVAL FAILED、不产生工具/审批/生成）+ 组件测试“调整主题重新创建”（未重跑）                                                                  | 无结果分支未在 E2E 走通；正向路径为真栈                                                                                                                                     |
| M02-AC-08 | 工具名、入参摘要、结果摘要、耗时与状态；失败显示原因且可重试该阶段                                         | pass    | EV-0299 审批预览工具名 + 脱敏参数、MCP 调用记录、阶段详情；EV-0485 stub 超时 `RICCON-MCP-504` + “重试工具阶段”（未重跑）                                                                                                                | 真栈未注入超时                                                                                                                                                              |
| M02-AC-09 | 未审批不生成；批准后继续，拒绝后终止并记录审批人、时间与决定                                               | pass    | EV-0299 批准→报告生成；拒绝→TOOL/APPROVAL SKIPPED、无产物、审批人/时间/决定；EV-0485 stub 拒绝路径（未重跑）                                                                                                                            | 本次未重跑                                                                                                                                                                  |
| M02-AC-10 | 产物含标题、摘要、分节正文、引用清单与数据来源；可再次打开；引用每条能在任务时间线找到                     | pass    | EV-0299 同源 content 渲染“摘要/分析”、下载 2156 字节、citationCount=5、“定位检索阶段”；EV-0485 stub 刷新后重开任务与产物（未重跑）                                                                                                      | >2 MiB 产物下载返回 413（M02 F-015 accepted），不声称大产物可下载                                                                                                           |
| M02-AC-11 | 任务标记失败、保留已完成阶段记录、可重试且重试不产生重复产物                                               | pass    | EV-0485 stub：MCP 超时→任务失败、旧调用留时间线、重试后仅 1 份报告（未重跑）                                                                                                                                                            | 真栈未注入超时/生成失败                                                                                                                                                     |
| M02-AC-12 | 统一拒绝响应、不返回任何 B 的内容、不泄露对象是否存在                                                      | pass    | EV-0299 真实栈 8 类对象 404（含 content）、J1/J2 run 404/404、beta 工具 total=0（未重跑）；TEN-001..030 由 EV-0491 重跑                                                                                                                 | 真栈部分本次未重跑                                                                                                                                                          |
| M02-AC-13 | 跨租户列表、检索、对象读取与管理接口全部通过；零 skip、零 xfail                                            | pass    | EV-0491 全量 765+384、0 skip；`TenantIsolationSecurityTest` TEN-001..030 + 完整性清单；EV-0299 真实栈 8 类 404（未重跑）                                                                                                                | test 重跑含 TEN 套件；真实栈部分未重跑                                                                                                                                      |
| M02-AC-14 | 登录、文件上传/删除、会话创建、模型调用、MCP、审批与权限变更均产生审计；审计写入失败则业务失败             | pass    | EV-0299 16 类动作、tenant_id 非空；fail-closed：`AuditFailClosedTest`（登录）、`ResearchAuditFailClosedTest`（创建任务/审批）、`FileAuditFailClosedTest`（上传回滚并清理对象）（未重跑）                                                | 本次未重跑真栈审计                                                                                                                                                          |
| M02-AC-15 | 界面与接口均无删除/修改能力；普通员工无法访问审计接口                                                      | pass    | EV-0299 UPDATE/DELETE 均 ERROR 1644 `audit_event is append-only`；EV-0129/0130 负向证据；TEN-012 普通员工 403；无删除/修改端点                                                                                                          | 负向证据保留，不重跑                                                                                                                                                        |
| M02-AC-16 | 四界面加载/空/错误/成功/无权限均有文案与下一步；375px 无横向滚动；键盘完成登录、上传、提问、审批，焦点可见 | pass    | EV-0485 stub：空态、403、409、375px、键盘上传/审批与焦点；EV-0481 a11y 9 passed；EV-0488 真实栈 18 passed（J1/J2 a11y + 375px）（均未重跑）                                                                                             | 键盘路径为 stub；真栈未覆盖 M02 页面键盘；a11y/375px 本次未重跑                                                                                                             |

**小结**：16 条中 15 条 `pass`、1 条 `partial`（AC-02）。`pass` 中有 4 条依赖 stub 或组件/集成证据（AC-05、AC-07 无结果分支、AC-11、AC-16 键盘），已逐条标注，不当作真栈全链路通过。

模块级证据：EV-0491（本次 test/coverage，Java 765 + 前端 384，0 skip）；EV-0301（M02 时合同 E2E 19 passed）；EV-0485（M15 时合同 E2E 29 passed）；EV-0481（a11y 9 passed）；EV-0486（security exit 0）；EV-0299（M02 真实栈 10 passed + 跨租户/审计检查）；EV-0488（M15 时真实栈 18 passed）。除 EV-0491 外均为旧 EV，本次未重跑。

### Product 预验收（M02，M18 回溯）

- 结论：`partial`
- 需求一致性：AC-01、03–16 的用户可见结果有证据（含上表标注的证据类型限制）；AC-02 的“五种格式各自显示解析状态并可用于提问”只在 DOCX（真栈）与 PDF（stub）上验证，XLSX/PNG/JPEG 仅有解析器/上传集成测试，没有浏览器或真栈证据。未发现代码回归或新的用户可见缺陷；`partial` 指证据缺口，不是已观察到的功能失败。
- UX/文案：登录统一失败提示、解析状态与“重试解析”、停止生成与内容保留、引用抽屉定位、“未检索到相关资料”、J2 阶段名与失败码 `RICCON-MCP-504` + “重试工具阶段”、审批人/时间/决定、产物“摘要/分析/引用清单/数据来源”、403/409/空态、375px 与键盘路径均有旧 EV 或组件证据；本次未重跑，文案未重新抽查。
- 残余与去向：
  - AC-02 逐格式 UI 证据缺口：Product 不能建任务；由 Lead 决定是否补一条验证任务，或作为残余记入用户决策包（建议后者，理由：格式解析已由集成测试覆盖，UI 路径格式无关，风险为 P3）。
  - M02 F-009（P3 open，J1 原始 Markdown 展示）、F-014（P3 open，首条检索片段信息量低）仍 open，不声称解决。
  - M02 F-007（P2 accepted，`commands.perf` 缺失、无 p95）、F-011（P2 accepted，`/models` 与 admin 目录契约漂移）、F-015（P2 accepted，>2 MiB 产物不可下载）仍按 accepted 记录，不阻塞本次结论。
- 备注：M02 已于 2026-11-18 标 done（历史事实），本次回溯预验收不重开模块；`partial` 作为用户决策输入，需用户在「用户最终验收（M02/M18 回溯）」中选择接受为条件接受或要求补验证。

### M03 / M10 / M13 / M15 补丁式复验

- 四份新 review 只做证据重绑：`reviewed_evidence` 追加 EV-0492/0493/0495/0496，`dod_evidence.tests` 与 `dod_evidence.coverage` 指向新 EV，`dod` 其余项与 findings 状态未改；旧 review 原样归档在 `.riccon/archive/reviews/`。
- **M03**（REV-M03-002，gate pass，0 warnings）：AC-01..07、AC-19 部门段、AC-20、AC-21 部门管理页段原结论不变；F-006..F-010 仍 accepted/residual。DEC-0016 用户 accepted 仍有效，本文件 M03 章节不改写。
- **M10**（REV-M10-002，gate pass，0 warnings）：AC-08..18 与 AC-19/20/21 的 M10 段原结论不变；B2 F-001 collation、B4/B5 P3、OPS 生产未演练、ADR-0019/0020 proposed 仍按残余记录。DEC-0021 用户 accepted 仍有效，本文件 M10 章节不改写。
- **M13**（REV-M13-002，gate pass，0 warnings）：R-M03-01..03 仍 `fixed`，F-001..F-006 仍 accepted；随 M03 DEC-0016 accepted 不变。
- **M15**（REV-M15-002，gate pass，0 warnings）：R-M10-01 仍 `fixed`，F-003/F-005/F-006 仍 accepted；随 M10 DEC-0021 accepted 不变。
- 边界：四份新 review 均未重跑 E2E/a11y/security/真实栈，正文原结论不扩大；本次只恢复 gate 证据链。

### M01 / M12 登记（legacy / no-impact）

- **M01**（REV-M01-002，gate pass，8 warnings）：工程基线模块，无用户可见 AC，Product 预验收/用户验收按 N/A legacy 处理，不作为 gate 前置。F-001（前端覆盖率）与 F-003（生成代码计入分母）已由 EV-0490 与当前 `apps/web/vitest.config.ts` 证明修复；其余仍 open：P2 F-002（M01 时 `commands.security` 失败，M02 B-06 已处置但本次未重跑 security）、F-004（Umi overlay console TypeError / antd Alert 废弃警告）、F-005（ProLayout beta 折叠按钮 a11y）、F-006（11 条工具安装 EV 无 commit attestation）、F-007（MySQL Connector/J GPLv2+FOSS exception，发布阻断项未关闭）；P3 F-008（RUNBOOK 旧口径）、F-009（E2E 覆盖历史限制）。本次不声称这些项已解决。
- **M12**（REV-M12-002，gate pass，0 warnings）：维护/效率模块，无新的用户可见 AC，Product/用户验收为 `not-recorded`（N/A）；F-002..F-005、F-007 仍 accepted P3。no-impact。

### M02 Product/用户验收缺口

M02 是用户可见 MVC 模块，但 2026-11-18 关闭时没有 `product_acceptance` 记录（Product 预验收/用户最终验收均为 `not-recorded`），`REV-M02-003` 只是独立技术评审。本次在 M18 回溯中补做 **Product 预验收**（结论 `partial`），不补造用户验收；用户最终验收见下一节，结论 `pending`，由 Lead 提交决策包。

## 用户最终验收（M02/M18 回溯）

- 结论：`pending`
- 说明：M02 此前没有用户最终验收记录；本次只补 Product 预验收，不代用户接受。M18 的 Product 预验收按规则在 gate 后另做，本文件不把 M18 标 pass；M18 自身 `product_acceptance` 仍为 `pending`。
- 待用户决定：
  1. **M02 预验收结论**：接受 `partial` 为条件接受（AC-02 逐格式 UI 证据作为残余，记录去向与截止），或要求先补验证任务再验收。
  2. **M18 回溯范围**：本次只重录 test/coverage（EV-0490..EV-0496、EV-0498）；E2E/a11y/security/真实栈/迁移回滚未重跑。是否接受该范围，或另立全量复验任务。
  3. **残余 E2E/a11y/security 未重跑**：M02 绑定 EV-0301/EV-0295/EV-0294/EV-0299，M15 绑定 EV-0485/EV-0481/EV-0486/EV-0488（`864b0a7`，代码路径未变）；是否要求在当前 HEAD 重跑。
  4. **M01 open P2**：F-002（security）、F-004（console）、F-005（ProLayout a11y）、F-006（legacy attestation）、F-007（MySQL 驱动许可，发布阻断）；如何承接，是否升为 M09/发布前置。
  5. **M11 legacy**：planned 但有 done 任务，`state validate` 保留 1 条 warning；是否单独立 docs-only closeout。
  6. **M14**：docs-only legacy，Product/user 仍 pending，待独立 closeout（不在本次范围）。
  7. **M02 用户最终验收记录**：`accepted` / `conditional` / `changes-requested` 及条件、截止时间；由 Lead 建决策包，Product 不代填。
- 约束：用户未 `accepted`/`conditional` 前，不把 M02 回溯验收记为通过；M18 不因本文件而进入 done。

## 历史模块

| 模块 | Product 预验收                                         | 用户最终验收               | 记录                                                                                       |
| ---- | ------------------------------------------------------ | -------------------------- | ------------------------------------------------------------------------------------------ |
| M01  | not-recorded（N/A legacy）                             | not-recorded（N/A legacy） | REV-M01-001/REV-M01-002 gate pass；工程基线无用户可见 AC；open P2/P3 见「M18 回溯复验」    |
| M02  | partial（回溯，见「Product 预验收（M02，M18 回溯）」） | pending（回溯决策包）      | REV-M02-004 gate pass；2026-11-18 关闭时无记录，2026-11-20 补做回溯预验收，不补假 accepted |

## 规则

- gate 检查通过后、模块标记 done 前，先完成 Product 预验收；Product 未 `pass` 前不能把模块标为 done。
- 模块 done 后再提交用户最终验收；用户未 `accepted` 前，不进入下一个用户可见模块；最终产品验收未 `accepted` 前，不进入生产发布。
- 用模块的 `product_acceptance` 记录两层结论：`product_status` 和 `user_status`；本文件保留详细 AC 表和证据。
- 验收失败用 `kind: fix` + `fixes_module` 建修复模块，或在显式决策后重新打开；不要只靠标题或 `depends_on`。
- `rejected`/`conditional` 必须列出条件、截止时间和后续任务。
- 验收结论不替代 gate、release check 或生产批准。
