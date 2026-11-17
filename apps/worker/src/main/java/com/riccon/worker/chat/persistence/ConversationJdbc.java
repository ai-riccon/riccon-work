package com.riccon.worker.chat.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 对话、消息与会话归属只读访问。
 *
 * <p>只读取当前租户的会话与消息；不读取系统提示或其他租户内容。历史上限由调用方配置，
 * SQL 不允许无界查询。
 */
@Component
public class ConversationJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建会话访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ConversationJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 校验会话属于指定用户且未删除。
     *
     * @param tenantId 租户
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 是否存在
     */
    public boolean existsForUser(TenantId tenantId, UUID conversationId, UUID userId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT 1 AS present
                          FROM conversation
                         WHERE tenant_id = :tenantId
                           AND id = :conversationId
                           AND created_by = :userId
                           AND deleted_at IS NULL
                        """,
                        Map.of(
                                "conversationId", UuidBinary.toBytes(conversationId),
                                "userId", UuidBinary.toBytes(userId)),
                        (rs, rowNum) -> rs.getInt("present"))
                .isPresent();
    }

    /**
     * 读取最近的历史消息，按 seq 升序返回。
     *
     * @param tenantId 租户
     * @param conversationId 会话 ID
     * @param limit 条数上限
     * @return 历史消息
     */
    public List<ConversationMessage> history(TenantId tenantId, UUID conversationId, int limit) {
        List<ConversationMessage> descending = tenantJdbc.query(
                tenantId,
                """
                SELECT id, role, content
                  FROM message
                 WHERE tenant_id = :tenantId
                   AND conversation_id = :conversationId
                   AND role IN ('USER', 'ASSISTANT')
                 ORDER BY seq DESC
                 LIMIT :messageLimit
                """,
                Map.of("conversationId", UuidBinary.toBytes(conversationId), "messageLimit", limit),
                (rs, rowNum) -> new ConversationMessage(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        MessageRole.valueOf(rs.getString("role")),
                        rs.getString("content")));
        List<ConversationMessage> ascending = new ArrayList<>(descending.size());
        for (int index = descending.size() - 1; index >= 0; index--) {
            ascending.add(descending.get(index));
        }
        return List.copyOf(ascending);
    }

    /**
     * 读取指定用户消息。
     *
     * @param tenantId 租户
     * @param conversationId 会话 ID
     * @param messageId 消息 ID
     * @return 消息；不存在或角色不是 USER 时为空
     */
    public Optional<ConversationMessage> findUserMessage(TenantId tenantId, UUID conversationId, UUID messageId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, role, content
                  FROM message
                 WHERE tenant_id = :tenantId
                   AND conversation_id = :conversationId
                   AND id = :messageId
                   AND role = 'USER'
                """,
                Map.of(
                        "conversationId", UuidBinary.toBytes(conversationId),
                        "messageId", UuidBinary.toBytes(messageId)),
                (rs, rowNum) -> new ConversationMessage(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        MessageRole.valueOf(rs.getString("role")),
                        rs.getString("content")));
    }
}
