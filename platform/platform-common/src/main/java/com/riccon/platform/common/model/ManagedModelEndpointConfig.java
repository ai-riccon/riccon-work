package com.riccon.platform.common.model;

/**
 * 部署配置中的原始端点条目。
 *
 * <p>{@code RICCON_MODEL_ENDPOINTS} 是非密钥 JSON 数组；API 与 Worker 用各自 JSON 反序列化器把它映射为本记录，
 * 再由 {@link ManagedEndpointPolicy} 统一完成字段、URL 与重复校验，保证两侧语义一致。
 *
 * @param code 端点代码，全局唯一
 * @param baseUrl 端点基础 URL
 * @param environmentLabel 部署环境标签
 */
public record ManagedModelEndpointConfig(String code, String baseUrl, String environmentLabel) {}
