package com.riccon.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * API 集成测试基类。
 *
 * <p>使用真实 MySQL 8.4（Testcontainers）与随机端口 Tomcat，Flyway 在上下文启动时按 V1–V7 建库，
 * 引导管理员由 test profile 环境变量创建。所有测试共享同一容器与 Spring 上下文，避免重复建库；
 * 测试数据使用唯一租户代码隔离。
 */
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
        MYSQL.start();
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
     * 为每个测试创建客户端，避免跨测试共享 Cookie。
     */
    @BeforeEach
    void initializeClient() {
        client = new ApiTestClient(port);
    }

    /**
     * 注入 Testcontainers 数据源。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
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
     * 登录引导平台管理员。
     *
     * @return 平台管理员会话
     */
    protected LoginSession loginPlatformAdmin() {
        return login("platform", "platform-admin", "test-bootstrap-password-12");
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
     * 创建租户管理员并登录。
     *
     * @param platformAdmin 平台管理员会话
     * @param tenantCode 目标租户
     * @return 租户管理员会话
     */
    protected LoginSession createAndLoginTenantAdmin(LoginSession platformAdmin, String tenantCode) {
        String username =
                "admin" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createUser(platformAdmin, tenantCode, username, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        return login(tenantCode, username, "tenant-admin-password-12");
    }

    /**
     * 创建普通员工并登录。
     *
     * @param platformAdmin 平台管理员会话
     * @param tenantCode 目标租户
     * @return 员工会话
     */
    protected LoginSession createAndLoginMember(LoginSession platformAdmin, String tenantCode) {
        String username =
                "member" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createUser(platformAdmin, tenantCode, username, "member-password-12", List.of("MEMBER"));
        return login(tenantCode, username, "member-password-12");
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
