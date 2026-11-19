package com.riccon.api.audit.service;

import java.util.List;

/**
 * 审计事件分页响应（OpenAPI {@code AuditEventPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页事件
 */
public record AuditEventPageResponse(int page, int size, long total, int totalPages, List<AuditEventView> items) {}
