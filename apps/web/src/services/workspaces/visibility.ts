import type { Department, ManagedWorkspace, WorkspaceVisibility } from '@/services/api/types';

/**
 * 知识库共享范围的固定文案。
 *
 * M03 知识库仍是租户级共享，文档级 ACL 属于 M05；界面只能使用这句口径，
 * 不得出现“部门隔离”或“文档级权限已生效”的声明（M03-AC-06）。
 */
export const KNOWLEDGE_SHARING_NOTICE = '知识库为全租户共享，文档级权限在后续版本提供。';

/**
 * 来源文件不可访问时的固定说明。
 *
 * 知识库引用可以跨部门展示，但“打开源文件”仍受空间访问判定约束；
 * 源文件属于其他部门空间时服务端统一返回 404，界面只显示文件名与不可访问原因。
 */
export const SOURCE_FILE_UNAVAILABLE = '源文件不可访问：它可能属于其他部门的共享空间，或已被删除。';

/** 可见性中文标签；历史 TENANT 明确标注只读。 */
const VISIBILITY_LABELS: Record<WorkspaceVisibility, string> = {
  PRIVATE: '仅本人可见',
  TENANT: '全租户可见（历史空间，只读）',
  DEPT: '本部门成员',
  CUSTOM: '指定成员',
};

/**
 * 返回可见性的中文标签。
 *
 * @param visibility 可见性枚举
 * @returns 面向用户的标签
 */
export function workspaceVisibilityLabel(visibility: WorkspaceVisibility): string {
  return VISIBILITY_LABELS[visibility];
}

/**
 * 判断空间是否处于 M03 只读状态。
 *
 * 历史 `TENANT` 空间不提供可见性转换或成员管理；个人空间同样不进入共享管理列表。
 *
 * @param workspace 团队空间
 * @returns 是否只读
 */
export function isReadOnlyWorkspace(workspace: ManagedWorkspace): boolean {
  return workspace.visibility === 'TENANT' || workspace.visibility === 'PRIVATE';
}

/**
 * 判断当前用户是否可以管理指定团队空间。
 *
 * 只有租户管理员或该空间所属部门的负责人可以管理；普通成员即使能访问也返回 false，
 * 从而隐藏管理入口。个人空间从不进入该判断。
 *
 * @param workspace 团队空间
 * @param manageableDepartmentIds 当前用户可管理的部门 ID 集合
 * @returns 是否展示管理操作
 */
export function canManageWorkspace(
  workspace: ManagedWorkspace,
  manageableDepartmentIds: ReadonlySet<string>,
): boolean {
  if (isReadOnlyWorkspace(workspace) || !workspace.departmentId) {
    return false;
  }
  return manageableDepartmentIds.has(workspace.departmentId);
}

/**
 * 计算当前用户可管理的启用部门。
 *
 * 租户管理员可管理本租户全部启用部门；部门负责人只管理负责人是自己的部门。
 * 普通成员得到空列表，界面据此隐藏创建与成员管理入口。
 *
 * @param departments 当前用户可见的部门
 * @param userId 当前账号 ID
 * @param isTenantAdmin 是否租户管理员
 * @returns 可管理部门列表
 */
export function manageableDepartments(
  departments: Department[],
  userId: string,
  isTenantAdmin: boolean,
): Department[] {
  return departments.filter(
    (department) =>
      department.status === 'ACTIVE' && (isTenantAdmin || department.managerUserId === userId),
  );
}

/**
 * 判断会话是否具备租户管理员的空间管理能力。
 *
 * 以服务端下发的角色与权限为准，不根据前端隐藏菜单推断授权。
 *
 * @param roles 会话角色
 * @param permissions 会话权限
 * @returns 是否租户管理员
 */
export function isTenantAdminSession(roles: string[], permissions: string[]): boolean {
  return roles.includes('TENANT_ADMIN') || permissions.includes('workspace.write');
}
