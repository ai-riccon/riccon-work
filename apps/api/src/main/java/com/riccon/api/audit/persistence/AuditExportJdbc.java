package com.riccon.api.audit.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审计导出请求持久化（ADR-0020 第 2/5.1 节）。
 *
 * <p>只存请求元数据，不存导出文件；审批用带 {@code status = 'PENDING_APPROVAL'} 的条件更新，
 * 并发时只有一个请求成功。所有 SQL 带租户条件，跨租户读取由平台管理路径显式切换租户后执行。
 */
@Component
public class AuditExportJdbc {

    /** 读取列。 */
    private static final String COLUMNS = """
            id, requested_by, status, filters, filters_hash, estimated_row_count,
            approved_by, approved_by_tenant_id, decided_at, decision_comment, expires_at, created_at
            """;

    /** 插入申请；状态固定为 PENDING_APPROVAL。 */
    private static final String INSERT = """
            INSERT INTO audit_export_request (
                tenant_id, id, requested_by, status, filters, filters_hash, estimated_row_count,
                created_at, updated_at
            ) VALUES (
                :tenantId, :id, :requestedBy, 'PENDING_APPROVAL', :filters, :filtersHash, :estimatedRowCount,
                :now, :now
            )
            """;

    /** 按 ID 读取。 */
    private static final String FIND_BY_ID =
            "SELECT " + COLUMNS + " FROM audit_export_request WHERE tenant_id = :tenantId AND id = :exportId";

    /** 列表基础条件。 */
    private static final String LIST_BASE =
            "SELECT " + COLUMNS + " FROM audit_export_request WHERE tenant_id = :tenantId";

    /** 计数基础条件。 */
    private static final String COUNT_BASE = "SELECT COUNT(*) FROM audit_export_request WHERE tenant_id = :tenantId";

