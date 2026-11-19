package com.riccon.api.audit.repository;

import com.riccon.api.audit.persistence.AuditQueryJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 审计事件只读仓储。
 *
 * <p>仓储只暴露查询；没有写入入口（审计写入走 {@code AuditRepository}）。操作者展示名按页批量解析，
 * 避免逐行查询（ADR-0020 F-009）。
 */
@Repository
public class AuditQueryRepository {

    /** 审计只读查询。 */
    private final AuditQueryJdbc auditQueryJdbc;

    /**
     * 创建审计只读仓储。
     *
     * @param auditQueryJdbc 审计只读查询
     */
    public AuditQueryRepository(AuditQueryJdbc auditQueryJdbc) {
        this.auditQueryJdbc = Objects.requireNonNull(auditQueryJdbc, "auditQueryJdbc must not be null");
    }

    /**
     * 统计符合条件的事件数。
     *
     * @param tenantId 当前租户
     * @param filter 已规范化过滤器
     * @return 行数
     */
    public long count(TenantId tenantId, AuditQueryFilter filter) {
        return auditQueryJdbc.count(tenantId, criteria(filter));
    }

    /**
     * 分页查询审计事件。
     *
     * @param tenantId 当前租户
     * @param filter 已规范化过滤器
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 事件记录
     */
    public List<AuditEventRecord> page(TenantId tenantId, AuditQueryFilter filter, int limit, int offset) {
        List<AuditQueryJdbc.Row> rows = auditQueryJdbc.page(tenantId, criteria(filter), limit, offset);
        Set<UUID> userIds = new LinkedHashSet<>();
        for (AuditQueryJdbc.Row row : rows) {
            if (row.actorType() == AuditActorType.USER) {
                parseUserId(row.actorId()).ifPresent(userIds::add);
            }
        }
        Map<UUID, String> displayNames = auditQueryJdbc.findDisplayNames(tenantId, List.copyOf(userIds));
        return rows.stream().map(row -> toRecord(row, displayNames)).toList();
    }

    /**
     * 把服务层过滤器转换为持久化查询条件。
     *
     * @param filter 已规范化过滤器
     * @return 查询条件
     */
    private static AuditQueryJdbc.Criteria criteria(AuditQueryFilter filter) {
        return new AuditQueryJdbc.Criteria(
                filter.occurredFrom().toInstant(),
                filter.occurredTo().toInstant(),
                filter.action(),
                filter.actorId(),
                filter.targetType(),
                filter.targetId(),
                filter.outcome());
    }

    /**
     * 映射事件记录并附加读取时的展示名。
     *
     * @param row 事件行
     * @param displayNames 账号展示名映射
     * @return 事件记录
     */
    private static AuditEventRecord toRecord(AuditQueryJdbc.Row row, Map<UUID, String> displayNames) {
        String displayName = null;
        if (row.actorType() == AuditActorType.USER) {
            displayName = parseUserId(row.actorId()).map(displayNames::get).orElse(null);
        }
        return new AuditEventRecord(
                row.eventId(),
                row.occurredAt(),
                row.tenantId(),
                row.actorType(),
                row.actorId(),
                displayName,
                row.action(),
                row.targetType(),
                row.targetId(),
                row.outcome(),
                row.traceId(),
                row.detailsJson());
    }

    /**
     * 解析用户操作者 ID；服务账号或 system 不是 UUID，不参与展示名解析。
     *
     * @param actorId 操作者 ID
     * @return 合法 UUID
     */
    private static Optional<UUID> parseUserId(String actorId) {
        if (actorId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(actorId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
