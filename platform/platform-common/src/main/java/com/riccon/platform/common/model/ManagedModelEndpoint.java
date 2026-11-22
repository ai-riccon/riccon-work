package com.riccon.platform.common.model;

import java.net.URI;

/**
 * 已通过校验的受管端点。
 *
 * <p>只由 {@link ManagedEndpointPolicy#load(java.util.List)} 创建；构造时再次保证字段完整，
 * 避免未校验对象进入路由或目录响应。
 *
 * @param code 端点代码
 * @param baseUrl 已规范化的基础 URL
 * @param environmentLabel 部署环境标签
 */
public record ManagedModelEndpoint(String code, URI baseUrl, EndpointEnvironmentLabel environmentLabel) {

    /** 保证配置对象完整。 */
    public ManagedModelEndpoint {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("model endpoint code must not be blank");
        }
        if (baseUrl == null) {
            throw new IllegalArgumentException("model endpoint baseUrl must not be null");
        }
        if (environmentLabel == null) {
            throw new IllegalArgumentException("model endpoint environmentLabel must not be null");
        }
    }
}
