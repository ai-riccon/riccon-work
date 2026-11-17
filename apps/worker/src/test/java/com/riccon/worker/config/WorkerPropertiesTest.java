package com.riccon.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 配置属性边界校验测试。 */
class WorkerPropertiesTest {

    /** 合法 Outbox 配置。 */
    @Test
    void outbox_validDefaults_accepts() {
        OutboxProperties properties = outbox(
                Duration.ofMillis(500),
                Duration.ofSeconds(5),
                10,
                Duration.ofSeconds(90),
                3,
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(8)),
                Duration.ofMillis(200));

        assertThat(properties.batchSize()).isEqualTo(10);
        assertThat(properties.retryDelays()).hasSize(2);
        assertThat(properties.leaseOwner()).isEmpty();
    }

    /** 轮询间隔不能为空、负数或零。 */
    @Test
    void outbox_invalidPollInterval_throws() {
        assertThatThrownBy(() -> outbox(
                        null,
                        Duration.ofSeconds(1),
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-interval");
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(-1),
                        Duration.ofSeconds(1),
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 首次延迟不能为空或负数，但允许零。 */
    @Test
    void outbox_invalidInitialDelay_throws() {
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        null,
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(-1),
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 批量大小必须在 1–50。 */
    @Test
    void outbox_invalidBatchSize_throws() {
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        0,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        51,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 租约时长必须为正。 */
    @Test
    void outbox_invalidLeaseDuration_throws() {
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        10,
                        null,
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        10,
                        Duration.ofSeconds(-1),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        10,
                        Duration.ZERO,
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 最大尝试次数至少为 1。 */
    @Test
    void outbox_invalidMaxAttempts_throws() {
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        10,
                        Duration.ofSeconds(90),
                        0,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 退避列表不能为空。 */
    @Test
    void outbox_invalidRetryDelays_throws() {
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1), Duration.ZERO, 10, Duration.ofSeconds(90), 3, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1), Duration.ZERO, 10, Duration.ofSeconds(90), 3, List.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 抖动不能为负。 */
    @Test
    void outbox_invalidJitter_throws() {
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox(
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        10,
                        Duration.ofSeconds(90),
                        3,
                        List.of(Duration.ofSeconds(1)),
                        Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** OpenSearch URL、超时与索引名必须合法。 */
    @Test
    void openSearch_invalidValues_throws() {
        assertThatThrownBy(() -> openSearch(null, Duration.ofSeconds(1), Duration.ofSeconds(1), "riccon-kb", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openSearch(" ", Duration.ofSeconds(1), Duration.ofSeconds(1), "riccon-kb", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openSearch("http://localhost:9200", null, Duration.ofSeconds(1), "riccon-kb", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        openSearch("http://localhost:9200", Duration.ZERO, Duration.ofSeconds(1), "riccon-kb", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openSearch(
                        "http://localhost:9200", Duration.ofSeconds(1), Duration.ofSeconds(-1), "riccon-kb", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        openSearch("http://localhost:9200", Duration.ofSeconds(1), Duration.ofSeconds(1), null, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        openSearch("http://localhost:9200", Duration.ofSeconds(1), Duration.ofSeconds(1), "Bad", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openSearch(
                        "http://localhost:9200", Duration.ofSeconds(1), Duration.ofSeconds(1), "riccon-kb", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openSearch(
                        "http://localhost:9200", Duration.ofSeconds(1), Duration.ofSeconds(1), "riccon-kb", "V1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 解析上限与切分参数必须自洽。 */
    @Test
    void parse_invalidValues_throws() {
        assertThatThrownBy(() -> new ParseProperties(0, 800, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseProperties(1, 99, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseProperties(1, 800, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseProperties(1, 800, 800)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ParseProperties(1, 800, 80).chunkSize()).isEqualTo(800);
    }

    /** 向量维度必须在 8–4096。 */
    @Test
    void embedding_invalidDimensions_throws() {
        assertThatThrownBy(() -> new EmbeddingProperties(7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProperties(4097)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EmbeddingProperties(64).dimensions()).isEqualTo(64);
    }

    /** 存储端点与桶名不能为空。 */
    @Test
    void storage_invalidValues_throws() {
        assertThatThrownBy(() -> new StorageProperties(null, "us-east-1", "", "", "b", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties(" ", "us-east-1", "", "", "b", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties("http://localhost:9000", "us-east-1", "", "", null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties("http://localhost:9000", "us-east-1", "", "", " ", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StorageProperties("http://localhost:9000", "us-east-1", "", "", "b", true).bucket())
                .isEqualTo("b");
    }

    /**
     * 构造 Outbox 配置。
     *
     * @param pollInterval 轮询间隔
     * @param initialDelay 首次延迟
     * @param batchSize 批量
     * @param leaseDuration 租约
     * @param maxAttempts 最大尝试
     * @param retryDelays 退避列表
     * @param jitter 抖动
     * @return 配置
     */
    private static OutboxProperties outbox(
            Duration pollInterval,
            Duration initialDelay,
            int batchSize,
            Duration leaseDuration,
            int maxAttempts,
            List<Duration> retryDelays,
            Duration jitter) {
        return new OutboxProperties(
                pollInterval, initialDelay, batchSize, leaseDuration, maxAttempts, retryDelays, jitter, "");
    }

    /**
     * 构造 OpenSearch 配置。
     *
     * @param url 端点
     * @param connectTimeout 连接超时
     * @param requestTimeout 请求超时
     * @param prefix 索引前缀
     * @param version 索引版本
     * @return 配置
     */
    private static OpenSearchProperties openSearch(
            String url, Duration connectTimeout, Duration requestTimeout, String prefix, String version) {
        return new OpenSearchProperties(url, "", "", connectTimeout, requestTimeout, prefix, version);
    }
}
