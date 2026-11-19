package com.riccon.api.auth.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.domain.LoginContext;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.auth.repository.UserAccountRepository;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.InvalidCredentialsException;
import com.riccon.api.error.RateLimitedException;
import com.riccon.api.error.UnauthenticatedException;
import com.riccon.api.identity.domain.Credential;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证与授权服务。
 *
 * <p>实现 ADR-0013：
 *
 * <ul>
 *   <li>内置账号使用 {@code tenantCode + username + password}，失败统一返回 401；
 *   <li>bcrypt 12 校验；未知账号也执行一次哈希比较，保持响应时间同量级；
 *   <li>连续 5 次失败锁定 15 分钟；成功登录清零；
 *   <li>登录成功/失败、登出都在同一事务写审计，审计失败使请求失败；
 *   <li>权限每次请求从数据库重新计算，不写入会话。
 * </ul>
 */
@Service
public class AuthService {

    /** 连续失败达到该次数后锁定。 */
    static final int MAX_FAILED_ATTEMPTS = 5;

    /** 账号锁定时长。 */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** 账号仓储。 */
    private final UserAccountRepository userAccountRepository;

    /** 租户目录仓储。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 密码哈希与校验。 */
    private final PasswordEncoder passwordEncoder;

    /** 登录失败限流。 */
    private final LoginRateLimiter loginRateLimiter;

    /** 可注入时钟，保证锁定与审计时间可测试。 */
    private final Clock clock;

    /** 未知账号时用于等价耗时的 bcrypt 哈希；不含任何真实凭据。 */
    private final String dummyPasswordHash;

