# M05 规划期独立预评审（REV-M05-AC-001）

> 类型：规划期预评审，**不进入模块 gate**，不创建 EV，不代表模块验收。
> reviewer：`riccon/verifier`，session `ses_dfd763712ffebWT13UkWW360SK`，独立只读。
> 评审对象：`docs/PRODUCT.md` §11（22 条 M05-AC）、`docs/adr/ADR-0023-m05-knowledge-depth.md`（09e0c35 + 259dc7b）、`docs/ARCHITECTURE.md` M05 段。
> verdict：**pass-with-changes**；P1 未闭合前用户范围批准应视为 fail/blocked。
> 结论摘要：22 条 AC 中仅 AC-17 可无改动判定；9 条需改措辞/线索；12 条当前不可验证或不可判。

## P1（批准前必须修复）

| ID    | 标题                                                             | 关键问题                                                                                                                     | Owner             |
| ----- | ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| F-001 | AC-02/03 仍要求 P6=A 不提供的“打开文档/页图框选”                 | Product 文本写“打开文档并定位/高亮框选”，ADR 自认 A 不满足字面；B 需页图端点 +12–16h                                         | Product           |
| F-002 | AC-21 “清理内容”与合同 30 天删除口径未闭合                       | 选项 A 只软删除+索引 DELETE，物理对象归 M09 且无 owner/due；合同 `data.retention` 要求删除请求 30 天内完成                   | Product/Lead      |
| F-003 | retention 审计 `reason` 不在白名单，到期任务会 fail-closed       | `knowledge.document.deleted` details 只有 `sourceType/visibility`，到期任务写 `reason=retention`                             | Architect         |
| F-004 | 403/404 语义不闭合                                               | AC-06/09/10 平台管理员仍写 403；AC-09 未限定“已可见”；ADR `SEC-KB-002` 仍写 403；AC-09 未覆盖连接器配置跨租户 `SEC-CONN-004` | Product + Lead    |
| F-005 | 删除与 reindex 端点不存在                                        | `KnowledgeController` 只有 list/create/get；OpenAPI 的 DELETE/reindex 未实现；AC-15/16/21/09 依赖                            | Architect/Lead    |
| F-006 | M05/M05b 拆分与契约/UI/后端边界矛盾                              | B2 在 M05 写全量契约（含连接器），B8 写连接器 UI，B6 后端在 M05b；AC-22 要求契约不 404                                       | Architect/Lead    |
| F-007 | 连接器 tombstone 与唯一键冲突                                    | 唯一键 `(tenant, connector, external_id_hash)` 覆盖 tombstone；源端重现会撞键                                                | Architect         |
| F-008 | 连接器部分/截断 list 可能误删未见对象                            | 以“本轮未见 + last_seen_at”删除；无“完整 list 才传播删除” guard                                                              | Architect         |
| F-009 | AC-11 同步开始/结束时间未建模                                    | 只有 `last_sync_at/last_success_at/last_sync_summary`，失败同步无结束时间                                                    | Architect         |
| F-010 | 提示注入无可执行 harness                                         | 仓库无 `INJ-001..012`；`UntrustedDataAndToolTest` 只测包装/截断                                                              | Architect/Lead    |
| F-011 | OCR 测试 profile 与零出网证据不可复现                            | 默认 `RICCON_OCR_ENABLED=true`，无 Tesseract 的宿主/CI 会全面失败；`--network none` worker 无法连 MySQL/OpenSearch           | Architect/Lead    |
| F-012 | AC-20 检索 p95 无预算，perf harness 仍 M04 专用                  | `AgentContextJdbc` 用 `LIKE '%term%'` + ACL EXISTS；`mvc-smoke.js` 只认 `PERF_M04_SCENARIO`                                  | Architect/Lead    |
| F-013 | 知识库下载“预签名 + 不返回对象 key”自相矛盾                      | 预签名 URL 必含 key 且 TTL 内绕过 ACL；`FileStorage` 无流式代理                                                              | Architect         |
| F-014 | U8 租户管理员“管理性读取不受 ACL、读取不审计”与 AC/TM-56/T4 冲突 | AC-07 说市场部成员 404，管理员例外未写；TM-56 未列例外                                                                       | Product/Architect |
| F-015 | M03-AC-06 已被 M05 改变，但无 supersession                       | M03-AC-06 仍要求“全租户共享、不出现文档级权限声明”；`E2E-M03-02` 仍断言旧文案                                                | Product/Lead      |

## P2（批准前修或经用户批准转 residual）

