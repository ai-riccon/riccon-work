package com.riccon.worker.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.research.persistence.ApprovalParamsKeyJdbc;
import com.riccon.worker.research.service.ApprovalParamsCrypto;
import com.riccon.worker.support.TestCredentials;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 审批参数信封加密：往返、懒建 DEK、篡改、缺失、错 key、跨租户与哈希校验。 */
class ApprovalParamsCryptoTest extends WorkerIntegrationTestBase {

    /** 加密服务。 */
    @Autowired
    private ApprovalParamsCrypto crypto;

    /** 测试租户。 */
    private static final String TENANT = "tenant-a";

    /** 另一租户。 */
    private static final String OTHER_TENANT = "tenant-b";

    /** 每个用例创建租户；tenant_data_key 外键依赖 tenant 行。 */
    @BeforeEach
    void createTenants() {
        data.createTenant(TENANT);
        data.createTenant(OTHER_TENANT);
    }

    /** 同租户同审批往返；密文与明文不同且使用租户 DEK。 */
    @Test
    void sealAndOpen_roundTrip() {
        UUID approvalId = UUID.randomUUID();
        String json = "{\"text\":\"market-data\",\"limit\":5}";
        String sha = Digests.sha256Hex(json);

        ApprovalParamsCrypto.Sealed sealed = inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), approvalId, json));
        Map<String, Object> opened = inTenant(
                TENANT, () -> crypto.open(TenantId.of(TENANT), approvalId, sealed.keyId(), sealed.ciphertext(), sha));

        assertThat(opened).containsEntry("text", "market-data").containsEntry("limit", 5);
        assertThat(sealed.ciphertext().length).isGreaterThan(json.getBytes(StandardCharsets.UTF_8).length);
        assertThat(new String(sealed.ciphertext(), StandardCharsets.UTF_8)).doesNotContain("market-data");
        assertThat(keyCount(TENANT)).isEqualTo(1);
    }

    /** 同一租户重复加密复用当前 DEK，不产生密钥膨胀。 */
    @Test
    void seal_reusesActiveTenantKey() {
        ApprovalParamsCrypto.Sealed first =
                inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), UUID.randomUUID(), "{\"a\":1}"));
        ApprovalParamsCrypto.Sealed second =
                inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), UUID.randomUUID(), "{\"b\":2}"));

        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(keyCount(TENANT)).isEqualTo(1);
    }

    /** 篡改密文必须认证失败，不能返回任何明文。 */
    @Test
    void open_tamperedCiphertext_fails() {
        UUID approvalId = UUID.randomUUID();
        String json = "{\"text\":\"secretish\"}";
        ApprovalParamsCrypto.Sealed sealed = inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), approvalId, json));
        byte[] tampered = sealed.ciphertext();
        tampered[tampered.length - 1] ^= 0x01;

        assertCryptoFailure(() -> inTenant(
                TENANT,
                () -> crypto.open(TenantId.of(TENANT), approvalId, sealed.keyId(), tampered, Digests.sha256Hex(json))));
    }

    /** 缺失密文、key id 或摘要都必须拒绝，禁止回退到预览明文。 */
    @Test
    void open_missingEnvelope_fails() {
        UUID approvalId = UUID.randomUUID();
        assertCryptoFailure(
                () -> inTenant(TENANT, () -> crypto.open(TenantId.of(TENANT), approvalId, null, null, null)));
        assertCryptoFailure(() -> inTenant(
                TENANT,
                () -> crypto.open(TenantId.of(TENANT), approvalId, "ap-missing", new byte[29], "a".repeat(64))));
    }

    /** 使用同一租户的另一把 DEK 解密必须失败（key id 选择与 GCM 认证双重绑定）。 */
    @Test
    void open_wrongKey_fails() {
        UUID approvalId = UUID.randomUUID();
        String json = "{\"text\":\"market-data\"}";
        ApprovalParamsCrypto.Sealed sealed = inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), approvalId, json));
        String otherKeyId = "ap-" + UUID.randomUUID().toString().replace("-", "");
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped = TestCredentials.wrap(
                TestCredentials.MASTER_KEY_BASE64, TENANT, ApprovalParamsKeyJdbc.PURPOSE, otherKeyId, dek);
        jdbcClient
                .sql("""
                        INSERT INTO tenant_data_key
                            (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
                        VALUES
                            (:tenantId, :keyId, :purpose, :wrappedDek, :wrapNonce, 'AES-256-GCM', :masterKeyId)
                        """)
                .param("tenantId", TENANT)
                .param("keyId", otherKeyId)
                .param("purpose", ApprovalParamsKeyJdbc.PURPOSE)
                .param("wrappedDek", wrapped.wrappedDek())
                .param("wrapNonce", wrapped.nonce())
                .param("masterKeyId", TestCredentials.MASTER_KEY_ID)
                .update();

        assertCryptoFailure(() -> inTenant(
                TENANT,
                () -> crypto.open(
                        TenantId.of(TENANT), approvalId, otherKeyId, sealed.ciphertext(), Digests.sha256Hex(json))));
    }

    /** 跨租户使用同一 key id 不可见，直接失败。 */
    @Test
    void open_crossTenant_fails() {
        UUID approvalId = UUID.randomUUID();
        String json = "{\"text\":\"market-data\"}";
        ApprovalParamsCrypto.Sealed sealed = inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), approvalId, json));

        assertCryptoFailure(() -> inTenant(
                OTHER_TENANT,
                () -> crypto.open(
                        TenantId.of(OTHER_TENANT),
                        approvalId,
                        sealed.keyId(),
                        sealed.ciphertext(),
                        Digests.sha256Hex(json))));
    }

    /** 哈希与解密结果不一致必须失败。 */
    @Test
    void open_hashMismatch_fails() {
        UUID approvalId = UUID.randomUUID();
        String json = "{\"text\":\"market-data\"}";
        ApprovalParamsCrypto.Sealed sealed = inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), approvalId, json));

        assertCryptoFailure(() -> inTenant(
                TENANT,
                () -> crypto.open(
                        TenantId.of(TENANT), approvalId, sealed.keyId(), sealed.ciphertext(), "f".repeat(64))));
    }

    /** 解密后仍含敏感键时失败，防止历史/异常数据进入工具调用。 */
    @Test
    void open_sensitivePlaintext_fails() {
        UUID approvalId = UUID.randomUUID();
        String json = "{\"apiKey\":\"should-not-run\"}";
        ApprovalParamsCrypto.Sealed sealed = inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), approvalId, json));

        assertCryptoFailure(() -> inTenant(
                TENANT,
                () -> crypto.open(
                        TenantId.of(TENANT),
                        approvalId,
                        sealed.keyId(),
                        sealed.ciphertext(),
                        Digests.sha256Hex(json))));
    }

    /** 超过重放预算的明文在加密入口直接拒绝。 */
    @Test
    void seal_oversized_fails() {
        String json = "{\"text\":\"" + "x".repeat(ApprovalParamsCrypto.MAX_PARAMS_BYTES) + "\"}";

        assertThatThrownBy(() -> inTenant(TENANT, () -> crypto.seal(TenantId.of(TENANT), UUID.randomUUID(), json)))
                .isInstanceOf(AgentException.class)
                .satisfies(error ->
                        assertThat(((AgentException) error).errorCode()).isEqualTo(AgentException.INVALID));
    }

    /** 断言统一加密失败错误码，且消息不含密文/明文。 */
    private static void assertCryptoFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(AgentException.class).satisfies(error -> {
            AgentException agent = (AgentException) error;
            assertThat(agent.errorCode()).isEqualTo(AgentException.CRYPTO);
            assertThat(agent.getMessage()).doesNotContain("market-data", "should-not-run");
        });
    }

    /** 在租户作用域内执行。 */
    private static <T> T inTenant(String tenant, Supplier<T> action) {
        return TenantContext.callWithTenant(TenantId.of(tenant), action);
    }

    /** 统计租户审批参数 DEK 数量。 */
    private int keyCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM tenant_data_key WHERE tenant_id = :tenantId AND purpose = :purpose")
                .param("tenantId", tenant)
                .param("purpose", ApprovalParamsKeyJdbc.PURPOSE)
                .query(Integer.class)
                .single();
    }
}
