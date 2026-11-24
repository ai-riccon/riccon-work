package com.riccon.api.workflow.definition.domain;

/**
 * 流程列表视图（契约 {@code GET /workflows?view=}）。
 *
 * <p>{@link #MINE} 返回本人草稿与可见的已发布流程（租户管理员返回本租户全部）；{@link #RUNNABLE}
 * 只返回已发布、启用且按版本范围可运行的流程；{@link #ALL} 仅租户管理员可用。未发布草稿不得出现在
 * {@link #RUNNABLE}（ADR-0024 §6.2，AC-01）。
 */
public enum WorkflowDefinitionView {
    /** 我的流程：本人草稿与可见的已发布流程；租户管理员返回全部。 */
    MINE,
    /** 可运行流程：仅已发布、启用且按范围可见。 */
    RUNNABLE,
    /** 全部流程：仅租户管理员。 */
    ALL
}
