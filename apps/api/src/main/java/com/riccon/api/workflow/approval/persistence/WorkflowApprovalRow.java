package com.riccon.api.workflow.approval.persistence;

import com.riccon.api.workflow.approval.domain.WorkflowApprovalSnapshot;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code workflow_approval} 与运行/流程/版本摘要的查询行。
 *
 * <p>只存在于持久化层；服务层通过 {@link #toSnapshot()} 转为领域快照，避免把数据库列名或字节 UUID 暴露
 * 到 controller/service。
 *
 * @param id 审批 ID
 * @param workflowRunId 运行 ID
 * @param workflowId 流程 ID
 * @param workflowName 流程名
 * @param nodeId 审批节点 key
 * @param nodeName 审批节点名
 * @param attempt 节点尝试号
 * @param status 审批状态
 * @param assigneeType 审批人解析方式
 * @param assigneeRole 角色审批码
 * @param actionKind 解锁的动作类型
 * @param targetNodeKey 解锁目标节点 key
 * @param targetToolName 目标工具名
 * @param paramsPreview 脱敏参数摘要 JSON 原文
 * @param planSha256 plan 绑定哈希
 * @param requestedBy 发起人
 * @param decidedBy 审批人
 * @param decidedAt 决定时间
 * @param decisionComment 决定理由
 * @param createdAt 创建时间
 * @param runScope 发布范围
 * @param departmentId 部门范围 ID
 */
public record WorkflowApprovalRow(
        UUID id,
        UUID workflowRunId,
        UUID workflowId,
        String workflowName,
        String nodeId,
        String nodeName,
        int attempt,
        WorkflowApprovalStatus status,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        WorkflowApprovalActionKind actionKind,
        String targetNodeKey,
        String targetToolName,
        String paramsPreview,
        String planSha256,
        UUID requestedBy,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant createdAt,
        WorkflowRunScope runScope,
        UUID departmentId) {

    /**
     * 转为领域快照。
     *
     * @return 审批快照
     */
    public WorkflowApprovalSnapshot toSnapshot() {
        return new WorkflowApprovalSnapshot(
                id,
                workflowRunId,
                workflowId,
                workflowName,
                nodeId,
                nodeName,
                attempt,
                status,
                assigneeType,
                assigneeRole,
                actionKind,
                targetNodeKey,
                targetToolName,
                paramsPreview,
                planSha256,
                requestedBy,
                decidedBy,
                decidedAt,
                decisionComment,
                createdAt,
                runScope,
                departmentId);
    }
}
