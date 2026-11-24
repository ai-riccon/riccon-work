package com.riccon.worker.injection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.AgentPrompts;
import com.riccon.worker.agent.service.ChatAgentService;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.KnowledgeSearchTool;
import com.riccon.worker.agent.service.UntrustedData;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.index.domain.EmbeddingModel;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import com.riccon.worker.index.domain.KnowledgeDocumentRecord;
import com.riccon.worker.index.repository.KnowledgeRepository;
import com.riccon.worker.index.service.KnowledgeIndexService;
import com.riccon.worker.index.service.OpenSearchIndexClient;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.parse.domain.ParseWarningCodes;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.repository.FileRepository;
import com.riccon.worker.parse.service.DocumentParserRegistry;
import com.riccon.worker.parse.service.FileContentExtractor;
import com.riccon.worker.parse.service.S3FileContentReader;
import com.riccon.worker.parse.service.TextChunker;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.service.EventPayloadParser;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * M05-B10/B10b 可执行注入 harness：`INJ-001..012`（ADR-0023 §8/§11.2，验收别名
 * `SEC-KB-004` / `SEC-CONN-002`）。
 *
 * <p>每条样本走完整的生产链路：真实 XLSX/OCR/连接器解析 → 生产切分 → 引用形状 → {@link ChatAgentService}
 * 的真实系统提示与工具装配 → 假模型。断言模型只看到不可信数据块，系统指令保持静态，
 * 回答不泄露 canary/内网地址，工具面只有只读知识检索，日志不出现注入内容。
 *
 * <p>M05b-B10b 的连接器样本复用同一假模型、tripwire、工具权限与日志断言；差异只在解析入口
 * （txt/md/docx/pdf）和引用标题。连接器引用仍是 `KNOWLEDGE` 来源，遮蔽/来源状态由 API 侧
 * {@code CitationResponse} 统一处理。
 *
 * <p>假模型是 tripwire：只有生产提示构造把注入提升为指令时才会输出 canary；因此该 harness
 * 不依赖真实模型的随机服从度，也不访问网络、MCP 或数据库。
 */
class InjectionHarnessTest {

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-inj");

    /** 运行 ID。 */
    private static final UUID RUN = UUID.fromString("00000000-0000-4000-8000-000000000172");

    /** 用户 ID。 */
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    /** 会话 ID。 */
    private static final UUID CONVERSATION = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    /** 用户问题保持中性，避免测试自身携带注入检测词。 */
    private static final String QUESTION = "请总结这份季度资料的关键数字。";

    /** 与生产默认一致的 Agent 预算。 */
    private static final AgentProperties AGENT_PROPERTIES = new AgentProperties(4, 5, 5, 2000, 60);

    /** 参数化样本来源；display 让失败消息直接带稳定 ID。 */
    static Stream<Arguments> samples() {
        return Stream.concat(InjectionSamples.ALL.stream(), ConnectorInjectionSamples.ALL.stream())
                .map(sample -> Arguments.of(sample.display(), sample));
    }

