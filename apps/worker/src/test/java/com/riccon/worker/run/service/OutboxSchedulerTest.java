package com.riccon.worker.run.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** Outbox 调度器异常隔离测试。 */
class OutboxSchedulerTest {

    /** 正常轮询委托给 OutboxPoller。 */
    @Test
    void poll_success_delegates() {
        OutboxPoller poller = mock(OutboxPoller.class);
        when(poller.pollOnce()).thenReturn(2);

        new OutboxScheduler(poller).poll();

        verify(poller).pollOnce();
    }

    /** 轮询异常不能逃出调度方法，否则会终止调度线程。 */
    @Test
    void poll_exception_isCaught() {
        OutboxPoller poller = mock(OutboxPoller.class);
        when(poller.pollOnce()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> new OutboxScheduler(poller).poll()).doesNotThrowAnyException();

        verify(poller).pollOnce();
    }
}
