---
review_id: REV-TASK-0168-002
module_id: M05
task_id: TASK-0168
previous_review: REV-TASK-0168-001
reviewer:
  agent: riccon/verifier
  session: ses_df98e39f5ffeT9iar4BVndBf3U
  model: "opencode-go/deepseek-v4.1-flash#max"
  independent: true
verdict: pass
reviewed_commits:
  - 49122a6c7c03cf936135f70ad9a16c4add82f9c6
  - 8b64cec89197f7bcfade120207a3730c57cbab28
head_commit: 8b64cec89197f7bcfade120207a3730c57cbab28
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - docs
dod_note: >-
  B5 任务级 acceptance 通过：F-001/F-002/F-003/F-005 已修，EV-0842 worker 全量 524/0/0/0 且无
  stale，零出网 OcrCli 通过，独立探针对旧实现失败、对新实现通过。bbox/ocr_confidence 落库仍由
  TASK-0169（in_progress，验收/scope 已包含）承接，模块 AC-03 保留 residual，不影响 B5 任务 done。
  coverage/E2E/a11y/security/perf/迁移仍属 M05-V1/V2 与 gate。
evidence:
  - EV-0825
  - EV-0832
  - EV-0841
  - EV-0842
reviewed_evidence:
  - EV-0825
  - EV-0832
  - EV-0841
  - EV-0842
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-kb-ocr + riccon/architect"
    due: "fixed in 49122a6/8b64cec"
    title: "OCR 资源上限按 200MP 检查但渲染/解码峰值可达数百 MB，5000×5000pt PDF 页在 512MB 堆下 OOM——已修"
    summary: >-
      49122a6 新增 `max-decode-bytes=64MiB` 与 `effectiveDecodePixels()`（min(maxPixels, 64MiB/4,
      maxLongEdge²) = 16,000,000）；`PdfDocumentParser.planRender` 在渲染前把页面比例压到
      `budgetPixels`/`maxLongEdge`，渲染后再 `fits` 双检，失败记 `KB_PARSE_RESOURCE_LIMIT`；
      `OcrImagePreprocessor` 的解码子采样同时受 `maxLongEdge` 与 64MiB 像素预算约束，输入长边
      超过 `maxLongEdge×8` 在解码前拒绝；`TextChunker` 极端 overlap 也改为 `next=end`。
      `8b64cec` 仅统一配置键文案。旧实现对 5000×5000pt 页在 `-Xmx512m` 下 OOM；新实现同一断言
      渲染为 3998×3998（15,984,004 像素）并成功解析。F-001 关闭。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/ocr/config/OcrProperties.java:39-43、105-113`；
      `apps/worker/src/main/java/com/riccon/worker/parse/service/PdfDocumentParser.java:138-169、300-366`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrImagePreprocessor.java:159-183、190-214、272-296`；
      `apps/worker/src/main/resources/application.yml:156`；`docs/adr/ADR-0023-m05-knowledge-depth.md:191-192`；
      `apps/worker/src/test/java/com/riccon/worker/ocr/service/OcrImagePreprocessorTest.java:129-167`；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/PdfDocumentParserTest.java:298-330、489-500`；
      旧：`.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-pdf-oom.log`（OOM）；
      新：`.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-pdf-oom-fixed.log`
      （`rendered=3998x3998 pixels=15984004`、`NO_OOM pages=1 segments=1`，`-Xmx512m`）；
      EV-0841/EV-0842。
  - id: F-002
    severity: P2
    status: fixed
    owner: "riccon/builder-kb-ocr"
    due: "fixed in 8b64cec"
    title: "RICCON_OCR_LANG / riccon.worker.ocr.language 绑定一致——已修"
    summary: >-
      `application.yml` 由 `lang` 改为 `language: ${RICCON_OCR_LANG:chi_sim+eng}`，与
      `OcrProperties.language` 记录组件一致；`8b64cec` 同步错误消息中的键名。
      独立探针用 `YamlPropertySourceLoader` 加载真实 `application.yml`，在环境变量
      `RICCON_OCR_LANG=eng` 下绑定得到 `language=eng`、`maxDecodeBytes=67108864`、
      `effectiveDecodePixels=16000000`；`OcrInfoContributorTest` 也覆盖自定义语言展示。F-002 关闭；
      测试对旧实现的覆盖缺口见 F-007（P3）。
    evidence: >-
      `apps/worker/src/main/resources/application.yml:150`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/config/OcrProperties.java:34`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrInfoContributor.java:55-56`；
      `apps/worker/src/test/java/com/riccon/worker/ocr/OcrPropertiesBindingTest.java:18-35`；
      `apps/worker/src/test/java/com/riccon/worker/ocr/service/OcrInfoContributorTest.java:61-73`；
      `.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-lang-yaml-fixed.log`（`yaml language=eng`）；
      旧探针 `.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-lang-binding.log`。
  - id: F-003
    severity: P2
    status: fixed
    owner: "riccon/builder-kb-ocr"
    due: "fixed in 49122a6"
    title: "全页无文本的扫描 PDF 返回 KB_NO_TEXT（retryable=false）——已修"
    summary: >-
      `PdfDocumentParser.dominantFailure` 在 `errors` 全为 `NO_TEXT` 时返回 `KB_NO_TEXT`，不再
      落入 `OCR_FAILED`；`OcrService.isRetryable(NO_TEXT)` 为 false。独立探针对空白扫描 PDF 得到
      `errorCode=KB_NO_TEXT retryable=false`；新增 `parse_allPagesNoText_returnsNoText` 对旧实现
      （返回 `KB_OCR_FAILED`）会失败。F-003 关闭。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/service/PdfDocumentParser.java:261-270`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrService.java:231-232、265-270`；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/PdfDocumentParserTest.java:219-240`；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/ImageDocumentParserTest.java:120-141`；
      `.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-nottext-fixed.log`；
      旧：`.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-nottext-code.log`（`KB_OCR_FAILED`）。
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-index（TASK-0169）"
    due: "M05-B7 / M05 gate 前"
    accepted_reason: >-
      B5 write_scope 不含 `index/**`/`parse/repository/**`；B5 已把 bbox/ocrConfidence 透传到
      `ParsedChunk`。Lead 已把 TASK-0169（B7）扩到持久化 parse_engine/parse_warnings/parse_summary
      与 file_chunk/knowledge_chunk 的 bbox/ocr_confidence，并明确引用 REV-TASK-0168-001 F-004；
      B7 当前 in_progress。该 P2 由 B7 承接，M05-AC-03 在 B7 完成前保持 residual，不影响 B5 任务 done。
    title: "bbox/ocr_confidence 落库由 B7 承接，B5 只负责透传"
    summary: >-
      `KnowledgeRepository`/`KnowledgeChunkRecord`/`FileRepository` 仍使用 M02 列集合，
      `KnowledgeIndexService.toParsedChunk` 重建时也丢弃 bbox/confidence；V12 已加列，
      ADR-0023 IT-KB-104 要求落库。TASK-0169 的 acceptance/scope 已更新为包含该职责，B7 实现后
      M05-AC-03 才能完整 pass。
    evidence: >-
      `.riccon/tasks/TASK-0169.json`（in_progress；acceptance 含 bbox/ocr_confidence，scope 含 FileParseService）；
      `apps/worker/src/main/java/com/riccon/worker/index/repository/KnowledgeRepository.java:185-204`；
      `apps/worker/src/main/java/com/riccon/worker/index/domain/KnowledgeChunkRecord.java:21-29`；
      `apps/worker/src/main/java/com/riccon/worker/parse/repository/FileRepository.java:221-227`；
      `apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:197-205`。
  - id: F-005
    severity: P3
    status: fixed
    owner: "riccon/builder-kb-ocr"
    due: "fixed in 49122a6"
    title: "TextChunker 极端 overlap 不再逐字符爆炸——已修"
    summary: >-
      `chunkSegment` 的 `next` 改为 `end - chunkOverlap`，仅当 `next <= start` 时取 `end`，不再
      每次前进 1 字符。同一夹具（chunkSize=100、overlap=80、182 字）旧实现 46 块，新实现严格
      3 块 `A×60/B×60/C×60`；新增 `chunk_extremeOverlap_advancesWithoutDuplication` 对旧实现会失败。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/service/TextChunker.java:93-99`；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/TextChunkerTest.java:103-118`；
      `.riccon/evidence/TASK-0168/probe-2026-11-23-chunker-fixed.log`（`chunkCount=3`）；
      旧：`.riccon/evidence/TASK-0168/probe-2026-11-23-chunker-overlap.log`（46）。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      EV-0841 的 builder worktree（riccon-b5-fix-wt2）与 EV-0842 的 verifier worktree
      （riccon-b5-r2-wt）均在 commit 8b64cec、dirty=false 下录制；主树被 B7/B10 WIP 占用，无法在
      8b64cec 上录干净主树证据。EV-0839/0840 曾撞号后重编号，EV-0841 为权威、EV-0842 为独立复核；
      这是并行取证流程例外，需 Lead 在 M05 gate 前统一规范。
    title: "EV-0841/EV-0842 在隔离 worktree 录制；EV-0839/0840 撞号已重编号"
    summary: >-
      主树 HEAD 14e2ca4、存在 B7/B10 WIP（index/domain/KnowledgeChunkRecord 与 worker injection
      测试等未跟踪/修改文件），无法在主树录 dirty=false 的 8b64cec 证据。EV-0841（builder）与
      EV-0842（verifier）均绑定 8b64cec，EV JSON/日志已回灌主树。EV-0840 对应 49122a6，已被
      EV-0841 取代；本评审不使用 EV-0840 作验收证据。
    evidence: >-
      `.riccon/evidence/TASK-0168/EV-0841.json`；`.riccon/evidence/TASK-0168/EV-0842.json`；
      `.riccon/evidence/TASK-0168/EV-0840.json`（superseded）；
      `git worktree list`；主树 `git status --short`。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-ocr"
    due: "M05 gate 前"
    accepted_reason: >-
      行为已由独立探针和全量测试验证，缺口是测试对旧实现的锁定强度，不影响本次 pass：
      (1) `OcrPropertiesBindingTest` 直接绑定 `riccon.worker.ocr.language`，不加载 `application.yml`；
      旧实现记录组件同为 `language`，该测试在旧代码上也会通过，不能防止 YAML 再次退回 `lang`。
      建议用 `YamlPropertySourceLoader`/Spring context + `RICCON_OCR_LANG` 做真实绑定测试（本次已用
      独立探针验证）。
      (2) `parse_nearLimitPage_downscalesBeforeRender` 在旧实现上只看到预处理后的 4000×4000，
      在大堆 CI 上会通过；真正的 OOM 回归由 `-Xmx512m` 探针锁定。建议增加受限堆测试或把
      `planRender` 提为可单测方法。
      (3) `OcrInfoContributorTest` 的自定义语言直接构造 properties，旧实现也通过；binding 覆盖
      以 (1) 为准。
    title: "测试对 F-002/ near-limit OOM 的旧实现锁定不足（P3 测试质量）"
    summary: >-
      F-001 的输入长边炸弹测试和 F-003/F-005 的新测试能对旧实现失败；F-002 的绑定测试与
      near-limit PDF 降采样测试在旧实现上不会失败。建议补真实 YAML/placeholder 绑定测试和受限堆
      OOM 回归，避免未来回归只靠手工探针。
    evidence: >-
      `apps/worker/src/test/java/com/riccon/worker/ocr/OcrPropertiesBindingTest.java:18-35`；
      `apps/worker/src/test/java/com/riccon/worker/ocr/service/OcrInfoContributorTest.java:61-73`；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/PdfDocumentParserTest.java:298-330`；
      `apps/worker/src/test/java/com/riccon/worker/ocr/service/OcrImagePreprocessorTest.java:129-167`。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-ocr + riccon/ops（O1b / RUNBOOK）"
    due: "M05 gate 前 / O1b"
    accepted_reason: >-
      沿用 REV-TASK-0168-001 F-008；本次修复未改线程/超时模型。现有测试无 real Tesseract 挂起样本，
      最坏情况受文档超时、outbox heavy 看门狗和进程重启兜底；需 O1b/RUNBOOK 给出 riccon_ocr_* 指标、
      告警与处置，或后续把 OCR 放到可 kill 的进程/容器边界。
    title: "单页超时只 cancel Future，Tesseract native 调用不可中断（沿用 P3）"
    summary: >-
      `OcrService.recognizePrepared` 超时后 `future.cancel(true)` 返回 KB_OCR_TIMEOUT，但 Tesseract
      低层 native 调用不响应 Java 中断；固定 2 线程池被两个挂起页占用时，后续 OCR 任务会排队并逐个
      超时，文档超时只停止提交、不能恢复池。图片预处理与 PDF 渲染也不在单页 timeout 内。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrService.java:188-224、110-120、136-146`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/TesseractOcrEngine.java:151-191`；
      `docs/adr/ADR-0023-m05-knowledge-depth.md:193-198`。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-ocr + riccon/architect + riccon/verifier（M05-V1）"
    due: "M05-V1 / 精度复核"
    accepted_reason: >-
      这是 OCR 质量提示问题，不影响内存/正确性/安全边界；M05-V1 的 CER/固定夹具复核可判断是否需在
      `OcrOptions` 中传实际有效 DPI 或在降采样后重设。当前没有 5000pt 级真实业务夹具，按 P3 跟踪。
    title: "PDF 页降采样后仍向 Tesseract 传 pdfDpi=200（有效 DPI 可能约 57）"
    summary: >-
      `PdfDocumentParser.planRender` 对 5000×5000pt 页把渲染比例降到 ~0.8（有效约 57 DPI），但
      `OcrService.recognizeRendered` 仍以 `properties.pdfDpi()`（200）构造 `OcrOptions`；Tesseract
      的版面/字符尺寸估计可能偏差。旧实现也存在该提示不一致，但修复后大页降采样更常见，需在 V1
      精度夹具中确认是否需要传有效 DPI。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/service/PdfDocumentParser.java:158-169、300-366`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrService.java:176-190、214-224`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/domain/OcrOptions.java:6-15`。
created_at: "2026-11-23T12:38:00+08:00"
---

# REV-TASK-0168-002：TASK-0168（M05-B5 OCR/扫描 PDF/OcrCli/资源控制）复评

## 一页摘要

**verdict：pass（任务级）**。REV-001 的 F-001（P1，资源上限未阻止 OOM）、F-002（`RICCON_OCR_LANG`
绑定无效）、F-003（全 NO_TEXT 误报 OCR_FAILED）、F-005（chunker 极端 overlap 重复块）均已修复：
独立 worker 全量 **524 tests / 0 failures / 0 errors / 0 skipped（无 stale）**，零出网 OcrCli 通过；
旧实现在 `-Xmx512m` 下对 5000×5000pt PDF 页 OOM，新实现渲染为 3998×3998 且不 OOM；真实
`application.yml` + `RICCON_OCR_LANG=eng` 绑定得到 `language=eng`；空白扫描 PDF 返回
`KB_NO_TEXT retryable=false`；极端 overlap 夹具返回 3 块。剩余 P2 只有 bbox/ocr_confidence
落库，已由 TASK-0169（B7，in_progress，验收/scope 已扩）承接；P3 为测试锁定强度、native 超时、
降采样 DPI 提示和 worktree/ID 例外。B5 任务可以 done，但 M05-AC-03 在 B7 完成前仍是 residual。

## 审查范围与证据

| 项         | 值                                                                                                                                                                |
| ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| baseline   | `7ad975d`                                                                                                                                                         |
| 修复提交   | `49122a6c7c03cf936135f70ad9a16c4add82f9c6`（资源/错误码/chunker/测试） + `8b64cec89197f7bcfade120207a3730c57cbab28`（仅 OCR 配置键文案）                          |
| task end   | `8b64cec89197f7bcfade120207a3730c57cbab28`                                                                                                                        |
| 任务级证据 | EV-0841（builder 权威：worker 全量 + package + 镜像内 OcrCli，commit 8b64cec，dirty=false）；EV-0842（verifier 独立复核，commit 8b64cec，dirty=false，524/0/0/0） |
| 历史证据   | EV-0825（REV-001 初版全量）、EV-0832（REV-001 定向 97 tests，旧 commit 6c61754）                                                                                  |
| 独立探针   | `probe-2026-11-23-ocr-pdf-oom-fixed.*`、`probe-2026-11-23-ocr-lang-yaml-fixed.*`、`probe-2026-11-23-ocr-nottext-fixed.*`、`probe-2026-11-23-chunker-fixed.*`      |
| 未跑       | coverage/E2E/a11y/security/perf/迁移属 M05-V1/V2；B7 bbox 落库属 TASK-0169；O1b 零出网编排属 TASK-0175                                                            |

范围核对：B5 修复提交本身为 `49122a6` 的 12 个文件（`ocr/config`、`ocr/service`、`parse/service`、
`application.yml`、`ocr`/`parse` 测试）与 `8b64cec` 的 `OcrProperties.java`，全部在 TASK-0168
write_scope 内；`7ad975d..8b64cec` 的 broader diff 还包含 TASK-0166/0172/0190 的 `.riccon`
状态/证据、DEC-0037 与 TASK-0166 review-002 等并行任务产物，不是 B5 代码，也没有 B5 生产/测试文件
越界。新增依赖未变化（Tess4J/JNA/lept4j/jai-imageio-core/commons-io）；NOTICE 已由 TASK-0187
（done）收口，CODING-STANDARDS `ocr` tag 已由 TASK-0188（done）收口。

### EV 真实性核对

- **EV-0841**：`output_sha256=a27960c4...9618ee` 与日志一致（269,143 bytes）；commit 8b64cec、
  dirty=false、exit 0；命令为 worker 全量 `./mvnw -q -T1C -pl apps/worker -am test` + `package` +
  `docker run --rm --network none` OcrCli；日志尾部 `engine=tesseract / averageConfidence=0.922 /
blocks=1 / line=1 ... bbox=57,74,766,54 / text=RICCON OCR 12345`。**524 tests/0/0/0** 与本次
  EV-0842 的 surefire 汇总一致，且无 `OcrLineAssemblerTest` stale。
- **EV-0842**：`output_sha256=e63c8b3c...04cc4` 与日志一致（269,282 bytes）；commit 8b64cec、
  dirty=false、exit 0；surefire 93 suites / **524 tests / 0 failures / 0 errors / 0 skipped**；
  OcrCli 输出与 EV-0841 相同。
- **EV-0840**：对应 `49122a6`（配置键文案修正前），已被 EV-0841 取代；任务 `evidence` 数组虽保留
  EV-0840，本评审不以它作最终验收证据。EV-0839/0840 撞号重编号见 F-006。
- **EV-0832**：REV-001 的 verifier 定向 97 tests，commit 6c61754；保留为旧实现基线。
- **EV-0825**：REV-001 的初版 builder 全量；保留为历史。

### 独立定向验证

```bash
# EV-0842（隔离 worktree 8b64cec；加锁）
LOCK="${TMPDIR:-/tmp}/riccon-maven.lock"; while ! mkdir "$LOCK" 2>/dev/null; do sleep 5; done; \
  trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT; \
  ./mvnw -q -T1C -pl apps/worker -am test && \
  ./mvnw -q -T1C -DskipTests -pl apps/worker -am package && \
  docker run --rm --network none \
    -v "$PWD/apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar:/app/app.jar:ro" \
    -v "$PWD/deploy/ocr:/ocr:ro" --entrypoint java riccon-worker-ocr:o1a \
    -Dloader.main=com.riccon.worker.ocr.service.OcrCli -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher /ocr/sample.png --lang chi_sim+eng --dpi 200
# 93 suites, 524 tests, 0 failures, 0 errors, 0 skipped；OcrCli 0.922 + bbox 57,74,766,54
```

独立探针（手动，源/日志在 `.riccon/evidence/TASK-0168/`）：

```text
# F-001 旧实现 6c61754，-Xmx512m，5000×5000pt PDF
maxHeapMiB=512 / renderedPixels=192904321 / THROWN java.lang.OutOfMemoryError: Java heap space
# F-001 新实现 8b64cec，-Xmx512m，同一 PDF
maxHeapMiB=512 / requestedPixels=192904321 / effectiveDecodePixels=16000000
rendered=3998x3998 pixels=15984004 / NO_OOM pages=1 segments=1

# F-002 新实现真实 application.yml + RICCON_OCR_LANG=eng
yaml language=eng maxDecodeBytes=67108864 effectiveDecodePixels=16000000

# F-003 新实现空白扫描 PDF
errorCode=KB_NO_TEXT retryable=false

# F-005 新实现 chunkSize=100 overlap=80，182 字
chunkCount=3 textLength=182
contents=[A×60, B×60, C×60]
```

### Gate check（预期失败）

`riccon gate check --module M05 --json` 返回 `ok=false`：TASK-0168 仍 `review`、TASK-0169 `in_progress`、
B8/B9/B10/B11/V1/V2 未完成、无模块 review；`task-scope` 还被主树 B7/B10 WIP（index/domain、
worker injection 测试）污染。这些是模块尚未收口与 dirty 主树原因；本复评只对 B5 任务级给出 pass，
不替代模块 gate。

## F-001 / F-002 / F-003 / F-005 逐条结论

| 项        | 结论      | 代码/测试/独立证据                                                                                                                                                                                                                                                                                                                                                                      |
| --------- | --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **F-001** | **fixed** | `maxDecodeBytes=64MiB` + `effectiveDecodePixels=16M`；`planRender` 渲染前降采样、渲染后 `fits` 双检、`KB_PARSE_RESOURCE_LIMIT`；图片解码按 64MiB/4000px 子采样，输入长边 8× 硬上限。旧实现 `-Xmx512m` OOM；新实现 3998×3998 不 OOM。测试：`prepare_inputLongEdgeBomb`（旧失败）、`prepare_decodeByteBudget`、`parse_nearLimitPage`、`effectiveDecodePixels`。残余测试锁定强度见 F-007。 |
| **F-002** | **fixed** | YAML `language: ${RICCON_OCR_LANG:chi_sim+eng}` 与 `OcrProperties.language` 一致；真实 YAML + `RICCON_OCR_LANG=eng` 探针得到 `language=eng`；`OcrInfoContributor` 用绑定值。`OcrPropertiesBindingTest` 覆盖 record 键，但不加载 YAML（旧实现也会通过），见 F-007。                                                                                                                      |
| **F-003** | **fixed** | `dominantFailure` 对 `errors` 全 `NO_TEXT` 返回 `KB_NO_TEXT`；`isRetryable=false`；空白扫描 PDF 探针确认；`parse_allPagesNoText_returnsNoText` 对旧实现失败。                                                                                                                                                                                                                           |
| **F-005** | **fixed** | `next=end-overlap`，`next<=start` 时取 `end`；182 字/100/80 从旧 46 块降为严格 3 块；新增测试断言块数和内容；旧实现会失败。                                                                                                                                                                                                                                                             |

## 回归与范围

- **worker 全量**：EV-0841 与 EV-0842 均为 524 tests / 0/0/0，surefire 93 suites，无 stale；包含
  ArchUnit、既有 OCR/parse 测试、`FileParseServiceIntegrationTest`、`OcrInfoEndpointIntegrationTest`。
- **零出网 OcrCli**：EV-0841/EV-0842 均在 `riccon-worker-ocr:o1a` 内 `--network none` 运行，输出
  `engine=tesseract / averageConfidence=0.922 / bbox=57,74,766,54 / RICCON OCR 12345`；无权重下载/
  公网访问。
- **范围**：B5 修复提交 12+1 文件全在 write_scope；broad range 的 `.riccon`/DEC/其他任务产物不是 B5
  代码。**无新增 P0/P1**。
- **转派**：NOTICE TASK-0187（done）、CODING-STANDARDS `ocr` tag TASK-0188（done）；O1b 零出网
  TASK-0175（planned）仍按 ADR 复用 `OcrCli`。B7 bbox/parse_* 落库 TASK-0169（in_progress）已扩
  scope/验收，引用 REV-TASK-0168-001 F-004。

## 剩余风险与需 Lead 处理

| 项                        | 状态/动作                                                                                                                        |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| F-004 P2 bbox 落库        | 由 `riccon/builder-kb-index` 在 TASK-0169 完成；M05-AC-03 在 B7 前为 residual；B5 任务可 done。                                  |
| F-006 P3 worktree/ID 例外 | Lead 在 M05 gate 前统一并行取证规范；EV-0841 权威、EV-0842 独立复核、EV-0840 superseded。                                        |
| F-007 P3 测试锁定强度     | builder 在 M05 gate 前补真实 YAML/placeholder 绑定测试与受限堆 OOM 回归（或 `planRender` 单测）。                                |
| F-008 P3 native 超时      | builder/ops 在 O1b/RUNBOOK 给出 `riccon_ocr_*` 指标与处置；不阻塞 B5。                                                           |
| F-009 P3 降采样 DPI 提示  | M05-V1 精度夹具复核；必要时 `OcrService` 传有效 DPI。                                                                            |
| 任务状态                  | 建议 Lead 可将 TASK-0168 移 `done`（B5 任务级通过）；模块 gate 仍需 B7/B8/B9/B10/B11/V1/V2 与模块 review。本评审不修改任务状态。 |

## 未验证

- coverage、合同全量 E2E、a11y、security、perf、迁移回滚：属 M05-V1/V2 与模块 gate；本复评只做
  B5 任务级 worker 全量/定向与探针。
- B7 的 parse_engine/parse_warnings/parse_summary/bbox 落库与 API shape：TASK-0169 进行中。
- O1b `scripts/ops/ocr-check` 的独立零出网编排：TASK-0175 planned；B5 提供 `OcrCli` 入口。
- 真实无 native/无 chi_sim 镜像的启动失败负向测试、real Tesseract 挂起样本、5000pt 业务夹具的
  OCR 精度（CER）：O1b/V1 复核。
