package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 发布图 JSON 的严格解析（Worker 侧）。
 *
 * <p>契约 {@code WorkflowGraph} 使用 {@code additionalProperties: false}；本类逐层白名单校验，
 * 拒绝未知字段、未知枚举、错误类型与超限；发布快照由 API 生成，运行侧重复校验是 TM-72 的
 * “保存/发布/运行三处拒绝”之一。错误消息只描述字段，不回显提示词或工具参数原文。
 *
 * <p>只解析执行需要的结构；{@code argsTemplate} 与审批配置保留原始 JSON，供 T07a/T07b 使用。
 */
public final class WorkflowGraphJson {

    /** 图根字段白名单。 */
    private static final Set<String> GRAPH_FIELDS = Set.of("schemaVersion", "nodes", "edges");

    /** 节点字段白名单。 */
    private static final Set<String> NODE_FIELDS = Set.of("id", "type", "name", "config", "position");

    /** 节点配置字段白名单；刻意不含 script/code/http/cron/parallel/loop/subflow。 */
    private static final Set<String> CONFIG_FIELDS = Set.of(
            "promptTemplate",
            "queryTemplate",
            "topK",
            "modelId",
            "outputKind",
            "labels",
            "toolId",
            "argsTemplate",
            "assigneeType",
            "assigneeRole",
            "assigneeUserIds",
            "actionKind",
            "targetNodeKey",
            "condition",
            "resultFrom",
            "resultTitle");

    /** 条件字段白名单。 */
    private static final Set<String> CONDITION_FIELDS = Set.of("left", "operator", "right");

    /** 条件左值字段白名单。 */
    private static final Set<String> CONDITION_SOURCE_FIELDS = Set.of("source", "nodeId", "path", "valueType");

    /** 条件右值字段白名单。 */
    private static final Set<String> CONDITION_RIGHT_FIELDS = Set.of("valueType", "value", "values");

    /** 边字段白名单。 */
    private static final Set<String> EDGE_FIELDS = Set.of("id", "source", "target", "branch");

    /** 仅用于解析，不写回。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 工具类禁止实例化。 */
    private WorkflowGraphJson() {}

    /**
     * 解析发布图。
     *
     * @param json JSON 文本
     * @param maxNodes 部署节点上限
     * @return 图视图
     * @throws WorkflowGraphFormatException JSON 非法或结构不符合契约
     */
    public static WorkflowGraph parse(String json, int maxNodes) {
        if (json == null || json.isBlank()) {
            throw new WorkflowGraphFormatException("发布图快照为空");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException exception) {
            throw new WorkflowGraphFormatException("发布图不是合法 JSON", exception);
        }
        return parse(root, maxNodes);
    }

    /**
     * 解析发布图 JSON 节点。
     *
     * @param root JSON 根节点
     * @param maxNodes 部署节点上限
     * @return 图视图
     * @throws WorkflowGraphFormatException 结构不符合契约
     */
    public static WorkflowGraph parse(JsonNode root, int maxNodes) {
        JsonNode object = requireObject(root, "graph");
        rejectUnknown(object, GRAPH_FIELDS, "graph");
        int schemaVersion = requireInt(object, "schemaVersion", "graph");
        if (schemaVersion != WorkflowGraph.SCHEMA_VERSION) {
            throw new WorkflowGraphFormatException("发布图 schemaVersion 不受支持");
        }
        List<WorkflowGraphNode> nodes = parseNodes(requireArray(object, "nodes", "graph"));
        List<WorkflowGraphEdge> edges = parseEdges(requireArray(object, "edges", "graph"));
        // 部署可以收紧节点上限，但不能超过 ADR/契约的 50 节点硬上限。
        int effectiveMax = Math.max(1, Math.min(maxNodes, WorkflowEngineLimits.MAX_GRAPH_NODES));
        return new WorkflowGraph(nodes, edges, effectiveMax);
    }

    /**
     * 解析节点数组。
     *
     * @param array 节点数组
     * @return 节点列表
     */
    private static List<WorkflowGraphNode> parseNodes(JsonNode array) {
        List<WorkflowGraphNode> nodes = new ArrayList<>(array.size());
        for (JsonNode child : array) {
            nodes.add(parseNode(child));
        }
        return nodes;
    }

