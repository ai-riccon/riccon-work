package com.riccon.api.workflow.run.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 重试时新增的节点尝试插入参数。
 *
 * <p>重试不新建运行，只追加 {@code attempt+1} 的节点行；上游成功节点不重跑，下游 {@code SKIPPED}
 * 节点由仓储批量恢复为 {@code PENDING}（ADR-0024 §4.6）。
 *
 * @param id 节点运行 ID（UUIDv7）
 * @param workflowRunId 运行 ID
 * @param nodeKey 节点 key
 * @param nodeType 节点类型
 * @param nodeName 节点名快照
 * @param attempt 新尝试号
 * @param createdAt 创建时间
 */
public record WorkflowRunNodeInsert(
        UUID id,
        UUID workflowRunId,
        String nodeKey,
        WorkflowNodeType nodeType,
        String nodeName,
        int attempt,
        Instant createdAt) {

    /** 校验必填字段。 */
    public WorkflowRunNodeInsert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
    }
}
