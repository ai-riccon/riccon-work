package com.riccon.api.admin.cost;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.cost.CostApiTestSupport;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 平台成本租户过滤的角色与路径边界（REV-TASK-0108-001 F-006 / TASK-0142）。
 *
 * <p>覆盖 {@code TenantContextFilter} 的窄例外：只有 {@code platform} 租户、{@code GET
 * /api/v1/admin/costs} 且具备 {@code platform.cost.read} 的主体可以使用 {@code tenantId} 过滤。平台非管理员、
 * 租户端点的伪造查询参数/请求头、非 GET 方法与 trailing slash 全部返回 403 {@code RICCON-AUTH-403}，
 * 响应不包含租户、金额或明细；过滤器拒绝写 {@code tenant.access.denied} 审计（{@code attemptedTenant}、
 * {@code reason}）。
 */
@Tag("security")
class PlatformCostTenantFilterTest extends CostApiTestSupport {

    /** 本类专用的平台成本测试窗口（北京时间自然日），避免与其他成本测试的日期相互影响。 */
    private static final LocalDate WINDOW_DAY = LocalDate.of(2027, 4, 7);

    /** 窗口内查询参数。 */
    private static final String WINDOW_QUERY = "from=2027-04-07&to=2027-04-07";

    /** 本类创建的模型 ID；用于精确删除对应调用行。 */
    private final List<UUID> createdModelIds = new ArrayList<>();

    /**
     * 清理保留窗口内的遗留调用行。
     *
     * <p>窗口只归本类使用；若上次运行在清理前中断，遗留行会让聚合金额与租户桶数量漂移。删除范围是精确的
     * 北京时间自然日半开区间，不触及本套件其他测试使用的日期。
     */
    @BeforeEach
    void clearReservedCostWindow() {
        jdbcClient
                .sql("""
                        DELETE FROM model_invocation
                         WHERE started_at >= :fromInstant
                           AND started_at < :toInstant
                        """)
                .param("fromInstant", utc("2027-04-06T16:00:00Z"))
                .param("toInstant", utc("2027-04-07T16:00:00Z"))
                .update();
    }

