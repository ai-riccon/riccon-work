---
review_id: REV-M05-001
module_id: M05
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_df6c1db13ffelhx1bMd8ahz4pO
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
review_status: draft_waiting_fix
product_preacceptance_status: changes-requested
product_preacceptance_findings:
  - R-M05-06
  - R-M05-07
draft_note: >-
  等待 TASK-0202 修复后定稿。Product 预验收当前为 changes-requested（R-M05-06 失败时间缺失 P1、
  R-M05-07 保留策略不追溯界面明示 P2）；TASK-0202 完成、代码（API/Web/E2E）变化后，Lead 通知补评，
  再更新 head_commit/reviewed_commits/reviewed_evidence（含 TASK-0202 的 EV）并重跑 gate。当前工作树
  已有 TASK-0202 的未提交实现（API/Web/E2E/契约），本评审只针对 HEAD eaa0adf，未评 dirty 代码。
head_commit: eaa0adf6359fdf2303f4e45aa5d542527879f82a
reviewed_commits:
  - 8c5781c9048befdc1911fc9738503ea938a44f38
  - c384c3579caf3725f6830d5460211844bf939e71
  - c1bd0e7fb0bdab80904a8e929e64946ce0d22b51
  - f8b6211b841e71db394628cc796177d2c86289de
  - e42ee1625c7a4e0829adad0c840e4759c9e31604
  - f4c43996e8abd2734bc40722df9d6cda7c4397d2
  - 9e73e91
  - cb55b79
  - 6951dc4
  - 0e13e942ff90b5dd33c7064c6ed651a3df503c3f
  - 4f923ff
  - eaa0adf6359fdf2303f4e45aa5d542527879f82a
