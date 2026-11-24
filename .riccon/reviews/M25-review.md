---
review_id: REV-M25-001
module_id: M25
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_df2eeb840ffeTSwdddbYBAPXQ3
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
review_status: final
head_commit: bf7260d12bdf52cebbff91c37daf239c04f56df6
baseline_commit: c3c8d2c
reviewed_commits:
  - c3c8d2c
  - 3928aee
  - 68e3f03
  - ece49eb
  - 572ed85
  - f31e180
  - 9012edb
  - f745dc6
  - d87c8cc
  - e30c1b9
  - 38852d7
  - 275e14a
  - ad40633
  - 1e833ba
  - 1bde396
  - a96753d
  - 381d7d1
  - 7ac5d3c
  - 7081487
  - 8d06bd4
  - 8e65489
  - 5b4fede
  - 993f7fe
  - fd6a5f0
  - 02724e2
  - 58f5a2b
  - 2a946ea
  - fa5129d
  - 1e01aa5
  - 7378421
  - 40650a6
  - 3191df5
  - 1e11660
  - c446f1e
  - b5e79c1
  - bf7260d
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
    - EV-1007
    - EV-1012
    - EV-1006
    - EV-1002
  build:
    - EV-1011
  tests:
    - EV-1012
  coverage:
    - EV-1012
  e2e:
    - EV-1007
  docs:
    - EV-1014
  evidence:
    - EV-1011
    - EV-1012
    - EV-1007
    - EV-1006
    - EV-1014
    - EV-1002
    - EV-0964
    - EV-0982
    - EV-0997
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "M09/release 前（DEC-0043=A 明确跟踪到 M09/发布）"
    title: "AC-20 M05b 整条复验中 ACL 写 p95 未量测（DEC-0043=A 接受 residual）"
    accepted_reason: >-
      EV-1002 的连接器场景下同步 40.1s、同步队列 p95 48.8s、检索 p95 37.5ms、首字估算
      85.5ms、API p95 197.3ms 均达标；唯一未量测项是 ACL 写（PERF_M05_ACL_WRITES=0，
      aclWithinBudget=null）。ACL 写是租户管理员低频操作、不在聊天热路径，历史 4.3–4.6s 的
      根因是 QuotaJdbc 租户行锁。DEC-0043=A 已由用户再次书面接受，本次不新增修复预算，也不
      声称 ACL 写 ≤500ms。
    evidence: "EV-1002；DEC-0043；REV-TASK-0209-001 F-001；docs/ACCEPTANCE.md R-M25-02"
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-perf"
    due: "M25 用户验收前（原 TASK-0209/0213 due=M25 gate 前，未修；Lead 已列为 accepted）"
    title: "perf 报告 reproduceCommand/env 未反映 PERF_M05_ACL_WRITES=0（0209/0213 F-001）"
    accepted_reason: >-
      EV-1002 的 command 字段本身带 PERF_M05_ACL_WRITES=0，且矩阵 passed；缺陷只在报告内
      reproduceCommand 与 real-stack-env.json 仍写 aclWriteVus=1。照抄报告内复现命令会启动
      已接受的 ACL 写场景并触发 500ms 阈值，不能复现本次 passed 矩阵。该缺陷不改变连接器
      同步/队列/检索/首字/API 的测量结论，但属于可复现性 P2；scripts/real-stack 不在本模块
      修复范围，必须在用户验收前修复或由 Lead 书面接受，不得声称报告可直接复现。
    evidence: "scripts/real-stack/run.mjs:850,860-877；EV-1002；REV-TASK-0213-001 F-001"
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/ops"
    due: "M25 用户验收前 / M09 前（生产调度归 M09）"
    title: "O2b RUNBOOK 误删恢复/维护账号最小权限未修订（0211 F-001/F-002）"
    accepted_reason: >-
      RUNBOOK 仍写“误置空且源端凭据仍在客户密钥库，只能由管理员在管理端重新录入”，但现有 API
      对已软删除连接器不可见、不可写凭据，也没有 restore/undelete；同节仍未列
      blank-connector-credentials.mjs --apply 的最小 DB 权限（SELECT/UPDATE kb_connector、
      INSERT audit_event、CREATE TEMPORARY TABLES）。脚本行为已由 TASK-0211 的 51 项真实
      MySQL drill、self-test 与 dry-run 验证且 fail-closed，缺陷只在 RUNBOOK 可执行性；
      生产调度与首次 apply 归 M09，不阻塞 M25 用户可见结果。
    evidence: "docs/RUNBOOK.md:1262,1287,1337-1338；REV-TASK-0211-001 F-001/F-002；REV-TASK-0213-001 F-002"
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/builder-kb-connector"
    due: "M25 用户验收前 / M05+ 下载路径维护"
    title: "连接器文档平台对象缺失返回 RICCON-KB-502 而非 404（0214 F-001）"
    accepted_reason: >-
      文档对调用者可见、ACL 检查通过后，平台对象缺失返回 RICCON-KB-502，与上传路径的
      KnowledgeDownloadFailedException 一致；不是 ACL 绕过或存在性泄露（不可见/跨租户仍
      统一 404），但未满足 TASK-0214 AC 字面的“无源对象返回 RICCON-KB-404”。风险有界，
      Product 已登记 R-M25-06，不声称字面 404 已实现。
    evidence: "apps/api/src/main/java/com/riccon/api/knowledge/service/KnowledgeDownloadService.java:147-158；REV-TASK-0214-001 F-001"
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "M25 用户验收后 / 后续"
    title: "J2 审批前检索阶段 sources 无 source_version（0207 F-001）"
    accepted_reason: >-
      J2 产物生成前的检索阶段 sources 没有 source_version，因此不会显示“源内容已更新”；
      产物生成后与 J1 同判，ACL/删除遮蔽不受影响。该残余只影响审批前时间线的文案提示，
      不产生重复/悬空引用，归后续统一引用快照处理。
    evidence: "REV-TASK-0207-001 F-001；docs/ACCEPTANCE.md R-M25-05"
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-connector + riccon/verifier"
    due: "M25 用户验收前 / 下次审计维护"
    title: "连接器审计边界两条子句缺直接测试：审计导出无明文、配置/凭据审计失败回滚"
    accepted_reason: >-
      AC-10 的“审计导出无明文”没有连接器专项扫描用例；AC-16 的连接器配置/状态变更/凭据
      轮换“审计失败使操作失败”也没有 API 级故障注入用例。凭据明文从不进入审计 details：
      AuditDetailsPolicy 对 knowledge.connector.credential.changed 只允许 connectorId/type/
      credentialKeyId/maskedHint，未列键 fail-closed，M10 导出是通用路径；ConnectorService
      的 create/update/putCredential/delete 均为 @Transactional，AuditService.append 加入
      同一事务，Worker 的同步完成/删除传播已有 audit-fail 回滚测试。故这是测试覆盖残余，
      不是已观察的泄露或回滚失败。
    evidence: "apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:253；apps/api/src/main/java/com/riccon/api/audit/service/AuditService.java:35；apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:152-206,272-292；REV-TASK-0213-001；docs/ACCEPTANCE.md R-M25-11"
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-index + riccon/builder-kb-web"
    due: "后续"
    title: "连接器 txt/md 详情 parseEngine=NONE 显示“未进入解析”（0205 F-002）"
    accepted_reason: >-
      连接器 txt/md 已生成文档、分块并可检索，parse_engine=NONE 只让知识库详情显示“未进入
      解析”，属状态文案误导；不影响检索、ACL、删除或引用。归后续统一来源状态文案。
    evidence: "apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:626；REV-TASK-0205-001 F-002；docs/ACCEPTANCE.md R-M25-07"
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-index + riccon/ops"
    due: "后续"
    title: "连接器纯文本无独立字符/分块上限（0205 F-001）"
    accepted_reason: >-
      连接器 txt/md 没有独立字符/分块上限，极端大文本会生成数万分块与超大 bulk 请求；
      当前受 100MB 对象上限与部署容量约束，属于资源公平性残余，不影响 AC-11/12 的用户可见
      正确性（计数、幂等、无重复引用均由集成测试覆盖）。
    evidence: "REV-TASK-0205-001 F-001；docs/ACCEPTANCE.md R-M25-08"
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/ops + riccon/builder-kb-connector"
    due: "M09/后续"
    title: "连接器关闭开关只读/软删除语义与 verifyRedirectTarget HTTPS 纵深防御（0213 F-003/F-004）"
    accepted_reason: >-
      RICCON_CONNECTOR_ENABLED=false 时只读列表与管理员显式软删除仍可用，均需 TENANT_ADMIN，
      不触发新同步或出网；RUNBOOK 需明确关闭语义。verifyRedirectTarget 未校验 HTTPS/allowHttp，
      但当前无生产调用，S3 客户端不跟随重定向已由真实 302 第二主机零请求测试证明；保留为
      纵深防御遗留。
    evidence: "apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:108-147,304-327；apps/worker/src/main/java/com/riccon/worker/connector/domain/ConnectorEndpointPolicy.java:220-252；REV-TASK-0213-001 F-003/F-004"
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M09 前 / M25 用户验收后"
    title: "ADR-0023 仍 proposed / 无 M25 独立架构评审（继承 M05 F-010）"
    accepted_reason: >-
      ADR-0023 状态仍为 proposed，ARCHITECTURE/PRODUCT 表记录 M25 Architect 可行性 pending、
      无 M25 独立架构评审。实现按已答复的 DEC-0034/0041/0042 与 ADR-0023 M05b 段落地，运行时代码、
      契约与安全矩阵均通过；这是治理/文档漂移，继承 M05 F-010，不影响本轮行为结论，但不得
      声称 ADR 已批准或架构评审已完成。
    evidence: "docs/adr/ADR-0023-m05-knowledge-depth.md:3；docs/ARCHITECTURE.md:32；docs/PRODUCT.md 模块状态表 M25 行；REV-M05-002 F-010"
  - id: F-011
    severity: P3
    status: accepted
    owner: "riccon/verifier + riccon/ops"
    due: "M25 用户验收前 / release 前"
    title: "security/Trivy 扫描未录为 M25 EV（仅 0213 评审叙述与临时日志）"
    accepted_reason: >-
      REV-TASK-0213-001 的 Trivy 结论为 exit 0、0 漏洞/0 密钥/0 错配（仅 ADR-0011 两条既有
      抑制），临时日志 final-trivy-40650a6.log 可复核；但 M25 没有 security EV，模块 DoD 也不
      包含 security。contracts.commands.security 是 release DoD 项，发布前必须重跑并录 EV；
      本次不把临时日志当模块 gate 通过证据。
    evidence: "REV-TASK-0213-001 §验证；/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/riccon-m25-v2b-logs/final-trivy-40650a6.log"
  - id: F-012
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-connector"
    due: "M25 用户验收前 / 后续"
    title: "PAUSED 连接器 scheduleEnabled=true 仍返回 nextSyncAt（0204 F-012）"
    accepted_reason: >-
      PAUSED 连接器 scheduleEnabled=true 时响应仍返回非空 nextSyncAt；Worker findDue 明确要求
      status='ACTIVE'，不会误调度。缺陷只影响响应可读性，建议 gate 前统一文案或补测试，
      不影响同步安全与用户可见结果。
    evidence: "apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:176,394-402；apps/worker/src/main/java/com/riccon/worker/connector/persistence/ConnectorJdbc.java:195-215；REV-TASK-0204-001 F-012"
