package com.riccon.api.knowledge.persistence;

import com.riccon.api.knowledge.domain.KnowledgeAclEntry;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 文档用户级 ACL SQL。
 *
 * <p>主键 {@code (tenant_id, document_id, user_id)} 覆盖“某文档的授权列表”；创建人隐式可见不写入本表，
 * 避免“移除创建人”造成自锁。整体替换在服务层事务内先删后插，保存后下一次请求立即生效。
 */
@Component
public class KnowledgeAclJdbc {

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建 ACL SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public KnowledgeAclJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 查询文档显式名单及展示名。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 名单，按展示名排序
     */
    public List<KnowledgeAclEntry> findUsers(TenantId tenantId, UUID documentId) {
        return tenantJdbc.query(
                tenantId,
                """
                SELECT a.user_id, u.display_name
                  FROM knowledge_document_acl a
                  JOIN app_user u
                    ON u.tenant_id = a.tenant_id
                   AND u.id = a.user_id
                 WHERE a.tenant_id = :tenantId
                   AND a.document_id = :documentId
                 ORDER BY u.display_name ASC, a.user_id ASC
                """,
                Map.of("documentId", BinaryUuids.toBytes(documentId)),
                (rs, rowNum) -> new KnowledgeAclEntry(
                        BinaryUuids.fromBytes(rs.getBytes("user_id")), rs.getString("display_name")));
    }

    /**
     * 查询文档显式授权账号 ID。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 账号 ID 集合
     */
    public Set<UUID> findUserIds(TenantId tenantId, UUID documentId) {
        return findUsers(tenantId, documentId).stream()
                .map(KnowledgeAclEntry::userId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 整体替换显式名单。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param userIds 新名单；必须已去重且不含创建人
     * @param grantedBy 授权操作者
     * @param now 授权时间
     */
    public void replace(TenantId tenantId, UUID documentId, List<UUID> userIds, UUID grantedBy, Instant now) {
        tenantJdbc.update(
                tenantId,
                "DELETE FROM knowledge_document_acl WHERE tenant_id = :tenantId AND document_id = :documentId",
                Map.of("documentId", BinaryUuids.toBytes(documentId)));
        for (UUID userId : userIds) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("documentId", BinaryUuids.toBytes(documentId));
            params.put("userId", BinaryUuids.toBytes(userId));
            params.put("grantedBy", BinaryUuids.toBytes(grantedBy));
            params.put("createdAt", SqlTimestamps.toDatabase(now));
            tenantJdbc.insert(tenantId, """
                    INSERT INTO knowledge_document_acl
                        (tenant_id, document_id, user_id, granted_by, created_at)
                    VALUES
                        (:tenantId, :documentId, :userId, :grantedBy, :createdAt)
                    """, params);
        }
    }

    /**
     * 查询文档显式授权人数。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 人数
     */
    public long count(TenantId tenantId, UUID documentId) {
        Long value = tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT COUNT(*) FROM knowledge_document_acl
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                        """,
                        Map.of("documentId", BinaryUuids.toBytes(documentId)),
                        (rs, rowNum) -> rs.getLong(1))
                .orElse(0L);
        return value;
    }
}
