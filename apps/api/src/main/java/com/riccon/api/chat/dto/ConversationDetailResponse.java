package com.riccon.api.chat.dto;

import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.domain.ConversationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话详情响应，对应 OpenAPI {@code ConversationDetail}。
 *
 * <p>包含会话字段与最近消息（最多 100 条，按 seq 升序）；更早的消息通过消息列表接口按 {@code beforeSeq} 翻页。
 *
 * @param id 会话 ID
 * @param workspaceId 所属工作空间
 * @param title 标题
 * @param status 状态
 * @param lastMessageAt 最近消息时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param messages 最近消息
 */
public record ConversationDetailResponse(
        UUID id,
        UUID workspaceId,
        String title,
        ConversationStatus status,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt,
        List<MessageResponse> messages) {

    /**
     * 组装会话详情。
     *
     * @param conversation 会话快照
     * @param messages 最近消息
     * @return 响应
     */
    public static ConversationDetailResponse from(ConversationRecord conversation, List<MessageResponse> messages) {
        return new ConversationDetailResponse(
                conversation.id(),
                conversation.workspaceId(),
                conversation.title(),
                conversation.status(),
                conversation.lastMessageAt(),
                conversation.createdAt(),
                conversation.updatedAt(),
                messages);
    }
}
