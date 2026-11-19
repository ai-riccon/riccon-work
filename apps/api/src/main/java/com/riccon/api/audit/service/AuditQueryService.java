package com.riccon.api.audit.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditEventRecord;
import com.riccon.api.audit.repository.AuditExportRepository;
import com.riccon.api.audit.repository.AuditQueryFilter;
import com.riccon.api.audit.repository.AuditQueryRepository;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计查询服务（ADR-0020 第 1 节）。
 *
 * <p>租户只来自已认证主体；分页与时间范围在服务端强制。查询成功后在同一租户写 {@code audit.queried}，
 * 审计写入失败使查询失败（fail-closed）。读取 details 时执行与写入相同的 action-aware 白名单。
 */
@Service
public class AuditQueryService {

    /** 审计只读仓储。 */
    private final AuditQueryRepository auditQueryRepository;

    /** 导出仓储：复用规范化过滤器哈希。 */
    private final AuditExportRepository auditExportRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 查询/导出配置。 */
    private final AuditProperties auditProperties;

    /** details JSON 解析。 */
    private final ObjectMapper objectMapper;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建审计查询服务。
     *
     * @param auditQueryRepository 审计只读仓储
     * @param auditExportRepository 导出仓储
     * @param auditService 审计写入
     * @param auditProperties 查询/导出配置
     * @param objectMapper details JSON 解析
     * @param clock 可注入时钟
     */
    public AuditQueryService(
            AuditQueryRepository auditQueryRepository,
            AuditExportRepository auditExportRepository,
            AuditService auditService,
            AuditProperties auditProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.auditQueryRepository = Objects.requireNonNull(auditQueryRepository, "auditQueryRepository");
        this.auditExportRepository = Objects.requireNonNull(auditExportRepository, "auditExportRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.auditProperties = Objects.requireNonNull(auditProperties, "auditProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 查询审计事件并写查询审计。
     *
     * @param tenantId 已认证主体租户
     * @param occurredFrom 起始时间原文，可为空
     * @param occurredTo 结束时间原文，可为空
     * @param action 动作名，可为空
     * @param actorId 操作者 ID 原文，可为空
     * @param targetType 目标类型原文，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果原文，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @param actor 当前主体
     * @param meta 请求元数据
     * @return 审计分页
     */
    @Transactional
    public AuditEventPageResponse list(
            TenantId tenantId,
            String occurredFrom,
            String occurredTo,
            String action,
            String actorId,
            String targetType,
            String targetId,
            String outcome,
            Integer page,
            Integer size,
            RicconPrincipal actor,
            RequestMeta meta) {
        Instant now = clock.instant();
        AuditQueryFilter filter = AuditQueryFilters.resolve(
                occurredFrom, occurredTo, action, actorId, targetType, targetId, outcome, now);
        AuditPagination pagination = AuditPagination.of(page, size);
        long total = auditQueryRepository.count(tenantId, filter);
        List<AuditEventView> items =
                auditQueryRepository.page(tenantId, filter, pagination.size(), pagination.offset()).stream()
                        .map(this::toView)
                        .toList();

        // 查询审计在读取之后写入，不会出现在本次结果中；写失败直接让请求失败。
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filtersHash", auditExportRepository.filtersHash(filter));
        details.put("rowCount", items.size());
        auditService.append(AuditEvent.create(
                now,
                tenantId.value(),
                AuditActorType.USER,
                actor.userId().toString(),
                AuditActions.AUDIT_QUERIED,
                AuditTargetType.AUDIT,
                null,
                AuditOutcome.SUCCESS,
                meta.traceId(),
                meta.requestId(),
                null,
                details));
        return new AuditEventPageResponse(
                pagination.page(), pagination.size(), total, pagination.totalPages(total), items);
    }

    /**
     * 返回审计保留、范围、分页、动作与导出策略。
     *
     * @return 审计策略
     */
    public AuditPolicyResponse policy() {
        AuditProperties.Export export = auditProperties.getExport();
        // 契约要求 ttlHours 为 1–168 的整数；测试可用秒级 TTL，向上取整避免出现 0。
        int ttlHours = (int) Math.max(1, Math.ceil(export.getTtl().toSeconds() / 3600.0));
        return new AuditPolicyResponse(
                auditProperties.getRetentionDays(),
                AuditQueryFilters.MAX_RANGE_DAYS,
                AuditPagination.MAX_SIZE,
                AuditPagination.MAX_PAGE,
                AuditActionVocabulary.knownActions(),
                new AuditPolicyResponse.AuditExportLimits(ttlHours, export.getRowLimit()));
    }

    /**
     * 映射事件记录为响应视图，并执行 details 读取白名单。
     *
     * @param record 事件记录
     * @return 事件视图
     * @throws AuditDetailsInvalidException details 命中 fail-closed 策略
     */
    AuditEventView toView(AuditEventRecord record) {
        return new AuditEventView(
                record.eventId(),
                record.occurredAt().atOffset(AuditQueryFilters.TEAM_OFFSET),
                record.tenantId(),
                record.actorType(),
                record.actorId(),
                record.actorDisplayName(),
                record.action(),
                record.targetType(),
                record.targetId(),
                record.outcome(),
                record.traceId(),
                AuditDetailsReader.read(objectMapper, record.action(), record.detailsJson()));
    }
}
