// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Department, SessionInfo, WorkspaceFile } from '@/services/api/types';
import type {
  KnowledgeApi,
  KnowledgeDocument,
  KnowledgeDocumentAcl,
  KnowledgeUserDirectory,
} from '@/services/knowledge';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import { KnowledgeDrawer } from './knowledge-drawer';

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

/** 普通员工会话（只有 knowledge.read）。 */
const MEMBER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  permissions: [...DEMO_SESSION.permissions, 'knowledge.read'],
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

/** 构造文档。 */
function makeDocument(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: 'doc-1',
    title: '销售数据.xlsx',
    sourceType: 'UPLOAD',
    fileId: 'file-1',
    status: 'INDEXED',
    checksum: 'a'.repeat(64),
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    sizeBytes: 2048,
    createdAt: '2026-11-23T10:00:00+08:00',
    indexedAt: '2026-11-23T10:05:00+08:00',
    failedAt: null,
    visibility: 'TENANT',
    departmentId: null,
    aclUserCount: 0,
    parseEngine: 'POI',
    retentionDays: 180,
    retentionPolicy: 'DEPLOYMENT_DEFAULT',
    retentionExpiresAt: '2027-05-22T10:00:00+08:00',
    warnings: [],
    ...overrides,
  };
}

/** 构造 ACL。 */
function makeAcl(overrides: Partial<KnowledgeDocumentAcl> = {}): KnowledgeDocumentAcl {
  return {
    visibility: 'TENANT',
    departmentId: null,
    creatorUserId: 'user-1',
    users: [{ userId: 'user-1', displayName: '演示员工', creator: true }],
    ...overrides,
  };
}

/** 构造知识库 API 替身。 */
function makeKnowledgeApi(overrides: Partial<KnowledgeApi> = {}): KnowledgeApi {
  return {
    listDocuments: vi.fn(async () => pageOf([makeDocument()])),
    createDocument: vi.fn(async () => makeDocument({ id: 'doc-new' })),
    getDocument: vi.fn(async () => makeDocument()),
    getDocumentAcl: vi.fn(async () => makeAcl()),
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

/** 构造用户目录替身。 */
function makeUserDirectory(
  overrides: Partial<KnowledgeUserDirectory> = {},
): KnowledgeUserDirectory {
  return {
    listUsers: vi.fn(async () => [
      {
        id: 'user-1',
        username: 'demo',
        displayName: '演示员工',
        email: null,
        status: 'ACTIVE' as const,
        roles: ['TENANT_ADMIN'],
        lastLoginAt: null,
        createdAt: '2026-11-18T08:00:00+08:00',
      },
      {
        id: 'user-2',
        username: 'wangmin',
        displayName: '王敏',
        email: null,
        status: 'ACTIVE' as const,
        roles: ['MEMBER'],
        lastLoginAt: null,
        createdAt: '2026-11-18T08:00:00+08:00',
      },
    ]),
    ...overrides,
  };
}

/** 渲染已打开的知识库抽屉。 */
function renderDrawer(
  api: KnowledgeApi,
  session: SessionInfo = ADMIN_SESSION,
  userDirectory: KnowledgeUserDirectory = makeUserDirectory(),
  departments: Department[] = [makeDepartment()],
  files: WorkspaceFile[] = [makeWorkspaceFile()],
) {
  return render(
    withProviders(
      <KnowledgeDrawer
        api={api}
        departments={departments}
        listWorkspaceFiles={vi.fn(async () => files)}
        onClose={vi.fn()}
        open
        session={session}
        userDirectory={userDirectory}
      />,
    ),
  );
}

/** 构造工作空间文件。 */
function makeWorkspaceFile(overrides: Partial<WorkspaceFile> = {}): WorkspaceFile {
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

describe('知识库列表', () => {
  it('展示可见范围摘要、状态与详情入口', async () => {
    const api = makeKnowledgeApi();
    renderDrawer(api);

    expect(await screen.findByText('销售数据.xlsx')).toBeVisible();
    expect(screen.getAllByText('租户全员').length).toBeGreaterThan(0);
    expect(screen.getByText('本租户全员可见（创建人始终可见）')).toBeVisible();
    expect(screen.getByText('已索引')).toBeVisible();
    expect(screen.getByTestId('knowledge-legacy-notice')).toHaveTextContent(
      '历史文档默认全租户可见，可收紧',
    );
    expect(screen.getByRole('button', { name: '查看文档：销售数据.xlsx' })).toBeVisible();
  });

  it('空态给出下一步', async () => {
    renderDrawer(makeKnowledgeApi({ listDocuments: vi.fn(async () => pageOf([])) }));

    expect(await screen.findByText(/当前没有可见的知识库文档/)).toBeVisible();
  });

  it('403 显示无权限，不渲染列表', async () => {
    renderDrawer(
      makeKnowledgeApi({
        listDocuments: vi.fn(async () => {
          throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
        }),
      }),
    );

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.queryByText('销售数据.xlsx')).not.toBeInTheDocument();
  });

  it('网络/500 显示可重试错误', async () => {
    const api = makeKnowledgeApi({
      listDocuments: vi
        .fn()
        .mockRejectedValueOnce(new ApiError('boom', { status: 500, code: 'RICCON-COMMON-500' }))
        .mockResolvedValueOnce(pageOf([makeDocument()])),
    });
    renderDrawer(api);

    expect(await screen.findByText('知识库加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('销售数据.xlsx')).toBeVisible();
  });

  it('状态筛选携带 status 并回到第一页', async () => {
    const api = makeKnowledgeApi();
    renderDrawer(api);
    await screen.findByText('销售数据.xlsx');

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '筛选文档状态' }));
    fireEvent.click(await screen.findByText('解析失败'));

    await waitFor(() => {
      expect(api.listDocuments).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1, status: 'FAILED' }),
      );
    });
  });
});

