package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.auth.service.LoginRateLimiter;
import com.riccon.api.oidc.service.OidcProviderRateLimiter;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OIDC 登录/登出集成测试（IT-OIDC-001..007 骨架）。
 *
 * <p>使用本地 stub discovery/JWKS/token（{@link OidcStubProvider}），不访问网络；覆盖：
 * 授权码 + PKCE(S256) + nonce、state 一次性、失败会话清理、无 userinfo、无 token 落库、
 * 自动联合登出地址、providers 限流。绑定端点属于 M10-B2，测试直接准备数据库状态。
 */
class OidcLoginFlowTest extends ApiIntegrationTestSupport {

    /** JSON 序列化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本地 stub（realm a/c/d）。 */
    private static final OidcStubProvider STUB;

    /** 不可达端口，用于 idp_unavailable 与 IdP 不可达的联合登出。 */
    private static final int CLOSED_PORT;

    /** 租户代码（每个测试类唯一，避免与共享容器内其他测试冲突）。 */
    private static final String TENANT_A;

    private static final String TENANT_B;
    private static final String TENANT_C;
    private static final String TENANT_D;
    private static final String TENANT_LOCAL;

    /** 客户端 ID / 密钥（测试占位值，不是真实凭据）。 */
    private static final String CLIENT_A = "riccon-a";

    private static final String CLIENT_C = "riccon-c";
    private static final String CLIENT_D = "riccon-d";
    private static final String SECRET_A = "oidc-test-secret-a";
    private static final String SECRET_C = "oidc-test-secret-c";
    private static final String SECRET_D = "oidc-test-secret-d";

    /** 测试主体。 */
    private static final String SUCCESS_SUBJECT = "sub-success";

    private static final String EMAIL_SUBJECT = "sub-email";
    private static final String CSRF_SUBJECT = "sub-csrf";
    private static final String REPLAY_SUBJECT = "sub-replay";
    private static final String REVOKE_SUBJECT = "sub-revoke";
    private static final String LOGOUT_SUBJECT = "sub-logout";
    private static final String DISABLED_SUBJECT = "sub-disabled";

    static {
        try {
            STUB = new OidcStubProvider("a", "c", "d");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            CLOSED_PORT = socket.getLocalPort();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        TENANT_A = "oidca" + suffix;
        TENANT_B = "oidcb" + suffix;
        TENANT_C = "oidcc" + suffix;
        TENANT_D = "oidcd" + suffix;
        TENANT_LOCAL = "oidcl" + suffix;
    }

    /** 登录失败限流；每个测试清空本测试租户的计数。 */
    @Autowired
    private LoginRateLimiter loginRateLimiter;

    /** providers 限流；每个测试清空。 */
    @Autowired
    private OidcProviderRateLimiter providerRateLimiter;

    /** 固定数据是否已创建。 */
    private static boolean fixturesReady;

    /** 账号 ID。 */
    private static String successUserId;

    private static String emailUserId;
    private static String csrfUserId;
    private static String replayUserId;
    private static String revokeUserId;
    private static String logoutUserId;
    private static String disabledUserId;
    private static String localUsername;

    /**
     * 注入 OIDC 配置：A/C/D 使用本地 stub，B 使用不可达 issuer。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        String json = """
                [
                  {"tenantCode":"%s","issuer":"%s","clientId":"%s","clientSecret":"%s",
                   "providerName":"Acme 企业账号","endSessionEndpoint":"%s/logout","enabled":true},
                  {"tenantCode":"%s","issuer":"http://127.0.0.1:%d/realms/b","clientId":"riccon-b",
                   "clientSecret":"oidc-test-secret-b","enabled":true},
                  {"tenantCode":"%s","issuer":"%s","clientId":"%s","clientSecret":"%s","enabled":true},
                  {"tenantCode":"%s","issuer":"%s","clientId":"%s","clientSecret":"%s",
                   "endSessionEndpoint":"http://127.0.0.1:%d/logout","enabled":true}
                ]
                """.formatted(
                        TENANT_A,
                        STUB.issuer("a"),
                        CLIENT_A,
                        SECRET_A,
                        STUB.issuer("a"),
                        TENANT_B,
                        CLOSED_PORT,
                        TENANT_C,
                        STUB.issuer("c"),
                        CLIENT_C,
                        SECRET_C,
                        TENANT_D,
                        STUB.issuer("d"),
                        CLIENT_D,
                        SECRET_D,
                        CLOSED_PORT);
        registry.add("riccon.oidc.idps", () -> json);
        registry.add("riccon.oidc.allow-http", () -> true);
        registry.add("riccon.public-base-url", () -> "http://localhost:8000");
    }

    /**
     * 关闭 stub。
     */
    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    /**
     * 清理限流并准备固定数据。
     */
    @BeforeEach
    void prepareFixtures() {
        providerRateLimiter.resetAll();
        for (String tenant : List.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D, TENANT_LOCAL)) {
            loginRateLimiter.reset("127.0.0.1", tenant);
        }
        STUB.reset();
        if (!fixturesReady) {
            createFixtures();
            fixturesReady = true;
        }
    }

