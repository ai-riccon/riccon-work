import { ApiError } from '@/services/api/errors';
import type { Department } from '@/services/api/types';
import { formatOffsetDateTime } from '@/services/api/time';
import type {
  KnowledgeDocument,
  KnowledgeDocumentStatus,
  KnowledgeParseSummary,
  KnowledgeRetentionPolicy,
  KnowledgeVisibility,
  ParseEngine,
} from './types';

/** 文档状态中文名。 */
const STATUS_LABELS: Record<KnowledgeDocumentStatus, string> = {
  PENDING: '排队中',
  INDEXING: '解析中',
  INDEXED: '已索引',
  FAILED: '解析失败',
  DELETED: '已删除',
};

/** 可见范围中文名。 */
const VISIBILITY_LABELS: Record<KnowledgeVisibility, string> = {
  TENANT: '租户全员',
  DEPT: '指定部门',
  USERS: '指定用户',
};

/** 解析引擎中文名。 */
const ENGINE_LABELS: Record<Exclude<ParseEngine, null>, string> = {
  PDFBOX: 'PDF 文本层',
  POI: 'Excel 结构化解析',
  TESSERACT: 'OCR（Tesseract）',
  RAPIDOCR: 'OCR（RapidOCR）',
  NONE: '未进入解析',
};

/** 保留策略中文名。 */
const RETENTION_LABELS: Record<KnowledgeRetentionPolicy, string> = {
  DEPLOYMENT_DEFAULT: '部署默认',
  TENANT_POLICY: '租户策略',
  EXPLICIT: '文档显式设置',
};

/** 解析状态；用于详情页与列表的四态展示。 */
export type KnowledgeParseState =
  'loading' | 'success' | 'low-confidence' | 'partial' | 'failed' | 'disabled';

/** 低置信阈值；ADR-0023 §4.3。 */
export const LOW_CONFIDENCE_THRESHOLD = 0.6;

/**
 * 返回文档状态中文名。
 *
 * @param status 文档状态
 * @returns 中文名
 */
export function knowledgeStatusLabel(status: KnowledgeDocumentStatus): string {
  return STATUS_LABELS[status] ?? status;
}

/**
 * 返回文档状态对应的 antd 颜色。
 *
 * @param status 文档状态
 * @returns Tag 颜色
 */
export function knowledgeStatusColor(
  status: KnowledgeDocumentStatus,
): 'default' | 'processing' | 'success' | 'warning' | 'error' {
  switch (status) {
    case 'PENDING':
      return 'default';
    case 'INDEXING':
      return 'processing';
    case 'INDEXED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'DELETED':
    default:
      return 'default';
  }
}

/**
 * 返回可见范围中文名。
 *
 * @param visibility 可见范围
 * @returns 中文名
 */
export function knowledgeVisibilityLabel(visibility: KnowledgeVisibility): string {
  return VISIBILITY_LABELS[visibility] ?? visibility;
}

/**
 * 返回可见范围对应的 antd 颜色。
 *
 * @param visibility 可见范围
 * @returns Tag 颜色
 */
export function knowledgeVisibilityColor(
  visibility: KnowledgeVisibility,
): 'blue' | 'geekblue' | 'purple' {
  switch (visibility) {
    case 'TENANT':
      return 'blue';
    case 'DEPT':
      return 'geekblue';
    case 'USERS':
    default:
      return 'purple';
  }
}

/**
 * 返回部门展示名。
 *
 * @param departments 可管理部门列表
 * @param departmentId 部门 ID
 * @returns 部门名；未知时回退到 ID
 */
export function knowledgeDepartmentName(
  departments: readonly Department[],
  departmentId: string | null | undefined,
): string | null {
  if (!departmentId) {
    return null;
  }
  return departments.find((department) => department.id === departmentId)?.name ?? null;
}

/**
 * 生成列表中的可见范围摘要。
 *
 * 列表不批量读取 ACL，避免 N+1；因此只展示范围与显式名单人数，
 * 创建人姓名在详情/ACL 面板中展示，列表始终提示创建人隐式可见。
 *
 * @param document 文档
 * @param departments 可管理部门列表
 * @returns 中文摘要
 */
export function knowledgeVisibilitySummary(
  document: Pick<KnowledgeDocument, 'visibility' | 'departmentId' | 'aclUserCount'>,
  departments: readonly Department[] = [],
): string {
  switch (document.visibility) {
    case 'DEPT': {
      const name = knowledgeDepartmentName(departments, document.departmentId);
      return name ? `指定部门：${name}` : '指定部门（部门信息加载中）';
    }
    case 'USERS':
      return `指定用户：${document.aclUserCount} 人（创建人始终可见）`;
    case 'TENANT':
    default:
      return '本租户全员可见（创建人始终可见）';
  }
}

