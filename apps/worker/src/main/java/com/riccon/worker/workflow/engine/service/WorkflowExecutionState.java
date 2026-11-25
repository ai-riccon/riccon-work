package com.riccon.worker.workflow.engine.service;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptRow;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行内节点状态（崩溃恢复与重试的只读重建）。
 *
 * <p>从 {@code workflow_node_run} 一次读取全部尝试，按 node key 保留最新尝试；成功尝试的输出、
 * 摘要、引用与字节数用于下游模板/条件/结果组装，避免重试时重跑上游（ADR-0024 §4.6）。
 * 字节数按存储 JSON 原文计算，用于 5 MiB 总量预算。
 */
final class WorkflowExecutionState {

    /** 最新尝试状态。 */
    private final Map<String, NodeState> latest = new LinkedHashMap<>();

    /** 成功节点输出。 */
    private final Map<String, WorkflowNodeOutput> outputs = new LinkedHashMap<>();

    /** 成功节点引用。 */
    private final Map<String, List<WorkflowCitation>> citations = new LinkedHashMap<>();

    /** 成功节点输出 + 引用字节数。 */
    private final Map<String, Long> outputBytes = new LinkedHashMap<>();

    /** 节点尝试总数（步数预算）。 */
    private int attemptCount;

    /** 工具类禁止实例化。 */
    private WorkflowExecutionState() {}

