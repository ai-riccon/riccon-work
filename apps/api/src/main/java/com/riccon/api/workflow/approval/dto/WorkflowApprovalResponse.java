package com.riccon.api.workflow.approval.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 审批响应（契约 {@code WorkflowApproval} 与 {@code WorkflowApprovalDetail}）。
 *
 * <p>待办列表与详情共用一个结构：{@code paramsPreview} 只在审批详情（assignee）返回；列表省略该字段，
 * 避免把参数摘要带进批量响应（ADR-0024 第 5.3 节）。{@code selfApprovalBlocked} 与
 * {@code documentReferenceCount} 是详情扩展字段，列表为空并因 {@link JsonInclude} 省略。
 *
 * <p>时间统一转换为 {@code Asia/Shanghai}（UTC+08:00）偏移的 ISO 8601；数据库仍存 UTC。
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
 * @param assigneeUserIds 指定用户审批人；角色审批为空列表，列表响应省略
 * @param actionKind 解锁的动作类型
 * @param dataScopeSummary 数据范围摘要
 * @param targetToolName 目标工具名
 * @param paramsPreview 脱敏参数摘要；仅详情返回
 * @param planSha256 plan 绑定哈希
 * @param requestedBy 运行发起人
 * @param decidedBy 审批人；未决定为空
 * @param decidedAt 决定时间；未决定为空
 * @param decisionComment 决定理由；未决定为空
 * @param createdAt 创建时间
 * @param selfApprovalBlocked 当前用户是运行发起人时 true；仅详情返回
 * @param documentReferenceCount 审批涉及的引用数量；仅详情返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowApprovalResponse(
        UUID id,
        UUID workflowRunId,
        UUID workflowId,
        String workflowName,
        String nodeId,
        String nodeName,
        WorkflowApprovalStatus status,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        List<UUID> assigneeUserIds,
        WorkflowApprovalActionKind actionKind,
        WorkflowDataScopeSummaryResponse dataScopeSummary,
        String targetToolName,
        JsonNode paramsPreview,
        String planSha256,
        UUID requestedBy,
        UUID decidedBy,
        OffsetDateTime decidedAt,
        String decisionComment,
        OffsetDateTime createdAt,
        Boolean selfApprovalBlocked,
        Integer documentReferenceCount) {}
