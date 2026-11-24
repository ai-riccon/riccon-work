package com.riccon.api.workflow.run.persistence;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code workflow_run} 持久化行。
 *
 * <p>只承载 API 侧读取与状态机需要的列；{@code inputs_json}/{@code result_json} 等业务内容不在运行准入
 * 与取消/重试路径读取，避免把用户输入带进日志或审计（ADR-0024 §8.2）。
 *
 * @param id 运行 ID
 * @param workflowName 流程名（JOIN {@code workflow_definition}）
 * @param workflowId 流程 ID
 * @param workflowVersionId 版本 ID
 * @param versionNo 版本号
 * @param requesterUserId 发起人
 * @param status 运行状态
 * @param currentNodeKey 当前节点 key
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏失败原因
 * @param resultStatus 结果保存状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param startedAt 首次开始时间
 * @param finishedAt 终态时间
 * @param retentionExpiresAt 保留到期时间
 * @param idempotencyKey 创建幂等键
 */
public record WorkflowRunRow(
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
        String idempotencyKey) {}
