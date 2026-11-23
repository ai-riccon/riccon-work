package com.riccon.worker.agent.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.repository.AgentContextRepository;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.config.AgentProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 知识库与文件上下文检索。
 *
 * <p>检索结果同时用于引用事件与模型数据块；引用序号在合并知识库与文件来源后重新编排。
 * 文件 ID 必须先通过租户校验，跨租户或不存在统一返回 404，不区分存在性。
 *
 * <p>M05：知识库检索必须带当前 run 用户；<code>unrestricted</code> 由服务端按
 * {@code knowledge.acl.manage} 判定，不信任客户端。管理员绕过 ACL 命中本不可见文档时写
 * {@code knowledge.document.admin_read scope=SEARCH}，审计失败使检索失败（fail-closed）。
 */
@Component
public class KnowledgeRetriever {

    /** 引用片段最大长度。 */
    public static final int SNIPPET_LENGTH = 500;

    /** 检索仓储。 */
    private final AgentContextRepository repository;

    /** Agent 预算配置。 */
    private final AgentProperties properties;

    /** Worker 审计写入。 */
    private final AuditService auditService;

    /**
     * 创建检索服务。
     *
     * @param repository 检索仓储
     * @param properties Agent 配置
     * @param auditService Worker 审计写入
     */
    public KnowledgeRetriever(
            AgentContextRepository repository, AgentProperties properties, AuditService auditService) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    /**
     * 为一次对话检索知识库与指定文件。
     *
     * @param tenantId 租户
     * @param actorUserId 当前 run 的用户
     * @param runId 运行 ID（作为审计关联 ID）
     * @param query 用户问题
     * @param fileIds 会话指定文件
     * @return 合并并重新编号的引用上下文
     */
    public List<RetrievedChunk> retrieve(
            TenantId tenantId, UUID actorUserId, UUID runId, String query, List<UUID> fileIds) {
        List<RetrievedChunk> combined = new ArrayList<>();
        if (fileIds != null && !fileIds.isEmpty()) {
            for (UUID fileId : fileIds) {
                if (!repository.fileAvailable(tenantId, fileId)) {
                    throw new AgentException(AgentException.NOT_FOUND, false, "file is not available");
                }
            }
            combined.addAll(repository.fileChunks(tenantId, fileIds, properties.fileChunkLimit()));
        }
        combined.addAll(searchKnowledge(tenantId, actorUserId, runId, query));
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
     * 仅检索知识库，供报告检索阶段与 knowledge_search 工具复用。
     *
     * @param tenantId 租户
     * @param actorUserId 当前 run 的用户
     * @param runId 运行 ID（作为审计关联 ID）
     * @param query 主题
     * @return 命中
     */
    public List<RetrievedChunk> retrieveKnowledge(TenantId tenantId, UUID actorUserId, UUID runId, String query) {
        return searchKnowledge(tenantId, actorUserId, runId, query);
    }

    /**
     * 按 ACL 检索知识库并对管理性命中写审计。
     *
     * @param tenantId 租户
     * @param actorUserId 当前 run 的用户
     * @param runId 运行 ID
     * @param query 查询
     * @return 命中
     */
    private List<RetrievedChunk> searchKnowledge(TenantId tenantId, UUID actorUserId, UUID runId, String query) {
        boolean unrestricted = repository.isKnowledgeAclManager(tenantId, actorUserId);
        List<RetrievedChunk> hits = repository.search(
                tenantId,
                actorUserId,
                unrestricted,
                query,
                properties.retrievalCandidateLimit(),
                properties.knowledgeTopK());
        if (unrestricted) {
            auditHiddenHits(tenantId, actorUserId, runId, hits);
        }
        return hits;
    }

    /**
     * 对管理员绕过 ACL 命中的文档写 SEARCH 审计。
     *
     * <p>按文档去重；只记录文档 ID、可见范围、授权人数与结果数，不写标题、片段或正文。
     * 审计失败直接抛出，让本次检索/运行失败（ADR-0023 第 5.6 节 fail-closed）。
     *
     * @param tenantId 租户
     * @param actorUserId 管理员
     * @param runId 运行 ID
     * @param hits 检索命中
     */
    private void auditHiddenHits(TenantId tenantId, UUID actorUserId, UUID runId, List<RetrievedChunk> hits) {
        Set<UUID> audited = new LinkedHashSet<>();
        for (RetrievedChunk hit : hits) {
            UUID documentId = hit.knowledgeDocumentId();
            if (documentId == null || !audited.add(documentId)) {
                continue;
            }
            if (repository.isVisibleByAcl(tenantId, actorUserId, documentId)) {
                continue;
            }
            AgentContextRepository.DocumentAclSummary summary = repository
                    .findAclSummary(tenantId, documentId)
                    .orElseThrow(() -> new IllegalStateException("knowledge document ACL summary is missing"));
            auditService.knowledgeAdminSearch(
                    tenantId,
                    actorUserId,
                    documentId,
                    summary.visibility().name(),
                    summary.aclUserCount(),
                    hits.size(),
                    runId.toString());
        }
    }
}
