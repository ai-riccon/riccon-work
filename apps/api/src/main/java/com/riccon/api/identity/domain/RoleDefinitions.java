package com.riccon.api.identity.domain;

import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import java.util.List;
import java.util.Set;

/**
 * M02/M03 内置角色与权限映射。
 *
 * <p>权限字典在迁移中全局固定（V2 定义 M02 权限码，V9 新增部门权限码）；角色映射在租户开通时复制到租户内。
 * 平台管理员角色只存在于 {@code platform} 保留租户，且与租户角色权限不重叠（ADR-0013 第 4 节、ADR-0018
 * 第 4 节）。部门负责人不是 RBAC 角色，而由 {@code department.manager_user_id} 资源关系表达，因此本类不新增
 * 角色码。新租户开通（{@code TenantAdminService}）与空库引导（{@code BootstrapAdminRunner}）都读取本类，
 * 不维护第二份角色定义。
 *
 * <p>M10 说明：{@code audit.read} 已在 V2 权限字典中，B1 直接授予租户管理员与平台管理员；两条导出审批权限
 * （{@code audit.export.approve}/{@code platform.audit.export.approve}）的字典行由 V10 写入并回填既有角色，
 * 本类在 V10 之后补齐新租户开通与空库引导路径。这是 ADR-0020 §8.1 记录的 B3a 窄例外：本类仍由 B1 维护，
 * B3a 只新增这两条审批权限及其测试，不改变其他角色/权限语义；M10-B3b 不得再修改本文件。
 *
 * <p>M04 说明：{@code quota.read}/{@code cost.read} 授予租户管理员；{@code platform.model.manage}/
 * {@code platform.quota.manage}/{@code platform.cost.read} 只授予平台管理员。V11 负责既有租户/平台角色的
 * 回填，本类负责 V11 之后新建租户与空库引导；员工（MEMBER）不获得配额/成本入口（ADR-0021 §7.1、
 * ADR-0022 §1.5/§3.1）。
 *
 * <p>为什么必须同时改迁移与本类：V10 的 {@code INSERT ... SELECT FROM role} 只覆盖迁移前已存在的角色，
 * 覆盖不到 fresh install、空库引导和 V10 之后新建的租户；缺少这两条映射会直接断裂 AC-17 的审批链。
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
            PermissionCodes.MCP_READ,
            PermissionCodes.DEPARTMENT_READ);

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
            PermissionCodes.DEPARTMENT_READ,
            PermissionCodes.TENANT_USER_MANAGE,
            PermissionCodes.TENANT_ROLE_MANAGE,
            // M10：租户管理员可查询本租户审计（audit.read 自 M02 已在权限字典）。
            PermissionCodes.AUDIT_READ,
            // M10：同租户审计导出审批；申请人不得自批，平台审批权限只属于 PLATFORM_ADMIN。
            PermissionCodes.AUDIT_EXPORT_APPROVE,
            // M04：租户管理员只读本租户配额与成本；写权限只属于平台管理员。
            PermissionCodes.QUOTA_READ,
            PermissionCodes.COST_READ);

    /** 平台管理员只管理平台目录与租户元数据（含部门元数据），不读取租户业务内容。 */
    private static final Set<String> PLATFORM_ADMIN_PERMISSIONS = Set.of(
            PermissionCodes.PLATFORM_TENANT_MANAGE,
            PermissionCodes.PLATFORM_DEPARTMENT_MANAGE,
            PermissionCodes.PLATFORM_MODEL_READ,
            // M04：路由/模型启停/key 池/网关开关写权限，只属于平台管理员。
            PermissionCodes.PLATFORM_MODEL_MANAGE,
            // M04：跨租户配额查看与整体替换；平台成本只返回聚合，不返回用户明细。
            PermissionCodes.PLATFORM_QUOTA_MANAGE,
            PermissionCodes.PLATFORM_COST_READ,
            PermissionCodes.PLATFORM_MCP_READ,
            // M10：平台管理员只能查询平台租户自身审计（跨租户明细 defer）。
            PermissionCodes.AUDIT_READ,
            // M10：平台管理员只能审批单管理员租户的导出元数据，不能下载审计内容或审批多管理员租户。
            PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE);

    /** 租户管理员角色定义。 */
    private static final RoleDefinition TENANT_ADMIN =
            new RoleDefinition(RoleCodes.TENANT_ADMIN, "租户管理员", "管理本租户账号、角色与知识库", TENANT_ADMIN_PERMISSIONS);

    /** 普通员工角色定义。 */
    private static final RoleDefinition MEMBER =
            new RoleDefinition(RoleCodes.MEMBER, "普通员工", "使用工作空间、文件、会话与报告", MEMBER_PERMISSIONS);

    /** 平台管理员角色定义。 */
    private static final RoleDefinition PLATFORM_ADMIN = new RoleDefinition(
            RoleCodes.PLATFORM_ADMIN, "平台管理员", "管理租户、部门元数据与平台目录，不读取租户业务内容", PLATFORM_ADMIN_PERMISSIONS);

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
