import { BookOutlined, FileAddOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Drawer,
  Empty,
  Flex,
  Result,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Department, SessionInfo, WorkspaceFile } from '@/services/api/types';
import { knowledgeUserDirectory as defaultKnowledgeUserDirectory } from '@/services/knowledge';
import {
  describeKnowledgeError,
  knowledgeParseState,
  knowledgeParseStateLabel,
  KNOWLEDGE_PERMISSIONS,
  hasKnowledgePermission,
  knowledgeStatusTagStyle,
  knowledgeStatusLabel,
  knowledgeVisibilityTagStyle,
  knowledgeVisibilityLabel,
  knowledgeVisibilitySummary,
} from '@/services/knowledge';
import { knowledgeApi as defaultKnowledgeApi } from '@/services/knowledge';
import type {
  KnowledgeApi,
  KnowledgeDocument,
  KnowledgeDocumentStatus,
  KnowledgeUserDirectory,
} from '@/services/knowledge';
import { KnowledgeCreateModal } from './knowledge-create-modal';
import { KnowledgeDetailPanel } from './knowledge-detail';
import './knowledge.css';

/** 知识库抽屉属性。 */
export interface KnowledgeDrawerProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调。 */
  onClose: () => void;
  /** 当前会话。 */
  session: SessionInfo | null;
  /** 可管理部门；用于部门名与 ACL 选择。 */
  departments?: readonly Department[];
  /** 知识库 API；测试可注入。 */
  api?: KnowledgeApi;
  /** 用户目录；测试可注入。 */
  userDirectory?: KnowledgeUserDirectory;
  /** 每页大小；测试可缩短。 */
  pageSize?: number;
  /** 读取当前工作空间已上传文件；由 J1 页面注入。 */
  listWorkspaceFiles?: () => Promise<WorkspaceFile[]>;
}

/** 列表状态筛选可选项。 */
const STATUS_OPTIONS: Array<{ label: string; value: KnowledgeDocumentStatus }> = [
  { label: '排队中', value: 'PENDING' },
  { label: '解析中', value: 'INDEXING' },
  { label: '已索引', value: 'INDEXED' },
  { label: '解析失败', value: 'FAILED' },
];

/**
 * 知识库列表、详情与 ACL 抽屉。
 *
 * 入口在 J1 工作空间；M05 的页面能力以抽屉形式提供，避免本次任务修改
 * 不在写入范围内的 Umi 路由表。列表只展示 ACL 过滤后的文档，
 * 详情/ACL 编辑由服务端再次判定权限，前端隐藏入口不代替鉴权。
 *
 * @param props 会话、目录与可注入 API
 * @returns 知识库抽屉
 */
