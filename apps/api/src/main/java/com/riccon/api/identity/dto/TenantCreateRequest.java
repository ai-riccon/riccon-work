package com.riccon.api.identity.dto;

/**
 * 创建租户请求。
 *
 * @param id 租户代码，创建后不可修改；{@code platform} 为保留值
 * @param name 展示名
 */
public record TenantCreateRequest(String id, String name) {}
