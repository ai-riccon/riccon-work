package com.riccon.worker.workflow.metrics.domain;

/**
 * 待审批运行聚合快照（ADR-0024 §10.2）。
 *
 * <p>只包含计数与最老等待秒数，不含审批正文、参数或审批人信息，因此可作为全局（跨租户）聚合
 * 指标读取，不构成 TM-85 之外的业务内容跨租户读取。
 *
 * @param pending 当前 {@code PENDING} 审批数
 * @param oldestWaitSeconds 最老待审批已等待秒数；无待审批时为 0
 */
public record WorkflowApprovalWaitSnapshot(long pending, long oldestWaitSeconds) {

    /** 空快照；队列初始化或数据库不可用时使用。 */
    public static final WorkflowApprovalWaitSnapshot EMPTY = new WorkflowApprovalWaitSnapshot(0L, 0L);
}
