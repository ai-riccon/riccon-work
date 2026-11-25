package com.riccon.api.workflow.history.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行详情中的审批记录快照。
 *
 * <p>只投影审批展示所需字段：不读取 {@code params_ciphertext}/key、完整工具参数、提示词或文档正文；
 * {@code paramsPreview} 由服务层按“当前账号是 assignee 且非发起人”决定是否返回
 * （ADR-0024 §5.3/§8.2）。
 *
 * @param id 审批 ID
 * @param workflowRunId 运行 ID
 * @param workflowId 流程 ID
 * @param nodeKey 审批节点 key
 * @param nodeName 审批节点名
 * @param status 审批状态
 * @param assigneeType 审批人解析方式
 * @param assigneeRole 角色审批码；用户审批为空
 * @param actionKind 解锁动作类型
 * @param targetToolName 目标工具名；可空
 * @param paramsPreview 脱敏参数摘要 JSON；仅详情展示
 * @param planSha256 plan 绑定哈希
 * @param requestedBy 发起人
 * @param decidedBy 审批人；可空
 * @param decidedAt 决定时间；可空
 * @param decisionComment 决定理由；可空
 * @param createdAt 创建时间
 * @param runScope 运行绑定版本的范围
 * @param departmentId 运行绑定版本的部门；可空
 */
public record WorkflowRunApprovalSnapshot(
        UUID id,
        UUID workflowRunId,
        UUID workflowId,
        String nodeKey,
        String nodeName,
        WorkflowApprovalStatus status,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        WorkflowApprovalActionKind actionKind,
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

    /** 校验必填字段。 */
    public WorkflowRunApprovalSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(paramsPreview, "paramsPreview must not be null");
        Objects.requireNonNull(planSha256, "planSha256 must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
    }
}
