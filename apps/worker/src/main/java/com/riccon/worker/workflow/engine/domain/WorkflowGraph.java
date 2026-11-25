package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 发布图快照的运行视图（单入边树状 DAG）。
 *
 * <p>{@link #validateForRun()} 执行运行入口的结构校验：恰好一个 START、至少一个 END、
 * 节点 key/边引用有效、入度 ≤1、非 END 节点单出边（条件节点恰好 TRUE/FALSE 两条）、
 * 无环、所有节点从 START 可达、END 的 {@code resultFrom} 指向上游节点。API 的保存/发布校验
 * 仍是第一道防线；运行入口重复校验是为了在快照损坏或旧版本数据下 fail-closed（AC-24）。
 *
 * <p>图只提供只读导航，不执行节点、不解释表达式。
 */
public final class WorkflowGraph {

    /** 图 schema 版本；M06 固定 1（契约一致）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 节点 key 到节点。 */
    private final Map<String, WorkflowGraphNode> nodesById;

    /** 节点 key 到出边。 */
    private final Map<String, List<WorkflowGraphEdge>> outgoing;

    /** 节点 key 到入边；入度 ≤1，但保留列表便于校验。 */
    private final Map<String, List<WorkflowGraphEdge>> incoming;

    /** 节点列表（保持发布顺序，用于时间线/验证输出）。 */
    private final List<WorkflowGraphNode> nodes;

    /** 边列表。 */
    private final List<WorkflowGraphEdge> edges;

    /**
     * 创建图视图。
     *
     * @param nodes 节点
     * @param edges 边
     * @param maxNodes 部署节点上限
     */
    public WorkflowGraph(List<WorkflowGraphNode> nodes, List<WorkflowGraphEdge> edges, int maxNodes) {
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        this.edges = List.copyOf(Objects.requireNonNull(edges, "edges must not be null"));
        if (nodes.size() > maxNodes) {
            throw new WorkflowGraphFormatException("节点数超过部署上限 " + maxNodes);
        }
        if (edges.size() > WorkflowEngineLimits.MAX_EDGES) {
            throw new WorkflowGraphFormatException("边数超过上限 " + WorkflowEngineLimits.MAX_EDGES);
        }
        Map<String, WorkflowGraphNode> byId = new LinkedHashMap<>();
        for (WorkflowGraphNode node : nodes) {
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new WorkflowGraphFormatException("节点 key 重复");
            }
        }
        Set<String> edgeIds = new HashSet<>();
        Map<String, List<WorkflowGraphEdge>> out = new LinkedHashMap<>();
        Map<String, List<WorkflowGraphEdge>> in = new LinkedHashMap<>();
        for (WorkflowGraphEdge edge : edges) {
            if (!edgeIds.add(edge.id())) {
                throw new WorkflowGraphFormatException("边 ID 重复");
            }
            if (!byId.containsKey(edge.source()) || !byId.containsKey(edge.target())) {
                throw new WorkflowGraphFormatException("边引用了不存在的节点");
            }
            out.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            in.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }
        this.nodesById = Map.copyOf(byId);
        this.outgoing = freeze(out);
        this.incoming = freeze(in);
    }

    /**
     * 返回发布顺序的节点列表。
     *
     * @return 节点列表
     */
    public List<WorkflowGraphNode> nodes() {
        return nodes;
    }

    /**
     * 返回边列表。
     *
     * @return 边列表
     */
    public List<WorkflowGraphEdge> edges() {
        return edges;
    }

    /**
     * 按 key 查找节点。
     *
     * @param nodeKey 节点 key
     * @return 节点；不存在时为空
     */
    public Optional<WorkflowGraphNode> findNode(String nodeKey) {
        return nodeKey == null ? Optional.empty() : Optional.ofNullable(nodesById.get(nodeKey));
    }

    /**
     * 按 key 获取节点。
     *
     * @param nodeKey 节点 key
     * @return 节点
     * @throws WorkflowGraphFormatException 节点不在图中
     */
    public WorkflowGraphNode requireNode(String nodeKey) {
        WorkflowGraphNode node = nodesById.get(nodeKey);
        if (node == null) {
            throw new WorkflowGraphFormatException("当前节点不在发布图中");
        }
        return node;
    }

    /**
     * 返回第一个 START 节点。
     *
     * @return START 节点
     * @throws WorkflowGraphFormatException 缺少 START
     */
    public WorkflowGraphNode startNode() {
        return nodes.stream()
                .filter(node -> node.type() == WorkflowNodeType.START)
                .findFirst()
                .orElseThrow(() -> new WorkflowGraphFormatException("发布图缺少 START 节点"));
    }

    /**
     * 返回节点出边。
     *
     * @param nodeKey 节点 key
     * @return 出边列表
     */
    public List<WorkflowGraphEdge> outgoing(String nodeKey) {
        return outgoing.getOrDefault(nodeKey, List.of());
    }

    /**
     * 返回节点入边。
     *
     * @param nodeKey 节点 key
     * @return 入边列表
     */
    public List<WorkflowGraphEdge> incoming(String nodeKey) {
        return incoming.getOrDefault(nodeKey, List.of());
    }

    /**
     * 返回条件节点指定分支的出边。
     *
     * @param nodeKey 条件节点 key
     * @param branch 分支
     * @return 出边；不存在时为空
     */
    public Optional<WorkflowGraphEdge> branchEdge(String nodeKey, WorkflowEdgeBranch branch) {
        return outgoing(nodeKey).stream()
                .filter(edge -> edge.branch() == branch)
                .findFirst();
    }

    /**
     * 计算从指定节点可达的全部下游节点 key（不含自身）。
     *
     * @param nodeKey 起点
     * @return 下游 key 集合，按广度优先顺序
     */
    public Set<String> downstreamKeys(String nodeKey) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.addLast(nodeKey);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (WorkflowGraphEdge edge : outgoing(current)) {
                if (visited.add(edge.target())) {
                    queue.addLast(edge.target());
                }
            }
        }
        return visited;
    }

    /**
     * 运行入口结构校验。
     *
     * @throws WorkflowGraphFormatException 任一结构规则不满足
     */
    public void validateForRun() {
        long starts = nodes.stream().filter(node -> node.type() == WorkflowNodeType.START).count();
        if (starts != 1) {
            throw new WorkflowGraphFormatException("发布图必须恰好有一个 START 节点");
        }
        boolean hasEnd = nodes.stream().anyMatch(node -> node.type() == WorkflowNodeType.END);
        if (!hasEnd) {
            throw new WorkflowGraphFormatException("发布图至少需要一个 END 节点");
        }
        for (WorkflowGraphNode node : nodes) {
            List<WorkflowGraphEdge> in = incoming(node.id());
            if (in.size() > 1) {
                throw new WorkflowGraphFormatException("节点入度不能大于 1：" + node.name());
            }
            List<WorkflowGraphEdge> out = outgoing(node.id());
            switch (node.type()) {
                case END -> {
                    if (!out.isEmpty()) {
                        throw new WorkflowGraphFormatException("END 节点不能有出边：" + node.name());
                    }
                    validateEndConfig(node);
                }
                case CONDITION -> validateConditionEdges(node, out);
                default -> {
                    if (out.size() != 1) {
                        throw new WorkflowGraphFormatException("非 END 节点必须恰好一条出边：" + node.name());
                    }
                    if (out.get(0).branch() != null) {
                        throw new WorkflowGraphFormatException("普通边的 branch 必须为空：" + node.name());
                    }
                }
            }
        }
        ensureReachableFromStart();
        ensureAcyclic();
    }

    /**
     * 校验 END 节点的结果来源。
     *
     * @param node END 节点
     */
    private void validateEndConfig(WorkflowGraphNode node) {
        String resultFrom = node.config().resultFrom();
        if (resultFrom == null || resultFrom.isBlank()) {
            throw new WorkflowGraphFormatException("END 节点必须配置 resultFrom：" + node.name());
        }
        WorkflowGraphNode source = nodesById.get(resultFrom);
        if (source == null || source.type() == WorkflowNodeType.END) {
            throw new WorkflowGraphFormatException("resultFrom 必须指向图中的上游非 END 节点：" + node.name());
        }
        // resultFrom 必须是 END 的真实祖先：沿唯一入边回溯，避免把旁支节点当作结果来源。
        String cursor = node.id();
        Set<String> visited = new HashSet<>();
        boolean ancestor = false;
        while (visited.add(cursor)) {
            List<WorkflowGraphEdge> in = incoming(cursor);
            if (in.isEmpty()) {
                break;
            }
            cursor = in.get(0).source();
            if (cursor.equals(resultFrom)) {
                ancestor = true;
                break;
            }
        }
        if (!ancestor) {
            throw new WorkflowGraphFormatException("resultFrom 必须是 END 的上游节点：" + node.name());
        }
    }

    /**
     * 校验条件节点恰好一条 TRUE 与一条 FALSE 出边。
     *
     * @param node 条件节点
     * @param out 出边
     */
    private static void validateConditionEdges(WorkflowGraphNode node, List<WorkflowGraphEdge> out) {
        if (out.size() != 2) {
            throw new WorkflowGraphFormatException("条件节点必须恰好两条出边：" + node.name());
        }
        long trueEdges = out.stream().filter(edge -> edge.branch() == WorkflowEdgeBranch.TRUE).count();
        long falseEdges = out.stream().filter(edge -> edge.branch() == WorkflowEdgeBranch.FALSE).count();
        if (trueEdges != 1 || falseEdges != 1) {
            throw new WorkflowGraphFormatException("条件节点必须各有一条 TRUE/FALSE 出边：" + node.name());
        }
        if (node.config().condition() == null) {
            throw new WorkflowGraphFormatException("条件节点缺少 condition 配置：" + node.name());
        }
    }

    /**
     * 校验所有节点都能从 START 到达；没有孤立节点或不可达分支。
     */
    private void ensureReachableFromStart() {
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        String start = startNode().id();
        reached.add(start);
        queue.addLast(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (WorkflowGraphEdge edge : outgoing(current)) {
                if (reached.add(edge.target())) {
                    queue.addLast(edge.target());
                }
            }
        }
        if (reached.size() != nodes.size()) {
            throw new WorkflowGraphFormatException("发布图存在不可达节点");
        }
    }

    /**
     * 显式检测环；单入边结构下环会让路径无法在 END 结束。
     */
    private void ensureAcyclic() {
        Map<String, Integer> color = new HashMap<>();
        for (WorkflowGraphNode node : nodes) {
            visit(node.id(), color);
        }
    }

    /**
     * 深度优先三色标记。
     *
     * @param nodeKey 当前节点
     * @param color 0=未访问 1=访问中 2=已完成
     */
    private void visit(String nodeKey, Map<String, Integer> color) {
        int state = color.getOrDefault(nodeKey, 0);
        if (state == 2) {
            return;
        }
        if (state == 1) {
            throw new WorkflowGraphFormatException("发布图存在环");
        }
        color.put(nodeKey, 1);
        for (WorkflowGraphEdge edge : outgoing(nodeKey)) {
            visit(edge.target(), color);
        }
        color.put(nodeKey, 2);
    }

    /**
     * 冻结邻接表，避免调用方修改内部状态。
     *
     * @param source 可变邻接表
     * @return 不可变邻接表
     */
    private static Map<String, List<WorkflowGraphEdge>> freeze(Map<String, List<WorkflowGraphEdge>> source) {
        Map<String, List<WorkflowGraphEdge>> frozen = new HashMap<>();
        source.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return Map.copyOf(frozen);
    }
}
