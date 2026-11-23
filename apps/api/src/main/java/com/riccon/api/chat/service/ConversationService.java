package com.riccon.api.chat.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.domain.ConversationStatus;
import com.riccon.api.chat.domain.MessageRecord;
import com.riccon.api.chat.dto.ConversationCreateRequest;
import com.riccon.api.chat.dto.ConversationDetailResponse;
import com.riccon.api.chat.dto.ConversationPageResponse;
import com.riccon.api.chat.dto.ConversationResponse;
import com.riccon.api.chat.dto.ConversationUpdateRequest;
import com.riccon.api.chat.dto.MessagePageResponse;
import com.riccon.api.chat.dto.MessageResponse;
import com.riccon.api.chat.repository.ConversationRepository;
import com.riccon.api.chat.repository.MessageRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话与消息读取、会话生命周期。
 *
 * <p>M03 起会话可见性跟随所属工作空间：列表、详情、消息与停止生成先经
 * {@link WorkspaceService#requireAccessible} 判定空间访问，再按会话 ID 读取；跨租户、跨部门与个人空间他人
 * 统一 404。标题修改与会话删除仍只允许创建人（ADR-0018 第 3.2 节）。会话创建时按租户 {@code retention_days}
 * 写入到期时间；首条消息自动生成标题；删除只写 {@code deleted_at}，消息与引用保留。
 */
@Service
public class ConversationService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConversationService.class);

    /** 标题最大长度，与 OpenAPI 和列定义一致。 */
    private static final int MAX_TITLE_LENGTH = 200;

    /** 自动标题字符数（按 code point 计算，避免截断代理对）。 */
    private static final int AUTO_TITLE_CODE_POINTS = 20;

    /** 会话详情返回的最近消息条数上限。 */
    private static final int DETAIL_MESSAGE_LIMIT = 100;

    /** 会话仓储。 */
    private final ConversationRepository conversationRepository;

    /** 消息仓储。 */
    private final MessageRepository messageRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /** 租户目录，用于读取保留策略。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param conversationRepository 会话仓储
     * @param messageRepository 消息仓储
     * @param workspaceService 工作空间可见性
     * @param tenantDirectoryRepository 租户目录
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ConversationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            WorkspaceService workspaceService,
            TenantDirectoryRepository tenantDirectoryRepository,
            AuditService auditService,
            Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantDirectoryRepository = Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 列出工作空间内的活跃会话。
     *
     * <p>空间判定与列表复用同一访问入口：先 {@code requireAccessible}，再按 {@code workspace_id} 查询，
     * 因此共享空间成员能看到同一空间内的全部活跃会话（ADR-0018 第 3.1/3.2 节）。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param page 分页参数
     * @return 会话分页
     */
    @Transactional(readOnly = true)
    public ConversationPageResponse list(RicconPrincipal actor, UUID workspaceId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        long total = conversationRepository.countActive(tenantId, workspaceId);
        List<ConversationResponse> items =
                conversationRepository.listActive(tenantId, workspaceId, page.size(), page.offset()).stream()
                        .map(ConversationResponse::from)
                        .toList();
        return new ConversationPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 新建会话并写 {@code session.created} 审计。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param request 创建请求；可为空
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 新会话
     */
    @Transactional
    public ConversationResponse create(
            RicconPrincipal actor,
            UUID workspaceId,
            ConversationCreateRequest request,
            String traceId,
            String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        String title = normalizeOptionalTitle(request == null ? null : request.title());
        Instant now = clock.instant();
        TenantRecord tenant =
                tenantDirectoryRepository.findById(actor.tenantId()).orElseThrow(() -> new NotFoundException("tenant"));
        ConversationRecord conversation = new ConversationRecord(
                UuidV7.generate(now), workspaceId, actor.userId(), title, ConversationStatus.ACTIVE, null, now, now);
        conversationRepository.insert(tenantId, conversation, now.plus(Duration.ofDays(tenant.retentionDays())));
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                AuditActions.SESSION_CREATED,
                AuditTargetType.CONVERSATION,
                conversation.id().toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                java.util.Map.of("sessionId", conversation.id().toString())));
        LOG.info(
                "conversation.created tenantId={} conversationId={} workspaceId={}",
                actor.tenantId(),
                conversation.id(),
                workspaceId);
        return ConversationResponse.from(conversation);
    }

    /**
     * 读取会话详情与最近消息。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @return 会话详情
     */
    @Transactional(readOnly = true)
    public ConversationDetailResponse get(RicconPrincipal actor, UUID conversationId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = requireAccessible(actor, conversationId);
        List<MessageRecord> messages =
                messageRepository.listLatest(tenantId, conversation.id(), null, DETAIL_MESSAGE_LIMIT, actor.userId());
        return ConversationDetailResponse.from(
                conversation, messages.stream().map(MessageResponse::from).toList());
    }

    /**
     * 修改会话标题；只有创建人可以修改。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @param request 标题请求
     * @return 更新后的会话
     */
    @Transactional
    public ConversationResponse update(RicconPrincipal actor, UUID conversationId, ConversationUpdateRequest request) {
        Validation.requireBody(request);
        String title = Validation.requireText(request.title(), "title", 1, MAX_TITLE_LENGTH);
        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = requireAccessible(actor, conversationId);
        requireCreator(actor, conversation);
        Instant now = clock.instant();
        int updated = conversationRepository.updateTitle(tenantId, conversationId, title, now);
        if (updated == 0) {
            throw new NotFoundException("conversation");
        }
        return ConversationResponse.from(new ConversationRecord(
                conversation.id(),
                conversation.workspaceId(),
                conversation.createdBy(),
                title,
                conversation.status(),
                conversation.lastMessageAt(),
                conversation.createdAt(),
                now));
    }

    /**
     * 软删除会话并写 {@code session.deleted} 审计；只有创建人可以删除。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void delete(RicconPrincipal actor, UUID conversationId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = requireAccessible(actor, conversationId);
        requireCreator(actor, conversation);
        Instant now = clock.instant();
        if (conversationRepository.softDelete(tenantId, conversationId, now) == 0) {
            throw new NotFoundException("conversation");
        }
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                com.riccon.api.audit.domain.AdditionalAuditActions.SESSION_DELETED,
                AuditTargetType.CONVERSATION,
                conversation.id().toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                java.util.Map.of("sessionId", conversation.id().toString())));
        LOG.info("conversation.deleted tenantId={} conversationId={}", actor.tenantId(), conversationId);
    }

    /**
     * 列出会话消息，按 seq 升序；{@code beforeSeq} 用于向前翻页。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @param beforeSeq 只返回 seq 小于该值的消息
     * @param size 每页大小
     * @return 消息分页
     */
    @Transactional(readOnly = true)
    public MessagePageResponse listMessages(RicconPrincipal actor, UUID conversationId, Long beforeSeq, Integer size) {
        PageQuery page = PageQuery.of(1, size);
        if (beforeSeq != null && beforeSeq < 1) {
            throw new BadRequestException("beforeSeq must be >= 1");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = requireAccessible(actor, conversationId);
        long total = messageRepository.countByConversation(tenantId, conversation.id());
        List<MessageResponse> items =
                messageRepository
                        .listLatest(tenantId, conversation.id(), beforeSeq, page.size(), actor.userId())
                        .stream()
                        .map(MessageResponse::from)
                        .toList();
        return new MessagePageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取会话并校验当前员工可访问其所属工作空间。
     *
     * <p>访问判定只有一处：先按对象 ID 读取所属 {@code workspaceId}，再调用
     * {@link WorkspaceService#requireAccessible}；本方法不按创建人过滤，共享空间成员可读取同一会话。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @return 会话
     * @throws NotFoundException 不存在、已删除、跨租户或所属空间不可访问
     */
    @Transactional(readOnly = true)
    public ConversationRecord requireAccessible(RicconPrincipal actor, UUID conversationId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = conversationRepository
                .findById(tenantId, conversationId)
                .orElseThrow(() -> new NotFoundException("conversation"));
        workspaceService.requireAccessible(tenantId, conversation.workspaceId(), actor.userId());
        return conversation;
    }

    /**
     * 校验当前员工是会话创建人。
     *
     * <p>共享空间成员可以读取与追加消息，但标题修改和删除仍是创建人对象级权限（ADR-0018 第 3.2 节）。
     *
     * @param actor 当前员工
     * @param conversation 已通过空间判定的会话
     * @throws ForbiddenException 当前员工不是创建人
     */
    private static void requireCreator(RicconPrincipal actor, ConversationRecord conversation) {
        if (!conversation.createdBy().equals(actor.userId())) {
            throw new ForbiddenException("conversation.write");
        }
    }

    /**
     * 规范化可选标题；空白视为未提供，超长拒绝。
     *
     * @param value 原始标题
     * @return 去空白后的标题或 {@code null}
     */
    private static String normalizeOptionalTitle(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new BadRequestException("title must not exceed " + MAX_TITLE_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * 从首条消息生成标题：压缩空白后取前 20 个 code point，超出时追加省略号。
     *
     * @param content 消息内容
     * @return 标题
     */
    static String autoTitle(String content) {
        String normalized = content.replaceAll("\\s+", " ").strip();
        int total = normalized.codePointCount(0, normalized.length());
        if (total <= AUTO_TITLE_CODE_POINTS) {
            return normalized;
        }
        int endIndex = normalized.offsetByCodePoints(0, AUTO_TITLE_CODE_POINTS);
        return normalized.substring(0, endIndex) + "…";
    }
}
