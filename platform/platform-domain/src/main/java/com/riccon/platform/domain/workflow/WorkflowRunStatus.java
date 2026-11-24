package com.riccon.platform.domain.workflow;

/**
 * 工作流运行状态。
 *
 * <p>与 {@code workflow_run.status} 的 CHECK 约束、OpenAPI {@code WorkflowRunStatus} 一致。审批等待
 * （{@link #WAITING_APPROVAL}）释放执行槽位但仍占用租户准入并发；取消只允许从 PENDING/RUNNING/
 * WAITING_APPROVAL 进入（ADR-0024 第 4.2/4.6 节）。
 */
public enum WorkflowRunStatus {
    /** 等待 Worker 领取。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 等待审批决定。 */
    WAITING_APPROVAL,
    /** 成功并写最终结果。 */
    SUCCEEDED,
    /** 失败；保留已完成节点。 */
    FAILED,
    /** 运行发起人或租户管理员取消。 */
    CANCELLED
}
