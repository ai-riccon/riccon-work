package com.riccon.api.model.catalog;

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
 * API 侧受管模型端点配置（{@code RICCON_MODEL_ENDPOINTS} / {@code RICCON_MODEL_DEPLOYMENT_MODE}）。
 *
 * <p>端点只来自部署配置，是非密钥 JSON 数组；API 用它计算目录中的 {@code allowed} 与 {@code environmentLabel}，
 * 并在管理接口尝试写 baseUrl 时复用同一 {@link ManagedEndpointPolicy}。启动校验失败即启动失败：
 *
 * <ul>
 *   <li>{@code PRODUCTION} 下开启 {@code allow-http} 直接失败；
 *   <li>{@code PRODUCTION} 未配置端点且未显式 {@code allow-legacy-endpoints} 直接失败；
 *   <li>端点 code/标签/URL/重复项非法直接失败。
 * </ul>
 *
 * <p>端点 URL、主机与解析结果不写入日志；日志只输出数量与模式（ADR-0021 §2.1/§2.2）。
 */
@Component
@ConfigurationProperties(prefix = "riccon.model")
public class ModelEndpointProperties implements InitializingBean {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelEndpointProperties.class);

    /** JSON 反序列化器；只用于部署配置，不接触用户输入。 */
    private final ObjectMapper objectMapper;

    /** 部署模式原始值，绑定后校验。 */
    private String deploymentMode = "PRODUCTION";

    /** 端点 JSON 数组原始值。 */
    private String endpoints = "";

    /** 是否允许“仅 DB 目录”的遗留兼容；生产必须显式开启并书面接受风险。 */
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
     * 启动时解析并校验端点配置；失败即让上下文启动失败。
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
            // Jackson 的详细错误包含输入片段；只保留配置键名，避免把部署地址写入启动日志。
            throw new IllegalStateException("riccon.model.endpoints must be a valid JSON array", exception);
        }
    }
}
