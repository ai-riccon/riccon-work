package com.riccon.api.research.dto;

import java.util.List;

/**
 * 产物分页响应，对应 OpenAPI {@code ArtifactPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页产物
 */
public record ArtifactPageResponse(int page, int size, long total, int totalPages, List<ArtifactResponse> items) {}
