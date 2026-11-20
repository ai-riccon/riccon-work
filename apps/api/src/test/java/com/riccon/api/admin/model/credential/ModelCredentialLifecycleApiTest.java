package com.riccon.api.admin.model.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.crypto.CredentialCipher;
import com.riccon.api.crypto.MasterKeyProvider;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台 key 池生命周期与掩码行为（IT-MODEL-105 / IT-MODEL-106）。
 *
 * <p>使用真实 MySQL 与真实 HTTP 栈：覆盖多 key 停用、401 自动失效后的恢复、恢复审计、最后一把 key 可停用、
 * 租户级遗留凭据被忽略、轮换在途材料不被破坏、旧 key 掩码保持、HMAC fingerprint 去重与并发轮换。
 * 测试主密钥/pepper 来自 {@code application-test.yml}，全部为仓库占位值，不对应任何真实密钥。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=",
            "riccon.model.allow-legacy-endpoints=true",
            "riccon.model.allow-http=true"
        })
class ModelCredentialLifecycleApiTest extends ApiIntegrationTestSupport {

    /** 本测试类的 provider code；每个测试重建，避免跨测试漂移。 */
    private static final String PROVIDER_CODE = "m04-b5-lifecycle";

    /** 第二个 provider，用于 cross-provider 404。 */
    private static final String OTHER_PROVIDER_CODE = "m04-b5-other";

    /** 目录中的测试端点；DEMO 遗留模式下列表为空也允许 DB 目录。 */
    private static final String PROVIDER_BASE_URL = "https://10.20.30.41/v1";

    /** details JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 主密钥提供者，用于验证 fingerprint 与旧 key 在途解密。 */
    @Autowired
    private MasterKeyProvider masterKeyProvider;

    /** 凭据加密器，用于验证旧 key 密文在轮换后仍可解密。 */
    @Autowired
    private CredentialCipher credentialCipher;

    /** 平台 key 池服务，用于并发轮换用例。 */
    @Autowired
    private com.riccon.api.admin.model.credential.service.ModelCredentialService credentialService;

    /** 主 provider ID。 */
    private UUID providerId;

    /** 第二个 provider ID。 */
    private UUID otherProviderId;

    /** 每个测试重建目录与平台 key。 */
    @BeforeEach
    void seedCatalog() {
        cleanup();
        providerId = insertProvider(PROVIDER_CODE);
        otherProviderId = insertProvider(OTHER_PROVIDER_CODE);
    }

