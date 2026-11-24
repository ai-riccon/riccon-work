package com.riccon.api.workflow.validation;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphCondition;
import com.riccon.api.workflow.domain.WorkflowGraphConditionRight;
import com.riccon.api.workflow.domain.WorkflowGraphConditionSource;
import com.riccon.api.workflow.domain.WorkflowGraphEdge;
import com.riccon.api.workflow.domain.WorkflowGraphNode;
import com.riccon.api.workflow.domain.WorkflowGraphNodeConfig;
import com.riccon.api.workflow.domain.WorkflowGraphOutputKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowValidationCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 流程图校验器（保存/发布/运行前共用）。
 *
 * <p>覆盖 ADR-0024 §4.1/§4.4/§4.5/§9.3 与 AC-02/06/09/18/24：
 *
 * <ul>
 *   <li>结构：恰好 1 个 START、≥1 个 END、每个节点入边 ≤1、非 END 节点恰好 1 条出边、CONDITION 恰好
 *       2 条 TRUE/FALSE 出边、无环、无孤立节点、节点 ≤50、边 ≤100；
 *   <li>节点类型只允许封闭枚举的 7 类；脚本/代码/HTTP/并行/循环/子流程/定时类型在 JSON 解析阶段已被拒绝，
 *       本类对已构造图不再接受其他类型；
 *   <li>必填参数、{@code targetNodeKey} 等节点键 pattern、条件源/算子/值类型、END 结果来源；
 *   <li>模型必须在 REPORT 路由链且 ACTIVE；MCP 工具必须已授权且未停用；要求审批的工具必须有直接
 *       APPROVAL 前驱并绑定 {@code actionKind=MCP_TOOL/targetNodeKey}；
 *   <li>审批人配置二选一、动作与直接后继一致；发布阶段额外要求存在非发布者的可用审批人。
 * </ul>
 *
 * <p>本类只读数据库目录/授权与审批人数量，不写库、不写审计、不改变草稿版本。返回的问题列表由
 * {@code POST /workflows/{workflowId}/validate}、保存失败与后续发布/运行复用，保证画布与表单看到同一结果。
 */
@Component
public class WorkflowValidator {

    /** 条件左值允许的受控输出字段。 */
    private static final Set<String> NODE_OUTPUT_PATHS =
            Set.of("hitCount", "classificationLabel", "decision", "status");

    /** 仅允许 NUMBER 的比较算子。 */
    private static final Set<WorkflowConditionOperator> NUMERIC_OPERATORS = EnumSet.of(
            WorkflowConditionOperator.GT,
            WorkflowConditionOperator.GTE,
            WorkflowConditionOperator.LT,
            WorkflowConditionOperator.LTE);

    /** 只读目录/授权查询端口。 */
    private final WorkflowValidationSupport support;

    /**
     * 创建校验器。
     *
     * @param support 只读目录/授权查询端口
     */
    public WorkflowValidator(WorkflowValidationSupport support) {
        this.support = Objects.requireNonNull(support, "support must not be null");
    }

    /**
     * 校验流程图。
     *
     * @param graph 待校验图
     * @param request 校验上下文
     * @return 逐项校验结果；无问题时 {@code valid=true}
     */
    public WorkflowValidationResult validate(WorkflowGraph graph, WorkflowValidationRequest request) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(request, "request must not be null");

