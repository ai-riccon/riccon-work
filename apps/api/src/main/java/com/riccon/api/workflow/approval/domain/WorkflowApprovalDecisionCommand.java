package com.riccon.api.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import java.util.Objects;

/**
 * 审批决定的输入。
 *
 * <p>与 Worker T07a 的 {@code WorkflowApprovalDecisionCommand} 保持同一语义：{@code comment} 在
 * {@link WorkflowApprovalDecision#REJECT} 时必填（1–500），批准时可选；空白视为未填写并去除首尾空白，
 * 长度按 Unicode 码点计算。API 与 Worker 的校验口径必须一致，避免同一请求在两侧得到不同结果
 * （ADR-0024 第 5.4 节）。
 *
 * @param decision 决定
 * @param comment 规范化后的理由；没有时为 {@code null}
 */
public record WorkflowApprovalDecisionCommand(WorkflowApprovalDecision decision, String comment) {

    /** 理由最大长度（与 {@code decision_comment} 列宽和审计上限一致）。 */
    public static final int MAX_COMMENT_LENGTH = 500;

    /** 校验决定并按 Worker 口径规范化理由。 */
    public WorkflowApprovalDecisionCommand {
        Objects.requireNonNull(decision, "decision must not be null");
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
