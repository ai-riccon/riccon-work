package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 对象存储配置校验。 */
class StoragePropertiesTest {

    /** 合法配置保留全部字段。 */
    @Test
    void constructor_valid_keepsValues() {
        StorageProperties properties = new StorageProperties(
                "http://127.0.0.1:19000", "us-east-1", "key", "secret", "bucket", true, Duration.ofSeconds(300));
        assertThat(properties.bucket()).isEqualTo("bucket");
        assertThat(properties.downloadUrlTtl()).isEqualTo(Duration.ofSeconds(300));
    }

    /** 空端点、空桶名与越界 TTL 启动即失败。 */
    @Test
    void constructor_invalid_throws() {
        assertThatThrownBy(() -> new StorageProperties(
                        " ", "us-east-1", "key", "secret", "bucket", true, Duration.ofSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> new StorageProperties(
                        "http://localhost", "us-east-1", "key", "secret", " ", true, Duration.ofSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
        assertThatThrownBy(() -> new StorageProperties(
                        "http://localhost", "us-east-1", "key", "secret", "bucket", true, Duration.ofSeconds(301)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("300s");
        assertThatThrownBy(() -> new StorageProperties(
                        "http://localhost", "us-east-1", "key", "secret", "bucket", true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
