package com.riccon.api.workspace.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.service.AuthService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.department.domain.DepartmentConflictException;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.organization.OrganizationDirectory;
import com.riccon.api.organization.domain.DepartmentMemberSnapshot;
import com.riccon.api.organization.domain.DepartmentSnapshot;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.api.workspace.dto.DepartmentWorkspaceCreateRequest;
import com.riccon.api.workspace.dto.WorkspaceCreateRequest;
import com.riccon.api.workspace.dto.WorkspaceMemberPageResponse;
import com.riccon.api.workspace.dto.WorkspaceMemberResponse;
import com.riccon.api.workspace.dto.WorkspacePageResponse;
import com.riccon.api.workspace.dto.WorkspaceResponse;
import com.riccon.api.workspace.dto.WorkspaceUpdateRequest;
import com.riccon.api.workspace.repository.WorkspaceRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.organization.DepartmentStatus;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
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
 * 工作空间读取、创建、可见性与 CUSTOM 成员管理。
 *
 * <p>M03 的空间可见性由 {@code WorkspaceJdbc} 的单一 SQL 谓词执行；本类只做授权能力判定与业务编排。
 * {@link #requireAccessible} 仍是内容访问的唯一入口，签名保持不变供会话、文件、报告与运行复用；
 * {@link #requireManageable} 负责管理面：租户管理员可管理任意团队空间，部门负责人只管理本部门空间，
 * 对不可见空间返回 404、可见但无管理能力返回 403（ADR-0018 第 3.1 节）。部门、负责人和成员关系一律通过
 * {@link OrganizationDirectory} 读取，不在本包复制部门 SQL；访问判定每请求查库，不引入跨请求缓存。
 */
@Service
public class WorkspaceService {

    /** 个人空间名称后缀。 */
    private static final String PERSONAL_SUFFIX = " 的个人空间";

    /** 名称最大长度，与 V2 列定义一致。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** 说明最大长度。 */
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    /** 工作空间仓储。 */
    private final WorkspaceRepository workspaceRepository;

    /** 组织目录只读接口，由 B2 实现。 */
    private final OrganizationDirectory organizationDirectory;

    /** 认证服务，用于读取当前角色快照。 */
    private final AuthService authService;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建工作空间服务。
     *
     * @param workspaceRepository 工作空间仓储
     * @param organizationDirectory 组织目录只读接口
     * @param authService 认证服务
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationDirectory organizationDirectory,
            AuthService authService,
            AuditService auditService,
            Clock clock) {
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
        this.organizationDirectory = Objects.requireNonNull(organizationDirectory, "organizationDirectory");
        this.authService = Objects.requireNonNull(authService, "authService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 列出当前员工可见的工作空间，并在首次访问时创建个人空间。
     *
     * @param actor 当前员工
     * @param page 分页参数
     * @param kind 可选类型过滤
     * @return 工作空间分页
     */
    @Transactional
    public WorkspacePageResponse list(RicconPrincipal actor, PageQuery page, WorkspaceKind kind) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ensurePersonalWorkspace(tenantId, actor);
        long total = workspaceRepository.countAccessible(tenantId, actor.userId(), kind);
        List<WorkspaceResponse> items =
                workspaceRepository.listAccessible(tenantId, actor.userId(), kind, page.size(), page.offset()).stream()
                        .map(WorkspaceResponse::from)
                        .toList();
        return new WorkspacePageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 创建团队工作空间（{@code POST /workspaces}）。
     *
     * <p>M03 起必须提供启用部门，可见性只允许 {@code DEPT}/{@code CUSTOM}；历史 {@code TENANT} 不可新建。
     * 该入口保留 {@code workspace.write} 的租户管理员语义（ADR-0018 第 2.3/7.1 节）。
     *
     * @param actor 当前员工
     * @param request 创建请求
     * @param meta 审计所需的请求元数据
     * @return 新工作空间
     */
    @Transactional
    public WorkspaceResponse createTeam(RicconPrincipal actor, WorkspaceCreateRequest request, RequestMeta meta) {
        Validation.requireBody(request);
        UUID departmentId = Validation.requireNonNull(request.departmentId(), "departmentId");
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        if (!currentUser.roles().contains(RoleCodes.TENANT_ADMIN)) {
            throw new ForbiddenException(PermissionCodes.WORKSPACE_WRITE);
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        DepartmentSnapshot department = organizationDirectory
                .findDepartment(tenantId, departmentId)
                .orElseThrow(() -> new NotFoundException("department"));
        requireActiveDepartment(department);
        return createWorkspace(
                actor, tenantId, department, request.name(), request.description(), request.visibility(), meta);
    }

    /**
     * 在路径指定部门下创建团队空间（{@code POST /departments/{departmentId}/workspaces}）。
     *
     * <p>部门来自路径，避免负责人伪造归属；租户管理员可对任意启用部门创建，部门负责人只能对本部门创建，
     * 其他部门负责人统一 404（ADR-0018 第 2.3/7.1 节）。
     *
     * @param actor 当前员工
     * @param departmentId 路径部门 ID
     * @param request 创建请求
     * @param meta 审计所需的请求元数据
     * @return 新工作空间
     */
    @Transactional
    public WorkspaceResponse createTeamInDepartment(
            RicconPrincipal actor, UUID departmentId, DepartmentWorkspaceCreateRequest request, RequestMeta meta) {
        Validation.requireBody(request);
        TenantId tenantId = TenantId.of(actor.tenantId());
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        DepartmentSnapshot department = requireDepartmentManagement(currentUser, actor, tenantId, departmentId);
        return createWorkspace(
                actor, tenantId, department, request.name(), request.description(), request.visibility(), meta);
    }

    /**
     * 读取对当前员工可见的工作空间。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @return 工作空间
     * @throws NotFoundException 不存在、属于其他租户或不可见
     */
    @Transactional(readOnly = true)
    public WorkspaceResponse get(RicconPrincipal actor, UUID workspaceId) {
        return WorkspaceResponse.from(requireAccessible(TenantId.of(actor.tenantId()), workspaceId, actor.userId()));
    }

    /**
     * 校验工作空间对指定员工可见（唯一内容访问入口）。
     *
     * <p>签名与 M02 保持一致，供会话、文件、报告、产物与运行详情复用。对象不存在、跨租户、跨部门、
     * 个人空间他人统一 404，不泄露存在性（ADR-0018 第 3.1 节）。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 工作空间快照
     * @throws NotFoundException 不存在或不可见
     */
    @Transactional(readOnly = true)
    public WorkspaceRecord requireAccessible(TenantId tenantId, UUID workspaceId, UUID userId) {
        return workspaceRepository
                .findAccessible(tenantId, workspaceId, userId)
                .orElseThrow(() -> new NotFoundException("workspace"));
    }

    /**
     * 列出部门空间元数据，供管理界面使用。
     *
     * <p>只有租户管理员或本部门负责人可读取；部门必须启用。返回所有归属该部门的 ACTIVE 空间元数据，
     * 不返回会话、文件、报告或知识库内容（ADR-0018 第 2.3/7.1 节）。
     *
     * @param actor 当前员工
     * @param departmentId 部门 ID
     * @param page 分页参数
     * @return 空间分页
     */
    @Transactional(readOnly = true)
    public WorkspacePageResponse listDepartmentWorkspaces(RicconPrincipal actor, UUID departmentId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        requireDepartmentManagement(currentUser, actor, tenantId, departmentId);
        long total = workspaceRepository.countByDepartment(tenantId, departmentId);
        List<WorkspaceResponse> items =
                workspaceRepository.listByDepartment(tenantId, departmentId, page.size(), page.offset()).stream()
                        .map(WorkspaceResponse::from)
                        .toList();
        return new WorkspacePageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 修改空间名称、说明或可见性。
     *
     * <p>租户管理员可修改任意团队空间，部门负责人只修改本部门空间；历史 {@code TENANT} 只允许改名/说明，
     * 不可转换可见性。{@code DEPT} 与 {@code CUSTOM} 互转时清空旧显式成员，避免重新加入部门后恢复旧共享
     * （ADR-0018 第 2.2 节）。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param request 更新请求
     * @param meta 审计所需的请求元数据
     * @return 更新后的工作空间
     */
    @Transactional
    public WorkspaceResponse update(
            RicconPrincipal actor, UUID workspaceId, WorkspaceUpdateRequest request, RequestMeta meta) {
        Validation.requireBody(request);
        if (request.name() == null && request.description() == null && request.visibility() == null) {
            throw new BadRequestException("at least one of name, description or visibility is required");
        }
        WorkspaceRecord workspace = requireManageable(actor, workspaceId);
        TenantId tenantId = TenantId.of(actor.tenantId());
        Instant now = clock.instant();

        String newName = request.name() == null
                ? workspace.name()
                : Validation.requireText(request.name(), "name", 1, MAX_NAME_LENGTH);
        String newDescription =
                request.description() == null ? workspace.description() : normalizeDescription(request.description());
        boolean metadataChanged =
                !newName.equals(workspace.name()) || !Objects.equals(newDescription, workspace.description());
        if (metadataChanged) {
            workspaceRepository.updateMetadata(tenantId, workspaceId, newName, newDescription, now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("workspaceId", workspaceId.toString());
            if (!newName.equals(workspace.name())) {
                details.put("name", newName);
                details.put("previousName", workspace.name());
            }
            appendAudit(actor, AuditActions.WORKSPACE_UPDATED, workspaceId, details, meta, now);
        }

        WorkspaceVisibility requested = request.visibility();
        if (requested != null) {
            applyVisibilityChange(actor, tenantId, workspace, requested, meta, now);
        }
        return WorkspaceResponse.from(workspaceRepository
                .findById(tenantId, workspaceId)
                .orElseThrow(() -> new NotFoundException("workspace")));
    }

    /**
     * 判定当前员工对空间的管理能力。
     *
     * <p>租户管理员可管理任意团队空间；部门负责人只管理本部门且部门启用的空间。不可见空间返回 404，
     * 可见但无管理能力返回 403，避免用 403 泄露其他部门空间的存在（ADR-0018 第 3.1 节）。个人空间不提供
     * 管理能力：所有者得到 403，其他人得到 404。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @return 空间快照
     * @throws NotFoundException 不存在、跨租户或不可见
     * @throws ForbiddenException 可见但无管理能力
     */
    @Transactional(readOnly = true)
    public WorkspaceRecord requireManageable(RicconPrincipal actor, UUID workspaceId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkspaceRecord workspace = workspaceRepository
                .findById(tenantId, workspaceId)
                .orElseThrow(() -> new NotFoundException("workspace"));
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        if (workspace.kind() == WorkspaceKind.TEAM && currentUser.roles().contains(RoleCodes.TENANT_ADMIN)) {
            return workspace;
        }
        if (workspace.kind() == WorkspaceKind.TEAM
                && workspace.departmentId() != null
                && isActiveDepartmentManager(tenantId, actor.userId(), workspace.departmentId())) {
            return workspace;
        }
        if (workspaceRepository
                .findAccessible(tenantId, workspaceId, actor.userId())
                .isPresent()) {
            throw new ForbiddenException(PermissionCodes.WORKSPACE_READ);
        }
        throw new NotFoundException("workspace");
    }

    /**
     * 分页列出 CUSTOM 空间显式成员。
     *
     * <p>{@code DEPT}/{@code TENANT} 空间没有显式成员，返回空页；个人空间不支持成员管理，按 400/404 拒绝
     * （ADR-0018 第 2.2/7.3 节）。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param page 分页参数
     * @return 成员分页
     */
    @Transactional(readOnly = true)
    public WorkspaceMemberPageResponse listMembers(RicconPrincipal actor, UUID workspaceId, PageQuery page) {
        WorkspaceRecord workspace = requireManageable(actor, workspaceId);
        if (workspace.effectiveVisibility() != WorkspaceVisibility.CUSTOM) {
            return new WorkspaceMemberPageResponse(page.page(), page.size(), 0, 0, List.of());
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        long total = workspaceRepository.countMembers(tenantId, workspaceId);
        List<WorkspaceMemberResponse> items =
                workspaceRepository.listMembers(tenantId, workspaceId, page.size(), page.offset()).stream()
                        .map(WorkspaceMemberResponse::from)
                        .toList();
        return new WorkspaceMemberPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 加入 CUSTOM 空间显式成员。
     *
     * <p>目标账号必须是空间所属部门的当前启用成员；跨部门、跨租户或停用账号统一 404，不泄露对方部门信息。
     * 重复加入返回 409 {@code RICCON-DEPT-409}（ADR-0018 第 2.2/7.3 节）。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param userId 目标账号 ID
     * @param meta 审计所需的请求元数据
     * @return 新成员
     */
    @Transactional
    public WorkspaceMemberResponse addMember(RicconPrincipal actor, UUID workspaceId, UUID userId, RequestMeta meta) {
        WorkspaceRecord workspace = requireCustomWorkspace(actor, workspaceId);
        TenantId tenantId = TenantId.of(actor.tenantId());
        UUID departmentId = Validation.requireNonNull(workspace.departmentId(), "departmentId");
        if (!organizationDirectory.isActiveMember(tenantId, departmentId, userId)) {
            throw new NotFoundException("user");
        }
        if (workspaceRepository.memberExists(tenantId, workspaceId, userId)) {
            throw new DepartmentConflictException("workspace member already exists");
        }
        Instant now = clock.instant();
        try {
            workspaceRepository.insertMember(tenantId, workspaceId, userId, actor.userId(), now);
        } catch (DuplicateKeyException exception) {
            // 并发重复加入由复合主键兜底，统一转成稳定冲突码。
            throw new DepartmentConflictException("workspace member already exists");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceId", workspaceId.toString());
        details.put("userId", userId.toString());
        appendAudit(actor, AuditActions.WORKSPACE_MEMBER_ADDED, workspaceId, details, meta, now);
        DepartmentMemberSnapshot member = organizationDirectory.listActiveMembers(tenantId, departmentId).stream()
                .filter(candidate -> candidate.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("user"));
        return new WorkspaceMemberResponse(member.userId(), member.username(), member.displayName(), now);
    }

    /**
     * 移出 CUSTOM 空间显式成员。
     *
     * <p>幂等：成员不存在返回 204 且不写审计；非 CUSTOM 空间拒绝。移出后下一次请求立即失去访问，
     * 不需要失效缓存（ADR-0018 第 3.3 节）。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param userId 目标账号 ID
     * @param meta 审计所需的请求元数据
     */
    @Transactional
    public void removeMember(RicconPrincipal actor, UUID workspaceId, UUID userId, RequestMeta meta) {
        requireCustomWorkspace(actor, workspaceId);
        TenantId tenantId = TenantId.of(actor.tenantId());
        int removed = workspaceRepository.deleteMember(tenantId, workspaceId, userId);
        if (removed == 0) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceId", workspaceId.toString());
        details.put("userId", userId.toString());
        appendAudit(actor, AuditActions.WORKSPACE_MEMBER_REMOVED, workspaceId, details, meta, clock.instant());
    }

    /**
     * 校验空间可管理且可见性为 {@code CUSTOM}。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @return 空间快照
     */
    private WorkspaceRecord requireCustomWorkspace(RicconPrincipal actor, UUID workspaceId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        WorkspaceRecord raw = workspaceRepository
                .findById(tenantId, workspaceId)
                .orElseThrow(() -> new NotFoundException("workspace"));
        if (raw.kind() == WorkspaceKind.PERSONAL) {
            // 个人空间不使用 workspace_member：所有者按参数错误拒绝，其他人按不存在处理，不泄露空间归属。
            if (workspaceRepository
                    .findAccessible(tenantId, workspaceId, actor.userId())
                    .isPresent()) {
                throw new BadRequestException("personal workspace does not support explicit members");
            }
            throw new NotFoundException("workspace");
        }
        WorkspaceRecord workspace = requireManageable(actor, workspaceId);
        if (workspace.effectiveVisibility() != WorkspaceVisibility.CUSTOM) {
            throw new BadRequestException("explicit members are only supported for CUSTOM workspaces");
        }
        return workspace;
    }

    /**
     * 应用可见性变更并写审计。
     *
     * @param actor 当前员工
     * @param tenantId 当前租户
     * @param workspace 变更前快照
     * @param requested 目标可见性
     * @param meta 审计所需的请求元数据
     * @param now 变更时间
     */
    private void applyVisibilityChange(
            RicconPrincipal actor,
            TenantId tenantId,
            WorkspaceRecord workspace,
            WorkspaceVisibility requested,
            RequestMeta meta,
            Instant now) {
        WorkspaceVisibility current = workspace.effectiveVisibility();
        if (workspace.kind() == WorkspaceKind.PERSONAL
                || current == WorkspaceVisibility.TENANT
                || current == WorkspaceVisibility.PRIVATE) {
            throw new BadRequestException("visibility of this workspace cannot be changed");
        }
        if (requested == WorkspaceVisibility.TENANT || requested == WorkspaceVisibility.PRIVATE) {
            throw new BadRequestException("only DEPT or CUSTOM visibility is allowed");
        }
        if (requested == current) {
            return;
        }
        // DEPT 与 CUSTOM 互转时清空旧显式成员，避免切回 DEPT 后残留行继续授予访问。
        workspaceRepository.deleteAllMembers(tenantId, workspace.id());
        workspaceRepository.updateVisibility(tenantId, workspace.id(), requested, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceId", workspace.id().toString());
        details.put("visibility", requested.name());
        details.put("previousVisibility", current.name());
        details.put("departmentId", workspace.departmentId().toString());
        appendAudit(actor, AuditActions.WORKSPACE_VISIBILITY_CHANGED, workspace.id(), details, meta, now);
    }

    /**
     * 创建团队空间并写审计。
     *
     * @param actor 当前员工
     * @param tenantId 当前租户
     * @param department 已授权的启用部门
     * @param rawName 原始名称
     * @param rawDescription 原始说明
     * @param requestedVisibility 请求可见性；为空时默认 {@code DEPT}
     * @param meta 审计所需的请求元数据
     * @return 新工作空间
     */
    private WorkspaceResponse createWorkspace(
            RicconPrincipal actor,
            TenantId tenantId,
            DepartmentSnapshot department,
            String rawName,
            String rawDescription,
            WorkspaceVisibility requestedVisibility,
            RequestMeta meta) {
        WorkspaceVisibility visibility = requestedVisibility == null ? WorkspaceVisibility.DEPT : requestedVisibility;
        if (visibility != WorkspaceVisibility.DEPT && visibility != WorkspaceVisibility.CUSTOM) {
            throw new BadRequestException("only DEPT or CUSTOM visibility is allowed");
        }
        String name = Validation.requireText(rawName, "name", 1, MAX_NAME_LENGTH);
        String description = normalizeDescription(rawDescription);
        Instant now = clock.instant();
        WorkspaceRecord workspace = new WorkspaceRecord(
                UuidV7.generate(now),
                WorkspaceKind.TEAM,
                name,
                description,
                actor.userId(),
                department.id(),
                department.name(),
                visibility,
                WorkspaceStatus.ACTIVE,
                now,
                now);
        workspaceRepository.insert(tenantId, null, workspace, actor.userId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceId", workspace.id().toString());
        details.put("departmentId", department.id().toString());
        details.put("visibility", visibility.name());
        appendAudit(actor, AuditActions.WORKSPACE_CREATED, workspace.id(), details, meta, now);
        return WorkspaceResponse.from(workspace);
    }

    /**
     * 校验部门存在、启用，并且当前账号是租户管理员或本部门负责人。
     *
     * @param currentUser 当前授权快照
     * @param actor 当前员工
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @return 启用部门快照
     */
    private DepartmentSnapshot requireDepartmentManagement(
            CurrentUser currentUser, RicconPrincipal actor, TenantId tenantId, UUID departmentId) {
        DepartmentSnapshot department = organizationDirectory
                .findDepartment(tenantId, departmentId)
                .orElseThrow(() -> new NotFoundException("department"));
        if (currentUser.roles().contains(RoleCodes.TENANT_ADMIN)) {
            requireActiveDepartment(department);
            return department;
        }
        if (actor.userId().equals(department.managerUserId())) {
            requireActiveDepartment(department);
            return department;
        }
        boolean ownDepartment = organizationDirectory
                .findDepartmentOfUser(tenantId, actor.userId())
                .map(own -> own.id().equals(departmentId))
                .orElse(false);
        if (ownDepartment) {
            throw new ForbiddenException(PermissionCodes.WORKSPACE_READ);
        }
        throw new NotFoundException("department");
    }

    /**
     * 校验部门启用；停用部门不参与空间创建或管理。
     *
     * @param department 部门快照
     * @throws BadRequestException 部门已停用
     */
    private static void requireActiveDepartment(DepartmentSnapshot department) {
        if (department.status() != DepartmentStatus.ACTIVE) {
            throw new BadRequestException("department is not active");
        }
    }

    /**
     * 判断账号是否为启用部门的当前负责人。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param departmentId 部门 ID
     * @return 是负责人且部门启用时返回 {@code true}
     */
    private boolean isActiveDepartmentManager(TenantId tenantId, UUID userId, UUID departmentId) {
        return organizationDirectory
                .findDepartment(tenantId, departmentId)
                .filter(department -> department.status() == DepartmentStatus.ACTIVE)
                .map(department -> userId.equals(department.managerUserId()))
                .orElse(false);
    }

    /**
     * 确保个人空间存在；并发首次访问时依赖唯一键收敛到同一条记录。
     *
     * @param tenantId 当前租户
     * @param actor 当前员工
     * @return 个人空间
     */
    private WorkspaceRecord ensurePersonalWorkspace(TenantId tenantId, RicconPrincipal actor) {
        return workspaceRepository.findPersonal(tenantId, actor.userId()).orElseGet(() -> {
            Instant now = clock.instant();
            WorkspaceRecord workspace = new WorkspaceRecord(
                    UuidV7.generate(now),
                    WorkspaceKind.PERSONAL,
                    personalName(actor),
                    null,
                    actor.userId(),
                    null,
                    null,
                    WorkspaceVisibility.PRIVATE,
                    WorkspaceStatus.ACTIVE,
                    now,
                    now);
            try {
                workspaceRepository.insert(tenantId, actor.userId(), workspace, actor.userId());
                return workspace;
            } catch (DuplicateKeyException exception) {
                // 两个并发首次请求只有一个插入成功；失败方读取已提交记录，保持每人一个个人空间。
                return workspaceRepository
                        .findPersonal(tenantId, actor.userId())
                        .orElseThrow(
                                () -> new IllegalStateException("personal workspace insert conflicted", exception));
            }
        });
    }

    /**
     * 生成个人空间名称；展示名可能已接近列上限，需截断后再加后缀。
     *
     * @param actor 当前员工
     * @return 不超过 200 字符的名称
     */
    private static String personalName(RicconPrincipal actor) {
        String base = actor.displayName() == null || actor.displayName().isBlank()
                ? actor.username()
                : actor.displayName().strip();
        int maxBaseLength = MAX_NAME_LENGTH - PERSONAL_SUFFIX.length();
        String truncated = base.length() <= maxBaseLength ? base : base.substring(0, maxBaseLength);
        return truncated + PERSONAL_SUFFIX;
    }

    /**
     * 标准化可选说明；空白值统一为 {@code null}，避免列表展示空串。
     *
     * @param description 原始说明
     * @return 去空白后的说明或 {@code null}
     * @throws BadRequestException 超过 1000 字符
     */
    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return Validation.requireText(trimmed, "description", 1, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * 写空间审计事件；事件租户为操作者租户，details 只含白名单字段。
     *
     * @param actor 当前员工
     * @param action 审计动作
     * @param workspaceId 工作空间 ID
     * @param details 白名单详情
     * @param meta 请求元数据；创建流程可为空
     * @param occurredAt 事件时间
     */
    private void appendAudit(
            RicconPrincipal actor,
            String action,
            UUID workspaceId,
            Map<String, Object> details,
            RequestMeta meta,
            Instant occurredAt) {
        String traceId = meta == null ? "unknown" : meta.traceId();
        String requestId = meta == null ? null : meta.requestId();
        auditService.append(AuditEvent.create(
                occurredAt,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                action,
                AuditTargetType.WORKSPACE,
                workspaceId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details));
    }
}
