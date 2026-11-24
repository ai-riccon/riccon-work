package com.riccon.api.workflow.run.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.workflow.run.domain.WorkflowRunSnapshot;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 运行响应（契约 {@code WorkflowRun}）。
 *
 * <p>{@code createdAt}/{@code updatedAt} 等时间统一转换为 {@code Asia/Shanghai}（UTC+08:00）偏移的
 * ISO 8601，与合同和 M10 展示口径一致；数据库仍存 UTC。创建/幂等重放只返回运行摘要，节点时间线由
 * T08 的详情端点提供（ADR-0024 §7.4）。
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
 * @param startedAt 首次开始时间
 * @param finishedAt 终态时间
 * @param durationMs 执行耗时；创建时为空
 * @param updatedAt 最近更新时间（轮询判据）
 * @param pendingApprovalCount 待审批数
 * @param approvedApprovalCount 已批准数
 * @param rejectedApprovalCount 已拒绝数
 * @param retentionExpiresAt 保留到期时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunResponse(
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
        OffsetDateTime retentionExpiresAt) {

    /** 对外时间偏移（Asia/Shanghai，UTC+08:00）。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 从运行快照构造响应。
     *
     * @param snapshot 运行快照
     * @return 运行响应
     */
    public static WorkflowRunResponse from(WorkflowRunSnapshot snapshot) {
        return new WorkflowRunResponse(
                snapshot.id(),
                snapshot.workflowId(),
                snapshot.workflowName(),
                snapshot.workflowVersionId(),
                snapshot.versionNo(),
                snapshot.requesterUserId(),
                snapshot.status(),
                snapshot.currentNodeKey(),
                null,
                snapshot.errorCode(),
                snapshot.errorMessage(),
                snapshot.resultStatus(),
                display(snapshot.createdAt()),
                display(snapshot.startedAt()),
                display(snapshot.finishedAt()),
                null,
                display(snapshot.updatedAt()),
                0,
                0,
                0,
                display(snapshot.retentionExpiresAt()));
    }

    /**
     * 把 UTC 时间转换为带 +08:00 偏移的展示时间。
     *
     * @param instant UTC 时间；可空
     * @return 展示时间或 {@code null}
     */
    private static OffsetDateTime display(Instant instant) {
        return instant == null ? null : instant.atZone(DISPLAY_ZONE).toOffsetDateTime();
    }
}
