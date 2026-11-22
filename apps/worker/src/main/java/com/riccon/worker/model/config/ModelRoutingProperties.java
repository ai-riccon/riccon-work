package com.riccon.worker.model.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 降级状态机的尝试预算（ADR-0021 §5.2）。
 *
 * <p>内层 OpenAI 客户端固定只发一次 HTTP（`riccon.worker.model.max-attempts=1`）；重试、换 key、
 * 换模型都在本配置约束下由 `FailoverChatModel` 执行。`retry-delays` 只用于同一 key 的显式
 * 退避重试，索引超出长度时取最后一个值。
 *
 * @param maxAttempts 同一调用（trace）最多尝试次数，默认 3、硬上限 5
 * @param retryDelays 同 key 重试的退避序列
 * @param firstTokenTimeout 单次尝试等待首个可见增量的超时
 * @param preFirstTokenBudget 首字前所有尝试共享的总预算
 */
@ConfigurationProperties(prefix = "riccon.worker.model.routing")
public record ModelRoutingProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue({"200ms", "500ms", "1s"}) List<Duration> retryDelays,
        @DefaultValue("30s") Duration firstTokenTimeout,
        @DefaultValue("60s") Duration preFirstTokenBudget) {

    /** 尝试上限硬约束与超时合法性校验。 */
    public ModelRoutingProperties {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("riccon.worker.model.routing.max-attempts must be between 1 and 5");
        }
        if (retryDelays == null || retryDelays.isEmpty()) {
            throw new IllegalArgumentException("riccon.worker.model.routing.retry-delays must not be empty");
        }
        retryDelays = List.copyOf(retryDelays);
        requirePositive(firstTokenTimeout, "first-token-timeout");
        requirePositive(preFirstTokenBudget, "pre-first-token-budget");
    }

    /** 校验时长为正。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.model.routing." + name + " must be positive");
        }
    }
}
