package com.riccon.api.workflow.validation;

import com.riccon.platform.domain.workflow.WorkflowValidationCode;
import java.util.Objects;

/**
 * 单条图校验问题（契约 {@code WorkflowValidationIssue}）。
 *
 * <p>{@code code} 是稳定枚举，UI 用它映射中文文案；{@code nodeId}/{@code field} 用于画布定位与表单聚焦。
 * {@code message} 只描述规则与修正方向，不回显提示词、工具参数或文档正文（ADR-0024 §8.2）。
 *
 * @param code 稳定问题码
 * @param nodeId 问题节点 ID；图级问题为空
 * @param field 修正字段路径，例如 {@code config.resultFrom}；无法定位时为空
 * @param message 脱敏说明，最多 500 字符
 */
public record WorkflowValidationIssue(WorkflowValidationCode code, String nodeId, String field, String message) {

    /** 问题说明最大长度，与契约 {@code WorkflowValidationIssue.message} 一致。 */
    public static final int MAX_MESSAGE_LENGTH = 500;

    /** 校验必填字段与说明长度。 */
    public WorkflowValidationIssue {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message length must be between 1 and " + MAX_MESSAGE_LENGTH);
        }
        if (nodeId != null && nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank when present");
        }
        if (field != null && field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank when present");
        }
    }

    /**
     * 构造问题。
     *
     * @param code 稳定问题码
     * @param nodeId 问题节点 ID；可空
     * @param field 修正字段路径；可空
     * @param message 脱敏说明
     * @return 问题
     */
    public static WorkflowValidationIssue of(WorkflowValidationCode code, String nodeId, String field, String message) {
        return new WorkflowValidationIssue(code, nodeId, field, message);
    }
}