/**
 * 生成 ACL 名单摘要，避免 200 人名单把详情页撑爆。
 *
 * @param users 显式名单（可含创建人）
 * @param limit 最多展示的姓名数
 * @returns 例如 `王敏、李强（创建人）等 12 人`；空名单返回“仅创建人可见”
 */
export function knowledgeAclNameSummary(
  users: readonly { displayName: string; creator?: boolean }[],
  limit = 5,
): string {
  if (users.length === 0) {
    return '仅创建人可见';
  }
  const names = users
    .slice(0, limit)
    .map((user) => `${user.displayName}${user.creator ? '（创建人）' : ''}`);
  return users.length > limit ? `${names.join('、')} 等 ${users.length} 人` : names.join('、');
}

/**
 * 判断文档是否处于解析中。
 *
 * @param document 文档
 * @returns PENDING/INDEXING 时为 true
 */
export function knowledgeIsParsing(document: Pick<KnowledgeDocument, 'status'>): boolean {
  return document.status === 'PENDING' || document.status === 'INDEXING';
}

/**
 * 提取结构化警告中的页号列表。
 *
 * @param warnings 警告数组
 * @param code 警告码前缀
 * @returns 页号列表；格式异常时为空数组
 */
export function knowledgeWarningPages(
  warnings: readonly string[] | undefined,
  code: 'FAILED_PAGES' | 'LOW_CONFIDENCE_PAGES',
): number[] {
  const entry = warnings?.find((warning) => warning.startsWith(`${code}:`));
  if (!entry) {
    return [];
  }
  const raw = entry.slice(code.length + 1).replace(/^\[|\]$/g, '');
  if (!raw) {
    return [];
  }
  return raw
    .split(',')
    .map((value) => Number.parseInt(value, 10))
    .filter((value) => Number.isInteger(value) && value > 0);
}

/**
 * 判断文档是否具备部分成功或低置信状态。
 *
 * @param document 文档
 * @returns 解析状态
 */
export function knowledgeParseState(document: KnowledgeDocument): KnowledgeParseState {
  if (knowledgeIsParsing(document)) {
    return 'loading';
  }
  if (document.status === 'FAILED' || document.status === 'DELETED') {
    if (
      document.errorCode === 'KB_OCR_DISABLED' ||
      document.errorCode === 'KB_OCR_LANG_UNAVAILABLE' ||
      document.errorCode === 'KB_OCR_UNAVAILABLE'
    ) {
      return 'disabled';
    }
    return 'failed';
  }

  const failedPages =
    document.parseSummary?.ocr?.failedPages?.length ??
    knowledgeWarningPages(document.warnings, 'FAILED_PAGES').length;
  if (
    failedPages > 0 ||
    document.warnings?.some((warning) => warning.startsWith('FAILED_PAGES:'))
  ) {
    return 'partial';
  }

  const lowPages =
    document.parseSummary?.ocr?.lowConfidencePages?.length ??
    knowledgeWarningPages(document.warnings, 'LOW_CONFIDENCE_PAGES').length;
  const averageConfidence = document.parseSummary?.ocr?.averageConfidence;
  const lowConfidence =
    document.warnings?.includes('LOW_CONFIDENCE') ||
    lowPages > 0 ||
    (averageConfidence !== undefined && averageConfidence < LOW_CONFIDENCE_THRESHOLD);
  return lowConfidence ? 'low-confidence' : 'success';
}

/**
 * 返回解析状态中文文案。
 *
 * @param state 解析状态
 * @returns 例如“识别成功（置信度低，请核对原文）”
 */
export function knowledgeParseStateLabel(state: KnowledgeParseState): string {
  switch (state) {
    case 'loading':
      return '解析中';
    case 'low-confidence':
      return '识别成功（置信度低，请核对原文）';
    case 'partial':
      return '部分成功';
    case 'failed':
      return '识别失败';
    case 'disabled':
      return '识别未启用';
    case 'success':
    default:
      return '识别成功';
  }
}

/**
 * 返回解析状态的补充说明与下一步。
 *
 * @param state 解析状态
 * @returns 中文说明
 */
