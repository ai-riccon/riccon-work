package com.riccon.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首次启动引导管理员配置。
 *
 * <p>对应环境变量 {@code RICCON_BOOTSTRAP_ADMIN_USERNAME} 与 {@code RICCON_BOOTSTRAP_ADMIN_PASSWORD}。
 * 迁移不创建默认账号；两个变量都未配置时不创建管理员。密码不得写入仓库、日志或证据。
 *
 * @param username 引导管理员登录名
 * @param password 引导管理员初始密码，至少 12 位
 */
@ConfigurationProperties(prefix = "riccon.bootstrap.admin")
public record RicconBootstrapProperties(String username, String password) {

    /**
     * 判断是否配置了引导管理员。
     *
     * @return 用户名或密码任一非空时返回 {@code true}
     */
    public boolean configured() {
        return (username != null && !username.isBlank()) || (password != null && !password.isBlank());
    }
}
