package com.riccon.api.cost.dto;

import java.util.List;

/**
 * 调用成本明细分页，对应 OpenAPI {@code CostInvocationPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页明细
 */
public record CostInvocationPage(int page, int size, long total, int totalPages, List<CostInvocation> items) {}
