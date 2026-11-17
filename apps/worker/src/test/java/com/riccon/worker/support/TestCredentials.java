package com.riccon.worker.support;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 测试用信封加密夹具。
 *
 * <p>独立于生产解密器实现加密，验证 AAD、nonce 与密文格式；主密钥仅用于测试，且不在生产已知
 * 测试密钥清单中。
 */
public final class TestCredentials {

    /** 32 字节测试主密钥的 Base64。 */
    public static final String MASTER_KEY_BASE64 = "cmljY29uLXdvcmtlci10ZXN0LW1hc3Rlci1rZXktMzI=";

    /** 另一把 32 字节密钥，用于轮换测试。 */
    public static final String PREVIOUS_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 测试 API key。 */
    public static final String API_KEY = "sk-test-worker-secret-0001";

    /** 主密钥 ID。 */
    public static final String MASTER_KEY_ID = "mk-test";

    /** DEK 用途。 */
    public static final String PURPOSE = "model-credential";

    /** 随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类禁止实例化。 */
    private TestCredentials() {}

    /**
     * 生成 32 字节 DEK。
     *
     * @return DEK
     */
    public static byte[] newDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    /**
     * 使用主密钥包裹 DEK。
     *
     * @param masterKeyBase64 主密钥
     * @param tenantId 租户
     * @param purpose 用途
     * @param keyId DEK 标识
     * @param dek DEK
     * @return 包裹结果
     */
    public static WrappedDek wrap(String masterKeyBase64, String tenantId, String purpose, String keyId, byte[] dek) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext =
                encrypt(Base64.getDecoder().decode(masterKeyBase64), nonce, dek, aad(tenantId, purpose, keyId));
        return new WrappedDek(ciphertext, nonce);
    }

    /**
     * 加密凭据。
     *
     * @param tenantId 租户
     * @param purpose 用途
     * @param keyId DEK 标识
     * @param dek DEK
     * @param plaintext 明文
     * @return 密文格式字符串
     */
    public static String encryptCredential(
            String tenantId, String purpose, String keyId, byte[] dek, String plaintext) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext =
                encrypt(dek, nonce, plaintext.getBytes(StandardCharsets.UTF_8), aad(tenantId, purpose, keyId));
        byte[] payload = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
        return "enc:v1:" + keyId + ":" + Base64.getEncoder().encodeToString(payload);
    }

    /**
     * AES-GCM 加密。
     *
     * @param key 密钥
     * @param nonce nonce
     * @param plaintext 明文
     * @param aad AAD
     * @return 密文与标签
     */
    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (Exception ex) {
            throw new IllegalStateException("test encryption failed", ex);
        }
    }

    /**
     * 构造 AAD。
     *
     * @param tenantId 租户
     * @param purpose 用途
     * @param keyId 标识
     * @return AAD
     */
    public static byte[] aad(String tenantId, String purpose, String keyId) {
        return (tenantId + "|" + purpose + "|" + keyId + "|1").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 包裹结果。
     *
     * @param wrappedDek 密文
     * @param nonce nonce
     */
    public record WrappedDek(byte[] wrappedDek, byte[] nonce) {}
}
