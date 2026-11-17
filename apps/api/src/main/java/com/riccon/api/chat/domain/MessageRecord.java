package com.riccon.api.chat.domain;

import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.chat.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 消息行快照。
 *
 * <p>用户消息在发送时持久化为 COMPLETED；assistant 占位消息先 PENDING，收到 Worker started 后进入 STREAMING，
 * 终止事件推进到 COMPLETED/FAILED/STOPPED。{@code runId} 与 {@code modelId} 是指向其他聚合的逻辑引用。
 *
 * @param id 消息 ID（UUIDv7）
 * @param conversationId 所属会话
 * @param seq 会话内序号，从 1 开始
 * @param role 角色
 * @param content 内容；系统提示不经过本表回显
 * @param status 状态
 * @param clientMessageId 客户端幂等键；仅用户消息
 * @param runId 生成该消息的运行 ID；用户消息为空
 * @param modelId 生成该消息的模型 ID；可为空
 * @param errorCode 失败错误码；可为空
 * @param citations 引用快照
 * @param createdAt 创建时间
 */
public record MessageRecord(
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
        List<CitationRecord> citations,
        Instant createdAt) {

    /** 校验必填字段并复制引用列表。 */
    public MessageRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
