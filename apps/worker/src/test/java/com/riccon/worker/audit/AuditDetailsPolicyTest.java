package com.riccon.worker.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.worker.audit.domain.AuditDetailsPolicy;
import com.riccon.worker.audit.domain.AuditEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 审计 details 白名单与事件必填字段。 */
class AuditDetailsPolicyTest {

    /** 白名单字段通过，包括包含 token 子串的指标名。 */
    @Test
    void validate_allowedKeys_passes() {
        AuditDetailsPolicy.validate(
                AuditActions.MODEL_INVOCATION_COMPLETED,
                Map.of(
                        "modelId",
                        UUID.randomUUID().toString(),
                        "durationMs",
                        10,
                        "promptTokens",
                        1,
                        "completionTokens",
                        2));
        AuditDetailsPolicy.validate(
                AuditActions.MCP_TOOL_INVOKED,
                Map.of("toolName", "echo", "paramsSha256", "a".repeat(64), "durationMs", 1));
    }

    /** 未授权、敏感键与非法值都被拒绝。 */
    @Test
    void validate_forbidden_fails() {
        assertThatThrownBy(
                        () -> AuditDetailsPolicy.validate(AuditActions.MODEL_INVOCATION_STARTED, Map.of("prompt", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validate(AuditActions.MODEL_INVOCATION_STARTED, Map.of("unknown", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        AuditDetailsPolicy.validate(AuditActions.MODEL_INVOCATION_STARTED, Map.of("modelId", ""));
        java.util.Map<String, Object> nullValue = new java.util.HashMap<>();
        nullValue.put("modelId", null);
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.MODEL_INVOCATION_STARTED, nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    /** 超长值与非基本类型被拒绝。 */
    @Test
    void validate_valueConstraints_fail() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_STARTED, Map.of("modelId", "x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_STARTED, Map.of("modelId", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    /** details 为 null 等价于空 details；未登记动作只允许空 details。 */
    @Test
    void validate_nullDetails_passes() {
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.ARTIFACT_SAVED, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate("future.action", null)).doesNotThrowAnyException();
    }

    /** 空键与空白键必须 fail-closed，避免无键名写入。 */
    @Test
    void validate_nullOrBlankKey_fails() {
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.ARTIFACT_SAVED, nullKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validate(AuditActions.ARTIFACT_SAVED, Map.<String, Object>of("  ", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    /** Boolean 值可写入；emitted 只属于 model.invocation.failed。 */
    @Test
    void validate_booleanValuesAndEmittedScopedToFailed() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_FAILED,
                        Map.of(
                                "modelId",
                                UUID.randomUUID().toString(),
                                "durationMs",
                                10,
                                "errorCode",
                                "RICCON-MODEL-502",
                                "retryable",
                                true,
                                "emitted",
                                true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_FAILED,
                        Map.of("modelId", "m", "retryable", false, "emitted", false)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_STARTED, Map.of("modelId", "m", "emitted", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    /** 数组/Map 等复杂值不能借白名单键绕过基本类型校验。 */
    @Test
    void validate_listOrMapValueOnNonArrayKey_fails() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_STARTED,
                        Map.of("modelId", List.of(UUID.randomUUID().toString()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_STARTED, Map.of("modelId", Map.of("nested", "value"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    /** M04 降级与凭据失效动作的白名单字段可写入（credentialId 不被 credential 子串误杀）。 */
    @Test
    void validate_m04FailoverAndCredentialActions_pass() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_FAILOVER_TRIGGERED,
                        Map.of(
                                "purpose",
                                "CHAT",
                                "fromModelId",
                                UUID.randomUUID().toString(),
                                "toModelId",
                                UUID.randomUUID().toString(),
                                "reason",
                                "RATE_LIMITED",
                                "attempt",
                                2)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_CREDENTIAL_INVALIDATED,
                        Map.of(
                                "providerId",
                                UUID.randomUUID().toString(),
                                "credentialId",
                                UUID.randomUUID().toString(),
                                "maskedHint",
                                "****a1b2",
                                "reason",
                                "UNAUTHORIZED")))
                .doesNotThrowAnyException();
    }

    /** M04 敏感子串全部拒绝；promptTokens/completionTokens 仍按白名单放行。 */
    @Test
    void validate_sensitiveM04Keys_fail() {
        for (String key : List.of("apiKey", "ciphertext", "authorization", "baseUrl", "prompt", "content", "token")) {
            assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                            AuditActions.MODEL_CREDENTIAL_INVALIDATED, Map.<String, Object>of(key, "secret")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("forbidden");
        }
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_COMPLETED,
                        Map.of("modelId", "m", "promptTokens", 10, "completionTokens", 20)))
                .doesNotThrowAnyException();
    }

    /**
     * Worker 不写路由/配额动作；这些动作在 Worker 白名单中保持 fail-closed。
     *
     * <p>数组与 nullable 的“允许”语义只在 API 策略登记（B3/B6 写入方），Worker 侧只验证自身动作，
     * 避免把平台配置事件的 details 放宽到 worker 进程。
     */
    @Test
    void validate_apiOnlyM04Actions_failClosedOnWorker() {
        List<String> modelIds =
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_ROUTING_UPDATED,
                        Map.of("purpose", "CHAT", "modelIds", modelIds, "version", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed for " + AuditActions.MODEL_ROUTING_UPDATED);

        Map<String, Object> quota = new HashMap<>();
        quota.put("scopeType", "TENANT");
        quota.put("scopeId", "acme");
        quota.put("window", "DAILY");
        quota.put("previousLimit", null);
        quota.put("limit", 100);
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.QUOTA_LIMIT_CHANGED, quota))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed for " + AuditActions.QUOTA_LIMIT_CHANGED);
    }

    /** 事件必填字段与版本校验。 */
    @Test
    void event_invalidFields_fail() {
        assertThatThrownBy(() -> event("", "action", "trace")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("tenant", "", "trace")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("tenant", "action", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(
                        UUID.randomUUID(),
                        Instant.now(),
                        "tenant",
                        AuditActorType.SERVICE,
                        "worker",
                        "action",
                        AuditTargetType.MODEL,
                        null,
                        AuditOutcome.SUCCESS,
                        "trace",
                        null,
                        Map.of(),
                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造合法事件。 */
    private static AuditEvent event(String tenant, String action, String trace) {
        return new AuditEvent(
                UUID.randomUUID(),
                Instant.now(),
                tenant,
                AuditActorType.SERVICE,
                "worker",
                action,
                AuditTargetType.MODEL,
                null,
                AuditOutcome.SUCCESS,
                trace,
                null,
                Map.of(),
                1);
    }
}
