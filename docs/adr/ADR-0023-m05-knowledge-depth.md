# ADR-0023：M05 知识库纵深（Excel/OCR、文档级 ACL、外部连接器）

Status: proposed
Date: 2026-11-22
Reviewers: `riccon/lead`（范围/合同一致性）、`riccon/verifier`（ACL 越权、OCR 资源上限、迁移可测性）、`riccon/ops`（镜像/权重分发、迁移回滚、连接器凭据与出网）、安全评审（提示注入、SSRF、凭据、跨租户）
Approved-by: 未批准（本 ADR 为 `proposed`；OCR 引擎、连接器类型、模块拆分与回滚约束需用户决策，见 §13）
Review-date: 待定（M05 gate 前完成 Lead/Verifier/Ops/安全评审）
Supersedes: none
Scope: M05「知识库纵深」的 Excel 结构化抽取、图片/扫描件 OCR、文档级 ACL、1 个外部连接器；M02 已有 PDF/DOCX/XLSX/图片上传与引用
关联: `docs/PRODUCT.md` §6（M05 路线图，brief/AC 由 Product 并行编写）、`docs/ARCHITECTURE.md`、ADR-0006（信封加密）、ADR-0007（审计）、ADR-0009（租户隔离与检索）、ADR-0010（Flyway）、ADR-0012（数据模型）、ADR-0015（领域模块）、ADR-0018（M03 部门范围）、ADR-0021/0022（M04 网关）、`docs/security/THREAT-MODEL.md`、`docs/CODING-STANDARDS.md`

> 状态口径：`proposed` 只表示架构草案已写出。§4（OCR 引擎与权重分发）、§5.9（ACL 上线后的回滚约束）、§6.1（连接器类型）和 §12（模块拆分/预算）未经用户批准前不进入实现；本 ADR 不修改 `docs/PRODUCT.md`，§11 只是对 Product §11 草案的设计映射，AC 以 Product 定稿为准。

---

## 1. Context

### 1.1 现状（已实现，作为设计前提）

| 事实                                                                                                                      | 代码/契约证据                                                                 | 对 M05 的含义                                                                     |
| ------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 上传支持 PDF/DOCX/XLSX/PNG/JPEG，单文件 ≤ 200 MB；Worker 解析上限默认 50 MB（`riccon.worker.parse.max-file-bytes`）       | `FileTypes`、`application.yml`、`FileUploadPreparer`、`ParseProperties`       | Worker 已在大文件前拒绝；M05 不改变上传契约，Excel/OCR 的上限必须更低并有独立开关 |
| XLSX 用 POI 5.5.1 DOM（`XSSFWorkbook`），每工作表一个段落，`MAX_ROWS=20_000`、`MAX_COLUMNS=200`，`cellRange` 记录整表范围 | `XlsxDocumentParser`                                                          | 200 MB XLSX 会整本载入堆，M05 必须改为流式并细化定位                              |
| 图片只校验对象存在，返回元数据，不读像素                                                                                  | `FileContentExtractor`、`ImageDocumentParser`                                 | OCR 是新增能力，不替换现有行为；需开关与失败态                                    |
| PDF 逐页文本抽取；无文本时解析失败                                                                                        | `PdfDocumentParser`                                                           | 扫描件 PDF 需要逐页渲染 + OCR，且不能把“有文本页 + 扫描页”整本判失败              |
| 检索运行期走 MySQL `LIKE`（`knowledge_chunk JOIN knowledge_document`），不查 OpenSearch                                   | `AgentContextJdbc.search`、`AgentContextRepository` 注释                      | **ACL 的强制点在 MySQL**；OpenSearch 只做可重建索引，不在 M05 切换检索后端        |
| OpenSearch 索引 `riccon-kb-v1-{tenant}`，`dynamic: strict`，字段不含 ACL                                                  | `OpenSearchIndexClient.indexMapping`                                          | 新增 ACL 字段必须建 v2 索引并重灌；`strict` 下不能只靠动态写入                    |
| 知识库仍租户级共享：列表/详情按 `tenant_id`，界面固定明示“文档级权限在后续版本”                                           | `KnowledgeService.list/get`、`openapi.yaml` `/knowledge/documents`、M03-AC-06 | M05 必须让 ACL 真正生效，并同步撤销该明示文案（Product 侧）                       |
| 源文件打开/下载走工作空间谓词；文档本身不判定空间                                                                         | `KnowledgeService.create` 的 `lockAccessible`、ADR-0018 §3                    | 知识可见性与文件字节可见性必须分开建模，不能互相放大                              |
| `knowledge_document.file_id` 可空（SEED 语料），`created_by` 可空                                                         | V3 迁移                                                                       | 连接器文档可以没有 `file` 行；创建人空缺时 ACL 不能依赖“创建人隐式可见”           |
| outbox 单通道、顺序处理，租约 90 s，无续约                                                                                | `OutboxPoller.pollOnce`、`OutboxClaimer`、`application.yml`                   | OCR/连接器长任务会阻塞 J2 报告与文件解析；M05 必须拆通道并支持租约续期            |
| 审计 details 是 action-aware 白名单，未知动作或白名单外键 fail-closed                                                     | `AuditDetailsReader`、`AuditDetailsPolicy`                                    | 新增知识库/连接器动作必须同步白名单，否则审计写入直接失败                         |

### 1.2 约束

- **T4 数据边界**：生产数据不出客户内网；OCR 不得使用公有云；连接器只连客户已授权的内部/受管端点。
- **许可**：只允许 Apache-2.0、MIT、BSD、EPL；AGPL 仅内部使用且不得进入一体机分发（合同 `constraints.licensing`）。模型权重按同一标准核验，并记录再分发义务。
- **性能**：假模型基准首字 p95 ≤ 3 s、稳态 JSON API p95 ≤ 500 ms（M04 F-013 口径）、单实例 50 并发 Agent run。
- **迁移**：Flyway 只向前；破坏性变更需 ADR、回滚脚本和演练；只有 `apps/api` 执行迁移，worker 校验 schema 版本。
- **不改写已发布契约语义**：M05 的 OpenAPI 变更为 additive，版本升 `1.4.0-m05`；旧 `locator_type` 枚举不扩展（见 §4.3 理由）。

---

## 2. 决策总览

| 能力             | 决策                                                                                                                                                                                                     | 需要用户批准                                                      |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| Excel 结构化抽取 | 继续用 Apache POI 5.5.1，XLSX 改为 `XSSFReader` 流式；按“工作表 × 行带”定位（`sheetName` + `cellRange`）；公式只读缓存值、不计算；隐藏表默认跳过并出警告                                                 | 否（团队可决定）；隐藏表默认值和警告展示需 Product 确认           |
| 图片/扫描件 OCR  | 默认 Tesseract 5 + `tessdata_fast`（chi_sim+eng），经 Tess4J（Apache-2.0）调用；`OcrEngine` SPI 允许切换到 RapidOCR/PP-OCR ONNX；本地 CPU、无公网                                                        | **是**：引擎、权重随镜像分发、中文精度基线                        |
| 文档级 ACL       | `knowledge_document.visibility ∈ {TENANT, DEPT, USERS}` + `knowledge_document_acl`（USERS）；创建人或 `TENANT_ADMIN` 可改；列表/检索/引用/知识库下载四处同一谓词；MySQL 为权威，下一次请求生效；统一 404 | **是**：默认可见范围、管理员是否可读全部文档、上线后回滚约束      |
| 外部连接器       | 1 个：S3 兼容目录（前缀）连接器，手动 + 可选定时；ETag/校验和增量；源端删除传播；凭据信封加密；端点允许列表 + SSRF 防护                                                                                  | **是**：连接器类型（产品/高成本选择）                             |
| 数据与索引       | Flyway V12 单迁移；OpenSearch v2 索引 + 别名切换（离线可回滚）；outbox 拆 `default`/`heavy` 双通道 + 租约续期；`1.4.0-m05` 全量契约                                                                      | 否（团队可决定），但 `connector` 对象 key 类别需 Lead 修 ADR-0009 |

---

## 3. Excel 结构化抽取

### 3.1 解析库与许可

| 方案                                                          | 许可       | 维护/现状                           | 结论                                                                     |
| ------------------------------------------------------------- | ---------- | ----------------------------------- | ------------------------------------------------------------------------ |
| Apache POI 5.5.1 `XSSFWorkbook`（DOM）                        | Apache-2.0 | 已用，活跃                          | 仅保留用于既有小文件测试夹具；M05 后 XLSX 运行时统一走流式，不提供双路径 |
| Apache POI 5.5.1 `XSSFReader` + `XSSFSheetXMLHandler`（流式） | Apache-2.0 | 同库，无新增依赖                    | **采用**：单次顺序读取，内存与文件大小解耦                               |
| fastexcel / EasyExcel                                         | Apache-2.0 | 需新增依赖，能力与 POI 重叠         | 不引入（“少一个依赖”优先）                                               |
| 自研 OOXML 解析                                               | —          | 维护成本高，易漏 sharedStrings/样式 | 拒绝                                                                     |

- 不新增依赖；ZIP 解析限制使用 POI 自带 `ZipSecureFile`：
  - `maxEntrySize = 64 MB`、`maxTextSize = 256 MB`、`minInflateRatio = 0.01`、`maxEntryCount = 10_000`。
  - 超限 → `KB_PARSE_RESOURCE_LIMIT`（fail-closed，不尝试“部分读取”）。
- 外部链接、宏、OLE 对象一律忽略，不解析、不下载、不执行。

### 3.2 定位模型

| 维度       | 规则                                                                                                                                                                                                                              |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 工作表     | `sheetName` 原样保存（≤255 字符）；跳过隐藏表时计入警告                                                                                                                                                                           |
| 行带       | 每 **20 行** 一个段落（可配 `RICCON_KB_XLSX_BAND_ROWS`），`cellRange` 形如 `A1:F20`；同一行带被字符切分后所有块继承同一 `cellRange`                                                                                               |
| 列         | 实际有值的最大列，上限 200；超出截断并警告                                                                                                                                                                                        |
| 单元格渲染 | `DataFormatter(Locale.CHINA)`；数值用 `BigDecimal.toPlainString()` 保留精度；日期先按 `DateUtil.isCellDateFormatted` 判定，再用固定 `yyyy-MM-dd HH:mm:ss`（`Asia/Shanghai`），不依赖单元格显示样式；布尔 `TRUE/FALSE`；错误值跳过 |
| 公式       | 只读缓存值（`useCachedValuesForFormulaCells=true`）；无缓存值 → 跳过并警告 `FORMULA_NOT_CACHED`；**禁止 `FormulaEvaluator`**                                                                                                      |
| 合并单元格 | 只取左上角锚点一次，不向被合并区域复制；跨行带时锚点落在起始带                                                                                                                                                                    |
| 隐藏行/列  | 照常抽取（可能是有效数据）；只跳过隐藏**工作表**                                                                                                                                                                                  |
| 空表/空行  | 空表跳过；空行跳过且不计入分块                                                                                                                                                                                                    |

- 实现提示：`XSSFReader` 只给 sheet 流；隐藏表需先读 `workbook.xml` 的 `<sheet state>` 映射（文件很小），再跳过对应 sheet 的 XML 流；不要把整本 workbook 重新载入 DOM 来查可见性。

### 3.3 分块与上限

- 复用 `TextChunker`（800 字符、80 重叠），但 Excel 段落按行带生成；切分优先在换行处断开，避免把一行拆成两个无语义块（Builder 实现时在 `chunkSegment` 增加“换行优先”分支，行为由 `IT-KB-101` 锁定）。
- 上限（配置键均加 `RICCON_KB_XLSX_` 前缀，默认值如下）：

| 项           | 默认                                  | 超限行为                                |
| ------------ | ------------------------------------- | --------------------------------------- |
| 单文件字节   | 50 MB（沿用 Worker `max-file-bytes`） | 解析前拒绝 `KB_PARSE_RESOURCE_LIMIT`    |
| 工作表数     | 50                                    | 只索引前 50 个，警告 `SHEETS_TRUNCATED` |
| 单表行数     | 20 000                                | 截断，警告 `ROWS_TRUNCATED:<sheet>`     |
| 单表列数     | 200                                   | 截断，警告 `COLUMNS_TRUNCATED:<sheet>`  |
| 单文档字符数 | 8 000 000                             | 截断，警告 `CHARS_TRUNCATED`            |
| 单文档段落数 | 5 000                                 | 截断，警告 `SEGMENTS_TRUNCATED`         |
| 单文档分块数 | 5 000                                 | 截断，警告 `CHUNKS_TRUNCATED`           |
| 解析超时     | 180 s（文档级）                       | `KB_PARSE_TIMEOUT`，文档 `FAILED`       |

### 3.4 失败与降级

