package com.riccon.worker.agent.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.persistence.AgentContextJdbc;
import java.util.List;
import java.util.Objects;
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
     * 检索知识库分块。
     *
     * @param tenantId 租户
     * @param query 查询
     * @param candidateLimit 候选上限
     * @param limit 返回上限
     * @return 命中
     */
    public List<RetrievedChunk> search(TenantId tenantId, String query, int candidateLimit, int limit) {
        return contextJdbc.search(tenantId, query, candidateLimit, limit);
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
}
