package com.riccon.api.admin.service;

import com.riccon.api.admin.repository.AdminRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.service.AuthService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.identity.dto.UserCreateRequest;
import com.riccon.api.identity.dto.UserPageResponse;
import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户内账号与角色管理。
 *
 * <p>同一实现服务两类入口：
 *
 * <ul>
 *   <li>租户管理员：目标租户固定为当前 {@link TenantContext}，不能跨租户；
 *   <li>平台管理员：目标租户来自路径参数，审计租户仍为 {@code platform}，目标租户写入 details。
 * </ul>
 *
 * <p>角色只能是 M02 内置系统角色；授予者不得授予超出自身的角色（平台管理员拥有平台级特权，不在此限）。
 */
@Service
public class TenantUserAdminService {

    /** 登录名格式，与 OpenAPI {@code UserCreateRequest.username} 一致。 */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$");

    /** 平台管理仓储。 */
    private final AdminRepository adminRepository;

    /** 认证服务，用于计算授予者权限。 */
    private final AuthService authService;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 密码编码器。 */
    private final PasswordEncoder passwordEncoder;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建账号管理服务。
     *
     * @param adminRepository 平台管理仓储
     * @param authService 认证服务
     * @param auditService 审计写入
     * @param passwordEncoder 密码编码器
     * @param clock 可注入时钟
     */
    public TenantUserAdminService(
            AdminRepository adminRepository,
            AuthService authService,
            AuditService auditService,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository");
        this.authService = Objects.requireNonNull(authService, "authService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 分页列出目标租户账号。
     *
     * @param tenantId 目标租户
     * @param page 分页参数
     * @return 账号分页
     */
    @Transactional(readOnly = true)
    public UserPageResponse listUsers(TenantId tenantId, PageQuery page) {
        requireTenant(tenantId);
        long total = adminRepository.countUsers(tenantId);
        List<UserAccount> users = adminRepository.listUsers(tenantId, page.size(), page.offset());
        Map<UUID, List<String>> roles = adminRepository.rolesForUsers(
                tenantId, users.stream().map(UserAccount::id).toList());
        List<UserSummaryResponse> items = users.stream()
                .map(user -> toSummary(user, roles.getOrDefault(user.id(), List.of())))
                .toList();
        return new UserPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 在目标租户创建账号。
     *
     * @param tenantId 目标租户
     * @param request 创建请求
     * @param actor 操作者
     * @param platformActor 是否为平台管理员入口
     * @param meta 请求元数据
     * @return 新账号摘要
     */
    @Transactional
    public UserSummaryResponse createUser(
            TenantId tenantId,
            UserCreateRequest request,
            RicconPrincipal actor,
            boolean platformActor,
            RequestMeta meta) {
        requireTenant(tenantId);
        String username = Validation.requirePattern(request.username(), "username", USERNAME, 100);
        String displayName = Validation.requireText(request.displayName(), "displayName", 1, 200);
        String password = requirePassword(request.password());
        List<String> roles = Validation.requireRoles(request.roles(), "roles");
        Map<String, RoleDefinition> definitions = resolveTenantRoles(roles);
        if (!platformActor) {
            ensureCanGrant(actor, definitions.keySet());
        }
        if (adminRepository.findUserByUsername(tenantId, username).isPresent()) {
            throw new ConflictException("username already exists in tenant");
        }

        Instant now = clock.instant();
        UserAccount user = new UserAccount(
                UuidV7.generate(now),
                tenantId.value(),
                username,
                displayName,
                null,
                UserStatus.ACTIVE,
                AuthSource.LOCAL,
                null,
                now);
        String passwordHash = passwordEncoder.encode(password);
        try {
            adminRepository.insertUser(tenantId, user, passwordHash);
            adminRepository.replaceUserRoles(
                    tenantId, user.id(), roleIds(tenantId, definitions.keySet()), actor.userId());
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("username already exists in tenant");
        }
        for (String role : sorted(roles)) {
            appendAudit(
                    actor,
                    AuditActions.USER_CREATED,
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditOutcome.SUCCESS,
                    meta,
                    Map.of("role", role, "targetTenantId", tenantId.value()));
        }
        return toSummary(user, sorted(roles));
    }

    /**
     * 启用或禁用账号。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param status 目标状态
     * @param actor 操作者
     * @param platformActor 是否为平台管理员入口
     * @param meta 请求元数据
     * @return 更新后的账号摘要
     */
    @Transactional
    public UserSummaryResponse updateStatus(
            TenantId tenantId, UUID userId, UserStatus status, RicconPrincipal actor, RequestMeta meta) {
        Validation.requireNonNull(status, "status");
        UserAccount user = adminRepository.findUser(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        adminRepository.updateUserStatus(tenantId, userId, status, clock.instant());
        List<String> roles = adminRepository.rolesForUser(tenantId, userId);
        appendAudit(
                actor,
                AuditActions.USER_DISABLED,
                AuditTargetType.USER,
                userId.toString(),
                AuditOutcome.SUCCESS,
                meta,
                Map.of("enabled", status == UserStatus.ACTIVE, "targetTenantId", tenantId.value()));
        UserAccount updated = new UserAccount(
                user.id(),
                user.tenantId(),
                user.username(),
                user.displayName(),
                user.email(),
                status,
                user.authSource(),
                user.lastLoginAt(),
                user.createdAt());
        return toSummary(updated, roles);
    }

    /**
     * 替换账号角色，并按授予/撤销分别写审计。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param roles 新角色码
     * @param actor 操作者
     * @param platformActor 是否为平台管理员入口
     * @param meta 请求元数据
     * @return 更新后的账号摘要
     */
    @Transactional
    public UserSummaryResponse replaceRoles(
            TenantId tenantId,
            UUID userId,
            List<String> roles,
            RicconPrincipal actor,
            boolean platformActor,
            RequestMeta meta) {
        List<String> requested = Validation.requireRoles(roles, "roles");
        Map<String, RoleDefinition> definitions = resolveTenantRoles(requested);
        if (!platformActor) {
            ensureCanGrant(actor, definitions.keySet());
        }
        UserAccount user = adminRepository.findUser(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        List<String> existing = adminRepository.rolesForUser(tenantId, userId);
        Set<String> requestedSet = new LinkedHashSet<>(requested);
        Set<String> existingSet = new HashSet<>(existing);

        adminRepository.replaceUserRoles(tenantId, userId, roleIds(tenantId, requestedSet), actor.userId());

        for (String role : sorted(requestedSet)) {
            if (!existingSet.contains(role)) {
                appendAudit(
                        actor,
                        AuditActions.PERMISSION_CHANGED,
                        AuditTargetType.USER,
                        userId.toString(),
                        AuditOutcome.SUCCESS,
                        meta,
                        Map.of(
                                "targetUserId",
                                userId.toString(),
                                "role",
                                role,
                                "change",
                                "grant",
                                "targetTenantId",
                                tenantId.value()));
            }
        }
        for (String role : sorted(existingSet)) {
            if (!requestedSet.contains(role)) {
                appendAudit(
                        actor,
                        AuditActions.PERMISSION_CHANGED,
                        AuditTargetType.USER,
                        userId.toString(),
                        AuditOutcome.SUCCESS,
                        meta,
                        Map.of(
                                "targetUserId",
                                userId.toString(),
                                "role",
                                role,
                                "change",
                                "revoke",
                                "targetTenantId",
                                tenantId.value()));
            }
        }
        return toSummary(user, sorted(requestedSet));
    }

    /**
     * 校验目标租户存在。
     *
     * @param tenantId 目标租户
     * @return 租户元数据
     */
    @Transactional(readOnly = true)
    public TenantRecord requireTenant(TenantId tenantId) {
        return adminRepository.findTenant(tenantId.value()).orElseThrow(() -> new NotFoundException("tenant"));
    }

    /**
     * 校验角色码属于 M02 内置租户角色。
     *
     * @param roles 角色码
     * @return 角色码到定义的映射
     */
    private Map<String, RoleDefinition> resolveTenantRoles(List<String> roles) {
        Map<String, RoleDefinition> definitions = new LinkedHashMap<>();
        for (String role : roles) {
            RoleDefinition definition = RoleDefinitions.tenantRole(role);
            if (definition == null) {
                throw new BadRequestException("unknown role: " + role);
            }
            definitions.put(role, definition);
        }
        return definitions;
    }

    /**
     * 校验授予者拥有待授予角色的全部权限。
     *
     * @param actor 操作者
     * @param roleCodes 待授予角色码
     */
    private void ensureCanGrant(RicconPrincipal actor, Set<String> roleCodes) {
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        for (String roleCode : roleCodes) {
            RoleDefinition definition = RoleDefinitions.tenantRole(roleCode);
            if (definition == null) {
                throw new BadRequestException("unknown role: " + roleCode);
            }
            for (String permission : definition.permissions()) {
                if (!currentUser.hasPermission(permission)) {
                    throw new ForbiddenException(permission);
                }
            }
        }
    }

    /**
     * 解析角色 ID，缺失说明租户初始化不完整。
     *
     * @param tenantId 目标租户
     * @param roleCodes 角色码
     * @return 角色 ID 列表
     */
    private List<UUID> roleIds(TenantId tenantId, Set<String> roleCodes) {
        List<UUID> roleIds = new ArrayList<>();
        for (String code : sorted(roleCodes)) {
            roleIds.add(adminRepository
                    .findRoleId(tenantId, code)
                    .orElseThrow(() -> new ConflictException("role mapping is missing for tenant")));
        }
        return roleIds;
    }

    /**
     * 校验并返回密码；不做 trim。
     *
     * @param password 明文密码
     * @return 原密码
     */
    private String requirePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new BadRequestException("password length must be between 12 and 200");
        }
        return password;
    }

    /**
     * 写审计；事件租户为操作者租户，目标租户只进入 details。
     *
     * @param actor 操作者
     * @param targetTenant 目标租户
     * @param action 动作
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param outcome 结果
     * @param meta 请求元数据
     * @param details 白名单字段
     */
    private void appendAudit(
            RicconPrincipal actor,
            String action,
            AuditTargetType targetType,
            String targetId,
            AuditOutcome outcome,
            RequestMeta meta,
            Map<String, Object> details) {
        String auditTenant = actor.tenantId();
        TenantContext.runWithTenant(
                TenantId.of(auditTenant),
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        auditTenant,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        action,
                        targetType,
                        targetId,
                        outcome,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        details)));
    }

    /**
     * 映射账号摘要。
     *
     * @param user 账号
     * @param roles 角色码
     * @return 摘要
     */
    private static UserSummaryResponse toSummary(UserAccount user, List<String> roles) {
        return new UserSummaryResponse(
                user.id(),
                user.username(),
                user.displayName(),
                user.email(),
                user.status(),
                sorted(roles),
                user.lastLoginAt(),
                user.createdAt());
    }

    /**
     * 按字典序排序，保证响应稳定。
     *
     * @param values 原集合
     * @return 排序后的不可变列表
     */
    private static List<String> sorted(java.util.Collection<String> values) {
        return values.stream().sorted().toList();
    }
}