    /**
     * 按本类创建的模型 ID 精确删除调用行，避免影响后续测试或全量套件。
     */
    @AfterEach
    void cleanupCostInvocations() {
        for (UUID modelId : createdModelIds) {
            jdbcClient
                    .sql("DELETE FROM model_invocation WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
        }
        createdModelIds.clear();
    }

    /**
     * 平台非管理员（{@code platform} 租户但无 {@code platform.cost.read}）不能读取任何成本数据。
     *
     * <p>不带过滤参数时由 {@code PermissionInterceptor} 拒绝；带查询参数或 {@code X-Tenant-Id} 头时由
     * {@code TenantContextFilter} 先校验权限再拒绝，两条路径都必须 403 且不泄露租户、金额、明细。
     */
    @Test
    @DisplayName("F-006：平台非管理员读取成本一律 403 且无数据")
    void platformCost_platformPrincipalWithoutPermission_isForbiddenWithoutCostData() {
        Fixture fixture = fixture();
        TenantOther other = seedTwoTenantCosts(fixture);
        LoginSession platformUser = seedAndLoginLocalUser(
                fixture.platform(),
                "platform",
                "platcost" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "platform-user-password-12",
                List.of());
        UUID platformUserId = actorId(platformUser);

        String path = "/api/v1/admin/costs?" + WINDOW_QUERY + "&groupBy=TENANT";
        assertForbiddenWithoutCostData(
                client.get(path, platformUser.sessionCookie(), Map.of()), fixture.tenant(), other.tenant());

        assertForbiddenWithoutCostData(
                client.get(path + "&tenantId=" + other.tenant(), platformUser.sessionCookie(), Map.of()),
                fixture.tenant(),
                other.tenant());
        assertForbiddenWithoutCostData(
                client.get(path, platformUser.sessionCookie(), Map.of("X-Tenant-Id", other.tenant())),
                fixture.tenant(),
                other.tenant());

        List<AuditRow> denied = auditRows("tenant.access.denied").stream()
                .filter(row -> platformUserId.toString().equals(row.actorId()))
                .toList();
        assertThat(denied).as("查询参数与请求头各写一条拒绝审计").hasSize(2);
        assertThat(denied).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.actorType()).isEqualTo("USER");
            assertThat(row.outcome()).isEqualTo("DENIED");
            assertThat(row.targetType()).isEqualTo("TENANT");
            assertThat(row.targetId()).isEqualTo(other.tenant());
            assertThat(row.details()).contains(other.tenant(), "missing_permission");
        });
    }

    /**
     * 租户管理员在租户成本端点上伪造查询参数或请求头都被拒绝并写审计。
     */
    @Test
    @DisplayName("F-006：租户管理员伪造租户标识读取 /api/v1/costs 403 并审计")
    void tenantCost_forgedTenantIdentifiers_areForbiddenAndAudited() {
        Fixture fixture = fixture();
        TenantOther other = seedTwoTenantCosts(fixture);
        String path = "/api/v1/costs?" + WINDOW_QUERY + "&groupBy=DAY";

        assertForbiddenWithoutCostData(
                client.get(path + "&tenantId=" + other.tenant(), fixture.admin().sessionCookie(), Map.of()),
                fixture.tenant(),
                other.tenant());
        assertForbiddenWithoutCostData(
                client.get(path, fixture.admin().sessionCookie(), Map.of("X-Tenant-Id", other.tenant())),
                fixture.tenant(),
                other.tenant());

        String adminId = userId(fixture.admin());
        List<AuditRow> denied = auditRows("tenant.access.denied").stream()
                .filter(row -> adminId.equals(row.actorId()))
                .toList();
        assertThat(denied).as("查询参数与请求头各写一条拒绝审计").hasSize(2);
        assertThat(denied).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(fixture.tenant());
            assertThat(row.actorType()).isEqualTo("USER");
            assertThat(row.outcome()).isEqualTo("DENIED");
            assertThat(row.targetId()).isEqualTo(other.tenant());
            assertThat(row.details()).contains(other.tenant(), "tenant_mismatch");
        });
    }

    /**
     * 平台管理员的授权范围内过滤：查询参数决定聚合范围，请求头被忽略且不改变结果。
     */
    @Test
    @DisplayName("F-006：平台管理员 tenantId 过滤生效且 X-Tenant-Id 不参与过滤")
    void platformCost_platformAdminFilterAndHeader_followQueryOnly() {
        Fixture fixture = fixture();
        TenantOther other = seedTwoTenantCosts(fixture);
        String path = "/api/v1/admin/costs?" + WINDOW_QUERY + "&groupBy=TENANT";

        JsonNode filtered = platformReport(fixture, path + "&tenantId=" + other.tenant(), Map.of());
        assertThat(filtered.path("items")).hasSize(1);
        assertThat(filtered.path("items").get(0).path("tenantId").asString()).isEqualTo(other.tenant());
        assertThat(filtered.path("totals").path("amount").asString()).isEqualTo("0.006000");

        // 同时携带请求头与查询参数时以查询参数为准；头不参与聚合。
        JsonNode queryWins =
                platformReport(fixture, path + "&tenantId=" + other.tenant(), Map.of("X-Tenant-Id", fixture.tenant()));
        assertThat(queryWins.path("items")).hasSize(1);
        assertThat(queryWins.path("items").get(0).path("tenantId").asString()).isEqualTo(other.tenant());
        assertThat(queryWins.path("totals").path("amount").asString()).isEqualTo("0.006000");

        // 只带请求头时不改变过滤结果：平台管理员仍看到授权范围内的全租户聚合。
        JsonNode headerOnly = platformReport(fixture, path, Map.of("X-Tenant-Id", other.tenant()));
        assertThat(headerOnly.path("items")).hasSize(2);
        assertThat(headerOnly.path("totals").path("amount").asString()).isEqualTo("0.009000");
        List<String> tenants = new ArrayList<>();
        headerOnly
                .path("items")
                .forEach(item -> tenants.add(item.path("tenantId").asString()));
        assertThat(tenants).containsExactlyInAnyOrder(fixture.tenant(), other.tenant());
    }

    /**
     * 例外只覆盖精确的 {@code GET /api/v1/admin/costs}：其他路径、其他方法与 trailing slash 仍拒绝。
     */
    @Test
    @DisplayName("F-006：其他路径与方法不享受平台成本例外")
    void platformCost_otherPathsAndMethods_areForbiddenAndAudited() {
        Fixture fixture = fixture();
        TenantOther other = seedTwoTenantCosts(fixture);

        assertForbiddenWithoutCostData(
                client.get(
                        "/api/v1/users?tenantId=" + other.tenant(),
                        fixture.admin().sessionCookie(),
                        Map.of()),
                fixture.tenant(),
                other.tenant());

        assertForbiddenWithoutCostData(
                client.post(
                        "/api/v1/admin/costs?tenantId=" + other.tenant(),
                        null,
                        fixture.platform().sessionCookie(),
                        fixture.platform().csrfToken(),
                        Map.of()),
                fixture.tenant(),
                other.tenant());

        assertForbiddenWithoutCostData(
                client.get(
                        "/api/v1/admin/costs/?tenantId=" + other.tenant(),
                        fixture.platform().sessionCookie(),
                        Map.of()),
                fixture.tenant(),
                other.tenant());

        String adminId = userId(fixture.admin());
        assertThat(auditRows("tenant.access.denied")).anySatisfy(row -> {
            assertThat(row.actorId()).isEqualTo(adminId);
            assertThat(row.tenantId()).isEqualTo(fixture.tenant());
            assertThat(row.targetId()).isEqualTo(other.tenant());
            assertThat(row.details()).contains(other.tenant(), "tenant_mismatch");
        });

        String platformAdminId = userId(fixture.platform());
        List<AuditRow> platformDenied = auditRows("tenant.access.denied").stream()
                .filter(row -> platformAdminId.equals(row.actorId()))
                .filter(row -> other.tenant().equals(row.targetId()))
                .toList();
        assertThat(platformDenied).as("POST 与 trailing slash 各写一条拒绝审计").hasSize(2);
        assertThat(platformDenied).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.outcome()).isEqualTo("DENIED");
            assertThat(row.details()).contains(other.tenant(), "tenant_mismatch");
        });
    }

    /**
     * 写入两个租户的 PRICED 调用，供平台聚合断言使用。
     *
     * <p>测试窗口只归本类使用；金额固定为 1000×3/1M=0.003000 与 2000×3/1M=0.006000，合计 0.009000。
     *
     * @param fixture 主夹具
     * @return 另一个租户
     */
    private TenantOther seedTwoTenantCosts(Fixture fixture) {
        TenantOther other = createOtherTenant(fixture);
        UUID modelId = createPricedModel("3.000000", "6.000000");
        createdModelIds.add(modelId);
        pricedInvocation(
                fixture.tenant(),
                UUID.fromString(userId(fixture.admin())),
                modelId,
                Instant.parse("2027-04-07T01:00:00Z"),
                WINDOW_DAY,
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        pricedInvocation(
                other.tenant(),
                other.userId(),
                modelId,
                Instant.parse("2027-04-07T02:00:00Z"),
                WINDOW_DAY,
                2000,
                0,
                "3.000000",
                "6.000000",
                "0.006000");
        return other;
    }

    /**
     * 创建第二个租户及其员工。
     *
     * @param fixture 主夹具
     * @return 其他租户
     */
    private TenantOther createOtherTenant(Fixture fixture) {
        String tenant = uniqueTenantCode("tcostfilter");
        createTenant(fixture.platform(), tenant);
        String username =
                "costfilter" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonNode user = createUser(fixture.platform(), tenant, username, "member-password-12", List.of("MEMBER"));
        return new TenantOther(tenant, UUID.fromString(user.path("id").asString()));
    }

    /**
     * 查询平台报告并断言 200。
     *
     * @param fixture 夹具
     * @param path 已包含窗口参数的请求路径
     * @param headers 额外请求头
     * @return 报告
     */
    private JsonNode platformReport(Fixture fixture, String path, Map<String, String> headers) {
        ApiTestClient.ApiResponse response = client.get(path, fixture.platform().sessionCookie(), headers);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 断言 403 {@code RICCON-AUTH-403}，且响应不包含成本明细、租户或金额。
     *
     * @param response 响应
     * @param forbiddenValues 不应出现在响应体中的值（租户代码等）
     */
    private void assertForbiddenWithoutCostData(ApiTestClient.ApiResponse response, String... forbiddenValues) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asString()).isEqualTo("RICCON-AUTH-403");
        assertThat(problem.has("items")).as(response.body()).isFalse();
        assertThat(problem.has("totals")).as(response.body()).isFalse();
        assertThat(response.body()).doesNotContain("0.003000", "0.006000", "0.009000");
        for (String value : forbiddenValues) {
            assertThat(response.body()).doesNotContain(value);
        }
    }

    /**
     * 其他租户与员工。
     *
     * @param tenant 租户代码
     * @param userId 员工 ID
     */
    private record TenantOther(String tenant, UUID userId) {}
}