verdict: fail
dod:
  acceptance: false
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
dod_evidence:
  acceptance:
    - EV-0939
    - EV-0943
    - EV-0951
    - EV-0953
  build:
    - EV-0940
  tests:
    - EV-0939
  coverage:
    - EV-0939
  e2e:
    - EV-0943
  docs:
    - EV-0952
    - EV-0953
  evidence:
    - EV-0939
    - EV-0940
    - EV-0941
    - EV-0942
    - EV-0943
    - EV-0944
    - EV-0946
    - EV-0948
    - EV-0951
    - EV-0952
    - EV-0953
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/builder-kb-index + riccon/builder-kb-web"
    due: "TASK-0202 修复；修复后重跑定向与全量证据、补评模块 review"
    title: "AC-15 要求的“失败时间”在 Worker/API/UI 均缺失，文档详情只显示创建时间和索引完成时间"
    evidence: >-
      docs/PRODUCT.md:565（AC-15 原文“显示可读原因…失败时间与重试入口”）；
      apps/worker/src/main/java/com/riccon/worker/index/repository/KnowledgeRepository.java:177-190（markFailed 只写 status/error_code/error_message，不写失败时间）；
      apps/api/src/main/java/com/riccon/api/knowledge/dto/KnowledgeDocumentResponse.java:43-70（无 failedAt 字段）；
      apps/web/src/pages/knowledge/components/knowledge-detail.tsx:416-425（只显示“创建时间”和“索引完成”，无“失败时间”）；
      e2e/tests/m05.spec.ts:413-480（E2E-M05-05 断言删除/重索引/403/404/409/503，不断言失败时间）；
      工作树中未提交的 Product 预验收草稿 docs/ACCEPTANCE.md（2026-11-24，R-M05-06）同样判定 changes-requested。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/builder-kb-web + riccon/lead"
    due: "M05 gate 前（补界面文案/说明，或由 Lead/用户书面接受该子句不适用）"
    title: "AC-21 要求保留策略变更“不追溯旧文档、界面明示”，当前 UI 无策略变更入口也无“不追溯”说明"
    evidence: >-
      docs/PRODUCT.md:571（AC-21 原文“…不追溯旧文档，仅新文档/新到期计算按新策略，界面明示”）；
      apps/web/src/pages/knowledge/components/knowledge-detail.tsx:426-430（仅显示保留天数/到期/策略来源）；
      `grep -rn "不追溯|策略变更" apps/web/src/pages/knowledge apps/web/src/services/knowledge` 无命中；
      apps/web/src/pages/admin/tenants/index.tsx:112 只显示“审计保留 N 天”，无编辑入口；
      EV-0924 的 KnowledgeRetentionTest 证明后端不追溯行为，但不构成“界面明示”证据；
      工作树中未提交的 Product 预验收草稿 R-M05-07 同样判定 changes-requested。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "用户最终验收前 / 最迟 M05b gate 前（需用户显式接受 ACL 写预算 residual）"
    accepted_reason: >-
      检索 p95 97.516ms < 500ms、首字估算 201.5ms < 3s、API p95 386.8ms < 500ms、索引 p95 7.7s < 60s、
      下载 p95 301ms，用户主路径预算均达标；唯一超标的是 ACL 写 p95 4330.25ms（B11 另测 4576.75ms），
      根因是 QuotaJdbc 租户行锁争用，ACL 写是管理员操作、不在聊天热路径。DEC-0038=A 已选择 MySQL
      FULLTEXT 解决检索，并明确“ACL 租户锁另修”；因此技术验收接受该 residual，但 Product/用户仍须在
      最终验收中显式接受，不能把本 accepted 当成用户已批准预算。
    title: "AC-20 ACL 写 p95 4.33–4.58s > 500ms（quota 租户行锁 residual）"
    evidence: "EV-0947（检索 97.516ms、ACL 写 4330.25ms，exit 1）；EV-0922（73.412ms/4576.75ms）；REV-TASK-0177-001 F-002；Product R-M05-01"
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-index + riccon/architect"
    due: "M05b gate 前"
    accepted_reason: >-
      force=false 当前与 force=true 一样整本重解析，未实现契约“只补缺失页/不重复已成功页”，
      成功页 chunk_id 会重生成；但重试幂等、不产生重复文档或重复引用，引用以快照和 source_version
      解释，M05 用户可见结果（补齐失败页、状态更新、无重复引用）成立。B5 当前没有页级解析入口，
      修复需要页级解析或按 page_no 过滤；归 M05b 处理，AC-04/AC-15 在 M05 评审按 with residual 记录。
    title: "reindex 的 force=false 与 force=true 行为相同：FULL 始终整本重解析，未只补缺失页"
    evidence: "KnowledgeIndexService.java:150-152、177；contracts/openapi.yaml:1194-1203；E2E-M05-02 只断言请求不带 force=true；REV-TASK-0169-001 F-001；Product R-M05-02"
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/verifier + riccon/ops"
    due: "M05b 前或下次真实栈维护"
    accepted_reason: >-
      OCR 原生引擎与零出网由 O1b 镜像 `--network none` 的 OcrCli 证明（EV-0946，engine=tesseract
      5.3.4、avgConfidence=0.922、bbox 存在、无路由、uid 10001）；宿主无 libtesseract，
      TesseractOcrEngineIT 未在宿主运行，真实栈 full 只能以 RICCON_OCR_ENABLED=0 跑通 29/29。
      因此不声称宿主原生 IT 或真实栈 OCR 页面旅程已通过；这是测试环境覆盖缺口，不是生产代码失败。
    title: "宿主 Tesseract 原生 IT 未运行；真实栈 full 29/29 在 OCR 关闭下取得，M05 知识库 UI 未单列真实栈旅程"
    evidence: "EV-0946；EV-0951（RICCON_OCR_ENABLED=0）；EV-0949（宿主缺 libtesseract 失败保留）；REV-TASK-0177-001 F-005/§4；TesseractOcrEngineIT 未执行"
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/ops + riccon/architect"
    due: "M09 迁移演练 / release blocker"
    accepted_reason: >-
      V14 的 forward/rollback 已由 V14MigrationTest 4/4、schema 14、restore-drill（49 表/2 触发器/
      schema 14）验证；但 InnoDB FULLTEXT 的 LOCK=SHARED 写阻塞与实际客户数据量下的迁移时长尚未量测，
      ADR-0023 §5.10 已明确生产锁等待/维护窗口归 M09。M05 只做软删除+索引 DELETE，物理对象/分块清理
      按 P8=A 归 M09 在 30 天内完成，作为 release blocker 跟踪，不在本模块声称已完成。
    title: "V14 FULLTEXT 生产锁/维护窗口未量测；软删除后的物理清理仍归 M09 release blocker"
    evidence: "ADR-0023:441；EV-0948（restore-drill schema 14，partial 未含主密钥/J1）；REV-TASK-0198-001 F-005；Product R-M05-04"
  - id: F-007
    severity: P2
    status: accepted
    owner: "riccon/builder-route"
    due: "M09 前"
    accepted_reason: >-
      M04 F-008 的 API 侧 pass-through 仍在：ChatService 把 Worker failedMessage 原样写入
      run.error_message；当前 Worker 发送的是固定脱敏文案，未见 key/地址/堆栈/用户输入回显，
      M24 已用 canary 锁定负向回显，但 API 侧仍未做白名单/截断/结构化分类。该债务不因 M24 的 P3
      标签降级，M04 评审仍按 P2 accepted；M05 未改变该路径，不构成 M05 回归。
    title: "M04 F-008 生产 pass-through 仍未修复（继承 M24 F-006，P2 accepted）"
    evidence: "apps/api/src/main/java/com/riccon/api/chat/service/ChatService.java:395；REV-M24-001 F-006；Product R-M05-05"
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "M05b / 下次维护（不阻塞 M05 用户可见结果）"
    accepted_reason: >-
      ADR-0023 §3.3 要求单文件 50MB 解析前拒绝 `KB_PARSE_RESOURCE_LIMIT`、文档级 180s 超时
      `KB_PARSE_TIMEOUT`；实际 50MB 超限抛 `RICCON-FILE-413`，180s 超时没有生产写入方，只剩
      outbox 30m/65m 看门狗。上传层已 fail-closed 限 50MB，解析有字符/段落/entry 上限，不会 OOM；
      风险是认证租户可用对抗性工作簿占用解析 lane 较长时间，属可用性/公平性 residual，不是数据泄露。
    title: "解析资源码与 180s 文档超时未按 ADR-0023 §3.3 落地（继承 TASK-0167 F-004）"
    evidence: "docs/adr/ADR-0023-m05-knowledge-depth.md:95、102；S3FileContentReader.java:64-68；WorkerErrorCodes.java:34；REV-TASK-0167-001 F-004"
  - id: F-009
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-index"
    due: "M05b / M09 前"
    accepted_reason: >-
      重灌脚本在计数不一致/目标索引缺失时 fail-closed，不会把错误状态切到别名；小租户能完整比较
      分块 ID。10k–50k 分块会因 OpenSearch `max_result_window=10000` 失败，>50k 只比计数且未比较
      文档数/document_id，因此 IT-KB-113 的“全量 ID 校验”不能在大租户上宣称通过。M05 的用户检索
      走 MySQL FULLTEXT，不依赖该脚本验证；归 M05b/M09 迁移工具加固。
    title: "重灌脚本大租户 ID 校验缺口：10k–50k 失败、>50k 仅比计数（继承 TASK-0169 F-003）"
    evidence: "scripts/ops/reindex-knowledge.mjs:628-689；REV-TASK-0169-001 F-003；docs/RUNBOOK.md:1002"
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M05 Product 预验收前 / M05b 前"
    accepted_reason: >-
      ADR-0023、RUNBOOK、CODING-STANDARDS 已按 V14/FULLTEXT/HEALTHCHECK 收口（EV-0952/0953），
      但 ARCHITECTURE.md 仍写迁移 V2–V12、THREAT-MODEL/ARCHITECTURE 仍写 INJ-001..012“未建/未覆盖”、
      application-test.yml 仍写 V1–V11，ADR-0023 状态仍为 proposed、Architect 可行性在 PRODUCT
      评审表仍 pending。均为文档/治理漂移，不影响运行时代码与已通过的行为；需在 Product 预验收或
      M05b 前同步，避免 M09 按旧迁移口径评估。
    title: "文档漂移：ARCHITECTURE 迁移仍 V2–V12、INJ harness 文档仍称未建、ADR-0023 仍 proposed、application-test 注释 V1–V11"
    evidence: "docs/ARCHITECTURE.md:130、373、432-436；docs/security/THREAT-MODEL.md:522/627/861；apps/api/src/test/resources/application-test.yml:2；docs/adr/ADR-0023-m05-knowledge-depth.md:3；docs/PRODUCT.md 评审状态表 Architect 可行性 pending；REV-TASK-0172-001 F-002、REV-TASK-0180-001 F-002、REV-TASK-0197-002 F-004"
  - id: F-011
    severity: P3
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M05b / 下次文档维护"
    accepted_reason: >-
      PRODUCT/ADR 使用 `IT-KB-101..116`、`IT-WORKER-LANE-001`、`IT-WORKER-LEASE-001`、
      `FE-QUOTA-RELATIVE-001` 作为验收线索，但仓库中没有以这些 ID 命名的测试类/方法，实际覆盖在
      XlsxDocumentParserTest、PdfDocumentParserTest、ImageDocumentParserTest、OcrServiceTest、
      KnowledgeIndexModesIntegrationTest、M05TenantIsolationTest、M05DepartmentIsolationTest、
      M05AclSecurityMatrixTest、M05AclAuditOrderTest、M05 契约测试与 E2E-M05-01..08 中。
      行为覆盖存在，缺口是“AC → 测试 ID → 报告路径”的可追溯映射；不影响本轮失败结论，但应在
      M05b 前补登记，避免后续重复/漏登记。
    title: "AC 测试线索 ID 未与真实测试类/方法建立可追溯映射（IT-KB-*、IT-WORKER-*、FE-QUOTA-*）"
    evidence: "docs/PRODUCT.md:551-572、709；docs/adr/ADR-0023-m05-knowledge-depth.md:889-912；`grep -rn IT-KB-101 apps e2e` 仅 docs/ADR 命中；实际测试类见 §4/§2"
