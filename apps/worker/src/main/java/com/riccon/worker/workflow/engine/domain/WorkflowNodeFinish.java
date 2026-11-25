package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行中节点尝试的终态写入参数。
 *
 * <p>只允许把 {@code RUNNING} 尝试推进到 {@code SUCCEEDED}/{@code FAILED}/{@code CANCELLED}；
 * {@code outputJson}/{@code citationsJson} 可为空，摘要与错误信息在持久化层截断到列宽。
 *
 * @param runId 运行 ID
 * @param nodeKey 节点 key
 * @param attempt 尝试号
 * @param status 终态
 * @param inputSummary 输入摘要
 * @param outputSummary 输出摘要
 * @param outputJson 输出 JSON
 * @param citationsJson 引用 JSON
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏原因
 * @param finishedAt 完成时间（UTC）
 */
public record WorkflowNodeFinish(
        UUID runId,
        String nodeKey,
        int attempt,
        WorkflowNodeStatus status,
        String inputSummary,
        String outputSummary,
        String outputJson,
        String citationsJson,
        String errorCode,
        String errorMessage,
        Instant finishedAt) {

    /** 校验必填字段与终态。 */
    public WorkflowNodeFinish {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (status != WorkflowNodeStatus.SUCCEEDED
                && status != WorkflowNodeStatus.FAILED
                && status != WorkflowNodeStatus.CANCELLED) {
            throw new IllegalArgumentException("node finish status must be terminal");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
