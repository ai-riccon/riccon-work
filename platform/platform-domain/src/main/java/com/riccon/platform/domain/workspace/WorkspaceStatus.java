package com.riccon.platform.domain.workspace;

/**
 * 工作空间状态。
 *
 * <p>归档不删除数据；归档空间不出现在默认列表，但历史会话与产物仍可按 ID 读取（仍受租户与权限约束）。
 */
public enum WorkspaceStatus {
    /** 正常可用。 */
    ACTIVE,
    /** 只读归档，M02 不提供写入入口。 */
    ARCHIVED
}
