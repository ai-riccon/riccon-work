package com.riccon.platform.domain.workflow;

/**
 * 审批人解析方式。
 *
 * <p>节点配置二选一：{@link #USER} + 1–50 个用户 ID，或 {@link #ROLE} + 一个角色码；角色审批不写
 * {@code workflow_approval_assignee}，待办按 {@code user_role} 联接解析（ADR-0024 第 5.2 节）。
 */
public enum WorkflowApprovalAssigneeType {
    /** 指定用户审批。 */
    USER,
    /** 指定角色成员审批。 */
    ROLE
}
