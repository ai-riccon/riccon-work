package com.riccon.platform.domain.mcp;

/**
 * MCP 传输方式。
 *
 * <p>M02 生产路径只允许 HTTP（TLS、允许列表）；stdio 仅用于本地冒烟，不进入租户可用工具目录。
 */
public enum McpTransport {
    /** 流式 HTTP（集群内或受控出网）。 */
    HTTP,
    /** 本地子进程，仅测试/开发。 */
    STDIO
}
