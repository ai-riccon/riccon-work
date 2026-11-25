package com.riccon.api.workflow.history.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.chat.domain.CitationSourceState;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.chat.repository.CitationStatusRepository;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.workflow.history.domain.WorkflowApprovalCounts;
import com.riccon.api.workflow.history.domain.WorkflowCitationSnapshot;
import com.riccon.api.workflow.history.domain.WorkflowHistoryActor;
import com.riccon.api.workflow.history.domain.WorkflowHistoryFilter;
import com.riccon.api.workflow.history.domain.WorkflowResultFormat;
import com.riccon.api.workflow.history.domain.WorkflowRunApprovalSnapshot;
import com.riccon.api.workflow.history.domain.WorkflowRunHistorySnapshot;
import com.riccon.api.workflow.history.domain.WorkflowRunNodeSnapshot;
import com.riccon.api.workflow.history.dto.WorkflowCitationResponse;
import com.riccon.api.workflow.history.dto.WorkflowDataScopeSummaryResponse;
import com.riccon.api.workflow.history.dto.WorkflowNodeRunResponse;
import com.riccon.api.workflow.history.dto.WorkflowResultResponse;
import com.riccon.api.workflow.history.dto.WorkflowRunApprovalResponse;
import com.riccon.api.workflow.history.dto.WorkflowRunDetailResponse;
import com.riccon.api.workflow.history.dto.WorkflowRunHistoryItemResponse;
import com.riccon.api.workflow.history.dto.WorkflowRunHistoryPageResponse;
import com.riccon.api.workflow.history.repository.WorkflowHistoryRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行历史与详情服务（ADR-0024 §3.3.3/§6.2/§7.4，AC-12/13/14/15/16）。
 *
 * <p>可见性：租户管理员看全部运行；其他主体只看自己发起的运行与自己是流程所有者的运行元数据。
 * 详情仅发起人与租户管理员可读；流程所有者只看列表元数据，不获得他人运行结果。跨租户、平台租户与
 * 到期运行统一 404（ADR-0024 §6.2，AC-14/15）。
 *
 * <p>管理性读取：租户管理员每次列表写一条 {@code workflow.run.admin_read scope=LIST}；详情仅在
 * 发起人不是管理员本人时写 {@code scope=DETAIL}。审计与读取在同一事务内，写失败即请求失败
 * （ADR-0024 §8.1/§8.2）。
 *
 * <p>内容最小化：列表不返回结果正文；详情只返回节点摘要、审批记录、聚合引用与最终结果，不返回
 * 完整输入、提示词、工具参数、凭据或节点 {@code output_json}。引用按当前账号 ACL 重新计算
 * {@code sourceStatus} 并在不可访问时遮蔽标题/片段/定位（AC-13）。
 */
@Service
public class WorkflowHistoryService {

    /** 运行不可见时的资源名。 */
    private static final String RUN_RESOURCE = "workflow run";

    /** 脱敏参数摘要允许的最大顶层键数，与 V15 与 T09 审批响应一致。 */
    private static final int MAX_PREVIEW_KEYS = 16;

    /** 对外时间偏移（Asia/Shanghai，UTC+08:00）。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 运行历史仓储。 */
    private final WorkflowHistoryRepository repository;

    /** 审计写入（同事务 fail-closed）。 */
    private final AuditService auditService;

