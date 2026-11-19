package com.riccon.api.audit.controller;

import com.riccon.api.audit.service.AuditFilterRequest;

/**
 * 发起导出申请请求体（OpenAPI {@code AuditExportCreateRequest}）。
 *
 * @param filter 过滤器；与查询过滤器同构
 */
public record AuditExportCreateRequest(AuditFilterRequest filter) {}
