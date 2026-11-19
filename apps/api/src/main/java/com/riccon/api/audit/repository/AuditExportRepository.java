package com.riccon.api.audit.repository;

import com.riccon.api.audit.persistence.AuditExportJdbc;
import com.riccon.api.audit.persistence.AuditQueryJdbc;
import com.riccon.api.common.Sha256;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计导出请求仓储。
 *
 * <p>负责规范化过滤器 JSON 与其 SHA-256、状态派生和展示名批量解析；状态机由持久化层的条件更新保证。
 * 导出内容不落库，仓储只处理请求元数据（ADR-0020 第 2 节）。
 */
@Repository
public class AuditExportRepository {

    /** 团队默认时区偏移（Asia/Shanghai）；过滤器与响应时间统一使用该偏移。 */
    private static final ZoneOffset TEAM_OFFSET = ZoneOffset.ofHours(8);

    /** 规范化过滤器中的时间格式。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 导出请求持久化。 */
    private final AuditExportJdbc auditExportJdbc;

    /** 账号展示名批量查询。 */
    private final AuditQueryJdbc auditQueryJdbc;

    /** 规范化过滤器 JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建导出请求仓储。
     *
     * @param auditExportJdbc 导出请求持久化
     * @param auditQueryJdbc 账号展示名批量查询
     * @param objectMapper JSON 编解码
     */
    public AuditExportRepository(
            AuditExportJdbc auditExportJdbc, AuditQueryJdbc auditQueryJdbc, ObjectMapper objectMapper) {
        this.auditExportJdbc = Objects.requireNonNull(auditExportJdbc, "auditExportJdbc must not be null");
        this.auditQueryJdbc = Objects.requireNonNull(auditQueryJdbc, "auditQueryJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 计算规范化过滤器 JSON 的 SHA-256。
     *
     * @param filter 已规范化过滤器
     * @return 64 位十六进制哈希
     */
    public String filtersHash(AuditQueryFilter filter) {
        return Sha256.hex(canonicalJson(filter));
    }

    /**
     * 固化并保存导出申请。
     *
     * <p>字段长度在服务层已限制，规范化 JSON 远小于数据库注释约定的 4 KiB；这里不再重复截断，
     * 避免把非法输入静默降级。
     *
     * @param tenantId 申请租户
     * @param id 导出请求 ID
     * @param requestedBy 申请人账号 ID
     * @param filter 已规范化过滤器
     * @param estimatedRowCount 预计行数
     * @param now 申请时间
     * @return 新建的导出请求
     */
    public AuditExportRecord create(
            TenantId tenantId, UUID id, UUID requestedBy, AuditQueryFilter filter, int estimatedRowCount, Instant now) {
        String filtersJson = canonicalJson(filter);
        auditExportJdbc.insert(tenantId, id, requestedBy, filtersJson, Sha256.hex(filtersJson), estimatedRowCount, now);
        return findById(tenantId, id).orElseThrow(() -> new IllegalStateException("audit export was not persisted"));
    }

    /**
     * 按 ID 读取导出请求。
     *
     * @param tenantId 租户
     * @param exportId 导出请求 ID
     * @return 导出请求
     */
    public Optional<AuditExportRecord> findById(TenantId tenantId, UUID exportId) {
        return auditExportJdbc
                .findById(tenantId, exportId)
                .map(row -> mapRows(tenantId, List.of(row)).get(0));
    }

    /**
     * 分页列出导出请求。
     *
     * @param tenantId 租户
     * @param status 状态过滤；{@code null} 表示全部
     * @param now 当前时间；用于派生过期状态
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 导出请求
     */
    public List<AuditExportRecord> list(
            TenantId tenantId, AuditExportStatus status, Instant now, int limit, int offset) {
        return mapRows(tenantId, auditExportJdbc.list(tenantId, toStatusFilter(status), now, limit, offset));
    }

    /**
     * 统计导出请求数。
     *
     * @param tenantId 租户
     * @param status 状态过滤；{@code null} 表示全部
     * @param now 当前时间；用于派生过期状态
     * @return 行数
     */
    public long count(TenantId tenantId, AuditExportStatus status, Instant now) {
        return auditExportJdbc.count(tenantId, toStatusFilter(status), now);
    }

    /**
     * 条件审批导出请求；并发或状态已变化时返回 {@code false}。
     *
     * @param tenantId 导出所属租户
     * @param exportId 导出请求 ID
     * @param decision 审批决定
     * @param approvedBy 审批人账号 ID
     * @param approvedByTenantId 审批人所属租户
     * @param decidedAt 审批时间
     * @param expiresAt 下载截止时间；拒绝时为 {@code null}
     * @param comment 审批意见，可为空
     * @return 是否成功迁移
     */
    public boolean decide(
            TenantId tenantId,
            UUID exportId,
            AuditExportDecision decision,
            UUID approvedBy,
            String approvedByTenantId,
            Instant decidedAt,
            Instant expiresAt,
            String comment) {
        String status = decision == AuditExportDecision.APPROVE
                ? AuditExportStatus.APPROVED.name()
                : AuditExportStatus.REJECTED.name();
        return auditExportJdbc.decide(
                        tenantId, exportId, status, approvedBy, approvedByTenantId, decidedAt, expiresAt, comment)
                > 0;
    }

    /**
     * 把过滤器编码为确定性 JSON（键顺序固定、时间统一 +08:00），用于存储与哈希。
     *
     * @param filter 已规范化过滤器
     * @return 规范化 JSON
     */
    private String canonicalJson(AuditQueryFilter filter) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("occurredFrom", TIME_FORMAT.format(filter.occurredFrom()));
        values.put("occurredTo", TIME_FORMAT.format(filter.occurredTo()));
        if (filter.action() != null) {
            values.put("action", filter.action());
        }
        if (filter.actorId() != null) {
            values.put("actorId", filter.actorId().toString());
        }
        if (filter.targetType() != null) {
            values.put("targetType", filter.targetType().name());
        }
        if (filter.targetId() != null) {
            values.put("targetId", filter.targetId());
        }
        if (filter.outcome() != null) {
            values.put("outcome", filter.outcome().name());
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("failed to serialize audit filter", exception);
        }
    }

    /**
     * 从数据库 JSON 还原过滤器；数据损坏属于实现错误，直接失败而不是静默放宽条件。
     *
     * @param json 规范化 JSON
     * @return 过滤器
     */
    private AuditQueryFilter parseFilter(String json) {
        try {
            Map<String, Object> values = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return new AuditQueryFilter(
                    OffsetDateTime.parse(text(values.get("occurredFrom")), TIME_FORMAT)
                            .withOffsetSameInstant(TEAM_OFFSET),
                    OffsetDateTime.parse(text(values.get("occurredTo")), TIME_FORMAT)
                            .withOffsetSameInstant(TEAM_OFFSET),
                    text(values.get("action")),
                    values.get("actorId") == null ? null : UUID.fromString(text(values.get("actorId"))),
                    values.get("targetType") == null ? null : AuditTargetType.valueOf(text(values.get("targetType"))),
                    text(values.get("targetId")),
                    values.get("outcome") == null ? null : AuditOutcome.valueOf(text(values.get("outcome"))));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("stored audit filter is invalid", exception);
        }
    }

    /**
     * 读取文本字段。
     *
     * @param value JSON 值
     * @return 文本；为空时返回 {@code null}
     */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 批量映射导出请求行并解析申请人/审批人展示名；审批人可能属于平台租户，跨租户时不解析。
     *
     * @param tenantId 导出所属租户
     * @param rows 持久化行
     * @return 导出请求
     */
    private List<AuditExportRecord> mapRows(TenantId tenantId, List<AuditExportJdbc.Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> userIds = new LinkedHashSet<>();
        for (AuditExportJdbc.Row row : rows) {
            userIds.add(row.requestedBy());
            if (row.approvedBy() != null) {
                userIds.add(row.approvedBy());
            }
        }
        Map<UUID, String> displayNames = auditQueryJdbc.findDisplayNames(tenantId, List.copyOf(userIds));
        return rows.stream()
                .map(row -> new AuditExportRecord(
                        row.id(),
                        row.requestedBy(),
                        displayNames.get(row.requestedBy()),
                        AuditExportStatus.valueOf(row.status()),
                        parseFilter(row.filtersJson()),
                        row.filtersJson(),
                        row.filtersHash(),
                        row.estimatedRowCount(),
                        row.approvedBy(),
                        row.approvedByTenantId(),
                        row.approvedBy() == null ? null : displayNames.get(row.approvedBy()),
                        row.decidedAt(),
                        row.decisionComment(),
                        row.expiresAt(),
                        row.createdAt()))
                .toList();
    }

    /**
     * 映射服务层状态到持久化过滤。
     *
     * @param status 服务层状态；{@code null} 表示全部
     * @return 持久化过滤
     */
    private static AuditExportJdbc.StatusFilter toStatusFilter(AuditExportStatus status) {
        if (status == null) {
            return AuditExportJdbc.StatusFilter.ALL;
        }
        return switch (status) {
            case PENDING_APPROVAL -> AuditExportJdbc.StatusFilter.PENDING_APPROVAL;
            case APPROVED -> AuditExportJdbc.StatusFilter.APPROVED;
            case REJECTED -> AuditExportJdbc.StatusFilter.REJECTED;
            case EXPIRED -> AuditExportJdbc.StatusFilter.EXPIRED;
        };
    }
}
