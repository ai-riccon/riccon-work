package com.riccon.api.oidc.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.domain.LoginContext;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.auth.repository.UserAccountRepository;
import com.riccon.api.auth.service.LoginRateLimiter;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.oidc.domain.OidcConfigurationParser;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.domain.OidcLoginRejectedException;
import com.riccon.api.oidc.domain.OidcProviderAvailability;
import com.riccon.api.oidc.domain.SessionIdHashes;
import com.riccon.api.oidc.repository.OidcIdentityRepository;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OIDC 登录业务校验与审计。
 *
 * <p>关键边界（ADR-0019 第 3/4 节）：
 *
 * <ul>
 *   <li>身份键固定 {@code (tenant, issuer, subject)}，不读邮箱、不自动开通、不按邮箱关联；
 *   <li>业务拒绝（未绑定、禁用、租户不可用、issuer 不一致）在认证成功前抛出带稳定原因码的异常，
 *       确保失败处理器能清理会话且成功处理器不会执行；
 *   <li>成功/失败审计都写库；成功审计失败使登录失败（fail-closed），未知租户的失败只写进程日志；
 *   <li>只复用 {@code LoginRateLimiter} 的计数结果，不在本类重造限流。
 * </ul>
 */
@Service
public class OidcLoginService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(OidcLoginService.class);

    /** OIDC subject 最大长度（ADR-0019 第 2 节）。 */
    private static final int MAX_SUBJECT_LENGTH = 255;

    /** IdP 注册表。 */
    private final OidcIdpRegistry registry;

    /** 租户目录。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 身份绑定查询。 */
    private final OidcIdentityRepository identityRepository;

    /** 账号仓储（更新最近登录时间）。 */
    private final UserAccountRepository userAccountRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 登录失败限流计数。 */
    private final LoginRateLimiter loginRateLimiter;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建登录服务。
     *
     * @param registry IdP 注册表
     * @param tenantDirectoryRepository 租户目录
     * @param identityRepository 身份绑定查询
     * @param userAccountRepository 账号仓储
     * @param auditService 审计写入
     * @param loginRateLimiter 登录失败限流
     * @param clock 可注入时钟
     */
    public OidcLoginService(
            OidcIdpRegistry registry,
            TenantDirectoryRepository tenantDirectoryRepository,
            OidcIdentityRepository identityRepository,
            UserAccountRepository userAccountRepository,
            AuditService auditService,
            LoginRateLimiter loginRateLimiter,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.identityRepository = Objects.requireNonNull(identityRepository, "identityRepository must not be null");
        this.userAccountRepository =
                Objects.requireNonNull(userAccountRepository, "userAccountRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.loginRateLimiter = Objects.requireNonNull(loginRateLimiter, "loginRateLimiter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 在建立会话前校验 OIDC 身份与账号状态。
     *
     * @param registrationId 注册 ID
     * @param issuer ID Token 的 issuer
     * @param subject ID Token 的 subject
     * @return 已绑定的启用账号
     * @throws OidcLoginRejectedException 业务拒绝（未绑定、禁用、租户不可用、issuer 不一致等）
     */
    public UserAccount validateLogin(String registrationId, String issuer, String subject) {
        OidcIdpConfig idp = registry.findEnabledByRegistrationId(registrationId)
                .orElseThrow(() -> reject(OidcLoginFailureReason.LOGIN_FAILED));
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw reject(OidcLoginFailureReason.LOGIN_FAILED);
        }
        if (!issuerMatches(idp, issuer)) {
            throw reject(OidcLoginFailureReason.ISSUER_MISMATCH);
        }
        TenantRecord tenant = tenantDirectoryRepository
                .findById(idp.tenantCode())
                .orElseThrow(() -> reject(OidcLoginFailureReason.TENANT_UNAVAILABLE));
        if (!tenant.isActive()) {
            throw reject(OidcLoginFailureReason.TENANT_UNAVAILABLE);
        }
        TenantId tenantId = TenantId.of(tenant.id());
        UserAccount user = identityRepository
                .findBoundUser(tenantId, idp.issuer(), subject)
                .orElseThrow(() -> reject(OidcLoginFailureReason.ACCOUNT_NOT_BOUND));
        if (!user.isActive()) {
            throw reject(OidcLoginFailureReason.ACCOUNT_DISABLED);
        }
        return user;
    }

    /**
     * 查询租户企业登录可用性。
     *
     * @param tenantCode 租户代码
     * @return 可用性；未知/未配置/停用/租户暂停统一 {@code enabled=false}
     */
    @Transactional(readOnly = true)
    public OidcProviderAvailability providerAvailability(String tenantCode) {
        Optional<OidcIdpConfig> idp = registry.findEnabledByTenantCode(tenantCode);
        if (idp.isEmpty()) {
            return OidcProviderAvailability.disabled();
        }
        TenantRecord tenant = tenantDirectoryRepository.findById(tenantCode).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            return OidcProviderAvailability.disabled();
        }
        return OidcProviderAvailability.enabled(idp.get().providerName());
    }

    /**
     * 记录登录成功：更新最近登录时间、清空失败限流并写审计。
     *
     * <p>审计失败会回滚并抛出，由成功处理器统一清理会话并返回 {@code login_failed}（fail-closed）。
     *
     * @param user 已校验账号
     * @param context 新会话 ID 的请求上下文
     */
    @Transactional
    public void recordLoginSuccess(UserAccount user, LoginContext context) {
        TenantId tenantId = TenantId.of(user.tenantId());
        Instant now = clock.instant();
        TenantContext.runWithTenant(tenantId, () -> {
            userAccountRepository.markLoginSucceeded(tenantId, user.id(), now);
            auditService.append(AuditEvent.create(
                    now,
                    user.tenantId(),
                    AuditActorType.USER,
                    user.id().toString(),
                    AuditActions.AUTH_LOGIN_SUCCEEDED,
                    AuditTargetType.SESSION,
                    null,
                    AuditOutcome.SUCCESS,
                    context.traceId(),
                    context.requestId(),
                    context.sourceIp(),
                    Map.of("method", "oidc", "sessionIdHash", SessionIdHashes.sha256Hex(context.sessionId()))));
        });
        loginRateLimiter.reset(context.sourceIp(), user.tenantId());
    }

    /**
     * 记录登录失败审计；未知租户只写进程日志，避免伪造租户写入垃圾事件。
     *
     * @param registrationId 注册 ID；可为空
     * @param reason 稳定失败原因
     * @param context 请求上下文
     */
    @Transactional
    public void recordLoginFailure(String registrationId, OidcLoginFailureReason reason, LoginContext context) {
        OidcIdpConfig idp = registry.findConfigured(registrationId).orElse(null);
        if (idp == null) {
            LOG.info("oidc.login.failed reason={} registration=unknown", reason.code());
            return;
        }
        TenantRecord tenant =
                tenantDirectoryRepository.findById(idp.tenantCode()).orElse(null);
        if (tenant == null) {
            LOG.info("oidc.login.failed tenantCode={} reason={}", idp.tenantCode(), reason.code());
            return;
        }
        TenantId tenantId = TenantId.of(tenant.id());
        TenantContext.runWithTenant(
                tenantId,
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        tenant.id(),
                        AuditActorType.SYSTEM,
                        "system",
                        AuditActions.AUTH_LOGIN_FAILED,
                        AuditTargetType.TENANT,
                        tenant.id(),
                        AuditOutcome.FAILURE,
                        context.traceId(),
                        context.requestId(),
                        context.sourceIp(),
                        Map.of("reasonCode", reason.code()))));
    }

    /**
     * 判断 ID Token issuer 是否与租户配置一致。
     *
     * @param idp 配置
     * @param issuer ID Token issuer
     * @return 一致时返回 {@code true}
     */
    private boolean issuerMatches(OidcIdpConfig idp, String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return false;
        }
        try {
            return idp.issuer().equals(OidcConfigurationParser.normalizeIssuer(issuer, true));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 构造业务拒绝异常。
     *
     * @param reason 失败原因
     * @return 拒绝异常
     */
    private static OidcLoginRejectedException reject(OidcLoginFailureReason reason) {
        return new OidcLoginRejectedException(reason);
    }
}
