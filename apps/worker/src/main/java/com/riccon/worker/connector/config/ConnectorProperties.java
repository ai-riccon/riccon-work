package com.riccon.worker.connector.config;

import com.riccon.platform.common.model.HostResolver;
import com.riccon.worker.connector.domain.ConnectorEndpointConfig;
import com.riccon.worker.connector.domain.ConnectorEndpointPolicy;
import com.riccon.worker.connector.domain.ConnectorEndpointRegistry;
import com.riccon.worker.run.config.OutboxLaneProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 连接器配置（{@code RICCON_CONNECTOR_*}）。
 *
 * <p>端点只来自部署允许列表；连接器启用时 heavy 通道看门狗必须 ≥ 同步总超时（默认 60 分钟），否则
 * Worker 启动失败，确保同步先按 {@code KB_CONNECTOR_TIMEOUT} 收尾（ADR-0023 第 6.5/7.3 节）。
 *
 * <p>默认 {@code riccon.connector.enabled=false}；关闭时调度器不启动，事件处理器直接跳过并记日志。
 * 日志只输出开关、端点数量与上限，不输出端点 URL、bucket 或对象 key。
 */
@Component
@ConfigurationProperties(prefix = "riccon.connector")
public class ConnectorProperties implements InitializingBean {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorProperties.class);

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /** 通道配置，用于启动校验 heavy 看门狗。 */
    private final OutboxLaneProperties laneProperties;

    /** 功能开关。 */
    private boolean enabled;

    /** 端点允许列表 JSON。 */
    private String endpoints = "";

    /** 部署固定同步周期（分钟）。 */
    private int syncIntervalMinutes = 60;

    /** 是否启动定时调度器；测试可关闭。 */
    private boolean schedulerEnabled = true;

    /** 调度器首次延迟。 */
    private Duration schedulerInitialDelay = Duration.ofSeconds(15);

    /** 调度器轮询间隔。 */
    private Duration schedulerFixedDelay = Duration.ofSeconds(60);

    /** 调度器首次延迟毫秒（{@code @Scheduled} 使用；测试可设大避免后台干扰）。 */
    private long schedulerInitialDelayMs = 15_000L;

    /** 调度器轮询间隔毫秒（{@code @Scheduled} 使用）。 */
    private long schedulerFixedDelayMs = 60_000L;

    /** 单对象大小上限。 */
    private long maxObjectBytes = 104_857_600L;

    /** 单次同步对象数上限。 */
    private int maxObjects = 5_000;

    /** 单次同步总字节上限。 */
    private long maxTotalBytes = 2L * 1024 * 1024 * 1024;

    /** 单对象下载超时。 */
    private Duration objectTimeout = Duration.ofSeconds(60);

    /** 单次同步总超时；必须小于 heavy 看门狗。 */
    private Duration syncTimeout = Duration.ofMinutes(60);

    /** S3 path-style。 */
    private boolean pathStyle = true;

    /** 默认区域。 */
    private String defaultRegion = "us-east-1";

    /** 认证/网络失败退避。 */
    private List<Duration> retryDelays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    /** 校验后的端点策略。 */
    private ConnectorEndpointPolicy policy;

    /** 校验后的端点允许列表。 */
    private ConnectorEndpointRegistry registry;

    /**
     * 创建配置绑定对象。
     *
     * @param objectMapper JSON 解析器
     * @param laneProperties 通道配置
     */
    public ConnectorProperties(ObjectMapper objectMapper, OutboxLaneProperties laneProperties) {
        this.objectMapper = objectMapper;
        this.laneProperties = laneProperties;
    }

    /** 绑定开关。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 绑定端点 JSON。 */
    public void setEndpoints(String endpoints) {
        this.endpoints = endpoints;
    }

    /** 绑定同步周期。 */
    public void setSyncIntervalMinutes(int syncIntervalMinutes) {
        this.syncIntervalMinutes = syncIntervalMinutes;
    }

    /** 绑定调度器开关。 */
    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    /** 绑定调度器首次延迟。 */
    public void setSchedulerInitialDelay(Duration schedulerInitialDelay) {
        this.schedulerInitialDelay = schedulerInitialDelay;
    }

    /** 绑定调度器轮询间隔。 */
    public void setSchedulerFixedDelay(Duration schedulerFixedDelay) {
        this.schedulerFixedDelay = schedulerFixedDelay;
    }

    /** 绑定调度器首次延迟毫秒。 */
    public void setSchedulerInitialDelayMs(long schedulerInitialDelayMs) {
        this.schedulerInitialDelayMs = schedulerInitialDelayMs;
    }

    /** 绑定调度器轮询间隔毫秒。 */
    public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
        this.schedulerFixedDelayMs = schedulerFixedDelayMs;
    }

    /** 绑定单对象大小上限。 */
    public void setMaxObjectBytes(long maxObjectBytes) {
        this.maxObjectBytes = maxObjectBytes;
    }

    /** 绑定对象数上限。 */
    public void setMaxObjects(int maxObjects) {
        this.maxObjects = maxObjects;
    }

    /** 绑定总字节上限。 */
    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    /** 绑定单对象超时。 */
    public void setObjectTimeout(Duration objectTimeout) {
        this.objectTimeout = objectTimeout;
    }

    /** 绑定同步总超时。 */
    public void setSyncTimeout(Duration syncTimeout) {
        this.syncTimeout = syncTimeout;
    }

    /** 绑定 path-style。 */
    public void setPathStyle(boolean pathStyle) {
        this.pathStyle = pathStyle;
    }

    /** 绑定默认区域。 */
    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /** 绑定重试退避。 */
    public void setRetryDelays(List<Duration> retryDelays) {
        this.retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
    }

    /**
     * 启动校验：解析允许列表并校验时间/大小/通道上限。
     */
    @Override
    public void afterPropertiesSet() {
        if (syncIntervalMinutes < 1 || syncIntervalMinutes > 10_080) {
            throw new IllegalStateException("riccon.connector.sync-interval-minutes must be between 1 and 10080");
        }
        if (schedulerInitialDelay == null || schedulerInitialDelay.isNegative()) {
            throw new IllegalStateException("riccon.connector.scheduler-initial-delay must not be negative");
        }
        if (schedulerFixedDelay == null || schedulerFixedDelay.isNegative() || schedulerFixedDelay.isZero()) {
            throw new IllegalStateException("riccon.connector.scheduler-fixed-delay must be positive");
        }
        if (schedulerInitialDelayMs < 0) {
            throw new IllegalStateException("riccon.connector.scheduler-initial-delay-ms must not be negative");
        }
        if (schedulerFixedDelayMs < 1) {
            throw new IllegalStateException("riccon.connector.scheduler-fixed-delay-ms must be positive");
        }
        if (maxObjectBytes < 1 || maxObjectBytes > 100L * 1024 * 1024) {
            throw new IllegalStateException("riccon.connector.max-object-bytes must be between 1 and 104857600");
        }
        if (maxObjects < 1 || maxObjects > 5_000) {
            throw new IllegalStateException("riccon.connector.max-objects must be between 1 and 5000");
        }
        if (maxTotalBytes < 1 || maxTotalBytes > 2L * 1024 * 1024 * 1024) {
            throw new IllegalStateException("riccon.connector.max-total-bytes must be between 1 and 2147483648");
        }
        if (objectTimeout == null || objectTimeout.isNegative() || objectTimeout.isZero()) {
            throw new IllegalStateException("riccon.connector.object-timeout must be positive");
        }
        if (syncTimeout == null || syncTimeout.isNegative() || syncTimeout.isZero()) {
            throw new IllegalStateException("riccon.connector.sync-timeout must be positive");
        }
        if (defaultRegion == null || defaultRegion.isBlank()) {
            throw new IllegalStateException("riccon.connector.default-region must not be blank");
        }
        if (retryDelays.isEmpty()) {
            throw new IllegalStateException("riccon.connector.retry-delays must not be empty");
        }
        if (enabled) {
            Duration heavy = laneProperties.getHeavyMaxProcessing();
            if (heavy == null || heavy.compareTo(syncTimeout) < 0) {
                throw new IllegalStateException(
                        "riccon.worker.outbox.heavy-max-processing must be >= connector sync timeout when"
                                + " RICCON_CONNECTOR_ENABLED=true");
            }
        }
        this.policy = new ConnectorEndpointPolicy(HostResolver.system());
        try {
            this.registry = policy.load(parseEndpoints());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid connector endpoint configuration: " + exception.getMessage());
        }
        LOG.info(
                "worker connector configuration loaded; enabled={} endpoints={} maxObjects={} maxObjectBytes={}"
                        + " syncTimeoutMinutes={} schedulerEnabled={}",
                enabled,
                registry.endpoints().size(),
                maxObjects,
                maxObjectBytes,
                syncTimeout.toMinutes(),
                schedulerEnabled);
    }

    /**
     * 解析端点 JSON。
     *
     * @return 原始条目
     */
    private List<ConnectorEndpointConfig> parseEndpoints() {
        String raw = endpoints == null ? "" : endpoints.strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        try {
            List<ConnectorEndpointConfig> parsed =
                    objectMapper.readValue(raw, new TypeReference<List<ConnectorEndpointConfig>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("riccon.connector.endpoints must be a valid JSON array");
        }
    }

    /** 返回功能开关。 */
    public boolean enabled() {
        return enabled;
    }

    /** 返回同步周期。 */
    public int syncIntervalMinutes() {
        return syncIntervalMinutes;
    }

    /** 返回调度器开关。 */
    public boolean schedulerEnabled() {
        return schedulerEnabled;
    }

    /** 返回调度器首次延迟。 */
    public Duration schedulerInitialDelay() {
        return schedulerInitialDelay;
    }

    /** 返回调度器轮询间隔。 */
    public Duration schedulerFixedDelay() {
        return schedulerFixedDelay;
    }

    /** 返回调度器首次延迟毫秒。 */
    public long schedulerInitialDelayMs() {
        return schedulerInitialDelayMs;
    }

    /** 返回调度器轮询间隔毫秒。 */
    public long schedulerFixedDelayMs() {
        return schedulerFixedDelayMs;
    }

    /** 返回单对象上限。 */
    public long maxObjectBytes() {
        return maxObjectBytes;
    }

    /** 返回对象数上限。 */
    public int maxObjects() {
        return maxObjects;
    }

    /** 返回总字节上限。 */
    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    /** 返回单对象超时。 */
    public Duration objectTimeout() {
        return objectTimeout;
    }

    /** 返回同步总超时。 */
    public Duration syncTimeout() {
        return syncTimeout;
    }

    /** 返回 path-style。 */
    public boolean pathStyle() {
        return pathStyle;
    }

    /** 返回默认区域。 */
    public String defaultRegion() {
        return defaultRegion;
    }

    /** 返回重试退避。 */
    public List<Duration> retryDelays() {
        return retryDelays;
    }

    /** 返回端点策略。 */
    public ConnectorEndpointPolicy policy() {
        return policy;
    }

    /** 返回端点允许列表。 */
    public ConnectorEndpointRegistry registry() {
        return registry;
    }
}
