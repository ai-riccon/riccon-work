package com.riccon.worker.run.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox heavy 看门狗与连接器同步总超时的启动校验（M05-B1 F-004）。
 *
 * <p>连接器启用时 heavy 上限必须 >= 60 分钟，否则长同步会被看门狗提前中断；连接器关闭或本实例
 * 不启用 heavy 通道时允许更小值，避免测试与小部署被不必要地阻断。
 */
class OutboxProcessingTimeoutValidatorTest {

    /** 连接器启用且 heavy 通道启用的边界：60m 通过、59m 失败、65m 默认值通过。 */
    @Test
    @DisplayName("连接器启用时 heavy 上限不得小于 60m")
    void connectorEnabled_requiresSixtyMinutes() {
        assertThatCode(() ->
                        validator(true, laneProperties(Duration.ofMinutes(60))).afterPropertiesSet())
                .as("60m 边界应通过")
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        validator(true, laneProperties(Duration.ofMinutes(65))).afterPropertiesSet())
                .as("默认 65m 应通过")
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                        validator(true, laneProperties(Duration.ofMinutes(59))).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_CONNECTOR_ENABLED")
                .hasMessageContaining("heavy-max-processing")
                .hasMessageContaining("60m")
                .hasMessageContaining("59 分钟");
    }

    /** 连接器关闭时允许 heavy 上限小于 60m，供测试与小部署使用。 */
    @Test
    @DisplayName("连接器关闭时允许更小的 heavy 上限")
    void connectorDisabled_allowsSmallerHeavyLimit() {
        assertThatCode(() ->
                        validator(false, laneProperties(Duration.ofMinutes(5))).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    /** 只启用 default 通道的实例不消费连接器事件，不受 heavy 上限校验影响。 */
    @Test
    @DisplayName("只启用 default 通道时跳过连接器校验")
    void defaultOnlyLane_skipsConnectorValidation() {
        OutboxLaneProperties laneProperties = laneProperties(Duration.ofMinutes(5));
        laneProperties.setLanes(List.of("default"));
        laneProperties.afterPropertiesSet();
        assertThatCode(() -> validator(true, laneProperties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    /** 连接器启用但只启用 heavy 的分实例部署同样必须满足 60m。 */
    @Test
    @DisplayName("只启用 heavy 时仍校验 60m")
    void heavyOnlyLane_stillRequiresSixtyMinutes() {
        OutboxLaneProperties laneProperties = laneProperties(Duration.ofMinutes(5));
        laneProperties.setLanes(List.of("heavy"));
        laneProperties.afterPropertiesSet();
        assertThatThrownBy(() -> validator(true, laneProperties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("60m");
    }

    /**
     * 构造默认 default+heavy 的通道配置。
     *
     * @param heavyMaxProcessing heavy 最大处理时长
     * @return 已校验的通道配置
     */
    private static OutboxLaneProperties laneProperties(Duration heavyMaxProcessing) {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.setHeavyMaxProcessing(heavyMaxProcessing);
        properties.afterPropertiesSet();
        return properties;
    }

    /**
     * 构造连接器看门狗校验器。
     *
     * @param connectorEnabled 连接器开关
     * @param laneProperties 通道配置
     * @return 校验器
     */
    private static OutboxProcessingTimeoutValidator validator(
            boolean connectorEnabled, OutboxLaneProperties laneProperties) {
        return new OutboxProcessingTimeoutValidator(connectorEnabled, laneProperties);
    }
}
