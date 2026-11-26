import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Result, Skeleton, Space, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import { WorkflowTag } from './accessible-tag';
import {
  describeRuntimeLimitsDisabledReason,
  formatLimitBytes,
  formatLimitSeconds,
  retentionPolicyLabel,
} from './runtime-limits-format';
import {
  workflowRuntimeLimitsApi,
  type WorkflowRuntimeLimits,
  type WorkflowRuntimeLimitsApi,
} from './runtime-limits-api';
import './runtime-limits.css';

/** 运行限制面板加载阶段。 */
type LimitsPhase = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden';

/** 运行限制只读抽屉属性。 */
export interface WorkflowRuntimeLimitsDrawerProps {
  /** 是否打开；关闭后下一次打开会重新读取，避免展示过期部署配置。 */
  open: boolean;
  /** 关闭回调；由入口按钮控制。 */
  onClose: () => void;
  /** 运行限制 API；默认同源真实客户端，测试可注入。 */
  api?: WorkflowRuntimeLimitsApi;
}

/**
 * 运行限制只读抽屉（M06-AC-17）。
 *
 * 展示部署配置对当前租户生效的节点/步数/超时/并发/结果大小/保留限制与编排开关；
 * 租户只能查看，不提供任何编辑控件。加载/错误/无权限/编排关闭各有中文说明与下一步；
 * 375px 下抽屉占满视口宽度，字段换行且不产生横向滚动。
 *
 * 数据只在打开时读取，关闭时递增请求序号丢弃在途响应；失败后重试仍走同一 `load`，
 * 不把错误吞掉或静默展示空值。
 *
 * @param props 打开状态、关闭回调与可注入 API
 * @returns 右侧只读抽屉
 */
export function WorkflowRuntimeLimitsDrawer({
  open,
  onClose,
  api = workflowRuntimeLimitsApi,
}: WorkflowRuntimeLimitsDrawerProps) {
  const [phase, setPhase] = useState<LimitsPhase>('idle');
  const [limits, setLimits] = useState<WorkflowRuntimeLimits | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeq = useRef(0);

  /**
   * 读取当前租户的运行限制。
   *
   * 用递增序号丢弃过期响应；403 单独成态，避免把权限问题误报成网络故障。
   */
  const load = useCallback(async (): Promise<void> => {
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setPhase('loading');
    setError(null);
    try {
      const result = await api.getRuntimeLimits();
      if (requestSeq.current !== seq) {
        return;
      }
      setLimits(result);
      setPhase('ready');
    } catch (caught) {
      if (requestSeq.current !== seq) {
        return;
      }
      if (caught instanceof ApiError && caught.status === 403) {
        setPhase('forbidden');
        return;
      }
      setError(describeApiError(caught, '运行限制加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api]);

  // 打开时读取；关闭时递增序号并回到 idle，防止在途响应在下次打开前覆盖新状态。
  useEffect(() => {
    if (!open) {
      requestSeq.current += 1;
      setPhase('idle');
      return;
    }
    void load();
  }, [load, open]);

  const showLoading = open && (phase === 'idle' || phase === 'loading');

  return (
    <Drawer
      className="workflow-runtime-limits-drawer"
      title="运行限制（只读）"
      placement="right"
      open={open}
      size="min(480px, 100vw)"
      destroyOnHidden
      onClose={onClose}
    >
      <Typography.Paragraph type="secondary" className="workflow-runtime-limits-readonly">
        限制值由部署配置决定，租户只能查看，不能在此修改。如需调整，请联系平台运维提交部署配置变更。
      </Typography.Paragraph>

      {showLoading ? (
        <div aria-busy="true" aria-label="正在加载运行限制">
          <Skeleton active paragraph={{ rows: 10 }} />
        </div>
      ) : null}

      {phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有查看运行限制的权限"
          subTitle="查看运行限制需要 workflow.read 权限，请联系租户管理员申请。"
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

      {phase === 'error' ? (
        <Result
          status="error"
          title="运行限制加载失败"
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

      {phase === 'ready' && limits ? (
        <section
          className="workflow-runtime-limits"
          aria-label="运行限制明细"
          data-testid="workflow-runtime-limits"
        >
          {!limits.featureEnabled ? (
            <Alert
              className="workflow-runtime-limits-disabled"
              type="warning"
              showIcon
              title="编排功能已关闭"
              description={
                <span>
                  已有运行记录仍可查看；新运行、重试与审批决定暂不可用。
                  <br />
                  关闭原因：{describeRuntimeLimitsDisabledReason(limits.disabledReason)}
                </span>
              }
            />
          ) : null}

          <Descriptions
            className="workflow-runtime-limits-group"
            size="small"
            bordered
            column={1}
            title="流程与运行上限"
            items={[
              {
                key: 'status',
                label: '编排状态',
                children: (
                  <WorkflowTag color={limits.featureEnabled ? 'green' : 'red'}>
                    {limits.featureEnabled ? '已启用' : '已关闭'}
                  </WorkflowTag>
                ),
              },
              {
                key: 'maxNodes',
                label: '单流程节点上限',
                children: `${limits.maxNodes} 个节点`,
              },
              {
                key: 'maxSteps',
                label: '单运行步数上限',
                children: `${limits.maxSteps} 步`,
              },
              {
                key: 'runTimeoutSeconds',
                label: '运行执行超时',
                children: `${formatLimitSeconds(limits.runTimeoutSeconds)}（审批等待不计）`,
              },
              {
                key: 'resultMaxBytes',
                label: '单次运行结果上限',
                children: formatLimitBytes(limits.resultMaxBytes),
              },
            ]}
          />

          <Descriptions
            className="workflow-runtime-limits-group"
            size="small"
            bordered
            column={1}
            title="节点超时"
            items={[
              {
                key: 'modelTimeout',
                label: '模型节点超时',
                children: formatLimitSeconds(limits.nodeTimeoutSeconds.model),
              },
              {
                key: 'retrievalTimeout',
                label: '检索节点超时',
                children: formatLimitSeconds(limits.nodeTimeoutSeconds.retrieval),
              },
              {
                key: 'mcpTimeout',
                label: 'MCP 工具节点超时',
                children: formatLimitSeconds(limits.nodeTimeoutSeconds.mcp),
              },
            ]}
          />

          <Descriptions
            className="workflow-runtime-limits-group"
            size="small"
            bordered
            column={1}
            title="并发与保留"
            items={[
              {
                key: 'tenantConcurrency',
                label: '租户并发运行上限',
                children: `${limits.tenantConcurrency} 个运行（含等待审批）`,
              },
              {
                key: 'workflowConcurrency',
                label: '单流程并发上限',
                children: `${limits.workflowConcurrency} 个运行`,
              },
              {
                key: 'retentionDays',
                label: '运行记录保留',
                children: `${limits.retentionDays} 天（${retentionPolicyLabel(
                  limits.retentionPolicy,
                )}）`,
              },
              {
                key: 'generatedAt',
                label: '限制生成时间',
                children: formatOffsetDateTime(limits.generatedAt),
              },
            ]}
          />

          <Space wrap className="workflow-runtime-limits-footer">
            <Typography.Text type="secondary">
              本页为只读视图，不提供修改入口；运维调整部署配置后，新请求按新限制生效。
            </Typography.Text>
            <Button
              size="small"
              icon={<ReloadOutlined aria-hidden="true" />}
              aria-label="刷新运行限制"
              onClick={() => void load()}
            >
              刷新
            </Button>
          </Space>
        </section>
      ) : null}
    </Drawer>
  );
}
