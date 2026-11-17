package com.riccon.worker.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.worker.audit.domain.AuditDetailsPolicy;
import com.riccon.worker.audit.domain.AuditEvent;
import java.time.Instant;
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
