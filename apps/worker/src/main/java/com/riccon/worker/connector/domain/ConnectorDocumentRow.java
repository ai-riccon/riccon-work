package com.riccon.worker.connector.domain;

import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * 连接器导入文档快照。
 *
 * @param documentId 文档 ID
 * @param checksum 内容哈希
 * @param status 文档状态
 * @param visibility 可见范围
 * @param aclVersion ACL 版本
 * @param createdAt 创建时间
 * @param deletedAt 删除时间
 */
public record ConnectorDocumentRow(
        UUID documentId,
        String checksum,
        KnowledgeDocumentStatus status,
        KnowledgeVisibility visibility,
        int aclVersion,
        Instant createdAt,
        Instant deletedAt) {

    /**
     * 判断文档是否仍可写。
     *
     * @return 未删除返回 true
     */
    public boolean active() {
        return deletedAt == null && status != KnowledgeDocumentStatus.DELETED;
    }
}
