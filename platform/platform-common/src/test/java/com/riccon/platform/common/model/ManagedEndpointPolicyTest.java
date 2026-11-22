package com.riccon.platform.common.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 受管端点 URL 策略矩阵（SEC-M04-001/002、IT-MODEL-107 的单元层）。
 *
 * <p>测试只使用固定 {@link HostResolver} 映射与字面量地址，不依赖外部 DNS、不发真实出网。
 * 覆盖：HTTPS/HTTP 开关、userinfo/query/fragment、回环、云元数据与链路本地、十进制/十六进制/八进制 IP、
 * IPv4-mapped IPv6、末尾点 FQDN、重复项、生产/演示标签与遗留 DB 目录兼容。
 */
class ManagedEndpointPolicyTest {

    /** 生产策略：固定解析器把内网域名映射到私网地址。 */
    private static final HostResolver RESOLVER = resolver(Map.of(
            "llm.corp.example", "10.20.30.40",
            "metadata.evil.example", "169.254.169.254",
            "loop.evil.example", "127.0.0.1",
            "v6.evil.example", "fd00:ec2::254"));

    /** 构造固定映射解析器；未列出的主机名视为无法解析。 */
    private static HostResolver resolver(Map<String, String> mapping) {
        return host -> {
            String value = mapping.get(host);
            if (value == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(value)};
        };
    }

