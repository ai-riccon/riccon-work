package com.riccon.api.chat.dto;

import com.riccon.api.chat.domain.MessageRecord;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.chat.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 消息响应，对应 OpenAPI {@code Message}。
 *
 * @param id 消息 ID
 * @param conversationId 所属会话
 * @param seq 会话内序号
 * @param role 角色
 * @param content 内容
 * @param status 状态
 * @param clientMessageId 客户端幂等键；仅用户消息
 * @param runId 关联运行
 * @param modelId 生成模型
 * @param errorCode 失败错误码
 * @param citations 引用快照
 * @param createdAt 创建时间
 */
public record MessageResponse(
        UUID id,
        UUID conversationId,
        long seq,
        MessageRole role,
        String content,
        MessageStatus status,
        String clientMessageId,
        UUID runId,
        UUID modelId,
        String errorCode,
        List<CitationResponse> citations,
        Instant createdAt) {

    /**
     * 将持久化快照映射为响应。
     *
     * @param message 消息快照
     * @return 响应
     */
    public static MessageResponse from(MessageRecord message) {
        return new MessageResponse(
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
                message.citations().stream().map(CitationResponse::from).toList(),
                message.createdAt());
    }
}
