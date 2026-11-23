// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { buildAclUpdate, toAclFormValues } from './acl-editor';

describe('ACL 表单转换', () => {
  it('USERS 初始值排除创建人，TENANT/DEPT 清空名单', () => {
    const users = [
      { userId: 'creator', displayName: '创建人', creator: true },
      { userId: 'user-2', displayName: '王敏' },
    ];
    expect(
      toAclFormValues({ visibility: 'USERS', users, creatorUserId: 'creator' }, 'creator'),
    ).toEqual({ visibility: 'USERS', departmentId: null, userIds: ['user-2'] });
    expect(
      toAclFormValues(
        { visibility: 'DEPT', departmentId: 'dept-sales', users: [], creatorUserId: 'creator' },
        'creator',
      ),
    ).toEqual({ visibility: 'DEPT', departmentId: 'dept-sales', userIds: [] });
    expect(
      toAclFormValues({ visibility: 'TENANT', users: [], creatorUserId: 'creator' }, 'creator'),
    ).toEqual({ visibility: 'TENANT', departmentId: null, userIds: [] });
  });

  it('TENANT 只发 visibility，DEPT 发单个部门，USERS 去重且排除创建人', () => {
    expect(buildAclUpdate({ visibility: 'TENANT' }, 'creator')).toEqual({
      visibility: 'TENANT',
    });
    expect(buildAclUpdate({ visibility: 'DEPT', departmentId: 'dept-sales' }, 'creator')).toEqual({
      visibility: 'DEPT',
      departmentId: 'dept-sales',
    });
    expect(
      buildAclUpdate(
        { visibility: 'USERS', userIds: ['creator', 'user-2', 'user-2', 'user-3'] },
        'creator',
      ),
    ).toEqual({ visibility: 'USERS', userIds: ['user-2', 'user-3'] });
  });

  it('USERS 超过 200 时不静默截断，由服务端 400 fail-closed', () => {
    const userIds = Array.from({ length: 201 }, (_value, index) => `user-${index}`);
    const update = buildAclUpdate({ visibility: 'USERS', userIds }, 'creator');
    expect(update.userIds).toHaveLength(201);
  });
});
