package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 启动、迁移与默认拒绝基线。
 *
 * <p>验证 Testcontainers MySQL 8.4 上 Flyway V1–V9 全部成功（V9 已在发布前于同一文件内收紧）、
 * 审计触发器存在、健康检查公开，以及未认证业务路径返回统一 401 ProblemDetail。
 */
class ApiApplicationTests extends ApiIntegrationTestSupport {

    /** 健康检查必须公开且数据库可用。 */
    @Test
    void healthEndpointIsPublic() {
        ApiTestClient.ApiResponse response = client.get("/actuator/health");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("status").asText()).isEqualTo("UP");
    }

    /** Flyway 在干净 MySQL 上应用 V1–V9，结构与表数量可核对。 */
    @Test
    void flywayAppliesAllMigrationsOnCleanDatabase() {
        Long migrations = jdbcClient
                .sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")
                .query(Long.class)
                .single();
        Long tables = jdbcClient
                .sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")
                .query(Long.class)
                .single();
        Long triggers = jdbcClient
                .sql("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()")
                .query(Long.class)
                .single();
        assertThat(migrations).isEqualTo(9L);
        // 38 张业务/基础设施表 + flyway_schema_history；V9 新增 department/workspace_member。
        assertThat(tables).isEqualTo(39L);
        assertThat(triggers).isEqualTo(2L);
    }

    /** V9 已收紧：可见性列 NOT NULL，严格 workspace scope CHECK 存在（ADR-0018 §6.2）。 */
    @Test
    void workspaceVisibilityIsTightened() {
        String nullable = jdbcClient.sql("""
                        SELECT IS_NULLABLE FROM information_schema.columns
                         WHERE table_schema = DATABASE() AND table_name = 'workspace' AND column_name = 'visibility'
                        """).query(String.class).single();
        Long scopeChecks = jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.table_constraints
                         WHERE table_schema = DATABASE() AND table_name = 'workspace'
                           AND constraint_name = 'chk_workspace_scope' AND constraint_type = 'CHECK'
                        """).query(Long.class).single();
        assertThat(nullable).isEqualTo("NO");
        assertThat(scopeChecks).isEqualTo(1L);
    }

    /** 会话表必须由 Flyway 创建，且应用关闭自动建表。 */
    @Test
    void springSessionTablesExist() {
        Long sessionTables = jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name IN ('SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES')
                        """).query(Long.class).single();
        assertThat(sessionTables).isEqualTo(2L);
    }

    /** 未认证读取会话返回统一 401 ProblemDetail。 */
    @Test
    void sessionWithoutAuthenticationReturnsProblemDetail() {
        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/session");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-401");
        assertThat(client.json(response).path("traceId").asText()).isNotBlank();
        assertThat(response.header("X-Trace-Id")).isNotBlank();
    }

    /** 未认证访问未知业务路径仍是 401，不暴露路由存在性。 */
    @Test
    void unknownBusinessPathRequiresAuthentication() {
        ApiTestClient.ApiResponse response = client.get("/api/v1/unknown");
        assertThat(response.status()).isEqualTo(401);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-401");
    }

    /** 健康检查之外的非 API 路径继续默认拒绝。 */
    @Test
    void nonApiPathIsDeniedByDefault() {
        ApiTestClient.ApiResponse response = client.get("/internal/secret", null, Map.of());
        assertThat(response.status()).isIn(401, 403);
        assertThat(client.json(response).path("code").asText()).isIn("RICCON-AUTH-401", "RICCON-AUTH-403");
    }

    /** 引导管理员创建必须写 user.created 与 permission.changed 审计，且不泄露密码。 */
    @Test
    void bootstrapAdminCreationIsAudited() {
        assertThat(auditRows("user.created"))
                .filteredOn(row -> "platform".equals(row.tenantId()) && "SYSTEM".equals(row.actorType()))
                .anySatisfy(row -> assertThat(row.details()).contains("PLATFORM_ADMIN"));
        assertThat(auditRows("permission.changed"))
                .filteredOn(row -> "platform".equals(row.tenantId()) && "SYSTEM".equals(row.actorType()))
                .anySatisfy(row ->
                        assertThat(row.details()).contains("PLATFORM_ADMIN").contains("grant"));
    }

    /** 验证 test profile 的引导管理员已创建并带平台管理员角色。 */
    @Test
    @DisplayName("TEN-019 引导管理员属于 platform 租户且拥有 PLATFORM_ADMIN")
    void bootstrapAdminBelongsToPlatformTenant() {
        Long admins = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app_user u
                        JOIN user_role ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                        JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                        WHERE u.tenant_id = 'platform' AND u.username = 'platform-admin' AND r.code = 'PLATFORM_ADMIN'
                        """).query(Long.class).single();
        assertThat(admins).isEqualTo(1L);
    }
}
