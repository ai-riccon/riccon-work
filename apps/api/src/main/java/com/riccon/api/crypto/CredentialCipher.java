package com.riccon.api.crypto;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * API 凭据信封加密器。
 *
 * <p>负责 DEK 级别的凭据加解密，产出与 Worker {@code EnvelopeDecryptor} 完全一致的
 * {@code enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>} 格式与 AAD；DEK 的包裹/解包由
 * {@link MasterKeyProvider} 完成。两者共同构成“API 加密、Worker 解密”的跨服务契约。
 *
 * <p>本类无状态、不缓存明文；调用方必须在写库后尽快丢弃返回的明文引用。
 */
public final class CredentialCipher {

    /**
     * 加密凭据明文。
     *
     * @param tenantId 租户 ID；平台凭据固定 {@code platform}
     * @param purpose 用途，模型凭据固定 {@link CredentialEnvelope#PURPOSE_MODEL_CREDENTIAL}
     * @param keyId DEK 标识
     * @param dek 32 字节数据密钥
     * @param plaintext 明文凭据
     * @return 密文格式字符串
     */
    public String encrypt(String tenantId, String purpose, String keyId, byte[] dek, String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("plaintext must not be empty");
        }
        return CredentialEnvelope.encrypt(tenantId, purpose, keyId, dek, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解密凭据密文。
     *
     * @param tenantId 租户 ID
     * @param purpose 用途
     * @param keyId DEK 标识
     * @param dek 32 字节数据密钥
     * @param ciphertext 密文格式字符串
     * @return 明文凭据
     */
    public String decrypt(String tenantId, String purpose, String keyId, byte[] dek, String ciphertext) {
        byte[] plaintext = CredentialEnvelope.decrypt(tenantId, purpose, keyId, dek, ciphertext);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
