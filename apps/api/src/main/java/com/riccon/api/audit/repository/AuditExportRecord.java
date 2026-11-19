package com.riccon.api.audit.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计导出请求读取模型（ADR-0020 第 2 节）。
 *
 * <p>只包含请求元数据与规范化过滤器；不含导出文件、对象 key 或审计内容。展示名在读取时按当前账号解析，
 * 不是历史快照。
 *
 * @param id 导出请求 ID
 * @param requestedBy 申请人账号 ID
 * @param requestedByDisplayName 申请人当前展示名，可为空
 * @param storedStatus 数据库存储状态
 * @param filter 规范化过滤器
 * @param filtersJson 规范化过滤器 JSON（与 {@code filters_hash} 对应）
 * @param filtersHash 规范化过滤器的 SHA-256
 * @param estimatedRowCount 申请时预计行数
 * @param approvedBy 审批人账号 ID，可为空
 * @param approvedByTenantId 审批人所属租户，可为空
 * @param approvedByDisplayName 审批人当前展示名，可为空
 * @param decidedAt 审批时间，可为空
 * @param decisionComment 审批意见，可为空；不进入审计 details
 * @param expiresAt 下载截止时间，可为空
 * @param createdAt 申请时间
 */
public record AuditExportRecord(
        UUID id,
        UUID requestedBy,
        String requestedByDisplayName,
        AuditExportStatus storedStatus,
        AuditQueryFilter filter,
        String filtersJson,
        String filtersHash,
        int estimatedRowCount,
        UUID approvedBy,
        String approvedByTenantId,
        String approvedByDisplayName,
        Instant decidedAt,
        String decisionComment,
        Instant expiresAt,
        Instant createdAt) {

    /**
     * 按当前时间派生展示状态（ADR-0020 第 2 节）。
     *
     * @param now 当前时间
     * @return 已批准且过期时返回 {@link AuditExportStatus#EXPIRED}
     */
    public AuditExportStatus effectiveStatus(Instant now) {
        if (storedStatus == AuditExportStatus.APPROVED && expiresAt != null && !expiresAt.isAfter(now)) {
            return AuditExportStatus.EXPIRED;
        }
        return storedStatus;
    }
}
