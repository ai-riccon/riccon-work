package com.riccon.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 知识库文档级 ACL 的部署开关与上限（ADR-0023 第 5.8 节）。
 *
 * <p>属性前缀 {@code riccon.kb.acl}，对应环境变量 {@code RICCON_KB_ACL_ENFORCEMENT_ENABLED}
 * 与 {@code RICCON_KB_ACL_MAX_USERS}。开关只来自部署环境，不提供租户级自助修改；关闭时谓词
 * 退化为 {@code tenant_id}（等价 M03），启动必须输出固定 WARN 与指标，M05 gate 必须在 true 下取证。
 *
 * @param enforcementEnabled 是否启用文档级 ACL；首次部署默认 false
 * @param maxUsers 单文档 USERS 授权上限；超出由服务层返回 400，不静默截断
 */
@ConfigurationProperties(prefix = "riccon.kb.acl")
public record KnowledgeAclProperties(
        @DefaultValue("false") boolean enforcementEnabled,
        @DefaultValue("200") int maxUsers) {

    /** 最小授权上限：至少允许创建人之外的一名成员。 */
    private static final int MIN_MAX_USERS = 1;

    /** 最大授权上限：避免异常配置把单文档授权扩大到不可控规模。 */
    private static final int MAX_MAX_USERS = 1000;

    /**
     * 校验配置边界。
     *
     * @throws IllegalArgumentException 上限越界
     */
    public KnowledgeAclProperties {
        if (maxUsers < MIN_MAX_USERS || maxUsers > MAX_MAX_USERS) {
            throw new IllegalArgumentException(
                    "riccon.kb.acl.max-users must be between " + MIN_MAX_USERS + " and " + MAX_MAX_USERS);
        }
    }
}
