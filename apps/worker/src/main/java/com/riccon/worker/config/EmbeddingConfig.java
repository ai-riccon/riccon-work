package com.riccon.worker.config;

import com.riccon.worker.index.domain.EmbeddingModel;
import com.riccon.worker.index.domain.HashingEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 实现配置。
 *
 * <p>M02 默认使用确定性占位实现；后续真实模型接入时提供同类型 Bean 即可覆盖，无需修改索引服务。
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

    /**
     * 创建默认 Embedding 模型。
     *
     * @param properties 向量维度配置
     * @return 确定性哈希向量模型
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    EmbeddingModel embeddingModel(EmbeddingProperties properties) {
        return new HashingEmbeddingModel(properties.dimensions());
    }
}
