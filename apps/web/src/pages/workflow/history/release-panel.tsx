import {
  CloudUploadOutlined,
  EditOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Result,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import type { Department, SessionInfo } from '@/services/api/types';
import { formatShortId } from '../approvals/format';
import {
  hasWorkflowPermission,
  runScopeLabel,
  workflowDisplayStatus,
  WORKFLOW_PERMISSIONS,
} from '../list/format';
import type {
  WorkflowDefinition,
  WorkflowDefinitionDetail,
  WorkflowRunScope,
  WorkflowVersion,
} from '../list/workflow-api';
import type { WorkflowHistoryApi } from './history-api';
import {
  buildScopeOptions,
  describeCreateRunFailure,
  describePublishFailure,
  describeStatusFailure,
  describeVersionScope,
  evaluatePublishScope,
  isWorkflowUnavailable,
  parseRunInputs,
  PUBLISH_IDEMPOTENT_MESSAGE,
  PUBLISH_READONLY_MESSAGE,
  versionOptionLabel,
  WORKFLOW_UNAVAILABLE_MESSAGE,
  type DepartmentsState,
} from './format';

/** 发布面板阶段。 */
type ReleasePhase = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden' | 'notFound' | 'disabled';

/** 版本列表每页条数；使用契约上限 100，保证常规版本量下旧版本都能在选择器中运行。 */
const VERSION_PAGE_SIZE = 100;

/** 发布面板属性。 */
export interface ReleasePanelProps {
  /** 运行历史/发布 API。 */
  api: WorkflowHistoryApi;
  /** 当前会话；用于发布权限与部门负责人判定。 */
  session: SessionInfo;
  /** 流程下拉候选；页面统一加载。 */
  workflowOptions: WorkflowDefinition[];
  /** 流程候选加载中。 */
  workflowOptionsLoading?: boolean;
  /** 流程候选加载失败说明。 */
  workflowOptionsError?: string | null;
  /** 重新加载流程候选。 */
  onReloadOptions?: () => void;
  /** 深链初始流程。 */
  initialWorkflowId?: string;
  /** 打开流程编辑器。 */
  onEditWorkflow: (workflowId: string) => void;
  /** 打开 T12a 运行详情。 */
  onOpenDetail: (runId: string) => void;
  /** 返回编排 Studio。 */
  onBackToStudio: () => void;
  /** 发布/停用/启用后通知页面刷新流程候选。 */
  onWorkflowUpdated?: () => void;
}

/**
 * 发布与版本面板。
 *
 * 覆盖 M06-AC-03/19 的发布部分：
 * - 展示当前草稿/发布版本、版本列表（版本号、发布人、发布时间、节点数与运行范围）；
 * - 发布当前草稿并按角色限制运行范围；400/409/503 有中文说明；
 * - 旧版本仍可用于创建运行（除非流程停用），运行绑定所选版本；
 * - 停用/启用只对所有者或租户管理员开放，成功后刷新详情与版本；
 * - 重新打开 DEPARTMENT 范围流程时保留最近范围；部门目录未加载/失败不静默回退，
 *   发布前二次确认；租户管理员不能选择本部门（关闭 REV-TASK-0230-001 F-001/F-005）。
 *
 * 权限：读版本需要 `workflow.read`；发布/停用需要 `workflow.publish`；运行需要 `workflow.run`。
 * 服务端仍按所有者/管理员与发布范围做最终判定，前端隐藏入口不代替鉴权。
 *
 * @param props API、会话、流程候选与导航回调
 * @returns 发布与版本卡片
 */
export function ReleasePanel({
  api,
  session,
  workflowOptions,
  workflowOptionsLoading = false,
  workflowOptionsError = null,
  onReloadOptions,
  initialWorkflowId,
  onEditWorkflow,
  onOpenDetail,
  onBackToStudio,
  onWorkflowUpdated,
}: ReleasePanelProps) {
  const { message } = App.useApp();
  const canPublishPermission = hasWorkflowPermission(
    session.permissions,
    WORKFLOW_PERMISSIONS.publish,
  );
  const canRunPermission = hasWorkflowPermission(session.permissions, WORKFLOW_PERMISSIONS.run);
  const tenantAdmin = hasWorkflowPermission(session.permissions, WORKFLOW_PERMISSIONS.manage);

  const [selectedWorkflowId, setSelectedWorkflowId] = useState<string | undefined>(
    initialWorkflowId,
  );
  const [phase, setPhase] = useState<ReleasePhase>('idle');
  const [detail, setDetail] = useState<WorkflowDefinitionDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [versions, setVersions] = useState<WorkflowVersion[]>([]);
  const [versionsTotal, setVersionsTotal] = useState(0);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const detailSeq = useRef(0);
  const versionsSeq = useRef(0);

  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentsState, setDepartmentsState] = useState<DepartmentsState>('idle');
  const [departmentsError, setDepartmentsError] = useState<string | null>(null);

  const [runScope, setRunScope] = useState<WorkflowRunScope>('ONLY_SELF');
  const [departmentId, setDepartmentId] = useState<string | undefined>(undefined);
  const scopeInitializedFor = useRef<string | null>(null);

  const [publishOpen, setPublishOpen] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);

  const [statusAction, setStatusAction] = useState<'disable' | 'enable' | null>(null);
  const [statusBusy, setStatusBusy] = useState(false);
  const [statusError, setStatusError] = useState<string | null>(null);

  const [runOpen, setRunOpen] = useState(false);
  const [runVersionId, setRunVersionId] = useState<string | undefined>(undefined);
  const [runInputs, setRunInputs] = useState('');
  const [runBusy, setRunBusy] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  // 深链参数变化时切换到目标流程。
  useEffect(() => {
    if (initialWorkflowId) {
      setSelectedWorkflowId(initialWorkflowId);
    }
  }, [initialWorkflowId]);

  /**
   * 加载部门目录。
   *
   * 只有具备发布权限且非租户管理员时才需要：租户管理员不能发布本部门，普通发布者需要
   * 确认自己负责的启用部门。失败时保留 failed 状态与错误说明，页面禁用必然非法的范围，
   * 不把空数组当成“没有部门”静默忽略（关闭 REV-TASK-0230-001 F-005）。
   */
  const loadDepartments = useCallback(async (): Promise<void> => {
    if (!canPublishPermission || tenantAdmin) {
      return;
    }
    setDepartmentsState('loading');
    setDepartmentsError(null);
    try {
      const page = await api.listDepartments();
      setDepartments(page.items);
      setDepartmentsState('loaded');
    } catch (caught) {
      setDepartments([]);
      setDepartmentsError(
        caught instanceof ApiError && caught.status === 403
          ? '没有部门读取权限，无法确认你负责的启用部门；可先发布“仅自己”范围，或联系租户管理员补充权限。'
          : describeApiError(caught, '部门目录加载失败，请重试；你也可以先发布“仅自己”范围。'),
      );
      setDepartmentsState('failed');
    }
  }, [api, canPublishPermission, tenantAdmin]);

  useEffect(() => {
    void loadDepartments();
  }, [loadDepartments]);

  /**
   * 读取流程详情。
   *
   * 首次读取某个流程时按最近发布版本初始化范围；后续刷新（发布/停用后）不覆盖用户当前选择。
   * 部门目录可能仍在异步加载，这里只设置范围，不做“目录为空就回退 ONLY_SELF”的兜底，
   * 保持最近发布范围并在发布前提示（关闭 REV-TASK-0230-001 F-001）。
   */
  const loadDetail = useCallback(async (): Promise<void> => {
    if (!selectedWorkflowId) {
      return;
    }
    const seq = detailSeq.current + 1;
    detailSeq.current = seq;
    setPhase('loading');
    setDetailError(null);
    try {
      const loaded = await api.getWorkflow(selectedWorkflowId);
      if (detailSeq.current !== seq) {
        return;
      }
      setDetail(loaded);
      if (scopeInitializedFor.current !== selectedWorkflowId) {
        setRunScope(loaded.latestVersion?.runScope ?? 'ONLY_SELF');
        setDepartmentId(loaded.latestVersion?.departmentId ?? undefined);
        scopeInitializedFor.current = selectedWorkflowId;
      }
      setPhase('ready');
    } catch (caught) {
      if (detailSeq.current !== seq) {
        return;
      }
      if (caught instanceof ApiError && caught.status === 403) {
        setPhase('forbidden');
        return;
      }
      if (caught instanceof ApiError && caught.status === 404) {
        setPhase('notFound');
        return;
      }
      if (isWorkflowUnavailable(caught)) {
        setPhase('disabled');
        return;
      }
      setDetailError(describeApiError(caught, '流程详情加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, selectedWorkflowId]);

  useEffect(() => {
    scopeInitializedFor.current = null;
    setDetail(null);
    setDetailError(null);
    setPhase(selectedWorkflowId ? 'loading' : 'idle');
    setVersions([]);
    setVersionsTotal(0);
    setVersionsError(null);
    void loadDetail();
  }, [loadDetail, selectedWorkflowId]);

  /**
   * 读取版本列表。
   *
   * 版本读取失败不阻断流程详情与发布表单；在版本区单独提示并可重试。
   */
  const loadVersions = useCallback(async (): Promise<void> => {
    if (!selectedWorkflowId) {
      return;
    }
    const seq = versionsSeq.current + 1;
    versionsSeq.current = seq;
    setVersionsLoading(true);
    setVersionsError(null);
    try {
      const page = await api.listVersions(selectedWorkflowId, {
        page: 1,
        size: VERSION_PAGE_SIZE,
      });
      if (versionsSeq.current !== seq) {
        return;
      }
      setVersions(page.items);
      setVersionsTotal(page.total);
    } catch (caught) {
      if (versionsSeq.current !== seq) {
        return;
      }
      setVersionsError(describeApiError(caught, '版本列表加载失败，请稍后重试。'));
    } finally {
      if (versionsSeq.current === seq) {
        setVersionsLoading(false);
      }
    }
  }, [api, selectedWorkflowId]);

  useEffect(() => {
    void loadVersions();
  }, [loadVersions]);

  const managedDepartments = useMemo(
    () =>
      departments.filter(
        (department) =>
          department.status === 'ACTIVE' && department.managerUserId === session.user.id,
      ),
    [departments, session.user.id],
  );

  // 保留最近发布版本的部门选项：即使目录未返回该部门，也要能展示并允许用户确认保持。
  const departmentOptions = useMemo(() => {
    const options = managedDepartments.map((department) => ({
      value: department.id,
      label: department.name,
    }));
    if (
      departmentId &&
      !options.some((option) => option.value === departmentId) &&
      detail?.latestVersion?.departmentId === departmentId
    ) {
      const known = departments.find((department) => department.id === departmentId);
      options.push({
        value: departmentId,
        label: known?.name ?? `${formatShortId(departmentId, 12)}（最近发布版本）`,
      });
    }
    return options;
  }, [departmentId, departments, detail?.latestVersion?.departmentId, managedDepartments]);

  const scopeOptions = buildScopeOptions({
    tenantAdmin,
    departmentsState,
    managedDepartmentCount: managedDepartments.length,
  });

  const scopeEvaluation = evaluatePublishScope({
    tenantAdmin,
    runScope,
    departmentId,
    departmentsState,
    managedDepartmentIds: managedDepartments.map((department) => department.id),
    latestVersionScope: detail?.latestVersion?.runScope ?? null,
    latestVersionDepartmentId: detail?.latestVersion?.departmentId ?? null,
  });

  const currentScopeOption = scopeOptions.find((option) => option.value === runScope);
  const canSubmitPublish = detail?.canPublish === true && scopeEvaluation.allowed && !publishing;

  /**
   * 切换发布范围。
   *
   * 离开本部门时清空部门；进入本部门时优先选择最近发布版本的部门，否则选择第一个负责部门。
   *
   * @param next 目标范围
   */
  const handleScopeChange = (next: WorkflowRunScope): void => {
    setRunScope(next);
    setPublishError(null);
    if (next !== 'DEPARTMENT') {
      setDepartmentId(undefined);
      return;
    }
    if (!departmentId) {
      const latestDepartmentId = detail?.latestVersion?.departmentId ?? undefined;
      setDepartmentId(latestDepartmentId ?? managedDepartments[0]?.id);
    }
  };

  /**
   * 打开发布确认弹窗。
   *
   * 弹窗是发布前的二次确认：展示当前草稿、目标范围、部门与范围告警；阻断状态不打开提交。
   */
  const openPublish = (): void => {
    if (!detail || !scopeEvaluation.allowed) {
      return;
    }
    setPublishError(null);
    setPublishOpen(true);
  };

  /**
   * 提交发布。
   *
   * 成功提示版本号与范围并刷新详情/版本/流程候选；失败保留弹窗并显示中文下一步。
   */
  const handlePublish = async (): Promise<void> => {
    if (!selectedWorkflowId || !scopeEvaluation.allowed) {
      return;
    }
    setPublishing(true);
    setPublishError(null);
    try {
      const version = await api.publishWorkflow(selectedWorkflowId, {
        runScope,
        departmentId: runScope === 'DEPARTMENT' ? departmentId : undefined,
      });
      const existed = detail?.latestVersion?.id === version.id;
      setPublishOpen(false);
      if (existed) {
        message.info(PUBLISH_IDEMPOTENT_MESSAGE);
      } else {
        message.success(
          `已发布 v${version.versionNo}（${describeVersionScope(version, departments)}）。`,
        );
      }
      setRunScope(version.runScope);
      setDepartmentId(version.departmentId ?? undefined);
      scopeInitializedFor.current = selectedWorkflowId;
      await Promise.all([loadDetail(), loadVersions()]);
      onWorkflowUpdated?.();
    } catch (caught) {
      setPublishError(describePublishFailure(caught));
    } finally {
      setPublishing(false);
    }
  };

  /**
   * 打开停用/启用确认弹窗。
   *
   * @param action 目标动作
   */
  const openStatusChange = (action: 'disable' | 'enable'): void => {
    setStatusAction(action);
    setStatusError(null);
  };

  /**
   * 提交停用/启用。
   *
   * 成功后刷新详情、版本与流程候选；409/403/503 保留在弹窗内。
   */
  const handleStatusChange = async (): Promise<void> => {
    if (!selectedWorkflowId || !statusAction) {
      return;
    }
    setStatusBusy(true);
    setStatusError(null);
    try {
      if (statusAction === 'disable') {
        await api.disableWorkflow(selectedWorkflowId);
        message.success('流程已停用：不能创建新运行，历史与在途运行保留。');
      } else {
        await api.enableWorkflow(selectedWorkflowId);
        message.success('流程已重新启用，可继续创建新运行。');
      }
      setStatusAction(null);
      await Promise.all([loadDetail(), loadVersions()]);
      onWorkflowUpdated?.();
    } catch (caught) {
      setStatusError(describeStatusFailure(caught));
    } finally {
      setStatusBusy(false);
    }
  };

  /**
   * 打开运行弹窗。
   *
   * @param versionId 指定的旧版本；省略时使用最近发布版本
   */
  const openRun = (versionId?: string): void => {
    if (!detail || !detail.canRun) {
      return;
    }
    const targetVersionId = versionId ?? detail.latestVersion?.id ?? versions[0]?.id ?? undefined;
    if (!targetVersionId) {
      message.warning('该流程还没有发布版本，不能运行。');
      return;
    }
    setRunVersionId(targetVersionId);
    setRunInputs('');
    setRunError(null);
    setRunOpen(true);
  };

  /**
   * 提交创建运行。
   *
   * 运行输入在客户端按契约上限预校验；成功后导航到 T12a 运行详情。
   */
  const handleRun = async (): Promise<void> => {
    if (!selectedWorkflowId) {
      return;
    }
    const parsed = parseRunInputs(runInputs);
    if (parsed.error || !parsed.inputs) {
      setRunError(parsed.error ?? '运行输入格式不正确。');
      return;
    }
    setRunBusy(true);
    setRunError(null);
    try {
      const run = await api.createRun(selectedWorkflowId, {
        versionId: runVersionId,
        inputs: parsed.inputs,
      });
      setRunOpen(false);
      message.success('已提交运行，正在打开运行详情。');
      onOpenDetail(run.id);
    } catch (caught) {
      setRunError(describeCreateRunFailure(caught));
    } finally {
      setRunBusy(false);
    }
  };

  /** 流程选择变化：清空旧详情并按新流程重新加载。 */
  const handleWorkflowChange = (value: string | undefined): void => {
    setSelectedWorkflowId(value || undefined);
    setPublishOpen(false);
    setStatusAction(null);
    setRunOpen(false);
  };

  /** 返回流程列表；无流程或错误态的下一步。 */
  const goToList = (): void => {
    onBackToStudio();
  };

  const workflowSelectOptions = workflowOptions.map((workflow) => ({
    value: workflow.id,
    label: workflow.name,
  }));
  const statusDisplay = detail ? workflowDisplayStatus(detail) : null;
  const runVersionOptions = versions.length
    ? versions.map((version) => ({
        value: version.id,
        label: versionOptionLabel(version, departments),
      }))
    : detail?.latestVersion
      ? [
          {
            value: detail.latestVersion.id,
            label: versionOptionLabel(detail.latestVersion, departments),
          },
        ]
      : [];

  return (
    <Card
      className="workflow-history-card"
      title="发布与版本"
      extra={
        <Select
          allowClear
          aria-label="选择流程"
          className="workflow-history-workflow-select"
          loading={workflowOptionsLoading}
          options={workflowSelectOptions}
          placeholder="选择流程查看版本"
          showSearch
          optionFilterProp="label"
          value={selectedWorkflowId}
          onChange={(value: string | undefined) => handleWorkflowChange(value)}
        />
      }
    >
      {workflowOptionsError ? (
        <Alert
          className="workflow-history-alert"
          type="warning"
          showIcon
          title="流程目录加载失败"
          description={workflowOptionsError}
          action={
            onReloadOptions ? (
              <Button size="small" aria-label="重试加载流程目录" onClick={onReloadOptions}>
                重试
              </Button>
            ) : undefined
          }
        />
      ) : null}

      {!selectedWorkflowId ? (
        <Empty
          description="选择流程后可查看已发布版本、发布当前草稿、停用/启用或运行指定版本。"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        >
          <Button onClick={goToList}>返回编排 Studio</Button>
        </Empty>
      ) : null}

      {selectedWorkflowId && phase === 'loading' ? (
        <div aria-busy="true" aria-label="正在加载流程版本">
          <Skeleton active paragraph={{ rows: 5 }} />
        </div>
      ) : null}

      {selectedWorkflowId && phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有访问权限"
          subTitle="查看流程版本需要 workflow.read 权限，请联系租户管理员申请。"
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void loadDetail()}>
              重试
            </Button>,
            <Button key="back" onClick={goToList}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {selectedWorkflowId && phase === 'notFound' ? (
        <Result
          status="404"
          title="流程不存在或你无权查看"
          subTitle="流程可能已删除、跨租户，或不在你的发布范围；为保护数据，页面不提示是否存在。"
          extra={[
            <Button key="retry" aria-label="重试" onClick={() => void loadDetail()}>
              重试
            </Button>,
            <Button key="back" type="primary" onClick={goToList}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {selectedWorkflowId && phase === 'disabled' ? (
        <Result
          status="warning"
          title="编排功能已关闭"
          subTitle={WORKFLOW_UNAVAILABLE_MESSAGE}
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void loadDetail()}>
              重新加载
            </Button>,
            <Button key="back" onClick={goToList}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {selectedWorkflowId && phase === 'error' ? (
        <Result
          status="error"
          title="流程详情加载失败"
          subTitle={detailError ?? '请稍后重试。'}
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void loadDetail()}>
              重试
            </Button>,
            <Button key="back" onClick={goToList}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {selectedWorkflowId && phase === 'ready' && detail ? (
        <div className="workflow-history-release" data-testid="workflow-release-panel">
          <Descriptions
            className="workflow-history-release-summary"
            bordered
            size="small"
            column={{ xs: 1, sm: 1, md: 2 }}
            items={[
              {
                key: 'status',
                label: '流程状态',
                children: statusDisplay ? (
                  <Tag color={statusDisplay.color}>{statusDisplay.label}</Tag>
                ) : (
                  '—'
                ),
              },
              {
                key: 'draft',
                label: '当前草稿',
                children: `v${detail.draftVersion}${
                  detail.hasUnpublishedChanges ? '（有未发布修改）' : ''
                }`,
              },
              {
                key: 'published',
                label: '当前发布版本',
                children:
                  detail.publishedVersionNo == null ? '尚未发布' : `v${detail.publishedVersionNo}`,
              },
              {
                key: 'latestScope',
                label: '最近发布范围',
                children: detail.latestVersion
                  ? describeVersionScope(detail.latestVersion, departments)
                  : '—',
              },
              {
                key: 'owner',
                label: '流程所有者',
                children: (
                  <span title={detail.ownerUserId}>{formatShortId(detail.ownerUserId, 12)}</span>
                ),
              },
              {
                key: 'updatedAt',
                label: '最近更新',
                children: formatOffsetDateTime(detail.updatedAt),
              },
            ]}
          />

          {detail.status === 'DISABLED' ? (
            <Alert
              className="workflow-history-alert"
              type="warning"
              showIcon
              title="流程已停用"
              description="停用后不能创建新运行，历史与在途运行保留；持有发布权限的所有者或租户管理员可以重新启用。"
            />
          ) : null}

          {!detail.canPublish ? (
            <Alert
              className="workflow-history-alert"
              type="info"
              showIcon
              title="只读版本"
              description={PUBLISH_READONLY_MESSAGE}
            />
          ) : null}

          <section
            className="workflow-history-release-section"
            aria-labelledby="workflow-publish-title"
          >
            <h2 id="workflow-publish-title" className="workflow-history-section-title">
              发布当前草稿
            </h2>
            <Typography.Paragraph type="secondary">
              发布后生成不可变版本；运行默认绑定最近发布版本，编辑草稿不影响已发布版本。
            </Typography.Paragraph>

            <Space wrap size={[8, 12]} align="center">
              <Select
                allowClear
                aria-label="发布运行范围"
                className="workflow-history-scope-select"
                value={runScope}
                options={scopeOptions.map((option) => ({
                  value: option.value,
                  label: option.label,
                  disabled: option.disabled,
                }))}
                onChange={(value: WorkflowRunScope) => handleScopeChange(value)}
                disabled={!detail.canPublish}
              />
              {runScope === 'DEPARTMENT' ? (
                <Select
                  allowClear
                  aria-label="发布部门"
                  className="workflow-history-department-select"
                  value={departmentId}
                  options={departmentOptions}
                  placeholder={
                    departmentsState === 'failed' ? '部门目录加载失败，可重试' : '选择你负责的部门'
                  }
                  onChange={(value: string | undefined) => setDepartmentId(value || undefined)}
                  disabled={!detail.canPublish}
                />
              ) : null}
              <Button
                type="primary"
                icon={<CloudUploadOutlined aria-hidden="true" />}
                disabled={!canSubmitPublish}
                onClick={openPublish}
              >
                发布版本
              </Button>
              <Button
                icon={<PlayCircleOutlined aria-hidden="true" />}
                disabled={!detail.canRun || !canRunPermission || detail.status !== 'ACTIVE'}
                onClick={() => openRun()}
              >
                运行流程
              </Button>
              <Button
                icon={<EditOutlined aria-hidden="true" />}
                onClick={() => onEditWorkflow(detail.id)}
              >
                打开编辑器
              </Button>
              {detail.status === 'ACTIVE' ? (
                <Button
                  danger
                  icon={<StopOutlined aria-hidden="true" />}
                  disabled={!detail.canPublish}
                  onClick={() => openStatusChange('disable')}
                >
                  停用流程
                </Button>
              ) : (
                <Button
                  icon={<PlayCircleOutlined aria-hidden="true" />}
                  disabled={!detail.canPublish}
                  onClick={() => openStatusChange('enable')}
                >
                  重新启用
                </Button>
              )}
            </Space>

            {departmentsState === 'loading' ? (
              <Typography.Text type="secondary">正在加载部门目录…</Typography.Text>
            ) : null}
            {departmentsState === 'failed' ? (
              <Alert
                className="workflow-history-alert"
                type="warning"
                showIcon
                title="部门目录加载失败"
                description={`${departmentsError ?? '无法确认你负责的部门。'} 暂时不能选择本部门；若最近版本使用本部门范围，页面会保留原范围并在发布前再次确认。`}
                action={
                  <Button size="small" onClick={() => void loadDepartments()}>
                    重试加载
                  </Button>
                }
              />
            ) : null}
            {currentScopeOption?.disabled && currentScopeOption.reason ? (
              <Alert
                className="workflow-history-alert"
                type="warning"
                showIcon
                title="当前运行范围不可用"
                description={currentScopeOption.reason}
              />
            ) : null}
            {scopeEvaluation.blockedReason ? (
              <Alert
                className="workflow-history-alert"
                type="error"
                showIcon
                title="暂不能发布"
                description={scopeEvaluation.blockedReason}
              />
            ) : null}
            {scopeEvaluation.warning ? (
              <Alert
                className="workflow-history-alert"
                type="warning"
                showIcon
                title="发布范围需要确认"
                description={scopeEvaluation.warning}
              />
            ) : null}
            <Typography.Text type="secondary" className="workflow-history-scope-hint">
              仅自己：只有你能运行；本部门：由你当前负责的启用部门成员运行；本租户：租户内具备运行权限的成员均可运行。
              发布前会再次确认范围，服务端始终按最新负责人/角色关系校验。
            </Typography.Text>
          </section>

          <section
            className="workflow-history-release-section"
            aria-labelledby="workflow-versions-title"
          >
            <div className="workflow-history-versions-heading">
              <h2 id="workflow-versions-title" className="workflow-history-section-title">
                已发布版本
              </h2>
              <Button
                size="small"
                icon={<ReloadOutlined aria-hidden="true" />}
                onClick={() => void loadVersions()}
                loading={versionsLoading}
              >
                刷新版本
              </Button>
            </div>
            <Typography.Paragraph type="secondary">
              版本不可变；旧版本仍可运行，除非流程被停用。选择“用此版本运行”会按该版本发布范围校验。
            </Typography.Paragraph>
            {versionsError ? (
              <Alert
                className="workflow-history-alert"
                type="error"
                showIcon
                title="版本列表加载失败"
                description={versionsError}
                action={
                  <Button
                    size="small"
                    aria-label="重试加载版本列表"
                    onClick={() => void loadVersions()}
                  >
                    重试
                  </Button>
                }
              />
            ) : null}
            {versionsLoading && versions.length === 0 ? (
              <Skeleton active paragraph={{ rows: 2 }} />
            ) : null}
            {!versionsLoading && !versionsError && versions.length === 0 ? (
              <Empty
                description="该流程还没有发布版本。发布当前草稿后会显示在这里。"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ) : null}
            {versions.length > 0 ? (
              <>
                <ul className="workflow-history-versions" data-testid="workflow-version-list">
                  {versions.map((version) => {
                    const isCurrent = version.id === detail.publishedVersionId;
                    return (
                      <li className="workflow-history-version" key={version.id}>
                        <div className="workflow-history-version-main">
                          <Space wrap size={[6, 6]}>
                            <Typography.Text strong>v{version.versionNo}</Typography.Text>
                            {isCurrent ? <Tag color="green">当前发布</Tag> : null}
                            <Tag>{describeVersionScope(version, departments)}</Tag>
                          </Space>
                          <dl className="workflow-history-version-meta">
                            <div>
                              <dt>发布人</dt>
                              <dd title={version.publishedBy}>
                                {formatShortId(version.publishedBy, 12)}
                              </dd>
                            </div>
                            <div>
                              <dt>发布时间</dt>
                              <dd>{formatOffsetDateTime(version.publishedAt)}</dd>
                            </div>
                            <div>
                              <dt>节点数</dt>
                              <dd>{version.nodeCount}</dd>
                            </div>
                          </dl>
                        </div>
                        <div className="workflow-history-version-actions">
                          <Button
                            size="small"
                            disabled={
                              !detail.canRun || !canRunPermission || detail.status !== 'ACTIVE'
                            }
                            onClick={() => openRun(version.id)}
                          >
                            用此版本运行
                          </Button>
                        </div>
                      </li>
                    );
                  })}
                </ul>
                {versionsTotal > VERSION_PAGE_SIZE ? (
                  <Typography.Text type="secondary">
                    共 {versionsTotal} 个版本，当前仅显示最近 {VERSION_PAGE_SIZE} 个。
                  </Typography.Text>
                ) : null}
              </>
            ) : null}
          </section>
        </div>
      ) : null}

      <Modal
        title="确认发布"
        open={publishOpen}
        okText="确认发布"
        cancelText="取消"
        okButtonProps={{ 'aria-label': '确认发布版本', disabled: !canSubmitPublish }}
        confirmLoading={publishing}
        onOk={() => void handlePublish()}
        onCancel={() => {
          if (!publishing) {
            setPublishOpen(false);
          }
        }}
      >
        <Typography.Paragraph>
          将当前草稿（v{detail?.draftVersion ?? '—'}）发布为不可变版本，范围：
          <Typography.Text strong>{runScopeLabel(runScope)}</Typography.Text>
          {runScope === 'DEPARTMENT'
            ? `（${
                departmentOptions.find((option) => option.value === departmentId)?.label ??
                formatShortId(departmentId, 12)
              }）`
            : ''}
          。发布前服务端会执行完整校验并按最新负责人/角色关系复核范围。
        </Typography.Paragraph>
        {detail && !detail.hasUnpublishedChanges ? (
          <Alert
            className="workflow-history-alert"
            type="info"
            showIcon
            title="草稿与最近发布版本一致"
            description={PUBLISH_IDEMPOTENT_MESSAGE}
          />
        ) : null}
        {scopeEvaluation.warning ? (
          <Alert
            className="workflow-history-alert"
            type="warning"
            showIcon
            title="请确认发布范围"
            description={scopeEvaluation.warning}
          />
        ) : null}
        {publishError ? <Alert type="error" showIcon title={publishError} /> : null}
      </Modal>

      <Modal
        title={statusAction === 'enable' ? '确认重新启用流程' : '确认停用流程'}
        open={statusAction != null}
        okText={statusAction === 'enable' ? '重新启用' : '停用流程'}
        cancelText="取消"
        okButtonProps={{
          danger: statusAction === 'disable',
          'aria-label': statusAction === 'enable' ? '确认重新启用流程' : '确认停用流程',
        }}
        confirmLoading={statusBusy}
        onOk={() => void handleStatusChange()}
        onCancel={() => {
          if (!statusBusy) {
            setStatusAction(null);
          }
        }}
      >
        <Typography.Paragraph>
          {statusAction === 'enable'
            ? '启用后可以继续创建新运行，历史与在途运行不受影响。'
            : '停用后不能创建新运行，历史与在途运行保留；持有发布权限的所有者或租户管理员可以重新启用。'}
        </Typography.Paragraph>
        {statusError ? <Alert type="error" showIcon title={statusError} /> : null}
      </Modal>

      <Modal
        title="运行流程"
        open={runOpen}
        okText="提交运行"
        cancelText="取消"
        confirmLoading={runBusy}
        onOk={() => void handleRun()}
        onCancel={() => {
          if (!runBusy) {
            setRunOpen(false);
          }
        }}
      >
        <Form layout="vertical" className="workflow-history-run-form">
          <Form.Item label="运行版本">
            <Select
              aria-label="选择运行版本"
              value={runVersionId}
              options={runVersionOptions}
              placeholder="选择已发布版本"
              onChange={(value: string) => setRunVersionId(value)}
            />
          </Form.Item>
          <Form.Item
            label="运行输入（JSON，可选）"
            extra="必须是 JSON 对象；最多 16 个键、64 KiB。留空表示无输入。"
          >
            <Input.TextArea
              aria-label="运行输入 JSON"
              rows={4}
              value={runInputs}
              placeholder={'例如 {"topic": "新能源市场"}'}
              onChange={(event) => setRunInputs(event.target.value)}
            />
          </Form.Item>
        </Form>
        <Typography.Paragraph type="secondary">
          运行默认绑定所选版本；旧版本仍可运行，除非流程被停用。若所选版本使用本部门范围，运行范围按该版本发布时确定，不随草稿或最新版本改变。
        </Typography.Paragraph>
        {runError ? <Alert type="error" showIcon title={runError} /> : null}
      </Modal>
    </Card>
  );
}
