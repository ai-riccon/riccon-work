package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.oidc.service.OidcAwareSecurityContextRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * OIDC 感知安全上下文仓储：OIDC 主体绝不落会话，历史残留读取时清理。
 */
class OidcAwareSecurityContextRepositoryTest {

    /** 被测仓储。 */
    private OidcAwareSecurityContextRepository repository;

    /** 请求。 */
    private MockHttpServletRequest request;

    /** 响应。 */
    private MockHttpServletResponse response;

    /** 每个测试使用干净仓储与请求。 */
    @BeforeEach
    void setUp() {
        repository = new OidcAwareSecurityContextRepository(new HttpSessionSecurityContextRepository());
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.getSession(true);
    }

    /** OIDC 认证只留在当前线程，不写会话。 */
    @Test
    void saveContext_oidcAuthentication_isNotPersisted() {
        SecurityContext context = context(oidcAuthentication());
        repository.saveContext(context, request, response);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(request.getSession(false)
                        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    /** RicconPrincipal 认证照常持久化。 */
    @Test
    void saveContext_ricconPrincipal_isPersisted() {
        SecurityContext context = context(ricconAuthentication());
        repository.saveContext(context, request, response);

        assertThat(request.getSession(false)
                        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isEqualTo(context);
    }

    /** 历史/异常残留的 OIDC 上下文在读取时被清理（F-029）。 */
    @Test
    void loadContext_legacyOidcContext_returnsEmptyAndRemovesAttribute() {
        request.getSession(false)
                .setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        context(oidcAuthentication()));

        SecurityContext loaded = repository.loadContext(new HttpRequestResponseHolder(request, response));

        assertThat(loaded.getAuthentication()).isNull();
        assertThat(request.getSession(false)
                        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    /** 保存空上下文会删除会话属性且不创建新会话。 */
    @Test
    void saveContext_emptyContext_removesAttribute() {
        request.getSession(false)
                .setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        context(ricconAuthentication()));
        repository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);

        assertThat(request.getSession(false)
                        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    /** containsContext 委托实际仓储。 */
    @Test
    void containsContext_delegatesToSession() {
        assertThat(repository.containsContext(request)).isFalse();
        repository.saveContext(context(ricconAuthentication()), request, response);
        assertThat(repository.containsContext(request)).isTrue();
    }

    /**
     * 构造 OIDC 认证。
     *
     * @return OIDC 认证
     */
    private static OAuth2AuthenticationToken oidcAuthentication() {
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value", Instant.now(), Instant.now().plusSeconds(300), Map.of("sub", "subject"));
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        return new OAuth2AuthenticationToken(oidcUser, List.of(), "tenant-acme");
    }

    /**
     * 构造 Riccon 主体认证。
     *
     * @return 会话认证
     */
    private static UsernamePasswordAuthenticationToken ricconAuthentication() {
        RicconPrincipal principal = new RicconPrincipal(UUID.randomUUID(), "acme", "user", "User");
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    /**
     * 包装认证为安全上下文。
     *
     * @param authentication 认证
     * @return 安全上下文
     */
    private static SecurityContext context(org.springframework.security.core.Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        return context;
    }
}
