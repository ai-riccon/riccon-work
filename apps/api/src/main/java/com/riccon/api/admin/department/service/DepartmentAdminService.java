package com.riccon.api.admin.department.service;

import com.riccon.api.admin.department.repository.DepartmentAdminRepository;
import com.riccon.api.admin.repository.AdminRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.department.domain.DepartmentConflictException;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.domain.DepartmentUserRecord;
import com.riccon.api.department.dto.DepartmentMemberPageResponse;
import com.riccon.api.department.dto.DepartmentMemberResponse;
import com.riccon.api.department.dto.DepartmentPageResponse;
import com.riccon.api.department.dto.DepartmentResponse;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.TenantStatus;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.UserStatus;
import com.riccon.platform.domain.organization.DepartmentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台管理员跨租户部门元数据管理。
 *
 * <p>部门管理主体是平台管理员（DEC-0015）：创建/改名/停用/启用、成员加/移、负责人移交都只在这里。
 * 目标租户必须存在且 {@code ACTIVE}，不存在统一 404、已暂停 409；业务读写显式使用目标租户作用域，审计事件
 * 写操作者租户（平台管理员为 {@code platform}），目标租户只进入 {@code details.targetTenantId}
 * （ADR-0018 第 1.4/5/7.3 节）。所有冲突返回 409 且错误码 {@code RICCON-DEPT-409}。
 */
@Service
public class DepartmentAdminService {

    /** 部门管理仓储。 */
    private final DepartmentAdminRepository departmentRepository;

