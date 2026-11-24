package com.riccon.api.workflow.run.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.config.WorkflowProperties;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.repository.DepartmentRepository;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.quota.service.QuotaAdmissionService;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphEdge;
import com.riccon.api.workflow.domain.WorkflowGraphNode;
import com.riccon.api.workflow.run.domain.WorkflowRunCreateCommand;
import com.riccon.api.workflow.run.domain.WorkflowRunDefinitionSnapshot;
import com.riccon.api.workflow.run.domain.WorkflowRunNodeSnapshot;
import com.riccon.api.workflow.run.domain.WorkflowRunRetryCommand;
import com.riccon.api.workflow.run.domain.WorkflowRunSnapshot;
import com.riccon.api.workflow.run.domain.WorkflowRunVersionSnapshot;
import com.riccon.api.workflow.run.dto.WorkflowRunResponse;
import com.riccon.api.workflow.run.domain.WorkflowRunInsert;
import com.riccon.api.workflow.run.domain.WorkflowRunNodeInsert;
import com.riccon.api.workflow.run.repository.WorkflowRunRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.organization.DepartmentStatus;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作流运行准入、创建、取消与重试服务。
 *
 * <p>准入在同一事务内完成：先锁租户行（与 M04 配额锁顺序一致）→ 查创建幂等 → 配额按运行 1 单位准入 →
 * 统计租户/单流程活跃运行（含等待审批）→ 插入 {@code PENDING} 运行 → 写审计；任一步失败整体回滚
 * （ADR-0024 §4.8，DEC-0048=A）。
 *
 * <p>版本范围校验使用用户实际选择的 {@code workflow_version.run_scope + department_id}，而不是最近发布
 * 版本或草稿范围；范围外统一 404、同租户可见但无能力统一 403（ADR-0024 §6.2，T04 评审约束）。
 *
 * <p>关闭开关只拒绝新运行与重试，取消保持可用；取消先取消待审批再条件更新运行，与审批决定的加锁顺序
 * 一致（ADR-0024 §4.8/§5.4，AC-23）。
 */
@Service
public class WorkflowRunService {

    /** 流程资源名，用于统一 404。 */
    private static final String WORKFLOW_RESOURCE = "workflow";

    /** 版本资源名，用于统一 404。 */
    private static final String VERSION_RESOURCE = "workflow version";

    /** 运行仓储。 */
    private final WorkflowRunRepository repository;

    /** M04 配额准入。 */
    private final QuotaAdmissionService quotaAdmissionService;

    /** 审计写入（同事务 fail-closed）。 */
    private final AuditService auditService;

    /** 部门仓储，用于 DEPARTMENT 范围的当前成员判定。 */
    private final DepartmentRepository departmentRepository;

    /** 租户目录，用于读取保留天数。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 编排部署配置（并发上限、保留默认、kill switch）。 */
    private final WorkflowProperties properties;

