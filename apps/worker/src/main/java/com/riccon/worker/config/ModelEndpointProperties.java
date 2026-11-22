package com.riccon.worker.config;

import com.riccon.platform.common.model.HostResolver;
import com.riccon.platform.common.model.ManagedEndpointPolicy;
import com.riccon.platform.common.model.ManagedEndpointPolicyException;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import com.riccon.platform.common.model.ManagedModelEndpointConfig;
import com.riccon.platform.common.model.ModelDeploymentMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 侧受管模型端点配置。
 *
 * <p>与 API 使用同一组部署键和同一个 {@link ManagedEndpointPolicy} 语义，保证启动校验、标签与 URL 规则一致
 * （ADR-0021 §2.1/§2.2）。Worker 在 B4 路由实现中需要在每次调用前再次按 code + baseUrl 复核允许列表，
 * 因此这里直接暴露不可变 {@link ManagedEndpointRegistry}，不做跨请求缓存。
 *
 * <p>启动失败条件：{@code PRODUCTION} 且未配置端点（除非显式允许遗留目录）、{@code PRODUCTION} 开启
 * {@code allow-http}、端点 JSON 非法、code/标签/URL 重复或命中元数据/链路本地/生产回环地址。
 */
@Component
@ConfigurationProperties(prefix = "riccon.model")
public class ModelEndpointProperties implements InitializingBean {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelEndpointProperties.class);

    /** JSON 反序列化器；只用于部署配置。 */
    private final ObjectMapper objectMapper;

    /** 部署模式原始值。 */
    private String deploymentMode = "PRODUCTION";

    /** 端点 JSON 数组原始值。 */
    private String endpoints = "";

    /** 是否允许“仅 DB 目录”的遗留兼容。 */
    private boolean allowLegacyEndpoints;

    /** 是否允许明文 HTTP；仅 DEMO 可开启。 */
    private boolean allowHttp;

    /** 校验后的策略。 */
    private ManagedEndpointPolicy policy;

    /** 校验后的允许列表。 */
    private ManagedEndpointRegistry registry;

    /**
     * 创建配置绑定对象。
     *
     * @param objectMapper JSON 反序列化器
     */
    public ModelEndpointProperties(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 绑定部署模式。
     *
     * @param deploymentMode DEMO 或 PRODUCTION
     */
    public void setDeploymentMode(String deploymentMode) {
        this.deploymentMode = deploymentMode;
    }

    /**
     * 绑定端点 JSON 数组。
     *
     * @param endpoints JSON 数组；空表示未配置
     */
    public void setEndpoints(String endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * 绑定遗留兼容开关。
     *
     * @param allowLegacyEndpoints 是否允许无端点配置
     */
    public void setAllowLegacyEndpoints(boolean allowLegacyEndpoints) {
        this.allowLegacyEndpoints = allowLegacyEndpoints;
    }

    /**
     * 绑定 HTTP 开关。
     *
     * @param allowHttp 是否允许 http 端点
     */
    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    /**
     * 启动时解析并校验端点配置；失败即让 Worker 启动失败。
     *
     * @throws IllegalStateException 配置非法或生产方式下缺少端点
     */
    @Override
    public void afterPropertiesSet() {
        ModelDeploymentMode mode;
        try {
            mode = ModelDeploymentMode.parse(deploymentMode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        List<ManagedModelEndpointConfig> configs = parseEndpoints();
        if (configs.isEmpty()) {
            if (mode == ModelDeploymentMode.PRODUCTION && !allowLegacyEndpoints) {
                throw new IllegalStateException("RICCON_MODEL_ENDPOINTS must be configured in PRODUCTION mode "
                        + "(or explicitly accept RICCON_MODEL_ALLOW_LEGACY_ENDPOINTS)");
            }
            LOG.warn("model endpoint allowlist is empty; mode={} allowLegacyEndpoints={}", mode, allowLegacyEndpoints);
        }
        try {
            this.policy = new ManagedEndpointPolicy(mode, allowHttp, HostResolver.system());
            this.registry = policy.load(configs);
        } catch (ManagedEndpointPolicyException exception) {
            // 统一暴露为启动失败；只输出原因码，不输出 URL 或解析结果。
            throw new IllegalStateException("invalid model endpoint configuration: " + exception.reason(), exception);
        }
        LOG.info(
                "model endpoint allowlist loaded; mode={} endpoints={}",
                mode,
                registry.endpoints().size());
    }

    /**
     * 返回校验后的端点策略。
     *
     * @return 策略
     */
    public ManagedEndpointPolicy policy() {
        return policy;
    }

    /**
     * 返回校验后的允许列表。
     *
     * @return 允许列表
     */
    public ManagedEndpointRegistry registry() {
        return registry;
    }

    /**
     * 解析端点 JSON；空值返回空列表。
     *
     * @return 原始端点条目
     */
    private List<ManagedModelEndpointConfig> parseEndpoints() {
        String raw = endpoints == null ? "" : endpoints.strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        try {
            List<ManagedModelEndpointConfig> parsed =
                    objectMapper.readValue(raw, new TypeReference<List<ManagedModelEndpointConfig>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException exception) {
            // 不把 Jackson 的输入片段写进启动日志，避免部署地址进入日志。
            throw new IllegalStateException("riccon.model.endpoints must be a valid JSON array", exception);
        }
    }
}
