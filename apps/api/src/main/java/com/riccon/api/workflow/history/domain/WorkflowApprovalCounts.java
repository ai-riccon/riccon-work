package com.riccon.api.workflow.history.domain;

/**
 * 运行下审批状态计数。
 *
 * <p>从 {@code workflow_approval} 派生的聚合，不新增运行列：{@code CANCELLED} 审批不计入任何计数；
 * {@code approvalStatus} 聚合规则为任一 {@code PENDING} → PENDING，否则任一 {@code REJECTED} → REJECTED，
 * 否则任一 {@code APPROVED} → APPROVED，否则为空（ADR-0024 §3.3.3、F-012）。
 *
 * @param pending 待审批数
 * @param approved 已批准数
 * @param rejected 已拒绝数
 */
public record WorkflowApprovalCounts(int pending, int approved, int rejected) {

    /** 无审批时的空计数。 */
    public static final WorkflowApprovalCounts EMPTY = new WorkflowApprovalCounts(0, 0, 0);

    /** 校验计数非负。 */
    public WorkflowApprovalCounts {
        if (pending < 0 || approved < 0 || rejected < 0) {
            throw new IllegalArgumentException("approval counts must be non-negative");
        }
    }
}
