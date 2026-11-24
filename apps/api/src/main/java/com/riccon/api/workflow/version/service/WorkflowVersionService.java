package com.riccon.api.workflow.version.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.UserAccountRepository;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.repository.DepartmentRepository;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSnapshot;
import com.riccon.api.workflow.definition.domain.WorkflowPublishedVersionSnapshot;
import com.riccon.api.workflow.definition.dto.WorkflowVersionSummaryResponse;
import com.riccon.api.workflow.definition.repository.WorkflowDefinitionRepository;
import com.riccon.api.workflow.version.domain.WorkflowTransferCommand;
import com.riccon.api.workflow.version.dto.WorkflowVersionPageResponse;
import com.riccon.api.workflow.version.repository.WorkflowVersionRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.organization.DepartmentStatus;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本历史、停用/启用与所有权移交服务。
 *
 * <p>实现 AC-03/15/16：版本列表按版本号倒序返回，旧版本保留且不改写；停用/启用只由所有者或租户管理员
 * 执行并使用条件更新避免并发覆盖；移交只由租户管理员执行，目标必须是本租户启用且具备
 * {@code workflow.edit} 的成员。每次成功变更与审计同事务写入，失败回滚（ADR-0007/§8.2）。
 *
 * <p>可见性遵循 ADR-0024 §6.2：私有草稿/跨租户/跨部门不可见对象统一 404；已发布且按范围可见但无
 * 发布能力的同租户主体返回 403 {@code RICCON-AUTH-403}。
 */
@Service
public class WorkflowVersionService {

    /** 流程资源名，用于统一 404。 */
    private static final String RESOURCE = "workflow";

    /** 用户资源名，用于移交目标不存在时的 404。 */
    private static final String USER_RESOURCE = "user";

    /** 流程定义仓储。 */
    private final WorkflowDefinitionRepository definitionRepository;

    /** 版本历史与生命周期仓储。 */
    private final WorkflowVersionRepository versionRepository;

    /** 部门仓储，用于范围可见性判定。 */
    private final DepartmentRepository departmentRepository;

    /** 账号仓储，用于移交目标状态与权限校验。 */
    private final UserAccountRepository userAccountRepository;

