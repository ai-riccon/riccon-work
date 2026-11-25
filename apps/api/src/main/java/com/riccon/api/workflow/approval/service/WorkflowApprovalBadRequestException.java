package com.riccon.api.workflow.approval.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审批决定请求非法，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>与 Worker T07a 同口径：拒绝理由必填且 1–500 字符，批准理由可选但不超过 500；决定枚举非法、未知字段
 * 或幂等键格式错误同样 400。说明只含字段名与限制值，不回显理由原文（ADR-0024 第 5.4/7.3 节）。
 */
public class WorkflowApprovalBadRequestException extends RicconException {

    /** 非法原因。 */
    public enum Reason {
        /** 拒绝决定缺少理由。 */
        COMMENT_REQUIRED,
        /** 理由超过 500 字符。 */
        COMMENT_TOO_LONG,
        /** 决定枚举缺失或非法。 */
        DECISION_INVALID,
        /** 请求体形状或字段非法。 */
        REQUEST_INVALID,
        /** 幂等键缺失或格式非法。 */
        IDEMPOTENCY_KEY_INVALID
    }

    /** 非法原因。 */
    private final Reason reason;

    /**
     * 创建请求非法异常。
     *
     * @param reason 非法原因
     * @param detail 脱敏说明
     */
    public WorkflowApprovalBadRequestException(Reason reason, String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Invalid workflow approval request", detail);
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
