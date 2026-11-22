package com.riccon.api.admin.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.quota.QuotaApiTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 配额读写端点、权限与跨租户边界（IT-QUOTA-103 + TEN 配额子集）。
 *
 * <p>覆盖租户管理员只读、员工无入口、平台租户聚合无用户明细、跨租户/不存在目标 404、整体替换与审计。
 */
class QuotaAdminApiTest extends QuotaApiTestSupport {

    /** 每个用例前恢复执行模式，保证读取端点不依赖开关。 */
    @BeforeEach
    void enableEnforcement() {
        setEnforcement(true);
    }

    /**
     * IT-QUOTA-103：租户管理员读取本租户默认配额与用户明细。
     */
    @Test
    @DisplayName("IT-QUOTA-103 租户管理员读取本租户配额")
    void tenantAdmin_readsOwnQuotaAndUsers() {
        Fixture fixture = fixture();

        ApiTestClient.ApiResponse tenantResponse =
                client.get("/api/v1/quotas", fixture.admin().sessionCookie(), Map.of());
        assertThat(tenantResponse.status()).as(tenantResponse.body()).isEqualTo(200);
        JsonNode quota = client.json(tenantResponse);
        assertThat(quota.path("tenantId").asText()).isEqualTo(fixture.tenant());
        assertThat(quota.path("tenantCode").asText()).isEqualTo(fixture.tenant());
        assertThat(quota.path("windows")).hasSize(2);
        assertWindow(quota.path("windows").get(0), "DAILY", 10_000, "DEPLOYMENT_DEFAULT");
        assertWindow(quota.path("windows").get(1), "MONTHLY", 200_000, "DEPLOYMENT_DEFAULT");

        String otherTenant = uniqueTenantCode("tother");
        createTenant(fixture.platform(), otherTenant);
        createUser(
                fixture.platform(),
                otherTenant,
                "other" + UUID.randomUUID().toString().substring(0, 8),
                "member-password-12",
                List.of("MEMBER"));

        ApiTestClient.ApiResponse usersResponse = client.get(
                "/api/v1/quotas/users?page=1&size=100", fixture.admin().sessionCookie(), Map.of());
        assertThat(usersResponse.status()).as(usersResponse.body()).isEqualTo(200);
        JsonNode users = client.json(usersResponse);
        assertThat(users.path("items").isArray()).isTrue();
        List<String> usernames = new java.util.ArrayList<>();
        users.path("items").forEach(item -> usernames.add(item.path("username").asText()));
        assertThat(usernames)
                .contains(
                        fixture.admin().body().path("user").path("username").asText(),
                        fixture.member().body().path("user").path("username").asText());
        assertThat(usernames).noneMatch(name -> name.startsWith("other"));
        JsonNode memberQuota = findUser(users, userId(fixture.member()));
        assertWindow(memberQuota.path("windows").get(0), "DAILY", 1_000, "DEPLOYMENT_DEFAULT");
    }

    /**
     * IT-QUOTA-103：员工无入口、租户管理员不能访问平台写接口。
     */
    @Test
    @DisplayName("IT-QUOTA-103 员工与租户管理员越权被拒绝")
    void forbiddenUsers_cannotReadPlatformOrWrite() {
        Fixture fixture = fixture();
        assertForbidden(client.get("/api/v1/quotas", fixture.member().sessionCookie(), Map.of()));
        assertForbidden(client.get("/api/v1/quotas/users", fixture.member().sessionCookie(), Map.of()));
        assertForbidden(client.get("/api/v1/admin/quotas", fixture.admin().sessionCookie(), Map.of()));
        assertForbidden(putTenantQuota(fixture.admin(), fixture.tenant(), 1, 1));
        assertForbidden(putUserQuota(fixture.admin(), fixture.tenant(), userId(fixture.member()), 1, 1));
    }

