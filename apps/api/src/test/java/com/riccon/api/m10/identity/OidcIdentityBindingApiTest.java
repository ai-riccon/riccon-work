package com.riccon.api.m10.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.auth.service.LoginRateLimiter;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M10-B2 账号开通/绑定集成测试。
 *
 * <p>使用真实 MySQL（Testcontainers）与真实迁移；OIDC 配置只提供 issuer 元数据，不触发发现或登录请求。
 * 覆盖：LOCAL/OIDC 创建分支、platform 租户拒绝、显式绑定/幂等/冲突/跨租户、凭据删除与重建、authSource
 * 展示、权限边界与审计 details（只存哈希）。
 */
class OidcIdentityBindingApiTest extends ApiIntegrationTestSupport {

    /** JSON 序列化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 租户 A：配置 issuer A。 */
    private static final String TENANT_A;

    /** 租户 B：配置 issuer B。 */
    private static final String TENANT_B;

    /** 租户 A 的规范化 issuer。 */
    private static final String ISSUER_A = "https://idp.b2.example/realms/a";

    /** 租户 B 的规范化 issuer。 */
    private static final String ISSUER_B = "https://idp.b2.example/realms/b";

    /** 不属于任何租户的 issuer，用于 mismatch 断言。 */
    private static final String ISSUER_OTHER = "https://idp.b2.example/realms/other";

    /** 合法初始密码（测试占位值）。 */
    private static final String PASSWORD = "initial-password-12";

    /** 解绑时设置的合法新密码（测试占位值）。 */
    private static final String NEW_PASSWORD = "restored-password-12";

    static {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        TENANT_A = "b2a" + suffix;
        TENANT_B = "b2b" + suffix;
    }

    /** 登录失败限流；每个测试清空本测试租户计数，避免历史失败累积成 429。 */
    @Autowired
    private LoginRateLimiter loginRateLimiter;

    /**
     * 注入 OIDC 配置：两个测试租户各一个 issuer，不访问网络。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        String json = """
                [
                  {"tenantCode":"%s","issuer":"%s","clientId":"riccon-b2-a",
                   "clientSecret":"b2-test-secret-a","providerName":"B2 IdP A","enabled":true},
                  {"tenantCode":"%s","issuer":"%s","clientId":"riccon-b2-b",
                   "clientSecret":"b2-test-secret-b","providerName":"B2 IdP B","enabled":true}
                ]
                """.formatted(TENANT_A, ISSUER_A, TENANT_B, ISSUER_B);
        registry.add("riccon.oidc.idps", () -> json);
        registry.add("riccon.public-base-url", () -> "http://localhost:8000");
    }

    /** 清空登录失败计数。 */
    @BeforeEach
    void resetRateLimiter() {
        for (String tenant : List.of(TENANT_A, TENANT_B, "platform")) {
            loginRateLimiter.reset("127.0.0.1", tenant);
        }
    }

