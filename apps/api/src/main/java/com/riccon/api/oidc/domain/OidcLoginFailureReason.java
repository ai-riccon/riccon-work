package com.riccon.api.oidc.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * OIDC 登录失败的稳定原因码。
 *
 * <p>原因码面向登录页，不包含 IdP 原始错误、code、state、token、内部地址或堆栈（ADR-0019 第 4 节）。
 * 只有账号绑定/禁用/issuer 不一致计入登录失败限流；IdP 故障、用户取消与 state 失效不计入，避免把正常用户锁死。
 */
public enum OidcLoginFailureReason {

    /** 发现文档或 token 端点不可用。 */
    IDP_UNAVAILABLE("idp_unavailable", false),
    /** state 缺失、过期、被重放或回调不属于本会话。 */
    STATE_INVALID("state_invalid", false),
    /** 用户在 IdP 拒绝授权。 */
    ACCESS_DENIED("access_denied", false),
    /** 账号未开通或未绑定该 OIDC 身份。 */
    ACCOUNT_NOT_BOUND("account_not_bound", true),
    /** 账号已禁用。 */
    ACCOUNT_DISABLED("account_disabled", true),
    /** 租户不存在或已暂停。 */
    TENANT_UNAVAILABLE("tenant_unavailable", false),
    /** 发现文档或身份 issuer 与租户配置不一致。 */
    ISSUER_MISMATCH("issuer_mismatch", true),
    /** 登录限流触发。 */
    RATE_LIMITED("rate_limited", false),
    /** 其他登录失败（协议错误、ID Token 非法等）。 */
    LOGIN_FAILED("login_failed", false);

    /** 面向 UI 的稳定原因码。 */
    private final String code;

    /** 是否计入登录失败限流。 */
    private final boolean countsAsRateLimitFailure;

    /**
     * 创建原因码。
     *
     * @param code 稳定原因码
     * @param countsAsRateLimitFailure 是否计入登录失败限流
     */
    OidcLoginFailureReason(String code, boolean countsAsRateLimitFailure) {
        this.code = code;
        this.countsAsRateLimitFailure = countsAsRateLimitFailure;
    }

    /**
     * 返回稳定原因码。
     *
     * @return 面向 UI 的原因码
     */
    public String code() {
        return code;
    }

    /**
     * 判断是否应累计一次登录失败（用于 IP + tenantCode 限流）。
     *
     * @return 需要累计时返回 {@code true}
     */
    public boolean countsAsRateLimitFailure() {
        return countsAsRateLimitFailure;
    }

    /**
     * 按原因码查找枚举；未知值返回空。
     *
     * @param code 原因码
     * @return 匹配的枚举
     */
    public static Optional<OidcLoginFailureReason> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(reason -> reason.code.equals(code))
                .findFirst();
    }
}
