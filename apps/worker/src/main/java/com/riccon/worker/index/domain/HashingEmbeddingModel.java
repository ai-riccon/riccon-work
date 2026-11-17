package com.riccon.worker.index.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 确定性哈希向量实现（M02 占位）。
 *
 * <p>真实模型接入前，用 SHA-256 按维度派生向量，保证同一文本在任何 JVM/实例上得到相同结果，
 * 用于验证切分、索引与重建流程。该向量没有语义质量，不得用于生产检索效果评估。
 */
public final class HashingEmbeddingModel implements EmbeddingModel {

    /** 每个维度派生 4 字节，使用 SHA-256 摘要的前 4 字节。 */
    private static final int BYTES_PER_DIMENSION = Integer.BYTES;

    /** 向量维度。 */
    private final int dimensions;

    /**
     * 创建确定性哈希向量模型。
     *
     * @param dimensions 向量维度
     */
    public HashingEmbeddingModel(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    /** 返回配置的向量维度。 */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * 对文本做归一化后按维度派生向量。
     *
     * @param text 待向量化文本
     * @return L2 归一化向量；空文本返回零向量
     */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimensions];
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        float[] vector = new float[dimensions];
        double squaredNorm = 0.0;
        for (int i = 0; i < dimensions; i++) {
            byte[] digest = sha256(normalized + "#" + i);
            int raw = ByteBuffer.wrap(digest, 0, BYTES_PER_DIMENSION).getInt();
            // 映射到 [-1, 1)，避免所有分量为正导致余弦相似度失真。
            float value = raw / (float) Integer.MAX_VALUE;
            vector[i] = value;
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0.0) {
            return vector;
        }
        double norm = Math.sqrt(squaredNorm);
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    /**
     * 计算 SHA-256 摘要。
     *
     * @param value 输入字符串
     * @return 32 字节摘要
     */
    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必须提供 SHA-256；缺失说明运行时被破坏，直接失败而不是降级。
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