| 场景            | 结果          | error_code / 警告           |
| --------------- | ------------- | --------------------------- |
| 加密/受密码保护 | 文档 `FAILED` | `KB_PARSE_ENCRYPTED`        |
| 损坏/非法 OOXML | `FAILED`      | `KB_PARSE_CORRUPT`          |
| ZIP 资源超限    | `FAILED`      | `KB_PARSE_RESOURCE_LIMIT`   |
| 超时            | `FAILED`      | `KB_PARSE_TIMEOUT`          |
| 有内容但被截断  | `INDEXED`     | `warnings[]`（不阻断检索）  |
| 完全无文本      | `FAILED`      | `KB_NO_TEXT`                |
| 隐藏表被跳过    | `INDEXED`     | `HIDDEN_SHEETS_SKIPPED:<n>` |

- 失败可重试：`POST /knowledge/documents/{id}/reindex` 复用既有端点；重试不清空已有分块，成功后原子替换（沿用 M02“先删后插”）。
- 警告落库：`knowledge_document.parse_warnings JSON`（白名单数组），API 以 `warnings` 返回；不把警告塞进 `error_message`。
- 结构摘要落库：`knowledge_document.parse_summary JSON`（工作表名、行列范围、空表标记、已索引/未索引范围、隐藏表数），API 以 `parseSummary` 返回；只含结构元数据，不含单元格原文。

### 3.5 验证

- `IT-KB-101`：多工作表、行带、空表标记、隐藏表警告、行列/字符/段落上限与已索引/未索引范围；换行优先切分。
- `IT-KB-102`：加密工作簿、损坏 XML、伪装扩展名/非 XLSX 的稳定错误码与中文原因；不产生半索引文档。
- `IT-KB-103`：引用显示 `工作表!区域`，打开可定位到对应工作表/区域；整表片段不伪造单元格。
- `IT-KB-116`：ZIP 炸弹/资源上限 fixture 被拒；100 MB 级 fixture（或 20 MB 慢测试替代）解析 RSS 增量 ≤ 256 MB，不 OOM。

---

## 4. 图片与扫描件 OCR

### 4.1 本地方案对比（许可已核对，仍需法务/Trivy 复核）

| 方案                                                      | 代码许可                                                                                                            | 权重许可                                            | 运行时/依赖                                         | 中文印刷体质量                        | 结论                                             |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------- | --------------------------------------------------- | ------------------------------------- | ------------------------------------------------ |
| **Tesseract 5 + `tessdata_fast`（chi_sim+eng）经 Tess4J** | Tesseract Apache-2.0；Tess4J Apache-2.0；JNA Apache-2.0 或 LGPL-2.1+（本项目选 Apache-2.0）；Leptonica BSD-2-Clause | `tessdata_fast` Apache-2.0                          | Linux 原生 `libtesseract`/`libleptonica` + JVM；CPU | 中（清晰扫描件可用，照片/旋转件较弱） | **推荐（默认）**                                 |
| RapidOCR / PP-OCR ONNX（ONNX Runtime Java）               | RapidOCR Apache-2.0；ONNX Runtime MIT                                                                               | PP-OCR 系列 Apache-2.0（RapidOCR 模型许可声明覆盖） | 纯 JVM + 原生 ORT；模型 10–30 MB                    | 高                                    | **替代**：若固定样本达不到精度门槛则切换（§4.2） |
| PaddleOCR（Python 服务）                                  | Apache-2.0                                                                                                          | Apache-2.0                                          | 新增 Python 运行时/进程/镜像层                      | 高                                    | 拒绝：合同技术栈无 Python，新增部署与运维面      |
| OCRmyPDF                                                  | **MPL-2.0**                                                                                                         | Apache-2.0                                          | Python + 系统二进制                                 | 中                                    | 拒绝：MPL 不在合同允许列表，一体机分发需额外审批 |
| 公有云 OCR                                                | 商业                                                                                                                | —                                                   | 公网                                                | 高                                    | 拒绝：T4 生产数据不出客户内网                    |

维护活跃度：Tesseract 5.5.x（2026 仍发布）与 Tess4J 5.20.0（2026-07）均在维护；RapidOCR 持续发布 PP-OCRv6 模型。两者都非“实验性库”。

### 4.2 推荐、替代与需批准项

- **默认引擎 `tesseract`**：`OcrEngine` SPI + 启动自检（原生库可加载、至少一个配置语言可加载、1 页固定 fixture 能出文本）。引擎/原生库不可用 → worker 启动失败；配置语言缺失 → 启动 WARN 并写入 `/actuator/info`，对应文档失败态为 `KB_OCR_LANG_UNAVAILABLE`。SPI 方法只接受字节与选项、返回块列表与置信度，不泄漏 Tesseract 类型。
- **精度门槛（B 阶段先做 1 天 spike，结果进评审）**：固定 20 张印刷体夹具（中文 10、英文 5、中英混排 5），要求中文 CER ≤ 10%、英文 CER ≤ 5%、整页均置信度 ≥ 0.70。未达标 → 走替代引擎（RapidOCR/PP-OCR ONNX），由 Lead 在决策包中选。
- **替代引擎的许可与分发**：RapidOCR/PP-OCR 模型 Apache-2.0，允许随一体机分发；需在 `NOTICE` 保留上游版权与模型来源/哈希。
- **需用户批准**：
  1. 选 Tesseract 还是 PP-OCR（影响中文识别质量与实现工作量）；
  2. 允许把 `tessdata_fast`/PP-OCR 权重打进一体机镜像（Apache-2.0 允许，但属于产品分发内容）；
  3. 接受上述精度基线作为 AC 口径（而不是“任意图片都能识别”）。

### 4.3 管线与定位

```text
图片（PNG/JPEG）       扫描件 PDF 页
  |  EXIF 方向校正        |  PDFRenderer 200 DPI 逐页渲染
  |  像素上限 50 MP       |  单页超时 30 s
  +----------+-----------+
             v
     OcrEngine.recognize(bytes, lang=chi_sim+eng, psm=3)
             |  置信度 < 0.45 且为图片：旋转 90° 重试一次（最多 1 次）
             v
  文本块（按阅读顺序）→ 合并短行 → ParsedSegment
             |  locator_type = PAGE（图片固定 page_no=1）
             |  bbox + ocr_confidence 写入分块
             v
     TextChunker（同一字符窗口）→ knowledge_chunk / file_chunk
```

- **定位**：`locator_type` 保持 `PAGE|SHEET|PARAGRAPH`，**不新增枚举值**（避免 `file_chunk`/`knowledge_chunk` 的 CHECK 变更与旧客户端枚举兼容问题）。图片区域用新增可空列 `bbox_x/bbox_y/bbox_w/bbox_h`（渲染像素坐标）与 `ocr_confidence DECIMAL(4,3)` 表达；契约新增可选 `bbox` 对象。`page_no` 对单图固定 1，对 PDF 为真实页码。
- 实现提示：Tesseract 用 `ResultIterator` 按行/词取 text + bounding box + confidence，再按阅读顺序合并短行；不要只取 `getUTF8Text()`，否则拿不到区域与置信度。
- **区域高亮口径待定（Product/前端）**：M05 先由契约提供 `bbox`/`sheetName`/`cellRange` 与分块文本；若要求“页图叠加框选”，需要新增受 ACL 保护的页图渲染端点（API 引入 PDFBox 或由 worker 预生成页图），成本与缓存策略另计（§13.3 P6）。不允许前端直接读对象存储。
- **低置信**：块级 `ocr_confidence < 0.60` 仍入索引，但标记 `LOW_CONFIDENCE` 警告与指标；整页均置信度 < 0.45 → 页面警告，不伪造文本。文档级均置信度与低置信页集合写入 `parse_summary.ocr`，供详情页展示“识别成功（置信度低，请核对原文）”。
- **扫描件 PDF 混合页**：逐页判断可抽取文本长度；有文本页走 PDFBox，无文本页走 OCR；OCR 不可用且有文本页 → 只索引文本页 + 警告 `PAGES_WITHOUT_TEXT:<n>`；整本无可索引文本 → `FAILED`/`KB_OCR_UNAVAILABLE`。
- **旋转**：JPEG 先按 EXIF Orientation 校正（JDK ImageIO 元数据，不新增依赖）；校正失败按 0° 处理并记录警告。

### 4.4 资源、队列与超时

| 项                   | 默认                                                                                    | 说明                                                              |
| -------------------- | --------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `RICCON_OCR_ENABLED` | `true`（引擎/原生库不可用时 worker 启动失败；关闭时 OCR 文档 `FAILED/KB_OCR_DISABLED`） | 运维可关闭，非静默降级                                            |
| `RICCON_OCR_ENGINE`  | `tesseract`                                                                             | `tesseract`/`rapidocr`（SPI 选择）                                |
| `RICCON_OCR_LANG`    | `chi_sim+eng`                                                                           | 语言包缺失 → 启动 WARN + `KB_OCR_LANG_UNAVAILABLE`，不阻断 worker |
| 并发                 | 每实例 2（`RICCON_OCR_MAX_CONCURRENCY`）                                                | 信号量限制，不占用 chat 流线程                                    |
| 单页超时             | 30 s                                                                                    | 超时记 `KB_OCR_TIMEOUT`，继续下一页                               |
| 单文档 OCR 页数      | 100                                                                                     | 超出页保持“仅文本层”，警告 `OCR_PAGE_LIMIT`                       |
| 单文档 OCR 超时      | 600 s                                                                                   | 超时 → 部分结果入索引 + 警告；全无结果 → `FAILED`                 |
| 图片像素             | 50 MP，最长边 4000 px                                                                   | 超出先等比缩放，再 OCR                                            |
| 内存                 | 单页解码后 ≤ 64 MB                                                                      | 逐页释放；禁止整本渲染                                            |

- **队列**：不新建队列表，使用 outbox `heavy` 通道（§7.3）。可观察指标：`riccon_ocr_in_flight`、`riccon_ocr_page_seconds`、`riccon_ocr_queue_wait_seconds`、`riccon_ocr_failures_total{reason}`。
- **租约**：heavy 通道支持租约续期（§7.3），否则 90 s 租约会在长 OCR 时被重复领取。

### 4.5 失败与降级

| 场景                  | 结果                                            | error_code/警告                                                    |
| --------------------- | ----------------------------------------------- | ------------------------------------------------------------------ |
| OCR 关闭              | `FAILED`                                        | `KB_OCR_DISABLED`                                                  |
| 引擎/权重缺失         | worker 启动失败（`RICCON_OCR_ENABLED=true` 时） | 启动日志固定 WARN + 非零退出                                       |
| 语言包缺失            | 文档 `FAILED`                                   | `KB_OCR_LANG_UNAVAILABLE`；UI“不支持该语言”，其他语言/文档不受影响 |
| 单页超时/崩溃         | 跳过该页                                        | `KB_OCR_TIMEOUT` / `KB_OCR_FAILED`；其余页继续                     |
| 引擎内存不足/原生崩溃 | 任务失败                                        | `KB_OCR_FAILED`；native 崩溃由进程重启恢复，outbox 重试            |
| 全部页无文本          | `FAILED`                                        | `KB_NO_TEXT`                                                       |
| 部分页无文本          | `INDEXED`                                       | `PAGES_WITHOUT_TEXT:<n>`                                           |
| 低置信                | `INDEXED`                                       | `LOW_CONFIDENCE`                                                   |

- **部分成功**：失败页集合写入 `parse_warnings`（`FAILED_PAGES:[2,5]`、`LOW_CONFIDENCE_PAGES:[3]`），文档仍 `INDEXED`；成功页可检索，失败页不产生分块。UI 以 `warnings` 是否含 `FAILED_PAGES` 渲染“部分成功”，不新增 `status` 枚举值。
- **重试**：`POST /knowledge/documents/{id}/reindex` 默认只补“没有分块的页”（按 `knowledge_chunk.page_no` 求差），不重复已成功页；`?force=true` 才整本重做。重试不清空已成功页，成功后原子替换，避免重复引用。

### 4.6 供应链与分发

- 镜像内固定 `tesseract` 版本、`tessdata_fast` 文件哈希（`chi_sim.traineddata`、`eng.traineddata`）并在 `/actuator/info` 暴露版本/哈希指纹（不含密钥）。
- `NOTICE` 增加 Tesseract/Leptonica/tessdata 与（若切换）RapidOCR/PP-OCR 的版权与许可；`trivy fs` 扫描镜像时排除模型数据目录的误报，但保留 SBOM 记录。
- 启动自检输出固定日志键：`ocr.engine`、`ocr.lang`、`ocr.model_hash`。

### 4.7 验证

