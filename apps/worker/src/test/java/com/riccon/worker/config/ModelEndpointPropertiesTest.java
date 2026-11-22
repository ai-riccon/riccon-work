package com.riccon.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.model.EndpointEnvironmentLabel;
import com.riccon.platform.common.model.ManagedEndpointPolicyException;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 受管端点配置启动校验（IT-MODEL-107 配置层）。
 *
 * <p>覆盖生产方式缺少端点、生产开启 HTTP、演示端点在生产不可路由、非法 JSON/重复项与 DEMO 回环端点。
 * 只使用字面量地址，不发起 DNS 或出网。
 */
class ModelEndpointPropertiesTest {

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 测试端点：生产标签。 */
    private static final String PRODUCTION_ENDPOINTS =
            "[{\"code\":\"corp-gw\",\"baseUrl\":\"https://10.20.30.40/v1\",\"environmentLabel\":\"PRODUCTION\"}]";

    /** 测试端点：演示标签。 */
    private static final String DEMO_ENDPOINTS =
            "[{\"code\":\"demo-gw\",\"baseUrl\":\"https://10.20.30.50/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]";

    /** DEMO 回环端点（仅本地测试）。 */
    private static final String DEMO_LOOPBACK_ENDPOINTS =
            "[{\"code\":\"demo-local\",\"baseUrl\":\"http://127.0.0.1:19090/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]";

    /** 生产方式未配置端点且未接受遗留目录时启动失败。 */
    @Test
    @DisplayName("PRODUCTION 未配置端点启动失败")
    void afterPropertiesSet_productionWithoutEndpointsFails() {
        ModelEndpointProperties properties = properties("PRODUCTION", "", false, false);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_MODEL_ENDPOINTS");
    }

    /** 生产模式开启 HTTP 开关启动失败。 */
    @Test
    @DisplayName("PRODUCTION 开启 allow-http 启动失败")
    void afterPropertiesSet_productionWithHttpFails() {
        ModelEndpointProperties properties = properties("PRODUCTION", PRODUCTION_ENDPOINTS, false, true);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(ManagedEndpointPolicyException.class);
    }

    /** 演示端点在 PRODUCTION 下加载成功但不可路由。 */
    @Test
    @DisplayName("PRODUCTION 下演示端点 allowed=false")
    void registry_productionRejectsDemoEndpoint() {
        ModelEndpointProperties properties = properties("PRODUCTION", DEMO_ENDPOINTS, false, false);
        properties.afterPropertiesSet();
        ManagedEndpointRegistry registry = properties.registry();
        assertThat(registry.labelFor("demo-gw")).contains(EndpointEnvironmentLabel.DEMO_ONLY);
        assertThat(registry.isAllowed("demo-gw", "https://10.20.30.50/v1")).isFalse();
    }

    /** 生产标签端点在 PRODUCTION 下可路由。 */
    @Test
    @DisplayName("PRODUCTION 标签端点 allowed=true")
    void registry_productionAllowsProductionEndpoint() {
        ModelEndpointProperties properties = properties("PRODUCTION", PRODUCTION_ENDPOINTS, false, false);
        properties.afterPropertiesSet();
        assertThat(properties.registry().isAllowed("corp-gw", "https://10.20.30.40/v1"))
                .isTrue();
        assertThat(properties.registry().isAllowed("corp-gw", "https://10.20.30.41/v1"))
                .isFalse();
    }

    /** DEMO + allow-http 允许回环端点。 */
    @Test
    @DisplayName("DEMO 回环端点允许")
    void registry_demoAllowsLoopbackEndpoint() {
        ModelEndpointProperties properties = properties("DEMO", DEMO_LOOPBACK_ENDPOINTS, false, true);
        properties.afterPropertiesSet();
        assertThat(properties.registry().isAllowed("demo-local", "http://127.0.0.1:19090/v1"))
                .isTrue();
    }

    /** DEMO + allow-legacy-endpoints 且未配置端点时进入遗留 DB 目录兼容。 */
    @Test
    @DisplayName("DEMO 空端点为遗留目录兼容")
    void registry_demoEmptyAllowlistUsesLegacyCatalog() {
        ModelEndpointProperties properties = properties("DEMO", "", true, false);
        properties.afterPropertiesSet();
        assertThat(properties.registry().isLegacyCatalog()).isTrue();
        assertThat(properties.registry().isAllowed("legacy-provider", "https://10.20.30.40/v1"))
                .isTrue();
    }

    /** 非法 JSON、重复 code 与非法模式都启动失败。 */
    @Test
    @DisplayName("非法配置启动失败")
    void afterPropertiesSet_invalidConfigurationFails() {
        assertThatThrownBy(() -> properties("DEMO", "not-json", false, true).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON array");

        String duplicates =
                "[{\"code\":\"corp-gw\",\"baseUrl\":\"https://10.20.30.40/v1\",\"environmentLabel\":\"PRODUCTION\"},"
                        + "{\"code\":\"corp-gw\",\"baseUrl\":\"https://10.20.30.41/v1\",\"environmentLabel\":\"PRODUCTION\"}]";
        assertThatThrownBy(() -> properties("DEMO", duplicates, false, true).afterPropertiesSet())
                .isInstanceOfSatisfying(
                        IllegalStateException.class,
                        exception ->
                                assertThat(exception.getCause()).isInstanceOf(ManagedEndpointPolicyException.class));

        assertThatThrownBy(() -> properties("STAGING", "", false, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    /** 构造配置对象。 */
    private static ModelEndpointProperties properties(
            String mode, String endpoints, boolean allowLegacy, boolean allowHttp) {
        ModelEndpointProperties properties = new ModelEndpointProperties(MAPPER);
        properties.setDeploymentMode(mode);
        properties.setEndpoints(endpoints);
        properties.setAllowLegacyEndpoints(allowLegacy);
        properties.setAllowHttp(allowHttp);
        return properties;
    }
}
