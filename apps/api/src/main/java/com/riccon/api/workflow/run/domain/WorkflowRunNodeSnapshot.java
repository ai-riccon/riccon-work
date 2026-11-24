package com.riccon.api.workflow.run.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.Objects;
import java.util.UUID;

/**
 * 节点运行快照。
 *
 * <p>重试端点只读取最新 attempt 的状态，用于判定“仅失败节点可重试”；完整节点时间线由 T08 读取
 * （ADR-0024 §4.6）。
 *
 * @param id 节点运行 ID
 * @param nodeKey 图内节点 key
 * @param nodeType 节点类型
 * @param nodeName 节点名快照
 * @param attempt 尝试号
 * @param status 节点状态
 */
public record WorkflowRunNodeSnapshot(
        UUID id, String nodeKey, WorkflowNodeType nodeType, String nodeName, int attempt, WorkflowNodeStatus status) {

    /** 校验必填字段。 */
    public WorkflowRunNodeSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
    }
}
