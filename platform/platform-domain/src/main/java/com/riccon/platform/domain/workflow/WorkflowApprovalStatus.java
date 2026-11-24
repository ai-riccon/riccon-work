package com.riccon.platform.domain.workflow;

/**
 * 工作流审批状态。
 *
 * <p>与 {@code workflow_approval.status} 的 CHECK 约束一致。M06 审批不过期、不自动放行；取消运行时
 * 只把 PENDING 审批置 {@link #CANCELLED}，已决定记录保留（ADR-0024 第 5.4 节）。
 */
public enum WorkflowApprovalStatus {
    /** 等待审批人决定。 */
    PENDING,
    /** 已批准。 */
    APPROVED,
    /** 已拒绝；拒绝理由必填。 */
    REJECTED,
    /** 运行取消导致审批作废。 */
    CANCELLED
}
