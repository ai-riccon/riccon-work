import { CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Empty,
  Flex,
  Input,
  Pagination,
  Result,
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
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import {
  templateApi as defaultTemplateApi,
  type WorkflowTemplate,
  type WorkflowTemplateApi,
} from './template-api';
import './templates.css';

/** 列表加载阶段。 */
type ListPhase = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden';

/** 已应用的过滤条件。 */
interface TemplateFilters {
  name?: string;
  scenario?: string;
}

/** 页面可注入依赖，便于组件测试。 */
export interface WorkflowTemplatesPageProps {
  /** 模板 API；默认同源真实客户端。 */
  api?: WorkflowTemplateApi;
  /** 会话读取能力；默认复用员工端 ApiClient。 */
  sessionReader?: SessionReader;
  /** 导航；测试注入。 */
  navigate?: (to: string) => void;
}

/** 每页条数；列表页固定 20，符合合同分页上限。 */
const PAGE_SIZE = 20;

/**
 * 模板库页面。
 *
 * 覆盖 M06-AC-10 的模板可见与复制入口：列出启用的内置模板（名称、适用场景、内置标识、说明、版本与最近
 * 更新时间），有编排权限的用户可复制为仅自己可见的草稿并跳转编辑器；模板本身不被修改。M06 只展示内置
 * 模板，用户模板共享与升级提示由 M06b 提供（ADR-0024 §12）。
 *
 * 状态：加载、空、错误、无权限各自有中文说明与下一步；375 px 使用卡片布局，操作按钮换行占满宽度。
 *
 * @param props API、会话读取与导航注入
 * @returns 模板库界面
 */
export default function WorkflowTemplatesPage({
  api = defaultTemplateApi,
  sessionReader = defaultApi,
  navigate: injectedNavigate,
}: WorkflowTemplatesPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const { message } = App.useApp();
  const auth = useAuth();
  const session = auth.session;
  const permissions = session?.permissions;
  const canEdit = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.edit);
  const canRead = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.read);

  const [phase, setPhase] = useState<ListPhase>('loading');
  const [items, setItems] = useState<WorkflowTemplate[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [filters, setFilters] = useState<TemplateFilters>({});
  const [nameInput, setNameInput] = useState('');
  const [scenarioInput, setScenarioInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [copyingId, setCopyingId] = useState<string | null>(null);
  const [copyError, setCopyError] = useState<string | null>(null);
  const requestSeq = useRef(0);

  // 页面可能被独立挂载（测试或深链）；未恢复会话时沿用员工端统一恢复逻辑。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(sessionReader);
    }
  }, [auth.status, sessionReader]);

  /**
   * 拉取模板列表。
   *
   * 使用递增序号丢弃过期响应，避免用户快速查询或翻页时旧请求覆盖新结果。
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
      const result = await api.listTemplates({ ...filters, page, size: PAGE_SIZE });
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
      if (caught instanceof ApiError && (caught.status === 403 || caught.status === 401)) {
        setPhase('forbidden');
        return;
      }
      setError(describeApiError(caught, '模板库加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, canRead, filters, page, session]);

  useEffect(() => {
    if (!session) {
      return;
    }
    void load();
  }, [load, session]);

  /** 应用名称/场景过滤并回到第一页。 */
  const handleSearch = (): void => {
    const name = nameInput.trim();
    const scenario = scenarioInput.trim();
    setFilters({
      ...(name ? { name } : {}),
      ...(scenario ? { scenario } : {}),
    });
    setPage(1);
    setCopyError(null);
  };

  /** 清空过滤条件并回到第一页。 */
  const handleReset = (): void => {
    setNameInput('');
    setScenarioInput('');
    setFilters({});
    setPage(1);
    setCopyError(null);
  };

  /**
   * 复制模板为当前用户的草稿并跳转编辑器。
   *
   * 成功提示明确“模板本身不会被修改”，避免用户误以为在编辑模板；403 单独给出申请权限的下一步。
   *
   * @param template 目标模板
   */
  const handleCopy = async (template: WorkflowTemplate): Promise<void> => {
    setCopyError(null);
    setCopyingId(template.id);
    try {
      const draft = await api.copyTemplate(template.id);
      message.success(`已复制“${draft.name}”，模板本身不会被修改。`);
      navigate(`/workflows/${draft.id}/edit`);
    } catch (caught) {
      setCopyError(
        caught instanceof ApiError && caught.status === 403
          ? '你没有编排权限，无法复制模板，请联系租户管理员申请。'
          : describeApiError(caught, '复制模板失败，请稍后重试。'),
      );
    } finally {
      setCopyingId(null);
    }
  };

  return (
    <div className="workflow-templates-page" data-testid="workflow-templates-page">
      <div className="workflow-templates-heading">
        <Typography.Title level={1}>模板库</Typography.Title>
        <Typography.Paragraph type="secondary">
          从内置模板复制一份仅自己可见的草稿，模板本身不会被修改。复制后可继续修改节点、连线与参数。
        </Typography.Paragraph>
      </div>

      <Flex
        className="workflow-templates-toolbar"
        wrap
        gap={8}
        align="center"
        justify="space-between"
      >
        <Space wrap size={[8, 8]}>
          <Input
            aria-label="模板名称"
            placeholder="按名称搜索"
            value={nameInput}
            maxLength={200}
            style={{ width: 200, maxWidth: '100%' }}
            onChange={(event) => setNameInput(event.target.value)}
            onPressEnter={handleSearch}
          />
          <Input
            aria-label="适用场景"
            placeholder="按场景搜索"
            value={scenarioInput}
            maxLength={200}
            style={{ width: 200, maxWidth: '100%' }}
            onChange={(event) => setScenarioInput(event.target.value)}
            onPressEnter={handleSearch}
          />
          <Button type="primary" onClick={handleSearch}>
            查询
          </Button>
          <Button onClick={handleReset}>重置</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </Flex>

      {canRead && !canEdit ? (
        <Alert
          className="workflow-templates-permission-note"
          type="info"
          showIcon
          title="你没有编排权限，无法复制模板；请联系租户管理员申请。"
        />
      ) : null}

      {copyError ? (
        <Alert className="workflow-templates-copy-error" type="error" showIcon title={copyError} />
      ) : null}

      {phase === 'loading' ? (
        <div aria-busy="true" aria-label="正在加载模板列表">
          <Skeleton active paragraph={{ rows: 5 }} />
        </div>
      ) : null}

      {phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有访问权限"
          subTitle="你没有模板库的读取权限，请联系租户管理员申请。"
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

      {phase === 'error' ? (
        <Result
          status="error"
          title="模板库加载失败"
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
        <div className="workflow-templates-empty">
          <Empty description="暂无可用模板。内置模板由平台统一维护，请联系租户管理员。" />
          <Space wrap>
            <Button onClick={() => void load()}>刷新</Button>
            <Button onClick={handleReset}>清空筛选</Button>
          </Space>
        </div>
      ) : null}

      {phase === 'ready' ? (
        <>
          <ul className="workflow-templates-list" data-testid="workflow-template-list">
            {items.map((template) => (
              <li className="workflow-templates-item" key={template.id}>
                <div className="workflow-templates-main">
                  <Typography.Title level={2} className="workflow-templates-name">
                    {template.name}
                  </Typography.Title>
                  <Space wrap size={[6, 6]}>
                    <WorkflowTag color="blue">内置模板</WorkflowTag>
                    <Tag>{template.scenario}</Tag>
                  </Space>
                  <Typography.Text type="secondary" className="workflow-templates-description">
                    {template.description || '暂无说明'}
                  </Typography.Text>
                  <Typography.Text type="secondary" className="workflow-templates-meta">
                    {template.builtInVersion ? `内置版本 v${template.builtInVersion}` : '内置模板'}{' '}
                    · 更新于 {formatOffsetDateTime(template.updatedAt)}
                  </Typography.Text>
                </div>
                <div className="workflow-templates-actions">
                  <Button
                    type="primary"
                    icon={<CopyOutlined />}
                    aria-label={`复制模板：${template.name} 复制为草稿`}
                    title={canEdit ? undefined : '需要编排权限'}
                    disabled={!canEdit}
                    loading={copyingId === template.id}
                    onClick={() => void handleCopy(template)}
                  >
                    复制为草稿
                  </Button>
                </div>
              </li>
            ))}
          </ul>
          {total > PAGE_SIZE ? (
            <Pagination
              aria-label="模板列表分页"
              simple
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              onChange={setPage}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}