        List<WorkflowValidationIssue> issues = new ArrayList<>();
        Map<String, WorkflowGraphNode> nodes = indexNodes(graph);
        Map<String, List<WorkflowGraphEdge>> outgoing = new LinkedHashMap<>();
        Map<String, List<WorkflowGraphEdge>> incoming = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet()) {
            outgoing.put(nodeId, new ArrayList<>());
            incoming.put(nodeId, new ArrayList<>());
        }

        checkLimits(graph, issues);
        checkStartAndEnd(nodes, issues);
        collectEdges(graph, nodes, outgoing, incoming, issues);
        checkEdgeBranches(graph, nodes, issues);
        checkDegrees(nodes, outgoing, incoming, issues);
        checkCycle(nodes, incoming, outgoing, issues);
        checkOrphans(nodes, outgoing, issues);

        Set<UUID> modelIds = collectModelIds(nodes);
        Set<UUID> availableModels = modelIds.isEmpty()
                ? Set.of()
                : Objects.requireNonNull(
                        support.findAvailableReportModels(Set.copyOf(modelIds)), "available models must not be null");
        Set<UUID> toolIds = collectToolIds(nodes);
        Map<UUID, WorkflowToolAvailability> toolStates = toolIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNull(
                        support.toolAvailabilities(request.tenantId(), Set.copyOf(toolIds)),
                        "tool availability must not be null");

        for (WorkflowGraphNode node : nodes.values()) {
            validateTargetNodeKeyPattern(node, issues);
            switch (node.type()) {
                case START -> {
                    // START 无必填配置；名称与 ID 已由图领域对象保证。
                }
                case END -> validateEnd(node, nodes, outgoing, issues);
                case RETRIEVAL -> validateRetrieval(node, issues);
                case MODEL -> validateModel(node, availableModels, issues);
                case MCP_TOOL -> validateTool(node, nodes, incoming, toolStates, issues);
                case APPROVAL -> validateApproval(node, nodes, outgoing, request, issues);
                case CONDITION -> validateCondition(node, nodes, outgoing, issues);
            }
        }

        return issues.isEmpty() ? WorkflowValidationResult.passed() : WorkflowValidationResult.invalid(issues);
    }

    /**
     * 建立节点索引；重复 ID 已由 {@link WorkflowGraph} 构造器拒绝。
     *
     * @param graph 图
     * @return 按节点顺序排列的索引
     */
    private static Map<String, WorkflowGraphNode> indexNodes(WorkflowGraph graph) {
        Map<String, WorkflowGraphNode> nodes = new LinkedHashMap<>();
        for (WorkflowGraphNode node : graph.nodes()) {
            nodes.put(node.id(), node);
        }
        return nodes;
    }

    /**
     * 校验节点/边数量上限。
     *
     * <p>{@link WorkflowGraph} 构造器已拒绝超限图；这里保留防御性检查，供程序化构造或未来放宽解析器的路径
     * 复用时仍返回稳定问题码，而不是静默通过。
     *
     * @param graph 图
     * @param issues 问题列表
     */
    private static void checkLimits(WorkflowGraph graph, List<WorkflowValidationIssue> issues) {
        if (graph.nodes().size() > WorkflowGraph.MAX_NODES) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.NODE_LIMIT,
                    null,
                    "nodes",
                    "workflow must not contain more than " + WorkflowGraph.MAX_NODES + " nodes"));
        }
        if (graph.edges().size() > WorkflowGraph.MAX_EDGES) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.EDGE_LIMIT,
                    null,
                    "edges",
                    "workflow must not contain more than " + WorkflowGraph.MAX_EDGES + " edges"));
        }
    }

    /**
     * 校验恰好一个 START 与至少一个 END。
     *
     * @param nodes 节点索引
     * @param issues 问题列表
     */
    private static void checkStartAndEnd(Map<String, WorkflowGraphNode> nodes, List<WorkflowValidationIssue> issues) {
        List<WorkflowGraphNode> starts = nodes.values().stream()
                .filter(node -> node.type() == WorkflowNodeType.START)
                .toList();
        long endCount = nodes.values().stream()
                .filter(node -> node.type() == WorkflowNodeType.END)
                .count();
        if (starts.isEmpty()) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.MISSING_START,
                    null,
                    "nodes",
                    "workflow must contain exactly one START node"));
        } else if (starts.size() > 1) {
            for (int index = 1; index < starts.size(); index++) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.MULTIPLE_START,
                        starts.get(index).id(),
                        "type",
                        "workflow must contain exactly one START node"));
            }
        }
        if (endCount == 0) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.MISSING_END, null, "nodes", "workflow must contain at least one END node"));
        }
    }

    /**
     * 收集已知端点的边并校验 START/END 的连线方向。
     *
     * <p>未知源/目标的边不加入邻接表，但仍返回 {@code EDGE_INVALID}；这样后续环/孤立/入度计算不会被脏边污染。
     *
     * @param graph 图
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param incoming 入边邻接表
     * @param issues 问题列表
     */
    private static void collectEdges(
            WorkflowGraph graph,
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            Map<String, List<WorkflowGraphEdge>> incoming,
            List<WorkflowValidationIssue> issues) {
        for (int index = 0; index < graph.edges().size(); index++) {
            WorkflowGraphEdge edge = graph.edges().get(index);
            WorkflowGraphNode source = nodes.get(edge.source());
            WorkflowGraphNode target = nodes.get(edge.target());
            if (source == null || target == null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.EDGE_INVALID,
                        null,
                        "edges[" + index + "]",
                        "edge source and target must reference existing nodes"));
                continue;
            }
            outgoing.get(source.id()).add(edge);
            incoming.get(target.id()).add(edge);
            if (source.type() == WorkflowNodeType.END) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.EDGE_INVALID,
                        source.id(),
                        "edges[" + index + "]",
                        "END node must not have outgoing edges"));
            }
            if (target.type() == WorkflowNodeType.START) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.EDGE_INVALID,
                        target.id(),
                        "edges[" + index + "]",
                        "START node must not have incoming edges"));
            }
        }
    }

    /**
     * 校验条件分支标记只出现在 CONDITION 出边且必备。
     *
     * @param graph 图
     * @param nodes 节点索引
     * @param issues 问题列表
     */
    private static void checkEdgeBranches(
            WorkflowGraph graph, Map<String, WorkflowGraphNode> nodes, List<WorkflowValidationIssue> issues) {
        for (int index = 0; index < graph.edges().size(); index++) {
            WorkflowGraphEdge edge = graph.edges().get(index);
            WorkflowGraphNode source = nodes.get(edge.source());
            if (source == null) {
                continue;
            }
            if (source.type() == WorkflowNodeType.CONDITION) {
                if (edge.branch() == null) {
                    issues.add(WorkflowValidationIssue.of(
                            WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                            source.id(),
                            "edges[" + index + "].branch",
                            "CONDITION outgoing edge must set branch TRUE or FALSE"));
                }
            } else if (edge.branch() != null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.EDGE_INVALID,
                        source.id(),
                        "edges[" + index + "].branch",
                        "branch is only allowed on CONDITION outgoing edges"));
            }
        }
    }

    /**
     * 校验入度与出度：入边 ≤1；非 END 恰好 1 条出边；CONDITION 恰好 2 条且一条 TRUE、一条 FALSE。
     *
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param incoming 入边邻接表
     * @param issues 问题列表
     */
    private static void checkDegrees(
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            Map<String, List<WorkflowGraphEdge>> incoming,
            List<WorkflowValidationIssue> issues) {
        for (WorkflowGraphNode node : nodes.values()) {
            List<WorkflowGraphEdge> outs = outgoing.get(node.id());
            List<WorkflowGraphEdge> ins = incoming.get(node.id());
            if (ins.size() > 1) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.INDEGREE_EXCEEDED,
                        node.id(),
                        "edges",
                        "node must not have more than one incoming edge"));
            }
            if (node.type() == WorkflowNodeType.CONDITION) {
                checkConditionOutgoing(node, outs, issues);
            } else if (node.type() != WorkflowNodeType.END && outs.size() != 1) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.EDGE_INVALID,
                        node.id(),
                        "edges",
                        "non-END node must have exactly one outgoing edge"));
            }
        }
    }

    /**
     * 校验条件节点出边数量与分支唯一性。
     *
     * @param node 条件节点
     * @param outs 出边
     * @param issues 问题列表
     */
    private static void checkConditionOutgoing(
            WorkflowGraphNode node, List<WorkflowGraphEdge> outs, List<WorkflowValidationIssue> issues) {
        if (outs.size() != 2) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "edges",
                    "CONDITION node must have exactly two outgoing edges"));
            return;
        }
        long trueBranches = outs.stream()
                .filter(edge -> edge.branch() == WorkflowEdgeBranch.TRUE)
                .count();
        long falseBranches = outs.stream()
                .filter(edge -> edge.branch() == WorkflowEdgeBranch.FALSE)
                .count();
        if (trueBranches != 1 || falseBranches != 1) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "edges",
                    "CONDITION node must have exactly one TRUE and one FALSE outgoing edge"));
        }
    }

    /**
     * 使用 Kahn 拓扑排序检测环；存在环时返回首个剩余节点作为定位提示。
     *
     * @param nodes 节点索引
     * @param incoming 入边邻接表
     * @param outgoing 出边邻接表
     * @param issues 问题列表
     */
    private static void checkCycle(
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> incoming,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            List<WorkflowValidationIssue> issues) {
        Map<String, Integer> indegrees = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String nodeId : nodes.keySet()) {
            int indegree = incoming.get(nodeId).size();
            indegrees.put(nodeId, indegree);
            if (indegree == 0) {
                queue.addLast(nodeId);
            }
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            processed++;
            for (WorkflowGraphEdge edge : outgoing.get(nodeId)) {
                String target = edge.target();
                if (!indegrees.containsKey(target)) {
                    continue;
                }
                int remaining = indegrees.get(target) - 1;
                indegrees.put(target, remaining);
                if (remaining == 0) {
                    queue.addLast(target);
                }
            }
        }
        if (processed < nodes.size()) {
            String cycleNode = indegrees.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.GRAPH_CYCLE, cycleNode, "edges", "workflow graph must not contain cycles"));
        }
    }

    /**
     * 校验所有节点可从 START 到达；仅在 START 恰好一个时执行，避免缺失/多 START 时产生噪声。
     *
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param issues 问题列表
     */
    private static void checkOrphans(
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            List<WorkflowValidationIssue> issues) {
        List<WorkflowGraphNode> starts = nodes.values().stream()
                .filter(node -> node.type() == WorkflowNodeType.START)
                .toList();
        if (starts.size() != 1) {
            return;
        }
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        String startId = starts.get(0).id();
        reached.add(startId);
        queue.addLast(startId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (WorkflowGraphEdge edge : outgoing.get(current)) {
                if (reached.add(edge.target())) {
                    queue.addLast(edge.target());
                }
            }
        }
        for (String nodeId : nodes.keySet()) {
            if (!reached.contains(nodeId)) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.ORPHAN_NODE, nodeId, "edges", "node is not reachable from START"));
            }
        }
    }

    /**
     * 收集模型节点引用的模型 ID。
     *
     * @param nodes 节点索引
     * @return 模型 ID
     */
    private static Set<UUID> collectModelIds(Map<String, WorkflowGraphNode> nodes) {
        Set<UUID> modelIds = new LinkedHashSet<>();
        for (WorkflowGraphNode node : nodes.values()) {
            if (node.type() == WorkflowNodeType.MODEL && node.config().modelId() != null) {
                modelIds.add(node.config().modelId());
            }
        }
        return modelIds;
    }

    /**
     * 收集工具节点引用的工具 ID。
     *
     * @param nodes 节点索引
     * @return 工具 ID
     */
    private static Set<UUID> collectToolIds(Map<String, WorkflowGraphNode> nodes) {
        Set<UUID> toolIds = new LinkedHashSet<>();
        for (WorkflowGraphNode node : nodes.values()) {
            if (node.type() == WorkflowNodeType.MCP_TOOL && node.config().toolId() != null) {
                toolIds.add(node.config().toolId());
            }
        }
        return toolIds;
    }

    /**
     * 校验任意节点上的 {@code targetNodeKey} pattern；关闭 T02 评审 F-002。
     *
     * <p>契约 pattern 为 {@code ^[A-Za-z0-9_-]{1,64}$}。即使字段出现在非审批节点上，也不允许非法节点键
     * 进入草稿；审批节点使用 {@code APPROVAL_CONFIG}，其他节点使用 {@code REQUIRED_CONFIG}。
     *
     * @param node 节点
     * @param issues 问题列表
     */
    private static void validateTargetNodeKeyPattern(WorkflowGraphNode node, List<WorkflowValidationIssue> issues) {
        String targetNodeKey = node.config().targetNodeKey();
        if (targetNodeKey == null
                || WorkflowGraphNode.ID_PATTERN.matcher(targetNodeKey).matches()) {
            return;
        }
        WorkflowValidationCode code = node.type() == WorkflowNodeType.APPROVAL
                ? WorkflowValidationCode.APPROVAL_CONFIG
                : WorkflowValidationCode.REQUIRED_CONFIG;
        issues.add(WorkflowValidationIssue.of(
                code,
                node.id(),
                "config.targetNodeKey",
                "targetNodeKey must match " + WorkflowGraphNode.ID_PATTERN.pattern()));
    }

    /**
     * 校验 END 的 {@code resultFrom} 必填且指向已执行的上游节点。
     *
     * @param node END 节点
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param issues 问题列表
     */
    private static void validateEnd(
            WorkflowGraphNode node,
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            List<WorkflowValidationIssue> issues) {
        String resultFrom = node.config().resultFrom();
        if (isBlank(resultFrom)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG,
                    node.id(),
                    "config.resultFrom",
                    "END node requires resultFrom"));
            return;
        }
        WorkflowGraphNode source = nodes.get(resultFrom);
        if (source == null || source.type() == WorkflowNodeType.START || !isAncestor(resultFrom, node.id(), outgoing)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.RESULT_INVALID,
                    node.id(),
                    "config.resultFrom",
                    "resultFrom must reference an executable upstream node"));
        }
    }

    /**
     * 校验检索节点必填参数。
     *
     * @param node 检索节点
     * @param issues 问题列表
     */
    private static void validateRetrieval(WorkflowGraphNode node, List<WorkflowValidationIssue> issues) {
        WorkflowGraphNodeConfig config = node.config();
        if (isBlank(config.queryTemplate())) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG,
                    node.id(),
                    "config.queryTemplate",
                    "RETRIEVAL node requires queryTemplate"));
        }
        if (config.topK() == null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG, node.id(), "config.topK", "RETRIEVAL node requires topK"));
        }
    }

    /**
     * 校验模型节点必填参数与不可用模型。
     *
     * @param node 模型节点
     * @param availableModels 可用 REPORT 模型
     * @param issues 问题列表
     */
    private static void validateModel(
            WorkflowGraphNode node, Set<UUID> availableModels, List<WorkflowValidationIssue> issues) {
        WorkflowGraphNodeConfig config = node.config();
        if (isBlank(config.promptTemplate())) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG,
                    node.id(),
                    "config.promptTemplate",
                    "MODEL node requires promptTemplate"));
        }
        if (config.outputKind() == null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG,
                    node.id(),
                    "config.outputKind",
                    "MODEL node requires outputKind"));
        } else if (config.outputKind() == WorkflowGraphOutputKind.CLASSIFICATION
                && (config.labels() == null || config.labels().isEmpty())) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG,
                    node.id(),
                    "config.labels",
                    "CLASSIFICATION model node requires at least one label"));
        }
        if (config.modelId() != null && !availableModels.contains(config.modelId())) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.MODEL_UNAVAILABLE,
                    node.id(),
                    "config.modelId",
                    "modelId must be an ACTIVE model in the REPORT routing chain"));
        }
    }

    /**
     * 校验工具授权、停用状态与审批前驱。
     *
     * @param node 工具节点
     * @param nodes 节点索引
     * @param incoming 入边邻接表
     * @param toolStates 工具状态
     * @param issues 问题列表
     */
    private static void validateTool(
            WorkflowGraphNode node,
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> incoming,
            Map<UUID, WorkflowToolAvailability> toolStates,
            List<WorkflowValidationIssue> issues) {
        UUID toolId = node.config().toolId();
        if (toolId == null) {
            // 内置模板把工具留空，复制后由用户在授权范围内选择；空值不发起工具调用。
            return;
        }
        WorkflowToolAvailability state = toolStates.getOrDefault(toolId, WorkflowToolAvailability.notAuthorized());
        if (state.disabled()) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.TOOL_DISABLED, node.id(), "config.toolId", "MCP tool is disabled"));
            return;
        }
        if (!state.available()) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.TOOL_NOT_AUTHORIZED,
                    node.id(),
                    "config.toolId",
                    "MCP tool is not authorized for this tenant"));
            return;
        }
        if (!state.requiresApprovalBeforeInvocation()) {
            return;
        }
        List<WorkflowGraphEdge> ins = incoming.get(node.id());
        if (ins.size() != 1) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.toolId",
                    "tool requires approval and must have exactly one APPROVAL predecessor"));
            return;
        }
        WorkflowGraphNode predecessor = nodes.get(ins.get(0).source());
        if (predecessor == null || predecessor.type() != WorkflowNodeType.APPROVAL) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.toolId",
                    "tool requires an APPROVAL node immediately before it"));
            return;
        }
        WorkflowGraphNodeConfig predecessorConfig = predecessor.config();
        if (predecessorConfig.actionKind() != WorkflowApprovalActionKind.MCP_TOOL
                || !node.id().equals(predecessorConfig.targetNodeKey())) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.toolId",
                    "APPROVAL predecessor must use actionKind MCP_TOOL and target this tool node"));
        }
    }

    /**
     * 校验审批人配置、动作与直接后继绑定，并在发布阶段检查可用审批人。
     *
     * @param node 审批节点
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param request 校验上下文
     * @param issues 问题列表
     */
    private void validateApproval(
            WorkflowGraphNode node,
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            WorkflowValidationRequest request,
            List<WorkflowValidationIssue> issues) {
        WorkflowGraphNodeConfig config = node.config();
        WorkflowApprovalAssigneeType assigneeType = config.assigneeType();
        if (assigneeType == null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.assigneeType",
                    "APPROVAL node requires assigneeType"));
        }
        if (config.actionKind() == null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.actionKind",
                    "APPROVAL node requires actionKind"));
        }

        boolean assigneeConfigured = false;
        if (assigneeType == WorkflowApprovalAssigneeType.USER) {
            if (config.assigneeRole() != null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.APPROVAL_CONFIG,
                        node.id(),
                        "config.assigneeRole",
                        "USER approval must not set assigneeRole"));
            }
            if (config.assigneeUserIds() == null || config.assigneeUserIds().isEmpty()) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.APPROVAL_CONFIG,
                        node.id(),
                        "config.assigneeUserIds",
                        "USER approval requires at least one assigneeUserId"));
            } else {
                assigneeConfigured = config.assigneeRole() == null;
            }
        } else if (assigneeType == WorkflowApprovalAssigneeType.ROLE) {
            if (config.assigneeRole() == null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.APPROVAL_CONFIG,
                        node.id(),
                        "config.assigneeRole",
                        "ROLE approval requires assigneeRole"));
            }
            if (config.assigneeUserIds() != null && !config.assigneeUserIds().isEmpty()) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.APPROVAL_CONFIG,
                        node.id(),
                        "config.assigneeUserIds",
                        "ROLE approval must not set assigneeUserIds"));
            } else {
                assigneeConfigured = config.assigneeRole() != null;
            }
        }

        String targetNodeKey = config.targetNodeKey();
        boolean targetNodeKeyPatternValid = targetNodeKey == null
                || WorkflowGraphNode.ID_PATTERN.matcher(targetNodeKey).matches();
        String successor = singleSuccessor(outgoing.get(node.id()));
        WorkflowGraphNode successorNode = successor == null ? null : nodes.get(successor);
        WorkflowNodeType successorType = successorNode == null ? null : successorNode.type();

        if (config.actionKind() == WorkflowApprovalActionKind.MCP_TOOL) {
            if (targetNodeKey == null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.APPROVAL_CONFIG,
                        node.id(),
                        "config.targetNodeKey",
                        "MCP_TOOL approval requires targetNodeKey"));
            } else if (targetNodeKeyPatternValid) {
                checkSuccessorTarget(node, targetNodeKey, successor, successorType, WorkflowNodeType.MCP_TOOL, issues);
            }
        } else if (config.actionKind() == WorkflowApprovalActionKind.MODEL) {
            if (targetNodeKey != null && targetNodeKeyPatternValid) {
                checkSuccessorTarget(node, targetNodeKey, successor, successorType, WorkflowNodeType.MODEL, issues);
            } else if (targetNodeKey == null && successorType != null && successorType != WorkflowNodeType.MODEL) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.APPROVAL_CONFIG,
                        node.id(),
                        "config.actionKind",
                        "MODEL approval requires a MODEL successor"));
            }
        } else if (config.actionKind() == WorkflowApprovalActionKind.CONTINUE
                && targetNodeKey != null
                && targetNodeKeyPatternValid
                && !targetNodeKey.equals(successor)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.targetNodeKey",
                    "targetNodeKey must reference the direct successor node"));
        }

        if (request.scope().checksApproverAvailability() && assigneeConfigured) {
            WorkflowApproverQuery query = new WorkflowApproverQuery(
                    assigneeType, config.assigneeRole(), config.assigneeUserIds(), request.actingUserId());
            int approvers = support.countActiveApprovers(request.tenantId(), query);
            if (approvers == 0) {
                String field = assigneeType == WorkflowApprovalAssigneeType.USER
                        ? "config.assigneeUserIds"
                        : "config.assigneeRole";
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.NO_APPROVER,
                        node.id(),
                        field,
                        "approval node requires at least one active approver other than the publisher"));
            }
        }
    }

    /**
     * 校验审批动作目标必须是直接后继且类型匹配。
     *
     * @param node 审批节点
     * @param targetNodeKey 目标节点键
     * @param successor 直接后继节点 ID
     * @param successorType 直接后继类型
     * @param expectedType 期望后继类型
     * @param issues 问题列表
     */
    private static void checkSuccessorTarget(
            WorkflowGraphNode node,
            String targetNodeKey,
            String successor,
            WorkflowNodeType successorType,
            WorkflowNodeType expectedType,
            List<WorkflowValidationIssue> issues) {
        if (!targetNodeKey.equals(successor)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.targetNodeKey",
                    "targetNodeKey must reference the direct successor node"));
            return;
        }
        if (successorType != expectedType) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.APPROVAL_CONFIG,
                    node.id(),
                    "config.targetNodeKey",
                    "targetNodeKey must reference a " + expectedType.name() + " node"));
        }
    }

    /**
     * 返回节点唯一后继；出边数量不为 1 时返回 {@code null}，由出度问题单独报告。
     *
     * @param outgoing 节点的出边
     * @return 后继节点 ID 或 {@code null}
     */
    private static String singleSuccessor(List<WorkflowGraphEdge> outgoing) {
        return outgoing != null && outgoing.size() == 1 ? outgoing.get(0).target() : null;
    }

    /**
     * 校验条件节点的数据来源、算子、值类型与受控字段。
     *
     * @param node 条件节点
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param issues 问题列表
     */
    private static void validateCondition(
            WorkflowGraphNode node,
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            List<WorkflowValidationIssue> issues) {
        WorkflowGraphCondition condition = node.config().condition();
        if (condition == null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.REQUIRED_CONFIG,
                    node.id(),
                    "config.condition",
                    "CONDITION node requires condition"));
            return;
        }
        WorkflowGraphConditionSource left = condition.left();
        WorkflowGraphConditionRight right = condition.right();
        WorkflowConditionOperator operator = condition.operator();

        if (left.valueType() != right.valueType()) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition",
                    "condition left and right valueType must match"));
        }
        if (NUMERIC_OPERATORS.contains(operator) && left.valueType() != WorkflowConditionValueType.NUMBER) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.operator",
                    "numeric condition operator requires NUMBER valueType"));
        }
        if (operator == WorkflowConditionOperator.IN) {
            if (right.values() == null || right.values().isEmpty()) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                        node.id(),
                        "config.condition.right.values",
                        "IN operator requires a non-empty values array"));
            }
            if (right.value() != null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                        node.id(),
                        "config.condition.right.value",
                        "IN operator must not set value"));
            }
            if (right.values() != null
                    && right.values().stream().anyMatch(value -> !matchesValueType(right.valueType(), value))) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                        node.id(),
                        "config.condition.right.values",
                        "values must match the declared valueType"));
            }
        } else {
            if (right.value() == null) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                        node.id(),
                        "config.condition.right.value",
                        "condition operator requires a value"));
            } else if (!matchesValueType(right.valueType(), right.value())) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                        node.id(),
                        "config.condition.right.value",
                        "value must match the declared valueType"));
            }
            if (right.values() != null && !right.values().isEmpty()) {
                issues.add(WorkflowValidationIssue.of(
                        WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                        node.id(),
                        "config.condition.right.values",
                        "values are only allowed for the IN operator"));
            }
        }

        if (left.source() == WorkflowConditionSource.NODE_OUTPUT) {
            validateNodeOutputSource(node, left, nodes, outgoing, issues);
        } else if (left.nodeId() != null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.left.nodeId",
                    "nodeId is only allowed for NODE_OUTPUT source"));
        }
    }

    /**
     * 校验条件左值引用上游节点及受控输出字段。
     *
     * @param node 条件节点
     * @param left 条件左值
     * @param nodes 节点索引
     * @param outgoing 出边邻接表
     * @param issues 问题列表
     */
    private static void validateNodeOutputSource(
            WorkflowGraphNode node,
            WorkflowGraphConditionSource left,
            Map<String, WorkflowGraphNode> nodes,
            Map<String, List<WorkflowGraphEdge>> outgoing,
            List<WorkflowValidationIssue> issues) {
        String sourceNodeId = left.nodeId();
        if (isBlank(sourceNodeId)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.left.nodeId",
                    "NODE_OUTPUT condition requires nodeId"));
            return;
        }
        if (!WorkflowGraphNode.ID_PATTERN.matcher(sourceNodeId).matches()) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.left.nodeId",
                    "condition nodeId must match " + WorkflowGraphNode.ID_PATTERN.pattern()));
            return;
        }
        WorkflowGraphNode sourceNode = nodes.get(sourceNodeId);
        if (sourceNode == null) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.left.nodeId",
                    "condition nodeId must reference an existing node"));
            return;
        }
        if (!isAncestor(sourceNodeId, node.id(), outgoing)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.left.nodeId",
                    "condition nodeId must reference an upstream node"));
            return;
        }
        if (!NODE_OUTPUT_PATHS.contains(left.path()) || !pathAllowedFor(left.path(), sourceNode)) {
            issues.add(WorkflowValidationIssue.of(
                    WorkflowValidationCode.CONDITION_BRANCH_INVALID,
                    node.id(),
                    "config.condition.left.path",
                    "condition path is not allowed for the referenced node type"));
        }
    }

    /**
     * 判断受控输出字段是否与来源节点类型匹配。
     *
     * @param path 字段路径
     * @param sourceNode 来源节点
     * @return 匹配时返回 {@code true}
     */
    private static boolean pathAllowedFor(String path, WorkflowGraphNode sourceNode) {
        return switch (path) {
            case "hitCount" -> sourceNode.type() == WorkflowNodeType.RETRIEVAL;
            case "classificationLabel" ->
                sourceNode.type() == WorkflowNodeType.MODEL
                        && sourceNode.config().outputKind() == WorkflowGraphOutputKind.CLASSIFICATION;
            case "decision" -> sourceNode.type() == WorkflowNodeType.APPROVAL;
            case "status" -> sourceNode.type() == WorkflowNodeType.MCP_TOOL;
            default -> false;
        };
    }

    /**
     * 判断标量是否匹配声明类型。
     *
     * @param valueType 声明类型
     * @param value 标量值
     * @return 匹配时返回 {@code true}
     */
    private static boolean matchesValueType(WorkflowConditionValueType valueType, Object value) {
        if (value == null) {
            return false;
        }
        return switch (valueType) {
            case NUMBER -> value instanceof Number;
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    /**
     * 判断 {@code ancestorId} 是否为 {@code nodeId} 的严格上游节点。
     *
     * @param ancestorId 候选上游节点
     * @param nodeId 当前节点
     * @param outgoing 出边邻接表
     * @return 严格上游时返回 {@code true}
     */
    private static boolean isAncestor(String ancestorId, String nodeId, Map<String, List<WorkflowGraphEdge>> outgoing) {
        if (ancestorId.equals(nodeId)) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(ancestorId);
        queue.addLast(ancestorId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (WorkflowGraphEdge edge : outgoing.getOrDefault(current, List.of())) {
                if (edge.target().equals(nodeId)) {
                    return true;
                }
                if (visited.add(edge.target())) {
                    queue.addLast(edge.target());
                }
            }
        }
        return false;
    }

    /**
     * 判断文本为空或仅空白。
     *
     * @param value 文本
     * @return 为空时返回 {@code true}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
