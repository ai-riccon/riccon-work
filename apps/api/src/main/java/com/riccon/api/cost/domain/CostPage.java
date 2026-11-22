package com.riccon.api.cost.domain;

/**
 * 成本查询分页参数。
 *
 * <p>ADR-0022 §2.4/§2.5 要求 {@code page 1..200}、{@code size 1..100}；聚合与明细共用。
 * 与通用 {@link com.riccon.api.identity.dto.PageQuery} 的区别是页号上限为 200，
 * 避免对聚合结果做深分页扫描。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 */
public record CostPage(int page, int size) {

    /** 默认每页大小。 */
    private static final int DEFAULT_SIZE = 20;

    /** 最大每页大小。 */
    private static final int MAX_SIZE = 100;

    /** 最大页号。 */
    private static final int MAX_PAGE = 200;

    /**
     * 校验并创建分页参数。
     *
     * @param page 页码；为空时使用 1
     * @param size 每页大小；为空时使用 20
     * @return 合法分页参数
     * @throws CostInvalidRequestException 页码或每页大小越界
     */
    public static CostPage of(Integer page, Integer size) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1 || resolvedPage > MAX_PAGE) {
            throw new CostInvalidRequestException("page must be between 1 and 200");
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new CostInvalidRequestException("size must be between 1 and 100");
        }
        return new CostPage(resolvedPage, resolvedSize);
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