    /** IT-OIDC-001：授权码 + PKCE + nonce 成功登录；会话只含 RicconPrincipal；无 token/无 userinfo。 */
    @Test
    @DisplayName("IT-OIDC-001 PKCE+nonce 登录成功且不落 token、不调 userinfo")
    void itOidc001_successfulLoginStoresOnlyRicconPrincipal() {
        AuthorizeFlow flow = authorize(TENANT_A);
        assertThat(flow.params().get("code_challenge_method")).isEqualTo("S256");
        assertThat(flow.params().get("code_challenge")).isNotBlank();
        assertThat(flow.params().get("nonce")).isNotBlank();
        assertThat(flow.params().get("state")).isNotBlank();
        assertThat(flow.params().get("client_id")).isEqualTo(CLIENT_A);
        assertThat(flow.params().get("redirect_uri"))
                .isEqualTo("http://localhost:8000/api/v1/auth/oidc/callback/" + registrationId(TENANT_A));
        assertThat(flow.params().get("scope").split(" ")).contains("openid").doesNotContain("offline_access");
        assertThat(flow.params()).doesNotContainKey("id_token_hint");

        String code = registerCode(TENANT_A, SUCCESS_SUBJECT, flow);
        ApiTestClient.ApiResponse callback = callback(TENANT_A, flow.sessionCookie(), code, flow.state());

        assertThat(callback.status()).as(callback.body()).isEqualTo(302);
        assertThat(callback.header("Location")).isEqualTo(absolute("/"));
        String sessionCookie = client.sessionCookie(callback).orElseThrow();
        assertThat(sessionCookie).isNotEqualTo(flow.sessionCookie());
        String setCookie = callback.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("RICCON_SESSION="))
                .findFirst()
                .orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax");

        ApiTestClient.ApiResponse session = client.get("/api/v1/auth/session", sessionCookie, Map.of());
        assertThat(session.status()).isEqualTo(200);
        JsonNode body = client.json(session);
        assertThat(body.path("authenticated").asBoolean()).isTrue();
        assertThat(body.path("user").path("id").asText()).isEqualTo(successUserId);
        assertThat(body.path("csrfToken").asText()).isNotBlank();
        assertThat(session.body()).doesNotContain("accessToken", "refreshToken", "idToken");

        // 会话 ID 已轮换：旧 Cookie 不再可用。
        assertThat(client.get("/api/v1/auth/session", flow.sessionCookie(), Map.of())
                        .status())
                .isEqualTo(401);
        assertThat(STUB.userInfoCalls()).isZero();
        assertThat(STUB.tokenCalls()).isEqualTo(1);

