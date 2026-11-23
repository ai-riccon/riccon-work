---
review_id: REV-M05-002
module_id: M05
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_df6c1db13ffelhx1bMd8ahz4pO
  model: opencode-go/deepseek-v4.1-flash
  independent: true
review_kind: module
review_status: final
previous_review: REV-M05-001
supersedes: REV-M05-001
product_preacceptance_status: pass
product_preacceptance_note: >-
  Product 预验收已 pass（with residual R-M05-01..05；R-M05-06/07 fixed），见 docs/ACCEPTANCE.md
  2026-11-24 M05 段。Product pass 不替代用户最终验收；ACL 写 residual（R-M05-01）仍需用户最终
  验收显式接受。模块 done 可在本技术 gate pass 后由 Lead 记录。
head_commit: 2554b97e2c2de6c4c13bcb52bc7ad05566cc70b6
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
  - b7b8c45
  - 028a2ce
  - 823bfc0a4bf719044a88b8aa97adac62895d2a20
  - 2554b97e2c2de6c4c13bcb52bc7ad05566cc70b6
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
    - EV-0939
    - EV-0943
    - EV-0951
    - EV-0954
    - EV-0955
    - EV-0959
    - EV-0960
  build:
    - EV-0940
  tests:
    - EV-0939
  coverage:
    - EV-0939
  e2e:
    - EV-0943
  docs:
    - EV-0960
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
    - EV-0954
    - EV-0955
    - EV-0956
    - EV-0957
    - EV-0958
    - EV-0959
    - EV-0960
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-kb-index + riccon/builder-kb-web"
    due: "fixed in b7b8c45（TASK-0202）"
    title: "AC-15 失败时间已补：API failedAt（file_parse.finished_at 优先、updated_at 回退、+08:00）、Web knowledge-failed-at、E2E-M05-09"
    evidence: >-
      apps/api/src/main/java/com/riccon/api/knowledge/dto/KnowledgeDocumentResponse.java:33、58、91；
      apps/api/src/main/java/com/riccon/api/knowledge/persistence/KnowledgeJdbc.java:49、550-565；
      apps/web/src/pages/knowledge/components/knowledge-detail.tsx:474；
      e2e/tests/m05.spec.ts:651-675（E2E-M05-09）；contracts/openapi.yaml:5157；
      EV-0954（KnowledgeFailedAtApiTest/契约/解析形态）、EV-0959（E2E 52/52）、EV-0960（docs/contract）。
  - id: F-002
    severity: P2
    status: fixed
    owner: "riccon/builder-kb-web"
    due: "fixed in b7b8c45（TASK-0202）"
    title: "AC-21 保留策略不追溯界面明示已补：knowledge-retention-note + E2E-M05-09"
    evidence: >-
      apps/web/src/pages/knowledge/components/knowledge-detail.tsx:451；
      e2e/tests/m05.spec.ts:651-675（E2E-M05-09 断言“保留策略变更仅影响新文档和新的到期计算，不追溯历史文档”）；
      EV-0955（web 597/coverage）、EV-0959（E2E 52/52）。
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
    due: "M05 Product 预验收后 / M05b 前"
    accepted_reason: >-
      ADR-0023、RUNBOOK、CODING-STANDARDS 已按 V14/FULLTEXT/HEALTHCHECK 收口（EV-0952/0953/0960），
      但 ARCHITECTURE.md 仍写迁移 V2–V12、THREAT-MODEL/ARCHITECTURE 仍写 INJ-001..012“未建/未覆盖”、
      application-test.yml 仍写 V1–V11，ADR-0023 状态仍为 proposed、Architect 可行性在 PRODUCT
      评审表仍 pending。均为文档/治理漂移，不影响运行时代码与已通过的行为；需在 M05b 前同步，避免
      M09 按旧迁移口径评估。
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
      M05AclSecurityMatrixTest、M05AclAuditOrderTest、M05 契约测试与 E2E-M05-01..09 中。
      行为覆盖存在，缺口是“AC → 测试 ID → 报告路径”的可追溯映射；不影响本轮结论，但应在
      M05b 前补登记，避免后续重复/漏登记。
    title: "AC 测试线索 ID 未与真实测试类/方法建立可追溯映射（IT-KB-*、IT-WORKER-*、FE-QUOTA-*）"
    evidence: "docs/PRODUCT.md:551-572、709；docs/adr/ADR-0023-m05-knowledge-depth.md:889-912；`grep -rn IT-KB-101 apps e2e` 仅 docs/ADR 命中；实际测试类见 §4/§2"
  - id: F-012
    severity: P3
    status: accepted
    owner: "riccon/verifier + riccon/lead"
    due: "M05b / 下次 maintenance"
    accepted_reason: >-
      TASK-0202 修复后的 b7b8c45 没有重跑合同全量 Maven `verify`（EV-0939 全量在 c1bd0e7：API 772 +
      worker 579）；但改动面由 EV-0954（API 契约/KnowledgeFailedAtApiTest/解析形态定向）、EV-0955
      （web 全量 597/coverage 87.19/80.73）、EV-0956 typecheck、EV-0957 lint、EV-0958 a11y 18/18、
      EV-0959 E2E 52/52 覆盖，worker 未改。风险是未重跑其他 API 回归，属证据范围 residual；建议
      M05b 或下次全量时补一次 b7b8c45 之后的合同 test/coverage。
    title: "TASK-0202 后未在 b7b8c45 重跑合同全量 Maven verify（全量 EV-0939 在 c1bd0e7）"
    evidence: "EV-0939（c1bd0e7 全量）；EV-0954/0955/0956/0957/0958/0959（b7b8c45 定向与 web/E2E）"
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
  - EV-0954
  - EV-0955
  - EV-0956
  - EV-0957
  - EV-0958
  - EV-0959
  - EV-0960
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
  - EV-0954
  - EV-0955
  - EV-0956
  - EV-0957
  - EV-0958
  - EV-0959
  - EV-0960