- `IT-KB-104`：中英文/中英混排图片与图片型 PDF 的文本、`page_no`、`bbox` 与置信度落库；文本型 PDF/DOCX 不重复 OCR。
- `IT-KB-105`：成功/低置信/部分失败/全部失败四态；失败页集合、成功页可检索、失败页不产生分块；重试幂等。
- `IT-KB-114`：EXIF 90°/180°/270° 夹具；低置信触发的 90° 重试只发生一次。
- `SEC-KB-001`：禁用出网环境执行 OCR，成功且无公有云请求（拒绝日志/网络证据）；日志/审计无凭据或内部地址。
- `SEC-KB-005`：超大像素图片、畸形 JPEG、解压炸弹不导致 OOM/超时失控。
- `scripts/ops/ocr-check`（Ops 新增）：单页 fixture 断言非空文本与均置信度，纳入部署检查与 M05 gate。

---

## 5. 文档级 ACL

### 5.1 数据模型（V12，additive）

```sql
-- knowledge_document 扩展
ALTER TABLE knowledge_document
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT|DEPT|USERS',
    ADD COLUMN department_id BINARY(16) NULL COMMENT 'DEPT 可见时必填；复合外键到 department',
    ADD COLUMN connector_id BINARY(16) NULL COMMENT '来源连接器（逻辑引用）',
    ADD COLUMN external_id VARCHAR(1000) NULL COMMENT '连接器源对象标识，仅展示/排障',
    ADD COLUMN parse_engine VARCHAR(16) NULL COMMENT 'PDFBOX|POI|TESSERACT|RAPIDOCR|NONE',
    ADD COLUMN parse_warnings JSON NULL COMMENT '白名单警告数组，不含原文',
    ADD COLUMN parse_summary JSON NULL COMMENT '结构化摘要：工作表清单/范围/空表/已索引范围，不含原文',
    ADD COLUMN acl_version INT NOT NULL DEFAULT 1 COMMENT 'ACL 变更计数，用于索引刷新幂等键',
    ADD COLUMN retention_expires_at DATETIME(6) NULL COMMENT '内容保留到期（UTC）；默认创建后 180 天，租户策略覆盖',
    ADD KEY idx_kd_retention (tenant_id, retention_expires_at),
    ADD KEY idx_kd_visibility (tenant_id, visibility, department_id, created_at),
    ADD CONSTRAINT fk_kd_department FOREIGN KEY (tenant_id, department_id)
        REFERENCES department (tenant_id, id),
    ADD CONSTRAINT chk_kd_visibility CHECK (visibility IN ('TENANT', 'DEPT', 'USERS')),
    ADD CONSTRAINT chk_kd_visibility_department CHECK (
        (visibility = 'DEPT' AND department_id IS NOT NULL)
        OR (visibility <> 'DEPT' AND department_id IS NULL)
    );

-- 原 source_type CHECK 增加 CONNECTOR（小表，DROP/ADD CHECK 可接受）
ALTER TABLE knowledge_document
    DROP CHECK chk_knowledge_document_source,
    ADD CONSTRAINT chk_knowledge_document_source CHECK (source_type IN ('UPLOAD', 'SEED', 'CONNECTOR'));

CREATE TABLE knowledge_document_acl (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    document_id BINARY(16) NOT NULL COMMENT '知识库文档 ID',
    user_id     BINARY(16) NOT NULL COMMENT '被授权账号 ID',
    granted_by  BINARY(16) NULL COMMENT '最近一次授权操作者（逻辑引用）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, document_id, user_id),
    KEY idx_kda_user (tenant_id, user_id, document_id),
    CONSTRAINT fk_kda_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document (tenant_id, id),
    CONSTRAINT fk_kda_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_kda_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库文档用户级 ACL（M05）';
```

- 主键 `(tenant_id, document_id, user_id)` 已覆盖“某文档的授权列表”；`idx_kda_user` 覆盖“某用户可见的文档”，供检索 `EXISTS` 与列表过滤。
- 复合外键阻止跨租户文档/用户；`document_id` 不级联删除（文档只软删除，ACL 行保留以便恢复）。
- 上限：单文档 `USERS` 授权 ≤ 200（`RICCON_KB_ACL_MAX_USERS`）；超出 → 400 `RICCON-KB-400`，不静默截断。
- `visibility` 不新增独立表：三种取值是封闭集合，列 + CHECK 比枚举表更简单。

引用可用性所需的版本快照（additive，可空）：

```sql
ALTER TABLE message_citation
    ADD COLUMN source_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '引用时的 knowledge_document.checksum；用于识别源内容已更新';

ALTER TABLE artifact_citation
    ADD COLUMN source_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '引用时的 knowledge_document.checksum；产物引用仍保留快照但展示按 ACL 遮蔽';
```

- `source_version` 为空的旧引用按 `UPDATED` 的保守分支处理：能判定可见性，但不承诺“内容未变”；不回溯改写历史引用。仅 `sourceType=KNOWLEDGE` 的引用写入该列；`FILE` 引用保持 NULL，不参与版本比较。

### 5.2 可见范围与判定

| `visibility` | 可见集合                                                                          | 备注                                            |
| ------------ | --------------------------------------------------------------------------------- | ----------------------------------------------- |
| `TENANT`     | 本租户全部启用账号                                                                | 与 M02/M03 行为一致；历史文档回填默认值         |
| `DEPT`       | `app_user.department_id = d.department_id` 且 `department.status='ACTIVE'` 的账号 | 复用 M03 单主部门语义；部门停用下一次请求即失效 |
| `USERS`      | `knowledge_document_acl` 中的账号 + 创建人（若有）                                | 创建人隐式可见，不可被移除；避免“自锁”          |

- **管理员边界**：`TENANT_ADMIN` 可以管理任意文档的 ACL（`knowledge.acl.manage`），并对本租户知识文档有管理性读取（列表/检索/详情/下载全量，不做 ACL 过滤）；因此**事实上可以给自己授权并读取**。M05 明确接受这一点（与 M03 租户管理员可管理空间一致），不把 ACL 当作对租户管理员的边界；读取不写审计（§5.6 读放大口径）。`PLATFORM_ADMIN` 不读取租户知识内容（沿用 ADR-0009）。
- **创建人**：`created_by` 非空时隐式 `USERS` 授权；ACL 列表接口返回 `creator` 标记，不允许移除。
- **源文件空间与文档可见性相互独立**：把文件加入知识库**不扩大**文件所在空间的可访问性；把文档设为 `DEPT`/`USERS` 也**不缩小**源文件的空间访问。知识库下载只按文档 ACL，原空间路径只按空间谓词；两条路径独立，不要求同时满足（§5.3）。

### 5.3 与 M03 空间 ACL 的关系与优先级

| 动作                            | 判定                                                 | 失败                                     |
| ------------------------------- | ---------------------------------------------------- | ---------------------------------------- |
| 知识库列表/详情/检索/引用元数据 | 文档 ACL（MySQL）                                    | 不可见 → 统一 404；列表直接不出现        |
| 知识库下载（新增）              | 文档 ACL + `knowledge.read`                          | 不可见 → 404；无源文件 → `RICCON-KB-404` |
| 原工作空间文件路径（既有）      | 工作空间谓词（`WorkspaceService.requireAccessible`） | 404（沿用 M03）                          |
| 打开引用                        | 文档 ACL（走知识库下载/片段接口，不走原空间路径）    | 404；旧引用按 §5.4 遮蔽                  |
| 把文件加入知识库                | 空间谓词 + `knowledge.manage`                        | 404/403                                  |

- **知识库下载**：`GET /api/v1/knowledge/documents/{documentId}/download` 由服务端按 `(tenant_id, documentId)` 解析源对象（上传文件或连接器对象），返回短时预签名 URL 或流；不返回对象 key，不把连接器端点暴露给浏览器。无源文件时返回 `RICCON-KB-404`，该码只在 ACL 已通过后出现，不能替代不可见时的 `RICCON-TENANT-404`。
- **优先级**：文档 ACL 只决定“知识内容是否可见”；原工作空间路径只决定“该空间成员能否访问原文件”。知识库下载与引用打开走文档 ACL，不要求用户仍有源文件所在空间的访问权；原空间路径不因文档 ACL 放宽而扩大。两条路径互不继承、互不放大。**知识库下载是文档 ACL 的显式授权**：创建人/管理员把文档设为 `TENANT`/`DEPT`/`USERS` 时，即同时授予这些用户下载源文件的能力；UI 必须在保存前提示这一点。
- **与 M03 的关系**：M03-AC-06 的“源文件打开/下载仍走空间判定”继续适用于**原工作空间路径**；M05 新增的“知识库下载”是独立路径，按文档 ACL，不撤销 M03 的原路径结论。`DEPT-014` 锁定两者互不放宽。
- **可见范围宽于源空间**：允许保存，但文档详情返回 `sourceFileAccessLimited=true`（文档可见集合 ⊃ 源文件所在空间的可见集合），UI 提示“部分可见用户可能无法通过原工作空间打开源文件，可用知识库下载”。是否改为硬阻断由 Product 决定（§13 P4）。

### 5.4 强制执行链

```text
列表 GET /knowledge/documents
  -> SQL: tenant_id AND (visibility='TENANT'
          OR (visibility='DEPT' AND department_id=:myDept AND EXISTS active dept)
          OR created_by=:me
          OR EXISTS (SELECT 1 FROM knowledge_document_acl a
                     WHERE a.tenant_id=d.tenant_id AND a.document_id=d.id AND a.user_id=:me))
  -> count 使用同一谓词

检索（chat / report / knowledge_search）
  -> AgentContextJdbc.search 在 JOIN 上追加同一谓词
  -> 命中后仍以 MySQL 行为权威；OpenSearch 不参与授权

详情/删除/重建索引/改 ACL
  -> 先按同一谓词取文档；取不到 -> 404
  -> 取到但无管理能力 -> 403

引用
  -> 聊天消息引用与产物引用：读取时按同一谓词计算 sourceStatus
     AVAILABLE | NO_ACCESS（ACL 撤销） | DELETED（源文档软删除） | UPDATED（源内容版本变化）
  -> NO_ACCESS / DELETED：title="来源不可访问/已删除"、snippet=""、隐藏定位；不返回正文
  -> UPDATED：保留快照，标注“源内容已更新”，定位指向当前版本
```

- `:myDept` 由 `app_user.department_id` 一次查询得到；无部门时 `DEPT` 分支恒假。`TENANT_ADMIN` 的管理性读取由调用方先解析 `unrestricted` 布尔并传入检索，不在 `knowledge_chunk` 查询里 join 角色表；平台租户不适用。
- **两条路径**：`GET /knowledge/documents/{id}/download` 走文档 ACL；`GET /workspaces/{id}/files/{fileId}/download` 仍只走空间谓词。知识库详情不返回预签名 URL 或对象 key，只返回 `fileId`/`connectorId` 供前端选择路径。
- **引用可用性**：`sourceStatus` 由知识库访问层统一计算；判定依据是当前 ACL + `knowledge_document.deleted_at` + 引用创建时快照的 `source_version`（文档 checksum）与当前 checksum 的比较。优先级：`NO_ACCESS` > `DELETED` > `UPDATED` > `AVAILABLE`。审计/取证所需的快照仍保存在库中（`message_citation`/`artifact_citation`），但 API 在不可访问时不再返回正文、片段、标题或定位。历史引用上区分“已删除”和“不可访问”是有意行为：用户此前已看过该来源，不构成新的存在性泄露；直接对象访问仍统一 404。实现必须按 `document_id` 批量查询，禁止逐条 N+1。
- **统一 404**：`RICCON-TENANT-404`，不区分不存在/跨租户/跨部门/未授权；`403` 只用于“已可见但缺管理能力”。

### 5.5 变更时延与索引一致性

- **MySQL 权威 + 无跨请求缓存**：ACL 变更在下一次请求生效（列表、检索、详情、引用、下载同时生效），与 M03 的成员变更语义一致。
- **OpenSearch 最终一致**：ACL 变更写 `acl_version+1`，并投递 `KNOWLEDGE_INDEX_REQUESTED`（`mode=ACL_ONLY`）刷新索引字段；`ACL_ONLY` 只读现有 `knowledge_chunk` 并重写索引文档，**不调用 `replaceChunks`**，保持 `chunk_id` 稳定（引用依赖它）；不重解析源文件。
- **索引字段不作为授权依据**：M05 不切换检索到 OpenSearch。若未来切换，必须先做“OpenSearch top-K → MySQL 可见性复核”的两阶段过滤，并由 `IT-KB-117` 锁定；禁止直接把 OpenSearch 过滤当授权。
- **部门停用/成员移出**：MySQL 谓词即时生效，无需索引操作。

### 5.6 权限与审计

| 权限                                      | 角色           | 说明                                                                                           |
| ----------------------------------------- | -------------- | ---------------------------------------------------------------------------------------------- |
| `knowledge.read`                          | 全部启用员工   | 列表/详情按 ACL 过滤                                                                           |
| `knowledge.manage`                        | `MEMBER`       | 添加文档；删除/重索引仅限创建人或 `knowledge.acl.manage`（M05 收紧，M02 时代为同租户任意成员） |
| `knowledge.acl.manage`（新增）            | `TENANT_ADMIN` | 任意文档 ACL；创建人由服务层例外授权                                                           |
| `knowledge.connector.read/manage`（新增） | `TENANT_ADMIN` | 连接器列表/配置/同步                                                                           |

