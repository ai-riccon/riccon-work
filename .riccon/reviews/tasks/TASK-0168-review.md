---
review_id: REV-TASK-0168-001
module_id: M05
task_id: TASK-0168
reviewer:
  agent: riccon/verifier
  session: ses_df98e39f5ffeT9iar4BVndBf3U
  model: "opencode-go/deepseek-v4.1-flash#max"
  independent: true
verdict: fail
reviewed_commits:
  - 6c61754e9000d9401175674fca7b258b9b29a2af
  - be1155d3078a874570cb2c259f5922bc4d290775
head_commit: 6c61754e9000d9401175674fca7b258b9b29a2af
dod:
  acceptance: false
  build: true
  tests: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - docs
dod_note: >-
  任务级评审只判 B5 的 OCR 解析链与资源控制；coverage/E2E/a11y/security/perf 属 M05-V1/V2 与 gate。
  build 由 EV-0825 的 package 与 EV-0830 的 test 编译支撑；tests 由 EV-0830（97 用例）与 EV-0825
  （worker 全量）支撑。acceptance 因 F-001（P1，资源上限未阻止 OOM）判 false。
evidence:
  - EV-0825
  - EV-0830
reviewed_evidence:
  - EV-0825
  - EV-0830
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/builder-kb-ocr + riccon/architect"
    due: "M05-B5 修复 / M05 gate 前"
    title: "OCR 资源上限按 200MP 检查但实际渲染/解码峰值可达数百 MB，5000×5000pt PDF 页在 512MB 堆下 OOM"
    summary: >-
      `PdfDocumentParser` 在渲染前用 `maxDecodePixels`（默认 200,000,000）判断页面像素，而
      `PDFRenderer.renderImageWithDPI` 会一次性分配整页 `BufferedImage`。5000×5000pt 页面按 200 DPI
      渲染为 13889×13889 = 192,904,321 像素（小于 200MP），会通过检查，但 RGB 图像约 580MB；
      在 `-Xmx512m` 下已复现 `java.lang.OutOfMemoryError: Java heap space`。图片路径同类：
      `OcrImagePreprocessor.decode` 只按 `maxPixels`（50,000,000）做子采样，7071×7071 图片会先
      解码 50MP（约 150–200MB）再缩放至 4000px，超过 ADR-0023 §4.4“单页解码后 ≤ 64MB”，且
      子采样步长没有同时受 `maxLongEdge` 约束。M05-AC-05/SEC-KB-005 要求超大像素/畸形输入被
      资源上限拒绝且不 OOM/超时失控；现有测试只覆盖 100000×100000 头部（10^10 像素）和
      10000×10000pt PDF 页（>200MP），没有覆盖 150–193MP 的边界。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/service/PdfDocumentParser.java:138-148`
      （像素检查用 `maxDecodePixels`，随后 `renderImageWithDPI`）；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrImagePreprocessor.java:150-181`
      （解码前只按 `maxDecodePixels` 拒绝，子采样只按 `maxPixels`）、`:272-296`（解码后才按 maxLongEdge 缩放）；
      `apps/worker/src/main/java/com/riccon/worker/ocr/config/OcrProperties.java:39-41`（默认 50MP / 200MP / 4000）；
      `apps/worker/src/main/resources/application.yml:155-157`；
      `docs/adr/ADR-0023-m05-knowledge-depth.md:191-192`（50MP/4000px 与单页解码 ≤64MB）；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/PdfDocumentParserTest.java:258-276`
      （只测 10000×10000pt 页）；`apps/worker/src/test/java/com/riccon/worker/ocr/service/OcrImagePreprocessorTest.java:84-96`
      （只测 100000×100000 头部）。
      复现：`.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-pdf-oom.java`，在 worktree `6c61754`
      用 `-Xmx512m` 运行输出
      `maxHeapMiB=512 / renderedPixels=192904321 / maxDecodePixels=200000000 / THROWN java.lang.OutOfMemoryError: Java heap space`
      （日志 `probe-2026-11-23-ocr-pdf-oom.log`）。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/builder-kb-ocr"
    due: "M05-B5 修复 / M05 gate 前"
    title: "RICCON_OCR_LANG / riccon.worker.ocr.lang 不绑定 OcrProperties.language，语言配置实际无效"
    summary: >-
      `application.yml` 写的是 `lang: ${RICCON_OCR_LANG:chi_sim+eng}`，而 `OcrProperties` 记录组件
      是 `language`；Spring Boot relaxed binding 不会把 `lang` 映射到 `language`，所以
      `RICCON_OCR_LANG=eng`（或 YAML 中的 lang）被忽略，运行期始终使用默认 `chi_sim+eng`，
      `/actuator/info` 也显示默认值。ADR-0023 §4.4 把 `RICCON_OCR_LANG` 列为部署语言开关；在只安装
      `eng` 的镜像/宿主上，运维设置 `eng` 仍会得到“chi_sim 缺失”的启动 WARN 与
      `KB_OCR_LANG_UNAVAILABLE`，语言配置不可用。现有测试没有覆盖绑定，`OcrInfoEndpointIntegrationTest`
      只断言默认值，掩盖了该问题。
    evidence: >-
      `apps/worker/src/main/resources/application.yml:150`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/config/OcrProperties.java:34`；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrInfoContributor.java:55-56`；
      `docs/adr/ADR-0023-m05-knowledge-depth.md:181`（RICCON_OCR_LANG）。
      复现：`.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-lang-binding.log` 用 Spring Binder
      绑定 `riccon.worker.ocr.lang=eng`，输出 `bound language=chi_sim+eng expected=eng-from-lang-key`。
  - id: F-003
    severity: P2
    status: open
    owner: "riccon/builder-kb-ocr"
    due: "M05-B5 修复 / M05 gate 前"
    title: "全页无文本的扫描 PDF 返回 KB_OCR_FAILED（可重试）而非 ADR 要求的 KB_NO_TEXT"
    summary: >-
      OCR 引擎对空白页返回 `NO_TEXT`，`PdfDocumentParser` 把所有失败码收集到 `ocrErrors`；当所有页
      都无文本时，`dominantFailure` 只处理 language/resource/timeout，其余一律返回 `OCR_FAILED`，
      并把 `retryable` 设为 true。ADR-0023 §4.5 明确“全部页无文本 → FAILED/KB_NO_TEXT”；当前行为
      会让空白扫描件显示“OCR 识别失败，请稍后重试”而不是“未识别到可索引文本”，并可能触发无意义的
      outbox 重试。现有 `parse_allPagesFailed_fails` 只覆盖 OCR_TIMEOUT，没有覆盖全 NO_TEXT。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/service/PdfDocumentParser.java:219-254`
      （`dominantFailure` 的 fallback 为 `OCR_FAILED`）；
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrService.java:231-232`（空块 → NO_TEXT）；
      `docs/adr/ADR-0023-m05-knowledge-depth.md:206-207`（全部页无文本 → KB_NO_TEXT）；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/PdfDocumentParserTest.java:199-217`
      （只测 timeout）。
      复现：`.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-nottext-code.java` 对空白 PDF 运行输出
      `errorCode=KB_OCR_FAILED retryable=true`（日志 `probe-2026-11-23-ocr-nottext-code.log`）。
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/builder-kb-index（TASK-0169）"
    due: "M05-B7 / M05 gate 前"
    accepted_reason: >-
      B5 的 write_scope 只到 `ocr/**`、parse/service 与 pom/application.yml，不含
      `index/**`、`parse/repository/**`；B5 已把 bbox/ocrConfidence 透传到 `ParsedChunk`，落库属于 B7。
      但 TASK-0169 的 acceptance/scope 未明确 bbox 持久化，必须在 M05 gate 前由 Lead 扩 B7 验收或拆新任务，
      否则 M05-AC-03 的“引用详情显示 bbox”仍无法实现；因此按 P2 accepted 跟踪，不作为 B5 代码失败。
    title: "bbox/ocr_confidence 只到 ParsedChunk，knowledge_chunk/file_chunk 与索引未落库；B7 验收未列该职责"
    summary: >-
      `ParsedChunk`/`ParsedSegment` 已携带 `bbox` 与 `ocrConfidence`，`TextChunker` 也原样继承；
      但 `KnowledgeRepository.replaceChunks` 的 INSERT、`KnowledgeChunkRecord`、`FileRepository.replaceChunks`
      都没有 bbox/ocr_confidence 列，`KnowledgeIndexService.toParsedChunk` 重建时也丢弃。V12 已给两张
      分块表加列，ADR-0023 §4.7 IT-KB-104 明确要求“bbox 与置信度落库”；B5 没有、也不能在自身
      write_scope 内完成。B7 的 `index/**` scope 可以覆盖 knowledge_chunk，但需要 Lead 明确验收；
      `file_chunk` 路径可能还需要单独任务。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/domain/ParsedChunk.java:31-32`；
      `apps/worker/src/main/java/com/riccon/worker/index/repository/KnowledgeRepository.java:185-204`；
      `apps/worker/src/main/java/com/riccon/worker/index/domain/KnowledgeChunkRecord.java:21-29`；
      `apps/worker/src/main/java/com/riccon/worker/index/service/KnowledgeIndexService.java:197-205`；
      `apps/worker/src/main/java/com/riccon/worker/parse/repository/FileRepository.java:221-227`；
      `apps/api/src/main/resources/db/migration/V12__knowledge_depth.sql:142-154`；
      `docs/adr/ADR-0023-m05-knowledge-depth.md:168、221`；`.riccon/tasks/TASK-0169.json`（无 bbox 验收）。
  - id: F-005
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-ocr"
    due: "M05 gate 前"
    accepted_reason: >-
      生产默认 `chunk-size=800`/`chunk-overlap=80` 不会触发（换行断点至少落在窗口后半段，`end-overlap`
      仍大于 start）；只有合法但极端的 `overlap >= chunkSize/2` 配置会放大重复块。属于边界健壮性，
      不改变当前默认路径，按 P3 跟踪；建议加 `overlap < chunkSize/2` 校验或把 fallback 改为 `end`。
    title: "TextChunker 换行断点 + 极大 overlap 会把 182 字文本切成 46 块（重复块放大）"
    summary: >-
      `chunkSegment` 先把 `end` 回退到换行位置，再用 `next = Math.max(end - chunkOverlap, start + 1)`；
      当 `chunkOverlap >= chunkSize/2` 且换行点靠近窗口后半段时，`end - overlap < start`，fallback 每次只
      前进 1 个字符，产生大量重叠/重复块。用 chunkSize=100、overlap=80 对测试夹具
      `60A\n60B\n60C`（182 字）运行得到 46 块（首块 A×60，随后 A×59…A×50 等）。默认 800/80 与测试
      100/20 不触发；`TextChunkerTest.chunk_longTextWithNewlines_breaksAtNewline` 只断言块数 >1、首块和
      长度上限，未覆盖该边界。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/parse/service/TextChunker.java:69-95`；
      `apps/worker/src/test/java/com/riccon/worker/parse/service/TextChunkerTest.java:90-101`；
      `apps/worker/src/main/java/com/riccon/worker/config/ParseProperties.java:28-32`（只校验 overlap < chunkSize）。
      复现：`.riccon/evidence/TASK-0168/probe-2026-11-23-chunker-overlap.log` 输出
      `chunkCount=46 textLength=182`。
  - id: F-006
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      EV-0830 的 commit 是 6c61754、dirty=false，命令与产出的 97 个用例可复核；主树被 B3/B4 WIP 占用时
      用隔离 worktree 取证是已批准的临时方式，但仍是流程例外。Lead 需要在 M05 gate 前统一并行任务的
      取证规范，避免模块评审拿到 worktree/dirty 证据。
    title: "verifier EV-0830 在隔离 worktree 录制（主树被 B3/B4 WIP 占用）"
    summary: >-
      主树当前 HEAD 8573fcd、34 个 dirty 文件（B3/B4 WIP），无法在 6c61754 上录 dirty=false 证据；
      按任务指示在 `$TMPDIR/riccon-b5-verify-wt` 检出 6c61754，同步 `.riccon` 元数据后运行定向测试并录
      EV-0830。EV JSON 的 cwd 为 worktree、commit=6c61754、dirty=false；EV JSON/日志已回灌主树
      `.riccon/evidence/TASK-0168/`。这是流程例外，不是代码缺陷。
    evidence: >-
      `.riccon/evidence/TASK-0168/EV-0830.json`；`git worktree list`；主树 `git status --porcelain`（34 文件）。
  - id: F-007
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-ocr + riccon/lead"
    due: "M05 gate 前"
    accepted_reason: >-
      EV 本身 exit 0、0 failure/0 skip，测试执行是真实的；计数偏差来自 stale surefire 报告，不是假绿。
      记录为证据卫生问题，后续全量/模块评审以干净 worktree 或 clean 后的 target 重新汇总。
    title: "EV-0825 自报 511 个 worker 测试含 4 个已删除 OcrLineAssemblerTest 的陈旧报告，实际约 507"
    summary: >-
      EV-0825 的 log 因 `-q` 没有 surefire 汇总；主树 `apps/worker/target/surefire-reports` 合计
      92 个 suite/511 tests，其中 `TEST-com.riccon.worker.ocr.service.OcrLineAssemblerTest.xml`
      （4 tests）的源文件在 6c61754/be1155d 均不存在，报告 mtime 11:05 早于 EV 开始 11:19；因此
      实际执行约 507 个用例。builder 的“511 tests/0/0/0”计数不精确，但 exit 0 与 0 failure/0 skip
      结论不受影响。
    evidence: >-
      `.riccon/evidence/TASK-0168/EV-0825.json`（exit 0、dirty false、commit be1155d）；
      `apps/worker/target/surefire-reports/TEST-com.riccon.worker.ocr.service.OcrLineAssemblerTest.xml`；
      `find apps/worker/src -name '*LineAssembler*'`（无结果）；`git ls-tree -r 6c61754 | grep -i lineassembler`（无结果）。
  - id: F-008
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-ocr + riccon/ops（O1b / RUNBOOK）"
    due: "M05-B5 修复 / M05 gate 前"
    accepted_reason: >-
      现有测试未观察到 real Tesseract 挂起；最坏情况受文档超时、outbox heavy 看门狗和进程重启兜底，
      但当前没有针对 `riccon-ocr` 池耗尽的探针/指标。按 P3 记录，要求 O1b/RUNBOOK 给出观测与处置
      （或后续把 OCR 放到可 kill 的进程/容器边界）。
    title: "单页超时只 cancel Future，Tesseract 原生调用不可中断；两个挂起线程可耗尽 riccon-ocr 池"
    summary: >-
      `OcrService.recognizePrepared` 在 `future.get(timeout)` 超时后调用 `future.cancel(true)` 并返回
      KB_OCR_TIMEOUT；Tesseract 的低层 native 调用不会响应 Java 中断，线程可能继续占用。固定池大小
      `maxConcurrency=2` 时，两个挂起页会让后续 OCR 任务排队并逐个超时；文档级超时只能阻止继续提交，
      不能恢复池。图片预处理和 PDF 渲染也不在单页 timeout 内。ADR 说明 native 崩溃由进程重启恢复，
      但没有覆盖“挂起”场景。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrService.java:188-224`（future/超时/取消）、
      `:110-120`（固定池）；`apps/worker/src/main/java/com/riccon/worker/ocr/service/OcrService.java:136-146`
      （预处理在池外）；`apps/worker/src/main/java/com/riccon/worker/ocr/service/TesseractOcrEngine.java:151-191`；
      `docs/adr/ADR-0023-m05-knowledge-depth.md:193-198`（单页超时/进程重启）。
created_at: "2026-11-23T11:52:00+08:00"
---

# REV-TASK-0168：TASK-0168（M05-B5 OCR/扫描 PDF/OcrCli/资源控制）独立评审

## 一页摘要

**verdict：fail（任务级）**。OCR SPI、Tesseract 低层 ResultIterator、EXIF 90/180/270、混合页/四态、
`KB_OCR_DISABLED`、OcrCli `--network none` 主路径和 97 个定向用例均通过；但资源上限存在一个 P1：
PDF 页按 `maxDecodePixels=200MP` 放行、实际 `renderImageWithDPI` 可分配约 580MB，`5000×5000pt`
页面在 `-Xmx512m` 下已复现 OOM；图片解码也只按 50MP 子采样、未按 4000px/64MB 约束，违反
ADR-0023 §4.4 与 M05-AC-05/SEC-KB-005。另有 P2：`RICCON_OCR_LANG` 因 `lang`/`language` 键不一致
实际无效、全页无文本 PDF 返回 `KB_OCR_FAILED`（应为 `KB_NO_TEXT`）；bbox/ocr_confidence 尚未落库
（B7 验收未列，需 Lead 收口）。P1 修复前不建议任务 done。

## 审查范围与证据

| 项         | 值                                                                                                                                       |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| baseline   | `bb0099f4e4d19873994f6abb6acee45aa8249498`                                                                                               |
| 任务提交   | `6c61754e9000d9401175674fca7b258b9b29a2af`（code）；`be1155d` 仅为 EV 录制时的 B3 scope amendment 元数据提交                             |
| 代码 diff  | 41 文件，全部在 Lead 补录后的 write_scope 内（ocr/**、parse/service 5 文件、pom/application.yml、test 资源）                             |
| 任务级证据 | EV-0825（builder，worker 全量 + package + 镜像内 OcrCli；commit be1155d，dirty=false）；EV-0830（verifier，worktree 6c61754，97 用例）   |
| 独立探针   | `.riccon/evidence/TASK-0168/probe-2026-11-23-ocr-pdf-oom.*`、`...-lang-binding.log`、`...-chunker-overlap.log`、`...-ocr-nottext-code.*` |
| 未跑       | 本任务未重复全量 worker（EV-0825 已覆盖；M05-V1/V2 再跑）；coverage/E2E/a11y/security/perf/迁移属模块 gate；O1b 零出网编排属 TASK-0175   |

范围核对：`git diff --name-status bb0099f..6c61754` 共 41 文件，无 B3/B4 生产文件、无迁移、无前端；
新增依赖 Tess4J 5.20.0、JNA、lept4j、jai-imageio-core、commons-io 的许可为 Apache-2.0/BSD，
与合同允许清单一致；NOTICE 由 TASK-0187（Ops，ready）另行收口，CODING-STANDARDS 的 `ocr` tag 由
TASK-0188（Architect，in_progress）收口。

### EV 真实性核对

- **EV-0825**：`output_sha256=f232206a...d673c` 与日志一致（261,918 bytes）；命令含 worker 全量
  `./mvnw -q -T1C -pl apps/worker -am test`、`package`、`docker run --rm --network none` 的 OcrCli；
  log 末尾为 `engine=tesseract`、`averageConfidence=0.922`、`blocks=1`、`line=1 ... bbox=57,74,766,54`、
  `text=RICCON OCR 12345`；exit 0、dirty=false、commit be1155d。**注意**：builder 自报的 511 tests
  含 4 个已删除 `OcrLineAssemblerTest` 的 stale surefire 报告，实际约 507，见 F-007。
- **EV-0830**：`output_sha256=5c02d53e...cc13f` 与日志一致（17,393 bytes）；commit=6c61754、
  dirty=false；命令在隔离 worktree 运行定向测试，13 个 suite/97 tests/0 failure/0 error/0 skip；
  cwd 为 worktree，已按 F-006 记录流程例外。

### Gate check（预期失败）

`riccon gate check --module M05 --json` 返回 `ok=false`：模块仍 `building`，TASK-0166 `in_progress`、
TASK-0167/TASK-0168 `review`、TASK-0169 之后 `planned`，且没有模块 review；`task-scope:TASK-0166/0168`
还被主树上的 B3/B4 WIP 与 TASK-0187/0188 文档变更污染（与 F-006 同类）。这些是模块未收口与 dirty 主树
原因，不是 B5 代码通过与否的替代证据；本评审 verdict=fail，gate 不应通过。

### 独立定向验证

```bash
# EV-0830（worktree 6c61754；加锁；含 Ocr*、DocumentParser、PdfDocumentParser、TextChunker、FileParseServiceIntegrationTest）
LOCK="${TMPDIR:-/tmp}/riccon-maven.lock"; while ! mkdir "$LOCK" 2>/dev/null; do sleep 5; done; \
  trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT; \
  ./mvnw -q -T1C -pl apps/worker -am test \
    -Dtest='*Ocr*Test,*DocumentParserTest,*PdfDocumentParserTest,*TextChunkerTest,*FileParseServiceIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
