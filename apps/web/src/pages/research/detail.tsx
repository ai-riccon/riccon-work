import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from '@umijs/max';
import { Alert, Button, Result, Skeleton, Space, Typography } from 'antd';
import { useCallback, useMemo, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import type { RicconApi } from '@/services/api/client';
import type { ApprovalDecision } from '@/services/api/types';
import { useAuth } from '@/services/auth/auth-store';
import {
  describeResearchFailure,
  formatDateTime,
  formatShortId,
  isTerminalResearchStatus,
  researchStatusColor,
  researchStatusLabel,
  STAGE_LABELS,
} from '@/services/research/format';
import { ArtifactPanel } from './components/artifact-panel';
import { StageTimeline } from './components/stage-timeline';
import { StatusTag } from './components/status-tag';
import { useResearchTask } from './use-research-task';
import './research.css';

/** 报告任务详情页可注入依赖，便于组件测试。 */
export interface ResearchDetailPageProps {
  api?: RicconApi;
  navigate?: (to: string, options?: { replace?: boolean }) => void;
  /** 路由参数；测试可直接传入，避免启动 Umi 路由。 */
  taskId?: string;
  pollIntervalMs?: number;
}

/**
 * J2 报告任务详情页。
 *
 * 责任边界：展示阶段时间线、审批、MCP 调用摘要与报告产物；
 * 所有状态以服务端详情为准，审批/重试后重新读取，不在前端伪造终态。
 *
 * @param props API、导航、任务 ID 与轮询间隔注入
 * @returns 报告任务详情界面
 */
export default function ResearchDetailPage({
  api = defaultApi,
  navigate: injectedNavigate,
  taskId: injectedTaskId,
  pollIntervalMs,
}: ResearchDetailPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const params = useParams();
  const taskId = injectedTaskId ?? params.researchTaskId ?? '';
  const auth = useAuth();
  const controller = useResearchTask(api, taskId, pollIntervalMs);
  const [highlightedAnchor, setHighlightedAnchor] = useState<string | null>(null);
  const [locateError, setLocateError] = useState<string | null>(null);

  const failure = useMemo(
    () => (controller.detail ? describeResearchFailure(controller.detail) : null),
    [controller.detail],
  );

  /**
   * 把引用定位到时间线锚点并高亮。
   *
   * 目标不存在时提示而不是静默跳转，避免用户误以为已经核对过来源。
   *
   * @param anchorId 时间线锚点
   */
  const handleLocate = useCallback((anchorId: string): void => {
    setLocateError(null);
    setHighlightedAnchor(anchorId);
    const target = document.getElementById(anchorId);
    if (!target) {
      setLocateError('未找到对应的时间线记录，可能已被服务端清理。');
      return;
    }
    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, []);

  /**
   * 滚动到报告产物。
   */
  const handleViewArtifact = useCallback((): void => {
    const target = document.getElementById('research-artifact-title');
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, []);

  /**
   * 提交审批决定。
   *
   * @param approvalId 审批 ID
   * @param decision 决定
   * @param comment 可选备注
   */
  const handleDecide = useCallback(
    (approvalId: string, decision: ApprovalDecision, comment?: string): void => {
      void controller.decide(approvalId, decision, comment);
    },
    [controller],
  );

  const backButton = (
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/research/tasks')}>
      返回报告任务列表
    </Button>
  );

  if (controller.phase === 'loading') {
    return (
      <div className="research-page">
        {backButton}
        <div aria-busy="true" aria-label="正在加载报告任务详情" style={{ marginTop: 16 }}>
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      </div>
    );
  }

  if (controller.phase === 'forbidden') {
    return (
      <div className="research-page">
        {backButton}
        <Result
          status="403"
          title="没有访问权限"
          subTitle={controller.error ?? '当前账号无权查看该报告任务。'}
          extra={[
            <Button key="retry" onClick={() => void controller.reload()} type="primary">
              重试
            </Button>,
            backButton,
          ]}
        />
      </div>
    );
  }

  if (controller.phase === 'notFound') {
    return (
      <div className="research-page">
        {backButton}
        <Result
          status="404"
          title="报告任务不存在"
          subTitle={controller.error ?? '报告任务不存在或已被删除。'}
          extra={backButton}
        />
      </div>
    );
  }

  if (controller.phase === 'error' || !controller.detail) {
    return (
      <div className="research-page">
        {backButton}
        <Result
          status="error"
          title="报告任务加载失败"
          subTitle={controller.error ?? '请检查网络后重试。'}
          extra={[
            <Button key="retry" onClick={() => void controller.reload()} type="primary">
              重试
            </Button>,
            backButton,
          ]}
        />
      </div>
    );
  }

  const detail = controller.detail;
  const currentUser = auth.session
    ? { id: auth.session.user.id, displayName: auth.session.user.displayName }
    : null;
  const retryStage = failure?.stage === 'GENERATION' ? 'GENERATION' : 'TOOL';
  const terminal = isTerminalResearchStatus(detail.status);
  // 终态任务的 currentStage 可能尚未收尾；不再展示“当前阶段”，避免误读为仍在运行。
  const activeStage = terminal ? null : detail.currentStage;
  const artifactContentUrl = detail.artifact?.currentVersionDetail
    ? api.getArtifactContentUrl(detail.artifact.id, detail.artifact.currentVersionDetail.version)
    : null;

  return (
    <div className="research-page">
      {backButton}

      <header className="research-detail-header">
        <Typography.Title level={1} style={{ margin: 0 }}>
          {detail.topic}
        </Typography.Title>
        <Space size={[8, 8]} wrap>
          <StatusTag tone={researchStatusColor(detail.status)}>
            {researchStatusLabel(detail.status)}
          </StatusTag>
          {activeStage && (
            <Typography.Text type="secondary">
              当前阶段：{STAGE_LABELS[activeStage]}
            </Typography.Text>
          )}
          <Typography.Text type="secondary">
            任务 {formatShortId(detail.id)} · 创建于 {formatDateTime(detail.createdAt)}
          </Typography.Text>
          {detail.finishedAt && (
            <Typography.Text type="secondary">
              结束于 {formatDateTime(detail.finishedAt)}
            </Typography.Text>
          )}
        </Space>
      </header>

      <div aria-live="polite" className="research-sr-only">
        {`任务状态：${researchStatusLabel(detail.status)}${
          activeStage ? `；当前阶段：${STAGE_LABELS[activeStage]}` : ''
        }`}
      </div>

      {controller.actionError && (
        <Alert
          showIcon
          closable
          description={controller.actionError}
          onClose={controller.dismissActionError}
          style={{ margin: '16px 0' }}
          title="操作未完成"
          type="error"
        />
      )}

      {locateError && (
        <Alert
          showIcon
          closable
          description={locateError}
          onClose={() => setLocateError(null)}
          style={{ margin: '16px 0' }}
          title="无法定位"
          type="warning"
        />
      )}

      {detail.status === 'WAITING_APPROVAL' && (
        <Alert
          showIcon
          description="批准后才会调用外部 MCP 工具并生成报告；拒绝将终止任务且不产生报告。请在下方“审批”阶段处理。"
          style={{ margin: '16px 0' }}
          title="任务正在等待审批"
          type="warning"
        />
      )}

      {detail.status === 'FAILED' && failure && (
        <Alert
          action={
            <Space wrap>
              {failure.retryable ? (
                <Button
                  loading={controller.retrying}
                  onClick={() => void controller.retry(retryStage)}
                  type="primary"
                >
                  {retryStage === 'GENERATION' ? '重试生成阶段' : '重试工具阶段'}
                </Button>
              ) : (
                <Button onClick={() => navigate('/research/tasks')} type="primary">
                  调整主题重新创建
                </Button>
              )}
              <Button icon={<ReloadOutlined />} onClick={() => void controller.reload()}>
                刷新状态
              </Button>
            </Space>
          }
          description={`${failure.message}${detail.failureCode ? ` 错误码：${detail.failureCode}` : ''}`}
          showIcon
          style={{ margin: '16px 0' }}
          title="任务失败"
          type="error"
        />
      )}

      {detail.status === 'CANCELLED' && (
        <Alert
          showIcon
          description={detail.failureMessage || '审批被拒绝或任务已取消，任务已终止，未生成报告。'}
          style={{ margin: '16px 0' }}
          title="任务已终止"
          type="warning"
        />
      )}

      {detail.status === 'SUCCEEDED' && (
        <Alert
          action={
            <Button onClick={handleViewArtifact} type="primary">
              查看报告产物
            </Button>
          }
          showIcon
          style={{ margin: '16px 0' }}
          title="报告已生成"
          type="success"
        />
      )}

      <StageTimeline
        currentUser={currentUser}
        decidingApprovalId={controller.decidingApprovalId}
        detail={detail}
        highlightedAnchor={highlightedAnchor}
        onDecide={handleDecide}
        onViewArtifact={handleViewArtifact}
      />

      <ArtifactPanel
        artifactContentUrl={artifactContentUrl}
        body={controller.artifactBody}
        bodyError={controller.artifactBodyError}
        bodyLoading={controller.artifactBodyLoading}
        detail={detail}
        highlightedAnchor={highlightedAnchor}
        onLocate={handleLocate}
        onReloadBody={() => void controller.loadArtifactBody()}
      />
    </div>
  );
}
