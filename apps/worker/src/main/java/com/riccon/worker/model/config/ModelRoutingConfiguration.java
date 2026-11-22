package com.riccon.worker.model.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 模型路由/降级配置装配。
 *
 * <p>配置类必须位于 `..config..`（ArchUnit）；这里集中注册 B4 的三个配置绑定，避免把新键
 * 散落到 `config/WorkerConfig`（TASK-0105 写入范围不含该文件）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    ModelGatewaySwitchProperties.class,
    ModelRoutingProperties.class,
    ModelDeploymentProperties.class
})
public class ModelRoutingConfiguration {}
