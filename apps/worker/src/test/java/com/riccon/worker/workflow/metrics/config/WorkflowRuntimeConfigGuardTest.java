package com.riccon.worker.workflow.metrics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.riccon.worker.workflow.config.WorkflowDatabasePoolGuard;
import com.riccon.worker.workflow.config.WorkflowRuntimeConfigValidator;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worker 运行限制启动校验与 Hikari 容量观测（OPS-M06-011/OPS-M06-022）。
 *
 * <p>测试文件放在任务写入范围 {@code workflow/metrics/**} 下，被验证类位于 {@code workflow/config}；
 * 断言只使用公开构造器，不启动 Spring 上下文。
 */
class WorkflowRuntimeConfigGuardTest {

    /** 默认运行限制。 */
    private static final WorkflowRuntimeProperties RUNTIME = new WorkflowRuntimeProperties(
            true, "", 50, 200, 300, 120, 60, 30, 10, 5, 5_242_880L, 180);

    /** 默认队列配置。 */
    private static final WorkflowQueueProperties QUEUE =
            new WorkflowQueueProperties(10, 1, 1_000L, 5_000L, 10, 10, 30, 90, true, 120, "it");

    /** 默认 16 MiB 包大小。 */
    private static final long DEFAULT_PACKET = 16_777_216L;

    /** 默认配置通过。 */
    @Test
    @DisplayName("默认运行限制与包大小通过启动校验")
    void defaults_pass() {
        new WorkflowRuntimeConfigValidator(RUNTIME, QUEUE, DEFAULT_PACKET).afterPropertiesSet();
    }

    /** 非法上限给出环境变量键。 */
    @Test
    @DisplayName("非法运行上限阻断启动")
    void invalidLimits_failWithKey() {
        WorkflowRuntimeProperties invalid = new WorkflowRuntimeProperties(
                true, "", 0, 200, 300, 120, 60, 30, 10, 5, 5_242_880L, 180);

        assertThatThrownBy(() -> new WorkflowRuntimeConfigValidator(invalid, QUEUE, DEFAULT_PACKET)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_MAX_NODES");
    }

    /** 关闭原因超长阻断。 */
    @Test
    @DisplayName("关闭原因超长阻断启动")
    void disabledReasonTooLong_fails() {
        WorkflowRuntimeProperties invalid = new WorkflowRuntimeProperties(
                true, "x".repeat(201), 50, 200, 300, 120, 60, 30, 10, 5, 5_242_880L, 180);

        assertThatThrownBy(() -> new WorkflowRuntimeConfigValidator(invalid, QUEUE, DEFAULT_PACKET)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_DISABLED_REASON");
    }

    /** 包大小低于 8 MiB 阻断。 */
    @Test
    @DisplayName("包大小低于 8 MiB 阻断启动")
    void packetBelowMinimum_fails() {
        assertThatThrownBy(() -> new WorkflowRuntimeConfigValidator(RUNTIME, QUEUE, 4L * 1024L * 1024L)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8 MiB");
    }

    /** 包大小未覆盖结果上限 + 1 MiB 阻断。 */
    @Test
    @DisplayName("包大小未覆盖结果上限加余量阻断启动")
    void packetBelowHeadroom_fails() {
        assertThatThrownBy(() -> new WorkflowRuntimeConfigValidator(RUNTIME, QUEUE, 5_242_880L)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_DB_MAX_ALLOWED_PACKET");
    }

    /** 总开关关闭但仍在领取只告警，不阻断启动（回滚顺序允许）。 */
    @Test
    @DisplayName("关闭总开关但仍领取不阻断启动")
    void disabledButClaiming_doesNotFail() {
        WorkflowRuntimeProperties disabled = new WorkflowRuntimeProperties(
                false, "演练", 50, 200, 300, 120, 60, 30, 10, 5, 5_242_880L, 180);

        new WorkflowRuntimeConfigValidator(disabled, QUEUE, DEFAULT_PACKET).afterPropertiesSet();
    }

    /** Hikari 实际池小于并发 + 4 时暴露缺口值。 */
    @Test
    @DisplayName("Hikari 实际池不足时暴露缺口 Gauge")
    void hikariShortfall_exposedAsGauge() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setMaximumPoolSize(3);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            new WorkflowDatabasePoolGuard(dataSource, QUEUE, registry).afterPropertiesSet();

            assertThat(registry.get(WorkflowDatabasePoolGuard.SHORTFALL_METRIC)
                            .gauge()
                            .value())
                    .isEqualTo(11.0);
        }
    }

    /** Hikari 实际池满足时缺口为 0。 */
    @Test
    @DisplayName("Hikari 实际池满足时缺口为 0")
    void hikariSufficient_reportsZero() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setMaximumPoolSize(14);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            new WorkflowDatabasePoolGuard(dataSource, QUEUE, registry).afterPropertiesSet();

            assertThat(registry.get(WorkflowDatabasePoolGuard.SHORTFALL_METRIC)
                            .gauge()
                            .value())
                    .isZero();
        }
    }

    /** 非 Hikari 数据源不注册缺口指标，也不阻断启动。 */
    @Test
    @DisplayName("非 Hikari 数据源跳过容量指标")
    void nonHikariDataSource_skipsGauge() {
        DataSource dataSource = mock(DataSource.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new WorkflowDatabasePoolGuard(dataSource, QUEUE, registry).afterPropertiesSet();

        assertThat(registry.find(WorkflowDatabasePoolGuard.SHORTFALL_METRIC).gauge())
                .isNull();
    }
}
