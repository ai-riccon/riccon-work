package com.riccon.worker.index.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** 确定性 Embedding 占位实现测试。 */
class HashingEmbeddingModelTest {

    /** 同一文本必须得到完全相同的向量，保证索引重建稳定。 */
    @Test
    void embed_sameText_isDeterministic() {
        HashingEmbeddingModel model = new HashingEmbeddingModel(32);
        assertThat(model.embed("quarterly revenue")).containsExactly(model.embed("quarterly revenue"));
    }

    /** 不同文本应得到不同向量。 */
    @Test
    void embed_differentText_differs() {
        HashingEmbeddingModel model = new HashingEmbeddingModel(32);
        assertThat(model.embed("revenue")).isNotEqualTo(model.embed("cost"));
    }

    /** 非空向量应做 L2 归一化，便于后续余弦检索。 */
    @Test
    void embed_nonBlank_isNormalized() {
        HashingEmbeddingModel model = new HashingEmbeddingModel(16);
        float[] vector = model.embed("market analysis");
        double norm = 0.0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-5));
    }

    /** 空文本返回零向量，调用方无需特殊分支。 */
    @Test
    void embed_blank_returnsZeroVector() {
        HashingEmbeddingModel model = new HashingEmbeddingModel(8);
        assertThat(model.embed("  ")).containsExactly(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        assertThat(model.embed(null)).hasSize(8);
    }

    /** 维度过小属于配置错误，必须启动期失败。 */
    @Test
    void constructor_invalidDimensions_throws() {
        assertThatThrownBy(() -> new HashingEmbeddingModel(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 维度访问器返回配置值。 */
    @Test
    void dimensions_returnsConfiguredValue() {
        assertThat(new HashingEmbeddingModel(24).dimensions()).isEqualTo(24);
    }
}
