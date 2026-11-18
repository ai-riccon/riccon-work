// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { WorkspaceManagementApi } from '@/services/api/client';
import { ApiError } from '@/services/api/errors';
import type { Citation, Department, SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import {
  DEMO_SESSION,
  createFakeApi,
  makeConversation,
  makeDetail,
  makeMessage,
  pageOf,
} from '@/test/fake-api';
import { withProviders } from '@/test/render';
import {
  KNOWLEDGE_SHARING_NOTICE,
  SOURCE_FILE_UNAVAILABLE,
} from '@/services/workspaces/visibility';
import ChatPage from './index';

/** 构造部门。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-1',
    managerDisplayName: '演示员工',
    memberCount: 2,
    createdAt: '2026-11-18T08:00:00.000Z',
    updatedAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/** 构造空间管理 API；只有部门列表与团队空间列表会被 J1 入口读取。 */
function makeManagementApi(departments: Department[]): WorkspaceManagementApi {
  const notUsed = async () => {
    throw new Error('当前用例不应调用该端点');
  };
  return {
    listTeamWorkspaces: vi.fn(async () => pageOf([])),
    listDepartments: vi.fn(async () => pageOf(departments)),
    listDepartmentMembers: vi.fn(notUsed),
    listDepartmentWorkspaces: vi.fn(notUsed),
    createDepartmentWorkspace: vi.fn(notUsed),
    createWorkspace: vi.fn(notUsed),
    updateWorkspace: vi.fn(notUsed),
    listWorkspaceMembers: vi.fn(notUsed),
    addWorkspaceMember: vi.fn(notUsed),
    removeWorkspaceMember: vi.fn(notUsed),
  };
}

/** 租户管理员会话。 */
const TENANT_ADMIN: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'workspace.write'],
};

/** 渲染已登录工作空间页。 */
function renderChat(session: SessionInfo, managementApi?: WorkspaceManagementApi) {
  authStore.reset();
  authStore.setAuthenticated(session);
  const api = createFakeApi();
  return render(
    withProviders(
      <ChatPage api={api} managementApi={managementApi} navigate={vi.fn()} pollIntervalMs={10} />,
    ),
  );
}

