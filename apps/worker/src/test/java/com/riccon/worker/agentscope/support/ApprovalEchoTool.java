package com.riccon.worker.agentscope.support;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * HITL 冒烟测试工具：记录真实执行次数并回显输入。
 *
 * <p>权限为 {@code ask} 时，只有在人工确认后才允许执行；执行次数用于区分“挂起”与“已批准执行”。
 */
public final class ApprovalEchoTool extends ToolBase {

    /** 工具名，同时作为权限规则键。 */
    public static final String TOOL_NAME = "approval_echo";

    private final AtomicInteger executionCount = new AtomicInteger();

    /** 创建带最小输入 schema 的回显工具。 */
    public ApprovalEchoTool() {
        super(ToolBase.builder()
                .name(TOOL_NAME)
                .description("测试用工具：人工批准后回显 text 参数")
                .inputSchema(inputSchema()));
    }

    /**
     * 执行回显；每次真实执行都会累加计数，供测试断言是否被权限门禁拦截。
     *
     * @param param 框架传入的工具调用参数
     * @return 包含 {@code approved: <text>} 的工具结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        executionCount.incrementAndGet();
        String text = String.valueOf(param.getInput().getOrDefault("text", ""));
        return Mono.just(ToolResultBlock.text("approved: " + text));
    }

    /**
     * 返回工具真实执行次数。
     *
     * @return 执行次数
     */
    public int executionCount() {
        return executionCount.get();
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", Map.of("type", "string", "description", "要回显的文本"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("text"));
        return schema;
    }
}
