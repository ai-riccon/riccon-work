package com.riccon.platform.domain.research;

/**
 * 执行运行状态。
 *
 * <p>允许的主路径：PENDING → RUNNING → WAITING_APPROVAL → RUNNING → SUCCEEDED；可重试错误进入
 * RETRY_WAIT 后回到 RUNNING；终态为 SUCCEEDED/FAILED/CANCELLED，不得再迁移。
 */
public enum RunStatus {
    /** 已受理，等待领取。 */
    PENDING,
    /** 执行中，必须持有租约。 */
    RUNNING,
    /** 等待审批，释放租约。 */
    WAITING_APPROVAL,
    /** 可重试失败后的退避等待。 */
    RETRY_WAIT,
    /** 成功。 */
    SUCCEEDED,
    /** 不可重试失败或超过最大尝试次数。 */
    FAILED,
    /** 用户取消或权限撤销。 */
    CANCELLED
}