# 13 suites, 97 tests, 0 failures, 0 errors, 0 skipped (surefire reports 汇总)
```

探针（手动、非 EV；源文件与日志已放入 `.riccon/evidence/TASK-0168/`）：

```text
# F-001：5000×5000pt PDF，200 DPI，-Xmx512m
maxHeapMiB=512
pagePoints=5000x5000 pdfDpi=200 renderedPixels=192904321 maxDecodePixels=200000000
THROWN java.lang.OutOfMemoryError: Java heap space

# F-002：Spring Binder 绑定 riccon.worker.ocr.lang=eng
bound language=chi_sim+eng expected=eng-from-lang-key

# F-003：空白扫描 PDF
errorCode=KB_OCR_FAILED retryable=true

# F-005：TextChunker chunkSize=100 overlap=80，textLength=182
chunkCount=46 textLength=182
```

OcrCli 零出网独立复核：使用 EV-0825 打包的 `riccon-worker-0.1.0-SNAPSHOT-exec.jar`
（`be1155d` 与 `6c61754` 的代码树只差 `.riccon/tasks/TASK-0166.json`）在 `riccon-worker-ocr:o1a`
内以 `--network none` 运行 `OcrCli /ocr/sample.png --lang chi_sim+eng --dpi 200`，输出
`engine=tesseract / averageConfidence=0.922 / line=1 confidence=0.922 bbox=57,74,766,54 /
text=RICCON OCR 12345`，见 `probe-2026-11-23-ocr-zero-network.log`；与 EV-0825 的 builder 证据一致。

## 逐项审查结论

| #   | 重点（任务 brief）                                                                                                                                                     | 结论                   | 依据/残余                                                                                                                                                                                                                                                                                         |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | SPI/引擎：OcrEngine 字节+选项、不泄漏 Tess4J；ResultIterator 行级 text/bbox/confidence；启动失败/WARN、info 指纹；测试 profile disabled                                | **pass（P2 lang）**    | `OcrEngine` 无 Tess4J 类型；`TesseractOcrEngine` 用 `TessResultIteratorGetUTF8Text/BoundingBox/Confidence(RIL_TEXTLINE)`；构造期加载 native、语言 probe WARN；info 暴露 engine/lang/model_hash；`application-test.yml` enabled=false。`lang` 绑定无效见 F-002。                                   |
| 2   | 预处理器：解码前像素上限、EXIF 90/180/270、子采样/缩放、PNG 规范化、OOM/超时边界                                                                                       | **fail（P1）**         | 头部尺寸检查、EXIF 6/3/8、subsampling、scaleDown、PNG 规范化与损坏输入测试通过；但 200MP 检查与 64MB/50MP 预算不一致，PDF 页 OOM 已复现，见 F-001。                                                                                                                                               |
| 3   | 解析接入：PNG/JPEG/图片 PDF；文本 PDF/DOCX 原路径；混合页部分失败、失败页零分块；KB_OCR_DISABLED 保留原图；FileContentExtractor 只读一次；TextChunker 换行/bbox/置信度 | **pass（P2/P3 残余）** | `ImageDocumentParser`/`JpegDocumentParser`/`PdfDocumentParser` 接入；文本页不 OCR；混合页只 OCR 无文本页；失败页无 segment；关闭返回 KB_OCR_DISABLED；`FileContentExtractor` 一次读取；chunker 继承 bbox/confidence。全 NO_TEXT 码见 F-003；chunker 边界见 F-005。                                |
| 4   | 资源/线程：riccon-ocr 池、30s/600s/2/100；不阻塞聊天首字；超时/取消/降级                                                                                               | **partial（P1/P3）**   | 固定池命名 `riccon-ocr`、maxConcurrency=2、页/文档预算与 maxPages；OCR 在 outbox heavy 而非 chat 线程。OOM 见 F-001；native 不可中断见 F-008。                                                                                                                                                    |
| 5   | OcrCli/零出网：standalone、`--network none`、文本/置信度/bbox、不下载权重/不访问公网                                                                                   | **pass**               | EV-0825 `docker run --rm --network none` 输出 engine/averageConfidence/bbox/text；OcrCli 不启动 Spring/MySQL/OpenSearch，无网络代码；独立重跑见探针日志。                                                                                                                                         |
| 6   | 四态/错误：成功/低置信/部分失败/全失败；KB_OCR_DISABLED/LANG_UNAVAILABLE 等稳定码；不 500/堆栈                                                                         | **partial（P2）**      | 四态测试与 warning 映射齐全；错误消息走 `OcrFailureMessages` 中文稳定文案；全 NO_TEXT 映射错误见 F-003。PDF load 损坏仍为既有 RICCON-FILE-422（B3/B8 展示层收口）。                                                                                                                               |
| 7   | 测试质量：fake engine/真实预处理器；IT 默认排除；零出网独立证据；错误实现会失败；覆盖关闭/EXIF/超限/部分失败                                                           | **pass with gaps**     | FakeOcrEngine 单测 + 真实 EXIF/预处理器；`TesseractOcrEngineIT` 不在默认 surefire（*IT 不被 include，且无 failsafe 配置，只能 `-Dtest` 显式运行）；OcrCli 零出网独立；关闭/EXIF/超限/部分失败有测试；但 near-limit OOM、lang 绑定、全 NO_TEXT、重试幂等（B3/B7）缺失；511 计数含 stale 见 F-007。 |
| 8   | 范围/合规：41 文件全在 scope；依赖许可；EV 命令/哈希/commit                                                                                                            | **pass**               | 41/41 在 scope；Tess4J/JNA/lept4j/jai-imageio-core/commons-io 许可允许；EV-0825 哈希/commit/exit 一致；NOTICE TASK-0187、CODING-STANDARDS TASK-0188 已转派。                                                                                                                                      |

## AC 对账（M05 段相关）

| AC        | 结论                            | 依据/残余                                                                                                                                                                                                 |
| --------- | ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M05-AC-03 | **pass with residual（B7/B8）** | 解析链的 PNG/JPEG/图片 PDF、文本 PDF/DOCX 不变、EXIF 6/3/8、页码/bbox/置信度到 `ParsedChunk`、关闭/LANG 稳定码、无伪引用均通过；bbox 落库与引用详情 UI 由 B7/B8 完成（F-004），当前不能宣称整条 AC pass。 |
| M05-AC-04 | **pass with residual（B3/B7）** | 高/低置信、部分失败、全失败、失败页零分块、`LOW_CONFIDENCE*`/`FAILED_PAGES` 有测试；重试幂等与“缺失页补齐”属 B3 reindex/B7，B5 只提供无状态解析；全 NO_TEXT 码错误见 F-003。                              |
| M05-AC-05 | **fail（P1）**                  | 零出网、OCR 关闭、已有结果保留、无引擎配置/凭据泄露通过；超大像素/资源上限在 near-limit PDF 页上 OOM，违反“被资源上限拒绝且不 OOM”，见 F-001。                                                            |
| M05-AC-15 | **partial（B3/B8）**            | OCR 失败可读中文原因、稳定码、可重试标记存在；全 NO_TEXT 码错误（F-003）；reindex/删除与审计属 B3。                                                                                                       |
| M05-AC-16 | **not in B5**                   | 审计写入/查询由 B3 与 M05-B10/B8 负责；B5 不写审计。                                                                                                                                                      |
| M05-AC-22 | **partial（模块 gate 复核）**   | EV-0825 的 worker 全量回归通过；本评审未重复全量；M05-V1/V2 在最终提交上跑合同全量。                                                                                                                      |

## Findings 详情

### F-001（P1）资源上限未阻止 OOM：PDF 页按 200MP 放行、图片解码峰值超 64MB

**观察**：`PdfDocumentParser` 用 `maxDecodePixels`（默认 200,000,000）作为渲染前检查，5000×5000pt
页面在 200 DPI 下是 192,904,321 像素，小于 200MP，因此通过检查；`PDFRenderer.renderImageWithDPI`
会为整页分配 RGB `BufferedImage`（约 580MB），在 `-Xmx512m` 下直接 `OutOfMemoryError`。图片路径的
`OcrImagePreprocessor.decode` 只按 `maxPixels=50,000,000` 计算子采样步长，7071×7071 图会先解 50MP
（约 150–200MB）再 `scaleDown` 到 4000px；`maxLongEdge` 没有参与解码前的步长计算。ADR-0023 §4.4
写的是“图片像素 50MP，最长边 4000px”与“单页解码后 ≤64MB”，当前实现满足前者的目标尺寸，但
峰值内存不是 64MB；`max-decode-pixels=200MP` 也没有对应的 64MB 预算。

**期望**：按 64MB 单页预算推导解码/渲染上限（例如约 16MP for 4-byte、或把 `maxLongEdge` 纳入
子采样步长），PDF 页在渲染前按该预算拒绝或降 DPI/缩放；OOM 不得由用户可控的 PDF/图片触发。
不应把“超大页”测试只放在 10^10 像素/771MP 的极端点，而应覆盖 150–193MP 边界并至少在受限堆下
断言不 OOM。

**影响**：T4 资源边界与 M05-AC-05/SEC-KB-005 失败；恶意 PDF/大图可让 worker OOM，导致 outbox 处理
中断，OCR/索引不可用。该 finding 是本次 fail 的主因。

### F-002（P2）`lang` 与 `language` 键不一致，RICCON_OCR_LANG 无效

**观察**：YAML 使用 `lang:`，记录组件是 `language`。Spring Boot 的 relaxed binding 只做 kebab/camel/
下划线等大小写归一，不会把 `lang` 识别为 `language`；用 `Binder` 绑定 `riccon.worker.ocr.lang=eng`
得到默认 `chi_sim+eng`。`OcrInfoContributor` 读取 `properties.language()`，所以 info 也显示默认；
`OcrCli --lang` 直接传参，不受影响。

**期望**：YAML 改为 `language: ${RICCON_OCR_LANG:chi_sim+eng}`，或在记录组件上加 `@Name("lang")`；
增加 `ApplicationContextRunner`/`Binder` 绑定测试，确认 `RICCON_OCR_LANG=eng` 在 `OcrProperties.language()`
与 `/actuator/info` 均生效。

**影响**：运维无法按 ADR 切换语言；只安装 `eng` 的部署即使设置 `RICCON_OCR_LANG=eng` 仍会因
`chi_sim` 缺失而让 OCR 文档失败（`KB_OCR_LANG_UNAVAILABLE`），属于可见功能缺陷。

### F-003（P2）全页无文本 PDF 的 `KB_NO_TEXT` 被归为可重试 `KB_OCR_FAILED`

**观察**：空白页的 OCR 结果是 `NO_TEXT`；所有页都如此时，`dominantFailure` 不识别 `NO_TEXT`，
落到 `OCR_FAILED`，并把 retryable 设为 true。ADR §4.5 明确全页无文本应为 `FAILED/KB_NO_TEXT`。
这会让文档详情显示“OCR 识别失败，请稍后重试”并让 outbox 继续重试，而不是让用户替换/确认空白页。

**期望**：`dominantFailure` 在所有 errors 均 `NO_TEXT` 时返回 `NO_TEXT`（不可重试）；增加
“空白扫描 PDF → KB_NO_TEXT”测试，覆盖 OCR 开启且引擎返回空块的情况。

**影响**：AC-04/AC-15 的用户可见失败原因与重试语义不准确；不是数据泄露，但会放大无效重试。

### F-004（P2 accepted）bbox/ocr_confidence 尚未落库，B7 验收未列

B5 已把 bbox/confidence 从 `OcrBlock` → `ParsedSegment` → `ParsedChunk` 透传，`TextChunker` 也继承；
但 `KnowledgeRepository`/`KnowledgeChunkRecord`/`FileRepository` 的 INSERT 仍是 M02 的列集合，
`KnowledgeIndexService.toParsedChunk` 重建时也丢失这两个字段。V12 已加列，ADR IT-KB-104 要求
“bbox 与置信度落库”，M05-AC-03 的引用详情与 B8 都依赖它。B5 write_scope 不含 `index/**` 与
`parse/repository/**`，所以本任务不判代码失败；但 TASK-0169 的 acceptance/scope 未出现 bbox，
必须由 Lead 在 B7 前扩验收（knowledge_chunk）并评估 file_chunk 是否需要单独任务。否则 M05-AC-03
只能标 `pass (with residual)`，模块 gate 不能宣称该 AC 完成。

### F-005（P3 accepted）TextChunker 极端 overlap 下的重复块放大

生产默认 800/80 与测试 100/20 安全；但 `ParseProperties` 允许任意 `overlap < chunkSize`。当
`overlap >= chunkSize/2` 时，换行断点回退后 `end - overlap < start`，fallback `start + 1` 每次只前进
一个字符，导致大量重复块（182 字 → 46 块）。建议把 `next` 的 fallback 改为 `end`（不回退重叠），
或把校验收紧为 `chunkOverlap < chunkSize/2`；同时给 `chunk_longTextWithNewlines_breaksAtNewline`
增加总块数/去重断言，避免只断言“块数 >1”。

### F-006（P3 accepted）EV-0830 worktree 流程例外

主树被 B3/B4 WIP 占用，无法在 6c61754 上录 dirty=false 证据；EV-0830 在隔离 worktree 录制并回灌
主树。EV 的 commit/dirty/命令/97 用例可复核，但这是并行取证流程例外，需 Lead 在 M05 gate 前统一
规范；不能让模块评审把 worktree 证据当成主树 clean 证据。

### F-007（P3 accepted）EV-0825 的 511 计数含 stale 报告

surefire 合计 511，其中 4 个来自已删除 `OcrLineAssemblerTest`；实际约 507。EV exit 0、0 failure/
0 skip 的结论不受影响，但 builder 的测试计数不精确；建议以后在 clean 的 `target` 或 worktree 上
重新汇总，避免把 stale XML 计入交付报告。

### F-008（P3 accepted）native 超时/池耗尽的降级边界

`future.cancel(true)` 不能中断 Tesseract native 调用；固定 2 线程池在两个挂起页后可能一直排队超时，
文档超时只停止提交，不能恢复池；图片预处理/PDF 渲染也不在页 timeout 内。现有测试没有 real
Tesseract 挂起样本；O1b/RUNBOOK 需要给出 `riccon_ocr_*` 指标与处置（或把 OCR 放到可 kill 的
进程/容器边界）。这是 P3 残余，不阻塞本任务，但 T4 运维手册必须覆盖。

## 未验证

- **本任务未重复全量 worker**：EV-0825 已跑全量；本评审为定向 97 用例。M05-V1/V2 在最终提交上跑
  配置全量、coverage、E2E、a11y、security、perf、迁移回滚；本任务不替代模块 gate。
- **真实 native 缺失/语言包缺失启动失败**：O1a 镜像有 Tesseract/tessdata，未在“无 native/无 chi_sim”
  镜像上跑负向启动；O1b（TASK-0175）与 O1a `deploy/ocr/verify.sh` 覆盖镜像侧，代码侧只有构造期
  fail-fast 静态审查，无单元测试。
- **`TesseractOcrEngineIT` 未运行**：默认 surefire 不匹配 `*IT`，项目也没有 failsafe；只能通过
  `-Dtest=TesseractOcrEngineIT -DRICCON_OCR_IT=true` 显式运行。本次用 OcrCli 零出网证据覆盖真实
  Tesseract；IT 的专用 CI job 尚未登记（O1b/RUNBOOK）。
- **bbox 落库/引用详情/UI**：B7/B8 尚未实现；F-004 只验证到 `ParsedChunk`。
- **50MP 图片堆峰值**：只做了代码推导（50MP×3–4B），未在受限堆下单独测图片；PDF 页的 OOM 已实测。
- **重试幂等/缺失页补齐**：B3 reindex/B7 的实现与测试（本任务只提供无状态解析与 warning）。
- **OCR 精度门槛（CER）**：ADR §4.2 的 20 张印刷体 CER 基线未在本任务执行；O1a 仅 sample.png 冒烟。

## 剩余风险

- `OcrManifest.load` 在 manifest 不可读时日志带 `path=`；路径非凭据，但 AC-03 的“日志不含 OCR 引擎
  配置”口径较严，建议 M05-V2 统一确认。
- 损坏 EXIF 的 JPEG 若在 `reader.getImageMetadata(0)` 阶段抛 `IIOException`，当前会映射为
  `KB_PARSE_CORRUPT` 而不是按 0° 处理；SEC-KB-005 只要求不 OOM，但 ADR §4.3 的“校正失败按 0°”
  需要 M05-V2 用畸形 EXIF 夹具确认。
- PDF 文本页判定只按 `text.isBlank()`，头部/页脚有少量文本层的扫描页会跳过 OCR；ADR 的“按可抽取
  文本长度”未量化，需 Product/Architect 明确是否需要阈值。
- `FileParser` 的 `file_chunk` 路径同样未写 bbox；若 M05-B8 的“文件解析详情”需要区域，需扩展任务。
- `TesseractOcrEngineIT` 没有 failsafe/专用 job；真实引擎回归目前依赖 O1a/O1b 的 OcrCli。

## 转派与需 Lead 处理

| 项                         | 状态/动作                                                                                                                                 |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 P1 资源上限/OOM      | 由 `riccon/builder-kb-ocr` 修 B5（解码/渲染预算 + near-limit 测试），`riccon/architect` 确认 64MB/50MP/4000px 口径；修复后独立复评。      |
| F-002 P2 lang 绑定         | B5 修 YAML `language` 或 `@Name("lang")` + 绑定测试；与 F-001 同批复评。                                                                  |
| F-003 P2 全 NO_TEXT 码     | B5 修 `dominantFailure` + 测试。                                                                                                          |
| F-004 bbox 落库            | Lead 扩 TASK-0169（B7）验收/scope 或拆任务，明确 knowledge_chunk（及 file_chunk）的 bbox/ocr_confidence 持久化；M05-AC-03 当前 residual。 |
| F-005/F-006/F-007/F-008 P3 | 分别由 builder / Lead / builder / Ops 在 M05 gate 前接受或修复；已在 front matter 写 owner/due/accepted_reason。                          |
| NOTICE                     | TASK-0187（Ops，ready）：Tess4J/JNA/lept4j/jai-imageio-core/commons-io 与 Tesseract/tessdata 的 NOTICE/SBOM；不属 B5。                    |
| CODING-STANDARDS `ocr` tag | TASK-0188（Architect，in_progress）：登记 `@Tag("ocr")` 与专用 job；不属 B5。                                                             |
| O1b 零出网                 | TASK-0175（Ops，planned）：复用 `OcrCli`，在 `--network none` 一次性容器只跑引擎+fixture；B5 已提供入口与 EV-0825 冒烟。                  |
| 任务状态                   | 建议保持 `review`；F-001 修复并复评通过前不要 done。本评审不修改任务状态。                                                                |
