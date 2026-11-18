package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.oidc.config.OidcProperties;
import com.riccon.api.oidc.domain.OidcConfigurationParser;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * OIDC 配置解析与 secret 脱敏测试。
 *
 * <p>对应 TASK-0065 验收“Secret 配置校验/issuer 唯一/secret 脱敏”和 OPS-003：
 * 任何错误消息与 {@code toString} 都不得包含原始 JSON 或 clientSecret。
 */
class OidcConfigurationParserTest {

    /** 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用于日志/异常泄漏扫描的哨兵密钥。 */
    private static final String SECRET = "stub-secret-value-do-not-leak";

    /** 合法最小配置。 */
    private static String validJson() {
        return """
                [{
                  "tenantCode": "acme",
                  "issuer": "https://idp.acme.example/realms/acme/",
                  "clientId": "riccon",
                  "clientSecret": "%s",
                  "providerName": "Acme 企业账号",
                  "scopes": ["openid"],
                  "endSessionEndpoint": "https://idp.acme.example/realms/acme/logout",
                  "enabled": true
                }]
                """.formatted(SECRET);
    }

    /** 合法配置：字段、规范化 issuer 与注册 ID。 */
    @Test
    void parse_validConfig_normalizesIssuerAndDefaults() {
        List<OidcIdpConfig> idps = OidcConfigurationParser.parse(validJson(), false, MAPPER);

        assertThat(idps).hasSize(1);
        OidcIdpConfig idp = idps.get(0);
        assertThat(idp.tenantCode()).isEqualTo("acme");
        assertThat(idp.issuer()).isEqualTo("https://idp.acme.example/realms/acme");
        assertThat(idp.registrationId()).isEqualTo("tenant-acme");
        assertThat(idp.clientSecret()).isEqualTo(SECRET);
        assertThat(idp.providerName()).isEqualTo("Acme 企业账号");
        assertThat(idp.scopes()).containsExactly("openid");
        assertThat(idp.enabled()).isTrue();
    }

    /** 未配置配置空列表，不能阻止应用启动。 */
    @Test
    void parse_blankConfig_returnsEmpty() {
        assertThat(OidcConfigurationParser.parse(null, false, MAPPER)).isEmpty();
        assertThat(OidcConfigurationParser.parse("", false, MAPPER)).isEmpty();
        assertThat(OidcConfigurationParser.parse("  ", false, MAPPER)).isEmpty();
        assertThat(OidcConfigurationParser.parse("[]", false, MAPPER)).isEmpty();
    }

    /** 缺省 scope/providerName/enabled 时使用安全默认值。 */
    @Test
    void parse_omittedOptionalFields_appliesDefaults() {
        String json = """
                [{"tenantCode":"acme","issuer":"https://idp.example","clientId":"c","clientSecret":"%s"}]
                """.formatted(SECRET);
        OidcIdpConfig idp = OidcConfigurationParser.parse(json, false, MAPPER).get(0);
        assertThat(idp.providerName()).isEqualTo("企业账号");
        assertThat(idp.scopes()).containsExactly("openid");
        assertThat(idp.enabled()).isTrue();
        assertThat(idp.endSessionEndpoint()).isNull();
    }

    /** malformed JSON：消息只含类型与位置，不含原文或 secret（OPS-003）。 */
    @Test
    void parse_malformedJson_failsWithoutLeakingSecret() {
        String malformed = """
                [{"tenantCode":"acme","issuer":"https://idp.example","clientId":"c","clientSecret":"%s" BROKEN]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(malformed, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riccon.oidc.idps")
                .hasMessageContaining("is not valid JSON")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(SECRET)
                        .doesNotContain("BROKEN"));
    }

    /** 缺 clientSecret：只报告字段路径，不回显其他字段值。 */
    @Test
    void parse_missingSecret_reportsPathWithoutValues() {
        String json = """
                [{"tenantCode":"acme","issuer":"https://idp.example","clientId":"c"}]
                """;
        assertThatThrownBy(() -> OidcConfigurationParser.parse(json, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riccon.oidc.idps[0].clientSecret")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("acme"));
    }

    /** issuer 规范化后重复（大小写/尾部斜杠）必须失败。 */
    @Test
    void parse_duplicateIssuerAfterNormalization_fails() {
        String json = """
                [
                  {"tenantCode":"a","issuer":"https://idp.example/realms/x","clientId":"c","clientSecret":"%1$s"},
                  {"tenantCode":"b","issuer":"https://IDP.example/realms/x/","clientId":"c","clientSecret":"%1$s"}
                ]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(json, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riccon.oidc.idps[1].issuer")
                .hasMessageContaining("already used");
    }

    /** 同租户重复配置必须失败（每租户 1 个 IdP）。 */
    @Test
    void parse_duplicateTenantCode_fails() {
        String json = """
                [
                  {"tenantCode":"a","issuer":"https://one.example","clientId":"c","clientSecret":"%1$s"},
                  {"tenantCode":"a","issuer":"https://two.example","clientId":"c","clientSecret":"%1$s"}
                ]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(json, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenantCode is duplicated");
    }

    /** 非 HTTPS issuer 默认拒绝；allow-http 仅测试/本地打开。 */
    @Test
    void parse_httpIssuer_requiresAllowHttp() {
        String json = """
                [{"tenantCode":"a","issuer":"http://127.0.0.1:9000","clientId":"c","clientSecret":"%s"}]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(json, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use https");
        assertThat(OidcConfigurationParser.parse(json, true, MAPPER)).hasSize(1);
    }

    /** scopes 必须含 openid 且不得含 offline_access。 */
    @Test
    void parse_scopes_rejectsMissingOpenidAndOfflineAccess() {
        String missingOpenid = """
                [{"tenantCode":"a","issuer":"https://idp.example","clientId":"c","clientSecret":"%s","scopes":["profile"]}]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(missingOpenid, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain openid");
        String offline = """
                [{"tenantCode":"a","issuer":"https://idp.example","clientId":"c","clientSecret":"%s","scopes":["openid","offline_access"]}]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(offline, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offline_access");
    }

    /** tenantCode 格式错误必须失败。 */
    @Test
    void parse_invalidTenantCode_fails() {
        String json = """
                [{"tenantCode":"-bad","issuer":"https://idp.example","clientId":"c","clientSecret":"%s"}]
                """.formatted(SECRET);
        assertThatThrownBy(() -> OidcConfigurationParser.parse(json, false, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenantCode has an invalid format");
    }

    /** 配置与属性对象的 toString 必须掩码 secret。 */
    @Test
    void toString_masksClientSecret() {
        OidcIdpConfig idp =
                OidcConfigurationParser.parse(validJson(), false, MAPPER).get(0);
        assertThat(idp.toString()).doesNotContain(SECRET).contains("clientSecret=***");

        OidcProperties properties = new OidcProperties();
        properties.getOidc().setIdps(validJson());
        assertThat(properties.toString()).doesNotContain(SECRET).contains("<redacted>");
    }
}
