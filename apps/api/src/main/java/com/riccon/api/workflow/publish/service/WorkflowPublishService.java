package com.riccon.api.workflow.publish.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.repository.DepartmentRepository;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSnapshot;
import com.riccon.api.workflow.definition.domain.WorkflowPublishedVersionSnapshot;
import com.riccon.api.workflow.definition.dto.WorkflowVersionSummaryResponse;
import com.riccon.api.workflow.definition.repository.WorkflowDefinitionRepository;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.api.workflow.publish.domain.WorkflowPublishCommand;
import com.riccon.api.workflow.publish.domain.WorkflowPublishLockRow;
import com.riccon.api.workflow.publish.domain.WorkflowVersionInsert;
import com.riccon.api.workflow.publish.repository.WorkflowPublishRepository;
import com.riccon.api.workflow.validation.WorkflowPublishScopeValidator;
import com.riccon.api.workflow.validation.WorkflowScopeValidationException;
import com.riccon.api.workflow.validation.WorkflowValidationException;
import com.riccon.api.workflow.validation.WorkflowValidationRequest;
import com.riccon.api.workflow.validation.WorkflowValidationResult;
import com.riccon.api.workflow.validation.WorkflowValidator;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.organization.DepartmentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流程发布服务。
 *
 * <p>实现 AC-03：先按 {@link com.riccon.api.workflow.validation.WorkflowValidationScope#PUBLISH} 完整校验
 * （结构/必填/模型/工具/条件/结果/限制、审批可达/NO_APPROVER，以及发布范围），失败返回 400
 * {@code RICCON-WF-400}，不写 {@code workflow_version}、不改当前发布版本；通过后写不可变版本快照
 * （规范化 JSON + SHA-256 + 范围）、更新定义的发布指针并同事务写审计。
 *
 * <p>幂等口径：M06 不新增迁移，也**不承诺** {@code Idempotency-Key} 键级重放；同一草稿以“当前发布版本的
 * {@code graph_sha256 + run_scope + department_id} 与本次发布相同”判定资源级幂等，直接返回既有版本，
 * 不产生重复版本或重复 {@code workflow.published} 审计；草稿内容或范围变化必须发布新版本。控制器仍
 * 接受可选的 {@code Idempotency-Key}（只校验 8..128 长度），但不持久化该键（ADR-0024 §7.4、§16.1）。
 *
 * <p>并发：发布前对 {@code workflow_definition} 行加排他锁并在锁内重读草稿，保证同一流程的发布、
 * 保存草稿、停用/启用与移交串行化；后到者能看到先到者提交的发布指针并走幂等分支。本方法使用
 * {@link Isolation#READ_COMMITTED}：MySQL 默认的 REPEATABLE READ 会让后到事务的普通读继续使用加锁前的
 * 快照，看不到先到事务刚提交的 {@code workflow_version} 行，从而误判为非重复发布；READ COMMITTED
 * 保证锁内读取能看到最新提交，同时行锁仍阻止并发写。
 */
@Service
public class WorkflowPublishService {

    /** 流程资源名，用于统一 404。 */
    private static final String RESOURCE = "workflow";

    /** 流程定义仓储，读取草稿与发布摘要。 */
    private final WorkflowDefinitionRepository definitionRepository;

    /** 发布写入仓储。 */
    private final WorkflowPublishRepository publishRepository;

    /** 图校验器；发布复用 PUBLISH profile。 */
    private final WorkflowValidator validator;

    /** 审计写入；与发布同事务，失败回滚。 */
    private final AuditService auditService;

    /** 部门仓储，用于已发布版本的可见性判定。 */
    private final DepartmentRepository departmentRepository;

    /** 发布/校验共用的运行范围校验器。 */
    private final WorkflowPublishScopeValidator scopeValidator;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建发布服务。
     *
     * @param definitionRepository 流程定义仓储
     * @param publishRepository 发布写入仓储
     * @param validator 图校验器
     * @param auditService 审计写入
     * @param departmentRepository 部门仓储
     * @param scopeValidator 发布/校验共用的运行范围校验器
     * @param clock 可注入时钟
     */
    public WorkflowPublishService(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowPublishRepository publishRepository,
            WorkflowValidator validator,
            AuditService auditService,
            DepartmentRepository departmentRepository,
            WorkflowPublishScopeValidator scopeValidator,
            Clock clock) {
        this.definitionRepository =
                Objects.requireNonNull(definitionRepository, "definitionRepository must not be null");
        this.publishRepository = Objects.requireNonNull(publishRepository, "publishRepository must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.departmentRepository =
                Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        this.scopeValidator = Objects.requireNonNull(scopeValidator, "scopeValidator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 发布当前草稿为不可变版本。
     *
     * @param actor 当前主体
     * @param workflowId 流程 ID
     * @param command 发布命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 新发布版本或同一草稿的既有版本
     * @throws WorkflowScopeValidationException 范围/角色/部门不满足发布条件
     * @throws WorkflowValidationException 完整发布校验失败
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkflowVersionSummaryResponse publish(
            WorkflowDefinitionActor actor,
            UUID workflowId,
            WorkflowPublishCommand command,
            String traceId,
            String requestId) {
        requirePublisher(actor);
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkflowDefinitionSnapshot initial = load(tenantId, workflowId);
        requireOwnerOrAdminWithVisibility(tenantId, actor, initial);

        // 行锁：锁内重读草稿，避免并发保存使发布使用旧图；后到发布者看到先到者的发布指针。
        WorkflowPublishLockRow lock = publishRepository
                .lockDefinition(tenantId, workflowId)
                .orElseThrow(() -> new NotFoundException(RESOURCE));
        WorkflowDefinitionSnapshot snapshot = load(tenantId, workflowId);
        requireOwnerOrAdminWithVisibility(tenantId, actor, snapshot);

        UUID departmentId = scopeValidator.resolve(
                tenantId, actor.userId(), actor.tenantAdmin(), command.runScope(), command.departmentId());
        String graphSha256 = WorkflowGraphJson.sha256(snapshot.draftGraph());
        WorkflowPublishedVersionSnapshot existing = existingVersion(tenantId, lock);
        if (existing != null
                && existing.graphSha256().equals(graphSha256)
                && existing.runScope() == command.runScope()
                && Objects.equals(existing.departmentId(), departmentId)) {
            // 同一草稿 + 范围重复发布：返回既有不可变版本，不写第二条版本或审计。
            return WorkflowVersionSummaryResponse.from(existing);
        }

        WorkflowValidationResult validation =
                validator.validate(snapshot.draftGraph(), WorkflowValidationRequest.publish(tenantId, actor.userId()));
        if (!validation.valid()) {
            // 校验失败发生在任何版本写入之前；当前发布指针保持不变（AC-03）。
            throw new WorkflowValidationException(validation);
        }

        Instant now = clock.instant();
        int versionNo = publishRepository.nextVersionNo(tenantId, workflowId);
        UUID versionId = UuidV7.generate(now);
        int nodeCount = snapshot.draftGraph().nodeCount();
        String graphJson = WorkflowGraphJson.toJson(snapshot.draftGraph());
        publishRepository.insertVersion(
                tenantId,
                new WorkflowVersionInsert(
                        versionId,
                        workflowId,
                        versionNo,
                        graphJson,
                        graphSha256,
                        nodeCount,
                        command.runScope(),
                        departmentId,
                        actor.userId(),
                        now));
        int updated = publishRepository.markPublished(tenantId, workflowId, versionId, versionNo, actor.userId(), now);
        if (updated != 1) {
            // 行锁内不应发生；保留 409 语义并回滚已插入版本。
            throw new WorkflowPublishConflictException("workflow changed during publish");
        }
        auditService.append(WorkflowPublishAuditEvents.published(
                now,
                tenantId.value(),
                actor.userId(),
                workflowId,
                versionId,
                versionNo,
                command.runScope(),
                departmentId,
                nodeCount,
                traceId,
                requestId));
        return new WorkflowVersionSummaryResponse(
                versionId,
                workflowId,
                versionNo,
                command.runScope(),
                departmentId,
                graphSha256,
                nodeCount,
                actor.userId(),
                now);
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
     * 校验发布权限。
     *
     * @param actor 当前主体
     */
    private static void requirePublisher(WorkflowDefinitionActor actor) {
        if (!actor.canPublish()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_PUBLISH);
        }
    }

    /**
     * 校验操作者可以发布该流程。
     *
     * <p>只有所有者与租户管理员可以发布；私有草稿对其他编辑者不可见，返回 404；已发布且按范围可见
     * 但非所有者，属于“可见但无能力”，返回 403（ADR-0024 §6.2，AC-15）。
     *
     * @param tenantId 当前租户
     * @param actor 当前主体
     * @param snapshot 流程快照
     */
    private void requireOwnerOrAdminWithVisibility(
            TenantId tenantId, WorkflowDefinitionActor actor, WorkflowDefinitionSnapshot snapshot) {
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
     * 读取当前发布版本摘要。
     *
     * @param tenantId 当前租户
     * @param lock 行锁快照
     * @return 发布摘要；未发布时为空
     */
    private WorkflowPublishedVersionSnapshot existingVersion(TenantId tenantId, WorkflowPublishLockRow lock) {
        if (lock.publishedVersionId() == null) {
            return null;
        }
        return definitionRepository
                .findPublishedVersion(tenantId, lock.publishedVersionId())
                .orElse(null);
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
