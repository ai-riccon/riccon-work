package com.riccon.api.audit.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditEventRecord;
import com.riccon.api.audit.repository.AuditExportDecision;
import com.riccon.api.audit.repository.AuditExportRecord;
import com.riccon.api.audit.repository.AuditExportRepository;
import com.riccon.api.audit.repository.AuditExportStatus;
import com.riccon.api.audit.repository.AuditQueryFilter;
import com.riccon.api.audit.repository.AuditQueryRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计导出申请、审批与下载服务（ADR-0020 第 2/3 节）。
 *
 * <p>安全边界：
 *
 * <ul>
 *   <li>租户只来自已认证主体；平台租户自身导出在 M10 不支持（403），平台管理员下载租户导出按 404 处理；
 *   <li>只有申请人本人可下载；申请人不得自批；审批用条件更新保证并发唯一成功；
 *   <li>下载不落盘、不写对象存储，按 CSV 流式输出；过期与行数在开始流式前判定；
 *   <li>{@code data.exported} 在流式开始前写入，失败使下载失败（fail-closed）。
 * </ul>
 */
@Service
public class AuditExportService {

    /** 时间格式：ISO 8601 带偏移。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 审批意见最大长度（OpenAPI AuditExportDecisionRequest.comment）。 */
    private static final int MAX_COMMENT_LENGTH = 500;

    /** CSV 流式分页大小；避免一次加载 10,000 行。 */
    private static final int STREAM_BATCH_SIZE = 500;

    /** 导出仓储。 */
    private final AuditExportRepository auditExportRepository;

    /** 审计只读仓储：预计行数与下载前重新计数。 */
    private final AuditQueryRepository auditQueryRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 查询/导出配置。 */
    private final AuditProperties auditProperties;

    /** CSV details 序列化。 */
    private final ObjectMapper objectMapper;

    /** 可注入时钟；TTL 与过期判断使用同一时钟。 */
    private final Clock clock;

