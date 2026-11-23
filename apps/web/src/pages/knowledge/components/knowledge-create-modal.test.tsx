// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Department, SessionInfo, UserSummary, WorkspaceFile } from '@/services/api/types';
import type { KnowledgeApi, KnowledgeDocument, KnowledgeUserDirectory } from '@/services/knowledge';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import { KnowledgeCreateModal, buildKnowledgeCreateRequest } from './knowledge-create-modal';

/** 租户管理员会话。 */
const ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [
    ...DEMO_SESSION.permissions,
    'knowledge.read',
    'knowledge.manage',
    'knowledge.acl.manage',
  ],
};

/** 构造部门。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-1',
    managerDisplayName: '演示员工',
    memberCount: 2,
    createdAt: '2026-11-18T08:00:00+08:00',
    updatedAt: '2026-11-18T08:00:00+08:00',
    ...overrides,
  };
}

/** 构造工作空间文件。 */
function makeFile(overrides: Partial<WorkspaceFile> = {}): WorkspaceFile {
  return {
    id: 'file-1',
    workspaceId: 'workspace-1',
    conversationId: 'conversation-1',
    originalName: '销售数据.xlsx',
    format: 'XLSX',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    sizeBytes: 2048,
    sha256: 'a'.repeat(64),
    status: 'AVAILABLE',
    parse: null,
    createdAt: '2026-11-23T10:00:00+08:00',
    deletedAt: null,
    ...overrides,
  };
}

/** 构造文档。 */
function makeDocument(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: 'doc-new',
    title: '销售数据',
    sourceType: 'UPLOAD',
    fileId: 'file-1',
    status: 'PENDING',
    checksum: 'b'.repeat(64),
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    sizeBytes: 2048,
    createdAt: '2026-11-23T10:00:00+08:00',
    visibility: 'TENANT',
    departmentId: null,
    aclUserCount: 0,
    ...overrides,
  };
}

/** 构造知识库 API 替身。 */
function makeApi(overrides: Partial<KnowledgeApi> = {}): KnowledgeApi {
  return {
    listDocuments: vi.fn(async () => pageOf([makeDocument()])),
    createDocument: vi.fn(async () => makeDocument()),
    getDocument: vi.fn(async () => makeDocument()),
    getDocumentAcl: vi.fn(async () => ({
      visibility: 'TENANT' as const,
      departmentId: null,
      creatorUserId: 'user-1',
      users: [],
    })),
    updateDocumentAcl: vi.fn(async () => makeDocument()),
    reindexDocument: vi.fn(async () => makeDocument()),
    deleteDocument: vi.fn(async () => undefined),
    downloadDocument: vi.fn(async () => ({
      blob: new Blob(['hello']),
      filename: '销售数据.xlsx',
    })),
    documentDownloadPath: (id: string) => `/api/v1/knowledge/documents/${id}/download`,
    ...overrides,
  };
}

/** 构造用户目录。 */
function makeUserDirectory(users: UserSummary[]): KnowledgeUserDirectory {
  return { listUsers: vi.fn(async () => users) };
}

/** 构造用户。 */
function makeUser(id: string, displayName: string): UserSummary {
  return {
    id,
    username: id,
    displayName,
    email: null,
    status: 'ACTIVE',
    roles: ['MEMBER'],
    lastLoginAt: null,
    createdAt: '2026-11-18T08:00:00+08:00',
  };
}

/** 渲染登记弹窗。 */
function renderModal(
  api: KnowledgeApi,
  overrides: {
    files?: WorkspaceFile[];
    users?: UserSummary[];
    departments?: Department[];
    onCreated?: (document: KnowledgeDocument) => void;
  } = {},
) {
  const onCreated = overrides.onCreated ?? vi.fn();
  render(
    withProviders(
      <KnowledgeCreateModal
        api={api}
        departments={overrides.departments ?? [makeDepartment()]}
        listWorkspaceFiles={vi.fn(async () => overrides.files ?? [makeFile()])}
        onClose={vi.fn()}
        onCreated={onCreated}
        open
        session={ADMIN_SESSION}
        userDirectory={makeUserDirectory(
          overrides.users ?? [makeUser('user-1', '演示员工'), makeUser('user-2', '王敏')],
        )}
      />,
    ),
  );
  return { onCreated };
}

/** 选择源文件。 */
async function selectFile(name = /销售数据\.xlsx/): Promise<void> {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '源文件' }));
  fireEvent.click(await screen.findByText(name));
}

/** 勾选影响确认。 */
function confirmImpact(): void {
  fireEvent.click(screen.getByRole('checkbox', { name: /我已确认可见范围影响/ }));
}

