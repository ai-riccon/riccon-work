package com.riccon.api.knowledge.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.config.KnowledgeAclProperties;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.knowledge.domain.KnowledgeAclEntry;
import com.riccon.api.knowledge.domain.KnowledgeAclUser;
import com.riccon.api.knowledge.domain.KnowledgeActor;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.dto.KnowledgeDocumentAclResponse;
import com.riccon.api.knowledge.dto.KnowledgeDocumentAclUpdate;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.api.knowledge.repository.KnowledgeAclRepository;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档 ACL 读取与整体替换。
 *
 * <p>判定顺序严格按 ADR-0023 第 5.4 节：先按租户 + ACL 谓词取文档，不可见统一 404；取到后若既不是创建人
 * 也没有 {@code knowledge.acl.manage}，返回 403。保存后下一次请求立即生效：MySQL 是权威，写入
 * {@code acl_version+1} 并投递 {@code ACL_ONLY} 索引刷新（不替换分块、保持 chunk_id 稳定）。
 */
@Service
public class KnowledgeAclService {

    /** 知识库仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** ACL 仓储。 */
    private final KnowledgeAclRepository aclRepository;

    /** ACL 输入校验。 */
    private final KnowledgeAclPolicy aclPolicy;

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
     * 创建 ACL 服务。
     *
     * @param knowledgeRepository 知识库仓储
     * @param aclRepository ACL 仓储
     * @param aclPolicy ACL 输入校验
     * @param auditService 审计写入
     * @param indexEvents 索引事件投递
     * @param aclProperties ACL 配置
     * @param tenantDirectoryRepository 租户目录
     * @param clock 可注入时钟
     */
    public KnowledgeAclService(
            KnowledgeRepository knowledgeRepository,
            KnowledgeAclRepository aclRepository,
            KnowledgeAclPolicy aclPolicy,
            AuditService auditService,
            KnowledgeIndexEvents indexEvents,
            KnowledgeAclProperties aclProperties,
            TenantDirectoryRepository tenantDirectoryRepository,
            Clock clock) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.aclRepository = Objects.requireNonNull(aclRepository, "aclRepository must not be null");
        this.aclPolicy = Objects.requireNonNull(aclPolicy, "aclPolicy must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.indexEvents = Objects.requireNonNull(indexEvents, "indexEvents must not be null");
        this.aclProperties = Objects.requireNonNull(aclProperties, "aclProperties must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取文档 ACL。
     *
     * @param actor 当前操作者
     * @param documentId 文档 ID
     * @return ACL
     * @throws NotFoundException 文档不可见或跨租户
     * @throws ForbiddenException 文档可见但不是创建人且无 ACL 管理权限
     */
    @Transactional(readOnly = true)
    public KnowledgeDocumentAclResponse get(KnowledgeActor actor, UUID documentId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        KnowledgeDocumentRecord document = visibleDocument(actor, tenantId, documentId);
        requireManageCapability(actor, document);
        List<KnowledgeAclUser> users = users(tenantId, document);
        return KnowledgeDocumentAclResponse.from(document, users);
    }

    /**
     * 整体替换文档 ACL。
     *
     * @param actor 当前操作者
     * @param documentId 文档 ID
     * @param update 目标 ACL
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 更新后的文档
     * @throws NotFoundException 文档不可见或跨租户
     * @throws ForbiddenException 文档可见但不是创建人且无 ACL 管理权限
     */
    @Transactional
    public KnowledgeDocumentResponse put(
            KnowledgeActor actor,
            UUID documentId,
            KnowledgeDocumentAclUpdate update,
            String traceId,
            String requestId) {
        Validation.requireBody(update);
        if (update.visibility() == null || update.visibility().isBlank()) {
            throw new KnowledgeBadRequestException("visibility is required");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        var scope = knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        // 行锁串行化同一文档的 ACL 变更，保证 acl_version 与审计/事件一致。
        if (!knowledgeRepository.lockVisibleId(tenantId, documentId, scope)) {
            throw new NotFoundException("knowledge document");
        }
        KnowledgeDocumentRecord previous = visibleDocument(actor, tenantId, documentId);
        requireManageCapability(actor, previous);
        KnowledgeAclPolicy.ResolvedAcl resolved = aclPolicy.resolve(
                tenantId, update.visibility(), update.departmentId(), update.userIds(), previous.createdBy());
        Set<UUID> previousUsers = aclRepository.findUserIds(tenantId, documentId);
        Set<UUID> nextUsers = new LinkedHashSet<>(resolved.userIds());
        Set<UUID> added = new LinkedHashSet<>(nextUsers);
        added.removeAll(previousUsers);
        Set<UUID> removed = new LinkedHashSet<>(previousUsers);
        removed.removeAll(nextUsers);
        Instant now = clock.instant();
        knowledgeRepository.updateVisibility(tenantId, documentId, resolved.visibility(), resolved.departmentId(), now);
        aclRepository.replace(tenantId, documentId, List.copyOf(nextUsers), actor.userId(), now);
        auditService.append(KnowledgeAuditEvents.aclChanged(
                now,
                actor.tenantId(),
                actor.userId(),
                documentId,
                previous.visibility(),
                resolved.visibility(),
                resolved.departmentId(),
                added,
                removed,
                nextUsers.size(),
                traceId,
                requestId));
        KnowledgeDocumentRecord updated = knowledgeRepository
                .findActiveById(tenantId, documentId)
                .orElseThrow(() -> new IllegalStateException("knowledge document update is not visible"));
        indexEvents.enqueue(
                tenantId,
                updated,
                KnowledgeIndexMode.ACL_ONLY,
                knowledgeRepository.nextIndexAttempt(tenantId, documentId),
                false,
                updated.aclVersion(),
                "ACL_CHANGED",
                actor.userId(),
                traceId,
                now);
        return KnowledgeDocumentResponse.from(updated, retentionDays(tenantId));
    }

    /**
     * 读取可见文档；不可见统一 404。
     *
     * @param actor 当前操作者
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    private KnowledgeDocumentRecord visibleDocument(KnowledgeActor actor, TenantId tenantId, UUID documentId) {
        var scope = knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        return knowledgeRepository
                .findVisibleById(tenantId, documentId, scope)
                .orElseThrow(() -> new NotFoundException("knowledge document"));
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
     * 组装 ACL 名单；{@code USERS} 包含创建人条目，{@code TENANT}/{@code DEPT} 返回空数组。
     *
     * @param tenantId 当前租户
     * @param document 文档
     * @return 名单
     */
    private List<KnowledgeAclUser> users(TenantId tenantId, KnowledgeDocumentRecord document) {
        if (document.visibility() != KnowledgeVisibility.USERS) {
            return List.of();
        }
        List<KnowledgeAclUser> users = new ArrayList<>();
        UUID creator = document.createdBy();
        if (creator != null) {
            String displayName =
                    knowledgeRepository.findDisplayName(tenantId, creator).orElse(creator.toString());
            users.add(new KnowledgeAclUser(creator, displayName, true));
        }
        for (KnowledgeAclEntry row : aclRepository.findUsers(tenantId, document.id())) {
            if (row.userId().equals(creator)) {
                // 历史脏数据可能把创建人写进名单；创建人条目只保留一次，且始终标记 creator=true。
                continue;
            }
            users.add(new KnowledgeAclUser(row.userId(), row.displayName(), false));
        }
        return users;
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