export function knowledgeParseStateDescription(state: KnowledgeParseState): string {
  switch (state) {
    case 'loading':
      return '文档正在后台解析，完成后即可检索；不会阻塞其他文档提问。';
    case 'low-confidence':
      return '已入索引并可检索；引用会携带同样的低置信提示，请核对原文。';
    case 'partial':
      return '成功页已可检索；失败页不会产生引用，可按缺失页重试。';
    case 'failed':
      return '本次没有产生可检索内容；请查看原因后重试。';
    case 'disabled':
      return '图片或扫描件暂未识别；原图与文件名仍保留，请联系管理员启用 OCR 或安装语言包。';
    case 'success':
    default:
      return '文档已完成解析，可被检索与引用。';
  }
}

/**
 * 把解析错误码映射为稳定中文原因。
 *
 * 不直接展示英文错误码或异常堆栈；服务端 `errorMessage` 只作为补充，
 * 且只在长度与内容可控时展示（见 `knowledgeSafeErrorMessage`）。
 *
 * @param errorCode 稳定错误码
 * @returns 中文原因
 */
export function knowledgeParseErrorText(errorCode: string | null | undefined): string {
  switch (errorCode) {
    case 'KB_PARSE_ENCRYPTED':
      return '文件已加密或受密码保护，无法解析；请移除密码后重新上传。';
    case 'KB_PARSE_CORRUPT':
      return '文件损坏或格式不合法；请检查文件后重新上传。';
    case 'KB_PARSE_RESOURCE_LIMIT':
      return '文件超出解析资源上限；请拆分后重新上传。';
    case 'KB_PARSE_TIMEOUT':
      return '解析超时；可稍后重试，其他文档不受影响。';
    case 'KB_NO_TEXT':
      return '没有可索引的文本内容；请确认文件不是空表或纯图片。';
    case 'KB_OCR_DISABLED':
      return 'OCR 未启用，图片仅保留原图与文件名；请联系管理员启用后重试。';
    case 'KB_OCR_LANG_UNAVAILABLE':
      return '不支持该语言的 OCR；请联系管理员安装语言包。';
    case 'KB_OCR_TIMEOUT':
      return 'OCR 单页超时；其余页继续处理，可重试失败页。';
    case 'KB_OCR_FAILED':
      return 'OCR 识别失败；可稍后重试，或联系管理员检查识别引擎。';
    case 'KB_OCR_UNAVAILABLE':
      return '扫描件没有文本层且 OCR 不可用；请联系管理员。';
    default:
      return errorCode ? `解析失败（${errorCode}）。` : '解析失败，请稍后重试。';
  }
}

/**
 * 过滤服务端失败原因，避免把堆栈或超长内部文本带进界面。
 *
 * @param errorMessage 服务端脱敏消息
 * @returns 可展示的安全消息；不满足条件时为 null
 */
