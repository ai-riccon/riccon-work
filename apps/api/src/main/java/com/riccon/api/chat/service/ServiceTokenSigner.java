package com.riccon.api.chat.service;

import com.riccon.api.common.UuidV7;
import com.riccon.api.config.WorkerClientProperties;
import com.riccon.platform.common.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * API → Worker HMAC 服务令牌签发。
 *
 * <p>令牌格式与 Worker 校验器一致：{@code riccon1.<Base64Url(payload JSON)>.<Base64Url(HMAC-SHA256)>}。
 * 声明包含 {@code iss/aud/sub/tenantId/runId/scope/iat/exp/jti}，有效期不超过 300 秒；密钥只从配置注入，
 * 不写入日志或响应。
 */
@Component
public class ServiceTokenSigner {

    /** 令牌前缀。 */
    static final String PREFIX = "riccon1";

    /** 固定签发者。 */
    static final String ISSUER = "riccon-api";

    /** 固定受众。 */
    static final String AUDIENCE = "riccon-worker";

    /** 固定 scope。 */
    static final String SCOPE = "agent:run";

    /** HMAC 算法。 */
    private static final String HMAC = "HmacSHA256";

    /** 客户端配置。 */
    private final WorkerClientProperties properties;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建签发器。
     *
     * @param properties 客户端配置
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public ServiceTokenSigner(WorkerClientProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 签发一次运行的服务令牌。
     *
     * @param tenantId 租户
     * @param userId 发起人
     * @param runId 运行 ID
     * @return 可放入 {@code X-Riccon-Service-Token} 的令牌
     * @throws IllegalStateException JSON 序列化失败或 HMAC 算法不可用
     */
    public String sign(TenantId tenantId, UUID userId, UUID runId) {
        long issuedAt = clock.instant().getEpochSecond();
        long expiresAt = issuedAt + properties.tokenTtl().toSeconds();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", ISSUER);
        payload.put("aud", AUDIENCE);
        payload.put("sub", "user:" + userId);
        payload.put("tenantId", tenantId.value());
        payload.put("runId", runId.toString());
        payload.put("scope", SCOPE);
        payload.put("iat", issuedAt);
        payload.put("exp", expiresAt);
        payload.put("jti", UuidV7.generate().toString());
        String encodedPayload;
        try {
            encodedPayload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize service token payload", exception);
        }
        String signingInput = PREFIX + "." + encodedPayload;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(signingInput));
        return signingInput + "." + signature;
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param value 签名输入
     * @return 摘要
     */
    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(properties.serviceTokenSecret().getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            // 算法或密钥不可用属于启动配置错误，必须失败而不是降级为无签名。
            throw new IllegalStateException("service token signer is unavailable", exception);
        }
    }
}
