package com.riccon.api.knowledge.domain;

import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 知识库文档行快照。
 *
 * <p>MySQL 是权威元数据；索引状态由 Worker 推进。M02 无文档级 ACL，同租户员工可检索全部文档。
 *
 * @param id 文档 ID
 * @param title 标题
 * @param sourceType 来源类型
 * @param fileId 来源文件，可为空
 * @param checksum 内容 SHA-256
 * @param mimeType 来源 MIME
 * @param sizeBytes 来源大小
 * @param status 索引状态
 * @param errorCode 失败错误码
 * @param errorMessage 脱敏失败原因
 * @param indexedAt 索引完成时间
 * @param createdBy 添加人
 * @param createdAt 创建时间
 */
public record KnowledgeDocumentRecord(
        UUID id,
        String title,
        KnowledgeSourceType sourceType,
        UUID fileId,
        String checksum,
        String mimeType,
        long sizeBytes,
        KnowledgeDocumentStatus status,
        String errorCode,
        String errorMessage,
        Instant indexedAt,
        UUID createdBy,
        Instant createdAt) {

    /** 校验必填字段。 */
    public KnowledgeDocumentRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
    }
}
