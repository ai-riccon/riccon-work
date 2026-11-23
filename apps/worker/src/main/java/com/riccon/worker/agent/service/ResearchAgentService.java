package com.riccon.worker.agent.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.model.domain.ModelTarget;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报告任务的 Agent 构建。
 *
 * <p>M02 只实现 HITL 所需的工具阶段与生成阶段：工具阶段暴露一个白名单 MCP 工具的
 * schema，并强制 {@code ASK} 权限；模型发起调用时 AgentScope 会挂起，由运行处理器持久化审批。
 * 生成阶段只带知识库工具，不再允许外部工具。
 */
@Component
public class ResearchAgentService {

    /** 检索服务。 */
    private final KnowledgeRetriever retriever;

    /** Agent 配置。 */
    private final AgentProperties properties;

    /**
     * 创建报告 Agent 服务。
     *
     * @param retriever 检索服务
     * @param properties Agent 配置
     */
    public ResearchAgentService(KnowledgeRetriever retriever, AgentProperties properties) {
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 准备工具阶段 Agent。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param userId 用户 ID
     * @param topic 调研主题
     * @param handle 已授权 MCP 工具
     * @param model 带降级的 ChatModel
     * @param target 链首模型快照（不含明文）
     * @return Agent 会话
     */
    public AgentSession prepareToolStage(
            TenantId tenantId,
            UUID runId,
            UUID userId,
            String topic,
            McpToolHandle handle,
            Model model,
            ModelTarget target) {
        List<RetrievedChunk> citations = retriever.retrieveKnowledge(tenantId, userId, runId, topic);
        String prompt = "调研主题：" + topic + "\n"
                + (citations.isEmpty()
                        ? "（未检索到知识库资料）"
                        : UntrustedData.fromCitations(citations, properties.maxChunkChars()))
                + "\n请判断是否需要调用工具获取外部数据。";
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(tenantId, userId, runId, retriever, properties));
        toolkit.registerAgentTool(new SchemaOnlyTool(
                handle.tool().name(),
                handle.tool().description() == null ? "外部只读工具" : handle.tool().description(),
                handle.tool().inputSchema()));
        PermissionContextState permissionContext = PermissionContextState.builder()
                .addAllowRule(
                        KnowledgeSearchTool.TOOL_NAME,
                        new PermissionRule(
                                KnowledgeSearchTool.TOOL_NAME, null, PermissionBehavior.ALLOW, "riccon-worker"))
                .addAskRule(
                        handle.tool().name(),
                        new PermissionRule(handle.tool().name(), null, PermissionBehavior.ASK, "riccon-hitl"))
                .build();
        ReActAgent agent = buildAgent(
                "riccon-research-tool", AgentPrompts.RESEARCH_TOOL_SYSTEM, model, toolkit, permissionContext);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(userId.toString())
                .sessionId(runId.toString())
                .build();
        Msg message = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(prompt)
                .build();
        return new AgentSession(
                agent, agent.streamEvents(List.of(message), runtimeContext), runtimeContext, target, citations);
    }

    /**
     * 准备生成阶段 Agent。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param userId 用户 ID
     * @param topic 调研主题
     * @param toolResult 已批准工具结果文本
     * @param model 带降级的 ChatModel
     * @param target 链首模型快照（不含明文）
     * @return Agent 会话
     */
    public AgentSession prepareGenerationStage(
            TenantId tenantId,
            UUID runId,
            UUID userId,
            String topic,
            String toolResult,
            Model model,
            ModelTarget target) {
        List<RetrievedChunk> citations = retriever.retrieveKnowledge(tenantId, userId, runId, topic);
        String prompt = "调研主题：" + topic + "\n"
                + (citations.isEmpty()
                        ? "（未检索到知识库资料）"
                        : UntrustedData.fromCitations(citations, properties.maxChunkChars()))
                + "\n"
                + UntrustedData.fromToolResult("approved-tool", toolResult, properties.maxChunkChars())
                + "\n请基于以上资料撰写报告正文。";
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(tenantId, userId, runId, retriever, properties));
        PermissionContextState permissionContext = PermissionContextState.builder()
                .addAllowRule(
                        KnowledgeSearchTool.TOOL_NAME,
                        new PermissionRule(
                                KnowledgeSearchTool.TOOL_NAME, null, PermissionBehavior.ALLOW, "riccon-worker"))
                .build();
        ReActAgent agent = buildAgent(
                "riccon-research-generation",
                AgentPrompts.RESEARCH_GENERATION_SYSTEM,
                model,
                toolkit,
                permissionContext);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(userId.toString())
                .sessionId(runId.toString())
                .build();
        Msg message = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(prompt)
                .build();
        return new AgentSession(
                agent, agent.streamEvents(List.of(message), runtimeContext), runtimeContext, target, citations);
    }

    /**
     * 构建 ReActAgent。
     *
     * @param name Agent 名
     * @param systemPrompt 静态系统指令
     * @param model 带降级的 ChatModel
     * @param toolkit 工具集
     * @param permissionContext 权限上下文
     * @return Agent
     */
    private ReActAgent buildAgent(
            String name, String systemPrompt, Model model, Toolkit toolkit, PermissionContextState permissionContext) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(systemPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(properties.maxIterations())
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(permissionContext)
                .build();
    }
}
