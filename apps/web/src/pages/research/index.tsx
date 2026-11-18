import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Empty,
  Flex,
  Form,
  Input,
  Result,
  Select,
  Skeleton,
  Space,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import type { RicconApi } from '@/services/api/client';
import type { ResearchTask } from '@/services/api/types';
import { authStore, useAuth } from '@/services/auth/auth-store';
import {
  formatDateTime,
  researchStatusColor,
  researchStatusLabel,
  STAGE_LABELS,
} from '@/services/research/format';
import { useResearchWorkspace } from './use-research-workspace';
import { StatusTag } from './components/status-tag';
import './research.css';

/** 报告任务列表页可注入依赖，便于组件测试。 */
export interface ResearchListPageProps {
  api?: RicconApi;
  navigate?: (to: string, options?: { replace?: boolean }) => void;
}

/** 创建任务的表单值。 */
interface CreateTaskValues {
  topic: string;
  mcpToolId: string;
}

/**
 * 生成创建任务的幂等键。
 *
 * 同一次提交失败后再次点击会复用旧键，避免服务端已创建但响应丢失时产生重复任务。
 *
 * @returns 幂等键
 */
function createIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `research-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 校验调研主题的去除空白后的长度。
 *
 * @param value 表单值
 * @returns 错误文案；通过时返回 null
 */
function topicError(value: unknown): string | null {
  const topic = typeof value === 'string' ? value.trim() : '';
  if (topic.length < 4) {
    return '调研主题至少 4 个字符。';
  }
  if (topic.length > 500) {
    return '调研主题不能超过 500 个字符。';
  }
  return null;
}

/**
 * J2 报告任务列表与创建页。
 *
 * 页面只调用 apps/api：工作空间、任务列表与授权工具均来自契约接口；
 * MCP 调用与审批由任务详情页负责，这里不直接接触外部工具。
 *
 * @param props API 与导航注入
 * @returns 报告任务列表界面
 */
export default function ResearchListPage({
  api = defaultApi,
  navigate: injectedNavigate,
}: ResearchListPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const controller = useResearchWorkspace(api, auth.session);
  const [form] = Form.useForm<CreateTaskValues>();
  const createKeyRef = useRef<string | null>(null);
  const lastSubmitSignatureRef = useRef<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // 与 J1 相同：首次进入时恢复会话；匿名由布局跳转登录。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(api);
    }
  }, [api, auth.status]);

  /**
   * 提交创建表单。
   *
   * @param values 主题与工具
   */
  const handleFinish = async (values: CreateTaskValues): Promise<void> => {
    const topic = values.topic.trim();
    // 幂等键与请求内容绑定：同一内容失败重试复用旧键；内容变化则换新键，
    // 避免服务端把新主题误判为旧请求而返回旧任务。
    const signature = `${topic}\u0000${values.mcpToolId}`;
    if (!createKeyRef.current || lastSubmitSignatureRef.current !== signature) {
      createKeyRef.current = createIdempotencyKey();
      lastSubmitSignatureRef.current = signature;
    }
    const key = createKeyRef.current;
    setSubmitting(true);
    try {
      const task = await controller.createTask(topic, values.mcpToolId, key);
      if (task) {
        createKeyRef.current = null;
        lastSubmitSignatureRef.current = null;
        form.resetFields();
        navigate(`/research/${task.id}`);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const pageTitle = (
    <div className="research-page-heading">
      <Typography.Title level={1} style={{ margin: 0 }}>
        市场调研报告
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
        输入主题、选择一个已授权的只读数据工具；任务会在调用外部工具前请求审批，报告生成后保存在工作空间。
      </Typography.Paragraph>
    </div>
  );

  if (controller.phase === 'loading') {
    return (
      <div className="research-page">
        {pageTitle}
        <div aria-busy="true" aria-label="正在加载报告任务">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </div>
    );
  }

  if (controller.phase === 'forbidden') {
    return (
      <div className="research-page">
        {pageTitle}
        <Result
          status="403"
          title="没有访问权限"
          subTitle={controller.error ?? '当前账号无权访问报告任务，请联系管理员。'}
          extra={[
            <Button key="retry" onClick={() => void controller.initialize()} type="primary">
              重试
            </Button>,
            <Button key="back" onClick={() => navigate('/chat')}>
              返回工作空间
            </Button>,
          ]}
        />
      </div>
    );
  }

  if (controller.phase === 'error') {
    return (
      <div className="research-page">
        {pageTitle}
        <Result
          status="error"
          title="报告任务加载失败"
          subTitle={controller.error ?? '请检查网络后重试。'}
          extra={[
            <Button key="retry" onClick={() => void controller.initialize()} type="primary">
              重试
            </Button>,
            <Button key="back" onClick={() => navigate('/chat')}>
              返回工作空间
            </Button>,
          ]}
        />
      </div>
    );
  }

  if (controller.phase === 'empty') {
    return (
      <div className="research-page">
        {pageTitle}
        <Result
          status="warning"
          title="暂无可用工作空间"
          subTitle="当前租户还没有分配个人或团队工作空间，请联系管理员开通。"
          extra={[
            <Button key="retry" onClick={() => void controller.initialize()} type="primary">
              重试
            </Button>,
            <Button key="back" onClick={() => navigate('/chat')}>
              返回工作空间
            </Button>,
          ]}
        />
      </div>
    );
  }

  return (
    <div className="research-page">
      {pageTitle}

      {controller.actionError && (
        <Alert
          showIcon
          closable
          description={controller.actionError}
          onClose={controller.dismissActionError}
          style={{ marginBottom: 16 }}
          title="操作未完成"
          type="error"
        />
      )}

      {controller.toolsError && (
        <Alert
          showIcon
          action={
            <Button
              icon={<ReloadOutlined />}
              onClick={() => void controller.initialize()}
              size="small"
            >
              重新加载
            </Button>
          }
          description={controller.toolsError}
          style={{ marginBottom: 16 }}
          title={
            controller.toolsErrorKind === 'forbidden' ? '无权使用数据工具' : '授权工具加载失败'
          }
          type="warning"
        />
      )}

      <div className="research-list-layout">
        <Card className="research-create-card" title="新建报告任务">
          <Form<CreateTaskValues>
            form={form}
            layout="vertical"
            onFinish={(values) => {
              void handleFinish(values);
            }}
            requiredMark="optional"
          >
            <Form.Item
              label="调研主题"
              name="topic"
              rules={[
                { required: true, message: '请输入调研主题。' },
                {
                  validator: async (_rule, value: unknown) => {
                    const message = topicError(value);
                    if (message) {
                      throw new Error(message);
                    }
                  },
                },
              ]}
            >
              <Input.TextArea
                autoSize={{ minRows: 3, maxRows: 6 }}
                maxLength={500}
                placeholder="例如：2026 年中国企业协同办公市场规模与竞争格局"
                showCount
              />
            </Form.Item>

            <Form.Item
              extra="M02 只授权只读工具；工具调用前会请求人工审批。"
              label="数据工具"
              name="mcpToolId"
              rules={[{ required: true, message: '请选择数据工具。' }]}
            >
              <Select
                aria-label="数据工具"
                disabled={controller.toolsLoading || controller.tools.length === 0}
                loading={controller.toolsLoading}
                notFoundContent={controller.toolsLoading ? '正在加载工具…' : '没有已授权的只读工具'}
                options={controller.tools.map((tool) => ({
                  value: tool.id,
                  label: `${tool.name} · ${tool.riskLevel === 'READ' ? '只读' : '写入'}${
                    tool.requiresApproval ? ' · 需审批' : ''
                  }`,
                }))}
                placeholder="选择一个已授权的只读工具"
              />
            </Form.Item>

            {!controller.toolsLoading && controller.disabledTools.length > 0 && (
              <Typography.Paragraph style={{ marginTop: -8 }} type="secondary">
                有 {controller.disabledTools.length}{' '}
                个已授权工具当前被禁用，未列入选择；请联系管理员确认工具状态。
              </Typography.Paragraph>
            )}

            {!controller.toolsLoading &&
              controller.tools.length === 0 &&
              !controller.toolsError && (
                <Alert
                  showIcon
                  description="当前租户没有可用的只读 MCP 工具，暂时无法创建报告任务。请联系管理员授权。"
                  style={{ marginBottom: 16 }}
                  title="没有可用工具"
                  type="warning"
                />
              )}

            <Button
              disabled={controller.tools.length === 0 || controller.toolsLoading}
              htmlType="submit"
              icon={<PlusOutlined />}
              loading={controller.creating || submitting}
              type="primary"
            >
              创建报告任务
            </Button>
          </Form>
        </Card>

        <Card
          className="research-list-card"
          extra={
            <Button
              icon={<ReloadOutlined />}
              loading={controller.tasksLoading}
              onClick={() => void controller.refreshTasks()}
              size="small"
            >
              刷新
            </Button>
          }
          title="我的报告任务"
        >
          {controller.tasksLoading && controller.tasks.length === 0 ? (
            <div aria-busy="true" aria-label="正在加载任务列表">
              <Skeleton active paragraph={{ rows: 4 }} title={false} />
            </div>
          ) : controller.tasks.length === 0 ? (
            <Empty
              description="还没有报告任务；在左侧输入主题创建第一个任务。"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          ) : (
            <ul aria-label="报告任务列表" className="research-task-list">
              {controller.tasks.map((task) => (
                <li key={task.id}>
                  <TaskRow task={task} onOpen={() => navigate(`/research/${task.id}`)} />
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}

/**
 * 任务列表行：主题、状态、当前阶段与创建时间。
 *
 * @param props 任务与打开回调
 * @returns 列表项内容
 */
function TaskRow({ task, onOpen }: { task: ResearchTask; onOpen: () => void }) {
  return (
    <div className="research-task-row">
      <div className="research-task-main">
        <Button className="research-task-link" onClick={onOpen} type="link">
          {task.topic}
        </Button>
        <Space size={[8, 4]} wrap>
          <StatusTag tone={researchStatusColor(task.status)}>
            {researchStatusLabel(task.status)}
          </StatusTag>
          {task.currentStage && (
            <Typography.Text type="secondary">
              当前阶段：{STAGE_LABELS[task.currentStage]}
            </Typography.Text>
          )}
          <Typography.Text type="secondary">
            创建于 {formatDateTime(task.createdAt)}
          </Typography.Text>
        </Space>
        {task.failureMessage && (
          <Typography.Text type="danger">{task.failureMessage}</Typography.Text>
        )}
      </div>
      <Flex align="center" gap={8}>
        <Button onClick={onOpen}>打开任务</Button>
      </Flex>
    </div>
  );
}