describe('知识库详情与 ACL', () => {
  it('打开详情显示创建人、名单摘要、解析状态与保留策略', async () => {
    const api = makeKnowledgeApi({
      getDocumentAcl: vi.fn(async () =>
        makeAcl({
          visibility: 'USERS',
          users: [
            { userId: 'user-1', displayName: '演示员工', creator: true },
            { userId: 'user-2', displayName: '王敏' },
          ],
        }),
      ),
    });
    renderDrawer(api);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));

    expect(await screen.findByTestId('knowledge-document-title')).toHaveTextContent(
      '销售数据.xlsx',
    );
    expect(api.getDocument).toHaveBeenCalledWith('doc-1');
    expect(api.getDocumentAcl).toHaveBeenCalledWith('doc-1');
    expect(screen.getAllByText('演示员工').length).toBeGreaterThan(0);
    expect(screen.getByText('王敏')).toBeVisible();
    expect(screen.getAllByText('识别成功').length).toBeGreaterThan(0);
    expect(screen.getByText(/到期 2027-05-22/)).toBeVisible();
    expect(screen.getByTestId('knowledge-retention-note')).toHaveTextContent(
      '保留策略变更仅影响新文档和新的到期计算，不追溯历史文档',
    );
    expect(screen.queryByTestId('knowledge-failed-at')).not.toBeInTheDocument();
  });

  it('部门范围显示部门名；USERS 名单只显示摘要', async () => {
    const document = makeDocument({
      visibility: 'DEPT',
      departmentId: 'dept-sales',
      aclUserCount: 0,
    });
    renderDrawer(
      makeKnowledgeApi({
        listDocuments: vi.fn(async () => pageOf([document])),
        getDocument: vi.fn(async () => document),
        getDocumentAcl: vi.fn(async () =>
          makeAcl({ visibility: 'DEPT', departmentId: 'dept-sales', users: [] }),
        ),
      }),
    );

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect((await screen.findAllByText('指定部门：销售部')).length).toBeGreaterThan(0);
    expect(await screen.findByText('当前范围无需单独名单；创建人始终可见')).toBeVisible();
  });

  it('详情 404 显示已删除/不可见，不展示正文', async () => {
    renderDrawer(
      makeKnowledgeApi({
        getDocument: vi.fn(async () => {
          throw new ApiError('gone', { status: 404, code: 'RICCON-TENANT-404' });
        }),
      }),
    );

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect(await screen.findByText(/文档不存在、已被删除/)).toBeVisible();
  });

  it('保存 DEPT 范围：必须选择部门，提交整体替换', async () => {
    const departments = [makeDepartment()];
    const api = makeKnowledgeApi({
      updateDocumentAcl: vi.fn(async () => makeDocument({ visibility: 'DEPT' })),
    });
    renderDrawer(api, ADMIN_SESSION, makeUserDirectory(), departments);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-acl-edit'));

    fireEvent.click(screen.getByRole('radio', { name: '指定部门' }));
    await screen.findByRole('combobox', { name: '可见部门' });
    fireEvent.click(screen.getByRole('button', { name: '保存可见范围' }));
    expect(await screen.findByText('请选择单个启用部门。')).toBeInTheDocument();
    expect(api.updateDocumentAcl).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '可见部门' }));
    fireEvent.click(await screen.findByText('销售部'));
    fireEvent.click(screen.getByRole('button', { name: '保存可见范围' }));

    await waitFor(() => {
      expect(api.updateDocumentAcl).toHaveBeenCalledWith('doc-1', {
        visibility: 'DEPT',
        departmentId: 'dept-sales',
      });
    });
    expect(await screen.findByTestId('knowledge-notice')).toHaveTextContent(
      '可见范围已保存，下一次请求生效。',
    );
  });

  it('TENANT 保存清空部门与名单', async () => {
    const api = makeKnowledgeApi({
      getDocumentAcl: vi.fn(async () =>
        makeAcl({ visibility: 'DEPT', departmentId: 'dept-sales', users: [] }),
      ),
    });
    renderDrawer(api);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-acl-edit'));
    fireEvent.click(screen.getByRole('radio', { name: '租户全员' }));
    fireEvent.click(screen.getByRole('button', { name: '保存可见范围' }));

    await waitFor(() => {
      expect(api.updateDocumentAcl).toHaveBeenCalledWith('doc-1', { visibility: 'TENANT' });
    });
  });

  it('USERS 范围过滤创建人并提交名单', async () => {
    const userDirectory = makeUserDirectory();
    const api = makeKnowledgeApi({
      updateDocumentAcl: vi.fn(async () => makeDocument({ visibility: 'USERS' })),
    });
    renderDrawer(api, ADMIN_SESSION, userDirectory);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-acl-edit'));
    fireEvent.click(screen.getByRole('radio', { name: '指定用户' }));

    await waitFor(() => {
      expect(userDirectory.listUsers).toHaveBeenCalled();
    });
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '指定用户' }));
    expect(await screen.findByText('王敏（wangmin）')).toBeInTheDocument();
    expect(screen.queryByText('演示员工（demo）')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('王敏（wangmin）'));
    fireEvent.click(screen.getByRole('button', { name: '保存可见范围' }));

    await waitFor(() => {
      expect(api.updateDocumentAcl).toHaveBeenCalledWith('doc-1', {
        visibility: 'USERS',
        userIds: ['user-2'],
      });
    });
  });

  it('保存 403 显示可读原因且不出现部分生效提示', async () => {
    const api = makeKnowledgeApi({
      updateDocumentAcl: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderDrawer(api);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-acl-edit'));
    fireEvent.click(screen.getByRole('radio', { name: '租户全员' }));
    fireEvent.click(screen.getByRole('button', { name: '保存可见范围' }));

    expect(await screen.findByTestId('knowledge-acl-error')).toHaveTextContent(
      '没有修改该文档可见范围的权限。',
    );
    expect(screen.queryByTestId('knowledge-action-notice')).not.toBeInTheDocument();
  });

  it('普通员工不显示 ACL 编辑入口', async () => {
    renderDrawer(
      makeKnowledgeApi({
        getDocumentAcl: vi.fn(async () => {
          throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
        }),
      }),
      MEMBER_SESSION,
    );

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect(await screen.findByTestId('knowledge-detail')).toBeVisible();
    expect(screen.queryByTestId('knowledge-acl-edit')).not.toBeInTheDocument();
    expect(screen.queryByTestId('knowledge-delete')).not.toBeInTheDocument();
  });
});

