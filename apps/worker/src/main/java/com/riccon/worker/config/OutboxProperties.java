package com.riccon.worker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox 轮询、租约与重试配置。
 *
 * <p>默认值对齐 ADR-0008：500 ms 轮询、批量 10、90 秒租约、最多 3 次尝试，
 * 退避 2 s / 8 s / 30 s。测试可注入更短的退避，但生产不得把批量改成无界值。
 *
 * @param pollInterval 两次轮询之间的固定延迟
 * @param pollInitialDelay 启动后首次轮询的延迟，给 schema 校验留出时间
 * @param batchSize 单次领取上限
 * @param leaseDuration 领取租约时长；进程崩溃后租约到期可被重新领取
 * @param maxAttempts 最大尝试次数（包含首次）
 * @param retryDelays 每次失败后的退避时长，索引从 attempts-1 开始
 * @param retryJitter 退避抖动上限，避免多个 Worker 同时重试
 * @param leaseOwner 显式实例标识；为空时使用主机名与 PID
 */
@ConfigurationProperties(prefix = "riccon.worker.outbox")
public record OutboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("5s") Duration pollInitialDelay,
        @DefaultValue("10") int batchSize,
        @DefaultValue("90s") Duration leaseDuration,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue({"2s", "8s", "30s"}) List<Duration> retryDelays,
        @DefaultValue("200ms") Duration retryJitter,
        @DefaultValue("") String leaseOwner) {

    /** 校验配置边界，避免无界轮询或永久租约。 */
    public OutboxProperties {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("riccon.worker.outbox.poll-interval must be positive");
        }
        if (pollInitialDelay == null || pollInitialDelay.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.outbox.poll-initial-delay must not be negative");
        }
        if (batchSize < 1 || batchSize > 50) {
            throw new IllegalArgumentException("riccon.worker.outbox.batch-size must be between 1 and 50");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("riccon.worker.outbox.lease-duration must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("riccon.worker.outbox.max-attempts must be at least 1");
        }
        if (retryDelays == null || retryDelays.isEmpty()) {
            throw new IllegalArgumentException("riccon.worker.outbox.retry-delays must not be empty");
        }
        if (retryJitter == null || retryJitter.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.outbox.retry-jitter must not be negative");
        }
        retryDelays = List.copyOf(retryDelays);
    }
}
