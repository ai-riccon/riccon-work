import { useEffect, useMemo, useState } from 'react';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { Department, SessionInfo } from '@/services/api/types';
import {
  isTenantAdminSession,
  manageableDepartments as selectManageableDepartments,
} from '@/services/workspaces/visibility';

/** 空间共享管理上下文。 */
export interface WorkspaceManagementContext {
  /** 当前会话是否租户管理员。 */
  isTenantAdmin: boolean;
  /** 当前用户可见的部门。 */
  departments: Department[];
  /** 当前用户可管理的启用部门。 */
  manageableDepartments: Department[];
  /** 是否展示空间共享入口；普通成员为 false。 */
  canManage: boolean;
  /**
   * 普通成员的主部门；未分配时为 null。
   *
   * 租户管理员返回 null，界面显示“租户管理员”，避免把全部部门中的第一个误报为主部门。
   */
  currentDepartment: Department | null;
  /** 部门列表是否仍在加载。 */
  loading: boolean;
  /** 部门列表加载错误；用于管理抽屉内的可读提示。 */
  error: string | null;
}

/**
 * 加载空间共享管理上下文。
 *
 * 只在页面拿到真实管理 API 时发请求：测试与只读页面不注入该能力时，
 * 不会产生额外网络调用，也不会向普通成员暴露管理入口。
 *
 * @param api 空间管理 API；null 表示当前环境不支持管理能力
 * @param session 当前会话；null 时清空上下文
 * @returns 管理上下文
 */
export function useWorkspaceManagement(
  api: WorkspaceManagementApi | null,
  session: SessionInfo | null,
): WorkspaceManagementContext {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isTenantAdmin = Boolean(
    session && isTenantAdminSession(session.roles, session.permissions),
  );

  useEffect(() => {
    if (!api || !session) {
      setDepartments([]);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    api
      .listDepartments()
      .then((page) => {
        if (!cancelled) {
          setDepartments(page.items);
        }
      })
      .catch(() => {
        if (!cancelled) {
          // 部门读取失败不阻塞 J1 主流程；管理入口对负责人保持隐藏，
          // 租户管理员仍可通过已下发的角色进入并看到抽屉内错误。
          setDepartments([]);
          setError('部门列表加载失败，请稍后重试。');
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [api, session]);

  const manageable = useMemo(
    () => (session ? selectManageableDepartments(departments, session.user.id, isTenantAdmin) : []),
    [departments, isTenantAdmin, session],
  );

  /**
   * 计算普通成员的主部门。
   *
   * `/departments` 对普通成员只返回本人部门（0 或 1 条）；若会话已带 departmentId，
   * 优先按 ID 匹配，避免未来接口返回多条时误取第一条。租户管理员返回全部部门，
   * 因此这里不推断“主部门”。
   */
  const currentDepartment = useMemo(() => {
    if (!session || isTenantAdmin) {
      return null;
    }
    const expectedId = session.user.departmentId;
    if (expectedId) {
      return departments.find((department) => department.id === expectedId) ?? null;
    }
    return departments[0] ?? null;
  }, [departments, isTenantAdmin, session]);

  return {
    isTenantAdmin,
    departments,
    manageableDepartments: manageable,
    canManage: Boolean(session) && (isTenantAdmin || manageable.length > 0),
    currentDepartment,
    loading,
    error,
  };
}