notes: >-
  REV-M05-002 技术定稿：M05 35/35 任务 + TASK-0202 修复 done；R-M05-06/07 fixed（failedAt +
  knowledge-failed-at + retention-note + E2E-M05-09）。合同 build/lint/typecheck/test+coverage/
  E2E/a11y/security 与真实栈 29/29 全绿；EV-0954..0960 在 b7b8c45/823bfc0，dirty=false。技术
  verdict pass；ACL 写 p95 4.33–4.58s 为 P2 accepted residual，需用户最终验收显式接受。Product
  预验收已 pass（with residual R-M05-01..05；R-M05-06/07 fixed）；模块 done 可在技术 gate pass 后
  由 Lead 记录，用户 `accepted` 前不启动 M05b。本评审是独立技术验收，不替代 Product 预验收或用户
  最终验收。gate 已 pass（exit 0，0 errors，3 warnings，仅技术 gate）。
created_at: "2026-11-24T01:38:07+08:00"
---

# REV-M05-002：M05 知识库纵深模块独立评审（定稿）

> **Product 预验收：pass（with residual R-M05-01..05；R-M05-06/07 fixed）。** R-M05-06（失败时间
> P1）与 R-M05-07（保留策略界面明示 P2）已由 TASK-0202 修复并 done（b7b8c45）；Product 2026-11-24
> 预验收 pass。本文件是**技术验收**，技术 verdict 为 pass；用户最终验收（含 ACL 写 residual 的
> 显式接受）仍 pending，用户 `accepted` 前不启动 M05b。本评审不替代用户最终验收。

## 结论

**技术 verdict：pass（R-M05-06/07 fixed；无 P0/P1 open；P2/P3 残余均 accepted）。**

M05 的 35 个任务全部 done，TASK-0202（AC-15 失败时间 + AC-21 保留策略明示）已修复并 done。
合同全量 build/lint/typecheck/test+coverage/E2E/a11y/security 与真实栈 29/29 均可复核，EV 的
commit、dirty、日志哈希一致。关键闭环：

- **AC-15 fixed**：TASK-0202 增加 `KnowledgeDocument.failedAt`（FAILED 时 `file_parse.finished_at`
  优先、`updated_at` 回退，+08:00）、API `failedAt`、Web `knowledge-failed-at`、E2E-M05-09；
  EV-0954/EV-0959/EV-0960 通过。先前 P1 关闭。
- **AC-21 fixed**：Web 增加 `knowledge-retention-note`（“保留策略变更仅影响新文档和新的到期计算，
  不追溯历史文档”），E2E-M05-09 断言；EV-0955/EV-0959 通过。先前 P2 关闭。
