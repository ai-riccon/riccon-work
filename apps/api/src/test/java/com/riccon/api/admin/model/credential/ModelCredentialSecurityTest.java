package com.riccon.api.admin.model.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台 key 池的安全子集（SEC-M04-005 / SEC-M04-006）。
 *
 * <p>覆盖：明文 canary 在响应/列表/审计/数据库/错误中零出现、生命周期审计使用 platform + USER +
 * MODEL_CREDENTIAL 且 details 只含白名单键、审计写入失败时创建/停用整体回滚（fail-closed），以及
 * 租户管理员/员工/匿名访问的权限边界。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=",
            "riccon.model.allow-legacy-endpoints=true",
            "riccon.model.allow-http=true"
        })
@Tag("security")
class ModelCredentialSecurityTest extends ApiIntegrationTestSupport {

    /** 本测试类的 provider code。 */
    private static final String PROVIDER_CODE = "m04-b5-security";

    /** 目录中的测试端点。 */
    private static final String PROVIDER_BASE_URL = "https://10.20.30.42/v1";

    /** details JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 审计失败注入 trigger 名；只在本测试内存在。 */
    private static final String FAILURE_TRIGGER = "trg_m04b5_audit_failure";

    /** 每个生命周期动作允许的 details 键（ADR-0021 §7.4）。 */
    private static final Map<String, Set<String>> ALLOWED_DETAILS = Map.of(
            "model.credential.created", Set.of("providerId", "credentialId", "maskedHint", "status"),
            "model.credential.rotated",
                    Set.of("providerId", "credentialId", "previousCredentialId", "maskedHint", "status"),
            "model.credential.disabled", Set.of("providerId", "credentialId", "maskedHint", "status", "reason"),
            "model.credential.enabled", Set.of("providerId", "credentialId", "maskedHint", "status", "reason"),
            "model.credential.recovered", Set.of("providerId", "credentialId", "maskedHint", "reason"));

    /** provider ID。 */
    private UUID providerId;

    /** 每个测试重建目录。 */
    @BeforeEach
    void seedProvider() {
        cleanup();
        providerId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, 'M04 B5 Security', 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", PROVIDER_CODE)
                .param("baseUrl", PROVIDER_BASE_URL)
                .update();
    }

