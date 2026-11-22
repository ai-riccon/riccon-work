package com.riccon.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * API 集成测试基类。
 *
 * <p>默认使用真实 MySQL 8.4（Testcontainers）与随机端口 Tomcat，Flyway 在上下文启动时按 V1–V9 建库，
 * 引导管理员由 test profile 环境变量创建。所有测试共享同一容器与 Spring 上下文，避免重复建库；
 * 测试数据使用唯一租户代码隔离。
 *
 * <p>仅当显式设置 {@code RICCON_TEST_SERVICES=external} 且提供
 * {@code RICCON_TEST_SERVICES_NAMESPACE} 时，改为复用 compose 热测试栈；见
 * {@link ExternalTestServices}。默认与 CI 行为不变。
 *
 * <p>M19 P4a：类级 {@code @Tag("integration")} 会被所有子类继承（JUnit 5/6 {@code @Tag} 是
 * {@code @Inherited}），因此新增 API 集成测试只需继承本类，不再逐类标注；子类叠加的
 * {@code security}/{@code dept-isolation} 等标签与 {@code integration} 共存。标签只用于选择，
 * 不改变默认执行集合；规则见 {@code docs/CODING-STANDARDS.md} §10.4。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiIntegrationTestSupport {

    /** 与合同 stack.versions.mysql 一致的测试镜像。 */
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            // MySQL 8.4 默认开启 binlog；V6 的非 SUPER 触发器创建需要该参数（与 compose 测试栈一致）。
            .withCommand(
                    "--log-bin-trust-function-creators=1",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    static {
        if (ExternalTestServices.enabled()) {
            // 显式 opt-in：连接 compose 热栈，并用命名锁阻止并行 agent 共用同一数据库。
            ExternalTestServices.verifyAndLock();
        } else {
            MYSQL.start();
        }
    }

    /** 随机端口。 */
    @LocalServerPort
    protected int port;

    /** 直接查询数据库，用于断言迁移、会话与审计。 */
    @Autowired
    protected JdbcClient jdbcClient;

    /** HTTP 客户端。 */
    protected ApiTestClient client;

    /**
     * 本 JVM 内复用的平台管理员会话。
     *
     * <p>只缓存“引导平台管理员已通过真实登录”这一不可变结果，不缓存任何测试业务数据；每次复用前都用
     * {@code GET /api/v1/auth/session} 只读探针确认会话仍有效，登出/禁用/过期时自动重新真实登录。
     * 认证、会话生命周期、会话计数、CSRF 轮换与登录审计测试必须使用
     * {@link #freshPlatformAdminLogin()}，不能依赖本缓存。
     */
    private static volatile LoginSession cachedPlatformAdminSession;

    /**
     * fixture 密码到 bcrypt 哈希的进程内缓存。
     *
     * <p>bcrypt 强度 12 单次编码约 0.3–0.7 秒；fixture 账号只需“能用同一密码真实登录”，不需要每个账号
     * 重新编码。相同密码复用同一哈希不改变登录校验语义（bcrypt 校验仍逐次执行），也不影响任何密码策略
     * 断言——账号创建路径目前没有短密码/策略拒绝断言（既有缺口），种子路径未取代任何策略测试。
     */
    private static final Map<String, String> FIXTURE_PASSWORD_HASHES = new ConcurrentHashMap<>();

    /**
     * 为每个测试创建客户端，避免跨测试共享 Cookie。
     */
    @BeforeEach
    void initializeClient() {
        client = new ApiTestClient(port);
    }

    /**
     * 注入数据源：默认使用一次性容器，external 模式使用 compose 热栈的命名空间数据库。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (ExternalTestServices.enabled()) {
            registry.add("spring.datasource.url", ExternalTestServices::jdbcUrl);
            registry.add("spring.datasource.username", ExternalTestServices::username);
            registry.add("spring.datasource.password", ExternalTestServices::password);
            return;
        }
        String jdbcUrl = MYSQL.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        registry.add(
                "spring.datasource.url",
                () -> jdbcUrl + separator + "useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * 获取匿名 CSRF 会话。
     *
     * @return CSRF 会话
     */
    protected CsrfSession csrfSession() {
        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/csrf");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String cookie = client.sessionCookie(response)
                .orElseThrow(() -> new AssertionError("csrf response did not set session cookie: " + response.body()));
        return new CsrfSession(cookie, client.csrfToken(response));
    }

    /**
     * 使用给定凭据登录并断言成功。
     *
     * @param tenantCode 租户代码
     * @param username 登录名
     * @param password 密码
     * @return 登录会话
     */
    protected LoginSession login(String tenantCode, String username, String password) {
        CsrfSession csrf = csrfSession();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/auth/login",
                client.jsonBody("tenantCode", tenantCode, "username", username, "password", password),
                csrf.cookie(),
                csrf.token(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String cookie = client.sessionCookie(response)
                .orElseThrow(() -> new AssertionError("login did not set session cookie: " + response.body()));
        JsonNode body = client.json(response);
        return new LoginSession(cookie, body.path("csrfToken").asText(), body);
    }

    /**
     * 登录引导平台管理员，本 JVM 内复用首次真实登录的会话。
     *
     * <p>平台管理员是公共 fixture 账号，87 个测试方法重复登录会重复执行 bcrypt 强度 12 校验并写会话行；
     * 这里返回缓存会话以减少 setup 时间。缓存不是认证绕过：首次登录、会话探针和失效后的重新登录都走真实
     * HTTP、CSRF 与会话表。探针返回非 200（登出、禁用、过期或跨数据库上下文）时立即重新登录。
     *
     * <p>需要验证登录、登出、会话计数、CSRF 轮换或登录审计的用例必须显式调用
     * {@link #freshPlatformAdminLogin()}，本缓存不得替代这些断言。
     *
     * @return 平台管理员会话
     */
    protected LoginSession loginPlatformAdmin() {
        LoginSession cached = cachedPlatformAdminSession;
        if (cached != null && isSessionActive(cached)) {
            return cached;
        }
        // 双重检查：只有缓存缺失或失效时才进入同步块，避免并发调用重复登录。
        synchronized (ApiIntegrationTestSupport.class) {
            cached = cachedPlatformAdminSession;
            if (cached != null && isSessionActive(cached)) {
                return cached;
            }
            cached = login("platform", "platform-admin", "test-bootstrap-password-12");
            cachedPlatformAdminSession = cached;
            return cached;
        }
    }

    /**
     * 真实登录平台管理员，不使用缓存。
     *
     * <p>用于认证、会话生命周期、会话计数、CSRF 与登录审计测试；每次执行完整的
     * {@code GET /auth/csrf} + {@code POST /auth/login}，包含 bcrypt 校验、会话轮换与登录审计。
     *
     * @return 平台管理员会话
     */
    protected LoginSession freshPlatformAdminLogin() {
        return login("platform", "platform-admin", "test-bootstrap-password-12");
    }

    /**
     * 只读探针：判断缓存会话当前是否仍然有效。
     *
     * <p>直接请求真实 {@code /auth/session}，不写审计、不轮换令牌；401 表示会话已登出、禁用或过期，
     * 由调用方重新登录。探针失败不会被当成认证成功。
     *
     * @param session 缓存会话
     * @return 会话有效时返回 {@code true}
     */
    private boolean isSessionActive(LoginSession session) {
        return client.get("/api/v1/auth/session", session.sessionCookie(), Map.of())
                        .status()
                == 200;
    }

    /**
     * 创建唯一租户代码。
     *
     * @param prefix 前缀
     * @return 租户代码
     */
    protected String uniqueTenantCode(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 通过平台管理员创建租户。
     *
     * @param admin 平台管理员会话
     * @param tenantCode 租户代码
     * @return 租户响应
     */
    protected JsonNode createTenant(LoginSession admin, String tenantCode) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants",
                client.jsonBody("id", tenantCode, "name", "Tenant " + tenantCode),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 通过平台管理员在目标租户创建账号。
     *
     * @param admin 平台管理员会话
     * @param tenantCode 目标租户
     * @param username 登录名
     * @param password 初始密码
     * @param roles 角色码
     * @return 账号响应
     */
    protected JsonNode createUser(
            LoginSession admin, String tenantCode, String username, String password, List<String> roles) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenantCode + "/users",
                client.jsonBody(
                        "username", username, "displayName", "User " + username, "password", password, "roles", roles),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 直接种子一个 LOCAL 账号（fixture 专用，不调用账号创建 HTTP 端点）。
     *
     * <p>只允许用于“测试目标不是账号创建端点、权限、审计或密码策略”的场景，例如租户/部门隔离、空间
     * 数据范围、文件、报告与审计查询的 fixture。原因：这些套件每个用例都要准备多个账号，而
     * {@code POST /admin/tenants/{id}/users} 每次都会执行 bcrypt 强度 12 编码（单次约 0.3–0.7 秒），
     * 成为套件主要耗时。这里复用相同密码的 bcrypt 哈希并直接写入账号、凭据与角色表；真实登录、
     * CSRF、会话与后续权限判断仍由生产代码执行。
     *
     * <p>账号创建成功路径与创建审计的真实链路由 {@code AuthFlowTest}、{@code TenantIsolationTest}、
     * {@code TenantIsolationSecurityTest}、{@code OidcIdentityBindingApiTest}、{@code QuotaAdminApiTest}
     * 与 {@code AuditTestSupport} 等通过 {@link #createUser} 保留覆盖；账号创建路径目前没有短密码/密码策略
     * 拒绝断言（既有缺口），本方法不得用于这些断言场景。
     *
     * @param tenantCode 目标租户
     * @param username 登录名
     * @param password 初始密码
     * @param roles 角色码
     * @param grantedBy 角色授予人账号 ID
     * @return 账号 ID
     */
    protected UUID seedLocalUser(
            String tenantCode, String username, String password, List<String> roles, UUID grantedBy) {
        UUID userId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO app_user (
                            tenant_id, id, username, display_name, email, status, auth_source, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :username, :displayName, NULL, 'ACTIVE', 'LOCAL', NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantCode)
                .param("id", BinaryUuids.toBytes(userId))
                .param("username", username)
                .param("displayName", "User " + username)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO user_credential (
                            tenant_id, user_id, password_hash, algorithm, must_change_password, failed_attempts
                        ) VALUES (
                            :tenantId, :userId, :passwordHash, 'bcrypt', FALSE, 0
                        )
                        """)
                .param("tenantId", tenantCode)
                .param("userId", BinaryUuids.toBytes(userId))
                .param("passwordHash", fixturePasswordHash(password))
                .update();
        for (String role : roles) {
            // 角色 ID 是 BINARY(16)，直接按二进制读取，避免 UUID 文本转换。
            byte[] roleBytes = jdbcClient
                    .sql("SELECT id FROM role WHERE tenant_id = :tenantId AND code = :code")
                    .param("tenantId", tenantCode)
                    .param("code", role)
                    .query(byte[].class)
                    .single();
            jdbcClient
                    .sql("""
                            INSERT INTO user_role (tenant_id, user_id, role_id, granted_by)
                            VALUES (:tenantId, :userId, :roleId, :grantedBy)
                            """)
                    .param("tenantId", tenantCode)
                    .param("userId", BinaryUuids.toBytes(userId))
                    .param("roleId", roleBytes)
                    .param("grantedBy", BinaryUuids.toBytes(grantedBy))
                    .update();
        }
        return userId;
    }

    /**
     * 种子一个 LOCAL 账号并真实登录（fixture 专用）。
     *
     * <p>账号行由 {@link #seedLocalUser} 写入，登录仍走真实 {@code GET /auth/csrf} +
     * {@code POST /auth/login}，因此会话、CSRF 与密码校验都是生产路径。
     *
     * @param platformAdmin 平台管理员会话，用于角色授予人
     * @param tenantCode 目标租户
     * @param username 登录名
     * @param password 初始密码
     * @param roles 角色码
     * @return 登录会话
     */
    protected LoginSession seedAndLoginLocalUser(
            LoginSession platformAdmin, String tenantCode, String username, String password, List<String> roles) {
        seedLocalUser(tenantCode, username, password, roles, actorId(platformAdmin));
        return login(tenantCode, username, password);
    }

    /**
     * 创建租户管理员并登录。
     *
     * <p>账号行用 fixture 种子写入（见 {@link #seedLocalUser}），登录走真实 HTTP；本方法用于非账号创建
     * 目标的 fixture。账号创建端点由真实 HTTP 测试覆盖；密码策略拒绝断言目前是既有缺口。
     *
     * @param platformAdmin 平台管理员会话
     * @param tenantCode 目标租户
     * @return 租户管理员会话
     */
    protected LoginSession createAndLoginTenantAdmin(LoginSession platformAdmin, String tenantCode) {
        String username =
                "admin" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return seedAndLoginLocalUser(
                platformAdmin, tenantCode, username, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
    }

    /**
     * 创建普通员工并登录。
     *
     * <p>账号行用 fixture 种子写入（见 {@link #seedLocalUser}），登录走真实 HTTP；本方法用于非账号创建
     * 目标的 fixture。账号创建端点由真实 HTTP 测试覆盖；密码策略拒绝断言目前是既有缺口。
     *
     * @param platformAdmin 平台管理员会话
     * @param tenantCode 目标租户
     * @return 员工会话
     */
    protected LoginSession createAndLoginMember(LoginSession platformAdmin, String tenantCode) {
        String username =
                "member" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return seedAndLoginLocalUser(platformAdmin, tenantCode, username, "member-password-12", List.of("MEMBER"));
    }

    /**
     * 读取登录会话中的账号 ID。
     *
     * @param session 登录会话
     * @return 账号 ID
     */
    protected UUID actorId(LoginSession session) {
        return UUID.fromString(session.body().path("user").path("id").asText());
    }

    /**
     * 返回 fixture 密码的 bcrypt 哈希；相同密码进程内只编码一次。
     *
     * @param password 明文密码
     * @return 带 {@code {bcrypt}} 前缀的强度 12 哈希
     */
    private static String fixturePasswordHash(String password) {
        return FIXTURE_PASSWORD_HASHES.computeIfAbsent(
                password, value -> "{bcrypt}" + new BCryptPasswordEncoder(12).encode(value));
    }

    /**
     * 查询指定动作的审计记录。
     *
     * @param action 动作名
     * @return 审计行
     */
    protected List<AuditRow> auditRows(String action) {
        return jdbcClient
                .sql("""
                        SELECT tenant_id, actor_type, actor_id, action, target_type, target_id,
                               outcome, trace_id, details
                        FROM audit_event WHERE action = :action ORDER BY occurred_at ASC, event_id ASC
                        """)
                .param("action", action)
                .query((rs, rowNum) -> new AuditRow(
                        rs.getString("tenant_id"),
                        rs.getString("actor_type"),
                        rs.getString("actor_id"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("outcome"),
                        rs.getString("trace_id"),
                        rs.getString("details")))
                .list();
    }

    /**
     * 查询会话表中某主体的会话数。
     *
     * @param principalName 主体名
     * @return 会话数
     */
    protected long sessionCount(String principalName) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = :principalName")
                .param("principalName", principalName)
                .query(Long.class)
                .single();
    }

    /**
     * 匿名 CSRF 会话。
     *
     * @param cookie 会话 Cookie
     * @param token CSRF 令牌
     */
    protected record CsrfSession(String cookie, String token) {}

    /**
     * 已登录会话。
     *
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @param body 登录响应体
     */
    protected record LoginSession(String sessionCookie, String csrfToken, JsonNode body) {}

    /**
     * 审计行。
     *
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param actorId 操作者 ID
     * @param action 动作
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param outcome 结果
     * @param traceId 追踪 ID
     * @param details details JSON
     */
    protected record AuditRow(
            String tenantId,
            String actorType,
            String actorId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String traceId,
            String details) {}
}
