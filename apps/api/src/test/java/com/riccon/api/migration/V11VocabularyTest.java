package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M04 权限码、错误码、审计动作/目标类型与 details 白名单的纯单元测试（B1a 词汇预置）。
 *
 * <p>不启动 Spring 或数据库：迁移测试验证数据库行与约束，本测试验证后续 B2–B7 依赖的稳定常量与
 * fail-closed 校验语义，避免“迁移有行但代码常量缺失”或“数组/nullable 放宽为任意 JSON”。
 */
class V11VocabularyTest {

    /** 权限码值必须与迁移/OpenAPI 的 x-riccon-permission 一致。 */
    @Test
    @DisplayName("M04 权限码与角色定义预置完整")
    void permissionCodesAndRoles_includeM04() {
        assertThat(PermissionCodes.PLATFORM_MODEL_MANAGE).isEqualTo("platform.model.manage");
        assertThat(PermissionCodes.QUOTA_READ).isEqualTo("quota.read");
        assertThat(PermissionCodes.PLATFORM_QUOTA_MANAGE).isEqualTo("platform.quota.manage");
        assertThat(PermissionCodes.COST_READ).isEqualTo("cost.read");
        assertThat(PermissionCodes.PLATFORM_COST_READ).isEqualTo("platform.cost.read");

        RoleDefinition tenantAdmin = RoleDefinitions.tenantRole("TENANT_ADMIN");
        assertThat(tenantAdmin.permissions())
                .contains(PermissionCodes.QUOTA_READ, PermissionCodes.COST_READ)
                .doesNotContain(
                        PermissionCodes.PLATFORM_MODEL_MANAGE,
                        PermissionCodes.PLATFORM_QUOTA_MANAGE,
                        PermissionCodes.PLATFORM_COST_READ);
        RoleDefinition member = RoleDefinitions.tenantRole("MEMBER");
        assertThat(member.permissions()).doesNotContain(PermissionCodes.QUOTA_READ, PermissionCodes.COST_READ);
        assertThat(RoleDefinitions.platformAdminRole().permissions())
                .contains(
                        PermissionCodes.PLATFORM_MODEL_READ,
                        PermissionCodes.PLATFORM_MODEL_MANAGE,
                        PermissionCodes.PLATFORM_QUOTA_MANAGE,
                        PermissionCodes.PLATFORM_COST_READ)
                .doesNotContain(PermissionCodes.QUOTA_READ, PermissionCodes.COST_READ);
    }

    /** 对外错误码必须使用 ADR 定义的稳定值，不复用旧码语义。 */
    @Test
    @DisplayName("M04 错误码稳定")
    void errorCodes_areStable() {
        assertThat(ErrorCodes.MODEL_INVALID_REQUEST).isEqualTo("RICCON-MODEL-400");
        assertThat(ErrorCodes.MODEL_CONFLICT).isEqualTo("RICCON-MODEL-409");
        assertThat(ErrorCodes.MODEL_STREAM_INTERRUPTED).isEqualTo("RICCON-MODEL-502");
        assertThat(ErrorCodes.MODEL_UNAVAILABLE).isEqualTo("RICCON-MODEL-503");
        assertThat(ErrorCodes.MODEL_STREAM_TIMEOUT).isEqualTo("RICCON-MODEL-504");
        assertThat(ErrorCodes.QUOTA_INVALID_REQUEST).isEqualTo("RICCON-QUOTA-400");
        assertThat(ErrorCodes.QUOTA_CONFLICT).isEqualTo("RICCON-QUOTA-409");
        assertThat(ErrorCodes.QUOTA_EXCEEDED).isEqualTo("RICCON-QUOTA-429");
        assertThat(ErrorCodes.QUOTA_UNAVAILABLE).isEqualTo("RICCON-QUOTA-503");
        assertThat(ErrorCodes.COST_INVALID_REQUEST).isEqualTo("RICCON-COST-400");
        assertThat(ErrorCodes.COST_UNAVAILABLE).isEqualTo("RICCON-COST-503");
    }

    /** 审计动作名与目标类型必须覆盖 ADR-0021/0022 的全部 M04 事件。 */
    @Test
    @DisplayName("M04 审计动作与目标类型完整")
    void auditActionsAndTargets_includeM04() {
        assertThat(AuditActions.MODEL_ROUTING_UPDATED).isEqualTo("model.routing.updated");
        assertThat(AuditActions.MODEL_UPDATED).isEqualTo("model.updated");
        assertThat(AuditActions.MODEL_FAILOVER_TRIGGERED).isEqualTo("model.failover.triggered");
        assertThat(AuditActions.MODEL_CREDENTIAL_CREATED).isEqualTo("model.credential.created");
        assertThat(AuditActions.MODEL_CREDENTIAL_ROTATED).isEqualTo("model.credential.rotated");
        assertThat(AuditActions.MODEL_CREDENTIAL_DISABLED).isEqualTo("model.credential.disabled");
        assertThat(AuditActions.MODEL_CREDENTIAL_ENABLED).isEqualTo("model.credential.enabled");
        assertThat(AuditActions.MODEL_CREDENTIAL_INVALIDATED).isEqualTo("model.credential.invalidated");
        assertThat(AuditActions.MODEL_CREDENTIAL_RECOVERED).isEqualTo("model.credential.recovered");
        assertThat(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).isEqualTo("model.gateway.switch.changed");
        assertThat(AuditActions.QUOTA_LIMIT_CHANGED).isEqualTo("quota.limit.changed");
        assertThat(AuditActions.QUOTA_EXCEEDED).isEqualTo("quota.exceeded");

        assertThat(AuditTargetType.valueOf("MODEL_ROUTING")).isNotNull();
        assertThat(AuditTargetType.valueOf("MODEL_CREDENTIAL")).isNotNull();
        assertThat(AuditTargetType.valueOf("QUOTA")).isNotNull();
    }

