import type { ProblemDetail } from './types';

/**
 * 前端统一的 API 错误。
 *
 * 设计目标：
 * 1. 把 RFC 9457 ProblemDetail 归一成可判断的 `status/code/traceId`，页面不解析原始 JSON；
 * 2. 网络失败与主动取消使用独立状态（`status = 0` / `kind = 'aborted'`），避免被误当成业务错误；
 * 3. 不在错误消息中拼接请求头、Cookie 或响应原文，防止把敏感信息带进界面和日志。
 */
export class ApiError extends Error {
  /** HTTP 状态码；网络错误与取消为 0。 */
  readonly status: number;
  /** 稳定的业务/协议错误码；网络错误为 `RICCON-NETWORK-000`。 */
  readonly code: string;
  /** 服务端 traceId，便于用户报障时定位；网络错误为空。 */
  readonly traceId?: string;
  /** 是否建议用户重试。 */
  readonly retryable: boolean;
  /** 是否由用户主动取消触发。 */
  readonly kind: 'http' | 'network' | 'aborted';
  /** 原始 ProblemDetail；仅用于需要展示 traceId 的场景。 */
  readonly problem?: ProblemDetail;

  /**
   * 创建 API 错误。
   *
   * @param message 面向用户的中文消息
   * @param options 状态码、错误码、traceId 与可重试标记
   */
  constructor(
    message: string,
    options: {
      status: number;
      code: string;
      traceId?: string;
      retryable?: boolean;
      kind?: 'http' | 'network' | 'aborted';
      problem?: ProblemDetail;
    },
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = options.status;
    this.code = options.code;
    this.traceId = options.traceId;
    this.retryable = (options.retryable ?? options.status >= 500) || options.status === 429;
    this.kind = options.kind ?? 'http';
    this.problem = options.problem;
  }

  /**
   * 创建网络错误。
   *
   * @param cause 原始异常，仅用于开发者排查，不进入 UI
   * @returns 状态码为 0 的 ApiError
   */
  static network(cause?: unknown): ApiError {
    const error = new ApiError('无法连接服务器，请检查网络后重试。', {
      status: 0,
      code: 'RICCON-NETWORK-000',
      retryable: true,
      kind: 'network',
    });
    if (cause instanceof Error) {
      (error as Error & { cause?: unknown }).cause = cause;
    }
    return error;
  }

  /**
   * 创建“用户主动取消”错误。
   *
   * @returns kind 为 aborted 的 ApiError
   */
  static aborted(): ApiError {
    return new ApiError('请求已取消。', {
      status: 0,
      code: 'RICCON-CLIENT-ABORT',
      retryable: false,
      kind: 'aborted',
    });
  }
}

/**
 * 判断异常是否由 AbortController 触发。
 *
 * 浏览器抛 DOMException，Node/测试环境可能抛普通 Error，因此同时检查 name。
 *
 * @param error 任意异常
 * @returns 是否为主动取消
 */
export function isAbortError(error: unknown): boolean {
  if (error instanceof ApiError) {
    return error.kind === 'aborted';
  }
  return (
    (error instanceof DOMException && error.name === 'AbortError') ||
    (error instanceof Error && error.name === 'AbortError')
  );
}

/**
 * 从失败响应构造 ApiError。
 *
 * ProblemDetail 解析失败时退化为通用 HTTP 错误，仍然保留状态码；
 * 消息优先使用 `detail`，其次 `title`，避免把内部堆栈展示给用户。
 *
 * @param response fetch 返回的非 2xx 响应
 * @returns 归一化的 ApiError
 */
export async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined;
  try {
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('json')) {
      problem = (await response.json()) as ProblemDetail;
    }
  } catch {
    // 响应不是合法 JSON 时保持 problem 为空，由下面的兜底消息处理。
  }

  const message =
    problem?.detail || problem?.title || `请求失败（HTTP ${response.status}），请稍后重试。`;

  return new ApiError(message, {
    status: response.status,
    code: problem?.code ?? `RICCON-HTTP-${response.status}`,
    traceId: problem?.traceId,
    retryable: response.status >= 500 || response.status === 429,
    kind: 'http',
    problem,
  });
}

/**
 * 把任意异常转换为适合界面展示的短消息。
 *
 * 业务 401/403/404 使用固定文案，避免服务端错误细节泄露账号或对象是否存在；
 * 413/415 使用文件场景的明确说明；模型链全不可用（M04-AC-05）优先透传 ProblemDetail 的固定中文 detail，
 * 其余 5xx 保持通用文案。
 *
 * @param error 任意异常
 * @param fallback 未识别异常的兜底文案
 * @returns 面向用户的中文消息
 */
export function describeApiError(error: unknown, fallback = '操作失败，请稍后重试。'): string {
  if (!(error instanceof ApiError)) {
    return fallback;
  }

  switch (error.status) {
    case 0:
      return error.kind === 'aborted' ? '操作已取消。' : '无法连接服务器，请检查网络后重试。';
    case 400:
      return error.message || '请求参数不正确，请检查后重试。';
    case 401:
      return '登录状态已失效，请重新登录。';
    case 403:
      return '没有访问权限，或安全令牌已失效。';
    case 404:
      return '内容不存在或已被删除。';
    case 409:
      return error.message || '当前状态不允许该操作，请刷新后重试。';
    case 413:
      return '文件超过 200 MB 上限，请压缩或拆分后重试。';
    case 415:
      return '不支持的文件格式，请上传 PDF、DOCX、XLSX、PNG 或 JPEG。';
    case 429:
      return '请求过于频繁，请稍后重试。';
    default:
      if (error.status >= 500) {
        // M04-AC-05：模型链所有模型与 key 均不可用时 API 返回稳定 503，
        // 并把固定中文文案放在 ProblemDetail.detail；这里优先透传该文案。
        // 其余 5xx 继续使用通用文案，避免把 provider、key、堆栈等内部细节带进界面。
        const modelUnavailable = error.status === 503 && error.code === 'RICCON-MODEL-503';
        return (modelUnavailable && error.problem?.detail) || '服务暂时不可用，请稍后重试。';
      }
      return error.message || fallback;
  }
}
