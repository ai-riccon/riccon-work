package com.riccon.api.chat.repository;

import com.riccon.api.chat.domain.CitationRecord;
import com.riccon.api.chat.domain.MessageRecord;
import com.riccon.api.chat.persistence.MessageJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.chat.MessageStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 消息与引用仓储。
 *
 * <p>负责把引用批量附加到消息快照，避免列表接口逐条查询引用（N+1）。状态推进与 seq 分配规则由服务层控制。
 */
@Repository
public class MessageRepository {

    /** 消息 SQL。 */
    private final MessageJdbc messageJdbc;

    /**
     * 创建仓储。
     *
     * @param messageJdbc 消息 SQL
     */
    public MessageRepository(MessageJdbc messageJdbc) {
        this.messageJdbc = Objects.requireNonNull(messageJdbc, "messageJdbc must not be null");
    }

    /**
     * 插入消息。
     *
     * @param tenantId 当前租户
     * @param message 消息快照
     */
    public void insert(TenantId tenantId, MessageRecord message) {
        messageJdbc.insert(tenantId, message);
    }

    /**
     * 查询会话内最大 seq。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 最大 seq
     */
    public long maxSeq(TenantId tenantId, UUID conversationId) {
        return messageJdbc.maxSeq(tenantId, conversationId);
    }

    /**
     * 按客户端幂等键查询用户消息。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param clientMessageId 客户端幂等键
     * @return 用户消息
     */
    public Optional<MessageRecord> findByClientMessageId(
            TenantId tenantId, UUID conversationId, String clientMessageId) {
        return messageJdbc.findByClientMessageId(tenantId, conversationId, clientMessageId);
    }

    /**
     * 按运行查询 assistant 消息。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return assistant 消息
     */
    public Optional<MessageRecord> findAssistantByRun(TenantId tenantId, UUID runId) {
        return messageJdbc.findAssistantByRun(tenantId, runId);
    }

    /**
     * 查询消息状态。
     *
     * @param tenantId 当前租户
     * @param messageId 消息 ID
     * @return 状态
     */
    public Optional<MessageStatus> findStatus(TenantId tenantId, UUID messageId) {
        return messageJdbc.findStatus(tenantId, messageId);
    }

    /**
     * 统计会话消息总数。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 总数
     */
    public long countByConversation(TenantId tenantId, UUID conversationId) {
        return messageJdbc.countByConversation(tenantId, conversationId);
    }

    /**
     * 查询最近消息并附加引用。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param beforeSeq 向前翻页游标；为空表示最新一页
     * @param limit 条数上限
     * @return 按 seq 升序的消息
     */
    public List<MessageRecord> listLatest(TenantId tenantId, UUID conversationId, Long beforeSeq, int limit) {
        List<MessageRecord> messages = messageJdbc.listLatest(tenantId, conversationId, beforeSeq, limit);
        return attachCitations(tenantId, messages);
    }

    /**
     * 批量附加引用。
     *
     * @param tenantId 当前租户
     * @param messages 消息
     * @return 带引用的消息
     */
    public List<MessageRecord> attachCitations(TenantId tenantId, List<MessageRecord> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        Set<UUID> assistantIds = new LinkedHashSet<>();
        for (MessageRecord message : messages) {
            if (message.role() == MessageRole.ASSISTANT) {
                assistantIds.add(message.id());
            }
        }
        if (assistantIds.isEmpty()) {
            return List.copyOf(messages);
        }
        Map<UUID, List<CitationRecord>> byMessage = messageJdbc.listCitations(tenantId, List.copyOf(assistantIds));
        List<MessageRecord> enriched = new java.util.ArrayList<>(messages.size());
        for (MessageRecord message : messages) {
            List<CitationRecord> citations = byMessage.getOrDefault(message.id(), List.of());
            enriched.add(new MessageRecord(
                    message.id(),
                    message.conversationId(),
                    message.seq(),
                    message.role(),
                    message.content(),
                    message.status(),
                    message.clientMessageId(),
                    message.runId(),
                    message.modelId(),
                    message.errorCode(),
                    citations,
                    message.createdAt()));
        }
        return List.copyOf(enriched);
    }

    /**
     * 追加流式内容。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param delta 文本增量
     * @param now 当前时间
     * @return 受影响行数
     */
    public int appendContent(TenantId tenantId, UUID messageId, String delta, Instant now) {
        return messageJdbc.appendContent(tenantId, messageId, delta, now);
    }

    /**
     * 收到 started 后写入模型并进入 STREAMING。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param modelId 模型 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markStreaming(TenantId tenantId, UUID messageId, UUID modelId, Instant now) {
        return messageJdbc.markStreaming(tenantId, messageId, modelId, now);
    }

    /**
     * 推进消息终态。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param status 终态
     * @param errorCode 失败错误码
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markTerminal(TenantId tenantId, UUID messageId, MessageStatus status, String errorCode, Instant now) {
        return messageJdbc.markTerminal(tenantId, messageId, status, errorCode, now);
    }

    /**
     * 按运行推进 assistant 消息终态。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param status 终态
     * @param errorCode 失败错误码
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markTerminalByRun(TenantId tenantId, UUID runId, MessageStatus status, String errorCode, Instant now) {
        return messageJdbc.markTerminalByRun(tenantId, runId, status, errorCode, now);
    }

    /**
     * 插入引用快照。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param citation 引用
     */
    public void insertCitation(TenantId tenantId, UUID messageId, CitationRecord citation) {
        messageJdbc.insertCitation(tenantId, messageId, citation);
    }
}
