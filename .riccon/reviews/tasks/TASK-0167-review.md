---
review_id: REV-TASK-0167-001
module_id: M05
task_id: TASK-0167
reviewer:
  agent: riccon/verifier
  session: ses_df999388cffeefjx0j5vKLR7TQ
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 8acffe99a06a6f378bae6db2929abcccd82fa167
  - 6a70b9bdae64622abceee2d94b126e9f8b10af94
  - f6ccf9c79a8a6fd40d31874704a2b8f523fef213
  - be1155d3078a874570cb2c259f5922bc4d290775
  - 3288af40385cfce9340bcedd0a7ddbc50f7e6a64
  - 3b7a3ab660e0e85d55e066a06c9803b7b0644f22
head_commit: "3b7a3ab660e0e85d55e066a06c9803b7b0644f22"
evidence: [EV-0824, EV-0826, EV-0828]
reviewed_evidence: [EV-0824, EV-0826, EV-0828]
dod_note: "任务级 DoD：acceptance/build/tests/review/evidence 已覆盖；coverage/e2e/a11y/security/perf/migration 属 M05 模块 gate，不在任务评审判。"
dod_not_applicable: [coverage, e2e, a11y, security, perf, migration]
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "TASK-0169（M05-B7）实现前 / M05 gate 前"
    title: "parse_engine/parse_warnings/parse_summary 无持久化写入者，worker ParseSummary 与 API/OpenAPI 的 sheet 摘要字段不一致"
    summary: >-
      B4 在 `XlsxDocumentParser.parse` 返回 `ParsedContent(parseEngine=POI, warnings, summary)`，但 worker 侧
      `KnowledgeIndexService.handle`（index/service/KnowledgeIndexService.java:136-146）只用 `chunks`，
      `KnowledgeRepository.markIndexed`（index/repository/KnowledgeRepository.java:123-134）只更新
      status/indexed_at；`grep -rn "parse_warnings\|parse_summary" apps/worker/src/main/java` 只有注释与
      domain 文档，没有任何 UPDATE/INSERT。API 侧 `KnowledgeJdbc.parseSummary`（607-634）按
      `KnowledgeParseSummary/KnowledgeParseSheetSummary` 反序列化，后者要求 `name/cellRange/empty`
      （api/.../KnowledgeParseSheetSummary.java:14-15；contracts/openapi.yaml:5198-5225），而 worker
      `ParseSummary.SheetSummary` 的字段是 `sheetName/rowCount/columnCount/empty/indexedRange/skippedRange`
      （parse/domain/ParseSummary.java:43-44）；若 B7 直接 Jackson 序列化 worker record，API 读到
      缺 `name` 会触发 NPE/反序列化失败。TASK-0169 的 acceptance 也没有“写回 parse_* 元数据”条目。
      结论：AC-01/02 的“工作表名/行列范围/已索引与未索引范围/警告”当前没有从解析器到 API 的落库链路。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/parse/service/XlsxDocumentParser.java:234-241；
      apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:136-146；
      apps/worker/src/main/java/com/riccon/worker/index/repository/KnowledgeRepository.java:123-134；
      apps/api/src/main/java/com/riccon/api/knowledge/persistence/KnowledgeJdbc.java:607-634；
      apps/api/src/main/java/com/riccon/api/knowledge/domain/KnowledgeParseSheetSummary.java:14-15；
      apps/worker/src/main/java/com/riccon/worker/parse/domain/ParseSummary.java:19-44；
      contracts/openapi.yaml:5181-5225；.riccon/tasks/TASK-0169.json（acceptance 无 parse_* 写回）。
    accepted_reason: >-
      B4 的 write_scope 只有解析器与单测，写库不属本任务；当前没有用户可见数据，风险尚未落地。
      但这是 AC-01/02 详情的数据链路缺口，必须在 B7 验收中补齐并由模块 review 复验；本 finding 用来
      把缺口显式转交 Lead/Architect（补 B7 acceptance 或指定 B3），不是把 B4 代码判为缺陷。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-excel"
    due: "M05 gate 前（建议 TASK-0167 fix）"
    title: "共享字符串表缺少可选 count/uniqueCount 属性时，合法 XLSX 被误判为 KB_NO_TEXT"
    summary: >-
      `sharedStringValue`（XlsxDocumentParser.java:948-963）用 `index >= sharedStrings.getCount()` 做边界，
      但 POI 的 `ReadOnlySharedStringsTable.getCount()` 只返回 XML `count` 属性（POI 5.5.1
      ReadOnlySharedStringsTable.java:246-247），而 `count`/`uniqueCount` 在 ECMA-376 中都是可选属性。
      当合法生成器省略该属性时，`strings` 已读入但 `getCount()==0`，所有 `t="s"` 单元格被判为非法索引并跳过；
      整本为字符串的工作簿因此抛 `KB_NO_TEXT`（FAILED），reindex 也无法恢复。独立探针已复现：
      baseline `A1:A3` 正常，去掉 `count/uniqueCount` 后 `ParseException: xlsx workbook has no indexable text`
      （.riccon/evidence/TASK-0167/probe-2026-11-23-sharedstrings-rowref.log）。Excel/WPS/LibreOffice 均写
      count，所以现实触发面小，但这是“合法输入被错误判失败”，不是安全边界内的 fail-closed 设计选择。
      建议改为 `getUniqueCount()` 回退或 `getItemAt` 的 `IllegalStateException` 保护，并补回归用例。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/parse/service/XlsxDocumentParser.java:948-963；
      POI 5.5.1 `ReadOnlySharedStringsTable.java:208-211、246-247`；
      .riccon/evidence/TASK-0167/probe-2026-11-23-sharedstrings-rowref.log（probe 脚本同目录 .jsh）；
      EV-0826/EV-0828 的 25 个用例无此场景。
    accepted_reason: >-
      只影响省略可选属性的非主流 OOXML 生成器；失败为 fail-closed（KB_NO_TEXT），不产生脏索引、不泄露原文；
      修复与回归成本低（<10 行 + 1 用例）。按 P2 accepted 记录，M05 gate 前由 builder 修复或由 Lead 明确接受。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-excel"
    due: "M05 gate 前（建议 TASK-0167 fix）"
    title: "行元素缺少可选 r 属性时行号坍缩，cellRange/rowCount/定位错位"
    summary: >-
      `SheetXmlHandler.startRow`（XlsxDocumentParser.java:863-877）在 `r` 缺失时把 0 基 `nextRowNum`
      当成 1 基值再减一：第 1 行得到 row 0，第 2 行 `parsed=1` 又得到 row 0，后续行全部坍缩到同一行。
      独立探针用三行文本（first/second/third）、去掉 `<row r="N">` 属性后，解析结果为单段
      `cellRange=A1:A1`、`rowCount=1`，内容仍为三行（.riccon/evidence/TASK-0167/probe-2026-11-23-sharedstrings-rowref.log）；
      期望是 `A1:A3`、3 行。`r` 在 OOXML 中同样可选，Excel/WPS/LibreOffice 都写，但最小生成器可能省略。
      该 bug 不丢文本，但会让引用区域指向错误单元格，直接影响 M05-AC-02 的“单元格/区域可定位”。
      修复：`rowReference == null ? nextRowNum + 1 : parseIndex(rowReference)`，并补缺 r 回归用例。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/parse/service/XlsxDocumentParser.java:863-877；
      .riccon/evidence/TASK-0167/probe-2026-11-23-sharedstrings-rowref.log；
      XlsxDocumentParserTest.java:47-87（现有用例全部由 POI 生成，必带 r，未覆盖此边界）。
    accepted_reason: >-
      现实文件几乎都带 r；内容不丢失，但定位会错，属数据质量问题而非泄露。作为 AC-02 的残余由 M05-V1
      回归覆盖；建议 M05 gate 前用一行修复关闭，若 Lead 接受残余则必须在模块 review 的 AC-02 标注。
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "M05 gate 前"
    title: "ADR §3.3 的 50 MB 拒绝码与 180 s 文档超时未按 ADR 落地"
    summary: >-
      ADR-0023 §3.3（95、102 行）要求单文件 50 MB “解析前拒绝 KB_PARSE_RESOURCE_LIMIT”、文档级 180 s
      “KB_PARSE_TIMEOUT，文档 FAILED”。实际：`S3FileContentReader.read`（parse/service/S3FileContentReader.java:64-68）
      超限抛 `RICCON-FILE-413`（WorkerErrorCodes.FILE_TOO_LARGE），不是 KB_PARSE_RESOURCE_LIMIT；
      `grep -rn "PARSE_TIMEOUT" apps --include=*.java` 只有常量与 ParseDomainModelTest，没有任何写入方；
      唯一兜底是 outbox 的 30m/65m lane watchdog（application.yml:110-113）。内存侧仍受 50MB 上传、
      64MB entry、8M 字符、5k 段落约束，不会 OOM；但认证租户可用一个 50MB 对抗性工作簿占用解析 lane
      长达 30 分钟，缺少 ADR 承诺的 180 s 快速失败。上传层 400/415 拒绝属 B3/M02，本 finding 只指解析
      资源码与超时。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:95、102；
      apps/worker/src/main/java/com/riccon/worker/parse/service/S3FileContentReader.java:64-68；
      apps/worker/src/main/java/com/riccon/worker/run/domain/WorkerErrorCodes.java:34；
      apps/worker/src/main/resources/application.yml:110-113；
      grep 结果：PARSE_TIMEOUT 无生产写入方。
    accepted_reason: >-
      上传层已 fail-closed 限 50MB，解析有字符/段落/entry 上限，不会 OOM；lane watchdog 提供 30m/65m 上限，
      风险是可用性/租户公平而非数据泄露。需要 Lead/Architect 决定 180 s 超时由 B4（解析器内部 deadline）
      还是 B7/B1（事件级）承担，并统一 50MB 的稳定错误码；M05 gate 必须给出裁决。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    title: "B4 使用临时 worktree 取证例外并两次发生 EV ID 重编号；硬化提交标题误标 m05-b3；测试文件事后补录写范围"
    summary: >-
      (1) EV-0824 的 cwd 是 `TMPDIR/riccon-b4-evidence-wt`（detached f6ccf9c），不是主工作树；该 worktree
      内本地证据为 EV-0822/EV-0823，回灌主树时 EV-0823 因 ID 被 TASK-0166 占用重编号为 EV-0824，
      command/commit/output_sha256（55e4314…）与日志字节完全一致。(2) EV-0828 同样来自
      `riccon-b4-evidence2-wt`（detached 3b7a3ab），本地 ID EV-0827→主树 EV-0828，哈希 7a5b03e… 一致。
      (3) B4 的流式硬化与单格上限修复（6a70b9b，60 行 parser + 19 行 test）被并发 amend 进标题为
      “m05-b3”的提交；8acffe9 提交时任务卡 write_scope 只含 parser，测试文件是事后由 Lead 在
      scope_amendment 补录。worktree 内 f6ccf9c 的任务卡 `baseline_commit=5c230e4…` 与主树最终
      `dbef7ba` 也不一致（同类归因债务）。代码完整性已核对：worktree 两文件与 HEAD 除空白外 token 相同，
      未发现伪造证据；但这是 strict evidence 的已批准例外 + 提交归因债务，需在 M05 gate 前归档口径。
    evidence: >-
      .riccon/evidence/TASK-0167/EV-0824.json（cwd/commit/output_sha256）；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/riccon-b4-evidence-wt/.riccon/evidence/TASK-0167/EV-0823.json；
      .riccon/evidence/TASK-0167/EV-0828.json（cwd/commit/output_sha256）；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/riccon-b4-evidence2-wt/.riccon/evidence/TASK-0167/EV-0827.json；
      /private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/riccon-b4-evidence-wt/.riccon/tasks/TASK-0167.json（baseline_commit=5c230e4…）；
      git show --stat 8acffe9 6a70b9b 3288af4 3b7a3ab；git show 8acffe9:.riccon/tasks/TASK-0167.json（write_scope 无测试文件）。
    accepted_reason: >-
      Lead 已批准 worktree 例外并在任务卡记录 renumber/归因说明；EV 的命令、提交、哈希与日志可审计，
      代码树与 HEAD token 等价，未发现结果造假。影响限于流程与审计可读性，按 P3 accepted 记录，
      M05 gate 前由 Lead 收口（后续任务默认回主树干净窗口取证）。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    title: "end_commit=8acffe9 不包含 B4 最终硬化/格式化代码，归因仅靠 commit_attribution_note"
    summary: >-
      TASK-0167.end_commit 仍为 8acffe9（初版），而 B4 最终代码在 6a70b9b（硬化）与 3b7a3ab（spotless）中；
      任务卡的 `commit_attribution_note` 与 scope_amendment.reason 已说明“评审以 HEAD 与 EV 为准”，
      人工阅读足够，但机器字段 end_commit 指向的提交不包含单格上限修复，后续审计/复验若只读字段会误判。
      建议把 end_commit 更新为 3b7a3ab（B4 最后代码提交）或新增 end_commit_note 说明 8acffe9 只是初版。
    evidence: >-
      .riccon/tasks/TASK-0167.json（end_commit=8acffe9、commit_attribution_note、scope_amendment）；
      git diff --stat 8acffe9..3b7a3ab -- apps/worker/src/main/java/com/riccon/worker/parse/service/XlsxDocumentParser.java；
      git show --stat 6a70b9b。
    accepted_reason: >-
      Lead 已在任务卡用文字记录真实归因，且 EV-0824/0828 的 commit 与 HEAD token 等价；不影响本次代码结论。
      仅建议在 gate 前把机器字段改到最终提交，避免未来审计误读。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "TASK-0176（M05-V1）/ M05 gate 前"
    title: "任务验收引用的 IT-KB-101/102/103 在仓库不存在且不在 B4 write_scope；“无 500/半索引”未做服务级验证"
    summary: >-
      TASK-0167.acceptance[1] 要求“IT-KB-101/102/103 通过；无 500/半索引”，但 `grep -rn "IT-KB-101\|IT-KB-102\|IT-KB-103"`
      在 apps/e2e 无任何实现；ADR-0023 §11.2/§3.5 把这些集成用例归 M05 gate 的 verifier 验证，TASK-0176
      （M05-V1，write_scope 含 apps/**/test/**）才是 owner。B4 单测覆盖了 parser 层的拒绝/FAILED 分离
      （corrupt/encrypted/resource/NO_TEXT），但 service 层 markFailed、无半索引、上传层 400/415 边界
      未在任务级验证。建议 Lead 把该条改写为“parser 层错误码分离通过；IT-KB-101/102/103 由 TASK-0176 关闭”，
      避免任务卡长期保留不可满足的验收。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:125-131、848-853；
      .riccon/tasks/TASK-0176.json（M05-V1 planned，write_scope apps/**/test/**、e2e/**）；
      grep 无 IT-KB-101/102/103 实现；XlsxDocumentParserTest.java:219-226、510-595（parser 层错误码）。
    accepted_reason: >-
      B4 的 write_scope 不可能创建这些 IT；parser 层等价用例已通过，集成由 M05-V1 承接，风险有明确 owner。
      作为任务 AC 措辞债务记录，不阻断 B4 代码交付。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-excel"
    due: "M05b 前 / TASK-0176 回归时补齐"
    title: "测试覆盖缺口：1904、ZIP ratio/file-count 炸弹、50 表边界、缺 r/count 回归、纯列超限、XXE、服务级 FAILED/半索引"
    summary: >-
      25 个用例已覆盖：多表/空表/隐藏表/隐藏行列、公式缓存与未缓存、日期/布尔/错误值/合并单元格、
      行/列/表/字符/段落上限、ZIP entry-size 超限、空字节/CSV/伪装 ZIP/加密 OLE2/旧版 OLE2/无文本、
      警告与摘要不含原文。未覆盖：(a) 缺 `r`、缺 `sst count`（F-002/F-003 的回归）、(b) 1904 日期窗口、
      (c) ZIP 压缩比/条目数两条炸弹路径（只测了 maxEntrySize）、(d) maxSheets=50 的真实边界、
      (e) 所有单元格都在列上限之外的“有内容但零段落”组合（当前会 NO_TEXT，而不是 INDEXED+skippedRange）、
      (f) XXE/DOCTYPE 夹具、(g) FileParseService/KnowledgeIndexService 的 FAILED 与无半索引集成。
      其中 (e) 是设计边界：parser 的 `state.segments().isEmpty()`（230-233）在“有内容但全部被列/表上限排除”
      时抛 NO_TEXT，与 ADR“有内容但被截断 → INDEXED + warnings”不一致；建议至少加一条夹具锁定预期。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/parse/service/XlsxDocumentParserTest.java（25 个 @Test）；
      apps/worker/src/main/java/com/riccon/worker/parse/service/XlsxDocumentParser.java:230-233、1255-1267、1297；
      EV-0826/EV-0828 surefire：Tests run: 25, Failures: 0, Errors: 0, Skipped: 0。
    accepted_reason: >-
      主矩阵覆盖充分，缺口集中在非主流格式属性、ZIP 炸弹的另外两条路径与集成层；M05-V1 会补 IT-KB-101/102/103/116，
      其中 116 覆盖 ZIP 炸弹/资源上限。F-002/F-003 修复时应顺带补缺 r/count 回归；(e) 建议由 Lead 决定
      期望后加一条单测。
created_at: "2026-11-23T11:36:00+08:00"
---

# REV-TASK-0167-001：任务 TASK-0167（M05-B4 Excel 流式解析/区域引用/警告）独立评审

## 结论

**verdict: pass（任务级）**。B4 的交付是诚实的：生产解析器确实改为 `XSSFReader` + 自定义 SAX（无 `XSSFWorkbook`、
无 `FormulaEvaluator`），按 20 行行带输出 `sheetName`/`cellRange`，隐藏表跳过、空表标记、公式只读缓存、
上限/警告/失败错误码齐全；25 个单测在 HEAD 代码上独立复跑为 25/0/0/0。没有 P0/P1。

但有 4 个 P2 accepted：两个是合法 OOXML 可选属性缺失导致的边界 bug（F-002 误判 `KB_NO_TEXT`、F-003 行号坍缩），
一个是跨任务的 `parse_engine/parse_warnings/parse_summary` 持久化与 API shape 缺口（F-001），
一个是 ADR §3.3 的 50 MB 错误码/180 s 超时未落地（F-004）。任务 AC 第 2 条的 IT-KB-101/102/103 不在 B4
write_scope，未实现，按 TASK-0176 延期并保留残余（F-007）；因此 AC 对账写 **pass (with residual)**，不写成
无残余 pass。

本评审是**任务级技术验收**，不是 M05 模块评审、Product 预验收或用户验收；AC-01/02 的“详情显示/引用 UI/E2E”
仍必须由 B7、B8、B9 与 M05-V1/V2 在模块 gate 复验。

## 1. 评审对象与权威依据

- 任务：TASK-0167，模块 M05，高风险（解析/资源上限），owner `riccon/builder-kb-excel`，状态 review。
- 代码提交：8acffe9（初版）、6a70b9b（硬化/测试增量，标题误为 m05-b3）、f6ccf9c（任务卡/归因）、
  3288af4/3b7a3ab（spotless，style-only）、be1155d（verifier EV-0826 的代码基线）。
- EV：EV-0824（builder，worktree，25 tests）、EV-0826（verifier，主树，25 tests）、EV-0828（builder，
  worktree 2，spotless + 25 tests）。最新 B4 代码提交为 3b7a3ab；评审时仓库 HEAD 为 c819bb7（仅 .riccon
  变更），B4 两文件与 3b7a3ab token 相同（见 §3）。
- 依据：ADR-0023 §3.1–§3.5、§11.2、§14.2；PRODUCT M05-AC-01/02/15；contracts/openapi.yaml 1.4.0-m05
  （`parseEngine`/`warnings`/`parseSummary`/`cellRange`/引用字段）；`ParsedContent/Segment/Chunk`、
  `ParseSummary`、`ParseWarningCodes`、`ParseException`（B1 领域模型）；`TextChunker`（B5，不在本任务）。

## 2. 产物静态审查（先读产物，不继承 builder 结论）

### 2.1 流式链路（核心风险 1）

- `parse`（XlsxDocumentParser.java:171-185）：先校验容器签名，再把字节写入 `Files.createTempFile`
  （`stage`，395-403），用 `OPCPackage.open(File, PackageAccess.READ)` 打开，`finally` 删除临时文件。
  落盘后 POI 走 `ZipSecureFile`/`ZipFileZipEntrySource` 的随机访问路径，工作表 XML 按需流式读取，
  避免 `open(InputStream)` 的整包展开；临时文件默认 0600。
- 工作簿元数据：`readWorkbookInfo`（256-295）只用 StAX 读 workbook.xml 的 `sheet@name/state` 与
  `workbookPr@date1904`，不为了查隐藏状态载入 DOM；`parsePackage`（198-242）用 `XSSFReader.SheetIterator`
  顺序读表。
- 单表解析：`parseSheetXml`（310-322）用 `XMLHelper.newXMLReader()`（POI 5.5.1 已设 secure-processing、
  disallow-doctype、关闭 external entities/DTD）+ 自定义 `SheetXmlHandler`。自定义 handler 只认
  `<row>/<c>/<f>/<v>/<is>/<t>/<rPh>`，内存只保留单行/单格/单行带状态。
- 生产代码没有任何 `XSSFWorkbook`/`FormulaEvaluator`/`XSSFSheetXMLHandler` 使用（grep 只命中注释）；
  测试夹具里的 `FormulaEvaluator` 仅用于生成缓存值，不参与解析。
- 内存：ZIP 上限 64 MB entry / 256 MB text / ratio 0.01 / 10k entries（93-103、470-475），与 ADR §3.1 一致；
  单文档 8M 字符、5k 段落、单表 20k 行/200 列/50 表、5k 段落均有上限（Limits，487-519）。
  `ReadOnlySharedStringsTable` 会一次性载入共享字符串（POI 设计），其总量受 256 MB text 上限约束；
  与 50 MB 上传上限叠加，最坏内存仍低于“按行数增长”的 DOM 方案。builder 报告的 16 MB/230k 行
  `-Xmx192m` 旁证未落盘为 EV，本评审未复现（见 §8），不作为通过依据。

### 2.2 定位与摘要（核心风险 2）

- 行带：`flushRow`/`flushBand`（1377-1468）以 `firstContentRow + bandIndex*bandRows` 划分 20 行带，
  `cellRange` 由 `CellReference.convertNumToColString` 生成（1528-1534），如 `B2:D10`；段 `locatorType=SHEET`，
  `sheetName` 原样、`cellRange` 为行带矩形，不伪造单元格。
- 整表/截断范围：`ParseState.addSheet`（671-679）把 `sheetName!range` 写入 `indexedRanges/skippedRanges`；
  `SheetCollector.summary`（1331-1346）给出 `rowCount/columnCount/empty/indexedRange/skippedRange`。
  AC-02 需要的 `sheetName` + `cellRange` + 命中文本块由 `ParsedSegment` 直接携带。
- 空表：`hasContent=false → empty=true`，不生成段，不影响其他表；整本无段才 `NO_TEXT`（230-233）。
- 隐藏表：`info.hiddenSheets().getOrDefault(sheetName,FALSE)` 为真时 `continue`，不调用 `parseSheetXml`，
  只写 `HIDDEN_SHEETS_SKIPPED:<n>`；摘要不含隐藏表；测试 118-142 断言输出与摘要不含 `hidden-marker-绝密`。
- 公式：只读 `<v>`；`cellHasFormula && !cellHasValue` → `FORMULA_NOT_CACHED`（904-917、1294-1303）；
  没有 `FormulaEvaluator`。
- 合并单元格：只读锚点（Excel 本身只在锚点存值），测试 314-330 断言出现一次。
- 隐藏行列：不检查 `zeroHeight`/`column hidden`，照常抽取；测试 335-357。
- 日期/数值/布尔/错误：数值 `BigDecimal.toPlainString`；日期样式走 `DateUtil.getLocalDateTime` + 固定
  `yyyy-MM-dd HH:mm:ss`；布尔 `TRUE/FALSE`；`t="e"` 跳过（1009-1039、966-1007）。与 ADR §3.2 一致
  （ADR 文字提到 `DataFormatter(Locale.CHINA)`，实现用更具体的类型渲染规则，行为符合表内细则）。

### 2.3 上限与配置（核心风险 4）

- 行/列/表/字符/段落上限均在 `SheetCollector.cell`（1246-1279）与 `flushRow`/`flushBand` 执行：
  超行 → `ROWS_TRUNCATED:<sheet>` + `skippedRange`；超列 → `COLUMNS_TRUNCATED:<sheet>`；超表 →
  `SHEETS_TRUNCATED` 且后续表只扫范围不建段；超字符/段落 → `CHARS_TRUNCATED`/`SEGMENTS_TRUNCATED`。
- 单格超长：`appendCapped`（1049-1063）按 `maxChars` 截断并标 `cellTruncated`，`noteCellTruncated`
  记 `CHARS_TRUNCATED`，防止 SAX 回调先撑爆堆；6a70b9b 的硬化与该测试（XlsxDocumentParserTest.java:253-267）
  是初版之后补上的真实修复。
- 配置：Spring 构造器 `@Value("${riccon.kb.xlsx.band-rows:20}")` …（137-146），默认值与 ADR 一致；
  用 `StandardEnvironment` + `RICCON_KB_XLSX_BAND_ROWS=7 / RICCON_KB_XLSX_MAX_ROWS=123` 探针验证
  `@Value` 的属性名可被环境变量解析（未启动完整 Spring 上下文）。`application.yml` 没有显式
  `riccon.kb.xlsx.*` 项，但默认值在 `@Value` 内，env override 可用。`Limits` 对非法配置抛
  `IllegalArgumentException`（490-509），测试 193-214 覆盖。

### 2.4 失败矩阵（核心风险 5）

| 场景                                         | 实现                                                                      | 错误码/警告                                         | 证据                         |
| -------------------------------------------- | ------------------------------------------------------------------------- | --------------------------------------------------- | ---------------------------- |
| 空字节/非 ZIP/CSV/伪装 ZIP                   | `validateContainer` 330-348、`translate` 424-433                          | `KB_PARSE_CORRUPT`（retryable=false）               | 测试 219-226、509-524        |
| 加密 OLE2（EncryptionInfo/EncryptedPackage） | `looksEncrypted` 356-364                                                  | `KB_PARSE_ENCRYPTED`（false）                       | 测试 529-541                 |
| 旧版 .xls（OLE2 无加密流）                   | `validateContainer`                                                       | `KB_PARSE_CORRUPT`（false）                         | 测试 546-555                 |
| ZIP entry 超限                               | POI `ZipSecureFile` 静态上限 + `isResourceLimit` 441-453                  | `KB_PARSE_RESOURCE_LIMIT`（false）                  | 测试 560-581                 |
| 公式无缓存                                   | `formulaNotCached`                                                        | `FORMULA_NOT_CACHED`                                | 测试 147-171                 |
| 隐藏表                                       | `continue` + 计数                                                         | `HIDDEN_SHEETS_SKIPPED:<n>`                         | 测试 118-142                 |
| 完全无文本                                   | `segments.isEmpty()`                                                      | `KB_NO_TEXT`（false）                               | 测试 583-595                 |
| 服务层映射                                   | `FileParseService.handle` 133-139、`KnowledgeIndexService.handle` 153-158 | `markFailed`，无半索引（chunks 只在成功后 replace） | 静态审查；服务集成未跑（§8） |

`isResourceLimit` 的匹配串与 POI 5.5.1 实际消息核对过：`Zip bomb detected!`、`MAX_FILE_COUNT`、
`potentially malicious`、`ZIP entry size is too large` 均存在（`ZipSecureFile.MAX_FILE_COUNT_MSG`、
`ZipArchiveThresholdInputStream`），entry-size 路径有测试；ratio/file-count 路径未测（F-008）。

### 2.5 代码质量与注释

- 类/方法/参数均有中文 Javadoc（ADR §3 依据、安全边界、异常语义），符合 `docs/CODING-STANDARDS.md`
  的中文注释要求；无 TODO/FIXME；spotless 已在 3b7a3ab 通过（EV-0828）。
- 未发现吞异常：`translate` 会区分资源限制/损坏，ParseException 透传；临时文件 finally 删除；
  `deleteQuietly` 失败只 WARN。`stage()` 在 `Files.write` 失败时不会删除已创建的半文件（P3 级细节，
  未单列 finding；见 §9）。

## 3. 证据审计（EV 与代码/哈希/提交核对）

### 3.1 EV-0824（builder 初版取证）

- 记录：commit `f6ccf9c…`，dirty=false，exit 0，cwd `TMPDIR/riccon-b4-evidence-wt`，command 为
  `LOCK=… ./mvnw -q -T1C -pl apps/worker -am test -Dtest='XlsxDocumentParser*Test' -Dsurefire.failIfNoSpecifiedTests=false`。
- 日志 SHA-256 `55e4314b…` 与记录一致；worktree 内 `surefire-reports/…XlsxDocumentParserTest.txt` 显示
  **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0**，时间 11:12，与 EV 时间一致。
- worktree 在 f6ccf9c，其 B4 两文件与主树当前 HEAD token 相同（见 §3.4）；worktree 只有 `.riccon`
  变更，Riccon 的 dirty 定义忽略 `.riccon`，dirty=false 诚实。
- 发现 worktree 本地 ID 为 EV-0822/EV-0823；EV-0824 是 EV-0823 的回灌重编号（同命令/同哈希）。见 F-005。

### 3.2 EV-0826（verifier 独立定向复跑）

- 主工作树在 `be1155d`（B4 代码与 3b7a3ab token 相同），`riccon evidence run --agent riccon/verifier`，
  exit 0、dirty=false、15.0 s；日志 SHA-256 `64e9ebd3…` 与记录一致。
- 主树 `surefire-reports/…XlsxDocumentParserTest.txt` 显示 **25/0/0/0**（4.014 s），证明 B4 测试类真实执行，
  不是 `failIfNoSpecifiedTests=false` 的零测试假绿。
- 该 EV 命令与任务卡/合同 `test` 的 Java 部分一致（worker 模块定向），不含 `pnpm` 部分；模块 gate 仍需
  `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage` 全量（见 §8）。

### 3.3 EV-0828（spotless + 测试，最新）

- 记录：commit `3b7a3ab…`（含 3288af4），dirty=false，exit 0，cwd `TMPDIR/riccon-b4-evidence2-wt`；
  command = `spotless:check`（apps/worker）+ `test -Dtest='XlsxDocumentParser*Test'`；日志 SHA-256 `7a5b03e5…` 一致。
- worktree surefire 显示 **25/0/0/0**（5.849 s）；worktree 的 B4 两文件与主树 HEAD token 相同；
  `grep -c "@Test"` = 25。
- EV-0828 后 B4 两文件已通过全 worker spotless；主树全 worker spotless 仍可能因 B3 WIP 失败，但 B4 交付
  文件本身干净（Lead 2026-11-23 说明，与本次审计一致）。
- worktree 本地 ID 为 EV-0827，回灌主树重编号为 EV-0828（同哈希）。见 F-005。

### 3.4 最新代码覆盖

- `3288af4`/`3b7a3ab` 仅格式化 B4 两文件；`tr -d '[:space:]'` 后两文件在 be1155d、3b7a3ab、当前 HEAD
  的 SHA-256 相同，证明 EV-0826/0828 覆盖的代码与最新 HEAD 语义一致（25 个 @Test 未变）。
- 当前仓库 HEAD（评审时 c819bb7）在 3b7a3ab 之后只有 `.riccon` 任务卡/证据提交；B4 生产代码未被后续修改。

### 3.5 EV-0827 说明（不采用）

- EV-0827（11:28:56，exit 1）是 verifier 在主树误触发的探测：B3 的未提交 P1 修复（`AgentContextJdbc`
  的 `appendAclPredicate` 参数个数不一致）导致 worker 编译失败，与 B4 无关。
- 已从 `TASK-0167.evidence` 移除引用（任务卡当前为 `[EV-0824, EV-0826, EV-0828]`），文件保留供审计；
  **不**列入 reviewed_evidence，不据此判 B4 fail。

## 4. AC 对账（任务级）

| #   | 验收原文                                                             | 结论                      | 依据                                                                                  | 残余                                                                                                                     |
| --- | -------------------------------------------------------------------- | ------------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| 1   | XSSFReader 流式、工作表×20 行行带、隐藏表/公式/合并单元格、上限/警告 | **pass**                  | §2.1–2.3；EV-0826/EV-0828 25/0/0/0；20k 行用例（XlsxDocumentParserTest.java:486-504） | F-002/F-003 是可选的 OOXML 属性边界（非 Excel 默认产物）；F-008 覆盖缺口                                                 |
| 2   | 拒绝与 FAILED 分开；IT-KB-101/102/103 通过；无 500/半索引            | **pass（with residual）** | §2.4；parser 层 corrupt/encrypted/resource/NO_TEXT 分离有单测；service 映射静态核对   | **IT-KB-101/102/103 未实现且不在 B4 write_scope，延期 TASK-0176**；“无 500/半索引”只有静态/单测证据，无服务级集成；F-007 |

**M05-AC-01/02（模块级）不在本任务判 pass**：AC-01 要求详情显示可见表名/行列范围/空表标记/已索引与未索引
范围，AC-02 要求引用详情显示 `工作表!区域` 与命中块；B4 只提供 `ParsedContent` 的 segments/warnings/summary，
落库与 UI 分别依赖 B7/B8/B9。F-001 是这条链路的缺口，模块 review 必须以 B7/B8 的 API/UI 证据复验，
不能用 B4 单测替代。

## 5. Findings

按严重级别排序；全部为 accepted（无 open，无 P0/P1）。详见 front matter 的 owner/due/accepted_reason。

### F-001（P2）parse_engine/parse_warnings/parse_summary 无持久化写入者，worker/API/OpenAPI shape 不一致

- 现象与影响：B4 正确产出 `ParseEngine.POI`、白名单 warnings、`ParseSummary`，但 worker 索引链路只用
  `chunks`；`KnowledgeRepository.markIndexed` 不接收元数据。AC-01 的“已索引/未索引范围/隐藏表/空表”和
  AC-02 的“区域”无法从 DB/API 读到。
- 额外风险：worker `ParseSummary.SheetSummary` 字段名（`sheetName/rowCount/columnCount/skippedRange`）与
  API `KnowledgeParseSheetSummary`（`name/cellRange/indexed/indexedRange`）不一致；若 B7 直接序列化会
  反序列化失败。B7 需要显式 mapper/DTO，不能“原样”写。
- 处置：Lead 在 B7 开工前补 acceptance 与 shape 映射；模块 review 绑定 API 详情/契约测试证据。

### F-002（P2）缺 count/uniqueCount 的 sst 被误判 KB_NO_TEXT

- 独立探针已复现（baseline OK；去属性后 NO_TEXT）。POI 的 `getCount()` 只看可选属性，这是实现边界选择错误，
  不是文件损坏。
- 处置：改用 `getUniqueCount()` 回退或在 `getItemAt` 外包 `IllegalStateException`；补一条“去 count 属性”的
  fixture。M05 gate 前修复或 Lead 明确接受。

### F-003（P2）缺 r 的行号坍缩

- 独立探针已复现：三行文本变成 `A1:A1`/rowCount=1，内容不丢但定位错。AC-02 的引用定位会指向错误区域。
- 处置：一行修复 `rowReference == null ? nextRowNum + 1 : parseIndex(rowReference)`，补缺 r 回归；V1 用
  IT-KB-103 复验。

### F-004（P2）50 MB 错误码与 180 s 超时未按 ADR

- `RICCON-FILE-413` vs `KB_PARSE_RESOURCE_LIMIT`；`PARSE_TIMEOUT` 无写入方；只有 30m/65m lane watchdog。
- 处置：Lead/Architect 决定由谁承担；至少在 B7/gate 前补 180 s deadline 或明确修订 ADR；否则 T4 的
  解析资源 DoD 不完整。

### F-005（P3）worktree 取证例外 + 两次 EV ID 重编号 + 提交归因

- EV-0824（本地 EV-0823→主 EV-0824）、EV-0828（本地 EV-0827→主 EV-0828）；哈希/命令/提交一致；
  6a70b9b 标题误标 m05-b3；8acffe9 时测试文件不在 write_scope，Lead 事后补录。
- 处置：Lead 归档例外口径，后续默认主树干净窗口取证；不判造假。

### F-006（P3）end_commit=8acffe9 不包含最终代码

- 文字归因充分但机器字段误导；建议改 end_commit=3b7a3ab 或加 end_commit_note。

### F-007（P3）IT-KB-101/102/103 延期

- 任务 AC 不可在 B4 内满足；parser 层等价已通过；TASK-0176 关闭；建议改写任务 AC，避免未来误判。

### F-008（P3）测试覆盖缺口

- 1904、ZIP ratio/file-count、50 表边界、缺 r/count 回归、纯列超限→NO_TEXT、XXE、服务级 FAILED/半索引。
- 其中“纯列超限”建议 Lead 明确期望（INDEXED + skippedRange 还是 NO_TEXT），再加一条单测。

## 6. 独立探针（可复现，非 EV）

为避免只靠 builder 测试，我在主树 HEAD 源码上做了两个 ad-hoc 探针（不改仓库源码/测试）：

- 脚本：`.riccon/evidence/TASK-0167/probe-2026-11-23-sharedstrings-rowref.jsh`
- 输出：`.riccon/evidence/TASK-0167/probe-2026-11-23-sharedstrings-rowref.log`
- 方法：用 POI 生成三行字符串工作簿 → 重新打包时分别去掉 `xl/sharedStrings.xml` 的 `count/uniqueCount`
  属性、去掉 `xl/worksheets/sheet1.xml` 的 `<row r="N">` 属性 → 调用 HEAD 的 `XlsxDocumentParser.parse`。
- 结果：baseline `A1:A3`/3 行；缺 count → `ParseException: xlsx workbook has no indexable text`；
  缺 r → `A1:A1`/rowCount=1，内容仍为三行。探针编译使用 HEAD 源码子集 + `dependency:build-classpath`
  的 worker runtime classpath（jshell），不写仓库 target 以外的文件。

该探针只用于支撑 F-002/F-003，不作为任务 DoD 证据；EV 仍为 EV-0824/0826/0828。

## 7. 未验证 / 没做什么

- **没有复现 200 MB/230k 行、`-Xmx192m` 内存旁证**：builder 的该声明未落盘为 EV；本次只跑了 20k 行单测，
  不能把 16 MB heap 当已证明。ADR 的内存预算由 IT-KB-116（100 MB fixture/RSS ≤256 MB）在 module gate 验证。
- **没有跑服务层/集成**：`FileParseServiceIntegrationTest`（MySQL/S3）与 `KnowledgeIndexService` 的
  XLSX→FAILED、无半索引、上传 400/415 边界未在本次运行；需 TASK-0176/V2 或模块 gate。
- **没有跑完整合同 build/lint/coverage/E2E/a11y/security/perf**：任务级不重复全量；模块 gate 由 Lead
  在干净提交上跑。当前主树因 B3/B5 WIP 不干净，`gate check --module M05` 仍失败（任务未 done、
  无模块 review、TASK-0166 的 EV-0822/0823 为 dirty、未提交文件被计入 TASK-0167 scope），这些不是
  B4 代码结论。
- **没有启动完整 Spring 上下文**验证 `@Value` 注入；只用 `StandardEnvironment` 验证了 env 变量解析。
- **没有真实浏览器/E2E/a11y**：B4 无 UI；AC-02 的引用详情 UI 属 B8/E2E-M05-01。
- **没有验证 50 MB/ratio/file-count 炸弹与 XXE 夹具**；只静态核对了 POI 消息与 XMLHelper 安全设置。
- **没有验证生产 180 s 超时**：确认无实现（F-004），不是“未测”。

## 8. 剩余风险与下一步

- **Lead**：(1) 把 F-001 的 parse_* 持久化/shape 写进 TASK-0169 或指定 B3，否则 M05-AC-01/02 详情不可验证；
  (2) 裁决 F-004 的 180 s/50 MB 错误码归属；(3) 收口 F-005/F-006 的 worktree/end_commit/提交归因；
  (4) 改写 F-007 的任务 AC 措辞（参考 TASK-0165 的 “pass (with residual)” 先例）。
- **Builder（B4）**：F-002/F-003 各是 1–10 行修复 + 回归用例，建议 M05 gate 前关闭；若关闭，重跑
  `XlsxDocumentParser*Test` 并更新 EV。
- **Verifier（TASK-0176/V1）**：IT-KB-101/102/103/116、E2E-M05-01、引用详情 `cellRange` 定位、服务级
  FAILED/无半索引、a11y；模块 review 用 B7/B8/B9 的最终提交跑全量合同命令。
- **B4 代码自身**：当前无 P0/P1；EV-0828 后两文件 spotless 干净，25 tests 0 skip，任务级 verdict pass。

## 9. 附：任务卡证据与 gate 现状

- `TASK-0167.evidence` 当前为 `[EV-0824, EV-0826, EV-0828]`；EV-0827 已移除（保留文件）。
- `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M05` 当前 18 errors：
  TASK-0166/0167/0168 未 done、无 M05 模块 review、TASK-0166 的 EV-0822/0823 dirty、主树未提交的
  B3/B5 文件被计入 TASK-0167 scope 等。B4 的 EV-0824/0826/0828 均通过 hash/commit 校验；
  模块 gate 必须在 B3/B5 提交、B7/B8/V1 完成后由 Lead 重跑，不能以本任务评审替代。