describe('解析状态与重试', () => {
  it('低置信态显示提示但可检索，重试入口不误显示为失败', async () => {
    const document = makeDocument({
      warnings: ['LOW_CONFIDENCE', 'LOW_CONFIDENCE_PAGES:[1]'],
      parseSummary: {
        ocr: {
          averageConfidence: 0.52,
          lowConfidencePages: [1],
          failedPages: [],
        },
      },
    });
    renderDrawer(
      makeKnowledgeApi({
        listDocuments: vi.fn(async () => pageOf([document])),
        getDocument: vi.fn(async () => document),
      }),
    );

    expect(await screen.findByText('识别成功（置信度低，请核对原文）')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect((await screen.findAllByText(/平均置信度 52%/)).length).toBeGreaterThan(0);
  });

  it('部分成功列出失败页并通过 reindex(force=false) 补齐', async () => {
    const document = makeDocument({
      warnings: ['FAILED_PAGES:[2]'],
      parseSummary: {
        ocr: { averageConfidence: 0.9, lowConfidencePages: [], failedPages: [2] },
      },
    });
    const api = makeKnowledgeApi({
      listDocuments: vi.fn(async () => pageOf([document])),
      getDocument: vi.fn(async () => document),
    });
    renderDrawer(api);

    expect(await screen.findByText('部分成功')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-retry'));

    await waitFor(() => {
      expect(api.reindexDocument).toHaveBeenCalledWith('doc-1', false);
    });
  });

  it('失败态显示中文原因，重试走 force=true 且不展示堆栈', async () => {
    const document = makeDocument({
      status: 'FAILED',
      errorCode: 'KB_PARSE_CORRUPT',
      errorMessage: 'at java.lang.Reader.read(Reader.java:1)',
      failedAt: '2026-11-23T18:30:00+08:00',
    });
    const api = makeKnowledgeApi({
      listDocuments: vi.fn(async () => pageOf([document])),
      getDocument: vi.fn(async () => document),
    });
    renderDrawer(api);

    expect(await screen.findByText('解析失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect(await screen.findByText(/文件损坏或格式不合法/)).toBeVisible();
    expect(screen.getByTestId('knowledge-failed-at')).toHaveTextContent(
      '失败时间：2026-11-23 18:30:00 +08:00',
    );
    expect(screen.queryByText(/java\.lang/)).not.toBeInTheDocument();

    fireEvent.click(await screen.findByTestId('knowledge-retry'));
    await waitFor(() => {
      expect(api.reindexDocument).toHaveBeenCalledWith('doc-1', true);
    });
  });

  it('OCR 关闭显示“识别未启用”与下一步，不出现伪引用错误', async () => {
    const document = makeDocument({
      status: 'FAILED',
      errorCode: 'KB_OCR_DISABLED',
      mimeType: 'image/png',
    });
    renderDrawer(
      makeKnowledgeApi({
        listDocuments: vi.fn(async () => pageOf([document])),
        getDocument: vi.fn(async () => document),
      }),
    );

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect((await screen.findAllByText('识别未启用')).length).toBeGreaterThan(0);
    expect(screen.getByText(/OCR 未启用，图片仅保留原图与文件名/)).toBeVisible();
  });

  it('加载态显示解析中且不显示重试', async () => {
    const document = makeDocument({ status: 'INDEXING' });
    renderDrawer(
      makeKnowledgeApi({
        listDocuments: vi.fn(async () => pageOf([document])),
        getDocument: vi.fn(async () => document),
      }),
    );

    expect(await screen.findByText('解析中')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '查看文档：销售数据.xlsx' }));
    expect(await screen.findByText(/文档正在后台解析/)).toBeVisible();
    expect(screen.queryByTestId('knowledge-retry')).not.toBeInTheDocument();
  });
});

describe('下载与删除', () => {
  it('下载调用服务端代理并展示 KB-404 中文提示', async () => {
    const api = makeKnowledgeApi({
      downloadDocument: vi.fn(async () => {
        throw new ApiError('no source', { status: 404, code: 'RICCON-KB-404' });
      }),
    });
    renderDrawer(api);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-download'));

    expect(await screen.findByTestId('knowledge-download-error')).toHaveTextContent(
      '该文档没有可下载的源文件。',
    );
    expect(api.downloadDocument).toHaveBeenCalledWith('doc-1');
  });

  it('无源文件文档直接提示，不发起下载请求', async () => {
    const document = makeDocument({ sourceType: 'SEED', fileId: null });
    const api = makeKnowledgeApi({
      listDocuments: vi.fn(async () => pageOf([document])),
      getDocument: vi.fn(async () => document),
    });
    renderDrawer(api);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-download'));

    expect(await screen.findByTestId('knowledge-download-error')).toHaveTextContent(
      '该文档没有可下载的源文件。',
    );
    expect(api.downloadDocument).not.toHaveBeenCalled();
  });

  it('删除成功后关闭详情并刷新列表', async () => {
    const api = makeKnowledgeApi();
    renderDrawer(api);

    fireEvent.click(await screen.findByRole('button', { name: '查看文档：销售数据.xlsx' }));
    fireEvent.click(await screen.findByTestId('knowledge-delete'));
    fireEvent.click(await screen.findByRole('button', { name: '确认删除' }));

    await waitFor(() => {
      expect(api.deleteDocument).toHaveBeenCalledWith('doc-1');
    });
    await waitFor(() => {
      expect(api.listDocuments).toHaveBeenCalledTimes(2);
    });
    expect(screen.queryByTestId('knowledge-detail')).not.toBeInTheDocument();
  });
});

describe('新文档登记入口', () => {
  it('租户管理员可登记文档，成功后刷新列表并显示持久提示', async () => {
    const created = makeDocument({
      id: 'doc-new',
      title: '新文档.xlsx',
      status: 'PENDING',
    });
    const api = makeKnowledgeApi({
      createDocument: vi.fn(async () => created),
      getDocument: vi.fn(async (documentId: string) =>
        documentId === created.id ? created : makeDocument(),
      ),
      listDocuments: vi
        .fn()
        .mockResolvedValueOnce(pageOf([makeDocument()]))
        .mockResolvedValueOnce(pageOf([created, makeDocument()])),
    });
    renderDrawer(api);

    fireEvent.click(await screen.findByTestId('knowledge-create-open'));
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '源文件' }));
    fireEvent.click(await screen.findByText(/销售数据\.xlsx · 2 KB/));
    fireEvent.click(screen.getByRole('checkbox', { name: /我已确认可见范围影响/ }));
    fireEvent.click(screen.getByTestId('knowledge-create-submit'));

    await waitFor(() => {
      expect(api.createDocument).toHaveBeenCalledWith(
        {
          title: '销售数据',
          fileId: 'file-1',
          visibility: 'TENANT',
        },
        expect.any(String),
      );
    });
    expect(await screen.findByTestId('knowledge-notice')).toHaveTextContent(
      '文档已登记并进入解析队列',
    );
    await waitFor(() => {
      expect(api.listDocuments).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByTestId('knowledge-document-title')).toHaveTextContent('新文档.xlsx');
  });

  it('普通员工不显示登记入口', async () => {
    renderDrawer(makeKnowledgeApi(), MEMBER_SESSION);

    expect(await screen.findByText('销售数据.xlsx')).toBeVisible();
    expect(screen.queryByTestId('knowledge-create-open')).not.toBeInTheDocument();
  });
});
