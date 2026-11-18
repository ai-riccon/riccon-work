package com.riccon.api.oidc.service;

import com.riccon.api.oidc.domain.OidcLoginRejectedException;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * 只从 ID Token 构建 OIDC 用户的 {@link OAuth2UserService}。
 *
 * <p>ADR-0019 第 2 节 F-011：不调用 userinfo。ID Token 的签名、iss、aud、exp 与 nonce 已由
 * {@code OidcAuthorizationCodeAuthenticationProvider} + JWKS 校验；本类只做业务校验（租户、绑定、禁用）
 * 并在任何拒绝时抛 {@link OAuth2AuthenticationException}，使认证在成功前失败。
 */
@Component
public class OidcUserServiceImpl implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** 登录业务校验。 */
    private final OidcLoginService loginService;

    /**
     * 创建 OIDC 用户服务。
     *
     * @param loginService 登录业务校验
     */
    public OidcUserServiceImpl(OidcLoginService loginService) {
        this.loginService = Objects.requireNonNull(loginService, "loginService must not be null");
    }

    /**
     * 构建 OIDC 用户；拒绝时在认证成功前抛出。
     *
     * @param userRequest OIDC 用户请求
     * @return 仅携带 ID Token 的用户
     * @throws OAuth2AuthenticationException 业务拒绝
     */
    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcIdToken idToken = userRequest.getIdToken();
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        String issuer = idToken.getIssuer() == null ? null : idToken.getIssuer().toExternalForm();
        try {
            loginService.validateLogin(registrationId, issuer, idToken.getSubject());
        } catch (OidcLoginRejectedException exception) {
            OAuth2Error error = new OAuth2Error(exception.reason().code(), "oidc login rejected", null);
            throw new OAuth2AuthenticationException(error, error.toString());
        }
        // 不请求 userinfo；默认 OIDC 用户仅从 ID Token 读取 claims，昵称/邮箱不参与绑定。
        return new DefaultOidcUser(List.of(), idToken, IdTokenClaimNames.SUB);
    }
}