    /** 1. 平台管理员直接开通 OIDC 账号：无本地凭据，审计只写哈希。 */
    @Test
    @DisplayName("B2-01 平台管理员开通 OIDC 账号不写凭据且审计只含哈希")
    void createOidcUser_platformAdmin_writesIdentityWithoutCredential() {
        LoginSession platform = ensureTenants();
        String username = unique("oidc");
        String subject = "sub-create-" + UUID.randomUUID();
        UUID userId = createOidcUser(platform, TENANT_A, username, ISSUER_A, subject, List.of("MEMBER"), true);

        IdentityRow identity = identity(TENANT_A, userId);
        assertThat(identity.authSource()).isEqualTo("OIDC");
        assertThat(identity.issuer()).isEqualTo(ISSUER_A);
        assertThat(identity.subject()).isEqualTo(subject);
        assertThat(credentialCount(TENANT_A, userId)).isZero();
        assertThat(loginStatus(TENANT_A, username, PASSWORD)).isEqualTo(401);

        List<AuditRecord> bound = audits("user.oidc.bound", userId);
        assertThat(bound).hasSize(1);
        JsonNode details = MAPPER.readTree(bound.get(0).details());
        assertThat(details.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(details.path("issuerHash").asText()).isEqualTo(sha256(ISSUER_A));
        assertThat(details.path("subjectHash").asText()).isEqualTo(sha256(subject));
        assertThat(details.has("previousAuthSource")).isFalse();
        assertThat(bound.get(0).details()).doesNotContain(subject).doesNotContain(ISSUER_A);
        // platform 管理员的审计租户仍为 platform；目标租户只出现在 user.created 的 targetTenantId。
        assertThat(bound.get(0).tenantId()).isEqualTo("platform");
    }

    /** 2. OIDC 创建分支的非法组合与 platform 租户拒绝。 */
    @Test
    @DisplayName("B2-02 OIDC 创建拒绝密码、缺失字段、issuer mismatch 与 platform 租户")
    void createOidcUser_rejectsInvalidRequestAndPlatformTenant() {
        LoginSession platform = ensureTenants();
        String username = unique("bad");

        assertThat(createRaw(
                                platform,
                                "/api/v1/admin/tenants/" + TENANT_A + "/users",
                                createBody(username + "p", "OIDC", PASSWORD, ISSUER_A, "sub-x", List.of("MEMBER")))
                        .status())
                .isEqualTo(400);
        assertCode(
                createRaw(
                        platform,
                        "/api/v1/admin/tenants/" + TENANT_A + "/users",
                        createBody(username + "i", "OIDC", null, null, "sub-x", List.of("MEMBER"))),
                "RICCON-OIDC-400");
        assertCode(
                createRaw(
                        platform,
                        "/api/v1/admin/tenants/" + TENANT_A + "/users",
                        createBody(username + "s", "OIDC", null, ISSUER_A, null, List.of("MEMBER"))),
                "RICCON-OIDC-400");
        assertCode(
                createRaw(
                        platform,
                        "/api/v1/admin/tenants/" + TENANT_A + "/users",
                        createBody(username + "m", "OIDC", null, ISSUER_B, "sub-x", List.of("MEMBER"))),
                "RICCON-OIDC-400");

        // ADR-0019 §10 / B1 评审 F-002：平台保留租户不允许 OIDC 账号。
        assertCode(
                createRaw(
                        platform,
                        "/api/v1/admin/tenants/platform/users",
                        createBody(username + "f", "OIDC", null, ISSUER_A, "sub-x", List.of("MEMBER"))),
                "RICCON-OIDC-400");
        UUID platformUserId =
                UUID.fromString(platform.body().path("user").path("id").asText());
        assertCode(
                bindRaw(
                        platform,
                        "/api/v1/admin/tenants/platform/users/" + platformUserId + "/oidc-binding",
                        ISSUER_A,
                        "sub-platform"),
                "RICCON-OIDC-400");
    }

    /** 3. LOCAL 分支保持 M02 行为：必须密码、写入凭据且可登录。 */
    @Test
    @DisplayName("B2-03 LOCAL 分支缺密码 400，带密码创建后凭据可用")
    void createLocalUser_requiresPasswordAndStoresCredential() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        String username = unique("local");

        ApiResponse missingPassword = client.post(
                "/api/v1/users",
                json(createBody(username, null, null, null, null, List.of("MEMBER"))),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(missingPassword.status()).as(missingPassword.body()).isEqualTo(400);
        assertCode(missingPassword, "RICCON-REQUEST-400");

        JsonNode created = createLocalUserByTenantAdmin(admin, username);
        UUID userId = UUID.fromString(created.path("id").asText());
        assertThat(created.path("authSource").asText()).isEqualTo("LOCAL");
        assertThat(credentialCount(TENANT_A, userId)).isEqualTo(1);
        assertThat(loginStatus(TENANT_A, username, PASSWORD)).isEqualTo(200);
    }

    /** 4. 租户管理员显式绑定：命中同一账号与角色，删除本地凭据。 */
    @Test
    @DisplayName("B2-04 租户管理员绑定删除本地凭据并保留角色")
    void bind_tenantAdmin_deletesCredentialAndKeepsRoles() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        String username = unique("bind");
        JsonNode created = createLocalUserByTenantAdmin(admin, username);
        UUID userId = UUID.fromString(created.path("id").asText());
        String subject = "sub-bind-" + UUID.randomUUID();

        ApiResponse response = bind(admin, "/api/v1/users/" + userId + "/oidc-binding", ISSUER_A, subject);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("id").asText()).isEqualTo(userId.toString());
        assertThat(body.path("authSource").asText()).isEqualTo("OIDC");
        assertThat(body.path("roles").toString()).isEqualTo("[\"MEMBER\"]");