reviewed_task_reviews:
  - REV-TASK-0203-001
  - REV-TASK-0204-001
  - REV-TASK-0205-001
  - REV-TASK-0206-001
  - REV-TASK-0207-001
  - REV-TASK-0208-001
  - REV-TASK-0209-001
  - REV-TASK-0210-001
  - REV-TASK-0211-001
  - REV-TASK-0213-001
  - REV-TASK-0214-001
  - REV-TASK-0215-002
product_preacceptance_status: pass
product_preacceptance_note: >-
  Product 已在 docs/ACCEPTANCE.md「M25 知识库连接器 S3/OSS（Product 预验收）」记录 pass
  （with residual R-M25-01..11），2026-11-24；.riccon/modules/M25.json 的 product_status
  仍为 pending，需 Lead 在模块 done 前按流程记录 product=pass。本字段只引用 Product 预验收，
  不替代用户最终验收。
evidence:
  - EV-0961
  - EV-0962
  - EV-0963
  - EV-0964
  - EV-0965
  - EV-0966
  - EV-0967
  - EV-0968
  - EV-0969
  - EV-0970
  - EV-0971
  - EV-0972
  - EV-0973
  - EV-0974
  - EV-0975
  - EV-0976
  - EV-0977
  - EV-0978
  - EV-0979
  - EV-0980
  - EV-0981
  - EV-0982
  - EV-0983
  - EV-0984
  - EV-0986
  - EV-0987
  - EV-0988
  - EV-0989
  - EV-0990
  - EV-0991
  - EV-0992
  - EV-0993
  - EV-0994
  - EV-0995
  - EV-0996
  - EV-0997
  - EV-0998
  - EV-0999
  - EV-1002
  - EV-1003
  - EV-1004
  - EV-1005
  - EV-1006
  - EV-1007
  - EV-1008
  - EV-1011
  - EV-1012
  - EV-1013
  - EV-1014
