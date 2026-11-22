package com.riccon.api.admin.service;

import com.riccon.api.admin.identity.OidcAccountIdentity;
import com.riccon.api.admin.identity.OidcBindingConflictException;
import com.riccon.api.admin.identity.OidcBindingInvalidException;
import com.riccon.api.admin.identity.OidcIdentityHashes;
import com.riccon.api.admin.identity.repository.OidcBindingRepository;
import com.riccon.api.admin.repository.AdminRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.service.AuthService;
import com.riccon.api.auth.service.IdentityProviderResolver;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.identity.dto.OidcBindingRequest;
import com.riccon.api.identity.dto.OidcUnbindRequest;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.identity.dto.UserCreateRequest;
import com.riccon.api.identity.dto.UserPageResponse;
import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.api.oidc.domain.OidcConfigurationParser;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.service.OidcIdpRegistry;
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
 *
 * <p>M10-B2：创建账号支持 {@code authSource=OIDC}；管理员可显式绑定/解绑已有账号。绑定后删除本地密码凭据，
 * 解绑必须同时设置新密码；身份键固定 {@code (tenant, issuer, subject)}，不按邮箱关联（ADR-0019 第 3 节）。
 */
@Service
public class TenantUserAdminService {

    /** 登录名格式，与 OpenAPI {@code UserCreateRequest.username} 一致。 */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$");

    /** OIDC subject 最大长度（ADR-0019 第 2/3 节）。 */
    private static final int MAX_SUBJECT_LENGTH = 255;

    /** 平台管理仓储。 */
    private final AdminRepository adminRepository;

    /** 认证服务，用于计算授予者权限。 */
    private final AuthService authService;

    /** 来源 IdP 脱敏展示名解析。 */
    private final IdentityProviderResolver identityProviderResolver;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 密码编码器。 */
    private final PasswordEncoder passwordEncoder;

    /** OIDC IdP 注册表，用于校验租户当前配置的 issuer。 */
    private final OidcIdpRegistry oidcIdpRegistry;

    /** OIDC 绑定持久化。 */
    private final OidcBindingRepository oidcBindingRepository;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建账号管理服务。
     *
     * @param adminRepository 平台管理仓储
     * @param authService 认证服务
     * @param identityProviderResolver 来源 IdP 脱敏展示名解析
     * @param auditService 审计写入
     * @param passwordEncoder 密码编码器
     * @param oidcIdpRegistry OIDC IdP 注册表
     * @param oidcBindingRepository OIDC 绑定持久化
     * @param clock 可注入时钟
     */
    public TenantUserAdminService(
            AdminRepository adminRepository,
            AuthService authService,
            IdentityProviderResolver identityProviderResolver,
            AuditService auditService,
            PasswordEncoder passwordEncoder,
            OidcIdpRegistry oidcIdpRegistry,
            OidcBindingRepository oidcBindingRepository,
            Clock clock) {
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository");
        this.authService = Objects.requireNonNull(authService, "authService");
        this.identityProviderResolver = Objects.requireNonNull(identityProviderResolver, "identityProviderResolver");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.oidcIdpRegistry = Objects.requireNonNull(oidcIdpRegistry, "oidcIdpRegistry");
        this.oidcBindingRepository = Objects.requireNonNull(oidcBindingRepository, "oidcBindingRepository");
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
     * <p>{@code authSource} 省略视为 LOCAL：必须提供密码并写入 {@code user_credential}。OIDC 分支必须提供
     * issuer + subject、不得提供密码，直接写入外部身份且不写本地凭据；平台保留租户禁止创建 OIDC 账号。
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
        AuthSource authSource = request.authSource() == null ? AuthSource.LOCAL : request.authSource();
        List<String> roles = Validation.requireRoles(request.roles(), "roles");
        Map<String, RoleDefinition> definitions = resolveTenantRoles(roles);
        if (!platformActor) {
            ensureCanGrant(actor, definitions.keySet());
        }
        if (adminRepository.findUserByUsername(tenantId, username).isPresent()) {
            throw new ConflictException("username already exists in tenant");
        }
        Instant now = clock.instant();
        if (authSource == AuthSource.OIDC) {
            return createOidcUser(tenantId, request, username, displayName, roles, actor, meta, now);
        }
        return createLocalUser(tenantId, request, username, displayName, roles, actor, meta, now);
    }

