package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Worker 客户端配置校验。
 *
 * <p>覆盖 URL、密钥长度、令牌有效期与超时边界，确保错误配置在启动阶段失败而不是运行时静默降级。
 */
class WorkerClientPropertiesTest {

    /** 合法配置。 */
    @Test
    void validConfigurationIsAccepted() {
        assertThatCode(() -> new WorkerClientProperties(
                        "http://127.0.0.1:8081",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(300),
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WorkerClientProperties(
                        "https://worker.internal:8443/",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .doesNotThrowAnyException();
    }

    /** 地址必须是 http/https 且包含主机。 */
    @Test
    void invalidBaseUrlIsRejected() {
        assertThatThrownBy(() -> properties("", Duration.ofSeconds(300), "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base-url");
        assertThatThrownBy(() ->
                        properties("ftp://worker:21", Duration.ofSeconds(300), "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() ->
                        properties("http:///missing-host", Duration.ofSeconds(300), "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    /** 令牌有效期必须在 1–300 秒。 */
    @Test
    void tokenTtlIsBounded() {
        assertThatThrownBy(() -> properties("http://worker:8081", Duration.ZERO, "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-ttl");
        assertThatThrownBy(() ->
                        properties("http://worker:8081", Duration.ofSeconds(301), "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-ttl");
    }

    /** 共享密钥至少 32 字符。 */
    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> properties("http://worker:8081", Duration.ofSeconds(300), "too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service-token-secret");
    }

    /** 所有超时都必须为正。 */
    @Test
    void nonPositiveDurationsAreRejected() {
        assertThatThrownBy(() -> new WorkerClientProperties(
                        "http://worker:8081",
                        Duration.ZERO,
                        Duration.ofSeconds(300),
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10)))
                .hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new WorkerClientProperties(
                        "http://worker:8081",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(300),
                        "0123456789abcdef0123456789abcdef",
                        Duration.ZERO,
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10)))
                .hasMessageContaining("cancel-timeout");
        assertThatThrownBy(() -> new WorkerClientProperties(
                        "http://worker:8081",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(300),
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofSeconds(10),
                        Duration.ZERO,
                        Duration.ofMinutes(10)))
                .hasMessageContaining("idle-timeout");
        assertThatThrownBy(() -> new WorkerClientProperties(
                        "http://worker:8081",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(300),
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ZERO))
                .hasMessageContaining("max-duration");
    }

    /**
     * 构造仅变化地址与令牌有效期的配置。
     *
     * @param baseUrl 地址
     * @param ttl 令牌有效期
     * @param secret 密钥
     * @return 配置
     */
    private static WorkerClientProperties properties(String baseUrl, Duration ttl, String secret) {
        return new WorkerClientProperties(
                baseUrl,
                Duration.ofSeconds(5),
                ttl,
                secret,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10));
    }
}