    /** 列表固定排序：最新申请在前。 */
    private static final String LIST_SUFFIX = " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 条件更新：只有 PENDING_APPROVAL 可迁移，保证并发审批唯一成功。 */
    private static final String DECIDE = """
            UPDATE audit_export_request
               SET status = :status,
                   approved_by = :approvedBy,
                   approved_by_tenant_id = :approvedByTenantId,
                   decided_at = :decidedAt,
                   decision_comment = :decisionComment,
                   expires_at = :expiresAt,
                   updated_at = :decidedAt
             WHERE tenant_id = :tenantId
               AND id = :exportId
               AND status = 'PENDING_APPROVAL'
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建导出请求持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public AuditExportJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入导出申请。
     *
     * @param tenantId 申请租户
     * @param id 导出请求 ID
     * @param requestedBy 申请人账号 ID
     * @param filtersJson 规范化过滤器 JSON
     * @param filtersHash 过滤器 SHA-256
     * @param estimatedRowCount 预计行数
     * @param now 申请时间
     */
    public void insert(
            TenantId tenantId,
            UUID id,
            UUID requestedBy,
            String filtersJson,
            String filtersHash,
            int estimatedRowCount,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(id));
        params.put("requestedBy", BinaryUuids.toBytes(requestedBy));
        params.put("filters", filtersJson);
        params.put("filtersHash", filtersHash);
        params.put("estimatedRowCount", estimatedRowCount);
        params.put("now", SqlTimestamps.toDatabase(now));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按 ID 读取导出请求。
     *
     * @param tenantId 租户
     * @param exportId 导出请求 ID
     * @return 导出请求
     */
    public Optional<Row> findById(TenantId tenantId, UUID exportId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_ID, Map.of("exportId", BinaryUuids.toBytes(exportId)), this::mapRow);
    }

    /**
     * 分页列出导出请求。
     *
     * @param tenantId 租户
     * @param statusFilter 状态过滤
     * @param now 当前时间；用于 APPROVED/EXPIRED 派生过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 导出请求行
     */
    public List<Row> list(TenantId tenantId, StatusFilter statusFilter, Instant now, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = LIST_BASE + statusCondition(statusFilter) + LIST_SUFFIX;
        statusParams(statusFilter, now, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql, params, this::mapRow);
    }

    /**
     * 统计导出请求数。
     *
     * @param tenantId 租户
     * @param statusFilter 状态过滤
     * @param now 当前时间；用于 APPROVED/EXPIRED 派生过滤
     * @return 行数
     */
    public long count(TenantId tenantId, StatusFilter statusFilter, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = COUNT_BASE + statusCondition(statusFilter);
        statusParams(statusFilter, now, params);
        return tenantJdbc.count(tenantId, sql, params);
    }

    /**
     * 条件审批：只有 PENDING_APPROVAL 可迁移。
     *
     * @param tenantId 导出所属租户
     * @param exportId 导出请求 ID
     * @param status 目标状态（APPROVED/REJECTED）
     * @param approvedBy 审批人账号 ID
     * @param approvedByTenantId 审批人所属租户
     * @param decidedAt 审批时间
     * @param expiresAt 下载截止时间；拒绝时为 {@code null}
     * @param comment 审批意见，可为空
     * @return 受影响行数；0 表示并发冲突或状态已变化
     */
    public int decide(
            TenantId tenantId,
            UUID exportId,
            String status,
            UUID approvedBy,
            String approvedByTenantId,
            Instant decidedAt,
            Instant expiresAt,
            String comment) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status);
        params.put("approvedBy", BinaryUuids.toBytes(approvedBy));
        params.put("approvedByTenantId", approvedByTenantId);
        params.put("decidedAt", SqlTimestamps.toDatabase(decidedAt));
        params.put("decisionComment", comment);
        params.put("expiresAt", SqlTimestamps.toDatabase(expiresAt));
        params.put("exportId", BinaryUuids.toBytes(exportId));
        return tenantJdbc.update(tenantId, DECIDE, params);
    }

    /**
     * 生成状态过滤 SQL 片段。
     *
     * @param filter 状态过滤
     * @return SQL 片段；ALL 为空串
     */
    private static String statusCondition(StatusFilter filter) {
        return switch (filter) {
            case ALL -> "";
            case PENDING_APPROVAL -> " AND status = 'PENDING_APPROVAL'";
            case APPROVED -> " AND status = 'APPROVED' AND expires_at > :now";
            case REJECTED -> " AND status = 'REJECTED'";
            case EXPIRED -> " AND status = 'APPROVED' AND expires_at <= :now";
        };
    }

    /**
     * 绑定状态过滤需要的当前时间参数。
     *
     * @param filter 状态过滤
     * @param now 当前时间
     * @param params 命名参数
     */
    private static void statusParams(StatusFilter filter, Instant now, Map<String, Object> params) {
        if (filter == StatusFilter.APPROVED || filter == StatusFilter.EXPIRED) {
            params.put("now", SqlTimestamps.toDatabase(now));
        }
    }

    /**
     * 映射导出请求行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 导出行
     * @throws SQLException 列读取失败
     */
    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("requested_by")),
                rs.getString("status"),
                rs.getString("filters"),
                rs.getString("filters_hash"),
                rs.getInt("estimated_row_count"),
                BinaryUuids.fromBytes(rs.getBytes("approved_by")),
                rs.getString("approved_by_tenant_id"),
                SqlTimestamps.toInstant(rs.getObject("decided_at", LocalDateTime.class)),
                rs.getString("decision_comment"),
                SqlTimestamps.toInstant(rs.getObject("expires_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /**
     * 状态过滤；EXPIRED 是按时间派生的等价条件，不是数据库状态。
     */
    public enum StatusFilter {
        /** 全部状态。 */
        ALL,
        /** 等待审批。 */
        PENDING_APPROVAL,
        /** 已批准且未过期。 */
        APPROVED,
        /** 已拒绝。 */
        REJECTED,
        /** 已批准但已过期。 */
        EXPIRED
    }

    /**
     * 导出请求行。
     *
     * @param id 导出请求 ID
     * @param requestedBy 申请人账号 ID
     * @param status 数据库存储状态
     * @param filtersJson 规范化过滤器 JSON
     * @param filtersHash 过滤器 SHA-256
     * @param estimatedRowCount 预计行数
     * @param approvedBy 审批人账号 ID，可为空
     * @param approvedByTenantId 审批人租户，可为空
     * @param decidedAt 审批时间，可为空
     * @param decisionComment 审批意见，可为空
     * @param expiresAt 下载截止时间，可为空
     * @param createdAt 申请时间
     */
    public record Row(
            UUID id,
            UUID requestedBy,
            String status,
            String filtersJson,
            String filtersHash,
            int estimatedRowCount,
            UUID approvedBy,
            String approvedByTenantId,
            Instant decidedAt,
            String decisionComment,
            Instant expiresAt,
            Instant createdAt) {}
}
