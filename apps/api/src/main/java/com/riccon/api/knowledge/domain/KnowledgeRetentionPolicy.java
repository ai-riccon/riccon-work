package com.riccon.api.knowledge.domain;

/**
 * 知识库文档保留策略来源（ADR-0023 第 10 节）。
 *
 * <p>历史文档 {@code retention_expires_at} 为空时按“创建时间 + 部署/租户默认天数”计算；
 * 创建时写入的到期值与当前策略一致时标记为部署默认或租户策略，不一致时视为显式设置。
 */
public enum KnowledgeRetentionPolicy {
    /** 部署默认 180 天。 */
    DEPLOYMENT_DEFAULT,
    /** 租户 {@code tenant.retention_days} 覆盖。 */
    TENANT_POLICY,
    /** 文档级显式到期时间，与当前策略不一致。 */
    EXPLICIT
}
