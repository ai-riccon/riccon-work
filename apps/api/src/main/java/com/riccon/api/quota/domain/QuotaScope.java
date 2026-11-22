package com.riccon.api.quota.domain;

/**
 * 配额维度。
 *
 * <p>与 {@code quota_limit.scope_type}/{@code quota_usage.scope_type} 的 CHECK 值、OpenAPI
 * {@code QuotaScopeType} 一一对应；两级必须同时通过才准入（ADR-0022 第 1.1 节）。
 */
public enum QuotaScope {
    /** 租户级配额。 */
    TENANT,
    /** 用户级配额。 */
    USER
}
