package com.riccon.api.knowledge.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单个租户的保留到期清理。
 *
 * <p>条件 {@code status IN ('INDEXED','INDEXING','FAILED','PENDING') AND deleted_at IS NULL} 且到期，批量 500；
 * 只软删除，不物理删除对象与分块（M09 承接）。已索引或仍有分块的文档投递 {@code mode=DELETE} 清理索引；
 * 未索引且无分块的文档只软删除，不产生无效索引 DELETE。审计 {@code reason=RETENTION} 与业务同事务，
 * 失败即整体回滚（ADR-0023 第 10 节）。
 *
 * <p>每个租户独立事务：某个租户审计/队列失败不会阻塞其他租户；本租户下一次调度重试。
 */
@Service
public class KnowledgeRetentionProcessor {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeRetentionProcessor.class);

    /** 单批上限（ADR-0023 第 10 节）。 */
    public static final int BATCH_SIZE = 500;

    /** 单次调度最多处理的批次数，避免异常数据导致无限循环。 */
    private static final int MAX_BATCHES = 20;

    /** 知识库仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 索引事件投递。 */
    private final KnowledgeIndexEvents indexEvents;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建保留处理器。
     *
     * @param knowledgeRepository 知识库仓储
     * @param auditService 审计写入
     * @param indexEvents 索引事件投递
     * @param clock 可注入时钟
     */
    public KnowledgeRetentionProcessor(
            KnowledgeRepository knowledgeRepository,
            AuditService auditService,
            KnowledgeIndexEvents indexEvents,
            Clock clock) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.indexEvents = Objects.requireNonNull(indexEvents, "indexEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 清理指定租户的到期文档。
     *
     * <p>历史 {@code retention_expires_at IS NULL} 行固定按创建后 180 天计算，不使用当前租户策略，
     * 避免策略调低后追溯删除升级前文档（ADR-0023 第 10 节）。
     *
     * @param tenantId 当前租户
     * @return 实际软删除的文档数
     */
    @Transactional
    public int expireTenant(TenantId tenantId) {
        Instant now = clock.instant();
        Instant createdCutoff = now.minus(Duration.ofDays(KnowledgeDocumentRecord.DEFAULT_RETENTION_DAYS));
        int expired = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<KnowledgeDocumentRecord> documents =
                    knowledgeRepository.findExpired(tenantId, createdCutoff, now, BATCH_SIZE);
            if (documents.isEmpty()) {
                break;
            }
            for (KnowledgeDocumentRecord document : documents) {
                expired += expireOne(tenantId, document, now);
            }
            if (documents.size() < BATCH_SIZE) {
                break;
            }
        }
        if (expired > 0) {
            LOG.info("knowledge.retention.expired tenantId={} documents={}", tenantId.value(), expired);
        }
        return expired;
    }

    /**
     * 软删除单个文档并按需清理索引。
     *
     * @param tenantId 当前租户
     * @param document 到期文档
     * @param now 当前时间
     * @return 实际删除时返回 1，否则 0
     */
    private int expireOne(TenantId tenantId, KnowledgeDocumentRecord document, Instant now) {
        // 只有已索引或已有分块的文档才需要清理索引；PENDING/FAILED 且无分块不产生无效 DELETE。
        boolean indexed = document.status() == KnowledgeDocumentStatus.INDEXED
                || knowledgeRepository.hasChunks(tenantId, document.id());
        if (knowledgeRepository.softDelete(tenantId, document.id(), now) == 0) {
            // 其他实例已处理；条件更新保证多实例幂等。
            return 0;
        }
        auditService.append(KnowledgeAuditEvents.deleted(
                now,
                tenantId.value(),
                AuditActorType.SYSTEM,
                "system",
                document,
                "RETENTION",
                "knowledge-retention",
                null));
        if (indexed) {
            Optional<UUID> actorId = document.createdBy() == null
                    ? knowledgeRepository.findFirstActiveUserId(tenantId)
                    : Optional.of(document.createdBy());
            if (actorId.isPresent()) {
                indexEvents.enqueue(
                        tenantId,
                        document,
                        KnowledgeIndexMode.DELETE,
                        knowledgeRepository.nextIndexAttempt(tenantId, document.id()),
                        false,
                        document.aclVersion(),
                        "RETENTION",
                        actorId.get(),
                        "knowledge-retention",
                        now);
            } else {
                // 租户没有任何启用账号时无法建立 run（FK），索引清理留待 M09 维护任务兜底。
                LOG.warn(
                        "knowledge.retention.index_cleanup_skipped tenantId={} documentId={} reason=no_active_user",
                        tenantId.value(),
                        document.id());
            }
        }
        return 1;
    }
}
