package com.riccon.api.workflow.runtime.domain;

/**
 * 运行保留策略来源（契约 {@code WorkflowRetentionPolicy}，ADR-0024 §10.1）。
 *
 * <p>M06 不新增租户保留覆盖入口：租户值与部署默认一致时归因 {@link #DEPLOYMENT_DEFAULT}，
 * 平台管理员为租户单独设置过保留天数时归因 {@link #TENANT_POLICY}。实际运行到期时间仍由
 * {@code workflow_run.retention_expires_at} 决定，本枚举只用于运行限制的只读展示。
 */
public enum WorkflowRetentionPolicy {

    /** 部署默认（{@code RICCON_WORKFLOW_RETENTION_DAYS}，默认 180 天）。 */
    DEPLOYMENT_DEFAULT,

    /** 租户 {@code tenant.retention_days} 覆盖部署默认。 */
    TENANT_POLICY
}
