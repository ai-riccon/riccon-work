import { describe, expect, it } from 'vitest';
import { ApiError, describeApiError, isAbortError, toApiError } from './errors';

/** 构造 ProblemDetail 响应。 */
function problemResponse(status: number, code: string, detail?: string): Response {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      title: '请求失败',
      status,
      code,
      traceId: 'trace-123',
      ...(detail ? { detail } : {}),
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

describe('API 错误', () => {
  it('从 ProblemDetail 提取 status/code/traceId 与可重试标记', async () => {
    const error = await toApiError(problemResponse(503, 'RICCON-MODEL-500', '模型暂不可用'));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(503);
    expect(error.code).toBe('RICCON-MODEL-500');
    expect(error.traceId).toBe('trace-123');
    expect(error.message).toBe('模型暂不可用');
    expect(error.retryable).toBe(true);
  });

  it('非 JSON 响应退化为通用 HTTP 错误', async () => {
    const error = await toApiError(
      new Response('bad gateway', { status: 502, headers: { 'Content-Type': 'text/plain' } }),
    );

    expect(error.status).toBe(502);
    expect(error.code).toBe('RICCON-HTTP-502');
    expect(error.message).toContain('HTTP 502');
  });

  it('网络错误与主动取消使用独立状态', () => {
    expect(ApiError.network().status).toBe(0);
    expect(ApiError.network().kind).toBe('network');
    expect(isAbortError(ApiError.aborted())).toBe(true);
    expect(isAbortError(new DOMException('aborted', 'AbortError'))).toBe(true);
    expect(isAbortError(new Error('普通错误'))).toBe(false);
  });

  it('把 401/403/413/415/429 映射为固定中文文案', () => {
    expect(describeApiError(new ApiError('x', { status: 401, code: 'A' }))).toContain('重新登录');
    expect(describeApiError(new ApiError('x', { status: 403, code: 'A' }))).toContain(
      '没有访问权限',
    );
    expect(describeApiError(new ApiError('x', { status: 413, code: 'A' }))).toContain('200 MB');
    expect(describeApiError(new ApiError('x', { status: 415, code: 'A' }))).toContain('不支持');
    expect(describeApiError(new ApiError('x', { status: 429, code: 'A' }))).toContain('频繁');
    expect(describeApiError(new ApiError('x', { status: 500, code: 'A' }))).toContain('暂时不可用');
  });

  it('503 + RICCON-MODEL-503 优先使用 ProblemDetail detail（M04-AC-05）', async () => {
    const error = await toApiError(
      problemResponse(503, 'RICCON-MODEL-503', '模型服务暂不可用，请稍后重试。'),
    );

    // 文案来自 ProblemDetail.detail，错误码仍保留稳定值供页面展示。
    expect(describeApiError(error)).toBe('模型服务暂不可用，请稍后重试。');
    expect(error.code).toBe('RICCON-MODEL-503');
    expect(error.retryable).toBe(true);
  });

  it('503 + RICCON-MODEL-503 缺少 detail 时退化为通用 5xx 文案', async () => {
    const error = await toApiError(problemResponse(503, 'RICCON-MODEL-503'));

    expect(describeApiError(error)).toBe('服务暂时不可用，请稍后重试。');
    expect(error.code).toBe('RICCON-MODEL-503');
  });

  it('503 其他错误码保持通用文案且不回显服务端细节', async () => {
    const error = await toApiError(
      problemResponse(503, 'RICCON-CHAT-500', 'upstream provider=openai key=sk-live-123 stack'),
    );
    const message = describeApiError(error);

    expect(message).toBe('服务暂时不可用，请稍后重试。');
    expect(message).not.toContain('provider');
    expect(message).not.toContain('sk-live-123');
  });

  it('400/429/500 文案不回归', () => {
    expect(describeApiError(new ApiError('请求参数不正确', { status: 400, code: 'A' }))).toBe(
      '请求参数不正确',
    );
    expect(describeApiError(new ApiError('x', { status: 429, code: 'A' }))).toBe(
      '请求过于频繁，请稍后重试。',
    );
    expect(describeApiError(new ApiError('x', { status: 500, code: 'A' }))).toBe(
      '服务暂时不可用，请稍后重试。',
    );
  });

  it('未知异常使用兜底文案', () => {
    expect(describeApiError(new Error('secret'), '兜底')).toBe('兜底');
  });
});
