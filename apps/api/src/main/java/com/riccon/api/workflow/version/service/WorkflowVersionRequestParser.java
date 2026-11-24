package com.riccon.api.workflow.version.service;

import com.riccon.api.workflow.version.domain.WorkflowTransferCommand;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 版本/生命周期请求体解析与白名单校验。
 *
 * <p>移交请求使用 {@link JsonNode} 解析，确保契约 {@code additionalProperties: false} 生效；未知字段、
 * 缺失或非 UUID 的 {@code ownerUserId} 统一返回 400 {@code RICCON-WF-400}（ADR-0024 §7.3）。
 */
@Component
public class WorkflowVersionRequestParser {

    /** 移交请求字段白名单。 */
    private static final Set<String> TRANSFER_FIELDS = Set.of("ownerUserId");

    /**
     * 解析移交请求。
     *
     * @param body 请求体
     * @return 移交命令
     * @throws WorkflowVersionBadRequestException 请求体或字段非法
     */
    public WorkflowTransferCommand parseTransfer(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new WorkflowVersionBadRequestException("request body must be a JSON object");
        }
        for (String name : body.propertyNames()) {
            if (!TRANSFER_FIELDS.contains(name)) {
                throw new WorkflowVersionBadRequestException("unknown field: " + name);
            }
        }
        JsonNode value = body.get("ownerUserId");
        if (value == null || !value.isTextual()) {
            throw new WorkflowVersionBadRequestException("ownerUserId must be a UUID string");
        }
        try {
            return new WorkflowTransferCommand(UUID.fromString(value.asString()));
        } catch (IllegalArgumentException exception) {
            throw new WorkflowVersionBadRequestException("ownerUserId must be a UUID string");
        }
    }
}
