package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.api.oidc.controller.OidcFailureReasonMapper;
import com.riccon.api.oidc.domain.OidcDiscoveryException;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.domain.OidcLoginRejectedException;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** 失败原因码映射：只输出稳定原因码。 */
class OidcFailureReasonMapperTest {

    /** 注册表（仅用于发现失败判断）。 */
    private final OidcIdpRegistry registry = mock(OidcIdpRegistry.class);

    /** 业务拒绝直接映射。 */
    @Test
    void map_businessRejection_returnsReason() {
        assertThat(OidcFailureReasonMapper.map(
                        new OidcLoginRejectedException(OidcLoginFailureReason.ACCOUNT_NOT_BOUND), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.ACCOUNT_NOT_BOUND);
    }

    /** Spring 协议错误：access_denied。 */
    @Test
    void map_accessDenied_returnsAccessDenied() {
        assertThat(OidcFailureReasonMapper.map(oauth2("access_denied"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.ACCESS_DENIED);
    }

    /** Spring 协议错误：state 缺失/重放/过期统一 state_invalid。 */
    @Test
    void map_stateErrors_returnStateInvalid() {
        assertThat(OidcFailureReasonMapper.map(oauth2("invalid_state_parameter"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.STATE_INVALID);
        assertThat(OidcFailureReasonMapper.map(oauth2("authorization_request_not_found"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.STATE_INVALID);
    }

    /** token 端点/IdP 故障返回 idp_unavailable。 */
    @Test
    void map_tokenEndpointFailure_returnsIdpUnavailable() {
        assertThat(OidcFailureReasonMapper.map(oauth2("invalid_token_response"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.IDP_UNAVAILABLE);
        assertThat(OidcFailureReasonMapper.map(oauth2("temporarily_unavailable"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.IDP_UNAVAILABLE);
    }

    /** 未知注册在发现失败退避窗口内映射 idp_unavailable。 */
    @Test
    void map_unknownWithDiscoveryFailure_returnsIdpUnavailable() {
        when(registry.isDiscoveryUnavailable("tenant-a")).thenReturn(true);

        assertThat(OidcFailureReasonMapper.map(oauth2("client_registration_not_found"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.IDP_UNAVAILABLE);
    }

    /** 发现异常原因直接透传。 */
    @Test
    void map_discoveryException_returnsReason() {
        assertThat(OidcFailureReasonMapper.map(
                        new OidcDiscoveryException(OidcLoginFailureReason.ISSUER_MISMATCH), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.ISSUER_MISMATCH);
    }

    /** 未识别异常统一 login_failed。 */
    @Test
    void map_unknown_returnsLoginFailed() {
        assertThat(OidcFailureReasonMapper.map(new IllegalStateException("boom"), registry, "tenant-a"))
                .isEqualTo(OidcLoginFailureReason.LOGIN_FAILED);
        assertThat(OidcFailureReasonMapper.map(new IllegalStateException("boom"), registry, null))
                .isEqualTo(OidcLoginFailureReason.LOGIN_FAILED);
    }

    /**
     * 构造 OAuth2 认证异常。
     *
     * @param code 错误码
     * @return 异常
     */
    private static OAuth2AuthenticationException oauth2(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
