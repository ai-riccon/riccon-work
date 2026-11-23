package com.riccon.api.knowledge.connector.service;

import com.riccon.api.common.UuidV7;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import com.riccon.platform.domain.research.RunKind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 连接器文档索引事件投递。
 *
 * <p>连接器删除传播只写 {@code mode=DELETE} 事件；B7b 才实现连接器对象的索引读取分支，本任务不修改
 * {@code KnowledgeIndexService}/{@code S3FileContentReader}。事件 payload 只含文档 ID、尝试号、模式与
 * ACL 版本，不含对象 key、bucket 或凭据。
 */
@Component
public class ConnectorIndexEvents {

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
    public ConnectorIndexEvents(RunRepository runRepository, OutboxRepository outboxRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
    }

    /**
     * 投递文档删除事件（连接器删除传播或连接器删除时的 SOFT_DELETE）。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param aclVersion ACL 版本
     * @param actorId 发起人
     * @param reason 原因（{@code CONNECTOR}）
     * @param traceId 追踪 ID
     * @param now 事件时间
     * @return 事件 ID
     */
    public UUID enqueueDelete(
            TenantId tenantId,
            UUID documentId,
            int aclVersion,
            UUID actorId,
            String reason,
            String traceId,
            Instant now) {
        UUID runId = UuidV7.generate(now);
        UUID eventId = UuidV7.generate(now);
        runRepository.createPending(tenantId, runId, RunKind.KNOWLEDGE_INDEX, null, actorId, traceId, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("attempt", 1);
        payload.put("mode", KnowledgeIndexMode.DELETE.name());
        payload.put("aclVersion", aclVersion);
        payload.put("reason", reason);
        OutboxEvent event = new OutboxEvent(
                eventId,
                runId,
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                1,
                "doc:" + documentId + ":delete:" + aclVersion,
                payload,
                now);
        outboxRepository.enqueue(tenantId, event);
        return eventId;
    }
}
