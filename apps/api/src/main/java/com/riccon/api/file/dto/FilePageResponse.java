package com.riccon.api.file.dto;

import java.util.List;

/**
 * 文件分页响应，对应 OpenAPI {@code FilePage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页文件
 */
public record FilePageResponse(int page, int size, long total, int totalPages, List<FileResponse> items) {}