| ID    | 问题                                                                      | Owner              |
| ----- | ------------------------------------------------------------------------- | ------------------ |
| F-016 | AC-01 不支持格式仍写“详情显示”，应在 upsert/上传阶段拒绝                  | Product            |
| F-017 | AC-08 创建人隐式可见与“王敏失去访问/只有名单内可见”冲突                   | Product            |
| F-018 | AC-10 “填写端点/同步周期/bucket 摘要/导出”与设计冲突                      | Product            |
| F-019 | `IT-QUOTA-106` 未定义/未注册，AC-18 用 JUnit IT ID 表示前端逻辑           | Architect/Verifier |
| F-020 | AC-21 策略变更不回溯、只处理 INDEXED、E2E 无法自然等 180 天               | Product/Architect  |
| F-021 | AC-19 “禁用”状态未定义；M05/M05b 页面测试拆分与 stub 未计划               | Product/Lead       |
| F-022 | 套件完整性计数仍 30/10，`TEN-034..037`/`DEPT-011..014` 未自动核对零 skip  | Architect/Verifier |
| F-023 | ADR §11.1 “未列出的 AC 无需改措辞”不成立（AC-08/09/10/11/20）             | Architect          |
| F-024 | 补充测试未映射到 AC；TM-55..71 未合入 THREAT-MODEL；`IT-KB-117` 占位      | Architect/Lead     |
| F-025 | 预算 98h vs 116h；V1+V2 18h 对 22 AC + T4 证据偏薄                        | Lead/Product       |
| F-026 | AC-17/18 属 M04 范围增量，合同 M05 路线图未列                             | Lead/Product       |
| F-027 | 连接器凭据 30 天置空无 owner；删除连接器时文档处置无 API；凭据轮换未进 AC | Architect          |
| F-028 | ACL 灰度 `ENFORCEMENT=false` 时创建 DEPT/USERS 文档会短暂全租户可见       | Architect/Ops      |

## P3

| ID    | 问题                                                                                                                | Owner             |
| ----- | ------------------------------------------------------------------------------------------------------------------- | ----------------- |
| F-029 | 文案/线索清理：meta-ID、`externalId` 脱敏、单独重试措辞、OCR“服务凭据”、AC-02 示例与行带不一致、`sync.retried` 缺失 | Product/Architect |
| F-030 | `IT-KB-117` 是未来占位；`IT-KB-115` 缺号                                                                            | Architect         |

## 必须修改清单（批准前）

1. F-001/F-002/F-015：Product 明确 AC-02/03 选 P6=A/B、AC-21 选 P8=A/B，并显式记录 M03-AC-06 由 M05 取代。
2. F-004：统一 403/404 判定表，修 AC-06/09/10、`SEC-KB-002`，AC-09 增加 `SEC-CONN-004` 连接器跨租户。
3. F-003：补 `knowledge.document.deleted` 的 `reason` 白名单与 retention 审计用例。
4. F-005：把 delete/reindex 端点实现列入 B3/新任务，更新 ADR 现状与预算。
5. F-006：重切 M05/M05b 契约、B2/B6/B8 写范围；AC-22 按分段写。
6. F-007/F-008：修 tombstone 唯一键；增加“完整 list 才传播删除” guard 与负向用例。
7. F-009：补同步 start/finish 数据模型。
8. F-010/F-011：建可执行注入 harness；建 OCR 测试 profile + 零出网 OCR harness。
9. F-012：检索 p95 定口径/预算，补 M05 perf 场景与 owner/写范围。
10. F-013：下载方案二选一，删除“预签名 + 不返回 key”矛盾。
11. F-014：用户对 U8 做显式安全模型决策，并在 AC/TM-56 写例外。
12. F-016..F-023：AC-01/08/10/18/19/21 文案与测试 ID、套件计数、§11.1 补全。
13. F-025/F-026：预算与 AC-17/18 范围由用户决策。
14. F-024/F-027/F-028：威胁模型合入、连接器凭据/删除文档处置、ACL 灰度 WARN。

## T4 风险结论

- 安全：403/404 未闭合（含平台管理员/连接器配置跨租户）与 U8 管理员全读不审计；不修不能声称 ACL 是 T4 安全边界。
- 性能：AC-20 的检索 p95 无预算依据，perf harness 仍 M04 专用；50 并发 + OCR/索引积压证据不可复现。
- 迁移/回滚：V12 与索引 v2 可测；ACL 开启后只能前向修复，灰度 `ENFORCEMENT=false` 会 fail-open；M05 无物理清理，retention 合规依赖未排期的 M09。
- 证据：OCR 零出网、提示注入、越权零 skip、真实栈 E2E 的 harness 都缺 owner/写范围；V1+V2 18h 对 22 条 AC + T4 证据偏薄。
