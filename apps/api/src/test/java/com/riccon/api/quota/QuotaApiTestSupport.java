package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * M04 配额集成测试基座。
 *
 * <p>租户、账号与工作空间通过公开接口创建；MCP 工具目录直接落库模拟平台授权。配额开关与限额通过管理接口或
 * 直接 SQL 设置，租户代码唯一，测试之间互不干扰。
 */
public abstract class QuotaApiTestSupport extends ResearchApiTestSupport {

    /**
     * 设置全局配额执行开关（模拟 B3 管理端写入）。
     *
     * @param enabled true 执行限额比较；false 只计量
     */
    protected void setEnforcement(boolean enabled) {
        jdbcClient.sql("""
                        UPDATE model_gateway_switch
                           SET switch_value = :value, updated_at = NOW(6)
                         WHERE switch_key = 'quotaEnforcementEnabled'
                        """).param("value", Boolean.toString(enabled)).update();
    }

    /**
     * 通过平台管理员整体替换租户配额并断言 200。
     *
     * @param platform 平台管理员
     * @param tenant 目标租户
     * @param daily 日限额；{@code null} 表示明确不限
     * @param monthly 月限额；{@code null} 表示明确不限
     * @return 更新后的响应
     */
    protected JsonNode setTenantQuota(LoginSession platform, String tenant, Integer daily, Integer monthly) {
        ApiTestClient.ApiResponse response = putTenantQuota(platform, tenant, daily, monthly);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 通过平台管理员整体替换用户配额并断言 200。
     *
     * @param platform 平台管理员
     * @param tenant 目标租户
     * @param userId 目标用户
     * @param daily 日限额
     * @param monthly 月限额
     * @return 更新后的响应
     */
    protected JsonNode setUserQuota(
            LoginSession platform, String tenant, String userId, Integer daily, Integer monthly) {
        ApiTestClient.ApiResponse response = putUserQuota(platform, tenant, userId, daily, monthly);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 原始租户配额 PUT。
     *
     * @param platform 平台管理员
     * @param tenant 目标租户
     * @param daily 日限额
     * @param monthly 月限额
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse putTenantQuota(
            LoginSession platform, String tenant, Integer daily, Integer monthly) {
        return client.put(
                "/api/v1/admin/tenants/" + tenant + "/quotas",
                quotaBody(daily, monthly),
                platform.sessionCookie(),
                platform.csrfToken());
    }

    /**
     * 原始用户配额 PUT。
     *
     * @param platform 平台管理员
     * @param tenant 目标租户
     * @param userId 目标用户
     * @param daily 日限额
     * @param monthly 月限额
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse putUserQuota(
            LoginSession platform, String tenant, String userId, Integer daily, Integer monthly) {
        return client.put(
                "/api/v1/admin/tenants/" + tenant + "/users/" + userId + "/quotas",
                quotaBody(daily, monthly),
                platform.sessionCookie(),
                platform.csrfToken());
    }

    /**
     * 构造 QuotaUpdateRequest JSON。
     *
     * @param daily 日限额
     * @param monthly 月限额
     * @return JSON
     */
    protected String quotaBody(Integer daily, Integer monthly) {
        return "{\"limits\":{\"daily\":" + (daily == null ? "null" : daily) + ",\"monthly\":"
                + (monthly == null ? "null" : monthly) + "}}";
    }

    /**
     * 读取指定窗口的已用量；只用于唯一租户的断言。
     *
     * @param tenant 租户
     * @param scopeType TENANT 或 USER
     * @param scopeId 租户代码或用户 UUID
     * @param window DAILY 或 MONTHLY
     * @return 已用量；行不存在时为 0
     */
    protected long usedCount(String tenant, String scopeType, String scopeId, String window) {
        List<Integer> values = jdbcClient
                .sql("""
                        SELECT used_count
                          FROM quota_usage
                         WHERE tenant_id = :tenantId
                           AND scope_type = :scopeType
                           AND scope_id = :scopeId
                           AND window_kind = :window
                         ORDER BY window_start DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenant)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("window", window)
                .query(Integer.class)
                .list();
        return values.isEmpty() ? 0L : values.getFirst();
    }

    /**
     * 读取显式限额行数；用于断言“0/null 都是显式不限”与默认值区分。
     *
     * @param tenant 租户
     * @param scopeType 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @return 行数
     */
    protected long limitRowCount(String tenant, String scopeType, String scopeId, String window) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*)
                          FROM quota_limit
                         WHERE tenant_id = :tenantId
                           AND scope_type = :scopeType
                           AND scope_id = :scopeId
                           AND window_kind = :window
                        """)
                .param("tenantId", tenant)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("window", window)
                .query(Long.class)
                .single();
    }

    /**
     * 统计租户内配额用量行数；冷启动应为 4，热路径保持 4。
     *
     * @param tenant 租户
     * @return 行数
     */
    protected long quotaRowCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM quota_usage WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }

    /**
     * 统计租户内 run 数。
     *
     * @param tenant 租户
     * @return 行数
     */
    protected long runCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM agent_run WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }

    /**
     * 统计租户内报告任务数。
     *
     * @param tenant 租户
     * @return 行数
     */
    protected long taskCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM research_task WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }

    /**
     * 直接 POST 报告任务，不内置状态断言。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间
     * @param topic 主题
     * @param toolId MCP 工具
     * @param idempotencyKey 幂等键
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse postResearchTask(
            LoginSession session, String workspaceId, String topic, UUID toolId, String idempotencyKey) {
        return client.post(
                "/api/v1/workspaces/" + workspaceId + "/research-tasks",
                client.jsonBody("topic", topic, "mcpToolId", toolId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    /**
     * 通过公开接口创建会话并断言 201。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间
     * @return 会话 ID
     */
    protected String createConversation(LoginSession session, String workspaceId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + workspaceId + "/conversations",
                "{}",
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * 从登录响应构造主体，供直接调用 service 层的测试使用。
     *
     * @param session 登录会话
     * @param tenant 租户
     * @return 会话主体
     */
    protected RicconPrincipal principal(LoginSession session, String tenant) {
        JsonNode user = session.body().path("user");
        return new RicconPrincipal(
                UUID.fromString(user.path("id").asText()),
                tenant,
                user.path("username").asText(),
                user.path("displayName").asText());
    }

    /**
     * 解析审计 details JSON。
     *
     * @param row 审计行
     * @return JSON 树
     */
    protected JsonNode details(AuditRow row) {
        try {
            return TEST_JSON.readTree(row.details());
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("audit details is not valid JSON", exception);
        }
    }
}
