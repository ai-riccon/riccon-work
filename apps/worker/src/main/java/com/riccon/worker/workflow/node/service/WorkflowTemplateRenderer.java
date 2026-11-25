package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 服务端封闭模板渲染（ADR-0024 §4.5）。
 *
 * <p>只允许四类占位符：{@code {{input.<field>}}}、{@code {{node.<nodeId>.summary}}}、
 * {@code {{node.<nodeId>.classificationLabel}}}、{@code {{node.<nodeId>.hitCount}}}。
 * 不解析表达式、函数、正则或字符串拼接；未知占位符、缺失字段、非标量输入一律失败，不静默替换为空串。
 *
 * <p>渲染结果只进入用户消息区；上游输出视为不可信数据，不进入系统指令（TM-78）。
 */
@Component
public class WorkflowTemplateRenderer {

    /** 模板中的输入占位符前缀。 */
    private static final String INPUT_PREFIX = "input.";

    /** 模板中的节点占位符前缀。 */
    private static final String NODE_PREFIX = "node.";

    /** 节点摘要占位符字段。 */
    private static final String FIELD_SUMMARY = "summary";

    /** 分类标签占位符字段。 */
    private static final String FIELD_CLASSIFICATION = "classificationLabel";

    /** 检索命中数占位符字段。 */
    private static final String FIELD_HIT_COUNT = "hitCount";

