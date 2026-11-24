package com.riccon.api.workflow.publish.service;

import com.riccon.api.workflow.publish.domain.WorkflowPublishCommand;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 发布请求体解析与白名单校验。
 *
 * <p>使用 {@link JsonNode} 而不是直接绑定 DTO，确保契约 {@code additionalProperties: false} 生效：
 * 未知字段、错误类型、非法枚举统一返回 400 {@code RICCON-WF-400}。{@code runScope} 必填，
 * {@code departmentId} 只在 {@code DEPARTMENT} 范围允许出现；角色/负责人条件由
 * {@link WorkflowPublishService} 校验（ADR-0024 §6.2）。
 */
@Component
public class WorkflowPublishRequestParser {

    /** 发布请求字段白名单。 */
    private static final Set<String> PUBLISH_FIELDS = Set.of("runScope", "departmentId");

    /**
     * 解析发布请求。
     *
     * @param body 请求体
     * @return 发布命令
     * @throws WorkflowPublishBadRequestException 请求体或字段非法
     */
    public WorkflowPublishCommand parsePublish(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new WorkflowPublishBadRequestException("request body must be a JSON object");
        }
        for (String name : body.propertyNames()) {
            if (!PUBLISH_FIELDS.contains(name)) {
                throw new WorkflowPublishBadRequestException("unknown field: " + name);
            }
        }
        WorkflowRunScope runScope = requireRunScope(body);
        UUID departmentId = optionalUuid(body, "departmentId");
        if (runScope == WorkflowRunScope.DEPARTMENT && departmentId == null) {
            throw new WorkflowPublishBadRequestException("departmentId is required for DEPARTMENT scope");
        }
        if (runScope != WorkflowRunScope.DEPARTMENT && departmentId != null) {
            throw new WorkflowPublishBadRequestException("departmentId is only allowed for DEPARTMENT scope");
        }
        return new WorkflowPublishCommand(runScope, departmentId);
    }

    /**
     * 读取必填运行范围。
     *
     * @param object 请求对象
     * @return 运行范围
     */
    private static WorkflowRunScope requireRunScope(JsonNode object) {
        JsonNode value = object.get("runScope");
        if (value == null || !value.isTextual()) {
            throw new WorkflowPublishBadRequestException("runScope must be ONLY_SELF, DEPARTMENT or TENANT");
        }
        try {
            return WorkflowRunScope.valueOf(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowPublishBadRequestException("runScope must be ONLY_SELF, DEPARTMENT or TENANT");
        }
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
            throw new WorkflowPublishBadRequestException(field + " must be a UUID string");
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowPublishBadRequestException(field + " must be a UUID string");
        }
    }
}
