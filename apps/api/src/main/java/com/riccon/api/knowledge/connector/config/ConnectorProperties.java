package com.riccon.api.knowledge.connector.config;

import com.riccon.api.knowledge.connector.domain.ConnectorEndpointConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorEndpointPolicy;
import com.riccon.api.knowledge.connector.domain.ConnectorEndpointRegistry;
import com.riccon.platform.common.model.HostResolver;
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
 * API 连接器配置（{@code RICCON_CONNECTOR_*}）。
 *
 * <p>端点允许列表只来自部署环境 {@code RICCON_CONNECTOR_ENDPOINTS}（JSON 数组）；连接器只保存
 * {@code endpointId}，不能提交任意 URL。配置非法时启动失败，不静默降级为零出网以外的行为。
 *
 * <p>默认 {@code riccon.connector.enabled=false}，与 RUNBOOK「M05b-1」一致；关闭时 test/sync 返回 503，
 * 但已有连接器列表与条目仍可读取，便于回滚排查。
 *
 * <p>端点 URL、主机与解析结果不写入启动日志；只输出端点数量与开关状态（ADR-0023 第 6.5 节）。
 */
@Component
@ConfigurationProperties(prefix = "riccon.connector")
public class ConnectorProperties implements InitializingBean {

    /** 启动日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorProperties.class);

    /** JSON 解析器；只用于部署配置。 */
    private final ObjectMapper objectMapper;

    /** 功能开关。 */
    private boolean enabled;

    /** 部署允许列表 JSON。 */
    private String endpoints = "";

    /** 部署固定同步周期（分钟）；不提供 per-connector cron。 */
    private int syncIntervalMinutes = 60;

    /** S3 path-style；S3 兼容实现一般需要 true。 */
    private boolean pathStyle = true;

    /** 默认签名区域；连接器 config.region 为空时使用。 */
    private String defaultRegion = "us-east-1";

    /** 连接测试的 HTTP 调用超时。 */
    private Duration testTimeout = Duration.ofSeconds(10);

    /** 校验后的端点允许列表。 */
    private ConnectorEndpointRegistry registry;

    /** 校验后的 SSRF 策略。 */
    private ConnectorEndpointPolicy policy;

    /**
     * 创建配置绑定对象。
     *
     * @param objectMapper JSON 解析器
     */
    public ConnectorProperties(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 绑定功能开关。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 绑定端点允许列表 JSON。 */
    public void setEndpoints(String endpoints) {
        this.endpoints = endpoints;
    }

    /** 绑定同步周期。 */
    public void setSyncIntervalMinutes(int syncIntervalMinutes) {
        this.syncIntervalMinutes = syncIntervalMinutes;
    }

    /** 绑定 path-style。 */
    public void setPathStyle(boolean pathStyle) {
        this.pathStyle = pathStyle;
    }

    /** 绑定默认区域。 */
    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /** 绑定连接测试超时。 */
    public void setTestTimeout(Duration testTimeout) {
        this.testTimeout = testTimeout;
    }

    /**
     * 启动时解析并校验允许列表；非法配置直接让上下文启动失败（fail-closed）。
     */
    @Override
    public void afterPropertiesSet() {
        if (syncIntervalMinutes < 1 || syncIntervalMinutes > 10_080) {
            throw new IllegalStateException("riccon.connector.sync-interval-minutes must be between 1 and 10080");
        }
        if (defaultRegion == null || defaultRegion.isBlank()) {
            throw new IllegalStateException("riccon.connector.default-region must not be blank");
        }
        if (testTimeout == null || testTimeout.isNegative() || testTimeout.isZero()) {
            throw new IllegalStateException("riccon.connector.test-timeout must be positive");
        }
        this.policy = new ConnectorEndpointPolicy(HostResolver.system());
        List<ConnectorEndpointConfig> configs = parseEndpoints();
        try {
            this.registry = policy.load(configs);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid connector endpoint configuration: " + exception.getMessage());
        }
        LOG.info(
                "connector endpoint allowlist loaded; enabled={} endpoints={} syncIntervalMinutes={}",
                enabled,
                registry.endpoints().size(),
                syncIntervalMinutes);
    }

    /**
     * 解析端点 JSON；空值返回空列表。
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
            // Jackson 详细错误包含输入片段；只保留配置键名，避免把部署地址写入启动日志。
            throw new IllegalStateException("riccon.connector.endpoints must be a valid JSON array");
        }
    }

    /** 返回功能是否启用。 */
    public boolean enabled() {
        return enabled;
    }

    /** 返回部署同步周期（分钟）。 */
    public int syncIntervalMinutes() {
        return syncIntervalMinutes;
    }

    /** 返回 path-style。 */
    public boolean pathStyle() {
        return pathStyle;
    }

    /** 返回默认区域。 */
    public String defaultRegion() {
        return defaultRegion;
    }

    /** 返回连接测试超时。 */
    public Duration testTimeout() {
        return testTimeout;
    }

    /** 返回端点允许列表。 */
    public ConnectorEndpointRegistry registry() {
        return registry;
    }

    /** 返回 SSRF 策略。 */
    public ConnectorEndpointPolicy policy() {
        return policy;
    }
}
