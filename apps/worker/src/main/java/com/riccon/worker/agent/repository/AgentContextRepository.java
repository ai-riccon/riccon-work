package com.riccon.worker.agent.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.persistence.AgentContextJdbc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Agent 检索仓储。
 *
 * <p>只读 MySQL 权威分块；不访问 OpenSearch 或对象存储，避免 Agent 运行依赖可重建索引可用性。
 */
@Component
public class AgentContextRepository {

    /** 检索访问。 */
    private final AgentContextJdbc contextJdbc;

    /**
     * 创建检索仓储。
     *
     * @param contextJdbc 检索访问
     */
    public AgentContextRepository(AgentContextJdbc contextJdbc) {
        this.contextJdbc = Objects.requireNonNull(contextJdbc, "contextJdbc must not be null");
    }

    /**
     * 按文档 ACL 检索知识库分块。
     *
     * @param tenantId 租户
     * @param actorUserId 当前 run 的用户
     * @param unrestricted 是否管理性读取（服务端判定）
     * @param query 查询
     * @param candidateLimit 候选上限
     * @param limit 返回上限
     * @return 命中
     */
    public List<RetrievedChunk> search(
            TenantId tenantId, UUID actorUserId, boolean unrestricted, String query, int candidateLimit, int limit) {
        return contextJdbc.search(tenantId, actorUserId, unrestricted, query, candidateLimit, limit);
    }

    /**
     * 判断用户是否持有知识库 ACL 管理权限。
     *
     * @param tenantId 租户
     * @param userId 用户
     * @return 持有管理权限时返回 {@code true}
     */
    public boolean isKnowledgeAclManager(TenantId tenantId, UUID userId) {
        return contextJdbc.isKnowledgeAclManager(tenantId, userId);
    }

    /**
     * 判断文档是否按 ACL 对用户可见。
     *
     * @param tenantId 租户
     * @param actorUserId 用户
     * @param documentId 文档
     * @return 按 ACL 可见时返回 {@code true}
     */
    public boolean isVisibleByAcl(TenantId tenantId, UUID actorUserId, UUID documentId) {
        return contextJdbc.isVisibleByAcl(tenantId, actorUserId, documentId);
    }

    /**
     * 读取文档 ACL 摘要。
     *
     * @param tenantId 租户
     * @param documentId 文档
     * @return 摘要
     */
    public Optional<DocumentAclSummary> findAclSummary(TenantId tenantId, UUID documentId) {
        return contextJdbc
                .findAclSummary(tenantId, documentId)
                .map(summary -> new DocumentAclSummary(summary.visibility(), summary.aclUserCount()));
    }

    /**
     * 读取指定文件的最新解析分块。
     *
     * @param tenantId 租户
     * @param fileIds 文件 ID
     * @param perFileLimit 每文件上限
     * @return 分块
     */
    public List<RetrievedChunk> fileChunks(TenantId tenantId, List<UUID> fileIds, int perFileLimit) {
        return contextJdbc.fileChunks(tenantId, fileIds, perFileLimit);
    }

    /**
     * 校验文件可用。
     *
     * @param tenantId 租户
     * @param fileId 文件 ID
     * @return 是否可用
     */
    public boolean fileAvailable(TenantId tenantId, UUID fileId) {
        return contextJdbc.fileAvailable(tenantId, fileId);
    }

    /**
     * 文档 ACL 摘要（供管理性 SEARCH 审计）。
     *
     * @param visibility 可见范围
     * @param aclUserCount 显式授权人数
     */
    public record DocumentAclSummary(KnowledgeVisibility visibility, long aclUserCount) {}
}