reviewed_evidence:
  - EV-0961
  - EV-0962
  - EV-0963
  - EV-0964
  - EV-0965
  - EV-0966
  - EV-0967
  - EV-0968
  - EV-0969
  - EV-0970
  - EV-0971
  - EV-0972
  - EV-0973
  - EV-0974
  - EV-0975
  - EV-0976
  - EV-0977
  - EV-0978
  - EV-0979
  - EV-0980
  - EV-0981
  - EV-0982
  - EV-0984
  - EV-0986
  - EV-0987
  - EV-0988
  - EV-0989
  - EV-0990
  - EV-0991
  - EV-0992
  - EV-0993
  - EV-0994
  - EV-0995
  - EV-0996
  - EV-0997
  - EV-0998
  - EV-0999
  - EV-1002
  - EV-1003
  - EV-1004
  - EV-1005
  - EV-1006
  - EV-1007
  - EV-1008
  - EV-1011
  - EV-1012
  - EV-1013
  - EV-1014
created_at: "2026-11-24T18:52:54+08:00"
notes: >-
  REV-M25-001 技术定稿：M25 14/14 任务 done；合同 build/test+coverage/E2E/a11y、1000 对象
  perf、docs/contract validate 与 O2a 零出网/SSRf 矩阵均可复核；EV commit/dirty/sha256 与
  HEAD 祖先关系一致。技术 verdict pass；无 P0/P1。ACL 写 residual 由 DEC-0043=A 接受，
  perf 报告元数据与 O2b RUNBOOK 两条 P2 accepted（原 due M25 gate 前已逾期，Lead 需在用户
  验收前修复或继续书面接受）。Product 预验收已 pass（with residual R-M25-01..11），但
  .riccon 的 product_status 仍 pending，需 Lead 记录；用户最终验收 pending。本评审是独立
  技术验收，不替代 Product 预验收或用户最终验收。EV-0983/EV-1009/EV-1010 为探索性失败，
  保留但不作通过证据；TASK-0212/0216 无独立任务级 review，分别由本模块评审直接复核
  EV-1003..1007 与 EV-1013/EV-1012。
