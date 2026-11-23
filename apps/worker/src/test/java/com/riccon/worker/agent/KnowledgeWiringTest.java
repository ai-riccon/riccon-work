package com.riccon.worker.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.agent.service.ChatAgentService;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.ResearchAgentService;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.mcp.domain.McpServerDescriptor;
import com.riccon.worker.mcp.domain.McpToolDescriptor;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ProviderDescriptor;
import io.agentscope.core.model.Model;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M05 生产 Agent 调用链必须把 run 用户传入知识检索。
 *
 * <p>chat 与 report 构建 Agent 时不得调用无 actor 的兼容入口；knowledge_search 工具由
 * {@code UntrustedDataAndToolTest} 验证。本测试只验证 wiring，不访问模型（事件流为冷流）。
 */
class KnowledgeWiringTest {

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-wiring");

    /** 运行/用户。 */
    private static final UUID RUN = UUID.randomUUID();

    /** 用户。 */
    private static final UUID USER = UUID.randomUUID();

    /** 对话链把 userId/runId 传入 retriever。 */
    @Test
    @DisplayName("ChatAgentService 传递 run 用户")
    void chatAgent_passesActor() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(eq(TENANT), eq(USER), eq(RUN), any(), any())).thenReturn(List.of());
        ChatAgentService service = new ChatAgentService(retriever, new AgentProperties(2, 5, 5, 2000, 60));

        service.prepare(
                TENANT,
                RUN,
                UUID.randomUUID(),
                USER,
                "问题",
                List.of(),
                List.of(new ConversationMessage(UUID.randomUUID(), MessageRole.USER, "问题")),
                mock(Model.class),
                modelTarget());

        verify(retriever).retrieve(eq(TENANT), eq(USER), eq(RUN), eq("问题"), eq(List.of()));
    }

    /** 报告链把 userId/runId 传入 retriever。 */
    @Test
    @DisplayName("ResearchAgentService 传递 run 用户")
    void researchAgent_passesActor() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), any())).thenReturn(List.of());
        ResearchAgentService service = new ResearchAgentService(retriever, new AgentProperties(2, 5, 5, 2000, 60));

        service.prepareToolStage(TENANT, RUN, USER, "调研主题", mcpHandle(), mock(Model.class), modelTarget());

        verify(retriever).retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), eq("调研主题"));
    }

    /** 构造模型快照。 */
    private static ModelTarget modelTarget() {
        UUID modelId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        return new ModelTarget(
                new ModelDescriptor(modelId, providerId, "model-key", "模型", 1000, 8000),
                new ProviderDescriptor(
                        providerId, "provider", "OPENAI_COMPATIBLE", URI.create("http://127.0.0.1:19090/v1")),
                URI.create("http://127.0.0.1:19090/v1"),
                1000);
    }

    /** 构造 MCP 工具快照。 */
    private static McpToolHandle mcpHandle() {
        UUID toolId = UUID.randomUUID();
        UUID serverId = UUID.randomUUID();
        return new McpToolHandle(
                new McpToolDescriptor(
                        toolId, serverId, "lookup", "查询", Map.of("type", "object"), McpToolRisk.READ, true, true),
                new McpServerDescriptor(
                        serverId,
                        "server",
                        "Server",
                        com.riccon.platform.domain.mcp.McpTransport.HTTP,
                        URI.create("http://127.0.0.1:19091"),
                        null,
                        null,
                        true));
    }
}
