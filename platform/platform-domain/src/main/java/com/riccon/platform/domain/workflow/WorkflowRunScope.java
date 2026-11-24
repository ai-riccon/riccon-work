package com.riccon.platform.domain.workflow;

/**
 * 发布版本的运行范围。
 *
 * <p>范围是版本快照且不可变，变更需要发布新版本：{@link #ONLY_SELF} 仅所有者可运行；
 * {@link #DEPARTMENT} 该部门当前 ACTIVE 成员可运行；{@link #TENANT} 本租户启用成员可运行
 * （ADR-0024 第 6.2 节，AC-15）。
 */
public enum WorkflowRunScope {
    /** 仅流程所有者。 */
    ONLY_SELF,
    /** 指定部门当前成员。 */
    DEPARTMENT,
    /** 本租户全部启用成员。 */
    TENANT
}