---

# REV-M25-001：M25（M05b S3 连接器）模块独立评审

> **Product 预验收：pass（with residual R-M25-01..11，见 `docs/ACCEPTANCE.md` 2026-11-24 M25 段）。**
> 本文件是**独立技术验收**，verdict 为 pass；用户最终验收仍为 pending。本评审不替代 Product
> 预验收或用户最终验收；`ACL 写 p95` 与 perf 复现元数据、O2b RUNBOOK 等残余不得在发布材料中
> 声称已解决。

## 1. 结论

**技术 verdict：pass。** M25（M05b S3/OSS 连接器）14/14 任务 done，所有 M05b 用户可见 AC
段（AC-09/10..14、AC-15/16/21 连接器段、AC-19/20/22 整条、AC-07 下载段）均有可复核证据；
未发现 P0/P1。保留 12 条 P2/P3 accepted 残余，均写 owner/due/accepted_reason；其中
ACL 写 p95 未量测由用户 DEC-0043=A 再次书面接受，perf 报告复现元数据与 O2b RUNBOOK 两条
P2 原 due 为 M25 gate 前，当前仍未修复，必须在用户验收前处理或继续书面接受。

关键结论：

- **连接器主链路成立**：端点 allowlist/SSRF 拒绝、凭据只写+掩码+信封加密、首次/增量/删除/
  重现/部分 list 同步、tombstone 复用、item 级失败与 `sync.retried`、连接器文档索引/引用/
  下载/保留、注入 harness、关闭开关回滚、O2a 零出网与 O2b 30 天置空脚本均有集成/安全/E2E
  证据。
- **合同全量通过**：EV-1012 在最终代码树 `c446f1e` 上 exit 0；API **821**、Worker **688**、
  Web **650**，0 failure/0 error/0 skip；覆盖率 API **91.50/71.57**、Worker **86.81/71.98**、
  Web **87.02/80.26**，均过合同 80/70 门槛。
- **E2E/a11y 通过**：EV-1007 合同 E2E **63/63**（M05b 11 条 = 7 功能 + 4 `@a11y`）；
  EV-1006 `pnpm -w a11y` **22/22**，含连接器列表/表单/条目抽屉/删除确认 4 条 axe。
- **性能通过**：EV-1002 1000 对象连接器同步 40.1s（预算 900s）、队列 p95 48.8s（预算 300s）、
  检索 p95 37.5ms（≤500ms）、首字估算 85.5ms（≤3s）、API p95 197.3ms（≤500ms）；ACL 写按
  DEC-0043=A 未执行（F-001）。
- **契约真实**：`contracts/openapi.yaml` 1.5.0-m05b 的 8 个连接器端点由
  `ConnectorContractM05bTest` 11/11 和真实 API 测试覆盖，无 deferred/404 冒充。

## 2. 基线与证据完整性

- 模块基线 `c3c8d2c`（M05 done/accepted 后的 handoff），最终 HEAD `bf7260d`；包含代码的最终
  树为 `c446f1e`，`bf7260d` 只追加 `.riccon` 证据/状态。`git diff --check` exit 0；
  `riccon state validate` exit 0（仅历史 legacy review warnings，0 errors）；
  `contract validate --strict` **0 errors / 0 warnings**。
- 工作树在评审开始时只有 `.riccon/modules/M25.json`、`.riccon/state.json` 的 Lead 状态变更，
  以及 Product 正在写入的 `docs/ACCEPTANCE.md`/`docs/PRODUCT.md`；所有被引用的 EV 均为
  `dirty=false` 且绑定 commit（见下表）。
- EV JSON 的 `output_sha256` 与实际日志逐条复核一致，`output_bytes` 一致；EV-1011/1012/1007/
  1006/1014/1002 的 commit 均为 HEAD 祖先。
