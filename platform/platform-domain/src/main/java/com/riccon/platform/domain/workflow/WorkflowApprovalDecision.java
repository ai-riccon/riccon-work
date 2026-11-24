package com.riccon.platform.domain.workflow;

/**
 * 审批决定。
 *
 * <p>契约 {@code WorkflowApprovalDecision} 与请求 schema 的条件必填规则同源：{@link #REJECT} 时
 * {@code comment} 必填且长度 1–500，缺失或空返回 400 {@code RICCON-WF-400}（ADR-0024 第 5.4 节）。
 */
public enum WorkflowApprovalDecision {
    /** 批准并解锁 targetNodeKey。 */
    APPROVE,
    /** 拒绝；运行失败且不写最终结果。 */
    REJECT
}
