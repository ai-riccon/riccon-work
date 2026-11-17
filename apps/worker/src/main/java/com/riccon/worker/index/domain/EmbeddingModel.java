package com.riccon.worker.index.domain;

/**
 * 可插拔的文本向量化接口。
 *
 * <p>M02 只要求索引可重建与租户隔离，不在线调用真实模型；后续任务接入模型网关时实现本接口，
 * 并保持向量维度与 OpenSearch 索引映射一致。实现必须是纯函数：同一文本返回同一向量，
 * 否则索引重建结果不稳定。
 */
public interface EmbeddingModel {

    /**
     * 返回向量维度。
     *
     * @return 向量维度
     */
    int dimensions();

    /**
     * 将文本转换为向量。
     *
     * @param text 待向量化文本；调用方保证非空
     * @return 长度为 {@link #dimensions()} 的向量
     */
    float[] embed(String text);
}
