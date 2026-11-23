package com.riccon.api.knowledge.connector.domain;

/**
 * 连接测试失败分类（OpenAPI {@code ConnectorTestCategory}）。
 *
 * <p>成功时为 {@code null}；失败必须归入以下分类之一，便于管理员按 RUNBOOK 排查。
 */
public enum ConnectorTestCategory {
    /** 凭据无效、过期或被拒绝。 */
    AUTH,
    /** 端点不可达、连接超时或 TLS 失败。 */
    NETWORK,
    /** 端点不在允许列表、URL 非法或主机不可解析。 */
    ENDPOINT,
    /** 桶名、区域、凭据未配置等配置问题。 */
    CONFIG
}