describe('新文档登记弹窗', () => {
  it('默认 TENANT，未确认影响不能保存；确认后提交标题/文件/范围与幂等键', async () => {
    const api = makeApi();
    const { onCreated } = renderModal(api);

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '租户全员' })).toBeChecked();
    await selectFile();
    expect(screen.getByLabelText('文档标题')).toHaveValue('销售数据');

    fireEvent.click(screen.getByTestId('knowledge-create-submit'));
    expect(await screen.findByText('请先确认可见范围影响。')).toBeInTheDocument();
    expect(api.createDocument).not.toHaveBeenCalled();

    confirmImpact();
    fireEvent.click(screen.getByTestId('knowledge-create-submit'));

    await waitFor(() => {
      expect(api.createDocument).toHaveBeenCalledTimes(1);
    });
    expect(api.createDocument).toHaveBeenCalledWith(
      { title: '销售数据', fileId: 'file-1', visibility: 'TENANT' },
      expect.any(String),
    );
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ id: 'doc-new' }));
  });

  it('DEPT 必须选择单个启用部门，提交部门 ID', async () => {
    const api = makeApi();
    renderModal(api);

    await selectFile();
    fireEvent.click(screen.getByRole('radio', { name: '指定部门' }));
    await screen.findByRole('combobox', { name: '可见部门' });
    confirmImpact();
    fireEvent.click(screen.getByTestId('knowledge-create-submit'));
    expect(await screen.findByText('请选择单个启用部门。')).toBeInTheDocument();
    expect(api.createDocument).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '可见部门' }));
    fireEvent.click(await screen.findByText('销售部'));
    fireEvent.click(screen.getByTestId('knowledge-create-submit'));

    await waitFor(() => {
      expect(api.createDocument).toHaveBeenCalledWith(
        {
          title: '销售数据',
          fileId: 'file-1',
          visibility: 'DEPT',
          departmentId: 'dept-sales',
        },
        expect.any(String),
      );
    });
  });

  it('USERS 过滤创建人并提交名单', async () => {
    const api = makeApi();
    renderModal(api);

    await selectFile();
    fireEvent.click(screen.getByRole('radio', { name: '指定用户' }));
    await screen.findByRole('combobox', { name: '指定用户' });
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '指定用户' }));
    expect(await screen.findByText('王敏（user-2）')).toBeInTheDocument();
    expect(screen.queryByText('演示员工（user-1）')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('王敏（user-2）'));
    confirmImpact();
    fireEvent.click(screen.getByTestId('knowledge-create-submit'));

    await waitFor(() => {
      expect(api.createDocument).toHaveBeenCalledWith(
        {
          title: '销售数据',
          fileId: 'file-1',
          visibility: 'USERS',
          userIds: ['user-2'],
        },
        expect.any(String),
      );
    });
  });

  it('登记错误显示中文原因且不关闭弹窗', async () => {
    const api = makeApi({
      createDocument: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const { onCreated } = renderModal(api);

    await selectFile();
    confirmImpact();
    fireEvent.click(screen.getByTestId('knowledge-create-submit'));

    expect(await screen.findByTestId('knowledge-create-error')).toHaveTextContent(
      '没有登记知识库文档的权限',
    );
    expect(onCreated).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('无文件时给出先上传的下一步，并允许刷新文件列表', async () => {
    const listWorkspaceFiles = vi.fn(async () => [] as WorkspaceFile[]);
    render(
      withProviders(
        <KnowledgeCreateModal
          api={makeApi()}
          departments={[makeDepartment()]}
          listWorkspaceFiles={listWorkspaceFiles}
          onClose={vi.fn()}
          onCreated={vi.fn()}
          open
          session={ADMIN_SESSION}
          userDirectory={makeUserDirectory([])}
        />,
      ),
    );

    expect(await screen.findByText(/当前工作空间没有可登记文件/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('knowledge-create-reload-files'));
    await waitFor(() => {
      expect(listWorkspaceFiles).toHaveBeenCalledTimes(2);
    });
  });

  it('创建请求构造覆盖 TENANT/DEPT/USERS 且 USERS 不静默截断 200 上限', () => {
    expect(
      buildKnowledgeCreateRequest(
        { title: ' A ', fileId: 'f1', visibility: 'TENANT', confirmed: true },
        'user-1',
      ),
    ).toEqual({ title: 'A', fileId: 'f1', visibility: 'TENANT' });
    expect(
      buildKnowledgeCreateRequest(
        {
          title: 'A',
          fileId: 'f1',
          visibility: 'DEPT',
          departmentId: 'dept-sales',
          confirmed: true,
        },
        'user-1',
      ),
    ).toMatchObject({ visibility: 'DEPT', departmentId: 'dept-sales' });
    const manyUsers = Array.from({ length: 201 }, (_value, index) => `u${index}`);
    const request = buildKnowledgeCreateRequest(
      {
        title: 'A',
        fileId: 'f1',
        visibility: 'USERS',
        userIds: ['user-1', 'u0', 'u0', ...manyUsers],
        confirmed: true,
      },
      'user-1',
    );
    expect(request.userIds).toHaveLength(201);
    expect(request.userIds).not.toContain('user-1');
    expect(request.userIds).toEqual([...new Set(request.userIds)]);
  });
});
