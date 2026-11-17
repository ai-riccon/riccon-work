package com.riccon.worker.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.config.CryptoProperties;
import com.riccon.worker.crypto.domain.CredentialException;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.crypto.service.EnvelopeDecryptor;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestCredentials;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 信封解密格式、AAD 绑定与失败语义。 */
class EnvelopeDecryptorTest {

    private static final String KEY_ID = "dek-test";

    private static final String TENANT = "platform";

    /** 正常密文可解密为明文。 */
    @Test
    void decrypt_validCiphertext_returnsSecret() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);

        SecretValue value = fixture.decryptor().decrypt(fixture.credential(), fixture.dataKey());

        assertThat(value.reveal()).isEqualTo(TestCredentials.API_KEY);
        assertThat(value.toString()).isEqualTo(SecretValue.REDACTED);
    }

    /** 密文绑定租户；跨租户替换后失败。 */
    @Test
    void decrypt_tenantSwap_fails() {
        Fixture a = fixture("tenant-a", "secret-a");
        Fixture b = fixture("tenant-b", "secret-b");
        CredentialRecord swapped = new CredentialRecord(
                a.credential().id(),
                "tenant-b",
                a.credential().providerId(),
                "API_KEY",
                a.credential().ciphertext(),
                KEY_ID,
                TestCredentials.MASTER_KEY_ID,
                1);

        assertThatThrownBy(() -> b.decryptor().decrypt(swapped, b.dataKey()))
                .isInstanceOf(CredentialException.class)
                .hasMessageNotContaining("secret-a");
    }

    /** 密文前缀 keyId 与 DEK 不一致失败。 */
    @Test
    void decrypt_keyIdMismatch_fails() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);
        CredentialRecord wrong = new CredentialRecord(
                fixture.credential().id(),
                TENANT,
                fixture.credential().providerId(),
                "API_KEY",
                fixture.credential().ciphertext().replace("enc:v1:" + KEY_ID, "enc:v1:other"),
                "other",
                TestCredentials.MASTER_KEY_ID,
                1);

        assertThatThrownBy(() -> fixture.decryptor().decrypt(wrong, fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
    }

    /** 篡改密文触发 GCM 认证失败。 */
    @Test
    void decrypt_tamperedCiphertext_fails() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);
        String ciphertext = fixture.credential().ciphertext();
        String prefix = "enc:v1:" + KEY_ID + ":";
        byte[] payload = java.util.Base64.getDecoder().decode(ciphertext.substring(prefix.length()));
        payload[payload.length - 1] = (byte) (payload[payload.length - 1] ^ 0x01);
        String tampered = prefix + java.util.Base64.getEncoder().encodeToString(payload);
        CredentialRecord credential = new CredentialRecord(
                fixture.credential().id(),
                TENANT,
                fixture.credential().providerId(),
                "API_KEY",
                tampered,
                KEY_ID,
                TestCredentials.MASTER_KEY_ID,
                1);

        assertThatThrownBy(() -> fixture.decryptor().decrypt(credential, fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
    }

    /** 凭据与 DEK 租户不一致失败。 */
    @Test
    void decrypt_tenantMismatch_fails() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);
        CredentialRecord credential = new CredentialRecord(
                fixture.credential().id(),
                "tenant-x",
                fixture.credential().providerId(),
                "API_KEY",
                fixture.credential().ciphertext(),
                KEY_ID,
                TestCredentials.MASTER_KEY_ID,
                1);

        assertThatThrownBy(() -> fixture.decryptor().decrypt(credential, fixture.dataKey()))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("tenant mismatch");
    }

    /** 非 model-credential 用途拒绝解密。 */
    @Test
    void decrypt_wrongPurpose_fails() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);
        TenantDataKeyRecord key = new TenantDataKeyRecord(
                fixture.dataKey().tenantId(),
                fixture.dataKey().keyId(),
                "other-purpose",
                fixture.dataKey().wrappedDek(),
                fixture.dataKey().wrapNonce(),
                "AES-256-GCM",
                TestCredentials.MASTER_KEY_ID);

        assertThatThrownBy(() -> fixture.decryptor().decrypt(fixture.credential(), key))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("purpose");
    }

    /** 不支持的算法拒绝解密。 */
    @Test
    void decrypt_wrongAlgorithm_fails() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);
        TenantDataKeyRecord key = new TenantDataKeyRecord(
                fixture.dataKey().tenantId(),
                fixture.dataKey().keyId(),
                fixture.dataKey().purpose(),
                fixture.dataKey().wrappedDek(),
                fixture.dataKey().wrapNonce(),
                "AES-128-GCM",
                TestCredentials.MASTER_KEY_ID);

        assertThatThrownBy(() -> fixture.decryptor().decrypt(fixture.credential(), key))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("algorithm");
    }

    /** 非法前缀与 Base64 失败。 */
    @Test
    void decrypt_malformedCiphertext_fails() {
        Fixture fixture = fixture(TENANT, TestCredentials.API_KEY);
        CredentialRecord plain = new CredentialRecord(
                fixture.credential().id(),
                TENANT,
                fixture.credential().providerId(),
                "API_KEY",
                "plain-text",
                KEY_ID,
                TestCredentials.MASTER_KEY_ID,
                1);
        CredentialRecord badBase64 = new CredentialRecord(
                fixture.credential().id(),
                TENANT,
                fixture.credential().providerId(),
                "API_KEY",
                "enc:v1:" + KEY_ID + ":!!!",
                KEY_ID,
                TestCredentials.MASTER_KEY_ID,
                1);

        assertThatThrownBy(() -> fixture.decryptor().decrypt(plain, fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
        assertThatThrownBy(() -> fixture.decryptor().decrypt(badBase64, fixture.dataKey()))
                .isInstanceOf(CredentialException.class);
    }

    /** 构造测试夹具。 */
    private static Fixture fixture(String tenant, String apiKey) {
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped =
                TestCredentials.wrap(TestCredentials.MASTER_KEY_BASE64, tenant, TestCredentials.PURPOSE, KEY_ID, dek);
        TenantDataKeyRecord dataKey = new TenantDataKeyRecord(
                tenant,
                KEY_ID,
                TestCredentials.PURPOSE,
                wrapped.wrappedDek(),
                wrapped.nonce(),
                "AES-256-GCM",
                TestCredentials.MASTER_KEY_ID);
        CredentialRecord credential = new CredentialRecord(
                UUID.randomUUID(),
                tenant,
                UuidBinary.newV7(),
                "API_KEY",
                TestCredentials.encryptCredential(tenant, TestCredentials.PURPOSE, KEY_ID, dek, apiKey),
                KEY_ID,
                TestCredentials.MASTER_KEY_ID,
                1);
        MasterKeyProvider provider =
                new MasterKeyProvider(new CryptoProperties(TestCredentials.MASTER_KEY_BASE64, "", "mk-test", true));
        return new Fixture(new EnvelopeDecryptor(provider), credential, dataKey);
    }

    /**
     * 夹具。
     *
     * @param decryptor 解密器
     * @param credential 凭据
     * @param dataKey DEK
     */
    private record Fixture(EnvelopeDecryptor decryptor, CredentialRecord credential, TenantDataKeyRecord dataKey) {}
}