- 证据范围说明：EV-1011/EV-1012 在 `c446f1e`（最终代码树）；EV-1007 E2E 在 `7378421`，
  EV-1006 a11y 在 `fa5129d`，EV-1002 perf 在 `5b4fede`，EV-1014 docs 在 `b5e79c1`。之后的提交
  只新增测试（V2b 安全矩阵、worker 分支覆盖）或 `.riccon` 证据，**没有生产源码/契约/迁移/
  deploy 变更**：`git diff --stat fa5129d..HEAD -- apps/web/src apps/web/config apps/api/src/main
apps/worker/src/main contracts deploy platform` 为空；`git diff --stat 7378421..HEAD` 只含
  `apps/**/test/**` 测试文件；`git diff --stat c446f1e..HEAD` 只含 `.riccon` 证据；`b5e79c1..HEAD`
  无 docs 变更。故这些 EV 对最终代码树有效。

## 3. 全量证据

| 项                 | 结果                                                                                                                                              | EV / 证据                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| 合同 build         | exit 0；`./mvnw -q -T1C -DskipTests package && pnpm -w build`；`c446f1e`、dirty=false                                                             | **EV-1011**                                 |
| 合同 test+coverage | exit 0；API **821**、Worker **688**、Web **650**；0/0/0；API **91.50/71.57**、Worker **86.81/71.98**、Web **87.02/80.26**；`c446f1e`、dirty=false | **EV-1012**                                 |
| 合同 E2E           | exit 0；**63 passed**（7.5m）；M05b 11 条（含 4 a11y）                                                                                            | **EV-1007**（`7378421`）                    |
| a11y               | exit 0；**22 passed**（3.0m）；连接器 4 条 axe                                                                                                    | **EV-1006**（`fa5129d`）                    |
| 1000 对象 perf     | exit 0；matrix `status=passed`；sync 40.1s、queue p95 48.8s、retrieval p95 37.5ms、first-token 85.5ms、API p95 197.3ms；ACL 写未执行              | **EV-1002**（`5b4fede`）                    |
| docs/contract      | exit 0；`git diff --check && grep M05b RUNBOOK/PRODUCT && contract validate --strict`                                                             | **EV-1014**（`b5e79c1`）                    |
| O2a 零出网         | exit 0；PASS：无默认路由、外部地址拒绝、元数据地址拒绝；down 后网络已删除                                                                         | **EV-0961/EV-0964**                         |
| O2b 脚本           | exit 0；`--self-test` / `--dry-run`；脚本 fail-closed                                                                                             | **EV-0997**                                 |
| V2b 定向安全/越权  | exit 0；API 48/48、Worker 85/85，0/0/0；V12 迁移 13/13、schema 9/9                                                                                | `REV-TASK-0213-001` §验证（临时日志）       |
| security/Trivy     | exit 0；0 vuln/0 secret/0 misconfig（2 条 ADR-0011 既有抑制）                                                                                     | `final-trivy-40650a6.log`（未录 EV，F-011） |

覆盖率说明：Worker 覆盖率由 TASK-0216 的 8 个连接器分支测试补到 **71.98%**，较 M05 的
70.01% 有约 2pp 缓冲；EV-1013 在 `c446f1e` 定向重跑这 8 类 **36/36**，EV-1012 全量已包含
它们。surefire 报告显示 API 162 个 XML、821 tests；Worker 126 个有效类、688 tests；
Web 74 files/650 tests，均 0 failure/error/skip。

## 4. AC 对账（M05b 用户可见段）

模块验收文本写“AC-09/10..14/19/20/22 连接器段”；按 `docs/PRODUCT.md` §11 的 M05b 最小 P0
闭包，本评审同时核对 AC-15/16/21 连接器段与 DEC-0041 的连接器原文下载段。