审计动作（新增，均需同步 `AuditDetailsPolicy` 白名单）：

| 动作                         | target               | details 白名单                                                                                                                                                                                     |
| ---------------------------- | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `knowledge.document.added`   | `KNOWLEDGE_DOCUMENT` | `sourceType`、`fileId`、`visibility`、`departmentId`、`connectorId`                                                                                                                                |
| `knowledge.document.deleted` | `KNOWLEDGE_DOCUMENT` | `sourceType`、`visibility`                                                                                                                                                                         |
| `knowledge.acl.changed`      | `KNOWLEDGE_DOCUMENT` | `visibility`、`previousVisibility`、`departmentId`、`addedUserCount`、`removedUserCount`、`addedUserIds`、`removedUserIds`（UUID 数组，最多 50，超出置 `truncated=true` 只留计数）、`aclUserCount` |

- 不含姓名、邮箱、文档标题、分块原文；`knowledge.acl.changed` 审计写失败 → 变更失败（fail-closed）。
- 读取/检索不写审计（避免读放大）；ACL 变更、删除、添加、连接器生命周期必须审计。
- 验证：`IT-KB-110`（新动作 details 白名单、未知键 fail-closed、审计失败回滚、四类动作可按租户/操作者/时间/结果查询）、`IT-KB-107`（`sourceFileAccessLimited` 计算与 UI 提示）。

### 5.7 迁移与历史回填

- V12 只加列/表；`visibility` 的 `DEFAULT 'TENANT'` 使全部历史文档保持现状，**不执行全表 UPDATE**。
- `knowledge_document_acl` 初始为空；创建人隐式可见保证历史文档的创建人仍可见。
- 回填不猜测部门：历史文档不自动改为 `DEPT`；需要收窄的文档由创建人/管理员显式修改。
- 校验：迁移测试断言“历史行 visibility=TENANT、department_id 为空、检索结果与迁移前一致”。

### 5.8 开关与灰度

| 开关                                | 默认（首次部署） | 生效点                | 说明                                                             |
| ----------------------------------- | ---------------- | --------------------- | ---------------------------------------------------------------- |
| `RICCON_KB_ACL_ENFORCEMENT_ENABLED` | `false`          | API + Worker 每次查询 | `false` 时谓词退化为 `tenant_id`（等价 M03）；`true` 时启用 §5.4 |
| `RICCON_KB_ACL_MAX_USERS`           | `200`            | API                   | 单文档 USERS 上限                                                |

- 灰度顺序：V12 与代码（ACL off）→ 重灌 v2 索引 → 创建 `DEPT`/`USERS` 文档并核对 → 打开 ACL → 全量越权套件 → gate 以 `true` 取证。
- 关闭开关只回退“强制”，不删除 ACL 数据；重新打开立即恢复。

### 5.9 回滚约束（需用户批准）

- ACL 生效后，**应用回滚到 M05 前版本会忽略 `visibility`，所有 `DEPT`/`USERS` 文档暂时变为租户全员可见**。这是安全回归，不能当作普通回滚。
- 决策（推荐）：**ACL 打开后不提供应用回滚**，只允许前向修复（修复 ACL 逻辑或关闭 `RICCON_KB_ACL_ENFORCEMENT_ENABLED` 并接受同样放宽，需用户书面同意）；schema 回滚只允许从备份恢复。
- 若用户要求保留回滚能力，必须在回滚前把所有 `DEPT`/`USERS` 文档改为 `TENANT`（等于主动放弃 ACL 保护）并记录审计，再回滚应用；该操作作为决策包选项，不在 M05 默认路径。

### 5.10 性能影响

- 检索谓词的 `EXISTS` 使用 `idx_kda_user`；`TENANT`/`DEPT` 分支是常量比较。检索仍以 `knowledge_chunk` 的 `LIKE` 扫描为主，ACL 只增加每行少量比较。
- 列表 `count` 与 `list` 使用同一谓词；`idx_kd_visibility` 覆盖 `TENANT`/`DEPT`，`USERS` 走 `idx_kda_user`。
- 预算：ACL 开启后检索/首字 p95 相对 M04 基线增幅 ≤ 20%，绝对首字 p95 ≤ 3 s（`PERF-M05-001`）；10 万 `knowledge_chunk`、5 000 文档下列表 p95 ≤ 500 ms。
- 若 MySQL 8.4 对 `OR + EXISTS` 选择次优计划，允许改为“先取可见文档 ID 子查询再 JOIN”（同一语义），但必须保留 `EXPLAIN` 证据与 `PERF-M05-001` 对比。

### 5.11 越权测试边界

| ID           | 场景                                                             | 期望                                             |
| ------------ | ---------------------------------------------------------------- | ------------------------------------------------ |
| `TEN-034`    | 租户 A 持有租户 B 文档 ID，读/删/改 ACL/重索引/下载              | `RICCON-TENANT-404`                              |
| `TEN-035`    | 同租户跨部门读取 `DEPT` 文档（列表/详情/检索/引用/下载）         | 统一 404；检索无命中                             |
| `TEN-036`    | `USERS` 文档：未授权用户、被移出用户、停用账号                   | 404；撤销后下一次请求生效                        |
| `TEN-037`    | 连接器同步文档的跨租户/跨部门访问（M05b）                        | 404；不泄露 externalId/title                     |
| `DEPT-011`   | `DEPT` 文档四处一致：本部门可见、他部门 404、下载与引用同判      | 4/4 面一致；无标题/片段/总数泄露                 |
| `DEPT-012`   | 部门停用、成员移出后访问 `DEPT` 文档                             | 下一次请求 404                                   |
| `DEPT-013`   | 部门 → 指定用户切换后，新加入部门者不再自动可见                  | 按新范围判定                                     |
| `DEPT-014`   | 知识库下载不放宽原工作空间路径；非空间成员仍不能走空间下载       | 空间路径 404，知识库下载按 ACL                   |
| `SEC-KB-002` | 非创建者普通员工/平台管理员改 ACL 或读正文                       | 403/无入口；响应不含正文、标题、名单             |
| `SEC-KB-003` | ACL 从 TENANT 收窄为 USERS 后，未授权用户刷新列表/检索/引用/下载 | 无结果；旧引用 `NO_ACCESS`，不展示标题/片段/定位 |
| `IT-KB-117`  | （未来）OpenSearch 检索切换的两阶段复核                          | 若未复核则测试失败                               |

---

## 6. 外部连接器（1 个）

### 6.1 候选对比

| 方案                               | 私有化可行性                       | 增量能力                            | 权限映射                           | 许可/SDK                        | 工作量 | 结论                              |
| ---------------------------------- | ---------------------------------- | ----------------------------------- | ---------------------------------- | ------------------------------- | ------ | --------------------------------- |
| **S3 兼容目录（bucket + prefix）** | 高（客户已有 S3/OSS/MinIO/RustFS） | `ListObjectsV2` + ETag/LastModified | 前缀 + 默认可见范围（TENANT/DEPT） | AWS SDK v2 Apache-2.0（已依赖） | 中     | **推荐**                          |
| Confluence Data Center             | 中（需客户已部署）                 | REST `lastModified` 分页            | 空间 → 部门/用户映射复杂           | 无官方 Java SDK；手写 REST      | 高     | 替代                              |
| 网页/URL 抓取                      | 高                                 | 无原生增量，需内容 diff             | 无                                 | 自研 + HTML 解析                | 高     | 拒绝：SSRF/robots/质量/礼貌成本高 |
| SharePoint                         | 低（Graph 依赖云/混合部署）        | Graph delta                         | Azure AD 组映射                    | MSAL/Graph                      | 高     | 拒绝：与私有化 v1 不符            |

**推荐 S3 连接器**，理由：复用已有 SDK 与对象存储运维能力、增量语义稳定（ETag）、权限模型可退化为“前缀 + 默认可见范围”、无公网依赖。Confluence 作为替代，仅在客户明确要求且有联调环境时另立任务。

> 连接器类型属于产品/高成本选择，需用户批准（§13 U2）。

### 6.2 数据模型（V12）

```sql
CREATE TABLE kb_connector (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id          BINARY(16) NOT NULL COMMENT '连接器 ID（UUIDv7）',
    name        VARCHAR(120) NOT NULL,
    type        VARCHAR(16) NOT NULL COMMENT 'S3',
    status      VARCHAR(16) NOT NULL DEFAULT 'PAUSED' COMMENT 'ACTIVE|PAUSED|ERROR',
    endpoint_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '部署允许列表 ID',
    config      JSON NOT NULL COMMENT 'bucket/prefix/region/includeExtensions/excludeGlobs',
    default_visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT|DEPT',
    default_department_id BINARY(16) NULL,
    credential_ciphertext VARBINARY(8192) NULL COMMENT '信封加密（ADR-0006，purpose=connector-credential）',
    credential_key_id VARCHAR(64) NULL,
    credential_masked_hint VARCHAR(128) NULL COMMENT '仅掩码',
    sync_mode   VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL|SCHEDULED',
    schedule_cron VARCHAR(64) NULL,
    next_sync_at DATETIME(6) NULL,
    last_sync_at DATETIME(6) NULL,
    last_success_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    last_sync_summary JSON NULL COMMENT 'scanned/created/updated/skipped/failed/deleted',
    created_by  BINARY(16) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at  DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_connector_name (tenant_id, name),
    KEY idx_kb_connector_schedule (status, next_sync_at),
    CONSTRAINT fk_kb_connector_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_kb_connector_department FOREIGN KEY (tenant_id, default_department_id)
        REFERENCES department (tenant_id, id),
    CONSTRAINT chk_kb_connector_type CHECK (type IN ('S3')),
    CONSTRAINT chk_kb_connector_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR')),
    CONSTRAINT chk_kb_connector_visibility CHECK (default_visibility IN ('TENANT', 'DEPT')),
    CONSTRAINT chk_kb_connector_mode CHECK (sync_mode IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT chk_kb_connector_visibility_department CHECK (
        (default_visibility = 'DEPT' AND default_department_id IS NOT NULL)
        OR (default_visibility <> 'DEPT' AND default_department_id IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库外部连接器（M05）';

CREATE TABLE kb_connector_item (
    tenant_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id           BINARY(16) NOT NULL,
    connector_id BINARY(16) NOT NULL,
    external_id  VARCHAR(1000) NOT NULL COMMENT '源对象 key（仅展示）',
    external_id_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256(external_id)，用于唯一键',
    external_version VARCHAR(255) NULL COMMENT 'ETag/version',
    checksum     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '内容 SHA-256',
    document_id  BINARY(16) NULL COMMENT '对应知识库文档（逻辑引用）',
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SYNCED|SKIPPED|FAILED|DELETED',
    error_code   VARCHAR(64) NULL,
    last_seen_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at   DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_item_external (tenant_id, connector_id, external_id_hash),
    KEY idx_kb_item_status (tenant_id, connector_id, status),
    KEY idx_kb_item_document (tenant_id, document_id),
    CONSTRAINT fk_kb_item_connector FOREIGN KEY (tenant_id, connector_id) REFERENCES kb_connector (tenant_id, id),
    CONSTRAINT chk_kb_item_status CHECK (status IN ('PENDING', 'SYNCED', 'SKIPPED', 'FAILED', 'DELETED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '连接器源对象同步状态（M05）';
```

- `external_id_hash` 规避 `utf8mb4` 长唯一键限制并保持大小写敏感；展示用原文。
- `status` 语义：`ACTIVE` 参与定时同步；`PAUSED` 只停定时、仍允许手动同步；`ERROR` 表示最近同步失败、仍可手动重试。新建默认 `PAUSED`，管理员完成连接测试后显式启用。
- `uk_kb_connector_name` 使软删除后同名不可重建；UI 要求改名，避免“删除后重建”产生两个同源连接器。
- 连接器文档的原文存对象存储：key 由服务端派生 `tenants/{tenantId}/connector/{yyyy}/{documentId}`。**这是 ADR-0009 §6 key 类别（`file|artifact|export`）的扩展**，由 Lead 指派 ADR-0009 增量修订；不自造“系统工作空间”，也不把连接器文件塞进 `file` 表（`workspace_id` 外键不成立）。

### 6.3 同步模式、去重与删除传播