    /**
     * 解析单个节点。
     *
     * @param node 节点 JSON
     * @return 节点
     */
    private static WorkflowGraphNode parseNode(JsonNode node) {
        JsonNode object = requireObject(node, "node");
        rejectUnknown(object, NODE_FIELDS, "node");
        String id = requireText(object, "id", 1, 64, "node");
        if (!WorkflowGraphNode.ID_PATTERN.matcher(id).matches()) {
            throw new WorkflowGraphFormatException("node.id 格式非法");
        }
        WorkflowNodeType type = requireEnum(object, "type", WorkflowNodeType.class, "node");
        String name = requireText(object, "name", 1, WorkflowGraphNode.MAX_NAME_LENGTH, "node");
        JsonNode configNode = requireField(object, "config", "node");
        WorkflowGraphNodeConfig config = parseConfig(requireObject(configNode, "config"));
        return new WorkflowGraphNode(id, type, name, config);
    }

    /**
     * 解析节点配置。
     *
     * @param object 配置对象
     * @return 配置
     */
    private static WorkflowGraphNodeConfig parseConfig(JsonNode object) {
        rejectUnknown(object, CONFIG_FIELDS, "config");
        JsonNode argsTemplate = object.get("argsTemplate");
        if (argsTemplate == null || argsTemplate.isNull()) {
            argsTemplate = MAPPER.createObjectNode();
        } else if (!argsTemplate.isObject()) {
            throw new WorkflowGraphFormatException("config.argsTemplate 必须是对象");
        } else if (argsTemplate.propertyNames().size() > 16) {
            throw new WorkflowGraphFormatException("config.argsTemplate 顶层键超过 16 个");
        }
        return new WorkflowGraphNodeConfig(
                optionalText(object, "promptTemplate", 8000),
                optionalText(object, "queryTemplate", 2000),
                optionalInt(object, "topK"),
                optionalUuid(object, "modelId"),
                optionalEnum(object, "outputKind", WorkflowNodeOutputKind.class),
                optionalLabelList(object),
                optionalUuid(object, "toolId"),
                argsTemplate,
                optionalEnum(object, "assigneeType", WorkflowApprovalAssigneeType.class),
                optionalText(object, "assigneeRole", 64),
                optionalUuidList(object, "assigneeUserIds", 50),
                optionalEnum(object, "actionKind", WorkflowApprovalActionKind.class),
                optionalText(object, "targetNodeKey", 64),
                optionalCondition(object),
                optionalText(object, "resultFrom", 64),
                optionalText(object, "resultTitle", 200));
    }

