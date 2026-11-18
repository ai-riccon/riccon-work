import { Tag, Typography } from 'antd';
import type { Department } from '@/services/api/types';
import './current-department-badge.css';

/** 当前主部门徽标参数。 */
export interface CurrentDepartmentBadgeProps {
  /** 当前会话是否租户管理员。 */
  isTenantAdmin: boolean;
  /** 普通成员主部门；未分配或租户管理员为 null。 */
  department: Department | null;
  /** 部门列表是否仍在加载。 */
  loading: boolean;
  /** 部门列表加载错误。 */
  error: string | null;
}

/**
 * 员工端“所属部门”展示。
 *
 * 普通成员显示主部门；未分配显示明确文案；停用部门保留名称并标注“已停用”。
 * 租户管理员显示角色标签，不把 `/departments` 返回的第一个部门误报为主部门。
 *
 * @param props 部门上下文
 * @returns 可换行的部门徽标
 */
export function CurrentDepartmentBadge({
  isTenantAdmin,
  department,
  loading,
  error,
}: CurrentDepartmentBadgeProps) {
  if (isTenantAdmin) {
    return (
      <span className="current-department-badge" data-testid="current-department">
        <Typography.Text type="secondary">角色：</Typography.Text>
        <Tag>租户管理员</Tag>
      </span>
    );
  }

  const label = loading ? '加载中…' : error ? '暂不可用' : (department?.name ?? '未分配部门');
  return (
    <span className="current-department-badge" data-testid="current-department">
      <Typography.Text type="secondary">所属部门：</Typography.Text>
      <Typography.Text>{label}</Typography.Text>
      {department?.status === 'DISABLED' && <Tag>已停用</Tag>}
    </span>
  );
}
