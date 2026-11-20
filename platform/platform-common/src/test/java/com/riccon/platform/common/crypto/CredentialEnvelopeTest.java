package com.riccon.platform.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link CredentialEnvelope} 的格式、AAD 与失败语义。
 *
 * <p>测试固定 DEK 为仓库内的测试占位值；断言密文与 Worker 解密器期望的
 * {@code enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>} 完全一致，并覆盖跨租户、跨用途、
 * 篡改与 key id 不匹配等负向路径。
 */
class CredentialEnvelopeTest {

    /** 测试租户。 */
    private static final String TENANT = "platform";

    /** 测试用途。 */
    private static final String PURPOSE = CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL;

    /** 测试 DEK 标识。 */
    private static final String KEY_ID = "dek-test-1";

    /** 测试明文凭据。 */
    private static final String API_KEY = "sk-test-credential-0123456789abcdef";

    /** 随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成 32 字节随机 DEK；只用于测试。 */
    private static byte[] newDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    /** 标准加密夹具。 */
    private static String encrypt(byte[] dek) {
        return CredentialEnvelope.encrypt(TENANT, PURPOSE, KEY_ID, dek, API_KEY.getBytes(StandardCharsets.UTF_8));
    }

    /** 密文格式必须是 Worker 解析器支持的 {@code enc:v1:<keyId>:<Base64>}。 */
    @Test
    void encrypt_producesWorkerCompatibleFormat() {
        byte[] dek = newDek();

        String ciphertext = encrypt(dek);

        assertThat(ciphertext).startsWith("enc:v1:" + KEY_ID + ":");
        String payload = ciphertext.substring(("enc:v1:" + KEY_ID + ":").length());
        byte[] decoded = Base64.getDecoder().decode(payload);
        // payload = 12 字节 nonce + 明文长度 + 16 字节 tag。
        assertThat(decoded).hasSize(12 + API_KEY.length() + 16);
        assertThat(ciphertext).doesNotContain(API_KEY);
    }

    /** 加密后可还原明文。 */
    @Test
    void encryptAndDecrypt_roundTrip() {
        byte[] dek = newDek();
        String ciphertext = encrypt(dek);

        byte[] plaintext = CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, dek, ciphertext);

        assertThat(new String(plaintext, StandardCharsets.UTF_8)).isEqualTo(API_KEY);
    }

    /** 每次加密使用新 nonce，禁止确定性密文。 */
    @Test
    void encrypt_usesFreshNonce() {
        byte[] dek = newDek();

        String first = encrypt(dek);
        String second = encrypt(dek);

        assertThat(first).isNotEqualTo(second);
        byte[] firstPayload = Base64.getDecoder().decode(first.substring(("enc:v1:" + KEY_ID + ":").length()));
        byte[] secondPayload = Base64.getDecoder().decode(second.substring(("enc:v1:" + KEY_ID + ":").length()));
        assertThat(Arrays.copyOfRange(firstPayload, 0, 12)).isNotEqualTo(Arrays.copyOfRange(secondPayload, 0, 12));
    }

    /** AAD 必须与 ADR-0006 第 2 节一致。 */
    @Test
    void aad_matchesDocumentedFormat() {
        byte[] aad = CredentialEnvelope.aad(TENANT, PURPOSE, KEY_ID);

        assertThat(new String(aad, StandardCharsets.UTF_8)).isEqualTo("platform|model-credential|dek-test-1|1");
    }

    /** AAD 片段为空或包含分隔符时拒绝构造，避免不同组合拼出相同字节串。 */
    @Test
    void aad_invalidParts_fail() {
        assertThatThrownBy(() -> CredentialEnvelope.aad(" ", PURPOSE, KEY_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialEnvelope.aad("tenant|a", PURPOSE, KEY_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialEnvelope.aad(TENANT, PURPOSE, "dek:1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 跨租户替换密文必须认证失败。 */
    @Test
    void decrypt_crossTenant_fails() {
        byte[] dek = newDek();
        String ciphertext = encrypt(dek);

        assertThatThrownBy(() -> CredentialEnvelope.decrypt("tenant-b", PURPOSE, KEY_ID, dek, ciphertext))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("credential decryption failed");
    }

    /** 跨用途替换密文必须认证失败。 */
    @Test
    void decrypt_wrongPurpose_fails() {
        byte[] dek = newDek();
        String ciphertext = encrypt(dek);

        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, "approval-params", KEY_ID, dek, ciphertext))
                .isInstanceOf(EnvelopeException.class);
    }

    /** 密文任意位被篡改必须认证失败。 */
    @Test
    void decrypt_tamperedPayload_fails() {
        byte[] dek = newDek();
        String ciphertext = encrypt(dek);
        String prefix = "enc:v1:" + KEY_ID + ":";
        byte[] payload = Base64.getDecoder().decode(ciphertext.substring(prefix.length()));
        payload[payload.length - 1] ^= 0x01;
        String tampered = prefix + Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, dek, tampered))
                .isInstanceOf(EnvelopeException.class);
    }

    /** 密文携带的 keyId 与调用方期望不一致时拒绝解析，不尝试猜测其他 DEK。 */
    @Test
    void decrypt_wrongKeyId_fails() {
        byte[] dek = newDek();
        String ciphertext = encrypt(dek);

        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, "dek-other", dek, ciphertext))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("key id does not match");
    }

    /** 不支持的前缀与截断载荷必须失败，而不是返回部分明文。 */
    @Test
    void decrypt_malformedCiphertext_fails() {
        byte[] dek = newDek();

        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, dek, "plain-text"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("unsupported format");
        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, dek, "enc:v1:" + KEY_ID + ":!!!"))
                .isInstanceOf(EnvelopeException.class);
        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, dek, "enc:v1:" + KEY_ID + ":AAAA"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("truncated");
        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, dek, "enc:v1:only-key-id:"))
                .isInstanceOf(EnvelopeException.class);
    }

    /** DEK 长度不是 32 字节时拒绝加解密。 */
    @Test
    void invalidDek_fails() {
        assertThatThrownBy(() -> CredentialEnvelope.encrypt(
                        TENANT, PURPOSE, KEY_ID, new byte[16], API_KEY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EnvelopeException.class);
        assertThatThrownBy(() -> CredentialEnvelope.decrypt(TENANT, PURPOSE, KEY_ID, new byte[16], "enc:v1:x:AAAA"))
                .isInstanceOf(EnvelopeException.class);
    }

    /** 掩码只暴露末 4 位；过短的凭据不暴露任何字符。 */
    @Test
    void maskedHint_exposesOnlyTail() {
        assertThat(CredentialEnvelope.maskedHint(API_KEY)).isEqualTo("****cdef");
        assertThat(CredentialEnvelope.maskedHint("short")).isEqualTo("****");
        assertThat(CredentialEnvelope.maskedHint(null)).isEqualTo("****");
    }
}
