package com.riccon.api.knowledge.dto;

import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 知识库文档响应，对应 OpenAPI {@code KnowledgeDocument}。
 *
 * @param id 文档 ID
 * @param title 标题
 * @param sourceType 来源类型
 * @param fileId 来源文件
 * @param status 索引状态
 * @param checksum 内容 SHA-256
 * @param mimeType 来源 MIME
 * @param sizeBytes 来源大小
 * @param errorCode 失败错误码
 * @param errorMessage 脱敏失败原因
 * @param indexedAt 索引完成时间
 * @param createdAt 创建时间
 */
public record KnowledgeDocumentResponse(
        UUID id,
        String title,
        KnowledgeSourceType sourceType,
        UUID fileId,
        KnowledgeDocumentStatus status,
        String checksum,
        String mimeType,
        long sizeBytes,
        String errorCode,
        String errorMessage,
        Instant indexedAt,
        Instant createdAt) {

    /**
     * 映射文档快照。
     *
     * @param document 文档
     * @return 响应
     */
    public static KnowledgeDocumentResponse from(KnowledgeDocumentRecord document) {
        return new KnowledgeDocumentResponse(
                document.id(),
                document.title(),
                document.sourceType(),
                document.fileId(),
                document.status(),
                document.checksum(),
                document.mimeType(),
                document.sizeBytes(),
                document.errorCode(),
                document.errorMessage(),
                document.indexedAt(),
                document.createdAt());
    }
}
