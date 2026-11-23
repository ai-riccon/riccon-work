package com.riccon.worker.connector.domain;

/**
 * 同步级失败分类，用于指标与重试决策。
 *
 * <p>认证/网络失败按 1m/5m/15m 退避三次后 ERROR；凭据不可解、配置非法、超时不再自动重试。
 */
public enum ConnectorFailureKind {
    /** 源端拒绝凭据。 */
    AUTH,
    /** 端点不可达或网络错误。 */
    NETWORK,
    /** 同步总超时。 */
    TIMEOUT,
    /** 凭据/DEK 不可解或未配置。 */
    CREDENTIAL,
    /** 端点/配置错误。 */
    CONFIG,
    /** 无失败。 */
    NONE
}
