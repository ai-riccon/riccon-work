package com.riccon.worker.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.riccon.platform.domain.model.ModelPurpose;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 模型指标记录器单元测试（ADR-0021 §10.6）。
 *
 * <p>覆盖空/空白标签脱敏、负耗时归零、REPORT/CHAT 链为空分桶与 inflight 不低于 0 的边界；
 * 不启动 Spring，也不依赖 Prometheus 抓取。
 */
class ModelMetricsTest {

    /** 构造时注册 7 个指标并预置零值序列。 */
    @Test
    void constructor_registersM04MetricSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelMetrics metrics = new ModelMetrics(registry);

        assertThat(registry.find("riccon_model_requests").counters()).isNotEmpty();
        assertThat(registry.find("riccon_model_failover").counters()).isNotEmpty();
        assertThat(registry.find("riccon_model_attempt_duration").timers()).isNotEmpty();
        assertThat(registry.find("riccon_model_credential_invalid").counters()).isNotEmpty();
        assertThat(registry.find("riccon_model_credential_cooldown").counters()).isNotEmpty();
        assertThat(registry.find("riccon_model_chain_empty").counters()).hasSize(2);
        assertThat(registry.find("riccon_agent_runs_inflight").gauges()).isNotEmpty();
        assertThat(metrics.agentRunsInflight()).isZero();
    }

    /** 空/空白标签脱敏为 none，所有记录方法可安全调用。 */
    @Test
    void recorders_nullOrBlankTags_areSanitized() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelMetrics metrics = new ModelMetrics(registry);

        assertThatCode(() -> {
                    metrics.request(null, "  ", null);
                    metrics.request("provider", "model", "success");
                    metrics.failover(null, "to-model", "RATE_LIMITED");
                    metrics.credentialInvalid(null);
                    metrics.credentialCooldown(" ");
                })
                .doesNotThrowAnyException();

        assertThat(registry.find("riccon_model_requests")
                        .tag("provider", ModelMetrics.NONE)
                        .tag("model", ModelMetrics.NONE)
                        .tag("outcome", ModelMetrics.NONE)
                        .counter())
                .isNotNull();
    }

    /** 负耗时与 null 都按 0 记录，不向 Timer 写入非法值。 */
    @Test
    void attemptDuration_nullOrNegative_recordsZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelMetrics metrics = new ModelMetrics(registry);

        assertThatCode(() -> {
                    metrics.attemptDuration(null);
                    metrics.attemptDuration(Duration.ofMillis(-1));
                    metrics.attemptDuration(Duration.ZERO);
                    metrics.attemptDuration(Duration.ofMillis(25));
                })
                .doesNotThrowAnyException();
        assertThat(registry.find("riccon_model_attempt_duration").timer().count())
                .isGreaterThanOrEqualTo(4);
    }

    /** REPORT 与 CHAT 使用独立链为空计数器。 */
    @Test
    void chainEmpty_reportAndChatUseSeparateCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelMetrics metrics = new ModelMetrics(registry);

        metrics.chainEmpty(ModelPurpose.CHAT);
        metrics.chainEmpty(ModelPurpose.REPORT);

        assertThat(registry.find("riccon_model_chain_empty")
                        .tag("purpose", "CHAT")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("riccon_model_chain_empty")
                        .tag("purpose", "REPORT")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /** inflight 先增后减，且不会低于 0。 */
    @Test
    void agentRunFinished_neverGoesNegative() {
        ModelMetrics metrics = new ModelMetrics(new SimpleMeterRegistry());

        metrics.agentRunStarted();
        metrics.agentRunStarted();
        assertThat(metrics.agentRunsInflight()).isEqualTo(2);
        metrics.agentRunFinished();
        assertThat(metrics.agentRunsInflight()).isEqualTo(1);
        metrics.agentRunFinished();
        metrics.agentRunFinished();
        assertThat(metrics.agentRunsInflight()).isZero();
    }
}
