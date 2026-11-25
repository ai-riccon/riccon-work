package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批行只读投影（{@code workflow_approval}）。
 *
 * <p>只包含决定与审计需要的元数据；不加载明文参数（密文仅由 T07b 在批准后按 key id 解密）。
 * {@code paramsPreview} 是已脱敏 JSON，用于审批详情展示；运行详情/日志不得回显完整参数
 * （ADR-0024 §3.3.5/§3.4）。
 *
 * @param id 审批 ID
 * @param workflowRunId 运行 ID
 * @param workflowId 流程 ID
 * @param nodeKey 审批节点 key
 * @param attempt 节点尝试号
 * @param status 审批状态
 * @param assigneeType 审批人类型
 * @param assigneeRoleCode 角色码；用户审批为空
 * @param actionKind 解锁动作
 * @param targetNodeKey 解锁目标节点；可为空（按图取直接后继）
 * @param targetToolId 目标工具 ID；仅 MCP_TOOL
 * @param targetToolName 目标工具名快照；仅 MCP_TOOL
 * @param paramsPreview 脱敏参数摘要 JSON
 * @param paramsSha256 规范化参数摘要；仅 MCP_TOOL
 * @param planSha256 计划绑定哈希
 * @param requestedBy 发起人
 * @param decidedBy 审批人；未决定为空
 * @param decidedAt 决定时间；未决定为空
 * @param decisionComment 决定理由；拒绝时必填
 * @param createdAt 创建时间（等待时长起点）
 */
public record WorkflowApprovalRow(
        UUID id,
        UUID workflowRunId,
        UUID workflowId,
        String nodeKey,
        int attempt,
        WorkflowApprovalStatus status,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRoleCode,
        WorkflowApprovalActionKind actionKind,
        String targetNodeKey,
        UUID targetToolId,
        String targetToolName,
        String paramsPreview,
        String paramsSha256,
        String planSha256,
        UUID requestedBy,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant createdAt) {

    /** 校验核心字段，避免不完整投影进入决定状态机。 */
    public WorkflowApprovalRow {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(planSha256, "planSha256 must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
