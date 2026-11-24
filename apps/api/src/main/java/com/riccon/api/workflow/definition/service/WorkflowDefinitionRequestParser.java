package com.riccon.api.workflow.definition.service;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphFormatException;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 流程草稿请求体解析与白名单校验。
 *
 * <p>请求体使用 {@link JsonNode} 而不是直接反序列化 DTO，以确保契约 {@code additionalProperties: false}
 * 生效：全局 Jackson 配置忽略未知字段，直接绑定会静默丢弃客户端字段。所有错误映射为
 * 400 {@code RICCON-WF-400}，说明只含字段名与范围。
 *
 * <p>保存草稿不做完整发布校验：允许空图或不完整节点参数，结构问题由校验器在 validate/publish 返回；
 * 图 JSON 的字段白名单、类型、枚举与上限由 {@link WorkflowGraphJson} 在保存时拒绝（TM-72）。
 */
@Component
public class WorkflowDefinitionRequestParser {

    /** 创建请求字段白名单。 */
    private static final Set<String> CREATE_FIELDS = Set.of("name", "description", "templateId");

    /** 草稿保存字段白名单。 */
    private static final Set<String> DRAFT_FIELDS = Set.of("version", "name", "description", "graph");

    /** 副本请求字段白名单。 */
    private static final Set<String> DUPLICATE_FIELDS = Set.of("name");

    /** 流程名最大长度，与契约一致。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** 说明最大长度，与契约一致。 */
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    /**
     * 解析创建请求。
     *
     * @param body 请求体
     * @return 创建命令
     * @throws WorkflowDefinitionBadRequestException 请求体或字段非法
     */
    public CreateCommand parseCreate(JsonNode body) {
        JsonNode object = requireObject(body, "request body");
        rejectUnknown(object, CREATE_FIELDS);
        String name = optionalText(object, "name", 1, MAX_NAME_LENGTH);
        String description = optionalText(object, "description", 0, MAX_DESCRIPTION_LENGTH);
        UUID templateId = optionalUuid(object, "templateId");
        if (name == null && templateId == null) {
            throw new WorkflowDefinitionBadRequestException("name or templateId is required");
        }
        return new CreateCommand(name, description, templateId);
    }

    /**
     * 解析草稿保存请求。
     *
     * @param body 请求体
     * @return 保存命令
     * @throws WorkflowDefinitionBadRequestException 请求体或字段非法
     */
    public DraftCommand parseDraft(JsonNode body) {
        JsonNode object = requireObject(body, "request body");
        rejectUnknown(object, DRAFT_FIELDS);
        int version = requireVersion(object);
        String name = optionalText(object, "name", 1, MAX_NAME_LENGTH);
        boolean descriptionPresent = object.get("description") != null;
        String description = descriptionPresent ? optionalText(object, "description", 0, MAX_DESCRIPTION_LENGTH) : null;
        JsonNode graphNode = object.get("graph");
        if (graphNode == null || graphNode.isNull()) {
            throw new WorkflowDefinitionBadRequestException("graph is required");
        }
        WorkflowGraph graph = parseGraph(graphNode);
        return new DraftCommand(version, name, description, descriptionPresent, graph);
    }

    /**
     * 解析另存副本请求；请求体可省略。
     *
     * @param body 请求体
     * @return 副本命令
     * @throws WorkflowDefinitionBadRequestException 请求体或字段非法
     */
    public DuplicateCommand parseDuplicate(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return new DuplicateCommand(null);
        }
        JsonNode object = requireObject(body, "request body");
        rejectUnknown(object, DUPLICATE_FIELDS);
        return new DuplicateCommand(optionalText(object, "name", 1, MAX_NAME_LENGTH));
    }

    /**
     * 解析图 JSON。
     *
     * @param node 图节点
     * @return 图
     */
    private static WorkflowGraph parseGraph(JsonNode node) {
        try {
            return WorkflowGraphJson.parse(node);
        } catch (WorkflowGraphFormatException exception) {
            throw new WorkflowDefinitionBadRequestException(exception.getMessage());
        }
    }

    /**
     * 读取草稿版本。
     *
     * @param object 请求对象
     * @return 版本
     */
    private static int requireVersion(JsonNode object) {
        JsonNode value = object.get("version");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new WorkflowDefinitionBadRequestException("version must be an integer >= 1");
        }
        int version = value.intValue();
        if (version < 1) {
            throw new WorkflowDefinitionBadRequestException("version must be an integer >= 1");
        }
        return version;
    }

    /**
     * 校验请求体是 JSON 对象。
     *
     * @param body 请求体
     * @param context 上下文
     * @return 请求对象
     */
    private static JsonNode requireObject(JsonNode body, String context) {
        if (body == null || !body.isObject()) {
            throw new WorkflowDefinitionBadRequestException(context + " must be a JSON object");
        }
        return body;
    }

    /**
     * 拒绝白名单外字段。
     *
     * @param object 请求对象
     * @param allowed 允许字段
     */
    private static void rejectUnknown(JsonNode object, Set<String> allowed) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new WorkflowDefinitionBadRequestException("unknown field: " + name);
            }
        }
    }

    /**
     * 读取可选文本并去除首尾空白。
     *
     * @param object 请求对象
     * @param field 字段名
     * @param minLength 最小长度
     * @param maxLength 最大长度
     * @return 文本或 {@code null}
     */
    private static String optionalText(JsonNode object, String field, int minLength, int maxLength) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new WorkflowDefinitionBadRequestException(field + " must be a string");
        }
        String text = value.asString().strip();
        if (text.length() < minLength || text.length() > maxLength) {
            throw new WorkflowDefinitionBadRequestException(
                    field + " length must be between " + minLength + " and " + maxLength);
        }
        return text;
    }

    /**
     * 读取可选 UUID。
     *
     * @param object 请求对象
     * @param field 字段名
     * @return UUID 或 {@code null}
     */
    private static UUID optionalUuid(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new WorkflowDefinitionBadRequestException(field + " must be a UUID string");
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowDefinitionBadRequestException(field + " must be a UUID string");
        }
    }

    /**
     * 创建命令。
     *
     * @param name 流程名；为空时使用模板名
     * @param description 说明；为空时使用模板说明
     * @param templateId 模板 ID；为空时创建空白草稿
     */
    public record CreateCommand(String name, String description, UUID templateId) {}

    /**
     * 草稿保存命令。
     *
     * @param version 客户端持有的草稿版本
     * @param name 新名称；为空表示不修改
     * @param description 新说明
     * @param descriptionPresent 是否显式提交说明；显式 null 表示清空
     * @param graph 新图
     */
    public record DraftCommand(
            int version, String name, String description, boolean descriptionPresent, WorkflowGraph graph) {}

    /**
     * 另存副本命令。
     *
     * @param name 副本名称；为空时使用“原名 + 副本”
     */
    public record DuplicateCommand(String name) {}
}
