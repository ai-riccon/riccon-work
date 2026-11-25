package com.riccon.worker.workflow.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单槽位空队列指数退避。
 *
 * <p>序列必须是 poll → 2×poll → 4×poll → 封顶；领取成功后复位为 poll。
 */
class WorkflowClaimBackoffTest {

    /** 1000ms 起步、5000ms 封顶的完整序列。 */
    @Test
    @DisplayName("空队列指数退避并封顶")
    void nextAfterEmpty_doublesAndCaps() {
        WorkflowClaimBackoff backoff = new WorkflowClaimBackoff(1000L, 5000L);

        assertThat(backoff.nextAfterEmpty()).isEqualTo(2000L);
        assertThat(backoff.nextAfterEmpty()).isEqualTo(4000L);
        assertThat(backoff.nextAfterEmpty()).isEqualTo(5000L);
        assertThat(backoff.nextAfterEmpty()).isEqualTo(5000L);
    }

    /** 领取成功后立即复位，不等退避上限。 */
    @Test
    @DisplayName("领取成功复位轮询周期")
    void reset_restoresInitialInterval() {
        WorkflowClaimBackoff backoff = new WorkflowClaimBackoff(250L, 5000L);
        backoff.nextAfterEmpty();
        backoff.nextAfterEmpty();

        assertThat(backoff.reset()).isEqualTo(250L);
        assertThat(backoff.nextAfterEmpty()).isEqualTo(500L);
        assertThat(backoff.nextAfterEmpty()).isEqualTo(1000L);
    }

    /** 每个槽位独立：一个槽位退避不影响其他槽位。 */
    @Test
    @DisplayName("槽位退避相互独立")
    void instances_areIndependent() {
        WorkflowClaimBackoff first = new WorkflowClaimBackoff(1000L, 5000L);
        WorkflowClaimBackoff second = new WorkflowClaimBackoff(1000L, 5000L);

        assertThat(first.nextAfterEmpty()).isEqualTo(2000L);
        assertThat(first.nextAfterEmpty()).isEqualTo(4000L);
        assertThat(second.nextAfterEmpty()).isEqualTo(2000L);
    }

    /** 非法边界在构造期失败，避免 0 延迟空转。 */
    @Test
    @DisplayName("非法退避边界直接失败")
    void constructor_rejectsInvalidBounds() {
        assertThatThrownBy(() -> new WorkflowClaimBackoff(0L, 1000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowClaimBackoff(1000L, 999L)).isInstanceOf(IllegalArgumentException.class);
    }
}
