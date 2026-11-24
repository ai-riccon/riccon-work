package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import java.util.Objects;

/**
 * 图边（契约 {@code WorkflowEdge}）。
 *
 * <p>{@code branch} 只允许字符串 {@code "TRUE"}/{@code "FALSE"}，且仅条件节点出边必填；其他边为空。
 * 不接受布尔值，避免把分支语义混入普通连线（ADR-0024 第 4.1/4.5 节）。
 *
 * @param id 边 ID
 * @param source 源节点 ID
 * @param target 目标节点 ID
 * @param branch 条件分支；可空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphEdge(String id, String source, String target, WorkflowEdgeBranch branch) {

    /** 校验必填字段与 ID 格式。 */
    public WorkflowGraphEdge {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (!WorkflowGraphNode.ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id must match " + WorkflowGraphNode.ID_PATTERN.pattern());
        }
        if (!WorkflowGraphNode.ID_PATTERN.matcher(source).matches()) {
            throw new IllegalArgumentException("source must match " + WorkflowGraphNode.ID_PATTERN.pattern());
        }
        if (!WorkflowGraphNode.ID_PATTERN.matcher(target).matches()) {
            throw new IllegalArgumentException("target must match " + WorkflowGraphNode.ID_PATTERN.pattern());
        }
    }
}
