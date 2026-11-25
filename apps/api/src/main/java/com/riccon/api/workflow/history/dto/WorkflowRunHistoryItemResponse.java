package com.riccon.api.workflow.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 运行历史条目（契约 {@code WorkflowRun}）。
 *
 * <p>列表按开始时间倒序；不返回最终结果正文，只返回 {@code resultStatus}。{@code approvalStatus} 与三个
 * 计数来自审批表派生聚合，多审批节点时由计数表达细节（ADR-0024 §3.3.3、AC-12）。
 *
 * <p>时间统一转换为 {@code Asia/Shanghai}（UTC+08:00）偏移的 ISO 8601；数据库仍存 UTC。
 *
 * @param id 运行 ID
 * @param workflowId 流程 ID
 * @param workflowName 流程名
 * @param workflowVersionId 绑定版本 ID
 * @param versionNo 版本号
 * @param requesterUserId 发起人
 * @param status 运行状态
 * @param currentNodeId 当前节点 key
 * @param approvalStatus 审批聚合状态；无审批时为空
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏失败原因
 * @param resultStatus 结果保存状态
 * @param createdAt 创建时间
 * @param startedAt 开始时间
 * @param finishedAt 终态时间
 * @param durationMs 执行耗时（不含审批等待）
 * @param updatedAt 最近更新时间（轮询判据）
 * @param pendingApprovalCount 待审批数
 * @param approvedApprovalCount 已批准数
 * @param rejectedApprovalCount 已拒绝数
 * @param retentionExpiresAt 保留到期时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunHistoryItemResponse(
        UUID id,
        UUID workflowId,
        String workflowName,
        UUID workflowVersionId,
        int versionNo,
        UUID requesterUserId,
        WorkflowRunStatus status,
        String currentNodeId,
        WorkflowApprovalStatus approvalStatus,
        WorkflowErrorCode errorCode,
        String errorMessage,
        WorkflowResultStatus resultStatus,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationMs,
        OffsetDateTime updatedAt,
        int pendingApprovalCount,
        int approvedApprovalCount,
        int rejectedApprovalCount,
        OffsetDateTime retentionExpiresAt) {}
