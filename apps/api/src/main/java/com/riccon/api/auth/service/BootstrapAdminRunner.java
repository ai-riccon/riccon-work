package com.riccon.api.auth.service;

import com.riccon.api.admin.repository.AdminRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.config.RicconBootstrapProperties;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动引导平台管理员。
 *
 * <p>ADR-0013 第 5 节：迁移不创建默认账号；仅当配置了 {@code RICCON_BOOTSTRAP_ADMIN_*} 且系统中没有
 * {@code PLATFORM_ADMIN} 时创建。创建成功写 {@code user.created} 与 {@code permission.changed} 审计；
 * 已存在时跳过并告警。配置不完整或密码过弱会直接使启动失败，禁止降级为默认弱密码。
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    /** 引导管理员登录名格式，与新账号一致。 */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$");

    /** 引导密码最小长度。 */
    private static final int MIN_PASSWORD_LENGTH = 12;

    /** 引导配置。 */
    private final RicconBootstrapProperties properties;

    /** 平台管理仓储。 */
    private final AdminRepository adminRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 密码编码器。 */
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建引导管理员运行器。
     *
     * @param properties 引导配置
     * @param adminRepository 平台管理仓储
     * @param auditService 审计写入
     * @param passwordEncoder 密码编码器
     * @param clock 可注入时钟
     */
    public BootstrapAdminRunner(
            RicconBootstrapProperties properties,
            AdminRepository adminRepository,
            AuditService auditService,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 启动时执行引导逻辑；异常向上抛出使应用启动失败。
     *
     * @param args 启动参数
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            LOG.info("bootstrap admin skipped: not configured");
            return;
        }
        String username = requireUsername(properties.username());
        String password = requirePassword(properties.password());
        if (adminRepository.countUsersWithRole(PlatformTenant.TENANT_ID, RoleCodes.PLATFORM_ADMIN) > 0) {
            LOG.warn("bootstrap admin skipped: platform admin already exists");
            return;
        }
        if (adminRepository
                .findUserByUsername(PlatformTenant.TENANT_ID, username)
                .isPresent()) {
            throw new IllegalStateException("bootstrap admin username already exists without platform admin role");
        }

        UUID roleId = adminRepository
                .findRoleId(PlatformTenant.TENANT_ID, RoleCodes.PLATFORM_ADMIN)
                .orElseGet(() ->
                        adminRepository.createRole(PlatformTenant.TENANT_ID, RoleDefinitions.platformAdminRole()));
        Instant now = clock.instant();
        UserAccount user = new UserAccount(
                UuidV7.generate(now),
                PlatformTenant.ID,
                username,
                username,
                null,
                UserStatus.ACTIVE,
                AuthSource.LOCAL,
                null,
                now);
        adminRepository.insertUser(PlatformTenant.TENANT_ID, user, passwordEncoder.encode(password));
        adminRepository.replaceUserRoles(PlatformTenant.TENANT_ID, user.id(), List.of(roleId), null);

        TenantContext.runWithTenant(PlatformTenant.TENANT_ID, () -> {
            auditService.append(AuditEvent.create(
                    now,
                    PlatformTenant.ID,
                    AuditActorType.SYSTEM,
                    "system",
                    AuditActions.USER_CREATED,
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditOutcome.SUCCESS,
                    "bootstrap",
                    null,
                    null,
                    Map.of("role", RoleCodes.PLATFORM_ADMIN)));
            auditService.append(AuditEvent.create(
                    now,
                    PlatformTenant.ID,
                    AuditActorType.SYSTEM,
                    "system",
                    AuditActions.PERMISSION_CHANGED,
                    AuditTargetType.USER,
                    user.id().toString(),
                    AuditOutcome.SUCCESS,
                    "bootstrap",
                    null,
                    null,
                    Map.of("targetUserId", user.id().toString(), "role", RoleCodes.PLATFORM_ADMIN, "change", "grant")));
        });
        LOG.info("bootstrap admin created");
    }

    /**
     * 校验引导用户名。
     *
     * @param username 配置值
     * @return 合法用户名
     */
    private String requireUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalStateException("RICCON_BOOTSTRAP_ADMIN_USERNAME has invalid format");
        }
        return username;
    }

    /**
     * 校验引导密码；不记录密码内容。
     *
     * @param password 配置值
     * @return 合法密码
     */
    private String requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > 200) {
            throw new IllegalStateException("RICCON_BOOTSTRAP_ADMIN_PASSWORD must be 12-200 characters");
        }
        return password;
    }
}
