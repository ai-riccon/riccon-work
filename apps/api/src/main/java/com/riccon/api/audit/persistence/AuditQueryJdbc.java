package com.riccon.api.audit.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审计事件只读查询（ADR-0020 第 1 节）。
 *
 * <p>只提供 SELECT：没有 update/delete 方法，符合 {@code audit_event} 的 append-only 约束。查询必须带租户条件
 * 与时间范围，排序固定 {@code occurred_at DESC, event_id DESC}；明确不读取 {@code source_ip}、
 * {@code request_id} 与 {@code schema_version}。操作者展示名用一次批量查询解析，避免逐行 N+1。
 */
@Component
public class AuditQueryJdbc {

    /** 查询列；不含 source_ip/request_id/schema_version。 */
    private static final String COLUMNS = """
            event_id, occurred_at, tenant_id, actor_type, actor_id, action,
            target_type, target_id, outcome, trace_id, details
            """;

    /** 计数基础条件。 */
    private static final String COUNT_PREFIX = "SELECT COUNT(*) FROM audit_event WHERE tenant_id = :tenantId";

    /** 分页查询基础语句。 */
    private static final String PAGE_PREFIX = "SELECT " + COLUMNS + " FROM audit_event WHERE tenant_id = :tenantId";

    /** 固定排序；不接受客户端排序字段。 */
    private static final String PAGE_SUFFIX = " ORDER BY occurred_at DESC, event_id DESC LIMIT :limit OFFSET :offset";

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建审计只读查询组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public AuditQueryJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 统计符合条件的事件数。
     *
     * @param tenantId 当前租户
     * @param criteria 已校验的查询条件
     * @return 行数
     */
    public long count(TenantId tenantId, Criteria criteria) {
        StringBuilder sql = new StringBuilder(COUNT_PREFIX);
        Map<String, Object> params = new LinkedHashMap<>();
        applyCriteria(criteria, sql, params);
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询审计事件。
     *
     * @param tenantId 当前租户
     * @param criteria 已校验的查询条件
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 事件行
     */
    public List<Row> page(TenantId tenantId, Criteria criteria, int limit, int offset) {
        StringBuilder sql = new StringBuilder(PAGE_PREFIX);
        Map<String, Object> params = new LinkedHashMap<>();
        applyCriteria(criteria, sql, params);
        sql.append(PAGE_SUFFIX);
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, this::mapRow);
    }

    /**
     * 批量解析账号展示名（ADR-0020 F-009：禁止逐行查询）。
     *
     * @param tenantId 当前租户
     * @param userIds 账号 ID；空集合直接返回空映射
     * @return 账号 ID 到展示名的映射
     */
    public Map<UUID, String> findDisplayNames(TenantId tenantId, List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", userIds.stream().map(BinaryUuids::toBytes).toList());
        List<Map.Entry<UUID, String>> rows = tenantJdbc.query(
                tenantId,
                "SELECT id, display_name FROM app_user WHERE tenant_id = :tenantId AND id IN (:ids)",
                params,
                (rs, rowNum) -> Map.entry(BinaryUuids.fromBytes(rs.getBytes("id")), rs.getString("display_name")));
        Map<UUID, String> names = new LinkedHashMap<>();
        rows.forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    /**
     * 把过滤条件追加到 SQL 与命名参数。
     *
     * @param criteria 查询条件
     * @param sql SQL 构造器
     * @param params 命名参数
     */
    private static void applyCriteria(Criteria criteria, StringBuilder sql, Map<String, Object> params) {
        sql.append(" AND occurred_at >= :fromTime AND occurred_at <= :toTime");
        params.put("fromTime", SqlTimestamps.toDatabase(criteria.from()));
        params.put("toTime", SqlTimestamps.toDatabase(criteria.to()));
        if (criteria.action() != null) {
            sql.append(" AND action = :action");
            params.put("action", criteria.action());
        }
        if (criteria.actorId() != null) {
            // actor_id 列是字符串（用户 UUID、服务名或 system），按文本精确匹配才能命中历史数据。
            sql.append(" AND actor_id = :actorId");
            params.put("actorId", criteria.actorId().toString());
        }
        if (criteria.targetType() != null) {
            sql.append(" AND target_type = :targetType");
            params.put("targetType", criteria.targetType().name());
        }
        if (criteria.targetId() != null) {
            sql.append(" AND target_id = :targetId");
            params.put("targetId", criteria.targetId());
        }
        if (criteria.outcome() != null) {
            sql.append(" AND outcome = :outcome");
            params.put("outcome", criteria.outcome().name());
        }
    }

    /**
     * 映射审计事件行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 事件行
     * @throws SQLException 列读取失败
     */
    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                BinaryUuids.fromBytes(rs.getBytes("event_id")),
                SqlTimestamps.toInstant(rs.getObject("occurred_at", java.time.LocalDateTime.class)),
                rs.getString("tenant_id"),
                AuditActorType.valueOf(rs.getString("actor_type")),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("target_type") == null ? null : AuditTargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"),
                AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getString("trace_id"),
                rs.getString("details"));
    }

    /**
     * 审计查询条件；由 Repository 从服务层过滤器转换，持久化层不感知 HTTP 参数。
     *
     * @param from 起始时间（UTC）
     * @param to 结束时间（UTC）
     * @param action 动作名，可为空
     * @param actorId 操作者 ID，可为空
     * @param targetType 目标类型，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果，可为空
     */
    public record Criteria(
            Instant from,
            Instant to,
            String action,
            UUID actorId,
            AuditTargetType targetType,
            String targetId,
            AuditOutcome outcome) {}

    /**
     * 审计事件行。
     *
     * @param eventId 事件 ID
     * @param occurredAt 事件时间（UTC）
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param actorId 操作者 ID
     * @param action 动作名
     * @param targetType 目标类型，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果
     * @param traceId 追踪 ID
     * @param detailsJson details 原始 JSON，可为空
     */
    public record Row(
            UUID eventId,
            Instant occurredAt,
            String tenantId,
            AuditActorType actorType,
            String actorId,
            String action,
            AuditTargetType targetType,
            String targetId,
            AuditOutcome outcome,
            String traceId,
            String detailsJson) {}
}
