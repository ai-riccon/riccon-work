package com.riccon.api.workflow.history.domain;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行历史/详情行快照。
 *
 * <p>列表与详情共用同一记录；{@code resultJson} 只在详情查询加载，列表恒为空，避免把大结果带进分页
 * 响应（ADR-0024 §7.4“列表不返回 result”）。{@code approvalStatus} 与计数不在 SQL 行内，
 * 由服务层用一次批量聚合填充，避免列表 N+1。
 *
 * @param id 运行 ID
 * @param workflowName 流程名
 * @param workflowId 流程 ID
 * @param workflowVersionId 绑定版本 ID
 * @param versionNo 版本号快照
 * @param requesterUserId 发起人
 * @param status 运行状态
 * @param currentNodeKey 当前节点 key
 * @param errorCode 稳定错误码；可空
 * @param errorMessage 脱敏失败原因；可空
 * @param resultStatus 结果保存状态
 * @param resultSizeBytes 结果大小
 * @param executionMs 累计执行耗时（不含审批等待）
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间（轮询判据）
 * @param startedAt 首次开始时间；可空
 * @param finishedAt 终态时间；可空
 * @param retentionExpiresAt 保留到期时间
 * @param resultJson 最终结果 JSON 原文；仅详情加载
 */
public record WorkflowRunHistorySnapshot(
        UUID id,
        String workflowName,
        UUID workflowId,
        UUID workflowVersionId,
        int versionNo,
        UUID requesterUserId,
        WorkflowRunStatus status,
        String currentNodeKey,
        WorkflowErrorCode errorCode,
        String errorMessage,
        WorkflowResultStatus resultStatus,
        long resultSizeBytes,
        long executionMs,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant retentionExpiresAt,
        String resultJson) {

    /** 校验必填字段。 */
    public WorkflowRunHistorySnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowName, "workflowName must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId must not be null");
        Objects.requireNonNull(requesterUserId, "requesterUserId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(resultStatus, "resultStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(retentionExpiresAt, "retentionExpiresAt must not be null");
        if (executionMs < 0 || resultSizeBytes < 0) {
            throw new IllegalArgumentException("run counters must be non-negative");
        }
    }

    /**
     * 判断运行是否处于终态。
     *
     * @return 成功、失败或已取消时返回 {@code true}
     */
    public boolean terminal() {
        return status == WorkflowRunStatus.SUCCEEDED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED;
    }
}
