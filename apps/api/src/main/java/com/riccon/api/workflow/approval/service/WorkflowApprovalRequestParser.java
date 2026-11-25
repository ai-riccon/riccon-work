package com.riccon.api.workflow.approval.service;

import com.riccon.api.workflow.approval.domain.WorkflowApprovalDecisionCommand;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 审批决定请求体解析与幂等键校验。
 *
 * <p>请求体使用 {@link JsonNode} 而不是直接反序列化 DTO，确保契约 {@code additionalProperties: false}
 * 生效并返回 400 {@code RICCON-WF-400}，而不是被全局 Jackson 配置静默忽略未知字段。理由规范化与长度
 * 校验在 {@link WorkflowApprovalDecisionCommand} 中完成，保证 API/Worker 口径一致。
 *
 * <p>幂等键规则与 T05 运行创建相同：8–128 个可打印 ASCII 字符。M06 不持久化该键，审批决定的重复请求由
 * 状态机返回 409，而不是重放既有响应（ADR-0024 第 5.4/7.4 节）。
 */
@Component
public class WorkflowApprovalRequestParser {

    /** 决定请求字段白名单，对应契约 {@code WorkflowApprovalDecisionRequest}。 */
    private static final Set<String> DECISION_FIELDS = Set.of("decision", "comment");

    /** Idempotency-Key 最小长度，与 OpenAPI 一致。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度，与 OpenAPI 一致。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /**
     * 解析决定请求。
     *
     * @param body 请求体
     * @return 决定与规范化理由
     * @throws WorkflowApprovalBadRequestException 请求体形状、字段或决定枚举非法
     */
    public WorkflowApprovalDecisionCommand parseDecision(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.REQUEST_INVALID, "request body is required");
        }
        if (!body.isObject()) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.REQUEST_INVALID, "request body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!DECISION_FIELDS.contains(field)) {
                throw new WorkflowApprovalBadRequestException(
                        WorkflowApprovalBadRequestException.Reason.REQUEST_INVALID, "unknown field: " + field);
            }
        }
        JsonNode decisionNode = body.get("decision");
        if (decisionNode == null || !decisionNode.isTextual()) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.DECISION_INVALID, "decision must be APPROVE or REJECT");
        }
        WorkflowApprovalDecision decision;
        try {
            decision = WorkflowApprovalDecision.valueOf(decisionNode.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.DECISION_INVALID, "decision must be APPROVE or REJECT");
        }
        JsonNode commentNode = body.get("comment");
        if (commentNode != null && !commentNode.isNull() && !commentNode.isTextual()) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.REQUEST_INVALID, "comment must be a string");
        }
        String comment = commentNode == null || commentNode.isNull() ? null : commentNode.asString();
        return new WorkflowApprovalDecisionCommand(decision, comment);
    }

    /**
     * 校验必需的幂等键。
     *
     * @param idempotencyKey 请求头
     * @throws WorkflowApprovalBadRequestException 缺失、长度或字符集非法
     */
    public static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH + " and "
                            + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        for (int index = 0; index < idempotencyKey.length(); index++) {
            char value = idempotencyKey.charAt(index);
            if (value < 0x21 || value > 0x7e) {
                throw new WorkflowApprovalBadRequestException(
                        WorkflowApprovalBadRequestException.Reason.IDEMPOTENCY_KEY_INVALID,
                        "Idempotency-Key must contain printable ASCII characters only");
            }
        }
    }
}