        IdentityRow identity = identity(TENANT_A, userId);
        assertThat(identity.authSource()).isEqualTo("OIDC");
        assertThat(identity.issuer()).isEqualTo(ISSUER_A);
        assertThat(identity.subject()).isEqualTo(subject);
        assertThat(credentialCount(TENANT_A, userId)).isZero();
        assertThat(loginStatus(TENANT_A, username, PASSWORD)).isEqualTo(401);

        List<AuditRecord> rows = audits("user.oidc.bound", userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tenantId()).isEqualTo(TENANT_A);
        JsonNode details = MAPPER.readTree(rows.get(0).details());
        assertThat(details.path("previousAuthSource").asText()).isEqualTo("LOCAL");
        assertThat(details.path("subjectHash").asText()).isEqualTo(sha256(subject));
        assertThat(rows.get(0).details()).doesNotContain(subject).doesNotContain("password");
    }

    /** 5. 同一身份重复绑定幂等，不重复审计。 */
    @Test
    @DisplayName("B2-05 同一身份重复绑定幂等且不重复写审计")
    void bind_sameIdentity_isIdempotent() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        UUID userId = UUID.fromString(
                createLocalUserByTenantAdmin(admin, unique("idem")).path("id").asText());
        String subject = "sub-idem-" + UUID.randomUUID();
        String path = "/api/v1/users/" + userId + "/oidc-binding";

        assertThat(bind(admin, path, ISSUER_A, subject).status()).isEqualTo(200);
        ApiResponse second = bind(admin, path, ISSUER_A, subject);
        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(client.json(second).path("authSource").asText()).isEqualTo("OIDC");
        assertThat(audits("user.oidc.bound", userId)).hasSize(1);
        assertThat(credentialCount(TENANT_A, userId)).isZero();
    }

    /** 6. 绑定冲突、issuer mismatch、跨租户与不存在账号语义。 */
    @Test
    @DisplayName("B2-06 绑定冲突 409、跨租户 404、issuer mismatch 400")
    void bind_conflictsAndTenantBoundaries() {
        LoginSession platform = ensureTenants();
        LoginSession adminA = createAndLoginTenantAdmin(platform, TENANT_A);
        UUID first = UUID.fromString(
                createLocalUserByTenantAdmin(adminA, unique("one")).path("id").asText());
        UUID second = UUID.fromString(
                createLocalUserByTenantAdmin(adminA, unique("two")).path("id").asText());
        String subject = "sub-conflict-" + UUID.randomUUID();

        assertThat(bind(adminA, "/api/v1/users/" + first + "/oidc-binding", ISSUER_A, subject)
                        .status())
                .isEqualTo(200);

        // 同一身份已绑定其他账号。
        assertCode(bind(adminA, "/api/v1/users/" + second + "/oidc-binding", ISSUER_A, subject), "RICCON-OIDC-409");
        // 账号已绑定其他身份。
        assertCode(
                bind(adminA, "/api/v1/users/" + first + "/oidc-binding", ISSUER_A, "sub-other-" + UUID.randomUUID()),
                "RICCON-OIDC-409");
        // issuer 与租户配置不一致。
        assertCode(
                bind(adminA, "/api/v1/users/" + second + "/oidc-binding", ISSUER_B, "sub-mismatch"), "RICCON-OIDC-400");

        // 跨租户：A 的管理员看不到 B 的账号，与不存在统一 404。
        LoginSession adminB = createAndLoginTenantAdmin(platform, TENANT_B);
        UUID otherTenantUser = UUID.fromString(
                createLocalUserByTenantAdmin(adminB, unique("other")).path("id").asText());
        assertCode(
                bind(adminA, "/api/v1/users/" + otherTenantUser + "/oidc-binding", ISSUER_A, "sub-cross"),
                "RICCON-TENANT-404");
        assertCode(
                bind(adminA, "/api/v1/users/" + UUID.randomUUID() + "/oidc-binding", ISSUER_A, "sub-missing"),
                "RICCON-TENANT-404");
    }

    /** 7. 平台管理员跨租户绑定：目标租户生效、审计仍写平台租户。 */
    @Test
    @DisplayName("B2-07 平台管理员跨租户绑定目标租户账号")
    void bind_platformAdmin_usesTargetTenant() {
        LoginSession platform = ensureTenants();
        LoginSession adminB = createAndLoginTenantAdmin(platform, TENANT_B);
        UUID userId = UUID.fromString(
                createLocalUserByTenantAdmin(adminB, unique("plat")).path("id").asText());
        String subject = "sub-platform-bind-" + UUID.randomUUID();

        ApiResponse response = bind(
                platform,
                "/api/v1/admin/tenants/" + TENANT_B + "/users/" + userId + "/oidc-binding",
                ISSUER_B,
                subject);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("authSource").asText()).isEqualTo("OIDC");
        assertThat(identity(TENANT_B, userId).subject()).isEqualTo(subject);
        assertThat(credentialCount(TENANT_B, userId)).isZero();

        // 目标租户不存在统一 404，不泄露租户存在性以外的信息。
        assertCode(
                bind(
                        platform,
                        "/api/v1/admin/tenants/" + TENANT_B + "x/users/" + userId + "/oidc-binding",
                        ISSUER_B,
                        "sub-missing-tenant"),
                "RICCON-TENANT-404");

        List<AuditRecord> rows = audits("user.oidc.bound", userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tenantId()).isEqualTo("platform");
        assertThat(rows.get(0).actorId())
                .isEqualTo(platform.body().path("user").path("id").asText());
    }

    /** 8. 权限边界：普通员工不能列出账号或绑定，响应不泄露账号数据。 */
    @Test
    @DisplayName("B2-08 普通员工无账号管理权限且不泄露账号数据")
    void bind_requiresTenantUserManage() {
        LoginSession platform = ensureTenants();
        LoginSession member = createAndLoginMember(platform, TENANT_A);
        UUID target = UUID.fromString(createLocalUserByPlatform(platform, TENANT_A, unique("perm"))
                .path("id")
                .asText());

        ApiResponse list = client.get("/api/v1/users", member.sessionCookie(), Map.of());
        assertThat(list.status()).isEqualTo(403);
        assertCode(list, "RICCON-AUTH-403");
        assertThat(list.body()).doesNotContain("email").doesNotContain("authSource");

        ApiResponse bind = bind(member, "/api/v1/users/" + target + "/oidc-binding", ISSUER_A, "sub-perm");
        assertThat(bind.status()).isEqualTo(403);
        assertCode(bind, "RICCON-AUTH-403");
        assertThat(identity(TENANT_A, target).authSource()).isEqualTo("LOCAL");
    }

    /** 9. 解除绑定必须提供新密码，恢复后可用新密码登录。 */
    @Test
    @DisplayName("B2-09 解绑恢复 LOCAL 并可用新密码登录")
    void unbind_restoresLocalPassword() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        String username = unique("unbind");
        UUID userId = UUID.fromString(
                createLocalUserByTenantAdmin(admin, username).path("id").asText());
        String subject = "sub-unbind-" + UUID.randomUUID();
        String bindPath = "/api/v1/users/" + userId + "/oidc-binding";
        assertThat(bind(admin, bindPath, ISSUER_A, subject).status()).isEqualTo(200);

        // 短密码拒绝，且账号仍为 OIDC。
        assertCode(unbind(admin, bindPath + "/remove", "short"), "RICCON-OIDC-400");
        assertThat(identity(TENANT_A, userId).authSource()).isEqualTo("OIDC");

        ApiResponse response = unbind(admin, bindPath + "/remove", NEW_PASSWORD);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("authSource").asText()).isEqualTo("LOCAL");
        IdentityRow identity = identity(TENANT_A, userId);
        assertThat(identity.authSource()).isEqualTo("LOCAL");
        assertThat(identity.issuer()).isNull();
        assertThat(identity.subject()).isNull();
        assertThat(credentialCount(TENANT_A, userId)).isEqualTo(1);
        assertThat(loginStatus(TENANT_A, username, NEW_PASSWORD)).isEqualTo(200);

        List<AuditRecord> rows = audits("user.oidc.unbound", userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tenantId()).isEqualTo(TENANT_A);
        JsonNode details = MAPPER.readTree(rows.get(0).details());
        assertThat(details.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(details.path("issuerHash").asText()).isEqualTo(sha256(ISSUER_A));
        assertThat(details.size()).isEqualTo(2);
        assertThat(rows.get(0).details()).doesNotContain(subject).doesNotContain(ISSUER_A);
    }

    /** 10. 未绑定账号不能“无凭据解绑”；平台租户不可解绑。 */
    @Test
    @DisplayName("B2-10 未绑定账号解绑 409，平台租户拒绝")
    void unbind_rejectsUnboundAccountAndPlatformTenant() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        UUID userId = UUID.fromString(
                createLocalUserByTenantAdmin(admin, unique("nobind")).path("id").asText());

        assertCode(unbind(admin, "/api/v1/users/" + userId + "/oidc-binding/remove", NEW_PASSWORD), "RICCON-OIDC-409");

        UUID platformUserId =
                UUID.fromString(platform.body().path("user").path("id").asText());
        assertCode(
                unbind(
                        platform,
                        "/api/v1/admin/tenants/platform/users/" + platformUserId + "/oidc-binding/remove",
                        NEW_PASSWORD),
                "RICCON-OIDC-400");
    }

    /** 11. 禁用账号可被绑定但不会恢复登录；解绑后仍因禁用无法登录。 */
    @Test
    @DisplayName("B2-11 禁用账号绑定/解绑不改变禁用状态")
    void bind_disabledAccount_keepsLoginBlocked() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        String username = unique("disabled");
        UUID userId = UUID.fromString(
                createLocalUserByTenantAdmin(admin, username).path("id").asText());

        ApiResponse disable = client.patch(
                "/api/v1/users/" + userId,
                json(Map.of("status", "DISABLED")),
                admin.sessionCookie(),
                admin.csrfToken());
        assertThat(disable.status()).as(disable.body()).isEqualTo(200);

        String path = "/api/v1/users/" + userId + "/oidc-binding";
        ApiResponse bind = bind(admin, path, ISSUER_A, "sub-disabled-" + UUID.randomUUID());
        assertThat(bind.status()).as(bind.body()).isEqualTo(200);
        assertThat(client.json(bind).path("status").asText()).isEqualTo("DISABLED");
        assertThat(client.json(bind).path("authSource").asText()).isEqualTo("OIDC");
        assertThat(loginStatus(TENANT_A, username, PASSWORD)).isEqualTo(401);

        assertThat(unbind(admin, path + "/remove", NEW_PASSWORD).status()).isEqualTo(200);
        assertThat(identity(TENANT_A, userId).authSource()).isEqualTo("LOCAL");
        assertThat(loginStatus(TENANT_A, username, NEW_PASSWORD)).isEqualTo(401);
    }

    /** 12. 列表与会话摘要暴露 authSource，便于界面区分企业账号/内置账号。 */
    @Test
    @DisplayName("B2-12 账号列表与创建响应包含 authSource")
    void listUsers_exposesAuthSource() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        String username = unique("listed");
        UUID oidcUserId = createOidcUser(
                admin, TENANT_A, username, ISSUER_A, "sub-listed-" + UUID.randomUUID(), List.of("MEMBER"), false);

        ApiResponse list = client.get("/api/v1/users?size=100", admin.sessionCookie(), Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        JsonNode item = null;
        for (JsonNode node : client.json(list).path("items")) {
            if (oidcUserId.toString().equals(node.path("id").asText())) {
                item = node;
                break;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("authSource").asText()).isEqualTo("OIDC");
        assertThat(item.path("roles").toString()).isEqualTo("[\"MEMBER\"]");
    }

    /** 13. 登录响应与 /auth/session 返回账号真实来源，且不泄露 issuer/subject/token。 */
    @Test
    @DisplayName("B2-13 会话摘要返回 LOCAL/OIDC 且不泄露身份原文")
    void sessionInfo_exposesAuthSourceWithoutIdentitySecrets() {
        LoginSession platform = ensureTenants();
        LoginSession admin = createAndLoginTenantAdmin(platform, TENANT_A);
        String username = unique("session");
        JsonNode created = createLocalUserByTenantAdmin(admin, username);
        UUID userId = UUID.fromString(created.path("id").asText());
        String subject = "sub-session-" + UUID.randomUUID();

        // LOCAL：登录响应与会话读取都返回 LOCAL。
        LoginSession member = login(TENANT_A, username, PASSWORD);
        assertThat(member.body().path("user").path("authSource").asText()).isEqualTo("LOCAL");
        JsonNode localSession = client.json(client.get("/api/v1/auth/session", member.sessionCookie(), Map.of()));
        assertThat(localSession.path("user").path("authSource").asText()).isEqualTo("LOCAL");

        // OIDC：账号转为 OIDC 后，已认证会话在下一请求读取真实来源。
        assertThat(bind(admin, "/api/v1/users/" + userId + "/oidc-binding", ISSUER_A, subject)
                        .status())
                .isEqualTo(200);
        ApiResponse oidcResponse = client.get("/api/v1/auth/session", member.sessionCookie(), Map.of());
        assertThat(oidcResponse.status()).as(oidcResponse.body()).isEqualTo(200);
        JsonNode oidcSession = client.json(oidcResponse);
        assertThat(oidcSession.path("user").path("authSource").asText()).isEqualTo("OIDC");

        // 不泄露身份原文与任何 token；只输出来源枚举。
        assertThat(oidcSession.path("user").has("issuer")).isFalse();
        assertThat(oidcSession.path("user").has("subject")).isFalse();
        assertThat(oidcResponse.body())
                .doesNotContain(ISSUER_A)
                .doesNotContain(subject)
                .doesNotContain("accessToken", "refreshToken", "idToken");

        // 未登录访问会话端点返回 401。
        assertThat(client.get("/api/v1/auth/session").status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------------
    // 测试辅助
    // ---------------------------------------------------------------------

    /**
     * 执行一次登录并返回状态码；用于断言密码凭据存在/删除后的行为。
     *
     * @param tenantCode 租户代码
     * @param username 登录名
     * @param password 密码
     * @return HTTP 状态码
     */
    private int loginStatus(String tenantCode, String username, String password) {
        CsrfSession csrf = csrfSession();
        return client.post(
                        "/api/v1/auth/login",
                        client.jsonBody("tenantCode", tenantCode, "username", username, "password", password),
                        csrf.cookie(),
                        csrf.token(),
                        Map.of())
                .status();
    }

    /**
     * 登录平台管理员并确保两个测试租户存在。
     *
     * @return 平台管理员会话
     */
    private LoginSession ensureTenants() {
        LoginSession platform = loginPlatformAdmin();
        ensureTenant(platform, TENANT_A);
        ensureTenant(platform, TENANT_B);
        return platform;
    }

    /**
     * 幂等创建租户：已存在时接受 409，避免测试顺序耦合。
     *
     * @param platform 平台管理员会话
     * @param tenantCode 租户代码
     */
    private void ensureTenant(LoginSession platform, String tenantCode) {
        ApiResponse response = client.post(
                "/api/v1/admin/tenants",
                json(Map.of("id", tenantCode, "name", "Tenant " + tenantCode)),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isIn(201, 409);
    }

    /**
     * 以平台管理员创建 LOCAL 账号并断言 201。
     *
     * @param platform 平台管理员会话
     * @param tenantCode 目标租户
     * @param username 登录名
     * @return 账号响应
     */
    private JsonNode createLocalUserByPlatform(LoginSession platform, String tenantCode, String username) {
        ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenantCode + "/users",
                json(createBody(username, null, PASSWORD, null, null, List.of("MEMBER"))),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 以租户管理员创建 LOCAL 账号并断言 201。
     *
     * @param admin 租户管理员会话
     * @param username 登录名
     * @return 账号响应
     */
    private JsonNode createLocalUserByTenantAdmin(LoginSession admin, String username) {
        ApiResponse response = client.post(
                "/api/v1/users",
                json(createBody(username, null, PASSWORD, null, null, List.of("MEMBER"))),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(client.json(response).path("authSource").asText()).isEqualTo("LOCAL");
        return client.json(response);
    }

    /**
     * 创建 OIDC 账号并断言 201。
     *
     * @param actor 操作者会话
     * @param tenantCode 目标租户
     * @param username 登录名
     * @param issuer issuer
     * @param subject subject
     * @param roles 角色
     * @param platformRoute 是否走平台管理员路径
     * @return 账号 ID
     */
    private UUID createOidcUser(
            LoginSession actor,
            String tenantCode,
            String username,
            String issuer,
            String subject,
            List<String> roles,
            boolean platformRoute) {
        String path = platformRoute ? "/api/v1/admin/tenants/" + tenantCode + "/users" : "/api/v1/users";
        ApiResponse response = client.post(
                path,
                json(createBody(username, "OIDC", null, issuer, subject, roles)),
                actor.sessionCookie(),
                actor.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode body = client.json(response);
        assertThat(body.path("authSource").asText()).isEqualTo("OIDC");
        return UUID.fromString(body.path("id").asText());
    }

    /**
     * 创建账号请求体；空值字段不进入 JSON，避免把 {@code null} 当成显式密码。
     *
     * @param username 登录名
     * @param authSource 认证来源，可为空
     * @param password 密码，可为空
     * @param issuer issuer，可为空
     * @param subject subject，可为空
     * @param roles 角色
     * @return 请求体映射
     */
    private static Map<String, Object> createBody(
            String username, String authSource, String password, String issuer, String subject, List<String> roles) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "User " + username);
        if (authSource != null) {
            body.put("authSource", authSource);
        }
        if (password != null) {
            body.put("password", password);
        }
        if (issuer != null) {
            body.put("issuer", issuer);
        }
        if (subject != null) {
            body.put("subject", subject);
        }
        body.put("roles", roles);
        return body;
    }

    /**
     * 执行绑定并返回原始响应。
     *
     * @param actor 操作者会话
     * @param path 绑定路径
     * @param issuer issuer
     * @param subject subject
     * @return 响应
     */
    private ApiResponse bind(LoginSession actor, String path, String issuer, String subject) {
        return bindRaw(actor, path, issuer, subject);
    }

    /**
     * 执行绑定请求。
     *
     * @param actor 操作者会话
     * @param path 绑定路径
     * @param issuer issuer
     * @param subject subject
     * @return 响应
     */
    private ApiResponse bindRaw(LoginSession actor, String path, String issuer, String subject) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("subject", subject);
        return client.put(path, json(body), actor.sessionCookie(), actor.csrfToken());
    }

    /**
     * 执行解绑请求并返回原始响应。
     *
     * @param actor 操作者会话
     * @param path 解绑路径
     * @param newPassword 新密码
     * @return 响应
     */
    private ApiResponse unbind(LoginSession actor, String path, String newPassword) {
        return client.post(
                path, json(Map.of("newPassword", newPassword)), actor.sessionCookie(), actor.csrfToken(), Map.of());
    }

    /**
     * 读取账号认证来源与外部身份。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 身份行
     */
    private IdentityRow identity(String tenantId, UUID userId) {
        return jdbcClient
                .sql("""
                        SELECT auth_source, external_issuer, external_subject, status
                          FROM app_user
                         WHERE tenant_id = :tenantId AND id = :userId
                        """)
                .param("tenantId", tenantId)
                .param("userId", BinaryUuids.toBytes(userId))
                .query((rs, rowNum) -> new IdentityRow(
                        rs.getString("auth_source"),
                        rs.getString("external_issuer"),
                        rs.getString("external_subject"),
                        rs.getString("status")))
                .single();
    }

    /**
     * 统计账号的本地密码凭据数。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 凭据行数
     */
    private long credentialCount(String tenantId, UUID userId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM user_credential WHERE tenant_id = :tenantId AND user_id = :userId")
                .param("tenantId", tenantId)
                .param("userId", BinaryUuids.toBytes(userId))
                .query(Long.class)
                .single();
    }

    /**
     * 查询指定动作与目标账号的审计行。
     *
     * @param action 动作名
     * @param userId 目标账号 ID
     * @return 审计行
     */
    private List<AuditRecord> audits(String action, UUID userId) {
        return jdbcClient
                .sql("""
                        SELECT tenant_id, actor_id, details
                          FROM audit_event
                         WHERE action = :action AND target_id = :targetId
                         ORDER BY occurred_at ASC, event_id ASC
                        """)
                .param("action", action)
                .param("targetId", userId.toString())
                .query((rs, rowNum) ->
                        new AuditRecord(rs.getString("tenant_id"), rs.getString("actor_id"), rs.getString("details")))
                .list();
    }

    /**
     * 断言响应体中的稳定错误码。
     *
     * @param response 响应
     * @param code 期望错误码
     */
    private void assertCode(ApiResponse response, String code) {
        assertThat(client.json(response).path("code").asText())
                .as(response.body())
                .isEqualTo(code);
    }

    /**
     * 执行原始创建请求。
     *
     * @param actor 操作者会话
     * @param path 路径
     * @param body 请求体
     * @return 响应
     */
    private ApiResponse createRaw(LoginSession actor, String path, Map<String, Object> body) {
        return client.post(path, json(body), actor.sessionCookie(), actor.csrfToken(), Map.of());
    }

    /**
     * JSON 序列化。
     *
     * @param body 对象
     * @return JSON 文本
     */
    private static String json(Object body) {
        return MAPPER.writeValueAsString(body);
    }

    /**
     * 生成随机登录名。
     *
     * @param prefix 前缀
     * @return 登录名
     */
    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 独立计算 SHA-256，避免用生产工具验证自身。
     *
     * @param value 原文
     * @return 64 位 hex
     */
    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 账号身份行。
     *
     * @param authSource 认证来源
     * @param issuer issuer
     * @param subject subject
     * @param status 状态
     */
    private record IdentityRow(String authSource, String issuer, String subject, String status) {}

    /**
     * 审计行。
     *
     * @param tenantId 租户
     * @param actorId 操作者
     * @param details details JSON
     */
    private record AuditRecord(String tenantId, String actorId, String details) {}
}