reviewed_task_reviews:
  - REV-TASK-0164-001
  - REV-TASK-0165-001
  - REV-TASK-0166-002
  - REV-TASK-0167-001
  - REV-TASK-0168-002
  - REV-TASK-0169-001
  - REV-TASK-0171-001
  - REV-TASK-0172-001
  - REV-TASK-0177-001
  - REV-TASK-0180-001
  - REV-TASK-0197-002
  - REV-TASK-0198-001
evidence:
  - EV-0939
  - EV-0940
  - EV-0941
  - EV-0942
  - EV-0943
  - EV-0944
  - EV-0946
  - EV-0948
  - EV-0951
  - EV-0952
  - EV-0953
  - EV-0922
  - EV-0947
reviewed_evidence:
  - EV-0769
  - EV-0772
  - EV-0773
  - EV-0779
  - EV-0785
  - EV-0821
  - EV-0831
  - EV-0838
  - EV-0824
  - EV-0826
  - EV-0828
  - EV-0825
  - EV-0832
  - EV-0840
  - EV-0841
  - EV-0842
  - EV-0849
  - EV-0852
  - EV-0855
  - EV-0900
  - EV-0901
  - EV-0902
  - EV-0903
  - EV-0860
  - EV-0866
  - EV-0848
  - EV-0850
  - EV-0851
  - EV-0853
  - EV-0865
  - EV-0909
  - EV-0922
  - EV-0923
  - EV-0820
  - EV-0845
  - EV-0847
  - EV-0924
  - EV-0925
  - EV-0926
  - EV-0927
  - EV-0928
  - EV-0929
  - EV-0934
  - EV-0935
  - EV-0936
  - EV-0937
  - EV-0938
  - EV-0939
  - EV-0940
  - EV-0941
  - EV-0942
  - EV-0943
  - EV-0944
  - EV-0945
  - EV-0946
  - EV-0947
  - EV-0948
  - EV-0949
  - EV-0950
  - EV-0951
  - EV-0952
  - EV-0953
  - EV-0780
  - EV-0778
  - EV-0781
  - EV-0782
  - EV-0790
  - EV-0791
  - EV-0789
  - EV-0787
  - EV-0819
  - EV-0818
  - EV-0830
  - EV-0829
  - EV-0833
  - EV-0839
  - EV-0836
  - EV-0837
  - EV-0854
  - EV-0861
  - EV-0862
  - EV-0904
  - EV-0905
  - EV-0906
  - EV-0907
  - EV-0910
  - EV-0911
  - EV-0913
  - EV-0914
  - EV-0915
  - EV-0918
  - EV-0920
  - EV-0916
  - EV-0921
  - EV-0930
  - EV-0931
  - EV-0932
  - EV-0933
notes: "【草稿·等待 TASK-0202 修复后定稿】M05 独立技术评审：35/35 任务 done；合同 build/lint/typecheck/test+coverage/E2E/a11y/security 与真实栈 29/29 全绿，EV 哈希/commit/dirty 已核对。技术 verdict 为 fail：AC-15 缺少“失败时间”（P1，Worker/API/UI/E2E 均无 failedAt），AC-21 缺少“策略变更不追溯/界面明示”（P2 open）；这不是 Product 主观偏好，而是可复现的代码/契约/UI/E2E 缺口。Product 预验收当前 changes-requested（R-M05-06/R-M05-07），TASK-0202 修复前模块不得 done；本评审不替代 Product 预验收或用户最终验收。ACL 写 p95 4.33–4.58s 为 P2 accepted 技术残余，仍待用户显式接受。"
created_at: "2026-11-24T00:47:17+08:00"
---

