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
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.web.Validation;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 发送消息的持久化阶段。
 *
 * <p>在会话行锁内完成：幂等检查、附件校验、seq 分配、用户消息与 assistant 占位插入、agent_run 创建、会话标题刷新
 * 与 {@code message.sent} 审计。本阶段不调用 Worker；事务提交后由 {@link ChatService} 打开 SSE，避免把网络等待
 * 放进数据库事务。
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

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param conversationRepository 会话仓储
     * @param messageRepository 消息仓储
     * @param runRepository 运行仓储
     * @param fileRepository 文件仓储
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ChatSubmissionService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            RunRepository runRepository,
            FileRepository fileRepository,
            AuditService auditService,
            Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 持久化用户消息、assistant 占位、运行与审计。
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
    @Transactional
    public ChatSubmission prepare(
            RicconPrincipal actor, UUID conversationId, SendMessageRequest request, String traceId, String requestId) {
        Validation.requireBody(request);
        String content = Validation.requireText(request.content(), "content", 1, MAX_CONTENT_LENGTH);
        String clientMessageId = Validation.requireText(
                request.clientMessageId(), "clientMessageId", MIN_CLIENT_MESSAGE_ID, MAX_CLIENT_MESSAGE_ID);
        List<UUID> fileIds = normalizeFileIds(request.fileIds());

        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = conversationRepository
                .findOwnedForUpdate(tenantId, conversationId, actor.userId())
                .orElseThrow(() -> new NotFoundException("conversation"));
        if (messageRepository
                .findByClientMessageId(tenantId, conversation.id(), clientMessageId)
                .isPresent()) {
            throw new ConflictException("clientMessageId was already submitted");
        }
        requireUsableFiles(tenantId, conversation, actor.userId(), fileIds);

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
            // 并发相同 clientMessageId：会话行锁已串行化，这里是对唯一键的最终守卫。
            throw new ConflictException("clientMessageId was already submitted");
        }
        String autoTitle = ConversationService.autoTitle(content);
        if (conversationRepository.touchAfterMessage(tenantId, conversation.id(), actor.userId(), autoTitle, now)
                == 0) {
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
     * 校验附件均属于当前会话、可用且对当前员工可见。
     *
     * @param tenantId 当前租户
     * @param conversation 会话
     * @param userId 当前员工
     * @param fileIds 附件 ID
     */
    private void requireUsableFiles(
            TenantId tenantId, ConversationRecord conversation, UUID userId, List<UUID> fileIds) {
        for (UUID fileId : fileIds) {
            FileRecord file = fileRepository
                    .findAccessible(tenantId, fileId, userId)
                    .orElseThrow(() -> new BadRequestException("file is not available in this conversation"));
            if (!conversation.id().equals(file.conversationId())) {
                throw new BadRequestException("file is not available in this conversation");
            }
        }
    }
}
