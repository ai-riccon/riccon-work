package com.riccon.api.research.dto;

import com.riccon.api.research.domain.McpInvocationSnapshot;
import com.riccon.platform.domain.mcp.McpInvocationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * MCP 调用响应，对应 OpenAPI {@code McpInvocation}。
 *
 * @param id 调用 ID
 * @param toolName 工具名
 * @param status 状态
 * @param paramsSha256 入参 SHA-256
 * @param resultSha256 结果 SHA-256
 * @param durationMs 耗时毫秒
 * @param errorCode 错误码
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 */
public record McpInvocationResponse(
        UUID id,
        String toolName,
        McpInvocationStatus status,
        String paramsSha256,
        String resultSha256,
        Long durationMs,
        String errorCode,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 将调用快照映射为响应。
     *
     * @param invocation 调用快照
     * @return 响应
     */
    public static McpInvocationResponse from(McpInvocationSnapshot invocation) {
        return new McpInvocationResponse(
                invocation.id(),
                invocation.toolName(),
                invocation.status(),
                invocation.paramsSha256(),
                invocation.resultSha256(),
                invocation.durationMs(),
                invocation.errorCode(),
                invocation.startedAt(),
                invocation.finishedAt());
    }
}
