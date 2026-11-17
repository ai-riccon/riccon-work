package com.riccon.platform.domain.research;

/**
 * 审批决定。
 *
 * <p>只允许向上或向下二选一；拒绝是不可重试的终止路径，必须在时间线与审计中留下审批人与时间。
 */
public enum ApprovalDecision {
    /** 批准本次外部工具调用。 */
    APPROVE,
    /** 拒绝并终止任务。 */
    REJECT
}
