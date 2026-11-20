package com.riccon.worker.agent.domain;

import com.riccon.worker.model.domain.ModelTarget;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * 一次 Agent 会话。
 *
 * <p>持有 Agent、事件流、模型快照与引用；调用方必须在 finally 中关闭，释放工具与状态存储资源。
 * 事件流是冷流，订阅前不会访问模型。模型快照不含凭据明文；真实 key 由降级客户端在每次尝试时
 * 按需解密（ADR-0021 §6.5）。
 *
 * @param agent 已构建的 ReActAgent
 * @param events 事件流
 * @param runtimeContext 运行时上下文
 * @param model 本次调用的链首模型快照
 * @param citations 检索引用
 */
public record AgentSession(
        ReActAgent agent,
        Flux<AgentEvent> events,
        RuntimeContext runtimeContext,
        ModelTarget model,
        List<RetrievedChunk> citations)
        implements AutoCloseable {

    /** 校验必填字段并复制引用列表。 */
    public AgentSession {
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(model, "model must not be null");
        citations = List.copyOf(citations == null ? List.of() : citations);
    }

    /** 关闭 Agent 与其工具资源。 */
    @Override
    public void close() {
        agent.close();
    }
}
