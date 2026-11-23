package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.repository.ConnectorRepository;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 侧连接器事件发布：索引事件、定时同步与同步重试事件。
 *
 * <p>每个事件在同一短事务内写入 {@code agent_run}、{@code agent_run_outbox} 与发起审计，避免孤儿运行或
 * “事件已排队但审计缺失”。payload 只含 ID/模式/时间，不含对象 key、bucket 或凭据；事件幂等键与 API 一致，
 * 重复投递由唯一键收敛。
 */
@Component
public class ConnectorEventPublisher {

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** 审计写入。 */
    private final ConnectorAuditWriter auditWriter;

    /**
     * 创建发布器。
     *
     * @param connectorRepository 仓储
     * @param objectMapper JSON 序列化
     * @param auditWriter 审计写入
     */
    public ConnectorEventPublisher(
            ConnectorRepository connectorRepository, ObjectMapper objectMapper, ConnectorAuditWriter auditWriter) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * 发布全文重索引事件。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param aclVersion ACL 版本
     * @param actorId 发起人
     * @param traceId 追踪 ID
     * @param now 事件时间
     * @return 事件 ID
     */
    @Transactional
    public UUID enqueueIndex(
            TenantId tenantId, UUID documentId, int aclVersion, UUID actorId, String traceId, Instant now) {
        UUID runId = UuidV7.generate();
        UUID eventId = UuidV7.generate();
        int attempt = connectorRepository.nextIndexAttempt(tenantId, documentId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("attempt", attempt);
        payload.put("mode", KnowledgeIndexMode.FULL.name());
        payload.put("aclVersion", aclVersion);
        connectorRepository.insertRun(tenantId, runId, actorId, traceId, now);
        connectorRepository.insertOutboxEvent(
                tenantId,
                eventId,
                runId,
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                "doc:" + documentId + ":index:" + attempt,
                toJson(payload),
                now,
                now);
        return eventId;
    }

    /**
     * 发布删除索引事件；重复删除事件由唯一键收敛。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param aclVersion ACL 版本
     * @param actorId 发起人
     * @param traceId 追踪 ID
     * @param now 事件时间
     * @return 事件 ID
     */
    @Transactional
    public UUID enqueueDelete(
            TenantId tenantId, UUID documentId, int aclVersion, UUID actorId, String traceId, Instant now) {
        if (connectorRepository.hasDeleteIndexEvent(tenantId, documentId, aclVersion)) {
            // 事件已存在（API 删除连接器或上一轮传播已投递）：视为成功，不重复插入。
            return null;
        }
        UUID runId = UuidV7.generate();
        UUID eventId = UuidV7.generate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("attempt", 1);
        payload.put("mode", KnowledgeIndexMode.DELETE.name());
        payload.put("aclVersion", aclVersion);
        payload.put("reason", "CONNECTOR");
        connectorRepository.insertRun(tenantId, runId, actorId, traceId, now);
        connectorRepository.insertOutboxEvent(
                tenantId,
                eventId,
                runId,
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                "doc:" + documentId + ":delete:" + aclVersion,
                toJson(payload),
                now,
                now);
        return eventId;
    }

    /**
     * 发布定时同步事件；发起审计与事件同事务写入。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @param syncId 同步 ID
     * @param trigger 触发方式
     * @param actorId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     * @return 事件 ID
     */
    @Transactional
    public UUID enqueueSync(
            TenantId tenantId,
            ConnectorRecord connector,
            UUID syncId,
            ConnectorSyncTrigger trigger,
            UUID actorId,
            String traceId,
            Instant now) {
        UUID runId = UuidV7.generate();
        UUID eventId = UuidV7.generate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectorId", connector.id().toString());
        payload.put("syncId", syncId.toString());
        payload.put("startedAt", DateTimeFormatter.ISO_INSTANT.format(now));
        connectorRepository.insertRun(tenantId, runId, actorId, traceId, now);
        connectorRepository.insertOutboxEvent(
                tenantId,
                eventId,
                runId,
                EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED,
                "connector:" + connector.id() + ":sync:" + syncId,
                toJson(payload),
                now,
                now);
        auditWriter.syncStarted(tenantId, connector.id(), syncId, trigger, traceId);
        return eventId;
    }

    /**
     * 发布同步重试事件，按 1m/5m/15m 的可用时间投递；重试审计与事件同事务写入。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @param syncId 同步 ID
     * @param attempt 下一次尝试序号（1 开始）
     * @param reason 失败分类
     * @param availableAt 最早可领取时间
     * @param actorId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     * @return 事件 ID
     */
    @Transactional
    public UUID enqueueRetry(
            TenantId tenantId,
            ConnectorRecord connector,
            UUID syncId,
            int attempt,
            String reason,
            Instant availableAt,
            UUID actorId,
            String traceId,
            Instant now) {
        UUID runId = UuidV7.generate();
        UUID eventId = UuidV7.generate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectorId", connector.id().toString());
        payload.put("syncId", syncId.toString());
        payload.put("startedAt", DateTimeFormatter.ISO_INSTANT.format(now));
        connectorRepository.insertRun(tenantId, runId, actorId, traceId, now);
        connectorRepository.insertOutboxEvent(
                tenantId,
                eventId,
                runId,
                EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED,
                "connector:" + connector.id() + ":sync:" + syncId + ":retry:" + attempt,
                toJson(payload),
                availableAt,
                now);
        auditWriter.syncRetried(tenantId, connector.id(), syncId, attempt, reason, traceId);
        return eventId;
    }

    /**
     * 序列化 payload。
     *
     * @param payload payload
     * @return JSON
     */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize connector outbox payload", exception);
        }
    }
}
