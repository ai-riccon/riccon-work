package com.riccon.worker.connector.domain;

import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次同步请求。
 *
 * @param connectorId 连接器 ID
 * @param syncId 同步 ID
 * @param startedAt 事件声明的开始时间
 * @param trigger 触发方式
 * @param retryAttempt 重试序号；0 表示首次
 */
public record ConnectorSyncRequest(
        UUID connectorId, UUID syncId, Instant startedAt, ConnectorSyncTrigger trigger, int retryAttempt) {}
