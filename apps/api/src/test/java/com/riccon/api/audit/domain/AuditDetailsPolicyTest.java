package com.riccon.api.audit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.organization.DepartmentStatus;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
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

    /** 文件上传与删除只允许合同定义的摘要字段。 */
    @Test
    void fileActionsAllowOnlyContractKeys() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.FILE_UPLOADED,
                        Map.of(
                                "fileId",
                                "0193",
                                "sizeBytes",
                                1024,
                                "contentType",
                                "application/pdf",
                                "sha256",
                                "a".repeat(64))))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.FILE_DELETED, Map.of("fileId", "0193", "objectKeyHash", "b".repeat(64))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.FILE_UPLOADED, Map.of("fileId", "0193", "originalName", "secret.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    /** 会话与消息动作只允许 ID、计数与原因，不允许内容。 */
    @Test
    void conversationAndMessageActionsUseWhitelistedIds() {
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.SESSION_CREATED, Map.of("sessionId", "0193")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AdditionalAuditActions.SESSION_DELETED, Map.of("sessionId", "0193")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AdditionalAuditActions.MESSAGE_SENT,
                        Map.of(
                                "userMessageId", "0193",
                                "assistantMessageId", "0194",
                                "runId", "0195",
                                "attachmentCount", 2)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AdditionalAuditActions.MESSAGE_STOPPED,
                        Map.of("messageId", "0194", "runId", "0195", "reason", "USER_CANCELLED")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validate(AdditionalAuditActions.MESSAGE_SENT, Map.of("content", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    /** M03 部门动作只允许 ADR-0018 第 5 节列出的组织元数据键。 */
    @Test
    void departmentActionsAllowOnlyContractKeys() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_CREATED,
                        Map.of(
                                "targetTenantId", "tenant-a",
                                "departmentId", "0193",
                                "name", "研发部",
                                "status", DepartmentStatus.ACTIVE.name())))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_RENAMED,
                        Map.of("departmentId", "0193", "name", "平台研发部", "previousName", "研发部")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_DISABLED,
                        Map.of(
                                "targetTenantId",
                                "tenant-a",
                                "departmentId",
                                "0193",
                                "status",
                                DepartmentStatus.DISABLED.name(),
                                "memberCount",
                                12)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_ENABLED,
                        Map.of("departmentId", "0193", "status", DepartmentStatus.ACTIVE.name(), "memberCount", 12)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_MEMBER_ADDED,
                        Map.of(
                                "targetTenantId", "tenant-a",
                                "departmentId", "0193",
                                "userId", "0194",
                                "previousDepartmentId", "0192")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_MEMBER_REMOVED,
                        Map.of(
                                "targetTenantId", "tenant-a",
                                "departmentId", "0193",
                                "userId", "0194",
                                "removedWorkspaceMembers", 2)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_MANAGER_CHANGED,
                        Map.of(
                                "targetTenantId", "tenant-a",
                                "departmentId", "0193",
                                "managerUserId", "0195",
                                "previousManagerUserId", "0194")))
                .doesNotThrowAnyException();
    }

    /** 部门成员名单与邮箱等 PII 不得进入审计 details。 */
    @Test
    void departmentActionsRejectMemberListAndPii() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_MEMBER_REMOVED,
                        Map.of("departmentId", "0193", "memberEmail", "alice@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.DEPARTMENT_CREATED, Map.of("departmentId", "0193", "memberList", "alice,bob")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    /** M03 空间动作只允许 ID、名称与可见性枚举。 */
    @Test
    void workspaceActionsAllowOnlyContractKeys() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_CREATED,
                        Map.of(
                                "workspaceId", "0193",
                                "departmentId", "0194",
                                "visibility", WorkspaceVisibility.DEPT.name())))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_UPDATED,
                        Map.of("workspaceId", "0193", "name", "研发空间", "previousName", "旧空间名")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_VISIBILITY_CHANGED,
                        Map.of(
                                "workspaceId",
                                "0193",
                                "visibility",
                                WorkspaceVisibility.CUSTOM.name(),
                                "previousVisibility",
                                WorkspaceVisibility.DEPT.name(),
                                "departmentId",
                                "0194")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_MEMBER_ADDED, Map.of("workspaceId", "0193", "userId", "0194")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_MEMBER_REMOVED, Map.of("workspaceId", "0193", "userId", "0194")))
                .doesNotThrowAnyException();
    }

    /** 空间成员名单与内容不得进入审计 details。 */
    @Test
    void workspaceActionsRejectMemberListAndContent() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_MEMBER_ADDED,
                        Map.of("workspaceId", "0193", "userId", "0194", "memberList", "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKSPACE_VISIBILITY_CHANGED,
                        Map.of("workspaceId", "0193", "visibility", "CUSTOM", "content", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }
}
