package com.riccon.api.workflow.approval.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.config.WorkflowProperties;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalActor;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalCitationSnapshot;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalDecisionCommand;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalSnapshot;
import com.riccon.api.workflow.approval.dto.WorkflowApprovalPageResponse;
import com.riccon.api.workflow.approval.dto.WorkflowApprovalResponse;
import com.riccon.api.workflow.approval.dto.WorkflowDataScopeSummaryResponse;
import com.riccon.api.workflow.approval.repository.WorkflowApprovalRepository;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphEdge;
import com.riccon.api.workflow.domain.WorkflowGraphFormatException;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.api.workflow.domain.WorkflowGraphNode;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
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
 * 审批待办、详情与决定服务（ADR-0024 第 5.2/5.3/5.4 节）。
 *
 * <p>可见性：待办只返回分配给当前用户、非本人发起、仍为 {@code PENDING} 的审批，按 {@code createdAt ASC}
 * 排序；详情只对 assignee 开放（未分配/发起人/跨租户统一 404），租户管理员不因管理员身份获得参数摘要。
 *
 * <p>决定状态机与 Worker T07a 保持同一语义：审批条件更新 {@code PENDING -> APPROVED/REJECTED}（0 行即重复/
 * 并发冲突）；审批节点 {@code WAITING_APPROVAL -> SUCCEEDED/FAILED}；批准把运行恢复为 {@code PENDING} 并从
 * {@code targetNodeKey} 继续；拒绝把运行置 {@code FAILED}（{@code WF_APPROVAL_REJECTED}）、下游 {@code SKIPPED}、
 * 不写最终结果。自批、范围外与停用账号 403 并写 {@code outcome=DENIED} 审计，运行保持等待。
 *
 * <p>所有决定写入与审计在同一事务内提交；审计失败使决定整体回滚（fail-closed，ADR-0007/§8.2）。编排关闭时
 * 决定返回 503，待办与详情保持只读（ADR-0024 §10.1）。
 */
@Service
public class WorkflowApprovalService {

    /** 审批不可见时的资源名。 */
    private static final String APPROVAL_RESOURCE = "workflow approval";

    /** 审批节点失败原因（拒绝），与 Worker T07a 文案一致。 */
    private static final String REJECTED_MESSAGE = "审批已拒绝，运行终止";

    /** 下游跳过原因。 */
    private static final String REJECTED_SKIP_SUMMARY = "审批拒绝，后续节点未执行";

    /** 审批通过摘要。 */
    private static final String APPROVED_SUMMARY = "审批通过";

    /** 拒绝时的审批节点摘要。 */
    private static final String REJECTED_NODE_SUMMARY = "审批已拒绝，后续节点未执行";

    /** 对外时间偏移（Asia/Shanghai，UTC+08:00）。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 脱敏参数摘要允许的最大顶层键数，与契约和 V15 列语义一致。 */
    private static final int MAX_PREVIEW_KEYS = 16;

    /** 审批仓储。 */
    private final WorkflowApprovalRepository repository;

    /** 审计写入（同事务 fail-closed）。 */
    private final AuditService auditService;

