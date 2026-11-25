package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 新建节点尝试的持久化参数。
 *
 * <p>正常执行先插入 {@code PENDING} 再条件更新为 {@code RUNNING}；跳过/超时等不执行的节点直接
 * 插入终态行，保证运行详情的节点时间线完整（ADR-0024 §4.2/§4.6）。
 *
 * @param id 节点运行 ID
 * @param runId 运行 ID
 * @param nodeKey 节点 key
 * @param nodeType 节点类型
 * @param nodeName 节点名快照
 * @param attempt 尝试号
 * @param status 初始状态
 * @param inputSummary 输入摘要
 * @param outputSummary 输出摘要
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏原因
 * @param now UTC 时间
 */
public record WorkflowNodeAttemptInsert(
        UUID id,
        UUID runId,
        String nodeKey,
        WorkflowNodeType nodeType,
        String nodeName,
        int attempt,
        WorkflowNodeStatus status,
        String inputSummary,
        String outputSummary,
        String errorCode,
        String errorMessage,
        Instant now) {

    /** 校验必填字段。 */
    public WorkflowNodeAttemptInsert {
        Objects.requireNonNull(id, "node run id must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
