package com.riccon.api.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话行快照。
 *
 * <p>会话属于工作空间并由创建人私有；M02 只允许创建人读取与写入，跨租户或他人会话统一 404。
 *
 * @param id 会话 ID（UUIDv7）
 * @param workspaceId 所属工作空间
 * @param createdBy 创建人账号 ID
 * @param title 标题；为空表示新会话
 * @param status 状态
 * @param lastMessageAt 最近消息时间；可为空
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ConversationRecord(
        UUID id,
        UUID workspaceId,
        UUID createdBy,
        String title,
        ConversationStatus status,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验必填字段，避免半成品行进入响应。 */
    public ConversationRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