    /**
     * 渲染模板。
     *
     * @param template 模板文本
     * @param inputs 运行输入
     * @param upstreamOutputs 已成功上游节点输出
     * @return 渲染结果
     * @throws WorkflowNodeFailureException 占位符非法或字段缺失（{@code WF_INVALID_GRAPH}）
     */
    public String render(String template, JsonNode inputs, Map<String, WorkflowNodeOutput> upstreamOutputs) {
        if (template == null) {
            return "";
        }
        StringBuilder rendered = new StringBuilder(template.length());
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            if (open < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            rendered.append(template, cursor, open);
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw invalid();
            }
            String token = template.substring(open + 2, close).strip();
            if (token.isEmpty() || token.contains("{{")) {
                throw invalid();
            }
            rendered.append(resolve(token, inputs, upstreamOutputs));
            cursor = close + 2;
        }
        return rendered.toString();
    }

    /**
     * 校验模板语法并返回引用的运行输入字段。
     *
     * <p>START 节点用本方法在运行开始时检查必填输入；节点输出占位符此时只校验语法，不要求
     * 上游已执行。
     *
     * @param template 模板文本
     * @return 输入字段集合；无模板时为空集
     * @throws WorkflowNodeFailureException 占位符非法（{@code WF_INVALID_GRAPH}）
     */
    public Set<String> referencedInputFields(String template) {
        if (template == null || template.isBlank()) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            if (open < 0) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw invalid();
            }
            String token = template.substring(open + 2, close).strip();
            if (token.isEmpty() || token.contains("{{")) {
                throw invalid();
            }
            if (token.startsWith(INPUT_PREFIX)) {
                fields.add(requireInputField(token.substring(INPUT_PREFIX.length())));
            } else if (token.startsWith(NODE_PREFIX)) {
                requireNodeToken(token);
            } else {
                throw invalid();
            }
            cursor = close + 2;
        }
        return fields;
    }

    /**
     * 校验运行时输入字段是否齐全。
     *
     * @param graph 发布图
     * @param inputs 运行输入
     * @throws WorkflowNodeFailureException 缺少必填输入（{@code WF_INVALID_GRAPH}）
     */
    public void requireInputs(com.riccon.worker.workflow.engine.domain.WorkflowGraph graph, JsonNode inputs) {
        Set<String> required = new LinkedHashSet<>();
        for (WorkflowGraphNode node : graph.nodes()) {
            required.addAll(referencedInputFields(node.config().queryTemplate()));
            required.addAll(referencedInputFields(node.config().promptTemplate()));
        }
        for (String field : required) {
            JsonNode value = inputs == null ? null : inputs.get(field);
            if (value == null || value.isNull()) {
                throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "运行输入缺少必填字段：" + field);
            }
        }
    }

    /**
     * 解析单个占位符。
     *
     * @param token 占位符内容（不含花括号）
     * @param inputs 运行输入
     * @param upstreamOutputs 上游输出
     * @return 替换文本
     */
    private String resolve(String token, JsonNode inputs, Map<String, WorkflowNodeOutput> upstreamOutputs) {
        if (token.startsWith(INPUT_PREFIX)) {
            String field = requireInputField(token.substring(INPUT_PREFIX.length()));
            JsonNode value = inputs == null ? null : inputs.get(field);
            if (value == null || value.isNull() || value.isContainer()) {
                throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "运行输入字段缺失或不是标量：" + field);
            }
            return scalarText(value, field);
        }
        if (token.startsWith(NODE_PREFIX)) {
            NodeToken nodeToken = requireNodeToken(token);
            WorkflowNodeOutput output = upstreamOutputs.get(nodeToken.nodeId());
            if (output == null) {
                throw new WorkflowNodeFailureException(
                        WorkflowErrorCode.WF_INVALID_GRAPH, "引用了未执行的上游节点：" + nodeToken.nodeId());
            }
            return switch (nodeToken.field()) {
                case FIELD_SUMMARY -> output.summary();
                case FIELD_CLASSIFICATION -> requiredText(output.output(), FIELD_CLASSIFICATION, nodeToken.nodeId());
                case FIELD_HIT_COUNT -> requiredNumber(output.output(), FIELD_HIT_COUNT, nodeToken.nodeId());
                default -> throw invalid();
            };
        }
        throw invalid();
    }

    /**
     * 校验输入字段名。
     *
     * @param field 字段名
     * @return 原字段名
     */
    private static String requireInputField(String field) {
        if (!WorkflowGraphNode.ID_PATTERN.matcher(field).matches()) {
            throw invalid();
        }
        return field;
    }

    /**
     * 解析并校验节点占位符。
     *
     * @param token 占位符内容
     * @return 节点与字段
     */
    private static NodeToken requireNodeToken(String token) {
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3
                || !parts[0].equals("node")
                || !WorkflowGraphNode.ID_PATTERN.matcher(parts[1]).matches()
                || !(parts[2].equals(FIELD_SUMMARY)
                        || parts[2].equals(FIELD_CLASSIFICATION)
                        || parts[2].equals(FIELD_HIT_COUNT))) {
            throw invalid();
        }
        return new NodeToken(parts[1], parts[2]);
    }

    /**
     * 读取标量文本。
     *
     * @param value JSON 标量
     * @param field 字段名（仅用于错误）
     * @return 文本
     */
    private static String scalarText(JsonNode value, String field) {
        if (value.isTextual()) {
            return value.asString();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asString();
        }
        throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "运行输入字段不是标量：" + field);
    }

    /**
     * 读取节点输出中的必填文本字段。
     *
     * @param output 节点输出
     * @param field 字段
     * @param nodeId 节点 key
     * @return 文本
     */
    private static String requiredText(JsonNode output, String field, String nodeId) {
        JsonNode value = output.get(field);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_INVALID_GRAPH, "上游节点缺少字段 " + field + "：" + nodeId);
        }
        return value.asString();
    }

    /**
     * 读取节点输出中的必填数字字段。
     *
     * @param output 节点输出
     * @param field 字段
     * @param nodeId 节点 key
     * @return 数字文本
     */
    private static String requiredNumber(JsonNode output, String field, String nodeId) {
        JsonNode value = output.get(field);
        if (value == null || !value.isNumber()) {
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_INVALID_GRAPH, "上游节点缺少字段 " + field + "：" + nodeId);
        }
        return value.asString();
    }

    /**
     * 构造模板非法失败。
     *
     * @return 失败异常
     */
    private static WorkflowNodeFailureException invalid() {
        return new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "流程模板占位符不受支持");
    }

    /**
     * 节点占位符解析结果。
     *
     * @param nodeId 节点 key
     * @param field 字段
     */
    private record NodeToken(String nodeId, String field) {}
}
