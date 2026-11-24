package com.riccon.platform.domain.workflow;

/**
 * 节点运行状态。
 *
 * <p>与 {@code workflow_node_run.status} 的 CHECK 约束、OpenAPI {@code WorkflowNodeStatus} 一致；
 * 未走分支或上游失败的下游节点使用 {@link #SKIPPED}，不写最终结果（ADR-0024 第 4.2 节）。
 */
public enum WorkflowNodeStatus {
    /** 等待执行。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 审批节点等待决定。 */
    WAITING_APPROVAL,
    /** 成功。 */
    SUCCEEDED,
    /** 失败；可重试。 */
    FAILED,
    /** 未走分支或上游失败。 */
    SKIPPED,
    /** 用户取消或运行超时。 */
    CANCELLED
}