    /** 引用源文档 ACL/软删除状态批量查询。 */
    private final CitationStatusRepository citationStatusRepository;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /** 可注入 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建运行历史服务。
     *
     * @param repository 运行历史仓储
     * @param auditService 审计写入
     * @param citationStatusRepository 引用状态批量查询
     * @param objectMapper JSON 编解码
     * @param clock 可注入时钟
     */
    public WorkflowHistoryService(
            WorkflowHistoryRepository repository,
            AuditService auditService,
            CitationStatusRepository citationStatusRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.citationStatusRepository =
                Objects.requireNonNull(citationStatusRepository, "citationStatusRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取运行历史。
     *
     * <p>按开始时间倒序；{@code approvalStatus} 与三个计数由一次审批聚合批量填充，列表查询不读取
     * 最终结果与输入原文。到期运行在 SQL 层过滤（AC-12/14）。
     *
     * @param actor 当前主体
     * @param filter 过滤条件
     * @param page 分页参数
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 运行分页
     */
    @Transactional
    public WorkflowRunHistoryPageResponse list(
            WorkflowHistoryActor actor,
            WorkflowHistoryFilter filter,
            PageQuery page,
            String traceId,
            String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        long total = repository.countRuns(tenantId, actor, filter, now);
        List<WorkflowRunHistoryItemResponse> items;
        if (total == 0) {
            items = List.of();
        } else {
            List<WorkflowRunHistorySnapshot> runs =
                    repository.findRuns(tenantId, actor, filter, now, page.size(), page.offset());
            List<UUID> runIds =
                    runs.stream().map(WorkflowRunHistorySnapshot::id).toList();
            Map<UUID, WorkflowApprovalCounts> counts = repository.findApprovalCounts(tenantId, runIds);
            items = new ArrayList<>(runs.size());
            for (WorkflowRunHistorySnapshot run : runs) {
                items.add(item(run, counts.getOrDefault(run.id(), WorkflowApprovalCounts.EMPTY)));
            }
        }
        if (actor.tenantAdmin()) {
            // 管理员每次列表都写一条管理性读取审计；不因结果为空而跳过（ADR-0024 §8.2）。
            auditService.append(WorkflowHistoryAuditEvents.adminListRead(
                    now, actor.tenantId(), actor.userId(), traceId, requestId));
        }
        return new WorkflowRunHistoryPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取运行详情。
     *
     * <p>仅发起人与租户管理员可见；流程所有者、其他部门成员、跨租户与到期运行统一 404。详情返回
     * 节点全部尝试、审批记录、聚合引用与最终结果；{@code paramsPreview} 只对当前账号被指派的审批返回。
     *
     * @param actor 当前主体
     * @param runId 运行 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 运行详情
     * @throws NotFoundException 运行不存在、已到期或对当前主体不可见
     */
    @Transactional
    public WorkflowRunDetailResponse detail(WorkflowHistoryActor actor, UUID runId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        WorkflowRunHistorySnapshot run =
                repository.findRunDetail(tenantId, runId, now).orElseThrow(() -> new NotFoundException(RUN_RESOURCE));
        requireDetailVisible(actor, run);

        List<WorkflowRunNodeSnapshot> nodes = repository.findNodeRuns(tenantId, runId);
        List<WorkflowRunApprovalSnapshot> approvals = repository.findApprovals(tenantId, runId);
        WorkflowApprovalCounts counts = repository
                .findApprovalCounts(tenantId, List.of(runId))
                .getOrDefault(runId, WorkflowApprovalCounts.EMPTY);

        // 引用只解析一次：节点原始快照同时服务详情聚合与审批 dataScopeSummary 计数。
        List<NodeCitation> nodeCitations = parseCitations(nodes);
        List<WorkflowCitationResponse> citations = aggregateCitations(tenantId, actor.userId(), nodeCitations);
        Set<UUID> assigned = repository.findAssignedApprovalIds(
                tenantId,
                actor.userId(),
                approvals.stream().map(WorkflowRunApprovalSnapshot::id).toList());
        List<WorkflowRunApprovalResponse> approvalResponses = new ArrayList<>(approvals.size());
        for (WorkflowRunApprovalSnapshot approval : approvals) {
            int documentReferences = documentReferenceCount(nodeCitations, approval.createdAt());
            boolean showPreview =
                    assigned.contains(approval.id()) && !approval.requestedBy().equals(actor.userId());
            approvalResponses.add(approvalResponse(run, approval, documentReferences, showPreview));
        }

        if (actor.tenantAdmin() && !run.requesterUserId().equals(actor.userId())) {
            auditService.append(WorkflowHistoryAuditEvents.adminDetailRead(
                    now, actor.tenantId(), actor.userId(), runId, traceId, requestId));
        }
        return detailResponse(run, nodes, approvalResponses, citations, counts);
    }

    /**
     * 校验详情可见性：发起人或租户管理员；流程所有者只拥有列表元数据，不获得详情。
     *
     * @param actor 当前主体
     * @param run 运行快照
     * @throws NotFoundException 不可见
     */
    private static void requireDetailVisible(WorkflowHistoryActor actor, WorkflowRunHistorySnapshot run) {
        if (!run.requesterUserId().equals(actor.userId()) && !actor.tenantAdmin()) {
            throw new NotFoundException(RUN_RESOURCE);
        }
    }

    /**
     * 构造列表条目。
     *
     * @param run 运行快照
     * @param counts 审批计数
     * @return 列表条目
     */
    private static WorkflowRunHistoryItemResponse item(WorkflowRunHistorySnapshot run, WorkflowApprovalCounts counts) {
        return new WorkflowRunHistoryItemResponse(
                run.id(),
                run.workflowId(),
                run.workflowName(),
                run.workflowVersionId(),
                run.versionNo(),
                run.requesterUserId(),
                run.status(),
                run.currentNodeKey(),
                approvalStatus(counts),
                run.errorCode(),
                run.errorMessage(),
                run.resultStatus(),
                display(run.createdAt()),
                display(run.startedAt()),
                display(run.finishedAt()),
                run.executionMs(),
                display(run.updatedAt()),
                counts.pending(),
                counts.approved(),
                counts.rejected(),
                display(run.retentionExpiresAt()));
    }

    /**
     * 构造详情响应。
     *
     * @param run 运行快照
     * @param nodes 节点尝试
     * @param approvals 审批响应
     * @param citations 聚合引用
     * @param counts 审批计数
     * @return 详情响应
     */
    private WorkflowRunDetailResponse detailResponse(
            WorkflowRunHistorySnapshot run,
            List<WorkflowRunNodeSnapshot> nodes,
            List<WorkflowRunApprovalResponse> approvals,
            List<WorkflowCitationResponse> citations,
            WorkflowApprovalCounts counts) {
        return new WorkflowRunDetailResponse(
                run.id(),
                run.workflowId(),
                run.workflowName(),
                run.workflowVersionId(),
                run.versionNo(),
                run.requesterUserId(),
                run.status(),
                run.currentNodeKey(),
                approvalStatus(counts),
                run.errorCode(),
                run.errorMessage(),
                run.resultStatus(),
                display(run.createdAt()),
                display(run.startedAt()),
                display(run.finishedAt()),
                run.executionMs(),
                display(run.updatedAt()),
                counts.pending(),
                counts.approved(),
                counts.rejected(),
                display(run.retentionExpiresAt()),
                nodes.stream().map(WorkflowHistoryService::nodeResponse).toList(),
                approvals,
                citations,
                result(run));
    }

    /**
     * 把审批计数聚合为 {@code approvalStatus}。
     *
     * <p>任一 PENDING → PENDING；否则任一 REJECTED → REJECTED；否则任一 APPROVED → APPROVED；否则空。
     *
     * @param counts 计数
     * @return 聚合状态或 {@code null}
     */
    private static WorkflowApprovalStatus approvalStatus(WorkflowApprovalCounts counts) {
        if (counts.pending() > 0) {
            return WorkflowApprovalStatus.PENDING;
        }
        if (counts.rejected() > 0) {
            return WorkflowApprovalStatus.REJECTED;
        }
        if (counts.approved() > 0) {
            return WorkflowApprovalStatus.APPROVED;
        }
        return null;
    }

    /**
     * 构造节点时间线响应。
     *
     * @param node 节点快照
     * @return 节点响应
     */
    private static WorkflowNodeRunResponse nodeResponse(WorkflowRunNodeSnapshot node) {
        return new WorkflowNodeRunResponse(
                node.id(),
                node.nodeKey(),
                node.nodeType(),
                node.nodeName(),
                node.status(),
                node.attempt(),
                display(node.startedAt()),
                display(node.finishedAt()),
                node.durationMs(),
                node.inputSummary(),
                node.outputSummary(),
                node.errorCode(),
                node.errorMessage(),
                node.approvalId(),
                node.invocationId());
    }

    /**
     * 构造审批记录响应；仅 assignee 且非发起人时返回脱敏参数摘要。
     *
     * @param run 运行快照
     * @param approval 审批快照
     * @param documentReferences 去重引用数
     * @param showPreview 是否返回参数摘要
     * @return 审批响应
     */
    private WorkflowRunApprovalResponse approvalResponse(
            WorkflowRunHistorySnapshot run,
            WorkflowRunApprovalSnapshot approval,
            int documentReferences,
            boolean showPreview) {
        return new WorkflowRunApprovalResponse(
                approval.id(),
                approval.workflowRunId(),
                approval.workflowId(),
                run.workflowName(),
                approval.nodeKey(),
                approval.nodeName(),
                approval.status(),
                approval.assigneeType(),
                approval.assigneeRole(),
                approval.actionKind(),
                new WorkflowDataScopeSummaryResponse(approval.runScope(), approval.departmentId(), documentReferences),
                approval.targetToolName(),
                showPreview ? paramsPreview(approval) : null,
                approval.planSha256(),
                approval.requestedBy(),
                approval.decidedBy(),
                display(approval.decidedAt()),
                approval.decisionComment(),
                display(approval.createdAt()));
    }

    /**
     * 解析并校验脱敏参数摘要。
     *
     * @param approval 审批快照
     * @return 参数摘要对象
     */
    private JsonNode paramsPreview(WorkflowRunApprovalSnapshot approval) {
        JsonNode preview = readJson(approval.paramsPreview(), "workflow approval params preview");
        if (!preview.isObject() || preview.size() > MAX_PREVIEW_KEYS) {
            throw new IllegalStateException("workflow approval params preview is invalid");
        }
        return preview;
    }

    /**
     * 解析全部节点引用快照。
     *
     * @param nodes 节点尝试
     * @return 按节点顺序的引用集合
     */
    private List<NodeCitation> parseCitations(List<WorkflowRunNodeSnapshot> nodes) {
        List<NodeCitation> parsed = new ArrayList<>(nodes.size());
        for (WorkflowRunNodeSnapshot node : nodes) {
            if (node.citationsJson() == null || node.citationsJson().isBlank()) {
                continue;
            }
            JsonNode array = readJson(node.citationsJson(), "workflow citations snapshot");
            if (!array.isArray()) {
                throw new IllegalStateException("workflow citations snapshot is invalid");
            }
            List<WorkflowCitationSnapshot> citations = new ArrayList<>(array.size());
            for (JsonNode item : array) {
                citations.add(parseCitation(item));
            }
            parsed.add(new NodeCitation(node.finishedAt(), List.copyOf(citations)));
        }
        return parsed;
    }

    /**
     * 解析单条引用快照；未知枚举与非法 JSON fail-closed，不静默丢弃。
     *
     * @param item 引用 JSON
     * @return 引用快照
     */
    private static WorkflowCitationSnapshot parseCitation(JsonNode item) {
        if (!item.isObject()) {
            throw new IllegalStateException("workflow citation is invalid");
        }
        try {
            return new WorkflowCitationSnapshot(
                    item.path("ordinal").asInt(1),
                    CitationSourceType.valueOf(text(item, "sourceType")),
                    uuid(item, "fileId"),
                    uuid(item, "knowledgeDocumentId"),
                    uuid(item, "chunkId"),
                    locator(item),
                    number(item, "pageNo"),
                    optionalText(item, "sheetName"),
                    optionalText(item, "cellRange"),
                    number(item, "paragraphNo"),
                    optionalText(item, "title"),
                    optionalText(item, "snippet"),
                    item.path("score").isNumber() ? item.path("score").asDouble() : null,
                    optionalText(item, "sourceVersion"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("workflow citation is invalid", exception);
        }
    }

    /**
     * 聚合全部节点引用、去重并按当前账号计算 {@code sourceStatus}。
     *
     * <p>同一文档不同分块视为不同引用；去重保留时间线中首次出现的快照，重新连续编号供详情展示。
     * 不可访问/已删除的引用遮蔽标题、片段与定位，不返回正文（AC-13、TM-76）。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param nodeCitations 节点引用
     * @return 聚合引用
     */
    private List<WorkflowCitationResponse> aggregateCitations(
            TenantId tenantId, UUID viewerId, List<NodeCitation> nodeCitations) {
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (NodeCitation node : nodeCitations) {
            for (WorkflowCitationSnapshot citation : node.citations()) {
                if (citation.sourceType() == CitationSourceType.KNOWLEDGE && citation.knowledgeDocumentId() != null) {
                    documentIds.add(citation.knowledgeDocumentId());
                }
            }
        }
        Map<UUID, CitationSourceState> states = citationStatusRepository.findStates(tenantId, viewerId, documentIds);
        Set<String> seen = new LinkedHashSet<>();
        List<WorkflowCitationResponse> aggregated = new ArrayList<>();
        for (NodeCitation node : nodeCitations) {
            for (WorkflowCitationSnapshot citation : node.citations()) {
                if (!seen.add(citation.dedupeKey())) {
                    continue;
                }
                CitationSourceStatus status = CitationSourceStatus.AVAILABLE;
                if (citation.sourceType() == CitationSourceType.KNOWLEDGE && citation.knowledgeDocumentId() != null) {
                    CitationSourceState state = states.get(citation.knowledgeDocumentId());
                    status = state == null ? CitationSourceStatus.NO_ACCESS : state.statusFor(citation.sourceVersion());
                }
                aggregated.add(citationResponse(aggregated.size() + 1, citation, status));
            }
        }
        return List.copyOf(aggregated);
    }

    /**
     * 构造引用响应并应用遮蔽。
     *
     * @param ordinal 聚合序号
     * @param citation 引用快照
     * @param status 可用性
     * @return 引用响应
     */
    private static WorkflowCitationResponse citationResponse(
            int ordinal, WorkflowCitationSnapshot citation, CitationSourceStatus status) {
        boolean hidden = status == CitationSourceStatus.NO_ACCESS || status == CitationSourceStatus.DELETED;
        return new WorkflowCitationResponse(
                ordinal,
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                citation.chunkId(),
                hidden ? null : citation.locatorType(),
                hidden ? null : citation.pageNo(),
                hidden ? null : citation.sheetName(),
                hidden ? null : citation.cellRange(),
                hidden ? null : citation.paragraphNo(),
                null,
                null,
                status,
                hidden ? "" : citation.title(),
                hidden ? "" : citation.snippet(),
                citation.score());
    }

    /**
     * 计算审批创建前已执行节点的去重文档引用数。
     *
     * @param nodeCitations 节点引用
     * @param createdAt 审批创建时间
     * @return 去重引用数
     */
    private static int documentReferenceCount(List<NodeCitation> nodeCitations, Instant createdAt) {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeCitation node : nodeCitations) {
            if (node.finishedAt() == null || node.finishedAt().isAfter(createdAt)) {
                continue;
            }
            for (WorkflowCitationSnapshot citation : node.citations()) {
                String key = citation.documentKey();
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys.size();
    }

    /**
     * 解析最终结果；只在终态且 {@code resultStatus=SAVED} 时返回，超限运行不伪造结果。
     *
     * @param run 运行快照
     * @return 结果响应或 {@code null}
     */
    private WorkflowResultResponse result(WorkflowRunHistorySnapshot run) {
        if (!run.terminal()
                || run.resultStatus() != WorkflowResultStatus.SAVED
                || run.resultJson() == null
                || run.resultJson().isBlank()) {
            return null;
        }
        JsonNode node = readJson(run.resultJson(), "workflow run result");
        if (!node.isObject()) {
            throw new IllegalStateException("workflow run result is invalid");
        }
        WorkflowResultFormat format;
        try {
            format = WorkflowResultFormat.valueOf(text(node, "format"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("workflow run result format is invalid", exception);
        }
        JsonNode textNode = node.get("text");
        String text = textNode == null || textNode.isNull() ? null : textNode.asString();
        long sizeBytes =
                node.path("sizeBytes").isNumber() ? node.path("sizeBytes").asLong() : run.resultSizeBytes();
        OffsetDateTime generatedAt = generatedAt(node);
        return new WorkflowResultResponse(format, text, sizeBytes, generatedAt);
    }

    /**
     * 读取结果生成时间；缺失或格式非法时为空。
     *
     * @param node 结果 JSON
     * @return 带偏移时间或 {@code null}
     */
    private static OffsetDateTime generatedAt(JsonNode node) {
        String value = optionalText(node, "generatedAt");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("workflow run result generatedAt is invalid", exception);
        }
    }

    /**
     * 解析 JSON；损坏数据 fail-closed。
     *
     * @param json JSON 文本
     * @param label 排障标签
     * @return JSON 节点
     */
    private JsonNode readJson(String json, String label) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(label + " is missing");
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException(label + " is invalid", exception);
        }
    }

    /**
     * 读取必填文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本
     * @throws IllegalArgumentException 缺失或非文本
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("workflow citation field is required: " + field);
        }
        return value.asString();
    }

    /**
     * 读取可空文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本或 {@code null}
     */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asString();
    }

    /**
     * 读取可空 UUID 字段；非法值 fail-closed。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return UUID 或 {@code null}
     */
    private static UUID uuid(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    /**
     * 读取可空整数。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 整数或 {@code null}
     */
    private static Integer number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    /**
     * 读取定位类型。
     *
     * @param node 引用 JSON
     * @return 定位类型或 {@code null}
     */
    private static LocatorType locator(JsonNode node) {
        String value = optionalText(node, "locatorType");
        return value == null || value.isBlank() ? null : LocatorType.valueOf(value);
    }

    /**
     * 把 UTC 时间转换为带 +08:00 偏移的展示时间。
     *
     * @param instant UTC 时间；可空
     * @return 展示时间或 {@code null}
     */
    private static OffsetDateTime display(Instant instant) {
        return instant == null ? null : instant.atZone(DISPLAY_ZONE).toOffsetDateTime();
    }

    /**
     * 节点引用集合。
     *
     * @param finishedAt 节点完成时间；可空
     * @param citations 引用快照
     */
    private record NodeCitation(Instant finishedAt, List<WorkflowCitationSnapshot> citations) {}
}