    /** IT-MODEL-105：多 key 停用/失效/恢复/全失效路径与 platform 审计。 */
    @Test
    @DisplayName("IT-MODEL-105 多 key 停用、401 失效恢复、全失效与平台租户审计")
    void itModel105_multiKeyLifecycle() {
        LoginSession admin = loginPlatformAdmin();
        String keyA = randomApiKey("a");
        String keyB = randomApiKey("b");

        JsonNode createdA = createCredential(admin, "primary", keyA, 10);
        JsonNode createdB = createCredential(admin, "backup", keyB, 20);
        String idA = createdA.path("id").asText();
        String idB = createdB.path("id").asText();
        // 新增响应不带 changed（契约中 changed 只属于停用/恢复）。
        assertThat(createdA.has("changed")).isFalse();

        // 列表只返回掩码：不得出现明文、密文或 DEK 标识。
        JsonNode list = listCredentials(admin);
        assertThat(list.path("total").asInt()).isEqualTo(2);
        JsonNode itemA = findById(list.path("items"), idA);
        JsonNode itemB = findById(list.path("items"), idB);
        assertThat(itemA).isNotNull();
        assertThat(itemB).isNotNull();
        assertThat(itemA.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(itemA.path("maskedHint").asText()).isEqualTo(lastFourMask(keyA));
        assertThat(itemB.path("maskedHint").asText()).isEqualTo(lastFourMask(keyB));
        assertThat(list.toString()).doesNotContain(keyA).doesNotContain(keyB);
        assertThat(itemA.has("ciphertext")).isFalse();
        assertThat(itemA.has("keyId")).isFalse();
        assertThat(itemA.has("masterKeyId")).isFalse();

        // 停用 A：新请求不再使用；审计归属 platform 且 details 只含 ID/掩码/状态。
        ApiTestClient.ApiResponse disabledA = postCredential(admin, idA, "disable", null);
        assertThat(disabledA.status()).as(disabledA.body()).isEqualTo(200);
        JsonNode disabledABody = client.json(disabledA);
        assertThat(disabledABody.path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabledABody.path("changed").asBoolean()).isTrue();
        List<AuditRow> disabledAudits = auditsFor("model.credential.disabled", idA);
        assertThat(disabledAudits).hasSize(1);
        AuditRow disabledAudit = disabledAudits.get(0);
        assertThat(disabledAudit.tenantId()).isEqualTo("platform");
        assertThat(disabledAudit.actorType()).isEqualTo("USER");
        assertThat(disabledAudit.targetType()).isEqualTo("MODEL_CREDENTIAL");
        assertThat(disabledAudit.details()).doesNotContain(keyA);
        assertThat(parseDetails(disabledAudit).path("maskedHint").asText()).isEqualTo(lastFourMask(keyA));

        // 幂等：再次停用 changed=false，不重复写审计。
        ApiTestClient.ApiResponse disabledAgain = postCredential(admin, idA, "disable", null);
        assertThat(disabledAgain.status()).as(disabledAgain.body()).isEqualTo(200);
        assertThat(client.json(disabledAgain).path("changed").asBoolean()).isFalse();
        assertThat(auditsFor("model.credential.disabled", idA)).hasSize(1);

        // 模拟 Worker 收到 401/403：直接置 INVALID 并记录连续失败；管理端恢复写 recovered。
        jdbcClient
                .sql("""
                        UPDATE provider_credential
                           SET status = 'INVALID', invalidated_at = UTC_TIMESTAMP(6), consecutive_failures = 2
                         WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(UUID.fromString(idB)))
                .update();
        JsonNode invalidList = listCredentials(admin);
        JsonNode invalidB = findById(invalidList.path("items"), idB);
        assertThat(invalidB.path("status").asText()).isEqualTo("INVALID");
        assertThat(invalidB.path("consecutiveFailures").asInt()).isEqualTo(2);

        ApiTestClient.ApiResponse recoveredB = postCredential(admin, idB, "enable", null);
        assertThat(recoveredB.status()).as(recoveredB.body()).isEqualTo(200);
        JsonNode recoveredBody = client.json(recoveredB);
        assertThat(recoveredBody.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(recoveredBody.path("changed").asBoolean()).isTrue();
        assertThat(recoveredBody.path("consecutiveFailures").asInt()).isZero();
        List<AuditRow> recoveredAudits = auditsFor("model.credential.recovered", idB);
        assertThat(recoveredAudits).hasSize(1);
        assertThat(parseDetails(recoveredAudits.get(0)).has("status")).isFalse();
        assertThat(recoveredAudits.get(0).details()).doesNotContain(keyB);

        // 从 DISABLED 恢复写 enabled，而不是 recovered。
        ApiTestClient.ApiResponse enabledA = postCredential(admin, idA, "enable", null);
        assertThat(enabledA.status()).as(enabledA.body()).isEqualTo(200);
        assertThat(client.json(enabledA).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(auditsFor("model.credential.enabled", idA)).hasSize(1);
        assertThat(auditsFor("model.credential.recovered", idA)).isEmpty();

        // 最后一把 ACTIVE key 允许停用（允许 provider 无可用 key，运行时由 Worker 切链/503）。
        assertThat(postCredential(admin, idB, "disable", null).status()).isEqualTo(200);
        ApiTestClient.ApiResponse lastDisabled = postCredential(admin, idA, "disable", null);
        assertThat(lastDisabled.status()).as(lastDisabled.body()).isEqualTo(200);
        assertThat(client.json(lastDisabled).path("changed").asBoolean()).isTrue();

        JsonNode finalList = listCredentials(admin);
        assertThat(finalList.path("total").asInt()).isEqualTo(2);
        assertThat(findById(finalList.path("items"), idA).path("status").asText())
                .isEqualTo("DISABLED");
        assertThat(findById(finalList.path("items"), idB).path("status").asText())
                .isEqualTo("DISABLED");
        assertThat(activeCredentialCount()).isZero();
    }

    /** IT-MODEL-105：租户级遗留凭据既不可列出，也不可按 ID 操作。 */
    @Test
    @DisplayName("IT-MODEL-105 租户级凭据被平台 key 池忽略且跨租户 404")
    void itModel105_tenantScopedCredentialIsIgnored() {
        LoginSession admin = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04b5");
        createTenant(admin, tenantCode);

        String tenantKeyId = "mc-tenant-" + UUID.randomUUID().toString().replace("-", "");
        jdbcClient
                .sql("""
                        INSERT INTO tenant_data_key (
                            tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
                        ) VALUES (:tenantId, :keyId, 'model-credential', X'00', X'000000000000',
                                  'AES-256-GCM', 'mk-test')
                        """)
                .param("tenantId", tenantCode)
                .param("keyId", tenantKeyId)
                .update();
        UUID tenantCredentialId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO provider_credential (
                            tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id,
                            version, status, label, fingerprint, masked_hint, priority
                        ) VALUES (
                            :tenantId, :id, :providerId, 'API_KEY', 'enc:v1:tenant-placeholder:AAAA',
                            :keyId, 'mk-test', 1, 'ACTIVE', 'tenant-owned', :fingerprint, '****test', 100
                        )
                        """)
                .param("tenantId", tenantCode)
                .param("id", BinaryUuids.toBytes(tenantCredentialId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("keyId", tenantKeyId)
                .param("fingerprint", "0".repeat(64))
                .update();

        JsonNode list = listCredentials(admin);
        assertThat(list.path("total").asInt()).isZero();
        assertThat(list.toString()).doesNotContain(tenantCredentialId.toString());

        ApiTestClient.ApiResponse disable = postCredential(admin, tenantCredentialId.toString(), "disable", null);
        assertThat(disable.status()).as(disable.body()).isEqualTo(404);
        assertThat(client.json(disable).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        // 跨 provider 同样统一 404：credentialId 必须同时匹配路径中的 providerId。
        JsonNode otherCredential =
                createCredentialOn(otherProviderId, admin, "other-provider", randomApiKey("other"), 100);
        ApiTestClient.ApiResponse crossProvider =
                postCredential(admin, otherCredential.path("id").asText(), "disable", null);
        assertThat(crossProvider.status()).as(crossProvider.body()).isEqualTo(404);
        assertThat(client.json(crossProvider).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** IT-MODEL-106：轮换插入新 key、旧 key 掩码保持、在途材料可继续解密、fingerprint 去重。 */
    @Test
    @DisplayName("IT-MODEL-106 轮换在途、旧 key 掩码与 HMAC fingerprint 409")
    void itModel106_rotationKeepsInFlightMaterialAndMasks() {
        LoginSession admin = loginPlatformAdmin();
        String oldKey = randomApiKey("old");
        String newKey = randomApiKey("new");
        JsonNode created = createCredential(admin, "rotating", oldKey, 5);
        String oldId = created.path("id").asText();

        // 模拟一次在途尝试：轮换前已读取密文与 DEK，并成功解密。
        Snapshot snapshot = snapshot(oldId);
        assertThat(decryptSnapshot(snapshot)).isEqualTo(oldKey);

        // 轮换请求不传 label，必须继承旧 key 的标签。
        ApiTestClient.ApiResponse rotated = client.post(
                credentialsPath() + "/" + oldId + "/rotate",
                client.jsonBody("apiKey", newKey),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
        JsonNode rotatedBody = client.json(rotated);
        String newId = rotatedBody.path("id").asText();
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(rotatedBody.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(rotatedBody.path("maskedHint").asText()).isEqualTo(lastFourMask(newKey));
        assertThat(rotatedBody.path("priority").asInt()).isEqualTo(5);
        assertThat(rotatedBody.path("label").asText()).isEqualTo("rotating");
        assertThat(rotatedBody.path("rotatedAt").isNull()).isFalse();
        assertThat(rotated.body()).doesNotContain(oldKey).doesNotContain(newKey);

        JsonNode list = listCredentials(admin);
        JsonNode oldItem = findById(list.path("items"), oldId);
        assertThat(oldItem.path("status").asText()).isEqualTo("DISABLED");
        assertThat(oldItem.path("maskedHint").asText()).isEqualTo(lastFourMask(oldKey));
        assertThat(findById(list.path("items"), newId).path("status").asText()).isEqualTo("ACTIVE");

        // 在途请求持有的旧材料在轮换后仍可解密；旧行密文未被改写。
        Snapshot afterRotation = snapshot(oldId);
        assertThat(afterRotation.ciphertext()).isEqualTo(snapshot.ciphertext());
        assertThat(decryptSnapshot(afterRotation)).isEqualTo(oldKey);

        List<AuditRow> rotatedAudits = auditsFor("model.credential.rotated", newId);
        assertThat(rotatedAudits).hasSize(1);
        JsonNode details = parseDetails(rotatedAudits.get(0));
        assertThat(details.path("previousCredentialId").asText()).isEqualTo(oldId);
        assertThat(details.path("maskedHint").asText()).isEqualTo(lastFourMask(newKey));
        assertThat(rotatedAudits.get(0).details()).doesNotContain(oldKey).doesNotContain(newKey);

        // 并发/状态冲突：已停用的旧 key 不能再轮换。
        ApiTestClient.ApiResponse rotateDisabled = postCredential(admin, oldId, "rotate", randomApiKey("again"));
        assertThat(rotateDisabled.status()).as(rotateDisabled.body()).isEqualTo(409);
        assertThat(client.json(rotateDisabled).path("reason").asText()).isEqualTo("CREDENTIAL_STATE_CONFLICT");

        // 轮换回旧明文与直接重复新增都命中 fingerprint 唯一键。
        ApiTestClient.ApiResponse rotateDuplicate = postCredential(admin, newId, "rotate", oldKey);
        assertThat(rotateDuplicate.status()).as(rotateDuplicate.body()).isEqualTo(409);
        assertThat(client.json(rotateDuplicate).path("reason").asText()).isEqualTo("DUPLICATE_CREDENTIAL");

        ApiTestClient.ApiResponse createDuplicate = postCreate(admin, "duplicate", newKey, 100);
        assertThat(createDuplicate.status()).as(createDuplicate.body()).isEqualTo(409);
        assertThat(client.json(createDuplicate).path("code").asText()).isEqualTo("RICCON-MODEL-409");
        assertThat(client.json(createDuplicate).path("reason").asText()).isEqualTo("DUPLICATE_CREDENTIAL");

        // fingerprint 是 pepper 参与的 HMAC-SHA-256 十六进制，不是明文或普通摘要。
        String fingerprint = jdbcClient
                .sql("SELECT fingerprint FROM provider_credential WHERE tenant_id = 'platform' AND id = :id")
                .param("id", BinaryUuids.toBytes(UUID.fromString(newId)))
                .query(String.class)
                .single();
        assertThat(fingerprint)
                .isEqualTo(masterKeyProvider.fingerprint(providerId.toString(), newKey))
                .matches("^[0-9a-f]{64}$");
    }

    /** 并发轮换只允许一个成功，另一个返回 409；旧行只被停用一次。 */
    @Test
    @DisplayName("IT-MODEL-106 并发轮换只有一个成功")
    void itModel106_concurrentRotationAllowsSingleWinner() throws Exception {
        LoginSession admin = loginPlatformAdmin();
        JsonNode created = createCredential(admin, "concurrent", randomApiKey("c0"), 100);
        UUID oldId = UUID.fromString(created.path("id").asText());
        com.riccon.api.identity.domain.RicconPrincipal actor = new com.riccon.api.identity.domain.RicconPrincipal(
                UuidV7.generate(), "platform", "platform-admin", "Platform Admin");
        com.riccon.api.identity.domain.RequestMeta meta =
                new com.riccon.api.identity.domain.RequestMeta("trace-b5-concurrent", null, null);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Object>> futures = List.of(
                    pool.submit(
                            () -> rotateConcurrently(providerId, oldId, actor, meta, ready, start, randomApiKey("c1"))),
                    pool.submit(() ->
                            rotateConcurrently(providerId, oldId, actor, meta, ready, start, randomApiKey("c2"))));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Object> future : futures) {
                outcomes.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream()
                            .filter(value ->
                                    value instanceof com.riccon.api.admin.model.credential.dto.ModelCredentialResponse)
                            .count())
                    .isEqualTo(1);
            assertThat(outcomes.stream()
                            .filter(value -> value
                                    instanceof
                                    com.riccon.api.admin.model.credential.domain.ModelCredentialConflictException)
                            .count())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        JsonNode list = listCredentials(admin);
        assertThat(list.path("total").asInt()).isEqualTo(2);
        assertThat(findById(list.path("items"), oldId.toString()).path("status").asText())
                .isEqualTo("DISABLED");
        assertThat(list.path("items").findValues("status").stream()
                        .filter(node -> "ACTIVE".equals(node.asText()))
                        .count())
                .isEqualTo(1);
    }

    /** 输入校验：长度、空白、label、priority 与未知 provider。 */
    @Test
    @DisplayName("新增 key 的字段级 400 与 provider 404")
    void createRejectsInvalidInput() {
        LoginSession admin = loginPlatformAdmin();

        assertThat(postCreate(admin, "short", "0123456789abcde", 100).status())
                .as("15 字符 apiKey 必须拒绝")
                .isEqualTo(400);
        assertThat(client.json(postCreate(admin, "long", "x".repeat(513), 100))
                        .path("field")
                        .asText())
                .isEqualTo("apiKey");
        assertThat(client.json(postCreate(admin, "blank", "   ", 100))
                        .path("reason")
                        .asText())
                .isEqualTo("INVALID_API_KEY");
        assertThat(client.json(postCreate(admin, " ", randomApiKey("label"), 100))
                        .path("reason")
                        .asText())
                .isEqualTo("INVALID_LABEL");
        assertThat(client.json(postCreate(admin, "priority", randomApiKey("prio"), 0))
                        .path("reason")
                        .asText())
                .isEqualTo("INVALID_PRIORITY");

        ApiTestClient.ApiResponse missingBody =
                client.post(credentialsPath(), null, admin.sessionCookie(), admin.csrfToken(), Map.of());
        assertThat(missingBody.status()).as(missingBody.body()).isEqualTo(400);
        assertThat(client.json(missingBody).path("field").asText()).isEqualTo("apiKey");

        ApiTestClient.ApiResponse missingProvider = client.post(
                "/api/v1/admin/model-providers/" + UUID.randomUUID() + "/credentials",
                client.jsonBody("label", "missing", "apiKey", randomApiKey("missing")),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(missingProvider.status()).as(missingProvider.body()).isEqualTo(404);
        assertThat(client.json(missingProvider).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        // 已是 ACTIVE 时恢复是幂等空操作。
        JsonNode active = createCredential(admin, "active", randomApiKey("active"), 100);
        ApiTestClient.ApiResponse enableActive =
                postCredential(admin, active.path("id").asText(), "enable", null);
        assertThat(enableActive.status()).as(enableActive.body()).isEqualTo(200);
        assertThat(client.json(enableActive).path("changed").asBoolean()).isFalse();

        // provider 被部署停用后：仍可列出并停用止血，但不能新增 key。
        jdbcClient
                .sql("UPDATE model_provider SET status = 'DISABLED' WHERE id = :providerId")
                .param("providerId", BinaryUuids.toBytes(providerId))
                .update();
        assertThat(listCredentials(admin).path("total").asInt()).isEqualTo(1);
        ApiTestClient.ApiResponse rejectedByProvider =
                postCreate(admin, "disabled-provider", randomApiKey("disabled"), 100);
        assertThat(rejectedByProvider.status()).as(rejectedByProvider.body()).isEqualTo(400);
        assertThat(client.json(rejectedByProvider).path("reason").asText()).isEqualTo("PROVIDER_NOT_ALLOWED");
        assertThat(postCredential(admin, active.path("id").asText(), "disable", null)
                        .status())
                .isEqualTo(200);
    }

    /** 新增并断言 201。 */
    private JsonNode createCredential(LoginSession admin, String label, String apiKey, int priority) {
        return createCredentialOn(providerId, admin, label, apiKey, priority);
    }

    /** 在指定 provider 下新增并断言 201。 */
    private JsonNode createCredentialOn(
            UUID targetProviderId, LoginSession admin, String label, String apiKey, int priority) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/model-providers/" + targetProviderId + "/credentials",
                client.jsonBody("label", label, "apiKey", apiKey, "priority", priority),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /** 发起新增请求，不预设状态码。 */
    private ApiTestClient.ApiResponse postCreate(LoginSession admin, String label, String apiKey, int priority) {
        return client.post(
                credentialsPath(),
                client.jsonBody("label", label, "apiKey", apiKey, "priority", priority),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
    }

    /** 发起生命周期动作；body 为 {@code null} 时不发送 JSON。 */
    private ApiTestClient.ApiResponse postCredential(
            LoginSession admin, String credentialId, String action, String apiKey) {
        String body =
                apiKey == null ? client.jsonBody() : client.jsonBody("apiKey", apiKey, "label", "rotated-" + action);
        return client.post(
                credentialsPath() + "/" + credentialId + "/" + action,
                body,
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
    }

    /** 读取掩码列表。 */
    private JsonNode listCredentials(LoginSession admin) {
        ApiTestClient.ApiResponse response = client.get(credentialsPath(), admin.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 组装 key 列表路径。 */
    private String credentialsPath() {
        return "/api/v1/admin/model-providers/" + providerId + "/credentials";
    }

    /** 查询 platform 下 ACTIVE key 数量，验证“最后一把可停用”。 */
    private long activeCredentialCount() {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM provider_credential
                         WHERE tenant_id = 'platform' AND provider_id = :providerId
                           AND credential_type = 'API_KEY' AND status = 'ACTIVE'
                        """)
                .param("providerId", BinaryUuids.toBytes(providerId))
                .query(Long.class)
                .single();
    }

    /**
     * 模拟在途请求已读取的密文、keyId 与包裹 DEK。
     *
     * @param credentialId 凭据 ID
     * @return 快照
     */
    private Snapshot snapshot(String credentialId) {
        return jdbcClient
                .sql("""
                        SELECT c.ciphertext, c.key_id, k.wrapped_dek, k.wrap_nonce
                          FROM provider_credential c
                          JOIN tenant_data_key k ON k.tenant_id = c.tenant_id AND k.key_id = c.key_id
                         WHERE c.tenant_id = 'platform' AND c.id = :id
                        """)
                .param("id", BinaryUuids.toBytes(UUID.fromString(credentialId)))
                .query((rs, rowNum) -> new Snapshot(
                        rs.getString("ciphertext"),
                        rs.getString("key_id"),
                        rs.getBytes("wrapped_dek"),
                        rs.getBytes("wrap_nonce")))
                .single();
    }

    /**
     * 用快照中的 keyId/包裹 DEK 解密；验证轮换不破坏在途材料。
     *
     * @param snapshot 在途快照
     * @return 明文
     */
    private String decryptSnapshot(Snapshot snapshot) {
        byte[] aad = CredentialEnvelope.aad("platform", CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL, snapshot.keyId());
        byte[] dek = masterKeyProvider.unwrap(snapshot.wrapNonce(), snapshot.wrappedDek(), aad);
        try {
            return credentialCipher.decrypt(
                    "platform",
                    CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL,
                    snapshot.keyId(),
                    dek,
                    snapshot.ciphertext());
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** 并发轮换任务；返回响应或冲突异常。 */
    private Object rotateConcurrently(
            UUID targetProviderId,
            UUID oldId,
            com.riccon.api.identity.domain.RicconPrincipal actor,
            com.riccon.api.identity.domain.RequestMeta meta,
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start,
            String apiKey)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return credentialService.rotate(
                    targetProviderId,
                    oldId,
                    new com.riccon.api.admin.model.credential.dto.ModelCredentialRotateRequest(apiKey, "concurrent"),
                    actor,
                    meta);
        } catch (com.riccon.api.admin.model.credential.domain.ModelCredentialConflictException exception) {
            return exception;
        }
    }

    /** 解析审计 details。 */
    private static JsonNode parseDetails(AuditRow row) {
        return MAPPER.readTree(row.details());
    }

    /** 查询指定动作且 details 包含目标凭据 ID 的审计行。 */
    private List<AuditRow> auditsFor(String action, String credentialId) {
        return auditRows(action).stream()
                .filter(row -> row.details() != null && row.details().contains(credentialId))
                .toList();
    }

    /** 按 ID 查找列表项。 */
    private static JsonNode findById(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 生成测试用 apiKey；仅占位值。 */
    private static String randomApiKey(String tag) {
        return "sk-m04b5-" + tag + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 末 4 位掩码。 */
    private static String lastFourMask(String apiKey) {
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 插入目录 provider。 */
    private UUID insertProvider(String code) {
        UUID id = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, :name, 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(id))
                .param("code", code)
                .param("name", "M04 B5 " + code)
                .param("baseUrl", PROVIDER_BASE_URL)
                .update();
        return id;
    }

    /** 清理本测试的 provider、凭据、测试 DEK 与遗留租户凭据。 */
    private void cleanup() {
        for (String code : List.of(PROVIDER_CODE, OTHER_PROVIDER_CODE)) {
            Optional<UUID> existing = jdbcClient
                    .sql("SELECT id FROM model_provider WHERE code = :code")
                    .param("code", code)
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
        }
        jdbcClient
                .sql("DELETE FROM provider_credential WHERE tenant_id <> 'platform' AND key_id LIKE 'mc-tenant-%'")
                .update();
        jdbcClient
                .sql("DELETE FROM tenant_data_key WHERE key_id LIKE 'mc-tenant-%'")
                .update();
        jdbcClient.sql("""
                        DELETE k FROM tenant_data_key k
                         LEFT JOIN provider_credential c
                           ON c.tenant_id = k.tenant_id AND c.key_id = k.key_id
                        WHERE k.tenant_id = 'platform' AND k.key_id LIKE 'mc-%' AND c.id IS NULL
                        """).update();
    }

    /**
     * 在途快照。
     *
     * @param ciphertext 密文
     * @param keyId DEK 标识
     * @param wrappedDek 包裹 DEK
     * @param wrapNonce 包裹 nonce
     */
    private record Snapshot(String ciphertext, String keyId, byte[] wrappedDek, byte[] wrapNonce) {}
}
