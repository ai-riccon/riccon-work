package com.riccon.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding 配置。
 *
 * <p>M02 只提供可插拔接口与确定性占位实现；真实模型接入时替换 {@code EmbeddingModel}
 * Bean，并同步索引维度与重建流程。
 *
 * @param dimensions 向量维度，必须与 OpenSearch knn_vector 映射一致
 */
@ConfigurationProperties(prefix = "riccon.worker.embedding")
public record EmbeddingProperties(@DefaultValue("64") int dimensions) {

    /** 维度必须为正，且不超过 M02 预算；避免映射巨大向量导致索引不可用。 */
    public EmbeddingProperties {
        if (dimensions < 8 || dimensions > 4096) {
            throw new IllegalArgumentException("riccon.worker.embedding.dimensions must be between 8 and 4096");
        }
    }
}
