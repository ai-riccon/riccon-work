package com.riccon.api.knowledge.connector.domain;

import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 连接器源对象同步状态行。
 *
 * <p>{@code externalId} 完整值只用于服务端比对与排障；响应只返回末段/截断值。
 *
 * @param id 条目 ID
 * @param externalId 源对象 key（库内完整值）
 * @param externalVersion ETag/version
 * @param checksum 内容 SHA-256
 * @param documentId 对应知识库文档
 * @param generation 删除-重现代数
 * @param status 同步状态
 * @param errorCode 失败码
 * @param lastSeenAt 最近一次在 list 中出现
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param deletedAt 软删除/删除传播时间
 */
public record ConnectorItemRecord(
        UUID id,
        String externalId,
        String externalVersion,
        String checksum,
        UUID documentId,
        int generation,
        ConnectorItemStatus status,
        String errorCode,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {}
