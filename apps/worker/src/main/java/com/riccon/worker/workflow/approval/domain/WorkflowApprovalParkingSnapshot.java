package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批停车所需的运行快照。
 *
 * <p>执行器在创建审批前读取租约持有者、当前节点与版本 ID：租约持有者用于把“运行进入等待”的
 * 条件更新限定在本实例持有的租约上，版本 ID 用于 {@code plan_sha256} 绑定（ADR-0024 §4.7）。
 *
 * @param workflowId 流程 ID
 * @param workflowVersionId 发布版本 ID
 * @param requesterUserId 发起人
 * @param status 运行状态
 * @param currentNodeKey 当前节点
 * @param leaseOwner 当前租约持有者
 */
public record WorkflowApprovalParkingSnapshot(
        UUID workflowId,
        UUID workflowVersionId,
        UUID requesterUserId,
        WorkflowRunStatus status,
        String currentNodeKey,
        String leaseOwner) {

    /** 校验必填字段。 */
    public WorkflowApprovalParkingSnapshot {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId must not be null");
        Objects.requireNonNull(requesterUserId, "requesterUserId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
