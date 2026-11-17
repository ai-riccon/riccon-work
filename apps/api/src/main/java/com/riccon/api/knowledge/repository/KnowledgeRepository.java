package com.riccon.api.knowledge.repository;

import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.persistence.KnowledgeJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 知识库文档仓储。
 *
 * <p>封装文档元数据 SQL；索引分块与 OpenSearch 写入由 Worker 负责。
 */
@Repository
public class KnowledgeRepository {

    /** 知识库 SQL。 */
    private final KnowledgeJdbc knowledgeJdbc;

    /**
     * 创建仓储。
     *
     * @param knowledgeJdbc 知识库 SQL
     */
    public KnowledgeRepository(KnowledgeJdbc knowledgeJdbc) {
        this.knowledgeJdbc = Objects.requireNonNull(knowledgeJdbc, "knowledgeJdbc must not be null");
    }

    /**
     * 插入文档。
     *
     * @param tenantId 当前租户
     * @param document 文档
     */
    public void insert(TenantId tenantId, KnowledgeDocumentRecord document) {
        knowledgeJdbc.insert(tenantId, document);
    }

    /**
     * 按 ID 查询文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findById(TenantId tenantId, UUID documentId) {
        return knowledgeJdbc.findById(tenantId, documentId);
    }

    /**
     * 按来源文件查询文档。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findByFileId(TenantId tenantId, UUID fileId) {
        return knowledgeJdbc.findByFileId(tenantId, fileId);
    }

    /**
     * 统计文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态
     * @return 总数
     */
    public long count(TenantId tenantId, KnowledgeDocumentStatus status) {
        return knowledgeJdbc.count(tenantId, status);
    }

    /**
     * 分页查询文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文档列表
     */
    public List<KnowledgeDocumentRecord> list(
            TenantId tenantId, KnowledgeDocumentStatus status, int limit, int offset) {
        return knowledgeJdbc.list(tenantId, status, limit, offset);
    }
}
