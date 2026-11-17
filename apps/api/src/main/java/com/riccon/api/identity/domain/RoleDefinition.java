package com.riccon.api.identity.domain;

import java.util.Set;

/**
 * 系统角色定义。
 *
 * @param code 角色码
 * @param name 展示名
 * @param description 说明
 * @param permissions 角色拥有的权限码集合
 */
public record RoleDefinition(String code, String name, String description, Set<String> permissions) {

    /** 复制权限集合，避免外部修改定义。 */
    public RoleDefinition {
        permissions = Set.copyOf(permissions);
    }
}
