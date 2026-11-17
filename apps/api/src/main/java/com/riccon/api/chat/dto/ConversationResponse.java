package com.riccon.api.chat.dto;

import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.domain.ConversationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话响应，对应 OpenAPI {@code Conversation}。
 *
 * @param id 会话 ID
 * @param workspaceId 所属工作空间
 * @param title 标题；可为空
 * @param status 状态
 * @param lastMessageAt 最近消息时间；可为空
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ConversationResponse(
        UUID id,
        UUID workspaceId,
        String title,
        ConversationStatus status,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 将持久化快照映射为响应。
     *
     * @param conversation 会话快照
     * @return 响应
     */
    public static ConversationResponse from(ConversationRecord conversation) {
        return new ConversationResponse(
                conversation.id(),
                conversation.workspaceId(),
                conversation.title(),
                conversation.status(),
                conversation.lastMessageAt(),
                conversation.createdAt(),
                conversation.updatedAt());
    }
}
