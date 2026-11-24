package com.riccon.api.workflow.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工作流图的严格 JSON 编解码与规范化哈希。
 *
 * <p>契约 {@code WorkflowGraph} 使用 {@code additionalProperties: false}，因此本类不依赖全局 Jackson
 * 的“忽略未知字段”配置，而是逐层白名单校验：未知字段、错误类型、非法枚举、超出节点/边上限都以
 * {@link WorkflowGraphFormatException} 拒绝（ADR-0024 第 4.1/9.3 节，TM-72）。
 *
 * <p>规范化用于发布快照与草稿变更判定：先按 record 字段序列化，再递归按键名排序并去掉空白，最后计算
 * SHA-256。发布（T04）必须使用同一 {@link #sha256(WorkflowGraph)}，否则无法与草稿比较。
 */
public final class WorkflowGraphJson {

    /** 图根字段白名单。 */
    private static final Set<String> GRAPH_FIELDS = Set.of("schemaVersion", "nodes", "edges");

    /** 节点字段白名单。 */
    private static final Set<String> NODE_FIELDS = Set.of("id", "type", "name", "config", "position");

    /** 坐标字段白名单。 */
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y");

    /** 边字段白名单。 */
    private static final Set<String> EDGE_FIELDS = Set.of("id", "source", "target", "branch");

    /** 节点配置字段白名单；刻意不包含 script/code/http/cron/parallel/loop/subflow。 */
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

    /**
     * 单例 JSON 编解码。
     *
     * <p>开启 Map 键排序以稳定工具参数模板输出；开启重复键检测，避免同一 JSON 对象出现两个同名字段时
     * 被静默覆盖，造成保存与运行看到不同配置。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** 工具类禁止实例化。 */
    private WorkflowGraphJson() {}

    /**
     * 解析图 JSON 文本。
     *
     * @param json JSON 文本
     * @return 通过形状校验的图
     * @throws WorkflowGraphFormatException JSON 非法或形状不符合契约
     */
    public static WorkflowGraph parse(String json) {
        if (json == null || json.isBlank()) {
            throw new WorkflowGraphFormatException("graph must not be blank");
        }
        try {
            return parse(MAPPER.readTree(json));
        } catch (JacksonException exception) {
            // 不回显原文，避免把提示词或工具参数写入错误响应与日志。
            throw new WorkflowGraphFormatException("graph is not valid JSON");
        }
    }

    /**
     * 解析图 JSON 节点。
     *
     * @param node JSON 节点
     * @return 通过形状校验的图
     * @throws WorkflowGraphFormatException 形状不符合契约
     */
    public static WorkflowGraph parse(JsonNode node) {
        JsonNode object = requireObject(node, "graph");
        rejectUnknown(object, GRAPH_FIELDS, "graph");
        int schemaVersion = requireInt(object, "schemaVersion", "graph");
        List<WorkflowGraphNode> nodes = parseNodes(requireArray(object, "nodes", "graph"));
        List<WorkflowGraphEdge> edges = parseEdges(requireArray(object, "edges", "graph"));
        try {
            return new WorkflowGraph(schemaVersion, nodes, edges);
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(exception.getMessage());
        }
    }

    /**
     * 将图序列化为规范化 JSON（键排序、无空白）。
     *
     * @param graph 图
     * @return 规范化 JSON
     */
    public static String toJson(WorkflowGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        try {
            return MAPPER.writeValueAsString(canonicalize(MAPPER.valueToTree(graph)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize workflow graph", exception);
        }
    }

    /**
     * 计算规范化图的 SHA-256（小写 hex）。
     *
     * @param graph 图
     * @return 64 位小写十六进制哈希
     */
    public static String sha256(WorkflowGraph graph) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(toJson(graph).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * 解析节点数组。
     *
     * @param array 节点数组
     * @return 节点列表
     */
    private static List<WorkflowGraphNode> parseNodes(JsonNode array) {
        if (array.size() > WorkflowGraph.MAX_NODES) {
            throw new WorkflowGraphFormatException("nodes must not exceed " + WorkflowGraph.MAX_NODES);
        }
        List<WorkflowGraphNode> nodes = new ArrayList<>();
        for (JsonNode child : array) {
            nodes.add(parseNode(child));
        }
        return nodes;
    }

    /**
     * 解析节点数组。
     *
     * @param array 边数组
     * @return 边列表
     */
    private static List<WorkflowGraphEdge> parseEdges(JsonNode array) {
        if (array.size() > WorkflowGraph.MAX_EDGES) {
            throw new WorkflowGraphFormatException("edges must not exceed " + WorkflowGraph.MAX_EDGES);
        }
        List<WorkflowGraphEdge> edges = new ArrayList<>();
        for (JsonNode child : array) {
            edges.add(parseEdge(child));
        }
        return edges;
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
        WorkflowNodeType type = requireEnum(object, "type", WorkflowNodeType.class, "node");
        String name = requireText(object, "name", 1, WorkflowGraphNode.MAX_NAME_LENGTH, "node");
        WorkflowGraphNodeConfig config = parseConfig(requireObject(requireField(object, "config", "node"), "config"));
        WorkflowGraphPosition position = parsePosition(optionalField(object, "position"));
        try {
            return new WorkflowGraphNode(id, type, name, config, position);
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(exception.getMessage());
        }
    }

    /**
     * 解析画布坐标。
     *
     * @param node 坐标 JSON；为空表示没有坐标
     * @return 坐标或 {@code null}
     */
    private static WorkflowGraphPosition parsePosition(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode object = requireObject(node, "position");
        rejectUnknown(object, POSITION_FIELDS, "position");
        return new WorkflowGraphPosition(
                requireNumber(object, "x", "position"), requireNumber(object, "y", "position"));
    }

    /**
     * 解析节点配置。
     *
     * @param object 配置 JSON
     * @return 节点配置
     */
    private static WorkflowGraphNodeConfig parseConfig(JsonNode object) {
        rejectUnknown(object, CONFIG_FIELDS, "config");
        try {
            return new WorkflowGraphNodeConfig(
                    optionalText(object, "promptTemplate", WorkflowGraphNodeConfig.MAX_PROMPT_TEMPLATE_LENGTH),
                    optionalText(object, "queryTemplate", WorkflowGraphNodeConfig.MAX_QUERY_TEMPLATE_LENGTH),
                    optionalInt(object, "topK"),
                    optionalUuid(object, "modelId"),
                    optionalEnum(object, "outputKind", WorkflowGraphOutputKind.class),
                    optionalTextList(object, "labels", WorkflowGraphNodeConfig.MAX_LABELS, 1, 64),
                    optionalUuid(object, "toolId"),
                    optionalArgsTemplate(object),
                    optionalEnum(object, "assigneeType", WorkflowApprovalAssigneeType.class),
                    optionalText(object, "assigneeRole", 64),
                    optionalUuidList(object, "assigneeUserIds", WorkflowGraphNodeConfig.MAX_ASSIGNEE_USER_IDS),
                    optionalEnum(object, "actionKind", WorkflowApprovalActionKind.class),
                    optionalText(object, "targetNodeKey", 64),
                    optionalCondition(object),
                    optionalText(object, "resultFrom", 64),
                    optionalText(object, "resultTitle", 200));
        } catch (WorkflowGraphFormatException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(exception.getMessage());
        }
    }

    /**
     * 解析工具参数模板；值保持任意 JSON，但顶层最多 16 个键。
     *
     * @param object 配置 JSON
     * @return 参数模板或 {@code null}
     */
    private static Map<String, Object> optionalArgsTemplate(JsonNode object) {
        JsonNode value = optionalField(object, "argsTemplate");
        if (value == null) {
            return null;
        }
        if (!value.isObject()) {
            throw new WorkflowGraphFormatException("argsTemplate must be an object");
        }
        if (value.propertyNames().size() > WorkflowGraphNodeConfig.MAX_ARGS_TEMPLATE_KEYS) {
            throw new WorkflowGraphFormatException(
                    "argsTemplate must not exceed " + WorkflowGraphNodeConfig.MAX_ARGS_TEMPLATE_KEYS + " keys");
        }
        return MAPPER.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * 解析条件配置。
     *
     * @param object 配置 JSON
     * @return 条件或 {@code null}
     */
    private static WorkflowGraphCondition optionalCondition(JsonNode object) {
        JsonNode value = optionalField(object, "condition");
        if (value == null) {
            return null;
        }
        JsonNode condition = requireObject(value, "condition");
        rejectUnknown(condition, CONDITION_FIELDS, "condition");
        return new WorkflowGraphCondition(
                parseConditionSource(requireObject(requireField(condition, "left", "condition"), "condition.left")),
                requireEnum(condition, "operator", WorkflowConditionOperator.class, "condition"),
                parseConditionRight(requireObject(requireField(condition, "right", "condition"), "condition.right")));
    }

    /**
     * 解析条件左值。
     *
     * @param object 左值 JSON
     * @return 左值
     */
    private static WorkflowGraphConditionSource parseConditionSource(JsonNode object) {
        rejectUnknown(object, CONDITION_SOURCE_FIELDS, "condition.left");
        return new WorkflowGraphConditionSource(
                requireEnum(object, "source", WorkflowConditionSource.class, "condition.left"),
                optionalText(object, "nodeId", 64),
                requireText(object, "path", 1, WorkflowGraphConditionSource.MAX_PATH_LENGTH, "condition.left"),
                requireEnum(object, "valueType", WorkflowConditionValueType.class, "condition.left"));
    }

    /**
     * 解析条件右值。
     *
     * @param object 右值 JSON
     * @return 右值
     */
    private static WorkflowGraphConditionRight parseConditionRight(JsonNode object) {
        rejectUnknown(object, CONDITION_RIGHT_FIELDS, "condition.right");
        List<Object> values = optionalScalarList(object, "values", WorkflowGraphConditionRight.MAX_VALUES);
        Object value = null;
        JsonNode valueNode = object.get("value");
        if (valueNode != null && !valueNode.isNull()) {
            value = scalarValue(valueNode, "condition.right.value");
        }
        try {
            return new WorkflowGraphConditionRight(
                    requireEnum(object, "valueType", WorkflowConditionValueType.class, "condition.right"),
                    value,
                    values);
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(exception.getMessage());
        }
    }

    /**
     * 解析单个边。
     *
     * @param node 边 JSON
     * @return 边
     */
    private static WorkflowGraphEdge parseEdge(JsonNode node) {
        JsonNode object = requireObject(node, "edge");
        rejectUnknown(object, EDGE_FIELDS, "edge");
        String id = requireText(object, "id", 1, 64, "edge");
        String source = requireText(object, "source", 1, 64, "edge");
        String target = requireText(object, "target", 1, 64, "edge");
        WorkflowEdgeBranch branch = optionalEnum(object, "branch", WorkflowEdgeBranch.class);
        try {
            return new WorkflowGraphEdge(id, source, target, branch);
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(exception.getMessage());
        }
    }

    /**
     * 校验 JSON 节点是对象。
     *
     * @param node JSON 节点
     * @param context 字段上下文，用于错误说明
     * @return 对象节点
     */
    private static JsonNode requireObject(JsonNode node, String context) {
        if (node == null || !node.isObject()) {
            throw new WorkflowGraphFormatException(context + " must be an object");
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
            throw new WorkflowGraphFormatException(context + "." + field + " is required");
        }
        return value;
    }

    /**
     * 读取可选字段；缺失或显式 null 都返回 {@code null}。
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
     * 校验对象只包含白名单字段。
     *
     * @param object 对象节点
     * @param allowed 允许字段
     * @param context 字段上下文
     */
    private static void rejectUnknown(JsonNode object, Set<String> allowed, String context) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new WorkflowGraphFormatException(context + " contains unknown field: " + name);
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
            throw new WorkflowGraphFormatException(context + "." + field + " must be an array");
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
        JsonNode value = requireField(object, field, context);
        String text = requireTextValue(value, context + "." + field, minLength, maxLength);
        return text;
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
            throw new WorkflowGraphFormatException(field + " must be a string");
        }
        String text = value.asString();
        if (text.length() < minLength || text.length() > maxLength) {
            throw new WorkflowGraphFormatException(
                    field + " length must be between " + minLength + " and " + maxLength);
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
            throw new WorkflowGraphFormatException(context + "." + field + " must be an integer");
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
            throw new WorkflowGraphFormatException(field + " must be an integer");
        }
        return value.intValue();
    }

    /**
     * 读取必填数字。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param context 字段上下文
     * @return 数字
     */
    private static double requireNumber(JsonNode object, String field, String context) {
        JsonNode value = requireField(object, field, context);
        if (!value.isNumber()) {
            throw new WorkflowGraphFormatException(context + "." + field + " must be a number");
        }
        return value.doubleValue();
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
            throw new WorkflowGraphFormatException(field + " must be a string");
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
            throw new WorkflowGraphFormatException(field + " is not a supported value");
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
            throw new WorkflowGraphFormatException(field + " must be a UUID string");
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowGraphFormatException(field + " must be a UUID string");
        }
    }

    /**
     * 读取可选文本数组。
     *
     * @param object 对象节点
     * @param field 字段名
     * @param maxItems 最大元素数
     * @param minItemLength 单元素最小长度
     * @param maxItemLength 单元素最大长度
     * @return 文本列表或 {@code null}
     */
    private static List<String> optionalTextList(
            JsonNode object, String field, int maxItems, int minItemLength, int maxItemLength) {
        JsonNode value = optionalField(object, field);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw new WorkflowGraphFormatException(field + " must be an array");
        }
        if (value.size() > maxItems) {
            throw new WorkflowGraphFormatException(field + " must not exceed " + maxItems + " items");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            items.add(requireTextValue(item, field + " item", minItemLength, maxItemLength));
        }
        return items;
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
        if (!value.isArray()) {
            throw new WorkflowGraphFormatException(field + " must be an array");
        }
        if (value.size() > maxItems) {
            throw new WorkflowGraphFormatException(field + " must not exceed " + maxItems + " items");
        }
        List<UUID> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new WorkflowGraphFormatException(field + " items must be UUID strings");
            }
            try {
                items.add(UUID.fromString(item.asString()));
            } catch (IllegalArgumentException exception) {
                throw new WorkflowGraphFormatException(field + " items must be UUID strings");
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
        if (!value.isArray()) {
            throw new WorkflowGraphFormatException(field + " must be an array");
        }
        if (value.size() > maxItems) {
            throw new WorkflowGraphFormatException(field + " must not exceed " + maxItems + " items");
        }
        List<Object> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isNull()) {
                throw new WorkflowGraphFormatException(field + " items must be scalar values");
            }
            items.add(scalarValue(item, field + " item"));
        }
        return items;
    }

    /**
     * 将 JSON 标量转换为 Java 标量。
     *
     * @param value JSON 值
     * @param field 字段上下文
     * @return 字符串、布尔或数字
     */
    private static Object scalarValue(JsonNode value, String field) {
        if (value.isTextual()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            if (value.isIntegralNumber() && value.canConvertToLong()) {
                return value.longValue();
            }
            return value.doubleValue();
        }
        throw new WorkflowGraphFormatException(field + " must be a scalar value");
    }

    /**
     * 递归按键名排序，数组保持原顺序。
     *
     * @param node JSON 节点
     * @return 规范化节点
     */
    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                array.add(canonicalize(item));
            }
            return array;
        }
        return node;
    }
}