| 项        | 决策                                                                                                                                                                          |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 触发      | 手动 `POST /knowledge/connectors/{id}/sync`（202）；`SCHEDULED` 由 `next_sync_at` 条件更新领取，单实例安全                                                                    |
| 连接测试  | `POST /knowledge/connectors/{id}/test` 只做一次 `ListObjectsV2`（`max-keys=1`），空 bucket 也算成功；返回 `auth/network/endpoint/config` 分类的中文原因，不写对象、不返回凭据 |
| 全量/增量 | 首次全量；之后按 `external_id_hash` + ETag/checksum 增量；未变化只更新 `last_seen_at`                                                                                         |
| 去重      | 同一 `(connector, external_id)` 唯一；内容 checksum 相同且文档 `INDEXED` → 跳过；跨连接器不去重（可能是有意保留的两份）                                                       |
| 新增      | 下载 → 校验大小/类型 → 存对象 → 建 `knowledge_document(CONNECTOR)` → 投递索引事件；创建请求必须显式给 `defaultVisibility`（缺省 400，不依赖 DB 默认值）                       |
| 更新      | 内容变化才重写对象、更新 checksum、`acl_version` 不变、投递重索引                                                                                                             |
| 源端删除  | 本轮 list 未见且 `last_seen_at < syncStartedAt` → 项 `DELETED`、文档软删除、投递 `mode=DELETE` 清理索引                                                                       |
| 源端重现  | 生成新 item 行与新文档；旧项保留 tombstone 180 天，避免“删除-重现”风暴                                                                                                        |
| 部分失败  | 单项失败记 `FAILED` + error_code，同步继续；总结 `last_sync_summary`                                                                                                          |
| 重试      | 失败项在下一次同步重试；同步级失败（认证/网络）按 1 m/5 m/15 m 退避 3 次后 `ERROR`                                                                                            |

- 租户停用：API 拒绝新同步请求；worker 领取事件后再次校验 `tenant.status='ACTIVE'`，非 ACTIVE 直接跳过并记日志（不把 connector 标成 ERROR，恢复后下一次定时/手动同步继续）。知识库读取本身由既有认证/租户状态过滤器拦截。
- 索引入口：连接器文档 `file_id` 为空、`connector_id` 非空；`KnowledgeIndexService.loadChunks` 增加“连接器对象”分支，按 `(tenantId, documentId)` 派生 key 读取对象并走同一 `FileContentExtractor`，不能落进 SEED 的“读取已有分块”路径。

### 6.4 凭据托管

- 使用 ADR-0006 信封加密：`purpose=connector-credential`，AAD `tenantId|connector-credential|keyId|v1`；存 `kb_connector.credential_ciphertext` + `credential_key_id`。
- 写入端点 `PUT /knowledge/connectors/{id}/credential` 只写不读；响应/日志/审计只出现 `credential_masked_hint`（如 `AKIA…7F2`）。轮换 = 覆盖写入 + 审计 `knowledge.connector.credential.changed`；旧密文不保留。
- API 不调用连接器源；只有 worker 解密并在内存中使用（`char[]`/`byte[]`，用后清零），不写日志。
- 主密钥缺失/不可解 → worker 启动失败（ADR-0006 既有行为），不在 M05 降级。

### 6.5 SSRF、大小与类型限制

- 端点只来自部署允许列表 `RICCON_CONNECTOR_ENDPOINTS`（JSON：`id`、`url`、`label`、`allowHttp`、`production`）；连接器只存 `endpoint_id`，不能存任意 URL。
- URL 校验复用 M04 端点规则：HTTPS（`allowHttp=true` 的显式内部端点除外）、无用户信息、无查询串、无重定向；解析后的 IP 永不允许 `169.254.0.0/16`（云元数据）、`::1` 与 `fe80::/10` 链路本地；RFC1918 仅在端点显式允许时放行（内部 S3 的常态）。
- S3 客户端禁用区域/跨域自动重定向（或对每个 Location 重新做同一校验）；不允许 SDK 自行切换到未允许的主机。
- 对象限制：单对象 ≤ 100 MB（`RICCON_CONNECTOR_MAX_OBJECT_BYTES`）、单次同步 ≤ 5 000 对象、≤ 2 GB 总量、单项下载超时 60 s、同步总超时 60 min。
- 类型白名单：`pdf,docx,xlsx,png,jpg,jpeg,txt,md`；其他类型记为 `SKIPPED` + `KB_CONNECTOR_TYPE_UNSUPPORTED`，不下载。
- 解压/解析沿用 POI/PDFBox 的 ZIP 与页数限制（§3.1、§4.4）；连接器不额外解包归档文件（`.zip` 不在白名单）。

### 6.6 失败、可观察性与审计

| 场景                  | 状态                                            | 可观察性                                                                    |
| --------------------- | ----------------------------------------------- | --------------------------------------------------------------------------- |
| 认证失败              | `ERROR` + `KB_CONNECTOR_AUTH`                   | 指标 `riccon_connector_sync_failures_total{reason=auth}`；`last_error_code` |
| 网络不可达/超时       | 退避重试后 `ERROR` + `KB_CONNECTOR_UNREACHABLE` | 同上 `reason=network`                                                       |
| 单对象过大/类型不支持 | 项 `SKIPPED`/`FAILED`                           | 同步摘要计数                                                                |
| 同步超时              | `ERROR` + `KB_CONNECTOR_TIMEOUT`                | 下一轮增量继续                                                              |
| 凭据不可解            | `ERROR` + `KB_CONNECTOR_CREDENTIAL`             | worker 日志只记 connectorId                                                 |

审计动作：`knowledge.connector.created/updated/deleted/status.changed/credential.changed/sync.started/sync.completed/sync.failed`，target `KNOWLEDGE_CONNECTOR`（新增枚举值，`target_type` 列无 CHECK，无需迁移）；details 只含 `connectorId`、`type`、`endpointId`、`bucket`、`prefix`（截断 ≤200）、`syncId`、计数与 `errorCode`，不含凭据/对象正文。

### 6.7 验证

- `IT-CONN-101`：配置必须选默认可见范围、连接测试成功/失败原因、凭据加密入库且只显示掩码、轮换后新请求生效。
- `IT-CONN-102`：首次全量 100 对象 → 100 文档；重复触发不产生重复文档；不支持格式跳过且不生成失败文档。
- `IT-CONN-103`：源端新增/修改/删除 → 增量计数正确；修改仍是同一文档；删除软删除、索引清理、列表消失、旧引用 `DELETED`；无变化同步为 0。
- `IT-CONN-104`：源端不可达/凭据失效/部分对象解析失败 → 可读原因、上次成功时间、失败数；重试只处理失败项。
- `IT-CONN-105`：超时、退避、同步摘要、失败状态与幂等重试。
- `IT-CONN-106`：配置/同步/失败/凭据轮换审计（含计数，不含凭据/正文）。
- `IT-CONN-107`：连接器文档的保留与删除传播（180 天默认、软删除不命中）。
- `SEC-CONN-001`：凭据与 Authorization 头不出现在页面/日志/审计/错误/导出，只出现掩码。
- `SEC-CONN-002`：连接器内容中的中英文提示注入不改变指令、不泄露凭据、不触发未授权工具。
- `SEC-CONN-003`：非允许列表端点、重定向、`169.254.169.254`、未允许的 `http://` 全部拒绝且不发起连接。
- `SEC-CONN-004`：非管理员触发同步/读配置 → 403；跨租户 connectorId → 404。

---

## 7. 数据与契约

### 7.1 OpenSearch mapping v2 与重灌

新增字段（`dynamic: strict` 下必须随 mapping 一起建）：

| 字段             | 类型    | 用途                            |
| ---------------- | ------- | ------------------------------- |
| `visibility`     | keyword | 诊断/未来过滤                   |
| `department_id`  | keyword | 同上                            |
| `acl_version`    | integer | 识别索引文档是否落后            |
| `source_type`    | keyword | `UPLOAD` / `SEED` / `CONNECTOR` |
| `connector_id`   | keyword | 连接器排障                      |
| `ocr_confidence` | float   | 质量观测                        |
| `bbox_x/y/w/h`   | integer | 区域定位                        |

- 新索引名 `riccon-kb-v2-{tenant}`（`RICCON_OPENSEARCH_INDEX_VERSION=v2`）；别名 `riccon-kb-{tenant}` 在重灌完成后**逐租户原子切换**。
- 重灌由 `scripts/ops/reindex-knowledge.mjs` 触发：按 `knowledge_document.status='INDEXED'` 分批投递 `KNOWLEDGE_INDEX_REQUESTED`（`reason=BACKFILL_V2`），完成后切别名；v1 保留 7 天供回滚。
- 回滚：别名切回 v1（v1 未被删除）；v2 索引可丢弃。因为运行期检索不依赖 OpenSearch，**索引回滚不影响用户请求**。
- 迁移测试：`IT-KB-113` 断言 v2 mapping 字段、别名切换/切回 v1、重灌后文档数/分块 ID 与 MySQL 一致且无重复。

### 7.2 Flyway V12 与回滚

- `V12__knowledge_depth.sql`，接 V11；worker schema 期望 11 → 12。
- 内容：§5.1、§6.2 的 DDL；`message_citation`/`artifact_citation` 各加可空 `source_version`；新增权限 `knowledge.acl.manage`、`knowledge.connector.read`、`knowledge.connector.manage` 及 `TENANT_ADMIN` 角色回填（沿用 V9 的 `INSERT ... SELECT` 模式）；`biz_*` 无。
- 兼容性：新增列可空或带默认；`source_type` 与 `visibility` 的 CHECK 变更在 `knowledge_document`（小表）上执行，`ALGORITHM=INPLACE, LOCK=NONE`；若 MySQL 拒绝，记录实际锁等待并改维护窗口。`file_chunk`/`knowledge_chunk` **不加新 CHECK**（bbox/置信度列可空）。
- 回滚 SQL（仅应急、需备份与用户批准）：

```sql
-- 顺序：先删子表，再删列/约束。回滚前必须确认没有 CONNECTOR 文档或已归档。
DROP TABLE IF EXISTS kb_connector_item;
DROP TABLE IF EXISTS kb_connector;
DROP TABLE IF EXISTS knowledge_document_acl;
ALTER TABLE knowledge_document
    DROP FOREIGN KEY fk_kd_department,
    DROP CHECK chk_kd_visibility_department,
    DROP CHECK chk_kd_visibility,
    DROP CHECK chk_knowledge_document_source,
    ADD CONSTRAINT chk_knowledge_document_source CHECK (source_type IN ('UPLOAD','SEED')),
    DROP KEY idx_kd_retention,
    DROP KEY idx_kd_visibility,
    DROP COLUMN retention_expires_at,
    DROP COLUMN acl_version,
    DROP COLUMN parse_warnings,
    DROP COLUMN parse_summary,
    DROP COLUMN parse_engine,
    DROP COLUMN external_id,
    DROP COLUMN connector_id,
    DROP COLUMN department_id,
    DROP COLUMN visibility;
DELETE FROM role_permission WHERE permission_code IN
    ('knowledge.acl.manage','knowledge.connector.read','knowledge.connector.manage');
DELETE FROM permission WHERE code IN
    ('knowledge.acl.manage','knowledge.connector.read','knowledge.connector.manage');

ALTER TABLE message_citation DROP COLUMN source_version;
ALTER TABLE artifact_citation DROP COLUMN source_version;
```

> 该脚本只在“没有任何 CONNECTOR 文档/ACL 使用”时可执行；否则先归档或回退到 `TENANT`。生产禁止直接执行，必须走 M09 备份/演练与用户批准。

### 7.3 outbox、异步任务与双通道

| 事件                                                   | 方向         | 说明                                                                                               |
| ------------------------------------------------------ | ------------ | -------------------------------------------------------------------------------------------------- |
| `riccon.knowledge.index.requested.v1`（扩展）          | API → Worker | payload 新增可选 `mode`（`FULL` / `ACL_ONLY` / `DELETE`）、`reason`；旧消费者默认 `FULL`，向后兼容 |
| `riccon.knowledge.connector.sync.requested.v1`（新增） | API → Worker | payload：`connectorId`、`syncId`、`startedAt`；幂等键 `connector:{id}:sync:{syncId}`               |

- **双通道**：`RICCON_WORKER_OUTBOX_LANES=default,heavy`；`heavy` 通道包含 `FILE_PARSE`、`KNOWLEDGE_INDEX`、`KNOWLEDGE_CONNECTOR_SYNC`，默认并发 2；`default` 保持 chat/report 事件顺序处理。领取 SQL 增加 `event_type IN (...)` 过滤；需要时新增索引 `(status, event_type, available_at)`。
- **索引模式**：`FULL` 允许重解析并原子替换分块；`ACL_ONLY` 只读现有分块、不替换（保 `chunk_id`）；`DELETE` 删除该文档索引与分块（软删除后清理）。`FULL` 的“缺失页补齐”只对 OCR 文档生效（§4.5）。
- **租约续期**：新增 `OutboxLeaseHeartbeat`，在 handler 执行期间每 `leaseDuration/3` 续期一次，续期失败则中断 handler 并放弃结果（避免双执行）。最大处理时长 `RICCON_WORKER_OUTBOX_MAX_PROCESSING=30m`，超时按可重试失败处理。心跳必须绑定单个 `eventId` 与 `leaseOwner`，不得用共享定时器批量续期；`lease_owner` 需能区分同实例的不同处理线程（如 `instance#lane#thread`）。
- 长任务不放在 HTTP 请求线程；API 只写事件并返回 202。
- 幂等：索引事件 `doc:{id}:index:{attempt}`；ACL 刷新 `doc:{id}:acl:{acl_version}`；删除 `doc:{id}:delete:{acl_version}`；连接器按 `syncId`。

