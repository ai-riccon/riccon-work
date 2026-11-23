package com.riccon.worker.connector.domain;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已校验的连接器端点允许列表。
 *
 * <p>不可变；连接器只保存 {@code endpointId}，运行期按 ID 取回 URL。未列入的 ID 不允许创建/更新/测试，
 * 也不允许 Worker 发起连接（ADR-0023 第 6.5 节、TM-61）。
 */
public final class ConnectorEndpointRegistry {

    /** 按 ID 索引的端点，保持配置顺序。 */
    private final Map<String, ConnectorEndpointConfig> endpointsById;

    /** 允许列表中的归一化主机集合，用于重定向目标校验。 */
    private final Map<String, String> hostOwner;

    /**
     * 创建注册表；只允许 {@link ConnectorEndpointPolicy#load(List)} 调用。
     *
     * @param endpoints 已校验端点
     */
    ConnectorEndpointRegistry(List<ConnectorEndpointConfig> endpoints) {
        Map<String, ConnectorEndpointConfig> byId = new LinkedHashMap<>();
        Map<String, String> hosts = new LinkedHashMap<>();
        for (ConnectorEndpointConfig endpoint : endpoints) {
            byId.put(endpoint.id(), endpoint);
            hosts.putIfAbsent(ConnectorEndpointPolicy.extractHost(URI.create(endpoint.url())), endpoint.id());
        }
        this.endpointsById = Collections.unmodifiableMap(byId);
        this.hostOwner = Collections.unmodifiableMap(hosts);
    }

    /**
     * 按 ID 查找端点。
     *
     * @param id 端点 ID
     * @return 配置存在时返回
     */
    public Optional<ConnectorEndpointConfig> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(endpointsById.get(id));
    }

    /**
     * 按 ID 查找端点；不存在时抛出稳定错误。
     *
     * @param id 端点 ID
     * @return 端点
     * @throws IllegalArgumentException 端点不在允许列表
     */
    public ConnectorEndpointConfig require(String id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("connector endpoint is not allowed: " + id));
    }

    /**
     * 返回全部端点。
     *
     * @return 不可变列表
     */
    public List<ConnectorEndpointConfig> endpoints() {
        return List.copyOf(endpointsById.values());
    }

    /**
     * 判断主机是否属于允许列表。
     *
     * @param host 归一化主机
     * @return 属于时返回 true
     */
    public boolean containsHost(String host) {
        return host != null && hostOwner.containsKey(host);
    }

    /**
     * 返回允许列表是否为空。
     *
     * @return 空列表返回 true
     */
    public boolean isEmpty() {
        return endpointsById.isEmpty();
    }
}
