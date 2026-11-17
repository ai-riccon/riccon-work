package com.riccon.platform.domain.research;

/**
 * 审批状态。
 *
 * <p>审批请求由服务端在工具调用前创建；模型输出不能生成审批结论。每个运行阶段的每次尝试最多一个审批，
 * 防止重试时复用旧批准。
 */
public enum ApprovalStatus {
    /** 等待员工决定。 */
    PENDING,
    /** 已批准，可继续执行。 */
    APPROVED,
    /** 已拒绝，任务终止。 */
    REJECTED,
    /** 超时失效，任务保持待处理或失败。 */
    EXPIRED,
    /** 任务取消或权限撤销。 */
    CANCELLED
}
