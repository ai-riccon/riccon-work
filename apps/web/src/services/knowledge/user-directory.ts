import { ApiError, toApiError } from '@/services/api/errors';
import type { Page, UserSummary } from '@/services/api/types';
import type { KnowledgeUserDirectory } from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type KnowledgeUserFetchLike = typeof fetch;

/** ACL 用户目录客户端构造参数。 */
export interface KnowledgeUserDirectoryClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: KnowledgeUserFetchLike;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/** ACL 单次最多可选用户数；与 `RICCON_KB_ACL_MAX_USERS` 对齐。 */
export const ACL_MAX_USERS = 200;

/**
 * ACL 可授权用户目录。
 *
 * 复用账号管理读取端点 `/api/v1/users`（`tenant.user.manage`），按契约最大页大小
 * 100 分页，最多读取 200 个账号；超过 200 的账号无法直接加入名单，
 * 与后端上限一致，不做静默截断或伪全量。
 */
export class KnowledgeUserDirectoryClient implements KnowledgeUserDirectory {
  private readonly baseUrl: string;

  private readonly fetchImpl: KnowledgeUserFetchLike;

  private readonly onUnauthorized?: () => void;

  /**
   * 创建目录客户端。
   *
   * @param options 基础地址、fetch 与会话过期回调
   */
  constructor(options: KnowledgeUserDirectoryClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 分页读取可授权账号，最多 200 个。
   *
   * @returns 账号数组；服务端 total 小于 100 时只请求一页
   */
  async listUsers(): Promise<UserSummary[]> {
    const collected: UserSummary[] = [];
    const pageSize = 100;
    for (let page = 1; page <= 2; page += 1) {
      const response = await this.fetchPage(page, pageSize);
      collected.push(...response.items);
      if (collected.length >= ACL_MAX_USERS || response.items.length < pageSize) {
        break;
      }
    }
    return collected.slice(0, ACL_MAX_USERS);
  }

  /**
   * 读取一页账号。
   *
   * @param page 页码
   * @param size 每页大小
   * @returns 账号分页
   */
  private async fetchPage(page: number, size: number): Promise<Page<UserSummary>> {
    const path = `/api/v1/users?page=${page}&size=${size}`;
    let response: Response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
      });
    } catch {
      throw ApiError.network();
    }
    if (response.status === 401) {
      this.onUnauthorized?.();
    }
    if (!response.ok) {
      throw await toApiError(response);
    }
    return (await response.json()) as Page<UserSummary>;
  }
}
