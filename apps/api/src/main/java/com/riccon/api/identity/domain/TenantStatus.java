package com.riccon.api.identity.domain;

/**
 * 租户状态。
 *
 * <p>值与 {@code contracts/openapi.yaml} 的 {@code TenantStatus} 一致；数据库列使用 VARCHAR + CHECK，
 * 新增值必须同步迁移与契约。
 */
public enum TenantStatus {
    /** 正常可用。 */
    ACTIVE,
    /** 暂停；禁止登录与业务访问，元数据保留。 */
    SUSPENDED
}
