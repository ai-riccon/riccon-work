package com.riccon.api.knowledge.connector.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 同步受理响应（OpenAPI {@code ConnectorSyncAccepted}）。
 *
 * @param syncId 同步 ID
 * @param connectorId 连接器 ID
 * @param status 受理状态，固定 {@code PENDING}
 * @param acceptedAt 受理时间
 */
public record ConnectorSyncAcceptedResponse(UUID syncId, UUID connectorId, String status, Instant acceptedAt) {}
