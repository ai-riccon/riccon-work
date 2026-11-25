package com.riccon.api.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批行与运行/流程/版本摘要的组合快照。
 *
 * <p>用于待办、详情与决定响应；只承载已脱敏的 {@code paramsPreview} 原始 JSON，不读取参数密文、凭据、
 * 提示词或文档正文（ADR-0024 第 5.3/8.2 节）。{@code nodeName} 取运行节点快照，缺失时回退为节点 key；
 * {@code runScope}/{@code departmentId} 来自运行绑定的发布版本，用于派生 {@code dataScopeSummary}。
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
 * @param assigneeRole 角色审批码；用户审批时为空
 * @param actionKind 解锁的动作类型
 * @param targetNodeKey 解锁的目标节点 key；可为空
 * @param targetToolName 目标工具名快照；可为空
 * @param paramsPreview 脱敏参数摘要 JSON 原文
 * @param planSha256 plan 绑定哈希
 * @param requestedBy 运行发起人
 * @param decidedBy 审批人；未决定为空
 * @param decidedAt 决定时间；未决定为空
 * @param decisionComment 决定理由；未决定为空
 * @param createdAt 审批创建时间
 * @param runScope 运行绑定版本的发布范围
 * @param departmentId 部门范围 ID；非 DEPARTMENT 为空
 */
public record WorkflowApprovalSnapshot(
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

    /** 校验必填字段；枚举与 ID 不允许为空，可选快照字段按 ADR 语义保留空值。 */
    public WorkflowApprovalSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowName, "workflowName must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(planSha256, "planSha256 must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
    }
}
