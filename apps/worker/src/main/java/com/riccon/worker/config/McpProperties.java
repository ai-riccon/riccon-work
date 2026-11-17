package com.riccon.worker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP 工具调用配置。
 *
 * <p>M02 只允许平台内置白名单中的只读工具，且每次调用都必须经过人工审批（ADR-0008、THREAT-MODEL）。
 * HTTP 是生产传输；stdio 只允许在测试/开发显式开启，避免租户工具启动本地进程。
 *
 * @param allowedServerCodes 允许的 server code 白名单，数据库中其他 server 一律拒绝
 * @param allowStdio 是否允许 stdio 传输；生产必须为 false
 * @param allowInsecureHttp 是否允许 http MCP 端点；仅本地测试可开启
 * @param requestTimeout 单次工具调用超时
 * @param initializationTimeout MCP 初始化与工具发现超时
 * @param maxResultBytes 允许返回给模型的结果大小上限
 * @param paramsPreviewMaxLength 入参脱敏预览的最大长度
 */
@ConfigurationProperties(prefix = "riccon.worker.mcp")
public record McpProperties(
        @DefaultValue("riccon-builtin") List<String> allowedServerCodes,
        @DefaultValue("false") boolean allowStdio,
        @DefaultValue("false") boolean allowInsecureHttp,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("15s") Duration initializationTimeout,
        @DefaultValue("65536") int maxResultBytes,
        @DefaultValue("500") int paramsPreviewMaxLength) {

    /** 校验白名单与超时，禁止空白名单或无限等待。 */
    public McpProperties {
        if (allowedServerCodes == null || allowedServerCodes.isEmpty()) {
            throw new IllegalArgumentException("riccon.worker.mcp.allowed-server-codes must not be empty");
        }
        allowedServerCodes = List.copyOf(allowedServerCodes);
        requirePositive(requestTimeout, "request-timeout");
        requirePositive(initializationTimeout, "initialization-timeout");
        if (maxResultBytes < 1024 || maxResultBytes > 1048576) {
            throw new IllegalArgumentException("riccon.worker.mcp.max-result-bytes must be between 1024 and 1048576");
        }
        if (paramsPreviewMaxLength < 32 || paramsPreviewMaxLength > 1000) {
            throw new IllegalArgumentException(
                    "riccon.worker.mcp.params-preview-max-length must be between 32 and 1000");
        }
    }

    /** 校验时长为正。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.mcp." + name + " must be positive");
        }
    }
}
