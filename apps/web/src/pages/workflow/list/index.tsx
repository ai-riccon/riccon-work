import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from '@umijs/max';
import {
  Alert,
  Button,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Pagination,
  Result,
  Segmented,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { WorkflowTag } from '@/components/workflow/accessible-tag';
import { api as defaultApi } from '@/services/api';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import { authStore, useAuth } from '@/services/auth/auth-store';
import type { SessionReader } from '@/services/auth/auth-store';
import {
  hasWorkflowPermission,
  WORKFLOW_PERMISSIONS,
  workflowDisplayStatus,
  workflowVersionSummary,
} from './format';
import {
  MARKET_RESEARCH_TEMPLATE_ID,
  workflowApi as defaultWorkflowApi,
  type WorkflowApi,
  type WorkflowDefinition,
  type WorkflowDefinitionStatus,
  type WorkflowView,
} from './workflow-api';
import './workflow.css';

/** 列表加载阶段。 */
type ListPhase = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden' | 'disabled';

/** 新建弹窗的创建方式。 */
type CreateMode = 'BLANK' | 'TEMPLATE';

/** 新建流程表单值。 */
interface CreateFormValues {
  name?: string;
  description?: string;
}

/** 列表页可注入依赖，便于组件测试。 */
export interface WorkflowListPageProps {
  /** 编排 API；默认同源真实客户端。 */
  api?: WorkflowApi;
  /** 会话读取能力；默认复用员工端 ApiClient。 */
  sessionReader?: SessionReader;
  /** 导航；测试注入。 */
  navigate?: (to: string) => void;
  /** 初始视图；测试注入。 */
  initialView?: WorkflowView;
}

/** 每页条数；列表页固定 20，符合合同分页上限。 */
const PAGE_SIZE = 20;

/**
 * 判断错误是否表示编排总开关关闭。
 *
 * TASK-0236 会提供 runtime-limits 端点；在该端点落地前，API 的 503 `RICCON-WF-503`
 * 是唯一的关闭信号。这里只按稳定错误码判定，不猜测网络错误。
 *
 * @param error 任意异常
 * @returns 是否为编排关闭
 */
function isWorkflowDisabled(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'RICCON-WF-503';
}

/**
 * 流程列表页。
 *
 * 覆盖 AC-01/AC-19 的列表部分：我的流程/可运行（管理员额外可见全部）、草稿与版本摘要、
 * 空/加载/错误/无权限/禁用态、创建空白或从内置模板创建。页面不展示他人私有草稿；
 * 服务端已按可见性过滤，前端只渲染返回项。
 *
 * @param props API、会话读取与导航注入
 * @returns 流程列表界面
 */
export default function WorkflowListPage({
  api = defaultWorkflowApi,
  sessionReader = defaultApi,
  navigate: injectedNavigate,
  initialView = 'MINE',
}: WorkflowListPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const session = auth.session;
  const permissions = session?.permissions;
  const canEdit = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.edit);
  const canManage = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.manage);
  const canRead = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.read);

  const [view, setView] = useState<WorkflowView>(initialView);
  const [status, setStatus] = useState<WorkflowDefinitionStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(1);
  const [phase, setPhase] = useState<ListPhase>('loading');
  const [items, setItems] = useState<WorkflowDefinition[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createMode, setCreateMode] = useState<CreateMode>('BLANK');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [createForm] = Form.useForm<CreateFormValues>();
  const requestSeq = useRef(0);

  // 页面可能被独立挂载（测试或深链）；未恢复会话时沿用员工端统一恢复逻辑。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(sessionReader);
    }
  }, [auth.status, sessionReader]);

  /**
   * 拉取当前视图的流程列表。
   *
   * 使用递增序号丢弃过期响应，避免用户快速切换视图时旧请求覆盖新结果。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!session) {
      return;
    }
    if (!canRead) {
      setPhase('forbidden');
      return;
    }
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setPhase('loading');
    setError(null);
    try {
      const result = await api.listWorkflows({
        view,
        status: status === 'ALL' ? undefined : status,
        page,
        size: PAGE_SIZE,
      });
      if (requestSeq.current !== seq) {
        return;
      }
      setItems(result.items);
      setTotal(result.total);
      setPhase(result.items.length === 0 ? 'empty' : 'ready');
    } catch (caught) {
      if (requestSeq.current !== seq) {
        return;
      }
      if (isWorkflowDisabled(caught)) {
        setPhase('disabled');
        return;
      }
      if (caught instanceof ApiError && caught.status === 403) {
        setPhase('forbidden');
        return;
      }
      setError(describeApiError(caught, '流程列表加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, canRead, page, session, status, view]);

  useEffect(() => {
    if (!session) {
      return;
    }
    void load();
  }, [load, session]);

  /**
   * 切换视图或状态筛选时回到第一页。
   *
   * @param nextView 目标视图
   */
  const handleViewChange = (nextView: string | number): void => {
    setView(nextView as WorkflowView);
    setPage(1);
  };

  /**
   * 切换状态筛选。
   *
   * @param nextStatus 目标状态或 ALL
   */
  const handleStatusChange = (nextStatus: WorkflowDefinitionStatus | 'ALL'): void => {
    setStatus(nextStatus);
    setPage(1);
  };

  /**
   * 打开新建弹窗。
   *
   * @param mode 空白或从模板创建
   */
  const openCreate = (mode: CreateMode): void => {
    setCreateMode(mode);
    setCreateError(null);
    createForm.resetFields();
    setCreateOpen(true);
  };

  /**
   * 创建流程并跳转编辑器。
   *
   * 模板模式使用 V15 内置市场调研模板的固定 ID；服务端会复制为当前用户草稿，
   * 模板本身不被修改（AC-01/AC-10）。T14 引入模板列表后只替换入口数据源。
   */
  const handleCreate = async (): Promise<void> => {
    setCreateError(null);
    let values: CreateFormValues;
    try {
      values = await createForm.validateFields();
    } catch {
      return;
    }
    const name = values.name?.trim();
    const description = values.description?.trim();
    setCreating(true);
    try {
      const detail = await api.createWorkflow(
        createMode === 'TEMPLATE'
          ? {
              templateId: MARKET_RESEARCH_TEMPLATE_ID,
              ...(name ? { name } : {}),
              ...(description ? { description } : {}),
            }
          : {
              name: name ?? '',
              ...(description ? { description } : {}),
            },
      );
      setCreateOpen(false);
      navigate(`/workflows/${detail.id}/edit`);
    } catch (caught) {
      setCreateError(
        caught instanceof ApiError && caught.status === 403
          ? '你没有编排权限，请联系租户管理员申请。'
          : describeApiError(caught, '创建流程失败，请稍后重试。'),
      );
    } finally {
      setCreating(false);
    }
  };

  const viewOptions = [
    { label: '我的流程', value: 'MINE' },
    { label: '可运行', value: 'RUNNABLE' },
    ...(canManage ? [{ label: '全部流程', value: 'ALL' }] : []),
  ];

  const emptyText = canEdit
    ? '还没有流程。从模板创建，或新建空白流程。'
    : '当前没有可运行的流程。请联系租户管理员或流程所有者。';

  return (
    <div className="workflow-page" data-testid="workflow-list-page">
      <div className="workflow-page-heading">
        <Typography.Title level={1}>编排 Studio</Typography.Title>
        <Typography.Paragraph type="secondary">
          把检索、模型、MCP 工具与人工审批编排成可发布、可复用的流程。
        </Typography.Paragraph>
      </div>

      <Flex className="workflow-toolbar" wrap gap={8} align="center" justify="space-between">
        <Space wrap size={[8, 8]}>
          <Segmented
            aria-label="流程视图"
            value={view}
            options={viewOptions}
            onChange={handleViewChange}
          />
          <Select
            aria-label="状态筛选"
            value={status}
            style={{ minWidth: 140 }}
            options={[
              { value: 'ALL', label: '全部状态' },
              { value: 'ACTIVE', label: '启用' },
              { value: 'DISABLED', label: '已停用' },
            ]}
            onChange={handleStatusChange}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
        {canEdit ? (
          <Space wrap size={[8, 8]}>
            <Button onClick={() => openCreate('TEMPLATE')}>从模板创建</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate('BLANK')}>
              新建空白流程
            </Button>
          </Space>
        ) : null}
      </Flex>

      {!canEdit ? (
        <Alert
          className="workflow-permission-note"
          type="info"
          showIcon
          title="你没有编排权限，请联系租户管理员申请。"
        />
      ) : null}

      {phase === 'loading' ? (
        <div aria-busy="true" aria-label="正在加载流程列表">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      ) : null}

      {phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有访问权限"
          subTitle="你没有编排权限，请联系租户管理员申请。"
          extra={[
            <Button
              key="retry"
              aria-label="重试"
              autoInsertSpace={false}
              onClick={() => void load()}
            >
              重试
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'disabled' ? (
        <Result
          status="warning"
          title="编排功能已关闭"
          subTitle="编排功能已关闭，已有运行记录仍可查看。"
          extra={[
            <Button key="retry" type="primary" onClick={() => void load()}>
              重新加载
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'error' ? (
        <Result
          status="error"
          title="流程列表加载失败"
          subTitle={error ?? '请稍后重试。'}
          extra={[
            <Button
              key="retry"
              type="primary"
              aria-label="重试"
              autoInsertSpace={false}
              onClick={() => void load()}
            >
              重试
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'empty' ? (
        <div className="workflow-empty">
          <Empty description={emptyText} />
          {canEdit ? (
            <Space wrap>
              <Button onClick={() => openCreate('TEMPLATE')}>从模板创建</Button>
              <Button type="primary" onClick={() => openCreate('BLANK')}>
                新建空白流程
              </Button>
            </Space>
          ) : null}
        </div>
      ) : null}

      {phase === 'ready' ? (
        <>
          <ul className="workflow-list" data-testid="workflow-list" data-layout="cards">
            {items.map((workflow) => {
              const display = workflowDisplayStatus(workflow);
              return (
                <li className="workflow-list-item" key={workflow.id}>
                  <div className="workflow-list-main">
                    <Button
                      type="link"
                      className="workflow-list-name"
                      onClick={() => navigate(`/workflows/${workflow.id}/edit`)}
                    >
                      {workflow.name}
                    </Button>
                    <Space wrap size={[6, 6]}>
                      <WorkflowTag color={display.color}>{display.label}</WorkflowTag>
                      {workflow.publishedVersionNo == null ? (
                        <Tag>未发布</Tag>
                      ) : (
                        <WorkflowTag color="green">
                          已发布 v{workflow.publishedVersionNo}
                        </WorkflowTag>
                      )}
                    </Space>
                    <Typography.Text type="secondary" className="workflow-list-description">
                      {workflow.description || '暂无说明'}
                    </Typography.Text>
                    <Typography.Text type="secondary" className="workflow-list-meta">
                      {workflowVersionSummary(workflow)} · 更新于{' '}
                      {formatOffsetDateTime(workflow.updatedAt)}
                    </Typography.Text>
                  </div>
                  <div className="workflow-list-actions">
                    <Button onClick={() => navigate(`/workflows/${workflow.id}/edit`)}>
                      {canEdit ? '打开编辑' : '查看流程'}
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
          {total > PAGE_SIZE ? (
            <Pagination
              aria-label="流程列表分页"
              simple
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              onChange={setPage}
            />
          ) : null}
        </>
      ) : null}

      <Modal
        title={createMode === 'TEMPLATE' ? '从模板创建流程' : '新建空白流程'}
        open={createOpen}
        okText="创建并打开"
        cancelText="取消"
        confirmLoading={creating}
        destroyOnHidden
        onOk={() => void handleCreate()}
        onCancel={() => {
          if (!creating) {
            setCreateOpen(false);
          }
        }}
      >
        <Segmented
          block
          aria-label="创建方式"
          value={createMode}
          options={[
            { label: '空白流程', value: 'BLANK' },
            { label: '从模板创建', value: 'TEMPLATE' },
          ]}
          onChange={(value) => {
            setCreateMode(value as CreateMode);
            setCreateError(null);
          }}
        />
        {createMode === 'TEMPLATE' ? (
          <Alert
            className="workflow-template-note"
            type="info"
            showIcon
            title="内置模板：市场调研报告"
            description="将复制一份仅自己可见的草稿，模板本身不会被修改。"
          />
        ) : null}
        <Form form={createForm} layout="vertical" className="workflow-create-form">
          <Form.Item
            label={createMode === 'TEMPLATE' ? '流程名称（可选）' : '流程名称'}
            name="name"
            rules={[
              {
                validator: async (_, value: string | undefined) => {
                  if (createMode === 'BLANK' && !value?.trim()) {
                    throw new Error('请输入流程名称。');
                  }
                  if (value && value.trim().length > 200) {
                    throw new Error('流程名称不能超过 200 个字符。');
                  }
                },
              },
            ]}
          >
            <Input
              maxLength={200}
              placeholder={
                createMode === 'TEMPLATE' ? '留空则使用模板名称' : '例如：销售部市场调研'
              }
            />
          </Form.Item>
          <Form.Item label="说明" name="description">
            <Input.TextArea maxLength={1000} rows={3} placeholder="可选，最多 1000 个字符" />
          </Form.Item>
        </Form>
        {createError ? <Alert type="error" showIcon title={createError} /> : null}
      </Modal>
    </div>
  );
}