# REV-M05-001：M05 知识库纵深模块独立评审

> **评审状态：草稿，等待 TASK-0202 修复后定稿。**
> Product 预验收当前为 **changes-requested**（R-M05-06 失败时间缺失 P1、R-M05-07 保留策略不追溯界面
> 明示 P2）；Lead 已建 TASK-0202 修复，尚未完成、尚无 EV。本文件是技术评审草稿，**不是** Product
> 预验收 pass，也不表示模块可以 done；TASK-0202 修复后由 Lead 通知补评，届时更新
> `head_commit`/`reviewed_commits`/`reviewed_evidence`（含 TASK-0202 的 EV）并重跑 gate。

## 结论

**技术 verdict：fail（1 条 P1、1 条 P2 open；无 P0；其余 P2/P3 为 accepted 残余）。**

M05 的 35 个任务全部 done，合同全量 build/lint/typecheck/test+coverage/E2E/a11y/security 与真实栈
29/29 均可复核，EV 的 commit、dirty、日志哈希也一致。但验收标准本身未被完全满足：

- **AC-15（P0）failed**：原文要求解析/OCR 失败时文档详情显示“失败时间”。当前 Worker
  `markFailed` 只写 status/error_code/error_message，不写失败时间；API `KnowledgeDocumentResponse`
  没有 `failedAt`；前端只显示“创建时间”和“索引完成”。E2E-M05-05 也没有失败时间断言。这是用户可见
  的 P1 缺口，不能以“原因和重试入口已通过”替代；它是代码/契约/UI/E2E 的客观缺口，不是 Product
  主观偏好。
- **AC-21（P0）failed（子句）**：保留数据行为（默认 180 天、到期/软删除、索引 DELETE、状态覆盖）
  通过，但“策略变更不追溯旧文档、界面明示”没有 UI 入口或说明文案；`KnowledgeRetentionTest`
  只证明后端行为，不能替代“界面明示”。按 P2 open 跟踪，需补文案或书面接受该子句不适用。

Product 预验收当前为 **changes-requested**：工作树中未提交的 Product 草稿（`docs/ACCEPTANCE.md`，
2026-11-24）给出 R-M05-06（P1）与 R-M05-07（P2），与本次独立核对一致；Lead 已建 **TASK-0202**
修复，但尚未完成、尚无 EV。因此 M05 不能标记 done；TASK-0202 修复后需重跑定向与全量证据、补评
本模块 review，再由 Product 重新预验收；Product `pass` 后才可模块 done，用户 `accepted` 前不启动
M05b。

本评审是独立技术验收，**不替代** Product 预验收或用户最终验收；M05 状态、Product/用户验收与 gate
重跑由 Lead 处理。本文件当前为草稿，正式定稿以 TASK-0202 修复后的补评为准。

## 1. 范围与基线

- 模块：M05「知识库纵深」；范围按 DEC-0034=A：Excel 单元格/区域引用、图片/扫描件本地 OCR、
  文档级 ACL（TENANT/DEPT/USERS + U8 管理性读取）、delete/reindex、保留、429 文案、三页 a11y；
  M05b（S3 连接器）不在本模块。
- 任务：`.riccon/modules/M05.json` 的 35 个任务全部 done（TASK-0164..0201，含 V1/V2、O1a/O1b、
  docs/ops 收口任务）。Product 预验收后 Lead 已建 **TASK-0202**（`status=ready`，owner
  `riccon/builder-kb-web`，`evidence=[]`）修复 R-M05-06/07；TASK-0202 当前尚未完成、尚无 EV，
  因此不在 `reviewed_task_reviews`/`reviewed_evidence` 中；修复后补评时更新。
- 代码基线：全量合同证据录制于 `c1bd0e7`（EV-0939..0944）；之后 `c1bd0e7..HEAD` 的代码变更只有
  `deploy/Dockerfile.worker`（HEALTHCHECK）与 `docs/`，没有 `apps/`、`platform/`、`contracts/` 源码
  变更（`git diff --name-status c1bd0e7..HEAD -- . ':(exclude).riccon'`）。当前 HEAD 为 `eaa0adf`
  （`4f923ff`/`eaa0adf` 只新增 Product changes-requested 记录与 TASK-0202 任务卡）。
- 真实栈最终证据 EV-0951 在 `f8b6211`；docs EV-0952 在 `e42ee16`；security/HEALTHCHECK EV-0953 在
  `f4c4399`。三者都是 HEAD 的祖先，dirty=false。
- 当前工作树已有 TASK-0202 的未提交实现（`KnowledgeDocumentResponse`/`KnowledgeJdbc`/`KnowledgeService`/
  `knowledge-detail.tsx`/`contracts/openapi.yaml`/`e2e/tests/m05.spec.ts` 等）；按 Lead 指示，本评审
  只针对 HEAD `eaa0adf`（TASK-0202 修复前）给出草稿结论，**不评 dirty 代码**；TASK-0202 提交后
  再更新 `head_commit`/`reviewed_commits`/`reviewed_evidence` 并补评。
- 工作树当前有一份未提交的 `docs/ACCEPTANCE.md` Product 预验收草稿（由 Product 并行写入）；本评审
  只读取、不修改它，也不把它当作唯一事实源——F-001/F-002 已用代码与 E2E 独立复核。

## 2. 全量证据（EV 完整性已核对）