    /** 审计写入；与状态/所有权变更同事务。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建版本服务。
     *
     * @param definitionRepository 流程定义仓储
     * @param versionRepository 版本历史与生命周期仓储
     * @param departmentRepository 部门仓储
     * @param userAccountRepository 账号仓储
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public WorkflowVersionService(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowVersionRepository versionRepository,
            DepartmentRepository departmentRepository,
            UserAccountRepository userAccountRepository,
            AuditService auditService,
            Clock clock) {
        this.definitionRepository =
                Objects.requireNonNull(definitionRepository, "definitionRepository must not be null");
        this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository must not be null");
        this.departmentRepository =
                Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        this.userAccountRepository =
                Objects.requireNonNull(userAccountRepository, "userAccountRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出版本。
     *
     * <p>所有者与租户管理员看全部；其他主体只在最近发布版本的运行范围内可见。版本列表不返回图 JSON，
     * 旧版本仍保留（停用流程仍可读，只是不能新运行）。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param page 分页
     * @return 版本分页
     */
    @Transactional(readOnly = true)
    public WorkflowVersionPageResponse list(WorkflowDefinitionActor actor, UUID workflowId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot snapshot = load(tenantId, workflowId);
        requireReadable(tenantId, actor, snapshot);
        long total = versionRepository.count(tenantId, workflowId);
        List<WorkflowVersionSummaryResponse> items =
                versionRepository.list(tenantId, workflowId, page.size(), page.offset()).stream()
                        .map(WorkflowVersionSummaryResponse::from)
                        .toList();
        return new WorkflowVersionPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 停用流程。
     *
     * <p>停用后不可创建新运行，历史与在途运行保留；只由所有者或租户管理员执行，重复停用返回
     * 409 {@code RICCON-WF-409}（AC-03/15）。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void disable(WorkflowDefinitionActor actor, UUID workflowId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot snapshot = load(tenantId, workflowId);
        requirePublisher(tenantId, actor, snapshot);
        changeStatus(
                tenantId,
                actor,
                snapshot,
                WorkflowDefinitionStatus.DISABLED,
                AuditActions.WORKFLOW_DISABLED,
                traceId,
                requestId);
    }

    /**
     * 重新启用流程。
     *
     * <p>只由所有者或租户管理员执行，重复启用返回 409 {@code RICCON-WF-409}；历史与在途运行不变。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void enable(WorkflowDefinitionActor actor, UUID workflowId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot snapshot = load(tenantId, workflowId);
        requirePublisher(tenantId, actor, snapshot);
        changeStatus(
                tenantId,
                actor,
                snapshot,
                WorkflowDefinitionStatus.ACTIVE,
                AuditActions.WORKFLOW_ENABLED,
                traceId,
                requestId);
    }

    /**
     * 移交流程所有权。
     *
     * <p>只有租户管理员可以执行；目标必须是本租户启用且具备 {@code workflow.edit} 的成员，原所有者
     * 失去编辑权（管理员仍可管理）。目标不存在返回 404，目标停用/无编辑权返回 400，重复移交返回
     * 409（ADR-0024 §6.2，AC-15）。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param command 移交命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void transfer(
            WorkflowDefinitionActor actor,
            UUID workflowId,
            WorkflowTransferCommand command,
            String traceId,
            String requestId) {
        if (!actor.tenantAdmin()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_MANAGE);
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot snapshot = load(tenantId, workflowId);
        UUID targetUserId = command.ownerUserId();
        if (targetUserId.equals(snapshot.ownerUserId())) {
            throw new WorkflowVersionConflictException("workflow owner is already the target user");
        }
        UserAccount target = userAccountRepository
                .findById(tenantId, targetUserId)
                .orElseThrow(() -> new NotFoundException(USER_RESOURCE));
        if (!target.isActive()) {
            throw new WorkflowVersionBadRequestException("target user must be active");
        }
        if (!userAccountRepository.findPermissions(tenantId, targetUserId).contains(PermissionCodes.WORKFLOW_EDIT)) {
            throw new WorkflowVersionBadRequestException("target user must have workflow.edit");
        }
        Instant now = clock.instant();
        int updated = versionRepository.updateOwner(
                tenantId, workflowId, snapshot.ownerUserId(), targetUserId, actor.userId(), now);
        if (updated == 0) {
            throw new WorkflowVersionConflictException("workflow owner changed during transfer");
        }
        auditService.append(WorkflowVersionAuditEvents.ownerTransferred(
                now,
                tenantId.value(),
                actor.userId(),
                workflowId,
                snapshot.ownerUserId(),
                targetUserId,
                traceId,
                requestId));
    }

    /**
     * 条件更新流程状态并写审计。
     *
     * @param tenantId 当前租户
     * @param actor 当前主体
     * @param snapshot 变更前快照
     * @param status 目标状态
     * @param action 审计动作
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    private void changeStatus(
            TenantId tenantId,
            WorkflowDefinitionActor actor,
            WorkflowDefinitionSnapshot snapshot,
            WorkflowDefinitionStatus status,
            String action,
            String traceId,
            String requestId) {
        if (snapshot.status() == status) {
            throw new WorkflowVersionConflictException("workflow is already " + status.name());
        }
        Instant now = clock.instant();
        int updated = versionRepository.updateStatus(
                tenantId, snapshot.id(), snapshot.status(), snapshot.ownerUserId(), status, actor.userId(), now);
        if (updated == 0) {
            throw new WorkflowVersionConflictException("workflow status changed during update");
        }
        auditService.append(WorkflowVersionAuditEvents.statusChanged(
                now,
                tenantId.value(),
                actor.userId(),
                snapshot.id(),
                snapshot.status(),
                status,
                action,
                traceId,
                requestId));
    }

    /**
     * 读取流程快照。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 流程快照
     */
    private WorkflowDefinitionSnapshot load(TenantId tenantId, UUID workflowId) {
        return definitionRepository.findById(tenantId, workflowId).orElseThrow(() -> new NotFoundException(RESOURCE));
    }

    /**
     * 校验读取可见性。
     *
     * <p>所有者/租户管理员始终可见；其他主体必须位于最近发布版本的运行范围内，否则 404，不泄露名称
     * 或数量（ADR-0024 §6.2）。
     *
     * @param tenantId 当前租户
     * @param actor 当前主体
     * @param snapshot 流程快照
     */
    private void requireReadable(
            TenantId tenantId, WorkflowDefinitionActor actor, WorkflowDefinitionSnapshot snapshot) {
        if (snapshot.isOwnedBy(actor.userId()) || actor.tenantAdmin()) {
            return;
        }
        WorkflowPublishedVersionSnapshot latest = latestVersion(tenantId, snapshot);
        UUID departmentId = latest == null ? null : activeDepartmentId(tenantId, actor.userId());
        if (latest == null || !withinScope(latest, snapshot, actor.userId(), departmentId)) {
            throw new NotFoundException(RESOURCE);
        }
    }

    /**
     * 校验停用/启用能力。
     *
     * <p>只有所有者与租户管理员可执行；同租户内已发布且范围可见但非所有者返回 403，私有草稿/跨范围
     * 返回 404（ADR-0024 §6.2，AC-15）。
     *
     * @param tenantId 当前租户
     * @param actor 当前主体
     * @param snapshot 流程快照
     */
    private void requirePublisher(
            TenantId tenantId, WorkflowDefinitionActor actor, WorkflowDefinitionSnapshot snapshot) {
        if (!actor.canPublish()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_PUBLISH);
        }
        if (snapshot.isOwnedBy(actor.userId()) || actor.tenantAdmin()) {
            return;
        }
        WorkflowPublishedVersionSnapshot latest = latestVersion(tenantId, snapshot);
        UUID departmentId = latest == null ? null : activeDepartmentId(tenantId, actor.userId());
        if (latest != null && withinScope(latest, snapshot, actor.userId(), departmentId)) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_PUBLISH);
        }
        throw new NotFoundException(RESOURCE);
    }

    /**
     * 读取最近发布版本。
     *
     * @param tenantId 当前租户
     * @param snapshot 流程快照
     * @return 发布摘要；未发布时为空
     */
    private WorkflowPublishedVersionSnapshot latestVersion(TenantId tenantId, WorkflowDefinitionSnapshot snapshot) {
        if (snapshot.publishedVersionId() == null) {
            return null;
        }
        return definitionRepository
                .findPublishedVersion(tenantId, snapshot.publishedVersionId())
                .orElse(null);
    }

    /**
     * 查询当前账号的启用主部门。
     *
     * @param tenantId 当前租户
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
     * 判断账号是否在发布版本运行范围内。
     *
     * @param version 发布版本
     * @param snapshot 流程快照
     * @param userId 账号
     * @param departmentId 当前启用部门
     * @return 范围内返回 {@code true}
     */
    private static boolean withinScope(
            WorkflowPublishedVersionSnapshot version,
            WorkflowDefinitionSnapshot snapshot,
            UUID userId,
            UUID departmentId) {
        return switch (version.runScope()) {
            case TENANT -> true;
            case ONLY_SELF -> snapshot.isOwnedBy(userId);
            case DEPARTMENT -> departmentId != null && departmentId.equals(version.departmentId());
        };
    }
}
