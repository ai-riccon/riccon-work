package com.riccon.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.MockWorkerServer;
import com.riccon.api.support.MockWorkerServer.Scenario;
import com.riccon.api.support.TestFiles;
import com.riccon.platform.common.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M03 跨租户回归独立套件（TEN-001..027）。
 *
 * <p>保留 M02 的 TEN-001..020 编号并补齐 M03 新增对象；021..027 覆盖部门、空间成员与平台部门端点。
 * 所有用例使用 {@code @Tag("tenant-isolation")}，零 skip/xfail；断言统一 404 的响应结构、无内容泄露、
 * 服务令牌绑定、审计租户与平台部门端点边界。
 */
@Tag("tenant-isolation")
class TenantIsolationSecurityTest extends SecurityTestSupport {

    /** mock Worker。 */
    private static final MockWorkerServer WORKER = new MockWorkerServer(SERVICE_SECRET);

    /** 模型供应商 ID；只用于本地 mock。 */
    private static final UUID PROVIDER_ID = UUID.fromString("01930000-0000-7000-8000-0000000000ac");

    /** HTTP 客户端。 */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 应用上下文，用于断言 M03 未启用共享缓存。 */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 注入 mock Worker 地址与密钥。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.worker.client.base-url", WORKER::url);
        registry.add("riccon.worker.client.service-token-secret", () -> SERVICE_SECRET);
    }

    /** 每个用例前重置 mock Worker。 */
    @BeforeEach
    void resetWorker() {
        WORKER.reset(Scenario.SUCCESS);
        WORKER.setChatHook(this::prepareModelInvocation);
    }

    /** 关闭 mock Worker。 */
    @AfterAll
    static void stopWorker() {
        WORKER.close();
    }

    /** TEN-001：账号列表只返回本租户。 */
    @Test
    @DisplayName("TEN-001 账号列表只返回本租户")
    void ten001_tenantUserListIsTenantScoped() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("tena");
        String tenantB = uniqueTenantCode("tenb");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession adminA = createAndLoginTenantAdmin(platform, tenantA);
        JsonNode memberA =
                createUser(platform, tenantA, "a-member" + randomSuffix(), MEMBER_PASSWORD, List.of("MEMBER"));
        createUser(platform, tenantB, "b-member" + randomSuffix(), MEMBER_PASSWORD, List.of("MEMBER"));

        JsonNode page = list(adminA, "/api/v1/users");
        assertThat(page.path("items").toString()).contains(memberA.path("id").asText());
        assertThat(page.path("items").toString()).doesNotContain("b-member");
        assertThat(page.path("total").asLong()).isEqualTo(2);
    }

    /** TEN-002：请求头、查询参数与请求体伪造租户标识均不能跨租户。 */
    @Test
    @DisplayName("TEN-002 伪造租户标识被拒绝或忽略")
    void ten002_forgedTenantIdentifiersAreRejectedOrIgnored() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("tena");
        String tenantB = uniqueTenantCode("tenb");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession adminA = createAndLoginTenantAdmin(platform, tenantA);
        LoginSession adminB = createAndLoginTenantAdmin(platform, tenantB);

        ApiTestClient.ApiResponse header =
                client.get("/api/v1/users", adminA.sessionCookie(), Map.of("X-Tenant-Id", tenantB));
        assertThat(header.status()).isEqualTo(403);
        assertThat(client.json(header).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        ApiTestClient.ApiResponse query = get(adminA, "/api/v1/users?tenantId=" + tenantB);
        assertThat(query.status()).isEqualTo(403);
        assertThat(client.json(query).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        UUID department = createDepartment(platform, tenantA, "伪造测试部" + randomSuffix());
        ApiTestClient.ApiResponse body = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "伪造租户空间", "departmentId", department.toString(), "tenantId", tenantB),
                adminA.sessionCookie(),
                adminA.csrfToken(),
                Map.of());
        assertThat(body.status()).as(body.body()).isEqualTo(201);
        String workspaceId = client.json(body).path("id").asText();
        assertThat(client.json(body).path("departmentId").asText()).isEqualTo(department.toString());
        assertThat(list(adminB, "/api/v1/workspaces").toString()).doesNotContain(workspaceId);

        assertThat(auditRows("tenant.access.denied"))
                .anySatisfy(row -> assertThat(row.details()).contains(tenantB));
    }

    /** TEN-003：读取他租户会话返回统一 404。 */
    @Test
    @DisplayName("TEN-003 跨租户会话读取 404")
    void ten003_crossTenantConversationReadIsNotFound() {
        CrossTenantFixture fixture = crossTenantFixture();
        ApiTestClient.ApiResponse response = get(fixture.aAdmin(), "/api/v1/conversations/" + fixture.bConversation());
        assertNotFound(response);
        assertNoLeak(response, "B 租户会话标题", fixture.bTenant());
    }

    /** TEN-004：修改/删除他租户会话返回统一 404，目标数据不变。 */
    @Test
    @DisplayName("TEN-004 跨租户会话修改/删除 404 且数据不变")
    void ten004_crossTenantConversationMutationsAreRejected() {
        CrossTenantFixture fixture = crossTenantFixture();

        ApiTestClient.ApiResponse patch = client.patch(
                "/api/v1/conversations/" + fixture.bConversation(),
                client.jsonBody("title", "被篡改的标题"),
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken());
        assertNotFound(patch);

        ApiTestClient.ApiResponse delete = client.delete(
                "/api/v1/conversations/" + fixture.bConversation(),
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken());
        assertNotFound(delete);

        String title = jdbcClient
                .sql("SELECT title FROM conversation WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.bTenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(fixture.bConversation())))
                .query(String.class)
                .single();
        assertThat(title).isEqualTo("B 租户会话标题");
    }

    /** TEN-005：读取他租户文件元数据返回统一 404。 */
    @Test
    @DisplayName("TEN-005 跨租户文件元数据 404")
    void ten005_crossTenantFileMetadataIsNotFound() {
        CrossTenantFixture fixture = crossTenantFixture();
        ApiTestClient.ApiResponse response = get(fixture.aAdmin(), "/api/v1/files/" + fixture.bFile());
        assertNotFound(response);
        assertNoLeak(response, "b-tenant.pdf", fixture.bTenant());
    }

    /** TEN-006：跨租户下载被拒；A 的签名 URL 被篡改到 B 前缀时对象存储拒绝。 */
    @Test
    @DisplayName("TEN-006 跨租户下载与签名 URL 篡改被拒")
    void ten006_crossTenantDownloadAndSignatureTamperingAreRejected() throws Exception {
        CrossTenantFixture fixture = crossTenantFixture();
        String aFile = upload(
                        fixture.aAdmin(), fixture.aPersonal(), "a-tenant.pdf", "application/pdf", TestFiles.pdf(), null)
                .path("id")
                .asText();

        ApiTestClient.ApiResponse bDownload =
                get(fixture.aAdmin(), "/api/v1/files/" + fixture.bFile() + "/download-url");
        assertNotFound(bDownload);
        assertThat(bDownload.body()).doesNotContain("X-Amz").doesNotContain("tenants/");

        ApiTestClient.ApiResponse aDownload = get(fixture.aAdmin(), "/api/v1/files/" + aFile + "/download-url");
        assertThat(aDownload.status()).as(aDownload.body()).isEqualTo(200);
        JsonNode body = client.json(aDownload);
        String url = body.path("url").asText();
        assertThat(url).contains(aFile).contains(fixture.aTenant());

        String tampered = url.replace(fixture.aTenant(), fixture.bTenant()).replace(aFile, fixture.bFile());
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(tampered)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isIn(400, 403);
        assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("%PDF");
    }

    /** TEN-007：读取他租户产物返回统一 404。 */
    @Test
    @DisplayName("TEN-007 跨租户产物读取 404")
    void ten007_crossTenantArtifactReadIsNotFound() {
        CrossTenantFixture fixture = crossTenantFixture();
        ApiTestClient.ApiResponse response = get(fixture.aAdmin(), "/api/v1/artifacts/" + fixture.bArtifact());
        assertNotFound(response);
        assertNoLeak(response, "B 租户产物", fixture.bTenant());
    }

    /** TEN-008：产物列表只返回本租户/本空间。 */
    @Test
    @DisplayName("TEN-008 产物列表租户隔离")
    void ten008_crossTenantArtifactListIsEmpty() {
        CrossTenantFixture fixture = crossTenantFixture();
        ApiTestClient.ApiResponse response =
                get(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.bWorkspace() + "/artifacts");
        assertNotFound(response);

        JsonNode own = list(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.aPersonal() + "/artifacts");
        assertThat(own.path("items").toString())
                .doesNotContain(fixture.bArtifact().toString());
        assertThat(own.path("total").asLong()).isZero();
    }

    /** TEN-009：客户端不能指定其他租户索引；知识库列表按租户隔离。 */
    @Test
    @DisplayName("TEN-009 检索索引不可由客户端指定")
    void ten009_clientCannotSelectForeignIndex() {
        CrossTenantFixture fixture = crossTenantFixture();
        String docId = createKnowledgeDocument(fixture.bAdmin(), fixture.bPersonal(), "B 租户知识文档");

        ApiTestClient.ApiResponse search =
                get(fixture.aAdmin(), "/api/v1/knowledge/search?index=riccon-kb-v1-" + fixture.bTenant());
        assertThat(search.status()).isIn(403, 404);
        assertThat(search.body()).doesNotContain(docId);

        JsonNode aKnowledge = list(fixture.aAdmin(), "/api/v1/knowledge/documents");
        assertThat(aKnowledge.path("items").toString()).doesNotContain(docId);
    }

    /** TEN-010：读取他租户知识库文档返回统一 404。 */
    @Test
    @DisplayName("TEN-010 跨租户知识库文档 404")
    void ten010_crossTenantKnowledgeDocumentIsNotFound() {
        CrossTenantFixture fixture = crossTenantFixture();
        String docId = createKnowledgeDocument(fixture.bAdmin(), fixture.bPersonal(), "B 租户知识文档");
        ApiTestClient.ApiResponse response = get(fixture.aAdmin(), "/api/v1/knowledge/documents/" + docId);
        assertNotFound(response);
        assertNoLeak(response, "B 租户知识文档", fixture.bTenant());
    }

    /** TEN-011：租户管理员不能访问平台接口；平台管理员可调用并写审计。 */
    @Test
    @DisplayName("TEN-011 平台接口角色边界")
    void ten011_platformApiRequiresPlatformAdmin() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("tena");
        createTenant(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);

        ApiTestClient.ApiResponse forbidden = get(tenantAdmin, "/api/v1/admin/tenants");
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(client.json(forbidden).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        assertThat(get(platform, "/api/v1/admin/tenants").status()).isEqualTo(200);
        assertThat(auditRows("tenant.created")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.targetId()).isEqualTo(tenant);
        });
    }

    /** TEN-012：普通员工访问审计接口被拒，响应不含审计内容。 */
    @Test
    @DisplayName("TEN-012 普通员工不能读取审计")
    void ten012_memberCannotReadAudit() {
        CrossTenantFixture fixture = crossTenantFixture();
        ApiTestClient.ApiResponse member = get(fixture.bMember().session(), "/api/v1/audit/events");
        assertThat(member.status()).isIn(403, 404);
        assertThat(member.body()).doesNotContain("eventId").doesNotContain("actorId");

        ApiTestClient.ApiResponse platform = get(fixture.aPlatform(), "/api/v1/audit/events");
        assertThat(platform.status()).isIn(403, 404);
        assertThat(platform.body()).doesNotContain("eventId").doesNotContain("actorId");
    }

    /** TEN-013：篡改对象 key 前缀被拒绝。 */
    @Test
    @DisplayName("TEN-013 对象 key 前缀篡改被拒")
    void ten013_objectKeyPrefixTamperingIsRejected() throws Exception {
        CrossTenantFixture fixture = crossTenantFixture();
        String aFile = upload(
                        fixture.aAdmin(), fixture.aPersonal(), "prefix.pdf", "application/pdf", TestFiles.pdf(), null)
                .path("id")
                .asText();
        ApiTestClient.ApiResponse download = get(fixture.aAdmin(), "/api/v1/files/" + aFile + "/download-url");
        assertThat(download.status()).isEqualTo(200);
        String url = client.json(download).path("url").asText();
        String tampered = url.replace("tenants/" + fixture.aTenant() + "/", "tenants/" + fixture.bTenant() + "/");
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(tampered)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isIn(400, 403);

        ApiTestClient.ApiResponse crossCreate = client.post(
                "/api/v1/workspaces/" + fixture.bWorkspace() + "/conversations",
                "{}",
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken(),
                Map.of());
        assertNotFound(crossCreate);
    }

    /** TEN-014：服务令牌的租户与运行绑定。 */
    @Test
    @DisplayName("TEN-014 服务令牌绑定租户与运行")
    void ten014_serviceTokenIsBoundToRunTenant() {
        CrossTenantFixture fixture = crossTenantFixture();
        ensureModelCatalog();

        String aConversation = createConversation(fixture.aAdmin(), fixture.aPersonal());
        ApiTestClient.ApiResponse aSend = client.post(
                "/api/v1/conversations/" + aConversation + "/messages",
                client.jsonBody("content", "自我介绍", "clientMessageId", "ten014-a-" + UUID.randomUUID()),
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(aSend.status()).as(aSend.body()).isEqualTo(200);

        JsonNode claimsA = WORKER.lastTokenClaims();
        assertThat(claimsA).isNotNull();
        assertThat(claimsA.path("tenantId").asText()).isEqualTo(fixture.aTenant());
        String aRunId = claimsA.path("runId").asText();
        assertThat(agentRunTenant(aRunId)).isEqualTo(fixture.aTenant());

        String bConversation = createConversation(fixture.bMember().session(), fixture.bMemberPersonal());
        ApiTestClient.ApiResponse bSend = client.post(
                "/api/v1/conversations/" + bConversation + "/messages",
                client.jsonBody("content", "自我介绍", "clientMessageId", "ten014-b-" + UUID.randomUUID()),
                fixture.bMember().session().sessionCookie(),
                fixture.bMember().session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(bSend.status()).as(bSend.body()).isEqualTo(200);

        JsonNode claimsB = WORKER.lastTokenClaims();
        assertThat(claimsB).isNotNull();
        assertThat(claimsB.path("tenantId").asText()).isEqualTo(fixture.bTenant());
        assertThat(claimsB.path("runId").asText()).isNotEqualTo(aRunId);
        assertThat(agentRunTenant(claimsB.path("runId").asText())).isEqualTo(fixture.bTenant());
        assertThat(claimsB.path("exp").asLong() - claimsB.path("iat").asLong()).isBetween(1L, 300L);
        assertThat(claimsB.path("scope").asText()).isEqualTo("agent:run");
    }

    /** TEN-015：未携带租户的线程快速失败，且 HTTP 请求不把租户泄漏到测试线程。 */
    @Test
    @DisplayName("TEN-015 缺失租户快速失败且不跨线程泄漏")
    void ten015_missingTenantFailsFast() {
        CrossTenantFixture fixture = crossTenantFixture();
        assertThat(TenantContext.get()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);

        assertThat(get(fixture.aAdmin(), "/api/v1/workspaces").status()).isEqualTo(200);
        assertThat(TenantContext.get()).isEmpty();
    }

    /** TEN-016：M03 未启用跨请求共享缓存；重复跨租户读取仍 404。 */
    @Test
    @DisplayName("TEN-016 缓存键租户前缀守卫")
    void ten016_noSharedCacheIsEnabled() {
        CrossTenantFixture fixture = crossTenantFixture();
        assertThat(applicationContext.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                .isEmpty();

        assertNotFound(get(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.bWorkspace()));
        assertNotFound(get(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.bWorkspace()));
        assertNotFound(get(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.bWorkspace()));
    }

    /** TEN-017：服务令牌跨 run/跨租户使用被 Worker 拒绝。 */
    @Test
    @DisplayName("TEN-017 服务令牌跨 run 与跨租户使用被拒")
    void ten017_serviceTokenReplayAcrossRunsIsRejected() throws Exception {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID userId = fixture.aAdminId();
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        String validToken =
                signServiceToken(fixture.aTenant(), userId, runA, Instant.now().plusSeconds(300));

        int valid = callWorker(validToken, fixture.aTenant(), runA, userId);
        assertThat(valid).isIn(200, 400, 500);

        int crossRun = callWorker(validToken, fixture.aTenant(), runB, userId);
        assertThat(crossRun).isEqualTo(401);

        int crossTenant = callWorker(validToken, fixture.bTenant(), runA, userId);
        assertThat(crossTenant).isIn(400, 401);

        String expired =
                signServiceToken(fixture.aTenant(), userId, runA, Instant.now().minusSeconds(60));
        assertThat(callWorker(expired, fixture.aTenant(), runA, userId)).isEqualTo(401);

        String wrongSecret = invalidToken(fixture.aTenant(), userId, runA);
        assertThat(callWorker(wrongSecret, fixture.aTenant(), runA, userId)).isEqualTo(401);
    }

    /** TEN-018：跨租户 ID 枚举的状态、错误码与响应字段一致。 */
    @Test
    @DisplayName("TEN-018 跨租户 ID 枚举无响应差异")
    void ten018_enumerationResponsesAreIndistinguishable() {
        CrossTenantFixture fixture = crossTenantFixture();
        String missing = UUID.randomUUID().toString();

        ApiTestClient.ApiResponse missingResponse = get(fixture.aAdmin(), "/api/v1/conversations/" + missing);
        ApiTestClient.ApiResponse foreignResponse =
                get(fixture.aAdmin(), "/api/v1/conversations/" + fixture.bConversation());
        assertThat(missingResponse.status()).isEqualTo(foreignResponse.status()).isEqualTo(404);
        assertThat(problemFields(missingResponse)).isEqualTo(problemFields(foreignResponse));

        Duration missingAverage = averageDuration(() -> get(fixture.aAdmin(), "/api/v1/conversations/" + missing));
        Duration foreignAverage =
                averageDuration(() -> get(fixture.aAdmin(), "/api/v1/conversations/" + fixture.bConversation()));
        assertThat(foreignAverage.toMillis())
                .as("跨租户响应时间不得显著高于普通不存在")
                .isLessThanOrEqualTo(Math.max(300, missingAverage.toMillis() * 10));
    }

    /** TEN-019：审计事件 tenant_id 与 actor 所属租户一致；平台事件为 platform。 */
    @Test
    @DisplayName("TEN-019 审计租户与 actor 一致")
    void ten019_auditTenantMatchesActor() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("tena");
        createTenant(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);
        String username = "audit" + randomSuffix();
        createUser(platform, tenant, username, MEMBER_PASSWORD, List.of("MEMBER"));

        assertThat(auditRows("user.created")).anySatisfy(row -> {
            if (tenant.equals(row.tenantId())) {
                assertThat(row.actorType()).isEqualTo("USER");
                assertThat(row.details()).doesNotContain("@").doesNotContain("password");
            }
        });
        assertThat(auditRows("tenant.created")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.targetId()).isEqualTo(tenant);
        });
        assertThat(tenantAdmin.body().path("user").path("username").asText()).isNotBlank();
    }

    /** TEN-020：M03 无审计导出接口，任何导出路径不返回数据。 */
    @Test
    @DisplayName("TEN-020 导出能力缺失时一律拒绝")
    void ten020_exportEndpointsAreAbsent() {
        CrossTenantFixture fixture = crossTenantFixture();
        for (String path :
                List.of("/api/v1/audit/exports", "/api/v1/audit/events/export", "/api/v1/audit/export-requests")) {
            ApiTestClient.ApiResponse response = get(fixture.aAdmin(), path);
            assertThat(response.status()).as(path).isIn(403, 404);
            assertThat(response.body()).doesNotContain("eventId").doesNotContain("rows");
        }
    }

    /** TEN-021：读取他租户部门、成员与空间统一 404。 */
    @Test
    @DisplayName("TEN-021 跨租户部门与空间读取 404")
    void ten021_crossTenantDepartmentReadsAreRejected() {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID bDepartment = createDepartment(fixture.aPlatform(), fixture.bTenant(), "B 秘密部门");

        assertNotFound(get(fixture.aAdmin(), "/api/v1/departments/" + bDepartment + "/members"));
        assertNotFound(get(fixture.aAdmin(), "/api/v1/departments/" + bDepartment + "/workspaces"));
        assertNotFound(get(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.bWorkspace()));
        JsonNode departments = list(fixture.aAdmin(), "/api/v1/departments");
        assertThat(departments.toString()).doesNotContain("B 秘密部门");
    }

    /** TEN-022：用他租户部门/空间 ID 创建或修改被拒，不产生跨租户关联。 */
    @Test
    @DisplayName("TEN-022 跨租户部门/空间写入被拒")
    void ten022_crossTenantWritesAreRejected() {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID bDepartment = createDepartment(fixture.aPlatform(), fixture.bTenant(), "B 部门" + randomSuffix());

        ApiTestClient.ApiResponse create = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "越界空间", "departmentId", bDepartment.toString(), "visibility", "DEPT"),
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken(),
                Map.of());
        assertThat(create.status()).isEqualTo(404);
        assertThat(client.json(create).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(create.body()).doesNotContain("B 部门");

        ApiTestClient.ApiResponse patch = client.patch(
                "/api/v1/workspaces/" + fixture.bWorkspace(),
                client.jsonBody("name", "跨租户改名"),
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken());
        assertNotFound(patch);

        ApiTestClient.ApiResponse pathCreate =
                createDepartmentWorkspaceRaw(fixture.aAdmin(), bDepartment, "越界路径空间", "DEPT");
        assertNotFound(pathCreate);

        Long crossRows = jdbcClient
                .sql("SELECT COUNT(*) FROM workspace WHERE tenant_id = :tenantId AND department_id = :departmentId")
                .param("tenantId", fixture.aTenant())
                .param("departmentId", BinaryUuids.toBytes(bDepartment))
                .query(Long.class)
                .single();
        assertThat(crossRows).isZero();
    }

    /** TEN-023：把 B 租户账号加入 A 租户部门/CUSTOM 空间返回 404，不泄露账号存在性。 */
    @Test
    @DisplayName("TEN-023 跨租户账号加入被拒")
    void ten023_crossTenantAccountCannotJoin() {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID aDepartment = createDepartment(fixture.aPlatform(), fixture.aTenant(), "A 部门" + randomSuffix());
        UUID bUserId = fixture.bMemberId();

        ApiTestClient.ApiResponse departmentAdd =
                addDepartmentMember(fixture.aPlatform(), fixture.aTenant(), aDepartment, bUserId);
        assertThat(departmentAdd.status()).isEqualTo(404);
        assertThat(client.json(departmentAdd).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(departmentAdd.body()).doesNotContain("b-member");

        ApiTestClient.ApiResponse workspaceAdd =
                addWorkspaceMember(fixture.aAdmin(), fixture.aCustomWorkspace(), bUserId);
        assertThat(workspaceAdd.status()).isIn(404, 409);
        assertThat(workspaceAdd.body()).doesNotContain("b-member");
        assertThat(userDepartmentOrNull(fixture.aTenant(), bUserId)).isNull();
    }

    /** TEN-024：部门/空间审计事件的 tenant_id 与 actor 一致，details 无 PII。 */
    @Test
    @DisplayName("TEN-024 部门/空间审计租户与白名单")
    void ten024_departmentAndWorkspaceAuditAreTenantScoped() {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID department = createDepartment(fixture.aPlatform(), fixture.aTenant(), "审计部门" + randomSuffix());
        JsonNode workspace = createWorkspaceAsAdmin(fixture.aAdmin(), department, "审计空间", "DEPT");

        assertThat(auditRows("department.created")).anySatisfy(row -> {
            if (department.toString().equals(row.targetId())) {
                assertThat(row.tenantId()).isEqualTo("platform");
                assertThat(row.details()).contains(fixture.aTenant()).contains(department.toString());
                assertThat(row.details()).doesNotContain("@").doesNotContain("password");
            }
        });
        assertThat(auditRows("workspace.created")).anySatisfy(row -> {
            if (workspace.path("id").asText().equals(row.targetId())) {
                assertThat(row.tenantId()).isEqualTo(fixture.aTenant());
                assertThat(row.details()).contains(department.toString()).contains("DEPT");
                assertThat(row.details()).doesNotContain("@").doesNotContain("password");
            }
        });
    }

    /** TEN-025：读取/修改他租户空间成员统一 404。 */
    @Test
    @DisplayName("TEN-025 跨租户空间成员操作 404")
    void ten025_crossTenantWorkspaceMembersAreRejected() {
        CrossTenantFixture fixture = crossTenantFixture();
        ApiTestClient.ApiResponse list =
                get(fixture.aAdmin(), "/api/v1/workspaces/" + fixture.bCustomWorkspace() + "/members");
        assertNotFound(list);

        ApiTestClient.ApiResponse add =
                addWorkspaceMember(fixture.aAdmin(), fixture.bCustomWorkspace(), fixture.aAdminId());
        assertNotFound(add);

        ApiTestClient.ApiResponse remove =
                removeWorkspaceMember(fixture.aAdmin(), fixture.bCustomWorkspace(), fixture.bMemberId());
        assertNotFound(remove);
    }

    /** TEN-026：跨租户移交/停用部门被拒，目标租户数据不变。 */
    @Test
    @DisplayName("TEN-026 跨租户部门管理被拒")
    void ten026_crossTenantDepartmentAdministrationIsRejected() {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID aDepartment = createDepartment(fixture.aPlatform(), fixture.aTenant(), "A 部门" + randomSuffix());
        UUID bDepartment = createDepartment(fixture.aPlatform(), fixture.bTenant(), "B 目标部门");

        ApiTestClient.ApiResponse tenantAdminAttempt = client.post(
                adminDepartmentsPath(fixture.bTenant()) + "/" + bDepartment + "/disable",
                null,
                fixture.aAdmin().sessionCookie(),
                fixture.aAdmin().csrfToken(),
                Map.of());
        assertThat(tenantAdminAttempt.status()).isEqualTo(403);

        ApiTestClient.ApiResponse platformDeptMismatch = client.post(
                adminDepartmentsPath(fixture.bTenant()) + "/" + aDepartment + "/disable",
                null,
                fixture.aPlatform().sessionCookie(),
                fixture.aPlatform().csrfToken(),
                Map.of());
        assertThat(platformDeptMismatch.status()).isEqualTo(404);
        assertThat(client.json(platformDeptMismatch).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        String status = jdbcClient
                .sql("SELECT status FROM department WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.bTenant())
                .param("id", BinaryUuids.toBytes(bDepartment))
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("ACTIVE");
    }

    /** TEN-027：平台部门端点的目标租户 404/409、部门不匹配 404、角色 403 与响应字段边界。 */
    @Test
    @DisplayName("TEN-027 平台部门端点边界")
    void ten027_platformDepartmentEndpointsAreBounded() {
        CrossTenantFixture fixture = crossTenantFixture();
        UUID aDepartment = createDepartment(fixture.aPlatform(), fixture.aTenant(), "A 部门" + randomSuffix());

        ApiTestClient.ApiResponse missingTenant =
                get(fixture.aPlatform(), adminDepartmentsPath(uniqueTenantCode("missing")));
        assertThat(missingTenant.status()).isEqualTo(404);
        assertThat(client.json(missingTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        String suspended = uniqueTenantCode("susp");
        createTenant(fixture.aPlatform(), suspended);
        ApiTestClient.ApiResponse suspend = client.patch(
                "/api/v1/admin/tenants/" + suspended,
                client.jsonBody("status", "SUSPENDED"),
                fixture.aPlatform().sessionCookie(),
                fixture.aPlatform().csrfToken());
        assertThat(suspend.status()).as(suspend.body()).isEqualTo(200);
        ApiTestClient.ApiResponse suspendedList = get(fixture.aPlatform(), adminDepartmentsPath(suspended));
        assertThat(suspendedList.status()).isEqualTo(409);
        assertThat(client.json(suspendedList).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");

        assertNotFound(
                get(fixture.aPlatform(), adminDepartmentsPath(fixture.bTenant()) + "/" + aDepartment + "/members"));

        ApiTestClient.ApiResponse tenantAdmin = get(fixture.aAdmin(), adminDepartmentsPath(fixture.aTenant()));
        assertThat(tenantAdmin.status()).isEqualTo(403);

        JsonNode page = list(fixture.aPlatform(), adminDepartmentsPath(fixture.aTenant()));
        JsonNode department = page.path("items").get(0);
        assertThat(department.toString())
                .doesNotContain("conversationId")
                .doesNotContain("fileId")
                .doesNotContain("reportId")
                .doesNotContain("artifactId")
                .doesNotContain("workspaceId");
        List<String> fields =
                department.properties().stream().map(Map.Entry::getKey).sorted().toList();
        assertThat(fields).contains("id", "name", "status", "memberCount");

        // 平台管理员凭同一会话不能读取目标租户业务内容；缺少租户内权限时返回 403。
        for (ApiTestClient.ApiResponse content : List.of(
                get(fixture.aPlatform(), "/api/v1/workspaces/" + fixture.bWorkspace()),
                get(fixture.aPlatform(), "/api/v1/conversations/" + fixture.aConversation()))) {
            assertThat(content.status()).isIn(403, 404);
            assertThat(client.json(content).path("code").asText()).isIn("RICCON-AUTH-403", "RICCON-TENANT-404");
            assertThat(content.body()).doesNotContain("B 团队空间");
        }
    }

    /**
     * 构造跨租户夹具：租户 A 管理员/个人空间，租户 B 管理员/成员/个人空间/会话/文件/产物/CUSTOM 空间。
     *
     * @return 夹具
     */
    private CrossTenantFixture crossTenantFixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("xtena");
        String tenantB = uniqueTenantCode("xtenb");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession aAdmin = createAndLoginTenantAdmin(platform, tenantA);
        UUID aAdminId = UUID.fromString(userId(aAdmin));
        String aPersonal = personalWorkspace(aAdmin);

        UUID aDepartment = createDepartment(platform, tenantA, "A 部门" + randomSuffix());
        assertThat(addDepartmentMember(platform, tenantA, aDepartment, aAdminId).status())
                .isEqualTo(201);
        JsonNode aCustom = createDepartmentWorkspace(aAdmin, aDepartment, "A 指定成员空间", "CUSTOM");

        LoginSession bAdmin = createAndLoginTenantAdmin(platform, tenantB);
        UUID bAdminId = UUID.fromString(userId(bAdmin));
        String bPersonal = personalWorkspace(bAdmin);
        CreatedMember bMember = createMember(platform, tenantB);
        UUID bMemberId = bMember.id();
        String bMemberPersonal = personalWorkspace(bMember.session());

        String aConversation = createConversation(aAdmin, aPersonal);
        String bConversation = createConversation(bMember.session(), bMemberPersonal);
        jdbcClient
                .sql("UPDATE conversation SET title = 'B 租户会话标题' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantB)
                .param("id", BinaryUuids.toBytes(UUID.fromString(bConversation)))
                .update();

        String aFile = upload(aAdmin, aPersonal, "a-file.pdf", "application/pdf", TestFiles.pdf(), null)
                .path("id")
                .asText();
        String bFile = upload(
                        bMember.session(), bMemberPersonal, "b-tenant.pdf", "application/pdf", TestFiles.pdf(), null)
                .path("id")
                .asText();
        UUID bArtifact = seedArtifact(tenantB, bPersonal, bAdminId, "B 租户产物");

        UUID bDepartment = createDepartment(platform, tenantB, "B 部门" + randomSuffix());
        assertThat(addDepartmentMember(platform, tenantB, bDepartment, bAdminId).status())
                .isEqualTo(201);
        assertThat(addDepartmentMember(platform, tenantB, bDepartment, bMemberId)
                        .status())
                .isEqualTo(201);
        JsonNode bWorkspace = createDepartmentWorkspace(bAdmin, bDepartment, "B 团队空间", "DEPT");
        JsonNode bCustom = createDepartmentWorkspace(bAdmin, bDepartment, "B 指定成员空间", "CUSTOM");

        return new CrossTenantFixture(
                platform,
                tenantA,
                aAdmin,
                aAdminId,
                aPersonal,
                aConversation,
                aFile,
                aCustom.path("id").asText(),
                tenantB,
                bAdmin,
                bAdminId,
                bPersonal,
                bMemberPersonal,
                bMember,
                bMemberId,
                bConversation,
                bFile,
                bArtifact,
                bWorkspace.path("id").asText(),
                bCustom.path("id").asText());
    }

    /**
     * 通过 API 登记知识库文档。
     *
     * @param session 具备 knowledge.manage 的会话
     * @param workspaceId 空间
     * @param title 标题
     * @return 文档 ID
     */
    private String createKnowledgeDocument(LoginSession session, String workspaceId, String title) {
        String fileId = upload(session, workspaceId, "knowledge.pdf", "application/pdf", TestFiles.pdf(), null)
                .path("id")
                .asText();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", title, "fileId", fileId),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", "ten-knowledge-" + randomSuffix()));
        assertThat(response.status()).as(response.body()).isIn(201, 202);
        return client.json(response).path("id").asText();
    }

    /**
     * 确保 mock 模型目录存在。
     */
    private void ensureModelCatalog() {
        jdbcClient.sql("""
                        INSERT IGNORE INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, 'security-mock', 'Security Mock', 'OPENAI_COMPATIBLE', 'http://127.0.0.1:1', 'ACTIVE')
                        """).param("id", BinaryUuids.toBytes(PROVIDER_ID)).update();
        jdbcClient
                .sql("""
                        INSERT IGNORE INTO model
                            (id, provider_id, model_key, display_name, context_window, max_output_tokens, status)
                        VALUES (:id, :providerId, 'fake-model', 'Fake Model', 8192, 1024, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(MockWorkerServer.MODEL_ID))
                .param("providerId", BinaryUuids.toBytes(PROVIDER_ID))
                .update();
    }

    /**
     * mock Worker 回调：为运行补一条模型调用记录。
     *
     * @param request mock Worker 收到的请求
     */
    private void prepareModelInvocation(JsonNode request) {
        ensureModelCatalog();
        jdbcClient
                .sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, run_id, conversation_id, message_id, model_id,
                            model_key_snapshot, purpose, status, trace_id, started_at
                        ) VALUES (
                            :tenantId, :id, :runId, :conversationId, NULL, :modelId,
                            'fake-model', 'CHAT', 'STARTED', 'security-suite', NOW(6)
                        )
                        """)
                .param("tenantId", request.path("tenantId").asText())
                .param("id", BinaryUuids.toBytes(UUID.randomUUID()))
                .param(
                        "runId",
                        BinaryUuids.toBytes(
                                UUID.fromString(request.path("runId").asText())))
                .param(
                        "conversationId",
                        BinaryUuids.toBytes(
                                UUID.fromString(request.path("conversationId").asText())))
                .param("modelId", BinaryUuids.toBytes(MockWorkerServer.MODEL_ID))
                .update();
    }

    /**
     * 读取运行的租户。
     *
     * @param runId 运行 ID
     * @return 租户
     */
    private String agentRunTenant(String runId) {
        return jdbcClient
                .sql("SELECT tenant_id FROM agent_run WHERE id = :id")
                .param("id", BinaryUuids.toBytes(UUID.fromString(runId)))
                .query(String.class)
                .single();
    }

    /**
     * 直接调用 mock Worker 的内部流端点。
     *
     * @param token 服务令牌
     * @param tenant 请求体租户
     * @param runId 请求体运行
     * @param userId 请求体用户
     * @return HTTP 状态
     * @throws Exception 网络失败
     */
    private int callWorker(String token, String tenant, UUID runId, UUID userId) throws Exception {
        String body = client.jsonBody(
                "tenantId", tenant,
                "runId", runId.toString(),
                "userId", userId.toString(),
                "conversationId", UUID.randomUUID().toString(),
                "messages", List.of(Map.of("role", "user", "content", "hi")));
        HttpRequest request = HttpRequest.newBuilder(URI.create(WORKER.url() + "/internal/v1/chat/streams"))
                .header("Content-Type", "application/json")
                .header("X-Riccon-Service-Token", token)
                .header("X-Trace-Id", "security-trace-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    /**
     * 用错误密钥签发令牌。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param runId 运行
     * @return 无效令牌
     */
    private String invalidToken(String tenant, UUID userId, UUID runId) {
        String token = signServiceToken(tenant, userId, runId, Instant.now().plusSeconds(300));
        return token.substring(0, token.length() - 2) + "AA";
    }

    /**
     * 读取 ProblemDetail 的字段名与错误码。
     *
     * @param response 原始响应
     * @return 字段签名
     */
    private List<String> problemFields(ApiTestClient.ApiResponse response) {
        JsonNode body = client.json(response);
        return List.of(
                body.path("code").asText(),
                body.path("title").asText(),
                body.properties().stream()
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList()
                        .toString());
    }

    /**
     * 计算 3 次请求的平均耗时。
     *
     * @param action 请求动作
     * @return 平均耗时
     */
    private Duration averageDuration(Runnable action) {
        long start = System.nanoTime();
        for (int index = 0; index < 3; index += 1) {
            action.run();
        }
        return Duration.ofNanos((System.nanoTime() - start) / 3);
    }

    /**
     * 跨租户夹具。
     *
     * @param aPlatform 平台管理员
     * @param aTenant 租户 A
     * @param aAdmin 租户 A 管理员
     * @param aAdminId 租户 A 管理员 ID
     * @param aPersonal A 管理员个人空间
     * @param aConversation A 会话
     * @param aFile A 文件
     * @param aCustomWorkspace A CUSTOM 空间
     * @param bTenant 租户 B
     * @param bAdmin 租户 B 管理员
     * @param bAdminId 租户 B 管理员 ID
     * @param bPersonal B 管理员个人空间
     * @param bMemberPersonal B 普通成员个人空间
     * @param bMember B 普通成员
     * @param bMemberId B 普通成员 ID
     * @param bConversation B 会话
     * @param bFile B 文件
     * @param bArtifact B 产物
     * @param bWorkspace B 部门空间
     * @param bCustomWorkspace B CUSTOM 空间
     */
    private record CrossTenantFixture(
            LoginSession aPlatform,
            String aTenant,
            LoginSession aAdmin,
            UUID aAdminId,
            String aPersonal,
            String aConversation,
            String aFile,
            String aCustomWorkspace,
            String bTenant,
            LoginSession bAdmin,
            UUID bAdminId,
            String bPersonal,
            String bMemberPersonal,
            CreatedMember bMember,
            UUID bMemberId,
            String bConversation,
            String bFile,
            UUID bArtifact,
            String bWorkspace,
            String bCustomWorkspace) {}
}
