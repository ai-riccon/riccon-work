package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * M10 审计查询/导出集成测试基座。
 *
 * <p>复用真实 MySQL（Testcontainers）与真实迁移 V1–V10；每个夹具使用唯一租户代码隔离数据。
 * 审计事件既通过真实业务接口产生（登录、租户开通），也在测试内直接插入以覆盖历史数据、模型事件与
 * 公式注入边界；直接插入仍经过真实表结构、分区与 CHECK，不 mock 持久化。
 */
abstract class AuditTestSupport extends ApiIntegrationTestSupport {

    /** 团队默认时区偏移。 */
    protected static final ZoneOffset TEAM_OFFSET = ZoneOffset.ofHours(8);

    /** ISO 8601 带偏移格式。 */
    protected static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 插入审计事件时固定写入的请求 ID；用于断言响应不会返回 requestId。 */
    protected static final String REQUEST_ID = "req-audit-test";

    /** 插入审计事件时固定写入的伪造 IP；用于断言查询与 CSV 不返回 source_ip。 */
    protected static final String SOURCE_IP = "203.0.113.9";

    /**
     * 创建带租户管理员与普通成员的隔离租户。
     *
     * @param prefix 租户代码前缀
     * @return 测试夹具
     */
    protected TenantFixture createFixture(String prefix) {
        LoginSession platform = loginPlatformAdmin();
        UUID platformUserId =
                UUID.fromString(platform.body().path("user").path("id").asText());
        String tenantId = uniqueTenantCode(prefix);
        createTenant(platform, tenantId);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String adminUsername = "adm" + suffix;
        String memberUsername = "mem" + suffix;
        JsonNode admin =
                createUser(platform, tenantId, adminUsername, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        JsonNode member = createUser(platform, tenantId, memberUsername, "member-password-12", List.of("MEMBER"));
        return new TenantFixture(
                tenantId,
                platform,
                platformUserId,
                login(tenantId, adminUsername, "tenant-admin-password-12"),
                UUID.fromString(admin.path("id").asText()),
                adminUsername,
                login(tenantId, memberUsername, "member-password-12"),
                UUID.fromString(member.path("id").asText()),
                memberUsername);
    }

    /**
     * 在租户内再创建一个租户管理员并登录，用于审批与并发用例。
     *
     * @param fixture 测试夹具
     * @return 额外管理员会话
     */
    protected LoginSession createSecondAdmin(TenantFixture fixture) {
        String username = "adm" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createUser(
                fixture.platform(), fixture.tenantId(), username, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        return login(fixture.tenantId(), username, "tenant-admin-password-12");
    }

    /**
     * 直接插入一条审计事件；用于构造历史数据、模型事件与危险前缀。
     *
     * @param tenantId 租户
     * @param occurredAt 事件时间
     * @param actorType 操作者类型
     * @param actorId 操作者 ID
     * @param action 动作
     * @param targetType 目标类型，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果
     * @param detailsJson details JSON，可为空
     * @return 事件 ID
     */
    protected UUID insertAuditEvent(
            String tenantId,
            Instant occurredAt,
            String actorType,
            String actorId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String detailsJson) {
        UUID eventId = UuidV7.generate(occurredAt);
        jdbcClient
                .sql("""
                        INSERT INTO audit_event (
                            event_id, occurred_at, tenant_id, actor_type, actor_id, action,
                            target_type, target_id, outcome, trace_id, request_id, source_ip,
                            details, schema_version
                        ) VALUES (
                            :eventId, :occurredAt, :tenantId, :actorType, :actorId, :action,
                            :targetType, :targetId, :outcome, :traceId, :requestId, :sourceIp,
                            :details, 1
                        )
                        """)
                .param("eventId", BinaryUuids.toBytes(eventId))
                .param("occurredAt", SqlTimestamps.toDatabase(occurredAt))
                .param("tenantId", tenantId)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("outcome", outcome)
                .param("traceId", "trace-audit-test")
                .param("requestId", REQUEST_ID)
                .param("sourceIp", SOURCE_IP)
                .param("details", detailsJson)
                .update();
        return eventId;
    }

    /**
     * 使用当前时间插入事件。
     *
     * @param tenantId 租户
     * @param actorId 操作者 ID
     * @param action 动作
     * @param detailsJson details JSON，可为空
     * @return 事件 ID
     */
    protected UUID insertAuditEvent(String tenantId, String actorId, String action, String detailsJson) {
        return insertAuditEvent(
                tenantId, Instant.now().minusSeconds(1), "USER", actorId, action, null, null, "SUCCESS", detailsJson);
    }

    /**
     * 构造审计查询路径；查询值统一 URL 编码，避免时区偏移中的 {@code +} 被解释为空格。
     *
     * @param pairs 交替的键值对，值为 {@code null} 时跳过
     * @return 路径
     */
    protected String auditQueryPath(String... pairs) {
        StringBuilder path = new StringBuilder("/api/v1/audit-events");
        String separator = "?";
        for (int index = 0; index < pairs.length; index += 2) {
            String value = pairs[index + 1];
            if (value == null) {
                continue;
            }
            path.append(separator).append(encode(pairs[index])).append('=').append(encode(value));
            separator = "&";
        }
        return path.toString();
    }

    /**
     * 查询审计（原始响应，便于断言错误码）。
     *
     * @param session 会话
     * @param queryPath 查询路径
     * @return 响应
     */
    protected ApiTestClient.ApiResponse queryAudit(LoginSession session, String queryPath) {
        return client.get(queryPath, session.sessionCookie(), Map.of());
    }

    /**
     * 构造导出申请过滤器；只放入非空字段。
     *
     * @param occurredFrom 起始时间原文，可为空
     * @param occurredTo 结束时间原文，可为空
     * @param action 动作名，可为空
     * @param actorId 操作者 ID，可为空
     * @param targetType 目标类型，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果，可为空
     * @return 过滤器映射
     */
    protected Map<String, Object> filter(
            String occurredFrom,
            String occurredTo,
            String action,
            String actorId,
            String targetType,
            String targetId,
            String outcome) {
        Map<String, Object> filter = new LinkedHashMap<>();
        putIfPresent(filter, "occurredFrom", occurredFrom);
        putIfPresent(filter, "occurredTo", occurredTo);
        putIfPresent(filter, "action", action);
        putIfPresent(filter, "actorId", actorId);
        putIfPresent(filter, "targetType", targetType);
        putIfPresent(filter, "targetId", targetId);
        putIfPresent(filter, "outcome", outcome);
        return filter;
    }

    /**
     * 发起导出申请并返回原始响应。
     *
     * @param session 申请人会话
     * @param filter 过滤器
     * @return 响应
     */
    protected ApiTestClient.ApiResponse createExport(LoginSession session, Map<String, Object> filter) {
        return client.post(
                "/api/v1/audit-exports",
                client.jsonBody("filter", filter),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 发起导出申请并断言 201，返回导出请求 ID。
     *
     * @param session 申请人会话
     * @param filter 过滤器
     * @return 导出请求 ID
     */
    protected UUID createExportId(LoginSession session, Map<String, Object> filter) {
        ApiTestClient.ApiResponse response = createExport(session, filter);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(client.json(response).path("id").asText());
    }

    /**
     * 同租户审批导出。
     *
     * @param session 审批人会话
     * @param exportId 导出请求 ID
     * @param decision 决定
     * @param comment 意见，可为空
     * @return 响应
     */
    protected ApiTestClient.ApiResponse decide(LoginSession session, UUID exportId, String decision, String comment) {
        return client.post(
                "/api/v1/audit-exports/" + exportId + "/decision",
                client.jsonBody("decision", decision, "comment", comment),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 平台管理员审批目标租户导出。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param exportId 导出请求 ID
     * @param decision 决定
     * @param comment 意见，可为空
     * @return 响应
     */
    protected ApiTestClient.ApiResponse decidePlatform(
            LoginSession platform, String tenantId, UUID exportId, String decision, String comment) {
        return client.post(
                "/api/v1/admin/tenants/" + tenantId + "/audit-exports/" + exportId + "/decision",
                client.jsonBody("decision", decision, "comment", comment),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
    }

    /**
     * 下载导出 CSV。
     *
     * @param session 会话
     * @param exportId 导出请求 ID
     * @return 响应
     */
    protected ApiTestClient.ApiResponse download(LoginSession session, UUID exportId) {
        return client.get("/api/v1/audit-exports/" + exportId + "/download", session.sessionCookie(), Map.of());
    }

    /**
     * 断言响应体中的稳定错误码。
     *
     * @param response 响应
     * @param code 期望错误码
     */
    protected void assertCode(ApiTestClient.ApiResponse response, String code) {
        assertThat(client.json(response).path("code").asText())
                .as(response.body())
                .isEqualTo(code);
    }

    /**
     * 读取某租户某动作的审计行。
     *
     * @param tenantId 租户
     * @param action 动作
     * @return 审计行
     */
    protected List<AuditRow> tenantAuditRows(String tenantId, String action) {
        return jdbcClient
                .sql("""
                        SELECT tenant_id, actor_type, actor_id, action, target_type, target_id,
                               outcome, trace_id, details
                          FROM audit_event
                         WHERE tenant_id = :tenantId AND action = :action
                         ORDER BY occurred_at ASC, event_id ASC
                        """)
                .param("tenantId", tenantId)
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
     * URL 编码。
     *
     * @param value 原文
     * @return 编码值
     */
    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 当前时间偏移后的 ISO 8601 文本。
     *
     * @param hours 相对现在的小时数（负数为过去）
     * @return ISO 8601 带偏移文本
     */
    protected static String hoursAgo(long hours) {
        return TIME_FORMAT.format(OffsetDateTime.now(TEAM_OFFSET).minusHours(hours));
    }

    /**
     * 构造任意偏移的时间文本。
     *
     * @param instant 时间
     * @return ISO 8601 带偏移文本
     */
    protected static String iso(Instant instant) {
        return TIME_FORMAT.format(instant.atOffset(TEAM_OFFSET));
    }

    /**
     * 只放入非空值。
     *
     * @param map 目标映射
     * @param key 键
     * @param value 值
     */
    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * 租户夹具。
     *
     * @param tenantId 租户代码
     * @param platform 平台管理员会话
     * @param platformUserId 平台管理员账号 ID
     * @param tenantAdmin 租户管理员会话
     * @param tenantAdminId 租户管理员账号 ID
     * @param tenantAdminUsername 租户管理员登录名
     * @param member 普通成员会话
     * @param memberId 普通成员账号 ID
     * @param memberUsername 普通成员登录名
     */
    protected record TenantFixture(
            String tenantId,
            LoginSession platform,
            UUID platformUserId,
            LoginSession tenantAdmin,
            UUID tenantAdminId,
            String tenantAdminUsername,
            LoginSession member,
            UUID memberId,
            String memberUsername) {}

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
