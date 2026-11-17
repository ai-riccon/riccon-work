package com.riccon.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Riccon 异步 Agent 运行与 MCP 代理入口。
 *
 * <p>唯一允许依赖 {@code io.agentscope} 的部署物。M01 只提供可启动骨架与健康检查；Agent 运行、模型路由、
 * MCP 代理与 HITL 编排从 M02 起通过内部 SPI 接入，禁止把 Agent 逻辑下沉到 {@code apps/api} 或 {@code platform}。
 *
 * <p>M01 尚未接入服务间认证，排除默认内存用户自动配置，避免生成无意义的默认密码。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WorkerApplication {

    /**
     * 启动应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
