package com.riccon.worker.workflow.queue.domain;

/**
 * 运行队列固定限制。
 *
 * <p>M06 v1 的 {@code claim_attempts} 上限是部署级常量而非租户配置：超过后运行进入
 * {@code WF_INTERNAL} 失败终态，避免永久占用槽位（ADR-0024 §4.3/§4.6）。
 */
public final class WorkflowQueueLimits {

    /** 租约丢失重领次数上限；超过后进入 WF_INTERNAL。 */
    public static final int MAX_CLAIM_ATTEMPTS = 5;

    /** 工具类禁止实例化。 */
    private WorkflowQueueLimits() {}
}