    /**
     * 创建审计导出服务。
     *
     * @param auditExportRepository 导出仓储
     * @param auditQueryRepository 审计只读仓储
     * @param auditService 审计写入
     * @param auditProperties 查询/导出配置
     * @param objectMapper CSV details 序列化
     * @param clock 可注入时钟
     */
    public AuditExportService(
            AuditExportRepository auditExportRepository,
            AuditQueryRepository auditQueryRepository,
            AuditService auditService,
            AuditProperties auditProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.auditExportRepository = Objects.requireNonNull(auditExportRepository, "auditExportRepository");
        this.auditQueryRepository = Objects.requireNonNull(auditQueryRepository, "auditQueryRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.auditProperties = Objects.requireNonNull(auditProperties, "auditProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 发起导出申请；预计行数由服务端计算，超过上限返回 400。
     *
     * @param tenantId 申请租户
     * @param request 过滤器原文
     * @param actor 申请人
     * @param meta 请求元数据
     * @return 新建导出请求
     */
    @Transactional
    public AuditExportView create(
            TenantId tenantId, AuditFilterRequest request, RicconPrincipal actor, RequestMeta meta) {
        requireTenantExportSupported(tenantId);
        if (request == null) {
            throw new AuditInvalidRequestException("filter is required");
        }
        Instant now = clock.instant();
        AuditQueryFilter filter = resolve(request, now);
        long estimated = auditQueryRepository.count(tenantId, filter);
        if (estimated > auditProperties.getExport().getRowLimit()) {
            throw new AuditInvalidRequestException(
                    "estimated rows exceed export limit; narrow the time range or filters");
        }
        UUID exportId = UuidV7.generate(now);
        AuditExportRecord record = TenantContext.callWithTenant(
                tenantId,
                () -> auditExportRepository.create(tenantId, exportId, actor.userId(), filter, (int) estimated, now));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportId", exportId.toString());
        details.put("filtersHash", record.filtersHash());
        details.put("estimatedRowCount", record.estimatedRowCount());
        details.put("status", AuditExportStatus.PENDING_APPROVAL.name());
        putFilterDetails(details, record.filter());
        appendAudit(tenantId.value(), actor, meta, AuditActions.AUDIT_EXPORT_REQUESTED, exportId.toString(), details);
        return toView(record, now);
    }

    /**
     * 列出本租户导出请求；平台租户自身导出在 M10 不支持。
     *
     * @param tenantId 租户
     * @param statusRaw 状态原文，可为空（含派生 {@code EXPIRED}）
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @return 导出请求分页
     */
    @Transactional(readOnly = true)
    public AuditExportPageResponse list(TenantId tenantId, String statusRaw, Integer page, Integer size) {
        requireTenantExportSupported(tenantId);
        AuditExportStatus status = parseStatus(statusRaw);
        AuditPagination pagination = AuditPagination.of(page, size);
        Instant now = clock.instant();
        return TenantContext.callWithTenant(tenantId, () -> {
            long total = auditExportRepository.count(tenantId, status, now);
            List<AuditExportView> items =
                    auditExportRepository.list(tenantId, status, now, pagination.size(), pagination.offset()).stream()
                            .map(record -> toView(record, now))
                            .toList();
            return new AuditExportPageResponse(
                    pagination.page(), pagination.size(), total, pagination.totalPages(total), items);
        });
    }

    /**
     * 读取本租户导出请求详情。
     *
     * @param tenantId 租户
     * @param exportId 导出请求 ID
     * @return 导出请求
     */
    @Transactional(readOnly = true)
    public AuditExportView get(TenantId tenantId, UUID exportId) {
        requireTenantExportSupported(tenantId);
        Instant now = clock.instant();
        AuditExportRecord record = TenantContext.callWithTenant(tenantId, () -> findOrThrow(tenantId, exportId));
        return toView(record, now);
    }

    /**
     * 同租户审批导出请求；申请人不得自批，并发审批只有一个成功。
     *
     * @param tenantId 当前租户
     * @param exportId 导出请求 ID
     * @param decisionRaw 决定原文（{@code APPROVE}/{@code REJECT}）
     * @param comment 审批意见，可为空
     * @param actor 审批人
     * @param meta 请求元数据
     * @return 审批后的导出请求
     */
    @Transactional
    public AuditExportView decideTenant(
            TenantId tenantId,
            UUID exportId,
            String decisionRaw,
            String comment,
            RicconPrincipal actor,
            RequestMeta meta) {
        requireTenantExportSupported(tenantId);
        return applyDecision(tenantId, exportId, decisionRaw, comment, actor, meta, tenantId.value(), null);
    }

    /**
     * 平台管理员审批单管理员租户的导出请求（仅元数据）。
     *
     * <p>调用方（平台管理服务）必须先完成单管理员租户校验；本方法把审批审计写到 {@code platform} 租户并带
     * {@code details.targetTenantId}，不写目标租户审计，也不提供下载。
     *
     * @param targetTenant 目标租户
     * @param exportId 导出请求 ID
     * @param decisionRaw 决定原文
     * @param comment 审批意见，可为空
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 审批后的导出请求
     */
    @Transactional
    public AuditExportView decidePlatform(
            TenantId targetTenant,
            UUID exportId,
            String decisionRaw,
            String comment,
            RicconPrincipal actor,
            RequestMeta meta) {
        if (PlatformTenant.ID.equals(targetTenant.value())) {
            throw new AuditForbiddenException("platform tenant export is not supported");
        }
        return applyDecision(
                targetTenant, exportId, decisionRaw, comment, actor, meta, PlatformTenant.ID, targetTenant.value());
    }

    /**
     * 下载前校验并写 {@code data.exported}（在流式开始前，fail-closed）。
     *
     * @param tenantId 当前租户
     * @param exportId 导出请求 ID
     * @param actor 当前主体
     * @param meta 请求元数据
     * @return 流式导出描述
     */
    @Transactional
    public AuditDownload prepareDownload(TenantId tenantId, UUID exportId, RicconPrincipal actor, RequestMeta meta) {
        // 平台管理员调用租户下载路径按 404 处理，不泄露导出存在性（ADR-0020 F-015）。
        if (PlatformTenant.ID.equals(tenantId.value())) {
            throw new NotFoundException("audit export");
        }
        Instant now = clock.instant();
        AuditExportRecord record = TenantContext.callWithTenant(tenantId, () -> findOrThrow(tenantId, exportId));
        if (!record.requestedBy().equals(actor.userId())) {
            throw new AuditForbiddenException("only the requester can download the export");
        }
        if (record.storedStatus() != AuditExportStatus.APPROVED) {
            throw new AuditConflictException("export is not approved");
        }
        if (record.expiresAt() == null || !record.expiresAt().isAfter(now)) {
            throw new AuditExportExpiredException("export has expired");
        }
        long rowCount =
                TenantContext.callWithTenant(tenantId, () -> auditQueryRepository.count(tenantId, record.filter()));
        if (rowCount > auditProperties.getExport().getRowLimit()) {
            throw new AuditConflictException("export exceeds row limit after re-count; narrow the filter");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportId", exportId.toString());
        details.put("scope", "audit");
        details.put("rowCount", rowCount);
        details.put("filtersHash", record.filtersHash());
        appendAudit(tenantId.value(), actor, meta, AuditActions.DATA_EXPORTED, exportId.toString(), details);

        return new AuditDownload(
                exportId,
                record.filter(),
                record.filtersJson(),
                record.filtersHash(),
                rowCount,
                now.atOffset(AuditQueryFilters.TEAM_OFFSET));
    }

    /**
     * 流式写 CSV；在异步线程内重新打开租户作用域。
     *
     * @param download 已校验的导出描述
     * @param tenantId 导出所属租户
     * @param outputStream 响应输出流；调用方负责关闭
     * @throws IOException 写入失败或读取审计失败
     */
    public void writeCsv(AuditDownload download, TenantId tenantId, OutputStream outputStream) throws IOException {
        // StreamingResponseBody 在容器异步线程执行；TenantContext 不会跨线程传播，必须显式打开。
        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            AuditCsvWriter.writeMetadata(writer, download);
            AuditCsvWriter.writeHeader(writer);
            int offset = 0;
            while (true) {
                List<AuditEventRecord> rows =
                        auditQueryRepository.page(tenantId, download.filter(), STREAM_BATCH_SIZE, offset);
                if (rows.isEmpty()) {
                    break;
                }
                for (AuditEventRecord record : rows) {
                    AuditCsvWriter.writeRow(writer, toEventView(record), objectMapper);
                }
                offset += rows.size();
                if (rows.size() < STREAM_BATCH_SIZE) {
                    break;
                }
            }
            writer.flush();
        }
    }

    /**
     * 执行审批状态机：自批 403、已决定/并发 409、拒绝存意见、批准设置过期。
     *
     * @param exportTenant 导出所属租户
     * @param exportId 导出请求 ID
     * @param decisionRaw 决定原文
     * @param comment 审批意见，可为空
     * @param actor 审批人
     * @param meta 请求元数据
     * @param approverTenantId 审批人租户；平台审批为 {@code platform}
     * @param targetTenantIdForAudit 平台审批审计的 {@code details.targetTenantId}；租户审批为空
     * @return 审批后的导出请求
     */
    private AuditExportView applyDecision(
            TenantId exportTenant,
            UUID exportId,
            String decisionRaw,
            String comment,
            RicconPrincipal actor,
            RequestMeta meta,
            String approverTenantId,
            String targetTenantIdForAudit) {
        AuditExportDecision decision = parseDecision(decisionRaw);
        String normalizedComment = normalizeComment(comment);
        Instant now = clock.instant();
        Instant expiresAt = decision == AuditExportDecision.APPROVE
                ? now.plus(auditProperties.getExport().getTtl())
                : null;

        AuditExportRecord updated = TenantContext.callWithTenant(exportTenant, () -> {
            AuditExportRecord existing = findOrThrow(exportTenant, exportId);
            if (existing.requestedBy().equals(actor.userId())) {
                throw new AuditForbiddenException("requester cannot decide own export");
            }
            boolean changed = auditExportRepository.decide(
                    exportTenant,
                    exportId,
                    decision,
                    actor.userId(),
                    approverTenantId,
                    now,
                    expiresAt,
                    normalizedComment);
            if (!changed) {
                // 条件更新失败：要么已被其他审批人决定（并发），要么状态不再是 PENDING_APPROVAL。
                findOrThrow(exportTenant, exportId);
                throw new AuditConflictException("export was already decided");
            }
            return findOrThrow(exportTenant, exportId);
        });

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportId", exportId.toString());
        details.put("decision", decision.name());
        details.put("estimatedRowCount", updated.estimatedRowCount());
        details.put("filtersHash", updated.filtersHash());
        if (targetTenantIdForAudit != null) {
            details.put("targetTenantId", targetTenantIdForAudit);
        }
        putFilterDetails(details, updated.filter());
        appendAudit(
                approverTenantId,
                actor,
                meta,
                decision == AuditExportDecision.APPROVE
                        ? AuditActions.AUDIT_EXPORT_APPROVED
                        : AuditActions.AUDIT_EXPORT_REJECTED,
                exportId.toString(),
                details);
        return toView(updated, now);
    }

    /**
     * 解析并校验收紧过滤器。
     *
     * @param request 过滤器原文
     * @param now 当前时间
     * @return 已规范化过滤器
     */
    private static AuditQueryFilter resolve(AuditFilterRequest request, Instant now) {
        return AuditQueryFilters.resolve(
                request.occurredFrom(),
                request.occurredTo(),
                request.action(),
                request.actorId(),
                request.targetType(),
                request.targetId(),
                request.outcome(),
                now);
    }

    /**
     * 平台租户自身导出在 M10 不支持（ADR-0020 F-023）。
     *
     * @param tenantId 当前租户
     */
    private static void requireTenantExportSupported(TenantId tenantId) {
        if (PlatformTenant.ID.equals(tenantId.value())) {
            throw new AuditForbiddenException("platform tenant export is not supported");
        }
    }

    /**
     * 读取导出请求；不存在或跨租户统一 404。
     *
     * @param tenantId 租户
     * @param exportId 导出请求 ID
     * @return 导出请求
     */
    private AuditExportRecord findOrThrow(TenantId tenantId, UUID exportId) {
        return auditExportRepository
                .findById(tenantId, exportId)
                .orElseThrow(() -> new NotFoundException("audit export"));
    }

    /**
     * 解析审批决定。
     *
     * @param decisionRaw 决定原文
     * @return 决定
     */
    private static AuditExportDecision parseDecision(String decisionRaw) {
        try {
            return AuditExportDecision.parse(decisionRaw);
        } catch (IllegalArgumentException exception) {
            throw new AuditInvalidRequestException("decision must be APPROVE or REJECT");
        }
    }

    /**
     * 校验收审批意见长度。
     *
     * @param comment 审批意见，可为空
     * @return 去空白后的意见
     */
    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new AuditInvalidRequestException("comment must not exceed " + MAX_COMMENT_LENGTH + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 解析状态过滤原文；{@code EXPIRED} 是派生过滤。
     *
     * @param statusRaw 状态原文，可为空
     * @return 状态；为空表示全部
     */
    private static AuditExportStatus parseStatus(String statusRaw) {
        if (statusRaw == null || statusRaw.isBlank()) {
            return null;
        }
        try {
            return AuditExportStatus.valueOf(statusRaw.strip());
        } catch (IllegalArgumentException exception) {
            throw new AuditInvalidRequestException("status is not supported");
        }
    }

    /**
     * 把非空过滤条件写入审计 details；值不超过 200 字符且只写 ID/枚举/哈希（ADR-0020 F-016）。
     *
     * @param details 目标 details
     * @param filter 过滤器
     */
    private static void putFilterDetails(Map<String, Object> details, AuditQueryFilter filter) {
        details.put("occurredFrom", TIME_FORMAT.format(filter.occurredFrom()));
        details.put("occurredTo", TIME_FORMAT.format(filter.occurredTo()));
        if (filter.action() != null) {
            details.put("action", filter.action());
        }
        if (filter.actorId() != null) {
            details.put("actorId", filter.actorId().toString());
        }
        if (filter.targetType() != null) {
            details.put("targetType", filter.targetType().name());
        }
        if (filter.targetId() != null) {
            details.put("targetId", filter.targetId());
        }
        if (filter.outcome() != null) {
            details.put("outcome", filter.outcome().name());
        }
    }

    /**
     * 追加审计；上下文必须已切换到审计事件所属租户。
     *
     * @param auditTenantId 审计事件租户
     * @param actor 操作者
     * @param meta 请求元数据
     * @param action 动作
     * @param targetId 目标 ID
     * @param details 白名单 details
     */
    private void appendAudit(
            String auditTenantId,
            RicconPrincipal actor,
            RequestMeta meta,
            String action,
            String targetId,
            Map<String, Object> details) {
        TenantContext.runWithTenant(
                TenantId.of(auditTenantId),
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        auditTenantId,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        action,
                        AuditTargetType.AUDIT_EXPORT,
                        targetId,
                        AuditOutcome.SUCCESS,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        details)));
    }

    /**
     * 映射导出请求为响应视图。
     *
     * @param record 导出请求记录
     * @param now 当前时间
     * @return 导出请求视图
     */
    private static AuditExportView toView(AuditExportRecord record, Instant now) {
        return new AuditExportView(
                record.id(),
                record.effectiveStatus(now),
                record.requestedBy(),
                record.requestedByDisplayName(),
                new AuditFilterView(
                        record.filter().occurredFrom(),
                        record.filter().occurredTo(),
                        record.filter().action(),
                        record.filter().actorId(),
                        record.filter().targetType(),
                        record.filter().targetId(),
                        record.filter().outcome()),
                record.estimatedRowCount(),
                record.approvedBy(),
                record.approvedByDisplayName(),
                record.decidedAt() == null ? null : record.decidedAt().atOffset(AuditQueryFilters.TEAM_OFFSET),
                record.decisionComment(),
                record.expiresAt() == null ? null : record.expiresAt().atOffset(AuditQueryFilters.TEAM_OFFSET),
                record.createdAt().atOffset(AuditQueryFilters.TEAM_OFFSET));
    }

    /**
     * 映射事件记录为 CSV 行视图，并执行 details 读取白名单。
     *
     * @param record 事件记录
     * @return 事件视图
     */
    private AuditEventView toEventView(AuditEventRecord record) {
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
