package com.riccon.api.research.service;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.chat.domain.CitationRegion;
import com.riccon.api.chat.domain.CitationSourceState;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.chat.repository.CitationRegionRepository;
import com.riccon.api.chat.repository.CitationStatusRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.quota.service.QuotaAdmissionService;
import com.riccon.api.research.domain.ApprovalSnapshot;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import com.riccon.api.research.domain.McpInvocationSnapshot;
import com.riccon.api.research.domain.ResearchTaskSnapshot;
import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.api.research.dto.ArtifactCitationResponse;
import com.riccon.api.research.dto.ArtifactDetailResponse;
import com.riccon.api.research.dto.CitationResponse;
import com.riccon.api.research.dto.ResearchTaskCreateRequest;
import com.riccon.api.research.dto.ResearchTaskDetailResponse;
import com.riccon.api.research.dto.ResearchTaskPageResponse;
import com.riccon.api.research.dto.ResearchTaskResponse;
import com.riccon.api.research.dto.RunStageDetails;
import com.riccon.api.research.dto.RunStageResponse;
import com.riccon.api.research.repository.ApprovalRepository;
import com.riccon.api.research.repository.ArtifactRepository;
import com.riccon.api.research.repository.McpInvocationRepository;
import com.riccon.api.research.repository.ResearchTaskRepository;
import com.riccon.api.research.repository.RunStageRepository;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.platform.domain.research.RunStage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告任务创建、列表与详情。
 *
 * <p>创建在同一个事务内写 {@code research_task}、{@code agent_run}、outbox 事件与审计：任一步失败都不产生
 * “已受理”状态（ADR-0008 第 7 节）。Worker 只推进运行；任务状态与阶段在读取前由当前运行投影，避免列表
 * 永远显示 PENDING。租户与工作空间可见性统一 404，不区分“不存在”和“属于其他租户”。
 */
