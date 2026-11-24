package com.riccon.api.workflow.run.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行插入参数。
 *
 * <p>创建路径只写 {@code PENDING} 运行；状态推进、租约与结果由 Worker 负责，API 不在插入时伪造执行态
 * （ADR-0024 §4.1/§4.3）。
 *
 * @param id 运行 ID（UUIDv7）
 * @param workflowId 流程 ID
 * @param workflowVersionId 绑定版本 ID
 * @param versionNo 版本号快照
 * @param requesterUserId 发起人
 * @param inputsJson 已校验并规范化的输入 JSON（≤64 KiB）
 * @param currentNodeKey 起始节点 key（START 节点）
 * @param idempotencyKey HTTP 幂等键
 * @param retentionExpiresAt 保留到期时间
 * @param createdAt 创建时间
 */
public record WorkflowRunInsert(
        UUID id,
        UUID workflowId,
        UUID workflowVersionId,
        int versionNo,
        UUID requesterUserId,
        String inputsJson,
        String currentNodeKey,
        String idempotencyKey,
        Instant retentionExpiresAt,
        Instant createdAt) {

    /** 校验必填字段。 */
    public WorkflowRunInsert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId must not be null");
        Objects.requireNonNull(requesterUserId, "requesterUserId must not be null");
        Objects.requireNonNull(inputsJson, "inputsJson must not be null");
        Objects.requireNonNull(currentNodeKey, "currentNodeKey must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(retentionExpiresAt, "retentionExpiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be >= 1");
        }
    }
}
