package com.riccon.api.knowledge.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.config.KnowledgeAclProperties;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.knowledge.domain.KnowledgeActor;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库文档删除与重索引。
 *
 * <p>删除只做软删除并投递 {@code mode=DELETE} 清理索引，不物理删除对象与分块（M09 承接）；重复删除幂等。
 * 重索引默认 {@code force=false} 只补缺失页，{@code force=true} 整本重做；正在索引或已有排队事件返回 409，
 * 队列不可用返回 503。判定顺序仍是“先 ACL 取文档，再判能力”（ADR-0023 第 5.4、7.3、10 节）。
 */
@Service
public class KnowledgeDocumentLifecycleService {

    /** 知识库仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 索引事件投递。 */
    private final KnowledgeIndexEvents indexEvents;

    /** ACL 配置。 */
    private final KnowledgeAclProperties aclProperties;

    /** 租户目录，用于读取保留天数。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建生命周期服务。
     *
     * @param knowledgeRepository 知识库仓储
     * @param auditService 审计写入
     * @param indexEvents 索引事件投递
     * @param aclProperties ACL 配置
     * @param tenantDirectoryRepository 租户目录
     * @param clock 可注入时钟
     */
    public KnowledgeDocumentLifecycleService(
            KnowledgeRepository knowledgeRepository,
            AuditService auditService,
            KnowledgeIndexEvents indexEvents,
            KnowledgeAclProperties aclProperties,
            TenantDirectoryRepository tenantDirectoryRepository,
            Clock clock) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.indexEvents = Objects.requireNonNull(indexEvents, "indexEvents must not be null");
        this.aclProperties = Objects.requireNonNull(aclProperties, "aclProperties must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 软删除文档并投递索引清理；重复删除幂等。
     *
     * @param actor 当前操作者
     * @param documentId 文档 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @throws NotFoundException 文档不可见或跨租户
     * @throws ForbiddenException 文档可见但不是创建人且无 ACL 管理权限
     */
    @Transactional
    public void delete(KnowledgeActor actor, UUID documentId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        var scope = knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        if (!knowledgeRepository.lockVisibleIdIncludingDeleted(tenantId, documentId, scope)) {
            throw new NotFoundException("knowledge document");
        }
        KnowledgeDocumentRecord document = knowledgeRepository
                .findVisibleByIdIncludingDeleted(tenantId, documentId, scope)
                .orElseThrow(() -> new NotFoundException("knowledge document"));
        requireManageCapability(actor, document);
        if (document.deletedAt() != null) {
            // 已软删除：创建人/管理员重复删除返回 204，不重复写审计或事件。
            return;
        }
        Instant now = clock.instant();
        if (knowledgeRepository.softDelete(tenantId, documentId, now) == 0) {
            // 并发删除已被其他事务完成；按幂等处理。
            return;
        }
        auditService.append(KnowledgeAuditEvents.deleted(
                now,
                actor.tenantId(),
                com.riccon.platform.domain.audit.AuditActorType.USER,
                actor.userId().toString(),
                document,
                "USER",
                traceId,
                requestId));
        indexEvents.enqueue(
                tenantId,
                document,
                KnowledgeIndexMode.DELETE,
                knowledgeRepository.nextIndexAttempt(tenantId, documentId),
                false,
                document.aclVersion(),
                "USER",
                actor.userId(),
                traceId,
                now);
    }

    /**
     * 重建索引；默认补缺失页，{@code force=true} 整本重做。
     *
     * @param actor 当前操作者
     * @param documentId 文档 ID
     * @param force 是否整本重做
     * @param traceId 追踪 ID
     * @return 当前文档
     * @throws NotFoundException 文档不可见或跨租户
     * @throws ForbiddenException 文档可见但缺少 {@code knowledge.manage}
     * @throws KnowledgeConflictException 文档已软删除或正在索引
     * @throws KnowledgeIndexUnavailableException 索引队列不可用
     */
    @Transactional
    public KnowledgeDocumentResponse reindex(KnowledgeActor actor, UUID documentId, boolean force, String traceId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        var scope = knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        if (!knowledgeRepository.lockVisibleIdIncludingDeleted(tenantId, documentId, scope)) {
            throw new NotFoundException("knowledge document");
        }
        KnowledgeDocumentRecord document = knowledgeRepository
                .findVisibleByIdIncludingDeleted(tenantId, documentId, scope)
                .orElseThrow(() -> new NotFoundException("knowledge document"));
        if (document.deletedAt() != null) {
            if (actor.manage() || actor.unrestricted() || actor.userId().equals(document.createdBy())) {
                throw new KnowledgeConflictException("document is deleted");
            }
            throw new ForbiddenException(PermissionCodes.KNOWLEDGE_MANAGE);
        }
        // 重索引的 RBAC 门是 knowledge.manage，创建人例外不替代该门（ADR-0023 第 5.6 节）。
        if (!actor.manage()) {
            throw new ForbiddenException(PermissionCodes.KNOWLEDGE_MANAGE);
        }
        if (document.status() == KnowledgeDocumentStatus.INDEXING
                || knowledgeRepository.hasPendingIndexEvent(tenantId, documentId)) {
            throw new KnowledgeConflictException("document is already being indexed");
        }
        Instant now = clock.instant();
        int attempt = knowledgeRepository.nextIndexAttempt(tenantId, documentId);
        try {
            indexEvents.enqueue(
                    tenantId,
                    document,
                    KnowledgeIndexMode.FULL,
                    attempt,
                    force,
                    document.aclVersion(),
                    "REINDEX",
                    actor.userId(),
                    traceId,
                    now);
        } catch (DataAccessException exception) {
            throw new KnowledgeIndexUnavailableException("knowledge index queue is unavailable");
        }
        return KnowledgeDocumentResponse.from(document, retentionDays(tenantId));
    }

    /**
     * 校验创建人或 ACL 管理权限；可见但无能力返回 403。
     *
     * @param actor 当前操作者
     * @param document 文档
     * @throws ForbiddenException 无管理能力
     */
    private static void requireManageCapability(KnowledgeActor actor, KnowledgeDocumentRecord document) {
        boolean creator = actor.userId().equals(document.createdBy());
        if (!creator && !actor.aclManage()) {
            throw new ForbiddenException(PermissionCodes.KNOWLEDGE_ACL_MANAGE);
        }
    }

    /**
     * 读取租户保留天数。
     *
     * @param tenantId 当前租户
     * @return 保留天数
     */
    private int retentionDays(TenantId tenantId) {
        return tenantDirectoryRepository
                .findById(tenantId.value())
                .map(tenant -> tenant.retentionDays())
                .orElseThrow(() -> new IllegalStateException("tenant not found: " + tenantId.value()));
    }
}
