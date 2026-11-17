package com.riccon.worker.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试服务令牌签发器。
 *
 * <p>与生产校验器使用同一格式，仅用于测试；密钥为显式测试值，不进入生产配置。
 */
public final class TestServiceToken {

    /** 测试共享密钥。 */
    public static final String SECRET = "riccon-worker-test-service-token-secret-0001";

    /** JSON 序列化器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具类禁止实例化。 */
    private TestServiceToken() {}

    /**
     * 签发合法令牌。
     *
     * @param tenantId 租户
     * @param userId 用户
     * @param runId 运行
     * @return 令牌
     */
    public static String mint(String tenantId, UUID userId, UUID runId) {
        Instant now = Instant.now();
        return mint(tenantId, userId, runId, now.getEpochSecond(), now.getEpochSecond() + 300);
    }

    /**
     * 按指定时间签发。
     *
     * @param tenantId 租户
     * @param userId 用户
     * @param runId 运行
     * @param issuedAt iat
     * @param expiresAt exp
     * @return 令牌
     */
    public static String mint(String tenantId, UUID userId, UUID runId, long issuedAt, long expiresAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "riccon-api");
        claims.put("aud", "riccon-worker");
        claims.put("sub", "user:" + userId);
        claims.put("tenantId", tenantId);
        claims.put("runId", runId.toString());
        claims.put("scope", "agent:run");
        claims.put("iat", issuedAt);
        claims.put("exp", expiresAt);
        claims.put("jti", UUID.randomUUID().toString());
        return sign(claims, SECRET);
    }

    /**
     * 用指定声明与密钥签名。
     *
     * @param claims 声明
     * @param secret 密钥
     * @return 令牌
     */
    public static String sign(Map<String, Object> claims, String secret) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(claims));
            String signingInput = "riccon1." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("test token signing failed", ex);
        }
    }
}
