package com.riccon.api.workflow.run.domain;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作流运行快照。
 *
 * <p>由仓储从 {@code workflow_run} 行映射而来；服务层只依赖本快照做状态机判断与响应组装，不接触 SQL。
 * 运行状态推进使用条件更新，快照本身不保证并发后仍然最新（ADR-0024 §4.2）。
 *
 * @param id 运行 ID
 * @param workflowName 流程名（响应展示用）
 * @param workflowId 流程 ID
 * @param workflowVersionId 运行绑定的版本 ID
 * @param versionNo 版本号快照
 * @param requesterUserId 发起人
 * @param status 运行状态
 * @param currentNodeKey 当前节点 key；创建时为 START 节点
 * @param errorCode 稳定错误码；可空
 * @param errorMessage 脱敏失败原因；可空
 * @param resultStatus 结果保存状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间（轮询判据）
 * @param startedAt 首次开始执行时间；可空
 * @param finishedAt 终态时间；可空
 * @param retentionExpiresAt 保留到期时间
 * @param idempotencyKey 创建幂等键
 */
public record WorkflowRunSnapshot(
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
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant retentionExpiresAt,
        String idempotencyKey) {

    /** 校验必填字段。 */
    public WorkflowRunSnapshot {
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
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
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
