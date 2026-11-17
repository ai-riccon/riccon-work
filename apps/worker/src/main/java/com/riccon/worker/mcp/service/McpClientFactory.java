package com.riccon.worker.mcp.service;

import com.riccon.worker.config.McpProperties;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpServerDescriptor;
import com.riccon.worker.mcp.domain.StdioCommand;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端工厂。
 *
 * <p>每次调用创建并初始化一个客户端，调用结束即关闭，避免跨租户复用连接与工具缓存。
 * HTTP 只允许 HTTPS（本地测试可显式允许 HTTP）；stdio 默认拒绝，仅测试/开发开启。
 */
@Component
public class McpClientFactory {

    /** MCP 协议版本，与测试夹具一致。 */
    static final String PROTOCOL_VERSION = "2024-11-05";

    /** MCP 配置。 */
    private final McpProperties properties;

    /**
     * 创建客户端工厂。
     *
     * @param properties MCP 配置
     */
    public McpClientFactory(McpProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建并初始化 MCP 客户端。
     *
     * @param server server 目录快照
     * @return 已初始化客户端；调用方必须关闭
     * @throws McpException 配置不合法或初始化失败
     */
    public McpClientWrapper create(McpServerDescriptor server) {
        McpClientBuilder builder = McpClientBuilder.create(server.code())
                .timeout(properties.requestTimeout())
                .initializationTimeout(properties.initializationTimeout())
                .protocolVersions(PROTOCOL_VERSION);
        try {
            switch (server.transport()) {
                case HTTP -> configureHttp(builder, server);
                case STDIO -> configureStdio(builder, server);
            }
            McpClientWrapper client = builder.buildSync();
            client.initialize().block(properties.initializationTimeout());
            return client;
        } catch (McpException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new McpException(McpException.UNAVAILABLE, true, "mcp client initialization failed", ex);
        }
    }

    /**
     * 配置 HTTP 传输。
     *
     * @param builder 客户端构建器
     * @param server server 快照
     */
    private void configureHttp(McpClientBuilder builder, McpServerDescriptor server) {
        URI endpoint = server.endpoint();
        if (endpoint == null || endpoint.getHost() == null) {
            throw new McpException(McpException.DENIED, false, "mcp server endpoint is missing");
        }
        if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new McpException(McpException.DENIED, false, "mcp server endpoint contains forbidden parts");
        }
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean insecureHttp = "http".equals(scheme) && properties.allowInsecureHttp();
        if (!https && !insecureHttp) {
            throw new McpException(McpException.DENIED, false, "mcp server endpoint must use https");
        }
        builder.streamableHttpTransport(endpoint.toString());
        if (server.credentialRef() != null && !server.credentialRef().isBlank()) {
            String token = System.getenv(server.credentialRef());
            if (token == null || token.isBlank()) {
                throw new McpException(McpException.DENIED, false, "mcp credential environment variable is missing");
            }
            builder.header("Authorization", "Bearer " + token);
        }
    }

    /**
     * 配置 stdio 传输。
     *
     * @param builder 客户端构建器
     * @param server server 快照
     */
    private void configureStdio(McpClientBuilder builder, McpServerDescriptor server) {
        if (!properties.allowStdio()) {
            throw new McpException(McpException.DENIED, false, "stdio mcp transport is disabled");
        }
        List<String> command;
        try {
            command = StdioCommand.split(server.command());
        } catch (IllegalArgumentException ex) {
            throw new McpException(McpException.DENIED, false, "stdio mcp command is invalid");
        }
        if (command.isEmpty()) {
            throw new McpException(McpException.DENIED, false, "stdio mcp command is empty");
        }
        builder.stdioTransport(command.get(0), command.subList(1, command.size()), Map.of());
    }
}
