package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IT-AUDIT-101/102/103：审计查询、策略端点、读取策略与平台边界。
 *
 * <p>使用真实 MySQL、真实迁移与 HTTP；每个用例使用唯一租户隔离数据。覆盖过滤组合、固定排序、分页上限、
 * 180 天边界、默认 24 小时、查询自身审计、普通员工 403、无写入口、模型事件 token 指标可查、
 * details fail-closed 与平台管理员只读平台租户。
 */
class AuditQueryApiTest extends AuditTestSupport {

    /** JSON 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 过滤组合、固定排序与分页元数据。
     */
    @Test
    @DisplayName("IT-AUDIT-101 过滤组合、固定排序与分页元数据")
    void listAudit_filtersAndPaginates() {
        TenantFixture fixture = createFixture("aq");
        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        for (int index = 0; index < 5; index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    base.plusSeconds(index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "file.uploaded",
                    "FILE",
                    "file-" + index,
                    index == 4 ? "DENIED" : "SUCCESS",
                    "{\"fileId\":\"file-" + index + "\",\"sizeBytes\":10,\"contentType\":\"text/plain\"}");
        }

        ApiTestClient.ApiResponse firstPage = queryAudit(
                fixture.tenantAdmin(),
                auditQueryPath(
                        "occurredFrom", hoursAgo(1),
                        "action", "file.uploaded",
                        "page", "1",
                        "size", "2"));
        assertThat(firstPage.status()).as(firstPage.body()).isEqualTo(200);
        JsonNode page = client.json(firstPage);
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(page.path("total").asLong()).isEqualTo(5);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        assertThat(page.path("items").size()).isEqualTo(2);
        JsonNode newest = page.path("items").get(0);
        JsonNode second = page.path("items").get(1);
        assertThat(Instant.parse(newest.path("occurredAt").asText()))
                .isAfter(Instant.parse(second.path("occurredAt").asText()));
        assertThat(newest.path("occurredAt").asText()).endsWith("+08:00");
        assertThat(newest.path("tenantId").asText()).isEqualTo(fixture.tenantId());
        assertThat(newest.path("actorDisplayName").asText()).isEqualTo("User " + fixture.tenantAdminUsername());
        assertThat(newest.path("details").path("sizeBytes").asInt()).isEqualTo(10);
        // 字段白名单：不返回 source_ip/request_id/schema_version。
        assertThat(newest.has("sourceIp")).isFalse();
        assertThat(newest.has("requestId")).isFalse();
        assertThat(newest.has("schemaVersion")).isFalse();

        JsonNode denied = client.json(queryAudit(
                fixture.tenantAdmin(),
                auditQueryPath(
                        "occurredFrom", hoursAgo(1),
                        "action", "file.uploaded",
                        "outcome", "DENIED")));
        assertThat(denied.path("total").asLong()).isEqualTo(1);
        assertThat(denied.path("items").get(0).path("outcome").asText()).isEqualTo("DENIED");

        JsonNode byActor = client.json(queryAudit(
                fixture.tenantAdmin(),
                auditQueryPath(
                        "occurredFrom", hoursAgo(1),
                        "action", "file.uploaded",
                        "actorId", fixture.tenantAdminId().toString())));
        assertThat(byActor.path("total").asLong()).isEqualTo(5);

        JsonNode byTarget = client.json(queryAudit(
                fixture.tenantAdmin(),
                auditQueryPath(
                        "occurredFrom", hoursAgo(1),
                        "action", "file.uploaded",
                        "targetType", "FILE",
                        "targetId", "file-3")));
        assertThat(byTarget.path("total").asLong()).isEqualTo(1);
        assertThat(byTarget.path("items").get(0).path("targetId").asText()).isEqualTo("file-3");
    }

