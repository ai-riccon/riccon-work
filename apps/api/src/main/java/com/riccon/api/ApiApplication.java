package com.riccon.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Riccon 同步 HTTP 服务入口。
 *
 * <p>该部署物只承担同步请求、认证授权与会话；Agent 运行、模型调用和 MCP 代理属于 {@code apps/worker}，
 * 两个部署物通过内部契约通信，禁止在本进程内引入 agentscope。
 *
 * <p>M01 尚未接入认证，排除 Spring Boot 默认的内存用户自动配置，避免启动时生成无意义的默认密码，
 * 也让“默认拒绝”成为唯一的安全行为。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ApiApplication {

    /**
     * 启动应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
