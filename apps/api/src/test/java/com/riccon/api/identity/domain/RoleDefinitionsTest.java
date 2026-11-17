package com.riccon.api.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
                .doesNotContain(PermissionCodes.WORKSPACE_READ, PermissionCodes.CONVERSATION_READ);
    }
}