    /**
     * 从节点尝试行重建状态。
     *
     * @param graph 发布图；用于拒绝不属于当前版本的脏节点行
     * @param rows 节点尝试行（按 node_key, attempt 升序）
     * @param objectMapper JSON 解析
     * @return 运行状态
     * @throws WorkflowNodeFailureException 存储的节点输出/引用损坏（{@code WF_INTERNAL}）
     */
    static WorkflowExecutionState load(
            WorkflowGraph graph, List<WorkflowNodeAttemptRow> rows, ObjectMapper objectMapper) {
        WorkflowExecutionState state = new WorkflowExecutionState();
        for (WorkflowNodeAttemptRow row : rows) {
            if (graph.findNode(row.nodeKey()).isEmpty()) {
                throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "节点尝试不属于发布图，拒绝继续执行");
            }
            state.attemptCount++;
            state.latest.put(row.nodeKey(), new NodeState(row.id(), row.attempt(), row.status()));
        }
        for (WorkflowNodeAttemptRow row : rows) {
            NodeState current = state.latest.get(row.nodeKey());
            if (current == null || current.attempt() != row.attempt() || row.status() != WorkflowNodeStatus.SUCCEEDED) {
                continue;
            }
            state.recordSuccess(row, objectMapper);
        }
        return state;
    }

    /**
     * 读取节点最新尝试。
     *
     * @param nodeKey 节点 key
     * @return 最新状态；没有尝试为空
     */
    Optional<NodeState> latest(String nodeKey) {
        return Optional.ofNullable(latest.get(nodeKey));
    }

    /**
     * 写入/覆盖节点最新尝试状态（无行 ID，例如 INSERT 后的本地乐观状态）。
     *
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param status 状态
     */
    void put(String nodeKey, int attempt, WorkflowNodeStatus status) {
        latest.put(nodeKey, new NodeState(null, attempt, status));
    }

    /**
     * 记录一次新节点尝试（步数预算 +1）。
     *
     * @param nodeKey 节点 key
     * @param attemptId 节点尝试 ID
     * @param attempt 尝试号
     * @param status 初始状态
     */
    void addAttempt(String nodeKey, UUID attemptId, int attempt, WorkflowNodeStatus status) {
        latest.put(nodeKey, new NodeState(attemptId, attempt, status));
        attemptCount++;
    }

    /**
     * 返回尝试总数。
     *
     * @return 尝试数
     */
    int attemptCount() {
        return attemptCount;
    }

    /**
     * 已成功节点输出。
     *
     * @return 不可变输出视图
     */
    Map<String, WorkflowNodeOutput> outputs() {
        return Map.copyOf(outputs);
    }

    /**
     * 已成功节点输出与引用累计字节数。
     *
     * @return 字节数
     */
    long outputBytesTotal() {
        return outputBytes.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * 记录节点成功输出。
     *
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param output 输出 JSON
     * @param summary 输出摘要
     * @param outputJson 输出 JSON 原文（用于字节数）
     * @param citationsJson 引用 JSON 原文
     * @param nodeCitations 引用列表
     */
    void recordSuccess(
            String nodeKey,
            int attempt,
            JsonNode output,
            String summary,
            String outputJson,
            String citationsJson,
            List<WorkflowCitation> nodeCitations) {
        latest.put(nodeKey, new NodeState(null, attempt, WorkflowNodeStatus.SUCCEEDED));
        outputs.put(nodeKey, new WorkflowNodeOutput(output, summary));
        citations.put(nodeKey, List.copyOf(nodeCitations));
        long bytes = bytes(outputJson) + (citationsJson == null ? 0 : bytes(citationsJson));
        outputBytes.put(nodeKey, bytes);
    }

    /**
     * 聚合全部已执行检索节点引用并去重（按来源 + 分块），重新编号。
     *
     * @param graph 发布图；用于稳定节点顺序
     * @return 引用列表
     */
    List<WorkflowCitation> aggregateCitations(WorkflowGraph graph) {
        Map<String, WorkflowCitation> deduplicated = new LinkedHashMap<>();
        for (WorkflowGraphNode node : graph.nodes()) {
            List<WorkflowCitation> nodeCitations = citations.get(node.id());
            if (nodeCitations == null) {
                continue;
            }
            for (WorkflowCitation citation : nodeCitations) {
                String key = citation.sourceType()
                        + "|"
                        + citation.fileId()
                        + "|"
                        + citation.knowledgeDocumentId()
                        + "|"
                        + citation.chunkId();
                deduplicated.putIfAbsent(key, citation);
            }
        }
        List<WorkflowCitation> aggregated = new ArrayList<>(deduplicated.size());
        int ordinal = 1;
        for (WorkflowCitation citation : deduplicated.values()) {
            aggregated.add(new WorkflowCitation(
                    ordinal++,
                    citation.sourceType(),
                    citation.fileId(),
                    citation.knowledgeDocumentId(),
                    citation.chunkId(),
                    citation.locatorType(),
                    citation.pageNo(),
                    citation.sheetName(),
                    citation.cellRange(),
                    citation.paragraphNo(),
                    citation.title(),
                    citation.snippet(),
                    citation.score(),
                    citation.sourceVersion()));
        }
        return List.copyOf(aggregated);
    }

    /**
     * 解析成功尝试的存储内容。
     *
     * @param row 成功尝试
     * @param objectMapper JSON 解析
     */
    private void recordSuccess(WorkflowNodeAttemptRow row, ObjectMapper objectMapper) {
        JsonNode output;
        List<WorkflowCitation> nodeCitations;
        try {
            output = row.outputJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(row.outputJson());
            nodeCitations = row.citationsJson() == null
                    ? List.of()
                    : objectMapper.readValue(row.citationsJson(), new TypeReference<List<WorkflowCitation>>() {});
        } catch (JacksonException exception) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "节点输出快照损坏，无法恢复运行", exception);
        }
        recordSuccess(
                row.nodeKey(),
                row.attempt(),
                output,
                row.outputSummary() == null ? "" : row.outputSummary(),
                row.outputJson(),
                row.citationsJson(),
                nodeCitations);
    }

    /**
     * 计算 JSON 文本 UTF-8 字节数。
     *
     * @param json JSON 文本；空视为 0
     * @return 字节数
     */
    private static long bytes(String json) {
        return json == null ? 0 : json.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 节点最新尝试。
     *
     * @param id 节点尝试 ID；仅 PENDING 行复用需要
     * @param attempt 尝试号
     * @param status 状态
     */
    record NodeState(UUID id, int attempt, WorkflowNodeStatus status) {

        /** 校验状态。 */
        NodeState {
            Objects.requireNonNull(status, "status must not be null");
        }
    }
}
