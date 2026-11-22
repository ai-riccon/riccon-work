package com.riccon.api.admin.quota.dto;

import com.riccon.api.quota.dto.QuotaWindowState;
import java.util.List;

/**
 * 平台租户配额聚合行，对应 OpenAPI {@code PlatformQuotaRow}。
 *
 * <p>只包含租户级聚合；不得加入用户 ID、用户名或用户明细字段（ADR-0022 第 2.4 节）。
 *
 * @param tenantId 租户 ID
 * @param tenantCode 租户代码
 * @param tenantName 租户展示名
 * @param windows 日/月窗口
 */
public record PlatformQuotaRow(String tenantId, String tenantCode, String tenantName, List<QuotaWindowState> windows) {}
