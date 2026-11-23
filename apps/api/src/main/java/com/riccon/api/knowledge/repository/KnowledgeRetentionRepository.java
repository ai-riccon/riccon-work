package com.riccon.api.knowledge.repository;

import com.riccon.api.knowledge.domain.KnowledgeTenantRetention;
import com.riccon.api.knowledge.persistence.KnowledgeRetentionJdbc;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 知识库保留任务仓储。
 *
 * <p>只读取租户目录的保留配置；文档到期查询仍走 {@link KnowledgeRepository}，保持租户上下文。
 */
@Repository
public class KnowledgeRetentionRepository {

    /** 保留目录 SQL。 */
    private final KnowledgeRetentionJdbc retentionJdbc;

    /**
     * 创建仓储。
     *
     * @param retentionJdbc 保留目录 SQL
     */
    public KnowledgeRetentionRepository(KnowledgeRetentionJdbc retentionJdbc) {
        this.retentionJdbc = Objects.requireNonNull(retentionJdbc, "retentionJdbc must not be null");
    }

    /**
     * 查询启用租户及保留天数。
     *
     * @return 租户保留配置
     */
    public List<KnowledgeTenantRetention> findActiveTenants() {
        return retentionJdbc.findActiveTenants().stream()
                .map(tenant -> new KnowledgeTenantRetention(tenant.tenantId(), tenant.retentionDays()))
                .toList();
    }
}
