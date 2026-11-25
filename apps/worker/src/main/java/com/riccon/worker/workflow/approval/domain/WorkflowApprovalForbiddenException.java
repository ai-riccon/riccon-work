package com.riccon.worker.workflow.approval.domain;

/**
 * 审批人无权决定该审批。
 *
 * <p>覆盖 ADR-0024 §5.2 的三类拒绝：发起人自批、审批人不在节点指定范围（用户未被指派或没有
 * 指定角色）、账号已停用/不属于本租户。拒绝决定必须写 {@code outcome=denied} 审计，运行时仍
 * 保持等待；API 层 T09 把本异常映射为 {@code 403 RICCON-AUTH-403}。
 */
public class WorkflowApprovalForbiddenException extends RuntimeException {

    /** 拒绝原因。 */
    public enum Reason {
        /** 发起人尝试审批自己的运行。 */
        SELF_APPROVAL,
        /** 审批人不在节点指定的用户/角色范围。 */
        OUT_OF_SCOPE,
        /** 审批人账号已停用或不属于本租户。 */
        APPROVER_INACTIVE
    }

    /** 拒绝原因。 */
    private final Reason reason;

    /**
     * 创建拒绝异常。
     *
     * @param reason 拒绝原因
     */
    public WorkflowApprovalForbiddenException(Reason reason) {
        super(reason == Reason.SELF_APPROVAL ? "requester cannot approve own run" : "approver is not allowed");
        this.reason = reason;
    }

    /**
     * 返回拒绝原因。
     *
     * @return 拒绝原因
     */
    public Reason reason() {
        return reason;
    }
}
