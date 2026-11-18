package com.riccon.platform.domain.workspace;

/**
 * 工作空间可见范围（M03，ADR-0018 第 2.1 节）。
 *
 * <p>可见性与 {@link WorkspaceKind} 正交：{@code PERSONAL} 空间固定 {@link #PRIVATE}；{@code TEAM}
 * 空间使用 {@link #TENANT}、{@link #DEPT} 或 {@link #CUSTOM}。{@code TENANT} 只用于 M02 历史团队空间的
 * 迁移回填，M03 不允许新建或转换，避免把历史内容暴露给错误部门。
 */
public enum WorkspaceVisibility {
    /** 个人空间：仅所有者可见。 */
    PRIVATE,
    /** 历史团队空间：本租户全部启用员工可见；M03 只读，禁止新建。 */
    TENANT,
    /** 部门空间：所属部门的当前启用成员可见，是 M03 新建团队空间的默认值。 */
    DEPT,
    /** 自定义空间：所属部门当前启用成员中，显式加入 {@code workspace_member} 的人可见。 */
    CUSTOM
}
