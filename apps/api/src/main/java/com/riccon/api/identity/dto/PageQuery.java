package com.riccon.api.identity.dto;

import com.riccon.api.error.BadRequestException;

/**
 * 强制分页参数。
 *
 * <p>合同要求所有列表分页：{@code page} 从 1 开始，{@code size} 默认 20、最大 100；禁止无界查询。
 *
 * @param page 当前页
 * @param size 每页大小
 */
public record PageQuery(int page, int size) {

    /** 默认每页大小。 */
    private static final int DEFAULT_SIZE = 20;

    /** 最大每页大小。 */
    private static final int MAX_SIZE = 100;

    /**
     * 校验并创建分页参数。
     *
     * @param page 页码；为空时使用 1
     * @param size 每页大小；为空时使用 20
     * @return 合法分页参数
     * @throws BadRequestException 页码小于 1 或每页大小超出 1–100
     */
    public static PageQuery of(Integer page, Integer size) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1) {
            throw new BadRequestException("page must be >= 1");
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_SIZE);
        }
        return new PageQuery(resolvedPage, resolvedSize);
    }

    /**
     * 计算 SQL 偏移量。
     *
     * @return 偏移量
     */
    public int offset() {
        return (page - 1) * size;
    }

    /**
     * 计算总页数。
     *
     * @param total 总行数
     * @return 总页数；无数据时为 0
     */
    public int totalPages(long total) {
        return total == 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
