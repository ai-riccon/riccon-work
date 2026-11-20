package com.riccon.worker.model.service;

import com.riccon.worker.config.ModelEndpointProperties;
import com.riccon.worker.model.config.ModelDeploymentProperties;
import com.riccon.worker.model.domain.ProviderDescriptor;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 调用前受管端点复核（双重校验的第二道）。
 *
 * <p>数据库 `model_provider` 行只有在部署允许列表内、标签与部署模式兼容、baseUrl 规范化后
 * 一致时才可路由（ADR-0021 §2.1/§4.1）。`RICCON_MODEL_ALLOW_LEGACY_ENDPOINTS=true` 是部署方
 * 显式书面风险接受（仅 DB 目录兼容），此时跳过允许列表匹配但仍执行 URL/协议校验；生产默认
 * false，测试上下文用它保持 M02 动态端口。
 */
@Component
public class ModelEndpointGuard {

    /** 日志；只记录 provider code 与原因码，不记录 URL。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelEndpointGuard.class);

    /** 端点配置。 */
    private final ModelEndpointProperties endpointProperties;

    /** 部署兼容开关。 */
    private final ModelDeploymentProperties deploymentProperties;

    /** 模型解析（复用 URL 校验）。 */
    private final ModelRegistry modelRegistry;

    /**
     * 创建端点守卫。
     *
     * @param endpointProperties 端点配置
     * @param deploymentProperties 部署兼容开关
     * @param modelRegistry 模型解析
     */
    public ModelEndpointGuard(
            ModelEndpointProperties endpointProperties,
            ModelDeploymentProperties deploymentProperties,
            ModelRegistry modelRegistry) {
        this.endpointProperties = endpointProperties;
        this.deploymentProperties = deploymentProperties;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 判断 provider 是否可被路由选择。
     *
     * @param provider 供应商目录快照
     * @return 可路由返回 true
     */
    public boolean isRoutable(ProviderDescriptor provider) {
        if (provider == null || !"OPENAI_COMPATIBLE".equals(provider.protocol())) {
            return false;
        }
        URI baseUrl = provider.baseUrl();
        try {
            endpointProperties.policy().validateUrl(baseUrl.toString(), "baseUrl");
            modelRegistry.validateBaseUrl(baseUrl);
        } catch (RuntimeException exception) {
            LOG.warn("model.endpoint.rejected providerCode={} reason=URL_INVALID", provider.code());
            return false;
        }
        if (deploymentProperties.allowLegacyEndpoints()) {
            return true;
        }
        boolean allowed = endpointProperties.registry().isAllowed(provider.code(), baseUrl.toString());
        if (!allowed) {
            LOG.warn("model.endpoint.rejected providerCode={} reason=NOT_ALLOWED", provider.code());
        }
        return allowed;
    }
}
