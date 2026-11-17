package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.chat.domain.ChatException;
import com.riccon.worker.chat.service.ServiceTokenValidator;
import com.riccon.worker.config.ServiceTokenProperties;
import com.riccon.worker.support.TestServiceToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 服务令牌签名、声明与运行绑定。 */
class ServiceTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID USER = UUID.fromString("01930000-0000-7000-8000-000000000001");

    private static final UUID RUN = UUID.fromString("01930000-0000-7000-8000-000000000002");

    /** 合法令牌通过并返回绑定。 */
    @Test
    void validate_validToken_returnsClaims() {
        String token = TestServiceToken.mint("tenant-a", USER, RUN, NOW.getEpochSecond(), NOW.getEpochSecond() + 300);

        ServiceTokenValidator.ServiceTokenClaims claims = validator().validate(token, RUN);

        assertThat(claims.tenantId().value()).isEqualTo("tenant-a");
        assertThat(claims.userId()).isEqualTo(USER);
        assertThat(claims.runId()).isEqualTo(RUN);
    }

    /** 缺失令牌返回 401。 */
    @Test
    void validate_missingToken_fails() {
        assertUnauthorized(null);
        assertUnauthorized("   ");
    }

    /** 格式与签名错误返回 401。 */
    @Test
    void validate_malformedOrBadSignature_fails() {
        assertUnauthorized("not-a-token");
        String token = TestServiceToken.mint("tenant-a", USER, RUN, NOW.getEpochSecond(), NOW.getEpochSecond() + 300);
        assertUnauthorized(token.substring(0, token.length() - 2) + "AA");
    }

    /** 发行者、受众与 scope 必须匹配。 */
    @Test
    void validate_wrongIssuerAudienceOrScope_fails() {
        assertUnauthorized(token(claims -> claims.put("iss", "other")));
        assertUnauthorized(token(claims -> claims.put("aud", "other")));
        assertUnauthorized(token(claims -> claims.put("scope", "admin")));
    }

    /** 过期、未来签发与超长有效期都被拒绝。 */
    @Test
    void validate_timeWindow_fails() {
        assertUnauthorized(
                TestServiceToken.mint("tenant-a", USER, RUN, NOW.getEpochSecond() - 600, NOW.getEpochSecond() - 1));
        assertUnauthorized(
                TestServiceToken.mint("tenant-a", USER, RUN, NOW.getEpochSecond() + 120, NOW.getEpochSecond() + 300));
        assertUnauthorized(
                TestServiceToken.mint("tenant-a", USER, RUN, NOW.getEpochSecond(), NOW.getEpochSecond() + 301));
    }

    /** 运行绑定不匹配返回 401。 */
    @Test
    void validate_runMismatch_fails() {
        String token = TestServiceToken.mint("tenant-a", USER, RUN, NOW.getEpochSecond(), NOW.getEpochSecond() + 300);
        assertThatThrownBy(() -> validator().validate(token, UUID.randomUUID()))
                .isInstanceOf(ChatException.class)
                .satisfies(error -> assertThat(((ChatException) error).code()).isEqualTo("RICCON-AUTH-401"));
    }

    /** 缺失声明、非法 subject 与非法租户都拒绝。 */
    @Test
    void validate_invalidClaims_fails() {
        assertUnauthorized(token(claims -> claims.remove("jti")));
        assertUnauthorized(token(claims -> claims.put("jti", "")));
        assertUnauthorized(token(claims -> claims.put("sub", "service:abc")));
        assertUnauthorized(token(claims -> claims.put("sub", "user:not-uuid")));
        assertUnauthorized(token(claims -> claims.put("tenantId", "bad tenant")));
        assertUnauthorized(token(claims -> claims.put("runId", "not-uuid")));
        assertUnauthorized(token(claims -> claims.remove("iat")));
    }

    /** 断言 401。 */
    private static void assertUnauthorized(String token) {
        assertThatThrownBy(() -> validator().validate(token, RUN))
                .isInstanceOf(ChatException.class)
                .satisfies(error -> {
                    ChatException chat = (ChatException) error;
                    assertThat(chat.status()).isEqualTo(401);
                    assertThat(chat.code()).isEqualTo("RICCON-AUTH-401");
                });
    }

    /** 构造带修改声明的令牌。 */
    private static String token(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "riccon-api");
        claims.put("aud", "riccon-worker");
        claims.put("sub", "user:" + USER);
        claims.put("tenantId", "tenant-a");
        claims.put("runId", RUN.toString());
        claims.put("scope", "agent:run");
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.getEpochSecond() + 300);
        claims.put("jti", UUID.randomUUID().toString());
        customizer.accept(claims);
        return TestServiceToken.sign(claims, TestServiceToken.SECRET);
    }

    /** 构造校验器。 */
    private static ServiceTokenValidator validator() {
        return new ServiceTokenValidator(
                new ServiceTokenProperties(TestServiceToken.SECRET, 300, 5), new ObjectMapper(), CLOCK);
    }
}
