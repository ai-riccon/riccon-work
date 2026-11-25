package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批计划绑定哈希的输入。
 *
 * <p>ADR-0024 §4.7 要求 {@code plan_sha256} 绑定“版本 + 节点配置 + 上游输出摘要”，执行前重算，
 * 漂移即 {@code WF_PLAN_DRIFT}。本记录把参与哈希的字段显式列出：版本 ID、审批节点、解锁动作、
 * 审批人配置、目标节点/工具快照与上游成功节点的输出摘要；参数明文不进入计划哈希（由
 * {@code params_sha256} 单独绑定）。
 *
 * @param workflowId 流程 ID
 * @param workflowVersionId 版本 ID
 * @param nodeKey 审批节点 key
 * @param actionKind 解锁动作
 * @param assigneeType 审批人类型
 * @param assigneeRoleCode 角色码；用户审批为空
 * @param assigneeUserIds 用户审批人（已排序去重）
 * @param targetNodeKey 解锁目标节点；可为空
 * @param targetToolId 目标工具 ID；可为空
 * @param targetToolName 目标工具名快照；可为空
 * @param upstreamSummaries 上游成功节点 key 到输出摘要
 */
public record WorkflowApprovalPlanInput(
        UUID workflowId,
        UUID workflowVersionId,
        String nodeKey,
        WorkflowApprovalActionKind actionKind,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRoleCode,
        List<UUID> assigneeUserIds,
        String targetNodeKey,
        UUID targetToolId,
        String targetToolName,
        Map<String, String> upstreamSummaries) {

    /** 校验必填字段并复制集合。 */
    public WorkflowApprovalPlanInput {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        assigneeUserIds = assigneeUserIds == null ? List.of() : List.copyOf(assigneeUserIds);
        upstreamSummaries = upstreamSummaries == null ? Map.of() : Map.copyOf(upstreamSummaries);
    }
}
