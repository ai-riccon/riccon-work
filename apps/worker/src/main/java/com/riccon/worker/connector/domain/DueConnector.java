package com.riccon.worker.connector.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 到期的定时同步连接器。
 *
 * @param tenantId 租户
 * @param connectorId 连接器 ID
 * @param dueAt 到期时间
 */
public record DueConnector(String tenantId, UUID connectorId, Instant dueAt) {}
