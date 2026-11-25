package com.riccon.worker.workflow.queue.domain;

/**
 * 运行队列聚合指标（跨租户只读聚合）。
 *
 * <p>队列深度与最老 PENDING 年龄只按状态聚合，不读取租户、输入或结果；用于
 * {@code riccon_workflow_queue_depth} 与 {@code riccon_workflow_queue_oldest_pending_age_seconds}
 * 两个 Gauge（ADR-0024 §10.2）。
 *
 * @param pendingDepth PENDING 运行数
 * @param waitingApprovalDepth WAITING_APPROVAL 运行数
 * @param oldestPendingAgeSeconds 最老 PENDING 的创建时长（秒）；无 PENDING 时为 0
 */
public record WorkflowRunQueueStats(long pendingDepth, long waitingApprovalDepth, long oldestPendingAgeSeconds) {

    /** 空队列基线，指标首次读取前使用。 */
    public static final WorkflowRunQueueStats EMPTY = new WorkflowRunQueueStats(0L, 0L, 0L);
}
