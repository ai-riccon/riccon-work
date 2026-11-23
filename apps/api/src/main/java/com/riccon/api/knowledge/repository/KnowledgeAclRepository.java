package com.riccon.api.knowledge.repository;

import com.riccon.api.knowledge.domain.KnowledgeAclEntry;
import com.riccon.api.knowledge.persistence.KnowledgeAclJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 文档用户级 ACL 仓储。
 *
 * <p>只暴露显式名单查询与整体替换；创建人隐式可见由服务层单独合并，不写入本表。
 */
@Repository
public class KnowledgeAclRepository {

    /** ACL SQL。 */
    private final KnowledgeAclJdbc aclJdbc;

    /**
     * 创建仓储。
     *
     * @param aclJdbc ACL SQL
     */
    public KnowledgeAclRepository(KnowledgeAclJdbc aclJdbc) {
        this.aclJdbc = Objects.requireNonNull(aclJdbc, "aclJdbc must not be null");
    }

    /**
     * 查询名单及展示名。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 名单
     */
    public List<KnowledgeAclEntry> findUsers(TenantId tenantId, UUID documentId) {
        return aclJdbc.findUsers(tenantId, documentId);
    }

    /**
     * 查询显式授权账号。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 账号 ID 集合
     */
    public Set<UUID> findUserIds(TenantId tenantId, UUID documentId) {
        return aclJdbc.findUserIds(tenantId, documentId);
    }

    /**
     * 整体替换名单。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param userIds 新名单
     * @param grantedBy 授权操作者
     * @param now 授权时间
     */
    public void replace(TenantId tenantId, UUID documentId, List<UUID> userIds, UUID grantedBy, Instant now) {
        aclJdbc.replace(tenantId, documentId, userIds, grantedBy, now);
    }

    /**
     * 统计显式授权人数。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 人数
     */
    public long count(TenantId tenantId, UUID documentId) {
        return aclJdbc.count(tenantId, documentId);
    }
}
