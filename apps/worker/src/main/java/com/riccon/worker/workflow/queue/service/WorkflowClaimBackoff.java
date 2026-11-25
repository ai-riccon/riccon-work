package com.riccon.worker.workflow.queue.service;

/**
 * 单槽位空队列指数退避。
 *
 * <p>每个槽位独立维护退避状态：第一次空领取后从 {@code claim-poll-interval-ms} 开始倍增，
 * 上限为 {@code claim-idle-backoff-max-ms}；领取到运行时立即复位，保证有任务时不额外等待。
 * 该策略避免 10 个槽位在空队列时每秒同时打满数据库（ADR-0024 §4.3、OPS-M06-022）。
 *
 * <p>类本身不持有线程或时间，纯状态机，便于单元测试精确断言序列。
 */
public final class WorkflowClaimBackoff {

    /** 初始（也是复位后）延迟。 */
    private final long initialMillis;

    /** 最大延迟。 */
    private final long maxMillis;

    /** 当前延迟；初始值为轮询周期，首次空领取后立即倍增。 */
    private long currentMillis;

    /**
     * 创建退避状态机。
     *
     * @param initialMillis 初始延迟；必须为正
     * @param maxMillis 最大延迟；必须 ≥ 初始延迟
     */
    public WorkflowClaimBackoff(long initialMillis, long maxMillis) {
        if (initialMillis <= 0L) {
            throw new IllegalArgumentException("initialMillis must be positive");
        }
        if (maxMillis < initialMillis) {
            throw new IllegalArgumentException("maxMillis must be >= initialMillis");
        }
        this.initialMillis = initialMillis;
        this.maxMillis = maxMillis;
        this.currentMillis = initialMillis;
    }

    /**
     * 空领取后的下一次延迟：首次从轮询周期倍增，之后继续倍增并封顶。
     *
     * @return 下一次轮询延迟毫秒
     */
    public long nextAfterEmpty() {
        currentMillis = Math.min(maxMillis, Math.max(initialMillis, currentMillis) * 2L);
        return currentMillis;
    }

    /**
     * 领取成功后的复位延迟。
     *
     * @return 初始轮询延迟
     */
    public long reset() {
        currentMillis = initialMillis;
        return currentMillis;
    }
}