| 项                            | 结果                                                                                                                       | EV                 |
| ----------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| 合同 build                    | exit 0；`./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                              | **EV-0940**        |
| 合同 lint                     | exit 0；spotless + eslint `--max-warnings=0` + prettier                                                                    | **EV-0941**        |
| 合同 typecheck                | exit 0                                                                                                                     | **EV-0942**        |
| 合同 test+coverage（同一 EV） | exit 0；API **772**、worker **579**、web **69 文件/597 用例**，0 failure / 0 error / 0 skip                                | **EV-0939**        |
| 合同 E2E                      | exit 0；**51 passed**（6.3m），含 E2E-M05-01/02/03/03b/05/06/07/08                                                         | **EV-0943**        |
| a11y                          | exit 0；**18 passed**（含 M05 三页 axe）                                                                                   | **EV-0944**        |
| security（HEALTHCHECK 后）    | exit 0；trivy 0 漏洞 / 0 密钥 / 0 错配（Dockerfile.worker 0）                                                              | **EV-0953**        |
| OCR 零出网                    | exit 0；O1b 镜像 `--network none`，uid 10001，语言包/权重/bbox 全通过                                                      | **EV-0946**        |
| 迁移/restore                  | exit 0；schema 14、49 表、2 触发器、RTO 12.4s；partial（无主密钥/J1）                                                      | **EV-0948**        |
| 真实栈 full                   | exit 0；**29/29**，J1/J2/M03/M10/M04/security/m04/m03 全 passed                                                            | **EV-0951**        |
| perf（AC-20）                 | **exit 1（预期）**；检索 p95 97.516ms、首字 201.5ms、API 386.8ms、索引 7.7s、下载 301ms；**ACL 写 4330.25ms**              | EV-0947 / EV-0922  |
| 失败证据保留                  | EV-0927/0928（V1 E2E/a11y 旧期望失败）、EV-0945（DS-0026）、EV-0949（宿主缺 libtesseract）、EV-0950（3 条 M04 stale 期望） | 保留，不作通过证据 |

覆盖率（EV-0939 报告）：

| 范围        |   Line | Branch | 门槛  | 结论                                     |
| ----------- | -----: | -----: | ----- | ---------------------------------------- |
| apps/api    | 92.78% | 76.29% | 80/70 | pass                                     |
| apps/worker | 85.84% | 70.01% | 80/70 | pass（仅高于 branch 门槛 0.01pp，见 §6） |
| apps/web    | 87.19% | 80.71% | 80/70 | pass                                     |

补充核对：

- EV-0939 的 surefire 报告在磁盘上仍对应录制时间（API 151 个 XML 合计 772、worker 107 个 XML 合计
  579，failures/errors/skipped 全 0）；web 汇总为 `Test Files 69 passed / Tests 597 passed`。
- EV-0943 日志为 `51 passed`；EV-0944 为 `18 passed`；EV-0951 的 Playwright 日志为 `29 passed`；
  EV-0946 的 `ocr-check` 明确 `--network none`、无 DB/OpenSearch/S3 env、text/bbox/置信度；
  EV-0953 的 trivy 表为 0/0/0。
- 所有 `dod_evidence` EV 的 JSON、日志哈希、commit、dirty 与 `# command`/`# exit_code` 头均一致；
  commit 均为 HEAD 祖先。

## 3. AC 对账（M05 段）

