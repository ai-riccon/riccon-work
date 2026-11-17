package com.riccon.api.identity.domain;

import java.util.List;
import java.util.Objects;

/**
 * 当前请求的完整授权快照。
 *
 * <p>每次请求从数据库重新计算，不写入会话；角色撤销或账号禁用会在下一请求生效（ADR-0013）。
 *
 * @param user 账号
 * @param tenant 租户
 * @param roles 角色码，按字典序排列
 * @param permissions 权限码，按字典序排列
 */
public record CurrentUser(UserAccount user, TenantRecord tenant, List<String> roles, List<String> permissions) {

    /** 保证快照完整并复制集合，避免调用方修改。 */
    public CurrentUser {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }

    /**
     * 判断是否拥有指定权限。
     *
     * @param permissionCode 权限码
     * @return 拥有时返回 {@code true}
     */
    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }
}