    /** model.routing.updated 的 modelIds 只允许最多 20 个 UUID 的数组。 */
    @Test
    @DisplayName("路由审计 details 支持受限 UUID 数组")
    void routingDetails_allowBoundedUuidArray() {
        List<String> modelIds = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            modelIds.add(UUID.randomUUID().toString());
        }
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_ROUTING_UPDATED,
                        Map.of(
                                "purpose",
                                "CHAT",
                                "modelIds",
                                modelIds,
                                "version",
                                2,
                                "previousVersion",
                                1,
                                "result",
                                "SUCCESS")))
                .doesNotThrowAnyException();

        List<String> tooMany = new ArrayList<>(modelIds);
        tooMany.add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_ROUTING_UPDATED,
                        Map.of("purpose", "CHAT", "modelIds", tooMany, "version", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many");

        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_ROUTING_UPDATED,
                        Map.of("purpose", "CHAT", "modelIds", List.of("not-a-uuid"), "version", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");

        // 数组能力不能扩散到其他动作/字段：modelIds 在 model.updated 中不是白名单键。
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.MODEL_UPDATED, Map.of("modelIds", modelIds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        // 嵌套数组/对象继续 fail-closed。
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_ROUTING_UPDATED,
                        Map.of("purpose", "CHAT", "modelIds", List.of(List.of("nested")), "version", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("element type");
    }

    /** quota.limit.changed.previousLimit 首次设置允许 JSON null，其他键不得 null。 */
    @Test
    @DisplayName("配额审计 details 仅 previousLimit 允许 null")
    void quotaLimitDetails_allowNullablePreviousLimit() {
        Map<String, Object> details = new HashMap<>();
        details.put("scopeType", "TENANT");
        details.put("scopeId", "acme");
        details.put("window", "DAILY");
        details.put("previousLimit", null);
        details.put("limit", 100);
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.QUOTA_LIMIT_CHANGED, details))
                .doesNotThrowAnyException();

        details.put("previousLimit", 50);
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.QUOTA_LIMIT_CHANGED, details))
                .doesNotThrowAnyException();

        Map<String, Object> nullWindow = new HashMap<>(details);
        nullWindow.put("window", null);
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.QUOTA_LIMIT_CHANGED, nullWindow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        // nullable 只属于动作级白名单，不得扩散到其他动作。
        assertThatThrownBy(() -> {
                    Map<String, Object> modelDetails = new HashMap<>();
                    modelDetails.put("modelId", null);
                    AuditDetailsPolicy.validate(AuditActions.MODEL_UPDATED, modelDetails);
                })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    /** 敏感子串（apiKey/ciphertext/authorization/baseUrl/prompt/content/token）必须拒绝。 */
    @Test
    @DisplayName("M04 审计 details 拒绝敏感键")
    void sensitiveKeys_areRejected() {
        for (String key : List.of("apiKey", "ciphertext", "authorization", "baseUrl", "prompt", "content")) {
            assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                            AuditActions.MODEL_CREDENTIAL_CREATED,
                            Map.of("providerId", UUID.randomUUID().toString(), key, "secret")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("forbidden");
        }
        // 白名单键优先：promptTokens/completionTokens 是既有合法指标，不会被 token 子串误杀。
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_COMPLETED,
                        Map.of(
                                "modelId",
                                UUID.randomUUID().toString(),
                                "durationMs",
                                10,
                                "promptTokens",
                                1,
                                "completionTokens",
                                2)))
                .doesNotThrowAnyException();
    }

    /** M04 新增动作的 details 白名单可用，未登记字段仍 fail-closed。 */
    @Test
    @DisplayName("M04 审计 details 白名单可用且未登记键拒绝")
    void m04Details_useWhitelists() {
        UUID providerId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_CREDENTIAL_CREATED,
                        Map.of(
                                "providerId",
                                providerId.toString(),
                                "credentialId",
                                credentialId.toString(),
                                "maskedHint",
                                "****a1b2",
                                "status",
                                "ACTIVE")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_CREDENTIAL_ROTATED,
                        Map.of(
                                "providerId",
                                providerId.toString(),
                                "credentialId",
                                credentialId.toString(),
                                "previousCredentialId",
                                UUID.randomUUID().toString(),
                                "maskedHint",
                                "****a1b2",
                                "status",
                                "ACTIVE")))
                .doesNotThrowAnyException();
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
                        AuditActions.MODEL_GATEWAY_SWITCH_CHANGED,
                        Map.of("switch", "failoverEnabled", "value", "false", "previousValue", "true", "source", "DB")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.QUOTA_EXCEEDED,
                        Map.of(
                                "scopeType",
                                "TENANT",
                                "scopeId",
                                "acme",
                                "window",
                                "DAILY",
                                "limit",
                                100,
                                "used",
                                100,
                                "resetsAt",
                                "2026-12-01T00:00:00+08:00")))
                .doesNotThrowAnyException();
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

        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_CREDENTIAL_CREATED,
                        Map.of("providerId", providerId.toString(), "plaintext", "sk-live")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }
}
