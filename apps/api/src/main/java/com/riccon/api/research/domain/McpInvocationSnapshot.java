package com.riccon.api.research.domain;

import com.riccon.platform.domain.mcp.McpInvocationStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MCP 调用摘要。
 *
 * <p>只暴露 hash、耗时与脱敏错误码；请求与响应原文不落库、不返回（V5 迁移注释）。
 *
 * @param id 调用 ID
 * @param toolName 工具名快照
 * @param status 调用状态
 * @param paramsSha256 入参 SHA-256
 * @param resultSha256 结果 SHA-256
 * @param durationMs 耗时毫秒
 * @param errorCode 失败/拒绝错误码
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 */
public record McpInvocationSnapshot(
        UUID id,
        String toolName,
        McpInvocationStatus status,
        String paramsSha256,
        String resultSha256,
        Long durationMs,
        String errorCode,
        Instant startedAt,
        Instant finishedAt) {

    /** 校验必填字段。 */
    public McpInvocationSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(paramsSha256, "paramsSha256 must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
