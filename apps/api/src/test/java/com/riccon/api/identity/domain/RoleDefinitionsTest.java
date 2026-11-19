package com.riccon.api.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.error.ErrorCodes;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import org.junit.jupiter.api.Test;

/** 内置角色与权限映射。 */
class RoleDefinitionsTest {

    /** 租户开通必须创建 TENANT_ADMIN 与 MEMBER。 */
    @Test
    void tenantSystemRolesContainAdminAndMember() {
        assertThat(RoleDefinitions.tenantSystemRoles())
                .extracting(RoleDefinition::code)
                .containsExactly(RoleCodes.TENANT_ADMIN, RoleCodes.MEMBER);
        assertThat(RoleDefinitions.tenantRole(RoleCodes.TENANT_ADMIN).permissions())
                .contains(PermissionCodes.TENANT_USER_MANAGE, PermissionCodes.TENANT_ROLE_MANAGE);
        assertThat(RoleDefinitions.tenantRole(RoleCodes.MEMBER).permissions())
                .contains(PermissionCodes.CONVERSATION_WRITE)
                .doesNotContain(PermissionCodes.TENANT_USER_MANAGE);
        assertThat(RoleDefinitions.tenantRole("UNKNOWN")).isNull();
    }

    /** 平台管理员权限不得包含租户业务权限。 */
    @Test
    void platformAdminPermissionsDoNotOverlapTenantContent() {
        RoleDefinition platform = RoleDefinitions.platformAdminRole();
        assertThat(platform.code()).isEqualTo(RoleCodes.PLATFORM_ADMIN);
        assertThat(platform.permissions())
                .contains(PermissionCodes.PLATFORM_TENANT_MANAGE)
                .doesNotContain(
                        PermissionCodes.DEPARTMENT_READ,
                        PermissionCodes.WORKSPACE_READ,
                        PermissionCodes.CONVERSATION_READ);
    }

    /** 部门管理只属于平台管理员；租户角色只有只读 department.read，且不新增 department.manage。 */
    @Test
    void departmentPermissionsFollowPlatformAndTenantBoundary() {
        assertThat(RoleDefinitions.platformAdminRole().permissions())
                .contains(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
                .doesNotContain(PermissionCodes.DEPARTMENT_READ);
        assertThat(RoleDefinitions.tenantRole(RoleCodes.TENANT_ADMIN).permissions())
                .contains(PermissionCodes.DEPARTMENT_READ)
                .doesNotContain(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE, "department.manage");
        assertThat(RoleDefinitions.tenantRole(RoleCodes.MEMBER).permissions())
                .contains(PermissionCodes.DEPARTMENT_READ)
                .doesNotContain(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE, "department.manage");
    }

    /**
     * M10 导出审批权限（ADR-0020 §8.1 的 B3a 窄例外）：新租户开通与空库引导都读取本类，
     * 因此两条审批权限必须出现在对应角色中，且 MEMBER 不获得任何审计权限。
     */
    @Test
    void exportApprovalPermissionsFollowRoleBoundary() {
        assertThat(RoleDefinitions.tenantRole(RoleCodes.TENANT_ADMIN).permissions())
                .contains(PermissionCodes.AUDIT_READ, PermissionCodes.AUDIT_EXPORT_APPROVE)
                .doesNotContain(PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE);
        assertThat(RoleDefinitions.platformAdminRole().permissions())
                .contains(PermissionCodes.AUDIT_READ, PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE)
                .doesNotContain(PermissionCodes.AUDIT_EXPORT_APPROVE);
        // 普通员工没有审计查询/导出审批，M02/M03 权限语义不变。
        assertThat(RoleDefinitions.tenantRole(RoleCodes.MEMBER).permissions())
                .doesNotContain(
                        PermissionCodes.AUDIT_READ,
                        PermissionCodes.AUDIT_EXPORT_APPROVE,
                        PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE);
    }

    /** 部门/空间成员冲突错误码稳定为 RICCON-DEPT-409，供 B2/B3 直接复用而不写字符串字面量。 */
    @Test
    void departmentConflictCodeUsesStableDeptDomain() {
        assertThat(ErrorCodes.RICCON_DEPT_409).isEqualTo("RICCON-DEPT-409");
    }
}
