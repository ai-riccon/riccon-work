package com.riccon.worker.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.mcp.McpTransport;
import com.riccon.worker.config.McpProperties;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpServerDescriptor;
import com.riccon.worker.mcp.service.McpClientFactory;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MCP 客户端配置失败必须在连接前拒绝。 */
class McpClientFactoryTest {

    /** stdio 默认禁用。 */
    @Test
    void create_stdioDisabled_denied() {
        McpClientFactory factory = new McpClientFactory(properties(false, false));
        McpServerDescriptor server = server(McpTransport.STDIO, null, "java -version", null);

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(McpException.class)
                .satisfies(
                        error -> assertThat(((McpException) error).errorCode()).isEqualTo(McpException.DENIED));
    }

    /** HTTP 必须为 HTTPS（测试可显式允许）。 */
    @Test
    void create_httpWithoutTls_denied() {
        McpClientFactory factory = new McpClientFactory(properties(false, false));
        McpServerDescriptor server = server(McpTransport.HTTP, URI.create("http://example.com/mcp"), null, null);

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("https");
    }

    /** HTTP 端点缺失拒绝。 */
    @Test
    void create_httpWithoutEndpoint_denied() {
        McpClientFactory factory = new McpClientFactory(properties(false, false));
        McpServerDescriptor server = server(McpTransport.HTTP, null, null, null);

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("endpoint");
    }

    /** credential_ref 指向不存在的环境变量时拒绝。 */
    @Test
    void create_missingCredentialEnv_denied() {
        McpClientFactory factory = new McpClientFactory(properties(false, true));
        McpServerDescriptor server = server(
                McpTransport.HTTP,
                URI.create("http://example.com/mcp"),
                null,
                "RICCON_MCP_MISSING_" + UUID.randomUUID());

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("credential");
    }

    /** 非法 stdio 命令拒绝。 */
    @Test
    void create_invalidStdioCommand_denied() {
        McpClientFactory factory = new McpClientFactory(properties(true, false));
        McpServerDescriptor server = server(McpTransport.STDIO, null, "\"unterminated", null);

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("command");
    }

    /** 端点不能包含用户信息、查询或片段。 */
    @Test
    void create_endpointWithForbiddenParts_denied() {
        McpClientFactory factory = new McpClientFactory(properties(false, false));
        for (String endpoint : List.of(
                "https://user:pass@example.com/mcp", "https://example.com/mcp?x=1", "https://example.com/mcp#f")) {
            McpServerDescriptor server = server(McpTransport.HTTP, URI.create(endpoint), null, null);
            assertThatThrownBy(() -> factory.create(server))
                    .as("endpoint %s", endpoint)
                    .isInstanceOf(McpException.class);
        }
    }

    /** 构造配置。 */
    private static McpProperties properties(boolean allowStdio, boolean allowHttp) {
        return new McpProperties(
                List.of("riccon-builtin"),
                allowStdio,
                allowHttp,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                4096,
                100);
    }

    /** 构造 server 描述。 */
    private static McpServerDescriptor server(
            McpTransport transport, URI endpoint, String command, String credentialRef) {
        return new McpServerDescriptor(
                UUID.randomUUID(), "riccon-builtin", "Stub", transport, endpoint, command, credentialRef, true);
    }
}
