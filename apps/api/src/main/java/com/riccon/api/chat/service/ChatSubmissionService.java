package com.riccon.api.chat.service;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.chat.domain.ChatSubmission;
import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.domain.MessageRecord;
import com.riccon.api.chat.dto.SendMessageRequest;
import com.riccon.api.chat.repository.ConversationRepository;
import com.riccon.api.chat.repository.MessageRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.repository.FileRepository;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.quota.service.QuotaAdmissionService;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.chat.MessageStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发送消息的持久化阶段。
 *
 * <p>按 ADR-0022 §1.3-R，冷路径补行 {@link QuotaAdmissionService#prepare} 在业务事务之外执行；随后由本类用
 * {@link TransactionTemplate} 开启业务事务：先取 tenant S 锁，再锁会话行完成幂等检查、附件校验、seq 分配、
 * 用户消息与 assistant 占位插入、配额准入、agent_run 创建、会话标题刷新与 {@code message.sent} 审计。
 * 锁序固定为 tenant S → 会话行 → 配额行；本阶段不调用 Worker，事务提交后由 {@link ChatService} 打开 SSE，
 * 避免把网络等待放进数据库事务。M03 起共享空间成员都可向同一会话追加消息，写入前先经过工作空间访问判定。
 *
 * <p>按 ADR-0008，J1 流式回答走 API → Worker 直连 SSE，不写 outbox；{@code riccon.agent.run.requested.v1}
 * 的 payload 要求 {@code researchTaskId}，只适用于 J2 报告任务，不能用于 CHAT。
 */
@Service
public class ChatSubmissionService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatSubmissionService.class);

    /** 单条消息最多附件数。 */
    private static final int MAX_FILES = 5;

    /** 消息内容最大长度。 */
    private static final int MAX_CONTENT_LENGTH = 20000;

    /** 客户端消息 ID 范围。 */
    private static final int MIN_CLIENT_MESSAGE_ID = 8;

    /** 客户端消息 ID 范围。 */
    private static final int MAX_CLIENT_MESSAGE_ID = 64;

    /** 会话仓储。 */
    private final ConversationRepository conversationRepository;

    /** 消息仓储。 */
    private final MessageRepository messageRepository;

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** 文件仓储，用于校验附件归属。 */
    private final FileRepository fileRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /** 配额准入；与 run 创建同事务。 */
    private final QuotaAdmissionService quotaAdmissionService;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 业务事务模板；配额冷路径补行在模板之外完成。 */
    private final TransactionTemplate transactionTemplate;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param conversationRepository 会话仓储
     * @param messageRepository 消息仓储
     * @param runRepository 运行仓储
     * @param fileRepository 文件仓储
     * @param workspaceService 工作空间可见性
     * @param quotaAdmissionService 配额准入
     * @param auditService 审计写入
     * @param transactionManager 事务管理器
     * @param clock UTC 时钟
     */
    public ChatSubmissionService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            RunRepository runRepository,
            FileRepository fileRepository,
            WorkspaceService workspaceService,
            QuotaAdmissionService quotaAdmissionService,
            AuditService auditService,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.quotaAdmissionService = Objects.requireNonNull(quotaAdmissionService, "quotaAdmissionService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 持久化用户消息、assistant 占位、运行与审计。
     *
     * <p>配额冷路径补行在业务事务之外完成（不能把 {@code REQUIRES_NEW} 嵌套进业务事务占用第二条连接）；
     * 之后才开启业务事务，在事务内先取 tenant S、再锁会话行。会话不存在/无权访问仍在补行前拒绝，避免为无效
     * 请求创建配额行。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @param request 发送请求
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已持久化的提交
     * @throws NotFoundException 会话不存在或不属于当前员工
     * @throws ConflictException 同一 clientMessageId 已提交
     * @throws BadRequestException 内容、附件或幂等键不合法
     */
    public ChatSubmission prepare(
            RicconPrincipal actor, UUID conversationId, SendMessageRequest request, String traceId, String requestId) {
        Validation.requireBody(request);
        String content = Validation.requireText(request.content(), "content", 1, MAX_CONTENT_LENGTH);
        String clientMessageId = Validation.requireText(
                request.clientMessageId(), "clientMessageId", MIN_CLIENT_MESSAGE_ID, MAX_CLIENT_MESSAGE_ID);
        List<UUID> fileIds = normalizeFileIds(request.fileIds());

        TenantId tenantId = TenantId.of(actor.tenantId());
        // 先按对象 ID 读取所属空间并判定访问，再锁定会话行串行分配 seq；共享空间成员都可追加消息。
        ConversationRecord conversation = conversationRepository
                .findById(tenantId, conversationId)
                .orElseThrow(() -> new NotFoundException("conversation"));
        workspaceService.requireAccessible(tenantId, conversation.workspaceId(), actor.userId());
        // 冷路径补行必须在任何业务事务之外；随后业务事务内先取 tenant S、再锁会话行（ADR-0022 §1.3-R）。
        QuotaAdmissionService.AdmissionContext admission =
                quotaAdmissionService.prepare(tenantId, actor.userId(), clock.instant());
        return transactionTemplate.execute(status -> persistSubmission(
                actor, tenantId, conversationId, content, clientMessageId, fileIds, traceId, requestId, admission));
    }

    /**
     * 在业务事务内完成会话行锁、幂等检查、配额准入、消息/运行插入与审计。
     *
     * @param actor 当前员工
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param content 消息内容
     * @param clientMessageId 客户端消息 ID
     * @param fileIds 已规范化的附件 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param admission 配额准入上下文
     * @return 已持久化的提交
     */
    private ChatSubmission persistSubmission(
            RicconPrincipal actor,
            TenantId tenantId,
            UUID conversationId,
            String content,
            String clientMessageId,
            List<UUID> fileIds,
            String traceId,
            String requestId,
            QuotaAdmissionService.AdmissionContext admission) {
        // tenant S 必须早于会话行锁：S 相互兼容，只与冷路径补行/M06 重试的 X 互斥。
        quotaAdmissionService.lockTenantShared(tenantId);
        ConversationRecord conversation = conversationRepository
                .findByIdForUpdate(tenantId, conversationId)
                .orElseThrow(() -> new NotFoundException("conversation"));
        if (messageRepository
                .findByClientMessageId(tenantId, conversation.id(), clientMessageId)
                .isPresent()) {
            throw new ConflictException("clientMessageId was already submitted");
        }
        requireUsableFiles(tenantId, conversation, fileIds);

        // 配额准入放在幂等/权限/文件校验之后、任何消息与 run 插入之前：重复提交不会重复扣配额，
        // 被拒绝时事务内尚未写入业务数据；计数与 run 插入同事务提交/回滚（ADR-0022 §1.4/§1.3-R）。
        quotaAdmissionService.admit(admission);

        Instant now = clock.instant();
        long maxSeq = messageRepository.maxSeq(tenantId, conversation.id());
        UUID userMessageId = UuidV7.generate(now);
        UUID assistantMessageId = UuidV7.generate(now);
        UUID runId = UuidV7.generate(now);
        MessageRecord userMessage = new MessageRecord(
                userMessageId,
                conversation.id(),
                maxSeq + 1,
                MessageRole.USER,
                content,
                MessageStatus.COMPLETED,
                clientMessageId,
                null,
                null,
                null,
                List.of(),
                now);
        MessageRecord assistantMessage = new MessageRecord(
                assistantMessageId,
                conversation.id(),
                maxSeq + 2,
                MessageRole.ASSISTANT,
                "",
                MessageStatus.PENDING,
                null,
                runId,
                null,
                null,
                List.of(),
                now);
        try {
            messageRepository.insert(tenantId, userMessage);
            messageRepository.insert(tenantId, assistantMessage);
            runRepository.createChatPending(tenantId, runId, conversation.id(), actor.userId(), traceId, now);
        } catch (DuplicateKeyException exception) {
            // 并发相同 clientMessageId：会话行锁已串行化，这里是对唯一键的最终守卫；抛错使配额与消息一并回滚。
            throw new ConflictException("clientMessageId was already submitted");
        }
        String autoTitle = ConversationService.autoTitle(content);
        if (conversationRepository.touchAfterMessage(tenantId, conversation.id(), autoTitle, now) == 0) {
            throw new NotFoundException("conversation");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userMessageId", userMessageId.toString());
        details.put("assistantMessageId", assistantMessageId.toString());
        details.put("runId", runId.toString());
        details.put("attachmentCount", fileIds.size());
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                AdditionalAuditActions.MESSAGE_SENT,
                AuditTargetType.CONVERSATION,
                conversation.id().toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details));
        LOG.info(
                "chat.message.sent tenantId={} conversationId={} runId={} userMessageId={} assistantMessageId={} attachments={}",
                actor.tenantId(),
                conversation.id(),
                runId,
                userMessageId,
                assistantMessageId,
                fileIds.size());
        return new ChatSubmission(
                tenantId,
                actor.userId(),
                conversation.id(),
                userMessageId,
                assistantMessageId,
                runId,
                traceId,
                fileIds);
    }

    /**
     * 规范化附件列表：去空、去重、限制数量。
     *
     * @param fileIds 原始附件
     * @return 附件 ID
     */
    private static List<UUID> normalizeFileIds(List<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        if (fileIds.size() > MAX_FILES) {
            throw new BadRequestException("fileIds must contain at most " + MAX_FILES + " items");
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID fileId : fileIds) {
            if (fileId == null) {
                throw new BadRequestException("fileIds must not contain null");
            }
            unique.add(fileId);
        }
        if (unique.size() != fileIds.size()) {
            throw new BadRequestException("fileIds must not contain duplicates");
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    /**
     * 校验附件均属于当前会话、可用且所属工作空间与会话一致。
     *
     * <p>会话空间已由调用方通过 {@code requireAccessible} 判定；这里再校验文件自身的工作空间与会话一致，
     * 避免出现“会话可见但附件属于其他空间”的数据不一致被当成可用附件。
     *
     * @param tenantId 当前租户
     * @param conversation 会话
     * @param fileIds 附件 ID
     */
    private void requireUsableFiles(TenantId tenantId, ConversationRecord conversation, List<UUID> fileIds) {
        for (UUID fileId : fileIds) {
            FileRecord file = fileRepository
                    .findById(tenantId, fileId)
                    .orElseThrow(() -> new BadRequestException("file is not available in this conversation"));
            if (!conversation.id().equals(file.conversationId())
                    || !conversation.workspaceId().equals(file.workspaceId())) {
                throw new BadRequestException("file is not available in this conversation");
            }
        }
    }
}
