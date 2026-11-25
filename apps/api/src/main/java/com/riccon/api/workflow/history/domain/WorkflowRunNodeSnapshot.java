package com.riccon.api.workflow.history.domain;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 节点运行尝试快照（运行详情时间线）。
 *
 * <p>只承载详情展示需要的摘要字段；{@code output_json} 属于下游执行内部数据，不进入 API 投影。
 * {@code citationsJson} 是节点引用快照原文，由服务层解析、批量计算 {@code sourceStatus} 后聚合去重。
 *
 * @param id 节点运行 ID
 * @param nodeKey 图内节点 key
 * @param nodeType 节点类型
 * @param nodeName 节点名快照
 * @param attempt 尝试号
 * @param status 节点状态
 * @param inputSummary 输入摘要；可空
 * @param outputSummary 输出摘要；可空
 * @param citationsJson 引用快照 JSON；可空
 * @param approvalId 审批 ID；可空
 * @param invocationId 工具调用 ID；可空
 * @param errorCode 稳定错误码；可空
 * @param errorMessage 脱敏原因；可空
 * @param startedAt 开始时间；可空
 * @param finishedAt 完成时间；可空
 * @param durationMs 耗时；可空
 */
public record WorkflowRunNodeSnapshot(
        UUID id,
        String nodeKey,
        WorkflowNodeType nodeType,
        String nodeName,
        int attempt,
        WorkflowNodeStatus status,
        String inputSummary,
        String outputSummary,
        String citationsJson,
        UUID approvalId,
        UUID invocationId,
        WorkflowErrorCode errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {

    /** 校验必填字段与尝试号。 */
    public WorkflowRunNodeSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
    }
}
