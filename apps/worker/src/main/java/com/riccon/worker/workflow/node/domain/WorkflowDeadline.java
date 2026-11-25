package com.riccon.worker.workflow.node.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 节点执行截止时间。
 *
 * <p>截止时间取“节点类型超时”与“运行剩余执行预算”的较小值；模型流式调用直接使用
 * {@link #remaining()}，检索在查询前后检查。使用注入时钟，测试不依赖机器时间
 * （ADR-0024 §4.4/§4.6）。
 */
public record WorkflowDeadline(Instant deadlineAt, Clock clock) {

    /** 校验必填字段。 */
    public WorkflowDeadline {
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 是否已过期。
     *
     * @return true 表示当前时间不早于截止时间
     */
    public boolean expired() {
        return !clock.instant().isBefore(deadlineAt);
    }

    /**
     * 剩余时间；已过期时为零。
     *
     * @return 剩余时长
     */
    public Duration remaining() {
        Duration remaining = Duration.between(clock.instant(), deadlineAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * 断言尚未过期。
     *
     * @throws WorkflowNodeTimeoutException 已过期
     */
    public void requireRemaining() {
        if (expired()) {
            throw new WorkflowNodeTimeoutException("节点执行超过超时预算");
        }
    }
}
