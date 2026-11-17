package com.riccon.worker.agent.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.ModelRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 对话 Agent 构建。
 *
 * <p>历史消息来自 MySQL，检索资料以独立不可信数据块插入最后一条用户问题之前；
 * 系统指令保持静态。知识库检索工具通过权限上下文显式允许，工具内部自行恢复租户上下文。
 */
@Component
public class ChatAgentService {

    /** 检索服务。 */
    private final KnowledgeRetriever retriever;

    /** 模型解析。 */
    private final ModelRegistry modelRegistry;

    /** Agent 配置。 */
    private final AgentProperties properties;

    /**
     * 创建对话 Agent 服务。
     *
     * @param retriever 检索服务
     * @param modelRegistry 模型解析
     * @param properties Agent 配置
     */
    public ChatAgentService(KnowledgeRetriever retriever, ModelRegistry modelRegistry, AgentProperties properties) {
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 准备一次对话 Agent 会话。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @param query 当前问题
     * @param fileIds 会话指定文件
     * @param history 历史消息（包含当前用户消息）
     * @param resolvedModel 已解析模型
     * @return Agent 会话
     */
    public AgentSession prepare(
            TenantId tenantId,
            UUID runId,
            UUID conversationId,
            UUID userId,
            String query,
            List<UUID> fileIds,
            List<ConversationMessage> history,
            ResolvedModel resolvedModel) {
        if (history.isEmpty()) {
            throw new AgentException(AgentException.INVALID, false, "conversation history is empty");
        }
        List<RetrievedChunk> citations = retriever.retrieve(tenantId, query, fileIds);
        List<Msg> messages = new ArrayList<>();
        int last = history.size() - 1;
        for (int index = 0; index < history.size(); index++) {
            ConversationMessage message = history.get(index);
            if (index == last) {
                if (!citations.isEmpty()) {
                    messages.add(Msg.builder()
                            .name("data")
                            .role(MsgRole.USER)
                            .textContent(UntrustedData.fromCitations(citations, properties.maxChunkChars()))
                            .build());
                }
                messages.add(new UserMessage(message.content()));
            } else if (message.role() == MessageRole.USER) {
                messages.add(new UserMessage(message.content()));
            } else {
                messages.add(Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .textContent(message.content())
                        .build());
            }
        }
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(tenantId, retriever, properties));
        PermissionContextState permissionContext = PermissionContextState.builder()
                .addAllowRule(
                        KnowledgeSearchTool.TOOL_NAME,
                        new PermissionRule(
                                KnowledgeSearchTool.TOOL_NAME, null, PermissionBehavior.ALLOW, "riccon-worker"))
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("riccon-chat")
                .sysPrompt(AgentPrompts.CHAT_SYSTEM)
                .model(modelRegistry.createClient(resolvedModel))
                .toolkit(toolkit)
                .maxIters(properties.maxIterations())
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(permissionContext)
                .build();
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(userId.toString())
                .sessionId(runId.toString())
                .build();
        return new AgentSession(
                agent, agent.streamEvents(messages, runtimeContext), runtimeContext, resolvedModel, citations);
    }
}
