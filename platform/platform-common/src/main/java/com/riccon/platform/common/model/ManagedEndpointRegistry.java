package com.riccon.platform.common.model;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 已校验的受管端点允许列表。
 *
 * <p>由 {@link ManagedEndpointPolicy#load(List)} 创建后不可变；API 用它计算目录中的 {@code allowed} 与
 * {@code environmentLabel}，Worker 在调用前按 code + baseUrl 复核，形成“保存/启动 + 调用前”双重校验（ADR-0021 §2.1）。
 */
public final class ManagedEndpointRegistry {

    /** 部署模式；演示端点在 PRODUCTION 下不可路由。 */
    private final ModelDeploymentMode deploymentMode;

    /** 按 code 索引的端点，保持配置顺序。 */
    private final Map<String, ManagedModelEndpoint> endpointsByCode;

    /** 是否处于“仅 DB 目录”的遗留兼容模式（部署未配置允许列表且已显式/演示接受）。 */
    private final boolean legacyCatalog;

    /**
     * 创建允许列表；只允许 {@link ManagedEndpointPolicy} 调用。
     *
     * @param deploymentMode 部署模式
     * @param endpoints 已校验端点
     */
    ManagedEndpointRegistry(ModelDeploymentMode deploymentMode, List<ManagedModelEndpoint> endpoints) {
        this.deploymentMode = deploymentMode;
        Map<String, ManagedModelEndpoint> byCode = new LinkedHashMap<>();
        for (ManagedModelEndpoint endpoint : endpoints) {
            byCode.put(endpoint.code(), endpoint);
        }
        this.endpointsByCode = Collections.unmodifiableMap(byCode);
        // ADR-0021 §2.1：允许列表为空表示 DEMO 遗留兼容或 PRODUCTION 显式风险接受；
        // 由 ModelEndpointProperties 决定是否允许启动，这里只记录“仅 DB 目录”语义。
        this.legacyCatalog = endpoints.isEmpty();
    }

    /**
     * 返回部署模式。
     *
     * @return 部署模式
     */
    public ModelDeploymentMode deploymentMode() {
        return deploymentMode;
    }

    /**
     * 返回全部端点（配置顺序）。
     *
     * @return 不可变列表
     */
    public List<ManagedModelEndpoint> endpoints() {
        return List.copyOf(endpointsByCode.values());
    }

    /**
     * 按 code 查找端点。
     *
     * @param code 端点代码
     * @return 配置存在时返回端点
     */
    public Optional<ManagedModelEndpoint> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(endpointsByCode.get(code));
    }

    /**
     * 判断端点标签在当前部署模式下是否可被路由选择。
     *
     * @param label 端点标签
     * @return 演示端点仅 DEMO 模式可路由；生产端点始终可路由
     */
    public boolean isEnvironmentRoutable(EndpointEnvironmentLabel label) {
        return label != null
                && (deploymentMode == ModelDeploymentMode.DEMO || label == EndpointEnvironmentLabel.PRODUCTION);
    }

    /**
     * 判断 provider 目录行是否被允许路由。
     *
     * <p>要求 code 存在于允许列表、标签在当前模式下可路由，且 baseUrl 与部署配置规范化后一致；
     * DB 行与部署配置不一致时返回 false（以部署配置为准，运行时再次拒绝，ADR-0021 §2.1）。
     *
     * @param code provider code
     * @param rawBaseUrl DB 目录中的 base_url；可为空
     * @return 是否允许
     */
    public boolean isAllowed(String code, String rawBaseUrl) {
        if (code == null || rawBaseUrl == null) {
            return false;
        }
        if (legacyCatalog) {
            // DEMO 遗留兼容 / PRODUCTION 显式风险接受：DB 目录行全部可被目录展示与路由选择；
            // URL/元数据/标签仍由 B4 在每次调用前用 ManagedEndpointPolicy 再次校验。
            return true;
        }
        ManagedModelEndpoint endpoint = endpointsByCode.get(code);
        if (endpoint == null || !isEnvironmentRoutable(endpoint.environmentLabel())) {
            return false;
        }
        String candidate;
        try {
            candidate = ManagedEndpointPolicy.normalizeBaseUrl(URI.create(rawBaseUrl.strip()));
        } catch (RuntimeException exception) {
            return false;
        }
        return candidate.equals(ManagedEndpointPolicy.normalizeBaseUrl(endpoint.baseUrl()));
    }

    /**
     * 返回端点标签；未配置时为空。
     *
     * @param code provider code
     * @return 标签
     */
    public Optional<EndpointEnvironmentLabel> labelFor(String code) {
        return findByCode(code).map(ManagedModelEndpoint::environmentLabel);
    }

    /**
     * 判断允许列表是否为空。
     *
     * @return 空列表返回 true
     */
    public boolean isEmpty() {
        return endpointsByCode.isEmpty();
    }

    /**
     * 是否处于“仅 DB 目录”的遗留兼容模式。
     *
     * @return 允许列表为空时返回 true
     */
    public boolean isLegacyCatalog() {
        return legacyCatalog;
    }

    /**
     * 规范化 URL 的 host 部分，供目录比较与诊断。
     *
     * @param host 原始 host
     * @return 小写、去尾部点、去方括号的 host
     */
    static String normalizeHost(String host) {
        String value = host.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            value = value.substring(1);
        }
        if (value.endsWith("]")) {
            value = value.substring(0, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