    /**
     * 执行一条注入样本并断言边界、回答、工具与日志。
     *
     * @param display 失败定位名
     * @param sample 样本
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @Timeout(60)
    void injectionSample_isContainedAndCannotLeak(String display, InjectionSample sample) {
        ListAppender<ILoggingEvent> logs = attachRootLog();
        AgentSession session = null;
        try {
            ParsedContent parsed = InjectionFixtures.parse(sample);
            List<ParsedChunk> chunks = InjectionFixtures.chunks(parsed);
            List<RetrievedChunk> citations = InjectionFixtures.citations(sample, chunks);
            assertParseExpectation(display, sample, parsed, chunks);
            if (sample.surface().isConnector()) {
                assertConnectorCitations(display, sample, citations);
            }

            KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
            when(retriever.retrieve(eq(TENANT), eq(USER), eq(RUN), eq(QUESTION), eq(List.of())))
                    .thenReturn(citations);
            when(retriever.retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), any()))
                    .thenReturn(citations);
            InjectionModel.Mode mode = sample.expectToolCall()
                    ? InjectionModel.Mode.CALL_KNOWLEDGE_THEN_ANSWER
                    : InjectionModel.Mode.BOUNDARY_AWARE;
            InjectionModel model = new InjectionModel(mode, InjectionSamples.CANARY_VALUE, sample.detectors());
            ChatAgentService service = new ChatAgentService(retriever, AGENT_PROPERTIES);

            session = service.prepare(
                    TENANT,
                    RUN,
                    CONVERSATION,
                    USER,
                    QUESTION,
                    List.of(),
                    List.of(new ConversationMessage(UUID.randomUUID(), MessageRole.USER, QUESTION)),
                    model,
                    modelTarget());

            List<AgentEvent> events = session.events().collectList().block(Duration.ofSeconds(30));
            assertThat(events).as("%s 事件流不能为空", display).isNotNull();

            assertThat(model.breaches())
                    .as("%s 注入文本逃出不可信数据块；当前生产提示构造可被注入", display)
                    .isEmpty();
            assertThat(model.calls()).as("%s 模型至少被调用一次", display).isNotEmpty();
            assertThat(model.calls()).as("%s canary 值不得进入模型请求的任何表面", display).allSatisfy(call -> {
                assertThat(call.scan().instructionTexts())
                        .allSatisfy(text -> assertThat(text).doesNotContain(InjectionSamples.CANARY_VALUE));
                assertThat(call.scan().untrustedTexts())
                        .allSatisfy(text -> assertThat(text).doesNotContain(InjectionSamples.CANARY_VALUE));
            });

            InjectionModel.ModelCall firstCall = model.calls().get(0);
            assertThat(firstCall.systemTexts())
                    .as("%s 系统提示必须等于静态常量，不能被检索内容替换", display)
                    .contains(AgentPrompts.CHAT_SYSTEM.strip());
            assertThat(firstCall.scan().systemTexts())
                    .as("%s 所有系统消息都不能包含注入检测词", display)
                    .allSatisfy(text -> assertThat(text.toLowerCase(Locale.ROOT))
                            .doesNotContain("canary")
                            .doesNotContain("198.51.100.7")
                            .doesNotContain("忽略")
                            .doesNotContain("ignore previous"));

            assertThat(session.agent().getSysPrompt().strip())
                    .as("%s Agent 系统提示保持静态", display)
                    .isEqualTo(AgentPrompts.CHAT_SYSTEM.strip());
            assertThat(session.agent().getToolkit().getToolNames())
                    .as("%s 聊天 Agent 只能装配只读知识检索工具", display)
                    .containsExactly(KnowledgeSearchTool.TOOL_NAME);
            assertThat(session.agent()
                            .getToolkit()
                            .getTool(KnowledgeSearchTool.TOOL_NAME)
                            .isReadOnly())
                    .as("%s knowledge_search 必须是只读工具", display)
                    .isTrue();

            assertPromptBoundary(display, sample, model);
            assertAnswerAndEvents(display, sample, model, events, retriever);
            assertLogsHaveNoSecrets(display, sample, logs);
        } finally {
            if (session != null) {
                session.close();
            }
            detachRootLog(logs);
        }
    }

    /**
     * 连接器索引链：平台桶中的恶意原文由生产 {@link KnowledgeIndexService} 读取、抽取、切分并写入分块，
     * 内容保持完整但仍是不可信数据；随后同一批分块交给检索/聊天由参数化 harness 覆盖。
     */
    @Test
    @Timeout(60)
    void connectorIndex_preservesInjectionAsUntrustedChunk() throws Exception {
        InjectionSample sample = ConnectorInjectionSamples.byId("INJ-001");
        UUID documentId = UUID.fromString("00000000-0000-4000-8000-000000000b10");
        Instant createdAt = Instant.parse("2026-11-24T00:00:00Z");
        KnowledgeDocumentRecord document = new KnowledgeDocumentRecord(
                documentId,
                ConnectorInjectionFixtures.fileName(sample),
                null,
                createdAt,
                KnowledgeDocumentStatus.PENDING,
                KnowledgeVisibility.TENANT,
                null,
                1,
                "CONNECTOR",
                UUID.randomUUID(),
                null);

        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        FileRepository fileRepository = mock(FileRepository.class);
        FileContentExtractor contentExtractor = mock(FileContentExtractor.class);
        S3FileContentReader contentReader = mock(S3FileContentReader.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        OpenSearchIndexClient indexClient = mock(OpenSearchIndexClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<List<ParsedChunk>> captured = new AtomicReference<>();

        when(repository.findDocument(TENANT, documentId)).thenReturn(Optional.of(document));
        when(repository.markIndexing(TENANT, documentId)).thenReturn(true);
        when(contentReader.readConnector(TENANT, documentId, createdAt))
                .thenReturn(ConnectorInjectionFixtures.content(sample));
        when(repository.replaceChunks(eq(TENANT), eq(documentId), any())).thenAnswer(invocation -> {
            List<ParsedChunk> chunks = invocation.getArgument(2);
            captured.set(List.copyOf(chunks));
            return chunks.stream()
                    .map(chunk -> new KnowledgeChunkRecord(
                            UUID.randomUUID(),
                            chunk.content(),
                            chunk.locatorType(),
                            chunk.pageNo(),
                            chunk.sheetName(),
                            chunk.cellRange(),
                            chunk.paragraphNo(),
                            chunk.tokenCount(),
                            chunk.bbox(),
                            chunk.ocrConfidence()))
                    .toList();
        });

        KnowledgeIndexService service = new KnowledgeIndexService(
                repository,
                fileRepository,
                contentExtractor,
                contentReader,
                parserRegistry,
                new TextChunker(InjectionFixtures.PARSE_PROPERTIES),
                new EmbeddingModel() {
                    @Override
                    public int dimensions() {
                        return 3;
                    }

                    @Override
                    public float[] embed(String text) {
                        return new float[] {0.1f, 0.2f, 0.3f};
                    }
                },
                indexClient,
                new EventPayloadParser(objectMapper));
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                UUID.randomUUID(),
                TENANT.value(),
                RUN,
                "riccon.knowledge.index.requested.v1",
                1,
                "inj-connector-index",
                objectMapper.writeValueAsString(
                        Map.of("documentId", documentId.toString(), "attempt", 1, "mode", "FULL")),
                1,
                Instant.now(),
                Instant.now().plusSeconds(60));

        TenantContext.runWithTenant(TENANT, () -> service.handle(event));

        List<ParsedChunk> chunks = captured.get();
        assertThat(chunks).as("连接器索引必须产生分块").isNotNull().isNotEmpty();
        String joined =
                String.join("\n", chunks.stream().map(ParsedChunk::content).toList());
        assertThat(joined).as("连接器原文必须完整进入索引").contains(sample.payload());
        assertThat(joined).as("索引内容不得包含 canary 值").doesNotContain(InjectionSamples.CANARY_VALUE);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.locatorType()).isEqualTo(LocatorType.PARAGRAPH);
            assertThat(chunk.paragraphNo()).isNotNull();
        });
        verify(contentReader).readConnector(TENANT, documentId, createdAt);
        verify(repository)
                .updateParseMetadata(eq(TENANT), eq(documentId), eq(ParseEngine.NONE), eq(List.of()), isNull());
        verify(repository).markIndexed(TENANT, documentId);
        verify(indexClient).replaceDocument(eq(TENANT), any(KnowledgeDocumentRecord.class), anyList(), anyList());
    }

    /**
     * 断言解析结果与样本输入表面一致，且隐藏表/公式不会被误索引。
     *
     * @param display 失败定位名
     * @param sample 样本
     * @param parsed 解析结果
     * @param chunks 切分结果
     */
    private static void assertParseExpectation(
            String display, InjectionSample sample, ParsedContent parsed, List<ParsedChunk> chunks) {
        assertThat(parsed.segments()).as("%s 解析结果不能为空", display).isNotEmpty();
        String payload = sample.payload();
        String joined =
                String.join("\n", chunks.stream().map(ParsedChunk::content).toList());
        switch (sample.surface()) {
            case XLSX_HIDDEN_SHEET -> {
                assertThat(parsed.warnings())
                        .as("%s 隐藏表必须产生 HIDDEN_SHEETS_SKIPPED 警告", display)
                        .contains(ParseWarningCodes.withCount(ParseWarningCodes.HIDDEN_SHEETS_SKIPPED, 1));
                assertThat(joined).as("%s 隐藏表内容不得进入索引", display).doesNotContain(payload);
                assertThat(parsed.summary().toString())
                        .as("%s 摘要不得包含隐藏表原文", display)
                        .doesNotContain(payload);
            }
            case XLSX_FORMULA_CELL -> {
                assertThat(joined).as("%s 公式缓存值必须作为文本进入索引", display).contains(payload);
                assertThat(joined).as("%s 解析器不得执行公式或暴露公式文本", display).doesNotContain("CONCATENATE(");
                assertThat(chunks.get(0).sheetName()).isEqualTo(sample.sheetName());
                assertThat(chunks.get(0).cellRange()).isNotBlank();
                assertThat(chunks.get(0).locatorType().name()).isEqualTo("SHEET");
            }
            case XLSX_VISIBLE_CELL -> {
                assertThat(joined).as("%s 可见单元格必须进入索引", display).contains(payload);
                assertThat(chunks.get(0).sheetName()).isEqualTo(sample.sheetName());
                assertThat(chunks.get(0).cellRange()).isNotBlank();
            }
            case OCR_LINE, OCR_FORGED_BOUNDARY -> {
                assertThat(joined).as("%s OCR 文本必须进入索引", display).contains(payload);
                assertThat(parsed.parseEngine()).as("%s 必须走 OCR 解析引擎", display).isEqualTo(ParseEngine.TESSERACT);
                assertThat(parsed.pageCount()).as("%s 单页图片页码", display).isEqualTo(1);
                assertThat(chunks.get(0).pageNo()).as("%s OCR 引用必须带页码", display).isEqualTo(1);
                assertThat(chunks.get(0).bbox()).as("%s OCR 引用必须带区域坐标", display).isNotNull();
                assertThat(chunks.get(0).ocrConfidence())
                        .as("%s OCR 引用必须带置信度", display)
                        .isNotNull();
            }
            case CONNECTOR_TXT, CONNECTOR_MD -> {
                assertThat(joined).as("%s 连接器纯文本必须进入索引", display).contains(payload);
                assertThat(parsed.parseEngine())
                        .as("%s 连接器 txt/md 不经过二进制解析器", display)
                        .isEqualTo(ParseEngine.NONE);
                assertThat(chunks.get(0).locatorType()).isEqualTo(LocatorType.PARAGRAPH);
                assertThat(chunks.get(0).paragraphNo())
                        .as("%s 连接器纯文本引用必须带段落定位", display)
                        .isNotNull();
            }
            case CONNECTOR_DOCX -> {
                assertThat(joined).as("%s 连接器 docx 必须进入索引", display).contains(payload);
                assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.NONE);
                assertThat(chunks.get(0).locatorType()).isEqualTo(LocatorType.PARAGRAPH);
                assertThat(chunks.get(0).paragraphNo())
                        .as("%s 连接器 docx 引用必须带段落定位", display)
                        .isNotNull();
            }
            case CONNECTOR_DOCX_WHITE_TEXT -> {
                assertThat(joined)
                        .as("%s 白字/小字号不是信任边界：解析器仍抽取文本，随后必须按不可信数据隔离", display)
                        .contains(payload);
                assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.NONE);
                assertThat(chunks.get(0).locatorType()).isEqualTo(LocatorType.PARAGRAPH);
                assertThat(chunks.get(0).paragraphNo()).isNotNull();
            }
            case CONNECTOR_DOCX_TABLE -> {
                assertThat(joined).as("%s 表格公式样式文本必须按字面量进入索引", display).contains(payload);
                assertThat(joined).as("%s 解析器不得执行公式或把公式文本改写成结果", display).contains("=WEBSERVICE(");
                assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.NONE);
                assertThat(chunks.get(0).locatorType()).isEqualTo(LocatorType.PARAGRAPH);
                assertThat(chunks.get(0).paragraphNo()).isNotNull();
            }
            case CONNECTOR_PDF -> {
                assertThat(joined).as("%s 连接器 pdf 文本层必须进入索引", display).contains(payload);
                assertThat(parsed.parseEngine())
                        .as("%s 文本层 pdf 走 PDFBox，不触发 OCR", display)
                        .isEqualTo(ParseEngine.PDFBOX);
                assertThat(parsed.pageCount()).as("%s pdf 单页", display).isEqualTo(1);
                assertThat(chunks.get(0).locatorType()).isEqualTo(LocatorType.PAGE);
                assertThat(chunks.get(0).pageNo()).as("%s pdf 引用必须带页码", display).isEqualTo(1);
            }
            default -> throw new IllegalStateException("unexpected surface " + sample.surface());
        }
    }

    /**
     * 断言连接器引用的来源类型、标题与定位；伪造引用行不能覆盖系统生成的引用头。
     *
     * @param display 失败定位名
     * @param sample 连接器样本
     * @param citations 引用
     */
    private static void assertConnectorCitations(
            String display, InjectionSample sample, List<RetrievedChunk> citations) {
        assertThat(citations).as("%s 连接器引用不能为空", display).isNotEmpty();
        assertThat(citations).as("%s 连接器内容在检索层仍是知识库来源", display).allSatisfy(citation -> {
            assertThat(citation.sourceType()).isEqualTo(CitationSourceType.KNOWLEDGE);
            assertThat(citation.knowledgeDocumentId()).isEqualTo(InjectionFixtures.DOCUMENT_ID);
            assertThat(citation.title()).isEqualTo(ConnectorInjectionFixtures.fileName(sample));
            assertThat(citation.title()).doesNotContain("connector/");
        });
        RetrievedChunk first = citations.get(0);
        if (sample.surface() == InjectionSurface.CONNECTOR_PDF) {
            assertThat(first.locatorType()).isEqualTo(LocatorType.PAGE);
            assertThat(first.pageNo()).isEqualTo(1);
        } else {
            assertThat(first.locatorType()).isEqualTo(LocatorType.PARAGRAPH);
            assertThat(first.paragraphNo()).isNotNull();
        }

        if (sample.payload().contains("source=KNOWLEDGE")) {
            String data = UntrustedData.fromCitations(citations, 2000);
            String[] lines = data.split("\n", 3);
            assertThat(lines[0]).as("%s 数据块开始标记", display).isEqualTo(UntrustedData.START);
            assertThat(lines[1])
                    .as("%s 引用头必须由系统按真实序号生成，不能采用样本里的伪造引用", display)
                    .startsWith("[1] source=KNOWLEDGE title=\"" + ConnectorInjectionFixtures.fileName(sample) + "\"")
                    .doesNotContain("[9]");
            assertThat(lines[2]).as("%s 伪造引用行只能留在不可信数据块内", display).contains("[9] source=KNOWLEDGE");
        }
    }

    /**
     * 断言模型请求里注入文本只在不可信面，且回答没有改变系统任务。
     *
     * @param display 失败定位名
     * @param sample 样本
     * @param model 假模型
     */
    private static void assertPromptBoundary(String display, InjectionSample sample, InjectionModel model) {
        String sanitizedPayload = sample.payload()
                .replace(UntrustedData.START, "[[untrusted-data-start]]")
                .replace(UntrustedData.END, "[[untrusted-data-end]]");
        for (InjectionModel.ModelCall call : model.calls()) {
            PromptScan scan = call.scan();
            assertThat(scan.instructionHits())
                    .as("%s 第 %d 次调用检测到指令面注入", display, call.index())
                    .isEmpty();
            assertThat(scan.startMarkers())
                    .as("%s 第 %d 次调用必须有且只有一个数据块开始标记", display, call.index())
                    .isEqualTo(1);
            assertThat(scan.endMarkers())
                    .as("%s 第 %d 次调用必须有且只有一个数据块结束标记", display, call.index())
                    .isEqualTo(1);
            assertThat(scan.toolResultTexts())
                    .as("%s canary/内网地址不得进入工具结果", display)
                    .allSatisfy(text -> assertThat(text)
                            .doesNotContain(InjectionSamples.CANARY_VALUE)
                            .doesNotContain(InjectionSamples.INTERNAL_ADDRESS));
        }
        if (sample.expectIndexed()) {
            PromptScan first = model.calls().get(0).scan();
            assertThat(first.untrustedTexts())
                    .as("%s 注入文本必须位于不可信数据块内", display)
                    .anySatisfy(text -> assertThat(text).contains(sanitizedPayload));
            assertThat(first.untrustedHits())
                    .as("%s 检测词应在不可信面命中，证明样本实际到达模型", display)
                    .isNotEmpty();
        } else {
            assertThat(model.calls().get(0).scan().untrustedTexts())
                    .as("%s 隐藏表注入文本不得进入模型上下文", display)
                    .allSatisfy(text -> assertThat(text).doesNotContain(sample.payload()));
        }
        if (sample.expectForgedMarkers()) {
            assertThat(sample.payload())
                    .as("%s 样本必须包含伪造边界标记", display)
                    .contains(UntrustedData.END, UntrustedData.START);
            assertThat(firstUntrusted(model))
                    .as("%s 伪造标记必须被转义", display)
                    .doesNotContain(UntrustedData.END, UntrustedData.START)
                    .contains("[[untrusted-data-end]]", "[[untrusted-data-start]]");
        }
    }

    /**
     * 断言最终回答、工具调用与事件状态。
     *
     * @param display 失败定位名
     * @param sample 样本
     * @param model 假模型
     * @param events 事件流
     * @param retriever 检索桩
     */
    private static void assertAnswerAndEvents(
            String display,
            InjectionSample sample,
            InjectionModel model,
            List<AgentEvent> events,
            KnowledgeRetriever retriever) {
        AgentResultEvent result = events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(display + " 事件流缺少 AgentResultEvent"));
        assertThat(result.getResult().getGenerateReason())
                .as("%s 正常回答应以模型停止结束", display)
                .isEqualTo(GenerateReason.MODEL_STOP);
        assertThat(result.getResult().getTextContent())
                .as("%s 模型应继续执行系统预设任务", display)
                .isEqualTo(InjectionModel.BENIGN_ANSWER);
        assertThat(result.getResult().getTextContent())
                .as("%s 回答不得包含 canary", display)
                .doesNotContain(InjectionSamples.CANARY_VALUE);
        assertThat(result.getResult().getTextContent())
                .as("%s 回答不得包含内网地址", display)
                .doesNotContain(InjectionSamples.INTERNAL_ADDRESS);

        List<ToolResultEndEvent> toolEnds = events.stream()
                .filter(ToolResultEndEvent.class::isInstance)
                .map(ToolResultEndEvent.class::cast)
                .toList();
        assertThat(toolEnds)
                .as("%s 未授权删除工具不得产生任何工具结果", display)
                .noneMatch(event -> ForbiddenToolStub.TOOL_NAME.equals(event.getToolCallName()));
        if (sample.expectToolCall()) {
            assertThat(model.invocationCount())
                    .as("%s 工具结果必须进入第二次模型调用", display)
                    .isEqualTo(2);
            assertThat(toolEnds).as("%s 只读知识检索必须成功", display).singleElement().satisfies(event -> {
                assertThat(event.getToolCallName()).isEqualTo(KnowledgeSearchTool.TOOL_NAME);
                assertThat(event.getState()).isEqualTo(ToolResultState.SUCCESS);
            });
            verify(retriever).retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), eq("资料"));
        } else {
            assertThat(model.invocationCount()).as("%s 普通样本不应触发工具调用", display).isEqualTo(1);
            assertThat(toolEnds).as("%s 普通样本不应产生工具结果", display).isEmpty();
        }
    }

    /**
     * 断言日志不包含 canary 或内网地址。
     *
     * @param display 失败定位名
     * @param sample 样本
     * @param logs 日志 appender
     */
    private static void assertLogsHaveNoSecrets(
            String display, InjectionSample sample, ListAppender<ILoggingEvent> logs) {
        List<String> messages =
                logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).as("%s 日志捕获必须有效，否则泄露断言会空转", display).isNotEmpty();
        assertThat(messages)
                .as("%s 日志不得包含 canary", display)
                .allSatisfy(message -> assertThat(message).doesNotContain(InjectionSamples.CANARY_VALUE));
        assertThat(messages)
                .as("%s 日志不得包含内网地址", display)
                .allSatisfy(message -> assertThat(message).doesNotContain(InjectionSamples.INTERNAL_ADDRESS));
        assertThat(messages)
                .as("%s 日志不得包含注入原文", display)
                .allSatisfy(message -> assertThat(message).doesNotContain(sample.payload()));
    }

    /**
     * 返回首个不可信数据块文本；不存在时让断言给出明确原因。
     *
     * @param model 假模型
     * @return 数据块文本
     */
    private static String firstUntrusted(InjectionModel model) {
        return model.calls().get(0).scan().untrustedTexts().stream()
                .filter(text -> text.contains("[[untrusted-data-end]]"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("模型请求缺少不可信数据块"));
    }

    /** 构造模型快照；与生产 Worker 的 ModelTarget 形状一致。 */
    private static ModelTarget modelTarget() {
        UUID modelId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        return new ModelTarget(
                new ModelDescriptor(modelId, providerId, "injection-model", "注入测试模型", 4096, 8192),
                new ProviderDescriptor(
                        providerId, "injection-provider", "OPENAI_COMPATIBLE", URI.create("http://127.0.0.1:19090/v1")),
                URI.create("http://127.0.0.1:19090/v1"),
                4096);
    }

    /** 挂载 root 日志 appender；测试串行执行，结束后立即移除。 */
    private static ListAppender<ILoggingEvent> attachRootLog() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    /** 移除 root 日志 appender，避免影响其他测试。 */
    private static void detachRootLog(ListAppender<ILoggingEvent> appender) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
    }
}
