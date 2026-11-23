package com.riccon.api.knowledge.dto;

import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeParseSummary;
import com.riccon.api.knowledge.domain.KnowledgeRetentionPolicy;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 知识库文档响应，对应 OpenAPI {@code KnowledgeDocument}。
 *
 * <p>不返回对象 key、预签名 URL 或分块原文；{@code sourceFileAccessLimited} 只提示“部分可见用户可能无法
 * 通过原工作空间打开源文件”，不放大原空间路径（ADR-0023 第 5.3 节）。
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
 * @param visibility 可见范围
 * @param departmentId 可见部门
 * @param aclUserCount 显式授权人数（不含创建人）
 * @param parseEngine 解析引擎
 * @param warnings 解析警告
 * @param parseSummary 结构摘要
 * @param sourceFileAccessLimited 文档可见集合是否宽于源文件空间
 * @param retentionExpiresAt 保留到期时间
 * @param retentionDays 生效保留天数
 * @param retentionPolicy 保留策略来源
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
        Instant createdAt,
        KnowledgeVisibility visibility,
        UUID departmentId,
        long aclUserCount,
        ParseEngine parseEngine,
        List<String> warnings,
        KnowledgeParseSummary parseSummary,
        boolean sourceFileAccessLimited,
        Instant retentionExpiresAt,
        int retentionDays,
        KnowledgeRetentionPolicy retentionPolicy) {

    /**
     * 映射文档快照。
     *
     * @param document 文档
     * @param tenantRetentionDays 当前租户保留天数
     * @return 响应
     */
    public static KnowledgeDocumentResponse from(KnowledgeDocumentRecord document, int tenantRetentionDays) {
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
                document.createdAt(),
                document.visibility(),
                document.departmentId(),
                document.aclUserCount(),
                document.parseEngine(),
                document.warnings(),
                document.parseSummary(),
                document.sourceFileAccessLimited(),
                document.retentionExpiresAt(),
                document.retentionDays(tenantRetentionDays),
                document.retentionPolicy(tenantRetentionDays));
    }
}
