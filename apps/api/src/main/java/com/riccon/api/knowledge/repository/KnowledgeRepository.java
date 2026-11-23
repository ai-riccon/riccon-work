package com.riccon.api.knowledge.repository;

import com.riccon.api.knowledge.domain.KnowledgeActor;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeVisibilityScope;
import com.riccon.api.knowledge.persistence.KnowledgeAccessJdbc;
import com.riccon.api.knowledge.persistence.KnowledgeJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 知识库文档仓储。
 *
 * <p>封装文档元数据 SQL 与可见性上下文解析；索引分块与 OpenSearch 写入由 Worker 负责。服务层只通过本类
 * 访问数据库，保证 ACL 谓词不会被某个入口绕开。
 */
@Repository
public class KnowledgeRepository {

    /** 可见性上下文解析。 */
    private final KnowledgeAccessJdbc accessJdbc;

    /** 知识库 SQL。 */
    private final KnowledgeJdbc knowledgeJdbc;

    /**
     * 创建仓储。
     *
     * @param accessJdbc 可见性上下文解析
     * @param knowledgeJdbc 知识库 SQL
     */
    public KnowledgeRepository(KnowledgeAccessJdbc accessJdbc, KnowledgeJdbc knowledgeJdbc) {
        this.accessJdbc = Objects.requireNonNull(accessJdbc, "accessJdbc must not be null");
        this.knowledgeJdbc = Objects.requireNonNull(knowledgeJdbc, "knowledgeJdbc must not be null");
    }

    /**
     * 解析操作者的可见性上下文。
     *
     * @param tenantId 当前租户
     * @param actor 操作者
     * @param enforceAcl 是否启用文档级 ACL
     * @return 可见性上下文
     */
    public KnowledgeVisibilityScope resolveScope(TenantId tenantId, KnowledgeActor actor, boolean enforceAcl) {
        return accessJdbc.resolve(tenantId, actor.userId(), actor.unrestricted(), enforceAcl);
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
     * 按 ID 查询未删除文档（内部使用，不判定 ACL）。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findActiveById(TenantId tenantId, UUID documentId) {
        return knowledgeJdbc.findActiveById(tenantId, documentId);
    }

    /**
     * 按来源文件查询未删除文档。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findActiveByFileId(TenantId tenantId, UUID fileId) {
        return knowledgeJdbc.findActiveByFileId(tenantId, fileId);
    }

    /**
     * 按 ACL 谓词查询未删除文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findVisibleById(
            TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return knowledgeJdbc.findVisibleById(tenantId, documentId, scope);
    }

    /**
     * 按 ACL 谓词查询文档，包含软删除。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findVisibleByIdIncludingDeleted(
            TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return knowledgeJdbc.findVisibleByIdIncludingDeleted(tenantId, documentId, scope);
    }

    /**
     * 管理性读取未删除文档，不判定 ACL。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findActiveByIdIgnoringAcl(TenantId tenantId, UUID documentId) {
        return knowledgeJdbc.findActiveByIdIgnoringAcl(tenantId, documentId);
    }

    /**
     * 统计文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态
     * @param scope 可见性上下文
     * @return 总数
     */
    public long count(TenantId tenantId, KnowledgeDocumentStatus status, KnowledgeVisibilityScope scope) {
        return knowledgeJdbc.count(tenantId, status, scope);
    }

    /**
     * 分页查询文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态
     * @param scope 可见性上下文
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文档列表
     */
    public List<KnowledgeDocumentRecord> list(
            TenantId tenantId, KnowledgeDocumentStatus status, KnowledgeVisibilityScope scope, int limit, int offset) {
        return knowledgeJdbc.list(tenantId, status, scope, limit, offset);
    }

    /**
     * 锁定可见且未删除的文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 命中时返回 {@code true}
     */
    public boolean lockVisibleId(TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return knowledgeJdbc.lockVisibleId(tenantId, documentId, scope);
    }

    /**
     * 锁定可见文档，包含软删除。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 命中时返回 {@code true}
     */
    public boolean lockVisibleIdIncludingDeleted(TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return knowledgeJdbc.lockVisibleIdIncludingDeleted(tenantId, documentId, scope);
    }

    /**
     * 软删除文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param now 删除时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID documentId, Instant now) {
        return knowledgeJdbc.softDelete(tenantId, documentId, now);
    }

    /**
     * 更新可见范围并递增 ACL 版本。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateVisibility(
            TenantId tenantId, UUID documentId, KnowledgeVisibility visibility, UUID departmentId, Instant now) {
        return knowledgeJdbc.updateVisibility(tenantId, documentId, visibility, departmentId, now);
    }

    /**
     * 查询启用部门。
     *
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @return 部门 ID
     */
    public Optional<UUID> findActiveDepartment(TenantId tenantId, UUID departmentId) {
        return knowledgeJdbc.findActiveDepartment(tenantId, departmentId);
    }

    /**
     * 查询全部启用账号。
     *
     * @param tenantId 当前租户
     * @return 账号 ID 集合
     */
    public Set<UUID> findActiveUserIds(TenantId tenantId) {
        return knowledgeJdbc.findActiveUserIds(tenantId);
    }

    /**
     * 查询账号展示名。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 展示名
     */
    public Optional<String> findDisplayName(TenantId tenantId, UUID userId) {
        return knowledgeJdbc.findDisplayName(tenantId, userId);
    }

    /**
     * 查询第一个启用账号，供保留清理事件使用。
     *
     * @param tenantId 当前租户
     * @return 账号 ID
     */
    public Optional<UUID> findFirstActiveUserId(TenantId tenantId) {
        return knowledgeJdbc.findFirstActiveUserId(tenantId);
    }

    /**
     * 判断文档是否已有分块。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 有分块时返回 {@code true}
     */
    public boolean hasChunks(TenantId tenantId, UUID documentId) {
        return knowledgeJdbc.hasChunks(tenantId, documentId);
    }

    /**
     * 计算下一次索引尝试号。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 尝试号
     */
    public int nextIndexAttempt(TenantId tenantId, UUID documentId) {
        return knowledgeJdbc.nextIndexAttempt(tenantId, documentId);
    }

    /**
     * 判断是否有排队或处理中的索引事件。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 存在时返回 {@code true}
     */
    public boolean hasPendingIndexEvent(TenantId tenantId, UUID documentId) {
        return knowledgeJdbc.hasPendingIndexEvent(tenantId, documentId);
    }

    /**
     * 查询保留到期文档。
     *
     * @param tenantId 当前租户
     * @param createdCutoff 无显式到期时的创建时间截止
     * @param now 当前时间
     * @param limit 批量上限
     * @return 到期文档
     */
    public List<KnowledgeDocumentRecord> findExpired(TenantId tenantId, Instant createdCutoff, Instant now, int limit) {
        return knowledgeJdbc.findExpired(tenantId, createdCutoff, now, limit);
    }
}
