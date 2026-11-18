package com.riccon.api.oidc.controller;

import com.riccon.api.oidc.domain.OidcDiscoveryException;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.domain.OidcLoginRejectedException;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * 把认证异常映射为登录页稳定原因码。
 *
 * <p>只输出 ADR-0019 第 4 节列出的原因码，不输出 IdP 原始错误、code、state、token 或内部地址。
 * 映射顺序：业务拒绝 → 我方原因码（直接作为 OAuth2 error code 抛出）→ 发现失败 → Spring 协议错误 →
 * 注册表失败退避 → 通用 {@code login_failed}。
 */
public final class OidcFailureReasonMapper {

    /** Spring 错误码：用户拒绝授权。 */
    private static final String ACCESS_DENIED = "access_denied";

    /** Spring 错误码：state 缺失/不匹配/过期。 */
    private static final String INVALID_STATE = "invalid_state_parameter";

    /** Spring 错误码：授权请求已被消费（重放/过期）。 */
    private static final String AUTHORIZATION_REQUEST_NOT_FOUND = "authorization_request_not_found";

    /** Spring 错误码：回调参数不完整。 */
    private static final String INVALID_REQUEST = "invalid_request";

    /** Spring 错误码：注册 ID 不存在。 */
    private static final String CLIENT_REGISTRATION_NOT_FOUND = "client_registration_not_found";

    /** Spring 错误码：注册 ID 非法。 */
    private static final String INVALID_CLIENT_REGISTRATION = "invalid_client_registration";

    /** Spring 错误码：token 响应非法。 */
    private static final String INVALID_TOKEN_RESPONSE = "invalid_token_response";

    /** Spring 错误码：IdP 服务端错误。 */
    private static final String SERVER_ERROR = "server_error";

    /** Spring 错误码：IdP 暂时不可用。 */
    private static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

    /** Spring 错误码：客户端认证失败。 */
    private static final String INVALID_CLIENT = "invalid_client";

    /** 工具类禁止实例化。 */
    private OidcFailureReasonMapper() {}

    /**
     * 映射失败原因。
     *
     * @param throwable 认证异常
     * @param registry IdP 注册表
     * @param registrationId 注册 ID；可为空
     * @return 稳定原因码
     */
    public static OidcLoginFailureReason map(Throwable throwable, OidcIdpRegistry registry, String registrationId) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof OidcLoginRejectedException rejected) {
                return rejected.reason();
            }
            if (current instanceof OidcDiscoveryException discovery) {
                return discovery.reason();
            }
            if (current instanceof OAuth2AuthenticationException oauth2) {
                OidcLoginFailureReason mapped =
                        fromOAuth2ErrorCode(oauth2.getError().getErrorCode());
                if (mapped != null) {
                    return mapped;
                }
            }
            current = current.getCause();
        }
        if (registrationId != null && registry.isDiscoveryUnavailable(registrationId)) {
            return OidcLoginFailureReason.IDP_UNAVAILABLE;
        }
        return OidcLoginFailureReason.LOGIN_FAILED;
    }

    /**
     * 把 Spring OAuth2 错误码映射为原因码。
     *
     * @param errorCode Spring 错误码
     * @return 原因码；未知时返回 {@code null} 继续遍历 cause
     */
    private static OidcLoginFailureReason fromOAuth2ErrorCode(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        // 业务拒绝直接以我方原因码作为 error code，优先识别。
        OidcLoginFailureReason businessReason =
                OidcLoginFailureReason.fromCode(errorCode).orElse(null);
        if (businessReason != null) {
            return businessReason;
        }
        return switch (errorCode) {
            case ACCESS_DENIED -> OidcLoginFailureReason.ACCESS_DENIED;
            case INVALID_STATE, AUTHORIZATION_REQUEST_NOT_FOUND, INVALID_REQUEST ->
                OidcLoginFailureReason.STATE_INVALID;
            case INVALID_TOKEN_RESPONSE, SERVER_ERROR, TEMPORARILY_UNAVAILABLE, INVALID_CLIENT ->
                OidcLoginFailureReason.IDP_UNAVAILABLE;
            // 注册 ID 不存在可能是“配置已移除”或“发现失败导致注册无法构建”；留到循环结束后结合
            // 注册表失败退避判断，避免把 IdP 故障误报成通用 login_failed。
            case CLIENT_REGISTRATION_NOT_FOUND, INVALID_CLIENT_REGISTRATION -> null;
            default -> null;
        };
    }
}
