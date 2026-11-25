package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批决定的结果快照。
 *
 * <p>批准后运行回到 {@code PENDING} 并由 Worker 从 {@code targetNodeKey} 继续；拒绝后运行
 * {@code FAILED}、审批节点失败、下游跳过且不写最终结果（ADR-0024 §5.4）。结果只含 ID、枚举
 * 与恢复节点，供调用方或测试断言，不包含参数或理由原文。
 *
 * @param approvalId 审批 ID
 * @param workflowRunId 运行 ID
 * @param nodeKey 审批节点 key
 * @param decision 决定
 * @param approvalStatus 审批终态
 * @param runStatus 运行状态
 * @param resumeNodeKey 批准后恢复的节点；拒绝时为空
 */
public record WorkflowApprovalDecisionResult(
        UUID approvalId,
        UUID workflowRunId,
        String nodeKey,
        WorkflowApprovalDecision decision,
        WorkflowApprovalStatus approvalStatus,
        WorkflowRunStatus runStatus,
        String resumeNodeKey) {

    /** 校验终态与恢复节点的一致性。 */
    public WorkflowApprovalDecisionResult {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(approvalStatus, "approvalStatus must not be null");
        Objects.requireNonNull(runStatus, "runStatus must not be null");
        if (approvalStatus == WorkflowApprovalStatus.APPROVED && resumeNodeKey == null) {
            throw new IllegalArgumentException("approved decision requires a resume node");
        }
    }
}
