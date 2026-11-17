package com.riccon.worker.mcp.domain;

import com.riccon.platform.domain.mcp.McpTransport;
import java.net.URI;
import java.util.UUID;

/**
 * MCP server 目录快照。
 *
 * @param id server ID
 * @param code 内部稳定代码
 * @param name 展示名
 * @param transport 传输方式
 * @param endpoint HTTP 端点，STDIO 时为空
 * @param command stdio 命令，HTTP 时为空
 * @param credentialRef 凭据环境变量名，可为空
 * @param active 是否 ACTIVE
 */
public record McpServerDescriptor(
        UUID id,
        String code,
        String name,
        McpTransport transport,
        URI endpoint,
        String command,
        String credentialRef,
        boolean active) {}
