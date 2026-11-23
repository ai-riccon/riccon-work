package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.connector.domain.ConnectorSyncBudget;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 同步预算：对象数、总字节与总超时；超限只标记截断，由调用方停止传播删除。
 */
class ConnectorSyncBudgetTest {

    @Test
    void budget_enforcesObjectAndByteLimits() {
        Instant start = Instant.parse("2026-11-24T00:00:00Z");
        ConnectorSyncBudget budget = new ConnectorSyncBudget(start, Duration.ofMinutes(60), 2, 100);
        assertThat(budget.addObject()).isTrue();
        assertThat(budget.addObject()).isTrue();
        assertThat(budget.addObject()).isFalse();
        assertThat(budget.truncated()).isTrue();

        ConnectorSyncBudget bytes = new ConnectorSyncBudget(start, Duration.ofMinutes(60), 10, 10);
        assertThat(bytes.addBytes(5)).isTrue();
        assertThat(bytes.addBytes(6)).isFalse();
        assertThat(bytes.truncated()).isTrue();
        assertThat(bytes.downloadedBytes()).isEqualTo(11);
    }

    @Test
    void budget_expiresAtDeadline() {
        Instant start = Instant.parse("2026-11-24T00:00:00Z");
        ConnectorSyncBudget budget = new ConnectorSyncBudget(start, Duration.ofMinutes(60), 10, 100);
        assertThat(budget.expired(start.plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(budget.expired(start.plus(Duration.ofMinutes(60)))).isTrue();
        assertThat(budget.remaining(start.plus(Duration.ofMinutes(61)))).isZero();
    }
}
