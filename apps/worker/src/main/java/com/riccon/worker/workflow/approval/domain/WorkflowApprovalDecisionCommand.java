package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批决定的输入。
 *
 * <p>{@code comment} 在 {@link WorkflowApprovalDecision#REJECT} 时必填（1–500），批准时可选；
 * {@code traceId} 用于把审计事件关联到同一次请求。审批人由已认证主体传入，Worker 侧仍会校验
 * 自批、账号状态与 assignee 范围（ADR-0024 §5.2/§5.4）。
 *
 * @param tenantId 租户
 * @param approvalId 审批 ID
 * @param approverUserId 审批人
 * @param decision 决定
 * @param comment 理由；拒绝必填
 * @param traceId 追踪 ID
 */
public record WorkflowApprovalDecisionCommand(
        TenantId tenantId,
        UUID approvalId,
        UUID approverUserId,
        WorkflowApprovalDecision decision,
        String comment,
        String traceId) {

    /** 拒绝理由最大长度（与 {@code decision_comment} 列宽一致）。 */
    public static final int MAX_COMMENT_LENGTH = 500;

    /** 校验必填字段并规范化空白理由。 */
    public WorkflowApprovalDecisionCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(approverUserId, "approverUserId must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        comment = comment == null || comment.isBlank() ? null : comment.strip();
    }

    /**
     * 返回理由长度（按 Unicode 码点计），没有理由时为 0。
     *
     * @return 理由长度
     */
    public int commentLength() {
        return comment == null ? 0 : comment.codePointCount(0, comment.length());
    }
}
