import {
  ArrowLeftOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Flex,
  Popconfirm,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { Department, SessionInfo } from '@/services/api/types';
import {
  KNOWLEDGE_PERMISSIONS,
  canDeleteKnowledge,
  canEditKnowledgeAcl,
  canReindexKnowledge,
  describeKnowledgeError,
  formatKnowledgeSize,
  hasKnowledgePermission,
  knowledgeAclNameSummary,
  knowledgeOcrConfidenceText,
  knowledgeParseErrorText,
  knowledgeParseState,
  knowledgeParseStateDescription,
  knowledgeParseStateLabel,
  knowledgeRetentionText,
  knowledgeSafeErrorMessage,
  knowledgeParseStateTagStyle,
  knowledgeStatusTagStyle,
  knowledgeStatusLabel,
  knowledgeVisibilityTagStyle,
  knowledgeVisibilityLabel,
  knowledgeVisibilitySummary,
  knowledgeWarningTexts,
  parseEngineLabel,
} from '@/services/knowledge';
import type {
  KnowledgeApi,
  KnowledgeDocument,
  KnowledgeDocumentAcl,
  KnowledgeUserDirectory,
} from '@/services/knowledge';
import { formatOffsetDateTime } from '@/services/api/time';
import { KnowledgeAclEditor } from './acl-editor';

/** 文档详情面板属性。 */
export interface KnowledgeDetailPanelProps {
  /** 列表中的文档快照。 */
  document: KnowledgeDocument;
  /** 当前会话。 */
  session: SessionInfo | null;
  /** 可管理部门。 */
  departments: readonly Department[];
  /** 知识库 API。 */
  api: KnowledgeApi;
  /** 用户目录。 */
  userDirectory: KnowledgeUserDirectory;
  /** 返回列表。 */
  onBack: () => void;
  /** 文档或 ACL 变化后通知父级刷新列表。 */
  onChanged: () => void;
  /** 删除成功后关闭详情。 */
  onDeleted: () => void;
  /** 写入持久成功提示；由抽屉统一展示，刷新详情后仍保留。 */
  onNotice: (message: string) => void;
}

/**
 * 知识库文档详情与 ACL 面板。
 *
 * 状态覆盖：加载/成功/无权限/已删除/错误；解析状态覆盖成功、低置信、
 * 部分失败、失败、OCR 关闭；所有失败原因使用稳定中文文案，不展示堆栈。
 *
 * @param props 文档、会话、目录与回调
 * @returns 文档详情
 */
export function KnowledgeDetailPanel({
  document: initialDocument,
  session,
  departments,
  api,
  userDirectory,
  onBack,
  onChanged,
  onDeleted,
  onNotice,
}: KnowledgeDetailPanelProps) {
  const [document, setDocument] = useState<KnowledgeDocument>(initialDocument);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [acl, setAcl] = useState<KnowledgeDocumentAcl | null>(null);
  const [aclLoading, setAclLoading] = useState(false);
  const [aclError, setAclError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [reindexing, setReindexing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const canLoadAcl = hasKnowledgePermission(session?.permissions, KNOWLEDGE_PERMISSIONS.aclManage);
  const canEdit = acl !== null && canEditKnowledgeAcl(session, acl);
  const canReindex = canReindexKnowledge(session);
  const canDelete = acl !== null && canDeleteKnowledge(session, acl);
  const parseState = knowledgeParseState(document);
  const canRetryParse =
    parseState === 'partial' || parseState === 'failed' || parseState === 'disabled';
  const warnings = knowledgeWarningTexts(document.warnings);
  const ocrConfidence = knowledgeOcrConfidenceText(document.parseSummary);
  const sheets = document.parseSummary?.sheets ?? [];
  const creator =
    acl?.users.find((user) => user.creator) ??
    acl?.users.find((user) => user.userId === acl?.creatorUserId);
  const aclUsers = acl?.users.filter((user) => !user.creator) ?? [];

  /**
   * 读取最新详情；列表快照可能已过期。
   */
  const loadDetail = useCallback(async (): Promise<void> => {
    setLoading(true);
    setLoadError(null);
    setNotFound(false);
    try {
      setDocument(await api.getDocument(initialDocument.id));
    } catch (error) {
      const status = (error as { status?: number }).status;
      if (status === 404) {
        setNotFound(true);
      } else if (status === 403) {
        setLoadError('没有查看该文档的权限。');
      } else {
        setLoadError(describeKnowledgeError(error));
      }
    } finally {
      setLoading(false);
    }
  }, [api, initialDocument.id]);

  /**
   * 读取 ACL；仅租户管理员调用，普通员工不产生必然 403 的请求。
   */
  const loadAcl = useCallback(async (): Promise<void> => {
    if (!canLoadAcl) {
      return;
    }
    setAclLoading(true);
    setAclError(null);
    try {
      setAcl(await api.getDocumentAcl(initialDocument.id));
    } catch (error) {
      setAcl(null);
      setAclError(describeKnowledgeError(error));
    } finally {
      setAclLoading(false);
    }
  }, [api, canLoadAcl, initialDocument.id]);

  // 文档切换时重新读取详情与 ACL，避免沿用上一个文档的状态。
  useEffect(() => {
    setDocument(initialDocument);
    setAcl(null);
    setEditing(false);
    setDownloadError(null);
    setActionError(null);
    setNotFound(false);
    void loadDetail();
    void loadAcl();
  }, [initialDocument, loadAcl, loadDetail]);

  /**
   * 触发文档级重索引。
   *
   * @param force 是否整本重做
   */
  const handleReindex = async (force: boolean): Promise<void> => {
    setReindexing(true);
    setActionError(null);
    try {
      const updated = await api.reindexDocument(document.id, force);
      setDocument(updated);
      onNotice(
        force
          ? '已受理整本重做；解析完成前不会产生重复文档或重复引用。'
          : '已受理缺失页补齐；成功后状态会更新。',
      );
      onChanged();
    } catch (error) {
      setActionError(describeKnowledgeError(error));
    } finally {
      setReindexing(false);
    }
  };

  /**
   * 通过服务端代理下载源文件。
   *
   * 无 `fileId` 的历史/种子文档直接显示固定文案，不发起必败请求；
   * 有 `fileId` 但服务端判定无源文件时展示 `RICCON-KB-404` 中文原因。
   */
  const handleDownload = async (): Promise<void> => {
    setDownloadError(null);
    if (!document.fileId) {
      setDownloadError('该文档没有可下载的源文件。');
      return;
    }
    setDownloading(true);
    try {
      const download = await api.downloadDocument(document.id);
      const objectUrl = URL.createObjectURL(download.blob);
      const anchor = window.document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = download.filename;
      anchor.rel = 'noopener';
      window.document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
    } catch (error) {
      setDownloadError(describeKnowledgeError(error));
    } finally {
      setDownloading(false);
    }
  };

  /**
   * 软删除文档。
   */
  const handleDelete = async (): Promise<void> => {
    setDeleting(true);
    setActionError(null);
    try {
      await api.deleteDocument(document.id);
      onDeleted();
    } catch (error) {
      setActionError(describeKnowledgeError(error));
    } finally {
      setDeleting(false);
    }
  };

  if (notFound) {
    return (
      <Flex align="center" justify="center" style={{ minHeight: 240 }}>
        <Empty description="文档不存在、已被删除，或当前账号无权访问。">
          <Button onClick={onBack} type="primary">
            返回列表
          </Button>
        </Empty>
      </Flex>
    );
  }

  if (loadError) {
    return (
      <Alert
        action={
          <Space>
            <Button onClick={() => void loadDetail()} size="small">
              重试
            </Button>
            <Button onClick={onBack} size="small">
              返回列表
            </Button>
          </Space>
        }
        description={loadError}
        showIcon
        title="文档加载失败"
        type="error"
      />
    );
  }

  return (
    <section aria-labelledby="knowledge-detail-title" data-testid="knowledge-detail">
      <Flex align="start" gap={12} justify="space-between" wrap>
        <Flex align="center" gap={8} wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack} size="small">
            返回列表
          </Button>
          <Typography.Title
            data-testid="knowledge-document-title"
            id="knowledge-detail-title"
            level={4}
            style={{ margin: 0 }}
          >
            {document.title}
          </Typography.Title>
        </Flex>
        <Space wrap>
          <Button
            data-testid="knowledge-download"
            icon={<DownloadOutlined />}
            loading={downloading}
            onClick={() => {
              void handleDownload();
            }}
            size="small"
          >
            下载源文件
          </Button>
          {canReindex && canRetryParse && (
            <Button
              data-testid="knowledge-retry"
              disabled={reindexing}
              icon={<ReloadOutlined />}
              loading={reindexing}
              onClick={() => {
                void handleReindex(parseState === 'failed' || parseState === 'disabled');
              }}
              size="small"
            >
              {parseState === 'partial' ? '补齐失败页' : '重试解析'}
            </Button>
          )}
          {canReindex && document.status === 'INDEXED' && (
            <Button
              data-testid="knowledge-force-reindex"
              disabled={reindexing}
              onClick={() => {
                void handleReindex(true);
              }}
              size="small"
              type="link"
            >
              整本重做
            </Button>
          )}
          {canDelete && (
            <Popconfirm
              description="删除后文档不再出现在列表与检索中；物理清理由 M09 承接。"
              okText="确认删除"
              onConfirm={() => {
                void handleDelete();
              }}
              title="确认删除该文档？"
            >
              <Button
                data-testid="knowledge-delete"
                icon={<DeleteOutlined />}
                loading={deleting}
                size="small"
                style={{ color: '#a8071a', borderColor: '#ffa39e', background: '#fff1f0' }}
              >
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      </Flex>

      <Descriptions
        bordered
        column={{ xs: 1, sm: 1, md: 2 }}
        items={[
          {
            key: 'status',
            label: '状态',
            children: (
              <Tag style={knowledgeStatusTagStyle(document.status)} data-testid="knowledge-status">
                {knowledgeStatusLabel(document.status)}
              </Tag>
            ),
          },
          {
            key: 'visibility',
            label: '可见范围',
            children: (
              <Space size={[4, 4]} wrap>
                <Tag style={knowledgeVisibilityTagStyle(document.visibility)}>
                  {knowledgeVisibilityLabel(document.visibility)}
                </Tag>
                <span data-testid="knowledge-visibility">
                  {knowledgeVisibilitySummary(document, departments)}
                </span>
              </Space>
            ),
          },
          {
            key: 'creator',
            label: '创建人',
            children:
              creator?.displayName ?? (acl ? '未记录' : '创建人始终可见（姓名需管理员查看）'),
          },
          {
            key: 'acl',
            label: '显式名单',
            children:
              document.visibility === 'USERS'
                ? acl
                  ? knowledgeAclNameSummary(aclUsers)
                  : `${document.aclUserCount} 人（创建人始终可见）`
                : '无需单独名单',
          },
          {
            key: 'engine',
            label: '解析引擎',
            children: parseEngineLabel(document.parseEngine),
          },
          {
            key: 'size',
            label: '文件大小',
            children: formatKnowledgeSize(document.sizeBytes),
          },
          {
            key: 'created',
            label: '创建时间',
            children: formatOffsetDateTime(document.createdAt),
          },
          {
            key: 'indexed',
            label: '索引完成',
            children: formatOffsetDateTime(document.indexedAt),
          },
          {
            key: 'retention',
            label: '保留',
            children: knowledgeRetentionText(document),
          },
          {
            key: 'source-type',
            label: '来源',
            children: document.sourceType === 'UPLOAD' ? '上传文件' : '种子语料',
          },
        ]}
        size="small"
      />

      {document.sourceFileAccessLimited && (
        <Alert
          description="部分可见用户可能无法通过原工作空间打开源文件；可使用知识库下载。原工作空间路径不因知识库可见范围而放宽。"
          showIcon
          style={{ marginTop: 12 }}
          title="可见范围宽于源文件所在空间"
          type="warning"
        />
      )}

      <Typography.Paragraph
        data-testid="knowledge-retention-note"
        style={{ marginTop: 12 }}
        type="secondary"
      >
        保留策略变更仅影响新文档和新的到期计算，不追溯历史文档；默认 180 天，租户策略可覆盖。
      </Typography.Paragraph>

      <section
        aria-labelledby="knowledge-parse-title"
        className="knowledge-parse-section"
        data-testid="knowledge-parse-status"
      >
        <Typography.Title id="knowledge-parse-title" level={5}>
          解析与识别状态
        </Typography.Title>
        <Flex align="center" gap={8} wrap>
          <Tag style={knowledgeParseStateTagStyle(parseState)}>
            {knowledgeParseStateLabel(parseState)}
          </Tag>
          {ocrConfidence && <Typography.Text type="secondary">{ocrConfidence}</Typography.Text>}
        </Flex>
        {document.status === 'FAILED' && (
          <Typography.Paragraph
            data-testid="knowledge-failed-at"
            style={{ margin: '4px 0 8px' }}
            type="secondary"
          >
            失败时间：
            {document.failedAt ? formatOffsetDateTime(document.failedAt) : '未记录'}
          </Typography.Paragraph>
        )}
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          {knowledgeParseStateDescription(parseState)}
        </Typography.Paragraph>

        {document.status === 'FAILED' && (
          <Alert
            description={
              <>
                <Typography.Paragraph style={{ marginBottom: 4 }}>
                  {knowledgeParseErrorText(document.errorCode)}
                </Typography.Paragraph>
                {knowledgeSafeErrorMessage(document.errorMessage) && (
                  <Typography.Text type="secondary">
                    服务端说明：{knowledgeSafeErrorMessage(document.errorMessage)}
                  </Typography.Text>
                )}
              </>
            }
            showIcon
            title={parseState === 'disabled' ? '识别未启用' : '解析失败'}
            type={parseState === 'disabled' ? 'warning' : 'error'}
          />
        )}

        {parseState === 'partial' && (
          <Alert
            data-testid="knowledge-parse-warning"
            description={`成功页已可检索，失败页不产生引用。${
              document.parseSummary?.ocr?.failedPages?.length
                ? `失败页：${document.parseSummary.ocr.failedPages.join('、')}。`
                : ''
            }可按缺失页重试。`}
            showIcon
            title="部分成功"
            type="warning"
          />
        )}

        {parseState === 'low-confidence' && (
          <Alert
            data-testid="knowledge-parse-warning"
            description="文档仍可检索，引用会携带同样的低置信提示，请核对原文。"
            showIcon
            title="识别成功（置信度低，请核对原文）"
            type="warning"
          />
        )}

        {warnings.length > 0 && (
          <ul className="knowledge-warning-list" data-testid="knowledge-warning-list">
            {warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        )}

        {sheets.length > 0 && (
          <Descriptions
            bordered
            column={1}
            items={sheets.map((sheet) => ({
              key: sheet.name,
              label: sheet.empty ? `${sheet.name}（空工作表）` : sheet.name,
              children: (
                <Space size={[4, 4]} wrap>
                  <span>范围：{sheet.cellRange ?? '—'}</span>
                  {sheet.indexed === false && (
                    <Tag style={knowledgeParseStateTagStyle('partial')}>未索引</Tag>
                  )}
                  {sheet.indexedRange && sheet.indexedRange !== sheet.cellRange && (
                    <span>已索引：{sheet.indexedRange}</span>
                  )}
                  {sheet.empty && <Tag>空工作表</Tag>}
                </Space>
              ),
            }))}
            size="small"
            title="工作表结构"
          />
        )}

        {(document.parseSummary?.hiddenSheetCount ?? 0) > 0 && (
          <Typography.Paragraph type="secondary">
            隐藏工作表：已跳过 {document.parseSummary?.hiddenSheetCount} 个，不索引其内容。
          </Typography.Paragraph>
        )}

        {document.parseSummary?.ocr && (
          <Typography.Paragraph type="secondary">
            {knowledgeOcrConfidenceText(document.parseSummary)}
            {(document.parseSummary.ocr.failedPages?.length ?? 0) > 0
              ? `；失败页：${document.parseSummary.ocr.failedPages.join('、')}`
              : ''}
            {(document.parseSummary.ocr.lowConfidencePages?.length ?? 0) > 0
              ? `；低置信页：${document.parseSummary.ocr.lowConfidencePages.join('、')}`
              : ''}
          </Typography.Paragraph>
        )}
      </section>

      {downloadError && (
        <Alert
          data-testid="knowledge-download-error"
          description={downloadError}
          showIcon
          title="下载失败"
          type="error"
        />
      )}
      {actionError && (
        <Alert
          data-testid="knowledge-action-error"
          description={actionError}
          showIcon
          title="操作未完成"
          type="error"
        />
      )}
      <section
        aria-labelledby="knowledge-acl-title"
        className="knowledge-acl-section"
        data-testid="knowledge-acl-section"
      >
        <Flex align="center" gap={8} justify="space-between" wrap>
          <Typography.Title id="knowledge-acl-title" level={5}>
            可见范围与名单
          </Typography.Title>
          {canEdit && !editing && (
            <Button
              data-testid="knowledge-acl-edit"
              icon={<EditOutlined />}
              onClick={() => setEditing(true)}
              size="small"
            >
              修改可见范围
            </Button>
          )}
        </Flex>

        {aclLoading && <Skeleton active paragraph={{ rows: 2 }} />}
        {aclError && (
          <Alert description={aclError} showIcon title="可见范围加载失败" type="error" />
        )}
        {!canLoadAcl && (
          <Typography.Paragraph type="secondary">
            只有创建人或租户管理员可以查看完整名单；当前范围：
            {knowledgeVisibilitySummary(document, departments)}。
          </Typography.Paragraph>
        )}
        {acl && !editing && (
          <Descriptions
            bordered
            column={1}
            items={[
              {
                key: 'visibility',
                label: '可见范围',
                children: knowledgeVisibilityLabel(acl.visibility),
              },
              {
                key: 'creator',
                label: '创建人',
                children: creator?.displayName ?? '未记录',
              },
              {
                key: 'users',
                label: '名单摘要',
                children:
                  acl.visibility === 'USERS'
                    ? aclUsers.length > 0
                      ? aclUsers
                          .map((user) => `${user.displayName}${user.creator ? '（创建人）' : ''}`)
                          .join('、')
                      : '仅创建人可见'
                    : '当前范围无需单独名单；创建人始终可见',
              },
            ]}
            size="small"
          />
        )}
        {acl && editing && (
          <KnowledgeAclEditor
            acl={acl}
            api={api}
            departments={departments}
            document={document}
            onCancel={() => setEditing(false)}
            onSaved={(updated) => {
              setDocument(updated);
              setEditing(false);
              setActionError(null);
              onNotice('可见范围已保存，下一次请求生效。');
              void loadAcl();
              onChanged();
            }}
            session={session}
            userDirectory={userDirectory}
          />
        )}
      </section>

      {loading && (
        <Typography.Text aria-live="polite" type="secondary">
          正在刷新文档详情…
        </Typography.Text>
      )}
    </section>
  );
}
