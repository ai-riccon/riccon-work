package com.riccon.worker.agent.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.repository.AgentContextRepository;
import com.riccon.worker.config.AgentProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 知识库与文件上下文检索。
 *
 * <p>检索结果同时用于引用事件与模型数据块；引用序号在合并知识库与文件来源后重新编排。
 * 文件 ID 必须先通过租户校验，跨租户或不存在统一返回 404，不区分存在性。
 */
@Component
public class KnowledgeRetriever {

    /** 引用片段最大长度。 */
    public static final int SNIPPET_LENGTH = 500;

    /** 检索仓储。 */
    private final AgentContextRepository repository;

    /** Agent 预算配置。 */
    private final AgentProperties properties;

    /**
     * 创建检索服务。
     *
     * @param repository 检索仓储
     * @param properties Agent 配置
     */
    public KnowledgeRetriever(AgentContextRepository repository, AgentProperties properties) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 为一次对话检索知识库与指定文件。
     *
     * @param tenantId 租户
     * @param query 用户问题
     * @param fileIds 会话指定文件
     * @return 合并并重新编号的引用上下文
     */
    public List<RetrievedChunk> retrieve(TenantId tenantId, String query, List<UUID> fileIds) {
        List<RetrievedChunk> combined = new ArrayList<>();
        if (fileIds != null && !fileIds.isEmpty()) {
            for (UUID fileId : fileIds) {
                if (!repository.fileAvailable(tenantId, fileId)) {
                    throw new AgentException(AgentException.NOT_FOUND, false, "file is not available");
                }
            }
            combined.addAll(repository.fileChunks(tenantId, fileIds, properties.fileChunkLimit()));
        }
        combined.addAll(
                repository.search(tenantId, query, properties.retrievalCandidateLimit(), properties.knowledgeTopK()));
        List<RetrievedChunk> renumbered = new ArrayList<>(combined.size());
        int ordinal = 1;
        for (RetrievedChunk chunk : combined) {
            renumbered.add(new RetrievedChunk(
                    ordinal++,
                    chunk.sourceType(),
                    chunk.fileId(),
                    chunk.knowledgeDocumentId(),
                    chunk.chunkId(),
                    chunk.locatorType(),
                    chunk.pageNo(),
                    chunk.sheetName(),
                    chunk.cellRange(),
                    chunk.paragraphNo(),
                    chunk.title(),
                    chunk.content(),
                    chunk.score()));
        }
        return List.copyOf(renumbered);
    }

    /**
     * 仅检索知识库，供报告检索阶段复用。
     *
     * @param tenantId 租户
     * @param query 主题
     * @return 命中
     */
    public List<RetrievedChunk> retrieveKnowledge(TenantId tenantId, String query) {
        return repository.search(tenantId, query, properties.retrievalCandidateLimit(), properties.knowledgeTopK());
    }
}