| AC                        | 结果                                     | 用户可见结果 / 证据                                                                                                                                                                                                                                                               | 残余                                                                                                 |
| ------------------------- | ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| M05-AC-09（M05b 段）      | **pass**                                 | E2E-M05-04b：员工 403、平台 404、跨租户 404、CSRF 拒绝；`ConnectorTenantIsolationVerifyTest` 3 用例（TEN-037、SEC-CONN-004、8 端点 403）零 skip；EV-1007/EV-1012                                                                                                                  | 无                                                                                                   |
| M05-AC-10                 | **pass（with residual R-M25-01/F-006）** | E2E-M05-04/04c：endpointId 文本输入 + 后端 allowlist 400、默认可见范围必选、DEPT 必选部门、凭据只写/掩码/轮换、连接测试中文分类、无 per-connector cron、平台 404/员工 403；`ConnectorCrudApiTest`/`ConnectorCredentialSyncApiTest` 与 V2b 矩阵；契约 1.5.0-m05b                   | endpointId 文本输入是 DEC-0042=A 的已批准形态（R-M25-01）；审计导出无连接器专项明文扫描（F-006，P3） |
| M05-AC-11                 | **pass**                                 | `ConnectorSyncIntegrationTest`：首次同步创建文档/跳过不支持格式、重复首次同步无重复、部分/截断 list 不传播删除、失败也有结束时间；E2E-M05-05 起止/计数/list 完整                                                                                                                  | 无                                                                                                   |
| M05-AC-12                 | **pass（with residual R-M25-05）**       | incrementalAdd/Change、sourceDeletion、sourceReappears、repeatSync；E2E-M05-04f “源内容已更新”、E2E-M05-05b 删除传播、M05-08 已删除引用                                                                                                                                           | J2 审批前检索阶段 sources 无 source_version（F-005，P3）                                             |
| M05-AC-13                 | **pass**                                 | authFailure（`sync.failed` + `sync.retried` 审计、`:retry:` 幂等键）、missingCredential、objectTooLarge 部分成功；E2E-M05-05 失败原因/上次成功/重试                                                                                                                               | 未对客户真实网络中断/代理演练                                                                        |
| M05-AC-14                 | **pass**                                 | `InjectionHarnessTest` 25、`InjCatalogTest` 6、`ConnectorCitationInjectionTest` 3：canary/内网地址不进入模型请求/工具结果/回答/日志/审计；E2E-M05-04 页面/DOM/响应/URL 无凭据；externalId 只显示末段                                                                              | harness 为测试环境，不声称生产绝对免疫；TASK-0208 F-001/F-002 P3 accepted                            |
| M05-AC-15（连接器段）     | **pass（继承 R-M05-02）**                | E2E-M05-05/05b；`ConnectorSyncCompletionIntegrationTest`、`ConnectorDeletePropagationIntegrationTest`、`ConnectorDisabledRollbackVerifyTest`；同步失败原因/结束时间/重试，删除幂等，关闭开关后写操作 503、历史文档仍可读                                                          | `force=false` 仍整本重解析（M05 R-M05-02，继承）                                                     |
| M05-AC-16（连接器段）     | **pass（with residual F-006）**          | `ConnectorAuditPolicyTest`、`ConnectorSyncIntegrationTest` 的 auditCount、`ConnectorCrudApiTest` 的 created/updated/status/deleted、`ConnectorCredentialSyncApiTest` 的 credential.changed、TEN-037 的 admin_read；审计 details 白名单不含凭据/externalId；普通员工无管理审计入口 | 配置/凭据审计失败回滚无直接测试（F-006，P3）                                                         |
| M05-AC-19（连接器页）     | **pass**                                 | E2E-M05-04c 七态（加载/空/错误/禁用 503/无权限 403/404/成功）；E2E-M05-07e 375px + 键盘“打开连接器→测试→保存/编辑→保存” + Drawer 焦点陷阱/Escape 归还；E2E-M05-07f-1..4 axe 无 serious/critical；EV-1006 22/22                                                                    | 未逐态跑 axe；仅 Chromium 375×812                                                                    |
| M05-AC-20（整条）         | **pass（with residual F-001/F-002）**    | EV-1002：1000 对象 sync 40.1s、queue p95 48.8s、retrieval p95 37.5ms、首字 85.5ms、API 197.3ms；1000/1000 INDEXED、无重复；V2b 独立复跑 sync 33.2s/queue 45.4s/retrieval 33.6ms/first-token 76.6ms/API 169.7ms                                                                    | ACL 写 p95 未执行（DEC-0043=A，F-001）；报告复现元数据缺 `PERF_M05_ACL_WRITES=0`（F-002，P2）        |
| M05-AC-21（连接器文档段） | **pass（M09 物理清理 residual）**        | `KnowledgeRetentionTest#retention_coversConnectorSourceDocuments`：到期软删除、列表/详情不可见、`reason=RETENTION`、索引 DELETE；E2E-M05-05b 删除传播；M05 E2E-M05-08/09 继承                                                                                                     | 物理对象/分块清理与 30 天凭据置空调度归 M09（release blocker）；未在客户数据量演练                   |
| M05-AC-22（整条）         | **pass**                                 | EV-1007 63/63（J1/J2/M03/M10/M04/M05/M05b 回归）、EV-1006 22/22、EV-1012 821/688/650、EV-1014；`ConnectorContractM05bTest` 11/11，8 端点真实实现非 deferred；知识库无过期“全租户共享”声明                                                                                         | 真实栈 M05b 页面未单列；生产外部端点未接                                                             |
| M05-AC-07（连接器下载段） | **pass（with residual F-004）**          | TASK-0214/EV-0984 `ConnectorDownloadApiTest`：可见/创建人/跨租户/无 key/流式；`ConnectorKnowledgeIndexIntegrationTest` 12；E2E-M05 下载/引用回归                                                                                                                                  | 可见但平台对象缺失返回 KB-502（F-004，P2）                                                           |

## 5. 任务评审覆盖

本模块 14 个任务中，12 个有任务级评审（均 pass）；B2b/B6/B7b/B8b/B9b/B10b/B11b/B12b/
O2a/O2b/V2b 高风险任务全覆盖。以下为每个已有 task review 的最新 `review_id`：

