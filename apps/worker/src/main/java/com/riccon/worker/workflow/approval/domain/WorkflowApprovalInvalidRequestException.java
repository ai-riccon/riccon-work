package com.riccon.worker.workflow.approval.domain;

/**
 * 审批决定请求参数不合法。
 *
 * <p>ADR-0024 §5.4：拒绝理由必填且长度 1–500，缺失/空返回 400 {@code RICCON-WF-400}；批准时
 * 理由可选但不得超过 500。Worker 侧只做业务校验，HTTP 状态由 API T09 映射。
 */
public class WorkflowApprovalInvalidRequestException extends RuntimeException {

    /** 非法原因。 */
    public enum Reason {
        /** 拒绝决定缺少理由。 */
        COMMENT_REQUIRED,
        /** 理由超过 500 字符。 */
        COMMENT_TOO_LONG,
        /** 决定枚举缺失或非法。 */
        DECISION_INVALID
    }

    /** 非法原因。 */
    private final Reason reason;

    /**
     * 创建非法请求异常。
     *
     * @param reason 非法原因
     */
    public WorkflowApprovalInvalidRequestException(Reason reason) {
        super(
                reason == Reason.COMMENT_REQUIRED
                        ? "reject decision requires a comment"
                        : "approval decision is invalid");
        this.reason = reason;
    }

    /**
     * 返回非法原因。
     *
     * @return 非法原因
     */
    public Reason reason() {
        return reason;
    }
}
