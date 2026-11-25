package com.riccon.worker.workflow.node.domain;

import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节点执行成功结果。
 *
 * <p>{@code output} 序列化为 {@code workflow_node_run.output_json}（≤256 KiB）；{@code result} 只有
 * END 节点填写，由引擎写出运行最终结果。摘要不包含凭据、完整系统提示或文档正文。
 *
 * @param inputSummary 输入摘要
 * @param outputSummary 输出摘要
 * @param output 下游使用的结构
 * @param citations 引用快照
 * @param result 最终结果；非 END 节点为空
 */
public record WorkflowNodeResult(
        String inputSummary,
        String outputSummary,
        Map<String, Object> output,
        List<WorkflowCitation> citations,
        WorkflowResultPayload result) {

    /** 复制集合并校验必填输出。 */
    public WorkflowNodeResult {
        Objects.requireNonNull(output, "output must not be null");
        // 使用 LinkedHashMap 保留稳定字段顺序；输出值允许 JSON null，不能用 Map.copyOf。
        output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
        citations = citations == null ? List.of() : List.copyOf(citations);
        inputSummary = inputSummary == null ? "" : inputSummary;
        outputSummary = outputSummary == null ? "" : outputSummary;
    }

    /**
     * 构造无最终结果的节点结果。
     *
     * @param inputSummary 输入摘要
     * @param outputSummary 输出摘要
     * @param output 输出结构
     * @param citations 引用
     * @return 节点结果
     */
    public static WorkflowNodeResult of(
            String inputSummary, String outputSummary, Map<String, Object> output, List<WorkflowCitation> citations) {
        return new WorkflowNodeResult(inputSummary, outputSummary, output, citations, null);
    }
}
