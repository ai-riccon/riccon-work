package com.riccon.worker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 内部对话流配置。
 *
 * <p>流式请求不进入 outbox；Worker 用信号量限制并发，并设置首字、空闲与总时长上限（ADR-0008）。
 * 首字预算 3 秒由性能测试验证，这里的超时是保护上限，不代替性能预算。
 *
 * @param maxConcurrentStreams 单实例同时执行的对话流上限
 * @param idleTimeout 两个事件之间的空闲上限；也覆盖首字等待
 * @param maxDuration 单次对话流总时长上限
 * @param maxHistoryMessages 载入模型的历史消息条数上限
 */
@ConfigurationProperties(prefix = "riccon.worker.chat")
public record ChatStreamProperties(
        @DefaultValue("16") int maxConcurrentStreams,
        @DefaultValue("60s") Duration idleTimeout,
        @DefaultValue("10m") Duration maxDuration,
        @DefaultValue("40") int maxHistoryMessages) {

    /** 校验并发与超时边界。 */
    public ChatStreamProperties {
        if (maxConcurrentStreams < 1 || maxConcurrentStreams > 128) {
            throw new IllegalArgumentException("riccon.worker.chat.max-concurrent-streams must be between 1 and 128");
        }
        requirePositive(idleTimeout, "idle-timeout");
        requirePositive(maxDuration, "max-duration");
        if (maxHistoryMessages < 1 || maxHistoryMessages > 200) {
            throw new IllegalArgumentException("riccon.worker.chat.max-history-messages must be between 1 and 200");
        }
    }

    /** 校验时长为正。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.chat." + name + " must be positive");
        }
    }
}
