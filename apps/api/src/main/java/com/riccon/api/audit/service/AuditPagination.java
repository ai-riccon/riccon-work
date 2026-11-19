package com.riccon.api.audit.service;

/**
 * 审计查询分页参数。
 *
 * <p>契约要求 {@code page} 从 1 开始且最大 200，{@code size} 默认 20、最大 100；超限返回
 * {@code RICCON-AUDIT-400}，提示缩小时间范围或改用导出（ADR-0020 第 1 节）。
 *
 * @param page 当前页
 * @param size 每页大小
 */
public record AuditPagination(int page, int size) {

    /** 默认每页大小。 */
    public static final int DEFAULT_SIZE = 20;

    /** 最大每页大小。 */
    public static final int MAX_SIZE = 100;

    /** 最大页码。 */
    public static final int MAX_PAGE = 200;

    /**
     * 校验并创建分页参数。
     *
     * @param page 页码；为空时使用 1
     * @param size 每页大小；为空时使用 20
     * @return 合法分页参数
     * @throws AuditInvalidRequestException 页码或每页大小越界
     */
    public static AuditPagination of(Integer page, Integer size) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1 || resolvedPage > MAX_PAGE) {
            throw new AuditInvalidRequestException("page must be between 1 and " + MAX_PAGE);
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new AuditInvalidRequestException("size must be between 1 and " + MAX_SIZE);
        }
        return new AuditPagination(resolvedPage, resolvedSize);
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
