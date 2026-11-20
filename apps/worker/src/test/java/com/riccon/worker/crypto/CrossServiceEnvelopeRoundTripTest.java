package com.riccon.worker.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.crypto.MasterKeyRing;
import com.riccon.worker.config.CryptoProperties;
import com.riccon.worker.crypto.domain.CredentialException;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.crypto.service.EnvelopeDecryptor;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import com.riccon.worker.support.TestCredentials;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 跨服务信封契约：API 加密、Worker 解密。
 *
 * <p>API 的 {@code CredentialCipher} 直接委托 {@code platform-common} 的
 * {@link CredentialEnvelope}，DEK 由同一个 {@link MasterKeyRing} 包裹；本测试用共享实现模拟 API 侧
 * （与 API 服务代码走同一条路径），再用 Worker 真实生产代码 {@link EnvelopeDecryptor} 解密，
 * 证明密文格式、AAD 与主密钥轮换语义在 API/Worker 两侧一致。
 */
class CrossServiceEnvelopeRoundTripTest {

    /** 测试租户；平台凭据固定 platform。 */
    private static final String PLATFORM = "platform";

    /** 另一个租户，用于跨租户负向用例。 */
    private static final String OTHER_TENANT = "tenant-b";

    /** 测试 DEK 标识。 */
    private static final String KEY_ID = "dek-cross-1";

    /** 测试明文凭据。 */
    private static final String API_KEY = "sk-test-cross-service-0123456789abcdef";

    /** API 侧主密钥环：与 API 启动后的配置一致（相同主密钥标识与密钥）。 */
    private final MasterKeyRing apiRing =
            MasterKeyRing.load(TestCredentials.MASTER_KEY_BASE64, "", TestCredentials.MASTER_KEY_ID, "", false);

    /** Worker 侧解密器：真实生产实现，只替换密钥为测试占位值。 */
    private final EnvelopeDecryptor workerDecryptor = new EnvelopeDecryptor(
            new MasterKeyProvider(new CryptoProperties(TestCredentials.MASTER_KEY_BASE64, "", "mk-test", false)));

    /** API 加密的密文与包裹 DEK 可由 Worker 还原。 */
    @Test
    void apiEncrypt_workerDecrypt_roundTrip() {
        Fixture fixture = fixture(PLATFORM, PLATFORM);

        SecretValue decrypted = workerDecryptor.decrypt(fixture.credential(), fixture.dataKey());

        assertThat(decrypted.reveal()).isEqualTo(API_KEY);
        assertThat(decrypted.toString()).isEqualTo(SecretValue.REDACTED);
    }

    /** AAD 必须与 Worker 解密器完全一致，否则跨服务往返会认证失败。 */
    @Test
    void aad_matchesWorkerDecryptorExactly() {
        assertThat(CredentialEnvelope.aad(PLATFORM, "model-credential", KEY_ID))
                .isEqualTo(EnvelopeDecryptor.aad(PLATFORM, "model-credential", KEY_ID));
        assertThat(CredentialEnvelope.aad("tenant-a", "approval-params", KEY_ID))
                .isEqualTo(EnvelopeDecryptor.aad("tenant-a", "approval-params", KEY_ID));
    }

    /** 密文按其他租户的 AAD 加密时，即使记录租户一致也必须认证失败。 */
    @Test
    void crossTenantCiphertext_fails() {
        Fixture fixture = fixture(PLATFORM, OTHER_TENANT);

        assertThatThrownBy(() -> workerDecryptor.decrypt(fixture.credential(), fixture.dataKey()))
                .isInstanceOf(CredentialException.class)
                .hasMessageNotContaining(API_KEY);
    }