    /**
     * 默认 24 小时窗口、180 天上限与分页硬上限。
     */
    @Test
    @DisplayName("IT-AUDIT-101 默认 24 小时窗口、180 天上限与分页硬上限")
    void listAudit_enforcesTimeAndPaginationLimits() {
        TenantFixture fixture = createFixture("aq");
        insertAuditEvent(
                fixture.tenantId(),
                Instant.now().minus(25, ChronoUnit.HOURS),
                "USER",
                fixture.tenantAdminId().toString(),
                "file.deleted",
                "FILE",
                "old-file",
                "SUCCESS",
                null);
        insertAuditEvent(
                fixture.tenantId(),
                Instant.now().minus(30, ChronoUnit.MINUTES),
                "USER",
                fixture.tenantAdminId().toString(),
                "file.deleted",
                "FILE",
                "recent-file",
                "SUCCESS",
                null);

        JsonNode defaultWindow =
                client.json(queryAudit(fixture.tenantAdmin(), auditQueryPath("action", "file.deleted")));
        assertThat(defaultWindow.path("total").asLong()).isEqualTo(1);
        assertThat(defaultWindow.path("items").get(0).path("targetId").asText()).isEqualTo("recent-file");

        JsonNode explicitWindow = client.json(queryAudit(
                fixture.tenantAdmin(),
                auditQueryPath(
                        "occurredFrom", hoursAgo(30),
                        "occurredTo", hoursAgo(1),
                        "action", "file.deleted")));
        assertThat(explicitWindow.path("total").asLong()).isEqualTo(1);
        assertThat(explicitWindow.path("items").get(0).path("targetId").asText())
                .isEqualTo("old-file");

        assertCode(
                queryAudit(
                        fixture.tenantAdmin(),
                        auditQueryPath("occurredFrom", hoursAgo(181 * 24), "occurredTo", hoursAgo(0))),
                "RICCON-AUDIT-400");
        assertCode(
                queryAudit(
                        fixture.tenantAdmin(),
                        auditQueryPath(
                                "occurredFrom", hoursAgo(1),
                                "occurredTo", iso(Instant.now().plus(10, ChronoUnit.MINUTES)))),
                "RICCON-AUDIT-400");
        assertCode(
                queryAudit(fixture.tenantAdmin(), auditQueryPath("occurredFrom", "2026-11-01T00:00:00")),
                "RICCON-AUDIT-400");
        assertCode(
                queryAudit(
                        fixture.tenantAdmin(), auditQueryPath("occurredFrom", hoursAgo(0), "occurredTo", hoursAgo(1))),
                "RICCON-AUDIT-400");
        assertCode(queryAudit(fixture.tenantAdmin(), auditQueryPath("page", "201")), "RICCON-AUDIT-400");
        assertCode(queryAudit(fixture.tenantAdmin(), auditQueryPath("size", "101")), "RICCON-AUDIT-400");
        assertCode(queryAudit(fixture.tenantAdmin(), auditQueryPath("targetType", "NOT_A_TYPE")), "RICCON-AUDIT-400");
    }

