package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.repository.AgentContextRepository;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * RETRIEVAL 节点执行器（ADR-0024 §4.4）。
 *
 * <p>用运行输入/上游摘要渲染查询模板，以<b>运行发起人</b>作为主体调用 M05 检索入口
 * {@code AgentContextRepository.search(..., unrestricted=false, ...)}；不信任客户端，也不使用
 * 管理员绕过 ACL。命中数写入输出供条件节点使用，命中固化为引用快照并批量写
 * {@code source_version}（文档 checksum），供运行详情读取时计算 {@code sourceStatus}。
 *
 * <p>检索超时由引擎提供的截止时间约束：查询前后检查；数据库查询本身不可强杀，超时后丢弃结果并失败。
 */
@Component
public class RetrievalNodeExecutor implements WorkflowNodeExecutor {

    /** 引用片段最大字符数。 */
    static final int CITATION_SNIPPET_LENGTH = 500;

    /** 查询摘要最大字符数；不返回完整提示词/查询模板。 */
    static final int QUERY_SUMMARY_LENGTH = 500;

    /** 检索仓储。 */
    private final AgentContextRepository agentContextRepository;

    /** 引擎仓储（引用版本快照）。 */
    private final WorkflowEngineRepository repository;

    /** 模板渲染。 */
    private final WorkflowTemplateRenderer renderer;

    /** M05 检索预算。 */
    private final AgentProperties agentProperties;

    /**
     * 创建检索执行器。
     *
     * @param agentContextRepository M05 检索仓储
     * @param repository 引擎仓储
     * @param renderer 模板渲染
     * @param agentProperties M05 检索预算
     */
    public RetrievalNodeExecutor(
            AgentContextRepository agentContextRepository,
            WorkflowEngineRepository repository,
            WorkflowTemplateRenderer renderer,
            AgentProperties agentProperties) {
        this.agentContextRepository = Objects.requireNonNull(agentContextRepository, "agentContextRepository");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.agentProperties = Objects.requireNonNull(agentProperties, "agentProperties");
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeType nodeType() {
        return WorkflowNodeType.RETRIEVAL;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        execution.guard().requireActive();
        execution.deadline().requireRemaining();
        Integer configuredTopK = execution.node().config().topK();
        if (configuredTopK == null || configuredTopK < 1 || configuredTopK > 20) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "检索节点 topK 配置非法");
        }
        String queryTemplate = execution.node().config().queryTemplate();
        if (queryTemplate == null || queryTemplate.isBlank()) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "检索节点缺少查询模板");
        }
        String query = renderer.render(queryTemplate, execution.inputs(), execution.upstreamOutputs());
        int topK = configuredTopK;
        int candidateLimit = Math.min(
                WorkflowEngineLimits.MAX_RETRIEVAL_CANDIDATES,
                Math.max(agentProperties.retrievalCandidateLimit(), topK));
        List<RetrievedChunk> hits;
        try {
            // 运行发起人是检索 ACL 主体；unrestricted 固定 false，工作流没有管理员绕过。
            hits = agentContextRepository.search(
                    execution.tenantId(), execution.requesterUserId(), false, query, candidateLimit, topK);
        } catch (RuntimeException ex) {
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_RETRIEVAL_FAILED, "检索失败，请稍后重试或检查知识库检索配置", ex);
        }
        execution.guard().requireActive();
        execution.deadline().requireRemaining();
        List<WorkflowCitation> citations = buildCitations(execution, hits);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("hitCount", hits.size());
        return WorkflowNodeResult.of(
                truncate("检索查询：" + query, QUERY_SUMMARY_LENGTH),
                "检索命中 " + hits.size() + " 条",
                output,
                citations);
    }

    /**
     * 固化引用快照并批量写入文档版本。
     *
     * @param execution 执行上下文
     * @param hits 检索命中
     * @return 引用列表
     */
    private List<WorkflowCitation> buildCitations(WorkflowNodeExecution execution, List<RetrievedChunk> hits) {
        List<RetrievedChunk> limited = hits.size() > WorkflowEngineLimits.MAX_CITATIONS_PER_NODE
                ? hits.subList(0, WorkflowEngineLimits.MAX_CITATIONS_PER_NODE)
                : hits;
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (RetrievedChunk hit : limited) {
            if (hit.knowledgeDocumentId() != null) {
                documentIds.add(hit.knowledgeDocumentId());
            }
        }
        Map<UUID, String> versions;
        try {
            versions = documentIds.isEmpty()
                    ? Map.of()
                    : repository.findKnowledgeChecksums(execution.tenantId(), documentIds);
        } catch (RuntimeException ex) {
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_RETRIEVAL_FAILED, "引用版本信息读取失败，请稍后重试", ex);
        }
        List<WorkflowCitation> citations = new ArrayList<>(limited.size());
        int ordinal = 1;
        for (RetrievedChunk hit : limited) {
            citations.add(new WorkflowCitation(
                    ordinal++,
                    hit.sourceType(),
                    hit.fileId(),
                    hit.knowledgeDocumentId(),
                    hit.chunkId(),
                    hit.locatorType(),
                    hit.pageNo(),
                    hit.sheetName(),
                    hit.cellRange(),
                    hit.paragraphNo(),
                    hit.title(),
                    hit.snippet(CITATION_SNIPPET_LENGTH),
                    hit.score(),
                    hit.knowledgeDocumentId() == null ? null : versions.get(hit.knowledgeDocumentId())));
        }
        return citations;
    }

    /**
     * 截断摘要；不返回完整查询模板或文档正文。
     *
     * @param value 文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
