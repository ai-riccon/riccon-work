package com.riccon.api.workflow.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 运行详情（契约 {@code WorkflowRunDetail}）。
 *
 * <p>在运行摘要之上返回节点时间线（含全部尝试）、审批记录、聚合引用与最终结果。不返回完整输入、
 * 完整提示词、工具参数、凭据或无 ACL 正文；引用按当前账号重新计算 {@code sourceStatus} 并遮蔽
 * （ADR-0024 §3.4/§6.2，AC-13）。
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
 * @param durationMs 执行耗时
 * @param updatedAt 最近更新时间
 * @param pendingApprovalCount 待审批数
 * @param approvedApprovalCount 已批准数
 * @param rejectedApprovalCount 已拒绝数
 * @param retentionExpiresAt 保留到期时间
 * @param nodes 节点时间线（含重试尝试）
 * @param approvals 审批记录
 * @param citations 聚合去重引用
 * @param result 最终结果；非成功或无结果时为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunDetailResponse(
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
        OffsetDateTime retentionExpiresAt,
        List<WorkflowNodeRunResponse> nodes,
        List<WorkflowRunApprovalResponse> approvals,
        List<WorkflowCitationResponse> citations,
        WorkflowResultResponse result) {}