- **AC-20 pass（with residual F-003）**：检索 p95 97.5ms、首字 201.5ms、API 386.8ms、索引 7.7s、
  下载 301ms 达标；ACL 写 p95 4.33–4.58s > 500ms 为 quota 租户行锁 residual，P2 accepted，
  仍需用户最终验收显式接受。

Product 预验收 **pass**（with residual R-M05-01..05；R-M05-06/07 fixed，见 `docs/ACCEPTANCE.md`
2026-11-24 M05 段）。模块 done 可在本技术 gate pass 后由 Lead 记录；用户最终验收仍 pending，
ACL 写 residual 需用户显式接受，用户 `accepted` 前不启动 M05b。本评审是独立技术验收，**不替代**
Product 预验收或用户最终验收；M05 状态、Product/用户验收与 gate 重跑由 Lead 处理。

## 1. 范围与基线

- 模块：M05「知识库纵深」；范围按 DEC-0034=A：Excel 单元格/区域引用、图片/扫描件本地 OCR、
  文档级 ACL（TENANT/DEPT/USERS + U8 管理性读取）、delete/reindex、保留、429 文案、三页 a11y；
  M05b（S3 连接器）不在本模块。
- 任务：`.riccon/modules/M05.json` 的 35 个任务全部 done（TASK-0164..0201，含 V1/V2、O1a/O1b、
  docs/ops 收口任务）。Product changes-requested 后 Lead 建 **TASK-0202**（owner
  `riccon/builder-kb-web`），修复 R-M05-06/07，已 done（`b7b8c45`），evidence 为
  EV-0954..EV-0960；TASK-0202 没有单独任务级 review（按 Lead 要求 `reviewed_task_reviews` 保持
  12 个，模块 review 直接复核其 EV）。
- 代码基线：全量合同证据 EV-0939..EV-0944 录制于 `c1bd0e7`；TASK-0202 修复提交 `b7b8c45`
  （`KnowledgeDocument.failedAt`/API/Web/OpenAPI/E2E-M05-09）；`028a2ce`/`823bfc0` 是
  TASK-0202 证据与 closeout（.riccon）；`2554b97` 记录 Product 预验收 pass（with residual
  R-M05-01..05）与 EV-0960。当前 HEAD 为 `2554b97`。`b7b8c45` 后的代码变更只有 TASK-0202
  修复面，没有其他 `apps/`/`platform/`/`contracts/` 变更。
- 真实栈最终证据 EV-0951 在 `f8b6211`；security/HEALTHCHECK EV-0953 在 `f4c4399`；TASK-0202
  的 EV-0954..0959 在 `b7b8c45`（dirty=false）；docs EV-0960 在 `823bfc0`（clean worktree，
  dirty=false）。这些 commit 都是 HEAD 的祖先。