| AC            | 结果                               | 证据与说明                                                                                                                                                                                                                                                                                                                   |
| ------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M05-AC-01     | **pass**                           | E2E-M05-01 断言多表/空表/隐藏表警告/单表截断/损坏文件原因与重试/旧 .xls 拒绝；XlsxDocumentParserTest 27/27、PDF/资源上限相关测试通过。E2E 为 stub UI，解析器由集成/单测覆盖；AC-15 的失败时间单列。                                                                                                                          |
| M05-AC-02     | **pass**                           | E2E-M05-01 断言 `citation-locator`/`citation-region=销售数据!B2:D10`/`citation-snippet`；E2E-M05-08 断言无源文件提示且片段/定位仍可见；定位摘要表用 `mark` 高亮，符合 P6=A。                                                                                                                                                 |
| M05-AC-03     | **pass（with residual R-M05-03）** | E2E-M05-02 覆盖 PNG/JPEG/图片型 PDF、四态、bbox、低置信；OcrImagePreprocessorTest 覆盖 EXIF 90/180/270；OCR 关闭显示“识别未启用”。宿主 Tesseract 原生 IT 未跑，真实引擎由 O1b OcrCli 覆盖；不声称宿主 IT 已通过。                                                                                                            |
| M05-AC-04     | **pass（with residual R-M05-02）** | E2E-M05-02 覆盖高/低置信、部分失败、全部失败、失败页列表、重试状态；OcrServiceTest/IndexModes 测试覆盖幂等与无重复。`force=false` 仍整本重解析，见 F-004；用户结果（补齐、幂等、无重复引用）成立。                                                                                                                           |
| M05-AC-05     | **pass**                           | EV-0946：O1b 镜像 `--network none`、无路由、uid 10001、无 DB/OpenSearch/S3 env，OCR 出文本/bbox；OcrServiceTest/OcrImagePreprocessorTest 覆盖关闭开关、像素炸弹/畸形图/资源上限；不把宿主 IT 未执行当通过。                                                                                                                  |
| M05-AC-06     | **pass**                           | E2E-M05-03/03b 断言新文档默认 TENANT、影响确认、未确认不能保存、范围三档、保存后下一次请求生效、U8 `MANAGEMENT_READ` 审计、平台管理员 404、历史文档提示；KnowledgeAclApiTest/AdminReadAuditTest 覆盖 API 与审计。                                                                                                            |
| M05-AC-07     | **pass**                           | E2E-M05-03 断言列表/详情/下载/创建人/平台 404/旧引用遮蔽；SEC-KB-002/003、TEN-034、DEPT-011/014 由 V2 独立矩阵覆盖；下载为服务端流式代理，不暴露对象 key/预签名。                                                                                                                                                            |
| M05-AC-08     | **pass**                           | E2E-M05-03 断言 USERS→DEPT、部门切换、下一次请求生效、创建人始终可见；M05AclAuditOrderTest 断言连续三次 ACL 审计顺序与第二次失败不部分生效；DEPT-012/013 覆盖停用/移出。                                                                                                                                                     |
| M05-AC-09     | **pass（M05 段）**                 | V2 新增 TEN-034..036、DEPT-011..014、SEC-KB-002/003，13 个安全矩阵用例 + AgentContextJdbcAclTest 13 用例全通过、零 skip；跨租户/跨部门/不可见 404、同租户可见无权 403、U8 审计。M05b 的 TEN-037/SEC-CONN-004 不判。                                                                                                          |
| M05-AC-10..14 | **N/A（M05b）**                    | 连接器配置/同步/凭据/注入 harness 归 M05b；M05 不提供连接器 UI/契约。                                                                                                                                                                                                                                                        |
| M05-AC-15     | **fail（R-M05-06 / F-001）**       | DELETE/reindex 真实实现、幂等、403/404、409/503 文案均通过（E2E-M05-05、KnowledgeDeleteReindexApiTest）；但 AC-15 明确要求“失败时间”，当前 Worker 不写、API 无 `failedAt`、UI 不显示、E2E 不断言。P1，必须修复后再验收。                                                                                                     |
| M05-AC-16     | **pass**                           | KnowledgeAdminReadAuditTest、KnowledgeAuditFailClosedTest、KnowledgeRetentionTest 与 E2E-M05-05 覆盖 ACL/U8/删除（USER/RETENTION）/重索引审计、审计失败回滚、无正文/凭据；普通员工无管理审计入口。                                                                                                                           |
| M05-AC-17     | **pass（关闭 R-M04-01）**          | E2E-M05-06 断言聊天/报告 429 显示日/月限额、+08:00 绝对时间、“还有 X 小时”、下一步，且不再出现通用文案；不泄露 tenant/用量。                                                                                                                                                                                                 |
| M05-AC-18     | **pass（关闭 R-M04-02）**          | E2E-M05-06 断言相对时间与绝对时间并列；format 单测覆盖小时/天/即将恢复。未单独造月末/年末用例，属于测试样本缺口而非实现依赖日历月。                                                                                                                                                                                          |
| M05-AC-19     | **pass（with residual）**          | E2E-M05-03b/07 覆盖空/错误/403/解析失败/OCR 关闭/ACL/引用正常/低置信/不可访问/已删除/无源文件；375px、键盘、axe 18/18 通过。知识库列表的“加载”态只在组件实现中以 `aria-busy`/Skeleton 呈现，E2E 未单独断言；真实栈 M05 页面未单列（F-005），不影响 375px/键盘/axe 主结论。                                                   |
| M05-AC-20     | **pass（with residual R-M05-01）** | EV-0947：检索 97.516ms <500、首字估算 201.5ms <3s、API 386.8ms <500、索引 7.7s <60s、下载 301ms；检索 `decisionRequired=false`，无需 FTS/OpenSearch 决策。**ACL 写 4330.25ms（B11 4576.75ms）>500ms**，F-003 P2 accepted；用户仍需在最终验收中显式接受该 residual，不能静默放宽。OCR 量测 measured=false，不把未执行当通过。 |
| M05-AC-21     | **fail（R-M05-07 / F-002）**       | 默认 180 天、租户策略覆盖、到期/软删除、四状态、索引 DELETE 边界由 KnowledgeRetentionTest 3/3 与 E2E-M05-08 证明；但“策略变更不追溯、界面明示”没有 UI 文案/入口，`grep 不追溯                                                                                                                                                | 策略变更` 在 knowledge UI 无命中，P2 open。 |
| M05-AC-22     | **pass（with residual）**          | E2E-M05 51/51、E2E-M03-02 已更新为 ACL 语义、真实栈 29/29；`KnowledgeContractM05Test` 10/10 覆盖 ACL/下载/解析/引用/删除/重索引，端点真实实现无 404/deferred。M05b 段不判；真实栈 M05 知识库 UI 未单列（F-005）。                                                                                                            |

## 4. 任务评审覆盖

本模块共有 12 份任务级评审；以下为每个任务的最新 review ID（模块 review 已逐条读取）：

| 任务      | 最新评审            | 结论 | 与模块 review 的对账                                                                                                                                                                                                       |
| --------- | ------------------- | ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| TASK-0164 | `REV-TASK-0164-001` | pass | F-001 角色口径已由 TASK-0182/ADR-0023 §5.6 澄清（`knowledge.manage` 仅 TENANT_ADMIN；创建人是资源关系）；F-005 新旧调度互斥由 TASK-0180 修复；其余 accepted 保留。                                                         |
| TASK-0165 | `REV-TASK-0165-001` | pass | F-001 ACL 端点门与 404 顺序已由 SEC-KB-002 行为锁定；F-003 下载 502 已由 TASK-0190 修复；契约 prose 残余保留。                                                                                                             |
| TASK-0166 | `REV-TASK-0166-002` | pass | F-001..F-005 fixed（检索 actor/unrestricted、U8 admin_read、reindex 审计、历史 NULL retention、worker ACL 开关）；F-006 下载 502 已由 TASK-0190 修复；F-008 平台 delete 等 P3 accepted 保留。                              |
| TASK-0167 | `REV-TASK-0167-001` | pass | F-001 parse_* 写入由 B7 实现；F-002/F-003 由 TASK-0189 修复；F-004 解析 180s/稳定码仍 accepted（F-008）；F-008 测试缺口部分由 TASK-0189/V2 补。                                                                            |
| TASK-0168 | `REV-TASK-0168-002` | pass | F-001/F-002/F-003/F-005 fixed（OOM、语言绑定、NO_TEXT、TextChunker）；F-008/F-009 P3 accepted（Tesseract 不可中断/DPI）。                                                                                                  |
| TASK-0169 | `REV-TASK-0169-001` | pass | F-001 force=false 整本重解析 → F-004；F-002 env 占位符已 fixed；F-003 重灌脚本大租户校验 → F-009；其余 P3 accepted。                                                                                                       |
| TASK-0171 | `REV-TASK-0171-001` | pass | F-001 bbox/ocrConfidence 由 TASK-0197 修复；F-002 N+1 由 TASK-0197 批量修复；F-003..F-005 P3 accepted。                                                                                                                    |
| TASK-0172 | `REV-TASK-0172-001` | pass | F-001 spotless fixed；F-002 INJ 文档漂移 → F-010；F-003/F-004/F-005 P3/P2 accepted。                                                                                                                                       |
| TASK-0177 | `REV-TASK-0177-001` | pass | V2 全量验证；F-001 HEALTHCHECK 由 TASK-0200/EV-0953 修复；F-003/F-004 文档由 TASK-0201 修复；F-002 ACL 写 → F-003；F-005 OCR probe → F-005。                                                                               |
| TASK-0180 | `REV-TASK-0180-001` | pass | F-001 legacy 30m 看门狗 accepted（M05b 前决定）；F-002 application-test 注释 V1–V11 → F-010；F-003/F-004/F-005 P3 accepted。                                                                                               |
| TASK-0197 | `REV-TASK-0197-002` | pass | F-001/F-002/F-003 fixed；front matter `cross_task_blockers: X-001` 仍写 open，但已由 `REV-TASK-0198-001` F-001/EV-0921 修复并在 EV-0939 全量通过，属过期状态而非 open P1。F-004/F-005/F-006/F-008 accepted → F-010/F-005。 |
| TASK-0198 | `REV-TASK-0198-001` | pass | X-001 已 fixed（10e8bc8，EV-0921）；F-002/F-003/F-004/F-005/F-006/F-007/F-008 accepted；V14 锁归 M09（F-006）。                                                                                                            |

