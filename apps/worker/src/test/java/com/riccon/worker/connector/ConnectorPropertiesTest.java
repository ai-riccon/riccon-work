package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.run.config.OutboxLaneProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接器配置启动校验：heavy 看门狗必须覆盖同步总超时。
 */
class ConnectorPropertiesTest {

    @Test
    void afterPropertiesSet_requiresHeavyWatchdogLongerThanSyncTimeout() {
        OutboxLaneProperties lanes = new OutboxLaneProperties();
        lanes.setHeavyMaxProcessing(Duration.ofMinutes(30));
        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), lanes);
        properties.setEnabled(true);
        properties.setEndpoints("[]");
        properties.setSyncTimeout(Duration.ofMinutes(60));
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heavy-max-processing");
    }

    @Test
    void afterPropertiesSet_acceptsDefaultHeavyWatchdogAndInvalidEndpointFails() {
        OutboxLaneProperties lanes = new OutboxLaneProperties();
        lanes.setHeavyMaxProcessing(Duration.ofMinutes(65));
        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), lanes);
        properties.setEnabled(true);
        properties.setEndpoints("[]");
        properties.setSyncTimeout(Duration.ofMinutes(60));
        properties.afterPropertiesSet();
        assertThat(properties.registry().isEmpty()).isTrue();

        ConnectorProperties invalid = new ConnectorProperties(new ObjectMapper(), lanes);
        invalid.setEndpoints("not-json");
        assertThatThrownBy(invalid::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid JSON array");
    }
}
