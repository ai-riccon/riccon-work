package com.riccon.api.workflow.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 运行详情中的审批记录（契约 {@code WorkflowApproval}）。
 *
 * <p>只返回审批状态、动作、范围摘要、审批人与决定；{@code paramsPreview} 仅在当前账号是该审批的
 * assignee 且非运行发起人时返回，租户管理员不因身份获得待审批参数摘要（ADR-0024 §5.3）。
 *
 * @param id 审批 ID
 * @param workflowRunId 运行 ID
 * @param workflowId 流程 ID
 * @param workflowName 流程名
 * @param nodeId 审批节点 key
 * @param nodeName 审批节点名
 * @param status 审批状态
 * @param assigneeType 审批人解析方式
 * @param assigneeRole 角色审批码；用户审批为空
 * @param actionKind 解锁动作类型
 * @param dataScopeSummary 数据范围摘要
 * @param targetToolName 目标工具名
 * @param paramsPreview 脱敏参数摘要；仅 assignee 返回
 * @param planSha256 plan 绑定哈希
 * @param requestedBy 发起人
 * @param decidedBy 审批人
 * @param decidedAt 决定时间
 * @param decisionComment 决定理由
 * @param createdAt 创建时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunApprovalResponse(
        UUID id,
        UUID workflowRunId,
        UUID workflowId,
        String workflowName,
        String nodeId,
        String nodeName,
        WorkflowApprovalStatus status,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        WorkflowApprovalActionKind actionKind,
        WorkflowDataScopeSummaryResponse dataScopeSummary,
        String targetToolName,
        JsonNode paramsPreview,
        String planSha256,
        UUID requestedBy,
        UUID decidedBy,
        OffsetDateTime decidedAt,
        String decisionComment,
        OffsetDateTime createdAt) {}
