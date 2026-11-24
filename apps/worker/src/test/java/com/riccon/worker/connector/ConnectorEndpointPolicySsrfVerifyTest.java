package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.model.HostResolver;
import com.riccon.worker.connector.domain.ConnectorEndpointConfig;
import com.riccon.worker.connector.domain.ConnectorEndpointPolicy;
import com.riccon.worker.connector.domain.ConnectorEndpointRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 连接器 SSRF 补充矩阵：允许列表 DNS 解析到元数据/链路本地、字面量元数据与运行期 DNS 重绑定。
 *
 * <p>现有 {@code ConnectorEndpointPolicyTest} 已覆盖 HTTPS/allowHttp、userinfo/query/fragment、常见元数据字面量
 * 与未列入主机；本类补齐“允许列表主机名解析到元数据”和“启动后 DNS 被改写”的负向路径，对应 SEC-CONN-003。
 */
class ConnectorEndpointPolicySsrfVerifyTest {

    /** 元数据/链路本地 DNS 映射；其余主机返回公网测试地址。 */
    private static final HostResolver METADATA_RESOLVER = host -> switch (host) {
        case "metadata.internal" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        case "link-local.internal" -> new InetAddress[] {InetAddress.getByName("fe80::1")};
        case "loopback.internal" -> new InetAddress[] {InetAddress.getByName("::1")};
        case "aws-metadata.internal" -> new InetAddress[] {InetAddress.getByName("fd00:ec2::254")};
        case "aliyun-metadata.internal" -> new InetAddress[] {InetAddress.getByName("100.100.100.200")};
        case "oracle-metadata.internal" -> new InetAddress[] {InetAddress.getByName("192.0.0.192")};
        default -> new InetAddress[] {InetAddress.getByName("8.8.8.8")};
    };

    @Test
    void load_rejectsAllowlistedHostResolvingToMetadataOrLinkLocal() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(METADATA_RESOLVER);
        for (String host : List.of(
                "metadata.internal",
                "link-local.internal",
                "loopback.internal",
                "aws-metadata.internal",
                "aliyun-metadata.internal",
                "oracle-metadata.internal")) {
            assertThatThrownBy(() -> policy.load(
                            List.of(new ConnectorEndpointConfig("s3", "https://" + host, "测试", false, true))))
                    .as(host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validateUrl_rejectsLiteralMetadataVariants() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(METADATA_RESOLVER);
        for (String url : List.of(
                "https://100.100.100.200/",
                "https://192.0.0.192/",
                "https://[fd00:ec2::254]/",
                "https://[::ffff:169.254.169.254]/",
                "https://[fe80::abcd]/",
                "https://[::1]/")) {
            assertThatThrownBy(() -> policy.validateUrl(url, "s3", true))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void runtimeDnsRebinding_rejectedByRedirectAndSyncVerification() throws Exception {
        AtomicReference<InetAddress[]> addresses =
                new AtomicReference<>(new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        HostResolver mutable = host -> addresses.get();
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(mutable);
        ConnectorEndpointRegistry registry = policy.load(
                List.of(new ConnectorEndpointConfig("s3", "https://storage.internal", "内部 S3", false, true)));
        URI endpoint = URI.create("https://storage.internal");

        policy.verifyResolvedHost(endpoint, "s3");
        policy.verifyRedirectTarget(endpoint, registry);

        addresses.set(new InetAddress[] {InetAddress.getByName("169.254.169.254")});
        assertThatThrownBy(() -> policy.verifyResolvedHost(endpoint, "s3"))
                .as("同步/连接测试前的运行期复核必须拒绝重绑定后的元数据地址")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.verifyRedirectTarget(URI.create("https://storage.internal/object"), registry))
                .as("允许列表主机名重绑定后，重定向目标校验也必须 fail-closed")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