    /**
     * 创建认证服务。
     *
     * @param userAccountRepository 账号仓储
     * @param tenantDirectoryRepository 租户目录仓储
     * @param auditService 审计写入
     * @param passwordEncoder 密码编码器
     * @param loginRateLimiter 登录失败限流
     * @param clock 可注入时钟
     */
    public AuthService(
            UserAccountRepository userAccountRepository,
            TenantDirectoryRepository tenantDirectoryRepository,
            AuditService auditService,
            PasswordEncoder passwordEncoder,
            LoginRateLimiter loginRateLimiter,
            Clock clock) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository, "userAccountRepository");
        this.tenantDirectoryRepository = Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.loginRateLimiter = Objects.requireNonNull(loginRateLimiter, "loginRateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        // 启动时生成一次假哈希，登录未知账号时仍执行 bcrypt，避免用响应时间枚举账号。
        this.dummyPasswordHash = passwordEncoder.encode("riccon-dummy-" + UUID.randomUUID());
    }

    /**
     * 使用内置账号登录。
     *
     * <p>{@link InvalidCredentialsException} 与 {@link RateLimitedException} 不回滚事务：失败计数与审计
     * 必须先提交，再向客户端返回统一错误。审计自身写入失败会以技术异常抛出并回滚。
     *
     * @param tenantCode 租户代码
     * @param username 登录名
     * @param rawPassword 明文密码，只在内存中短暂存在
     * @param context 请求上下文
     * @return 登录成功后的授权快照
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, RateLimitedException.class})
    public CurrentUser login(String tenantCode, String username, String rawPassword, LoginContext context) {
        try {
            loginRateLimiter.checkAllowed(context.sourceIp(), tenantCode);
        } catch (RateLimitedException exception) {
            appendAudit(
                    PlatformTenant.ID,
                    AuditActorType.SYSTEM,
                    "system",
                    AuditTargetType.TENANT,
                    tenantCode,
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "rate_limited", "attemptCount", LoginRateLimiter.DEFAULT_MAX_FAILURES));
            throw exception;
        }

        TenantRecord tenant = tenantDirectoryRepository.findById(tenantCode).orElse(null);
        if (tenant == null) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            loginRateLimiter.recordFailure(context.sourceIp(), tenantCode);
            appendAudit(
                    PlatformTenant.ID,
                    AuditActorType.SYSTEM,
                    "system",
                    AuditTargetType.TENANT,
                    tenantCode,
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "unknown_tenant", "attemptCount", 0));
            throw new InvalidCredentialsException("unknown_tenant");
        }

        TenantId tenantId = TenantId.of(tenant.id());
        if (!tenant.isActive()) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            loginRateLimiter.recordFailure(context.sourceIp(), tenantCode);
            appendAudit(
                    tenant.id(),
                    AuditActorType.SYSTEM,
                    "system",
                    AuditTargetType.TENANT,
                    tenant.id(),
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "tenant_suspended", "attemptCount", 0));
            throw new InvalidCredentialsException("tenant_suspended");
        }

        return TenantContext.callWithTenant(
                tenantId, () -> authenticateInTenant(tenant, username, rawPassword, context));
    }

    /**
     * 在已确定的租户内完成账号、凭据、锁定与审计判断。
     *
     * @param tenant 已确认启用中的租户
     * @param username 登录名
     * @param rawPassword 明文密码
     * @param context 请求上下文
     * @return 登录成功后的授权快照
     */
    private CurrentUser authenticateInTenant(
            TenantRecord tenant, String username, String rawPassword, LoginContext context) {
        TenantId tenantId = TenantId.of(tenant.id());
        UserAccount user =
                userAccountRepository.findByUsername(tenantId, username).orElse(null);
        Credential credential = user == null
                ? null
                : userAccountRepository.findCredential(tenantId, user.id()).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(
                rawPassword, credential == null ? dummyPasswordHash : credential.passwordHash());

        if (user == null) {
            loginRateLimiter.recordFailure(context.sourceIp(), tenant.id());
            appendAudit(
                    tenant.id(),
                    AuditActorType.SYSTEM,
                    "system",
                    AuditTargetType.USER,
                    null,
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "unknown_user", "attemptCount", 0));
            throw new InvalidCredentialsException("unknown_user");
        }
        if (credential == null) {
            loginRateLimiter.recordFailure(context.sourceIp(), tenant.id());
            appendAudit(
                    tenant.id(),
                    AuditActorType.USER,
                    user.id().toString(),
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "no_local_credential", "attemptCount", 0));
            throw new InvalidCredentialsException("no_local_credential");
        }
        if (!user.isActive()) {
            loginRateLimiter.recordFailure(context.sourceIp(), tenant.id());
            appendAudit(
                    tenant.id(),
                    AuditActorType.USER,
                    user.id().toString(),
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "account_disabled", "attemptCount", credential.failedAttempts()));
            throw new InvalidCredentialsException("account_disabled");
        }

        Instant now = clock.instant();
        if (credential.isLockedAt(now)) {
            loginRateLimiter.recordFailure(context.sourceIp(), tenant.id());
            appendAudit(
                    tenant.id(),
                    AuditActorType.USER,
                    user.id().toString(),
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "account_locked", "attemptCount", credential.failedAttempts()));
            throw new InvalidCredentialsException("account_locked");
        }

        if (!passwordMatches) {
            int attempts = credential.failedAttempts() + 1;
            Instant lockedUntil = attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOCK_DURATION) : null;
            userAccountRepository.recordLoginFailure(tenantId, user.id(), attempts, lockedUntil, now);
            loginRateLimiter.recordFailure(context.sourceIp(), tenant.id());
            appendAudit(
                    tenant.id(),
                    AuditActorType.USER,
                    user.id().toString(),
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditActions.AUTH_LOGIN_FAILED,
                    AuditOutcome.FAILURE,
                    context,
                    Map.of("reasonCode", "bad_password", "attemptCount", attempts));
            throw new InvalidCredentialsException("bad_password");
        }

        userAccountRepository.markLoginSucceeded(tenantId, user.id(), now);
        loginRateLimiter.reset(context.sourceIp(), tenant.id());
        List<String> roles = userAccountRepository.findRoles(tenantId, user.id());
        List<String> permissions = userAccountRepository.findPermissions(tenantId, user.id());
        appendAudit(
                tenant.id(),
                AuditActorType.USER,
                user.id().toString(),
                AuditTargetType.SESSION,
                null,
                AuditActions.AUTH_LOGIN_SUCCEEDED,
                AuditOutcome.SUCCESS,
                context,
                Map.of("method", "password", "sessionIdHash", hashSessionId(context.sessionId())));
        UserAccount refreshed = new UserAccount(
                user.id(),
                user.tenantId(),
                user.username(),
                user.displayName(),
                user.email(),
                user.status(),
                user.authSource(),
                user.externalIssuer(),
                now,
                user.createdAt());
        return new CurrentUser(refreshed, tenant, roles, permissions);
    }

    /**
     * 读取当前会话的授权快照，并校验账号与租户仍启用。
     *
     * @param principal 会话主体
     * @return 当前授权快照
     * @throws UnauthenticatedException 租户或账号不存在、被禁用或暂停
     */
    @Transactional(readOnly = true)
    public CurrentUser loadCurrentUser(RicconPrincipal principal) {
        TenantId tenantId = TenantId.of(principal.tenantId());
        return TenantContext.callWithTenant(tenantId, () -> {
            TenantRecord tenant =
                    tenantDirectoryRepository.findById(tenantId.value()).orElseThrow(UnauthenticatedException::new);
            if (!tenant.isActive()) {
                throw new UnauthenticatedException();
            }
            UserAccount user = userAccountRepository
                    .findById(tenantId, principal.userId())
                    .orElseThrow(UnauthenticatedException::new);
            if (!user.isActive()) {
                throw new UnauthenticatedException();
            }
            List<String> roles = userAccountRepository.findRoles(tenantId, user.id());
            List<String> permissions = userAccountRepository.findPermissions(tenantId, user.id());
            return new CurrentUser(user, tenant, roles, permissions);
        });
    }

    /**
     * 写入登出审计。
     *
     * @param principal 会话主体
     * @param context 请求上下文
     */
    @Transactional
    public void logout(RicconPrincipal principal, LoginContext context) {
        TenantId tenantId = TenantId.of(principal.tenantId());
        TenantContext.runWithTenant(
                tenantId,
                () -> appendAudit(
                        principal.tenantId(),
                        AuditActorType.USER,
                        principal.userId().toString(),
                        AuditTargetType.SESSION,
                        null,
                        AuditActions.AUTH_LOGOUT,
                        AuditOutcome.SUCCESS,
                        context,
                        Map.of()));
    }

    /**
     * 校验当前主体是否拥有指定权限。
     *
     * @param principal 会话主体
     * @param permissionCode 权限码
     * @throws ForbiddenException 权限不足
     */
    public void requirePermission(RicconPrincipal principal, String permissionCode) {
        CurrentUser currentUser = loadCurrentUser(principal);
        if (!currentUser.hasPermission(permissionCode)) {
            throw new ForbiddenException(permissionCode);
        }
    }

    /**
     * 追加审计事件；在事件所属租户上下文中执行，避免跨租户写入。
     *
     * @param tenantId 事件租户
     * @param actorType 操作者类型
     * @param actorId 操作者 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作
     * @param outcome 结果
     * @param context 请求上下文
     * @param details 白名单字段
     */
    private void appendAudit(
            String tenantId,
            AuditActorType actorType,
            String actorId,
            AuditTargetType targetType,
            String targetId,
            String action,
            AuditOutcome outcome,
            LoginContext context,
            Map<String, Object> details) {
        TenantContext.runWithTenant(
                TenantId.of(tenantId),
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        tenantId,
                        actorType,
                        actorId,
                        action,
                        targetType,
                        targetId,
                        outcome,
                        context.traceId(),
                        context.requestId(),
                        context.sourceIp(),
                        details)));
    }

    /**
     * 计算会话 ID 的 SHA-256，审计只保存哈希。
     *
     * @param sessionId 会话 ID；可为空
     * @return 十六进制哈希；无会话时返回 {@code none}
     */
    private String hashSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
