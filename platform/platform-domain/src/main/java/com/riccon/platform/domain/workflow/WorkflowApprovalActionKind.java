package com.riccon.platform.domain.workflow;

/**
 * 审批解锁的动作类型。
 *
 * <p>与契约 {@code WorkflowApprovalActionKind} 一致：{@link #MCP_TOOL} 携带可重放参数密文，
 * {@link #MODEL} 解锁模型节点，{@link #CONTINUE} 仅表示继续执行（ADR-0024 第 3.3.5/4.4 节）。
 */
public enum WorkflowApprovalActionKind {
    /** 解锁 MCP 工具节点；必须绑定参数密文与 plan 哈希。 */
    MCP_TOOL,
    /** 解锁模型节点。 */
    MODEL,
    /** 无工具/模型动作，仅确认继续。 */
    CONTINUE
}