    /** 生产 HTTPS 私网字面量允许。 */
    @Test
    @DisplayName("生产模式允许 HTTPS 私网地址")
    void validateUrl_allowsHttpsPrivateAddressInProduction() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertThat(policy.validateUrl("https://10.20.30.40/v1", "baseUrl")).isNotNull();
        assertThat(policy.validateConfiguredUrl("https://10.20.30.40/v1", "baseUrl"))
                .isNotNull();
    }

    /** 生产模式拒绝明文 HTTP。 */
    @Test
    @DisplayName("生产模式拒绝 HTTP")
    void validateUrl_rejectsHttpInProduction() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertReason(
                () -> policy.validateUrl("http://10.20.30.40/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_SCHEME_NOT_ALLOWED);
    }

    /** DEMO + allow-http 才允许 HTTP；缺任一条件都拒绝。 */
    @Test
    @DisplayName("HTTP 仅在 DEMO + allow-http 下允许")
    void validateUrl_allowsHttpOnlyInDemoWithSwitch() {
        ManagedEndpointPolicy demo = new ManagedEndpointPolicy(ModelDeploymentMode.DEMO, true, RESOLVER);
        assertThat(demo.validateUrl("http://127.0.0.1:19090/v1", "baseUrl")).isNotNull();
        assertReason(
                () -> new ManagedEndpointPolicy(ModelDeploymentMode.DEMO, false, RESOLVER)
                        .validateUrl("http://127.0.0.1:19090/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_SCHEME_NOT_ALLOWED);
        assertReason(
                () -> new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, true, RESOLVER),
                EndpointRejectionReason.ENDPOINT_SCHEME_NOT_ALLOWED);
    }

    /** userinfo、query、fragment 分别返回稳定原因。 */
    @Test
    @DisplayName("拒绝 userinfo/query/fragment")
    void validateUrl_rejectsForbiddenUrlParts() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertReason(
                () -> policy.validateUrl("https://user:pass@10.20.30.40/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_USERINFO_FORBIDDEN);
        assertReason(
                () -> policy.validateUrl("https://10.20.30.40/v1?token=x", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_QUERY_FORBIDDEN);
        assertReason(
                () -> policy.validateUrl("https://10.20.30.40/v1#frag", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_FRAGMENT_FORBIDDEN);
    }

    /** 云元数据与链路本地地址永久拒绝，包含编码 IP 与末尾点 FQDN。 */
    @Test
    @DisplayName("拒绝云元数据/链路本地地址（含 IP 归一化）")
    void validateUrl_rejectsMetadataAddresses() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        List<String> metadataUrls = List.of(
                "https://169.254.169.254/v1",
                "https://169.254.170.2/v1",
                "https://100.100.100.200/v1",
                "https://192.0.0.192/v1",
                "https://0xa9.0xfe.0xa9.0xfe/v1",
                "https://0251.0376.0251.0376/v1",
                "https://2852039166/v1",
                "https://0xa9fea9fe/v1",
                "https://[::ffff:a9fe:a9fe]/v1",
                "https://[fd00:ec2::254]/v1",
                "https://metadata.google.internal./v1");
        for (String url : metadataUrls) {
            assertReason(() -> policy.validateUrl(url, "baseUrl"), EndpointRejectionReason.ENDPOINT_METADATA_FORBIDDEN);
        }
    }

    /** 生产拒绝回环；DEMO 允许回环（配合显式 HTTP 开关）。 */
    @Test
    @DisplayName("生产拒绝回环，DEMO 允许回环")
    void validateUrl_rejectsLoopbackOnlyInProduction() {
        ManagedEndpointPolicy production = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        for (String url :
                List.of("https://127.0.0.1/v1", "https://127.1.2.3/v1", "https://localhost/v1", "https://[::1]/v1")) {
            assertReason(
                    () -> production.validateUrl(url, "baseUrl"), EndpointRejectionReason.ENDPOINT_LOOPBACK_FORBIDDEN);
        }
        ManagedEndpointPolicy demo = new ManagedEndpointPolicy(ModelDeploymentMode.DEMO, true, RESOLVER);
        assertThat(demo.validateUrl("http://127.0.0.1:19090/v1", "baseUrl")).isNotNull();
    }

    /** DNS 解析到元数据或生产回环时拒绝。 */
    @Test
    @DisplayName("DNS 解析到元数据/回环时拒绝")
    void validateConfiguredUrl_rejectsResolvedForbiddenAddresses() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertReason(
                () -> policy.validateConfiguredUrl("https://metadata.evil.example/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_METADATA_FORBIDDEN);
        assertReason(
                () -> policy.validateConfiguredUrl("https://loop.evil.example/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_LOOPBACK_FORBIDDEN);
        assertReason(
                () -> policy.validateConfiguredUrl("https://v6.evil.example/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_METADATA_FORBIDDEN);
    }

    /** 私网 DNS 解析允许；无法解析时启动失败。 */
    @Test
    @DisplayName("私网 DNS 允许，无法解析拒绝")
    void validateConfiguredUrl_allowsPrivateDnsAndRejectsUnresolved() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertThat(policy.validateConfiguredUrl("https://llm.corp.example/v1", "baseUrl"))
                .isNotNull();
        assertReason(
                () -> policy.validateConfiguredUrl("https://unknown.example/v1", "baseUrl"),
                EndpointRejectionReason.ENDPOINT_UNRESOLVED);
    }

    /** 允许列表拒绝重复 code/baseUrl 与非法 code。 */
    @Test
    @DisplayName("允许列表拒绝重复与非法 code")
    void load_rejectsDuplicateAndInvalidEntries() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertReason(
                () -> policy.load(List.of(
                        new ManagedModelEndpointConfig("corp-gw", "https://10.20.30.40/v1", "PRODUCTION"),
                        new ManagedModelEndpointConfig("corp-gw", "https://10.20.30.41/v1", "PRODUCTION"))),
                EndpointRejectionReason.ENDPOINT_DUPLICATE);
        assertReason(
                () -> policy.load(List.of(
                        new ManagedModelEndpointConfig("corp-gw", "https://10.20.30.40/v1", "PRODUCTION"),
                        new ManagedModelEndpointConfig("corp-gw-2", "https://10.20.30.40/v1", "PRODUCTION"))),
                EndpointRejectionReason.ENDPOINT_DUPLICATE);
        assertReason(
                () -> policy.load(
                        List.of(new ManagedModelEndpointConfig("Corp_GW", "https://10.20.30.40/v1", "PRODUCTION"))),
                EndpointRejectionReason.ENDPOINT_MALFORMED);
        assertReason(
                () -> policy.load(List.of(new ManagedModelEndpointConfig("corp-gw", "https://10.20.30.40/v1", null))),
                EndpointRejectionReason.ENDPOINT_MALFORMED);
        assertReason(
                () -> policy.load(java.util.Arrays.asList((ManagedModelEndpointConfig) null)),
                EndpointRejectionReason.ENDPOINT_MALFORMED);
    }

    /** 标签与允许状态：演示端点在生产不可路由，生产端点在 DEMO 可路由；URL 不一致不可路由。 */
    @Test
    @DisplayName("标签与允许状态按部署模式派生")
    void registry_derivesAllowedFromModeAndUrl() {
        ManagedEndpointPolicy policy = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        ManagedEndpointRegistry productionRegistry = policy.load(List.of(
                new ManagedModelEndpointConfig("corp-gw", "https://10.20.30.40/v1", "PRODUCTION"),
                new ManagedModelEndpointConfig("demo-gw", "https://10.20.30.50/v1", "DEMO_ONLY")));
        assertThat(productionRegistry.isAllowed("corp-gw", "https://10.20.30.40/v1"))
                .isTrue();
        assertThat(productionRegistry.isAllowed("demo-gw", "https://10.20.30.50/v1"))
                .isFalse();
        assertThat(productionRegistry.isAllowed("corp-gw", "https://10.20.30.41/v1"))
                .isFalse();
        assertThat(productionRegistry.isAllowed("unknown", "https://10.20.30.40/v1"))
                .isFalse();
        assertThat(productionRegistry.labelFor("demo-gw")).contains(EndpointEnvironmentLabel.DEMO_ONLY);
        assertThat(productionRegistry.labelFor("unknown")).isEmpty();
        assertThat(productionRegistry.findByCode("corp-gw")).isPresent();
        assertThat(productionRegistry.findByCode(null)).isEmpty();
        assertThat(productionRegistry.endpoints()).hasSize(2);
        assertThat(productionRegistry.isEmpty()).isFalse();
        assertThat(productionRegistry.isLegacyCatalog()).isFalse();
        assertThat(productionRegistry.deploymentMode()).isEqualTo(ModelDeploymentMode.PRODUCTION);
        assertThat(productionRegistry.isEnvironmentRoutable(EndpointEnvironmentLabel.DEMO_ONLY))
                .isFalse();
        assertThat(productionRegistry.isEnvironmentRoutable(null)).isFalse();
        assertThat(productionRegistry.isAllowed(null, "https://10.20.30.40/v1")).isFalse();
        assertThat(productionRegistry.isAllowed("corp-gw", null)).isFalse();
        assertThat(productionRegistry.isAllowed("corp-gw", "not a url")).isFalse();

        ManagedEndpointRegistry demoRegistry = new ManagedEndpointPolicy(ModelDeploymentMode.DEMO, true, RESOLVER)
                .load(List.of(new ManagedModelEndpointConfig("demo-gw", "https://10.20.30.50/v1", "DEMO_ONLY")));
        assertThat(demoRegistry.isAllowed("demo-gw", "https://10.20.30.50/v1")).isTrue();
        assertThat(demoRegistry.isEnvironmentRoutable(EndpointEnvironmentLabel.DEMO_ONLY))
                .isTrue();
    }

    /** 空允许列表进入“仅 DB 目录”的遗留兼容模式；非空列表必须按 code/baseUrl 对齐。 */
    @Test
    @DisplayName("空允许列表为遗留 DB 目录兼容模式")
    void registry_emptyAllowlistUsesLegacyCatalog() {
        ManagedEndpointRegistry legacy =
                new ManagedEndpointPolicy(ModelDeploymentMode.DEMO, false, RESOLVER).load(List.of());
        assertThat(legacy.isLegacyCatalog()).isTrue();
        assertThat(legacy.isEmpty()).isTrue();
        assertThat(legacy.endpoints()).isEmpty();
        assertThat(legacy.isAllowed("legacy-provider", "https://10.20.30.40/v1"))
                .isTrue();
        assertThat(legacy.isAllowed("legacy-provider", null)).isFalse();

        ManagedEndpointRegistry configured = new ManagedEndpointPolicy(ModelDeploymentMode.DEMO, false, RESOLVER)
                .load(List.of(new ManagedModelEndpointConfig("corp-gw", "https://10.20.30.40/v1", "PRODUCTION")));
        assertThat(configured.isLegacyCatalog()).isFalse();
        assertThat(configured.isAllowed("legacy-provider", "https://10.20.30.40/v1"))
                .isFalse();
    }

    /** 部署模式与标签解析：默认安全、大小写宽容、非法值拒绝。 */
    @Test
    @DisplayName("部署模式与标签解析")
    void deploymentModeAndLabelParsing() {
        assertThat(ModelDeploymentMode.parse(null)).isEqualTo(ModelDeploymentMode.PRODUCTION);
        assertThat(ModelDeploymentMode.parse("  ")).isEqualTo(ModelDeploymentMode.PRODUCTION);
        assertThat(ModelDeploymentMode.parse("demo")).isEqualTo(ModelDeploymentMode.DEMO);
        assertThat(ModelDeploymentMode.parse("PRODUCTION")).isEqualTo(ModelDeploymentMode.PRODUCTION);
        assertThatThrownBy(() -> ModelDeploymentMode.parse("STAGING")).isInstanceOf(IllegalArgumentException.class);

        assertThat(EndpointEnvironmentLabel.parse("demo_only")).isEqualTo(EndpointEnvironmentLabel.DEMO_ONLY);
        assertThat(EndpointEnvironmentLabel.parse("PRODUCTION")).isEqualTo(EndpointEnvironmentLabel.PRODUCTION);
        assertThatThrownBy(() -> EndpointEnvironmentLabel.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointEnvironmentLabel.parse("TEST")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 值对象构造守卫与 URL 规范化。 */
    @Test
    @DisplayName("值对象守卫与 baseUrl 规范化")
    void valueObjectsAndNormalization() {
        assertThatThrownBy(() -> new ManagedModelEndpoint(" ", URI.create("https://a.example/v1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManagedModelEndpoint("corp", null, EndpointEnvironmentLabel.PRODUCTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManagedModelEndpoint("corp", URI.create("https://a.example/v1"), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ManagedEndpointPolicy.normalizeBaseUrl(URI.create("HTTPS://LLM.Corp.Example.:443/v1/")))
                .isEqualTo("https://llm.corp.example/v1/");
        assertThat(ManagedEndpointPolicy.normalizeBaseUrl(URI.create("https://10.20.30.40:8443")))
                .isEqualTo("https://10.20.30.40:8443/");
        assertThat(ManagedEndpointPolicy.normalizeBaseUrl(URI.create("HTTPS://[::1]:443/v1")))
                .isEqualTo("https://[::1]/v1");
        assertThat(ManagedEndpointPolicy.normalizeBaseUrl(URI.create("http://10.20.30.40:80/v1")))
                .isEqualTo("http://10.20.30.40/v1");
    }

    /** 策略直接判断标签；系统解析器可解析 loopback 字面量。 */
    @Test
    @DisplayName("isRoutable 与系统解析器")
    void routabilityAndSystemResolver() throws UnknownHostException {
        ManagedEndpointPolicy production = new ManagedEndpointPolicy(ModelDeploymentMode.PRODUCTION, false, RESOLVER);
        assertThat(production.isRoutable(EndpointEnvironmentLabel.PRODUCTION)).isTrue();
        assertThat(production.isRoutable(EndpointEnvironmentLabel.DEMO_ONLY)).isFalse();
        assertThat(production.isRoutable(null)).isFalse();
        assertThat(production.deploymentMode()).isEqualTo(ModelDeploymentMode.PRODUCTION);

        assertThat(HostResolver.system().resolve("127.0.0.1")).isNotEmpty();
    }

    /** 断言抛出稳定原因码。 */
    private static void assertReason(Runnable action, EndpointRejectionReason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ManagedEndpointPolicyException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