    /** 可注入 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建运行服务。
     *
     * @param repository 运行仓储
     * @param quotaAdmissionService 配额准入
     * @param auditService 审计写入
     * @param departmentRepository 部门仓储
     * @param tenantDirectoryRepository 租户目录
     * @param properties 编排部署配置
     * @param clock 可注入时钟
     */
    public WorkflowRunService(
            WorkflowRunRepository repository,
            QuotaAdmissionService quotaAdmissionService,
            AuditService auditService,
            DepartmentRepository departmentRepository,
            TenantDirectoryRepository tenantDirectoryRepository,
            WorkflowProperties properties,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.quotaAdmissionService =
                Objects.requireNonNull(quotaAdmissionService, "quotaAdmissionService must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.departmentRepository =
                Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 创建运行。
     *
     * <p>同一主体在同一流程用同一 {@code Idempotency-Key} 重放时返回既有运行，不重复扣配额、不重复写审计；
     * 幂等查询在租户行锁之后、配额准入之前执行（ADR-0024 §4.6/§7.4）。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param command 已校验的创建命令
     * @param idempotencyKey 幂等键
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 新建或既有的运行
     * @throws WorkflowRunUnavailableException 编排关闭
     * @throws NotFoundException 流程/版本不可见
     * @throws WorkflowRunBadRequestException 未发布、停用或输入/图非法
     * @throws WorkflowRunRateLimitedException 租户或流程并发超限
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkflowRunResponse create(
            WorkflowDefinitionActor actor,
            UUID workflowId,
            WorkflowRunCreateCommand command,
            String idempotencyKey,
            String traceId,
            String requestId) {
        requireFeatureEnabled();
        requireRunPermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        // 先锁租户行：与 M04 配额准入相同锁顺序，并让同租户创建/重试串行化。
        quotaAdmissionService.lockTenant(tenantId);
        var existing = repository.findByIdempotencyKey(tenantId, workflowId, actor.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return WorkflowRunResponse.from(existing.get());
        }
        WorkflowRunDefinitionSnapshot definition = repository
                .findDefinition(tenantId, workflowId)
                .orElseThrow(() -> new NotFoundException(WORKFLOW_RESOURCE));
        WorkflowRunVersionSnapshot version = resolveVersion(tenantId, definition, command.versionId());
        requireRunnable(tenantId, actor, definition, version);
        String startNodeKey = startNodeKey(version.graph());
        // 配额按一次运行准入 1 单位；超限异常会使插入与计数一并回滚。
        quotaAdmissionService.admit(tenantId, actor.userId());
        requireAdmissionLimits(tenantId, workflowId);
        Instant now = clock.instant();
        TenantRecord tenant = tenantDirectoryRepository
                .findById(actor.tenantId())
                .orElseThrow(() -> new NotFoundException("tenant"));
        UUID runId = UuidV7.generate(now);
        WorkflowRunSnapshot created = new WorkflowRunSnapshot(
                runId,
                definition.name(),
                workflowId,
                version.id(),
                version.versionNo(),
                actor.userId(),
                WorkflowRunStatus.PENDING,
                startNodeKey,
                null,
                null,
                WorkflowResultStatus.NONE,
                now,
                now,
                null,
                null,
                now.plus(Duration.ofDays(tenant.retentionDays())),
                idempotencyKey);
        repository.insertRun(
                tenantId,
                new WorkflowRunInsert(
                        runId,
                        workflowId,
                        version.id(),
                        version.versionNo(),
                        actor.userId(),
                        command.inputsJson(),
                        startNodeKey,
                        idempotencyKey,
                        created.retentionExpiresAt(),
                        now));
        auditService.append(WorkflowRunAuditEvents.requested(
                now, tenantId.value(), actor.userId(), created, version.nodeCount(), traceId, requestId));
        return WorkflowRunResponse.from(created);
    }

    /**
     * 取消运行。
     *
     * <p>仅发起人与租户管理员可取消；已取消按状态幂等返回，成功/失败终态返回 409。取消先取消待审批项，
     * 再条件更新运行，已产生节点结果保留（ADR-0024 §4.6/§5.4）。
     *
     * @param actor 当前主体
     * @param runId 运行 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @throws NotFoundException 运行不可见
     * @throws ForbiddenException 同租户可见但不是发起人或租户管理员
     * @throws WorkflowRunConflictException 运行已结束且不是已取消
     */
    @Transactional
    public void cancel(WorkflowDefinitionActor actor, UUID runId, String traceId, String requestId) {
        requireRunPermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowRunSnapshot run =
                repository.findById(tenantId, runId).orElseThrow(() -> new NotFoundException("workflow run"));
        requireOwnerOrAdmin(actor, run);
        if (run.status() == WorkflowRunStatus.CANCELLED) {
            // 取消按状态幂等；不重复写审计（ADR-0024 §7.4）。
            return;
        }
        if (run.terminal()) {
            throw new WorkflowRunConflictException("run is already " + run.status().name());
        }
        Instant now = clock.instant();
        if (run.status() == WorkflowRunStatus.WAITING_APPROVAL) {
            repository.cancelPendingApprovals(tenantId, runId, now);
        }
        int updated = repository.markCancelled(tenantId, runId, run.status(), now);
        if (updated == 0) {
            WorkflowRunSnapshot latest = repository
                    .findById(tenantId, runId)
                    .orElseThrow(() -> new NotFoundException("workflow run"));
            if (latest.status() == WorkflowRunStatus.CANCELLED) {
                return;
            }
            throw new WorkflowRunConflictException("run status changed during cancel");
        }
        auditService.append(WorkflowRunAuditEvents.cancelled(
                now, tenantId.value(), actor.userId(), runId, run.status(), traceId, requestId));
    }

    /**
     * 重试失败节点。
     *
     * <p>只允许运行 {@code FAILED} 且指定节点最新尝试 {@code FAILED}；新增 {@code attempt+1} 的
     * {@code PENDING} 节点行、把运行恢复为 {@code PENDING}、下游 {@code SKIPPED} 节点恢复为
     * {@code PENDING}。上游成功节点不重跑；写工具重审批由执行器在 T07b 完成（ADR-0024 §4.6）。
     *
     * <p>同一请求在运行已被恢复后重放时，按状态机返回 202 且不重复新增尝试/审计；不做键级持久化，
     * 与 ADR “重试由状态机与既有唯一键兜底”一致。
     *
     * @param actor 当前主体
     * @param runId 运行 ID
     * @param command 重试命令
     * @param idempotencyKey 幂等键（契约必填；状态机兜底）
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @throws WorkflowRunUnavailableException 编排关闭
     * @throws NotFoundException 运行不可见
     * @throws ForbiddenException 同租户可见但不是发起人或租户管理员
     * @throws WorkflowRunConflictException 运行/节点状态不允许重试
     * @throws WorkflowRunRateLimitedException 恢复后并发超限
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void retry(
            WorkflowDefinitionActor actor,
            UUID runId,
            WorkflowRunRetryCommand command,
            String idempotencyKey,
            String traceId,
            String requestId) {
        requireFeatureEnabled();
        requireRunPermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        // 与创建共用租户锁，保证重试与并发计数串行；失败运行本身不占活跃槽位。
        quotaAdmissionService.lockTenant(tenantId);
        WorkflowRunSnapshot run =
                repository.findById(tenantId, runId).orElseThrow(() -> new NotFoundException("workflow run"));
        requireOwnerOrAdmin(actor, run);
        WorkflowRunNodeSnapshot latest = repository
                .findLatestNode(tenantId, runId, command.nodeId())
                .orElse(null);
        if (run.status() != WorkflowRunStatus.FAILED) {
            if (isReplayAfterRetry(run, command.nodeId(), latest)) {
                return;
            }
            throw new WorkflowRunConflictException("run is not in FAILED status");
        }
        if (latest == null || latest.status() != WorkflowNodeStatus.FAILED) {
            throw new WorkflowRunConflictException("node is not a failed node of this run");
        }
        WorkflowRunVersionSnapshot version = repository
                .findVersion(tenantId, run.workflowVersionId())
                .orElseThrow(() -> new IllegalStateException("workflow run references missing version"));
        WorkflowGraphNode node = version.graph().nodes().stream()
                .filter(candidate -> candidate.id().equals(command.nodeId()))
                .findFirst()
                .orElseThrow(() -> new WorkflowRunConflictException("node is not part of the run version"));
        requireAdmissionLimits(tenantId, run.workflowId());
        Instant now = clock.instant();
        int updated = repository.markRetried(tenantId, runId, command.nodeId(), now);
        if (updated == 0) {
            WorkflowRunSnapshot latestRun = repository
                    .findById(tenantId, runId)
                    .orElseThrow(() -> new NotFoundException("workflow run"));
            if (isReplayAfterRetry(latestRun, command.nodeId(), latest)) {
                return;
            }
            throw new WorkflowRunConflictException("run status changed during retry");
        }
        int attempt = latest.attempt() + 1;
        repository.insertNodeAttempt(
                tenantId,
                new WorkflowRunNodeInsert(
                        UuidV7.generate(now),
                        runId,
                        node.id(),
                        node.type(),
                        node.name(),
                        attempt,
                        now));
        Set<String> downstream = downstreamNodeKeys(version.graph(), node.id());
        if (!downstream.isEmpty()) {
            // 只恢复失败节点的可达下游；条件未走分支的 SKIPPED 保持原状。
            repository.resetSkippedNodes(tenantId, runId, downstream, now);
        }
        auditService.append(WorkflowRunAuditEvents.retried(
                now, tenantId.value(), actor.userId(), runId, command.nodeId(), attempt, traceId, requestId));
    }

    /**
     * 判断重试请求是否是对已恢复运行的幂等重放。
     *
     * <p>条件：运行非终态、当前节点等于请求节点、该节点最新尝试是 {@code attempt>1} 的
     * {@code PENDING}。满足时首个重试已经生效，直接返回 202，不重复新增尝试或审计。
     *
     * @param run 运行快照
     * @param nodeId 请求节点
     * @param latest 最新节点尝试
     * @return 幂等重放时返回 {@code true}
     */
    private static boolean isReplayAfterRetry(
            WorkflowRunSnapshot run, String nodeId, WorkflowRunNodeSnapshot latest) {
        return !run.terminal()
                && nodeId.equals(run.currentNodeKey())
                && latest != null
                && latest.attempt() > 1
                && latest.status() == WorkflowNodeStatus.PENDING;
    }

    /**
     * 校验编排开关。
     *
     * @throws WorkflowRunUnavailableException 关闭时
     */
    private void requireFeatureEnabled() {
        if (!properties.enabled()) {
            throw new WorkflowRunUnavailableException(properties.disabledReason());
        }
    }

    /**
     * 校验主体持有 {@code workflow.run}。
     *
     * @param actor 当前主体
     */
    private static void requireRunPermission(WorkflowDefinitionActor actor) {
        if (!actor.canRun()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_RUN);
        }
    }

    /**
     * 解析运行绑定版本。
     *
     * @param tenantId 当前租户
     * @param definition 流程定义
     * @param requestedVersionId 用户所选版本；可空
     * @return 版本快照
     */
    private WorkflowRunVersionSnapshot resolveVersion(
            TenantId tenantId, WorkflowRunDefinitionSnapshot definition, UUID requestedVersionId) {
        UUID versionId = requestedVersionId;
        if (versionId == null) {
            if (definition.publishedVersionId() == null) {
                throw new WorkflowRunBadRequestException("workflow has no published version");
            }
            versionId = definition.publishedVersionId();
        }
        WorkflowRunVersionSnapshot version = repository
                .findVersion(tenantId, versionId)
                .orElseThrow(() -> new NotFoundException(VERSION_RESOURCE));
        if (!version.workflowId().equals(definition.id())) {
            // 版本不属于路径流程：按不可见统一 404，不泄露其他流程的版本存在性。
            throw new NotFoundException(VERSION_RESOURCE);
        }
        return version;
    }

    /**
     * 校验流程对当前主体可见且可运行。
     *
     * <p>范围判定与 {@code RUNNABLE} 列表一致：租户管理员可见全部；其他主体按所选版本的
     * {@code TENANT}/{@code ONLY_SELF}/{@code DEPARTMENT} 判定。所有者移出部门后不再拥有 DEPARTMENT
     * 版本的运行权，下一次请求按新范围返回 404（ADR-0024 §6.2，T02 评审 F-004/F-005）。
     *
     * @param tenantId 当前租户
     * @param actor 当前主体
     * @param definition 流程定义
     * @param version 绑定版本
     */
    private void requireRunnable(
            TenantId tenantId,
            WorkflowDefinitionActor actor,
            WorkflowRunDefinitionSnapshot definition,
            WorkflowRunVersionSnapshot version) {
        boolean visible = actor.tenantAdmin() || withinVersionScope(tenantId, actor, definition, version);
        if (!visible) {
            throw new NotFoundException(WORKFLOW_RESOURCE);
        }
        if (definition.status() == WorkflowDefinitionStatus.DISABLED) {
            throw new WorkflowRunBadRequestException("workflow is disabled");
        }
    }

    /**
     * 判断主体是否在所选版本范围内。
     *
     * @param tenantId 当前租户
     * @param actor 当前主体
     * @param definition 流程定义
     * @param version 绑定版本
     * @return 范围内返回 {@code true}
     */
    private boolean withinVersionScope(
            TenantId tenantId,
            WorkflowDefinitionActor actor,
            WorkflowRunDefinitionSnapshot definition,
            WorkflowRunVersionSnapshot version) {
        return switch (version.runScope()) {
            case TENANT -> true;
            case ONLY_SELF -> definition.ownerUserId().equals(actor.userId());
            case DEPARTMENT -> {
                UUID departmentId = activeDepartmentId(tenantId, actor.userId());
                yield departmentId != null && departmentId.equals(version.departmentId());
            }
        };
    }

    /**
     * 查询当前账号的启用主部门。
     *
     * @param tenantId 当前租户
     * @param userId 账号
     * @return 部门 ID；未分配或部门停用时为空
     */
    private UUID activeDepartmentId(TenantId tenantId, UUID userId) {
        return departmentRepository
                .findDepartmentRecordOfUser(tenantId, userId)
                .filter(record -> record.status() == DepartmentStatus.ACTIVE)
                .map(DepartmentRecord::id)
                .orElse(null);
    }

    /**
     * 校验租户与单流程准入并发。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @throws WorkflowRunRateLimitedException 超过任一层上限
     */
    private void requireAdmissionLimits(TenantId tenantId, UUID workflowId) {
        long tenantActive = repository.countActiveTenant(tenantId);
        if (tenantActive >= properties.tenantConcurrency()) {
            throw new WorkflowRunRateLimitedException(
                    "tenant", properties.tenantConcurrency(), tenantActive);
        }
        long workflowActive = repository.countActiveWorkflow(tenantId, workflowId);
        if (workflowActive >= properties.flowConcurrency()) {
            throw new WorkflowRunRateLimitedException(
                    "workflow", properties.flowConcurrency(), workflowActive);
        }
    }

    /**
     * 从发布版本图找出 START 节点 key。
     *
     * @param graph 发布图
     * @return START 节点 key
     * @throws WorkflowRunBadRequestException 发布图缺少 START（数据异常，不静默绑定错误节点）
     */
    private static String startNodeKey(WorkflowGraph graph) {
        return graph.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeType.START)
                .map(WorkflowGraphNode::id)
                .findFirst()
                .orElseThrow(() -> new WorkflowRunBadRequestException("workflow version has no START node"));
    }

    /**
     * 计算失败节点在发布图中的全部可达下游节点。
     *
     * <p>串行图无环，但这里仍用 visited 防御损坏图导致重复遍历。只返回下游 key，不包含失败节点自身；
     * 重试只恢复这些节点的 {@code SKIPPED} 状态（ADR-0024 §4.6）。
     *
     * @param graph 发布图
     * @param nodeKey 失败节点 key
     * @return 可达下游节点 key 集合
     */
    private static Set<String> downstreamNodeKeys(WorkflowGraph graph, String nodeKey) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(nodeKey);
        queue.addLast(nodeKey);
        Set<String> downstream = new HashSet<>();
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
     * 校验取消/重试主体：发起人或租户管理员。
     *
     * @param actor 当前主体
     * @param run 运行快照
     */
    private static void requireOwnerOrAdmin(WorkflowDefinitionActor actor, WorkflowRunSnapshot run) {
        if (!run.requesterUserId().equals(actor.userId()) && !actor.tenantAdmin()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_RUN);
        }
    }
}
