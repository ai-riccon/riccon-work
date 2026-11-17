package com.riccon.api.admin.repository;

import com.riccon.api.admin.persistence.AdminJdbc;
import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.TenantStatus;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台管理仓储。
 *
 * <p>只负责持久化编排；租户创建、权限校验与审计在 service 中完成。跨租户访问必须显式传入目标租户。
 */
@Repository
public class AdminRepository {

    /** 平台管理 SQL。 */
    private final AdminJdbc adminJdbc;

    /**
     * 创建平台管理仓储。
     *
     * @param adminJdbc 平台管理 SQL
     */
    public AdminRepository(AdminJdbc adminJdbc) {
        this.adminJdbc = Objects.requireNonNull(adminJdbc, "adminJdbc must not be null");
    }

    /**
     * 查询租户。
     *
     * @param tenantId 租户代码
     * @return 租户元数据
     */
    public Optional<TenantRecord> findTenant(String tenantId) {
        return adminJdbc.findTenant(tenantId);
    }

    /**
     * 分页列出租户。
     *
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 租户列表
     */
    public List<TenantRecord> listTenants(int limit, int offset) {
        return adminJdbc.listTenants(limit, offset);
    }

    /**
     * 统计租户总数。
     *
     * @return 租户数
     */
    public long countTenants() {
        return adminJdbc.countTenants();
    }

    /**
     * 插入租户。
     *
     * @param tenant 租户元数据
     */
    public void insertTenant(TenantRecord tenant) {
        adminJdbc.insertTenant(tenant);
    }

    /**
     * 更新租户状态与保留策略。
     *
     * @param tenantId 租户代码
     * @param status 目标状态
     * @param retentionDays 目标保留天数
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateTenant(String tenantId, TenantStatus status, Integer retentionDays, Instant now) {
        return adminJdbc.updateTenant(tenantId, status, retentionDays, now);
    }

    /**
     * 按角色码查询角色 ID。
     *
     * @param tenantId 目标租户
     * @param code 角色码
     * @return 角色 ID
     */
    public Optional<UUID> findRoleId(TenantId tenantId, String code) {
        return adminJdbc.findRoleId(tenantId, code);
    }

    /**
     * 创建角色及权限映射。
     *
     * @param tenantId 目标租户
     * @param definition 角色定义
     * @return 角色 ID
     */
    public UUID createRole(TenantId tenantId, RoleDefinition definition) {
        UUID roleId = com.riccon.api.common.UuidV7.generate();
        adminJdbc.insertRole(tenantId, roleId, definition.code(), definition.name(), definition.description());
        for (String permission : definition.permissions()) {
            adminJdbc.insertRolePermission(tenantId, roleId, permission);
        }
        return roleId;
    }

    /**
     * 查询账号。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 账号
     */
    public Optional<UserAccount> findUser(TenantId tenantId, UUID userId) {
        return adminJdbc.findUser(tenantId, userId);
    }

    /**
     * 按登录名查询账号。
     *
     * @param tenantId 目标租户
     * @param username 登录名
     * @return 账号
     */
    public Optional<UserAccount> findUserByUsername(TenantId tenantId, String username) {
        return adminJdbc.findUserByUsername(tenantId, username);
    }

    /**
     * 分页列出账号。
     *
     * @param tenantId 目标租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 账号列表
     */
    public List<UserAccount> listUsers(TenantId tenantId, int limit, int offset) {
        return adminJdbc.listUsers(tenantId, limit, offset);
    }

    /**
     * 统计账号总数。
     *
     * @param tenantId 目标租户
     * @return 账号数
     */
    public long countUsers(TenantId tenantId) {
        return adminJdbc.countUsers(tenantId);
    }

    /**
     * 批量查询账号角色。
     *
     * @param tenantId 目标租户
     * @param userIds 账号 ID 列表
     * @return 账号 ID 到角色码列表的映射
     */
    public Map<UUID, List<String>> rolesForUsers(TenantId tenantId, List<UUID> userIds) {
        return adminJdbc.rolesForUsers(tenantId, userIds);
    }

    /**
     * 查询单账号角色。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 角色码列表
     */
    public List<String> rolesForUser(TenantId tenantId, UUID userId) {
        return adminJdbc.rolesForUser(tenantId, userId);
    }

    /**
     * 插入账号与密码凭据。
     *
     * @param tenantId 目标租户
     * @param user 账号
     * @param passwordHash 密码哈希
     */
    public void insertUser(TenantId tenantId, UserAccount user, String passwordHash) {
        adminJdbc.insertUser(tenantId, user);
        adminJdbc.insertCredential(tenantId, user.id(), passwordHash);
    }

    /**
     * 替换账号角色。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param roleIds 角色 ID 列表
     * @param grantedBy 授予人
     */
    public void replaceUserRoles(TenantId tenantId, UUID userId, List<UUID> roleIds, UUID grantedBy) {
        adminJdbc.replaceUserRoles(tenantId, userId, roleIds, grantedBy);
    }

    /**
     * 更新账号状态。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param status 目标状态
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateUserStatus(TenantId tenantId, UUID userId, UserStatus status, Instant now) {
        return adminJdbc.updateUserStatus(tenantId, userId, status, now);
    }

    /**
     * 统计拥有指定角色的账号数。
     *
     * @param tenantId 目标租户
     * @param roleCode 角色码
     * @return 账号数
     */
    public long countUsersWithRole(TenantId tenantId, String roleCode) {
        return adminJdbc.countUsersWithRole(tenantId, roleCode);
    }
}
