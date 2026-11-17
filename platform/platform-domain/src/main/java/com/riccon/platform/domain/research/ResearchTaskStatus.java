package com.riccon.platform.domain.research;

/**
 * 市场调研任务状态。
 *
 * <p>业务任务与执行运行分离：任务保存用户可见状态，运行保存每次尝试与租约。审批等待不占用 Worker 租约。
 */
public enum ResearchTaskStatus {
    /** 已创建，等待首次运行领取。 */
    PENDING,
    /** 运行中（检索/工具/生成任一阶段）。 */
    RUNNING,
    /** 等待员工审批外部工具调用。 */
    WAITING_APPROVAL,
    /** 报告已保存。 */
    SUCCEEDED,
    /** 失败；保留已完成阶段，可重试。 */
    FAILED,
    /** 用户取消或权限撤销。 */
    CANCELLED
}