### 7.4 OpenAPI `1.4.0-m05` 变更清单（Builder 单点实现）

| 方法   | 路径                                                           | 权限                                | 说明                                                                                |
| ------ | -------------------------------------------------------------- | ----------------------------------- | ----------------------------------------------------------------------------------- |
| PUT    | `/api/v1/knowledge/documents/{documentId}/acl`                 | 创建人 或 `knowledge.acl.manage`    | 整体替换 `{visibility, departmentId?, userIds?}`；200 返回文档                      |
| GET    | `/api/v1/knowledge/documents/{documentId}/acl`                 | 同上                                | 返回 `visibility`、`departmentId`、`users[]`（userId/displayName）、`creatorUserId` |
| GET    | `/api/v1/knowledge/documents/{documentId}/download`            | `knowledge.read` + 文档 ACL         | 短时预签名或流；无源文件 → `RICCON-KB-404`；不返回对象 key                          |
| POST   | `/api/v1/knowledge/documents/{documentId}/reindex?force=false` | `knowledge.manage`（创建人/管理员） | 默认只补缺失页；`force=true` 整本重做                                               |
| GET    | `/api/v1/knowledge/connectors`                                 | `knowledge.connector.read`          | 连接器列表（不含凭据）                                                              |
| POST   | `/api/v1/knowledge/connectors`                                 | `knowledge.connector.manage`        | 创建；凭据可选写入                                                                  |
| PATCH  | `/api/v1/knowledge/connectors/{connectorId}`                   | 同上                                | 改配置/可见范围/调度；不支持改 type                                                 |
| DELETE | `/api/v1/knowledge/connectors/{connectorId}`                   | 同上                                | 软删除；不自动删除已导入文档（需显式选择）                                          |
| PUT    | `/api/v1/knowledge/connectors/{connectorId}/credential`        | 同上                                | 只写不读；返回掩码                                                                  |
| POST   | `/api/v1/knowledge/connectors/{connectorId}/test`              | 同上                                | 连接测试（只读列前缀/鉴权），中文原因；不返回凭据                                   |
| POST   | `/api/v1/knowledge/connectors/{connectorId}/sync`              | 同上                                | 202；并发同步 409                                                                   |
| GET    | `/api/v1/knowledge/connectors/{connectorId}/items`             | `knowledge.connector.read`          | 分页同步状态（无原文）                                                              |

Schema 增量（additive）：

- `KnowledgeDocument`：`visibility`、`departmentId`、`aclUserCount`、`parseEngine`、`warnings[]`、`parseSummary`、`connectorId`、`externalId`、`sourceFileAccessLimited`（文档可见范围宽于源文件空间）；`sourceType` 枚举加 `CONNECTOR`。
- `KnowledgeDocumentCreateRequest`：新增可选 `visibility`/`departmentId`/`userIds`（缺省 `TENANT`，与 §13.1 U5 一致）；`PUT .../acl` 用于后续整体替换。
- 新增 `KnowledgeDocumentAcl`、`KnowledgeDocumentAclUpdate`、`Connector`、`ConnectorCreate/Update`（`ConnectorCreate` 必填 `defaultVisibility`）、`ConnectorCredential`、`ConnectorItemPage`。
- `Citation` 与分块响应：可选 `bbox {x,y,w,h}`、`ocrConfidence`、`sourceStatus`（`AVAILABLE`/`NO_ACCESS`/`DELETED`/`UPDATED`）；`NO_ACCESS`/`DELETED` 时 `title`/`snippet` 返回空串、定位为 null（保持契约 required 字段，不删字段）；不新增 `locatorType` 枚举值。
- 契约文案：`/knowledge/documents` 列表/创建与 `/workspaces/{id}/files` 相关描述中“M03 知识库为租户级共享、文档级权限在后续版本”必须改为 M05 ACL 语义（列表/检索/下载按 ACL；原空间路径不变），并移除过期声明（Product AC-22）。
- 错误码：`RICCON-KB-400`（ACL/连接器配置非法）、`RICCON-KB-404`（文档存在但无源文件，仅 ACL 通过后）、`RICCON-KB-409`（重复同步/重名）、`RICCON-KB-503`（连接器/索引服务不可用）；跨租户继续 `RICCON-TENANT-404`；权限继续 `RICCON-AUTH-403`。

### 7.5 功能开关与灰度

见 §5.8；另加 `RICCON_OCR_ENABLED`（worker）、`RICCON_CONNECTOR_ENABLED`（API + worker）。开关只来自部署环境（不做 DB 开关）：M05 的三个开关没有租户级自助修改需求，且回滚本来就需要重部署；减少一张全局表与一套 UI。

### 7.6 回滚步骤

1. `RICCON_OCR_ENABLED=false`、`RICCON_CONNECTOR_ENABLED=false` → 重启 worker（OCR/连接器文档保持现状，不新增）。
2. `RICCON_KB_ACL_ENFORCEMENT_ENABLED=false` → 重启 API（仅当用户书面接受放宽；否则不走此步）。
3. 索引别名切回 v1（可选，无用户影响）。
4. 应用回滚：仅在“未产生 DEPT/USERS 文档”或用户批准放宽后允许；否则只做前向修复。
5. 数据库不做 down migration；V12 结构由旧应用忽略（旧应用不读新列/新表）。

---

## 8. T4 威胁模型增量（TM-55..TM-69）

> 本表是 ADR 级增量；`docs/security/THREAT-MODEL.md` 的正式编号/章节由威胁模型 owner 在 M05 gate 前合入（§13 L2），本 ADR 不改该文件。

| ID    | 威胁                                                             | 影响                      | 控制                                                                                                    | 验证                                              |
| ----- | ---------------------------------------------------------------- | ------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| TM-55 | 上传/连接器/OCR 文本中的提示注入（隐藏指令、白字、表格公式文本） | 模型越权/数据外泄         | 检索内容一律不可信数据、不进入系统指令（M02 既有）；OCR/Excel 文本同样经 `UntrustedData`；工具仍走 HITL | `SEC-KB-004`、`SEC-CONN-002`                      |
| TM-56 | ACL 绕过：列表/详情/检索/引用/下载任一入口漏谓词                 | 租户内越权                | 单一谓词集中在 `KnowledgeAccessJdbc`；所有入口复用；越权矩阵                                            | `TEN-034..037`、`DEPT-011..014`、`SEC-KB-002/003` |
| TM-57 | ACL 收窄后缓存/索引延迟导致继续可见                              | 越权残留                  | MySQL 权威、无跨请求缓存；索引字段不作授权；ACL_ONLY 刷新                                               | `IT-KB-108`、`SEC-KB-003`                         |
| TM-58 | 通过 404/403 差异枚举文档存在性                                  | 信息泄露                  | 统一 404；403 只在可见后返回；列表不出现                                                                | `SEC-KB-002`、`TEN-034`                           |
| TM-59 | OCR/解析资源耗尽（超大图、超多页、ZIP 炸弹）                     | Worker 不可用             | 像素/页/行/字符/条目上限、逐页释放、heavy 通道并发 2、超时                                              | `SEC-KB-005`、`PERF-M05-001`                      |
| TM-60 | 恶意 OOXML/PDF（外部链接、宏、畸形结构）                         | RCE/SSRF                  | 不执行公式/宏/外部链接；POI/PDFBox 只解析；ZIP 限制；无 shell                                           | `IT-KB-102/116`、`SEC-KB-005`                     |
| TM-61 | 连接器 SSRF（端点/重定向/元数据地址）                            | 内网探测/凭据外发         | 端点允许列表、HTTPS、无重定向、禁 `169.254/16`、RFC1918 仅显式允许                                      | `SEC-CONN-003`                                    |
| TM-62 | 连接器凭据泄露（响应/日志/审计/DB）                              | 源端接管                  | 信封加密、只写不读、掩码、日志禁明文、启动校验                                                          | `IT-CONN-101`、`SEC-CONN-001`                     |
| TM-63 | 源端删除未传播                                                   | 过期/已撤权内容继续被检索 | 每轮 list + `last_seen_at` 对账；软删除 + 索引清理；tombstone                                           | `IT-CONN-103`                                     |
| TM-64 | 审计缺失/可否认（ACL、连接器、删除）                             | 无法追责                  | 同事务审计、fail-closed、details 白名单                                                                 | `IT-KB-110`、`IT-CONN-106`、`IT-AUDIT-*`          |
| TM-65 | 索引重灌/别名切换把 A 租户数据写入 B                             | 跨租户泄露                | 索引名由 `TenantContext` 派生；写入带 `tenant_id`；别名逐租户切换                                       | `IT-KB-113`、`TEN-034`                            |
| TM-66 | OCR 引擎/权重供应链投毒                                          | 恶意输出/RCE              | 固定版本与文件哈希、镜像 SBOM、Trivy、启动自检输出指纹                                                  | `scripts/ops/ocr-check`、`commands.security`      |
| TM-67 | 文档可见范围宽于源文件空间造成误解                               | 用户以为能打开却 404      | 创建/编辑警告；详情标注；提供知识库下载；不返回预签名 URL/对象 key                                      | `IT-KB-107`                                       |
| TM-68 | 引用快照在 ACL 撤销/源删除后泄露片段                             | 内容残留                  | 聊天与产物引用读取时统一计算 `sourceStatus` 并遮蔽正文/片段/标题/定位；库内快照仅供审计/取证            | `SEC-KB-003`、`IT-CONN-103`                       |
| TM-69 | 连接器同步放大（一次巨量对象）拖垮 Worker                        | 可用性                    | 单次对象/字节/时间上限；heavy 通道；增量                                                                | `PERF-M05-001`、`IT-CONN-105`                     |

- `SEC-KB-004`/`SEC-CONN-002` 的夹具由 B4/B5/B6 提供、V2 独立复核，复用 M02 的 `UntrustedData` 用例集；`IT-WORKER-LANE-001`/`IT-WORKER-LEASE-001` 归 B1。

---

## 9. 性能预算与可测口径

| 指标                  | 预算                | 口径                                                                | 验证           |
| --------------------- | ------------------- | ------------------------------------------------------------------- | -------------- |
| 首字 p95              | ≤ 3 s               | 假模型、50 并发、ACL 开启、10 万 `knowledge_chunk`                  | `PERF-M05-001` |
| 稳态 JSON API p95     | ≤ 500 ms            | 知识库列表/详情/ACL 写；排除登录/setup/cleanup（M04 F-013 口径）    | `PERF-M05-001` |
| ACL 谓词增量          | ≤ 20%               | 同一数据集与 M04 基线对比                                           | `PERF-M05-001` |
| 索引延迟（无 OCR）    | p95 ≤ 60 s          | create → `INDEXED`                                                  | `PERF-M05-002` |
| 索引延迟（OCR 20 页） | p95 ≤ 120 s         | 2 并发 OCR                                                          | `PERF-M05-002` |
| OCR 单页 p95          | ≤ 3 s               | A4 200 DPI、2 并发、`tessdata_fast`                                 | `PERF-M05-002` |
| 解析失败率            | ≤ 1%                | 固定 50 文件集（含损坏/加密各 2）                                   | `PERF-M05-002` |
| 连接器同步            | 1 000 对象 ≤ 15 min | 增量、无 >10 MB 对象                                                | `PERF-M05-003` |
| 队列等待              | p95 ≤ 5 min         | 50 并发上传/同步提交后的 heavy 通道（并发 2）；chat/report 不受影响 | `PERF-M05-004` |

- 性能脚本沿用 `e2e/perf/mvc-smoke.js` 的 profile 与环境指纹；M05 新增场景或独立 `e2e/perf/knowledge-smoke.js`，报告必须记录 `RICCON_FAKE_MODEL=1`、`RICCON_DB_POOL_SIZE≥25`、索引版本与 OCR 引擎版本。
- `PERF-M05-001` 是 Product AC-20 的判定口径；`PERF-M05-002..004` 是架构补充观测（OCR/解析、连接器、队列等待），不替代 001，也不单独构成 AC 通过。
- 首字预算不因知识库检索退化：ACL 谓词、OCR 队列、连接器同步都不得进入 chat 请求线程。

---

## 10. 数据保留与删除

