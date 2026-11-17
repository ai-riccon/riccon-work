package com.riccon.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.config.WorkerClientProperties;
import com.riccon.platform.common.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 服务令牌签发测试。
 *
 * <p>独立实现 Worker 侧 HMAC 校验，确认字段、签名与有效期，避免 API/Worker 各自漂移。
 */
class ServiceTokenSignerTest {

    /** 测试密钥。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    /** JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定时钟。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    /** 令牌声明与签名可被 Worker 侧逻辑验证。 */
    @Test
    void signedTokenCarriesExpectedClaimsAndVerifies() throws Exception {
        ServiceTokenSigner signer = new ServiceTokenSigner(properties(), MAPPER, CLOCK);
        TenantId tenantId = TenantId.of("tenant-a");
        UUID userId = UUID.fromString("01930000-0000-7000-8000-000000000001");
        UUID runId = UUID.fromString("01930000-0000-7000-8000-000000000002");

        String token = signer.sign(tenantId, userId, runId);
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("riccon1");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        byte[] provided = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(MessageDigest.isEqual(expected, provided)).isTrue();

        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.path("iss").asText()).isEqualTo("riccon-api");
        assertThat(payload.path("aud").asText()).isEqualTo("riccon-worker");
        assertThat(payload.path("sub").asText()).isEqualTo("user:" + userId);
        assertThat(payload.path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(payload.path("runId").asText()).isEqualTo(runId.toString());
        assertThat(payload.path("scope").asText()).isEqualTo("agent:run");
        assertThat(payload.path("iat").asLong()).isEqualTo(CLOCK.instant().getEpochSecond());
        assertThat(payload.path("exp").asLong()).isEqualTo(CLOCK.instant().getEpochSecond() + 300);
        assertThat(payload.path("jti").asText()).isNotBlank();
    }

    /** 篡改载荷后签名不再匹配。 */
    @Test
    void tamperedPayloadDoesNotVerify() throws Exception {
        ServiceTokenSigner signer = new ServiceTokenSigner(properties(), MAPPER, CLOCK);
        String token = signer.sign(
                TenantId.of("tenant-a"),
                UUID.fromString("01930000-0000-7000-8000-000000000001"),
                UUID.fromString("01930000-0000-7000-8000-000000000002"));
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"iss\":\"evil\"}".getBytes(StandardCharsets.UTF_8));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(("riccon1." + tamperedPayload).getBytes(StandardCharsets.UTF_8));
        byte[] originalSignature = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(MessageDigest.isEqual(expected, originalSignature)).isFalse();
    }

    /**
     * 构造测试配置。
     *
     * @return 配置
     */
    private static WorkerClientProperties properties() {
        return new WorkerClientProperties(
                "http://127.0.0.1:8081",
                Duration.ofSeconds(5),
                Duration.ofSeconds(300),
                SECRET,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10));
    }
}
