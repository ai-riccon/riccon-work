package com.riccon.worker.agentscope.support;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/**
 * 测试用假 ChatModel：按调用次序返回预置响应，全程不访问任何真实模型端点。
 *
 * <p>脚本耗尽时返回错误而不是空流，避免 Agent 意外重试或多轮调用时测试仍然“通过”。
 */
public final class FakeChatModel implements Model {

    private final String modelName;
    private final List<List<ChatResponse>> scriptedResponses;
    private final AtomicInteger invocationCount = new AtomicInteger();

    /**
     * 使用默认模型名创建假模型。
     *
     * @param scriptedResponses 每次模型调用的响应块列表，按调用次序消费
     */
    public FakeChatModel(List<List<ChatResponse>> scriptedResponses) {
        this("fake-chat-model", scriptedResponses);
    }

    /**
     * 创建指定模型名的假模型。
     *
     * @param modelName 模型名，仅用于断言与日志
     * @param scriptedResponses 每次模型调用的响应块列表，按调用次序消费
     */
    public FakeChatModel(String modelName, List<List<ChatResponse>> scriptedResponses) {
        this.modelName = modelName;
        this.scriptedResponses = List.copyOf(scriptedResponses);
    }

    /**
     * 返回预置响应块；调用次数超过脚本时以错误终止，暴露测试假设不成立。
     *
     * @param messages 模型输入消息，本假模型不解析
     * @param tools 可用工具 schema，本假模型不解析
     * @param options 生成参数，本假模型不解析
     * @return 预置响应块序列
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        int index = invocationCount.getAndIncrement();
        if (index >= scriptedResponses.size()) {
            return Flux.error(new IllegalStateException("假模型脚本已耗尽：第 " + (index + 1) + " 次调用"));
        }
        return Flux.fromIterable(scriptedResponses.get(index));
    }

    /**
     * 返回固定模型名，便于断言事件来源。
     *
     * @return 模型名
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 返回模型被调用的次数，用于验证 HITL 恢复后确实重新进入推理。
     *
     * @return 调用次数
     */
    public int invocationCount() {
        return invocationCount.get();
    }
}