- 文档软删除（`deleted_at`）；检索/列表立即不可见；分块与对象保留 30 天恢复窗口后由 M09 维护任务清理。
- 正常保留：`retention_expires_at` 默认创建后 180 天（合同 `data.retention`；租户可用 `tenant.retention_days` 覆盖），历史行 NULL 按 `created_at + 180 天` 计算（不回溯 UPDATE）；界面显示当前策略（Product AC-21）。
- 到期清理：M05 提供最小任务（API 每日 03:00 `Asia/Shanghai`，批量 500）：条件 `status='INDEXED' AND deleted_at IS NULL AND COALESCE(retention_expires_at, created_at + 180 天) <= now` → 软删除 + `mode=DELETE` 索引事件 + 审计 `knowledge.document.deleted`（`reason=retention`）；条件更新保证多实例幂等。物理对象/分块清理仍归 M09（软删除后 30 天窗口）。
- 索引清理：删除事件 `mode=DELETE` 立即执行 `_delete_by_query`；失败可重试，重试不影响“已软删除即不可见”。
- 连接器项 tombstone 保留 180 天（防“删除-重现”风暴），到期由 M09 清理；`kb_connector` 软删除后凭据密文保留 30 天再置空。
- 引用：聊天与产物引用读取时重判 ACL/删除/版本并返回 `sourceStatus`；不可访问时不返回正文、片段、标题或定位（TM-68）。审计/取证所需的快照仍保留在库中，不随展示遮蔽删除。
- 审计 ≥180 天不变；知识库/连接器事件与业务同事务，审计失败业务失败。
- 租户注销：随对象存储前缀与租户 DEK 删除（M09）；本模块不新增跨境/跨租户清理逻辑。

---

## 11. AC 映射（Product §11 草案，待评审）

> `docs/PRODUCT.md` §11 的 M05 草案（2026-11-22）已列出 22 条 AC；本表用于设计自检与一致性复核，**不是验收依据**。AC 编号与测试线索以 Product 定稿为准；若冲突，以 AC 为准并回改 ADR。Product 草案的测试 ID（`IT-KB-1xx`、`IT-CONN-1xx`、`SEC-KB-00x`、`SEC-CONN-00x`、`TEN-03x`、`DEPT-01x`）是本 ADR 的唯一命名口径，不使用旧草案的 `IT-KB-XLSX-*`/`IT-KB-OCR-*`/`IT-KB-ACL-*` 编号。

| AC        | 主题                                                    | ADR 章节         | 测试线索（Product 草案）                          |
| --------- | ------------------------------------------------------- | ---------------- | ------------------------------------------------- |
| M05-AC-01 | Excel 多表/空表/上限/损坏与非 XLSX 的状态               | §3.1–3.4         | `IT-KB-101/102`、`E2E-M05-01`                     |
| M05-AC-02 | Excel 引用显示工作表与单元格/区域并可定位               | §3.2、§5.4       | `IT-KB-103`、`E2E-M05-01`                         |
| M05-AC-03 | 图片/扫描件 OCR 成功、页/区域引用；文本型 PDF/DOCX 不变 | §4.3–4.5         | `IT-KB-104`、`E2E-M05-02`                         |
| M05-AC-04 | OCR 四态（成功/低置信/部分失败/失败）与幂等重试         | §4.5             | `IT-KB-105`、`E2E-M05-02`                         |
| M05-AC-05 | 本地 OCR 零出网、关闭开关、无凭据/内部地址              | §4.4、§4.6       | `SEC-KB-001`、`E2E-M05-02`                        |
| M05-AC-06 | ACL 最小集合、创建者/租户管理员、历史文档默认租户全员   | §5.1、§5.2、§5.7 | `IT-KB-106/107`、`SEC-KB-002`、`E2E-M05-03`       |
| M05-AC-07 | 列表/检索/引用/下载四处一致、统一 404、旧引用遮蔽       | §5.3、§5.4       | `SEC-KB-003`、`DEPT-011`、`TEN-034`、`E2E-M05-03` |
| M05-AC-08 | ACL 变更下一次请求生效、部门停用/移出、审计             | §5.5、§5.6       | `IT-KB-108`、`DEPT-012`、`E2E-M05-03`             |
| M05-AC-09 | 跨租户/跨部门/指定用户越权套件零漏检                    | §5.11            | `TEN-034..037`、`DEPT-011..014`、`SEC-KB-002/003` |
| M05-AC-10 | 连接器配置、默认可见范围、连接测试、凭据掩码            | §6.2、§6.4       | `IT-CONN-101`、`SEC-CONN-001`、`E2E-M05-04`       |
| M05-AC-11 | 首次同步、状态与计数、不支持格式跳过                    | §6.3             | `IT-CONN-102`、`E2E-M05-04`                       |
| M05-AC-12 | 增量新增/修改/删除、引用状态“已更新/已删除”             | §6.3、§5.4       | `IT-CONN-103`、`E2E-M05-04`                       |
| M05-AC-13 | 源不可达/凭据失效/部分失败、重试与审计                  | §6.6             | `IT-CONN-104/106`、`E2E-M05-05`                   |
| M05-AC-14 | 连接器与 OCR 内容的提示注入防护                         | §8               | `SEC-CONN-002`、`SEC-KB-004`                      |
| M05-AC-15 | 解析/OCR/同步失败可读、幂等重试、不重复文档             | §3.4、§4.5、§6.6 | `IT-KB-102/105/109`、`IT-CONN-105`、`E2E-M05-05`  |
| M05-AC-16 | 文档/ACL/连接器/同步审计；写失败即操作失败              | §5.6、§6.6       | `IT-KB-110`、`IT-CONN-106`、`E2E-M05-05`          |
| M05-AC-17 | 429 透传限额类型/绝对重置时间（R-M04-01）               | 前端/契约（M04） | `E2E-M05-06`                                      |
| M05-AC-18 | 相对重置时间“还有 X 小时/天”（R-M04-02）                | 前端（M04）      | `IT-QUOTA-106`、`E2E-M05-06`                      |
| M05-AC-19 | 四页加载/空/错误/禁用/无权限/成功态、375 px、键盘、axe  | 前端             | `E2E-M05-07`、`@a11y`                             |
| M05-AC-20 | 50 并发首字/API p95；OCR/同步积压不阻塞 chat/report     | §7.3、§9         | `PERF-M05-001`、`IT-WORKER-LANE-001`              |
| M05-AC-21 | 默认 180 天、软删除不命中、清理与删除传播               | §10              | `IT-KB-111`、`IT-CONN-107`、`E2E-M05-08`          |
| M05-AC-22 | J1/J2/M03/M10/M04 回归、契约真实实现、过期文案替换      | §7.4、§12        | `IT-KB-112`、`E2E-M05-09`、契约测试               |

测试 ID 口径（本 ADR 使用，Builder/Verifier 共用）：

| ID                    | 内容                                               |
| --------------------- | -------------------------------------------------- |
| `IT-KB-101`           | Excel 多表/行带/空表/隐藏表/上限与已索引范围       |
| `IT-KB-102`           | 损坏/加密/伪装扩展名/非 XLSX 的稳定失败态          |
| `IT-KB-103`           | Excel 引用定位与打开                               |
| `IT-KB-104`           | OCR 成功、页码/区域/置信度、混合页                 |
| `IT-KB-105`           | OCR 四态、失败页与重试幂等                         |
| `IT-KB-106`           | ACL CRUD、可见范围校验、权限                       |
| `IT-KB-107`           | 历史迁移默认值、`sourceFileAccessLimited`、UI 提示 |
| `IT-KB-108`           | ACL 生效时延、部门停用/成员移出                    |
| `IT-KB-109`           | 失败与恢复（共享，覆盖文档/OCR/同步）              |
| `IT-KB-110`           | 审计动作、details 白名单、fail-closed              |
| `IT-KB-111`           | 保留 180 天、软删除、清理                          |
| `IT-KB-112`           | 既有 E2E/越权/契约回归                             |
| `IT-KB-113`           | 索引 v2 mapping、别名切换/回滚、重灌幂等           |
| `IT-KB-114`           | EXIF 方向与 90° 重试                               |
| `IT-KB-116`           | ZIP/像素/页数资源上限                              |
| `IT-KB-117`           | 未来 OpenSearch 两阶段复核（占位）                 |
| `IT-CONN-101..107`    | 连接器配置/首次/增量/失败/审计/保留                |
| `SEC-KB-001`          | OCR 零出网                                         |
| `SEC-KB-002`          | ACL 403/404、平台管理员不读正文                    |
| `SEC-KB-003`          | 四处一致与旧引用遮蔽                               |
| `SEC-KB-004`          | Excel/OCR 文本注入                                 |
| `SEC-KB-005`          | 解析/OCR 资源耗尽                                  |
| `SEC-CONN-001`        | 连接器凭据不泄露                                   |
| `SEC-CONN-002`        | 连接器内容注入                                     |
| `SEC-CONN-003`        | 连接器 SSRF/端点允许列表                           |
| `SEC-CONN-004`        | 连接器越权（非管理员/跨租户）                      |
| `IT-WORKER-LANE-001`  | heavy 通道不阻塞 chat/report                       |
| `IT-WORKER-LEASE-001` | 租约续期与丢失取消                                 |
| `PERF-M05-001`        | 首字/API p95、OCR/同步积压、后台不阻塞             |

---

## 12. 交付计划（单模块或拆分，待用户决策）

### 12.1 任务边界（写范围互不重叠）

| 任务   | owner 建议             | 写范围（glob）                                                                                                                                                                                                                                                                                                                                                                                                                         | 内容                                                                                                                                 | 估时 |
| ------ | ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ---- |
| M05-B1 | `builder-kb-core`      | `platform/platform-domain/**`、`apps/api/src/main/resources/db/migration/V12*`、`apps/worker/src/main/java/**/run/**`、`apps/worker/src/main/java/**/parse/domain/**`、`apps/worker/src/main/resources/application.yml`、`apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java`、`apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java`、`apps/api/src/main/java/com/riccon/api/config/**` | 枚举/审计常量与白名单/权限、V12、解析领域模型（bbox/置信度/summary）、全部 M05 配置键、outbox 双通道 + 租约续期、API scheduling 开关 | 11h  |
| M05-B2 | `builder-kb-contract`  | `contracts/**`、`apps/api/src/test/java/**/contract/**`                                                                                                                                                                                                                                                                                                                                                                                | `1.4.0-m05` 全量契约 + 契约测试（唯一契约 writer）                                                                                   | 6h   |
| M05-B3 | `builder-kb-acl`       | `apps/api/src/main/java/com/riccon/api/knowledge/{controller,service,persistence,repository,dto,domain}/**`、`apps/worker/src/main/java/**/agent/persistence/AgentContextJdbc.java`                                                                                                                                                                                                                                                    | ACL 模型/API/谓词/审计/知识库下载/保留到期标记                                                                                       | 16h  |
| M05-B4 | `builder-kb-excel`     | `apps/worker/src/main/java/**/parse/service/XlsxDocumentParser.java`                                                                                                                                                                                                                                                                                                                                                                   | 流式 XLSX、行带定位、警告、上限                                                                                                      | 8h   |
| M05-B5 | `builder-kb-ocr`       | `apps/worker/src/main/java/**/ocr/**`、`apps/worker/src/main/java/**/parse/service/{ImageDocumentParser,JpegDocumentParser,PdfDocumentParser,FileContentExtractor,TextChunker}.java`                                                                                                                                                                                                                                                   | OCR SPI/Tesseract/扫描 PDF/资源控制、按字节复用解析、换行优先切分/bbox 传递                                                          | 12h  |
| M05-B6 | `builder-kb-connector` | `apps/api/src/main/java/com/riccon/api/knowledge/connector/**`、`apps/worker/src/main/java/**/connector/**`                                                                                                                                                                                                                                                                                                                            | 连接器 API/同步/凭据/删除传播                                                                                                        | 16h  |
| M05-B7 | `builder-kb-index`     | `apps/worker/src/main/java/**/index/**`、`apps/worker/src/main/java/**/parse/service/S3FileContentReader.java`、`scripts/ops/reindex-knowledge.mjs`                                                                                                                                                                                                                                                                                    | 索引 v2、ACL_ONLY/DELETE、连接器对象分支、别名切换                                                                                   | 6h   |
| M05-B8 | `builder-kb-web`       | `apps/web/**`                                                                                                                                                                                                                                                                                                                                                                                                                          | ACL 编辑、引用打开/下载、OCR/解析状态、连接器管理、M04 429 文案与相对重置时间、a11y                                                  | 12h  |
| M05-B9 | `builder-kb-citation`  | `apps/api/src/main/java/com/riccon/api/chat/{domain,dto,persistence,repository,service}/**`、`apps/api/src/main/java/com/riccon/api/research/{dto,persistence,service}/**`、`apps/worker/src/main/java/com/riccon/worker/research/{domain,persistence,service}/**`                                                                                                                                                                     | 引用 `source_version` 写入、`sourceStatus` 计算、不可访问时遮蔽                                                                      | 6h   |
| M05-V1 | `verifier`             | `apps/**/test/**`、`e2e/**`                                                                                                                                                                                                                                                                                                                                                                                                            | AC 功能/E2E/a11y 独立验证                                                                                                            | 8h   |
| M05-V2 | `verifier`             | `apps/**/test/**`、`e2e/**`、`scripts/ops/**`                                                                                                                                                                                                                                                                                                                                                                                          | 越权/安全/性能/迁移/回滚独立验证                                                                                                     | 10h  |

