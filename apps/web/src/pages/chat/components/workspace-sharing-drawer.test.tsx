// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { WorkspaceManagementApi } from '@/services/api/client';
import { ApiError } from '@/services/api/errors';
import type {
  Department,
  DepartmentMember,
  ManagedWorkspace,
  Workspace,
  WorkspaceMember,
} from '@/services/api/types';
import { pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import { WorkspaceSharingDrawer } from './workspace-sharing-drawer';

/** 构造可管理部门。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-1',
    managerDisplayName: '李强',
    memberCount: 3,
    createdAt: '2026-11-18T08:00:00.000Z',
    updatedAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/** 构造团队空间。 */
function makeSpace(overrides: Partial<ManagedWorkspace> = {}): ManagedWorkspace {
  return {
    id: 'workspace-1',
    kind: 'TEAM',
    name: '销售部项目空间',
    description: null,
    ownerUserId: 'user-1',
    departmentId: 'dept-sales',
    departmentName: '销售部',
    visibility: 'DEPT',
    status: 'ACTIVE',
    createdAt: '2026-11-18T08:00:00.000Z',
    updatedAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/** 个人空间只用于展示个人空间提示。 */
const PERSONAL: Workspace = {
  id: 'workspace-personal',
  kind: 'PERSONAL',
  name: '我的工作空间',
  description: null,
  ownerUserId: 'user-1',
  status: 'ACTIVE',
  createdAt: '2026-11-18T08:00:00.000Z',
  updatedAt: '2026-11-18T08:00:00.000Z',
};

/** 构造空间管理 API 替身。 */
function makeApi(overrides: Partial<WorkspaceManagementApi> = {}): WorkspaceManagementApi {
  const notUsed = async () => {
    throw new Error('当前用例不应调用该端点');
  };
  return {
    listTeamWorkspaces: vi.fn(async () => pageOf([makeSpace()])),
    listDepartments: vi.fn(async () => pageOf([makeDepartment()])),
    listDepartmentMembers: vi.fn(async () => pageOf<DepartmentMember>([])),
    listDepartmentWorkspaces: vi.fn(async () => pageOf<ManagedWorkspace>([])),
    createDepartmentWorkspace: vi.fn(notUsed),
    createWorkspace: vi.fn(notUsed),
    updateWorkspace: vi.fn(notUsed),
    listWorkspaceMembers: vi.fn(async () => pageOf<WorkspaceMember>([])),
    addWorkspaceMember: vi.fn(notUsed),
    removeWorkspaceMember: vi.fn(async () => undefined),
    ...overrides,
  };
}

/** 渲染共享抽屉。 */
function renderDrawer(
  api: WorkspaceManagementApi,
  activeWorkspace: Workspace = PERSONAL,
  onWorkspaceRenamed?: (workspace: ManagedWorkspace) => void,
) {
  return render(
    withProviders(
      <WorkspaceSharingDrawer
        activeWorkspace={activeWorkspace}
        api={api}
        departmentError={null}
        manageableDepartments={[makeDepartment()]}
        onClose={vi.fn()}
        onWorkspaceRenamed={onWorkspaceRenamed}
      />,
    ),
  );
}

/**
 * 找到包含指定文本的弹窗。
 *
 * antd 在 jsdom 中给 Drawer 与 Modal 复用了同一个标题 id，导致 role dialog 的
 * 可访问名互相覆盖；这里按内容定位，避免把测试失败误判为组件缺陷。
 */
async function findDialog(text: string | RegExp) {
  await waitFor(() => {
    expect(screen.getAllByRole('dialog').length).toBeGreaterThan(1);
  });
  const dialogs = screen.getAllByRole('dialog');
  const match = dialogs.find((dialog) =>
    within(dialog).queryByText(text, { selector: '.ant-modal-title' }),
  );
  if (!match) {
    throw new Error(`未找到包含 ${String(text)} 的弹窗`);
  }
  return match;
}

describe('工作空间共享抽屉', () => {
  it('展示个人空间提示与可访问团队空间', async () => {
    renderDrawer(makeApi());

    expect(screen.getByText('工作空间共享')).toBeVisible();
    expect(await screen.findByText('销售部项目空间')).toBeVisible();
    expect(screen.getByText('部门：销售部')).toBeVisible();
    expect(screen.getByText(/当前是个人空间，仅你本人可见/)).toBeVisible();
    expect(screen.getByRole('button', { name: '改可见性' })).toBeEnabled();
  });

  it('历史 TENANT 空间只读，不提供可见性或成员入口', async () => {
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () =>
        pageOf([
          makeSpace({
            id: 'workspace-tenant',
            name: '历史全租户空间',
            visibility: 'TENANT',
            departmentId: null,
            departmentName: null,
          }),
        ]),
      ),
    });
    renderDrawer(api);

    expect(await screen.findByText('历史全租户空间')).toBeVisible();
    expect(screen.getByText(/M02 历史全租户空间/)).toBeVisible();
    expect(screen.queryByRole('button', { name: '改可见性' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '管理成员' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重命名' })).not.toBeInTheDocument();
    expect(screen.getAllByText('只读').length).toBeGreaterThan(0);
  });

  it('创建部门空间成功后更新列表并提示', async () => {
    const created = makeSpace({ id: 'workspace-new', name: '新建空间' });
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () => pageOf([])),
      createDepartmentWorkspace: vi.fn(async () => created),
    });
    renderDrawer(api);

    await screen.findByText(/还没有可访问的团队空间/);
    fireEvent.click(screen.getByRole('button', { name: '创建部门空间' }));
    const dialog = await findDialog('创建部门空间');
    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '所属部门' }));
    fireEvent.click(await screen.findByTitle('销售部'));
    fireEvent.change(within(dialog).getByPlaceholderText('例如：销售部项目空间'), {
      target: { value: '新建空间' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /创建空间/ }));

    await waitFor(() => {
      expect(api.createDepartmentWorkspace).toHaveBeenCalledWith('dept-sales', {
        name: '新建空间',
        visibility: 'DEPT',
      });
    });
    expect(await screen.findByText('空间“新建空间”已创建。')).toBeVisible();
    expect(screen.getByText('新建空间')).toBeVisible();
  });

  it('创建空间 409 显示可读原因', async () => {
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () => pageOf([])),
      createDepartmentWorkspace: vi.fn(async () => {
        throw new ApiError('conflict', {
          status: 409,
          code: 'RICCON-DEPT-409',
          problem: {
            type: 'about:blank',
            title: '冲突',
            status: 409,
            detail: 'department is not active',
            code: 'RICCON-DEPT-409',
            traceId: 'trace-1',
          },
        });
      }),
    });
    renderDrawer(api);

    await screen.findByText(/还没有可访问的团队空间/);
    fireEvent.click(screen.getByRole('button', { name: '创建部门空间' }));
    const dialog = await findDialog('创建部门空间');
    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '所属部门' }));
    fireEvent.click(await screen.findByTitle('销售部'));
    fireEvent.change(within(dialog).getByPlaceholderText('例如：销售部项目空间'), {
      target: { value: '冲突空间' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /创建空间/ }));

    expect(await within(dialog).findByText(/所属部门已停用/)).toBeInTheDocument();
  });

  it('切换 DEPT 到 CUSTOM 显示影响说明并提交', async () => {
    const updated = makeSpace({ visibility: 'CUSTOM' });
    const api = makeApi({
      updateWorkspace: vi.fn(async () => updated),
    });
    renderDrawer(api);

    await screen.findByText('销售部项目空间');
    fireEvent.click(screen.getByRole('button', { name: '改可见性' }));
    const dialog = await findDialog(/修改可见性/);
    fireEvent.click(within(dialog).getByLabelText('指定成员'));
    expect(within(dialog).getByText(/原有显式成员名单会被清空/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /保存可见性/ }));

    await waitFor(() => {
      expect(api.updateWorkspace).toHaveBeenCalledWith('workspace-1', { visibility: 'CUSTOM' });
    });
    expect(await screen.findByText('空间“销售部项目空间”的可见范围已更新。')).toBeVisible();
  });

  it('CUSTOM 空间可增删显式成员', async () => {
    const custom = makeSpace({ visibility: 'CUSTOM' });
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () => pageOf([custom])),
      listWorkspaceMembers: vi.fn(async () =>
        pageOf([
          {
            userId: 'user-2',
            username: 'wangmin',
            displayName: '王敏',
            grantedAt: '2026-11-18T08:00:00.000Z',
          },
        ]),
      ),
      listDepartmentMembers: vi.fn(async () =>
        pageOf([
          { id: 'user-2', username: 'wangmin', displayName: '王敏', status: 'ACTIVE' as const },
          { id: 'user-3', username: 'zhaomin', displayName: '赵敏', status: 'ACTIVE' as const },
        ]),
      ),
      addWorkspaceMember: vi.fn(async () => ({
        userId: 'user-3',
        username: 'zhaomin',
        displayName: '赵敏',
        grantedAt: '2026-11-18T08:00:00.000Z',
      })),
    });
    renderDrawer(api);

    await screen.findByText('销售部项目空间');
    fireEvent.click(screen.getByRole('button', { name: '管理成员' }));
    const dialog = await findDialog(/成员管理/);
    expect(await within(dialog).findByText('王敏')).toBeInTheDocument();

    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '添加本部门成员' }));
    fireEvent.click(await screen.findByText('赵敏（zhaomin）'));
    fireEvent.click(within(dialog).getByRole('button', { name: /加入空间/ }));
    await waitFor(() => {
      expect(api.addWorkspaceMember).toHaveBeenCalledWith('workspace-1', 'user-3');
    });
    expect(await within(dialog).findByText('赵敏')).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: '移出成员：王敏' }));
    await waitFor(() => {
      expect(api.removeWorkspaceMember).toHaveBeenCalledWith('workspace-1', 'user-2');
    });
    await waitFor(() => {
      expect(within(dialog).queryByText('王敏')).not.toBeInTheDocument();
    });
  });

  it('成员加入冲突 409 显示可读原因', async () => {
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () => pageOf([makeSpace({ visibility: 'CUSTOM' })])),
      listDepartmentMembers: vi.fn(async () =>
        pageOf([
          { id: 'user-3', username: 'zhaomin', displayName: '赵敏', status: 'ACTIVE' as const },
        ]),
      ),
      addWorkspaceMember: vi.fn(async () => {
        throw new ApiError('conflict', {
          status: 409,
          code: 'RICCON-DEPT-409',
          problem: {
            type: 'about:blank',
            title: '冲突',
            status: 409,
            detail: 'workspace member already exists',
            code: 'RICCON-DEPT-409',
            traceId: 'trace-1',
          },
        });
      }),
    });
    renderDrawer(api);

    await screen.findByText('销售部项目空间');
    fireEvent.click(screen.getByRole('button', { name: '管理成员' }));
    const dialog = await findDialog(/成员管理/);
    await within(dialog).findByText(/还没有显式成员/);
    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '添加本部门成员' }));
    fireEvent.click(await screen.findByText('赵敏（zhaomin）'));
    fireEvent.click(within(dialog).getByRole('button', { name: /加入空间/ }));

    expect(await within(dialog).findByText(/已在空间内/)).toBeInTheDocument();
  });

  it('列表加载失败显示错误并可重试', async () => {
    let attempts = 0;
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
        }
        return pageOf([makeSpace()]);
      }),
    });
    renderDrawer(api);

    expect(await screen.findByText('空间列表加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('销售部项目空间')).toBeVisible();
  });
});

