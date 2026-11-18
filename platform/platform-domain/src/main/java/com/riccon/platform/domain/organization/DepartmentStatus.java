package com.riccon.platform.domain.organization;

/**
 * 部门状态（M03，ADR-0018 第 1.1/1.3 节）。
 *
 * <p>与 V9 {@code department.status} 的 CHECK 取值一致。停用部门只冻结管理操作和空间可见性，
 * 不清空成员归属；重新启用后原成员关系恢复。
 */
public enum DepartmentStatus {
    /** 启用：参与团队空间创建与部门可见性判定。 */
    ACTIVE,
    /** 停用：成员归属保留，但下一次请求即失去该部门空间的访问。 */
    DISABLED
}
