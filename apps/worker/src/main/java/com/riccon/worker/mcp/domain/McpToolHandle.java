package com.riccon.worker.mcp.domain;

/**
 * 已解析的工具与 server 组合。
 *
 * @param tool 工具目录
 * @param server 所属 server 目录
 */
public record McpToolHandle(McpToolDescriptor tool, McpServerDescriptor server) {}