- Product 预验收已在 `2554b97`/`docs/ACCEPTANCE.md` 记录 **pass（with residual R-M05-01..05；
  R-M05-06/07 fixed）**；本评审不替代用户最终验收，用户 `accepted` 前不启动 M05b。

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
| TASK-0202 API 定向            | exit 0；KnowledgeContractM05Test、KnowledgeFailedAtApiTest、KnowledgeDocumentParseShapeTest                                | **EV-0954**        |
| TASK-0202 web 全量/coverage   | exit 0；69 文件/**597** 用例；Statements 87.31%、Branches **80.73%**、Lines **87.19%**                                     | **EV-0955**        |
| TASK-0202 typecheck / lint    | exit 0                                                                                                                     | EV-0956 / EV-0957  |
| TASK-0202 a11y                | exit 0；**18 passed**                                                                                                      | **EV-0958**        |
| TASK-0202 E2E                 | exit 0；**52 passed**（新增 E2E-M05-09）                                                                                   | **EV-0959**        |
| TASK-0202 docs                | exit 0；clean worktree、无 `; echo`；CODING-STANDARDS/ADR/合同校验                                                         | **EV-0960**        |
| perf（AC-20）                 | **exit 1（预期）**；检索 p95 97.516ms、首字 201.5ms、API 386.8ms、索引 7.7s、下载 301ms；**ACL 写 4330.25ms**              | EV-0947 / EV-0922  |
| 失败证据保留                  | EV-0927/0928（V1 E2E/a11y 旧期望失败）、EV-0945（DS-0026）、EV-0949（宿主缺 libtesseract）、EV-0950（3 条 M04 stale 期望） | 保留，不作通过证据 |

覆盖率（EV-0939 报告）：

| 范围        |   Line | Branch | 门槛  | 结论                                     |
| ----------- | -----: | -----: | ----- | ---------------------------------------- |
| apps/api    | 92.78% | 76.29% | 80/70 | pass                                     |
| apps/worker | 85.84% | 70.01% | 80/70 | pass（仅高于 branch 门槛 0.01pp，见 §6） |
| apps/web    | 87.19% | 80.73% | 80/70 | pass（EV-0955，TASK-0202 后）            |

补充核对：

- EV-0939 的 surefire 报告在磁盘上仍对应录制时间（API 151 个 XML 合计 772、worker 107 个 XML 合计
  579，failures/errors/skipped 全 0）；web 汇总为 `Test Files 69 passed / Tests 597 passed`。
- EV-0943 日志为 `51 passed`；EV-0944 为 `18 passed`；EV-0951 的 Playwright 日志为 `29 passed`；
  EV-0946 的 `ocr-check` 明确 `--network none`、无 DB/OpenSearch/S3 env、text/bbox/置信度；
  EV-0953 的 trivy 表为 0/0/0。
- 所有 `dod_evidence` EV 的 JSON、日志哈希、commit、dirty 与 `# command`/`# exit_code` 头均一致；
  commit 均为 HEAD 祖先。
- **证据范围说明**：TASK-0202 修复后没有在 `b7b8c45` 重跑合同全量 Maven `verify`（EV-0939 全量在
  `c1bd0e7`：API 772 + worker 579）；改动面由 EV-0954（API 定向）、EV-0955（web 全量 597/coverage）、
  EV-0956 typecheck、EV-0957 lint、EV-0958 a11y、EV-0959 E2E 52/52 覆盖，worker 未改。见 F-012
  P3 accepted；建议 M05b 或下次全量补一次 `b7b8c45` 之后的合同 test/coverage。

## 3. AC 对账（M05 段）

| AC            | 结果                               | 证据与说明                                                                                                                                                                                                                                                                                                                    |
| ------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M05-AC-01     | **pass**                           | E2E-M05-01 断言多表/空表/隐藏表警告/单表截断/损坏文件原因与重试/旧 .xls 拒绝；XlsxDocumentParserTest 27/27、PDF/资源上限相关测试通过。E2E 为 stub UI，解析器由集成/单测覆盖；AC-15 的失败时间单列。                                                                                                                           |
| M05-AC-02     | **pass**                           | E2E-M05-01 断言 `citation-locator`/`citation-region=销售数据!B2:D10`/`citation-snippet`；E2E-M05-08 断言无源文件提示且片段/定位仍可见；定位摘要表用 `mark` 高亮，符合 P6=A。                                                                                                                                                  |
| M05-AC-03     | **pass（with residual R-M05-03）** | E2E-M05-02 覆盖 PNG/JPEG/图片型 PDF、四态、bbox、低置信；OcrImagePreprocessorTest 覆盖 EXIF 90/180/270；OCR 关闭显示“识别未启用”。宿主 Tesseract 原生 IT 未跑，真实引擎由 O1b OcrCli 覆盖；不声称宿主 IT 已通过。                                                                                                             |
| M05-AC-04     | **pass（with residual R-M05-02）** | E2E-M05-02 覆盖高/低置信、部分失败、全部失败、失败页列表、重试状态；OcrServiceTest/IndexModes 测试覆盖幂等与无重复。`force=false` 仍整本重解析，见 F-004；用户结果（补齐、幂等、无重复引用）成立。                                                                                                                            |
| M05-AC-05     | **pass**                           | EV-0946：O1b 镜像 `--network none`、无路由、uid 10001、无 DB/OpenSearch/S3 env，OCR 出文本/bbox；OcrServiceTest/OcrImagePreprocessorTest 覆盖关闭开关、像素炸弹/畸形图/资源上限；不把宿主 IT 未执行当通过。                                                                                                                   |
| M05-AC-06     | **pass**                           | E2E-M05-03/03b 断言新文档默认 TENANT、影响确认、未确认不能保存、范围三档、保存后下一次请求生效、U8 `MANAGEMENT_READ` 审计、平台管理员 404、历史文档提示；KnowledgeAclApiTest/AdminReadAuditTest 覆盖 API 与审计。                                                                                                             |
| M05-AC-07     | **pass**                           | E2E-M05-03 断言列表/详情/下载/创建人/平台 404/旧引用遮蔽；SEC-KB-002/003、TEN-034、DEPT-011/014 由 V2 独立矩阵覆盖；下载为服务端流式代理，不暴露对象 key/预签名。                                                                                                                                                             |
| M05-AC-08     | **pass**                           | E2E-M05-03 断言 USERS→DEPT、部门切换、下一次请求生效、创建人始终可见；M05AclAuditOrderTest 断言连续三次 ACL 审计顺序与第二次失败不部分生效；DEPT-012/013 覆盖停用/移出。                                                                                                                                                      |
| M05-AC-09     | **pass（M05 段）**                 | V2 新增 TEN-034..036、DEPT-011..014、SEC-KB-002/003，13 个安全矩阵用例 + AgentContextJdbcAclTest 13 用例全通过、零 skip；跨租户/跨部门/不可见 404、同租户可见无权 403、U8 审计。M05b 的 TEN-037/SEC-CONN-004 不判。                                                                                                           |
| M05-AC-10..14 | **N/A（M05b）**                    | 连接器配置/同步/凭据/注入 harness 归 M05b；M05 不提供连接器 UI/契约。                                                                                                                                                                                                                                                         |
| M05-AC-15     | **pass（R-M05-06 fixed）**         | DELETE/reindex 真实实现、幂等、403/404、409/503 文案均通过（E2E-M05-05、KnowledgeDeleteReindexApiTest）。TASK-0202 补 `KnowledgeDocument.failedAt`（FAILED 时 `file_parse.finished_at` 优先、`updated_at` 回退，+08:00）、API `failedAt`、Web `knowledge-failed-at` 与 E2E-M05-09；EV-0954/EV-0959/EV-0960 通过，F-001 关闭。 |
| M05-AC-16     | **pass**                           | KnowledgeAdminReadAuditTest、KnowledgeAuditFailClosedTest、KnowledgeRetentionTest 与 E2E-M05-05 覆盖 ACL/U8/删除（USER/RETENTION）/重索引审计、审计失败回滚、无正文/凭据；普通员工无管理审计入口。                                                                                                                            |
| M05-AC-17     | **pass（关闭 R-M04-01）**          | E2E-M05-06 断言聊天/报告 429 显示日/月限额、+08:00 绝对时间、“还有 X 小时”、下一步，且不再出现通用文案；不泄露 tenant/用量。                                                                                                                                                                                                  |
| M05-AC-18     | **pass（关闭 R-M04-02）**          | E2E-M05-06 断言相对时间与绝对时间并列；format 单测覆盖小时/天/即将恢复。未单独造月末/年末用例，属于测试样本缺口而非实现依赖日历月。                                                                                                                                                                                           |
| M05-AC-19     | **pass（with residual）**          | E2E-M05-03b/07 覆盖空/错误/403/解析失败/OCR 关闭/ACL/引用正常/低置信/不可访问/已删除/无源文件；375px、键盘、axe 18/18 通过。知识库列表的“加载”态只在组件实现中以 `aria-busy`/Skeleton 呈现，E2E 未单独断言；真实栈 M05 页面未单列（F-005），不影响 375px/键盘/axe 主结论。                                                    |
| M05-AC-20     | **pass（with residual R-M05-01）** | EV-0947：检索 97.516ms <500、首字估算 201.5ms <3s、API 386.8ms <500、索引 7.7s <60s、下载 301ms；检索 `decisionRequired=false`，无需 FTS/OpenSearch 决策。**ACL 写 4330.25ms（B11 4576.75ms）>500ms**，F-003 P2 accepted；用户仍需在最终验收中显式接受该 residual，不能静默放宽。OCR 量测 measured=false，不把未执行当通过。  |
| M05-AC-21     | **pass（R-M05-07 fixed）**         | 默认 180 天、租户策略覆盖、到期/软删除、四状态、索引 DELETE 边界由 KnowledgeRetentionTest 3/3 与 E2E-M05-08 证明；TASK-0202 增加 `knowledge-retention-note`（“保留策略变更仅影响新文档和新的到期计算，不追溯历史文档”），E2E-M05-09 断言；EV-0955/EV-0959 通过，F-002 关闭。                                                  |
| M05-AC-22     | **pass（with residual）**          | E2E-M05 51/51（EV-0943）、TASK-0202 后 52/52（EV-0959）、E2E-M03-02 已更新为 ACL 语义、真实栈 29/29；`KnowledgeContractM05Test` 10/10 覆盖 ACL/下载/解析/引用/删除/重索引，端点真实实现无 404/deferred。M05b 段不判；真实栈 M05 知识库 UI 未单列（F-005）。                                                                   |

## 4. 任务评审覆盖

本模块共有 12 份任务级评审；以下为每个任务的最新 review ID（模块 review 已逐条读取）：

| 任务      | 最新评审            | 结论 | 与模块 review 的对账                                                                                                                                                                                                                  |
| --------- | ------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| TASK-0164 | `REV-TASK-0164-001` | pass | F-001 角色口径已由 TASK-0182/ADR-0023 §5.6 澄清（`knowledge.manage` 仅 TENANT_ADMIN；创建人是资源关系）；F-005 新旧调度互斥由 TASK-0180 修复；其余 accepted 保留。                                                                    |
| TASK-0165 | `REV-TASK-0165-001` | pass | F-001 ACL 端点门与 404 顺序已由 SEC-KB-002 行为锁定；F-003 下载 502 已由 TASK-0190 修复；契约 prose 残余保留。                                                                                                                        |
| TASK-0166 | `REV-TASK-0166-002` | pass | F-001..F-005 fixed（检索 actor/unrestricted、U8 admin_read、reindex 审计、历史 NULL retention、worker ACL 开关）；F-006 下载 502 已由 TASK-0190 修复；F-008 平台 delete 等 P3 accepted 保留。                                         |
| TASK-0167 | `REV-TASK-0167-001` | pass | F-001 parse_* 写入由 B7 实现；F-002/F-003 由 TASK-0189 修复；F-004 解析 180s/稳定码仍 accepted（F-008）；F-008 测试缺口部分由 TASK-0189/V2 补。                                                                                       |
| TASK-0168 | `REV-TASK-0168-002` | pass | F-001/F-002/F-003/F-005 fixed（OOM、语言绑定、NO_TEXT、TextChunker）；F-008/F-009 P3 accepted（Tesseract 不可中断/DPI）。                                                                                                             |
| TASK-0169 | `REV-TASK-0169-001` | pass | F-001 force=false 整本重解析 → F-004；F-002 env 占位符已 fixed；F-003 重灌脚本大租户校验 → F-009；其余 P3 accepted。                                                                                                                  |
| TASK-0171 | `REV-TASK-0171-001` | pass | F-001 bbox/ocrConfidence 由 TASK-0197 修复；F-002 N+1 由 TASK-0197 批量修复；F-003..F-005 P3 accepted。                                                                                                                               |
| TASK-0172 | `REV-TASK-0172-001` | pass | F-001 spotless fixed；F-002 INJ 文档漂移 → F-010；F-003/F-004/F-005 P3/P2 accepted。                                                                                                                                                  |
| TASK-0177 | `REV-TASK-0177-001` | pass | V2 全量验证；F-001 HEALTHCHECK 由 TASK-0200/EV-0953 修复；F-003/F-004 文档由 TASK-0201 修复；F-002 ACL 写 → F-003；F-005 OCR probe → F-005。                                                                                          |
| TASK-0180 | `REV-TASK-0180-001` | pass | F-001 legacy 30m 看门狗 accepted（M05b 前决定）；F-002 application-test 注释 V1–V11 → F-010；F-003/F-004/F-005 P3 accepted。                                                                                                          |
| TASK-0197 | `REV-TASK-0197-002` | pass | F-001/F-002/F-003 fixed；`cross_task_blockers: X-001` 的旧 open 已由 `REV-TASK-0198-001` F-001/EV-0921 修复，并在 EV-0939 全量通过；front matter 的 open 是过期状态，不再是 open P1。F-004/F-005/F-006/F-008 accepted → F-010/F-005。 |
| TASK-0198 | `REV-TASK-0198-001` | pass | X-001 已 fixed（10e8bc8，EV-0921）；F-002/F-003/F-004/F-005/F-006/F-007/F-008 accepted；V14 锁归 M09（F-006）。                                                                                                                       |

没有任务级评审的任务（B8/B8fix/B11/O1a/O1b/docs 收口等）不触发本模块 `reviewed_task_reviews`
缺失；TASK-0200/TASK-0201 的产出由 EV-0953/EV-0952/EV-0960 与本次模块 review 直接覆盖。
TASK-0202 是 Product 修复任务，没有单独任务级 review（按 Lead 要求保持 12 个）；其 EV-0954..EV-0960
由本次模块 review 直接复核。

## 5. Findings

| ID    | 级别   | 状态      | 摘要                                                                        | Owner / due                                          |
| ----- | ------ | --------- | --------------------------------------------------------------------------- | ---------------------------------------------------- |
| F-001 | **P1** | **fixed** | AC-15 失败时间已补：API `failedAt` + Web `knowledge-failed-at` + E2E-M05-09 | builder-kb-index + builder-kb-web / fixed in b7b8c45 |
| F-002 | **P2** | **fixed** | AC-21 保留策略不追溯界面明示已补：`knowledge-retention-note` + E2E-M05-09   | builder-kb-web / fixed in b7b8c45                    |
| F-003 | P2     | accepted  | AC-20 ACL 写 p95 4.33–4.58s > 500ms（quota 行锁）                           | Lead + Architect / 用户最终验收前、M05b gate 前      |
| F-004 | P2     | accepted  | force=false 仍整本重解析，未只补缺失页                                      | builder-kb-index + Architect / M05b                  |
| F-005 | P3     | accepted  | 宿主 Tesseract IT 未跑；真实栈 full 用 OCR 关闭；M05 UI 无真实栈单列        | verifier + ops / M05b                                |
| F-006 | P2     | accepted  | V14 生产锁/维护窗口未量测；物理清理归 M09                                   | ops + Architect / M09                                |
| F-007 | P2     | accepted  | M04 F-008 生产 pass-through 继承                                            | builder-route / M09 前                               |
| F-008 | P2     | accepted  | 解析 180s/稳定码未按 ADR §3.3                                               | Lead + Architect / M05b                              |
| F-009 | P2     | accepted  | 重灌脚本大租户 ID 校验缺口                                                  | builder-kb-index / M05b/M09                          |
| F-010 | P3     | accepted  | ARCHITECTURE/THREAT-MODEL/application-test/ADR 状态文档漂移                 | Architect + Lead / M05b                              |
| F-011 | P3     | accepted  | AC 测试线索 ID 与真实测试类无可追溯映射                                     | Architect + Lead / M05b                              |
| F-012 | P3     | accepted  | TASK-0202 后未在 b7b8c45 重跑合同全量 Maven verify                          | verifier + Lead / M05b 或下次全量                    |

### 5.1 F-001（P1，fixed）AC-15 失败时间

b7b8c45 已把 `failedAt` 落到 API 与 UI：

- `KnowledgeDocumentResponse.java:33、58、91` 增加可选 `failedAt`（+08:00）；
- `KnowledgeJdbc.java:49、550-565` 以 `file_parse.finished_at` 为优先、`updated_at` 回退；
- `knowledge-detail.tsx:474` 显示 `knowledge-failed-at`；
- `contracts/openapi.yaml:5157` 声明 `failedAt`；
- `e2e/tests/m05.spec.ts:651-675` 的 E2E-M05-09 断言失败时间与 +08:00，且非 FAILED 文档不显示；
- EV-0954（KnowledgeFailedAtApiTest/契约/解析形态）、EV-0959（E2E 52/52）、EV-0960（docs/contract）
  均 exit 0、dirty=false。

结论：AC-15 的“失败时间”子句已满足，F-001 关闭。恢复/回退语义（失败后 ACL 变更是否污染
`updated_at` 回退值）由 EV-0954 的 API 断言与代码路径覆盖；后续若出现失败后 ACL 更新再改写
`updated_at` 的边界，作为 M05b/维护项复验。

### 5.2 F-002（P2，fixed）AC-21 保留策略界面明示

b7b8c45 已增加固定说明：

- `knowledge-detail.tsx:451` 的 `knowledge-retention-note`；
- 文案为“保留策略变更仅影响新文档和新的到期计算，不追溯历史文档”；
- E2E-M05-09（`e2e/tests/m05.spec.ts:651-675`）在失败文档与成功文档详情均断言该说明；
- EV-0955（web 597/coverage）、EV-0959（E2E 52/52）通过。

结论：AC-21 的“界面明示”子句已满足，F-002 关闭。

### 5.3 F-012（P3，accepted）证据范围

TASK-0202 修复后没有在 `b7b8c45` 重跑合同全量 Maven `verify`；EV-0939（API 772 + worker 579 +
web 597）在 `c1bd0e7`。修复面由 EV-0954（API 定向 3 类）、EV-0955（web 全量 597/coverage
87.19/80.73）、EV-0956 typecheck、EV-0957 lint、EV-0958 a11y 18/18、EV-0959 E2E 52/52 覆盖，
worker 未改。风险是其他 API 回归未在新提交重跑；建议 M05b 或下次全量补一次 `b7b8c45` 之后的
合同 test/coverage。该残余不改变本轮 AC-15/21 修复与用户可见结论。

## 6. 残余与未验证

- **M05b**：AC-10..14、AC-09 连接器段、AC-15/16/21 连接器段、AC-20/22 整条复验均未做；M05 不据此
  判整条 pass。S3 连接器/凭据/注入 harness 归 M05b。
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
  AC-15/21 的修复结论，但应在 Product 预验收/M05b 前收口。
- **证据范围**：F-012（TASK-0202 后未在 b7b8c45 重跑合同全量 Maven verify）为 P3 accepted；EV-0954
  API 定向 + EV-0955 web 全量 + EV-0959 E2E 52 覆盖修复面，worker 未改。建议 M05b 或下次全量补一次
  `b7b8c45` 之后的合同 test/coverage。
- **证据卫生**：EV-0908 无 commit 字段（TASK-0173 的中间证据），本评审未把它当权威通过证据；
  TASK-0173 的权威 perf 证据是 EV-0922（exit 1，residual）与 V2 EV-0947（exit 1，residual）。
  EV-0927/0928/0945/0949/0950 为失败尝试，保留但不作通过证据。EV-0919 是 TASK-0197 的负向探针、
  不在 TASK-0197.evidence 列表，已从 `reviewed_evidence` 移除。

## 7. Gate 预期与下一步

- 本评审定稿后运行 `riccon gate check --module M05`：**exit 0，0 errors，3 warnings**（EV-0571/EV-0908
  为历史 manual 证据不能建立 pass；1 条 legacy evidence 无 commit attestation），技术 gate **PASS**。
- `dod_evidence.docs` 使用 **EV-0960**（TASK-0202，clean worktree、`git diff --check && grep ... &&
contract validate`，无 `; echo`、exit 0、dirty=false），未绑定会被 strict masking 拒绝的
  EV-0952/EV-0953；三者仍保留在 `reviewed_evidence` 作为 V14/Healthcheck 历史证据。
- Product 预验收 **pass**（with residual R-M05-01..05；R-M05-06/07 fixed，`2554b97`/`docs/ACCEPTANCE.md`）；
  R-M05-06/R-M05-07 已 fixed。模块 done 可在本技术 gate pass 后由 Lead 记录；用户最终验收仍
  pending，用户 `accepted` 前不启动 M05b。
- Lead 需要：
  1. 将 `product_status=pass`（with residual）与模块 done 记录到 `.riccon`（按 Lead 流程）；
  2. 用户最终验收时显式接受 ACL 写 residual（F-003），或验收前修复；DEC-0038 不能替代；
  3. M05b 前处理 F-004/F-005/F-006/F-009/F-010/F-011/F-012 与 M23/M24 继承残余；
  4. M05b 开工前另批 53h 预算（DEC-0035），并复验 AC-09/10..14/19/20/22 的连接器段。
- 注意：技术 review pass / gate pass **不能替代**用户最终验收；Product 预验收已 pass，用户最终
  验收仍 pending，用户 `accepted` 前不启动 M05b。
- 本评审不修改任务/模块状态、不提交 git；`docs/ACCEPTANCE.md` 的 Product 结论由 Product/Lead 记录。
