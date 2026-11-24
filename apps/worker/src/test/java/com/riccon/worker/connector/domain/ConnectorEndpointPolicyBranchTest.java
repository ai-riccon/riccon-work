package com.riccon.worker.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.model.HostResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ConnectorEndpointPolicy 分支矩阵：覆盖结构校验、IP 字面量、DNS 解析、重定向与 URL 归一化的边界。
 *
 * <p>本类只通过公开入口触发策略；每条用例对应一种部署配置或重定向输入，不依赖网络。
 */
class ConnectorEndpointPolicyBranchTest {

    /** 测试 DNS：所有非特殊主机返回 10.1.2.3。 */
    private static final HostResolver RESOLVER = host -> switch (host) {
        case "unknown.invalid" -> throw new UnknownHostException(host);
        case "null-address" -> new InetAddress[] {null};
        case "empty" -> new InetAddress[0];
        case "metadata" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        case "link-local" -> new InetAddress[] {InetAddress.getByName("fe80::1")};
        case "null-and-public" -> new InetAddress[] {null, InetAddress.getByName("8.8.8.8")};
        default -> new InetAddress[] {InetAddress.getByName("10.1.2.3")};
    };

    @Test
    void load_handlesNullEmptyAndInvalidEntries() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        assertThat(policy.load(null).isEmpty()).isTrue();
        assertThat(policy.load(List.of()).isEmpty()).isTrue();