@Service
public class ResearchTaskService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ResearchTaskService.class);

    /** 主题最小长度，与 OpenAPI 一致。 */
    private static final int MIN_TOPIC_LENGTH = 4;

    /** 主题最大长度，与 V4 列定义一致。 */
    private static final int MAX_TOPIC_LENGTH = 500;

    /** 首个执行阶段；M02 创建时固定从检索开始。 */
    private static final RunStage FIRST_STAGE = RunStage.RETRIEVAL;

    /** 报告任务仓储。 */
    private final ResearchTaskRepository researchTaskRepository;

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** Outbox 事件仓储。 */
    private final OutboxRepository outboxRepository;

    /** 阶段时间线仓储。 */
    private final RunStageRepository runStageRepository;

    /** 审批仓储。 */
    private final ApprovalRepository approvalRepository;

    /** MCP 调用仓储。 */
    private final McpInvocationRepository mcpInvocationRepository;

    /** 产物仓储。 */
    private final ArtifactRepository artifactRepository;

    /** 引用源文档状态。 */
    private final CitationStatusRepository citationStatusRepository;

    /** 引用分块 OCR 元数据。 */
    private final CitationRegionRepository citationRegionRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /** 配额准入；与任务/运行创建同事务。 */
    private final QuotaAdmissionService quotaAdmissionService;

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建报告任务服务。
     *
     * @param researchTaskRepository 报告任务仓储
     * @param runRepository 运行仓储
     * @param outboxRepository outbox 仓储
     * @param runStageRepository 阶段时间线仓储
     * @param approvalRepository 审批仓储
     * @param mcpInvocationRepository MCP 调用仓储
     * @param artifactRepository 产物仓储
     * @param citationStatusRepository 引用源文档状态
     * @param citationRegionRepository 引用分块 OCR 元数据
     * @param workspaceService 工作空间可见性
     * @param quotaAdmissionService 配额准入
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ResearchTaskService(
            ResearchTaskRepository researchTaskRepository,
            RunRepository runRepository,
            OutboxRepository outboxRepository,
            RunStageRepository runStageRepository,
            ApprovalRepository approvalRepository,
            McpInvocationRepository mcpInvocationRepository,
            ArtifactRepository artifactRepository,
            CitationStatusRepository citationStatusRepository,
            CitationRegionRepository citationRegionRepository,
            WorkspaceService workspaceService,
            QuotaAdmissionService quotaAdmissionService,
            AuditService auditService,
            Clock clock) {
        this.researchTaskRepository = Objects.requireNonNull(researchTaskRepository, "researchTaskRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.runStageRepository = Objects.requireNonNull(runStageRepository, "runStageRepository");
        this.approvalRepository = Objects.requireNonNull(approvalRepository, "approvalRepository");
        this.mcpInvocationRepository = Objects.requireNonNull(mcpInvocationRepository, "mcpInvocationRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.citationStatusRepository = Objects.requireNonNull(citationStatusRepository, "citationStatusRepository");
        this.citationRegionRepository = Objects.requireNonNull(citationRegionRepository, "citationRegionRepository");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.quotaAdmissionService = Objects.requireNonNull(quotaAdmissionService, "quotaAdmissionService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建报告任务并入队。
     *
     * <p>幂等键与创建人绑定：同一用户重复提交同一键且主题/工具一致时返回既有任务；内容不同返回 409。
     * 预先用 {@code SELECT ... FOR UPDATE} 做当前读，避免并发重复提交在快照读下看不到已提交任务。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param request 创建请求
     * @param idempotencyKey 幂等键
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已创建或既有的任务
     */
    @Transactional
    public ResearchTaskResponse create(
            RicconPrincipal actor,
            UUID workspaceId,
            ResearchTaskCreateRequest request,
            String idempotencyKey,
            String traceId,
            String requestId) {
        Validation.requireBody(request);
        String topic = Validation.requireText(request.topic(), "topic", MIN_TOPIC_LENGTH, MAX_TOPIC_LENGTH);
        UUID mcpToolId = Validation.requireNonNull(request.mcpToolId(), "mcpToolId");

        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        // 在锁定幂等记录/插入任务之前先取租户准入串行锁，保证 run 创建事务的加锁顺序一致。
        quotaAdmissionService.lockTenant(tenantId);

        // 先查幂等记录：重复提交应返回既有任务，即使该工具随后被停用，也不改变已受理结果。
        Optional<ResearchTaskSnapshot> existing =
                researchTaskRepository.findByIdempotencyKeyForUpdate(tenantId, actor.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(tenantId, actor, existing.get(), topic, mcpToolId);
        }
        if (!researchTaskRepository.isToolAuthorized(tenantId, mcpToolId)) {
            throw new BadRequestException("mcpToolId is not authorized for this tenant");
        }

        // 幂等命中已在前面返回，此处只对新任务准入一次；计数与任务/运行/outbox 同事务回滚（ADR-0022 §1.4）。
        quotaAdmissionService.admit(tenantId, actor.userId());

        Instant now = clock.instant();
        UUID taskId = UuidV7.generate(now);
        UUID runId = UuidV7.generate(now);
        UUID eventId = UuidV7.generate(now);
        ResearchTaskSnapshot task = new ResearchTaskSnapshot(
                taskId,
                workspaceId,
                actor.userId(),
                topic,
                ResearchTaskStatus.PENDING,
                null,
                mcpToolId,
                runId,
                null,
                null,
                now,
                now,
                null,
                null);
        try {
            researchTaskRepository.insert(tenantId, task, idempotencyKey);
        } catch (DuplicateKeyException exception) {
            // 并发同键请求：唯一键是最后守卫；重新当前读，返回先到者或报告内容冲突。
            ResearchTaskSnapshot winner = researchTaskRepository
                    .findByIdempotencyKeyForUpdate(tenantId, actor.userId(), idempotencyKey)
                    .orElseThrow(() -> new ConflictException("idempotency key is being processed concurrently"));
            return replayOrConflict(tenantId, actor, winner, topic, mcpToolId);
        }
        runRepository.createResearchPending(tenantId, runId, taskId, actor.userId(), traceId, now);
        outboxRepository.enqueue(
                tenantId,
                new OutboxEvent(
                        eventId,
                        runId,
                        EventTypes.AGENT_RUN_REQUESTED,
                        1,
                        "research-task:" + taskId + ":requested:v1",
                        requestedPayload(runId, taskId),
                        now));
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                AdditionalAuditActions.RESEARCH_TASK_CREATED,
                AuditTargetType.RESEARCH_TASK,
                taskId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                auditDetails(taskId, workspaceId, runId, mcpToolId)));
        LOG.info(
                "research.task.created tenantId={} taskId={} runId={} workspaceId={} toolId={}",
                actor.tenantId(),
                taskId,
                runId,
                workspaceId,
                mcpToolId);
        return ResearchTaskResponse.from(task);
    }

    /**
     * 列出工作空间报告任务。
     *
     * <p>先把当前运行状态投影回任务行，再按投影后的状态过滤与分页，保证 status 查询参数与响应一致。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param status 可选状态过滤
     * @param page 分页参数
     * @return 任务分页
     */
    @Transactional
    public ResearchTaskPageResponse list(
            RicconPrincipal actor, UUID workspaceId, ResearchTaskStatus status, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        researchTaskRepository.syncWorkspace(tenantId, workspaceId);
        long total = researchTaskRepository.count(tenantId, workspaceId, status);
        List<ResearchTaskResponse> items =
                researchTaskRepository.list(tenantId, workspaceId, status, page.size(), page.offset()).stream()
                        .map(ResearchTaskResponse::from)
                        .toList();
        return new ResearchTaskPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取任务详情与时间线。
     *
     * @param actor 当前员工
     * @param taskId 任务 ID
     * @return 任务详情
     */
    @Transactional
    public ResearchTaskDetailResponse get(RicconPrincipal actor, UUID taskId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ResearchTaskSnapshot task = researchTaskRepository
                .findById(tenantId, taskId)
                .orElseThrow(() -> new NotFoundException("research task"));
        workspaceService.requireAccessible(tenantId, task.workspaceId(), actor.userId());
        researchTaskRepository.syncTask(tenantId, task.workspaceId(), task.id());
        task = researchTaskRepository
                .findById(tenantId, taskId)
                .orElseThrow(() -> new NotFoundException("research task"));

        UUID runId = task.currentRunId();
        List<RunStageSnapshot> stages = runId == null ? List.of() : runStageRepository.listByRun(tenantId, runId);
        List<ApprovalSnapshot> approvals = approvalRepository.listByTask(tenantId, task.id());
        List<McpInvocationSnapshot> invocations = mcpInvocationRepository.listByTask(tenantId, task.id());
        List<RunStageResponse> stageResponses = stageResponses(tenantId, actor.userId(), stages);
        ArtifactDetailResponse artifact = artifactDetail(tenantId, task.id(), actor.userId());
        List<CitationResponse> sources = resolveSources(stageResponses, artifact);
        return ResearchTaskDetailResponse.of(task, stageResponses, approvals, invocations, sources, artifact);
    }

    /**
     * 幂等重放：主题与工具一致返回既有任务，否则冲突。
     *
     * <p>返回前把任务状态与当前运行同步，保证重放响应与正常读取看到同一状态，而不是创建时的 PENDING 快照。
     * 同时按既有任务所属工作空间重新判定访问，避免成员被移出部门后通过任意可访问空间重放旧幂等键读取任务。
     *
     * @param tenantId 当前租户
     * @param actor 当前员工
     * @param existing 既有任务
     * @param topic 本次主题
     * @param mcpToolId 本次工具
     * @return 既有任务响应
     */
    private ResearchTaskResponse replayOrConflict(
            TenantId tenantId, RicconPrincipal actor, ResearchTaskSnapshot existing, String topic, UUID mcpToolId) {
        if (!existing.topic().equals(topic) || !existing.mcpToolId().equals(mcpToolId)) {
            throw new ConflictException("idempotency key was already used with a different request");
        }
        workspaceService.requireAccessible(tenantId, existing.workspaceId(), actor.userId());
        researchTaskRepository.syncTask(tenantId, existing.workspaceId(), existing.id());
        ResearchTaskSnapshot refreshed = researchTaskRepository
                .findById(tenantId, existing.id())
                .orElseThrow(() -> new NotFoundException("research task"));
        return ResearchTaskResponse.from(refreshed);
    }

    /**
     * 解析任务级来源清单。
     *
     * <p>产物生成后以 {@code artifact_citation} 为权威快照；产物尚未生成或没有引用时，使用已按当前权限遮蔽的
     * 检索阶段 {@code details.sources}，保证 J2 在等待审批时就能展示命中标题、定位与片段（M02-AC-07）。
     * 两条路径都映射到同一 {@link CitationResponse}，前端无需区分数据来源。
     *
     * @param stages 已计算可用性的阶段时间线
     * @param artifact 产物详情；不存在时为 {@code null}
     * @return 来源清单；没有命中时为空列表
     */
    private static List<CitationResponse> resolveSources(
            List<RunStageResponse> stages, ArtifactDetailResponse artifact) {
        if (artifact != null && artifact.currentVersionDetail() != null) {
            List<ArtifactCitationResponse> citations =
                    artifact.currentVersionDetail().citations();
            if (!citations.isEmpty()) {
                return citations.stream().map(CitationResponse::from).toList();
            }
        }
        List<CitationResponse> latest = List.of();
        for (RunStageResponse stage : stages) {
            if (stage.stage() != RunStage.RETRIEVAL || stage.details() == null) {
                continue;
            }
            List<CitationResponse> sources = stage.details().sources();
            if (sources != null && !sources.isEmpty()) {
                latest = sources;
            }
        }
        return latest;
    }

    /**
     * 构造阶段时间线，并对每个阶段的检索来源应用同一遮蔽规则。
     *
     * <p>先跨阶段收集文档 ID 一次批量读取状态，避免逐阶段、逐条查询；阶段详情原始 JSON 不改写，只在响应映射时
     * 遮蔽（ADR-0023 第 5.4 节）。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param stages 阶段快照
     * @return 阶段响应
     */
    private List<RunStageResponse> stageResponses(TenantId tenantId, UUID viewerId, List<RunStageSnapshot> stages) {
        if (stages.isEmpty()) {
            return List.of();
        }
        Set<UUID> documentIds = new LinkedHashSet<>();
        Set<UUID> chunkIds = new LinkedHashSet<>();
        for (RunStageSnapshot stage : stages) {
            RunStageDetails details = RunStageDetails.from(stage.details());
            if (details == null || details.sources() == null) {
                continue;
            }
            for (CitationResponse source : details.sources()) {
                if (source.sourceType() == ArtifactSourceType.KNOWLEDGE && source.knowledgeDocumentId() != null) {
                    documentIds.add(source.knowledgeDocumentId());
                }
                if (source.chunkId() != null) {
                    chunkIds.add(source.chunkId());
                }
            }
        }
        Map<UUID, CitationSourceState> states = citationStatusRepository.findStates(tenantId, viewerId, documentIds);
        Map<UUID, CitationRegion> regions = citationRegionRepository.findRegions(tenantId, chunkIds);
        return stages.stream()
                .map(stage -> RunStageResponse.from(stage, source -> withStageStatus(source, states, regions)))
                .toList();
    }

    /**
     * 计算单个检索来源的可用性；检索阶段没有版本快照，不会出现 {@code UPDATED}。
     *
     * @param source 来源
     * @param states 文档状态映射
     * @param regions 分块 OCR 元数据
     * @return 带可用性、遮蔽与区域的来源
     */
    private static CitationResponse withStageStatus(
            CitationResponse source, Map<UUID, CitationSourceState> states, Map<UUID, CitationRegion> regions) {
        CitationSourceStatus status = CitationSourceStatus.AVAILABLE;
        if (source.sourceType() == ArtifactSourceType.KNOWLEDGE && source.knowledgeDocumentId() != null) {
            CitationSourceState state = states.get(source.knowledgeDocumentId());
            status = state == null ? CitationSourceStatus.NO_ACCESS : state.statusFor(null);
        }
        CitationRegion region = source.chunkId() == null ? null : regions.get(source.chunkId());
        return source.withSourceStatus(status).withRegion(region);
    }

    /**
     * 读取任务产物详情；没有产物时返回 {@code null}。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 产物详情
     */
    private ArtifactDetailResponse artifactDetail(TenantId tenantId, UUID taskId, UUID viewerId) {
        Optional<ArtifactSnapshot> artifact = artifactRepository.findByTask(tenantId, taskId);
        if (artifact.isEmpty()) {
            return null;
        }
        ArtifactSnapshot snapshot = artifact.get();
        ArtifactVersionSnapshot version = null;
        if (snapshot.currentVersion() >= 1) {
            version = artifactRepository
                    .findVersion(tenantId, snapshot.id(), snapshot.currentVersion())
                    .map(item -> withCitations(tenantId, viewerId, item))
                    .orElse(null);
        }
        return ArtifactDetailResponse.from(snapshot, version);
    }

    /**
     * 为单个版本补齐引用清单。
     *
     * @param tenantId 当前租户
     * @param version 版本快照
     * @return 带引用的版本快照
     */
    private ArtifactVersionSnapshot withCitations(TenantId tenantId, UUID viewerId, ArtifactVersionSnapshot version) {
        List<ArtifactCitationSnapshot> citations = artifactRepository
                .citationsByVersionIds(tenantId, List.of(version.id()))
                .getOrDefault(version.id(), List.of());
        return new ArtifactVersionSnapshot(
                version.id(),
                version.artifactId(),
                version.version(),
                version.storageKey(),
                version.contentType(),
                version.sizeBytes(),
                version.sha256(),
                version.summary(),
                version.createdAt(),
                withCitationStatus(tenantId, viewerId, citations));
    }

    /**
     * 批量计算产物引用可用性。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param citations 引用快照
     * @return 带可用性的引用
     */
    private List<ArtifactCitationSnapshot> withCitationStatus(
            TenantId tenantId, UUID viewerId, List<ArtifactCitationSnapshot> citations) {
        if (citations.isEmpty()) {
            return List.of();
        }
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (ArtifactCitationSnapshot citation : citations) {
            if (citation.sourceType() == ArtifactSourceType.KNOWLEDGE && citation.knowledgeDocumentId() != null) {
                documentIds.add(citation.knowledgeDocumentId());
            }
        }
        Map<UUID, CitationSourceState> states = citationStatusRepository.findStates(tenantId, viewerId, documentIds);
        Set<UUID> chunkIds = new LinkedHashSet<>();
        for (ArtifactCitationSnapshot citation : citations) {
            if (citation.chunkId() != null) {
                chunkIds.add(citation.chunkId());
            }
        }
        Map<UUID, CitationRegion> regions = citationRegionRepository.findRegions(tenantId, chunkIds);
        List<ArtifactCitationSnapshot> resolved = new ArrayList<>(citations.size());
        for (ArtifactCitationSnapshot citation : citations) {
            CitationSourceStatus status = CitationSourceStatus.AVAILABLE;
            if (citation.sourceType() == ArtifactSourceType.KNOWLEDGE && citation.knowledgeDocumentId() != null) {
                CitationSourceState state = states.get(citation.knowledgeDocumentId());
                status = state == null ? CitationSourceStatus.NO_ACCESS : state.statusFor(citation.sourceVersion());
            }
            CitationRegion region = citation.chunkId() == null ? null : regions.get(citation.chunkId());
            resolved.add(citation.withSourceStatus(status).withRegion(region));
        }
        return List.copyOf(resolved);
    }

    /**
     * 构造创建事件 payload；字段与 {@code agent-run-requested.v1} schema 逐字段一致，禁止新增键。
     *
     * @param runId 运行 ID
     * @param taskId 报告任务 ID
     * @return payload
     */
    private static Map<String, Object> requestedPayload(UUID runId, UUID taskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId.toString());
        payload.put("researchTaskId", taskId.toString());
        payload.put("stage", FIRST_STAGE.name());
        payload.put("attempt", 1);
        return payload;
    }

    /**
     * 构造创建审计 details；只放 ID，不含主题原文。
     *
     * @param taskId 任务 ID
     * @param workspaceId 工作空间 ID
     * @param runId 运行 ID
     * @param mcpToolId 工具 ID
     * @return details
     */
    private static Map<String, Object> auditDetails(UUID taskId, UUID workspaceId, UUID runId, UUID mcpToolId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("researchTaskId", taskId.toString());
        details.put("workspaceId", workspaceId.toString());
        details.put("runId", runId.toString());
        details.put("mcpToolId", mcpToolId.toString());
        return details;
    }
}
