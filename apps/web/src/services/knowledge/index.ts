import { API_BASE_URL } from '@/services/api/config';
import { authStore } from '@/services/auth/auth-store';
import { KnowledgeUserDirectoryClient } from './user-directory';
import { KnowledgeClient } from './api';
import type { KnowledgeApi } from './api';

/**
 * 应用默认知识库 API 客户端。
 *
 * CSRF 令牌优先从当前会话读取；401 统一交给 authStore，页面据此提示重新登录。
 * 不包含任何 mock 或演示成功分支，端到端测试由独立 stub API 承担。
 */
export const knowledgeApi: KnowledgeApi = new KnowledgeClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  csrfTokenProvider: () => authStore.getSnapshot().session?.csrfToken ?? null,
  onUnauthorized: () => authStore.markExpired(),
});

/**
 * 应用默认 ACL 用户目录；最多读取 200 个账号。
 */
export const knowledgeUserDirectory = new KnowledgeUserDirectoryClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  onUnauthorized: () => authStore.markExpired(),
});

export { KnowledgeClient, knowledgeDownloadPath, parseKnowledgeFilename } from './api';
export { ACL_MAX_USERS, KnowledgeUserDirectoryClient } from './user-directory';
export type { KnowledgeUserDirectoryClientOptions, KnowledgeUserFetchLike } from './user-directory';
export type { KnowledgeApi, KnowledgeClientOptions, KnowledgeFetchLike } from './api';
export * from './types';
export * from './format';
export * from './citation';
export * from './access';
