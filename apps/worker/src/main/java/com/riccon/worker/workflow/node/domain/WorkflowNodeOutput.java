package com.riccon.worker.workflow.node.domain;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * 已成功节点的输出视图（供下游模板与条件求值使用）。
 *
 * <p>{@code output} 是节点 {@code output_json} 解析结果；{@code summary} 是运行详情展示摘要。
 * 两者都可能含用户内容，只允许进入下游用户消息区，不进入系统指令（TM-78）。
 *
 * @param output 节点输出 JSON
 * @param summary 输出摘要
 */
public record WorkflowNodeOutput(JsonNode output, String summary) {

    /** 校验输出存在。 */
    public WorkflowNodeOutput {
        Objects.requireNonNull(output, "node output must not be null");
        summary = summary == null ? "" : summary;
    }
}