    /** 编排部署配置（kill switch）。 */
    private final WorkflowProperties properties;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /** 可注入 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建审批服务。
     *
     * @param repository 审批仓储
     * @param auditService 审计写入
     * @param properties 编排部署配置
     * @param objectMapper JSON 编解码
     * @param clock 可注入时钟
     */
    public WorkflowApprovalService(
            WorkflowApprovalRepository repository,
            AuditService auditService,
            WorkflowProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取当前用户的审批待办。
     *
     * <p>契约的 {@code status} 参数只用于表达“待办状态”；本端点按 ADR-0024 第 5.3 节固定只返回
     * {@code PENDING}。显式传入其他状态时返回空页，避免把已决定记录当作待办，也不把参数错误伪装成
     * 服务端 500。
     *
     * @param actor 当前主体
     * @param status 可选状态过滤
     * @param page 分页参数
     * @return 审批分页
     */
    @Transactional(readOnly = true)
    public WorkflowApprovalPageResponse list(
            WorkflowApprovalActor actor, WorkflowApprovalStatus status, PageQuery page) {
        requireApprovePermission(actor);
        if (status != null && status != WorkflowApprovalStatus.PENDING) {
            return new WorkflowApprovalPageResponse(page.page(), page.size(), 0, page.totalPages(0), List.of());
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        long total = repository.countInbox(tenantId, actor.userId(), now);
        List<WorkflowApprovalResponse> items;
        if (total == 0) {
            items = List.of();
        } else {
            List<WorkflowApprovalSnapshot> approvals =
                    repository.listInbox(tenantId, actor.userId(), now, page.size(), page.offset());
            // 一次批量读取本页涉及的运行引用，避免逐条审批查询造成 N+1（REV-TASK-0229-001 F-003）。
            Map<UUID, List<WorkflowApprovalCitationSnapshot>> citationsByRun = citationsByRun(tenantId, approvals);
            items = new ArrayList<>(approvals.size());
            for (WorkflowApprovalSnapshot approval : approvals) {
                int documentReferences = documentReferenceCount(
                        citationsByRun.getOrDefault(approval.workflowRunId(), List.of()), approval.createdAt());
                items.add(response(approval, false, false, documentReferences, null));
            }
        }
        return new WorkflowApprovalPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取审批详情。
     *
     * @param actor 当前主体
     * @param approvalId 审批 ID
     * @return 审批详情（含 dataScopeSummary 与脱敏参数摘要）
     * @throws WorkflowApprovalNotFoundException 审批不存在、跨租户、发起人或未分配给当前用户
     */
    @Transactional(readOnly = true)
    public WorkflowApprovalResponse get(WorkflowApprovalActor actor, UUID approvalId) {
        requireApprovePermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        WorkflowApprovalSnapshot approval = repository
                .findById(tenantId, approvalId, now)
                .orElseThrow(() -> new WorkflowApprovalNotFoundException(APPROVAL_RESOURCE));
        requireAssignableVisibility(tenantId, approval, actor.userId());
        int documentReferences = documentReferenceCount(tenantId, approval);
        List<UUID> assigneeUserIds = approval.assigneeType() == WorkflowApprovalAssigneeType.USER
                ? repository.findAssigneeUserIds(tenantId, approval.id())
                : List.of();
        return response(approval, true, false, documentReferences, assigneeUserIds);
    }

    /**
     * 批准或拒绝审批。
     *
     * <p>校验顺序与 Worker T07a 一致：理由 → 审批可见性 → 自批 → 账号状态 → assignee 范围 → 审批状态 →
     * 运行状态；这样同一请求在 API 与 Worker 两侧得到相同错误。拒绝/范围/停用写 denied 审计后提交，
     * 业务状态不变（{@code noRollbackFor}）。
     *
     * @param actor 当前主体
     * @param approvalId 审批 ID
     * @param command 已解析的决定
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已决定的审批
     * @throws WorkflowApprovalUnavailableException 编排关闭
     * @throws WorkflowApprovalBadRequestException 理由缺失/超长
     * @throws WorkflowApprovalNotFoundException 审批不可见
     * @throws WorkflowApprovalForbiddenException 自批/范围外/账号停用
     * @throws WorkflowApprovalConflictException 重复或并发决定、运行状态冲突
     */
    @Transactional(
            noRollbackFor = {
                WorkflowApprovalNotFoundException.class,
                WorkflowApprovalForbiddenException.class,
                WorkflowApprovalBadRequestException.class,
                WorkflowApprovalConflictException.class
            })
    public WorkflowApprovalResponse decide(
            WorkflowApprovalActor actor,
            UUID approvalId,
            WorkflowApprovalDecisionCommand command,
            String traceId,
            String requestId) {
        requireFeatureEnabled();
        requireApprovePermission(actor);
        validateComment(command);
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        WorkflowApprovalSnapshot approval = repository
                .findById(tenantId, approvalId, now)
                .orElseThrow(() -> new WorkflowApprovalNotFoundException(APPROVAL_RESOURCE));
        requireApprover(tenantId, approval, actor, command, traceId, requestId);
        if (approval.status() != WorkflowApprovalStatus.PENDING) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.ALREADY_DECIDED);
        }
        WorkflowRunStatus runStatus = repository
                .findRunStatus(tenantId, approval.workflowRunId(), now)
                .orElseThrow(() -> new WorkflowApprovalNotFoundException(APPROVAL_RESOURCE));
        if (runStatus != WorkflowRunStatus.WAITING_APPROVAL) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.RUN_STATE_CONFLICT);
        }
        WorkflowGraph graph = loadGraph(tenantId, approval.workflowRunId(), now);
        long waitMs = waitMillis(approval.createdAt(), now);
        if (command.decision() == WorkflowApprovalDecision.APPROVE) {
            approve(tenantId, approval, graph, waitMs, now, actor.userId(), command.comment());
        } else {
            reject(tenantId, approval, graph, waitMs, now, actor.userId(), command.comment());
        }
        WorkflowApprovalSnapshot decided = repository
                .findById(tenantId, approval.id(), now)
                .orElseThrow(() -> new IllegalStateException("workflow approval disappeared after decision"));
        auditService.append(WorkflowApprovalAuditEvents.decided(
                now,
                tenantId.value(),
                actor.userId(),
                decided,
                command.decision(),
                command.comment(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId));
        return response(decided, false, false, documentReferenceCount(tenantId, decided), null);
    }

