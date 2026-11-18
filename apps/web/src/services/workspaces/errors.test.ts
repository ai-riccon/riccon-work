import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { describePlatformDepartmentError, describeWorkspaceError } from './errors';

/**
 * 构造带稳定 detail 的 ApiError。
 *
 * @param status HTTP 状态
 * @param code 错误码
 * @param detail 服务端稳定原因
 * @returns ApiError
 */
function apiError(status: number, code: string, detail: string): ApiError {
  return new ApiError(detail, {
    status,
    code,
    problem: {
      type: 'about:blank',
      title: '请求失败',
      status,
      detail,
      code,
      traceId: 'trace-1',
    },
  });
}

describe('部门与空间管理错误文案', () => {
  it('把平台部门 409 的稳定原因翻译为可读中文', () => {
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-DEPT-409', 'department name already exists'),
      ),
    ).toContain('部门名称已存在');
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-DEPT-409', 'member already belongs to another department'),
      ),
    ).toContain('已属于其他部门');
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-DEPT-409', 'member already belongs to this department'),
      ),
    ).toContain('已在本部门');
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-DEPT-409', 'department manager must be transferred before removal'),
      ),
    ).toContain('先转移负责人');
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-DEPT-409', 'target user must be an active member of the department'),
      ),
    ).toContain('必须是本部门');
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-CONFLICT-409', 'target tenant is not active'),
      ),
    ).toContain('已暂停');
    expect(
      describePlatformDepartmentError(apiError(409, 'RICCON-DEPT-409', 'department is disabled')),
    ).toContain('已停用');
    expect(
      describePlatformDepartmentError(
        apiError(409, 'RICCON-DEPT-409', 'member account is not active'),
      ),
    ).toContain('不能加入');
  });

  it('平台部门错误按 403/404/400/未知回落到稳定文案', () => {
    expect(
      describePlatformDepartmentError(apiError(403, 'RICCON-AUTH-403', 'forbidden')),
    ).toContain('只有平台管理员');
    expect(
      describePlatformDepartmentError(apiError(404, 'RICCON-TENANT-404', 'not found')),
    ).toContain('不存在');
    expect(
      describePlatformDepartmentError(apiError(400, 'RICCON-REQUEST-400', 'bad request')),
    ).toContain('参数');
    expect(
      describePlatformDepartmentError(apiError(409, 'RICCON-DEPT-409', 'unknown reason')),
    ).toContain('状态冲突');
    expect(
      describePlatformDepartmentError(apiError(500, 'RICCON-INTERNAL-500', 'internal')),
    ).toContain('暂时不可用');
    expect(describePlatformDepartmentError(new Error('boom'), '兜底')).toBe('兜底');
  });

  it('空间 409 原因与 403/404 文案可读', () => {
    expect(
      describeWorkspaceError(apiError(409, 'RICCON-DEPT-409', 'workspace member already exists')),
    ).toContain('已在空间内');
    expect(
      describeWorkspaceError(
        apiError(400, 'RICCON-REQUEST-400', 'visibility of this workspace cannot be changed'),
      ),
    ).toContain('不可转换');
    expect(
      describeWorkspaceError(
        apiError(
          400,
          'RICCON-REQUEST-400',
          'explicit members are only supported for CUSTOM workspaces',
        ),
      ),
    ).toContain('指定成员');
    expect(
      describeWorkspaceError(
        apiError(400, 'RICCON-REQUEST-400', 'personal workspace does not support explicit members'),
      ),
    ).toContain('个人空间');
    expect(
      describeWorkspaceError(
        apiError(400, 'RICCON-REQUEST-400', 'only DEPT or CUSTOM visibility is allowed'),
      ),
    ).toContain('可见性只能');
    expect(
      describeWorkspaceError(apiError(400, 'RICCON-REQUEST-400', 'department is not active')),
    ).toContain('已停用');
    expect(
      describeWorkspaceError(
        apiError(
          400,
          'RICCON-REQUEST-400',
          'at least one of name, description or visibility is required',
        ),
      ),
    ).toContain('至少修改一个字段');
    expect(describeWorkspaceError(apiError(403, 'RICCON-AUTH-403', 'forbidden'))).toContain(
      '不是该空间的管理者',
    );
    expect(describeWorkspaceError(apiError(404, 'RICCON-TENANT-404', 'not found'))).toContain(
      '没有管理权限',
    );
    expect(describeWorkspaceError(apiError(409, 'RICCON-DEPT-409', 'unknown'))).toContain(
      '状态冲突',
    );
    expect(describeWorkspaceError(apiError(400, 'RICCON-REQUEST-400', 'bad request'))).toContain(
      '空间管理规则',
    );
    expect(describeWorkspaceError(apiError(500, 'X', 'boom'))).toContain('暂时不可用');
    expect(describeWorkspaceError('not an error', '兜底')).toBe('兜底');
  });

  it('网络错误走统一网络文案', () => {
    expect(describePlatformDepartmentError(ApiError.network())).toContain('无法连接服务器');
    expect(describeWorkspaceError(ApiError.network())).toContain('无法连接服务器');
  });
});
