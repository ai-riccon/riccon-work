package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 流程草稿图（契约 {@code WorkflowGraph}）。
 *
 * <p>这是保存与发布使用的完整图快照。{@code schemaVersion} 固定为 {@value #SCHEMA_VERSION}；节点最多
 * {@value #MAX_NODES} 个、边最多 {@value #MAX_EDGES} 条；节点 ID 在流程内唯一（ADR-0024 第 4.1 节）。
 *
 * <p>本对象只保证“形状可表示”；恰好一个 START、至少一个 END、入度、无环、条件分支与必填配置等结构
 * 规则由校验器在 validate/publish 阶段逐项返回，保存阶段允许未完成草稿。
 *
 * @param schemaVersion 图 schema 版本，固定 1
 * @param nodes 节点列表
 * @param edges 边列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraph(int schemaVersion, List<WorkflowGraphNode> nodes, List<WorkflowGraphEdge> edges) {

    /** 当前图 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 单流程最大节点数，与契约和 ADR-0024 第 4.1 节一致。 */
    public static final int MAX_NODES = 50;

    /** 单流程最大边数，与契约和 ADR-0024 第 4.1 节一致。 */
    public static final int MAX_EDGES = 100;

    /** 校验版本、上限与节点/边 ID 唯一性。 */
    public WorkflowGraph {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges must not be null"));
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("nodes must not exceed " + MAX_NODES);
        }
        if (edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("edges must not exceed " + MAX_EDGES);
        }
        requireUnique(nodes.stream().map(WorkflowGraphNode::id).toList(), "node id");
        requireUnique(edges.stream().map(WorkflowGraphEdge::id).toList(), "edge id");
    }

    /**
     * 返回节点数量，供审计与发布快照使用。
     *
     * @return 节点数
     */
    public int nodeCount() {
        return nodes.size();
    }

    /**
     * 校验 ID 列表无重复。
     *
     * @param ids ID 列表
     * @param field 字段说明
     */
    private static void requireUnique(List<String> ids, String field) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException(field + " must be unique");
            }
        }
    }
}
