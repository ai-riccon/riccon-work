package com.riccon.worker.model.domain;

import java.util.Locale;

/**
 * 降级触发原因（ADR-0021 §5.1/§5.6）。
 *
 * <p>枚举名同时用于 `model.failover.triggered.details.reason` 与指标
 * `riccon_model_failover_total{reason}`，保持审计与指标的同一份词汇。
 */
public enum FailureReason {

    /** 连接超时/拒绝/DNS 失败或上游 5xx。 */
    UNAVAILABLE,

    /** 等待响应头或首字超时。 */
    TIMEOUT,

    /** 429 限流。 */
    RATE_LIMITED,

    /** 401 凭据失效。 */
    UNAUTHORIZED,

    /** 403 上游拒绝访问。 */
    FORBIDDEN,

    /** 协议/JSON/3xx 等非法响应。 */
    INVALID_RESPONSE,

    /** 凭据解密或配置不可用。 */
    CONFIGURATION,

    /** 首字后断流/超时；不触发任何切换。 */
    INTERRUPTED,

    /** 同一 key 的显式退避重试。 */
    RETRY;

    /**
     * 返回审计/指标使用的大写名称。
     *
     * @return 名称
     */
    public String code() {
        return name().toUpperCase(Locale.ROOT);
    }
}
