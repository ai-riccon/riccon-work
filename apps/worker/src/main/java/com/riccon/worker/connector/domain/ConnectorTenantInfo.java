package com.riccon.worker.connector.domain;

/**
 * 租户状态与保留天数。
 *
 * @param status 租户状态
 * @param retentionDays 保留天数
 */
public record ConnectorTenantInfo(String status, int retentionDays) {}
