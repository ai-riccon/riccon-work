package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M10 共享词汇与审计 details 白名单（B1 唯一 owner，B3 只消费）。
 *
 * <p>覆盖 ADR-0020 第 6.1 节：动作/权限/错误码稳定；白名单优先，{@code promptTokens}/{@code completionTokens}
 * 等合法指标不被 token 子串规则误杀；token/secret/content 等敏感键仍被拒绝。
 */
class M10VocabularyTest {

    /** 审计动作词汇稳定。 */
    @Test
    void auditActions_exposeM10Vocabulary() {
        assertThat(AuditActions.AUDIT_EXPORT_REQUESTED).isEqualTo("audit.export.requested");
        assertThat(AuditActions.AUDIT_EXPORT_APPROVED).isEqualTo("audit.export.approved");
        assertThat(AuditActions.AUDIT_EXPORT_REJECTED).isEqualTo("audit.export.rejected");
        assertThat(AuditActions.USER_OIDC_BOUND).isEqualTo("user.oidc.bound");
        assertThat(AuditActions.USER_OIDC_UNBOUND).isEqualTo("user.oidc.unbound");
        // M10 把已写入动作提升为共享词汇，查询/导出不会出现“未知动作”。
        assertThat(AuditActions.TENANT_UPDATED).isEqualTo("tenant.updated");
        assertThat(AuditActions.TENANT_ACCESS_DENIED).isEqualTo("tenant.access.denied");
        assertThat(AuditActions.SESSION_DELETED).isEqualTo("session.deleted");
        assertThat(AuditActions.MESSAGE_SENT).isEqualTo("message.sent");
        assertThat(AuditActions.MESSAGE_STOPPED).isEqualTo("message.stopped");
        assertThat(AuditActions.RESEARCH_TASK_CREATED).isEqualTo("research.task.created");
        assertThat(AuditActions.ARTIFACT_DOWNLOADED).isEqualTo("artifact.downloaded");
        assertThat(AuditActions.USER_ACCESS_DENIED).isEqualTo("user.access.denied");
        assertThat(AuditTargetType.AUDIT_EXPORT).isNotNull();
    }

    /** 权限与错误码稳定；audit.read 已授予租户管理员与平台管理员。 */
    @Test
    void permissionsAndErrorCodes_areStable() {
        assertThat(PermissionCodes.AUDIT_EXPORT_APPROVE).isEqualTo("audit.export.approve");
        assertThat(PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE).isEqualTo("platform.audit.export.approve");
        assertThat(RoleDefinitions.tenantRole(RoleCodes.TENANT_ADMIN).permissions())
                .contains(PermissionCodes.AUDIT_READ);
        assertThat(RoleDefinitions.platformAdminRole().permissions()).contains(PermissionCodes.AUDIT_READ);

        assertThat(ErrorCodes.OIDC_INVALID_REQUEST).isEqualTo("RICCON-OIDC-400");
        assertThat(ErrorCodes.OIDC_BINDING_CONFLICT).isEqualTo("RICCON-OIDC-409");
        assertThat(ErrorCodes.AUDIT_INVALID_REQUEST).isEqualTo("RICCON-AUDIT-400");
        assertThat(ErrorCodes.AUDIT_FORBIDDEN).isEqualTo("RICCON-AUDIT-403");
        assertThat(ErrorCodes.AUDIT_CONFLICT).isEqualTo("RICCON-AUDIT-409");
        assertThat(ErrorCodes.AUDIT_EXPORT_EXPIRED).isEqualTo("RICCON-AUDIT-410");
        assertThat(ErrorCodes.AUDIT_DETAILS_INVALID).isEqualTo("RICCON-AUDIT-500");
    }

    /** 审计 details 白名单：M10 动作与 worker 指标均可写入/读取。 */
    @Test
    void auditDetailsPolicy_allowsM10AndWorkerDetails() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUDIT_QUERIED, Map.of("filtersHash", "a".repeat(64), "rowCount", 3)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.AUDIT_EXPORT_APPROVED,
                        Map.of("exportId", "id", "decision", "APPROVE", "estimatedRowCount", 10)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.USER_OIDC_BOUND,
                        Map.of(
                                "userId",
                                "id",
                                "issuerHash",
                                "b".repeat(64),
                                "subjectHash",
                                "c".repeat(64),
                                "previousAuthSource",
                                "LOCAL")))
                .doesNotThrowAnyException();
        // F-003：token 计数指标是合法白名单键，不能被 "token" 子串误杀。
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.MODEL_INVOCATION_COMPLETED,
                        Map.of("modelId", "fake-model", "promptTokens", 10, "completionTokens", 20)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.FILE_UPLOADED, Map.of("fileId", "id", "contentType", "application/pdf")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.AUTH_LOGOUT, Map.of()))
                .doesNotThrowAnyException();
    }

    /** 敏感键仍被拒绝：token/secret/content 与白名单外键。 */
    @Test
    void auditDetailsPolicy_rejectsSensitiveOrUnknownKeys() {
        assertThatThrownBy(
                        () -> AuditDetailsPolicy.validate(AuditActions.AUDIT_EXPORT_APPROVED, Map.of("token", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.AUDIT_QUERIED, Map.of("secret", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.AUTH_LOGOUT, Map.of("content", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validate(AuditActions.USER_OIDC_BOUND, Map.of("subject", "raw-subject")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.AUDIT_QUERIED, Map.of("unknownKey", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }
}
