package com.riccon.platform.domain.workspace;

/**
 * 工作空间类型。
 *
 * <p>个人空间在员工首次登录时自动创建，只有本人可见；团队空间在 M02 对本租户启用员工可见，
 * M03 再收敛到部门/成员范围。
 */
public enum WorkspaceKind {
    /** 每人一个，不能删除，只能归档。 */
    PERSONAL,
    /** 租户内共享，创建者拥有管理权。 */
    TEAM
}