没有任务级评审的任务（B8/B8fix/B11/O1a/O1b/docs 收口等）不触发本模块 `reviewed_task_reviews`
缺失；TASK-0200/TASK-0201 的产出由 EV-0953/EV-0952 与本次模块 review 直接覆盖。

## 5. Findings

| ID    | 级别   | 状态     | 摘要                                                                 | Owner / due                                     |
| ----- | ------ | -------- | -------------------------------------------------------------------- | ----------------------------------------------- |
| F-001 | **P1** | **open** | AC-15“失败时间”在 Worker/API/UI/E2E 均缺失                           | builder-kb-index + builder-kb-web / M05 gate 前 |
| F-002 | **P2** | **open** | AC-21“策略变更不追溯/界面明示”无 UI 文案或入口                       | builder-kb-web + Lead / M05 gate 前或书面接受   |
| F-003 | P2     | accepted | AC-20 ACL 写 p95 4.33–4.58s > 500ms（quota 行锁）                    | Lead + Architect / 用户最终验收前、M05b gate 前 |
| F-004 | P2     | accepted | force=false 仍整本重解析，未只补缺失页                               | builder-kb-index + Architect / M05b             |
| F-005 | P3     | accepted | 宿主 Tesseract IT 未跑；真实栈 full 用 OCR 关闭；M05 UI 无真实栈单列 | verifier + ops / M05b                           |
| F-006 | P2     | accepted | V14 生产锁/维护窗口未量测；物理清理归 M09                            | ops + Architect / M09                           |
| F-007 | P2     | accepted | M04 F-008 生产 pass-through 继承                                     | builder-route / M09 前                          |
| F-008 | P2     | accepted | 解析 180s/稳定码未按 ADR §3.3                                        | Lead + Architect / M05b                         |
| F-009 | P2     | accepted | 重灌脚本大租户 ID 校验缺口                                           | builder-kb-index / M05b/M09                     |
| F-010 | P3     | accepted | ARCHITECTURE/THREAT-MODEL/application-test/ADR 状态文档漂移          | Architect + Lead / 预验收前或 M05b              |
| F-011 | P3     | accepted | AC 测试线索 ID 与真实测试类无可追溯映射                              | Architect + Lead / M05b                         |

### 5.1 F-001（P1）最小复现与修复要求

可复现核对：

```bash
# 1) API 响应无 failedAt
grep -n "failedAt\|failed_at" \
  apps/api/src/main/java/com/riccon/api/knowledge/dto/KnowledgeDocumentResponse.java
# 2) Worker markFailed 不写失败时间
sed -n '177,190p' apps/worker/src/main/java/com/riccon/worker/index/repository/KnowledgeRepository.java
# 3) 前端只显示创建/索引完成
sed -n '416,425p' apps/web/src/pages/knowledge/components/knowledge-detail.tsx
# 4) E2E-M05-05 无失败时间断言
sed -n '413,480p' e2e/tests/m05.spec.ts
```

观察值：`KnowledgeDocumentResponse` 字段只有 `indexedAt`/`createdAt`；`markFailed` 的 UPDATE 只写
`status/error_code/error_message`；前端显示“创建时间”“索引完成”；E2E 没有“失败时间”文本断言。
期望值：AC-15 要求“可读原因、**失败时间**与重试入口”。TASK-0202 的 acceptance 允许 `failedAt`
来自 `updated_at` 或专用字段；若是 `updated_at`，必须证明文档进入 FAILED 后 ACL/可见性等其他
UPDATE 不会改写它（`knowledge_document.updated_at` 是 `ON UPDATE CURRENT_TIMESTAMP`，ACL 变更会
碰同一行），否则展示的仍不是稳定失败时间。建议用专用 `failed_at`，或只在失败路径写入且后续
ACL 变更不更新该列。修复至少包含：

1. Flyway additive 迁移增加 `knowledge_document.failed_at`（或等价显式且稳定的时间戳；不要用会被
   其他更新覆盖的 `updated_at` 冒充），worker `markFailed` 写入，成功/重新索引时清理；
2. API `KnowledgeDocumentResponse` 增加 `failedAt`（可选字段）并在契约中登记；
3. 文档详情显示“失败时间”，失败/部分失败均可见；
4. E2E-M05-05 或新用例断言该字段（stub 保真 + 真实 API 断言）；
5. 修复后重跑定向（KnowledgeDeleteReindexApiTest、M05 相关 API/worker 测试、E2E-M05-05）与全量
   合同 test/coverage/E2E/a11y；新增 EV 后再做 REV-M05-002。

