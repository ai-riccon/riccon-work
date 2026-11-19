package com.riccon.api.m10.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.auth.service.IdentityProviderResolver;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.oidc.config.OidcProperties;
import com.riccon.api.oidc.infrastructure.OidcDiscoveryClient;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link IdentityProviderResolver} 的脱敏与可区分性契约。
 *
 * <p>覆盖：LOCAL 不伪造来源；OIDC 优先配置 provider 名；未配置/不匹配回退 issuer host；同一租户两个 issuer 可区分；
 * 同一 issuer 结果稳定；host 之外不泄露路径、用户信息、查询参数、subject、token 或邮箱。
 */
class IdentityProviderResolverTest {

    /** 测试租户代码。 */
    private static final String TENANT = "tenant-alpha";

    /** 已配置的 issuer A。 */
    private static final String ISSUER_A = "https://idp.a.example/realms/alpha";

    /** 未配置的 issuer B，与 A 不同 host。 */
    private static final String ISSUER_B = "https://idp.b.example/realms/beta";

    /** 配置了 providerName 的注册表。 */
    private static final String CONFIGURED_IDPS = """
            [
              {"tenantCode":"%s","issuer":"%s","clientId":"client-a",
               "clientSecret":"test-secret-a","providerName":"Alpha 企业 IdP","enabled":true}
            ]
            """.formatted(TENANT, ISSUER_A);

    /** 空配置注册表。 */
    private static final String EMPTY_IDPS = "[]";

