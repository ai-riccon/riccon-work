import type { SessionInfo } from '@/services/api/types';
import type { KnowledgeDocumentAcl } from './types';

/** M05 知识库权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const KNOWLEDGE_PERMISSIONS = {
  /** 所有启用员工可读取按 ACL 过滤后的知识库。 */
  read: 'knowledge.read',
  /** 租户管理员登记文档与触发重索引。 */
  manage: 'knowledge.manage',
  /** 租户管理员管理任意文档 ACL、下载与软删除。 */
  aclManage: 'knowledge.acl.manage',
} as const;

/**
 * 判断会话是否具备指定知识库权限。
 *
 * 权限未加载时按无权限处理；前端隐藏入口不代替服务端鉴权。
 *
 * @param permissions 会话权限
 * @param permission 目标权限码
 * @returns 是否具备
 */
export function hasKnowledgePermission(
  permissions: readonly string[] | undefined,
  permission: string,
): boolean {
  return permissions?.includes(permission) ?? false;
}

/**
 * 判断是否可以编辑文档 ACL。
 *
 * 服务端 ACL 读取成功已经证明调用者是创建人或持有 `knowledge.acl.manage`；
 * 角色权限作为额外快速路径，避免在 ACL 尚未加载时误显示入口。
 *
 * @param session 当前会话
 * @param acl 已加载的 ACL；未加载时传 null
 * @returns 是否显示 ACL 编辑入口
 */
export function canEditKnowledgeAcl(
  session: SessionInfo | null,
  acl: KnowledgeDocumentAcl | null,
): boolean {
  if (!session) {
    return false;
  }
  if (acl) {
    return (
      acl.creatorUserId === session.user.id ||
      hasKnowledgePermission(session.permissions, KNOWLEDGE_PERMISSIONS.aclManage)
    );
  }
  return hasKnowledgePermission(session.permissions, KNOWLEDGE_PERMISSIONS.aclManage);
}

/**
 * 判断是否可以触发重索引。
 *
 * 端点 RBAC 门是 `knowledge.manage`；创建人例外不能替代 RBAC 门（ADR-0023 §5.6）。
 *
 * @param session 当前会话
 * @returns 是否显示重试入口
 */
export function canReindexKnowledge(session: SessionInfo | null): boolean {
  return (
    session !== null && hasKnowledgePermission(session.permissions, KNOWLEDGE_PERMISSIONS.manage)
  );
}

/**
 * 判断是否可以删除文档。
 *
 * 删除与 ACL 管理同一资源级规则：创建人或 `knowledge.acl.manage`。
 *
 * @param session 当前会话
 * @param acl 已加载的 ACL
 * @returns 是否显示删除入口
 */
export function canDeleteKnowledge(
  session: SessionInfo | null,
  acl: KnowledgeDocumentAcl | null,
): boolean {
  return canEditKnowledgeAcl(session, acl);
}
