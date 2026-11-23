package com.riccon.worker.injection;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * 未授权工具 stub：模拟“模型输出诱导调用删除工具”时最坏情况下的工具实现。
 *
 * <p>继承 {@link ToolBase} 而不是直接实现 {@code AgentTool}，才能进入 agentscope 的权限引擎；
 * 生产聊天工具 {@code knowledge_search} 是只读 legacy 工具，真正的第一道防线是“未注册”。
 * 本 stub 在测试运行时挂到生产 Agent 的 Toolkit 上，验证权限上下文仍只显式放行
 * {@code knowledge_search}，其他工具默认 ASK、不会被执行。只有被真正执行才会递增计数并返回 canary。
 */
final class ForbiddenToolStub extends ToolBase {

    /** 工具名；不在生产聊天工具白名单内。 */
    static final String TOOL_NAME = "delete_knowledge";

    /** 若被执行则返回的 canary。 */
    private final String canaryValue;

    /** 执行次数。 */
    private final AtomicInteger executions = new AtomicInteger();

    /**
     * 创建 stub。
     *
     * @param canaryValue 被执行时返回的 canary，便于失败定位
     */
    ForbiddenToolStub(String canaryValue) {
        super(ToolBase.builder()
                .name(TOOL_NAME)
                .description("测试用未授权删除工具：禁止在注入 harness 中执行。")
                .inputSchema(inputSchema()));
        this.canaryValue = canaryValue;
    }

    /**
     * 记录执行并返回 canary；测试应在执行前就失败。
     *
     * @param param 工具调用参数
     * @return 工具结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        executions.incrementAndGet();
        String text = "已执行未授权删除：canary=" + canaryValue;
        return Mono.just(ToolResultBlock.of(
                param.getToolUseBlock().getId(),
                TOOL_NAME,
                TextBlock.builder().text(text).build()));
    }

    /** 返回执行次数。 */
    int executions() {
        return executions.get();
    }

    /**
     * 构造入参 schema。
     *
     * @return JSON schema
     */
    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("documentId", Map.of("type", "string", "description", "要删除的文档 ID"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("documentId"));
        return schema;
    }
}