        // 无 token 落库：扫描该主体会话属性字节与成功审计 details。
        String accessToken = STUB.lastAccessToken();
        String idToken = STUB.lastIdToken();
        assertThat(accessToken).isNotBlank();
        assertThat(idToken).isNotBlank();
        List<SessionAttribute> attributes = sessionAttributes(TENANT_A + ":" + successUserId);
        assertThat(attributes).isNotEmpty();
        assertThat(attributes).noneMatch(attribute -> attribute.name().contains("OAuth2AuthorizedClient"));
        String joined = attributes.stream()
                .map(attribute -> new String(attribute.bytes(), StandardCharsets.ISO_8859_1))
                .collect(Collectors.joining("\n"));
        assertThat(joined)
                .doesNotContain(accessToken)
                .doesNotContain(idToken)
                .doesNotContain("id_token")
                .doesNotContain("OidcUser")
                // state/nonce/PKCE verifier 只能在回调期间存在，成功后授权请求必须已被删除（ADR-0019 第 2 节）。
                .doesNotContain("code_verifier")
                .doesNotContain("authorization_request")
                .contains("com.riccon.api.identity.domain.RicconPrincipal");
        assertThat(attributes)
                .noneMatch(attribute ->
                        attribute.name().toLowerCase(java.util.Locale.ROOT).contains("authorization_request"));
        assertThat(sessionCount(TENANT_A + ":" + successUserId)).isEqualTo(1);

