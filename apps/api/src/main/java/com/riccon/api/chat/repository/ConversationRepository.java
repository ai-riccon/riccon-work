package com.riccon.api.chat.repository;

import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.persistence.ConversationJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 会话仓储。
 *
 * <p>只做持久化编排；可见性、保留期、标题策略与审计由服务层决定。
 */
@Repository
public class ConversationRepository {

    /** 会话 SQL。 */
    private final ConversationJdbc conversationJdbc;

    /**
     * 创建仓储。
     *
     * @param conversationJdbc 会话 SQL
     */
    public ConversationRepository(ConversationJdbc conversationJdbc) {
        this.conversationJdbc = Objects.requireNonNull(conversationJdbc, "conversationJdbc must not be null");
    }

    /**
     * 插入会话。
     *
     * @param tenantId 当前租户
     * @param conversation 会话快照
     * @param retentionExpiresAt 保留到期时间
     */
    public void insert(TenantId tenantId, ConversationRecord conversation, Instant retentionExpiresAt) {
        conversationJdbc.insert(tenantId, conversation, retentionExpiresAt);
    }

    /**
     * 查询创建人可见的会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param userId 创建人
     * @return 会话
     */
    public Optional<ConversationRecord> findOwned(TenantId tenantId, UUID conversationId, UUID userId) {
        return conversationJdbc.findOwned(tenantId, conversationId, userId);
    }

    /**
     * 锁定并查询创建人可见的会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param userId 创建人
     * @return 会话
     */
    public Optional<ConversationRecord> findOwnedForUpdate(TenantId tenantId, UUID conversationId, UUID userId) {
        return conversationJdbc.findOwnedForUpdate(tenantId, conversationId, userId);
    }

    /**
     * 统计创建人的活跃会话。
     *
     * @param tenantId 当前租户
     * @param userId 创建人
     * @return 总数
     */
    public long countOwned(TenantId tenantId, UUID userId) {
        return conversationJdbc.countOwned(tenantId, userId);
    }

    /**
     * 分页列出创建人的活跃会话。
     *
     * @param tenantId 当前租户
     * @param userId 创建人
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 会话列表
     */
    public List<ConversationRecord> listOwned(TenantId tenantId, UUID userId, int limit, int offset) {
        return conversationJdbc.listOwned(tenantId, userId, limit, offset);
    }

    /**
     * 修改标题。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param userId 创建人
     * @param title 新标题
     * @param now 当前时间
     * @return 受影响行数
     */
    public int updateTitle(TenantId tenantId, UUID conversationId, UUID userId, String title, Instant now) {
        return conversationJdbc.updateTitle(tenantId, conversationId, userId, title, now);
    }

    /**
     * 发送消息后刷新标题与最近消息时间。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param userId 创建人
     * @param autoTitle 空标题时使用的自动标题
     * @param now 当前时间
     * @return 受影响行数
     */
    public int touchAfterMessage(TenantId tenantId, UUID conversationId, UUID userId, String autoTitle, Instant now) {
        return conversationJdbc.touchAfterMessage(tenantId, conversationId, userId, autoTitle, now);
    }

    /**
     * 软删除会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param userId 创建人
     * @param now 当前时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID conversationId, UUID userId, Instant now) {
        return conversationJdbc.softDelete(tenantId, conversationId, userId, now);
    }
}