    /** DEK 按其他租户的 AAD 包裹时必须解包失败，跨租户不能复用平台密钥。 */
    @Test
    void crossTenantDek_fails() {
        Fixture fixture = fixture(OTHER_TENANT, PLATFORM);

        assertThatThrownBy(() -> workerDecryptor.decrypt(fixture.credential(), fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
    }

    /** 密文任意位被篡改必须认证失败。 */
    @Test
    void tamperedCiphertext_fails() {
        Fixture fixture = fixture(PLATFORM, PLATFORM);
        String prefix = CredentialEnvelope.PREFIX + KEY_ID + ":";
        byte[] payload = Base64.getDecoder().decode(fixture.ciphertext().substring(prefix.length()));
        payload[payload.length - 1] ^= 0x01;
        String tampered = prefix + Base64.getEncoder().encodeToString(payload);
        CredentialRecord record = new CredentialRecord(
                UUID.randomUUID(), PLATFORM, UUID.randomUUID(), "API_KEY", tampered, KEY_ID, "mk-test", 1);

        assertThatThrownBy(() -> workerDecryptor.decrypt(record, fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
    }

    /** 密文 key id 与 DEK 记录不一致时拒绝解密，不尝试其他 DEK。 */
    @Test
    void wrongKeyId_fails() {
        Fixture fixture = fixture(PLATFORM, PLATFORM);
        CredentialRecord record = new CredentialRecord(
                UUID.randomUUID(),
                PLATFORM,
                UUID.randomUUID(),
                "API_KEY",
                fixture.ciphertext(),
                "dek-other",
                "mk-test",
                1);

        assertThatThrownBy(() -> workerDecryptor.decrypt(record, fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
    }

    /** 轮换窗口：API 用上一把主密钥包裹的 DEK，Worker 同时持有当前与上一把时必须能解开。 */
    @Test
    void previousMasterKey_unwrapsRotatedDek() {
        byte[] dek = TestCredentials.newDek();
        MasterKeyRing oldRing = MasterKeyRing.load(TestCredentials.PREVIOUS_KEY_BASE64, "", "mk-old", "", false);
        MasterKeyRing.WrappedDek rotated =
                oldRing.wrap(dek, CredentialEnvelope.aad(PLATFORM, "model-credential", KEY_ID));
        String ciphertext = CredentialEnvelope.encrypt(
                PLATFORM, "model-credential", KEY_ID, dek, API_KEY.getBytes(StandardCharsets.UTF_8));
        TenantDataKeyRecord dataKey = new TenantDataKeyRecord(
                PLATFORM, KEY_ID, "model-credential", rotated.wrappedDek(), rotated.nonce(), "AES-256-GCM", "mk-old");
        CredentialRecord credential = new CredentialRecord(
                UUID.randomUUID(), PLATFORM, UUID.randomUUID(), "API_KEY", ciphertext, KEY_ID, "mk-old", 1);
        EnvelopeDecryptor rotatingDecryptor = new EnvelopeDecryptor(new MasterKeyProvider(new CryptoProperties(
                TestCredentials.MASTER_KEY_BASE64, TestCredentials.PREVIOUS_KEY_BASE64, "mk-test", false)));

        assertThat(rotatingDecryptor.decrypt(credential, dataKey).reveal()).isEqualTo(API_KEY);
    }

    /**
     * 构造 API 侧产物：DEK 包裹与密文分别使用给定租户，记录租户固定为记录侧租户。
     *
     * @param wrapTenant 包裹 DEK 时使用的租户
     * @param recordTenant 密文与记录使用的租户
     * @return 凭据、数据密钥与密文
     */
    private Fixture fixture(String wrapTenant, String recordTenant) {
        byte[] dek = TestCredentials.newDek();
        MasterKeyRing.WrappedDek wrapped =
                apiRing.wrap(dek, CredentialEnvelope.aad(wrapTenant, "model-credential", KEY_ID));
        String ciphertext = CredentialEnvelope.encrypt(
                recordTenant, "model-credential", KEY_ID, dek, API_KEY.getBytes(StandardCharsets.UTF_8));
        CredentialRecord credential = new CredentialRecord(
                UUID.randomUUID(), recordTenant, UUID.randomUUID(), "API_KEY", ciphertext, KEY_ID, "mk-test", 1);
        TenantDataKeyRecord dataKey = new TenantDataKeyRecord(
                recordTenant,
                KEY_ID,
                "model-credential",
                wrapped.wrappedDek(),
                wrapped.nonce(),
                "AES-256-GCM",
                "mk-test");
        return new Fixture(credential, dataKey, ciphertext);
    }

    /**
     * 测试夹具。
     *
     * @param credential 凭据记录
     * @param dataKey 数据密钥记录
     * @param ciphertext 密文
     */
    private record Fixture(CredentialRecord credential, TenantDataKeyRecord dataKey, String ciphertext) {}
}
