package com.riccon.worker.connector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.run.config.OutboxLaneProperties;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * ConnectorProperties 启动校验分支矩阵：每个配置上限/下限与空值都必须 fail-fast，合法组合可通过。
 */
class ConnectorPropertiesBranchTest {

    @Test
    void afterPropertiesSet_rejectsEveryInvalidBoundary() {
        assertInvalid(properties -> properties.setSyncIntervalMinutes(0));
        assertInvalid(properties -> properties.setSyncIntervalMinutes(10_081));
        assertInvalid(properties -> properties.setSchedulerInitialDelay(null));
        assertInvalid(properties -> properties.setSchedulerInitialDelay(Duration.ofSeconds(-1)));
        assertInvalid(properties -> properties.setSchedulerFixedDelay(null));
        assertInvalid(properties -> properties.setSchedulerFixedDelay(Duration.ZERO));
        assertInvalid(properties -> properties.setSchedulerFixedDelay(Duration.ofSeconds(-1)));
        assertInvalid(properties -> properties.setSchedulerInitialDelayMs(-1));
        assertInvalid(properties -> properties.setSchedulerFixedDelayMs(0));
        assertInvalid(properties -> properties.setMaxObjectBytes(0));
        assertInvalid(properties -> properties.setMaxObjectBytes(100L * 1024 * 1024 + 1));
        assertInvalid(properties -> properties.setMaxObjects(0));
        assertInvalid(properties -> properties.setMaxObjects(5_001));
        assertInvalid(properties -> properties.setMaxTotalBytes(0));
        assertInvalid(properties -> properties.setMaxTotalBytes(2L * 1024 * 1024 * 1024 + 1));
        assertInvalid(properties -> properties.setObjectTimeout(null));
        assertInvalid(properties -> properties.setObjectTimeout(Duration.ZERO));
        assertInvalid(properties -> properties.setObjectTimeout(Duration.ofSeconds(-1)));
        assertInvalid(properties -> properties.setSyncTimeout(null));
        assertInvalid(properties -> properties.setSyncTimeout(Duration.ZERO));
        assertInvalid(properties -> properties.setSyncTimeout(Duration.ofSeconds(-1)));
        assertInvalid(properties -> properties.setDefaultRegion(null));
        assertInvalid(properties -> properties.setDefaultRegion(" "));
        assertInvalid(properties -> properties.setRetryDelays(null));
        assertInvalid(properties -> properties.setRetryDelays(List.of()));
    }

    @Test
    void afterPropertiesSet_validatesHeavyWatchdogOnlyWhenEnabled() {
        OutboxLaneProperties shortLanes = lanes(Duration.ofMinutes(30));
        ConnectorProperties enabled = new ConnectorProperties(new ObjectMapper(), shortLanes);
        enabled.setEnabled(true);
        enabled.setEndpoints("[]");
        enabled.setSyncTimeout(Duration.ofMinutes(60));
        assertThatThrownBy(enabled::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heavy-max-processing");

        OutboxLaneProperties longLanes = lanes(Duration.ofMinutes(65));
        ConnectorProperties valid = new ConnectorProperties(new ObjectMapper(), longLanes);
        valid.setEnabled(true);
        valid.setEndpoints("[]");
        valid.setSyncTimeout(Duration.ofMinutes(60));
        valid.setRetryDelays(List.of(Duration.ofMinutes(1)));
        valid.afterPropertiesSet();
        assertThat(valid.registry().isEmpty()).isTrue();
        assertThat(valid.retryDelays()).containsExactly(Duration.ofMinutes(1));
    }

    @Test
    void afterPropertiesSet_parsesNullBlankEmptyAndInvalidEndpointJson() {
        ConnectorProperties nullEndpoints = properties();
        nullEndpoints.setEndpoints(null);
        nullEndpoints.afterPropertiesSet();
        assertThat(nullEndpoints.registry().isEmpty()).isTrue();

        ConnectorProperties blankEndpoints = properties();
        blankEndpoints.setEndpoints("   ");
        blankEndpoints.afterPropertiesSet();
        assertThat(blankEndpoints.registry().isEmpty()).isTrue();

        ConnectorProperties emptyEndpoints = properties();
        emptyEndpoints.setEndpoints("[]");
        emptyEndpoints.afterPropertiesSet();
        assertThat(emptyEndpoints.registry().isEmpty()).isTrue();

        ConnectorProperties invalid = properties();
        invalid.setEndpoints("not-json");
        assertThatThrownBy(invalid::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid JSON array");
    }

    /**
     * 断言某个非法配置在启动校验时失败。
     *
     * @param mutation 配置修改
     */
    private static void assertInvalid(Consumer<ConnectorProperties> mutation) {
        ConnectorProperties properties = properties();
        mutation.accept(properties);
        assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 构造合法基础配置。
     *
     * @return 配置
     */
    private static ConnectorProperties properties() {
        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), lanes(Duration.ofMinutes(65)));
        properties.setEndpoints("[]");
        return properties;
    }

    /**
     * 构造通道配置。
     *
     * @param heavy 重通道看门狗
     * @return 通道配置
     */
    private static OutboxLaneProperties lanes(Duration heavy) {
        OutboxLaneProperties lanes = new OutboxLaneProperties();
        lanes.setHeavyMaxProcessing(heavy);
        return lanes;
    }
}