    /** SEC-M04-005：canary 明文在响应、列表、审计、数据库与错误中零出现。 */
    @Test
    @DisplayName("SEC-M04-005 canary key 不进入响应/列表/审计/数据库/错误")
    void secM04_005_canaryKeyNeverLeavesProcess() {
        LoginSession admin = loginPlatformAdmin();
        String canary = "sk-canary-m04b5-" + UUID.randomUUID().toString().replace("-", "");

        ApiTestClient.ApiResponse created = client.post(
                credentialsPath(),
                client.jsonBody("label", "canary", "apiKey", canary, "priority", 100),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).doesNotContain(canary);
        String credentialId = client.json(created).path("id").asText();

        ApiTestClient.ApiResponse list = client.get(credentialsPath(), admin.sessionCookie(), Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).doesNotContain(canary);

        ApiTestClient.ApiResponse duplicate = client.post(
                credentialsPath(),
                client.jsonBody("label", "canary-dup", "apiKey", canary, "priority", 100),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(duplicate.status()).as(duplicate.body()).isEqualTo(409);
        assertThat(duplicate.body()).doesNotContain(canary);

        List<AuditRow> createdAudits = auditsFor("model.credential.created", credentialId);
        assertThat(createdAudits).hasSize(1);
        assertThat(createdAudits.get(0).details()).doesNotContain(canary);

        // 数据库只保存信封密文与 HMAC 指纹；明文、ciphertext 内容与 fingerprint 都不包含 canary。
        Map<String, Object> stored = jdbcClient
                .sql("""
                        SELECT ciphertext, key_id, fingerprint FROM provider_credential
                         WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(UUID.fromString(credentialId)))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "ciphertext", rs.getString("ciphertext"),
                        "keyId", rs.getString("key_id"),
                        "fingerprint", rs.getString("fingerprint")))
                .single();
        assertThat(stored.get("ciphertext").toString()).startsWith("enc:v1:").doesNotContain(canary);
        assertThat(stored.get("keyId").toString()).doesNotContain(canary);
        assertThat(stored.get("fingerprint").toString()).matches("^[0-9a-f]{64}$");
    }

    /** SEC-M04-006：生命周期审计白名单 + 审计失败回滚。 */
    @Test
    @DisplayName("SEC-M04-006 生命周期审计白名单与审计失败 fail-closed")
    void secM04_006_lifecycleAuditsAndFailClosed() {
        LoginSession admin = loginPlatformAdmin();
        String firstKey = randomApiKey("first");
        String secondKey = randomApiKey("second");

        JsonNode created = createCredential(admin, "lifecycle", firstKey, 100);
        String firstId = created.path("id").asText();
        assertAudit("model.credential.created", firstId);

        ApiTestClient.ApiResponse rotated = post(admin, firstId, "rotate", secondKey);
        assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
        String secondId = client.json(rotated).path("id").asText();
        assertAudit("model.credential.rotated", secondId);

        assertThat(post(admin, secondId, "disable", null).status()).isEqualTo(200);
        assertAudit("model.credential.disabled", secondId);

        assertThat(post(admin, secondId, "enable", null).status()).isEqualTo(200);
        assertAudit("model.credential.enabled", secondId);

        // 模拟 Worker 401/403 自动失效，再恢复；审计动作必须是 recovered 且不含 status 键。
        jdbcClient
                .sql("""
                        UPDATE provider_credential SET status = 'INVALID', invalidated_at = UTC_TIMESTAMP(6)
                         WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(UUID.fromString(secondId)))
                .update();
        assertThat(post(admin, secondId, "enable", null).status()).isEqualTo(200);
        assertAudit("model.credential.recovered", secondId);

        // 审计失败：创建整体回滚，不留下凭据行、DEK 或审计（fingerprint 也不会占用）。
        String rollbackKey = randomApiKey("rollback");
        try {
            createAuditFailureTrigger("model.credential.created");
            ApiTestClient.ApiResponse failedCreate = client.post(
                    credentialsPath(),
                    client.jsonBody("label", "rollback", "apiKey", rollbackKey, "priority", 100),
                    admin.sessionCookie(),
                    admin.csrfToken(),
                    Map.of());
            assertThat(failedCreate.status()).as(failedCreate.body()).isEqualTo(500);
            assertThat(client.json(failedCreate).path("code").asText()).isEqualTo("RICCON-COMMON-500");
            assertThat(credentialCountByLabel("rollback")).isZero();
        } finally {
            dropAuditFailureTrigger();
        }

        // 审计失败：停用不生效，凭据保持 ACTIVE，也不产生 disabled 审计。
        try {
            createAuditFailureTrigger("model.credential.disabled");
            ApiTestClient.ApiResponse failedDisable = post(admin, secondId, "disable", null);
            assertThat(failedDisable.status()).as(failedDisable.body()).isEqualTo(500);
            assertThat(credentialStatus(secondId)).isEqualTo("ACTIVE");
            assertThat(auditsFor("model.credential.disabled", secondId)).hasSize(1);
        } finally {
            dropAuditFailureTrigger();
        }

        // trigger 移除后同一操作恢复；证明失败没有污染数据库会话。
        assertThat(post(admin, secondId, "disable", null).status()).isEqualTo(200);
        assertThat(auditsFor("model.credential.disabled", secondId)).hasSize(2);
    }

    /** 权限：租户管理员/员工不能读或写平台 key；匿名 401。 */
    @Test
    @DisplayName("平台 key 池仅平台管理员可管理")
    void tenantRolesCannotManagePlatformCredentials() {
        LoginSession platformAdmin = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04b5sec");
        createTenant(platformAdmin, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platformAdmin, tenantCode);
        LoginSession member = createAndLoginMember(platformAdmin, tenantCode);
        UUID credentialId = UuidV7.generate();

        assertThat(list(tenantAdmin).status()).isEqualTo(403);
        assertThat(list(member).status()).isEqualTo(403);
        assertThat(client.get(credentialsPath(), null, Map.of()).status()).isEqualTo(401);

        // 写接口在权限拦截器阶段拒绝，不进入服务层、不回显任何配置内容。
        assertThat(create(tenantAdmin, "nope", randomApiKey("denied"), 100).status())
                .isEqualTo(403);
        assertThat(create(member, "nope", randomApiKey("denied"), 100).status()).isEqualTo(403);
        assertThat(post(tenantAdmin, credentialId.toString(), "rotate", randomApiKey("denied"))
                        .status())
                .isEqualTo(403);
        assertThat(post(tenantAdmin, credentialId.toString(), "disable", null).status())
                .isEqualTo(403);
        assertThat(post(member, credentialId.toString(), "enable", null).status())
                .isEqualTo(403);

        ApiTestClient.ApiResponse denied = list(tenantAdmin);
        assertThat(client.json(denied).path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(denied.body()).doesNotContain(PROVIDER_BASE_URL);
        assertThat(denied.body()).doesNotContain("maskedHint");
    }

    /** 断言审计信封与 details 白名单。 */
    private void assertAudit(String action, String credentialId) {
        List<AuditRow> rows = auditsFor(action, credentialId);
        assertThat(rows).as(action + " audit").hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.tenantId()).isEqualTo("platform");
        assertThat(row.actorType()).isEqualTo("USER");
        assertThat(row.targetType()).isEqualTo("MODEL_CREDENTIAL");
        assertThat(row.targetId()).isEqualTo(credentialId);
        assertThat(row.outcome()).isEqualTo("SUCCESS");
        JsonNode details = MAPPER.readTree(row.details());
        Set<String> allowed = ALLOWED_DETAILS.get(action);
        assertThat(allowed).as("测试需要覆盖全部生命周期动作").isNotNull();
        for (String key : details.properties().stream().map(Map.Entry::getKey).toList()) {
            assertThat(allowed).as(action + " details key " + key).contains(key);
        }
        assertThat(details.path("providerId").asText()).isEqualTo(providerId.toString());
        assertThat(details.path("credentialId").asText()).isEqualTo(credentialId);
        assertThat(details.path("maskedHint").asText()).startsWith("****");
    }

    /** 创建平台 key。 */
    private JsonNode createCredential(LoginSession admin, String label, String apiKey, int priority) {
        ApiTestClient.ApiResponse response = create(admin, label, apiKey, priority);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /** 新增请求。 */
    private ApiTestClient.ApiResponse create(LoginSession admin, String label, String apiKey, int priority) {
        return client.post(
                credentialsPath(),
                client.jsonBody("label", label, "apiKey", apiKey, "priority", priority),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
    }

    /** 生命周期动作。 */
    private ApiTestClient.ApiResponse post(LoginSession admin, String credentialId, String action, String apiKey) {
        String body =
                apiKey == null ? client.jsonBody() : client.jsonBody("apiKey", apiKey, "label", "security-" + action);
        return client.post(
                credentialsPath() + "/" + credentialId + "/" + action,
                body,
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
    }

    /** 掩码列表请求。 */
    private ApiTestClient.ApiResponse list(LoginSession session) {
        return client.get(credentialsPath(), session.sessionCookie(), Map.of());
    }

    /** 凭据列表路径。 */
    private String credentialsPath() {
        return "/api/v1/admin/model-providers/" + providerId + "/credentials";
    }

    /** 按 label 统计凭据行，用于断言回滚。 */
    private long credentialCountByLabel(String label) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM provider_credential
                         WHERE tenant_id = 'platform' AND provider_id = :providerId AND label = :label
                        """)
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("label", label)
                .query(Long.class)
                .single();
    }

    /** 读取凭据状态。 */
    private String credentialStatus(String credentialId) {
        return jdbcClient
                .sql("SELECT status FROM provider_credential WHERE tenant_id = 'platform' AND id = :id")
                .param("id", BinaryUuids.toBytes(UUID.fromString(credentialId)))
                .query(String.class)
                .single();
    }

    /** 注入“指定动作审计写入失败”的 trigger；只用于 fail-closed 断言。 */
    private void createAuditFailureTrigger(String action) {
        // 防御上次中断遗留同名 trigger；失败注入只属于本测试。
        dropAuditFailureTrigger();
        jdbcClient.sql("""
                        CREATE TRIGGER %s
                        BEFORE INSERT ON audit_event
                        FOR EACH ROW
                        BEGIN
                          IF NEW.action = '%s' THEN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected credential audit failure';
                          END IF;
                        END
                        """.formatted(FAILURE_TRIGGER, action)).update();
    }

    /** 移除审计失败 trigger；必须幂等，避免污染后续测试。 */
    private void dropAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER).update();
    }

    /** 查询指定动作且 details 包含目标凭据 ID 的审计行。 */
    private List<AuditRow> auditsFor(String action, String credentialId) {
        return auditRows(action).stream()
                .filter(row -> row.details() != null && row.details().contains(credentialId))
                .toList();
    }

    /** 生成测试用 apiKey；仅占位值。 */
    private static String randomApiKey(String tag) {
        return "sk-m04b5-" + tag + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 清理本测试的 provider 与凭据/测试 DEK。 */
    private void cleanup() {
        Optional<UUID> existing = jdbcClient
                .sql("SELECT id FROM model_provider WHERE code = :code")
                .param("code", PROVIDER_CODE)
                .query((rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                .optional();
        existing.ifPresent(id -> {
            jdbcClient
                    .sql("DELETE FROM provider_credential WHERE provider_id = :providerId")
                    .param("providerId", BinaryUuids.toBytes(id))
                    .update();
            jdbcClient
                    .sql("DELETE FROM model WHERE provider_id = :providerId")
                    .param("providerId", BinaryUuids.toBytes(id))
                    .update();
            jdbcClient
                    .sql("DELETE FROM model_provider WHERE id = :providerId")
                    .param("providerId", BinaryUuids.toBytes(id))
                    .update();
        });
        jdbcClient.sql("""
                        DELETE k FROM tenant_data_key k
                         LEFT JOIN provider_credential c
                           ON c.tenant_id = k.tenant_id AND c.key_id = k.key_id
                        WHERE k.tenant_id = 'platform' AND k.key_id LIKE 'mc-%' AND c.id IS NULL
                        """).update();
    }
}
