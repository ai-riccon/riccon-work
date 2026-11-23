package com.riccon.api.knowledge.connector.service;

import com.riccon.api.common.UuidV7;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 连接器同步事件投递。
 *
 * <p>API 只受理并写 outbox，实际同步由 Worker 在 heavy 通道执行；事件负载只有 {@code connectorId}/{@code syncId}/
 * {@code startedAt}，幂等键 {@code connector:{id}:sync:{syncId}}（ADR-0023 第 7.3 节）。运行类别复用
 * {@code KNOWLEDGE_INDEX}，因为同步属于知识库流水线且 {@code agent_run} 的 kind 集合是封闭的。
 */
@Component
public class ConnectorSyncEvents {

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** Outbox 仓储。 */
    private final OutboxRepository outboxRepository;

    /**
     * 创建事件投递器。
     *
     * @param runRepository 运行仓储
     * @param outboxRepository outbox 仓储
     */
    public ConnectorSyncEvents(RunRepository runRepository, OutboxRepository outboxRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
    }

    /**
     * 受理一次手动同步。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param syncId 同步 ID
     * @param actorId 发起人
     * @param trigger 触发方式
     * @param traceId 追踪 ID
     * @param now 受理时间
     * @return 事件 ID
     */
    public UUID enqueue(
            TenantId tenantId,
            UUID connectorId,
            UUID syncId,
            UUID actorId,
            ConnectorSyncTrigger trigger,
            String traceId,
            Instant now) {
        UUID runId = UuidV7.generate(now);
        UUID eventId = UuidV7.generate(now);
        runRepository.createPending(
                tenantId,
                runId,
                com.riccon.platform.domain.research.RunKind.KNOWLEDGE_INDEX,
                null,
                actorId,
                traceId,
                now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectorId", connectorId.toString());
        payload.put("syncId", syncId.toString());
        payload.put("startedAt", DateTimeFormatter.ISO_INSTANT.format(now));
        payload.put("trigger", trigger.name());
        OutboxEvent event = new OutboxEvent(
                eventId,
                runId,
                EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED,
                1,
                "connector:" + connectorId + ":sync:" + syncId,
                payload,
                now);
        outboxRepository.enqueue(tenantId, event);
        return eventId;
    }
}
