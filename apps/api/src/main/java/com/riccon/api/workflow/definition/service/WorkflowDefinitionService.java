package com.riccon.api.workflow.definition.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.repository.DepartmentRepository;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionCreation;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSnapshot;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionView;
import com.riccon.api.workflow.definition.domain.WorkflowPublishedVersionSnapshot;
import com.riccon.api.workflow.definition.domain.WorkflowTemplateSnapshot;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionDetailResponse;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionPageResponse;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionResponse;
import com.riccon.api.workflow.definition.dto.WorkflowVersionSummaryResponse;
import com.riccon.api.workflow.definition.repository.WorkflowDefinitionRepository;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionRequestParser.CreateCommand;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionRequestParser.DraftCommand;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionRequestParser.DuplicateCommand;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.api.workflow.validation.WorkflowPublishScopeValidator;
import com.riccon.api.workflow.validation.WorkflowValidationException;
import com.riccon.api.workflow.validation.WorkflowValidationRequest;
import com.riccon.api.workflow.validation.WorkflowValidationResult;
import com.riccon.api.workflow.validation.WorkflowValidator;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.organization.DepartmentStatus;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流程定义与草稿服务。
 *
 * <p>实现 AC-01 的保存/重开/未发布不可运行/并发冲突与另存副本：草稿只能由所有者与租户管理员读写；
 * 保存使用 {@code draft_version} 条件更新，冲突返回 409 {@code RICCON-WF-409}；未发布草稿不进入
 * {@code view=RUNNABLE}。发布、停用/启用与移交属于 T04，运行创建属于 T05。
 *
 * <p>审计 {@code workflow.created}/{@code workflow.draft.saved} 与业务同事务写入，失败即回滚
 * （ADR-0007/ADR-0024 §8.2）。本服务不记录 prompt、查询模板、图 JSON 或工具参数。
 *
 * <p>保存前调用 {@link WorkflowValidator} 拒绝非法图；{@link #validateDraft} 按发布口径返回逐项问题但不改
 * 草稿版本、不写审计。发布与运行由 T04/T05 复用同一校验器，保证三处规则一致。
 */
@Service
public class WorkflowDefinitionService {

    /** 流程资源名，用于统一 404。 */
    private static final String RESOURCE = "workflow";

    /** 副本名称后缀。 */
    private static final String COPY_SUFFIX = " 副本";

    /** 流程名最大长度。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** 流程定义仓储。 */
    private final WorkflowDefinitionRepository repository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 部门仓储；用于 DEPARTMENT 范围可见性与运行准入判定。 */
    private final DepartmentRepository departmentRepository;

    /** 图校验器；保存、校验、发布与运行前共用。 */
    private final WorkflowValidator validator;

    /** 发布/校验共用的运行范围校验器。 */
    private final WorkflowPublishScopeValidator scopeValidator;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param repository 流程定义仓储
     * @param auditService 审计写入
     * @param departmentRepository 部门仓储
     * @param validator 图校验器
     * @param scopeValidator 发布/校验共用的运行范围校验器
     * @param clock 可注入时钟
     */
    public WorkflowDefinitionService(
            WorkflowDefinitionRepository repository,
            AuditService auditService,
            DepartmentRepository departmentRepository,
            WorkflowValidator validator,
            WorkflowPublishScopeValidator scopeValidator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.departmentRepository =
                Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.scopeValidator = Objects.requireNonNull(scopeValidator, "scopeValidator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出可见流程。
     *
     * @param actor 当前主体
     * @param view 列表视图
     * @param status 可选状态
     * @param page 分页
     * @return 流程分页
     */
    @Transactional(readOnly = true)
    public WorkflowDefinitionPageResponse list(
            WorkflowDefinitionActor actor,
            WorkflowDefinitionView view,
            WorkflowDefinitionStatus status,
            PageQuery page) {
        if (view == WorkflowDefinitionView.ALL && !actor.tenantAdmin()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_MANAGE);
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        UUID departmentId = needsDepartment(view, actor) ? activeDepartmentId(tenantId, actor.userId()) : null;
        long total = repository.count(tenantId, view, status, actor.userId(), departmentId, actor.tenantAdmin());
        List<WorkflowDefinitionResponse> items = repository
                .list(
                        tenantId,
                        view,
                        status,
                        actor.userId(),
                        departmentId,
                        actor.tenantAdmin(),
                        page.size(),
                        page.offset())
                .stream()
                .map(snapshot -> WorkflowDefinitionResponse.from(snapshot, hasUnpublishedChanges(snapshot)))
                .toList();
        return new WorkflowDefinitionPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取流程详情。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @return 流程详情
     */
    @Transactional(readOnly = true)
    public WorkflowDefinitionDetailResponse get(WorkflowDefinitionActor actor, UUID workflowId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot snapshot =
                repository.findById(tenantId, workflowId).orElseThrow(() -> new NotFoundException(RESOURCE));
        boolean owner = snapshot.isOwnedBy(actor.userId());
        boolean admin = actor.tenantAdmin();
        WorkflowPublishedVersionSnapshot latest = latestVersion(tenantId, snapshot);
        // 运行范围对所有角色统一按发布版本判定；owner/admin 只影响详情可见性与编辑能力，不豁免运行范围。
        UUID departmentId = latest == null || admin ? null : activeDepartmentId(tenantId, actor.userId());
        boolean scopeVisible = latest != null && withinScope(latest, snapshot, actor, departmentId);
        if (!owner && !admin && !scopeVisible) {
            // 私有草稿、跨范围或跨租户统一 404，不泄露存在性（ADR-0024 §6.2）。
            throw new NotFoundException(RESOURCE);
        }
        return detail(snapshot, latest, actor, owner || admin, scopeVisible);
    }

    /**
     * 创建空白草稿。
     *
     * @param actor 当前主体
     * @param command 创建命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已创建草稿
     */
    @Transactional
    public WorkflowDefinitionDetailResponse create(
            WorkflowDefinitionActor actor, CreateCommand command, String traceId, String requestId) {
        requireEditPermission(actor);
        if (command.templateId() != null) {
            return createFromTemplate(
                    actor, command.templateId(), command.name(), command.description(), traceId, requestId);
        }
        if (command.name() == null) {
            throw new WorkflowDefinitionBadRequestException("name is required when templateId is absent");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        UUID workflowId = UuidV7.generate(now);
        WorkflowGraph emptyGraph = new WorkflowGraph(WorkflowGraph.SCHEMA_VERSION, List.of(), List.of());
        repository.insert(
                tenantId,
                new WorkflowDefinitionCreation(
                        workflowId,
                        command.name(),
                        command.description(),
                        actor.userId(),
                        emptyGraph,
                        null,
                        null,
                        null,
                        actor.userId(),
                        now));
        auditService.append(WorkflowDefinitionAuditEvents.created(
                now, tenantId.value(), actor.userId(), workflowId, null, null, traceId, requestId));
        return detail(load(tenantId, workflowId), null, actor, true, false);
    }

    /**
     * 从内置模板创建当前用户的草稿。
     *
     * <p>供 {@link #create} 与 T14 的模板复制端点复用；只读取内置目录，不修改源模板（AC-10）。模板不存在
     * 或已停用按请求非法 400 处理，因为契约 {@code POST /workflows} 未声明 404。
     *
     * @param actor 当前主体
     * @param templateId 模板 ID
     * @param name 可选名称；为空时使用模板名
     * @param description 可选说明；为空时使用模板说明
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已创建草稿
     */
    @Transactional
    public WorkflowDefinitionDetailResponse createFromTemplate(
            WorkflowDefinitionActor actor,
            UUID templateId,
            String name,
            String description,
            String traceId,
            String requestId) {
        requireEditPermission(actor);
        WorkflowTemplateSnapshot template = repository
                .findBuiltInTemplate(templateId)
                .orElseThrow(() -> new WorkflowDefinitionBadRequestException("templateId is not available"));
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();
        UUID workflowId = UuidV7.generate(now);
        String resolvedName = name == null ? template.name() : name;
        String resolvedDescription = description == null ? template.description() : description;
        repository.insert(
                tenantId,
                new WorkflowDefinitionCreation(
                        workflowId,
                        resolvedName,
                        resolvedDescription,
                        actor.userId(),
                        template.graph(),
                        template.id(),
                        template.sourceType(),
                        template.version(),
                        actor.userId(),
                        now));
        auditService.append(WorkflowDefinitionAuditEvents.created(
                now,
                tenantId.value(),
                actor.userId(),
                workflowId,
                template.id(),
                template.sourceType(),
                traceId,
                requestId));
        return detail(load(tenantId, workflowId), null, actor, true, false);
    }

    /**
     * 保存草稿（乐观锁）。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param command 保存命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 保存后的详情
     */
    @Transactional
    public WorkflowDefinitionDetailResponse saveDraft(
            WorkflowDefinitionActor actor, UUID workflowId, DraftCommand command, String traceId, String requestId) {
        requireEditPermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireEditable(tenantId, workflowId, actor);
        WorkflowValidationResult validation =
                validator.validate(command.graph(), WorkflowValidationRequest.save(tenantId, actor.userId()));
        if (!validation.valid()) {
            // 非法图不写库、不写审计；逐项问题由 400 detail 与 validate 端点返回（AC-02/24）。
            throw new WorkflowValidationException(validation);
        }
        Instant now = clock.instant();
        int updated = repository.updateDraft(
                tenantId,
                workflowId,
                command.version(),
                command.name(),
                command.description(),
                command.descriptionPresent(),
                command.graph(),
                actor.userId(),
                now);
        if (updated == 0) {
            // 版本不匹配：不覆盖对方修改，由客户端选择另存副本或放弃（AC-01）。
            throw new WorkflowDefinitionConflictException("draft version does not match");
        }
        WorkflowDefinitionSnapshot saved = load(tenantId, workflowId);
        auditService.append(WorkflowDefinitionAuditEvents.draftSaved(
                now,
                tenantId.value(),
                actor.userId(),
                workflowId,
                saved.draftVersion(),
                command.graph().nodeCount(),
                traceId,
                requestId));
        WorkflowPublishedVersionSnapshot latest = latestVersion(tenantId, saved);
        UUID departmentId = latest == null || actor.tenantAdmin() ? null : activeDepartmentId(tenantId, actor.userId());
        boolean scopeVisible = latest != null && withinScope(latest, saved, actor, departmentId);
        return detail(saved, latest, actor, true, scopeVisible);
    }

    /**
     * 校验当前草稿（不改变版本、不写审计）。
     *
     * <p>按发布口径返回逐项问题：结构、必填参数、模型、工具、条件、结果与可用审批人；画布与列表/表单共用
     * 同一结果（AC-02/06）。可选 {@code runScope}/{@code departmentId} 与 PUBLISH 复用同一范围校验器：
     * 提供时校验角色/部门并返回发布等价结果；省略时只校验图与审批可达性，不含范围（ADR-0024 §7.4，
     * F-002）。部门校验只读，不改变草稿版本、不写审计。只有所有者与租户管理员可见草稿，其他主体按
     * 私有草稿 404（ADR-0024 §6.2）。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param runScope 可选运行范围；为空表示不校验范围
     * @param departmentId 可选部门 ID；非 DEPARTMENT 范围必须为空
     * @return 图校验结果
     */
    @Transactional(readOnly = true)
    public WorkflowValidationResult validateDraft(
            WorkflowDefinitionActor actor, UUID workflowId, WorkflowRunScope runScope, UUID departmentId) {
        requireEditPermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot snapshot = requireEditable(tenantId, workflowId, actor);
        // 与 PUBLISH 同口径：先校验范围组合/角色/部门，再执行完整图与审批可达性校验；全程只读。
        scopeValidator.resolve(tenantId, actor.userId(), actor.tenantAdmin(), runScope, departmentId);
        return validator.validate(snapshot.draftGraph(), WorkflowValidationRequest.publish(tenantId, actor.userId()));
    }

    /**
     * 另存副本。
     *
     * @param actor 当前主体
     * @param sourceId 源流程 ID
     * @param command 副本命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 副本详情
     */
    @Transactional
    public WorkflowDefinitionDetailResponse duplicate(
            WorkflowDefinitionActor actor, UUID sourceId, DuplicateCommand command, String traceId, String requestId) {
        requireEditPermission(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot source =
                repository.findById(tenantId, sourceId).orElseThrow(() -> new NotFoundException(RESOURCE));
        if (!source.isOwnedBy(actor.userId()) && !actor.tenantAdmin()) {
            // 草稿只对所有者与租户管理员可见；其他编辑者复制已发布流程属于 T04/T05 能力，不在草稿副本内。
            throw new NotFoundException(RESOURCE);
        }
        Instant now = clock.instant();
        UUID workflowId = UuidV7.generate(now);
        String name = command.name() == null ? copyName(source.name()) : command.name();
        repository.insert(
                tenantId,
                new WorkflowDefinitionCreation(
                        workflowId,
                        name,
                        source.description(),
                        actor.userId(),
                        source.draftGraph(),
                        source.sourceTemplateId(),
                        source.sourceTemplateType(),
                        source.sourceTemplateVersion(),
                        actor.userId(),
                        now));
        auditService.append(WorkflowDefinitionAuditEvents.created(
                now, tenantId.value(), actor.userId(), workflowId, null, null, traceId, requestId));
        return detail(load(tenantId, workflowId), null, actor, true, false);
    }

    /**
     * 读取流程并把缺失映射为统一 404。
     *
     * @param tenantId 租户
     * @param workflowId 流程 ID
     * @return 流程快照
     */
    private WorkflowDefinitionSnapshot load(TenantId tenantId, UUID workflowId) {
        return repository.findById(tenantId, workflowId).orElseThrow(() -> new NotFoundException(RESOURCE));
    }

    /**
     * 校验当前主体可编辑草稿：非所有者/管理员按私有草稿 404；所有者但角色被撤销按 403。
     *
     * @param tenantId 租户
     * @param workflowId 流程 ID
     * @param actor 当前主体
     * @return 流程快照
     */
    private WorkflowDefinitionSnapshot requireEditable(
            TenantId tenantId, UUID workflowId, WorkflowDefinitionActor actor) {
        WorkflowDefinitionSnapshot snapshot = load(tenantId, workflowId);
        if (!snapshot.isOwnedBy(actor.userId()) && !actor.tenantAdmin()) {
            throw new NotFoundException(RESOURCE);
        }
        if (!actor.canEdit()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_EDIT);
        }
        return snapshot;
    }

    /**
     * 校验编辑权限。
     *
     * @param actor 当前主体
     */
    private static void requireEditPermission(WorkflowDefinitionActor actor) {
        if (!actor.canEdit()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_EDIT);
        }
    }

    /**
     * 判断列表视图是否需要当前部门。
     *
     * @param view 视图
     * @param actor 当前主体
     * @return 需要时返回 {@code true}
     */
    private static boolean needsDepartment(WorkflowDefinitionView view, WorkflowDefinitionActor actor) {
        if (actor.tenantAdmin()) {
            return false;
        }
        return view == WorkflowDefinitionView.MINE || view == WorkflowDefinitionView.RUNNABLE;
    }

    /**
     * 查询当前账号的启用主部门。
     *
     * @param tenantId 租户
     * @param userId 账号
     * @return 部门 ID；无部门或部门停用时为空
     */
    private UUID activeDepartmentId(TenantId tenantId, UUID userId) {
        return departmentRepository
                .findDepartmentRecordOfUser(tenantId, userId)
                .filter(record -> record.status() == DepartmentStatus.ACTIVE)
                .map(DepartmentRecord::id)
                .orElse(null);
    }

    /**
     * 读取最近发布版本。
     *
     * @param tenantId 租户
     * @param snapshot 流程快照
     * @return 发布版本；未发布时为空
     */
    private WorkflowPublishedVersionSnapshot latestVersion(TenantId tenantId, WorkflowDefinitionSnapshot snapshot) {
        if (snapshot.publishedVersionId() == null) {
            return null;
        }
        return repository
                .findPublishedVersion(tenantId, snapshot.publishedVersionId())
                .orElse(null);
    }

    /**
     * 判断操作者是否在发布版本的运行范围内。
     *
     * @param version 发布版本
     * @param snapshot 流程快照
     * @param actor 当前主体
     * @param departmentId 当前启用部门
     * @return 范围内返回 {@code true}
     */
    private static boolean withinScope(
            WorkflowPublishedVersionSnapshot version,
            WorkflowDefinitionSnapshot snapshot,
            WorkflowDefinitionActor actor,
            UUID departmentId) {
        return switch (version.runScope()) {
            case TENANT -> true;
            case ONLY_SELF -> snapshot.isOwnedBy(actor.userId());
            case DEPARTMENT -> departmentId != null && departmentId.equals(version.departmentId());
        };
    }

    /**
     * 判断草稿是否落后于最近发布版本。
     *
     * <p>未发布恒为 {@code true}；已发布时比较草稿规范化哈希与版本 {@code graph_sha256}。发布必须使用
     * {@link WorkflowGraphJson#sha256(WorkflowGraph)} 计算哈希，否则该判断会失真（T04 约束）。
     *
     * @param snapshot 流程快照
     * @return 存在未发布修改时返回 {@code true}
     */
    private static boolean hasUnpublishedChanges(WorkflowDefinitionSnapshot snapshot) {
        if (snapshot.publishedVersionId() == null || snapshot.publishedGraphSha256() == null) {
            return true;
        }
        return !WorkflowGraphJson.sha256(snapshot.draftGraph()).equals(snapshot.publishedGraphSha256());
    }

    /**
     * 组装流程详情与能力标记。
     *
     * @param snapshot 流程快照
     * @param latest 最近发布版本；可空
     * @param actor 当前主体
     * @param includeDraftGraph 是否返回草稿图
     * @param scopeVisible 是否在发布版本运行范围内；仅用于 canRun，所有者不豁免 DEPARTMENT 范围
     * @return 流程详情
     */
    private static WorkflowDefinitionDetailResponse detail(
            WorkflowDefinitionSnapshot snapshot,
            WorkflowPublishedVersionSnapshot latest,
            WorkflowDefinitionActor actor,
            boolean includeDraftGraph,
            boolean scopeVisible) {
        boolean owner = snapshot.isOwnedBy(actor.userId());
        boolean admin = actor.tenantAdmin();
        boolean canEdit = (owner || admin) && actor.canEdit();
        boolean canPublish = (owner || admin) && actor.canPublish();
        // canRun 与运行准入/RUNNABLE 同口径：租户管理员按管理能力放行，其余主体必须命中版本范围。
        boolean canRun = latest != null
                && snapshot.status() == WorkflowDefinitionStatus.ACTIVE
                && actor.canRun()
                && (admin || scopeVisible);
        WorkflowDefinitionResponse summary = WorkflowDefinitionResponse.from(snapshot, hasUnpublishedChanges(snapshot));
        return WorkflowDefinitionDetailResponse.from(
                summary,
                includeDraftGraph ? snapshot.draftGraph() : null,
                latest == null ? null : WorkflowVersionSummaryResponse.from(latest),
                canEdit,
                canPublish,
                canRun);
    }

    /**
     * 生成副本默认名称：原名 + “ 副本”，超出 200 字符时截断原名。
     *
     * @param sourceName 源名称
     * @return 副本名称
     */
    private static String copyName(String sourceName) {
        int maxBase = MAX_NAME_LENGTH - COPY_SUFFIX.length();
        String base = sourceName.length() > maxBase ? sourceName.substring(0, maxBase) : sourceName;
        return base + COPY_SUFFIX;
    }
}