- 依赖：B1 → B2 → {B3,B4,B5} → B6（依赖 B3 的 ACL/来源枚举）→ B7（依赖 B4/B5 的解析器）→ {B8,B9} → V1/V2；B9 依赖 B3 的访问谓词；B2 合并前其他任务不得改 `contracts/`。
- 高风险任务（T4）：B1（迁移/outbox 框架）、B3（ACL/下载）、B6（连接器 SSRF/凭据）、B7（索引重灌）、B9（引用遮蔽）在 done 前必须有独立 Verifier 任务级评审。
- 不新增功能代码的文档任务（Product 文案、RUNBOOK）由 Lead 排期。
- **范围差异（需 Lead/用户确认）**：Product §11 把 M04 的 R-M04-01/02（`RICCON-QUOTA-429` 文案与相对重置时间）并入 M05（AC-17/18）；合同 `module_roadmap` 的 M05 只写知识库纵深。若确认并入，B8 承担前端改动，后端无需新设计（429 响应已有字段）；若不并入，按 `kind: fix` 另立维护模块。

### 12.2 预算与拆分建议

- 按 §12.1 逐项估算 ≈ 110h（含验证），远超合同 `budget.wall_clock` 的 8h/模块。Product §11.7 建议 M05 58h + M05b 40h（合计 98h）；差异主要在引用可用性（B9）、知识库下载、保留到期任务与连接器测试端点的独立任务。最终以 U3 决策为准。
- 两种拆分都可行，AC 内容不变：
  - **Product 方案（推荐优先讨论）**：M05 = Excel + OCR + ACL + M04 文案；M05b = 连接器。理由：OCR 是路线图 M05 的用户可见核心，连接器是最高外部依赖风险。
  - **Architect 风险方案**：M05a = Excel + ACL + 索引/契约；M05b = OCR + 连接器。理由：先把 T4 安全面（ACL）与契约稳定，再叠加 OCR/连接器。
- 替代：单模块 ≈110h 并提高上限（需用户批准）。不推荐在预算内静默砍 AC。
- 写范围不重叠：B3 与 B6 以 `knowledge/` 下不同子包分界；B4 只改 `XlsxDocumentParser`，B5 只改 OCR/图片/PDF/`FileContentExtractor`/`TextChunker`；B1 独占 `application.yml`、解析领域模型与审计白名单，避免并行写冲突。

---

## 13. 风险与开放问题

### 13.1 需用户决策（不可逆/高成本）

| ID  | 问题                 | 选项                                                                | 推荐                                                 | 截止           |
| --- | -------------------- | ------------------------------------------------------------------- | ---------------------------------------------------- | -------------- |
| U1  | OCR 引擎             | Tesseract / PP-OCR ONNX                                             | Tesseract 默认 + SPI；精度不达标再切 PP-OCR          | B5 开始前      |
| U2  | 连接器类型           | S3 / Confluence                                                     | S3 兼容目录                                          | B6 开始前      |
| U3  | 模块拆分与预算       | 单模块 ≈110h / Product 方案 M05+M05b / Architect 风险方案 M05a+M05b | 先讨论 Product 方案（OCR 留在 M05、连接器单独 M05b） | 计划评审时     |
| U4  | ACL 上线后回滚约束   | 禁止应用回滚（前向修复）/ 允许回滚但先放宽为 TENANT                 | 禁止回滚，前向修复                                   | ACL 开启前     |
| U5  | 新文档默认可见范围   | `TENANT`（保持现状）/ `USERS`（最小权限）                           | `TENANT` + 上传时显式选择与提示                      | 用户范围批准前 |
| U6  | 权重随镜像分发       | 允许 / 不允许                                                       | 允许（Apache-2.0，保留 NOTICE/SBOM）                 | B5 开始前      |
| U7  | 连接器原文是否可下载 | 可下载（受 ACL）/ 仅知识库引用                                      | 可下载（同文档 ACL）                                 | B6 开始前      |

### 13.2 团队可决定（本 ADR 已给默认值）

- 流式 XLSX、行带定位、公式只读缓存、隐藏表默认跳过。
- 不新增 `locator_type` 枚举；bbox/置信度用可空列表达。
- MySQL 为 ACL 权威、OpenSearch 字段只做诊断；不切换检索后端。
- outbox 双通道 + 租约续期；heavy 并发 2。
- 索引 v2 + 别名切换；v1 保留 7 天。
- 审计动作命名与 details 白名单；错误码分工（400/403/404/409/503 + 异步 `KB_*`）。

### 13.3 依赖 Product / Lead / Ops

| ID  | 依赖                                                                           | 谁                                                                      | 影响                                                 |
| --- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------------- | ---------------------------------------------------- |
| P1  | ACL 可见范围、谁可改 ACL、界面文案、默认值                                     | Product（草案已给：TENANT/DEPT/USERS、创建者+租户管理员、默认租户全员） | B3/B8 契约与 AC；待评审确认                          |
| P2  | 隐藏表/截断警告是否对用户可见                                                  | Product（草案已给：隐藏表跳过并显示警告）                               | B4/B8，待评审确认                                    |
| P3  | OCR 区域高亮是否必需                                                           | Product（草案已给：页内区域高亮/框选）                                  | bbox 必须展示，待评审确认                            |
| P4  | 源文件空间与文档可见性不一致时警告还是阻断                                     | Product（草案倾向：知识库下载按 ACL，不阻断）                           | §5.3、B3，待评审确认                                 |
| P5  | 产物引用在 ACL 撤销/源删除后是否仍展示                                         | Product（草案已给：不展示正文/片段/定位）                               | §5.4、§10、TM-68，待评审确认                         |
| P6  | 引用区域高亮口径：文本块高亮 vs 页图叠加框选；是否需要页图端点                 | Product + 前端                                                          | §4.3、§7.4；影响 B8 成本与 API 依赖                  |
| P7  | Product AC-06 写“平台管理员读租户正文 → 无入口或 403”；ADR-0009 统一跨租户 404 | Product + Lead                                                          | 建议改为“无入口或统一 404”，避免破坏跨租户不可区分性 |
| L1  | ADR-0009 §6 增加 `connector` 对象 key 类别                                     | Lead + Ops                                                              | B6 对象存储路径                                      |
| L2  | `THREAT-MODEL.md` 合入 TM-55..TM-69                                            | Lead（威胁模型 owner）                                                  | M05 安全 gate                                        |
| L3  | M05 AC 定稿后复核本 ADR §11 与任务边界                                         | Lead + Product                                                          | 计划评审                                             |
| L4  | PRODUCT §11 把 M04 R-M04-01/02（429 文案/相对时间）并入 M05，合同路线图未列    | Lead + 用户                                                             | 模块范围；确认则 B8 承担，否则 `kind: fix`           |
| O1  | OCR 镜像/权重、`scripts/ops/ocr-check`、RUNBOOK                                | Ops                                                                     | B5/B7 类任务                                         |

---

## 14. 设计自检

### 14.1 AC → 设计/契约/威胁/测试

- §11 的 22 条 Product AC 均映射到 ADR 章节与测试 ID；AC-17/18 属 M04 UI 收口，不在本 ADR 的知识库设计内（只标注归属与范围差异）；其余无“只写设计、无验证”的条目。
- 契约（§7.4）与威胁（§8）覆盖同一组入口：列表/详情/检索/引用/ACL/连接器/索引。
- 跨模块归属：ACL 归 M05；空间 ACL 仍归 M03；模型配额/成本不涉及 M05；M13 F-001 不在本 ADR 承接。

### 14.2 迁移可执行性

- V12 顺序：加列（INSTANT）→ 加索引（INPLACE）→ 加复合外键（需 `department` 存在）→ DROP/ADD `source_type` CHECK（小表）→ 新表 → 引用表加列 → 权限回填。§5.1 的 DDL 为了可读性合并在一个 `ALTER`；Builder 必须按上述顺序拆成多条语句，并在 MySQL 8.4 实测 `INSTANT`/`INPLACE`、记录锁等待（不能把合并语句当成已验证）。
- 回滚 SQL 先删子表再删列/约束，且明确“仅在无 CONNECTOR/ACL 数据时可执行”；生产回滚走备份/演练。
- worker schema 11 → 12；只有 API 执行迁移（ADR-0010）。

### 14.3 Gate 项拆分

- 模块 gate 只使用合同 `definition_of_done.module` 的 8 个 key；security/accessibility/performance/migration 继续作为 Verifier 评审项与 release evidence（2026-11-20 T4 DoD 决策不变）。
- M05 不得声称“已交付 M07 的 MCP 凭据托管”或“已交付 M09 的运维”；连接器凭据复用 ADR-0006，不扩展 M07 范围。

### 14.4 命名一致性

- 文档、契约、代码统一使用：`visibility`（TENANT/DEPT/USERS）、`knowledge_document_acl`、`kb_connector`、`kb_connector_item`、`KNOWLEDGE_CONNECTOR`、`knowledge.acl.changed`、`RICCON-KB-*`、`KB_*`（异步 error_code）。
- 不引入第二套命名（如 `scope`/`shareLevel`/`acl_entry`）。

### 14.5 高风险任务

- B1/B3/B6/B7/B9 均在 §12.1 标注写范围与内容，验收以 §9 指标、§11 对应测试 ID 与任务级评审为准；B2 是唯一契约 writer；B8 不写后端。

---

## Consequences

- **正面**：Excel 引用可定位到工作表/区域；扫描件与图片进入可检索闭环且完全本地；文档级 ACL 与 M03 空间 ACL 边界清晰、可测；连接器复用已有 S3 能力；索引/开关/迁移均可回滚（除 ACL 数据本身）。
- **负面**：OCR 增加镜像体积与 CPU 成本；Tesseract 中文精度有限（需精度门槛与替代路径）；ACL 上线后不能随意回滚；连接器引入新的出网与凭据托管面；outbox 双通道与租约续期是框架级改动，需要独立测试。
- **约束**：所有知识可见性判定必须走同一谓词；所有连接器端点必须来自允许列表；所有新依赖/权重必须许可合规并进 NOTICE/SBOM；所有 ACL/连接器变更必须审计且 fail-closed。

## Alternatives

- **只做 ACL、不做 OCR/连接器**：不满足合同 M05 范围；拒绝。
- **OCR 用公有云**：违反 T4 数据边界；拒绝。
- **OCR 用 Python PaddleOCR 服务**：新增运行时与部署面，合同技术栈无 Python；拒绝（ONNX Java 作为替代保留）。
- **ACL 继承源文件空间（不建独立 ACL）**：无法表达“知识库共享但源文件私有”，且把空间权限放大到知识检索；拒绝。
- **ACL 只做部门级、不做用户级**：Product 建议包含“指定用户”，且部门级无法处理跨部门小组；拒绝。
- **连接器做网页抓取**：SSRF/质量/礼貌成本高，且无稳定增量；拒绝。
- **ACL 放进 OpenSearch 过滤并切换检索后端**：改变召回与授权语义，M05 风险过高；拒绝，保留两阶段复核作为未来路径。

## Evidence

- 现状代码：`XlsxDocumentParser`、`PdfDocumentParser`、`ImageDocumentParser`、`FileContentExtractor`、`AgentContextJdbc.search`、`OpenSearchIndexClient.indexMapping`、`KnowledgeService`、`OutboxPoller`、`OutboxClaimer`、`AuditDetailsReader`。
- 迁移：V3（`knowledge_document`/`knowledge_chunk`/`file_chunk`）、V6（`audit_event` 无 `target_type` CHECK）、V9（权限回填模式）、V11（`DROP CHECK` 先例）。
- 契约：`contracts/openapi.yaml` `1.3.0-m04` 的知识库/文件/引用段；`KnowledgeDocument`、`Citation`、`LocatorType`。
- 许可核对（2026-11-22）：Tesseract/Tess4J Apache-2.0、JNA Apache-2.0 或 LGPL、PaddleOCR/RapidOCR 代码与模型 Apache-2.0、ONNX Runtime MIT、OCRmyPDF MPL-2.0（不在允许列表）。
- 评审依据：`docs/ARCHITECTURE.md`、ADR-0006/0007/0009/0010/0012/0015/0018/0021/0022、`docs/CODING-STANDARDS.md` §10.5/§11/§12。

## 修订记录

| 日期       | 变更                                                                                | 依据                          |
| ---------- | ----------------------------------------------------------------------------------- | ----------------------------- |
| 2026-11-22 | 初稿（proposed）：Excel/OCR/ACL/连接器设计、V12、契约增量、威胁模型、性能与任务边界 | M05 规划任务；Product AC 待定 |
