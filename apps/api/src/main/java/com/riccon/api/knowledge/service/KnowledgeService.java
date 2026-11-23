package com.riccon.api.knowledge.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.config.KnowledgeAclProperties;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.repository.FileRepository;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.domain.KnowledgeActor;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.api.knowledge.domain.KnowledgeVisibilityScope;
import com.riccon.api.knowledge.dto.KnowledgeDocumentCreateRequest;
import com.riccon.api.knowledge.dto.KnowledgeDocumentPageResponse;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.api.knowledge.repository.KnowledgeAclRepository;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import com.riccon.platform.domain.knowledge.ParseEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库文档登记与查询。
 *
 * <p>登记复用工作空间文件，写入文档元数据、初始 ACL 与审计后投递索引事件；解析与索引由 Worker 异步完成。
 * 列表/详情按文档 ACL 过滤：不可见、跨租户、跨部门统一 404；{@code TENANT_ADMIN} 的管理性读取会写
 * {@code knowledge.document.admin_read}（ADR-0023 第 5.4/5.6 节）。
 */
@Service
public class KnowledgeService {

    /** 标题最大长度，与 V3 列定义一致。 */
    private static final int MAX_TITLE_LENGTH = 300;

    /** 知识库仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** ACL 仓储。 */
    private final KnowledgeAclRepository aclRepository;

    /** 文件仓储，用于校验来源文件归属并复制元数据。 */
    private final FileRepository fileRepository;

    /** 租户目录，用于读取保留天数。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** ACL 输入校验。 */
    private final KnowledgeAclPolicy aclPolicy;

    /** 索引事件投递。 */
    private final KnowledgeIndexEvents indexEvents;