describe('工作空间共享入口与知识库明示', () => {
  it('普通成员不显示空间共享入口', async () => {
    renderChat(DEMO_SESSION);

    expect(await screen.findByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    expect(screen.queryByRole('button', { name: '工作空间共享' })).not.toBeInTheDocument();
  });

  it('租户管理员显示入口并可打开共享抽屉', async () => {
    const managementApi = makeManagementApi([makeDepartment()]);
    renderChat(TENANT_ADMIN, managementApi);

    const entry = await screen.findByRole('button', { name: '工作空间共享' });
    fireEvent.click(entry);

    // antd 在 jsdom 中给 Select 与 Drawer 复用同一个标题 id，导致 dialog 的可访问名丢失；
    // 这里按 DOM 角色定位，生产浏览器中标题仍由 antd 正常关联。
    await screen.findByRole('dialog');
    expect(await screen.findByText('可管理部门')).toBeVisible();
    expect(await screen.findByText('可管理部门')).toBeVisible();
    expect(screen.getByText('销售部')).toBeVisible();
    expect(screen.getByText(/当前是个人空间，仅你本人可见/)).toBeVisible();
  });

  it('部门负责人显示入口，其他部门负责人不显示', async () => {
    const first = renderChat(DEMO_SESSION, makeManagementApi([makeDepartment()]));
    expect(await screen.findByRole('button', { name: '工作空间共享' })).toBeVisible();
    first.unmount();

    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
    const api = createFakeApi();
    const { unmount } = render(
      withProviders(
        <ChatPage
          api={api}
          managementApi={makeManagementApi([
            makeDepartment({ managerUserId: 'user-other', managerDisplayName: '其他负责人' }),
          ])}
          navigate={vi.fn()}
          pollIntervalMs={10}
        />,
      ),
    );
    expect(await screen.findByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    expect(screen.queryByRole('button', { name: '工作空间共享' })).not.toBeInTheDocument();
    unmount();
  });

  it('会话界面固定展示知识库租户级共享文案', async () => {
    renderChat(DEMO_SESSION);

    expect(await screen.findByText(KNOWLEDGE_SHARING_NOTICE)).toBeVisible();
    expect(screen.queryByText(/按部门隔离知识库/)).not.toBeInTheDocument();
    expect(screen.queryByText(/文档级权限已生效/)).not.toBeInTheDocument();
  });

  it('知识库引用详情展示共享范围提示，不声称文档级权限', async () => {
    const knowledgeCitation: Citation = {
      ordinal: 1,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: 'doc-1',
      locatorType: 'PARAGRAPH',
      paragraphNo: 2,
      title: '知识库：协同办公市场研究',
      snippet: '知识库片段可以跨部门检索。',
    };
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () =>
        makeDetail([
          makeMessage({
            id: 'a1',
            seq: 2,
            role: 'ASSISTANT',
            content: '知识库回答。',
            citations: [knowledgeCitation],
          }),
        ]),
      ),
    });
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
    render(withProviders(<ChatPage api={api} navigate={vi.fn()} pollIntervalMs={10} />));

    const conversation = await screen.findByRole('button', { name: '示例会话' });
    fireEvent.click(conversation);
    fireEvent.click(await screen.findByRole('button', { name: /知识库：协同办公市场研究/ }));

    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText(KNOWLEDGE_SHARING_NOTICE)).toBeVisible();
    expect(within(drawer).queryByText(/打开原文件/)).not.toBeInTheDocument();
  });

  it('来源文件 404 显示可读原因并移除打开入口', async () => {
    const fileCitation: Citation = {
      ordinal: 1,
      sourceType: 'KNOWLEDGE',
      fileId: 'file-other-dept',
      knowledgeDocumentId: 'doc-1',
      locatorType: 'PAGE',
      pageNo: 3,
      title: '其他部门上传的报告.pdf',
      snippet: '源文件属于其他部门空间。',
    };
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () =>
        makeDetail([
          makeMessage({
            id: 'a1',
            seq: 2,
            role: 'ASSISTANT',
            content: '引用回答。',
            citations: [fileCitation],
          }),
        ]),
      ),
      getFileDownloadUrl: vi.fn(async () => {
        throw new ApiError('not found', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    const popup = { opener: null, close: vi.fn(), location: { href: '' } };
    const openSpy = vi
      .spyOn(window, 'open')
      .mockReturnValue(popup as unknown as Window & typeof globalThis);
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
    render(withProviders(<ChatPage api={api} navigate={vi.fn()} pollIntervalMs={10} />));

    fireEvent.click(await screen.findByRole('button', { name: '示例会话' }));
    fireEvent.click(await screen.findByRole('button', { name: /其他部门上传的报告\.pdf/ }));
    fireEvent.click(await screen.findByRole('button', { name: '打开原文件' }));

    await waitFor(() => {
      expect(screen.getByText(SOURCE_FILE_UNAVAILABLE)).toBeVisible();
    });
    expect(screen.getByText('源文件不可访问')).toBeVisible();
    expect(screen.queryByRole('button', { name: '打开原文件' })).not.toBeInTheDocument();
    openSpy.mockRestore();
  });
});

describe('员工端所属部门', () => {
  it('普通成员显示主部门，未分配时显示明确文案', async () => {
    const first = renderChat(DEMO_SESSION, makeManagementApi([makeDepartment()]));
    expect(await screen.findByText('所属部门：')).toBeVisible();
    expect(screen.getByText('销售部')).toBeVisible();
    first.unmount();

    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
    render(
      withProviders(
        <ChatPage
          api={createFakeApi()}
          managementApi={makeManagementApi([])}
          navigate={vi.fn()}
          pollIntervalMs={10}
        />,
      ),
    );
    expect(await screen.findByText('未分配部门')).toBeVisible();
  });

  it('停用部门仍显示名称并标注已停用', async () => {
    renderChat(DEMO_SESSION, makeManagementApi([makeDepartment({ status: 'DISABLED' })]));

    expect(await screen.findByText('销售部')).toBeVisible();
    expect(screen.getByText('已停用')).toBeVisible();
  });

  it('租户管理员显示角色标签，不把第一个部门误报为所属部门', async () => {
    renderChat(TENANT_ADMIN, makeManagementApi([makeDepartment()]));

    expect(await screen.findByText('租户管理员')).toBeVisible();
    expect(screen.queryByText('所属部门：')).not.toBeInTheDocument();
    expect(screen.queryByText(/所属部门：销售部/)).not.toBeInTheDocument();
  });
});