### 5.2 F-002（P2）最小复现与修复要求

```bash
grep -rn "不追溯\|策略变更" apps/web/src/pages/knowledge apps/web/src/services/knowledge
# 无输出；仅 admin/tenants/index.tsx:112 显示“审计保留 N 天”，无编辑入口
sed -n '426,430p' apps/web/src/pages/knowledge/components/knowledge-detail.tsx
```

观察值：详情只显示保留天数/到期/策略来源；没有“策略变更不追溯旧文档”的说明；知识库 UI 没有租户
保留策略变更入口。期望值：AC-21 的“界面明示”。修复选项：
A) 在知识库列表或文档详情加固定中文说明（例如“保留策略变更只影响新文档与新的到期计算，不追溯
已有文档”），并加 web 单测/E2E；B) 若 M05 明确不提供策略变更入口，由 Lead/用户书面接受该子句
“不适用”，并在 ACCEPTANCE/ADR 记录；不能静默省略。

## 6. 残余与未验证

- **M05b**：AC-10..14、AC-09 连接器段、AC-15/16/21 连接器段、AC-20/22 整条复验均未做；M05 不据此
  判 pass。S3 连接器/凭据/注入 harness 归 M05b。
- **ACL 写性能**：4.33–4.58s 是 500ms 预算的 8.7–9.2 倍；检索/首字/API/索引/下载均达标。F-003 技术
  验收 accepted，但用户最终验收必须显式接受或验收前修复；DEC-0038 只覆盖检索 FTS，不覆盖 ACL 写。
- **OCR**：宿主无 libtesseract，`TesseractOcrEngineIT` 未跑；真实栈 29/29 以 `RICCON_OCR_ENABLED=0`
  取得，M05 知识库 UI 未单列真实栈旅程；零出网由 O1b OcrCli 覆盖。F-005。
- **迁移/清理**：V14 生产锁等待/维护窗口未量测；软删除后的物理对象/分块清理归 M09 release blocker。
  F-006。
- **继承债务**：M04 F-008 pass-through（F-007）、M23 F-001..F-003（部门字段/前端兜底/身份任务评审
  例外）、M24 F-001..F-005（跨进程轮换/扫描范围/3s 等待/真实栈顺序）仍按各自评审 accepted；M05 未
  改变其结论，不重复打开。worker 分支覆盖率 70.01% 仅高于门槛 0.01pp，后续删除任一 worker 测试都
  可能让合同 `verify` 变红，建议 M05b 前加缓冲（非 AC finding）。
- **文档/可追溯性**：F-010（ARCHITECTURE V2–V12、INJ 文档“未建”、ADR-0023 proposed、application-test
  V1–V11）与 F-011（IT-KB/IT-WORKER/FE-QUOTA 线索 ID 未映射到实际测试类）为 P3 accepted；不影响
  本轮 AC-15/21 失败结论，但应在 Product 预验收/M05b 前收口。
- **证据卫生**：EV-0908 无 commit 字段（TASK-0173 的中间证据），本评审未把它当权威通过证据；
  TASK-0173 的权威 perf 证据是 EV-0922（exit 1，residual）与 V2 EV-0947（exit 1，residual）。
  EV-0927/0928/0945/0949/0950 为失败尝试，保留但不作通过证据。

## 7. Gate 预期与下一步

- 当前 `riccon gate check --module M05` 预期 **fail**：review verdict 为 fail，`dod.acceptance=false`；
  这是正确反馈，不应改 review 强行通过。本文件是等待 TASK-0202 的草稿；TASK-0202 修复前不重跑
  gate 作为模块通过依据。2026-11-24T00:47 试跑结果为 **4 errors**：verdict fail、
  `dod.acceptance` false、`EV-0952/EV-0953` docs evidence 被判 placeholder/no-op；其余
  evidence/task coverage/DoD build/tests/coverage/e2e/docs/evidence 检查均通过。
- 本次试跑 gate 另暴露一个**证据格式问题**（不影响 F-001/F-002 结论）：`EV-0952`/`EV-0953` 的
  命令末尾 `; echo ...` 被 strict masking 检测判为 placeholder/no-op，因此 `dod_evidence.docs`
  当前会被 gate 拒绝；`EV-0919` 是 TASK-0197 的负向探针、不在 TASK-0197.evidence 列表，已从
  `reviewed_evidence` 移除（正文仍引用其失败事实）。TASK-0202 定稿时请补一条无 `; echo` 的 docs
  证据（例如 `git diff --check && grep -q "ngram" ... && grep -q "M05AclSecurityMatrixTest" ...`
  的纯命令）或由 Lead 记录格式例外，再重新绑定 docs。
- Lead 需要：
  1. 完成已建的 **TASK-0202**：F-001（P1，失败时间：迁移/Worker/API/UI/E2E）与 F-002（P2，策略
     变更界面明示或书面接受）；
  2. 修复后重跑定向测试 + 全量合同 test/coverage/E2E/a11y（必要时 security），新增 EV；
  3. 写 `REV-M05-002`（新上下文/新会话），更新 `reviewed_task_reviews`（含 TASK-0202 的任务 review
     如适用）、`head_commit` 与 `reviewed_evidence`（含 TASK-0202 的 EV）；
  4. 重新 Product 预验收；Product `pass` 后才可模块 done；用户 `accepted` 前不启动 M05b；
  5. ACL 写 residual（F-003）需在用户最终验收中显式接受，或验收前修复；DEC-0038 不能替代。
- 注意：本评审的技术 verdict 只说明当前代码不满足 AC-15/21；**gate 通过/技术 review pass 都不能替代
  Product 预验收**。Product 当前为 changes-requested，TASK-0202 未关闭前模块 done 不应发生。
- 本评审不修改任务/模块状态、不提交 git；`docs/ACCEPTANCE.md` 的 Product 草稿由 Product/Lead 处理。