    /**
     * 批准：条件更新审批、节点成功、运行恢复。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @param graph 发布图
     * @param waitMs 等待毫秒
     * @param now 决定时间
     * @param approverUserId 审批人
     * @param comment 批准理由；可空
     */
    private void approve(
            TenantId tenantId,
            WorkflowApprovalSnapshot approval,
            WorkflowGraph graph,
            long waitMs,
            Instant now,
            UUID approverUserId,
            String comment) {
        if (repository.approve(tenantId, approval.id(), approverUserId, comment, now) == 0) {
            // 条件更新是重复/并发决定的唯一守卫；0 行表示已被其他请求决定。
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.CONCURRENT_DECISION);
        }
        String targetNodeKey =
                approval.targetNodeKey() != null ? approval.targetNodeKey() : successorOf(graph, approval.nodeId());
        if (repository.finishApprovalNode(
                        tenantId,
                        approval.workflowRunId(),
                        approval.nodeId(),
                        approval.attempt(),
                        WorkflowNodeStatus.SUCCEEDED,
                        decisionOutput(WorkflowApprovalDecision.APPROVE),
                        APPROVED_SUMMARY,
                        null,
                        null,
                        now)
                == 0) {
            // 审批已从 PENDING 改成功，但节点不在 WAITING_APPROVAL：状态不一致，整体回滚。
            throw new IllegalStateException("approval node is not waiting for approval");
        }
        if (repository.resumeRunPending(tenantId, approval.workflowRunId(), targetNodeKey, waitMs, now) == 0) {
            throw new IllegalStateException("workflow run is not waiting for approval");
        }
    }

    /**
     * 拒绝：条件更新审批、节点失败、运行失败、下游跳过。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @param graph 发布图
     * @param waitMs 等待毫秒
     * @param now 决定时间
     * @param approverUserId 审批人
     * @param comment 拒绝理由（已校验非空）
     */
    private void reject(
            TenantId tenantId,
            WorkflowApprovalSnapshot approval,
            WorkflowGraph graph,
            long waitMs,
            Instant now,
            UUID approverUserId,
            String comment) {
        if (repository.reject(tenantId, approval.id(), approverUserId, comment, now) == 0) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.CONCURRENT_DECISION);
        }
        if (repository.finishApprovalNode(
                        tenantId,
                        approval.workflowRunId(),
                        approval.nodeId(),
                        approval.attempt(),
                        WorkflowNodeStatus.FAILED,
                        null,
                        REJECTED_NODE_SUMMARY,
                        WorkflowErrorCode.WF_APPROVAL_REJECTED,
                        REJECTED_MESSAGE,
                        now)
                == 0) {
            throw new IllegalStateException("approval node is not waiting for approval");
        }
        skipDownstream(tenantId, approval.workflowRunId(), graph, approval.nodeId(), now);
        if (repository.failRunRejected(
                        tenantId, approval.workflowRunId(), approval.nodeId(), REJECTED_MESSAGE, waitMs, now)
                == 0) {
            throw new IllegalStateException("workflow run is not waiting for approval");
        }
    }

    /**
     * 校验理由：拒绝必填，任何决定不超过 500 字符。
     *
     * @param command 决定
     * @throws WorkflowApprovalBadRequestException 理由缺失或超长
     */
    private static void validateComment(WorkflowApprovalDecisionCommand command) {
        int length = command.commentLength();
        if (command.decision() == WorkflowApprovalDecision.REJECT && length == 0) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.COMMENT_REQUIRED, "reject decision requires a comment");
        }
        if (length > WorkflowApprovalDecisionCommand.MAX_COMMENT_LENGTH) {
            throw new WorkflowApprovalBadRequestException(
                    WorkflowApprovalBadRequestException.Reason.COMMENT_TOO_LONG,
                    "comment must not exceed " + WorkflowApprovalDecisionCommand.MAX_COMMENT_LENGTH + " characters");
        }
    }

    /**
     * 校验审批人：自批 403、账号停用 403、范围外 403；拒绝写 denied 审计。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @param actor 当前主体
     * @param command 决定
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    private void requireApprover(
            TenantId tenantId,
            WorkflowApprovalSnapshot approval,
            WorkflowApprovalActor actor,
            WorkflowApprovalDecisionCommand command,
            String traceId,
            String requestId) {
        if (actor.userId().equals(approval.requestedBy())) {
            auditDenied(tenantId, approval, actor, command, traceId, requestId);
            throw new WorkflowApprovalForbiddenException(WorkflowApprovalForbiddenException.Reason.SELF_APPROVAL);
        }
        if (!repository.isActiveUser(tenantId, actor.userId())) {
            auditDenied(tenantId, approval, actor, command, traceId, requestId);
            throw new WorkflowApprovalForbiddenException(WorkflowApprovalForbiddenException.Reason.APPROVER_INACTIVE);
        }
        if (!inScope(tenantId, approval, actor.userId())) {
            auditDenied(tenantId, approval, actor, command, traceId, requestId);
            throw new WorkflowApprovalForbiddenException(WorkflowApprovalForbiddenException.Reason.OUT_OF_SCOPE);
        }
    }

    /**
     * 判断审批人是否在节点指定范围内。
     *
     * <p>用户审批查 {@code workflow_approval_assignee}；角色审批按 {@code user_role} 联接复查，与 Worker
     * T07a 的 {@code WorkflowApprovalAssigneeResolver.inScope} 一致（ADR-0024 §5.2）。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @param userId 审批人
     * @return 范围内返回 {@code true}
     */
    private boolean inScope(TenantId tenantId, WorkflowApprovalSnapshot approval, UUID userId) {
        if (approval.assigneeType() == WorkflowApprovalAssigneeType.USER) {
            return repository.isAssignedUser(tenantId, approval.id(), userId);
        }
        String roleCode = approval.assigneeRole();
        return roleCode != null && repository.hasRole(tenantId, userId, roleCode);
    }

    /**
     * 写拒绝审计；失败直接抛出，不吞异常。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @param actor 当前主体
     * @param command 被拒绝的决定
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    private void auditDenied(
            TenantId tenantId,
            WorkflowApprovalSnapshot approval,
            WorkflowApprovalActor actor,
            WorkflowApprovalDecisionCommand command,
            String traceId,
            String requestId) {
        auditService.append(WorkflowApprovalAuditEvents.decided(
                clock.instant(),
                tenantId.value(),
                actor.userId(),
                approval,
                command.decision(),
                null,
                AuditOutcome.DENIED,
                traceId,
                requestId));
    }

    /**
     * 校验详情可见性：必须是分配给自己且不是运行发起人。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @param userId 当前用户
     * @throws WorkflowApprovalNotFoundException 不可见
     */
    private void requireAssignableVisibility(TenantId tenantId, WorkflowApprovalSnapshot approval, UUID userId) {
        if (userId.equals(approval.requestedBy()) || !inScope(tenantId, approval, userId)) {
            throw new WorkflowApprovalNotFoundException(APPROVAL_RESOURCE);
        }
    }

    /**
     * 校验主体持有 {@code workflow.approve}。
     *
     * @param actor 当前主体
     */
    private static void requireApprovePermission(WorkflowApprovalActor actor) {
        if (!actor.canApprove()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_APPROVE);
        }
    }

    /**
     * 校验编排开关。
     *
     * @throws WorkflowApprovalUnavailableException 关闭时
     */
    private void requireFeatureEnabled() {
        if (!properties.enabled()) {
            throw new WorkflowApprovalUnavailableException(properties.disabledReason());
        }
    }

    /**
     * 读取运行绑定版本的发布图。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 图
     */
    private WorkflowGraph loadGraph(TenantId tenantId, UUID runId, Instant now) {
        String graphJson = repository
                .findRunGraphJson(tenantId, runId, now)
                .orElseThrow(() -> new IllegalStateException("workflow run version graph is missing"));
        try {
            return WorkflowGraphJson.parse(graphJson);
        } catch (WorkflowGraphFormatException exception) {
            throw new IllegalStateException("workflow run version graph is invalid", exception);
        }
    }

    /**
     * 跳过审批节点之后的全部可达节点；已有尝试写 SKIPPED，没有尝试直接插入终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param graph 发布图
     * @param approvalNodeKey 审批节点
     * @param now 当前时间
     */
    private void skipDownstream(
            TenantId tenantId, UUID runId, WorkflowGraph graph, String approvalNodeKey, Instant now) {
        Set<String> keys = new LinkedHashSet<>(downstreamKeys(graph, approvalNodeKey));
        for (String key : keys) {
            WorkflowGraphNode node = graph.nodes().stream()
                    .filter(candidate -> candidate.id().equals(key))
                    .findFirst()
                    .orElse(null);
            if (node == null) {
                continue;
            }
            if (repository.hasNodeAttempt(tenantId, runId, key)) {
                repository.skipPendingNode(tenantId, runId, key, REJECTED_SKIP_SUMMARY, now);
            } else {
                repository.insertSkippedNode(
                        tenantId,
                        UuidV7.generate(now),
                        runId,
                        key,
                        node.type(),
                        node.name(),
                        REJECTED_SKIP_SUMMARY,
                        now);
            }
        }
    }

    /**
     * 计算审批节点在发布图中的全部可达下游 key。
     *
     * <p>串行图无环，但仍用 visited 防御损坏图导致重复遍历；只返回下游 key，不包含审批节点自身。
     *
     * @param graph 发布图
     * @param nodeKey 审批节点 key
     * @return 可达下游节点 key 集合
     */
    private static Set<String> downstreamKeys(WorkflowGraph graph, String nodeKey) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(nodeKey);
        queue.addLast(nodeKey);
        Set<String> downstream = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (WorkflowGraphEdge edge : graph.edges()) {
                if (!edge.source().equals(current)) {
                    continue;
                }
                if (visited.add(edge.target())) {
                    downstream.add(edge.target());
                    queue.addLast(edge.target());
                }
            }
        }
        return downstream;
    }

    /**
     * 返回审批节点的直接后继。
     *
     * @param graph 发布图
     * @param nodeKey 审批节点
     * @return 后继 key
     */
    private static String successorOf(WorkflowGraph graph, String nodeKey) {
        List<WorkflowGraphEdge> outgoing = graph.edges().stream()
                .filter(edge -> edge.source().equals(nodeKey))
                .toList();
        if (outgoing.size() != 1) {
            throw new IllegalStateException("workflow approval node has no unique successor");
        }
        return outgoing.getFirst().target();
    }

    /**
     * 序列化审批节点输出，供下游条件读取 {@code APPROVAL.decision}。
     *
     * @param decision 决定
     * @return JSON 文本
     */
    private String decisionOutput(WorkflowApprovalDecision decision) {
        WorkflowApprovalStatus status = decision == WorkflowApprovalDecision.APPROVE
                ? WorkflowApprovalStatus.APPROVED
                : WorkflowApprovalStatus.REJECTED;
        try {
            return objectMapper.writeValueAsString(Map.of("decision", status.name()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("workflow approval output cannot be serialized", exception);
        }
    }

    /**
     * 计算审批涉及的去重文档引用数。
     *
     * <p>取同一运行中已成功执行节点的 {@code citations_json} 快照（审批节点当前仍在等待，尚未产生引用）；
     * 只按文档/文件/分块主键去重计数，不返回标题、片段或正文（ADR-0024 §3.3.5、§5.3，AC-05/18）。
     *
     * @param tenantId 租户
     * @param approval 审批快照
     * @return 去重引用数
     */
    private int documentReferenceCount(TenantId tenantId, WorkflowApprovalSnapshot approval) {
        Set<String> keys = new HashSet<>();
        for (String citationsJson :
                repository.findSucceededCitations(tenantId, approval.workflowRunId(), approval.createdAt())) {
            collectCitationKeys(citationsJson, keys);
        }
        return keys.size();
    }

    /**
     * 批量读取一页审批涉及运行的上游引用快照（仓储已完成按运行分组）。
     *
     * @param tenantId 租户
     * @param approvals 当页审批
     * @return 运行 ID 到引用快照的映射
     */
    private Map<UUID, List<WorkflowApprovalCitationSnapshot>> citationsByRun(
            TenantId tenantId, List<WorkflowApprovalSnapshot> approvals) {
        List<UUID> runIds = approvals.stream()
                .map(WorkflowApprovalSnapshot::workflowRunId)
                .distinct()
                .toList();
        return repository.findSucceededCitationsByRuns(tenantId, runIds);
    }

    /**
     * 计算审批创建前已执行上游节点的去重文档引用数（批量版本）。
     *
     * <p>只按文档、文件、分块主键去重计数，不返回标题、片段或正文；与逐条版本使用同一口径
     * （ADR-0024 §3.3.5、REV-TASK-0229-001 F-003）。
     *
     * @param rows 运行下已成功节点的引用快照
     * @param createdAt 审批创建时间
     * @return 去重引用数
     */
    private int documentReferenceCount(List<WorkflowApprovalCitationSnapshot> rows, Instant createdAt) {
        Set<String> keys = new HashSet<>();
        for (WorkflowApprovalCitationSnapshot row : rows) {
            if (row.finishedAt().isAfter(createdAt)) {
                continue;
            }
            collectCitationKeys(row.citationsJson(), keys);
        }
        return keys.size();
    }

    /**
     * 解析引用快照并收集去重键。
     *
     * @param citationsJson 引用 JSON
     * @param keys 去重键集合（原地写入）
     */
    private void collectCitationKeys(String citationsJson, Set<String> keys) {
        JsonNode array = readJson(citationsJson, "workflow citation snapshot");
        if (!array.isArray()) {
            throw new IllegalStateException("workflow citation snapshot is invalid");
        }
        for (JsonNode citation : array) {
            String key = citationKey(citation);
            if (key != null) {
                keys.add(key);
            }
        }
    }

    /**
     * 解析单条引用的去重键。
     *
     * @param citation 引用对象
     * @return 去重键；没有可用标识时为空
     */
    private static String citationKey(JsonNode citation) {
        for (String field : List.of("knowledgeDocumentId", "fileId", "chunkId")) {
            JsonNode value = citation.get(field);
            if (value != null && value.isTextual() && !value.asString().isBlank()) {
                return value.asString();
            }
        }
        return null;
    }

    /**
     * 解析 JSON 文本；损坏数据 fail-closed。
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
     * 解析并校验脱敏参数摘要。
     *
     * @param approval 审批快照
     * @return 参数摘要对象
     */
    private JsonNode paramsPreview(WorkflowApprovalSnapshot approval) {
        JsonNode preview = readJson(approval.paramsPreview(), "workflow approval params preview");
        if (!preview.isObject() || preview.size() > MAX_PREVIEW_KEYS) {
            throw new IllegalStateException("workflow approval params preview is invalid");
        }
        return preview;
    }

    /**
     * 构造审批响应。
     *
     * @param approval 审批快照
     * @param detail 是否详情（返回参数摘要与详情扩展字段）
     * @param selfApprovalBlocked 当前用户是否发起人
     * @param documentReferenceCount 去重引用数
     * @param assigneeUserIds 指定用户审批人；列表传 {@code null} 以省略
     * @return 审批响应
     */
    private WorkflowApprovalResponse response(
            WorkflowApprovalSnapshot approval,
            boolean detail,
            boolean selfApprovalBlocked,
            int documentReferenceCount,
            List<UUID> assigneeUserIds) {
        return new WorkflowApprovalResponse(
                approval.id(),
                approval.workflowRunId(),
                approval.workflowId(),
                approval.workflowName(),
                approval.nodeId(),
                approval.nodeName(),
                approval.status(),
                approval.assigneeType(),
                approval.assigneeRole(),
                assigneeUserIds,
                approval.actionKind(),
                new WorkflowDataScopeSummaryResponse(
                        approval.runScope(), approval.departmentId(), documentReferenceCount),
                approval.targetToolName(),
                detail ? paramsPreview(approval) : null,
                approval.planSha256(),
                approval.requestedBy(),
                approval.decidedBy(),
                display(approval.decidedAt()),
                approval.decisionComment(),
                display(approval.createdAt()),
                detail ? selfApprovalBlocked : null,
                detail ? documentReferenceCount : null);
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
     * 计算等待毫秒（负值归零，防止测试时钟回拨）。
     *
     * @param createdAt 创建时间
     * @param now 决定时间
     * @return 等待毫秒
     */
    private static long waitMillis(Instant createdAt, Instant now) {
        long millis = Duration.between(createdAt, now).toMillis();
        return Math.max(0L, millis);
    }
}
