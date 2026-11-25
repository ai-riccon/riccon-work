package com.riccon.worker.workflow.node.domain;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 单次节点执行上下文。
 *
 * <p>引擎在节点尝试已落库为 {@code RUNNING}、当前节点已写入运行后构造本对象；执行器只读取运行
 * 输入、上游成功输出与引用，不访问其他租户数据。{@code nodeRunId} 用于把模型调用关联回
 * {@code model_invocation.workflow_node_run_id}（ADR-0024 §4.10）。
 *
 * @param tenantId 当前租户
 * @param runId 运行 ID
 * @param nodeRunId 当前节点尝试 ID
 * @param requesterUserId 发起人（检索 ACL 主体）
 * @param traceId 追踪 ID
 * @param inputs 运行输入
 * @param graph 当前发布图（执行器只读）
 * @param node 当前节点
 * @param upstreamOutputs 已成功上游节点输出，按节点 key
 * @param upstreamOutputBytes 已成功节点输出与引用的累计字节数
 * @param citations 已执行检索节点聚合去重后的引用；仅 END 使用
 * @param deadline 节点截止时间
 * @param guard 取消/租约检查
 */
public record WorkflowNodeExecution(
        TenantId tenantId,
        UUID runId,
        UUID nodeRunId,
        UUID requesterUserId,
        String traceId,
        JsonNode inputs,
        WorkflowGraph graph,
        WorkflowGraphNode node,
        Map<String, WorkflowNodeOutput> upstreamOutputs,
        long upstreamOutputBytes,
        List<WorkflowCitation> citations,
        WorkflowDeadline deadline,
        WorkflowNodeGuard guard) {

    /** 校验必填字段并复制集合，避免执行器修改引擎状态。 */
    public WorkflowNodeExecution {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(nodeRunId, "nodeRunId must not be null");
        Objects.requireNonNull(requesterUserId, "requesterUserId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(node, "node must not be null");
        upstreamOutputs = Map.copyOf(Objects.requireNonNull(upstreamOutputs, "upstreamOutputs must not be null"));
        citations = List.copyOf(Objects.requireNonNull(citations, "citations must not be null"));
        Objects.requireNonNull(deadline, "deadline must not be null");
        Objects.requireNonNull(guard, "guard must not be null");
    }
}
