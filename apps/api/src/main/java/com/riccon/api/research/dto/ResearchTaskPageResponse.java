package com.riccon.api.research.dto;

import java.util.List;

/**
 * 报告任务分页响应，对应 OpenAPI {@code ResearchTaskPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页任务
 */
public record ResearchTaskPageResponse(
        int page, int size, long total, int totalPages, List<ResearchTaskResponse> items) {}
