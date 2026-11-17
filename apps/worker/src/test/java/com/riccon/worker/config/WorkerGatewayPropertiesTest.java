package com.riccon.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 新增配置属性的边界校验。 */
class WorkerGatewayPropertiesTest {

    /** 合法配置可构造。 */
    @Test
    void validConfigs_construct() {
        assertThat(new CryptoProperties("a", "", "mk", true).masterKeyId()).isEqualTo("mk");
        assertThat(new ServiceTokenProperties("x".repeat(32), 300, 5).maxTokenAgeSeconds())
                .isEqualTo(300);
        assertThat(new ModelGatewayProperties(
                                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, List.of(Duration.ZERO), false, 1024)
                        .maxAttempts())
                .isEqualTo(3);
        assertThat(new ChatStreamProperties(16, Duration.ofSeconds(60), Duration.ofMinutes(10), 40)
                        .maxConcurrentStreams())
                .isEqualTo(16);
        assertThat(new McpProperties(
                                List.of("riccon-builtin"),
                                false,
                                false,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1),
                                1024,
                                100)
                        .allowedServerCodes())
                .hasSize(1);
        assertThat(new AgentProperties(4, 5, 5, 2000, 60).maxIterations()).isEqualTo(4);
    }

    /** 加密配置拒绝空白 key id。 */
    @Test
    void crypto_invalidKeyId_fails() {
        assertThatThrownBy(() -> new CryptoProperties("", "", " ", true)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 服务令牌拒绝短密钥与非法时间窗口。 */
    @Test
    void serviceToken_invalid_fails() {
        assertThatThrownBy(() -> new ServiceTokenProperties("short", 300, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenProperties("x".repeat(32), 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenProperties("x".repeat(32), 301, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenProperties("x".repeat(32), 300, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenProperties("x".repeat(32), 300, 31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 模型配置拒绝零超时、越界重试与空退避。 */
    @Test
    void model_invalid_fails() {
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ZERO, Duration.ofSeconds(1), 3, List.of(Duration.ZERO), false, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ofSeconds(1), Duration.ZERO, 3, List.of(Duration.ZERO), false, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 0, List.of(Duration.ZERO), false, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 6, List.of(Duration.ZERO), false, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3, List.of(), false, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3, List.of(Duration.ZERO), false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGatewayProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3, List.of(Duration.ZERO), false, 40000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 对话流配置拒绝越界并发与零超时。 */
    @Test
    void chat_invalid_fails() {
        assertThatThrownBy(() -> new ChatStreamProperties(0, Duration.ofSeconds(1), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatStreamProperties(129, Duration.ofSeconds(1), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatStreamProperties(1, Duration.ZERO, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatStreamProperties(1, Duration.ofSeconds(1), Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatStreamProperties(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatStreamProperties(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 201))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** MCP 配置拒绝空白名单与越界预算。 */
    @Test
    void mcp_invalid_fails() {
        assertThatThrownBy(() -> new McpProperties(
                        List.of(), false, false, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new McpProperties(List.of("a"), false, false, Duration.ZERO, Duration.ofSeconds(1), 1024, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new McpProperties(List.of("a"), false, false, Duration.ofSeconds(1), Duration.ZERO, 1024, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpProperties(
                        List.of("a"), false, false, Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpProperties(
                        List.of("a"), false, false, Duration.ofSeconds(1), Duration.ofSeconds(1), 2000000, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpProperties(
                        List.of("a"), false, false, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpProperties(
                        List.of("a"), false, false, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Agent 配置拒绝越界预算。 */
    @Test
    void agent_invalid_fails() {
        assertThatThrownBy(() -> new AgentProperties(0, 5, 5, 2000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(13, 5, 5, 2000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 0, 5, 2000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 21, 5, 2000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 5, 0, 2000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 5, 21, 2000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 5, 5, 100, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 5, 5, 9000, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 5, 5, 2000, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties(4, 5, 5, 2000, 201)).isInstanceOf(IllegalArgumentException.class);
    }
}