        Map<String, Object> details = successAuditDetails(TENANT_A, successUserId);
        assertThat(details).containsEntry("method", "oidc");
        assertThat(details.get("sessionIdHash").toString()).hasSize(64);
        assertThat(details.toString()).doesNotContain(accessToken).doesNotContain(idToken);
    }

    /** IT-OIDC-002：未绑定账号 → account_not_bound，并清理会话。 */
    @Test
    @DisplayName("IT-OIDC-002 未绑定账号失败并清理会话")
    void itOidc002_unboundAccount_cleansSession() {
        long baseline = totalSessionCount();
        AuthorizeFlow flow = authorize(TENANT_A);
        String code = registerCode(TENANT_A, "sub-unbound", flow);

        ApiTestClient.ApiResponse callback = callback(TENANT_A, flow.sessionCookie(), code, flow.state());

        assertFailure(callback, "account_not_bound");
        assertThat(client.get("/api/v1/auth/session", flow.sessionCookie(), Map.of())
                        .status())
                .isEqualTo(401);
        assertThat(totalSessionCount()).isEqualTo(baseline);
        assertAuditFailure(TENANT_A, "account_not_bound");
        assertThat(STUB.userInfoCalls()).isZero();
    }

    /** IT-OIDC-003：身份键 issuer + subject；不读取邮箱，不自动开通。 */
    @Test
    @DisplayName("IT-OIDC-003 按 issuer+subject 绑定，不使用邮箱")
    void itOidc003_identityKeyIgnoresEmail() {
        // 绑定账号的 DB 邮箱与 ID Token 邮箱不同；只要 subject 命中就登录成功。
        jdbcClient
                .sql("UPDATE app_user SET email = 'bound@example.test' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", TENANT_A)
                .param("id", BinaryUuids.toBytes(UUID.fromString(emailUserId)))
                .update();
        AuthorizeFlow boundFlow = authorize(TENANT_A);
        String boundCode = registerCode(TENANT_A, EMAIL_SUBJECT, boundFlow);
        ApiTestClient.ApiResponse boundCallback =
                callback(TENANT_A, boundFlow.sessionCookie(), boundCode, boundFlow.state());
        assertThat(boundCallback.header("Location")).isEqualTo(absolute("/"));
        String sessionCookie = client.sessionCookie(boundCallback).orElseThrow();
        JsonNode session = client.json(client.get("/api/v1/auth/session", sessionCookie, Map.of()));
        assertThat(session.path("user").path("id").asText()).isEqualTo(emailUserId);

        // 同一邮箱但未绑定的 subject：拒绝，不按邮箱自动关联。
        AuthorizeFlow unboundFlow = authorize(TENANT_A);
        String unboundCode = registerCode(TENANT_A, "sub-other-person", unboundFlow);
        ApiTestClient.ApiResponse unboundCallback =
                callback(TENANT_A, unboundFlow.sessionCookie(), unboundCode, unboundFlow.state());
        assertFailure(unboundCallback, "account_not_bound");
    }

    /** IT-OIDC-004：禁用账号拒绝建会话；已存在会话下一请求失效。 */
    @Test
    @DisplayName("IT-OIDC-004 禁用账号拒绝登录且既有会话失效")
    void itOidc004_disabledAccount_isRejectedAndSessionInvalidated() {
        AuthorizeFlow loginFlow = authorize(TENANT_A);
        String loginCode = registerCode(TENANT_A, REVOKE_SUBJECT, loginFlow);
        ApiTestClient.ApiResponse loginCallback =
                callback(TENANT_A, loginFlow.sessionCookie(), loginCode, loginFlow.state());
        assertThat(loginCallback.header("Location")).isEqualTo(absolute("/"));
        String sessionCookie = client.sessionCookie(loginCallback).orElseThrow();
        assertThat(client.get("/api/v1/auth/session", sessionCookie, Map.of()).status())
                .isEqualTo(200);

        jdbcClient
                .sql("UPDATE app_user SET status = 'DISABLED' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", TENANT_A)
                .param("id", BinaryUuids.toBytes(UUID.fromString(revokeUserId)))
                .update();
        try {
            assertThat(client.get("/api/v1/auth/session", sessionCookie, Map.of())
                            .status())
                    .isEqualTo(401);
        } finally {
            jdbcClient
                    .sql("UPDATE app_user SET status = 'ACTIVE' WHERE tenant_id = :tenant AND id = :id")
                    .param("tenant", TENANT_A)
                    .param("id", BinaryUuids.toBytes(UUID.fromString(revokeUserId)))
                    .update();
        }

        // 已禁用账号登录：account_disabled，且不会建立会话。
        AuthorizeFlow rejectedFlow = authorize(TENANT_A);
        String rejectedCode = registerCode(TENANT_A, DISABLED_SUBJECT, rejectedFlow);
        ApiTestClient.ApiResponse rejectedCallback =
                callback(TENANT_A, rejectedFlow.sessionCookie(), rejectedCode, rejectedFlow.state());
        assertFailure(rejectedCallback, "account_disabled");
        assertAuditFailure(TENANT_A, "account_disabled");
        assertThat(sessionCount(TENANT_A + ":" + disabledUserId)).isZero();
    }

    /** IT-OIDC-005：本地登出先失效；OIDC 账号返回联合登出地址；不调用 IdP；不影响并发会话。 */
    @Test
    @DisplayName("IT-OIDC-005 本地登出权威且返回 end-session 地址（IdP 不可达）")
    void itOidc005_logoutReturnsFederatedUrlAndKeepsOtherSessions() {
        String first = loginOidc(TENANT_D, LOGOUT_SUBJECT);
        String second = loginOidc(TENANT_D, LOGOUT_SUBJECT);
        String csrf = client.json(client.get("/api/v1/auth/session", first, Map.of()))
                .path("csrfToken")
                .asText();

        ApiTestClient.ApiResponse logout = client.post("/api/v1/auth/logout", null, first, csrf, Map.of());

        assertThat(logout.status()).isEqualTo(204);
        String federated = logout.header("X-Riccon-Federated-Logout-Url");
        assertThat(federated).isNotNull().startsWith("http://127.0.0.1:" + CLOSED_PORT + "/logout?");
        Map<String, String> params = queryParams(federated);
        assertThat(params.get("client_id")).isEqualTo(CLIENT_D);
        assertThat(params.get("post_logout_redirect_uri"))
                .isEqualTo("http://localhost:8000/login?loggedOut=1&federated=1");
        assertThat(params).doesNotContainKey("id_token_hint");
        // B1 不调用 IdP：本地登出是权威结果，IdP 不可达不影响 204。
        assertThat(STUB.logoutCalls()).isZero();
        assertThat(client.get("/api/v1/auth/session", first, Map.of()).status()).isEqualTo(401);
        assertThat(client.get("/api/v1/auth/session", second, Map.of()).status())
                .isEqualTo(200);
        assertThat(sessionCount(TENANT_D + ":" + logoutUserId)).isEqualTo(1);
        assertThat(auditRows("auth.logout")).isNotEmpty();

        // LOCAL 账号不返回联合登出地址。
        LoginSession local = login(TENANT_LOCAL, localUsername, "local-password-12");
        ApiTestClient.ApiResponse localLogout =
                client.post("/api/v1/auth/logout", null, local.sessionCookie(), local.csrfToken(), Map.of());
        assertThat(localLogout.status()).isEqualTo(204);
        assertThat(localLogout.header("X-Riccon-Federated-Logout-Url")).isNull();
    }

    /** IT-OIDC-006：各类失败原因码稳定，且无原始错误/token。 */
    @Test
    @DisplayName("IT-OIDC-006 error/state 重放/租户暂停/IdP 不可达原因码")
    void itOidc006_failureReasonsAreStableAndSessionsCleaned() {
        // access_denied：IdP 拒绝授权。
        AuthorizeFlow deniedFlow = authorize(TENANT_A);
        ApiTestClient.ApiResponse denied = client.get(
                "/api/v1/auth/oidc/callback/" + registrationId(TENANT_A) + "?error=access_denied&state="
                        + encode(deniedFlow.state()),
                deniedFlow.sessionCookie(),
                Map.of());
        assertFailure(denied, "access_denied");
        assertAuditFailure(TENANT_A, "access_denied");

        // bad state：state 不匹配。
        AuthorizeFlow badStateFlow = authorize(TENANT_A);
        String code = registerCode(TENANT_A, SUCCESS_SUBJECT, badStateFlow);
        ApiTestClient.ApiResponse badState = callback(TENANT_A, badStateFlow.sessionCookie(), code, "not-the-state");
        assertFailure(badState, "state_invalid");

        // 重放：成功回调后再次使用同一回调 URL。
        AuthorizeFlow replayFlow = authorize(TENANT_A);
        String replayCode = registerCode(TENANT_A, REPLAY_SUBJECT, replayFlow);
        ApiTestClient.ApiResponse replayCallback =
                callback(TENANT_A, replayFlow.sessionCookie(), replayCode, replayFlow.state());
        String replaySession = client.sessionCookie(replayCallback).orElseThrow();
        ApiTestClient.ApiResponse replayed = callback(TENANT_A, replaySession, replayCode, replayFlow.state());
        assertFailure(replayed, "state_invalid");
        assertThat(client.get("/api/v1/auth/session", replaySession, Map.of()).status())
                .isEqualTo(401);

        // 租户暂停。
        AuthorizeFlow suspendedFlow = authorize(TENANT_C);
        String suspendedCode = registerCode(TENANT_C, "sub-any", suspendedFlow);
        ApiTestClient.ApiResponse suspended =
                callback(TENANT_C, suspendedFlow.sessionCookie(), suspendedCode, suspendedFlow.state());
        assertFailure(suspended, "tenant_unavailable");
        assertAuditFailure(TENANT_C, "tenant_unavailable");

        // IdP 不可达。
        long baseline = totalSessionCount();
        ApiTestClient.ApiResponse unreachable = client.get("/api/v1/auth/oidc/authorize/" + registrationId(TENANT_B));
        assertThat(unreachable.status()).isEqualTo(302);
        assertThat(unreachable.header("Location")).isEqualTo(absolute("/login?oidcError=idp_unavailable"));
        assertThat(totalSessionCount()).isEqualTo(baseline);
        assertAuditFailure(TENANT_B, "idp_unavailable");

        // 回调限流：达到 IP + tenantCode 失败阈值后直接 rate_limited，不进入 token 交换。
        for (int index = 0; index < 10; index++) {
            loginRateLimiter.recordFailure("127.0.0.1", TENANT_A);
        }
        long rateLimitBaseline = totalSessionCount();
        ApiTestClient.ApiResponse rateLimited =
                client.get("/api/v1/auth/oidc/callback/" + registrationId(TENANT_A) + "?code=none&state=none");
        assertFailure(rateLimited, "rate_limited");
        assertThat(totalSessionCount()).isEqualTo(rateLimitBaseline);
        assertAuditFailure(TENANT_A, "rate_limited");
    }

    /** IT-OIDC-007：Cookie/CSRF 与内置账号一致；响应不含 JWT。 */
    @Test
    @DisplayName("IT-OIDC-007 会话 Cookie/CSRF 一致且无 JWT")
    void itOidc007_sessionUsesExistingCookieAndCsrf() {
        String sessionCookie = loginOidc(TENANT_A, CSRF_SUBJECT);
        JsonNode session = client.json(client.get("/api/v1/auth/session", sessionCookie, Map.of()));
        String csrf = session.path("csrfToken").asText();
        assertThat(csrf).isNotBlank();
        assertThat(session.toString()).doesNotContain("accessToken", "refreshToken", "idToken");

        // CSRF 生效：无令牌登出 403，有令牌 204。
        assertThat(client.post("/api/v1/auth/logout", null, sessionCookie, null, Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(client.post("/api/v1/auth/logout", null, sessionCookie, csrf, Map.of())
                        .status())
                .isEqualTo(204);
    }

    /** providers：未知/暂停租户统一 enabled=false；按 IP 60 次/分钟限流。 */
    @Test
    @DisplayName("IT-OIDC-PROVIDERS 可用性不泄露差异且 60/min 限流")
    void itOidcProviders_availabilityAndRateLimit() {
        ApiTestClient.ApiResponse enabled = client.get("/api/v1/auth/oidc/providers?tenantCode=" + TENANT_A);
        assertThat(enabled.status()).isEqualTo(200);
        JsonNode enabledBody = client.json(enabled);
        assertThat(enabledBody.path("enabled").asBoolean()).isTrue();
        assertThat(enabledBody.path("providerName").asText()).isEqualTo("Acme 企业账号");

        // 默认登录页必须关闭：/login 不得返回 HTML 登录页（只允许 JSON 401/403）。
        ApiTestClient.ApiResponse defaultLoginPage = client.get("/login");
        assertThat(defaultLoginPage.status()).isIn(401, 403);
        assertThat(defaultLoginPage.header("Content-Type")).contains("application/problem+json");

        ApiTestClient.ApiResponse unknown = client.get("/api/v1/auth/oidc/providers?tenantCode=unknown-tenant");
        ApiTestClient.ApiResponse suspended = client.get("/api/v1/auth/oidc/providers?tenantCode=" + TENANT_C);
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(suspended.status()).isEqualTo(200);
        assertThat(client.json(unknown).path("enabled").asBoolean()).isFalse();
        assertThat(client.json(suspended).path("enabled").asBoolean()).isFalse();
        assertThat(client.json(unknown).path("providerName").isNull()).isTrue();

        ApiTestClient.ApiResponse invalid = client.get("/api/v1/auth/oidc/providers?tenantCode=" + encode("-bad"));
        assertThat(invalid.status()).isEqualTo(400);

        providerRateLimiter.resetAll();
        for (int index = 0; index < 60; index++) {
            assertThat(client.get("/api/v1/auth/oidc/providers?tenantCode=" + TENANT_A)
                            .status())
                    .isEqualTo(200);
        }
        ApiTestClient.ApiResponse limited = client.get("/api/v1/auth/oidc/providers?tenantCode=" + TENANT_A);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(client.json(limited).path("code").asText()).isEqualTo("RICCON-RATE-429");
    }

    /**
     * 创建固定数据（租户、OIDC 用户、禁用用户、暂停租户、本地用户）。
     */
    private void createFixtures() {
        LoginSession admin = loginPlatformAdmin();
        createTenant(admin, TENANT_A);
        createTenant(admin, TENANT_B);
        createTenant(admin, TENANT_C);
        createTenant(admin, TENANT_D);
        createTenant(admin, TENANT_LOCAL);

        successUserId = createOidcUser(admin, TENANT_A, SUCCESS_SUBJECT, "ACTIVE");
        emailUserId = createOidcUser(admin, TENANT_A, EMAIL_SUBJECT, "ACTIVE");
        csrfUserId = createOidcUser(admin, TENANT_A, CSRF_SUBJECT, "ACTIVE");
        replayUserId = createOidcUser(admin, TENANT_A, REPLAY_SUBJECT, "ACTIVE");
        revokeUserId = createOidcUser(admin, TENANT_A, REVOKE_SUBJECT, "ACTIVE");
        logoutUserId = createOidcUser(admin, TENANT_D, LOGOUT_SUBJECT, "ACTIVE");
        disabledUserId = createOidcUser(admin, TENANT_A, DISABLED_SUBJECT, "DISABLED");

        localUsername = "local" + uniqueTenantCode("u");
        createUser(admin, TENANT_LOCAL, localUsername, "local-password-12", List.of("MEMBER"));

        jdbcClient
                .sql("UPDATE tenant SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", TENANT_C)
                .update();
    }

    /**
     * 创建并绑定 OIDC 用户。
     *
     * @param admin 平台管理员会话
     * @param tenantCode 租户
     * @param subject 绑定的 subject
     * @param status 账号状态
     * @return 账号 ID
     */
    private String createOidcUser(LoginSession admin, String tenantCode, String subject, String status) {
        String username = "oidc" + uniqueTenantCode("u");
        JsonNode created = createUser(admin, tenantCode, username, "local-password-12", List.of("MEMBER"));
        String userId = created.path("id").asText();
        String issuer = TENANT_D.equals(tenantCode) ? STUB.issuer("d") : STUB.issuer("a");
        jdbcClient
                .sql("""
                        UPDATE app_user
                        SET auth_source = 'OIDC', external_issuer = :issuer,
                            external_subject = :subject, status = :status
                        WHERE tenant_id = :tenantId AND id = :userId
                        """)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("status", status)
                .param("tenantId", tenantCode)
                .param("userId", BinaryUuids.toBytes(UUID.fromString(userId)))
                .update();
        jdbcClient
                .sql("DELETE FROM user_credential WHERE tenant_id = :tenantId AND user_id = :userId")
                .param("tenantId", tenantCode)
                .param("userId", BinaryUuids.toBytes(UUID.fromString(userId)))
                .update();
        return userId;
    }

    /**
     * 执行一次 OIDC 登录并返回会话 Cookie。
     *
     * @param tenantCode 租户
     * @param subject subject
     * @return 登录后的会话 Cookie
     */
    private String loginOidc(String tenantCode, String subject) {
        AuthorizeFlow flow = authorize(tenantCode);
        String code = registerCode(tenantCode, subject, flow);
        ApiTestClient.ApiResponse callback = callback(tenantCode, flow.sessionCookie(), code, flow.state());
        assertThat(callback.status()).as(callback.body()).isEqualTo(302);
        assertThat(callback.header("Location")).as(callback.body()).isEqualTo(absolute("/"));
        return client.sessionCookie(callback).orElseThrow();
    }

    /**
     * 发起授权并解析重定向参数。
     *
     * @param tenantCode 租户
     * @return 授权流程
     */
    private AuthorizeFlow authorize(String tenantCode) {
        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/oidc/authorize/" + registrationId(tenantCode));
        assertThat(response.status()).as(response.body()).isEqualTo(302);
        String location = response.header("Location");
        assertThat(location).isNotNull();
        String cookie = client.sessionCookie(response).orElseThrow();
        return new AuthorizeFlow(cookie, queryParams(location));
    }

    /**
     * 登记授权码。
     *
     * @param tenantCode 租户
     * @param subject subject
     * @param flow 授权流程
     * @return 授权码
     */
    private String registerCode(String tenantCode, String subject, AuthorizeFlow flow) {
        String code = "code-" + UUID.randomUUID();
        STUB.registerCode(
                realm(tenantCode),
                clientId(tenantCode),
                code,
                flow.params().get("code_challenge"),
                flow.params().get("nonce"),
                subject);
        return code;
    }

    /**
     * 调用回调。
     *
     * @param tenantCode 租户
     * @param sessionCookie 授权会话 Cookie
     * @param code 授权码
     * @param state state
     * @return 响应
     */
    private ApiTestClient.ApiResponse callback(String tenantCode, String sessionCookie, String code, String state) {
        String path = "/api/v1/auth/oidc/callback/" + registrationId(tenantCode) + "?code=" + encode(code) + "&state="
                + encode(state);
        return client.get(path, sessionCookie, Map.of());
    }

    /**
     * 构造测试服务器的绝对地址；Servlet 的 sendRedirect 会把相对 Location 转为绝对地址。
     *
     * @param path 路径
     * @return 绝对地址
     */
    private String absolute(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /**
     * 断言失败重定向。
     *
     * @param response 响应
     * @param reason 原因码
     */
    private void assertFailure(ApiTestClient.ApiResponse response, String reason) {
        assertThat(response.status()).as(response.body()).isEqualTo(302);
        assertThat(response.header("Location")).isEqualTo(absolute("/login?oidcError=" + reason));
        String body = response.body() == null ? "" : response.body();
        assertThat(body).doesNotContain("token", "code=", "state=");
    }

    /**
     * 断言存在指定原因码的失败审计。
     *
     * @param tenantCode 租户
     * @param reason 原因码
     */
    private void assertAuditFailure(String tenantCode, String reason) {
        assertThat(auditRows("auth.login.failed"))
                .filteredOn(row -> tenantCode.equals(row.tenantId()))
                .anySatisfy(row -> assertThat(MAPPER.readTree(row.details())
                                .path("reasonCode")
                                .asText())
                        .isEqualTo(reason));
    }

    /**
     * 读取成功审计 details。
     *
     * @param tenantCode 租户
     * @param userId 账号 ID
     * @return details
     */
    private Map<String, Object> successAuditDetails(String tenantCode, String userId) {
        AuditRow row = auditRows("auth.login.succeeded").stream()
                .filter(candidate -> tenantCode.equals(candidate.tenantId()) && userId.equals(candidate.actorId()))
                .findFirst()
                .orElseThrow();
        return MAPPER.readValue(row.details(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /**
     * 按主体读取会话属性（避免依赖 Cookie 编码）。
     *
     * @param principalName {@code tenant:userId}
     * @return 属性
     */
    private List<SessionAttribute> sessionAttributes(String principalName) {
        return jdbcClient
                .sql("""
                        SELECT sa.ATTRIBUTE_NAME, sa.ATTRIBUTE_BYTES
                        FROM SPRING_SESSION s
                        JOIN SPRING_SESSION_ATTRIBUTES sa ON sa.SESSION_PRIMARY_ID = s.PRIMARY_ID
                        WHERE s.PRINCIPAL_NAME = :principalName
                        """)
                .param("principalName", principalName)
                .query((rs, rowNum) -> new SessionAttribute(rs.getString(1), rs.getBytes(2)))
                .list();
    }

    /**
     * 统计所有会话行；用于断言失败会话已删除。
     *
     * @return 会话数
     */
    private long totalSessionCount() {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM SPRING_SESSION")
                .query(Long.class)
                .single();
    }

    /**
     * 解析 URL 查询参数。
     *
     * @param url URL
     * @return 参数
     */
    private static Map<String, String> queryParams(String url) {
        URI uri = URI.create(url);
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return params;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                params.put(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    /**
     * URL 编码查询值。
     *
     * @param value 值
     * @return 编码值
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 注册 ID。
     *
     * @param tenantCode 租户
     * @return {@code tenant-{code}}
     */
    private static String registrationId(String tenantCode) {
        return "tenant-" + tenantCode;
    }

    /**
     * realm 名。
     *
     * @param tenantCode 租户
     * @return realm
     */
    private static String realm(String tenantCode) {
        if (TENANT_C.equals(tenantCode)) {
            return "c";
        }
        if (TENANT_D.equals(tenantCode)) {
            return "d";
        }
        return "a";
    }

    /**
     * 客户端 ID。
     *
     * @param tenantCode 租户
     * @return client_id
     */
    private static String clientId(String tenantCode) {
        if (TENANT_C.equals(tenantCode)) {
            return CLIENT_C;
        }
        if (TENANT_D.equals(tenantCode)) {
            return CLIENT_D;
        }
        return CLIENT_A;
    }

    /**
     * 授权流程结果。
     *
     * @param sessionCookie 授权会话 Cookie
     * @param params 重定向查询参数
     */
    private record AuthorizeFlow(String sessionCookie, Map<String, String> params) {

        /**
         * state。
         *
         * @return state
         */
        String state() {
            return params.get("state");
        }
    }

    /**
     * 会话属性行。
     *
     * @param name 属性名
     * @param bytes 序列化字节
     */
    private record SessionAttribute(String name, byte[] bytes) {}
}
