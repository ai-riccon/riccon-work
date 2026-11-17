package com.riccon.api.chat.dto;

import java.util.UUID;

/**
 * 停止生成请求，对应 OpenAPI {@code StopRunRequest}。
 *
 * @param runId 要停止的运行 ID，来自 started 事件
 */
public record StopRunRequest(UUID runId) {}
