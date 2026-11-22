package com.riccon.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.OutboxLane;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox 通道配置边界（ADR-0023 第 7.3 节）。
 *
 * <p>配置错误必须在启动期失败：空通道、未知通道、重复通道都会让事件无人领取或双领取。
 * 重通道并发与最大处理时长同样有界，避免单实例吃满资源或事件永久占用租约。
 */
class OutboxLanePropertiesTest {

    /** 默认 default+heavy、并发 2、default 最大处理 30 分钟、heavy 65 分钟。 */
    @Test
    @DisplayName("通道配置默认值符合 ADR")
    void defaults_matchAdr() {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.afterPropertiesSet();
        assertThat(properties.enabledLanes()).containsExactly(OutboxLane.DEFAULT, OutboxLane.HEAVY);
        assertThat(properties.heavyConcurrency()).isEqualTo(2);
        assertThat(properties.maxProcessing()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.maxProcessing(OutboxLane.DEFAULT)).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.maxProcessing(OutboxLane.HEAVY)).isEqualTo(Duration.ofMinutes(65));
        assertThat(properties.maxProcessing(null)).as("旧的全事件领取沿用 default 值").isEqualTo(Duration.ofMinutes(30));
    }

    /** 两个通道的处理时长都可以独立配置，互不覆盖。 */
    @Test
    @DisplayName("按通道配置最大处理时长")
    void perLaneMaxProcessing_isConfigurable() {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.setMaxProcessing(Duration.ofMinutes(12));
        properties.setHeavyMaxProcessing(Duration.ofMinutes(70));
        properties.afterPropertiesSet();
        assertThat(properties.maxProcessing(OutboxLane.DEFAULT)).isEqualTo(Duration.ofMinutes(12));
        assertThat(properties.maxProcessing(OutboxLane.HEAVY)).isEqualTo(Duration.ofMinutes(70));
        assertThat(properties.maxProcessing(null)).isEqualTo(Duration.ofMinutes(12));
    }

    /** 允许只启用 heavy（分实例部署），未覆盖事件由调度器日志提示。 */
    @Test
    @DisplayName("允许只启用 heavy 通道")
    void singleLane_isAllowed() {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.setLanes(List.of("HEAVY"));
        properties.afterPropertiesSet();
        assertThat(properties.enabledLanes()).containsExactly(OutboxLane.HEAVY);
    }

    /** 空/未知/重复通道必须启动失败。 */
    @Test
    @DisplayName("非法通道配置启动失败")
    void invalidLanes_failFast() {
        OutboxLaneProperties empty = new OutboxLaneProperties();
        empty.setLanes(List.of());
        assertThatThrownBy(empty::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one lane");

        OutboxLaneProperties unknown = new OutboxLaneProperties();
        unknown.setLanes(List.of("default", "background"));
        assertThatThrownBy(unknown::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown lanes");

        OutboxLaneProperties duplicate = new OutboxLaneProperties();
        duplicate.setLanes(List.of("default", "DEFAULT"));
        assertThatThrownBy(duplicate::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate lane");
    }

    /** 并发与最大处理时长必须有界。 */
    @Test
    @DisplayName("并发与处理时长越界启动失败")
    void invalidLimits_failFast() {
        OutboxLaneProperties zeroConcurrency = new OutboxLaneProperties();
        zeroConcurrency.setHeavyConcurrency(0);
        assertThatThrownBy(zeroConcurrency::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heavy-concurrency");

        OutboxLaneProperties tooMany = new OutboxLaneProperties();
        tooMany.setHeavyConcurrency(9);
        assertThatThrownBy(tooMany::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heavy-concurrency");

        OutboxLaneProperties zeroMax = new OutboxLaneProperties();
        zeroMax.setMaxProcessing(Duration.ZERO);
        assertThatThrownBy(zeroMax::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-processing");

        OutboxLaneProperties negativeMax = new OutboxLaneProperties();
        negativeMax.setMaxProcessing(Duration.ofSeconds(-1));
        assertThatThrownBy(negativeMax::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-processing");

        OutboxLaneProperties zeroHeavyMax = new OutboxLaneProperties();
        zeroHeavyMax.setHeavyMaxProcessing(Duration.ZERO);
        assertThatThrownBy(zeroHeavyMax::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heavy-max-processing");

        OutboxLaneProperties negativeHeavyMax = new OutboxLaneProperties();
        negativeHeavyMax.setHeavyMaxProcessing(Duration.ofSeconds(-1));
        assertThatThrownBy(negativeHeavyMax::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heavy-max-processing");
    }
}
