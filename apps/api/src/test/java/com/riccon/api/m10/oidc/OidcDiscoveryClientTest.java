package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.oidc.domain.OidcConfigurationParser;
import com.riccon.api.oidc.domain.OidcDiscoveryException;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.domain.OidcProviderMetadata;
import com.riccon.api.oidc.service.OidcDiscoveryClient;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 发现文档客户端测试：懒加载、10 分钟缓存、失败 30 秒退避、issuer 一致性与无 userinfo。
 */
class OidcDiscoveryClientTest {

    /** 本地 stub。 */
    private static OidcStubProvider stub;

    /** 可前进时钟。 */
    private MutableClock clock;

    /** 被测客户端。 */
    private OidcDiscoveryClient client;

    /**
     * 启动 stub。
     *
     * @throws Exception 启动失败
     */
    @BeforeAll
    static void startStub() throws Exception {
        stub = new OidcStubProvider("a");
    }

    /**
     * 关闭 stub。
     */
    @AfterAll
    static void stopStub() {
        stub.close();
    }

    /** 每个测试使用独立缓存与时钟。 */
    @BeforeEach
    void setUp() {
        stub.reset();
        clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        client = new OidcDiscoveryClient(
                new ObjectMapper(), clock, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMinutes(10), true);
    }

    /** 首次解析成功，重复调用命中缓存且不触发 userinfo。 */
    @Test
    void resolve_cachesMetadata_forTenMinutes() {
        OidcIdpConfig idp = idp("https://ignored.example", stub.issuer("a"));

        OidcProviderMetadata first = client.resolve(idp);
        OidcProviderMetadata second = client.resolve(idp);

        assertThat(first.issuer()).isEqualTo(stub.issuer("a"));
        assertThat(first.authorizationEndpoint()).endsWith("/authorize");
        assertThat(first.tokenEndpoint()).endsWith("/token");
        assertThat(first.jwkSetUri()).endsWith("/jwks");
        assertThat(second).isEqualTo(first);
        assertThat(stub.discoveryCalls("a")).isEqualTo(1);
        assertThat(stub.userInfoCalls()).isZero();
    }

    /** 缓存过期后重新拉取。 */
    @Test
    void resolve_afterCacheTtl_refetches() {
        OidcIdpConfig idp = idp(null, stub.issuer("a"));
        client.resolve(idp);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        client.resolve(idp);

        assertThat(stub.discoveryCalls("a")).isEqualTo(2);
    }

    /** 失败进入 30 秒退避窗口，窗口内不再打 IdP。 */
    @Test
    void resolve_failure_isNegativeCachedForThirtySeconds() {
        OidcIdpConfig idp = idp(null, stub.issuer("a"));
        stub.setDiscoveryFails(true);

        assertThatThrownBy(() -> client.resolve(idp))
                .isInstanceOf(OidcDiscoveryException.class)
                .satisfies(exception -> assertThat(((OidcDiscoveryException) exception).reason())
                        .isEqualTo(OidcLoginFailureReason.IDP_UNAVAILABLE));
        assertThat(client.isUnavailable(stub.issuer("a"))).isTrue();
        assertThat(client.failureReason(stub.issuer("a"))).contains("idp_unavailable");

        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> client.resolve(idp)).isInstanceOf(OidcDiscoveryException.class);
        assertThat(stub.discoveryCalls("a")).isEqualTo(1);

        // 退避结束后允许重试；IdP 恢复后成功并清除失败状态。
        clock.advance(Duration.ofSeconds(21));
        stub.setDiscoveryFails(false);
        assertThat(client.resolve(idp).issuer()).isEqualTo(stub.issuer("a"));
        assertThat(client.isUnavailable(stub.issuer("a"))).isFalse();
        assertThat(stub.discoveryCalls("a")).isEqualTo(2);
    }

    /** 发现文档 issuer 与配置不一致 → issuer_mismatch。 */
    @Test
    void resolve_issuerMismatch_returnsIssuerMismatch() {
        OidcIdpConfig idp = idp(null, stub.issuer("a"));
        stub.setDiscoveryIssuerOverride("https://evil.example/realms/a");

        assertThatThrownBy(() -> client.resolve(idp))
                .isInstanceOf(OidcDiscoveryException.class)
                .satisfies(exception -> assertThat(((OidcDiscoveryException) exception).reason())
                        .isEqualTo(OidcLoginFailureReason.ISSUER_MISMATCH));
    }

    /** 不可达 IdP → idp_unavailable，且不抛出网络细节。 */
    @Test
    void resolve_unreachable_returnsIdpUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OidcIdpConfig idp = idp(null, "http://127.0.0.1:" + closedPort + "/realms/b");

        assertThatThrownBy(() -> client.resolve(idp))
                .isInstanceOf(OidcDiscoveryException.class)
                .hasMessage("idp_unavailable")
                .satisfies(exception -> assertThat(((OidcDiscoveryException) exception).reason())
                        .isEqualTo(OidcLoginFailureReason.IDP_UNAVAILABLE));
    }

    /**
     * 构造最小 IdP 配置；issuer 参数用于覆盖为任意地址。
     *
     * @param ignoredIssuer 未使用（保持测试可读）
     * @param issuer 实际 issuer
     * @return 配置
     */
    private static OidcIdpConfig idp(String ignoredIssuer, String issuer) {
        return new OidcIdpConfig(
                "a",
                OidcConfigurationParser.normalizeIssuer(issuer, true),
                "client",
                "secret",
                "Provider",
                List.of("openid"),
                null,
                true);
    }
}
