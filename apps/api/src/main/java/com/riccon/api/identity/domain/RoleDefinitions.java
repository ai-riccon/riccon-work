package com.riccon.api.identity.domain;

import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import java.util.List;
import java.util.Set;

/**
 * M02 内置角色与权限映射。
 *
 * <p>权限字典在 V2 迁移中全局固定；角色映射在租户开通时复制到租户内。平台管理员角色只存在于
 * {@code platform} 保留租户，且与租户角色权限不重叠（ADR-0013 第 4 节）。
 */
public final class RoleDefinitions {

    /** 普通员工权限：工作空间读取、文件、会话、知识库读取、报告与产物。 */
    private static final Set<String> MEMBER_PERMISSIONS = Set.of(
            PermissionCodes.WORKSPACE_READ,
            PermissionCodes.FILE_READ,
            PermissionCodes.FILE_UPLOAD,
            PermissionCodes.FILE_DELETE,
            PermissionCodes.CONVERSATION_READ,
            PermissionCodes.CONVERSATION_WRITE,
            PermissionCodes.KNOWLEDGE_READ,
            PermissionCodes.REPORT_READ,
            PermissionCodes.REPORT_CREATE,
            PermissionCodes.REPORT_APPROVE,
            PermissionCodes.ARTIFACT_READ,
            PermissionCodes.MODEL_READ,
            PermissionCodes.MCP_READ);

    /** 租户管理员在员工权限之上增加团队空间、知识库维护与账号/角色管理。 */
    private static final Set<String> TENANT_ADMIN_PERMISSIONS = Set.of(
            PermissionCodes.WORKSPACE_READ,
            PermissionCodes.WORKSPACE_WRITE,
            PermissionCodes.FILE_READ,
            PermissionCodes.FILE_UPLOAD,
            PermissionCodes.FILE_DELETE,
            PermissionCodes.CONVERSATION_READ,
            PermissionCodes.CONVERSATION_WRITE,
            PermissionCodes.KNOWLEDGE_READ,
            PermissionCodes.KNOWLEDGE_MANAGE,
            PermissionCodes.REPORT_READ,
            PermissionCodes.REPORT_CREATE,
            PermissionCodes.REPORT_APPROVE,
            PermissionCodes.ARTIFACT_READ,
            PermissionCodes.MODEL_READ,
            PermissionCodes.MCP_READ,
            PermissionCodes.TENANT_USER_MANAGE,
            PermissionCodes.TENANT_ROLE_MANAGE);

    /** 平台管理员只管理平台目录与租户元数据，不读取租户业务内容。 */
    private static final Set<String> PLATFORM_ADMIN_PERMISSIONS = Set.of(
            PermissionCodes.PLATFORM_TENANT_MANAGE,
            PermissionCodes.PLATFORM_MODEL_READ,
            PermissionCodes.PLATFORM_MCP_READ);

    /** 租户管理员角色定义。 */
    private static final RoleDefinition TENANT_ADMIN =
            new RoleDefinition(RoleCodes.TENANT_ADMIN, "租户管理员", "管理本租户账号、角色与知识库", TENANT_ADMIN_PERMISSIONS);

    /** 普通员工角色定义。 */
    private static final RoleDefinition MEMBER =
            new RoleDefinition(RoleCodes.MEMBER, "普通员工", "使用工作空间、文件、会话与报告", MEMBER_PERMISSIONS);

    /** 平台管理员角色定义。 */
    private static final RoleDefinition PLATFORM_ADMIN =
            new RoleDefinition(RoleCodes.PLATFORM_ADMIN, "平台管理员", "管理租户与平台目录，不读取租户业务内容", PLATFORM_ADMIN_PERMISSIONS);

    /** 工具类禁止实例化。 */
    private RoleDefinitions() {}

    /**
     * 返回新租户开通时必须创建的系统角色。
     *
     * @return 租户管理员与普通员工两个系统角色
     */
    public static List<RoleDefinition> tenantSystemRoles() {
        return List.of(TENANT_ADMIN, MEMBER);
    }

    /**
     * 返回平台管理员角色定义。
     *
     * @return 平台管理员角色
     */
    public static RoleDefinition platformAdminRole() {
        return PLATFORM_ADMIN;
    }

    /**
     * 按角色码查找租户内系统角色。
     *
     * @param code 角色码
     * @return 找到时返回定义，否则返回 {@code null}
     */
    public static RoleDefinition tenantRole(String code) {
        if (RoleCodes.TENANT_ADMIN.equals(code)) {
            return TENANT_ADMIN;
        }
        if (RoleCodes.MEMBER.equals(code)) {
            return MEMBER;
        }
        return null;
    }
}