    /** 平台管理仓储，用于校验目标租户状态。 */
    private final AdminRepository adminRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建平台部门管理服务。
     *
     * @param departmentRepository 部门管理仓储
     * @param adminRepository 平台管理仓储
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public DepartmentAdminService(
            DepartmentAdminRepository departmentRepository,
            AdminRepository adminRepository,
            AuditService auditService,
            Clock clock) {
        this.departmentRepository = Objects.requireNonNull(departmentRepository, "departmentRepository");
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 分页列出目标租户部门。
     *
     * @param tenantId 目标租户
     * @param status 可选状态过滤
     * @param page 分页参数
     * @return 部门分页
     */
    @Transactional(readOnly = true)
    public DepartmentPageResponse listDepartments(TenantId tenantId, DepartmentStatus status, PageQuery page) {
        requireActiveTenant(tenantId);
        long total = departmentRepository.countDepartments(tenantId, status);
        List<DepartmentResponse> items =
                departmentRepository.listDepartments(tenantId, status, page.size(), page.offset()).stream()
                        .map(DepartmentResponse::from)
                        .toList();
        return new DepartmentPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 在目标租户创建部门。
     *
     * @param tenantId 目标租户
     * @param name 部门名
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 新部门
     */
    @Transactional
    public DepartmentResponse createDepartment(
            TenantId tenantId, String name, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        String departmentName = Validation.requireText(name, "name", 1, 100);
        if (departmentRepository.existsByName(tenantId, departmentName)) {
            throw new DepartmentConflictException("department name already exists");
        }
        Instant now = clock.instant();
        UUID departmentId = UuidV7.generate(now);
        try {
            departmentRepository.insertDepartment(tenantId, departmentId, departmentName, actor.userId(), now);
        } catch (DuplicateKeyException exception) {
            // 并发创建同名部门时由数据库唯一键兜底，统一转成稳定冲突码。
            throw new DepartmentConflictException("department name already exists");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("name", departmentName);
        details.put("status", DepartmentStatus.ACTIVE.name());
        appendAudit(actor, AuditActions.DEPARTMENT_CREATED, departmentId, meta, details);
        return loadDepartment(tenantId, departmentId);
    }

    /**
     * 重命名目标租户部门。
     *
     * <p>只有启用部门可改名；停用部门冻结，重新启用后再改（ADR-0018 第 1.3 节）。同名请求幂等返回且不写重复审计。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param name 新名称
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 更新后的部门
     */
    @Transactional
    public DepartmentResponse renameDepartment(
            TenantId tenantId, UUID departmentId, String name, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        String departmentName = Validation.requireText(name, "name", 1, 100);
        DepartmentRecord department = requireDepartment(tenantId, departmentId);
        if (department.status() == DepartmentStatus.DISABLED) {
            throw new DepartmentConflictException("department is disabled");
        }
        if (department.name().equals(departmentName)) {
            return DepartmentResponse.from(department);
        }
        if (departmentRepository.existsByName(tenantId, departmentName)) {
            throw new DepartmentConflictException("department name already exists");
        }
        int updated;
        try {
            updated = departmentRepository.updateName(tenantId, departmentId, departmentName, clock.instant());
        } catch (DuplicateKeyException exception) {
            // 并发改名到同一名称时由数据库唯一键兜底。
            throw new DepartmentConflictException("department name already exists");
        }
        if (updated == 0) {
            // 防御：部门在事务内被并发停用时，必须按“停用部门不可改名”处理，而不是静默成功。
            throw new DepartmentConflictException("department is disabled");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("name", departmentName);
        details.put("previousName", department.name());
        appendAudit(actor, AuditActions.DEPARTMENT_RENAMED, departmentId, meta, details);
        return loadDepartment(tenantId, departmentId);
    }

    /**
     * 停用目标租户部门；幂等，仅在状态真正变化时写审计。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 更新后的部门
     */
    @Transactional
    public DepartmentResponse disableDepartment(
            TenantId tenantId, UUID departmentId, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        DepartmentRecord department = requireDepartment(tenantId, departmentId);
        if (department.status() == DepartmentStatus.DISABLED) {
            return DepartmentResponse.from(department);
        }
        Instant now = clock.instant();
        departmentRepository.updateStatus(tenantId, departmentId, DepartmentStatus.DISABLED, now, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("status", DepartmentStatus.DISABLED.name());
        details.put("memberCount", departmentRepository.countActiveMembers(tenantId, departmentId));
        appendAudit(actor, AuditActions.DEPARTMENT_DISABLED, departmentId, meta, details);
        return loadDepartment(tenantId, departmentId);
    }

    /**
     * 重新启用目标租户部门；幂等，仅在状态真正变化时写审计。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 更新后的部门
     */
    @Transactional
    public DepartmentResponse enableDepartment(
            TenantId tenantId, UUID departmentId, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        DepartmentRecord department = requireDepartment(tenantId, departmentId);
        if (department.status() == DepartmentStatus.ACTIVE) {
            return DepartmentResponse.from(department);
        }
        Instant now = clock.instant();
        departmentRepository.updateStatus(tenantId, departmentId, DepartmentStatus.ACTIVE, null, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("status", DepartmentStatus.ACTIVE.name());
        details.put("memberCount", departmentRepository.countActiveMembers(tenantId, departmentId));
        appendAudit(actor, AuditActions.DEPARTMENT_ENABLED, departmentId, meta, details);
        return loadDepartment(tenantId, departmentId);
    }

    /**
     * 分页列出目标租户部门成员。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param page 分页参数
     * @return 成员分页
     */
    @Transactional(readOnly = true)
    public DepartmentMemberPageResponse listMembers(TenantId tenantId, UUID departmentId, PageQuery page) {
        requireActiveTenant(tenantId);
        requireDepartment(tenantId, departmentId);
        long total = departmentRepository.countMembers(tenantId, departmentId);
        List<DepartmentMemberResponse> items =
                departmentRepository.listMembers(tenantId, departmentId, page.size(), page.offset()).stream()
                        .map(DepartmentMemberResponse::from)
                        .toList();
        return new DepartmentMemberPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 把目标租户账号加入部门。
     *
     * <p>单主部门：账号必须启用且未分配部门；已在其他部门或本部门都返回 409，且不回显对方部门信息。
     * 跨租户账号按不存在处理，返回统一 404。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 加入后的成员
     */
    @Transactional
    public DepartmentMemberResponse addMember(
            TenantId tenantId, UUID departmentId, UUID userId, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        requireDepartment(tenantId, departmentId);
        DepartmentUserRecord user = requireUser(tenantId, userId);
        if (user.status() != UserStatus.ACTIVE) {
            throw new DepartmentConflictException("member account is not active");
        }
        if (user.departmentId() != null) {
            if (user.departmentId().equals(departmentId)) {
                throw new DepartmentConflictException("member already belongs to this department");
            }
            throw new DepartmentConflictException("member already belongs to another department");
        }
        int updated = departmentRepository.assignDepartment(tenantId, departmentId, userId, clock.instant());
        if (updated == 0) {
            // 并发加入时由条件更新兜底，避免两个部门同时认领同一账号。
            throw new DepartmentConflictException("member already belongs to a department");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("userId", userId.toString());
        appendAudit(actor, AuditActions.DEPARTMENT_MEMBER_ADDED, departmentId, meta, details);
        return new DepartmentMemberResponse(user.id(), user.username(), user.displayName(), user.status());
    }

    /**
     * 把账号移出部门；不存在时幂等返回 204。
     *
     * <p>移出当前负责人必须先移交（409）。成员变更与清理本部门 CUSTOM 空间成员行在同一事务内完成，
     * 避免重新加入部门后恢复旧共享（ADR-0018 第 2.2 节）。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     */
    @Transactional
    public void removeMember(
            TenantId tenantId, UUID departmentId, UUID userId, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        DepartmentRecord department = requireDepartment(tenantId, departmentId);
        if (userId.equals(department.managerUserId())) {
            throw new DepartmentConflictException("department manager must be transferred before removal");
        }
        Instant now = clock.instant();
        int cleared = departmentRepository.clearDepartment(tenantId, departmentId, userId, now);
        int removedWorkspaceMembers = departmentRepository.deleteCustomWorkspaceMembers(tenantId, departmentId, userId);
        if (cleared == 0 && removedWorkspaceMembers == 0) {
            // 幂等：本来就不是成员且没有残留共享行，不写重复审计。
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("userId", userId.toString());
        details.put("removedWorkspaceMembers", removedWorkspaceMembers);
        appendAudit(actor, AuditActions.DEPARTMENT_MEMBER_REMOVED, departmentId, meta, details);
    }

    /**
     * 任命或移交部门负责人。
     *
     * <p>目标必须是本部门当前启用成员；同一人重复提交幂等返回且不写重复审计；一次移交只写一条
     * {@code department.manager.changed}（ADR-0018 第 5 节）。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 目标负责人账号 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 更新后的部门
     */
    @Transactional
    public DepartmentResponse transferManager(
            TenantId tenantId, UUID departmentId, UUID userId, RicconPrincipal actor, RequestMeta meta) {
        requireActiveTenant(tenantId);
        DepartmentRecord department = requireDepartment(tenantId, departmentId);
        DepartmentUserRecord user = requireUser(tenantId, userId);
        if (user.status() != UserStatus.ACTIVE || !departmentId.equals(user.departmentId())) {
            throw new DepartmentConflictException("target user must be an active member of the department");
        }
        if (userId.equals(department.managerUserId())) {
            return DepartmentResponse.from(department);
        }
        departmentRepository.updateManager(tenantId, departmentId, userId, clock.instant());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenantId.value());
        details.put("departmentId", departmentId.toString());
        details.put("managerUserId", userId.toString());
        if (department.managerUserId() != null) {
            details.put("previousManagerUserId", department.managerUserId().toString());
        }
        appendAudit(actor, AuditActions.DEPARTMENT_MANAGER_CHANGED, departmentId, meta, details);
        return loadDepartment(tenantId, departmentId);
    }

    /**
     * 校验目标租户存在且启用。
     *
     * @param tenantId 目标租户
     * @return 租户元数据
     * @throws NotFoundException 租户不存在
     * @throws ConflictException 租户已暂停
     */
    private TenantRecord requireActiveTenant(TenantId tenantId) {
        TenantRecord tenant =
                adminRepository.findTenant(tenantId.value()).orElseThrow(() -> new NotFoundException("tenant"));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new ConflictException("target tenant is not active");
        }
        return tenant;
    }

    /**
     * 校验部门存在且属于目标租户。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 部门快照
     * @throws NotFoundException 部门不存在或属于其他租户
     */
    private DepartmentRecord requireDepartment(TenantId tenantId, UUID departmentId) {
        return departmentRepository
                .findDepartment(tenantId, departmentId)
                .orElseThrow(() -> new NotFoundException("department"));
    }

    /**
     * 校验账号存在且属于目标租户。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 账号快照
     * @throws NotFoundException 账号不存在或属于其他租户
     */
    private DepartmentUserRecord requireUser(TenantId tenantId, UUID userId) {
        return departmentRepository.findUser(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
    }

    /**
     * 重新读取部门并映射响应。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 部门响应
     */
    private DepartmentResponse loadDepartment(TenantId tenantId, UUID departmentId) {
        return DepartmentResponse.from(requireDepartment(tenantId, departmentId));
    }

    /**
     * 写部门审计；事件租户为操作者租户，目标租户只进入 details。
     *
     * @param actor 操作者
     * @param action 动作名
     * @param departmentId 部门 ID，作为 target_id
     * @param meta 请求元数据
     * @param details 白名单字段
     */
    private void appendAudit(
            RicconPrincipal actor, String action, UUID departmentId, RequestMeta meta, Map<String, Object> details) {
        String auditTenant = actor.tenantId();
        TenantContext.runWithTenant(
                TenantId.of(auditTenant),
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        auditTenant,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        action,
                        AuditTargetType.DEPARTMENT,
                        departmentId.toString(),
                        AuditOutcome.SUCCESS,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        details)));
    }
}