export function knowledgeSafeErrorMessage(errorMessage: string | null | undefined): string | null {
  const text = errorMessage?.trim();
  if (!text || text.length > 200) {
    return null;
  }
  if (/\bat\s+[\w.$]+\(|Exception|SQLException|stack/i.test(text)) {
    return null;
  }
  return text;
}

/**
 * 把白名单警告转换为中文说明。
 *
 * 未知警告保留原始码，便于排障；不回填任何原文或单元格内容。
 *
 * @param warnings 警告数组
 * @returns 中文说明列表
 */
export function knowledgeWarningTexts(warnings: readonly string[] | undefined): string[] {
  if (!warnings || warnings.length === 0) {
    return [];
  }
  return warnings.map((warning) => {
    const [code, rawParam] = splitWarning(warning);
    const count = Number.parseInt(rawParam ?? '', 10);
    switch (code) {
      case 'SHEETS_TRUNCATED':
        return '工作表数量超过上限，仅索引前 50 个。';
      case 'ROWS_TRUNCATED':
        return rawParam
          ? `工作表“${rawParam}”行数超过上限，已截断。`
          : '工作表行数超过上限，已截断。';
      case 'COLUMNS_TRUNCATED':
        return rawParam
          ? `工作表“${rawParam}”列数超过上限，已截断。`
          : '工作表列数超过上限，已截断。';
      case 'CHARS_TRUNCATED':
        return '单文档字符数超过上限，已截断。';
      case 'SEGMENTS_TRUNCATED':
        return '单文档段落数超过上限，已截断。';
      case 'CHUNKS_TRUNCATED':
        return '单文档分块数超过上限，已截断。';
      case 'HIDDEN_SHEETS_SKIPPED':
        return Number.isInteger(count) ? `已跳过 ${count} 个隐藏工作表。` : '已跳过隐藏工作表。';
      case 'FORMULA_NOT_CACHED':
        return '部分公式没有缓存值，已跳过；不会计算公式。';
      case 'LOW_CONFIDENCE':
        return '识别置信度低，请核对原文。';
      case 'OCR_PAGE_LIMIT':
        return Number.isInteger(count)
          ? `超过 OCR 页数上限，${count} 页仅保留文本层。`
          : '超过 OCR 页数上限，部分页仅保留文本层。';
      case 'PAGES_WITHOUT_TEXT':
        return Number.isInteger(count) ? `${count} 页没有可抽取文本。` : '部分页没有可抽取文本。';
      case 'FAILED_PAGES': {
        const pages = knowledgeWarningPages([warning], 'FAILED_PAGES');
        return pages.length > 0 ? `第 ${pages.join('、')} 页识别失败。` : '部分页识别失败。';
      }
      case 'LOW_CONFIDENCE_PAGES': {
        const pages = knowledgeWarningPages([warning], 'LOW_CONFIDENCE_PAGES');
        return pages.length > 0
          ? `第 ${pages.join('、')} 页置信度低，请核对原文。`
          : '部分页置信度低。';
      }
      default:
        return `解析警告：${warning}`;
    }
  });
}

/**
 * 拆分 `CODE:参数` 形式的警告。
 *
 * @param warning 原始警告
 * @returns 代码与参数
 */
function splitWarning(warning: string): [string, string | undefined] {
  const index = warning.indexOf(':');
  if (index < 0) {
    return [warning, undefined];
  }
  return [warning.slice(0, index), warning.slice(index + 1)];
}

/**
 * 生成 OCR 平均置信度文案。
 *
 * @param summary 解析摘要
 * @returns 例如“平均置信度 82%”；无 OCR 时返回 null
 */
export function knowledgeOcrConfidenceText(
  summary: KnowledgeParseSummary | null | undefined,
): string | null {
  const average = summary?.ocr?.averageConfidence;
  if (average === undefined || average === null) {
    return null;
  }
  return `平均置信度 ${Math.round(average * 100)}%`;
}

/**
 * 返回解析引擎中文名。
 *
 * @param engine 解析引擎
 * @returns 中文名；历史文档未记录时返回“未记录”
 */
export function parseEngineLabel(engine: ParseEngine | undefined): string {
  if (engine === undefined) {
    return '未记录';
  }
  if (engine === null) {
    return '未记录';
  }
  return ENGINE_LABELS[engine] ?? engine;
}

/**
 * 返回保留策略中文名。
 *
 * @param policy 保留策略
 * @returns 中文名
 */
export function retentionPolicyLabel(policy: KnowledgeRetentionPolicy | undefined): string {
  if (!policy) {
    return '未记录';
  }
  return RETENTION_LABELS[policy] ?? policy;
}

/**
 * 生成保留到期文案。
 *
 * @param document 文档
 * @returns 例如 `2027-05-22（180 天，租户策略）`
 */
export function knowledgeRetentionText(
  document: Pick<KnowledgeDocument, 'retentionDays' | 'retentionExpiresAt' | 'retentionPolicy'>,
): string {
  const days = document.retentionDays ? `保留 ${document.retentionDays} 天` : '保留策略未记录';
  const policy = retentionPolicyLabel(document.retentionPolicy);
  const expires = document.retentionExpiresAt
    ? `到期 ${formatOffsetDateTime(document.retentionExpiresAt)}`
    : '到期时间未记录';
  return `${expires}（${days}，${policy}）`;
}

/**
 * 把文件大小格式化为易读文本。
 *
 * @param bytes 字节数
 * @returns 例如 `1.5 MB`
 */
export function formatKnowledgeSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`;
}

/**
 * 把下载错误映射为中文原因。
 *
 * `RICCON-KB-404` 只会在 ACL 通过后出现，语义是“无源文件”；
 * 不可见仍是 `RICCON-TENANT-404`，界面显示“不存在或已被删除”。
 *
 * @param error 任意异常
 * @returns 中文原因
 */
export function describeKnowledgeError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '知识库操作未完成，请稍后重试。';
  }
  if (error.status === 401) {
    return '登录状态已失效，请重新登录。';
  }
  if (error.status === 403) {
    return '没有修改该文档可见范围的权限。';
  }
  if (error.status === 404) {
    if (error.code === 'RICCON-KB-404') {
      return '该文档没有可下载的源文件。';
    }
    return '文档不存在、已被删除，或当前账号无权访问。';
  }
  if (error.status === 409) {
    return '文档状态已变化，请刷新后重试。';
  }
  if (error.status === 503) {
    return '索引服务暂不可用，请稍后重试。';
  }
  if (error.status === 502) {
    return '源文件读取失败，请稍后重试。';
  }
  if (error.status === 400) {
    return error.message || '可见范围参数不正确，请检查后重试。';
  }
  return '知识库操作未完成，请稍后重试。';
}