    /**
     * IT-QUOTA-103：平台列表只返回租户聚合，不含用户字段。
     */
    @Test
    @DisplayName("IT-QUOTA-103 平台列表无用户明细")
    void platformList_hasNoUserFields() {
        Fixture fixture = fixture();

        JsonNode firstPage = adminQuotaPage(fixture.platform(), 1);
        assertThat(firstPage.path("page").asInt()).isEqualTo(1);
        assertThat(firstPage.path("size").asInt()).isEqualTo(100);
        // 共享库里租户按 created_at ASC 排序，新 fixture 可能落在最后一页；不能假设第 1 页。
        JsonNode row = findTenantAcrossPages(fixture.platform(), fixture.tenant());
        assertThat(row.path("tenantCode").asText()).isEqualTo(fixture.tenant());
        assertThat(row.path("tenantName").asText()).isNotBlank();
        assertThat(row.path("windows")).hasSize(2);
        assertThat(row.has("userId")).isFalse();
        assertThat(row.has("username")).isFalse();
        assertThat(row.has("displayName")).isFalse();
        assertWindow(row.path("windows").get(0), "DAILY", 10_000, "DEPLOYMENT_DEFAULT");
    }

    /**
     * IT-QUOTA-103：平台整体替换租户配额并写两条审计（每个窗口一条）。
     */
    @Test
    @DisplayName("IT-QUOTA-103 平台写租户配额并审计")
    void platformWritesTenantQuota_andAudits() {
        Fixture fixture = fixture();

        JsonNode first = setTenantQuota(fixture.platform(), fixture.tenant(), 5, null);
        assertWindow(first.path("windows").get(0), "DAILY", 5, "EXPLICIT");
        assertThat(first.path("windows").get(1).path("limit").isNull()).isTrue();
        assertThat(first.path("windows").get(1).path("source").asText()).isEqualTo("EXPLICIT_UNLIMITED");
        assertThat(limitRowCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);

        JsonNode second = setTenantQuota(fixture.platform(), fixture.tenant(), 7, 8);
        assertWindow(second.path("windows").get(0), "DAILY", 7, "EXPLICIT");
        assertWindow(second.path("windows").get(1), "MONTHLY", 8, "EXPLICIT");

        List<AuditRow> audits = auditRows("quota.limit.changed").stream()
                .filter(row -> "platform".equals(row.tenantId()))
                .filter(row -> fixture.tenant().equals(row.targetId()))
                .toList();
        assertThat(audits).hasSize(4);
        assertThat(audits).allSatisfy(row -> {
            assertThat(row.actorType()).isEqualTo("USER");
            assertThat(row.actorId()).isEqualTo(userId(fixture.platform()));
            assertThat(row.targetType()).isEqualTo("QUOTA");
            assertThat(row.outcome()).isEqualTo("SUCCESS");
        });
        List<JsonNode> allDetails = audits.stream().map(this::details).toList();
        assertThat(allDetails).anySatisfy(details -> {
            assertThat(details.path("window").asText()).isEqualTo("DAILY");
            assertThat(details.path("limit").asInt()).isEqualTo(5);
            assertThat(details.has("previousLimit")).isTrue();
            assertThat(details.path("previousLimit").isNull()).isTrue();
        });
        assertThat(allDetails).anySatisfy(details -> {
            assertThat(details.path("window").asText()).isEqualTo("DAILY");
            assertThat(details.path("limit").asInt()).isEqualTo(7);
            assertThat(details.path("previousLimit").asInt()).isEqualTo(5);
        });
        // 明确不限在审计中写 0（AuditDetailsPolicy 不允许 limit 为 null）；响应仍是 limit=null。
        assertThat(allDetails).anySatisfy(details -> {
            assertThat(details.path("window").asText()).isEqualTo("MONTHLY");
            assertThat(details.path("limit").asInt()).isZero();
            assertThat(details.has("previousLimit")).isTrue();
            assertThat(details.path("previousLimit").isNull()).isTrue();
        });
        assertThat(allDetails).anySatisfy(details -> {
            assertThat(details.path("window").asText()).isEqualTo("MONTHLY");
            assertThat(details.path("limit").asInt()).isEqualTo(8);
            assertThat(details.has("previousLimit")).isTrue();
            assertThat(details.path("previousLimit").isNull()).isTrue();
        });

        // 审计查询端必须能读出 JSON null（first/monthly 为 null，daily 第二次为 5）。
        ApiTestClient.ApiResponse auditResponse = client.get(
                "/api/v1/audit-events?action=quota.limit.changed&targetId=" + fixture.tenant() + "&page=1&size=100",
                fixture.platform().sessionCookie(),
                Map.of());
        assertThat(auditResponse.status()).as(auditResponse.body()).isEqualTo(200);
        JsonNode auditPage = client.json(auditResponse);
        assertThat(auditPage.path("items")).hasSize(4);
        assertThat(auditPage.path("items")).anySatisfy(item -> {
            JsonNode auditDetails = item.path("details");
            assertThat(auditDetails.path("window").asText()).isEqualTo("DAILY");
            assertThat(auditDetails.path("limit").asInt()).isEqualTo(5);
            assertThat(auditDetails.has("previousLimit")).isTrue();
            assertThat(auditDetails.path("previousLimit").isNull()).isTrue();
        });
        assertThat(auditPage.path("items")).anySatisfy(item -> {
            JsonNode auditDetails = item.path("details");
            assertThat(auditDetails.path("window").asText()).isEqualTo("DAILY");
            assertThat(auditDetails.path("limit").asInt()).isEqualTo(7);
            assertThat(auditDetails.path("previousLimit").asInt()).isEqualTo(5);
        });

        ApiTestClient.ApiResponse tenantResponse =
                client.get("/api/v1/quotas", fixture.admin().sessionCookie(), Map.of());
        JsonNode quota = client.json(tenantResponse);
        assertWindow(quota.path("windows").get(0), "DAILY", 7, "EXPLICIT");
    }

