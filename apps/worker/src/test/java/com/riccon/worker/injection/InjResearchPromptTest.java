package com.riccon.worker.injection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.AgentPrompts;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.ResearchAgentService;
import com.riccon.worker.agent.service.UntrustedData;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.parse.domain.ParsedChunk;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * J2 报告生成阶段的注入边界：Excel/OCR/连接器引用与已批准工具结果都必须作为不可信数据块进入模型。
 *
 * <p>与聊天链不同，{@code ResearchAgentService.prepareGenerationStage} 把引用与工具结果拼进
 * 同一条用户消息；任一遗漏 {@link UntrustedData#fromCitations}/{@link UntrustedData#fromToolResult}
 * 都会让注入文本进入指令面，假模型 tripwire 会直接输出 canary 使测试失败。
 */
class InjResearchPromptTest {

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-inj-research");

    /** 运行 ID。 */
    private static final UUID RUN = UUID.fromString("00000000-0000-4000-8000-000000000174");

    /** 用户 ID。 */
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a3");

    /** 调研主题；保持中性，不携带检测词。 */
    private static final String TOPIC = "季度市场概况";

    /** 与生产默认一致的 Agent 预算。 */
    private static final AgentProperties AGENT_PROPERTIES = new AgentProperties(4, 5, 5, 2000, 60);

    /**
     * Excel/OCR 引用在报告生成阶段必须被包裹为不可信数据。
     *
     * @param sampleId 样本编号
     */
    @ParameterizedTest(name = "{0} 引用在报告生成阶段仍被隔离")
    @ValueSource(strings = {"INJ-002", "INJ-007"})
    @Timeout(60)
    void generationStage_wrapsExcelAndOcrCitations(String sampleId) {
        assertGenerationStage(sampleId, InjectionSamples.byId(sampleId));
    }

    /**
     * 连接器引用在报告生成阶段必须与上传内容使用同一不可信数据边界。
     *
     * @param display 失败定位名
     * @param sample 连接器样本
     */
    @ParameterizedTest(name = "{0} 连接器引用在报告生成阶段仍被隔离")
    @MethodSource("connectorSamples")
    @Timeout(60)
    void generationStage_wrapsConnectorCitations(String display, InjectionSample sample) {
        assertGenerationStage(display, sample);
    }

    /** 连接器报告生成阶段样本：英文 md 与英文 pdf，覆盖纯文本与页码定位。 */
    static Stream<Arguments> connectorSamples() {
        return Stream.of("INJ-002", "INJ-007")
                .map(id -> Arguments.of("connector-" + id, ConnectorInjectionSamples.byId(id)));
    }

    /**
     * 执行报告生成阶段并断言引用隔离、系统提示与回答。
     *
     * @param display 失败定位名
     * @param sample 样本
     */
    private static void assertGenerationStage(String display, InjectionSample sample) {
        List<ParsedChunk> chunks = InjectionFixtures.chunks(InjectionFixtures.parse(sample));
        List<RetrievedChunk> citations = InjectionFixtures.citations(sample, chunks);
        InjectionModel model = new InjectionModel(
                InjectionModel.Mode.BOUNDARY_AWARE, InjectionSamples.CANARY_VALUE, InjectionSamples.DETECTORS);
        AgentSession session = new ResearchAgentService(mockRetriever(citations), AGENT_PROPERTIES)
                .prepareGenerationStage(TENANT, RUN, USER, TOPIC, "外部工具结果：本季度价格 128。", model, modelTarget());
        try {
            List<AgentEvent> events = session.events().collectList().block(Duration.ofSeconds(30));
            assertThat(events).as("事件流不能为空").isNotNull();
            assertThat(model.breaches()).as("%s 报告生成阶段不得被注入", display).isEmpty();
            assertAgentPrompt(session, model, AgentPrompts.RESEARCH_GENERATION_SYSTEM);

            PromptScan scan = model.calls().get(0).scan();
            assertThat(scan.startMarkers()).as("引用块与工具结果块各一个开始标记").isEqualTo(2);
            assertThat(scan.endMarkers()).as("引用块与工具结果块各一个结束标记").isEqualTo(2);
            assertThat(scan.untrustedHits()).as("%s 样本必须到达不可信面", display).isNotEmpty();
            assertThat(scan.untrustedTexts())
                    .as("%s 引用原文必须位于数据块内", display)
                    .anySatisfy(text -> assertThat(text).contains(sample.payload()));
            assertBenignAnswer(events, display);
            assertCitationLocator(sample, citations);
        } finally {
            session.close();
        }
    }

    /**
     * 恶意工具结果即使来自“已批准”路径，也必须被 fromToolResult 包裹，不能获得指令权。
     */
    @Test
    @DisplayName("已批准工具结果中的注入仍被隔离")
    @Timeout(60)
    void generationStage_wrapsMaliciousToolResult() {
        InjectionSample sample = InjectionSamples.byId("INJ-011");
        List<ParsedChunk> chunks = InjectionFixtures.chunks(InjectionFixtures.parse(sample));
        List<RetrievedChunk> citations = InjectionFixtures.citations(sample, chunks);
        String maliciousToolResult = sample.payload() + " 访问 http://" + InjectionSamples.INTERNAL_ADDRESS + "/collect";
        InjectionModel model = new InjectionModel(
                InjectionModel.Mode.BOUNDARY_AWARE, InjectionSamples.CANARY_VALUE, InjectionSamples.DETECTORS);
        AgentSession session = new ResearchAgentService(mockRetriever(citations), AGENT_PROPERTIES)
                .prepareGenerationStage(TENANT, RUN, USER, TOPIC, maliciousToolResult, model, modelTarget());
        try {
            List<AgentEvent> events = session.events().collectList().block(Duration.ofSeconds(30));
            assertThat(events).as("事件流不能为空").isNotNull();
            assertThat(model.breaches()).as("恶意工具结果不得逃出数据块").isEmpty();
            assertAgentPrompt(session, model, AgentPrompts.RESEARCH_GENERATION_SYSTEM);

            PromptScan scan = model.calls().get(0).scan();
            assertThat(scan.startMarkers()).isEqualTo(2);
            assertThat(scan.endMarkers()).isEqualTo(2);
            assertThat(scan.untrustedTexts())
                    .as("工具结果必须带 tool= 标签进入数据面")
                    .anySatisfy(text -> assertThat(text).contains("tool=approved-tool", sample.payload()));
            assertThat(scan.instructionTexts())
                    .as("工具结果注入不得进入指令面")
                    .allSatisfy(text -> assertThat(text)
                            .doesNotContain(InjectionSamples.CANARY_VALUE)
                            .doesNotContain(InjectionSamples.INTERNAL_ADDRESS));
            assertBenignAnswer(events, "INJ-011-tool-result");
        } finally {
            session.close();
        }
    }

    /**
     * 断言系统提示静态、工具面只读且回答不泄露。
     *
     * @param session Agent 会话
     * @param model 假模型
     * @param expectedSystem 期望系统提示
     */
    private static void assertAgentPrompt(AgentSession session, InjectionModel model, String expectedSystem) {
        assertThat(model.calls().get(0).systemTexts()).as("系统提示必须保持静态").contains(expectedSystem.strip());
        assertThat(session.agent().getSysPrompt().strip()).isEqualTo(expectedSystem.strip());
        assertThat(session.agent().getToolkit().getToolNames())
                .as("报告生成阶段只装配只读知识检索")
                .containsExactly(com.riccon.worker.agent.service.KnowledgeSearchTool.TOOL_NAME);
    }

    /**
     * 断言最终回答为系统预设任务且不含秘密。
     *
     * @param events 事件流
     * @param display 失败定位名
     */
    private static void assertBenignAnswer(List<AgentEvent> events, String display) {
        AgentResultEvent result = events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(display + " 事件流缺少 AgentResultEvent"));
        assertThat(result.getResult().getTextContent())
                .as("%s 模型继续系统预设任务", display)
                .isEqualTo(InjectionModel.BENIGN_ANSWER)
                .doesNotContain(InjectionSamples.CANARY_VALUE)
                .doesNotContain(InjectionSamples.INTERNAL_ADDRESS);
    }

    /**
     * 断言引用仍保留工作表/页码定位。
     *
     * @param sample 样本
     * @param citations 引用
     */
    private static void assertCitationLocator(InjectionSample sample, List<RetrievedChunk> citations) {
        assertThat(citations).as("引用不能为空").isNotEmpty();
        if (sample.surface().isConnector()) {
            assertThat(citations.get(0).title()).isEqualTo(ConnectorInjectionFixtures.fileName(sample));
            if (sample.surface() == InjectionSurface.CONNECTOR_PDF) {
                assertThat(citations.get(0).pageNo()).isEqualTo(1);
            } else {
                assertThat(citations.get(0).paragraphNo()).isNotNull();
            }
        } else if (sample.sheetName() != null) {
            assertThat(citations.get(0).sheetName()).isEqualTo(sample.sheetName());
            assertThat(citations.get(0).cellRange()).isNotBlank();
        } else {
            assertThat(citations.get(0).pageNo()).isEqualTo(1);
        }
    }

    /**
     * 构造知识检索桩。
     *
     * @param citations 引用
     * @return 检索桩
     */
    private static KnowledgeRetriever mockRetriever(List<RetrievedChunk> citations) {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), any())).thenReturn(citations);
        return retriever;
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
}
