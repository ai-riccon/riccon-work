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
 * <p>只做持久化编排；工作空间可见性、保留期、标题策略与审计由服务层决定。M03 起本仓储不按创建人过滤，
 * 共享空间的成员可以读取同一会话。
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
     * 按租户读取未删除会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 会话
     */
    public Optional<ConversationRecord> findById(TenantId tenantId, UUID conversationId) {
        return conversationJdbc.findById(tenantId, conversationId);
    }

    /**
     * 锁定并查询未删除会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 会话
     */
    public Optional<ConversationRecord> findByIdForUpdate(TenantId tenantId, UUID conversationId) {
        return conversationJdbc.findByIdForUpdate(tenantId, conversationId);
    }

    /**
     * 统计工作空间内活跃会话。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 总数
     */
    public long countActive(TenantId tenantId, UUID workspaceId) {
        return conversationJdbc.countActive(tenantId, workspaceId);
    }

    /**
     * 分页列出工作空间内活跃会话。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 会话列表
     */
    public List<ConversationRecord> listActive(TenantId tenantId, UUID workspaceId, int limit, int offset) {
        return conversationJdbc.listActive(tenantId, workspaceId, limit, offset);
    }

    /**
     * 修改标题。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param title 新标题
     * @param now 当前时间
     * @return 受影响行数
     */
    public int updateTitle(TenantId tenantId, UUID conversationId, String title, Instant now) {
        return conversationJdbc.updateTitle(tenantId, conversationId, title, now);
    }

    /**
     * 发送消息后刷新标题与最近消息时间。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param autoTitle 空标题时使用的自动标题
     * @param now 当前时间
     * @return 受影响行数
     */
    public int touchAfterMessage(TenantId tenantId, UUID conversationId, String autoTitle, Instant now) {
        return conversationJdbc.touchAfterMessage(tenantId, conversationId, autoTitle, now);
    }

    /**
     * 软删除会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID conversationId, Instant now) {
        return conversationJdbc.softDelete(tenantId, conversationId, now);
    }
}