    /**
     * 创建 LOCAL 账号：保留 M02 行为，必须提供 12–200 字符密码。
     *
     * @param tenantId 目标租户
     * @param request 创建请求
     * @param username 已校验登录名
     * @param displayName 已校验展示名
     * @param roles 已校验角色码
     * @param actor 操作者
     * @param meta 请求元数据
     * @param now 创建时间
     * @return 新账号摘要
     */
    private UserSummaryResponse createLocalUser(
            TenantId tenantId,
            UserCreateRequest request,
            String username,
            String displayName,
            List<String> roles,
            RicconPrincipal actor,
            RequestMeta meta,
            Instant now) {
        String password = requirePassword(request.password());
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
                    tenantId, user.id(), roleIds(tenantId, new LinkedHashSet<>(roles)), actor.userId());
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
     * 直接开通 OIDC 账号：issuer 必须命中租户当前启用配置，subject 非空且不可重复；不写本地凭据。
     *
     * @param tenantId 目标租户
     * @param request 创建请求
     * @param username 已校验登录名
     * @param displayName 已校验展示名
     * @param roles 已校验角色码
     * @param actor 操作者
     * @param meta 请求元数据
     * @param now 创建时间
     * @return 新账号摘要
     */
    private UserSummaryResponse createOidcUser(
            TenantId tenantId,
            UserCreateRequest request,
            String username,
            String displayName,
            List<String> roles,
            RicconPrincipal actor,
            RequestMeta meta,
            Instant now) {
        rejectPlatformTenant(tenantId);
        requireNoOidcPassword(request.password());
        String issuer = requireConfiguredIssuer(tenantId, request.issuer());
        String subject = requireSubject(request.subject());
        if (oidcBindingRepository.findBoundUser(tenantId, issuer, subject).isPresent()) {
            throw new OidcBindingConflictException("OIDC identity is already bound to another account");
        }
        UserAccount user = new UserAccount(
                UuidV7.generate(now),
                tenantId.value(),
                username,
                displayName,
                null,
                UserStatus.ACTIVE,
                AuthSource.OIDC,
                issuer,
                null,
                now);
        try {
            oidcBindingRepository.insertOidcUser(tenantId, user, issuer, subject);
            adminRepository.replaceUserRoles(
                    tenantId, user.id(), roleIds(tenantId, new LinkedHashSet<>(roles)), actor.userId());
        } catch (DuplicateKeyException exception) {
            // 唯一键同时保护 username 与 (issuer, subject)；预检查已排除常见路径，这里兜住并发。
            throw new OidcBindingConflictException("OIDC identity or username is already in use");
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
        // ADR-0019 第 3 节：直接开通 OIDC 账号不写 previousAuthSource，details 白名单保持 B1 定义。
        appendAudit(
                actor,
                AuditActions.USER_OIDC_BOUND,
                AuditTargetType.USER,
                user.id().toString(),
                AuditOutcome.SUCCESS,
                meta,
                Map.of(
                        "userId", user.id().toString(),
                        "issuerHash", OidcIdentityHashes.sha256Hex(issuer),
                        "subjectHash", OidcIdentityHashes.sha256Hex(subject)));
        return toSummary(user, sorted(roles));
    }

    /**
     * 管理员显式把已有账号绑定到 OIDC 身份。
     *
     * <p>同一事务内：更新 {@code auth_source=OIDC} 与 issuer/subject、删除本地密码凭据、写审计。绑定命中同一
     * 账号与原有角色；同身份已绑定其他账号或账号已绑定其他身份返回 409。平台保留租户不可绑定（B1 评审 F-002）。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param request 绑定请求
     * @param actor 操作者
     * @param meta 请求元数据
     * @return 更新后的账号摘要
     */
    @Transactional
    public UserSummaryResponse bindOidcIdentity(
            TenantId tenantId, UUID userId, OidcBindingRequest request, RicconPrincipal actor, RequestMeta meta) {
        requireTenant(tenantId);
        rejectPlatformTenant(tenantId);
        String issuer = requireConfiguredIssuer(tenantId, request.issuer());
        String subject = requireSubject(request.subject());
        UserAccount user = adminRepository.findUser(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        OidcAccountIdentity current =
                oidcBindingRepository.findIdentity(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        if (current.authSource() == AuthSource.OIDC) {
            if (issuer.equals(current.issuer()) && subject.equals(current.subject())) {
                // 幂等重试：已是同一身份时不重复写审计，但补齐“OIDC 账号不得有本地凭据”的不变量。
                oidcBindingRepository.deleteCredential(tenantId, userId);
                return toSummary(user, adminRepository.rolesForUser(tenantId, userId));
            }
            throw new OidcBindingConflictException("account is already bound to another OIDC identity");
        }
        if (oidcBindingRepository.findBoundUser(tenantId, issuer, subject).isPresent()) {
            throw new OidcBindingConflictException("OIDC identity is already bound to another account");
        }
        Instant now = clock.instant();
        try {
            if (!oidcBindingRepository.bind(tenantId, userId, issuer, subject, now)) {
                throw new OidcBindingConflictException("account authentication source changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw new OidcBindingConflictException("OIDC identity is already bound to another account");
        }
        // 单认证来源：绑定后必须删除本地密码，避免绕过 IdP 策略。
        oidcBindingRepository.deleteCredential(tenantId, userId);
        appendAudit(
                actor,
                AuditActions.USER_OIDC_BOUND,
                AuditTargetType.USER,
                userId.toString(),
                AuditOutcome.SUCCESS,
                meta,
                Map.of(
                        "userId", userId.toString(),
                        "issuerHash", OidcIdentityHashes.sha256Hex(issuer),
                        "subjectHash", OidcIdentityHashes.sha256Hex(subject),
                        "previousAuthSource", current.authSource().name()));
        List<String> roles = adminRepository.rolesForUser(tenantId, userId);
        return toSummary(withIdentity(user, AuthSource.OIDC, issuer), roles);
    }

    /**
     * 解除 OIDC 绑定并恢复本地密码。
     *
     * <p>没有“无凭据解绑”：必须提供 12–200 字符新密码，同一事务内清空 issuer/subject、重建
     * {@code user_credential} 并写审计；未绑定账号返回 409，平台保留租户不可解绑。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param request 解绑请求
     * @param actor 操作者
     * @param meta 请求元数据
     * @return 更新后的账号摘要
     */
    @Transactional
    public UserSummaryResponse unbindOidcIdentity(
            TenantId tenantId, UUID userId, OidcUnbindRequest request, RicconPrincipal actor, RequestMeta meta) {
        requireTenant(tenantId);
        rejectPlatformTenant(tenantId);
        String newPassword = requireOidcPassword(request.newPassword());
        UserAccount user = adminRepository.findUser(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        OidcAccountIdentity current =
                oidcBindingRepository.findIdentity(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        if (current.authSource() != AuthSource.OIDC) {
            throw new OidcBindingConflictException("account is not bound to an OIDC identity");
        }
        Instant now = clock.instant();
        if (!oidcBindingRepository.unbind(tenantId, userId, now)) {
            throw new OidcBindingConflictException("account authentication source changed concurrently");
        }
        oidcBindingRepository.upsertCredential(tenantId, userId, passwordEncoder.encode(newPassword), now);
        appendAudit(
                actor,
                AuditActions.USER_OIDC_UNBOUND,
                AuditTargetType.USER,
                userId.toString(),
                AuditOutcome.SUCCESS,
                meta,
                Map.of(
                        "userId", userId.toString(),
                        "issuerHash", OidcIdentityHashes.sha256Hex(current.issuer())));
        List<String> roles = adminRepository.rolesForUser(tenantId, userId);
        return toSummary(withIdentity(user, AuthSource.LOCAL, null), roles);
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
                user.externalIssuer(),
                user.lastLoginAt(),
                user.createdAt(),
                user.departmentId(),
                user.departmentName());
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
     * 拒绝平台保留租户的 OIDC 账号或绑定。
     *
     * <p>平台管理员 OIDC 登录在 ADR-0019 第 10 节后置，平台引导管理员必须保持内置账号（B1 评审 F-002）。
     *
     * @param tenantId 目标租户
     */
    private static void rejectPlatformTenant(TenantId tenantId) {
        if (PlatformTenant.ID.equals(tenantId.value())) {
            throw new OidcBindingInvalidException("OIDC accounts are not allowed in the platform tenant");
        }
    }

    /**
     * 校验创建/绑定提供的 issuer 命中租户当前启用的 IdP。
     *
     * @param tenantId 目标租户
     * @param rawIssuer 请求 issuer
     * @return 规范化 issuer
     */
    private String requireConfiguredIssuer(TenantId tenantId, String rawIssuer) {
        String normalized;
        try {
            // 配置在启动时已按 allow-http 校验；这里用宽口径规范化后再与已校验配置做严格相等比较。
            normalized = OidcConfigurationParser.normalizeIssuer(rawIssuer == null ? "" : rawIssuer, true);
        } catch (IllegalArgumentException exception) {
            throw new OidcBindingInvalidException("issuer must be a valid absolute URL");
        }
        OidcIdpConfig idp = oidcIdpRegistry
                .findEnabledByTenantCode(tenantId.value())
                .orElseThrow(() -> new OidcBindingInvalidException("OIDC is not configured for this tenant"));
        if (!idp.issuer().equals(normalized)) {
            throw new OidcBindingInvalidException("issuer does not match the tenant OIDC configuration");
        }
        return normalized;
    }

    /**
     * 校验 OIDC subject：非空、最长 255 字符；保留原值，不 trim（身份键必须与 ID Token 的 {@code sub} 完全一致）。
     *
     * @param subject 原始 subject
     * @return 原 subject
     */
    private static String requireSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new OidcBindingInvalidException(
                    "subject length must be between 1 and " + MAX_SUBJECT_LENGTH + " characters");
        }
        return subject;
    }

    /**
     * OIDC 分支禁止携带密码。
     *
     * @param password 请求密码
     */
    private static void requireNoOidcPassword(String password) {
        if (password != null && !password.isEmpty()) {
            throw new OidcBindingInvalidException("password must be empty for OIDC accounts");
        }
    }

    /**
     * 校验解绑时的新密码：12–200 字符，不做 trim。
     *
     * @param password 新密码
     * @return 原密码
     */
    private static String requireOidcPassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new OidcBindingInvalidException("newPassword length must be between 12 and 200");
        }
        return password;
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
     * 复制账号并替换认证来源与外部 issuer。
     *
     * <p>绑定/解绑后的响应必须携带新身份的 issuer，否则来源 IdP 展示会滞后到下一次列表查询。
     *
     * @param user 原账号
     * @param authSource 新认证来源
     * @param externalIssuer 新 issuer；LOCAL 必须为 {@code null}
     * @return 新账号快照
     */
    private static UserAccount withIdentity(UserAccount user, AuthSource authSource, String externalIssuer) {
        return new UserAccount(
                user.id(),
                user.tenantId(),
                user.username(),
                user.displayName(),
                user.email(),
                user.status(),
                authSource,
                externalIssuer,
                user.lastLoginAt(),
                user.createdAt(),
                user.departmentId(),
                user.departmentName());
    }

    /**
     * 映射账号摘要。
     *
     * <p>来源 IdP 只输出脱敏展示名（配置 provider 名或 issuer host）；完整 issuer/subject 不进入响应。
     *
     * @param user 账号
     * @param roles 角色码
     * @return 摘要
     */
    private UserSummaryResponse toSummary(UserAccount user, List<String> roles) {
        return new UserSummaryResponse(
                user.id(),
                user.username(),
                user.displayName(),
                user.email(),
                user.status(),
                sorted(roles),
                user.authSource(),
                identityProviderResolver.resolve(user),
                user.lastLoginAt(),
                user.createdAt(),
                user.departmentId(),
                user.departmentName());
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
