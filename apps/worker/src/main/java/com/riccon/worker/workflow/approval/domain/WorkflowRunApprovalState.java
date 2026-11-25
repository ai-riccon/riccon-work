package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.Objects;

/**
 * 审批恢复所需的运行状态快照。
 *
 * @param status 运行状态
 * @param currentNodeKey 当前节点
 * @param approvalWaitMs 累计审批等待毫秒
 */
public record WorkflowRunApprovalState(WorkflowRunStatus status, String currentNodeKey, long approvalWaitMs) {

    /** 校验必填字段。 */
    public WorkflowRunApprovalState {
        Objects.requireNonNull(status, "status must not be null");
        if (approvalWaitMs < 0) {
            throw new IllegalArgumentException("approvalWaitMs must not be negative");
        }
    }
}
