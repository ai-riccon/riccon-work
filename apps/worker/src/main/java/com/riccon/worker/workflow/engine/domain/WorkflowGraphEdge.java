package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import java.util.Objects;

/**
 * 发布图边（契约 {@code WorkflowEdge} 的 Worker 只读副本）。
 *
 * <p>{@code branch} 只对条件节点出边有值；其他边为空。运行入口校验边引用、入度与分支完整性，
 * 不执行任何表达式或脚本（ADR-0024 §4.1/§4.5）。
 *
 * @param id 边 ID
 * @param source 源节点 key
 * @param target 目标节点 key
 * @param branch 条件分支；普通边为空
 */
public record WorkflowGraphEdge(String id, String source, String target, WorkflowEdgeBranch branch) {

    /** 校验必填字段。 */
    public WorkflowGraphEdge {
        Objects.requireNonNull(id, "edge id must not be null");
        Objects.requireNonNull(source, "edge source must not be null");
        Objects.requireNonNull(target, "edge target must not be null");
    }
}