| 任务      | 最新评审            | 结论 | 与本模块评审的对账                                                                                                                 |
| --------- | ------------------- | ---- | ---------------------------------------------------------------------------------------------------------------------------------- |
| TASK-0203 | `REV-TASK-0203-001` | pass | F-001..F-004 fixed，F-005 P3 accepted；契约 1.5.0-m05b 由本模块 EV-1012 复核                                                       |
| TASK-0204 | `REV-TASK-0204-001` | pass | 6 条 P1/P2 fixed（双桶、8 端点 authz、删除传播事务、索引补投、409、同步中止）；F-008 下载转 TASK-0214 已交付；F-011/F-012 accepted |
| TASK-0205 | `REV-TASK-0205-001` | pass | F-001/F-002 P3 accepted → F-008/F-007                                                                                              |
| TASK-0206 | `REV-TASK-0206-001` | pass | F-001 accepted 由 DEC-0042=A 澄清为 endpointId 文本输入；F-002/F-003 由 V1b TASK-0212/0215 承接                                    |
| TASK-0207 | `REV-TASK-0207-001` | pass | F-001 P3 accepted → F-005；F-002/F-003/F-004 accepted                                                                              |
| TASK-0208 | `REV-TASK-0208-001` | pass | F-001/F-002 P3 accepted；harness 由 EV-1012 全量复核                                                                               |
| TASK-0209 | `REV-TASK-0209-001` | pass | F-001 P2 accepted → F-002                                                                                                          |
| TASK-0210 | `REV-TASK-0210-001` | pass | F-001/F-002 fixed，F-003 accepted；EV-0964 含 down 清理断言                                                                        |
| TASK-0211 | `REV-TASK-0211-001` | pass | F-001/F-002 P2 accepted → F-003；F-003/F-004 accepted                                                                              |
| TASK-0213 | `REV-TASK-0213-001` | pass | F-001 → F-002；F-002 → F-003；F-003/F-004 → F-009；V2b 133/133、V12 13/13、Trivy 0/0/0                                             |
| TASK-0214 | `REV-TASK-0214-001` | pass | F-001 P2 accepted → F-004；F-002..F-006 accepted                                                                                   |
| TASK-0215 | `REV-TASK-0215-002` | pass | F-001/F-002 P1 fixed、F-003/F-004 P2 fixed、F-005 P3 fixed；最终 E2E/a11y 由 EV-1007/EV-1006 复核                                  |

没有独立任务级 review 的任务：

- **TASK-0212（V1b 功能/E2E/a11y）**：本身是 verifier 任务，产出 EV-1003..EV-1007；其最终
  E2E/a11y 与 TASK-0215 的 F-001..F-005 由本模块评审、`REV-TASK-0215-002` 与 EV-1007/1006
  直接复核。
- **TASK-0216（worker 分支覆盖修复）**：测试性补测，非高风险；产出 EV-1013（8 类 36/36）与
  EV-1012 的全量覆盖率，本模块评审已复核。
- V1b 过程中 TASK-0215 修复了 B8b 的 2 条 P1（danger 按钮对比度、条目抽屉滚动区键盘访问）
  和 2 条 P2（375px 固定列、Drawer 焦点陷阱），均已在 `7378421`/`fa5129d` 的最终 E2E/a11y
  中通过。

## 6. Findings

| ID    | 级别 | 状态     | 摘要                                                          | Owner / due                                    |
| ----- | ---- | -------- | ------------------------------------------------------------- | ---------------------------------------------- |
| F-001 | P2   | accepted | AC-20 ACL 写 p95 未量测，DEC-0043=A 接受 residual             | Lead+Architect / M09/release 前                |
| F-002 | P2   | accepted | perf 报告 reproduceCommand/env 缺 `PERF_M05_ACL_WRITES=0`     | builder-kb-perf / M25 用户验收前               |
| F-003 | P2   | accepted | O2b RUNBOOK 误删恢复/维护账号最小权限未修订                   | ops / M25 用户验收前、M09 前                   |
| F-004 | P2   | accepted | 连接器文档平台对象缺失 502 而非 404                           | Lead+builder-kb-connector / M25 用户验收前     |
| F-005 | P3   | accepted | J2 审批前 sources 无 source_version                           | Lead+Architect / 后续                          |
| F-006 | P3   | accepted | 审计导出无明文、配置/凭据审计失败回滚缺直接测试               | builder-kb-connector+verifier / M25 用户验收前 |
| F-007 | P3   | accepted | 连接器 txt/md parseEngine=NONE 文案误导                       | builder-kb-index+builder-kb-web / 后续         |
| F-008 | P3   | accepted | 连接器纯文本无独立字符/分块上限                               | builder-kb-index+ops / 后续                    |
| F-009 | P3   | accepted | 关闭开关只读/软删除语义 + verifyRedirectTarget HTTPS 纵深防御 | ops+builder-kb-connector / M09/后续            |
| F-010 | P3   | accepted | ADR-0023 仍 proposed、无 M25 独立架构评审                     | Architect+Lead / M09 前                        |
| F-011 | P3   | accepted | security/Trivy 未录 M25 EV                                    | verifier+ops / M25 用户验收前、release 前      |
| F-012 | P3   | accepted | PAUSED 连接器 scheduleEnabled=true 仍返回 nextSyncAt          | builder-kb-connector / M25 用户验收前          |