    /** ACL 开关与上限。 */
    private final KnowledgeAclProperties aclProperties;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param knowledgeRepository 知识库仓储
     * @param aclRepository ACL 仓储
     * @param fileRepository 文件仓储
     * @param tenantDirectoryRepository 租户目录
     * @param auditService 审计写入
     * @param aclPolicy ACL 输入校验
     * @param indexEvents 索引事件投递
     * @param aclProperties ACL 配置
     * @param clock 可注入时钟
     */
    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            KnowledgeAclRepository aclRepository,
            FileRepository fileRepository,
            TenantDirectoryRepository tenantDirectoryRepository,
            AuditService auditService,
            KnowledgeAclPolicy aclPolicy,
            KnowledgeIndexEvents indexEvents,
            KnowledgeAclProperties aclProperties,
            Clock clock) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.aclRepository = Objects.requireNonNull(aclRepository, "aclRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.aclPolicy = Objects.requireNonNull(aclPolicy, "aclPolicy must not be null");
        this.indexEvents = Objects.requireNonNull(indexEvents, "indexEvents must not be null");
        this.aclProperties = Objects.requireNonNull(aclProperties, "aclProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出当前账号可见的知识库文档。
     *
     * @param actor 当前操作者
     * @param page 分页参数
     * @param status 可选状态过滤
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 文档分页
     */
    @Transactional
    public KnowledgeDocumentPageResponse list(
            KnowledgeActor actor, PageQuery page, KnowledgeDocumentStatus status, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        KnowledgeVisibilityScope scope =
                knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        long total = knowledgeRepository.count(tenantId, status, scope);
        List<KnowledgeDocumentRecord> documents =
                knowledgeRepository.list(tenantId, status, scope, page.size(), page.offset());
        int tenantRetentionDays = retentionDays(tenantId);
        List<KnowledgeDocumentResponse> items = documents.stream()
                .map(document -> KnowledgeDocumentResponse.from(document, tenantRetentionDays))
                .toList();
        if (scope.unrestricted() && aclProperties.enforcementEnabled()) {
            // 管理列表每次读取都写审计；包含结果数量，不含标题或名单（ADR-0023 第 5.4/5.6 节）。
            auditService.append(KnowledgeAuditEvents.adminRead(
                    clock.instant(),
                    actor.tenantId(),
                    actor.userId(),
                    "LIST",
                    null,
                    null,
                    null,
                    total,
                    traceId,
                    requestId));
        }
        return new KnowledgeDocumentPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取当前账号可见的知识库文档。
     *
     * @param actor 当前操作者
     * @param documentId 文档 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 文档
     * @throws NotFoundException 不存在、已删除、跨租户/跨部门或未授权可见
     */
    @Transactional
    public KnowledgeDocumentResponse get(KnowledgeActor actor, UUID documentId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        KnowledgeVisibilityScope scope =
                knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        // 先按“本人 ACL”判定；管理员 scope 会绕过 ACL，不能用来判断是否发生管理性读取。
        KnowledgeVisibilityScope aclScope = knowledgeRepository.resolveScope(
                tenantId,
                new KnowledgeActor(actor.userId(), actor.tenantId(), false, actor.manage()),
                aclProperties.enforcementEnabled());
        Optional<KnowledgeDocumentRecord> visible = knowledgeRepository.findVisibleById(tenantId, documentId, aclScope);
        if (visible.isPresent()) {
            return KnowledgeDocumentResponse.from(visible.get(), retentionDays(tenantId));
        }
        if (scope.unrestricted() && aclProperties.enforcementEnabled()) {
            Optional<KnowledgeDocumentRecord> managed =
                    knowledgeRepository.findActiveByIdIgnoringAcl(tenantId, documentId);
            if (managed.isPresent()) {
                KnowledgeDocumentRecord document = managed.get();
                // 管理性读取只记录对象 ID、可见范围与人数，不写标题或正文。
                auditService.append(KnowledgeAuditEvents.adminRead(
                        clock.instant(),
                        actor.tenantId(),
                        actor.userId(),
                        "DETAIL",
                        document.id(),
                        document.visibility(),
                        aclRepository.count(tenantId, document.id()),
                        null,
                        traceId,
                        requestId));
                return KnowledgeDocumentResponse.from(document, retentionDays(tenantId));
            }
        }
        throw new NotFoundException("knowledge document");
    }

    /**
     * 将可访问文件登记为知识库文档并投递索引事件。
     *
     * @param actor 当前操作者
     * @param request 登记请求
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已受理文档
     * @throws NotFoundException 来源文件不存在或不可见
     */
    @Transactional
    public KnowledgeDocumentResponse create(
            KnowledgeActor actor, KnowledgeDocumentCreateRequest request, String traceId, String requestId) {
        Validation.requireBody(request);
        String title = Validation.requireText(request.title(), "title", 1, MAX_TITLE_LENGTH);
        if (request.fileId() == null) {
            throw new BadRequestException("fileId is required");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        // 锁定来源文件，串行化同一文件的重复登记，避免生成两个文档与两个索引任务。
        FileRecord file = fileRepository
                .lockAccessible(tenantId, request.fileId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("file"));
        Optional<KnowledgeDocumentRecord> existing = knowledgeRepository.findActiveByFileId(tenantId, file.id());
        if (existing.isPresent()) {
            return KnowledgeDocumentResponse.from(existing.get(), retentionDays(tenantId));
        }
        KnowledgeAclPolicy.ResolvedAcl acl = aclPolicy.resolve(
                tenantId, request.visibility(), request.departmentId(), request.userIds(), actor.userId());
        Instant now = clock.instant();
        int tenantRetentionDays = retentionDays(tenantId);
        UUID documentId = UuidV7.generate(now);
        KnowledgeDocumentRecord document = new KnowledgeDocumentRecord(
                documentId,
                title,
                KnowledgeSourceType.UPLOAD,
                file.id(),
                file.sha256(),
                file.mimeType(),
                file.sizeBytes(),
                KnowledgeDocumentStatus.PENDING,
                null,
                null,
                null,
                null,
                actor.userId(),
                now,
                acl.visibility(),
                acl.departmentId(),
                1,
                ParseEngine.NONE,
                List.of(),
                null,
                now.plus(Duration.ofDays(tenantRetentionDays)),
                false,
                acl.userIds().size(),
                null);
        knowledgeRepository.insert(tenantId, document);
        aclRepository.replace(tenantId, documentId, acl.userIds(), actor.userId(), now);
        auditService.append(KnowledgeAuditEvents.added(
                now,
                actor.tenantId(),
                actor.userId(),
                documentId,
                KnowledgeSourceType.UPLOAD.name(),
                acl.visibility(),
                acl.departmentId(),
                file.id(),
                traceId,
                requestId));
        indexEvents.enqueue(
                tenantId,
                document,
                KnowledgeIndexMode.FULL,
                1,
                false,
                document.aclVersion(),
                null,
                actor.userId(),
                traceId,
                now);
        // 回读一次以带上源文件空间可见性等派生字段；同一事务内可见。
        KnowledgeDocumentRecord stored = knowledgeRepository
                .findActiveById(tenantId, documentId)
                .orElseThrow(() -> new IllegalStateException("knowledge document insert is not visible"));
        return KnowledgeDocumentResponse.from(stored, tenantRetentionDays);
    }

    /**
     * 读取当前租户保留天数。
     *
     * @param tenantId 当前租户
     * @return 保留天数
     */
    int retentionDays(TenantId tenantId) {
        TenantRecord tenant = tenantDirectoryRepository
                .findById(tenantId.value())
                .orElseThrow(() -> new IllegalStateException("tenant not found: " + tenantId.value()));
        return tenant.retentionDays();
    }
}
