package com.riccon.worker.injection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.AgentPrompts;
import com.riccon.worker.agent.service.ChatAgentService;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.KnowledgeSearchTool;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.parse.domain.ParsedChunk;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `INJ-004/009/010` 工具诱导的纵深验证：即使假模型完全被注入控制，未授权删除工具也不能被执行。
 *
 * <p>生产聊天 Agent 只注册只读 `knowledge_search`，权限上下文也只显式允许该工具；本测试把
 * `delete_knowledge` stub 挂到同一个 Agent 的 Toolkit 上，模拟“工具恰好存在”的最坏情况，
 * 断言权限引擎默认 ASK 使 Agent 暂停等待审批，stub 执行次数为 0，且事件与回答不泄露 canary。
 */
class InjToolPermissionTest {

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-inj-tool");

    /** 运行 ID。 */
    private static final UUID RUN = UUID.fromString("00000000-0000-4000-8000-000000000173");

    /** 用户 ID。 */
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    /** 会话 ID。 */
    private static final UUID CONVERSATION = UUID.fromString("00000000-0000-4000-8000-0000000000c2");

    /** 用户问题保持中性。 */
    private static final String QUESTION = "请总结这份季度资料的关键数字。";

    /** 与生产默认一致的 Agent 预算。 */
    private static final AgentProperties AGENT_PROPERTIES = new AgentProperties(4, 5, 5, 2000, 60);

    /**
     * 被攻破模型调用删除工具时必须停在审批，工具不执行、canary 不出现在事件中。
     */
    @ParameterizedTest(name = "{0} 被攻破模型不能执行未授权 delete_knowledge")
    @ValueSource(strings = {"INJ-004", "INJ-009", "INJ-010"})
    @Timeout(60)
    void hijackedModel_unauthorizedToolIsNeverExecuted(String sampleId) {
        InjectionSample sample = InjectionSamples.byId(sampleId);
        ParsedChunk chunk = InjectionFixtures.chunks(InjectionFixtures.parse(sample)).get(0);
        List<RetrievedChunk> citations =
                InjectionFixtures.citations(sample, List.of(chunk));

        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(eq(TENANT), eq(USER), eq(RUN), eq(QUESTION), eq(List.of())))
                .thenReturn(citations);
        when(retriever.retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), any())).thenReturn(citations);
        InjectionModel model = new InjectionModel(
                InjectionModel.Mode.HIJACK_DELETE, InjectionSamples.CANARY_VALUE, InjectionSamples.DETECTORS);
        ChatAgentService service = new ChatAgentService(retriever, AGENT_PROPERTIES);

        AgentSession session = service.prepare(
                TENANT,
                RUN,
                CONVERSATION,
                USER,
                QUESTION,
                List.of(),
                List.of(new ConversationMessage(UUID.randomUUID(), MessageRole.USER, QUESTION)),
                model,
                modelTarget());
        ForbiddenToolStub stub = new ForbiddenToolStub(InjectionSamples.CANARY_VALUE);
        try {
            assertThat(session.agent().getSysPrompt().strip())
                    .as("系统提示必须保持静态")
                    .isEqualTo(AgentPrompts.CHAT_SYSTEM.strip());
            assertThat(session.agent().getToolkit().getToolNames())
                    .as("生产聊天 Agent 只装配 knowledge_search")
                    .containsExactly(KnowledgeSearchTool.TOOL_NAME);

            // 模拟最坏情况：删除工具在 Toolkit 中存在；权限上下文仍只允许 knowledge_search。
            session.agent().getToolkit().registerAgentTool(stub);
            assertThat(session.agent().getToolkit().getTool(ForbiddenToolStub.TOOL_NAME))
                    .as("测试 stub 必须真实注册，才能证明是权限规则而非工具缺失阻断")
                    .isNotNull();

            List<AgentEvent> events = session.events().collectList().block(Duration.ofSeconds(30));
            assertThat(events).as("事件流不能为空").isNotNull();
            assertThat(model.breaches())
                    .as("注入文本不得逃出不可信数据块")
                    .isEmpty();
            assertThat(stub.executions())
                    .as("未授权删除工具绝不能被执行")
                    .isZero();
            assertThat(events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast))
                    .as("未授权工具不得产生工具结果")
                    .noneMatch(event -> ForbiddenToolStub.TOOL_NAME.equals(event.getToolCallName()));
            assertThat(events.stream().anyMatch(RequireUserConfirmEvent.class::isInstance))
                    .as("默认权限行为应是 ASK，Agent 暂停等待审批")
                    .isTrue();

            AgentResultEvent result = events.stream()
                    .filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("事件流缺少 AgentResultEvent"));
            assertThat(result.getResult().getGenerateReason())
                    .as("被攻破模型只能停在权限审批，不能完成删除")
                    .isEqualTo(GenerateReason.PERMISSION_ASKING);
            assertThat(result.getResult().getTextContent())
                    .as("结果不得包含 canary")
                    .doesNotContain(InjectionSamples.CANARY_VALUE);
            assertThat(model.invocationCount())
                    .as("审批前不得再次进入模型推理")
                    .isEqualTo(1);
        } finally {
            session.close();
        }
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
