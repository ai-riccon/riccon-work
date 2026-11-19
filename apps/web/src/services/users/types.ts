/**
 * 用户管理前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `UserSummary`、`UserPage`、
 * `OidcBindingRequest` 与 `OidcUnbindRequest`。请求类型直接从 `services/api/types`
 * 复用，避免在页面层复制第二份字段定义。
 */

import type { Page, UserSummary } from '@/services/api/types';

/** 账号分页，对应 OpenAPI `UserPage`。 */
export type UserPage = Page<UserSummary>;

export type { OidcBindingRequest, OidcUnbindRequest } from '@/services/api/types';
