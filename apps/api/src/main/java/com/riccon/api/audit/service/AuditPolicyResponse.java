package com.riccon.api.audit.service;

import java.util.List;

/**
 * 审计策略响应（OpenAPI {@code AuditPolicy}）。
 *
 * <p>界面据此显示“审计至少保留 {retentionDays} 天，可查最近 180 天；无删除入口”，不写死 180 天。
 *
 * @param retentionDays 平台审计保留天数（≥180）
 * @param maxRangeDays 最大可查跨度（固定 180）
 * @param pageSizeMax 每页上限（固定 100）
 * @param maxPage 最大页码（固定 200）
 * @param actions 当前已知动作名，供界面下拉
 * @param exportLimits 导出限制
 */
public record AuditPolicyResponse(
        int retentionDays,
        int maxRangeDays,
        int pageSizeMax,
        int maxPage,
        List<String> actions,
        AuditExportLimits exportLimits) {

    /**
     * 导出限制。
     *
     * @param ttlHours 批准后的下载有效期（小时）
     * @param rowLimit 单次导出行数上限
     */
    public record AuditExportLimits(int ttlHours, int rowLimit) {}
}