describe('负责人部门空间管理列表', () => {
  it('未加入 CUSTOM 空间时仍能从部门管理列表看到并管理', async () => {
    const custom = makeSpace({ visibility: 'CUSTOM', name: '重点客户空间' });
    const api = makeApi({
      listTeamWorkspaces: vi.fn(async () => pageOf([])),
      listDepartmentWorkspaces: vi.fn(async () => pageOf([custom])),
    });
    renderDrawer(api);

    expect(await screen.findByText('重点客户空间')).toBeInTheDocument();
    expect(api.listDepartmentWorkspaces).toHaveBeenCalledWith('dept-sales');
    expect(screen.getByRole('button', { name: '管理成员' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '改可见性' })).toBeInTheDocument();
    expect(screen.getByText('指定成员')).toBeInTheDocument();
  });
});

describe('空间重命名', () => {
  it('可管理空间重命名后更新列表并通知切换器', async () => {
    const renamed = makeSpace({ name: '销售部新空间' });
    const onRenamed = vi.fn();
    const api = makeApi({
      updateWorkspace: vi.fn(async () => renamed),
    });
    renderDrawer(api, PERSONAL, onRenamed);

    await screen.findByText('销售部项目空间');
    fireEvent.click(screen.getByRole('button', { name: '重命名' }));
    const dialog = await findDialog(/重命名空间/);
    fireEvent.change(within(dialog).getByPlaceholderText('例如：销售部项目空间'), {
      target: { value: '销售部新空间' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /保存名称/ }));

    await waitFor(() => {
      expect(api.updateWorkspace).toHaveBeenCalledWith('workspace-1', {
        name: '销售部新空间',
      });
    });
    expect(onRenamed).toHaveBeenCalledWith(renamed);
    expect(await screen.findByText('空间已重命名为“销售部新空间”。')).toBeVisible();
    expect(screen.getAllByText('销售部新空间').length).toBeGreaterThan(0);
  });

  it.each([
    {
      status: 409,
      detail: 'workspace name already exists',
      expected: '空间名称已存在',
    },
    {
      status: 403,
      detail: undefined,
      expected: '你不是该空间的管理者',
    },
    {
      status: 404,
      detail: undefined,
      expected: '空间或部门不存在',
    },
  ])('重命名失败（$status）显示可读文案', async ({ status, detail, expected }) => {
    const api = makeApi({
      updateWorkspace: vi.fn(async () => {
        throw new ApiError('rename failed', {
          status,
          code: status === 409 ? 'RICCON-DEPT-409' : `RICCON-HTTP-${status}`,
          ...(detail
            ? {
                problem: {
                  type: 'about:blank',
                  title: '错误',
                  status,
                  detail,
                  code: 'RICCON-DEPT-409',
                  traceId: 'trace-1',
                },
              }
            : {}),
        });
      }),
    });
    renderDrawer(api);

    await screen.findByText('销售部项目空间');
    fireEvent.click(screen.getByRole('button', { name: '重命名' }));
    const dialog = await findDialog(/重命名空间/);
    fireEvent.change(within(dialog).getByPlaceholderText('例如：销售部项目空间'), {
      target: { value: '冲突空间' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /保存名称/ }));

    expect(await within(dialog).findByText(new RegExp(expected))).toBeInTheDocument();
  });
});
