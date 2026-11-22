package com.riccon.api.admin.model.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.api.admin.model.routing.domain.ModelRoutingRules;
import com.riccon.api.admin.model.routing.domain.ModelRoutingValidationException;
import com.riccon.api.common.UuidV7;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由保存规则的单元测试。
 *
 * <p>覆盖集成测试不易穷举的边界：空/超长/重复/非法 UUID、版本非法、provider 停用、协议不支持与端点
 * 不允许；停用模型本身允许保留在链中（ADR-0021 §4.2）。
 */
class ModelRoutingRulesTest {

    /** 允许列表 mock。 */
    private final ManagedEndpointRegistry endpointRegistry = mock(ManagedEndpointRegistry.class);

    /** 版本为空或小于 1 时返回字段级校验失败。 */
    @Test
    @DisplayName("requireVersion 拒绝空与小于 1 的版本")
    void requireVersionRejectsInvalidValues() {
        assertThatThrownBy(() -> ModelRoutingRules.requireVersion(null))
                .isInstanceOf(ModelRoutingValidationException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> ModelRoutingRules.requireVersion(0))
                .isInstanceOf(ModelRoutingValidationException.class)
                .hasMessageContaining("version");
        assertThat(ModelRoutingRules.requireVersion(7)).isEqualTo(7);
    }

    /** 空列表表示“空链”，必须拒绝。 */
    @Test
    @DisplayName("parseModelIds 拒绝空链")
    void parseModelIdsRejectsEmptyChain() {
        assertThatThrownBy(() -> ModelRoutingRules.parseModelIds(List.of()))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("CHAIN_EMPTY"));
        assertThatThrownBy(() -> ModelRoutingRules.parseModelIds(null))
                .isInstanceOf(ModelRoutingValidationException.class);
    }

    /** 超过 20 个模型必须拒绝，避免无限链。 */
    @Test
    @DisplayName("parseModelIds 拒绝超长链")
    void parseModelIdsRejectsTooLongChain() {
        List<String> ids = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> UuidV7.generate().toString())
                .toList();
        assertThatThrownBy(() -> ModelRoutingRules.parseModelIds(ids))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("CHAIN_TOO_LONG"));
    }

    /** 重复模型必须拒绝；顺序在合法输入上保持不变。 */
    @Test
    @DisplayName("parseModelIds 拒绝重复并保持顺序")
    void parseModelIdsRejectsDuplicatesAndKeepsOrder() {
        UUID first = UuidV7.generate();
        UUID second = UuidV7.generate();
        assertThat(ModelRoutingRules.parseModelIds(List.of(second.toString(), first.toString())))
                .containsExactly(second, first);
        assertThatThrownBy(() -> ModelRoutingRules.parseModelIds(List.of(first.toString(), first.toString())))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("DUPLICATE_MODEL"));
    }

    /** 非法 UUID 返回字段级原因，而不是通用反序列化错误。 */
    @Test
    @DisplayName("parseModelIds 拒绝非法 UUID")
    void parseModelIdsRejectsInvalidUuid() {
        assertThatThrownBy(() -> ModelRoutingRules.parseModelIds(List.of("not-a-uuid")))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("INVALID_MODEL_ID"));
        assertThatThrownBy(() -> ModelRoutingRules.parseModelIds(java.util.Arrays.asList((String) null)))
                .isInstanceOf(ModelRoutingValidationException.class);
    }

    /** provider 停用或协议不支持时不可路由。 */
    @Test
    @DisplayName("requireRoutable 拒绝停用 provider 与非 OpenAI 兼容协议")
    void requireRoutableRejectsDisabledProviderAndUnsupportedProtocol() {
        AdminModelRow disabledProvider = row("DISABLED", "OPENAI_COMPATIBLE", "m04-rules", "https://10.20.30.70/v1");
        assertThatThrownBy(() -> ModelRoutingRules.requireRoutable(disabledProvider, endpointRegistry))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("MODEL_NOT_ROUTABLE"));

        AdminModelRow unsupportedProtocol = row("ACTIVE", "OTHER", "m04-rules", "https://10.20.30.70/v1");
        assertThatThrownBy(() -> ModelRoutingRules.requireRoutable(unsupportedProtocol, endpointRegistry))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("MODEL_NOT_ROUTABLE"));
    }

    /** 端点不在部署允许列表时必须拒绝。 */
    @Test
    @DisplayName("requireRoutable 拒绝未授权端点")
    void requireRoutableRejectsEndpointOutsideAllowlist() {
        AdminModelRow model = row("ACTIVE", "OPENAI_COMPATIBLE", "m04-rules", "https://10.20.30.71/v1");
        when(endpointRegistry.isAllowed("m04-rules", "https://10.20.30.71/v1")).thenReturn(false);

        assertThatThrownBy(() -> ModelRoutingRules.requireRoutable(model, endpointRegistry))
                .isInstanceOf(ModelRoutingValidationException.class)
                .satisfies(exception -> assertThat(((ModelRoutingValidationException) exception).reason())
                        .isEqualTo("ENDPOINT_NOT_ALLOWED"));
    }

    /** 停用模型保留在链中是允许的；运行时由 Worker 跳过。 */
    @Test
    @DisplayName("requireRoutable 允许停用模型保留")
    void requireRoutableAllowsDisabledModel() {
        AdminModelRow disabledModel = new AdminModelRow(
                UuidV7.generate(),
                UuidV7.generate(),
                "m04-model",
                "停用模型",
                32768,
                2048,
                null,
                null,
                null,
                "DISABLED",
                "m04-rules",
                "OPENAI_COMPATIBLE",
                "https://10.20.30.72/v1",
                "ACTIVE");
        when(endpointRegistry.isAllowed("m04-rules", "https://10.20.30.72/v1")).thenReturn(true);

        ModelRoutingRules.requireRoutable(disabledModel, endpointRegistry);
    }

    /** 构造目录行；只填充路由规则关心的字段。 */
    private static AdminModelRow row(String providerStatus, String protocol, String code, String baseUrl) {
        return new AdminModelRow(
                UuidV7.generate(),
                UuidV7.generate(),
                "m04-model",
                "模型",
                32768,
                2048,
                null,
                null,
                null,
                "ACTIVE",
                code,
                protocol,
                baseUrl,
                providerStatus);
    }
}
