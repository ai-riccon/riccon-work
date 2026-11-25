package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import com.riccon.worker.workflow.node.domain.WorkflowResultFormat;
import com.riccon.worker.workflow.node.domain.WorkflowResultPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * END 节点结果组装。
 *
 * <p>结果 = {@code resultFrom} 上游节点输出 + 聚合引用（引用由引擎单独聚合去重，见
 * ADR-0024 §3.4/§4.4）。输出形态：
 * <ul>
 *   <li>MODEL(TEXT)：{@code format=TEXT}，结果文本即模型文本；
 *   <li>MODEL(CLASSIFICATION)：{@code format=JSON}，只含分类标签；
 *   <li>RETRIEVAL：{@code format=JSON}，只含命中数，不复制文档正文；
 *   <li>其他：{@code format=JSON}，序列化节点输出。
 * </ul>
 * 不做静默截断；5 MiB 上限由引擎在序列化后检查并写 {@code result_status=OVER_LIMIT}。
 */
@Component
public class WorkflowResultAssembler {

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建结果组装器。
     *
     * @param objectMapper JSON 序列化器
     */
    public WorkflowResultAssembler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 组装最终结果。
     *
     * @param graph 发布图
     * @param endNode END 节点
     * @param upstreamOutputs 已成功上游输出
     * @return 结果内容
     * @throws WorkflowNodeFailureException resultFrom 缺失或来源节点未执行（{@code WF_INVALID_GRAPH}）
     */
    public WorkflowResultPayload assemble(
            WorkflowGraph graph, WorkflowGraphNode endNode, Map<String, WorkflowNodeOutput> upstreamOutputs) {
        String resultFrom = endNode.config().resultFrom();
        if (resultFrom == null || resultFrom.isBlank()) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "END 节点缺少结果来源");
        }
        WorkflowGraphNode source = graph
                .findNode(resultFrom)
                .orElseThrow(() -> new WorkflowNodeFailureException(
                        WorkflowErrorCode.WF_INVALID_GRAPH, "END 的结果来源不在发布图中"));
        WorkflowNodeOutput output = upstreamOutputs.get(resultFrom);
        if (output == null) {
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_INVALID_GRAPH, "END 的结果来源节点尚未执行：" + resultFrom);
        }
        JsonNode nodeOutput = output.output();
        return switch (source.type()) {
            case MODEL -> modelResult(source, nodeOutput);
            case RETRIEVAL -> {
                JsonNode hitCount = nodeOutput.get("hitCount");
                if (hitCount == null || !hitCount.isNumber()) {
                    throw new WorkflowNodeFailureException(
                            WorkflowErrorCode.WF_INVALID_GRAPH, "检索节点输出缺少 hitCount");
                }
                yield jsonResult(Map.of("hitCount", hitCount.intValue()));
            }
            case CONDITION -> {
                JsonNode branch = nodeOutput.get("branch");
                if (branch == null || !branch.isTextual()) {
                    throw new WorkflowNodeFailureException(
                            WorkflowErrorCode.WF_INVALID_GRAPH, "条件节点输出缺少 branch");
                }
                yield jsonResult(Map.of("branch", branch.asString()));
            }
            default -> jsonResult(nodeOutputToMap(nodeOutput));
        };
    }

    /**
     * 组装模型节点结果。
     *
     * @param source 来源模型节点
     * @param nodeOutput 模型输出
     * @return 结果内容
     */
    private WorkflowResultPayload modelResult(WorkflowGraphNode source, JsonNode nodeOutput) {
        if (source.config().outputKind() == com.riccon.worker.workflow.engine.domain.WorkflowNodeOutputKind
                .CLASSIFICATION) {
            JsonNode label = nodeOutput.get("classificationLabel");
            if (label == null || !label.isTextual() || label.asString().isBlank()) {
                throw new WorkflowNodeFailureException(
                        WorkflowErrorCode.WF_INVALID_GRAPH, "模型节点输出缺少分类标签");
            }
            return jsonResult(Map.of("classificationLabel", label.asString()));
        }
        JsonNode text = nodeOutput.get("text");
        if (text == null || !text.isTextual()) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "模型节点输出缺少文本");
        }
        return new WorkflowResultPayload(WorkflowResultFormat.TEXT, text.asString());
    }

    /**
     * 构造 JSON 结果。
     *
     * @param value 结果对象
     * @return 结果内容
     */
    private WorkflowResultPayload jsonResult(Map<String, Object> value) {
        return new WorkflowResultPayload(WorkflowResultFormat.JSON, serialize(value));
    }

    /**
     * 把任意节点输出转换为 Map，供 JSON 结果使用。
     *
     * <p>对象输出保留字段顺序；非对象输出不伪装成结果，返回空对象由上游问题暴露。
     *
     * @param nodeOutput 节点输出
     * @return 有序 Map
     */
    private Map<String, Object> nodeOutputToMap(JsonNode nodeOutput) {
        if (nodeOutput != null && nodeOutput.isObject()) {
            return objectMapper.convertValue(nodeOutput, new TypeReference<LinkedHashMap<String, Object>>() {});
        }
        return Map.of();
    }

    /**
     * 序列化结果对象。
     *
     * @param value 结果对象
     * @return JSON 文本
     */
    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_INVALID_GRAPH, "结果无法序列化为 JSON", exception);
        }
    }
}