没有 `open` finding；没有 P0/P1。F-002/F-003 的原 due 为“M25 gate 前”，当前未修复；本模块
评审将其保留为 accepted 并把跟踪点移到 M25 用户验收前/M09，但**不声称已修复**，Lead 需在
用户验收决策中显式接受或安排修复。F-001 的 ACL 写 residual 由 DEC-0043=A 用户书面接受，
不属于静默放宽。

## 7. 残余与未验证

- **ACL 写性能**：ACL 写 p95 未执行（`aclWithinBudget=null`），不声称 ≤500ms；DEC-0043=A 接受，
  owner Lead+Architect，due M09/release。M05 R-M05-01 继续有效。
- **真实环境**：连接器同步/索引使用 RustFS + MySQL + OpenSearch 测试栈；未接客户真实 S3/OSS、
  未做客户网络/代理中断演练。真实栈 M05b 页面未单列（V1b/V2b 浏览器验证在合同 stub；后端
  集成在真实栈）。不把 stub E2E 当作真实后端同步证据。
- **a11y**：连接器 4 条 axe 覆盖列表/表单/条目抽屉/删除确认；403/404/禁用态未逐态跑 axe；
  仅 Chromium 375×812，未覆盖 Safari/Firefox。无 serious/critical。
- **安全**：新端点 authz/SSRF/凭据/注入矩阵与 O2a 零出网均通过；Trivy 0/0/0 但未录 EV
  （F-011）。T4 威胁模型/滥用案例已在 ADR-0023/TASK-0213 覆盖，不声称生产绝对安全。
- **迁移**：V12 forward/rollback 与 schema 9/9 由 `V12MigrationTest` 13/13 与 EV-1012 覆盖；
  生产 One-shot 迁移、备份/恢复与旧 Worker 回滚窗口归 M09。
- **继承 M05**：R-M05-02（force=false 整本重解析）、R-M05-03（宿主 Tesseract IT 未跑）、
  R-M05-04（V14 生产锁/物理清理 M09）、R-M05-05（M04 F-008 pass-through）继续按 M05 评审
  accepted；M25 未关闭，也不据此声称已解决。
- **证据范围**：EV-1007/1006/1002 不在最终 HEAD，但后续提交只含测试/.riccon（§2 已用
  `git diff` 证明无生产变更）；若后续再改生产代码，必须在新提交重跑合同证据。
- **任务级 review 覆盖**：TASK-0212/0216 无独立任务级 review（均为 verifier/测试性任务），
  已由本模块评审和 EV-1003..1007/EV-1013/EV-1012 直接覆盖；高风险 B2b/B6/B7b/B8b/B9b/B10b/
  B11b/B12b/O2a/O2b/V2b 均有任务级 review。
- **文档/治理**：ADR-0023 仍 proposed，PRODUCT 记录 M25 Architect 可行性 pending；本评审
  不把 DEC-0034/0041/0042/0043 当作 ADR 批准。`docs/ACCEPTANCE.md` M25 段为 Product 预验收
  pass（with residual R-M25-01..11），`.riccon/modules/M25.json` 的 `product_status` 仍为
  pending，需 Lead 在模块 done 前记录。

## 8. Gate 预期与下一步

- 写入本评审后运行 `riccon gate check --module M25`：**PASS，0 errors，2 warnings**。两条 warning
  为历史 manual 证据 EV-0571/EV-0908（属 TASK-0173/M05 遗留，不能建立 pass），不是 M25
  任务证据，也不影响本模块 DoD；`review_evidence`/`dod-evidence`/`review_fresh` 全部通过。
  若后续出现格式/证据绑定失败，修正本评审文件（不是证据或状态）后重跑。
- Lead 需要：
  1. 运行 `riccon gate check --module M25` 并处理任何格式/证据绑定反馈；
  2. 在 gate pass 后按流程记录 `product_status=pass` 与模块 done；用户最终验收仍 pending；
  3. 用户验收前修复或继续书面接受 F-002（perf 复现元数据）与 F-003（O2b RUNBOOK）；
     F-001 已由 DEC-0043=A 接受，F-004/F-005/F-006 等 P2/P3 按各自 owner/due 跟踪；
  4. 发布前重跑 `commands.security` 并录 EV（F-011），处理 DEC-0003 MySQL Connector/J
     许可与 M09 物理清理/凭据置空调度。
- 本评审不修改任务/模块状态，不提交 git；`docs/ACCEPTANCE.md`/`docs/PRODUCT.md` 的 Product
  结论由 Product/Lead 维护。