    /**
     * 普通员工 403、匿名 401、审计接口无写路由。
     */
    @Test
    @DisplayName("IT-AUDIT-102 普通员工 403、匿名 401、审计接口无写路由")
    void listAudit_rejectsMembersAnonymousAndWrites() {
        TenantFixture fixture = createFixture("aq");

        ApiTestClient.ApiResponse member = queryAudit(fixture.member(), auditQueryPath());
        assertThat(member.status()).as(member.body()).isEqualTo(403);
        assertCode(member, "RICCON-AUTH-403");

        ApiTestClient.ApiResponse anonymous = client.get(auditQueryPath());
        assertThat(anonymous.status()).as(anonymous.body()).isEqualTo(401);

        /*
         * 审计接口没有写路由，写请求不会成功；当前全局异常处理器把
         * HttpRequestMethodNotSupportedException 归入兜底 500（web/GlobalExceptionHandler 不在 B3b 写范围）。
         * 这里断言“请求被拒绝且不产生写入”，405 映射作为残余风险报告给 Lead/Verifier。
         */
        ApiTestClient.ApiResponse post = client.post(
                "/api/v1/audit-events",
                "{}",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(post.status()).isGreaterThanOrEqualTo(400);
        assertThat(post.body()).doesNotContain("file.uploaded").doesNotContain("eventId");

        ApiTestClient.ApiResponse delete = client.delete(
                "/api/v1/audit-events",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken());
        assertThat(delete.status()).isGreaterThanOrEqualTo(400);
        assertThat(tenantAuditRows(fixture.tenantId(), "audit.queried")).isEmpty();
    }

    /**
     * 查询成功后写 {@code audit.queried}，且不影响本次结果。
     */
    @Test
    @DisplayName("IT-AUDIT-103 查询自身审计 filtersHash/rowCount")
    void listAudit_writesQueryAudit() throws Exception {
        TenantFixture fixture = createFixture("aq");

        JsonNode empty = client.json(
                queryAudit(fixture.tenantAdmin(), auditQueryPath("action", "session.created", "size", "10")));
        assertThat(empty.path("total").asLong()).isZero();

        List<AuditRow> queryEvents = tenantAuditRows(fixture.tenantId(), "audit.queried");
        assertThat(queryEvents).hasSize(1);
        JsonNode details = objectMapper.readTree(queryEvents.get(0).details());
        assertThat(details.path("filtersHash").asText()).hasSize(64);
        assertThat(details.path("rowCount").asInt()).isZero();

        JsonNode second =
                client.json(queryAudit(fixture.tenantAdmin(), auditQueryPath("action", "audit.queried", "size", "10")));
        assertThat(second.path("total").asLong()).isEqualTo(1);
        JsonNode item = second.path("items").get(0);
        assertThat(item.path("targetType").asText()).isEqualTo("AUDIT");
        assertThat(item.path("details").path("filtersHash").asText()).hasSize(64);
        assertThat(item.has("sourceIp")).isFalse();
        assertThat(item.has("requestId")).isFalse();
    }

    /**
     * 模型事件 token 指标可查，100 行展示名批量解析。
     */
    @Test
    @DisplayName("IT-AUDIT-101 模型事件 promptTokens/completionTokens 可查且 100 行展示名")
    void listAudit_modelTokenMetricsAndDisplayNames() {
        TenantFixture fixture = createFixture("aq");
        Instant base = Instant.now().minus(20, ChronoUnit.MINUTES);
        for (int index = 0; index < 100; index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    base.plusSeconds(index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "model.invocation.completed",
                    "MODEL",
                    "fake-model",
                    "SUCCESS",
                    "{\"modelId\":\"fake-model\",\"durationMs\":12,\"promptTokens\":10,\"completionTokens\":20}");
        }

        ApiTestClient.ApiResponse response = queryAudit(
                fixture.tenantAdmin(),
                auditQueryPath(
                        "occurredFrom", hoursAgo(1),
                        "action", "model.invocation.completed",
                        "size", "100"));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode page = client.json(response);
        assertThat(page.path("total").asLong()).isEqualTo(100);
        assertThat(page.path("items").size()).isEqualTo(100);
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("details").path("promptTokens").asInt()).isEqualTo(10);
            assertThat(item.path("details").path("completionTokens").asInt()).isEqualTo(20);
            assertThat(item.path("actorDisplayName").asText()).isEqualTo("User " + fixture.tenantAdminUsername());
        }
    }

    /**
     * details 读取白名单 fail-closed：白名单外键或未知动作非空 details 返回 500。
     */
    @Test
    @DisplayName("IT-AUDIT-103 details 白名单 fail-closed（敏感键/未知动作）")
    void listAudit_failsClosedOnDisallowedDetails() {
        TenantFixture fixture = createFixture("aq");
        insertAuditEvent(
                fixture.tenantId(),
                Instant.now().minusSeconds(5),
                "USER",
                fixture.tenantAdminId().toString(),
                "auth.login.succeeded",
                "SESSION",
                null,
                "SUCCESS",
                "{\"token\":\"should-not-be-returned\"}");
        insertAuditEvent(
                fixture.tenantId(),
                Instant.now().minusSeconds(4),
                "USER",
                fixture.tenantAdminId().toString(),
                "custom.unknown",
                null,
                null,
                "SUCCESS",
                "{\"foo\":\"bar\"}");
        insertAuditEvent(
                fixture.tenantId(),
                Instant.now().minusSeconds(3),
                "USER",
                fixture.tenantAdminId().toString(),
                "custom.unknown.empty",
                null,
                null,
                "SUCCESS",
                null);

        ApiTestClient.ApiResponse sensitive =
                queryAudit(fixture.tenantAdmin(), auditQueryPath("action", "auth.login.succeeded", "size", "10"));
        assertThat(sensitive.status()).as(sensitive.body()).isEqualTo(500);
        assertCode(sensitive, "RICCON-AUDIT-500");
        assertThat(sensitive.body()).doesNotContain("should-not-be-returned");

        ApiTestClient.ApiResponse unknown =
                queryAudit(fixture.tenantAdmin(), auditQueryPath("action", "custom.unknown", "size", "10"));
        assertThat(unknown.status()).as(unknown.body()).isEqualTo(500);
        assertCode(unknown, "RICCON-AUDIT-500");
        assertThat(unknown.body()).doesNotContain("foo");

        ApiTestClient.ApiResponse emptyDetails =
                queryAudit(fixture.tenantAdmin(), auditQueryPath("action", "custom.unknown.empty", "size", "10"));
        assertThat(emptyDetails.status()).as(emptyDetails.body()).isEqualTo(200);
        assertThat(client.json(emptyDetails).path("total").asLong()).isEqualTo(1);
    }

    /**
     * 策略端点返回保留天数、范围、分页、动作与导出限制。
     */
    @Test
    @DisplayName("IT-AUDIT-101 策略端点返回保留/范围/动作/导出限制")
    void policy_returnsAuditPolicy() {
        TenantFixture fixture = createFixture("aq");
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/audit-events/policy", fixture.tenantAdmin().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode policy = client.json(response);
        assertThat(policy.path("retentionDays").asInt()).isGreaterThanOrEqualTo(180);
        assertThat(policy.path("maxRangeDays").asInt()).isEqualTo(180);
        assertThat(policy.path("pageSizeMax").asInt()).isEqualTo(100);
        assertThat(policy.path("maxPage").asInt()).isEqualTo(200);
        assertThat(policy.path("exportLimits").path("rowLimit").asInt()).isEqualTo(10000);
        assertThat(policy.path("exportLimits").path("ttlHours").asInt()).isEqualTo(24);
        List<String> actions = objectMapper.convertValue(
                policy.path("actions"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(actions)
                .contains(
                        "audit.queried",
                        "audit.export.requested",
                        "audit.export.approved",
                        "data.exported",
                        "model.invocation.completed",
                        "user.access.denied");

        ApiTestClient.ApiResponse member =
                client.get("/api/v1/audit-events/policy", fixture.member().sessionCookie(), Map.of());
        assertThat(member.status()).as(member.body()).isEqualTo(403);
    }

    /**
     * 平台管理员只能查询平台租户自身事件，租户查询只返回本租户。
     */
    @Test
    @DisplayName("IT-AUDIT-103 平台管理员只读平台租户，租户查询只返回本租户")
    void listAudit_platformScopeIsPlatformTenantOnly() {
        TenantFixture first = createFixture("aq");
        TenantFixture second = createFixture("aq");
        insertAuditEvent(
                first.tenantId(),
                Instant.now().minusSeconds(2),
                "USER",
                first.tenantAdminId().toString(),
                "file.uploaded",
                "FILE",
                "secret-file",
                "SUCCESS",
                "{\"fileId\":\"secret-file\"}");
        insertAuditEvent(
                second.tenantId(),
                Instant.now().minusSeconds(2),
                "USER",
                second.tenantAdminId().toString(),
                "file.uploaded",
                "FILE",
                "other-file",
                "SUCCESS",
                "{\"fileId\":\"other-file\"}");

        JsonNode platformPage =
                client.json(queryAudit(first.platform(), auditQueryPath("action", "tenant.created", "size", "100")));
        assertThat(platformPage.path("total").asLong()).isGreaterThan(0);
        for (JsonNode item : platformPage.path("items")) {
            assertThat(item.path("tenantId").asText()).isEqualTo("platform");
        }

        JsonNode platformFiles =
                client.json(queryAudit(first.platform(), auditQueryPath("action", "file.uploaded", "size", "100")));
        assertThat(platformFiles.path("total").asLong()).isZero();

        JsonNode tenantPage =
                client.json(queryAudit(first.tenantAdmin(), auditQueryPath("action", "file.uploaded", "size", "100")));
        assertThat(tenantPage.path("total").asLong()).isEqualTo(1);
        assertThat(tenantPage.path("items").get(0).path("tenantId").asText()).isEqualTo(first.tenantId());
        assertThat(tenantPage.path("items").get(0).path("targetId").asText()).isEqualTo("secret-file");
        assertThat(tenantPage.toString()).doesNotContain("other-file");
    }

    /**
     * 拒绝伪造的租户查询参数（TenantContextFilter 纵深）。
     */
    @Test
    @DisplayName("IT-AUDIT-103 查询不接受 tenantId 参数")
    void listAudit_rejectsForgedTenantParameter() {
        TenantFixture fixture = createFixture("aq");
        ApiTestClient.ApiResponse response =
                queryAudit(fixture.tenantAdmin(), auditQueryPath("tenantId", "platform", "size", "10"));
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertCode(response, "RICCON-AUTH-403");
        assertThat(tenantAuditRows(fixture.tenantId(), "tenant.access.denied")).isNotEmpty();
    }
}
