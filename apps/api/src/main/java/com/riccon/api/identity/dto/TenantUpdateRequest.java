package com.riccon.api.identity.dto;

import com.riccon.api.identity.domain.TenantStatus;

/**
 * 更新租户请求；两个字段均可选，但至少提供一个。
 *
 * @param status 目标状态
 * @param retentionDays 保留天数，30–3650
 */
public record TenantUpdateRequest(TenantStatus status, Integer retentionDays) {}
