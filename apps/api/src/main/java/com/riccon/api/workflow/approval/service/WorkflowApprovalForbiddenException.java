package com.riccon.api.workflow.approval.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审批人无权决定该审批，HTTP 403 {@code RICCON-AUTH-403}。
 *
 * <p>覆盖 ADR-0024 第 5.2 节的三类拒绝：发起人自批、审批人不在节点指定的用户/角色范围、账号已停用或
 * 不属于本租户。拒绝必须在同事务写 {@code outcome=denied} 审计；运行与审批保持等待，不产生状态推进。
 */
public class WorkflowApprovalForbiddenException extends RicconException {

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
        super(
                HttpStatus.FORBIDDEN,
                ErrorCodes.AUTH_FORBIDDEN,
                "Forbidden",
                reason == Reason.SELF_APPROVAL
                        ? "requester cannot approve own run"
                        : "approver is not allowed to decide this approval");
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
