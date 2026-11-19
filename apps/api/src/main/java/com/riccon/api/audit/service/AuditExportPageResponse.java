package com.riccon.api.audit.service;

import java.util.List;

/**
 * 审计导出请求分页响应（OpenAPI {@code AuditExportRequestPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页导出请求
 */
public record AuditExportPageResponse(int page, int size, long total, int totalPages, List<AuditExportView> items) {}