    /**
     * IT-QUOTA-103：平台整体替换用户配额并在租户用户明细中可见。
     */
    @Test
    @DisplayName("IT-QUOTA-103 平台写用户配额")
    void platformWritesUserQuota() {
        Fixture fixture = fixture();
        String memberId = userId(fixture.member());

        JsonNode updated = setUserQuota(fixture.platform(), fixture.tenant(), memberId, 3, null);

        assertThat(updated.path("userId").asText()).isEqualTo(memberId);
        assertThat(updated.path("username").asText())
                .isEqualTo(fixture.member().body().path("user").path("username").asText());
        assertWindow(updated.path("windows").get(0), "DAILY", 3, "EXPLICIT");
        assertThat(updated.path("windows").get(1).path("source").asText()).isEqualTo("EXPLICIT_UNLIMITED");
        List<AuditRow> audits = auditRows("quota.limit.changed").stream()
                .filter(row -> "platform".equals(row.tenantId()))
                .filter(row -> memberId.equals(row.targetId()))
                .toList();
        assertThat(audits).hasSize(2);
        assertThat(details(audits.getFirst()).path("scopeType").asText()).isEqualTo("USER");

        ApiTestClient.ApiResponse usersResponse = client.get(
                "/api/v1/quotas/users?page=1&size=100", fixture.admin().sessionCookie(), Map.of());
        JsonNode userQuota = findUser(client.json(usersResponse), memberId);
        assertWindow(userQuota.path("windows").get(0), "DAILY", 3, "EXPLICIT");
    }

