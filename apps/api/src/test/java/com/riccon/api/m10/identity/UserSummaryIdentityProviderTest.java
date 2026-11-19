package com.riccon.api.m10.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code UserSummaryResponse.identityProvider} 序列化契约。
 *
 * <p>OpenAPI {@code UserSummary.identityProvider} 是可选属性：OIDC 输出脱敏展示名；LOCAL 或未提供时省略字段，
 * 不输出 {@code null}，也不输出完整 issuer/subject。
 */
class UserSummaryIdentityProviderTest {

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OIDC 展示名存在时输出字符串。 */
    @Test
    @DisplayName("identityProvider 存在时序列化")
    void serializesIdentityProviderWhenPresent() {
        UserSummaryResponse summary = new UserSummaryResponse(
                UUID.randomUUID(),
                "alice",
                "Alice",
                null,
                UserStatus.ACTIVE,
                List.of("MEMBER"),
                AuthSource.OIDC,
                "idp.example.com",
                null,
                null);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(summary));

        assertThat(json.path("authSource").asText()).isEqualTo("OIDC");
        assertThat(json.path("identityProvider").asText()).isEqualTo("idp.example.com");
    }

    /** LOCAL 未提供展示名时省略字段。 */
    @Test
    @DisplayName("identityProvider 为空时省略字段")
    void omitsIdentityProviderWhenAbsent() {
        UserSummaryResponse summary = new UserSummaryResponse(
                UUID.randomUUID(),
                "bob",
                "Bob",
                null,
                UserStatus.ACTIVE,
                List.of("MEMBER"),
                AuthSource.LOCAL,
                null,
                null,
                null);

        String json = objectMapper.writeValueAsString(summary);

        assertThat(json).doesNotContain("identityProvider");
    }

    /** 既有 9 参与 8 参兼容构造器仍可用，且不输出 identityProvider。 */
    @Test
    @DisplayName("兼容构造器保持字段省略")
    void compatConstructorsRemainBackwardCompatible() {
        UserSummaryResponse legacy9 = new UserSummaryResponse(
                UUID.randomUUID(),
                "carol",
                "Carol",
                null,
                UserStatus.ACTIVE,
                List.of("MEMBER"),
                AuthSource.LOCAL,
                null,
                null);
        UserSummaryResponse legacy8 = new UserSummaryResponse(
                UUID.randomUUID(), "dave", "Dave", null, UserStatus.ACTIVE, List.of("MEMBER"), null, null);

        assertThat(objectMapper.writeValueAsString(legacy9)).doesNotContain("identityProvider");
        assertThat(objectMapper.writeValueAsString(legacy8)).doesNotContain("identityProvider", "authSource");
    }
}
