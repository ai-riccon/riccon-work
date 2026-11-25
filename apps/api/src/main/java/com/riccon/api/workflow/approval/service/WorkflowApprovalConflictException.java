package com.riccon.api.workflow.approval.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审批决定与当前状态冲突，HTTP 409 {@code RICCON-WF-409}。
 *
 * <p>ADR-0024 第 5.4 节：重复决定或并发决定由条件更新兜底，0 行即冲突；一个成功、一个 409。运行已不处于
 * {@code WAITING_APPROVAL}（例如被取消）同样按冲突处理，决定不得跨运行状态生效。
 */
public class WorkflowApprovalConflictException extends RicconException {

    /** 冲突原因。 */
    public enum Reason {
        /** 审批已经不是 PENDING（重复决定）。 */
        ALREADY_DECIDED,
        /** 并发决定中后到者失败。 */
        CONCURRENT_DECISION,
        /** 运行已不处于等待审批状态。 */
        RUN_STATE_CONFLICT
    }

    /** 冲突原因。 */
    private final Reason reason;

    /**
     * 创建冲突异常。
     *
     * @param reason 冲突原因
     */
    public WorkflowApprovalConflictException(Reason reason) {
        super(
                HttpStatus.CONFLICT,
                "RICCON-WF-409",
                "Workflow approval conflict",
                reason == Reason.RUN_STATE_CONFLICT
                        ? "workflow run is not waiting for approval"
                        : "workflow approval is already decided");
        this.reason = reason;
    }

    /**
     * 返回冲突原因。
     *
     * @return 冲突原因
     */
    public Reason reason() {
        return reason;
    }
}
