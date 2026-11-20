package com.riccon.worker.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 侧部署级模型兼容开关。
 *
 * <p>{@code ModelEndpointProperties} 负责解析允许列表并启动校验；本类只暴露 B4 路由解析需要的
 * “是否显式接受仅 DB 目录端点”。由于 B4 的写入范围只覆盖 `worker/model/**`，不能直接给
 * `config/ModelEndpointProperties` 增加 getter，因此这里以独立绑定读取同一部署键
 * （`RICCON_MODEL_ALLOW_LEGACY_ENDPOINTS`，默认 false）。
 *
 * @param allowLegacyEndpoints 是否显式接受 DB 目录即端点来源（书面风险接受，生产默认关闭）
 */
@ConfigurationProperties(prefix = "riccon.model")
public record ModelDeploymentProperties(boolean allowLegacyEndpoints) {}
