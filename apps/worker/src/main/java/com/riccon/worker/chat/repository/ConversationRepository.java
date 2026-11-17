package com.riccon.worker.chat.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.chat.persistence.ConversationJdbc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 会话只读仓储。
 */
@Component
public class ConversationRepository {

    /** 会话访问。 */
    private final ConversationJdbc conversationJdbc;

    /**
     * 创建会话仓储。
     *
     * @param conversationJdbc 会话访问
     */
    public ConversationRepository(ConversationJdbc conversationJdbc) {
        this.conversationJdbc = Objects.requireNonNull(conversationJdbc, "conversationJdbc must not be null");
    }

    /**
     * 校验会话归属。
     *
     * @param tenantId 租户
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 是否存在
     */
    public boolean existsForUser(TenantId tenantId, UUID conversationId, UUID userId) {
        return conversationJdbc.existsForUser(tenantId, conversationId, userId);
    }

    /**
     * 读取历史。
     *
     * @param tenantId 租户
     * @param conversationId 会话 ID
     * @param limit 上限
     * @return 历史
     */
    public List<ConversationMessage> history(TenantId tenantId, UUID conversationId, int limit) {
        return conversationJdbc.history(tenantId, conversationId, limit);
    }

    /**
     * 读取用户消息。
     *
     * @param tenantId 租户
     * @param conversationId 会话 ID
     * @param messageId 消息 ID
     * @return 消息
     */
    public Optional<ConversationMessage> findUserMessage(TenantId tenantId, UUID conversationId, UUID messageId) {
        return conversationJdbc.findUserMessage(tenantId, conversationId, messageId);
    }
}