        assertInvalidLoad(policy, null, "null entry");
        assertInvalidLoad(
                policy, new ConnectorEndpointConfig(null, "https://a.internal", "label", false, true), "null id");
        assertInvalidLoad(
                policy, new ConnectorEndpointConfig("", "https://a.internal", "label", false, true), "blank id");
        assertInvalidLoad(
                policy, new ConnectorEndpointConfig("A", "https://a.internal", "label", false, true), "uppercase id");
        assertInvalidLoad(
                policy,
                new ConnectorEndpointConfig("a".repeat(65), "https://a.internal", "label", false, true),
                "long id");
        assertInvalidLoad(
                policy, new ConnectorEndpointConfig("s3", "https://a.internal", null, false, true), "null label");
        assertInvalidLoad(
                policy, new ConnectorEndpointConfig("s3", "https://a.internal", " ", false, true), "blank label");
        assertInvalidLoad(
                policy,
                new ConnectorEndpointConfig("s3", "https://a.internal", "x".repeat(65), false, true),
                "long label");
        assertInvalidLoad(
                policy,
                new ConnectorEndpointConfig("s3", "https://a.internal", "label", true, true),
                "production http");
    }

    @Test
    void load_duplicateIdsAndNormalizedUrlsAreRejected() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        assertThatThrownBy(() -> policy.load(List.of(
                        new ConnectorEndpointConfig("s3", "https://a.internal", "a", false, true),
                        new ConnectorEndpointConfig("s3", "https://b.internal", "b", false, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate connector endpoint id");
        assertThatThrownBy(() -> policy.load(List.of(
                        new ConnectorEndpointConfig("a", "https://a.internal", "a", false, true),
                        new ConnectorEndpointConfig("b", "https://A.INTERNAL:443/", "b", false, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate connector endpoint url");
    }

    @Test
    void validateUrl_rejectsStructuralAndPortBoundaries() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        assertThatThrownBy(() -> policy.validateUrl(null, "s3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateUrl(" ", "s3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateUrl(" https://example.com", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> policy.validateUrl("https://exa\u0000mple.com", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> policy.validateUrl("/relative", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> policy.validateUrl("mailto:a@b", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> policy.validateUrl("https://[::gg]/", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid URI");
        assertThatThrownBy(() -> policy.validateUrl("ftp://example.com/", "s3", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        assertThatThrownBy(() -> policy.validateUrl("https://example.com:0/", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid port");
        assertThatThrownBy(() -> policy.validateUrl("https://example.com:65536/", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid port");
        assertThatThrownBy(() -> policy.validateUrl("https://user:pass@example.com/", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
        assertThatThrownBy(() -> policy.validateUrl("https://example.com/?a=1", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> policy.validateUrl("https://example.com/#x", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragment");
        assertThatThrownBy(() -> policy.validateUrl("https:///path", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> policy.validateUrl("https://metadata.google.internal/", "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");
    }

    @Test
    void validateUrl_acceptsIpLiteralAndHttpExceptionVariants() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        assertThat(policy.validateUrl("HTTPS://example.com/", "s3")).isNotNull();
        assertThat(policy.validateUrl("http://127.0.0.1:9000/", "s3", true)).isNotNull();
        assertThat(policy.validateUrl("https://[2001:db8::1]/x", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://2130706433/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://0x7f000001/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://0177.0.0.1/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://[::ffff:10.0.0.1]/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://[::]/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://999.1.1.1/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://0xGG.1.1.1/", "s3")).isNotNull();
        assertThat(policy.validateUrl("https://[::ffff:127.0.0.1]/", "s3")).isNotNull();
        assertThatThrownBy(() -> policy.validateUrl("https://[::ffff:169.254.169.254]/", "s3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateUrl("https://[fe80::1]/", "s3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyResolvedHost_handlesNullOrInvalidDnsResults() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        assertThatThrownBy(() -> policy.verifyResolvedHost(URI.create("https:///path"), "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> policy.verifyResolvedHost(URI.create("https://unknown.invalid"), "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolved");
        assertThatThrownBy(() -> policy.verifyResolvedHost(URI.create("https://empty"), "s3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no address");
        assertThatThrownBy(() -> policy.verifyResolvedHost(URI.create("https://metadata"), "s3"))
                .isInstanceOf(IllegalArgumentException.class);
        policy.verifyResolvedHost(URI.create("https://null-address"), "s3");
        policy.verifyResolvedHost(URI.create("https://null-and-public"), "s3");
        policy.verifyResolvedHost(URI.create("https://public"), "s3");
    }

    @Test
    void verifyRedirectTarget_handlesSchemeHostAndDnsBranches() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        ConnectorEndpointRegistry registry = policy.load(List.of(
                new ConnectorEndpointConfig("s3", "https://storage.internal", "存储", false, true),
                new ConnectorEndpointConfig("other", "https://other.internal", "其他", false, true)));

        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("ftp://storage.internal/x"), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("https:///x"), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("https://evil.internal/x"), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
        policy.verifyRedirectTarget(URI.create("https://storage.internal/x"), registry);

        ConnectorEndpointRegistry literalMetadata = new ConnectorEndpointRegistry(
                List.of(new ConnectorEndpointConfig("meta", "https://169.254.169.254", "meta", false, true)));
        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("https://169.254.169.254/x"), literalMetadata))
                .isInstanceOf(IllegalArgumentException.class);
        ConnectorEndpointRegistry unknownHost = new ConnectorEndpointRegistry(
                List.of(new ConnectorEndpointConfig("unknown", "https://unknown.invalid", "unknown", false, true)));
        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("https://unknown.invalid/x"), unknownHost))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolved");
    }

    @Test
    void normalizeUrlAndExtractHost_coverFallbackParsing() {
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https://EXAMPLE.COM:443")))
                .isEqualTo("https://example.com/");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("http://EXAMPLE.COM:80/x")))
                .isEqualTo("http://example.com/x");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https://example.com:8443/x")))
                .isEqualTo("https://example.com:8443/x");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https://example.com./x")))
                .isEqualTo("https://example.com/x");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https://[2001:db8::1]/x")))
                .isEqualTo("https://[2001:db8::1]/x");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https://host_/")))
                .isEqualTo("https://host_/");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https:///path")))
                .isEqualTo("https:///path");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("//example.com/path")))
                .isEqualTo("://example.com/path");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("https://example.com:0/")))
                .isEqualTo("https://example.com:0/");
        assertThat(ConnectorEndpointPolicy.normalizeUrl(URI.create("http://example.com:443/")))
                .isEqualTo("http://example.com:443/");

        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://host_/")))
                .isEqualTo("host_");
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://user@host_/")))
                .isEqualTo("host_");
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://host_:443/")))
                .isEqualTo("host_");
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://host_:bad/")))
                .isEqualTo("host_:bad");
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://user@host_:443/")))
                .isEqualTo("host_");
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https:///path")))
                .isNull();
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://user@/path")))
                .isEmpty();
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("//:443/path")))
                .isEmpty();
        assertThat(ConnectorEndpointPolicy.extractHost(URI.create("https://user:pass@exa_mple.com:443/x")))
                .isEqualTo("exa_mple.com");
        assertThat(ConnectorEndpointPolicy.normalizeHost("[EXAMPLE.COM.]")).isEqualTo("example.com");
        assertThat(ConnectorEndpointPolicy.normalizeHost(null)).isEmpty();
        assertThat(ConnectorEndpointPolicy.normalizeHost("  ")).isEmpty();
    }

    /**
     * 断言单个端点条目被拒绝。
     *
     * @param policy 策略
     * @param config 条目
     * @param reason 说明
     */
    private static void assertInvalidLoad(
            ConnectorEndpointPolicy policy, ConnectorEndpointConfig config, String reason) {
        assertThatThrownBy(() -> policy.load(java.util.Arrays.asList(config)))
                .as(reason)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