    /**
     * IT-QUOTA-103：跨租户与不存在目标统一 404。
     */
    @Test
    @DisplayName("IT-QUOTA-103 跨租户与不存在目标 404")
    void crossTenantAndMissingTargets_notFound() {
        Fixture fixture = fixture();
        String otherTenant = uniqueTenantCode("tquota");
        createTenant(fixture.platform(), otherTenant);
        JsonNode otherUser = createUser(
                fixture.platform(),
                otherTenant,
                "quser" + UUID.randomUUID().toString().substring(0, 8),
                "member-password-12",
                List.of("MEMBER"));

        ApiTestClient.ApiResponse missingTenant = putTenantQuota(
                fixture.platform(), "missing" + UUID.randomUUID().toString().substring(0, 8), 1, 1);
        assertThat(missingTenant.status()).isEqualTo(404);
        assertThat(client.json(missingTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse crossTenantUser = putUserQuota(
                fixture.platform(), fixture.tenant(), otherUser.path("id").asText(), 1, 1);
        assertThat(crossTenantUser.status()).isEqualTo(404);
        assertThat(client.json(crossTenantUser).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(limitRowCount(fixture.tenant(), "USER", otherUser.path("id").asText(), "DAILY"))
                .isZero();
    }

    /**
     * 配额请求体非法统一 400 RICCON-QUOTA-400。
     */
    @Test
    @DisplayName("IT-QUOTA-103 非法请求体返回 QUOTA-400")
    void invalidBodies_returnQuota400() {
        Fixture fixture = fixture();
        String path = "/api/v1/admin/tenants/" + fixture.tenant() + "/quotas";

        assertInvalid(client.put(
                path,
                "{\"limits\":{\"daily\":1}}",
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken()));
        assertInvalid(client.put(
                path,
                "{\"limits\":{\"daily\":-1,\"monthly\":1}}",
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken()));
        assertInvalid(client.put(
                path,
                "{\"limits\":{\"daily\":1,\"monthly\":1},\"extra\":true}",
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken()));
    }

    /**
     * 断言窗口字段。
     *
     * @param window 窗口节点
     * @param kind 窗口类型
     * @param limit 限额；{@code null} 表示不限
     * @param source 来源
     */
    private void assertWindow(JsonNode window, String kind, Integer limit, String source) {
        assertThat(window.path("window").asText()).isEqualTo(kind);
        if (limit == null) {
            assertThat(window.path("limit").isNull()).isTrue();
        } else {
            assertThat(window.path("limit").asInt()).isEqualTo(limit);
        }
        assertThat(window.path("used").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(window.path("resetsAt").asText()).endsWith("+08:00");
        assertThat(window.path("source").asText()).isEqualTo(source);
    }

    /**
     * 断言 403 统一错误码。
     *
     * @param response 响应
     */
    private void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /**
     * 断言非法请求体 400。
     *
     * @param response 响应
     */
    private void assertInvalid(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-QUOTA-400");
    }

    /**
     * 读取平台配额分页并断言 200。
     *
     * @param platform 平台管理员
     * @param page 页码
     * @return 分页 JSON
     */
    private JsonNode adminQuotaPage(LoginSession platform, int page) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/quotas?page=" + page + "&size=100", platform.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 跨页查找租户行，避免共享库里新租户落在第 1 页之外造成假失败。
     *
     * <p>租户按 {@code created_at ASC} 排序；fixture 是本次新建租户，优先从最后一页倒序查找，
     * 不使用超过 OpenAPI 上限 100 的 size。
     *
     * @param platform 平台管理员
     * @param tenant 租户
     * @return 行
     */
    private JsonNode findTenantAcrossPages(LoginSession platform, String tenant) {
        JsonNode first = adminQuotaPage(platform, 1);
        JsonNode found = findTenantOrNull(first, tenant);
        if (found != null) {
            return found;
        }
        int totalPages = first.path("totalPages").asInt();
        for (int page = totalPages; page >= 2; page--) {
            found = findTenantOrNull(adminQuotaPage(platform, page), tenant);
            if (found != null) {
                return found;
            }
        }
        throw new AssertionError("tenant quota row not found across " + totalPages + " pages: " + tenant);
    }

    /**
     * 在分页中查找租户行。
     *
     * @param page 分页 JSON
     * @param tenant 租户
     * @return 行；未找到时返回 {@code null}
     */
    private JsonNode findTenantOrNull(JsonNode page, String tenant) {
        for (JsonNode item : page.path("items")) {
            if (tenant.equals(item.path("tenantId").asText())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 在用户分页中查找用户行。
     *
     * @param page 分页 JSON
     * @param userId 用户 ID
     * @return 行
     */
    private JsonNode findUser(JsonNode page, String userId) {
        for (JsonNode item : page.path("items")) {
            if (userId.equals(item.path("userId").asText())) {
                return item;
            }
        }
        throw new AssertionError("user quota row not found: " + userId);
    }
}
