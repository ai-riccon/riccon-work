package com.riccon.api.workflow.template.service;

import com.riccon.api.workflow.definition.service.WorkflowDefinitionBadRequestException;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 模板复制请求体解析与白名单校验。
 *
 * <p>与流程草稿解析器同口径：请求体使用 {@link JsonNode} 而不是直接反序列化 DTO，确保契约
 * {@code additionalProperties: false} 生效（全局 Jackson 配置会忽略未知字段）。错误统一映射为
 * 400 {@code RICCON-WF-400}，说明只含字段名与长度范围。
 */
@Component
public class WorkflowTemplateRequestParser {

    /** 复制请求字段白名单，对应契约 {@code WorkflowTemplateCopyRequest}。 */
    private static final Set<String> COPY_FIELDS = Set.of("name");

    /** 草稿名称最大长度，与契约一致。 */
    private static final int MAX_NAME_LENGTH = 200;

    /**
     * 解析复制请求；请求体可省略。
     *
     * @param body 请求体
     * @return 用户指定名称；省略或显式 {@code null} 时返回 {@code null}（服务端使用模板名）
     * @throws WorkflowDefinitionBadRequestException 请求体或字段非法
     */
    public String parseCopy(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return null;
        }
        if (!body.isObject()) {
            throw new WorkflowDefinitionBadRequestException("request body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!COPY_FIELDS.contains(field)) {
                throw new WorkflowDefinitionBadRequestException("unknown field: " + field);
            }
        }
        JsonNode value = body.get("name");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new WorkflowDefinitionBadRequestException("name must be a string");
        }
        String name = value.asString().strip();
        if (name.length() < 1 || name.length() > MAX_NAME_LENGTH) {
            throw new WorkflowDefinitionBadRequestException("name length must be between 1 and " + MAX_NAME_LENGTH);
        }
        return name;
    }
}
