package com.riccon.api.audit.repository;

/**
 * 审计导出状态。
 *
 * <p>存储状态只有 {@link #PENDING_APPROVAL}/{@link #APPROVED}/{@link #REJECTED}；
 * {@link #EXPIRED} 是按 {@code expires_at} 与当前时间派生的展示/过滤值，不改写数据库状态
 * （ADR-0020 第 2 节）。
 */
public enum AuditExportStatus {
    /** 等待审批。 */
    PENDING_APPROVAL,
    /** 已批准且未过期。 */
    APPROVED,
    /** 已拒绝。 */
    REJECTED,
    /** 已批准但下载有效期已过（派生值）。 */
    EXPIRED;

    /**
     * 判断是否为数据库可存储状态。
     *
     * @return 可存储时返回 {@code true}
     */
    public boolean stored() {
        return this != EXPIRED;
    }
}
