package com.riccon.platform.domain.workflow;

/**
 * 条件节点的出边分支。
 *
 * <p>契约要求 {@code WorkflowEdge.branch} 是字符串枚举 {@code "TRUE"}/{@code "FALSE"}（不是布尔值），
 * 其他边的 branch 为空；条件节点恰好两条出边，另一分支全部 {@code SKIPPED}（ADR-0024 第 4.1 节，AC-09）。
 */
public enum WorkflowEdgeBranch {
    /** 条件为真分支。 */
    TRUE,
    /** 条件为假分支。 */
    FALSE
}
