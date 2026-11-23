package com.riccon.worker.connector.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 连接器同步指标。
 *
 * <p>指标 {@code riccon_connector_sync_failures_total{reason=auth|network|timeout|credential}} 只记录同步级失败；
 * 单对象失败计入同步摘要，不按对象放大指标（ADR-0023 第 6.6 节）。标签值固定集合，不使用 connectorId 等高基数字段。
 */
@Component
public class ConnectorMetrics {

    /** 指标名。 */
    static final String FAILURES_METRIC = "riccon_connector_sync_failures_total";

    /** 指标注册表。 */
    private final MeterRegistry registry;

    /**
     * 创建指标组件。
     *
     * @param registry 指标注册表
     */
    public ConnectorMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * 记录一次同步级失败。
     *
     * @param reason auth/network/timeout/credential
     */
    public void syncFailure(String reason) {
        String normalized = reason == null ? "network" : reason.toLowerCase(Locale.ROOT);
        Counter.builder(FAILURES_METRIC)
                .tag("reason", normalized)
                .description("Connector sync failures by reason")
                .register(registry)
                .increment();
    }
}