export function KnowledgeDrawer({
  open,
  onClose,
  session,
  departments = [],
  api = defaultKnowledgeApi,
  userDirectory = defaultKnowledgeUserDirectory,
  pageSize = 10,
  listWorkspaceFiles = async () => [],
}: KnowledgeDrawerProps) {
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<KnowledgeDocumentStatus | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const loadTokenRef = useRef(0);

  const selected = documents.find((document) => document.id === selectedId) ?? null;

  /**
   * 读取当前页文档。
   */
  const load = useCallback(async (): Promise<void> => {
    const token = loadTokenRef.current + 1;
    loadTokenRef.current = token;
    setLoading(true);
    setError(null);
    setForbidden(false);
    try {
      const result = await api.listDocuments({
        page,
        size: pageSize,
        ...(status ? { status } : {}),
      });
      if (token !== loadTokenRef.current) {
        return;
      }
      setDocuments(result.items);
      setTotal(result.total);
    } catch (loadError) {
      if (token !== loadTokenRef.current) {
        return;
      }
      const statusCode = (loadError as { status?: number }).status;
      if (statusCode === 403) {
        setForbidden(true);
        setDocuments([]);
      } else {
        setError(describeKnowledgeError(loadError));
      }
    } finally {
      if (token === loadTokenRef.current) {
        setLoading(false);
      }
    }
  }, [api, page, pageSize, status]);

  // 打开、翻页、筛选或显式刷新时重新读取；关闭状态不发请求。
  useEffect(() => {
    if (open) {
      void load();
    }
  }, [open, load, refreshToken]);

  /**
   * 状态筛选变化时回到第一页，避免停留在空页。
   *
   * @param value 目标状态
   */
  const handleStatusChange = (value: KnowledgeDocumentStatus | undefined): void => {
    setStatus(value);
    setPage(1);
  };

  /**
   * 打开文档详情；切换文档时清空上一条成功提示，避免串到另一个文档。
   *
   * @param documentId 文档 ID
   */
  const openDocument = (documentId: string): void => {
    setNotice(null);
    setSelectedId(documentId);
  };

  const canCreate = hasKnowledgePermission(session?.permissions, KNOWLEDGE_PERMISSIONS.manage);

  const columns: TableColumnsType<KnowledgeDocument> = [
    {
      title: '标题',
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (_value, record) => (
        <Flex align="center" gap={6} wrap>
          <Typography.Text strong ellipsis={{ tooltip: record.title }}>
            {record.title}
          </Typography.Text>
          {record.sourceType === 'SEED' && <Tag>种子</Tag>}
          {record.errorCode && <Tag style={knowledgeStatusTagStyle('FAILED')}>待处理</Tag>}
        </Flex>
      ),
    },
    {
      title: '可见范围',
      key: 'visibility',
      className: 'knowledge-visibility-column',
      render: (_value, record) => (
        <Space orientation="vertical" size={0}>
          <Tag style={knowledgeVisibilityTagStyle(record.visibility)}>
            {knowledgeVisibilityLabel(record.visibility)}
          </Tag>
          <Typography.Text
            ellipsis={{ tooltip: knowledgeVisibilitySummary(record, departments) }}
            type="secondary"
          >
            {knowledgeVisibilitySummary(record, departments)}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 150,
      render: (_value, record) => (
        <Space orientation="vertical" size={0}>
          <Tag style={knowledgeStatusTagStyle(record.status)}>
            {knowledgeStatusLabel(record.status)}
          </Tag>
          {record.status === 'INDEXED' && (
            <Typography.Text type="secondary">
              {knowledgeParseStateLabel(knowledgeParseState(record))}
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 96,
      render: (_value, record) => (
        <Button
          aria-label={`查看文档：${record.title}`}
          data-testid={`knowledge-open-${record.id}`}
          onClick={() => openDocument(record.id)}
          size="small"
          type="link"
        >
          查看
        </Button>
      ),
    },
  ];

  return (
    <Drawer
      className="knowledge-drawer"
      data-testid="knowledge-drawer"
      destroyOnHidden
      onClose={onClose}
      open={open}
      size="min(1120px, 96vw)"
      title={
        <Flex align="center" gap={8}>
          <BookOutlined />
          <span>知识库</span>
          <Typography.Text type="secondary">按文档可见范围过滤</Typography.Text>
        </Flex>
      }
    >
      <div className="knowledge-layout">
        <section aria-label="知识库文档列表" className="knowledge-list-pane">
          <Flex align="center" gap={8} justify="space-between" style={{ marginBottom: 12 }} wrap>
            <Select
              allowClear
              aria-label="筛选文档状态"
              onChange={handleStatusChange}
              options={STATUS_OPTIONS}
              placeholder="全部状态"
              style={{ minWidth: 140 }}
              value={status}
            />
            <Flex gap={8} wrap>
              <Button
                aria-label="刷新知识库列表"
                data-testid="knowledge-refresh"
                icon={<ReloadOutlined />}
                loading={loading}
                onClick={() => setRefreshToken((token) => token + 1)}
                size="small"
              >
                刷新
              </Button>
              {canCreate && (
                <Button
                  data-testid="knowledge-create-open"
                  icon={<FileAddOutlined />}
                  onClick={() => {
                    setNotice(null);
                    setCreateOpen(true);
                  }}
                  size="small"
                  type="primary"
                >
                  登记文档
                </Button>
              )}
            </Flex>
          </Flex>

          <Alert
            data-testid="knowledge-legacy-notice"
            description="历史文档默认全租户可见，可收紧；新文档默认租户全员，保存前必须确认可见范围影响。"
            showIcon
            style={{ marginBottom: 12 }}
            title="历史文档可见范围"
            type="info"
          />

          {notice && (
            <Alert
              closable
              data-testid="knowledge-notice"
              description={notice}
              onClose={() => setNotice(null)}
              showIcon
              style={{ marginBottom: 12 }}
              title="操作已生效"
              type="success"
            />
          )}

          {forbidden ? (
            <Result
              data-testid="knowledge-forbidden"
              extra={
                <Button onClick={onClose} type="primary">
                  关闭
                </Button>
              }
              status="403"
              subTitle="当前账号没有知识库读取权限，请联系租户管理员开通 knowledge.read。"
              title="没有访问权限"
            />
          ) : error ? (
            <Result
              data-testid="knowledge-error"
              extra={
                <Button onClick={() => setRefreshToken((token) => token + 1)} type="primary">
                  重试
                </Button>
              }
              status="error"
              subTitle={error}
              title="知识库加载失败"
            />
          ) : loading && documents.length === 0 ? (
            <div aria-busy="true" aria-label="正在加载知识库">
              <Skeleton active paragraph={{ rows: 6 }} />
            </div>
          ) : documents.length === 0 ? (
            <Empty
              data-testid="knowledge-empty"
              description={
                status
                  ? '当前筛选条件下没有文档；可清空筛选后重试。'
                  : '当前没有可见的知识库文档。可在会话中上传文件，并由租户管理员加入知识库。'
              }
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            >
              {status && (
                <Button onClick={() => handleStatusChange(undefined)} size="small">
                  清空筛选
                </Button>
              )}
            </Empty>
          ) : (
            <Table
              columns={columns}
              data-testid="knowledge-list"
              dataSource={documents}
              loading={loading}
              pagination={{
                current: page,
                pageSize,
                total,
                showSizeChanger: false,
                showTotal: (count) => `共 ${count} 个文档`,
                onChange: (nextPage) => setPage(nextPage),
              }}
              rowKey="id"
              size="small"
              tableLayout="fixed"
            />
          )}
        </section>

        <section aria-label="知识库文档详情" className="knowledge-detail-pane">
          {selected ? (
            <KnowledgeDetailPanel
              api={api}
              departments={departments}
              document={selected}
              onBack={() => setSelectedId(null)}
              onChanged={() => setRefreshToken((token) => token + 1)}
              onDeleted={() => {
                setSelectedId(null);
                setRefreshToken((token) => token + 1);
              }}
              onNotice={setNotice}
              session={session}
              userDirectory={userDirectory}
            />
          ) : (
            <Flex align="center" justify="center" style={{ minHeight: 260 }}>
              <Empty
                description="选择左侧文档，查看解析状态、可见范围与下载入口。"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            </Flex>
          )}
        </section>
      </div>

      <KnowledgeCreateModal
        api={api}
        departments={departments}
        listWorkspaceFiles={listWorkspaceFiles}
        onClose={() => setCreateOpen(false)}
        onCreated={(created) => {
          setCreateOpen(false);
          setNotice('文档已登记并进入解析队列；下一次列表请求可见。');
          setSelectedId(created.id);
          setRefreshToken((token) => token + 1);
        }}
        open={createOpen}
        session={session}
        userDirectory={userDirectory}
      />
    </Drawer>
  );
}
