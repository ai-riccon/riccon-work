package com.riccon.worker.chat.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.chat.domain.ChatException;
import com.riccon.worker.config.ServiceTokenProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API → Worker 服务令牌校验。
 *
 * <p>令牌格式 {@code riccon1.<Base64Url(payload JSON)>.<Base64Url(HMAC-SHA256)>}，声明与
 * ADR-0014 第 5 节一致：iss/aud/sub/tenantId/runId/scope/iat/exp/jti。校验签名、受众、
 * 有效期、scope 与运行绑定；失败统一 401，不区分具体原因，避免探测。
 */
@Component
public class ServiceTokenValidator {

    /** 令牌前缀。 */
    private static final String PREFIX = "riccon1";

    /** 固定签发者。 */
    static final String ISSUER = "riccon-api";

    /** 固定受众。 */
    static final String AUDIENCE = "riccon-worker";

    /** 允许的 scope。 */
    static final String SCOPE = "agent:run";

    /** HMAC 算法。 */
    private static final String HMAC = "HmacSHA256";

    /** 配置。 */
    private final ServiceTokenProperties properties;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建令牌校验器。
     *
     * @param properties 配置
     * @param objectMapper JSON 解析
     * @param clock UTC 时钟
     */
    public ServiceTokenValidator(ServiceTokenProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 校验令牌并返回绑定声明。
     *
     * @param token 原始令牌
     * @param expectedRunId 路径或请求体中的运行 ID
     * @return 通过校验的声明
     * @throws ChatException 缺失、签名错误、过期或绑定不匹配
     */
    public ServiceTokenClaims validate(String token, UUID expectedRunId) {
        if (token == null || token.isBlank()) {
            throw unauthorized("service token is missing");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw unauthorized("service token format is invalid");
        }
        String signingInput = PREFIX + "." + parts[1];
        byte[] expected = hmac(signingInput);
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw unauthorized("service token signature is not valid Base64");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw unauthorized("service token signature is invalid");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IllegalArgumentException | JacksonException ex) {
            throw unauthorized("service token payload is not valid JSON");
        }
        if (!ISSUER.equals(text(payload, "iss")) || !AUDIENCE.equals(text(payload, "aud"))) {
            throw unauthorized("service token issuer or audience is invalid");
        }
        if (!SCOPE.equals(text(payload, "scope"))) {
            throw unauthorized("service token scope is insufficient");
        }
        long issuedAt = number(payload, "iat");
        long expiresAt = number(payload, "exp");
        long now = clock.instant().getEpochSecond();
        long skew = properties.clockSkewSeconds();
        if (issuedAt > now + skew || expiresAt < now - skew || expiresAt <= issuedAt) {
            throw unauthorized("service token is not valid at this time");
        }
        if (expiresAt - issuedAt > properties.maxTokenAgeSeconds()) {
            throw unauthorized("service token lifetime exceeds policy");
        }
        String jti = text(payload, "jti");
        if (jti.isBlank() || jti.length() > 128) {
            throw unauthorized("service token jti is invalid");
        }
        String subject = text(payload, "sub");
        if (!subject.startsWith("user:")) {
            throw unauthorized("service token subject is not a user");
        }
        UUID userId;
        UUID runId;
        try {
            userId = UUID.fromString(subject.substring("user:".length()));
            runId = UUID.fromString(text(payload, "runId"));
        } catch (IllegalArgumentException ex) {
            throw unauthorized("service token contains invalid identifiers");
        }
        TenantId tenantId;
        try {
            tenantId = TenantId.of(text(payload, "tenantId"));
        } catch (IllegalArgumentException ex) {
            throw unauthorized("service token tenant is invalid");
        }
        if (expectedRunId == null || !expectedRunId.equals(runId)) {
            throw unauthorized("service token is not bound to this run");
        }
        return new ServiceTokenClaims(tenantId, userId, runId);
    }

    /**
     * 读取必填文本字段。
     *
     * @param payload JSON
     * @param field 字段
     * @return 文本
     */
    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw unauthorized("service token is missing field " + field);
        }
        return node.asText();
    }

    /**
     * 读取必填数字字段。
     *
     * @param payload JSON
     * @param field 字段
     * @return 数值
     */
    private static long number(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isNumber()) {
            throw unauthorized("service token is missing field " + field);
        }
        return node.asLong();
    }

    /**
     * 计算 HMAC。
     *
     * @param value 签名输入
     * @return 摘要
     */
    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            // 密钥长度或算法不可用时属于启动配置错误，必须失败而不是降级。
            throw new IllegalStateException("service token signer is unavailable", ex);
        }
    }

    /**
     * 构造统一 401。
     *
     * @param message 内部说明
     * @return 异常
     */
    private static ChatException unauthorized(String message) {
        return new ChatException(401, "RICCON-AUTH-401", "Unauthorized", message);
    }

    /**
     * 通过校验的声明。
     *
     * @param tenantId 租户
     * @param userId 用户 ID
     * @param runId 运行 ID
     */
    public record ServiceTokenClaims(TenantId tenantId, UUID userId, UUID runId) {}
}
