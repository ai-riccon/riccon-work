package com.riccon.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.crypto.EnvelopeException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * API 侧凭据加密器的格式与失败语义。
 *
 * <p>API 从 M04 起负责新增/轮换平台 key 时的加密；密文必须能被 Worker 的 {@code EnvelopeDecryptor}
 * 直接解开（跨服务往返见 Worker 模块的 {@code CrossServiceEnvelopeRoundTripTest}）。
 */
class CredentialCipherTest {

    /** 测试租户；平台凭据固定 {@code platform}。 */
    private static final String TENANT = "platform";

    /** 测试用途。 */
    private static final String PURPOSE = CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL;

    /** 测试 DEK 标识。 */
    private static final String KEY_ID = "dek-api-1";

    /** 测试明文凭据。 */
    private static final String API_KEY = "sk-test-api-cipher-0123456789abcdef";

    /** 随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 凭据加密器。 */
    private final CredentialCipher cipher = new CredentialCipher();

    /** 生成 32 字节随机 DEK；只用于测试。 */
    private static byte[] newDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    /** API 加密后可用同一 DEK 还原，且不把明文写入密文。 */
    @Test
    void encryptAndDecrypt_roundTrip() {
        byte[] dek = newDek();

        String ciphertext = cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, API_KEY);

        assertThat(ciphertext).startsWith("enc:v1:" + KEY_ID + ":").doesNotContain(API_KEY);
        assertThat(cipher.decrypt(TENANT, PURPOSE, KEY_ID, dek, ciphertext)).isEqualTo(API_KEY);
    }

    /** 跨租户替换密文必须认证失败。 */
    @Test
    void decrypt_crossTenant_fails() {
        byte[] dek = newDek();
        String ciphertext = cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, API_KEY);

        assertThatThrownBy(() -> cipher.decrypt("tenant-b", PURPOSE, KEY_ID, dek, ciphertext))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageNotContaining(API_KEY);
    }

    /** 篡改密文必须认证失败。 */
    @Test
    void decrypt_tampered_fails() {
        byte[] dek = newDek();
        String ciphertext = cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, API_KEY);
        byte[] payload = Base64.getDecoder().decode(ciphertext.substring(("enc:v1:" + KEY_ID + ":").length()));
        payload[payload.length - 1] ^= 0x01;
        String tampered = "enc:v1:" + KEY_ID + ":" + Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decrypt(TENANT, PURPOSE, KEY_ID, dek, tampered))
                .isInstanceOf(EnvelopeException.class);
    }

    /** 错误 key id 必须失败，不尝试其他 DEK。 */
    @Test
    void decrypt_wrongKeyId_fails() {
        byte[] dek = newDek();
        String ciphertext = cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, API_KEY);

        assertThatThrownBy(() -> cipher.decrypt(TENANT, PURPOSE, "dek-other", dek, ciphertext))
                .isInstanceOf(EnvelopeException.class);
    }

    /** 空明文由服务层拒绝，避免写入无意义的空凭据。 */
    @Test
    void encrypt_blankPlaintext_fails() {
        byte[] dek = newDek();

        assertThatThrownBy(() -> cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 解密结果返回字符串后清零中间字节，减少明文驻留。 */
    @Test
    void decrypt_returnsPlaintextString() {
        byte[] dek = newDek();
        String ciphertext = cipher.encrypt(TENANT, PURPOSE, KEY_ID, dek, API_KEY);
        byte[] copy = Arrays.copyOf(dek, dek.length);

        assertThat(cipher.decrypt(TENANT, PURPOSE, KEY_ID, copy, ciphertext)).isEqualTo(API_KEY);
    }
}
