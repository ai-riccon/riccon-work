package com.riccon.api.quota.domain;

/**
 * 配额限额来源。
 *
 * <p>响应必须让前端能区分“显式不限”和“部署默认值”，避免把默认 10,000 误显示为管理员设置
 * （ADR-0022 第 1.1 节、OpenAPI {@code QuotaLimitSource}）。
 */
public enum QuotaLimitSource {
    /** 管理员显式设置的有限限额。 */
    EXPLICIT,
    /** 管理员显式设置为不限（limit_count 为 NULL 或 0）。 */
    EXPLICIT_UNLIMITED,
    /** 无显式行时使用部署配置 RICCON_QUOTA_DEFAULT_*。 */
    DEPLOYMENT_DEFAULT
}