    /**
     * 解析分类标签列表。
     *
     * @param object 配置对象
     * @return 标签或 {@code null}
     */
    private static List<String> optionalLabelList(JsonNode object) {
        JsonNode value = optionalField(object, "labels");
        if (value == null) {
            return null;
        }
        if (!value.isArray() || value.size() > 10) {
            throw new WorkflowGraphFormatException("config.labels 必须是最多 10 项的数组");
        }
        List<String> labels = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            labels.add(requireTextValue(item, "labels item", 1, 64));
        }
        return labels;
    }

    /**
     * 解析条件配置。
     *
     * @param object 配置对象
     * @return 条件或 {@code null}
     */
    private static WorkflowGraphCondition optionalCondition(JsonNode object) {
        JsonNode value = optionalField(object, "condition");
        if (value == null) {
            return null;
        }
        JsonNode condition = requireObject(value, "condition");
        rejectUnknown(condition, CONDITION_FIELDS, "condition");
        JsonNode left = requireObject(requireField(condition, "left", "condition"), "condition.left");
        rejectUnknown(left, CONDITION_SOURCE_FIELDS, "condition.left");
        WorkflowGraphConditionSource source = new WorkflowGraphConditionSource(
                requireEnum(left, "source", WorkflowConditionSource.class, "condition.left"),
                optionalText(left, "nodeId", 64),
                requireText(left, "path", 1, 64, "condition.left"),
                requireEnum(left, "valueType", WorkflowConditionValueType.class, "condition.left"));
        JsonNode right = requireObject(requireField(condition, "right", "condition"), "condition.right");
        rejectUnknown(right, CONDITION_RIGHT_FIELDS, "condition.right");
        List<Object> values = optionalScalarList(right, "values", 20);
        Object rightValue = null;
        JsonNode valueNode = right.get("value");
        if (valueNode != null && !valueNode.isNull()) {
            rightValue = scalarValue(valueNode, "condition.right.value");
        }
        WorkflowGraphConditionRight rightSide = new WorkflowGraphConditionRight(
                requireEnum(right, "valueType", WorkflowConditionValueType.class, "condition.right"),
                rightValue,
                values);
        return new WorkflowGraphCondition(
                source,
                requireEnum(condition, "operator", WorkflowConditionOperator.class, "condition"),
                rightSide);
    }

    /**
     * 解析边数组。
     *
     * @param array 边数组
     * @return 边列表
     */
    private static List<WorkflowGraphEdge> parseEdges(JsonNode array) {
        List<WorkflowGraphEdge> edges = new ArrayList<>(array.size());
        for (JsonNode child : array) {
            JsonNode object = requireObject(child, "edge");
            rejectUnknown(object, EDGE_FIELDS, "edge");
            edges.add(new WorkflowGraphEdge(
                    requireText(object, "id", 1, 64, "edge"),
                    requireText(object, "source", 1, 64, "edge"),
                    requireText(object, "target", 1, 64, "edge"),
                    optionalEnum(object, "branch", WorkflowEdgeBranch.class)));
        }
        return edges;
    }

    /**
     * 校验 JSON 节点是对象。
     *
     * @param node JSON 节点
     * @param context 字段上下文
     * @return 对象节点
     */
    private static JsonNode requireObject(JsonNode node, String context) {
        if (node == null || !node.isObject()) {
            throw new WorkflowGraphFormatException(context + " 必须是对象");
        }
        return node;
    }

    /**
     * 读取必填字段。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param context 字段上下文
     * @return 字段值
     */
    private static JsonNode requireField(JsonNode object, String field, String context) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw new WorkflowGraphFormatException(context + "." + field + " 必填");
        }
        return value;
    }

    /**
     * 读取可选字段；缺失或 null 返回 {@code null}。
     *
     * @param object 对象节点
     * @param field 字段名
     * @return 字段值或 {@code null}
     */
    private static JsonNode optionalField(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? null : value;
    }

    /**
     * 校验对象只含白名单字段。
     *
     * @param object 对象节点
     * @param allowed 允许字段
     * @param context 字段上下文
     */
    private static void rejectUnknown(JsonNode object, Set<String> allowed, String context) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new WorkflowGraphFormatException(context + " 含未知字段");
            }
        }
    }

    /**
     * 读取必填数组。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param context 字段上下文
     * @return 数组节点
     */
    private static JsonNode requireArray(JsonNode object, String field, String context) {
        JsonNode value = requireField(object, field, context);
        if (!value.isArray()) {
            throw new WorkflowGraphFormatException(context + "." + field + " 必须是数组");
        }
        return value;
    }

    /**
     * 读取必填文本。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param minLength 最小长度
     * @param maxLength 最大长度
     * @param context 字段上下文
     * @return 文本
     */
    private static String requireText(JsonNode object, String field, int minLength, int maxLength, String context) {
        return requireTextValue(requireField(object, field, context), context + "." + field, minLength, maxLength);
    }

    /**
     * 读取可选文本。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param maxLength 最大长度
     * @return 文本或 {@code null}
     */
    private static String optionalText(JsonNode object, String field, int maxLength) {
        JsonNode value = optionalField(object, field);
        return value == null ? null : requireTextValue(value, field, 1, maxLength);
    }

    /**
     * 校验并返回文本值。
     *
     * @param value JSON 值
     * @param field 字段上下文
     * @param minLength 最小长度
     * @param maxLength 最大长度
     * @return 文本
     */
    private static String requireTextValue(JsonNode value, String field, int minLength, int maxLength) {
        if (!value.isTextual()) {
            throw new WorkflowGraphFormatException(field + " 必须是字符串");
        }
        String text = value.asString();
        if (text.length() < minLength || text.length() > maxLength) {
            throw new WorkflowGraphFormatException(field + " 长度非法");
        }
        return text;
    }

    /**
     * 读取必填整数。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param context 字段上下文
     * @return 整数
     */
    private static int requireInt(JsonNode object, String field, String context) {
        JsonNode value = requireField(object, field, context);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new WorkflowGraphFormatException(context + "." + field + " 必须是整数");
        }
        return value.intValue();
    }

    /**
     * 读取可选整数。
     *
     * @param object 对象节点
     * @param field 字段名
     * @return 整数或 {@code null}
     */
    private static Integer optionalInt(JsonNode object, String field) {
        JsonNode value = optionalField(object, field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new WorkflowGraphFormatException(field + " 必须是整数");
        }
        return value.intValue();
    }

    /**
     * 读取必填枚举。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param type 枚举类型
     * @param context 字段上下文
     * @param <E> 枚举类型
     * @return 枚举值
     */
    private static <E extends Enum<E>> E requireEnum(JsonNode object, String field, Class<E> type, String context) {
        String text = requireText(object, field, 1, 64, context);
        return parseEnum(text, context + "." + field, type);
    }

    /**
     * 读取可选枚举。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param type 枚举类型
     * @param <E> 枚举类型
     * @return 枚举值或 {@code null}
     */
    private static <E extends Enum<E>> E optionalEnum(JsonNode object, String field, Class<E> type) {
        JsonNode value = optionalField(object, field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new WorkflowGraphFormatException(field + " 必须是字符串");
        }
        return parseEnum(value.asString(), field, type);
    }

    /**
     * 解析枚举名。
     *
     * @param text 枚举名
     * @param field 字段上下文
     * @param type 枚举类型
     * @param <E> 枚举类型
     * @return 枚举值
     */
    private static <E extends Enum<E>> E parseEnum(String text, String field, Class<E> type) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(field + " 不是受支持的值");
        }
    }

    /**
     * 读取可选 UUID。
     *
     * @param object 对象节点
     * @param field 字段名
     * @return UUID 或 {@code null}
     */
    private static UUID optionalUuid(JsonNode object, String field) {
        JsonNode value = optionalField(object, field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new WorkflowGraphFormatException(field + " 必须是 UUID 字符串");
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(field + " 必须是 UUID 字符串");
        }
    }

    /**
     * 读取可选 UUID 数组。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param maxItems 最大元素数
     * @return UUID 列表或 {@code null}
     */
    private static List<UUID> optionalUuidList(JsonNode object, String field, int maxItems) {
        JsonNode value = optionalField(object, field);
        if (value == null) {
            return null;
        }
        if (!value.isArray() || value.size() > maxItems) {
            throw new WorkflowGraphFormatException(field + " 必须是最多 " + maxItems + " 项的数组");
        }
        List<UUID> items = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new WorkflowGraphFormatException(field + " 项必须是 UUID 字符串");
            }
            try {
                items.add(UUID.fromString(item.asString()));
            } catch (IllegalArgumentException exception) {
                throw new WorkflowGraphFormatException(field + " 项必须是 UUID 字符串");
            }
        }
        return items;
    }

    /**
     * 读取可选标量数组；元素不允许 null、对象或数组。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param maxItems 最大元素数
     * @return 标量列表或 {@code null}
     */
    private static List<Object> optionalScalarList(JsonNode object, String field, int maxItems) {
        JsonNode value = optionalField(object, field);
        if (value == null) {
            return null;
        }
        if (!value.isArray() || value.size() > maxItems) {
            throw new WorkflowGraphFormatException(field + " 必须是最多 " + maxItems + " 项的数组");
        }
        List<Object> items = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (item.isNull()) {
                throw new WorkflowGraphFormatException(field + " 项必须是标量");
            }
            items.add(scalarValue(item, field + " 项"));
        }
        return items;
    }

    /**
     * 将 JSON 标量转换为 Java 标量。
     *
     * @param value JSON 值
     * @param field 字段上下文
     * @return 字符串、布尔、整数或小数
     */
    private static Object scalarValue(JsonNode value, String field) {
        if (value.isTextual()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            if (value.isIntegralNumber()) {
                return value.longValue();
            }
            return value.decimalValue();
        }
        throw new WorkflowGraphFormatException(field + " 必须是标量");
    }
}
