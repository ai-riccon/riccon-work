package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.model.HostResolver;
import com.riccon.worker.connector.domain.ConnectorEndpointConfig;
import com.riccon.worker.connector.domain.ConnectorEndpointPolicy;
import com.riccon.worker.connector.domain.ConnectorEndpointRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 连接器端点 SSRF 策略：HTTPS/allowHttp、userinfo/query、元数据/链路本地、私网 DNS 正反例与重定向目标。
 */
class ConnectorEndpointPolicyTest {

    /** 固定 DNS 映射：storage.internal → 10.1.2.3（RFC1918），evil.internal → 10.9.9.9。 */
    private static final HostResolver RESOLVER = host -> switch (host) {
        case "storage.internal" -> new InetAddress[] {InetAddress.getByName("10.1.2.3")};
        case "evil.internal" -> new InetAddress[] {InetAddress.getByName("10.9.9.9")};
        default -> InetAddress.getAllByName(host);
    };

    /** 公网测试解析器；unknown.invalid 不可解析。 */
    private static final HostResolver PUBLIC_RESOLVER = host -> {
        if ("unknown.invalid".equals(host)) {
            throw new UnknownHostException(host);
        }
        return InetAddress.getAllByName(host);
    };

    @Test
    void load_allowlistPrivateDnsAccepted() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        ConnectorEndpointRegistry registry = policy.load(
                List.of(new ConnectorEndpointConfig("s3", "https://storage.internal:9000", "内部 S3", false, true)));
        assertThat(registry.findById("s3")).isPresent();
        assertThat(registry.endpoints()).hasSize(1);
    }

    @Test
    void load_rejectsHttpUnlessExplicitAndProductionHttp() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(PUBLIC_RESOLVER);
        assertThatThrownBy(() -> policy.load(
                        List.of(new ConnectorEndpointConfig("s3", "http://127.0.0.1:9000", "local", false, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> policy.load(
                        List.of(new ConnectorEndpointConfig("s3", "http://127.0.0.1:9000", "local", true, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production");
        assertThat(policy.load(List.of(
                                new ConnectorEndpointConfig("s3", "http://127.0.0.1:9000", "local", true, false)))
                        .endpoints())
                .hasSize(1);
    }

    @Test
    void load_rejectsMetadataLinkLocalAndMalformedUrls() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(PUBLIC_RESOLVER);
        assertRejected(policy, "https://169.254.169.254/latest/meta-data");
        assertRejected(policy, "https://[::1]/");
        assertRejected(policy, "https://[fe80::1]/");
        assertRejected(policy, "https://user:pass@example.com/");
        assertRejected(policy, "https://example.com/?token=1");
        assertRejected(policy, "https://example.com/#fragment");
        assertRejected(policy, "ftp://example.com/");
        assertRejected(policy, "https://unknown.invalid/");
    }

    @Test
    void load_rejectsDuplicateIdsAndUrls() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        assertThatThrownBy(() -> policy.load(List.of(
                        new ConnectorEndpointConfig("s3", "https://storage.internal", "a", false, true),
                        new ConnectorEndpointConfig("s3", "https://storage.internal", "b", false, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void redirectTarget_unlistedPrivateHostRejected_allowlistedHostAccepted() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(RESOLVER);
        ConnectorEndpointRegistry registry = policy.load(
                List.of(new ConnectorEndpointConfig("s3", "https://storage.internal", "内部 S3", false, true)));
        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("https://evil.internal/object"), registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
        policy.verifyRedirectTarget(URI.create("https://storage.internal/object"), registry);
    }

    /**
     * 断言 URL 被拒绝。
     *
     * @param policy 策略
     * @param url URL
     */
    private static void assertRejected(ConnectorEndpointPolicy policy, String url) {
        assertThatThrownBy(() -> policy.load(List.of(new ConnectorEndpointConfig("s3", url, "test", false, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
