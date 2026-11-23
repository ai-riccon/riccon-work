package com.riccon.worker.connector.domain;

import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Worker 侧连接器源对象同步状态。
 *
 * <p>{@code externalId} 完整值只在 Worker 内部用于比对与去重；日志与事件 payload 不得写入。
 *
 * @param id 条目 ID
 * @param externalId 源对象 key
 * @param externalVersion ETag/version
 * @param checksum 内容 SHA-256
 * @param documentId 对应知识库文档
 * @param generation 删除-重现代数
 * @param status 同步状态
 * @param errorCode 失败码
 * @param lastSeenAt 最近一次在 list 中出现
 * @param deletedAt 删除时间
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
        Instant deletedAt) {}
