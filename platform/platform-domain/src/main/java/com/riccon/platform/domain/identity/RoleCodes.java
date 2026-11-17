package com.riccon.platform.domain.identity;

/**
 * 内置角色码。
 *
 * <p>角色码在租户内唯一，平台管理员角色只存在于保留租户 {@code platform}。M02 不提供自定义角色；
 * M03 引入部门与数据范围时新增角色码，不改动已有语义。
 */
public interface RoleCodes {

    /** 平台管理员：只能管理租户、账号元数据、模型/MCP 目录，不读取租户业务内容。 */
    String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** 租户管理员：管理本租户账号、角色与知识库，不跨租户。 */
    String TENANT_ADMIN = "TENANT_ADMIN";

    /** 普通员工：使用工作空间、文件、会话、报告与审批。 */
    String MEMBER = "MEMBER";
}