    /**
     * 构造只读解析器；注册表构造不访问网络。
     *
     * @param idpsJson IdP 配置 JSON
     * @return 解析器
     */
    private static IdentityProviderResolver resolver(String idpsJson) {
        OidcProperties properties = new OidcProperties();
        properties.getOidc().setIdps(idpsJson);
        properties.getOidc().setAllowHttp(true);
        OidcDiscoveryClient discoveryClient = new OidcDiscoveryClient(
                new ObjectMapper(),
                Clock.systemUTC(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                true);
        return new IdentityProviderResolver(new OidcIdpRegistry(properties, new ObjectMapper(), discoveryClient));
    }

    /**
     * 构造账号快照。
     *
     * @param authSource 认证来源
     * @param issuer 外部 issuer，可为空
     * @return 账号
     */
    private static UserAccount account(AuthSource authSource, String issuer) {
        return new UserAccount(
                UUID.randomUUID(),
                TENANT,
                "alice",
                "Alice",
                "alice@example.test",
                UserStatus.ACTIVE,
                authSource,
                issuer,
                Instant.parse("2026-11-19T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    /** LOCAL 账号不返回来源 IdP。 */
    @Test
    @DisplayName("LOCAL 返回 null")
    void localAccountReturnsNull() {
        assertThat(resolver(CONFIGURED_IDPS).resolve(account(AuthSource.LOCAL, null)))
                .isNull();
        // 防御性：历史脏数据即使带了 issuer，LOCAL 也不能被标成企业 IdP。
        assertThat(resolver(CONFIGURED_IDPS).resolve(account(AuthSource.LOCAL, ISSUER_A)))
                .isNull();
    }

    /** 配置 issuer 命中时使用 providerName。 */
    @Test
    @DisplayName("OIDC 命中配置 issuer 时返回 providerName")
    void oidcConfiguredIssuerUsesProviderName() {
        assertThat(resolver(CONFIGURED_IDPS).resolve(account(AuthSource.OIDC, ISSUER_A)))
                .isEqualTo("Alpha 企业 IdP");
    }

    /** 配置缺失时回退到 issuer host。 */
    @Test
    @DisplayName("OIDC 未配置时回退 issuer host")
    void oidcUnknownIssuerFallsBackToHost() {
        assertThat(resolver(EMPTY_IDPS).resolve(account(AuthSource.OIDC, ISSUER_A)))
                .isEqualTo("idp.a.example");
    }

    /** 配置 issuer 已替换时，历史账号回退 host，不被错误合并到新配置名。 */
    @Test
    @DisplayName("OIDC issuer 与配置不匹配时回退 host")
    void oidcStaleIssuerFallsBackToHost() {
        assertThat(resolver(CONFIGURED_IDPS).resolve(account(AuthSource.OIDC, ISSUER_B)))
                .isEqualTo("idp.b.example");
    }

    /** 同一租户两个 issuer 必须可区分；同一 issuer 结果稳定。 */
    @Test
    @DisplayName("同租户两个 issuer 可区分且同 issuer 稳定")
    void twoIssuersAreDistinguishable() {
        IdentityProviderResolver resolver = resolver(CONFIGURED_IDPS);
        String first = resolver.resolve(account(AuthSource.OIDC, ISSUER_A));
        String second = resolver.resolve(account(AuthSource.OIDC, ISSUER_B));

        assertThat(first).isEqualTo("Alpha 企业 IdP");
        assertThat(second).isEqualTo("idp.b.example");
        assertThat(first).isNotEqualTo(second);
        assertThat(resolver.resolve(account(AuthSource.OIDC, ISSUER_B))).isEqualTo(second);
    }

    /** http issuer 带非默认端口时保留端口，便于区分同 host 的不同 IdP。 */
    @Test
    @DisplayName("非默认端口保留在脱敏 host 中")
    void nonDefaultPortIsKept() {
        assertThat(resolver(EMPTY_IDPS).resolve(TENANT, AuthSource.OIDC, "http://127.0.0.1:18995/realms/alpha"))
                .isEqualTo("127.0.0.1:18995");
        // 显式默认端口与省略写法一致，避免同一 IdP 出现两种展示名。
        assertThat(resolver(EMPTY_IDPS).resolve(TENANT, AuthSource.OIDC, "https://idp.example:443/realms/a"))
                .isEqualTo("idp.example");
    }

    /** 脱敏边界：只取 host[:port]，不带 path/query/userinfo/fragment。 */
    @Test
    @DisplayName("不泄露 issuer 路径、用户信息、查询参数与 fragment")
    void stripsIssuerPathAndCredentials() {
        String label = resolver(EMPTY_IDPS)
                .resolve(TENANT, AuthSource.OIDC, "https://user:secret@idp.example:8443/realms/alpha?x=1#frag");

        assertThat(label).isEqualTo("idp.example:8443");
        assertThat(label)
                .doesNotContain("user")
                .doesNotContain("secret")
                .doesNotContain("realms")
                .doesNotContain("?")
                .doesNotContain("#");
    }

    /** 非 OIDC、空 issuer 与非法 issuer 都不输出原始字符串。 */
    @Test
    @DisplayName("空/非法 issuer 返回 null")
    void invalidInputsReturnNull() {
        IdentityProviderResolver resolver = resolver(EMPTY_IDPS);
        assertThat(resolver.resolve(TENANT, AuthSource.LOCAL, null)).isNull();
        assertThat(resolver.resolve(TENANT, AuthSource.OIDC, null)).isNull();
        assertThat(resolver.resolve(TENANT, AuthSource.OIDC, "   ")).isNull();
        assertThat(resolver.resolve(TENANT, AuthSource.OIDC, "not a uri")).isNull();
        assertThat(resolver.resolve(TENANT, AuthSource.OIDC, "/relative/path")).isNull();
    }

    /** 账号级入口与显式参数入口保持一致。 */
    @Test
    @DisplayName("账号级解析与显式参数一致")
    void accountOverloadMatchesExplicitOverload() {
        IdentityProviderResolver resolver = resolver(CONFIGURED_IDPS);
        UserAccount user = account(AuthSource.OIDC, ISSUER_A);

        assertThat(resolver.resolve(user)).isEqualTo(resolver.resolve(TENANT, AuthSource.OIDC, ISSUER_A));
    }
}
