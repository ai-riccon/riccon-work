package com.riccon.api.knowledge.service;

import com.riccon.api.common.UuidV7;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
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
 * 知识库索引事件投递。
 *
 * <p>payload 只包含文档 ID、来源文件、尝试号、模式与 ACL 版本；不包含原文、对象 key 或凭据。
 * 幂等键按 ADR-0023 第 7.3 节：{@code doc:{id}:index:{attempt}}、{@code doc:{id}:acl:{aclVersion}}、
 * {@code doc:{id}:delete:{aclVersion}}。API 只写事件并返回 202，不在 HTTP 线程执行解析。
 */
@Component
public class KnowledgeIndexEvents {

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** Outbox 仓储。 */
    private final OutboxRepository outboxRepository;

    /**
     * 创建索引事件投递器。
     *
     * @param runRepository 运行仓储
     * @param outboxRepository outbox 仓储
     */
    public KnowledgeIndexEvents(RunRepository runRepository, OutboxRepository outboxRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
    }

    /**
     * 投递索引事件。
     *
     * @param tenantId 当前租户
     * @param document 文档快照（ACL_ONLY/DELETE 使用 aclVersion）
     * @param mode 索引模式
     * @param attempt 尝试号
     * @param force 是否整本重做；仅 {@code FULL} 有意义
     * @param aclVersion ACL 版本；{@code ACL_ONLY}/{@code DELETE} 使用
     * @param reason 删除/重索引原因；可空
     * @param actorId 发起人
     * @param traceId 追踪 ID
     * @param now 事件时间
     * @return 事件 ID
     */
    public UUID enqueue(
            TenantId tenantId,
            KnowledgeDocumentRecord document,
            KnowledgeIndexMode mode,
            int attempt,
            boolean force,
            int aclVersion,
            String reason,
            UUID actorId,
            String traceId,
            Instant now) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        UUID runId = UuidV7.generate(now);
        UUID eventId = UuidV7.generate(now);
        runRepository.createPending(tenantId, runId, RunKind.KNOWLEDGE_INDEX, null, actorId, traceId, now);
        OutboxEvent event = new OutboxEvent(
                eventId,
                runId,
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                1,
                idempotencyKey(document.id(), mode, attempt, aclVersion),
                payload(document, mode, attempt, force, aclVersion, reason),
                now);
        outboxRepository.enqueue(tenantId, event);
        return eventId;
    }

    /**
     * 构造幂等键。
     *
     * @param documentId 文档 ID
     * @param mode 模式
     * @param attempt 尝试号
     * @param aclVersion ACL 版本
     * @return 幂等键
     */
    private static String idempotencyKey(UUID documentId, KnowledgeIndexMode mode, int attempt, int aclVersion) {
        return switch (mode) {
            case FULL -> "doc:" + documentId + ":index:" + attempt;
            case ACL_ONLY -> "doc:" + documentId + ":acl:" + aclVersion;
            case DELETE -> "doc:" + documentId + ":delete:" + aclVersion;
        };
    }

    /**
     * 构造脱敏 payload。
     *
     * @param document 文档
     * @param mode 模式
     * @param attempt 尝试号
     * @param force 是否整本重做
     * @param aclVersion ACL 版本
     * @param reason 原因
     * @return payload
     */
    private static Map<String, Object> payload(
            KnowledgeDocumentRecord document,
            KnowledgeIndexMode mode,
            int attempt,
            boolean force,
            int aclVersion,
            String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", document.id().toString());
        if (document.fileId() != null) {
            payload.put("fileId", document.fileId().toString());
        }
        payload.put("attempt", attempt);
        payload.put("mode", mode.name());
        payload.put("aclVersion", aclVersion);
        if (mode == KnowledgeIndexMode.FULL) {
            payload.put("force", force);
        }
        if (reason != null) {
            payload.put("reason", reason);
        }
        return payload;
    }
}
