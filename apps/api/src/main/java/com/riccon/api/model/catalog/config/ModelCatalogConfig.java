package com.riccon.api.model.catalog.config;

import com.riccon.api.model.catalog.ModelEndpointProperties;
import com.riccon.platform.common.model.ManagedEndpointPolicy;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型目录与端点策略装配。
 *
 * <p>把启动时校验完成的允许列表暴露为只读 Bean，供员工目录、平台目录与 provider 写接口共享同一份策略；
 * 不在请求路径上重新解析部署配置。{@link ModelEndpointProperties} 自身负责启动失败语义（ADR-0021 §2.1）。
 */
@Configuration(proxyBeanMethods = false)
public class ModelCatalogConfig {

    /**
     * 暴露不可变端点允许列表。
     *
     * @param properties 已校验的端点配置
     * @return 允许列表
     */
    @Bean
    public ManagedEndpointRegistry managedEndpointRegistry(ModelEndpointProperties properties) {
        return properties.registry();
    }

    /**
     * 暴露端点 URL 校验策略。
     *
     * @param properties 已校验的端点配置
     * @return 策略
     */
    @Bean
    public ManagedEndpointPolicy managedEndpointPolicy(ModelEndpointProperties properties) {
        return properties.policy();
    }
}
