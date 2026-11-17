package com.riccon.api.audit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.audit.AuditActions;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 审计 details 白名单校验。 */
class AuditDetailsPolicyTest {

    /** 白名单字段与基本类型必须通过。 */
    @Test
    void allowedKeysPass() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUTH_LOGIN_SUCCEEDED, Map.of("method", "password", "sessionIdHash", "abc123")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUTH_LOGIN_FAILED, Map.of("reasonCode", "bad_password", "attemptCount", 3)))
                .doesNotThrowAnyException();
    }

    /** 未授权键必须拒绝。 */
    @Test
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUTH_LOGIN_SUCCEEDED, Map.of("method", "password", "username", "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    /** 敏感键即使在白名单动作下也必须拒绝。 */
    @Test
    void forbiddenKeyIsRejected() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.USER_CREATED, Map.of("role", "MEMBER", "password", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    /** 空值与复杂对象必须拒绝，避免把原文写入审计。 */
    @Test
    void invalidValueTypesAreRejected() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUTH_LOGIN_FAILED, java.util.Collections.singletonMap("reasonCode", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validate(AuditActions.AUTH_LOGIN_FAILED, Map.of("reasonCode", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUTH_LOGIN_FAILED, Map.of("reasonCode", "x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    /** 未登记动作只允许空 details。 */
    @Test
    void unknownActionOnlyAllowsEmptyDetails() {
        assertThatCode(() -> AuditDetailsPolicy.validate("future.action", Map.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditDetailsPolicy.validate("future.action", Map.of("method", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
