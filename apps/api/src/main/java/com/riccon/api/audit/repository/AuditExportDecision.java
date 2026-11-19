package com.riccon.api.audit.repository;

/**
 * 审计导出审批决定（ADR-0020 第 2 节）。
 */
public enum AuditExportDecision {
    /** 批准；设置下载截止时间。 */
    APPROVE,
    /** 拒绝；不设下载截止时间。 */
    REJECT;

    /**
     * 解析契约中的决定值；非法值由服务层转换为 {@code RICCON-AUDIT-400}。
     *
     * @param value 请求值
     * @return 决定
     * @throws IllegalArgumentException 值不受支持
     */
    public static AuditExportDecision parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("decision is required");
        }
        return AuditExportDecision.valueOf(value.strip());
    }
}
