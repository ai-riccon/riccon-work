package com.riccon.api.audit.service;

import com.riccon.api.audit.repository.AuditExportStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审计导出请求响应（OpenAPI {@code AuditExportRequest}）。
 *
 * <p>{@code status} 是派生状态：已批准且过期时返回 {@code EXPIRED}，不改写数据库；
 * {@code decisionComment} 只从导出记录读取，不进入审计 details。
 *
 * @param id 导出请求 ID
 * @param status 展示状态
 * @param requestedBy 申请人账号 ID
 * @param requestedByDisplayName 申请人展示名，可为空
 * @param filter 规范化过滤器
 * @param estimatedRowCount 预计行数
 * @param approvedBy 审批人账号 ID，可为空
 * @param approvedByDisplayName 审批人展示名，可为空
 * @param decidedAt 审批时间，可为空
 * @param decisionComment 审批意见，可为空
 * @param expiresAt 下载截止时间，可为空
 * @param createdAt 申请时间
 */
public record AuditExportView(
        UUID id,
        AuditExportStatus status,
        UUID requestedBy,
        String requestedByDisplayName,
        AuditFilterView filter,
        int estimatedRowCount,
        UUID approvedBy,
        String approvedByDisplayName,
        OffsetDateTime decidedAt,
        String decisionComment,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {}
